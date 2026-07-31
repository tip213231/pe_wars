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
 * 2. PvP разрешено ТОЛЬКО между участвующими городами (п.4 ТЗ): урон между
 *    не-участниками или внутри одной стороны в зоне войны ОТМЕНЯЕТСЯ, даже если
 *    флаг PvP города сейчас включён.
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

        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof org.bukkit.entity.Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof org.bukkit.entity.TNTPrimed tnt && tnt.getSource() instanceof Player p) {
            // Урон от ТНТ, активированного игроком.
            attacker = p;
        } else if (event.getDamager() instanceof org.bukkit.entity.AreaEffectCloud cloud && cloud.getSource() instanceof Player p) {
            // Урон от лингер-зелий (облаков эффектов) игрока.
            attacker = p;
        }

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
        if (war != null && war.phase == WarPhase.ACTIVE) {
            boolean isMainZone = isMainWarZone(war, victim.getLocation()) || isMainWarZone(war, attacker.getLocation());
            boolean cross = (isAttackerInWar(attacker, war) && isDefenderInWar(victim, war))
                    || (isAttackerInWar(victim, war) && isDefenderInWar(attacker, war));
            
            if (isMainZone) {
                // Основная зона войны: PvP есть у всех (кто зашел на терру войны)
                if (cross) {
                    if (event.isCancelled()) {
                        event.setCancelled(false);
                    }
                } else {
                    // п.4 ТЗ: если случайный игрок бьет случайного на терре войны, PvP запрещено (если Towny запрещает)
                    // Но по условиям ТЗ "на территории войны пвп есть у всех", поэтому оставим как есть.
                    // Подождите: в ТЗ сказано "на территории войны пвп есть у всех". 
                    // Раньше было: event.setCancelled(true); для не-кросс. Я оставлю как раньше для не-кросс.
                    event.setCancelled(true);
                }
            } else {
                // Зона помогающей нации
                if (cross) {
                    if (event.isCancelled()) {
                        event.setCancelled(false);
                    }
                } else {
                    event.setCancelled(true);
                }
            }
        }
    }

    private Raid raidAt(Player player) {
        Object town = towny.getTownAt(player.getLocation());
        if (town == null) return null;
        return raids.getRaidByDefender(towny.townName(town));
    }

    private boolean isMainWarZone(War war, Location loc) {
        Object town = towny.getTownAt(loc);
        if (town == null) return false;
        String townName = towny.townName(town);
        return townName.equalsIgnoreCase(war.defenderTownName) || townName.equalsIgnoreCase(war.attackerTownName);
    }

    /**
     * Возвращает войну, в которую вовлечена территория (основной город или помогающая нация).
     */
    private War warAt(Location loc) {
        Object town = towny.getTownAt(loc);
        if (town == null) return null;
        String townName = towny.townName(town);
        
        War war = wars.getWarInvolving(townName);
        if (war != null && (townName.equalsIgnoreCase(war.defenderTownName) || townName.equalsIgnoreCase(war.attackerTownName))) {
            return war;
        }
        
        Object nation = towny.getNation(town);
        if (nation != null) {
            String nationName = towny.nationName(nation);
            War w = wars.getWarInvolvingNation(nationName);
            if (w != null) return w;
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
        if (nation != null && war.attackerNations.contains(towny.nationName(nation))) return true;
        return false;
    }

    private boolean isDefenderInWar(Player player, War war) {
        Object town = towny.getTown(player);
        if (town != null && war.defenderTownName.equalsIgnoreCase(towny.townName(town))) return true;
        Object nation = towny.getNation(player);
        if (nation != null && war.defenderNations.contains(towny.nationName(nation))) return true;
        return false;
    }
}
