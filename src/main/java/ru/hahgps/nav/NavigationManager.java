package ru.hahgps.nav;

import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import ru.hahgps.HahGPSPlugin;
import ru.hahgps.config.Messages;
import ru.hahgps.config.PluginConfig;
import ru.hahgps.hud.HudService;
import ru.hahgps.model.Graph;
import ru.hahgps.model.NavStep;
import ru.hahgps.model.Node;
import ru.hahgps.model.Route;
import ru.hahgps.model.TransportType;
import ru.hahgps.model.Vec;
import ru.hahgps.routing.RouteBuilder;
import ru.hahgps.util.Text;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NavigationManager {

    private final HahGPSPlugin plugin;
    private final PluginConfig config;
    private final Messages messages;
    private final Graph graph;
    private final RouteBuilder routeBuilder;
    private final HudService hud;

    private final Map<UUID, NavigationSession> sessions = new HashMap<>();
    private BukkitTask task;

    public NavigationManager(HahGPSPlugin plugin, PluginConfig config, Messages messages, Graph graph,
                             RouteBuilder routeBuilder, HudService hud) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.graph = graph;
        this.routeBuilder = routeBuilder;
        this.hud = hud;
    }

    public void start() {
        if (task != null) {
            task.cancel();
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, config.hudPeriodTicks());
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (NavigationSession session : sessions.values()) {
            hud.detach(session, plugin.getServer().getPlayer(session.playerId()));
        }
        sessions.clear();
    }

    public boolean isNavigating(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public NavigationSession session(UUID playerId) {
        return sessions.get(playerId);
    }

    public boolean start(Player player, Node destination, TransportType transport) {
        if (sessions.containsKey(player.getUniqueId())) {
            messages.send(player, "gps.already");
            return false;
        }
        Vec position = Vec.of(player.getLocation());
        Route route = routeBuilder.build(position, destination, transport);
        if (route == null) {
            messages.send(player, "general.unknown-node", "ref", destination == null ? "?" : destination.getId());
            return false;
        }

        NavigationSession session = new NavigationSession(player.getUniqueId(), destination.getId(), transport, route);
        sessions.put(player.getUniqueId(), session);
        hud.attach(session, player);

        sendPlan(player, session);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7F, 1.6F);
        return true;
    }

    public boolean stop(Player player, String reasonKey) {
        NavigationSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            messages.send(player, "gps.not-active");
            return false;
        }
        hud.detach(session, player);
        if (reasonKey != null) {
            messages.send(player, reasonKey);
        }
        return true;
    }

    public void forget(UUID playerId) {
        NavigationSession session = sessions.remove(playerId);
        if (session != null) {
            hud.detach(session, plugin.getServer().getPlayer(playerId));
        }
    }

    private void tick() {
        if (sessions.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, NavigationSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            NavigationSession session = iterator.next().getValue();
            Player player = plugin.getServer().getPlayer(session.playerId());
            if (player == null || !player.isOnline()) {
                hud.detach(session, null);
                iterator.remove();
                continue;
            }
            NavStep step = session.currentStep();
            if (step == null) {
                hud.detach(session, player);
                iterator.remove();
                continue;
            }

            Vec position = Vec.of(player.getLocation());
            boolean wrongWorld = step.world() != null && !step.world().equals(player.getWorld().getName());

            if (!wrongWorld) {
                double toTarget = step.distanceToTarget(position);
                if (toTarget <= config.arriveRadius(step.transport())) {
                    NavStep completed = step;
                    session.advance();
                    NavStep next = session.currentStep();
                    if (next == null) {
                        hud.detach(session, player);
                        iterator.remove();
                        finish(player, session);
                        continue;
                    }
                    messages.send(player, "gps.step-done", "step", completed.title());
                    messages.send(player, "gps.next-step", "step", next.title());
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6F, 1.6F);
                    step = next;
                } else if (step.points().size() > 1
                        && step.distanceFromPolyline(position) > config.recomputeDistance()
                        && now - session.lastRecomputeAt() >= config.recomputeCooldownMs()) {
                    session.setLastRecomputeAt(now);
                    recompute(player, session);
                    step = session.currentStep();
                    if (step == null) {
                        hud.detach(session, player);
                        iterator.remove();
                        continue;
                    }
                }
            }

            hud.update(session, player, new HudService.View(
                    step,
                    position,
                    wrongWorld ? session.route().totalDistance() : session.remainingDistance(position),
                    session.route().totalDistance(),
                    session.index(),
                    session.route().steps().size(),
                    wrongWorld,
                    step.world(),
                    session.route().destination().display()));
        }
    }

    private void recompute(Player player, NavigationSession session) {
        Node destination = graph.node(session.destinationId());
        if (destination == null) {
            messages.send(player, "gps.recompute-failed");
            return;
        }
        Route route = routeBuilder.build(Vec.of(player.getLocation()), destination, session.transport());
        if (route == null) {
            messages.send(player, "gps.recompute-failed");
            return;
        }
        session.setRoute(route);
        messages.send(player, "gps.recomputed");
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5F, 1.2F);
    }

    private void finish(Player player, NavigationSession session) {
        Node destination = graph.node(session.destinationId());
        String destinationName = destination == null ? session.destinationId() : destination.display();
        messages.send(player, "gps.arrived", "dest", destinationName);
        player.showTitle(Title.title(
                messages.get("gps.arrived-title"),
                messages.get("gps.arrived-subtitle", "dest", destinationName),
                Title.Times.times(Duration.ofMillis(200L), Duration.ofSeconds(2L), Duration.ofMillis(500L))));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8F, 1.4F);
    }

    private void sendPlan(Player player, NavigationSession session) {
        Route route = session.route();
        Node destination = route.destination();
        double seconds = 0.0D;
        for (NavStep step : route.steps()) {
            seconds += step.length() / Math.max(0.1D, config.speed(step.transport()));
        }
        messages.send(player, "gps.started",
                "dest", destination.display(),
                "transport", route.transport().displayName());
        if (route.noteKey() != null) {
            messages.send(player, route.noteKey(), "transport", session.transport().displayName());
        }
        messages.sendRaw(player, "gps.route-header",
                "dest", destination.display(),
                "transport", route.transport().icon() + " " + route.transport().displayName(),
                "distance", Text.distance(route.totalDistance()),
                "time", Text.duration(seconds));

        List<NavStep> steps = route.steps();
        for (int i = 0; i < steps.size(); i++) {
            NavStep step = steps.get(i);
            messages.sendRaw(player, "gps.plan-entry",
                    "index", String.valueOf(i + 1),
                    "icon", step.transport().icon(),
                    "title", step.title(),
                    "detail", step.detail());
        }
    }
}
