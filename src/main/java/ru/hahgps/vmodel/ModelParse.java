package ru.hahgps.vmodel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ModelParse {

    public static final int MAX_SHAPES = 80;
    public static final int MAX_SIT_POINTS = 8;
    public static final double SIT_LIMIT = 4.0;

    private ModelParse() {
    }

    public static ModelDefinition definition(Object raw) {
        if (!(raw instanceof Map<?, ?>)) {
            return null;
        }
        String key = Json.string(raw, "model_key", Json.string(raw, "model_id", ""));
        if (key == null || key.isBlank()) {
            return null;
        }
        return new ModelDefinition(
                key.trim().toLowerCase(Locale.ROOT),
                Json.string(raw, "display_item", "minecraft:paper"),
                Json.integer(raw, "custom_model_data", 0),
                faces(raw),
                Json.number(raw, "offset_x", Json.at(Json.numbers(Json.object(raw).get("offset")), 0, 0.0)),
                Json.number(raw, "offset_y", Json.at(Json.numbers(Json.object(raw).get("offset")), 1, 0.0)),
                Json.number(raw, "offset_z", Json.at(Json.numbers(Json.object(raw).get("offset")), 2, 0.0)),
                Json.bool(raw, "rotate_to_face", true),
                box(raw, "hitbox_w", 0),
                box(raw, "hitbox_h", 1),
                box(raw, "hitbox_d", 2),
                shapes(Json.object(raw).get("parts")),
                Json.number(raw, "hitbox_ox", Json.at(Json.numbers(Json.object(raw).get("hitbox_offset")), 0, 0.0)),
                Json.number(raw, "hitbox_oy", Json.at(Json.numbers(Json.object(raw).get("hitbox_offset")), 1, 0.0)),
                Json.number(raw, "hitbox_oz", Json.at(Json.numbers(Json.object(raw).get("hitbox_offset")), 2, 0.0)),
                (float) rotate(raw, "hitbox_yaw", 0),
                (float) rotate(raw, "hitbox_pitch", 1),
                (float) rotate(raw, "hitbox_roll", 2),
                Json.bool(raw, "collision", true),
                sitPoints(Json.object(raw).get("sit_points")));
    }

    public static PlacedModel placed(Object raw) {
        if (!(raw instanceof Map<?, ?>)) {
            return null;
        }
        String id = Json.string(raw, "id", Json.string(raw, "uid", ""));
        String key = Json.string(raw, "model_id", Json.string(raw, "model_key", ""));
        if (id.isBlank() || key.isBlank()) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(id.trim());
        } catch (IllegalArgumentException broken) {
            return null;
        }
        String world = Json.string(raw, "world", "");
        if (world.isBlank()) {
            return null;
        }
        return new PlacedModel(uuid, world,
                Json.number(raw, "x", 0),
                Json.number(raw, "y", 0),
                Json.number(raw, "z", 0),
                (float) Json.number(raw, "yaw", 0),
                (float) Json.number(raw, "pitch", 0),
                key.trim().toLowerCase(Locale.ROOT));
    }

    public static Set<String> faces(Object raw) {
        Set<String> out = new LinkedHashSet<>();
        for (Object item : Json.array(Json.object(raw).get("allowed_faces"))) {
            String face = item == null ? "" : String.valueOf(item).trim().toUpperCase(Locale.ROOT);
            if (face.equals("FLOOR") || face.equals("WALL") || face.equals("CEILING")) {
                out.add(face);
            }
        }
        if (out.isEmpty()) {
            out.add("FLOOR");
        }
        return Set.copyOf(out);
    }

    private static double rotate(Object raw, String scalar, int index) {
        List<Double> array = Json.numbers(Json.object(raw).get("hitbox_rotate"));
        if (index < array.size()) {
            return array.get(index);
        }
        return Json.number(raw, scalar, 0);
    }

    private static double box(Object raw, String key, int index) {
        double direct = Json.number(raw, key, 0);
        if (direct > 0.05) {
            return direct;
        }
        double fromArray = Json.at(Json.numbers(Json.object(raw).get("hitbox")), index, 0.0);
        return fromArray > 0.05 ? fromArray : 1.0;
    }

    public static List<ModelShape> shapes(Object raw) {
        List<ModelShape> shapes = new ArrayList<>();
        List<Object> rows = Json.array(raw);
        int limit = Math.min(MAX_SHAPES, rows.size());
        for (int i = 0; i < limit; i++) {
            List<Double> row = Json.numbers(rows.get(i));
            if (row.size() < 6) {
                continue;
            }
            boolean rotated = row.size() >= 11;
            int axis = rotated ? (int) Math.round(row.get(9)) : 0;
            float angle = rotated ? (float) (double) row.get(10) : 0f;
            ModelShape shape = new ModelShape(
                    row.get(0), row.get(1), row.get(2),
                    row.get(3), row.get(4), row.get(5),
                    rotated ? row.get(6) : 0,
                    rotated ? row.get(7) : 0,
                    rotated ? row.get(8) : 0,
                    axis, angle).normalized();
            if (shape.sizeX() < 0.01 && shape.sizeY() < 0.01 && shape.sizeZ() < 0.01) {
                continue;
            }
            shapes.add(shape);
        }
        return List.copyOf(shapes);
    }

    public static List<double[]> sitPoints(Object raw) {
        List<double[]> points = new ArrayList<>();
        List<Object> rows = Json.array(raw);
        int limit = Math.min(MAX_SIT_POINTS, rows.size());
        for (int i = 0; i < limit; i++) {
            Object row = rows.get(i);
            List<Double> values = row instanceof Map<?, ?>
                    ? List.of(Json.number(row, "x", 0), Json.number(row, "y", 0),
                              Json.number(row, "z", 0))
                    : Json.numbers(row);
            if (values.size() < 3) {
                continue;
            }
            points.add(new double[]{
                    clampSit(values.get(0)),
                    clampSit(values.get(1)),
                    clampSit(values.get(2))});
        }
        return List.copyOf(points);
    }

    private static double clampSit(double value) {
        return Math.max(-SIT_LIMIT, Math.min(SIT_LIMIT, value));
    }

    public static List<ModelDefinition> definitions(Object raw) {
        List<ModelDefinition> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object row : Json.array(raw)) {
            ModelDefinition def = definition(row);
            if (def != null && seen.add(def.key())) {
                out.add(def);
            }
        }
        return out;
    }

    public static List<PlacedModel> placedList(Object raw) {
        List<PlacedModel> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Object row : Json.array(raw)) {
            PlacedModel placed = placed(row);
            if (placed != null && seen.add(placed.id())) {
                out.add(placed);
            }
        }
        return out;
    }
}
