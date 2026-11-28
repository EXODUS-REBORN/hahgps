package ru.hahgps.vmodel;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.util.Vector;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.config.MessagesManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Точки «сесть» на модели: ПКМ сажает игрока на ближайшую свободную, Shift — встать.
 *
 * <p>Сиденье — невидимый небольшой арморстенд без маркера: игрок становится его пассажиром.
 * Если игрок ждёт посадки, стенд не снимается; когда он встал и пассажиров нет — стенд
 * удаляется следующим тиком (как в EXODUS-OS {@code ModelSeats}).</p>
 */
public final class ModelSeats implements Listener {

    public static final double REACH = 3.6;

    private static final Map<UUID, List<ArmorStand>> LIVE = new HashMap<>();

    private final HahGpsPlugin plugin;

    ModelSeats(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    public int liveCount() {
        int count = 0;
        for (List<ArmorStand> row : LIVE.values()) {
            count += row.size();
        }
        return count;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExit(VehicleExitEvent event) {
        if (!(event.getExited() instanceof Player) || !(event.getVehicle() instanceof ArmorStand stand)) {
            return;
        }
        if (stand.isMarker() || ModelPackets.idOf(stand) == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!stand.isValid() || !stand.getPassengers().isEmpty()) {
                return;
            }
            UUID placedId = ModelPackets.idOf(stand);
            stand.remove();
            List<ArmorStand> row = LIVE.get(placedId);
            if (row != null) {
                row.removeIf(item -> !item.isValid()
                        || item.getUniqueId().equals(stand.getUniqueId()));
            }
        });
    }

    public boolean sit(Player player, PlacedModel placed, ModelDefinition def) {
        if (player == null || placed == null || def == null || def.sitPoints().isEmpty()) {
            return false;
        }
        if (player.isInsideVehicle()) {
            return true;
        }
        Location origin = ModelPackets.locationOf(placed);
        if (origin == null || origin.getWorld() == null) {
            return false;
        }
        Location chosen = null;
        double best = REACH * REACH;
        for (Location spot : spots(origin, def)) {
            if (taken(spot)) {
                continue;
            }
            double dist = player.getLocation().distanceSquared(spot);
            if (dist < best) {
                best = dist;
                chosen = spot;
            }
        }
        if (chosen == null) {
            send(player, "vmodel-seat-taken");
            return true;
        }
        try {
            ArmorStand seat = chosen.getWorld().spawn(chosen, ArmorStand.class,
                    stand -> style(stand, placed.id()));
            LIVE.computeIfAbsent(placed.id(), ignored -> new ArrayList<>()).add(seat);
            seat.addPassenger(player);
        } catch (RuntimeException error) {
            warn("посадка на модель", error);
            return false;
        }
        send(player, "vmodel-seated");
        return true;
    }

    public void drop(UUID placedId) {
        List<ArmorStand> row = LIVE.remove(placedId);
        if (row == null) {
            return;
        }
        for (ArmorStand stand : row) {
            if (stand.isValid()) {
                stand.remove();
            }
        }
    }

    public void dropAll() {
        for (UUID id : new ArrayList<>(LIVE.keySet())) {
            drop(id);
        }
        LIVE.clear();
    }

    private void style(ArmorStand stand, UUID placedId) {
        stand.setPersistent(false);
        stand.setGravity(false);
        stand.setSilent(true);
        stand.setInvisible(true);
        stand.setInvulnerable(true);
        stand.setSmall(true);
        stand.setBasePlate(false);
        stand.setArms(false);
        stand.setMarker(false);
        stand.setCollidable(false);
        stand.setRemoveWhenFarAway(false);
        try {
            stand.setCanTick(false);
        } catch (RuntimeException error) {
            warn("отключение тика сиденья", error);
        }
        ModelPackets.mark(stand, placedId);
    }

    private static boolean taken(Location spot) {
        if (spot == null || spot.getWorld() == null) {
            return true;
        }
        for (Player player : spot.getWorld().getPlayers()) {
            if (!player.isInsideVehicle()) {
                continue;
            }
            if (player.getLocation().distanceSquared(spot) < 0.16) {
                return true;
            }
        }
        return false;
    }

    public static List<Location> spots(Location origin, ModelDefinition def) {
        List<Location> out = new ArrayList<>();
        if (origin == null || def == null) {
            return out;
        }
        for (double[] point : def.sitPoints()) {
            if (point == null || point.length < 3) {
                continue;
            }
            Vector world = ModelBoxes.toWorld(origin, origin.getYaw(), origin.getPitch(),
                    point[0], point[1], point[2]);
            out.add(new Location(origin.getWorld(), world.getX(), world.getY(), world.getZ(),
                    origin.getYaw(), 0f));
        }
        return out;
    }

    private void send(Player player, String key) {
        MessagesManager messages = plugin == null ? null : plugin.getMessages();
        if (messages != null) {
            player.sendMessage(messages.getPrefixed(key));
        }
    }

    private static void warn(String what, Throwable error) {
        HahGpsPlugin host = HahGpsPlugin.getInstance();
        if (host != null) {
            host.getLogger().log(Level.FINE, "HahGps: модели — " + what, error);
        }
    }
}
