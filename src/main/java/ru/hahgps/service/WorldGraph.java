package ru.hahgps.service;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import ru.hahgps.model.TransportType;

import java.util.*;

/**
 * Процедурный граф мира для транспортно-зависимой прокладки маршрута.
 *
 * Строит сетку узлов между игроком и целью. Каждое ребро тегируется
 * допустимыми видами транспорта на основе типа поверхности:
 *  - FOOT: проходит по любой проходимой поверхности, предпочитая тропинки;
 *  - CAR:  проходит только по «дорожным» материалам (гравий, камень, бетон и т.п.).
 */
public class WorldGraph {

    // Размер ячейки сетки в блоках
    private static final int CELL = 4;
    // Отступ вокруг старта/финиша
    private static final int MARGIN = 24;
    // Максимальное расстояние поиска (блоки) — защита от слишком большого графа
    private static final int MAX_DISTANCE = 512;

    /**
     * Результат поиска пути.
     */
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

    /**
     * Узел графа.
     */
    private static class Node {
        final int gx, gz;
        final Location location;
        final boolean road;      // дорожная поверхность
        final boolean walkable;  // проходимый

        Node(int gx, int gz, Location location, boolean road, boolean walkable) {
            this.gx = gx;
            this.gz = gz;
            this.location = location;
            this.road = road;
            this.walkable = walkable;
        }
    }

    /**
     * Найти путь от start до end с учётом транспорта.
     */
    public PathResult findPath(World world, Location start, Location end, TransportType transport) {
        if (start == null || end == null || world == null) {
            return straightLine(start, end);
        }
        if (!start.getWorld().equals(end.getWorld())) {
            return straightLine(start, end);
        }
        if (start.distance(end) > MAX_DISTANCE) {
            return straightLine(start, end);
        }

        // Границы области
        int minX = (int) Math.floor(Math.min(start.getX(), end.getX())) - MARGIN;
        int maxX = (int) Math.ceil(Math.max(start.getX(), end.getX())) + MARGIN;
        int minZ = (int) Math.floor(Math.min(start.getZ(), end.getZ())) - MARGIN;
        int maxZ = (int) Math.ceil(Math.max(start.getZ(), end.getZ())) + MARGIN;

        int minGX = minX / CELL;
        int maxGX = maxX / CELL;
        int minGZ = minZ / CELL;
        int maxGZ = maxZ / CELL;

        // Строим узлы
        Map<Long, Node> nodes = new HashMap<>();
        for (int gx = minGX; gx <= maxGX; gx++) {
            for (int gz = minGZ; gz <= maxGZ; gz++) {
                int wx = gx * CELL + CELL / 2;
                int wz = gz * CELL + CELL / 2;
                Node node = buildNode(world, gx, gz, wx, wz);
                if (node != null) {
                    nodes.put(key(gx, gz), node);
                }
            }
        }

        // Ближайшие узлы к старту и финишу
        Node startNode = nearestNode(nodes, start);
        Node endNode = nearestNode(nodes, end);

        if (startNode == null || endNode == null) {
            return straightLine(start, end);
        }

        // A*
        List<Node> pathNodes = astar(nodes, startNode, endNode, transport);
        if (pathNodes == null) {
            // Путь не найден (например, авто без дорог) — fallback на прямую
            return straightLine(start, end);
        }

        // Собираем локации
        List<Location> path = new ArrayList<>();
        path.add(start);
        for (Node node : pathNodes) {
            path.add(node.location);
        }
        path.add(end);

        // Упрощаем путь (убираем коллинеарные точки)
        path = simplify(path);

        double distance = 0;
        for (int i = 1; i < path.size(); i++) {
            distance += path.get(i - 1).distance(path.get(i));
        }
        return new PathResult(path, distance, true);
    }

    /**
     * Построить узел в ячейке сетки.
     */
    private Node buildNode(World world, int gx, int gz, int wx, int wz) {
        int y = world.getHighestBlockYAt(wx, wz);
        Material ground = world.getBlockAt(wx, y - 1, wz).getType();
        Material feet = world.getBlockAt(wx, y, wz).getType();
        Material head = world.getBlockAt(wx, y + 1, wz).getType();

        // Проходимый: твёрдая поверхность под ногами и свободное пространство над головой
        boolean walkable = ground.isSolid()
                && !feet.isSolid() && !head.isSolid()
                && feet != Material.WATER && feet != Material.LAVA
                && head != Material.WATER && head != Material.LAVA;

        if (!walkable) {
            return null;
        }

        boolean road = isRoad(ground);
        return new Node(gx, gz, new Location(world, wx, y, wz), road, walkable);
    }

