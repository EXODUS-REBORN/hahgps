package ru.hahgps.util;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import ru.hahgps.model.BodyPart;

/**
 * Куда именно пришёлся клик по носителю: голова, туловище, руки или ноги.
 *
 * Считается по точке пересечения взгляда игрока с хитбоксом сущности, без
 * обращения к внутренним данным клиента: хитбокс и направление взгляда — вся
 * нужная информация. Точка переводится в «локальные» координаты носителя
 * (рост и поворот), поэтому разметка годится и для игрока, и для зомби, и для
 * животного.
 */
public final class BodyPartResolver {

    private BodyPartResolver() {
    }

    /** Часть тела, в которую попал игрок; null — не живое существо. */
    public static BodyPart of(Player hitter, Entity target) {
        if (hitter == null || !(target instanceof LivingEntity)) {
            return null;
        }
        BoundingBox box = target.getBoundingBox();
        if (box.getHeight() < 0.05 || Math.max(box.getWidthX(), box.getWidthZ()) < 0.05) {
            return null;
        }
        Location eye = hitter.getEyeLocation();
        return resolve(eye.toVector(), eye.getDirection(), box, target.getLocation().getYaw());
    }

    /** Часть тела по готовой точке попадания (бросок трекера в сущность). */
    public static BodyPart at(Vector hit, Entity target) {
        if (hit == null || !(target instanceof LivingEntity)) {
            return null;
        }
        BoundingBox box = target.getBoundingBox();
        if (box.getHeight() < 0.05 || Math.max(box.getWidthX(), box.getWidthZ()) < 0.05) {
            return null;
        }
        double relativeY = (hit.getY() - box.getMinY()) / box.getHeight();
        return BodyPart.of(relativeY, sideOf(hit, box, target.getLocation().getYaw()));
    }

    /**
     * Часть тела по лучу зрения и хитбоксу.
     *
     * @param eye     глаза игрока
     * @param look    направление взгляда (единичный вектор)
     * @param box     хитбокс носителя
     * @param yaw     поворот носителя в градусах (Minecraft: 0 — на юг)
     */
    public static BodyPart resolve(Vector eye, Vector look, BoundingBox box, float yaw) {
        if (eye == null || look == null || box == null) {
            return null;
        }
        Vector hit = intersect(eye, look, box);
        if (hit == null) {
            hit = closestPoint(eye, box);
        }
        double height = box.getHeight();
        double relativeY = height <= 0.0 ? 0.5 : (hit.getY() - box.getMinY()) / height;
        double side = sideOf(hit, box, yaw);
        return BodyPart.of(relativeY, side);
    }

    /**
     * Смещение точки попадания вбок в долях половины хитбокса: 0 — центр,
     * ±1 — край. Положительное — правая сторона носителя (его собственная,
     * не игрока).
     */
    static double sideOf(Vector hit, BoundingBox box, float yaw) {
        double dx = hit.getX() - box.getCenterX();
        double dz = hit.getZ() - box.getCenterZ();
        double radians = Math.toRadians(yaw);
        // «Право» носителя: вектор, повёрнутый от взгляда на -90°.
        double rightX = -Math.cos(radians);
        double rightZ = -Math.sin(radians);
        double projected = dx * rightX + dz * rightZ;
        double halfX = box.getWidthX() / 2.0;
        double halfZ = box.getWidthZ() / 2.0;
        double extent = Math.abs(rightX) * halfX + Math.abs(rightZ) * halfZ;
        if (extent < 0.05) {
            extent = Math.max(halfX, halfZ);
        }
        if (extent < 0.05) {
            return 0.0;
        }
        return projected / extent;
    }

    /** Ближайшая точка хитбокса к глазу: запасной вариант, если луч промахнулся. */
    static Vector closestPoint(Vector point, BoundingBox box) {
        return new Vector(
                clamp(point.getX(), box.getMinX(), box.getMaxX()),
                clamp(point.getY(), box.getMinY(), box.getMaxY()),
                clamp(point.getZ(), box.getMinZ(), box.getMaxZ()));
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    /** Пересечение луча с хитбоксом (метод плит); null — мимо. */
    static Vector intersect(Vector origin, Vector direction, BoundingBox box) {
        double tMin = 0.0;
        double tMax = Double.MAX_VALUE;
        double[] start = {origin.getX(), origin.getY(), origin.getZ()};
        double[] dir = {direction.getX(), direction.getY(), direction.getZ()};
        double[] min = {box.getMinX(), box.getMinY(), box.getMinZ()};
        double[] max = {box.getMaxX(), box.getMaxY(), box.getMaxZ()};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(dir[axis]) < 1.0e-9) {
                if (start[axis] < min[axis] || start[axis] > max[axis]) {
                    return null;
                }
                continue;
            }
            double t1 = (min[axis] - start[axis]) / dir[axis];
            double t2 = (max[axis] - start[axis]) / dir[axis];
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return null;
            }
        }
        // Луч начался внутри хитбокса: поверхность — это ближайшая точка, а не
        // вход впереди (иначе часть тела считалась бы по точке «за спиной»).
        if (tMin <= 0.0) {
            return null;
        }
        return origin.clone().add(direction.clone().multiply(tMin));
    }
}
