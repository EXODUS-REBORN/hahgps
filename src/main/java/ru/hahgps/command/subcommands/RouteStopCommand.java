package ru.hahgps.command.subcommands;

import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;

import java.util.List;

/**
 * /gps route stop
 */
public class RouteStopCommand implements SubCommand {

    @Override
    public String name() {
        return "stop";
    }

    @Override
    public String usage() {
        return "/gps route stop";
    }

    @Override
    public String permission() {
        return "gps.use";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (!plugin.getNavigationService().isNavigating(player)) {
            player.sendMessage(plugin.getMessages().getPrefixed("no-active-route"));
            return;
        }
        plugin.getNavigationService().stopNavigation(player);
        player.sendMessage(plugin.getMessages().getPrefixed("route-stopped"));
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        return List.of();
    }
}
