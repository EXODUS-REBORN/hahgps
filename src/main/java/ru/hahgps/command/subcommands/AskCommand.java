package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.CommandManager;
import ru.hahgps.command.SubCommand;

import java.util.List;

public class AskCommand implements SubCommand {

    @Override
    public String name() {
        return "ask";
    }

    @Override
    public String usage() {
        return "/gps ask <команда с <параметрами>>";
    }

    @Override
    public String permission() {
        return "gps.use";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (args.length == 0) {
            player.sendMessage(plugin.getMessages().getPrefixed("ask-usage"));
            return;
        }
        String command = String.join(" ", args);
        CommandManager manager = plugin.getCommandManager();
        if (manager == null || !manager.ask(player, command)) {
            player.sendMessage(plugin.getMessages().getPrefixed("ask-usage"));
        }
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        return List.of();
    }
}
