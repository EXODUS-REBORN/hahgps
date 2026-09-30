package ru.hahgps.util;

/**
 * Утилиты направления: стрелки и подсказки.
 *
 * Соглашение: deltaAngle > 0 — цель СЛЕВА от направления взгляда,
 * deltaAngle < 0 — цель СПРАВА (для системы координат Minecraft,
 * где yaw=0 — юг, а угол цели считается через atan2(dx, dz)).
 */
public final class DirectionUtil {

    private DirectionUtil() {
    }

    /**
     * Стрелка направления на основе угла относительно взгляда игрока.
     *
     * @param deltaAngle угол между направлением взгляда и направлением на цель (градусы),
     *                   нормализованный в [-180, 180]. Положительный = цель слева.
     * @return символ стрелки
     */
    public static String getArrow(double deltaAngle) {
        double a = Math.abs(deltaAngle);
        if (a <= 22.5) {
            return "↑";
        } else if (a <= 67.5) {
            return deltaAngle > 0 ? "↖" : "↗";
        } else if (a <= 112.5) {
            return deltaAngle > 0 ? "←" : "→";
        } else if (a <= 157.5) {
            return deltaAngle > 0 ? "↙" : "↘";
        } else {
            return "↓";
        }
    }

    /**
     * Подсказка для журнала маршрута.
     *
     * @param deltaAngle угол относительно взгляда
     * @return ключ сообщения подсказки
     */
    public static String getHintKey(double deltaAngle) {
        double a = Math.abs(deltaAngle);
        if (a <= 30) {
            return "hint-straight";
        } else if (a <= 75) {
            return deltaAngle > 0 ? "hint-slight-left" : "hint-slight-right";
        } else if (a <= 120) {
            return deltaAngle > 0 ? "hint-left" : "hint-right";
        } else if (a <= 165) {
            return deltaAngle > 0 ? "hint-sharp-left" : "hint-sharp-right";
        } else {
            return "hint-back";
        }
    }
}
