package ru.hahgps.service;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.map.MapView;
import org.bukkit.scheduler.BukkitTask;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.model.Poi;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * Отрисовка карты: сэмплирование блоков идёт порциями в главном потоке,
 * картинка рисуется в своём потоке.
 *
 * Каждая задача знает, актуальна ли она ещё ({@code cancelled}): карта,
 * которую игрок уже закрыл или перерисовал, перестаёт грузить чанки и
 * сэмплировать блоки. Раньше токен только отменял подстановку картинки,
 * а работа (до 1024 загрузок чанков) продолжалась до конца.
 */
public class TerrainRenderer {

    private static final int MAP_SIZE = 128;
    private static final int TOTAL_SAMPLES = MAP_SIZE * MAP_SIZE;

    /**
     * Сколько чанков разрешено догрузить за один рендер. Догрузка идёт без
     * генерации (второй аргумент {@code getChunkAtAsync} — false), но каждый
     * загруженный чанк — это работа главного потока и событие ChunkLoad,
     * поэтому бюджет маленький: остальное полотно возьмёт из кэша мира, а
     * совсем неисследованные углы останутся тёмными.
     */
    private static int chunkBudget(org.bukkit.map.MapView.Scale scale) {
        return switch (scale) {
            case CLOSEST -> 16;
            case CLOSE -> 24;
            case NORMAL -> 32;
            case FAR, FARTHEST -> 0;
        };
    }

    /** Пикселей за тик: 128×128 полотно снимается за 16 тиков (0.8 с). */
    private static final int SAMPLES_PER_TICK = 1024;

    private static final int NO_DATA = 0x202020;


