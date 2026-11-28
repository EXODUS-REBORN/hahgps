package ru.hahgps.update;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import ru.hahgps.HahGpsPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Второй канал обновлений: пререлизы с нашего сервера (pre.hakerone.ru), без GitHub.
 *
 * <p>Канал включается сам, когда в {@code plugins/HahGps/keyydev.txt} появляется ключ:
 * плагин периодически спрашивает манифест {@code /pre/hahgps/manifest.json}, скачивает
 * сборку, проверяет SHA-256 и токен сборки и ставит её через {@link HotSwap}.</p>
 *
 * <p>Версии пререлизов — своя последовательность 1.0.0 … 1.0.9 → 1.1.0; сборка
 * ставится только если её база не старше установленной версии, чтобы канал не откатил
 * сервер назад.</p>
 */
public final class DevChannel {

    public record Build(String version, String base, String file, String sha256, long size,
                        String publishedAt, String notes) {
    }

    private static final String KEY_TEMPLATE = "keyydev.example.txt";

    private static final String BUILD_ENTRY = "devbuild.txt";

    public static final String KEY_PLACEHOLDER = "ВСТАВЬ_КЛЮЧ_СЮДА";

    private static final String DEFAULT_URL = "https://pre.hakerone.ru";

    private static final Pattern VERSION_LIKE = Pattern.compile("\\d+");

    private final HahGpsPlugin plugin;

    private volatile boolean enabled = true;

    private volatile boolean autoDownload = true;

    private volatile boolean hotApply = true;

    private volatile long intervalMinutes = 2;

    private volatile String url = DEFAULT_URL;

    private volatile String project = "hahgps";

    private volatile String keyFile = "keyydev.txt";

    private volatile String proxyUrl = "";

    private volatile String proxyApplied = "";

    private volatile String key = "";

    private volatile BukkitTask task;

    private volatile boolean busy;

    private volatile String lastError = "";

    private volatile long lastCheckMs;

    private volatile String latestVersion = "";

    private volatile Path stagedJar;

    private volatile Build installedCache;

    private volatile boolean installedRead;

    /** Итог прошлой плановой проверки — чтобы не писать одно и то же в консоль каждые 2 минуты. */
    private volatile String lastQuietOutcome = "";

    /** Итоги, о которых плановая проверка молчит всегда: «новее нет» — это норма, а не событие. */
    private static final java.util.Set<String> QUIET_SILENT = java.util.Set.of("latest");

    public DevChannel(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void reload() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("пререлизы.включено", true);
        url = String.valueOf(cfg.getString("пререлизы.адрес", DEFAULT_URL)).trim()
                .replaceAll("/+$", "");
        if (url.isEmpty()) url = DEFAULT_URL;
        project = String.valueOf(cfg.getString("пререлизы.проект", "hahgps")).trim();
        if (project.isEmpty()) project = "hahgps";
        keyFile = String.valueOf(cfg.getString("пререлизы.файл-ключа", "keyydev.txt")).trim();
        proxyUrl = String.valueOf(cfg.getString("пререлизы.прокси", "")).trim();
        if (proxyUrl.isEmpty()) {
            String env = System.getenv("HAHTOJER_DEV_PROXY");
            proxyUrl = env == null ? "" : env.trim();
        }
        intervalMinutes = Math.max(1, Math.min(1440, cfg.getLong("пререлизы.интервал-минут", 2)));
        autoDownload = cfg.getBoolean("пререлизы.авто-скачивание", true);
        hotApply = cfg.getBoolean("пререлизы.горячее-обновление", true);
        key = readKey();
        applyProxy();
    }

    /** SOCKS-прокси живёт в системных свойствах JVM: HttpURLConnection умеет через него HTTPS. */
    private void applyProxy() {
        if (proxyUrl.equals(proxyApplied)) return;
        proxyApplied = proxyUrl;
        ProxySpec spec = parseProxy(proxyUrl);
        if (spec == null || !spec.socks()) return;
        System.setProperty("socksProxyHost", spec.host());
        System.setProperty("socksProxyPort", String.valueOf(spec.port()));
    }

