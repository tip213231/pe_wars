package ru.pewars.server.raid;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.pewars.server.Config;
import ru.pewars.server.api.events.TownRaidEndEvent;
import ru.pewars.server.api.events.TownRaidStartEvent;
import ru.pewars.server.towny.TownyBridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Управление жизненным циклом рейдов (п.10 ТЗ).
 *
 *  - ПАРНЫЕ кулдауны (п.13): КД действует между ЭТИМИ двумя городами;
 *  - победа атакующих: удержались до конца — получают % казны защитника (п.10);
 *  - если атакующих не осталось на территории — рейд ОТБИТ (п.10);
 *  - нейтралитет на рейды НЕ влияет (п.12) — проверки нет;
 *  - события API: TownRaidStartEvent / TownRaidEndEvent (п.17);
 *  - хуки персистентности для Database (п.16).
 *
 * Рейд НЕ захватывает чанки.
 */
public final class RaidManager {
    private final JavaPlugin plugin;
    private final Config config;
    private final TownyBridge towny;

    private final Map<UUID, Raid> raids = new HashMap<>();
    /** По защитнику И по атакующему (для запрета двойного объявления). */
    private final Map<String, Raid> raidsByTown = new HashMap<>();
    /** ПАРНЫЕ кулдауны рейдов (п.13): ключ pairKey(a,b). */
    private final Map<String, Long> raidCooldowns = new HashMap<>();
    private final List<Raid> history = new ArrayList<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    public RaidManager(JavaPlugin plugin, Config config, TownyBridge towny) {
        this.plugin = plugin;
        this.config = config;
        this.towny = towny;
    }

    /** Ссылка на менеджер войн (для запрета одновременных рейда и войны). */
    private ru.pewars.server.war.WarManager wars;

    public void setWarManager(ru.pewars.server.war.WarManager wars) {
        this.wars = wars;
    }

    // ===================== Declaration =====================

    public String declareRaid(Player declarer, String defenderTownName) {
        if (!towny.townyReady()) return "§cTowny не найден на сервере.";
        Object attackerTown = towny.getTown(declarer);
        if (attackerTown == null) {
            return color(config.chat("raid-no-town"));
        }
        if (!towny.isMayor(declarer) && !declarer.hasPermission("pewars.admin")) {
            return color(config.chat("not-mayor"));
        }
        Object defenderTown = towny.getTownByName(defenderTownName);
        if (defenderTown == null) {
            return color(config.chat("town-not-found", "name", defenderTownName));
        }
        String attackerName = towny.townName(attackerTown);
        String defenderName = towny.townName(defenderTown);
        if (defenderName.equalsIgnoreCase(attackerName)) {
            return color(config.chat("raid-self-target"));
        }
        // Запрет двойного объявления.
        if (raidsByTown.containsKey(attackerName.toLowerCase(Locale.ROOT))
                || raidsByTown.containsKey(defenderName.toLowerCase(Locale.ROOT))) {
            return color(config.chat("raid-already-active"));
        }

        // П.13 ТЗ: ПАРНЫЙ кулдаун между ЭТИМИ городами.
        long now = System.currentTimeMillis();
        Long pairCd = raidCooldowns.get(pairKey(attackerName, defenderName));
        if (pairCd != null && pairCd > now && !declarer.hasPermission("pewars.admin")) {
            return color(config.chat("raid-cooldown-pair",
                    "other", defenderName,
                    "time", formatDuration(pairCd - now)));
        }
        // П.12 ТЗ: нейтралитет на рейды НЕ распространяется — проверки нет.
        // Запрет одновременных рейда и войны с участием одного города.
        if (wars != null && (wars.isAtWar(attackerName) || wars.isAtWar(defenderName))) {
            return color(config.chat("raid-war-conflict"));
        }
        // Минимум онлайн в городе-защитнике (п.10 ТЗ: от 4 игроков).
        int defenderOnline = countOnlineInTown(defenderName);
        if (defenderOnline < config.raidMinOnline) {
            return color(config.chat("raid-min-online", "min", String.valueOf(config.raidMinOnline),
                    "online", String.valueOf(defenderOnline)));
        }
        if (!towny.economyReady()) return "§cVault economy не найден.";

        double cost = config.raidCost;
        if (towny.townBalance(attackerTown) < cost) {
            return color(config.chat("raid-cannot-afford", "cost", format(cost)));
        }
        if (!towny.withdrawTown(attackerTown, cost)) {
            return color(config.chat("raid-cannot-afford", "cost", format(cost)));
        }

        now = System.currentTimeMillis();
        long prepEnd = now + config.raidPrepSeconds * 1000L;
        long activeEnd = prepEnd + config.raidActiveSeconds * 1000L;

        Raid raid = new Raid(UUID.randomUUID(), attackerName, defenderName, prepEnd, activeEnd);
        raid.attackerTown = attackerTown;
        raid.defenderTown = defenderTown;

        raids.put(raid.id, raid);
        raidsByTown.put(defenderName.toLowerCase(Locale.ROOT), raid);
        raidsByTown.put(attackerName.toLowerCase(Locale.ROOT), raid);

        int prepMin = (int) (config.raidPrepSeconds / 60);
        Bukkit.broadcastMessage(color(config.chat("raid-declared",
                "attacker", raid.attackerTownName,
                "defender", raid.defenderTownName,
                "prep", String.valueOf(prepMin))));

        createBossBar(raid);
        return null;
    }

