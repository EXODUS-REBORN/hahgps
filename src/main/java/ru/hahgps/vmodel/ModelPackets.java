package ru.hahgps.vmodel;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.hahgps.HahGpsPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Живые отображения моделей: одна сущность {@link ItemDisplay} на поставленную модель.
 *
 * <p>Видимость — персональная: сервер сам решает, кто модель видит ({@code showEntity} /
 * {@code hideEntity}), поэтому дальние модели не висят у всех в мире. Как и в оригинале
 * (EXODUS-OS, {@code os.exodus.bridge.vmodel.ModelPackets}), здесь нет ни одного пакета NMS:
 * только штатный API Paper.</p>
 */
public final class ModelPackets {

    public static final Color GLOW_YELLOW = Color.fromRGB(255, 214, 40);
    public static final Color GLOW_GREEN = Color.fromRGB(64, 230, 90);
    public static final Color GLOW_RED = Color.fromRGB(255, 56, 56);

    private static final Map<UUID, ItemDisplay> LIVE = new HashMap<>();
    private static Plugin plugin;
    private static NamespacedKey placedKey;

    private ModelPackets() {
    }

    public static void bind(HahGpsPlugin host) {
        plugin = host;
        placedKey = new NamespacedKey(host, "vmodel_placed_id");
        dropLegacyHits(host);
    }

    static boolean bound() {
        return placedKey != null;
    }

    public static NamespacedKey placedKey() {
        return placedKey;
    }

    public static UUID idOf(Entity entity) {
        if (entity == null || placedKey == null) {
            return null;
        }
        String raw = entity.getPersistentDataContainer().get(placedKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException broken) {
            return null;
        }
    }

    static void mark(Entity entity, UUID id) {
        if (entity != null && placedKey != null && id != null) {
            entity.getPersistentDataContainer().set(placedKey, PersistentDataType.STRING, id.toString());
        }
    }

    public static void spawn(Player player, PlacedModel placed, ModelDefinition def) {
        ItemDisplay display = ensure(placed, def);
        if (plugin == null || display == null || player == null) {
            return;
        }
        try {
            player.showEntity(plugin, display);
        } catch (RuntimeException error) {
            warn("показ модели игроку", error);
        }
    }

    public static void hideFrom(Player player, PlacedModel placed) {
        if (plugin == null || player == null || placed == null) {
            return;
        }
        Entity entity = LIVE.get(placed.id());
        if (entity == null || !entity.isValid()) {
            return;
        }
        try {
            player.hideEntity(plugin, entity);
        } catch (RuntimeException error) {
            warn("скрытие модели", error);
        }
    }

    public static void drop(UUID placedId) {
        remove(LIVE.remove(placedId));
    }

    public static void dropAll() {
        for (ItemDisplay display : new ArrayList<>(LIVE.values())) {
            remove(display);
        }
        LIVE.clear();
    }

    public static void pruneUnused(Set<UUID> keep) {
        for (UUID id : new ArrayList<>(LIVE.keySet())) {
            if (!keep.contains(id)) {
                remove(LIVE.remove(id));
            }
        }
    }

    public static ItemDisplay live(UUID placedId) {
        return placedId == null ? null : LIVE.get(placedId);
    }

    public static ItemDisplay ensure(PlacedModel placed, ModelDefinition def) {
        if (placed == null || def == null) {
            return null;
        }
        ItemDisplay current = LIVE.get(placed.id());
        if (current != null && current.isValid()) {
            Location location = locationOf(placed);
            if (location != null && current.getLocation().distanceSquared(location) > 0.0001) {
                current.teleport(location);
            }
            return current;
        }
        LIVE.remove(placed.id());
        Location location = locationOf(placed);
        if (location == null || location.getWorld() == null) {
            return null;
        }
        try {
            ItemDisplay display = location.getWorld().spawn(location, ItemDisplay.class, spawned -> {
                styleDisplay(spawned, def, false, null);
                mark(spawned, placed.id());
            });
            LIVE.put(placed.id(), display);
            return display;
        } catch (RuntimeException error) {
            warn("создание отображения модели", error);
            return null;
        }
    }

