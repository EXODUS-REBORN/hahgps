package ru.hahgps.model;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.Location;

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
        // Копия: иначе вызывающий держит тот же список, а владелец — нет.
        this.nodes = nodes != null ? new ArrayList<>(nodes) : new ArrayList<>();
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

    public double getLength() {
        double length = 0.0;
        for (int i = 1; i < nodes.size(); i++) {
            Location a = nodes.get(i - 1);
            Location b = nodes.get(i);
            if (a.getWorld() != null && b.getWorld() != null
                    && ru.hahgps.util.LocationUtil.sameWorld(a.getWorld(), b.getWorld())) {
                length += ru.hahgps.util.LocationUtil.distance(a, b);
            }
        }
        return length;
    }
}
