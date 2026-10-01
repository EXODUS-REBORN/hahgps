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

/**
 * Палитра и разметка messages_*.yml: только тона GuiTheme.TONES, нейтральные
 * цвета и красный; все теги закрыты и разбираются без «сырых» остатков в чате.
 */
class MessagesPaletteTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern RAW_MARKUP = Pattern.compile("</?[a-zA-Z#][^>]*>", Pattern.DOTALL);
    private static final Pattern NAMED_TAG = Pattern.compile("</?([a-z_]+)>");
    /** Запрещённые чужие цвета (см. docs/gui.md). */
    private static final Set<String> FORBIDDEN =
            Set.of("gold", "green", "yellow", "light_purple", "dark_green", "dark_red", "dark_purple");
    /** Разрешённые именованные теги. */
    private static final Set<String> ALLOWED = Set.of(
            "white", "gray", "dark_gray", "black", "red", "blue", "dark_blue", "dark_aqua",
            "bold", "italic", "underlined", "strikethrough", "obfuscated", "reset",
            "gradient", "color", "colour", "click", "hover", "key", "newline");

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

    @Test
    void paletteVersionIsCurrent() throws IOException {
        for (String lang : List.of("ru", "en")) {
            Path file = RESOURCES.resolve("messages_" + lang + ".yml");
            boolean found = false;
            for (String[] kv : parse(file)) {
                if ("palette-version".equals(kv[0])) {
                    found = true;
                    assertEquals("2", kv[1].trim(), lang + ": устаревшая схема палитры");
                }
            }
            assertTrue(found, lang + ": нет ключа palette-version");
        }
    }

    @Test
    void onlyPaletteTonesAndAllowedTags() throws IOException {
        Set<String> tones = new HashSet<>(List.of(GuiTheme.TONES));
        for (String lang : List.of("ru", "en")) {
            for (String[] kv : parse(RESOURCES.resolve("messages_" + lang + ".yml"))) {
                String key = kv[0], value = kv[1];

                assertFalse(value.contains("§"), lang + " " + key + ": legacy § в сообщении");

                Matcher hex = HEX.matcher(value);
                while (hex.find()) {
                    assertTrue(tones.contains(hex.group()),
                            lang + " " + key + ": цвет вне палитры " + hex.group());
                }

                Matcher tag = NAMED_TAG.matcher(value);
                while (tag.find()) {
                    String name = tag.group(1);
                    if (name.startsWith("#")) continue;
                    assertFalse(FORBIDDEN.contains(name),
                            lang + " " + key + ": запрещённый цвет <" + name + ">");
                    assertTrue(ALLOWED.contains(name) || name.matches("#[0-9a-fA-F]{6}"),
                            lang + " " + key + ": неизвестный тег <" + name + ">");
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
}
