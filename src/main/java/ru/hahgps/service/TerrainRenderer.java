package ru.hahgps.service;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.map.MapView;
import ru.hahgps.model.Poi;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Асинхронный рендерер террейна для карты.
 * Загружает чанки, сэмплирует блоки, рисует цветное изображение.
 */
public class TerrainRenderer {

    private static final int MAP_SIZE = 128;

    /**
     * Блоков на пиксель для каждого масштаба.
     */
    public static double blocksPerPixel(MapView.Scale scale) {
        return switch (scale) {
            case FARTHEST -> 8.0;
            case FAR -> 4.0;
            case NORMAL -> 2.0;
            case CLOSE -> 1.0;
            case CLOSEST -> 0.5;
        };
    }

    /**
     * Асинхронный рендер карты.
     */
    public CompletableFuture<BufferedImage> render(World world, int centerX, int centerZ,
                                                   MapView.Scale scale, List<Poi> pois,
                                                   List<Location> route, Location playerLoc) {
        double bpp = blocksPerPixel(scale);
        int radius = (int) (MAP_SIZE / 2 * bpp);

        // Загружаем все необходимые чанки
        Set<Long> chunkKeys = new HashSet<>();
        List<CompletableFuture<Chunk>> futures = new ArrayList<>();
        int minChunkX = (centerX - radius) >> 4;
        int maxChunkX = (centerX + radius) >> 4;
        int minChunkZ = (centerZ - radius) >> 4;
        int maxChunkZ = (centerZ + radius) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                long key = (((long) cx) << 32) | (cz & 0xffffffffL);
                if (chunkKeys.add(key)) {
                    futures.add(world.getChunkAtAsync(cx, cz));
                }
            }
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> drawImage(world, centerX, centerZ, bpp, pois, route, playerLoc));
    }

    private BufferedImage drawImage(World world, int centerX, int centerZ, double bpp,
                                    List<Poi> pois, List<Location> route, Location playerLoc) {
        BufferedImage image = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        // Фон (вода по умолчанию)
        g.setColor(new Color(30, 60, 120));
        g.fillRect(0, 0, MAP_SIZE, MAP_SIZE);

        // Террейн
        for (int px = 0; px < MAP_SIZE; px++) {
            for (int pz = 0; pz < MAP_SIZE; pz++) {
                int worldX = centerX + (int) ((px - MAP_SIZE / 2) * bpp);
                int worldZ = centerZ + (int) ((pz - MAP_SIZE / 2) * bpp);
                int color = sampleBlockColor(world, worldX, worldZ);
                g.setColor(new Color(color));
                g.fillRect(px, pz, 1, 1);
            }
        }

        // Маршрут
        if (route != null && route.size() >= 2) {
            g.setColor(new Color(0, 255, 200));
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

        // Точки (POI)
        if (pois != null) {
            for (Poi poi : pois) {
                Location loc = poi.getLocation();
                if (loc == null || loc.getWorld() == null || !loc.getWorld().equals(world)) {
                    continue;
                }
                int px = (int) ((loc.getX() - centerX) / bpp) + MAP_SIZE / 2;
                int pz = (int) ((loc.getZ() - centerZ) / bpp) + MAP_SIZE / 2;
                if (px >= 0 && px < MAP_SIZE && pz >= 0 && pz < MAP_SIZE) {
                    drawMarker(g, px, pz, Color.RED);
                }
            }
        }

        // Игрок
        if (playerLoc != null && playerLoc.getWorld() != null && playerLoc.getWorld().equals(world)) {
            int px = (int) ((playerLoc.getX() - centerX) / bpp) + MAP_SIZE / 2;
            int pz = (int) ((playerLoc.getZ() - centerZ) / bpp) + MAP_SIZE / 2;
            if (px >= 0 && px < MAP_SIZE && pz >= 0 && pz < MAP_SIZE) {
                drawMarker(g, px, pz, Color.WHITE);
            }
        }

        g.dispose();
        return image;
    }

    /**
     * Сэмплирование цвета блока с учётом высоты.
     */
    private int sampleBlockColor(World world, int x, int z) {
        try {
            int y = world.getHighestBlockYAt(x, z);
            Material material = world.getBlockAt(x, y, z).getType();
            int base = getMaterialColor(material);
            // Затемнение по высоте
            double shade = Math.max(0.4, Math.min(1.0, 0.6 + (y / 256.0) * 0.4));
            int r = (int) (((base >> 16) & 0xFF) * shade);
            int g = (int) (((base >> 8) & 0xFF) * shade);
            int b = (int) ((base & 0xFF) * shade);
            return (r << 16) | (g << 8) | b;
        } catch (Exception e) {
            return 0x202020;
        }
    }

    /**
     * Цвета материалов (упрощённая палитра).
     */
    private int getMaterialColor(Material material) {
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

    /**
     * Маркер 3x3 с тёмной окантовкой.
     */
    private void drawMarker(Graphics2D g, int x, int y, Color color) {
        g.setColor(Color.BLACK);
        g.fillRect(x - 2, y - 2, 5, 5);
        g.setColor(color);
        g.fillRect(x - 1, y - 1, 3, 3);
    }

    /**
     * Линия (алгоритм Брезенхэма).
     */
    private void drawLine(Graphics2D g, int x0, int y0, int x1, int y1) {
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
