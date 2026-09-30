package ru.hahgps.gui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.hahgps.HahGpsPlugin;

import java.util.List;

public class SettingsGui extends GuiScreen {

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
                plugin.getMessages().getComponent("gui.language", "language", lang.toUpperCase()),
                List.of(Component.text("Нажмите, чтобы переключить RU/EN", NamedTextColor.AQUA))));

        inventory.setItem(13, GuiUtil.item(Material.PAPER,
                plugin.getMessages().getComponent("gui.logging", "state", logging ? "Вкл" : "Выкл"),
                List.of(Component.text("Подсказки в чате при навигации", NamedTextColor.AQUA))));

        inventory.setItem(15, GuiUtil.item(Material.MAP,
                GuiUtil.tonedName(9, "Масштаб карты"),
                List.of(Component.text("Нажмите, чтобы изменить", NamedTextColor.AQUA))));

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
        switch (slot) {
            case 11 -> {

                String current = plugin.getConfigManager().getLanguage();
                String next = current.equals("ru") ? "en" : "ru";
                plugin.getConfigManager().get().set("language", next);
                plugin.getConfigManager().reload();
                plugin.getMessages().reload(next);
                player.sendMessage(plugin.getMessages().getPrefixed("reloaded"));
                refresh();
            }
            case 13 -> {

                boolean logging = !plugin.getConfigManager().isLogHints();
                plugin.getConfigManager().get().set("navigation.log-hints", logging);
                plugin.getConfigManager().reload();
                refresh();
            }
            case 15 -> {

                String[] zooms = {"FARTHEST", "FAR", "NORMAL", "CLOSE", "CLOSEST"};
                String current = plugin.getConfigManager().getMapZoom();
                int idx = 0;
                for (int i = 0; i < zooms.length; i++) {
                    if (zooms[i].equals(current)) {
                        idx = i;
                        break;
                    }
                }
                String next = zooms[(idx + 1) % zooms.length];
                plugin.getConfigManager().get().set("map.zoom", next);
                plugin.getConfigManager().reload();
                refresh();
            }
            case 22 -> new MainMenuGui(player).open();
        }
    }
}
