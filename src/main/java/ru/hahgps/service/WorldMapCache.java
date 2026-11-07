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
    private static final int CATCHUP_CHUNKS_PER_PASS = 256;

    private final HahGpsPlugin plugin;
    private final Map<String, Layer> layers = new ConcurrentHashMap<>();
    /** С какого чанка продолжать «догон» уже загруженных чанков. */
    private final Map<String, Integer> catchupCursor = new ConcurrentHashMap<>();
    private final ExecutorService io;
    private BukkitTask saveTask;
    private BukkitTask catchupTask;
    private long catchupStartedAt;

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
        for (Layer layer : layers.values()) {
            if (layer.dirty) {
                layer.dirty = false;
                io.execute(() -> save(layer));
            }
        }
        io.shutdown();
        try {
            if (!io.awaitTermination(3, TimeUnit.SECONDS)) {
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            io.shutdownNow();
            Thread.currentThread().interrupt();
        }
        layers.clear();
    }

    /** Исследованный чанк попал в память — снимаем с него цвет. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.getConfigManager().isMapWorldCache()) {
            return;
        }
        Layer layer = layer(event.getWorld(), true);
        if (layer != null) {
            try {
                sample(event.getWorld(), layer, event.getChunk());
            } catch (Throwable ignored) {
            }
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (plugin.getConfigManager().isMapWorldCache()) {
            layer(event.getWorld(), true);
        }
    }

    /**
     * Чанки, загруженные до регистрации слушателя (перезагрузка плагина,
     * огромный радиус прогрузки), добираем порциями: снимать всё сразу в
     * главном потоке нельзя.
     */
    private void catchUp() {
        if (System.currentTimeMillis() - catchupStartedAt > CATCHUP_TICKS_LIMIT * 50L) {
            BukkitTask task = catchupTask;
            catchupTask = null;
            if (task != null) {
                task.cancel();
            }
            return;
        }
        int budget = CATCHUP_CHUNKS_PER_PASS;
        for (World world : Bukkit.getWorlds()) {
            if (budget <= 0) {
                return;
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
            int start = Math.floorMod(catchupCursor.getOrDefault(world.getName(), 0), loaded.length);
            int count = Math.min(budget, loaded.length);
            for (int i = 0; i < count; i++) {
                Chunk chunk = loaded[(start + i) % loaded.length];
                try {
                    sample(world, layer, chunk);
                } catch (Throwable ignored) {
                }
            }
            catchupCursor.put(world.getName(), (start + count) % loaded.length);
            budget -= count;
        }
    }

    // ------------------------------------------------------------------
    // Чтение
    // ------------------------------------------------------------------

    /** Цвет поверхности из кэша; 0 — этой точки в кэше нет. */
    public int colorAt(World world, int x, int z) {
        Layer layer = layer(world, false);
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
            for (int pz = 0; pz < layer.size; pz++) {
                int row = pz * layer.size;
                for (int px = 0; px < layer.size; px++) {
                    int color = copy[row + px];
                    if (color != 0) {
                        image.setRGB(px, pz, color);
                    }
                }
            }
            ImageIO.write(image, "png", pngFile(layer.world));
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
            for (int pz = 0; pz < layer.size; pz++) {
                int row = pz * layer.size;
                for (int px = 0; px < layer.size; px++) {
                    int argb = image.getRGB(px, pz);
                    if ((argb >>> 24) != 0) {
                        layer.colors[row + px] = argb | 0xFF000000;
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
