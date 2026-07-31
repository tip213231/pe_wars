package ru.pewars.server.listeners;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import ru.pewars.server.towny.TownyBridge;
import ru.pewars.server.raid.Raid;
import ru.pewars.server.raid.RaidManager;
import ru.pewars.server.raid.RaidPhase;
import ru.pewars.server.war.War;
import ru.pewars.server.war.WarManager;
import ru.pewars.server.war.WarPhase;

/**
 * PvP-правила в зонах активной войны/рейда (п.4/п.11 ТЗ):
 *
 * 1. PvP ПРИНУДИТЕЛЬНО включено между участниками войны во ВСЁМ городе-защитнике
 *    И во ВСЁМ городе-атакующем (включая все аванпосты обоих городов).
 *    Даже если Towny отменил урон (PvP-off флаг) — мы переоткрываем событие.
 *    Выключить PvP на время войны невозможно: тумблеры блокируются (CommandListener),
 *    а WarManager каждые несколько секунд принудительно возвращает флаг PvP=true.
 *
 * 2. PvP разрешено ТОЛЬКО между участвующими сторонами (п.4 ТЗ): урон между
 *    не-участниками или внутри одной стороны в зоне войны ОТМЕНЯЕТСЯ, даже если
 *    флаг PvP города сейчас включён. Правило одинаково и для основной зоны войны,
 *    и для территории помогающей нации.
 *
 * ВАЖНО: ignoreCancelled должен быть false — иначе Bukkit не доставит сюда
 * события, уже отменённые Towny (PvP-off), а весь смысл — их переоткрыть.
 */
public final class CombatListener implements Listener {
    private final TownyBridge towny;
    private final RaidManager raids;
    private final WarManager wars;

    public CombatListener(TownyBridge towny, RaidManager raids, WarManager wars) {
        this.towny = towny;
        this.raids = raids;
        this.wars = wars;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = resolveAttacker(event);
        if (attacker == null || victim.equals(attacker)) return;

        // --- Рейд: переоткрываем PvP между сторонами в городе-защитнике. ---
        Raid raid = raidAt(victim);
        if (raid == null) raid = raidAt(attacker);
        if (raid != null && raid.phase == RaidPhase.ACTIVE) {
            boolean cross = (isAttackerInRaid(attacker, raid) && isDefenderInRaid(victim, raid))
                    || (isAttackerInRaid(victim, raid) && isDefenderInRaid(attacker, raid));
            if (cross && event.isCancelled()) {
                event.setCancelled(false);
            }
            return;
        }

        // --- Война: PvP во всей зоне войны (оба города целиком, включая аванпосты). ---
        War war = warAt(victim.getLocation());
        if (war == null) war = warAt(attacker.getLocation());
        if (war == null || war.phase != WarPhase.ACTIVE) return;

        boolean cross = (isAttackerInWar(attacker, war) && isDefenderInWar(victim, war))
                || (isAttackerInWar(victim, war) && isDefenderInWar(attacker, war));

        // Между сторонами конфликта — принудительно разрешаем, во всех остальных
        // случаях (нейтралы, свои по своим) — запрещаем.
        event.setCancelled(!cross);
    }

    /** Определяет игрока-источника урона, включая стрелы, ТНТ и облака зелий. */
    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) {
            return p;
        }
        if (event.getDamager() instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof Player p) {
            return p;
        }
        if (event.getDamager() instanceof org.bukkit.entity.TNTPrimed tnt
                && tnt.getSource() instanceof Player p) {
            return p;
        }
        if (event.getDamager() instanceof org.bukkit.entity.AreaEffectCloud cloud
                && cloud.getSource() instanceof Player p) {
            return p;
        }
        return null;
    }

    /**
     * Зона рейда — это ТОЛЬКО территория города-защитника.
     * RaidManager индексирует рейд по обоим городам, поэтому обязательно
     * проверяем, что найденный город действительно защищается.
     */
    private Raid raidAt(Player player) {
        Object town = towny.getTownAt(player.getLocation());
        if (town == null) return null;
        String townName = towny.townName(town);
        Raid raid = raids.getRaidByDefender(townName);
        if (raid == null) return null;
        return raid.defenderTownName.equalsIgnoreCase(townName) ? raid : null;
    }

    /**
     * Возвращает войну, в которую вовлечена территория (основной город или помогающая нация).
     */
    private War warAt(Location loc) {
        Object town = towny.getTownAt(loc);
        if (town == null) return null;
        String townName = towny.townName(town);

        War war = wars.getWarInvolving(townName);
        if (war != null && (townName.equalsIgnoreCase(war.defenderTownName)
                || townName.equalsIgnoreCase(war.attackerTownName))) {
            return war;
        }

        Object nation = towny.getNation(town);
        if (nation != null) {
            return wars.getWarInvolvingNation(towny.nationName(nation));
        }
        return null;
    }

    private boolean isAttackerInRaid(Player player, Raid raid) {
        Object town = towny.getTown(player);
        return town != null && raid.attackerTownName.equalsIgnoreCase(towny.townName(town));
    }

    private boolean isDefenderInRaid(Player player, Raid raid) {
        Object town = towny.getTown(player);
        return town != null && raid.defenderTownName.equalsIgnoreCase(towny.townName(town));
    }

    private boolean isAttackerInWar(Player player, War war) {
        Object town = towny.getTown(player);
        if (town != null && war.attackerTownName.equalsIgnoreCase(towny.townName(town))) return true;
        Object nation = towny.getNation(player);
        return nation != null && war.attackerNations.contains(towny.nationName(nation));
    }

    private boolean isDefenderInWar(Player player, War war) {
        Object town = towny.getTown(player);
        if (town != null && war.defenderTownName.equalsIgnoreCase(towny.townName(town))) return true;
        Object nation = towny.getNation(player);
        return nation != null && war.defenderNations.contains(towny.nationName(nation));
    }
}
