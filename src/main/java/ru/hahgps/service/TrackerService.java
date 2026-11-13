package ru.hahgps.service;

import io.papermc.paper.block.fluid.FluidData;
import io.papermc.paper.block.fluid.type.FallingFluidData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.config.ConfigManager;
import ru.hahgps.config.MessagesManager;
import ru.hahgps.item.ItemFactory;
import ru.hahgps.item.TrackerCodes;
import ru.hahgps.model.Tracker;
import ru.hahgps.model.TrackerColor;
import ru.hahgps.storage.DataService;
import ru.hahgps.util.EntityNames;
import ru.hahgps.util.LocationUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * iTrak: установленные трекеры, их живые координаты и оповещения.
 *
 * Вся изменяемая работа идёт в главном потоке (тик планировщика): координаты
 * привязанного трекера спрашиваются у сущности, движение фиксируется по порогу,
 * а в БД координаты уходят редко и пачкой — иначе трекер писал бы строку
 * каждую секунду. Постоянное состояние (кто смотрит, кому уже сообщили о
 * неподвижности) живёт только в памяти и переживает перезапуск в виде
 * координат и времени последнего движения в таблице {@code trackers}.
 */
public class TrackerService {

    public enum PlaceResult {
        OK,
        ALREADY_PLACED,
        LIMIT,
        DISABLED,
        FAILED
    }

    public record PlaceOutcome(Tracker tracker, PlaceResult result) {
    }

    public record Nearby(Tracker tracker, double distance) {
    }

    /** Последняя точка, которую игрок получил, пока трекер был в зоне связи. */
    private record LastKnown(String world, double x, double y, double z, long at) {
        Location toLocation() {
            World loaded = world == null ? null : Bukkit.getWorld(world);
            return loaded == null ? null : new Location(loaded, x, y, z);
        }
    }

    /**
     * Что игрок видит про трекер: {@code exact} — точное место (в зоне связи),
     * иначе только район и последняя известная отметка.
     */
    public record Signal(Location exact, Location lastKnown, long lastKnownAt, String region) {
        public boolean inRange() {
            return exact != null;
        }
    }

    /** Результат удачного скана: сколько попыток осталось и началась ли перезарядка. */
    public record ScanConsumption(int left, int max, boolean cooldownStarted) {
    }

    private final HahGpsPlugin plugin;
    private final DataService data;

    private final Map<Integer, Tracker> trackers = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Integer>> watchedByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastWatchMessage = new ConcurrentHashMap<>();
    /**
     * Конец перезарядки локатора по игроку. Живёт и после выхода из игры:
     * иначе перезаход сбрасывал бы пятиминутный КД, и «белая полоса» была бы
     * только декорацией.
     */
    private final Map<UUID, Long> scanCooldownEnd = new ConcurrentHashMap<>();
    /** Сколько удачных сканирований у игрока осталось до перезарядки. */
    private final Map<UUID, Integer> scanAttempts = new ConcurrentHashMap<>();
    /**
     * Последнее точное место трекера, которое видел конкретный игрок, пока
     * был в зоне связи. Дальше этого расстояния точные координаты не
     * показываются — только район и эта отметка.
     */
    private final Map<UUID, Map<Integer, LastKnown>> lastKnown = new ConcurrentHashMap<>();
    private final Set<Integer> stationaryNotified = ConcurrentHashMap.newKeySet();
    private final Set<Integer> dirty = ConcurrentHashMap.newKeySet();
    /** Коды, которые прямо сейчас вставляются в БД: двойной клик не создаст два трекера. */
    private final Set<String> placingCodes = ConcurrentHashMap.newKeySet();
    /** С какого момента привязанная сущность не находится (для снятия «висящих» трекеров). */
    private final Map<Integer, Long> lostSince = new ConcurrentHashMap<>();
    /** Подсветка трекеров конкретному игроку: терминал показал координаты. */
    private final Map<UUID, List<Highlight>> highlights = new ConcurrentHashMap<>();

    private record Highlight(int trackerId, long until) {
    }

    private volatile BukkitTask task;
    private volatile long lastSaveAt;

    public TrackerService(HahGpsPlugin plugin, DataService data) {
        this.plugin = plugin;
        this.data = data;
    }

    // ------------------------------------------------------------------
    // Жизненный цикл
    // ------------------------------------------------------------------

    /** Загрузка из БД (главный поток). */
    public void load(List<Tracker> loaded) {
        trackers.clear();
        lostSince.clear();
        placingCodes.clear();
        lastKnown.clear();
        Set<String> seenCodes = new HashSet<>();
        if (loaded != null) {
            for (Tracker tracker : loaded) {
                if (tracker == null || tracker.getCode() == null) {
                    continue;
                }
                // Код — идентификатор одного установленного трекера. Если в
                // базе оказались дубли (старый генератор кодов), оставляем
                // первый: второй — призрак, из-за которого локатор показывал
                // трекер там, где его уже нет.
                if (!seenCodes.add(tracker.getCode())) {
                    plugin.getLogger().warning("HahGps: в базе два трекера с кодом "
                            + tracker.getCode() + " — оставляю первый, лишний удалён");
                    data.deleteTracker(tracker.getId());
                    continue;
                }
                TrackerCodes.reserve(tracker.getCode());
                // Привязанный к сущности трекер до первого тика сигнала не имеет:
                // сущность может быть в незагруженном чанке.
                if (tracker.isAttached()) {
                    tracker.setSignal(false);
                    // Игрок мог выйти до рестарта: тип носителя определяется
                    // один раз, чтобы такой трекер не считался потерянным.
                    tracker.setCarrierPlayer(isKnownPlayer(tracker.getEntityUuid()));
                    // Трекеры, поставленные до 1.3.6, назывались «Игрок»:
                    // подставляем ник носителя, пока он не в сети.
                    repairCarrierLabel(tracker);
                } else {
                    // Флаг «плывёт» в БД не хранится: восстанавливаем его по
                    // блоку под маркером — иначе после рестарта трекер на воде
                    // висел бы неподвижно.
                    tracker.setWater(isWaterUnder(tracker));
                }
                trackers.put(tracker.getId(), tracker);
            }
        }
        plugin.getLogger().info("HahGps: загружено трекеров iTrak — " + trackers.size());
    }

