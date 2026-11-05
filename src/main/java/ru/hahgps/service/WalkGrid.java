package ru.hahgps.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* по клеткам мира: шаг в соседнюю клетку, прыжок на блок вверх и падение до
 * трёх блоков вниз. Нужен, чтобы маршрут обходил стены, а не шёл по прямой
 * сквозь них (раньше при пустом графе дорог отдавалась прямая линия).
 *
 * Bukkit здесь не используется: доступ к блокам приходит через {@link Blocks},
 * поэтому алгоритм можно покрыть обычными юнит-тестами на сетке.
 */
public final class WalkGrid {

    /** Доступ к миру: «можно ли стоять в клетке» и «свободна ли клетка». */
    public interface Blocks {
        boolean standable(int x, int y, int z);

        boolean passable(int x, int y, int z);
    }

    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private final Blocks blocks;
    private final int goalX;
    private final int goalY;
    private final int goalZ;

    private final Map<Long, Long> cameFrom = new HashMap<>();
    private final Map<Long, Double> scores = new HashMap<>();
    private final Map<Long, int[]> coords = new HashMap<>();
    private final Set<Long> closed = new HashSet<>();
    private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node.f));

    private boolean finished;
    private List<int[]> path = List.of();
    private int expanded;

    public WalkGrid(int startX, int startY, int startZ,
                    int goalX, int goalY, int goalZ, Blocks blocks) {
        this.blocks = blocks;
        this.goalX = goalX;
        this.goalY = goalY;
        this.goalZ = goalZ;
        addStart(startX, startY, startZ);
    }

    private void addStart(int x, int y, int z) {
        long key = key(x, y, z);
        Node first = new Node(x, y, z, 0.0, heuristic(x, y, z));
        scores.put(key, 0.0);
        coords.put(key, new int[]{x, y, z});
        open.add(first);
    }

    /**
     * Продолжить поиск.
     *
     * @param budget сколько клеток разобрать за вызов
     * @return true, если поиск завершён (путь найден или ходов больше нет)
     */
    public boolean step(int budget) {
        if (finished) {
            return true;
        }
        while (budget-- > 0 && !open.isEmpty()) {
            Node current = open.poll();
            if (current == null || closed.contains(current.key)) {
                continue;
            }
            closed.add(current.key);
            expanded++;
            if (reachedGoal(current)) {
                path = route(current);
                finished = true;
                return true;
            }
            expand(current);
        }
        if (open.isEmpty()) {
            finished = true;
        }
        return finished;
    }

    public boolean isFinished() {
        return finished;
    }

    /** Найденный путь (клетки от старта к цели) или пустой список. */
    public List<int[]> path() {
        return path;
    }

    /** Сколько клеток уже разобрано — по этому числу видно стоимость расчёта. */
    public int expanded() {
        return expanded;
    }

    private void expand(Node current) {
        for (int[] direction : DIRECTIONS) {
            int dx = direction[0];
            int dz = direction[1];
            boolean diagonal = dx != 0 && dz != 0;
            if (diagonal && (!blocks.passable(current.x + dx, current.y, current.z)
                    || !blocks.passable(current.x, current.y, current.z + dz))) {
                // Срезать угол сквозь стену нельзя: обе смежные клетки должны быть свободны.
                continue;
            }
            int nx = current.x + dx;
            int nz = current.z + dz;
            double step = diagonal ? 1.4142 : 1.0;

            if (blocks.standable(nx, current.y, nz)) {
                add(current, nx, current.y, nz, step);
                continue;
            }
            if (blocks.passable(current.x, current.y + 2, current.z)
                    && blocks.standable(nx, current.y + 1, nz)) {
                add(current, nx, current.y + 1, nz, step + 0.5);
                continue;
            }
            for (int drop = 1; drop <= 3; drop++) {
                if (blocks.standable(nx, current.y - drop, nz)) {
                    add(current, nx, current.y - drop, nz, step + drop * 0.2);
                    break;
                }
            }
        }
    }

    private void add(Node from, int x, int y, int z, double cost) {
        long key = key(x, y, z);
        if (closed.contains(key)) {
            return;
        }
        double tentative = from.g + cost;
        Double known = scores.get(key);
        if (known != null && known <= tentative) {
            return;
        }
        scores.put(key, tentative);
        coords.put(key, new int[]{x, y, z});
        cameFrom.put(key, from.key);
        open.add(new Node(x, y, z, tentative, tentative + heuristic(x, y, z)));
    }

    private boolean reachedGoal(Node node) {
        return Math.abs(node.x - goalX) <= 1 && Math.abs(node.z - goalZ) <= 1
                && Math.abs(node.y - goalY) <= 2;
    }

    private double heuristic(int x, int y, int z) {
        double dx = x - goalX;
        double dy = (y - goalY) * 1.2;
        double dz = z - goalZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private List<int[]> route(Node goal) {
        List<int[]> cells = new ArrayList<>();
        Long key = goal.key;
        while (key != null) {
            int[] cell = coords.get(key);
            if (cell == null) {
                break;
            }
            cells.add(cell);
            key = cameFrom.get(key);
        }
        Collections.reverse(cells);
        return cells;
    }

    private static final class Node {
        private final int x;
        private final int y;
        private final int z;
        private final long key;
        private final double g;
        private final double f;

        private Node(int x, int y, int z, double g, double f) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.key = key(x, y, z);
            this.g = g;
            this.f = f;
        }
    }

    /** Упаковка координат в long: ключ клетки для карт A*. */
    static long key(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38)
                | (((long) z & 0x3FFFFFFL) << 12)
                | ((long) y & 0xFFFL);
    }
}
