package ru.pewars.server.towny;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Единственное место, где плагин обращается к Towny и Vault.
 * Все вызовы Towny идут через рефлексию (compileOnly-зависимости на Towny нет).
 *
 * ОПТИМИЗАЦИЯ: вся рефлексия кэшируется (класс TownyAPI, его инстанс
 * и резолв методов, включая отрицательные результаты). Раньше Class.forName
 * + getMethod выполнялись заново на каждый вызов — по нескольку раз на
 * каждого игрока каждую секунду тикера.
 *
 * Все типы Towny (Town, Nation, Resident, TownBlock, WorldCoord) держим как Object,
 * чтобы не тащить зависимость. Удобные методы возвращают строки/примитивы.
 */
public final class TownyBridge {
    private final JavaPlugin plugin;
    private final boolean debug;
    private Economy economy;

    // Кэш рефлексии (все обращения — с главного потока сервера, HashMap достаточно).
    private Class<?> townyApiClass;
    private boolean townyApiClassResolved;
    private Object townyApiInstance;
    private final Map<String, Optional<Method>> methodCache = new HashMap<>();

    public TownyBridge(JavaPlugin plugin) {
        this.plugin = plugin;
        this.debug = plugin.getConfig().getBoolean("settings.debug", false);
        setupEconomy();
    }

    // ===================== Vault economy =====================

