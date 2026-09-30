package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.model.Category;

import java.util.List;
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
        plugin.getCategoryService().listCategories().thenCompose(categories -> {
            List<CompletableFuture<Integer>> countFutures = categories.stream()
                    .map(c -> plugin.getDataService().getPoisByCategory(c.getId())
                            .thenApply(List::size))
                    .toList();
            return CompletableFuture.allOf(countFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> countFutures.stream().map(CompletableFuture::join).toList())
                    .thenApply(counts -> {
                        plugin.getDataService().runSync(() -> {
                            if (!player.isOnline()) {
                                return;
                            }
                            var messages = plugin.getMessages();
                            player.sendMessage(messages.getPrefixed("category-list-header",
                                    "count", String.valueOf(categories.size())));
                            for (int i = 0; i < categories.size(); i++) {
                                Category category = categories.get(i);
                                player.sendMessage(messages.getPrefixed("category-list-entry",
                                        "name", category.getName(),
                                        "count", String.valueOf(counts.get(i))));
                            }
                        });
                        return null;
                    });
        });
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        return List.of();
    }
}
