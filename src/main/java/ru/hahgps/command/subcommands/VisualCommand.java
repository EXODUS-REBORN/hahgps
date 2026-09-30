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
        return "gps.admin";
    }

    @Override
    public void execute(Player player, String[] args) {
        VisualizationService viz = HahGpsPlugin.getInstance().getVisualizationService();
        if (args.length == 0) {
            viz.disableAll(player);
            player.sendMessage(HahGpsPlugin.getInstance().getMessages().getPrefixed("visual-disabled"));
            return;
        }
        TransportType type = TransportType.fromKey(args[0]);
        if (viz.isVisualizing(player, type)) {
            viz.disableVisualization(player, type);
            player.sendMessage(HahGpsPlugin.getInstance().getMessages().getPrefixed("visual-disabled"));
        } else {
            viz.enableVisualization(player, type);
            player.sendMessage(HahGpsPlugin.getInstance().getMessages().getPrefixed("visual-enabled",
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
