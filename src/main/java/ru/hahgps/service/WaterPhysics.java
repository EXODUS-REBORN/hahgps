package ru.hahgps.service;

/**
 * Движение трекера, попавшего в воду: держится на поверхности, плывёт по
 * течению и падает вниз там, где вода обрывается (водопад, слив, осушённое
 * русло).
 *
 * Класс намеренно не знает про Bukkit: мир описан интерфейсом {@link Terrain},
 * поэтому поверхность и течение проверяются юнит-тестами без сервера. Всю
 * работу с блоками делает {@code TrackerService}, который и передаёт сюда
 * высоту поверхности и вектор течения.
 *
 * Позиция трекера — маркер у поверхности: {@code y} — верхний уровень воды.
 * Блок, на котором маркер «стоит», — это {@code floor(y - 0.02)}, поэтому вода
 * ищется именно там, а не по центру блока.
 */
public final class WaterPhysics {

    /** Шаг падения: мельче блока, чтобы вода и дно не проскакивались насквозь. */
    private static final double FALL_STEP = 0.5;

    /** Зазор между маркером и блоком под ним: тот же, что в сервисе. */
    private static final double BLOCK_EPSILON = 0.02;

    /** Порог, ниже которого вектор течения считается нулевым. */
    private static final double FLOW_EPSILON = 1e-4;

    private WaterPhysics() {
    }

    /** Что нужно знать о мире, чтобы посчитать шаг. */
    public interface Terrain {

        /** Настоящая вода (источник или течение), без затопленных блоков. */
        boolean isWater(int x, int y, int z);

        /** Вода падает: блок водопада, а не поверхность. */
        boolean isFallingWater(int x, int y, int z);

        /** Блок не пропускает трекер (берег, дно, стена). */
        boolean isSolid(int x, int y, int z);

        /** Абсолютная высота поверхности воды в этом блоке. */
        double surfaceY(int x, int y, int z);

        /** Горизонтальное течение в этом блоке (не нормализованное). */
        double flowX(int x, int y, int z);

        double flowZ(int x, int y, int z);
    }

    /** Координаты блока, на который трекер сел после падения. */
    public record BlockPos(int x, int y, int z) {
    }

    /**
     * Куда встать после шага.
     *
     * @param floating стоит на поверхности воды (а не лежит на земле)
     * @param ground   блок, на который сел трекер; {@code null}, если не сел
     */
    public record Pose(double x, double y, double z, boolean floating, BlockPos ground) {
    }

    /**
     * Шаг плавающего трекера: либо дрейф по течению, либо падение вниз.
     *
     * @param dtSeconds  прошедшее время шага
     * @param driftSpeed скорость дрейфа по течению, блоков в секунду
     * @param fallSpeed  скорость падения, блоков в секунду
     */
    public static Pose floatStep(double x, double y, double z, Terrain terrain,
                                 double dtSeconds, double driftSpeed, double fallSpeed) {
        int bx = floor(x);
        int bz = floor(z);
        int by = floor(y - BLOCK_EPSILON);
        if (terrain.isWater(bx, by, bz) && !terrain.isFallingWater(bx, by, bz)) {
            return drift(x, y, z, bx, by, bz, terrain, dtSeconds, driftSpeed);
        }
        return fall(x, y, z, bx, bz, terrain, dtSeconds, fallSpeed);
    }

    private static Pose drift(double x, double y, double z, int bx, int by, int bz,
                              Terrain terrain, double dtSeconds, double driftSpeed) {
        double surface = terrain.surfaceY(bx, by, bz);
        double nx = x;
        double nz = z;
        double flowX = terrain.flowX(bx, by, bz);
        double flowZ = terrain.flowZ(bx, by, bz);
        double length = Math.hypot(flowX, flowZ);
        if (driftSpeed > 0 && length > FLOW_EPSILON) {
            double tx = x + flowX / length * driftSpeed * dtSeconds;
            double tz = z + flowZ / length * driftSpeed * dtSeconds;
            int dx = floor(tx);
            int dz = floor(tz);
            // Берег: в твёрдый блок трекер не заплывает, но и не дёргается.
            if (!terrain.isSolid(dx, by, dz)) {
                nx = tx;
                nz = tz;
                if (terrain.isWater(dx, by, dz) && !terrain.isFallingWater(dx, by, dz)) {
                    surface = terrain.surfaceY(dx, by, dz);
                }
            }
        }
        return new Pose(nx, surface, nz, true, null);
    }

    private static Pose fall(double x, double y, double z, int bx, int bz,
                             Terrain terrain, double dtSeconds, double fallSpeed) {
        double remaining = Math.max(0.0, fallSpeed * dtSeconds);
        double current = y;
        while (remaining > 1e-6) {
            double step = Math.min(FALL_STEP, remaining);
            double probe = current - step;
            int by = floor(probe - BLOCK_EPSILON);
            if (terrain.isWater(bx, by, bz)) {
                if (!terrain.isFallingWater(bx, by, bz)) {
                    // Вода-поверхность: всплываем на неё, как выброшенный предмет.
                    return new Pose(x, terrain.surfaceY(bx, by, bz), z, true, null);
                }
            } else if (terrain.isSolid(bx, by, bz)) {
                // Сели на землю: маркер встаёт в свободный блок над ней.
                return new Pose(x, by + 1.5, z, false, new BlockPos(bx, by, bz));
            }
            current = probe;
            remaining -= step;
        }
        return new Pose(x, current, z, false, null);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
