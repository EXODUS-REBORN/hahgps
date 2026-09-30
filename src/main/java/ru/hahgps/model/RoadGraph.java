package ru.hahgps.model;
import org.bukkit.Location;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RoadGraph {

    private final List<GraphNode> nodes = new ArrayList<>();
    private final Map<Integer, List<GraphEdge>> adjacency = new HashMap<>();

    public void addNode(GraphNode node) {
        nodes.add(node);
        adjacency.putIfAbsent(node.getId(), new ArrayList<>());
    }

    public void addEdge(GraphNode from, GraphNode to) {
        double weight = from.distanceTo(to);
        if (weight == Double.MAX_VALUE) {
            return;
        }
        adjacency.computeIfAbsent(from.getId(), k -> new ArrayList<>())
                .add(new GraphEdge(from, to, weight));
        adjacency.computeIfAbsent(to.getId(), k -> new ArrayList<>())
                .add(new GraphEdge(to, from, weight));
    }

    public List<GraphNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<GraphEdge> getEdges(GraphNode node) {
        return Collections.unmodifiableList(adjacency.getOrDefault(node.getId(), Collections.emptyList()));
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

    public void clear() {
        nodes.clear();
        adjacency.clear();
    }
}
