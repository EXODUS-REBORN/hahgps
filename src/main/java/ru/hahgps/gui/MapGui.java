package ru.hahgps.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.model.Poi;
import ru.hahgps.util.LocationUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Карта: отображает террейн, точки и маршрут.
 * Клик по точке выбирает её как цель.
 */
public class MapGui extends GuiScreen {

    private final Map<Integer, Poi> slotToPoi = new HashMap<>();

    public MapGui(Player player) {
        super(player);
    }

    @Override
    public String title() {
        return HahGpsPlugin.getInstance().getMessages().get("gui.map-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build() {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        MapView view = plugin.getMapService().getMapView(player);

        // Центр — карта
        if (view != null) {
            ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) mapItem.getItemMeta();
            if (meta != null) {
                meta.setMapView(view);
                mapItem.setItemMeta(meta);
            }
            inventory.setItem(13, mapItem);
        } else {
            inventory.setItem(13, GuiUtil.item(Material.MAP,
                    Component.text("Карта не открыта", NamedTextColor.RED)));
        }

        // Зум
        inventory.setItem(20, GuiUtil.item(Material.GOLD_NUGGET,
                plugin.getMessages().getComponent("gui.zoom-in")));
        inventory.setItem(24, GuiUtil.item(Material.IRON_NUGGET,
                plugin.getMessages().getComponent("gui.zoom-out")));

        // Центрировать
        inventory.setItem(31, GuiUtil.item(Material.COMPASS,
                plugin.getMessages().getComponent("gui.recenter")));

        // Назад
        inventory.setItem(49, GuiUtil.item(Material.ARROW,
                plugin.getMessages().getComponent("gui.back")));

        // Точки вокруг карты
        slotToPoi.clear();
        plugin.getPoiService().loadAllPoisWithRatings().thenAcceptAsync(pois -> {
            plugin.getDataService().runSync(() -> {
                if (!player.isOnline()) {
                    return;
                }
                int[] slots = {1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 14, 15, 16, 17, 19, 21, 22, 23, 25, 26, 28, 29, 30, 32, 33, 34, 35, 37, 38, 39, 40, 41, 42, 43, 44};
                int idx = 0;
                for (Poi poi : pois) {
                    if (idx >= slots.length) {
                        break;
                    }
                    var loc = poi.getLocation();
                    if (loc == null || loc.getWorld() == null || !loc.getWorld().equals(player.getWorld())) {
                        continue;
                    }
                    double distance = LocationUtil.distance(player.getLocation(), loc);
                    inventory.setItem(slots[idx], GuiUtil.item(Material.COMPASS,
                            GuiUtil.tonedName(9, poi.getName()),
                            List.of(Component.text(LocationUtil.formatDistance(distance), NamedTextColor.GRAY))));
                    slotToPoi.put(slots[idx], poi);
                    idx++;
                }
            });
        });

        for (int i = 0; i < size(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, GuiUtil.filler());
            }
        }
    }

    @Override
    public void onClick(int slot, ClickType clickType) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        switch (slot) {
            case 20 -> {
                MapView view = plugin.getMapService().getMapView(player);
                if (view != null) {
                    MapView.Scale[] scales = MapView.Scale.values();
                    int idx = view.getScale().ordinal();
                    if (idx < scales.length - 1) {
                        plugin.getMapService().setZoom(player, scales[idx + 1]);
                    }
                }
            }
            case 24 -> {
                MapView view = plugin.getMapService().getMapView(player);
                if (view != null) {
                    MapView.Scale[] scales = MapView.Scale.values();
                    int idx = view.getScale().ordinal();
                    if (idx > 0) {
                        plugin.getMapService().setZoom(player, scales[idx - 1]);
                    }
                }
            }
            case 31 -> plugin.getMapService().recenter(player);
            case 49 -> new MainMenuGui(player).open();
            default -> {
                Poi poi = slotToPoi.get(slot);
                if (poi != null) {
                    player.closeInventory();
                    plugin.getPoiService().loadPoiWithRating(poi.getName()).thenAcceptAsync(loaded -> {
                        if (loaded == null) {
                            return;
                        }
                        plugin.getDataService().runSync(() -> {
                            if (player.isOnline()) {
                                new TransportSelectGui(player, transport ->
                                        plugin.getCommandManager().gotoPoi(player, loaded, transport)).open();
                            }
                        });
                    });
                }
            }
        }
    }
}
