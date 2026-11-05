package ru.hahgps.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Аргумент выдачи локатора: число — радиус, {@code long} — дальний локатор.
 * Имя игрока не должно превращаться в радиус.
 */
class LocatorRadiusTest {

    @Test
    void numericTokenIsRadius() {
        assertEquals(15, ItemFactory.parseRadiusToken("15", 15));
        assertEquals(30, ItemFactory.parseRadiusToken(" 30 ", 15));
        assertEquals(1000, ItemFactory.parseRadiusToken("99999", 15));
    }

    @Test
    void longTokenUsesConfiguredLongRadius() {
        assertEquals(15, ItemFactory.parseRadiusToken("long", 15));
        assertEquals(22, ItemFactory.parseRadiusToken("дальний", 22));
    }

    @Test
    void playerNameIsNotARadius() {
        assertNull(ItemFactory.parseRadiusToken("Steve", 15));
        assertNull(ItemFactory.parseRadiusToken("", 15));
        assertNull(ItemFactory.parseRadiusToken(null, 15));
        assertNull(ItemFactory.parseRadiusToken("0", 15));
        assertNull(ItemFactory.parseRadiusToken("-5", 15));
    }
}
