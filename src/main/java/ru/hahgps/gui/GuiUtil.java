package ru.hahgps.gui;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.List;

public final class GuiUtil {

    private GuiUtil() {
    }

    public static ItemStack item(Material material, Component name, List<Component> lore) {
        return item(material, name, lore, null);
    }

    /** Предмет GUI с кастомным ID модели для будущего ресурспака. */
    public static ItemStack item(Material material, Component name, List<Component> lore, Integer modelId) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore);
            }
            if (modelId != null) {
                meta.setCustomModelData(modelId);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static ItemStack item(Material material, Component name) {
        return item(material, name, null);
    }

    public static ItemStack toned(Material material, int tone, String text, List<String> lore) {
        return GuiTheme.glow(GuiTheme.named(material, GuiTheme.toned(tone, text), lore));
    }

    public static ItemStack toned(Material material, int tone, String text, List<String> lore, Integer modelId) {
        return GuiTheme.glow(GuiTheme.named(material, GuiTheme.toned(tone, text), lore, modelId));
    }

    public static ItemStack gradient(Material material, String text, List<String> lore) {
        return GuiTheme.glow(GuiTheme.named(material, GuiTheme.gradient(text), lore));
    }

    public static Component gradientName(String text) {
        return GuiTheme.mm(GuiTheme.gradient(text));
    }

    public static Component tonedName(int tone, String text) {
        return GuiTheme.mm(GuiTheme.toned(tone, text));
    }

    public static ItemStack filler() {
        return GuiTheme.filler();
    }
}
