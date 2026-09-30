package ru.hahgps.listener;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import ru.hahgps.gui.GuiManager;
import ru.hahgps.gui.GuiScreen;

public class GuiListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        GuiScreen gui = GuiManager.getInstance().get(player);
        if (gui == null) {

            if (event.getView() != null && event.getView().getTopInventory() != null
                    && event.getView().getTopInventory().getHolder() instanceof GuiScreen) {
                event.setCancelled(true);
                player.closeInventory();
            }
            return;
        }

        if (event.getClickedInventory() == null
                || event.getClickedInventory().getType() == InventoryType.PLAYER) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        gui.onClick(event.getRawSlot(), event.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (GuiManager.getInstance().hasGui(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (!(event.getInventory().getHolder() instanceof GuiScreen)) {
            return;
        }
        GuiManager.getInstance().unregister(player);
    }
}
