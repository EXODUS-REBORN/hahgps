package ru.hahgps.service;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final Map<UUID, NavigationSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private org.bukkit.scheduler.BukkitTask updateTask;

    public NavigationService(HahGpsPlugin plugin, ConfigManager config, MessagesManager messages) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
    }

    public void start() {
        int interval = config.getUpdateInterval();
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, interval, interval);
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
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
                path, totalDistance, transport);
        session.setLoggingEnabled(config.isLogHints());
        sessions.put(player.getUniqueId(), session);

        BarColor color = parseColor(config.getBossbarColor());
        BarStyle style = parseStyle(config.getBossbarStyle());
        BossBar bar = Bukkit.createBossBar("GPS", color, style);
        bar.setProgress(1.0f);
        bar.addPlayer(player);
        bossBars.put(player.getUniqueId(), bar);
    }

    public void stopNavigation(Player player) {
        sessions.remove(player.getUniqueId());
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.removePlayer(player);
        }
        player.sendActionBar(Component.empty());
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
            updateSession(player, entry.getValue());
        }
    }

    private void updateSession(Player player, NavigationSession session) {
        Location target = session.getTarget();
        Location playerLoc = player.getLocation();

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
        String transportIcon = getTransportIcon(session.getTransport());

        BossBar bar = bossBars.get(player.getUniqueId());
        if (bar != null) {
            Component title = miniMessage.deserialize(
                    "<#8fc6ff>" + transportIcon + " <#e8f4ff>" + session.getTargetName()
                            + " <#c8e4ff>" + arrow + " <#b8dcff>"
                            + LocationUtil.formatDistance(distanceToTarget));
            bar.setTitle(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(title));
            float progress = session.getTotalDistance() > 0
                    ? (float) Math.max(0.0, Math.min(1.0, distanceToTarget / session.getTotalDistance()))
                    : 0.0f;
            bar.setProgress(progress);
        }

        Component hotbarText = miniMessage.deserialize(
                "<#c8e4ff>" + transportIcon + " <#8fc6ff>" + session.getTargetName()
                        + " <#b8dcff>| <#e8f4ff>"
                        + LocationUtil.formatDistance(distanceToTarget)
                        + " <#c8e4ff>| <#66aaff>"
                        + (int) distanceToTarget + " <#8fc6ff>m");
        player.sendActionBar(hotbarText);

        if (session.isLoggingEnabled()) {
            String hintKey = DirectionUtil.getHintKey(delta);
            if (!hintKey.equals(session.getLastHint())) {
                session.setLastHint(hintKey);
                player.sendMessage(messages.getPrefixed(hintKey));
            }
        }
    }

    private String getTransportIcon(TransportType type) {
        return switch (type) {
            case FOOT -> "👣";
            case CAR -> "🚗";
            case TRANSIT -> "🚌";
            case AIR -> "🚁";
            case WATER -> "🚢";
        };
    }

    private BarColor parseColor(String name) {
        try {
            return BarColor.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BarColor.BLUE;
        }
    }

    private BarStyle parseStyle(String name) {
        try {
            return BarStyle.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BarStyle.SOLID;
        }
    }
}
