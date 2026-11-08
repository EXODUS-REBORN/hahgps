package ru.hahgps.command.subcommands;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.gui.GuiTheme;
import ru.hahgps.gui.ItrakMenuGui;
import ru.hahgps.item.ItemFactory;
import ru.hahgps.model.Tracker;
import ru.hahgps.model.TrackerColor;
import ru.hahgps.model.TransportType;
import ru.hahgps.service.LocatorCooldown;
import ru.hahgps.service.MapService;
import ru.hahgps.service.Pathfinder;
import ru.hahgps.service.TrackerService;
import ru.hahgps.util.LocationUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * iTrak: выдача, поиск, слежение и маршруты к GPS-трекерам.
 *
 * Подкоманды повторяют то, что умеет GUI, — админам и в консольных сценариях
 * удобнее команда. Общей частью (текст сканирования, старт маршрута) владеет
 * этот класс: локатор в руке вызывает тот же {@link #reportScan}.
 */
public class ItrakCommand implements SubCommand {

    @Override
    public String name() {
        return "itrak";
    }

    @Override
    public String usage() {
        return "/gps itrak [give|give-locator|give-terminal|scan|find|list|info|route|take|remove|watch|unwatch]";
    }

    @Override
    public String permission() {
        return "gps.itrak";
    }

    @Override
    public List<SubCommand> children() {
        return List.of(new GiveCommand(), new GiveLocatorCommand(),
                new GiveTerminalCommand(), new ScanCommand(), new FindCommand(), new ListCommand(),
                new InfoCommand(), new RouteCommand(), new TakeCommand(),
                new RemoveCommand(), new WatchCommand(), new UnwatchCommand());
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (!plugin.getConfigManager().isItrakEnabled()) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-disabled"));
            return;
        }
        if (plugin.getTrackerService() == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("loading-data"));
            return;
        }
        new ItrakMenuGui(player).open();
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        return List.of();
    }

    // ------------------------------------------------------------------
    // Общая логика
    // ------------------------------------------------------------------

    /** Скан трекеров поблизости: локатор в руке и /gps itrak scan. */
    public static void reportScan(HahGpsPlugin plugin, Player player) {
        reportScan(plugin, player, plugin.getConfigManager().getItrakScanRadius());
    }

    /**
     * Скан с радиусом конкретного локатора: у предмета может быть своя
     * версия (10, 15 блоков), радиус берётся из PDC локатора в руке.
     */
    public static void reportScan(HahGpsPlugin plugin, Player player, double radius) {
        TrackerService service = plugin.getTrackerService();
        if (service == null) {
            return;
        }
        double scanRadius = Math.max(1, Math.min(1000, radius));
        List<TrackerService.Nearby> found = service.scan(player, scanRadius);
        if (found.isEmpty()) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-scan-empty",
                    "radius", String.valueOf((int) scanRadius)));
            GuiTheme.deny(player);
            return;
        }
        player.sendMessage(plugin.getMessages().getPrefixed("itrak-scan-header",
                "count", String.valueOf(found.size()),
                "radius", String.valueOf((int) scanRadius)));
        int seconds = plugin.getConfigManager().getItrakScanHighlightSeconds();
        for (TrackerService.Nearby nearby : found) {
            Tracker tracker = nearby.tracker();
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-scan-entry",
                    "name", tracker.getDisplayName(),
                    "code", tracker.getCode(),
                    "distance", LocationUtil.formatDistance(nearby.distance()),
                    "x", TrackerService.formatCoord(tracker.getX()),
                    "y", TrackerService.formatCoord(tracker.getY()),
                    "z", TrackerService.formatCoord(tracker.getZ()),
                    "status", service.statusText(tracker)));
            sendTrackerAction(plugin, player, tracker);
            service.highlight(player, tracker, seconds);
        }
        player.sendMessage(plugin.getMessages().getComponent("itrak-route-cancel-button"));
        player.sendMessage(plugin.getMessages().getPrefixed("itrak-scan-highlight",
                "count", String.valueOf(found.size()), "seconds", String.valueOf(seconds)));
        GuiTheme.click(player);
    }

    /**
     * Проверка перед сканом: право, загрузка данных и перезарядка локатора.
     * Возвращает false, если сканировать нельзя (игрок уже получил ответ).
     *
     * КД общий на игрока, а не на предмет: длительность зависит от локатора,
     * которым сканировали (10 блоков — 5 минут, дальний — 7), но второй
     * локатор не даёт обойти перезарядку. Исключений для админов нет: иначе
     * проверить и почувствовать КД было бы нельзя.
     */
    public static boolean prepareScan(HahGpsPlugin plugin, Player player, double radius) {
        TrackerService service = plugin.getTrackerService();
        if (service == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("loading-data"));
            return false;
        }
        if (!plugin.getConfigManager().isItrakEnabled()) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-disabled"));
            return false;
        }
        if (!player.hasPermission("gps.itrak")) {
            player.sendMessage(plugin.getMessages().getPrefixed("no-permission"));
            return false;
        }
        long remaining = service.scanCooldownRemaining(player);
        if (remaining > 0L) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-locator-cooldown",
                    "time", LocatorCooldown.format(remaining)));
            // Ванильная полоса могла пропасть после перезахода — вернём её.
            service.applyScanCooldownBar(player);
            GuiTheme.deny(player);
            return false;
        }
        service.startScanCooldown(player, service.scanCooldownSeconds(radius));
        return true;
    }

    /**
     * Кнопка под найденным трекером: к чужому — «построить маршрут», а если
     * маячок на самом сканирующем — «снять трекер» (маршрут к себе не нужен).
     */
    private static void sendTrackerAction(HahGpsPlugin plugin, Player player, Tracker tracker) {
        if (isOnPlayer(player, tracker)) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-scan-self",
                    "name", tracker.getDisplayName()));
            player.sendMessage(plugin.getMessages().getComponent("itrak-take-button",
                    "code", tracker.getCode()));
            return;
        }
        player.sendMessage(plugin.getMessages().getComponent("itrak-route-button",
                "code", tracker.getCode()));
    }

    /** Трекер прицеплен к этому игроку. */
    public static boolean isOnPlayer(Player player, Tracker tracker) {
        return player != null && tracker != null && tracker.getEntityUuid() != null
                && tracker.getEntityUuid().equals(player.getUniqueId());
    }

    /**
     * Нажатие на терминал: координаты трекеров в радиусе и подсветка.
     * Тот же путь использует сам блок-терминал из слушателя.
     */
    public static void reportTerminal(HahGpsPlugin plugin, Player player, Block block) {
        TrackerService service = plugin.getTrackerService();
        if (service == null || block == null) {
            return;
        }
        double radius = plugin.getConfigManager().getItrakTerminalRadius();
        Location origin = block.getLocation().add(0.5, 0.5, 0.5);
        List<TrackerService.Nearby> found = service.scanAround(origin, radius);
        if (found.isEmpty()) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-terminal-empty",
                    "radius", String.valueOf((int) radius)));
            GuiTheme.deny(player);
            return;
        }
        player.sendMessage(plugin.getMessages().getPrefixed("itrak-terminal-header",
                "count", String.valueOf(found.size())));
        int seconds = plugin.getConfigManager().getItrakTerminalHighlightSeconds();
        for (TrackerService.Nearby nearby : found) {
            Tracker tracker = nearby.tracker();
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-terminal-entry",
                    "name", tracker.getDisplayName(),
                    "code", tracker.getCode(),
                    "x", TrackerService.formatCoord(tracker.getX()),
                    "y", TrackerService.formatCoord(tracker.getY()),
                    "z", TrackerService.formatCoord(tracker.getZ()),
                    "distance", LocationUtil.formatDistance(nearby.distance()),
                    "status", service.statusText(tracker)));
            sendTrackerAction(plugin, player, tracker);
            service.highlight(player, tracker, seconds);
        }
        player.sendMessage(plugin.getMessages().getComponent("itrak-route-cancel-button"));
        player.sendMessage(plugin.getMessages().getPrefixed("itrak-terminal-highlight",
                "count", String.valueOf(found.size()), "seconds", String.valueOf(seconds)));
        GuiTheme.click(player);
    }

    /** Маршрут к трекеру: путь строится в пуле, навигация ведёт к живой цели. */
    public static void startRoute(HahGpsPlugin plugin, Player player, Tracker tracker,
                                  TransportType transport) {
        TrackerService service = plugin.getTrackerService();
        if (service == null || tracker == null) {
            return;
        }
        Location target = service.liveLocation(tracker);
        if (target == null) {
            target = tracker.getLocation();
        }
        if (target == null || target.getWorld() == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("world-not-found",
                    "world", tracker.getWorld() == null ? "?" : tracker.getWorld()));
            return;
        }
        final Location goal = target.clone();
        plugin.getRouteService().buildMultiModalAsync(player, goal, transport)
                .whenComplete((path, error) -> plugin.getDataService().runSync(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        SubCommand.sendError(player, error);
                        return;
                    }
                    List<Location> points = path == null ? List.of() : path;
                    double distance = Pathfinder.routeLength(points);
                    if (distance <= 0 || !Double.isFinite(distance)) {
                        distance = LocationUtil.distance(player.getLocation(), goal);
                    }
                    plugin.getNavigationService().startTrackerNavigation(player, tracker, goal,
                            points, distance, transport);
                    player.sendMessage(plugin.getMessages().getPrefixed("itrak-route-started",
                            "name", tracker.getDisplayName(),
                            "distance", LocationUtil.formatDistance(distance)));
                    long now = System.currentTimeMillis();
                    if (tracker.status() == Tracker.Status.LIVE
                            && !tracker.isStationary(now, plugin.getConfigManager().getItrakStationaryMillis())) {
                        player.sendMessage(plugin.getMessages().getPrefixed("itrak-route-live"));
                    }
                }));
    }

    static Tracker requireTracker(HahGpsPlugin plugin, Player player, String query) {
        TrackerService service = plugin.getTrackerService();
        if (service == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("loading-data"));
            return null;
        }
        Tracker tracker = service.find(query);
        if (tracker == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-not-found", "name", query));
        }
        return tracker;
    }

    static Tracker requireOwned(HahGpsPlugin plugin, Player player, String query) {
        Tracker tracker = requireTracker(plugin, player, query);
        if (tracker == null) {
            return null;
        }
        if (!plugin.getTrackerService().canManage(player, tracker)) {
            player.sendMessage(plugin.getMessages().getPrefixed("no-permission"));
            return null;
        }
        return tracker;
    }

    /**
     * Найти свой трекер по коду, имени или номеру. Чужие не находим вовсе:
     * «find» — личный локатор, а не способ подсмотреть чужой маячок.
     */
    static Tracker requireMine(HahGpsPlugin plugin, Player player, String query) {
        TrackerService service = plugin.getTrackerService();
        if (service == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("loading-data"));
            return null;
        }
        Tracker tracker = service.find(query);
        if (tracker == null || !service.canManage(player, tracker)) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-not-found", "name", query));
            return null;
        }
        return tracker;
    }

    /**
     * Навести карту игрока на трекер и подсветить его. Используется командой
     * «find» и кнопкой «показать на карте» в карточке.
     */
    public static void focusOnMap(HahGpsPlugin plugin, Player player, Tracker tracker) {
        TrackerService service = plugin.getTrackerService();
        if (service == null || tracker == null || player == null) {
            return;
        }
        Location target = service.liveLocation(tracker);
        if (target == null || target.getWorld() == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("world-not-found",
                    "world", tracker.getWorld() == null ? "?" : tracker.getWorld()));
            return;
        }
        if (!LocationUtil.sameWorld(player.getWorld(), target.getWorld())) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-find-other-world",
                    "world", target.getWorld().getName()));
            GuiTheme.deny(player);
            return;
        }
        MapService maps = plugin.getMapService();
        if (maps == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("loading-data"));
            return;
        }
        maps.focus(player, target);
        int seconds = plugin.getConfigManager().getItrakScanHighlightSeconds();
        service.highlight(player, tracker, seconds);
        player.sendMessage(plugin.getMessages().getPrefixed("itrak-find-focused",
                "name", tracker.getDisplayName(),
                "id", String.valueOf(tracker.getId()),
                "status", service.statusText(tracker),
                "world", target.getWorld().getName(),
                "x", TrackerService.formatCoord(target.getX()),
                "y", TrackerService.formatCoord(target.getY()),
                "z", TrackerService.formatCoord(target.getZ())));
        GuiTheme.click(player);
    }

    public static void sendInfo(HahGpsPlugin plugin, Player player, Tracker tracker) {
        TrackerService service = plugin.getTrackerService();
        long now = System.currentTimeMillis();
        Location location = tracker.getLocation();
        double distance = location == null ? Double.MAX_VALUE
                : LocationUtil.distance(player.getLocation(), location);
        String type = tracker.isWaterproof()
                ? plugin.getMessages().get("itrak-type-waterproof")
                : plugin.getMessages().get("itrak-type-regular");
        String raw = plugin.getMessages().get("itrak-info",
                "name", tracker.getDisplayName(),
                "id", String.valueOf(tracker.getId()),
                "code", tracker.getCode(),
                "color", plugin.getMessages().get(tracker.getColor().messageKey()),
                "type", type,
                "owner", tracker.getOwnerName(),
                "status", service == null ? "?" : service.statusText(tracker),
                "state", service == null ? "?" : service.stateText(tracker, now),
                "world", tracker.getWorld() == null ? "?" : tracker.getWorld(),
                "x", TrackerService.formatCoord(tracker.getX()),
                "y", TrackerService.formatCoord(tracker.getY()),
                "z", TrackerService.formatCoord(tracker.getZ()),
                "distance", LocationUtil.formatDistance(distance),
                "time", service == null ? "?" : service.formatDuration(tracker.stationaryMillis(now)));
        for (String line : raw.split("\n")) {
            player.sendMessage(plugin.getMessages().getComponentRaw(line));
        }
    }

    // ------------------------------------------------------------------
    // Подкоманды
    // ------------------------------------------------------------------

    private static class ScanCommand implements SubCommand {
        @Override
        public String name() {
            return "scan";
        }

        @Override
        public String usage() {
            return "/gps itrak scan";
        }

        @Override
        public String permission() {
            return "gps.itrak";
        }

        @Override
        public void execute(Player player, String[] args) {
            HahGpsPlugin plugin = HahGpsPlugin.getInstance();
            if (!prepareScan(plugin, player, plugin.getConfigManager().getItrakScanRadius())) {
                return;
            }
            reportScan(plugin, player);
        }

        @Override
        public List<String> tabComplete(Player player, String[] args) {
            return List.of();
        }
    }

    private static class ListCommand implements SubCommand {
        @Override
        public String name() {
            return "list";
        }

        @Override
        public String usage() {
            return "/gps itrak list";
        }

        @Override
        public String permission() {
            return "gps.itrak";
        }

        @Override
        public void execute(Player player, String[] args) {
            HahGpsPlugin plugin = HahGpsPlugin.getInstance();
            TrackerService service = plugin.getTrackerService();
            if (service == null) {
                player.sendMessage(plugin.getMessages().getPrefixed("loading-data"));
                return;
            }
            boolean admin = player.hasPermission("gps.itrak.admin");
            List<Tracker> list = admin ? service.all() : service.byOwner(player.getUniqueId());
            if (list.isEmpty()) {
                player.sendMessage(plugin.getMessages().getPrefixed("itrak-no-trackers"));
                return;
            }
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-list-header",
                    "count", String.valueOf(list.size())));
            for (Tracker tracker : list) {
                Location location = service.liveLocation(tracker);
                if (location == null) {
                    location = tracker.getLocation();
                }
                double distance = location == null ? Double.MAX_VALUE
                        : LocationUtil.distance(player.getLocation(), location);
                player.sendMessage(plugin.getMessages().getPrefixed("itrak-list-entry",
                        "name", tracker.getDisplayName(),
                        "id", String.valueOf(tracker.getId()),
                        "code", tracker.getCode(),
                        "status", service.statusText(tracker),
                        "distance", LocationUtil.formatDistance(distance),
                        "x", TrackerService.formatCoord(tracker.getX()),
                        "y", TrackerService.formatCoord(tracker.getY()),
                        "z", TrackerService.formatCoord(tracker.getZ())));
            }
        }

        @Override
        public List<String> tabComplete(Player player, String[] args) {
            return List.of();
        }
    }

    /**
     * «Мои трекеры» — личный локатор без радиуса и перезарядки: показывает все
     * свои трекеры (в том числе офлайн на вышедшем игроке), а с аргументом
     * наводит карту на выбранный. Принимает код, имя и номер в БД.
     */
    private static class FindCommand implements SubCommand {
        @Override
        public String name() {
            return "find";
        }

        @Override
        public String usage() {
            return "/gps itrak find [код|имя|id]";
        }

        @Override
        public String permission() {
            return "gps.itrak";
        }

        @Override
        public void execute(Player player, String[] args) {
            HahGpsPlugin plugin = HahGpsPlugin.getInstance();
            TrackerService service = plugin.getTrackerService();
            if (service == null) {
                player.sendMessage(plugin.getMessages().getPrefixed("loading-data"));
                return;
            }
            if (args.length > 0) {
                // Имя может состоять из нескольких слов — склеиваем обратно.
                String query = join(args, 0, args.length);
                Tracker tracker = requireMine(plugin, player, query);
                if (tracker != null) {
                    focusOnMap(plugin, player, tracker);
                }
                return;
            }
            boolean admin = player.hasPermission("gps.itrak.admin");
            List<Tracker> list = admin ? service.all() : service.byOwner(player.getUniqueId());
            if (list.isEmpty()) {
                player.sendMessage(plugin.getMessages().getPrefixed("itrak-no-trackers"));
                return;
            }
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-find-header",
                    "count", String.valueOf(list.size())));
            int seconds = plugin.getConfigManager().getItrakScanHighlightSeconds();
            for (Tracker tracker : list) {
                Location location = service.liveLocation(tracker);
                if (location == null) {
                    location = tracker.getLocation();
                }
                double distance = location == null ? Double.MAX_VALUE
                        : LocationUtil.distance(player.getLocation(), location);
                player.sendMessage(plugin.getMessages().getPrefixed("itrak-find-entry",
                        "name", tracker.getDisplayName(),
                        "id", String.valueOf(tracker.getId()),
                        "code", tracker.getCode(),
                        "status", service.statusText(tracker),
                        "distance", LocationUtil.formatDistance(distance),
                        "x", TrackerService.formatCoord(tracker.getX()),
                        "y", TrackerService.formatCoord(tracker.getY()),
                        "z", TrackerService.formatCoord(tracker.getZ())));
                sendTrackerAction(plugin, player, tracker);
                player.sendMessage(plugin.getMessages().getComponent("itrak-find-map-button",
                        "code", tracker.getCode()));
                if (location != null && LocationUtil.sameWorld(player.getWorld(), location.getWorld())) {
                    service.highlight(player, tracker, seconds);
                }
            }
            GuiTheme.click(player);
        }

        @Override
        public List<String> tabComplete(Player player, String[] args) {
            return args.length == 1 ? trackerSuggestions(player, args[0]) : List.of();
        }
    }

    private static class InfoCommand implements SubCommand {
        @Override
        public String name() {
            return "info";
        }

        @Override
        public String usage() {
            return "/gps itrak info <код|имя>";
        }

        @Override
        public String permission() {
            return "gps.itrak";
        }

        @Override
        public void execute(Player player, String[] args) {
            HahGpsPlugin plugin = HahGpsPlugin.getInstance();
            if (args.length == 0) {
                player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
                return;
            }
            Tracker tracker = requireTracker(plugin, player, args[0]);
            if (tracker != null) {
                sendInfo(plugin, player, tracker);
            }
        }

        @Override
        public List<String> tabComplete(Player player, String[] args) {
            return args.length == 1 ? trackerSuggestions(player, args[0]) : List.of();
        }
    }

    private static class RouteCommand implements SubCommand {
        @Override
        public String name() {
            return "route";
        }

        @Override
        public String usage() {
            return "/gps itrak route <код|имя> [foot|car]";
        }

        @Override
        public String permission() {
            return "gps.itrak";
        }

        @Override
        public void execute(Player player, String[] args) {
            HahGpsPlugin plugin = HahGpsPlugin.getInstance();
            if (args.length == 0) {
                player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
                return;
            }
            Tracker tracker = requireTracker(plugin, player, args[0]);
            if (tracker == null) {
                return;
            }
            TransportType transport = TransportType.FOOT;
            if (args.length >= 2) {
                TransportType parsed = SubCommand.parseTransport(args[1]);
                if (parsed == null) {
                    player.sendMessage(plugin.getMessages().getPrefixed("invalid-args",
                            "usage", usage()));
                    return;
                }
                transport = parsed;
            }
            if (!plugin.getConfigManager().isTransportAvailable(transport.getKey())) {
                player.sendMessage(plugin.getMessages().getPrefixed("transport-disabled",
                        "transport", transport.getKey()));
                return;
            }
            startRoute(plugin, player, tracker, transport);
        }

        @Override
        public List<String> tabComplete(Player player, String[] args) {
            if (args.length == 1) {
                return trackerSuggestions(player, "");
            }
            if (args.length == 2) {
                List<String> transports = new ArrayList<>();
                for (TransportType type : TransportType.values()) {
                    if (HahGpsPlugin.getInstance().getConfigManager().isTransportAvailable(type.getKey())) {
                        transports.add(type.getKey());
                    }
                }
                return SubCommand.filterByPrefix(transports, args[1]);
            }
            return List.of();
        }
    }

    private static class TakeCommand implements SubCommand {
        @Override
        public String name() {
            return "take";
        }

        @Override
        public String usage() {
            return "/gps itrak take <код|имя>";
        }

        @Override
        public String permission() {
            return "gps.itrak";
        }

        @Override
        public void execute(Player player, String[] args) {
            HahGpsPlugin plugin = HahGpsPlugin.getInstance();
            if (args.length == 0) {
                player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
                return;
            }
            Tracker tracker = requireOwned(plugin, player, args[0]);
            if (tracker == null) {
                return;
            }
            TrackerService service = plugin.getTrackerService();
            if (!service.canTake(player, tracker)) {
                Location location = tracker.getLocation();
                double distance = location == null ? Double.MAX_VALUE
                        : LocationUtil.distance(player.getLocation(), location);
                player.sendMessage(plugin.getMessages().getPrefixed("itrak-take-far",
                        "distance", LocationUtil.formatDistance(distance),
                        "radius", String.valueOf((int) plugin.getConfigManager().getItrakTakeRadius())));
                return;
            }
            String name = tracker.getDisplayName();
            service.take(player, tracker).whenComplete((ok, error) ->
                    plugin.getDataService().runSync(() -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (error != null) {
                            SubCommand.sendError(player, error);
                            return;
                        }
                        player.sendMessage(plugin.getMessages().getPrefixed("itrak-taken", "name", name));
                    }));
        }

        @Override
        public List<String> tabComplete(Player player, String[] args) {
            return args.length == 1 ? trackerSuggestions(player, args[0]) : List.of();
        }
    }

    private static class RemoveCommand implements SubCommand {
        @Override
        public String name() {
            return "remove";
        }

        @Override
        public String usage() {
            return "/gps itrak remove <код|имя>";
        }

        @Override
        public String permission() {
            return "gps.itrak.admin";
        }

        @Override
        public void execute(Player player, String[] args) {
            HahGpsPlugin plugin = HahGpsPlugin.getInstance();
            if (args.length == 0) {
                player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
                return;
            }
            Tracker tracker = requireTracker(plugin, player, args[0]);
            if (tracker == null) {
                return;
            }
            String name = tracker.getDisplayName();
            plugin.getTrackerService().remove(tracker).whenComplete((ok, error) ->
                    plugin.getDataService().runSync(() -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (error != null) {
                            SubCommand.sendError(player, error);
                            return;
                        }
                        player.sendMessage(plugin.getMessages().getPrefixed("itrak-removed", "name", name));
                    }));
        }

        @Override
        public List<String> tabComplete(Player player, String[] args) {
            return args.length == 1 ? trackerSuggestions(player, args[0]) : List.of();
        }
    }

    private static class WatchCommand implements SubCommand {
        @Override
        public String name() {
            return "watch";
        }

        @Override
        public String usage() {
            return "/gps itrak watch <код|имя>";
        }

        @Override
        public String permission() {
            return "gps.itrak";
        }

        @Override
        public void execute(Player player, String[] args) {
            HahGpsPlugin plugin = HahGpsPlugin.getInstance();
            Tracker tracker = args.length == 0 ? null : requireTracker(plugin, player, args[0]);
            if (tracker == null) {
                if (args.length == 0) {
                    player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
                }
                return;
            }
            plugin.getTrackerService().watch(player, tracker);
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-watch-on",
                    "name", tracker.getDisplayName()));
        }

        @Override
        public List<String> tabComplete(Player player, String[] args) {
            return args.length == 1 ? trackerSuggestions(player, args[0]) : List.of();
        }
    }

    private static class UnwatchCommand implements SubCommand {
        @Override
        public String name() {
            return "unwatch";
        }

        @Override
        public String usage() {
            return "/gps itrak unwatch <код|имя>";
        }

        @Override
        public String permission() {
            return "gps.itrak";
        }

        @Override
        public void execute(Player player, String[] args) {
            HahGpsPlugin plugin = HahGpsPlugin.getInstance();
            Tracker tracker = args.length == 0 ? null : requireTracker(plugin, player, args[0]);
            if (tracker == null) {
                if (args.length == 0) {
                    player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
                }
                return;
            }
            plugin.getTrackerService().unwatch(player, tracker);
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-watch-off",
                    "name", tracker.getDisplayName()));
        }

        @Override
        public List<String> tabComplete(Player player, String[] args) {
            return args.length == 1 ? trackerSuggestions(player, args[0]) : List.of();
        }
    }

    private static class GiveTerminalCommand implements SubCommand {
        @Override
        public String name() {
            return "give-terminal";
        }

        @Override
        public String usage() {
            return "/gps itrak give-terminal [игрок]";
        }

        @Override
        public String permission() {
            return "gps.itrak.give";
        }

        @Override
        public void execute(Player player, String[] args) {
            HahGpsPlugin plugin = HahGpsPlugin.getInstance();
            Player target = player;
            if (args.length >= 1) {
                Player named = Bukkit.getPlayerExact(args[0]);
                if (named == null) {
                    player.sendMessage(plugin.getMessages().getPrefixed("itrak-player-not-found",
                            "name", args[0]));
                    return;
                }
                target = named;
            }
            ItemFactory.give(target, ItemFactory.terminalItem());
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-terminal-given",
                    "player", target.getName()));
        }

        @Override
        public List<String> tabComplete(Player player, String[] args) {
            return args.length == 1 ? SubCommand.filterByPrefix(playerNames(), args[0]) : List.of();
        }
    }

    private static class GiveCommand implements SubCommand {
        @Override
        public String name() {
            return "give";
        }

        @Override
        public String usage() {
            return "/gps itrak give <цвет> [waterproof] [имя] [игрок]";
        }

        @Override
        public String permission() {
            return "gps.itrak.give";
        }

        @Override
        public void execute(Player player, String[] args) {
            HahGpsPlugin plugin = HahGpsPlugin.getInstance();
            if (args.length == 0) {
                player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
                return;
            }
            TrackerColor color = TrackerColor.parse(args[0]);
            if (color == null) {
                player.sendMessage(plugin.getMessages().getPrefixed("itrak-color-unknown",
                        "color", args[0]));
                return;
            }
            boolean waterproof = false;
            int index = 1;
            if (index < args.length && isWaterproofToken(args[index])) {
                waterproof = true;
                index++;
            } else if (index < args.length && isRegularToken(args[index])) {
                index++;
            }
            Player target = player;
            String label = "";
            if (args.length - index >= 2) {
                Player named = Bukkit.getPlayerExact(args[args.length - 1]);
                if (named != null) {
                    target = named;
                    label = join(args, index, args.length - 1);
                } else {
                    label = join(args, index, args.length);
                }
            } else if (args.length - index == 1) {
                Player named = Bukkit.getPlayerExact(args[index]);
                if (named != null) {
                    target = named;
                } else {
                    label = args[index];
                }
            }
            giveTracker(plugin, player, target, color, waterproof, label);
        }

        @Override
        public List<String> tabComplete(Player player, String[] args) {
            if (args.length == 1) {
                return SubCommand.filterByPrefix(TrackerColor.keys(), args[0]);
            }
            if (args.length == 2) {
                return SubCommand.filterByPrefix(List.of("waterproof", "regular"), args[1]);
            }
            if (args.length >= 3) {
                return SubCommand.filterByPrefix(playerNames(), args[args.length - 1]);
            }
            return List.of();
        }
    }

    private static class GiveLocatorCommand implements SubCommand {
        @Override
        public String name() {
            return "give-locator";
        }

        @Override
        public String usage() {
            return "/gps itrak give-locator [радиус|long] [игрок]";
        }

        @Override
        public String permission() {
            return "gps.itrak.give";
        }

        @Override
        public void execute(Player player, String[] args) {
            HahGpsPlugin plugin = HahGpsPlugin.getInstance();
            int longRadius = plugin.getConfigManager().getItrakLocatorLongRadius();
            int radius = (int) plugin.getConfigManager().getItrakScanRadius();
            int index = 0;
            if (args.length >= 1) {
                Integer parsed = ItemFactory.parseRadiusToken(args[0], longRadius);
                if (parsed != null) {
                    radius = parsed;
                    index = 1;
                }
            }
            Player target = player;
            if (args.length > index) {
                Player named = Bukkit.getPlayerExact(args[index]);
                if (named == null) {
                    player.sendMessage(plugin.getMessages().getPrefixed("itrak-player-not-found",
                            "name", args[index]));
                    return;
                }
                target = named;
            }
            final Player receiver = target;
            final int finalRadius = ItemFactory.normalizeLocatorRadius(radius);
            ItemFactory.give(receiver, ItemFactory.locatorItem(finalRadius));
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-locator-given",
                    "player", receiver.getName(), "radius", String.valueOf(finalRadius)));
        }

        @Override
        public List<String> tabComplete(Player player, String[] args) {
            if (args.length == 1) {
                List<String> radiusTokens = new ArrayList<>(List.of("long"));
                radiusTokens.add(String.valueOf(
                        HahGpsPlugin.getInstance().getConfigManager().getItrakLocatorLongRadius()));
                radiusTokens.add(String.valueOf(
                        HahGpsPlugin.getInstance().getConfigManager().getItrakScanRadius()));
                List<String> names = new ArrayList<>(SubCommand.filterByPrefix(radiusTokens, args[0]));
                names.addAll(SubCommand.filterByPrefix(playerNames(), args[0]));
                return names;
            }
            if (args.length == 2) {
                return SubCommand.filterByPrefix(playerNames(), args[1]);
            }
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // Выдача предмета
    // ------------------------------------------------------------------

    private static void giveTracker(HahGpsPlugin plugin, Player sender, Player target,
                                    TrackerColor color, boolean waterproof, String label) {
        String code = ItemFactory.newCode();
        ItemFactory.TrackerSpec spec = new ItemFactory.TrackerSpec(code, label == null ? "" : label,
                color, waterproof);
        target.getInventory().addItem(ItemFactory.trackerItem(spec)).values()
                .forEach(drop -> target.getWorld().dropItemNaturally(target.getLocation(), drop));
        sender.sendMessage(plugin.getMessages().getPrefixed("itrak-given",
                "name", label == null || label.isBlank() ? code : label,
                "code", code,
                "color", plugin.getMessages().get(color.messageKey()),
                "player", target.getName()));
        if (!target.equals(sender)) {
            target.sendMessage(plugin.getMessages().getPrefixed("itrak-received", "code", code));
        }
    }

    /**
     * Выдача из ввода в чат (кнопка «Выдать трекер» в GUI): цвет, признак
     * водозащиты и необязательное имя через пробел.
     */
    public static void giveFromInput(HahGpsPlugin plugin, Player player, String input) {
        if (input == null || input.isBlank()) {
            player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage",
                    "/gps itrak give <цвет> [waterproof] [имя]"));
            return;
        }
        String[] parts = input.trim().split("\\s+");
        TrackerColor color = TrackerColor.parse(parts[0]);
        if (color == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-color-unknown",
                    "color", parts[0]));
            return;
        }
        boolean waterproof = false;
        int index = 1;
        if (index < parts.length && isWaterproofToken(parts[index])) {
            waterproof = true;
            index++;
        } else if (index < parts.length && isRegularToken(parts[index])) {
            index++;
        }
        String label = String.join(" ", java.util.Arrays.copyOfRange(parts, index, parts.length));
        giveTracker(plugin, player, player, color, waterproof, label.trim());
    }

    private static List<String> playerNames() {
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            names.add(online.getName());
        }
        return names;
    }

    private static boolean isWaterproofToken(String token) {
        String value = token.toLowerCase(Locale.ROOT);
        return value.equals("waterproof") || value.equals("wp") || value.equals("водонепроницаемый")
                || value.equals("водостойкий");
    }

    private static boolean isRegularToken(String token) {
        String value = token.toLowerCase(Locale.ROOT);
        return value.equals("regular") || value.equals("обычный") || value.equals("common");
    }

    private static String join(String[] args, int from, int to) {
        if (from >= to) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString().trim();
    }

    /** Коды, имена и номера всех трекеров — для tab-complete. */
    static List<String> trackerSuggestions(Player player, String prefix) {
        TrackerService service = HahGpsPlugin.getInstance().getTrackerService();
        if (service == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        boolean admin = player.hasPermission("gps.itrak.admin");
        for (Tracker tracker : admin ? service.all() : service.byOwner(player.getUniqueId())) {
            out.add(tracker.getCode());
            out.add(String.valueOf(tracker.getId()));
            if (tracker.getName() != null && !tracker.getName().isBlank()) {
                out.add(tracker.getName());
            }
        }
        return SubCommand.filterByPrefix(out, prefix);
    }
}
