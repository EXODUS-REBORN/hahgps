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

    private final Map<UUID, MapView> playerMaps = new ConcurrentHashMap<>();
    private final Map<UUID, BufferedImage> renderedImages = new ConcurrentHashMap<>();
    private final Map<UUID, MapRenderer> renderers = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> renderTokens = new ConcurrentHashMap<>();

    private static final BufferedImage BLANK_IMAGE = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);

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
        if (view != null && view.getWorld() != null && view.getWorld().equals(world)) {
            return view;
        }
        if (view != null) {
            MapRenderer old = renderers.get(player.getUniqueId());
            if (old != null) {
                view.removeRenderer(old);
            }
        }
        view = Bukkit.createMap(world);
        view.setScale(parseZoom(plugin.getConfigManager().getMapZoom()));
        view.setUnlimitedTracking(false);
        MapRenderer renderer = getRenderer(player);
        view.addRenderer(renderer);
        renderers.put(player.getUniqueId(), renderer);
        playerMaps.put(player.getUniqueId(), view);
        return view;
    }

    private MapRenderer getRenderer(Player player) {
        return new MapRenderer() {
            @Override
            public void render(MapView mapView, MapCanvas canvas, Player p) {
                BufferedImage image = renderedImages.get(player.getUniqueId());
                canvas.drawImage(0, 0, image != null ? image : BLANK_IMAGE);
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

        List<Poi> finalPois = pois;
        List<Location> finalRoute = route;
        List<Location> finalOthers = others;

        terrainRenderer.render(world, centerX, centerZ, scale, finalPois, finalRoute, playerLoc,
                        finalOthers, cancelled)
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
