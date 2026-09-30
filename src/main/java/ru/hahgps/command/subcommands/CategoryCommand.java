package ru.hahgps.command.subcommands;

import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;

import java.util.List;

/**
 * Группа команд /gps category.
 */
public class CategoryCommand implements SubCommand {

    private final List<SubCommand> children = List.of(
            new CategoryCreateCommand(),
            new CategoryDeleteCommand(),
            new CategoryListCommand()
    );

    @Override
    public String name() {
        return "category";
    }

    @Override
    public String usage() {
        return "/gps category <create|delete|list>";
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
