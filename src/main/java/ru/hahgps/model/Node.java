package ru.hahgps.model;

import org.bukkit.Location;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

public final class Node {

    private final String id;
    private String world;
    private double x;
    private double y;
    private double z;
    private String name;
    private final EnumSet<TransportType> allowedTypes = EnumSet.noneOf(TransportType.class);

    public Node(String id, String world, double x, double y, double z) {
        this.id = id;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.name = "";
    }

    public String getId() {
        return id;
    }

    public String getWorld() {
        return world;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public Set<TransportType> allowedTypes() {
        return allowedTypes;
    }

    public boolean allows(TransportType type) {
        return allowedTypes.contains(type);
    }

    public void setAllowedTypes(Collection<TransportType> types) {
        allowedTypes.clear();
        if (types != null) {
            allowedTypes.addAll(types);
        }
    }

    public void addAllowedType(TransportType type) {
        allowedTypes.add(type);
    }

    public void removeAllowedType(TransportType type) {
        allowedTypes.remove(type);
    }

    public Vec vec() {
        return new Vec(world, x, y, z);
    }

    public Location location() {
        return vec().toLocation();
    }

    public String display() {
        return name == null || name.isBlank() ? id : name;
    }

    @Override
    public String toString() {
        return "Node{" + id + " @ " + vec().pretty() + " " + allowedTypes + "}";
    }
}
