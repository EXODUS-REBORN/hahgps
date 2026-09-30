package ru.hahgps.command.subcommands;

import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;

import java.util.List;

/**
 * /gps log — переключить журналирование маршрута.
 */
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
        boolean enabled = plugin.getNavigationService().toggleLogging(player);
        player.sendMessage(plugin.getMessages().getPrefixed(enabled ? "log-enabled" : "log-disabled"));
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        return List.of();
    }
}
