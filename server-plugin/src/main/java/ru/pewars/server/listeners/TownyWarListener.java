package ru.pewars.server.listeners;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import ru.pewars.server.towny.TownyBridge;
import ru.pewars.server.war.War;
import ru.pewars.server.war.WarManager;
import ru.pewars.server.war.WarPhase;

/**
 * Перехватывает PlayerInteractEvent с приоритетом HIGHEST (ignoreCancelled=false),
 * чтобы разрешить участникам войны открывать двери/контейнеры и взаимодействовать
 * с блоками в зоне войны (оба города целиком, включая аванпосты) — даже если Towny
 * это отменил.
 *
 * Towny отменяет все block interactions в claimed чанках для чужих игроков через
 * ListenerTowny. Нам нужен приоритет выше, чтобы переопределить это.
 */
public final class TownyWarListener implements Listener {
    private final TownyBridge towny;
    private final WarManager wars;

    public TownyWarListener(TownyBridge towny, WarManager wars) {
        this.towny = towny;
        this.wars = wars;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        // Нас интересуют только клики по блокам.
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                && event.getAction() != Action.LEFT_CLICK_BLOCK) return;

        Block block = event.getClickedBlock();
        if (block == null) return;

        Player player = event.getPlayer();
        Location loc = block.getLocation();

        War war = warAt(loc);
        if (war == null || war.phase != WarPhase.ACTIVE) return;

        // Участник войны (атакующий или защитник) или админ — разрешаем взаимодействие.
        if (isAttackerInWar(player, war) || isDefenderInWar(player, war) || player.hasPermission("pewars.admin")) {
            event.setCancelled(false);
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.ALLOW);
        } else {
            // Третьи лица (нейтральные игроки) НЕ участвуют в войне — запрещаем взаимодействие в зоне войны.
            event.setCancelled(true);
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        }
    }

    /** Зона войны: ВЕСЬ город-защитник и ВЕСЬ город-атакующий (включая аванпосты). */
    private War warAt(Location loc) {
        Object town = towny.getTownAt(loc);
        if (town == null) return null;
        String townName = towny.townName(town);
        War war = wars.getWarInvolving(townName);
        if (war == null) return null;
        if (townName.equalsIgnoreCase(war.defenderTownName)
                || townName.equalsIgnoreCase(war.attackerTownName)) {
            return war;
        }
        return null;
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
