package ru.hahgps.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import ru.hahgps.gui.GuiTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CmdLinkTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static List<Component> flatten(Component c) {
        List<Component> out = new ArrayList<>();
        collect(c, out);
        return out;
    }

    private static void collect(Component c, List<Component> out) {
        out.add(c);
        for (Component kid : c.children()) collect(kid, out);
    }

    private static Component buttonWith(Component c, String command) {
        for (Component part : flatten(c)) {
            ClickEvent click = part.style().clickEvent();
            if (click != null && plain(part).equals(command)) return part;
        }
        return null;
    }

    @Test
    void commandBecomesButton() {
        Component out = CmdLink.decorate(GuiTheme.safe("<gray>Сохрани командой</gray> <white>/gps route save</white>"));
        Component button = buttonWith(out, "/g route save");
        assertNotNull(button, "нет кнопки: " + plain(out));
        assertEquals(ClickEvent.Action.RUN_COMMAND, button.style().clickEvent().action());
    }

    @Test
    void placeholderCommandAsksBeforeRunning() {
        Component out = CmdLink.decorate(GuiTheme.safe("<gray>Потом</gray> <white>/gps point info <имя></white>"));
        Component button = buttonWith(out, "/g point info <имя>");
        assertNotNull(button, "нет кнопки: " + plain(out));
        assertEquals(ClickEvent.Action.RUN_COMMAND, button.style().clickEvent().action());
        assertEquals("/g ask /gps point info <имя>", button.style().clickEvent().value());
    }

    @Test
    void alternativeVariantsBecomeSuggestion() {
        Component out = CmdLink.decorate(GuiTheme.safe("<white>/gps visual mode on|off</white>"));
        Component button = buttonWith(out, "/g visual mode on|off");
        assertNotNull(button, "нет кнопки: " + plain(out));
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, button.style().clickEvent().action());
    }

    @Test
    void plainTextStaysIntact() {
        Component out = CmdLink.decorate(GuiTheme.safe("<gray>До</gray> <white>/gps log</white> <gray>— после</gray>"));
        assertEquals("До /g log — после", plain(out));
    }

    @Test
    void hoverShowsWhatCommandDoes() {
        Function<String, String> old = null;
        CmdLink.describer(cmd -> "Подсказки навигации в чате");
        try {
            Component out = CmdLink.decorate(GuiTheme.safe("<white>/gps log</white>"));
            Component button = buttonWith(out, "/g log");
            assertNotNull(button);
            String hover = plain((Component) button.style().hoverEvent().value());
            assertTrue(hover.contains("Подсказки навигации в чате"), "нет описания: " + hover);
        } finally {
            CmdLink.describer(old);
        }
    }

    @Test
    void shortRootAndTemplates() {
        assertEquals("/g route save", CmdLink.shorten("/gps route save"));
        assertEquals("/g", CmdLink.shorten("/gps"));
        assertEquals("/g point info ИМЯ", CmdLink.shorten("/gps point info ИМЯ"));
        assertEquals("/gps point create <имя>", CmdLink.templateOf("/gps point create <имя> [категория]"));
        assertEquals(1, CmdLink.placeholderCount("/gps point create <имя> [категория]"));
        assertTrue(CmdLink.hasPlaceholder("/gps goto <точка>"));
        assertFalse(CmdLink.hasPlaceholder("/gps goto дом"));
    }

    @Test
    void askFillsPlaceholders() {
        String filled = CommandFill.fill("/gps point info <имя>", new String[]{"дом"});
        assertEquals("/gps point info дом", filled);
        assertEquals("/gps route record start путь",
                CommandFill.fill("/gps route record <режим> <имя>", new String[]{"start", "путь"}));
    }

    private static final class CommandFill {
        static String fill(String template, String[] parts) {
            try {
                var method = Class.forName("ru.hahgps.command.CommandManager")
                        .getDeclaredMethod("fill", String.class, String[].class);
                method.setAccessible(true);
                return (String) method.invoke(null, template, parts);
            } catch (Throwable t) {
                throw new AssertionError("не удалось вызвать CommandManager.fill: " + t, t);
            }
        }
    }
}
