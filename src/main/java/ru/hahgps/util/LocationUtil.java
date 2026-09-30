package ru.hahgps.util;
import org.bukkit.Location;
import org.bukkit.World;
public final class LocationUtil {
    private LocationUtil() {
    }
    public static double distance(Location a, Location b) {
        if (a.getWorld() == null || b.getWorld() == null) {
            return Double.MAX_VALUE;
        }
        if (!a.getWorld().equals(b.getWorld())) {
            return Double.MAX_VALUE;
        }
        return a.distance(b);
    }

    public static double distanceHorizontal(Location a, Location b) {
        if (a.getWorld() == null || b.getWorld() == null) {
            return Double.MAX_VALUE;
        }
        if (!a.getWorld().equals(b.getWorld())) {
            return Double.MAX_VALUE;
        }
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static String formatDistance(double distance) {
        if (distance >= 1000) {
            return String.format("%.1f км", distance / 1000.0);
        }
        return String.format("%.0f м", distance);
    }

    public static double angleTo(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.toDegrees(Math.atan2(dx, dz));
    }

    public static double normalizeAngle(double angle) {
        while (angle > 180) {
            angle -= 360;
        }
        while (angle < -180) {
            angle += 360;
        }
        return angle;
    }

    public static String worldName(World world) {
        return world != null ? world.getName() : "unknown";
    }
}
