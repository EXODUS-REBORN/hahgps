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
import ru.hahgps.model.Poi;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Сервис карты: создание MapView, рендеринг, выдача карты игроку.
 */
public class MapService {

    private final HahGpsPlugin plugin;
    private final TerrainRenderer terrainRenderer = new TerrainRenderer();

    // Активные карты игроков
    private final Map<UUID, MapView> playerMaps = new HashMap<>();
    // Кэш отрендеренных изображений
    private final Map<UUID, BufferedImage> renderedImages = new HashMap<>();
    // Рендереры игроков
    private final Map<UUID, MapRenderer> renderers = new HashMap<>();

    public MapService(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Открыть карту для игрока.
     */
    public void openMap(Player player) {
        World world = player.getWorld();
        MapView view = getOrCreateMapView(player, world);
        view.setCenterX(player.getLocation().getBlockX());
        view.setCenterZ(player.getLocation().getBlockZ());

        // Первичная отрисовка (заглушка), затем асинхронный рендер
        player.sendMap(view);
        renderAsync(player, view);

        // Выдаём карту в руки
        giveMapItem(player, view);
    }

    /**
     * Центрировать карту на игроке и перерендерить.
     */
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

    /**
     * Изменить масштаб карты.
     */
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

    /**
     * Получить или создать MapView для игрока.
     */
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
        view.setScale(MapView.Scale.CLOSE);
        view.setUnlimitedTracking(false);
        MapRenderer renderer = getRenderer(player);
        view.addRenderer(renderer);
        renderers.put(player.getUniqueId(), renderer);
        playerMaps.put(player.getUniqueId(), view);
        return view;
    }

    /**
     * Рендерер, который рисует кэшированное изображение на канвас.
     */
    private MapRenderer getRenderer(Player player) {
        return new MapRenderer() {
            @Override
            public void render(MapView mapView, MapCanvas canvas, Player p) {
                BufferedImage image = renderedImages.get(player.getUniqueId());
                if (image != null) {
                    canvas.drawImage(0, 0, image);
                } else {
                    // Заглушка
                    for (int x = 0; x < 128; x++) {
                        for (int y = 0; y < 128; y++) {
                            canvas.setPixel(x, y, (byte) 0);
                        }
                    }
                }
            }
        };
    }

    /**
     * Асинхронный рендер террейна.
     */
    private void renderAsync(Player player, MapView view) {
        World world = view.getWorld();
        if (world == null) {
            return;
        }
        int centerX = view.getCenterX();
        int centerZ = view.getCenterZ();
        MapView.Scale scale = view.getScale();

        CompletableFuture<List<Poi>> poisFuture = plugin.getDataService().getPois();
        CompletableFuture<List<Location>> routeFuture = CompletableFuture.completedFuture(null);

        poisFuture.thenAccept(pois -> {
            List<Poi> filtered = pois.stream()
                    .filter(poi -> {
                        Location loc = poi.getLocation();
                        return loc != null && loc.getWorld() != null && loc.getWorld().equals(world);
                    })
                    .toList();

            terrainRenderer.render(world, centerX, centerZ, scale, filtered, null, player.getLocation())
                    .thenAccept(image -> {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            renderedImages.put(player.getUniqueId(), image);
                            player.sendMap(view);
                        });
                    });
        });
    }

    /**
     * Выдать игроку заполненную карту.
     */
    private void giveMapItem(Player player, MapView view) {
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        if (meta != null) {
            meta.setMapView(view);
            map.setItemMeta(meta);
        }
        // Кладём в свободный слот или выкидываем
        player.getInventory().addItem(map);
    }

    /**
     * Получить MapView игрока (если есть).
     */
    public MapView getMapView(Player player) {
        return playerMaps.get(player.getUniqueId());
    }

    /**
     * Очистка при выходе игрока.
     */
    public void removePlayer(Player player) {
        MapView view = playerMaps.remove(player.getUniqueId());
        if (view != null) {
            MapRenderer renderer = renderers.remove(player.getUniqueId());
            if (renderer != null) {
                view.removeRenderer(renderer);
            }
        }
        renderedImages.remove(player.getUniqueId());
    }
}
