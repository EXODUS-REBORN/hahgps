package ru.hahgps.gui;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.item.ItemIds;
import ru.hahgps.model.TransportType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;


public class TransportSelectGui extends GuiScreen {


    private static final int[][] LAYOUT = {
            {13},
            {11, 15},
            {10, 13, 16},
            {10, 12, 14, 16},
            {9, 11, 13, 15, 17}
    };

    private final Consumer<TransportType> callback;
    private final Map<Integer, TransportType> slotToType = new HashMap<>();

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

    private List<TransportType> availableTypes() {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        List<TransportType> list = new ArrayList<>();
        for (TransportType type : TransportType.values()) {
            if (plugin.getConfigManager().isTransportAvailable(type.getKey())) {
                list.add(type);
            }
        }
        return list;
    }

    private static Material iconFor(TransportType type) {
        return switch (type) {
            case FOOT -> Material.LEATHER_BOOTS;
            case CAR -> Material.MINECART;
            case TRANSIT -> Material.CLOCK;
            case AIR -> Material.FEATHER;
            case WATER -> Material.OAK_BOAT;
        };
    }

    @Override
    public void build() {
        List<TransportType> types = availableTypes();
        int[] slots = LAYOUT[Math.min(types.size(), LAYOUT.length) - 1];
        slotToType.clear();

        for (int i = 0; i < types.size() && i < slots.length; i++) {
            TransportType type = types.get(i);
            slotToType.put(slots[i], type);
            inventory.setItem(slots[i], GuiUtil.item(iconFor(type),
                    comp("gui.transport-" + type.getKey()),
                    lore("gui.lore-transport-" + type.getKey()),
                    ItemIds.TRANSPORT_BASE + type.ordinal()));
        }

        inventory.setItem(22, GuiTheme.navBack(getMsg("gui.back")));

        for (int i = 0; i < size(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, GuiUtil.filler());
            }
        }
        putBrand();
    }

    @Override
    public void onClick(int slot, ClickType clickType) {
        if (slot == 22) {
            player.closeInventory();
            return;
        }
        TransportType type = slotToType.get(slot);
        if (type != null) {
            GuiTheme.click(player);
            player.closeInventory();
            if (callback != null) {
                callback.accept(type);
            }
        }
    }
}
