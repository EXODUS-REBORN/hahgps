package ru.hahgps.gui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.model.Category;
import ru.hahgps.model.Poi;

import java.util.List;

public class PoiActionsGui extends GuiScreen {

    private final Poi poi;

    public PoiActionsGui(Player player, Poi poi) {
        super(player);
        this.poi = poi;
    }

    @Override
    public String title() {
        return HahGpsPlugin.getInstance().getMessages().get("gui.poi-actions-title", "name", poi.getName());
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build() {
        inventory.setItem(10, GuiUtil.item(Material.PAPER,
                HahGpsPlugin.getInstance().getMessages().getComponent("gui.info"),
                List.of(Component.text("Подробная информация", NamedTextColor.AQUA))));

        inventory.setItem(12, GuiUtil.item(Material.COMPASS,
                HahGpsPlugin.getInstance().getMessages().getComponent("gui.goto"),
                List.of(Component.text("Построить маршрут", NamedTextColor.AQUA))));

        inventory.setItem(14, GuiUtil.item(Material.NETHER_STAR,
                HahGpsPlugin.getInstance().getMessages().getComponent("gui.rate"),
                List.of(Component.text("Оценить (1-10)", NamedTextColor.AQUA))));

        inventory.setItem(16, GuiUtil.item(Material.LAVA_BUCKET,
                HahGpsPlugin.getInstance().getMessages().getComponent("gui.delete"),
                List.of(Component.text("Удалить точку", NamedTextColor.AQUA))));

        inventory.setItem(22, GuiUtil.item(Material.ARROW,
                HahGpsPlugin.getInstance().getMessages().getComponent("gui.back")));

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
            case 10 -> {

                plugin.getPoiService().loadPoiWithRating(poi.getName()).thenAcceptAsync(loaded -> {
                    if (loaded == null) {
                        return;
                    }
                    plugin.getDataService().runSync(() -> {
                        if (player.isOnline()) {
                            sendPoiInfo(loaded);
                        }
                    });
                });
            }
            case 12 -> {

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
            case 14 -> {

                player.closeInventory();
                ru.hahgps.gui.InputManager.getInstance().request(player,
                        plugin.getMessages().getPrefixed("gui.rate"),
                        input -> {
                            try {
                                int rating = Integer.parseInt(input.trim());
                                if (rating < 1 || rating > 10) {
                                    player.sendMessage(plugin.getMessages().getPrefixed("invalid-rating"));
                                    return;
                                }
                                plugin.getPoiService().ratePoi(poi.getName(), player.getUniqueId(), rating)
                                        .thenRun(() -> player.sendMessage(
                                                plugin.getMessages().getPrefixed("point-rated",
                                                        "name", poi.getName(), "rating", String.valueOf(rating))));
                            } catch (NumberFormatException e) {
                                player.sendMessage(plugin.getMessages().getPrefixed("invalid-rating"));
                            }
                        });
            }
            case 16 -> {

                player.closeInventory();
                plugin.getPoiService().deletePoi(poi.getName()).thenRun(() -> {
                    plugin.getDataService().runSync(() -> {
                        if (player.isOnline()) {
                            player.sendMessage(plugin.getMessages().getPrefixed("point-deleted",
                                    "name", poi.getName()));
                        }
                    });
                });
            }
            case 22 -> new PointsListGui(player, null).open();
        }
    }

    private void sendPoiInfo(Poi poi) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        Category category = plugin.getDataService().getCachedCategory(poi.getCategoryId());
        String categoryName = category != null ? category.getName() : "?";

        String raw = plugin.getMessages().get("point-info",
                "name", poi.getName(),
                "category", categoryName,
                "x", String.format("%.1f", poi.getX()),
                "y", String.format("%.1f", poi.getY()),
                "z", String.format("%.1f", poi.getZ()),
                "world", poi.getWorld(),
                "rating", String.format("%.1f", poi.getRating()),
                "votes", String.valueOf(poi.getRatingCount()),
                "author", poi.getAuthor(),
                "date", poi.getFormattedDate(),
                "description", poi.getDescription());

        for (String line : raw.split("\n")) {
            player.sendMessage(plugin.getMessages().getComponentRaw(line));
        }
    }
}
