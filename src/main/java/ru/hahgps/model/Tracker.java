package ru.hahgps.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.Locale;
import java.util.UUID;

/**
 * Установленный iTrak.
 *
 * Положение меняется в главном потоке (тик {@code TrackerService}): трекер,
 * прицепленный к существу, повторяет его координаты, трекер на блоке стоит на
 * месте. {@code movedAt} — время последнего смещения больше порога, по нему
 * считается «неподвижен N». Заряд тратится, пока трекер передаёт сигнал, и
 * без заряда трекер молчит (статус «разряжен»).
 */
public class Tracker {

    /** Порог смещения: дрожание сущности на месте не считается движением. */
    public static final double MOVE_THRESHOLD = 0.35;

    /** Полный заряд. */
    public static final int CHARGE_MAX = 100;

    public enum Status {
        LIVE,
        NO_SIGNAL,
        SUNK,
        EMPTY
    }

    /** Блок, к которому прицеплен трекер (null — прицеплен к существу или потерял опору). */
    public record SupportBlock(int x, int y, int z) {
    }

    private final int id;
    private final String code;
    private String name;
    private final TrackerColor color;
    private final boolean waterproof;
    private final UUID ownerUuid;
    private final String ownerName;
    private String world;
    private double x;
    private double y;
    private double z;
    private UUID entityUuid;
    private SupportBlock supportBlock;
    private boolean sunk;
    private final long placedAt;
    private long movedAt;
    private long updatedAt;

    private int charge;
    private long lastChargeAt;

    /** Живой сигнал: у привязанного к сущности трекера снимается, если сущности нет. */
    private transient boolean signal = true;

    public Tracker(int id, String code, String name, TrackerColor color, boolean waterproof,
                   UUID ownerUuid, String ownerName, String world, double x, double y, double z,
                   UUID entityUuid, boolean sunk, long placedAt, long movedAt, long updatedAt) {
        this(id, code, name, color, waterproof, ownerUuid, ownerName, world, x, y, z,
                entityUuid, sunk, placedAt, movedAt, updatedAt,
                CHARGE_MAX, placedAt, null);
    }

    public Tracker(int id, String code, String name, TrackerColor color, boolean waterproof,
                   UUID ownerUuid, String ownerName, String world, double x, double y, double z,
                   UUID entityUuid, boolean sunk, long placedAt, long movedAt, long updatedAt,
                   int charge, long lastChargeAt, SupportBlock supportBlock) {
        this.id = id;
        this.code = code;
        this.name = name == null ? "" : name;
        this.color = color == null ? TrackerColor.BLUE : color;
        this.waterproof = waterproof;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName == null ? "" : ownerName;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.entityUuid = entityUuid;
        this.supportBlock = supportBlock;
        this.sunk = sunk;
        this.placedAt = placedAt;
        this.movedAt = movedAt;
        this.updatedAt = updatedAt;
        this.charge = Math.max(0, Math.min(CHARGE_MAX, charge));
        this.lastChargeAt = lastChargeAt <= 0 ? placedAt : lastChargeAt;
        this.signal = !sunk;
    }

    public int getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    /** Подпись для сообщений: имя, а если его нет — код. */
    public String getDisplayName() {
        return name == null || name.isBlank() ? code : name;
    }

    public TrackerColor getColor() {
        return color;
    }

    public boolean isWaterproof() {
        return waterproof;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public String getOwnerName() {
        return ownerName == null ? "" : ownerName;
    }

    public String getWorld() {
        return world;
    }

    public void setWorld(String world) {
        this.world = world;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public UUID getEntityUuid() {
        return entityUuid;
    }

    public void setEntityUuid(UUID entityUuid) {
        this.entityUuid = entityUuid;
    }

    public boolean isAttached() {
        return entityUuid != null;
    }

    public SupportBlock getSupportBlock() {
        return supportBlock;
    }

    public void setSupportBlock(SupportBlock supportBlock) {
        this.supportBlock = supportBlock;
    }

    public boolean isOnBlock() {
        return supportBlock != null;
    }

    /** Стоит ли этот трекер на данном блоке (для снятия кликом и поломки блока). */
    public boolean isOnBlock(int blockX, int blockY, int blockZ) {
        return supportBlock != null && supportBlock.x() == blockX
                && supportBlock.y() == blockY && supportBlock.z() == blockZ;
    }

    public boolean isSunk() {
        return sunk;
    }

    public void setSunk(boolean sunk) {
        this.sunk = sunk;
        if (sunk) {
            this.signal = false;
        }
    }

    public long getPlacedAt() {
        return placedAt;
    }

    public long getMovedAt() {
        return movedAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public int getCharge() {
        return charge;
    }

    public void setCharge(int charge) {
        this.charge = Math.max(0, Math.min(CHARGE_MAX, charge));
    }

    /** @return сколько процентов реально добавилось (может быть меньше запрошенного) */
    public int addCharge(int delta) {
        int before = charge;
        setCharge(charge + delta);
        return charge - before;
    }

    public long getLastChargeAt() {
        return lastChargeAt;
    }

    public void setLastChargeAt(long lastChargeAt) {
        this.lastChargeAt = lastChargeAt;
    }

    public boolean isPowered() {
        return charge > 0;
    }

    public boolean hasSignal() {
        return signal && !sunk && charge > 0;
    }

    public void setSignal(boolean signal) {
        this.signal = signal && !sunk;
    }

    public Status status() {
        if (sunk) {
            return Status.SUNK;
        }
        if (charge <= 0) {
            return Status.EMPTY;
        }
        return signal ? Status.LIVE : Status.NO_SIGNAL;
    }

    /**
     * Сдвиг без сравнения: применяет координаты и обновляет время обновления.
     * @return true, если смещение больше порога (то есть трекер реально сдвинулся)
     */
    public boolean moveTo(double newX, double newY, double newZ, long now) {
        double dx = newX - x;
        double dy = newY - y;
        double dz = newZ - z;
        double moved2 = dx * dx + dy * dy + dz * dz;
        boolean moved = moved2 > MOVE_THRESHOLD * MOVE_THRESHOLD;
        this.x = newX;
        this.y = newY;
        this.z = newZ;
        this.updatedAt = now;
        if (moved) {
            this.movedAt = now;
        }
        return moved;
    }

    /** Сколько миллисекунд трекер стоит на месте. */
    public long stationaryMillis(long now) {
        return Math.max(0L, now - movedAt);
    }

    /** Неподвижен ли трекер достаточно долго, чтобы предлагать маршрут. */
    public boolean isStationary(long now, long thresholdMillis) {
        if (status() != Status.LIVE) {
            return false;
        }
        return thresholdMillis > 0 && stationaryMillis(now) >= thresholdMillis;
    }

    public Location getLocation() {
        World loaded = world == null ? null : Bukkit.getWorld(world);
        if (loaded == null) {
            return null;
        }
        return new Location(loaded, x, y, z);
    }

    /**
     * Живая точка: сущность может быть в другом месте, чем последние
     * сохранённые координаты, и её нужно спросить прямо сейчас.
     */
    public Location liveLocation() {
        UUID entityId = entityUuid;
        if (entityId != null) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null && entity.isValid() && !entity.isDead()) {
                return entity.getLocation();
            }
            return null;
        }
        return getLocation();
    }

    public String getFormattedCoords() {
        return String.format(Locale.ROOT, "%.1f, %.1f, %.1f", x, y, z);
    }

    @Override
    public String toString() {
        return "Tracker{" + code + " " + color.getKey() + (waterproof ? " waterproof" : "")
                + " " + getFormattedCoords() + " @ " + world + " charge=" + charge + "}";
    }
}
