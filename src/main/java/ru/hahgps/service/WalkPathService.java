package ru.hahgps.service;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;
import ru.hahgps.HahGpsPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Пешеходный маршрут по блокам: A* ({@link WalkGrid}), который обходит стены,
 * а не ведёт по прямой сквозь них.
 *
 * Зачем отдельно от {@link Pathfinder}: граф дорог строится по записанным
 * маршрутам, и если дороги к цели нет, прежний код отдавал прямую линию —
 * игрок «проходил» сквозь горы. Здесь путь ищется по реальным блокам: шаг в
 * соседнюю клетку, прыжок на блок вверх и падение до трёх блоков вниз; лава,
 * огонь, кактус и порошковый снег считаются непроходимыми.
 *
 * Расчёт идёт в главном потоке порциями (по {@link #NODES_PER_TICK} клеток за
 * тик): читать блоки из фонового потока нельзя — это гонка с загрузкой чанков.
 */
public class WalkPathService {

    /** Сколько клеток A* разбирает за один тик. */
    private static final int NODES_PER_TICK = 1200;
    /** Потолок работы на один запрос: дальше честно сдаёмся. */
    private static final int MAX_NODES = 24_000;
    /** Сколько тиков живёт расчёт до принудительной остановки. */
    private static final int MAX_TICKS = 100;

    private static final Set<Material> HAZARDS = Set.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.POWDER_SNOW,
            Material.CACTUS, Material.MAGMA_BLOCK, Material.SWEET_BERRY_BUSH,
            Material.WITHER_ROSE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE);

    private final HahGpsPlugin plugin;

    public WalkPathService(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        try {
            return plugin.getConfigManager().isWalkFallbackEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** Путь по клеткам или пустой список, если дойти нельзя/далеко/выключено. */
    public CompletableFuture<List<Location>> findPath(Location start, Location target) {
        if (start == null || target == null || start.getWorld() == null || target.getWorld() == null
                || !start.getWorld().equals(target.getWorld()) || !isEnabled()) {
            return CompletableFuture.completedFuture(List.of());
        }
        double distance = start.distance(target);
        if (distance <= 0.0 || distance > plugin.getConfigManager().getWalkMaxDistance()) {
            return CompletableFuture.completedFuture(List.of());
        }
        CompletableFuture<List<Location>> future = new CompletableFuture<>();
        Runnable starter = () -> new Job(start.clone(), target.clone(), future).start();
        if (Bukkit.isPrimaryThread()) {
            starter.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, starter);
        }
        return future;
    }

    // ------------------------------------------------------------------
    // Расчёт
    // ------------------------------------------------------------------

    private final class Job implements Runnable {

        private final Location start;
        private final Location target;
        private final CompletableFuture<List<Location>> future;
        private final World world;
        private final WalkGrid grid;

        private BukkitTask task;
        private int ticks;
        private boolean finished;

        private Job(Location start, Location target, CompletableFuture<List<Location>> future) {
            this.start = start;
            this.target = target;
            this.future = future;
            this.world = start.getWorld();
            Block probe = new Block(world);
            this.grid = new WalkGrid(start.getBlockX(), start.getBlockY(), start.getBlockZ(),
                    target.getBlockX(), target.getBlockY(), target.getBlockZ(), probe);
        }

        private void start() {
            if (!world.isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) {
                complete(List.of());
                return;
            }
            step();
        }

        private void step() {
            if (!plugin.isEnabled()) {
                complete(List.of());
                return;
            }
            boolean done = grid.step(NODES_PER_TICK);
            if (done) {
                complete(grid.path().isEmpty() ? List.of() : route(grid.path()));
                return;
            }
            if (++ticks > MAX_TICKS || grid.expanded() >= MAX_NODES) {
                complete(List.of());
                return;
            }
            task = Bukkit.getScheduler().runTaskLater(plugin, this, 1L);
        }

        @Override
        public void run() {
            step();
        }

        private List<Location> route(List<int[]> cells) {
            List<Location> path = new ArrayList<>();
            int[] previous = null;
            int[] previousDirection = null;
            for (int i = 0; i < cells.size(); i++) {
                int[] cell = cells.get(i);
                int[] direction = previous == null ? null
                        : new int[]{cell[0] - previous[0], cell[1] - previous[1], cell[2] - previous[2]};
                boolean turn = previousDirection == null || direction == null
                        || direction[0] != previousDirection[0] || direction[1] != previousDirection[1]
                        || direction[2] != previousDirection[2];
                if (i == 0 || i == cells.size() - 1 || turn || i % 4 == 0) {
                    path.add(new Location(world, cell[0] + 0.5, cell[1], cell[2] + 0.5));
                }
                previous = cell;
                previousDirection = direction;
            }
            // Последняя точка — точная цель: трекер висит не по центру клетки.
            if (path.isEmpty() || path.get(path.size() - 1).distance(target) > 0.4) {
                path.add(target.clone());
            }
            return path;
        }

        private void complete(List<Location> path) {
            if (finished) {
                return;
            }
            finished = true;
            BukkitTask current = task;
            task = null;
            if (current != null) {
                try {
                    current.cancel();
                } catch (Throwable ignored) {
                }
            }
            future.complete(path);
        }
    }

    /** Доступ к блокам мира для {@link WalkGrid} (только главный поток). */
    private static final class Block implements WalkGrid.Blocks {

        private final World world;

        private Block(World world) {
            this.world = world;
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return passable(x, y, z) && passable(x, y + 1, z) && support(x, y - 1, z);
        }

        @Override
        public boolean passable(int x, int y, int z) {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return false;
            }
            Material material = world.getBlockAt(x, y, z).getType();
            if (HAZARDS.contains(material)) {
                return false;
            }
            return material.isAir() || !material.isSolid();
        }

        private boolean support(int x, int y, int z) {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return false;
            }
            Material material = world.getBlockAt(x, y, z).getType();
            if (HAZARDS.contains(material)) {
                return false;
            }
            return material.isSolid() || material == Material.WATER;
        }
    }
}
