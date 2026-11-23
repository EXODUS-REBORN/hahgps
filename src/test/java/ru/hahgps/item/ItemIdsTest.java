package ru.hahgps.item;

import org.junit.jupiter.api.Test;
import ru.hahgps.model.TrackerColor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Кастомные ID моделей — стабильный контракт для ресурспака: два предмета с
 * одним номером нарисовать по-разному невозможно, а поменять выпущенный номер
 * нельзя. Тест ловит случайные совпадения.
 */
class ItemIdsTest {

    @Test
    void everyConstantHasItsOwnModelId() throws Exception {
        Set<Integer> seen = new HashSet<>();
        for (Field field : ItemIds.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) {
                continue;
            }
            int value = field.getInt(null);
            assertTrue(seen.add(value), "ID модели повторяется: " + field.getName() + " = " + value);
        }
        assertTrue(seen.size() > 40, "моделей слишком мало: " + seen.size());
    }

    @Test
    void trackerModelIdsCoverEveryColorAndWaterproofFlag() {
        Set<Integer> ids = new HashSet<>();
        for (TrackerColor color : TrackerColor.values()) {
            int regular = ItemIds.tracker(color, false);
            int waterproof = ItemIds.tracker(color, true);
            assertTrue(regular >= ItemIds.TRACKER_BASE);
            assertTrue(waterproof >= ItemIds.TRACKER_BASE);
            assertTrue(ids.add(regular), "дубль обычного трекера: " + color);
            assertTrue(ids.add(waterproof), "дубль водонепроницаемого: " + color);
        }
        assertEquals(TrackerColor.values().length * 2, ids.size());
    }

    @Test
    void collectionsExposeAllTrackerIds() {
        Set<Integer> all = ItemIds.all();
        for (TrackerColor color : TrackerColor.values()) {
            assertTrue(all.contains(ItemIds.tracker(color, false)));
            assertTrue(all.contains(ItemIds.tracker(color, true)));
        }
        assertTrue(all.contains(ItemIds.ITRAK_LOCATOR));
        assertTrue(all.contains(ItemIds.ITRAK_LOCATOR_LONG));
    }

    @Test
    void routeSignRangeIsGoneAndStaysFree() {
        Set<Integer> all = ItemIds.all();
        for (int id = 1800; id < 2000; id++) {
            assertTrue(!all.contains(id), "номер из диапазона знаков занят: " + id);
        }
    }
}
