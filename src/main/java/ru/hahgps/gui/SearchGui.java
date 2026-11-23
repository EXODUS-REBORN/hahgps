package ru.hahgps.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.item.ItemIds;
import ru.hahgps.service.SearchService;
import ru.hahgps.service.TrackerService;

import java.util.ArrayList;
import java.util.List;

/**
 * Поиск мест. Процент совпадения в списке не печатается: он живёт в
 * подсказке при наведении вместе с координатами, полным названием и числом
 * игроков, которые искали это место. Количество результатов настраивается
 * прямо здесь (1..25) и запоминается для игрока.
 */
public class SearchGui extends GuiScreen {

    private static final int LIMIT_SLOT = 49;

    private List<SearchService.SearchResult> results = new ArrayList<>();
    private String query = "";
    private boolean showingResults;

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
        if (!player.hasPermission("gps.search")) {
            player.sendMessage(HahGpsPlugin.getInstance().getMessages().getPrefixed("no-permission"));
            GuiTheme.deny(player);
            return;
        }
        super.open();

        if (initialResults != null) {
            showResults(initialQuery, initialResults);
        }
    }

    @Override
    public void build() {
        inventory.setItem(11, GuiUtil.item(Material.PAPER,
                comp("gui.search-input"),
                lore("gui.lore-search-start"), ru.hahgps.item.ItemIds.SEARCH_INPUT));

        inventory.setItem(15, GuiUtil.item(Material.PAPER,
                comp("gui.search-quick"),
                lore("gui.lore-search-name"), ru.hahgps.item.ItemIds.SEARCH_QUICK));

        putNavBack();
        inventory.setItem(LIMIT_SLOT, limitItem());
        putNavClose();

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
        if (slot == NAV_CLOSE_LARGE) {
            player.closeInventory();
            return;
        }

        // Лимит результатов — всегда на 49: в режиме результатов слот 24
        // занят 25-м местом, а не кнопкой лимита.
        if (slot == LIMIT_SLOT) {
            changeLimit(clickType);
            return;
        }

        if (showingResults) {
            if (slot == NAV_BACK_LARGE) {
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
                String foundQuery = query;
                List<SearchService.SearchResult> foundResults = results;
                new TransportSelectGui(player, transport ->
                        plugin.getCommandManager().gotoPoi(player, result.poi, transport),
                        () -> new SearchGui(player, foundQuery, foundResults)).open();
            }
            return;
        }

        if (slot == NAV_BACK_LARGE) {
            new MainMenuGui(player).open();
            GuiTheme.click(player);
            return;
        }
        if (slot == 11 || slot == 15) {
            player.closeInventory();
            InputManager.getInstance().request(player,
                    plugin.getMessages().getPrefixed("input-search"),
                    input -> runSearch(plugin, input));
        }
    }

    /** Меню количества результатов: 1..25, ЛКМ +1, ПКМ −1, Shift — по 5. */
    private void changeLimit(ClickType clickType) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        int delta = clickType.isShiftClick() ? 5 : 1;
        boolean down = clickType == ClickType.RIGHT || clickType == ClickType.SHIFT_RIGHT
                || clickType == ClickType.MIDDLE;
        int current = plugin.getSearchService().limitFor(player.getUniqueId());
        int next = plugin.getSearchService().setLimitFor(player.getUniqueId(),
                current + (down ? -delta : delta));
        GuiTheme.click(player);
        if (showingResults && !query.isBlank()) {
            runSearch(plugin, query);
            return;
        }
        refresh();
        player.sendMessage(plugin.getMessages().getPrefixed("gui.search-limit",
                "count", String.valueOf(next)));
    }

    private void runSearch(HahGpsPlugin plugin, String input) {
        plugin.getSearchService().search(input, player.getUniqueId()).whenComplete((found, error) -> {
            if (error != null) {
                reportError(error);
                return;
            }
            plugin.getDataService().runSync(() -> {
                if (player.isOnline()) {
                    new SearchGui(player, input, found).open();
                }
            });
        });
    }

    private org.bukkit.inventory.ItemStack limitItem() {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        int limit = plugin.getSearchService().limitFor(player.getUniqueId());
        return GuiUtil.item(Material.REPEATER,
                comp("gui.search-limit", "count", String.valueOf(limit)),
                lore("gui.lore-search-limit"), ItemIds.SEARCH_LIMIT);
    }

    private void showResults(String query, List<SearchService.SearchResult> found) {
        this.query = query == null ? "" : query.trim();
        this.results = found == null ? new ArrayList<>() : found;
        this.showingResults = true;
        inventory.clear();
        if (this.results.isEmpty()) {
            inventory.setItem(13, GuiUtil.item(Material.BARRIER,
                    comp("search-no-results", "query", this.query), null,
                    ru.hahgps.item.ItemIds.SEARCH_EMPTY));
        } else {
            int slot = 0;
            for (SearchService.SearchResult result : this.results) {
                if (slot >= 44) {
                    break;
                }
                inventory.setItem(slot++, resultItem(result));
            }
            inventory.setItem(48, GuiUtil.item(Material.PAPER,
                    comp("search-header", "count", String.valueOf(this.results.size())),
                    null, ru.hahgps.item.ItemIds.SEARCH_RESULT));
        }
        putNavBack();
        inventory.setItem(LIMIT_SLOT, limitItem());
        putNavClose();
        for (int i = 0; i < size(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, GuiUtil.filler());
            }
        }
        putBrand();
    }

    /** Место в поиске: при наведении — полное название, координаты, игроки и процент. */
    private org.bukkit.inventory.ItemStack resultItem(SearchService.SearchResult result) {
        List<Component> lines = new ArrayList<>();
        lines.add(comp("gui.search-result-type"));
        lines.addAll(lore("gui.lore-search-full-name", "full", result.name));
        if (result.poi != null) {
            lines.addAll(lore("gui.lore-search-coords",
                    "x", TrackerService.formatCoord(result.poi.getX()),
                    "y", TrackerService.formatCoord(result.poi.getY()),
                    "z", TrackerService.formatCoord(result.poi.getZ())));
        }
        lines.addAll(lore("gui.lore-search-searchers",
                "count", String.valueOf(result.searchers)));
        lines.addAll(lore("gui.lore-search-result",
                "percent", String.valueOf(result.percent())));
        if (result.poi != null) {
            lines.addAll(lore("gui.lore-search-action"));
        }
        return GuiUtil.item(Material.PAPER,
                GuiUtil.tonedName(9, result.name), lines, ru.hahgps.item.ItemIds.SEARCH_RESULT);
    }
}
