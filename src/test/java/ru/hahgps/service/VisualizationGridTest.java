package ru.hahgps.service;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import ru.hahgps.model.GraphEdge;
import ru.hahgps.model.GraphNode;
import ru.hahgps.model.TransportType;
import ru.hahgps.testsupport.FakeWorld;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пространственный индекс рёбер: ребро лежит в ячейках, которые пересекает,
 * поэтому по сетке рядом с игроком видно только окрестные рёбра, а не весь граф.
 */
class VisualizationGridTest {

    private static final World WORLD = FakeWorld.named("world");
    private static final World NETHER = FakeWorld.named("world_nether");

    private static GraphNode node(int id, World world, double x, double z) {
        return new GraphNode(id, new Location(world, x, 64, z));
    }

    private static GraphEdge edge(GraphNode from, GraphNode to) {
        return new GraphEdge(from, to, 1.0, EnumSet.of(TransportType.FOOT));
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, List<GraphEdge>> buildGrid(List<GraphEdge> edges) throws Exception {
        Method method = VisualizationService.class.getDeclaredMethod("buildGrid", List.class);
        method.setAccessible(true);
        return (Map<Long, List<GraphEdge>>) method.invoke(null, edges);
    }

    @Test
    void edgeIsIndexedByEveryCellItCrosses() throws Exception {
        GraphEdge longEdge = edge(node(1, WORLD, 0, 0), node(2, WORLD, 200, 0));

        Map<Long, List<GraphEdge>> grid = buildGrid(List.of(longEdge));

        // Ребро 0..200 при ячейке 64 пересекает четыре ячейки по X.
        assertEquals(4, grid.size());
        for (List<GraphEdge> bucket : grid.values()) {
            assertSame(longEdge, bucket.get(0));
        }
    }

    @Test
    void shortEdgeMatchesSingleBucket() throws Exception {
        GraphEdge shortEdge = edge(node(1, WORLD, 5, 5), node(2, WORLD, 20, 20));

        Map<Long, List<GraphEdge>> grid = buildGrid(List.of(shortEdge));

        assertEquals(1, grid.size());
        assertTrue(grid.values().stream().allMatch(bucket -> bucket.size() == 1));
    }

    @Test
    void cellKeySeparatesWorldsAndSign() throws Exception {
        Method key = VisualizationService.class
                .getDeclaredMethod("cellKey", String.class, int.class, int.class);
        key.setAccessible(true);

        long first = (Long) key.invoke(null, WORLD.getName(), -1, -1);
        long same = (Long) key.invoke(null, WORLD.getName(), -1, -1);
        long otherWorld = (Long) key.invoke(null, NETHER.getName(), -1, -1);
        long otherCell = (Long) key.invoke(null, WORLD.getName(), -2, -1);

        assertEquals(first, same);
        assertNotEquals(first, otherWorld);
        assertNotEquals(first, otherCell);
    }
}
