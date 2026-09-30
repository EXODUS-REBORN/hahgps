package ru.hahgps.command.subcommands;

import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.service.SearchService;

import java.util.List;

/**
 * /gps search <запрос>
 */
public class SearchCommand implements SubCommand {

    @Override
    public String name() {
        return "search";
    }

    @Override
    public String usage() {
        return "/gps search <запрос>";
    }

    @Override
    public String permission() {
        return "gps.search";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (args.length < 1) {
            player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
            return;
        }
        String query = String.join(" ", args);
        plugin.getSearchService().search(query).thenAcceptAsync(results -> {
            plugin.getDataService().runSync(() -> {
                if (!player.isOnline()) {
                    return;
                }
                var messages = plugin.getMessages();
                if (results.isEmpty()) {
                    player.sendMessage(messages.getPrefixed("search-no-results", "query", query));
                    return;
                }
                player.sendMessage(messages.getPrefixed("search-header", "count", String.valueOf(results.size())));
                for (SearchService.SearchResult result : results) {
                    player.sendMessage(messages.getPrefixed("search-entry",
                            "name", result.name,
                            "match", String.valueOf((int) (result.similarity * 100))));
                }
            });
        });
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            return List.of("<запрос>");
        }
        return List.of();
    }
}
