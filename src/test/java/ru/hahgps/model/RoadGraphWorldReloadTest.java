package ru.hahgps.model;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import ru.hahgps.testsupport.FakeWorld;
import ru.hahgps.util.LocationUtil;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Мир после перезагрузки (Multiverse, /mv reload) — новый объект, но с тем же
 * именем. Сравнение мира по идентификации объявления после первой перезагрузки
 * «разные миры» одним и тем же миром, и навигация умирала молча.
 */
class RoadGraphWorldReloadTest {

    @Test
    void nodesAreFoundAcrossWorldInstances() {
        World before = FakeWorld.named("world");
        RoadGraph graph = new RoadGraph();
        graph.addNode(new GraphNode(1, new Location(before, 0, 64, 0)));
        graph.addNode(new GraphNode(2, new Location(before, 10, 64, 0)));
        graph.addEdge(graph.getNodes().get(0), graph.getNodes().get(1),
                EnumSet.of(TransportType.FOOT));

        World afterReload = FakeWorld.named("world");
        GraphNode nearest = graph.findNearestNode(new Location(afterReload, 8, 64, 0), TransportType.FOOT);

        assertNotNull(nearest, "узел должен находиться и после перезагрузки мира");
        assertEquals(2, nearest.getId());
    }

    @Test
    void otherWorldIsNotMatched() {
        World world = FakeWorld.named("world");
        RoadGraph graph = new RoadGraph();
        graph.addNode(new GraphNode(1, new Location(world, 0, 64, 0)));

        assertNull(graph.findNearestNode(new Location(FakeWorld.named("nether"), 0, 64, 0),
                TransportType.FOOT));
    }

    @Test
    void locationDistanceWorksAcrossWorldInstances() {
        World before = FakeWorld.named("world");
        World afterReload = FakeWorld.named("world");

        double distance = LocationUtil.distance(
                new Location(before, 0, 64, 0),
                new Location(afterReload, 3, 64, 4));

        assertEquals(5.0, distance, 0.0001);
        // Разные миры по-прежнему не пересекаются.
        assertEquals(Double.MAX_VALUE, LocationUtil.distance(
                new Location(before, 0, 64, 0),
                new Location(FakeWorld.named("end"), 0, 64, 0)));
    }

    @Test
    void graphNodeDistanceSurvivesWorldReload() {
        World before = FakeWorld.named("world");
        World afterReload = FakeWorld.named("world");
        GraphNode a = new GraphNode(1, new Location(before, 0, 64, 0));
        GraphNode b = new GraphNode(2, new Location(afterReload, 6, 64, 8));

        assertEquals(10.0, a.distanceTo(b), 0.0001);
    }
}
