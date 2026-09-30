package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.model.Poi;

import java.util.List;

public class PointRateCommand implements SubCommand {

    @Override
    public String name() {
        return "rate";
    }

    @Override
    public String usage() {
        return "/gps point rate <имя> <1-10>";
    }

    @Override
    public String permission() {
        return "gps.point.rate";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (args.length < 2) {
            player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
            return;
        }
        String name = args[0];
        int rating;
        try {
            rating = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getMessages().getPrefixed("invalid-rating"));
            return;
        }
        if (rating < 1 || rating > 10) {
            player.sendMessage(plugin.getMessages().getPrefixed("invalid-rating"));
            return;
        }
        plugin.getPoiService().ratePoi(name, player.getUniqueId(), rating).thenRun(() ->
                plugin.getDataService().runSync(() -> {
                    if (player.isOnline()) {
                        player.sendMessage(plugin.getMessages().getPrefixed("point-rated",
                                "name", name, "rating", String.valueOf(rating)));
                    }
                }));
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            return HahGpsPlugin.getInstance().getDataService().getPois().thenApply(pois ->
                    pois.stream().map(Poi::getName)
                            .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                            .toList()).join();
        }
        if (args.length == 2) {
            return List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        }
        return List.of();
    }
}
