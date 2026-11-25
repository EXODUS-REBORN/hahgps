package ru.hahgps.service;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.config.ConfigManager;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Ссылка на пак сервера. Файл собирает и раздаёт панель EXODUS(OS)
 * (`vedamirs-support.ru`), плагин его не хранит и нигде не хостит — только
 * сообщает клиенту адрес при входе, чтобы модели и иконки были на месте.
 *
 * Своего пакета у плагина нет: ни zip внутри jar, ни HTTP-сервера,
 * ни публикации в каталог веб-раздачи.
 */
public class ResourcePackService implements Listener {

    /** Стабильный UUID пака: по нему Paper отличает наш статус от чужого. */
    static final UUID PACK_ID = UUID.nameUUIDFromBytes("hahgps-panel-pack".getBytes(StandardCharsets.UTF_8));

    private final HahGpsPlugin plugin;
    private final ConfigManager config;

    public ResourcePackService(HahGpsPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() {
        if (!config.isResourcePackEnabled()) {
            plugin.getLogger().info("HahGps: ссылка на пак панели выключена (resource-pack.enabled=false).");
            return;
        }
        plugin.getLogger().info("HahGps: пак берётся с панели — " + config.getResourcePackUrl());
    }

    public void reload() {
        start();
    }

    /** Останавливать нечего: плагин пак не раздаёт. */
    public void stop() {
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!config.isResourcePackEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> send(player), 40L);
    }

    private void send(Player player) {
        String url = config.getResourcePackUrl();
        if (player == null || !player.isOnline() || url == null || url.isBlank()) {
            return;
        }
        Component prompt = Component.text(config.getResourcePackPrompt());
        try {
            // Хэш не навязываем: панель пересобирает пак по кнопке, файл за тем
            // же адресом меняется, и жёсткий sha1 сломал бы установку.
            player.setResourcePack(PACK_ID, url, (byte[]) null, prompt, false);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "HahGps: пак не отправлен " + player.getName(), t);
        }
    }
}
