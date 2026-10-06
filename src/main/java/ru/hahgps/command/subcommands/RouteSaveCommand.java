package ru.hahgps.command.subcommands;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.model.Route;
import ru.hahgps.model.TransportType;
import ru.hahgps.service.RouteRecordingService;

import java.util.List;
import java.util.Locale;

/**
 * {@code /gps route save <имя> [транспорт]} — сохранение записанного маршрута.
 * После сохранения граф дорог пересобирается, и точки маршрута становятся
 * узлами для поиска пути.
 */
public class RouteSaveCommand implements SubCommand {

    @Override
    public String name() {
        return "save";
    }

    @Override
    public String usage() {
        return "/gps route save <имя> [foot|car|transit|air|water]";
    }

    @Override
    public String permission() {
        return "gps.route.save";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        RouteRecordingService recording = plugin.getRouteRecordingService();
        if (recording == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("loading-data"));
            return;
        }
        if (args.length < 1) {
            player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
            return;
        }
        String name = args[0];
        if (name.length() > 64) {
            player.sendMessage(plugin.getMessages().getPrefixed("invalid-args", "usage", usage()));
            return;
        }
        TransportType transport = TransportType.FOOT;
        if (args.length >= 2) {
            transport = SubCommand.parseTransport(args[1]);
            if (transport == null) {
                player.sendMessage(plugin.getMessages().getPrefixed("invalid-args", "usage", usage()));
                return;
            }
        }

        List<Location> points = recording.pending(player);
        if (points.size() < 2) {
            player.sendMessage(plugin.getMessages().getPrefixed("route-record-none"));
            return;
        }
        Location first = points.get(0);
        if (first.getWorld() == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("world-not-found", "world", "?"));
            return;
        }

        String worldName = first.getWorld().getName();
        final TransportType finalTransport = transport;
        Route route = new Route(0, name, worldName, finalTransport.name(), player.getName(),
                System.currentTimeMillis(), points);

        plugin.getDataService().createRoute(route).whenComplete((saved, error) ->
                plugin.getDataService().runSync(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        if (SubCommand.isDuplicate(error)) {
                            player.sendMessage(plugin.getMessages().getPrefixed("route-exists", "name", name));
                        } else {
                            SubCommand.sendError(player, error);
                        }
                        return;
                    }
                    if (saved == null) {
                        player.sendMessage(plugin.getMessages().getPrefixed("error-occurred",
                                "error", "маршрут не сохранён"));
                        return;
                    }
                    recording.forget(player);
                    player.sendMessage(plugin.getMessages().getPrefixed("route-saved",
                            "name", saved.getName(),
                            "count", String.valueOf(saved.getNodeCount()),
                            "length", ru.hahgps.util.LocationUtil.formatDistance(saved.getLength())));
                    var graphService = plugin.getGraphService();
                    if (graphService != null) {
                        graphService.loadGraph().exceptionally(graphError -> {
                            plugin.getLogger().log(java.util.logging.Level.WARNING,
                                    "HahGps: не удалось пересобрать граф после сохранения маршрута",
                                    graphError);
                            return null;
                        });
                    }
                }));
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length != 2) {
            return List.of();
        }
        String prefix = args[1].toLowerCase(Locale.ROOT);
        List<String> types = new java.util.ArrayList<>();
        for (TransportType type : TransportType.values()) {
            if (type.getKey().startsWith(prefix)) {
                types.add(type.getKey());
            }
        }
        return types;
    }
}
