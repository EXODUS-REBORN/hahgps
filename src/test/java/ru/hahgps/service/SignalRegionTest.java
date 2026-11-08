package ru.hahgps.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Дальность сигнала: район вместо точной точки и «100 км = 100 000 блоков». */
class SignalRegionTest {

    @Test
    void exactUntilRangeAndOnlyAreaBeyond() {
        assertTrue(SignalRegion.inRange(99_999.0, 100_000));
        assertTrue(SignalRegion.inRange(100_000.0, 100_000));
        assertFalse(SignalRegion.inRange(100_001.0, 100_000));
        // Другой мир — расстояния нет, значит и точной точки нет.
        assertFalse(SignalRegion.inRange(Double.MAX_VALUE, 100_000));
        // 0 — без ограничения: точное место видно всегда.
        assertTrue(SignalRegion.inRange(Double.MAX_VALUE, 0));
    }

    @Test
    void regionCellsAreStableOnBothSidesOfZero() {
        assertEquals(0, SignalRegion.start(10, 512));
        assertEquals(512, SignalRegion.start(512, 512));
        assertEquals(1024, SignalRegion.start(1500, 512));
        assertEquals(-512, SignalRegion.start(-1, 512));
        assertEquals(-1024, SignalRegion.start(-513, 512));
        assertEquals(511, SignalRegion.end(10, 512));
        assertEquals(256, SignalRegion.center(10, 512));
    }

    @Test
    void brokenCellSizeDoesNotDivideByZero() {
        // Размер района 0 — считаем его единицей, а не падаем с делением на ноль.
        assertEquals(10, SignalRegion.start(10, 0));
        assertEquals(1, SignalRegion.start(1, 0));
        assertTrue(SignalRegion.inRange(0.0, 1));
    }
}
