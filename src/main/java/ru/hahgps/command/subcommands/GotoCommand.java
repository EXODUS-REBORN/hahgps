package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.model.Poi;
import ru.hahgps.model.TransportType;

import java.util.List;

public class GotoCommand implements SubCommand {

    @Override
    public String name() {
        return "goto";
    }

    @Override
    public String usage() {
        return "/gps goto <имя> [foot|car|transit|air|water]";
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
            transport = ru.hahgps.command.SubCommand.parseTransport(args[1]);
            if (transport == null) {

                player.sendMessage(plugin.getMessages().getPrefixed("invalid-args",
                        "usage", usage()));
                return;
            }
            if (!plugin.getConfigManager().isTransportAvailable(transport.getKey())) {
                player.sendMessage(plugin.getMessages().getPrefixed("transport-disabled",
                        "transport", transport.getKey()));
                return;
            }
        }
        final TransportType finalTransport = transport;
        plugin.getPoiService().loadPoiWithRating(name).whenComplete((poi, error) -> {
            plugin.getDataService().runSync(() -> {
                if (!player.isOnline()) {
                    return;
                }
                if (error != null) {
                    ru.hahgps.command.SubCommand.sendError(player, error);
                    return;
                }
                if (poi == null) {
                    player.sendMessage(plugin.getMessages().getPrefixed("point-not-found", "name", name));
                    return;
                }
                plugin.getCommandManager().gotoPoi(player, poi, finalTransport);
            });
        });
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return HahGpsPlugin.getInstance().getDataService().getCachedPois().stream()
                    .map(Poi::getName)
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .toList();
        }
        if (args.length == 2) {
            List<String> types = new java.util.ArrayList<>();
            for (TransportType t : TransportType.values()) {
                // Подсказываем только включённые в config.yml транспорты.
                if (HahGpsPlugin.getInstance().getConfigManager().isTransportAvailable(t.getKey())) {
                    types.add(t.getKey());
                }
            }
            return types;
        }
        return List.of();
    }
}
