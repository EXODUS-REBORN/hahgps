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

    /** Мир загрузился во время сборки графа: пересобрать сразу после неё. */
    private volatile boolean pendingRebuild;

    public WorldLoadListener(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        String worldName = event.getWorld().getName();
        if (plugin.getGraphService() == null) {
            return;
        }
        // Запрос не выбрасываем: если сборка уже идёт, её снимок мира не видит,
        // и без повторной сборки маршруты этого мира пропали бы до рестарта.
        if (plugin.getGraphService().isLoading()) {
            pendingRebuild = true;
            return;
        }
        rebuild(worldName);
    }

    private void rebuild(String worldName) {
        var graphService = plugin.getGraphService();
        var dataService = plugin.getDataService();
        if (graphService == null) {
            return;
        }
        plugin.getLogger().info("HahGps: мир «" + worldName
                + "» загружен — пересобираю граф дорог.");
        // Кэш маршрутов держит Location на старый инстанс мира: сбрасываем,
        // иначе граф собирался бы по мёртвым ссылкам.
        if (dataService != null) {
            dataService.invalidateRoutes();
        }
        graphService.loadGraph().whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "HahGps: не удалось пересобрать граф после загрузки мира", error);
            }
            if (pendingRebuild) {
                pendingRebuild = false;
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> rebuild("(догрузка)"));
            }
        });
    }
}
