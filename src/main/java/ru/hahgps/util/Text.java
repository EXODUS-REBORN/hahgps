package ru.hahgps.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class Text {

    private static final DecimalFormat NUMBER = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT));

    private Text() {
    }

    public static String num(double value) {
        return NUMBER.format(value);
    }

    public static String distance(double blocks) {
        if (blocks >= 1000.0D) {
            return num(Math.round(blocks / 100.0D) / 10.0D) + " км";
        }
        return Math.max(0L, Math.round(blocks)) + " м";
    }

    public static String duration(double seconds) {
        long totalMinutes = Math.max(1L, Math.round(seconds / 60.0D));
        if (totalMinutes < 60L) {
            return totalMinutes + " мин";
        }
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        return minutes == 0L ? hours + " ч" : hours + " ч " + minutes + " мин";
    }

    public static String yesNo(boolean value) {
        return value ? "да" : "нет";
    }
}
