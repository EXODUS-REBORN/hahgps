package ru.hahgps.command.subcommands;

import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.vmodel.VirtualModelsService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /gps vmodel} — модели в мире: статус, маркер, список, синхронизация с панелью,
 * радиус видимости и снятие. Сама логика — {@link VirtualModelsService}.
 */
public class VmodelCommand implements SubCommand {

    private static final List<String> SUBS = List.of(
            "status", "give", "list", "sync", "reload", "radius", "remove", "local");

    @Override
    public String name() {
        return "vmodel";
    }

    @Override
    public String usage() {
        return "/gps vmodel status|give <ник> <ключ>|list [фильтр]|sync|reload|radius <4-128>|remove";
    }

    @Override
    public String permission() {
        return "gps.models";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        VirtualModelsService models = plugin.getVmodels();
        if (models == null || !models.enabled()) {
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-disabled"));
            return;
        }
        if (args.length == 0) {
            status(player, plugin, models);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(player, plugin, models);
            case "sync" -> sync(player, plugin, models);
            case "give" -> give(player, plugin, models, args);
            case "reload" -> reload(player, plugin, models);
            case "remove" -> models.removeLooking(player);
            case "radius" -> radius(player, plugin, models, args);
            case "list" -> list(player, plugin, models, args);
            case "local" -> local(player, plugin, models);
            default -> player.sendMessage(plugin.getMessages().getPrefixed("invalid-args",
                    "usage", usage()));
        }
    }

    private void status(Player player, HahGpsPlugin plugin, VirtualModelsService models) {
        player.sendMessage(plugin.getMessages().getPrefixed("vmodel-header"));
        player.sendMessage(plugin.getMessages().getPrefixed("vmodel-status-line",
                "status", models.statusLine()));
        long last = models.lastSyncMs();
        if (last > 0) {
            long ago = Math.max(0, (System.currentTimeMillis() - last) / 1000L);
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-last-sync",
                    "seconds", String.valueOf(ago)));
        }
        String error = models.lastSyncError();
        if (error != null && !error.isBlank()) {
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-sync-error", "error", error));
        }
    }

    private void sync(Player player, HahGpsPlugin plugin, VirtualModelsService models) {
        if (!player.hasPermission("gps.models.admin") && !player.hasPermission("gps.admin")) {
            player.sendMessage(plugin.getMessages().getPrefixed("no-permission"));
            return;
        }
        if (!models.panel().configured()) {
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-no-token"));
            return;
        }
        player.sendMessage(plugin.getMessages().getPrefixed("vmodel-sync-started"));
        models.syncAsync(true);
    }

    private void give(Player player, HahGpsPlugin plugin, VirtualModelsService models, String[] args) {
        if (!player.hasPermission("gps.models.give") && !player.hasPermission("gps.admin")) {
            player.sendMessage(plugin.getMessages().getPrefixed("no-permission"));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-give-usage"));
            return;
        }
        Player target = args.length >= 3 ? plugin.getServer().getPlayerExact(args[1]) : player;
        String key = args.length >= 3 ? args[2] : args[1];
        if (target == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-offline",
                    "player", args[1]));
            return;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        if (!models.giveMarker(target, normalized, false)) {
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-not-found",
                    "model", normalized));
            return;
        }
        player.sendMessage(plugin.getMessages().getPrefixed("vmodel-gave",
                "model", normalized, "player", target.getName()));
    }

    private void reload(Player player, HahGpsPlugin plugin, VirtualModelsService models) {
        if (!player.hasPermission("gps.models.admin") && !player.hasPermission("gps.admin")) {
            player.sendMessage(plugin.getMessages().getPrefixed("no-permission"));
            return;
        }
        models.reload();
        models.loadRadius();
        models.reloadLocal();
        models.rebuildSolids();
        models.refreshAll();
        player.sendMessage(plugin.getMessages().getPrefixed("vmodel-reloaded"));
    }

    private void radius(Player player, HahGpsPlugin plugin, VirtualModelsService models,
                        String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-radius-now",
                    "radius", String.valueOf(models.scheduler().radiusOf(player.getUniqueId()))));
            return;
        }
        int value;
        try {
            value = Integer.parseInt(args[1]);
        } catch (NumberFormatException broken) {
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-radius-number"));
            return;
        }
        models.scheduler().setRadius(player.getUniqueId(), value);
        models.saveRadius();
        models.scheduler().refreshPlayer(player);
        player.sendMessage(plugin.getMessages().getPrefixed("vmodel-radius-set",
                "radius", String.valueOf(models.scheduler().radiusOf(player.getUniqueId()))));
    }

    private void list(Player player, HahGpsPlugin plugin, VirtualModelsService models, String[] args) {
        String filter = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        List<String> keys = new ArrayList<>();
        for (String key : models.keys()) {
            if (filter.isEmpty() || key.contains(filter)) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-list-empty"));
            return;
        }
        int limit = Math.min(40, keys.size());
        player.sendMessage(plugin.getMessages().getPrefixed("vmodel-list-header",
                "total", String.valueOf(keys.size())));
        for (int i = 0; i < limit; i++) {
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-list-line",
                    "index", String.valueOf(i + 1), "model", keys.get(i)));
        }
        if (keys.size() > limit) {
            player.sendMessage(plugin.getMessages().getPrefixed("vmodel-list-cut",
                    "left", String.valueOf(keys.size() - limit)));
        }
    }

    private void local(Player player, HahGpsPlugin plugin, VirtualModelsService models) {
        if (!player.hasPermission("gps.models.admin") && !player.hasPermission("gps.admin")) {
            player.sendMessage(plugin.getMessages().getPrefixed("no-permission"));
            return;
        }
        models.ensureLocalSample();
        player.sendMessage(plugin.getMessages().getPrefixed("vmodel-local-file",
                "file", models.modelsFile().getName()));
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            return SubCommand.filterByPrefix(SUBS, args[0]);
        }
        VirtualModelsService models = HahGpsPlugin.getInstance().getVmodels();
        if (models == null) {
            return List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> names = new ArrayList<>();
            for (Player online : HahGpsPlugin.getInstance().getServer().getOnlinePlayers()) {
                names.add(online.getName());
            }
            return SubCommand.filterByPrefix(names, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
            return SubCommand.filterByPrefix(models.keys(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return SubCommand.filterByPrefix(models.keys(), args[2]);
        }
        return List.of();
    }
}
