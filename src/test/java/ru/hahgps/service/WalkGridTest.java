package ru.hahgps.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A* по клеткам должен обходить стены, а не резать напрямую — именно на этом
 * ломался прежний маршрут: при пустом графе дорог отдавалась прямая линия.
 */
class WalkGridTest {

    /** Плоский мир: пол на y=0, всё остальное — воздух, кроме явно заданных стен. */
    private static final class TestBlocks implements WalkGrid.Blocks {

        private final Set<String> solid = new HashSet<>();
        private int maxY = 6;

        private TestBlocks wall(int x, int y, int z) {
            solid.add(x + "," + y + "," + z);
            return this;
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return passable(x, y, z) && passable(x, y + 1, z) && isFloor(x, y - 1, z);
        }

        @Override
        public boolean passable(int x, int y, int z) {
            if (y < 1 || y > maxY) {
                return false;
            }
            return !solid.contains(x + "," + y + "," + z);
        }

        private boolean isFloor(int x, int y, int z) {
            return y == 0 || solid.contains(x + "," + y + "," + z);
        }
    }

    private static List<int[]> solve(WalkGrid.Blocks blocks, int sx, int sy, int sz,
                                     int gx, int gy, int gz) {
        WalkGrid grid = new WalkGrid(sx, sy, sz, gx, gy, gz, blocks);
        int guard = 0;
        while (!grid.step(64) && guard++ < 10_000) {
            // крутим порции, пока поиск не завершится
        }
        return grid.path();
    }

    @Test
    void pathGoesAroundWallInsteadOfThroughIt() {
        TestBlocks blocks = new TestBlocks();
        // Стена поперёк пути: z = -1..1, два блока высотой.
        for (int z = -1; z <= 1; z++) {
            blocks.wall(2, 1, z).wall(2, 2, z);
        }

        List<int[]> path = solve(blocks, 0, 1, 0, 4, 1, 0);

        assertFalse(path.isEmpty(), "путь через стену не найден");
        for (int[] cell : path) {
            assertFalse(cell[0] == 2 && Math.abs(cell[2]) <= 1,
                    "маршрут прошёл сквозь стену: " + cell[0] + "," + cell[1] + "," + cell[2]);
        }
        boolean detour = path.stream().anyMatch(cell -> Math.abs(cell[2]) >= 2);
        assertTrue(detour, "маршрут не обошёл стену — нет обходной клетки");
        int[] last = path.get(path.size() - 1);
        assertTrue(Math.abs(last[0] - 4) <= 1 && Math.abs(last[2]) <= 1, "конец пути не у цели");
    }

    @Test
    void sealedBoxHasNoPath() {
        TestBlocks blocks = new TestBlocks();
        // Замуровываем старт в комнате 3x3 без выхода.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = 1; y <= 3; y++) {
                    boolean inside = Math.abs(x) <= 0 && Math.abs(z) <= 0;
                    if (!inside) {
                        blocks.wall(x + 2, y, z);
                    }
                }
            }
        }
        blocks.wall(1, 1, 0).wall(1, 2, 0).wall(1, 3, 0);
        blocks.wall(0, 1, 1).wall(0, 2, 1).wall(0, 3, 1);
        blocks.wall(0, 1, -1).wall(0, 2, -1).wall(0, 3, -1);
        blocks.wall(-1, 1, 0).wall(-1, 2, 0).wall(-1, 3, 0);
        // Сверху тоже закрыто.
        blocks.wall(2, 4, 0);

        List<int[]> path = solve(blocks, 2, 1, 0, 20, 1, 20);

        assertTrue(path.isEmpty(), "из замурованной комнаты пути быть не должно");
    }

    @Test
    void jumpAndDropAreUsedForUnevenGround() {
        TestBlocks blocks = new TestBlocks();
        // Ступенька в блок высотой поперёк всего коридора: обойти её дольше,
        // чем прыгнуть, поэтому маршрут обязан подняться на y = 2.
        for (int z = -3; z <= 3; z++) {
            blocks.wall(2, 1, z);
        }

        List<int[]> path = solve(blocks, 0, 1, 0, 4, 1, 0);

        assertFalse(path.isEmpty(), "путь через уступ не найден");
        boolean climbed = path.stream().anyMatch(cell -> cell[1] >= 2);
        assertTrue(climbed, "маршрут не поднялся на блок — прыжок не работает");
    }
}
