package ru.hahgps.service;

/**
 * Арифметика дальности сигнала iTrak без Bukkit: в зоне ли трекер и какой
 * район показывать, когда сигнала нет. Вынесено отдельно, чтобы правила
 * проверялись тестами без поднятия сервера.
 */
public final class SignalRegion {

    private SignalRegion() {
    }

    /**
     * В зоне связи? {@code range <= 0} — ограничения нет, точное место видно
     * всегда. Расстояние {@link Double#MAX_VALUE} (другой мир) — вне зоны.
     */
    public static boolean inRange(double distance, int range) {
        if (range <= 0) {
            return true;
        }
        return Double.isFinite(distance) && distance <= range;
    }

    /** Начало квадрата-района, в котором лежит координата. */
    public static int start(int coordinate, int cell) {
        int size = Math.max(1, cell);
        return Math.floorDiv(coordinate, size) * size;
    }

    /** Центр квадрата-района: конечная точка маршрута, если сигнала нет. */
    public static int center(int coordinate, int cell) {
        int size = Math.max(1, cell);
        return start(coordinate, size) + size / 2;
    }

    /** Конец квадрата-района (включительно). */
    public static int end(int coordinate, int cell) {
        int size = Math.max(1, cell);
        return start(coordinate, size) + size - 1;
    }
}
