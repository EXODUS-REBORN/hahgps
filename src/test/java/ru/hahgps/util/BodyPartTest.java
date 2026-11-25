package ru.hahgps.util;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import ru.hahgps.model.BodyPart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Часть тела — это не украшение: по ней видно, куда именно прицепили трекер,
 * и она должна одинаково считаться и для игрока, и для зомби, и для животного.
 * Проверяются и доли роста, и стороны (левая/правая — со стороны носителя),
 * и геометрия луча по хитбоксу.
 */
class BodyPartTest {

    /** Хитбокс игрока: ширина 0.6, рост 1.8, ноги на нуле. */
    private static final BoundingBox PLAYER = new BoundingBox(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3);

    @Test
    void proportionsSplitHeadTorsoArmsAndLegs() {
        assertEquals(BodyPart.HEAD, BodyPart.of(0.95, 0.0));
        assertEquals(BodyPart.HEAD, BodyPart.of(0.82, -0.9));
        assertEquals(BodyPart.CHEST, BodyPart.of(0.70, 0.0));
        assertEquals(BodyPart.CHEST, BodyPart.of(0.50, 0.4));
        assertEquals(BodyPart.LEFT_ARM, BodyPart.of(0.60, -0.9));
        assertEquals(BodyPart.RIGHT_ARM, BodyPart.of(0.60, 0.9));
        assertEquals(BodyPart.LEFT_LEG, BodyPart.of(0.20, -0.5));
        assertEquals(BodyPart.RIGHT_LEG, BodyPart.of(0.20, 0.5));
    }

    @Test
    void keyRoundTripsAndMessageKeyIsStable() {
        for (BodyPart part : BodyPart.values()) {
            assertEquals(part, BodyPart.parse(part.getKey()));
            assertEquals(part, BodyPart.parse(part.name()));
            assertEquals("itrak-part-" + part.getKey(), part.messageKey());
        }
        assertNull(BodyPart.parse(null));
        assertNull(BodyPart.parse("  "));
        assertNull(BodyPart.parse("хвост"));
    }

    @Test
    void rayHittingTheHeadResolvesToHead() {
        // Глаза на 1.8, целимся чуть ниже макушки в переднюю грань хитбокса.
        BodyPart part = BodyPartResolver.resolve(
                new Vector(0, 1.8, -3), new Vector(0, -0.03, 1).normalize(),
                PLAYER, 0f);
        assertEquals(BodyPart.HEAD, part);
    }

    @Test
    void sidesAreRelativeToTheCarrierNotToTheHitter() {
        // Носитель смотрит на юг (+Z). Его левая рука — на восток (+X).
        BodyPart left = BodyPartResolver.resolve(
                new Vector(0.3, 1.1, -3), new Vector(0.3, 1.1, -0.3).subtract(new Vector(0.3, 1.1, -3)).normalize(),
                PLAYER, 0f);
        assertEquals(BodyPart.LEFT_ARM, left);

        BodyPart right = BodyPartResolver.resolve(
                new Vector(-0.3, 1.1, -3),
                new Vector(-0.3, 1.1, -0.3).subtract(new Vector(-0.3, 1.1, -3)).normalize(),
                PLAYER, 0f);
        assertEquals(BodyPart.RIGHT_ARM, right);

        // Тот же удар, но носитель развернулся на 180°: стороны меняются местами.
        BodyPart turned = BodyPartResolver.resolve(
                new Vector(0.3, 1.1, -3),
                new Vector(0.3, 1.1, -0.3).subtract(new Vector(0.3, 1.1, -3)).normalize(),
                PLAYER, 180f);
        assertEquals(BodyPart.RIGHT_ARM, turned);
    }

    @Test
    void rayStartingInsideTheBoxFallsBackToTheNearestPoint() {
        // Клик «изнутри» хитбокса не должен считаться промахом.
        BodyPart part = BodyPartResolver.resolve(new Vector(0, 1.1, 0), new Vector(0, 0, 1), PLAYER, 0f);
        assertEquals(BodyPart.CHEST, part);
    }

    @Test
    void intersectionMissesWhenBoxIsBehind() {
        assertNull(BodyPartResolver.intersect(new Vector(0, 1, 5), new Vector(0, 0, 1), PLAYER));
        assertTrue(BodyPartResolver.intersect(new Vector(0, 1, -5), new Vector(0, 0, 1), PLAYER) != null);
    }
}
