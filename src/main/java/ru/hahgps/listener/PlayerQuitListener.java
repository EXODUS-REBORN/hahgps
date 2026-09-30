package ru.hahgps.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.gui.GuiManager;
import ru.hahgps.gui.InputManager;

/**
 * Очистка данных при выходе игрока.
 */
public class PlayerQuitListener implements Listener {

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        var plugin = HahGpsPlugin.getInstance();
        plugin.getNavigationService().stopNavigation(event.getPlayer());
        plugin.getMapService().removePlayer(event.getPlayer());
        GuiManager.getInstance().unregister(event.getPlayer());
        InputManager.getInstance().remove(event.getPlayer());
    }
}
