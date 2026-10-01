package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;

import java.util.ArrayList;
import java.util.List;

public class HelpCommand implements SubCommand {

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String usage() {
        return "/gps help";
    }

    @Override
    public String permission() {
        return "gps.use";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        var messages = plugin.getMessages();
        player.sendMessage(messages.getPrefixed("help-header"));
        for (SubCommand cmd : plugin.getCommandManager().getSubcommands().values()) {
            if (cmd.permission().isEmpty() || player.hasPermission(cmd.permission())) {
                String description = messages.get("help-" + cmd.name(), "description", "");
                if (description.contains("Missing:")) {
                    // ключ help-<имя> ещё не добавлен в messages_*.yml — не показываем игроку служебный текст
                    description = cmd.usage();
                }
                player.sendMessage(messages.getPrefixed("help-entry",
                        "usage", cmd.usage(),
                        "description", description));
            }
        }
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        return List.of();
    }
}
