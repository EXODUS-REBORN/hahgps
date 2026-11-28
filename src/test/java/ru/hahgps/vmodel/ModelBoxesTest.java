package ru.hahgps.vmodel;

import org.bukkit.Location;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelBoxesTest {

    private static Location at(double x, double y, double z, float yaw) {
        return new Location(null, x, y, z, yaw, 0f);
    }

    private static ModelDefinition cube(double size) {
        double half = size / 2.0;
        return new ModelDefinition("тест", "minecraft:paper", 0, Set.of("FLOOR"),
                0, 0, 0, true, size, size, size,
                List.of(ModelShape.box(-half, -half, -half, half, half, half)),
                0, 0, 0, 0f, 0f, 0f, true, List.of());
    }

    @Test
    void shapesFallBackToTheHitboxBox() {
        ModelDefinition def = new ModelDefinition("тест", "minecraft:paper", 0, Set.of("FLOOR"),
                0, 0, 0, true, 1.0, 2.0, 3.0, List.of(),
                0, 0, 0, 0f, 0f, 0f, true, List.of());
        List<ModelShape> shapes = ModelBoxes.shapes(def);
        assertEquals(1, shapes.size(), "без parts должна остаться одна коробка");
        assertEquals(1.0, shapes.get(0).sizeX(), 1e-9, "ширина");
        assertEquals(2.0, shapes.get(0).sizeY(), 1e-9, "высота");
        assertEquals(3.0, shapes.get(0).sizeZ(), 1e-9, "глубина");
    }

    @Test
    void localBoundsCoverEveryPart() {
        ModelDefinition def = new ModelDefinition("тест", "minecraft:paper", 0, Set.of("FLOOR"),
                0, 0, 0, true, 1, 1, 1,
                List.of(ModelShape.box(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5),
                        ModelShape.box(0.2, 0.5, 0.2, 0.6, 1.2, 0.6)),
                0, 0, 0, 0f, 0f, 0f, true, List.of());
        ModelShape bounds = ModelBoxes.localBounds(def);
        assertEquals(-0.5, bounds.minX(), 1e-9, "нижняя граница X");
        assertEquals(1.2, bounds.maxY(), 1e-9, "верхняя граница Y");
    }

    @Test
    void obbOfCubeMatchesItsBoundingBox() {
        ModelDefinition def = cube(1.0);
        ModelBoxes.OrientedBox obb = ModelBoxes.obb(at(10, 64, 10, 0f), 0f, 0f, def);
        BoundingBox aabb = obb.aabb();
        assertEquals(9.5, aabb.getMinX(), 1e-6, "левый край");
        assertEquals(10.5, aabb.getMaxX(), 1e-6, "правый край");
        assertEquals(64.5, aabb.getMaxY(), 1e-6, "верх");
    }

    @Test
    void turnedModelChangesItsEnvelope() {
        ModelDefinition def = new ModelDefinition("тест", "minecraft:paper", 0, Set.of("FLOOR"),
                0, 0, 0, true, 1, 1, 1,
                List.of(ModelShape.box(-1.0, -0.5, -0.1, 1.0, 0.5, 0.1)),
                0, 0, 0, 0f, 0f, 0f, true, List.of());
        BoundingBox flat = ModelBoxes.envelope(at(0, 0, 0, 0f), 0f, 0f, def);
        assertEquals(2.0, flat.getWidthX(), 1e-6, "без поворота ширина 2.0");
        BoundingBox turned = ModelBoxes.envelope(at(0, 0, 0, 90f), 90f, 0f, def);
        assertEquals(2.0, turned.getWidthZ(), 1e-6, "повёрнутая модель растянута по Z");
        assertTrue(turned.getWidthX() < 0.4, "по X повёрнутая модель узкая");
    }

    @Test
    void raycastHitsTheModelFromTheFront() {
        ModelDefinition def = cube(1.0);
        Location origin = at(0, 0, 0, 0f);
        Vector eye = new Vector(0, 0, 5);
        Vector dir = new Vector(0, 0, -1);
        Double hit = ModelBoxes.raycast(origin, 0f, 0f, def, eye, dir, 10.0);
        assertNotNull(hit, "луч в куб не попал");
        assertEquals(4.5, hit, 0.05, "расстояние до ближней грани");
    }

    @Test
    void raycastMissesAway() {
        ModelDefinition def = cube(1.0);
        Double hit = ModelBoxes.raycast(at(0, 0, 0, 0f), 0f, 0f, def,
                new Vector(0, 10, 5), new Vector(0, 0, -1), 10.0);
        assertNull(hit, "луч прошёл мимо куба");
    }

    @Test
    void slabClipsToTheBox() {
        ModelShape shape = ModelShape.box(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        Double front = ModelBoxes.slab(shape, new Vector(0, 0, 3), new Vector(0, 0, -1), 5.0);
        assertNotNull(front);
        assertEquals(2.5, front, 1e-6, "вход в куб");
        assertNull(ModelBoxes.slab(shape, new Vector(3, 3, 3), new Vector(0, 0, -1), 5.0),
                "параллельный луч мимо куба");
    }

    @Test
    void orientedBoxesSeparateWhenFarApart() {
        ModelDefinition def = cube(1.0);
        ModelBoxes.OrientedBox a = ModelBoxes.obb(at(0, 0, 0, 0f), 0f, 0f, def);
        ModelBoxes.OrientedBox b = ModelBoxes.obb(at(3, 0, 0, 0f), 0f, 0f, def);
        assertFalse(a.overlaps(b), "далеко стоящие коробки не пересекаются");
        ModelBoxes.OrientedBox near = ModelBoxes.obb(at(0.4, 0, 0, 0f), 0f, 0f, def);
        assertTrue(a.overlaps(near), "близкие коробки пересекаются");
    }

    @Test
    void rotatedBoxesDoNotOverlapThemselves() {
        ModelDefinition def = new ModelDefinition("тест", "minecraft:paper", 0, Set.of("FLOOR"),
                0, 0, 0, true, 2, 0.4, 0.4,
                List.of(ModelShape.box(-1.0, -0.2, -0.2, 1.0, 0.2, 0.2)),
                0, 0, 0, 0f, 0f, 0f, true, List.of());
        ModelBoxes.OrientedBox a = ModelBoxes.obb(at(0, 0, 0, 0f), 0f, 0f, def);
        ModelBoxes.OrientedBox same = ModelBoxes.obb(at(0, 0, 0, 0f), 0f, 0f, def);
        assertTrue(a.overlaps(same), "коробка пересекается сама с собой");
        ModelBoxes.OrientedBox turned = ModelBoxes.obb(at(0, 0, 0, 90f), 90f, 0f, def);
        assertTrue(a.overlaps(turned), "перекрещенные коробки пересекаются");
        ModelBoxes.OrientedBox far = ModelBoxes.obb(at(4, 0, 4, 0f), 0f, 0f, def);
        assertFalse(a.overlaps(far), "коробки в разных углах не пересекаются");
    }

    @Test
    void hitboxOffsetMovesTheShape() {
        ModelDefinition base = cube(1.0);
        ModelDefinition shifted = new ModelDefinition(base.key(), base.displayItem(),
                base.customModelData(), base.faces(), 0, 0, 0, true,
                base.hitboxW(), base.hitboxH(), base.hitboxD(), base.shapes(),
                0.5, 0, 0, 0f, 0f, 0f, true, List.of());
        BoundingBox plain = ModelBoxes.obb(at(0, 0, 0, 0f), 0f, 0f, base).aabb();
        BoundingBox moved = ModelBoxes.obb(at(0, 0, 0, 0f), 0f, 0f, shifted).aabb();
        assertEquals(plain.getCenterX() - 0.5, moved.getCenterX(), 1e-6, "сдвиг хитбокса по X");
    }

    @Test
    void elementPointAppliesPartRotation() {
        ModelDefinition def = cube(1.0);
        ModelShape shape = new ModelShape(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5,
                0, 0, 0, 2, 90f);
        Vector point = ModelBoxes.elementPoint(def, shape, 0.5, 0, 0);
        assertEquals(0.0, point.getX(), 1e-6, "точка повернулась на 90° по Y (X)");
        assertEquals(0.5, point.getZ(), 1e-6, "точка повернулась на 90° по Y (Z)");
    }

    @Test
    void displayRotationKeepsYawDirection() {
        Vector forward = ModelBoxes.rotateToWorld(0f, 0f, 0, 0, 1);
        assertEquals(0.0, forward.getX(), 1e-6, "нулевой yaw не крутит X");
        Vector back = ModelBoxes.rotateToLocal(0f, 0f, forward.getX(), forward.getY(), forward.getZ());
        assertEquals(1.0, back.getZ(), 1e-6, "обратное преобразование возвращает Z");
    }

    @Test
    void overlapsAabbSeesBlocks() {
        ModelDefinition def = cube(1.0);
        BoundingBox inside = new BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        assertTrue(ModelBoxes.overlapsAabb(at(0, 0, 0, 0f), 0f, 0f, def, inside),
                "блок внутри модели пересекается");
        BoundingBox far = new BoundingBox(5.0, 5.0, 5.0, 6.0, 6.0, 6.0);
        assertFalse(ModelBoxes.overlapsAabb(at(0, 0, 0, 0f), 0f, 0f, def, far),
                "далёкий блок не пересекается");
        assertFalse(ModelBoxes.overlapsAabb(at(0, 0, 0, 0f), 0f, 0f, def, null),
                "null-коробка не пересекается");
    }

    @Test
    void overlapBetweenModelsSeesTheirShapes() {
        ModelDefinition def = cube(1.0);
        assertTrue(ModelBoxes.overlapsModel(at(0, 0, 0, 0f), 0f, 0f, def,
                        at(0.5, 0, 0, 0f), 0f, 0f, def),
                "две модели вплотную пересекаются");
        assertFalse(ModelBoxes.overlapsModel(at(0, 0, 0, 0f), 0f, 0f, def,
                        at(1.2, 0, 0, 0f), 0f, 0f, def),
                "модели через зазор не пересекаются");
    }
}
