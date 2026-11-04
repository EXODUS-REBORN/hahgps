package ru.hahgps.item;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.gui.GuiTheme;
import ru.hahgps.model.TrackerColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Предметы iTrak: сам трекер и локатор.
 *
 * Данные трекера лежат в {@link PersistentDataContainer}, а не только в имени:
 * имя игрок может переименовать в наковальне, а код, цвет и водозащита должны
 * пережить это без потерь. Кастомный ID модели ставится каждому предмету —
 * под будущий ресурспак (как и у остальных предметов плагина, см. {@link ItemIds}).
 */
public final class ItemFactory {

    private ItemFactory() {
    }

    public static final NamespacedKey TRACKER_CODE = NamespacedKey.fromString("hahgps:tracker_code");
    public static final NamespacedKey TRACKER_COLOR = NamespacedKey.fromString("hahgps:tracker_color");
    public static final NamespacedKey TRACKER_WATERPROOF = NamespacedKey.fromString("hahgps:tracker_waterproof");
    public static final NamespacedKey TRACKER_LABEL = NamespacedKey.fromString("hahgps:tracker_label");
    public static final NamespacedKey LOCATOR = NamespacedKey.fromString("hahgps:itrak_locator");

    /** То, что записано в предмет-трекер до его установки. */
    public record TrackerSpec(String code, String name, TrackerColor color, boolean waterproof) {
    }

    public static ItemStack trackerItem(TrackerSpec spec) {
        TrackerColor color = spec.color() == null ? TrackerColor.BLUE : spec.color();
        ItemStack item = new ItemStack(color.getMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String colorName = message(color.messageKey(), color.getKey());
            StringBuilder display = new StringBuilder();
            display.append("<bold><color:").append(color.hex()).append(">iTrak</color></bold>");
            display.append(" <color:").append(color.hex()).append(">").append(GuiTheme.escapeForMini(colorName))
                    .append("</color>");
            if (spec.name() != null && !spec.name().isBlank()) {
                display.append(" <dark_gray>·</dark_gray> <white>")
                        .append(GuiTheme.escapeForMini(spec.name().trim())).append("</white>");
            }
            meta.displayName(GuiTheme.mm(display.toString()));

            String type = spec.waterproof()
                    ? message("itrak-type-waterproof", "водонепроницаемый")
                    : message("itrak-type-regular", "обычный");
            List<Component> lore = new ArrayList<>();
            lore.add(GuiTheme.mm("<gray>" + GuiTheme.escapeForMini(message("itrak-item-code", "Код"))
                    + ": <white><bold>" + spec.code() + "</bold></white></gray>"));
            lore.add(GuiTheme.mm("<gray>" + GuiTheme.escapeForMini(message("itrak-item-type", "Тип"))
                    + ": <white>" + GuiTheme.escapeForMini(type) + "</white></gray>"));
            lore.add(GuiTheme.mm("<#66aaff>→</#66aaff> <gray>"
                    + GuiTheme.escapeForMini(message("itrak-item-hint",
                    "ПКМ по блоку или существу — прицепить")) + "</gray>"));
            meta.lore(lore);

            meta.setCustomModelData(ItemIds.tracker(color, spec.waterproof()));
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(TRACKER_CODE, PersistentDataType.STRING, spec.code());
            data.set(TRACKER_COLOR, PersistentDataType.STRING, color.getKey());
            data.set(TRACKER_WATERPROOF, PersistentDataType.BYTE, (byte) (spec.waterproof() ? 1 : 0));
            if (spec.name() != null && !spec.name().isBlank()) {
                data.set(TRACKER_LABEL, PersistentDataType.STRING, spec.name().trim());
            }
            item.setItemMeta(meta);
        }
        if (spec.waterproof()) {
            GuiTheme.glow(item);
        }
        return item;
    }

    public static ItemStack locatorItem() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(GuiTheme.mm("<bold>" + GuiTheme.gradient("iTrak")
                    + "</bold> <#8fc6ff>" + GuiTheme.escapeForMini(message("itrak-locator-name", "Локатор"))
                    + "</#8fc6ff>"));
            List<Component> lore = new ArrayList<>();
            lore.add(GuiTheme.mm("<gray>" + GuiTheme.escapeForMini(
                    message("itrak-locator-lore", "Ищет трекеры поблизости")) + "</gray>"));
            lore.add(GuiTheme.mm("<#66aaff>→</#66aaff> <gray>" + GuiTheme.escapeForMini(
                    message("itrak-locator-hint", "ПКМ — просканировать")) + "</gray>"));
            meta.lore(lore);
            meta.setCustomModelData(ItemIds.ITRAK_LOCATOR);
            meta.getPersistentDataContainer().set(LOCATOR, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return GuiTheme.glow(item);
    }

    /** Данные трекера из предмета или null, если предмет — не трекер. */
    public static TrackerSpec readTracker(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        String code = data.get(TRACKER_CODE, PersistentDataType.STRING);
        if (code == null || code.isBlank()) {
            return null;
        }
        TrackerColor color = TrackerColor.parse(data.get(TRACKER_COLOR, PersistentDataType.STRING));
        if (color == null) {
            color = TrackerColor.BLUE;
        }
        Byte waterproof = data.get(TRACKER_WATERPROOF, PersistentDataType.BYTE);
        String label = data.get(TRACKER_LABEL, PersistentDataType.STRING);
        return new TrackerSpec(code, label == null ? "" : label, color, waterproof != null && waterproof != 0);
    }

    public static boolean isLocator(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte marker = meta.getPersistentDataContainer().get(LOCATOR, PersistentDataType.BYTE);
        return marker != null && marker != 0;
    }

    /** Код нового трекера: TRK-1A2B. */
    public static String newCode() {
        String hex = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000))
                .toUpperCase(Locale.ROOT);
        return "TRK-" + "0".repeat(4 - hex.length()) + hex;
    }

    private static String message(String key, String fallback) {
        try {
            HahGpsPlugin plugin = HahGpsPlugin.getInstance();
            if (plugin != null && plugin.getMessages() != null) {
                String value = plugin.getMessages().get(key);
                if (value != null && !value.startsWith("<#3f86ff>Missing:")) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}
