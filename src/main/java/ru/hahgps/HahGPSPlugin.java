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

/**
 * Главный класс плагина HahGps.
 */
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

    /**
     * Идёт горячая подмена (см. {@code update/HotSwap}): плагин выгружают специально, чтобы
     * поднять рядом новую версию. В этом режиме в {@code onDisable} нельзя трогать файл плагина —
     * подменой занимается сам HotSwap.
     */
    private volatile boolean hotSwapping;

    private volatile boolean ready = false;

    @Override
    public void onEnable() {
        instance = this;

        // Конфиг и сообщения
        configManager = new ConfigManager(this);
        configManager.load();
        messagesManager = new MessagesManager(this);
        messagesManager.load(configManager.getLanguage());

        // Хранилище
        initStorage();

        // Сервисы инициализируются после загрузки кэша (в onStorageReady)
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
        // Сервисы
        poiService = new PoiService(dataService);
        categoryService = new CategoryService(dataService);
        routeService = new RouteService();
        navigationService = new NavigationService(this, configManager, messagesManager);
        searchService = new SearchService(dataService,
                configManager.getSearchMaxResults(), configManager.getSearchThreshold());
        mapService = new MapService(this);
        commandManager = new CommandManager(this);

        // Стандартные категории
        categoryService.seedDefaults(configManager.getDefaultCategories());

        // Команда
        var cmd = getCommand("gps");
        if (cmd != null) {
            cmd.setExecutor(commandManager);
            cmd.setTabCompleter(commandManager);
        }

        // Слушатели
        Bukkit.getPluginManager().registerEvents(new GuiListener(), this);
        Bukkit.getPluginManager().registerEvents(new ChatInputListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(), this);

        // Задача навигации
        navigationService.start();

        // Автообновление — после того, как хранилище готово (обновление может выгрузить плагин).
        initUpdater();

        ready = true;
        getLogger().info("HahGps enabled. Storage: " + configManager.getStorageType());
    }

    /** Автообновление из GitHub Releases: проверка версии, скачивание и горячая подмена. */
    private void initUpdater() {
        // Файл с ключом создаём сразу: админу остаётся вписать в него токен (или он возьмётся
        // из папки основного плагина hahtojer — токен у плагинов общий).
        UpdaterService.ensureTokenFile(this);
        updater = new UpdaterService(this);
        updater.start();
        // Если мы только что поднялись на ходу вместо прежней версии — скажем об этом админам.
        HotSwap.announceLastResult(this);
    }

    @Override
    public void onDisable() {
        ready = false;
        if (navigationService != null) {
            navigationService.stop();
        }
        GuiManager.getInstance().closeAll();
        if (dataService != null) {
            dataService.shutdown();
        }
        // Обновление применяем последним: плагин уже всё сохранил. При горячей подмене
        // файл подменяет сам HotSwap — здесь молчим.
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

    // ---------------- Геттеры ----------------

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

    /** Автообновление плагина (может быть {@code null}, пока хранилище не готово). */
    public UpdaterService getUpdater() {
        return updater;
    }

    /** Идёт горячая подмена версии — файл плагина трогать нельзя. */
    public boolean isHotSwapping() {
        return hotSwapping;
    }

    public void setHotSwapping(boolean value) {
        hotSwapping = value;
    }
}
