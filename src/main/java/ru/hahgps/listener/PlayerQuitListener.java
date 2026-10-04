package ru.hahgps.listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.gui.GuiManager;
import ru.hahgps.gui.InputManager;

public class PlayerQuitListener implements Listener {

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {


        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (plugin == null) {
            return;
        }
        try {
            if (plugin.getNavigationService() != null) {
                plugin.getNavigationService().stopNavigation(event.getPlayer());
            }
            if (plugin.getMapService() != null) {
                plugin.getMapService().removePlayer(event.getPlayer());
            }
        } catch (Throwable ignored) {

        }
        GuiManager.getInstance().unregister(event.getPlayer());
        InputManager.getInstance().remove(event.getPlayer());
    }
}
