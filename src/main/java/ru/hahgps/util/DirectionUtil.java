package ru.hahgps.util;
public final class DirectionUtil {
    private DirectionUtil() {
    }

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