    /**
     * Является ли материал «дорожным» (проезжим для авто).
     */
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

    /**
     * Ближайший валидный узел к локации.
     */
    private Node nearestNode(Map<Long, Node> nodes, Location loc) {
        int gx = (int) Math.floor(loc.getX() / CELL);
        int gz = (int) Math.floor(loc.getZ() / CELL);
        // Ищем в радиусе 2 ячеек
        Node best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                Node node = nodes.get(key(gx + dx, gz + dz));
                if (node == null) {
                    continue;
                }
                double d = node.location.distance(loc);
                if (d < bestDist) {
                    bestDist = d;
                    best = node;
                }
            }
        }
        return best;
    }

    /**
     * A* по сетке узлов с фильтром по транспорту.
     */
    private List<Node> astar(Map<Long, Node> nodes, Node start, Node end, TransportType transport) {
        Map<Node, Double> gScore = new HashMap<>();
        Map<Node, Double> fScore = new HashMap<>();
        Map<Node, Node> cameFrom = new HashMap<>();
        Set<Node> closed = new HashSet<>();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> fScore.getOrDefault(n, Double.MAX_VALUE)));

        gScore.put(start, 0.0);
        fScore.put(start, heuristic(start, end));
        open.add(start);

        while (!open.isEmpty()) {
            Node current = open.poll();
            if (current == end) {
                return reconstruct(cameFrom, current);
            }
            if (closed.contains(current)) {
                continue;
            }
            closed.add(current);

            // 8 соседей
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    Node neighbor = nodes.get(key(current.gx + dx, current.gz + dz));
                    if (neighbor == null || closed.contains(neighbor)) {
                        continue;
                    }
                    // Фильтр по транспорту
                    if (!isCompatible(neighbor, transport)) {
                        continue;
                    }
                    double cost = edgeCost(current, neighbor, transport);
                    double tentativeG = gScore.getOrDefault(current, Double.MAX_VALUE) + cost;
                    if (tentativeG < gScore.getOrDefault(neighbor, Double.MAX_VALUE)) {
                        cameFrom.put(neighbor, current);
                        gScore.put(neighbor, tentativeG);
                        fScore.put(neighbor, tentativeG + heuristic(neighbor, end));
                        open.add(neighbor);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Совместимость узла с транспортом.
     */
    private boolean isCompatible(Node node, TransportType transport) {
        if (transport == TransportType.CAR) {
            return node.road;
        }
        return node.walkable;
    }

    /**
     * Стоимость ребра с учётом транспорта и типа поверхности.
     */
    private double edgeCost(Node a, Node b, TransportType transport) {
        double dist = a.location.distance(b.location);
        if (transport == TransportType.CAR) {
            return dist; // дороги — базовая стоимость
        }
        // Пешком: предпочитаем тропинки/мягкую поверхность, дороги — чуть дороже
        boolean aPath = !a.road;
        boolean bPath = !b.road;
        if (aPath && bPath) {
            return dist * 0.8; // тропинка
        } else if (a.road && b.road) {
            return dist * 1.3; // по дороге
        }
        return dist * 1.0;
    }

    private double heuristic(Node a, Node b) {
        return a.location.distance(b.location);
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

    /**
     * Упрощение пути: удаление коллинеарных точек.
     */
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
            // Если направление не меняется — пропускаем
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
        // Коллинеарны, если векторы сонаправлены (произведение ~ 0 и скалярное > 0)
        double cross = dx1 * dz2 - dz1 * dx2;
        return Math.abs(cross) < 0.001;
    }

    private PathResult straightLine(Location start, Location end) {
        List<Location> path = new ArrayList<>();
        if (start != null) {
            path.add(start);
        }
        if (end != null) {
            path.add(end);
        }
        double distance = (start != null && end != null) ? start.distance(end) : 0;
        return new PathResult(path, distance, false);
    }

    private static long key(int gx, int gz) {
        return (((long) gx) << 32) | (gz & 0xffffffffL);
    }
}
