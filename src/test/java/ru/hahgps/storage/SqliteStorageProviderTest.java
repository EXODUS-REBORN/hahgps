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
import ru.hahgps.testsupport.FakeWorld;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
}
