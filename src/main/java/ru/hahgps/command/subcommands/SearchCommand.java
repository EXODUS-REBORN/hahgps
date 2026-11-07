package ru.hahgps.command.subcommands;

import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.service.SearchService;
import ru.hahgps.service.TrackerService;

import java.util.Arrays;
import java.util.List;

/**
 * Поиск мест в чате.
 *
 * Процент совпадения больше не печатается: в списке — название, координаты и
 * сколько игроков искали это место, а сам процент виден в подсказке при
 * наведении в меню поиска. Сколько мест выводить, задаётся ключом
 * {@code -n 1..25} (без ключа — настройка игрока или значение из конфига).
 */
public class SearchCommand implements SubCommand {

    @Override
    public String name() {
        return "search";
    }

    @Override
    public String usage() {
        return "/gps search [-n 1..25] <запрос>";
    }

    @Override
    public String permission() {
        return "gps.search";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        Integer limit = null;
        int from = 0;
        if (args.length >= 2 && (args[0].equalsIgnoreCase("-n") || args[0].equalsIgnoreCase("--limit"))) {
            try {
                limit = Integer.parseInt(args[1]);
            } catch (NumberFormatException notANumber) {
                player.sendMessage(plugin.getMessages().getPrefixed("invalid-args", "usage", usage()));
                return;
            }
            if (limit < SearchService.MIN_RESULTS || limit > SearchService.MAX_RESULTS) {
                player.sendMessage(plugin.getMessages().getPrefixed("invalid-args", "usage", usage()));
                return;
            }
            from = 2;
        }
        if (from >= args.length) {
            player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
            return;
        }
        // Запрос может быть адресом с числом («Хайсовская 10»), поэтому число
        // в конце запроса — часть названия, а не лимит: лимит только через -n.
        String query = String.join(" ", Arrays.copyOfRange(args, from, args.length));
        final Integer requestedLimit = limit;
        plugin.getSearchService().search(query, player.getUniqueId(), requestedLimit)
                .whenComplete((results, error) -> {
                    plugin.getDataService().runSync(() -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (error != null) {
                            SubCommand.sendError(player, error);
                            return;
                        }
                        var messages = plugin.getMessages();
                        var list = results == null ? List.<SearchService.SearchResult>of() : results;
                        if (list.isEmpty()) {
                            player.sendMessage(messages.getPrefixed("search-no-results", "query", query));
                            return;
                        }
                        player.sendMessage(messages.getPrefixed("search-header",
                                "count", String.valueOf(list.size())));
                        for (SearchService.SearchResult result : list) {
                            String x = "?";
                            String y = "?";
                            String z = "?";
                            if (result.poi != null) {
                                x = TrackerService.formatCoord(result.poi.getX());
                                y = TrackerService.formatCoord(result.poi.getY());
                                z = TrackerService.formatCoord(result.poi.getZ());
                            }
                            player.sendMessage(messages.getPrefixed("search-entry",
                                    "name", result.name,
                                    "x", x, "y", y, "z", z,
                                    "players", String.valueOf(result.searchers)));
                        }
                    });
                });
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
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
