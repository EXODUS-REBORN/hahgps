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
        if (target == null) {
            return List.of();
        }
        RoadGraph graph = graphService.getRoadGraph();
        return Pathfinder.buildRoute(graph, player.getLocation(), target, transport);
    }

    public List<Location> buildMultiModalPath(Player player, Location target, TransportType transport) {
        if (target == null) {
            return List.of();
        }
        RoadGraph graph = graphService.getRoadGraph();
        return Pathfinder.buildMultiModalRoute(graph, player.getLocation(), target, transport);
    }

    public CompletableFuture<List<Location>> buildPathToAsync(Player player, Location target,
                                                             TransportType transport) {
        return CompletableFuture.supplyAsync(() -> buildPathTo(player, target, transport));
    }

    public CompletableFuture<List<Location>> buildMultiModalAsync(Player player, Location target,
                                                                  TransportType transport) {
        return CompletableFuture.supplyAsync(() -> buildMultiModalPath(player, target, transport));
    }
}
