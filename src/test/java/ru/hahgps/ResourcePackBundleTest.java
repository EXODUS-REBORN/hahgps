package ru.hahgps;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ресурспак едет внутри jar: команда /gps pack читает именно этот файл.
 * Если пак забыли пересобрать, сервер раздал бы старую версию — тест ловит
 * и отсутствие файла, и потерю ключевых ассетов, и битые ссылки на текстуры
 * (клиент рисует их фиолетово-чёрной клеткой).
 */
class ResourcePackBundleTest {

    @Test
    void bundledPackContainsFontModelsAndVanillaSafeFallback() throws Exception {
        Set<String> entries = entries();

        assertTrue(entries.contains("pack.mcmeta"), "в паке нет pack.mcmeta");
        assertTrue(entries.contains("pack.png"), "в паке нет pack.png");
        assertTrue(entries.contains("assets/hahgps/font/arrow.json"), "нет шрифта стрелок");
        assertTrue(entries.contains("assets/hahgps/textures/font/arrow.png"), "нет атласа глифов");
        assertTrue(entries.contains("assets/hahgps/models/item/gui_settings.json"),
                "нет иконки настроек");
        assertTrue(entries.contains("assets/hahgps/models/item/gui_category.json"),
                "нет иконки категории");
        assertTrue(entries.contains("assets/hahgps/textures/item/gui_category.png"),
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

    /**
     * 3D-иконки главного меню рисуются по переменным («#map_side»): если
     * пак пересобрали без их текстур, в слоте была фиолетово-чёрная клетка.
     */
    @Test
    void mainMenuIconsHaveAllTextures() throws Exception {
        Set<String> entries = entries();
        for (String name : List.of(
                "main_map_top", "main_map_side", "main_map_back",
                "main_compass_face", "main_compass_side",
                "main_pin", "main_pin_top",
                "main_catalog_glass", "main_catalog_base",
                "main_catalog_band", "main_catalog_plate",
                "main_tag_ring")) {
            String path = "assets/hahgps/textures/item/" + name + ".png";
            assertTrue(entries.contains(path), "нет текстуры 3D-иконки меню: " + path);
        }
    }

    /** Ни одна модель пака не должна ссылаться на отсутствующую текстуру. */
    @Test
    void everyModelTextureReferenceResolves() throws Exception {
        Map<String, String> models = new HashMap<>();
        Set<String> entries = new HashSet<>();
        try (InputStream in = Files.newInputStream(pack());
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
                if (entry.getName().startsWith("assets/")
                        && entry.getName().contains("/models/")
                        && entry.getName().endsWith(".json")) {
                    models.put(entry.getName(),
                            new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        assertFalse(models.isEmpty(), "в паке нет моделей");

        Set<String> problems = new TreeSet<>();
        for (Map.Entry<String, String> model : models.entrySet()) {
            JsonObject root = JsonParser.parseString(model.getValue()).getAsJsonObject();
            Set<String> declared = new HashSet<>();
            JsonObject textures = root.getAsJsonObject("textures");
            if (textures != null) {
                for (Map.Entry<String, JsonElement> texture : textures.entrySet()) {
                    declared.add(texture.getKey());
                    String value = texture.getValue().getAsString();
                    if (!value.startsWith("#")) {
                        if (!hasTexture(entries, value)) {
                            problems.add(model.getKey() + ": нет текстуры " + value);
                        } else if (!inItemAtlas(value)) {
                            problems.add(model.getKey() + ": текстура " + value
                                    + " вне атласа предметов (не textures/item и не textures/block)");
                        }
                    }
                }
            }
            for (String ref : faceRefs(root)) {
                if (!declared.contains(ref.substring(1))) {
                    problems.add(model.getKey() + ": нет переменной " + ref);
                }
            }
        }
        assertTrue(problems.isEmpty(), "битые ссылки в ресурспаке: " + problems);
    }

    private static Path pack() {
        Path pack = Path.of("src", "main", "resources", "hahgps.zip");
        assertTrue(Files.isRegularFile(pack),
                "нет src/main/resources/hahgps.zip — соберите пак: python3 respack/build.py");
        return pack;
    }

    private static Set<String> entries() throws Exception {
        Set<String> entries = new HashSet<>();
        try (InputStream in = Files.newInputStream(pack());
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        return entries;
    }

    private static boolean hasTexture(Set<String> entries, String ref) {
        int colon = ref.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : ref.substring(0, colon);
        String path = colon < 0 ? ref : ref.substring(colon + 1);
        if ("minecraft".equals(namespace)) {
            return true;
        }
        return entries.contains("assets/" + namespace + "/textures/" + path + ".png");
    }

    /**
     * Атлас предметов Minecraft собирается из каталогов textures/item и
     * textures/block. Текстура из другого каталога в атлас не попадает, и
     * модель рисуется фиолетово-чёрной клеткой — так сломались иконки меню,
     * лежавшие в textures/gui.
     */
    private static boolean inItemAtlas(String ref) {
        int colon = ref.indexOf(':');
        String path = colon < 0 ? ref : ref.substring(colon + 1);
        return path.startsWith("item/") || path.startsWith("block/");
    }

    private static List<String> faceRefs(JsonElement node) {
        List<String> refs = new ArrayList<>();
        if (node.isJsonObject()) {
            for (Map.Entry<String, JsonElement> field : node.getAsJsonObject().entrySet()) {
                if ("textures".equals(field.getKey())) {
                    continue;
                }
                if ("texture".equals(field.getKey()) && field.getValue().isJsonPrimitive()
                        && field.getValue().getAsString().startsWith("#")) {
                    refs.add(field.getValue().getAsString());
                } else {
                    refs.addAll(faceRefs(field.getValue()));
                }
            }
        } else if (node.isJsonArray()) {
            for (JsonElement item : node.getAsJsonArray()) {
                refs.addAll(faceRefs(item));
            }
        }
        return refs;
    }
}
