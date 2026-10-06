package ru.hahgps.update;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class BuildToken {

    public static final String ENTRY = "hahtojer.token";

    private static final String OWN = readOwn();

    private BuildToken() {
    }

    public static String own() {
        return OWN;
    }

    public static boolean enabled() {
        return !OWN.isEmpty();
    }

    public static void verify(Path jar) throws IOException {
        if (OWN.isEmpty()) {
            // Fail-closed: без токена в собственной сборке подлинность скачанного
            // файла проверить нечем, и «пропустить проверку» здесь нельзя.
            throw new IOException("в этой сборке нет " + ENTRY
                    + " — подлинность скачанного файла проверить нечем, обновление запрещено");
        }
        String got;
        try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            got = readToken(zip);
        }
        if (got.isEmpty()) {
            throw new IOException("в скачанном файле нет " + ENTRY + " — это не наш плагин");
        }
        if (!OWN.equals(got)) {
            throw new IOException("токен сборки в скачанном файле не совпадает — это не наш плагин");
        }
    }

    static String readToken(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(ENTRY);
        if (entry == null) return "";
        try (InputStream in = zip.getInputStream(entry)) {
            return clean(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String readOwn() {
        try (InputStream in = BuildToken.class.getClassLoader().getResourceAsStream(ENTRY)) {
            if (in == null) return "";
            return clean(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    public static String tokenOf(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            return readToken(zip);
        }
    }

    private static String clean(String text) {
        String out = text.replace("\uFEFF", "");
        int end = out.indexOf('\n');
        if (end >= 0) out = out.substring(0, end);
        return out.trim();
    }
}
