package ru.hahgps.util;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import ru.hahgps.model.TrackerColor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Русские подписи носителей: трекер на лодке должен называться «Лодка», а не
 * «OAK_BOAT», и цвет трекера берётся по типу носителя. Если после обновления
 * Paper API появится новый тип сущности, тест упадёт — его нужно перевести,
 * иначе в игре снова окажется английское имя.
 */
class EntityNamesTest {

    @Test
    void everyEntityTypeHasRussianName() {
        for (EntityType type : EntityType.values()) {
            if (type == EntityType.UNKNOWN) {
                continue;
            }
            assertTrue(EntityNames.hasName(type), "нет русского имени: " + type);
            assertFalse(EntityNames.name(type).matches(".*[A-Za-z].*"),
                    "английские буквы в имени " + type + ": " + EntityNames.name(type));
        }
    }

    @Test
    void boatsAndAnimalsAreRussian() {
        assertEquals("Лодка", EntityNames.name(EntityType.OAK_BOAT));
        assertEquals("Лодка", EntityNames.name(EntityType.DARK_OAK_BOAT));
        assertEquals("Лодка с сундуком", EntityNames.name(EntityType.OAK_CHEST_BOAT));
        assertEquals("Плот", EntityNames.name(EntityType.BAMBOO_RAFT));
        assertEquals("Лошадь", EntityNames.name(EntityType.HORSE));
        assertEquals("Свинья", EntityNames.name(EntityType.PIG));
        assertEquals("Вагонетка", EntityNames.name(EntityType.MINECART));
        assertEquals("Стойка для брони", EntityNames.name(EntityType.ARMOR_STAND));
        assertEquals("Игрок", EntityNames.name(EntityType.PLAYER));
        assertEquals(EntityNames.FALLBACK, EntityNames.name(EntityType.UNKNOWN));
        assertEquals(EntityNames.FALLBACK, EntityNames.name(null));
    }

    @Test
    void colorComesFromCarrierType() {
        assertEquals(TrackerColor.BLUE, EntityNames.color(EntityType.OAK_BOAT));
        assertEquals(TrackerColor.YELLOW, EntityNames.color(EntityType.HORSE));
        assertEquals(TrackerColor.GREEN, EntityNames.color(EntityType.PIG));
        assertEquals(TrackerColor.CYAN, EntityNames.color(EntityType.MINECART));
        assertEquals(TrackerColor.RED, EntityNames.color(EntityType.ZOMBIE));
        assertEquals(TrackerColor.PURPLE, EntityNames.color(EntityType.VILLAGER));
        assertEquals(TrackerColor.ORANGE, EntityNames.color(EntityType.WOLF));
        // У декораций своего цвета нет — трекер остаётся цветом предмета.
        assertNull(EntityNames.color(EntityType.ARMOR_STAND));
        assertNull(EntityNames.color(EntityType.ITEM_FRAME));
        assertNull(EntityNames.color(null));
    }
}
