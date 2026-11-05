package ru.hahgps.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Keyed;
import org.bukkit.inventory.ShapelessRecipe;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.item.ItemFactory;
import ru.hahgps.model.TrackerColor;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Крафты iTrak: «создание проще» — трекер, локатор и терминал
 * собираются в верстаке, а не выдаются только админом.
 *
 * Результат подменяется в {@link PrepareItemCraftEvent}: ванильные рецепты не
 * умеют отдавать предмет с нашим PDC (код трекера). Каждый обычный
 * крафт получает новый код; шифт-крафт отключён намеренно — иначе несколько
 * трекеров из одного клика имели бы одинаковый код и на сервере выжил бы
 * только первый.
 */
public class ItrakCraftingListener implements Listener {

    private final HahGpsPlugin plugin;

    private final NamespacedKey trackerKey;
    private final NamespacedKey waterproofKey;
    private final NamespacedKey locatorKey;
    private final NamespacedKey terminalKey;

    public ItrakCraftingListener(HahGpsPlugin plugin) {
        this.plugin = plugin;
        this.trackerKey = new NamespacedKey(plugin, "itrak_tracker");
        this.waterproofKey = new NamespacedKey(plugin, "itrak_tracker_waterproof");
        this.locatorKey = new NamespacedKey(plugin, "itrak_locator");
        this.terminalKey = new NamespacedKey(plugin, "itrak_terminal");
    }

    /** Регистрация рецептов; повторный вызов (reload) не падает на дубликате. */
    public void registerRecipes() {
        if (!plugin.getConfigManager().isItrakCrafting()) {
            return;
        }
        shapeless(trackerKey, Material.COMPASS, Material.REDSTONE_BLOCK, Material.IRON_INGOT);
        shapeless(waterproofKey, Material.COMPASS, Material.NAUTILUS_SHELL,
                Material.REDSTONE_BLOCK, Material.IRON_INGOT);
        shapeless(locatorKey, Material.COMPASS, Material.AMETHYST_SHARD, Material.REDSTONE);
        shapeless(terminalKey, Material.LODESTONE, Material.AMETHYST_SHARD, Material.COMPASS);
    }

    private void shapeless(NamespacedKey key, Material... ingredients) {
        try {
            Bukkit.removeRecipe(key);
            ShapelessRecipe recipe = new ShapelessRecipe(key, new ItemStack(Material.PAPER));
            for (Material material : ingredients) {
                recipe.addIngredient(1, material);
            }
            Bukkit.addRecipe(recipe);
        } catch (Throwable t) {
            plugin.getLogger().warning("HahGps: рецепт iTrak " + key.getKey() + " не зарегистрирован: " + t);
        }
    }

    @EventHandler
    public void onPrepare(PrepareItemCraftEvent event) {
        if (!(event.getRecipe() instanceof Keyed keyed)) {
            return;
        }
        ItemStack result = resultFor(keyed.getKey());
        if (result != null) {
            event.getInventory().setResult(result);
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (!(event.getRecipe() instanceof Keyed keyed) || !isOurRecipe(keyed.getKey())) {
            return;
        }
        if (!event.isShiftClick()) {
            return;
        }
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player) {
            player.sendMessage(plugin.getMessages().getPrefixed("itrak-craft-one-by-one"));
        }
    }

    private boolean isOurRecipe(NamespacedKey key) {
        return key.equals(trackerKey) || key.equals(waterproofKey)
                || key.equals(locatorKey) || key.equals(terminalKey);
    }

    private ItemStack resultFor(NamespacedKey key) {
        if (key.equals(trackerKey)) {
            return ItemFactory.trackerItem(randomSpec(false));
        }
        if (key.equals(waterproofKey)) {
            return ItemFactory.trackerItem(randomSpec(true));
        }
        if (key.equals(locatorKey)) {
            return ItemFactory.locatorItem();
        }
        if (key.equals(terminalKey)) {
            return ItemFactory.terminalItem();
        }
        return null;
    }

    private static ItemFactory.TrackerSpec randomSpec(boolean waterproof) {
        TrackerColor[] colors = TrackerColor.values();
        TrackerColor color = colors[ThreadLocalRandom.current().nextInt(colors.length)];
        return new ItemFactory.TrackerSpec(ItemFactory.newCode(), "", color, waterproof);
    }
}
