package ru.hahgps.vmodel;

import java.util.UUID;

public record PlacedModel(
        UUID id,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String modelKey
) {

    public double distanceSquared(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
