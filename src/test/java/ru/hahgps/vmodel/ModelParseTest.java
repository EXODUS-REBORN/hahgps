package ru.hahgps.vmodel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelParseTest {

    private static final String DEFINITION = """
            {"model_key":"hahtojer_chair","display_item":"minecraft:oak_planks",
             "custom_model_data":20201,"allowed_faces":["floor","WALL"],
             "offset":[0.0,0.25,-0.1],"hitbox":[1.2,0.9,1.1],
             "hitbox_offset":[0.1,-0.2,0.0],"hitbox_rotate":[15,0,0],
             "collision":true,"rotate_to_face":true,
             "parts":[[-0.6,-0.45,-0.55,0.6,0.45,0.55],
                      [-0.1,0.45,-0.1,0.1,0.9,0.1,0,0,0,2,45]],
             "sit_points":[[0,0.35,0]]}
            """;

    @Test
    void definitionParsesEveryField() {
        ModelDefinition def = ModelParse.definition((Object) Json.parse(DEFINITION));
        assertNotNull(def, "определение не разобралось");
        assertEquals("hahtojer_chair", def.key(), "ключ");
        assertEquals("minecraft:oak_planks", def.displayItem(), "предмет");
        assertEquals(20201, def.customModelData(), "CMD");
        assertEquals(Set2.of("FLOOR", "WALL"), Set2.sorted(def.faces()), "грани");
        assertEquals(0.25, def.offsetY(), 1e-9, "смещение Y");
        assertEquals(-0.1, def.offsetZ(), 1e-9, "смещение Z");
        assertEquals(1.2, def.hitboxW(), 1e-9, "хитбокс W");
        assertEquals(0.9, def.hitboxH(), 1e-9, "хитбокс H");
        assertEquals(0.1, def.hitOx(), 1e-9, "сдвиг хитбокса X");
        assertEquals(-0.2, def.hitOy(), 1e-9, "сдвиг хитбокса Y");
        assertEquals(15f, def.hitYaw(), 1e-6, "поворот хитбокса");
        assertTrue(def.collision(), "коллизия");
        assertTrue(def.rotateToFace(), "поворот от стены");
        assertEquals(2, def.shapes().size(), "две части");
        assertFalse(def.shapes().get(0).rotated(), "первая часть без поворота");
        assertTrue(def.shapes().get(1).rotated(), "вторая часть повёрнута");
        assertEquals(2, def.shapes().get(1).axis(), "ось поворота Y");
        assertEquals(45f, def.shapes().get(1).angle(), 1e-6, "угол поворота");
        assertEquals(1, def.sitPoints().size(), "одна точка «сесть»");
        assertEquals(0.35, def.sitPoints().get(0)[1], 1e-9, "высота сиденья");
    }

    @Test
    void definitionFallsBackToDefaults() {
        ModelDefinition def = ModelParse.definition((Object) Json.parse("""
                {"model_key":"Без_Полей"}
                """));
        assertNotNull(def);
        assertEquals("без_полей", def.key(), "ключ нормализован в нижний регистр");
        assertEquals("minecraft:paper", def.displayItem(), "предмет по умолчанию");
        assertEquals(1.0, def.boxW(), 1e-9, "хитбокс по умолчанию");
        assertEquals(Set2.sorted(java.util.Set.of("FLOOR")), Set2.sorted(def.faces()),
                "грань по умолчанию — пол");
        assertTrue(def.rotateToFace(), "поворот от стены по умолчанию включён");
        assertTrue(def.shapes().isEmpty(), "частей нет");
        assertTrue(def.sitPoints().isEmpty(), "точек «сесть» нет");
    }

    @Test
    void definitionWithoutKeyIsRejected() {
        assertNull(ModelParse.definition((Object) Json.parse("{\"display_item\":\"minecraft:paper\"}")),
                "без ключа модель не принимается");
        assertNull(ModelParse.definition((Object) Json.parse("[]")), "массив — не определение");
    }

    @Test
    void placedRowParsesCoordinates() {
        PlacedModel row = ModelParse.placed((Object) Json.parse("""
                {"id":"7b6f93a0-6a7b-4f2b-9a1e-6b0d1c2f3a4b","world":"world",
                 "x":10.25,"y":64.0,"z":-3.5,"yaw":90.0,"pitch":0.0,
                 "model_id":"hahtojer_chair","placed_by":"haker_one"}
                """));
        assertNotNull(row, "расстановка не разобралась");
        assertEquals("world", row.world(), "мир");
        assertEquals(10.25, row.x(), 1e-9, "X");
        assertEquals(-3.5, row.z(), 1e-9, "Z");
        assertEquals(90f, row.yaw(), 1e-6, "поворот");
        assertEquals("hahtojer_chair", row.modelKey(), "модель");
    }

    @Test
    void placedRowRejectsBrokenInput() {
        assertNull(ModelParse.placed((Object) Json.parse(
                "{\"id\":\"не-uuid\",\"world\":\"world\",\"model_id\":\"x\"}")), "битый UUID");
        assertNull(ModelParse.placed((Object) Json.parse(
                "{\"id\":\"7b6f93a0-6a7b-4f2b-9a1e-6b0d1c2f3a4b\",\"world\":\"\"}")), "пустой мир");
    }

    @Test
    void shapesSkipDegenerateParts() {
        List<ModelShape> shapes = ModelParse.shapes(Json.parse("""
                [[-0.5,-0.5,-0.5,0.5,0.5,0.5],[0,0,0,0,0,0],[1,2]]
                """));
        assertEquals(1, shapes.size(), "нулевые и короткие части отбрасываются");
    }

    @Test
    void shapesAreLimitedToEighty() {
        StringBuilder raw = new StringBuilder("[");
        for (int i = 0; i < 120; i++) {
            if (i > 0) raw.append(',');
            raw.append("[-0.5,-0.5,-0.5,0.5,0.5,0.5]");
        }
        raw.append(']');
        List<ModelShape> shapes = ModelParse.shapes(Json.parse(raw.toString()));
        assertEquals(ModelParse.MAX_SHAPES, shapes.size(), "больше 80 частей не берём");
    }

    @Test
    void sitPointsAcceptObjectsAndClampToLimit() {
        List<double[]> points = ModelParse.sitPoints(Json.parse("""
                [{"x":1,"y":2,"z":3},{"x":99,"y":-99,"z":0},"мусор"]
                """));
        assertEquals(2, points.size(), "мусорная точка отброшена");
        assertEquals(ModelParse.SIT_LIMIT, points.get(1)[0], 1e-9, "координата обрезана сверху");
        assertEquals(-ModelParse.SIT_LIMIT, points.get(1)[1], 1e-9, "координата обрезана снизу");
    }

    @Test
    void snapshotSplitsIntoDefinitionsAndPlaced() {
        Object payload = Json.parse("""
                {"ok":true,
                 "definitions":[{"model_key":"a"},{"model_key":"b"},{"model_key":"a"}],
                 "placed":[{"id":"7b6f93a0-6a7b-4f2b-9a1e-6b0d1c2f3a4b","world":"w","model_id":"a"}]}
                """);
        List<ModelDefinition> defs = ModelParse.definitions(Json.object(payload).get("definitions"));
        List<PlacedModel> placed = ModelParse.placedList(Json.object(payload).get("placed"));
        assertEquals(2, defs.size(), "дубликаты ключей схлопываются");
        assertEquals(1, placed.size(), "расстановка разобрана");
    }

    @Test
    void hitboxRotateArrayWinsOverScalars() {
        ModelDefinition def = ModelParse.definition((Object) Json.parse("""
                {"model_key":"k","hitbox_rotate":[30,15,-5],"hitbox_yaw":90}
                """));
        assertNotNull(def);
        assertEquals(30f, def.hitYaw(), 1e-6, "массив важнее скаляра");
        assertEquals(15f, def.hitPitch(), 1e-6, "тангаж");
        assertEquals(-5f, def.hitRoll(), 1e-6, "крен");
    }

    @Test
    void serializeRoundTripsThroughParser() {
        Map<String, Object> payload = Json.map(
                "action", "place",
                "id", "7b6f93a0-6a7b-4f2b-9a1e-6b0d1c2f3a4b",
                "world", "world",
                "x", 12.5, "y", 64.0, "z", -1.25,
                "model_id", "hahtojer_chair");
        String raw = Json.write(payload);
        PlacedModel row = ModelParse.placed((Object) Json.parse(raw));
        assertNotNull(row, "сериализованная расстановка не читается");
        assertEquals(12.5, row.x(), 1e-9, "X после круга");
        assertEquals("hahtojer_chair", row.modelKey(), "модель после круга");
        assertTrue(raw.contains("\"action\":\"place\""), "строка без пробелов: " + raw);
    }

    private static final class Set2 {

        private Set2() {
        }

        static List<String> sorted(java.util.Set<String> faces) {
            List<String> out = new java.util.ArrayList<>(faces);
            out.sort(String::compareTo);
            return out;
        }

        static List<String> of(String a, String b) {
            List<String> out = new java.util.ArrayList<>(List.of(a, b));
            out.sort(String::compareTo);
            return out;
        }
    }
}
