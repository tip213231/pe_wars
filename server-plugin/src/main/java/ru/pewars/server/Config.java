package ru.pewars.server;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Типобезопасная обёртка над config.yml.
 *
 * Все значения перечитываются в load(), поэтому /pewar reload действительно
 * применяет изменения.
 *
 * Базовые значения — по п.2 ТЗ.
 */
public final class Config {
    private final JavaPlugin plugin;

    // economy (п.2 ТЗ: война 5000, рейд 1000)
    public double raidCost;
    public double warCost;
    public String currency = "$";

    // raid (п.2/п.10 ТЗ)
    public long raidCooldownHours;
    public long raidPrepSeconds;
    public long raidActiveSeconds;
    public long raidNoAttackersTimeoutSeconds;
    public int raidMinOnline;            // мин. онлайн в городе-защитнике для рейда
    public boolean raidAllowBuild;       // атакующий может строить (п.10 ТЗ)
    public double raidWinBankPercent;    // % казны защитника победителю (п.10 ТЗ)
    public Set<Material> raidBreakable = Set.of();
    // Контейнеры (сундуки/бочки/item_vault), разрешённые к разрушению в рейде И войне.
    public Set<NamespacedKey> breakableContainers = Set.of();

    // war (town-based, только мэр)
    public long warCooldownHours;
    public long warPrepSeconds;
    public long warActiveSeconds;
    public long warNoAttackersTimeoutSeconds;
    public int warMinOnline;             // мин. онлайн в городе-защитнике для войны
    public int warVictoryChunkPercent;   // процент чанков для победы (п.2/п.8 ТЗ)
    public boolean warAllowExplosions;   // разрешение TNT/взрывов на войне (п.2/п.11 ТЗ)
    public boolean warAllowBuild;        // разрешение строительства на войне (п.2/п.11 ТЗ)
    public boolean warAllowBreak;        // разрешение разрушения блоков на войне (п.2/п.11 ТЗ)
    public boolean hungerForNationless;  // эффект голода для игроков без нации в зоне войны (п.4 ТЗ)
    public Set<Material> warExtraBreakable = Set.of();
    /** Контейнеры, которые нельзя разрушать во время войны (сундуки, Create-хранилища, бочки). */
    public Set<NamespacedKey> warProtectedContainers = Set.of();
    public Set<String> blockedCommandsDuringWar = Set.of();
    public boolean blockSpawnTeleports;

    // захват чанков флагом (п.5-п.7 ТЗ)
    public long captureFlagSeconds;             // сколько флаг должен простоять для захвата (1 мин)
    public long captureNoFlagSeconds;           // сколько нужно стоять в чанке без флага для захвата (2 мин)
    public long recaptureSeconds;               // удержание для возврата чанка защитниками (п.6)
    public int centralFlagsRequired;            // флагов для центрального чанка (п.7)
    public int centralMinAttackers;             // мин. атакующих в центральном чанке (п.7)
    public int flagNoBlocksRadius;              // радиус запрета блоков вокруг флага (п.5)
    public final NamespacedKey captureFlagKey;  // ключ для кастомного предмета-флага
    public List<String> flagRecipeShape = List.of("WGW", "WIW", " B ");
    public Map<Character, Material> flagRecipeIngredients = Map.of();

    // хранилище (п.16 ТЗ): sqlite (по умолчанию) или mysql/mariadb
    public String storageType = "sqlite";
    public String mysqlHost = "localhost";
    public int mysqlPort = 3306;
    public String mysqlDatabase = "pe_wars";
    public String mysqlUser = "root";
    public String mysqlPassword = "";
    /**
     * Шифрование соединения с MySQL. Раньше useSSL=false был зашит в JDBC URL,
     * что означало передачу пароля и данных по сети в открытом виде без
     * возможности это изменить. Включайте, если БД не на localhost.
     */
    public boolean mysqlUseSsl;

    // звуки при старте/конце войны и рейда (для участников обеих сторон)
    public boolean soundsEnabled;
    public String warDeclaredSound;
    public Sound warStartSound;
    public Sound warEndSound;
    public Sound raidStartSound;
    public Sound raidEndSound;
    public float soundVolume;
    public float soundPitch;

    public Config(JavaPlugin plugin) {
        this.plugin = plugin;
        this.captureFlagKey = new NamespacedKey(plugin, "capture_flag");
        load();
    }

    /** Перечитать config.yml с диска и применить все значения. */
    public void reload() {
        plugin.reloadConfig();
        load();
    }

