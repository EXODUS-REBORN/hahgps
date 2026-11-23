package ru.hahgps.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Публикация пака в каталог веб-раздачи (nginx + Cloudflare Tunnel):
 * файл обновляется только при изменении байтов, вложенные каталоги создаются.
 */
class ResourcePackPublishTest {

    @TempDir
    Path dir;

    @Test
    void writesPackAndSkipsIdenticalBytes() throws Exception {
        byte[] data = "pk".getBytes(StandardCharsets.UTF_8);
        Path target = dir.resolve("hahgps.zip");

        assertTrue(ResourcePackService.writeIfDifferent(target.toFile(), data));
        assertArrayEquals(data, Files.readAllBytes(target));

        // Повторная публикация тех же байтов файл не трогает: иначе меняется
        // mtime и nginx/Cloudflare отдают пак как новый.
        long modified = Files.getLastModifiedTime(target).toMillis();
        assertFalse(ResourcePackService.writeIfDifferent(target.toFile(), data));
        assertEquals(modified, Files.getLastModifiedTime(target).toMillis());

        // Другие байты — файл обновляется.
        assertTrue(ResourcePackService.writeIfDifferent(target.toFile(), new byte[]{1, 2, 3}));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target));
    }

    @Test
    void createsMissingDirectories() throws Exception {
        Path target = dir.resolve("a/b/hahgps.zip");
        assertTrue(ResourcePackService.writeIfDifferent(target.toFile(), new byte[]{7}));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(target));
    }

    @Test
    void publicUrlPointsToOurTunnel() {
        assertTrue(ResourcePackService.PUBLIC_URL.startsWith("https://"),
                "пак должен качаться по HTTPS");
        assertTrue(ResourcePackService.PUBLIC_URL.endsWith("/hahgps.zip"),
                "ссылка должна вести на hahgps.zip");
    }
}
