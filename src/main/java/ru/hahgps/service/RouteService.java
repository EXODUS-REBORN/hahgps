package ru.hahgps.service;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import ru.hahgps.model.TransportType;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class RouteService {

    private final WorldGraph worldGraph = new WorldGraph();

    public WorldGraph.PathResult buildPathTo(Player player, Location target, TransportType transport) {
        if (target == null) {
            return new WorldGraph.PathResult(List.of(), 0, false);
        }
        return worldGraph.findPath(player.getWorld(), player.getLocation(), target, transport);
    }

    public CompletableFuture<WorldGraph.PathResult> buildPathToAsync(Player player, Location target,
                                                                     TransportType transport) {
        return CompletableFuture.supplyAsync(() -> buildPathTo(player, target, transport));
    }
}
