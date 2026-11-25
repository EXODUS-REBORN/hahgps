package ru.hahgps.gui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.item.ItemIds;

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

    /** Вызывается при закрытии меню: здесь гасят повторяющиеся задачи экрана. */
    public void onClose() {
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


    protected void putBrand() {
        if (inventory == null) return;
        int slot = size() >= 54 ? 44 : 26;
        org.bukkit.inventory.ItemStack current = inventory.getItem(slot);
        // Экран заполняет все слоты панелями ДО вызова putBrand: раньше бренд
        // поэтому не появлялся нигде. Перекрываем только нашу панель, а не
        // реальный контент (в списках слот 44 может быть занят элементом).
        if (current == null || current.getType() == GuiTheme.FILLER) {
            inventory.setItem(slot, GuiTheme.brand(brandLore()));
        }
    }


    private List<String> brandLore() {
        try {
            String version = GuiTheme.version();
            var messages = HahGpsPlugin.getInstance().getMessages();
            return List.of(
                    messages.get("gui.brand-version", "version", version == null ? "" : version),
                    messages.get("gui.brand-project"),
                    messages.get("gui.brand-style"));
        } catch (Throwable t) {
            return List.of();
        }
    }


    protected List<Component> lore(String key, String... args) {
        try {
            return List.of(HahGpsPlugin.getInstance().getMessages().getComponentPlain(key, args));
        } catch (Throwable t) {
            return List.of();
        }
    }


    protected Component comp(String key, String... args) {
        return HahGpsPlugin.getInstance().getMessages().getComponentPlain(key, args);
    }


    protected String getMsg(String key) {
        try {
            return HahGpsPlugin.getInstance().getMessages().get(key);
        } catch (Throwable t) {
            return key;
        }
    }


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
            } else if (plugin.isEnabled()) {
                // Сервиса данных нет, но плагин жив: выходим в главный поток
                // напрямую. Отправка сообщений из async — неопределённость.
                try {
                    Bukkit.getScheduler().runTask(plugin, notify);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {

        }
    }


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

    /**
     * Единая сетка навигации: в меню на 54 слота «Назад» — 45, стрелки
     * страниц — 48/53, номер страницы — 49, «Закрыть» — 52. В меню на 27
     * слотов «Назад» — 22, «Закрыть» — 24 (бренд остаётся на 26).
     */
    protected static final int NAV_BACK_LARGE = 45;
    protected static final int NAV_PREV = 48;
    protected static final int NAV_INFO = 49;
    protected static final int NAV_CLOSE_LARGE = 52;
    protected static final int NAV_NEXT = 53;
    protected static final int NAV_BACK_SMALL = 22;
    protected static final int NAV_CLOSE_SMALL = 24;

    protected int navBackSlot() {
        return size() >= 54 ? NAV_BACK_LARGE : NAV_BACK_SMALL;
    }

    protected int navCloseSlot() {
        return size() >= 54 ? NAV_CLOSE_LARGE : NAV_CLOSE_SMALL;
    }

    protected void putNavBack() {
        if (inventory != null) {
            inventory.setItem(navBackSlot(), GuiTheme.navBack(getMsg("gui.back")));
        }
    }

    protected void putNavClose() {
        if (inventory != null) {
            inventory.setItem(navCloseSlot(), GuiTheme.navClose(getMsg("gui.close")));
        }
    }

    /** Стрелки страниц и номер страницы в общей нижней строке. */
    protected void putPager(int page, int pages) {
        if (inventory == null || size() < 54) return;
        if (page > 0) {
            inventory.setItem(NAV_PREV, GuiTheme.navBack(getMsg("gui.prev-page")));
        }
        inventory.setItem(NAV_INFO, GuiUtil.item(Material.BOOK,
                comp("gui.page-info", "page", String.valueOf(page + 1),
                        "pages", String.valueOf(pages)), null, ItemIds.PAGE_INFO));
        if (page < pages - 1) {
            inventory.setItem(NAV_NEXT, GuiTheme.navForward(getMsg("gui.next-page")));
        }
    }
}
