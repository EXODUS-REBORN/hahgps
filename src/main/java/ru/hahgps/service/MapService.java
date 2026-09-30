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
public class MapService {
    private final HahGpsPlugin plugin;
    private final TerrainRenderer terrainRenderer = new TerrainRenderer();

    private final Map<UUID, MapView> playerMaps = new HashMap<>();

    private final Map<UUID, BufferedImage> renderedImages = new HashMap<>();

    private final Map<UUID, MapRenderer> renderers = new HashMap<>();

    public MapService(HahGpsPlugin plugin) {
        this.plugin = plugin;
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
                if (image != null) {
                    canvas.drawImage(0, 0, image);
                } else {

                    for (int x = 0; x < 128; x++) {
                        for (int y = 0; y < 128; y++) {
                            canvas.setPixel(x, y, (byte) 0);
                        }
                    }
                }
            }
        };
    }

    private void renderAsync(Player player, MapView view) {
        World world = view.getWorld();
        if (world == null) {
            return;
        }
        int centerX = view.getCenterX();
        int centerZ = view.getCenterZ();
        MapView.Scale scale = view.getScale();

        CompletableFuture<List<Poi>> poisFuture = plugin.getDataService().getPois();

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
                            if (!player.isOnline()) {
                                return;
                            }
                            renderedImages.put(player.getUniqueId(), image);
                            player.sendMap(view);
                        });
                    });
        });
    }

    private void giveMapItem(Player player, MapView view) {
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        if (meta != null) {
            meta.setMapView(view);
            map.setItemMeta(meta);
        }

        player.getInventory().addItem(map);
    }

    public MapView getMapView(Player player) {
        return playerMaps.get(player.getUniqueId());
    }

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
    }

    private static MapView.Scale parseZoom(String name) {
        try {
            return MapView.Scale.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MapView.Scale.CLOSE;
        }
    }
}
