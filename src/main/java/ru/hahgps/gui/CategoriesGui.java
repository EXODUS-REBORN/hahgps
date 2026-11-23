package ru.hahgps.gui;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.item.ItemIds;
import ru.hahgps.model.Category;

import java.util.List;

public class CategoriesGui extends GuiScreen {


    private static final int CONTENT_SLOTS = 44;
    private static final int CREATE_SLOT = 46;
    private static final int DELETE_SLOT = 47;

    private List<Category> categories = new java.util.ArrayList<>();
    private int page;

    public CategoriesGui(Player player) {
        this(player, 0);
    }

    public CategoriesGui(Player player, int page) {
        super(player);
        this.page = Math.max(0, page);
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
                // Даже при сбое загрузки экран не должен оставлять игрока без
                // выхода: ставим кнопки закрытия/назад в главный поток.
                plugin.getDataService().runSync(() -> {
                    if (inventory == null || GuiManager.getInstance().get(player) != this) {
                        return;
                    }
                    inventory.setItem(NAV_CLOSE_LARGE, GuiTheme.navClose(getMsg("gui.close")));
                    inventory.setItem(NAV_BACK_LARGE, GuiTheme.navBack(getMsg("gui.back")));
                });
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
                int pages = Math.max(1, (categories.size() + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
                page = Math.max(0, Math.min(page, pages - 1));
                int from = page * CONTENT_SLOTS;
                int to = Math.min(from + CONTENT_SLOTS, categories.size());
                for (int i = from; i < to; i++) {
                    Category category = categories.get(i);
                    int count = counts.getOrDefault(category.getId(), 0);
                    inventory.setItem(i - from, GuiUtil.item(Material.BOOK,
                            GuiUtil.tonedName(9, category.getName()),
                            lore("gui.lore-category-count", "count", String.valueOf(count)),
                            ItemIds.CATEGORY_ENTRY));
                }
                putPager(page, pages);

                if (player.hasPermission("gps.category.create")) {
                    inventory.setItem(CREATE_SLOT, GuiUtil.item(Material.EMERALD,
                            comp("gui.create"), null, ItemIds.CATEGORY_CREATE));
                }
                if (player.hasPermission("gps.category.delete")) {
                    inventory.setItem(DELETE_SLOT, GuiUtil.item(Material.LAVA_BUCKET,
                            comp("gui.delete"), null, ItemIds.CATEGORY_DELETE));
                }
                putNavBack();
                putNavClose();

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
        if (slot == NAV_CLOSE_LARGE) {
            player.closeInventory();
            return;
        }
        if (slot == NAV_BACK_LARGE) {
            new MainMenuGui(player).open();
            GuiTheme.click(player);
            return;
        }
        if (slot == NAV_PREV && page > 0) {
            page--;
            new CategoriesGui(player, page).open();
            GuiTheme.page(player);
            return;
        }
        int pages = Math.max(1, (categories.size() + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
        if (slot == NAV_NEXT && page < pages - 1) {
            page++;
            new CategoriesGui(player, page).open();
            GuiTheme.page(player);
            return;
        }
        if (slot == CREATE_SLOT && player.hasPermission("gps.category.create")) {

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
        if (slot == DELETE_SLOT && player.hasPermission("gps.category.delete")) {

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


        int index = page * CONTENT_SLOTS + slot;
        if (slot >= 0 && slot < CONTENT_SLOTS && index < categories.size()) {
            Category category = categories.get(index);
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
