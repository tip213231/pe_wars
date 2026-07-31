package ru.pewars.server.war;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ru.pewars.server.Config;
import ru.pewars.server.api.events.TownChunkCaptureEvent;
import ru.pewars.server.api.events.TownWarEndEvent;
import ru.pewars.server.api.events.TownWarStartEvent;
import ru.pewars.server.towny.TownyBridge;
import ru.pewars.server.towny.TownyBridge.ChunkCoord;

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
 * Управление жизненным циклом войн (town-based, только мэр).
 *
 * Реализация по ТЗ:
 *  - PvP ПРИНУДИТЕЛЬНО включается в ОБОИХ городах (защитник И атакующий,
 *    включая все аванпосты — флаг города действует на все его чанки) и
 *    ПЕРИОДИЧЕСКИ ФОРСИРУЕТСЯ: выключить PvP во время войны НЕВОЗМОЖНО (п.4/п.11);
 *  - капитуляция (/pewar surrender) и мирный договор (/pewar peace) (п.8/п.9);
 *  - победа: 75% чанков / центральный чанк / капитуляция (п.8);
 *  - ПАРНЫЕ кулдауны: КД действует между ЭТИМИ двумя городами (п.13);
 *  - нейтралитет проверяется ТОЛЬКО у ЗАЩИТНИКА (п.12: город с нейтралитетом
 *    нельзя выбрать ЦЕЛЬЮ войны; сам он объявлять войну может);
 *  - игроки без нации в зоне войны получают эффект голода (п.4);
 *  - события API: TownWarStartEvent / TownWarEndEvent / TownChunkCaptureEvent (п.17);
 *  - хуки персистентности для Database (п.16).
 */
public final class WarManager {
    private final JavaPlugin plugin;
    private final Config config;
    private final TownyBridge towny;

    private final Map<UUID, War> wars = new HashMap<>();
    /** По городу-защитнику И по городу-атакующему (для запрета двойного объявления). */
    private final Map<String, War> warsByTown = new HashMap<>();
    /** ПАРНЫЕ кулдауны войн (п.13): ключ pairKey(a,b), значение — ms до истечения. */
    private final Map<String, Long> warCooldowns = new HashMap<>();
    /** Войны, завершившиеся победой атакующих и ожидающие выбора исхода мэром победителей. */
    private final Map<UUID, War> pendingDecisions = new HashMap<>();
    /** Голограммы в оккупированных чанках. */
    private final Map<ChunkCoord, List<org.bukkit.entity.ArmorStand>> chunkHolograms = new HashMap<>();
    private final List<War> history = new ArrayList<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    /** Счётчик тиков для периодических задач (форсаж PvP, голод). */
    private long tickCounter;

    public WarManager(JavaPlugin plugin, Config config, TownyBridge towny) {
        this.plugin = plugin;
        this.config = config;
        this.towny = towny;
    }

    /** Ссылка на менеджер рейдов (для запрета одновременных войны и рейда у одного города). */
    private ru.pewars.server.raid.RaidManager raids;

    public void setRaidManager(ru.pewars.server.raid.RaidManager raids) {
        this.raids = raids;
    }

    // ===================== Declaration =====================

    public String declareWar(Player declarer, String defenderTownName) {
        if (!towny.townyReady()) return "§cTowny не найден на сервере.";
        Object attackerTown = towny.getTown(declarer);
        if (attackerTown == null) {
            return color(config.chat("war-no-town"));
        }
        if (!towny.isMayor(declarer) && !declarer.hasPermission("pewars.admin")) {
            return color(config.chat("not-mayor"));
        }
        Object defenderTown = towny.getTownByName(defenderTownName);
        if (defenderTown == null) {
            return color(config.chat("town-not-found", "name", defenderTownName));
        }
        if (towny.townName(defenderTown).equalsIgnoreCase(towny.townName(attackerTown))) {
            return color(config.chat("war-self-target"));
        }
        // Запрет двойного объявления: нельзя, если атакующий или защитник уже в войне.
        String attackerName = towny.townName(attackerTown);
        String defenderName = towny.townName(defenderTown);
        if (warsByTown.containsKey(attackerName.toLowerCase(Locale.ROOT))
                || warsByTown.containsKey(defenderName.toLowerCase(Locale.ROOT))) {
            return color(config.chat("war-already-active"));
        }

        // П.13 ТЗ: ПАРНЫЙ кулдаун — нельзя объявлять войну между ЭТИМИ двумя городами.
        long now = System.currentTimeMillis();
        Long pairCd = warCooldowns.get(pairKey(attackerName, defenderName));
        if (pairCd != null && pairCd > now && !declarer.hasPermission("pewars.admin")) {
            return color(config.chat("war-cooldown-pair",
                    "other", defenderName,
                    "time", formatDuration(pairCd - now)));
        }

        // П.12 ТЗ: нейтралитет проверяется ТОЛЬКО у города-ЦЕЛИ (защитника).
        if (isNeutralTown(defenderName)) {
            return color(config.chat("war-neutral-target", "defender", defenderName));
        }
        // Запрет одновременных войны и рейда с участием одного города.
        if (raids != null && (raids.isTownRaided(attackerName) || raids.isTownRaided(defenderName))) {
            return color(config.chat("war-raid-conflict"));
        }
        // Минимум онлайн в городе-защитнике (п.4 ТЗ: от 3 игроков).
        int defenderOnline = countOnlineInTown(defenderName);
        if (defenderOnline < config.warMinOnline) {
            return color(config.chat("war-min-online", "min", String.valueOf(config.warMinOnline),
                    "online", String.valueOf(defenderOnline)));
        }
        // Если у защитника нет homeblock-а — центрального чанка не будет, отказываем честно.
        if (towny.homeblockCoord(defenderTown) == null) {
            return color(config.chat("war-no-homeblock", "name", defenderName));
        }
        if (!towny.economyReady()) return "§cVault economy не найден.";

        // П.4 ТЗ: стоимость списывается из КАЗНЫ ГОРОДА (Town.getAccount()).
        double cost = config.warCost;
        if (towny.townBalance(attackerTown) < cost) {
            return color(config.chat("war-cannot-afford", "cost", format(cost)));
        }
        if (!towny.withdrawTown(attackerTown, cost)) {
            return color(config.chat("war-cannot-afford", "cost", format(cost)));
        }

        now = System.currentTimeMillis();
        long prepEnd = now + config.warPrepSeconds * 1000L;
        long activeEnd = prepEnd + config.warActiveSeconds * 1000L;

        War war = new War(UUID.randomUUID(), attackerName, defenderName, prepEnd, activeEnd);
        war.attackerTown = attackerTown;
        war.defenderTown = defenderTown;
        war.centralChunk = towny.homeblockCoord(defenderTown);
        for (ChunkCoord cc : towny.townChunkCoords(defenderTown)) {
            war.chunkStatus.put(cc, false);
        }

        wars.put(war.id, war);
        warsByTown.put(defenderName.toLowerCase(Locale.ROOT), war);
        warsByTown.put(attackerName.toLowerCase(Locale.ROOT), war);

        int prepMin = (int) (config.warPrepSeconds / 60);
        // П.4 ТЗ: оповещение об объявлении и подготовке — в ЧАТ СЕРВЕРА.
        Bukkit.broadcastMessage(color(config.chat("war-declared",
                "attacker", war.attackerTownName,
                "defender", war.defenderTownName,
                "prep", String.valueOf(prepMin))));

        playSoundToParticipants(config.warDeclaredSound, war, declarer);

        // BossBar с отсчётом подготовки.
        createBossBar(war);
        return null;
    }

    // ===================== Neutrality (п.12) =====================

