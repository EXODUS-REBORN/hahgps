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

    /**
     * Итог создания точки: игрок должен видеть, в какую категорию она реально
     * попала. Раньше сервис сам отправлял сообщения с захардкоженным русским
     * текстом и цветами вне палитры, а команда показывала поверх этого ещё
     * одно «категория не найдена» — теперь тексты только в messages_*.yml.
     *
     * @param poi              созданная точка или null, если создавать некуда
     * @param fallbackCategory категория, в которую точка сохранена вместо
     *                         запрошенной (null — запрошенная категория есть
     *                         либо не запрашивалась)
     */
    public record CreateOutcome(Poi poi, Category fallbackCategory) {
    }

    public CompletableFuture<CreateOutcome> createPoi(Player player, String name, String categoryName,
                                                      String description) {
        Location loc = player.getLocation().clone();
        String author = player.getName();
        if (loc.getWorld() == null) {
            return CompletableFuture.completedFuture(new CreateOutcome(null, null));
        }
        boolean noCategoryRequested = categoryName == null || categoryName.isBlank();
        if (noCategoryRequested) {
            return firstCategory().thenCompose(first -> first == null
                    ? CompletableFuture.completedFuture(new CreateOutcome(null, null))
                    : savePoi(name, first.getId(), description, loc, author)
                            .thenApply(poi -> new CreateOutcome(poi, null)));
        }
        return dataService.getCategoryByName(categoryName).thenCompose(category -> {
            if (category != null) {
                return savePoi(name, category.getId(), description, loc, author)
                        .thenApply(poi -> new CreateOutcome(poi, null));
            }
            // Неизвестная категория — не молчаливый откат на первую: игрок
            // должен знать, в какую категорию реально попала точка.
            return firstCategory().thenCompose(first -> first == null
                    ? CompletableFuture.completedFuture(new CreateOutcome(null, null))
                    : savePoi(name, first.getId(), description, loc, author)
                            .thenApply(poi -> new CreateOutcome(poi, first)));
        });
    }

    private CompletableFuture<Category> firstCategory() {
        return dataService.getCategories().thenApply(categories ->
                categories == null || categories.isEmpty() ? null : categories.get(0));
    }

    private CompletableFuture<Poi> savePoi(String name, int categoryId, String description,
                                           Location loc, String author) {
        Poi poi = new Poi(0, name, categoryId, loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(),
                description != null ? description : "",
                author, System.currentTimeMillis());
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
        return dataService.getPois().thenCombine(dataService.getAllRatings(), (pois, ratings) -> {
            for (Poi poi : pois) {
                Map<UUID, Integer> own = ratings.get(poi.getId());
                if (own == null || own.isEmpty()) {
                    continue;
                }
                double avg = own.values().stream().mapToInt(Integer::intValue).average().orElse(0);
                poi.setRating(avg);
                poi.setRatingCount(own.size());
            }
            return pois;
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
        Location poiLoc = poi != null ? poi.getLocation() : null;
        if (poiLoc == null) {
            return Double.MAX_VALUE;
        }
        Location playerLoc = player.getLocation();

        return ru.hahgps.util.LocationUtil.distance(playerLoc, poiLoc);
    }
}
