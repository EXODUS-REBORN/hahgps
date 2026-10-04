package ru.hahgps.gui;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.model.Category;
import ru.hahgps.model.Poi;
import ru.hahgps.util.LocationUtil;

import java.util.ArrayList;
import java.util.List;

public class PointsListGui extends GuiScreen {


    private static final int PER_PAGE = 44;

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

        HahGpsPlugin.getInstance().getPoiService().loadAllPoisWithRatings().whenComplete((list, error) -> {
            if (error != null) {
                reportError(error);
                return;
            }
            List<Poi> loaded = list == null ? new ArrayList<>() : list;
            this.pois = categoryId == null
                    ? loaded
                    : loaded.stream().filter(p -> p.getCategoryId() == categoryId).toList();
            HahGpsPlugin.getInstance().getDataService().runSync(() -> {
                if (player.isOnline() && inventory != null
                        && GuiManager.getInstance().get(player) == this) {
                    buildPage();
                }
            });
        });

        if (inventory != null) {
            inventory.clear();
            for (int i = 0; i < size(); i++) {
                inventory.setItem(i, GuiUtil.filler());
            }
        }
    }

    private void buildPage() {
        if (inventory == null) return;
        inventory.clear();
        int pages = Math.max(1, (pois.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));

        int start = page * PER_PAGE;
        int end = Math.min(start + PER_PAGE, pois.size());

        for (int i = start; i < end; i++) {
            Poi poi = pois.get(i);
            inventory.setItem(i - start, createPoiItem(poi));
        }

        int navRow = 45;
        if (page > 0) {
            inventory.setItem(navRow, GuiTheme.navBack(getMsg("gui.prev-page")));
        }
        if (page < pages - 1) {
            inventory.setItem(navRow + 8, GuiTheme.navForward(getMsg("gui.next-page")));
        }
        inventory.setItem(navRow + 4, GuiUtil.item(Material.BOOK,
                comp("gui.page-info",
                        "page", String.valueOf(page + 1), "pages", String.valueOf(pages))));
        inventory.setItem(52, GuiTheme.navClose(getMsg("gui.close")));

        for (int i = 0; i < size(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, GuiUtil.filler());
            }
        }
        putBrand();
    }

    private org.bukkit.inventory.ItemStack createPoiItem(Poi poi) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        Category category = plugin.getDataService().getCachedCategory(poi.getCategoryId());
        String categoryName = category != null ? category.getName() : "?";

        double distance = plugin.getPoiService().distanceTo(player, poi);
        String distanceStr = Double.isInfinite(distance) || distance == Double.MAX_VALUE
                ? "—" : LocationUtil.formatDistance(distance);

        int rating = (int) Math.round(poi.getRating());
        rating = Math.max(0, Math.min(10, rating));
        String stars = "★".repeat(rating);

        List<Component> lore = List.of(
                plugin.getMessages().getComponent("gui.category", "category", categoryName),
                plugin.getMessages().getComponent("gui.rating-stars",
                        "stars", stars, "empty", "☆".repeat(10 - rating)),
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
            int pages = Math.max(1, (pois.size() + PER_PAGE - 1) / PER_PAGE);
            if (slot == 45 && page > 0) {
                page--;
                buildPage();
                GuiTheme.page(player);
            } else if (slot == 53 && page < pages - 1) {
                page++;
                buildPage();
                GuiTheme.page(player);
            } else if (slot == 49) {
                new MainMenuGui(player).open();
            } else if (slot == 52) {
                player.closeInventory();
            }
            return;
        }


        int start = page * PER_PAGE;
        int shown = Math.max(0, Math.min(PER_PAGE, pois.size() - start));
        if (slot >= 0 && slot < shown) {
            new PoiActionsGui(player, pois.get(start + slot)).open();
            GuiTheme.click(player);
        }
    }
}
