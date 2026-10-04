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
import java.util.List;
import java.util.Map;
import java.util.UUID;

public abstract class AbstractSqlStorageProvider implements StorageProvider {

    protected final boolean mysql;
    protected Connection connection;

    protected AbstractSqlStorageProvider(boolean mysql) {
        this.mysql = mysql;
    }

    protected abstract void reconnect() throws SQLException;

    protected void ensureConnected() throws SQLException {
        if (connection == null || connection.isClosed() || !isAlive()) {
            forceReconnect();
        }
    }


    private boolean isAlive() {
        try {
            return connection.isValid(3);
        } catch (SQLException e) {
            return false;
        }
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

    protected void createSchema() throws SQLException {
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

            st.execute("CREATE TABLE IF NOT EXISTS ratings (" +
                    "poi_id INTEGER NOT NULL, " +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "rating INTEGER NOT NULL, " +
                    "PRIMARY KEY (poi_id, player_uuid))");

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
                    "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL)");
        }
        createIndexes();
    }


    protected void createIndexes() {
        createIndex("idx_route_nodes_route", "route_nodes", "route_id");
        createIndex("idx_pois_category", "pois", "category_id");
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

            java.util.logging.Logger logger = Bukkit.getLogger();
            if (logger != null) {
                logger.warning("HahGps: не удалось создать индекс " + name + ": " + e.getMessage());
            }
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
            throw new RuntimeException("Failed to load categories", e);
        }
        return list;
    }

    @Override
    public synchronized Category getCategoryByName(String name) {
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement("SELECT id, name, is_default FROM categories WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Category(rs.getInt("id"), rs.getString("name"), rs.getInt("is_default") == 1);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find category: " + name, e);
        }
        return null;
    }

    @Override
    public synchronized Category getCategoryById(int id) {
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement("SELECT id, name, is_default FROM categories WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Category(rs.getInt("id"), rs.getString("name"), rs.getInt("is_default") == 1);
                    }
                }
            }
        } catch (SQLException e) {
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
            throw new RuntimeException("Failed to create poi: " + poi.getName(), e);
        }
        return null;
    }

    @Override
    public synchronized boolean deletePoi(int id) {
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM pois WHERE id = ?")) {
                ps.setInt(1, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete poi: " + id, e);
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
            throw new RuntimeException("Failed to load pois", e);
        }
        return list;
    }

    @Override
    public synchronized List<Poi> getPoisByCategory(int categoryId) {
        List<Poi> list = new ArrayList<>();
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM pois WHERE category_id = ? ORDER BY name")) {
                ps.setInt(1, categoryId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapPoi(rs));
                    }
                }
            }
        } catch (SQLException e) {
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
            throw new RuntimeException("Failed to update poi: " + poi.getName(), e);
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
            throw new RuntimeException("Failed to set rating", e);
        }
    }

    @Override
    public synchronized Map<UUID, Integer> getRatings(int poiId) {
        Map<UUID, Integer> map = new HashMap<>();
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement("SELECT player_uuid, rating FROM ratings WHERE poi_id = ?")) {
                ps.setInt(1, poiId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        map.put(UUID.fromString(rs.getString("player_uuid")), rs.getInt("rating"));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load ratings", e);
        }
        return map;
    }

    @Override
    public synchronized Route createRoute(Route route) {
        String sql = "INSERT INTO routes (name, world, transport, created_by, created_at) VALUES (?, ?, ?, ?, ?)";
        boolean previousAutoCommit = true;
        try {
            ensureConnected();
            previousAutoCommit = connection.getAutoCommit();


            connection.setAutoCommit(false);
            try {
                try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, route.getName());
                    ps.setString(2, route.getWorld());
                    ps.setString(3, route.getTransport());
                    ps.setString(4, route.getCreatedBy());
                    ps.setLong(5, route.getCreatedAt());
                    ps.executeUpdate();
                    int routeId;
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (!rs.next()) {
                            throw new SQLException("No generated key for route");
                        }
                        routeId = rs.getInt(1);
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
                }
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
            throw new RuntimeException("Failed to create route: " + route.getName(), e);
        }
    }

    @Override
    public synchronized boolean deleteRoute(int id) {
        boolean previousAutoCommit = true;
        try {
            ensureConnected();
            previousAutoCommit = connection.getAutoCommit();


            connection.setAutoCommit(false);
            boolean deleted = false;
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
            throw new RuntimeException("Failed to delete route: " + id, e);
        }
    }

    @Override
    public synchronized List<Route> getRoutes() {
        List<Route> list = new ArrayList<>();
        try {
            ensureConnected();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM routes ORDER BY name")) {
                while (rs.next()) {
                    list.add(mapRoute(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load routes", e);
        }
        return list;
    }

    @Override
    public synchronized Route getRouteByName(String name) {
        try {
            ensureConnected();
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM routes WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return mapRoute(rs);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find route: " + name, e);
        }
        return null;
    }

    private Route mapRoute(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        String name = rs.getString("name");
        String world = rs.getString("world");
        String transport = rs.getString("transport");
        String createdBy = rs.getString("created_by");
        long createdAt = rs.getLong("created_at");

        List<Location> nodes = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT world, x, y, z FROM route_nodes WHERE route_id = ? ORDER BY seq")) {
            ps.setInt(1, id);
            try (ResultSet nrs = ps.executeQuery()) {
                while (nrs.next()) {
                    World w;
                    try {

                        w = Bukkit.getWorld(nrs.getString("world"));
                    } catch (Throwable ignored) {
                        w = null;
                    }
                    if (w != null) {
                        nodes.add(new Location(w, nrs.getDouble("x"), nrs.getDouble("y"), nrs.getDouble("z")));
                    }
                }
            }
        }
        return new Route(id, name, world, transport, createdBy, createdAt, nodes);
    }


    @Override
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {

        }
    }
}
