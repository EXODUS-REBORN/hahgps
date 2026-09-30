package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;

import java.util.List;

public class CategoryCreateCommand implements SubCommand {

    @Override
    public String name() {
        return "create";
    }

    @Override
    public String usage() {
        return "/gps category create <имя>";
    }

    @Override
    public String permission() {
        return "gps.category.create";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (args.length < 1) {
            player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
            return;
        }
        String name = args[0];
        plugin.getCategoryService().createCategory(name).thenRun(() ->
                plugin.getDataService().runSync(() -> {
                    if (player.isOnline()) {
                        player.sendMessage(plugin.getMessages().getPrefixed("category-created", "name", name));
                    }
                }));
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            return List.of("<имя>");
        }
        return List.of();
    }
}
