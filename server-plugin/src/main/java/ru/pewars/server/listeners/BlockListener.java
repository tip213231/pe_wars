package ru.pewars.server.listeners;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.Cancellable;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import ru.pewars.server.Config;
import ru.pewars.server.towny.TownyBridge;
import ru.pewars.server.raid.Raid;
import ru.pewars.server.raid.RaidManager;
import ru.pewars.server.raid.RaidPhase;
import ru.pewars.server.war.CaptureFlagManager;
import ru.pewars.server.war.War;
import ru.pewars.server.war.WarManager;
import ru.pewars.server.war.WarPhase;

/**
 * Правила блоков при рейде/войне (п.10/п.11 ТЗ).
 *
 * Строительство:
 *  - в ФАЗЕ ПОДГОТОВКИ — обычные правила Towny;
 *  - в АКТИВНОЙ фазе войны — участники могут строить, если war.allow-build (п.11 ТЗ);
 *  - в АКТИВНОЙ фазе рейда — АТАКУЮЩИЙ может строить, если raid.allow-build (п.10 ТЗ).
 *
 * Разрушение в активной фазе:
 *  - Рейд: атакующий ломает ТОЛЬКО блоки из raid.breakable-blocks + контейнеры из
 *    containers.breakable; остальное отменяется.
 *  - Война: участники ломают всё руками (если war.allow-break), включая контейнеры;
 *    защищённые контейнеры не разрушаются ТОЛЬКО взрывами и снарядами модов.
 *
 * Взрывы: в войне разрешены только при war.allow-explosions (п.11 ТЗ).
 */
public final class BlockListener implements Listener {
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final Config config;
    private final TownyBridge towny;
    private final RaidManager raids;
    private final WarManager wars;
    private final CaptureFlagManager captureFlags;

    public BlockListener(org.bukkit.plugin.java.JavaPlugin plugin, Config config, TownyBridge towny, RaidManager raids, WarManager wars,
                         CaptureFlagManager captureFlags) {
        this.plugin = plugin;
        this.config = config;
        this.towny = towny;
        this.raids = raids;
        this.wars = wars;
        this.captureFlags = captureFlags;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        // Активные флаги захвата обрабатывает CaptureFlagManager — не мешаем их ломать
        // (п.5 ТЗ: флаг могут ломать обе стороны).
        if (captureFlags.isActiveFlagAt(event.getBlock().getLocation())) return;

        Player player = event.getPlayer();
        Location loc = event.getBlock().getLocation();
        Material type = event.getBlock().getType();

        Raid raid = raidAt(loc);
        War war = warAt(loc);
        boolean activeRaid = raid != null && raid.phase == RaidPhase.ACTIVE;
        boolean activeWar = war != null && war.phase == WarPhase.ACTIVE;
        if (!activeRaid && !activeWar) return;

        if (activeWar) {
            boolean participant = isAttackerInWar(player, war) || isDefenderInWar(player, war)
                    || player.hasPermission("pewars.admin");
            if (!participant) {
                deny(event, player);
                return;
            }
            // п.11 ТЗ: разрушение блоков на войне — только если разрешено в конфиге.
            if (!config.warAllowBreak && !player.hasPermission("pewars.admin")) {
                deny(event, player);
                return;
            }
            // Контейнеры (сундуки, бочки и т.п.) на войне ЗАЩИЩЕНЫ ТОЛЬКО ОТ ВЗРЫВОВ
            // (см. handleExplosion) — ломать их руками УЧАСТНИКАМ МОЖНО.
            if (event.isCancelled()) event.setCancelled(false);
            event.setDropItems(true);
            return;
        }

        // Активный рейд (зона — город-защитник).
        if (isAttackerInRaid(player, raid)) {
            if (config.raidBreakable.contains(type) || isBreakableContainer(event.getBlock())) {
                if (event.isCancelled()) event.setCancelled(false);
                event.setDropItems(true);
                return;
            }
            deny(event, player);
            return;
        }
        // Защитники и прочие — запрещаем ломать во время активного рейда
        // (чтобы нельзя было прятать сундуки/лут в процессе).
        deny(event, player);
    }

