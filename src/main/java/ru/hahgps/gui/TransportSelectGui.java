package ru.hahgps.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.model.TransportType;

import java.util.List;
import java.util.function.Consumer;

/**
 * Выбор транспорта перед построением маршрута.
 */
public class TransportSelectGui extends GuiScreen {

    private final Consumer<TransportType> callback;

    public TransportSelectGui(Player player, Consumer<TransportType> callback) {
        super(player);
        this.callback = callback;
    }

    @Override
    public String title() {
        return HahGpsPlugin.getInstance().getMessages().get("gui.transport-title");
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void build() {
        inventory.setItem(11, GuiUtil.item(Material.LEATHER_BOOTS,
                HahGpsPlugin.getInstance().getMessages().getComponent("gui.transport-foot"),
                List.of(Component.text("Маршрут по тропинкам и пешеходным переходам", NamedTextColor.GRAY))));

        inventory.setItem(15, GuiUtil.item(Material.MINECART,
                HahGpsPlugin.getInstance().getMessages().getComponent("gui.transport-car"),
                List.of(Component.text("Маршрут по дорогам для авто", NamedTextColor.GRAY))));

        inventory.setItem(22, GuiUtil.item(Material.ARROW,
                HahGpsPlugin.getInstance().getMessages().getComponent("gui.back")));

        for (int i = 0; i < size(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, GuiUtil.filler());
            }
        }
    }

    @Override
    public void onClick(int slot, ClickType clickType) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        switch (slot) {
            case 11 -> {
                player.closeInventory();
                callback.accept(TransportType.FOOT);
            }
            case 15 -> {
                player.closeInventory();
                callback.accept(TransportType.CAR);
            }
            case 22 -> player.closeInventory();
        }
    }
}