    private void load() {
        FileConfiguration c = plugin.getConfig();

        this.raidCost = c.getDouble("economy.raid-cost", 1000);
        this.warCost = c.getDouble("economy.war-cost", 5000);
        this.currency = c.getString("settings.currency-symbol", "$").trim();

        this.raidCooldownHours = c.getLong("raid.cooldown-hours", 24);
        this.raidPrepSeconds = c.getLong("raid.preparation-minutes", 15) * 60L;
        this.raidActiveSeconds = c.getLong("raid.active-minutes", 30) * 60L;
        this.raidNoAttackersTimeoutSeconds = c.getLong("raid.no-attackers-timeout-seconds", 60);
        this.raidMinOnline = c.getInt("raid.min-online", 4);
        this.raidAllowBuild = c.getBoolean("raid.allow-build", true);
        this.raidWinBankPercent = Math.max(0, Math.min(100, c.getDouble("raid.win-bank-percent", 50)));
        this.raidBreakable = parseMaterials(c.getStringList("raid.breakable-blocks"));

        this.breakableContainers = parseNamespacedKeys(c.getStringList("containers.breakable"));

        this.warCooldownHours = c.getLong("war.cooldown-hours", 168);
        this.warPrepSeconds = c.getLong("war.preparation-minutes", 60) * 60L;
        this.warActiveSeconds = c.getLong("war.active-minutes", 120) * 60L;
        this.warNoAttackersTimeoutSeconds = c.getLong("war.no-attackers-timeout-minutes", 15) * 60L;
        this.warMinOnline = c.getInt("war.min-online", 3);
        this.warVictoryChunkPercent = Math.max(1, Math.min(100, c.getInt("war.victory-chunk-percent", 75)));
        this.warAllowExplosions = c.getBoolean("war.allow-explosions", true);
        this.warAllowBuild = c.getBoolean("war.allow-build", true);
        this.warAllowBreak = c.getBoolean("war.allow-break", true);
        this.hungerForNationless = c.getBoolean("war.hunger-for-nationless", true);
        this.warExtraBreakable = parseMaterials(c.getStringList("war.extra-breakable-blocks"));
        this.warProtectedContainers = parseNamespacedKeys(c.getStringList("war.protected-containers"));

        Set<String> blocked = new HashSet<>();
        for (String raw : c.getStringList("war.blocked-commands-during-war")) {
            blocked.add(normalizeCommand(raw));
        }
        this.blockedCommandsDuringWar = blocked;
        this.blockSpawnTeleports = c.getBoolean("war.block-spawn-teleports", true);

        this.captureFlagSeconds = c.getLong("capture.flag-seconds", 60);
        this.captureNoFlagSeconds = c.getLong("capture.no-flag-seconds", 120);
        this.recaptureSeconds = c.getLong("capture.recapture-seconds", 120);
        this.centralFlagsRequired = Math.max(1, c.getInt("capture.central-flags-required", 2));
        this.centralMinAttackers = Math.max(1, c.getInt("capture.central-min-attackers", 5));
        this.flagNoBlocksRadius = Math.max(1, c.getInt("capture.no-blocks-radius", 3));
        loadFlagRecipe(c);

        this.storageType = c.getString("storage.type", "sqlite").trim().toLowerCase(Locale.ROOT);
        this.mysqlHost = c.getString("storage.mysql.host", "localhost");
        this.mysqlPort = c.getInt("storage.mysql.port", 3306);
        this.mysqlDatabase = c.getString("storage.mysql.database", "pe_wars");
        this.mysqlUser = c.getString("storage.mysql.user", "root");
        this.mysqlPassword = c.getString("storage.mysql.password", "");
        this.mysqlUseSsl = c.getBoolean("storage.mysql.use-ssl", false);

        this.soundsEnabled = c.getBoolean("sounds.enabled", true);
        this.warDeclaredSound = c.getString("sounds.war-declared", "pe_wars:war_declared");
        this.warStartSound = parseSound(c.getString("sounds.war-start"), Sound.ENTITY_ENDER_DRAGON_GROWL);
        this.warEndSound = parseSound(c.getString("sounds.war-end"), Sound.UI_TOAST_CHALLENGE_COMPLETE);
        this.raidStartSound = parseSound(c.getString("sounds.raid-start"), Sound.ENTITY_WITHER_SPAWN);
        this.raidEndSound = parseSound(c.getString("sounds.raid-end"), Sound.UI_TOAST_CHALLENGE_COMPLETE);
        this.soundVolume = (float) c.getDouble("sounds.volume", 0.6);
        this.soundPitch = (float) c.getDouble("sounds.pitch", 1.0);
    }

