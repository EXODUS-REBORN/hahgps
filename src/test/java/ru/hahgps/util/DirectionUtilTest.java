package ru.hahgps.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Старые точки входа DirectionUtil должны остаться совместимыми. */
class DirectionUtilTest {

    @Test
    void arrowsMatchTheOldTable() {
        assertEquals("↑", DirectionUtil.getArrow(0.0));
        assertEquals("↖", DirectionUtil.getArrow(45.0));
        assertEquals("↗", DirectionUtil.getArrow(-45.0));
        assertEquals("←", DirectionUtil.getArrow(90.0));
        assertEquals("→", DirectionUtil.getArrow(-90.0));
        assertEquals("↙", DirectionUtil.getArrow(135.0));
        assertEquals("↘", DirectionUtil.getArrow(-135.0));
        assertEquals("↓", DirectionUtil.getArrow(180.0));
    }

    @Test
    void hintsMatchTheArrowSector() {
        assertEquals("hint-straight", DirectionUtil.getHintKey(0.0));
        assertEquals("hint-slight-left", DirectionUtil.getHintKey(45.0));
        assertEquals("hint-left", DirectionUtil.getHintKey(90.0));
        assertEquals("hint-sharp-left", DirectionUtil.getHintKey(135.0));
        assertEquals("hint-back", DirectionUtil.getHintKey(180.0));
    }
}