    private void setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("Vault не найден. Экономика pe_wars будет недоступна.");
            return;
        }
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider != null) {
            economy = provider.getProvider();
        }
    }

    public boolean economyReady() {
        return economy != null;
    }

    public Economy economy() {
        return economy;
    }

    // ===================== Towny API entrypoint =====================

    private Object townyApi() {
        if (townyApiInstance != null) return townyApiInstance;
        if (!townyApiClassResolved) {
            townyApiClassResolved = true;
            try {
                townyApiClass = Class.forName("com.palmergames.bukkit.towny.TownyAPI");
            } catch (ClassNotFoundException e) {
                if (debug) plugin.getLogger().warning("TownyAPI недоступен: " + e.getMessage());
            }
        }
        if (townyApiClass == null) return null;
        try {
            townyApiInstance = townyApiClass.getMethod("getInstance").invoke(null);
        } catch (Exception e) {
            if (debug) plugin.getLogger().warning("TownyAPI недоступен: " + e.getMessage());
        }
        return townyApiInstance;
    }

    public boolean townyReady() {
        return townyApi() != null;
    }

    // ===================== Resident / Town / Nation lookups =====================

    /** Resident по игроку. */
    public Object getResident(Player player) {
        Object api = townyApi();
        if (api == null) return null;
        Object resident = invokeTyped(api, "getResident", Player.class, player);
        if (resident != null) return resident;
        resident = invokeTyped(api, "getResident", UUID.class, player.getUniqueId());
        if (resident != null) return resident;
        return invokeTyped(api, "getResident", String.class, player.getName());
    }

    public Object getResidentByName(String name) {
        Object api = townyApi();
        if (api == null) return null;
        return invokeTyped(api, "getResident", String.class, name);
    }

    /** Town по имени. */
    public Object getTownByName(String name) {
        Object api = townyApi();
        if (api == null) return null;
        return invokeTyped(api, "getTown", String.class, name);
    }

    public Object getNationByName(String name) {
        Object api = townyApi();
        if (api == null) return null;
        return invokeTyped(api, "getNation", String.class, name);
    }

    /** Town игрока (или null). */
    public Object getTown(Player player) {
        Object resident = getResident(player);
        if (resident == null) return null;
        return firstNonNull(invokeAny(resident, "getTownOrNull"), invokeAny(resident, "getTown"));
    }

    /** Nation игрока (или null). */
    public Object getNation(Player player) {
        Object town = getTown(player);
        if (town == null) return null;
        return firstNonNull(invokeAny(town, "getNationOrNull"), invokeAny(town, "getNation"));
    }

    /** Nation города (или null). */
    public Object getNation(Object town) {
        if (town == null) return null;
        return firstNonNull(invokeAny(town, "getNationOrNull"), invokeAny(town, "getNation"));
    }

    public String townName(Object town) {
        return stringValue(invokeAny(town, "getName"));
    }

    public String nationName(Object nation) {
        return stringValue(invokeAny(nation, "getName"));
    }

    public boolean isMayor(Player player) {
        Object town = getTown(player);
        if (town == null) return false;
        String mayorName = townMayorName(town);
        return mayorName != null && mayorName.equalsIgnoreCase(player.getName());
    }
    
    public String townMayorName(Object town) {
        if (town == null) return null;
        try {
            Object mayor = firstNonNull(invokeAny(town, "getMayor"), invokeAny(town, "getMayorResident"));
            if (mayor != null) {
                return stringValue(firstNonNull(invokeAny(mayor, "getName"), invokeAny(mayor, "getUsername")));
            }
        } catch (Exception e) {}
        return null;
    }

    public boolean isKing(Player player) {
        Object nation = getNation(player);
        if (nation == null) return false;
        String kingName = nationKingName(nation);
        return kingName != null && kingName.equalsIgnoreCase(player.getName());
    }

    public String nationKingName(Object nation) {
        if (nation == null) return null;
        Object king = firstNonNull(invokeAny(nation, "getKing"), invokeAny(nation, "getCapital"));
        if (king == null) return null;
        return stringValue(firstNonNull(invokeAny(king, "getName"), invokeAny(king, "getUsername")));
    }

    // ===================== Towny lists =====================

    public List<Object> getAllTowns() {
        Object api = townyApi();
        Object result = invokeAny(api, "getTowns");
        return toObjectList(result);
    }

    public List<Object> getAllNations() {
        Object api = townyApi();
        Object result = invokeAny(api, "getNations");
        return toObjectList(result);
    }

    public List<String> getAllTownNames() {
        List<String> names = new ArrayList<>();
        for (Object town : getAllTowns()) {
            String name = townName(town);
            if (!name.isBlank()) names.add(name);
        }
        return names;
    }

    public List<String> getAllNationNames() {
        List<String> names = new ArrayList<>();
        for (Object nation : getAllNations()) {
            String name = nationName(nation);
            if (!name.isBlank()) names.add(name);
        }
        return names;
    }

    /** Имена городов, у которых есть хотя бы один игрок онлайн. */
    public List<String> getOnlineTownNames() {
        // ОПТИМИЗАЦИЯ: LinkedHashSet вместо O(n^2) List.contains().
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            Object town = getTown(online);
            if (town != null) {
                String name = townName(town);
                if (!name.isBlank()) names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    /** Подсчёт онлайна для каждого активного города (O(N_players)). */
    public Map<String, Integer> getOnlineTownCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            Object town = getTown(online);
            if (town != null) {
                String name = townName(town);
                if (!name.isBlank()) {
                    counts.put(name, counts.getOrDefault(name, 0) + 1);
                }
            }
        }
        return counts;
    }

    private List<Object> toObjectList(Object result) {
        List<Object> list = new ArrayList<>();
        if (result instanceof Iterable<?> iterable) {
            for (Object o : iterable) list.add(o);
        }
        return list;
    }

    /** Резиденты города (имена). */
    public List<String> townResidentNames(Object town) {
        List<String> names = new ArrayList<>();
        Object residentsObject = firstNonNull(invokeAny(town, "getResidents"), invokeAny(town, "getResidentsMap"));
        if (residentsObject instanceof Iterable<?> iterable) {
            for (Object resident : iterable) {
                String name = stringValue(firstNonNull(invokeAny(resident, "getName"), invokeAny(resident, "getUsername")));
                if (!name.isBlank()) names.add(name);
            }
        } else if (residentsObject instanceof java.util.Map<?, ?> map) {
            for (Object resident : map.values()) {
                String name = stringValue(firstNonNull(invokeAny(resident, "getName"), invokeAny(resident, "getUsername")));
                if (!name.isBlank()) names.add(name);
            }
        }
        return names;
    }

    public int nationOnlineCount(Object nation) {
        int count = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            Object playerNation = getNation(online);
            if (playerNation != null && nationName(playerNation).equalsIgnoreCase(nationName(nation))) {
                count++;
            }
        }
        return count;
    }

    // ===================== TownBlock / chunks =====================

    public Object getTownBlock(Location location) {
        Object api = townyApi();
        if (api == null) return null;
        return invokeTyped(api, "getTownBlock", Location.class, location);
    }

    /** Возвращает Town, которому принадлежит локация, или null (wilderness). */
    public Object getTownAt(Location location) {
        Object townBlock = getTownBlock(location);
        if (townBlock == null) return null;
        return firstNonNull(invokeAny(townBlock, "getTownOrNull"), invokeAny(townBlock, "getTown"));
    }

    public int chunkX(Object townBlock) {
        return intValue(invokeAny(townBlock, "getX"));
    }

    public int chunkZ(Object townBlock) {
        return intValue(invokeAny(townBlock, "getZ"));
    }

    public String worldName(Object townBlock) {
        Object world = invokeAny(townBlock, "getWorld");
        Object name = invokeAny(world, "getName");
        if (name != null) return stringValue(name);
        Object coord = invokeAny(townBlock, "getWorldCoord");
        return stringValue(invokeAny(coord, "getWorldName"));
    }

    /** Главная точка города (homeblock) как TownBlock. */
    public Object getHomeblock(Object town) {
        if (town == null) return null;
        return firstNonNull(invokeAny(town, "getHomeBlock"), invokeAny(town, "getHomeblock"));
    }

    public boolean isHomeBlock(Object townBlock) {
        if (townBlock == null) return false;
        Boolean result = booleanSetting(townBlock, "isHomeBlock", "isHomeblock");
        return Boolean.TRUE.equals(result);
    }

    /** Координаты homeblock-чанка города (или null). */
    public ChunkCoord homeblockCoord(Object town) {
        Object homeblock = getHomeblock(town);
        if (homeblock == null) return null;
        return new ChunkCoord(worldName(homeblock), chunkX(homeblock), chunkZ(homeblock));
    }

    public ChunkCoord coordOf(Object townBlock) {
        if (townBlock == null) return null;
        return new ChunkCoord(worldName(townBlock), chunkX(townBlock), chunkZ(townBlock));
    }

    /**
     * Карта TownBlock-ов города по координатам чанка.
     * ОПТИМИЗАЦИЯ: даёт O(1)-доступ при передаче захваченных чанков после войны
     * (раньше был перебор всех блоков города на КАЖДЫЙ захваченный чанк — O(n*m)).
     */
    public Map<ChunkCoord, Object> townBlocksByCoord(Object town) {
        Map<ChunkCoord, Object> result = new HashMap<>();
        Object blocks = firstNonNull(invokeAny(town, "getTownBlocks"), invokeAny(town, "getTownBlockMap"));
        if (blocks instanceof Iterable<?> iterable) {
            for (Object tb : iterable) {
                ChunkCoord c = coordOf(tb);
                if (c != null) result.put(c, tb);
            }
        } else if (blocks instanceof java.util.Map<?, ?> map) {
            for (Object tb : map.values()) {
                ChunkCoord c = coordOf(tb);
                if (c != null) result.put(c, tb);
            }
        }
        return result;
    }

    /** Все TownBlock-и города (для подсчёта общего числа чанков). */
    public List<ChunkCoord> townChunkCoords(Object town) {
        return new ArrayList<>(townBlocksByCoord(town).keySet());
    }

    /** Проверяет, находится ли локация в радиусе 2 чанков от любого клейма города. */
    public boolean isWithinTwoChunks(Location loc, Object town) {
        if (loc == null || town == null) return false;
        return isWithinTwoChunks(loc, townChunkCoords(town));
    }

    /**
     * То же, но по заранее собранному списку чанков.
     * ОПТИМИЗАЦИЯ: тикеры собирают чанки города один раз за тик,
     * а не перестраивают карту блоков через рефлексию на каждого игрока.
     */
    public boolean isWithinTwoChunks(Location loc, List<ChunkCoord> coords) {
        if (loc == null || coords == null || coords.isEmpty()) return false;
        String world = loc.getWorld().getName();
        int px = loc.getBlockX() >> 4;
        int pz = loc.getBlockZ() >> 4;

        for (ChunkCoord cc : coords) {
            if (!cc.world.equalsIgnoreCase(world)) continue;
            if (Math.abs(cc.x - px) <= 2 && Math.abs(cc.z - pz) <= 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * Является ли данный TownBlock аванпостом (TownBlockType.OUTPOST)?
     * Проверяем через getType().name() == "OUTPOST", чтобы не тащить зависимость на класс Towny.
     */
    public boolean isOutpostBlock(Object townBlock) {
        if (townBlock == null) return false;
        Object type = invokeAny(townBlock, "getType");
        if (type != null) {
            String typeName = stringValue(invokeAny(type, "name"));
            if ("OUTPOST".equalsIgnoreCase(typeName)) return true;
        }
        Boolean isOutpost = booleanSetting(townBlock, "isOutpost", "getIsOutpost", "isOutPost");
        return Boolean.TRUE.equals(isOutpost);
    }

    /**
     * Возвращает координаты чанков-аванпостов города.
     * Аванпост в Towny — это TownBlock с типом OUTPOST.
     */
    public List<ChunkCoord> getOutpostChunkCoords(Object town) {
        List<ChunkCoord> result = new ArrayList<>();
        if (town == null) return result;
        for (Map.Entry<ChunkCoord, Object> entry : townBlocksByCoord(town).entrySet()) {
            if (isOutpostBlock(entry.getValue())) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Проверяет, что локация loc:
     *  1) находится в чанке-аванпосте города attackerTown;
     *  2) этот аванпост расположен в радиусе 5 чанков от любого клейма defenderTown.
     *
     * Используется для права защитников контратаковать в аванпостах атакующего.
     */
    public boolean isAttackerOutpostNearDefender(Location loc, Object attackerTown, Object defenderTown) {
        if (loc == null || attackerTown == null || defenderTown == null) return false;
        String world = loc.getWorld().getName();
        int px = loc.getBlockX() >> 4;
        int pz = loc.getBlockZ() >> 4;

        // Шаг 1: Проверяем, что локация вообще находится в аванпосте атакующего.
        boolean inOutpost = false;
        for (ChunkCoord oc : getOutpostChunkCoords(attackerTown)) {
            if (oc.world.equalsIgnoreCase(world) && oc.x == px && oc.z == pz) {
                inOutpost = true;
                break;
            }
        }
        if (!inOutpost) return false;

        // Шаг 2: Аванпост находится в радиусе 5 чанков от любого клейма защитника.
        for (ChunkCoord dc : townChunkCoords(defenderTown)) {
            if (!dc.world.equalsIgnoreCase(world)) continue;
            if (Math.abs(dc.x - px) <= 5 && Math.abs(dc.z - pz) <= 5) {
                return true;
            }
        }
        return false;
    }

    // ===================== Balances & withdrawals =====================

    public double townBalance(Object town) {
        return accountBalance(town);
    }

    public double nationBalance(Object nation) {
        return accountBalance(nation);
    }

    private double accountBalance(Object townOrNation) {
        Object account = firstNonNull(invokeAny(townOrNation, "getAccount"), invokeAny(townOrNation, "getBankAccount"));
        return doubleValue(firstNonNull(invokeAny(account, "getHoldingBalance"), invokeAny(account, "getBalance")));
    }

    /**
     * Списать сумму с банка города/нации.
     * ФИКС: результат withdraw теперь проверяется — раньше списание «удавалось»
     * даже при нехватке средств, и война/рейд объявлялись бесплатно.
     */
    public boolean withdrawTown(Object town, double amount) {
        return withdrawAccount(town, amount);
    }

    public boolean withdrawNation(Object nation, double amount) {
        return withdrawAccount(nation, amount);
    }

    private boolean withdrawAccount(Object townOrNation, double amount) {
        if (economy == null) return false;
        try {
            Object account = firstNonNull(invokeAny(townOrNation, "getAccount"), invokeAny(townOrNation, "getBankAccount"));
            if (account == null) return false;
            // Сначала пробуем withdraw(double, String) — сигнатура Towny EconomyAccount.
            Method m = findMethod(account.getClass(), "withdraw", double.class, String.class);
            if (m != null) {
                Object result = m.invoke(account, amount, "pe_wars");
                if (result instanceof Boolean success) return success;
                return true;
            }
            // Fallback: через Vault bank API.
            return withdrawViaVaultOwner(account, amount);
        } catch (Exception e) {
            if (debug) plugin.getLogger().warning("withdrawAccount error: " + e.getMessage());
            return withdrawViaVaultOwner(townOrNation, amount);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean withdrawViaVaultOwner(Object accountOrTown, double amount) {
        if (economy == null) return false;
        try {
            String name = stringValue(firstNonNull(
                    invokeAny(accountOrTown, "getName"),
                    invokeAny(accountOrTown, "getBankAccountName")));
            if (name == null || name.isBlank()) {
                name = stringValue(invokeAny(accountOrTown, "getName"));
            }
            if (economy.hasBankSupport()) {
                try {
                    // ФИКС: результат транзакции раньше игнорировался.
                    if (economy.bankWithdraw(name, amount).transactionSuccess()) {
                        return true;
                    }
                } catch (Exception ignored) {
                    // fall through to player
                }
            }
            OfflinePlayer owner = findOwnerOfflinePlayer(accountOrTown);
            if (owner != null && economy.has(owner, amount)) {
                return economy.withdrawPlayer(owner, amount).transactionSuccess();
            }
            return false;
        } catch (Exception e) {
            if (debug) plugin.getLogger().warning("withdrawViaVaultOwner error: " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer findOwnerOfflinePlayer(Object townOrNation) {
        try {
            Object mayor = firstNonNull(invokeAny(townOrNation, "getMayor"), invokeAny(townOrNation, "getKing"));
            if (mayor != null) {
                String name = stringValue(firstNonNull(invokeAny(mayor, "getName"), invokeAny(mayor, "getUsername")));
                if (!name.isBlank()) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(name);
                    if (op != null) return op;
                }
                Object uuid = firstNonNull(invokeAny(mayor, "getUUID"), invokeAny(mayor, "getUuid"));
                if (uuid instanceof UUID u) {
                    return Bukkit.getOfflinePlayer(u);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Вернуть сумму в банк города (используется при отмене войны/рейда в фазе подготовки).
     */
    public boolean depositTown(Object town, double amount) {
        return depositAccount(town, amount);
    }

    private boolean depositAccount(Object townOrNation, double amount) {
        if (economy == null) return false;
        try {
            Object account = firstNonNull(invokeAny(townOrNation, "getAccount"), invokeAny(townOrNation, "getBankAccount"));
            if (account == null) return false;
            // Сначала пробуем deposit(double, String) — сигнатура Towny EconomyAccount.
            Method m = findMethod(account.getClass(), "deposit", double.class, String.class);
            if (m != null) {
                Object result = m.invoke(account, amount, "pe_wars refund");
                if (result instanceof Boolean success) return success;
                return true;
            }
            return depositViaVaultOwner(account, amount);
        } catch (Exception e) {
            if (debug) plugin.getLogger().warning("depositAccount error: " + e.getMessage());
            return depositViaVaultOwner(townOrNation, amount);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean depositViaVaultOwner(Object accountOrTown, double amount) {
        if (economy == null) return false;
        try {
            String name = stringValue(firstNonNull(
                    invokeAny(accountOrTown, "getName"),
                    invokeAny(accountOrTown, "getBankAccountName")));
            if (economy.hasBankSupport()) {
                try {
                    if (economy.bankDeposit(name, amount).transactionSuccess()) {
                        return true;
                    }
                } catch (Exception ignored) {
                    // fall through to player
                }
            }
            OfflinePlayer owner = findOwnerOfflinePlayer(accountOrTown);
            if (owner != null) {
                return economy.depositPlayer(owner, amount).transactionSuccess();
            }
            return false;
        } catch (Exception e) {
            if (debug) plugin.getLogger().warning("depositViaVaultOwner error: " + e.getMessage());
            return false;
        }
    }

    // ===================== Real chunk transfer (war win) =====================

    /**
     * Реальный переход чанка к атакующей нации. По возможности через Towny API
     * (townBlock.setTown(newTown)), иначе через команду консоли.
     */
    public boolean transferChunk(Object townBlock, Object newOwnerTown) {
        if (townBlock == null || newOwnerTown == null) return false;
        try {
            Method setTown = findMethod(townBlock.getClass(), "setTown", classForName("com.palmergames.bukkit.towny.object.Town"));
            if (setTown != null) {
                setTown.invoke(townBlock, newOwnerTown);
                saveTownBlock(townBlock);
                saveTown(newOwnerTown);
                return true;
            }
        } catch (Exception e) {
            if (debug) plugin.getLogger().warning("transferChunk setTown error: " + e.getMessage());
        }
        return false;
    }

    /** Название города, в котором состоит резидент (по нику), или null. */
    public String residentTownName(String playerName) {
        try {
            Object resident = getResidentByName(playerName);
            if (resident == null) return null;
            Method getTown = findMethod(resident.getClass(), "getTownOrNull");
            Object town = getTown == null ? null : getTown.invoke(resident);
            return town == null ? null : townName(town);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Назначить мэром города резидента по нику (исход войны «смена мэра»).
     * Сначала через API (перевод резидента в город + setMayor),
     * при неудаче — резервная консольная команда townyadmin.
     */
    public boolean setMayor(Object town, String residentName, String townNameForCommand) {
        try {
            Object resident = getResidentByName(residentName);
            Class<?> residentClass = classForName("com.palmergames.bukkit.towny.object.Resident");
            if (town != null && resident != null && residentClass != null) {
                Method getTownOrNull = findMethod(resident.getClass(), "getTownOrNull");
                Object current = getTownOrNull == null ? null : getTownOrNull.invoke(resident);
                boolean inTown = current != null && townName(current) != null
                        && townName(current).equalsIgnoreCase(townName(town));
                if (!inTown) {
                    // Переводим игрока в завоёванный город (мэр должен быть его резидентом).
                    if (current != null) {
                        Method removeTown = findMethod(resident.getClass(), "removeTown");
                        if (removeTown != null) removeTown.invoke(resident);
                    }
                    Method addResident = findMethod(town.getClass(), "addResident", residentClass);
                    if (addResident != null) addResident.invoke(town, resident);
                }
                Method setMayor = findMethod(town.getClass(), "setMayor", residentClass);
                if (setMayor != null) {
                    setMayor.invoke(town, resident);
                    saveTown(town);
                    return true;
                }
            }
        } catch (Exception e) {
            if (debug) plugin.getLogger().warning("setMayor error: " + e.getMessage());
        }
        // Резерв: команда TownyAdmin из консоли (сама обрабатывает перевод резидента).
        try {
            return org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(),
                    "townyadmin town " + townNameForCommand + " mayor " + residentName);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Превратить чанк в аванпост с названием и точкой спавна
     * (исход войны «захватить город»: аванпост на месте центра города).
     */
    public boolean makeOutpost(Object townBlock, String plotName, Object town, Location spawn) {
        boolean ok = false;
        try {
            if (townBlock != null) {
                Method setOutpost = findMethod(townBlock.getClass(), "setOutpost", boolean.class);
                if (setOutpost != null) {
                    setOutpost.invoke(townBlock, true);
                    ok = true;
                }
                if (plotName != null && !plotName.isBlank()) {
                    Method setName = findMethod(townBlock.getClass(), "setName", String.class);
                    if (setName != null) setName.invoke(townBlock, plotName);
                }
                saveTownBlock(townBlock);
            }
            if (town != null && spawn != null) {
                Method addSpawn = findMethod(town.getClass(), "addOutpostSpawn", Location.class);
                if (addSpawn != null) {
                    addSpawn.invoke(town, spawn);
                    ok = true;
                }
                saveTown(town);
            }
        } catch (Exception e) {
            if (debug) plugin.getLogger().warning("makeOutpost error: " + e.getMessage());
        }
        return ok;
    }

    /** Fallback через TownyUniverse.removeTownBlock (чанк становится wilderness). */
    public boolean unclaimChunk(Object townBlock) {
        try {
            Class<?> universeClass = Class.forName("com.palmergames.bukkit.towny.TownyUniverse");
            Object universe = universeClass.getMethod("getInstance").invoke(null);
            Method m = findMethod(universe.getClass(), "removeTownBlock", townBlock.getClass());
            if (m == null) {
                Class<?> tbClass = Class.forName("com.palmergames.bukkit.towny.object.TownBlock");
                m = findMethod(universe.getClass(), "removeTownBlock", tbClass);
            }
            if (m != null) {
                m.invoke(universe, townBlock);
                return true;
            }
        } catch (Exception e) {
            if (debug) plugin.getLogger().warning("unclaimChunk error: " + e.getMessage());
        }
        return false;
    }

    private void saveTownBlock(Object townBlock) {
        try {
            Class<?> universeClass = Class.forName("com.palmergames.bukkit.towny.TownyUniverse");
            Object universe = universeClass.getMethod("getInstance").invoke(null);
            Object ds = invokeAny(universe, "getDataSource");
            if (ds != null) {
                Method m = findMethod(ds.getClass(), "saveTownBlock", townBlock.getClass());
                if (m == null) {
                    Class<?> tbClass = Class.forName("com.palmergames.bukkit.towny.object.TownBlock");
                    m = findMethod(ds.getClass(), "saveTownBlock", tbClass);
                }
                if (m != null) m.invoke(ds, townBlock);
            }
        } catch (Exception ignored) {
        }
    }

    private void saveTown(Object town) {
        try {
            Class<?> universeClass = Class.forName("com.palmergames.bukkit.towny.TownyUniverse");
            Object universe = universeClass.getMethod("getInstance").invoke(null);
            Object ds = invokeAny(universe, "getDataSource");
            if (ds != null) {
                Method m = findMethod(ds.getClass(), "saveTown", town.getClass());
                if (m == null) {
                    Class<?> townClass = Class.forName("com.palmergames.bukkit.towny.object.Town");
                    m = findMethod(ds.getClass(), "saveTown", townClass);
                }
                if (m != null) m.invoke(ds, town);
            }
        } catch (Exception ignored) {
        }
    }

    // ===================== War: PvP / Explosion flags =====================

    /**
     * Принудительно включает или выключает PvP в городе через Towny API (рефлексия).
     * Используется при старте/конце войны.
     * Towny хранит флаг через TownBlockSettings / Town.setFlag или setPermission.
     */
    public boolean setTownPvp(Object town, boolean enabled) {
        if (town == null) return false;
        try {
            // Towny >= 0.98: Town.setFlag(TownFlag.PVP, boolean)
            Class<?> flagEnumClass = classForName("com.palmergames.bukkit.towny.object.TownFlag");
            if (flagEnumClass != null) {
                Object pvpFlag = null;
                for (Object o : flagEnumClass.getEnumConstants()) {
                    if ("PVP".equalsIgnoreCase(o.toString())) { pvpFlag = o; break; }
                }
                if (pvpFlag != null) {
                    Method setFlag = findMethod(town.getClass(), "setFlag", flagEnumClass, boolean.class);
                    if (setFlag != null) {
                        setFlag.invoke(town, pvpFlag, enabled);
                        saveTown(town);
                        return true;
                    }
                }
            }
            // Fallback: setPermissions / setPVP(boolean)
            Method m = findMethod(town.getClass(), "setPVP", boolean.class);
            if (m == null) m = findMethod(town.getClass(), "setPvp", boolean.class);
            if (m != null) {
                m.invoke(town, enabled);
                saveTown(town);
                return true;
            }
        } catch (Exception e) {
            if (debug) plugin.getLogger().warning("setTownPvp error: " + e.getMessage());
        }
        return false;
    }

    /**
     * Принудительно включает или выключает взрывы в городе через Towny API (рефлексия).
     * Используется при старте/конце войны.
     */
    public boolean setTownExplosion(Object town, boolean enabled) {
        if (town == null) return false;
        try {
            // Towny >= 0.98: Town.setFlag(TownFlag.EXPLOSION, boolean)
            Class<?> flagEnumClass = classForName("com.palmergames.bukkit.towny.object.TownFlag");
            if (flagEnumClass != null) {
                Object expFlag = null;
                for (Object o : flagEnumClass.getEnumConstants()) {
                    if ("EXPLOSION".equalsIgnoreCase(o.toString())) { expFlag = o; break; }
                }
                if (expFlag != null) {
                    Method setFlag = findMethod(town.getClass(), "setFlag", flagEnumClass, boolean.class);
                    if (setFlag != null) {
                        setFlag.invoke(town, expFlag, enabled);
                        saveTown(town);
                        return true;
                    }
                }
            }
            // Fallback: setExplosion(boolean)
            Method m = findMethod(town.getClass(), "setExplosion", boolean.class);
            if (m == null) m = findMethod(town.getClass(), "setExplosions", boolean.class);
            if (m != null) {
                m.invoke(town, enabled);
                saveTown(town);
                return true;
            }
        } catch (Exception e) {
            if (debug) plugin.getLogger().warning("setTownExplosion error: " + e.getMessage());
        }
        return false;
    }

    /** Читает текущий PvP-флаг города (null = неизвестно). */
    public Boolean getTownPvp(Object town) {
        if (town == null) return null;
        try {
            Class<?> flagEnumClass = classForName("com.palmergames.bukkit.towny.object.TownFlag");
            if (flagEnumClass != null) {
                Object pvpFlag = null;
                for (Object o : flagEnumClass.getEnumConstants()) {
                    if ("PVP".equalsIgnoreCase(o.toString())) { pvpFlag = o; break; }
                }
                if (pvpFlag != null) {
                    Method getFlag = findMethod(town.getClass(), "getFlag", flagEnumClass);
                    if (getFlag != null) {
                        Object result = getFlag.invoke(town, pvpFlag);
                        if (result instanceof Boolean b) return b;
                    }
                }
            }
        } catch (Exception e) {
            if (debug) plugin.getLogger().warning("getTownPvp error: " + e.getMessage());
        }
        return booleanSetting(town, "isPVP", "getPVP", "isPvp", "getPvp");
    }

    /** Читает текущий флаг взрывов города (null = неизвестно). */
    public Boolean getTownExplosion(Object town) {
        if (town == null) return null;
        try {
            Class<?> flagEnumClass = classForName("com.palmergames.bukkit.towny.object.TownFlag");
            if (flagEnumClass != null) {
                Object expFlag = null;
                for (Object o : flagEnumClass.getEnumConstants()) {
                    if ("EXPLOSION".equalsIgnoreCase(o.toString())) { expFlag = o; break; }
                }
                if (expFlag != null) {
                    Method getFlag = findMethod(town.getClass(), "getFlag", flagEnumClass);
                    if (getFlag != null) {
                        Object result = getFlag.invoke(town, expFlag);
                        if (result instanceof Boolean b) return b;
                    }
                }
            }
        } catch (Exception e) {
            if (debug) plugin.getLogger().warning("getTownExplosion error: " + e.getMessage());
        }
        return booleanSetting(town, "isExplosion", "getExplosion", "isExplosions", "getExplosions");
    }

    // ===================== Outsider Permissions =====================

    public static class TownyOutsiderPerms {
        public Boolean build;
        public Boolean destroy;
        public Boolean switchPerm;
        public Boolean itemUse;
    }

    public TownyOutsiderPerms getTownOutsiderPermissions(Object town) {
        if (town == null) return null;
        try {
            Object perms = firstNonNull(invokeAny(town, "getPermissions"), invokeAny(town, "getPermissionsMap"));
            if (perms == null) return null;
            TownyOutsiderPerms state = new TownyOutsiderPerms();
            state.build = booleanSetting(perms, "outsiderBuild", "getOutsiderBuild");
            state.destroy = booleanSetting(perms, "outsiderDestroy", "getOutsiderDestroy");
            state.switchPerm = booleanSetting(perms, "outsiderSwitch", "getOutsiderSwitch");
            state.itemUse = booleanSetting(perms, "outsiderItemUse", "getOutsiderItemUse");
            return state;
        } catch (Exception e) {
            if (debug) plugin.getLogger().warning("getTownOutsiderPermissions error: " + e.getMessage());
            return null;
        }
    }

    public void setTownOutsiderPermissions(Object town, boolean allow) {
        if (town == null) return;
        try {
            Object perms = firstNonNull(invokeAny(town, "getPermissions"), invokeAny(town, "getPermissionsMap"));
            if (perms == null) return;
            setFieldOrMethod(perms, "outsiderBuild", "setOutsiderBuild", allow);
            setFieldOrMethod(perms, "outsiderDestroy", "setOutsiderDestroy", allow);
            setFieldOrMethod(perms, "outsiderSwitch", "setOutsiderSwitch", allow);
            setFieldOrMethod(perms, "outsiderItemUse", "setOutsiderItemUse", allow);
            saveTown(town);
        } catch (Exception e) {
            if (debug) plugin.getLogger().warning("setTownOutsiderPermissions error: " + e.getMessage());
        }
    }

    public void restoreTownOutsiderPermissions(Object town, TownyOutsiderPerms state) {
        if (town == null || state == null) return;
        try {
            Object perms = firstNonNull(invokeAny(town, "getPermissions"), invokeAny(town, "getPermissionsMap"));
            if (perms == null) return;
            if (state.build != null) setFieldOrMethod(perms, "outsiderBuild", "setOutsiderBuild", state.build);
            if (state.destroy != null) setFieldOrMethod(perms, "outsiderDestroy", "setOutsiderDestroy", state.destroy);
            if (state.switchPerm != null) setFieldOrMethod(perms, "outsiderSwitch", "setOutsiderSwitch", state.switchPerm);
            if (state.itemUse != null) setFieldOrMethod(perms, "outsiderItemUse", "setOutsiderItemUse", state.itemUse);
            saveTown(town);
        } catch (Exception e) {
            if (debug) plugin.getLogger().warning("restoreTownOutsiderPermissions error: " + e.getMessage());
        }
    }

    private void setFieldOrMethod(Object target, String fieldName, String methodName, boolean value) {
        if (target == null) return;
        try {
            Method m = findMethod(target.getClass(), methodName, boolean.class);
            if (m != null) {
                m.invoke(target, value);
                return;
            }
        } catch (Exception ignored) {}
        try {
            java.lang.reflect.Field f = target.getClass().getField(fieldName);
            f.setBoolean(target, value);
        } catch (Exception ignored) {}
    }

    public Object getTownBlock(ChunkCoord coord) {
        if (coord == null) return null;
        org.bukkit.World world = Bukkit.getWorld(coord.world);
        if (world == null) return null;
        Location loc = new Location(world, (coord.x << 4) + 8, 64, (coord.z << 4) + 8);
        return getTownBlock(loc);
    }

    // ===================== Reflection helpers =====================

    private Class<?> classForName(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Резолв метода с кэшированием (в т.ч. отрицательным: «метода нет» тоже
     * запоминается, чтобы не сканировать getMethods() заново на каждый вызов).
     */
    private Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        if (clazz == null) return null;
        StringBuilder keyBuilder = new StringBuilder(clazz.getName()).append('#').append(name);
        for (Class<?> param : params) {
            keyBuilder.append('#').append(param == null ? "*" : param.getName());
        }
        String key = keyBuilder.toString();
        Optional<Method> cached = methodCache.get(key);
        if (cached != null) return cached.orElse(null);
        Method resolved = resolveMethod(clazz, name, params);
        methodCache.put(key, Optional.ofNullable(resolved));
        return resolved;
    }

    private Method resolveMethod(Class<?> clazz, String name, Class<?>... params) {
        boolean hasWildcard = false;
        for (Class<?> p : params) {
            if (p == null) {
                hasWildcard = true;
                break;
            }
        }
        if (!hasWildcard) {
            try {
                return clazz.getMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
        // Поиск по имени среди всех методов с подходящим числом параметров.
        for (Method candidate : clazz.getMethods()) {
            if (!candidate.getName().equals(name)) continue;
            if (candidate.getParameterCount() != params.length) continue;
            boolean ok = true;
            for (int i = 0; i < params.length; i++) {
                if (params[i] != null && !candidate.getParameterTypes()[i].isAssignableFrom(params[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) return candidate;
        }
        return null;
    }

    private Object invokeAny(Object target, String method) {
        if (target == null) return null;
        Method m = findMethod(target.getClass(), method);
        if (m == null) return null;
        try {
            return m.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object invokeTyped(Object target, String method, Class<?> type, Object arg) {
        if (target == null) return null;
        Method m = findMethod(target.getClass(), method, type);
        if (m == null) return null;
        try {
            return m.invoke(target, arg);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Boolean booleanSetting(Object target, String... methods) {
        for (String method : methods) {
            Object value = invokeAny(target, method);
            if (value instanceof Boolean bool) return bool;
            if (value != null) {
                String text = String.valueOf(value);
                if ("true".equalsIgnoreCase(text)) return true;
                if ("false".equalsIgnoreCase(text)) return false;
            }
        }
        return null;
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private String stringValue(Object object) {
        return object == null ? "" : String.valueOf(object);
    }

    private double doubleValue(Object object) {
        if (object instanceof Number number) return number.doubleValue();
        if (object == null) return 0.0;
        try {
            return Double.parseDouble(String.valueOf(object));
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private int intValue(Object object) {
        if (object instanceof Number number) return number.intValue();
        if (object == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(object));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** Простая запись координат чанка (world + chunk x/z). */
    public static final class ChunkCoord {
        public final String world;
        public final int x;
        public final int z;

        public ChunkCoord(String world, int x, int z) {
            this.world = world == null ? "" : world;
            this.x = x;
            this.z = z;
        }

        public boolean matches(String world, int x, int z) {
            return this.x == x && this.z == z && this.world.equalsIgnoreCase(world);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChunkCoord c)) return false;
            return x == c.x && z == c.z && world.equalsIgnoreCase(c.world);
        }

        @Override
        public int hashCode() {
            return (world.toLowerCase(Locale.ROOT) + ":" + x + ":" + z).hashCode();
        }

        @Override
        public String toString() {
            return world + ":" + x + ":" + z;
        }
    }

    public List<String> getNationResidents(Object nation) {
        if (nation == null) return null;
        List<String> names = new ArrayList<>();
        Object residentsObject = firstNonNull(invokeAny(nation, "getResidents"), invokeAny(nation, "getResidentsMap"));
        if (residentsObject instanceof Iterable<?> iterable) {
            for (Object residentObj : iterable) {
                String name = stringValue(firstNonNull(invokeAny(residentObj, "getName"), invokeAny(residentObj, "getUsername")));
                if (!name.isBlank()) names.add(name);
            }
        }
        if (residentsObject instanceof java.util.Map<?, ?> map) {
            for (Object residentObj : map.values()) {
                String name = stringValue(firstNonNull(invokeAny(residentObj, "getName"), invokeAny(residentObj, "getUsername")));
                if (!name.isBlank()) names.add(name);
            }
        }
        return names;
    }
}
