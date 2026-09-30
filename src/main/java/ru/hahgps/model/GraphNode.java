package ru.hahgps.model;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Узел графа дорог.
 */
public class GraphNode {

    private final int id;
    private final Location location;

    public GraphNode(int id, Location location) {
        this.id = id;
        this.location = location;
    }

    public int getId() {
        return id;
    }

    public Location getLocation() {
        return location;
    }

    public double distanceTo(GraphNode other) {
        if (location.getWorld() == null || other.location.getWorld() == null
                || !location.getWorld().equals(other.location.getWorld())) {
            return Double.MAX_VALUE;
        }
        return location.distance(other.location);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GraphNode)) return false;
        GraphNode that = (GraphNode) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
