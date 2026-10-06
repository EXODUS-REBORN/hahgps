package ru.hahgps.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FuzzyMatcherTest {

    @Test
    void levenshteinCountsSingleEdits() {
        assertEquals(0, FuzzyMatcher.levenshtein("spawn", "spawn"));
        assertEquals(1, FuzzyMatcher.levenshtein("spawn", "spavn"));
        assertEquals(1, FuzzyMatcher.levenshtein("spawn", "spaw"));
        assertEquals(2, FuzzyMatcher.levenshtein("spawn", "spa"), "два удаления на конце");
        assertEquals(Integer.MAX_VALUE, FuzzyMatcher.levenshtein(null, "spawn"));
    }

    @Test
    void similarityIsCaseInsensitive() {
        assertEquals(1.0, FuzzyMatcher.similarity("SPAWN", "spawn"), 0.0001);
        assertFalse(FuzzyMatcher.matches(null, "spawn", 0.4), "null-запрос не совпадает ни с чем");
        assertFalse(FuzzyMatcher.matches("spawn", null, 0.4));
    }

    @Test
    void matchesAcceptsSubstringEvenBelowThreshold() {
        assertTrue(FuzzyMatcher.matches("spa", "spawn", 0.99),
                "подстрока принимается даже при недостижимом пороге");
        assertTrue(FuzzyMatcher.matches("", "что угодно", 0.4), "пустой запрос пропускает всё");
        assertFalse(FuzzyMatcher.matches("zzz", "spawn", 0.8));
    }

    @Test
    void findBestSortsBySimilarityAndRespectsLimit() {
        List<FuzzyMatcher.Match> found = FuzzyMatcher.findBest("spawn",
                List.of("spawn", "spawn2", "аэропорт"), 2, 0.5);

        assertEquals(2, found.size(), "лимит соблюдён");
        assertEquals("spawn", found.get(0).target, "самое похожее — первым");
        assertTrue(found.get(0).similarity >= found.get(1).similarity);
        assertFalse(found.stream().anyMatch(m -> m.target.equals("аэропорт")));
    }

    @Test
    void findBestHandlesEmptyInput() {
        assertTrue(FuzzyMatcher.findBest("x", List.of(), 5, 0.4).isEmpty());
        assertTrue(FuzzyMatcher.findBest("x", null, 5, 0.4).isEmpty());
        assertTrue(FuzzyMatcher.findBest("x", List.of("y"), 0, 0.4).isEmpty());
    }

    @Test
    void upperBoundIsLengthBased() {
        assertEquals(1.0, FuzzyMatcher.upperBound("abc", "xyz"), 0.0001);
        assertEquals(0.5, FuzzyMatcher.upperBound("ab", "abcd"), 0.0001);
    }
}
