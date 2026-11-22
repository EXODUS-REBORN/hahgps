package ru.hahgps.util;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

/**
 * Восемь направлений маршрута — как стрелки навигатора.
 *
 * Для каждого направления есть два вида:
 * <ul>
 *   <li>{@link #unicode()} — ванильный символ (BMP), работает без ресурспака;</li>
 *   <li>{@link #glyphComponent()} — нарисованный 8×8 глиф из ресурспака
 *       в шрифте {@code hahgps:arrow}.</li>
 * </ul>
 *
 * Знак угла: положительная дельта — цель левее игрока (как в
 * {@link DirectionUtil} было раньше: {@code ↑↖←↙↓↘→↗}).
 * Коды глифов ровно те же, что в {@code respack/build.py}
 * (U+E000…U+E007 в порядке объявления).
 */
public enum RouteArrow {

    STRAIGHT('\uE000', "↑", "straight"),
    SLIGHT_LEFT('\uE001', "↖", "slight-left"),
    LEFT('\uE002', "←", "left"),
    SHARP_LEFT('\uE003', "↙", "sharp-left"),
    BACK('\uE004', "↓", "back"),
    SHARP_RIGHT('\uE005', "↘", "sharp-right"),
    RIGHT('\uE006', "→", "right"),
    SLIGHT_RIGHT('\uE007', "↗", "slight-right");

    /** Шрифт ресурспака {@code assets/hahgps/font/arrow.json}. */
    public static final Key FONT = Key.key("hahgps", "arrow");

    /** Границы секторов, градусы: прямо / чуть / поворот / резко / разворот. */
    private static final double STRAIGHT_LIMIT = 22.5;
    private static final double SLIGHT_LIMIT = 67.5;
    private static final double TURN_LIMIT = 112.5;
    private static final double SHARP_LIMIT = 157.5;

    private final char glyph;
    private final String unicode;
    private final String key;

    RouteArrow(char glyph, String unicode, String key) {
        this.glyph = glyph;
        this.unicode = unicode;
        this.key = key;
    }

    /** Символ приватной области Unicode для шрифта {@link #FONT}. */
    public char glyphChar() {
        return glyph;
    }

    /** Ванильная стрелка для клиентов без ресурспака. */
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

    /** Компонент с нарисованным глифом (шрифт ресурспака). */
    public Component glyphComponent() {
        return Component.text(String.valueOf(glyph)).font(FONT);
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
