package ru.hahgps.service;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import ru.hahgps.model.TransportType;
import ru.hahgps.util.LocationUtil;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class WorldGraph {
    private static final int CELL = 4;
    private static final int MARGIN = 24;
    private static final int MAX_DISTANCE = 512;

    private static final int NEAREST_SEARCH_RADIUS = 10;
    private static final AtomicBoolean WARNED_OFF_THREAD = new AtomicBoolean();

    public static class PathResult {
        public final List<Location> path;
        public final double distance;
        public final boolean found;
        public PathResult(List<Location> path, double distance, boolean found) {
            this.path = path;
            this.distance = distance;
            this.found = found;
        }
    }
    private static class Node {
        final int gx, gz;
        final Location location;
        final boolean road;
        final boolean walkable;

        Node(int gx, int gz, Location location, boolean road, boolean walkable) {
            this.gx = gx;
            this.gz = gz;
            this.location = location;
            this.road = road;
            this.walkable = walkable;
        }
    }

    public PathResult findPath(World world, Location start, Location end, TransportType transport) {
        if (start == null || end == null
                || start.getWorld() == null || end.getWorld() == null) {
            return straightLine(start, end);
        }
        if (!start.getWorld().equals(end.getWorld())) {
            return straightLine(start, end);
        }

        if (!Bukkit.isPrimaryThread()) {
            if (WARNED_OFF_THREAD.compareAndSet(false, true)) {
                Bukkit.getLogger().warning(
                        "HahGps: WorldGraph.findPath вызван не с главного потока — возвращается прямая линия.");
            }
            return straightLine(start, end);
        }
        World w = start.getWorld();
        if (start.distance(end) > MAX_DISTANCE) {
            return straightLine(start, end);
        }

        int minX = (int) Math.floor(Math.min(start.getX(), end.getX())) - MARGIN;
        int maxX = (int) Math.ceil(Math.max(start.getX(), end.getX())) + MARGIN;
        int minZ = (int) Math.floor(Math.min(start.getZ(), end.getZ())) - MARGIN;
        int maxZ = (int) Math.ceil(Math.max(start.getZ(), end.getZ())) + MARGIN;

        int minGX = minX / CELL;
        int maxGX = maxX / CELL;
        int minGZ = minZ / CELL;
        int maxGZ = maxZ / CELL;

        Map<Long, Node> nodes = new HashMap<>();
        for (int gx = minGX; gx <= maxGX; gx++) {
            for (int gz = minGZ; gz <= maxGZ; gz++) {
                int wx = gx * CELL + CELL / 2;
                int wz = gz * CELL + CELL / 2;
                Node node = buildNode(w, gx, gz, wx, wz);
                if (node != null) {
                    nodes.put(key(gx, gz), node);
                }
            }
        }

        Node startNode = nearestNode(nodes, start);
        Node endNode = nearestNode(nodes, end);

        if (startNode == null || endNode == null) {
            return straightLine(start, end);
        }

        List<Node> pathNodes = astar(nodes, startNode, endNode, transport);
        if (pathNodes == null) {
            return straightLine(start, end);
        }

        List<Location> path = new ArrayList<>();
        path.add(start);
        for (Node node : pathNodes) {
            path.add(node.location);
        }
        path.add(end);

        path = simplify(path);

        double distance = 0;
        for (int i = 1; i < path.size(); i++) {
            distance += LocationUtil.distance(path.get(i - 1), path.get(i));
        }
        if (!Double.isFinite(distance) || distance == Double.MAX_VALUE) {
            distance = 0;
        }
        return new PathResult(path, distance, true);
    }

    private Node buildNode(World world, int gx, int gz, int wx, int wz) {

        if (!world.isChunkLoaded(wx >> 4, wz >> 4)) {
            return null;
        }
        int y = world.getHighestBlockYAt(wx, wz);
        int minY = world.getMinHeight();

        Material feetMaterial = world.getBlockAt(wx, y, wz).getType();
        if (!feetMaterial.isAir()) {
            y++;
            feetMaterial = world.getBlockAt(wx, y, wz).getType();
        }
        if (y - 1 < minY || !feetMaterial.isAir()) {
            return null;
        }
        Material ground = world.getBlockAt(wx, y - 1, wz).getType();
        Material head = world.getBlockAt(wx, y + 1, wz).getType();

        boolean walkable = ground.isSolid()
                && !head.isSolid()
                && feetMaterial != Material.WATER && feetMaterial != Material.LAVA
                && head != Material.WATER && head != Material.LAVA;

        if (!walkable) {
            return null;
        }

        boolean road = isRoad(ground);
        return new Node(gx, gz, new Location(world, wx, y, wz), road, walkable);
    }

    private boolean isRoad(Material material) {
        return switch (material) {
            case GRAVEL, STONE, STONE_BRICKS, COBBLESTONE, MOSSY_COBBLESTONE,
                 BRICKS, SANDSTONE, SANDSTONE_SLAB, STONE_SLAB,
                 STONE_BRICK_SLAB, COBBLESTONE_SLAB, DIRT_PATH, PODZOL,
                 FARMLAND, GRASS_BLOCK, WHITE_CONCRETE, GRAY_CONCRETE,
                 BLACK_CONCRETE, RED_CONCRETE, BLUE_CONCRETE -> true;
            default -> false;
        };
    }

    private Node nearestNode(Map<Long, Node> nodes, Location loc) {
        int gx = (int) Math.floor(loc.getX() / CELL);
        int gz = (int) Math.floor(loc.getZ() / CELL);

        Node best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -NEAREST_SEARCH_RADIUS; dx <= NEAREST_SEARCH_RADIUS; dx++) {
            for (int dz = -NEAREST_SEARCH_RADIUS; dz <= NEAREST_SEARCH_RADIUS; dz++) {
                Node node = nodes.get(key(gx + dx, gz + dz));
                if (node == null) {
                    continue;
                }
                double d = LocationUtil.distance(node.location, loc);
                if (d < bestDist) {
                    bestDist = d;
                    best = node;
                }
            }
        }
        return best;
    }

    private List<Node> astar(Map<Long, Node> nodes, Node start, Node end, TransportType transport) {
        Map<Node, Double> gScore = new HashMap<>();
        Map<Node, Double> fScore = new HashMap<>();
        Map<Node, Node> cameFrom = new HashMap<>();
        Set<Node> closed = new HashSet<>();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> fScore.getOrDefault(n, Double.MAX_VALUE)));

        gScore.put(start, 0.0);
        fScore.put(start, heuristic(start, end, transport));
        open.add(start);



        int expansions = 0;
        final int maxExpansions = 8 * (nodes.size() + 1) + 1000;
        while (!open.isEmpty()) {
            if (++expansions > maxExpansions) {
                return null;
            }
            Node current = open.poll();
            if (current == end) {
                return reconstruct(cameFrom, current);
            }
            if (closed.contains(current)) {
                continue;
            }
            closed.add(current);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    Node neighbor = nodes.get(key(current.gx + dx, current.gz + dz));
                    if (neighbor == null || closed.contains(neighbor)) {
                        continue;
                    }

                    if (!isCompatible(neighbor, transport)) {
                        continue;
                    }
                    double cost = edgeCost(current, neighbor, transport);
                    double tentativeG = gScore.getOrDefault(current, Double.MAX_VALUE) + cost;
                    if (tentativeG < gScore.getOrDefault(neighbor, Double.MAX_VALUE)) {
                        cameFrom.put(neighbor, current);
                        gScore.put(neighbor, tentativeG);
                        fScore.put(neighbor, tentativeG + heuristic(neighbor, end, transport));
                        open.add(neighbor);
                    }
                }
            }
        }
        return null;
    }

    private boolean isCompatible(Node node, TransportType transport) {
        if (transport == TransportType.CAR) {
            return node.road;
        }
        return node.walkable;
    }

    private double edgeCost(Node a, Node b, TransportType transport) {
        double dist = LocationUtil.distance(a.location, b.location);
        if (dist == Double.MAX_VALUE) {
            return Double.MAX_VALUE;
        }
        if (transport == TransportType.CAR) {
            return dist;
        }

        boolean aPath = !a.road;
        boolean bPath = !b.road;
        if (aPath && bPath) {
            return dist * 0.8;
        } else if (a.road && b.road) {
            return dist * 1.3;
        }
        return dist * 1.0;
    }


    private double heuristic(Node a, Node b, TransportType transport) {
        double distance = LocationUtil.distance(a.location, b.location);
        if (distance == Double.MAX_VALUE) {
            return 0.0;
        }
        return distance * (transport == TransportType.CAR ? 1.0 : 0.8);
    }

    private List<Node> reconstruct(Map<Node, Node> cameFrom, Node current) {
        List<Node> path = new ArrayList<>();
        path.add(current);
        while (cameFrom.containsKey(current)) {
            current = cameFrom.get(current);
            path.add(current);
        }
        Collections.reverse(path);
        return path;
    }

    private List<Location> simplify(List<Location> path) {
        if (path.size() <= 2) {
            return path;
        }
        List<Location> result = new ArrayList<>();
        result.add(path.get(0));
        for (int i = 1; i < path.size() - 1; i++) {
            Location prev = path.get(i - 1);
            Location curr = path.get(i);
            Location next = path.get(i + 1);

            if (!isCollinear(prev, curr, next)) {
                result.add(curr);
            }
        }
        result.add(path.get(path.size() - 1));
        return result;
    }

    private boolean isCollinear(Location a, Location b, Location c) {
        double dx1 = b.getX() - a.getX();
        double dz1 = b.getZ() - a.getZ();
        double dx2 = c.getX() - b.getX();
        double dz2 = c.getZ() - b.getZ();
        double cross = dx1 * dz2 - dz1 * dx2;
        if (Math.abs(cross) >= 0.001) {
            return false;
        }
        return dx1 * dx2 + dz1 * dz2 > 0;
    }

    private PathResult straightLine(Location start, Location end) {
        List<Location> path = new ArrayList<>();
        if (start != null) {
            path.add(start);
        }
        if (end != null) {
            path.add(end);
        }


        double distance = (start != null && end != null) ? LocationUtil.distance(start, end) : 0;
        if (!Double.isFinite(distance) || distance == Double.MAX_VALUE) {
            distance = 0;
        }
        return new PathResult(path, distance, false);
    }

    private static long key(int gx, int gz) {
        return (((long) gx) << 32) | (gz & 0xffffffffL);
    }
}
