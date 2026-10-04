package ru.hahgps.service;
import ru.hahgps.model.GraphEdge;
import ru.hahgps.model.GraphNode;
import ru.hahgps.model.RoadGraph;
import ru.hahgps.model.TransportType;
import org.bukkit.Location;
import java.util.*;
public class Pathfinder {


    private static final int MAX_EXPANSIONS = 100_000;

    public static List<GraphNode> findPath(RoadGraph graph, GraphNode from, GraphNode to, TransportType transport) {
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
        fScore.put(from.getId(), heuristic(from, to, transport));
        open.add(from);

        int expansions = 0;
        while (!open.isEmpty()) {
            if (++expansions > MAX_EXPANSIONS) {

                return Collections.emptyList();
            }
            GraphNode current = open.poll();
            if (current.getId() == to.getId()) {
                return reconstructPath(cameFrom, current);
            }
            if (closed.contains(current.getId())) {
                continue;
            }
            closed.add(current.getId());

            for (GraphEdge edge : graph.getEdges(current, transport)) {
                GraphNode neighbor = edge.getTo();
                if (neighbor == null || closed.contains(neighbor.getId())) {
                    continue;
                }
                double speed = transport != null && transport.getSpeed() > 0 ? transport.getSpeed() : 1.0;
                double cost = edge.getWeight() / speed;
                double tentativeG = gScore.getOrDefault(current.getId(), Double.MAX_VALUE) + cost;
                if (tentativeG < gScore.getOrDefault(neighbor.getId(), Double.MAX_VALUE)) {
                    cameFrom.put(neighbor.getId(), current);
                    gScore.put(neighbor.getId(), tentativeG);
                    fScore.put(neighbor.getId(), tentativeG + heuristic(neighbor, to, transport));
                    open.add(neighbor);
                }
            }
        }
        return Collections.emptyList();
    }


    private static double heuristic(GraphNode a, GraphNode b, TransportType transport) {
        double distance = a.distanceTo(b);
        if (distance == Double.MAX_VALUE) {
            return 0.0;
        }
        double speed = transport != null && transport.getSpeed() > 0 ? transport.getSpeed() : 1.0;
        return distance / speed;
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

    public static List<Location> buildRoute(RoadGraph graph, Location start, Location end, TransportType transport) {
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

        GraphNode startNode = graph.findNearestNode(start, transport);
        GraphNode endNode = graph.findNearestNode(end, transport);

        if (startNode == null || endNode == null) {
            route.add(start);
            route.add(end);
            return route;
        }

        List<GraphNode> path = findPath(graph, startNode, endNode, transport);
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

    public static List<Location> buildMultiModalRoute(RoadGraph graph, Location start, Location end,
                                                      TransportType mainTransport) {
        List<Location> route = new ArrayList<>();
        route.add(start);

        if (start.getWorld() == null || end.getWorld() == null
                || !start.getWorld().equals(end.getWorld())) {
            route.add(end);
            return route;
        }

        GraphNode transitStart = graph.findNearestNode(start, mainTransport);
        GraphNode transitEnd = graph.findNearestNode(end, mainTransport);

        if (transitStart != null && !transitStart.getLocation().equals(start)) {
            List<Location> walkTo = buildRoute(graph, start, transitStart.getLocation(), TransportType.FOOT);
            route.addAll(walkTo.subList(1, walkTo.size()));
        }

        if (transitStart != null && transitEnd != null) {
            List<Location> transitPath = buildRoute(graph, transitStart.getLocation(),
                    transitEnd.getLocation(), mainTransport);
            route.addAll(transitPath.subList(1, transitPath.size()));
        }

        if (transitEnd != null && !transitEnd.getLocation().equals(end)) {
            List<Location> walkFrom = buildRoute(graph, transitEnd.getLocation(), end, TransportType.FOOT);
            route.addAll(walkFrom.subList(1, walkFrom.size()));
        }

        if (route.size() == 1) {
            route.add(end);
        }
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
