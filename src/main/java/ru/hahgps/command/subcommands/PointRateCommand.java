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
        final int finalRating = rating;
        // сначала проверяем существование точки: ratePoi молча возвращает null
        // и для несуществующей точки «успех» выглядел бы так же, как для настоящей
        plugin.getPoiService().loadPoiWithRating(name).whenComplete((poi, error) -> {
            if (error != null) {
                ru.hahgps.command.SubCommand.sendError(player, error);
                return;
            }
            if (poi == null) {
                plugin.getDataService().runSync(() -> {
                    if (player.isOnline()) {
                        player.sendMessage(plugin.getMessages().getPrefixed("point-not-found",
                                "name", name));
                    }
                });
                return;
            }
            plugin.getPoiService().ratePoi(name, player.getUniqueId(), finalRating)
                    .whenComplete((v, rateError) -> plugin.getDataService().runSync(() -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (rateError != null) {
                            ru.hahgps.command.SubCommand.sendError(player, rateError);
                            return;
                        }
                        player.sendMessage(plugin.getMessages().getPrefixed("point-rated",
                                "name", name, "rating", String.valueOf(finalRating)));
                    }));
        });
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return HahGpsPlugin.getInstance().getDataService().getCachedPois().stream()
                    .map(Poi::getName)
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .toList();
        }
        if (args.length == 2) {
            return List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        }
        return List.of();
    }
}
