package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.model.Category;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class CategoryListCommand implements SubCommand {

    @Override
    public String name() {
        return "list";
    }

    @Override
    public String usage() {
        return "/gps category list";
    }

    @Override
    public String permission() {
        return "gps.use";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        plugin.getCategoryService().listCategories().whenComplete((categories, error) -> {
            if (error != null) {
                ru.hahgps.command.SubCommand.sendError(player, error);
                return;
            }
            if (categories == null) {
                return;
            }
            // Счётчики — из кэша точек: раньше это был N запросов к БД на
            // общем пуле из двух потоков.
            Map<Integer, Long> counts = plugin.getDataService().countPoisByCategoryCached();
            plugin.getDataService().runSync(() -> {
                if (!player.isOnline()) {
                    return;
                }
                var messages = plugin.getMessages();
                player.sendMessage(messages.getPrefixed("category-list-header",
                        "count", String.valueOf(categories.size())));
                for (Category category : categories) {
                    player.sendMessage(messages.getPrefixed("category-list-entry",
                            "name", category.getName(),
                            "count", String.valueOf(counts.getOrDefault(category.getId(), 0L))));
                }
            });
        });
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        return List.of();
    }
}
