package ru.hahgps.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.service.RouteRecordingService;

/**
 * Сэмплирование пути при записи маршрута.
 *
 * Слушатель ничего не делает, если игрок не пишет маршрут: проверка — одно
 * обращение к карте по UUID.
 */
public class RouteRecordListener implements Listener {

    private final HahGpsPlugin plugin;

    public RouteRecordListener(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        RouteRecordingService recording = plugin.getRouteRecordingService();
        if (recording == null || !recording.isRecording(event.getPlayer())) {
            return;
        }
        recording.add(event.getPlayer());
    }
}