    /**
     * Проверка нейтралитета города через pe_townymenu (рефлексия, мягкая зависимость).
     * Публичный — используется GUI (StateSerializer) для отображения статуса (п.3 ТЗ).
     */
    public boolean isNeutralTown(String townName) {
        if (townName == null || townName.isBlank()) return false;
        org.bukkit.plugin.Plugin peTownyMenu = Bukkit.getPluginManager().getPlugin("pe_townymenu-server");
        if (peTownyMenu == null || !peTownyMenu.isEnabled()) return false;
        try {
            java.lang.reflect.Method getNeutralityManager = peTownyMenu.getClass().getMethod("getNeutralityManager");
            Object neutralityManager = getNeutralityManager.invoke(peTownyMenu);
            if (neutralityManager == null) return false;
            java.lang.reflect.Method hasNeutrality = neutralityManager.getClass().getMethod("hasNeutrality", String.class);
            return Boolean.TRUE.equals(hasNeutrality.invoke(neutralityManager, townName));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to check neutrality: " + e.getMessage());
            return false;
        }
    }

    // ===================== Lookup =====================

    public War getWarByDefender(String defenderTownName) {
        if (defenderTownName == null) return null;
        return warsByTown.get(defenderTownName.toLowerCase(Locale.ROOT));
    }

    public War getWarInvolving(String townName) {
        if (townName == null) return null;
        return warsByTown.get(townName.toLowerCase(Locale.ROOT));
    }

    public War getWarInvolvingNation(String nationName) {
        if (nationName == null || nationName.isBlank()) return null;
        for (War war : wars.values()) {
            if (war.attackerNations.contains(nationName) || war.defenderNations.contains(nationName)) {
                return war;
            }
        }
        return null;
    }

    public List<War> activeWars() {
        List<War> result = new ArrayList<>();
        for (War war : wars.values()) {
            if (war.isActive()) result.add(war);
        }
        return result;
    }
    
    public List<String> getWarHelpers(String townName) {
        if (townName == null) return Collections.emptyList();
        War war = getWarInvolving(townName);
        if (war == null || !war.isActive()) return Collections.emptyList();
        
        List<String> helpers = new ArrayList<>();
        boolean isAttacker = townName.equalsIgnoreCase(war.attackerTownName);
        boolean isDefender = townName.equalsIgnoreCase(war.defenderTownName);
        
        List<String> nations = new ArrayList<>();
        if (isAttacker) nations.addAll(war.attackerNations);
        if (isDefender) nations.addAll(war.defenderNations);
        
        for (String nationName : nations) {
            Object nation = towny.getNationByName(nationName);
            if (nation != null) {
                List<String> residents = towny.getNationResidents(nation);
                if (residents != null) helpers.addAll(residents);
            }
        }
        return helpers;
    }

    public List<War> history() {
        return new ArrayList<>(history);
    }

    public boolean isAtWar(String townName) {
        return getWarInvolving(townName) != null;
    }

    /** Война по id (O(1); используется CaptureFlagManager-ом). */
    public War getWar(UUID id) {
        if (id == null) return null;
        return wars.get(id);
    }

    // ===================== Persistence hooks (п.16) =====================

