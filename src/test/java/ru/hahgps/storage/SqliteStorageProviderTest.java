package ru.hahgps.storage;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.hahgps.model.Category;
import ru.hahgps.model.Poi;
import ru.hahgps.model.Route;
import ru.hahgps.model.Tracker;
import ru.hahgps.model.TrackerColor;
import ru.hahgps.model.TrackerTerminal;
import ru.hahgps.model.TransportType;
import ru.hahgps.testsupport.FakeWorld;

import java.io.File;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Реальное хранилище на SQLite: до 1.1.0 слой БД не проверялся ни одним тестом,
 * а вместе с ним и каскадное удаление оценок, и подсчёт точек в категории.
 */
class SqliteStorageProviderTest {

    /** Провайдер с подменённым разрешением миров: Bukkit в тестах недоступен. */
    private static class TestProvider extends SqliteStorageProvider {
        private final Map<String, World> worlds;

        TestProvider(File file, Map<String, World> worlds) {
            super(file);
            this.worlds = worlds;
        }

        @Override
        protected World resolveWorld(String name) {
            return worlds.get(name);
        }
    }

    private static final World WORLD = FakeWorld.named("world");

    @TempDir
    Path tempDir;

    private TestProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        provider = new TestProvider(tempDir.resolve("hahgps.db").toFile(), Map.of("world", WORLD));
        provider.init();
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    private Poi poi(String name, int categoryId) {
        return new Poi(0, name, categoryId, "world", 10.5, 64.0, -3.25,
                "описание", "tester", 1_700_000_000_000L);
    }

    @Test
    void categoriesAreStoredAndFoundByName() {
        Category created = provider.createCategory("Тест", false);
        assertNotNull(created);
        assertTrue(created.getId() > 0);

        Category found = provider.getCategoryByName("Тест");
        assertNotNull(found);
        assertEquals(created.getId(), found.getId());
        assertFalse(found.isDefaultCategory());

        assertEquals(1, provider.getCategories().size());
        assertTrue(provider.deleteCategory(created.getId()));
        assertEquals(0, provider.getCategories().size());
    }

    @Test
    void poisSurviveRoundTrip() {
        Category category = provider.createCategory("Тест", false);
        Poi created = provider.createPoi(poi("Спавн", category.getId()));
        assertNotNull(created);

        List<Poi> all = provider.getPois();
        assertEquals(1, all.size());
        Poi loaded = all.get(0);
        assertEquals("Спавн", loaded.getName());
        assertEquals(category.getId(), loaded.getCategoryId());
        assertEquals(10.5, loaded.getX(), 0.0001);
        assertEquals(-3.25, loaded.getZ(), 0.0001);
        assertEquals("tester", loaded.getAuthor());
        assertEquals(1_700_000_000_000L, loaded.getCreatedAt());

        created = new Poi(loaded.getId(), "Спавн 2", category.getId(), "world", 1, 2, 3,
                "новое", "tester", loaded.getCreatedAt());
        assertTrue(provider.updatePoi(created));
        assertEquals("Спавн 2", provider.getPoiByName("Спавн 2").getName());
        assertNull(provider.getPoiByName("Спавн"));
    }

    @Test
    void countPoisInCategoryHelpsBeforeDeleting() {
        Category one = provider.createCategory("Одна", false);
        Category two = provider.createCategory("Две", false);
        provider.createPoi(poi("A", one.getId()));
        provider.createPoi(poi("B", one.getId()));

        assertEquals(2, provider.countPoisInCategory(one.getId()));
        assertEquals(0, provider.countPoisInCategory(two.getId()));
    }

    @Test
    void ratingIsUpsertedPerPlayer() {
        Category category = provider.createCategory("Тест", false);
        Poi created = provider.createPoi(poi("Спавн", category.getId()));
        UUID player = UUID.randomUUID();

        provider.setRating(created.getId(), player, 5);
        provider.setRating(created.getId(), player, 3);

        Map<UUID, Integer> ratings = provider.getRatings(created.getId());
        assertEquals(1, ratings.size(), "повторная оценка того же игрока перезаписывает старую");
        assertEquals(3, ratings.get(player));

        Map<Integer, Map<UUID, Integer>> all = provider.getAllRatings();
        assertEquals(1, all.size());
        assertEquals(3, all.get(created.getId()).get(player));
    }

