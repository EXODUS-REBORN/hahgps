package ru.hahgps.service;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.config.ConfigManager;
import ru.hahgps.model.NavigationSession;
import ru.hahgps.model.Poi;
import ru.hahgps.model.Tracker;
import ru.hahgps.storage.DataService;
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
 * Карта игрока: одна MapView на игрока, отрисовка — порциями через TerrainRenderer.
 *
 * Список точек берётся из кэша DataService (раньше каждый рендер дёргал БД),
 * а каждый новый рендер отменяет предыдущий — иначе спам по клавише карты
 * запускал параллельные задачи загрузки чанков.
 */
public class MapService {
    private final HahGpsPlugin plugin;
    private final TerrainRenderer terrainRenderer;

    /** Ширина полотна карты в пикселях (ванильная карта 128×128). */
    public static final int MAP_SIZE = 128;

    /** Не чаще этой паузы полотно карты перерисовывается при движении мышью. */
    private static final long PAN_RENDER_INTERVAL_MS = 300L;

    /** Порядок масштабов: от самого крупного к самому мелкому. */
    private static final MapView.Scale[] SCALES = {
            MapView.Scale.CLOSEST, MapView.Scale.CLOSE, MapView.Scale.NORMAL,
            MapView.Scale.FAR, MapView.Scale.FARTHEST};

    private final Map<UUID, MapView> playerMaps = new ConcurrentHashMap<>();
    private final Map<UUID, BufferedImage> renderedImages = new ConcurrentHashMap<>();
    private final Map<UUID, MapRenderer> renderers = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> renderTokens = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPanRender = new ConcurrentHashMap<>();
    /** Накопленный сдвиг, ещё не перенесённый на картинку: {dx, dz} в блоках. */
    private final Map<UUID, int[]> pendingPan = new ConcurrentHashMap<>();

    /**
     * Пустая картинка для карты до первого рендера.
     *
     * Лежит в отдельном holder-классе намеренно: {@code new BufferedImage}
     * тянет за собой инициализацию AWT ({@code ColorSpace}, {@code Raster}),
     * а это десятки миллисекунд на нормальной машине и секунды на загруженной.
     * В статическом поле самого {@code MapService} эта инициализация попадала
     * в {@code onStorageReady} — то есть в главный поток при старте плагина,
     * и Paper писал «The server has not responded for 10 seconds».
     * Теперь AWT поднимается лениво при первом открытии карты.
     */
    private static final class Blank {
        private static final BufferedImage IMAGE =
                new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
    }

    public MapService(HahGpsPlugin plugin) {
        this.plugin = plugin;
        this.terrainRenderer = new TerrainRenderer(plugin);
    }

    public void openMap(Player player) {
        World world = player.getWorld();
        MapView view = getOrCreateMapView(player, world);
        view.setCenterX(player.getLocation().getBlockX());
        view.setCenterZ(player.getLocation().getBlockZ());

        player.sendMap(view);
        renderAsync(player, view);
        giveMapItem(player, view);
    }

    /** Показать уже существующую карту, не сбрасывая её центр на игроке. */
    public void showMap(Player player) {
        MapView view = playerMaps.get(player.getUniqueId());
        if (view == null || view.getWorld() == null
                || !ru.hahgps.util.LocationUtil.sameWorld(view.getWorld(), player.getWorld())) {
            openMap(player);
            return;
        }
        giveMapItem(player, view);
        player.sendMap(view);
        renderAsync(player, view);
    }

