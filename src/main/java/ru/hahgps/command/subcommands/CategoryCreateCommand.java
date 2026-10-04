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
        plugin.getCategoryService().createCategory(name)
                .whenComplete((created, error) -> plugin.getDataService().runSync(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        if (ru.hahgps.command.SubCommand.isDuplicate(error)) {
                            player.sendMessage(plugin.getMessages().getPrefixed("category-exists"));
                        } else {
                            ru.hahgps.command.SubCommand.sendError(player, error);
                        }
                        return;
                    }
                    if (created == null) {

                        player.sendMessage(plugin.getMessages().getPrefixed("category-exists"));
                        return;
                    }
                    player.sendMessage(plugin.getMessages().getPrefixed("category-created", "name", name));
                }));
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {

            return args[0].isEmpty() ? List.of("<имя>") : List.of();
        }
        return List.of();
    }
}
