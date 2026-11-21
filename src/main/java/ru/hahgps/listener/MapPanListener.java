package ru.hahgps.listener;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.gui.GuiTheme;
import ru.hahgps.gui.MapGui;
import ru.hahgps.service.MapService;

/**
 * Управление картой в руке.
 *
 * Карта сама едет за игроком (map.follow-player): смотреть по сторонам можно
 * спокойно, полотно от этого не дёргается. Сдвинуть его вручную — только
 * Shift+мышью, и тогда карта «отвязывается» от игрока. ПКМ по воздуху
 * возвращает центр на игрока, Shift+ПКМ открывает меню карты (зум,
 * следование, центр).
 *
 * Раньше панорама работала на любой поворот головы: достаточно было
 * осмотреться, и карта уезжала — этим пользоваться было нельзя, поэтому
 * теперь нужен явный Shift.
 */
public class MapPanListener implements Listener {

    /** Ниже этого поворота движение мыши не считается панорамой. */
    private static final float DEAD_ZONE_DEGREES = 0.2f;

    /**
     * Максимальный поворот за одно событие. Телепорт или резкая смена угла
     * иначе уносили бы карту на полкарты.
     */
    private static final float MAX_STEP_DEGREES = 20.0f;

    private final HahGpsPlugin plugin;

    public MapPanListener(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLook(PlayerMoveEvent event) {
        MapService maps = plugin.getMapService();
        if (maps == null || !plugin.getConfigManager().isMapMousePan()) {
            return;
        }
        Player player = event.getPlayer();
        // Панорама — только с Shift: обычный взгляд не должен трогать карту.
        if (!player.isSneaking()) {
            return;
        }
        // Сначала дешёвая проверка поворота: событие движения приходит на
        // каждый шаг игрока, а isHoldingMapMainHand копирует предмет и мету —
        // без этой ранней отсечки платили за неё даже при взгляде на месте.
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return;
        }
        float dyaw = clamp(wrapDegrees(to.getYaw() - from.getYaw()));
        float dpitch = clamp(to.getPitch() - from.getPitch());
        if (Math.abs(dyaw) < DEAD_ZONE_DEGREES && Math.abs(dpitch) < DEAD_ZONE_DEGREES) {
            return;
        }
        if (!maps.isHoldingMapMainHand(player)) {
            return;
        }
        // Пока открыт сундук или меню, взгляд не должен уводить карту.
        try {
            if (player.getOpenInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
                return;
            }
        } catch (Throwable ignored) {
        }
        double[] delta = MapService.panDelta(dyaw, dpitch, maps.blocksPerPixel(player),
                plugin.getConfigManager().getMapPanSensitivity());
        maps.pan(player, delta[0], delta[1]);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        MapService maps = plugin.getMapService();
        Player player = event.getPlayer();
        if (maps == null) {
            return;
        }
        boolean holding = maps.isHoldingMapMainHand(player);
        if (!holding && !MapService.isGpsMap(player.getInventory().getItemInMainHand())) {
            return;
        }
        // Только воздух: ПКМ по сундуку картой должен открыть сундук, а не карту.
        if (event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        event.setCancelled(true);
        if (player.isSneaking()) {
            new MapGui(player).open();
            GuiTheme.click(player);
            return;
        }
        maps.openMap(player);
        player.sendMessage(plugin.getMessages().getPrefixed("map-centered"));
        GuiTheme.click(player);
    }

    private static float clamp(float angle) {
        return Math.max(-MAX_STEP_DEGREES, Math.min(MAX_STEP_DEGREES, angle));
    }

    private static float wrapDegrees(float angle) {
        float value = angle % 360.0f;
        if (value >= 180.0f) {
            value -= 360.0f;
        } else if (value < -180.0f) {
            value += 360.0f;
        }
        return value;
    }
}