    /** Рецепт крафта флага захвата из конфига (п.2 ТЗ: рецепты настраиваются). */
    private void loadFlagRecipe(FileConfiguration c) {
        List<String> shape = c.getStringList("capture.recipe.shape");
        if (shape.isEmpty() || shape.size() > 3) {
            shape = List.of("WGW", "WIW", " B ");
        }
        this.flagRecipeShape = shape;

        Map<Character, Material> ingredients = new LinkedHashMap<>();
        ConfigurationSection section = c.getConfigurationSection("capture.recipe.ingredients");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (key == null || key.isEmpty()) continue;
                String materialName = section.getString(key, "");
                Material material = Material.matchMaterial(materialName.trim().toUpperCase(Locale.ROOT));
                if (material != null) {
                    ingredients.put(key.charAt(0), material);
                } else if (c.getBoolean("settings.debug", false)) {
                    plugin.getLogger().warning("Неизвестный материал ингредиента рецепта: " + materialName);
                }
            }
        }
        if (ingredients.isEmpty()) {
            ingredients.put('W', Material.RED_WOOL);
            ingredients.put('G', Material.GOLD_INGOT);
            ingredients.put('I', Material.IRON_INGOT);
            ingredients.put('B', Material.RED_BANNER);
        }
        this.flagRecipeIngredients = ingredients;
    }

    public String chat(String path) {
        // БЕЗ явного default: тогда Bukkit подставит значение из config.yml,
        // встроенного в jar, если ключа нет в config.yml на диске.
        // Это чинит «пустые сообщения» после обновления плагина со старым конфигом.
        String text = plugin.getConfig().getString("chat." + path);
        if (text == null || text.isBlank()) return "";
        String prefix = plugin.getConfig().getString("chat.prefix");
        return (prefix == null ? "" : prefix) + text;
    }

    public String chat(String path, String... pairs) {
        String text = chat(path);
        if (text == null || text.isBlank()) return "";
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return text;
    }

    public String title(String path) {
        // Без явного default — фолбэк на встроенный в jar config.yml (см. chat()).
        String text = plugin.getConfig().getString("titles." + path);
        return text == null ? "" : text;
    }

    public String title(String path, String... pairs) {
        String text = title(path);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return text;
    }

    private Set<Material> parseMaterials(List<String> raw) {
        Set<Material> result = new HashSet<>();
        for (String name : raw) {
            Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
            if (material != null) {
                result.add(material);
            } else if (plugin.getConfig().getBoolean("settings.debug", false)) {
                plugin.getLogger().warning("Неизвестный материал в config.yml: " + name);
            }
        }
        return result;
    }

    /** Парсер namespaced-id из конфига. Формат: "namespace:path" (например, "create:item_vault"). */
    private Set<NamespacedKey> parseNamespacedKeys(List<String> raw) {
        Set<NamespacedKey> result = new HashSet<>();
        for (String entry : raw) {
            if (entry == null) continue;
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            String namespace;
            String path;
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                namespace = "minecraft";
                path = trimmed;
            } else {
                namespace = trimmed.substring(0, colon);
                path = trimmed.substring(colon + 1);
            }
            try {
                result.add(new NamespacedKey(namespace.toLowerCase(Locale.ROOT),
                        path.toLowerCase(Locale.ROOT)));
            } catch (Exception ex) {
                if (plugin.getConfig().getBoolean("settings.debug", false)) {
                    plugin.getLogger().warning("Невалидный namespaced key в config.yml: " + entry);
                }
            }
        }
        return result;
    }

    public static String normalizeCommand(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("/")) s = s.substring(1).trim();
        // Убираем namespace у первого слова ("towny:t deposit" -> "t deposit").
        int colon = s.indexOf(':');
        if (colon > 0) {
            int space = s.indexOf(' ');
            if (space < 0 || colon < space) {
                s = s.substring(colon + 1).trim();
            }
        }
        return s.replaceAll("\\s+", " ");
    }

    /** Резолв имени звука из конфига в Sound (с фоллбеком и предупреждением при ошибке). */
    private Sound parseSound(String name, Sound fallback) {
        if (name == null || name.isBlank()) return fallback;
        try {
            return Sound.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            if (plugin.getConfig().getBoolean("settings.debug", false)) {
                plugin.getLogger().warning("Неизвестный Sound в config.yml: " + name + " (используется " + fallback + ")");
            }
            return fallback;
        }
    }
}
