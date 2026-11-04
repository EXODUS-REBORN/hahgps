package ru.hahgps.listener;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.subcommands.ItrakCommand;
import ru.hahgps.item.ItemFactory;
import ru.hahgps.model.Tracker;
import ru.hahgps.service.TrackerService;

/**
 * Установка iTrak: ПКМ по блоку (любая поверхность, включая воду) или по
 * существу. ПКМ локатором — скан трекеров поблизости.
 *
 * Событие отменяется: трекер — предмет-устройство, а не блок, и открывать
 * чужие меню/двери этим кликом нельзя.
 */
public class TrackerListener implements Listener {

    private final HahGpsPlugin plugin;

    public TrackerListener(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteractBlock(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (ItemFactory.isLocator(item)) {
            event.setCancelled(true);
            runScan(player);
            return;
        }
        ItemFactory.TrackerSpec spec = ItemFactory.readTracker(item);
        if (spec == null) {
            return;
        }
        switch (event.getAction()) {
            case RIGHT_CLICK_BLOCK, RIGHT_CLICK_AIR -> {
            }
            default -> {
                return;
            }
        }
        event.setCancelled(true);
        if (event.getClickedBlock() == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-place-air"));
            return;
        }
        if (!checkAllowed(player)) {
            return;
        }
        place(player, item, spec, event.getClickedBlock(), event.getBlockFace(), null);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        ItemFactory.TrackerSpec spec = ItemFactory.readTracker(item);
        if (spec == null) {
            return;
        }
        event.setCancelled(true);
        Entity target = event.getRightClicked();
        if (target instanceof Player && !plugin.getConfigManager().isItrakAttachPlayers()) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-attach-players-off"));
            return;
        }
        if (!(target instanceof LivingEntity) && !(target instanceof Vehicle)) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-attach-unsupported"));
            return;
        }
        if (!checkAllowed(player)) {
            return;
        }
        place(player, item, spec, target.getLocation().getBlock(), null, target);
    }

    private boolean checkAllowed(Player player) {
        if (!plugin.getConfigManager().isItrakEnabled()) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-disabled"));
            return false;
        }
        TrackerService service = plugin.getTrackerService();
        if (service == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("loading-data"));
            return false;
        }
        if (!player.hasPermission("gps.itrak")) {
            player.sendMessage(plugin.getMessages().getPrefixed("no-permission"));
            return false;
        }
        return true;
    }

    private void place(Player player, ItemStack item, ItemFactory.TrackerSpec spec,
                       org.bukkit.block.Block block, org.bukkit.block.BlockFace face, Entity target) {
        TrackerService service = plugin.getTrackerService();
        if (service == null) {
            return;
        }
        service.place(player, spec, block, face, target).whenComplete((outcome, error) ->
                plugin.getDataService().runSync(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        plugin.getLogger().log(java.util.logging.Level.WARNING,
                                "HahGps: iTrak не установлен", error);
                        player.sendMessage(plugin.getMessages().getPrefixed("itrak-place-failed"));
                        return;
                    }
                    TrackerService.PlaceResult result = outcome == null
                            ? TrackerService.PlaceResult.FAILED : outcome.result();
                    if (result != TrackerService.PlaceResult.OK) {
                        player.sendMessage(plugin.getMessages().getPrefixed(switch (result) {
                            case ALREADY_PLACED -> "itrak-exists";
                            case LIMIT -> "itrak-limit";
                            case DISABLED -> "itrak-disabled";
                            default -> "itrak-place-failed";
                        }, "max", String.valueOf(plugin.getConfigManager().getItrakMaxPerPlayer())));
                        return;
                    }
                    // Предмет забираем по коду, а не по слоту в руке: игрок
                    // мог переключить слот за время записи в БД, и тогда
                    // исчез бы не тот предмет (а трекер «размножился» бы).
                    consumeOne(player, spec.code());
                    Tracker tracker = outcome.tracker();
                    player.sendMessage(plugin.getMessages().getPrefixed("itrak-placed",
                            "name", tracker.getDisplayName(),
                            "code", tracker.getCode(),
                            "world", tracker.getWorld(),
                            "x", TrackerService.formatCoord(tracker.getX()),
                            "y", TrackerService.formatCoord(tracker.getY()),
                            "z", TrackerService.formatCoord(tracker.getZ())));
                    if (tracker.isSunk()) {
                        player.sendMessage(plugin.getMessages().getPrefixed("itrak-placed-sunk",
                                "name", tracker.getDisplayName(), "code", tracker.getCode()));
                    } else if (tracker.isAttached()) {
                        player.sendMessage(plugin.getMessages().getPrefixed("itrak-attached",
                                "name", tracker.getDisplayName(), "entity",
                                target == null ? "?" : target.getName()));
                    }
                }));
    }

    /** Убирает один предмет-трекер с этим кодом из инвентаря игрока. */
    private static void consumeOne(Player player, String code) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getAmount() <= 0) {
                continue;
            }
            ItemFactory.TrackerSpec spec = ItemFactory.readTracker(stack);
            if (spec != null && spec.code().equals(code)) {
                stack.setAmount(stack.getAmount() - 1);
                return;
            }
        }
    }

    private void runScan(Player player) {
        TrackerService service = plugin.getTrackerService();
        if (service == null || !plugin.getConfigManager().isItrakEnabled()) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-disabled"));
            return;
        }
        if (!service.isScanReady(player)) {
            return;
        }
        service.markScan(player);
        ItrakCommand.reportScan(plugin, player);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        TrackerService service = plugin.getTrackerService();
        if (service != null) {
            service.onEntityLost(event.getEntity().getUniqueId());
        }
    }
}
