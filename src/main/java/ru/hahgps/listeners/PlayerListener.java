package ru.hahgps.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.hahgps.HahGPSPlugin;

public final class PlayerListener implements Listener {

    private final HahGPSPlugin plugin;

    public PlayerListener(HahGPSPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.navigation().forget(event.getPlayer().getUniqueId());
        plugin.visualization().forget(event.getPlayer());
    }
}
