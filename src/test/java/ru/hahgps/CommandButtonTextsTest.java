package ru.hahgps;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import ru.hahgps.gui.GuiTheme;
import ru.hahgps.util.CmdLink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandButtonTextsTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");

    private static final Set<String> ROOTS = Set.of(
            "menu", "point", "category", "goto", "search", "route", "log", "reload",
            "update", "help", "edge", "visual", "itrak", "ask", "off", "pack");

    private static List<String[]> parse(Path file) throws IOException {
        List<String[]> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) continue;
            int idx = trimmed.indexOf(':');
            if (idx <= 0) continue;
            out.add(new String[]{trimmed.substring(0, idx), trimmed.substring(idx + 1).trim()});
        }
        return out;
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static void collect(Component c, List<ClickEvent> out) {
        ClickEvent click = c.style().clickEvent();
        if (click != null && (click.action() == ClickEvent.Action.RUN_COMMAND
                || click.action() == ClickEvent.Action.SUGGEST_COMMAND)) {
            out.add(click);
        }
        for (Component kid : c.children()) collect(kid, out);
    }

    private static String commandOf(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("/g ask ")) text = text.substring(7).trim();
        return text;
    }

    @Test
    void chatCommandsBecomeButtonsWithKnownRoot() throws IOException {
        List<String> bad = new ArrayList<>();
        for (String[] kv : parse(RESOURCES.resolve("messages_ru.yml"))) {
            String value = kv[1];
            if (!value.contains("/gps") && !value.contains("/g ")) continue;
            List<ClickEvent> clicks = new ArrayList<>();
            collect(CmdLink.decorate(GuiTheme.safe(value)), clicks);
            for (ClickEvent click : clicks) {
                String command = commandOf(click.value());
                String[] parts = command.split("\\s+");
                if (parts.length < 2) continue;
                String root = parts[1].toLowerCase(Locale.ROOT);
                if (root.startsWith("gps")) continue;
                if (!ROOTS.contains(root)) {
                    bad.add(kv[0] + ": «" + command + "»");
                }
            }
        }
        assertTrue(bad.isEmpty(), "в кнопки попали не команды:\n  " + String.join("\n  ", bad));
    }

    @Test
    void hintsAskToClickNotToType() throws IOException {
        List<String> bad = new ArrayList<>();
        for (String[] kv : parse(RESOURCES.resolve("messages_ru.yml"))) {
            String lower = kv[1].toLowerCase(Locale.ROOT);
            for (String ask : List.of("введи /gps", "введите /gps", "напиши команду", "впиши команду")) {
                if (lower.contains(ask)) bad.add(kv[0] + ": " + kv[1]);
            }
        }
        assertTrue(bad.isEmpty(), "подсказки просят вводить команду:\n  " + String.join("\n  ", bad));
    }

    @Test
    void shortRootIsShown() {
        Component out = CmdLink.decorate(GuiTheme.safe("<white>/gps route save</white>"));
        assertTrue(plain(out).contains("/g route save"), "короткая форма не показана: " + plain(out));
        assertFalse(plain(out).contains("/gps route save "), "лишний хвост");
    }
}
