package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.service.SearchService;

import java.util.List;

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
        plugin.getSearchService().search(query).whenComplete((results, error) -> {
            plugin.getDataService().runSync(() -> {
                if (!player.isOnline()) {
                    return;
                }
                if (error != null) {
                    ru.hahgps.command.SubCommand.sendError(player, error);
                    return;
                }
                var messages = plugin.getMessages();
                var list = results == null ? List.<SearchService.SearchResult>of() : results;
                if (list.isEmpty()) {
                    player.sendMessage(messages.getPrefixed("search-no-results", "query", query));
                    return;
                }
                player.sendMessage(messages.getPrefixed("search-header", "count", String.valueOf(list.size())));
                for (SearchService.SearchResult result : list) {
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
            // подсказываем имена точек из кэша (без запроса к БД на главном потоке)
            String prefix = args[0].toLowerCase();
            java.util.List<String> names = new java.util.ArrayList<>();
            for (var poi : HahGpsPlugin.getInstance().getDataService().getCachedPois()) {
                if (poi.getName().toLowerCase().startsWith(prefix)) {
                    names.add(poi.getName());
                }
                if (names.size() >= 30) {
                    break;
                }
            }
            return names;
        }
        return List.of();
    }
}
