package ru.hahgps.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Токен сборки: один и тот же во всех версиях плагина, лежит внутри jar файлом
 * {@code hahtojer.token}. Ни в конфиге, ни в настройках его нет — он нужен только для того,
 * чтобы скачанный файл действительно был нашим плагином, а не обрезанной закачкой, страницей
 * ошибки прокси или чужим jar под нашим именем.
 *
 * <p>Схема простая: плагин берёт токен из собственного classpath, у скачанного jar читает тот же
 * файл и сравнивает. Токен одинаковый во всех выпусках — новую версию не нужно «перевыпускать»
 * с новым токеном, он просто лежит в репозитории и попадает в каждый jar сам.
 *
 * <p>Проверка не про «кто-то подменил файл» (репозиторий и токен доступа — под контролем
 * владельца), а про «файл скачался целиком и это наш плагин». Размер файла для этого не годится:
 * он меняется от версии к версии, и порог рано или поздно мешает обновлению.
 *
 * <p>Версии, выпущенные до появления токена, проверку не выполняют (своего токена у них нет) —
 * иначе было бы невозможно на них обновиться. Как только стоит версия с токеном, проверка
 * включается для всех дальнейших скачиваний.
 *
 * <p>Класс чистый: только {@code java.io} и {@code java.util.zip}.
 */
public final class BuildToken {

    /** Файл с токеном внутри jar. Имя историческое (общее с hahtojer) — не переименовывать. */
    public static final String ENTRY = "hahtojer.token";

    /** Токен этой сборки (из собственного jar). Пусто у версий, выпущенных до появления токена. */
    private static final String OWN = readOwn();

    private BuildToken() {
    }

    /** Токен текущей сборки; пустая строка — сборка без токена (проверка не выполняется). */
    public static String own() {
        return OWN;
    }

    /** Проверка включена: у этой версии есть токен. */
    public static boolean enabled() {
        return !OWN.isEmpty();
    }

    /**
     * Проверяет скачанный jar. Бросает {@link IOException}, если это не наш плагин.
     * У сборки без токена проверка молча пропускается — иначе со старой версии нельзя было бы
     * обновиться.
     */
    public static void verify(Path jar) throws IOException {
        if (OWN.isEmpty()) return;
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

    /** Токен из zip-архива: пустая строка, если файла нет или он пустой. */
    static String readToken(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(ENTRY);
        if (entry == null) return "";
        try (InputStream in = zip.getInputStream(entry)) {
            return clean(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Токен этой сборки из classpath; пустая строка, если ресурса нет. */
    private static String readOwn() {
        try (InputStream in = BuildToken.class.getClassLoader().getResourceAsStream(ENTRY)) {
            if (in == null) return "";
            return clean(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    /** Токен из произвольного jar-файла — для диагностики. */
    public static String tokenOf(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            return readToken(zip);
        }
    }

    /** Первая строка без BOM и хвостовых пробелов (файл могли переписать блокнотом). */
    private static String clean(String text) {
        String out = text.replace("\uFEFF", "");
        int end = out.indexOf('\n');
        if (end >= 0) out = out.substring(0, end);
        return out.trim();
    }
}
