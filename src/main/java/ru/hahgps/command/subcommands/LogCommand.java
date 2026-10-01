package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;

import java.util.List;

public class LogCommand implements SubCommand {

    @Override
    public String name() {
        return "log";
    }

    @Override
    public String usage() {
        return "/gps log";
    }

    @Override
    public String permission() {
        return "gps.use";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (!plugin.getNavigationService().isNavigating(player)) {
            // переключать нечего — иначе игрок видел бы «выключена», когда ничего не включалось
            player.sendMessage(plugin.getMessages().getPrefixed("no-active-route"));
            return;
        }
        boolean enabled = plugin.getNavigationService().toggleLogging(player);
        player.sendMessage(plugin.getMessages().getPrefixed(enabled ? "log-enabled" : "log-disabled"));
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        return List.of();
    }
}
