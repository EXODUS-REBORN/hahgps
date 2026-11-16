package ru.hahgps.service;

import org.bukkit.Location;
import ru.hahgps.model.EdgeRecord;
import ru.hahgps.model.GraphEdge;
import ru.hahgps.model.GraphNode;
import ru.hahgps.model.RoadGraph;
import ru.hahgps.model.Route;
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
    private volatile int nextNodeId = 1;

    /** Растёт при каждой публикации: кэши по нему понимают, что устарели. */
    private volatile int revision;
    private volatile boolean loading;

    /** Текущая пересборка: второй вызов loadGraph() получает тот же future. */
    private volatile CompletableFuture<Void> loadInFlight;

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

    /**
     * Пересборка из БД: при старте и когда догружается новый мир.
     *
     * single-flight: два WorldLoadEvent подряд не должны запускать две
     * параллельные полные пересборки. Публикация итога — под монитором,
     * как и мутаторы, иначе правка могла бы затереть свежепересобранный граф.
     */
    public synchronized CompletableFuture<Void> loadGraph() {
        if (loading) {
            return loadInFlight;
        }
        loading = true;
        loadInFlight = dataService.getRoutes()
                .thenCompose(routes -> dataService.getEdgesAsync()
                        .thenApply(edges -> applyRoutes(routes, edges)))
                .handle((built, error) -> {
                    if (error != null) {
                        loading = false;
                        throw new CompletionException(error);
                    }
                    // Публикуем под монитором: handle(...) идёт на потоке БД,
                    // и правка под монитором (addNode/connectNodes) могла быть
                    // затёрта снимком, собранным раньше.
                    synchronized (this) {
                        publish(built.graph(), built.nextId());
                    }
                    log().log(Level.INFO, "HahGps: граф дорог собран — узлов " + built.graph().size()
                            + ", маршрутов " + built.routesUsed()
                            + (built.routesSkipped() > 0
                            ? ", пропущено маршрутов без загруженного мира: " + built.routesSkipped()
                            : "")
                            + ".");
                    return null;
                });
        return loadInFlight;
    }

    /** Сборка графа из маршрутов + применение сохранённых админских рёбер. */
    private BuiltGraph applyRoutes(List<Route> routes, List<EdgeRecord> edges) {
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
                    // Тип транспорта берём из самого маршрута: water/air-маршрут
                    // иначе становился проходимым пешком и на машине.
                    Set<TransportType> types = routeTransports(route);
                    graph.addEdge(prev, node, types);
                }
                prev = node;
            }
        }
        int applied = applyStoredEdges(graph, edges);
        if (applied > 0) {
            log().log(Level.INFO, "HahGps: применено сохранённых рёбер графа: " + applied + ".");
        }
        return new BuiltGraph(graph, nodeId, routesUsed, routesSkipped);
    }

    private Set<TransportType> routeTransports(Route route) {
        String key = route.getTransport();
        if (key == null || key.isBlank()) {
            return EnumSet.of(TransportType.FOOT, TransportType.CAR);
        }
        try {
            TransportType type = TransportType.valueOf(key.toUpperCase(java.util.Locale.ROOT));
            return EnumSet.of(type);
        } catch (IllegalArgumentException unknown) {
            return EnumSet.of(TransportType.FOOT, TransportType.CAR);
        }
    }

    /**
     * Восстановление админских рёбер: концы в БД хранятся координатами,
     * матчим с ближайшими узлами графа (допуск — доля блока).
     */
    private int applyStoredEdges(RoadGraph graph, List<EdgeRecord> edges) {
        if (edges == null || edges.isEmpty()) {
            return 0;
        }
        int applied = 0;
        List<GraphNode> nodes = graph.getNodes();
        for (EdgeRecord edge : edges) {
            if (edge.transports().isEmpty()) {
                continue;
            }
            GraphNode a = nearestTo(graph, nodes, edge.world(), edge.x1(), edge.y1(), edge.z1());
            GraphNode b = nearestTo(graph, nodes, edge.world(), edge.x2(), edge.y2(), edge.z2());
            if (a == null || b == null || a.equals(b)) {
                continue;
            }
            graph.replaceEdge(a, b, EnumSet.copyOf(edge.transports()));
            applied++;
        }
        return applied;
    }

    private GraphNode nearestTo(RoadGraph graph, List<GraphNode> nodes, String world,
                                double x, double y, double z) {
        GraphNode best = null;
        double bestDist = Double.MAX_VALUE;
        for (GraphNode node : nodes) {
            Location loc = node.getLocation();
            if (loc == null || loc.getWorld() == null || !loc.getWorld().getName().equals(world)) {
                continue;
            }
            double dx = loc.getX() - x;
            double dy = loc.getY() - y;
            double dz = loc.getZ() - z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = node;
            }
        }
        // Допуск 4 блока: координаты в БД должны совпадать с узлами маршрутов.
        return bestDist <= 16.0 ? best : null;
    }

    private record BuiltGraph(RoadGraph graph, int nextId, int routesUsed, int routesSkipped) {
    }

    private void publish(RoadGraph graph, int nextId) {
        roadGraph = graph;
        // Счётчик id только растёт: снимок из БД не должен откатывать его назад,
        // иначе новые узлы получат уже занятые id и склеятся с чужими.
        nextNodeId = Math.max(nextNodeId, nextId);
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
            if (types == null || types.isEmpty()) {
                return false;
            }
            RoadGraph copy = roadGraph.copy();
            copy.replaceEdge(n1, n2, EnumSet.copyOf(types));
            publish(copy, nextNodeId);
            persistEdge(n1, n2, types, true);
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
            persistEdge(n1, n2, EnumSet.noneOf(TransportType.class), false);
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
            copy.replaceEdge(n1, n2, current);
            publish(copy, nextNodeId);
            persistEdge(n1, n2, current, true);
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
            copy.replaceEdge(n1, n2, current);
            publish(copy, nextNodeId);
            if (current.isEmpty()) {
                persistEdge(n1, n2, current, false);
            } else {
                persistEdge(n1, n2, current, true);
            }
            return true;
        }
    }

    /** Правка ребра должна пережить пересборку графа: пишем в БД асинхронно. */
    private void persistEdge(GraphNode n1, GraphNode n2, Set<TransportType> types, boolean save) {
        Location a = n1.getLocation();
        Location b = n2.getLocation();
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            return;
        }
        EdgeRecord record = new EdgeRecord(
                a.getWorld().getName(),
                a.getX(), a.getY(), a.getZ(),
                b.getX(), b.getY(), b.getZ(),
                EnumSet.copyOf(types));
        try {
            if (save) {
                dataService.saveEdge(record);
            } else {
                dataService.deleteEdge(record);
            }
        } catch (Throwable t) {
            log().log(Level.WARNING, "HahGps: не удалось сохранить ребро графа в БД", t);
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
            for (GraphEdge edge : graph.getEdges(node)) {
                // Рёбра неориентированные и лежат в обе стороны: отдаём
                // каждое только один раз.
                if (edge.getFrom() != null && edge.getTo() != null
                        && edge.getFrom().getId() < edge.getTo().getId()) {
                    all.add(edge);
                }
            }
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