    /** Снимок парных кулдаунов для сохранения в БД. */
    public Map<String, Long> cooldownSnapshot() {
        long now = System.currentTimeMillis();
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, Long> e : warCooldowns.entrySet()) {
            if (e.getValue() != null && e.getValue() > now) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** Восстановление кулдаунов из БД при старте. */
    public void restoreCooldowns(Map<String, Long> snapshot) {
        if (snapshot == null) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : snapshot.entrySet()) {
            if (e.getValue() != null && e.getValue() > now) {
                warCooldowns.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
    }

    /** Восстановление войны из БД при старте сервера. */
    public void restoreWar(War war) {
        if (war == null || war.phase == WarPhase.ENDED) return;
        refreshTownyReferences(war);
        if (war.phase == WarPhase.DECISION) {
            // Война выиграна атакующими, но исход ещё не выбран — восстанавливаем ожидание.
            pendingDecisions.put(war.id, war);
            return;
        }
        wars.put(war.id, war);
        warsByTown.put(war.defenderTownName.toLowerCase(Locale.ROOT), war);
        warsByTown.put(war.attackerTownName.toLowerCase(Locale.ROOT), war);
        war.lastAttackerSeen = System.currentTimeMillis();
        // Если война была активной — снова форсируем боевые флаги городов.
        if (war.phase == WarPhase.ACTIVE) {
            applyWarFlags(war);
        }
        createBossBar(war);
    }

    /** Все текущие (не завершённые) войны — для периодического сохранения. */
    public List<War> allWars() {
        List<War> all = new ArrayList<>(wars.values());
        all.addAll(pendingDecisions.values());
        return all;
    }

    /** ПАРНЫЙ ключ кулдауна (п.13): одинаков для (a,b) и (b,a). */
    public static String pairKey(String a, String b) {
        String la = a == null ? "" : a.toLowerCase(Locale.ROOT);
        String lb = b == null ? "" : b.toLowerCase(Locale.ROOT);
        return la.compareTo(lb) <= 0 ? la + "|" + lb : lb + "|" + la;
    }

    /** Действует ли парный КД войны между городами (для GUI, п.3). */
    public boolean isPairOnCooldown(String a, String b) {
        Long until = warCooldowns.get(pairKey(a, b));
        return until != null && until > System.currentTimeMillis();
    }

    /**
     * Очистка при выключении плагина: восстанавливаем флаги ОБОИХ городов и чистим
     * голограммы/BossBar-ы. Состояние войн перед этим сохраняется в БД (PeWarsPlugin).
     */
    public void shutdown() {
        for (War war : wars.values()) {
            if (war.phase == WarPhase.ACTIVE) {
                restoreTownFlags(war);
            }
            for (ChunkCoord coord : new ArrayList<>(war.chunkStatus.keySet())) {
                removeChunkHologram(coord);
            }
        }
        for (ChunkCoord coord : new ArrayList<>(chunkHolograms.keySet())) {
            removeChunkHologram(coord);
        }
        for (BossBar bar : bossBars.values()) {
            bar.removeAll();
        }
        bossBars.clear();
        wars.clear();
        warsByTown.clear();
    }

    // ===================== Tick (called each second) =====================

    public void tick() {
        tickCounter++;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, War>> it = wars.entrySet().iterator();
        while (it.hasNext()) {
            War war = it.next().getValue();
            if (!war.isActive()) continue;

            refreshTownyReferences(war);

            // Фазовый переход: подготовка -> активная (автостарт после подготовки, п.4 ТЗ).
            if (war.phase == WarPhase.PREPARATION && now >= war.preparationEnd) {
                war.phase = WarPhase.ACTIVE;
                war.lastAttackerSeen = now;
                applyWarFlags(war);
                Bukkit.broadcastMessage(color(config.chat("war-started",
                        "attacker", war.attackerTownName,
                        "defender", war.defenderTownName,
                        "active", String.valueOf(config.warActiveSeconds / 60))));
                playSoundToParticipants(config.warStartSound, war);
                // П.17 ТЗ: событие для сторонних плагинов.
                Bukkit.getPluginManager().callEvent(
                        new TownWarStartEvent(war.attackerTownName, war.defenderTownName));
            }

            if (war.phase == WarPhase.ACTIVE) {
                // Каждые 5 секунд: форсаж боевых флагов (вырубить PvP НЕЛЬЗЯ) и голод без нации.
                if (tickCounter % 5 == 0) {
                    enforceWarFlags(war);
                    applyHungerToNationless(war);
                }

                // Обработка возврата чанков защитниками (п.6 ТЗ).
                processChunkRecapture(war);

                // Периодическая проверка присутствия атакующих — ТОЛЬКО в активной фазе.
                checkAttackerPresence(war);

                // Завершение по таймауту отсутствия атакующих -> победа защитников.
                if (now - war.lastAttackerSeen > config.warNoAttackersTimeoutSeconds * 1000L) {
                    war.endedByTimeout = true;
                    it.remove();
                    endWar(war, false);
                    continue;
                }
                // П.8 ТЗ: 75% чанков (процент настраивается) -> мгновенная победа атакующих.
                if (war.requiredChunksCaptured(config.warVictoryChunkPercent)) {
                    war.attackerWon = true;
                    it.remove();
                    endWar(war, false);
                    continue;
                }
                // П.7 ТЗ: захвачен центральный чанк (N флагов) -> победа атакующих.
                if (war.centralCaptured(config.centralFlagsRequired)) {
                    war.attackerWon = true;
                    it.remove();
                    endWar(war, false);
                    continue;
                }
                
                // Обработка запросов о помощи нациям
                processPendingHelpRequests(war, now);

                // Завершение по истечении активной фазы -> победа защитников.
                if (now >= war.activeEnd) {
                    it.remove();
                    endWar(war, false);
                    continue;
                }
            }

            updateBossBar(war, now);
        }
    }

    // ===================== War flags: force PvP (п.4/п.11) =====================

    /** Сохраняет исходные флаги и включает боевой режим в ОБОИХ городах. */
    private void applyWarFlags(War war) {
        if (war.defenderTown != null) {
            if (war.defenderOriginalPvp == null) {
                war.defenderOriginalPvp = towny.getTownPvp(war.defenderTown);
            }
            if (war.defenderOriginalExplosion == null) {
                war.defenderOriginalExplosion = towny.getTownExplosion(war.defenderTown);
            }
            if (war.defenderOriginalOutsiderPerms == null) {
                war.defenderOriginalOutsiderPerms = towny.getTownOutsiderPermissions(war.defenderTown);
            }
            towny.setTownPvp(war.defenderTown, true);
            if (config.warAllowExplosions) {
                towny.setTownExplosion(war.defenderTown, true);
            }
            towny.setTownOutsiderPermissions(war.defenderTown, true);
        }
        // ФИКС по ТЗ: PvP включается И в ГОРОДЕ-АТАКУЮЩЕМ (включая все его аванпосты).
        // Раньше атакующему форсировали только права чужаков — из-за этого в его
        // городе и аванпостах PvP не включалось вообще — это и был главный баг.
        if (war.attackerTown != null) {
            if (war.attackerOriginalPvp == null) {
                war.attackerOriginalPvp = towny.getTownPvp(war.attackerTown);
            }
            if (war.attackerOriginalExplosion == null) {
                war.attackerOriginalExplosion = towny.getTownExplosion(war.attackerTown);
            }
            if (war.attackerOriginalOutsiderPerms == null) {
                war.attackerOriginalOutsiderPerms = towny.getTownOutsiderPermissions(war.attackerTown);
            }
            towny.setTownPvp(war.attackerTown, true);
            if (config.warAllowExplosions) {
                towny.setTownExplosion(war.attackerTown, true);
            }
            towny.setTownOutsiderPermissions(war.attackerTown, true);
        }
    }

    /**
     * Периодический форсаж боевых флагов: если кто-то (админ, другой плагин,
     * обход блокировки команд) выключил PvP/взрывы — немедленно включаем обратно.
     * Гарантия ТЗ: «вырубить PvP нельзя».
     */
    private void enforceWarFlags(War war) {
        if (war.defenderTown != null) {
            if (!Boolean.TRUE.equals(towny.getTownPvp(war.defenderTown))) {
                towny.setTownPvp(war.defenderTown, true);
            }
            if (config.warAllowExplosions && !Boolean.TRUE.equals(towny.getTownExplosion(war.defenderTown))) {
                towny.setTownExplosion(war.defenderTown, true);
            }
        }
        if (war.attackerTown != null) {
            if (!Boolean.TRUE.equals(towny.getTownPvp(war.attackerTown))) {
                towny.setTownPvp(war.attackerTown, true);
            }
            if (config.warAllowExplosions && !Boolean.TRUE.equals(towny.getTownExplosion(war.attackerTown))) {
                towny.setTownExplosion(war.attackerTown, true);
            }
        }
    }

    /** Восстанавливает исходные флаги ОБОИХ городов после войны. */
    private void restoreTownFlags(War war) {
        if (war.defenderTown != null) {
            if (war.defenderOriginalPvp != null) {
                towny.setTownPvp(war.defenderTown, war.defenderOriginalPvp);
            }
            if (war.defenderOriginalExplosion != null) {
                towny.setTownExplosion(war.defenderTown, war.defenderOriginalExplosion);
            }
            if (war.defenderOriginalOutsiderPerms != null) {
                towny.restoreTownOutsiderPermissions(war.defenderTown, war.defenderOriginalOutsiderPerms);
            }
        }
        if (war.attackerTown != null) {
            if (war.attackerOriginalPvp != null) {
                towny.setTownPvp(war.attackerTown, war.attackerOriginalPvp);
            }
            if (war.attackerOriginalExplosion != null) {
                towny.setTownExplosion(war.attackerTown, war.attackerOriginalExplosion);
            }
            if (war.attackerOriginalOutsiderPerms != null) {
                towny.restoreTownOutsiderPermissions(war.attackerTown, war.attackerOriginalOutsiderPerms);
            }
        }
    }

    /**
     * П.4 ТЗ: игроки БЕЗ НАЦИИ, не участвующие в войне, при нахождении в зоне
     * войны (чанки обоих городов) получают эффект голода.
     */
    private void applyHungerToNationless(War war) {
        if (!config.hungerForNationless) return;
        for (Player online : Bukkit.getOnlinePlayers()) {
            Object townAt = towny.getTownAt(online.getLocation());
            if (townAt == null) continue;
            String zone = towny.townName(townAt);
            if (!war.attackerTownName.equalsIgnoreCase(zone) && !war.defenderTownName.equalsIgnoreCase(zone)) {
                continue;
            }
            // Участники войны эффект не получают.
            Object playerTown = towny.getTown(online);
            if (playerTown != null) {
                String name = towny.townName(playerTown);
                if (war.attackerTownName.equalsIgnoreCase(name) || war.defenderTownName.equalsIgnoreCase(name)) {
                    continue;
                }
            }
            // Только игроки без нации.
            if (towny.getNation(online) != null) continue;
            online.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 20 * 8, 0, true, true, true));
        }
    }

    /** Периодическая проверка присутствия атакующих в радиусе 2 чанков от города-защитника. */
    private void checkAttackerPresence(War war) {
        if (war.defenderTown == null) return;
        List<ChunkCoord> defenderChunks = towny.townChunkCoords(war.defenderTown);
        if (defenderChunks.isEmpty()) return;
        for (Player online : Bukkit.getOnlinePlayers()) {
            Object playerTown = towny.getTown(online);
            if (playerTown == null) continue;
            if (!war.attackerTownName.equalsIgnoreCase(towny.townName(playerTown))) continue;
            if (towny.isWithinTwoChunks(online.getLocation(), defenderChunks)) {
                war.lastAttackerSeen = System.currentTimeMillis();
                return;
            }
        }
    }

    // ===================== Surrender / Peace (п.8/п.9) =====================

    /**
     * Капитуляция (п.8 ТЗ). Мэр любой из сторон подписывает капитуляцию своего города:
     *  - капитулировал защитник -> победа атакующих (захваченные чанки остаются у них);
     *  - капитулировал атакующий -> победа защитников (чанки возвращаются).
     * Дополнительных штрафов нет (п.9 ТЗ). Возвращает null при успехе, иначе — текст ошибки.
     */
    public String surrender(Player initiator) {
        Object town = towny.getTown(initiator);
        if (town == null) return color(config.chat("war-not-active"));
        String townName = towny.townName(town);
        War war = getWarInvolving(townName);
        if (war == null) return color(config.chat("war-not-active"));
        if (war.phase != WarPhase.ACTIVE) {
            return color(config.chat("war-not-started-yet"));
        }
        if (!towny.isMayor(initiator) && !initiator.hasPermission("pewars.admin")) {
            return color(config.chat("not-mayor"));
        }

        war.surrenderedBy = townName;
        if (townName.equalsIgnoreCase(war.defenderTownName)) {
            war.attackerWon = true; // Капитуляция защитника -> победа атакующих.
        }
        wars.remove(war.id);
        endWar(war, false);
        return null;
    }

