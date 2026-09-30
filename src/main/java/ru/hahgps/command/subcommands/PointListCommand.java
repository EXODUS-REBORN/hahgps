package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.model.Category;
import ru.hahgps.model.Poi;
import ru.hahgps.util.LocationUtil;

import java.util.List;

public class PointListCommand implements SubCommand {

    @Override
    public String name() {
        return "list";
    }

    @Override
    public String usage() {
        return "/gps point list";
    }

    @Override
    public String permission() {
        return "gps.use";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        plugin.getPoiService().loadAllPoisWithRatings().thenAcceptAsync(pois -> {
            plugin.getDataService().runSync(() -> {
                if (!player.isOnline()) {
                    return;
                }
                var messages = plugin.getMessages();
                player.sendMessage(messages.getPrefixed("point-list-header", "count", String.valueOf(pois.size())));
                for (Poi poi : pois) {
                    Category category = plugin.getDataService().getCachedCategory(poi.getCategoryId());
                    String categoryName = category != null ? category.getName() : "?";
                    double distance = plugin.getPoiService().distanceTo(player, poi);
                    String distanceStr = distance == Double.MAX_VALUE ? "—" : LocationUtil.formatDistance(distance);
                    player.sendMessage(messages.getPrefixed("point-list-entry",
                            "name", poi.getName(),
                            "category", categoryName,
                            "rating", String.format("%.0f", poi.getRating()),
                            "distance", distanceStr));
                }
            });
        });
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        return List.of();
    }
}
