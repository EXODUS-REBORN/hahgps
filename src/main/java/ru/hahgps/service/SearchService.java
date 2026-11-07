package ru.hahgps.service;

import ru.hahgps.model.Poi;
import ru.hahgps.storage.DataService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Поиск мест по названию.
 *
 * Ранжирование — {@link SearchRanking}: честная похожесть плюс маленькая
 * надбавка за популярность (сколько разных игроков искали это место тем же
 * текстом). Количество результатов задаётся для каждого игрока отдельно
 * (1..25), по умолчанию — {@code search.max-results} из конфига.
 */
public class SearchService {

    public static final int MIN_RESULTS = 1;
    public static final int MAX_RESULTS = 25;

    private final DataService dataService;
    /** Значения из конфига на момент старта: запасной вариант, если плагина нет. */
    private final int defaultMaxResults;
    private final double defaultThreshold;

    /** Сколько мест показывать конкретному игроку (настройка в меню поиска). */
    private final Map<UUID, Integer> playerLimits = new ConcurrentHashMap<>();

    public SearchService(DataService dataService, int maxResults, double threshold) {
        this.dataService = dataService;
        this.defaultMaxResults = maxResults;
        this.defaultThreshold = threshold;
    }

    /**
     * Настройки поиска читаются на каждый запрос, а не один раз при старте:
     * иначе {@code /gps reload} (и переключение настроек) не менял ни порог,
     * ни лимит результатов до перезапуска сервера.
     */
    private int maxResults() {
        ru.hahgps.HahGpsPlugin plugin = ru.hahgps.HahGpsPlugin.getInstance();
        if (plugin != null && plugin.getConfigManager() != null) {
            return clampLimit(plugin.getConfigManager().getSearchMaxResults());
        }
        return clampLimit(defaultMaxResults);
    }

    private double threshold() {
        ru.hahgps.HahGpsPlugin plugin = ru.hahgps.HahGpsPlugin.getInstance();
        if (plugin != null && plugin.getConfigManager() != null) {
            return plugin.getConfigManager().getSearchThreshold();
        }
        return defaultThreshold;
    }

    public static int clampLimit(int value) {
        return Math.max(MIN_RESULTS, Math.min(MAX_RESULTS, value));
    }

    /** Сколько мест показывать игроку: его настройка или значение из конфига. */
    public int limitFor(UUID playerId) {
        Integer stored = playerId == null ? null : playerLimits.get(playerId);
        return stored == null ? maxResults() : clampLimit(stored);
    }

    /** Сохранить настройку игрока; возвращает применённое значение. */
    public int setLimitFor(UUID playerId, int limit) {
        int value = clampLimit(limit);
        if (playerId != null) {
            playerLimits.put(playerId, value);
        }
        return value;
    }

    public static class SearchResult {
        public final String name;
        /** Итоговый балл (похожесть + надбавка за популярность), 0..1. */
        public final double similarity;
        public final Poi poi;
        /** Сколько разных игроков искали именно эту точку. */
        public final int searchers;
        /** Сколько разных игроков искали её именно этим запросом. */
        public final int querySearchers;

        public SearchResult(String name, double similarity, Poi poi, int searchers, int querySearchers) {
            this.name = name;
            this.similarity = similarity;
            this.poi = poi;
            this.searchers = searchers;
            this.querySearchers = querySearchers;
        }

        /** Процент совпадения для показа игроку. */
        public int percent() {
            return (int) Math.round(Math.min(1.0, Math.max(0.0, similarity)) * 100);
        }
    }

    public CompletableFuture<List<SearchResult>> search(String query) {
        return search(query, null, null);
    }

    public CompletableFuture<List<SearchResult>> search(String query, UUID playerId) {
        return search(query, playerId, null);
    }

    /**
     * @param limitOverride сколько мест вернуть (1..25); null — настройка игрока
     */
    public CompletableFuture<List<SearchResult>> search(String query, UUID playerId, Integer limitOverride) {
        if (query == null || query.trim().isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        final String normalized = SearchRanking.normalizeQuery(query);
        final int limit = limitOverride == null ? limitFor(playerId) : clampLimit(limitOverride);
        final double minSimilarity = threshold();
        // Ранжирование всегда в пуле БД: похожесть по всем точкам в главном
        // потоке — это лаг на большом списке мест. Статистика популярности
        // перечитывается, если кто-то искал после прошлого чтения.
        return dataService.computeAsync(() -> {
            dataService.refreshSearchStatsNow();
            return rank(normalized, limit, minSimilarity, playerId);
        });
    }

    private List<SearchResult> rank(String query, int limit, double minSimilarity, UUID playerId) {
        Map<Integer, Map<String, Integer>> stats = dataService.searchStatsSnapshot();
        Map<Integer, Integer> counts = dataService.searcherCountsSnapshot();

        List<SearchResult> results = new ArrayList<>();
        Poi best = null;
        double bestBase = 0.0;

        for (Poi poi : dataService.getCachedPois()) {
            if (poi == null || poi.getName() == null || poi.getName().isEmpty()) {
                continue;
            }
            SearchRanking.Score score = SearchRanking.score(query, poi.getName(), minSimilarity);
            if (score.similarity() <= 0.0) {
                continue;
            }
            int querySearchers = querySearchers(stats, poi.getId(), query);
            results.add(new SearchResult(poi.getName(),
                    SearchRanking.withBoost(score.similarity(), querySearchers), poi,
                    counts.getOrDefault(poi.getId(), 0), querySearchers));
            if (score.similarity() > bestBase) {
                bestBase = score.similarity();
                best = poi;
            }
        }

        results.sort((a, b) -> {
            int byScore = Double.compare(b.similarity, a.similarity);
            if (byScore != 0) {
                return byScore;
            }
            int bySearchers = Integer.compare(b.searchers, a.searchers);
            if (bySearchers != 0) {
                return bySearchers;
            }
            return String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
        });
        if (results.size() > limit) {
            results = new ArrayList<>(results.subList(0, limit));
        }

        // Сигнал для популярности: только уверенное совпадение (адрес или
        // точное имя), иначе в статистику попадал бы случайный «похожий»
        // результат, и он же потом всплывал бы в топ.
        if (playerId != null && best != null && bestBase >= SearchRanking.RELIABLE) {
            dataService.recordPoiSearch(best.getId(), playerId, query);
        }
        return results;
    }

    private static int querySearchers(Map<Integer, Map<String, Integer>> stats, int poiId, String query) {
        Map<String, Integer> byQuery = stats.get(poiId);
        if (byQuery == null) {
            return 0;
        }
        Integer value = byQuery.get(query);
        return value == null ? 0 : value;
    }
}
