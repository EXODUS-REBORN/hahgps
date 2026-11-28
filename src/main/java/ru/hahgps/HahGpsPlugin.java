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
import ru.hahgps.listener.TrackerListener;
import ru.hahgps.listener.ItrakCraftingListener;
import ru.hahgps.listener.WorldLoadListener;
import ru.hahgps.service.*;
import ru.hahgps.storage.DataService;
import ru.hahgps.storage.MysqlStorageProvider;
import ru.hahgps.storage.SqliteStorageProvider;
import ru.hahgps.model.Tracker;
import ru.hahgps.storage.StorageProvider;
import ru.hahgps.item.TrackerCodes;
import ru.hahgps.update.HotSwap;
import ru.hahgps.update.DevChannel;
import ru.hahgps.update.UpdaterService;
import ru.hahgps.util.ConsoleBanner;
import ru.hahgps.util.StartupTimer;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class HahGpsPlugin extends JavaPlugin {

    /** Результат стартовой загрузки iTrak: трекеры и открытые к ним доступы. */
    private record TrackerLoad(java.util.List<Tracker> trackers,
                               java.util.Map<Integer, java.util.List<ru.hahgps.model.TrackerShare>> shares) {
    }

    private static HahGpsPlugin instance;

    private ConfigManager configManager;
    private MessagesManager messagesManager;

    private volatile DataService dataService;
    private StorageProvider storageProvider;

    private PoiService poiService;
    private CategoryService categoryService;
    private RouteService routeService;
    private WalkPathService walkPathService;
    private NavigationService navigationService;
    private SearchService searchService;
    private CommandManager commandManager;
    private UpdaterService updater;
    private DevChannel devChannel;
    private GraphService graphService;
    private VisualizationService visualizationService;
    private HudService hudService;
    private RouteRecordingService routeRecordingService;
    private TrackerService trackerService;
    private ResourcePackService resourcePackService;

    /** Модели в мире (физика из EXODUS-OS): каталог с панели, коллизия шалкерами, точки «сесть». */
    private ru.hahgps.vmodel.VirtualModelsService vmodels;

    private volatile boolean hotSwapping;

    /**
     * Поднят в самом начале onDisable: async-цепочка инициализацииstorage
     * проверяет его до того, как тронуть dataService, иначе присвоение после
     * выключения оставило бы DataService без shutdown (утечка пула БД).
     */
    private volatile boolean stopping;

    /** Задача тика визуализации: храним, чтобы отменять явно. */
    private org.bukkit.scheduler.BukkitTask visualizationTask;

    @Override
    public void onEnable() {
        instance = this;
        stopping = false;
        StartupTimer timer = new StartupTimer("включение (onEnable)");

        configManager = new ConfigManager(this);
        configManager.load();
        timer.phase("конфиг");
        messagesManager = new MessagesManager(this);
        messagesManager.load(configManager.getLanguage());
        timer.phase("сообщения");

        // Пак собирает и раздаёт панель EXODUS(OS) — плагин только сообщает
        // клиенту ссылку, сам ничего не хранит и не хостит.
        resourcePackService = new ResourcePackService(this, configManager);
        resourcePackService.start();

        registerPendingCommand();
        registerPendingItrakCommand();
        timer.phase("команды");

        initStorage();
        timer.report(this);
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

    /** До подъёма хранилища /itrak тоже должен отвечать, а не молчать. */
    private void registerPendingItrakCommand() {
        var cmd = getCommand("itrak");
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
                if (stopping) {
                    // Плагин уже выключали, пока поднималось хранилище.
                    try {
                        storageProvider.close();
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                DataService service = new DataService(this, storageProvider);
                dataService = service;
                service.loadCache().whenComplete((v, error) -> {
                    if (!isEnabled() || stopping) {
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


    /**
     * Счётчик уникальных кодов iTrak. Один блок номеров резервируется в БД в
     * самом начале: код не повторится даже после краша — следующий старт
     * продолжит уже за пределами выданного блока. Когда блок кончается,
     * берём следующий (это уже синхронный запрос — редко и по одному).
     */
    private void initTrackerCodes() {
        TrackerCodes.setBlockRequester(() -> {
            DataService service = dataService;
            if (service == null) {
                return -1L;
            }
            try {
                Long first = service.reserveTrackerCodes(TrackerCodes.BLOCK).join();
                return first == null ? -1L : first;
            } catch (Throwable error) {
                getLogger().log(Level.WARNING, "HahGps: не удалось зарезервировать коды iTrak", error);
                return -1L;
            }
        });
        if (dataService == null) {
            return;
        }
        dataService.reserveTrackerCodes(TrackerCodes.BLOCK).whenComplete((first, error) ->
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!isEnabled() || stopping || first == null) {
                        if (error != null) {
                            getLogger().log(Level.WARNING,
                                    "HahGps: счётчик кодов iTrak недоступен — новые трекеры получат временные коды",
                                    error);
                        }
                        return;
                    }
                    TrackerCodes.initialize(first, TrackerCodes.BLOCK);
                }));
    }

    private void failStartup() {
        if (dataService == null && storageProvider != null) {

            try {
                storageProvider.close();
            } catch (Throwable ignored) {
            }
        }
        Runnable cleanup = () -> {
            if (dataService != null) {
                try {
                    dataService.shutdown();
                } catch (Throwable ignored) {
                }
            }
            // Провайдер закрываем всегда: даже если shutdown() бросил,
            // иначе JDBC-соединение (и WAL-локи SQLite) останутся висеть.
            if (storageProvider != null) {
                try {
                    storageProvider.close();
                } catch (Throwable ignored) {
                }
            }
            dataService = null;
            getLogger().severe("HahGps: storage initialization failed. Disabling.");
            if (isEnabled()) {
                getServer().getPluginManager().disablePlugin(this);
            }
        };
        // runTask на выключенном плагине бросает IllegalPluginAccessException:
        // в этом случае чистим сразу, иначе отказ был бы тихим.
        if (isEnabled()) {
            try {
                Bukkit.getScheduler().runTask(this, cleanup);
            } catch (Throwable t) {
                cleanup.run();
            }
        } else {
            cleanup.run();
        }
    }


    private void onStorageReady() {
        if (!isEnabled()) {
            return;
        }
        StartupTimer timer = new StartupTimer("включение");
        try {
            poiService = new PoiService(dataService);
            categoryService = new CategoryService(dataService);
            graphService = new GraphService(dataService);
            graphService.loadGraph().whenComplete((v, error) -> {
                if (error != null) {
                    getLogger().log(Level.SEVERE, "HahGps: road graph was not loaded", error);
                }
            });
            walkPathService = new WalkPathService(this);
            routeService = new RouteService(graphService, walkPathService, configManager);
            navigationService = new NavigationService(this, configManager, messagesManager);
            searchService = new SearchService(dataService,
                    configManager.getSearchMaxResults(), configManager.getSearchThreshold());
            visualizationService = new VisualizationService(graphService);
            hudService = new HudService();
            routeRecordingService = new RouteRecordingService();
            trackerService = new TrackerService(this, dataService);
            commandManager = new CommandManager(this);
            timer.phase("сервисы");
            ru.hahgps.util.CmdLink.texts(key -> {
                ru.hahgps.config.MessagesManager msgs = messagesManager;
                if (msgs == null) return null;
                String text = msgs.get(key);
                return text.startsWith("Missing:") || text.contains("Missing:") ? null : text;
            });
            ru.hahgps.util.CmdLink.describer(command -> {
                ru.hahgps.config.MessagesManager msgs = messagesManager;
                if (msgs == null) return "";
                String path = command == null ? "" : command.trim();
                while (path.startsWith("/")) path = path.substring(1);
                String[] parts = path.split("\\s+");
                if (parts.length < 2) return "";
                String top = parts[1].toLowerCase(java.util.Locale.ROOT);
                String text = msgs.get("help-" + top, "description", "");
                if (text == null || text.contains("Missing:") || text.isBlank()) return "";
                return text;
            });
            initTrackerCodes();

            var trackersFuture = dataService.getTrackers().exceptionally(error -> {
                getLogger().log(Level.WARNING, "HahGps: трекеры iTrak не загрузились", error);
                return java.util.List.<Tracker>of();
            });
            // Доступы к трекерам грузим вместе с ними: без этого после рестарта
            // игрок, которому открыли трекер, видел бы его только после
            // повторной выдачи доступа.
            var sharesFuture = dataService.getTrackerShares().exceptionally(error -> {
                getLogger().log(Level.WARNING, "HahGps: доступы к трекерам iTrak не загрузились", error);
                return java.util.Map.<Integer, java.util.List<ru.hahgps.model.TrackerShare>>of();
            });
            trackersFuture.thenCombine(sharesFuture, TrackerLoad::new)
                    .thenAccept(loaded -> Bukkit.getScheduler().runTask(this, () -> {
                        if (!isEnabled() || stopping || trackerService == null) {
                            return;
                        }
                        trackerService.load(loaded.trackers(), loaded.shares());
                        trackerService.start();
                    }));
            timer.phase("трекеры");

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
            // /itrak — короткий вход в те же подкоманды: /itrak = /gps itrak.
            var itrak = getCommand("itrak");
            if (itrak != null) {
                itrak.setExecutor((sender, command, label, args) -> {
                    String[] full = new String[args.length + 1];
                    full[0] = "itrak";
                    System.arraycopy(args, 0, full, 1, args.length);
                    return commandManager.onCommand(sender, command, "gps", full);
                });
                itrak.setTabCompleter((sender, command, label, args) -> {
                    String[] full = new String[args.length + 1];
                    full[0] = "itrak";
                    System.arraycopy(args, 0, full, 1, args.length);
                    return commandManager.onTabComplete(sender, command, "gps", full);
                });
            }
            timer.phase("команды");

            // Отложенная регистрация слушателей и запуск таймеров: регистрация
            // событий и создание задач в главном потоке дают вклад в скачок MSPT
            // при перезагрузке плагина. Разбиваем на несколько тиков.
            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    Bukkit.getPluginManager().registerEvents(new GuiListener(), this);
                    Bukkit.getPluginManager().registerEvents(new ChatInputListener(), this);
                    Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(), this);
                    Bukkit.getPluginManager().registerEvents(resourcePackService, this);
                    Bukkit.getPluginManager().registerEvents(new WorldLoadListener(this), this);
                    Bukkit.getPluginManager().registerEvents(new RouteRecordListener(this), this);
                    Bukkit.getPluginManager().registerEvents(new TrackerListener(this), this);
                    ItrakCraftingListener crafting = new ItrakCraftingListener(this);
                    Bukkit.getPluginManager().registerEvents(crafting, this);
                    crafting.registerRecipes();
                } catch (Throwable t) {
                    getLogger().log(Level.WARNING, "HahGps: не удалось зарегистрировать слушателей", t);
                }
            }, 2L);

            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    ru.hahgps.vmodel.VirtualModelsService models = new ru.hahgps.vmodel.VirtualModelsService(this);
                    models.start();
                    vmodels = models;
                } catch (Throwable t) {
                    getLogger().log(Level.WARNING, "HahGps: модели в мире не поднялись", t);
                }
            }, 3L);

            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    navigationService.start();
                    visualizationTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                        try {
                            visualizationService.tick();
                        } catch (Throwable t) {
                            // Bukkit навсегда отменяет задачу при не пойманном RuntimeException:
                            // тогда визуализация молча умирает до перезапуска. Ловим и логируем.
                            getLogger().log(Level.WARNING, "HahGps: tick визуализации упал", t);
                        }
                    }, 20L, 20L);
                } catch (Throwable t) {
                    getLogger().log(Level.WARNING, "HahGps: не удалось запустить навигацию и визуализацию", t);
                }
            }, 4L);

            // Обновлятор запускаем последним и вне главного потока: первый
            // запрос к GitHub поднимает java.net.http (2–8 с на холодную), и
            // раньше это целиком попадало в тик сервера при каждом /reload.
            Bukkit.getScheduler().runTaskLater(this, this::initUpdater, 6L);

            ConsoleBanner.printBanner(this, getPluginMeta().getVersion());
            timer.report(this);
            getLogger().info("HahGps enabled. Storage: " + configManager.getStorageType());
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "HahGps: startup failed", t);
            failStartup();
        }
    }

    private void initUpdater() {
        if (!isEnabled() || stopping) {
            return;
        }
        try {
            UpdaterService.ensureTokenFile(this);
            DevChannel.ensureKeyFile(this);
            HotSwap.prime();
            UpdaterService next = new UpdaterService(this);
            next.start();
            updater = next;
            DevChannel channel = new DevChannel(this);
            channel.start();
            devChannel = channel;
            HotSwap.announceLastResult(this, next.announcer());
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "HahGps: не удалось запустить обновление", t);
        }
    }

    @Override
    public void onDisable() {
        stopping = true;
        StartupTimer timer = new StartupTimer("выключение");

        UpdaterService service = updater;
        updater = null;
        if (service != null) {
            service.stop();
        }
        DevChannel channel = devChannel;
        devChannel = null;
        if (channel != null) {
            channel.stop();
        }
        timer.phase("обновление");

        ru.hahgps.vmodel.VirtualModelsService models = vmodels;
        vmodels = null;
        if (models != null) {
            try {
                models.stop();
            } catch (Throwable t) {
                getLogger().log(Level.FINE, "HahGps: модели — остановка", t);
            }
        }

        if (visualizationTask != null) {
            try {
                visualizationTask.cancel();
            } catch (Throwable ignored) {
            }
            visualizationTask = null;
        }

        try {
            if (trackerService != null) {
                trackerService.stop();
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "HahGps: tracker stop failed", t);
        }
        timer.phase("трекеры");
        try {
            if (navigationService != null) {
                navigationService.stop();
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "HahGps: navigation stop failed", t);
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
        timer.phase("остановка");


        DataService data = dataService;
        if (data != null) {
            try {
                data.shutdown();
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "HahGps: storage shutdown failed", t);
            }
        }
        timer.phase("хранилище");
        // Провайдер закрываем отдельным if, а не else-if: если shutdown()
        // бросил, соединение всё равно должно закрыться.
        if (storageProvider != null) {
            try {
                storageProvider.close();
            } catch (Throwable ignored) {
            }
            storageProvider = null;
        }

        // Ссылки на сервисы и dataService намеренно НЕ обнуляем: висящие
        // async-колбэки (whenComplete в командах и GUI) дергают геттеры, и
        // runSync/isEnabled внутри них гасительно — NPE в async-потоке
        // проглотился бы future'ом, и игрок не получил бы даже ошибки.
        // Статический instance обнуляем: getInstance() не должен отдавать
        // выключенный плагин (важно для hot-swap).
        instance = null;

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
        timer.report(this);
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

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public UpdaterService getUpdater() {
        return updater;
    }

    public DevChannel getDevChannel() {
        return devChannel;
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

    public TrackerService getTrackerService() {
        return trackerService;
    }

    public ru.hahgps.vmodel.VirtualModelsService getVmodels() {
        return vmodels;
    }

    public ResourcePackService getResourcePackService() {
        return resourcePackService;
    }

    public boolean isHotSwapping() {
        return hotSwapping;
    }

    public void setHotSwapping(boolean value) {
        hotSwapping = value;
    }
}
