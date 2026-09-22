package ru.hahgps.visual;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import ru.hahgps.HahGPSPlugin;
import ru.hahgps.config.Messages;
import ru.hahgps.config.PluginConfig;
import ru.hahgps.model.Edge;
import ru.hahgps.model.Graph;
import ru.hahgps.model.Node;
import ru.hahgps.model.TransportType;
import ru.hahgps.model.Vec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class VisualizationService {

    private static final int PARTICLE_BUDGET = 900;

    private final HahGPSPlugin plugin;
    private final PluginConfig config;
    private final Messages messages;
    private final Graph graph;

    private final Map<UUID, EnumSet<TransportType>> viewers = new HashMap<>();
    private final Map<String, TextDisplay> labels = new HashMap<>();
    private final Map<UUID, Set<String>> shownLabels = new HashMap<>();
    private BukkitTask task;

    public VisualizationService(HahGPSPlugin plugin, PluginConfig config, Messages messages, Graph graph) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.graph = graph;
    }

    public void start() {
        if (task != null) {
            task.cancel();
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, config.visualPeriodTicks());
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (TextDisplay display : labels.values()) {
            if (display != null && display.isValid()) {
                display.remove();
            }
        }
        labels.clear();
        shownLabels.clear();
        viewers.clear();
    }

    public EnumSet<TransportType> selected(Player player) {
        return viewers.getOrDefault(player.getUniqueId(), EnumSet.noneOf(TransportType.class));
    }

    public boolean toggle(Player player, TransportType type) {
        EnumSet<TransportType> set = viewers.computeIfAbsent(player.getUniqueId(),
                key -> EnumSet.noneOf(TransportType.class));
        boolean enabled;
        if (set.contains(type)) {
            set.remove(type);
            enabled = false;
        } else {
            set.add(type);
            enabled = true;
        }
        if (set.isEmpty()) {
            hideAll(player);
            viewers.remove(player.getUniqueId());
        }
        return enabled;
    }

    public boolean toggleAll(Player player) {
        EnumSet<TransportType> set = viewers.get(player.getUniqueId());
        if (set != null && !set.isEmpty()) {
            hideAll(player);
            viewers.remove(player.getUniqueId());
            return false;
        }
        viewers.put(player.getUniqueId(), EnumSet.allOf(TransportType.class));
        return true;
    }

    public void clear(Player player) {
        viewers.remove(player.getUniqueId());
        hideAll(player);
    }

    public void forget(Player player) {
        viewers.remove(player.getUniqueId());
        shownLabels.remove(player.getUniqueId());
    }

    public boolean isActive(Player player) {
        EnumSet<TransportType> set = viewers.get(player.getUniqueId());
        return set != null && !set.isEmpty();
    }

    private void tick() {
        if (viewers.isEmpty()) {
            if (!labels.isEmpty()) {
                removeLabels();
            }
            return;
        }
        viewers.keySet().removeIf(id -> {
            Player player = plugin.getServer().getPlayer(id);
            return player == null || !player.isOnline();
        });
        if (viewers.isEmpty()) {
            removeLabels();
            return;
        }

        for (Map.Entry<UUID, EnumSet<TransportType>> entry : viewers.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }
            render(player, entry.getValue());
        }

        Set<String> inUse = new HashSet<>();
        for (Set<String> set : shownLabels.values()) {
            inUse.addAll(set);
        }
        labels.entrySet().removeIf(entry -> {
            if (inUse.contains(entry.getKey())) {
                return false;
            }
            TextDisplay display = entry.getValue();
            if (display != null && display.isValid()) {
                display.remove();
            }
            return true;
        });
    }

    private void render(Player player, EnumSet<TransportType> selected) {
        if (selected.isEmpty()) {
            return;
        }
        Vec center = Vec.of(player.getLocation());
        int budget = PARTICLE_BUDGET;
        Map<String, Node> visible = new LinkedHashMap<>();

        for (Edge edge : graph.edges()) {
            if (!edge.allowsAny(selected)) {
                continue;
            }
            Node from = graph.node(edge.getFrom());
            Node to = graph.node(edge.getTo());
            if (from == null || to == null || !from.vec().sameWorld(center)) {
                continue;
            }
            double distance = Math.min(from.vec().distanceTo(center), to.vec().distanceTo(center));
            if (distance > config.visualViewDistance()) {
                continue;
            }
            TransportType type = primaryType(edge, selected);
            if (type == null) {
                continue;
            }
            budget -= drawLine(player, from.vec(), to.vec(), type, budget);
            if (budget <= 0) {
                break;
            }
            visible.putIfAbsent(from.getId(), from);
            visible.putIfAbsent(to.getId(), to);
        }

        if (!config.visualLabels() || config.visualMaxLabels() <= 0) {
            hideAll(player);
            return;
        }

        List<Node> sorted = new ArrayList<>(visible.values());
        sorted.sort(Comparator.comparingDouble(node -> node.vec().distanceTo(center)));
        if (sorted.size() > config.visualMaxLabels()) {
            sorted = sorted.subList(0, config.visualMaxLabels());
        }

        Set<String> wanted = new HashSet<>();
        Set<String> current = shownLabels.getOrDefault(player.getUniqueId(), Set.of());
        for (Node node : sorted) {
            TextDisplay display = ensureLabel(node);
            if (display == null) {
                continue;
            }
            wanted.add(node.getId());
            if (!current.contains(node.getId())) {
                player.showEntity(plugin, display);
            }
        }
        for (String id : current) {
            if (wanted.contains(id)) {
                continue;
            }
            TextDisplay display = labels.get(id);
            if (display != null && display.isValid()) {
                player.hideEntity(plugin, display);
            }
        }
        shownLabels.put(player.getUniqueId(), wanted);
    }

    private TransportType primaryType(Edge edge, EnumSet<TransportType> selected) {
        for (TransportType type : TransportType.values()) {
            if (selected.contains(type) && edge.allows(type)) {
                return type;
            }
        }
        return null;
    }

    private int drawLine(Player player, Vec from, Vec to, TransportType type, int budget) {
        Location origin = from.toLocation();
        if (origin == null || budget <= 0) {
            return 0;
        }
        double length = from.distanceTo(to);
        if (length <= 0.01D) {
            return 0;
        }
        int points = (int) Math.ceil(length / config.visualParticleStep());
        points = Math.max(2, Math.min(points, budget));
        double yOffset = type == TransportType.AIR ? config.visualAirOffset() : 0.0D;
        World world = origin.getWorld();
        int drawn = 0;

        for (int i = 0; i <= points; i++) {
            double t = (double) i / points;
            double x = from.x() + (to.x() - from.x()) * t;
            double y = from.y() + (to.y() - from.y()) * t + yOffset;
            double z = from.z() + (to.z() - from.z()) * t;
            if (!world.isChunkLoaded(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4)) {
                continue;
            }
            Location location = new Location(world, x, y, z);
            player.spawnParticle(Particle.DUST, location, 1, 0.0D, 0.0D, 0.0D, 0.0D,
                    new Particle.DustOptions(type.color(), config.visualParticleSize()));
            drawn++;
        }
        return drawn;
    }

    private TextDisplay ensureLabel(Node node) {
        TextDisplay existing = labels.get(node.getId());
        if (existing != null && existing.isValid()) {
            return existing;
        }
        Location location = node.location();
        if (location == null) {
            return null;
        }
        Location spawn = location.clone().add(0.0D, config.visualLabelHeight(), 0.0D);
        World world = spawn.getWorld();
        if (!world.isChunkLoaded(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4)) {
            return null;
        }
        try {
            TextDisplay display = world.spawn(spawn, TextDisplay.class, entity -> {
                entity.text(labelComponent(node));
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setPersistent(false);
                entity.setVisibleByDefault(false);
                entity.setViewRange(1.0F);
                entity.setSeeThrough(false);
                entity.setShadowed(true);
            });
            labels.put(node.getId(), display);
            return display;
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Не удалось создать подпись узла " + node.getId() + ": " + throwable.getMessage());
            return null;
        }
    }

    private Component labelComponent(Node node) {
        String name = node.getName();
        if (name == null || name.isBlank()) {
            return messages.get("admin.label-no-name",
                    "id", node.getId(),
                    "transport", TransportType.join(node.allowedTypes()));
        }
        return messages.get("admin.label",
                "id", node.getId(),
                "transport", TransportType.join(node.allowedTypes()),
                "name", name);
    }

    private void hideAll(Player player) {
        Set<String> current = shownLabels.remove(player.getUniqueId());
        if (current == null) {
            return;
        }
        for (String id : current) {
            TextDisplay display = labels.get(id);
            if (display != null && display.isValid()) {
                player.hideEntity(plugin, display);
            }
        }
    }

    private void removeLabels() {
        for (TextDisplay display : labels.values()) {
            if (display != null && display.isValid()) {
                display.remove();
            }
        }
        labels.clear();
        shownLabels.clear();
    }
}
