package ru.hahgps.util;

import net.kyori.adventure.text.Component;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.gui.GuiTheme;

import java.util.logging.Logger;


public final class ConsoleBanner {


    private static final String[] LOGO = {
            "██╗  ██╗ █████╗ ██╗  ██╗ █████╗ ██████╗ ███████╗",
            "██║  ██║██╔══██╗██║  ██║██╔═══╝ ██╔══██╗██╔════╝",
            "███████║███████║███████║██║  ██╗██████╔╝███████╗",
            "██╔══██║██╔══██╗██╔══██║██║  ██║██╔═══╝ ╚════██║",
            "██║  ██║██║  ██║██║  ██║╚██████╗██║     ███████║",
            "╚═╝  ╚═╝╚═╝  ╚═╝╚═╝  ╚═╝ ╚═════╝╚═╝     ╚══════╝"
    };

    private static final String LINE = "▬".repeat(70);

    private static final int NOTES_MAX = 200;

    private ConsoleBanner() {
    }

    public static void printBanner(HahGpsPlugin plugin, String version) {
        Logger logger = plugin.getLogger();

        for (int i = 0; i < LOGO.length; i++) {
            int from = Math.min(i, 5);
            int to = Math.min(i + 5, 9);
            send(plugin, GuiTheme.gradient(LOGO[i], from, to), logger);
        }
        send(plugin, GuiTheme.gradient(LINE, 2, 9), logger);
        send(plugin, "<dark_gray>[</dark_gray>" + GuiTheme.gradient("HahGPS")
                + "<dark_gray>]</dark_gray> " + GuiTheme.toned(9, "Плагин активирован")
                + " <dark_gray>•</dark_gray> <gray>версия</gray> <white><bold>" + version + "</bold></white>"
                + " <dark_gray>•</dark_gray> <gray>проект NewJersey</gray>", logger);
        send(plugin, " <dark_gray>├</dark_gray> <gray>меню:</gray> <white>/gps menu</white>"
                + " <dark_gray>•</dark_gray> <gray>справка:</gray> <white>/gps help</white>", logger);
        send(plugin, " <dark_gray>└</dark_gray> <gray>Что нового:</gray> <white>"
                + loadReleaseNotes() + "</white>", logger);
    }

    static String loadReleaseNotes() {
        String raw = null;
        try (var in = ConsoleBanner.class.getResourceAsStream("/release-notes.txt")) {
            if (in != null) {
                raw = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
        }
        if (raw == null || raw.isBlank()) {
            return "см. GitHub Releases";
        }
        String oneLine = raw.replaceAll("\\s+", " ").trim();
        oneLine = oneLine.replace("<", "").replace(">", "");
        if (oneLine.length() > NOTES_MAX) {
            oneLine = oneLine.substring(0, NOTES_MAX).trim() + "…";
        }
        return oneLine;
    }

    private static void send(HahGpsPlugin plugin, String mini, Logger logger) {
        try {
            Component component = GuiTheme.mm(mini);
            plugin.getServer().getConsoleSender().sendMessage(component);
        } catch (Throwable t) {
            try {
                logger.info(GuiTheme.plainOf(mini));
            } catch (Throwable ignored) {
                logger.info(mini.replaceAll("</?[a-zA-Z#][^>]*>", ""));
            }
        }
    }
}
