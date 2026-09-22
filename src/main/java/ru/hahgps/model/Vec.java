package ru.hahgps.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;

public record Vec(String world, double x, double y, double z) {

    public static final double UNREACHABLE = 1_000_000.0D;

    public static Vec of(Location location) {
        return new Vec(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }

    public Vec add(Vec other) {
        return new Vec(world, x + other.x, y + other.y, z + other.z);
    }

    public Vec sub(Vec other) {
        return new Vec(world, x - other.x, y - other.y, z - other.z);
    }

    public Vec mul(double factor) {
        return new Vec(world, x * factor, y * factor, z * factor);
    }

    public double distanceTo(Vec other) {
        if (other == null || !world.equals(other.world)) {
            return UNREACHABLE;
        }
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public boolean sameWorld(Vec other) {
        return other != null && world.equals(other.world);
    }

    public boolean sameWorld(World other) {
        return other != null && world.equals(other.getName());
    }

    public Location toLocation() {
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) {
            return null;
        }
        return new Location(bukkitWorld, x, y, z);
    }

    public String pretty() {
        return Math.round(x) + ", " + Math.round(y) + ", " + Math.round(z);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Vec other)) {
            return false;
        }
        return world.equals(other.world)
                && Double.compare(x, other.x) == 0
                && Double.compare(y, other.y) == 0
                && Double.compare(z, other.z) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(world, x, y, z);
    }
}
