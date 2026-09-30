package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;

import java.util.List;

public class PointCommand implements SubCommand {

    private final List<SubCommand> children = List.of(
            new PointCreateCommand(),
            new PointDeleteCommand(),
            new PointInfoCommand(),
            new PointRateCommand(),
            new PointListCommand()
    );

    @Override
    public String name() {
        return "point";
    }

    @Override
    public String usage() {
        return "/gps point <create|delete|info|rate|list>";
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
