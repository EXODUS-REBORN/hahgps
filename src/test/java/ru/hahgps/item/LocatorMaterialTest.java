package ru.hahgps.item;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import ru.hahgps.model.TrackerColor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Локатор не должен быть компасом: {@code minecraft:compass} — это
 * «навигационная палочка» WorldEdit по умолчанию, и её ПКМ/ЛКМ телепортируют
 * игрока к блоку под прицелом (вниз — внутрь земли). Тест держит контракт.
 */
class LocatorMaterialTest {

    @Test
    void noTrackerSharesTheLocatorMaterial() {
        for (TrackerColor color : TrackerColor.values()) {
            assertNotEquals(ItemFactory.LOCATOR_MATERIAL, color.getMaterial(),
                    "трекер " + color.getKey() + " — тот же материал, что у локатора: "
                            + "белая полоса КД локатора снова появится на маячке");
        }
    }

    @Test
    void locatorIsNotACompass() {
        assertEquals(Material.RECOVERY_COMPASS, ItemFactory.LOCATOR_MATERIAL,
                "локатор снова стал компасом — он совпадёт с навигационной палочкой WorldEdit");
        assertEquals(Material.COMPASS, ItemFactory.LEGACY_LOCATOR_MATERIAL);
        assertNotEquals(ItemFactory.LEGACY_LOCATOR_MATERIAL, ItemFactory.LOCATOR_MATERIAL);
    }
}
