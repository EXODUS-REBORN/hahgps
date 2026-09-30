package ru.hahgps.command.subcommands;

import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.model.Category;

import java.util.List;

/**
 * /gps category delete <имя>
 */
public class CategoryDeleteCommand implements SubCommand {

    @Override
    public String name() {
        return "delete";
    }

    @Override
    public String usage() {
        return "/gps category delete <имя>";
    }

    @Override
    public String permission() {
        return "gps.category.delete";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (args.length < 1) {
            player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
            return;
        }
        String name = args[0];
        plugin.getCategoryService().deleteCategory(name).thenRun(() ->
                plugin.getDataService().runSync(() -> {
                    if (player.isOnline()) {
                        player.sendMessage(plugin.getMessages().getPrefixed("category-deleted", "name", name));
                    }
                }));
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            return HahGpsPlugin.getInstance().getDataService().getCategories().thenApply(cats ->
                    cats.stream().map(Category::getName)
                            .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                            .toList()).join();
        }
        return List.of();
    }
}
