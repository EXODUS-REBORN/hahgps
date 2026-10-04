package ru.hahgps.service;
import ru.hahgps.model.Poi;
import ru.hahgps.storage.DataService;
import ru.hahgps.util.FuzzyMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SearchService {

    private final DataService dataService;
    private final int maxResults;
    private final double threshold;

    public SearchService(DataService dataService, int maxResults, double threshold) {
        this.dataService = dataService;
        this.maxResults = maxResults;
        this.threshold = threshold;
    }

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

    public CompletableFuture<List<SearchResult>> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        final String qLower = query.toLowerCase(java.util.Locale.ROOT);
        final int limit = Math.max(0, maxResults);
        final double minSimilarity = threshold;
        return dataService.getPois().thenApply(pois -> {
            List<SearchResult> results = new ArrayList<>();
            for (Poi poi : pois) {
                if (poi == null || poi.getName() == null || poi.getName().isEmpty()) {
                    continue;
                }
                String name = poi.getName();
                String nameLower = name.toLowerCase(java.util.Locale.ROOT);
                boolean contains = nameLower.contains(qLower);
                double sim;
                if (contains) {
                    sim = FuzzyMatcher.similarity(qLower, nameLower);
                } else {

                    if (FuzzyMatcher.upperBound(qLower, nameLower) < minSimilarity) {
                        continue;
                    }
                    sim = FuzzyMatcher.similarity(qLower, nameLower);
                    if (sim < minSimilarity) {
                        continue;
                    }
                }
                results.add(new SearchResult(name, sim, poi));
            }
            results.sort((a, b) -> Double.compare(b.similarity, a.similarity));
            if (results.size() > limit) {
                return new ArrayList<>(results.subList(0, limit));
            }
            return results;
        });
    }
}
