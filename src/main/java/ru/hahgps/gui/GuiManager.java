package ru.hahgps.gui;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Менеджер открытых GUI.
 */
public class GuiManager {

    private static GuiManager instance;

    private final Map<UUID, GuiScreen> openGuis = new HashMap<>();

    public static GuiManager getInstance() {
        if (instance == null) {
            instance = new GuiManager();
        }
        return instance;
    }

    public void register(Player player, GuiScreen gui) {
        openGuis.put(player.getUniqueId(), gui);
    }

    public void unregister(Player player) {
        openGuis.remove(player.getUniqueId());
    }

    public GuiScreen get(Player player) {
        return openGuis.get(player.getUniqueId());
    }

    public boolean hasGui(Player player) {
        return openGuis.containsKey(player.getUniqueId());
    }

    /**
     * Закрыть все GUI (при выключении плагина).
     */
    public void closeAll() {
        for (GuiScreen gui : openGuis.values()) {
            gui.getPlayer().closeInventory();
        }
        openGuis.clear();
    }
}
