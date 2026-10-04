package ru.hahgps.command.subcommands;

import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.model.TransportType;
import ru.hahgps.service.VisualizationService;

import java.util.ArrayList;
import java.util.List;

public class VisualCommand implements SubCommand {

    @Override
    public String name() {
        return "visual";
    }

    @Override
    public String usage() {
        return "/gps visual [foot|car|transit|air|water]";
    }

    @Override
    public String permission() {

        return "gps.visual";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        VisualizationService viz = plugin.getVisualizationService();
        if (viz == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("loading-data"));
            return;
        }
        if (args.length == 0) {
            viz.disableAll(player);
            player.sendMessage(plugin.getMessages().getPrefixed("visual-disabled"));
            return;
        }
        TransportType type = ru.hahgps.command.SubCommand.parseTransport(args[0]);
        if (type == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("invalid-args", "usage", usage()));
            return;
        }
        if (viz.isVisualizing(player, type)) {
            viz.disableVisualization(player, type);
            player.sendMessage(plugin.getMessages().getPrefixed("visual-disabled"));
        } else {
            viz.enableVisualization(player, type);
            player.sendMessage(plugin.getMessages().getPrefixed("visual-enabled",
                    "type", type.getKey()));
        }
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            List<String> types = new ArrayList<>();
            for (TransportType t : TransportType.values()) {
                types.add(t.getKey());
            }
            return types;
        }
        return List.of();
    }
}
