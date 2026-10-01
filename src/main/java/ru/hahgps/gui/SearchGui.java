package ru.hahgps.gui;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.service.SearchService;

import java.util.ArrayList;
import java.util.List;

public class SearchGui extends GuiScreen {

    private List<SearchService.SearchResult> results = new ArrayList<>();
    private String query = "";
    private boolean showingResults;

    /** Результаты, которые нужно показать сразу после открытия (поиск из чата). */
    private final String initialQuery;
    private final List<SearchService.SearchResult> initialResults;

    public SearchGui(Player player) {
        this(player, null, null);
    }

    public SearchGui(Player player, String query, List<SearchService.SearchResult> results) {
        super(player);
        this.initialQuery = query;
        this.initialResults = results;
    }

    @Override
    public String title() {
        return HahGpsPlugin.getInstance().getMessages().get("gui.search-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void open() {
        super.open();
        // После закрытия меню ради ввода в чат экран уже переоткрыт —
        // результаты просто отрисовываем поверх
        if (initialResults != null) {
            showResults(initialQuery, initialResults);
        }
    }

    @Override
    public void build() {
        inventory.setItem(11, GuiUtil.item(Material.PAPER,
                comp("gui.search-input"),
                lore("gui.lore-search-start")));

        inventory.setItem(15, GuiUtil.item(Material.COMPASS,
                comp("gui.search-quick"),
                lore("gui.lore-search-name")));

        inventory.setItem(22, GuiTheme.navBack(getMsg("gui.back")));
        inventory.setItem(52, GuiTheme.navClose(getMsg("gui.close")));

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
        if (slot == 52) {
            player.closeInventory();
            return;
        }

        // Режим результатов: слоты 0-43 — найденные точки, 53 — «назад».
        // Раньше результат в слоте 22 перехватывался кнопкой «назад» и
        // вместо перехода к точке очищал поиск.
        if (showingResults) {
            if (slot == 53) {
                results = new ArrayList<>();
                query = "";
                showingResults = false;
                refresh();
                GuiTheme.click(player);
                return;
            }
            if (slot >= 0 && slot < Math.min(44, results.size())) {
                SearchService.SearchResult result = results.get(slot);
                GuiTheme.click(player);
                player.closeInventory();
                if (result.poi == null) {
                    player.sendMessage(plugin.getMessages().getPrefixed(
                            "point-not-found", "name", result.name));
                    return;
                }
                new TransportSelectGui(player, transport ->
                        plugin.getCommandManager().gotoPoi(player, result.poi, transport)).open();
            }
            return;
        }

        // Обычный режим: 22 — «назад» в главное меню, 11/15 — ввод запроса
        if (slot == 22) {
            new MainMenuGui(player).open();
            GuiTheme.click(player);
            return;
        }
        if (slot == 11 || slot == 15) {
            player.closeInventory();
            InputManager.getInstance().request(player,
                    plugin.getMessages().getPrefixed("input-search"),
                    input -> plugin.getSearchService().search(input).whenComplete((found, error) -> {
                        if (error != null) {
                            reportError(error);
                            return;
                        }
                        plugin.getDataService().runSync(() -> {
                            if (player.isOnline()) {
                                // Открываем новый экран: старый закрыт чатом
                                new SearchGui(player, input, found).open();
                            }
                        });
                    }));
        }
    }

    private void showResults(String query, List<SearchService.SearchResult> found) {
        this.query = query == null ? "" : query.trim();
        this.results = found == null ? new ArrayList<>() : found;
        this.showingResults = true;
        inventory.clear();
        if (this.results.isEmpty()) {
            inventory.setItem(13, GuiUtil.item(Material.BARRIER,
                    comp("search-no-results", "query", this.query)));
        } else {
            int slot = 0;
            for (SearchService.SearchResult result : this.results) {
                if (slot >= 44) {
                    break;
                }
                java.util.List<net.kyori.adventure.text.Component> lines = new java.util.ArrayList<>();
                lines.add(comp("gui.search-result-type"));
                lines.addAll(lore("gui.lore-search-result",
                        "percent", String.valueOf((int) (result.similarity * 100))));
                inventory.setItem(slot++, GuiUtil.item(Material.COMPASS,
                        GuiUtil.tonedName(9, result.name),
                        lines));
            }
            inventory.setItem(48, GuiUtil.item(Material.PAPER,
                    comp("search-header", "count", String.valueOf(this.results.size()))));
        }
        inventory.setItem(53, GuiTheme.navBack(getMsg("gui.back")));
        inventory.setItem(52, GuiTheme.navClose(getMsg("gui.close")));
        for (int i = 0; i < size(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, GuiUtil.filler());
            }
        }
        putBrand();
    }
}
