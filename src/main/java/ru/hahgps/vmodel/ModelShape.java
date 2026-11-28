package ru.hahgps.vmodel;

public record ModelShape(
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        double rotX,
        double rotY,
        double rotZ,
        int axis,
        float angle
) {

    public static ModelShape box(double minX, double minY, double minZ,
                                 double maxX, double maxY, double maxZ) {
        return new ModelShape(minX, minY, minZ, maxX, maxY, maxZ, 0, 0, 0, 0, 0f);
    }

    public ModelShape normalized() {
        return new ModelShape(
                Math.min(minX, maxX),
                Math.min(minY, maxY),
                Math.min(minZ, maxZ),
                Math.max(minX, maxX),
                Math.max(minY, maxY),
                Math.max(minZ, maxZ),
                rotX, rotY, rotZ, axis, angle);
    }

    public boolean rotated() {
        return axis > 0 && Math.abs(angle) > 0.01f;
    }

    public double sizeX() {
        return Math.abs(maxX - minX);
    }

    public double sizeY() {
        return Math.abs(maxY - minY);
    }

    public double sizeZ() {
        return Math.abs(maxZ - minZ);
    }

    public double volume() {
        return sizeX() * sizeY() * sizeZ();
    }
}
