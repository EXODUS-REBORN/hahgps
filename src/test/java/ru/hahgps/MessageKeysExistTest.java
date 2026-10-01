package ru.hahgps;

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ключи сообщений, которые запрашивает код, обязаны существовать в messages_*.yml —
 * иначе игрок увидит «Missing: key». Плюс сверка RU/EN наборов ключей.
 */
class MessageKeysExistTest {

    private static final Path JAVA = Path.of("src", "main", "java");
    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final String KEY = "([a-z][a-zA-Z0-9_.\\-]*)";

    /** Ключи, которые не являются сообщениями (JSON-поля и префиксы). */
    private static final Set<String> IGNORE = Set.of(
            "id", "name", "assets", "draft", "prerelease", "tag_name", "gui", "help-",
            "version", "url", "message", "status", "token", "zipball_url", "body");

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("getMessages\\(\\)\\s*\\.\\s*(?:get|getPrefixed|getComponent)\\(\"" + KEY + "\""),
            Pattern.compile("\\bmessages\\s*\\.\\s*(?:get|getPrefixed|getComponent)\\(\"" + KEY + "\""),
            Pattern.compile("\\b(?:comp|lore|getMsg)\\(\"" + KEY + "\""),
            Pattern.compile("getPrefixed\\(\"" + KEY + "\""));

    private static Set<String> codeKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(JAVA)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                for (Pattern p : PATTERNS) {
                    Matcher m = p.matcher(text);
                    while (m.find()) {
                        String key = m.group(1);
                        if (key.endsWith("-") || IGNORE.contains(key)) continue;
                        keys.add(key);
                    }
                }
            }
        }
        return keys;
    }

    private static Set<String> yamlKeys(String lang) throws IOException {
        Path file = RESOURCES.resolve("messages_" + lang + ".yml");
        Set<String> keys = new HashSet<>();
        boolean inGui = false;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Matcher top = Pattern.compile("^([a-zA-Z0-9_.\\-]+):").matcher(line);
            if (top.find()) {
                inGui = top.group(1).equals("gui");
                keys.add(top.group(1));
                continue;
            }
            if (inGui) {
                Matcher nested = Pattern.compile("^\\s{2}([a-zA-Z0-9_.\\-]+):").matcher(line);
                if (nested.find()) {
                    keys.add("gui." + nested.group(1));
                    continue;
                }
            }
            if (Pattern.compile("^\\S").matcher(line).find()) inGui = false;
        }
        return keys;
    }

    @Test
    void everyKeyUsedInCodeExistsInBothLanguages() throws IOException {
        Set<String> used = codeKeys();
        assertTrue(used.size() > 30, "regex не нашёл ключи в коде: " + used);
        Set<String> missing = new TreeSet<>();
        for (String lang : List.of("ru", "en")) {
            Set<String> defined = yamlKeys(lang);
            for (String key : used) {
                if (!defined.contains(key)) missing.add(lang + ": " + key);
            }
        }
        assertTrue(missing.isEmpty(), "ключи отсутствуют в сообщениях: " + missing);
    }

    @Test
    void requiredCommandsMessagesPresent() throws IOException {
        Set<String> ru = yamlKeys("ru");
        List<String> required = List.of(
                "prefix", "no-permission", "player-only", "usage", "invalid-args",
                "point-created", "point-deleted", "point-not-found", "point-info",
                "category-created", "category-deleted", "category-not-found",
                "category-exists", "cannot-delete-default",
                "route-started", "route-stopped", "no-active-route", "arrival",
                "search-header", "search-no-results", "reloaded", "error-occurred",
                "loading-data", "world-not-found", "distance-format", "distance-format-km",
                "help-header", "help-entry",
                "gui.main-menu", "gui.back", "gui.close", "gui.page-info",
                "input-search", "input-rate", "input-category-create", "input-category-delete");
        List<String> missing = new ArrayList<>();
        for (String key : required) {
            if (!ru.contains(key)) missing.add(key);
        }
        assertTrue(missing.isEmpty(), "нет обязательных ключей: " + missing);
    }

    @Test
    void paletteUsesTheSameTonesAsHahtojer() {
        String[] expected = {
                "#08122b", "#0e2450", "#14397e", "#1d51a8", "#2464c8",
                "#3f86ff", "#66aaff", "#8fc6ff", "#4fd1e0", "#8fe9f5"};
        assertTrue(java.util.Arrays.equals(expected, GuiTheme.TONES),
                "TONES разошлись с палитрой hahtojer: " + String.join(",", GuiTheme.TONES));
        assertTrue(GuiTheme.GRADIENT_START.equals("#0e2450")
                && GuiTheme.GRADIENT_MID.equals("#2464c8")
                && GuiTheme.GRADIENT_TAIL.equals("#8fe9f5"));
    }
}
