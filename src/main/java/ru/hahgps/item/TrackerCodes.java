package ru.hahgps.item;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Выдача кодов iTrak: один код — ровно один предмет-трекер.
 *
 * Раньше код был случайным четырёхзначным числом (65 536 вариантов), и на
 * сервере с десятками крафтов коды совпадали: два разных предмета получали
 * один код, второй нельзя было поставить («уже установлен»), а локатор
 * продолжал показывать первый. Теперь номера выдаются по порядку из
 * персистентного счётчика в БД: блок номеров резервируется одним запросом на
 * старте, поэтому код не повторится даже после перезапуска, краша или
 * очистки инвентаря.
 *
 * Формат: {@code TRK-0000042-7F3A} — порядковый номер и четыре случайных
 * знака. Номер гарантирует отсутствие повторов, случайный хвост не даёт
 * перебрать чужие трекеры по порядку (иначе любой игрок скриптом узнал бы
 * координаты всех трекеров сервера через {@code /gps itrak info}). Старые
 * коды {@code TRK-1A2B} короче и с новыми не пересекаются.
 */
public final class TrackerCodes {

    public static final String PREFIX = "TRK-";

    /** Цифр в порядковом номере: до 9 999 999 кодов в одном формате. */
    public static final int DIGITS = 7;

    /**
     * Потолок порядковых номеров. Резерв в БД идёт блоками, и последний блок
     * мог выйти за 7-значный формат — тогда номер печатался 8 цифрами, а
     * {@link #baseOf(String)} его уже не узнавал. Блок обрезается по потолку,
     * а дальше {@link #next()} отдаёт резервные коды.
     */
    public static final long MAX_SEQUENTIAL = 9_999_999L;

    /** Случайных знаков в хвосте кода. */
    public static final int SUFFIX = 4;

    /** Сколько номеров резервируется в БД за один запрос. */
    public static final int BLOCK = 1_000_000;

    private static final Set<String> used = ConcurrentHashMap.newKeySet();
    private static final AtomicLong next = new AtomicLong();
    private static final AtomicLong limit = new AtomicLong();
    private static volatile LongSupplier blockRequester;

    private TrackerCodes() {
    }

    /**
     * Начать диапазон: {@code count} номеров, начиная с {@code first}.
     * Вызывается в главном потоке, когда хранилище уже выдало блок.
     */
    public static synchronized void initialize(long first, int count) {
        long start = Math.max(0L, first);
        next.set(start);
        limit.set(sequentialEnd(start, count));
    }

    /** Конец диапазона (не включая), обрезанный по {@link #MAX_SEQUENTIAL}. */
    private static long sequentialEnd(long start, int count) {
        long end = start + Math.max(1, count);
        return Math.min(end, MAX_SEQUENTIAL + 1);
    }

    /**
     * Откуда брать следующий блок номеров, когда текущий кончился.
     * Поставщик возвращает первый номер нового блока или -1.
     */
    public static void setBlockRequester(LongSupplier requester) {
        blockRequester = requester;
    }

    public static synchronized boolean isReady() {
        return limit.get() > 0 && next.get() < limit.get();
    }

    /**
     * Следующий свободный код. Потокобезопасно: коды выдаются и из крафта
     * (главный поток), и из команд.
     */
    public static synchronized String next() {
        for (int guard = 0; guard < 64; guard++) {
            if (!isReady() && !requestBlock()) {
                return fallback();
            }
            long value = next.getAndIncrement();
            String base = format(value);
            if (!used.contains(base)) {
                return base + "-" + suffix();
            }
            // Номер занят вручную (например, код из старой базы) — берём следующий.
        }
        return fallback();
    }

    /** Пометить код занятым: коды из базы и старые случайные коды. */
    public static void reserve(String code) {
        if (code != null && !code.isBlank()) {
            used.add(baseOf(code));
        }
    }

    /** Тесты: сбросить состояние между прогонами. */
    static synchronized void resetForTests() {
        used.clear();
        next.set(0L);
        limit.set(0L);
        blockRequester = null;
    }

    static synchronized String format(long value) {
        return String.format(Locale.ROOT, "%s%0" + DIGITS + "d", PREFIX, value);
    }

    /**
     * Порядковая часть кода без случайного хвоста. Цифр может быть и больше
     * {@link #DIGITS}: старые/переполненные номера тоже должны распознаваться,
     * иначе проверка занятости через {@link #used} пропускала бы их.
     */
    static String baseOf(String code) {
        String value = code.trim().toUpperCase(Locale.ROOT);
        if (!value.startsWith(PREFIX)) {
            return value;
        }
        int dash = value.indexOf('-', PREFIX.length());
        if (dash < 0) {
            return value;
        }
        String digits = value.substring(PREFIX.length(), dash);
        if (digits.isEmpty()) {
            return value;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) {
                return value;
            }
        }
        return value.substring(0, dash);
    }

    private static String suffix() {
        String raw = Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFL)
                .toUpperCase(Locale.ROOT);
        return "0".repeat(SUFFIX - raw.length()) + raw;
    }

    private static boolean requestBlock() {
        LongSupplier requester = blockRequester;
        if (requester == null) {
            return false;
        }
        long start;
        try {
            start = requester.getAsLong();
        } catch (Throwable error) {
            return false;
        }
        if (start <= 0 || start > MAX_SEQUENTIAL) {
            return false;
        }
        next.set(start);
        limit.set(sequentialEnd(start, BLOCK));
        return true;
    }

    /**
     * Запасной вариант, когда счётчик недоступен (хранилище ещё поднимается
     * или лежит): восемь шестнадцатеричных символов. Такой код не совпадёт с
     * порядковым (разная длина), но гарантии «никогда не повторится» у него
     * нет — поэтому он живёт только до готовности БД.
     */
    private static String fallback() {
        for (int attempt = 0; attempt < 32; attempt++) {
            String raw = Long.toHexString(ThreadLocalRandom.current().nextLong(0x1_0000_0000L))
                    .toUpperCase(Locale.ROOT);
            String code = PREFIX + "0".repeat(8 - raw.length()) + raw;
            if (used.add(code)) {
                return code;
            }
        }
        String code = PREFIX + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 9).toUpperCase(Locale.ROOT);
        used.add(code);
        return code;
    }
}
