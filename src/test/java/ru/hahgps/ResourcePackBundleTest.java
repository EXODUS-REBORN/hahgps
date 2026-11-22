package ru.hahgps;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
        assertTrue(entries.contains("assets/hahgps/models/item/arrow_sign_straight.json"),
                "нет 3D-предмета");
        assertTrue(entries.contains("assets/hahgps/models/block/arrow_block_straight.json"),
                "нет 3D-блока");
        assertTrue(entries.contains("assets/minecraft/items/oak_sign.json"),
                "нет диспетчера CMD для oak_sign");
    }
}
