package ru.hahgps.storage;
import ru.hahgps.model.Category;
import ru.hahgps.model.Poi;
import ru.hahgps.model.Route;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface StorageProvider extends AutoCloseable {

    void init() throws Exception;

    Category createCategory(String name, boolean defaultCategory);

    boolean deleteCategory(int id);

    List<Category> getCategories();

    Category getCategoryByName(String name);

    Category getCategoryById(int id);

    Poi createPoi(Poi poi);

    boolean deletePoi(int id);

    Poi getPoiByName(String name);

    Poi getPoiById(int id);

    List<Poi> getPois();

    List<Poi> getPoisByCategory(int categoryId);

    boolean updatePoi(Poi poi);

    void setRating(int poiId, UUID playerId, int rating);

    Map<UUID, Integer> getRatings(int poiId);

    Route createRoute(Route route);

    boolean deleteRoute(int id);

    List<Route> getRoutes();

    Route getRouteByName(String name);

    @Override
    void close();
}
