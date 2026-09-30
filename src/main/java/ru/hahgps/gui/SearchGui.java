package ru.hahgps.gui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.service.SearchService;

import java.util.List;

public class SearchGui extends GuiScreen {

    private List<SearchService.SearchResult> results = new java.util.ArrayList<>();

    public SearchGui(Player player) {
        super(player);
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
    public void build() {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();

        inventory.setItem(11, GuiUtil.item(Material.PAPER,
                GuiUtil.tonedName(9, "Введите запрос в чат"),
                List.of(Component.text("Нажмите, чтобы начать поиск", NamedTextColor.AQUA))));

        inventory.setItem(15, GuiUtil.item(Material.COMPASS,
                GuiUtil.tonedName(9, "Быстрый поиск"),
                List.of(Component.text("По названию точки", NamedTextColor.AQUA))));

        inventory.setItem(22, GuiUtil.item(Material.ARROW,
                plugin.getMessages().getComponent("gui.back")));

        for (int i = 0; i < size(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, GuiUtil.filler());
            }
        }
    }

    @Override
    public void onClick(int slot, ClickType clickType) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (slot == 22) {
            new MainMenuGui(player).open();
            return;
        }
        if (slot == 11 || slot == 15) {

            player.closeInventory();
            InputManager.getInstance().request(player,
                    plugin.getMessages().getPrefixed("gui.search-hint"),
                    input -> {
                        plugin.getSearchService().search(input).thenAcceptAsync(found -> {
                            plugin.getDataService().runSync(() -> {
                                if (player.isOnline()) {
                                    showResults(found);
                                }
                            });
                        });
                    });
        }
    }

    private void showResults(List<SearchService.SearchResult> found) {
        this.results = found;
        inventory.clear();
        if (found.isEmpty()) {
            inventory.setItem(13, GuiUtil.item(Material.BARRIER,
                    HahGpsPlugin.getInstance().getMessages().getComponent("search-no-results",
                            "query", "")));
        } else {
            int slot = 0;
            for (SearchService.SearchResult result : found) {
                if (slot >= 45) {
                    break;
                }
                inventory.setItem(slot++, GuiUtil.item(Material.COMPASS,
                        GuiUtil.tonedName(9, result.name),
                        List.of(
                                Component.text("Точка", NamedTextColor.AQUA),
                                Component.text("Совпадение: " + (int) (result.similarity * 100) + "%", NamedTextColor.AQUA))));
            }
        }
        inventory.setItem(53, GuiUtil.item(Material.ARROW,
                HahGpsPlugin.getInstance().getMessages().getComponent("gui.back")));
        for (int i = 0; i < size(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, GuiUtil.filler());
            }
        }
    }
}
