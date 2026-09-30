package ru.hahgps.command.subcommands;

import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.model.Poi;
import ru.hahgps.model.TransportType;

import java.util.List;

/**
 * /gps route start <точка> [foot|car]
 */
public class RouteStartCommand implements SubCommand {

    @Override
    public String name() {
        return "start";
    }

    @Override
    public String usage() {
        return "/gps route start <точка> [foot|car]";
    }

    @Override
    public String permission() {
        return "gps.use";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (args.length < 1) {
            player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
            return;
        }
        String name = args[0];
        TransportType transport = TransportType.FOOT;
        if (args.length >= 2) {
            transport = TransportType.fromKey(args[1]);
        }
        final TransportType finalTransport = transport;
        plugin.getPoiService().loadPoiWithRating(name).thenAcceptAsync(poi -> {
            if (poi == null) {
                plugin.getDataService().runSync(() -> {
                    if (player.isOnline()) {
                        player.sendMessage(plugin.getMessages().getPrefixed("point-not-found", "name", name));
                    }
                });
                return;
            }
            plugin.getDataService().runSync(() -> {
                if (player.isOnline()) {
                    plugin.getCommandManager().gotoPoi(player, poi, finalTransport);
                }
            });
        });
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            return HahGpsPlugin.getInstance().getDataService().getPois().thenApply(pois ->
                    pois.stream().map(Poi::getName)
                            .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                            .toList()).join();
        }
        if (args.length == 2) {
            return List.of("foot", "car");
        }
        return List.of();
    }
}
