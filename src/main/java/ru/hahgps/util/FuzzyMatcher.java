package ru.hahgps.util;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
public final class FuzzyMatcher {
    private FuzzyMatcher() {
    }

    public static int levenshtein(String a, String b) {
        if (a == null || b == null) {
            return Integer.MAX_VALUE;
        }
        return levenshteinLower(a.toLowerCase(Locale.ROOT), b.toLowerCase(Locale.ROOT));
    }

    /** Левенштейн для уже нормализованных (lowercase) строк — без повторных аллокаций строк. */
    private static int levenshteinLower(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    /**
     * Верхняя оценка похожести только по разнице длин: если она уже ниже порога,
     * полный проход Левенштейна бессмысленен (используется в горячем поиске).
     */
    public static double upperBound(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        int la = a.length();
        int lb = b.length();
        int maxLen = Math.max(la, lb);
        if (maxLen == 0) {
            return 1.0;
        }
        return 1.0 - (double) Math.abs(la - lb) / maxLen;
    }

    public static double similarity(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        a = a.toLowerCase(Locale.ROOT);
        b = b.toLowerCase(Locale.ROOT);
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) {
            return 1.0;
        }
        return 1.0 - (double) levenshteinLower(a, b) / maxLen;
    }

    public static boolean matches(String query, String target, double threshold) {
        if (query == null || target == null) {
            return false;
        }
        String q = query.toLowerCase(Locale.ROOT).trim();
        String t = target.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) {
            return true;
        }
        if (t.contains(q)) {
            return true;
        }
        return similarity(q, t) >= threshold;
    }

    public static List<Match> findBest(String query, List<String> targets, int limit, double threshold) {
        if (query == null || targets == null || limit <= 0) {
            return new ArrayList<>();
        }
        List<Match> results = new ArrayList<>();
        String queryLower = query.toLowerCase(Locale.ROOT);
        for (String target : targets) {
            if (target == null) {
                continue;
            }
            // upperBound — максимально возможная похожесть для данной пары длин;
            // если она ниже порога, полный Левенштейн бессмысленен
            if (upperBound(query, target) < threshold) {
                continue;
            }
            double sim = similarity(queryLower, target);
            if (sim >= threshold) {
                results.add(new Match(target, sim));
            }
        }
        results.sort((a, b) -> Double.compare(b.similarity, a.similarity));
        if (results.size() > limit) {
            return new ArrayList<>(results.subList(0, limit));
        }
        return results;
    }

    public static class Match {
        public final String target;
        public final double similarity;

        public Match(String target, double similarity) {
            this.target = target;
            this.similarity = similarity;
        }
    }
}
