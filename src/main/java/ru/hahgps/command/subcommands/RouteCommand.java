package ru.hahgps.command.subcommands;

import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;

import java.util.List;

/**
 * Группа команд /gps route.
 */
public class RouteCommand implements SubCommand {

    private final List<SubCommand> children = List.of(
            new RouteStartCommand(),
            new RouteStopCommand()
    );

    @Override
    public String name() {
        return "route";
    }

    @Override
    public String usage() {
        return "/gps route <start|stop>";
    }

    @Override
    public String permission() {
        return "gps.use";
    }

    @Override
    public List<SubCommand> children() {
        return children;
    }

    @Override
    public void execute(Player player, String[] args) {
        player.sendMessage(HahGpsPlugin.getInstance().getMessages().getPrefixed("usage", "usage", usage()));
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        return List.of();
    }
}
