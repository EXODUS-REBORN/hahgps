package ru.hahgps.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;


class MessagesMigrationTest {

    @Test
    void offPaletteTonesAreRemapped() {
        assertEquals("<#8fe9f5>Х</#8fe9f5>", MessagesManager.remapPalette("<#b8dcff>Х</#b8dcff>"));
        assertEquals("<#66aaff>Я</#66aaff>", MessagesManager.remapPalette("<#c8e4ff>Я</#c8e4ff>"));
        assertEquals("<#8fe9f5>Ц</#8fe9f5>", MessagesManager.remapPalette("<#e8f4ff>Ц</#e8f4ff>"));
    }

    @Test
    void oldPrefixBecomesTheBrandedGradient() {
        String migrated = MessagesManager.remapPalette("<#8fc6ff>[GPS]<reset> ");
        assertEquals("<gray>[</gray><bold><gradient:#2464c8:#8fe9f5>GPS</gradient></bold><gray>]</gray> ",
                migrated);
        assertFalse(migrated.contains("[GPS]<reset>"), "старый префикс не заменён");
    }

    @Test
    void oldTinyFontPrefixBecomesTheBoldOne() {
        String oldPrefix = "<gray>[</gray><font:minecraft:alt><gradient:#2464c8:#8fe9f5>GPS</gradient></font><gray>]</gray>";
        String migrated = MessagesManager.remapPalette(oldPrefix + " текст");
        assertEquals("<gray>[</gray><bold><gradient:#2464c8:#8fe9f5>GPS</gradient></bold><gray>]</gray> текст",
                migrated);
        assertFalse(migrated.contains("<font:"), "alt-шрифт остался в префиксе: " + migrated);
    }

    @Test
    void cleanStringsAreUntouched() {
        String clean = "<gray>[</gray><bold><gradient:#2464c8:#8fe9f5>GPS</gradient></bold><gray>]</gray> "
                + "<#f87171>Ошибка</#f87171> <white>Значение</white>";
        assertEquals(clean, MessagesManager.remapPalette(clean));
    }
}
