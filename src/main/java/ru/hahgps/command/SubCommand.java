package ru.hahgps.command;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.model.TransportType;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;

public interface SubCommand {

    String name();

    String usage();

    String permission();

    default List<SubCommand> children() {
        return List.of();
    }

    default boolean isLeaf() {
        return children().isEmpty();
    }

    void execute(Player player, String[] args);

    List<String> tabComplete(Player player, String[] args);

    /**
     * Разбор значения транспорта из аргумента команды.
     * Возвращает null, если значение не соответствует ни одному типу —
     * вызывающая сторона должна показать usage, а не молча строить маршрут «пешком».
     */
    static TransportType parseTransport(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        for (TransportType type : TransportType.values()) {
            if (type.getKey().equalsIgnoreCase(key.trim())) {
                return type;
            }
        }
        return null;
    }

    /**
     * Единая обработка ошибок асинхронных операций: полный стек — в лог,
     * игроку — сообщение из ключа {@code error-occurred} на главном потоке.
     * Без этого игрока команда «висит» без всякого ответа.
     */
    static void sendError(Player player, Throwable error) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (plugin == null || error == null) {
            return;
        }
        Throwable cause = error;
        while (cause.getCause() != null
                && (cause instanceof CompletionException || cause instanceof ExecutionException)) {
            cause = cause.getCause();
        }
        String text = cause.getMessage();
        if (text == null || text.isBlank()) {
            text = cause.getClass().getSimpleName();
        }
        plugin.getLogger().log(Level.WARNING, "HahGps: операция не выполнена", cause);
        final String message = text; // effectively final — нужен для лямбды
        runSync(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(plugin.getMessages().getPrefixed("error-occurred", "error", message));
            }
        });
    }

    /** Нарушение UNIQUE — дубликат имени (SQLite и MySQL сообщают об этом по-разному). */
    static boolean isDuplicate(Throwable err) {
        Throwable t = err;
        while (t != null) {
            String m = t.getMessage();
            if (m != null) {
                String low = m.toLowerCase(java.util.Locale.ROOT);
                if (low.contains("unique") || low.contains("duplicate")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    /** Запуск на главном потоке с защитой от выключенного/выгружаемого плагина. */
    static void runSync(HahGpsPlugin plugin, Runnable task) {
        if (plugin == null || task == null || !plugin.isEnabled()) {
            return;
        }
        try {
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        } catch (Throwable ignored) {
            // плагин выключили между вызовами — ответ игроку уже не нужен
        }
    }
}
