package ru.hahgps.util;

/**
 * Восемь направлений маршрута — как стрелки навигатора.
 *
 * Рисуются ванильными символами ({@code ↑↖←↙↓↘→↗}): своего ресурспака у
 * плагина нет, а пак сервера собирает панель — шрифтов она не умеет.
 *
 * Знак угла: положительная дельта — цель левее игрока (как в
 * {@link DirectionUtil} было раньше).
 */
public enum RouteArrow {

    STRAIGHT("↑", "straight"),
    SLIGHT_LEFT("↖", "slight-left"),
    LEFT("←", "left"),
    SHARP_LEFT("↙", "sharp-left"),
    BACK("↓", "back"),
    SHARP_RIGHT("↘", "sharp-right"),
    RIGHT("→", "right"),
    SLIGHT_RIGHT("↗", "slight-right");

    /** Границы секторов, градусы: прямо / чуть / поворот / резко / разворот. */
    private static final double STRAIGHT_LIMIT = 22.5;
    private static final double SLIGHT_LIMIT = 67.5;
    private static final double TURN_LIMIT = 112.5;
    private static final double SHARP_LIMIT = 157.5;

    private final String unicode;
    private final String key;

    RouteArrow(String unicode, String key) {
        this.unicode = unicode;
        this.key = key;
    }

    /** Ванильная стрелка направления. */
    public String unicode() {
        return unicode;
    }

    /** Ключ направления: {@code straight}, {@code slight-left}, … */
    public String key() {
        return key;
    }

    /** Ключ подсказки в messages_*.yml: {@code hint-straight}, … */
    public String hintKey() {
        return "hint-" + key;
    }

    /**
     * Направление по дельте угла: 0 — цель прямо, +90 — слева, 180 — сзади.
     * Секторы симметричны: 0–22.5 прямо, дальше «чуть», «поворот», «резко»,
     * от 157.5 — разворот.
     */
    public static RouteArrow of(double deltaAngle) {
        double angle = Math.abs(deltaAngle);
        boolean left = deltaAngle > 0;
        if (angle <= STRAIGHT_LIMIT) {
            return STRAIGHT;
        }
        if (angle <= SLIGHT_LIMIT) {
            return left ? SLIGHT_LEFT : SLIGHT_RIGHT;
        }
        if (angle <= TURN_LIMIT) {
            return left ? LEFT : RIGHT;
        }
        if (angle <= SHARP_LIMIT) {
            return left ? SHARP_LEFT : SHARP_RIGHT;
        }
        return BACK;
    }
}
