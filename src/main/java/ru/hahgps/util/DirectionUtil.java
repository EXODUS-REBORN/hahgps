package ru.hahgps.util;

/** Текстовая и графическая стрелка направления маршрута. */
public final class DirectionUtil {

    private DirectionUtil() {
    }

    /** Ванильная стрелка для чата/BossBar без ресурспака. */
    public static String getArrow(double deltaAngle) {
        return RouteArrow.of(deltaAngle).unicode();
    }

    /** Ключ подсказки {@code hint-*} — тот же сектор, что и у стрелки. */
    public static String getHintKey(double deltaAngle) {
        return RouteArrow.of(deltaAngle).hintKey();
    }
}
