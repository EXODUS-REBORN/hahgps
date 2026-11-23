package ru.hahgps;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ресурспак едет внутри jar: команда /gps pack читает именно этот файл.
 * Если пак забыли пересобрать, сервер раздал бы старую версию — тест ловит
 * и отсутствие файла, и потерю ключевых ассетов.
 */
class ResourcePackBundleTest {

    @Test
    void bundledPackContainsFontModelsAndVanillaSafeFallback() throws Exception {
        Path pack = Path.of("src", "main", "resources", "hahgps.zip");
        assertTrue(Files.isRegularFile(pack),
                "нет src/main/resources/hahgps.zip — соберите пак: python3 respack/build.py");

        Set<String> entries = new HashSet<>();
        try (InputStream in = Files.newInputStream(pack);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }

        assertTrue(entries.contains("pack.mcmeta"), "в паке нет pack.mcmeta");
        assertTrue(entries.contains("pack.png"), "в паке нет pack.png");
        assertTrue(entries.contains("assets/hahgps/font/arrow.json"), "нет шрифта стрелок");
        assertTrue(entries.contains("assets/hahgps/textures/font/arrow.png"), "нет атласа глифов");
        assertTrue(entries.contains("assets/hahgps/models/item/gui_settings.json"),
                "нет иконки настроек");
        assertTrue(entries.contains("assets/hahgps/models/item/gui_category.json"),
                "нет иконки категории");
        assertTrue(entries.contains("assets/hahgps/textures/gui/category.png"),
                "нет текстуры иконки категории");
        assertTrue(entries.contains("assets/hahgps/models/item/tracker_blue.json"),
                "нет 3D-трекера");
        assertTrue(entries.contains("assets/hahgps/models/item/tracker_blue_wp.json"),
                "нет водонепроницаемого трекера");
        assertTrue(entries.contains("assets/hahgps/models/item/locator.json"),
                "нет 3D-локатора");
        assertTrue(entries.contains("assets/hahgps/models/item/locator_long.json"),
                "нет дальнего локатора");
        assertTrue(entries.contains("assets/hahgps/textures/item/tracker_blue.png"),
                "нет текстуры трекера");
        assertTrue(entries.contains("assets/minecraft/items/paper.json"),
                "нет диспетчера CMD для иконок поиска");
        assertTrue(entries.contains("assets/minecraft/items/lapis_lazuli.json"),
                "нет диспетчера CMD для синего трекера");
        assertTrue(entries.contains("assets/minecraft/items/recovery_compass.json"),
                "нет диспетчера CMD для локаторов");
        assertFalse(entries.contains("assets/minecraft/items/oak_sign.json"),
                "знаки маршрута убраны — диспетчер oak_sign не должен возвращаться");
        assertFalse(entries.stream().anyMatch(e -> e.contains("arrow_sign_")
                        || e.contains("arrow_block_")),
                "знаки маршрута убраны — моделей и текстур знаков быть не должно");
    }
}
