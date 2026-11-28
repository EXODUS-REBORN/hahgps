package ru.hahgps.vmodel;

import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.config.MessagesManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Редактор постановки: призрак модели под прицелом, поворот шагами, проверка пересечений.
 *
 * <p>Логика перенесена из EXODUS-OS ({@code ModelEditor}): призрак — отдельный
 * {@link ItemDisplay} без метки модели (иначе он попал бы под удары и посадку), цвет —
 * зелёный, если ставить можно, красный — если место занято.</p>
 */
public final class ModelEditor {

    public static final int[] STEPS = {15, 30, 45, 60, 90, 180};

    public static final double REACH = 7.0;

    private final ModelCatalog catalog;
    private final ModelStorage storage;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public ModelEditor(ModelCatalog catalog, ModelStorage storage) {
        this.catalog = catalog;
        this.storage = storage;
    }

    public void forget(UUID playerId) {
        Session session = sessions.remove(playerId);
        if (session != null) {
            session.clearGhost();
        }
    }

    public void clearAll() {
        for (Session session : sessions.values()) {
            session.clearGhost();
        }
        sessions.clear();
    }

    public PlaceTarget currentPlace(Player player) {
        Session session = player == null ? null : sessions.get(player.getUniqueId());
        return session == null ? null : session.target;
    }

