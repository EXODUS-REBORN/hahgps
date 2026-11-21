package ru.hahgps.service;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.item.ItemFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Вид брошенного iTrak: игрок видит летящий сам предмет, а не снежок.
 *
 * Физику и попадания по-прежнему считает снаряд — у него есть гравитация,
 * столкновения с блоками и сущностями и событие попадания. Снаряд скрыт от
 * игроков, а рядом с ним каждый тик ставится {@link ItemDisplay} с тем же
 * предметом: он и летит на экране, кувыркаясь.
 */
public final class ThrownTrackerVisuals {

    /** Сколько тиков живёт «летящий» предмет, если снаряд так ни во что не попал. */
    private static final int MAX_AGE_TICKS = 20 * 20;
    /** Поворот предмета за тик — то самое кувыркание брошенного предмета. */
    private static final float SPIN_PER_TICK = 24f;

    private static final class Flying {
        private final Projectile projectile;
        private final ItemDisplay display;
        private int age;

        private Flying(Projectile projectile, ItemDisplay display) {
            this.projectile = projectile;
            this.display = display;
        }
    }

    private final HahGpsPlugin plugin;
    private final Map<UUID, Flying> flying = new ConcurrentHashMap<>();
    private BukkitTask task;

    public ThrownTrackerVisuals(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Показать летящий предмет вместо снаряда. Снаряд уже должен существовать. */
    public void show(Projectile projectile, ItemStack item) {
        if (projectile == null || projectile.isDead() || item == null) {
            return;
        }
        hideCarrier(projectile);
        ItemDisplay display = spawn(projectile.getLocation(), item, projectile.getVelocity());
        if (display == null) {
            // Не смогли показать предмет — лучше видимый снаряд, чем пустота.
            showCarrier(projectile);
            return;
        }
        flying.put(projectile.getUniqueId(), new Flying(projectile, display));
        startTask();
    }

    /** Снаряд попал или исчез: убрать предмет сразу, не дожидаясь тика. */
    public void finish(Projectile projectile) {
        if (projectile == null) {
            return;
        }
        remove(flying.remove(projectile.getUniqueId()));
    }

    /** Убрать все летящие предметы (выключение плагина, перезагрузка). */
    public void dispose() {
        stopTask();
        for (UUID id : new ArrayList<>(flying.keySet())) {
            remove(flying.remove(id));
        }
    }

    private void hideCarrier(Projectile projectile) {
        try {
            World world = projectile.getWorld();
            for (Player viewer : world.getPlayers()) {
                viewer.hideEntity(plugin, projectile);
            }
        } catch (Throwable ignored) {
            // Старое ядро или чужой загрузчик: снаряд просто останется видимым.
        }
    }

    private void showCarrier(Projectile projectile) {
        try {
            World world = projectile.getWorld();
            for (Player viewer : world.getPlayers()) {
                viewer.showEntity(plugin, projectile);
            }
        } catch (Throwable ignored) {
        }
    }

    private ItemDisplay spawn(Location at, ItemStack item, Vector velocity) {
        World world = at.getWorld();
        if (world == null) {
            return null;
        }
        try {
            return world.spawn(at, ItemDisplay.class, display -> {
                display.setItemStack(item);
                // GROUND — так предмет рисуется «плашмя», как выброшенный из рук.
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
                display.setBillboard(Display.Billboard.FIXED);
                display.setPersistent(false);
                display.setInvulnerable(true);
                display.setGravity(false);
                display.setSilent(true);
                display.setTeleportDuration(1);
                display.setInterpolationDuration(1);
                display.setRotation(yawOf(velocity), pitchOf(velocity));
            });
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "HahGps: не удалось показать летящий iTrak", t);
            return null;
        }
    }

    private void startTask() {
        if (task != null) {
            return;
        }
        try {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sync, 1L, 1L);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "HahGps: не удалось запустить анимацию броска", t);
        }
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

    private void sync() {
        try {
            if (flying.isEmpty()) {
                stopTask();
                return;
            }
            List<UUID> gone = new ArrayList<>();
            List<Flying> expired = new ArrayList<>();
            for (Map.Entry<UUID, Flying> entry : flying.entrySet()) {
                Flying fly = entry.getValue();
                if (!fly.projectile.isValid() || fly.display == null || !fly.display.isValid()) {
                    gone.add(entry.getKey());
                    continue;
                }
                if (++fly.age > MAX_AGE_TICKS) {
                    expired.add(fly);
                    gone.add(entry.getKey());
                    continue;
                }
                try {
                    fly.display.teleport(fly.projectile.getLocation());
                    fly.display.setRotation((fly.age * SPIN_PER_TICK) % 360f,
                            pitchOf(fly.projectile.getVelocity()));
                } catch (Throwable t) {
                    gone.add(entry.getKey());
                }
            }
            for (UUID id : gone) {
                remove(flying.remove(id));
            }
            // Снаряд, который так ни во что и не попал, раньше просто исчезал
            // без события попадания: трекер пропадал вместе с предметом.
            // Возвращаем предмет туда, где снаряд закончил полёт.
            for (Flying fly : expired) {
                refund(fly.projectile);
            }
        } catch (Throwable t) {
            // Одна ошибка не должна навсегда убивать анимацию и бросать
            // висящие ItemDisplay в мире: чистим показанное и даём задаче
            // перезапуститься при следующем броске.
            plugin.getLogger().log(Level.FINE, "HahGps: анимация броска упала", t);
            dispose();
        }
    }

    /** Предмет обратно: брошенный трекер не должен исчезать молча. */
    private void refund(Projectile projectile) {
        try {
            ItemFactory.TrackerSpec spec = ItemFactory.readSpec(projectile);
            World world = projectile.getWorld();
            Location at = projectile.getLocation();
            projectile.remove();
            if (spec != null && world != null && at != null) {
                world.dropItemNaturally(at, ItemFactory.trackerItem(spec));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void remove(Flying fly) {
        if (fly == null || fly.display == null) {
            return;
        }
        try {
            if (!fly.display.isDead()) {
                fly.display.remove();
            }
        } catch (Throwable ignored) {
        }
    }

    private static float yawOf(Vector velocity) {
        if (velocity == null) {
            return 0f;
        }
        double x = velocity.getX();
        double z = velocity.getZ();
        if (x * x + z * z < 1.0e-6) {
            return 0f;
        }
        return (float) Math.toDegrees(Math.atan2(x, z));
    }

    private static float pitchOf(Vector velocity) {
        if (velocity == null) {
            return 0f;
        }
        double length = velocity.length();
        if (length < 1.0e-6) {
            return 0f;
        }
        double pitch = -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, velocity.getY() / length))));
        return (float) Math.max(-90.0, Math.min(90.0, pitch));
    }
}
