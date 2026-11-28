package ru.hahgps.vmodel;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import ru.hahgps.HahGpsPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Коллизия модели: невидимые шалкеры по контуру, посаженные на маркер-арморстенды.
 *
 * <p>Поведение скопировано из EXODUS-OS ({@code os.exodus.bridge.vmodel.ModelSolids}): сетка с
 * шагом {@link #STEP} блока, не больше {@link #MAX_CELLS} клеток на модель, шалкеры в команде
 * {@code hahvcol} с {@code COLLISION_RULE = FOR_OTHER_TEAMS}, чтобы не расталкивали друг друга.
 * Ключевая ловушка оригинала: шалкер обязан быть пассажиром маркер-стенда, иначе он падает по
 * гравитации и съезжает с модели при загрузке чанка.</p>
 */
public final class ModelSolids implements Listener {

    public static final double STEP = 0.55;

    public static final int MAX_CELLS = 40;

    public static final String TEAM_NAME = "hahvcol";

    private final Map<UUID, List<Cell>> live = new HashMap<>();

    public ModelSolids() {
    }

    public int liveCount() {
        int count = 0;
        for (List<Cell> row : live.values()) {
            count += row.size();
        }
        return count;
    }

    public int placedCount() {
        return live.size();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (ModelPackets.idOf(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (ModelPackets.idOf(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        if (ModelPackets.idOf(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Shulker && ModelPackets.idOf(event.getExited()) != null) {
            event.setCancelled(true);
        }
    }

    public void ensure(PlacedModel placed, ModelDefinition def) {
        if (placed == null) {
            return;
        }
        if (def == null || !def.collision()) {
            drop(placed.id());
            return;
        }
        Location origin = ModelPackets.locationOf(placed);
        if (origin == null || origin.getWorld() == null) {
            return;
        }
        List<Spot> spots = layout(origin, def);
        List<Cell> current = live.get(placed.id());
        if (current != null && current.size() == spots.size() && current.stream().allMatch(Cell::alive)) {
            for (int i = 0; i < spots.size(); i++) {
                sit(current.get(i), spots.get(i));
            }
            return;
        }
        drop(placed.id());
        List<Cell> spawned = new ArrayList<>();
        World world = origin.getWorld();
        for (Spot spot : spots) {
            if (world == null) {
                break;
            }
            try {
                ArmorStand mount = world.spawn(spot.location(), ArmorStand.class,
                        stand -> styleMount(stand, placed.id()));
                Shulker shulker = world.spawn(spot.location(), Shulker.class,
                        entity -> styleShulker(entity, placed.id(), spot.scale()));
                mount.addPassenger(shulker);
                spawned.add(new Cell(shulker, mount));
            } catch (RuntimeException error) {
                warn("клетка коллизии", error);
            }
        }
        if (!spawned.isEmpty()) {
            live.put(placed.id(), spawned);
        }
    }

    public void drop(UUID placedId) {
        List<Cell> row = live.remove(placedId);
        if (row == null) {
            return;
        }
        for (Cell cell : row) {
            cell.remove();
        }
    }

    public void dropAll() {
        for (UUID id : new ArrayList<>(live.keySet())) {
            drop(id);
        }
        live.clear();
        sweep();
    }

    public void sweep() {
        if (!ModelPackets.bound()) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if ((entity instanceof Shulker || entity instanceof ArmorStand)
                        && ModelPackets.idOf(entity) != null) {
                    entity.remove();
                }
            }
        }
    }

    private void styleShulker(Shulker shulker, UUID placedId, double scale) {
        shulker.setPersistent(false);
        shulker.setGravity(false);
        shulker.setSilent(true);
        shulker.setInvisible(true);
        shulker.setInvulnerable(true);
        shulker.setRemoveWhenFarAway(false);
        shulker.setAI(false);
        shulker.setAware(false);
        shulker.setCollidable(true);
        shulker.setPeek(0f);
        shulker.setGlowing(false);
        if (shulker instanceof Mob) {
            ((Mob) shulker).setAware(false);
        }
        try {
            shulker.setLootTable(null);
        } catch (RuntimeException error) {
            warn("таблица дропа шалкера", error);
        }
        AttributeInstance attribute = shulker.getAttribute(Attribute.SCALE);
        if (attribute != null) {
            attribute.setBaseValue(clampScale(scale));
        }
        ModelPackets.mark(shulker, placedId);
        teamOf(shulker.getWorld()).addEntry(shulker.getUniqueId().toString());
    }

    private void styleMount(ArmorStand stand, UUID placedId) {
        stand.setPersistent(false);
        stand.setGravity(false);
        stand.setSilent(true);
        stand.setInvisible(true);
        stand.setInvulnerable(true);
        stand.setMarker(true);
        stand.setSmall(true);
        stand.setBasePlate(false);
        stand.setArms(false);
        stand.setCollidable(false);
        stand.setRemoveWhenFarAway(false);
        try {
            stand.setCanTick(false);
        } catch (RuntimeException error) {
            warn("отключение тика стенда", error);
        }
        ModelPackets.mark(stand, placedId);
    }

    private void sit(Cell cell, Spot spot) {
        Shulker shulker = cell.shulker();
        ArmorStand mount = cell.mount();
        if (mount.getLocation().distanceSquared(spot.location()) > 0.0008) {
            mount.teleport(spot.location());
        }
        if (!mount.getPassengers().contains(shulker)) {
            mount.addPassenger(shulker);
        }
        shulker.setPeek(0f);
        shulker.setVelocity(new Vector());
        AttributeInstance attribute = shulker.getAttribute(Attribute.SCALE);
        if (attribute != null) {
            double want = clampScale(spot.scale());
            if (Math.abs(attribute.getBaseValue() - want) > 0.01) {
                attribute.setBaseValue(want);
            }
        }
    }

    private static double clampScale(double scale) {
        if (!Double.isFinite(scale)) {
            return 1.0;
        }
        return Math.max(0.25, Math.min(1.0, scale));
    }

    private static Team teamOf(World world) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(TEAM_NAME);
        if (team == null) {
            team = board.registerNewTeam(TEAM_NAME);
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.FOR_OTHER_TEAMS);
            team.setCanSeeFriendlyInvisibles(false);
        }
        return team;
    }

    public List<Spot> layout(Location origin, ModelDefinition def) {
        List<Spot> spots = new ArrayList<>();
        if (origin == null || def == null) {
            return spots;
        }
        int left = MAX_CELLS;
        for (ModelShape shape : ModelBoxes.shapes(def)) {
            if (left <= 0) {
                break;
            }
            List<Spot> part = fill(origin, def, shape, left);
            spots.addAll(part);
            left -= part.size();
        }
        return spots;
    }

    private static List<Spot> fill(Location origin, ModelDefinition def, ModelShape shape, int budget) {
        ModelShape box = shape.normalized();
        double sx = Math.max(0.2, box.sizeX());
        double sy = Math.max(0.2, box.sizeY());
        double sz = Math.max(0.2, box.sizeZ());
        int nx = Math.max(1, (int) Math.round(sx / STEP));
        int ny = Math.max(1, (int) Math.round(sy / STEP));
        int nz = Math.max(1, (int) Math.round(sz / STEP));
        while ((long) nx * ny * nz > budget && (nx > 1 || ny > 1 || nz > 1)) {
            if (nx >= ny && nx >= nz && nx > 1) {
                nx--;
            } else if (nz >= ny && nz > 1) {
                nz--;
            } else if (ny > 1) {
                ny--;
            } else {
                break;
            }
        }
        double stepX = sx / nx;
        double stepY = sy / ny;
        double stepZ = sz / nz;
        double scale = clampScale(Math.min(stepX, Math.min(stepY, stepZ)));
        List<Spot> spots = new ArrayList<>();
        for (int y = 0; y < ny; y++) {
            for (int x = 0; x < nx; x++) {
                for (int z = 0; z < nz; z++) {
                    double lx = box.minX() + (x + 0.5) * stepX;
                    double ly = box.minY() + (y + 0.5) * stepY;
                    double lz = box.minZ() + (z + 0.5) * stepZ;
                    Vector local = ModelBoxes.elementPoint(def, shape, lx, ly, lz);
                    Vector world = ModelBoxes.toWorld(origin, origin.getYaw(), origin.getPitch(),
                            local.getX(), local.getY(), local.getZ());
                    Location at = new Location(origin.getWorld(),
                            world.getX(), world.getY() - scale / 2.0, world.getZ());
                    spots.add(new Spot(at, scale));
                }
            }
        }
        return spots;
    }

    public record Spot(Location location, double scale) {
    }

    private record Cell(Shulker shulker, ArmorStand mount) {

        boolean alive() {
            return shulker.isValid() && mount.isValid();
        }

        void remove() {
            if (shulker.isValid()) {
                shulker.remove();
            }
            if (mount.isValid()) {
                mount.remove();
            }
        }
    }

    private static void warn(String what, Throwable error) {
        HahGpsPlugin host = HahGpsPlugin.getInstance();
        if (host != null) {
            host.getLogger().log(Level.FINE, "HahGps: модели — " + what, error);
        }
    }
}
