package ru.hahgps.gui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.hahgps.HahGpsPlugin;

import java.util.List;

public class MainMenuGui extends GuiScreen {

    public MainMenuGui(Player player) {
        super(player);
    }

    @Override
    public String title() {
        return HahGpsPlugin.getInstance().getMessages().get("gui.main-menu");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build() {

        inventory.setItem(10, GuiUtil.item(Material.COMPASS,
                msg("gui.my-points"),
                List.of(Component.text("Просмотр и управление точками", NamedTextColor.AQUA))));

        inventory.setItem(12, GuiUtil.item(Material.BOOK,
                msg("gui.categories"),
                List.of(Component.text("Управление категориями", NamedTextColor.AQUA))));

        inventory.setItem(14, GuiUtil.item(Material.MAP,
                msg("gui.map"),
                List.of(Component.text("Открыть карту местности", NamedTextColor.AQUA))));

        inventory.setItem(16, GuiUtil.item(Material.PAPER,
                msg("gui.search"),
                List.of(Component.text("Поиск точек", NamedTextColor.AQUA))));

        inventory.setItem(22, GuiUtil.item(Material.REDSTONE,
                msg("gui.settings"),
                List.of(Component.text("Настройки плагина", NamedTextColor.AQUA))));

        for (int i = 0; i < size(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, GuiUtil.filler());
            }
        }
    }

    @Override
    public void onClick(int slot, ClickType clickType) {
        switch (slot) {
            case 10 -> new PointsListGui(player, null).open();
            case 12 -> new CategoriesGui(player).open();
            case 14 -> {
                player.closeInventory();
                HahGpsPlugin.getInstance().getMapService().openMap(player);
            }
            case 16 -> new SearchGui(player).open();
            case 22 -> new SettingsGui(player).open();
        }
    }

    private Component msg(String key) {
        return HahGpsPlugin.getInstance().getMessages().getComponent(key);
    }
}
