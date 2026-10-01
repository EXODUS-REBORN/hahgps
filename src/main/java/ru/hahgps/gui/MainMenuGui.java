package ru.hahgps.gui;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.hahgps.HahGpsPlugin;

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
                comp("gui.my-points"),
                lore("gui.lore-my-points")));

        inventory.setItem(12, GuiUtil.item(Material.BOOK,
                comp("gui.categories"),
                lore("gui.lore-categories")));

        inventory.setItem(14, GuiUtil.item(Material.MAP,
                comp("gui.map"),
                lore("gui.lore-map")));

        inventory.setItem(16, GuiUtil.item(Material.PAPER,
                comp("gui.search"),
                lore("gui.lore-search")));

        inventory.setItem(22, GuiUtil.item(Material.REDSTONE,
                comp("gui.settings"),
                lore("gui.lore-settings")));

        for (int i = 0; i < size(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, GuiUtil.filler());
            }
        }
        putBrand();
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
}
