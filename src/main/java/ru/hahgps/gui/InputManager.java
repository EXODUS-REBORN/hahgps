package ru.hahgps.gui;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class InputManager {

    private static InputManager instance;

    private final Map<UUID, Consumer<String>> pending = new HashMap<>();

    public static InputManager getInstance() {
        if (instance == null) {
            instance = new InputManager();
        }
        return instance;
    }

    public void request(Player player, Component prompt, Consumer<String> callback) {
        pending.put(player.getUniqueId(), callback);
        player.sendMessage(prompt);
    }

    public boolean handleChat(Player player, String message) {
        Consumer<String> callback = pending.remove(player.getUniqueId());
        if (callback != null) {
            callback.accept(message);
            return true;
        }
        return false;
    }

    public boolean isPending(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    public void remove(Player player) {
        pending.remove(player.getUniqueId());
    }
}
