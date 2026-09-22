package ru.hahgps.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import ru.hahgps.HahGPSPlugin;
import ru.hahgps.model.Node;
import ru.hahgps.model.TransportType;
import ru.hahgps.model.Vec;
import ru.hahgps.util.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class GpsCommand implements TabExecutor {

    private static final int LIST_LIMIT = 25;

    private final HahGPSPlugin plugin;

    public GpsCommand(HahGPSPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.players-only");
            return true;
        }
        if (!player.hasPermission("hahgps.use")) {
            plugin.messages().send(player, "general.no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.messages().sendLines(player, "gps.help", "types", TransportType.ids());
            return true;
        }

        String first = args[0];
        String lower = first.toLowerCase(Locale.ROOT);

        switch (lower) {
            case "off", "stop", "cancel" -> {
                plugin.navigation().stop(player, "gps.stopped");
                return true;
            }
            case "list", "points" -> {
                sendList(player);
                return true;
            }
            case "help", "?" -> {
                plugin.messages().sendLines(player, "gps.help", "types", TransportType.ids());
                return true;
            }
            default -> {

            }
        }

        TransportType transport = TransportType.FOOT;
        if (args.length >= 2) {
            TransportType parsed = TransportType.byId(args[1]);
            if (parsed == null) {
                plugin.messages().send(player, "general.unknown-transport",
                        "input", args[1],
                        "types", TransportType.ids());
                return true;
            }
            transport = parsed;
        }

        Node destination = plugin.graph().findByRef(first);
        if (destination == null) {
            plugin.messages().send(player, "general.unknown-node", "ref", first);
            return true;
        }
        if (destination.location() == null) {
            plugin.messages().send(player, "general.world-missing", "world", destination.getWorld());
            return true;
        }

        plugin.navigation().start(player, destination, transport);
        return true;
    }

    private void sendList(Player player) {
        List<Node> nodes = new ArrayList<>(plugin.graph().nodes());
        if (nodes.isEmpty()) {
            plugin.messages().send(player, "admin.graph-empty");
            return;
        }
        Vec position = Vec.of(player.getLocation());
        nodes.sort(Comparator.comparingDouble(node -> node.vec().distanceTo(position)));
        plugin.messages().sendRaw(player, "gps.list-header", "count", String.valueOf(nodes.size()));
        int limit = Math.min(nodes.size(), LIST_LIMIT);
        for (int i = 0; i < limit; i++) {
            Node node = nodes.get(i);
            double distance = node.vec().distanceTo(position);
            String distanceText = node.vec().sameWorld(position)
                    ? "(" + Text.distance(distance) + ")"
                    : "(" + node.getWorld() + ")";
            if (node.getName().isBlank()) {
                plugin.messages().sendRaw(player, "gps.list-entry-no-name",
                        "id", node.getId(),
                        "transport", TransportType.joinIcons(node.allowedTypes()));
            } else {
                plugin.messages().sendRaw(player, "gps.list-entry",
                        "id", node.getId(),
                        "transport", TransportType.joinIcons(node.allowedTypes()),
                        "name", node.getName(),
                        "distance", distanceText);
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : List.of("off", "list", "help")) {
                if (sub.startsWith(prefix)) {
                    result.add(sub);
                }
            }
            for (Node node : plugin.graph().nodes()) {
                if (node.getId().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(node.getId());
                }
            }
        } else if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (TransportType type : TransportType.values()) {
                if (type.id().startsWith(prefix)) {
                    result.add(type.id());
                }
            }
        }
        return result;
    }
}
