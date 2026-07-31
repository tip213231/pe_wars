package ru.pewars.server.war;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import ru.pewars.server.Config;
import ru.pewars.server.api.events.TownChunkCaptureEvent;
import ru.pewars.server.storage.Database;
import ru.pewars.server.towny.TownyBridge;
import ru.pewars.server.towny.TownyBridge.ChunkCoord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Флаги захвата чанков (п.5/п.7 ТЗ):
 *  - рецепт крафта из конфига (capture.recipe);
 *  - голограмма над флагом с таймером обратного отсчёта;
 *  - флаг могут ломать ОБЕ стороны (мгновенно);
 *  - захват ПАУЗИТСЯ, пока в чанке есть защитники;
 *  - возле флага запрещено ставить блоки — при появлении блока захват прекращается;
 *  - центральный чанк: N флагов подряд, минимум M атакующих в чанке — победа;
 *  - состояние флагов сохраняется в БД (п.16).
 *
 * ВАЖНО про hasFlag: захват бывает двух видов. Обычный (hasFlag=true) создаётся
 * поставленным баннером. Безфлаговый (hasFlag=false) создаётся scanNoFlagCaptures()
 * просто потому, что атакующий стоит в приграничном чанке — физического баннера
 * в мире при этом НЕ СУЩЕСТВУЕТ. Любой код, который возвращает предмет флага
 * или стирает блок, обязан проверять hasFlag, иначе получается дюп (п.4).
 */
public final class CaptureFlagManager implements Listener {
    private static final String FLAG_KEY = "pe_wars_capture_flag";
    private static final NamespacedKey FLAG_PDC_KEY = NamespacedKey.fromString("pe_wars:" + FLAG_KEY);

    private final JavaPlugin plugin;
    private final Config config;
    private final TownyBridge towny;
    private final WarManager wars;

    private final Map<String, ActiveFlag> activeFlags = new HashMap<>();

    public CaptureFlagManager(JavaPlugin plugin, Config config, TownyBridge towny, WarManager wars) {
        this.plugin = plugin;
        this.config = config;
        this.towny = towny;
        this.wars = wars;
    }

    public void enable() {
        registerRecipe();
    }

    public void shutdown() {
        for (ActiveFlag flag : activeFlags.values()) {
            removeHolograms(flag);
        }
        activeFlags.clear();
    }

    /** П.2/п.5 ТЗ: рецепт крафта флага настраивается в конфиге (capture.recipe). */
    private void registerRecipe() {
        NamespacedKey key = config.captureFlagKey;
        try {
            Bukkit.removeRecipe(key);
        } catch (Throwable ignored) {
            // Рецепт ещё не был зарегистрирован — норма при первом старте.
        }
        ShapedRecipe recipe = new ShapedRecipe(key, createFlagItem(1));
        List<String> shape = config.flagRecipeShape;
        recipe.shape(shape.toArray(new String[0]));
        for (Map.Entry<Character, Material> e : config.flagRecipeIngredients.entrySet()) {
            recipe.setIngredient(e.getKey(), e.getValue());
        }
        Bukkit.addRecipe(recipe);
    }

