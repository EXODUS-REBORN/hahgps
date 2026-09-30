package ru.hahgps.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.model.Category;

import java.util.List;

/**
 * Список категорий.
 */
public class CategoriesGui extends GuiScreen {

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
        plugin.getCategoryService().listCategories().thenCompose(cats -> {
            this.categories = cats;
            // Асинхронно загружаем количество точек в каждой категории
            List<java.util.concurrent.CompletableFuture<Integer>> countFutures = cats.stream()
                    .map(c -> plugin.getDataService().getPoisByCategory(c.getId())
                            .thenApply(List::size))
                    .toList();
            return java.util.concurrent.CompletableFuture.allOf(countFutures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .thenApply(v -> countFutures.stream().map(java.util.concurrent.CompletableFuture::join).toList());
        }).thenAcceptAsync(counts -> {
            plugin.getDataService().runSync(() -> {
                if (!player.isOnline()) {
                    return;
                }
                inventory.clear();
                int slot = 0;
                for (int i = 0; i < categories.size() && slot < 45; i++) {
                    Category category = categories.get(i);
                    int count = counts.get(i);
                    inventory.setItem(slot++, GuiUtil.item(Material.BOOK,
                            GuiUtil.tonedName(9, category.getName()),
                            List.of(Component.text("Точек: " + count, NamedTextColor.GRAY))));
                }

                // Кнопки
                if (player.hasPermission("gps.category.create")) {
                    inventory.setItem(45, GuiUtil.item(Material.EMERALD,
                            plugin.getMessages().getComponent("gui.create")));
                }
                if (player.hasPermission("gps.category.delete")) {
                    inventory.setItem(46, GuiUtil.item(Material.LAVA_BUCKET,
                            plugin.getMessages().getComponent("gui.delete")));
                }
                inventory.setItem(53, GuiUtil.item(Material.ARROW,
                        plugin.getMessages().getComponent("gui.back")));

                for (int i = 0; i < size(); i++) {
                    if (inventory.getItem(i) == null) {
                        inventory.setItem(i, GuiUtil.filler());
                    }
                }
            });
        });
    }

    @Override
    public void onClick(int slot, ClickType clickType) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (slot == 53) {
            new MainMenuGui(player).open();
            return;
        }
        if (slot == 45 && player.hasPermission("gps.category.create")) {
            // Создать категорию — ввод через чат
            player.closeInventory();
            InputManager.getInstance().request(player,
                    plugin.getMessages().getPrefixed("gui.create"),
                    input -> {
                        String name = input.trim();
                        if (name.isEmpty()) {
                            return;
                        }
                        plugin.getCategoryService().createCategory(name).thenRun(() ->
                                plugin.getDataService().runSync(() -> {
                                    if (player.isOnline()) {
                                        player.sendMessage(plugin.getMessages().getPrefixed("category-created",
                                                "name", name));
                                    }
                                }));
                    });
            return;
        }
        if (slot == 46 && player.hasPermission("gps.category.delete")) {
            // Удалить категорию — ввод через чат
            player.closeInventory();
            InputManager.getInstance().request(player,
                    plugin.getMessages().getPrefixed("gui.delete"),
                    input -> {
                        String name = input.trim();
                        if (name.isEmpty()) {
                            return;
                        }
                        plugin.getCategoryService().deleteCategory(name).thenRun(() ->
                                plugin.getDataService().runSync(() -> {
                                    if (player.isOnline()) {
                                        player.sendMessage(plugin.getMessages().getPrefixed("category-deleted",
                                                "name", name));
                                    }
                                }));
                    });
            return;
        }
        // Клик по категории
        if (slot < categories.size()) {
            Category category = categories.get(slot);
            new PointsListGui(player, category.getId()).open();
        }
    }
}
