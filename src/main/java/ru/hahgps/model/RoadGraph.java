package ru.hahgps.model;
import org.bukkit.Location;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Граф дорог. Наружу отдаётся только для чтения: изменяющие методы вызываются
 * исключительно на приватной копии, которую публикует {@code GraphService}.
 * Списки смежности возвращаются как неизменяемые представления, поэтому
 * опубликованный граф безопасно читать из другого потока.
 */
public class RoadGraph {

    private final List<GraphNode> nodes = new ArrayList<>();
    private final Map<Integer, List<GraphEdge>> adjacency = new HashMap<>();

    public void addNode(GraphNode node) {
        nodes.add(node);
        adjacency.putIfAbsent(node.getId(), new ArrayList<>());
    }

    public void addEdge(GraphNode from, GraphNode to, Set<TransportType> allowedTransport) {
        if (from == null || to == null || allowedTransport == null || allowedTransport.isEmpty()) {
            return;
        }
        double weight = from.distanceTo(to);
        if (weight == Double.MAX_VALUE) {
            return;
        }
        Set<TransportType> types = EnumSet.copyOf(allowedTransport);
        adjacency.computeIfAbsent(from.getId(), k -> new ArrayList<>())
                .add(new GraphEdge(from, to, weight, types));
        adjacency.computeIfAbsent(to.getId(), k -> new ArrayList<>())
                .add(new GraphEdge(to, from, weight, types));
    }

    /**
     * Заменить ребро между парой узлов: снять существующие и добавить с
     * указанными типами. Пустой набор типов = снять ребро полностью.
     */
    public void replaceEdge(GraphNode from, GraphNode to, Set<TransportType> allowedTransport) {
        removeEdge(from, to);
        if (allowedTransport != null && !allowedTransport.isEmpty()) {
            addEdge(from, to, allowedTransport);
        }
    }

    public void removeEdge(GraphNode from, GraphNode to) {
        // Снимаем только по одному ребру в каждую сторону: между парой узлов
        // может проходить несколько физических маршрутов, и removeIf по
        // предикателю убирал бы их все разом.
        removeFirst(from.getId(), to);
        removeFirst(to.getId(), from);
    }

    private void removeFirst(int ownerId, GraphNode target) {
        List<GraphEdge> edges = adjacency.get(ownerId);
        if (edges == null) {
            return;
        }
        for (int i = 0; i < edges.size(); i++) {
            if (edges.get(i).getTo().equals(target)) {
                edges.remove(i);
                return;
            }
        }
    }

    /** Копия для правки: узлы общие (неизменяемы), списки смежности — свои. */
    public RoadGraph copy() {
        RoadGraph out = new RoadGraph();
        Set<Integer> copied = new HashSet<>();
        for (Map.Entry<Integer, List<GraphEdge>> entry : adjacency.entrySet()) {
            out.adjacency.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            copied.add(entry.getKey());
        }
        for (GraphNode node : nodes) {
            out.nodes.add(node);
            if (!copied.contains(node.getId())) {
                out.adjacency.put(node.getId(), new ArrayList<>());
            }
        }
        return out;
    }

    public List<GraphNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<GraphEdge> getEdges(GraphNode node) {
        return Collections.unmodifiableList(adjacency.getOrDefault(node.getId(), Collections.emptyList()));
    }

    public List<GraphEdge> getEdges(GraphNode node, TransportType type) {
        List<GraphEdge> source = adjacency.getOrDefault(node.getId(), Collections.emptyList());
        if (type == null) {
            return Collections.unmodifiableList(source);
        }
        List<GraphEdge> result = new ArrayList<>();
        for (GraphEdge edge : source) {
            if (edge.allows(type)) {
                result.add(edge);
            }
        }
        return result;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    public int size() {
        return nodes.size();
    }

    public GraphNode findNearestNode(Location location, TransportType type) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        GraphNode nearest = null;
        double min = Double.MAX_VALUE;
        for (GraphNode node : nodes) {
            Location nodeLoc = node.getLocation();
            if (nodeLoc == null || nodeLoc.getWorld() == null
                    || !ru.hahgps.util.LocationUtil.sameWorld(nodeLoc.getWorld(), location.getWorld())) {
                continue;
            }
            if (type != null && !hasEdgeFor(node, type)) {
                continue;
            }
            double d = ru.hahgps.util.LocationUtil.distance(nodeLoc, location);
            if (d < min) {
                min = d;
                nearest = node;
            }
        }
        return nearest;
    }

    private boolean hasEdgeFor(GraphNode node, TransportType type) {
        for (GraphEdge edge : adjacency.getOrDefault(node.getId(), Collections.emptyList())) {
            if (edge.allows(type)) {
                return true;
            }
        }
        return false;
    }

    public void clear() {
        nodes.clear();
        adjacency.clear();
    }
}
