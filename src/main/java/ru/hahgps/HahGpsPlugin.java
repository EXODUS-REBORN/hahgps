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
import ru.hahgps.service.*;
import ru.hahgps.storage.DataService;
import ru.hahgps.storage.MysqlStorageProvider;
import ru.hahgps.storage.SqliteStorageProvider;
import ru.hahgps.storage.StorageProvider;
import ru.hahgps.update.HotSwap;
import ru.hahgps.update.UpdaterService;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class HahGpsPlugin extends JavaPlugin {

    private static HahGpsPlugin instance;

    private ConfigManager configManager;
    private MessagesManager messagesManager;
    private DataService dataService;
    private StorageProvider storageProvider;

    private PoiService poiService;
    private CategoryService categoryService;
    private RouteService routeService;
    private NavigationService navigationService;
    private SearchService searchService;
    private MapService mapService;
    private CommandManager commandManager;
    private UpdaterService updater;

    private volatile boolean hotSwapping;

    private volatile boolean ready = false;

    @Override
    public void onEnable() {
        instance = this;

        configManager = new ConfigManager(this);
        configManager.load();
        messagesManager = new MessagesManager(this);
        messagesManager.load(configManager.getLanguage());

        initStorage();

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
                    false);
        } else {
            File dbFile = new File(getDataFolder(), configManager.getSqliteFile());
            storageProvider = new SqliteStorageProvider(dbFile);
        }

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                storageProvider.init();
                dataService = new DataService(this, storageProvider);
                dataService.loadCache().thenRun(this::onStorageReady);
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to initialize storage", e);
                Bukkit.getScheduler().runTask(this, () -> {
                    getLogger().severe("HahGps: storage initialization failed. Disabling.");
                    getServer().getPluginManager().disablePlugin(this);
                });
            }
        });
    }

    private void onStorageReady() {

        poiService = new PoiService(dataService);
        categoryService = new CategoryService(dataService);
        routeService = new RouteService();
        navigationService = new NavigationService(this, configManager, messagesManager);
        searchService = new SearchService(dataService,
                configManager.getSearchMaxResults(), configManager.getSearchThreshold());
        mapService = new MapService(this);
        commandManager = new CommandManager(this);

        categoryService.seedDefaults(configManager.getDefaultCategories());

        var cmd = getCommand("gps");
        if (cmd != null) {
            cmd.setExecutor(commandManager);
            cmd.setTabCompleter(commandManager);
        }

        Bukkit.getPluginManager().registerEvents(new GuiListener(), this);
        Bukkit.getPluginManager().registerEvents(new ChatInputListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(), this);

        navigationService.start();

        initUpdater();

        ready = true;
        getLogger().info("HahGps enabled. Storage: " + configManager.getStorageType());
    }

    private void initUpdater() {

        UpdaterService.ensureTokenFile(this);
        updater = new UpdaterService(this);
        updater.start();

        HotSwap.announceLastResult(this);
    }

    @Override
    public void onDisable() {
        ready = false;
        if (navigationService != null) {
            navigationService.stop();
        }
        if (mapService != null) {
            mapService.stop();
        }
        GuiManager.getInstance().closeAll();
        if (dataService != null) {
            dataService.shutdown();
        }

        if (updater != null) {
            UpdaterService service = updater;
            if (!hotSwapping) {
                try {
                    if (service.applyStaged()) {
                        getLogger().info("Новая версия загрузится при следующем запуске сервера.");
                    }
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Не удалось применить обновление", e);
                }
            }
            updater.stop();
            updater = null;
        }
        getLogger().info("HahGps disabled.");
    }

    public boolean isReady() {
        return ready;
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

    public boolean isHotSwapping() {
        return hotSwapping;
    }

    public void setHotSwapping(boolean value) {
        hotSwapping = value;
    }
}
