package ru.hahgps.service;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import ru.hahgps.model.GraphEdge;
import ru.hahgps.model.GraphNode;
import ru.hahgps.model.RoadGraph;
import ru.hahgps.model.TransportType;
import ru.hahgps.storage.DataService;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class GraphService {

    private final DataService dataService;
    private RoadGraph roadGraph = new RoadGraph();
    private int nextNodeId = 1;

    public GraphService(DataService dataService) {
        this.dataService = dataService;
    }

    public RoadGraph getRoadGraph() {
        return roadGraph;
    }

    public CompletableFuture<Void> loadGraph() {
        return dataService.getRoutes().thenAccept(routes -> {
            RoadGraph graph = new RoadGraph();
            int nodeId = 1;
            for (var route : routes) {
                GraphNode prev = null;
                for (Location loc : route.getNodes()) {
                    GraphNode node = new GraphNode(nodeId++, loc);
                    graph.addNode(node);
                    if (prev != null) {
                        Set<TransportType> types = EnumSet.of(TransportType.FOOT, TransportType.CAR);
                        graph.addEdge(prev, node, types);
                    }
                    prev = node;
                }
            }
            this.roadGraph = graph;
            this.nextNodeId = nodeId;
        });
    }

    public GraphNode addNode(Location location) {
        GraphNode node = new GraphNode(nextNodeId++, location);
        roadGraph.addNode(node);
        return node;
    }

    public boolean connectNodes(int id1, int id2, Set<TransportType> types) {
        GraphNode n1 = findNode(id1);
        GraphNode n2 = findNode(id2);
        if (n1 == null || n2 == null) {
            return false;
        }
        roadGraph.addEdge(n1, n2, types);
        return true;
    }

    public boolean disconnectNodes(int id1, int id2) {
        GraphNode n1 = findNode(id1);
        GraphNode n2 = findNode(id2);
        if (n1 == null || n2 == null) {
            return false;
        }
        roadGraph.removeEdge(n1, n2);
        return true;
    }

    public boolean addTransportToEdge(int id1, int id2, TransportType type) {
        GraphNode n1 = findNode(id1);
        GraphNode n2 = findNode(id2);
        if (n1 == null || n2 == null) {
            return false;
        }
        for (GraphEdge edge : roadGraph.getEdges(n1)) {
            if (edge.getTo().equals(n2)) {
                Set<TransportType> types = EnumSet.copyOf(edge.getAllowedTransport());
                types.add(type);
                roadGraph.removeEdge(n1, n2);
                roadGraph.addEdge(n1, n2, types);
                return true;
            }
        }
        return false;
    }

    public boolean removeTransportFromEdge(int id1, int id2, TransportType type) {
        GraphNode n1 = findNode(id1);
        GraphNode n2 = findNode(id2);
        if (n1 == null || n2 == null) {
            return false;
        }
        for (GraphEdge edge : roadGraph.getEdges(n1)) {
            if (edge.getTo().equals(n2)) {
                Set<TransportType> types = EnumSet.copyOf(edge.getAllowedTransport());
                types.remove(type);
                if (types.isEmpty()) {
                    roadGraph.removeEdge(n1, n2);
                } else {
                    roadGraph.removeEdge(n1, n2);
                    roadGraph.addEdge(n1, n2, types);
                }
                return true;
            }
        }
        return false;
    }

    public GraphNode findNode(int id) {
        for (GraphNode node : roadGraph.getNodes()) {
            if (node.getId() == id) {
                return node;
            }
        }
        return null;
    }

    public List<GraphNode> getNodes() {
        return roadGraph.getNodes();
    }

    public List<GraphEdge> getEdges() {
        List<GraphEdge> all = new ArrayList<>();
        for (GraphNode node : roadGraph.getNodes()) {
            all.addAll(roadGraph.getEdges(node));
        }
        return all;
    }
}
