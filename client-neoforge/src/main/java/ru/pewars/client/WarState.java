package ru.pewars.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Состояние меню войн (парсится из JSON сервера, маркер [PETM_WAR]).
 * По образцу BuildsState: статические ридеры bool/integer/str.
 */
public class WarState {
    public boolean loaded;
    public boolean hasTown;
    public boolean isMayor;
    public String townName = "";
    public String townBalance = "0";
    public String warCost = "0";
    public String warMinOnline = "3";
    // ФИКС: длительности фаз приходят с сервера (раньше были захардкожены в UI).
    public String warPrepMin = "60";
    public String warActiveMin = "120";
    public String currency = "$";
    public String message = "";
    public ActiveWar activeWar;
    /** Ожидающий выбор исхода войны (город игрока — победитель). */
    public Decision decision;
    public final List<SummaryWar> wars = new ArrayList<>();
    public final List<SummaryWar> history = new ArrayList<>();
    public final List<String> targets = new ArrayList<>();

    public static WarState loading() {
        WarState state = new WarState();
        state.loaded = false;
        state.message = "Загрузка данных о войнах...";
        return state;
    }

    public static WarState fromJson(JsonObject json) {
        WarState state = new WarState();
        state.loaded = true;
        state.hasTown = bool(json, "hasTown");
        state.isMayor = bool(json, "isMayor");
        state.townName = str(json, "townName");
        state.townBalance = str(json, "townBalance");
        state.warCost = str(json, "warCost");
        state.warMinOnline = str(json, "warMinOnline", "3");
        state.warPrepMin = str(json, "warPrepMin", "60");
        state.warActiveMin = str(json, "warActiveMin", "120");
        state.currency = str(json, "currency", "$").trim();
        state.message = str(json, "message");
        if (json.has("activeWar") && !json.get("activeWar").isJsonNull()) {
            state.activeWar = ActiveWar.fromJson(json.getAsJsonObject("activeWar"));
        }
        if (json.has("decision") && json.get("decision").isJsonObject()) {
            JsonObject d = json.getAsJsonObject("decision");
            state.decision = new Decision();
            state.decision.defender = str(d, "defender");
            state.decision.chooser = bool(d, "chooser");
        }
        if (json.has("wars") && json.get("wars").isJsonArray()) {
            for (var el : json.getAsJsonArray("wars")) {
                state.wars.add(SummaryWar.fromJson(el.getAsJsonObject()));
            }
        }
        if (json.has("history") && json.get("history").isJsonArray()) {
            for (var el : json.getAsJsonArray("history")) {
                state.history.add(SummaryWar.fromJson(el.getAsJsonObject()));
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

    /** Выбор исхода войны после победы (от сервера). */
    public static class Decision {
        public String defender = "";
        public boolean chooser;
    }

    /** Активная война, в которой участвует игрок (не наблюдатель). */
    public boolean isParticipating() {
        return activeWar != null && !"observer".equals(activeWar.role);
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

    public static final class ActiveWar {
        public String phase = "";
        public String attacker = "";
        public String defender = "";
        public String timeLeft = "";
        public String capturedChunks = "0";
        public String totalChunks = "0";
        public boolean centralCaptured;
        public String role = "observer";
        public long pendingHelpRequestTime = -1;

        static ActiveWar fromJson(JsonObject json) {
            ActiveWar w = new ActiveWar();
            w.phase = str(json, "phase");
            w.attacker = str(json, "attacker");
            w.defender = str(json, "defender");
            w.timeLeft = str(json, "timeLeft");
            w.capturedChunks = str(json, "capturedChunks");
            w.totalChunks = str(json, "totalChunks");
            w.centralCaptured = bool(json, "centralCaptured");
            w.role = str(json, "role", "observer");
            if (json.has("pendingHelpRequestTime") && !json.get("pendingHelpRequestTime").isJsonNull()) {
                w.pendingHelpRequestTime = json.get("pendingHelpRequestTime").getAsLong();
            }
            return w;
        }
    }

    public static final class SummaryWar {
        public String attacker = "";
        public String defender = "";
        public String phase = "";
        public String timeLeft = "";
        public String winner = "";

        static SummaryWar fromJson(JsonObject json) {
            SummaryWar w = new SummaryWar();
            w.attacker = str(json, "attacker");
            w.defender = str(json, "defender");
            w.phase = str(json, "phase");
            w.timeLeft = str(json, "timeLeft");
            w.winner = str(json, "winner");
            return w;
        }
    }
}