    /**
     * Мирный договор (п.9 ТЗ). Первый вызов мэра одной стороны — ПРЕДЛОЖЕНИЕ;
     * вызов мэра другой стороны — ПОДПИСАНИЕ: война завершается без победителя,
     * захваченные чанки возвращаются защитнику, штрафов нет.
     */
    public String peace(Player initiator) {
        Object town = towny.getTown(initiator);
        if (town == null) return color(config.chat("war-not-active"));
        String townName = towny.townName(town);
        War war = getWarInvolving(townName);
        if (war == null) return color(config.chat("war-not-active"));
        if (war.phase != WarPhase.ACTIVE) {
            return color(config.chat("war-not-started-yet"));
        }
        if (!towny.isMayor(initiator) && !initiator.hasPermission("pewars.admin")) {
            return color(config.chat("not-mayor"));
        }

        if (war.peaceOfferedBy == null) {
            war.peaceOfferedBy = townName;
            String other = townName.equalsIgnoreCase(war.attackerTownName)
                    ? war.defenderTownName : war.attackerTownName;
            Bukkit.broadcastMessage(color(config.chat("war-peace-offer",
                    "town", townName, "other", other)));
            return null;
        }
        if (war.peaceOfferedBy.equalsIgnoreCase(townName)) {
            return color(config.chat("war-peace-already"));
        }
        // Вторая сторона подписала — мирный договор заключён.
        war.peaceTreaty = true;
        wars.remove(war.id);
        endWar(war, false);
        return null;
    }

    /**
     * Мгновенная победа атакующих (вызывается CaptureFlagManager-ом при захвате
     * центрального чанка, п.7 ТЗ).
     */
    public void declareAttackerVictory(War war) {
        if (war == null || war.phase != WarPhase.ACTIVE) return;
        war.attackerWon = true;
        wars.remove(war.id);
        endWar(war, false);
    }

    // ===================== End / Cancel =====================

    public String cancelWar(Player initiator, String townName) {
        boolean forceByAdmin = initiator.hasPermission("pewars.admin");
        War war;
        if (townName == null || townName.isBlank()) {
            Object town = towny.getTown(initiator);
            if (town == null) {
                return color(config.chat("war-not-active"));
            }
            String initiatorTown = towny.townName(town);
            war = getWarInvolving(initiatorTown);
            if (war == null) {
                return color(config.chat("war-not-active"));
            }
        } else {
            war = getWarInvolving(townName);
            if (war == null) {
                return color(config.chat("town-not-found", "name", townName));
            }
        }

        if (!forceByAdmin) {
            // Только мэр может отменить, и только в фазе подготовки — активная
            // война завершается капитуляцией или мирным договором (п.9 ТЗ).
            if (!towny.isMayor(initiator)) {
                return color(config.chat("not-mayor"));
            }
            Object initiatorTown = towny.getTown(initiator);
            String initiatorTownName = towny.townName(initiatorTown);
            if (!initiatorTownName.equalsIgnoreCase(war.attackerTownName) && !initiatorTownName.equalsIgnoreCase(war.defenderTownName)) {
                return color(config.chat("war-cancel-no-permission"));
            }
        }

        // Возврат стоимости при отмене в фазе подготовки.
        if (war.phase == WarPhase.PREPARATION) {
            Object attackerTown = war.attackerTown != null ? war.attackerTown : towny.getTownByName(war.attackerTownName);
            if (attackerTown != null && towny.depositTown(attackerTown, config.warCost)) {
                initiator.sendMessage(color(config.chat("war-cancel-refund", "cost", format(config.warCost))));
            }
        }

        wars.remove(war.id);
        endWar(war, true);
        return null;
    }

    /**
     * Завершение войны. Исход определяется по полям war:
     * attackerWon / peaceTreaty / surrenderedBy / endedByTimeout / истечение времени.
     * ВЫЗЫВАТЬ только ПОСЛЕ удаления войны из карты wars (или через iterator.remove()).
     */
    private void endWar(War war, boolean byCancel) {
        war.phase = WarPhase.ENDED;
        removeBossBar(war);
        for (ChunkCoord coord : new ArrayList<>(war.chunkStatus.keySet())) {
            removeChunkHologram(coord);
        }

        // Восстанавливаем исходные флаги PvP/взрывов/прав чужаков ОБОИХ городов.
        refreshTownyReferences(war);
        restoreTownFlags(war);

        TownWarEndEvent.Result result;
        int capturedChunks = war.capturedCount();

        if (byCancel) {
            result = TownWarEndEvent.Result.CANCELLED;
            int reverted = revertCapturedChunksToDefender(war);
            Bukkit.broadcastMessage(color(config.chat("war-cancelled",
                    "attacker", war.attackerTownName,
                    "defender", war.defenderTownName)));
            if (reverted > 0) {
                Bukkit.broadcastMessage(color("&b\uD83D\uDEE1 Все временно захваченные чанки (" + reverted + ") возвращены городу " + war.defenderTownName + "."));
            }
            sendTitleGlobal(config.title("war-cancelled-title", "attacker", war.attackerTownName, "defender", war.defenderTownName),
                            config.title("war-cancelled-subtitle", "attacker", war.attackerTownName, "defender", war.defenderTownName));
        } else if (war.peaceTreaty) {
            // П.9 ТЗ: мирный договор — без победителя и штрафов, чанки возвращаются.
            result = TownWarEndEvent.Result.PEACE_TREATY;
            revertCapturedChunksToDefender(war);
            Bukkit.broadcastMessage(color(config.chat("war-peace-signed",
                    "attacker", war.attackerTownName,
                    "defender", war.defenderTownName)));
            sendTitleGlobal(config.title("war-peace-title", "attacker", war.attackerTownName, "defender", war.defenderTownName),
                            config.title("war-peace-subtitle", "attacker", war.attackerTownName, "defender", war.defenderTownName));
        } else if (war.attackerWon) {
            // Победа атакующих (75% / центральный чанк / капитуляция защитника).
            result = TownWarEndEvent.Result.ATTACKER_WIN;
            int transferred = transferCapturedChunksToAttacker(war);
            if (war.surrenderedBy != null) {
                Bukkit.broadcastMessage(color(config.chat("war-surrendered",
                        "town", war.surrenderedBy,
                        "winner", war.attackerTownName)));
            }
            Bukkit.broadcastMessage(color(config.chat("war-won-attackers",
                    "attacker", war.attackerTownName,
                    "defender", war.defenderTownName,
                    "chunks", String.valueOf(transferred))));
            sendTitleGlobal(config.title("war-won-attackers-title", "attacker", war.attackerTownName, "defender", war.defenderTownName),
                            config.title("war-won-attackers-subtitle", "attacker", war.attackerTownName, "defender", war.defenderTownName));
        } else if (war.surrenderedBy != null) {
            // Капитуляция атакующего -> победа защитников.
            result = TownWarEndEvent.Result.DEFENDER_WIN;
            revertCapturedChunksToDefender(war);
            Bukkit.broadcastMessage(color(config.chat("war-surrendered",
                    "town", war.surrenderedBy,
                    "winner", war.defenderTownName)));
            Bukkit.broadcastMessage(color(config.chat("war-won-defenders",
                    "attacker", war.attackerTownName,
                    "defender", war.defenderTownName)));
            sendTitleGlobal(config.title("war-won-defenders-title", "attacker", war.attackerTownName, "defender", war.defenderTownName),
                            config.title("war-won-defenders-subtitle", "attacker", war.attackerTownName, "defender", war.defenderTownName));
        } else if (war.endedByTimeout) {
            // Атакующие покинули войну -> победа защитников.
            result = TownWarEndEvent.Result.DEFENDER_WIN;
            int reverted = revertCapturedChunksToDefender(war);
            Bukkit.broadcastMessage(color(config.chat("war-timeout",
                    "attacker", war.attackerTownName,
                    "defender", war.defenderTownName)));
            if (reverted > 0) {
                Bukkit.broadcastMessage(color("&b\uD83D\uDEE1 Все временно захваченные чанки (" + reverted + ") возвращены городу " + war.defenderTownName + "."));
            }
            sendTitleGlobal(config.title("war-timeout-title", "attacker", war.attackerTownName, "defender", war.defenderTownName),
                            config.title("war-timeout-subtitle", "attacker", war.attackerTownName, "defender", war.defenderTownName));
        } else {
            // Время активной фазы истекло, победы атакующих нет -> победа защитников.
            result = TownWarEndEvent.Result.DEFENDER_WIN;
            int reverted = revertCapturedChunksToDefender(war);
            Bukkit.broadcastMessage(color(config.chat("war-won-defenders",
                    "attacker", war.attackerTownName,
                    "defender", war.defenderTownName)));
            if (reverted > 0) {
                Bukkit.broadcastMessage(color("&b\uD83D\uDEE1 Все временно захваченные чанки (" + reverted + ") возвращены городу " + war.defenderTownName + "."));
            }
            sendTitleGlobal(config.title("war-won-defenders-title", "attacker", war.attackerTownName, "defender", war.defenderTownName),
                            config.title("war-won-defenders-subtitle", "attacker", war.attackerTownName, "defender", war.defenderTownName));
        }

        warsByTown.remove(war.defenderTownName.toLowerCase(Locale.ROOT));
        warsByTown.remove(war.attackerTownName.toLowerCase(Locale.ROOT));
        wars.remove(war.id);

        // П.13 ТЗ: ПАРНЫЙ кулдаун между ЭТИМИ городами (при отмене в подготовке КД не ставим).
        if (!byCancel) {
            long cdUntil = System.currentTimeMillis() + config.warCooldownHours * 3600 * 1000L;
            warCooldowns.put(pairKey(war.attackerTownName, war.defenderTownName), cdUntil);
        }

        history.add(0, war);
        while (history.size() > 8) history.remove(history.size() - 1);
        playSoundToParticipants(config.warEndSound, war);

        // П.17 ТЗ: событие завершения войны для сторонних плагинов.
        Bukkit.getPluginManager().callEvent(new TownWarEndEvent(
                war.attackerTownName, war.defenderTownName, result, war.surrenderedBy, capturedChunks));

        // НОВОЕ: после победы атакующих мэр победителей выбирает исход войны
        // (казна / полный захват города / смена мэра) в меню войн.
        if (result == TownWarEndEvent.Result.ATTACKER_WIN) {
            war.phase = WarPhase.DECISION;
            pendingDecisions.put(war.id, war);
            Bukkit.broadcastMessage(color(config.chat("war-decision-available",
                    "attacker", war.attackerTownName,
                    "defender", war.defenderTownName)));
        }
    }

