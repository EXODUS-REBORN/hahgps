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
import ru.hahgps.model.BodyPart;
import ru.hahgps.model.Tracker;
import ru.hahgps.model.TrackerColor;
import ru.hahgps.model.TrackerShare;
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
    /**
     * С какого момента трекер мокнет под дождём. Живёт только в памяти:
     * после рестарта счётчик начинается заново, иначе трекер «промокал» бы
     * мгновенно сразу после включения сервера в дождь.
     */
    private final Map<Integer, Long> wetSince = new ConcurrentHashMap<>();
    private final Set<Integer> dirty = ConcurrentHashMap.newKeySet();
    /** Когда последний раз писали в лог про ошибку трекера (не чаще раза в минуту). */
    private final Map<Integer, Long> tickFailureLogged = new ConcurrentHashMap<>();
    /** Коды, которые прямо сейчас вставляются в БД: двойной клик не создаст два трекера. */
    private final Set<String> placingCodes = ConcurrentHashMap.newKeySet();
    /** С какого момента привязанная сущность не находится (для снятия «висящих» трекеров). */
    private final Map<Integer, Long> lostSince = new ConcurrentHashMap<>();
    /**
     * Кому владелец открыл каждый трекер: {@code trackerId -> (uuid -> ник)}.
     * Живёт только в памяти и грузится из таблицы {@code tracker_shares} при
     * старте: доступ — это право, а не часть предмета.
     */
    private final Map<Integer, Map<UUID, String>> shares = new ConcurrentHashMap<>();
    /** Трекеры, которые прямо сейчас слетают с умершей сущности: один дроп. */
    private final Set<Integer> dropping = ConcurrentHashMap.newKeySet();
    /**
     * Загруженные трекеры, которым ещё не восстановили производные поля (ник и
     * тип носителя, вода под маркером). Разбираются по {@link #HYDRATE_BATCH}
     * за тик: обращения к профилям игроков и блокам не должны съедать один
     * длинный кусок главного потока сразу после включения плагина.
     */
    private final List<Tracker> pendingHydration =
            java.util.Collections.synchronizedList(new ArrayList<>());

    /** Сколько трекеров доразбирать за один тик. */
    private static final int HYDRATE_BATCH = 4;
    /** Подсветка трекеров конкретному игроку: локатор или трекер показал координаты. */
    private final Map<UUID, List<Highlight>> highlights = new ConcurrentHashMap<>();

    private record Highlight(int trackerId, long until) {
    }

    private volatile BukkitTask task;
    private volatile long lastSaveAt;
    /** Летящий брошенный трекер: физика у снаряда, вид — у предмета. */
    private final ThrownTrackerVisuals thrownVisuals;

    public TrackerService(HahGpsPlugin plugin, DataService data) {
        this.plugin = plugin;
        this.data = data;
        this.thrownVisuals = new ThrownTrackerVisuals(plugin);
    }

    // ------------------------------------------------------------------
    // Жизненный цикл
    // ------------------------------------------------------------------

    /** Загрузка из БД (главный поток). */
    public void load(List<Tracker> loaded) {
        load(loaded, Map.of());
    }

    /** Загрузка из БД вместе с доступами к трекерам (главный поток). */
    public void load(List<Tracker> loaded, Map<Integer, List<TrackerShare>> loadedShares) {
        trackers.clear();
        lostSince.clear();
        placingCodes.clear();
        lastKnown.clear();
        stationaryNotified.clear();
        shares.clear();
        dropping.clear();
        pendingHydration.clear();
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
                // Ник носителя, признак «носитель — игрок» и флаг воды требуют
                // обращений к профилям игроков и к блокам: на десятках трекеров
                // это заметный кусок тика. Раскладываем на порции (hydrate),
                // чтобы включение плагина не съедало MSPT одним куском.
                pendingHydration.add(tracker);
                trackers.put(tracker.getId(), tracker);
                // После рестарта не спамим «трекер неподвижен N» по всем
                // старым маячкам: уведомление — это событие перехода в
                // неподвижность, а не факт загрузки. Время неподвижности
                // при этом считается по moved_at и включает простой сервера.
                // Загруженный трекер считается «уже известным»; после первого
                // реального движения отметка снимется и следующая остановка
                // снова даст одно уведомление.
                stationaryNotified.add(tracker.getId());
            }
        }
        if (loadedShares != null) {
            for (Map.Entry<Integer, List<TrackerShare>> entry : loadedShares.entrySet()) {
                // Доступ к уже удалённому трекеру не воскрешаем: после рестарта
                // такие строки просто игнорируются (и перезаписываются при
                // следующем удалении трекера).
                if (!trackers.containsKey(entry.getKey()) || entry.getValue() == null) {
                    continue;
                }
                Map<UUID, String> players = new ConcurrentHashMap<>();
                for (TrackerShare share : entry.getValue()) {
                    if (share != null && share.playerId() != null) {
                        players.put(share.playerId(), share.playerName() == null ? "" : share.playerName());
                    }
                }
                if (!players.isEmpty()) {
                    shares.put(entry.getKey(), players);
                }
            }
        }
        plugin.getLogger().info("HahGps: загружено трекеров iTrak — " + trackers.size());
    }

    /**
     * Досборка загруженных трекеров порциями по {@link #HYDRATE_BATCH}.
     *
     * Сразу после старта всё, что требует обращений к профилям игроков и к
     * блокам мира (ник носителя, тип носителя, вода под маркером), лучше не
     * делать: на десятках трекеров это один длинный кусок главного потока.
     * Каждый тик доразбираем небольшую порцию, пока очередь не кончится.
     */
    private void hydrate(int budget) {
        if (pendingHydration.isEmpty() || budget <= 0) {
            return;
        }
        java.util.Iterator<Tracker> iterator = pendingHydration.iterator();
        for (int done = 0; done < budget && iterator.hasNext(); done++) {
            Tracker tracker = iterator.next();
            iterator.remove();
            if (!trackers.containsKey(tracker.getId())) {
                continue;
            }
            try {
                if (tracker.isAttached()) {
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
            } catch (Throwable t) {
                logTickFailure(tracker, t);
            }
        }
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
        thrownVisuals.dispose();
        pendingHydration.clear();
        // Досохраняем только сдвинувшееся: остальное уже лежит в БД, а полная
        // перезапись всех трекеров не влезала в две секунды shutdown пула.
        List<Tracker> toSave = new ArrayList<>(dirty.size());
        for (Integer id : dirty) {
            Tracker tracked = trackers.get(id);
            if (tracked != null) {
                toSave.add(snapshotOf(tracked));
            }
        }
        dirty.clear();
        for (Tracker snapshot : toSave) {
            try {
                data.updateTracker(snapshot);
            } catch (Throwable ignored) {
            }
        }
        watchedByPlayer.clear();
        lastWatchMessage.clear();
        scanCooldownEnd.clear();
        scanAttempts.clear();
        highlights.clear();
        lastKnown.clear();
        stationaryNotified.clear();
        wetSince.clear();
        dirty.clear();
        lostSince.clear();
        placingCodes.clear();
        shares.clear();
        dropping.clear();
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
        ConfigManager config = plugin.getConfigManager();
        if (!config.isItrakEnabled()) {
            return;
        }
        // Сначала небольшая порция трекеров, которым не восстановили поля при
        // загрузке: ника и типа носителя у них ещё нет, но это не мешает.
        hydrate(HYDRATE_BATCH);
        // Все настройки читаются один раз на тик, а не на каждый трекер:
        // каждый getX() — это разбор пути и обход YAML.
        long now = System.currentTimeMillis();
        long stationaryMillis = config.getItrakStationaryMillis();
        boolean stationaryNotify = config.isItrakStationaryNotify();
        boolean waterCurrent = config.isItrakWaterCurrent();
        int intervalTicks = config.getItrakUpdateInterval();
        double driftSpeed = config.getItrakWaterDriftSpeed();
        double fallSpeed = config.getItrakWaterFallSpeed();
        long rainMillis = config.getItrakRainRuinMillis();

        for (Tracker tracker : trackers.values()) {
            // Промокший трекер замер: ни за носителем не ходит, ни по воде не
            // плывёт, ни сохнет. Его место — последнее, где был сигнал.
            if (tracker.isRuined()) {
                continue;
            }
            try {
                updateFromEntity(tracker, now);
                updateWater(tracker, now, waterCurrent, intervalTicks, driftSpeed, fallSpeed);
                updateRain(tracker, now, rainMillis);
                if (stationaryMillis > 0 && tracker.isStationary(now, stationaryMillis)) {
                    if (stationaryNotified.add(tracker.getId()) && stationaryNotify) {
                        notifyStationary(tracker, now);
                    }
                } else {
                    stationaryNotified.remove(tracker.getId());
                }
            } catch (Throwable t) {
                // Один сломанный трекер не должен ронять весь тик: иначе
                // сохранение, слежение и сообщения стоят до перезапуска.
                logTickFailure(tracker, t);
            }
        }

        try {
            if (config.isItrakParticles()) {
                spawnParticles();
            }
            spawnHighlights(now);
            refreshSignalSnapshots();
            pushWatchMessages(now);
            saveDirty(now);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "HahGps: пост-обработка тика iTrak упала", t);
        }
    }

    /** Ошибка одного трекера: пишем не чаще раза в минуту, чтобы не залить лог. */
    private void logTickFailure(Tracker tracker, Throwable error) {
        long now = System.currentTimeMillis();
        Long last = tickFailureLogged.get(tracker.getId());
        if (last != null && now - last < 60_000L) {
            return;
        }
        tickFailureLogged.put(tracker.getId(), now);
        plugin.getLogger().log(Level.WARNING,
                "HahGps: тик трекера " + tracker.getCode() + " упал", error);
    }

    /** Кому сообщать о событиях трекера: владелец и все следящие. */
    private Set<UUID> recipientsOf(Tracker tracker) {
        Set<UUID> recipients = new LinkedHashSet<>();
        if (tracker.getOwnerUuid() != null) {
            recipients.add(tracker.getOwnerUuid());
        }
        Map<UUID, String> shared = shares.get(tracker.getId());
        if (shared != null) {
            recipients.addAll(shared.keySet());
        }
        for (Map.Entry<UUID, Set<Integer>> entry : watchedByPlayer.entrySet()) {
            if (entry.getValue().contains(tracker.getId())) {
                recipients.add(entry.getKey());
            }
        }
        return recipients;
    }

    /** Подсветка найденных трекеров: цветные частицы вокруг них. */
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
                Particle.DustOptions dust = dustFor(tracker.getColor(), 1.1f);
                double x = location.getX();
                double y = location.getY();
                double z = location.getZ();
                player.spawnParticle(Particle.DUST, x, y + 0.7, z,
                        24, 0.9, 0.9, 0.9, 0, dust);
                player.spawnParticle(Particle.END_ROD, x, y + 1.1, z,
                        4, 0.25, 0.35, 0.25, 0.01);
            }
        }
    }

    /** Показать игроку трекер на несколько секунд (после сканирования). */
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
    private void updateWater(Tracker tracker, long now, boolean waterCurrent,
                             int intervalTicks, double driftSpeed, double fallSpeed) {
        if (tracker.isAttached() || tracker.isSunk() || !tracker.isWater() || !waterCurrent) {
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
        double dt = intervalTicks / 20.0;
        WaterPhysics.Pose pose = WaterPhysics.floatStep(tracker.getX(), tracker.getY(), tracker.getZ(),
                new WorldTerrain(world), dt, driftSpeed, fallSpeed);
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

    // ------------------------------------------------------------------
    // Дождь: намокание обычного трекера
    // ------------------------------------------------------------------

    /**
     * Обычный трекер под открытым небом в дождь мокнет и через
     * {@code itrak.rain-ruin-seconds} теряет сигнал навсегда (до снятия).
     * Водонепроницаемый не мокнет вообще, под крышей и в воде счётчик
     * сбрасывается — как только дождь кончился, трекер снова сухой.
     */
    private void updateRain(Tracker tracker, long now, long limit) {
        if (limit <= 0 || tracker.isWaterproof() || !tracker.hasSignal()) {
            wetSince.remove(tracker.getId());
            return;
        }
        if (!isExposedToRain(tracker)) {
            wetSince.remove(tracker.getId());
            return;
        }
        long since = wetSince.computeIfAbsent(tracker.getId(), id -> now);
        if (now - since < limit) {
            return;
        }
        ruinTracker(tracker, now);
    }

    /** Мокнет ли трекер: гроза в мире и над ним открытое небо. */
    private boolean isExposedToRain(Tracker tracker) {
        // Дешёвая проверка первой: без грозы незачем даже искать носителя.
        World world = tracker.getWorld() == null ? null : Bukkit.getWorld(tracker.getWorld());
        if (world == null || !world.hasStorm()) {
            return false;
        }
        Location at = physicalLocation(tracker);
        if (at == null || at.getWorld() == null) {
            return false;
        }
        world = at.getWorld();
        if (!world.hasStorm()) {
            return false;
        }
        int x = floor(at.getX());
        int z = floor(at.getZ());
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return false;
        }
        // Крыша и листва над трекером закрывают дождь так же, как игрока:
        // выше трекера есть непустой блок — значит, он под укрытием.
        return at.getY() >= world.getHighestBlockYAt(x, z);
    }

    /**
     * Где трекер физически: у привязанного к существу — у существа прямо
     * сейчас, даже если сигнал потерян. Нужно для «снять»: промокший трекер
     * на игроке снимается там, где носитель стоит, а не там, где замолчал.
     */
    public Location physicalLocation(Tracker tracker) {
        if (tracker == null) {
            return null;
        }
        UUID entityId = tracker.getEntityUuid();
        if (entityId != null) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null && entity.isValid() && !entity.isDead()) {
                return entity.getLocation();
            }
        }
        return tracker.getLocation();
    }

    /** Промок окончательно: сигнал пропадает, точка замирает, владельцу — сообщение. */
    private void ruinTracker(Tracker tracker, long now) {
        if (tracker.isRuined()) {
            return;
        }
        // Последняя точка — живая точка носителя: иначе «последним местом»
        // окажется место, где трекер сохранялся в БД.
        Location last = physicalLocation(tracker);
        if (last != null) {
            tracker.moveTo(last.getX(), last.getY(), last.getZ(), now);
        }
        tracker.ruin(now);
        wetSince.remove(tracker.getId());
        stationaryNotified.remove(tracker.getId());
        dirty.add(tracker.getId());
        data.updateTracker(snapshotOf(tracker)).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "HahGps: не удалось сохранить намокший трекер "
                    + tracker.getCode(), error);
            dirty.add(tracker.getId());
            return false;
        });
        MessagesManager messages = plugin.getMessages();
        for (UUID id : recipientsOf(tracker)) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            player.sendMessage(trackerLine("itrak-ruined", tracker,
                    "world", tracker.getWorld() == null ? "?" : tracker.getWorld(),
                    "x", formatCoord(tracker.getX()),
                    "y", formatCoord(tracker.getY()),
                    "z", formatCoord(tracker.getZ())));
            // Слежение за промокшим трекером бессмысленно: живых координат
            // больше не будет, дальше только последняя отметка.
            Set<Integer> watched = watchedByPlayer.get(id);
            if (watched != null) {
                watched.remove(tracker.getId());
            }
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

        // Снимок игроков один раз на тик: мир и координаты в «сыром» виде.
        // Раньше на каждую пару «трекер × игрок» создавался Location, а на
        // каждую отправку — ещё и клон позиции.
        Map<String, List<Viewer>> viewers = new java.util.HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location at = player.getLocation();
            World world = at.getWorld();
            if (world == null) {
                continue;
            }
            viewers.computeIfAbsent(world.getName(), key -> new ArrayList<>(4))
                    .add(new Viewer(player, at.getX(), at.getY(), at.getZ()));
        }
        if (viewers.isEmpty()) {
            return;
        }

        for (Tracker tracker : trackers.values()) {
            String worldName = tracker.getWorld();
            if (worldName == null) {
                continue;
            }
            List<Viewer> here = viewers.get(worldName);
            if (here == null || here.isEmpty()) {
                continue;
            }
            World world = Bukkit.getWorld(worldName);
            double x = tracker.getX();
            double y = tracker.getY() + 0.65;
            double z = tracker.getZ();
            if (world == null || !world.isChunkLoaded(floor(x) >> 4, floor(z) >> 4)) {
                continue;
            }
            UUID owner = tracker.getOwnerUuid();
            Particle.DustOptions dust = null;
            for (Viewer viewer : here) {
                if (ownerOnly && (owner == null || !owner.equals(viewer.player().getUniqueId()))) {
                    continue;
                }
                double dx = viewer.x() - x;
                double dy = viewer.y() - y;
                double dz = viewer.z() - z;
                if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                    continue;
                }
                if (dust == null) {
                    float size = tracker.hasSignal() ? 0.9f : 0.45f;
                    dust = dustFor(tracker.getColor(), size);
                }
                viewer.player().spawnParticle(Particle.DUST, x, y, z, 3,
                        0.18, 0.18, 0.18, 0, dust);
            }
        }
    }

    /** Игрок и его координаты на момент тика — без живого Location. */
    private record Viewer(Player player, double x, double y, double z) {
    }

    /** Пылинки маркера кэшируются: один и тот же цвет/размер не создаётся заново. */
    private final Map<String, Particle.DustOptions> dustCache = new ConcurrentHashMap<>();

    private Particle.DustOptions dustFor(TrackerColor color, float size) {
        String key = color.name() + '|' + size;
        return dustCache.computeIfAbsent(key, ignored ->
                new Particle.DustOptions(color.bukkitColor(), size));
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
        tracker.setBodyPart(null);
        tracker.setSignal(false);
        dirty.add(tracker.getId());
        Player owner = tracker.getOwnerUuid() == null ? null : Bukkit.getPlayer(tracker.getOwnerUuid());
        if (owner != null && owner.isOnline()) {
            owner.sendMessage(trackerLine("itrak-detached", tracker,
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
                if (tracker.isRuined()) {
                    // Живых координат больше не будет: сообщаем последнее
                    // место и прекращаем слежение, чтобы не спамить.
                    player.sendMessage(plugin.getMessages().getPrefixed("itrak-watch-ruined",
                            "name", tracker.getDisplayName(),
                            "world", tracker.getWorld() == null ? "?" : tracker.getWorld(),
                            "x", formatCoord(tracker.getX()),
                            "y", formatCoord(tracker.getY()),
                            "z", formatCoord(tracker.getZ())));
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
        // Снимки собираем пачкой и пишем одной транзакцией: раньше каждый
        // изменившийся трекер шёл отдельным UPDATE и отдельной задачей в пул БД.
        List<Tracker> snapshots = new ArrayList<>(dirty.size());
        for (Integer id : new ArrayList<>(dirty)) {
            Tracker live = trackers.get(id);
            if (live == null) {
                dirty.remove(id);
                continue;
            }
            // В БД уходит снимок значений: тик продолжает двигать живой объект,
            // и поток записи иначе мог прочитать новый x со старым y.
            snapshots.add(snapshotOf(live));
            dirty.remove(id);
        }
        if (snapshots.isEmpty()) {
            return;
        }
        data.updateTrackers(snapshots).whenComplete((saved, error) -> {
            if (error == null) {
                return;
            }
            // Запись не прошла — вернём трекеры в очередь, иначе позиция
            // потеряется до следующего движения.
            for (Tracker snapshot : snapshots) {
                dirty.add(snapshot.getId());
            }
            plugin.getLogger().log(Level.WARNING, "HahGps: не удалось сохранить трекеры iTrak ("
                    + snapshots.size() + ")", error);
        });
    }

    /** Неизменяемый снимок состояния трекера для фоновой записи в БД. */
    private static Tracker snapshotOf(Tracker tracker) {
        Tracker copy = new Tracker(tracker.getId(), tracker.getCode(), tracker.getName(),
                tracker.getColor(), tracker.isWaterproof(), tracker.getOwnerUuid(),
                tracker.getOwnerName(), tracker.getWorld(), tracker.getX(), tracker.getY(),
                tracker.getZ(), tracker.getEntityUuid(), tracker.isSunk(), tracker.getPlacedAt(),
                tracker.getMovedAt(), tracker.getUpdatedAt(), tracker.getSupportBlock());
        // Промокание — тоже состояние: без него после рестарта трекер
        // «оживал» бы с сигналом.
        copy.setRuined(tracker.isRuined(), tracker.getRuinedAt());
        copy.setBodyPart(tracker.getBodyPart());
        return copy;
    }

    private void notifyStationary(Tracker tracker, long now) {
        MessagesManager messages = plugin.getMessages();
        for (UUID id : recipientsOf(tracker)) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            player.sendMessage(trackerLine("itrak-stationary", tracker,
                    "time", formatDuration(tracker.stationaryMillis(now))));
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
        return place(player, spec, clicked, face, target, sinkRequested, null);
    }

    /**
     * Установка с частью тела носителя (куда игрок попал трекером).
     *
     * @param bodyPart ключ {@link BodyPart}; null — трекер на блоке или часть
     *                 не определилась
     */
    public CompletableFuture<PlaceOutcome> place(Player player, ItemFactory.TrackerSpec spec,
                                                 Block clicked, BlockFace face, Entity target,
                                                 boolean sinkRequested, String bodyPart) {
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
        candidate.setBodyPart(target == null ? null : bodyPart);

        return data.createTracker(candidate)
                .whenComplete((created, error) -> placingCodes.remove(spec.code()))
                .thenApply(created -> {
                    if (created == null) {
                        return new PlaceOutcome(null, PlaceResult.FAILED);
                    }
                    // Публикуем состояние в главном потоке: created приходит из
                    // пула БД, а tick() читает поля трекера без синхронизации.
                    runMain(() -> {
                        created.setWater(floating);
                        TrackerCodes.reserve(created.getCode());
                        trackers.put(created.getId(), created);
                    });
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
        Location location = physicalLocation(tracker);
        if (location == null) {
            return false;
        }
        double distance = LocationUtil.distance(player.getLocation(), location);
        return distance <= plugin.getConfigManager().getItrakTakeRadius();
    }

    /**
     * Подобрать трекер: удалить из мира и вернуть предмет владельцу.
     *
     * Порядок важен: сначала запись уходит из БД, и только при успехе трекер
     * исчезает из памяти и игрок получает предмет. Иначе сбой БД оставлял
     * трекер в таблице, и после рестарта он «воскресал» вместе с предметом.
     */
    public CompletableFuture<Boolean> take(Player player, Tracker tracker) {
        if (tracker == null) {
            return CompletableFuture.completedFuture(false);
        }
        return data.deleteTracker(tracker.getId()).thenApply(ok -> {
            if (!ok) {
                return false;
            }
            runMain(() -> {
                forgetTracker(tracker);
                ItemStack item = ItemFactory.trackerItem(new ItemFactory.TrackerSpec(tracker.getCode(),
                        tracker.getName(), tracker.getColor(), tracker.isWaterproof()));
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                for (ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            });
            return true;
        });
    }

    /** Снять трекер и выронить предмет: сломали блок, на котором он держался. */
    public CompletableFuture<Boolean> removeDropping(Tracker tracker, Location dropAt) {
        if (tracker == null) {
            return CompletableFuture.completedFuture(false);
        }
        return data.deleteTracker(tracker.getId()).thenApply(ok -> {
            if (!ok) {
                return false;
            }
            runMain(() -> {
                forgetTracker(tracker);
                ItemStack item = ItemFactory.trackerItem(new ItemFactory.TrackerSpec(tracker.getCode(),
                        tracker.getName(), tracker.getColor(), tracker.isWaterproof()));
                Location where = dropAt != null ? dropAt : tracker.getLocation();
                if (where != null && where.getWorld() != null) {
                    where.getWorld().dropItemNaturally(where, item);
                }
            });
            return true;
        });
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
        return data.deleteTracker(tracker.getId()).thenApply(ok -> {
            if (!ok) {
                return false;
            }
            runMain(() -> forgetTracker(tracker));
            return true;
        });
    }

    /** Снять трекер со всех подписок и убрать из памяти (только главный поток). */
    private void forgetTracker(Tracker tracker) {
        if (tracker != null) {
            shares.remove(tracker.getId());
            dropping.remove(tracker.getId());
            wetSince.remove(tracker.getId());
            tickFailureLogged.remove(tracker.getId());
            for (Map<Integer, LastKnown> known : lastKnown.values()) {
                known.remove(tracker.getId());
            }
        }
        unwatchAll(tracker.getId());
        stationaryNotified.remove(tracker.getId());
        lostSince.remove(tracker.getId());
        clearHighlights(tracker.getId());
        trackers.remove(tracker.getId());
    }

    /** Выполнить действие в главном потоке: предметы игроку и карты — только там. */
    private void runMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
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

    public int countOwned(UUID owner) {
        int count = 0;
        for (Tracker tracker : trackers.values()) {
            if (owner != null && owner.equals(tracker.getOwnerUuid())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Может ли игрок распоряжаться трекером: владелец, админ или тот, кому
     * владелец открыл доступ. Доступ даёт слежение, маршрут, смену имени и
     * цвета — но не право выдавать доступ дальше.
     */
    public boolean canManage(Player player, Tracker tracker) {
        if (player == null || tracker == null) {
            return false;
        }
        if (player.hasPermission("gps.itrak.admin")) {
            return true;
        }
        if (tracker.getOwnerUuid() != null && tracker.getOwnerUuid().equals(player.getUniqueId())) {
            return true;
        }
        return isSharedWith(tracker, player.getUniqueId());
    }

    /** Добавлять и отзывать доступ может только владелец (и админ). */
    public boolean canShare(Player player, Tracker tracker) {
        return player != null && tracker != null
                && (player.hasPermission("gps.itrak.admin")
                || (tracker.getOwnerUuid() != null && tracker.getOwnerUuid().equals(player.getUniqueId())));
    }

    public boolean isSharedWith(Tracker tracker, UUID playerId) {
        if (tracker == null || playerId == null) {
            return false;
        }
        Map<UUID, String> shared = shares.get(tracker.getId());
        return shared != null && shared.containsKey(playerId);
    }

    /** Все, кому открыт доступ, в порядке добавления. */
    public List<TrackerShare> shareList(Tracker tracker) {
        if (tracker == null) {
            return List.of();
        }
        Map<UUID, String> shared = shares.get(tracker.getId());
        if (shared == null || shared.isEmpty()) {
            return List.of();
        }
        List<TrackerShare> out = new ArrayList<>(shared.size());
        for (Map.Entry<UUID, String> entry : shared.entrySet()) {
            out.add(new TrackerShare(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    /**
     * Трекеры, которые игрок видит в своём списке: свои и те, к которым ему
     * открыли доступ. Админ видит все.
     */
    public List<Tracker> accessible(Player player) {
        if (player == null) {
            return List.of();
        }
        if (player.hasPermission("gps.itrak.admin")) {
            return all();
        }
        List<Tracker> out = new ArrayList<>();
        for (Tracker tracker : trackers.values()) {
            if (player.getUniqueId().equals(tracker.getOwnerUuid())
                    || isSharedWith(tracker, player.getUniqueId())) {
                out.add(tracker);
            }
        }
        out.sort(Comparator.comparing(Tracker::getCode));
        return out;
    }

    /**
     * Открыть доступ к трекеру. Строка сначала пишется в БД и только потом
     * появляется в памяти: доступ, которого нет в базе, пропал бы после
     * рестарта молча.
     */
    public CompletableFuture<Boolean> share(Tracker tracker, UUID by, UUID targetId, String targetName) {
        if (tracker == null || targetId == null || by == null) {
            return CompletableFuture.completedFuture(false);
        }
        String name = targetName == null ? "" : targetName;
        return data.addTrackerShare(tracker.getId(), targetId, name, by).thenApply(ok -> {
            if (Boolean.TRUE.equals(ok)) {
                runMain(() -> shares.computeIfAbsent(tracker.getId(), key -> new ConcurrentHashMap<>())
                        .put(targetId, name));
                Player target = Bukkit.getPlayer(targetId);
                if (target != null && target.isOnline()) {
                    target.sendMessage(plugin.getMessages().getPrefixed("itrak-share-received",
                            "name", tracker.getDisplayName()));
                }
            }
            return Boolean.TRUE.equals(ok);
        });
    }

    /** Отозвать доступ. */
    public CompletableFuture<Boolean> unshare(Tracker tracker, UUID targetId) {
        if (tracker == null || targetId == null) {
            return CompletableFuture.completedFuture(false);
        }
        Map<UUID, String> shared = shares.get(tracker.getId());
        if (shared == null || !shared.containsKey(targetId)) {
            return CompletableFuture.completedFuture(false);
        }
        return data.removeTrackerShare(tracker.getId(), targetId).thenApply(ok -> {
            if (Boolean.TRUE.equals(ok)) {
                runMain(() -> {
                    Map<UUID, String> current = shares.get(tracker.getId());
                    if (current != null) {
                        current.remove(targetId);
                        if (current.isEmpty()) {
                            shares.remove(tracker.getId(), current);
                        }
                    }
                });
            }
            return Boolean.TRUE.equals(ok);
        });
    }

    /**
     * Сменить цвет установленного трекера. Цвет — часть записи (модель,
     * частицы, подписи), поэтому пишем сразу, а не ждём периодического
     * сохранения.
     */
    public boolean recolor(Tracker tracker, TrackerColor color) {
        if (tracker == null || color == null || tracker.getColor() == color) {
            return false;
        }
        tracker.setColor(color);
        dirty.add(tracker.getId());
        Tracker snapshot = snapshotOf(tracker);
        data.updateTracker(snapshot).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "HahGps: не удалось сохранить цвет трекера "
                    + tracker.getCode(), error);
            dirty.add(tracker.getId());
            return false;
        });
        return true;
    }

    // ------------------------------------------------------------------
    // Название трекера
    // ------------------------------------------------------------------

    /** Сколько символов допускается в названии трекера (как и в подписях GUI). */
    public static final int NAME_LIMIT = 30;

    /**
     * Название трекера в том виде, в каком оно попадёт в БД и в подписи.
     * MiniMessage-теги вырезаются: имя приходит от игрока и не должно
     * раскрашивать меню и чат. Переводы строк, управляющие символы и
     * двойные пробелы схлопываются, длина ограничена {@link #NAME_LIMIT}.
     */
    public static String sanitizeName(String raw) {
        if (raw == null) {
            return "";
        }
        String plain = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().stripTags(raw);
        // Легаси-код цвета (§c) и управляющие символы в имени не нужны: в чате
        // и меню они не рендерятся, а буква кода осталась бы в названии.
        plain = plain.replaceAll("(?i)§[0-9a-fk-or]", "").replace('§', ' ')
                .replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (plain.codePointCount(0, plain.length()) > NAME_LIMIT) {
            plain = plain.substring(0, plain.offsetByCodePoints(0, NAME_LIMIT)).trim();
        }
        return plain;
    }

    /**
     * Переименовать установленный трекер. Пишем в БД сразу: имя не должно
     * теряться, если сервер уйдёт в рестарт раньше периодического сохранения.
     *
     * @return true, если название действительно изменилось
     */
    public boolean rename(Tracker tracker, String rawName) {
        if (tracker == null) {
            return false;
        }
        String name = sanitizeName(rawName);
        String current = sanitizeName(tracker.getName());
        if (name.equals(current)) {
            return false;
        }
        tracker.setName(name);
        dirty.add(tracker.getId());
        data.updateTracker(snapshotOf(tracker)).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "HahGps: не удалось сохранить имя трекера "
                    + tracker.getCode(), error);
            dirty.add(tracker.getId());
            return false;
        });
        return true;
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
            // Промокший трекер сигнала не даёт — локатор его не находит.
            // Снять его можно вручную, зная последнее место из меню.
            if (tracker.isRuined()) {
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
        long now = System.currentTimeMillis();
        // Попытки без перезарядки копятся у игроков, которые больше не сканируют.
        if (scanAttempts.size() > 256) {
            scanAttempts.keySet().removeIf(id -> !scanCooldownEnd.containsKey(id)
                    && Bukkit.getPlayer(id) == null);
        }
        if (scanCooldownEnd.size() < 256) {
            return;
        }
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
        if (tracker.isRuined()) {
            // Сигнала нет: показываем только последнее место и время, когда
            // трекер замолчал. Район тут не нужен — координаты и так открыты.
            Location last = tracker.getLocation();
            return new Signal(null, last, tracker.getRuinedAt(),
                    last == null ? null : regionText(last));
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
        if (tracker.isRuined()) {
            // Маршрут к последнему известному месту: трекер никуда не делся,
            // его можно подобрать, но сигнал оттуда уже не идёт.
            return tracker.getLocation();
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
        Map<Integer, LastKnown> known =
                lastKnown.computeIfAbsent(viewer.getUniqueId(), key -> new ConcurrentHashMap<>());
        LastKnown previous = known.get(tracker.getId());
        long now = System.currentTimeMillis();
        // Стоящий трекер не переписывает отметку каждую секунду: пишем, только
        // если он сдвинулся больше чем на блок или прошло пять секунд.
        if (previous != null
                && now - previous.at() < 5_000L
                && Math.abs(previous.x() - live.getX()) < 1.0
                && Math.abs(previous.y() - live.getY()) < 1.0
                && Math.abs(previous.z() - live.getZ()) < 1.0) {
            return;
        }
        known.put(tracker.getId(), new LastKnown(
                live.getWorld() == null ? null : live.getWorld().getName(),
                live.getX(), live.getY(), live.getZ(), now));
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
        // Владельцы: один проход по трекерам.
        for (Tracker tracker : trackers.values()) {
            if (tracker.isRuined() || tracker.getOwnerUuid() == null) {
                continue;
            }
            Player owner = Bukkit.getPlayer(tracker.getOwnerUuid());
            if (owner == null || !owner.isOnline()) {
                continue;
            }
            rememberIfInRange(owner, tracker, range);
        }
        // Следящие: проход по подпискам, а не по трекерам. Раньше на каждый
        // трекер собирался LinkedHashSet из всех следящих — O(трекеры × следящие).
        for (Map.Entry<UUID, Set<Integer>> entry : watchedByPlayer.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }
            for (Integer id : entry.getValue()) {
                Tracker tracker = trackers.get(id);
                if (tracker == null || tracker.isRuined()) {
                    continue;
                }
                if (tracker.getOwnerUuid() != null
                        && tracker.getOwnerUuid().equals(player.getUniqueId())) {
                    continue;
                }
                rememberIfInRange(player, tracker, range);
            }
        }
    }

    private void rememberIfInRange(Player player, Tracker tracker, int range) {
        Location live = tracker.liveLocation();
        if (live == null) {
            return;
        }
        if (!SignalRegion.inRange(LocationUtil.distance(player.getLocation(), live), range)) {
            return;
        }
        rememberLastKnown(player, tracker, live);
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
            // Пустой набор не оставляем: иначе карта подписок растёт, а тик
            // обходит игроков без единой подписки.
            if (ids.isEmpty()) {
                watchedByPlayer.remove(player.getUniqueId(), ids);
            }
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
            // Смерть может прийти двумя событиями (и повторным вызовом из
            // тика): без этой отметки предмет выпадал дважды, а запись
            // оставалась «на зомби». Один трекер — один дроп.
            if (!dropping.add(tracker.getId())) {
                continue;
            }
            // Отвязываем сразу, не дожидаясь удаления строки в БД: иначе
            // несколько секунд трекер числился на мёртвой сущности и его
            // можно было «найти» на трупе.
            tracker.setEntityUuid(null);
            tracker.setBodyPart(null);
            tracker.setSignal(false);
            dirty.add(tracker.getId());
            for (UUID id : recipientsOf(tracker)) {
                Player viewer = Bukkit.getPlayer(id);
                if (viewer != null && viewer.isOnline()) {
                    viewer.sendMessage(trackerLine("itrak-tracker-fell-entity-owner",
                            tracker, "entity", entityName));
                }
            }
            removeDropping(tracker, at).whenComplete((ok, error) -> {
                dropping.remove(tracker.getId());
                if (error != null || !Boolean.TRUE.equals(ok)) {
                    plugin.getLogger().log(Level.WARNING, "HahGps: трекер "
                            + tracker.getCode() + " слетел с сущности, но не удалился из БД", error);
                }
            });
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
            Tracker snapshot = snapshotOf(tracker);
            data.updateTracker(snapshot).exceptionally(error -> {
                plugin.getLogger().log(Level.WARNING, "HahGps: не удалось сохранить трекер "
                        + snapshot.getCode() + " при выходе игрока", error);
                return false;
            });
        }
    }

    // ------------------------------------------------------------------
    // Бросок трекера
    // ------------------------------------------------------------------

    /**
     * Бросить трекер: он летит и цепляется к тому, во что попал (блок,
     * существо или игрок). Предмет расходуется вызывающей стороной.
     *
     * Вид — сам предмет, а не снежок: физику считает скрытый снаряд, а рядом
     * с ним каждый тик стоит {@link ItemDisplay} с тем же трекером.
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
        thrownVisuals.show(snowball, ItemFactory.trackerItem(spec));
        return true;
    }

    /** Снаряд броска закончился (попадание): убрать летящий предмет. */
    public void finishThrow(Projectile projectile) {
        thrownVisuals.finish(projectile);
    }

    /** Попадание брошенного трекера: к существу, к блоку или на землю. */
    public CompletableFuture<PlaceOutcome> placeThrown(Player thrower, ItemFactory.TrackerSpec spec,
                                                       Block hitBlock, BlockFace face,
                                                       Entity hitEntity, Location fallback,
                                                       boolean sink, String bodyPart) {
        if (thrower == null || spec == null) {
            return CompletableFuture.completedFuture(new PlaceOutcome(null, PlaceResult.FAILED));
        }
        boolean attachPlayers = plugin.getConfigManager().isItrakAttachPlayers();
        if (hitEntity != null && hitEntity.isValid() && !hitEntity.isDead()
                && hitEntity.getType() != EntityType.UNKNOWN
                && (!(hitEntity instanceof Player) || attachPlayers)) {
            return place(thrower, spec, hitEntity.getLocation().getBlock(), BlockFace.UP, hitEntity,
                    false, bodyPart);
        }
        if (hitBlock != null) {
            return place(thrower, spec, hitBlock, face == null ? BlockFace.UP : face, null, sink, null);
        }
        Location landing = fallback;
        if (landing != null && landing.getWorld() != null) {
            // Снежок разбивается о воду/растение на высоте поверхности:
            // сам блок попадания может быть воздухом. Берём первый блок под
            // точкой падения — воду или землю, иначе трекер завис бы в воздухе
            // и «не заметил» воду.
            return place(thrower, spec, groundUnder(landing), BlockFace.UP, null, sink, null);
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
        if (tracker.isRuined()) {
            return messages.get("itrak-status-ruined");
        }
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
            case RUINED -> messages.get("itrak-status-ruined");
            case NO_SIGNAL -> messages.get("itrak-status-no-signal");
        };
    }

    /** «в движении» / «неподвижен» — состояние для GUI и сообщений. */
    public String stateText(Tracker tracker, long now) {
        MessagesManager messages = plugin.getMessages();
        if (tracker.isRuined()) {
            return messages.get("itrak-state-ruined");
        }
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

    // ------------------------------------------------------------------
    // Короткие строки про трекер: подробности — в подсказке
    // ------------------------------------------------------------------

    /**
     * Короткая строка про трекер: одна строка в чате вместо «Трекер <имя>
     * установлен …». Всё остальное — по наведению: имя, код, номер, цвет,
     * тип, статус; клик открывает карточку трекера в меню.
     */
    public Component trackerLine(String key, Tracker tracker, String... args) {
        MessagesManager messages = plugin.getMessages();
        String[] full = new String[6 + args.length];
        full[0] = "name";
        full[1] = tracker == null ? "iTrak" : tracker.getDisplayName();
        full[2] = "code";
        full[3] = tracker == null ? "?" : tracker.getCode();
        full[4] = "id";
        full[5] = tracker == null ? "0" : String.valueOf(tracker.getId());
        System.arraycopy(args, 0, full, 6, args.length);
        Component line = messages.getPrefixed(key, full);
        if (tracker == null) {
            return line;
        }
        line = line.hoverEvent(HoverEvent.showText(hoverCard(tracker)));
        if (tracker.getCode() != null && !tracker.getCode().isBlank()) {
            line = line.clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                    "/gps itrak open " + tracker.getCode()));
        }
        return line;
    }

    /**
     * Строка про трекер, которого ещё нет в мире (только что бросили):
     * подсказка с кодом, цветом и типом, без клика — открывать пока нечего.
     */
    public Component specLine(String key, ItemFactory.TrackerSpec spec, String... args) {
        MessagesManager messages = plugin.getMessages();
        if (spec == null) {
            return messages.getPrefixed(key, args);
        }
        String[] full = new String[4 + args.length];
        full[0] = "name";
        full[1] = spec.name() == null || spec.name().isBlank() ? spec.code() : spec.name();
        full[2] = "code";
        full[3] = spec.code();
        System.arraycopy(args, 0, full, 4, args.length);
        Component line = messages.getPrefixed(key, full);
        Component hover = messages.getComponent("itrak-hover-name", "name", full[1])
                .append(Component.newline())
                .append(messages.getComponent("itrak-hover-spec",
                        "code", spec.code(),
                        "color", messages.get(spec.color() == null
                                ? TrackerColor.BLUE.messageKey() : spec.color().messageKey()),
                        "type", messages.get(spec.waterproof()
                                ? "itrak-type-waterproof" : "itrak-type-regular")));
        return line.hoverEvent(HoverEvent.showText(hover));
    }

    /**
     * Короткая подсказка к строке трекера в чате: номер и код, цвет, тип,
     * часть тела и координаты. Больше ничего — ни времени установки, ни
     * статуса: место не должно утекать лишними строками.
     */
    public Component hoverCard(Tracker tracker) {
        MessagesManager messages = plugin.getMessages();
        String color = messages.get(tracker.getColor() == null
                ? TrackerColor.BLUE.messageKey() : tracker.getColor().messageKey());
        String type = messages.get(tracker.isWaterproof()
                ? "itrak-type-waterproof" : "itrak-type-regular");
        Location at = tracker.liveLocation();
        if (at == null) {
            at = tracker.getLocation();
        }
        double x = at == null ? tracker.getX() : at.getX();
        double y = at == null ? tracker.getY() : at.getY();
        double z = at == null ? tracker.getZ() : at.getZ();
        Component card = messages.getComponent("itrak-hover-title",
                        "name", tracker.getDisplayName(), "id", String.valueOf(tracker.getId()))
                .append(Component.newline())
                .append(messages.getComponent("itrak-hover-spec",
                        "code", tracker.getCode(), "color", color, "type", type));
        BodyPart part = tracker.part();
        if (part != null) {
            card = card.append(Component.newline())
                    .append(messages.getComponent("itrak-hover-part",
                            "part", messages.get(part.messageKey())));
        }
        return card.append(Component.newline())
                .append(messages.getComponent("itrak-hover-coords",
                        "x", formatCoord(x), "y", formatCoord(y), "z", formatCoord(z)));
    }

    /** Название части тела трекера или прочерк для блока/транспорта. */
    public String partText(Tracker tracker) {
        BodyPart part = tracker == null ? null : tracker.part();
        if (part == null) {
            return plugin.getMessages().get("itrak-part-none");
        }
        return plugin.getMessages().get(part.messageKey());
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
        return statusText(viewer == null || tracker == null ? null : signal(viewer, tracker), tracker);
    }

    /** То же, но по уже посчитанному сигналу — GUI не спрашивает его трижды за кадр. */
    public String statusText(Signal signal, Tracker tracker) {
        MessagesManager messages = plugin.getMessages();
        if (tracker == null) {
            return "?";
        }
        if (tracker.isRuined()) {
            return messages.get("itrak-status-ruined");
        }
        if (tracker.isSunk() && tracker.isWaterproof()) {
            return messages.get("itrak-status-submerged");
        }
        if (tracker.isCarrierOffline()) {
            return messages.get("itrak-status-offline");
        }
        if (signal != null && !signal.inRange()) {
            return messages.get("itrak-status-out-of-range");
        }
        return switch (tracker.status()) {
            case LIVE -> messages.get("itrak-status-live");
            case SUNK -> messages.get("itrak-status-sunk");
            case RUINED -> messages.get("itrak-status-ruined");
            case NO_SIGNAL -> messages.get("itrak-status-no-signal");
        };
    }

    /** Состояние глазами игрока: вне зоны связи точных данных нет. */
    public String stateText(Player viewer, Tracker tracker, long now) {
        if (tracker != null && tracker.isRuined()) {
            return stateText(tracker, now);
        }
        if (viewer != null && tracker != null && !signal(viewer, tracker).inRange()) {
            return plugin.getMessages().get("itrak-state-unknown");
        }
        return stateText(tracker, now);
    }

    /** То же по уже посчитанному сигналу — без повторного поиска сущности. */
    public String stateText(Signal signal, Tracker tracker, long now) {
        if (tracker != null && tracker.isRuined()) {
            return stateText(tracker, now);
        }
        if (signal != null && !signal.inRange()) {
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