    @Test
    void deletingPoiRemovesItsRatings() {
        Category category = provider.createCategory("Тест", false);
        Poi created = provider.createPoi(poi("Спавн", category.getId()));
        provider.setRating(created.getId(), UUID.randomUUID(), 4);

        assertTrue(provider.deletePoi(created.getId()));

        assertTrue(provider.getPois().isEmpty());
        assertTrue(provider.getAllRatings().isEmpty(),
                "оценки не должны оставаться сиротами после удаления точки");
    }

    @Test
    void routesSurviveRoundTripWithNodes() {
        Route route = new Route(0, "Дом-Работа", "world", "FOOT", "tester", 1_700_000_000_000L,
                List.of(new Location(WORLD, 1, 64, 1),
                        new Location(WORLD, 20, 65, 1),
                        new Location(WORLD, 40, 66, 3)));
        Route created = provider.createRoute(route);
        assertNotNull(created);
        assertTrue(created.getId() > 0);

        List<Route> routes = provider.getRoutes();
        assertEquals(1, routes.size());
        Route loaded = routes.get(0);
        assertEquals("Дом-Работа", loaded.getName());
        assertEquals("world", loaded.getWorld());
        assertEquals(3, loaded.getNodeCount());
        assertEquals(1.0, loaded.getNodes().get(0).getX(), 0.0001);
        assertEquals(40.0, loaded.getNodes().get(2).getX(), 0.0001);

        assertTrue(provider.deleteRoute(created.getId()));
        assertTrue(provider.getRoutes().isEmpty());
    }

    @Test
    void routeNodesOfUnknownWorldAreSkippedInsteadOfBreakingTheRoute() {
        Route route = new Route(0, "Чужой мир", "missing", "FOOT", "tester", 1L,
                List.of(new Location(FakeWorld.named("missing"), 5, 64, 5),
                        new Location(FakeWorld.named("missing"), 15, 64, 5)));
        provider.createRoute(route);

        List<Route> routes = provider.getRoutes();
        assertEquals(1, routes.size(), "маршрут остаётся, даже если мир не загружен");
        assertEquals(0, routes.get(0).getNodeCount(), "узлы недоступного мира пропускаются");
    }

    @Test
    void trackersSurviveRoundTrip() {
        long now = 1_700_000_000_000L;
        UUID owner = UUID.randomUUID();
        Tracker tracker = new Tracker(0, "TRK-AB12", "Мой", TrackerColor.CYAN, true,
                owner, "tester", "world", 10.5, 64.0, -3.25, null, false, now, now, now);
        Tracker created = provider.createTracker(tracker);
        assertNotNull(created);
        assertTrue(created.getId() > 0);

        List<Tracker> loaded = provider.getTrackers();
        assertEquals(1, loaded.size());
        Tracker saved = loaded.get(0);
        assertEquals("TRK-AB12", saved.getCode());
        assertEquals("Мой", saved.getName());
        assertEquals(TrackerColor.CYAN, saved.getColor());
        assertTrue(saved.isWaterproof());
        assertEquals(owner, saved.getOwnerUuid());
        assertEquals("world", saved.getWorld());
        assertEquals(10.5, saved.getX(), 0.0001);
        assertEquals(-3.25, saved.getZ(), 0.0001);
        assertFalse(saved.isSunk());

        Tracker moved = new Tracker(saved.getId(), saved.getCode(), saved.getName(), saved.getColor(),
                saved.isWaterproof(), saved.getOwnerUuid(), saved.getOwnerName(), "world",
                20.0, 65.0, -4.0, null, false, saved.getPlacedAt(), now + 5000, now + 5000);
        assertTrue(provider.updateTracker(moved));
        assertEquals(20.0, provider.getTrackers().get(0).getX(), 0.0001);
        assertEquals(now + 5000, provider.getTrackers().get(0).getMovedAt());

        assertTrue(provider.deleteTracker(saved.getId()));
        assertTrue(provider.getTrackers().isEmpty());
    }

