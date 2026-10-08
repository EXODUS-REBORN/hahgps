package ru.hahgps.service;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import ru.hahgps.model.GraphEdge;
import ru.hahgps.model.GraphNode;
import ru.hahgps.model.RoadGraph;
import ru.hahgps.model.TransportType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VisualizationService {


    private static final double RADIUS = 64.0;
    private static final double RADIUS_SQ = RADIUS * RADIUS;

    private static final double PARTICLE_STEP = 2.0;

    private static final int PARTICLE_BUDGET = 600;

    private final GraphService graphService;
    private final Map<UUID, Set<TransportType>> activeVisualizations = new ConcurrentHashMap<>();


    private final Map<TransportType, List<GraphEdge>> edgeCache = new EnumMap<>(TransportType.class);
    private final Map<TransportType, Particle.DustOptions> dustCache = new EnumMap<>(TransportType.class);
    private int cachedRevision = -1;


    private final double[] clipBuffer = new double[2];

    private final List<GraphEdge> candidates = new ArrayList<>();

    public VisualizationService(GraphService graphService) {
        this.graphService = graphService;
    }

    public void enableVisualization(Player player, TransportType type) {
        Set<TransportType> types = activeVisualizations.computeIfAbsent(player.getUniqueId(),
                k -> EnumSet.noneOf(TransportType.class));
        // Тот же монитор, что и в tick()/disableVisualization(): иначе тумблер
        // и отрисовка могли править набор одновременно.
        synchronized (types) {
            types.add(type);
        }
    }

    public void disableVisualization(Player player, TransportType type) {
        Set<TransportType> types = activeVisualizations.get(player.getUniqueId());
        if (types != null) {
            synchronized (types) {
                types.remove(type);
            }
            if (types.isEmpty()) {
                activeVisualizations.remove(player.getUniqueId(), types);
            }
        }
    }

    public void disableAll(Player player) {
        activeVisualizations.remove(player.getUniqueId());
    }

    public boolean isVisualizing(Player player, TransportType type) {
        Set<TransportType> types = activeVisualizations.get(player.getUniqueId());
        if (types == null) {
            return false;
        }
        synchronized (types) {
            return types.contains(type);
        }
    }

    public void tick() {
        refreshCacheIfNeeded();
        if (activeVisualizations.isEmpty()) {
            return;
        }

        List<UUID> offline = null;
        for (Map.Entry<UUID, Set<TransportType>> entry : activeVisualizations.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {

                if (offline == null) {
                    offline = new ArrayList<>(1);
                }
                offline.add(entry.getKey());
                continue;
            }
            Set<TransportType> types = entry.getValue();
            synchronized (types) {
                for (TransportType type : types) {
                    renderForType(player, type);
                }
            }
        }
        if (offline != null) {
            for (UUID id : offline) {
                activeVisualizations.remove(id);
            }
        }
    }


    private void refreshCacheIfNeeded() {
        int revision = graphService.getRevision();
        if (revision == cachedRevision) {
            return;
        }
        RoadGraph graph = graphService.graph();
        List<GraphNode> nodes = graph.getNodes();
        // Один проход по рёбрам вместо пяти (types × nodes): getEdges(node,
        // type) аллоцирует список на каждый вызов, и на 10k узлов это были
        // десятки тысяч временных объектов в главном потоке.
        Map<TransportType, List<GraphEdge>> fresh = new EnumMap<>(TransportType.class);
        for (TransportType type : TransportType.values()) {
            fresh.put(type, new ArrayList<>());
        }
        for (GraphNode node : nodes) {
            for (GraphEdge edge : graph.getEdges(node)) {
                if (edge.getFrom() == null || edge.getTo() == null
                        || edge.getFrom().getId() >= edge.getTo().getId()) {
                    continue;
                }
                Set<TransportType> allowed = edge.getAllowedTransport();
                if (allowed == null) {
                    continue;
                }
                for (TransportType type : allowed) {
                    List<GraphEdge> bucket = fresh.get(type);
                    if (bucket != null) {
                        bucket.add(edge);
                    }
                }
            }
        }
        edgeCache.clear();
        for (Map.Entry<TransportType, List<GraphEdge>> entry : fresh.entrySet()) {
            edgeCache.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        cachedRevision = revision;
    }

    private void renderForType(Player player, TransportType type) {
        List<GraphEdge> edges = edgeCache.get(type);
        if (edges == null || edges.isEmpty()) {
            return;
        }
        Particle.DustOptions dust = dustCache.computeIfAbsent(type, VisualizationService::createDust);
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        org.bukkit.Location playerLoc = player.getLocation();
        double px = playerLoc.getX();
        double pz = playerLoc.getZ();
        double py = playerLoc.getY();



        candidates.clear();
        for (GraphEdge edge : edges) {
            org.bukkit.Location from = edge.getFrom().getLocation();
            org.bukkit.Location to = edge.getTo().getLocation();
            if (from == null || to == null || from.getWorld() == null || to.getWorld() == null) {
                continue;
            }
            if (!world.equals(from.getWorld()) || !world.equals(to.getWorld())) {
                continue;
            }
            candidates.add(edge);
        }
        candidates.sort((e1, e2) -> Double.compare(
                nearestDistSq(e1, px, pz), nearestDistSq(e2, px, pz)));

        int budget = PARTICLE_BUDGET;
        for (GraphEdge edge : candidates) {
            if (budget <= 0) {
                break;
            }
            org.bukkit.Location from = edge.getFrom().getLocation();
            org.bukkit.Location to = edge.getTo().getLocation();
            if (!clipSegment(from.getX(), from.getZ(), to.getX(), to.getZ(), px, pz)) {
                continue;
            }
            double t0 = clipBuffer[0];
            double t1 = clipBuffer[1];
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double dy = to.getY() - from.getY();
            double ax = from.getX() + t0 * dx;
            double az = from.getZ() + t0 * dz;
            double bx = from.getX() + t1 * dx;
            double bz = from.getZ() + t1 * dz;

            double length = Math.hypot(bx - ax, bz - az);
            int steps = Math.max(1, (int) (length / PARTICLE_STEP));
            if (steps > budget) {

                steps = budget;
            }
            budget -= steps;

            double startY = from.getY() + t0 * dy;
            double endY = from.getY() + t1 * dy;
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                double y = startY + (endY - startY) * t + 1.0;
                if (Math.abs(y - py) > RADIUS) {
                    continue;
                }
                player.spawnParticle(Particle.DUST,
                        ax + (bx - ax) * t, y, az + (bz - az) * t, 1, dust);
            }
        }
    }

    private static Particle.DustOptions createDust(TransportType type) {
        int color = type.getParticleColor();
        return new Particle.DustOptions(org.bukkit.Color.fromRGB(
                (color >> 16) & 0xFF,
                (color >> 8) & 0xFF,
                color & 0xFF), 1.0f);
    }


    private static double nearestDistSq(GraphEdge edge, double px, double pz) {
        org.bukkit.Location from = edge.getFrom().getLocation();
        org.bukkit.Location to = edge.getTo().getLocation();
        double fx = from.getX() - px;
        double fz = from.getZ() - pz;
        double tx = to.getX() - px;
        double tz = to.getZ() - pz;
        return Math.min(fx * fx + fz * fz, tx * tx + tz * tz);
    }


    private boolean clipSegment(double ax, double az, double bx, double bz,
                                double cx, double cz) {
        double dx = bx - ax;
        double dz = bz - az;
        double fx = ax - cx;
        double fz = az - cz;
        double a = dx * dx + dz * dz;
        if (a < 1e-9) {

            if (fx * fx + fz * fz <= RADIUS_SQ) {
                clipBuffer[0] = 0.0;
                clipBuffer[1] = 1.0;
                return true;
            }
            return false;
        }
        double b = 2.0 * (fx * dx + fz * dz);
        double c = fx * fx + fz * fz - RADIUS_SQ;
        if (c <= 0.0) {

            double disc = b * b - 4.0 * a * c;
            double tExit = disc >= 0.0 ? (-b + Math.sqrt(disc)) / (2.0 * a) : 1.0;
            clipBuffer[0] = 0.0;
            clipBuffer[1] = Math.min(1.0, Math.max(0.0, tExit));
            return clipBuffer[1] > 0.0;
        }
        double disc = b * b - 4.0 * a * c;
        if (disc < 0.0) {
            return false;
        }
        double sqrt = Math.sqrt(disc);
        double t1 = (-b - sqrt) / (2.0 * a);
        double t2 = (-b + sqrt) / (2.0 * a);
        double lo = Math.max(0.0, Math.min(t1, t2));
        double hi = Math.min(1.0, Math.max(t1, t2));
        if (hi <= lo) {
            return false;
        }
        clipBuffer[0] = lo;
        clipBuffer[1] = hi;
        return true;
    }
}
