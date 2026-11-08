package ru.hahgps.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderStateTest {

    @Test
    void openLoaderIsAlive(@TempDir Path dir) throws IOException {
        URLClassLoader loader = loader(dir);
        try {
            assertFalse(LoaderState.closed(loader), "живой загрузчик отдаёт plugin.yml");
        } finally {
            loader.close();
        }
    }

    @Test
    void closedLoaderIsDetected(@TempDir Path dir) throws IOException {
        URLClassLoader loader = loader(dir);
        assertFalse(LoaderState.closed(loader));
        loader.close();
        assertTrue(LoaderState.closed(loader),
                "после close() ресурсы не читаются — как у PluginClassLoader после disablePlugin");
    }

    @Test
    void nullLoaderIsNotTreatedAsClosed() {
        assertFalse(LoaderState.closed(null), "проверять нечего — не мешаем подмене");
    }

    @Test
    void loaderWithoutAnchorsCountsAsClosed() throws IOException {
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            assertTrue(LoaderState.closed(empty), "ни одного нашего ресурса — считаем закрытым");
        }
    }

    @Test
    void zipClosedIsRecognisedByMessage() {
        assertTrue(LoaderState.zipClosed(new IllegalStateException("zip file closed")));
        assertTrue(LoaderState.zipClosed(new ClassNotFoundException("x",
                new IllegalStateException("ZIP file closed"))));
        assertFalse(LoaderState.zipClosed(new IllegalStateException("другая ошибка")));
        assertFalse(LoaderState.zipClosed(null));
    }

    private static URLClassLoader loader(Path dir) throws IOException {
        Path jar = dir.resolve("plugin.jar");
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry("plugin.yml"));
            jarOut.write("name: HahGps\nmain: ru.hahgps.HahGpsPlugin\n"
                    .getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
            jarOut.putNextEntry(new JarEntry("hahtojer.token"));
            jarOut.write("00".getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
        return new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
    }
}
