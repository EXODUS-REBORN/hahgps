package ru.hahgps.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.config.ConfigManager;
import ru.hahgps.config.MessagesManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
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
public class ResourcePackService {

    static final String RESOURCE = "hahgps.zip";

    /**
     * Публичный адрес пака: GitHub-репозиторий ресурспака (он публичный,
     * поэтому клиенты качают без авторизации). Плагин подставляет его, если
     * в config.yml не задан свой `resource-pack.url`.
     */
    static final String PUBLIC_URL =
            "https://raw.githubusercontent.com/EXODUS-REBORN/hahgps-pack/main/hahgps.zip";

    private final HahGpsPlugin plugin;
    private final ConfigManager config;
    private final MessagesManager messages;

    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile byte[] pack;
    private volatile byte[] sha1;
    private volatile String url;
    private volatile boolean enabled;

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

        String configured = config.getResourcePackUrl();
        // "local" — поднять свой HTTP-сервер; пусто — публичный адрес из репозитория
        // ресурспака; иначе — используется указанный URL как есть.
        if ("local".equalsIgnoreCase(configured)) {
            if (pack == null) {
                plugin.getLogger().warning("HahGps: в jar нет " + RESOURCE
                        + " — свой сервер поднять нечем. Соберите пак: python3 respack/build.py");
                return;
            }
            startServer();
            return;
        }

        String external = configured == null || configured.isBlank() ? PUBLIC_URL : configured.trim();
        url = external;
        plugin.getLogger().info("HahGps: ресурспак раздаётся по адресу " + url);
        if (pack == null) {
            plugin.getLogger().warning("HahGps: в jar нет " + RESOURCE
                    + " — внешний URL всё равно будет отправлен, но хэш проверить нечем.");
        }
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

    /** Отправить пак конкретному игроку; вызывается только из команды. */
    public void send(Player player) {
        if (!isAvailable()) {
            player.sendMessage(messages.getPrefixed(enabled ? "pack-unavailable" : "pack-disabled"));
            return;
        }
        Component prompt = Component.text(config.getResourcePackPrompt());
        if (server != null) {
            // Адрес берём тот, через который игрок зашёл на сервер: в Docker
            // автоопределение находило 172.x.x.x контейнера, и клиент не мог
            // скачать пак («Не удалось загрузить 1 из 1 наборов»).
            String playerUrl = urlFor(player);
            if (playerUrl == null) {
                plugin.getLogger().warning("HahGps: не удалось определить адрес сервера для "
                        + player.getName() + " — задайте resource-pack.host в config.yml.");
                player.sendMessage(messages.getPrefixed("pack-unavailable"));
                return;
            }
            if (sha1 != null) {
                player.setResourcePack(playerUrl, sha1, prompt, false);
            } else {
                player.setResourcePack(playerUrl);
            }
        } else {
            // Внешний URL может указывать на другой файл: хэш и prompt не навязываем.
            player.setResourcePack(url);
        }
        player.sendMessage(messages.getPrefixed("pack-sent"));
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
