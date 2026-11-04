package ru.hahgps.service;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.config.ConfigManager;
import ru.hahgps.config.MessagesManager;
import ru.hahgps.model.NavigationSession;
import ru.hahgps.model.Tracker;
import ru.hahgps.model.TransportType;
import ru.hahgps.util.DirectionUtil;
import ru.hahgps.util.LocationUtil;
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
        for (BossBar bar : bossBars.values()) {
            bar.removeAll();
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

        BarColor color = parseColor(config.getBossbarColor());
        BarStyle style = parseStyle(config.getBossbarStyle());
        BossBar bar = Bukkit.createBossBar("GPS", color, style);
        bar.setProgress(1.0f);
        bar.addPlayer(player);
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
            bar.removePlayer(player);
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
                BossBar bar = bossBars.remove(entry.getKey());
                if (bar != null) {
                    bar.removeAll();
                }
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
                crossWorldBar.setTitle(title("⚠", targetName, ""));
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

        String arrow = config.isShowArrow() ? DirectionUtil.getArrow(delta) : "";

        BossBar bar = bossBars.get(player.getUniqueId());
        if (bar != null) {
            bar.setTitle(title(arrow, targetName,
                    LocationUtil.formatDistance(distanceToTarget)));
            float progress = session.getTotalDistance() > 0
                    ? (float) Math.max(0.0, Math.min(1.0, distanceToTarget / session.getTotalDistance()))
                    : 0.0f;
            bar.setProgress(progress);
        }

        HudService hud = plugin.getHudService();
        if (hud != null && config.isHudEnabled()) {
            hud.updateHud(player, session.getTransport(), targetName, distanceToTarget);
        }

        if (session.isLoggingEnabled()) {
            String hintKey = DirectionUtil.getHintKey(delta);
            if (!hintKey.equals(session.getLastHint())) {
                session.setLastHint(hintKey);
                player.sendMessage(messages.getPrefixed(hintKey));
            }
        }
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
            session.setTarget(live);
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
     * Эмодзи-иконки транспорта (👣🚗🚌🚁🚢) отсюда убраны: они лежат за
     * пределами BMP, а шрифт Minecraft их не содержит — на клиенте вместо
     * иконки рисовался пустой квадрат. Остались только глифы из BMP
     * (стрелки, ⚠).
     */
    private String title(String arrow, String targetName, String distance) {
        StringBuilder sb = new StringBuilder(48);
        if (arrow != null && !arrow.isEmpty()) sb.append(arrow).append(' ');
        if (targetName != null && !targetName.isEmpty()) sb.append(targetName);
        if (distance != null && !distance.isEmpty()) sb.append(' ').append(distance);
        return sb.toString().trim();
    }

    private BarColor parseColor(String name) {
        if (name == null) {
            return BarColor.BLUE;
        }
        try {
            return BarColor.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BarColor.BLUE;
        }
    }

    private BarStyle parseStyle(String name) {
        if (name == null) {
            return BarStyle.SOLID;
        }
        try {
            return BarStyle.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BarStyle.SOLID;
        }
    }
}
