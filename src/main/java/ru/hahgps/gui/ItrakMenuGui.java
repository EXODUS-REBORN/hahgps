package ru.hahgps.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.subcommands.ItrakCommand;
import ru.hahgps.item.ItemIds;
import ru.hahgps.model.Tracker;
import ru.hahgps.service.TrackerService;
import ru.hahgps.util.LocationUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Список трекеров iTrak. Экран обновляется раз в секунду, пока открыт:
 * координаты трекера, привязанного к существу, живые, и снимок «на момент
 * открытия» быстро врал бы.
 */
public class ItrakMenuGui extends GuiScreen {

    private static final int PER_PAGE = 45;

    private List<Tracker> trackers = new ArrayList<>();
    private int page;
    private BukkitTask refreshTask;

    public ItrakMenuGui(Player player) {
        this(player, 0);
    }

    public ItrakMenuGui(Player player, int page) {
        super(player);
        this.page = Math.max(0, page);
    }

    @Override
    public String title() {
        return HahGpsPlugin.getInstance().getMessages().get("gui.itrak-title");
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void open() {
        super.open();
        startRefresh();
    }

    @Override
    public void onClose() {
        stopRefresh();
    }

    private void startRefresh() {
        stopRefresh();
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || GuiManager.getInstance().get(player) != this) {
                stopRefresh();
                return;
            }
            try {
                // Список перечитываем: трекер мог появиться или исчезнуть, пока
                // меню открыто (раньше показывался устаревший снимок).
                reloadTrackers(plugin.getTrackerService());
                buildPage();
            } catch (Throwable t) {
                logFailure(plugin, t);
            }
        }, 20L, 20L);
    }

    /** Не чаще раза в минуту: иначе сбой превращается в строку лога каждую секунду. */
    private long lastFailureLog;
    private int failureStreak;

    private void logFailure(HahGpsPlugin plugin, Throwable error) {
        long now = System.currentTimeMillis();
        failureStreak++;
        if (now - lastFailureLog < 60_000L) {
            return;
        }
        lastFailureLog = now;
        plugin.getLogger().warning("HahGps: меню iTrak не обновилось ("
                + failureStreak + " раз подряд): " + error);
    }

    private void stopRefresh() {
        BukkitTask task = refreshTask;
        refreshTask = null;
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void build() {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        TrackerService service = plugin.getTrackerService();
        if (service == null) {
            fillEmpty();
            return;
        }
        reloadTrackers(service);
        buildPage();
    }

    /** Перечитать список трекеров этого игрока (или всех — для админа). */
    private void reloadTrackers(TrackerService service) {
        if (service == null) {
            return;
        }
        boolean admin = player.hasPermission("gps.itrak.admin");
        trackers = admin ? service.all() : service.byOwner(player.getUniqueId());
    }

    private void buildPage() {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        TrackerService service = plugin.getTrackerService();
        if (service == null || inventory == null) {
            return;
        }
        long now = System.currentTimeMillis();
        int pages = Math.max(1, (trackers.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        inventory.clear();
        // Заполнитель — общий предмет (см. GuiTheme.filler), а не новый стек
        // на каждый пустой слот каждую секунду.
        GuiTheme.fill(inventory);

        int start = page * PER_PAGE;
        for (int i = start; i < Math.min(start + PER_PAGE, trackers.size()); i++) {
            inventory.setItem(i - start, entry(trackers.get(i), service, now));
        }

        if (trackers.isEmpty()) {
            inventory.setItem(22, GuiUtil.item(Material.BARRIER,
                    comp("gui.itrak-none"), lore("gui.lore-itrak-none"), ItemIds.ITRAK_EMPTY));
        }

        inventory.setItem(45, GuiTheme.navBack(getMsg("gui.back")));
        if (page > 0) {
            inventory.setItem(46, GuiTheme.navBack(getMsg("gui.prev-page")));
        }
        if (page < pages - 1) {
            inventory.setItem(52, GuiTheme.navForward(getMsg("gui.next-page")));
        }
        inventory.setItem(48, GuiUtil.item(Material.COMPASS,
                comp("gui.itrak-scan"), lore("gui.lore-itrak-scan"), ItemIds.ITRAK_SCAN));
        inventory.setItem(49, GuiUtil.item(Material.BOOK,
                comp("gui.page-info", "page", String.valueOf(page + 1),
                        "pages", String.valueOf(pages)), null, ItemIds.PAGE_INFO));
        if (player.hasPermission("gps.itrak.give")) {
            // Лазурит-блок, а не лазурит: на предмете-лазурите висит модель
            // синего трекера, и кнопка «выдать» выглядела бы маячком.
            inventory.setItem(50, GuiUtil.item(Material.LAPIS_BLOCK,
                    comp("gui.itrak-catalog"), lore("gui.lore-itrak-catalog"),
                    ItemIds.MAIN_ITRAK_GIVE));
        }
        inventory.setItem(51, GuiUtil.item(Material.SPYGLASS,
                comp("gui.itrak-refresh"), lore("gui.lore-itrak-refresh"), ItemIds.ITRAK_REFRESH));
        inventory.setItem(53, GuiTheme.navClose(getMsg("gui.close")));

        putBrand();
    }

    private ItemStack entry(Tracker tracker, TrackerService service, long now) {
        ItemStack icon = new ItemStack(tracker.getColor().getMaterial());
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            String label = GuiTheme.escapeForMini(GuiTheme.clipPlain(tracker.getDisplayName(), GuiTheme.NAME_MAX));
            meta.displayName(GuiTheme.mm("<bold><color:" + tracker.getColor().hex() + ">" + label
                    + "</color></bold> <dark_gray>·</dark_gray> <#8fc6ff>"
                    + tracker.getCode() + "</#8fc6ff>"));
            List<Component> lines = new ArrayList<>();
            TrackerService.Signal signal = service.signal(player, tracker);
            String carrier = service.carrierName(tracker);
            if (carrier != null) {
                lines.add(comp("gui.lore-itrak-carrier", "name", carrier));
            }
            // Сигнал уже посчитан выше: statusText/stateText по нему, без
            // повторного поиска сущности и записи «последнего места».
            lines.add(comp("gui.lore-itrak-status", "status", service.statusText(signal, tracker)));
            lines.add(comp("gui.lore-itrak-state", "state", service.stateText(signal, tracker, now)));
            if (signal.inRange()) {
                lines.add(comp("gui.lore-itrak-coords",
                        "x", TrackerService.formatCoord(signal.exact().getX()),
                        "y", TrackerService.formatCoord(signal.exact().getY()),
                        "z", TrackerService.formatCoord(signal.exact().getZ())));
            } else {
                lines.add(comp("gui.lore-itrak-region", "region", signal.region()));
                lines.add(comp("gui.lore-itrak-last-known", "last", service.lastKnownText(signal)));
            }
            lines.add(comp("gui.lore-itrak-world",
                    "world", tracker.getWorld() == null ? "?" : tracker.getWorld()));
            lines.add(comp("gui.lore-itrak-distance",
                    "distance", LocationUtil.formatDistance(distance(tracker, signal))));
            meta.lore(lines);
            meta.setCustomModelData(ItemIds.tracker(tracker.getColor(), tracker.isWaterproof()));
            meta.setEnchantmentGlintOverride(false);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private double distance(Tracker tracker, TrackerService.Signal signal) {
        if (signal != null && signal.inRange()) {
            return LocationUtil.distance(player.getLocation(), signal.exact());
        }
        return Double.MAX_VALUE;
    }

    @Override
    public void onClick(int slot, ClickType clickType) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        TrackerService service = plugin.getTrackerService();
        if (service == null) {
            return;
        }
        if (slot == 45) {
            new MainMenuGui(player).open();
            GuiTheme.click(player);
            return;
        }
        if (slot == 48) {
            player.closeInventory();
            int radius = (int) plugin.getConfigManager().getItrakScanRadius();
            if (ItrakCommand.prepareScan(plugin, player, radius)) {
                ItrakCommand.reportScan(plugin, player, radius);
            }
            return;
        }
        if (slot == 50 && player.hasPermission("gps.itrak.give")) {
            new ItrakCatalogGui(player).open();
            GuiTheme.click(player);
            return;
        }
        if (slot == 46 && page > 0) {
            page--;
            buildPage();
            GuiTheme.page(player);
            return;
        }
        if (slot == 52 && page < (Math.max(1, (trackers.size() + PER_PAGE - 1) / PER_PAGE)) - 1) {
            page++;
            buildPage();
            GuiTheme.page(player);
            return;
        }
        if (slot == 51) {
            page = 0;
            buildPage();
            GuiTheme.click(player);
            return;
        }
        if (slot == 53) {
            player.closeInventory();
            return;
        }
        int start = page * PER_PAGE;
        int shown = Math.max(0, Math.min(PER_PAGE, trackers.size() - start));
        if (slot >= 0 && slot < shown) {
            new TrackerDetailGui(player, trackers.get(start + slot).getId(), page).open();
            GuiTheme.click(player);
        }
    }
}
