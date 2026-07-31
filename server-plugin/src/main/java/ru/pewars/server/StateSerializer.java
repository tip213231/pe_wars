package ru.pewars.server;

import org.bukkit.entity.Player;
import ru.pewars.server.raid.Raid;
import ru.pewars.server.raid.RaidManager;
import ru.pewars.server.raid.RaidPhase;
import ru.pewars.server.towny.TownyBridge;
import ru.pewars.server.war.War;
import ru.pewars.server.war.WarManager;
import ru.pewars.server.war.WarPhase;

import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;

/**
 * Сериализация состояния в компактный JSON для клиентских меню.
 * Маркеры [PETM_WAR] / [PETM_RAID] — клиентский мод ловит их в чате.
 *
 * Ключи сделаны короткими, чтобы уложиться в лимит длины чат-пакета.
 * Числа передаются строками (клиентский парсер читает через getAsString).
 *
 * П.3 ТЗ: помимо старого массива "targets" (совместимость со старым клиентом)
 * отдаётся массив "towns" с онлайном, нейтралитетом и доступностью войны/рейда.
 */
public final class StateSerializer {
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##");

    public static final String WAR_MARKER = "[PETM_WAR]";
    public static final String RAID_MARKER = "[PETM_RAID]";

    private final Config config;
    private final TownyBridge towny;
    private final WarManager wars;
    private final RaidManager raids;

    public StateSerializer(Config config, TownyBridge towny, WarManager wars, RaidManager raids) {
        this.config = config;
        this.towny = towny;
        this.wars = wars;
        this.raids = raids;
    }

    public String buildWar(Player player) {
        Object town = towny.getTown(player);
        String ownTownName = towny.townName(town);
        StringBuilder json = new StringBuilder("{");
        put(json, "hasTown", town != null).append(',');
        put(json, "isMayor", towny.isMayor(player)).append(',');
        put(json, "townName", ownTownName).append(',');
        put(json, "townBalance", format(town == null ? 0 : towny.townBalance(town))).append(',');
        put(json, "warCost", format(config.warCost)).append(',');
        put(json, "warMinOnline", String.valueOf(config.warMinOnline)).append(',');
        put(json, "warPrepMin", String.valueOf(config.warPrepSeconds / 60)).append(',');
        put(json, "warActiveMin", String.valueOf(config.warActiveSeconds / 60)).append(',');
        put(json, "currency", config.currency).append(',');

        // НОВОЕ: ожидающий выбор исхода войны для города-победителя.
        War pendingDecision = wars.getPendingDecisionFor(ownTownName);
        json.append("\"decision\":");
        if (pendingDecision == null) {
            json.append("null").append(',');
        } else {
            json.append('{');
            put(json, "defender", pendingDecision.defenderTownName).append(',');
            put(json, "chooser", towny.isMayor(player) || player.hasPermission("pewars.admin"));
            json.append('}').append(',');
        }

        // Активная война, в которой участвует город игрока.
        War active = findActiveWarForPlayer(player, town);
        json.append("\"activeWar\":");
        if (active == null) {
            json.append("null");
        } else {
            json.append('{');
            put(json, "phase", active.phase.name()).append(',');
            put(json, "attacker", active.attackerTownName).append(',');
            put(json, "defender", active.defenderTownName).append(',');
            put(json, "timeLeft", warTimeLeft(active)).append(',');
            put(json, "capturedChunks", String.valueOf(active.capturedCount())).append(',');
            put(json, "totalChunks", String.valueOf(active.totalChunks())).append(',');
            put(json, "centralCaptured", active.centralCaptured(config.centralFlagsRequired)).append(',');
            put(json, "centralFlagsDone", String.valueOf(active.centralFlagsCaptured)).append(',');
            put(json, "centralFlagsNeed", String.valueOf(config.centralFlagsRequired)).append(',');
            put(json, "role", warRole(active, town)).append(',');
            
            Object nation = towny.getNation(town);
            long pendingHelpTime = -1;
            if (nation != null) {
                pendingHelpTime = wars.getPendingHelpRequestTime(towny.nationName(nation), towny.townName(town));
            }
            put(json, "pendingHelpRequestTime", String.valueOf(pendingHelpTime));
            json.append('}');
        }
        json.append(',');

        // Список всех активных войн (коротко) и истории.
        json.append("\"wars\":[");
        List<War> activeWars = wars.activeWars();
        for (int i = 0; i < activeWars.size(); i++) {
            if (i > 0) json.append(',');
            War w = activeWars.get(i);
            json.append('{');
            put(json, "attacker", w.attackerTownName).append(',');
            put(json, "defender", w.defenderTownName).append(',');
            put(json, "phase", w.phase.name()).append(',');
            put(json, "timeLeft", warTimeLeft(w));
            json.append('}');
        }
        json.append("],\"history\":[");
        List<War> hist = wars.history();
        for (int i = 0; i < Math.min(hist.size(), 6); i++) {
            if (i > 0) json.append(',');
            War w = hist.get(i);
            json.append('{');
            put(json, "attacker", w.attackerTownName).append(',');
            put(json, "defender", w.defenderTownName).append(',');
            put(json, "winner", w.attackerWon ? "attacker" : "defender");
            json.append('}');
        }
        json.append("],");

        Map<String, Integer> townCounts = towny.getOnlineTownCounts();

        // Старый список целей (совместимость с текущим клиентским модом).
        json.append("\"targets\":[");
        int targetCount = 0;
        for (Map.Entry<String, Integer> entry : townCounts.entrySet()) {
            if (entry.getValue() >= config.warMinOnline) {
                if (targetCount > 0) json.append(',');
                json.append('"').append(escape(entry.getKey())).append('"');
                targetCount++;
                if (targetCount >= 100) break;
            }
        }
        json.append("],");

        // П.3 ТЗ: список городов с онлайном, стоимостью, нейтралитетом и доступностью.
        json.append("\"towns\":[");
        int townCount = 0;
        for (Map.Entry<String, Integer> entry : townCounts.entrySet()) {
            String name = entry.getKey();
            if (name.equalsIgnoreCase(ownTownName)) continue;
            int online = entry.getValue();
            boolean neutral = wars.isNeutralTown(name);
            boolean inConflict = wars.isAtWar(name) || raids.isTownRaided(name)
                    || (ownTownName != null && !ownTownName.isBlank()
                        && (wars.isAtWar(ownTownName) || raids.isTownRaided(ownTownName)));
            boolean pairCd = ownTownName != null && !ownTownName.isBlank()
                    && wars.isPairOnCooldown(ownTownName, name);
            boolean warAvailable = online >= config.warMinOnline && !neutral && !inConflict && !pairCd;
            if (townCount > 0) json.append(',');
            json.append('{');
            put(json, "name", name).append(',');
            put(json, "online", String.valueOf(online)).append(',');
            put(json, "neutral", neutral).append(',');
            put(json, "onCooldown", pairCd).append(',');
            put(json, "warAvailable", warAvailable).append(',');
            put(json, "warCost", format(config.warCost));
            json.append('}');
            townCount++;
            if (townCount >= 100) break;
        }
        json.append("],");

        put(json, "message", "");
        json.append('}');
        return json.toString();
    }