    public static ItemDisplay spawnGhost(Player player, Location location, ModelDefinition def, Color glow) {
        if (location == null || location.getWorld() == null || plugin == null || def == null) {
            return null;
        }
        try {
            ItemDisplay ghost = location.getWorld().spawn(location, ItemDisplay.class, spawned -> {
                styleDisplay(spawned, def, true, glow);
                spawned.setVisibleByDefault(false);
            });
            if (player != null) {
                player.showEntity(plugin, ghost);
            }
            return ghost;
        } catch (RuntimeException error) {
            warn("призрак модели", error);
            return null;
        }
    }

    static void styleDisplay(ItemDisplay spawned, ModelDefinition def, boolean glowing, Color glow) {
        spawned.setPersistent(false);
        spawned.setGravity(false);
        spawned.setInvulnerable(true);
        spawned.setSilent(true);
        spawned.setVisibleByDefault(true);
        spawned.setBillboard(Display.Billboard.FIXED);
        spawned.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        spawned.setTransformation(new Transformation(
                new Vector3f(),
                new Quaternionf(),
                new Vector3f(1f, 1f, 1f),
                new Quaternionf()));
        spawned.setInterpolationDuration(0);
        spawned.setTeleportDuration(0);
        spawned.setViewRange(16f);
        spawned.setDisplayWidth(2f);
        spawned.setDisplayHeight(2f);
        spawned.setShadowRadius(0);
        spawned.setShadowStrength(0);
        spawned.setBrightness(new Display.Brightness(15, 15));
        spawned.setItemStack(modelItem(def));
        spawned.setGlowing(glowing);
        if (glow != null) {
            spawned.setGlowColorOverride(glow);
        }
    }

    static void applyGhost(ItemDisplay ghost, Location location, ModelDefinition def, Color glow) {
        if (ghost == null || !ghost.isValid() || def == null) {
            return;
        }
        ghost.setItemStack(modelItem(def));
        ghost.setGlowing(true);
        ghost.setGlowColorOverride(glow);
        if (location != null) {
            ghost.teleport(location);
        }
    }

    public static void remove(Entity entity) {
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    public static Location locationOf(PlacedModel placed) {
        if (placed == null) {
            return null;
        }
        World world = Bukkit.getWorld(placed.world());
        if (world == null) {
            return null;
        }
        return new Location(world, placed.x(), placed.y(), placed.z(), placed.yaw(), placed.pitch());
    }

    public static ItemStack modelItem(ModelDefinition def) {
        Material material = Material.matchMaterial(def == null ? "" : def.displayItem());
        if (material == null || material.isAir()) {
            material = Material.PAPER;
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            applyLooks(meta, def);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static void applyLooks(ItemMeta meta, ModelDefinition def) {
        if (meta == null || def == null || def.customModelData() <= 0) {
            return;
        }
        try {
            CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
            cmd.setFloats(List.of((float) def.customModelData()));
            meta.setCustomModelDataComponent(cmd);
        } catch (RuntimeException fallback) {
            try {
                meta.setCustomModelData(def.customModelData());
            } catch (RuntimeException broken) {
                warn("CustomModelData модели " + def.key(), broken);
            }
        }
    }

    /**
     * Сносит сущности-хитбоксы старого способа (Interaction) и всё, что осталось от прошлой
     * версии: у наших сущностей стоит ключ {@code vmodel_placed_id}, его и ищем.
     */
    public static void dropLegacyHits(HahGpsPlugin host) {
        if (host == null) {
            return;
        }
        NamespacedKey key = new NamespacedKey(host, "vmodel_placed_id");
        for (World world : Bukkit.getWorlds()) {
            for (Interaction hit : world.getEntitiesByClass(Interaction.class)) {
                if (hit.getPersistentDataContainer().has(key, PersistentDataType.STRING)) {
                    hit.remove();
                }
            }
        }
    }

    private static void warn(String what, Throwable error) {
        if (plugin != null) {
            plugin.getLogger().log(Level.FINE, "HahGps: модели — " + what, error);
        }
    }
}
