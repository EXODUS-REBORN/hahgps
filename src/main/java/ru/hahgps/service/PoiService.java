package ru.hahgps.service;
import ru.hahgps.model.Category;
import ru.hahgps.model.Poi;
import ru.hahgps.storage.DataService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
public class PoiService {

    private final DataService dataService;

    public PoiService(DataService dataService) {
        this.dataService = dataService;
    }

    public CompletableFuture<Poi> createPoi(Player player, String name, String categoryName, String description) {
        return dataService.getCategoryByName(categoryName).thenCompose(category -> {
            int categoryId;
            if (category != null) {
                categoryId = category.getId();
            } else {

                return dataService.getCategories().thenCompose(categories -> {
                    if (categories.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    Category first = categories.get(0);
                    return savePoi(player, name, first.getId(), description);
                });
            }
            return savePoi(player, name, categoryId, description);
        });
    }

    private CompletableFuture<Poi> savePoi(Player player, String name, int categoryId, String description) {
        Location loc = player.getLocation();
        Poi poi = new Poi(0, name, categoryId, loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(),
                description != null ? description : "",
                player.getName(), System.currentTimeMillis());
        return dataService.createPoi(poi);
    }

    public CompletableFuture<Boolean> deletePoi(String name) {
        return dataService.getPoiByName(name).thenCompose(poi -> {
            if (poi == null) {
                return CompletableFuture.completedFuture(false);
            }
            return dataService.deletePoi(poi.getId());
        });
    }

    public CompletableFuture<Poi> loadPoiWithRating(String name) {
        return dataService.getPoiByName(name).thenCompose(poi -> {
            if (poi == null) {
                return CompletableFuture.completedFuture(null);
            }
            return dataService.getRatings(poi.getId()).thenApply(ratings -> {
                if (!ratings.isEmpty()) {
                    double avg = ratings.values().stream().mapToInt(Integer::intValue).average().orElse(0);
                    poi.setRating(avg);
                    poi.setRatingCount(ratings.size());
                }
                return poi;
            });
        });
    }

    public CompletableFuture<List<Poi>> loadAllPoisWithRatings() {
        return dataService.getPois().thenCompose(pois -> {
            List<CompletableFuture<Void>> futures = pois.stream()
                    .map(poi -> dataService.getRatings(poi.getId()).thenAccept(ratings -> {
                        if (!ratings.isEmpty()) {
                            double avg = ratings.values().stream().mapToInt(Integer::intValue).average().orElse(0);
                            poi.setRating(avg);
                            poi.setRatingCount(ratings.size());
                        }
                    }))
                    .toList();
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> pois);
        });
    }

    public CompletableFuture<Void> ratePoi(String name, UUID playerId, int rating) {
        return dataService.getPoiByName(name).thenCompose(poi -> {
            if (poi == null) {
                return CompletableFuture.completedFuture(null);
            }
            return dataService.setRating(poi.getId(), playerId, rating);
        });
    }

    public double distanceTo(Player player, Poi poi) {
        Location poiLoc = poi.getLocation();
        if (poiLoc == null) {
            return Double.MAX_VALUE;
        }
        return player.getLocation().distance(poiLoc);
    }
}
