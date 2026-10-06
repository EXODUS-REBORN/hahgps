package ru.hahgps.gui;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

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
        Pending entry = pending.remove(player.getUniqueId());
        if (entry == null) {
            return false;
        }
        if (System.currentTimeMillis() - entry.createdAt() > TIMEOUT_MS) {

            return false;
        }
        // Событие чата в Paper асинхронное: колбэк трогает инвентари, сообщения
        // и сервисы, поэтому переносим его в главный поток.
        if (Bukkit.isPrimaryThread()) {
            accept(entry, message);
        } else {
            try {
                Bukkit.getScheduler().runTask(ru.hahgps.HahGpsPlugin.getInstance(),
                        () -> accept(entry, message));
            } catch (Throwable t) {
                accept(entry, message);
            }
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
