package ru.hahgps.gui;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class InputManager {


    private static final long TIMEOUT_MS = 60_000L;

    private record Pending(Consumer<String> callback, long createdAt) {
    }

    private static InputManager instance = new InputManager();


    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public static InputManager getInstance() {
        return instance;
    }

    public void request(Player player, net.kyori.adventure.text.Component prompt, Consumer<String> callback) {
        pending.put(player.getUniqueId(), new Pending(callback, System.currentTimeMillis()));
        player.sendMessage(prompt);
    }

    public boolean handleChat(Player player, String message) {
        Pending entry = pending.get(player.getUniqueId());
        if (entry == null) {
            return false;
        }
        if (System.currentTimeMillis() - entry.createdAt() > TIMEOUT_MS) {
            // Просрочено: снимаем промпт и честно предупреждаем. Раньше запись
            // удалялась до проверки, и фраза молча уходила в публичный чат.
            pending.remove(player.getUniqueId());
            player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<gray>Время ввода истекло — начните заново.</gray>"));
            return false;
        }
        pending.remove(player.getUniqueId());
        // Событие чата в Paper асинхронное: колбэк трогает инвентари,
        // сообщения и сервисы, поэтому переносим его в главный поток.
        if (Bukkit.isPrimaryThread()) {
            accept(entry, message);
            return true;
        }
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (plugin == null || !plugin.isEnabled()) {
            return true;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> accept(entry, message));
        } catch (Throwable t) {
            // Никакого fallback'а в async-потоке: колбэки трогают инвентари и
            // БД, и выполнение их мимо главного потока — неопределённость.
            Bukkit.getLogger().warning("[HahGps] Не удалось выйти в главный поток для ввода: " + t);
        }
        return true;
    }

    private void accept(Pending entry, String message) {
        try {
            entry.callback().accept(message);
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[HahGps] Ошибка обработки ввода: " + t);
        }
    }

    public boolean isPending(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    public void remove(Player player) {
        pending.remove(player.getUniqueId());
    }
}
