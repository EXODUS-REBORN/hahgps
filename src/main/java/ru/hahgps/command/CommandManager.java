package ru.hahgps.command;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.subcommands.*;
import ru.hahgps.model.Poi;
import ru.hahgps.model.TransportType;
import ru.hahgps.service.Pathfinder;
import ru.hahgps.util.LocationUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        register(new HelpCommand());
        register(new EdgeCommand());
        register(new VisualCommand());
    }

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

        if (args[0].equalsIgnoreCase("off")) {
            if (!plugin.getNavigationService().isNavigating(player)) {
                player.sendMessage(plugin.getMessages().getPrefixed("no-active-route"));
                return true;
            }
            plugin.getNavigationService().stopNavigation(player);
            player.sendMessage(plugin.getMessages().getPrefixed("route-stopped"));
            return true;
        }

        SubCommand current = roots.get(args[0].toLowerCase());
        if (current == null) {
            tryGoto(player, args);
            return true;
        }
        if (!hasPermission(player, current)) {
            player.sendMessage(plugin.getMessages().getPrefixed("no-permission"));
            return true;
        }

        SubCommand node = current;
        int i = 1;
        while (i < args.length && !node.isLeaf()) {
            SubCommand child = findChild(node, args[i].toLowerCase());
            if (child == null) {
                break;
            }
            if (!hasPermission(player, child)) {
                player.sendMessage(plugin.getMessages().getPrefixed("no-permission"));
                return true;
            }
            node = child;
            i++;
        }

        String[] remaining = new String[args.length - i];
        System.arraycopy(args, i, remaining, 0, remaining.length);

        // Permission проверяется и для узлов-родителей (usage-ветка),
        // и для листа — иначе gps.use/gps.edge не применялись бы вовсе.
        if (!node.isLeaf() || hasPermission(player, node)) {
            node.execute(player, remaining);
        } else {
            player.sendMessage(plugin.getMessages().getPrefixed("no-permission"));
        }
        return true;
    }

    private boolean hasPermission(Player player, SubCommand cmd) {
        return cmd.permission().isEmpty() || player.hasPermission(cmd.permission());
    }

    private void tryGoto(Player player, String[] args) {
        String pointName = args[0];
        TransportType transport = TransportType.FOOT;
        if (args.length >= 2) {
            transport = SubCommand.parseTransport(args[1]);
            if (transport == null) {
                player.sendMessage(plugin.getMessages().getPrefixed("invalid-args",
                        "usage", new GotoCommand().usage()));
                return;
            }
        }
        final TransportType finalTransport = transport;
        plugin.getPoiService().loadPoiWithRating(pointName).whenComplete((poi, error) ->
                plugin.getDataService().runSync(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        SubCommand.sendError(player, error);
                        return;
                    }
                    if (poi == null) {
                        player.sendMessage(plugin.getMessages().getPrefixed("point-not-found",
                                "name", pointName));
                        return;
                    }
                    gotoPoi(player, poi, finalTransport);
                }));
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
            List<String> names = new ArrayList<>();
            for (SubCommand cmd : roots.values()) {
                if (hasPermission(player, cmd)) {
                    names.add(cmd.name());
                }
            }
            names.add("off");
            return filter(names, args.length > 0 ? args[0] : "");
        }

        if (node.isLeaf()) {
            if (!hasPermission(player, node)) {
                return new ArrayList<>();
            }
            String[] remaining = new String[args.length - i];
            System.arraycopy(args, i, remaining, 0, remaining.length);
            try {
                return node.tabComplete(player, remaining);
            } catch (Throwable t) {
                plugin.getLogger().warning("HahGps: tab-complete failed for " + node.name() + ": " + t);
                return new ArrayList<>();
            }
        }

        List<String> names = new ArrayList<>();
        for (SubCommand child : node.children()) {
            if (hasPermission(player, child)) {
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

    public void gotoPoi(Player player, Poi poi, TransportType transport) {
        var routeService = plugin.getRouteService();
        var navService = plugin.getNavigationService();
        var messages = plugin.getMessages();

        var loc = poi.getLocation();
        if (loc == null) {
            // getLocation()==null означает, что мира точки нет на сервере
            player.sendMessage(messages.getPrefixed("world-not-found", "world",
                    poi.getWorld() == null ? "?" : poi.getWorld()));
            return;
        }

        routeService.buildMultiModalAsync(player, loc, transport).whenComplete((path, error) ->
                plugin.getDataService().runSync(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        SubCommand.sendError(player, error);
                        return;
                    }
                    List<org.bukkit.Location> result = path != null
                            ? path
                            : List.<org.bukkit.Location>of();
                    // Длина маршрута в метрах: раньше сюда попадало число точек маршрута,
                    // а String.format("%.0f", int) бросал IllegalFormatConversionException
                    // и игрок не получал сообщение о старте навигации.
                    double distance = Pathfinder.routeLength(result);
                    if (distance <= 0 && loc.getWorld() != null) {
                        distance = LocationUtil.distance(player.getLocation(), loc);
                    }
                    if (Double.isInfinite(distance) || Double.isNaN(distance) || distance == Double.MAX_VALUE) {
                        distance = 0;
                    }
                    navService.startNavigation(player, poi.getName(), loc, result, distance, transport);
                    player.sendMessage(messages.getPrefixed("route-started",
                            "name", poi.getName(),
                            "distance", String.format("%.0f", distance)));
                }));
    }
}
