package ru.hahgps;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import ru.hahgps.command.CommandManager;
import ru.hahgps.config.ConfigManager;
import ru.hahgps.config.MessagesManager;
import ru.hahgps.gui.GuiManager;
import ru.hahgps.gui.InputManager;
import ru.hahgps.listener.ChatInputListener;
import ru.hahgps.listener.GuiListener;
import ru.hahgps.listener.PlayerQuitListener;
import ru.hahgps.listener.RouteRecordListener;
import ru.hahgps.listener.WorldLoadListener;
import ru.hahgps.service.*;
import ru.hahgps.storage.DataService;
import ru.hahgps.storage.MysqlStorageProvider;
import ru.hahgps.storage.SqliteStorageProvider;
import ru.hahgps.storage.StorageProvider;
import ru.hahgps.update.HotSwap;
import ru.hahgps.update.UpdaterService;
import ru.hahgps.util.ConsoleBanner;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class HahGpsPlugin extends JavaPlugin {

    private static HahGpsPlugin instance;

    private ConfigManager configManager;
    private MessagesManager messagesManager;

    private volatile DataService dataService;
    private StorageProvider storageProvider;

    private PoiService poiService;
    private CategoryService categoryService;
    private RouteService routeService;
    private NavigationService navigationService;
    private SearchService searchService;
    private MapService mapService;
    private CommandManager commandManager;
    private UpdaterService updater;
    private GraphService graphService;
    private VisualizationService visualizationService;
    private HudService hudService;
    private RouteRecordingService routeRecordingService;

    private volatile boolean hotSwapping;

    @Override
    public void onEnable() {
        instance = this;

        configManager = new ConfigManager(this);
        configManager.load();
        messagesManager = new MessagesManager(this);
        messagesManager.load(configManager.getLanguage());



        registerPendingCommand();

        initStorage();
    }

    private void registerPendingCommand() {
        var cmd = getCommand("gps");
        if (cmd == null) {
            return;
        }
        cmd.setExecutor((sender, command, label, args) -> {
            sender.sendMessage(getMessages().getPrefixed("loading-data"));
            return true;
        });
        cmd.setTabCompleter((sender, command, label, args) -> new java.util.ArrayList<>());
    }

    private void initStorage() {
        String type = configManager.getStorageType();
        if ("mysql".equalsIgnoreCase(type)) {
            storageProvider = new MysqlStorageProvider(
                    configManager.getMysqlHost(),
                    configManager.getMysqlPort(),
                    configManager.getMysqlDatabase(),
                    configManager.getMysqlUsername(),
                    configManager.getMysqlPassword(),
                    configManager.getMysqlUseSsl());
        } else {
            File dbFile = new File(getDataFolder(), configManager.getSqliteFile());
            storageProvider = new SqliteStorageProvider(dbFile);
        }

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                storageProvider.init();
                DataService service = new DataService(this, storageProvider);
                dataService = service;
                service.loadCache().whenComplete((v, error) -> {
                    if (!isEnabled()) {
                        return;
                    }
                    if (error != null) {
                        getLogger().log(Level.SEVERE, "Failed to load HahGps data cache", error);
                        failStartup();
                        return;
                    }
                    try {

                        Bukkit.getScheduler().runTask(this, this::onStorageReady);
                    } catch (Throwable t) {
                        getLogger().log(Level.SEVERE, "HahGps: failed to schedule startup", t);
                    }
                });
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to initialize storage", e);
                failStartup();
            }
        });
    }


    private void failStartup() {
        if (dataService == null && storageProvider != null) {

            try {
                storageProvider.close();
            } catch (Throwable ignored) {
            }
        }
        try {


            Bukkit.getScheduler().runTask(this, () -> {
                if (dataService != null) {
                    try {
                        dataService.shutdown();
                    } catch (Throwable ignored) {
                    }
                    dataService = null;
                }
                getLogger().severe("HahGps: storage initialization failed. Disabling.");
                if (isEnabled()) {
                    getServer().getPluginManager().disablePlugin(this);
                }
            });
        } catch (Throwable ignored) {

        }
    }


    private void onStorageReady() {
        if (!isEnabled()) {
            return;
        }
        try {
            poiService = new PoiService(dataService);
            categoryService = new CategoryService(dataService);
            graphService = new GraphService(dataService);
            graphService.loadGraph().whenComplete((v, error) -> {
                if (error != null) {
                    getLogger().log(Level.SEVERE, "HahGps: road graph was not loaded", error);
                }
            });
            routeService = new RouteService(graphService);
            navigationService = new NavigationService(this, configManager, messagesManager);
            searchService = new SearchService(dataService,
                    configManager.getSearchMaxResults(), configManager.getSearchThreshold());
            mapService = new MapService(this);
            visualizationService = new VisualizationService(graphService);
            hudService = new HudService();
            routeRecordingService = new RouteRecordingService();
            commandManager = new CommandManager(this);

            categoryService.seedDefaults(configManager.getDefaultCategories())
                    .whenComplete((v, error) -> {
                        if (error != null) {
                            getLogger().log(Level.WARNING,
                                    "HahGps: default categories were not seeded", error);
                        }
                    });

            var cmd = getCommand("gps");
            if (cmd != null) {
                cmd.setExecutor(commandManager);
                cmd.setTabCompleter(commandManager);
            }

            Bukkit.getPluginManager().registerEvents(new GuiListener(), this);
            Bukkit.getPluginManager().registerEvents(new ChatInputListener(), this);
            Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(), this);
            Bukkit.getPluginManager().registerEvents(new WorldLoadListener(this), this);
            Bukkit.getPluginManager().registerEvents(new RouteRecordListener(this), this);

            navigationService.start();
            Bukkit.getScheduler().runTaskTimer(this, () -> visualizationService.tick(), 20L, 20L);

            initUpdater();

            ConsoleBanner.printBanner(this, getPluginMeta().getVersion());
            getLogger().info("HahGps enabled. Storage: " + configManager.getStorageType());
        } catch (Throwable t) {

            getLogger().log(Level.SEVERE, "HahGps: startup failed", t);
            failStartup();
        }
    }

    private void initUpdater() {

        UpdaterService.ensureTokenFile(this);
        updater = new UpdaterService(this);
        updater.start();

        HotSwap.announceLastResult(this, updater.announcer());
    }

    @Override
    public void onDisable() {
        UpdaterService service = updater;
        updater = null;
        if (service != null) {
            service.stop();
        }


        try {
            if (navigationService != null) {
                navigationService.stop();
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "HahGps: navigation stop failed", t);
        }
        try {
            if (mapService != null) {
                mapService.stop();
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "HahGps: map stop failed", t);
        }
        try {
            if (routeService != null) {
                routeService.shutdown();
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "HahGps: route service stop failed", t);
        }
        try {
            if (visualizationService != null) {
                for (var player : Bukkit.getOnlinePlayers()) {
                    visualizationService.disableAll(player);
                }
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "HahGps: visualization cleanup failed", t);
        }
        try {
            GuiManager.getInstance().closeAll();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "HahGps: GUI cleanup failed", t);
        }


        if (dataService != null) {
            try {
                dataService.shutdown();
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "HahGps: storage shutdown failed", t);
            }
            dataService = null;
        } else if (storageProvider != null) {
            try {
                storageProvider.close();
            } catch (Throwable ignored) {
            }
        }


        if (service != null && !hotSwapping) {
            try {
                if (service.applyStaged()) {
                    getLogger().info("Новая версия загрузится при следующем запуске сервера.");
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Не удалось применить обновление", e);
            }
        }

        getLogger().info("HahGps disabled.");
    }

    public static HahGpsPlugin getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessagesManager getMessages() {
        return messagesManager;
    }

    public DataService getDataService() {
        return dataService;
    }

    public PoiService getPoiService() {
        return poiService;
    }

    public CategoryService getCategoryService() {
        return categoryService;
    }

    public RouteService getRouteService() {
        return routeService;
    }

    public NavigationService getNavigationService() {
        return navigationService;
    }

    public SearchService getSearchService() {
        return searchService;
    }

    public MapService getMapService() {
        return mapService;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public UpdaterService getUpdater() {
        return updater;
    }

    public GraphService getGraphService() {
        return graphService;
    }

    public VisualizationService getVisualizationService() {
        return visualizationService;
    }

    public HudService getHudService() {
        return hudService;
    }

    public RouteRecordingService getRouteRecordingService() {
        return routeRecordingService;
    }

    public boolean isHotSwapping() {
        return hotSwapping;
    }

    public void setHotSwapping(boolean value) {
        hotSwapping = value;
    }
}
