package ru.hahgps.gui;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.model.Category;
import ru.hahgps.model.Poi;

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
                comp("gui.info"),
                lore("gui.lore-info")));

        inventory.setItem(12, GuiUtil.item(Material.COMPASS,
                comp("gui.goto"),
                lore("gui.lore-goto")));

        inventory.setItem(14, GuiUtil.item(Material.NETHER_STAR,
                comp("gui.rate"),
                lore("gui.lore-rate")));


        if (player.hasPermission("gps.point.delete")) {
            inventory.setItem(16, GuiUtil.item(Material.LAVA_BUCKET,
                    comp("gui.delete"),
                    lore("gui.lore-delete")));
        }

        inventory.setItem(22, GuiTheme.navBack(getMsg("gui.back")));

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
        switch (slot) {
            case 10 -> {
                GuiTheme.click(player);
                plugin.getPoiService().loadPoiWithRating(poi.getName()).whenComplete((loaded, error) -> {
                    if (error != null) {
                        reportError(error);
                        return;
                    }
                    if (loaded == null) {
                        player.sendMessage(plugin.getMessages().getPrefixed("point-not-found",
                                "name", poi.getName()));
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
                GuiTheme.click(player);
                player.closeInventory();
                plugin.getPoiService().loadPoiWithRating(poi.getName()).whenComplete((loaded, error) -> {
                    if (error != null) {
                        reportError(error);
                        return;
                    }
                    if (loaded == null) {
                        player.sendMessage(plugin.getMessages().getPrefixed("point-not-found",
                                "name", poi.getName()));
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
                if (!player.hasPermission("gps.point.rate")) {
                    player.sendMessage(plugin.getMessages().getPrefixed("no-permission"));
                    GuiTheme.deny(player);
                    return;
                }
                GuiTheme.click(player);
                player.closeInventory();
                InputManager.getInstance().request(player,
                        plugin.getMessages().getPrefixed("input-rate"),
                        input -> {
                            try {
                                int rating = Integer.parseInt(input.trim());
                                if (rating < 1 || rating > 10) {
                                    player.sendMessage(plugin.getMessages().getPrefixed("invalid-rating"));
                                    return;
                                }
                                plugin.getPoiService().ratePoi(poi.getName(), player.getUniqueId(), rating)
                                        .whenComplete((v, error) -> plugin.getDataService().runSync(() -> {
                                            if (!player.isOnline()) return;
                                            if (error != null) {
                                                reportError(error);
                                            } else {
                                                player.sendMessage(plugin.getMessages().getPrefixed("point-rated",
                                                        "name", poi.getName(), "rating", String.valueOf(rating)));
                                            }
                                        }));
                            } catch (NumberFormatException e) {
                                player.sendMessage(plugin.getMessages().getPrefixed("invalid-rating"));
                            }
                        });
            }
            case 16 -> {
                if (!player.hasPermission("gps.point.delete")) {
                    player.sendMessage(plugin.getMessages().getPrefixed("no-permission"));
                    GuiTheme.deny(player);
                    return;
                }
                GuiTheme.click(player);
                player.closeInventory();
                plugin.getPoiService().deletePoi(poi.getName()).whenComplete((v, error) ->
                        plugin.getDataService().runSync(() -> {
                            if (!player.isOnline()) return;
                            if (error != null) {
                                reportError(error);
                                return;
                            }
                            player.sendMessage(plugin.getMessages().getPrefixed("point-deleted",
                                    "name", poi.getName()));
                            new PointsListGui(player, null).open();
                        }));
            }
            case 22 -> {
                new PointsListGui(player, null).open();
                GuiTheme.click(player);
            }
        }
    }

    private void sendPoiInfo(Poi poi) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        Category category = plugin.getDataService().getCachedCategory(poi.getCategoryId());
        String categoryName = category != null ? category.getName() : "?";

        String raw = plugin.getMessages().get("point-info",
                "name", poi.getName(),
                "category", categoryName,
                "x", String.format(java.util.Locale.ROOT, "%.1f", poi.getX()),
                "y", String.format(java.util.Locale.ROOT, "%.1f", poi.getY()),
                "z", String.format(java.util.Locale.ROOT, "%.1f", poi.getZ()),
                "world", poi.getWorld(),
                "rating", String.format(java.util.Locale.ROOT, "%.1f", poi.getRating()),
                "votes", String.valueOf(poi.getRatingCount()),
                "author", poi.getAuthor(),
                "date", poi.getFormattedDate(),
                "description", poi.getDescription());

        for (String line : raw.split("\n")) {
            player.sendMessage(plugin.getMessages().getComponentRaw(line));
        }
    }
}
