package ru.hahgps.service;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import ru.hahgps.model.RoadGraph;
import ru.hahgps.model.TransportType;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
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

    private final GraphService graphService;
    private final ExecutorService executor = new ThreadPoolExecutor(
            0, 2, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "HahGps-Route");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    public RouteService(GraphService graphService) {
        this.graphService = graphService;
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
            }, executor);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // пул занят и политика отказалась ставить в очередь — считаем на месте
            RoadGraph graph = graphService.graph();
            return CompletableFuture.completedFuture(multiModal
                    ? Pathfinder.buildMultiModalRoute(graph, start, end, type)
                    : Pathfinder.buildRoute(graph, start, end, type));
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
