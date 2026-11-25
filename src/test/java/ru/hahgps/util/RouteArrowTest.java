package ru.hahgps.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Стрелки маршрута: сектора направлений и ванильные символы. Рисованных
 * глифов и своего ресурспака у плагина больше нет.
 */
class RouteArrowTest {

    @Test
    void straightSectorIsSymmetric() {
        assertEquals(RouteArrow.STRAIGHT, RouteArrow.of(0.0));
        assertEquals(RouteArrow.STRAIGHT, RouteArrow.of(15.0));
        assertEquals(RouteArrow.STRAIGHT, RouteArrow.of(-15.0));
        assertEquals(RouteArrow.STRAIGHT, RouteArrow.of(22.5));
        assertEquals(RouteArrow.STRAIGHT, RouteArrow.of(-22.5));
    }

    @Test
    void sectorsFollowAngleAndSide() {
        assertEquals(RouteArrow.SLIGHT_LEFT, RouteArrow.of(30.0));
        assertEquals(RouteArrow.SLIGHT_RIGHT, RouteArrow.of(-30.0));
        assertEquals(RouteArrow.SLIGHT_LEFT, RouteArrow.of(67.5));
        assertEquals(RouteArrow.LEFT, RouteArrow.of(90.0));
        assertEquals(RouteArrow.RIGHT, RouteArrow.of(-90.0));
        assertEquals(RouteArrow.LEFT, RouteArrow.of(112.5));
        assertEquals(RouteArrow.SHARP_LEFT, RouteArrow.of(135.0));
        assertEquals(RouteArrow.SHARP_RIGHT, RouteArrow.of(-135.0));
        assertEquals(RouteArrow.SHARP_LEFT, RouteArrow.of(157.5));
    }

    @Test
    void backAtOneHundredEighty() {
        assertEquals(RouteArrow.BACK, RouteArrow.of(170.0));
        assertEquals(RouteArrow.BACK, RouteArrow.of(-170.0));
        assertEquals(RouteArrow.BACK, RouteArrow.of(180.0));
        assertEquals(RouteArrow.BACK, RouteArrow.of(-180.0));
    }

    @Test
    void unicodeFallbackIsBmpAndUnique() {
        Set<String> unicode = new HashSet<>();
        for (RouteArrow arrow : RouteArrow.values()) {
            assertEquals(1, arrow.unicode().length(), "ванильная стрелка должна быть одним символом");
            assertTrue(arrow.unicode().charAt(0) <= 0xFFFF, "символ вне BMP: " + arrow);
            assertTrue(unicode.add(arrow.unicode()), "дубль ванильной стрелки: " + arrow);
        }
    }

    @Test
    void hintKeysMatchMessages() {
        assertEquals("hint-straight", RouteArrow.STRAIGHT.hintKey());
        assertEquals("hint-slight-left", RouteArrow.SLIGHT_LEFT.hintKey());
        assertEquals("hint-left", RouteArrow.LEFT.hintKey());
        assertEquals("hint-sharp-left", RouteArrow.SHARP_LEFT.hintKey());
        assertEquals("hint-back", RouteArrow.BACK.hintKey());
        assertEquals("hint-sharp-right", RouteArrow.SHARP_RIGHT.hintKey());
        assertEquals("hint-right", RouteArrow.RIGHT.hintKey());
        assertEquals("hint-slight-right", RouteArrow.SLIGHT_RIGHT.hintKey());
    }

    @Test
    void directionUtilUsesTheSameSector() {
        assertEquals(RouteArrow.of(90.0).unicode(), DirectionUtil.getArrow(90.0));
        assertEquals(RouteArrow.of(-120.0).hintKey(), DirectionUtil.getHintKey(-120.0));
    }
}
