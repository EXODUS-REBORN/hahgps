package ru.hahgps.storage;
import ru.hahgps.model.Category;
import ru.hahgps.model.Poi;
import ru.hahgps.model.Route;
import ru.hahgps.model.Tracker;
import org.bukkit.Bukkit;
import ru.hahgps.HahGpsPlugin;
import java.util.ArrayList;
import java.util.Collections;
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
 * Обращения к провайдеру уходят в пул: главный поток сервера на SQL не ходит
 * никогда. Пул из двух потоков, но соединение с БД одно и методы провайдера
 * синхронизированы — параллельных запросов не бывает, второй поток только
 * разгружает планирование.
 */
public class DataService {

    private final HahGpsPlugin plugin;
    private final StorageProvider provider;
    private final ExecutorService executor;

    private final Map<Integer, Category> categoryCache = new ConcurrentHashMap<>();
    private final Map<String, Category> categoryByName = new ConcurrentHashMap<>();
    private final Map<Integer, Poi> poiCache = new ConcurrentHashMap<>();

    /** Отсортированный по имени снимок poiCache: список точек открывают часто. */
    private volatile List<Poi> sortedPois = List.of();

    /**
     * Статистика поиска мест: {@code poiId -> (запрос -> сколько разных
     * игроков искали)} и {@code poiId -> сколько разных игроков искали точку}.
     * Снимки неизменяемые: поиск читает их из пула БД, пока запись добавляет
     * строку. Флаг «грязно» заставляет перечитать агрегаты при следующем
     * поиске — так счётчики остаются точными и после перезапуска сервера.
     */
    private volatile Map<Integer, Map<String, Integer>> poiSearchStats = Map.of();
    private volatile Map<Integer, Integer> poiSearcherCounts = Map.of();
    private final java.util.concurrent.atomic.AtomicBoolean searchStatsDirty =
            new java.util.concurrent.atomic.AtomicBoolean(true);

    private final List<Route> routeCache = new ArrayList<>();
    /** Растёт при каждом изменении routeCache: защита от гонки читателя и писателя. */
    private int routeCacheVersion = 0;

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
            sortedPois = sortPois(poiCache.values());

            List<Route> routes = provider.getRoutes();
            synchronized (routeCache) {
                routeCache.clear();
                routeCache.addAll(routes);
                routeCacheVersion++;
            }

