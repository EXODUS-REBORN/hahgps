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
 * считается «неподвижен N».
 */
public class Tracker {

    /** Порог смещения: дрожание сущности на месте не считается движением. */
    public static final double MOVE_THRESHOLD = 0.35;

    /** Накопленное смещение, после которого трекер считается сдвинувшимся. */
    private static final double PERSIST_THRESHOLD = 1.0;

    public enum Status {
        LIVE,
        NO_SIGNAL,
        SUNK,
        /** Промок под дождём: сигнала больше нет, но трекер на месте. */
        RUINED
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
    /**
     * Трекер промок под дождём и больше не передаёт сигнал. Координаты
     * замирают на последнем месте: трекер можно найти по последней отметке
     * и снять, но следить за ним и искать локатором уже нельзя.
     */
    private boolean ruined;
    private long ruinedAt;
    private final long placedAt;
    private long movedAt;
    /** Смещение, накопленное после последней отметки «сдвинулся». */
    private transient double pendingMove;
    private long updatedAt;

    /** Живой сигнал: у привязанного к сущности трекера снимается, если сущности нет. */
    private transient boolean signal = true;

    /**
     * Носитель — игрок. Такой трекер не слетает, когда игрок выходит или
     * умирает: он ждёт его на последних координатах со статусом «офлайн» и
     * снова цепляется при входе. {@code null} — тип носителя ещё не определён
     * (только что загрузились из БД).
     */
    private transient Boolean carrierPlayer;

    /**
     * Трекер в воде: держится на поверхности и дрейфует по течению. Состояние
     * не хранится в БД — при загрузке восстанавливается по блоку под маркером.
     * Утонувший трекер статичен, флаг ему не нужен.
     */
    private transient boolean water;

    public Tracker(int id, String code, String name, TrackerColor color, boolean waterproof,
                   UUID ownerUuid, String ownerName, String world, double x, double y, double z,
                   UUID entityUuid, boolean sunk, long placedAt, long movedAt, long updatedAt) {
        this(id, code, name, color, waterproof, ownerUuid, ownerName, world, x, y, z,
                entityUuid, sunk, placedAt, movedAt, updatedAt, null);
    }

