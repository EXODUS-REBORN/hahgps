package ru.hahgps.service;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.config.ConfigManager;
import ru.hahgps.gui.GuiTheme;
import ru.hahgps.model.NavigationSession;
import ru.hahgps.model.Poi;
import ru.hahgps.model.Tracker;
import ru.hahgps.storage.DataService;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Карта игрока: своя MapView, отрисовка порциями через TerrainRenderer.
 *
 * Как это ощущается игроку: карта открывается в руке и сама едет за игроком
 * (map.follow-player), поэтому пешком по ней не надо «догонять» себя. Сдвинуть
 * полотно вручную можно Shift+мышью — без Shift взгляд карту не трогает,
 * иначе она дёргалась при каждом повороте головы. ПКМ возвращает центр на
 * игрока, Shift+ПКМ открывает меню (зум, следование, центр).
 *
 * Технически: картинка принадлежит {@link MapView} (одна карта — один
 * MapView), а не игроку, поэтому рендерер ставится на вид ровно один раз.
 * Раньше на каждый вызов {@code openMap} добавлялся новый анонимный рендерер —
 * на сотом открытии их было сто, и карта заметно тормозила.
 */
public class MapService {

    /** Ширина полотна карты в пикселях (ванильная карта 128×128). */
    public static final int MAP_SIZE = 128;

    /** Не чаще этой паузы полотно карты перерисовывается при движении мышью. */
    private static final long PAN_RENDER_INTERVAL_MS = 300L;

    /** Пауза после последнего движения панорамой, через которую идёт рендер. */
    private static final long PAN_SETTLE_TICKS = 8L;

    /**
     * Дольше этого непрерывный жест не откладывает настоящий рендер: иначе
     * при бесконечном вращении мыши карта показывала бы только сдвинутую
     * копию со всё большими серыми полосами.
     */
    private static final long PAN_MAX_DEFER_MS = 1_500L;

    /** Следование: не чаще этой паузы карта переезжает за игроком. */
    private static final long FOLLOW_RENDER_INTERVAL_MS = 700L;

    /** Сдвиг игрока, после которого карта переезжает (блоков). */
    private static final int FOLLOW_MIN_BLOCKS = 6;

    /** Как часто тик карты проверяет игроков и маркеры (в тиках). */
    private static final long TICK_INTERVAL = 10L;

    /** Порядок масштабов: от самого крупного к самому мелкому. */
    private static final MapView.Scale[] SCALES = {
            MapView.Scale.CLOSEST, MapView.Scale.CLOSE, MapView.Scale.NORMAL,
            MapView.Scale.FAR, MapView.Scale.FARTHEST};

    /** Метка нашей карты в PDC — чтобы не трогать чужие ванильные карты. */
    public static final NamespacedKey MAP_MARKER = NamespacedKey.fromString("hahgps:gps_map");

    private final HahGpsPlugin plugin;
    private final TerrainRenderer terrainRenderer;

    private final Map<UUID, MapView> playerMaps = new ConcurrentHashMap<>();
    /** Готовые картинки по номеру MapView. */
    private final Map<Integer, BufferedImage> images = new ConcurrentHashMap<>();
    private final Map<Integer, MapRenderer> renderers = new ConcurrentHashMap<>();
    /** Все выданные MapView: нужны, чтобы снять рендерер при выключении. */
    private final Map<Integer, MapView> views = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicLong> renderTokens = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPanRender = new ConcurrentHashMap<>();
    /** Карта следует за игроком или закреплена в точке, куда её сдвинули. */
    private final Map<UUID, Boolean> follow = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFollowRender = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRefresh = new ConcurrentHashMap<>();
    /** Идёт ли рендер этого MapView (один на вид, чтобы задачи не убивали друг друга). */
    private final Map<Integer, Boolean> renderInFlight = new ConcurrentHashMap<>();
    /** Пока рендер шёл, полотно просили обновить ещё раз. */
    private final Map<Integer, Boolean> renderDirty = new ConcurrentHashMap<>();
    /** Отложенный (дебаунс) рендер панорамы по игроку. */
    private final Map<UUID, BukkitTask> pendingRenders = new ConcurrentHashMap<>();
    /** Когда начался текущий жест панорамы (для ограничения откладывания). */
    private final Map<UUID, Long> panGestureStart = new ConcurrentHashMap<>();
    /** Что сейчас нарисовано на полотне (сдвинутая копия) — рисует рендерер. */
    private final Map<Integer, BufferedImage> shownImages = new ConcurrentHashMap<>();
    /** Центр, в котором снята картинка из {@link #images}. */
    private final Map<Integer, int[]> renderedCenters = new ConcurrentHashMap<>();
    private volatile BukkitTask tickTask;

