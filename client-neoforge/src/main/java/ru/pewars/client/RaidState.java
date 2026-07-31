package ru.pewars.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Состояние меню рейдов (парсится из JSON сервера, маркер [PETM_RAID]).
 * По образцу BuildsState: статические ридеры bool/integer/str.
 */
public class RaidState {
    public boolean loaded;
    public boolean hasTown;
    public boolean isMayor;
    public String townName = "";
    public String townBalance = "0";
    public String raidCost = "0";
    // ФИКС: длительности фаз приходят с сервера (раньше были захардкожены в UI).
    public String raidPrepMin = "15";
    public String raidActiveMin = "15";
    public String currency = "$";
    public String message = "";
    public ActiveRaid activeRaid;
    public final List<SummaryRaid> raids = new ArrayList<>();
    public final List<SummaryRaid> history = new ArrayList<>();
    public final List<String> targets = new ArrayList<>();

    public static RaidState loading() {
        RaidState state = new RaidState();
        state.loaded = false;
        state.message = "Загрузка данных о рейдах...";
        return state;
    }

    public static RaidState fromJson(JsonObject json) {
        RaidState state = new RaidState();
        state.loaded = true;
        state.hasTown = bool(json, "hasTown");
        state.isMayor = bool(json, "isMayor");
        state.townName = str(json, "townName");
        state.townBalance = str(json, "townBalance");
        state.raidCost = str(json, "raidCost");
        state.raidPrepMin = str(json, "raidPrepMin", "15");
        state.raidActiveMin = str(json, "raidActiveMin", "15");
        state.currency = str(json, "currency", "$").trim();
        state.message = str(json, "message");
        if (json.has("activeRaid") && !json.get("activeRaid").isJsonNull()) {
            state.activeRaid = ActiveRaid.fromJson(json.getAsJsonObject("activeRaid"));
        }
        if (json.has("raids") && json.get("raids").isJsonArray()) {
            for (var el : json.getAsJsonArray("raids")) {
                state.raids.add(SummaryRaid.fromJson(el.getAsJsonObject()));
            }
        }
        if (json.has("history") && json.get("history").isJsonArray()) {
            for (var el : json.getAsJsonArray("history")) {
                state.history.add(SummaryRaid.fromJson(el.getAsJsonObject()));
            }
        }
        if (json.has("targets") && json.get("targets").isJsonArray()) {
            JsonArray arr = json.getAsJsonArray("targets");
            for (var el : arr) {
                if (el != null && !el.isJsonNull()) state.targets.add(el.getAsString());
            }
        }
        return state;
    }

    public boolean isParticipating() {
        return activeRaid != null && !"observer".equals(activeRaid.role);
    }

    private static boolean bool(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() && json.get(key).getAsBoolean();
    }

    private static String str(JsonObject json, String key) {
        return str(json, key, "");
    }

    private static String str(JsonObject json, String key, String fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) return fallback;
        return json.get(key).getAsString();
    }

    public static final class ActiveRaid {
        public String phase = "";
        public String attacker = "";
        public String defender = "";
        public String timeLeft = "";
        public String role = "observer";

        static ActiveRaid fromJson(JsonObject json) {
            ActiveRaid r = new ActiveRaid();
            r.phase = str(json, "phase");
            r.attacker = str(json, "attacker");
            r.defender = str(json, "defender");
            r.timeLeft = str(json, "timeLeft");
            r.role = str(json, "role", "observer");
            return r;
        }
    }

    public static final class SummaryRaid {
        public String attacker = "";
        public String defender = "";
        public String phase = "";
        public String timeLeft = "";

        static SummaryRaid fromJson(JsonObject json) {
            SummaryRaid r = new SummaryRaid();
            r.attacker = str(json, "attacker");
            r.defender = str(json, "defender");
            r.phase = str(json, "phase");
            r.timeLeft = str(json, "timeLeft");
            return r;
        }
    }
}
