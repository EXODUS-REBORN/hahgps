package ru.hahgps.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ранжирование поиска: честная похожесть плюс ограниченная надбавка за
 * популярность. Надбавка не должна позволять месту с явно худшим совпадением
 * обогнать лучшее совпадение.
 */
class SearchRankingTest {

    private static final double THRESHOLD = 0.4;

    @Test
    void exactNameWins() {
        assertEquals(1.0, SearchRanking.score("авиапарк", "Авиапарк", THRESHOLD).similarity());
        assertEquals(0.9, SearchRanking.score("авиа", "Авиапарк", THRESHOLD).similarity());
        assertEquals(0.85, SearchRanking.score("парк", "ТЦ Авиапарк", THRESHOLD).similarity());
    }

    @Test
    void addressWithNumberMatchesByWords() {
        // Ровно жалоба: «хайсовская 10» не должно находить одно «Авиапарк»
        // лучше, чем место, где весь адрес совпал.
        SearchRanking.Score address = SearchRanking.score("хайсовская 10",
                "ТЦ Авиапарк, Хайсовская 10", THRESHOLD);
        SearchRanking.Score mall = SearchRanking.score("хайсовская 10", "Авиапарк", THRESHOLD);
        assertTrue(address.similarity() >= 0.95, "адрес должен совпасть по словам: " + address);
        assertTrue(address.similarity() > mall.similarity());
        assertTrue(address.reliable());
    }

    @Test
    void oneWordOfAddressIsNotReliable() {
        SearchRanking.Score score = SearchRanking.score("хайсовская 10", "Хайсовская", THRESHOLD);
        assertTrue(score.similarity() > 0.0);
        assertFalse(score.reliable(), "одно слово из адреса — не повод писать статистику");
    }

    @Test
    void popularityBoostIsBounded() {
        assertTrue(SearchRanking.boost(0) == 0.0);
        assertTrue(SearchRanking.boost(1) > 0.0);
        assertTrue(SearchRanking.boost(50) <= SearchRanking.MAX_BOOST);
        assertTrue(SearchRanking.boost(1_000_000) <= SearchRanking.MAX_BOOST);
        assertTrue(SearchRanking.boost(50) > SearchRanking.boost(1));
    }

    @Test
    void popularPlaceCannotOvertakeClearlyBetterMatch() {
        // Худшее совпадение 67% даже с максимальной популярностью остаётся
        // ниже честных 85% — «накрутить» место в топ нельзя.
        double popular = SearchRanking.withBoost(0.67, 1_000_000);
        double honest = SearchRanking.withBoost(0.85, 0);
        assertTrue(popular < honest, popular + " не должен обгонять " + honest);
        assertTrue(popular <= 0.67 + SearchRanking.MAX_BOOST);
    }

    @Test
    void weakMatchesDoNotGetPopularityAtAll() {
        assertEquals(0.3, SearchRanking.withBoost(0.3, 1000));
        assertEquals(SearchRanking.withBoost(0.6, 0), 0.6, 0.0);
    }

    @Test
    void queryIsNormalizedForStatistics() {
        assertEquals("хайсовская 10", SearchRanking.normalizeQuery("  Хайсовская   10  "));
        assertEquals("", SearchRanking.normalizeQuery(null));
        String longQuery = "x".repeat(200);
        assertEquals(SearchRanking.MAX_QUERY_LENGTH, SearchRanking.normalizeQuery(longQuery).length());
    }

    @Test
    void limitIsClampedToTwentyFive() {
        assertEquals(1, SearchService.clampLimit(0));
        assertEquals(1, SearchService.clampLimit(-10));
        assertEquals(25, SearchService.clampLimit(26));
        assertEquals(7, SearchService.clampLimit(7));
    }
}
