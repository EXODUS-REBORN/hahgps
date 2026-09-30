package ru.hahgps.service;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import ru.hahgps.model.GraphEdge;
import ru.hahgps.model.GraphNode;
import ru.hahgps.model.TransportType;

import java.util.*;

public class VisualizationService {

    private final GraphService graphService;
    private final Map<UUID, Set<TransportType>> activeVisualizations = new HashMap<>();

    public VisualizationService(GraphService graphService) {
        this.graphService = graphService;
    }

    public void enableVisualization(Player player, TransportType type) {
        activeVisualizations.computeIfAbsent(player.getUniqueId(), k -> EnumSet.noneOf(TransportType.class))
                .add(type);
    }

    public void disableVisualization(Player player, TransportType type) {
        Set<TransportType> types = activeVisualizations.get(player.getUniqueId());
        if (types != null) {
            types.remove(type);
            if (types.isEmpty()) {
                activeVisualizations.remove(player.getUniqueId());
            }
        }
    }

    public void disableAll(Player player) {
        activeVisualizations.remove(player.getUniqueId());
    }

    public boolean isVisualizing(Player player, TransportType type) {
        Set<TransportType> types = activeVisualizations.get(player.getUniqueId());
        return types != null && types.contains(type);
    }

    public void tick() {
        for (Map.Entry<UUID, Set<TransportType>> entry : activeVisualizations.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }
            for (TransportType type : entry.getValue()) {
                renderForType(player, type);
            }
        }
    }

    private void renderForType(Player player, TransportType type) {
        for (GraphNode node : graphService.getNodes()) {
            Location loc = node.getLocation();
            if (loc == null || loc.getWorld() == null || !loc.getWorld().equals(player.getWorld())) {
                continue;
            }
            if (loc.distance(player.getLocation()) > 64) {
                continue;
            }
            for (GraphEdge edge : graphService.getRoadGraph().getEdges(node, type)) {
                renderEdge(player, edge, type);
            }
        }
    }

    private void renderEdge(Player player, GraphEdge edge, TransportType type) {
        Location from = edge.getFrom().getLocation();
        Location to = edge.getTo().getLocation();
        if (from == null || to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return;
        }
        double distance = from.distance(to);
        int steps = Math.max(1, (int) (distance / 2));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = from.getX() + (to.getX() - from.getX()) * t;
            double y = from.getY() + (to.getY() - from.getY()) * t;
            double z = from.getZ() + (to.getZ() - from.getZ()) * t;
            Location particleLoc = new Location(from.getWorld(), x, y + 1, z);
            player.spawnParticle(Particle.DUST, particleLoc, 1,
                    new Particle.DustOptions(org.bukkit.Color.fromRGB(
                            (type.getParticleColor() >> 16) & 0xFF,
                            (type.getParticleColor() >> 8) & 0xFF,
                            type.getParticleColor() & 0xFF), 1.0f));
        }
    }
}
