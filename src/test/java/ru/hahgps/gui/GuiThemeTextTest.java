package ru.hahgps.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Текстовые помощники GuiTheme работают без сервера: legacy §-коды, экранирование,
 * приведение hex-тегов к парности. Ловит регрессии вроде «\&gt; остаётся слэшем».
 */
class GuiThemeTextTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void legacyCodesBecomePaletteTags() {
        assertEquals("<#4fd1e0>Заголовок", GuiTheme.legacyToMini("§6Заголовок"));
        assertEquals("<blue>Назад", GuiTheme.legacyToMini("§9Назад"));
        assertEquals("<white>знач", GuiTheme.legacyToMini("§fзнач"));
        assertEquals("<gray>пояснение", GuiTheme.legacyToMini("§7пояснение"));
        assertEquals("<red>ошибка", GuiTheme.legacyToMini("§cошибка"));
        // запрещённые палитрой тона уходят в ближайший тон
        assertEquals("<#8fc6ff>X", GuiTheme.legacyToMini("§aX"));
        assertEquals("<#8fe9f5>X", GuiTheme.legacyToMini("§eX"));
        assertEquals("<#66aaff>X", GuiTheme.legacyToMini("§dX"));
        assertEquals("<#3f86ff>X", GuiTheme.legacyToMini("§2X"));
        assertEquals("<#2464c8>X", GuiTheme.legacyToMini("§5X"));
        assertEquals("<red>X", GuiTheme.legacyToMini("§4X"));
    }

    @Test
    void legacyHexSequenceIsParsed() {
        assertEquals("<color:#1d51a8>X", GuiTheme.legacyToMini("§x§1§d§5§1§a§8X"));
    }

    @Test
    void unknownLegacyCodeDoesNotLeakIntoMarkup() {
        // «§» обязан исчезнуть: остаток в MiniMessage — исключение
        String out = GuiTheme.legacyToMini("§qX");
        assertFalse(out.contains("§"), "§ остался в строке: " + out);
        assertEquals("qX", out);
    }

    @Test
    void readyMadeTagsAreNotEscaped() {
        assertEquals("<color:#8fc6ff>X</color>", GuiTheme.legacyToMini("<color:#8fc6ff>X</color>"));
    }

    @Test
    void angleBracketEscapingOnlyEscapesLessThan() {
        // MiniMessage понимает "\<", но НЕ "\>" — экранировать «>» нельзя
        assertEquals("test\\<red>x>", GuiTheme.escapeForMini("test<red>x>"));
        assertEquals("test<red>x>", plain(GuiTheme.mm(GuiTheme.toned(9, "test<red>x>"))));
        assertEquals("A > B", plain(GuiTheme.mm(GuiTheme.toned(9, "A > B"))));
        assertEquals("a\\\\b", GuiTheme.escapeForMini("a\\b"));
    }

    @Test
    void normalizeHexFixesMismatchedAndExtraClosingTags() {
        assertEquals("<#8fc6ff>a</#8fc6ff>", GuiTheme.normalizeHex("<#8fc6ff>a</#3f86ff>"));
        assertEquals("Ок", GuiTheme.normalizeHex("Ок</#8fc6ff>"));
        // чистая строка не трогается
        assertEquals("<#8fc6ff>a</#8fc6ff>", GuiTheme.normalizeHex("<#8fc6ff>a</#8fc6ff>"));
        assertEquals("<gradient:#2464c8:#8fe9f5>GPS</gradient>",
                GuiTheme.normalizeHex("<gradient:#2464c8:#8fe9f5>GPS</gradient>"));
    }

    @Test
    void safeNeverLeaksMarkupIntoPlainText() {
        String broken = plain(GuiTheme.safe("<#8fc6ff>Открыть</#3f86ff>"));
        assertEquals("Открыть", broken, "закрывающий тег светится в чате: " + broken);
        assertFalse(broken.contains("<") || broken.contains(">"), broken);
    }

    @Test
    void plainOfStripsMarkupForButtons() {
        assertEquals("← Назад", GuiTheme.plainOf("<#3f86ff>← Назад</#3f86ff>"));
        assertEquals("Страница 1/3", GuiTheme.plainOf("<gray>Страница 1/3</gray>"));
    }

    @Test
    void toneIndexRingsAroundPalette() {
        assertEquals(GuiTheme.TONES[0], GuiTheme.tone(10));
        assertEquals(GuiTheme.TONES[9], GuiTheme.tone(-1));
        assertEquals(GuiTheme.TONES[7], GuiTheme.tone(7));
        assertTrue(GuiTheme.toneColor(9).asHexString().equalsIgnoreCase("#8FE9F5"));
    }

    @Test
    void gradientsAndTitlesKeepTextIntact() {
        assertEquals("HahGPS", plain(GuiTheme.mm(GuiTheme.gradient("HahGPS"))));
        assertEquals("Имя", plain(GuiTheme.mm(GuiTheme.toned(9, "Имя"))));
        assertEquals("Главное", plain(GuiTheme.gradientTitle("Главное")));
        assertEquals("<color:#66aaff>версия</color> 1.0.4",
                GuiTheme.toned(6, "версия") + " 1.0.4");
    }
}
