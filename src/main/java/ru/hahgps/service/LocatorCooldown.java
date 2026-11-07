package ru.hahgps.service;

/**
 * Перезарядка локатора iTrak.
 *
 * Чистая арифметика без Bukkit: время, формат оставшегося и выбор длительности
 * по радиусу локатора. Тесты проверяют правила без поднятия сервера.
 */
public final class LocatorCooldown {

    /** Дольше суток КД не бывает: значение из конфига могло быть опечаткой. */
    public static final int MAX_SECONDS = 86_400;

    private LocatorCooldown() {
    }

    /**
     * Длительность перезарядки для конкретного локатора.
     *
     * Дальний локатор (радиус больше обычного) перезаряжается дольше: он и
     * ищет дальше. Отрицательные значения из конфига считаются нулём.
     */
    public static int seconds(int radius, int normalRadius, int normalSeconds, int longSeconds) {
        int normal = Math.max(0, Math.min(MAX_SECONDS, normalSeconds));
        int longer = Math.max(0, Math.min(MAX_SECONDS, longSeconds));
        return radius > normalRadius ? longer : normal;
    }

    /** Сколько миллисекунд ещё ждать; 0 — локатор готов. */
    public static long remaining(long endsAt, long now) {
        return Math.max(0L, endsAt - now);
    }

    /** Время в виде «4:59» или «1:02:03» — как таймер ванильного КД. */
    public static String format(long millis) {
        long total = Math.max(0L, millis) / 1000L;
        long hours = total / 3600L;
        long minutes = (total % 3600L) / 60L;
        long seconds = total % 60L;
        if (hours > 0) {
            return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    /** Тики для ванильной полосы КД на предмете (50 мс на тик, с округлением вверх). */
    public static int ticks(long millis) {
        long ticks = (Math.max(0L, millis) + 49L) / 50L;
        return (int) Math.min(Integer.MAX_VALUE, ticks);
    }
}