    /** Создаёт в папке плагина keyydev.txt с заглушкой, если файла ещё нет. */
    public static void ensureKeyFile(HahGpsPlugin plugin) {
        String name = String.valueOf(plugin.getConfig()
                .getString("пререлизы.файл-ключа", "keyydev.txt")).trim();
        if (name.isEmpty()) return;
        Path file = plugin.getDataFolder().toPath().resolve(name);
        if (Files.exists(file)) return;
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (InputStream in = plugin.getResource(KEY_TEMPLATE)) {
                if (in != null) {
                    Files.copy(in, file);
                } else {
                    Files.writeString(file, "# Ключ канала пререлизов (выдаёт владелец сервера)\n"
                            + KEY_PLACEHOLDER + "\n", StandardCharsets.UTF_8);
                }
            }
            plugin.getLogger().info("Пререлизы: создан plugins/HahGps/" + name
                    + " — впиши ключ, и тестовые сборки будут подхватываться сами.");
        } catch (Exception e) {
            plugin.getLogger().warning("Пререлизы: не удалось создать " + file.getFileName()
                    + " (" + e.getMessage() + ")");
        }
    }

    private String readKey() {
        String env = System.getenv("HAHTOJER_DEV_KEY");
        if (env != null && !env.isBlank()) return normalizeKey(env);
        if (!keyFile.isBlank()) {
            Path file = plugin.getDataFolder().toPath().resolve(keyFile);
            if (Files.isReadable(file)) {
                try {
                    String value = normalizeKey(Files.readString(file, StandardCharsets.UTF_8));
                    if (!value.isEmpty()) return value;
                } catch (IOException e) {
                    plugin.getLogger().warning("Пререлизы: не удалось прочитать "
                            + file.getFileName() + " (" + e.getMessage() + ")");
                }
            }

            Path shared = plugin.getDataFolder().getParentFile() == null ? null
                    : plugin.getDataFolder().getParentFile().toPath().resolve("hahtojer").resolve(keyFile);
            if (shared != null && Files.isReadable(shared)) {
                try {
                    String value = normalizeKey(Files.readString(shared, StandardCharsets.UTF_8));
                    if (!value.isEmpty()) return value;
                } catch (IOException e) {
                    plugin.getLogger().warning("Пререлизы: не удалось прочитать общий ключ "
                            + shared + " (" + e.getMessage() + ")");
                }
            }
        }
        return normalizeKey(plugin.getConfig().getString("пререлизы.ключ", ""));
    }

    static String normalizeKey(String raw) {
        if (raw == null) return "";
        String text = raw.replace("\uFEFF", "");
        for (String line : text.split("\\R")) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            if (value.equalsIgnoreCase(KEY_PLACEHOLDER)) return "";
            if (value.regionMatches(true, 0, "ключ=", 0, 5)
                    || value.regionMatches(true, 0, "key=", 0, 4)) {
                value = value.substring(value.indexOf('=') + 1).trim();
            }
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1).trim();
            }
            return value;
        }
        return "";
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean hasKey() {
        return !key.isEmpty();
    }

    public String channelUrl() {
        return url + "/pre/" + project;
    }

    public String latestVersion() {
        return latestVersion;
    }

    public Path stagedJar() {
        return stagedJar;
    }

    public synchronized void start() {
        reload();
        if (!enabled) {
            plugin.getLogger().info("Пререлизы: канал выключен (пререлизы.включено: false).");
            return;
        }
        if (key.isEmpty()) {
            plugin.getLogger().info("Пререлизы: ключа нет — впиши его в plugins/HahGps/" + keyFile
                    + " (или задай HAHTOJER_DEV_KEY), и канал включится сам.");
            return;
        }
        plugin.getLogger().info("Пререлизы: канал включён — " + channelUrl()
                + ", проверка каждые " + intervalMinutes + " мин."
                + (parseProxy(proxyUrl) == null ? "" : " (через прокси " + proxyUrl + ")"));
        long period = intervalMinutes * 60L * 20L;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                () -> checkAsync(text -> { }, autoDownload, true), 20L * 90L, period);
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }

    }

    public void restart() {
        stop();
        start();
    }

    /** Разобранный адрес прокси: socks-прокси идёт через системные свойства JVM. */
    record ProxySpec(String host, int port, boolean socks) {
    }

    static ProxySpec parseProxy(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return null;
        boolean socks = text.toLowerCase(java.util.Locale.ROOT).startsWith("socks");
        int schemeEnd = text.indexOf("://");
        String rest = schemeEnd >= 0 ? text.substring(schemeEnd + 3) : text;
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        int colon = rest.lastIndexOf(':');
        if (colon <= 0 || colon == rest.length() - 1) return null;
        String host = rest.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(rest.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (port <= 0 || port > 65535 || host.isBlank()) return null;
        return new ProxySpec(host, port, socks);
    }

    private HttpURLConnection open(String path) throws IOException {
        URL target = URI.create(channelUrl() + path).toURL();
        ProxySpec spec = parseProxy(proxyUrl);
        Proxy proxy = spec == null || spec.socks()
                ? Proxy.NO_PROXY
                : new Proxy(Proxy.Type.HTTP, new InetSocketAddress(spec.host(), spec.port()));
        HttpURLConnection connection = (HttpURLConnection) target.openConnection(proxy);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-Dev-Key", key);
        connection.setRequestProperty("User-Agent", "hahgps-dev");
        return connection;
    }

    /** Какой пререлиз сейчас запущен: читаем devbuild.txt из собственного jar. */
    public Build installed() {
        if (installedRead) return installedCache;
        installedRead = true;
        try (InputStream in = plugin.getResource(BUILD_ENTRY)) {
            if (in == null) return null;
            installedCache = parseBuild(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("Пререлизы: не удалось прочитать " + BUILD_ENTRY
                    + " (" + e.getMessage() + ")");
        }
        return installedCache;
    }

    private static Build parseBuild(String text) {
        String version = "";
        String base = "";
        String file = "";
        String notes = "";
        String patched = "";
        for (String line : text.replace("\uFEFF", "").split("\\R")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name = line.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
            String value = line.substring(eq + 1).trim();
            switch (name) {
                case "version" -> version = value;
                case "base" -> base = value;
                case "file" -> file = value;
                case "notes" -> notes = value;
                case "patched" -> patched = value;
                default -> {
                }
            }
        }
        if (version.isEmpty()) return null;
        return new Build(version, base, file.isEmpty() ? patched : file, "", 0, "", notes);
    }

    /** Проверка канала: {@code installIfNewer=false} — только рассказать. */
    public void checkAsync(Consumer<String> feedback, boolean installIfNewer) {
        checkAsync(feedback, installIfNewer, false);
    }

    /**
     * Проверка канала.
     *
     * @param quiet {@code true} для плановой проверки по таймеру: в консоль пишется только
     *              смена итога («новее нет» → «есть пререлиз», ошибка и т.п.), а не одна и та же
     *              строка каждые две минуты.
     */
    public void checkAsync(Consumer<String> feedback, boolean installIfNewer, boolean quiet) {
        if (!enabled) {
            reply(feedback, quiet, "disabled", plugin.getMessages().get("dev-disabled"));
            return;
        }
        if (key.isEmpty()) {
            key = readKey();
        }
        if (key.isEmpty()) {
            reply(feedback, quiet, "no-key", plugin.getMessages().get("dev-no-key", "file", keyFile));
            return;
        }
        if (busy) {
            reply(feedback, quiet, "busy", plugin.getMessages().get("update-checking"));
            return;
        }
        busy = true;
        String current = currentVersion();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Build remote = fetchLatest();
                lastCheckMs = System.currentTimeMillis();
                lastError = "";
                if (remote == null) {
                    reply(feedback, quiet, "none", plugin.getMessages().get("dev-none"));
                    return;
                }
                latestVersion = remote.version();
                String base = remote.base() == null ? "" : remote.base();
                if (!base.isEmpty() && compare(currentBase(current), base) > 0) {
                    reply(feedback, quiet, "behind", plugin.getMessages().get("dev-behind",
                            "version", remote.version(), "base", base, "current", current));
                    return;
                }
                Build local = installed();
                String localVersion = local == null ? "" : local.version();
                if (!localVersion.isEmpty() && compare(remote.version(), localVersion) <= 0) {
                    reply(feedback, quiet, "latest", plugin.getMessages().get("dev-latest", "version", localVersion));
                    return;
                }
                reply(feedback, quiet, "available", plugin.getMessages().get("dev-available",
                        "version", remote.version(), "base", base.isEmpty() ? "?" : base));
                announce("dev-available", "version", remote.version(),
                        "base", base.isEmpty() ? "?" : base);
                if (!installIfNewer) return;
                Path jar = download(remote);
                stagedJar = jar;
                reply(feedback, quiet, "staged", plugin.getMessages().get("dev-staged", "version", remote.version()));
                if (hotApply && plugin.isEnabled() && !HotSwap.running()) {
                    final Path staged = jar;
                    Bukkit.getScheduler().runTask(plugin, () -> HotSwap.apply(plugin, staged, false, feedback));
                }
            } catch (KeyRejected e) {
                lastError = "сервер не принял ключ";
                plugin.getLogger().warning("Пререлизы: сервер " + channelUrl()
                        + " не принял ключ из plugins/HahGps/" + keyFile
                        + " — канал остановлен до /gps update reload.");
                reply(feedback, quiet, "key-rejected", plugin.getMessages().get("dev-key-rejected"));
                stopTask();
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                reply(feedback, quiet, "failed", plugin.getMessages().get("dev-failed", "error", lastError));
            } finally {
                busy = false;
            }
        });
    }

    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add("Пререлизы: " + channelUrl() + " · канал "
                + (enabled ? "включён" : "выключен") + " · ключ: " + (hasKey() ? "есть" : "нет"));
        Build local = installed();
        String remote = latestVersion.isEmpty() ? "—" : latestVersion;
        if (local == null) {
            out.add("Пререлиз: не установлен (сейчас " + currentVersion() + ") · на сервере: " + remote);
        } else {
            out.add("Пререлиз: " + local.version() + " (база " + (local.base().isEmpty() ? "?" : local.base())
                    + ") · на сервере: " + remote);
        }
        if (stagedJar != null) {
            out.add("Подготовлено к установке: " + stagedJar.getFileName());
        }
        out.add(lastCheckMs == 0 ? "Проверка пререлизов: ещё не было"
                : "Проверка пререлизов: "
                + new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm").format(new java.util.Date(lastCheckMs)));
        if (!lastError.isEmpty()) {
            out.add("Ошибка канала пререлизов: " + lastError);
        }
        return out;
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private Build fetchLatest() throws Exception {
        HttpURLConnection connection = open("/manifest.json");
        try {
            int code = connection.getResponseCode();
            if (code == 401 || code == 403) throw new KeyRejected();
            if (code != 200) throw new IOException("HTTP " + code);
            String body;
            try (InputStream in = connection.getInputStream()) {
                body = new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8);
            }
            Map<String, Object> root = Json.object(body);
            if (root.get("latest") instanceof Map<?, ?> latest) {
                return build(latest);
            }
            return null;
        } finally {
            connection.disconnect();
        }
    }

    private Path download(Build remote) throws Exception {
        Path dir = plugin.getDataFolder().toPath().resolve("update").resolve("dev");
        Files.createDirectories(dir);
        String name = safeName(remote.file(), remote.version());
        Path target = dir.resolve(name);
        if (Files.isRegularFile(target) && !remote.sha256().isEmpty()
                && remote.sha256().equalsIgnoreCase(sha256(target))) {
            return target;
        }
        Path tmp = dir.resolve(name + ".part");
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        HttpURLConnection connection = open("/jars/" + encoded);
        connection.setReadTimeout(120_000);
        int code = connection.getResponseCode();
        if (code == 401 || code == 403) {
            connection.disconnect();
            throw new KeyRejected();
        }
        if (code != 200) {
            connection.disconnect();
            throw new IOException("скачивание: HTTP " + code);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (InputStream in = connection.getInputStream();
             var out = Files.newOutputStream(tmp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                size += read;
            }
        } finally {
            connection.disconnect();
        }
        String got = HexFormat.of().formatHex(digest.digest());
        try {
            UpdaterService.ensureJar(tmp);
            if (!remote.sha256().isEmpty() && !remote.sha256().equalsIgnoreCase(got)) {
                throw new IOException("хэш скачанного файла не совпал с манифестом");
            }
            if (remote.size() > 0 && remote.size() != size) {
                throw new IOException("размер скачанного файла не совпал: " + size
                        + " вместо " + remote.size());
            }
            BuildToken.verify(tmp);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // мусорный .part не критичен
            }
            throw e;
        }
        plugin.getLogger().info("Пререлизы: скачан " + name + " (" + size + " байт, sha256 "
                + got.substring(0, 12) + ")");
        return target;
    }

    private static String safeName(String file, String version) {
        String name = file == null ? "" : file.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.isEmpty()) name = "hahgps-dev" + version + ".jar";
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) name = name + ".jar";
        return name;
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Build build(Map<?, ?> map) {
        return new Build(str(map.get("version")), str(map.get("base")), str(map.get("file")),
                str(map.get("sha256")), (long) toDouble(map.get("size")),
                str(map.get("published_at")), str(map.get("notes")));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    /** Сравнение номеров по числовым частям: {@code >0}, если left новее right. */
    static int compare(String left, String right) {
        int[] a = parts(left);
        int[] b = parts(right);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int l = i < a.length ? a[i] : 0;
            int r = i < b.length ? b[i] : 0;
            if (l != r) return l > r ? 1 : -1;
        }
        return 0;
    }

    private static int[] parts(String version) {
        String text = version == null ? "" : version.trim();
        int plus = text.indexOf('+');
        if (plus >= 0) text = text.substring(0, plus);
        List<Integer> out = new ArrayList<>();
        var matcher = VERSION_LIKE.matcher(text);
        while (matcher.find()) {
            try {
                out.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException e) {
                out.add(0);
            }
        }
        int[] result = new int[out.size()];
        for (int i = 0; i < result.length; i++) result[i] = out.get(i);
        return result;
    }

    private static String currentBase(String version) {
        if (version == null) return "";
        int plus = version.indexOf('+');
        return plus >= 0 ? version.substring(0, plus) : version;
    }

    private String currentVersion() {
        try {
            return UpdaterService.normalizeVersion(plugin.getPluginMeta().getVersion());
        } catch (Throwable t) {
            return "?";
        }
    }

    private void announce(String key, String... args) {
        try {
            UpdaterService updater = plugin.getUpdater();
            if (updater != null) {
                updater.announcer().announce(key, args);
            }
        } catch (Throwable t) {
            plugin.getLogger().info("Пререлизы: оповещение не ушло (" + t.getMessage() + ")");
        }
    }

    private void reply(Consumer<String> feedback, String text) {
        reply(feedback, false, "manual", text);
    }

    /**
     * Отдать ответ игроку (если он есть) и в консоль. Плановая проверка ({@code quiet}) в консоль не
     * пишет «новее нет» вовсе: это норма, а не событие. Остальные итоги — только при смене, иначе
     * каждые две минуты в лог падала одна и та же строка.
     */
    private void reply(Consumer<String> feedback, boolean quiet, String outcome, String text) {
        if (feedback != null) {
            String message = text == null ? "" : text;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> feedback.accept(message));
            } catch (Throwable ignored) {
                // плагин уже выключается — отвечать некуда
            }
        }
        if (quiet) {
            boolean log = logsQuiet(outcome, lastQuietOutcome);
            lastQuietOutcome = outcome;
            if (!log) return;
        }
        plugin.getLogger().info("Пререлизы: " + stripTags(text));
    }

    /** Пишет ли плановая проверка этот итог в консоль: только смену и только не «новее нет». */
    static boolean logsQuiet(String outcome, String previous) {
        return !outcome.equals(previous) && !QUIET_SILENT.contains(outcome);
    }

    private static String stripTags(String text) {
        return text == null ? "" : text.replaceAll("<[^>]*>", "");
    }

    private static final class KeyRejected extends IOException {
        KeyRejected() {
            super("ключ не принят");
        }
    }
}
