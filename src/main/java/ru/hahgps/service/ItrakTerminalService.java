package ru.hahgps.service;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.model.TrackerTerminal;
import ru.hahgps.storage.DataService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Терминалы iTrak — блоки, по нажатию на которые игрок получает координаты
 * трекеров рядом и подсветку.
 *
 * В мире терминал — обычный лодестоун: отличает его реестр координат, а не
 * состояние блока. Поэтому привязка живёт в БД и переживает перезапуск, а
 * случайно поставленный игроком лодестоун терминалом не считается.
 */
public class ItrakTerminalService {

    public enum PlaceResult {
        OK,
        OCCUPIED,
        DISABLED,
        FAILED
    }

    public record Outcome(TrackerTerminal terminal, PlaceResult result) {
    }

    private final HahGpsPlugin plugin;
    private final DataService data;
    private final Map<String, TrackerTerminal> terminals = new ConcurrentHashMap<>();

    public ItrakTerminalService(HahGpsPlugin plugin, DataService data) {
        this.plugin = plugin;
        this.data = data;
    }

    public void load(List<TrackerTerminal> loaded) {
        terminals.clear();
        if (loaded != null) {
            for (TrackerTerminal terminal : loaded) {
                if (terminal != null && terminal.world() != null) {
                    terminals.put(terminal.key(), terminal);
                }
            }
        }
        plugin.getLogger().info("HahGps: загружено терминалов iTrak — " + terminals.size());
    }

    public void stop() {
        terminals.clear();
    }

    public TrackerTerminal at(Block block) {
        if (block == null || block.getWorld() == null) {
            return null;
        }
        return terminals.get(TrackerTerminal.key(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ()));
    }

    public boolean isTerminal(Block block) {
        return at(block) != null;
    }

    public List<TrackerTerminal> all() {
        return new ArrayList<>(terminals.values());
    }

    /** Поставить терминал в этот блок (предмет ставит слушатель). */
    public CompletableFuture<Outcome> place(Player player, Block block) {
        if (block == null || block.getWorld() == null) {
            return CompletableFuture.completedFuture(new Outcome(null, PlaceResult.FAILED));
        }
        if (!plugin.getConfigManager().isItrakEnabled()) {
            return CompletableFuture.completedFuture(new Outcome(null, PlaceResult.DISABLED));
        }
        if (!block.getType().isAir() && !block.isReplaceable()) {
            return CompletableFuture.completedFuture(new Outcome(null, PlaceResult.OCCUPIED));
        }
        World world = block.getWorld();
        String key = TrackerTerminal.key(world.getName(), block.getX(), block.getY(), block.getZ());
        TrackerTerminal existing = terminals.get(key);
        if (existing != null) {
            // Реестр может помнить блок, который уже сломали взрывом или
            // поршнем: если на месте нет терминала — запись протухла.
            if (block.getType() == Material.LODESTONE) {
                return CompletableFuture.completedFuture(new Outcome(null, PlaceResult.OCCUPIED));
            }
            terminals.remove(key);
            data.deleteTerminal(existing.world(), existing.x(), existing.y(), existing.z())
                    .exceptionally(error -> false);
        }
        // Запоминаем исходное состояние: при сбое БД возвращаем его, а не
        // AIR — иначе снег/трава/цветок под терминалом терялись навсегда.
        org.bukkit.block.data.BlockData previous = block.getBlockData();
        block.setType(Material.LODESTONE, false);
        long now = System.currentTimeMillis();
        TrackerTerminal candidate = new TrackerTerminal(0, world.getName(),
                block.getX(), block.getY(), block.getZ(),
                player.getUniqueId(), player.getName(), now);
        return data.createTerminal(candidate).thenApply(created -> {
            if (created == null) {
                block.setBlockData(previous, false);
                return new Outcome(null, PlaceResult.FAILED);
            }
            terminals.put(created.key(), created);
            return new Outcome(created, PlaceResult.OK);
        });
    }

    /** Снять терминал из реестра и БД (блок убирает вызывающая сторона). */
    public CompletableFuture<Boolean> remove(Block block) {
        TrackerTerminal terminal = at(block);
        if (terminal == null) {
            return CompletableFuture.completedFuture(false);
        }
        terminals.remove(terminal.key());
        return data.deleteTerminal(terminal.world(), terminal.x(), terminal.y(), terminal.z());
    }
}
