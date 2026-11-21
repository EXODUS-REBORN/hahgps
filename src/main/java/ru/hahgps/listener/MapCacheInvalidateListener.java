package ru.hahgps.listener;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import ru.hahgps.HahGpsPlugin;

/**
 * Мир меняется — кэш поверхности карты устарел.
 *
 * Карта и кэш мира раньше никогда не сбрасывались: сломанный дом продолжал
 * стоять на полотне, а на месте выкопанной ямы рисовалась трава. Сбрасываем
 * только тот мир, где правда произошла правка, и только его поверхность.
 */
public class MapCacheInvalidateListener implements Listener {

    private final HahGpsPlugin plugin;

    public MapCacheInvalidateListener(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        invalidate(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        invalidate(event.getBlock());
    }

    private void invalidate(Block block) {
        if (block == null || block.getWorld() == null) {
            return;
        }
        var maps = plugin.getMapService();
        if (maps != null) {
            maps.invalidateSurfaces(block.getWorld().getName());
        }
    }
}