    private final HahGpsPlugin plugin;
    private final ExecutorService drawExecutor;
    /** Задачи порционного сэмплирования: снимаются при выключении плагина. */
    private final java.util.Set<BukkitTask> samplingTasks =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Последняя снятая поверхность: «мир + центр + масштаб» → цвета пикселей.
     * Карта перерисовывается раз в {@code map.refresh-seconds} (и на каждый
     * шаг следования), но поверхность под маркерами не меняется — повторное
     * сэмплирование 16 384 блоков было самой дорогой частью рендера.
     * Живёт недолго: мир меняется, кэш надо перечитывать.
     */
    private static final int SURFACE_CACHE_LIMIT = 8;
    private static final long SURFACE_TTL_MS = 20_000L;
    private final java.util.Map<String, Surface> surfaces = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Surface> eldest) {
            return size() > SURFACE_CACHE_LIMIT;
        }
    };

    private record Surface(int[] colors, long at) {
    }

    private static String surfaceKey(World world, int centerX, int centerZ, MapView.Scale scale) {
        return world.getName() + '|' + centerX + '|' + centerZ + '|' + scale.name();
    }

    /** Готовая поверхность, если она ещё свежая; иначе null. */
    private synchronized int[] cachedSurface(String key) {
        Surface surface = surfaces.get(key);
        if (surface == null) {
            return null;
        }
        if (System.currentTimeMillis() - surface.at > SURFACE_TTL_MS) {
            surfaces.remove(key);
            return null;
        }
        return surface.colors;
    }

    private synchronized void storeSurface(String key, int[] colors) {
        surfaces.put(key, new Surface(colors, System.currentTimeMillis()));
    }

    /** Сбросить кэш поверхности: мир изменился (поставили/сломали блок). */
    public synchronized void invalidateSurface(String worldName) {
        if (worldName == null) {
            surfaces.clear();
            return;
        }
        surfaces.keySet().removeIf(key -> key.startsWith(worldName + '|'));
    }

    public TerrainRenderer(HahGpsPlugin plugin) {
        this.plugin = plugin;
        this.drawExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "HahGps-MapRender");
            t.setDaemon(true);
            return t;
        });
    }


    public void shutdown() {
        cancelSamplingTasks();
        drawExecutor.shutdownNow();
    }

    public static double blocksPerPixel(MapView.Scale scale) {
        return switch (scale) {
            case FARTHEST -> 16.0;
            case FAR -> 8.0;
            case NORMAL -> 4.0;
            case CLOSE -> 2.0;
            case CLOSEST -> 1.0;
        };
    }

    /** Цвет поверхности из кэша мира для незагруженного чанка; 0 — данных нет. */
    @FunctionalInterface
    public interface CachedColors {
        int colorAt(World world, int x, int z);
    }

    /**
     * Маячок трекера на карте: точка, цвет и признак «сигнала нет». Офлайн
     * рисуется контуром, чтобы его не путали с живым трекером.
     */
    public record Blip(Location location, Color color, boolean offline) {
    }

    public CompletableFuture<BufferedImage> render(World world, int centerX, int centerZ,
                                                   MapView.Scale scale, List<Poi> pois,
                                                   List<Location> route, Location playerLoc,
                                                   List<Location> others,
                                                   BooleanSupplier cancelled) {
        return render(world, centerX, centerZ, scale, pois, route, playerLoc, others,
                List.of(), cancelled, null);
    }

    /**
     * @param cachedColors цвет из кэша мира для чанков, которых сейчас нет в
     *                     памяти (null — такие пиксели остаются тёмными)
     */
    public CompletableFuture<BufferedImage> render(World world, int centerX, int centerZ,
                                                   MapView.Scale scale, List<Poi> pois,
                                                   List<Location> route, Location playerLoc,
                                                   List<Location> others, List<Blip> trackers,
                                                   BooleanSupplier cancelled,
                                                   CachedColors cachedColors) {
        if (world == null) {
            return CompletableFuture.completedFuture(blankImage());
        }
        if (!Bukkit.isPrimaryThread()) {
            CompletableFuture<BufferedImage> out = new CompletableFuture<>();
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        render(world, centerX, centerZ, scale, pois, route, playerLoc, others,
                                trackers, cancelled, cachedColors)
                                .whenComplete((image, error) -> {
                                    if (error != null) {
                                        out.completeExceptionally(error);
                                    } else {
                                        out.complete(image);
                                    }
                                });
                    } catch (Throwable t) {
                        out.completeExceptionally(t);
                    }
                });
            } catch (Throwable t) {
                out.completeExceptionally(t);
            }
            return out;
        }
        return renderOnMain(world, centerX, centerZ, scale, pois, route, playerLoc, others,
                trackers, cancelled, cachedColors);
    }

    private CompletableFuture<BufferedImage> renderOnMain(World world, int centerX, int centerZ,
                                                          MapView.Scale scale, List<Poi> pois,
                                                          List<Location> route, Location playerLoc,
                                                          List<Location> others,
                                                          List<Blip> trackers,
                                                          BooleanSupplier cancelled,
                                                          CachedColors cachedColors) {
        double bpp = blocksPerPixel(scale);
        int radius = (int) (MAP_SIZE / 2 * bpp);

        // Готовая поверхность этой же карты (тот же центр и масштаб) не
        // сэмплируется заново: полотно обновляется раз в map.refresh-seconds,
        // и без кэша это была бы повторная выборка 16 384 блоков каждые
        // несколько секунд на каждого держателя карты. Маркеры рисуются
        // поверх и всегда свежие.
        String key = surfaceKey(world, centerX, centerZ, scale);
        int[] reused = cachedSurface(key);
        if (reused != null) {
            return CompletableFuture.supplyAsync(
                    () -> drawImage(reused, centerX, centerZ, bpp, pois, route, playerLoc, others,
                            trackers, world),
                    drawExecutor);
        }

        int centerChunkX = centerX >> 4;
        int centerChunkZ = centerZ >> 4;

        // Чанки, которые надо догрузить: сразу только ближайшие к центру и
        // строго в пределах бюджета. Раньше площадь чанков материализовалась
        // целиком (на FARTHEST — 16 641 массив int[]), сортировалась и почти
        // вся выбрасывалась: на FAR это 4225 лишних аллокаций в главном потоке.
        int budget = chunkBudget(scale);
        List<int[]> chunks = new ArrayList<>(Math.max(1, budget));
        if (budget > 0) {
            int chunkRadius = (radius >> 4) + 1;
            int side = 2 * chunkRadius + 1;
            int total = side * side;
            if (total <= budget) {
                for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                    for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                        chunks.add(new int[]{centerChunkX + dx, centerChunkZ + dz});
                    }
                }
            } else {
                // Кольца от центра наружу: ровно budget чанков, без сортировки.
                chunks.add(new int[]{centerChunkX, centerChunkZ});
                outer:
                for (int ring = 1; ring <= chunkRadius; ring++) {
                    for (int dx = -ring; dx <= ring; dx++) {
                        for (int dz = -ring; dz <= ring; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                                continue;
                            }
                            chunks.add(new int[]{centerChunkX + dx, centerChunkZ + dz});
                            if (chunks.size() >= budget) {
                                break outer;
                            }
                        }
                    }
                }
            }
        }

        List<CompletableFuture<?>> loads = new ArrayList<>(chunks.size());
        for (int[] c : chunks) {
            // Уже загруженный чанк трогать не надо: getChunkAtAsync на нём всё
            // равно делает работу и поднимает событие загрузки.
            if (world.isChunkLoaded(c[0], c[1])) {
                continue;
            }
            try {
                CompletableFuture<Chunk> load = world.getChunkAtAsync(c[0], c[1], false);
                loads.add(load.handle((chunk, error) -> null));
            } catch (Throwable ignored) {
            }
        }

        CompletableFuture<Void> all = loads.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(loads.toArray(new CompletableFuture[0]));

        CompletableFuture<int[]> sampled = new CompletableFuture<>();
        all.whenComplete((v, error) -> {
            if (error != null) {
                sampled.completeExceptionally(error);
                return;
            }
            if (cancelled != null && cancelled.getAsBoolean()) {
                sampled.complete(null);
                return;
            }
            startSampling(world, centerX, centerZ, bpp, sampled, cancelled, cachedColors, key);
        });

        return sampled.thenApplyAsync(
                colors -> colors == null ? null
                        : drawImage(colors, centerX, centerZ, bpp, pois, route, playerLoc, others,
                                trackers, world),
                drawExecutor);
    }


    /**
     * Порционное сэмплирование полотна в главном потоке.
     *
     * Задача обязана завершить {@code out} при любом исходе: раньше исключение
     * внутри тика (Bukkit ловит только RuntimeException — но здесь могли
     * прилететь и Errors из чанк-API) убивало задачу вместе с future, и карта
     * молча переставала обновляться до перезапуска. Ссылка на задачу хранится,
     * чтобы {@link #shutdown()} мог её снять.
     */
    private void startSampling(World world, int centerX, int centerZ, double bpp,
                               CompletableFuture<int[]> out, BooleanSupplier cancelled,
                               CachedColors cachedColors, String surfaceKey) {
        int[] colors = new int[TOTAL_SAMPLES];
        Arrays.fill(colors, NO_DATA);
        int[] cursor = {0};
        BukkitTask[] holder = new BukkitTask[1];
        try {
            holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        cancelQuietly(holder[0]);
                        out.complete(null);
                        return;
                    }
                    int budget = SAMPLES_PER_TICK;
                    while (budget-- > 0 && cursor[0] < TOTAL_SAMPLES) {
                        int index = cursor[0]++;
                        int px = index % MAP_SIZE;
                        int pz = index / MAP_SIZE;
                        int worldX = centerX + (int) ((px - MAP_SIZE / 2) * bpp);
                        int worldZ = centerZ + (int) ((pz - MAP_SIZE / 2) * bpp);
                        colors[index] = sampleBlock(world, worldX, worldZ, cachedColors);
                    }
                    if (cursor[0] >= TOTAL_SAMPLES) {
                        cancelQuietly(holder[0]);
                        if (surfaceKey != null) {
                            storeSurface(surfaceKey, colors);
                        }
                        out.complete(colors);
                    }
                } catch (Throwable t) {
                    cancelQuietly(holder[0]);
                    out.completeExceptionally(t);
                }
            }, 0L, 1L);
            BukkitTask created = holder[0];
            if (created != null) {
                synchronized (samplingTasks) {
                    samplingTasks.add(created);
                }
            }
        } catch (Throwable t) {
            out.completeExceptionally(t);
        }
    }

    /** Снять все задачи сэмплирования: выключение плагина не должно их оставлять. */
    private void cancelSamplingTasks() {
        synchronized (samplingTasks) {
            for (BukkitTask task : samplingTasks) {
                cancelQuietly(task);
            }
            samplingTasks.clear();
        }
    }

    private static void cancelQuietly(BukkitTask task) {
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (Throwable ignored) {
        }
    }

    private int sampleBlock(World world, int x, int z, CachedColors cachedColors) {
        try {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                // Исследованный мир живёт в кэше: без него панорама карты
                // упиралась бы в чёрные дыры на незагруженных чанках.
                if (cachedColors != null) {
                    int cached = cachedColors.colorAt(world, x, z);
                    if (cached != 0) {
                        return cached & 0xFFFFFF;
                    }
                }
                return NO_DATA;
            }
            int y = world.getHighestBlockYAt(x, z);
            Material material = world.getBlockAt(x, y, z).getType();
            if (material.isAir()) {
                int below = y - 1;
                if (below < world.getMinHeight()) {
                    return NO_DATA;
                }
                material = world.getBlockAt(x, below, z).getType();
                if (material.isAir()) {
                    return NO_DATA;
                }
                y = below;
            }

            double shade = Math.max(0.4, Math.min(1.0, 0.6 + (y / 256.0) * 0.4));
            int base = materialColor(material);
            int r = (int) (((base >> 16) & 0xFF) * shade);
            int g = (int) (((base >> 8) & 0xFF) * shade);
            int b = (int) ((base & 0xFF) * shade);
            return (r << 16) | (g << 8) | b;
        } catch (Exception e) {
            return NO_DATA;
        }
    }

    /**
     * Цвет поверхности мира в точке (0 — данных нет). Общий код для карты и
     * кэша мира: иначе они рисовали бы разные палитры.
     */
    public static int surfaceColor(World world, int x, int z) {
        try {
            if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) {
                return 0;
            }
            int y = world.getHighestBlockYAt(x, z);
            Material material = world.getBlockAt(x, y, z).getType();
            if (material.isAir()) {
                int below = y - 1;
                if (below < world.getMinHeight()) {
                    return 0;
                }
                material = world.getBlockAt(x, below, z).getType();
                if (material.isAir()) {
                    return 0;
                }
                y = below;
            }
            double shade = Math.max(0.4, Math.min(1.0, 0.6 + (y / 256.0) * 0.4));
            int base = materialColor(material);
            int r = (int) (((base >> 16) & 0xFF) * shade);
            int g = (int) (((base >> 8) & 0xFF) * shade);
            int b = (int) ((base & 0xFF) * shade);
            return (r << 16) | (g << 8) | b;
        } catch (Exception e) {
            return 0;
        }
    }

    private static BufferedImage blankImage() {
        return new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_RGB);
    }


    private BufferedImage drawImage(int[] colors, int centerX, int centerZ, double bpp,
                                    List<Poi> pois, List<Location> route, Location playerLoc,
                                    List<Location> others, List<Blip> trackers, World world) {
        BufferedImage image = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_RGB);
        // Одной пачкой вместо 16 384 вызовов setRGB по одному пикселю.
        image.setRGB(0, 0, MAP_SIZE, MAP_SIZE, colors, 0, MAP_SIZE);
        Graphics2D g = image.createGraphics();

        if (route != null && route.size() >= 2) {
            g.setColor(new Color(102, 170, 255));
            for (int i = 1; i < route.size(); i++) {
                Location a = route.get(i - 1);
                Location b = route.get(i);
                if (a.getWorld() == null || !a.getWorld().equals(b.getWorld())) {
                    continue;
                }
                int ax = (int) ((a.getX() - centerX) / bpp) + MAP_SIZE / 2;
                int az = (int) ((a.getZ() - centerZ) / bpp) + MAP_SIZE / 2;
                int bx = (int) ((b.getX() - centerX) / bpp) + MAP_SIZE / 2;
                int bz = (int) ((b.getZ() - centerZ) / bpp) + MAP_SIZE / 2;
                drawLine(g, ax, az, bx, bz);
            }
        }

        if (pois != null) {
            for (Poi poi : pois) {
                Location loc = poi == null ? null : poi.getLocation();
                if (loc == null || loc.getWorld() == null || !loc.getWorld().equals(world)) {
                    continue;
                }
                int px = (int) ((loc.getX() - centerX) / bpp) + MAP_SIZE / 2;
                int pz = (int) ((loc.getZ() - centerZ) / bpp) + MAP_SIZE / 2;
                if (px >= 0 && px < MAP_SIZE && pz >= 0 && pz < MAP_SIZE) {
                    drawMarker(g, px, pz, new Color(63, 134, 255));
                }
            }
        }

        if (others != null) {
            for (Location other : others) {
                if (other == null || other.getWorld() == null || !other.getWorld().equals(world)) {
                    continue;
                }
                int px = (int) ((other.getX() - centerX) / bpp) + MAP_SIZE / 2;
                int pz = (int) ((other.getZ() - centerZ) / bpp) + MAP_SIZE / 2;
                if (px >= 0 && px < MAP_SIZE && pz >= 0 && pz < MAP_SIZE) {
                    drawMarker(g, px, pz, new Color(79, 209, 224));
                }
            }
        }

        if (trackers != null) {
            for (Blip blip : trackers) {
                Location loc = blip == null ? null : blip.location();
                if (loc == null || loc.getWorld() == null || !loc.getWorld().equals(world)) {
                    continue;
                }
                int px = (int) ((loc.getX() - centerX) / bpp) + MAP_SIZE / 2;
                int pz = (int) ((loc.getZ() - centerZ) / bpp) + MAP_SIZE / 2;
                if (px >= 0 && px < MAP_SIZE && pz >= 0 && pz < MAP_SIZE) {
                    drawBlip(g, px, pz, blip.color(), blip.offline());
                }
            }
        }

        if (playerLoc != null && playerLoc.getWorld() != null && playerLoc.getWorld().equals(world)) {
            int px = (int) ((playerLoc.getX() - centerX) / bpp) + MAP_SIZE / 2;
            int pz = (int) ((playerLoc.getZ() - centerZ) / bpp) + MAP_SIZE / 2;
            if (px >= 0 && px < MAP_SIZE && pz >= 0 && pz < MAP_SIZE) {
                drawMarker(g, px, pz, Color.WHITE);
            }
        }

        drawNorth(g);
        g.dispose();
        return image;
    }

    /** Стрелка «север» у верхнего края: без неё карта безликая и нечитаемая. */
    private void drawNorth(Graphics2D g) {
        int cx = MAP_SIZE / 2;
        g.setColor(new Color(8, 18, 43));
        g.fillPolygon(new int[]{cx, cx - 6, cx + 6}, new int[]{0, 10, 10}, 3);
        g.setColor(Color.WHITE);
        g.fillPolygon(new int[]{cx, cx - 4, cx + 4}, new int[]{2, 9, 9}, 3);
    }

    public static int materialColor(Material material) {
        return switch (material) {
            case GRASS_BLOCK, SHORT_GRASS, TALL_GRASS -> 0x4C9935;
            case DIRT, COARSE_DIRT, PODZOL -> 0x8B5A2B;
            case STONE, COBBLESTONE, MOSSY_COBBLESTONE, STONE_BRICKS -> 0x808080;
            case SAND, SANDSTONE, RED_SAND -> 0xD9C57B;
            case WATER, BUBBLE_COLUMN -> 0x2E5FA3;
            case LAVA -> 0xE25822;
            case SNOW_BLOCK, POWDER_SNOW -> 0xF0F0F0;
            case ICE, PACKED_ICE, BLUE_ICE -> 0x9EC9E8;
            case OAK_LOG, SPRUCE_LOG, BIRCH_LOG, JUNGLE_LOG, ACACIA_LOG, DARK_OAK_LOG -> 0x6B4F2A;
            case OAK_LEAVES, SPRUCE_LEAVES, BIRCH_LEAVES, JUNGLE_LEAVES, ACACIA_LEAVES, DARK_OAK_LEAVES -> 0x2E6B1E;
            case GLASS, GLASS_PANE -> 0xC0E8E8;
            case BRICKS, NETHERRACK -> 0x9B4A3C;
            case OBSIDIAN -> 0x1A1025;
            case GRAVEL -> 0x8A8A8A;
            case CLAY -> 0xA0A8B8;
            case FARMLAND -> 0x6B4A2A;
            case MYCELIUM -> 0x7A5A8A;
            case CRIMSON_NYLIUM, WARPED_NYLIUM -> 0x3A6B5A;
            case NETHER_BRICKS -> 0x3A2030;
            case END_STONE -> 0xD8D8A0;
            case QUARTZ_BLOCK, QUARTZ_BRICKS -> 0xE8E8E0;
            case PRISMARINE, PRISMARINE_BRICKS -> 0x4A8A8A;
            case SEA_LANTERN -> 0xC8E8E0;
            case GLOWSTONE -> 0xE8D878;
            case HAY_BLOCK -> 0xC8A83C;
            case MELON, PUMPKIN -> 0xC86B1E;
            case CACTUS -> 0x3A7A2A;
            case VINE -> 0x2E5B1E;
            case LILY_PAD -> 0x3A7A3A;
            case SNOW -> 0xF0F0F0;
            default -> {
                if (material.name().contains("LOG") || material.name().contains("WOOD")) {
                    yield 0x6B4F2A;
                } else if (material.name().contains("LEAVES")) {
                    yield 0x2E6B1E;
                } else if (material.name().contains("PLANKS")) {
                    yield 0xA8804A;
                } else if (material.name().contains("WOOL")) {
                    yield 0xD8D8D8;
                } else if (material.name().contains("CONCRETE")) {
                    yield 0xB0B0B0;
                } else if (material.name().contains("TERRACOTTA")) {
                    yield 0xB06040;
                } else {
                    yield 0x606060;
                }
            }
        };
    }

    private void drawMarker(Graphics2D g, int x, int y, Color color) {
        g.setColor(new Color(8, 18, 43));
        g.fillRect(x - 2, y - 2, 5, 5);
        g.setColor(color);
        g.fillRect(x - 1, y - 1, 3, 3);
    }

    /**
     * Маячок трекера крупнее обычной точки: живой — залитый цветом трекера,
     * офлайн (носитель не в сети) — тёмный контур с приглушённой заливкой.
     */
    private void drawBlip(Graphics2D g, int x, int y, Color color, boolean offline) {
        Color base = color == null ? Color.WHITE : color;
        g.setColor(new Color(8, 18, 43));
        g.fillRect(x - 3, y - 3, 7, 7);
        if (offline) {
            g.setColor(new Color(base.getRed() / 2, base.getGreen() / 2, base.getBlue() / 2));
            g.fillRect(x - 2, y - 2, 5, 5);
            g.setColor(base);
            g.drawRect(x - 2, y - 2, 5, 5);
        } else {
            g.setColor(base);
            g.fillRect(x - 2, y - 2, 5, 5);
        }
    }

    private void drawLine(Graphics2D g, int x0, int y0, int x1, int y1) {
        // Обрезка по границам канвы: линия к цели в 300k блоков иначе дала бы
        // сотни тысяч итераций Брезенхэма в единственном потоке рендера, и все
        // следующие рендеры встали бы в очередь.
        if ((x0 < 0 && x1 < 0) || (x0 >= MAP_SIZE && x1 >= MAP_SIZE)
                || (y0 < 0 && y1 < 0) || (y0 >= MAP_SIZE && y1 >= MAP_SIZE)) {
            return;
        }
        x0 = Math.max(0, Math.min(MAP_SIZE - 1, x0));
        y0 = Math.max(0, Math.min(MAP_SIZE - 1, y0));
        x1 = Math.max(0, Math.min(MAP_SIZE - 1, x1));
        y1 = Math.max(0, Math.min(MAP_SIZE - 1, y1));
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            if (x0 >= 0 && x0 < MAP_SIZE && y0 >= 0 && y0 < MAP_SIZE) {
                g.fillRect(x0, y0, 1, 1);
            }
            if (x0 == x1 && y0 == y1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
    }
}
