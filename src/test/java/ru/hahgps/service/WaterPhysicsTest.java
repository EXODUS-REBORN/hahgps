package ru.hahgps.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Физика трекера в воде: поверхность, течение, водопад и падение на землю.
 * Мир подменён сеткой блоков, поэтому ни Bukkit, ни сервер не нужны.
 */
class WaterPhysicsTest {

    private static final double EPS = 1e-6;

    /** Высота воды в блоке: как у полного источника в Minecraft. */
    private static final double SURFACE = 0.875;

    private enum Cell {
        WATER, FALLING, SOLID
    }

    private static final class Grid implements WaterPhysics.Terrain {

        private final Map<String, Cell> cells = new HashMap<>();
        private final Map<String, double[]> flows = new HashMap<>();

        Grid water(int x, int y, int z) {
            cells.put(key(x, y, z), Cell.WATER);
            return this;
        }

        Grid falling(int x, int y, int z) {
            cells.put(key(x, y, z), Cell.FALLING);
            return this;
        }

        Grid solid(int x, int y, int z) {
            cells.put(key(x, y, z), Cell.SOLID);
            return this;
        }

        Grid flow(int x, int y, int z, double dx, double dz) {
            flows.put(key(x, y, z), new double[]{dx, dz});
            return this;
        }

        /** Пол: сплошной слой твёрдых блоков. */
        Grid floor(int y, int fromX, int toX, int fromZ, int toZ) {
            for (int x = fromX; x <= toX; x++) {
                for (int z = fromZ; z <= toZ; z++) {
                    solid(x, y, z);
                }
            }
            return this;
        }

        private Cell cell(int x, int y, int z) {
            return cells.get(key(x, y, z));
        }

        private double[] flowVector(int x, int y, int z) {
            return flows.getOrDefault(key(x, y, z), new double[]{0.0, 0.0});
        }

        @Override
        public boolean isWater(int x, int y, int z) {
            Cell cell = cell(x, y, z);
            return cell == Cell.WATER || cell == Cell.FALLING;
        }

        @Override
        public boolean isFallingWater(int x, int y, int z) {
            return cell(x, y, z) == Cell.FALLING;
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return cell(x, y, z) == Cell.SOLID;
        }

        @Override
        public double surfaceY(int x, int y, int z) {
            return y + SURFACE;
        }

        @Override
        public double flowX(int x, int y, int z) {
            return flowVector(x, y, z)[0];
        }

