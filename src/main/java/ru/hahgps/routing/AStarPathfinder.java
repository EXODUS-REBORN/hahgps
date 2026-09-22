package ru.hahgps.routing;

import ru.hahgps.config.PluginConfig;
import ru.hahgps.model.Edge;
import ru.hahgps.model.Graph;
import ru.hahgps.model.Node;
import ru.hahgps.model.PathResult;
import ru.hahgps.model.TransportType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class AStarPathfinder {

    private final PluginConfig config;

    public AStarPathfinder(PluginConfig config) {
        this.config = config;
    }

    public PathResult find(Graph graph, String startId, String goalId, TransportType transport) {
        Node start = graph.node(startId);
        Node goal = graph.node(goalId);
        if (start == null || goal == null) {
            return null;
        }
        if (startId.equals(goalId)) {
            return new PathResult(List.of(startId), List.of(), 0.0D);
        }
        if (!start.vec().sameWorld(goal.vec())) {
            return null;
        }

        double layerSpeed = Math.max(0.1D, config.speed(transport));
        double maxSpeed = graph.maxEdgeSpeed(transport, layerSpeed);
        Map<String, Double> gScore = new HashMap<>();
        Map<String, String> cameFrom = new HashMap<>();
        Map<String, Edge> cameFromEdge = new HashMap<>();
        Set<String> closed = new HashSet<>();
        PriorityQueue<QueueEntry> open = new PriorityQueue<>(Comparator.comparingDouble(entry -> entry.f));

        gScore.put(startId, 0.0D);
        open.add(new QueueEntry(startId, heuristic(start, goal, maxSpeed)));

        int iterations = 0;
        while (!open.isEmpty()) {
            if (++iterations > config.maxIterations()) {
                return null;
            }
            QueueEntry current = open.poll();
            if (!closed.add(current.nodeId)) {
                continue;
            }
            if (current.nodeId.equals(goalId)) {
                return reconstruct(graph, cameFrom, cameFromEdge, startId, goalId);
            }
            double currentScore = gScore.getOrDefault(current.nodeId, Double.MAX_VALUE);
            for (Edge edge : graph.edgesOf(current.nodeId)) {
                if (!edge.canTraverse(current.nodeId, transport)) {
                    continue;
                }
                String nextId = edge.other(current.nodeId);
                if (closed.contains(nextId)) {
                    continue;
                }
                double edgeSpeed = Math.max(0.1D, edge.speedFor(layerSpeed));
                double cost = edge.getLength() / edgeSpeed * edge.getWeight() + config.nodePenalty();
                double tentative = currentScore + cost;
                if (tentative < gScore.getOrDefault(nextId, Double.MAX_VALUE) - 1.0E-9D) {
                    gScore.put(nextId, tentative);
                    cameFrom.put(nextId, current.nodeId);
                    cameFromEdge.put(nextId, edge);
                    Node next = graph.node(nextId);
                    double h = next == null ? 0.0D : heuristic(next, goal, maxSpeed);
                    open.add(new QueueEntry(nextId, tentative + h));
                }
            }
        }
        return null;
    }

    private double heuristic(Node from, Node to, double speed) {
        return from.vec().distanceTo(to.vec()) / Math.max(0.1D, speed) * Math.max(0.05D, config.heuristicFactor());
    }

    private PathResult reconstruct(Graph graph, Map<String, String> cameFrom, Map<String, Edge> cameFromEdge,
                                   String startId, String goalId) {
        LinkedList<String> nodes = new LinkedList<>();
        LinkedList<Edge> edges = new LinkedList<>();
        double distance = 0.0D;

        String cursor = goalId;
        int guard = 0;
        while (cursor != null && !cursor.equals(startId) && guard++ < 1_000_000) {
            nodes.addFirst(cursor);
            Edge edge = cameFromEdge.get(cursor);
            if (edge != null) {
                edges.addFirst(edge);
                distance += edge.getLength();
            }
            cursor = cameFrom.get(cursor);
        }
        nodes.addFirst(startId);
        return new PathResult(new ArrayList<>(nodes), new ArrayList<>(edges), distance);
    }

    private static final class QueueEntry {
        private final String nodeId;
        private final double f;

        private QueueEntry(String nodeId, double f) {
            this.nodeId = nodeId;
            this.f = f;
        }
    }
}
