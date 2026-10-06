package ru.hahgps.command.subcommands;

import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.service.RouteRecordingService;

import java.util.List;
import java.util.Locale;

/**
 * {@code /gps route record start|stop} — запись реального пути игрока,
 * из которого потом собирается граф дорог.
 */
public class RouteRecordCommand implements SubCommand {

    @Override
    public String name() {
        return "record";
    }

    @Override
    public String usage() {
        return "/gps route record <start|stop>";
    }

    @Override
    public String permission() {
        return "gps.route.record";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        RouteRecordingService recording = plugin.getRouteRecordingService();
        if (recording == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("loading-data"));
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> {
                if (recording.isRecording(player)) {
                    player.sendMessage(plugin.getMessages().getPrefixed("route-record-already"));
                    return;
                }
                recording.start(player);
                player.sendMessage(plugin.getMessages().getPrefixed("route-record-started"));
            }
            case "stop" -> {
                if (!recording.isRecording(player)) {
                    player.sendMessage(plugin.getMessages().getPrefixed("route-record-none"));
                    return;
                }
                List<org.bukkit.Location> points = recording.stop(player);
                if (points.size() < 2) {
                    player.sendMessage(plugin.getMessages().getPrefixed("route-record-empty"));
                    return;
                }
                player.sendMessage(plugin.getMessages().getPrefixed("route-record-stopped",
                        "count", String.valueOf(points.size())));
            }
            default -> player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
        }
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("start", "stop").stream().filter(s -> s.startsWith(prefix)).toList();
    }
}
