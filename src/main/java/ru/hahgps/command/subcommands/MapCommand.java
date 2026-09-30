package ru.hahgps.command.subcommands;

import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;

import java.util.List;

/**
 * /gps map
 */
public class MapCommand implements SubCommand {

    @Override
    public String name() {
        return "map";
    }

    @Override
    public String usage() {
        return "/gps map";
    }

    @Override
    public String permission() {
        return "gps.map";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin.getInstance().getMapService().openMap(player);
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        return List.of();
    }
}
