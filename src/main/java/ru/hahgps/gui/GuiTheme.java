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


public final class GuiTheme {

    private GuiTheme() {
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static final Material FILLER = Material.BLUE_STAINED_GLASS_PANE;

    public static final String ACCENT_COLOR = "\u00a79";
    public static final String VALUE_COLOR = "\u00a7f";


    public static final String[] TONES = {
            "#08122b",
            "#0e2450",
            "#14397e",
            "#1d51a8",
            "#2464c8",
            "#3f86ff",
            "#66aaff",
            "#8fc6ff",
            "#4fd1e0",
            "#8fe9f5"
    };


    public static final String GREEN = "#4ade80";
    public static final String GREEN_DEEP = "#22c55e";
    public static final String LIME = "#a3e635";
    public static final String RED = "#f87171";
    public static final String RED_DEEP = "#ef4444";
    public static final String ORANGE = "#fb923c";
    public static final String ORANGE_DEEP = "#f97316";
    public static final String AMBER = "#fbbf24";
    public static final String GOLD = "#fcd34d";
    public static final String PURPLE = "#c084fc";
    public static final String VIOLET = "#a855f7";
    public static final String PINK = "#f472b6";
    public static final String TEAL = "#2dd4bf";
    public static final String SKY = "#38bdf8";
    public static final String INDIGO = "#818cf8";
    public static final String CYAN = "#22d3ee";
    public static final String BLUE = "#3b82f6";


    public static final String[] ACCENTS = {
            GREEN, GREEN_DEEP, LIME,
            RED, RED_DEEP,
            ORANGE, ORANGE_DEEP, AMBER, GOLD,
            PURPLE, VIOLET, PINK,
            TEAL, SKY, INDIGO, CYAN, BLUE
    };


    public static final String[] PALETTE = concat(TONES, ACCENTS);


    public static final String OK_MARK = "<" + GREEN + ">✔</" + GREEN + ">";
    public static final String ERR_MARK = "<" + RED + ">✖</" + RED + ">";
    public static final String PROMPT_MARK = "<" + PURPLE + ">→</" + PURPLE + ">";

    private static String[] concat(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    public static final String GRADIENT_START = TONES[1];
    public static final String GRADIENT_MID = TONES[4];
    public static final String GRADIENT_TAIL = TONES[9];


    public static final String FONT_UNIFORM = "minecraft:uniform";


    public static String font(String fontKey, String mini) {
        return "<font:" + fontKey + ">" + mini + "</font>";
    }


    public static String micro(String mini) {
        return font(FONT_UNIFORM, mini);
    }

    public static Component mm(String mini) {
        return safe(mini);
    }


    public static String tone(int index) {
        return TONES[Math.floorMod(index, TONES.length)];
    }


    public static String toneTag(int index) {
        return "<color:" + tone(index) + ">";
    }


    public static TextColor toneColor(int index) {
        return TextColor.fromHexString(tone(index));
    }


    public static String toned(int index, String text) {
        return toneTag(index) + escapeForMini(text) + "</color>";
    }


    public static String gradient(String text, int from, int to) {
        return "<gradient:" + tone(from) + ":" + tone(to) + ">" + escapeForMini(text) + "</gradient>";
    }


    public static String gradient(String text) {
        return "<gradient:" + GRADIENT_START + ":" + GRADIENT_MID + ":" + GRADIENT_TAIL + ">"
                + escapeForMini(text) + "</gradient>";
    }


    public static Component gradientTitle(String text) {
        String plain = text == null ? "" : text.trim();
        return safe("<bold><gradient:" + GRADIENT_START + ":" + GRADIENT_MID + ":" + GRADIENT_TAIL + ">"
                + escapeForMini(clipPlain(plain, TITLE_MAX)) + "</gradient></bold>");
    }

    public static Component gradientTitle(String text, String tail) {
        Component head = gradientTitle(text);
        if (tail == null || tail.isBlank()) return head;
        return head.append(safe(" <dark_gray>\u2022</dark_gray> "
                + toned(6, clipPlain(tail.trim(), TAIL_MAX))));
    }

    public static final int TITLE_MAX = 28;
    public static final int TAIL_MAX = 12;
    public static final int NAME_MAX = 30;
    public static final int LORE_MAX = 40;


    public static String clipPlain(String text, int max) {
        if (text == null) return "";
        String s = text.trim();
        if (max <= 0 || s.isEmpty()) return s;
        int count = s.codePointCount(0, s.length());
        if (count <= max) return s;
        int cut = s.offsetByCodePoints(0, Math.max(1, max - 1));
        return s.substring(0, cut) + "\u2026";
    }


    static String clipMini(String mini, int max) {
        if (mini == null) return "";
        if (max <= 0) return mini;
        java.util.ArrayDeque<String> open = new java.util.ArrayDeque<>();
        StringBuilder out = new StringBuilder(mini.length());
        int visible = 0;
        int i = 0;
        int n = mini.length();
        boolean cut = false;
        while (i < n) {
            char c = mini.charAt(i);
            if (c == '\\' && i + 1 < n && mini.charAt(i + 1) == '<') {
                if (visible >= max) {
                    cut = true;
                    break;
                }
                out.append("\\<");
                visible++;
                i += 2;
                continue;
            }
            if (c == '<') {
                int end = mini.indexOf('>', i);
                if (end < 0) break;
                String tag = mini.substring(i, end + 1);
                out.append(tag);
                if (tag.startsWith("</")) {
                    if (!open.isEmpty()) open.pop();
                } else {
                    String name = tag.substring(1, tag.length() - 1);
                    int colon = name.indexOf(':');
                    String closeName = colon < 0 ? name : name.substring(0, colon);
                    open.push("</" + closeName + ">");
                }
                i = end + 1;
                continue;
            }
            if (visible >= max) {
                cut = true;
                break;
            }
            out.append(c);
            visible++;
            i++;
        }
        if (cut) {
            out.append('\u2026');
            while (!open.isEmpty()) out.append(open.pop());
        }
        return out.toString();
    }


    static String escapeForMini(String s) {
        if (s == null) return "";


        return s.replace("\\", "\\\\").replace("<", "\\<");
    }


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
                changed = true;
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
            if (name != null) m.displayName(text(clipMini(name, NAME_MAX)));
            if (lore != null && !lore.isEmpty()) {
                List<Component> out = new ArrayList<>(lore.size());
                for (String s : lore) {
                    out.add(s == null ? Component.empty() : text(clipMini(s, LORE_MAX)));
                }
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
                    case '2' -> TONES[5];
                    case '3' -> "dark_aqua";
                    case '4' -> "red";
                    case '5' -> TONES[4];
                    case '6' -> TONES[8];
                    case '7' -> "gray";
                    case '8' -> "dark_gray";
                    case '9' -> "blue";
                    case 'a' -> TONES[7];
                    case 'b' -> "aqua";
                    case 'c' -> "red";
                    case 'd' -> TONES[6];
                    case 'e' -> TONES[9];
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

                    StringBuilder hex = new StringBuilder(7);
                    hex.append('#');
                    boolean ok = s.charAt(i + 1) == 'x';
                    for (int k = 0; ok && k < 6; k++) {
                        int sep = i + 2 + k * 2;
                        int dig = i + 3 + k * 2;
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


    public static String plainOf(String mini) {
        if (mini == null || mini.isEmpty()) return "";
        try {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(MM.deserialize(mini)).trim();
        } catch (Throwable t) {
            return mini.replaceAll("</?[a-zA-Z#][^>]*>", "").trim();
        }
    }


    public static ItemStack navBack(String miniLabel) {
        String text = stripArrow(plainOf(miniLabel));
        if (text.isEmpty()) text = "Назад";
        return glow(named(Material.SPECTRAL_ARROW, gradient("\u25c0 " + text, 3, 8)));
    }


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


    public static ItemStack navClose(String miniLabel) {
        String text = plainOf(miniLabel);
        if (text.isEmpty()) text = "\u2716 Закрыть";
        return named(Material.BARRIER, toned(5, text));
    }


    public static ItemStack brand(List<String> loreLines) {
        List<String> lore = loreLines == null ? List.of() : loreLines;
        return named(Material.PAPER,
                "<bold>" + gradient("HahGPS") + "</bold>", lore);
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


    public static String version() {
        try {
            var plugin = Bukkit.getPluginManager().getPlugin("HahGps");
            if (plugin == null) plugin = Bukkit.getPluginManager().getPlugin("hahgps");
            if (plugin != null) return plugin.getPluginMeta().getVersion();
        } catch (Throwable ignored) {

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

        }
        return "?";
    }

    public static Inventory create(InventoryHolder holder, int size, String title) {
        return Bukkit.createInventory(holder, size, gradientTitle(title));
    }
}
