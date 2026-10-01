package ru.hahgps.gui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
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
 * Оформление меню в стиле основного плагина hahtojer.
 * Палитра — те же 10 тонов GuiTheme.TONES, что и у hahtojer,
 * нейтральные: white — значения, gray — пояснения, dark_gray — разделители,
 * red — только ошибки.
 */
public final class GuiTheme {

    private GuiTheme() {
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final Material FILLER = Material.BLUE_STAINED_GLASS_PANE;

    public static final String ACCENT_COLOR = "\u00a79";
    public static final String VALUE_COLOR = "\u00a7f";

    /** Палитра: тона синего → голубого от почти чёрно-синего (0) к светло-бирюзовому (9). */
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

    /**
     * Ванильные шрифты (ресурспак не нужен). uniform — пиксельный моноширинный
     * с полным покрытием (кириллица, цифры, символы): шапки, заголовки, числа.
     * alt — руны столов зачарований, ТОЛЬКО латиница A-Z/a-z: декоративные
     * акценты (бренд), цифры и кириллица в нём не отрисуются.
     */
    public static final String FONT_UNIFORM = "minecraft:uniform";
    public static final String FONT_ALT = "minecraft:alt";

    /** Текст в заданном ванильном шрифте (готовая MiniMessage-разметка внутри). */
    public static String font(String fontKey, String mini) {
        return "<font:" + fontKey + ">" + mini + "</font>";
    }

    public static Component mm(String mini) {
        return safe(mini);
    }

    /** Тон палитры по индексу (индекс не ограничен — считается по кольцу). */
    public static String tone(int index) {
        return TONES[Math.floorMod(index, TONES.length)];
    }

    /** Тон как MiniMessage-тег: {@code <color:#…>}. */
    public static String toneTag(int index) {
        return "<color:" + tone(index) + ">";
    }

    /** Цвет палитры как {@link TextColor} для Component API. */
    public static TextColor toneColor(int index) {
        return TextColor.fromHexString(tone(index));
    }

    /** Текст в заданном тоне палитры (MiniMessage). */
    public static String toned(int index, String text) {
        return toneTag(index) + escapeForMini(text) + "</color>";
    }

    /** Градиент между двумя тонами палитры. */
    public static String gradient(String text, int from, int to) {
        return "<gradient:" + tone(from) + ":" + tone(to) + ">" + escapeForMini(text) + "</gradient>";
    }

    /** Фирменный градиент тёмно-синий → голубой → бирюзовый. */
    public static String gradient(String text) {
        return "<gradient:" + GRADIENT_START + ":" + GRADIENT_MID + ":" + GRADIENT_TAIL + ">"
                + escapeForMini(text) + "</gradient>";
    }

    /** Заголовок меню: градиент + жирный + моноширинный шрифт uniform. */
    public static Component gradientTitle(String text) {
        return safe(font(FONT_UNIFORM,
                "<gradient:" + GRADIENT_START + ":" + GRADIENT_MID + ":" + GRADIENT_TAIL
                        + "><bold>" + escapeForMini(text) + "</bold></gradient>"));
    }

    public static Component gradientTitle(String text, String tail) {
        Component head = gradientTitle(text);
        if (tail == null || tail.isBlank()) return head;
        return head.append(safe(font(FONT_UNIFORM, " <dark_gray>\u2022</dark_gray> " + toned(6, tail))));
    }

    static String escapeForMini(String s) {
        if (s == null) return "";
        // Важно: MiniMessage понимает только "\<" (буквальный «<»).
        // Символ ">" экранировать нельзя — "\\>" остаётся в тексте слэшем.
        return s.replace("\\", "\\\\").replace("<", "\\<");
    }

    /** Разбор с защитой: некорректная разметка не должна ронять команду/GUI. */
    public static Component safe(String mini) {
        if (mini == null || mini.isEmpty()) return Component.empty();
        String normalized = normalizeHex(mini);
        try {
            return MM.deserialize(normalized);
        } catch (Throwable t) {
            String plain = normalized.replaceAll("</?[a-zA-Z#][^>]*>", "");
            try {
                return MM.deserialize(plain.replace("\u00a7", "&"));
            } catch (Throwable ignored) {
                return Component.text(plain);
            }
        }
    }

    /**
     * Приводит hex-теги к согласованному виду: закрывающий тег обязан совпадать
     * с последним открытым, иначе MiniMessage выведет «</#3f86ff>» прямо в чат.
     * Без единого несовпадения строка возвращается как есть.
     */
    static String normalizeHex(String mini) {
        if (mini.indexOf("</#") < 0) return mini;
        java.util.regex.Matcher m = HEX_TAG.matcher(mini);
        if (!m.find()) return mini;
        StringBuilder out = new StringBuilder(mini.length());
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        int pos = 0;
        m.reset();
        boolean changed = false;
        while (m.find()) {
            out.append(mini, pos, m.start());
            String color = m.group(1);
            boolean closing = mini.charAt(m.start() + 1) == '/';
            if (!closing) {
                stack.push(color);
                out.append(m.group());
            } else if (!stack.isEmpty()) {
                String expected = stack.pop();
                if (expected.equalsIgnoreCase(color)) {
                    out.append(m.group());
                } else {
                    out.append("</").append(expected).append('>');
                    changed = true;
                }
            } else {
                changed = true; // лишнее закрытие просто убираем
            }
            pos = m.end();
        }
        out.append(mini, pos, mini.length());
        return changed ? out.toString() : mini;
    }

    private static final java.util.regex.Pattern HEX_TAG =
            java.util.regex.Pattern.compile("</?(#[0-9a-fA-F]{6})>");

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

    private static Component text(String s) {
        return safe(legacyToMini(s));
    }

    /**
     * Переводит §-коды в теги MiniMessage. Все коды закрыты — иначе остаток
     * «§9» и подобные вызывают исключение MiniMessage (legacy-коды запрещены).
     * Уже готовые MiniMessage-теги не трогаем: они собраны выше по стеку.
     */
    static String legacyToMini(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00a7' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(i + 1));
                String tag = switch (code) {
                    case '0' -> "black";
                    case '1' -> "dark_blue";
                    case '2' -> TONES[5];   // запрещённый тёмно-зелёный → ярко-синий
                    case '3' -> "dark_aqua";
                    case '4' -> "red";      // запрещённый тёмно-красный → красный (ошибки)
                    case '5' -> TONES[4];   // запрещённый фиолетовый → синий
                    case '6' -> TONES[8];   // запрещённый золотой → бирюзовый
                    case '7' -> "gray";
                    case '8' -> "dark_gray";
                    case '9' -> "blue";
                    case 'a' -> TONES[7];   // запрещённый зелёный → светло-голубой
                    case 'b' -> "aqua";
                    case 'c' -> "red";
                    case 'd' -> TONES[6];   // запрещённый розовый → голубой
                    case 'e' -> TONES[9];   // запрещённый жёлтый → светло-бирюзовый
                    case 'f' -> "white";
                    case 'k' -> "obfuscated";
                    case 'l' -> "bold";
                    case 'm' -> "strikethrough";
                    case 'n' -> "underlined";
                    case 'o' -> "italic";
                    case 'r' -> "reset";
                    default -> null;
                };
                if (tag != null) {
                    sb.append('<').append(tag).append('>');
                    i++;
                    continue;
                }
                if (code == 'x' && i + 13 < s.length()) {
                    // Legacy-гекс: §x§R§R§G§G§B§B — каждая цифра после своего §
                    StringBuilder hex = new StringBuilder(7);
                    hex.append('#');
                    boolean ok = s.charAt(i + 1) == 'x';
                    for (int k = 0; ok && k < 6; k++) {
                        int sep = i + 2 + k * 2;      // §
                        int dig = i + 3 + k * 2;      // цифра
                        if (s.charAt(sep) != '\u00a7' || !isHex(s.charAt(dig))) {
                            ok = false;
                            break;
                        }
                        hex.append(Character.toLowerCase(s.charAt(dig)));
                    }
                    if (ok) {
                        sb.append("<color:").append(hex).append('>');
                        i += 13;
                        continue;
                    }
                }
                // Неизвестный код — просто убираем символ §, букву оставляем текстом
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

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

    public static ItemStack filler() {
        ItemStack it = new ItemStack(FILLER);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(Component.empty());
            it.setItemMeta(m);
        }
        return it;
    }

    public static void fill(Inventory inv) {
        if (inv == null) return;
        ItemStack pane = filler();
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, pane.clone());
        }
    }

    /** Текст без разметки — чтобы градиент не съел цвета из сообщения. */
    public static String plainOf(String mini) {
        if (mini == null || mini.isEmpty()) return "";
        try {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(MM.deserialize(mini)).trim();
        } catch (Throwable t) {
            return mini.replaceAll("</?[a-zA-Z#][^>]*>", "").trim();
        }
    }

    /** Кнопка «Назад» в стиле hahtojer: спектральная стрелка с фирменным градиентом. */
    public static ItemStack navBack(String miniLabel) {
        String text = stripArrow(plainOf(miniLabel));
        if (text.isEmpty()) text = "Назад";
        return glow(named(Material.SPECTRAL_ARROW, gradient("\u25c0 " + text, 3, 8)));
    }

    /** Кнопка «Вперёд» в стиле hahtojer. */
    public static ItemStack navForward(String miniLabel) {
        String text = stripArrow(plainOf(miniLabel));
        if (text.isEmpty()) text = "Вперёд";
        return glow(named(Material.SPECTRAL_ARROW, gradient(text + " \u25b6", 3, 8)));
    }

    private static String stripArrow(String text) {
        return text.replaceFirst("^[\\u2190\\u2192\\u25c0\\u25b6]+\\s*", "").trim();
    }

    public static ItemStack navInfo(String... lines) {
        List<String> lore = new ArrayList<>();
        lore.add(toneTag(6) + "Нажми, чтобы узнать подробнее</color>");
        if (lines != null) {
            for (String l : lines) lore.add(l);
        }
        return named(Material.LIME_DYE, gradient("Инфо", 4, 9), lore);
    }

    /** Кнопка закрытия (барьер) в стиле hahtojer. */
    public static ItemStack navClose(String miniLabel) {
        String text = plainOf(miniLabel);
        if (text.isEmpty()) text = "\u2716 Закрыть";
        return named(Material.BARRIER, toned(5, text));
    }

    /** Бренд-предмет: имя в фирменном градиенте, пояснения приходят из messages_*.yml. */
    public static ItemStack brand(List<String> loreLines) {
        List<String> lore = loreLines == null ? List.of() : loreLines;
        return named(Material.PAPER,
                font(FONT_ALT, gradient("HahGPS")), lore);
    }

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

    /** Версия плагина: из реестра, иначе из plugin.yml внутри jar (фильтруется Maven'ом). */
    public static String version() {
        try {
            var plugin = Bukkit.getPluginManager().getPlugin("HahGps");
            if (plugin == null) plugin = Bukkit.getPluginManager().getPlugin("hahgps");
            if (plugin != null) return plugin.getPluginMeta().getVersion();
        } catch (Throwable ignored) {
            // Bukkit ещё не поднят — берём версию из ресурса
        }
        try (var in = GuiTheme.class.getClassLoader().getResourceAsStream("plugin.yml")) {
            if (in != null) {
                String yml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("^version:\\s*(.+)$", java.util.regex.Pattern.MULTILINE)
                        .matcher(yml);
                if (m.find()) {
                    return m.group(1).trim().replace("'", "").replace("\"", "");
                }
            }
        } catch (Throwable ignored) {
            // ресурс недоступен
        }
        return "?";
    }

    public static Inventory create(InventoryHolder holder, int size, String title) {
        return Bukkit.createInventory(holder, size, gradientTitle(title));
    }
}