    /** Возврат всех захваченных чанков городу-защитнику. */
    private int revertCapturedChunksToDefender(War war) {
        Object defenderTown = war.defenderTown != null ? war.defenderTown : towny.getTownByName(war.defenderTownName);
        if (defenderTown == null) return 0;
        int reverted = 0;
        for (Map.Entry<ChunkCoord, Boolean> entry : war.chunkStatus.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) continue;
            ChunkCoord coord = entry.getKey();
            Object tb = towny.getTownBlock(coord);
            if (tb != null && towny.transferChunk(tb, defenderTown)) {
                reverted++;
            }
        }
        return reverted;
    }

    /** Передача всех захваченных чанков городу-атакующему (только при ПОБЕДЕ атакующих). */
    private int transferCapturedChunksToAttacker(War war) {
        Object receiverTown = war.attackerTown != null ? war.attackerTown : towny.getTownByName(war.attackerTownName);
        if (receiverTown == null) return 0;
        int transferred = 0;
        for (Map.Entry<ChunkCoord, Boolean> entry : war.chunkStatus.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) continue;
            ChunkCoord coord = entry.getKey();
            Object tb = towny.getTownBlock(coord);
            if (tb != null && towny.transferChunk(tb, receiverTown)) {
                transferred++;
            }
        }
        return transferred;
    }

    private void refreshTownyReferences(War war) {
        if (war.attackerTown == null) war.attackerTown = towny.getTownByName(war.attackerTownName);
        if (war.defenderTown == null) war.defenderTown = towny.getTownByName(war.defenderTownName);
    }

    // ===================== BossBar =====================

    private void createBossBar(War war) {
        if (war.phase == WarPhase.ENDED) return;
        BossBar bar = Bukkit.createBossBar(
                ChatColor.GOLD + "\u2694 Подготовка к войне: " + war.attackerTownName + " \u2192 " + war.defenderTownName,
                BarColor.YELLOW, BarStyle.SEGMENTED_20);
        bar.setProgress(1.0);
        bar.setVisible(true);
        showBarToParticipants(bar, war);
        bossBars.put(war.id, bar);
    }

    private void updateBossBar(War war, long now) {
        BossBar bar = bossBars.get(war.id);
        if (bar == null) return;
        long end = war.phase == WarPhase.PREPARATION ? war.preparationEnd : war.activeEnd;
        long total = war.phase == WarPhase.PREPARATION ? config.warPrepSeconds * 1000L : config.warActiveSeconds * 1000L;
        long remaining = Math.max(0, end - now);
        double progress = total > 0 ? Math.max(0, Math.min(1, (double) remaining / total)) : 0;
        bar.setProgress(progress);
        String title;
        if (war.phase == WarPhase.PREPARATION) {
            title = ChatColor.GOLD + "\u2694 Подготовка: " + formatDuration(remaining)
                    + " | " + war.attackerTownName + " \u2192 " + war.defenderTownName;
            bar.setColor(BarColor.YELLOW);
        } else {
            int percent = (int) Math.round(war.capturedPercent());
            title = ChatColor.RED + "\u2694 Война идёт: " + formatDuration(remaining)
                    + " | Захвачено " + percent + "% (" + war.capturedCount() + "/" + war.totalChunks() + " чанков)";
            bar.setColor(BarColor.RED);
        }
        bar.setTitle(title);
        showBarToParticipants(bar, war);
    }

    private void removeBossBar(War war) {
        BossBar bar = bossBars.remove(war.id);
        if (bar != null) {
            bar.removeAll();
        }
    }

    private void showBarToParticipants(BossBar bar, War war) {
        bar.removeAll();
        Set<Player> viewers = new HashSet<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            Object playerTown = towny.getTown(online);
            if (playerTown == null) continue;
            String name = towny.townName(playerTown);
            if (war.attackerTownName.equalsIgnoreCase(name) || war.defenderTownName.equalsIgnoreCase(name)) {
                viewers.add(online);
            }
        }
        for (Player p : viewers) bar.addPlayer(p);
    }

    /** Проигрывает звук всем участникам войны (атакующие + защитники). */
    private void playSoundToParticipants(org.bukkit.Sound sound, War war) {
        if (!config.soundsEnabled || sound == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Object town = towny.getTown(p);
            if (town != null) {
                String name = towny.townName(town);
                if (war.attackerTownName.equalsIgnoreCase(name) || war.defenderTownName.equalsIgnoreCase(name)) {
                    p.playSound(p.getLocation(), sound, config.soundVolume, config.soundPitch);
                }
            }
        }
    }

    /** Проигрывает звук (по имени или Sound enum) участникам войны и инициатору. */
    private void playSoundToParticipants(String soundName, War war, Player declarer) {
        if (!config.soundsEnabled || soundName == null || soundName.isBlank()) return;
        Set<Player> targets = new HashSet<>();
        if (declarer != null && declarer.isOnline()) {
            targets.add(declarer);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            Object town = towny.getTown(p);
            if (town != null) {
                String name = towny.townName(town);
                if (war.attackerTownName.equalsIgnoreCase(name) || war.defenderTownName.equalsIgnoreCase(name)) {
                    targets.add(p);
                }
            }
        }
        for (Player p : targets) {
            playSound(p, soundName, config.soundVolume, config.soundPitch);
        }
    }

    private void playSound(Player player, String soundName, float volume, float pitch) {
        if (player == null || soundName == null || soundName.isBlank()) return;
        try {
            Sound enumSound = Sound.valueOf(soundName.trim().toUpperCase(Locale.ROOT));
            player.playSound(player.getLocation(), enumSound, volume, pitch);
        } catch (IllegalArgumentException e) {
            player.playSound(player.getLocation(), soundName, volume, pitch);
        }
    }

    private void sendTitleGlobal(String titleRaw, String subtitleRaw) {
        String title = color(titleRaw);
        String subtitle = color(subtitleRaw);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(title, subtitle, 10, 100, 20);
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

    // ===================== Chunk Recapture (п.6 ТЗ) =====================

    /**
     * Возврат чанка защитниками БЕЗ флага (п.6 ТЗ):
     * условие — в чанке есть хотя бы один защитник и НИ ОДНОГО игрока
     * атакующей стороны. Удержание config.recaptureSeconds секунд.
     */
    private void processChunkRecapture(War war) {
        if (war == null || war.phase != WarPhase.ACTIVE) return;

        int needSec = Math.max(1, (int) config.recaptureSeconds);

        for (Map.Entry<ChunkCoord, Boolean> entry : new ArrayList<>(war.chunkStatus.entrySet())) {
            if (!Boolean.TRUE.equals(entry.getValue())) continue; // только захваченные чанки

            ChunkCoord coord = entry.getKey();
            org.bukkit.World world = Bukkit.getWorld(coord.world);
            if (world == null) continue;

            // Визуальное выделение захваченного чанка.
            highlightCapturedChunk(world, coord);

            List<Player> defendersInChunk = new ArrayList<>();
            List<Player> attackersInChunk = new ArrayList<>();

            for (Player p : world.getPlayers()) {
                if (p.isDead()) continue;
                if ((p.getLocation().getBlockX() >> 4) == coord.x && (p.getLocation().getBlockZ() >> 4) == coord.z) {
                    Object town = towny.getTown(p);
                    if (town != null) {
                        String name = towny.townName(town);
                        if (war.defenderTownName.equalsIgnoreCase(name)) {
                            defendersInChunk.add(p);
                        } else if (war.attackerTownName.equalsIgnoreCase(name)) {
                            attackersInChunk.add(p);
                        }
                    }
                }
            }

            int currentSec = war.recaptureProgress.getOrDefault(coord, 0);

            // П.6 ТЗ: для возврата на чанке НЕ ДОЛЖНО быть НИ ОДНОГО атакующего.
            if (!defendersInChunk.isEmpty() && attackersInChunk.isEmpty()) {
                currentSec++;
                war.recaptureProgress.put(coord, currentSec);

                spawnRecaptureParticles(world, coord);
                updateChunkHologram(world, coord, war.attackerTownName, currentSec, needSec, true, false,
                        defendersInChunk.size(), attackersInChunk.size());

                String defMsg = color("&b\uD83D\uDEE1 Возврат чанка: &f" + currentSec + "/" + needSec + " &bсек");
                for (Player d : defendersInChunk) {
                    d.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(defMsg));
                }

                if (currentSec >= needSec) {
                    war.markRecaptured(coord);
                    removeChunkHologram(coord);

                    Object tb = towny.getTownBlock(coord);
                    Object defenderTown = war.defenderTown != null ? war.defenderTown : towny.getTownByName(war.defenderTownName);
                    if (tb != null && defenderTown != null) {
                        towny.transferChunk(tb, defenderTown);
                    }

                    String msg = config.chat("recapture-success",
                            "defender", war.defenderTownName,
                            "attacker", war.attackerTownName);
                    if (msg != null && !msg.isBlank()) {
                        Bukkit.broadcastMessage(color(msg));
                    }
                    // П.17 ТЗ: событие возврата чанка.
                    Bukkit.getPluginManager().callEvent(new TownChunkCaptureEvent(
                            war.attackerTownName, war.defenderTownName,
                            coord.world, coord.x, coord.z,
                            war.isCentralChunk(coord), true));
                }
            } else if (!defendersInChunk.isEmpty()) {
                // Атакующие в чанке — возврат приостановлен.
                updateChunkHologram(world, coord, war.attackerTownName, currentSec, needSec, false, true,
                        defendersInChunk.size(), attackersInChunk.size());
                String pausedMsg = color("&c\uD83D\uDEE1 Возврат приостановлен: в чанке атакующие (&f" + attackersInChunk.size() + "&c)");
                for (Player d : defendersInChunk) {
                    d.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(pausedMsg));
                }
            } else {
                updateChunkHologram(world, coord, war.attackerTownName, currentSec, needSec, false, false, 0, attackersInChunk.size());
            }
        }
    }

    private void highlightCapturedChunk(org.bukkit.World world, ChunkCoord coord) {
        org.bukkit.Particle.DustOptions dust = new org.bukkit.Particle.DustOptions(org.bukkit.Color.RED, 1.2f);
        double minX = coord.x << 4;
        double minZ = coord.z << 4;
        double y = world.getHighestBlockYAt((int) minX + 8, (int) minZ + 8) + 1.0;
        for (int i = 0; i <= 16; i += 4) {
            world.spawnParticle(org.bukkit.Particle.DUST, minX + i, y, minZ, 1, 0, 0, 0, 0, dust);
            world.spawnParticle(org.bukkit.Particle.DUST, minX + i, y, minZ + 16, 1, 0, 0, 0, 0, dust);
            world.spawnParticle(org.bukkit.Particle.DUST, minX, y, minZ + i, 1, 0, 0, 0, 0, dust);
            world.spawnParticle(org.bukkit.Particle.DUST, minX + 16, y, minZ + i, 1, 0, 0, 0, 0, dust);
        }
    }

    private void spawnRecaptureParticles(org.bukkit.World world, ChunkCoord coord) {
        org.bukkit.Particle.DustOptions dust = new org.bukkit.Particle.DustOptions(org.bukkit.Color.BLUE, 1.5f);
        double centerX = (coord.x << 4) + 8.0;
        double centerZ = (coord.z << 4) + 8.0;
        double y = world.getHighestBlockYAt((int) centerX, (int) centerZ) + 1.5;
        world.spawnParticle(org.bukkit.Particle.DUST, centerX, y, centerZ, 10, 2.0, 0.5, 2.0, 0, dust);
    }

    // ===================== Frontline Capture =====================

    /**
     * Проверка «линии фронта»: флаг захвата можно установить ТОЛЬКО:
     *  - на чанк, граничащий с уже оккупированным чанком атакующих, ЛИБО
     *  - на ПРИГРАНИЧНЫЙ чанк города (у которого есть сосед вне территории города).
     *
     * Забежать в глубину города и захватить чанк в центре нельзя —
     * захват распространяется от границы вглубь, от уже захваченных чанков.
     * Если защитники вернули чанк — линия фронта пересчитывается автоматически.
     * Аванпосты обычно стоят отдельно и целиком считаются приграничными.
     */
    public boolean canPlaceCaptureFlag(War war, ChunkCoord coord) {
        if (war == null || coord == null) return false;
        if (!war.chunkStatus.containsKey(coord)) return false;
        if (hasAdjacentCaptured(war, coord)) return true;
        return isBorderChunk(war, coord);
    }

    /** Граничит ли чанк (по 4 сторонам) с уже оккупированным чанком атакующих. */
    private boolean hasAdjacentCaptured(War war, ChunkCoord coord) {
        for (ChunkCoord n : neighborChunks(coord)) {
            if (Boolean.TRUE.equals(war.chunkStatus.get(n))) return true;
        }
        return false;
    }

    /** Приграничный чанк города: хотя бы один сосед НЕ принадлежит городу-защитнику. */
    private boolean isBorderChunk(War war, ChunkCoord coord) {
        for (ChunkCoord n : neighborChunks(coord)) {
            if (!war.chunkStatus.containsKey(n)) return true;
        }
        return false;
    }

    private List<ChunkCoord> neighborChunks(ChunkCoord coord) {
        List<ChunkCoord> result = new ArrayList<>(4);
        result.add(new ChunkCoord(coord.world, coord.x + 1, coord.z));
        result.add(new ChunkCoord(coord.world, coord.x - 1, coord.z));
        result.add(new ChunkCoord(coord.world, coord.x, coord.z + 1));
        result.add(new ChunkCoord(coord.world, coord.x, coord.z - 1));
        return result;
    }

    // ===================== Исход войны (выбор победителя) =====================

    /** Война, ожидающая решения, где данный город — победивший атакующий. */
    public War getPendingDecisionFor(String townName) {
        if (townName == null || townName.isBlank()) return null;
        for (War war : pendingDecisions.values()) {
            if (war.attackerTownName.equalsIgnoreCase(townName)) return war;
        }
        return null;
    }

    /**
     * Применение исхода войны мэром города-победителя.
     * choice: treasury (казна), capture (захват города), mayor (смена мэра).
     * Возвращает текст ошибки или null при успехе.
     */
    public String decide(Player player, String choice, String targetName) {
        Object town = towny.getTown(player);
        String townName = town == null ? null : towny.townName(town);
        War war = getPendingDecisionFor(townName);
        if (war == null) return color(config.chat("war-decision-none"));
        if (!towny.isMayor(player) && !player.hasPermission("pewars.admin")) {
            return color(config.chat("war-decision-not-mayor"));
        }
        refreshTownyReferences(war);
        Object attackerTown = war.attackerTown;
        Object defenderTown = war.defenderTown;
        if (attackerTown == null || defenderTown == null) {
            return color(config.chat("town-not-found"));
        }
        switch (choice) {
            case "treasury" -> {
                // Вариант 1: вся казна проигравшего, захваченные чанки возвращаются городу.
                int reverted = revertCapturedChunksToDefender(war);
                double amount = towny.townBalance(defenderTown);
                if (amount > 0) {
                    towny.withdrawTown(defenderTown, amount);
                    towny.depositTown(attackerTown, amount);
                }
                Bukkit.broadcastMessage(color(config.chat("war-decision-treasury",
                        "attacker", war.attackerTownName,
                        "defender", war.defenderTownName,
                        "amount", String.format(Locale.US, "%.2f", amount),
                        "currency", config.currency,
                        "chunks", String.valueOf(reverted))));
                finalizeDecision(war);
                return null;
            }
            case "capture" -> {
                // Вариант 2: ВСЕ чанки города переходят победителю,
                // на месте центрального чанка создаётся аванпост с именем города.
                int moved = 0;
                for (Map.Entry<ChunkCoord, Object> entry : towny.townBlocksByCoord(defenderTown).entrySet()) {
                    if (towny.transferChunk(entry.getValue(), attackerTown)) moved++;
                }
                ChunkCoord central = war.centralChunk;
                if (central != null) {
                    Object tb = towny.getTownBlock(central);
                    org.bukkit.World world = Bukkit.getWorld(central.world);
                    org.bukkit.Location spawn = null;
                    if (world != null) {
                        int bx = central.x * 16 + 8;
                        int bz = central.z * 16 + 8;
                        int by = world.getHighestBlockYAt(bx, bz) + 1;
                        spawn = new org.bukkit.Location(world, bx + 0.5, by, bz + 0.5);
                    }
                    towny.makeOutpost(tb, war.defenderTownName, attackerTown, spawn);
                }
                Bukkit.broadcastMessage(color(config.chat("war-decision-capture",
                        "attacker", war.attackerTownName,
                        "defender", war.defenderTownName,
                        "chunks", String.valueOf(moved))));
                finalizeDecision(war);
                return null;
            }
            case "mayor" -> {
                // Вариант 3: мэром проигравшего города назначается игрок из города-победителя.
                if (targetName == null || targetName.isBlank()) {
                    return color(config.chat("war-decision-mayor-usage"));
                }
                String targetTown = towny.residentTownName(targetName);
                if (targetTown == null) {
                    return color(config.chat("war-decision-mayor-not-found", "name", targetName));
                }
                if (!targetTown.equalsIgnoreCase(war.attackerTownName)) {
                    return color(config.chat("war-decision-mayor-wrong-town",
                            "name", targetName, "attacker", war.attackerTownName));
                }
                revertCapturedChunksToDefender(war);
                if (!towny.setMayor(defenderTown, targetName, war.defenderTownName)) {
                    return color(config.chat("war-decision-mayor-failed"));
                }
                Bukkit.broadcastMessage(color(config.chat("war-decision-mayor",
                        "player", targetName,
                        "attacker", war.attackerTownName,
                        "defender", war.defenderTownName)));
                finalizeDecision(war);
                return null;
            }
            default -> {
                return color(config.chat("war-decision-unknown"));
            }
        }
    }

    private void finalizeDecision(War war) {
        war.phase = WarPhase.ENDED;
        pendingDecisions.remove(war.id);
    }

    // ===================== Chunk Holograms =====================

    private void updateChunkHologram(org.bukkit.World world, ChunkCoord coord, String attackerTown,
                                     int currentSec, int needSec,
                                     boolean activelyRecapturing, boolean pausedRecapturing,
                                     int defCount, int attCount) {
        if (world == null || coord == null) return;

        double centerX = (coord.x << 4) + 8.5;
        double centerZ = (coord.z << 4) + 8.5;
        double highestY = world.getHighestBlockYAt((int) centerX, (int) centerZ) + 2.5;

        List<String> lines = new ArrayList<>();
        lines.add(color("&c\u2694 ЧАНК ОККУПИРОВАН \u2694"));
        lines.add(color("&7Оккупант: &c" + attackerTown));

        if (activelyRecapturing) {
            lines.add(color("&b\uD83D\uDEE1 Идёт возврат: &f" + currentSec + "/" + needSec + " &bсек (&aЗащитники: " + defCount + "&b)"));
        } else if (pausedRecapturing) {
            lines.add(color("&c\u26A0 Возврат приостановлен: в чанке атакующие (&f" + attCount + "&c)"));
        } else {
            lines.add(color("&7Защитники могут вернуть чанк, зайдя в него без атакующих"));
        }

        List<org.bukkit.entity.ArmorStand> stands = chunkHolograms.get(coord);
        boolean respawn = stands == null || stands.size() != lines.size();
        if (!respawn) {
            for (org.bukkit.entity.ArmorStand stand : stands) {
                if (stand == null || stand.isDead() || !stand.isValid()) {
                    respawn = true;
                    break;
                }
            }
        }
        if (respawn) {
            removeChunkHologram(coord);
            stands = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                org.bukkit.Location loc = new org.bukkit.Location(world, centerX, highestY - i * 0.3, centerZ);
                org.bukkit.entity.ArmorStand stand = world.spawn(loc, org.bukkit.entity.ArmorStand.class);
                stand.setVisible(false);
                stand.setGravity(false);
                stand.setMarker(true);
                stand.setInvulnerable(true);
                stand.setCustomNameVisible(true);
                stands.add(stand);
            }
            chunkHolograms.put(coord, stands);
        }
        for (int i = 0; i < stands.size() && i < lines.size(); i++) {
            stands.get(i).setCustomName(lines.get(i));
        }
    }

    /** Удалить голограмму оккупированного чанка (после возврата или окончания войны). */
    private void removeChunkHologram(ChunkCoord coord) {
        List<org.bukkit.entity.ArmorStand> stands = chunkHolograms.remove(coord);
        if (stands == null) return;
        for (org.bukkit.entity.ArmorStand stand : stands) {
            if (stand != null && !stand.isDead()) {
                stand.remove();
            }
        }
    }

    // ===================== Nation Help =====================

    private void processPendingHelpRequests(War war, long now) {
        Iterator<Map.Entry<String, Long>> it = war.pendingHelpRequestsTime.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            String nationName = entry.getKey();
            long requestTime = entry.getValue();

            // Доставка 2 минуты
            if (now >= requestTime + 2 * 60 * 1000L) {
                it.remove();
                java.util.List<War.WarHelpRequest> pending = war.pendingHelpRequests.remove(nationName);
                if (pending != null && !pending.isEmpty()) {
                    war.deliveredHelpRequests.computeIfAbsent(nationName, k -> new ArrayList<>()).addAll(pending);
                    
                    // Найти короля и отправить сообщение/звук
                    Object nation = towny.getNationByName(nationName);
                    if (nation != null) {
                        String kingName = towny.nationKingName(nation);
                        if (kingName != null) {
                            Player king = Bukkit.getPlayerExact(kingName);
                            if (king != null) {
                                for (War.WarHelpRequest req : pending) {
                                    king.sendMessage(color("&e[!] &fВашей нации пришел запрос на помощь в войне от города &c" + req.townName + "&f. Откройте меню нации чтобы ответить!"));
                                }
                                king.playSound(king.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1.0f, 1.0f);
                            }
                        }
                    }
                }
            }
        }
    }

    public String requestNationHelp(Player initiator, String message) {
        Object town = towny.getTown(initiator);
        if (town == null) return color(config.chat("war-not-active"));
        Object nation = towny.getNation(initiator);
        if (nation == null) return color("&cВаш город не состоит в нации!");
        
        String townName = towny.townName(town);
        String nationName = towny.nationName(nation);
        
        War war = getWarInvolving(townName);
        if (war == null) return color(config.chat("war-not-active"));
        
        if (!towny.isMayor(initiator)) {
            return color(config.chat("not-mayor"));
        }

        if (war.phase != WarPhase.ACTIVE) {
            return color("&cВойна еще не в активной фазе!");
        }

        // 15 минут после начала активной фазы
        long activeStart = war.activeEnd - (config.warActiveSeconds * 1000L);
        if (System.currentTimeMillis() < activeStart + 15 * 60 * 1000L) {
            return color("&cЗапрос о помощи можно отправить только через 15 минут после начала войны!");
        }

        if (war.rejectedNations.contains(nationName.toLowerCase(Locale.ROOT))) {
            return color("&cЭта нация уже отклонила вашу просьбу в этой войне!");
        }

        // Check if this town already sent a request
        if (war.pendingHelpRequestsTime.containsKey(nationName)) {
            // Might have requested by another town in the same nation, but let's check if this specific town requested
            java.util.List<War.WarHelpRequest> pending = war.pendingHelpRequests.get(nationName);
            if (pending != null && pending.stream().anyMatch(r -> r.townName.equalsIgnoreCase(townName))) {
                return color("&cВаш запрос этой нации уже доставляется!");
            }
        }
        
        java.util.List<War.WarHelpRequest> delivered = war.deliveredHelpRequests.get(nationName);
        if (delivered != null && delivered.stream().anyMatch(r -> r.townName.equalsIgnoreCase(townName))) {
            return color("&cВаш запрос ожидает ответа короля!");
        }

        if (war.attackerNations.contains(nationName) || war.defenderNations.contains(nationName)) {
            return color("&cЭта нация уже участвует в войне!");
        }

        war.pendingHelpRequestsTime.put(nationName, System.currentTimeMillis());
        war.pendingHelpRequests.computeIfAbsent(nationName, k -> new ArrayList<>()).add(new War.WarHelpRequest(townName, message));
        return color("&aЗапрос отправлен. Он будет доставлен через 2 минуты.");
    }
    
    /**
     * Получить все доставленные запросы для нации (для отображения в меню короля).
     */
    public List<War.WarHelpRequest> getDeliveredHelpRequestsForNation(String nationName) {
        if (nationName == null) return null;
        List<War.WarHelpRequest> list = new ArrayList<>();
        for (War war : wars.values()) {
            if (war.isActive() && war.deliveredHelpRequests.containsKey(nationName)) {
                list.addAll(war.deliveredHelpRequests.get(nationName));
            }
        }
        return list.isEmpty() ? null : list;
    }
    
    /**
     * Получить время старта доставки запроса от конкретного города (для отображения таймера мэру).
     * @return время в мс или -1, если нет запроса в доставке.
     */
    public long getPendingHelpRequestTime(String nationName, String townName) {
        if (nationName == null || townName == null) return -1;
        for (War war : wars.values()) {
            if (war.isActive() && war.pendingHelpRequests.containsKey(nationName)) {
                for (War.WarHelpRequest req : war.pendingHelpRequests.get(nationName)) {
                    if (req.townName.equalsIgnoreCase(townName)) {
                        return war.pendingHelpRequestsTime.getOrDefault(nationName, -1L);
                    }
                }
            }
        }
        return -1;
    }

    public String acceptNationHelp(Player player, String townName) {
        Object town = towny.getTown(player);
        if (town == null) return color("&cВы не состоите в городе.");
        Object nation = towny.getNation(player);
        if (nation == null) return color("&cВы не состоите в нации.");
        if (!towny.isKing(player)) return color("&cТолько король нации может принять запрос!");

        String nationName = towny.nationName(nation);
        War war = getWarInvolving(townName);
        if (war == null || !war.isActive()) return color("&cЭта война уже окончена или не существует.");

        List<War.WarHelpRequest> delivered = war.deliveredHelpRequests.get(nationName);
        if (delivered == null || delivered.stream().noneMatch(r -> r.townName.equalsIgnoreCase(townName))) {
            return color("&cУ вас нет доставленного запроса от этого города.");
        }

        delivered.removeIf(r -> r.townName.equalsIgnoreCase(townName));
        if (delivered.isEmpty()) {
            war.deliveredHelpRequests.remove(nationName);
        }
        
        if (war.attackerTownName.equalsIgnoreCase(townName)) {
            war.attackerNations.add(nationName);
        } else if (war.defenderTownName.equalsIgnoreCase(townName)) {
            war.defenderNations.add(nationName);
        }
        
        Bukkit.broadcastMessage(color("&e[!] &fНация &c" + nationName + "&f вступила в войну на стороне &c" + townName + "&f!"));
        playSoundToParticipants(config.warDeclaredSound, war, null); // звук оповещения
        return null; // success
    }

    public String rejectNationHelp(Player player, String townName) {
        Object town = towny.getTown(player);
        if (town == null) return color("&cВы не состоите в городе.");
        Object nation = towny.getNation(player);
        if (nation == null) return color("&cВы не состоите в нации.");
        if (!towny.isKing(player)) return color("&cТолько король нации может отклонить запрос!");

        String nationName = towny.nationName(nation);
        War war = getWarInvolving(townName);
        if (war == null || !war.isActive()) return color("&cЭта война уже окончена или не существует.");

        List<War.WarHelpRequest> delivered = war.deliveredHelpRequests.get(nationName);
        if (delivered == null || delivered.stream().noneMatch(r -> r.townName.equalsIgnoreCase(townName))) {
            return color("&cУ вас нет доставленного запроса от этого города.");
        }

        delivered.removeIf(r -> r.townName.equalsIgnoreCase(townName));
        if (delivered.isEmpty()) {
            war.deliveredHelpRequests.remove(nationName);
        }
        
        war.rejectedNations.add(nationName.toLowerCase(Locale.ROOT));
        
        // Оповестить мэра города
        Object requestingTown = towny.getTownByName(townName);
        if (requestingTown != null) {
            String mayorName = towny.townMayorName(requestingTown);
            if (mayorName != null) {
                Player mayor = Bukkit.getPlayerExact(mayorName);
                if (mayor != null) {
                    mayor.sendMessage(color("&c[!] &fНация &e" + nationName + "&f отклонила ваш запрос на помощь в войне."));
                    mayor.playSound(mayor.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                }
            }
        }
        return null; // success
    }
}