    @Test
    void ruinedTrackerKeepsItsFlagAndLastSeenTime() {
        long now = 1_700_000_000_000L;
        Tracker tracker = new Tracker(0, "TRK-RUIN", "Мокрый", TrackerColor.BLUE, false,
                UUID.randomUUID(), "tester", "world", 1.5, 70.0, 2.5, null, false,
                now, now, now);
        Tracker created = provider.createTracker(tracker);
        assertNotNull(created);
        assertFalse(created.isRuined());

        // Промок: сигнала нет, время запоминается — по нему считается
        // «последний раз в сети».
        created.ruin(now + 60_000L);
        assertTrue(provider.updateTracker(created));

        Tracker saved = provider.getTrackers().get(0);
        assertTrue(saved.isRuined());
        assertEquals(now + 60_000L, saved.getRuinedAt());
        assertFalse(saved.hasSignal());
        assertEquals(Tracker.Status.RUINED, saved.status());
        // Координаты последнего места остались теми же.
        assertEquals(1.5, saved.getX(), 0.0001);
        assertEquals(2.5, saved.getZ(), 0.0001);
    }

    @Test
    void trackerCodeBlocksNeverOverlap() {
        long first = provider.reserveTrackerCodeBlock(1000);
        long second = provider.reserveTrackerCodeBlock(1000);
        assertEquals(1L, first);
        assertEquals(1001L, second, "новый блок обязан начинаться сразу за старым");
    }

    @Test
    void trackerCodeCounterSurvivesReopen() throws Exception {
        long first = provider.reserveTrackerCodeBlock(10);
        assertEquals(1L, first);
        provider.close();
        provider = new TestProvider(tempDir.resolve("hahgps.db").toFile(), Map.of("world", WORLD));
        provider.init();
        assertEquals(11L, provider.reserveTrackerCodeBlock(10),
                "после перезапуска счётчик должен продолжиться, иначе коды повторятся");
    }

    @Test
    void sunkTrackersKeepTheirStateAndEntityLink() {
        UUID entity = UUID.randomUUID();
        Tracker tracker = new Tracker(0, "TRK-SUNK", "", TrackerColor.RED, false,
                UUID.randomUUID(), "tester", "world", 1, 62, 2, entity, true, 1L, 2L, 3L);
        provider.createTracker(tracker);

        Tracker saved = provider.getTrackers().get(0);
        assertTrue(saved.isSunk());
        assertFalse(saved.hasSignal(), "затонувший трекер не отдаёт сигнал");
        assertEquals(entity, saved.getEntityUuid());
        assertEquals("", saved.getName());
        assertEquals("TRK-SUNK", saved.getDisplayName(), "без имени подписью становится код");
    }

    @Test
    void trackerSupportBlockSurvivesRoundTrip() {
        Tracker tracker = new Tracker(0, "TRK-BLK1", "", TrackerColor.PURPLE, true,
                UUID.randomUUID(), "tester", "world", 1, 64, 2, null, false,
                1L, 2L, 3L, new Tracker.SupportBlock(10, 63, -4));
        provider.createTracker(tracker);

        Tracker saved = provider.getTrackers().get(0);
        assertEquals(new Tracker.SupportBlock(10, 63, -4), saved.getSupportBlock());
        assertTrue(saved.isOnBlock());

        saved.setSupportBlock(null);
        assertTrue(provider.updateTracker(saved));
        assertNull(provider.getTrackers().get(0).getSupportBlock(),
                "снятая опора тоже сохраняется — иначе трекер снова прилипнет к блоку");
    }

    @Test
    void terminalsSurviveRoundTrip() {
        TrackerTerminal created = provider.createTerminal(new TrackerTerminal(0, "world",
                100, 64, -20, UUID.randomUUID(), "tester", 5L));
        assertNotNull(created);
        assertTrue(created.id() > 0);

        List<TrackerTerminal> all = provider.getTerminals();
        assertEquals(1, all.size());
        TrackerTerminal loaded = all.get(0);
        assertEquals("world", loaded.world());
        assertEquals(100, loaded.x());
        assertEquals(64, loaded.y());
        assertEquals(-20, loaded.z());
        assertEquals("tester", loaded.ownerName());

        assertTrue(provider.deleteTerminal("world", 100, 64, -20));
        assertTrue(provider.getTerminals().isEmpty());
    }