    /**
     * Навести карту на точку (трекер) и показать её в руке: так работает
     * «find» — карта как в «Локаторе», только по своим маячкам.
     */
    public boolean focus(Player player, Location target) {
        if (player == null || target == null || target.getWorld() == null) {
            return false;
        }
        MapView view = getOrCreateMapView(player, target.getWorld());
        view.setCenterX(clampCenter(target.getBlockX()));
        view.setCenterZ(clampCenter(target.getBlockZ()));
        player.sendMap(view);
        renderAsync(player, view);
        giveMapItem(player, view);
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
        if (!(item.getItemMeta() instanceof MapMeta meta) || !meta.hasMapId()) {
            return false;
        }
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
     * Сдвиг полотна карты (панорама мышью): центр карты едет за курсором,
     * картинка сразу сдвигается, а точная перерисовка приходит из фонового
     * рендера. Не чаще {@link #PAN_RENDER_INTERVAL_MS}, иначе на каждое
     * движение мыши уходил бы пакет карты на 16 КБ.
     */
    public void pan(Player player, double dx, double dz) {
        if (player == null || !plugin.isEnabled()) {
            return;
        }
        MapView view = playerMaps.get(player.getUniqueId());
        if (view == null) {
            return;
        }
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
        int[] pending = pendingPan.computeIfAbsent(player.getUniqueId(), k -> new int[2]);
        pending[0] += stepX;
        pending[1] += stepZ;
        if (!render) {
            return;
        }
        int dxTotal = pending[0];
        int dzTotal = pending[1];
        pending[0] = 0;
        pending[1] = 0;
        shiftImage(player, view, dxTotal, dzTotal);
        player.sendMap(view);
        renderAsync(player, view);
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

    /** Сдвинуть уже нарисованную картинку — панорама отзывается мгновенно. */
    private void shiftImage(Player player, MapView view, int blockDx, int blockDz) {
        BufferedImage source = renderedImages.get(player.getUniqueId());
        if (source == null) {
            return;
        }
        double bpp = TerrainRenderer.blocksPerPixel(view.getScale());
        int px = (int) Math.round(blockDx / bpp);
        int pz = (int) Math.round(blockDz / bpp);
        if (px == 0 && pz == 0) {
            return;
        }
        BufferedImage shifted = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = shifted.createGraphics();
        graphics.setColor(new java.awt.Color(0x202020));
        graphics.fillRect(0, 0, MAP_SIZE, MAP_SIZE);
        graphics.drawImage(source, -px, -pz, null);
        graphics.dispose();
        renderedImages.put(player.getUniqueId(), shifted);
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
        giveMapItem(player, view);
        return view.getScale();
    }

    private static int clampCenter(int value) {
        return Math.max(-29_999_984, Math.min(29_999_984, value));
    }

    public void recenter(Player player) {
        MapView view = playerMaps.get(player.getUniqueId());
        if (view == null) {
            openMap(player);
            return;
        }
        view.setCenterX(player.getLocation().getBlockX());
        view.setCenterZ(player.getLocation().getBlockZ());
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

    private MapView getOrCreateMapView(Player player, World world) {
        MapView view = playerMaps.get(player.getUniqueId());
        if (view != null && view.getWorld() != null
                && ru.hahgps.util.LocationUtil.sameWorld(view.getWorld(), world)) {
            return view;
        }
        if (view != null) {
            MapRenderer old = renderers.get(player.getUniqueId());
            if (old != null) {
                view.removeRenderer(old);
            }
        }
        // Карта уже лежит в инвентаре с прошлой сессии — берём её MapView,
        // а не создаём новую. Раньше каждый вход + открытие карты выдавали
        // новый filled_map, и предметы копились в инвентаре стопкой.
        view = findMapViewInInventory(player, world);
        if (view == null) {
            view = Bukkit.createMap(world);
            view.setScale(parseZoom(plugin.getConfigManager().getMapZoom()));
            view.setUnlimitedTracking(false);
        }
        MapRenderer renderer = getRenderer(player);
        view.addRenderer(renderer);
        renderers.put(player.getUniqueId(), renderer);
        playerMaps.put(player.getUniqueId(), view);
        return view;
    }

    /** MapView из выданной ранее карты игрока (в том же мире), если она есть. */
    private MapView findMapViewInInventory(Player player, World world) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType() != Material.FILLED_MAP || !item.hasItemMeta()) {
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

    private MapRenderer getRenderer(Player player) {
        return new MapRenderer() {
            @Override
            public void render(MapView mapView, MapCanvas canvas, Player p) {
                BufferedImage image = renderedImages.get(player.getUniqueId());
                canvas.drawImage(0, 0, image != null ? image : Blank.IMAGE);
            }
        };
    }

    private void renderAsync(Player player, MapView view) {
        World world = view.getWorld();
        DataService dataService = plugin.getDataService();
        if (world == null || !plugin.isEnabled() || dataService == null) {
            return;
        }
        ConfigManager config = plugin.getConfigManager();
        UUID id = player.getUniqueId();

        AtomicLong counter = renderTokens.computeIfAbsent(id, k -> new AtomicLong());
        long token = counter.incrementAndGet();
        // Устаревший рендер видит это и прекращает грузить чанки.
        BooleanSupplier cancelled = () -> {
            AtomicLong current = renderTokens.get(id);
            return current == null || current.get() != token || playerMaps.get(id) != view;
        };

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
        // офлайн (игрок вышел) рисуется контуром без заливки.
        List<TerrainRenderer.Blip> trackers = List.of();
        if (config.isMapShowTrackers()) {
            TrackerService trackerService = plugin.getTrackerService();
            if (trackerService != null) {
                List<TerrainRenderer.Blip> collected = new ArrayList<>();
                for (Tracker tracker : trackerService.byOwner(id)) {
                    Location location = tracker.liveLocation();
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

        WorldMapCache cache = config.isMapWorldCache() ? plugin.getWorldMapCache() : null;
        TerrainRenderer.CachedColors cached = cache == null ? null
                : (cachedWorld, x, z) -> cache.colorAt(cachedWorld, x, z);
        terrainRenderer.render(world, centerX, centerZ, scale, finalPois, finalRoute, playerLoc,
                        finalOthers, finalTrackers, cancelled, cached)
                .thenAccept(image -> {
                    if (image != null) {
                        applyRenderedImage(id, token, view, player, image);
                    }
                });
    }

    private void applyRenderedImage(UUID id, long token, MapView view, Player player, BufferedImage image) {
        try {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!plugin.isEnabled() || !player.isOnline()) {
                    return;
                }
                AtomicLong current = renderTokens.get(id);
                if (current == null || current.get() != token || playerMaps.get(id) != view) {
                    return;
                }
                renderedImages.put(id, image);
                player.sendMap(view);
            });
        } catch (Throwable ignored) {
        }
    }

    private void giveMapItem(Player player, MapView view) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType() != Material.FILLED_MAP || !item.hasItemMeta()) {
                continue;
            }
            if (item.getItemMeta() instanceof MapMeta meta
                    && meta.hasMapId() && meta.getMapId() == view.getId()) {
                return;
            }
        }

        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        if (meta != null) {
            meta.setMapView(view);
            // Кастомный ID модели — как и у остальных предметов плагина.
            meta.setCustomModelData(ru.hahgps.item.ItemIds.MAP_ITEM);
            map.setItemMeta(meta);
        }

        // addItem возвращает то, что не влезло, и это молча терялось.
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(map);
        if (!leftover.isEmpty()) {
            player.sendMessage(plugin.getMessages().getPrefixed("map-inventory-full"));
        }
    }

    public MapView getMapView(Player player) {
        return playerMaps.get(player.getUniqueId());
    }

    public void removePlayer(Player player) {
        UUID id = player.getUniqueId();
        MapView view = playerMaps.remove(id);
        MapRenderer renderer = renderers.remove(id);
        if (view != null && renderer != null) {
            view.removeRenderer(renderer);
        }
        renderedImages.remove(id);
        renderTokens.remove(id);
        lastPanRender.remove(id);
        pendingPan.remove(id);
    }

    public void stop() {
        for (Map.Entry<UUID, MapView> entry : playerMaps.entrySet()) {
            MapRenderer renderer = renderers.get(entry.getKey());
            if (renderer != null) {
                entry.getValue().removeRenderer(renderer);
            }
        }
        playerMaps.clear();
        renderers.clear();
        renderedImages.clear();
        renderTokens.clear();
        lastPanRender.clear();
        pendingPan.clear();
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
