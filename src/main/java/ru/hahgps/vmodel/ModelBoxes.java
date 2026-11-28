package ru.hahgps.vmodel;

import org.bukkit.Location;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

public final class ModelBoxes {

    private static final double EPS = 1.0E-4;

    private ModelBoxes() {
    }

    public static List<ModelShape> shapes(ModelDefinition def) {
        if (def == null) {
            return List.of();
        }
        List<ModelShape> shapes = def.shapes();
        if (shapes != null && !shapes.isEmpty()) {
            return shapes;
        }
        double hw = def.boxW() / 2.0;
        double hh = def.boxH() / 2.0;
        double hd = def.boxD() / 2.0;
        return List.of(ModelShape.box(-hw, -hh, -hd, hw, hh, hd));
    }

    public static ModelShape localBounds(ModelDefinition def) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (ModelShape shape : shapes(def)) {
            ModelShape box = shape.normalized();
            minX = Math.min(minX, box.minX());
            minY = Math.min(minY, box.minY());
            minZ = Math.min(minZ, box.minZ());
            maxX = Math.max(maxX, box.maxX());
            maxY = Math.max(maxY, box.maxY());
            maxZ = Math.max(maxZ, box.maxZ());
        }
        if (!Double.isFinite(minX)) {
            return ModelShape.box(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        }
        return ModelShape.box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static Double raycast(Location origin, float yaw, float pitch, ModelDefinition def,
                                 Vector eye, Vector dir, double maxDist) {
        if (origin == null || def == null || eye == null || dir == null) {
            return null;
        }
        Vector localOrigin = hitboxInverse(def, toLocal(origin, yaw, pitch,
                eye.getX(), eye.getY(), eye.getZ()));
        Vector localDir = hitboxInverseDir(def, rotateToLocal(yaw, pitch,
                dir.getX(), dir.getY(), dir.getZ()));
        Double best = null;
        for (ModelShape shape : shapes(def)) {
            Vector start = localOrigin;
            Vector step = localDir;
            if (shape.rotated()) {
                Vector pivot = new Vector(shape.rotX(), shape.rotY(), shape.rotZ());
                start = rotateAround(localOrigin, pivot, shape.axis(), -shape.angle());
                step = rotateVec(localDir, shape.axis(), -shape.angle());
            }
            Double hit = slab(shape, start, step, maxDist);
            if (hit != null && (best == null || hit < best)) {
                best = hit;
            }
        }
        return best;
    }

    public static OrientedBox obb(Location origin, float yaw, float pitch, ModelDefinition def) {
        return obbOfShape(origin, yaw, pitch, def, localBounds(def));
    }

    public static BoundingBox envelope(Location origin, float yaw, float pitch, ModelDefinition def) {
        BoundingBox box = null;
        for (ModelShape shape : shapes(def)) {
            BoundingBox part = obbOfShape(origin, yaw, pitch, def, shape).aabb();
            box = box == null ? part : box.union(part);
        }
        return box != null ? box : obb(origin, yaw, pitch, def).aabb();
    }

    public static boolean overlapsAabb(Location origin, float yaw, float pitch,
                                       ModelDefinition def, BoundingBox other) {
        if (other == null) {
            return false;
        }
        if (!envelope(origin, yaw, pitch, def).overlaps(other)) {
            return false;
        }
        OrientedBox world = fromAabb(other);
        for (ModelShape shape : shapes(def)) {
            if (obbOfShape(origin, yaw, pitch, def, shape).shrunk(0.02).overlaps(world)) {
                return true;
            }
        }
        return false;
    }

    public static boolean overlapsModel(Location origin, float yaw, float pitch, ModelDefinition def,
                                        Location otherOrigin, float otherYaw, float otherPitch,
                                        ModelDefinition other) {
        for (ModelShape shape : shapes(def)) {
            OrientedBox ours = obbOfShape(origin, yaw, pitch, def, shape).shrunk(0.02);
            for (ModelShape theirs : shapes(other)) {
                if (ours.overlaps(obbOfShape(otherOrigin, otherYaw, otherPitch, other, theirs)
                        .shrunk(0.02))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static BoundingBox worldAabb(Location origin, float yaw, float pitch,
                                        ModelDefinition def, ModelShape shape) {
        return obbOfShape(origin, yaw, pitch, def, shape).aabb();
    }

    public static OrientedBox obbOfShape(Location origin, float yaw, float pitch,
                                         ModelDefinition def, ModelShape shape) {
        ModelShape box = shape.normalized();
        double cx = (box.minX() + box.maxX()) / 2.0;
        double cy = (box.minY() + box.maxY()) / 2.0;
        double cz = (box.minZ() + box.maxZ()) / 2.0;
        Vector local = new Vector(cx, cy, cz);
        Vector ax = new Vector(1, 0, 0);
        Vector ay = new Vector(0, 1, 0);
        Vector az = new Vector(0, 0, 1);
        if (shape.rotated()) {
            Vector pivot = new Vector(shape.rotX(), shape.rotY(), shape.rotZ());
            local = rotateAround(local, pivot, shape.axis(), shape.angle());
            ax = rotateVec(ax, shape.axis(), shape.angle());
            ay = rotateVec(ay, shape.axis(), shape.angle());
            az = rotateVec(az, shape.axis(), shape.angle());
        }
        local = hitboxForward(def, local);
        ax = hitboxForwardDir(def, ax);
        ay = hitboxForwardDir(def, ay);
        az = hitboxForwardDir(def, az);
        Vector world = toWorld(origin, yaw, pitch, local.getX(), local.getY(), local.getZ());
        return new OrientedBox(
                world,
                unit(rotateToWorld(yaw, pitch, ax.getX(), ax.getY(), ax.getZ())),
                unit(rotateToWorld(yaw, pitch, ay.getX(), ay.getY(), ay.getZ())),
                unit(rotateToWorld(yaw, pitch, az.getX(), az.getY(), az.getZ())),
                Math.max(0.01, box.sizeX() / 2.0),
                Math.max(0.01, box.sizeY() / 2.0),
                Math.max(0.01, box.sizeZ() / 2.0));
    }

    public static Vector hitboxForward(ModelDefinition def, Vector local) {
        if (def == null) {
            return local;
        }
        Vector shifted = new Vector(local.getX() + def.hitOx(), local.getY() + def.hitOy(),
                local.getZ() + def.hitOz());
        return hitboxForwardDir(def, shifted);
    }

    public static Vector hitboxForwardDir(ModelDefinition def, Vector vector) {
        if (def == null) {
            return vector;
        }
        Vector out = vector;
        if (Math.abs(def.hitRoll()) > 0.01f) {
            out = rotateVec(out, 3, def.hitRoll());
        }
        if (Math.abs(def.hitPitch()) > 0.01f) {
            out = rotateVec(out, 1, def.hitPitch());
        }
        if (Math.abs(def.hitYaw()) > 0.01f) {
            out = rotateVec(out, 2, def.hitYaw());
        }
        return out;
    }

    public static Vector hitboxInverse(ModelDefinition def, Vector local) {
        Vector out = hitboxInverseDir(def, local);
        if (def == null) {
            return out;
        }
        return new Vector(out.getX() - def.hitOx(), out.getY() - def.hitOy(), out.getZ() - def.hitOz());
    }

    public static Vector hitboxInverseDir(ModelDefinition def, Vector vector) {
        if (def == null) {
            return vector;
        }
        Vector out = vector;
        if (Math.abs(def.hitYaw()) > 0.01f) {
            out = rotateVec(out, 2, -def.hitYaw());
        }
        if (Math.abs(def.hitPitch()) > 0.01f) {
            out = rotateVec(out, 1, -def.hitPitch());
        }
        if (Math.abs(def.hitRoll()) > 0.01f) {
            out = rotateVec(out, 3, -def.hitRoll());
        }
        return out;
    }

    public static Vector rotateVec(Vector vector, int axis, float angle) {
        double x = vector.getX();
        double y = vector.getY();
        double z = vector.getZ();
        double rad = Math.toRadians(angle);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        if (axis == 1) {
            return new Vector(x, y * cos - z * sin, y * sin + z * cos);
        }
        if (axis == 3) {
            return new Vector(x * cos - y * sin, x * sin + y * cos, z);
        }
        return new Vector(x * cos - z * sin, y, x * sin + z * cos);
    }

    public static Vector rotateAround(Vector point, Vector origin, int axis, float angle) {
        Vector relative = new Vector(point.getX() - origin.getX(), point.getY() - origin.getY(),
                point.getZ() - origin.getZ());
        Vector rotated = rotateVec(relative, axis, angle);
        return new Vector(rotated.getX() + origin.getX(), rotated.getY() + origin.getY(),
                rotated.getZ() + origin.getZ());
    }

    public static Vector elementPoint(ModelDefinition def, ModelShape shape,
                                      double lx, double ly, double lz) {
        Vector point = new Vector(lx, ly, lz);
        if (shape.rotated()) {
            point = rotateAround(point, new Vector(shape.rotX(), shape.rotY(), shape.rotZ()),
                    shape.axis(), shape.angle());
        }
        return hitboxForward(def, point);
    }

    public static Vector toWorld(Location origin, float yaw, float pitch,
                                 double lx, double ly, double lz) {
        Vector rotated = rotateToWorld(yaw, pitch, lx, ly, lz);
        return new Vector(origin.getX() + rotated.getX(), origin.getY() + rotated.getY(),
                origin.getZ() + rotated.getZ());
    }

    public static Vector toLocal(Location origin, float yaw, float pitch,
                                 double wx, double wy, double wz) {
        return rotateToLocal(yaw, pitch, wx - origin.getX(), wy - origin.getY(), wz - origin.getZ());
    }

    public static Quaternionf displayRotation(float yaw, float pitch) {
        return new Quaternionf().rotationYXZ(
                (float) Math.toRadians(180f - yaw),
                (float) Math.toRadians(-pitch),
                0f);
    }

    public static Vector rotateToWorld(float yaw, float pitch, double x, double y, double z) {
        Vector3f vector = new Vector3f((float) x, (float) y, (float) z);
        displayRotation(yaw, pitch).transform(vector);
        return new Vector(vector.x, vector.y, vector.z);
    }

    public static Vector rotateToLocal(float yaw, float pitch, double x, double y, double z) {
        Vector3f vector = new Vector3f((float) x, (float) y, (float) z);
        displayRotation(yaw, pitch).conjugate().transform(vector);
        return new Vector(vector.x, vector.y, vector.z);
    }

    public static Double slab(ModelShape shape, Vector origin, Vector dir, double maxDist) {
        double[] range = {0, maxDist};
        ModelShape box = shape.normalized();
        if (!clip(range, origin.getX(), dir.getX(), box.minX(), box.maxX())) {
            return null;
        }
        if (!clip(range, origin.getY(), dir.getY(), box.minY(), box.maxY())) {
            return null;
        }
        if (!clip(range, origin.getZ(), dir.getZ(), box.minZ(), box.maxZ())) {
            return null;
        }
        if (range[0] > range[1] || range[1] < 0 || range[0] > maxDist) {
            return null;
        }
        return Math.max(0, range[0]);
    }

    private static boolean clip(double[] range, double origin, double dir, double min, double max) {
        if (Math.abs(dir) < 1.0E-8) {
            return origin >= min && origin <= max;
        }
        double t1 = (min - origin) / dir;
        double t2 = (max - origin) / dir;
        if (t1 > t2) {
            double swap = t1;
            t1 = t2;
            t2 = swap;
        }
        range[0] = Math.max(range[0], t1);
        range[1] = Math.min(range[1], t2);
        return range[0] <= range[1];
    }

    public static OrientedBox fromAabb(BoundingBox box) {
        return new OrientedBox(
                box.getCenter(),
                new Vector(1, 0, 0),
                new Vector(0, 1, 0),
                new Vector(0, 0, 1),
                box.getWidthX() / 2.0,
                box.getHeight() / 2.0,
                box.getWidthZ() / 2.0);
    }

    private static Vector unit(Vector vector) {
        double length = vector.length();
        if (length < 1.0E-8) {
            return new Vector(0, 1, 0);
        }
        return vector.multiply(1.0 / length);
    }

    public static final class OrientedBox {

        private final Vector center;
        private final Vector ax;
        private final Vector ay;
        private final Vector az;
        private final double hx;
        private final double hy;
        private final double hz;

        OrientedBox(Vector center, Vector ax, Vector ay, Vector az,
                    double hx, double hy, double hz) {
            this.center = center;
            this.ax = ax;
            this.ay = ay;
            this.az = az;
            this.hx = Math.max(0, hx);
            this.hy = Math.max(0, hy);
            this.hz = Math.max(0, hz);
        }

        public Vector center() {
            return center;
        }

        public double halfX() {
            return hx;
        }

        public double halfY() {
            return hy;
        }

        public double halfZ() {
            return hz;
        }

        public OrientedBox shrunk(double by) {
            return new OrientedBox(center, ax, ay, az,
                    Math.max(0.01, hx - by), Math.max(0.01, hy - by), Math.max(0.01, hz - by));
        }

        public BoundingBox aabb() {
            double extX = Math.abs(ax.getX()) * hx + Math.abs(ay.getX()) * hy + Math.abs(az.getX()) * hz;
            double extY = Math.abs(ax.getY()) * hx + Math.abs(ay.getY()) * hy + Math.abs(az.getY()) * hz;
            double extZ = Math.abs(ax.getZ()) * hx + Math.abs(ay.getZ()) * hy + Math.abs(az.getZ()) * hz;
            return new BoundingBox(
                    center.getX() - extX, center.getY() - extY, center.getZ() - extZ,
                    center.getX() + extX, center.getY() + extY, center.getZ() + extZ);
        }

        public boolean overlaps(OrientedBox other) {
            Vector[] axes = {
                    ax, ay, az,
                    other.ax, other.ay, other.az,
                    ax.clone().crossProduct(other.ax),
                    ax.clone().crossProduct(other.ay),
                    ax.clone().crossProduct(other.az),
                    ay.clone().crossProduct(other.ax),
                    ay.clone().crossProduct(other.ay),
                    ay.clone().crossProduct(other.az),
                    az.clone().crossProduct(other.ax),
                    az.clone().crossProduct(other.ay),
                    az.clone().crossProduct(other.az)
            };
            Vector delta = other.center.clone().subtract(center);
            for (Vector axis : axes) {
                if (axis.lengthSquared() < 1.0E-12) {
                    continue;
                }
                if (separated(other, delta, axis)) {
                    return false;
                }
            }
            return true;
        }

        private boolean separated(OrientedBox other, Vector delta, Vector axis) {
            Vector n = unit(axis.clone());
            double ra = project(n);
            double rb = other.project(n);
            return Math.abs(n.dot(delta)) > ra + rb + EPS;
        }

        private double project(Vector axis) {
            return hx * Math.abs(axis.dot(ax)) + hy * Math.abs(axis.dot(ay))
                    + hz * Math.abs(axis.dot(az));
        }
    }
}