    @Test
    void duplicateTerminalAtSameBlockIsRejected() {
        provider.createTerminal(new TrackerTerminal(0, "world", 1, 64, 1,
                UUID.randomUUID(), "tester", 1L));
        RuntimeException error = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> provider.createTerminal(new TrackerTerminal(0, "world", 1, 64, 1,
                        UUID.randomUUID(), "tester", 2L)));
        assertNotNull(error.getMessage());
    }

    @Test
    void duplicateTrackerCodeIsRejected() {
        Tracker first = new Tracker(0, "TRK-DUP1", "", TrackerColor.BLUE, false,
                UUID.randomUUID(), "tester", "world", 0, 64, 0, null, false, 1L, 1L, 1L);
        provider.createTracker(first);
        Tracker second = new Tracker(0, "TRK-DUP1", "", TrackerColor.GREEN, false,
                UUID.randomUUID(), "tester", "world", 5, 64, 5, null, false, 1L, 1L, 1L);
        RuntimeException error = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> provider.createTracker(second));
        assertTrue(error.getMessage().contains("TRK-DUP1"));
    }

    @Test
    void searchStatsCountDistinctPlayersPerPoiAndQuery() {
        Category category = provider.createCategory("Поиск", false);
        Poi placed = provider.createPoi(poi("Авиапарк", category.getId()));
        assertNotNull(placed);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        provider.recordPoiSearch(placed.getId(), first, "авиапарк");
        // Тот же игрок и тот же запрос второй раз: счётчик игроков не растёт.
        provider.recordPoiSearch(placed.getId(), first, "авиапарк");
        provider.recordPoiSearch(placed.getId(), second, "авиапарк");
        provider.recordPoiSearch(placed.getId(), second, "хайсовская 10");

        Map<Integer, Map<String, Integer>> stats = provider.getPoiSearchStats();
        assertEquals(2, stats.get(placed.getId()).get("авиапарк"));
        assertEquals(1, stats.get(placed.getId()).get("хайсовская 10"));
        assertEquals(2, provider.getPoiSearcherCounts().get(placed.getId()));

        // Удаление точки уносит и её статистику поиска.
        assertTrue(provider.deletePoi(placed.getId()));
        assertFalse(provider.getPoiSearchStats().containsKey(placed.getId()));
    }

    @Test
    void duplicatePoiNameIsRejectedBySchema() {
        Category category = provider.createCategory("Тест", false);
        provider.createPoi(poi("Спавн", category.getId()));

        RuntimeException error = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> provider.createPoi(poi("Спавн", category.getId())));
        assertTrue(error.getMessage().contains("Спавн"));
    }

    @Test
    void schemaSurvivesReopen() throws Exception {
        Category category = provider.createCategory("Тест", false);
        provider.createPoi(poi("Спавн", category.getId()));
        provider.close();

        TestProvider reopened = new TestProvider(tempDir.resolve("hahgps.db").toFile(),
                Map.of("world", WORLD));
        reopened.init();
        try {
            assertEquals(1, reopened.getCategories().size());
            assertEquals(1, reopened.getPois().size());
        } finally {
            reopened.close();
        }
    }
    @Test
    void schemaVersionIsWritten() {
        assertEquals(AbstractSqlStorageProvider.SCHEMA_VERSION, provider.schemaVersion(),
                "версия схемы должна попадать в meta — на ней строятся миграции");
    }

    @Test
    void savedEdgesSurviveReopen() throws Exception {
        provider.saveEdge(new ru.hahgps.model.EdgeRecord("world",
                1, 64, 2, 10, 64, 5, EnumSet.of(TransportType.FOOT, TransportType.CAR)));
        provider.close();

        TestProvider reopened = new TestProvider(
                tempDir.resolve("hahgps.db").toFile(), Map.of("world", WORLD));
        reopened.init();
        try {
            List<ru.hahgps.model.EdgeRecord> edges = reopened.getEdges();
            assertEquals(1, edges.size());
            ru.hahgps.model.EdgeRecord edge = edges.get(0);
            assertEquals("world", edge.world());
            assertEquals(EnumSet.of(TransportType.FOOT, TransportType.CAR), edge.transports());
        } finally {
            reopened.close();
        }
    }

    @Test
    void saveEdgeReplacesEdgeWithSameEndpoints() {
        provider.saveEdge(new ru.hahgps.model.EdgeRecord("world",
                1, 64, 2, 10, 64, 5, EnumSet.of(TransportType.FOOT)));
        provider.saveEdge(new ru.hahgps.model.EdgeRecord("world",
                10, 64, 5, 1, 64, 2, EnumSet.of(TransportType.FOOT, TransportType.CAR)));

        List<ru.hahgps.model.EdgeRecord> edges = provider.getEdges();
        assertEquals(1, edges.size(), "ребро с теми же концами заменяется, а не дублируется");
        assertEquals(2, edges.get(0).transports().size());
    }

    @Test
    void deleteEdgeRemovesOnlyGivenPair() {
        provider.saveEdge(new ru.hahgps.model.EdgeRecord("world",
                1, 64, 2, 10, 64, 5, EnumSet.of(TransportType.FOOT)));
        provider.saveEdge(new ru.hahgps.model.EdgeRecord("world",
                10, 64, 5, 20, 64, 8, EnumSet.of(TransportType.FOOT)));

        provider.deleteEdge(new ru.hahgps.model.EdgeRecord("world",
                1, 64, 2, 10, 64, 5, EnumSet.of(TransportType.FOOT)));

        assertEquals(1, provider.getEdges().size());
    }

    @Test
    void malformedRatingRowDoesNotBreakRatingLoad() throws Exception {
        // Битая строка UUID: getRatings обязан её пропустить, как getAllRatings.
        try (var st = provider.connection.createStatement()) {
            st.execute("INSERT INTO categories (name, is_default) VALUES ('c', 0)");
            st.execute("INSERT INTO pois (name, category_id, world, x, y, z, description, author, created_at) "
                    + "VALUES ('p', 1, 'world', 0, 64, 0, '', 't', 1)");
            st.execute("INSERT INTO ratings (poi_id, player_uuid, rating) VALUES (1, 'not-a-uuid', 5)");
        }
        Map<UUID, Integer> ratings = provider.getRatings(1);
        assertTrue(ratings.isEmpty(), "битая строка не должна ронять весь ответ");
    }

    /** Создаёт базу ровно в том виде, какой её писали старые версии (схема без meta/graph_edges). */
    private void createLegacyDatabase(File file) throws Exception {
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
             var st = conn.createStatement()) {
            st.execute("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "name VARCHAR(64) UNIQUE NOT NULL, is_default TINYINT NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE pois (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "name VARCHAR(64) UNIQUE NOT NULL, category_id INTEGER NOT NULL, "
                    + "world VARCHAR(64) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, "
                    + "description TEXT, author VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL)");
            st.execute("CREATE TABLE ratings (poi_id INTEGER NOT NULL, player_uuid VARCHAR(36) NOT NULL, "
                    + "rating INTEGER NOT NULL, PRIMARY KEY (poi_id, player_uuid))");
            st.execute("CREATE TABLE routes (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "name VARCHAR(64) UNIQUE NOT NULL, world VARCHAR(64) NOT NULL, "
                    + "transport VARCHAR(16) NOT NULL DEFAULT 'FOOT', created_by VARCHAR(64), created_at BIGINT NOT NULL)");
            st.execute("CREATE TABLE route_nodes (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "route_id INTEGER NOT NULL, seq INTEGER NOT NULL, world VARCHAR(64) NOT NULL, "
                    + "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL)");
            st.execute("INSERT INTO categories (name, is_default) VALUES ('Старая', 0)");
            st.execute("INSERT INTO pois (name, category_id, world, x, y, z, description, author, created_at) "
                    + "VALUES ('Точка', 1, 'world', 5, 64, 7, 'desc', 'author', 42)");
            st.execute("INSERT INTO ratings (poi_id, player_uuid, rating) VALUES (1, '"
                    + UUID.randomUUID() + "', 9)");
            st.execute("INSERT INTO routes (name, world, transport, created_by, created_at) "
                    + "VALUES ('Маршрут', 'world', 'FOOT', 'admin', 42)");
            st.execute("INSERT INTO route_nodes (route_id, seq, world, x, y, z) VALUES (1, 0, 'world', 1, 64, 1)");
            st.execute("INSERT INTO route_nodes (route_id, seq, world, x, y, z) VALUES (1, 1, 'world', 2, 64, 2)");
        }
    }

    @Test
    void legacyDatabaseIsUpgradedWithoutLosingData() throws Exception {
        File file = tempDir.resolve("legacy.db").toFile();
        createLegacyDatabase(file);

        TestProvider legacy = new TestProvider(file, Map.of("world", WORLD));
        legacy.init();
        try {
            assertEquals(AbstractSqlStorageProvider.SCHEMA_VERSION, legacy.schemaVersion(),
                    "старая база без meta должна получить текущую версию схемы");
            assertEquals(1, legacy.getCategories().size());
            assertEquals("Старая", legacy.getCategories().get(0).getName());
            assertEquals(1, legacy.getPois().size());
            Poi poi = legacy.getPois().get(0);
            assertEquals("Точка", poi.getName());
            assertEquals(5.0, poi.getX(), 0.0001);
            assertEquals("author", poi.getAuthor());
            assertEquals(42L, poi.getCreatedAt());
            assertEquals(9, legacy.getAllRatings().get(1).values().iterator().next());
            assertEquals(1, legacy.getRoutes().size());
            assertEquals(2, legacy.getRoutes().get(0).getNodeCount());
            assertTrue(legacy.getEdges().isEmpty(), "graph_edges создаётся пустым для старой базы");
            assertTrue(legacy.getTrackers().isEmpty(), "таблица трекеров появляется пустой");
            assertTrue(legacy.getTerminals().isEmpty(), "таблица терминалов появляется пустой");
        } finally {
            legacy.close();
        }
    }

    @Test
    void missingColumnsAreBackfilledOnOpen() throws Exception {
        File file = tempDir.resolve("drift.db").toFile();
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
             var st = conn.createStatement()) {
            st.execute("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "name VARCHAR(64) UNIQUE NOT NULL, is_default TINYINT NOT NULL DEFAULT 0)");
            // Таблица pois без author и created_at — как будто её создала более ранняя/чужая версия.
            st.execute("CREATE TABLE pois (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "name VARCHAR(64) UNIQUE NOT NULL, category_id INTEGER NOT NULL, "
                    + "world VARCHAR(64) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, "
                    + "description TEXT)");
            st.execute("INSERT INTO categories (name, is_default) VALUES ('c', 0)");
            st.execute("INSERT INTO pois (name, category_id, world, x, y, z, description) "
                    + "VALUES ('p', 1, 'world', 1, 2, 3, 'd')");
        }

        TestProvider drifted = new TestProvider(file, Map.of("world", WORLD));
        drifted.init();
        try {
            List<Poi> pois = drifted.getPois();
            assertEquals(1, pois.size());
            assertEquals("", pois.get(0).getAuthor(), "author дозаполняется пустым значением");
            assertEquals(0L, pois.get(0).getCreatedAt(), "created_at дозаполняется нулём");
        } finally {
            drifted.close();
        }
    }

    @Test
    void duplicateTrackerCodesAreCleanedUpOnMigration() throws Exception {
        File file = tempDir.resolve("dupcodes.db").toFile();
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
             var st = conn.createStatement()) {
            st.execute("CREATE TABLE pois (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "name VARCHAR(64) UNIQUE NOT NULL)");
            st.execute("CREATE TABLE meta (key VARCHAR(32) PRIMARY KEY, "
                    + "value VARCHAR(64) NOT NULL)");
            st.execute("INSERT INTO meta (key, value) VALUES ('schema_version', '4')");
            st.execute("CREATE TABLE trackers (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, code VARCHAR(16) NOT NULL, " +
                    "name VARCHAR(64) NOT NULL DEFAULT '', owner_uuid VARCHAR(36) NOT NULL, " +
                    "owner_name VARCHAR(64) NOT NULL DEFAULT '', color VARCHAR(16) NOT NULL DEFAULT 'BLUE', " +
                    "waterproof TINYINT NOT NULL DEFAULT 0, world VARCHAR(64) NOT NULL, " +
                    "x DOUBLE NOT NULL DEFAULT 0, y DOUBLE NOT NULL DEFAULT 64, " +
                    "z DOUBLE NOT NULL DEFAULT 0, entity_uuid VARCHAR(36), " +
                    "sunk TINYINT NOT NULL DEFAULT 0, placed_at BIGINT NOT NULL DEFAULT 0, " +
                    "moved_at BIGINT NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL DEFAULT 0)");
            st.execute("INSERT INTO trackers (code, owner_uuid, owner_name, world) "
                    + "VALUES ('TRK-DUP1', '00000000-0000-0000-0000-000000000001', 'a', 'world')");
            st.execute("INSERT INTO trackers (code, owner_uuid, owner_name, world) "
                    + "VALUES ('TRK-DUP1', '00000000-0000-0000-0000-000000000002', 'b', 'world')");
        }

        TestProvider legacy = new TestProvider(file, Map.of("world", WORLD));
        legacy.init();
        try {
            List<Tracker> trackers = legacy.getTrackers();
            assertEquals(1, trackers.size(), "дубль кода — призрак, его нужно убрать");
            assertEquals("TRK-DUP1", trackers.get(0).getCode());
            assertEquals(AbstractSqlStorageProvider.SCHEMA_VERSION, legacy.schemaVersion());
        } finally {
            legacy.close();
        }
    }
}
