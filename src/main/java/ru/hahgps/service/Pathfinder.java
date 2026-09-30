package ru.hahgps.service;
import ru.hahgps.model.GraphEdge;
import ru.hahgps.model.GraphNode;
import ru.hahgps.model.RoadGraph;
import org.bukkit.Location;
import java.util.*;
public class Pathfinder {

    public static List<GraphNode> findPath(RoadGraph graph, GraphNode from, GraphNode to) {
        if (from == null || to == null) {
            return Collections.emptyList();
        }
        if (from.equals(to)) {
            return Collections.singletonList(from);
        }

        Map<Integer, Double> gScore = new HashMap<>();
        Map<Integer, Double> fScore = new HashMap<>();
        Map<Integer, GraphNode> cameFrom = new HashMap<>();
        PriorityQueue<GraphNode> open = new PriorityQueue<>(
                Comparator.comparingDouble(n -> fScore.getOrDefault(n.getId(), Double.MAX_VALUE)));
        Set<Integer> closed = new HashSet<>();

        gScore.put(from.getId(), 0.0);
        fScore.put(from.getId(), heuristic(from, to));
        open.add(from);

        while (!open.isEmpty()) {
            GraphNode current = open.poll();
            if (current.getId() == to.getId()) {
                return reconstructPath(cameFrom, current);
            }
            if (closed.contains(current.getId())) {
                continue;
            }
            closed.add(current.getId());

            for (GraphEdge edge : graph.getEdges(current)) {
                GraphNode neighbor = edge.getTo();
                if (closed.contains(neighbor.getId())) {
                    continue;
                }
                double tentativeG = gScore.getOrDefault(current.getId(), Double.MAX_VALUE) + edge.getWeight();
                if (tentativeG < gScore.getOrDefault(neighbor.getId(), Double.MAX_VALUE)) {
                    cameFrom.put(neighbor.getId(), current);
                    gScore.put(neighbor.getId(), tentativeG);
                    fScore.put(neighbor.getId(), tentativeG + heuristic(neighbor, to));
                    open.add(neighbor);
                }
            }
        }
        return Collections.emptyList();
    }

    private static double heuristic(GraphNode a, GraphNode b) {
        return a.distanceTo(b);
    }

    private static List<GraphNode> reconstructPath(Map<Integer, GraphNode> cameFrom, GraphNode current) {
        List<GraphNode> path = new ArrayList<>();
        path.add(current);
        while (cameFrom.containsKey(current.getId())) {
            current = cameFrom.get(current.getId());
            path.add(current);
        }
        Collections.reverse(path);
        return path;
    }

    public static List<Location> buildRoute(RoadGraph graph, Location start, Location end) {
        List<Location> route = new ArrayList<>();
        if (start.getWorld() == null || end.getWorld() == null
                || !start.getWorld().equals(end.getWorld())) {
            route.add(start);
            route.add(end);
            return route;
        }

        if (graph.isEmpty()) {
            route.add(start);
            route.add(end);
            return route;
        }

        GraphNode startNode = graph.findNearestNode(start);
        GraphNode endNode = graph.findNearestNode(end);

        if (startNode == null || endNode == null) {
            route.add(start);
            route.add(end);
            return route;
        }

        List<GraphNode> path = findPath(graph, startNode, endNode);
        if (path.isEmpty()) {
            route.add(start);
            route.add(end);
            return route;
        }

        route.add(start);
        for (GraphNode node : path) {
            route.add(node.getLocation());
        }
        route.add(end);
        return route;
    }

    public static double routeLength(List<Location> route) {
        double length = 0.0;
        for (int i = 1; i < route.size(); i++) {
            Location a = route.get(i - 1);
            Location b = route.get(i);
            if (a.getWorld() != null && a.getWorld().equals(b.getWorld())) {
                length += a.distance(b);
            }
        }
        return length;
    }
}
