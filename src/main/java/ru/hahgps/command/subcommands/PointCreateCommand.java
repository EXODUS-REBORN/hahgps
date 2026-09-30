package ru.hahgps.command.subcommands;

import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;

import java.util.List;

/**
 * /gps point create <имя> [категория]
 */
public class PointCreateCommand implements SubCommand {

    @Override
    public String name() {
        return "create";
    }

    @Override
    public String usage() {
        return "/gps point create <имя> [категория]";
    }

    @Override
    public String permission() {
        return "gps.point.create";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (args.length < 1) {
            player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
            return;
        }
        String name = args[0];
        String category = args.length > 1 ? args[1] : null;
        String description = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "";

        plugin.getPoiService().createPoi(player, name, category, description).thenRun(() ->
                plugin.getDataService().runSync(() -> {
                    if (player.isOnline()) {
                        player.sendMessage(plugin.getMessages().getPrefixed("point-created", "name", name));
                    }
                }));
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            return List.of("<имя>");
        }
        if (args.length == 2) {
            return HahGpsPlugin.getInstance().getDataService().getCachedCategories().stream()
                    .map(c -> c.getName())
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
