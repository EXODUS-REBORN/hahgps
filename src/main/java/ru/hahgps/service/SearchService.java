package ru.hahgps.service;

import ru.hahgps.model.Poi;
import ru.hahgps.storage.DataService;
import ru.hahgps.util.FuzzyMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Сервис поиска по точкам с нечётким совпадением.
 */
public class SearchService {

    private final DataService dataService;
    private final int maxResults;
    private final double threshold;

    public SearchService(DataService dataService, int maxResults, double threshold) {
        this.dataService = dataService;
        this.maxResults = maxResults;
        this.threshold = threshold;
    }

    /**
     * Результат поиска.
     */
    public static class SearchResult {
        public final String name;
        public final double similarity;
        public final Poi poi;

        public SearchResult(String name, double similarity, Poi poi) {
            this.name = name;
            this.similarity = similarity;
            this.poi = poi;
        }
    }

    /**
     * Поиск по точкам.
     */
    public CompletableFuture<List<SearchResult>> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        return dataService.getPois().thenApply(pois -> {
            List<SearchResult> results = new ArrayList<>();
            for (Poi poi : pois) {
                double sim = FuzzyMatcher.similarity(query, poi.getName());
                if (sim >= threshold || poi.getName().toLowerCase().contains(query.toLowerCase())) {
                    results.add(new SearchResult(poi.getName(), sim, poi));
                }
            }
            results.sort((a, b) -> Double.compare(b.similarity, a.similarity));
            if (results.size() > maxResults) {
                return results.subList(0, maxResults);
            }
            return results;
        });
    }
}
