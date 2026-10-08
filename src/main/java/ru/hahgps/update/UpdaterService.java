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
import java.util.concurrent.atomic.AtomicBoolean;
public final class UpdaterService {
    public record Release(String tag, String version, String assetName, long assetId,
                          boolean prerelease, String publishedAt, String body) {
    }
    private static final String API = "https://api.github.com";
    public static final String TOKEN_PLACEHOLDER = "ВСТАВЬ_ТОКЕН_СЮДА";
    private static final String MAIN_PLUGIN_FOLDER = "hahtojer";
    static final String JAR_PREFIX = "hahgps";
    private final HahGpsPlugin plugin;
    private final UpdateAnnouncer announcer;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private volatile boolean enabled = true;
    private volatile String repo = "";
    private volatile boolean allowPrerelease;
    private volatile long intervalMinutes = 5;
    private volatile String tokenFile = "github-token.txt";
    private volatile boolean autoDownload = true;
    private volatile boolean hotApply = true;
    private volatile String token = "";
    private volatile String api = API;
    private volatile BukkitTask task;

    private volatile boolean active;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile String lastError = "";
    private volatile long lastCheckMs;
    private volatile String latestVersion = "";
    private volatile String stagedVersion = "";
    private volatile Path stagedJar;
    private volatile String tokenSource = "";
    private volatile String loggedError = "";
    private volatile String announcedVersion = "";
    private volatile boolean remindedPlaceholder;
    public UpdaterService(HahGpsPlugin plugin) {
        this.plugin = plugin;
        this.announcer = new UpdateAnnouncer(plugin);
    }

    /**
     * Перечитывание настроек. synchronized: вызывается из async-потока
     * ({@code /gps update reload}), а поля читают таймерная задача и api().
     * Без монитора читатель видел бы полуобновлённые repo/token.
     */
    public synchronized void reload() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("обновление.включено", true);
        repo = String.valueOf(cfg.getString("обновление.репозиторий", "EXODUS-REBORN/hahgps")).trim();
        allowPrerelease = cfg.getBoolean("обновление.предрелизы", false);
        // Верхняя граница: интервал переводится в тики, и огромное значение
        // переполняло long, давая отрицательный период для runTaskTimer.
        intervalMinutes = Math.max(0, Math.min(1440, cfg.getLong("обновление.интервал-минут", 5)));
        tokenFile = String.valueOf(cfg.getString("обновление.файл-ключа", "github-token.txt")).trim();
        autoDownload = cfg.getBoolean("обновление.авто-скачивание", true);
        hotApply = cfg.getBoolean("обновление.горячее-обновление", true);

