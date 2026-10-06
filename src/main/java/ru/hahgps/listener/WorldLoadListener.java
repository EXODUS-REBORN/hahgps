package ru.hahgps.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import ru.hahgps.HahGpsPlugin;

/**
 * Пересборка графа дорог при загрузке мира.
 *
 * Маршруты хранят мировые координаты, и узлы мира, который ещё не поднялся,
 * отбрасываются. Раньше это происходило один раз при старте плагина (когда
 * миры обычно уже есть), но если мир создаётся позже — например, Multiverse
 * грузит его после — граф оставался без половины маршрутов до перезапуска.
 */
public class WorldLoadListener implements Listener {

    private final HahGpsPlugin plugin;

    public WorldLoadListener(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        var graphService = plugin.getGraphService();
        if (graphService == null || graphService.isLoading()) {
            return;
        }
        plugin.getLogger().info("HahGps: мир «" + event.getWorld().getName()
                + "» загружен — пересобираю граф дорог.");
        graphService.loadGraph().exceptionally(error -> {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "HahGps: не удалось пересобрать граф после загрузки мира", error);
            return null;
        });
    }
}
