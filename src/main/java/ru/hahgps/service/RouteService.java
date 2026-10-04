package ru.hahgps.service;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import ru.hahgps.model.RoadGraph;
import ru.hahgps.model.TransportType;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class RouteService {

    private final GraphService graphService;

    public RouteService(GraphService graphService) {
        this.graphService = graphService;
    }

    public List<Location> buildPathTo(Player player, Location target, TransportType transport) {
        if (target == null || player == null) {
            return List.of();
        }
        RoadGraph graph = graphService.getRoadGraph();
        return Pathfinder.buildRoute(graph, player.getLocation(), target, transport);
    }

    public List<Location> buildMultiModalPath(Player player, Location target, TransportType transport) {
        if (target == null || player == null) {
            return List.of();
        }
        RoadGraph graph = graphService.getRoadGraph();
        return Pathfinder.buildMultiModalRoute(graph, player.getLocation(), target, transport);
    }

    public CompletableFuture<List<Location>> buildPathToAsync(Player player, Location target,
                                                             TransportType transport) {


        Location start = player.getLocation().clone();
        Location end = target == null ? null : target.clone();
        TransportType type = transport == null ? TransportType.FOOT : transport;
        return CompletableFuture.supplyAsync(() -> {
            if (end == null) {
                return List.<Location>of();
            }
            RoadGraph graph = graphService.snapshot();
            return Pathfinder.buildRoute(graph, start, end, type);
        });
    }

    public CompletableFuture<List<Location>> buildMultiModalAsync(Player player, Location target,
                                                                  TransportType transport) {
        Location start = player.getLocation().clone();
        Location end = target == null ? null : target.clone();
        TransportType type = transport == null ? TransportType.FOOT : transport;
        return CompletableFuture.supplyAsync(() -> {
            if (end == null) {
                return List.<Location>of();
            }
            RoadGraph graph = graphService.snapshot();
            return Pathfinder.buildMultiModalRoute(graph, start, end, type);
        });
    }
}
