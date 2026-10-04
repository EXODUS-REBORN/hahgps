package ru.hahgps.util;
import org.bukkit.Location;
import org.bukkit.World;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.config.MessagesManager;
import java.util.Locale;
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
        if (!Double.isFinite(distance) || distance >= Double.MAX_VALUE) {

            return "—";
        }
        if (distance < 0) {
            distance = 0;
        }
        if (distance >= 1000) {
            String value = String.format(Locale.ROOT, "%.1f", distance / 1000.0);
            String localized = localized("distance-format-km", value);
            return localized != null
                    ? localized
                    : String.format(Locale.ROOT, "%.1f км", distance / 1000.0);
        }
        String value = String.format(Locale.ROOT, "%.0f", distance);
        String localized = localized("distance-format", value);
        return localized != null
                ? localized
                : String.format(Locale.ROOT, "%.0f м", distance);
    }


    private static String localized(String key, String value) {
        try {
            HahGpsPlugin plugin = HahGpsPlugin.getInstance();
            MessagesManager messages = plugin != null ? plugin.getMessages() : null;
            if (messages == null) {
                return null;
            }
            String result = messages.get(key, "value", value);
            if (result == null || result.startsWith("<red>Missing:")) {
                return null;
            }
            return result;
        } catch (Throwable t) {
            return null;
        }
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