    private void deny(BlockBreakEvent event, Player player) {
        event.setCancelled(true);
        warn(player, "break-blocked");
        refreshBlock(event.getBlock(), player);
    }

    /** Является ли блок контейнером из списка config.containers.breakable (с учётом модов). */
    private boolean isBreakableContainer(Block block) {
        if (block == null || config.breakableContainers.isEmpty()) return false;
        Material type = block.getType();
        if (type == null || type == Material.AIR) return false;
        NamespacedKey key;
        try {
            key = type.getKey();
        } catch (Throwable t) {
            return false;
        }
        if (key == null) return false;
        return config.breakableContainers.contains(key);
    }

    /**
     * Проверяет, является ли блок защищённым контейнером во время войны.
     * Такие блоки (сундуки, бочки, Create-хранилища) нельзя разрушать даже во время войны.
     */
    private boolean isWarProtectedContainer(Block block) {
        if (block == null || config.warProtectedContainers.isEmpty()) return false;
        Material type = block.getType();
        if (type == null || type == Material.AIR) return false;
        NamespacedKey key;
        try {
            key = type.getKey();
        } catch (Throwable t) {
            return false;
        }
        if (key == null) return false;
        return config.warProtectedContainers.contains(key);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Location loc = event.getBlock().getLocation();

        // Флаг захвата и запрет блоков возле флага обрабатывает CaptureFlagManager (HIGHEST).
        if (CaptureFlagManager.isCaptureFlagItem(event.getItemInHand())) return;

        Raid raid = raidAt(loc);
        War war = warAt(loc);
        boolean activeRaid = raid != null && raid.phase == RaidPhase.ACTIVE;
        boolean activeWar = war != null && war.phase == WarPhase.ACTIVE;
        if (!activeRaid && !activeWar) return;

        if (activeWar) {
            boolean participant = isAttackerInWar(player, war) || isDefenderInWar(player, war)
                    || player.hasPermission("pewars.admin");
            if (participant && (config.warAllowBuild || player.hasPermission("pewars.admin"))) {
                // п.11 ТЗ: строить на войне можно — переоткрываем отмену Towny.
                event.setCancelled(false);
                event.setBuild(true);
            } else {
                event.setCancelled(true);
                event.setBuild(false);
                warn(player, "place-blocked");
            }
            return;
        }

        // Активный рейд: п.10 ТЗ — АТАКУЮЩИЙ МОЖЕТ СТРОИТЬ (если разрешено в конфиге).
        if (isAttackerInRaid(player, raid)) {
            if (config.raidAllowBuild || player.hasPermission("pewars.admin")) {
                event.setCancelled(false);
                event.setBuild(true);
            } else {
                event.setCancelled(true);
                event.setBuild(false);
                warn(player, "place-blocked");
            }
            return;
        }
        // Защитники строят в своём городе по обычным правилам Towny — не вмешиваемся.
    }

