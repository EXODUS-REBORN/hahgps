package ru.hahgps.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.map.MapView;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.item.ItemIds;
import ru.hahgps.service.MapService;

/**
 * Меню карты: открыть полотно, зум, центр на игроке и сдвиг по сторонам.
 *
 * Самого изображения карты здесь нет и быть не может: клиент Minecraft
 * рисует содержимое карты только когда она в руке или в рамке, в слоте меню
 * видна лишь иконка. Поэтому каждая кнопка применяет действие и показывает
 * карту в руке — там она двигается мышью.
 */
public class MapGui extends GuiScreen {

    /** Сдвиг кнопкой: четверть ширины полотна. */
    private static final double PAN_FRACTION = 0.25;

    public MapGui(Player player) {
        super(player);
    }

    @Override
    public String title() {
        return HahGpsPlugin.getInstance().getMessages().get("gui.map-title");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build() {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        MapService maps = plugin.getMapService();
        MapView.Scale scale = maps == null ? null : maps.scaleOf(player);
        String scaleText = scaleText(scale);

        inventory.setItem(11, GuiUtil.item(Material.MAP,
                comp("gui.zoom-in"),
                lore("gui.lore-map-zoom", "scale", scaleText), ItemIds.MAP_ITEM));

        inventory.setItem(13, GuiUtil.item(Material.COMPASS,
                comp("gui.recenter"),
                lore("gui.lore-map-mouse"), ItemIds.MAP_ITEM));

        inventory.setItem(15, GuiUtil.item(Material.PAPER,
                comp("gui.zoom-out"),
                lore("gui.lore-map-zoom", "scale", scaleText), ItemIds.MAP_ITEM));

        inventory.setItem(10, GuiUtil.item(Material.ARROW,
                comp("gui.map-north"), lore("gui.lore-map-pan"), ItemIds.MAP_ITEM));
        inventory.setItem(12, GuiUtil.item(Material.ARROW,
                comp("gui.map-south"), lore("gui.lore-map-pan"), ItemIds.MAP_ITEM));
        inventory.setItem(19, GuiUtil.item(Material.ARROW,
                comp("gui.map-west"), lore("gui.lore-map-pan"), ItemIds.MAP_ITEM));
        inventory.setItem(21, GuiUtil.item(Material.ARROW,
                comp("gui.map-east"), lore("gui.lore-map-pan"), ItemIds.MAP_ITEM));

        inventory.setItem(24, GuiUtil.item(Material.FILLED_MAP,
                comp("gui.map-open"), lore("gui.lore-map-open"), ItemIds.MAP_ITEM));

        inventory.setItem(22, GuiTheme.navBack(getMsg("gui.back")));
        inventory.setItem(26, GuiTheme.navClose(getMsg("gui.close")));

        for (int i = 0; i < size(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, GuiUtil.filler());
            }
        }
        putBrand();
    }

    @Override
    public void onClick(int slot, ClickType clickType) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        MapService maps = plugin.getMapService();
        if (maps == null) {
            return;
        }
        switch (slot) {
            case 26 -> player.closeInventory();
            case 22 -> {
                new MainMenuGui(player).open();
                GuiTheme.click(player);
            }
            case 11 -> applyZoom(plugin, maps, 1);
            case 15 -> applyZoom(plugin, maps, -1);
            case 13 -> {
                player.closeInventory();
                maps.openMap(player);
                player.sendMessage(plugin.getMessages().getPrefixed("map-centered"));
                GuiTheme.click(player);
            }
            case 10 -> panBy(maps, 0, -1);
            case 12 -> panBy(maps, 0, 1);
            case 19 -> panBy(maps, -1, 0);
            case 21 -> panBy(maps, 1, 0);
            case 24 -> {
                player.closeInventory();
                maps.showMap(player);
                player.sendMessage(plugin.getMessages().getPrefixed("map-open-hint"));
                GuiTheme.click(player);
            }
            default -> {
            }
        }
    }

    private void applyZoom(HahGpsPlugin plugin, MapService maps, int direction) {
        MapView.Scale scale = maps.zoom(player, direction);
        player.closeInventory();
        player.sendMessage(plugin.getMessages().getPrefixed("map-zoom", "scale", scaleText(scale)));
        GuiTheme.click(player);
    }

    private void panBy(MapService maps, int dx, int dz) {
        double step = MapService.MAP_SIZE * maps.blocksPerPixel(player) * PAN_FRACTION;
        player.closeInventory();
        maps.panStep(player, dx * step, dz * step);
        GuiTheme.click(player);
    }

    /** Подпись масштаба без названия мира. */
    private String scaleText(MapView.Scale scale) {
        if (scale == null) {
            return getMsg("map-scale-2");
        }
        String key = switch (scale) {
            case CLOSEST -> "map-scale-1";
            case CLOSE -> "map-scale-2";
            case NORMAL -> "map-scale-4";
            case FAR -> "map-scale-8";
            case FARTHEST -> "map-scale-16";
        };
        return getMsg(key);
    }
}
