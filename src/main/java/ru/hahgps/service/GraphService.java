package ru.hahgps.service;

import org.bukkit.Location;
import ru.hahgps.model.GraphEdge;
import ru.hahgps.model.GraphNode;
import ru.hahgps.model.RoadGraph;
import ru.hahgps.model.TransportType;
import ru.hahgps.storage.DataService;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Единственный владелец графа дорог.
 *
 * Модель публикации copy-on-write: любая правка идёт по приватной копии, готовый
 * граф выкладывается в volatile-поле. Читатели (навигация в отдельном потоке,
 * визуализация в главном) никогда не видят наполовину изменённый граф и не делят
 * с писателем списки смежности.
 */
public class GraphService {

    private final DataService dataService;

    private volatile RoadGraph roadGraph = new RoadGraph();
    private int nextNodeId = 1;

    /** Растёт при каждой публикации: кэши по нему понимают, что устарели. */
    private volatile int revision;
    private volatile boolean loading;

    public GraphService(DataService dataService) {
        this.dataService = dataService;
    }

    /** Опубликованный граф — только для чтения. */
    public RoadGraph graph() {
        return roadGraph;
    }

    public int getRevision() {
        return revision;
    }

    public boolean isLoading() {
        return loading;
    }

    /** Пересборка из БД: при старте и когда догружается новый мир. */
    public CompletableFuture<Void> loadGraph() {
        loading = true;
        return dataService.getRoutes().handle((routes, error) -> {
            if (error != null) {
                loading = false;
                throw new CompletionException(error);
            }
            RoadGraph graph = new RoadGraph();
            int nodeId = 1;
            int routesUsed = 0;
            int routesSkipped = 0;
            for (var route : routes) {
                List<Location> nodes = route.getNodes();
                if (nodes.size() < 2) {
                    routesSkipped++;
                    continue;
                }
                routesUsed++;
                GraphNode prev = null;
                for (Location loc : nodes) {
                    GraphNode node = new GraphNode(nodeId++, loc);
                    graph.addNode(node);
                    if (prev != null) {
                        graph.addEdge(prev, node, EnumSet.of(TransportType.FOOT, TransportType.CAR));
                    }
                    prev = node;
                }
            }
            publish(graph, nodeId);
            log().log(Level.INFO, "HahGps: граф дорог собран — узлов " + graph.size()
                    + ", маршрутов " + routesUsed
                    + (routesSkipped > 0 ? ", пропущено маршрутов без загруженного мира: " + routesSkipped : "")
                    + ".");
            return null;
        });
    }

    private void publish(RoadGraph graph, int nextId) {
        roadGraph = graph;
        nextNodeId = nextId;
        revision++;
        loading = false;
    }

    public GraphNode addNode(Location location) {
        synchronized (this) {
            RoadGraph copy = roadGraph.copy();
            GraphNode node = new GraphNode(nextNodeId++, location);
            copy.addNode(node);
            publish(copy, nextNodeId);
            return node;
        }
    }

    public boolean connectNodes(int id1, int id2, Set<TransportType> types) {
        synchronized (this) {
            GraphNode n1 = findNode(id1);
            GraphNode n2 = findNode(id2);
            if (n1 == null || n2 == null) {
                return false;
            }
            RoadGraph copy = roadGraph.copy();
            copy.addEdge(n1, n2, types);
            publish(copy, nextNodeId);
            return true;
        }
    }

    public boolean disconnectNodes(int id1, int id2) {
        synchronized (this) {
            GraphNode n1 = findNode(id1);
            GraphNode n2 = findNode(id2);
            if (n1 == null || n2 == null) {
                return false;
            }
            RoadGraph copy = roadGraph.copy();
            copy.removeEdge(n1, n2);
            publish(copy, nextNodeId);
            return true;
        }
    }

    public boolean addTransportToEdge(int id1, int id2, TransportType type) {
        synchronized (this) {
            GraphNode n1 = findNode(id1);
            GraphNode n2 = findNode(id2);
            if (n1 == null || n2 == null) {
                return false;
            }
            Set<TransportType> current = typesOf(n1, n2);
            if (current == null) {
                return false;
            }
            current.add(type);
            RoadGraph copy = roadGraph.copy();
            copy.addEdge(n1, n2, current);
            publish(copy, nextNodeId);
            return true;
        }
    }

    public boolean removeTransportFromEdge(int id1, int id2, TransportType type) {
        synchronized (this) {
            GraphNode n1 = findNode(id1);
            GraphNode n2 = findNode(id2);
            if (n1 == null || n2 == null) {
                return false;
            }
            Set<TransportType> current = typesOf(n1, n2);
            if (current == null) {
                return false;
            }
            current.remove(type);
            RoadGraph copy = roadGraph.copy();
            copy.removeEdge(n1, n2);
            if (!current.isEmpty()) {
                copy.addEdge(n1, n2, current);
            }
            publish(copy, nextNodeId);
            return true;
        }
    }

    private Set<TransportType> typesOf(GraphNode from, GraphNode to) {
        for (GraphEdge edge : roadGraph.getEdges(from)) {
            if (edge.getTo().equals(to)) {
                Set<TransportType> current = edge.getAllowedTransport();
                return (current == null || current.isEmpty())
                        ? EnumSet.noneOf(TransportType.class)
                        : EnumSet.copyOf(current);
            }
        }
        return null;
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

    private static Logger log() {
        try {
            Logger logger = org.bukkit.Bukkit.getLogger();
            if (logger != null) {
                return logger;
            }
        } catch (Throwable ignored) {
            // вне сервера (юнит-тесты) Bukkit недоступен
        }
        return Logger.getLogger("HahGps");
    }
}