    public Tracker(int id, String code, String name, TrackerColor color, boolean waterproof,
                   UUID ownerUuid, String ownerName, String world, double x, double y, double z,
                   UUID entityUuid, boolean sunk, long placedAt, long movedAt, long updatedAt,
                   SupportBlock supportBlock) {
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
        // moved_at появился не во всех старых схемах: у записей до него
        // значение 0. Начинать отсчёт «неподвижен» с 1970 года нельзя —
        // берём последнее известное время (обновление, установку, сейчас).
        this.movedAt = effectiveMovedAt(movedAt, updatedAt, placedAt,
                System.currentTimeMillis());
        this.updatedAt = updatedAt;
        // Обычный трекер под водой глохнет, водонепроницаемый продолжает
        // передавать сигнал со дна — за это его и утапливают намеренно.
        this.signal = !sunk || waterproof;
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

    /** Носитель трекера — игрок (а не моб или транспорт). */
    public boolean isCarrierPlayer() {
        return Boolean.TRUE.equals(carrierPlayer);
    }

    /** Тип носителя уже известен: не нужно лишний раз лезть в OfflinePlayer. */
    public boolean isCarrierTypeKnown() {
        return carrierPlayer != null;
    }

    public void setCarrierPlayer(boolean carrierPlayer) {
        this.carrierPlayer = carrierPlayer;
    }

    /**
     * Трекер на игроке, который сейчас не в сети: сигнала нет, но запись
     * остаётся привязанной к UUID игрока и ждёт его возвращения.
     */
    public boolean isCarrierOffline() {
        if (entityUuid == null || !isCarrierPlayer() || hasSignal()) {
            return false;
        }
        try {
            return Bukkit.getPlayer(entityUuid) == null;
        } catch (Throwable ignored) {
            return true;
        }
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
        if (sunk && !waterproof) {
            this.signal = false;
        }
    }

    /** Плавающий трекер: держится на поверхности и дрейфует по течению. */
    public boolean isWater() {
        return water && !sunk;
    }

    public void setWater(boolean water) {
        this.water = water && !sunk;
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

    public boolean hasSignal() {
        return !ruined && signal && (!sunk || waterproof);
    }

    public boolean isRuined() {
        return ruined;
    }

    /** Когда трекер промок: от этого времени считается «последний раз в сети». */
    public long getRuinedAt() {
        return ruinedAt;
    }

    public void setRuined(boolean ruined, long ruinedAt) {
        this.ruined = ruined;
        this.ruinedAt = ruinedAt;
        if (ruined) {
            this.signal = false;
        }
    }

    /** Промокнуть: сигнал исчезает, координаты остаются на месте. */
    public void ruin(long now) {
        setRuined(true, now);
    }

    public void setSignal(boolean signal) {
        this.signal = signal && (!sunk || waterproof);
    }

    public Status status() {
        if (ruined) {
            return Status.RUINED;
        }
        if (sunk && !waterproof) {
            return Status.SUNK;
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
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        this.x = newX;
        this.y = newY;
        this.z = newZ;
        this.updatedAt = now;
        if (distance <= 0.0) {
            return false;
        }
        // Копим смещение с прошлой отметки: медленный носитель (лодка, вагонетка)
        // за один тик сдвигается меньше порога, и раньше его координаты не
        // сохранялись вовсе, а трекер считался неподвижным.
        pendingMove += distance;
        if (distance > MOVE_THRESHOLD || pendingMove > PERSIST_THRESHOLD) {
            this.movedAt = now;
            this.pendingMove = 0.0;
            return true;
        }
        return false;
    }

    /**
     * Время последнего движения с исправлением старых записей.
     *
     * Валидный {@code movedAt} не трогаем: именно он даёт «неподвижен N»,
     * включая время, пока сервер был выключен. Ноль — это отсутствие колонки
     * в старой БД, а не «двигался в 1970-м».
     */
    public static long effectiveMovedAt(long movedAt, long updatedAt, long placedAt, long now) {
        if (movedAt > 0L) {
            return movedAt;
        }
        if (updatedAt > 0L) {
            return updatedAt;
        }
        if (placedAt > 0L) {
            return placedAt;
        }
        return now;
    }

    /** Сколько миллисекунд трекер стоит на месте (включая простой сервера). */
    public long stationaryMillis(long now) {
        return Math.max(0L, now - movedAt);
    }

    /**
     * Отметить движение, которое меньше {@link #MOVE_THRESHOLD} в одном шаге:
     * течение двигает трекер мелкими порциями, но он всё равно плывёт, а не
     * стоит. Без этого при малом интервале обновления трекер «неподвижен»
     * с первого тика и владельцу приходит ложное «неподвижен N».
     */
    public void markMoved(long now) {
        this.movedAt = now;
        this.updatedAt = now;
        this.pendingMove = 0.0;
    }

    /** Неподвижен ли трекер достаточно долго, чтобы предлагать маршрут. */
    public boolean isStationary(long now, long thresholdMillis) {
        // Утонувший водонепроницаемый трекер сигнал не теряет — маршрут к нему
        // так же полезен, как к стоящему на земле.
        if (!hasSignal()) {
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
     * сохранённые координаты, и её нужно спросить прямо сейчас. Если сущности
     * уже нет (игрок вышел, моб исчез), возвращаем последние координаты —
     * локатор и карта должны показывать, где трекер видели в последний раз.
     */
    public Location liveLocation() {
        // Промокший трекер никуда не «едет» вслед за носителем: показываем
        // последнее место, где он передавал сигнал.
        if (ruined) {
            return getLocation();
        }
        UUID entityId = entityUuid;
        if (entityId != null) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null && entity.isValid() && !entity.isDead()) {
                return entity.getLocation();
            }
        }
        return getLocation();
    }

    public String getFormattedCoords() {
        return String.format(Locale.ROOT, "%.1f, %.1f, %.1f", x, y, z);
    }

    @Override
    public String toString() {
        return "Tracker{" + code + " " + color.getKey() + (waterproof ? " waterproof" : "")
                + " " + getFormattedCoords() + " @ " + world + "}";
    }
}
