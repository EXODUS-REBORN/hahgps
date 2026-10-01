package ru.hahgps.gui;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class InputManager {

    /** Сколько времени ждём ответа в чате, прежде чем снять запрос (мс). */
    private static final long TIMEOUT_MS = 60_000L;

    private record Pending(Consumer<String> callback, long createdAt) {
    }

    private static InputManager instance = new InputManager();

    // Чат приходит из асинхронного потока — только потокобезопасная коллекция
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public static InputManager getInstance() {
        return instance;
    }

    public void request(Player player, net.kyori.adventure.text.Component prompt, Consumer<String> callback) {
        pending.put(player.getUniqueId(), new Pending(callback, System.currentTimeMillis()));
        player.sendMessage(prompt);
    }

    public boolean handleChat(Player player, String message) {
        Pending entry = pending.remove(player.getUniqueId());
        if (entry == null) {
            return false;
        }
        if (System.currentTimeMillis() - entry.createdAt() > TIMEOUT_MS) {
            // Время вышло — пускаем сообщение в обычный чат
            return false;
        }
        try {
            entry.callback().accept(message);
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[HahGps] Ошибка обработки ввода: " + t);
        }
        return true;
    }

    public boolean isPending(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    public void remove(Player player) {
        pending.remove(player.getUniqueId());
    }
}
