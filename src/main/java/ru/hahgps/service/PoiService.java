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


        Location loc = player.getLocation().clone();
        String author = player.getName();
        if (loc.getWorld() == null) {
            return CompletableFuture.completedFuture(null);
        }
        return dataService.getCategoryByName(categoryName).thenCompose(category -> {
            int categoryId;
            if (category != null) {
                categoryId = category.getId();
            } else {
                // Неизвестная категория — не молчаливый откат на первую: игрок
                // должен знать, в какую категорию реально попала точка.
                return dataService.getCategories().thenCompose(categories -> {
                    if (categories.isEmpty()) {
                        player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                                .deserialize("<red>Нет ни одной категории — точка не создана. Создайте категорию: /gps category create <имя>"));
                        return CompletableFuture.completedFuture(null);
                    }
                    Category first = categories.get(0);
                    player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                            .deserialize("<yellow>Категория «" + safe(categoryName)
                                    + "» не найдена — точка сохранена в «" + safe(first.getName()) + "»."));
                    return savePoi(name, first.getId(), description, loc, author);
                });
            }
            return savePoi(name, categoryId, description, loc, author);
        });
    }

    private static String safe(String raw) {
        return raw == null ? "?" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize(ru.hahgps.gui.GuiTheme.escapeForMini(raw)));
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
