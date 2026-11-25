package ru.hahgps.service;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.config.ConfigManager;
import ru.hahgps.config.MessagesManager;
import ru.hahgps.model.NavigationSession;
import ru.hahgps.model.Tracker;
import ru.hahgps.model.TransportType;
import ru.hahgps.util.LocationUtil;
import ru.hahgps.util.RouteArrow;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NavigationService {

    private final HahGpsPlugin plugin;
    private final ConfigManager config;
    private final MessagesManager messages;

    private final Map<UUID, NavigationSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private volatile org.bukkit.scheduler.BukkitTask updateTask;

    public NavigationService(HahGpsPlugin plugin, ConfigManager config, MessagesManager messages) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
    }

    public synchronized void start() {

        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        int interval = Math.max(10, config.getUpdateInterval());
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, interval, interval);
    }

    public synchronized void stop() {
        org.bukkit.scheduler.BukkitTask task = updateTask;
        updateTask = null;
        if (task != null) {
            task.cancel();
        }
        HudService hud = plugin.getHudService();
        for (UUID id : sessions.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline() && hud != null) {
                hud.clearHud(player);
            }
        }
        for (Map.Entry<UUID, BossBar> entry : bossBars.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                player.hideBossBar(entry.getValue());
            }
        }
        bossBars.clear();
        sessions.clear();
    }

    public void startNavigation(Player player, String targetName, Location target,
                                java.util.List<Location> path, double totalDistance,
                                TransportType transport) {
        start(player, targetName, target, path, totalDistance, transport, null);
    }

    /**
     * Маршрут к трекеру iTrak: цель живая. Пока трекер движется, тик
     * подменяет точку, а путь перестраивается не чаще, чем разрешено
     * настройками и только если трекер ушёл достаточно далеко.
     */
    public void startTrackerNavigation(Player player, Tracker tracker, Location target,
                                       java.util.List<Location> path, double totalDistance,
                                       TransportType transport) {
        if (tracker == null) {
            return;
        }
        start(player, tracker.getDisplayName(), target, path, totalDistance, transport, tracker.getId());
    }

    private void start(Player player, String targetName, Location target,
                       java.util.List<Location> path, double totalDistance,
                       TransportType transport, Integer trackerId) {
        stopNavigation(player);
        NavigationSession session = new NavigationSession(player.getUniqueId(), targetName, target,
                path, routeLengthOrDefault(path, totalDistance), transport, trackerId);
        session.setLoggingEnabled(config.isLogHints());
        if (trackerId != null) {
            // Путь к трекеру уже построен командой: не перестраиваем его
            // сразу же на первом тике.
            session.setLastPathRebuild(System.currentTimeMillis());
            session.setLastRebuildOrigin(target == null ? null : target.clone());
        }
        sessions.put(player.getUniqueId(), session);

        BossBar.Color color = parseColor(config.getBossbarColor());
        BossBar.Overlay style = parseStyle(config.getBossbarStyle());
        BossBar bar = BossBar.bossBar(Component.text("GPS"), 1.0f, color, style);
        player.showBossBar(bar);
        bossBars.put(player.getUniqueId(), bar);

        // Гарантируем, что по центру экрана ничего не висит от прошлой навигации
        player.sendActionBar(Component.empty());
        HudService previousHud = plugin.getHudService();
        if (previousHud != null) {
            previousHud.clearHud(player);
        }
    }


    private static double routeLengthOrDefault(java.util.List<Location> path, double fallback) {
        if (path != null && path.size() >= 2) {
            double length = 0.0;
            Location prev = path.get(0);
            for (int i = 1; i < path.size(); i++) {
                Location curr = path.get(i);
                double d = LocationUtil.distance(prev, curr);
                if (d > 0 && d != Double.MAX_VALUE) {
                    length += d;
                }
                prev = curr;
            }
            if (length > 0) {
                return length;
            }
        }
        return fallback > 0 && Double.isFinite(fallback) ? fallback : 0.0;
    }

    public void stopNavigation(Player player) {
        sessions.remove(player.getUniqueId());
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
        player.sendActionBar(Component.empty());
        HudService hud = plugin.getHudService();
        if (hud != null) {
            hud.clearHud(player);
        }
    }

    public boolean isNavigating(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public NavigationSession getSession(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public boolean toggleLogging(Player player) {
        NavigationSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.setLoggingEnabled(!session.isLoggingEnabled());
            return session.isLoggingEnabled();
        }
        return false;
    }

    private void updateAll() {
        Iterator<Map.Entry<UUID, NavigationSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, NavigationSession> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                // Игрок вышел: боссбар у него уже не показывается, скрывать нечего.
                bossBars.remove(entry.getKey());
                it.remove();
                continue;
            }
            try {
                updateSession(player, entry.getValue());
            } catch (Exception ex) {

                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "HahGps: не удалось обновить навигацию для " + player.getName(), ex);
            }
        }
    }

    private void updateSession(Player player, NavigationSession session) {
        updateLiveTarget(player, session);
        Location target = session.getTarget();
        Location playerLoc = player.getLocation();
        String targetName = session.getTargetName() == null ? "?"
                : ru.hahgps.gui.GuiTheme.clipPlain(session.getTargetName(), ru.hahgps.gui.GuiTheme.NAME_MAX);

        // Указатель живёт ТОЛЬКО в боссбаре вверху экрана. ActionBar (центр/низ
        // экрана) чистится один раз при старте и при остановке — каждый тик
        // его трогать незачем, это ещё и затирает чужие плагины.

        if (target == null || target.getWorld() == null || playerLoc.getWorld() == null
                || !LocationUtil.sameWorld(playerLoc.getWorld(), target.getWorld())) {
            BossBar crossWorldBar = bossBars.get(player.getUniqueId());
            if (crossWorldBar != null) {
                crossWorldBar.name(title(Component.text("⚠"), targetName, ""));
            }
            // HUD и последнее расстояние из прошлого мира убираем: иначе
            // боссбар говорит «другой мир», а HUD продолжает показывать старое.
            HudService crossWorldHud = plugin.getHudService();
            if (crossWorldHud != null) {
                crossWorldHud.clearHud(player);
            }
            return;
        }

        double distanceToTarget = LocationUtil.distance(playerLoc, target);
        if (distanceToTarget <= config.getArrivalRadius()) {
            player.sendMessage(messages.getPrefixed("arrival"));
            stopNavigation(player);
            return;
        }

        session.setRemainingDistance(distanceToTarget);

        double angleToTarget = LocationUtil.angleTo(playerLoc, target);
        double facing = LocationUtil.normalizeAngle(-playerLoc.getYaw());
        double delta = LocationUtil.normalizeAngle(angleToTarget - facing);

        RouteArrow arrow = config.isShowArrow() ? RouteArrow.of(delta) : null;

        BossBar bar = bossBars.get(player.getUniqueId());
        if (bar != null) {
            // Отправляем пакет только если строка/полоса действительно
            // изменились: стоящий на месте игрок получал их дважды в секунду.
            String distanceText = LocationUtil.formatDistance(distanceToTarget);
            Component nextTitle = title(arrow == null ? null : Component.text(arrow.unicode()),
                    targetName, distanceText);
            // Ключ сравнения: направление, цель, расстояние.
            String nextKey = (arrow == null ? "-" : arrow.key())
                    + "|" + targetName + "|" + distanceText;
            float progress = session.getTotalDistance() > 0
                    ? (float) Math.max(0.0, Math.min(1.0, distanceToTarget / session.getTotalDistance()))
                    : 0.0f;
            progress = Math.round(progress * 100.0f) / 100.0f;
            if (!nextKey.equals(session.getLastBarKey()) || progress != session.getLastBarProgress()) {
                session.setLastBarKey(nextKey);
                session.setLastBarProgress(progress);
                bar.name(nextTitle);
                bar.progress(progress);
            }
        }

        HudService hud = plugin.getHudService();
        if (hud != null && config.isHudEnabled()) {
            hud.updateHud(player, session.getTransport(), targetName, distanceToTarget);
        }

        if (session.isLoggingEnabled()) {
            RouteArrow hintArrow = RouteArrow.of(delta);
            String hintKey = hintArrow.hintKey();
            if (!hintKey.equals(session.getLastHint())) {
                session.setLastHint(hintKey);
                player.sendMessage(hintMessage(player, hintArrow, hintKey));
            }
        }
    }

    /**
     * Подсказка в чате: префикс, стрелка направления и текст.
     *
     * Стрелка — та же ванильная, что и в боссбаре, поэтому в чате и наверху
     * экрана направление выглядит одинаково. В messages_*.yml стрелок больше
     * нет — иначе они дублировались бы.
     */
    private Component hintMessage(Player player, RouteArrow arrow, String hintKey) {
        Component prefix = messages.getComponent("prefix");
        Component icon = Component.text(arrow.unicode());
        return prefix.append(icon)
                .append(Component.text(" "))
                .append(messages.getComponent(hintKey));
    }

    /**
     * Живая цель трекера: координаты берутся у сущности прямо сейчас, а путь
     * перестраивается асинхронно, чтобы A* не тормозил главный поток.
     */
    private void updateLiveTarget(Player player, NavigationSession session) {
        Integer trackerId = session.getTrackerId();
        if (trackerId == null) {
            return;
        }
        TrackerService trackerService = plugin.getTrackerService();
        if (trackerService == null) {
            return;
        }
        Tracker tracker = trackerService.byId(trackerId);
        if (tracker == null) {
            return;
        }
        Location live = trackerService.liveLocation(tracker);
        if (live != null && live.getWorld() != null) {
            // Вне зоны связи цель — район, а не точные координаты: раньше тик
            // каждый раз подставлял сюда live и обнулял дальность сигнала.
            Location goal = live;
            TrackerService.Signal signal = trackerService.signal(player, tracker);
            if (signal != null && !signal.inRange()) {
                Location region = trackerService.targetLocation(player, tracker);
                if (region != null) {
                    goal = region;
                }
            }
            if (goal.getWorld() != null) {
                session.setTarget(goal);
            }
        }
        Location current = session.getTarget();
        if (current == null || session.isRebuildInFlight()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - session.getLastPathRebuild() < config.getItrakLiveRouteRebuildMillis()) {
            return;
        }
        Location origin = session.getLastRebuildOrigin();
        double moved = origin == null ? Double.MAX_VALUE : LocationUtil.distance(origin, current);
        if (moved < config.getItrakLiveRouteRebuildBlocks()) {
            return;
        }
        RouteService routeService = plugin.getRouteService();
        if (routeService == null) {
            return;
        }
        session.setRebuildInFlight(true);
        session.setLastPathRebuild(now);
        session.setLastRebuildOrigin(current.clone());
        routeService.buildMultiModalAsync(player, current.clone(), session.getTransport())
                .whenComplete((path, error) -> plugin.getDataService().runSync(() -> {
                    session.setRebuildInFlight(false);
                    if (!player.isOnline() || plugin.getNavigationService().getSession(player) != session) {
                        return;
                    }
                    if (error == null && path != null && !path.isEmpty()) {
                        session.setPath(path);
                        session.setTotalDistance(routeLengthOrDefault(path, session.getTotalDistance()));
                    }
                }));
    }

    /**
     * Строка боссбара — единственное место, где показывается направление:
     * {@code стрелка + цель + расстояние}. Порядок именно такой: стрелка
     * первая, чтобы она читалась сразу.
     *
     * Стрелка — ванильный символ BMP. Эмодзи-иконки транспорта
     * (👣🚗🚌🚁🚢) не используются: они вне BMP, и шрифт Minecraft рисует
     * вместо них пустой квадрат.
     */
    private Component title(Component lead, String targetName, String distance) {
        Component out = Component.empty();
        if (lead != null) {
            out = out.append(lead).append(Component.text(" "));
        }
        if (targetName != null && !targetName.isEmpty()) {
            out = out.append(Component.text(targetName));
        }
        if (distance != null && !distance.isEmpty()) {
            out = out.append(Component.text(" " + distance));
        }
        return out;
    }

    private BossBar.Color parseColor(String name) {
        if (name == null) {
            return BossBar.Color.BLUE;
        }
        try {
            return BossBar.Color.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Color.BLUE;
        }
    }

    private BossBar.Overlay parseStyle(String name) {
        if (name == null) {
            return BossBar.Overlay.PROGRESS;
        }
        return switch (name.toUpperCase(java.util.Locale.ROOT)) {
            case "SEGMENTED_6" -> BossBar.Overlay.NOTCHED_6;
            case "SEGMENTED_10" -> BossBar.Overlay.NOTCHED_10;
            case "SEGMENTED_12" -> BossBar.Overlay.NOTCHED_12;
            case "SEGMENTED_20" -> BossBar.Overlay.NOTCHED_20;
            default -> BossBar.Overlay.PROGRESS;
        };
    }
}
