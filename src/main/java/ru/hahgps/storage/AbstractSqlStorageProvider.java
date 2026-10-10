package ru.hahgps.storage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import ru.hahgps.model.Category;
import ru.hahgps.model.Poi;
import ru.hahgps.model.Route;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Общая SQL-часть для SQLite и MySQL.
 *
 * Соединение одно, поэтому все методы синхронизированы — но проверка живости
 * соединения (isValid) выполняется не чаще раза в 30 секунд: дергать её перед
 * каждым запросом означает сетевой round-trip на каждый вызов. После любой
 * ошибки SQL метка сбрасывается, и следующий вызов проверяет связь заново.
 */
public abstract class AbstractSqlStorageProvider implements StorageProvider {

    private static final long VALIDATION_INTERVAL_MS = 30_000L;

    protected final boolean mysql;
    protected Connection connection;

    private long lastValidation;
    private final Set<String> warnedWorlds = new HashSet<>();

    protected AbstractSqlStorageProvider(boolean mysql) {
        this.mysql = mysql;
    }

    protected abstract void reconnect() throws SQLException;

    protected void ensureConnected() throws SQLException {
        if (connection == null || connection.isClosed()) {
            forceReconnect();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastValidation < VALIDATION_INTERVAL_MS) {
            return;
        }
        lastValidation = now;
        boolean alive;
        try {
            alive = connection.isValid(3);
        } catch (SQLException e) {
            alive = false;
        }
        if (!alive) {
            forceReconnect();
        }
    }

    /** Пометить соединение как непроверенное: после ошибки следующий вызов его переподнимет. */
    protected void markStale() {
        lastValidation = 0L;
    }

