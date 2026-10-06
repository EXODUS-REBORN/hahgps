package ru.hahgps.gui;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.model.Category;

import java.util.List;

public class CategoriesGui extends GuiScreen {


    private static final int CONTENT_SLOTS = 44;

    private List<Category> categories = new java.util.ArrayList<>();

    public CategoriesGui(Player player) {
        super(player);
    }

    @Override
    public String title() {
        return HahGpsPlugin.getInstance().getMessages().get("gui.categories-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build() {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (inventory != null) {
            for (int i = 0; i < size(); i++) {
                inventory.setItem(i, GuiUtil.filler());
            }
        }
        plugin.getCategoryService().listCategories().whenComplete((cats, error) -> {
            if (error != null) {
                reportError(error);
                return;
            }
            this.categories = cats == null ? List.of() : cats;
            plugin.getDataService().runSync(() -> {
                if (!player.isOnline() || inventory == null
                        || GuiManager.getInstance().get(player) != this) {
                    return;
                }
                inventory.clear();
                java.util.Map<Integer, Integer> counts = countPoisByCategory(plugin);
                int slot = 0;
                for (int i = 0; i < categories.size() && slot < CONTENT_SLOTS; i++) {
                    Category category = categories.get(i);
                    int count = counts.getOrDefault(category.getId(), 0);
                    inventory.setItem(slot++, GuiUtil.item(Material.BOOK,
                            GuiUtil.tonedName(9, category.getName()),
                            lore("gui.lore-category-count", "count", String.valueOf(count))));
                }

                if (player.hasPermission("gps.category.create")) {
                    inventory.setItem(45, GuiUtil.item(Material.EMERALD,
                            comp("gui.create")));
                }
                if (player.hasPermission("gps.category.delete")) {
                    inventory.setItem(46, GuiUtil.item(Material.LAVA_BUCKET,
                            comp("gui.delete")));
                }
                inventory.setItem(53, GuiTheme.navBack(getMsg("gui.back")));
                inventory.setItem(52, GuiTheme.navClose(getMsg("gui.close")));

                for (int i = 0; i < size(); i++) {
                    if (inventory.getItem(i) == null) {
                        inventory.setItem(i, GuiUtil.filler());
                    }
                }
                putBrand();
            });
        });
    }

    @Override
    public void onClick(int slot, ClickType clickType) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (slot == 52) {
            player.closeInventory();
            return;
        }
        if (slot == 53) {
            new MainMenuGui(player).open();
            GuiTheme.click(player);
            return;
        }
        if (slot == 45 && player.hasPermission("gps.category.create")) {

            player.closeInventory();
            InputManager.getInstance().request(player,
                    plugin.getMessages().getPrefixed("input-category-create"),
                    input -> {
                        String name = input.trim();
                        if (name.isEmpty()) {
                            return;
                        }
                        plugin.getCategoryService().createCategory(name).whenComplete((cat, err) ->
                                plugin.getDataService().runSync(() -> {
                                    if (!player.isOnline()) return;
                                    if (err != null) {
                                        player.sendMessage(plugin.getMessages().getPrefixed(
                                                isDuplicate(err) ? "category-exists" : "error-occurred",
                                                "error", rootMessage(err)));
                                    } else {
                                        player.sendMessage(plugin.getMessages().getPrefixed("category-created",
                                                "name", name));
                                        new CategoriesGui(player).open();
                                    }
                                }));
                    });
            return;
        }
        if (slot == 46 && player.hasPermission("gps.category.delete")) {

            player.closeInventory();
            InputManager.getInstance().request(player,
                    plugin.getMessages().getPrefixed("input-category-delete"),
                    input -> {
                        String name = input.trim();
                        if (name.isEmpty()) {
                            return;
                        }
                        plugin.getCategoryService().delete(name).whenComplete((result, err) ->
                                plugin.getDataService().runSync(() -> {
                                    if (!player.isOnline()) return;
                                    if (err != null) {
                                        player.sendMessage(plugin.getMessages().getPrefixed("error-occurred",
                                                "error", rootMessage(err)));
                                        return;
                                    }
                                    switch (result == null
                                            ? ru.hahgps.service.CategoryService.DeleteResult.NOT_FOUND : result) {
                                        case DELETED -> {
                                            player.sendMessage(plugin.getMessages().getPrefixed(
                                                    "category-deleted", "name", name));
                                            new CategoriesGui(player).open();
                                        }
                                        case DEFAULT_CATEGORY -> player.sendMessage(plugin.getMessages()
                                                .getPrefixed("cannot-delete-default"));
                                        case NOT_EMPTY -> player.sendMessage(plugin.getMessages()
                                                .getPrefixed("category-not-empty", "name", name));
                                        case NOT_FOUND -> player.sendMessage(plugin.getMessages()
                                                .getPrefixed("category-not-found", "name", name));
                                    }
                                }));
                    });
            return;
        }


        if (slot >= 0 && slot < CONTENT_SLOTS && slot < categories.size()) {
            Category category = categories.get(slot);
            new PointsListGui(player, category.getId()).open();
            GuiTheme.click(player);
        }
    }


    /** Точки по категориям считаем по кэшу — без запроса в БД на каждую категорию. */
    private static java.util.Map<Integer, Integer> countPoisByCategory(HahGpsPlugin plugin) {
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (ru.hahgps.model.Poi poi : plugin.getDataService().getCachedPois()) {
            counts.merge(poi.getCategoryId(), 1, Integer::sum);
        }
        return counts;
    }


    private static boolean isDuplicate(Throwable err) {
        Throwable t = err;
        while (t != null) {
            String m = t.getMessage();
            if (m != null) {
                String low = m.toLowerCase(java.util.Locale.ROOT);
                if (low.contains("unique") || low.contains("duplicate")) return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
