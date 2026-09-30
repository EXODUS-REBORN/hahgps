package ru.hahgps.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.model.Category;
import ru.hahgps.model.Poi;
import ru.hahgps.util.LocationUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Список точек с пагинацией.
 */
public class PointsListGui extends GuiScreen {

    private static final int PER_PAGE = 45;

    private final Integer categoryId;
    private List<Poi> pois = new ArrayList<>();
    private int page = 0;

    public PointsListGui(Player player, Integer categoryId) {
        super(player);
        this.categoryId = categoryId;
    }

    @Override
    public String title() {
        return HahGpsPlugin.getInstance().getMessages().get("gui.points-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void build() {
        // Загружаем точки асинхронно
        HahGpsPlugin.getInstance().getPoiService().loadAllPoisWithRatings().thenAcceptAsync(list -> {
            this.pois = list;
            if (categoryId != null) {
                this.pois = list.stream().filter(p -> p.getCategoryId() == categoryId).toList();
            }
            HahGpsPlugin.getInstance().getDataService().runSync(() -> {
                if (player.isOnline()) {
                    buildPage();
                }
            });
        });
        // Пока данных нет — показываем заглушку
        if (inventory != null) {
            inventory.clear();
            for (int i = 0; i < size(); i++) {
                inventory.setItem(i, GuiUtil.filler());
            }
        }
    }

    private void buildPage() {
        inventory.clear();
        int pages = Math.max(1, (pois.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));

        int start = page * PER_PAGE;
        int end = Math.min(start + PER_PAGE, pois.size());

        for (int i = start; i < end; i++) {
            Poi poi = pois.get(i);
            inventory.setItem(i - start, createPoiItem(poi));
        }

        // Нижняя панель навигации
        int navRow = 45;
        if (page > 0) {
            inventory.setItem(navRow, GuiUtil.item(Material.ARROW,
                    HahGpsPlugin.getInstance().getMessages().getComponent("gui.prev-page")));
        }
        if (page < pages - 1) {
            inventory.setItem(navRow + 8, GuiUtil.item(Material.ARROW,
                    HahGpsPlugin.getInstance().getMessages().getComponent("gui.next-page")));
        }
        inventory.setItem(navRow + 4, GuiUtil.item(Material.BOOK,
                HahGpsPlugin.getInstance().getMessages().getComponent("gui.page-info",
                        "page", String.valueOf(page + 1), "pages", String.valueOf(pages))));

        // Заполнитель
        for (int i = 0; i < size(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, GuiUtil.filler());
            }
        }
    }

    private org.bukkit.inventory.ItemStack createPoiItem(Poi poi) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        Category category = plugin.getDataService().getCachedCategory(poi.getCategoryId());
        String categoryName = category != null ? category.getName() : "?";

        double distance = plugin.getPoiService().distanceTo(player, poi);
        String distanceStr = Double.isInfinite(distance) || distance == Double.MAX_VALUE
                ? "—" : LocationUtil.formatDistance(distance);

        String stars = "★".repeat((int) Math.round(poi.getRating()));

        List<Component> lore = List.of(
                plugin.getMessages().getComponent("gui.category", "category", categoryName),
                plugin.getMessages().getComponent("gui.rating-stars",
                        "stars", stars, "empty", "☆".repeat(10 - (int) Math.round(poi.getRating()))),
                plugin.getMessages().getComponent("gui.distance", "distance", distanceStr)
        );

        return GuiUtil.item(getIconForCategory(poi.getCategoryId()),
                GuiUtil.tonedName(9, poi.getName()), lore);
    }

    private Material getIconForCategory(int categoryId) {
        return switch (categoryId % 6) {
            case 0 -> Material.COMPASS;
            case 1 -> Material.BEACON;
            case 2 -> Material.BOOK;
            case 3 -> Material.ENDER_PEARL;
            case 4 -> Material.NETHER_STAR;
            default -> Material.PAPER;
        };
    }

    @Override
    public void onClick(int slot, ClickType clickType) {
        if (slot >= 45) {
            // Навигация
            if (slot == 45 && page > 0) {
                page--;
                buildPage();
            } else if (slot == 53 && page < Math.max(0, (pois.size() + PER_PAGE - 1) / PER_PAGE - 1)) {
                page++;
                buildPage();
            } else if (slot == 49) {
                new MainMenuGui(player).open();
            }
            return;
        }
        int index = page * PER_PAGE + slot;
        if (index < pois.size()) {
            new PoiActionsGui(player, pois.get(index)).open();
        }
    }
}
