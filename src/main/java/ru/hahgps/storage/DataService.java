package ru.hahgps.storage;

import ru.hahgps.model.Category;
import ru.hahgps.model.GraphNode;
import ru.hahgps.model.Poi;
import ru.hahgps.model.RoadGraph;
import ru.hahgps.model.Route;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;
import ru.hahgps.HahGpsPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Асинхронный сервис данных поверх {@link StorageProvider}.
 * Кэширует категории и маршруты, строит граф дорог.
 */
public class DataService {

    private final HahGpsPlugin plugin;
    private final StorageProvider provider;
    private final ExecutorService executor;

    // Кэш категорий
    private final Map<Integer, Category> categoryCache = new ConcurrentHashMap<>();
    private final Map<String, Category> categoryByName = new ConcurrentHashMap<>();

    // Кэш маршрутов (для графа)
    private final List<Route> routeCache = new ArrayList<>();
    private volatile RoadGraph roadGraph = new RoadGraph();

    public DataService(HahGpsPlugin plugin, StorageProvider provider) {
        this.plugin = plugin;
        this.provider = provider;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "HahGps-DB");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Загрузка кэша после инициализации провайдера.
     */
    public CompletableFuture<Void> loadCache() {
        return CompletableFuture.runAsync(() -> {
            try {
                // Категории
                List<Category> categories = provider.getCategories();
                categoryCache.clear();
                categoryByName.clear();
                for (Category c : categories) {
                    categoryCache.put(c.getId(), c);
                    categoryByName.put(c.getName().toLowerCase(), c);
                }
                // Маршруты + граф
                List<Route> routes = provider.getRoutes();
                routeCache.clear();
                routeCache.addAll(routes);
                rebuildGraph();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load data cache", e);
            }
        }, executor);
    }

    /**
     * Перестроение графа дорог из кэша маршрутов.
     */
    public synchronized void rebuildGraph() {
        RoadGraph graph = new RoadGraph();
        int nodeId = 1;
        for (Route route : routeCache) {
            GraphNode prev = null;
            for (Location loc : route.getNodes()) {
                GraphNode node = new GraphNode(nodeId++, loc);
                graph.addNode(node);
                if (prev != null) {
                    graph.addEdge(prev, node);
                }
                prev = node;
            }
        }
        this.roadGraph = graph;
    }

    public RoadGraph getRoadGraph() {
        return roadGraph;
    }

    public Category getCachedCategory(int id) {
        return categoryCache.get(id);
    }

    public Category getCachedCategoryByName(String name) {
        return categoryByName.get(name.toLowerCase());
    }

    public List<Category> getCachedCategories() {
        return new ArrayList<>(categoryCache.values());
    }

    // ---------------- Асинхронные обёртки ----------------

    private <T> CompletableFuture<T> async(Supplier<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return action.get();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Database operation failed", e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    private CompletableFuture<Void> asyncVoid(Runnable action) {
        return CompletableFuture.runAsync(() -> {
            try {
                action.run();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Database operation failed", e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    // ---------------- Категории ----------------

    public CompletableFuture<Category> createCategory(String name, boolean defaultCategory) {
        return async(() -> {
            Category c = provider.createCategory(name, defaultCategory);
            if (c != null) {
                categoryCache.put(c.getId(), c);
                categoryByName.put(c.getName().toLowerCase(), c);
            }
            return c;
        });
    }

    public CompletableFuture<Boolean> deleteCategory(int id) {
        return async(() -> {
            boolean result = provider.deleteCategory(id);
            Category removed = categoryCache.remove(id);
            if (removed != null) {
                categoryByName.remove(removed.getName().toLowerCase());
            }
            return result;
        });
    }

    public CompletableFuture<List<Category>> getCategories() {
        return async(provider::getCategories);
    }

    public CompletableFuture<Category> getCategoryByName(String name) {
        return async(() -> provider.getCategoryByName(name));
    }

    public CompletableFuture<Category> getCategoryById(int id) {
        return async(() -> provider.getCategoryById(id));
    }

    // ---------------- Точки ----------------

    public CompletableFuture<Poi> createPoi(Poi poi) {
        return async(() -> provider.createPoi(poi));
    }

    public CompletableFuture<Boolean> deletePoi(int id) {
        return async(() -> provider.deletePoi(id));
    }

    public CompletableFuture<Poi> getPoiByName(String name) {
        return async(() -> provider.getPoiByName(name));
    }

    public CompletableFuture<Poi> getPoiById(int id) {
        return async(() -> provider.getPoiById(id));
    }

    public CompletableFuture<List<Poi>> getPois() {
        return async(provider::getPois);
    }

    public CompletableFuture<List<Poi>> getPoisByCategory(int categoryId) {
        return async(() -> provider.getPoisByCategory(categoryId));
    }

    public CompletableFuture<Boolean> updatePoi(Poi poi) {
        return async(() -> provider.updatePoi(poi));
    }

    // ---------------- Рейтинги ----------------

    public CompletableFuture<Void> setRating(int poiId, UUID playerId, int rating) {
        return asyncVoid(() -> provider.setRating(poiId, playerId, rating));
    }

    public CompletableFuture<Map<UUID, Integer>> getRatings(int poiId) {
        return async(() -> provider.getRatings(poiId));
    }

    // ---------------- Маршруты ----------------

    public CompletableFuture<Route> createRoute(Route route) {
        return async(() -> {
            Route r = provider.createRoute(route);
            if (r != null) {
                synchronized (routeCache) {
                    routeCache.add(r);
                }
                rebuildGraph();
            }
            return r;
        });
    }

    public CompletableFuture<Boolean> deleteRoute(int id) {
        return async(() -> {
            boolean result = provider.deleteRoute(id);
            synchronized (routeCache) {
                routeCache.removeIf(r -> r.getId() == id);
            }
            rebuildGraph();
            return result;
        });
    }

    public CompletableFuture<List<Route>> getRoutes() {
        return async(provider::getRoutes);
    }

    public CompletableFuture<Route> getRouteByName(String name) {
        return async(() -> provider.getRouteByName(name));
    }

    // ---------------- Служебное ----------------

    /**
     * Выполнение задачи в главном потоке (для GUI и т.п.).
     */
    public void runSync(Runnable runnable) {
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    public void shutdown() {
        executor.shutdownNow();
        provider.close();
    }

    @FunctionalInterface
    public interface Supplier<T> {
        T get() throws Exception;
    }
}