        announcer.setGroups(cfg.getStringList("обновление.группы-оповещения"));
        announcer.setPlayers(cfg.getStringList("обновление.игроки-оповещения"));
        String custom = String.valueOf(cfg.getString("обновление.адрес-api", API)).trim();
        api = custom.isEmpty() ? API : custom.replaceAll("/+$", "");
        token = readToken();
    }

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

    private String readTokenFrom(File dataFolder, String fileName) {
        Path file = tokenPath(dataFolder, fileName);
        if (file == null || !Files.isReadable(file)) return "";
        try {
            return normalizeToken(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Обновление: не удалось прочитать " + file
                    + " (" + e.getMessage() + ")");
            return "";
        }
    }

    /**
     * Путь к файлу токена строго внутри папки плагина: имя файла приходит из
     * config.yml, и «../../server.properties» раньше позволял читать и создавать
     * файлы за её пределами.
     */
    static Path tokenPath(File dataFolder, String fileName) {
        if (dataFolder == null || fileName == null || fileName.isBlank()) {
            return null;
        }
        Path base = dataFolder.toPath().toAbsolutePath().normalize();
        Path file = base.resolve(fileName).normalize();
        return file.startsWith(base) ? file : null;
    }

    public static void ensureTokenFile(HahGpsPlugin plugin) {
        String name = String.valueOf(plugin.getConfig()
                .getString("обновление.файл-ключа", "github-token.txt")).trim();
        if (name.isEmpty()) return;
        Path file = tokenPath(plugin.getDataFolder(), name);
        if (file == null) {
            plugin.getLogger().warning("Обновление: обновление.файл-ключа = «" + name
                    + "» выводит за пределы папки плагина — файл не создаю.");
            return;
        }
        if (Files.exists(file)) return;
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, "# Токен GitHub (fine-grained, Contents: Read-only)\n"
                    + "# Можно не заполнять: если токен уже настроен у плагина "
                    + MAIN_PLUGIN_FOLDER + " (plugins/" + MAIN_PLUGIN_FOLDER + "/" + name
                    + "), hahgps возьмёт его оттуда.\n"
                    + TOKEN_PLACEHOLDER + "\n", StandardCharsets.UTF_8);
            plugin.getLogger().info("Обновление: создан plugins/" + plugin.getDataFolder().getName()
                    + "/" + name + " — впиши в него токен GitHub (fine-grained, право "
                    + "Contents: Read-only), иначе приватный репозиторий с релизами останется "
                    + "недоступен.");
        } catch (Exception e) {
            plugin.getLogger().warning("Обновление: не удалось создать " + file.getFileName()
                    + " (" + e.getMessage() + ")");
        }
    }

    static boolean isPlaceholder(String value) {
        return value != null && value.trim().equalsIgnoreCase(TOKEN_PLACEHOLDER);
    }

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
        if (picked == null) return "";
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

    public boolean hotApply() {
        return hotApply;
    }

    public Path stagedJar() {
        return stagedJar;
    }

    public String stagedVersion() {
        return stagedVersion;
    }

    public String repository() {
        return repo;
    }

    public UpdateAnnouncer announcer() {
        return announcer;
    }

    public synchronized void start() {
        reload();
        HotSwap.reset();
        if (!BuildToken.enabled()) {
            // Fail-closed: без токена сборки подлинность скачанного файла проверить
            // нечем, поэтому такой плагин сам себя не обновляет.
            autoDownload = false;
            hotApply = false;
            plugin.getLogger().warning("Обновление: в этой сборке нет hahtojer.token — "
                    + "автоскачивание и горячая подмена выключены (проверять подлинность нечем). "
                    + "Новую версию придётся положить в plugins/ вручную.");
        }
        active = true;
        try {

            Bukkit.getScheduler().runTaskAsynchronously(plugin, this::scanStagedAndSchedule);
        } catch (Throwable t) {
            active = false;
            plugin.getLogger().warning("Обновление: не удалось запустить проверку ("
                    + (t.getMessage() == null ? t.toString() : t.getMessage()) + ").");
        }
    }


    private void scanStagedAndSchedule() {
        if (!active || !plugin.isEnabled()) {
            return;
        }
        try {
            Path jar = newestStagedOnDisk();
            stagedJar = jar;
            stagedVersion = jar == null ? "" : versionFromJarName(jar);
            if (jar != null) {
                plugin.getLogger().info("Обновление: подготовлен " + jar.getFileName()
                        + (hotApply ? " — поставится на ходу, без рестарта сервера."
                                    : " — применится при выключении сервера."));
                if (hotApply && enabled) {
                    final Path staged = jar;
                    try {
                        Bukkit.getScheduler().runTaskLater(plugin,
                                () -> HotSwap.apply(plugin, staged, false, text -> { }), 20L * 20L);
                    } catch (Throwable t) {
                        plugin.getLogger().warning("Обновление: не удалось запланировать применение ("
                                + (t.getMessage() == null ? t.toString() : t.getMessage()) + ").");
                    }
                }
            }
            if (!active || !plugin.isEnabled()) {
                return;
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
            BukkitTask timer = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                    () -> checkAsync(text -> { }, autoDownload), 20L * 60L, period);
            if (active) {
                task = timer;
            } else {

                timer.cancel();
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Обновление: автопроверка не запущена ("
                    + (t.getMessage() == null ? t.toString() : t.getMessage()) + ").");
        }
    }

    public synchronized void stop() {
        active = false;
        BukkitTask current = task;
        task = null;
        if (current != null) {
            current.cancel();
        }
    }

    public void checkAsync(Consumer<String> feedback, boolean installIfNewer) {
        if (!busy.compareAndSet(false, true)) {
            reply(feedback, "Проверка обновления уже идёт.");
            return;
        }
        if (repo.isBlank() || !repo.contains("/")) {
            busy.set(false);
            reply(feedback, "Репозиторий релизов не задан (обновление.репозиторий в config.yml).");
            return;
        }
        String current = currentVersion();
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                Release release = fetchLatest();
                lastCheckMs = System.currentTimeMillis();
                lastError = "";
                loggedError = "";
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

                if (!release.version().equals(announcedVersion)) {
                    announcedVersion = release.version();

                    announcer.announce("update-available",
                            "version", release.version(), "date", formatReleaseDate(release.publishedAt()));
                    if (!release.body().isBlank()) {
                        announcer.announce("update-whats-new", "notes", shortNotes(release.body()));
                    }
                }
                if (!installIfNewer) return;

                announcer.announce("update-downloading-now");
                Path jar;
                try {
                    jar = download(release);
                } catch (Exception e) {
                    lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                    if (!lastError.equals(loggedError)) {
                        loggedError = lastError;
                        plugin.getLogger().warning("Обновление: " + lastError);
                    }
                    reply(feedback, "Не удалось проверить обновление: " + lastError);
                    announcer.announce("update-failed", "error", lastError);
                    if (!hasToken() && isAccessError(lastError)) {
                        plugin.getLogger().warning("Обновление: автопроверка остановлена — нужен токен "
                                + "(plugins/" + plugin.getDataFolder().getName() + "/" + tokenFile
                                + ", plugins/" + MAIN_PLUGIN_FOLDER + "/" + tokenFile
                                + " или переменная HAHTOJER_GITHUB_TOKEN), fine-grained, "
                                + "право Contents: Read. Настройки: секция «обновление» в config.yml.");
                        stop();
                    }
                    return;
                }
                stagedJar = jar;
                stagedVersion = release.version();
                reply(feedback, "Скачано и проверено: " + jar.getFileName());
                if (hotApply && plugin.isEnabled() && !HotSwap.running()) {

                    final Path staged = jar;
                    try {
                        Bukkit.getScheduler().runTask(plugin, () -> HotSwap.apply(plugin, staged, false, feedback));
                    } catch (Throwable t) {
                        lastError = t.getMessage() == null ? t.toString() : t.getMessage();
                        plugin.getLogger().warning("Обновление: горячую подмену запустить не удалось ("
                                + lastError + ") — версия применится при рестарте сервера.");
                        announcer.announce("update-failed", "error", lastError);
                        announcer.announce("update-staged", "version", release.version());
                    }
                    return;
                }
                announcer.announce("update-staged", "version", release.version());
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();

                if (!lastError.equals(loggedError)) {
                    loggedError = lastError;
                    plugin.getLogger().warning("Обновление: " + lastError);
                }
                reply(feedback, "Не удалось проверить обновление: " + lastError);

                if (!hasToken() && isAccessError(lastError)) {
                    plugin.getLogger().warning("Обновление: автопроверка остановлена — нужен токен "
                            + "(plugins/" + plugin.getDataFolder().getName() + "/" + tokenFile
                            + ", plugins/" + MAIN_PLUGIN_FOLDER + "/" + tokenFile
                            + " или переменная HAHTOJER_GITHUB_TOKEN), fine-grained, "
                            + "право Contents: Read. Настройки: секция «обновление» в config.yml.");
                    stop();
                }
            } finally {
                busy.set(false);
            }
            });
        } catch (Throwable t) {


            busy.set(false);
            String message = t.getMessage() == null ? t.toString() : t.getMessage();
            plugin.getLogger().warning("Обновление: не удалось запустить проверку (" + message + ")");
            reply(feedback, "Не удалось запустить проверку обновления: " + message);
        }
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

    private Release selectRelease(String body) {
        Object parsed = Json.parse(body);
        // GitHub на 200 может отдать объект ошибки (rate limit, maintenance) —
        // array() молча вернул бы пустой список, и пользователь увидел бы
        // «релизов не найдено» вместо настоящей причины.
        if (parsed instanceof Map<?, ?> error) {
            String message = str(error.get("message"));
            throw new IllegalArgumentException("GitHub вернул ошибку: "
                    + (message.isBlank() ? "неизвестная" : message));
        }
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

                if (!name.toLowerCase(Locale.ROOT).startsWith(JAR_PREFIX)
                        || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                long id = (long) toDouble(asset.get("id"));
                if (id <= 0) continue;
                return new Release(tag, version, name, id, pre,
                        str(release.get("published_at")), str(release.get("body")));
            }
        }
        return null;
    }

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

                ensureJar(tmp);

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
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (InterruptedException interrupted) {
                // Прерывание не должно съедать остальные попытки молча:
                // восстанавливаем флаг и выходим.
                Thread.currentThread().interrupt();
                throw new IOException("скачивание прервано", interrupted);
            }
        }
        throw last == null ? new IOException("скачивание не удалось") : last;
    }

    static void ensureJar(Path file) throws IOException {
        byte[] head = new byte[4];
        boolean zipHeader;
        try (InputStream in = Files.newInputStream(file)) {
            zipHeader = in.readNBytes(head, 0, head.length) == head.length
                    && head[0] == 0x50 && head[1] == 0x4B && head[2] == 0x03 && head[3] == 0x04;
        }
        if (!zipHeader) {
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


        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile(),
                java.nio.charset.StandardCharsets.UTF_8)) {
            if (zip.getEntry("plugin.yml") == null) {
                throw new IOException("в скачанном файле нет plugin.yml — это не jar плагина");
            }
        } catch (java.util.zip.ZipException e) {
            throw new IOException("скачанный jar повреждён: " + e.getMessage());
        }
    }

    private void fetchAsset(Release release, Path tmp) throws Exception {




        // Тело сразу пишется в файл: раньше весь jar собирался в byte[] и висел
        // в памяти, а попыток скачивания до трёх.
        HttpRequest request = api("/repos/" + repo + "/releases/assets/" + release.assetId())
                .setHeader("Accept", "application/octet-stream")
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
        followAssetRedirect(response, tmp);
    }

    private void followAssetRedirect(HttpResponse<Path> response, Path tmp) throws Exception {
        int code = response.statusCode();
        if (code == 200) {
            return;
        }
        if (code / 100 == 3) {
            String location = response.headers().firstValue("location")
                    .orElseThrow(() -> new IOException("редирект без заголовка Location"));
            if (!location.startsWith("https://")) {
                throw new IOException("небезопасный редирект: " + location);
            }
            // На редиректе в файл попадает служебный ответ GitHub, а не jar.
            Files.deleteIfExists(tmp);
            HttpRequest follow = HttpRequest.newBuilder(URI.create(location))
                    .timeout(Duration.ofMinutes(2))
                    .header("User-Agent", "hahgps-updater")
                    .GET()
                    .build();
            HttpResponse<Path> second = http.send(follow, HttpResponse.BodyHandlers.ofFile(tmp));
            if (second.statusCode() != 200) {
                throw new IOException("скачивание: HTTP " + second.statusCode());
            }
            return;
        }
        throw new IOException("скачивание: HTTP " + code);
    }

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
            return false;
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

            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {

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

    private HttpRequest.Builder api(String path) {

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

    public String currentVersion() {
        try {
            return normalizeVersion(plugin.getPluginMeta().getVersion());
        } catch (Throwable t) {
            return "0";
        }
    }

    static String normalizeVersion(String tag) {
        if (tag == null) return "";
        String out = tag.trim();
        while (!out.isEmpty() && (out.charAt(0) == 'v' || out.charAt(0) == 'V')) {
            out = out.substring(1);
        }
        return out;
    }


    static String formatReleaseDate(String iso) {
        if (iso == null || iso.isBlank()) return "";
        String raw = iso.trim();
        try {
            return java.time.OffsetDateTime.parse(raw)
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        } catch (Exception e) {
            int t = raw.indexOf('T');
            return t > 0 ? raw.substring(0, t) : raw;
        }
    }


    static String shortNotes(String body) {
        if (body == null) return "";
        String notes = body.strip();
        if (notes.length() > 500) {
            notes = notes.substring(0, 500).strip() + "…";
        }
        return notes;
    }

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

                if (!isNewer(version, current)) continue;

                try {

                    ensureJar(file);
                    BuildToken.verify(file);
                } catch (IOException e) {
                    plugin.getLogger().warning("Обновление: файл " + file.getFileName()
                            + " не прошёл проверку (" + e.getMessage() + ") — пропускаю.");
                    continue;
                }
                if (best == null || isNewer(version, versionFromJarName(best))) best = file;
            }
        } catch (IOException e) {
            return null;
        }
        return best;
    }

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

    private Path ownJar() {
        try {
            var source = plugin.getClass().getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path path = Path.of(source.getLocation().toURI());
                if (Files.isRegularFile(path)) return path;
            }
        } catch (Throwable ignored) {

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

    private void reply(Consumer<String> feedback, String text) {
        if (feedback == null) return;
        runOnMain(() -> feedback.accept(text));
    }

    private void runOnMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, action);
        } catch (Throwable ignored) {

        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    static boolean isAccessError(String message) {
        if (message == null) return false;
        return message.contains("404") || message.contains("401") || message.contains("403");
    }

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
        out.add("Оповещение об обновлении: группы " + String.join(", ", announcer.groups())
                + " (обновление.группы-оповещения)");
        out.add("Оповещение об обновлении: игроки " + String.join(", ", announcer.players())
                + " (обновление.игроки-оповещения)");
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
