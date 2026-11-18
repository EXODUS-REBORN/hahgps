package ru.hahgps.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Название трекера приходит от игрока (команда, наковальня, ввод в меню),
 * поэтому проверяется, что в подписи не остаётся разметки и мусора, а длина
 * не рвёт строку посреди символа.
 */
class TrackerNameTest {

    @Test
    void emptyAndNullBecomeEmptyName() {
        assertEquals("", TrackerService.sanitizeName(null));
        assertEquals("", TrackerService.sanitizeName("   "));
    }

    @Test
    void miniMessageTagsAreStripped() {
        assertEquals("Склад", TrackerService.sanitizeName("<red>Склад</red>"));
        assertEquals("Дом", TrackerService.sanitizeName("<gradient:#2464c8:#8fe9f5>Дом</gradient>"));
        assertEquals("iTrak", TrackerService.sanitizeName("<bold><color:#8fc6ff>iTrak</color></bold>"));
    }

    @Test
    void whitespaceAndControlCharsCollapse() {
        assertEquals("Мой склад", TrackerService.sanitizeName("  Мой\n\t склад  "));
        assertEquals("Дом", TrackerService.sanitizeName("§cДом"));
        assertEquals("а б", TrackerService.sanitizeName("а\u0000\u0007 б"));
    }

    @Test
    void longNameIsClippedWithoutBrokenSurrogate() {
        String longName = "я".repeat(TrackerService.NAME_LIMIT + 20);
        assertEquals(TrackerService.NAME_LIMIT, TrackerService.sanitizeName(longName).length());

        // Суррогатная пара (эмодзи) не должна разрезаться пополам.
        String emoji = "🙂".repeat(TrackerService.NAME_LIMIT + 5);
        String clipped = TrackerService.sanitizeName(emoji);
        assertTrue(clipped.codePointCount(0, clipped.length()) <= TrackerService.NAME_LIMIT);
        assertEquals(emoji.substring(0, clipped.length()), clipped);
    }
}