    protected void forceReconnect() throws SQLException {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            connection = null;
        }
        reconnect();
        lastValidation = System.currentTimeMillis();
    }

    protected String autoIncrement() {
        return mysql ? "AUTO_INCREMENT" : "AUTOINCREMENT";
    }

    protected String upsertRatingSql() {
        if (mysql) {
            return "INSERT INTO ratings (poi_id, player_uuid, rating) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE rating = VALUES(rating)";
        }
        return "INSERT INTO ratings (poi_id, player_uuid, rating) VALUES (?, ?, ?) " +
                "ON CONFLICT(poi_id, player_uuid) DO UPDATE SET rating = excluded.rating";
    }

    /**
     * Схема создаётся по шагам: таблицы → колонки в старых таблицах → индексы →
     * версия. База любой прошлой версии открывается без потерь и без
     * «no such column».
     */
    protected void createSchema() throws SQLException {
        boolean fresh = !tableExists("pois");
        int previousVersion = fresh ? SCHEMA_VERSION : readSchemaVersionQuietly();

        createTables();
        ensureColumns();
        createIndexes();
        writeSchemaVersion();

        if (!fresh && previousVersion < SCHEMA_VERSION) {
            log().log(Level.INFO, "HahGps: база старой версии (схема v" + previousVersion
                    + ") обновлена до v" + SCHEMA_VERSION + " — данные сохранены.");
        }
    }

    /** Текущая версия схемы: растёт при миграциях. */
    public static final int SCHEMA_VERSION = 2;

    private void createTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS categories (" +
                    "id INTEGER PRIMARY KEY " + autoIncrement() + ", " +
                    "name VARCHAR(64) UNIQUE NOT NULL, " +
                    "is_default TINYINT NOT NULL DEFAULT 0)");

            st.execute("CREATE TABLE IF NOT EXISTS pois (" +
                    "id INTEGER PRIMARY KEY " + autoIncrement() + ", " +
                    "name VARCHAR(64) UNIQUE NOT NULL, " +
                    "category_id INTEGER NOT NULL, " +
                    "world VARCHAR(64) NOT NULL, " +
                    "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, " +
                    "description TEXT, " +
                    "author VARCHAR(64) NOT NULL, " +
                    "created_at BIGINT NOT NULL)");

            // Внешний ключ нужен новым базам: без него удаление точки оставляло
            // осиротевшие оценки. Для уже созданных баз то же самое делается
            // явным DELETE внутри deletePoi.
            st.execute("CREATE TABLE IF NOT EXISTS ratings (" +
                    "poi_id INTEGER NOT NULL, " +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "rating INTEGER NOT NULL, " +
                    "PRIMARY KEY (poi_id, player_uuid), " +
                    "FOREIGN KEY (poi_id) REFERENCES pois(id) ON DELETE CASCADE)");

            st.execute("CREATE TABLE IF NOT EXISTS routes (" +
                    "id INTEGER PRIMARY KEY " + autoIncrement() + ", " +
                    "name VARCHAR(64) UNIQUE NOT NULL, " +
                    "world VARCHAR(64) NOT NULL, " +
                    "transport VARCHAR(16) NOT NULL DEFAULT 'FOOT', " +
                    "created_by VARCHAR(64), " +
                    "created_at BIGINT NOT NULL)");

            st.execute("CREATE TABLE IF NOT EXISTS route_nodes (" +
                    "id INTEGER PRIMARY KEY " + autoIncrement() + ", " +
                    "route_id INTEGER NOT NULL, " +
                    "seq INTEGER NOT NULL, " +
                    "world VARCHAR(64) NOT NULL, " +
                    "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, " +
                    "FOREIGN KEY (route_id) REFERENCES routes(id) ON DELETE CASCADE)");

            // Схема v2: рёбра, добавленные админом через /gps edge, переживают
            // пересборку графа. Концы храним координатами: id узлов графа
            // синтезируются заново при каждой загрузке.
            st.execute("CREATE TABLE IF NOT EXISTS graph_edges (" +
                    "id INTEGER PRIMARY KEY " + autoIncrement() + ", " +
                    "world VARCHAR(64) NOT NULL, " +
                    "x1 DOUBLE NOT NULL, y1 DOUBLE NOT NULL, z1 DOUBLE NOT NULL, " +
                    "x2 DOUBLE NOT NULL, y2 DOUBLE NOT NULL, z2 DOUBLE NOT NULL, " +
                    "transports VARCHAR(64) NOT NULL, " +
                    "created_at BIGINT NOT NULL)");

            st.execute("CREATE TABLE IF NOT EXISTS meta (" +
                    "key VARCHAR(32) PRIMARY KEY, " +
                    "value VARCHAR(64) NOT NULL)");
        }
    }

    /**
     * Обязательные колонки каждой таблицы. Без id (AUTOINCREMENT), уникальных
     * name и первичных ключей: их нельзя добавить через ALTER TABLE, а их
     * отсутствие = безнадёжно чужая база. Дефолты — только для NOT NULL:
     * SQLite не даёт ADD COLUMN NOT NULL без дефолта, и старые строки иначе
     * не прочитались бы.
     */
    private static final Map<String, List<ColumnAdd>> REQUIRED_COLUMNS = requiredColumns();

    private static Map<String, List<ColumnAdd>> requiredColumns() {
        Map<String, List<ColumnAdd>> spec = new LinkedHashMap<>();
        spec.put("categories", List.of(
                new ColumnAdd("is_default", "TINYINT NOT NULL DEFAULT 0")));
        spec.put("pois", List.of(
                new ColumnAdd("category_id", "INTEGER NOT NULL DEFAULT 0"),
                new ColumnAdd("world", "VARCHAR(64) NOT NULL DEFAULT 'world'"),
                new ColumnAdd("x", "DOUBLE NOT NULL DEFAULT 0"),
                new ColumnAdd("y", "DOUBLE NOT NULL DEFAULT 64"),
                new ColumnAdd("z", "DOUBLE NOT NULL DEFAULT 0"),
                new ColumnAdd("description", "TEXT"),
                new ColumnAdd("author", "VARCHAR(64) NOT NULL DEFAULT ''"),
                new ColumnAdd("created_at", "BIGINT NOT NULL DEFAULT 0")));
        spec.put("ratings", List.of(
                new ColumnAdd("poi_id", "INTEGER NOT NULL DEFAULT 0"),
                new ColumnAdd("player_uuid", "VARCHAR(36) NOT NULL DEFAULT ''"),
                new ColumnAdd("rating", "INTEGER NOT NULL DEFAULT 0")));
        spec.put("routes", List.of(
                new ColumnAdd("world", "VARCHAR(64) NOT NULL DEFAULT 'world'"),
                new ColumnAdd("transport", "VARCHAR(16) NOT NULL DEFAULT 'FOOT'"),
                new ColumnAdd("created_by", "VARCHAR(64)"),
                new ColumnAdd("created_at", "BIGINT NOT NULL DEFAULT 0")));
        spec.put("route_nodes", List.of(
                new ColumnAdd("route_id", "INTEGER NOT NULL DEFAULT 0"),
                new ColumnAdd("seq", "INTEGER NOT NULL DEFAULT 0"),
                new ColumnAdd("world", "VARCHAR(64) NOT NULL DEFAULT 'world'"),
                new ColumnAdd("x", "DOUBLE NOT NULL DEFAULT 0"),
                new ColumnAdd("y", "DOUBLE NOT NULL DEFAULT 64"),
                new ColumnAdd("z", "DOUBLE NOT NULL DEFAULT 0")));
        spec.put("graph_edges", List.of(
                new ColumnAdd("world", "VARCHAR(64) NOT NULL DEFAULT 'world'"),
                new ColumnAdd("x1", "DOUBLE NOT NULL DEFAULT 0"),
                new ColumnAdd("y1", "DOUBLE NOT NULL DEFAULT 64"),
                new ColumnAdd("z1", "DOUBLE NOT NULL DEFAULT 0"),
                new ColumnAdd("x2", "DOUBLE NOT NULL DEFAULT 0"),
                new ColumnAdd("y2", "DOUBLE NOT NULL DEFAULT 64"),
                new ColumnAdd("z2", "DOUBLE NOT NULL DEFAULT 0"),
                new ColumnAdd("transports", "VARCHAR(64) NOT NULL DEFAULT ''"),
                new ColumnAdd("created_at", "BIGINT NOT NULL DEFAULT 0")));
        spec.put("meta", List.of(
                new ColumnAdd("value", "VARCHAR(64) NOT NULL DEFAULT ''")));
        return spec;
    }

    private record ColumnAdd(String column, String definition) {}

    /** Добавляет в существующие таблицы колонки, которых в них ещё нет. */
    private void ensureColumns() throws SQLException {
        for (Map.Entry<String, List<ColumnAdd>> entry : REQUIRED_COLUMNS.entrySet()) {
            String table = entry.getKey();
            if (!tableExists(table)) {
                continue;
            }
            Set<String> existing = existingColumns(table);
            for (ColumnAdd add : entry.getValue()) {
                if (existing.contains(add.column().toLowerCase(java.util.Locale.ROOT))) {
                    continue;
                }
                String sql = "ALTER TABLE " + table + " ADD COLUMN "
                        + add.column() + " " + add.definition();
                try (Statement st = connection.createStatement()) {
                    st.execute(sql);
                }
                log().log(Level.FINE, "HahGps: миграция БД — добавлена колонка "
                        + table + "." + add.column());
            }
        }
    }

    private boolean tableExists(String table) throws SQLException {
        if (mysql) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private Set<String> existingColumns(String table) throws SQLException {
        Set<String> cols = new HashSet<>();
        if (mysql) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COLUMN_NAME FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        cols.add(rs.getString(1).toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
            return cols;
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                cols.add(rs.getString("name").toLowerCase(java.util.Locale.ROOT));
            }
        }
        return cols;
    }

    /** Версия схемы без проверки живости соединения — только для init. */
    private int readSchemaVersionQuietly() {
        try {
            if (!tableExists("meta")) {
                return 0;
            }
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT value FROM meta WHERE key = 'schema_version'")) {
                if (rs.next()) {
                    try {
                        return Integer.parseInt(rs.getString("value"));
                    } catch (NumberFormatException ignored) {
                        return 0;
                    }
                }
            }
        } catch (SQLException e) {
            return 0;
        }
        return 0;
    }

    private void writeSchemaVersion() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(upsertMetaSql());
        }
    }

    protected String upsertMetaSql() {
        if (mysql) {
            return "INSERT INTO meta (key, value) VALUES ('schema_version', '" + SCHEMA_VERSION + "') " +
                    "ON DUPLICATE KEY UPDATE value = VALUES(value)";
        }
        return "INSERT INTO meta (key, value) VALUES ('schema_version', '" + SCHEMA_VERSION + "') " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value";
    }

    /** Версия схемы в БД: будущие миграции сверяются с ней. */
    public int schemaVersion() {
        try {
            ensureConnected();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT value FROM meta WHERE key = 'schema_version'")) {
                if (rs.next()) {
                    try {
                        return Integer.parseInt(rs.getString("value"));
                    } catch (NumberFormatException ignored) {
                        return 0;
                    }
                }
            }
        } catch (SQLException e) {
            markStale();
        }
        return 0;
    }


    protected void createIndexes() {
        createIndex("idx_route_nodes_route", "route_nodes", "route_id");
        createIndex("idx_pois_category", "pois", "category_id");
        createIndex("idx_graph_edges_world", "graph_edges", "world");
    }

    private void createIndex(String name, String table, String columns) {
        String sql = (mysql
                ? "CREATE INDEX " + name + " ON " + table + " (" + columns + ")"
                : "CREATE INDEX IF NOT EXISTS " + name + " ON " + table + " (" + columns + ")");
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {

            if (mysql && e.getErrorCode() == 1061) {
                return;
            }
            if (String.valueOf(e.getMessage()).toLowerCase().contains("already exists")) {
                return;
            }

            log().log(Level.WARNING, "HahGps: не удалось создать индекс " + name + ": " + e.getMessage());
        }
    }

    @Override
    public synchronized Category createCategory(String name, boolean defaultCategory) {
        String sql = "INSERT INTO categories (name, is_default) VALUES (?, ?)";
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setInt(2, defaultCategory ? 1 : 0);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return new Category(rs.getInt(1), name, defaultCategory);
                    }
                }
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to create category: " + name, e);
        }
        return null;
    }

    @Override
    public synchronized boolean deleteCategory(int id) {
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM categories WHERE id = ?")) {
                ps.setInt(1, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to delete category: " + id, e);
        }
    }

    @Override
    public synchronized List<Category> getCategories() {
        List<Category> list = new ArrayList<>();
        try {
            ensureConnected();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, name, is_default FROM categories ORDER BY id")) {
                while (rs.next()) {
                    list.add(new Category(rs.getInt("id"), rs.getString("name"), rs.getInt("is_default") == 1));
                }
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to load categories", e);
        }
        return list;
    }

    @Override
    public synchronized Category getCategoryByName(String name) {
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, name, is_default FROM categories WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Category(rs.getInt("id"), rs.getString("name"), rs.getInt("is_default") == 1);
                    }
                }
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to find category: " + name, e);
        }
        return null;
    }

    @Override
    public synchronized Category getCategoryById(int id) {
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, name, is_default FROM categories WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Category(rs.getInt("id"), rs.getString("name"), rs.getInt("is_default") == 1);
                    }
                }
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to find category: " + id, e);
        }
        return null;
    }

    @Override
    public synchronized Poi createPoi(Poi poi) {
        String sql = "INSERT INTO pois (name, category_id, world, x, y, z, description, author, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, poi.getName());
                ps.setInt(2, poi.getCategoryId());
                ps.setString(3, poi.getWorld());
                ps.setDouble(4, poi.getX());
                ps.setDouble(5, poi.getY());
                ps.setDouble(6, poi.getZ());
                ps.setString(7, poi.getDescription());
                ps.setString(8, poi.getAuthor());
                ps.setLong(9, poi.getCreatedAt());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return new Poi(rs.getInt(1), poi.getName(), poi.getCategoryId(), poi.getWorld(),
                                poi.getX(), poi.getY(), poi.getZ(), poi.getDescription(),
                                poi.getAuthor(), poi.getCreatedAt());
                    }
                }
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to create poi: " + poi.getName(), e);
        }
        return null;
    }

    /**
     * Удаление точки вместе с её оценками и в одной транзакции: иначе в таблице
     * ratings остаются строки на несуществующий poi_id, и они молча висят вечно
     * (внешний ключ есть только у баз, созданных начиная с 1.1.0).
     */
    @Override
    public synchronized boolean deletePoi(int id) {
        try {
            ensureConnected();
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement ratings = connection.prepareStatement(
                        "DELETE FROM ratings WHERE poi_id = ?")) {
                    ratings.setInt(1, id);
                    ratings.executeUpdate();
                }
                boolean deleted;
                try (PreparedStatement ps = connection.prepareStatement("DELETE FROM pois WHERE id = ?")) {
                    ps.setInt(1, id);
                    deleted = ps.executeUpdate() > 0;
                }
                connection.commit();
                return deleted;
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to delete poi: " + id, e);
        }
    }

    @Override
    public synchronized int countPoisInCategory(int categoryId) {
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM pois WHERE category_id = ?")) {
                ps.setInt(1, categoryId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to count pois in category: " + categoryId, e);
        }
    }

    @Override
    public synchronized Poi getPoiByName(String name) {
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM pois WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return mapPoi(rs);
                    }
                }
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to find poi: " + name, e);
        }
        return null;
    }

    @Override
    public synchronized Poi getPoiById(int id) {
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM pois WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return mapPoi(rs);
                    }
                }
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to find poi: " + id, e);
        }
        return null;
    }

    @Override
    public synchronized List<Poi> getPois() {
        List<Poi> list = new ArrayList<>();
        try {
            ensureConnected();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM pois ORDER BY name")) {
                while (rs.next()) {
                    list.add(mapPoi(rs));
                }
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to load pois", e);
        }
        return list;
    }

    @Override
    public synchronized List<Poi> getPoisByCategory(int categoryId) {
        List<Poi> list = new ArrayList<>();
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM pois WHERE category_id = ? ORDER BY name")) {
                ps.setInt(1, categoryId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapPoi(rs));
                    }
                }
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to load pois by category: " + categoryId, e);
        }
        return list;
    }

    @Override
    public synchronized boolean updatePoi(Poi poi) {
        String sql = "UPDATE pois SET name = ?, category_id = ?, world = ?, x = ?, y = ?, z = ?, " +
                "description = ?, author = ? WHERE id = ?";
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, poi.getName());
                ps.setInt(2, poi.getCategoryId());
                ps.setString(3, poi.getWorld());
                ps.setDouble(4, poi.getX());
                ps.setDouble(5, poi.getY());
                ps.setDouble(6, poi.getZ());
                ps.setString(7, poi.getDescription());
                ps.setString(8, poi.getAuthor());
                ps.setInt(9, poi.getId());
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to update poi: " + poi.getName(), e);
        }
    }

    @Override
    public synchronized List<ru.hahgps.model.EdgeRecord> getEdges() {
        List<ru.hahgps.model.EdgeRecord> list = new ArrayList<>();
        try {
            ensureConnected();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT world, x1, y1, z1, x2, y2, z2, transports FROM graph_edges")) {
                while (rs.next()) {
                    list.add(new ru.hahgps.model.EdgeRecord(
                            rs.getString("world"),
                            rs.getDouble("x1"), rs.getDouble("y1"), rs.getDouble("z1"),
                            rs.getDouble("x2"), rs.getDouble("y2"), rs.getDouble("z2"),
                            ru.hahgps.model.EdgeRecord.parseTransports(rs.getString("transports"))));
                }
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to load graph edges", e);
        }
        return list;
    }

    @Override
    public synchronized void saveEdge(ru.hahgps.model.EdgeRecord edge) {
        if (edge == null || edge.transports().isEmpty()) {
            return;
        }
        ru.hahgps.model.EdgeRecord canonical = edge.canonical();
        try {
            ensureConnected();
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement del = connection.prepareStatement(
                        "DELETE FROM graph_edges WHERE world = ? AND x1 = ? AND y1 = ? AND z1 = ? " +
                                "AND x2 = ? AND y2 = ? AND z2 = ?")) {
                    del.setString(1, canonical.world());
                    del.setDouble(2, canonical.x1());
                    del.setDouble(3, canonical.y1());
                    del.setDouble(4, canonical.z1());
                    del.setDouble(5, canonical.x2());
                    del.setDouble(6, canonical.y2());
                    del.setDouble(7, canonical.z2());
                    del.executeUpdate();
                }
                try (PreparedStatement ins = connection.prepareStatement(
                        "INSERT INTO graph_edges (world, x1, y1, z1, x2, y2, z2, transports, created_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ins.setString(1, canonical.world());
                    ins.setDouble(2, canonical.x1());
                    ins.setDouble(3, canonical.y1());
                    ins.setDouble(4, canonical.z1());
                    ins.setDouble(5, canonical.x2());
                    ins.setDouble(6, canonical.y2());
                    ins.setDouble(7, canonical.z2());
                    ins.setString(8, ru.hahgps.model.EdgeRecord.serializeTransports(canonical.transports()));
                    ins.setLong(9, System.currentTimeMillis());
                    ins.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to save graph edge", e);
        }
    }

    @Override
    public synchronized void deleteEdge(ru.hahgps.model.EdgeRecord edge) {
        if (edge == null) {
            return;
        }
        ru.hahgps.model.EdgeRecord canonical = edge.canonical();
        try {
            ensureConnected();
            try (PreparedStatement del = connection.prepareStatement(
                    "DELETE FROM graph_edges WHERE world = ? AND x1 = ? AND y1 = ? AND z1 = ? " +
                            "AND x2 = ? AND y2 = ? AND z2 = ?")) {
                del.setString(1, canonical.world());
                del.setDouble(2, canonical.x1());
                del.setDouble(3, canonical.y1());
                del.setDouble(4, canonical.z1());
                del.setDouble(5, canonical.x2());
                del.setDouble(6, canonical.y2());
                del.setDouble(7, canonical.z2());
                del.executeUpdate();
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to delete graph edge", e);
        }
    }

    private Poi mapPoi(ResultSet rs) throws SQLException {
        return new Poi(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getInt("category_id"),
                rs.getString("world"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                rs.getString("description"),
                rs.getString("author"),
                rs.getLong("created_at"));
    }

    @Override
    public synchronized void setRating(int poiId, UUID playerId, int rating) {
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement(upsertRatingSql())) {
                ps.setInt(1, poiId);
                ps.setString(2, playerId.toString());
                ps.setInt(3, rating);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to set rating", e);
        }
    }

    @Override
    public synchronized Map<UUID, Integer> getRatings(int poiId) {
        Map<UUID, Integer> map = new HashMap<>();
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT player_uuid, rating FROM ratings WHERE poi_id = ?")) {
                ps.setInt(1, poiId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID player;
                        try {
                            player = UUID.fromString(rs.getString("player_uuid"));
                        } catch (IllegalArgumentException malformed) {
                            // Как в getAllRatings: одна битая строка не должна
                            // ронять весь будущий ответ.
                            continue;
                        }
                        map.put(player, rs.getInt("rating"));
                    }
                }
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to load ratings", e);
        }
        return map;
    }

    @Override
    public synchronized Map<Integer, Map<UUID, Integer>> getAllRatings() {
        Map<Integer, Map<UUID, Integer>> out = new HashMap<>();
        try {
            ensureConnected();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT poi_id, player_uuid, rating FROM ratings")) {
                while (rs.next()) {
                    UUID player;
                    try {
                        player = UUID.fromString(rs.getString("player_uuid"));
                    } catch (IllegalArgumentException malformed) {
                        continue;
                    }
                    out.computeIfAbsent(rs.getInt("poi_id"), k -> new HashMap<>())
                            .put(player, rs.getInt("rating"));
                }
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to load ratings", e);
        }
        return out;
    }

    @Override
    public synchronized Route createRoute(Route route) {
        String sql = "INSERT INTO routes (name, world, transport, created_by, created_at) VALUES (?, ?, ?, ?, ?)";
        try {
            ensureConnected();
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int routeId;
                try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, route.getName());
                    ps.setString(2, route.getWorld());
                    ps.setString(3, route.getTransport());
                    ps.setString(4, route.getCreatedBy());
                    ps.setLong(5, route.getCreatedAt());
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (!rs.next()) {
                            throw new SQLException("No generated key for route");
                        }
                        routeId = rs.getInt(1);
                    }
                }
                String nodeSql = "INSERT INTO route_nodes (route_id, seq, world, x, y, z) VALUES (?, ?, ?, ?, ?, ?)";
                try (PreparedStatement nps = connection.prepareStatement(nodeSql)) {
                    int seq = 0;
                    for (Location loc : route.getNodes()) {
                        nps.setInt(1, routeId);
                        nps.setInt(2, seq++);
                        nps.setString(3, loc.getWorld() != null ? loc.getWorld().getName() : route.getWorld());
                        nps.setDouble(4, loc.getX());
                        nps.setDouble(5, loc.getY());
                        nps.setDouble(6, loc.getZ());
                        nps.addBatch();
                    }
                    nps.executeBatch();
                }
                connection.commit();
                return new Route(routeId, route.getName(), route.getWorld(), route.getTransport(),
                        route.getCreatedBy(), route.getCreatedAt(), route.getNodes());
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to create route: " + route.getName(), e);
        }
    }

    @Override
    public synchronized boolean deleteRoute(int id) {
        try {
            ensureConnected();
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            boolean deleted;
            try {
                try (PreparedStatement nps = connection.prepareStatement(
                        "DELETE FROM route_nodes WHERE route_id = ?")) {
                    nps.setInt(1, id);
                    nps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement("DELETE FROM routes WHERE id = ?")) {
                    ps.setInt(1, id);
                    deleted = ps.executeUpdate() > 0;
                }
                connection.commit();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                }
            }
            return deleted;
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to delete route: " + id, e);
        }
    }

    @Override
    public synchronized List<Route> getRoutes() {
        // Маршруты и их узлы — двумя запросами вместо N+1 (один loadNodes на
        // каждый маршрут под тем же локом соединения блокировал бы всю БД).
        Map<Integer, Object[]> rows = new HashMap<>();
        try {
            ensureConnected();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id, name, world, transport, created_by, created_at FROM routes ORDER BY name")) {
                while (rs.next()) {
                    rows.put(rs.getInt("id"), new Object[]{
                            rs.getInt("id"), rs.getString("name"), rs.getString("world"),
                            rs.getString("transport"), rs.getString("created_by"), rs.getLong("created_at")});
                }
            }
            Map<Integer, List<double[]>> allNodes = loadAllNodes();
            List<Route> list = new ArrayList<>(rows.size());
            for (Object[] row : rows.values()) {
                int id = (int) row[0];
                List<double[]> coords = allNodes.getOrDefault(id, List.of());
                list.add(new Route(id, (String) row[1], (String) row[2], (String) row[3],
                        (String) row[4], (Long) row[5], locationsOf((String) row[2], coords)));
            }
            return list;
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to load routes", e);
        }
    }

    /** Все узлы всех маршрутов одним проходом: {@code routeId -> [x, y, z]...}. */
    private synchronized Map<Integer, List<double[]>> loadAllNodes() throws SQLException {
        Map<Integer, List<double[]>> out = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT route_id, x, y, z FROM route_nodes ORDER BY route_id, seq")) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getInt("route_id"), k -> new ArrayList<>())
                        .add(new double[]{rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z")});
            }
        }
        return out;
    }

    /**
     * Точки маршрута. Узлы, чей мир ещё не загружен, пропускаются — но об этом
     * пишется предупреждение: раньше маршрут молча укорачивался, и граф дорог
     * на старте сервера оказывался полупустым без единой строчки в логе.
     */
    private List<Location> locationsOf(String routeWorld, List<double[]> coords) {
        List<Location> nodes = new ArrayList<>(coords.size());
        for (double[] c : coords) {
            if (c == null || c.length < 3) {
                continue;
            }
            World world = routeWorld == null ? null : resolveWorld(routeWorld);
            if (world == null) {
                warnMissingWorld(-1, routeWorld);
                continue;
            }
            nodes.add(new Location(world, c[0], c[1], c[2]));
        }
        return nodes;
    }

    @Override
    public synchronized Route getRouteByName(String name) {
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, name, world, transport, created_by, created_at FROM routes WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int id = rs.getInt("id");
                        return new Route(id, rs.getString("name"), rs.getString("world"),
                                rs.getString("transport"), rs.getString("created_by"),
                                rs.getLong("created_at"), loadNodes(id));
                    }
                }
            }
        } catch (SQLException e) {
            markStale();
            throw new RuntimeException("Failed to find route: " + name, e);
        }
        return null;
    }

    /**
     * Точки маршрута. Узлы, чей мир ещё не загружен, пропускаются — но об этом
     * пишется предупреждение: раньше маршрут молча укорачивался, и граф дорог
     * на старте сервера оказывался полупустым без единой строчки в логе.
     */
    private List<Location> loadNodes(int routeId) throws SQLException {
        String routeWorld = null;
        List<double[]> coords = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT r.world AS world, n.x AS x, n.y AS y, n.z AS z " +
                        "FROM route_nodes n JOIN routes r ON r.id = n.route_id " +
                        "WHERE n.route_id = ? ORDER BY n.seq")) {
            ps.setInt(1, routeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (routeWorld == null) {
                        routeWorld = rs.getString("world");
                    }
                    coords.add(new double[]{rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z")});
                }
            }
        }
        if (routeWorld == null) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT world FROM routes WHERE id = ?")) {
                ps.setInt(1, routeId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        routeWorld = rs.getString("world");
                    }
                }
            }
        }
        return locationsOf(routeWorld, coords);
    }

    /** Точка расширения для тестов: в юнит-тестах Bukkit недоступен. */
    protected World resolveWorld(String name) {
        try {
            return Bukkit.getWorld(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void warnMissingWorld(int routeId, String worldName) {
        String key = worldName + "#" + routeId;
        synchronized (warnedWorlds) {
            if (!warnedWorlds.add(key)) {
                return;
            }
        }
        log().log(Level.WARNING, "HahGps: мир «" + worldName + "» не загружен — узлы маршрута "
                + routeId + " пропущены. Граф пересоберётся, когда мир поднимется.");
    }

    @Override
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            log().log(Level.FINE, "HahGps: соединение закрылось с ошибкой: " + e.getMessage());
        }
    }

    private static Logger log() {
        try {
            Logger logger = Bukkit.getLogger();
            if (logger != null) {
                return logger;
            }
        } catch (Throwable ignored) {
        }
        return Logger.getLogger("HahGps");
    }
}
