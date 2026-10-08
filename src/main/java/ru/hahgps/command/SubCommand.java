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
     * Подсказки, отфильтрованные по префиксу. Tab-complete идёт в главном
     * потоке, поэтому длинные списки (все точки, все узлы графа) режутся
     * ещё и по количеству.
     */
    static List<String> filterByPrefix(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(java.util.Locale.ROOT);
        List<String> out = new java.util.ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(java.util.Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
            if (out.size() >= 100) {
                break;
            }
        }
        return out;
    }


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
        final String message = text;
        runSync(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(plugin.getMessages().getPrefixed("error-occurred", "error", message));
            }
        });
    }


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
        } catch (Throwable t) {
            // Не молча: иначе любой сбой планировщика выглядел бы как
            // «команда просто ничего не сделала».
            plugin.getLogger().log(Level.FINE, "HahGps: не удалось выйти на главный поток", t);
        }
    }
}