    // ===================== Lookup =====================

    public Raid getRaidByDefender(String defenderTownName) {
        if (defenderTownName == null) return null;
        return raidsByTown.get(defenderTownName.toLowerCase(Locale.ROOT));
    }

    public Raid getRaidInvolving(String townName) {
        if (townName == null) return null;
        return raidsByTown.get(townName.toLowerCase(Locale.ROOT));
    }

    public List<Raid> activeRaids() {
        List<Raid> result = new ArrayList<>();
        for (Raid raid : raids.values()) {
            if (raid.isActive()) result.add(raid);
        }
        return result;
    }

    public List<Raid> history() {
        return new ArrayList<>(history);
    }

    public boolean isTownRaided(String townName) {
        return getRaidInvolving(townName) != null;
    }

    // ===================== Persistence hooks (п.16) =====================

    public Map<String, Long> cooldownSnapshot() {
        long now = System.currentTimeMillis();
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, Long> e : raidCooldowns.entrySet()) {
            if (e.getValue() != null && e.getValue() > now) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    public void restoreCooldowns(Map<String, Long> snapshot) {
        if (snapshot == null) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : snapshot.entrySet()) {
            if (e.getValue() != null && e.getValue() > now) {
                raidCooldowns.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
    }

    public void restoreRaid(Raid raid) {
        if (raid == null || raid.phase == RaidPhase.ENDED) return;
        if (raid.attackerTown == null) raid.attackerTown = towny.getTownByName(raid.attackerTownName);
        if (raid.defenderTown == null) raid.defenderTown = towny.getTownByName(raid.defenderTownName);
        raids.put(raid.id, raid);
        raidsByTown.put(raid.defenderTownName.toLowerCase(Locale.ROOT), raid);
        raidsByTown.put(raid.attackerTownName.toLowerCase(Locale.ROOT), raid);
        raid.lastAttackerSeen = System.currentTimeMillis();
        createBossBar(raid);
    }

    public List<Raid> allRaids() {
        return new ArrayList<>(raids.values());
    }

    /** ПАРНЫЙ ключ кулдауна (п.13): одинаков для (a,b) и (b,a). */
    public static String pairKey(String a, String b) {
        String la = a == null ? "" : a.toLowerCase(Locale.ROOT);
        String lb = b == null ? "" : b.toLowerCase(Locale.ROOT);
        return la.compareTo(lb) <= 0 ? la + "|" + lb : lb + "|" + la;
    }

    /** Действует ли парный КД рейда между городами (для GUI, п.3). */
    public boolean isPairOnCooldown(String a, String b) {
        Long until = raidCooldowns.get(pairKey(a, b));
        return until != null && until > System.currentTimeMillis();
    }

    public void shutdown() {
        for (BossBar bar : bossBars.values()) {
            bar.removeAll();
        }
        bossBars.clear();
        raids.clear();
        raidsByTown.clear();
    }

    /** Активная фаза рейда в указанном городе-защитнике? */
    public boolean isRaidActiveHere(Location location) {
        Object town = towny.getTownAt(location);
        if (town == null) return false;
        Raid raid = getRaidByDefender(towny.townName(town));
        return raid != null && raid.phase == RaidPhase.ACTIVE;
    }

    /** Любая фаза рейда (включая подготовку) в указанном городе-защитнике? */
    public boolean isRaidHere(Location location) {
        Object town = towny.getTownAt(location);
        if (town == null) return false;
        Raid raid = getRaidByDefender(towny.townName(town));
        return raid != null && raid.isActive();
    }

    // ===================== Tick (called each second) =====================

    public void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Raid>> it = raids.entrySet().iterator();
        while (it.hasNext()) {
            Raid raid = it.next().getValue();
            if (!raid.isActive()) continue;

            if (raid.attackerTown == null) raid.attackerTown = towny.getTownByName(raid.attackerTownName);
            if (raid.defenderTown == null) raid.defenderTown = towny.getTownByName(raid.defenderTownName);

            // Фазовый переход: подготовка -> активная.
            if (raid.phase == RaidPhase.PREPARATION && now >= raid.preparationEnd) {
                raid.phase = RaidPhase.ACTIVE;
                raid.lastAttackerSeen = now;
                Bukkit.broadcastMessage(color(config.chat("raid-active", "town", raid.defenderTownName)));
                playSoundToParticipants(config.raidStartSound, raid);
                // П.17 ТЗ: событие старта рейда.
                Bukkit.getPluginManager().callEvent(
                        new TownRaidStartEvent(raid.attackerTownName, raid.defenderTownName));
            }

            if (raid.phase == RaidPhase.ACTIVE) {
                checkAttackerPresence(raid);

                // П.10 ТЗ: если атакующих не осталось — рейд ОТБИТ.
                if (now - raid.lastAttackerSeen > config.raidNoAttackersTimeoutSeconds * 1000L) {
                    it.remove();
                    endRaid(raid, false, false);
                    continue;
                }

                // П.10 ТЗ: атакующие удержались до конца рейда — ПОБЕДА атакующих,
                // иначе (никого в зоне к концу) — рейд отбит.
                if (now >= raid.activeEnd) {
                    boolean attackersHeld = attackersInZone(raid);
                    it.remove();
                    endRaid(raid, false, attackersHeld);
                    continue;
                }
            }

            updateBossBar(raid, now);
        }
    }

    /** Периодическая проверка присутствия атакующих в радиусе 2 чанков. */
    private void checkAttackerPresence(Raid raid) {
        if (attackersInZone(raid)) {
            raid.lastAttackerSeen = System.currentTimeMillis();
        }
    }

    /** Есть ли хотя бы один атакующий на/возле территории защитника. */
    private boolean attackersInZone(Raid raid) {
        if (raid.defenderTown == null) return false;
        List<TownyBridge.ChunkCoord> defenderChunks = towny.townChunkCoords(raid.defenderTown);
        if (defenderChunks.isEmpty()) return false;
        for (Player online : Bukkit.getOnlinePlayers()) {
            Object playerTown = towny.getTown(online);
            if (playerTown == null) continue;
            if (!raid.attackerTownName.equalsIgnoreCase(towny.townName(playerTown))) continue;
            if (online.isDead()) continue;
            if (towny.isWithinTwoChunks(online.getLocation(), defenderChunks)) {
                return true;
            }
        }
        return false;
    }

    // ===================== End / Cancel =====================

    /**
     * Завершение рейда (п.10 ТЗ):
     *  - attackerWon=true: атакующие получают raid.win-bank-percent % казны защитника;
     *  - attackerWon=false (не отмена): рейд отбит, штрафов нет;
     *  - byCancel=true: отмена, КД не ставится.
     */
    private void endRaid(Raid raid, boolean byCancel, boolean attackerWon) {
        raid.phase = RaidPhase.ENDED;
        removeBossBar(raid);

        double loot = 0;
        TownRaidEndEvent.Result result;

        if (byCancel) {
            result = TownRaidEndEvent.Result.CANCELLED;
            Bukkit.broadcastMessage(color(config.chat("raid-cancelled",
                    "attacker", raid.attackerTownName,
                    "defender", raid.defenderTownName)));
        } else if (attackerWon) {
            result = TownRaidEndEvent.Result.ATTACKER_WIN;
            // П.10 ТЗ: при победе атакующие получают % казны города (настраивается).
            Object attackerTown = raid.attackerTown != null ? raid.attackerTown : towny.getTownByName(raid.attackerTownName);
            Object defenderTown = raid.defenderTown != null ? raid.defenderTown : towny.getTownByName(raid.defenderTownName);
            if (attackerTown != null && defenderTown != null && towny.economyReady()) {
                double balance = towny.townBalance(defenderTown);
                loot = Math.max(0, balance * config.raidWinBankPercent / 100.0);
                if (loot > 0 && towny.withdrawTown(defenderTown, loot)) {
                    towny.depositTown(attackerTown, loot);
                } else {
                    loot = 0;
                }
            }
            Bukkit.broadcastMessage(color(config.chat("raid-won-attackers",
                    "attacker", raid.attackerTownName,
                    "defender", raid.defenderTownName,
                    "amount", format(loot),
                    "currency", config.currency)));
        } else {
            result = TownRaidEndEvent.Result.DEFENDED;
            // П.10 ТЗ: рейд отбит защитниками. Штрафов для атакующих нет.
            Bukkit.broadcastMessage(color(config.chat("raid-defended",
                    "attacker", raid.attackerTownName,
                    "defender", raid.defenderTownName)));
        }

        raidsByTown.remove(raid.defenderTownName.toLowerCase(Locale.ROOT));
        raidsByTown.remove(raid.attackerTownName.toLowerCase(Locale.ROOT));
        raids.remove(raid.id);

        // П.13 ТЗ: ПАРНЫЙ кулдаун (при отмене в подготовке КД не ставим).
        if (!byCancel) {
            long cdUntil = System.currentTimeMillis() + config.raidCooldownHours * 3600 * 1000L;
            raidCooldowns.put(pairKey(raid.attackerTownName, raid.defenderTownName), cdUntil);
        }

        history.add(0, raid);
        while (history.size() > 8) history.remove(history.size() - 1);
        playSoundToParticipants(config.raidEndSound, raid);

        // П.17 ТЗ: событие завершения рейда.
        Bukkit.getPluginManager().callEvent(new TownRaidEndEvent(
                raid.attackerTownName, raid.defenderTownName, result, loot));
    }

    /** Отмена рейда мэром участвующего города или админом. */
    public String cancelRaid(Player initiator, String townName) {
        boolean forceByAdmin = initiator.hasPermission("pewars.admin");
        Raid raid;
        if (townName == null || townName.isBlank()) {
            Object town = towny.getTown(initiator);
            if (town == null) {
                return color(config.chat("raid-not-active"));
            }
            raid = getRaidInvolving(towny.townName(town));
            if (raid == null) {
                return color(config.chat("raid-not-active"));
            }
        } else {
            raid = getRaidInvolving(townName);
            if (raid == null) {
                return color(config.chat("town-not-found", "name", townName));
            }
        }

        if (!forceByAdmin) {
            if (!towny.isMayor(initiator)) {
                return color(config.chat("not-mayor"));
            }
            Object initiatorTown = towny.getTown(initiator);
            String initiatorTownName = towny.townName(initiatorTown);
            if (!initiatorTownName.equalsIgnoreCase(raid.attackerTownName)
                    && !initiatorTownName.equalsIgnoreCase(raid.defenderTownName)) {
                return color(config.chat("raid-cancel-no-permission"));
            }
        }

        // Возврат стоимости, если рейд ещё не начался (фаза подготовки).
        if (raid.phase == RaidPhase.PREPARATION) {
            Object attackerTown = raid.attackerTown != null ? raid.attackerTown : towny.getTownByName(raid.attackerTownName);
            if (attackerTown != null && towny.depositTown(attackerTown, config.raidCost)) {
                initiator.sendMessage(color(config.chat("raid-cancel-refund", "cost", format(config.raidCost))));
            }
        }

        raids.remove(raid.id);
        endRaid(raid, true, false);
        return null;
    }

    // ===================== BossBar =====================

    private void createBossBar(Raid raid) {
        if (raid.phase == RaidPhase.ENDED) return;
        BossBar bar = Bukkit.createBossBar(
                ChatColor.GOLD + "\uD83D\uDCA5 Подготовка к рейду: " + raid.attackerTownName + " \u2192 " + raid.defenderTownName,
                BarColor.YELLOW, BarStyle.SEGMENTED_20);
        bar.setProgress(1.0);
        bar.setVisible(true);
        showBarToParticipants(bar, raid);
        bossBars.put(raid.id, bar);
    }

    private void updateBossBar(Raid raid, long now) {
        BossBar bar = bossBars.get(raid.id);
        if (bar == null) return;
        long end = raid.phase == RaidPhase.PREPARATION ? raid.preparationEnd : raid.activeEnd;
        long total = raid.phase == RaidPhase.PREPARATION ? config.raidPrepSeconds * 1000L : config.raidActiveSeconds * 1000L;
        long remaining = Math.max(0, end - now);
        double progress = total > 0 ? Math.max(0, Math.min(1, (double) remaining / total)) : 0;
        bar.setProgress(progress);
        if (raid.phase == RaidPhase.PREPARATION) {
            bar.setTitle(ChatColor.GOLD + "\uD83D\uDCA5 Подготовка к рейду: " + formatDuration(remaining)
                    + " | " + raid.attackerTownName + " \u2192 " + raid.defenderTownName);
            bar.setColor(BarColor.YELLOW);
        } else {
            bar.setTitle(ChatColor.RED + "\uD83D\uDD25 Рейд идёт: " + formatDuration(remaining)
                    + " | " + raid.attackerTownName + " \u2192 " + raid.defenderTownName);
            bar.setColor(BarColor.RED);
        }
        showBarToParticipants(bar, raid);
    }

    private void removeBossBar(Raid raid) {
        BossBar bar = bossBars.remove(raid.id);
        if (bar != null) bar.removeAll();
    }

    private void showBarToParticipants(BossBar bar, Raid raid) {
        bar.removeAll();
        Set<Player> viewers = new HashSet<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            Object playerTown = towny.getTown(online);
            if (playerTown == null) continue;
            String name = towny.townName(playerTown);
            if (raid.attackerTownName.equalsIgnoreCase(name) || raid.defenderTownName.equalsIgnoreCase(name)) {
                viewers.add(online);
            }
        }
        for (Player p : viewers) bar.addPlayer(p);
    }

