package ru.hahgps.service;

import org.bukkit.Location;
import ru.hahgps.model.GraphEdge;
import ru.hahgps.model.GraphNode;
import ru.hahgps.model.RoadGraph;
import ru.hahgps.model.TransportType;
import ru.hahgps.storage.DataService;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class GraphService {

    private final DataService dataService;
    /** Всегда заменяется целиком; volatile — для безопасной публикации читателям. */
    private volatile RoadGraph roadGraph = new RoadGraph();
    private int nextNodeId = 1;
    /** Счётчик изменений графа — по нему кэши (визуализация) узнают об инвалидации. */
    private volatile int revision;

    public GraphService(DataService dataService) {
        this.dataService = dataService;
    }

    public RoadGraph getRoadGraph() {
        return roadGraph;
    }

    public int getRevision() {
        return revision;
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
            // завершение идёт из потока БД — публикуем атомарно с nextNodeId
            synchronized (this) {
                this.roadGraph = graph;
                this.nextNodeId = nodeId;
                this.revision++;
            }
        });
    }

    /**
     * Неизменяемая рабочая копия графа для асинхронного поиска маршрута:
     * главный поток при этом может редактировать боевой граф без гонок.
     */
    public synchronized RoadGraph snapshot() {
        RoadGraph source = roadGraph;
        RoadGraph copy = new RoadGraph();
        List<GraphNode> nodes = source.getNodes();
        for (GraphNode node : nodes) {
            copy.addNode(node);
        }
        for (GraphNode node : nodes) {
            for (GraphEdge edge : source.getEdges(node)) {
                // ребро добавляется в граф в обе стороны — копируем один раз
                if (edge.getFrom().getId() >= edge.getTo().getId()) {
                    continue;
                }
                Set<TransportType> types = edge.getAllowedTransport();
                if (types == null || types.isEmpty()) {
                    continue;
                }
                copy.addEdge(edge.getFrom(), edge.getTo(), EnumSet.copyOf(types));
            }
        }
        return copy;
    }

    public synchronized GraphNode addNode(Location location) {
        GraphNode node = new GraphNode(nextNodeId++, location);
        roadGraph.addNode(node);
        revision++;
        return node;
    }

    public synchronized boolean connectNodes(int id1, int id2, Set<TransportType> types) {
        GraphNode n1 = findNode(id1);
        GraphNode n2 = findNode(id2);
        if (n1 == null || n2 == null) {
            return false;
        }
        roadGraph.addEdge(n1, n2, types);
        revision++;
        return true;
    }

    public synchronized boolean disconnectNodes(int id1, int id2) {
        GraphNode n1 = findNode(id1);
        GraphNode n2 = findNode(id2);
        if (n1 == null || n2 == null) {
            return false;
        }
        roadGraph.removeEdge(n1, n2);
        revision++;
        return true;
    }

    public synchronized boolean addTransportToEdge(int id1, int id2, TransportType type) {
        GraphNode n1 = findNode(id1);
        GraphNode n2 = findNode(id2);
        if (n1 == null || n2 == null) {
            return false;
        }
        for (GraphEdge edge : roadGraph.getEdges(n1)) {
            if (edge.getTo().equals(n2)) {
                Set<TransportType> current = edge.getAllowedTransport();
                Set<TransportType> types = (current == null || current.isEmpty())
                        ? EnumSet.noneOf(TransportType.class)
                        : EnumSet.copyOf(current);
                types.add(type);
                roadGraph.removeEdge(n1, n2);
                roadGraph.addEdge(n1, n2, types);
                revision++;
                return true;
            }
        }
        return false;
    }

    public synchronized boolean removeTransportFromEdge(int id1, int id2, TransportType type) {
        GraphNode n1 = findNode(id1);
        GraphNode n2 = findNode(id2);
        if (n1 == null || n2 == null) {
            return false;
        }
        for (GraphEdge edge : roadGraph.getEdges(n1)) {
            if (edge.getTo().equals(n2)) {
                Set<TransportType> current = edge.getAllowedTransport();
                if (current == null || current.isEmpty()) {
                    roadGraph.removeEdge(n1, n2);
                    revision++;
                    return true;
                }
                Set<TransportType> types = EnumSet.copyOf(current);
                types.remove(type);
                if (types.isEmpty()) {
                    roadGraph.removeEdge(n1, n2);
                } else {
                    roadGraph.removeEdge(n1, n2);
                    roadGraph.addEdge(n1, n2, types);
                }
                revision++;
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
        RoadGraph graph = roadGraph;
        List<GraphEdge> all = new ArrayList<>();
        for (GraphNode node : graph.getNodes()) {
            all.addAll(graph.getEdges(node));
        }
        return all;
    }
}