    /** Стоит ли трекер на воде: проверка под маркером, чанк не грузим. */
    private static boolean isWaterUnder(Tracker tracker) {
        World world = tracker.getWorld() == null ? null : Bukkit.getWorld(tracker.getWorld());
        if (world == null) {
            return false;
        }
        int bx = floor(tracker.getX());
        int bz = floor(tracker.getZ());
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
            return false;
        }
        return isWaterBody(world.getBlockAt(bx, floor(tracker.getY() - 0.02), bz));
    }

    /**
     * UUID принадлежит игроку. Для мобов и транспорта всегда false: их UUID
     * нет в списке игравших. Нужно, чтобы отличить «игрок вышел» от «моба
     * больше нет» — первое не повод снимать трекер.
     */
    private static boolean isKnownPlayer(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        try {
            if (Bukkit.getPlayer(uuid) != null) {
                return true;
            }
            return Bukkit.getOfflinePlayer(uuid).hasPlayedBefore();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public synchronized void start() {
        stopTask();
        int interval = Math.max(5, plugin.getConfigManager().getItrakUpdateInterval());
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                tick();
            } catch (Throwable t) {
                // Не пойманное исключение навсегда убивает задачу планировщика.
                plugin.getLogger().log(Level.WARNING, "HahGps: тик iTrak упал", t);
            }
        }, interval, interval);
        lastSaveAt = System.currentTimeMillis();
    }

    public synchronized void stop() {
        stopTask();
        // Досохраняем всё, что успело сдвинуться: иначе после рестарта трекер
        // окажется там, где был при прошлом сохранении.
        for (Tracker tracker : trackers.values()) {
            try {
                data.updateTracker(tracker);
            } catch (Throwable ignored) {
            }
        }
        watchedByPlayer.clear();
        lastWatchMessage.clear();
        scanCooldownEnd.clear();
        scanAttempts.clear();
        lastKnown.clear();
        stationaryNotified.clear();
        dirty.clear();
        lostSince.clear();
        placingCodes.clear();
    }

    private void stopTask() {
        BukkitTask current = task;
        task = null;
        if (current != null) {
            try {
                current.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Тик: координаты, неподвижность, частицы, подписки, сохранение
    // ------------------------------------------------------------------

    private void tick() {
        if (!plugin.getConfigManager().isItrakEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long stationaryMillis = plugin.getConfigManager().getItrakStationaryMillis();

        for (Tracker tracker : trackers.values()) {
            updateFromEntity(tracker, now);
            updateWater(tracker, now);
            if (stationaryMillis > 0 && tracker.isStationary(now, stationaryMillis)) {
                if (stationaryNotified.add(tracker.getId())
                        && plugin.getConfigManager().isItrakStationaryNotify()) {
                    notifyStationary(tracker, now);
                }
            } else {
                stationaryNotified.remove(tracker.getId());
            }
        }

        if (plugin.getConfigManager().isItrakParticles()) {
            spawnParticles();
        }
        spawnHighlights(now);
        refreshSignalSnapshots();
        pushWatchMessages(now);
        saveDirty(now);
    }

    /** Кому сообщать о событиях трекера: владелец и все следящие. */
    private Set<UUID> recipientsOf(Tracker tracker) {
        Set<UUID> recipients = new LinkedHashSet<>();
        if (tracker.getOwnerUuid() != null) {
            recipients.add(tracker.getOwnerUuid());
        }
        for (Map.Entry<UUID, Set<Integer>> entry : watchedByPlayer.entrySet()) {
            if (entry.getValue().contains(tracker.getId())) {
                recipients.add(entry.getKey());
            }
        }
        return recipients;
    }

    /** Подсветка по нажатию на терминал: цветные частицы вокруг трекеров. */
    private void spawnHighlights(long now) {
        if (highlights.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, List<Highlight>>> iterator = highlights.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, List<Highlight>> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            List<Highlight> active = entry.getValue();
            active.removeIf(highlight -> now > highlight.until() || trackers.get(highlight.trackerId()) == null);
            if (player == null || !player.isOnline() || active.isEmpty()) {
                iterator.remove();
                continue;
            }
            for (Highlight highlight : active) {
                Tracker tracker = trackers.get(highlight.trackerId());
                Location location = tracker.getLocation();
                if (location == null || location.getWorld() == null
                        || !LocationUtil.sameWorld(player.getWorld(), location.getWorld())) {
                    continue;
                }
                Particle.DustOptions dust = new Particle.DustOptions(tracker.getColor().bukkitColor(), 1.1f);
                player.spawnParticle(Particle.DUST, location.clone().add(0, 0.7, 0),
                        24, 0.9, 0.9, 0.9, 0, dust);
                player.spawnParticle(Particle.END_ROD, location.clone().add(0, 1.1, 0),
                        4, 0.25, 0.35, 0.25, 0.01);
            }
        }
    }

    /** Показать игроку трекер на несколько секунд (после терминала). */
    public void highlight(Player player, Tracker tracker, int seconds) {
        if (player == null || tracker == null) {
            return;
        }
        long until = System.currentTimeMillis() + Math.max(1, seconds) * 1000L;
        List<Highlight> list = highlights.computeIfAbsent(player.getUniqueId(),
                key -> java.util.Collections.synchronizedList(new ArrayList<>()));
        list.removeIf(existing -> existing.trackerId() == tracker.getId());
        list.add(new Highlight(tracker.getId(), until));
    }

    /** Убрать подсветку трекера у всех игроков (трекер снят). */
    private void clearHighlights(int trackerId) {
        highlights.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(highlight -> highlight.trackerId() == trackerId);
            return entry.getValue().isEmpty();
        });
    }

    private void updateFromEntity(Tracker tracker, long now) {
        if (!tracker.isAttached() || tracker.isSunk()) {
            return;
        }
        Entity entity = Bukkit.getEntity(tracker.getEntityUuid());
        if (entity == null || !entity.isValid() || entity.isDead()) {
            // Сущность в выгруженном чанке тоже не находится: это не потеря
            // привязки. Иначе лошадь в загоне без игроков «теряла» трекер через
            // detach-after-minutes, и он выпадал предметом.
            if (entity == null && !isChunkLoadedAt(tracker)) {
                return;
            }
            if (tracker.hasSignal()) {
                tracker.setSignal(false);
                dirty.add(tracker.getId());
            }
            // Игрок вышел, умер или ещё не загрузился после входа: трекер
            // остаётся на нём, пока владелец не снимет его. Таймаут
            // detach-after-minutes — только для мобов и транспорта.
            if (isPlayerCarrier(tracker)) {
                lostSince.remove(tracker.getId());
                return;
            }
            long lostMillis = plugin.getConfigManager().getItrakDetachAfterMillis();
            long since = lostSince.computeIfAbsent(tracker.getId(), id -> now);
            if (lostMillis > 0 && now - since >= lostMillis) {
                detachLost(tracker);
            }
            return;
        }
        lostSince.remove(tracker.getId());
        World world = entity.getWorld();
        if (world == null) {
            return;
        }
        tracker.setCarrierPlayer(entity instanceof Player);
        tracker.setSignal(true);
        // Носитель в сети — самый надёжный источник ника: заодно чиним
        // подпись «Игрок» у трекеров, поставленных до 1.3.6.
        repairCarrierLabel(tracker);
        if (!world.getName().equals(tracker.getWorld())) {
            tracker.setWorld(world.getName());
            dirty.add(tracker.getId());
        }
        Location location = entity.getLocation();
        if (tracker.moveTo(location.getX(), location.getY(), location.getZ(), now)) {
            stationaryNotified.remove(tracker.getId());
            dirty.add(tracker.getId());
        } else {
            dirty.add(tracker.getId());
        }
    }

    /** Загружен ли чанк, где трекер видели в последний раз. */
    private static boolean isChunkLoadedAt(Tracker tracker) {
        World world = tracker.getWorld() == null ? null : Bukkit.getWorld(tracker.getWorld());
        if (world == null) {
            return false;
        }
        return world.isChunkLoaded(floor(tracker.getX()) >> 4, floor(tracker.getZ()) >> 4);
    }

    /** Носитель трекера — игрок. Тип определяется один раз и кэшируется в трекере. */
    private boolean isPlayerCarrier(Tracker tracker) {        if (!tracker.isAttached()) {
            return false;
        }
        if (!tracker.isCarrierTypeKnown()) {
            tracker.setCarrierPlayer(isKnownPlayer(tracker.getEntityUuid()));
        }
        return tracker.isCarrierPlayer();
    }

    /**
     * Трекер в воде: держится на поверхности, плывёт по течению и падает
     * вместе с водопадом. Утонувший трекер лежит на дне и не двигается —
     * его утопили намеренно.
     *
     * Чанк, в котором стоит трекер, не грузим: если он выгружен, трекер
     * просто ждёт на последних координатах.
     */
    private void updateWater(Tracker tracker, long now) {
        if (tracker.isAttached() || tracker.isSunk() || !tracker.isWater()
                || !plugin.getConfigManager().isItrakWaterCurrent()) {
            return;
        }
        World world = Bukkit.getWorld(tracker.getWorld());
        if (world == null) {
            return;
        }
        int bx = floor(tracker.getX());
        int bz = floor(tracker.getZ());
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
            return;
        }
        double dt = plugin.getConfigManager().getItrakUpdateInterval() / 20.0;
        WaterPhysics.Pose pose = WaterPhysics.floatStep(tracker.getX(), tracker.getY(), tracker.getZ(),
                new WorldTerrain(world), dt,
                plugin.getConfigManager().getItrakWaterDriftSpeed(),
                plugin.getConfigManager().getItrakWaterFallSpeed());
        double dx = pose.x() - tracker.getX();
        double dy = pose.y() - tracker.getY();
        double dz = pose.z() - tracker.getZ();
        if (dx * dx + dy * dy + dz * dz < 1e-8) {
            return;
        }
        tracker.moveTo(pose.x(), pose.y(), pose.z(), now);
        // Течение двигает мелкими шагами: порог moveTo их не заметит, а
        // «неподвижен N» пришло бы на первом же тике.
        tracker.markMoved(now);
        stationaryNotified.remove(tracker.getId());
        dirty.add(tracker.getId());
        if (pose.ground() != null) {
            // Приплыли на сушу: дальше это обычный трекер на блоке.
            tracker.setWater(false);
            tracker.setSupportBlock(new Tracker.SupportBlock(
                    pose.ground().x(), pose.ground().y(), pose.ground().z()));
        } else if (pose.floating()) {
            followBed(world, tracker, pose);
        }
    }

    /** Опорный блок плавающего трекера — дно под водой: по нему его и снимают. */
    private void followBed(World world, Tracker tracker, WaterPhysics.Pose pose) {
        int wx = floor(pose.x());
        int wz = floor(pose.z());
        int wy = floor(pose.y() - 0.02);
        Block water = world.getBlockAt(wx, wy, wz);
        if (!isWaterBody(water)) {
            return;
        }
        Tracker.SupportBlock current = tracker.getSupportBlock();
        if (current != null && current.x() == wx && current.z() == wz) {
            return;
        }
        tracker.setSupportBlock(new Tracker.SupportBlock(wx, bottomWaterY(water) - 1, wz));
        dirty.add(tracker.getId());
    }

    /**
     * Маркер трекера в мире. По умолчанию его видит только тот, кто трекер
     * поставил: чужой игрок не должен замечать слежку, но локатор её находит
     * и подсвечивает уже конкретному нашедшему (см. {@link #highlight}).
     */
    private void spawnParticles() {
        double radius = plugin.getConfigManager().getItrakParticleRadius();
        double radiusSquared = radius * radius;
        boolean ownerOnly = plugin.getConfigManager().isItrakParticlesOwnerOnly();
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            return;
        }
        for (Tracker tracker : trackers.values()) {
            Location location = tracker.getLocation();
            if (location == null) {
                continue;
            }
            World world = location.getWorld();
            if (world == null || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                continue;
            }
            UUID owner = tracker.getOwnerUuid();
            float size = tracker.hasSignal() ? 0.9f : 0.45f;
            Particle.DustOptions dust = new Particle.DustOptions(tracker.getColor().bukkitColor(), size);
            for (Player player : players) {
                if (ownerOnly && (owner == null || !owner.equals(player.getUniqueId()))) {
                    continue;
                }
                if (!LocationUtil.sameWorld(player.getWorld(), world)) {
                    continue;
                }
                Location eye = player.getLocation();
                double dx = eye.getX() - location.getX();
                double dy = eye.getY() - location.getY();
                double dz = eye.getZ() - location.getZ();
                if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                    continue;
                }
                player.spawnParticle(Particle.DUST,
                        location.clone().add(0, 0.65, 0), 3,
                        0.18, 0.18, 0.18, 0, dust);
            }
        }
    }

    /**
     * Привязка к сущности потеряна надолго (моб ушёл в незагруженный чанк или
     * исчез без события смерти). Трекер перестаёт быть «на сущности» и
     * остаётся на последних координатах — находка для локатора, а не вечный
     * призрак в списке.
     */
    private void detachLost(Tracker tracker) {
        lostSince.remove(tracker.getId());
        tracker.setEntityUuid(null);
        tracker.setSignal(false);
        dirty.add(tracker.getId());
        Player owner = tracker.getOwnerUuid() == null ? null : Bukkit.getPlayer(tracker.getOwnerUuid());
        if (owner != null && owner.isOnline()) {
            owner.sendMessage(plugin.getMessages().getPrefixed("itrak-detached",
                    "name", tracker.getDisplayName(),
                    "world", tracker.getWorld() == null ? "?" : tracker.getWorld(),
                    "x", formatCoord(tracker.getX()),
                    "y", formatCoord(tracker.getY()),
                    "z", formatCoord(tracker.getZ())));
        }
    }

    private void pushWatchMessages(long now) {
        if (watchedByPlayer.isEmpty()) {
            return;
        }
        long interval = plugin.getConfigManager().getItrakWatchIntervalSeconds() * 1000L;
        MessagesManager messages = plugin.getMessages();
        for (Map.Entry<UUID, Set<Integer>> entry : watchedByPlayer.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }
            long last = lastWatchMessage.getOrDefault(entry.getKey(), 0L);
            if (now - last < interval) {
                continue;
            }
            lastWatchMessage.put(entry.getKey(), now);
            for (Integer id : new ArrayList<>(entry.getValue())) {
                Tracker tracker = trackers.get(id);
                if (tracker == null) {
                    entry.getValue().remove(id);
                    continue;
                }
                Signal signal = signal(player, tracker);
                if (signal.inRange()) {
                    Location location = signal.exact();
                    double distance = LocationUtil.distance(player.getLocation(), location);
                    player.sendMessage(messages.getPrefixed("itrak-watch-update",
                            "name", tracker.getDisplayName(),
                            "code", tracker.getCode(),
                            "x", formatCoord(location.getX()),
                            "y", formatCoord(location.getY()),
                            "z", formatCoord(location.getZ()),
                            "world", tracker.getWorld() == null ? "?" : tracker.getWorld(),
                            "distance", LocationUtil.formatDistance(distance),
                            "status", statusText(player, tracker),
                            "time", formatDuration(tracker.stationaryMillis(now))));
                } else {
                    // Вне зоны связи: только район и последняя отметка — та,
                    // что успела дойти до этого игрока.
                    player.sendMessage(messages.getPrefixed("itrak-watch-far",
                            "name", tracker.getDisplayName(),
                            "code", tracker.getCode(),
                            "world", tracker.getWorld() == null ? "?" : tracker.getWorld(),
                            "status", statusText(player, tracker),
                            "region", signal.region(),
                            "last", lastKnownText(signal)));
                }
            }
        }
    }

    private void saveDirty(long now) {
        if (dirty.isEmpty()) {
            return;
        }
        long interval = plugin.getConfigManager().getItrakSaveIntervalSeconds() * 1000L;
        if (now - lastSaveAt < interval) {
            return;
        }
        lastSaveAt = now;
        for (Integer id : new ArrayList<>(dirty)) {
            Tracker tracker = trackers.get(id);
            dirty.remove(id);
            if (tracker == null) {
                continue;
            }
            data.updateTracker(tracker).exceptionally(error -> {
                plugin.getLogger().log(Level.WARNING, "HahGps: не удалось сохранить трекер "
                        + tracker.getCode(), error);
                return false;
            });
        }
    }

    private void notifyStationary(Tracker tracker, long now) {
        MessagesManager messages = plugin.getMessages();
        for (UUID id : recipientsOf(tracker)) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            player.sendMessage(withCarrierHover(messages.getPrefixed("itrak-stationary",
                    "name", tracker.getDisplayName(),
                    "time", formatDuration(tracker.stationaryMillis(now))), tracker));
            player.sendMessage(messages.getComponent("itrak-route-button",
                    "code", tracker.getCode()));
        }
        dirty.add(tracker.getId());
    }

    // ------------------------------------------------------------------
    // Установка, снятие, удаление
    // ------------------------------------------------------------------

    /**
     * Установка трекера. Блоки читаются в главном потоке, вставка в БД —
     * асинхронно; результат возвращается future'ом.
     *
     * @param sinkRequested утопить водонепроницаемый трекер намеренно
     *                      (Shift-клик по дну или Shift-бросок): ляжет на дно
     *                      и продолжит передавать сигнал. Обычный трекер тонет
     *                      в воде всегда — за это он и дешевле.
     */
    public CompletableFuture<PlaceOutcome> place(Player player, ItemFactory.TrackerSpec spec,
                                                 Block clicked, BlockFace face, Entity target,
                                                 boolean sinkRequested) {
        if (spec == null) {
            return CompletableFuture.completedFuture(new PlaceOutcome(null, PlaceResult.FAILED));
        }
        if (!plugin.getConfigManager().isItrakEnabled()) {
            return CompletableFuture.completedFuture(new PlaceOutcome(null, PlaceResult.DISABLED));
        }
        if (byCode(spec.code()) != null || !placingCodes.add(spec.code())) {
            return CompletableFuture.completedFuture(new PlaceOutcome(null, PlaceResult.ALREADY_PLACED));
        }
        boolean admin = player.hasPermission("gps.itrak.admin");
        if (!admin && countOwned(player.getUniqueId()) >= plugin.getConfigManager().getItrakMaxPerPlayer()) {
            placingCodes.remove(spec.code());
            return CompletableFuture.completedFuture(new PlaceOutcome(null, PlaceResult.LIMIT));
        }

        Location location;
        Tracker.SupportBlock support = null;
        boolean sunk = false;
        boolean floatingWater = false;
        if (target != null) {
            location = target.getLocation().clone()
                    .add(0, Math.max(0.4, target.getHeight() * 0.5), 0);
        } else {
            Block placed = clicked.getRelative(face == null ? BlockFace.UP : face);
            Block water = isWaterBody(clicked) ? clicked
                    : (isWaterBody(placed) ? placed : null);
            if (water != null) {
                int wx = water.getX();
                int wz = water.getZ();
                int bottom = bottomWaterY(water);
                // Дно под водой — опорный блок: по нему трекер снимают кликом.
                support = new Tracker.SupportBlock(wx, bottom - 1, wz);
                if (sinkRequested || !spec.waterproof()) {
                    location = new Location(water.getWorld(), wx + 0.5, bottom + 0.3, wz + 0.5);
                    sunk = true;
                } else {
                    location = new Location(water.getWorld(), wx + 0.5,
                            waterSurfaceY(water), wz + 0.5);
                    floatingWater = true;
                }
            } else {
                location = placed.getLocation().add(0.5, 0.5, 0.5);
                support = new Tracker.SupportBlock(clicked.getX(), clicked.getY(), clicked.getZ());
            }
        }
        if (location.getWorld() == null) {
            placingCodes.remove(spec.code());
            return CompletableFuture.completedFuture(new PlaceOutcome(null, PlaceResult.FAILED));
        }

        long now = System.currentTimeMillis();
        String label = spec.name() == null ? "" : spec.name().trim();
        TrackerColor color = spec.color() == null ? TrackerColor.BLUE : spec.color();
        if (target != null) {
            EntityType carrierType = target.getType();
            // Без своего имени трекер называется по-русски: «Лодка», «Лошадь»,
            // «Свинья», а на игроке — ником носителя. Ванильное
            // EntityType#getName() — английское, поэтому подпись берём из
            // EntityNames.
            if (label.isEmpty()) {
                label = EntityNames.autoLabel(carrierType,
                        target instanceof Player carrier ? carrier.getName() : null);
            }
            // Цвет по типу носителя: на карте и в локаторе сразу видно, что
            // за маячок (лодка — синий, лошадь — жёлтый, монстр — красный).
            TrackerColor auto = EntityNames.color(carrierType);
            if (auto != null) {
                color = auto;
            }
        }
        if (label.length() > 64) {
            label = label.substring(0, 64);
        }
        boolean floating = floatingWater;
        Tracker candidate = new Tracker(0, spec.code(), label, color, spec.waterproof(),
                player.getUniqueId(), player.getName(), location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                target == null ? null : target.getUniqueId(), sunk, now, now, now, support);
        candidate.setCarrierPlayer(target instanceof Player);

        return data.createTracker(candidate)
                .whenComplete((created, error) -> placingCodes.remove(spec.code()))
                .thenApply(created -> {
                    if (created == null) {
                        return new PlaceOutcome(null, PlaceResult.FAILED);
                    }
                    created.setWater(floating);
                    TrackerCodes.reserve(created.getCode());
                    trackers.put(created.getId(), created);
                    return new PlaceOutcome(created, PlaceResult.OK);
                });
    }

    /** Можно ли подобрать трекер: рядом или есть админ-право. */
    public boolean canTake(Player player, Tracker tracker) {
        if (tracker == null || player == null) {
            return false;
        }
        if (player.hasPermission("gps.itrak.admin")) {
            return true;
        }
        Location location = tracker.getLocation();
        if (location == null) {
            return false;
        }
        double distance = LocationUtil.distance(player.getLocation(), location);
        return distance <= plugin.getConfigManager().getItrakTakeRadius();
    }

    /** Подобрать трекер: удалить из мира и вернуть предмет владельцу. */
    public CompletableFuture<Boolean> take(Player player, Tracker tracker) {
        if (tracker == null) {
            return CompletableFuture.completedFuture(false);
        }
        unwatchAll(tracker.getId());
        stationaryNotified.remove(tracker.getId());
        lostSince.remove(tracker.getId());
        clearHighlights(tracker.getId());
        trackers.remove(tracker.getId());
        ItemStack item = ItemFactory.trackerItem(new ItemFactory.TrackerSpec(tracker.getCode(),
                tracker.getName(), tracker.getColor(), tracker.isWaterproof()));
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        return data.deleteTracker(tracker.getId()).thenApply(ok -> true);
    }

    /** Снять трекер и выронить предмет: сломали блок, на котором он держался. */
    public CompletableFuture<Boolean> removeDropping(Tracker tracker, Location dropAt) {
        if (tracker == null) {
            return CompletableFuture.completedFuture(false);
        }
        unwatchAll(tracker.getId());
        stationaryNotified.remove(tracker.getId());
        lostSince.remove(tracker.getId());
        clearHighlights(tracker.getId());
        trackers.remove(tracker.getId());
        ItemStack item = ItemFactory.trackerItem(new ItemFactory.TrackerSpec(tracker.getCode(),
                tracker.getName(), tracker.getColor(), tracker.isWaterproof()));
        Location where = dropAt != null ? dropAt : tracker.getLocation();
        if (where != null && where.getWorld() != null) {
            where.getWorld().dropItemNaturally(where, item);
        }
        return data.deleteTracker(tracker.getId());
    }

    /**
     * Трекер, прицепленный к этому блоку (для снятия и поломки).
     *
     * Сначала точное совпадение по опорному блоку. Трекеры, поставленные до
     * 1.1.7, опорного блока не знают (колонки появились позже): для них
     * ищем ближайший трекер в пределах блока-воздуха вокруг нажатого блока —
     * иначе старый трекер нельзя ни снять рукой, ни выронить поломкой.
     */
    public Tracker trackerOnBlock(Block block) {
        if (block == null || block.getWorld() == null) {
            return null;
        }
        String worldName = block.getWorld().getName();
        Tracker legacy = null;
        double legacyDistance = Double.MAX_VALUE;
        for (Tracker tracker : trackers.values()) {
            if (tracker.getWorld() == null || !tracker.getWorld().equals(worldName)) {
                continue;
            }
            if (tracker.isOnBlock(block.getX(), block.getY(), block.getZ())) {
                return tracker;
            }
            if (tracker.getSupportBlock() != null || tracker.isAttached()) {
                continue;
            }
            double dx = tracker.getX() - (block.getX() + 0.5);
            double dy = tracker.getY() - (block.getY() + 0.5);
            double dz = tracker.getZ() - (block.getZ() + 0.5);
            double distance = dx * dx + dy * dy + dz * dz;
            // Ровно соседний блок (трекер висит в воздухе у поверхности):
            // диагональ в 1.7 блока отсекается, соседние трекеры не путаются.
            if (distance <= 1.6 && distance < legacyDistance) {
                legacyDistance = distance;
                legacy = tracker;
            }
        }
        return legacy;
    }

    /** Трекер, прицепленный к существу. */
    public Tracker trackerOnEntity(UUID entityId) {
        if (entityId == null) {
            return null;
        }
        for (Tracker tracker : trackers.values()) {
            if (entityId.equals(tracker.getEntityUuid())) {
                return tracker;
            }
        }
        return null;
    }

    /** Удалить трекер без выдачи предмета (админ, потерянный трекер). */
    public CompletableFuture<Boolean> remove(Tracker tracker) {
        if (tracker == null) {
            return CompletableFuture.completedFuture(false);
        }
        unwatchAll(tracker.getId());
        stationaryNotified.remove(tracker.getId());
        lostSince.remove(tracker.getId());
        clearHighlights(tracker.getId());
        trackers.remove(tracker.getId());
        return data.deleteTracker(tracker.getId());
    }

    // ------------------------------------------------------------------
    // Поиск и подписки
    // ------------------------------------------------------------------

    public Tracker byId(int id) {
        return trackers.get(id);
    }

    /** Текущая точка трекера: у привязанного к существу — прямо сейчас. */
    public Location liveLocation(Tracker tracker) {
        return tracker == null ? null : tracker.liveLocation();
    }

    public Tracker byCode(String code) {
        if (code == null) {
            return null;
        }
        for (Tracker tracker : trackers.values()) {
            if (tracker.getCode().equalsIgnoreCase(code)) {
                return tracker;
            }
        }
        return null;
    }

    /**
     * Поиск по коду, названию (регистр не важен) или номеру в БД.
     *
     * Номер принимается и как {@code 42}, и как {@code #42}: в интерфейсе
     * трекеры подписаны коротким id, и вводить длинный код вручную неудобно.
     */
    public Tracker find(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String needle = query.trim();
        Tracker byCode = byCode(needle);
        if (byCode != null) {
            return byCode;
        }
        for (Tracker tracker : trackers.values()) {
            if (tracker.getName() != null && tracker.getName().equalsIgnoreCase(needle)) {
                return tracker;
            }
        }
        String numeric = needle.startsWith("#") ? needle.substring(1) : needle;
        if (!numeric.isEmpty() && numeric.chars().allMatch(Character::isDigit)) {
            try {
                Tracker byId = byId(Integer.parseInt(numeric));
                if (byId != null) {
                    return byId;
                }
            } catch (NumberFormatException ignored) {
                // Слишком длинное число — это уже не id, идём дальше по имени.
            }
        }
        for (Tracker tracker : trackers.values()) {
            if (tracker.getName() != null
                    && tracker.getName().toLowerCase(java.util.Locale.ROOT)
                    .startsWith(needle.toLowerCase(java.util.Locale.ROOT))) {
                return tracker;
            }
        }
        return null;
    }

    public List<Tracker> all() {
        List<Tracker> list = new ArrayList<>(trackers.values());
        list.sort(Comparator.comparing(Tracker::getCode));
        return list;
    }

    public List<Tracker> byOwner(UUID owner) {
        List<Tracker> list = new ArrayList<>();
        for (Tracker tracker : trackers.values()) {
            if (owner != null && owner.equals(tracker.getOwnerUuid())) {
                list.add(tracker);
            }
        }
        list.sort(Comparator.comparing(Tracker::getCode));
        return list;
    }

    public int countOwned(UUID owner) {
        int count = 0;
        for (Tracker tracker : trackers.values()) {
            if (owner != null && owner.equals(tracker.getOwnerUuid())) {
                count++;
            }
        }
        return count;
    }

    public boolean canManage(Player player, Tracker tracker) {
        return player != null && tracker != null
                && (player.hasPermission("gps.itrak.admin")
                || (tracker.getOwnerUuid() != null && tracker.getOwnerUuid().equals(player.getUniqueId())));
    }

    /** Трекеры в этом мире в радиусе точки, ближние первыми. */
    public List<Nearby> scanAround(Location origin, double radius) {
        List<Nearby> out = new ArrayList<>();
        if (origin == null || origin.getWorld() == null) {
            return out;
        }
        String worldName = origin.getWorld().getName();
        for (Tracker tracker : trackers.values()) {
            if (tracker.getWorld() == null || !tracker.getWorld().equals(worldName)) {
                continue;
            }
            // У привязанного к существу трекера живая точка точнее последней
            // сохранённой: локатор не должен показывать, что он «на игроке»,
            // когда тот уже ушёл.
            Location location = tracker.liveLocation();
            if (location == null) {
                location = tracker.getLocation();
            }
            if (location == null) {
                continue;
            }
            double distance = LocationUtil.distance(origin, location);
            if (distance <= radius) {
                out.add(new Nearby(tracker, distance));
            }
        }
        out.sort(Comparator.comparingDouble(Nearby::distance));
        return out;
    }

    /** Трекеры рядом с игроком. */
    public List<Nearby> scan(Player player, double radius) {
        return player == null ? List.of() : scanAround(player.getLocation(), radius);
    }

    /** Сколько миллисекунд ещё ждать до следующего скана; 0 — готов. */
    public long scanCooldownRemaining(Player player) {
        if (player == null) {
            return 0L;
        }
        Long endsAt = scanCooldownEnd.get(player.getUniqueId());
        if (endsAt == null) {
            return 0L;
        }
        long remaining = LocatorCooldown.remaining(endsAt, System.currentTimeMillis());
        if (remaining <= 0L) {
            scanCooldownEnd.remove(player.getUniqueId());
            // Перезарядка кончилась — попытки снова полные.
            scanAttempts.remove(player.getUniqueId());
            return 0L;
        }
        return remaining;
    }

    /** Сколько удачных сканирований осталось до перезарядки. */
    public int scanAttemptsLeft(Player player) {
        if (player == null) {
            return 0;
        }
        int max = plugin.getConfigManager().getItrakScanAttempts();
        if (scanCooldownRemaining(player) > 0L) {
            return 0;
        }
        Integer left = scanAttempts.get(player.getUniqueId());
        return left == null ? max : Math.max(0, Math.min(max, left));
    }

    /**
     * Списать попытку за удачный скан. Пустой скан попытку не тратит: если
     * рядом ничего нет, локатор не уходит в перезарядку. Она начинается
     * только когда попытки реально израсходованы на найденные трекеры.
     */
    public ScanConsumption consumeScanAttempt(Player player, double radius) {
        if (player == null) {
            return new ScanConsumption(0, 0, false);
        }
        int max = plugin.getConfigManager().getItrakScanAttempts();
        int left = scanAttemptsLeft(player) - 1;
        if (left <= 0) {
            scanAttempts.remove(player.getUniqueId());
            startScanCooldown(player, scanCooldownSeconds(radius));
            return new ScanConsumption(0, max, true);
        }
        scanAttempts.put(player.getUniqueId(), left);
        return new ScanConsumption(left, max, false);
    }

    /** Перезарядка для локатора с этим радиусом: дальний — дольше. */
    public int scanCooldownSeconds(double radius) {
        ConfigManager config = plugin.getConfigManager();
        int normalRadius = (int) config.getItrakScanRadius();
        return LocatorCooldown.seconds((int) Math.ceil(radius), normalRadius,
                config.getItrakScanCooldownSeconds(), config.getItrakScanCooldownLongSeconds());
    }

    /** Начать перезарядку и показать ванильную белую полосу КД на локаторе. */
    public void startScanCooldown(Player player, int seconds) {
        if (player == null) {
            return;
        }
        int safe = Math.max(0, Math.min(LocatorCooldown.MAX_SECONDS, seconds));
        if (safe <= 0) {
            scanCooldownEnd.remove(player.getUniqueId());
            applyScanCooldownBar(player);
            return;
        }
        scanCooldownEnd.put(player.getUniqueId(), System.currentTimeMillis() + safe * 1000L);
        applyScanCooldownBar(player);
        pruneScanCooldowns();
    }

    /**
     * Белая полоса КД на предмете локатора: ванильный оверлей, как у предметов
     * с перезарядкой. Полоса рисуется по материалу предмета, поэтому у
     * трекеров теперь свои материалы — КД локатора на маячках больше не висит
     * (раньше синий трекер был тем же компасом восстановления).
     */
    public void applyScanCooldownBar(Player player) {
        if (player == null) {
            return;
        }
        try {
            player.setCooldown(ItemFactory.LOCATOR_MATERIAL,
                    LocatorCooldown.ticks(scanCooldownRemaining(player)));
        } catch (Throwable ignored) {
        }
    }

    /** Просроченные записи не копятся до перезапуска сервера. */
    private void pruneScanCooldowns() {
        if (scanCooldownEnd.size() < 256) {
            return;
        }
        long now = System.currentTimeMillis();
        scanCooldownEnd.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    // ------------------------------------------------------------------
    // Дальность сигнала: точное место или район
    // ------------------------------------------------------------------

    /**
     * Что игрок видит про трекер. В зоне связи — живые точные координаты; за
     * дальностью сигнала — только район и последняя отметка, которую успел
     * получить именно этот игрок. Так далёкий маячок не выдаёт точку, но и не
     * пропадает из списка.
     */
    public Signal signal(Player viewer, Tracker tracker) {
        if (tracker == null || viewer == null) {
            return new Signal(null, null, 0L, null);
        }
        Location live = liveLocation(tracker);
        if (live == null) {
            live = tracker.getLocation();
        }
        if (live == null) {
            return new Signal(null, null, 0L, null);
        }
        int range = plugin.getConfigManager().getItrakSignalRange();
        double distance = LocationUtil.distance(viewer.getLocation(), live);
        if (SignalRegion.inRange(distance, range)) {
            rememberLastKnown(viewer, tracker, live);
            return new Signal(live, live, System.currentTimeMillis(), null);
        }
        LastKnown known = recallLastKnown(viewer, tracker);
        return new Signal(null, known == null ? null : known.toLocation(),
                known == null ? 0L : known.at(), regionText(live));
    }

    /**
     * Куда ведёт маршрут: в зоне связи — в точку трекера, вне её — в центр
     * района. Иначе маршрут выдавал бы точное место далёкого маячка.
     */
    public Location targetLocation(Player viewer, Tracker tracker) {
        if (tracker == null) {
            return null;
        }
        Location live = liveLocation(tracker);
        if (live == null) {
            live = tracker.getLocation();
        }
        if (live == null || viewer == null) {
            return live;
        }
        int range = plugin.getConfigManager().getItrakSignalRange();
        if (SignalRegion.inRange(LocationUtil.distance(viewer.getLocation(), live), range)) {
            return live;
        }
        int cell = plugin.getConfigManager().getItrakSignalRegionSize();
        int x = SignalRegion.center(floor(live.getX()), cell);
        int z = SignalRegion.center(floor(live.getZ()), cell);
        return new Location(live.getWorld(), x + 0.5, live.getY(), z + 0.5);
    }

    /** «Район X..X, Z..Z» — приблизительное место при потере сигнала. */
    public String regionText(Location live) {
        if (live == null) {
            return "?";
        }
        int cell = plugin.getConfigManager().getItrakSignalRegionSize();
        int x = floor(live.getX());
        int z = floor(live.getZ());
        return plugin.getMessages().get("itrak-region",
                "x1", String.valueOf(SignalRegion.start(x, cell)),
                "x2", String.valueOf(SignalRegion.end(x, cell)),
                "z1", String.valueOf(SignalRegion.start(z, cell)),
                "z2", String.valueOf(SignalRegion.end(z, cell)));
    }

    /** «Последнее известное: x, y, z (N назад)» — для чата и GUI. */
    public String lastKnownText(Signal signal) {
        Location known = signal == null ? null : signal.lastKnown();
        if (known == null) {
            return plugin.getMessages().get("itrak-last-known-none");
        }
        long ago = Math.max(0L, System.currentTimeMillis() - signal.lastKnownAt());
        return plugin.getMessages().get("itrak-last-known",
                "x", formatCoord(known.getX()),
                "y", formatCoord(known.getY()),
                "z", formatCoord(known.getZ()),
                "ago", formatDuration(ago));
    }

    private void rememberLastKnown(Player viewer, Tracker tracker, Location live) {
        if (viewer == null || tracker == null || live == null) {
            return;
        }
        lastKnown.computeIfAbsent(viewer.getUniqueId(), key -> new ConcurrentHashMap<>())
                .put(tracker.getId(), new LastKnown(
                        live.getWorld() == null ? null : live.getWorld().getName(),
                        live.getX(), live.getY(), live.getZ(), System.currentTimeMillis()));
    }

    private LastKnown recallLastKnown(Player viewer, Tracker tracker) {
        if (viewer == null || tracker == null) {
            return null;
        }
        Map<Integer, LastKnown> known = lastKnown.get(viewer.getUniqueId());
        return known == null ? null : known.get(tracker.getId());
    }

    /**
     * Пока игрок в зоне связи, запоминаем для него точку трекера: когда он
     * уйдёт дальше, показать будет что — «последнее известное местоположение».
     */
    private void refreshSignalSnapshots() {
        int range = plugin.getConfigManager().getItrakSignalRange();
        if (range <= 0 || trackers.isEmpty()) {
            return;
        }
        for (Tracker tracker : trackers.values()) {
            Location live = tracker.liveLocation();
            if (live == null) {
                continue;
            }
            for (UUID id : recipientsOf(tracker)) {
                Player player = Bukkit.getPlayer(id);
                if (player == null || !player.isOnline()) {
                    continue;
                }
                if (LocationUtil.distance(player.getLocation(), live) <= range) {
                    rememberLastKnown(player, tracker, live);
                }
            }
        }
    }

    public void watch(Player player, Tracker tracker) {
        if (player == null || tracker == null) {
            return;
        }
        watchedByPlayer.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet())
                .add(tracker.getId());
        lastWatchMessage.remove(player.getUniqueId());
    }

    public void unwatch(Player player, Tracker tracker) {
        if (player == null || tracker == null) {
            return;
        }
        Set<Integer> ids = watchedByPlayer.get(player.getUniqueId());
        if (ids != null) {
            ids.remove(tracker.getId());
        }
    }

    public boolean isWatching(Player player, Tracker tracker) {
        if (player == null || tracker == null) {
            return false;
        }
        Set<Integer> ids = watchedByPlayer.get(player.getUniqueId());
        return ids != null && ids.contains(tracker.getId());
    }

    private void unwatchAll(int trackerId) {
        for (Set<Integer> ids : watchedByPlayer.values()) {
            ids.remove(trackerId);
        }
    }

    /** Выход игрока: подписки не должны копиться до перезапуска. */
    public void forget(Player player) {
        if (player == null) {
            return;
        }
        watchedByPlayer.remove(player.getUniqueId());
        lastWatchMessage.remove(player.getUniqueId());
        lastKnown.remove(player.getUniqueId());
        // scanCooldownEnd и scanAttempts НЕ трогаем: перезаход не должен
        // сбрасывать КД локатора и накопленные попытки.
    }

    /** Трекеры, прицепленные к этой сущности: их может быть несколько. */
    public List<Tracker> trackersOnEntity(UUID entityId) {
        List<Tracker> out = new ArrayList<>();
        if (entityId == null) {
            return out;
        }
        for (Tracker tracker : trackers.values()) {
            if (entityId.equals(tracker.getEntityUuid())) {
                out.add(tracker);
            }
        }
        return out;
    }

    /**
     * Сущность исчезла (смерть моба или игрока, выход игрока). С мобов и
     * транспорта трекеры слетают и выпадают предметами там, где были; на
     * игроке трекер остаётся — он просто становится офлайн до следующего
     * входа носителя (см. {@link #onPlayerQuit}).
     */
    public void onEntityGone(Entity entity) {
        if (entity == null) {
            return;
        }
        Location at = entity.getLocation();
        String entityName = EntityNames.name(entity.getType());
        long now = System.currentTimeMillis();
        for (Tracker tracker : trackersOnEntity(entity.getUniqueId())) {
            if (entity instanceof Player) {
                // UUID игрока постоянный: трекер не слетает, а ждёт его.
                tracker.setCarrierPlayer(true);
                tracker.setSignal(false);
                tracker.moveTo(at.getX(), at.getY(), at.getZ(), now);
                lostSince.remove(tracker.getId());
                dirty.add(tracker.getId());
                continue;
            }
            String name = tracker.getDisplayName();
            Player owner = tracker.getOwnerUuid() == null ? null
                    : Bukkit.getPlayer(tracker.getOwnerUuid());
            removeDropping(tracker, at);
            if (owner != null && owner.isOnline()) {
                owner.sendMessage(plugin.getMessages().getPrefixed("itrak-tracker-fell-entity-owner",
                        "name", name,
                        "entity", entityName,
                        "world", at.getWorld() == null ? "?" : at.getWorld().getName(),
                        "x", formatCoord(at.getX()),
                        "y", formatCoord(at.getY()),
                        "z", formatCoord(at.getZ())));
            }
        }
    }

    /**
     * Игрок вышел: трекеры на нём остаются привязанными и переходят в
     * «офлайн» на последних координатах. При следующем входе тик находит
     * игрока по UUID и трекер снова цепляется к нему — снимается он только
     * вручную, как и просили.
     */
    public void onPlayerQuit(Player player) {
        if (player == null) {
            return;
        }
        Location at = player.getLocation();
        long now = System.currentTimeMillis();
        for (Tracker tracker : trackersOnEntity(player.getUniqueId())) {
            tracker.setCarrierPlayer(true);
            tracker.moveTo(at.getX(), at.getY(), at.getZ(), now);
            tracker.setSignal(false);
            lostSince.remove(tracker.getId());
            dirty.add(tracker.getId());
            // Сохраняем сразу: после выхода игрока сервер может уйти в
            // рестарт раньше, чем сработает периодическое сохранение.
            data.updateTracker(tracker).exceptionally(error -> {
                plugin.getLogger().log(Level.WARNING, "HahGps: не удалось сохранить трекер "
                        + tracker.getCode() + " при выходе игрока", error);
                return false;
            });
        }
    }

    // ------------------------------------------------------------------
    // Бросок трекера
    // ------------------------------------------------------------------

    /**
     * Бросить трекер: он летит как снежок и цепляется к тому, во что попал
     * (блок, существо или игрок). Предмет расходуется вызывающей стороной.
     * @param gentle аккуратный бросок под ноги (Shift+ПКМ)
     */
    public boolean throwTracker(Player player, ItemFactory.TrackerSpec spec, boolean gentle) {
        if (player == null || spec == null || !plugin.getConfigManager().isItrakEnabled()) {
            return false;
        }
        if (byCode(spec.code()) != null) {
            return false;
        }
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Snowball snowball = world.spawn(eye, Snowball.class);
        snowball.setShooter(player);
        snowball.setGravity(true);
        double speed = plugin.getConfigManager().getItrakThrowSpeed();
        if (gentle) {
            speed = Math.max(0.4, speed * 0.4);
        }
        snowball.setVelocity(eye.getDirection().multiply(speed));
        ItemFactory.writeSpec(snowball, spec, player.getUniqueId());
        // Shift-бросок — намеренно утопить водонепроницаемый трекер: попадёт
        // в воду, ляжет на дно и продолжит передавать сигнал.
        ItemFactory.writeSink(snowball, gentle);
        return true;
    }

    /** Попадание брошенного трекера: к существу, к блоку или на землю. */
    public CompletableFuture<PlaceOutcome> placeThrown(Player thrower, ItemFactory.TrackerSpec spec,
                                                       Block hitBlock, BlockFace face,
                                                       Entity hitEntity, Location fallback,
                                                       boolean sink) {
        if (thrower == null || spec == null) {
            return CompletableFuture.completedFuture(new PlaceOutcome(null, PlaceResult.FAILED));
        }
        boolean attachPlayers = plugin.getConfigManager().isItrakAttachPlayers();
        if (hitEntity != null && hitEntity.isValid() && !hitEntity.isDead()
                && hitEntity.getType() != EntityType.UNKNOWN
                && (!(hitEntity instanceof Player) || attachPlayers)) {
            return place(thrower, spec, hitEntity.getLocation().getBlock(), BlockFace.UP, hitEntity,
                    false);
        }
        if (hitBlock != null) {
            return place(thrower, spec, hitBlock, face == null ? BlockFace.UP : face, null, sink);
        }
        Location landing = fallback;
        if (landing != null && landing.getWorld() != null) {
            // Снежок разбивается о воду/растение на высоте поверхности:
            // сам блок попадания может быть воздухом. Берём первый блок под
            // точкой падения — воду или землю, иначе трекер завис бы в воздухе
            // и «не заметил» воду.
            return place(thrower, spec, groundUnder(landing), BlockFace.UP, null, sink);
        }
        return CompletableFuture.completedFuture(new PlaceOutcome(null, PlaceResult.FAILED));
    }

    /** Первый непустой блок под точкой падения (воздух пропускаем). */
    private static Block groundUnder(Location location) {
        Block block = location.getBlock();
        int guard = 0;
        while (guard++ < 4 && block.getType().isAir()) {
            block = block.getRelative(BlockFace.DOWN);
        }
        return block;
    }

    // ------------------------------------------------------------------
    // Тексты и форматирование
    // ------------------------------------------------------------------

    public String statusText(Tracker tracker) {
        MessagesManager messages = plugin.getMessages();
        // Утонувший водонепроницаемый трекер сигнал не теряет: для него
        // «затонул» звучало бы как поломка, поэтому статус — «под водой».
        if (tracker.isSunk() && tracker.isWaterproof()) {
            return messages.get("itrak-status-submerged");
        }
        // Трекер на игроке, которого нет в сети, — не «потерян»: он на нём,
        // просто ждёт входа. Так и пишем, чтобы не путать с потерей сигнала.
        if (tracker.isCarrierOffline()) {
            return messages.get("itrak-status-offline");
        }
        return switch (tracker.status()) {
            case LIVE -> messages.get("itrak-status-live");
            case SUNK -> messages.get("itrak-status-sunk");
            case NO_SIGNAL -> messages.get("itrak-status-no-signal");
        };
    }

    /** «в движении» / «неподвижен» — состояние для GUI и сообщений. */
    public String stateText(Tracker tracker, long now) {
        MessagesManager messages = plugin.getMessages();
        if (!tracker.hasSignal()) {
            return messages.get("itrak-state-offline");
        }
        long threshold = plugin.getConfigManager().getItrakStationaryMillis();
        return tracker.isStationary(now, threshold)
                ? messages.get("itrak-state-stationary")
                : messages.get("itrak-state-moving");
    }

    /**
     * Ник игрока-носителя. Для трекера на мобе, транспорте или блоке — null:
     * ника там нет, а название существа уже стоит в строке трекера.
     */
    public String carrierName(Tracker tracker) {
        if (!isPlayerCarrier(tracker)) {
            return null;
        }
        return playerNick(tracker.getEntityUuid());
    }

    /** Ник игрока по UUID: онлайн или из профиля; null — игрок серверу неизвестен. */
    private static String playerNick(UUID id) {
        if (id == null) {
            return null;
        }
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
            return offline == null ? null : offline.getName();
        } catch (Throwable ignored) {
            // Сервер выключается или профиль недоступен — ника просто нет.
            return null;
        }
    }

    /**
     * Подпись трекера на игроке — ник носителя. Безликое «Игрок» (так
     * назывались трекеры до 1.3.6) и пустую подпись заменяем ником; имя,
     * которое владелец дал трекеру в наковальне, не трогаем.
     */
    private void repairCarrierLabel(Tracker tracker) {
        if (tracker == null || !isPlayerCarrier(tracker)) {
            return;
        }
        String name = tracker.getName() == null ? "" : tracker.getName().trim();
        if (!name.isEmpty() && !EntityNames.isPlayerLabel(name)) {
            return;
        }
        String nick = playerNick(tracker.getEntityUuid());
        if (nick == null || nick.isBlank() || nick.equals(name)) {
            return;
        }
        tracker.setName(nick);
        dirty.add(tracker.getId());
    }

    /**
     * Подсказка при наведении на строку трекера, прицепленного к игроку:
     * ник носителя и его живой статус. У трекера на мобе или транспорте
     * подсказки нет — ника там нет, а название существа уже есть в строке.
     */
    public Component withCarrierHover(Component line, Tracker tracker) {
        if (line == null || tracker == null) {
            return line;
        }
        String carrier = carrierName(tracker);
        if (carrier == null) {
            return line;
        }
        MessagesManager messages = plugin.getMessages();
        Component hover = messages.getComponent("itrak-hover-carrier", "name", carrier)
                .append(Component.newline())
                .append(messages.getComponent("itrak-hover-status",
                        "status", messages.get(isCarrierOnline(tracker)
                                ? "itrak-status-live" : "itrak-status-offline")));
        return line.hoverEvent(HoverEvent.showText(hover));
    }

    /** Игрок-носитель сейчас в сети: для подсказки «в сети»/«офлайн». */
    public boolean isCarrierOnline(Tracker tracker) {
        if (!isPlayerCarrier(tracker)) {
            return false;
        }
        Player online = Bukkit.getPlayer(tracker.getEntityUuid());
        return online != null && online.isOnline();
    }

    /**
     * Статус глазами конкретного игрока: за дальностью сигнала у трекера нет
     * точных данных, и это важнее «в сети»/«нет сигнала».
     */
    public String statusText(Player viewer, Tracker tracker) {
        MessagesManager messages = plugin.getMessages();
        if (tracker == null) {
            return "?";
        }
        if (tracker.isSunk() && tracker.isWaterproof()) {
            return messages.get("itrak-status-submerged");
        }
        if (tracker.isCarrierOffline()) {
            return messages.get("itrak-status-offline");
        }
        if (viewer != null && !signal(viewer, tracker).inRange()) {
            return messages.get("itrak-status-out-of-range");
        }
        return switch (tracker.status()) {
            case LIVE -> messages.get("itrak-status-live");
            case SUNK -> messages.get("itrak-status-sunk");
            case NO_SIGNAL -> messages.get("itrak-status-no-signal");
        };
    }

    /** Состояние глазами игрока: вне зоны связи точных данных нет. */
    public String stateText(Player viewer, Tracker tracker, long now) {
        if (viewer != null && tracker != null && !signal(viewer, tracker).inRange()) {
            return plugin.getMessages().get("itrak-state-unknown");
        }
        return stateText(tracker, now);
    }

    public String formatDuration(long millis) {
        MessagesManager messages = plugin.getMessages();
        long seconds = Math.max(0L, millis / 1000L);
        if (seconds < 60) {
            return messages.get("itrak-duration-seconds", "seconds", String.valueOf(seconds));
        }
        long minutes = seconds / 60L;
        if (minutes < 60) {
            return messages.get("itrak-duration-minutes", "minutes", String.valueOf(minutes));
        }
        long hours = minutes / 60L;
        return messages.get("itrak-duration-hours",
                "hours", String.valueOf(hours), "minutes", String.valueOf(minutes % 60L));
    }

    public static String formatCoord(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    public ConfigManager config() {
        return plugin.getConfigManager();
    }

    // ------------------------------------------------------------------
    // Вода
    // ------------------------------------------------------------------

    /** Настоящая вода в блоке: источник или течение. Затопленные блоки — не вода. */
    static boolean isWaterBody(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        return type == Material.WATER || type == Material.BUBBLE_COLUMN;
    }

    /** Падающая вода (водопад): сквозь неё трекер не всплывает, а летит вниз. */
    private static boolean isFallingWater(Block block) {
        if (block == null || !isWaterBody(block)) {
            return false;
        }
        World world = block.getWorld();
        if (world == null) {
            return false;
        }
        try {
            return world.getFluidData(block.getX(), block.getY(), block.getZ())
                    instanceof FallingFluidData falling && falling.isFalling();
        } catch (Throwable ignored) {
            // Paper-API нет: водопад не отличается от обычной воды.
            return false;
        }
    }

    /** Верхний блок столба воды, в котором стоит трекер. */
    private static int topWaterY(Block origin) {
        World world = origin.getWorld();
        if (world == null) {
            return origin.getY();
        }
        int y = origin.getY();
        int guard = 0;
        while (guard++ < 320) {
            Block above = world.getBlockAt(origin.getX(), y + 1, origin.getZ());
            // Падающая вода водопада — не поверхность: иначе трекер из
            // бассейна под водопадом улетал бы на его верх.
            if (!isWaterBody(above) || isFallingWater(above)) {
                break;
            }
            y++;
        }
        return y;
    }

    /** Нижний блок столба воды: дно, на которое ложится утонувший трекер. */
    private static int bottomWaterY(Block origin) {
        World world = origin.getWorld();
        if (world == null) {
            return origin.getY();
        }
        int y = origin.getY();
        int guard = 0;
        while (guard++ < 320 && isWaterBody(world.getBlockAt(origin.getX(), y - 1, origin.getZ()))) {
            y--;
        }
        return Math.max(world.getMinHeight(), y);
    }

    /** Поверхность воды: не верх блока, а высота жидкости в нём. */
    private static double waterSurfaceY(Block origin) {
        World world = origin.getWorld();
        if (world == null) {
            return origin.getY() + 1.0;
        }
        int top = topWaterY(origin);
        return top + fluidHeight(world, origin.getX(), top, origin.getZ());
    }

    /**
     * Высота жидкости внутри блока из Paper-API (0..1). На ядре без этого
     * метода (Spigot) трекер всё равно плавает — просто на целом блоке.
     */
    private static double fluidHeight(World world, int x, int y, int z) {
        try {
            FluidData data = world.getFluidData(x, y, z);
            float height = data.computeHeight(new Location(world, x + 0.5, y + 0.5, z + 0.5));
            if (height > 0.05f && height <= 1.0001f) {
                return height;
            }
        } catch (Throwable ignored) {
            // Paper-API нет: считаем воду полным блоком.
        }
        return 1.0;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    /**
     * Мир для {@link WaterPhysics}. Чанки не грузит: в незагруженном соседе
     * движение считается упёршимся в берег, а поверхность — целым блоком.
     */
    private static final class WorldTerrain implements WaterPhysics.Terrain {

        private final World world;

        WorldTerrain(World world) {
            this.world = world;
        }

        @Override
        public boolean isWater(int x, int y, int z) {
            return loaded(x, z) && isWaterBody(world.getBlockAt(x, y, z));
        }

        @Override
        public boolean isFallingWater(int x, int y, int z) {
            return loaded(x, z) && TrackerService.isFallingWater(world.getBlockAt(x, y, z));
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            if (!loaded(x, z)) {
                return true;
            }
            Block block = world.getBlockAt(x, y, z);
            return !isWaterBody(block) && block.getType().isSolid();
        }

        @Override
        public double surfaceY(int x, int y, int z) {
            return loaded(x, z) ? waterSurfaceY(world.getBlockAt(x, y, z)) : y + 1.0;
        }

        @Override
        public double flowX(int x, int y, int z) {
            Vector flow = flow(x, y, z);
            return flow == null ? 0.0 : flow.getX();
        }

        @Override
        public double flowZ(int x, int y, int z) {
            Vector flow = flow(x, y, z);
            return flow == null ? 0.0 : flow.getZ();
        }

        /** Течение из Paper-API; в незагруженном окружении — стоячая вода. */
        private Vector flow(int x, int y, int z) {
            if (!neighboursLoaded(x, z)) {
                return null;
            }
            try {
                return world.getFluidData(x, y, z)
                        .computeFlowDirection(new Location(world, x + 0.5, y + 0.5, z + 0.5));
            } catch (Throwable ignored) {
                return null;
            }
        }

        private boolean loaded(int x, int z) {
            return world.isChunkLoaded(x >> 4, z >> 4);
        }

        /** Поток воды читает соседей: их чанки должны быть загружены заранее. */
        private boolean neighboursLoaded(int x, int z) {
            return loaded(x, z)
                    && loaded(x + 1, z) && loaded(x - 1, z)
                    && loaded(x, z + 1) && loaded(x, z - 1)
                    && loaded(x + 1, z + 1) && loaded(x - 1, z + 1)
                    && loaded(x + 1, z - 1) && loaded(x - 1, z - 1);
        }
    }

    /** Все трекеры — снимок для GUI (главный поток). */
    public Set<Integer> ids() {
        return new HashSet<>(trackers.keySet());
    }
}
