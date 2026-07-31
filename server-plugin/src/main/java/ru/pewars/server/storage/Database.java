package ru.pewars.server.storage;

import org.bukkit.plugin.java.JavaPlugin;
import ru.pewars.server.Config;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Хранилище состояния (п.16 ТЗ): SQLite (по умолчанию) или MySQL/MariaDB.
 * Для MySQL используется пул HikariCP, если библиотека доступна в classpath
 * (подключается через рефлексию, чтобы не требовать жёсткой зависимости),
 * иначе — прямое подключение через DriverManager.
 *
 * Сохраняются: активные войны (с захваченными чанками), активные рейды,
 * парные кулдауны и активные флаги захвата.
 */
public final class Database {
    private final JavaPlugin plugin;
    private final Config config;

    private boolean ready = false;
    private boolean sqlite = true;
    /** Постоянное соединение для SQLite. */
    private Connection sqliteConn;
    /** HikariDataSource (через рефлексию) для MySQL, если доступен. */
    private Object hikari;
    private String mysqlUrl;

    public Database(JavaPlugin plugin, Config config) {
        this.plugin = plugin;
        this.config = config;
    }

    public boolean isReady() {
        return ready;
    }

    public void init() {
        try {
            sqlite = !"mysql".equalsIgnoreCase(config.storageType)
                    && !"mariadb".equalsIgnoreCase(config.storageType);
            if (sqlite) {
                File folder = plugin.getDataFolder();
                if (!folder.exists() && !folder.mkdirs()) {
                    plugin.getLogger().warning("Не удалось создать папку плагина для SQLite.");
                }
                try {
                    Class.forName("org.sqlite.JDBC");
                } catch (ClassNotFoundException ignored) {
                    // Драйвер обычно уже есть в Paper.
                }
                sqliteConn = DriverManager.getConnection(
                        "jdbc:sqlite:" + new File(folder, "data.db").getAbsolutePath());
            } else {
                mysqlUrl = "jdbc:mysql://" + config.mysqlHost + ":" + config.mysqlPort + "/"
                        + config.mysqlDatabase + "?useSSL=false&autoReconnect=true&characterEncoding=utf8";
                hikari = tryCreateHikari(mysqlUrl);
                if (hikari == null) {
                    // Проверочное соединение без пула.
                    try (Connection test = DriverManager.getConnection(mysqlUrl, config.mysqlUser, config.mysqlPassword)) {
                        if (!test.isValid(3)) throw new IllegalStateException("MySQL connection is not valid");
                    }
                    plugin.getLogger().info("HikariCP не найден в classpath — используется DriverManager.");
                }
            }
            createTables();
            ready = true;
            plugin.getLogger().info("Хранилище готово: " + (sqlite ? "SQLite" : "MySQL" + (hikari != null ? " (HikariCP)" : "")));
        } catch (Throwable e) {
            ready = false;
            plugin.getLogger().warning("Не удалось инициализировать хранилище (" + config.storageType
                    + "): " + e.getMessage() + ". Состояние не будет переживать рестарт.");
        }
    }

    private Object tryCreateHikari(String jdbcUrl) {
        try {
            Class<?> cfgClass = Class.forName("com.zaxxer.hikari.HikariConfig");
            Class<?> dsClass = Class.forName("com.zaxxer.hikari.HikariDataSource");
            Object cfg = cfgClass.getConstructor().newInstance();
            cfgClass.getMethod("setJdbcUrl", String.class).invoke(cfg, jdbcUrl);
            cfgClass.getMethod("setUsername", String.class).invoke(cfg, config.mysqlUser);
            cfgClass.getMethod("setPassword", String.class).invoke(cfg, config.mysqlPassword);
            cfgClass.getMethod("setMaximumPoolSize", int.class).invoke(cfg, 4);
            return dsClass.getConstructor(cfgClass).newInstance(cfg);
        } catch (Throwable e) {
            return null;
        }
    }

    private Connection connection() throws Exception {
        if (sqlite) {
            if (sqliteConn == null || sqliteConn.isClosed()) {
                sqliteConn = DriverManager.getConnection(
                        "jdbc:sqlite:" + new File(plugin.getDataFolder(), "data.db").getAbsolutePath());
            }
            return sqliteConn;
        }
        if (hikari != null) {
            return (Connection) hikari.getClass().getMethod("getConnection").invoke(hikari);
        }
        return DriverManager.getConnection(mysqlUrl, config.mysqlUser, config.mysqlPassword);
    }

