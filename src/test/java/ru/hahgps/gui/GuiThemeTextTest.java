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
        assertEquals("<#1d51a8>Назад", GuiTheme.legacyToMini("§9Назад"));
        assertEquals("<white>знач", GuiTheme.legacyToMini("§fзнач"));
        assertEquals("<gray>пояснение", GuiTheme.legacyToMini("§7пояснение"));
        assertEquals("<#3f86ff>ошибка", GuiTheme.legacyToMini("§cошибка"));

        assertEquals("<#8fc6ff>X", GuiTheme.legacyToMini("§aX"));
        assertEquals("<#8fe9f5>X", GuiTheme.legacyToMini("§eX"));
        assertEquals("<#66aaff>X", GuiTheme.legacyToMini("§dX"));
        assertEquals("<#3f86ff>X", GuiTheme.legacyToMini("§2X"));
        assertEquals("<#2464c8>X", GuiTheme.legacyToMini("§5X"));
        assertEquals("<#3f86ff>X", GuiTheme.legacyToMini("§4X"));
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
        String clipped = GuiTheme.clipMini("<#8fc6ff><bold>Очень длинное название точки</bold></#8fc6ff>", 10);
        assertTrue(clipped.startsWith("<#8fc6ff><bold>"), clipped);
        assertTrue(clipped.endsWith("</bold></#8fc6ff>"), "теги не закрыты: " + clipped);
        String plainClipped = plain(GuiTheme.safe(clipped));
        assertTrue(plainClipped.endsWith("…"), "нет троеточия: " + plainClipped);
        assertTrue(plainClipped.length() <= 11, "не обрезано: " + plainClipped);

        String shortOne = GuiTheme.clipMini("<white>Точка</white>", 30);
        assertEquals("<white>Точка</white>", shortOne);
    }


    @Test
    void paletteIsASingleBlueLine() {
        assertTrue(java.util.Arrays.equals(GuiTheme.TONES, GuiTheme.PALETTE),
                "палитра разошлась с синей линейкой: " + String.join(",", GuiTheme.PALETTE));

        for (String tone : GuiTheme.PALETTE) {
            assertTrue(tone.matches("#[0-9a-f]{6}"), "тон вне RGB-формата: " + tone);
            int r = Integer.parseInt(tone.substring(1, 3), 16);
            int g = Integer.parseInt(tone.substring(3, 5), 16);
            int b = Integer.parseInt(tone.substring(5, 7), 16);
            assertTrue(b >= g && g >= r, "тон ушёл в не-синий: " + tone);
        }

        for (String foreign : new String[]{"#4ade80", "#22c55e", "#a3e635", "#f87171", "#ef4444",
                "#fb923c", "#f97316", "#fbbf24", "#fcd34d", "#c084fc", "#a855f7", "#f472b6",
                "#2dd4bf", "#38bdf8", "#818cf8", "#22d3ee", "#3b82f6"}) {
            assertFalse(java.util.Arrays.asList(GuiTheme.PALETTE).contains(foreign),
                    "в палитру вернулся чужой оттенок: " + foreign);
        }
    }


    @Test
    void semanticTonesBelongToThePalette() {
        for (String tone : new String[]{GuiTheme.OK, GuiTheme.INFO, GuiTheme.VALUE,
                GuiTheme.STEP, GuiTheme.ERR}) {
            assertTrue(java.util.Arrays.asList(GuiTheme.PALETTE).contains(tone),
                    "служебный тон вне палитры: " + tone);
        }
        assertEquals("<" + GuiTheme.OK + ">✔</" + GuiTheme.OK + ">", GuiTheme.OK_MARK);
        assertEquals("<" + GuiTheme.ERR + ">✖</" + GuiTheme.ERR + ">", GuiTheme.ERR_MARK);
        assertEquals("<" + GuiTheme.INFO + ">→</" + GuiTheme.INFO + ">", GuiTheme.PROMPT_MARK);
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
