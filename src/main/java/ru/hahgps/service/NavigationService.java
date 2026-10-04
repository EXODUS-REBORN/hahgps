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
        int interval = Math.max(1, config.getUpdateInterval());
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, interval, interval);
    }

    public void stop() {
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
        stopNavigation(player);
        NavigationSession session = new NavigationSession(player.getUniqueId(), targetName, target,
                path, routeLengthOrDefault(path, totalDistance), transport);
        session.setLoggingEnabled(config.isLogHints());
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
        Location target = session.getTarget();
        Location playerLoc = player.getLocation();
        String transportIcon = getTransportIcon(session.getTransport());
        String targetName = session.getTargetName() == null ? "?"
                : ru.hahgps.gui.GuiTheme.clipPlain(session.getTargetName(), ru.hahgps.gui.GuiTheme.NAME_MAX);

        // Указатель живёт ТОЛЬКО в боссбаре вверху экрана. ActionBar (центр/низ
        // экрана) чистится один раз при старте и при остановке — каждый тик
        // его трогать незачем, это ещё и затирает чужие плагины.

        if (target == null || target.getWorld() == null || playerLoc.getWorld() == null
                || !playerLoc.getWorld().equals(target.getWorld())) {
            BossBar crossWorldBar = bossBars.get(player.getUniqueId());
            if (crossWorldBar != null) {
                crossWorldBar.setTitle(title("⚠", transportIcon, targetName, ""));
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
            bar.setTitle(title(arrow, transportIcon, targetName,
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

    private String getTransportIcon(TransportType type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case FOOT -> "👣";
            case CAR -> "🚗";
            case TRANSIT -> "🚌";
            case AIR -> "🚁";
            case WATER -> "🚢";
        };
    }

    /**
     * Строка боссбара — единственное место, где показывается направление:
     * {@code стрелка + транспорт + цель + расстояние}.
     * Порядок именно такой: стрелка первая, чтобы она читалась сразу.
     */
    private String title(String arrow, String transportIcon, String targetName, String distance) {
        StringBuilder sb = new StringBuilder(48);
        if (arrow != null && !arrow.isEmpty()) sb.append(arrow).append(' ');
        if (transportIcon != null && !transportIcon.isEmpty()) sb.append(transportIcon).append(' ');
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
