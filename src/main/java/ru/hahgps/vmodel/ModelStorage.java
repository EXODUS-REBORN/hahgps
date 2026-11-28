package ru.hahgps.vmodel;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ModelStorage {

    private final Map<UUID, PlacedModel> byId = new ConcurrentHashMap<>();

    public int size() {
        return byId.size();
    }

    public void replaceAll(List<PlacedModel> rows) {
        Map<UUID, PlacedModel> next = new ConcurrentHashMap<>();
        if (rows != null) {
            for (PlacedModel row : rows) {
                if (row != null && row.id() != null && row.world() != null) {
                    next.put(row.id(), row);
                }
            }
        }
        byId.clear();
        byId.putAll(next);
    }

    public void put(PlacedModel row) {
        if (row != null && row.id() != null) {
            byId.put(row.id(), row);
        }
    }

    public void merge(List<PlacedModel> rows) {
        if (rows == null) return;
        for (PlacedModel row : rows) {
            put(row);
        }
    }

    public PlacedModel remove(UUID id) {
        return id == null ? null : byId.remove(id);
    }

    public PlacedModel get(UUID id) {
        return id == null ? null : byId.get(id);
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    public void clear() {
        byId.clear();
    }

    public List<PlacedModel> all() {
        return List.copyOf(byId.values());
    }

    public List<PlacedModel> inWorld(String world) {
        List<PlacedModel> out = new ArrayList<>();
        if (world == null) return out;
        for (PlacedModel row : byId.values()) {
            if (world.equalsIgnoreCase(row.world())) {
                out.add(row);
            }
        }
        return out;
    }

    public List<PlacedModel> inWorld(World world) {
        return world == null ? List.of() : inWorld(world.getName());
    }

    public Map<UUID, PlacedModel> snapshot() {
        return new LinkedHashMap<>(byId);
    }

    public static Map<String, Object> toJson(PlacedModel row, String playerName) {
        return Json.map(
                "action", "place",
                "id", row.id().toString(),
                "world", row.world(),
                "x", row.x(),
                "y", row.y(),
                "z", row.z(),
                "yaw", row.yaw(),
                "pitch", row.pitch(),
                "model_id", row.modelKey(),
                "placed_by", playerName == null ? "" : playerName);
    }

    public static Map<String, Object> removeJson(UUID id) {
        return Json.map("action", "remove", "id", id.toString());
    }

    public boolean worldExists(String world) {
        return world != null && Bukkit.getWorld(world) != null;
    }
}
