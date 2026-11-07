package ru.hahgps.service;

import ru.hahgps.util.FuzzyMatcher;

import java.util.Locale;

/**
 * Ранжирование поиска мест.
 *
 * Похожесть считается честно, а популярность (сколько разных игроков искали
 * это место именно этим текстом) даёт маленькую надбавку — не больше
 * {@link #MAX_BOOST}. Поэтому место с явно худшим совпадением не может
 * обогнать лучшее только за счёт частоты: надбавка ограничена, применяется
 * лишь к результатам с похожестью не ниже {@link #BOOST_FLOOR}, и её рост
 * логарифмический — один игрок не «накрутит» место до верха.
 */
public final class SearchRanking {

    /** Больше этой надбавки популярность не даёт (в долях единицы). */
    public static final double MAX_BOOST = 0.06;
    /** Шаг надбавки за каждого «условного» игрока (логарифм по основанию 2). */
    public static final double BOOST_STEP = 0.02;
    /** Ниже этой похожести надбавка не применяется: мусор не должен всплывать. */
    public static final double BOOST_FLOOR = 0.5;
    /** С такой похожести считаем, что запрос действительно про это место. */
    public static final double RELIABLE = 0.8;
    /** Длина нормализованного запроса: колонка в БД — 64 символа. */
    public static final int MAX_QUERY_LENGTH = 64;

    private SearchRanking() {
    }

    /** Похожесть запроса и названия точки; {@code reliable} — можно записывать статистику. */
    public record Score(double similarity, boolean reliable) {
    }

    /**
     * Совпадение запроса с названием точки.
     *
     * Порядок важен: точное имя и вхождение всегда лучше «похожести по
     * буквам», иначе запрос-адрес («хайсовская 10») проигрывал бы короткому
     * чужому названию, у которого просто удачное расстояние Левенштейна.
     */
    public static Score score(String query, String name, double threshold) {
        if (query == null || name == null) {
            return new Score(0.0, false);
        }
        String q = normalizeQuery(query);
        String n = collapse(name);
        if (q.isEmpty() || n.isEmpty()) {
            return new Score(0.0, false);
        }
        if (n.equals(q)) {
            return new Score(1.0, true);
        }

        // По словам: «хайсовская 10» должно находить «ТЦ Авиапарк, Хайсовская 10»
        // выше, чем короткое чужое название, даже если порядок слов другой.
        int total = 0;
        int matched = 0;
        for (String token : q.split(" ")) {
            if (token.length() < 2) {
                continue;
            }
            total++;
            if (n.contains(token)) {
                matched++;
            }
        }
        if (total >= 2 && matched == total) {
            return new Score(0.95, true);
        }
        if (n.startsWith(q)) {
            return new Score(0.9, true);
        }
        if (n.contains(q)) {
            return new Score(0.85, true);
        }
        if (total >= 2 && matched >= 2) {
            // Часть адреса совпала: это уже осмысленно, но не «точное» место.
            return new Score(Math.min(0.84, 0.65 + 0.05 * matched), false);
        }

        double upper = FuzzyMatcher.upperBound(q, n);
        if (upper < threshold) {
            return new Score(0.0, false);
        }
        double similarity = FuzzyMatcher.similarity(q, n);
        if (similarity < threshold) {
            return new Score(0.0, false);
        }
        return new Score(similarity, similarity >= RELIABLE);
    }

    /** Надбавка за популярность: 1 игрок → +0.02, 3 → +0.04, 7 и больше → +0.06. */
    public static double boost(int players) {
        if (players <= 0) {
            return 0.0;
        }
        double value = BOOST_STEP * (Math.log(players + 1.0) / Math.log(2.0));
        return Math.min(MAX_BOOST, value);
    }

    /** Итоговый балл: похожесть плюс ограниченная надбавка (не больше 1.0). */
    public static double withBoost(double base, int players) {
        if (base < BOOST_FLOOR) {
            return base;
        }
        return Math.min(1.0, base + boost(players));
    }

    /** Запрос как он хранится в статистике: нижний регистр, один пробел, до 64 символов. */
    public static String normalizeQuery(String raw) {
        if (raw == null) {
            return "";
        }
        String value = collapse(raw);
        return value.length() > MAX_QUERY_LENGTH ? value.substring(0, MAX_QUERY_LENGTH).trim() : value;
    }

    private static String collapse(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
