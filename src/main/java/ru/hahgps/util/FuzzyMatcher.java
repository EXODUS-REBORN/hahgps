package ru.hahgps.util;
import java.util.ArrayList;
import java.util.List;
public final class FuzzyMatcher {
    private FuzzyMatcher() {
    }
    public static int levenshtein(String a, String b) {
        if (a == null || b == null) {
            return Integer.MAX_VALUE;
        }
        a = a.toLowerCase();
        b = b.toLowerCase();
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

    public static double similarity(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        a = a.toLowerCase();
        b = b.toLowerCase();
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) {
            return 1.0;
        }
        return 1.0 - (double) levenshtein(a, b) / maxLen;
    }

    public static boolean matches(String query, String target, double threshold) {
        if (query == null || target == null) {
            return false;
        }
        String q = query.toLowerCase().trim();
        String t = target.toLowerCase().trim();
        if (q.isEmpty()) {
            return true;
        }
        if (t.contains(q)) {
            return true;
        }
        return similarity(q, t) >= threshold;
    }

    public static List<Match> findBest(String query, List<String> targets, int limit, double threshold) {
        List<Match> results = new ArrayList<>();
        for (String target : targets) {
            double sim = similarity(query, target);
            if (sim >= threshold) {
                results.add(new Match(target, sim));
            }
        }
        results.sort((a, b) -> Double.compare(b.similarity, a.similarity));
        if (results.size() > limit) {
            return results.subList(0, limit);
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
