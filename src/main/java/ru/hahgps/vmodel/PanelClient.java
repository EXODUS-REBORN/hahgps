package ru.hahgps.vmodel;

import org.bukkit.configuration.file.FileConfiguration;
import ru.hahgps.HahGpsPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Level;

/**
 * Клиент панели EXODUS(OS) — тот же протокол, что у {@code exodus-bridge}:
 *
 * <pre>
 * GET  /api/minecraft/v1/vmodels/?server=N            — снимок (definitions + placed)
 * GET  /api/minecraft/v1/vmodels/?key=K&amp;server=N     — одна модель
 * POST /api/minecraft/v1/vmodels/?server=N            — place / remove расстановки
 * Authorization: Bearer &lt;токен&gt;
 * </pre>
 *
 * <p>Запросы идут через {@link HttpURLConnection}: он умеет http- и socks-прокси, а
 * {@code java.net.http.HttpClient} с SOCKS не работает (см. ловушку в DevChannel). Ответ читаем
 * своим {@link Json} — сторонних зависимостей в плагине нет.</p>
 */
public final class PanelClient {

    public record Response(int code, byte[] body) {

        public boolean ok() {
            return code >= 200 && code < 300;
        }

        public String text() {
            return body == null ? "" : new String(body, StandardCharsets.UTF_8);
        }
    }

    public static final String DEFAULT_URL = "https://vedamirs-support.ru";

    public static final int DEFAULT_SERVER = 1;

    public static final String DEFAULT_TOKEN_FILE = "models-token.txt";

    private final HahGpsPlugin plugin;

    private volatile boolean enabled;

    private volatile String base = DEFAULT_URL;

    private volatile int server = DEFAULT_SERVER;

    private volatile String token = "";

    private volatile String tokenFile = DEFAULT_TOKEN_FILE;

    private volatile String proxyUrl = "";

    private volatile int timeoutMillis = 7000;

    public PanelClient(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        FileConfiguration cfg = plugin.getConfigManager() == null
                ? plugin.getConfig()
                : plugin.getConfigManager().get();
        if (cfg == null) {
            cfg = plugin.getConfig();
        }
        enabled = cfg.getBoolean("vmodel.panel.enabled", true);
        String url = String.valueOf(cfg.getString("vmodel.panel.url", DEFAULT_URL)).trim();
        base = url.isEmpty() ? DEFAULT_URL : url.replaceAll("/+$", "");
        server = Math.max(0, cfg.getInt("vmodel.panel.server", DEFAULT_SERVER));
        tokenFile = String.valueOf(cfg.getString("vmodel.panel.token-file", DEFAULT_TOKEN_FILE)).trim();
        if (tokenFile.isEmpty()) {
            tokenFile = DEFAULT_TOKEN_FILE;
        }
        proxyUrl = String.valueOf(cfg.getString("vmodel.panel.proxy", "")).trim();
        if (proxyUrl.isEmpty()) {
            String env = System.getenv("HAHGPS_MODELS_PROXY");
            proxyUrl = env == null ? "" : env.trim();
        }
        timeoutMillis = Math.max(1000, Math.min(60000, cfg.getInt("vmodel.panel.timeout-ms", 7000)));
        token = readToken();
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean configured() {
        return enabled && !token.isBlank() && !base.isBlank();
    }

    public String base() {
        return base;
    }

    public int server() {
        return server;
    }

    public String tokenFile() {
        return tokenFile;
    }

    public boolean hasToken() {
        return !token.isBlank();
    }

    public Path tokenPath() {
        return plugin.getDataFolder().toPath().resolve(tokenFile);
    }

    /**
     * Заготовка файла с токеном: сам токен в репозиторий не попадает, его вписывает админ.
     * Пока файл пустой/с заглушкой, плагин работает только на локальном {@code models.yml}.
     */
    public static void ensureTokenFile(HahGpsPlugin plugin) {
        if (plugin == null) {
            return;
        }
        String name = String.valueOf(plugin.getConfig().getString("vmodel.panel.token-file",
                DEFAULT_TOKEN_FILE)).trim();
        if (name.isEmpty()) {
            name = DEFAULT_TOKEN_FILE;
        }
        Path file = plugin.getDataFolder().toPath().resolve(name);
        if (Files.exists(file)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "# Токен плагина из панели EXODUS(OS) → Сервер → API.\n"
                    + "# Без токена каталог моделей берётся только из models.yml.\n",
                    StandardCharsets.UTF_8);
        } catch (IOException error) {
            plugin.getLogger().log(Level.FINE, "HahGps: модели — файл токена не создан", error);
        }
    }

    public String readToken() {
        String env = System.getenv("HAHGPS_MODELS_TOKEN");
        if (env == null || env.isBlank()) {
            env = System.getenv("HAHTOJER_MODELS_TOKEN");
        }
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        Path file = tokenPath();
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                return text;
            }
        } catch (IOException error) {
            warn("чтение токена панели", error);
        }
        return "";
    }

    public String endpoint(String path) {
        return base + path + (path.contains("?") ? "&" : "?") + "server=" + server;
    }

    public byte[] get(String path) {
        Response response = request("GET", path, null);
        return response != null && response.ok() ? response.body() : null;
    }

    public Response post(String path, Map<String, Object> body) {
        return request("POST", path, body == null ? "{}" : Json.write(body));
    }

    public Response request(String method, String path, String payload) {
        if (!configured()) {
            return null;
        }
        HttpURLConnection connection = null;
        try {
            URL url = URI.create(endpoint(path)).toURL();
            connection = (HttpURLConnection) url.openConnection(proxy());
            connection.setRequestMethod(method);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "hahgps-vmodel");
            if (payload != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(code, stream == null ? new byte[0] : stream.readAllBytes());
        } catch (IOException | IllegalArgumentException error) {
            warn("запрос к панели " + path, error);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Proxy proxy() {
        String url = proxyUrl;
        if (url == null || url.isBlank()) {
            return Proxy.NO_PROXY;
        }
        try {
            String text = url.contains("://") ? url : "http://" + url;
            URI uri = URI.create(text);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return Proxy.NO_PROXY;
            }
            int port = uri.getPort();
            Proxy.Type type = text.startsWith("socks") ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
            if (port <= 0) {
                port = type == Proxy.Type.SOCKS ? 1080 : 8080;
            }
            return new Proxy(type, new InetSocketAddress(host, port));
        } catch (IllegalArgumentException broken) {
            warn("разбор прокси панели", broken);
            return Proxy.NO_PROXY;
        }
    }

    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private void warn(String what, Throwable error) {
        if (plugin != null) {
            plugin.getLogger().log(Level.FINE, "HahGps: модели — " + what, error);
        }
    }
}
