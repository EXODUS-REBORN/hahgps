package ru.hahgps.gui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import ru.hahgps.HahGpsPlugin;

import java.util.List;
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
                .plainText().serialize(GuiTheme.safe(raw));
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
        putBrand();
    }

    /**
     * Бренд-предмет в углу меню (44 — для больших инвентарей, 26 — для маленьких),
     * как в hahtojer. Ставится только в пустой слот, чтобы не затирать содержимое.
     */
    protected void putBrand() {
        if (inventory == null) return;
        int slot = size() >= 54 ? 44 : 26;
        if (inventory.getItem(slot) == null) {
            inventory.setItem(slot, GuiTheme.brand(GuiTheme.version()));
        }
    }

    /** Пояснение под кнопкой из messages_*.yml. */
    protected List<Component> lore(String key, String... args) {
        try {
            return List.of(HahGpsPlugin.getInstance().getMessages().getComponent(key, args));
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** Компонент из messages_*.yml. */
    protected Component comp(String key, String... args) {
        return HahGpsPlugin.getInstance().getMessages().getComponent(key, args);
    }

    /** Строка из messages_*.yml (без разбора разметки) — для подстановки в плейсхолдеры. */
    protected String getMsg(String key) {
        try {
            return HahGpsPlugin.getInstance().getMessages().get(key);
        } catch (Throwable t) {
            return key;
        }
    }

    /**
     * Сбой фоновой операции из GUI: причина — в лог, игроку — «Произошла ошибка».
     * Без этого любой сбой БД оставлял игрока в молчании (выполнялось thenRun/thenAccept
     * без обработки исключений).
     */
    protected void reportError(Throwable error) {
        if (error == null) return;
        try {
            HahGpsPlugin plugin = HahGpsPlugin.getInstance();
            plugin.getLogger().log(java.util.logging.Level.WARNING, "HahGps: GUI-операция не удалась", error);
            String text = rootMessage(error);
            Runnable notify = () -> {
                if (player.isOnline()) {
                    player.sendMessage(plugin.getMessages().getPrefixed("error-occurred", "error", text));
                }
            };
            if (Bukkit.isPrimaryThread()) {
                notify.run();
            } else if (plugin.getDataService() != null) {
                plugin.getDataService().runSync(notify);
            } else {
                notify.run();
            }
        } catch (Throwable ignored) {
            // сообщение об ошибке не должно само падать
        }
    }

    /** Самая глубокая причина исключения — короткий текст для игрока. */
    protected static String rootMessage(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && t.getCause().getMessage() != null) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }

    protected void fillEmpty() {
        GuiTheme.fill(inventory);
    }
}