    /**
     * ФИКС (оружие Superb Warfare и других модов): Towny и прочие защитные плагины не
     * только отменяют событие взрыва в городе, но и ВЫЧИЩАЮТ blockList() до нашего
     * HIGHEST-обработчика. Снимаем копию списка блоков на LOWEST (до фильтров Towny)
     * и восстанавливаем её в активной войне/рейде.
     */
    private final Map<Object, List<Block>> explosionSnapshots = new WeakHashMap<>();

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityExplodeEarly(EntityExplodeEvent event) {
        explosionSnapshots.put(event, new ArrayList<>(event.blockList()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockExplodeEarly(BlockExplodeEvent event) {
        explosionSnapshots.put(event, new ArrayList<>(event.blockList()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosion(event.getLocation(), event.blockList(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplosion(event.getBlock().getLocation(), event.blockList(), event);
    }

    private void handleExplosion(Location loc, java.util.List<Block> blocks, Cancellable event) {
        List<Block> original = explosionSnapshots.remove(event);

        Raid raid = raidAt(loc);
        War war = warAt(loc);
        boolean activeRaid = raid != null && raid.phase == RaidPhase.ACTIVE;
        boolean activeWar = war != null && war.phase == WarPhase.ACTIVE;

        if (!activeRaid && !activeWar) return;

        // п.2/п.11 ТЗ: TNT и взрывы на войне — только если разрешено в конфиге.
        // Если запрещено — оставляем отмену Towny как есть и не восстанавливаем блоки.
        if (activeWar && !activeRaid && !config.warAllowExplosions) {
            return;
        }

        // Война или рейд активны в этом городе. Взрыв разрешён, но только для определённых блоков.
        if (event.isCancelled()) {
            event.setCancelled(false);
        }

        // Восстанавливаем блоки, которые Towny/другие плагины убрали из списка взрыва.
        if (original != null && original.size() > blocks.size()) {
            blocks.clear();
            blocks.addAll(original);
        }

        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block block = it.next();
            Material type = block.getType();
            boolean breakable;

            if (activeRaid) {
                // Рейд: только блоки из фильтра.
                breakable = config.raidBreakable.contains(type) || isBreakableContainer(block);
            } else {
                // Война: всё, кроме защищённых контейнеров (если разрушение разрешено).
                breakable = config.warAllowBreak && !isWarProtectedContainer(block);
            }

            // Исключаем флаг захвата из разрушения взрывом, его нужно ломать вручную.
            if (captureFlags.isActiveFlagAt(block.getLocation())) breakable = false;

            if (!breakable) {
                it.remove();
                refreshBlock(block, null);
            }
        }
    }

    /**
     * ФИКС (оружие Superb Warfare): часть модовых снарядов ломает блоки не взрывом,
     * а поштучно через EntityChangeBlockEvent (блок -> воздух). Towny отменяет такие
     * события в городе. Во время АКТИВНОЙ фазы войны переоткрываем их (если разрушение
     * разрешено) — кроме защищённых контейнеров и активного флага захвата.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof Player) return;
        if (!event.getTo().isAir()) return;

        Location loc = event.getBlock().getLocation();
        War war = warAt(loc);
        if (war == null || war.phase != WarPhase.ACTIVE) return;

        if (!config.warAllowBreak
                || isWarProtectedContainer(event.getBlock())
                || captureFlags.isActiveFlagAt(loc)) {
            event.setCancelled(true);
            refreshBlock(event.getBlock(), null);
            return;
        }
        if (event.isCancelled()) {
            event.setCancelled(false);
        }
    }

    private void refreshBlock(Block block, Player player) {
        if (block == null) return;
        Location loc = block.getLocation();
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            block.getState().update(true, true);
            if (player != null && player.isOnline()) {
                player.sendBlockChange(loc, block.getBlockData());
            } else if (loc.getWorld() != null) {
                for (Player p : loc.getWorld().getPlayers()) {
                    if (p.getLocation().distanceSquared(loc) <= 4096) {
                        p.sendBlockChange(loc, block.getBlockData());
                    }
                }
            }
        });
    }

    private Raid raidAt(Location loc) {
        Object town = towny.getTownAt(loc);
        if (town == null) return null;
        return raids.getRaidByDefender(towny.townName(town));
    }

    /** Зона войны: ВЕСЬ город-защитник и ВЕСЬ город-атакующий (включая все аванпосты). */
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

    private boolean isAttackerInRaid(Player player, Raid raid) {
        Object town = towny.getTown(player);
        return town != null && raid.attackerTownName.equalsIgnoreCase(towny.townName(town));
    }

    private boolean isAttackerInWar(Player player, War war) {
        Object town = towny.getTown(player);
        return town != null && war.attackerTownName.equalsIgnoreCase(towny.townName(town));
    }

    private boolean isDefenderInWar(Player player, War war) {
        Object town = towny.getTown(player);
        return town != null && war.defenderTownName.equalsIgnoreCase(towny.townName(town));
    }

    private void warn(Player player, String path) {
        String msg = config.chat(path);
        if (msg != null && !msg.isBlank()) {
            player.sendMessage(color(msg));
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
