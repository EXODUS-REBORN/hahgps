package ru.hahgps.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import ru.hahgps.gui.GuiTheme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


class MessagesPaletteTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern RAW_MARKUP = Pattern.compile("</?[a-zA-Z#][^>]*>", Pattern.DOTALL);
    private static final Pattern NAMED_TAG = Pattern.compile("</?([a-z_]+)>");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*}");


    private static final Set<String> FORBIDDEN =
            Set.of("gold", "green", "yellow", "light_purple", "dark_green", "dark_red", "dark_purple", "red");


    private static final Set<String> ALLOWED = Set.of(
            "white", "gray", "dark_gray", "black",
            "bold", "italic", "underlined", "strikethrough", "obfuscated", "reset",
            "gradient", "color", "colour", "click", "hover", "key", "newline", "font");


    private static final Set<String> ALLOWED_FONTS = Set.of("minecraft:uniform", "uniform");


    private static final Set<String> SIZE_ACCENT_PLACEHOLDERS = Set.of("{date}", "{time}", "{version}");


    private static final Set<String> NUMERIC_PLACEHOLDERS = Set.of(
            "{x}", "{y}", "{z}", "{rating}", "{distance}", "{match}", "{percent}",
            "{page}", "{pages}", "{count}", "{votes}", "{value}");

    private static final Pattern FONT_TAG =
            Pattern.compile("<font:([^>]+)>(.*?)</font>", Pattern.DOTALL);

    private static final Pattern ANY_FONT_OPEN =
            Pattern.compile("<font:([^>]+)>");

    private static List<String[]> parse(Path file) throws IOException {
        List<String[]> out = new ArrayList<>();
        String pendingBlock = null;
        StringBuilder block = null;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (pendingBlock != null) {
                if (line.startsWith("  ") || line.isBlank()) {
                    if (block == null) block = new StringBuilder();
                    block.append(line).append('\n');
                    continue;
                }
                out.add(new String[]{pendingBlock, block == null ? "" : block.toString()});
                pendingBlock = null;
                block = null;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int idx = trimmed.indexOf(':');
            if (idx <= 0) continue;
            String key = trimmed.substring(0, idx);
            String value = trimmed.substring(idx + 1).trim();
            if (value.equals("|") || value.equals("|-")) {
                pendingBlock = key;
                continue;
            }
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1).replace("\\\"", "\"");
            }
            out.add(new String[]{key, value});
        }
        if (pendingBlock != null) out.add(new String[]{pendingBlock, block == null ? "" : block.toString()});
        return out;
    }

    private static Set<String> keysOf(Path file) throws IOException {
        Set<String> keys = new TreeSet<>();
        for (String[] kv : parse(file)) keys.add(kv[0]);
        return keys;
    }

    private static String stripTags(String mini) {
        return mini.replaceAll("</?[a-zA-Z#][^>]*>", "");
    }

    @Test
    void paletteVersionIsCurrent() throws IOException {
        for (String lang : List.of("ru", "en")) {
            Path file = RESOURCES.resolve("messages_" + lang + ".yml");
            boolean found = false;
            for (String[] kv : parse(file)) {
                if ("palette-version".equals(kv[0])) {
                    found = true;
                    assertEquals(String.valueOf(MessagesManager.PALETTE_VERSION), kv[1].trim(),
                            lang + ": устаревшая схема файла сообщений");
                }
            }
            assertTrue(found, lang + ": нет ключа palette-version");
        }
    }

    @Test
    void onlyPaletteColorsAndAllowedTags() throws IOException {
        Set<String> palette = new HashSet<>(List.of(GuiTheme.PALETTE));
        for (String lang : List.of("ru", "en")) {
            for (String[] kv : parse(RESOURCES.resolve("messages_" + lang + ".yml"))) {
                String key = kv[0], value = kv[1];

                assertFalse(value.contains("§"), lang + " " + key + ": legacy § в сообщении");

                Matcher hex = HEX.matcher(value);
                while (hex.find()) {
                    assertTrue(palette.contains(hex.group().toLowerCase()),
                            lang + " " + key + ": цвет вне RGB-палитры " + hex.group());
                }

                Matcher tag = NAMED_TAG.matcher(value);
                while (tag.find()) {
                    String name = tag.group(1);
                    if (name.startsWith("#")) continue;
                    assertFalse(FORBIDDEN.contains(name),
                            lang + " " + key + ": именованный цвет <" + name + "> — используй RGB-хекс из палитры");
                    assertTrue(ALLOWED.contains(name) || name.matches("#[0-9a-fA-F]{6}"),
                            lang + " " + key + ": неизвестный тег <" + name + ">");
                }
            }
        }
    }

    /**
     * ОДИН ШАБЛОН: в текстах только синие тона (b >= g >= r).
     * Зелёные/красные/золотые/фиолетовые оттенки запрещены —
     * иначе плагин снова «раскрасится» по разным углам.
     */
    @Test
    void everyMessageStaysInTheBlueHue() throws IOException {
        for (String lang : List.of("ru", "en")) {
            for (String[] kv : parse(RESOURCES.resolve("messages_" + lang + ".yml"))) {
                Matcher hex = HEX.matcher(kv[1]);
                while (hex.find()) {
                    String tone = hex.group().toLowerCase();
                    int r = Integer.parseInt(tone.substring(1, 3), 16);
                    int g = Integer.parseInt(tone.substring(3, 5), 16);
                    int b = Integer.parseInt(tone.substring(5, 7), 16);
                    assertTrue(b >= g && g >= r,
                            lang + " " + kv[0] + ": оттенок " + tone + " вне синей линейки");
                }
            }
        }
    }

    /** Все кнопки меню — из тех же четырёх тонов, без радуги по разделам. */
    @Test
    void guiButtonsShareTheSameTones() throws IOException {
        Set<String> allowed = Set.of(
                GuiTheme.OK, GuiTheme.VALUE, GuiTheme.ERR, GuiTheme.INFO);
        for (String lang : List.of("ru", "en")) {
            for (String[] kv : parse(RESOURCES.resolve("messages_" + lang + ".yml"))) {
                if (!kv[0].startsWith("gui.")) continue;
                Matcher hex = HEX.matcher(kv[1]);
                while (hex.find()) {
                    assertTrue(allowed.contains(hex.group().toLowerCase()),
                            lang + " " + kv[0] + ": кнопка использует тон " + hex.group()
                                    + " вне общего шаблона");
                }
            }
        }
    }

    @Test
    void everyMessageParsesWithoutRawMarkupLeftovers() throws IOException {
        MiniMessage mm = MiniMessage.miniMessage();
        for (String lang : List.of("ru", "en")) {
            for (String[] kv : parse(RESOURCES.resolve("messages_" + lang + ".yml"))) {
                String key = kv[0], value = kv[1];
                if (value.isEmpty()) continue;
                Component component;
                try {
                    component = mm.deserialize(value);
                } catch (Throwable t) {
                    fail(lang + " " + key + ": MiniMessage не разобрал строку: " + t);
                    return;
                }
                String plain = PlainTextComponentSerializer.plainText().serialize(component);
                assertFalse(RAW_MARKUP.matcher(plain).find(),
                        lang + " " + key + ": в тексте остался тег: '" + plain + "'");
                assertFalse(plain.contains("\\"), lang + " " + key + ": лишний слэш: '" + plain + "'");
            }
        }
    }

    @Test
    void russianAndEnglishKeySetsMatch() throws IOException {
        Set<String> ru = keysOf(RESOURCES.resolve("messages_ru.yml"));
        Set<String> en = keysOf(RESOURCES.resolve("messages_en.yml"));
        Set<String> onlyRu = new TreeSet<>(ru);
        onlyRu.removeAll(en);
        Set<String> onlyEn = new TreeSet<>(en);
        onlyEn.removeAll(ru);
        assertTrue(onlyRu.isEmpty(), "ключ только в messages_ru.yml: " + onlyRu);
        assertTrue(onlyEn.isEmpty(), "ключ только в messages_en.yml: " + onlyEn);
    }


    @Test
    void fontTagsAreClosedAndUseVanillaKeys() throws IOException {
        for (String lang : List.of("ru", "en")) {
            for (String[] kv : parse(RESOURCES.resolve("messages_" + lang + ".yml"))) {
                String key = kv[0], value = kv[1];
                long opens = ANY_FONT_OPEN.matcher(value).results().count();
                long closes = value.split("</font>", -1).length - 1;
                assertEquals(opens, closes, lang + " " + key + ": незакрытый <font> (открыто "
                        + opens + ", закрыто " + closes + ")");

                Matcher open = ANY_FONT_OPEN.matcher(value);
                while (open.find()) {
                    String fontKey = open.group(1).trim();
                    assertTrue(ALLOWED_FONTS.contains(fontKey),
                            lang + " " + key + ": неизвестный шрифт <font:" + fontKey + ">");
                }
            }
        }
    }


    @Test
    void sizeAccentOnlyForTimeAndVersions() throws IOException {
        for (String lang : List.of("ru", "en")) {
            for (String[] kv : parse(RESOURCES.resolve("messages_" + lang + ".yml"))) {
                String key = kv[0], value = kv[1];
                Matcher m = FONT_TAG.matcher(value);
                while (m.find()) {
                    String text = stripTags(m.group(2));
                    boolean allowed = SIZE_ACCENT_PLACEHOLDERS.stream().anyMatch(text::contains);
                    assertTrue(allowed, lang + " " + key
                            + ": мини-шрифт допустим только для времени и версий, а обёрнут '"
                            + text + "'");
                }
            }
        }
    }


    @Test
    void numbersNeverUseTheTinyFont() throws IOException {
        for (String lang : List.of("ru", "en")) {
            for (String[] kv : parse(RESOURCES.resolve("messages_" + lang + ".yml"))) {
                String key = kv[0], value = kv[1];
                for (String num : NUMERIC_PLACEHOLDERS) {
                    int idx = value.indexOf(num);
                    if (idx < 0) continue;
                    String before = value.substring(0, idx);
                    int lastFont = before.lastIndexOf("<font:");
                    if (lastFont < 0) continue;
                    assertEquals(-1, before.indexOf("</font>", lastFont),
                            lang + " " + key + ": число " + num + " не должно быть в мини-шрифте");
                }
            }
        }
    }


    @Test
    void lineTemplatesAreUniform() throws IOException {
        for (String lang : List.of("ru", "en")) {
            for (String[] kv : parse(RESOURCES.resolve("messages_" + lang + ".yml"))) {
                String key = kv[0];
                for (String line : kv[1].split("\n")) {
                    if (line.isBlank()) continue;
                    String plain = stripTags(line).trim();
                    if (plain.isEmpty()) continue;
                    String trimmed = line.trim();

                    if (plain.startsWith("✔")) {
                        assertTrue(trimmed.startsWith("<#8fe9f5>✔") || trimmed.startsWith("<#8fe9f5><bold>✔"),
                                lang + " " + key + ": успех должен начинаться с <#8fe9f5>✔: '" + trimmed + "'");
                    }
                    if (plain.startsWith("✖")) {
                        assertTrue(trimmed.startsWith("<#3f86ff>✖") || trimmed.startsWith("<#3f86ff><bold>✖"),
                                lang + " " + key + ": ошибка должна начинаться с <#3f86ff>✖: '" + trimmed + "'");
                    }
                    if (plain.startsWith("→") && key.startsWith("input-")) {
                        assertTrue(trimmed.startsWith("<#66aaff>→") || trimmed.startsWith("<#66aaff><bold>→"),
                                lang + " " + key + ": подсказка должна начинаться с <#66aaff>→: '" + trimmed + "'");
                    }
                    if (key.startsWith("hint-")) {
                        assertTrue(trimmed.startsWith("<#") && trimmed.contains("<bold>"),
                                lang + " " + key + ": подсказка направления — цвет секции + <bold>: '" + trimmed + "'");
                    }
                    if (plain.contains("══")) {
                        assertTrue(trimmed.startsWith("<bold>"),
                                lang + " " + key + ": шапка с ═══ должна быть в <bold>: '" + trimmed + "'");
                    }
                }
            }
        }
    }


    @Test
    void guiTextsFitTheirBoxes() throws IOException {
        for (String lang : List.of("ru", "en")) {
            for (String[] kv : parse(RESOURCES.resolve("messages_" + lang + ".yml"))) {
                String key = kv[0], value = kv[1];
                if (!key.startsWith("gui.")) continue;
                int max = key.startsWith("gui.lore-") ? GuiTheme.LORE_MAX : GuiTheme.NAME_MAX;
                String plain = stripTags(value);
                plain = PLACEHOLDER.matcher(plain).replaceAll("");
                for (String line : plain.split("\n")) {
                    String text = line.trim();
                    if (text.isEmpty()) continue;
                    assertTrue(text.length() <= max, lang + " " + key
                            + ": текст '" + text + "' (" + text.length() + ") вылезает за границы ("
                            + max + ")");
                }
            }
        }
    }
}
