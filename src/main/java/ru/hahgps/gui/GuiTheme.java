package ru.hahgps.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Оформление меню в стиле основного плагина hahtojer: синяя палитра
 * (тёмно-синий → синий → бирюза), градиентные заголовки, синие панели фона,
 * единая сетка навигации, свечение активных кнопок и бренд-предмет.
 *
 * <p>Скопировано из {@code ru.newjerseyhah.util.GuiTheme}, чтобы меню HahGPS
 * визуально не отличались от меню основного плагина.</p>
 */
public final class GuiTheme {

    private GuiTheme() {
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Фон меню — как в основном плагине. */
    public static final Material FILLER = Material.BLUE_STAINED_GLASS_PANE;

    /** Палитра: тона синего → голубого от почти чёрно-синего (0) к светло-голубому (9). */
    public static final String[] TONES = {
            "#08122b", // 0 — ночной
            "#0e2450", // 1 — глубокий
            "#14397e", // 2 — тёмно-синий
            "#1d51a8", // 3 — синий
            "#2464c8", // 4 — синий ярче
            "#3f86ff", // 5 — ярко-синий
            "#66aaff", // 6 — голубой
            "#8fc6ff", // 7 — светло-голубой
            "#4fd1e0", // 8 — бирюзовый
            "#8fe9f5"  // 9 — светло-бирюзовый
    };

    public static final String GRADIENT_START = TONES[1];
    public static final String GRADIENT_MID = TONES[4];
    public static final String GRADIENT_TAIL = TONES[9];

    public static Component mm(String mini) {
        return MM.deserialize(mini);
    }

    /** Тон палитры по индексу (индекс не ограничен — считается по кольцу). */
    public static String tone(int index) {
        return TONES[Math.floorMod(index, TONES.length)];
    }

    /** Текст в заданном тоне палитры. */
    public static String toned(int index, String text) {
        return "<color:" + tone(index) + ">" + escapeForMini(text) + "</color>";
    }

    /** Градиент между двумя тонами палитры. */
    public static String gradient(String text, int from, int to) {
        return "<gradient:" + tone(from) + ":" + tone(to) + ">" + escapeForMini(text) + "</gradient>";
    }

    /** Фирменный градиент тёмно-синий → синий → бирюза. */
    public static String gradient(String text) {
        return "<gradient:" + GRADIENT_START + ":" + GRADIENT_MID + ":" + GRADIENT_TAIL + ">"
                + escapeForMini(text) + "</gradient>";
    }

    /** Заголовок меню в фирменном градиенте (как у основного плагина). */
    public static Component gradientTitle(String text) {
        return MM.deserialize("<gradient:" + GRADIENT_START + ":" + GRADIENT_MID + ":" + GRADIENT_TAIL
                + "><bold>" + escapeForMini(text) + "</bold></gradient>");
    }

    private static String escapeForMini(String s) {
        if (s == null) return "";
        return s.replace("<", "\\<").replace(">", "\\>");
    }

    /** Предмет с именем и описанием (имя и лор понимают и §-коды, и MiniMessage). */
    public static ItemStack named(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            if (name != null) m.displayName(text(name));
            if (lore != null && !lore.isEmpty()) {
                List<Component> out = new ArrayList<>(lore.size());
                for (String s : lore) out.add(s == null ? Component.empty() : text(s));
                m.lore(out);
            }
            it.setItemMeta(m);
        }
        return it;
    }

    public static ItemStack named(Material mat, String name) {
        return named(mat, name, List.of());
    }

    /** Текст, понимающий и §-коды, и MiniMessage в одной строке. */
    private static Component text(String s) {
        return MM.deserialize(legacyToMini(s));
    }

    private static String legacyToMini(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00a7' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(i + 1));
                String tag = switch (code) {
                    case '1' -> "dark_blue";
                    case '3' -> "dark_aqua";
                    case '9' -> "blue";
                    case 'b' -> "aqua";
                    case 'f' -> "white";
                    case '7' -> "gray";
                    case '8' -> "dark_gray";
                    case 'c' -> "red";
                    case 'l' -> "bold";
                    case 'o' -> "italic";
                    case 'r' -> "reset";
                    default -> null;
                };
                if (tag != null) {
                    sb.append('<').append(tag).append('>');
                    i++;
                    continue;
                }
            }
            if (c == '<') sb.append("\\<");
            else if (c == '>') sb.append("\\>");
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Свечение активных элементов (навигация, главные кнопки). */
    public static ItemStack glow(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return item;
        ItemMeta m = item.getItemMeta();
        if (m == null) return item;
        try {
            m.setEnchantmentGlintOverride(true);
        } catch (Throwable ignored) {
            m.addEnchant(Enchantment.UNBREAKING, 1, true);
        }
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(m);
        return item;
    }

    /** Панель фона (пустое имя), как в основном плагине. */
    public static ItemStack filler() {
        ItemStack it = new ItemStack(FILLER);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(Component.empty());
            it.setItemMeta(m);
        }
        return it;
    }

    /** Заполнить пустые слоты панелями. */
    public static void fill(Inventory inv) {
        if (inv == null) return;
        ItemStack pane = filler();
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, pane.clone());
        }
    }

    /** Назад (слот 45 в 6-строчных, 18/22 в 3-строчных меню). */
    public static ItemStack navBack(String label) {
        return glow(named(Material.SPECTRAL_ARROW, gradient("◀ " + (label == null ? "Назад" : label), 3, 8)));
    }

    public static ItemStack navForward(String label) {
        return glow(named(Material.SPECTRAL_ARROW, gradient((label == null ? "Вперёд" : label) + " ▶", 3, 8)));
    }

    public static ItemStack navInfo(String... lines) {
        List<String> lore = new ArrayList<>();
        lore.add("§7Нажми, чтобы узнать подробнее");
        if (lines != null) {
            for (String l : lines) lore.add(l);
        }
        return named(Material.LIME_DYE, gradient("Инфо", 4, 9), lore);
    }

    public static ItemStack navClose() {
        return named(Material.BARRIER, "§9Закрыть");
    }

    /** Бренд-предмет в углу меню (не кликается/не выдаётся). */
    public static ItemStack brand(String version) {
        return named(Material.PAPER, gradient("HahGPS"), List.of(
                "§7версия §f" + (version == null ? "" : version),
                "§7для проекта §fNewJersey",
                "§7стиль меню §fhahtojer"));
    }

    /** Клик — как в основном плагине. */
    public static void click(Player p) {
        if (p == null) return;
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.45f, 1.4f);
    }

    public static void page(Player p) {
        if (p == null) return;
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.1f);
    }

    public static void deny(Player p) {
        if (p == null) return;
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
    }

    /** Версия плагина для бренда. */
    public static String version() {
        try {
            var plugin = Bukkit.getPluginManager().getPlugin("HahGps");
            if (plugin == null) plugin = Bukkit.getPluginManager().getPlugin("hahgps");
            return plugin == null ? "1.0.1" : plugin.getPluginMeta().getVersion();
        } catch (Throwable t) {
            return "1.0.1";
        }
    }

    /** Холдер никогда не null — иначе клики в GUI не работают. */
    public static Inventory create(InventoryHolder holder, int size, String title) {
        return Bukkit.createInventory(holder, size, gradientTitle(title));
    }
}
