package ru.hahgps.service;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import ru.hahgps.model.TransportType;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Сервис маршрутов: процедурная прокладка пути по графу мира
 * с учётом выбранного транспорта (пешком / авто).
 */
public class RouteService {

    private final WorldGraph worldGraph = new WorldGraph();

    /**
     * Построить маршрут от игрока до цели с учётом транспорта.
     *
     * @return результат с путём, дистанцией и флагом успеха
     */
    public WorldGraph.PathResult buildPathTo(Player player, Location target, TransportType transport) {
        if (target == null) {
            return new WorldGraph.PathResult(List.of(), 0, false);
        }
        return worldGraph.findPath(player.getWorld(), player.getLocation(), target, transport);
    }

    /**
     * Асинхронная версия (для вызова из GUI/команд без блокировки).
     */
    public CompletableFuture<WorldGraph.PathResult> buildPathToAsync(Player player, Location target,
                                                                     TransportType transport) {
        return CompletableFuture.supplyAsync(() -> buildPathTo(player, target, transport));
    }
}
