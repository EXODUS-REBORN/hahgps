package ru.hahgps.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Панорама карты мышью: поворот на 180° = половина ширины полотна. */
class MapPanTest {

    @Test
    void quarterTurnMovesQuarterOfTheMap() {
        double[] delta = MapService.panDelta(90f, 0f, 2.0, 1.0);
        // CLOSE: 128 пикселей × 2 блока = 256 блоков полотна, 90° — четверть.
        assertEquals(-64.0, delta[0], 0.001);
        assertEquals(0.0, delta[1], 0.001);
    }

    @Test
    void turningRightPullsMapRight() {
        // Взгляд вправо (+yaw) тянет полотно вправо: центр уходит на запад.
        assertTrue(MapService.panDelta(10f, 0f, 2.0, 1.0)[0] < 0);
        // Взгляд вниз (+pitch) тянет полотно вниз: центр уходит на север.
        assertTrue(MapService.panDelta(0f, 10f, 2.0, 1.0)[1] < 0);
        // И наоборот.
        assertTrue(MapService.panDelta(-10f, 0f, 2.0, 1.0)[0] > 0);
        assertTrue(MapService.panDelta(0f, -10f, 2.0, 1.0)[1] > 0);
    }

    @Test
    void sensitivityScalesTheShift() {
        double full = MapService.panDelta(45f, 30f, 4.0, 1.0)[0];
        double half = MapService.panDelta(45f, 30f, 4.0, 0.5)[0];
        assertEquals(full / 2.0, half, 0.001);
        assertEquals(0.0, MapService.panDelta(0f, 0f, 2.0, 1.0)[0], 0.0);
    }
}
