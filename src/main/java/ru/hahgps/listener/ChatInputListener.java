package ru.hahgps.listener;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import ru.hahgps.gui.InputManager;


public class ChatInputListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(event.message());
        if (InputManager.getInstance().handleChat(event.getPlayer(), text)) {
            event.setCancelled(true);
        }
    }
}
