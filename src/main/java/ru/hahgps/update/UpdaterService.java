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
public final class UpdaterService {
    public record Release(String tag, String version, String assetName, long assetId,
                          boolean prerelease) {
    }
    private static final String API = "https://api.github.com";
    private static final String TOKEN_TEMPLATE = "github-token.example.txt";
    public static final String TOKEN_PLACEHOLDER = "ВСТАВЬ_ТОКЕН_СЮДА";
    private static final String MAIN_PLUGIN_FOLDER = "hahtojer";
    static final String JAR_PREFIX = "hahgps";
    private final HahGpsPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
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
    private volatile String tokenSource = "";
    private volatile String loggedError = "";
    private volatile String announcedVersion = "";
    private volatile boolean remindedPlaceholder;
    public UpdaterService(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

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

    public void start() {
        reload();
        HotSwap.reset();
        if (!BuildToken.enabled()) {

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

        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                () -> checkAsync(text -> { }, autoDownload), 20L * 60L, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

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
                    announce("Доступна новая версия " + release.version() + ".");
                }
                if (!installIfNewer) return;

                Path jar = download(release);
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
                        announce("Версия " + release.version() + " скачана и готова к установке.");
                    }
                    return;
                }
                announce("Версия " + release.version() + " скачана и готова к установке.");
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
                Thread.sleep(2000L * attempt);
            }
        }
        throw last == null ? new IOException("скачивание не удалось") : last;
    }

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

    private void fetchAsset(Release release, Path tmp) throws Exception {

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
