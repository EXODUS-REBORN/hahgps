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

/**
 * Обработка кликов в GUI.
 */
public class GuiListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        GuiScreen gui = GuiManager.getInstance().get(player);
        if (gui == null) {
            // Меню открыто, но не зарегистрировано (например, осталось от прежней копии
            // плагина после перезагрузки). Клики всё равно отменяем, иначе из него можно
            // забрать предметы, и закрываем его.
            if (event.getView() != null && event.getView().getTopInventory() != null
                    && event.getView().getTopInventory().getHolder() instanceof GuiScreen) {
                event.setCancelled(true);
                player.closeInventory();
            }
            return;
        }
        // Клик только в верхнем инвентаре GUI
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
        // Снимаем с регистрации, только если закрывается именно наш GUI: закрытие
        // своего инвентаря (при открытии нового меню) регистрацию не трогает.
        if (!(event.getInventory().getHolder() instanceof GuiScreen)) {
            return;
        }
        GuiManager.getInstance().unregister(player);
    }
}
