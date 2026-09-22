package ru.hahgps.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Graph {

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Edge> edges = new LinkedHashMap<>();
    private final Map<String, List<Edge>> adjacency = new LinkedHashMap<>();
    private final Map<TransportType, Double> maxEdgeSpeed = new EnumMap<>(TransportType.class);
    private Runnable dirtyListener = () -> {
    };

    public void setDirtyListener(Runnable listener) {
        this.dirtyListener = listener == null ? () -> {
        } : listener;
    }

    private void dirty() {
        maxEdgeSpeed.clear();
        dirtyListener.run();
    }

    public void clear() {
        nodes.clear();
        edges.clear();
        adjacency.clear();
    }

    public Collection<Node> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public Collection<Edge> edges() {
        return Collections.unmodifiableCollection(edges.values());
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public Node node(String id) {
        return id == null ? null : nodes.get(id);
    }

    public Node findByRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        Node exact = nodes.get(ref);
        if (exact != null) {
            return exact;
        }
        String lower = ref.toLowerCase(Locale.ROOT);
        for (Node node : nodes.values()) {
            if (node.getId().toLowerCase(Locale.ROOT).equals(lower)) {
                return node;
            }
        }
        for (Node node : nodes.values()) {
            if (!node.getName().isBlank() && node.getName().toLowerCase(Locale.ROOT).equals(lower)) {
                return node;
            }
        }
        return null;
    }

    public Node addNode(Node node) {
        nodes.put(node.getId(), node);
        adjacency.computeIfAbsent(node.getId(), key -> new ArrayList<>());
        dirty();
        return node;
    }

    public int removeNode(String id) {
        if (nodes.remove(id) == null) {
            return 0;
        }
        List<Edge> toRemove = new ArrayList<>(adjacency.getOrDefault(id, List.of()));
        for (Edge edge : toRemove) {
            removeEdge(edge.getFrom(), edge.getTo(), false);
        }
        adjacency.remove(id);
        dirty();
        return toRemove.size();
    }

    public Edge edge(String a, String b) {
        if (a == null || b == null) {
            return null;
        }
        return edges.get(Edge.key(a, b));
    }

    public Edge addEdge(String a, String b, Collection<TransportType> types) {
        Edge existing = edge(a, b);
        if (existing != null) {
            existing.setAllowedTypes(types);
            refreshLength(existing);
            dirty();
            return existing;
        }
        Edge edge = new Edge(a, b, types);
        refreshLength(edge);
        edges.put(edge.key(), edge);
        index(edge);
        dirty();
        return edge;
    }

    public boolean removeEdge(String a, String b) {
        return removeEdge(a, b, true);
    }

    private boolean removeEdge(String a, String b, boolean markDirty) {
        Edge removed = edges.remove(Edge.key(a, b));
        if (removed == null) {
            return false;
        }
        List<Edge> listA = adjacency.get(removed.getFrom());
        if (listA != null) {
            listA.remove(removed);
        }
        List<Edge> listB = adjacency.get(removed.getTo());
        if (listB != null) {
            listB.remove(removed);
        }
        if (markDirty) {
            dirty();
        }
        return true;
    }

    private void index(Edge edge) {
        adjacency.computeIfAbsent(edge.getFrom(), key -> new ArrayList<>()).add(edge);
        if (!edge.getTo().equals(edge.getFrom())) {
            adjacency.computeIfAbsent(edge.getTo(), key -> new ArrayList<>()).add(edge);
        }
    }

    public List<Edge> edgesOf(String nodeId) {
        return adjacency.getOrDefault(nodeId, List.of());
    }

    public void markDirty() {
        dirty();
    }

    public void refreshAllLengths() {
        for (Edge edge : edges.values()) {
            refreshLength(edge);
        }
    }

    public void refreshLengthsOf(String nodeId) {
        for (Edge edge : edgesOf(nodeId)) {
            refreshLength(edge);
        }
    }

    private void refreshLength(Edge edge) {
        Node a = nodes.get(edge.getFrom());
        Node b = nodes.get(edge.getTo());
        edge.setLength(a == null || b == null ? 0.0D : a.vec().distanceTo(b.vec()));
    }

    public double distance(String a, String b) {
        Node first = nodes.get(a);
        Node second = nodes.get(b);
        return first == null || second == null ? Vec.UNREACHABLE : first.vec().distanceTo(second.vec());
    }

    public Node nearest(Vec point, TransportType required) {
        Node best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Node node : nodes.values()) {
            if (required != null && !node.allows(required)) {
                continue;
            }
            if (!node.vec().sameWorld(point)) {
                continue;
            }
            double distance = node.vec().distanceTo(point);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = node;
            }
        }
        return best;
    }

    public double maxEdgeSpeed(TransportType layer, double layerSpeed) {
        Double cached = maxEdgeSpeed.get(layer);
        if (cached == null) {
            double max = 0.0D;
            for (Edge edge : edges.values()) {
                if (edge.allows(layer) && edge.getSpeed() > max) {
                    max = edge.getSpeed();
                }
            }
            cached = max;
            maxEdgeSpeed.put(layer, cached);
        }
        return Math.max(layerSpeed, cached);
    }
}
