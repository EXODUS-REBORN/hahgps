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
                + " <dark_gray>•</dark_gray> <gray>карта:</gray> <white>/gps map</white>"
                + " <dark_gray>•</dark_gray> <gray>справка:</gray> <white>/gps help</white>", logger);
        send(plugin, " <dark_gray>└</dark_gray> <gray>палитра:</gray> "
                + "<#3f86ff>синий</#3f86ff> <dark_gray>•</dark_gray> "
                + "<#8fe9f5>голубой</#8fe9f5> <dark_gray>•</dark_gray> "
                + "<#4fd1e0>бирюза</#4fd1e0> <dark_gray>•</dark_gray> "
                + "<#4ade80>зелёный</#4ade80> <dark_gray>•</dark_gray> "
                + "<#fbbf24>золото</#fbbf24> <dark_gray>•</dark_gray> "
                + "<#fb923c>оранжевый</#fb923c> <dark_gray>•</dark_gray> "
                + "<#c084fc>фиолетовый</#c084fc> <dark_gray>•</dark_gray> "
                + "<#f87171>красный</#f87171>", logger);
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
