package ru.hahgps.model;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EdgeRecordTest {

    private static EdgeRecord edge() {
        return new EdgeRecord("world", 1, 64, 2, 10, 64, 5,
                EnumSet.of(TransportType.FOOT, TransportType.CAR));
    }

    @Test
    void canonicalPutsSmallerPointFirst() {
        EdgeRecord record = edge();
        EdgeRecord canonical = record.canonical();

        assertEquals(1.0, canonical.x1());
        assertEquals(10.0, canonical.x2());
        assertEquals("world", canonical.world());
        assertEquals(Set.of(TransportType.FOOT, TransportType.CAR), canonical.transports());
    }

    @Test
    void canonicalIsStableForReversedInput() {
        EdgeRecord forward = edge().canonical();
        EdgeRecord reversed = new EdgeRecord("world", 10, 64, 5, 1, 64, 2,
                EnumSet.of(TransportType.CAR, TransportType.FOOT)).canonical();

        assertEquals(forward, reversed, "канонический вид не должен зависеть от порядка концов");
    }

    @Test
    void sameEndpointsIgnoresOrderAndCase() {
        EdgeRecord record = edge();
        assertTrue(record.sameEndpoints("WORLD", 10, 64, 5, 1, 64, 2));
        assertFalse(record.sameEndpoints("other", 1, 64, 2, 10, 64, 5));
        assertFalse(record.sameEndpoints("world", 1, 64, 2, 11, 64, 5));
    }

    @Test
    void transportsRoundTrip() {
        Set<TransportType> types = EnumSet.of(TransportType.FOOT, TransportType.AIR);
        String raw = EdgeRecord.serializeTransports(types);

        assertEquals(types, EdgeRecord.parseTransports(raw));
    }

    @Test
    void unknownTransportsAreSkipped() {
        assertEquals(EnumSet.of(TransportType.FOOT),
                EdgeRecord.parseTransports("foot,teleport,bogus"));
        assertTrue(EdgeRecord.parseTransports(null).isEmpty());
        assertTrue(EdgeRecord.parseTransports("").isEmpty());
    }
}
