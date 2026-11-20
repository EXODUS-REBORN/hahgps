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
import ru.hahgps.model.TrackerColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

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
    /** Радиус поиска конкретного локатора: у каждого предмета свой. */
    public static final NamespacedKey LOCATOR_RADIUS = NamespacedKey.fromString("hahgps:itrak_locator_radius");
    public static final NamespacedKey TERMINAL = NamespacedKey.fromString("hahgps:itrak_terminal");
    public static final NamespacedKey THROWER = NamespacedKey.fromString("hahgps:itrak_thrower");
    /** Shift-бросок: попав в воду, трекер тонет, а не остаётся на поверхности. */
    public static final NamespacedKey SINK = NamespacedKey.fromString("hahgps:itrak_sink");

    /** Граница «дальнего» локатора: от 30 блоков у него отдельный ID модели. */
    public static final int LONG_LOCATOR_RADIUS = 30;

    /** Радиусы локаторов до 1.3.1 — для одноразового обновления предметов. */
    public static final int LEGACY_LOCATOR_RADIUS = 10;
    public static final int LEGACY_LONG_LOCATOR_RADIUS = 15;

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
    public record TrackerSpec(String code, String name, TrackerColor color, boolean waterproof) {
    }

    public static ItemStack trackerItem(TrackerSpec spec) {
        TrackerColor color = spec.color() == null ? TrackerColor.BLUE : spec.color();
        ItemStack item = new ItemStack(color.getMaterial(), 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String colorName = message(color.messageKey(), color.getKey());
            String label = spec.name() == null ? "" : GuiTheme.clipPlain(spec.name(), GuiTheme.NAME_MAX);
            StringBuilder display = new StringBuilder();
            // Пробелы — внутри цветных тегов: так они не теряются ни в игре,
            // ни при разборе подсказки внешними инструментами.
            display.append("<bold><color:").append(color.hex()).append(">iTrak </color></bold>");
            display.append("<color:").append(color.hex()).append(">").append(GuiTheme.escapeForMini(colorName))
                    .append("</color>");
            if (!label.isBlank()) {
                display.append(" <dark_gray>·</dark_gray> <white>")
                        .append(GuiTheme.escapeForMini(label)).append("</white>");
            }
            meta.displayName(GuiTheme.mm(display.toString()));

            String type = spec.waterproof()
                    ? message("itrak-type-waterproof", "водонепроницаемый")
                    : message("itrak-type-regular", "обычный");
            // Компактно: код и тип в одной строке, дальше — по одной подсказке
            // на действие. Длинная строка «ПКМ по блоку …, ПКМ в воздух …»
            // разваливалась на два переноса и читалась хуже.
            List<Component> lore = new ArrayList<>();
            lore.add(GuiTheme.mm("<dark_gray>" + GuiTheme.escapeForMini(message("itrak-item-code", "Код"))
                    + ": </dark_gray><white>" + GuiTheme.escapeForMini(spec.code()) + "</white>"
                    + "<dark_gray> · </dark_gray><#8fc6ff>" + GuiTheme.escapeForMini(type) + "</#8fc6ff>"));
            lore.add(GuiTheme.mm("<#66aaff>▸ </#66aaff><gray>"
                    + GuiTheme.escapeForMini(message("itrak-item-hint-attach",
                    "ПКМ по блоку — прицепить")) + "</gray>"));
            lore.add(GuiTheme.mm("<#66aaff>▸ </#66aaff><gray>"
                    + GuiTheme.escapeForMini(message("itrak-item-hint-throw",
                    "ПКМ в воздух — бросить")) + "</gray>"));
            lore.add(GuiTheme.mm("<#66aaff>▸ </#66aaff><gray>"
                    + GuiTheme.escapeForMini(message("itrak-item-hint-remove",
                    "ПКМ пустой рукой — снять")) + "</gray>"));
            meta.lore(lore);

            meta.setCustomModelData(ItemIds.tracker(color, spec.waterproof()));
            meta.setMaxStackSize(1);
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(TRACKER_CODE, PersistentDataType.STRING, spec.code());
            data.set(TRACKER_COLOR, PersistentDataType.STRING, color.getKey());
            data.set(TRACKER_WATERPROOF, PersistentDataType.BYTE, (byte) (spec.waterproof() ? 1 : 0));
            if (!label.isBlank()) {
                data.set(TRACKER_LABEL, PersistentDataType.STRING, label);
            }
            item.setItemMeta(meta);
        }
        if (spec.waterproof()) {
            GuiTheme.glow(item);
        }
        return item;
    }

    /**
     * Переименовать предмет-трекер, не меняя код, цвет и водозащиту.
     * Пустое имя убирает подпись: трекер снова называется кодом.
     *
     * @return новый предмет или null, если предмет — не трекер
     */
    public static ItemStack renamedTracker(ItemStack item, String name) {
        TrackerSpec spec = readTracker(item);
        if (spec == null) {
            return null;
        }
        return trackerItem(new TrackerSpec(spec.code(), name, spec.color(), spec.waterproof()));
    }

    /** Локатор со стандартным радиусом из конфига. */
    public static ItemStack locatorItem() {
        return locatorItem(defaultLocatorRadius());
    }

    /**
     * Локатор с конкретным радиусом поиска.
     *
     * Радиус живёт в PDC, а не только в подписи: у сервера может быть несколько
     * версий локатора (обычный и дальний), и сканирование должно брать радиус
     * из предмета в руке, а не из общего конфига.
     */
    public static ItemStack locatorItem(int radius) {
        int value = normalizeLocatorRadius(radius);
        ItemStack item = new ItemStack(LOCATOR_MATERIAL, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = message("itrak-locator-name", "Локатор").replace("{radius}", String.valueOf(value));
            meta.displayName(GuiTheme.mm("<bold>" + GuiTheme.gradient("iTrak")
                    + "</bold> <#8fc6ff>" + GuiTheme.escapeForMini(name)
                    + " <dark_gray>·</dark_gray> " + value + "</#8fc6ff>"));
            List<Component> lore = new ArrayList<>();
            lore.add(GuiTheme.mm("<gray>" + GuiTheme.escapeForMini(
                    message("itrak-locator-lore", "Ищет трекеры поблизости")) + "</gray>"));
            lore.add(GuiTheme.mm("<gray>" + GuiTheme.escapeForMini(
                    message("itrak-locator-radius", "Радиус поиска: {radius} блоков")
                            .replace("{radius}", String.valueOf(value))) + "</gray>"));
            lore.add(GuiTheme.mm("<#66aaff>→</#66aaff> <gray>" + GuiTheme.escapeForMini(
                    message("itrak-locator-hint", "ПКМ — просканировать")) + "</gray>"));
            meta.lore(lore);
            meta.setMaxStackSize(1);
            meta.setCustomModelData(value >= LONG_LOCATOR_RADIUS
                    ? ItemIds.ITRAK_LOCATOR_LONG : ItemIds.ITRAK_LOCATOR);
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(LOCATOR, PersistentDataType.BYTE, (byte) 1);
            data.set(LOCATOR_RADIUS, PersistentDataType.INTEGER, value);
            item.setItemMeta(meta);
        }
        return GuiTheme.glow(item);
    }

    /** Радиус из предмета-локатора; 0 — у старого локатора без радиуса. */
    public static int readLocatorRadius(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return 0;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        Integer radius = meta.getPersistentDataContainer().get(LOCATOR_RADIUS, PersistentDataType.INTEGER);
        if (radius == null || radius <= 0) {
            return 0;
        }
        return Math.min(1000, radius);
    }

    /** Радиус локатора по умолчанию: из конфига, иначе 20. */
    public static int defaultLocatorRadius() {
        try {
            HahGpsPlugin plugin = HahGpsPlugin.getInstance();
            if (plugin != null && plugin.getConfigManager() != null) {
                return (int) plugin.getConfigManager().getItrakScanRadius();
            }
        } catch (Throwable ignored) {
        }
        return 20;
    }

    public static int normalizeLocatorRadius(int radius) {
        if (radius <= 0) {
            return defaultLocatorRadius();
        }
        return Math.max(1, Math.min(1000, radius));
    }

    /**
     * Обновить локатор в руке до актуальной версии. Старый компас, предмет без
     * радиуса и локаторы прежних радиусов (10 и 15 блоков) заменяются на
     * нынешние (20 и 30), иначе после обновления плагина в инвентарях
     * оставались бы предметы со старым радиусом.
     *
     * @return новый предмет или null, если менять нечего
     */
    public static ItemStack upgradedLocator(ItemStack item, int normalRadius, int longRadius) {
        if (item == null || item.getType().isAir() || !isLocator(item)) {
            return null;
        }
        int radius = readLocatorRadius(item);
        // Радиус важнее материала: до 1.3.1 компасом были ОБА локатора — и на
        // 10, и на 15 блоков. Раньше проверка материала срабатывала первой, и
        // старый дальний локатор молча превращался в обычный.
        if (radius <= 0 && isLegacyLocator(item)) {
            return locatorItem(normalRadius);
        }
        if (radius <= 0) {
            return locatorItem(normalRadius);
        }
        if (radius == LEGACY_LOCATOR_RADIUS && normalRadius != LEGACY_LOCATOR_RADIUS) {
            return locatorItem(normalRadius);
        }
        if (radius == LEGACY_LONG_LOCATOR_RADIUS && longRadius != LEGACY_LONG_LOCATOR_RADIUS) {
            return locatorItem(longRadius);
        }
        return null;
    }

    /**
     * Актуальный предмет-трекер: материал мог устареть (синий трекер раньше
     * был компасом восстановления, как локатор). Данные в PDC те же.
     *
     * @return новый предмет или null, если материал уже правильный
     */
    public static ItemStack healedTrackerItem(ItemStack item) {
        TrackerSpec spec = readTracker(item);
        if (spec == null) {
            return null;
        }
        TrackerColor color = spec.color() == null ? TrackerColor.BLUE : spec.color();
        if (item.getType() == color.getMaterial()) {
            return null;
        }
        return trackerItem(spec);
    }

    /**
     * Разбор аргумента команды выдачи локатора: число — радиус,
     * {@code long}/{@code дальний} — дальний локатор из конфига.
     * @return радиус или null, если токен — не радиус (например, имя игрока)
     */
    public static Integer parseRadiusToken(String token, int longRadius) {
        if (token == null) {
            return null;
        }
        String value = token.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        if (value.equals("long") || value.equals("дальний") || value.equals("дальнии")) {
            return normalizeLocatorRadius(longRadius);
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 1 ? Math.min(1000, parsed) : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** Тот же трекер, но с новым (гарантированно свободным) кодом. */
    public static TrackerSpec withFreshCode(TrackerSpec spec) {
        return new TrackerSpec(newCode(), spec.name(), spec.color(), spec.waterproof());
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
        return new TrackerSpec(code, label == null ? "" : label, color,
                waterproof != null && waterproof != 0);
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
        return isLocator(item) || isTerminalItem(item) || readTracker(item) != null;
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
            meta.setMaxStackSize(1);
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

    /** Отметить снаряд как «утопить»: Shift-бросок трекера. */
    public static void writeSink(PersistentDataHolder holder, boolean sink) {
        if (holder == null || !sink) {
            return;
        }
        holder.getPersistentDataContainer().set(SINK, PersistentDataType.BYTE, (byte) 1);
    }

    /** Был ли бросок «утопить» (Shift+ПКМ). */
    public static boolean readSink(PersistentDataHolder holder) {
        if (holder == null) {
            return false;
        }
        Byte marker = holder.getPersistentDataContainer().get(SINK, PersistentDataType.BYTE);
        return marker != null && marker != 0;
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

    /**
     * Код нового трекера: порядковый номер из БД ({@code TRK-0000042}).
     * Раньше здесь был случайный четырёхзначный код — с десятков крафтов он
     * совпадал, и второй предмет с тем же кодом не ставился, а локатор
     * продолжал показывать первый. См. {@link TrackerCodes}.
     */
    public static String newCode() {
        return TrackerCodes.next();
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
