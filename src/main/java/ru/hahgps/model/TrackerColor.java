package ru.hahgps.model;

import org.bukkit.Color;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Цвет iTrak. Цвет — не только подпись: он задаёт материал предмета (пока
 * текстур нет), оттенок частиц-маркера и строка цвета в интерфейсе.
 */
public enum TrackerColor {

    /**
     * Лазурит, а не компас восстановления: тот же материал у локатора, а
     * ванильная перезарядка {@code Player#setCooldown} включается на материал
     * целиком — белая полоса КД локатора появлялась и на синих трекерах.
     * Теперь у локатора свой материал, и КД маячков не касается.
     */
    BLUE("blue", Material.LAPIS_LAZULI, 0x3F86FF),
    CYAN("cyan", Material.PRISMARINE_CRYSTALS, 0x4FD1E0),
    GREEN("green", Material.EMERALD, 0x55C36B),
    YELLOW("yellow", Material.GOLD_NUGGET, 0xF0C24B),
    ORANGE("orange", Material.MAGMA_CREAM, 0xF2994A),
    RED("red", Material.REDSTONE, 0xEB5757),
    PURPLE("purple", Material.AMETHYST_SHARD, 0x9B51E0),
    WHITE("white", Material.QUARTZ, 0xF2F2F2);

    private final String key;
    private final Material material;
    private final int rgb;

    TrackerColor(String key, Material material, int rgb) {
        this.key = key;
        this.material = material;
        this.rgb = rgb;
    }

    public String getKey() {
        return key;
    }

    /** Материал для предмета и иконки, пока нет ресурспака. */
    public Material getMaterial() {
        return material;
    }

    public int getRgb() {
        return rgb;
    }

    public String hex() {
        return String.format(Locale.ROOT, "#%06x", rgb);
    }

    public Color bukkitColor() {
        return Color.fromRGB(rgb);
    }

    /** Ключ сообщения с названием цвета: itrak-color-blue и т. д. */
    public String messageKey() {
        return "itrak-color-" + key;
    }

    public static TrackerColor parse(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (TrackerColor color : values()) {
            if (color.key.equals(normalized) || color.name().equalsIgnoreCase(normalized)) {
                return color;
            }
        }
        return null;
    }

    public static List<String> keys() {
        List<String> out = new ArrayList<>(values().length);
        for (TrackerColor color : values()) {
            out.add(color.key);
        }
        return out;
    }
}
