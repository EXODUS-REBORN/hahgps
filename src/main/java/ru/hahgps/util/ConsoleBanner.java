package ru.hahgps.util;

import net.kyori.adventure.text.Component;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.gui.GuiTheme;

import java.util.logging.Logger;

/**
 * Консольный баннер. Выводится через console sender (Component), а не logger:
 * Paper не разбирает MiniMessage-теги в строках логгера — в логах остаётся
 * «&lt;gradient:…&gt;» как текст, а System.out вызывает предупреждение Paper.
 */
public final class ConsoleBanner {

    /** Логотип HAHGPS (по строке на градиент — весь блок идёт от тёмно-синего к бирюзе). */
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
        // По строке на градиент: весь блок идёт от ночного синего к бирюзе
        for (int i = 0; i < LOGO.length; i++) {
            int from = Math.min(i, 5);
            int to = Math.min(i + 5, 9);
            send(plugin, GuiTheme.gradient(LOGO[i], from, to), logger);
        }
        send(plugin, GuiTheme.gradient(LINE, 2, 9), logger);
        send(plugin, "<dark_gray>[</dark_gray>" + GuiTheme.gradient("HahGPS")
                + "<dark_gray>]</dark_gray> " + GuiTheme.toned(9, "Плагин активирован")
                + " <dark_gray>•</dark_gray> <gray>версия</gray> <white>" + version + "</white>"
                + " <dark_gray>•</dark_gray> <gray>проект NewJersey</gray>", logger);
        send(plugin, " <dark_gray>├</dark_gray> <gray>меню:</gray> <white>/gps menu</white>"
                + " <dark_gray>•</dark_gray> <gray>карта:</gray> <white>/gps map</white>"
                + " <dark_gray>•</dark_gray> <gray>справка:</gray> <white>/gps help</white>", logger);
        send(plugin, " <dark_gray>└</dark_gray> <gray>палитра:</gray> "
                + GuiTheme.toned(3, "синий") + " <dark_gray>•</dark_gray> "
                + GuiTheme.toned(6, "голубой") + " <dark_gray>•</dark_gray> "
                + GuiTheme.toned(9, "бирюза"), logger);
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