    public ItemStack createFlagItem(int amount) {
        ItemStack item = new ItemStack(Material.RED_BANNER, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "\uD83D\uDEA9 Флаг захвата");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Ставится в чанке города во время",
                    ChatColor.GRAY + "активной войны. Если не сломать за "
                            + (config.captureFlagSeconds / 60) + " мин — чанк захвачен."));
            meta.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, FLAG_KEY), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean isCaptureFlagItem(ItemStack item) {
        if (item == null || item.getType() != Material.RED_BANNER) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || FLAG_PDC_KEY == null) return false;
        return meta.getPersistentDataContainer().has(FLAG_PDC_KEY, PersistentDataType.BYTE);
    }

    public boolean isActiveFlagAt(Location loc) {
        return !activeFlags.isEmpty() && activeFlags.containsKey(key(loc));
    }

    // ===================== Persistence (п.16) =====================

    public List<Database.FlagRow> snapshotRows() {
        List<Database.FlagRow> rows = new ArrayList<>();
        for (ActiveFlag flag : activeFlags.values()) {
            Database.FlagRow row = new Database.FlagRow();
            row.flagKey = flag.key;
            row.warId = flag.warId.toString();
            row.world = flag.location.getWorld() == null ? "" : flag.location.getWorld().getName();
            row.x = flag.location.getBlockX();
            row.y = flag.location.getBlockY();
            row.z = flag.location.getBlockZ();
            row.remainingMs = flag.remainingMs;
            row.central = flag.central;
            rows.add(row);
        }
        return rows;
    }

    public void restoreFlags(List<Database.FlagRow> rows) {
        if (rows == null) return;
        for (Database.FlagRow row : rows) {
            try {
                UUID warId = UUID.fromString(row.warId);
                War war = wars.getWar(warId);
                if (war == null || war.phase != WarPhase.ACTIVE) continue;
                World world = Bukkit.getWorld(row.world);
                if (world == null) continue;
                Location loc = new Location(world, row.x, row.y, row.z);
                Material t = loc.getBlock().getType();
                if (t != Material.RED_BANNER && t != Material.RED_WALL_BANNER) continue;
                ChunkCoord coord = new ChunkCoord(row.world, row.x >> 4, row.z >> 4);
                ActiveFlag flag = new ActiveFlag(row.flagKey, warId, coord, loc, row.remainingMs, row.central);
                activeFlags.put(flag.key, flag);
                spawnHolograms(flag, war);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to restore capture flag: " + e.getMessage());
            }
        }
    }

    // ===================== Placement =====================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        Location loc = block.getLocation();
        ItemStack item = event.getItemInHand();

        // П.5 ТЗ: возле активного флага запрещено размещать ЛЮБЫЕ блоки.
        if (!isCaptureFlagItem(item)) {
            ActiveFlag near = flagNear(loc, config.flagNoBlocksRadius);
            if (near != null) {
                event.setCancelled(true);
                event.setBuild(false);
                sendPlayerMessage(player, "capture-flag-near-block");
            }
            return;
        }

        // Установка флага захвата.
        Object townAt = towny.getTownAt(loc);
        War war = null;
        if (townAt != null) {
            war = wars.getWarByDefender(towny.townName(townAt));
        }
        boolean isWarActive = (war != null && war.phase == WarPhase.ACTIVE);

        if (!isWarActive) {
            event.setCancelled(true);
            sendPlayerMessage(player, "capture-flag-no-town");
            return;
        }
        // Флаг ставит ТОЛЬКО атакующий город.
        if (!isAttackerInWar(player, war)) {
            event.setCancelled(true);
            sendPlayerMessage(player, "capture-flag-wrong-side");
            return;
        }

        ChunkCoord coord = new ChunkCoord(
                loc.getWorld() == null ? "" : loc.getWorld().getName(),
                loc.getBlockX() >> 4, loc.getBlockZ() >> 4);

        if (war.isCaptured(coord)) {
            event.setCancelled(true);
            sendPlayerMessage(player, "capture-flag-already-captured");
            return;
        }
        // Один активный флаг на войну.
        if (hasActiveFlagForWar(war.id)) {
            event.setCancelled(true);
            sendPlayerMessage(player, "capture-flag-one-active");
            return;
        }
        // Линия фронта: захват распространяется от границы города.
        // Флаг можно ставить только на приграничный чанк или рядом
        // с уже оккупированным — в глубине города (центр) нельзя.
        if (!wars.canPlaceCaptureFlag(war, coord)) {
            event.setCancelled(true);
            sendPlayerMessage(player, "capture-flag-not-frontline");
            return;
        }

        boolean central = war.isCentralChunk(coord);
        // П.7 ТЗ: в центральном чанке должно быть минимум N атакующих.
        if (central) {
            int attackers = attackersInChunk(war, coord);
            if (attackers < config.centralMinAttackers) {
                event.setCancelled(true);
                player.sendMessage(color(config.chat("capture-central-need-attackers",
                        "min", String.valueOf(config.centralMinAttackers),
                        "current", String.valueOf(attackers))));
                return;
            }
        }

        event.setCancelled(false);
        event.setBuild(true);

        String key = key(loc);
        ActiveFlag flag = new ActiveFlag(key, war.id, coord, loc,
                config.captureFlagSeconds * 1000L, central);
        activeFlags.put(key, flag);
        spawnHolograms(flag, war);

        final org.bukkit.block.data.BlockData placedData = block.getBlockData();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Material t = block.getType();
            if (activeFlags.containsKey(key) && t != Material.RED_BANNER && t != Material.RED_WALL_BANNER) {
                block.setBlockData(placedData, false);
            }
        });

        if (central) {
            Bukkit.broadcastMessage(color(config.chat("capture-flag-placed-central",
                    "attacker", war.attackerTownName,
                    "town", war.defenderTownName,
                    "done", String.valueOf(war.centralFlagsCaptured),
                    "need", String.valueOf(config.centralFlagsRequired))));
        } else {
            Bukkit.broadcastMessage(color(config.chat("capture-flag-placed",
                    "attacker", war.attackerTownName,
                    "town", war.defenderTownName,
                    "time", (config.captureFlagSeconds / 60) + " мин")));
        }
    }

    /** Активный флаг в радиусе radius блоков от точки (тот же мир). */
    private ActiveFlag flagNear(Location loc, int radius) {
        if (activeFlags.isEmpty() || loc.getWorld() == null) return null;
        long r2 = (long) radius * radius;
        for (ActiveFlag flag : activeFlags.values()) {
            if (flag.location.getWorld() == null) continue;
            if (!flag.location.getWorld().equals(loc.getWorld())) continue;
            if (flag.location.distanceSquared(loc) <= r2) return flag;
        }
        return null;
    }

    // ===================== Breaking (обе стороны, мгновенно) =====================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Location loc = block.getLocation();
        String key = key(loc);
        ActiveFlag flag = activeFlags.get(key);
        if (flag == null) return;

        War war = wars.getWar(flag.warId);
        Player player = event.getPlayer();

        // П.5 ТЗ: флаг могут ломать ОБЕ стороны (атакующие и защитники) и админ.
        boolean participant = war != null
                && (isAttackerInWar(player, war) || isDefenderInWar(player, war));
        if (!participant && !player.hasPermission("pewars.admin")) {
            event.setCancelled(true);
            sendPlayerMessage(player, "capture-flag-cannot-break");
            return;
        }

        event.setCancelled(false);
        removeHolograms(flag);
        activeFlags.remove(key);

        // ФИКС (п.4, дюп): предмет возвращаем ТОЛЬКО если это настоящий флаг.
        // У безфлагового захвата ключ — координаты НОГ игрока, поэтому сюда
        // можно было попасть, сломав любой посторонний блок в этой точке:
        // игрок получал бесплатный флаг и терял законный дроп из-за setDropItems(false).
        if (flag.hasFlag) {
            event.setDropItems(false);
            if (loc.getWorld() != null) {
                loc.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.2, 0.5), createFlagItem(1));
            }
        }

        Bukkit.broadcastMessage(color(config.chat("capture-flag-broken",
                "town", war != null ? war.defenderTownName : "?")));
    }

    /**
     * П.5 ТЗ: при ПОЯВЛЕНИИ блока возле флага (падающий песок/гравий и др.)
     * захват немедленно прекращается. Блоки от игроков отменяются в onPlace.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (activeFlags.isEmpty()) return;
        if (event.getTo() == null || event.getTo().isAir()) return;
        ActiveFlag near = flagNear(event.getBlock().getLocation(), config.flagNoBlocksRadius);
        if (near == null) return;
        stopCapture(near, "capture-flag-block-appeared");
    }

    /**
     * Прекращение захвата: флаг исчезает, предмет возвращается.
     *
     * ФИКС (п.4, дюп): возврат предмета выполняется ТОЛЬКО если в мире реально
     * стоял баннер. Безфлаговый захват физического флага не имеет — раньше
     * достаточно было встать в приграничном чанке и кинуть под ноги песок,
     * чтобы получить бесплатный флаг захвата, и так раз в секунду.
     * Выдача предмета вложена в проверку типа блока: если баннер уже исчез
     * (взрыв, поршень, выгрузка чанка), предмет не создаётся из воздуха.
     */
    private void stopCapture(ActiveFlag flag, String chatPath) {
        activeFlags.remove(flag.key);
        removeHolograms(flag);

        if (flag.hasFlag) {
            Block block = flag.location.getBlock();
            Material t = block.getType();
            if (t == Material.RED_BANNER || t == Material.RED_WALL_BANNER) {
                block.setType(Material.AIR);
                if (flag.location.getWorld() != null) {
                    flag.location.getWorld().dropItemNaturally(
                            flag.location.clone().add(0.5, 0.2, 0.5), createFlagItem(1));
                }
            }
        }

        String msg = config.chat(chatPath);
        if (msg != null && !msg.isBlank()) {
            Bukkit.broadcastMessage(color(msg));
        }
    }

    // ===================== Tick (called each second) =====================

    public void tick() {
        if (activeFlags.isEmpty()) return;
        Iterator<Map.Entry<String, ActiveFlag>> it = activeFlags.entrySet().iterator();
        while (it.hasNext()) {
            ActiveFlag flag = it.next().getValue();

            Block block = flag.location.getBlock();
            if (flag.hasFlag) {
                Material blockType = block.getType();
                if (blockType != Material.RED_BANNER && blockType != Material.RED_WALL_BANNER) {
                    // Флаг исчез (сломан/взорван вне нашего обработчика).
                    War lostWar = wars.getWar(flag.warId);
                    Bukkit.broadcastMessage(color(config.chat("capture-flag-broken",
                            "town", lostWar != null ? lostWar.defenderTownName : "?")));
                    removeHolograms(flag);
                    it.remove();
                    continue;
                }
            }

            War war = wars.getWar(flag.warId);
            if (war == null || war.phase != WarPhase.ACTIVE) {
                if (flag.hasFlag) block.setType(Material.AIR);
                removeHolograms(flag);
                it.remove();
                continue;
            }

            int defenders = defendersInChunk(war, flag.coord);
            int attackers = attackersInChunk(war, flag.coord);

            // П.5 ТЗ: во время захвата на чанке не должно быть защитников — иначе ПАУЗА.
            // П.7 ТЗ: для центрального чанка также нужно минимум N атакующих в чанке.
            // ФИКС (п.3): безфлаговый захват держится присутствием атакующего.
            // Раньше атакующий мог уйти, а таймер докручивался в пустом чанке.
            boolean paused = defenders > 0
                    || (flag.central && attackers < config.centralMinAttackers)
                    || (!flag.hasFlag && attackers < 1);

            if (paused) {
                String reason;
                if (defenders > 0) {
                    reason = color(config.chat("capture-flag-paused"));
                } else {
                    reason = color(config.chat("capture-central-need-attackers",
                            "min", String.valueOf(Math.max(1, flag.central ? config.centralMinAttackers : 1)),
                            "current", String.valueOf(attackers)));
                }
                updateHologramTimer(flag, flag.remainingMs, reason);
                highlightChunk(flag);
                continue;
            }

            flag.remainingMs -= 1000L;
            if (flag.remainingMs > 0) {
                updateHologramTimer(flag, flag.remainingMs, null);
                highlightChunk(flag);
                long remainingSec = (flag.remainingMs + 999) / 1000;
                if (remainingSec % 30 == 0) {
                    long totalSec = Math.max(1, flag.hasFlag ? config.captureFlagSeconds : config.captureNoFlagSeconds);
                    long percent = Math.min(100, Math.max(0, (totalSec - remainingSec) * 100 / totalSec));
                    sendToParticipants(war, color(config.chat("capture-flag-progress",
                            "percent", String.valueOf(percent),
                            "time", remainingSec + " сек")));
                }
                continue;
            }

            // Захват завершён.
            if (flag.hasFlag) block.setType(Material.AIR);
            removeHolograms(flag);
            it.remove();
            completeCapture(war, flag);
        }
    }

    /**
     * Захват чанка БЕЗ флага: атакующий, который просто стоит в валидном
     * приграничном чанке города, запускает таймер захвата (config.captureNoFlagSeconds).
     * Вызывается раз в секунду вместе с tick().
     *
     * ФИКС (п.3): действует тот же лимит, что и для поставленных флагов —
     * один активный захват на войну. Раньше можно было пробежать по границе
     * города, засеять десяток параллельных захватов и уйти.
     */
    public void scanNoFlagCaptures() {
        for (War war : wars.activeWars()) {
            if (war.phase != WarPhase.ACTIVE) continue;
            if (hasActiveFlagForWar(war.id)) continue;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!isAttackerInWar(p, war)) continue;
                Location loc = p.getLocation();
                ChunkCoord coord = new ChunkCoord(
                        loc.getWorld() == null ? "" : loc.getWorld().getName(),
                        loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
                if (war.isCaptured(coord)) continue;
                if (hasActiveFlagForChunk(coord)) continue;
                if (!wars.canPlaceCaptureFlag(war, coord)) continue;

                boolean central = war.isCentralChunk(coord);
                if (central && attackersInChunk(war, coord) < config.centralMinAttackers) continue;

                String key = key(loc);
                ActiveFlag flag = new ActiveFlag(key, war.id, coord, loc,
                        config.captureNoFlagSeconds * 1000L, central, false);
                activeFlags.put(key, flag);
                spawnHolograms(flag, war);
                // Один захват на войну — дальше по этой войне не сканируем.
                break;
            }
        }
    }

    /** Уже идёт захват (с флагом или без) этого чанка? */
    private boolean hasActiveFlagForChunk(ChunkCoord coord) {
        for (ActiveFlag f : activeFlags.values()) {
            if (f.coord.world.equals(coord.world) && f.coord.x == coord.x && f.coord.z == coord.z) return true;
        }
        return false;
    }

    /** Успешное истечение таймера флага. */
    private void completeCapture(War war, ActiveFlag flag) {
        if (flag.central) {
            // П.7 ТЗ: центральный чанк требует N успешных флагов.
            war.centralFlagsCaptured++;
            Bukkit.getPluginManager().callEvent(new TownChunkCaptureEvent(
                    war.attackerTownName, war.defenderTownName,
                    flag.coord.world, flag.coord.x, flag.coord.z, true, false));

            if (war.centralCaptured(config.centralFlagsRequired)) {
                war.markCaptured(flag.coord);
                Object tb = towny.getTownBlock(flag.coord);
                Object attackerTown = war.attackerTown != null ? war.attackerTown : towny.getTownByName(war.attackerTownName);
                if (tb != null && attackerTown != null) {
                    towny.transferChunk(tb, attackerTown);
                }
                Bukkit.broadcastMessage(color(config.chat("capture-central-success",
                        "town", war.defenderTownName,
                        "attacker", war.attackerTownName)));
                // Захват центрального чанка = победа атакующего (п.7/п.8 ТЗ).
                wars.declareAttackerVictory(war);
            } else {
                Bukkit.broadcastMessage(color(config.chat("capture-central-flag-captured",
                        "done", String.valueOf(war.centralFlagsCaptured),
                        "need", String.valueOf(config.centralFlagsRequired),
                        "town", war.defenderTownName)));
            }
            return;
        }

        boolean isNew = war.markCaptured(flag.coord);
        if (isNew) {
            Object tb = towny.getTownBlock(flag.coord);
            Object attackerTown = war.attackerTown != null ? war.attackerTown : towny.getTownByName(war.attackerTownName);
            if (tb != null && attackerTown != null) {
                towny.transferChunk(tb, attackerTown);
            }
            String msg = config.chat("capture-flag-success",
                    "attacker", war.attackerTownName,
                    "town", war.defenderTownName);
            if (msg != null && !msg.isBlank()) {
                Bukkit.broadcastMessage(color(msg));
            }
            // П.17 ТЗ: событие захвата чанка.
            Bukkit.getPluginManager().callEvent(new TownChunkCaptureEvent(
                    war.attackerTownName, war.defenderTownName,
                    flag.coord.world, flag.coord.x, flag.coord.z, false, false));
        }
    }

    // ===================== Piston protection =====================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(org.bukkit.event.block.BlockPistonExtendEvent event) {
        if (movesActiveFlag(event.getBlocks())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(org.bukkit.event.block.BlockPistonRetractEvent event) {
        if (movesActiveFlag(event.getBlocks())) event.setCancelled(true);
    }

    private boolean movesActiveFlag(List<Block> blocks) {
        if (activeFlags.isEmpty()) return false;
        for (Block moved : blocks) {
            if (isActiveFlagAt(moved.getLocation())) return true;
        }
        return false;
    }

    // ===================== Players in chunk =====================

    private int defendersInChunk(War war, ChunkCoord coord) {
        return countTownPlayersInChunk(war.defenderTownName, coord);
    }

    private int attackersInChunk(War war, ChunkCoord coord) {
        return countTownPlayersInChunk(war.attackerTownName, coord);
    }

    private int countTownPlayersInChunk(String townName, ChunkCoord coord) {
        World world = Bukkit.getWorld(coord.world);
        if (world == null) return 0;
        int count = 0;
        for (Player p : world.getPlayers()) {
            if (p.isDead()) continue;
            if ((p.getLocation().getBlockX() >> 4) != coord.x
                    || (p.getLocation().getBlockZ() >> 4) != coord.z) continue;
            Object town = towny.getTown(p);
            if (town != null && townName.equalsIgnoreCase(towny.townName(town))) {
                count++;
            }
        }
        return count;
    }

    private void sendToParticipants(War war, String message) {
        if (message == null || message.isBlank()) return;
        for (Player online : Bukkit.getOnlinePlayers()) {
            Object town = towny.getTown(online);
            if (town == null) continue;
            String name = towny.townName(town);
            if (war.attackerTownName.equalsIgnoreCase(name) || war.defenderTownName.equalsIgnoreCase(name)) {
                online.sendMessage(message);
            }
        }
    }

    // ===================== Hologram + particles =====================

    private void spawnHolograms(ActiveFlag flag, War war) {
        World world = flag.location.getWorld();
        if (world == null) return;
        // П.5 ТЗ: голограмма НЕПОСРЕДСТВЕННО НАД флагом с таймером обратного отсчёта.
        Location base = flag.location.clone().add(0.5, 2.35, 0.5);
        String head = flag.central
                ? "&4\uD83D\uDEA9 ЦЕНТРАЛЬНЫЙ ЧАНК: &f" + war.attackerTownName + " &eзахватывает &f" + war.defenderTownName
                : "&c\uD83D\uDEA9 &f" + war.attackerTownName + " &eзахватывает чанк города &f" + war.defenderTownName;
        flag.hologramIds.add(spawnHologramLine(world, base, color(head)));
        flag.hologramIds.add(spawnHologramLine(world, base.clone().subtract(0, 0.3, 0), color(
                "&eДо захвата: &f" + formatTime(flag.remainingMs))));
    }

    private UUID spawnHologramLine(World world, Location loc, String text) {
        ArmorStand stand = world.spawn(loc, ArmorStand.class, as -> {
            as.setVisible(false);
            as.setMarker(true);
            as.setGravity(false);
            as.setSmall(true);
            as.setInvulnerable(true);
            as.setPersistent(false);
            as.setCustomName(text);
            as.setCustomNameVisible(true);
        });
        return stand.getUniqueId();
    }

    private void updateHologramTimer(ActiveFlag flag, long remainingMs, String pausedReason) {
        if (flag.hologramIds.size() < 2) return;
        Entity entity = Bukkit.getEntity(flag.hologramIds.get(1));
        if (entity != null) {
            if (pausedReason != null && !pausedReason.isBlank()) {
                entity.setCustomName(color("&c\u23F8 " + pausedReason + " &7(" + formatTime(remainingMs) + ")"));
            } else {
                entity.setCustomName(color("&eДо захвата: &f" + formatTime(remainingMs)));
            }
        }
    }

    private void removeHolograms(ActiveFlag flag) {
        for (UUID id : flag.hologramIds) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
        flag.hologramIds.clear();
    }

    private void highlightChunk(ActiveFlag flag) {
        World world = flag.location.getWorld();
        if (world == null) return;
        Particle.DustOptions dust = new Particle.DustOptions(Color.RED, 1.4f);
        double minX = flag.coord.x << 4;
        double minZ = flag.coord.z << 4;
        double y = flag.location.getY() + 1.0;
        for (int i = 0; i <= 16; i += 2) {
            spawnBorderColumn(world, dust, minX + i, y, minZ);
            spawnBorderColumn(world, dust, minX + i, y, minZ + 16);
            spawnBorderColumn(world, dust, minX, y, minZ + i);
            spawnBorderColumn(world, dust, minX + 16, y, minZ + i);
        }
    }

    private void spawnBorderColumn(World world, Particle.DustOptions dust, double x, double y, double z) {
        for (int dy = 0; dy < 3; dy++) {
            world.spawnParticle(Particle.DUST, x, y + dy, z, 1, 0, 0, 0, 0, dust);
        }
    }

    private String formatTime(long ms) {
        long totalSec = Math.max(0, (ms + 999) / 1000);
        return String.format("%d:%02d", totalSec / 60, totalSec % 60);
    }

    private String key(Location loc) {
        return (loc.getWorld() == null ? "" : loc.getWorld().getName())
                + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private boolean hasActiveFlagForWar(UUID warId) {
        if (warId == null || activeFlags.isEmpty()) return false;
        for (ActiveFlag flag : activeFlags.values()) {
            if (warId.equals(flag.warId)) return true;
        }
        return false;
    }

    private void sendPlayerMessage(Player player, String chatPath) {
        if (player == null) return;
        String text = config.chat(chatPath);
        if (text != null && !text.isBlank()) {
            player.sendMessage(color(text));
        }
    }

    private boolean isAttackerInWar(Player player, War war) {
        if (player == null || war == null) return false;
        Object town = towny.getTown(player);
        if (town != null && war.attackerTownName.equalsIgnoreCase(towny.townName(town))) return true;
        Object nation = towny.getNation(player);
        if (nation != null && war.attackerNations.contains(towny.nationName(nation))) return true;
        return false;
    }

    private boolean isDefenderInWar(Player player, War war) {
        if (player == null || war == null) return false;
        Object town = towny.getTown(player);
        if (town != null && war.defenderTownName.equalsIgnoreCase(towny.townName(town))) return true;
        Object nation = towny.getNation(player);
        if (nation != null && war.defenderNations.contains(towny.nationName(nation))) return true;
        return false;
    }

    private static final class ActiveFlag {
        final String key;
        final UUID warId;
        final ChunkCoord coord;
        final Location location;
        /** Оставшееся время захвата (не тикает во время паузы, п.5/п.7 ТЗ). */
        long remainingMs;
        final boolean central;
        /**
         * true — захват от реально поставленного баннера;
         * false — безфлаговый захват от присутствия атакующего, физического
         * блока в мире НЕТ. Не выдавать предмет флага, если здесь false (п.4).
         */
        final boolean hasFlag;
        final List<UUID> hologramIds = new ArrayList<>();

        ActiveFlag(String key, UUID warId, ChunkCoord coord, Location location, long remainingMs, boolean central) {
            this(key, warId, coord, location, remainingMs, central, true);
        }

        ActiveFlag(String key, UUID warId, ChunkCoord coord, Location location, long remainingMs, boolean central, boolean hasFlag) {
            this.key = key;
            this.warId = warId;
            this.coord = coord;
            this.location = location;
            this.remainingMs = remainingMs;
            this.central = central;
            this.hasFlag = hasFlag;
        }
    }
}
