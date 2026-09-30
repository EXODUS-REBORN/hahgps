package ru.hahgps.gui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
public abstract class GuiScreen implements InventoryHolder {

    protected static final MiniMessage MM = MiniMessage.miniMessage();

    protected final Player player;
    protected Inventory inventory;

    public GuiScreen(Player player) {
        this.player = player;
    }

    public abstract String title();

    public abstract int size();

    public abstract void build();

    public abstract void onClick(int slot, ClickType clickType);

    public Component titleComponent() {
        String raw = title();
        String plain = raw == null ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(MM.deserialize(raw));
        return GuiTheme.gradientTitle(plain.trim());
    }

    public void open() {

        this.inventory = Bukkit.createInventory(this, size(), titleComponent());
        build();

        player.openInventory(inventory);
        GuiManager.getInstance().register(player, this);
    }

    public void refresh() {
        if (inventory != null) {
            inventory.clear();
            build();
        }
    }

    public static void openNext(Player p, GuiScreen next) {
        GuiManager.getInstance().unregister(p);
        next.open();
    }

    public Player getPlayer() {
        return player;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    protected void brand() {
        if (inventory == null) return;
        inventory.setItem(size() >= 54 ? 44 : 26, GuiTheme.brand(GuiTheme.version()));
    }

    protected void fillEmpty() {
        GuiTheme.fill(inventory);
    }
}
