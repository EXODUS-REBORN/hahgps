package ru.hahgps.update;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import ru.hahgps.HahGpsPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Автообновление плагина из GitHub Releases приватного репозитория.
 *
 * <p>Как работает: раз в N минут (асинхронно, сервер не тормозит) спрашиваем GitHub API
 * о свежем релизе; если тег новее установленной версии — скачиваем jar в
 * {@code plugins/hahgps/update/} и применяем: либо на ходу ({@link HotSwap}), либо при
 * следующем выключении сервера (подменяем файл плагина). Никаких сторонних плагинов и библиотек.
 *
 * <p>Доступ к приватному репозиторию — по токену. Токен читается по порядку:
 * переменная окружения {@code HAHTOJER_GITHUB_TOKEN} → файл {@code plugins/hahgps/github-token.txt}
 * → файл {@code plugins/hahtojer/github-token.txt} (токен основного плагина — общий для обоих,
 * отдельный заводить не нужно) → ключ {@code обновление.ключ} в config.yml. Рекомендуется файл:
 * config.yml чаще попадает в чужие руки (скриншоты, копии), а файл лежит рядом с базой и в git
 * не уезжает. Нужен fine-grained токен с правом <b>Contents: Read-only</b> на этот репозиторий.
 */
public final class UpdaterService {

    /** Один релиз GitHub, из которого можно взять jar. */
    public record Release(String tag, String version, String assetName, long assetId,
                          boolean prerelease) {
    }

    private static final String API = "https://api.github.com";

    /** Шаблон файла с ключом доступа: лежит в jar, копируется в папку плагина при первом запуске. */
    private static final String TOKEN_TEMPLATE = "github-token.example.txt";

    /** Подсказка-заглушка в шаблоне: пока она на месте, считаем, что токена нет. */
    public static final String TOKEN_PLACEHOLDER = "ВСТАВЬ_ТОКЕН_СЮДА";

    /** Папка основного плагина, у которого можно взять готовый токен. */
    private static final String MAIN_PLUGIN_FOLDER = "hahtojer";

    /** Префикс имени ассета с релизом и имени собственного jar. */
    static final String JAR_PREFIX = "hahgps";

