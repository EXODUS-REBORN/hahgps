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

    /** Сколько точек ссылается на категорию — чтобы не оставлять их сиротами при удалении. */
    int countPoisInCategory(int categoryId);

    boolean updatePoi(Poi poi);

    void setRating(int poiId, UUID playerId, int rating);

    Map<UUID, Integer> getRatings(int poiId);

    /** Все оценки одним запросом: {@code poiId -> (uuid -> оценка)}. */
    Map<Integer, Map<UUID, Integer>> getAllRatings();

    Route createRoute(Route route);

    boolean deleteRoute(int id);

    List<Route> getRoutes();

    Route getRouteByName(String name);

    /** Все сохранённые админские рёбра графа (между узлами маршрутов). */
    List<ru.hahgps.model.EdgeRecord> getEdges();

    /** Сохранить (заменить существующее с теми же концами) ребро. */
    void saveEdge(ru.hahgps.model.EdgeRecord edge);

    /** Удалить ребро с этими концами. */
    void deleteEdge(ru.hahgps.model.EdgeRecord edge);

    @Override
    void close();
}
