package ru.hahgps.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.item.ItemFactory;
import ru.hahgps.item.ItemIds;
import ru.hahgps.model.TrackerColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Каталог iTrak: все трекеры (8 цветов, обычные и водонепроницаемые) и оба
 * локатора в одном меню. Клик выдаёт готовый предмет с новым уникальным кодом.
 *
 * Это витрина, а не склад: предмет-трекер в слоте — только образец, настоящий
 * код выдаётся в момент клика, поэтому коды не расходуются от открытия меню.
 */
public class ItrakCatalogGui extends GuiScreen {

    private static final int REGULAR_FIRST = 10;
    private static final int WATERPROOF_FIRST = 19;

    public ItrakCatalogGui(Player player) {
        super(player);
    }

    @Override
    public String title() {
        return HahGpsPlugin.getInstance().getMessages().get("gui.itrak-catalog-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build() {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        GuiTheme.fill(inventory);

        inventory.setItem(4, GuiUtil.item(Material.BOOK,
                comp("gui.itrak-catalog-info"),
                lore("gui.lore-itrak-catalog-info"), ItemIds.ITRAK_MENU_ENTRY));

        TrackerColor[] colors = TrackerColor.values();
        for (int i = 0; i < colors.length; i++) {
            inventory.setItem(REGULAR_FIRST + i, display(colors[i], false));
            inventory.setItem(WATERPROOF_FIRST + i, display(colors[i], true));
        }

        int radius = (int) plugin.getConfigManager().getItrakScanRadius();
        int longRadius = plugin.getConfigManager().getItrakLocatorLongRadius();
        inventory.setItem(30, locatorDisplay(radius));
        inventory.setItem(32, locatorDisplay(longRadius));

        inventory.setItem(45, GuiTheme.navBack(getMsg("gui.back")));
        inventory.setItem(53, GuiTheme.navClose(getMsg("gui.close")));
        putBrand();
    }

    /** Образец трекера: модель и цвет как у настоящего, но без выдачи кода. */
    private ItemStack display(TrackerColor color, boolean waterproof) {
        ItemStack item = ItemFactory.trackerItem(new ItemFactory.TrackerSpec(
                "TRK-0000000", "", color, waterproof));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String type = waterproof
                    ? getMsg("gui.itrak-catalog-waterproof")
                    : getMsg("gui.itrak-catalog-regular");
            meta.displayName(GuiTheme.mm("<bold><color:" + color.hex() + ">iTrak </color></bold>"
                    + "<color:" + color.hex() + ">" + GuiTheme.escapeForMini(getMsg(color.messageKey()))
                    + "</color> <dark_gray>·</dark_gray> <#8fc6ff>"
                    + GuiTheme.escapeForMini(type) + "</#8fc6ff>"));
            List<Component> lines = new ArrayList<>();
            lines.add(comp("gui.lore-itrak-catalog-entry"));
            meta.lore(lines);
            meta.setCustomModelData(ItemIds.tracker(color, waterproof));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack locatorDisplay(int radius) {
        ItemStack item = ItemFactory.locatorItem(radius);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(comp("gui.itrak-catalog-locator", "radius", String.valueOf(radius)));
            List<Component> lines = new ArrayList<>();
            lines.add(comp("gui.lore-itrak-catalog-locator"));
            lines.add(comp("gui.lore-itrak-catalog-entry"));
            meta.lore(lines);
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public void onClick(int slot, ClickType clickType) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (slot == 45) {
            new MainMenuGui(player).open();
            GuiTheme.click(player);
            return;
        }
        if (slot == 53) {
            player.closeInventory();
            return;
        }
        if (!player.hasPermission("gps.itrak.give")) {
            player.sendMessage(plugin.getMessages().getPrefixed("no-permission"));
            GuiTheme.deny(player);
            return;
        }
        if (!plugin.getConfigManager().isItrakEnabled()) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-disabled"));
            GuiTheme.deny(player);
            return;
        }

        TrackerColor[] colors = TrackerColor.values();
        if (slot >= REGULAR_FIRST && slot < REGULAR_FIRST + colors.length) {
            giveTracker(colors[slot - REGULAR_FIRST], false);
            return;
        }
        if (slot >= WATERPROOF_FIRST && slot < WATERPROOF_FIRST + colors.length) {
            giveTracker(colors[slot - WATERPROOF_FIRST], true);
            return;
        }
        if (slot == 30) {
            giveLocator((int) plugin.getConfigManager().getItrakScanRadius());
            return;
        }
        if (slot == 32) {
            giveLocator(plugin.getConfigManager().getItrakLocatorLongRadius());
            return;
        }
    }

    private void giveTracker(TrackerColor color, boolean waterproof) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        String code = ItemFactory.newCode();
        ItemFactory.give(player, ItemFactory.trackerItem(new ItemFactory.TrackerSpec(
                code, "", color, waterproof)));
        player.sendMessage(plugin.getMessages().getPrefixed("itrak-received",
                "code", code,
                "color", getMsg(color.messageKey())));
        GuiTheme.click(player);
    }

    private void giveLocator(int radius) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        ItemFactory.give(player, ItemFactory.locatorItem(radius));
        player.sendMessage(plugin.getMessages().getPrefixed("itrak-locator-received",
                "radius", String.valueOf(radius)));
        GuiTheme.click(player);
    }
}
