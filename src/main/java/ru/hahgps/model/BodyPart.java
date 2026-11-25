package ru.hahgps.model;

import java.util.Locale;

/**
 * Часть тела носителя, на которую прицеплен iTrak. Часть выбирается по точке
 * попадания: куда игрок действительно ткнул трекером — туда он и цепляется.
 *
 * Пропорции взяты от роста сущности (0 — пятки, 1 — макушка), поэтому одна и
 * та же разметка работает и для игрока, и для зомби, и для животного: у
 * низкой курицы «голова» — верхние 18% роста, у жителя — его макушка.
 */
public enum BodyPart {

    HEAD("head"),
    CHEST("chest"),
    RIGHT_ARM("right-arm"),
    LEFT_ARM("left-arm"),
    RIGHT_LEG("right-leg"),
    LEFT_LEG("left-leg");

    /** Граница головы: всё выше — голова. */
    public static final double HEAD_FROM = 0.82;
    /** Граница ног: всё ниже — ноги. */
    public static final double LEGS_UNTIL = 0.45;
    /** Насколько вбок нужно попасть, чтобы это считалось рукой, а не туловищем. */
    public static final double ARM_SIDE = 0.55;

    private final String key;

    BodyPart(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    /** Ключ сообщения с названием части тела: itrak-part-head и т. д. */
    public String messageKey() {
        return "itrak-part-" + key;
    }

    public static BodyPart parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (BodyPart part : values()) {
            if (part.key.equals(normalized) || part.name().equalsIgnoreCase(normalized)) {
                return part;
            }
        }
        return null;
    }

    /**
     * Часть тела по точке попадания.
     *
     * @param relativeY 0 — пятки, 1 — макушка (может выйти за пределы: попадание
     *                  в хитбокс с запасом)
     * @param side      смещение вбок: 0 — центр, ±1 — край хитбокса;
     *                  положительное — правая сторона носителя
     */
    public static BodyPart of(double relativeY, double side) {
        if (relativeY >= HEAD_FROM) {
            return HEAD;
        }
        if (relativeY >= LEGS_UNTIL) {
            if (side >= ARM_SIDE) {
                return RIGHT_ARM;
            }
            if (side <= -ARM_SIDE) {
                return LEFT_ARM;
            }
            return CHEST;
        }
        return side < 0 ? LEFT_LEG : RIGHT_LEG;
    }
}
