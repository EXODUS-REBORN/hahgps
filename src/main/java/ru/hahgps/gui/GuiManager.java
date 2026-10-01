package ru.hahgps.gui;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GuiManager {

    // Инициализация при загрузке класса: ленивый double-check здесь гонялся
    // между главным потоком и асинхронными колбэками
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
        // Снимаем с регистрации ДО закрытия: InventoryCloseEvent вызовет
        // unregister, и итерация по живой карте дала бы ConcurrentModificationException
        List<GuiScreen> screens = new ArrayList<>(openGuis.values());
        openGuis.clear();
        for (GuiScreen gui : screens) {
            try {
                Player p = gui.getPlayer();
                if (p != null && p.isOnline()) {
                    p.closeInventory();
                }
            } catch (Throwable ignored) {
                // игрок мог отключиться в этот момент
            }
        }
    }
}
