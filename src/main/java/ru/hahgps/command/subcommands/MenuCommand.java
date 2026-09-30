package ru.hahgps.command.subcommands;

import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.gui.MainMenuGui;

import java.util.List;

/**
 * /gps menu
 */
public class MenuCommand implements SubCommand {

    @Override
    public String name() {
        return "menu";
    }

    @Override
    public String usage() {
        return "/gps menu";
    }

    @Override
    public String permission() {
        return "gps.use";
    }

    @Override
    public void execute(Player player, String[] args) {
        new MainMenuGui(player).open();
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        return List.of();
    }
}
