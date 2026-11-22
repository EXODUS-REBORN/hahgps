package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;

import java.util.List;

public class ReloadCommand implements SubCommand {

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String usage() {
        return "/gps reload";
    }

    @Override
    public String permission() {
        return "gps.reload";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        plugin.getConfigManager().reload();
        plugin.getMessages().reload(plugin.getConfigManager().getLanguage());
        if (plugin.getResourcePackService() != null) {
            plugin.getResourcePackService().reload();
        }
        player.sendMessage(plugin.getMessages().getPrefixed("reloaded"));
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        return List.of();
    }
}
