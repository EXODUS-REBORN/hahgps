package ru.hahgps.service;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.scheduler.BukkitTask;
import ru.hahgps.HahGpsPlugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Кэш карты мира: цвет поверхности по исследованной территории.
 *
 * Карта в руке рисует только загруженные чанки, поэтому панорама упиралась в
 * чёрные дыры там, где игрок уже был, но чанк выгружен. Кэш снимает цвет с
 * каждого загруженного чанка (несколько точек высоты — это дёшево), хранит
 * его картинкой на диске и отдаёт карте как «фолбэк» для незагруженных
 * чанков. Размер ограничен, координаты привязаны к центру и размеру границы
 * мира: без этого кэш разъезжался бы при смене границы.
 */
public class WorldMapCache implements Listener {

    /** Максимум пикселей на сторону: 1024² ≈ 4 МБ в памяти на мир. */
    private static final int MAX_IMAGE_SIZE = 1024;
    private static final int MIN_IMAGE_SIZE = 128;
    private static final int SAVE_INTERVAL_TICKS = 20 * 60;
    private static final long CATCHUP_TICKS_LIMIT = 20L * 60L;
    private static final int CATCHUP_CHUNKS_PER_PASS = 64;
    /** Чанков из очереди загрузки за один проход (раз в секунду). */
    private static final int PENDING_CHUNKS_PER_TICK = 48;
    private static final int PENDING_LIMIT = 4096;
    /** Ниже этого TPS кэш не снимает новые чанки, чтобы не добавлять лага. */
    private static final double MIN_TPS_FOR_SAMPLING = 17.0;

    private final HahGpsPlugin plugin;
    private final Map<String, Layer> layers = new ConcurrentHashMap<>();
    /** С какого чанка продолжать «догон» уже загруженных чанков. */
    private final Map<String, Integer> catchupCursor = new ConcurrentHashMap<>();
    /** Чанки, которые надо снять в кэш (загрузились или ещё не сняты). */
    private final java.util.Queue<PendingChunk> pending = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Set<Long> pendingKeys = ConcurrentHashMap.newKeySet();
    private final ExecutorService io;
    private BukkitTask saveTask;
    private BukkitTask catchupTask;
    private long catchupStartedAt;
    /** Первый полный проход по уже загруженным чанкам выполнен. */
    private volatile boolean catchupDone;

