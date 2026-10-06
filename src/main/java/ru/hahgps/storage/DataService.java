package ru.hahgps.storage;
import ru.hahgps.model.Category;
import ru.hahgps.model.Poi;
import ru.hahgps.model.Route;
import org.bukkit.Bukkit;
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
 * Асинхронная обёртка над хранилищем плюс кэш категорий и точек.
 *
 * Кэш существует для того, чтобы команды, GUI и карта не били в БД на каждое
 * открытие (кэш читается через {@code getCachedPois}/{@code getCachedCategories}).
 * Каждое обращение к провайдеру уходит в собственный пул из двух потоков: главный
 * поток сервера на SQL не ходит никогда.
 */
public class DataService {

    private final HahGpsPlugin plugin;
    private final StorageProvider provider;
    private final ExecutorService executor;

    private final Map<Integer, Category> categoryCache = new ConcurrentHashMap<>();
    private final Map<String, Category> categoryByName = new ConcurrentHashMap<>();
    private final Map<Integer, Poi> poiCache = new ConcurrentHashMap<>();

    private final List<Route> routeCache = new ArrayList<>();

    public DataService(HahGpsPlugin plugin, StorageProvider provider) {
        this.plugin = plugin;
        this.provider = provider;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "HahGps-DB");
            t.setDaemon(true);
            return t;
        });
    }

    public CompletableFuture<Void> loadCache() {
        return CompletableFuture.runAsync(() -> {
            List<Category> categories = provider.getCategories();
            categoryCache.clear();
            categoryByName.clear();
            for (Category c : categories) {
                categoryCache.put(c.getId(), c);
                categoryByName.put(c.getName().toLowerCase(java.util.Locale.ROOT), c);
            }

            List<Poi> pois = provider.getPois();
            poiCache.clear();
            for (Poi p : pois) {
                poiCache.put(p.getId(), p);
            }

            List<Route> routes = provider.getRoutes();
            synchronized (routeCache) {
                routeCache.clear();
                routeCache.addAll(routes);
            }
        }, executor);
    }

    public Category getCachedCategory(int id) {
        return categoryCache.get(id);
    }

    public Category getCachedCategoryByName(String name) {
        return name == null ? null : categoryByName.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    public List<Category> getCachedCategories() {
        return new ArrayList<>(categoryCache.values());
    }

    public List<Poi> getCachedPois() {
        List<Poi> out = new ArrayList<>(poiCache.values());
        out.sort(java.util.Comparator.comparing(Poi::getName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

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

    public CompletableFuture<Category> createCategory(String name, boolean defaultCategory) {
        return async(() -> {
            Category c = provider.createCategory(name, defaultCategory);
            if (c != null) {
                categoryCache.put(c.getId(), c);
                categoryByName.put(c.getName().toLowerCase(java.util.Locale.ROOT), c);
            }
            return c;
        });
    }

    public CompletableFuture<Boolean> deleteCategory(int id) {
        return async(() -> {
            boolean result = provider.deleteCategory(id);
            Category removed = categoryCache.remove(id);
            if (removed != null) {
                categoryByName.remove(removed.getName().toLowerCase(java.util.Locale.ROOT));
            }
            return result;
        });
    }

    public CompletableFuture<Integer> countPoisInCategory(int categoryId) {
        return async(() -> provider.countPoisInCategory(categoryId));
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

    public CompletableFuture<Poi> createPoi(Poi poi) {
        return async(() -> {
            Poi created = provider.createPoi(poi);
            if (created != null) {
                poiCache.put(created.getId(), created);
            }
            return created;
        });
    }

    public CompletableFuture<Boolean> deletePoi(int id) {
        return async(() -> {
            boolean result = provider.deletePoi(id);
            if (result) {
                poiCache.remove(id);
            }
            return result;
        });
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
        return async(() -> {
            boolean result = provider.updatePoi(poi);
            if (result) {
                poiCache.put(poi.getId(), poi);
            }
            return result;
        });
    }

    public CompletableFuture<Void> setRating(int poiId, UUID playerId, int rating) {
        return asyncVoid(() -> provider.setRating(poiId, playerId, rating));
    }

    public CompletableFuture<Map<UUID, Integer>> getRatings(int poiId) {
        return async(() -> provider.getRatings(poiId));
    }

    /** Все оценки одним запросом вместо N+1 при построении списка точек. */
    public CompletableFuture<Map<Integer, Map<UUID, Integer>>> getAllRatings() {
        return async(provider::getAllRatings);
    }

    public CompletableFuture<Route> createRoute(Route route) {
        return async(() -> {
            Route created = provider.createRoute(route);
            if (created != null) {
                synchronized (routeCache) {
                    routeCache.add(created);
                }
            }
            return created;
        });
    }

    public CompletableFuture<Boolean> deleteRoute(int id) {
        return async(() -> {
            boolean result = provider.deleteRoute(id);
            synchronized (routeCache) {
                routeCache.removeIf(r -> r.getId() == id);
            }
            return result;
        });
    }

    public CompletableFuture<List<Route>> getRoutes() {
        synchronized (routeCache) {
            if (!routeCache.isEmpty()) {
                return CompletableFuture.completedFuture(new ArrayList<>(routeCache));
            }
        }
        return async(provider::getRoutes);
    }

    public CompletableFuture<Route> getRouteByName(String name) {
        return async(() -> provider.getRouteByName(name));
    }

    public void runSync(Runnable runnable) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            if (Bukkit.isPrimaryThread()) {
                runnable.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, runnable);
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "HahGps: не удалось выйти на главный поток", t);
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            provider.close();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "HahGps: не удалось закрыть хранилище", t);
        }
    }

    @FunctionalInterface
    public interface Supplier<T> {
        T get() throws Exception;
    }
}