    public String buildRaid(Player player) {
        Object town = towny.getTown(player);
        String ownTownName = towny.townName(town);
        StringBuilder json = new StringBuilder("{");
        put(json, "hasTown", town != null).append(',');
        put(json, "isMayor", towny.isMayor(player)).append(',');
        put(json, "townName", ownTownName).append(',');
        put(json, "townBalance", format(town == null ? 0 : towny.townBalance(town))).append(',');
        put(json, "raidCost", format(config.raidCost)).append(',');
        put(json, "raidMinOnline", String.valueOf(config.raidMinOnline)).append(',');
        put(json, "raidPrepMin", String.valueOf(config.raidPrepSeconds / 60)).append(',');
        put(json, "raidActiveMin", String.valueOf(config.raidActiveSeconds / 60)).append(',');
        put(json, "currency", config.currency).append(',');

        // Активный рейд, где участвует город игрока (атака или защита).
        Raid active = findActiveRaidForPlayer(player, town);
        json.append("\"activeRaid\":");
        if (active == null) {
            json.append("null");
        } else {
            json.append('{');
            put(json, "phase", active.phase.name()).append(',');
            put(json, "attacker", active.attackerTownName).append(',');
            put(json, "defender", active.defenderTownName).append(',');
            put(json, "timeLeft", raidTimeLeft(active)).append(',');
            put(json, "role", raidRole(active, town));
            json.append('}');
        }
        json.append(',');

        json.append("\"raids\":[");
        List<Raid> activeRaids = raids.activeRaids();
        for (int i = 0; i < activeRaids.size(); i++) {
            if (i > 0) json.append(',');
            Raid r = activeRaids.get(i);
            json.append('{');
            put(json, "attacker", r.attackerTownName).append(',');
            put(json, "defender", r.defenderTownName).append(',');
            put(json, "phase", r.phase.name()).append(',');
            put(json, "timeLeft", raidTimeLeft(r));
            json.append('}');
        }
        json.append("],\"history\":[");
        List<Raid> hist = raids.history();
        for (int i = 0; i < Math.min(hist.size(), 6); i++) {
            if (i > 0) json.append(',');
            Raid r = hist.get(i);
            json.append('{');
            put(json, "attacker", r.attackerTownName).append(',');
            put(json, "defender", r.defenderTownName);
            json.append('}');
        }
        json.append("],");

        Map<String, Integer> townCounts = towny.getOnlineTownCounts();

        json.append("\"targets\":[");
        int targetCount = 0;
        for (Map.Entry<String, Integer> entry : townCounts.entrySet()) {
            if (entry.getValue() >= config.raidMinOnline) {
                if (targetCount > 0) json.append(',');
                json.append('"').append(escape(entry.getKey())).append('"');
                targetCount++;
                if (targetCount >= 100) break;
            }
        }
        json.append("],");

        // П.3 ТЗ: список городов для рейдов. Нейтралитет на рейд НЕ влияет (п.12).
        json.append("\"towns\":[");
        int townCount = 0;
        for (Map.Entry<String, Integer> entry : townCounts.entrySet()) {
            String name = entry.getKey();
            if (name.equalsIgnoreCase(ownTownName)) continue;
            int online = entry.getValue();
            boolean inConflict = wars.isAtWar(name) || raids.isTownRaided(name)
                    || (ownTownName != null && !ownTownName.isBlank()
                        && (wars.isAtWar(ownTownName) || raids.isTownRaided(ownTownName)));
            boolean pairCd = ownTownName != null && !ownTownName.isBlank()
                    && raids.isPairOnCooldown(ownTownName, name);
            boolean raidAvailable = online >= config.raidMinOnline && !inConflict && !pairCd;
            if (townCount > 0) json.append(',');
            json.append('{');
            put(json, "name", name).append(',');
            put(json, "online", String.valueOf(online)).append(',');
            put(json, "neutral", wars.isNeutralTown(name)).append(',');
            put(json, "onCooldown", pairCd).append(',');
            put(json, "raidAvailable", raidAvailable).append(',');
            put(json, "raidCost", format(config.raidCost));
            json.append('}');
            townCount++;
            if (townCount >= 100) break;
        }
        json.append("],");

        put(json, "message", "");
        json.append('}');
        return json.toString();
    }