            loadSearchStats();
        }, executor);
    }

    /** Агрегаты статистики поиска — один раз при старте и после каждой записи. */
    private void loadSearchStats() {
        poiSearchStats = immutable(provider.getPoiSearchStats());
        poiSearcherCounts = Map.copyOf(provider.getPoiSearcherCounts());
    }

    private static Map<Integer, Map<String, Integer>> immutable(
            Map<Integer, Map<String, Integer>> source) {
        Map<Integer, Map<String, Integer>> copy = new java.util.HashMap<>();
        if (source != null) {
            for (Map.Entry<Integer, Map<String, Integer>> entry : source.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                copy.put(entry.getKey(), Map.copyOf(entry.getValue()));
            }
        }
        return Map.copyOf(copy);
    }

    public Map<Integer, Map<String, Integer>> searchStatsSnapshot() {
        return poiSearchStats;
    }

    public Map<Integer, Integer> searcherCountsSnapshot() {
        return poiSearcherCounts;
    }

    /**
     * Перечитать статистику поиска, если она устарела (после новой записи).
     * Вызывается из пула БД: чтение таблицы в главном потоке недопустимо.
     */
    public void refreshSearchStatsNow() {
        if (!searchStatsDirty.compareAndSet(true, false)) {
            return;
        }
        try {
            loadSearchStats();
        } catch (Exception e) {
            searchStatsDirty.set(true);
            logFailure(e);
        }
    }

    /**
     * Вычисление в пуле БД. Поиск считает похожесть по всем точкам — на
     * главном потоке это дало бы лаг на большом списке мест.
     */
    public <T> CompletableFuture<T> computeAsync(Supplier<T> supplier) {
        return async(supplier);
    }

    /**
     * Запись сигнала «этим запросом искали это место». Ошибка БД не должна
     * ломать поиск: статистика — украшение, а не данные игрока.
     */
    public CompletableFuture<Void> recordPoiSearch(int poiId, UUID playerId, String query) {
        if (playerId == null || query == null || query.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                provider.recordPoiSearch(poiId, playerId, query);
            } catch (Exception e) {
                logFailure(e);
            } finally {
                searchStatsDirty.set(true);
            }
        }, executor);
    }

    /** Снимок кэша точек, отсортированный по имени (регистронезависимо). */
    public List<Poi> getCachedPois() {
        return sortedPois;
    }

    /**
     * Сколько точек в каждой категории — по кэшу. Раньше /gps category list
     * делал по запросу на категорию (N+1) и блокировал единственное
     * соединение с БД.
     */
    public Map<Integer, Long> countPoisByCategoryCached() {
        Map<Integer, Long> counts = new java.util.HashMap<>();
        for (Poi poi : poiCache.values()) {
            counts.merge(poi.getCategoryId(), 1L, Long::sum);
        }
        return counts;
    }

    private static List<Poi> sortPois(java.util.Collection<Poi> values) {
        List<Poi> out = new ArrayList<>(values);
        out.sort(java.util.Comparator.comparing(Poi::getName, String.CASE_INSENSITIVE_ORDER));
        return Collections.unmodifiableList(out);
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

    /**
     * Сброс кэша маршрутов: после перезагрузки мира в нём висят Location на
     * мёртвые инстансы World. Вызывается из WorldLoadListener перед
     * пересборкой графа.
     */
    public void invalidateRoutes() {
        synchronized (routeCache) {
            if (routeCache.isEmpty()) {
                return;
            }
            routeCache.clear();
            routeCacheVersion++;
        }
    }

    private <T> CompletableFuture<T> async(Supplier<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return action.get();
            } catch (Exception e) {
                logFailure(e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    private CompletableFuture<Void> asyncVoid(Runnable action) {
        return CompletableFuture.runAsync(() -> {
            try {
                action.run();
            } catch (Exception e) {
                logFailure(e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    /**
     * Дубликат имени — ошибка пользователя, а не сбой сервера: команда сама
     * скажет «уже существует». Раньше на каждый такой случай в консоль летел
     * ERROR со стектрейсом SQLite, и настоящие сбои БД тонули в этом шуме.
     */
    private void logFailure(Exception e) {
        if (isDuplicateLike(e)) {
            plugin.getLogger().log(Level.FINE, "Database rejected a duplicate", e);
        } else {
            plugin.getLogger().log(Level.SEVERE, "Database operation failed", e);
        }
    }

    private static boolean isDuplicateLike(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("unique") || lower.contains("duplicate")) {
                return true;
            }
        }
        return false;
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
                sortedPois = sortPois(poiCache.values());
            }
            return created;
        });
    }

    public CompletableFuture<Boolean> deletePoi(int id) {
        return async(() -> {
            boolean result = provider.deletePoi(id);
            if (result) {
                poiCache.remove(id);
                sortedPois = sortPois(poiCache.values());
                // Статистика поиска точки удалена вместе с ней — перечитаем.
                searchStatsDirty.set(true);
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
                sortedPois = sortPois(poiCache.values());
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

    /**
     * Маршруты. Кэш тёплый — отдаём снимок, но future ВСЕГДА доисполняется в
     * пуле: GraphService.loadGraph() строит граф в handle(), а на уже
     * завершённом future колбэк выполнился бы на вызывающем потоке — то есть
     * в главном, на каждом WorldLoadEvent.
     */
    public CompletableFuture<List<Route>> getRoutes() {
        return async(() -> {
            synchronized (routeCache) {
                if (!routeCache.isEmpty()) {
                    return new ArrayList<>(routeCache);
                }
            }
            return provider.getRoutes();
        });
    }

    public CompletableFuture<Route> getRouteByName(String name) {
        return async(() -> provider.getRouteByName(name));
    }

    /** Сохранённые админские рёбра графа. */
    public CompletableFuture<List<ru.hahgps.model.EdgeRecord>> getEdgesAsync() {
        return async(provider::getEdges);
    }

    public CompletableFuture<Void> saveEdge(ru.hahgps.model.EdgeRecord edge) {
        return asyncVoid(() -> provider.saveEdge(edge));
    }

    public CompletableFuture<Void> deleteEdge(ru.hahgps.model.EdgeRecord edge) {
        return asyncVoid(() -> provider.deleteEdge(edge));
    }

    /** Установленные трекеры — один запрос при старте. */
    public CompletableFuture<List<Tracker>> getTrackers() {
        return async(provider::getTrackers);
    }

    public CompletableFuture<Tracker> createTracker(Tracker tracker) {
        return async(() -> provider.createTracker(tracker));
    }

    public CompletableFuture<Boolean> updateTracker(Tracker tracker) {
        return async(() -> provider.updateTracker(tracker));
    }

    public CompletableFuture<Boolean> deleteTracker(int id) {
        return async(() -> provider.deleteTracker(id));
    }

    /** Блок порядковых номеров для уникальных кодов iTrak. */
    public CompletableFuture<Long> reserveTrackerCodes(int count) {
        return async(() -> provider.reserveTrackerCodeBlock(count));
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
        // Короткие таймауты: onDisable идёт в главном потоке, а зависшая на
        // SQLite-блокировке задача (busy_timeout=5s) иначе стояла бы секунды.
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
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
