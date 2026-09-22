package ru.hahgps.model;

public enum Turn {

    STRAIGHT("straight"),
    SLIGHT_LEFT("slight-left"),
    LEFT("left"),
    SHARP_LEFT("sharp-left"),
    BACK("back"),
    SLIGHT_RIGHT("slight-right"),
    RIGHT("right"),
    SHARP_RIGHT("sharp-right"),
    ARRIVE("arrive");

    private final String id;

    Turn(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Turn fromAngle(double delta, double straight, double slight, double sharp) {
        double abs = Math.abs(delta);
        boolean left = delta < 0.0D;
        if (abs < straight) {
            return STRAIGHT;
        }
        if (abs < slight) {
            return left ? SLIGHT_LEFT : SLIGHT_RIGHT;
        }
        if (abs < sharp) {
            return left ? LEFT : RIGHT;
        }
        if (abs < 180.0D - straight) {
            return left ? SHARP_LEFT : SHARP_RIGHT;
        }
        return BACK;
    }
}