    public WorldMapCache(HahGpsPlugin plugin) {
        this.plugin = plugin;
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "HahGps-MapCache");
            t.setDaemon(true);
            return t;
        });
    }

    /** Один мир: сетка цветов в мировых координатах. */
    private static final class Layer {
        final String world;
        final int bpp;
        final int minX;
        final int minZ;
        final int size;
        final int[] colors;
        volatile boolean dirty;

        Layer(String world, int bpp, int minX, int minZ, int size) {
            this.world = world;
            this.bpp = bpp;
            this.minX = minX;
            this.minZ = minZ;
            this.size = size;
            this.colors = new int[size * size];
        }
    }

    // ------------------------------------------------------------------
    // Жизненный цикл
    // ------------------------------------------------------------------

    public void start() {
        if (!plugin.getConfigManager().isMapWorldCache() || io.isShutdown()) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            layer(world, true);
        }
        catchupStartedAt = System.currentTimeMillis();
        catchupDone = false;
        pending.clear();
        pendingKeys.clear();
        catchupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::catchUp, 40L, 20L);
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty,
                SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS);
    }

    public void stop() {
        BukkitTask save = saveTask;
        saveTask = null;
        if (save != null) {
            save.cancel();
        }
        BukkitTask catchup = catchupTask;
        catchupTask = null;
        if (catchup != null) {
            catchup.cancel();
        }
        pending.clear();
        pendingKeys.clear();
        for (Layer layer : layers.values()) {
            if (layer.dirty) {
                layer.dirty = false;
                io.execute(() -> save(layer));
            }
        }
        io.shutdown();
        try {
            // Не держим главный поток выключением плагина: PNG-кэш небольшой,
            // секунды хватает, остальное допишется на демонах.
            if (!io.awaitTermination(1, TimeUnit.SECONDS)) {
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            io.shutdownNow();
            Thread.currentThread().interrupt();
        }
        layers.clear();
    }

    /** Исследованный чанк попал в память — ставим его в очередь кэша. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.getConfigManager().isMapWorldCache() || !plugin.isEnabled()) {
            return;
        }
        // Сэмплировать прямо в событии нельзя: карта успевала загрузить
        // сотни чанков, и на каждый шёл скан поверхности в главном потоке.
        // Чанк встаёт в очередь, кэш снимает его порцией из своего тика.
        World world = event.getWorld();
        if (world == null) {
            return;
        }
        int x = event.getChunk().getX();
        int z = event.getChunk().getZ();
        long key = chunkKey(world, x, z);
        if (pendingKeys.size() >= PENDING_LIMIT) {
            return;
        }
        if (pendingKeys.add(key)) {
            pending.add(new PendingChunk(world, x, z, key));
        }
    }

    private static long chunkKey(World world, int x, int z) {
        return ((long) world.getName().hashCode() << 42)
                ^ ((long) (x + 0x100000) << 21)
                ^ (z + 0x100000);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (plugin.getConfigManager().isMapWorldCache()) {
            layer(event.getWorld(), true);
        }
    }

    /** Чанк, который ещё не снят в кэш. */
    private record PendingChunk(World world, int x, int z, long key) {
    }

    private void drainPending() {
        if (pending.isEmpty() || !plugin.getConfigManager().isMapWorldCache()) {
            return;
        }
        // Сервер и так под нагрузкой — кэш подождёт следующего тика.
        try {
            if (Bukkit.getTPS()[0] < MIN_TPS_FOR_SAMPLING) {
                return;
            }
        } catch (Throwable ignored) {
        }
        int budget = PENDING_CHUNKS_PER_TICK;
        while (budget-- > 0) {
            PendingChunk ref = pending.poll();
            if (ref == null) {
                return;
            }
            pendingKeys.remove(ref.key());
            if (!ref.world().isChunkLoaded(ref.x(), ref.z())) {
                continue;
            }
            Layer layer = layer(ref.world(), true);
            if (layer == null) {
                continue;
            }
            try {
                sample(ref.world(), layer, ref.world().getChunkAt(ref.x(), ref.z()));
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Чанки, загруженные до регистрации слушателя (перезагрузка плагина,
     * огромный радиус прогрузки), добираем порциями: снимать всё сразу в
     * главном потоке нельзя. Один полный проход, дальше — очередь событий
     * загрузки, иначе кэш переснимал одни и те же чанки каждую секунду.
     */
    private void catchUp() {
        drainPending();
        if (catchupDone) {
            return;
        }
        if (System.currentTimeMillis() - catchupStartedAt > CATCHUP_TICKS_LIMIT * 50L) {
            catchupDone = true;
            return;
        }
        try {
            if (Bukkit.getTPS()[0] < MIN_TPS_FOR_SAMPLING) {
                return;
            }
        } catch (Throwable ignored) {
        }
        int budget = CATCHUP_CHUNKS_PER_PASS;
        boolean anything = false;
        for (World world : Bukkit.getWorlds()) {
            if (budget <= 0) {
                break;
            }
            Layer layer = layer(world, true);
            if (layer == null) {
                continue;
            }
            Chunk[] loaded;
            try {
                loaded = world.getLoadedChunks();
            } catch (Throwable failure) {
                continue;
            }
            if (loaded.length == 0) {
                continue;
            }
            anything = true;
            int start = Math.floorMod(catchupCursor.getOrDefault(world.getName(), 0), loaded.length);
            int count = Math.min(budget, loaded.length - start);
            for (int i = 0; i < count; i++) {
                try {
                    sample(world, layer, loaded[start + i]);
                } catch (Throwable ignored) {
                }
            }
            catchupCursor.put(world.getName(), (start + count) % loaded.length);
            budget -= count;
        }
        // Пока проход не упёрся в бюджет — все загруженные чанки уже сняты.
        if (!anything || budget == CATCHUP_CHUNKS_PER_PASS) {
            catchupDone = true;
            return;
        }
        boolean allWrapped = true;
        for (World world : Bukkit.getWorlds()) {
            int cursor = catchupCursor.getOrDefault(world.getName(), 0);
            if (cursor != 0) {
                allWrapped = false;
                break;
            }
        }
        if (allWrapped && budget > 0) {
            catchupDone = true;
        }
    }

    // ------------------------------------------------------------------
    // Чтение
    // ------------------------------------------------------------------

    /** Цвет поверхности из кэша; 0 — этой точки в кэше нет. */
    public int colorAt(World world, int x, int z) {
        return colorAt(layer(world, false), x, z);
    }

    private static int colorAt(Layer layer, int x, int z) {
        if (layer == null) {
            return 0;
        }
        int px = Math.floorDiv(x - layer.minX, layer.bpp);
        int pz = Math.floorDiv(z - layer.minZ, layer.bpp);
        if (px < 0 || pz < 0 || px >= layer.size || pz >= layer.size) {
            return 0;
        }
        return layer.colors[pz * layer.size + px];
    }

    /**
     * Слой мира, разрешённый один раз на рендер карты.
     *
     * Раньше на каждый тёмный пиксель полотна вызывался {@code colorAt}, а он
     * делал {@code ConcurrentHashMap.get} по имени мира плюс два floorDiv —
     * до 16 384 поисков в главном потоке за рендер. Теперь карта один раз
     * берёт снимок слоя и читает из него напрямую.
     */
    public TerrainRenderer.CachedColors snapshot(World world) {
        Layer layer = layer(world, true);
        return layer == null ? null : (cachedWorld, x, z) -> colorAt(layer, x, z);
    }

    // ------------------------------------------------------------------
    // Слой мира
    // ------------------------------------------------------------------

    private Layer layer(World world, boolean create) {
        if (world == null) {
            return null;
        }
        Layer existing = layers.get(world.getName());
        if (existing != null || !create) {
            return existing;
        }
        synchronized (layers) {
            existing = layers.get(world.getName());
            if (existing != null) {
                return existing;
            }
            Layer created = buildLayer(world);
            if (created == null) {
                return null;
            }
            layers.put(world.getName(), created);
            io.execute(() -> load(created));
            return created;
        }
    }

    private Layer buildLayer(World world) {
        int bpp = plugin.getConfigManager().getMapCacheBlocksPerPixel();
        WorldBorder border = world.getWorldBorder();
        org.bukkit.Location center = border == null ? null : border.getCenter();
        double centerX = center == null ? 0.0 : center.getX();
        double centerZ = center == null ? 0.0 : center.getZ();
        double borderSize = border == null ? 0.0 : border.getSize();
        if (!Double.isFinite(borderSize) || borderSize <= 0.0) {
            borderSize = MIN_IMAGE_SIZE * (double) bpp;
        }
        int size = (int) Math.ceil(borderSize / bpp);
        size = Math.max(MIN_IMAGE_SIZE, Math.min(MAX_IMAGE_SIZE, size));
        int minX = (int) Math.floor(centerX - (size * (double) bpp) / 2.0);
        int minZ = (int) Math.floor(centerZ - (size * (double) bpp) / 2.0);
        return new Layer(world.getName(), bpp, minX, minZ, size);
    }

    private void sample(World world, Layer layer, Chunk chunk) {
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        int px0 = Math.floorDiv(baseX - layer.minX, layer.bpp);
        int pz0 = Math.floorDiv(baseZ - layer.minZ, layer.bpp);
        int px1 = Math.floorDiv(baseX + 15 - layer.minX, layer.bpp);
        int pz1 = Math.floorDiv(baseZ + 15 - layer.minZ, layer.bpp);
        boolean changed = false;
        for (int pz = pz0; pz <= pz1; pz++) {
            if (pz < 0 || pz >= layer.size) {
                continue;
            }
            for (int px = px0; px <= px1; px++) {
                if (px < 0 || px >= layer.size) {
                    continue;
                }
                int wx = layer.minX + px * layer.bpp + layer.bpp / 2;
                int wz = layer.minZ + pz * layer.bpp + layer.bpp / 2;
                int color = TerrainRenderer.surfaceColor(world, wx, wz);
                if (color == 0) {
                    continue;
                }
                int value = color | 0xFF000000;
                int index = pz * layer.size + px;
                if (layer.colors[index] != value) {
                    layer.colors[index] = value;
                    changed = true;
                }
            }
        }
        if (changed) {
            layer.dirty = true;
        }
    }

    // ------------------------------------------------------------------
    // Диск
    // ------------------------------------------------------------------

    private void saveIfDirty() {
        for (Layer layer : layers.values()) {
            if (!layer.dirty) {
                continue;
            }
            layer.dirty = false;
            io.execute(() -> save(layer));
        }
    }

    private void save(Layer layer) {
        try {
            File dir = new File(plugin.getDataFolder(), "maps");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            int[] copy = layer.colors.clone();
            BufferedImage image = new BufferedImage(layer.size, layer.size, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, layer.size, layer.size, copy, 0, layer.size);
            File target = pngFile(layer.world);
            // Пишем в временный файл и переносим: прерванная запись не должна
            // оставить битый PNG вместо рабочего кэша.
            File temp = new File(target.getParentFile(), target.getName() + ".tmp");
            ImageIO.write(image, "png", temp);
            Files.move(temp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(metaFile(layer.world).toPath(),
                    layer.minX + " " + layer.minZ + " " + layer.bpp + " " + layer.size,
                    StandardCharsets.UTF_8);
        } catch (Throwable error) {
            plugin.getLogger().log(Level.FINE, "HahGps: не удалось сохранить кэш карты", error);
        }
    }

    private void load(Layer layer) {
        try {
            File meta = metaFile(layer.world);
            File png = pngFile(layer.world);
            if (!meta.isFile() || !png.isFile()) {
                return;
            }
            String text = Files.readString(meta.toPath(), StandardCharsets.UTF_8).trim();
            String[] parts = text.split("\\s+");
            if (parts.length < 4) {
                return;
            }
            int minX = Integer.parseInt(parts[0]);
            int minZ = Integer.parseInt(parts[1]);
            int bpp = Integer.parseInt(parts[2]);
            int size = Integer.parseInt(parts[3]);
            // Граница мира могла сдвинуться: старый кэш уже не по этим координатам.
            if (bpp != layer.bpp || size != layer.size
                    || minX != layer.minX || minZ != layer.minZ) {
                return;
            }
            BufferedImage image = ImageIO.read(png);
            if (image == null
                    || image.getWidth() != layer.size || image.getHeight() != layer.size) {
                return;
            }
            // Только пустые пиксели: пока PNG читался в фоне, главный поток уже
            // снял с загруженных чанков свежие цвета — затирать их нельзя.
            int[] colors = layer.colors;
            for (int pz = 0; pz < layer.size; pz++) {
                int row = pz * layer.size;
                for (int px = 0; px < layer.size; px++) {
                    int index = row + px;
                    if (colors[index] != 0) {
                        continue;
                    }
                    int argb = image.getRGB(px, pz);
                    if ((argb >>> 24) != 0) {
                        colors[index] = argb | 0xFF000000;
                    }
                }
            }
        } catch (Throwable error) {
            plugin.getLogger().log(Level.FINE, "HahGps: не удалось прочитать кэш карты", error);
        }
    }

    private File pngFile(String world) {
        return new File(new File(plugin.getDataFolder(), "maps"), safeName(world) + ".png");
    }

    private File metaFile(String world) {
        return new File(new File(plugin.getDataFolder(), "maps"), safeName(world) + ".txt");
    }

    private static String safeName(String world) {
        return world == null ? "world" : world.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
