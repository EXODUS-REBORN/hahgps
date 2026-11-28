package ru.hahgps.vmodel;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelLayoutTest {

    private static ModelDefinition cubic(double size) {
        return new ModelDefinition("тест", "minecraft:paper", 0, java.util.Set.of("FLOOR"),
                0, 0, 0, true, size, size, size, List.of(), 0, 0, 0, 0f, 0f, 0f, true, List.of());
    }

    @Test
    void layoutFillsTheModelWithShulkers() {
        ModelSolids solids = new ModelSolids();
        List<ModelSolids.Spot> spots = solids.layout(new Location(null, 10, 64, 10), cubic(1.0));
        assertFalse(spots.isEmpty(), "коллизия не построилась");
        assertTrue(spots.size() <= ModelSolids.MAX_CELLS, "клеток не больше лимита: " + spots.size());
        double minX = 100;
        double maxX = -100;
        double minY = 100;
        double maxY = -100;
        for (ModelSolids.Spot spot : spots) {
            Location at = spot.location();
            minX = Math.min(minX, at.getX());
            maxX = Math.max(maxX, at.getX());
            minY = Math.min(minY, at.getY());
            maxY = Math.max(maxY, at.getY());
            assertTrue(spot.scale() >= 0.25 && spot.scale() <= 1.0,
                    "масштаб шалкера в допустимых пределах: " + spot.scale());
        }
        assertEquals(10.0, (minX + maxX) / 2, 0.35, "центр по X");
        assertEquals(64.0, (minY + maxY) / 2, 0.60, "центр по Y");
        assertEquals(1.0, maxX - minX, 0.55, "ширина коллизии равна модели");
    }

    @Test
    void bigModelsAreCapped() {
        ModelSolids solids = new ModelSolids();
        List<ModelSolids.Spot> spots = solids.layout(new Location(null, 0, 0, 0), cubic(12.0));
        assertTrue(spots.size() <= ModelSolids.MAX_CELLS, "клеток не больше лимита: " + spots.size());
        assertTrue(spots.size() >= 27, "но и не меньше 3×3×3: " + spots.size());
    }

    @Test
    void severalPartsShareTheBudget() {
        ModelDefinition def = new ModelDefinition("тест", "minecraft:paper", 0,
                java.util.Set.of("FLOOR"), 0, 0, 0, true, 1, 1, 1,
                List.of(ModelShape.box(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5),
                        ModelShape.box(0.5, -0.5, -0.5, 1.5, 0.5, 0.5)),
                0, 0, 0, 0f, 0f, 0f, true, List.of());
        List<ModelSolids.Spot> spots = new ModelSolids().layout(new Location(null, 0, 0, 0), def);
        assertFalse(spots.isEmpty(), "части дали клетки");
        assertTrue(spots.size() <= ModelSolids.MAX_CELLS, "бюджет общий на все части");
        double minX = 100;
        double maxX = -100;
        for (ModelSolids.Spot spot : spots) {
            minX = Math.min(minX, spot.location().getX());
            maxX = Math.max(maxX, spot.location().getX());
        }
        assertTrue(maxX - minX > 1.2, "клетки покрывают обе части: " + (maxX - minX));
    }

    @Test
    void collidingModelMatchesTheShapes() {
        ModelSolids solids = new ModelSolids();
        List<ModelSolids.Spot> spots = solids.layout(new Location(null, 0, 0, 0), cubic(1.0));
        int inside = 0;
        for (ModelSolids.Spot spot : spots) {
            Location at = spot.location();
            if (Math.abs(at.getX()) <= 0.5 && Math.abs(at.getY()) <= 0.5
                    && Math.abs(at.getZ()) <= 0.5) {
                inside++;
            }
        }
        assertEquals(spots.size(), inside, "все клетки внутри куба 1×1×1");
    }

    @Test
    void placementKindFollowsTheFace() {
        assertEquals(Placement.FLOOR, Placement.kindOf(BlockFace.UP), "верх блока — пол");
        assertEquals(Placement.CEILING, Placement.kindOf(BlockFace.DOWN), "низ блока — потолок");
        assertEquals(Placement.WALL, Placement.kindOf(BlockFace.NORTH), "бок — стена");
        assertEquals(Placement.FLOOR, Placement.kindOf(null), "без грани считаем полом");
    }

    @Test
    void offsetFollowsTheWall() {
        ModelDefinition def = ModelDefinition.fallback("тест");
        ModelDefinition shifted = new ModelDefinition(def.key(), def.displayItem(), 0,
                def.faces(), 0.2, 0.1, 0.3, true, 1, 1, 1, List.of(),
                0, 0, 0, 0f, 0f, 0f, true, List.of());
        assertEquals(-0.3, Placement.offsetFor(shifted, BlockFace.NORTH).getZ(), 1e-9,
                "север зеркалит Z");
        assertEquals(0.2, Placement.offsetFor(shifted, BlockFace.NORTH).getX(), 1e-9,
                "X не меняется");
        assertEquals(0.2, Placement.offsetFor(shifted, BlockFace.EAST).getZ(), 1e-9,
                "восток меняет оси местами");
        assertEquals(0.3, Placement.offsetFor(shifted, BlockFace.EAST).getX(), 1e-9,
                "восток переносит Z в X");
    }

    @Test
    void wallYawLooksIntoTheRoom() {
        assertEquals(180f, Placement.yawFor(null, BlockFace.NORTH, true), 1e-6, "север");
        assertEquals(0f, Placement.yawFor(null, BlockFace.SOUTH, true), 1e-6, "юг");
        assertEquals(90f, Placement.yawFor(null, BlockFace.WEST, true), 1e-6, "запад");
        assertEquals(-90f, Placement.yawFor(null, BlockFace.EAST, true), 1e-6, "восток");
    }

    @Test
    void presetSitPointsLandAroundTheOrigin() {
        ModelDefinition def = new ModelDefinition("тест", "minecraft:paper", 0,
                java.util.Set.of("FLOOR"), 0, 0, 0, true, 1, 1, 1, List.of(),
                0, 0, 0, 0f, 0f, 0f, true,
                List.of(new double[]{0, 0.35, 0}, new double[]{0, 0.35, 0.5}));
        List<Location> spots = ModelSeats.spots(new Location(null, 5, 64, 5, 0f, 0f), def);
        assertEquals(2, spots.size(), "обе точки на месте");
        assertEquals(64.35, spots.get(0).getY(), 1e-6, "высота сиденья");
        assertEquals(4.5, spots.get(1).getZ(), 1e-6, "смещение по Z с разворотом модели");
    }
}
