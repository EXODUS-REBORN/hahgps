package ru.hahgps.vmodel;

import ru.hahgps.HahGpsPlugin;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ModelScheduler {

    private final ModelCatalog catalog;
    private final ModelStorage storage;
    private final Map<UUID, Integer> radius = new HashMap<>();
    private final Map<UUID, Set<UUID>> shown = new HashMap<>();

    public ModelScheduler(ModelCatalog catalog, ModelStorage storage) {
        this.catalog = catalog;
        this.storage = storage;
    }

    public void setRadius(UUID playerId, int blocks) {
        if (playerId == null) return;
        radius.put(playerId, Math.max(4, Math.min(128, blocks)));
    }

    public int radiusOf(UUID playerId) {
        return radius.getOrDefault(playerId, 0);
    }

    public void forget(UUID playerId) {
        shown.remove(playerId);
    }

    public int viewerCount() {
        return shown.size();
    }

    public void hideAll(Player player) {
        if (player == null) return;
        Set<UUID> view = shown.remove(player.getUniqueId());
        if (view == null) {
            return;
        }
        for (UUID placedId : view) {
            PlacedModel placed = storage.get(placedId);
            if (placed != null) {
                ModelPackets.hideFrom(player, placed);
            }
        }
    }

    public void refreshPlayer(Player player) {
        hideAll(player);
        tickPlayer(player);
    }

    public void tickAll() {
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            tickPlayer(player);
        }
    }

    public void tickPlayer(Player player) {
        if (player == null || player.getWorld() == null) return;
        int range = radiusOf(player.getUniqueId());
        if (range <= 0) {
            hideAll(player);
            return;
        }
        double rangeSq = (double) range * range;
        Set<UUID> view = shown.computeIfAbsent(player.getUniqueId(), ignored -> new HashSet<>());
        Set<UUID> keep = new HashSet<>();
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        for (PlacedModel placed : storage.inWorld(player.getWorld())) {
            if (placed.distanceSquared(px, py, pz) > rangeSq) {
                continue;
            }
            ModelDefinition def = catalog.get(placed.modelKey());
            if (def == null) {
                continue;
            }
            keep.add(placed.id());
            ModelPackets.spawn(player, placed, def);
        }
        for (UUID id : new ArrayList<>(view)) {
            if (keep.contains(id)) continue;
            view.remove(id);
            PlacedModel placed = storage.get(id);
            if (placed != null) {
                ModelPackets.hideFrom(player, placed);
            }
        }
        view.addAll(keep);
    }

    public void refreshKey(UUID placedId) {
        if (placedId == null) return;
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            Set<UUID> view = shown.get(player.getUniqueId());
            if (view == null || !view.contains(placedId)) continue;
            view.remove(placedId);
            PlacedModel placed = storage.get(placedId);
            if (placed != null) {
                ModelPackets.hideFrom(player, placed);
            }
        }
    }

    public Map<UUID, Integer> snapshotRadius() {
        return new HashMap<>(radius);
    }

    public void loadRadius(Map<String, Integer> data) {
        radius.clear();
        if (data == null) return;
        for (Map.Entry<String, Integer> entry : data.entrySet()) {
            try {
                UUID id = UUID.fromString(entry.getKey());
                Integer value = entry.getValue();
                radius.put(id, Math.max(4, Math.min(128, value == null ? 0 : value)));
            } catch (RuntimeException ignored) {
                HahGpsPlugin host = HahGpsPlugin.getInstance();
                if (host != null) {
                    host.getLogger().log(java.util.logging.Level.FINE,
                            "HahGps: модели — радиус игрока не разобран", ignored);
                }
            }
        }
    }

    public Location origin(PlacedModel placed) {
        return ModelPackets.locationOf(placed);
    }
}
