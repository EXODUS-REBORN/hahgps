package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.update.HotSwap;
import ru.hahgps.update.UpdaterService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class UpdateCommand implements SubCommand {

    private static final List<String> SUBS = List.of("status", "check", "install", "apply", "reload");

    @Override
    public String name() {
        return "update";
    }

    @Override
    public String usage() {
        return "/gps update <status|check|install|apply|reload>";
    }

    @Override
    public String permission() {
        return "gps.admin";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        UpdaterService updater = plugin.getUpdater();
        if (updater == null) {
            player.sendMessage("§9Обновление ещё не готово: хранилище загружается, повтори позже.");
            return;
        }
        Consumer<String> feedback = text -> player.sendMessage("§b" + text);
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> updater.statusLines().forEach(line -> player.sendMessage("§1│ §9" + line));
            case "check" -> {
                player.sendMessage("§9Проверяю релизы…");
                updater.checkAsync(feedback, false);
            }
            case "install" -> {
                player.sendMessage("§9Проверяю и скачиваю новую версию…");
                updater.checkAsync(feedback, true);
            }
            case "apply" -> {
                Path staged = updater.stagedJar();
                if (staged == null || !Files.isRegularFile(staged)) {
                    player.sendMessage("§9Применять нечего: подготовленного обновления нет "
                            + "(сначала /gps update install).");
                    return;
                }

                var result = HotSwap.apply(plugin, staged, true, feedback);
                if (!result.applied() && !"поставлено в очередь".equals(result.reason())
                        && !"перенесено в главный поток".equals(result.reason())) {
                    player.sendMessage("§9Горячая подмена не удалась: " + result.reason());
                }
            }
            case "reload" -> {
                updater.stop();
                updater.reload();
                updater.start();
                player.sendMessage("§bНастройки обновления перечитаны.");
            }
            default -> player.sendMessage("§1│ §9" + usage());
        }
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String sub : SUBS) {
            if (sub.startsWith(prefix)) {
                out.add(sub);
            }
        }
        return out;
    }
}
