package ru.hahgps.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Кнопки в чате («построить маршрут» / «отменить маршрут») — это MiniMessage с
 * {@code click:run_command}. Тест держит контракт: тег разбирается, клик есть,
 * команда правильная. Иначе кнопка в чате просто станет серым текстом.
 */
class ItrakChatButtonsTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static String message(String language, String key) throws IOException {
        String text = Files.readString(RESOURCES.resolve("messages_" + language + ".yml"),
                StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("^" + Pattern.quote(key) + ":\\s*\"(.*)\"\\s*$",
                Pattern.MULTILINE).matcher(text);
        assertTrue(matcher.find(), "нет ключа " + key + " в messages_" + language + ".yml");
        return matcher.group(1);
    }

    private static ClickEvent findClick(Component component) {
        if (component.clickEvent() != null) {
            return component.clickEvent();
        }
        for (Component child : component.children()) {
            ClickEvent event = findClick(child);
            if (event != null) {
                return event;
            }
        }
        return null;
    }

    private static void assertRouteButton(String language) throws IOException {
        String raw = message(language, "itrak-route-button").replace("{code}", "TRK-1234");
        ClickEvent click = findClick(MM.deserialize(raw));
        assertNotNull(click, "кнопка маршрута без клика: " + raw);
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.action());
        assertEquals("/gps itrak route TRK-1234", click.value());
    }

    private static void assertCancelButton(String language) throws IOException {
        String raw = message(language, "itrak-route-cancel-button");
        ClickEvent click = findClick(MM.deserialize(raw));
        assertNotNull(click, "кнопка отмены маршрута без клика: " + raw);
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.action());
        assertEquals("/gps route stop", click.value());
    }

    @Test
    void routeButtonsWorkInBothLanguages() throws IOException {
        for (String language : List.of("ru", "en")) {
            assertRouteButton(language);
            assertCancelButton(language);
        }
    }

    private static final Pattern ITRAK_ENTRY =
            Pattern.compile("(?m)^(itrak-[a-z0-9-]+):\\s*\"(.*)\"\\s*$");

    private static final Pattern CLICK_BLOCK =
            Pattern.compile("<click:run_command:'[^']*'>.*?</click>");

    /**
     * Действие в чате — всегда кнопка, а не текст команды. Раньше
     * «трекер неподвижен» печатал {@code /gps itrak route TRK-…} строкой:
     * игрок видел команду там, где везде остальном была кнопка.
     */
    @Test
    void itrakMessagesShowButtonsInsteadOfRawCommands() throws IOException {
        for (String language : List.of("ru", "en")) {
            String text = Files.readString(RESOURCES.resolve("messages_" + language + ".yml"),
                    StandardCharsets.UTF_8);
            Matcher entries = ITRAK_ENTRY.matcher(text);
            while (entries.find()) {
                String key = entries.group(1);
                String withoutButtons = CLICK_BLOCK.matcher(entries.group(2)).replaceAll("");
                assertFalse(withoutButtons.contains("/gps "), language + " " + key
                        + ": в чате сырая команда вместо кнопки: " + entries.group(2));
            }
        }
    }
}
