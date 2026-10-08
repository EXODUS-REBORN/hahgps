package ru.hahgps.model;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ребро графа для хранения в БД.
 *
 * Узлы графа синтезируются из маршрутов при каждой пересборке, поэтому их
 * целочисленные id нестабильны. Ребро, созданное админом через {@code /gps edge},
 * привязано к координатам: при пересборке графа концы матчатся по ним.
 */
public record EdgeRecord(String world,
                         double x1, double y1, double z1,
                         double x2, double y2, double z2,
                         Set<TransportType> transports) {

    private static final String SEP = ",";

    public static Set<TransportType> parseTransports(String raw) {
        Set<TransportType> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(SEP)) {
            String key = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (key.isEmpty()) {
                continue;
            }
            try {
                out.add(TransportType.valueOf(key.toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                // Неизвестный тип транспорта в БД не должен ронять загрузку графа.
            }
        }
        return out;
    }

    public static String serializeTransports(Set<TransportType> transports) {
        if (transports == null || transports.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (TransportType type : transports) {
            if (sb.length() > 0) {
                sb.append(SEP);
            }
            sb.append(type.name().toLowerCase(java.util.Locale.ROOT));
        }
        return sb.toString();
    }

    /** Канонический вид: узел с меньшими координатами — первый. */
    public EdgeRecord canonical() {
        if (comparePoint(x1, y1, z1, x2, y2, z2) <= 0) {
            return this;
        }
        return new EdgeRecord(world, x2, y2, z2, x1, y1, z1, transports);
    }

    public List<double[]> points() {
        return List.of(
                new double[]{x1, y1, z1},
                new double[]{x2, y2, z2});
    }

    private static int comparePoint(double ax, double ay, double az, double bx, double by, double bz) {
        int c = Double.compare(ax, bx);
        if (c != 0) return c;
        c = Double.compare(ay, by);
        if (c != 0) return c;
        return Double.compare(az, bz);
    }

    /** Совпадают ли концы в любом порядке (с допуском в доли блока). */
    public boolean sameEndpoints(String worldName, double ax, double ay, double az,
                                 double bx, double by, double bz) {
        if (world == null || !world.equalsIgnoreCase(worldName)) {
            return false;
        }
        return (near(x1, ax) && near(y1, ay) && near(z1, az)
                && near(x2, bx) && near(y2, by) && near(z2, bz))
                || (near(x1, bx) && near(y1, by) && near(z1, bz)
                && near(x2, ax) && near(y2, ay) && near(z2, az));
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 0.05;
    }

    public static Set<TransportType> immutable(Set<TransportType> transports) {
        return transports == null || transports.isEmpty()
                ? EnumSet.noneOf(TransportType.class)
                : Set.copyOf(transports);
    }
}
