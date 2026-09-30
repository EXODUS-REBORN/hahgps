package ru.hahgps.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Базовый класс GUI-экрана в стиле основного плагина hahtojer:
 * градиентный заголовок, синяя палитра, холдер — сам экран.
 */
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

    /**
     * Заголовок в фирменном градиенте — как у меню основного плагина.
     * Свои цвета из сообщений снимаем (иначе теги MiniMessage показались бы
     * прямо в названии окна) и отдаём чистый текст в градиент.
     */
    public Component titleComponent() {
        String raw = title();
        String plain = raw == null ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(MM.deserialize(raw));
        return GuiTheme.gradientTitle(plain.trim());
    }

    public void open() {
        // Холдер — сам экран (никогда не null): иначе клики в GUI не работают.
        this.inventory = Bukkit.createInventory(this, size(), titleComponent());
        build();
        // Сначала открыть (это закроет прежний контейнер — свой инвентарь игрока
        // или предыдущее меню), и только потом регистрироваться: иначе
        // InventoryCloseEvent от закрытия старого контейнера снимет с регистрации
        // только что добавленное меню, и клики по нему перестанут отменяться.
        player.openInventory(inventory);
        GuiManager.getInstance().register(player, this);
    }

    /**
     * Перестроить и переоткрыть инвентарь.
     */
    public void refresh() {
        if (inventory != null) {
            inventory.clear();
            build();
        }
    }

    /** Открыть следующий экран: снять прежнюю регистрацию и открыть новый. */
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

    /** Бренд-предмет в углу меню (слот 44 в 6-строчных, 26 в 3-строчных). */
    protected void brand() {
        if (inventory == null) return;
        inventory.setItem(size() >= 54 ? 44 : 26, GuiTheme.brand(GuiTheme.version()));
    }

    /** Заполнить пустые слоты фирменными панелями. */
    protected void fillEmpty() {
        GuiTheme.fill(inventory);
    }
}