    /**
     * Пустая картинка до первого рендера.
     *
     * Лежит в отдельном holder-классе намеренно: {@code new BufferedImage}
     * тянет за собой инициализацию AWT ({@code ColorSpace}, {@code Raster}),
     * а это десятки миллисекунд на нормальной машине и секунды на загруженной.
     * В статическом поле самого {@code MapService} эта инициализация попадала
     * в {@code onStorageReady} — то есть в главный поток при старте плагина,
     * и Paper писал «The server has not responded for 10 seconds».
     */
    private static final class Blank {
        private static final BufferedImage IMAGE =
                new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_RGB);
    }

    public MapService(HahGpsPlugin plugin) {
        this.plugin = plugin;
        this.terrainRenderer = new TerrainRenderer(plugin);
        startTicker();
    }

    private void startTicker() {
        try {
            tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    tick();
                } catch (Throwable error) {
                    plugin.getLogger().warning("HahGps: тик карты упал: " + error);
                }
            }, 40L, TICK_INTERVAL);
        } catch (Throwable ignored) {
            // Плагин ещё не включён — тип запустит openMap.
        }
    }

    // ------------------------------------------------------------------
    // Открытие и навигация
    // ------------------------------------------------------------------

    public void openMap(Player player) {
        World world = player.getWorld();
        MapView view = getOrCreateMapView(player, world);
        view.setCenterX(clampCenter(player.getLocation().getBlockX()));
        view.setCenterZ(clampCenter(player.getLocation().getBlockZ()));
        follow.put(player.getUniqueId(), plugin.getConfigManager().isMapFollowPlayer());
        if (!ensureMapInHand(player, view)) {
            player.sendMessage(plugin.getMessages().getPrefixed("map-inventory-full"));
            return;
        }
        player.sendMap(view);
        renderAsync(player, view);
    }

    /** Показать уже существующую карту, не сбрасывая её центр на игроке. */
    public void showMap(Player player) {
        MapView view = playerMaps.get(player.getUniqueId());
        if (view == null || view.getWorld() == null
                || !ru.hahgps.util.LocationUtil.sameWorld(view.getWorld(), player.getWorld())) {
            openMap(player);
            return;
        }
        if (!ensureMapInHand(player, view)) {
            player.sendMessage(plugin.getMessages().getPrefixed("map-inventory-full"));
            return;
        }
        player.sendMap(view);
        renderAsync(player, view);
    }

    /**
     * Навести карту на точку (трекер) и показать её в руке: так работает
     * «find» — карта как в «Локаторе», только по своим маячкам. После этого
     * карта закреплена; ПКМ снова вернёт её на игрока.
     */
    public boolean focus(Player player, Location target) {
        if (player == null || target == null || target.getWorld() == null) {
            return false;
        }
        MapView view = getOrCreateMapView(player, target.getWorld());
        view.setCenterX(clampCenter(target.getBlockX()));
        view.setCenterZ(clampCenter(target.getBlockZ()));
        follow.put(player.getUniqueId(), false);
        if (!ensureMapInHand(player, view)) {
            player.sendMessage(plugin.getMessages().getPrefixed("map-inventory-full"));
            return false;
        }
        player.sendMap(view);
        renderAsync(player, view);
        return true;
    }

    /** Масштаб карты игрока (null — карта ещё не открывалась). */
    public MapView.Scale scaleOf(Player player) {
        MapView view = playerMaps.get(player.getUniqueId());
        return view == null ? null : view.getScale();
    }

    /** Блоков на пиксель у текущей карты игрока. */
    public double blocksPerPixel(Player player) {
        MapView.Scale scale = scaleOf(player);
        return TerrainRenderer.blocksPerPixel(scale == null ? MapView.Scale.CLOSE : scale);
    }

    /** Карта следует за игроком? */
    public boolean isFollowing(Player player) {
        if (player == null) {
            return false;
        }
        return follow.getOrDefault(player.getUniqueId(), plugin.getConfigManager().isMapFollowPlayer());
    }

    public void setFollowing(Player player, boolean value) {
        if (player != null) {
            follow.put(player.getUniqueId(), value);
        }
    }

    /**
     * Карта в главной руке. Именно там она разворачивается на весь экран;
     * мини-карта в левой руке мышью не управляется.
     */
    public boolean isHoldingMapMainHand(Player player) {
        if (player == null) {
            return false;
        }
        MapView view = playerMaps.get(player.getUniqueId());
        if (view == null) {
            return false;
        }
        return matchesMap(player.getInventory().getItemInMainHand(), view.getId());
    }

    /** Этот предмет — карта, выданная HahGps (даже если её ещё не открывали). */
    public static boolean isGpsMap(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP || !item.hasItemMeta()) {
            return false;
        }
        if (!(item.getItemMeta() instanceof MapMeta meta)) {
            return false;
        }
        Byte marker = meta.getPersistentDataContainer().get(MAP_MARKER, PersistentDataType.BYTE);
        if (marker != null && marker != 0) {
            return true;
        }
        // Карты, выданные до 1.3.1, метки в PDC не имеют: у них только модель.
        Integer model = meta.getCustomModelData();
        return model != null && model == ru.hahgps.item.ItemIds.MAP_ITEM;
    }

    private static boolean matchesMap(ItemStack item, int mapId) {
        if (item == null || item.getType() != Material.FILLED_MAP || !item.hasItemMeta()) {
            return false;
        }
        if (!(item.getItemMeta() instanceof MapMeta meta) || !meta.hasMapId()) {
            return false;
        }
        return meta.getMapId() == mapId;
    }

    /**
     * Сдвиг полотна карты (панорама Shift+мышью): центр карты едет за
     * курсором, картинка сразу сдвигается, а точная перерисовка приходит из
     * фонового рендера. Рендер не запускается на каждое движение мыши — он
     * откладывается и выполняется один раз, когда жест закончился: иначе
     * 16 384 точки полотна переснимались бы по восемь раз в секунду.
     */
    public void pan(Player player, double dx, double dz) {
        if (player == null || !plugin.isEnabled()) {
            return;
        }
        MapView view = playerMaps.get(player.getUniqueId());
        if (view == null) {
            return;
        }
        // Ручная панорама отвязывает карту от игрока: иначе следующий тик
        // следования вернул бы её обратно и сдвиг «прыгал».
        follow.put(player.getUniqueId(), false);
        long now = System.currentTimeMillis();
        Long last = lastPanRender.get(player.getUniqueId());
        boolean render = last == null || now - last >= PAN_RENDER_INTERVAL_MS;
        if (render) {
            lastPanRender.put(player.getUniqueId(), now);
        }
        int targetX = clampCenter(view.getCenterX() + (int) Math.round(dx));
        int targetZ = clampCenter(view.getCenterZ() + (int) Math.round(dz));
        int stepX = targetX - view.getCenterX();
        int stepZ = targetZ - view.getCenterZ();
        if (stepX == 0 && stepZ == 0) {
            return;
        }
        view.setCenterX(targetX);
        view.setCenterZ(targetZ);
        if (!render) {
            // Жест продолжается: рендер состоится после его окончания.
            scheduleRender(player, view, PAN_SETTLE_TICKS);
            return;
        }
        // Сдвиг считается от центра, в котором снята картинка, поэтому
        // накопленный жест учитывается сам собой — отдельный счётчик не нужен.
        if (shiftImage(view) && isHoldingMapMainHand(player)) {
            player.sendMap(view);
        }
        UUID id = player.getUniqueId();
        long gestureStart = panGestureStart.computeIfAbsent(id, key -> now);
        if (now - gestureStart >= PAN_MAX_DEFER_MS) {
            // Жест длится слишком долго — рисуем по-настоящему сейчас.
            panGestureStart.put(id, now);
            renderAsync(player, view);
            return;
        }
        scheduleRender(player, view, PAN_SETTLE_TICKS);
    }

    /**
     * Отложенный рендер: жест продолжается — таймер сдвигается, работа идёт
     * один раз после его окончания.
     */
    private void scheduleRender(Player player, MapView view, long delayTicks) {
        UUID id = player.getUniqueId();
        BukkitTask previous = pendingRenders.remove(id);
        if (previous != null) {
            try {
                previous.cancel();
            } catch (Throwable ignored) {
            }
        }
        try {
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                pendingRenders.remove(id);
                if (!plugin.isEnabled() || !player.isOnline()
                        || playerMaps.get(id) != view) {
                    return;
                }
                renderAsync(player, view);
            }, delayTicks);
            pendingRenders.put(id, task);
        } catch (Throwable ignored) {
            renderAsync(player, view);
        }
    }

    /**
     * Сдвиг полотна за поворот мыши: {dx, dz} в блоках.
     *
     * За 180° поворота карта проезжает половину своей ширины. Знак обратный:
     * карту «тянут» за курсором, поэтому взгляд вправо уводит центр на запад.
     */
    public static double[] panDelta(float yawDelta, float pitchDelta,
                                    double blocksPerPixel, double sensitivity) {
        double perDegree = MAP_SIZE * blocksPerPixel / 360.0 * sensitivity;
        return new double[]{-yawDelta * perDegree, -pitchDelta * perDegree};
    }

    /** Сдвиг на кнопку меню: без паузы, игрок ждёт результат сразу. */
    public void panStep(Player player, double dx, double dz) {
        lastPanRender.remove(player.getUniqueId());
        pan(player, dx, dz);
    }

    /**
     * Сдвинуть уже нарисованную картинку — панорама отзывается мгновенно.
     *
     * Сдвигается отдельный буфер показа, а не сама отрендеренная картинка:
     * раньше новый кадр затирался сдвинутым, полоса неизвестности (тёмно-серая)
     * утягивалась внутрь полотна при каждом шаге и накапливалась.
     *
     * @return true, если картинка изменилась и её надо отправить игроку
     */
    private boolean shiftImage(MapView view) {
        int viewId = view.getId();
        BufferedImage source = images.get(viewId);
        if (source == null) {
            return false;
        }
        int[] renderedCenter = renderedCenters.get(viewId);
        if (renderedCenter == null) {
            return false;
        }
        double bpp = TerrainRenderer.blocksPerPixel(view.getScale());
        // Сдвиг считаем от центра, в котором картинка действительно снята:
        // несколько шагов панорамы складываются, поэтому смещение не теряется.
        int px = (int) Math.round((view.getCenterX() - renderedCenter[0]) / bpp);
        int pz = (int) Math.round((view.getCenterZ() - renderedCenter[1]) / bpp);
        if (px == 0 && pz == 0) {
            return false;
        }
        BufferedImage shown = shownImages.get(viewId);
        if (shown == null) {
            shown = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_RGB);
            shownImages.put(viewId, shown);
        }
        Graphics2D graphics = shown.createGraphics();
        graphics.setColor(new java.awt.Color(0x202020));
        graphics.fillRect(0, 0, MAP_SIZE, MAP_SIZE);
        graphics.drawImage(source, -px, -pz, null);
        graphics.dispose();
        return true;
    }

    /** Зум: +1 — ближе, −1 — дальше. Возвращает новый масштаб. */
    public MapView.Scale zoom(Player player, int direction) {
        MapView view = playerMaps.get(player.getUniqueId());
        if (view == null) {
            openMap(player);
            view = playerMaps.get(player.getUniqueId());
        }
        if (view == null) {
            return null;
        }
        int index = 0;
        for (int i = 0; i < SCALES.length; i++) {
            if (SCALES[i] == view.getScale()) {
                index = i;
                break;
            }
        }
        int next = Math.max(0, Math.min(SCALES.length - 1, index + direction));
        if (next != index) {
            view.setScale(SCALES[next]);
        }
        player.sendMap(view);
        renderAsync(player, view);
        return view.getScale();
    }

    private static int clampCenter(int value) {
        return Math.max(-29_999_984, Math.min(29_999_984, value));
    }

    /** Центр на игроке и снова следование за ним. */
    public void recenter(Player player) {
        MapView view = playerMaps.get(player.getUniqueId());
        if (view == null) {
            openMap(player);
            return;
        }
        view.setCenterX(clampCenter(player.getLocation().getBlockX()));
        view.setCenterZ(clampCenter(player.getLocation().getBlockZ()));
        follow.put(player.getUniqueId(), plugin.getConfigManager().isMapFollowPlayer());
        player.sendMap(view);
        renderAsync(player, view);
    }

    public void setZoom(Player player, MapView.Scale scale) {
        MapView view = playerMaps.get(player.getUniqueId());
        if (view == null) {
            openMap(player);
            return;
        }
        view.setScale(scale);
        player.sendMap(view);
        renderAsync(player, view);
    }

    /** Текущий центр карты — для меню и подсказок. */
    public int[] centerOf(Player player) {
        MapView view = playerMaps.get(player.getUniqueId());
        return view == null ? null : new int[]{view.getCenterX(), view.getCenterZ()};
    }

    // ------------------------------------------------------------------
    // Тик: следование за игроком и обновление маркеров
    // ------------------------------------------------------------------

    private void tick() {
        if (!plugin.isEnabled() || playerMaps.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long refreshMs = Math.max(1_000L, plugin.getConfigManager().getMapRefreshSeconds() * 1000L);
        // Идём по владельцам карт, а не по всем игрокам сервера: на 200 онлайн
        // без карт прежний цикл всё равно перебирал их каждые полсекунды.
        for (UUID id : playerMaps.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            MapView view = playerMaps.get(id);
            if (view == null || !isHoldingMapMainHand(player)) {
                continue;
            }
            if (view.getWorld() == null
                    || !ru.hahgps.util.LocationUtil.sameWorld(view.getWorld(), player.getWorld())) {
                // Карта, закреплённая на точке другого мира, должна там и
                // остаться: раньше её каждый тик пересоздавали на игрока, и
                // «показать трекер в Нижнем мире» не работало.
                if (isFollowing(player)) {
                    openMap(player);
                }
                continue;
            }
            boolean rendered = followStep(player, view, now);
            if (rendered) {
                continue;
            }
            long last = lastRefresh.getOrDefault(player.getUniqueId(), 0L);
            if (now - last >= refreshMs) {
                lastRefresh.put(player.getUniqueId(), now);
                renderAsync(player, view);
            }
        }
    }

    /** @return true, если карта уже перерисована в этом тике */
    private boolean followStep(Player player, MapView view, long now) {
        if (!isFollowing(player)) {
            return false;
        }
        int centerX = player.getLocation().getBlockX();
        int centerZ = player.getLocation().getBlockZ();
        int dx = centerX - view.getCenterX();
        int dz = centerZ - view.getCenterZ();
        if (Math.abs(dx) < FOLLOW_MIN_BLOCKS && Math.abs(dz) < FOLLOW_MIN_BLOCKS) {
            return false;
        }
        long last = lastFollowRender.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < FOLLOW_RENDER_INTERVAL_MS) {
            return false;
        }
        lastFollowRender.put(player.getUniqueId(), now);
        view.setCenterX(clampCenter(centerX));
        view.setCenterZ(clampCenter(centerZ));
        // Полотно не дёргаем вовсе: карта «стоит», а точную картинку принесёт
        // рендер нового центра. Сдвиг показа нужен только ручной панораме.
        scheduleRender(player, view, 1L);
        return true;
    }

    // ------------------------------------------------------------------
    // MapView и предмет карты
    // ------------------------------------------------------------------

    private MapView getOrCreateMapView(Player player, World world) {
        MapView view = playerMaps.get(player.getUniqueId());
        if (view != null && view.getWorld() != null
                && ru.hahgps.util.LocationUtil.sameWorld(view.getWorld(), world)) {
            return view;
        }
        // Карта уже лежит в инвентаре с прошлой сессии — берём её MapView,
        // а не создаём новую. Только свою: ванильную карту сокровищ или
        // чужую карту плагин трогать не должен.
        view = findMapViewInInventory(player, world);
        if (view == null) {
            view = Bukkit.createMap(world);
            view.setScale(parseZoom(plugin.getConfigManager().getMapZoom()));
            view.setUnlimitedTracking(false);
        }
        views.put(view.getId(), view);
        installRenderer(view);
        playerMaps.put(player.getUniqueId(), view);
        return view;
    }

    /** Один рендерер на MapView, а не по одному на каждое открытие карты. */
    private void installRenderer(MapView view) {
        renderers.computeIfAbsent(view.getId(), id -> {
            MapRenderer renderer = new ImageRenderer();
            view.addRenderer(renderer);
            return renderer;
        });
    }

    /** MapView из выданной ранее карты игрока (в том же мире), если она есть. */
    private MapView findMapViewInInventory(Player player, World world) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (!isGpsMap(item)) {
                continue;
            }
            if (!(item.getItemMeta() instanceof MapMeta meta) || !meta.hasMapId()) {
                continue;
            }
            MapView candidate = Bukkit.getMap(meta.getMapId());
            if (candidate == null || candidate.getWorld() == null) {
                continue;
            }
            if (ru.hahgps.util.LocationUtil.sameWorld(candidate.getWorld(), world)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Положить карту в главную руку: карта показывается на весь экран только
     * оттуда. Если карта лежит в инвентаре — переносим её в выбранный слот
     * (обменом, предмет не теряется), если её нет — выдаём новую.
     *
     * @return false, если места в инвентаре нет и карту выдать некуда
     */
    public boolean ensureMapInHand(Player player, MapView view) {
        PlayerInventory inventory = player.getInventory();
        int held = inventory.getHeldItemSlot();
        // Карта в левой руке тоже считается: меняем её с главной, чтобы клиент
        // развернул полотно на весь экран, и не выдаём второй предмет.
        ItemStack offHand = inventory.getItemInOffHand();
        if (matchesMap(offHand, view.getId())) {
            ItemStack refreshed = refreshMapItem(offHand);
            if (refreshed != null) {
                inventory.setItemInOffHand(refreshed);
                offHand = refreshed;
            }
            ItemStack main = inventory.getItem(held);
            inventory.setItemInOffHand(main);
            inventory.setItem(held, offHand);
            return true;
        }
        int slot = findMapSlot(player, view.getId());
        if (slot < 0) {
            ItemStack map = createMapItem(view);
            Map<Integer, ItemStack> leftover = inventory.addItem(map);
            if (!leftover.isEmpty()) {
                return false;
            }
            slot = findMapSlot(player, view.getId());
            if (slot < 0) {
                return false;
            }
        }
        if (slot < 9) {
            inventory.setHeldItemSlot(slot);
            return true;
        }
        ItemStack map = inventory.getItem(slot);
        ItemStack current = inventory.getItem(held);
        inventory.setItem(held, map);
        inventory.setItem(slot, current);
        inventory.setHeldItemSlot(held);
        return true;
    }

    private int findMapSlot(Player player, int mapId) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!matchesMap(item, mapId)) {
                continue;
            }
            ItemStack refreshed = refreshMapItem(item);
            if (refreshed != null) {
                inventory.setItem(slot, refreshed);
            }
            return slot;
        }
        return -1;
    }

    /** Предмет карты с подписью и меткой — его видно в инвентаре и не спутать. */
    private ItemStack createMapItem(MapView view) {
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        ItemMeta raw = map.getItemMeta();
        if (raw instanceof MapMeta meta) {
            meta.setMapView(view);
            decorateMapMeta(meta);
            map.setItemMeta(meta);
        }
        return GuiTheme.glow(map);
    }

    /**
     * Обновить подпись карты, выданной прошлой версией плагина: она осталась
     * в инвентаре игрока, и старый текст («Карта iTrak») не должен жить вечно.
     */
    private ItemStack refreshMapItem(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP) {
            return null;
        }
        ItemMeta raw = item.getItemMeta();
        if (!(raw instanceof MapMeta meta)) {
            return null;
        }
        Component current = meta.displayName();
        // Сравниваем только текст: цвет и градиент могли отличаться в прошлых версиях.
        String currentPlain = current == null ? ""
                : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(current).replaceAll("\\s+", " ").trim();
        String wantedPlain = "iTrak " + plugin.getMessages().get("map-item-name");
        if (currentPlain.equals(wantedPlain.replaceAll("\\s+", " ").trim())) {
            return null;
        }
        decorateMapMeta(meta);
        ItemStack updated = new ItemStack(Material.FILLED_MAP);
        updated.setItemMeta(meta);
        return GuiTheme.glow(updated);
    }

    private void decorateMapMeta(MapMeta meta) {
        meta.setCustomModelData(ru.hahgps.item.ItemIds.MAP_ITEM);
        meta.displayName(GuiTheme.mm("<bold>" + GuiTheme.gradient("iTrak")
                + "</bold> <#8fc6ff>" + GuiTheme.escapeForMini(
                plugin.getMessages().get("map-item-name")) + "</#8fc6ff>"));
        List<Component> lore = new ArrayList<>();
        lore.add(GuiTheme.mm("<gray>" + GuiTheme.escapeForMini(
                plugin.getMessages().get("map-item-lore")) + "</gray>"));
        lore.add(GuiTheme.mm("<#66aaff>→</#66aaff> <gray>" + GuiTheme.escapeForMini(
                plugin.getMessages().get("map-item-hint")) + "</gray>"));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(MAP_MARKER, PersistentDataType.BYTE, (byte) 1);
    }

    private class ImageRenderer extends MapRenderer {
        @Override
        public void render(MapView mapView, MapCanvas canvas, Player player) {
            int viewId = mapView.getId();
            BufferedImage image = shownImages.get(viewId);
            if (image == null) {
                image = images.get(viewId);
            }
            canvas.drawImage(0, 0, image != null ? image : Blank.IMAGE);
        }
    }

    // ------------------------------------------------------------------
    // Рендер
    // ------------------------------------------------------------------

    /**
     * Перерисовать полотно карты.
     *
     * Один рендер на MapView одновременно: пока он идёт, новые запросы только
     * ставят флаг «нужно ещё раз». Раньше каждый запрос поднимал новый токен и
     * убивал текущую задачу, поэтому при панораме (кадр раз в 300 мс, а
     * сэмплирование полотна дольше) рендер не доходил до конца никогда.
     */
    private void renderAsync(Player player, MapView view) {
        World world = view.getWorld();
        DataService dataService = plugin.getDataService();
        if (world == null || !plugin.isEnabled() || dataService == null) {
            return;
        }
        int viewId = view.getId();
        if (renderInFlight.putIfAbsent(viewId, Boolean.TRUE) != null) {
            renderDirty.put(viewId, Boolean.TRUE);
            return;
        }
        renderDirty.remove(viewId);

        ConfigManager config = plugin.getConfigManager();
        UUID id = player.getUniqueId();

        AtomicLong counter = renderTokens.computeIfAbsent(viewId, key -> new AtomicLong());
        long token = counter.incrementAndGet();
        // Отменяем только то, что действительно устарело: карта сменила
        // игрока/вид, мир выгрузился или плагин выключается.
        BooleanSupplier cancelled = () -> !plugin.isEnabled()
                || playerMaps.get(id) != view
                || view.getWorld() == null;

        int centerX = view.getCenterX();
        int centerZ = view.getCenterZ();
        MapView.Scale scale = view.getScale();
        Location playerLoc = player.getLocation().clone();
        boolean showPois = config.isMapShowPois();
        boolean showRoute = config.isMapShowRoute();
        boolean showPlayers = config.isMapShowPlayers();

        List<Poi> pois;
        if (showPois) {
            // Кэш DataService: точки меняются только командами, которые его обновляют.
            pois = new ArrayList<>();
            for (Poi poi : dataService.getCachedPois()) {
                Location loc = poi == null ? null : poi.getLocation();
                if (loc != null && loc.getWorld() != null && loc.getWorld().equals(world)) {
                    pois.add(poi);
                }
            }
        } else {
            pois = List.of();
        }

        List<Location> route = List.of();
        if (showRoute) {
            NavigationService navigation = plugin.getNavigationService();
            NavigationSession session = navigation == null ? null : navigation.getSession(player);
            if (session != null && session.getPath() != null) {
                route = session.getPath();
            }
        }

        List<Location> others = List.of();
        if (showPlayers) {
            List<Location> collected = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.getUniqueId().equals(id) && online.getWorld().equals(world)) {
                    collected.add(online.getLocation());
                }
            }
            others = collected;
        }

        // Свои трекеры на карте — как «Локатор» в телефоне: цвет маячка, а
        // офлайн (игрок вышел) рисуется контуром без заливки. Вне зоны связи
        // точной точки нет — такой маячок на карту не попадает.
        List<TerrainRenderer.Blip> trackers = List.of();
        if (config.isMapShowTrackers()) {
            TrackerService trackerService = plugin.getTrackerService();
            if (trackerService != null) {
                List<TerrainRenderer.Blip> collected = new ArrayList<>();
                for (Tracker tracker : trackerService.byOwner(id)) {
                    TrackerService.Signal signal = trackerService.signal(player, tracker);
                    Location location = signal.inRange() ? signal.exact() : null;
                    if (location == null || location.getWorld() == null
                            || !ru.hahgps.util.LocationUtil.sameWorld(location.getWorld(), world)) {
                        continue;
                    }
                    collected.add(new TerrainRenderer.Blip(location,
                            new java.awt.Color(tracker.getColor().getRgb()), !tracker.hasSignal()));
                }
                trackers = collected;
            }
        }

        List<Poi> finalPois = pois;
        List<Location> finalRoute = route;
        List<Location> finalOthers = others;
        List<TerrainRenderer.Blip> finalTrackers = trackers;

        // Слой кэша мира резолвится один раз на рендер, а не на каждый пиксель.
        WorldMapCache cache = config.isMapWorldCache() ? plugin.getWorldMapCache() : null;
        TerrainRenderer.CachedColors cached = cache == null ? null : cache.snapshot(world);
        terrainRenderer.render(world, centerX, centerZ, scale, finalPois, finalRoute, playerLoc,
                        finalOthers, finalTrackers, cancelled, cached)
                .whenComplete((image, error) -> finishRender(viewId, view, token, player, image,
                        centerX, centerZ));
    }

    /** Рендер закончился: показать картинку и, если просили ещё, запустить снова. */
    private void finishRender(int viewId, MapView view, long token, Player player,
                              BufferedImage image, int centerX, int centerZ) {
        renderInFlight.remove(viewId);
        if (image != null) {
            applyRenderedImage(view, token, player, image, centerX, centerZ);
        }
        if (renderDirty.remove(viewId) != null && plugin.isEnabled()) {
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && playerMaps.get(player.getUniqueId()) == view) {
                        renderAsync(player, view);
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private void applyRenderedImage(MapView view, long token, Player player, BufferedImage image,
                                    int centerX, int centerZ) {
        try {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!plugin.isEnabled() || !player.isOnline()) {
                    return;
                }
                AtomicLong current = renderTokens.get(view.getId());
                if (current == null || current.get() != token || playerMaps.get(player.getUniqueId()) != view) {
                    return;
                }
                int viewId = view.getId();
                images.put(viewId, image);
                renderedCenters.put(viewId, new int[]{centerX, centerZ});
                // Свежая картинка сразу становится и картинкой показа: сдвиг
                // панорамы после неё считается уже от нового центра.
                shownImages.remove(viewId);
                // Полотно обновляем только тому, кто действительно держит карту:
                // иначе на каждое обновление уходил пакет карты (16 КБ) впустую.
                if (isHoldingMapMainHand(player)) {
                    player.sendMap(view);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** Мир изменился: снять кэш поверхности, чтобы правка появилась на карте. */
    public void invalidateSurfaces(String worldName) {
        terrainRenderer.invalidateSurface(worldName);
    }

    public MapView getMapView(Player player) {
        return playerMaps.get(player.getUniqueId());
    }

    public void removePlayer(Player player) {
        UUID id = player.getUniqueId();
        playerMaps.remove(id);
        lastPanRender.remove(id);
        follow.remove(id);
        lastFollowRender.remove(id);
        lastRefresh.remove(id);
        panGestureStart.remove(id);
        BukkitTask deferred = pendingRenders.remove(id);
        if (deferred != null) {
            try {
                deferred.cancel();
            } catch (Throwable ignored) {
            }
        }
        // Картинку и рендерер не снимаем: карта остаётся в инвентаре игрока
        // и должна показывать то же полотно после повторного входа.
    }

    public void stop() {
        BukkitTask task = tickTask;
        tickTask = null;
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
            }
        }
        for (Map.Entry<Integer, MapRenderer> entry : renderers.entrySet()) {
            MapView view = views.get(entry.getKey());
            if (view != null && entry.getValue() != null) {
                try {
                    view.removeRenderer(entry.getValue());
                } catch (Throwable ignored) {
                }
            }
        }
        for (BukkitTask deferred : pendingRenders.values()) {
            try {
                deferred.cancel();
            } catch (Throwable ignored) {
            }
        }
        pendingRenders.clear();
        panGestureStart.clear();
        playerMaps.clear();
        views.clear();
        renderers.clear();
        images.clear();
        shownImages.clear();
        renderedCenters.clear();
        renderInFlight.clear();
        renderDirty.clear();
        renderTokens.clear();
        lastPanRender.clear();
        follow.clear();
        lastFollowRender.clear();
        lastRefresh.clear();
        terrainRenderer.shutdown();
    }

    private static MapView.Scale parseZoom(String name) {
        if (name == null) {
            return MapView.Scale.CLOSE;
        }
        try {
            return MapView.Scale.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MapView.Scale.CLOSE;
        }
    }
}
