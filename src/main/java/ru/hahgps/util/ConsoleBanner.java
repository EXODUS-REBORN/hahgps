package ru.hahgps.util;

import ru.hahgps.gui.GuiTheme;

import java.util.logging.Logger;

public final class ConsoleBanner {

    private static final String[] LOGO = {
            "██╗  ██╗ █████╗ ██╗  ██╗████████╗ ██████╗      ██╗███████╗██████╗ ",
            "██║  ██║██╔══██╗██║  ██║╚══██╔══╝██╔═══██╗     ██║██╔════╝██╔══██╗",
            "███████║███████║███████║   ██║   ██║   ██║     ██║█████╗  ██████╔╝",
            "██╔══██║██╔══██║██╔══██║   ██║   ██║   ██║██   ██║██╔══╝  ██╔══██╗",
            "██║  ██║██║  ██║██║  ██║   ██║   ╚██████╔╝╚█████╔╝███████╗██║  ██║",
            "╚═╝  ╚═╝╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝    ╚═════╝  ╚════╝ ╚══════╝╚═╝  ╚═╝"
    };

    private ConsoleBanner() {
    }

    public static void printBanner(Logger logger, String version) {
        for (int i = 0; i < LOGO.length; i++) {
            int from = Math.min(i, 5);
            int to = Math.min(i + 5, 9);
            logger.info(GuiTheme.gradient(LOGO[i], from, to));
        }
        logger.info(GuiTheme.gradient("▬".repeat(70), 2, 9));
        logger.info(GuiTheme.gradient("[HahGPS]", 4, 9));
        logger.info("Плагин активирован · версия " + version + " · NewJersey");
        logger.info("");
    }
}