    public void tick(Player player, String heldKey) {
        if (player == null || player.getWorld() == null) {
            return;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        PlacedModel looking = findPlaced(player);
        if (looking != null && player.isSneaking()) {
            showGhost(player, session, looking, catalog.get(looking.modelKey()),
                    ModelPackets.GLOW_YELLOW, false);
            return;
        }
        if (heldKey == null || (looking != null && !player.isSneaking())) {
            session.clearGhost();
            return;
        }
        ModelDefinition def = catalog.get(heldKey);
        PlaceTarget target = findPlace(player, def);
        if (def == null || target == null) {
            session.clearGhost();
            return;
        }
        session.target = target;
        Color color = target.blocked() ? ModelPackets.GLOW_RED : ModelPackets.GLOW_GREEN;
        showGhost(player, session, target.location(), def, color, true);
    }

    public void cycleStep(Player player) {
        if (player == null) {
            return;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        session.stepIndex = (session.stepIndex + 1) % STEPS.length;
        send(player, "vmodel-step", "degrees", String.valueOf(session.step()));
    }

    public boolean rotateGhost(Player player) {
        if (player == null) {
            return false;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.target == null || session.ghost == null
                || !session.ghost.isValid()) {
            return false;
        }
        session.extraYaw = wrap(session.extraYaw + session.step());
        ModelDefinition def = catalog.get(session.target.key());
        PlaceTarget next = findPlace(player, def);
        if (next == null) {
            return true;
        }
        session.target = next;
        Color color = next.blocked() ? ModelPackets.GLOW_RED : ModelPackets.GLOW_GREEN;
        ModelPackets.applyGhost(session.ghost, next.location(), def, color);
        send(player, "vmodel-rotated", "degrees", String.valueOf(Math.round(next.location().getYaw())),
                "step", String.valueOf(session.step()));
        return true;
    }

    public PlacedModel findPlaced(Player player) {
        if (player == null || player.getWorld() == null) {
            return null;
        }
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        RayTraceResult block = player.rayTraceBlocks(REACH, FluidCollisionMode.NEVER);
        double best = block == null || block.getHitPosition() == null
                ? REACH
                : block.getHitPosition().distance(eye.toVector());
        PlacedModel nearest = null;
        for (PlacedModel placed : storage.inWorld(player.getWorld())) {
            ModelDefinition def = catalog.get(placed.modelKey());
            if (def == null) {
                continue;
            }
            Location origin = new Location(player.getWorld(), placed.x(), placed.y(), placed.z(),
                    placed.yaw(), placed.pitch());
            Double dist = ModelBoxes.raycast(origin, placed.yaw(), placed.pitch(), def,
                    eye.toVector(), dir, REACH);
            if (dist == null || dist > best) {
                continue;
            }
            best = dist;
            nearest = placed;
        }
        return nearest;
    }

    public PlaceTarget findPlace(Player player, ModelDefinition def) {
        if (player == null || def == null) {
            return null;
        }
        RayTraceResult blockHit = player.rayTraceBlocks(REACH, FluidCollisionMode.NEVER);
        if (blockHit == null || blockHit.getHitBlock() == null || blockHit.getHitBlockFace() == null) {
            return null;
        }
        Block block = blockHit.getHitBlock();
        BlockFace face = blockHit.getHitBlockFace();
        String kind = Placement.kindOf(face);
        if (!def.allows(kind)) {
            return null;
        }
        boolean free = player.isSneaking() && blockHit.getHitPosition() != null;
        Location origin = free
                ? Placement.precise(blockHit.getHitPosition(), face, def, block.getWorld())
                : Placement.origin(block, face, def);
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        float yaw = wrap(Placement.yawFor(player, face, def.rotateToFace()) + session.extraYaw);
        float pitch = kind.equals(Placement.CEILING) ? 180f : 0f;
        origin.setYaw(yaw);
        origin.setPitch(pitch);
        boolean blocked = collides(origin, yaw, def, null);
        return new PlaceTarget(def.key(), origin, kind, blocked);
    }

    public boolean collides(Location origin, float yaw, ModelDefinition def, UUID ignore) {
        if (origin == null || origin.getWorld() == null || def == null) {
            return false;
        }
        float pitch = origin.getPitch();
        for (ModelShape shape : ModelBoxes.shapes(def)) {
            ModelBoxes.OrientedBox obb = ModelBoxes.obbOfShape(origin, yaw, pitch, def, shape).shrunk(0.02);
            BoundingBox hull = obb.aabb();
            int minX = (int) Math.floor(hull.getMinX());
            int minY = (int) Math.floor(hull.getMinY());
            int minZ = (int) Math.floor(hull.getMinZ());
            int maxX = (int) Math.floor(hull.getMaxX());
            int maxY = (int) Math.floor(hull.getMaxY());
            int maxZ = (int) Math.floor(hull.getMaxZ());
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Block block = origin.getWorld().getBlockAt(x, y, z);
                        if (block.isPassable()) {
                            continue;
                        }
                        if (obb.overlaps(ModelBoxes.fromAabb(block.getBoundingBox()))) {
                            return true;
                        }
                    }
                }
            }
        }
        for (PlacedModel placed : storage.inWorld(origin.getWorld())) {
            if (ignore != null && ignore.equals(placed.id())) {
                continue;
            }
            ModelDefinition other = catalog.get(placed.modelKey());
            if (other == null) {
                continue;
            }
            Location theirs = new Location(origin.getWorld(), placed.x(), placed.y(), placed.z(),
                    placed.yaw(), placed.pitch());
            if (ModelBoxes.overlapsModel(origin, yaw, pitch, def, theirs,
                    placed.yaw(), placed.pitch(), other)) {
                return true;
            }
        }
        return false;
    }

    private void showGhost(Player player, Session session, PlacedModel placed, ModelDefinition def,
                           Color color, boolean placing) {
        if (def == null || placed == null) {
            session.clearGhost();
            return;
        }
        Location location = new Location(player.getWorld(), placed.x(), placed.y(), placed.z(),
                placed.yaw(), placed.pitch());
        session.target = placing ? session.target : null;
        showGhost(player, session, location, def, color, placing);
    }

    private void showGhost(Player player, Session session, Location location, ModelDefinition def,
                           Color color, boolean placing) {
        if (!placing) {
            session.target = null;
        }
        if (session.ghost == null || !session.ghost.isValid()) {
            session.ghost = ModelPackets.spawnGhost(player, location, def, color);
            return;
        }
        try {
            ModelPackets.applyGhost(session.ghost, location, def, color);
        } catch (RuntimeException error) {
            warn("обновление призрака", error);
            session.ghost = null;
        }
    }

    public static float wrap(float yaw) {
        float value = yaw % 360f;
        if (value > 180f) {
            value -= 360f;
        }
        if (value < -180f) {
            value += 360f;
        }
        return value;
    }

    public record PlaceTarget(String key, Location location, String face, boolean blocked) {
    }

    private static void send(Player player, String key, String... args) {
        HahGpsPlugin host = HahGpsPlugin.getInstance();
        MessagesManager messages = host == null ? null : host.getMessages();
        if (messages != null) {
            player.sendMessage(messages.getPrefixed(key, args));
        }
    }

    private static void warn(String what, Throwable error) {
        HahGpsPlugin host = HahGpsPlugin.getInstance();
        if (host != null) {
            host.getLogger().log(Level.FINE, "HahGps: модели — " + what, error);
        }
    }

    private static final class Session {

        int stepIndex;
        float extraYaw;
        PlaceTarget target;
        ItemDisplay ghost;

        int step() {
            return STEPS[stepIndex];
        }

        void clearGhost() {
            target = null;
            ModelPackets.remove(ghost);
            ghost = null;
        }
    }
}
