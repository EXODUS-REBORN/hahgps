package ru.hahgps.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Утилиты для построения GUI-элементов в стиле основного плагина hahtojer.
 */
public final class GuiUtil {

    private GuiUtil() {
    }

    /**
     * Создать предмет с именем и описанием.
     */
    public static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * Создать предмет с именем (без описания).
     */
    public static ItemStack item(Material material, Component name) {
        return item(material, name, null);
    }

    /** Предмет с именем в тоне палитры основного плагина (MiniMessage). */
    public static ItemStack toned(Material material, int tone, String text, List<String> lore) {
        return GuiTheme.glow(GuiTheme.named(material, GuiTheme.toned(tone, text), lore));
    }

    /** Предмет с градиентным именем (как заголовки меню hahtojer). */
    public static ItemStack gradient(Material material, String text, List<String> lore) {
        return GuiTheme.glow(GuiTheme.named(material, GuiTheme.gradient(text), lore));
    }

    /** Название в фирменном градиенте. */
    public static Component gradientName(String text) {
        return GuiTheme.mm(GuiTheme.gradient(text));
    }

    /** Название в тоне палитры. */
    public static Component tonedName(int tone, String text) {
        return GuiTheme.mm(GuiTheme.toned(tone, text));
    }

    /**
     * Заполнитель пустых слотов — синяя панель, как в основном плагине.
     */
    public static ItemStack filler() {
        return GuiTheme.filler();
    }
}
