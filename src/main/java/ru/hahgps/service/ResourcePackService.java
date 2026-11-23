package ru.hahgps.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.config.ConfigManager;
import ru.hahgps.config.MessagesManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Раздача ресурспака со стрелками по команде {@code /gps pack}.
 *
 * Пак не отправляется при входе: игрок сам просит его командой, и только
 * тогда клиент показывает окно установки. Источник — либо внешний URL из
 * конфига, либо встроенный {@code hahgps.zip} из jar, который раздаёт
 * маленький HTTP-сервер на {@code resource-pack.port}.
 */
public class ResourcePackService implements Listener {

    static final String RESOURCE = "hahgps.zip";
    /** Стабильный UUID пака: по нему Paper отличает наш статус от чужого пака. */
    static final UUID PACK_ID = UUID.nameUUIDFromBytes("hahgps-pack".getBytes(StandardCharsets.UTF_8));

    /**
     * Публичный адрес пака. Файл лежит на нашем сервере и отдаётся nginx'ом
     * через Cloudflare Tunnel (`hahgps-pack.service`), поэтому качается без
     * авторизации и за NAT. Плагин подставляет его, если в config.yml не
     * задан свой `resource-pack.url`.
     */
    static final String PUBLIC_URL = "https://hahgps.hakerone.ru/hahgps.zip";

    private final HahGpsPlugin plugin;
    private final ConfigManager config;
    private final MessagesManager messages;

    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile byte[] pack;
    private volatile byte[] sha1;
    private volatile String sha1Hex = "";
    private volatile String url;
    private volatile boolean enabled;
    /** Каталог веб-раздачи на этом же хосте; пусто — пак никуда не копируется. */
    private volatile String publishDir = "";
    /** UUID -> хэш версии, которую игрок уже видел (загрузил или отклонил). */
    private final Map<UUID, String> seenHashes = new ConcurrentHashMap<>();
    private File seenFile;

