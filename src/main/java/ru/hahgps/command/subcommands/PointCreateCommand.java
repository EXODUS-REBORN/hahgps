package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;

import java.util.List;

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

        plugin.getPoiService().createPoi(player, name, category, description)
                .whenComplete((created, error) -> plugin.getDataService().runSync(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        if (ru.hahgps.command.SubCommand.isDuplicate(error)) {
                            player.sendMessage(plugin.getMessages().getPrefixed("point-exists",
                                    "name", name));
                        } else {
                            ru.hahgps.command.SubCommand.sendError(player, error);
                        }
                        return;
                    }
                    if (created == null) {
                        // категорий в БД нет — точка не создана, сообщать «создана» нельзя
                        player.sendMessage(plugin.getMessages().getPrefixed("category-not-found",
                                "name", category));
                        return;
                    }
                    player.sendMessage(plugin.getMessages().getPrefixed("point-created", "name", name));
                }));
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            return args[0].isEmpty() ? List.of("<имя>") : List.of();
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
