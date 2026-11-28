package ru.hahgps.vmodel;

import java.util.List;
import java.util.Set;

public record ModelDefinition(
        String key,
        String displayItem,
        int customModelData,
        Set<String> faces,
        double offsetX,
        double offsetY,
        double offsetZ,
        boolean rotateToFace,
        double hitboxW,
        double hitboxH,
        double hitboxD,
        List<ModelShape> shapes,
        double hitOx,
        double hitOy,
        double hitOz,
        float hitYaw,
        float hitPitch,
        float hitRoll,
        boolean collision,
        List<double[]> sitPoints
) {

    public boolean allows(String face) {
        return faces != null && faces.contains(face);
    }

    public static ModelDefinition fallback(String key) {
        return new ModelDefinition(key, "minecraft:paper", 0, Set.of("FLOOR"),
                0, 0, 0, true, 1, 1, 1, List.of(),
                0, 0, 0, 0f, 0f, 0f, true, List.of());
    }

    public double boxW() {
        return hitboxW > 0.05 ? hitboxW : 1;
    }

    public double boxH() {
        return hitboxH > 0.05 ? hitboxH : 1;
    }

    public double boxD() {
        return hitboxD > 0.05 ? hitboxD : 1;
    }

    public List<String> faceList() {
        return faces == null ? List.of() : List.copyOf(faces);
    }
}
