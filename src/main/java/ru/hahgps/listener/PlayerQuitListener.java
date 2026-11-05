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
            // Незакрытая запись маршрута копит до 2000 Location (каждый держит
            // мир) до перезапуска: снимаем при выходе.
            if (plugin.getRouteRecordingService() != null) {
                plugin.getRouteRecordingService().forget(event.getPlayer());
            }
            if (plugin.getVisualizationService() != null) {
                plugin.getVisualizationService().disableAll(event.getPlayer());
            }
            if (plugin.getTrackerService() != null) {
                plugin.getTrackerService().forget(event.getPlayer());
                // Трекер, прицепленный к уходящему игроку, слетает предметом
                // там, где он вышел. UUID игрока постоянный: без этого трекер
                // «прилипал» к нему заново на следующем входе и локатор вечно
                // показывал его на игроке, даже когда тот уже ушёл.
                plugin.getTrackerService().onEntityGone(event.getPlayer());
            }
        } catch (Throwable ignored) {

        }
        GuiManager.getInstance().unregister(event.getPlayer());
        InputManager.getInstance().remove(event.getPlayer());
    }
}
