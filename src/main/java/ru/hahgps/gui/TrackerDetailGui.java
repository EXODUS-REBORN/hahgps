package ru.hahgps.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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
 * Карточка трекера: живые координаты, статус, кнопки маршрута и слежения.
 *
 * Экран перерисовывается раз в секунду — это и есть «смотреть координаты в
 * реальном времени»: у привязанного к существу трекера они меняются.
 */
public class TrackerDetailGui extends GuiScreen {

    private final int trackerId;
    private BukkitTask refreshTask;
    private boolean stationaryHintSent;

    public TrackerDetailGui(Player player, int trackerId) {
        super(player);
        this.trackerId = trackerId;
    }

    @Override
    public String title() {
        Tracker tracker = tracker();
        String name = tracker == null ? "iTrak" : tracker.getDisplayName();
        return HahGpsPlugin.getInstance().getMessages().get("gui.itrak-detail-title", "name", name);
    }

    private Tracker tracker() {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        TrackerService service = plugin == null ? null : plugin.getTrackerService();
        return service == null ? null : service.byId(trackerId);
    }

    @Override
    public int size() {
        return 27;
    }

    @Override
    public void open() {
        super.open();
        startRefresh();
        Tracker tracker = tracker();
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (tracker != null && plugin != null) {
            long now = System.currentTimeMillis();
            if (tracker.isStationary(now, plugin.getConfigManager().getItrakStationaryMillis())
                    && !stationaryHintSent) {
                stationaryHintSent = true;
                player.sendMessage(plugin.getMessages().getPrefixed("itrak-stationary",
                        "name", tracker.getDisplayName(),
                        "time", plugin.getTrackerService().formatDuration(tracker.stationaryMillis(now))));
                player.sendMessage(plugin.getMessages().getPrefixed("itrak-stationary-route",
                        "code", tracker.getCode()));
            }
        }
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
            if (tracker() == null) {
                stopRefresh();
                player.closeInventory();
                player.sendMessage(plugin.getMessages().getPrefixed("itrak-not-found", "name", "iTrak"));
                return;
            }
            try {
                refresh();
            } catch (Throwable t) {
                plugin.getLogger().warning("HahGps: карточка iTrak не обновилась: " + t);
            }
        }, 20L, 20L);
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
        Tracker tracker = tracker();
        if (service == null || tracker == null) {
            fillEmpty();
            return;
        }
        long now = System.currentTimeMillis();

        inventory.setItem(4, detailItem(tracker, service, now));

        inventory.setItem(10, GuiUtil.item(Material.COMPASS,
                comp("gui.itrak-route"),
                List.of(comp(tracker.isStationary(now, plugin.getConfigManager().getItrakStationaryMillis())
                                ? "gui.lore-itrak-route-stationary" : "gui.lore-itrak-route")),
                ItemIds.ITRAK_ROUTE));

        boolean watching = service.isWatching(player, tracker);
        inventory.setItem(12, GuiUtil.item(watching ? Material.SPYGLASS : Material.GLASS,
                comp(watching ? "gui.itrak-unwatch" : "gui.itrak-watch"),
                List.of(comp(watching ? "gui.lore-itrak-watch-off" : "gui.lore-itrak-watch-on")),
                ItemIds.ITRAK_WATCH));

        inventory.setItem(13, GuiUtil.item(Material.FILLED_MAP,
                comp("gui.itrak-map"),
                List.of(comp("gui.lore-itrak-map")),
                ItemIds.MAP_ITEM));

        boolean canTake = service.canManage(player, tracker) && service.canTake(player, tracker);
        inventory.setItem(14, GuiUtil.item(canTake ? Material.CHEST : Material.BARRIER,
                comp(canTake ? "gui.itrak-take" : "gui.itrak-take-far"),
                List.of(comp(canTake ? "gui.lore-itrak-take" : "gui.lore-itrak-take-far")),
                ItemIds.ITRAK_TAKE));

        if (service.canManage(player, tracker)) {
            inventory.setItem(16, GuiUtil.item(Material.LAVA_BUCKET,
                    comp("gui.itrak-delete"),
                    List.of(comp("gui.lore-itrak-delete")),
                    ItemIds.ITRAK_REMOVE));
        }

        inventory.setItem(22, GuiTheme.navBack(getMsg("gui.back")));
        for (int i = 0; i < size(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, GuiUtil.filler());
            }
        }
        putBrand();
    }

    private ItemStack detailItem(Tracker tracker, TrackerService service, long now) {
        ItemStack icon = new ItemStack(tracker.getColor().getMaterial());
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            String label = GuiTheme.escapeForMini(GuiTheme.clipPlain(tracker.getDisplayName(), GuiTheme.NAME_MAX));
            meta.displayName(GuiTheme.mm("<bold><color:" + tracker.getColor().hex() + ">" + label
                    + "</color></bold>"));
            List<Component> lines = new ArrayList<>();
            TrackerService.Signal signal = service.signal(player, tracker);
            lines.add(comp("gui.lore-itrak-code", "code", tracker.getCode()));
            lines.add(comp("gui.lore-itrak-status", "status", service.statusText(player, tracker)));
            lines.add(comp("gui.lore-itrak-state", "state", service.stateText(player, tracker, now)));
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
            double distance = signal.inRange()
                    ? LocationUtil.distance(player.getLocation(), signal.exact())
                    : Double.MAX_VALUE;
            lines.add(comp("gui.lore-itrak-distance", "distance", LocationUtil.formatDistance(distance)));
            if (signal.inRange() && tracker.status() == Tracker.Status.LIVE) {
                lines.add(comp("gui.lore-itrak-time",
                        "time", service.formatDuration(tracker.stationaryMillis(now))));
            }
            lines.add(comp("gui.lore-itrak-live"));
            meta.lore(lines);
            meta.setCustomModelData(ItemIds.ITRAK_DETAIL);
            icon.setItemMeta(meta);
        }
        if (tracker.isWaterproof()) {
            icon = GuiTheme.glow(icon);
        }
        return icon;
    }

    @Override
    public void onClick(int slot, ClickType clickType) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        TrackerService service = plugin.getTrackerService();
        Tracker tracker = tracker();
        if (service == null || tracker == null) {
            return;
        }
        switch (slot) {
            case 4 -> {
                ItrakCommand.sendInfo(plugin, player, tracker);
                GuiTheme.click(player);
            }
            case 10 -> {
                player.closeInventory();
                new TransportSelectGui(player, transport ->
                        ItrakCommand.startRoute(plugin, player, tracker, transport)).open();
                GuiTheme.click(player);
            }
            case 12 -> {
                if (service.isWatching(player, tracker)) {
                    service.unwatch(player, tracker);
                    player.sendMessage(plugin.getMessages().getPrefixed("itrak-watch-off",
                            "name", tracker.getDisplayName()));
                } else {
                    service.watch(player, tracker);
                    player.sendMessage(plugin.getMessages().getPrefixed("itrak-watch-on",
                            "name", tracker.getDisplayName()));
                }
                GuiTheme.click(player);
                refresh();
            }
            case 13 -> {
                player.closeInventory();
                ItrakCommand.focusOnMap(plugin, player, tracker);
            }
            case 14 -> {
                if (!service.canManage(player, tracker) || !service.canTake(player, tracker)) {
                    GuiTheme.deny(player);
                    return;
                }
                String name = tracker.getDisplayName();
                player.closeInventory();
                service.take(player, tracker).whenComplete((ok, error) ->
                        plugin.getDataService().runSync(() -> {
                            if (!player.isOnline()) {
                                return;
                            }
                            if (error != null) {
                                reportError(error);
                                return;
                            }
                            player.sendMessage(plugin.getMessages().getPrefixed("itrak-taken", "name", name));
                        }));
            }
            case 16 -> {
                if (!service.canManage(player, tracker)) {
                    player.sendMessage(plugin.getMessages().getPrefixed("no-permission"));
                    GuiTheme.deny(player);
                    return;
                }
                if (!clickType.isShiftClick()) {
                    player.sendMessage(plugin.getMessages().getPrefixed("itrak-delete-hint"));
                    GuiTheme.deny(player);
                    return;
                }
                String name = tracker.getDisplayName();
                player.closeInventory();
                service.remove(tracker).whenComplete((ok, error) ->
                        plugin.getDataService().runSync(() -> {
                            if (!player.isOnline()) {
                                return;
                            }
                            if (error != null) {
                                reportError(error);
                                return;
                            }
                            player.sendMessage(plugin.getMessages().getPrefixed("itrak-removed", "name", name));
                        }));
            }
            case 22 -> {
                new ItrakMenuGui(player).open();
                GuiTheme.click(player);
            }
            default -> {
            }
        }
    }
}
