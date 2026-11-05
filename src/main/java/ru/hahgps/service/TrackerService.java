package ru.hahgps.service;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.config.ConfigManager;
import ru.hahgps.config.MessagesManager;
import ru.hahgps.item.ItemFactory;
import ru.hahgps.item.TrackerCodes;
import ru.hahgps.model.Tracker;
import ru.hahgps.model.TrackerColor;
import ru.hahgps.storage.DataService;
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

    private final HahGpsPlugin plugin;
    private final DataService data;

    private final Map<Integer, Tracker> trackers = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Integer>> watchedByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastWatchMessage = new ConcurrentHashMap<>();
    private final Map<UUID, Long> scanCooldown = new ConcurrentHashMap<>();
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
                }
                trackers.put(tracker.getId(), tracker);
            }
        }
        plugin.getLogger().info("HahGps: загружено трекеров iTrak — " + trackers.size());
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
        scanCooldown.clear();
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
            if (tracker.hasSignal()) {
                tracker.setSignal(false);
                dirty.add(tracker.getId());
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
        tracker.setSignal(true);
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
                Location location = tracker.getLocation();
                double distance = location == null ? Double.MAX_VALUE
                        : LocationUtil.distance(player.getLocation(), location);
                player.sendMessage(messages.getPrefixed("itrak-watch-update",
                        "name", tracker.getDisplayName(),
                        "code", tracker.getCode(),
                        "x", formatCoord(tracker.getX()),
                        "y", formatCoord(tracker.getY()),
                        "z", formatCoord(tracker.getZ()),
                        "world", tracker.getWorld() == null ? "?" : tracker.getWorld(),
                        "distance", LocationUtil.formatDistance(distance),
                        "status", statusText(tracker),
                        "time", formatDuration(tracker.stationaryMillis(now))));
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
            player.sendMessage(messages.getPrefixed("itrak-stationary",
                    "name", tracker.getDisplayName(),
                    "time", formatDuration(tracker.stationaryMillis(now))));
            player.sendMessage(messages.getPrefixed("itrak-stationary-route",
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
     */
    public CompletableFuture<PlaceOutcome> place(Player player, ItemFactory.TrackerSpec spec,
                                                 Block clicked, BlockFace face, Entity target) {
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
        if (target != null) {
            location = target.getLocation().clone()
                    .add(0, Math.max(0.4, target.getHeight() * 0.5), 0);
        } else {
            Block placed = clicked.getRelative(face == null ? BlockFace.UP : face);
            boolean water = isWater(clicked) || isWater(placed);
            if (water) {
                if (spec.waterproof()) {
                    location = new Location(clicked.getWorld(), clicked.getX() + 0.5,
                            waterSurfaceY(clicked), clicked.getZ() + 0.5);
                } else {
                    location = new Location(clicked.getWorld(), clicked.getX() + 0.5,
                            waterBottomY(clicked), clicked.getZ() + 0.5);
                    sunk = true;
                }
            } else {
                location = placed.getLocation().add(0.5, 0.5, 0.5);
            }
            support = new Tracker.SupportBlock(clicked.getX(), clicked.getY(), clicked.getZ());
        }
        if (location.getWorld() == null) {
            placingCodes.remove(spec.code());
            return CompletableFuture.completedFuture(new PlaceOutcome(null, PlaceResult.FAILED));
        }

        long now = System.currentTimeMillis();
        String label = spec.name() == null ? "" : spec.name().trim();
        if (label.length() > 64) {
            label = label.substring(0, 64);
        }
        Tracker candidate = new Tracker(0, spec.code(), label, spec.color(), spec.waterproof(),
                player.getUniqueId(), player.getName(), location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                target == null ? null : target.getUniqueId(), sunk, now, now, now, support);

        return data.createTracker(candidate)
                .whenComplete((created, error) -> placingCodes.remove(spec.code()))
                .thenApply(created -> {
                    if (created == null) {
                        return new PlaceOutcome(null, PlaceResult.FAILED);
                    }
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

    /** Поиск по коду или названию (регистр не важен). */
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

    public boolean isScanReady(Player player) {
        long last = scanCooldown.getOrDefault(player.getUniqueId(), 0L);
        return System.currentTimeMillis() - last >= 1500L;
    }

    public void markScan(Player player) {
        scanCooldown.put(player.getUniqueId(), System.currentTimeMillis());
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
        scanCooldown.remove(player.getUniqueId());
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
     * Сущность исчезла (смерть моба или игрока, выход игрока): трекеры
     * слетают и выпадают предметами там, где были.
     *
     * Раньше здесь только гасился сигнал: запись оставалась привязанной к
     * несуществующей сущности, и локатор продолжал показывать трекер «на
     * игроке», которого уже нет, — а снять его можно было только командой.
     */
    public void onEntityGone(Entity entity) {
        if (entity == null) {
            return;
        }
        Location at = entity.getLocation();
        String entityName = entity.getName();
        for (Tracker tracker : trackersOnEntity(entity.getUniqueId())) {
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
        return true;
    }

    /** Попадание брошенного трекера: к существу, к блоку или на землю. */
    public CompletableFuture<PlaceOutcome> placeThrown(Player thrower, ItemFactory.TrackerSpec spec,
                                                       Block hitBlock, BlockFace face,
                                                       Entity hitEntity, Location fallback) {
        if (thrower == null || spec == null) {
            return CompletableFuture.completedFuture(new PlaceOutcome(null, PlaceResult.FAILED));
        }
        boolean attachPlayers = plugin.getConfigManager().isItrakAttachPlayers();
        if (hitEntity != null && hitEntity.isValid() && !hitEntity.isDead()
                && (hitEntity instanceof org.bukkit.entity.LivingEntity
                || hitEntity instanceof org.bukkit.entity.Vehicle)
                && (!(hitEntity instanceof Player) || attachPlayers)) {
            return place(thrower, spec, hitEntity.getLocation().getBlock(), BlockFace.UP, hitEntity);
        }
        if (hitBlock != null) {
            return place(thrower, spec, hitBlock, face == null ? BlockFace.UP : face, null);
        }
        Location landing = fallback;
        if (landing != null && landing.getWorld() != null) {
            return place(thrower, spec, landing.getBlock(), BlockFace.UP, null);
        }
        return CompletableFuture.completedFuture(new PlaceOutcome(null, PlaceResult.FAILED));
    }

    // ------------------------------------------------------------------
    // Тексты и форматирование
    // ------------------------------------------------------------------

    public String statusText(Tracker tracker) {
        MessagesManager messages = plugin.getMessages();
        return switch (tracker.status()) {
            case LIVE -> messages.get("itrak-status-live");
            case SUNK -> messages.get("itrak-status-sunk");
            case NO_SIGNAL -> messages.get("itrak-status-no-signal");
        };
    }

    /** «в движении» / «неподвижен» — состояние для GUI и сообщений. */
    public String stateText(Tracker tracker, long now) {
        MessagesManager messages = plugin.getMessages();
        if (tracker.status() != Tracker.Status.LIVE) {
            return messages.get("itrak-state-offline");
        }
        long threshold = plugin.getConfigManager().getItrakStationaryMillis();
        return tracker.isStationary(now, threshold)
                ? messages.get("itrak-state-stationary")
                : messages.get("itrak-state-moving");
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

    /** Вода или затопленный блок: на воду тоже можно прицепить трекер. */
    static boolean isWater(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
            return true;
        }
        try {
            BlockData blockData = block.getBlockData();
            return blockData instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double waterSurfaceY(Block origin) {
        World world = origin.getWorld();
        if (world == null) {
            return origin.getY();
        }
        int y = origin.getY();
        int guard = 0;
        while (guard++ < 320 && isWater(world.getBlockAt(origin.getX(), y + 1, origin.getZ()))) {
            y++;
        }
        return y + 1.0;
    }

    private static double waterBottomY(Block origin) {
        World world = origin.getWorld();
        if (world == null) {
            return origin.getY();
        }
        int y = origin.getY();
        int guard = 0;
        while (guard++ < 320 && isWater(world.getBlockAt(origin.getX(), y - 1, origin.getZ()))) {
            y--;
        }
        return Math.max(world.getMinHeight() + 1, y) + 0.3;
    }

    /** Все трекеры — снимок для GUI (главный поток). */
    public Set<Integer> ids() {
        return new HashSet<>(trackers.keySet());
    }
}
