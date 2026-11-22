package ru.hahgps.service;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import ru.hahgps.config.ConfigManager;
import ru.hahgps.model.RoadGraph;
import ru.hahgps.model.TransportType;
import ru.hahgps.util.LocationUtil;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Построение маршрута в отдельном пуле.
 *
 * Пул свой, а не {@code ForkJoinPool.commonPool()}: A* может расширить сотни тысяч
 * узлов, и на общих потоках он тормозил бы и БД, и сам сервер. Потоки простаивают
 * и завершаются через 60 секунд, поэтому хот-своп не оставляет за собой живых
 * воркеров.
 */
public class RouteService {

    /** Осторожный обход: дорога может быть чуть длиннее прямой, это нормально. */
    public static final double DEFAULT_DETOUR_FACTOR = 1.35;
    /** И лишние 24 блока — уже заметный крюк, а не погрешность угла. */
    public static final int DEFAULT_DETOUR_BLOCKS = 24;

    private final GraphService graphService;
    private final WalkPathService walkPathService;
    private final ConfigManager config;
    /**
     * Очередь с отказом, а не CallerRunsPolicy: при занятом пуле задача
     * выполнилась бы в ВЫЗЫВАЮЩЕМ потоке — то есть в главном тике сервера,
     * и A* на большом графе фризил бы его на секунды.
     */
    private final ExecutorService executor = new ThreadPoolExecutor(
            0, 2, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(8),
            r -> {
                Thread t = new Thread(r, "HahGps-Route");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    public RouteService(GraphService graphService) {
        this(graphService, null, null);
    }

    public RouteService(GraphService graphService, WalkPathService walkPathService) {
        this(graphService, walkPathService, null);
    }

    public RouteService(GraphService graphService, WalkPathService walkPathService,
                        ConfigManager config) {
        this.graphService = graphService;
        this.walkPathService = walkPathService;
        this.config = config;
    }

    public CompletableFuture<List<Location>> buildMultiModalAsync(Player player, Location target,
                                                                 TransportType transport) {
        Location start = player.getLocation().clone();
        Location end = target == null ? null : target.clone();
        TransportType type = transport == null ? TransportType.FOOT : transport;
        return submit(start, end, type, true);
    }

    public CompletableFuture<List<Location>> buildPathToAsync(Player player, Location target,
                                                              TransportType transport) {
        Location start = player.getLocation().clone();
        Location end = target == null ? null : target.clone();
        TransportType type = transport == null ? TransportType.FOOT : transport;
        return submit(start, end, type, false);
    }

    private CompletableFuture<List<Location>> submit(Location start, Location end,
                                                     TransportType type, boolean multiModal) {
        if (end == null || start == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                RoadGraph graph = graphService.graph();
                return multiModal
                        ? Pathfinder.buildMultiModalRoute(graph, start, end, type)
                        : Pathfinder.buildRoute(graph, start, end, type);
            }, executor).thenCompose(route -> {
                // Две вещи, ради которых вообще нужен второй кандидат:
                // 1) граф не нашёл путь — две точки прямой сквозь горы;
                // 2) граф нашёл путь, но он крюком по записанной админом
                //    дороге. Пешеходу тогда ближе пройти по блокам, а не
                //    огибать полкарты, потому что так когда-то записали маршрут.
                if (type != TransportType.FOOT || walkPathService == null
                        || !walkPathService.isEnabled()
                        || !shouldTryWalk(route, start, end)) {
                    return CompletableFuture.completedFuture(route);
                }
                double graphLength = Pathfinder.routeLength(route);
                return walkPathService.findPath(start, end).thenApply(path -> {
                    if (path.size() <= 1) {
                        return route;
                    }
                    double walkLength = Pathfinder.routeLength(path);
                    // Берём только реально более короткий путь: если A* по
                    // блокам нашёл тот же крюк, остаётся граф.
                    return walkLength > 0 && (graphLength <= 0 || walkLength < graphLength)
                            ? path : route;
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Очередь переполнена: честный отказ, а не расчёт в главном потоке.
            return CompletableFuture.failedFuture(new java.util.concurrent.RejectedExecutionException(
                    "HahGps: сервер занят построением других маршрутов, попробуйте через секунду"));
        }
    }

    /**
     * Стоит ли вообще считать пеший путь по блокам. Считаем, только если
     * граф дал прямую (пути нет) или крюк: длиннее прямой в
     * {@code walk-detour-factor} раз и хотя бы на {@code walk-detour-blocks}
     * блоков. Так обычная дорога с плавным изгибом не заменяется лесом.
     */
    public boolean shouldTryWalk(List<Location> graphRoute, Location start, Location end) {
        if (graphRoute == null || graphRoute.isEmpty() || start == null || end == null) {
            return false;
        }
        if (graphRoute.size() <= 2) {
            return true;
        }
        double direct = LocationUtil.distance(start, end);
        if (!Double.isFinite(direct) || direct <= 0.0) {
            return false;
        }
        double detour = Pathfinder.routeLength(graphRoute);
        if (!Double.isFinite(detour) || detour <= 0.0) {
            return false;
        }
        double factor = config == null ? DEFAULT_DETOUR_FACTOR : config.getWalkDetourFactor();
        int extra = config == null ? DEFAULT_DETOUR_BLOCKS : config.getWalkDetourBlocks();
        return detour > direct * factor + extra;
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
