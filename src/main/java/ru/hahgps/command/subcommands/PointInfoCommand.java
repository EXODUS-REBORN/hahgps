package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.model.Category;
import ru.hahgps.model.Poi;

import java.util.List;

public class PointInfoCommand implements SubCommand {

    @Override
    public String name() {
        return "info";
    }

    @Override
    public String usage() {
        return "/gps point info <имя>";
    }

    @Override
    public String permission() {
        return "gps.use";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (args.length < 1) {
            player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
            return;
        }
        String name = args[0];
        plugin.getPoiService().loadPoiWithRating(name).thenAcceptAsync(poi -> {
            if (poi == null) {
                plugin.getDataService().runSync(() -> {
                    if (player.isOnline()) {
                        player.sendMessage(plugin.getMessages().getPrefixed("point-not-found", "name", name));
                    }
                });
                return;
            }
            plugin.getDataService().runSync(() -> {
                if (!player.isOnline()) {
                    return;
                }
                Category category = plugin.getDataService().getCachedCategory(poi.getCategoryId());
                String categoryName = category != null ? category.getName() : "?";
                String raw = plugin.getMessages().get("point-info",
                        "name", poi.getName(),
                        "category", categoryName,
                        "x", String.format("%.1f", poi.getX()),
                        "y", String.format("%.1f", poi.getY()),
                        "z", String.format("%.1f", poi.getZ()),
                        "world", poi.getWorld(),
                        "rating", String.format("%.1f", poi.getRating()),
                        "votes", String.valueOf(poi.getRatingCount()),
                        "author", poi.getAuthor(),
                        "date", poi.getFormattedDate(),
                        "description", poi.getDescription());
                for (String line : raw.split("\n")) {
                    player.sendMessage(plugin.getMessages().getComponentRaw(line));
                }
            });
        });
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            return HahGpsPlugin.getInstance().getDataService().getPois().thenApply(pois ->
                    pois.stream().map(Poi::getName)
                            .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                            .toList()).join();
        }
        return List.of();
    }
}
