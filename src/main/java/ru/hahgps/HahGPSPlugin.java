package ru.hahgps;

import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.hahgps.commands.GpsCommand;
import ru.hahgps.commands.NavCommand;
import ru.hahgps.config.Messages;
import ru.hahgps.config.PluginConfig;
import ru.hahgps.hud.HudService;
import ru.hahgps.hud.ItemsAdderHook;
import ru.hahgps.listeners.PlayerListener;
import ru.hahgps.model.Graph;
import ru.hahgps.nav.NavigationManager;
import ru.hahgps.routing.AStarPathfinder;
import ru.hahgps.routing.RouteBuilder;
import ru.hahgps.storage.GraphStorage;
import ru.hahgps.visual.VisualizationService;

public final class HahGPSPlugin extends JavaPlugin {

    private PluginConfig config;
    private Messages messages;
    private Graph graph;
    private GraphStorage storage;
    private NavigationManager navigation;
    private VisualizationService visualization;
    private BukkitTask autosaveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        messages = new Messages(this);
        config = new PluginConfig(this);

        graph = new Graph();
        storage = new GraphStorage(this, graph);
        graph.setDirtyListener(storage::markDirty);
        storage.load();

        AStarPathfinder pathfinder = new AStarPathfinder(config);
        RouteBuilder routeBuilder = new RouteBuilder(graph, pathfinder, config, messages);
        HudService hud = new HudService(config, messages);
        navigation = new NavigationManager(this, config, messages, graph, routeBuilder, hud);
        visualization = new VisualizationService(this, config, messages, graph);

        bindCommand("gps", new GpsCommand(this));
        bindCommand("nav", new NavCommand(this));
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        navigation.start();
        visualization.start();

        long period = Math.max(20L, config.autosaveSeconds() * 20L);
        autosaveTask = getServer().getScheduler().runTaskTimer(this, storage::saveIfDirty, period, period);

        getLogger().info("HahGPS включён. Узлов: " + graph.nodeCount() + ", рёбер: " + graph.edgeCount()
                + ", ItemsAdder: " + (ItemsAdderHook.available() ? "найден" : "нет"));
    }

    @Override
    public void onDisable() {
        if (navigation != null) {
            navigation.shutdown();
        }
        if (visualization != null) {
            visualization.shutdown();
        }
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
        if (storage != null) {
            storage.save();
        }
        getServer().getScheduler().cancelTasks(this);
        HandlerList.unregisterAll(this);
        ItemsAdderHook.reset();

        navigation = null;
        visualization = null;
        storage = null;
        graph = null;
        messages = null;
        config = null;
    }

    public void reloadEverything() {
        config.reload();
        messages.reload();
        storage.load();
        navigation.start();
        visualization.start();
    }

    private void bindCommand(String name, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Команда /" + name + " не объявлена в plugin.yml");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    public PluginConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public Graph graph() {
        return graph;
    }

    public GraphStorage storage() {
        return storage;
    }

    public NavigationManager navigation() {
        return navigation;
    }

    public VisualizationService visualization() {
        return visualization;
    }
}
