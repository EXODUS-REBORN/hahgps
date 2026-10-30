package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.model.Category;
import ru.hahgps.service.CategoryService;

import java.util.List;

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
        // Название категории может содержать пробелы («Близкие места»).
        String name = String.join(" ", args);

        plugin.getCategoryService().delete(name)
                .whenComplete((result, error) -> plugin.getDataService().runSync(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        SubCommand.sendError(player, error);
                        return;
                    }
                    report(plugin, player, name, result);
                }));
    }

    private static void report(HahGpsPlugin plugin, Player player, String name,
                               CategoryService.DeleteResult result) {
        switch (result == null ? CategoryService.DeleteResult.NOT_FOUND : result) {
            case DELETED -> player.sendMessage(
                    plugin.getMessages().getPrefixed("category-deleted", "name", name));
            case DEFAULT_CATEGORY -> player.sendMessage(
                    plugin.getMessages().getPrefixed("cannot-delete-default"));
            case NOT_EMPTY -> player.sendMessage(
                    plugin.getMessages().getPrefixed("category-not-empty", "name", name));
            case NOT_FOUND -> player.sendMessage(
                    plugin.getMessages().getPrefixed("category-not-found", "name", name));
        }
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return HahGpsPlugin.getInstance().getDataService().getCachedCategories().stream()
                    .map(Category::getName)
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
