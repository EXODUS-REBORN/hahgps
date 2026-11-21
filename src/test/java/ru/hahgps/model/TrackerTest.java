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
        return tracker(movedAt, sunk, true);
    }

    private static Tracker tracker(long movedAt, boolean sunk, boolean waterproof) {
        return new Tracker(1, "TRK-0001", "Дом", TrackerColor.RED, waterproof,
                UUID.randomUUID(), "tester", "world", 10, 64, 20,
                null, sunk, 1_000L, movedAt, movedAt);
    }

    /**
     * Медленный носитель: за один тик меньше порога, но суммарно трекер едет —
     * координаты обязаны сохраняться, а «неподвижен» не должно приходить.
     */
    @Test
    void slowCarrierIsMarkedAsMovedAfterAccumulatedShift() {
        Tracker tracker = tracker(0L, false);
        long now = 10_000L;
        boolean marked = false;
        for (int step = 0; step < 12; step++) {
            // 0.3 блока за тик: каждый шаг ниже MOVE_THRESHOLD (0.35).
            marked |= tracker.moveTo(10 + 0.3 * (step + 1), 64, 20, now);
        }
        assertTrue(marked, "суммарный сдвиг больше блока — трекер сдвинулся");
        assertEquals(0L, tracker.stationaryMillis(now), "время движения обновилось");
    }

    @Test
    void standingStillIsNotMarkedAsMoved() {
        Tracker tracker = tracker(0L, false);
        assertFalse(tracker.moveTo(10, 64, 20, 5_000L), "координаты не менялись");
        assertFalse(tracker.moveTo(10.1, 64, 20, 6_000L), "0.1 блока — ниже порога");
        assertTrue(tracker.moveTo(12, 64, 20, 7_000L), "почти два блока — движение");
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
    void ruinedTrackerLosesSignalButKeepsItsPlace() {
        long now = 200_000L;
        Tracker tracker = tracker(now - 10_000L, false, false);
        assertTrue(tracker.hasSignal());

        tracker.ruin(now);
        assertTrue(tracker.isRuined());
        assertEquals(now, tracker.getRuinedAt());
        assertFalse(tracker.hasSignal(), "промокший трекер сигнала не даёт");
        assertEquals(Tracker.Status.RUINED, tracker.status());
        assertFalse(tracker.isStationary(now + 600_000L, 60_000L),
                "у промокшего трекера нет живого состояния «неподвижен»");
        assertEquals(10.0, tracker.getX(), "последнее место не съезжает");
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
    void smallStepsOfWaterCurrentCountAsMovement() {
        long now = 200_000L;
        Tracker tracker = tracker(now - 60_000L, false);
        tracker.moveTo(10.05, 64, 20, now);
        assertTrue(tracker.isStationary(now, 60_000L), "0.05 блока — ещё не движение");
        tracker.markMoved(now);
        assertEquals(0L, tracker.stationaryMillis(now));
        assertFalse(tracker.isStationary(now, 1L), "течение двигает трекер — он не неподвижен");
    }

    @Test
    void sunkTrackerIsNotLiveAndDoesNotOfferRoute() {
        long now = 200_000L;
        Tracker tracker = tracker(now - 600_000L, true, false);
        assertEquals(Tracker.Status.SUNK, tracker.status());
        assertFalse(tracker.hasSignal());
        assertFalse(tracker.isStationary(now, 60_000L), "затонувшему предлагать маршрут незачем");
    }

    @Test
    void sunkWaterproofTrackerKeepsSignalAndOffersRoute() {
        long now = 200_000L;
        Tracker tracker = tracker(now - 600_000L, true);
        assertEquals(Tracker.Status.LIVE, tracker.status(),
                "водонепроницаемый под водой продолжает передавать сигнал");
        assertTrue(tracker.hasSignal());
        assertTrue(tracker.isStationary(now, 60_000L),
                "маршрут к утонувшему водонепроницаемому трекеру так же полезен");
    }

    @Test
    void wateredFlagDropsWhenTrackerSinks() {
        Tracker tracker = tracker(10L, false);
        tracker.setWater(true);
        assertTrue(tracker.isWater());
        tracker.setSunk(true);
        assertFalse(tracker.isWater(), "утонувший трекер не дрейфует по течению");
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
    void playerCarrierCanBeOfflineWithTrackerStillAttached() {
        UUID playerId = UUID.randomUUID();
        Tracker tracker = new Tracker(3, "TRK-0003", "Маячок", TrackerColor.CYAN, false,
                UUID.randomUUID(), "tester", "world", 5, 64, 5, playerId, false, 1L, 1L, 1L);
        assertFalse(tracker.isCarrierTypeKnown(), "тип носителя ещё не определён");
        tracker.setCarrierPlayer(true);
        assertTrue(tracker.isCarrierPlayer());
        assertTrue(tracker.isCarrierTypeKnown());
        tracker.setSignal(false);
        assertTrue(tracker.isCarrierOffline(), "игрок вышел — трекер на нём, но офлайн");
        tracker.setSignal(true);
        assertFalse(tracker.isCarrierOffline(), "игрок в сети — трекер не офлайн");
        tracker.setCarrierPlayer(false);
        tracker.setSignal(false);
        assertFalse(tracker.isCarrierOffline(), "моб/транспорт офлайн-статус не получает");
    }

    @Test
    void sunkFlagWinsOverRestoredSignalForRegularTracker() {
        Tracker tracker = tracker(10L, true, false);
        tracker.setSignal(true);
        assertFalse(tracker.hasSignal(), "обычному под водой сигнал не вернуть до подъёма");
    }

    @Test
    void sunkWaterproofTrackerKeepsRestoredSignal() {
        Tracker tracker = tracker(10L, true);
        tracker.setSignal(true);
        assertTrue(tracker.hasSignal(), "водонепроницаемому сигнал под водой не мешает");
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
    void lostSignalMeansNoRouteOffer() {
        long now = 200_000L;
        Tracker tracker = tracker(now - 600_000L, false);
        tracker.setSignal(false);
        assertEquals(Tracker.Status.NO_SIGNAL, tracker.status());
        assertFalse(tracker.hasSignal());
        assertFalse(tracker.isStationary(now, 60_000L), "без сигнала маршрут не предлагаем");
    }

    @Test
    void supportBlockIsMatchedExactly() {
        Tracker tracker = new Tracker(1, "TRK-BLK1", "", TrackerColor.BLUE, false,
                UUID.randomUUID(), "tester", "world", 10.5, 64.5, 5.5, null, false,
                1L, 2L, 3L, new Tracker.SupportBlock(10, 64, 5));
        assertTrue(tracker.isOnBlock());
        assertTrue(tracker.isOnBlock(10, 64, 5));
        assertFalse(tracker.isOnBlock(10, 64, 6));
        assertEquals(new Tracker.SupportBlock(10, 64, 5), tracker.getSupportBlock());
    }

    @Test
    void formattedCoordsAreStable() {
        Tracker tracker = tracker(1L, false);
        assertEquals("10.0, 64.0, 20.0", tracker.getFormattedCoords());
    }
}