    private void playSoundToParticipants(Sound sound, Raid raid) {
        if (!config.soundsEnabled || sound == null) return;
        for (Player online : Bukkit.getOnlinePlayers()) {
            Object playerTown = towny.getTown(online);
            if (playerTown == null) continue;
            String name = towny.townName(playerTown);
            if (raid.attackerTownName.equalsIgnoreCase(name) || raid.defenderTownName.equalsIgnoreCase(name)) {
                online.playSound(online.getLocation(), sound, config.soundVolume, config.soundPitch);
            }
        }
    }

    // ===================== Helpers =====================

    private int countOnlineInTown(String townName) {
        int count = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            Object playerTown = towny.getTown(online);
            if (playerTown != null && towny.townName(playerTown).equalsIgnoreCase(townName)) {
                count++;
            }
        }
        return count;
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(1L, (millis + 999L) / 1000L);
        long d = totalSeconds / 86400L;
        long h = (totalSeconds % 86400L) / 3600L;
        long m = (totalSeconds % 3600L) / 60L;
        long s = totalSeconds % 60L;
        if (d > 0) return d + " д. " + h + ":" + pad(m) + ":" + pad(s);
        if (h > 0) return h + ":" + pad(m) + ":" + pad(s);
        return m + ":" + pad(s);
    }

    private String pad(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private final java.text.DecimalFormat moneyFormat = new java.text.DecimalFormat("#,##0.##");

    private String format(double value) {
        return moneyFormat.format(value);
    }
}
