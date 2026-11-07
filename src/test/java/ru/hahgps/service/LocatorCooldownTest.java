package ru.hahgps.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Перезарядка локатора: 5 минут на обычный (10 блоков) и 7 — на дальний,
 * с белой полосой на предмете.
 */
class LocatorCooldownTest {

    @Test
    void normalLocatorUsesShortCooldown() {
        assertEquals(300, LocatorCooldown.seconds(10, 10, 300, 420));
        assertEquals(300, LocatorCooldown.seconds(5, 10, 300, 420));
    }

    @Test
    void longLocatorUsesLongCooldown() {
        assertEquals(420, LocatorCooldown.seconds(15, 10, 300, 420));
        assertEquals(420, LocatorCooldown.seconds(32, 10, 300, 420));
    }

    @Test
    void negativeConfigValuesMeanNoCooldown() {
        assertEquals(0, LocatorCooldown.seconds(10, 10, -5, -1));
        assertEquals(120, LocatorCooldown.seconds(10, 10, 120, -1));
    }

    @Test
    void remainingClampsToZero() {
        assertEquals(0L, LocatorCooldown.remaining(1000L, 1000L));
        assertEquals(0L, LocatorCooldown.remaining(1000L, 2000L));
        assertEquals(1500L, LocatorCooldown.remaining(2500L, 1000L));
    }

    @Test
    void formatShowsClockTime() {
        assertEquals("4:59", LocatorCooldown.format(299_000L));
        assertEquals("5:00", LocatorCooldown.format(300_000L));
        assertEquals("0:00", LocatorCooldown.format(0L));
        assertEquals("1:02:03", LocatorCooldown.format(3_723_000L));
    }

    @Test
    void ticksMatchVanillaCooldown() {
        assertEquals(0, LocatorCooldown.ticks(0L));
        assertEquals(100, LocatorCooldown.ticks(5000L));
        assertEquals(104, LocatorCooldown.ticks(5200L));
        assertEquals(6000, LocatorCooldown.ticks(300_000L));
    }
}
