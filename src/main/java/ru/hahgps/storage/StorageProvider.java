package ru.hahgps.storage;
import ru.hahgps.model.Category;
import ru.hahgps.model.Poi;
import ru.hahgps.model.Route;
import ru.hahgps.model.Tracker;
import ru.hahgps.model.TrackerShare;
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

    /**
     * Записать, что игрок искал это место этим запросом. Одна строка на
     * пару «место + игрок + запрос», поэтому счётчик игроков не накручивается
     * повторными поисками одного и того же человека.
     */
    void recordPoiSearch(int poiId, UUID playerId, String query);

    /** {@code poiId -> (нормализованный запрос -> сколько разных игроков искали)}. */
    Map<Integer, Map<String, Integer>> getPoiSearchStats();

    /** {@code poiId -> сколько разных игроков вообще искали это место}. */
    Map<Integer, Integer> getPoiSearcherCounts();

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

    /** Установленные трекеры iTrak. */
    List<Tracker> getTrackers();

    /** Сохранить новый трекер; возвращает запись с присвоенным id. */
    Tracker createTracker(Tracker tracker);

    /** Обновить координаты и состояние существующего трекера. */
    boolean updateTracker(Tracker tracker);

    /**
     * Обновить пачку трекеров одной транзакцией.
     *
     * Тик iTrak собирает изменившиеся трекеры и пишет их разом: раньше каждый
     * трекер шёл отдельным UPDATE и отдельным обращением к пулу БД, и на
     * десятках движущихся маячков пачка записей заметно занимала поток БД.
     *
     * @return сколько строк обновлено
     */
    int updateTrackers(java.util.Collection<Tracker> trackers);

    boolean deleteTracker(int id);

    /** Кому владелец открыл доступ: {@code trackerId -> игроки}. */
    java.util.Map<Integer, List<TrackerShare>> getTrackerShares();

    /** Открыть доступ к трекеру (повторный вызов обновляет имя). */
    boolean addTrackerShare(int trackerId, java.util.UUID playerId, String playerName,
                            java.util.UUID addedBy);

    /** Отозвать доступ. */
    boolean removeTrackerShare(int trackerId, java.util.UUID playerId);

    /**
     * Зарезервировать блок порядковых номеров для кодов трекеров.
     *
     * Счётчик живёт в БД, поэтому коды не повторяются после перезапуска:
     * серверу достаётся диапазон, который уже никто не выдаст.
     *
     * @return первый номер зарезервированного блока
     */
    long reserveTrackerCodeBlock(int count);

    @Override
    void close();
}
