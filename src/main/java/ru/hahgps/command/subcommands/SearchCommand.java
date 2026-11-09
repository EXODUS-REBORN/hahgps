package ru.hahgps.command.subcommands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.config.MessagesManager;
import ru.hahgps.service.SearchService;
import ru.hahgps.service.TrackerService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Поиск мест в чате.
 *
 * В строке — только название, под ним кнопка «построить маршрут». Показания
 * (полное название, координаты, число искавших и процент совпадения) живут
 * в подсказке при наведении: список не забивается цифрами, но всё видно, если
 * навести курсор. Сколько мест выводить, задаётся ключом {@code -n 1..25}
 * (без ключа — настройка игрока или значение из конфига).
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
                            player.sendMessage(resultLine(messages, result));
                            if (result.poi != null) {
                                player.sendMessage(messages.getComponent("search-route-button")
                                        .clickEvent(ClickEvent.runCommand(routeCommand(result.name))));
                            }
                        }
                    });
                });
    }

    /**
     * Строка результата: название плюс подсказка при наведении со всеми
     * показаниями (полное название, координаты, число искавших, совпадение).
     */
    private static Component resultLine(MessagesManager messages, SearchService.SearchResult result) {
        List<Component> lines = new ArrayList<>();
        lines.add(messages.getComponent("gui.lore-search-full-name", "full", result.name));
        if (result.poi != null) {
            lines.add(messages.getComponent("gui.lore-search-coords",
                    "x", TrackerService.formatCoord(result.poi.getX()),
                    "y", TrackerService.formatCoord(result.poi.getY()),
                    "z", TrackerService.formatCoord(result.poi.getZ())));
        }
        lines.add(messages.getComponent("gui.lore-search-searchers",
                "count", String.valueOf(result.searchers)));
        lines.add(messages.getComponent("gui.lore-search-result",
                "percent", String.valueOf(result.percent())));
        Component hover = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                hover = hover.append(Component.newline());
            }
            hover = hover.append(lines.get(i));
        }
        return messages.getComponent("search-entry", "name", result.name)
                .hoverEvent(HoverEvent.showText(hover));
    }

    /** Команда кнопки: имя может быть из нескольких слов — goto склеивает их сам. */
    private static String routeCommand(String name) {
        return "/gps goto " + name;
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
