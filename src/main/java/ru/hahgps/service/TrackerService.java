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
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.config.ConfigManager;
import ru.hahgps.config.MessagesManager;
import ru.hahgps.item.ItemFactory;
import ru.hahgps.model.Tracker;
import ru.hahgps.model.TrackerColor;
import ru.hahgps.storage.DataService;
import ru.hahgps.util.LocationUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
        if (loaded != null) {
            for (Tracker tracker : loaded) {
                if (tracker == null || tracker.getCode() == null) {
                    continue;
                }
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
        pushWatchMessages(now);
        saveDirty(now);
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
            return;
        }
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

    private void spawnParticles() {
        double radius = plugin.getConfigManager().getItrakParticleRadius();
        double radiusSquared = radius * radius;
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
            float size = tracker.hasSignal() ? 0.9f : 0.45f;
            Particle.DustOptions dust = new Particle.DustOptions(tracker.getColor().bukkitColor(), size);
            for (Player player : players) {
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
        Set<UUID> recipients = new LinkedHashSet<>();
        if (tracker.getOwnerUuid() != null) {
            recipients.add(tracker.getOwnerUuid());
        }
        for (Map.Entry<UUID, Set<Integer>> entry : watchedByPlayer.entrySet()) {
            if (entry.getValue().contains(tracker.getId())) {
                recipients.add(entry.getKey());
            }
        }
        for (UUID id : recipients) {
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
        if (byCode(spec.code()) != null) {
            return CompletableFuture.completedFuture(new PlaceOutcome(null, PlaceResult.ALREADY_PLACED));
        }
        boolean admin = player.hasPermission("gps.itrak.admin");
        if (!admin && countOwned(player.getUniqueId()) >= plugin.getConfigManager().getItrakMaxPerPlayer()) {
            return CompletableFuture.completedFuture(new PlaceOutcome(null, PlaceResult.LIMIT));
        }

        Location location;
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
        }
        if (location.getWorld() == null) {
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
                target == null ? null : target.getUniqueId(), sunk, now, now, now);

        return data.createTracker(candidate).thenApply(created -> {
            if (created == null) {
                return new PlaceOutcome(null, PlaceResult.FAILED);
            }
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
        trackers.remove(tracker.getId());
        ItemStack item = ItemFactory.trackerItem(new ItemFactory.TrackerSpec(tracker.getCode(),
                tracker.getName(), tracker.getColor(), tracker.isWaterproof()));
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        return data.deleteTracker(tracker.getId()).thenApply(ok -> true);
    }

    /** Удалить трекер без выдачи предмета (админ, потерянный трекер). */
    public CompletableFuture<Boolean> remove(Tracker tracker) {
        if (tracker == null) {
            return CompletableFuture.completedFuture(false);
        }
        unwatchAll(tracker.getId());
        stationaryNotified.remove(tracker.getId());
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

    /** Трекеры в этом мире в радиусе, ближние первыми. */
    public List<Nearby> scan(Player player, double radius) {
        List<Nearby> out = new ArrayList<>();
        if (player == null || player.getWorld() == null) {
            return out;
        }
        Location origin = player.getLocation();
        for (Tracker tracker : trackers.values()) {
            if (tracker.getWorld() == null || !tracker.getWorld().equals(player.getWorld().getName())) {
                continue;
            }
            Location location = tracker.getLocation();
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

    /** Привязка к сущности пропала (смерть): сигнал теряется, координаты остаются. */
    public void onEntityLost(UUID entityId) {
        if (entityId == null) {
            return;
        }
        for (Tracker tracker : trackers.values()) {
            if (entityId.equals(tracker.getEntityUuid())) {
                tracker.setEntityUuid(null);
                tracker.setSignal(false);
                dirty.add(tracker.getId());
                Player owner = tracker.getOwnerUuid() == null ? null
                        : Bukkit.getPlayer(tracker.getOwnerUuid());
                if (owner != null && owner.isOnline()) {
                    owner.sendMessage(plugin.getMessages().getPrefixed("itrak-signal-lost",
                            "name", tracker.getDisplayName()));
                }
            }
        }
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
