package ru.hahgps.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Логика «неподвижен N»: на ней держится подсказка «построй маршрут»,
 * поэтому проверяются и порог, и сброс времени движения, и то, что
 * затонувший/потерявший связь трекер не считается «стоящим живым».
 */
class TrackerTest {

    private static Tracker tracker(long movedAt, boolean sunk) {
        return new Tracker(1, "TRK-0001", "Дом", TrackerColor.RED, true,
                UUID.randomUUID(), "tester", "world", 10, 64, 20,
                null, sunk, 1_000L, movedAt, movedAt);
    }

    @Test
    void stationaryOnlyAfterConfiguredThreshold() {
        long now = 200_000L;
        Tracker tracker = tracker(now - 60_000L, false);
        assertTrue(tracker.isStationary(now, 60_000L), "минута без движения — неподвижен");
        assertFalse(tracker.isStationary(now, 120_000L), "порог в две минуты ещё не достигнут");
        assertEquals(60_000L, tracker.stationaryMillis(now));
    }

    @Test
    void movementResetsTheStationaryClock() {
        long now = 200_000L;
        Tracker tracker = tracker(now - 60_000L, false);
        boolean moved = tracker.moveTo(30, 64, 20, now);
        assertTrue(moved, "смещение на 20 блоков — это движение");
        assertEquals(now, tracker.getMovedAt());
        assertFalse(tracker.isStationary(now, 60_000L), "после движения трекер снова в пути");
        assertEquals(30.0, tracker.getX());
    }

    @Test
    void jitterOfEntityDoesNotCountAsMovement() {
        long now = 200_000L;
        Tracker tracker = tracker(now - 60_000L, false);
        boolean moved = tracker.moveTo(10.1, 64, 20.1, now);
        assertFalse(moved, "дрожание меньше порога не движение");
        assertEquals(now - 60_000L, tracker.getMovedAt(), "время движения не сбрасывается");
        assertTrue(tracker.isStationary(now, 60_000L));
    }

    @Test
    void sunkTrackerIsNotLiveAndDoesNotOfferRoute() {
        long now = 200_000L;
        Tracker tracker = tracker(now - 600_000L, true);
        assertEquals(Tracker.Status.SUNK, tracker.status());
        assertFalse(tracker.hasSignal());
        assertFalse(tracker.isStationary(now, 60_000L), "затонувшему предлагать маршрут незачем");
    }

    @Test
    void lostSignalTrackerIsNotStationary() {
        long now = 200_000L;
        Tracker tracker = tracker(now - 600_000L, false);
        tracker.setSignal(false);
        assertEquals(Tracker.Status.NO_SIGNAL, tracker.status());
        assertFalse(tracker.isStationary(now, 60_000L));
        tracker.setSignal(true);
        assertTrue(tracker.isStationary(now, 60_000L));
    }

    @Test
    void sunkFlagWinsOverRestoredSignal() {
        Tracker tracker = tracker(10L, true);
        tracker.setSignal(true);
        assertFalse(tracker.hasSignal(), "затонувшему сигнал не вернуть до подъёма");
    }

    @Test
    void displayNameFallsBackToCode() {
        assertEquals("Дом", tracker(1L, false).getDisplayName());
        Tracker unnamed = new Tracker(2, "TRK-BEEF", "  ", TrackerColor.BLUE, false,
                UUID.randomUUID(), "tester", "world", 0, 64, 0, null, false, 1L, 1L, 1L);
        assertEquals("TRK-BEEF", unnamed.getDisplayName());
    }

    @Test
    void colorParsesKeyAndEnumName() {
        assertEquals(TrackerColor.RED, TrackerColor.parse("red"));
        assertEquals(TrackerColor.RED, TrackerColor.parse("RED"));
        assertEquals(TrackerColor.CYAN, TrackerColor.parse(" cyan "));
        assertNull(TrackerColor.parse("pink"));
        assertNull(TrackerColor.parse(null));
        assertEquals(8, TrackerColor.values().length);
        assertEquals(TrackerColor.BLUE, TrackerColor.parse("BLUE"));
    }

    @Test
    void formattedCoordsAreStable() {
        Tracker tracker = tracker(1L, false);
        assertEquals("10.0, 64.0, 20.0", tracker.getFormattedCoords());
    }
}
