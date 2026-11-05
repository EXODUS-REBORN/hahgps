package ru.hahgps.item;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;
import ru.hahgps.HahGpsPlugin;
import org.bukkit.entity.Player;
import ru.hahgps.gui.GuiTheme;
import ru.hahgps.model.Tracker;
import ru.hahgps.model.TrackerColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
    public static final NamespacedKey TRACKER_CHARGE = NamespacedKey.fromString("hahgps:tracker_charge");
    public static final NamespacedKey LOCATOR = NamespacedKey.fromString("hahgps:itrak_locator");
    public static final NamespacedKey CHARGE = NamespacedKey.fromString("hahgps:itrak_charge");
    public static final NamespacedKey TERMINAL = NamespacedKey.fromString("hahgps:itrak_terminal");
    public static final NamespacedKey THROWER = NamespacedKey.fromString("hahgps:itrak_thrower");

    /**
     * Материал локатора. Намеренно не {@link Material#COMPASS}: компас — это
     * «навигационная палочка» WorldEdit (по умолчанию), и её ПКМ/ЛКМ
     * телепортируют игрока к блоку под прицелом — то есть внутрь земли, если
     * смотреть вниз. С {@link Material#RECOVERY_COMPASS} клик локатором больше
     * ни с чем не конфликтует.
     */
    public static final Material LOCATOR_MATERIAL = Material.RECOVERY_COMPASS;

    /** Старый локатор-компас: работает, но подменяется на актуальный при клике. */
    public static final Material LEGACY_LOCATOR_MATERIAL = Material.COMPASS;

    /** То, что записано в предмет-трекер до его установки (и переносится с ним). */
    public record TrackerSpec(String code, String name, TrackerColor color, boolean waterproof, int charge) {

        public TrackerSpec {
            charge = Math.max(0, Math.min(Tracker.CHARGE_MAX, charge));
        }

        /** Свежий трекер: полный заряд. */
        public TrackerSpec(String code, String name, TrackerColor color, boolean waterproof) {
            this(code, name, color, waterproof, Tracker.CHARGE_MAX);
        }
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
            lore.add(GuiTheme.mm("<gray>" + GuiTheme.escapeForMini(message("itrak-item-charge", "Заряд"))
                    + ": <#8fc6ff><bold>" + spec.charge() + "%</bold></#8fc6ff></gray>"));
            lore.add(GuiTheme.mm("<#66aaff>→</#66aaff> <gray>"
                    + GuiTheme.escapeForMini(message("itrak-item-hint",
                    "ПКМ по блоку — прицепить, ПКМ в воздух — бросить")) + "</gray>"));
            lore.add(GuiTheme.mm("<gray><italic>"
                    + GuiTheme.escapeForMini(message("itrak-item-remove-hint",
                    "ПКМ пустой рукой — снять")) + "</italic></gray>"));
            meta.lore(lore);

            meta.setCustomModelData(ItemIds.tracker(color, spec.waterproof()));
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(TRACKER_CODE, PersistentDataType.STRING, spec.code());
            data.set(TRACKER_COLOR, PersistentDataType.STRING, color.getKey());
            data.set(TRACKER_WATERPROOF, PersistentDataType.BYTE, (byte) (spec.waterproof() ? 1 : 0));
            data.set(TRACKER_CHARGE, PersistentDataType.INTEGER, spec.charge());
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
        ItemStack item = new ItemStack(LOCATOR_MATERIAL);
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
        return meta == null ? null : readSpec(meta);
    }

    /** Данные трекера из любого носителя PDC: предмета или брошенного снаряда. */
    public static TrackerSpec readSpec(PersistentDataHolder holder) {
        if (holder == null) {
            return null;
        }
        PersistentDataContainer data = holder.getPersistentDataContainer();
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
        Integer charge = data.get(TRACKER_CHARGE, PersistentDataType.INTEGER);
        return new TrackerSpec(code, label == null ? "" : label, color,
                waterproof != null && waterproof != 0,
                charge == null ? Tracker.CHARGE_MAX : charge);
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

    /** Старый локатор (компас) — подменяется на {@link #LOCATOR_MATERIAL} при первом клике. */
    public static boolean isLegacyLocator(ItemStack item) {
        return item != null && item.getType() == LEGACY_LOCATOR_MATERIAL && isLocator(item);
    }

    /** Любой наш предмет iTrak: локатор, терминал, заряд или трекер. */
    public static boolean isItrakItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        return isLocator(item) || isTerminalItem(item) || isChargeItem(item) || readTracker(item) != null;
    }

    /** Заряд: восстанавливает tracker'у часть заряда. */
    public static ItemStack chargeItem(int amount) {
        ItemStack item = new ItemStack(Material.REDSTONE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(GuiTheme.mm("<bold>" + GuiTheme.gradient("iTrak")
                    + "</bold> <#8fc6ff>" + GuiTheme.escapeForMini(
                    message("itrak-charge-item-name", "Заряд")) + "</#8fc6ff>"));
            List<Component> lore = new ArrayList<>();
            lore.add(GuiTheme.mm("<gray>" + GuiTheme.escapeForMini(
                    message("itrak-charge-item-lore", "Восстанавливает заряд трекера"))
                    + ": <#8fc6ff><bold>+" + amount + "%</bold></#8fc6ff></gray>"));
            lore.add(GuiTheme.mm("<#66aaff>→</#66aaff> <gray>" + GuiTheme.escapeForMini(
                    message("itrak-charge-item-hint", "ПКМ по трекеру — вставить")) + "</gray>"));
            meta.lore(lore);
            meta.setCustomModelData(ItemIds.ITRAK_CHARGE);
            meta.getPersistentDataContainer().set(CHARGE, PersistentDataType.INTEGER, Math.max(1, amount));
            item.setItemMeta(meta);
        }
        return GuiTheme.glow(item);
    }

    /** Сколько процентов даёт предмет-заряд (минимум 1). */
    public static int chargeAmount(ItemStack item) {
        if (item == null || item.getType() != Material.REDSTONE) {
            return 0;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        Integer amount = meta.getPersistentDataContainer().get(CHARGE, PersistentDataType.INTEGER);
        return amount == null ? 0 : Math.max(1, amount);
    }

    public static boolean isChargeItem(ItemStack item) {
        return chargeAmount(item) > 0;
    }

    /** Терминал iTrak — предмет-блок: ставится как лодестоун и получает координаты рядом. */
    public static ItemStack terminalItem() {
        ItemStack item = new ItemStack(Material.LODESTONE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(GuiTheme.mm("<bold>" + GuiTheme.gradient("iTrak")
                    + "</bold> <#8fc6ff>" + GuiTheme.escapeForMini(
                    message("itrak-terminal-item-name", "Терминал")) + "</#8fc6ff>"));
            List<Component> lore = new ArrayList<>();
            lore.add(GuiTheme.mm("<gray>" + GuiTheme.escapeForMini(
                    message("itrak-terminal-item-lore", "Показывает координаты трекеров рядом"))
                    + "</gray>"));
            lore.add(GuiTheme.mm("<#66aaff>→</#66aaff> <gray>" + GuiTheme.escapeForMini(
                    message("itrak-terminal-item-hint", "ПКМ по блоку — установить")) + "</gray>"));
            meta.lore(lore);
            meta.setCustomModelData(ItemIds.ITRAK_TERMINAL);
            meta.getPersistentDataContainer().set(TERMINAL, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return GuiTheme.glow(item);
    }

    public static boolean isTerminalItem(ItemStack item) {
        if (item == null || item.getType() != Material.LODESTONE) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte marker = meta.getPersistentDataContainer().get(TERMINAL, PersistentDataType.BYTE);
        return marker != null && marker != 0;
    }

    /** Записывает данные трекера в носитель PDC (снаряд броска). */
    public static void writeSpec(PersistentDataHolder holder, TrackerSpec spec, UUID thrower) {
        if (holder == null || spec == null) {
            return;
        }
        PersistentDataContainer data = holder.getPersistentDataContainer();
        data.set(TRACKER_CODE, PersistentDataType.STRING, spec.code());
        data.set(TRACKER_COLOR, PersistentDataType.STRING,
                (spec.color() == null ? TrackerColor.BLUE : spec.color()).getKey());
        data.set(TRACKER_WATERPROOF, PersistentDataType.BYTE, (byte) (spec.waterproof() ? 1 : 0));
        data.set(TRACKER_CHARGE, PersistentDataType.INTEGER, spec.charge());
        if (spec.name() != null && !spec.name().isBlank()) {
            data.set(TRACKER_LABEL, PersistentDataType.STRING, spec.name().trim());
        }
        if (thrower != null) {
            data.set(THROWER, PersistentDataType.STRING, thrower.toString());
        }
    }

    /** Кто бросил трекер (UUID) — из PDC снаряда. */
    public static UUID readThrower(PersistentDataHolder holder) {
        if (holder == null) {
            return null;
        }
        String raw = holder.getPersistentDataContainer().get(THROWER, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** Выдать предмет игроку: что не влезло — падает под ноги, а не теряется. */
    public static void give(Player player, ItemStack item) {
        if (player == null || item == null) {
            return;
        }
        player.getInventory().addItem(item).values()
                .forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
    }

    /** Убирает один предмет-заряд из инвентаря. @return true, если заряд был */
    public static boolean consumeChargeItem(Player player) {
        return consumeMatching(player, ItemFactory::isChargeItem);
    }

    /** Убирает один предмет терминала из инвентаря. */
    public static boolean consumeTerminalItem(Player player) {
        return consumeMatching(player, ItemFactory::isTerminalItem);
    }

    /** Убирает один предмет-трекер с этим кодом из инвентаря. */
    public static boolean consumeTrackerItem(Player player, String code) {
        return consumeMatching(player, item -> {
            TrackerSpec spec = readTracker(item);
            return spec != null && spec.code().equals(code);
        });
    }

    private static boolean consumeMatching(Player player, java.util.function.Predicate<ItemStack> matcher) {
        if (player == null) {
            return false;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getAmount() <= 0 || !matcher.test(stack)) {
                continue;
            }
            stack.setAmount(stack.getAmount() - 1);
            return true;
        }
        return false;
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