    public ResourcePackService(HahGpsPlugin plugin, ConfigManager config, MessagesManager messages) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
    }

    /** (Пере)запуск: читает пак и поднимает сервер, если внешний URL не задан. */
    public synchronized void start() {
        stop();
        enabled = config.isResourcePackEnabled();
        if (!enabled) {
            plugin.getLogger().info("HahGps: раздача ресурспака выключена (resource-pack.enabled=false).");
            return;
        }

        pack = readPack();
        sha1 = pack == null ? null : sha1(pack);
        sha1Hex = sha1 == null ? "" : hex(sha1);
        loadSeen();

        String configured = config.getResourcePackUrl();
        publishDir = config.getResourcePackPublish();
        // "local" — поднять свой HTTP-сервер; пусто — публичный адрес с нашего
        // сервера (nginx + Cloudflare Tunnel); иначе — используется указанный
        // URL как есть.
        if ("local".equalsIgnoreCase(configured)) {
            if (pack == null) {
                plugin.getLogger().warning("HahGps: в jar нет " + RESOURCE
                        + " — свой сервер поднять нечем. Соберите пак: python3 respack/build.py");
                return;
            }
            startServer();
            scheduleRefresh();
            return;
        }

        String external = configured == null || configured.isBlank() ? PUBLIC_URL : configured.trim();
        url = external;
        plugin.getLogger().info("HahGps: ресурспак раздаётся по адресу " + url);
        if (pack == null) {
            plugin.getLogger().warning("HahGps: в jar нет " + RESOURCE
                    + " — внешний URL всё равно будет отправлен, но хэш проверить нечем.");
        } else {
            // Плагин мог подняться рядом с веб-раздачей: обновляем файл, который
            // отдаёт nginx, иначе игроки продолжат качать старую версию.
            publish();
        }
        // Версия в URL — хэш того файла, который клиент реально скачает, а не
        // jar: сверяем в фоне, затем рассылаем автообновление.
        checkRemote(() -> scheduleRefresh());
    }

    public synchronized void reload() {
        start();
    }

    public synchronized void stop() {
        HttpServer current = server;
        server = null;
        if (current != null) {
            current.stop(0);
        }
        ExecutorService pool = executor;
        executor = null;
        if (pool != null) {
            pool.shutdownNow();
        }
        url = null;
    }

    public boolean isAvailable() {
        return url != null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Отправить пак конкретному игроку; вызывается из команды. */
    public void send(Player player) {
        if (!send(player, false) && player != null) {
            player.sendMessage(messages.getPrefixed(enabled ? "pack-unavailable" : "pack-disabled"));
        }
    }

    /**
     * Отправить пак и вернуть, ушёл ли он. {@code auto} — обновление игроку,
     * который уже ставил пак: команду ему вводить не нужно.
     */
    private boolean send(Player player, boolean auto) {
        if (player == null || !isAvailable()) {
            return false;
        }
        Component prompt = Component.text(config.getResourcePackPrompt());
        String base = server != null ? urlFor(player) : url;
        if (base == null || base.isBlank()) {
            plugin.getLogger().warning("HahGps: не удалось определить адрес сервера для "
                    + player.getName() + " — задайте resource-pack.host в config.yml.");
            return false;
        }
        boolean versioned = server != null || PUBLIC_URL.equals(base);
        String target = versioned ? versionedUrl(base) : base;
        // Хэш навязываем только за свой HTTP-сервер: там уходит ровно тот файл,
        // что лежит в jar. Публичный адрес с GitHub живёт отдельно от сборки
        // плагина и может отставать — тогда клиент скачивает другой файл и
        // падает с «Не удалось загрузить 1 из 1 наборов». Без хэша клиент
        // принимает то, что отдал сервер, а свежесть обеспечивает ?v= в URL.
        boolean hashKnown = sha1 != null && server != null;
        try {
            if (hashKnown) {
                player.setResourcePack(PACK_ID, target, sha1, prompt, false);
            } else {
                player.setResourcePack(PACK_ID, target, (byte[]) null, prompt, false);
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "HahGps: пак не отправлен " + player.getName(), t);
            return false;
        }
        if (auto) {
            player.sendMessage(messages.getPrefixed("pack-auto-updated"));
        } else {
            player.sendMessage(messages.getPrefixed("pack-sent"));
        }
        return true;
    }

    /** Сменить версию в URL: клиент не должен брать старый пак из кэша. */
    private String versionedUrl(String base) {
        String hash = sha1Hex;
        if (hash == null || hash.isBlank()) {
            return base;
        }
        String version = hash.substring(0, Math.min(12, hash.length()));
        return base + (base.contains("?") ? "&" : "?") + "v=" + version;
    }

    private static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            out.append(String.format(java.util.Locale.ROOT, "%02x", b));
        }
        return out.toString();
    }

    private void scheduleRefresh() {
        if (!enabled || !config.isResourcePackAutoUpdate()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTaskLater(plugin, this::refreshOnline, 60L);
        } catch (Throwable ignored) {
        }
    }

    /** После старта/обновления плагина досылаем новый пак тем, кто ставил старый. */
    private void refreshOnline() {
        if (!enabled || !config.isResourcePackAutoUpdate() || !isAvailable()) {
            return;
        }
        String current = sha1Hex;
        for (Player player : Bukkit.getOnlinePlayers()) {
            String seen = seenHashes.get(player.getUniqueId());
            if (seen != null && !seen.equals(current)) {
                send(player, true);
            } else if (seen == null && player.hasResourcePack()
                    && !current.equals(player.getResourcePackHash())) {
                // Первый запуск с отслеживанием: игрок ставил пак старой
                // версией плагина, когда pack-seen.yml ещё не было.
                send(player, true);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled || !config.isResourcePackAutoUpdate() || !isAvailable()) {
            return;
        }
        Player player = event.getPlayer();
        String seen = seenHashes.get(player.getUniqueId());
        if (seen == null && !player.hasResourcePack()) {
            return;
        }
        if (seen != null && seen.equals(sha1Hex)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                send(player, true);
            }
        }, 40L);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (event == null || !PACK_ID.equals(event.getID())) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        PlayerResourcePackStatusEvent.Status status = event.getStatus();
        if (status == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD
                || status == PlayerResourcePackStatusEvent.Status.INVALID_URL
                || status == PlayerResourcePackStatusEvent.Status.FAILED_RELOAD) {
            // Скачивание сорвалось: версию «увиденной» не помечаем, иначе игрок
            // больше не получит автоповтор. В лог — адрес, по нему видно причину
            // (обычно сеть или рассинхрон хэша).
            plugin.getLogger().warning("HahGps: игрок " + player.getName()
                    + " не смог загрузить ресурспак (" + status + "): " + url);
            return;
        }
        String hash = event.getHash();
        if (hash == null || hash.isBlank()) {
            hash = sha1Hex;
        }
        if (hash != null && !hash.isBlank()) {
            hash = hash.trim().toLowerCase(java.util.Locale.ROOT);
            seenHashes.put(player.getUniqueId(), hash);
            saveSeen();
        }
    }

    private void loadSeen() {
        seenHashes.clear();
        seenFile = new File(plugin.getDataFolder(), "pack-seen.yml");
        if (!seenFile.isFile()) {
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(seenFile);
            for (String key : yaml.getKeys(false)) {
                try {
                    String hash = yaml.getString(key);
                    if (hash != null && !hash.isBlank()) {
                        seenHashes.put(UUID.fromString(key), hash);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "HahGps: не удалось прочитать pack-seen.yml", t);
        }
    }

    private synchronized void saveSeen() {
        File file = seenFile;
        if (file == null) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        seenHashes.forEach((id, hash) -> yaml.set(id.toString(), hash));
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.FINE, "HahGps: не удалось сохранить pack-seen.yml", e);
        }
    }

    /**
     * URL пака для конкретного игрока. Приоритет: `resource-pack.host`,
     * затем адрес, по которому игрок подключился (virtual host), затем
     * автоопределённый локальный адрес.
     */
    private String urlFor(Player player) {
        String host = config.getResourcePackHost();
        if (host == null || host.isBlank()) {
            host = virtualHost(player);
        }
        if (host == null || host.isBlank()) {
            host = resolveHost();
        }
        if (host == null || host.isBlank() || "127.0.0.1".equals(host) || "0.0.0.0".equals(host)) {
            return null;
        }
        return "http://" + bracket(host) + ":" + config.getResourcePackPort() + "/" + RESOURCE;
    }

    /** Хост, к которому подключался клиент (IP или домен), без порта. */
    private static String virtualHost(Player player) {
        try {
            InetSocketAddress virtual = player.getVirtualHost();
            if (virtual == null) {
                return null;
            }
            String host = virtual.getHostString();
            if (host == null || host.isBlank() || "0.0.0.0".equals(host) || "::".equals(host)) {
                return null;
            }
            return host;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String bracket(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private byte[] readPack() {
        try (InputStream in = plugin.getResource(RESOURCE)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "HahGps: не удалось прочитать " + RESOURCE, e);
            return null;
        }
    }

    /**
     * Обновить файл веб-раздачи: nginx на этом же хосте отдаёт его через
     * Cloudflare Tunnel. Копируем, только если байты отличаются, — лишняя
     * запись меняет mtime и сбивает кэш. На машине без веб-раздачи ключ
     * `resource-pack.publish` просто не задан.
     */
    public void publish() {
        String dir = publishDir;
        byte[] data = pack;
        if (dir == null || dir.isBlank() || data == null) {
            return;
        }
        File target = new File(dir, RESOURCE);
        try {
            if (writeIfDifferent(target, data)) {
                plugin.getLogger().info("HahGps: ресурспак опубликован — " + target
                        + " (" + data.length + " байт)");
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "HahGps: не удалось опубликовать ресурспак в " + target, e);
        }
    }

    /** Хэш файла веб-раздачи; пусто — каталог не задан или файла нет. */
    private String publishedHash() {
        String dir = publishDir;
        if (dir == null || dir.isBlank()) {
            return "";
        }
        File target = new File(dir, RESOURCE);
        if (!target.isFile()) {
            return "";
        }
        try {
            return hex(sha1(java.nio.file.Files.readAllBytes(target.toPath())));
        } catch (IOException e) {
            return "";
        }
    }

    /** Записать байты, если они отличаются от лежащих в файле; true — обновлён. */
    static boolean writeIfDifferent(File target, byte[] data) throws IOException {
        if (target.isFile()) {
            byte[] current = java.nio.file.Files.readAllBytes(target.toPath());
            if (java.util.Arrays.equals(current, data)) {
                return false;
            }
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("нет каталога " + parent);
        }
        java.nio.file.Files.write(target.toPath(), data);
        return true;
    }

    /**
     * Сверить встроенный пак с файлом по внешнему адресу.
     *
     * Клиент качает не jar, а этот файл, поэтому версия {@code ?v=} должна
     * быть хэшем именно его: иначе после обновления пака на сервере клиенты
     * продолжат брать старую версию из кэша. Расхождение с jar не ломает
     * установку (хэш для внешнего адреса не навязывается), но это сигнал,
     * что в раздачу забыли положить свежий файл.
     */
    private void checkRemote(Runnable after) {
        String target = url;
        if (target == null || server != null) {
            return;
        }
        String published = publishedHash();
        if (!published.isBlank()) {
            // Файл раздачи лежит на этом же хосте — берём хэш у него и не
            // тратим трафик на скачивание через туннель.
            if (pack != null && !published.equals(sha1Hex)) {
                plugin.getLogger().warning("HahGps: в раздаче " + publishDir
                        + " лежит другой файл, чем в jar (" + published.substring(0, 12)
                        + " != " + sha1Hex.substring(0, Math.min(12, sha1Hex.length()))
                        + ") — проверьте, что nginx отдаёт именно " + RESOURCE + ".");
            }
            sha1Hex = published;
            runLater(after);
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                byte[] remote = download(target);
                if (remote == null) {
                    plugin.getLogger().warning("HahGps: не удалось проверить ресурспак по адресу "
                            + target + " — оставляю версию из jar.");
                } else {
                    String remoteHex = hex(sha1(remote));
                    if (pack != null && !remoteHex.equals(sha1Hex)) {
                        plugin.getLogger().warning("HahGps: ресурспак по адресу " + target
                                + " отличается от встроенного в jar (" + remoteHex.substring(0, 12)
                                + " != " + sha1Hex.substring(0, Math.min(12, sha1Hex.length()))
                                + "). Проверьте раздачу или пересоберите плагин.");
                    }
                    sha1Hex = remoteHex;
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "HahGps: проверка адреса пака не удалась", t);
            } finally {
                runLater(after);
            }
        }, "HahGps-Pack-Check");
        thread.setDaemon(true);
        thread.start();
    }

    /** Выполнить на главном потоке; из потока проверки это обязательно. */
    private void runLater(Runnable task) {
        if (task == null) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (Throwable ignored) {
        }
    }

    /** Скачать файл по адресу (для сверки хэша); null — не вышло. */
    private static byte[] download(String address) {
        try {
            java.net.URLConnection connection = java.net.URI.create(address).toURL().openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "HahGps");
            try (InputStream in = connection.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void startServer() {
        int port = config.getResourcePackPort();
        String host = resolveHost();
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
            http.createContext("/" + RESOURCE, this::serve);
            ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "HahGps-Pack");
                thread.setDaemon(true);
                return thread;
            });
            http.setExecutor(pool);
            http.start();
            server = http;
            executor = pool;
            url = "http://" + host + ":" + port + "/" + RESOURCE;
            plugin.getLogger().info("HahGps: ресурспак раздаётся по " + url);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "HahGps: не удалось поднять HTTP-сервер ресурспака на порту " + port
                            + " (порт занят? задайте resource-pack.url или другой порт)", e);
        }
    }

    private void serve(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = pack;
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException ignored) {
            // Клиент закрыл соединение — это не ошибка сервера.
        } finally {
            exchange.close();
        }
    }

    /**
     * Адрес, который увидят клиенты: конфиг, адрес интерфейса default-маршрута
     * или первый «настоящий» локальный IPv4.
     *
     * Просто «первый site-local» на сервере с Docker выбирает docker0/br-*
     * (172.x.x.x), и клиенты не могут скачать пак. Поэтому на Linux сначала
     * смотрим интерфейс default-маршрута (/proc/net/route), а виртуальные
     * мосты и туннели пропускаем.
     */
    private String resolveHost() {
        String configured = config.getResourcePackHost();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String bound = Bukkit.getIp();
        if (bound != null && !bound.isBlank() && !"0.0.0.0".equals(bound) && !"::".equals(bound)) {
            return bound;
        }
        String routed = siteLocalIpv4(defaultRouteInterface());
        if (routed != null) {
            return routed;
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || isVirtualInterface(ni)) {
                    continue;
                }
                String address = siteLocalIpv4(ni);
                if (address != null) {
                    return address;
                }
            }
        } catch (Exception ignored) {
            // ниже — запасной вариант
        }
        return "127.0.0.1";
    }

    /** Имя интерфейса default-маршрута на Linux; null — не Linux/не нашли. */
    private static String defaultRouteInterface() {
        try {
            java.nio.file.Path route = java.nio.file.Path.of("/proc/net/route");
            if (!java.nio.file.Files.isReadable(route)) {
                return null;
            }
            for (String line : java.nio.file.Files.readAllLines(route)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2 && "00000000".equals(parts[1])) {
                    return parts[0];
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String siteLocalIpv4(String interfaceName) {
        if (interfaceName == null) {
            return null;
        }
        try {
            return siteLocalIpv4(NetworkInterface.getByName(interfaceName));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String siteLocalIpv4(NetworkInterface ni) {
        if (ni == null) {
            return null;
        }
        // Сначала адрес подсети (/24), а не /32-алиас: на этом сервере
        // 192.168.0.65 — точечный адрес для DNS, канонический — 192.168.0.64.
        for (java.net.InterfaceAddress entry : ni.getInterfaceAddresses()) {
            InetAddress address = entry.getAddress();
            if (address instanceof Inet4Address && address.isSiteLocalAddress()
                    && entry.getNetworkPrefixLength() < 32) {
                return address.getHostAddress();
            }
        }
        for (InetAddress address : Collections.list(ni.getInetAddresses())) {
            if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                return address.getHostAddress();
            }
        }
        return null;
    }

    private static boolean isVirtualInterface(NetworkInterface ni) {
        String name = ni.getName().toLowerCase(java.util.Locale.ROOT);
        return name.startsWith("docker") || name.startsWith("br-") || name.startsWith("veth")
                || name.startsWith("virbr") || name.startsWith("tun") || name.startsWith("tap")
                || name.startsWith("tailscale") || name.startsWith("pterodactyl");
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            return new byte[0];
        }
    }


}
