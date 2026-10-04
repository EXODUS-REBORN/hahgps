package ru.hahgps.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


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

        assertEquals("test\\<red>x>", GuiTheme.escapeForMini("test<red>x>"));
        assertEquals("test<red>x>", plain(GuiTheme.mm(GuiTheme.toned(9, "test<red>x>"))));
        assertEquals("A > B", plain(GuiTheme.mm(GuiTheme.toned(9, "A > B"))));
        assertEquals("a\\\\b", GuiTheme.escapeForMini("a\\b"));
    }

    @Test
    void normalizeHexFixesMismatchedAndExtraClosingTags() {
        assertEquals("<#8fc6ff>a</#8fc6ff>", GuiTheme.normalizeHex("<#8fc6ff>a</#3f86ff>"));
        assertEquals("Ок", GuiTheme.normalizeHex("Ок</#8fc6ff>"));

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


    @Test
    void fontHelperKeepsTextAndSetsFontStyle() {
        Component uniform = GuiTheme.mm(GuiTheme.font(GuiTheme.FONT_UNIFORM, "<white>123</white>"));
        assertEquals("123", plain(uniform));
        assertEquals(net.kyori.adventure.key.Key.key("minecraft", "uniform"), fontOf(uniform));

        Component micro = GuiTheme.mm(GuiTheme.micro("<white>1.0.6</white>"));
        assertEquals("1.0.6", plain(micro));
        assertEquals(net.kyori.adventure.key.Key.key("minecraft", "uniform"), fontOf(micro));
    }


    @Test
    void gradientTitleIsBoldWithoutTinyFont() {
        Component title = GuiTheme.gradientTitle("Мои точки");
        assertEquals("Мои точки", plain(title));
        assertTrue(fontOf(title) == null, "заголовок окна не должен быть в мини-шрифте");
        assertTrue(hasBold(title), "заголовок окна должен быть жирным");
    }


    @Test
    void clipPlainCutsWithEllipsis() {
        assertEquals("Краткое", GuiTheme.clipPlain("Краткое", 28));
        assertEquals("", GuiTheme.clipPlain(null, 10));
        String clipped = GuiTheme.clipPlain("Очень длинное название точки", 23);
        assertTrue(clipped.endsWith("…"), "нет троеточия: " + clipped);
        assertTrue(clipped.length() <= 23, "не обрезано: " + clipped);
    }


    @Test
    void clipMiniClosesTagsAndStaysInsideTheBox() {
        String clipped = GuiTheme.clipMini("<#22d3ee><bold>Очень длинное название точки</bold></#22d3ee>", 10);
        assertTrue(clipped.startsWith("<#22d3ee><bold>"), clipped);
        assertTrue(clipped.endsWith("</bold></#22d3ee>"), "теги не закрыты: " + clipped);
        String plainClipped = plain(GuiTheme.safe(clipped));
        assertTrue(plainClipped.endsWith("…"), "нет троеточия: " + plainClipped);
        assertTrue(plainClipped.length() <= 11, "не обрезано: " + plainClipped);

        String shortOne = GuiTheme.clipMini("<white>Точка</white>", 30);
        assertEquals("<white>Точка</white>", shortOne);
    }


    @Test
    void paletteContainsTheFullRgbRange() {
        assertTrue(GuiTheme.PALETTE.length >= 20, "палитра слишком бедная: " + GuiTheme.PALETTE.length);
        assertTrue(java.util.Arrays.asList(GuiTheme.PALETTE).contains(GuiTheme.GREEN));
        assertTrue(java.util.Arrays.asList(GuiTheme.PALETTE).contains(GuiTheme.RED));
        assertTrue(java.util.Arrays.asList(GuiTheme.PALETTE).contains(GuiTheme.AMBER));
        assertTrue(java.util.Arrays.asList(GuiTheme.PALETTE).contains(GuiTheme.PURPLE));
    }


    private static boolean hasBold(Component c) {
        if (c.style().hasDecoration(net.kyori.adventure.text.format.TextDecoration.BOLD)) return true;
        for (Component child : c.children()) {
            if (hasBold(child)) return true;
        }
        return false;
    }


    private static net.kyori.adventure.key.Key fontOf(Component c) {
        if (c.style().font() != null) return c.style().font();
        for (Component child : c.children()) {
            net.kyori.adventure.key.Key found = fontOf(child);
            if (found != null) return found;
        }
        return null;
    }
}
