package ru.hahgps.gui;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.hahgps.HahGpsPlugin;

public class SettingsGui extends GuiScreen {

    private static final String[] ZOOMS = {"FARTHEST", "FAR", "NORMAL", "CLOSE", "CLOSEST"};

    public SettingsGui(Player player) {
        super(player);
    }

    @Override
    public String title() {
        return HahGpsPlugin.getInstance().getMessages().get("gui.settings-title");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build() {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        String lang = plugin.getConfigManager().getLanguage();
        boolean logging = plugin.getConfigManager().isLogHints();

        inventory.setItem(11, GuiUtil.item(Material.BOOK,
                comp("gui.language", "language", lang.toUpperCase(java.util.Locale.ROOT)),
                lore("gui.lore-language")));

        inventory.setItem(13, GuiUtil.item(Material.PAPER,
                comp("gui.logging", "state", logging ? getMsg("gui.state-on") : getMsg("gui.state-off")),
                lore("gui.lore-logging")));

        inventory.setItem(15, GuiUtil.item(Material.MAP,
                comp("gui.map-scale"),
                lore("gui.lore-toggle")));

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
            case 11 -> {
                String current = plugin.getConfigManager().getLanguage();
                String next = current.equals("ru") ? "en" : "ru";
                plugin.getConfigManager().get().set("language", next);
                persistConfig(plugin);
                plugin.getMessages().reload(next);
                player.sendMessage(plugin.getMessages().getPrefixed("reloaded"));
                refresh();
                GuiTheme.click(player);
            }
            case 13 -> {
                boolean logging = !plugin.getConfigManager().isLogHints();
                plugin.getConfigManager().get().set("navigation.log-hints", logging);
                persistConfig(plugin);
                refresh();
                GuiTheme.click(player);
            }
            case 15 -> {
                String current = plugin.getConfigManager().getMapZoom();
                int idx = 0;
                for (int i = 0; i < ZOOMS.length; i++) {
                    if (ZOOMS[i].equals(current)) {
                        idx = i;
                        break;
                    }
                }
                String next = ZOOMS[(idx + 1) % ZOOMS.length];
                plugin.getConfigManager().get().set("map.zoom", next);
                persistConfig(plugin);
                refresh();
                GuiTheme.click(player);
            }
            case 22 -> {
                new MainMenuGui(player).open();
                GuiTheme.click(player);
            }
        }
    }


    private void persistConfig(HahGpsPlugin plugin) {
        try {
            plugin.saveConfig();
            plugin.getConfigManager().reload();
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось сохранить config.yml: " + t.getMessage());
        }
    }
}
