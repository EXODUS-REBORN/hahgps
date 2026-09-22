package ru.hahgps.commands;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import ru.hahgps.HahGPSPlugin;
import ru.hahgps.model.Edge;
import ru.hahgps.model.Graph;
import ru.hahgps.model.Node;
import ru.hahgps.model.TransportType;
import ru.hahgps.util.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

public final class NavCommand implements TabExecutor {

    private static final int LIST_LIMIT = 40;

    private final HahGPSPlugin plugin;

    public NavCommand(HahGPSPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hahgps.admin")) {
            plugin.messages().send(sender, "general.no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.messages().sendLines(sender, "admin.help");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "node" -> node(sender, args);
            case "edge" -> edge(sender, args);
            case "visual" -> visual(sender, args);
            case "reload" -> reload(sender);
            case "save" -> save(sender);
            default -> plugin.messages().sendLines(sender, "admin.help");
        }
        return true;
    }

    private void node(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hahgps.admin.node")) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.messages().sendLines(sender, "admin.help");
            return;
        }
        Graph graph = plugin.graph();
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (!(sender instanceof Player player)) {
                    plugin.messages().send(sender, "general.players-only");
                    return;
                }
                if (args.length < 3) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                String id = args[2];
                if (graph.node(id) != null) {
                    plugin.messages().send(sender, "admin.node-exists", "id", id);
                    return;
                }
                EnumSet<TransportType> types = args.length >= 4 ? parseTypes(sender, args[3]) : EnumSet.of(TransportType.FOOT);
                if (types == null) {
                    return;
                }
                if (types.isEmpty()) {
                    types = EnumSet.of(TransportType.FOOT);
                }
                Location location = player.getLocation();
                Node node = new Node(id, location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
                node.setAllowedTypes(types);
                graph.addNode(node);
                plugin.messages().send(sender, "admin.node-created",
                        "id", id,
                        "world", node.getWorld(),
                        "x", Text.num(node.getX()),
                        "y", Text.num(node.getY()),
                        "z", Text.num(node.getZ()),
                        "transport", TransportType.join(types));
            }
            case "remove" -> {
                if (args.length < 3) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                String id = args[2];
                if (graph.node(id) == null) {
                    plugin.messages().send(sender, "admin.node-unknown", "id", id);
                    return;
                }
                int removed = graph.removeNode(id);
                plugin.messages().send(sender, "admin.node-removed", "id", id, "edges", String.valueOf(removed));
            }
            case "name" -> {
                if (args.length < 4) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                Node node = graph.node(args[2]);
                if (node == null) {
                    plugin.messages().send(sender, "admin.node-unknown", "id", args[2]);
                    return;
                }
                String name = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                node.setName(name);
                graph.markDirty();
                plugin.messages().send(sender, "admin.node-name", "id", node.getId(), "name", name);
            }
            case "allowed" -> {
                if (args.length < 5) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                Node node = graph.node(args[2]);
                if (node == null) {
                    plugin.messages().send(sender, "admin.node-unknown", "id", args[2]);
                    return;
                }
                TransportType type = TransportType.byId(args[4]);
                if (type == null) {
                    plugin.messages().send(sender, "general.unknown-transport", "input", args[4], "types", TransportType.ids());
                    return;
                }
                if (isAdd(args[3])) {
                    node.addAllowedType(type);
                } else if (isRemove(args[3])) {
                    node.removeAllowedType(type);
                } else {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                graph.markDirty();
                plugin.messages().send(sender, "admin.node-allowed",
                        "id", node.getId(),
                        "transport", TransportType.join(node.allowedTypes()));
            }
            case "tp" -> {
                if (!(sender instanceof Player player)) {
                    plugin.messages().send(sender, "general.players-only");
                    return;
                }
                if (args.length < 3) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                Node node = graph.node(args[2]);
                if (node == null) {
                    plugin.messages().send(sender, "admin.node-unknown", "id", args[2]);
                    return;
                }
                Location location = node.location();
                if (location == null) {
                    plugin.messages().send(sender, "general.world-missing", "world", node.getWorld());
                    return;
                }
                player.teleport(location);
            }
            case "list" -> {
                if (graph.nodeCount() == 0) {
                    plugin.messages().send(sender, "admin.graph-empty");
                    return;
                }
                plugin.messages().sendRaw(sender, "admin.node-list-header", "count", String.valueOf(graph.nodeCount()));
                int shown = 0;
                for (Node node : graph.nodes()) {
                    if (shown++ >= LIST_LIMIT) {
                        break;
                    }
                    plugin.messages().sendRaw(sender, "admin.node-list-entry",
                            "id", node.getId(),
                            "transport", TransportType.joinIcons(node.allowedTypes()),
                            "name", node.getName().isBlank() ? "" : "«" + node.getName() + "»",
                            "x", Text.num(node.getX()),
                            "y", Text.num(node.getY()),
                            "z", Text.num(node.getZ()));
                }
            }
            case "info" -> {
                if (args.length < 3) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                Node node = graph.node(args[2]);
                if (node == null) {
                    plugin.messages().send(sender, "admin.node-unknown", "id", args[2]);
                    return;
                }
                plugin.messages().sendLines(sender, "admin.node-info",
                        "id", node.getId(),
                        "name", node.getName().isBlank() ? "-" : node.getName(),
                        "world", node.getWorld(),
                        "x", Text.num(node.getX()),
                        "y", Text.num(node.getY()),
                        "z", Text.num(node.getZ()),
                        "transport", TransportType.join(node.allowedTypes()),
                        "edges", String.valueOf(graph.edgesOf(node.getId()).size()));
            }
            default -> plugin.messages().sendLines(sender, "admin.help");
        }
    }

    private void edge(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hahgps.admin.edge")) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.messages().sendLines(sender, "admin.help");
            return;
        }
        Graph graph = plugin.graph();
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "connect" -> {
                if (args.length < 4) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                Node first = graph.findByRef(args[2]);
                Node second = graph.findByRef(args[3]);
                if (first == null || second == null) {
                    plugin.messages().send(sender, "admin.node-unknown", "id", first == null ? args[2] : args[3]);
                    return;
                }
                if (first.getId().equals(second.getId())) {
                    plugin.messages().send(sender, "admin.edge-same");
                    return;
                }
                EnumSet<TransportType> types;
                if (args.length >= 5) {
                    types = parseTypes(sender, args[4]);
                    if (types == null) {
                        return;
                    }
                } else {
                    types = defaultTypes(first, second);
                }
                if (types.isEmpty()) {
                    types = EnumSet.of(TransportType.FOOT);
                }
                boolean existed = graph.edge(first.getId(), second.getId()) != null;
                Edge edge = graph.addEdge(first.getId(), second.getId(), types);
                if (args.length >= 6) {
                    edge.setLine(String.join(" ", Arrays.copyOfRange(args, 5, args.length)));
                }
                graph.refreshLengthsOf(first.getId());
                graph.refreshLengthsOf(second.getId());
                graph.markDirty();
                plugin.messages().send(sender, existed ? "admin.edge-updated" : "admin.edge-created",
                        "id1", first.getId(),
                        "id2", second.getId(),
                        "transport", TransportType.join(edge.allowedTypes()),
                        "length", Text.distance(edge.getLength()));
            }
            case "remove" -> {
                if (args.length < 4) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                if (graph.removeEdge(args[2], args[3])) {
                    plugin.messages().send(sender, "admin.edge-removed", "id1", args[2], "id2", args[3]);
                } else {
                    plugin.messages().send(sender, "admin.edge-unknown", "id1", args[2], "id2", args[3]);
                }
            }
            case "allowed" -> {
                if (args.length < 6) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                Edge edge = graph.edge(args[2], args[3]);
                if (edge == null) {
                    plugin.messages().send(sender, "admin.edge-unknown", "id1", args[2], "id2", args[3]);
                    return;
                }
                TransportType type = TransportType.byId(args[5]);
                if (type == null) {
                    plugin.messages().send(sender, "general.unknown-transport", "input", args[5], "types", TransportType.ids());
                    return;
                }
                if (isAdd(args[4])) {
                    edge.addAllowedType(type);
                } else if (isRemove(args[4])) {
                    edge.removeAllowedType(type);
                } else {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                graph.markDirty();
                plugin.messages().send(sender, "admin.edge-allowed",
                        "id1", edge.getFrom(),
                        "id2", edge.getTo(),
                        "transport", TransportType.join(edge.allowedTypes()));
            }
            case "oneway" -> {
                if (args.length < 5) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                Edge edge = graph.edge(args[2], args[3]);
                if (edge == null) {
                    plugin.messages().send(sender, "admin.edge-unknown", "id1", args[2], "id2", args[3]);
                    return;
                }
                boolean value = Boolean.parseBoolean(args[4]);
                edge.setOneWay(value);
                if (value) {
                    Node from = graph.findByRef(args[2]);
                    Node to = graph.findByRef(args[3]);
                    if (from != null && to != null) {
                        edge.setDirection(from.getId(), to.getId());
                    }
                }
                graph.markDirty();
                plugin.messages().send(sender, "admin.edge-oneway",
                        "id1", edge.getFrom(), "id2", edge.getTo(), "value", Text.yesNo(value));
            }
            case "weight" -> {
                if (args.length < 5) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                Edge edge = graph.edge(args[2], args[3]);
                if (edge == null) {
                    plugin.messages().send(sender, "admin.edge-unknown", "id1", args[2], "id2", args[3]);
                    return;
                }
                double value;
                try {
                    value = Double.parseDouble(args[4]);
                } catch (NumberFormatException exception) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                edge.setWeight(value);
                graph.markDirty();
                plugin.messages().send(sender, "admin.edge-weight",
                        "id1", edge.getFrom(), "id2", edge.getTo(), "value", Text.num(edge.getWeight()));
            }
            case "speed" -> {
                if (args.length < 5) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                Edge edge = graph.edge(args[2], args[3]);
                if (edge == null) {
                    plugin.messages().send(sender, "admin.edge-unknown", "id1", args[2], "id2", args[3]);
                    return;
                }
                double value;
                try {
                    value = Double.parseDouble(args[4]);
                } catch (NumberFormatException exception) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                edge.setSpeed(value);
                graph.markDirty();
                plugin.messages().send(sender, "admin.edge-speed",
                        "id1", edge.getFrom(), "id2", edge.getTo(),
                        "value", edge.getSpeed() > 0.0D
                                ? Text.num(edge.getSpeed()) + " бл/с"
                                : plugin.messages().raw("admin.edge-speed-layer"));
            }
            case "surface" -> {
                if (args.length < 5) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                Edge edge = graph.edge(args[2], args[3]);
                if (edge == null) {
                    plugin.messages().send(sender, "admin.edge-unknown", "id1", args[2], "id2", args[3]);
                    return;
                }
                String surface = args[4].toLowerCase(Locale.ROOT);
                if (surface.equals("none") || surface.equals("default") || surface.equals("reset")) {
                    edge.setSpeed(0.0D);
                    graph.markDirty();
                    plugin.messages().send(sender, "admin.edge-surface",
                            "id1", edge.getFrom(), "id2", edge.getTo(),
                            "surface", "none",
                            "speed", plugin.messages().raw("admin.edge-speed-layer"));
                } else if (plugin.config().hasSurface(surface)) {
                    double speed = plugin.config().surfaceSpeed(surface);
                    edge.setSpeed(speed);
                    graph.markDirty();
                    plugin.messages().send(sender, "admin.edge-surface",
                            "id1", edge.getFrom(), "id2", edge.getTo(),
                            "surface", surface,
                            "speed", Text.num(speed) + " бл/с");
                } else {
                    plugin.messages().send(sender, "admin.surface-unknown",
                            "input", args[4],
                            "surfaces", String.join(", ", plugin.config().surfaces().keySet()));
                }
            }
            case "line" -> {
                if (args.length < 5) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                Edge edge = graph.edge(args[2], args[3]);
                if (edge == null) {
                    plugin.messages().send(sender, "admin.edge-unknown", "id1", args[2], "id2", args[3]);
                    return;
                }
                String value = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
                edge.setLine(value);
                graph.markDirty();
                plugin.messages().send(sender, "admin.edge-line",
                        "id1", edge.getFrom(), "id2", edge.getTo(), "value", value);
            }
            case "name" -> {
                if (args.length < 5) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                Edge edge = graph.edge(args[2], args[3]);
                if (edge == null) {
                    plugin.messages().send(sender, "admin.edge-unknown", "id1", args[2], "id2", args[3]);
                    return;
                }
                String value = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
                edge.setName(value);
                graph.markDirty();
                plugin.messages().send(sender, "admin.edge-name",
                        "id1", edge.getFrom(), "id2", edge.getTo(), "value", value);
            }
            case "info" -> {
                if (args.length < 4) {
                    plugin.messages().sendLines(sender, "admin.help");
                    return;
                }
                Edge edge = graph.edge(args[2], args[3]);
                if (edge == null) {
                    plugin.messages().send(sender, "admin.edge-unknown", "id1", args[2], "id2", args[3]);
                    return;
                }
                plugin.messages().sendLines(sender, "admin.edge-info",
                        "id1", edge.getFrom(),
                        "id2", edge.getTo(),
                        "transport", TransportType.join(edge.allowedTypes()),
                        "length", Text.distance(edge.getLength()),
                        "oneway", Text.yesNo(edge.isOneWay()),
                        "weight", Text.num(edge.getWeight()),
                        "speed", edge.getSpeed() > 0.0D
                                ? Text.num(edge.getSpeed()) + " бл/с"
                                : plugin.messages().raw("admin.edge-speed-layer"),
                        "line", edge.getLine().isBlank() ? "-" : edge.getLine(),
                        "name", edge.getName().isBlank() ? "-" : edge.getName());
            }
            case "list" -> {
                if (graph.edgeCount() == 0) {
                    plugin.messages().send(sender, "admin.graph-empty");
                    return;
                }
                plugin.messages().sendRaw(sender, "admin.edge-list-header", "count", String.valueOf(graph.edgeCount()));
                int shown = 0;
                for (Edge edge : graph.edges()) {
                    if (shown++ >= LIST_LIMIT) {
                        break;
                    }
                    plugin.messages().sendRaw(sender, "admin.edge-list-entry",
                            "id1", edge.getFrom(),
                            "id2", edge.getTo(),
                            "transport", TransportType.joinIcons(edge.allowedTypes()),
                            "length", Text.distance(edge.getLength()),
                            "line", edge.getLine().isBlank() ? "" : "«" + edge.getLine() + "»");
                }
            }
            default -> plugin.messages().sendLines(sender, "admin.help");
        }
    }

    private void visual(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hahgps.admin.visual")) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return;
        }
        if (args.length < 2) {
            boolean enabled = plugin.visualization().toggleAll(player);
            sendVisualState(player, enabled);
            return;
        }
        String arg = args[1];
        if (arg.equalsIgnoreCase("off")) {
            plugin.visualization().clear(player);
            plugin.messages().send(player, "admin.visual-off");
            return;
        }
        if (arg.equalsIgnoreCase("all")) {
            sendVisualState(player, plugin.visualization().toggleAll(player));
            return;
        }
        TransportType type = TransportType.byId(arg);
        if (type == null) {
            plugin.messages().send(player, "general.unknown-transport", "input", arg, "types", TransportType.ids());
            return;
        }
        boolean enabled = plugin.visualization().toggle(player, type);
        if (enabled) {
            plugin.messages().send(player, "admin.visual-on",
                    "transport", type.icon() + " " + type.displayName());
        } else {
            plugin.messages().send(player, "admin.visual-state",
                    "transport", TransportType.join(plugin.visualization().selected(player)));
        }
    }

    private void sendVisualState(Player player, boolean enabled) {
        if (enabled) {
            plugin.messages().send(player, "admin.visual-on",
                    "transport", TransportType.joinIcons(plugin.visualization().selected(player))
                            + " " + TransportType.join(plugin.visualization().selected(player)));
        } else {
            plugin.messages().send(player, "admin.visual-off");
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("hahgps.admin.reload")) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        plugin.reloadEverything();
        plugin.messages().send(sender, "admin.reloaded",
                "nodes", String.valueOf(plugin.graph().nodeCount()),
                "edges", String.valueOf(plugin.graph().edgeCount()));
    }

    private void save(CommandSender sender) {
        if (!sender.hasPermission("hahgps.admin.reload")) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        if (plugin.storage().save()) {
            plugin.messages().send(sender, "admin.saved", "file", plugin.storage().fileName());
        } else {
            plugin.messages().send(sender, "admin.save-failed");
        }
    }

    private EnumSet<TransportType> parseTypes(CommandSender sender, String input) {
        try {
            return TransportType.parseSet(input);
        } catch (IllegalArgumentException exception) {
            plugin.messages().send(sender, "general.unknown-transport",
                    "input", exception.getMessage(),
                    "types", TransportType.ids());
            return null;
        }
    }

    private static EnumSet<TransportType> defaultTypes(Node first, Node second) {
        EnumSet<TransportType> types = EnumSet.noneOf(TransportType.class);
        for (TransportType type : TransportType.values()) {
            if (first.allows(type) && second.allows(type)) {
                types.add(type);
            }
        }
        return types;
    }

    private static boolean isAdd(String value) {
        return value.equalsIgnoreCase("add") || value.equalsIgnoreCase("+")
                || value.equalsIgnoreCase("добавить");
    }

    private static boolean isRemove(String value) {
        return value.equalsIgnoreCase("remove") || value.equalsIgnoreCase("-")
                || value.equalsIgnoreCase("del") || value.equalsIgnoreCase("удалить");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            addMatching(result, prefix, "node", "edge", "visual", "reload", "save", "help");
            return result;
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("node")) {
            if (args.length == 2) {
                addMatching(result, prefix, "create", "remove", "name", "allowed", "tp", "list", "info");
            } else if (args.length == 3) {
                addNodes(result, prefix);
            } else if (args.length == 4) {
                if (args[1].equalsIgnoreCase("create")) {
                    addTypes(result, prefix);
                } else if (args[1].equalsIgnoreCase("allowed")) {
                    addMatching(result, prefix, "add", "remove");
                }
            } else if (args.length == 5 && args[1].equalsIgnoreCase("allowed")) {
                addTypes(result, prefix);
            }
        } else if (root.equals("edge")) {
            if (args.length == 2) {
                addMatching(result, prefix, "connect", "remove", "allowed", "oneway", "weight", "speed", "surface", "line", "name", "info", "list");
            } else if (args.length == 3 || args.length == 4) {
                addNodes(result, prefix);
            } else if (args.length == 5) {
                if (args[1].equalsIgnoreCase("connect")) {
                    addTypes(result, prefix);
                } else if (args[1].equalsIgnoreCase("allowed")) {
                    addMatching(result, prefix, "add", "remove");
                } else if (args[1].equalsIgnoreCase("oneway")) {
                    addMatching(result, prefix, "true", "false");
                } else if (args[1].equalsIgnoreCase("surface")) {
                    addSurfaces(result, prefix);
                } else if (args[1].equalsIgnoreCase("speed")) {
                    addMatching(result, prefix, "0", "4.5", "9", "20", "34");
                }
            } else if (args.length == 6 && args[1].equalsIgnoreCase("allowed")) {
                addTypes(result, prefix);
            }
        } else if (root.equals("visual") && args.length == 2) {
            addMatching(result, prefix, "all", "off");
            addTypes(result, prefix);
        }
        return result;
    }

    private void addNodes(List<String> result, String prefix) {
        for (Node node : plugin.graph().nodes()) {
            if (node.getId().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(node.getId());
            }
        }
    }

    private static void addTypes(List<String> result, String prefix) {
        for (TransportType type : TransportType.values()) {
            if (type.id().startsWith(prefix)) {
                result.add(type.id());
            }
        }
    }

    private void addSurfaces(List<String> result, String prefix) {
        for (String surface : plugin.config().surfaces().keySet()) {
            if (surface.startsWith(prefix)) {
                result.add(surface);
            }
        }
        if ("none".startsWith(prefix)) {
            result.add("none");
        }
    }

    private static void addMatching(List<String> result, String prefix, String... values) {
        for (String value : values) {
            if (value.startsWith(prefix)) {
                result.add(value);
            }
        }
    }
}
