package ru.hahgps.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CmdLink {

    private static final TextColor ACCENT = TextColor.color(0x66aaff);

    private static final Pattern CMD = Pattern.compile("/(?:gps|g)(?![\\p{L}\\p{N}_/-])[^\\n]*");

    private static final Pattern PLACEHOLDER = Pattern.compile("[<>\\[\\]%{}]");

    private static final String TAIL_CHARS = ".,;:!?»\"')…";

    private static final Set<String> STOP = Set.of(
            "и", "или", "затем", "потом", "после", "дальше", "где", "это", "если", "чтобы",
            "также", "нажми", "введи", "введите", "команда", "командой", "например", "вроде",
            "в", "на", "по", "с", "к", "от", "для", "—", "–", "-", "·");

    private static final int MAX_ARGS = 4;

    private static volatile Function<String, String> describer;

    private static volatile Function<String, String> texts = key -> null;

    private static final ThreadLocal<Boolean> RENDERING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private CmdLink() {
    }

    public static void describer(Function<String, String> source) {
        describer = source;
    }

    public static void texts(Function<String, String> source) {
        texts = source == null ? key -> null : source;
    }

    public static Component decorate(Component component) {
        if (component == null || RENDERING.get()) return component;
        return walk(component);
    }

    public static Component button(String command) {
        return button(new Parsed(command == null ? "" : command, true));
    }

    public static String shorten(String command) {
        if (command == null) return "";
        if (command.startsWith("/gps ")) return "/g " + command.substring(5);
        if (command.equals("/gps")) return "/g";
        return command;
    }

    public static boolean runnable(String command) {
        if (command == null || command.isBlank()) return false;
        if (command.contains("|")) return false;
        return !command.contains("=");
    }

    public static boolean hasPlaceholder(String command) {
        return PLACEHOLDER.matcher(command == null ? "" : command).find();
    }

    public static int placeholderCount(String command) {
        if (command == null) return 0;
        int count = 0;
        for (String token : templateOf(command).split("\\s+")) {
            if (PLACEHOLDER.matcher(token).find()) count++;
        }
        return count;
    }

    public static String templateOf(String command) {
        if (command == null) return "";
        StringBuilder out = new StringBuilder();
        for (String token : command.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            char first = token.charAt(0);
            if (first == '[' || first == '(') continue;
            if (out.length() > 0) out.append(' ');
            out.append(token);
        }
        return out.toString();
    }

    public static String describe(String command) {
        Function<String, String> source = describer;
        if (source != null) {
            try {
                String value = source.apply(command);
                if (value != null && !value.isBlank()) return value;
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private record Parsed(String command, boolean run) {
    }

    private static Component button(Parsed parsed) {
        String command = parsed.command();
        boolean ask = hasPlaceholder(command);
        ClickEvent click;
        if (ask) {
            click = ClickEvent.runCommand("/g ask " + templateOf(command));
        } else if (parsed.run()) {
            click = ClickEvent.runCommand(command);
        } else {
            click = ClickEvent.suggestCommand(command);
        }
        Style style = Style.empty()
                .color(ACCENT)
                .decoration(TextDecoration.UNDERLINED, TextDecoration.State.TRUE);
        return Component.text(shorten(command)).style(style)
                .clickEvent(click)
                .hoverEvent(HoverEvent.showText(hover(command, ask, parsed.run())));
    }

    private static Component hover(String command, boolean ask, boolean run) {
        RENDERING.set(Boolean.TRUE);
        try {
            StringBuilder text = new StringBuilder();
            text.append("<#8fc6ff><bold>").append(mini(shorten(command))).append("</bold></#8fc6ff>");
            String desc = describe(command);
            if (desc != null && !desc.isBlank()) {
                for (String line : desc.split("\n", -1)) {
                    if (line.isBlank()) continue;
                    text.append("\n<gray>").append(mini(line)).append("</gray>");
                }
            }
            String action;
            if (ask) {
                action = buttonText("кнопки-спросит", "Клик — спросит значение и выполнит");
            } else if (run) {
                action = buttonText("кнопки-нажми", "Клик — выполнится сразу");
            } else {
                action = buttonText("кнопки-вставить", "Клик — команда подставится в чат");
            }
            text.append("\n<dark_gray>").append(mini(action)).append("</dark_gray>");
            return ru.hahgps.gui.GuiTheme.safe(text.toString());
        } finally {
            RENDERING.set(Boolean.FALSE);
        }
    }

    private static String mini(String text) {
        return text == null ? "" : text.replace("<", "\uFFF1").replace(">", "\uFFF2");
    }

    private static String buttonText(String key, String fallback) {
        Function<String, String> source = texts;
        try {
            String value = source == null ? null : source.apply(key);
            return value == null || value.isBlank() ? fallback : value;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static Component walk(Component component) {
        Style style = component.style();
        if (style != null && style.clickEvent() != null) return component;
        List<Component> children = component.children();
        if (component instanceof TextComponent text) {
            Component rebuilt = rebuild(text);
            if (children.isEmpty()) return rebuilt;
            List<Component> mapped = walkAll(children);
            return mapped == children ? rebuilt : rebuilt.children(mapped);
        }
        if (children.isEmpty()) return component;
        List<Component> mapped = walkAll(children);
        return mapped == children ? component : component.children(mapped);
    }

    private static List<Component> walkAll(List<Component> children) {
        List<Component> out = new ArrayList<>(children.size());
        boolean changed = false;
        for (Component child : children) {
            Component mapped = walk(child);
            if (mapped != child) changed = true;
            out.add(mapped);
        }
        return changed ? out : children;
    }

    private static Component rebuild(TextComponent text) {
        String content = text.content();
        Matcher matcher = CMD.matcher(content);
        if (!matcher.find()) return text;
        List<Component> parts = new ArrayList<>();
        int last = 0;
        do {
            Parsed parsed = parse(matcher.group());
            if (parsed.command().length() < 3) continue;
            int start = matcher.start();
            int end = start + parsed.command().length();
            if (start > last) parts.add(Component.text(content.substring(last, start)));
            parts.add(button(parsed));
            last = end;
        } while (matcher.find());
        if (parts.isEmpty()) return text;
        if (last < content.length()) parts.add(Component.text(content.substring(last)));
        return Component.text().content("").style(text.style()).append(parts).build();
    }

    private static Parsed parse(String raw) {
        String tail = raw == null ? "" : raw;
        int index = 0;
        int args = 0;
        while (index < tail.length()) {
            int space = tail.indexOf(' ', index);
            int end = space < 0 ? tail.length() : space;
            String token = tail.substring(index, end);
            String core = stripTail(token);
            if (core.isEmpty()) return new Parsed(cut(tail, index), true);
            if (args >= 1 && STOP.contains(core.toLowerCase(Locale.ROOT))) {
                return new Parsed(cut(tail, index), true);
            }
            if (core.indexOf('|') >= 0) return new Parsed(stripTail(tail), false);
            if (PLACEHOLDER.matcher(core).find()) return new Parsed(stripTail(tail), true);
            if (core.indexOf('=') >= 0) return new Parsed(stripTail(tail), false);
            if (args >= MAX_ARGS) return new Parsed(stripTail(tail), false);
            if (core.length() < token.length()) return new Parsed(cut(tail, index + core.length()), true);
            args++;
            if (space < 0) break;
            index = space + 1;
        }
        return new Parsed(stripTail(tail), true);
    }

    private static String cut(String tail, int end) {
        return stripTail(tail.substring(0, end));
    }

    private static String stripTail(String text) {
        String out = text == null ? "" : text.trim();
        while (!out.isEmpty() && TAIL_CHARS.indexOf(out.charAt(out.length() - 1)) >= 0) {
            out = out.substring(0, out.length() - 1).trim();
        }
        return out;
    }
}
