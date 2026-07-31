package ru.pewars.server;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.pewars.server.listeners.BlockListener;
import ru.pewars.server.listeners.CombatListener;
import ru.pewars.server.listeners.CommandListener;
import ru.pewars.server.listeners.TownyWarListener;
import ru.pewars.server.raid.Raid;
import ru.pewars.server.raid.RaidManager;
import ru.pewars.server.raid.RaidPhase;
import ru.pewars.server.storage.Database;
import ru.pewars.server.towny.TownyBridge;
import ru.pewars.server.towny.TownyBridge.ChunkCoord;
import ru.pewars.server.war.CaptureFlagManager;
import ru.pewars.server.war.War;
import ru.pewars.server.war.WarManager;
import ru.pewars.server.war.WarPhase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Главный класс серверного плагина pe_wars.
 * Регистрирует команды /pewar, /peraid, лисенеры, фазовый тикер и хранилище (п.16 ТЗ).
 *
 * Действия из клиентских меню приходят как /pewar action <action> [base64arg],
 * аналогично pe_townymenu.
 */
public final class PeWarsPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private Config config;
    private TownyBridge towny;
    private WarManager wars;
    private RaidManager raids;
    private CaptureFlagManager captureFlags;
    private StateSerializer serializer;
    private Database database;
    private BukkitTask tickerTask;
    private BukkitTask persistTask;

    public WarManager getWarManager() {
        return wars;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = new Config(this);
        towny = new TownyBridge(this);
        wars = new WarManager(this, config, towny);
        raids = new RaidManager(this, config, towny);
        // Перекрёстные ссылки — запрет одновременных войны и рейда у одного города.
        wars.setRaidManager(raids);
        raids.setWarManager(wars);
        captureFlags = new CaptureFlagManager(this, config, towny, wars);
        captureFlags.enable();
        serializer = new StateSerializer(config, towny, wars, raids);

        // П.16 ТЗ: хранилище SQLite/MySQL — конфликты, чанки, КД и флаги переживают рестарт.
        database = new Database(this, config);
        database.init();
        // Восстанавливаем состояние после полной загрузки сервера (Towny должен быть готов).
        getServer().getScheduler().runTask(this, this::restoreState);

        getCommand("pewar").setExecutor(this);
        getCommand("pewar").setTabCompleter(this);
        getCommand("peraid").setExecutor(this);
        getCommand("peraid").setTabCompleter(this);

        getServer().getPluginManager().registerEvents(
                new CommandListener(config, towny, wars, raids, serializer), this);
        getServer().getPluginManager().registerEvents(
                new BlockListener(this, config, towny, raids, wars, captureFlags), this);
        getServer().getPluginManager().registerEvents(new CombatListener(towny, raids, wars), this);
        getServer().getPluginManager().registerEvents(new TownyWarListener(towny, wars), this);
        getServer().getPluginManager().registerEvents(captureFlags, this);
        // Подавление спама Towny («PvP выключено», «Чужакам нельзя...») в активных конфликтах:
        // снимаем отмену на событиях самого Towny — тогда он не шлёт свои сообщения.
        new ru.pewars.server.listeners.TownyActionListener(this, towny, wars, raids).register();

        startTicker();
        getLogger().info("pe_wars включён. Рейды и войны для Towny активны. Towny: "
                + towny.townyReady() + ", Vault: " + towny.economyReady()
                + ", хранилище: " + database.isReady());
    }

    @Override
    public void onDisable() {
        if (tickerTask != null) tickerTask.cancel();
        if (persistTask != null) persistTask.cancel();
        // Сохраняем состояние ПЕРЕД очисткой менеджеров.
        persistState();
        if (captureFlags != null) captureFlags.shutdown();
        if (wars != null) wars.shutdown();
        if (raids != null) raids.shutdown();
        if (database != null) database.close();
    }

    /** Тикер каждую секунду + автосохранение состояния раз в минуту. */
    private void startTicker() {
        tickerTask = getServer().getScheduler().runTaskTimer(this, () -> {
            wars.tick();
            raids.tick();
            captureFlags.tick();
            captureFlags.scanNoFlagCaptures();
        }, 20L, 20L);
        persistTask = getServer().getScheduler().runTaskTimer(this, this::persistState, 1200L, 1200L);
    }

    // ===================== Persistence (п.16) =====================

    private void persistState() {
        if (database == null || !database.isReady()) return;
        List<Database.WarRow> warRows = new ArrayList<>();
        for (War war : wars.allWars()) {
            if (war.phase == WarPhase.ENDED) continue;
            Database.WarRow row = new Database.WarRow();
            row.id = war.id.toString();
            row.attacker = war.attackerTownName;
            row.defender = war.defenderTownName;
            row.phase = war.phase.name();
            row.prepEnd = war.preparationEnd;
            row.activeEnd = war.activeEnd;
            if (war.centralChunk != null) {
                row.centralWorld = war.centralChunk.world;
                row.centralX = war.centralChunk.x;
                row.centralZ = war.centralChunk.z;
            } else {
                row.centralWorld = "";
            }
            row.centralFlags = war.centralFlagsCaptured;
            row.chunks = encodeChunks(war.chunkStatus);
            row.dPvp = boolToInt(war.defenderOriginalPvp);
            row.dExpl = boolToInt(war.defenderOriginalExplosion);
            row.aPvp = boolToInt(war.attackerOriginalPvp);
            row.aExpl = boolToInt(war.attackerOriginalExplosion);
            warRows.add(row);
        }
        List<Database.RaidRow> raidRows = new ArrayList<>();
        for (Raid raid : raids.allRaids()) {
            if (raid.phase == RaidPhase.ENDED) continue;
            Database.RaidRow row = new Database.RaidRow();
            row.id = raid.id.toString();
            row.attacker = raid.attackerTownName;
            row.defender = raid.defenderTownName;
            row.phase = raid.phase.name();
            row.prepEnd = raid.preparationEnd;
            row.activeEnd = raid.activeEnd;
            raidRows.add(row);
        }
        database.saveAll(warRows, raidRows,
                wars.cooldownSnapshot(), raids.cooldownSnapshot(),
                captureFlags.snapshotRows());
    }

    private void restoreState() {
        if (database == null || !database.isReady()) return;
        Database.Loaded loaded = database.loadAll();
        wars.restoreCooldowns(loaded.warCooldowns);
        raids.restoreCooldowns(loaded.raidCooldowns);

        long now = System.currentTimeMillis();
        int restoredWars = 0;
        for (Database.WarRow row : loaded.wars) {
            try {
                boolean decisionPhase = "DECISION".equals(row.phase);
                if (!decisionPhase && row.activeEnd <= now) continue; // война уже истекла, КД сохранён отдельно.
                War war = new War(UUID.fromString(row.id), row.attacker, row.defender,
                        row.prepEnd, row.activeEnd);
                war.phase = WarPhase.valueOf(row.phase);
                if (war.phase == WarPhase.ENDED) continue;
                if (row.centralWorld != null && !row.centralWorld.isBlank()) {
                    war.centralChunk = new ChunkCoord(row.centralWorld, row.centralX, row.centralZ);
                }
                war.centralFlagsCaptured = row.centralFlags;
                decodeChunks(row.chunks, war.chunkStatus);
                war.defenderOriginalPvp = intToBool(row.dPvp);
                war.defenderOriginalExplosion = intToBool(row.dExpl);
                war.attackerOriginalPvp = intToBool(row.aPvp);
                war.attackerOriginalExplosion = intToBool(row.aExpl);
                wars.restoreWar(war);
                restoredWars++;
            } catch (Exception e) {
                getLogger().warning("Не удалось восстановить войну " + row.id + ": " + e.getMessage());
            }
        }
        int restoredRaids = 0;
        for (Database.RaidRow row : loaded.raids) {
            try {
                if (row.activeEnd <= now) continue;
                Raid raid = new Raid(UUID.fromString(row.id), row.attacker, row.defender,
                        row.prepEnd, row.activeEnd);
                raid.phase = RaidPhase.valueOf(row.phase);
                if (raid.phase == RaidPhase.ENDED) continue;
                raids.restoreRaid(raid);
                restoredRaids++;
            } catch (Exception e) {
                getLogger().warning("Не удалось восстановить рейд " + row.id + ": " + e.getMessage());
            }
        }
        captureFlags.restoreFlags(loaded.flags);
        if (restoredWars > 0 || restoredRaids > 0) {
            getLogger().info("Восстановлено из хранилища: войн — " + restoredWars
                    + ", рейдов — " + restoredRaids + ".");
        }
    }

    /** Формат: "world;x;z;1|world;x;z;0|..." (1 = чанк захвачен). */
    private String encodeChunks(Map<ChunkCoord, Boolean> chunkStatus) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<ChunkCoord, Boolean> e : chunkStatus.entrySet()) {
            if (sb.length() > 0) sb.append('|');
            ChunkCoord c = e.getKey();
            sb.append(c.world).append(';').append(c.x).append(';').append(c.z)
                    .append(';').append(Boolean.TRUE.equals(e.getValue()) ? 1 : 0);
        }
        return sb.toString();
    }

    private void decodeChunks(String encoded, Map<ChunkCoord, Boolean> into) {
        if (encoded == null || encoded.isBlank()) return;
        for (String part : encoded.split("\\|")) {
            String[] fields = part.split(";");
            if (fields.length != 4) continue;
            try {
                into.put(new ChunkCoord(fields[0], Integer.parseInt(fields[1]), Integer.parseInt(fields[2])),
                        "1".equals(fields[3]));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private int boolToInt(Boolean value) {
        if (value == null) return -1;
        return value ? 1 : 0;
    }

    private Boolean intToBool(int value) {
        if (value < 0) return null;
        return value == 1;
    }

    // ===================== Commands =====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("pewar")) {
            return handlePewar(sender, args);
        }
        if (name.equals("peraid")) {
            return handlePeraid(sender, args);
        }
        return false;
    }

    private boolean handlePewar(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Команда только для игроков.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(StateSerializer.WAR_MARKER + serializer.buildWar(player));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!player.hasPermission("pewars.admin")) {
                player.sendMessage(color("&cНет прав."));
                return true;
            }
            config.reload();
            player.sendMessage(color(config.chat("reload")));
            return true;
        }

        if (sub.equals("action")) {
            if (args.length < 2) {
                player.sendMessage(StateSerializer.WAR_MARKER + serializer.buildWar(player));
                return true;
            }
            String action = args[1].toLowerCase(Locale.ROOT);
            String value = args.length >= 3 ? decode(args[2]) : "";
            handleWarAction(player, action, value);
            return true;
        }

        // /pewar decide <treasury|capture|mayor> [ник] — выбор исхода войны после победы.
        if (sub.equals("decide")) {
            String choice = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
            choice = switch (choice) {
                case "казна", "treasury" -> "treasury";
                case "захват", "город", "capture" -> "capture";
                case "мэр", "mayor" -> "mayor";
                default -> choice;
            };
            String targetPlayer = args.length >= 3 ? args[2] : null;
            String error = wars.decide(player, choice, targetPlayer);
            if (error != null) player.sendMessage(error);
            return true;
        }

        // /pewar declare <targetTown> — быстрая объявка войны без меню.
        if (sub.equals("declare")) {
            String target = args.length >= 2 ? args[1] : "";
            String error = wars.declareWar(player, target);
            if (error != null) player.sendMessage(error);
            return true;
        }

        // П.8 ТЗ: капитуляция.
        if (sub.equals("surrender")) {
            String error = wars.surrender(player);
            if (error != null) player.sendMessage(error);
            return true;
        }

        // П.9 ТЗ: мирный договор (предложение/подпись второй стороной).
        if (sub.equals("peace")) {
            String error = wars.peace(player);
            if (error != null) player.sendMessage(error);
            return true;
        }

        // /pewar cancel [townName] — отмена войны.
        if (sub.equals("cancel") || sub.equals("stop")) {
            String target = args.length >= 2 ? args[1] : "";
            String error = wars.cancelWar(player, target);
            if (error != null) {
                player.sendMessage(error);
            } else {
                player.sendMessage(color(config.chat("war-cancel-success")));
            }
            return true;
        }

        player.sendMessage(StateSerializer.WAR_MARKER + serializer.buildWar(player));
        return true;
    }

    private boolean handlePeraid(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Команда только для игроков.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(StateSerializer.RAID_MARKER + serializer.buildRaid(player));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("action")) {
            if (args.length < 2) {
                player.sendMessage(StateSerializer.RAID_MARKER + serializer.buildRaid(player));
                return true;
            }
            String action = args[1].toLowerCase(Locale.ROOT);
            String value = args.length >= 3 ? decode(args[2]) : "";
            handleRaidAction(player, action, value);
            return true;
        }

        if (sub.equals("declare")) {
            String target = args.length >= 2 ? args[1] : "";
            String error = raids.declareRaid(player, target);
            if (error != null) player.sendMessage(error);
            return true;
        }

        if (sub.equals("cancel") || sub.equals("stop")) {
            String target = args.length >= 2 ? args[1] : "";
            String error = raids.cancelRaid(player, target);
            if (error != null) {
                player.sendMessage(error);
            } else {
                player.sendMessage(color(config.chat("raid-cancel-success")));
            }
            return true;
        }

        player.sendMessage(StateSerializer.RAID_MARKER + serializer.buildRaid(player));
        return true;
    }

    /** Обработка действий из клиентского меню войн. */
    private void handleWarAction(Player player, String action, String value) {
        switch (action) {
            case "war_declare" -> {
                String error = wars.declareWar(player, value);
                if (error != null) {
                    player.sendMessage(error);
                }
                player.sendMessage(StateSerializer.WAR_MARKER + serializer.buildWar(player));
            }
            case "war_surrender" -> {
                String error = wars.surrender(player);
                if (error != null) {
                    player.sendMessage(error);
                }
                player.sendMessage(StateSerializer.WAR_MARKER + serializer.buildWar(player));
            }
            case "war_peace" -> {
                String error = wars.peace(player);
                if (error != null) {
                    player.sendMessage(error);
                }
                player.sendMessage(StateSerializer.WAR_MARKER + serializer.buildWar(player));
            }
            case "war_request_help" -> {
                String error = wars.requestNationHelp(player, value);
                if (error != null) player.sendMessage(error);
                player.sendMessage(StateSerializer.WAR_MARKER + serializer.buildWar(player));
            }
            case "war_decide_treasury" -> {
                String error = wars.decide(player, "treasury", null);
                if (error != null) player.sendMessage(error);
                player.sendMessage(StateSerializer.WAR_MARKER + serializer.buildWar(player));
            }
            case "war_decide_capture" -> {
                String error = wars.decide(player, "capture", null);
                if (error != null) player.sendMessage(error);
                player.sendMessage(StateSerializer.WAR_MARKER + serializer.buildWar(player));
            }
            case "war_decide_mayor" -> {
                String error = wars.decide(player, "mayor", value);
                if (error != null) player.sendMessage(error);
                player.sendMessage(StateSerializer.WAR_MARKER + serializer.buildWar(player));
            }
            case "war_refresh", "war_open" ->
                    player.sendMessage(StateSerializer.WAR_MARKER + serializer.buildWar(player));
            default -> {
                player.sendMessage(color("&cНеизвестное действие войны: &f" + action));
                player.sendMessage(StateSerializer.WAR_MARKER + serializer.buildWar(player));
            }
        }
    }

    /** Обработка действий из клиентского меню рейдов. */
    private void handleRaidAction(Player player, String action, String value) {
        switch (action) {
            case "raid_declare" -> {
                String error = raids.declareRaid(player, value);
                if (error != null) {
                    player.sendMessage(error);
                }
                player.sendMessage(StateSerializer.RAID_MARKER + serializer.buildRaid(player));
            }
            case "raid_refresh", "raid_open" ->
                    player.sendMessage(StateSerializer.RAID_MARKER + serializer.buildRaid(player));
            default -> {
                player.sendMessage(color("&cНеизвестное действие рейда: &f" + action));
                player.sendMessage(StateSerializer.RAID_MARKER + serializer.buildRaid(player));
            }
        }
    }

    private String decode(String encoded) {
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean isWar = command.getName().equalsIgnoreCase("pewar");
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            List<String> options = isWar
                    ? List.of("action", "declare", "cancel", "surrender", "peace", "decide", "reload")
                    : List.of("action", "declare", "cancel");
            for (String option : options) {
                if (option.startsWith(prefix)) result.add(option);
            }
            return result;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("declare") || args[0].equalsIgnoreCase("cancel"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            for (String name : towny.getOnlineTownNames()) {
                if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) result.add(name);
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("decide")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            for (String option : List.of("treasury", "capture", "mayor")) {
                if (option.startsWith(prefix)) result.add(option);
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("action")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            List<String> actions = isWar
                    ? List.of("war_declare", "war_refresh", "war_surrender", "war_peace",
                            "war_decide_treasury", "war_decide_capture", "war_decide_mayor")
                    : List.of("raid_declare", "raid_refresh");
            for (String option : actions) {
                if (option.startsWith(prefix)) result.add(option);
            }
            return result;
        }
        return Collections.emptyList();
    }
}
