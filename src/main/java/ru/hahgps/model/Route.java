package ru.hahgps.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Сохранённый маршрут (записанный админом путь).
 */
public class Route {

    private final int id;
    private final String name;
    private final String world;
    private final String transport;
    private final String createdBy;
    private final long createdAt;
    private final List<Location> nodes;

    public Route(int id, String name, String world, String transport, String createdBy,
                 long createdAt, List<Location> nodes) {
        this.id = id;
        this.name = name;
        this.world = world;
        this.transport = transport;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.nodes = nodes != null ? nodes : new ArrayList<>();
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getWorld() {
        return world;
    }

    public String getTransport() {
        return transport;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public List<Location> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public int getNodeCount() {
        return nodes.size();
    }

    /**
     * Длина маршрута в блоках.
     */
    public double getLength() {
        double length = 0.0;
        for (int i = 1; i < nodes.size(); i++) {
            Location a = nodes.get(i - 1);
            Location b = nodes.get(i);
            if (a.getWorld() != null && a.getWorld().equals(b.getWorld())) {
                length += a.distance(b);
            }
        }
        return length;
    }

    public static List<Location> toNodeList(String worldName, List<double[]> coords) {
        List<Location> list = new ArrayList<>();
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return list;
        }
        for (double[] c : coords) {
            list.add(new Location(world, c[0], c[1], c[2]));
        }
        return list;
    }
}
