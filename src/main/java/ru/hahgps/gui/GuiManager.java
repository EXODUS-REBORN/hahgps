package ru.hahgps.gui;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GuiManager {



    private static final GuiManager INSTANCE = new GuiManager();

    private final Map<UUID, GuiScreen> openGuis = new ConcurrentHashMap<>();

    public static GuiManager getInstance() {
        return INSTANCE;
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

    public void closeAll() {


        List<GuiScreen> screens = new ArrayList<>(openGuis.values());
        openGuis.clear();
        for (GuiScreen gui : screens) {
            try {
                Player p = gui.getPlayer();
                if (p != null && p.isOnline()) {
                    p.closeInventory();
                }
            } catch (Throwable ignored) {

            }
        }
    }
}
