package ru.hahgps.update;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Дефолты канала пререлизов в поставляемом config.yml.
 *
 * <p>Релизные серверы получают обновления по тому же каналу, что и тестовые, поэтому
 * {@code пререлизы.включено} обязано оставаться {@code true}: выключать канал можно только
 * в локальном тестовом конфиге, а не в том, что уезжает в jar.</p>
 */
class PrereleaseDefaultsTest {

    private static YamlConfiguration config() throws Exception {
        try (InputStream in = PrereleaseDefaultsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml не найден в classpath");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void prereleaseChannelIsOnByDefault() throws Exception {
        YamlConfiguration cfg = config();
        assertTrue(cfg.getBoolean("пререлизы.включено", false),
                "в поставляемом config.yml пререлизы должны быть включены: релизные серверы живут "
                        + "на этом же канале, выключенный дефолт оставит их без обновлений");
    }

    @Test
    void prereleaseChannelPointsAtOurServer() throws Exception {
        YamlConfiguration cfg = config();
        String address = cfg.getString("пререлизы.адрес", "");
        assertTrue(address.startsWith("https://"), "адрес пререлизов должен быть https: " + address);
        assertTrue(!address.endsWith("/"), "адрес без слэша на конце: " + address);
        assertTrue("hahgps".equals(cfg.getString("пререлизы.проект", "")),
                "проект пререлизов должен быть hahgps");
    }

    @Test
    void prereleaseKeyFileIsNotTheReleaseToken() throws Exception {
        YamlConfiguration cfg = config();
        String devKey = cfg.getString("пререлизы.файл-ключа", "");
        assertTrue(!devKey.isBlank(), "не задан файл ключа пререлизов");
        assertTrue(!"github-token.txt".equals(devKey),
                "ключ пререлизов не должен лежать в файле токена GitHub: " + devKey);
    }
}