        @Override
        public double flowZ(int x, int y, int z) {
            return flowVector(x, y, z)[1];
        }

        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }
    }

    @Test
    void driftsAlongTheCurrentOnTheSurface() {
        Grid grid = new Grid().floor(62, -4, 8, -1, 1);
        for (int x = -4; x <= 8; x++) {
            grid.water(x, 63, 0);
        }
        grid.flow(0, 63, 0, 1.0, 0.0);

        WaterPhysics.Pose pose = WaterPhysics.floatStep(0.5, 63 + SURFACE, 0.5,
                grid, 1.0, 0.8, 6.0);

        assertTrue(pose.floating(), "на воде трекер плавает");
        assertEquals(1.3, pose.x(), EPS, "за секунду течение снесло на 0.8 блока");
        assertEquals(0.5, pose.z(), EPS);
        assertEquals(63 + SURFACE, pose.y(), EPS, "остался на поверхности");
        assertNull(pose.ground());
    }

    @Test
    void diagonalCurrentIsNormalised() {
        Grid grid = new Grid().floor(62, -4, 8, -4, 8);
        for (int x = -4; x <= 8; x++) {
            for (int z = -4; z <= 8; z++) {
                grid.water(x, 63, z);
            }
        }
        grid.flow(0, 63, 0, 1.0, 1.0);

        WaterPhysics.Pose pose = WaterPhysics.floatStep(0.5, 63 + SURFACE, 0.5,
                grid, 1.0, 0.8, 6.0);

        double dx = pose.x() - 0.5;
        double dz = pose.z() - 0.5;
        assertEquals(0.8, Math.hypot(dx, dz), EPS, "скорость одна независимо от направления");
        assertEquals(dx, dz, EPS, "диагональ сносит одинаково по обеим осям");
    }

    @Test
    void stillWaterKeepsTheTrackerInPlace() {
        Grid grid = new Grid().floor(62, -2, 2, -2, 2);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                grid.water(x, 63, z);
            }
        }

        WaterPhysics.Pose pose = WaterPhysics.floatStep(0.5, 63 + SURFACE, 0.5,
                grid, 1.0, 0.8, 6.0);

        assertTrue(pose.floating());
        assertEquals(0.5, pose.x(), EPS, "стоячая вода не двигает трекер");
        assertEquals(0.5, pose.z(), EPS);
    }

    @Test
    void bankStopsTheDrift() {
        Grid grid = new Grid().floor(62, -4, 4, -1, 1);
        grid.water(0, 63, 0);
        for (int z = -1; z <= 1; z++) {
            grid.solid(1, 63, z);
        }
        grid.flow(0, 63, 0, 1.0, 0.0);

        WaterPhysics.Pose pose = WaterPhysics.floatStep(0.5, 63 + SURFACE, 0.5,
                grid, 1.0, 0.8, 6.0);

        assertTrue(pose.floating());
        assertEquals(0.5, pose.x(), EPS, "в берег течение трекер не вжимает");
    }

    @Test
    void waterfallCarriesTheTrackerDownToThePool() {
        Grid grid = new Grid().floor(57, 0, 2, -1, 1);
        for (int y = 58; y <= 63; y++) {
            grid.falling(1, y, 0);
        }
        grid.water(1, 57, 0);

        WaterPhysics.Pose pose = WaterPhysics.floatStep(1.5, 63 + SURFACE, 0.5,
                grid, 1.0, 0.8, 6.0);

        assertTrue(pose.floating(), "из водопада трекер всплывает на поверхность");
        assertEquals(57 + SURFACE, pose.y(), EPS, "встал на воду в нижнем бассейне");
    }

    @Test
    void fallsOffTheLedgeAndLandsOnTheGround() {
        Grid grid = new Grid().floor(60, -2, 4, -1, 1);
        for (int x = -2; x <= 0; x++) {
            grid.water(x, 63, 0);
        }
        grid.flow(0, 63, 0, 1.0, 0.0);

        // Течение выносит за край: над сушей воды нет.
        WaterPhysics.Pose overEdge = WaterPhysics.floatStep(0.5, 63 + SURFACE, 0.5,
                grid, 1.0, 0.8, 6.0);
        assertTrue(overEdge.x() > 1.0, "трекер вынесло за край");

        WaterPhysics.Pose fallen = WaterPhysics.floatStep(overEdge.x(), overEdge.y(), overEdge.z(),
                grid, 1.0, 0.8, 6.0);
        assertFalse(fallen.floating(), "без воды трекер больше не плавает");
        assertNotNull(fallen.ground(), "сел на землю");
        assertEquals(60, fallen.ground().y());
        assertEquals(61.5, fallen.y(), EPS, "маркер встал в свободный блок над землёй");
    }

    @Test
    void freeFallWithoutGroundKeepsFalling() {
        Grid grid = new Grid();

        WaterPhysics.Pose pose = WaterPhysics.floatStep(0.5, 100.0, 0.5,
                grid, 1.0, 0.8, 6.0);

        assertFalse(pose.floating());
        assertNull(pose.ground());
        assertEquals(94.0, pose.y(), EPS, "за секунду пролетел 6 блоков");
    }

    @Test
    void zeroDriftSpeedDisablesCurrent() {
        Grid grid = new Grid().floor(62, -2, 4, -1, 1);
        for (int x = -2; x <= 4; x++) {
            grid.water(x, 63, 0);
        }
        grid.flow(0, 63, 0, 1.0, 0.0);

        WaterPhysics.Pose pose = WaterPhysics.floatStep(0.5, 63 + SURFACE, 0.5,
                grid, 1.0, 0.0, 6.0);

        assertTrue(pose.floating());
        assertEquals(0.5, pose.x(), EPS);
    }
}
