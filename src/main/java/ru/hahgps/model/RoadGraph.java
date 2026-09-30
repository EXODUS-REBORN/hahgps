package ru.hahgps.model;
import org.bukkit.Location;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RoadGraph {

    private final List<GraphNode> nodes = new ArrayList<>();
    private final Map<Integer, List<GraphEdge>> adjacency = new HashMap<>();

    public void addNode(GraphNode node) {
        nodes.add(node);
        adjacency.putIfAbsent(node.getId(), new ArrayList<>());
    }

    public void addEdge(GraphNode from, GraphNode to, Set<TransportType> allowedTransport) {
        double weight = from.distanceTo(to);
        if (weight == Double.MAX_VALUE) {
            return;
        }
        adjacency.computeIfAbsent(from.getId(), k -> new ArrayList<>())
                .add(new GraphEdge(from, to, weight, allowedTransport));
        adjacency.computeIfAbsent(to.getId(), k -> new ArrayList<>())
                .add(new GraphEdge(to, from, weight, allowedTransport));
    }

    public void removeEdge(GraphNode from, GraphNode to) {
        adjacency.getOrDefault(from.getId(), new ArrayList<>())
                .removeIf(e -> e.getTo().equals(to));
        adjacency.getOrDefault(to.getId(), new ArrayList<>())
                .removeIf(e -> e.getFrom().equals(from));
    }

    public List<GraphNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<GraphEdge> getEdges(GraphNode node) {
        return Collections.unmodifiableList(adjacency.getOrDefault(node.getId(), Collections.emptyList()));
    }

    public List<GraphEdge> getEdges(GraphNode node, TransportType type) {
        List<GraphEdge> result = new ArrayList<>();
        for (GraphEdge edge : adjacency.getOrDefault(node.getId(), Collections.emptyList())) {
            if (edge.allows(type)) {
                result.add(edge);
            }
        }
        return result;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public GraphNode findNearestNode(Location location) {
        GraphNode nearest = null;
        double min = Double.MAX_VALUE;
        for (GraphNode node : nodes) {
            if (node.getLocation().getWorld() == null
                    || !node.getLocation().getWorld().equals(location.getWorld())) {
                continue;
            }
            double d = node.getLocation().distance(location);
            if (d < min) {
                min = d;
                nearest = node;
            }
        }
        return nearest;
    }

    public GraphNode findNearestNode(Location location, TransportType type) {
        GraphNode nearest = null;
        double min = Double.MAX_VALUE;
        for (GraphNode node : nodes) {
            if (node.getLocation().getWorld() == null
                    || !node.getLocation().getWorld().equals(location.getWorld())) {
                continue;
            }
            boolean hasEdge = false;
            for (GraphEdge edge : adjacency.getOrDefault(node.getId(), Collections.emptyList())) {
                if (edge.allows(type)) {
                    hasEdge = true;
                    break;
                }
            }
            if (!hasEdge) {
                continue;
            }
            double d = node.getLocation().distance(location);
            if (d < min) {
                min = d;
                nearest = node;
            }
        }
        return nearest;
    }

    public void clear() {
        nodes.clear();
        adjacency.clear();
    }
}