    private War findActiveWarForPlayer(Player player, Object town) {
        String townName = towny.townName(town);
        if (townName.isBlank()) return null;
        for (War w : wars.activeWars()) {
            if (!w.isActive()) continue;
            // Приоритет защите.
            if (w.defenderTownName.equalsIgnoreCase(townName)) return w;
            if (w.attackerTownName.equalsIgnoreCase(townName)) return w;
        }
        return null;
    }

    private Raid findActiveRaidForPlayer(Player player, Object town) {
        String townName = towny.townName(town);
        if (townName.isBlank()) return null;
        for (Raid r : raids.activeRaids()) {
            if (!r.isActive()) continue;
            if (r.attackerTownName.equalsIgnoreCase(townName)) return r;
            if (r.defenderTownName.equalsIgnoreCase(townName)) return r;
        }
        return null;
    }

    private String warRole(War w, Object town) {
        String townName = towny.townName(town);
        if (townName.isBlank()) return "observer";
        if (w.defenderTownName.equalsIgnoreCase(townName)) return "defender";
        if (w.attackerTownName.equalsIgnoreCase(townName)) return "attacker";
        return "observer";
    }

    private String raidRole(Raid r, Object town) {
        String townName = towny.townName(town);
        if (townName.isBlank()) return "observer";
        if (r.attackerTownName.equalsIgnoreCase(townName)) return "attacker";
        if (r.defenderTownName.equalsIgnoreCase(townName)) return "defender";
        return "observer";
    }

    private String warTimeLeft(War w) {
        long now = System.currentTimeMillis();
        long end = w.phase == WarPhase.PREPARATION ? w.preparationEnd : w.activeEnd;
        return formatDuration(Math.max(0, end - now));
    }

    private String raidTimeLeft(Raid r) {
        long now = System.currentTimeMillis();
        long end = r.phase == RaidPhase.PREPARATION ? r.preparationEnd : r.activeEnd;
        return formatDuration(Math.max(0, end - now));
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(1L, (millis + 999L) / 1000L);
        long h = totalSeconds / 3600L;
        long m = (totalSeconds % 3600L) / 60L;
        long s = totalSeconds % 60L;
        if (h > 0) return h + ":" + pad(m) + ":" + pad(s);
        return m + ":" + pad(s);
    }

    private String pad(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private String format(double value) {
        return MONEY.format(value).replace(',', ' ');
    }

    private StringBuilder put(StringBuilder json, String key, String value) {
        json.append('"').append(escape(key)).append('"').append(':').append('"').append(escape(value)).append('"');
        return json;
    }

    private StringBuilder put(StringBuilder json, String key, boolean value) {
        json.append('"').append(escape(key)).append('"').append(':').append(value);
        return json;
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
