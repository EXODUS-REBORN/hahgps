package ru.hahgps.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import ru.hahgps.gui.InputManager;

/**
 * Перехват чата для ввода текста в GUI.
 */
public class ChatInputListener implements Listener {

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (InputManager.getInstance().handleChat(event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
        }
    }
}
