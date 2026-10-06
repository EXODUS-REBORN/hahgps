package ru.hahgps.service;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import ru.hahgps.model.GraphNode;
import ru.hahgps.model.RoadGraph;
import ru.hahgps.model.TransportType;
import ru.hahgps.testsupport.FakeWorld;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Алгоритм поиска пути: до 1.1.0 у него не было ни одного теста.
 */
class PathfinderTest {

    private static final World WORLD = FakeWorld.named("world");

    private static RoadGraph chain() {
        RoadGraph graph = new RoadGraph();
        GraphNode a = new GraphNode(1, new Location(WORLD, 0, 64, 0));
        GraphNode b = new GraphNode(2, new Location(WORLD, 10, 64, 0));
        GraphNode c = new GraphNode(3, new Location(WORLD, 20, 64, 0));
        graph.addNode(a);
        graph.addNode(b);
        graph.addNode(c);
        graph.addEdge(a, b, EnumSet.of(TransportType.FOOT, TransportType.CAR));
        graph.addEdge(b, c, EnumSet.of(TransportType.FOOT, TransportType.CAR));
        return graph;
    }

    @Test
    void findsPathThroughConnectedNodes() {
        RoadGraph graph = chain();
        List<GraphNode> path = Pathfinder.findPath(graph, graph.getNodes().get(0),
                graph.getNodes().get(2), TransportType.FOOT);

        assertEquals(3, path.size(), "путь обязан пройти через средний узел");
        assertEquals(1, path.get(0).getId());
        assertEquals(2, path.get(1).getId());
        assertEquals(3, path.get(2).getId());
    }

    @Test
    void sameNodeGivesSingleElementPath() {
        RoadGraph graph = chain();
        GraphNode first = graph.getNodes().get(0);
        assertEquals(List.of(first), Pathfinder.findPath(graph, first, first, TransportType.FOOT));
    }

    @Test
    void unreachableNodeGivesEmptyPath() {
        RoadGraph graph = chain();
        GraphNode island = new GraphNode(99, new Location(WORLD, 500, 64, 500));
        graph.addNode(island);

        List<GraphNode> path = Pathfinder.findPath(graph, graph.getNodes().get(0), island,
                TransportType.FOOT);
        assertTrue(path.isEmpty(), "до изолированного узла пути нет");
    }

    @Test
    void transportFilterIsRespected() {
        RoadGraph graph = new RoadGraph();
        GraphNode a = new GraphNode(1, new Location(WORLD, 0, 64, 0));
        GraphNode b = new GraphNode(2, new Location(WORLD, 10, 64, 0));
        graph.addNode(a);
        graph.addNode(b);
        graph.addEdge(a, b, EnumSet.of(TransportType.CAR));

        assertFalse(Pathfinder.findPath(graph, a, b, TransportType.CAR).isEmpty(),
                "по ребру для машины машина проедет");
        assertTrue(Pathfinder.findPath(graph, a, b, TransportType.FOOT).isEmpty(),
                "пешеходу ребро без FOOT не подходит");
    }

    @Test
    void nullNodesGiveEmptyPathInsteadOfException() {
        assertTrue(Pathfinder.findPath(chain(), null, null, TransportType.FOOT).isEmpty());
    }

    @Test
    void buildRouteKeepsEndpointsAndGoesThroughGraph() {
        RoadGraph graph = chain();
        Location start = new Location(WORLD, -5, 64, 0);
        Location end = new Location(WORLD, 25, 64, 0);

        List<Location> route = Pathfinder.buildRoute(graph, start, end, TransportType.FOOT);

        assertFalse(route.isEmpty());
        assertEquals(start, route.get(0), "маршрут начинается там, где стоит игрок");
        assertEquals(end, route.get(route.size() - 1), "и заканчивается у цели");
        assertTrue(route.size() >= 4, "между концами обязан быть хотя бы один узел графа");
        assertTrue(Pathfinder.routeLength(route) > 0);
    }

    @Test
    void buildRouteFallsBackToStraightLineOnEmptyGraph() {
        RoadGraph empty = new RoadGraph();
        Location start = new Location(WORLD, 0, 64, 0);
        Location end = new Location(WORLD, 30, 64, 0);

        List<Location> route = Pathfinder.buildRoute(empty, start, end, TransportType.FOOT);
        assertEquals(List.of(start, end), route);
    }

    @Test
    void buildRouteAcrossWorldsIsStraightLine() {
        Location start = new Location(WORLD, 0, 64, 0);
        Location end = new Location(FakeWorld.named("other"), 0, 64, 0);

        List<Location> route = Pathfinder.buildRoute(chain(), start, end, TransportType.FOOT);
        assertEquals(List.of(start, end), route);
    }

    @Test
    void multiModalRouteWalksToTransitAndBack() {
        RoadGraph graph = chain();
        Location start = new Location(WORLD, 0, 64, 0);
        Location end = new Location(WORLD, 20, 64, 0);

        List<Location> route = Pathfinder.buildMultiModalRoute(graph, start, end, TransportType.CAR);

        assertFalse(route.isEmpty());
        assertEquals(start, route.get(0));
        assertEquals(end, route.get(route.size() - 1));
        assertTrue(route.size() >= 3, "мультимодальный маршрут не должен схлопываться в две точки");
    }

    @Test
    void nearestNodeIsFoundByTransportRules() {
        RoadGraph graph = chain();
        GraphNode nearest = graph.findNearestNode(new Location(WORLD, 11, 64, 1), TransportType.FOOT);
        assertNotNull(nearest);
        assertEquals(2, nearest.getId());
    }

    @Test
    void routeLengthIgnoresOtherWorlds() {
        List<Location> route = List.of(
                new Location(WORLD, 0, 64, 0),
                new Location(WORLD, 3, 64, 4),
                new Location(FakeWorld.named("other"), 100, 64, 100));
        assertEquals(5.0, Pathfinder.routeLength(route), 0.0001);
    }
}
