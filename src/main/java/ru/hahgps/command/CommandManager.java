package ru.hahgps.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.subcommands.*;
import ru.hahgps.service.RouteService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Диспетчер команды /gps с древовидной структурой.
 */
public class CommandManager implements CommandExecutor, TabCompleter {

    private final HahGpsPlugin plugin;
    private final Map<String, SubCommand> roots = new HashMap<>();

    public CommandManager(HahGpsPlugin plugin) {
        this.plugin = plugin;
        register(new MenuCommand());
        register(new PointCommand());
        register(new CategoryCommand());
        register(new GotoCommand());
        register(new SearchCommand());
        register(new MapCommand());
        register(new RouteCommand());
        register(new LogCommand());
        register(new ReloadCommand());
        register(new UpdateCommand());
        register(new HelpCommand());    }

    private void register(SubCommand cmd) {
        roots.put(cmd.name().toLowerCase(), cmd);
    }

    public Map<String, SubCommand> getSubcommands() {
        return roots;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().getPrefixed("player-only"));
            return true;
        }

        if (args.length == 0) {
            new HelpCommand().execute(player, args);
            return true;
        }

        // Навигация по дереву команд
        SubCommand node = null;
        int i = 0;
        // Корень: ищем среди корневых команд
        SubCommand current = roots.get(args[0].toLowerCase());
        if (current == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", "/gps " + args[0]));
            return true;
        }
        node = current;
        i = 1;
        // Спускаемся глубже, пока есть вложенные команды
        while (i < args.length && !node.isLeaf()) {
            SubCommand child = findChild(node, args[i].toLowerCase());
            if (child == null) {
                break;
            }
            node = child;
            i++;
        }

        String[] remaining = new String[args.length - i];
        System.arraycopy(args, i, remaining, 0, remaining.length);

        if (!node.isLeaf()) {
            // Группа команд — показываем использование
            node.execute(player, remaining);
            return true;
        }

        if (!node.permission().isEmpty() && !player.hasPermission(node.permission())) {
            player.sendMessage(plugin.getMessages().getPrefixed("no-permission"));
            return true;
        }

        node.execute(player, remaining);
        return true;
    }

    private SubCommand findChild(SubCommand parent, String name) {
        for (SubCommand child : parent.children()) {
            if (child.name().equalsIgnoreCase(name)) {
                return child;
            }
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return new ArrayList<>();
        }

        // Навигация по дереву для автодополнения
        SubCommand node = null;
        int i = 0;
        if (args.length >= 1) {
            SubCommand current = roots.get(args[0].toLowerCase());
            if (current != null) {
                node = current;
                i = 1;
                while (i < args.length - 1 && !node.isLeaf()) {
                    SubCommand child = findChild(node, args[i].toLowerCase());
                    if (child == null) {
                        break;
                    }
                    node = child;
                    i++;
                }
            }
        }

        if (node == null) {
            // Автодополнение корневых команд
            List<String> names = new ArrayList<>();
            for (SubCommand cmd : roots.values()) {
                if (cmd.permission().isEmpty() || player.hasPermission(cmd.permission())) {
                    names.add(cmd.name());
                }
            }
            return filter(names, args.length > 0 ? args[0] : "");
        }

        if (node.isLeaf()) {
            String[] remaining = new String[args.length - i];
            System.arraycopy(args, i, remaining, 0, remaining.length);
            return node.tabComplete(player, remaining);
        }

        // Автодополнение вложенных команд
        List<String> names = new ArrayList<>();
        for (SubCommand child : node.children()) {
            if (child.permission().isEmpty() || player.hasPermission(child.permission())) {
                names.add(child.name());
            }
        }
        return filter(names, args.length > 0 ? args[args.length - 1] : "");
    }

    private List<String> filter(List<String> options, String prefix) {
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(prefix.toLowerCase())) {
                result.add(option);
            }
        }
        return result;
    }

    /**
     * Построить маршрут к точке и начать навигацию.
     */
    public void gotoPoi(Player player, ru.hahgps.model.Poi poi, ru.hahgps.model.TransportType transport) {
        var routeService = plugin.getRouteService();
        var navService = plugin.getNavigationService();
        var messages = plugin.getMessages();

        var loc = poi.getLocation();
        if (loc == null) {
            player.sendMessage(messages.getPrefixed("point-not-found", "name", poi.getName()));
            return;
        }

        routeService.buildPathToAsync(player, loc, transport).thenAcceptAsync(result -> {
            plugin.getDataService().runSync(() -> {
                if (!player.isOnline()) {
                    return;
                }
                navService.startNavigation(player, poi.getName(), loc, result.path, result.distance, transport);
                player.sendMessage(messages.getPrefixed("route-started",
                        "name", poi.getName(),
                        "distance", String.format("%.0f", result.distance)));
            });
        });
    }
}