    private final HahGpsPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER) // редирект на S3 обрабатываем вручную — без токена
            .build();

    private boolean enabled = true;
    private String repo = "";
    private boolean allowPrerelease;
    private long intervalMinutes = 5;
    private String tokenFile = "github-token.txt";
    private boolean autoDownload = true;
    private boolean hotApply = true;
    private String token = "";
    private String api = API;

    private BukkitTask task;
    private volatile boolean busy;
    private volatile String lastError = "";
    private volatile long lastCheckMs;
    private volatile String latestVersion = "";
    private volatile String stagedVersion = "";
    private volatile Path stagedJar;

    /** Откуда взялся токен — для /gps update status. */
    private volatile String tokenSource = "";

    /** Последняя ошибка, о которой уже сказали в консоли: повтор той же не спамит лог. */
    private volatile String loggedError = "";

    /** Версия, о которой уже объявляли: с частыми проверками объявляем один раз на релиз. */
    private volatile String announcedVersion = "";

    /** Про заглушку вместо токена напоминаем один раз за запуск, а не на каждой проверке. */
    private volatile boolean remindedPlaceholder;

    public UpdaterService(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── настройки ────────────────────────────────────────────────────────────────

    public void reload() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("обновление.включено", true);
        repo = String.valueOf(cfg.getString("обновление.репозиторий", "EXODUS-REBORN/hahgps")).trim();
        allowPrerelease = cfg.getBoolean("обновление.предрелизы", false);
        intervalMinutes = Math.max(0, cfg.getLong("обновление.интервал-минут", 5));
        tokenFile = String.valueOf(cfg.getString("обновление.файл-ключа", "github-token.txt")).trim();
        autoDownload = cfg.getBoolean("обновление.авто-скачивание", true);
        hotApply = cfg.getBoolean("обновление.горячее-обновление", true);
        String custom = String.valueOf(cfg.getString("обновление.адрес-api", API)).trim();
        api = custom.isEmpty() ? API : custom.replaceAll("/+$", "");
        token = readToken();
    }

    /**
     * Токен доступа, по порядку: переменная окружения → файл в папке этого плагина →
     * файл в папке основного плагина {@code hahtojer} (токен общий на оба плагина) → config.yml.
     */
    private String readToken() {
        String env = System.getenv("HAHTOJER_GITHUB_TOKEN");
        if (env != null && !env.isBlank()) {
            tokenSource = "переменная окружения HAHTOJER_GITHUB_TOKEN";
            return normalizeToken(env);
        }

        boolean placeholder = false;
        if (!tokenFile.isBlank()) {
            File data = plugin.getDataFolder();
            String own = readTokenFrom(data, tokenFile);
            if (!own.isEmpty() && !isPlaceholder(own)) {
                tokenSource = "файл plugins/" + data.getName() + "/" + tokenFile;
                return own;
            }
            placeholder |= isPlaceholder(own);

            // Отдельного токена у hahgps нет — берём токен основного плагина hahtojer:
            // если он на сервере уже настроен, второй файл заводить не нужно.
            File parent = data.getParentFile();
            if (parent != null) {
                String shared = readTokenFrom(new File(parent, MAIN_PLUGIN_FOLDER), tokenFile);
                if (!shared.isEmpty() && !isPlaceholder(shared)) {
                    tokenSource = "файл plugins/" + MAIN_PLUGIN_FOLDER + "/" + tokenFile;
                    return shared;
                }
                placeholder |= isPlaceholder(shared);
            }
        }

        if (placeholder && !remindedPlaceholder) {
            remindedPlaceholder = true;
            plugin.getLogger().info("Обновление: в " + tokenFile + " пока заглушка — впиши туда токен "
                    + "GitHub (fine-grained, право Contents: Read-only), иначе проверка обновлений "
                    + "не работает. Токен основного плагина (plugins/" + MAIN_PLUGIN_FOLDER + "/"
                    + tokenFile + ") подошёл бы и здесь.");
        }

        String fromConfig = normalizeToken(plugin.getConfig().getString("обновление.ключ", ""));
        tokenSource = fromConfig.isEmpty() ? "" : "config.yml (обновление.ключ)";
        return fromConfig;
    }

    /**
     * Токен из файла в указанной папке данных: разбор один и тот же для своей папки и для папки
     * основного плагина. Пустая строка — файла нет, он пуст, в нём только комментарии или заглушка.
     */
    private String readTokenFrom(File dataFolder, String fileName) {
        if (dataFolder == null || fileName == null || fileName.isBlank()) return "";
        Path file = dataFolder.toPath().resolve(fileName);
        if (!Files.isReadable(file)) return "";
        try {
            return normalizeToken(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Обновление: не удалось прочитать " + file
                    + " (" + e.getMessage() + ")");
            return "";
        }
    }

    /**
     * Создаёт файл с ключом доступа, если его ещё нет: искать, куда вставлять токен, — не дело
     * администратора, поэтому плагин кладёт рядом готовую инструкцию и просит вписать токен в неё.
     * Файл в git не попадает и в лог не пишется.
     */
    public static void ensureTokenFile(HahGpsPlugin plugin) {
        String name = String.valueOf(plugin.getConfig()
                .getString("обновление.файл-ключа", "github-token.txt")).trim();
        if (name.isEmpty()) return;
        Path file = plugin.getDataFolder().toPath().resolve(name);
        if (Files.exists(file)) return;
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (InputStream in = plugin.getResource(TOKEN_TEMPLATE)) {
                if (in != null) {
                    Files.copy(in, file);
                } else {
                    Files.writeString(file, "# Токен GitHub (fine-grained, Contents: Read-only)\n"
                            + "# Можно не заполнять: если токен уже настроен у плагина "
                            + MAIN_PLUGIN_FOLDER + " (plugins/" + MAIN_PLUGIN_FOLDER + "/" + name
                            + "), hahgps возьмёт его оттуда.\n"
                            + TOKEN_PLACEHOLDER + "\n", StandardCharsets.UTF_8);
                }
            }
            plugin.getLogger().info("Обновление: создан plugins/" + plugin.getDataFolder().getName()
                    + "/" + name + " — впиши в него токен GitHub (fine-grained, право "
                    + "Contents: Read-only), иначе приватный репозиторий с релизами останется "
                    + "недоступен.");
        } catch (Exception e) {
            plugin.getLogger().warning("Обновление: не удалось создать " + file.getFileName()
                    + " (" + e.getMessage() + ")");
        }
    }

    /** В файле лежит подсказка-заглушка вместо токена — значит ключа нет. */
    static boolean isPlaceholder(String value) {
        return value != null && value.trim().equalsIgnoreCase(TOKEN_PLACEHOLDER);
    }

    /**
     * Приводит токен к «голому» виду. Файл люди создают руками (блокнот, копипаст), поэтому
     * снимаем BOM (блокнот Windows пишет его в UTF-8), кавычки, префиксы вида {@code token=} или
     * {@code Authorization: Bearer}, комментарии и пустые строки — берём первую значимую строку.
     */
    static String normalizeToken(String raw) {
        if (raw == null) return "";
        String value = raw.replace("\uFEFF", "").trim();
        if (value.isEmpty()) return "";
        String picked = null;
        for (String line : value.split("\\R")) {
            String candidate = line.replace("\uFEFF", "").trim();
            if (candidate.isEmpty() || candidate.startsWith("#")) continue;
            picked = candidate;
            break;
        }
        if (picked == null) return ""; // в файле только комментарии и пустые строки
        value = picked;
        if (value.regionMatches(true, 0, "Authorization:", 0, 14)) {
            value = value.substring(14).trim();
        }
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        if (value.regionMatches(true, 0, "token=", 0, 6)
                || value.regionMatches(true, 0, "ключ=", 0, 5)) {
            value = value.substring(value.indexOf('=') + 1).trim();
        }
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean hasToken() {
        return !token.isEmpty();
    }

    /** Горячая подмена включена: новая версия ставится и запускается без перезапуска сервера. */
    public boolean hotApply() {
        return hotApply;
    }

    /** Подготовленный (проверенный) jar или {@code null}. */
    public Path stagedJar() {
        return stagedJar;
    }

    public String stagedVersion() {
        return stagedVersion;
    }

    public String repository() {
        return repo;
    }

    // ── жизненный цикл ───────────────────────────────────────────────────────────

    public void start() {
        reload();
        if (!BuildToken.enabled()) {
            // Версия выпущена до появления токена: сверять не с чем, иначе с неё нельзя было бы
            // обновиться. Скажем один раз и работаем дальше.
            plugin.getLogger().info("Обновление: в этой сборке нет токена — проверка подлинности "
                    + "скачиваемого файла пропускается; включится сама после обновления.");
        }
        stagedJar = newestStagedOnDisk();
        stagedVersion = stagedJar == null ? "" : versionFromJarName(stagedJar);
        if (stagedJar != null) {
            plugin.getLogger().info("Обновление: подготовлен " + stagedJar.getFileName()
                    + (hotApply ? " — поставится на ходу, без рестарта сервера."
                                : " — применится при выключении сервера."));
            if (hotApply && enabled) {
                // Подготовленный релиз ставим сами, даже если он остался с прошлого запуска
                // (например, сервер перезапустили до подмены). Повтор после неудачи не делаем —
                // это решает HotSwap по метке hot-failed.txt.
                final Path staged = stagedJar;
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> HotSwap.apply(plugin, staged, false, text -> { }), 20L * 20L);
            }
        }
        if (!enabled) {
            plugin.getLogger().info("Обновление: автопроверка выключена (обновление.включено: false).");
            return;
        }
        if (intervalMinutes <= 0) {
            plugin.getLogger().info("Обновление: периодическая проверка выключена (интервал-минут: 0).");
            return;
        }
        long period = intervalMinutes * 60L * 20L;
        // Первая проверка — через минуту после старта, дальше по интервалу; всё в отдельном потоке.
        // Проверки идут МОЛЧА: в консоль ничего не пишем, пока не найдётся новая версия или не
        // случится ошибка (одна и та же ошибка повторно в лог не идёт).
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                () -> checkAsync(text -> { }, autoDownload), 20L * 60L, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ── проверка и скачивание ────────────────────────────────────────────────────

    /** Запускает проверку в отдельном потоке; текст для игрока приходит через {@code feedback}. */
    public void checkAsync(Consumer<String> feedback, boolean installIfNewer) {
        if (busy) {
            reply(feedback, "Проверка обновления уже идёт.");
            return;
        }
        if (repo.isBlank() || !repo.contains("/")) {
            reply(feedback, "Репозиторий релизов не задан (обновление.репозиторий в config.yml).");
            return;
        }
        busy = true;
        String current = currentVersion();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Release release = fetchLatest();
                lastCheckMs = System.currentTimeMillis();
                lastError = "";
                loggedError = ""; // следующая беда снова попадёт в лог
                if (release == null) {
                    reply(feedback, "Релизов с подходящим файлом не найдено.");
                    return;
                }
                latestVersion = release.version();
                if (!isNewer(release.version(), current)) {
                    reply(feedback, "Установлена последняя версия: " + current + ".");
                    return;
                }
                if (release.version().equals(stagedVersion) && stagedJar != null
                        && Files.isRegularFile(stagedJar)) {
                    reply(feedback, "Версия " + release.version() + " уже скачана и ждёт установки.");
                    return;
                }
                reply(feedback, "Доступна версия " + release.version() + " (сейчас " + current + ").");
                // Проверки идут часто (раз в обновление.интервал-минут), поэтому про одну и ту же
                // версию объявляем один раз, а не на каждой проверке.
                if (!release.version().equals(announcedVersion)) {
                    announcedVersion = release.version();
                    announce("Доступна новая версия " + release.version() + ".");
                }
                if (!installIfNewer) return;

                Path jar = download(release);
                stagedJar = jar;
                stagedVersion = release.version();
                reply(feedback, "Скачано и проверено: " + jar.getFileName());
                if (hotApply && plugin.isEnabled() && !HotSwap.running()) {
                    // Горячая подмена: файл уже скачан целиком, ставим и запускаем на ходу —
                    // без рестарта сервера. Работа идёт в главном потоке, тексты скажет сам HotSwap.
                    final Path staged = jar;
                    try {
                        Bukkit.getScheduler().runTask(plugin, () -> HotSwap.apply(plugin, staged, false, feedback));
                    } catch (Throwable t) {
                        lastError = t.getMessage() == null ? t.toString() : t.getMessage();
                        plugin.getLogger().warning("Обновление: горячую подмену запустить не удалось ("
                                + lastError + ") — версия применится при рестарте сервера.");
                        announce("Версия " + release.version() + " скачана и готова к установке.");
                    }
                    return;
                }
                announce("Версия " + release.version() + " скачана и готова к установке.");
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                // Одна и та же ошибка не спамит лог на каждой проверке.
                if (!lastError.equals(loggedError)) {
                    loggedError = lastError;
                    plugin.getLogger().warning("Обновление: " + lastError);
                }
                reply(feedback, "Не удалось проверить обновление: " + lastError);
                // Без токена приватный репозиторий всегда отвечает 404: не долбим GitHub каждые N минут,
                // а один раз объясняем в логе, что делать.
                if (!hasToken() && isAccessError(lastError)) {
                    plugin.getLogger().warning("Обновление: автопроверка остановлена — нужен токен "
                            + "(plugins/" + plugin.getDataFolder().getName() + "/" + tokenFile
                            + ", plugins/" + MAIN_PLUGIN_FOLDER + "/" + tokenFile
                            + " или переменная HAHTOJER_GITHUB_TOKEN), fine-grained, "
                            + "право Contents: Read. Настройки: секция «обновление» в config.yml.");
                    stop();
                }
            } finally {
                busy = false;
            }
        });
    }

    private Release fetchLatest() throws IOException, InterruptedException {
        HttpRequest request = api("/repos/" + repo + "/releases?per_page=20").GET().build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = response.statusCode();
        if (code == 404) {
            throw new IOException("GitHub ответил 404 — репозиторий не найден или токен без доступа к нему");
        }
        if (code == 401 || code == 403) {
            throw new IOException("GitHub ответил " + code + " — токен неверный или без права Contents: Read");
        }
        if (code != 200) {
            throw new IOException("GitHub ответил HTTP " + code);
        }
        return selectRelease(response.body());
    }

    /** Первый подходящий релиз (не черновик, при необходимости не предрелиз) с jar-файлом. */
    private Release selectRelease(String body) {
        for (Object item : Json.array(body)) {
            if (!(item instanceof Map<?, ?> release)) continue;
            if (Boolean.TRUE.equals(release.get("draft"))) continue;
            boolean pre = Boolean.TRUE.equals(release.get("prerelease"));
            if (pre && !allowPrerelease) continue;

            String tag = str(release.get("tag_name"));
            if (tag.isBlank()) continue;
            String version = normalizeVersion(tag);

            Object assetsRaw = release.get("assets");
            if (!(assetsRaw instanceof List<?> assets)) continue;
            for (Object assetRaw : assets) {
                if (!(assetRaw instanceof Map<?, ?> asset)) continue;
                String name = str(asset.get("name"));
                // Имя ассета с релизом: hahgps-1.0.0.jar (регистр не важен).
                if (!name.toLowerCase(Locale.ROOT).startsWith(JAR_PREFIX)
                        || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                long id = (long) toDouble(asset.get("id"));
                if (id <= 0) continue;
                return new Release(tag, version, name, id, pre);
            }
        }
        return null;
    }

    /** Сколько раз пробовать скачать jar: прокси и провайдеры рвут закачку и подменяют ответ. */
    private static final int DOWNLOAD_ATTEMPTS = 3;

    private Path download(Release release) throws Exception {
        Path dir = plugin.getDataFolder().toPath().resolve("update");
        Files.createDirectories(dir);
        String name = JAR_PREFIX + "-" + release.version() + ".jar";
        Path target = dir.resolve(name);
        Path tmp = dir.resolve(name + ".tmp");

        IOException last = null;

        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            Files.deleteIfExists(tmp);
            try {
                fetchAsset(release, tmp);
                // Скачанное обязано быть jar (а jar — это zip): проверяем магию ДО токена сборки,
                // чтобы ошибка была понятной («начинается с ...»), а не «zip END header not found».
                ensureJar(tmp);
                // Подлинность файла проверяем токеном сборки (hahtojer.token внутри jar), а не
                // размером: у плагина нет постоянного веса, и порог рано или поздно начал бы
                // отсекать нормальные версии. Проверка идёт ДО переноса файла, чтобы на диск
                // не попал ни один непроверенный jar.
                BuildToken.verify(tmp);
                long size = Files.size(tmp);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().info("Обновление: скачан и проверен " + name + " (" + size + " байт"
                        + (attempt > 1 ? ", попытка " + attempt : "") + ")");
                return target;
            } catch (IOException e) {
                last = e;
                if (attempt >= DOWNLOAD_ATTEMPTS) break;
                plugin.getLogger().warning("Обновление: скачивание не удалось (" + e.getMessage()
                        + ") — пробую ещё раз (" + attempt + " из " + (DOWNLOAD_ATTEMPTS - 1) + ").");
                Thread.sleep(2000L * attempt);
            }
        }
        throw last == null ? new IOException("скачивание не удалось") : last;
    }

    /**
     * Скачанное обязано быть jar (а jar — это zip, начинается с {@code PK\\x03\\x04}). Прокси мог
     * подменить ответ страницей, закачка могла оборваться — проверяем магию до токена сборки,
     * чтобы ошибка была понятной («начинается с ...»), а не «zip END header not found».
     */
    static void ensureJar(Path file) throws IOException {
        byte[] head = new byte[4];
        try (InputStream in = Files.newInputStream(file)) {
            if (in.readNBytes(head, 0, head.length) == head.length
                    && head[0] == 0x50 && head[1] == 0x4B && head[2] == 0x03 && head[3] == 0x04) {
                return;
            }
        }
        long size = Files.size(file);
        String preview;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] chunk = in.readNBytes(120);
            StringBuilder sb = new StringBuilder(chunk.length + 3);
            for (byte b : chunk) {
                char c = (char) (b & 0xFF);
                sb.append(c >= 0x20 && c < 0x7F ? c : '.');
            }
            preview = sb.append("...").toString().replaceAll("\\s+", " ");
        } catch (IOException e) {
            preview = "?";
        }
        throw new IOException("скачанный файл не jar (" + size + " байт), начинается с: " + preview);
    }

    /** Тянет тело ассета во временный файл. */
    private void fetchAsset(Release release, Path tmp) throws Exception {
        // setHeader, не header: api() уже ставит Accept: application/vnd.github+json, и второй
        // Accept заставляет GitHub ответить JSON-метаданными ассета вместо бинарника —
        // скачанный «jar» оказывался JSON на полторы тысячи байт, и обновление падало
        // с непонятным «zip END header not found».
        HttpRequest request = api("/repos/" + repo + "/releases/assets/" + release.assetId())
                .setHeader("Accept", "application/octet-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        try (InputStream raw = openAssetStream(response)) {
            Files.copy(raw, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Тело ответа с ассетом; 302 на objects.githubusercontent.com проходим без заголовка Authorization. */
    private InputStream openAssetStream(HttpResponse<InputStream> response) throws Exception {
        int code = response.statusCode();
        if (code == 200) return response.body();
        if (code / 100 == 3) {
            response.body().close();
            String location = response.headers().firstValue("location")
                    .orElseThrow(() -> new IOException("редирект без заголовка Location"));
            if (!location.startsWith("https://")) {
                throw new IOException("небезопасный редирект: " + location);
            }
            HttpRequest follow = HttpRequest.newBuilder(URI.create(location))
                    .timeout(Duration.ofMinutes(2))
                    .header("User-Agent", "hahgps-updater")
                    .GET()
                    .build();
            HttpResponse<InputStream> second = http.send(follow, HttpResponse.BodyHandlers.ofInputStream());
            if (second.statusCode() != 200) {
                second.body().close();
                throw new IOException("скачивание: HTTP " + second.statusCode());
            }
            return second.body();
        }
        response.body().close();
        throw new IOException("скачивание: HTTP " + code);
    }

    // ── применение ───────────────────────────────────────────────────────────────

    /**
     * Подменяет файл плагина подготовленным jar. Вызывается последним в {@code onDisable}.
     *
     * <p>На Linux работает даже для запущенного jar (JVM держит открытый inode, подмена
     * имени файла её не ломает). На Windows запущенный jar залочен: тогда ничего не меняем,
     * чтобы не получить два плагина сразу, и пишем готовый скрипт для ручного применения.
     */
    public boolean applyStaged() {
        Path staged = stagedJar;
        if (staged == null || !Files.isRegularFile(staged)) {
            staged = newestStagedOnDisk();
        }
        if (staged == null) return false;

        Path current = ownJar();
        if (current == null) {
            plugin.getLogger().warning("Обновление: не удалось определить собственный jar — примени вручную из "
                    + staged);
            return false;
        }

        String version = versionFromJarName(staged);
        Path parent = current.getParent();
        Path target = (parent == null ? current : parent.resolve(JAR_PREFIX + "-" + version + ".jar"))
                .toAbsolutePath();
        if (target.equals(current.toAbsolutePath())) {
            return false; // уже эта версия
        }

        try {
            Files.copy(staged, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            writeFallbackScript(current, staged, e);
            return false;
        }
        try {
            Files.deleteIfExists(current);
        } catch (IOException e) {
            // Два jar одновременно — это два экземпляра плагина; откатываем копию и просим ручной шаг.
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // нечего добавить: ниже всё равно скажем, что делать руками
            }
            writeFallbackScript(current, staged, e);
            return false;
        }
        plugin.getLogger().info("Обновление применено: " + current.getFileName()
                + " → " + target.getFileName());
        return true;
    }

    private void writeFallbackScript(Path current, Path staged, IOException cause) {
        Path dir = staged.getParent();
        try {
            Path cmd = dir.resolve("apply-update.cmd");
            Files.writeString(cmd, "@echo off\r\n"
                    + "rem Обновление hahgps: сначала ОСТАНОВИ сервер, потом запусти этот файл.\r\n"
                    + "copy /Y \"" + staged.toAbsolutePath() + "\" \"" + current.toAbsolutePath() + "\"\r\n",
                    StandardCharsets.UTF_8);
            Path sh = dir.resolve("apply-update.sh");
            Files.writeString(sh, "#!/bin/sh\n"
                    + "# Обновление hahgps: сначала ОСТАНОВИ сервер, потом запусти этот файл.\n"
                    + "cp -f '" + staged.toAbsolutePath() + "' '" + current.toAbsolutePath() + "'\n",
                    StandardCharsets.UTF_8);
            plugin.getLogger().warning("Обновление: на ходу заменить jar не удалось (" + cause.getMessage()
                    + "). Останови сервер и запусти " + cmd + " — или скопируй jar вручную.");
        } catch (IOException e) {
            plugin.getLogger().warning("Обновление: заменить jar не удалось (" + cause.getMessage()
                    + "), записать скрипт тоже (" + e.getMessage() + "). Файл лежит здесь: " + staged);
        }
    }

    // ── вспомогательное ──────────────────────────────────────────────────────────

    private HttpRequest.Builder api(String path) {
        // Адрес API переопределяется (обновление.адрес-api) — для GitHub Enterprise и тестов.
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(api + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "hahgps-updater");
        if (!token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    /** Текущая версия плагина из plugin.yml (то, что подставил Maven). */
    public String currentVersion() {
        try {
            return normalizeVersion(plugin.getPluginMeta().getVersion());
        } catch (Throwable t) {
            return "0";
        }
    }

    /** «v1.0.1» → «1.0.1». */
    static String normalizeVersion(String tag) {
        if (tag == null) return "";
        String out = tag.trim();
        while (!out.isEmpty() && (out.charAt(0) == 'v' || out.charAt(0) == 'V')) {
            out = out.substring(1);
        }
        return out;
    }

    /**
     * Сравнение версий по числовым частям: «1.0.10» новее «1.0.9» (обычное сравнение строк тут врёт).
     * Нечисловой хвост (например «-beta») на сравнение не влияет.
     */
    static boolean isNewer(String candidate, String current) {
        int[] a = versionParts(candidate);
        int[] b = versionParts(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int left = i < a.length ? a[i] : 0;
            int right = i < b.length ? b[i] : 0;
            if (left != right) return left > right;
        }
        return false;
    }

    private static int[] versionParts(String version) {
        String[] chunks = normalizeVersion(version).split("[^0-9]+");
        List<Integer> parts = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.isEmpty()) continue;
            try {
                parts.add(Integer.parseInt(chunk));
            } catch (NumberFormatException e) {
                parts.add(0);
            }
        }
        int[] out = new int[parts.size()];
        for (int i = 0; i < out.length; i++) out[i] = parts.get(i);
        return out;
    }

    private Path newestStagedOnDisk() {
        Path dir = plugin.getDataFolder().toPath().resolve("update");
        if (!Files.isDirectory(dir)) return null;
        String current = currentVersion();
        Path best = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, JAR_PREFIX + "-*.jar")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) continue;
                String version = versionFromJarName(file);
                // Версия в update/ должна быть новее установленной: иначе выключение сервера
                // «откатило» бы плагин на старый файл.
                if (!isNewer(version, current)) continue;
                // Подлинность — по токену сборки, как при скачивании: файл мог остаться
                // от прошлой неудачной закачки или попасть откуда-то ещё.
                try {
                    BuildToken.verify(file);
                } catch (IOException e) {
                    plugin.getLogger().warning("Обновление: файл " + file.getFileName()
                            + " не прошёл проверку токена (" + e.getMessage() + ") — пропускаю.");
                    continue;
                }
                if (best == null || isNewer(version, versionFromJarName(best))) best = file;
            }
        } catch (IOException e) {
            return null;
        }
        return best;
    }

    /** «hahgps-1.0.1.jar» → «1.0.1» (регистр в имени не важен). */
    static String versionFromJarName(Path jar) {
        if (jar == null || jar.getFileName() == null) return "";
        String name = jar.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar")) name = name.substring(0, name.length() - 4);
        if (name.toLowerCase(Locale.ROOT).startsWith(JAR_PREFIX + "-")) {
            name = name.substring(JAR_PREFIX.length() + 1);
        }
        return normalizeVersion(name);
    }

    /**
     * Собственный файл плагина в plugins/.
     * {@code JavaPlugin#getFile()} в Paper закрыт (protected), поэтому берём путь из CodeSource,
     * а если и он недоступен — ищем свой jar по имени в каталоге plugins/.
     */
    private Path ownJar() {
        try {
            var source = plugin.getClass().getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path path = Path.of(source.getLocation().toURI());
                if (Files.isRegularFile(path)) return path;
            }
        } catch (Throwable ignored) {
            // переходим к поиску по каталогу
        }
        File parent = plugin.getDataFolder().getParentFile();
        if (parent == null) return null;
        File[] jars = parent.listFiles((dir, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".jar") && lower.startsWith(JAR_PREFIX);
        });
        if (jars == null || jars.length == 0) return null;
        String current = currentVersion();
        Path fallback = null;
        for (File jar : jars) {
            if (!current.isEmpty() && jar.getName().contains(current)) return jar.toPath();
            if (fallback == null) fallback = jar.toPath();
        }
        return fallback;
    }

    /** Ответ игроку/консоли — всегда в главном потоке (метод вызывается из сетевого потока). */
    private void reply(Consumer<String> feedback, String text) {
        if (feedback == null) return;
        runOnMain(() -> feedback.accept(text));
    }

    /**
     * Задача в главном потоке. Во время выключения плагина планировщик уже недоступен —
     * проверка обновлений в этот момент досчитывается в своём потоке и раньше валила
     * в лог {@code IllegalPluginAccessException}.
     */
    private void runOnMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, action);
        } catch (Throwable ignored) {
            // планировщик уже закрыт — сказать всё равно некому
        }
    }

    /** Уведомление админов в игре + строка в лог. */
    private void announce(String text) {
        runOnMain(() -> {
            for (var player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("gps.admin") || player.isOp()) {
                    player.sendMessage("§b" + text);
                }
            }
        });
        plugin.getLogger().info(text);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    /** Ошибка доступа к репозиторию (нет токена / токен без прав) — по ней гасим автопроверку. */
    static boolean isAccessError(String message) {
        if (message == null) return false;
        return message.contains("404") || message.contains("401") || message.contains("403");
    }

    // ── статус ───────────────────────────────────────────────────────────────────

    /** Строки для /gps update status. */
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add(repo.isBlank()
                ? "Репозиторий релизов: не задан"
                : "Репозиторий релизов: " + repo);
        out.add("Автопроверка: " + (enabled ? "включена" : "выключена")
                + ", интервал: " + intervalMinutes + " мин"
                + ", предрелизы: " + (allowPrerelease ? "да" : "нет"));
        out.add("Горячее обновление: " + (hotApply ? "включено" : "выключено")
                + ", последняя попытка: " + (HotSwap.lastResult().isEmpty()
                        ? "не было" : HotSwap.lastResult()));
        out.add(hasToken()
                ? "Токен доступа: есть (" + tokenSource + ")"
                : "Токен доступа: нет");
        out.add("Версия: " + currentVersion()
                + (latestVersion.isEmpty() ? "" : ", последний релиз: " + latestVersion));
        out.add("Проверка токеном сборки: " + (BuildToken.enabled() ? "включена" : "выключена"));
        out.add(stagedJar != null
                ? "Подготовлено: " + stagedJar.getFileName()
                : "Подготовленных обновлений нет");
        out.add(lastCheckMs == 0
                ? "Последняя проверка: не было"
                : "Последняя проверка: "
                        + new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm")
                                .format(new java.util.Date(lastCheckMs)));
        if (!lastError.isEmpty()) {
            out.add("Ошибка: " + lastError);
        }
        return out;
    }
}
