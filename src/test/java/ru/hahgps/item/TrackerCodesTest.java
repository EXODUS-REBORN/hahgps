package ru.hahgps.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Код трекера — идентификатор одного предмета: два трекера с одним кодом
 * приводят к тому, что второй не ставится, а локатор показывает первый.
 * Тест держит контракт счётчика.
 */
class TrackerCodesTest {

    @BeforeEach
    void setUp() {
        TrackerCodes.resetForTests();
    }

    @AfterEach
    void tearDown() {
        TrackerCodes.resetForTests();
    }

    @Test
    void sequentialCodesAreUniqueAndPredictable() {
        TrackerCodes.initialize(42, 3);
        Set<String> issued = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            issued.add(TrackerCodes.next());
        }
        assertEquals(3, issued.size(), "коды повторились: " + issued);
        assertTrue(issued.stream().allMatch(code -> code.matches("TRK-000004[234]-[0-9A-F]{4}")),
                "формат кода изменился: " + issued);
        assertFalse(TrackerCodes.isReady());
    }

    @Test
    void newBlockIsRequestedWhenTheCurrentOneRunsOut() {
        List<Long> requested = new ArrayList<>();
        TrackerCodes.setBlockRequester(() -> {
            requested.add(9_000L);
            return 9_000L;
        });
        TrackerCodes.initialize(1, 2);
        assertTrue(TrackerCodes.next().startsWith("TRK-0000001-"));
        assertTrue(TrackerCodes.next().startsWith("TRK-0000002-"));
        assertTrue(TrackerCodes.next().startsWith("TRK-0009000-"));
        assertEquals(1, requested.size(), "второй блок должен запрашиваться один раз");
    }

    @Test
    void reservedCodesAreSkipped() {
        TrackerCodes.reserve("TRK-0000002-AAAA");
        TrackerCodes.initialize(1, 4);
        Set<String> issued = new HashSet<>();
        issued.add(TrackerCodes.next());
        issued.add(TrackerCodes.next());
        issued.add(TrackerCodes.next());
        assertTrue(issued.stream().noneMatch(code -> code.startsWith("TRK-0000002-")),
                "занятый номер выдан повторно: " + issued);
        assertEquals(Set.of("TRK-0000001", "TRK-0000003", "TRK-0000004"),
                issued.stream().map(code -> code.substring(0, 11)).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void withoutADatabaseTheFallbackIsStillUniqueInProcess() {
        Set<String> issued = new HashSet<>();
        for (int i = 0; i < 256; i++) {
            String code = TrackerCodes.next();
            assertTrue(code.startsWith(TrackerCodes.PREFIX), code);
            assertEquals(12, code.length(), "запасной код должен отличаться от порядкового: " + code);
            assertTrue(issued.add(code), "запасной код повторился: " + code);
        }
        assertNotEquals(TrackerCodes.format(1), TrackerCodes.next());
    }

    @Test
    void formatKeepsSevenDigits() {
        assertEquals("TRK-0000001", TrackerCodes.format(1));
        assertEquals("TRK-1234567", TrackerCodes.format(1_234_567));
        assertEquals("TRK-0000001", TrackerCodes.baseOf("TRK-0000001-7F3A"));
        assertEquals("TRK-AB12", TrackerCodes.baseOf("TRK-AB12"));
    }
}