    private void release(Connection conn) {
        // SQLite держим открытым, пул/DriverManager — закрываем (возврат в пул).
        if (sqlite || conn == null) return;
        try {
            conn.close();
        } catch (Exception ignored) {
        }
    }

    private void createTables() throws Exception {
        Connection conn = connection();
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS pw_wars ("
                    + "id VARCHAR(40) PRIMARY KEY,"
                    + "attacker VARCHAR(64), defender VARCHAR(64), phase VARCHAR(16),"
                    + "prep_end BIGINT, active_end BIGINT,"
                    + "central_world VARCHAR(64), central_x INT, central_z INT, central_flags INT,"
                    + "chunks TEXT,"
                    + "d_pvp INT, d_expl INT, a_pvp INT, a_expl INT)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS pw_raids ("
                    + "id VARCHAR(40) PRIMARY KEY,"
                    + "attacker VARCHAR(64), defender VARCHAR(64), phase VARCHAR(16),"
                    + "prep_end BIGINT, active_end BIGINT)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS pw_cooldowns ("
                    + "kind VARCHAR(8) NOT NULL, pair VARCHAR(140) NOT NULL, until_ms BIGINT,"
                    + "PRIMARY KEY (kind, pair))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS pw_flags ("
                    + "flag_key VARCHAR(140) PRIMARY KEY,"
                    + "war_id VARCHAR(40), world VARCHAR(64), x INT, y INT, z INT,"
                    + "remaining_ms BIGINT, central INT)");
        } finally {
            release(conn);
        }
    }

    // ===================== Save =====================

    /** Полное сохранение состояния (DELETE + INSERT в транзакции). */
    public synchronized void saveAll(List<WarRow> wars, List<RaidRow> raids,
                                     Map<String, Long> warCooldowns, Map<String, Long> raidCooldowns,
                                     List<FlagRow> flags) {
        if (!ready) return;
        Connection conn = null;
        try {
            conn = connection();
            boolean oldAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM pw_wars");
                st.executeUpdate("DELETE FROM pw_raids");
                st.executeUpdate("DELETE FROM pw_cooldowns");
                st.executeUpdate("DELETE FROM pw_flags");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO pw_wars (id, attacker, defender, phase, prep_end, active_end,"
                            + " central_world, central_x, central_z, central_flags, chunks,"
                            + " d_pvp, d_expl, a_pvp, a_expl) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (WarRow w : wars) {
                    ps.setString(1, w.id);
                    ps.setString(2, w.attacker);
                    ps.setString(3, w.defender);
                    ps.setString(4, w.phase);
                    ps.setLong(5, w.prepEnd);
                    ps.setLong(6, w.activeEnd);
                    ps.setString(7, w.centralWorld);
                    ps.setInt(8, w.centralX);
                    ps.setInt(9, w.centralZ);
                    ps.setInt(10, w.centralFlags);
                    ps.setString(11, w.chunks);
                    ps.setInt(12, w.dPvp);
                    ps.setInt(13, w.dExpl);
                    ps.setInt(14, w.aPvp);
                    ps.setInt(15, w.aExpl);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO pw_raids (id, attacker, defender, phase, prep_end, active_end) VALUES (?,?,?,?,?,?)")) {
                for (RaidRow r : raids) {
                    ps.setString(1, r.id);
                    ps.setString(2, r.attacker);
                    ps.setString(3, r.defender);
                    ps.setString(4, r.phase);
                    ps.setLong(5, r.prepEnd);
                    ps.setLong(6, r.activeEnd);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO pw_cooldowns (kind, pair, until_ms) VALUES (?,?,?)")) {
                for (Map.Entry<String, Long> e : warCooldowns.entrySet()) {
                    ps.setString(1, "war");
                    ps.setString(2, e.getKey());
                    ps.setLong(3, e.getValue());
                    ps.addBatch();
                }
                for (Map.Entry<String, Long> e : raidCooldowns.entrySet()) {
                    ps.setString(1, "raid");
                    ps.setString(2, e.getKey());
                    ps.setLong(3, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO pw_flags (flag_key, war_id, world, x, y, z, remaining_ms, central) VALUES (?,?,?,?,?,?,?,?)")) {
                for (FlagRow f : flags) {
                    ps.setString(1, f.flagKey);
                    ps.setString(2, f.warId);
                    ps.setString(3, f.world);
                    ps.setInt(4, f.x);
                    ps.setInt(5, f.y);
                    ps.setInt(6, f.z);
                    ps.setLong(7, f.remainingMs);
                    ps.setInt(8, f.central ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            conn.setAutoCommit(oldAutoCommit);
        } catch (Throwable e) {
            plugin.getLogger().warning("Ошибка сохранения состояния: " + e.getMessage());
            try {
                if (conn != null) conn.rollback();
            } catch (Exception ignored) {
            }
        } finally {
            release(conn);
        }
    }

    // ===================== Load =====================

    public synchronized Loaded loadAll() {
        Loaded loaded = new Loaded();
        if (!ready) return loaded;
        Connection conn = null;
        try {
            conn = connection();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM pw_wars")) {
                while (rs.next()) {
                    WarRow w = new WarRow();
                    w.id = rs.getString("id");
                    w.attacker = rs.getString("attacker");
                    w.defender = rs.getString("defender");
                    w.phase = rs.getString("phase");
                    w.prepEnd = rs.getLong("prep_end");
                    w.activeEnd = rs.getLong("active_end");
                    w.centralWorld = rs.getString("central_world");
                    w.centralX = rs.getInt("central_x");
                    w.centralZ = rs.getInt("central_z");
                    w.centralFlags = rs.getInt("central_flags");
                    w.chunks = rs.getString("chunks");
                    w.dPvp = rs.getInt("d_pvp");
                    w.dExpl = rs.getInt("d_expl");
                    w.aPvp = rs.getInt("a_pvp");
                    w.aExpl = rs.getInt("a_expl");
                    loaded.wars.add(w);
                }
            }
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM pw_raids")) {
                while (rs.next()) {
                    RaidRow r = new RaidRow();
                    r.id = rs.getString("id");
                    r.attacker = rs.getString("attacker");
                    r.defender = rs.getString("defender");
                    r.phase = rs.getString("phase");
                    r.prepEnd = rs.getLong("prep_end");
                    r.activeEnd = rs.getLong("active_end");
                    loaded.raids.add(r);
                }
            }
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM pw_cooldowns")) {
                while (rs.next()) {
                    String kind = rs.getString("kind");
                    String pair = rs.getString("pair");
                    long until = rs.getLong("until_ms");
                    if ("war".equalsIgnoreCase(kind)) {
                        loaded.warCooldowns.put(pair, until);
                    } else {
                        loaded.raidCooldowns.put(pair, until);
                    }
                }
            }
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM pw_flags")) {
                while (rs.next()) {
                    FlagRow f = new FlagRow();
                    f.flagKey = rs.getString("flag_key");
                    f.warId = rs.getString("war_id");
                    f.world = rs.getString("world");
                    f.x = rs.getInt("x");
                    f.y = rs.getInt("y");
                    f.z = rs.getInt("z");
                    f.remainingMs = rs.getLong("remaining_ms");
                    f.central = rs.getInt("central") == 1;
                    loaded.flags.add(f);
                }
            }
        } catch (Throwable e) {
            plugin.getLogger().warning("Ошибка загрузки состояния: " + e.getMessage());
        } finally {
            release(conn);
        }
        return loaded;
    }

    public void close() {
        try {
            if (sqliteConn != null && !sqliteConn.isClosed()) sqliteConn.close();
        } catch (Exception ignored) {
        }
        if (hikari != null) {
            try {
                hikari.getClass().getMethod("close").invoke(hikari);
            } catch (Exception ignored) {
            }
        }
    }

    // ===================== Rows =====================

    public static final class Loaded {
        public final List<WarRow> wars = new ArrayList<>();
        public final List<RaidRow> raids = new ArrayList<>();
        public final Map<String, Long> warCooldowns = new HashMap<>();
        public final Map<String, Long> raidCooldowns = new HashMap<>();
        public final List<FlagRow> flags = new ArrayList<>();
    }

    public static final class WarRow {
        public String id;
        public String attacker;
        public String defender;
        public String phase;
        public long prepEnd;
        public long activeEnd;
        public String centralWorld;
        public int centralX;
        public int centralZ;
        public int centralFlags;
        /** "world;x;z;1|world;x;z;0|..." — 1 = чанк захвачен. */
        public String chunks;
        /** Исходные флаги городов: -1 = неизвестно, 0 = false, 1 = true. */
        public int dPvp = -1;
        public int dExpl = -1;
        public int aPvp = -1;
        public int aExpl = -1;
    }

    public static final class RaidRow {
        public String id;
        public String attacker;
        public String defender;
        public String phase;
        public long prepEnd;
        public long activeEnd;
    }

    public static final class FlagRow {
        public String flagKey;
        public String warId;
        public String world;
        public int x;
        public int y;
        public int z;
        public long remainingMs;
        public boolean central;
    }
}
