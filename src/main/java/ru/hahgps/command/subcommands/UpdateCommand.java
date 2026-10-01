package ru.hahgps.command.subcommands;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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

    /** Тон палитры hahtojer: основной текст информационных строк. */
    private static final String TEXT = "<#8fc6ff>";
    /** Тон палитры hahtojer: акцент (метки, разделители). */
    private static final String ACCENT = "<#4fd1e0>";

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

    private static Component line(HahGpsPlugin plugin, String mini) {
        return plugin.getMessages().getComponentRaw(mini);
    }

    private static Component text(HahGpsPlugin plugin, String value) {
        return plugin.getMessages().getComponentRaw(TEXT + value);
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        UpdaterService updater = plugin.getUpdater();
        if (updater == null) {
            // хранилище/обновлятор ещё не подняты — отвечаем ключом загрузки, а не тишиной
            player.sendMessage(plugin.getMessages().getPrefixed("loading-data"));
            return;
        }
        // обновлятор отвечает строками без разметки — красим их в тон палитры
        Consumer<String> feedback = message -> player.sendMessage(text(plugin, message));
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> updater.statusLines().forEach(
                    statusLine -> player.sendMessage(line(plugin, ACCENT + "│ " + TEXT + statusLine)));
            case "check" -> {
                player.sendMessage(plugin.getMessages().getPrefixed("update-checking"));
                updater.checkAsync(feedback, false);
            }
            case "install" -> {
                player.sendMessage(plugin.getMessages().getPrefixed("update-downloading"));
                updater.checkAsync(feedback, true);
            }
            case "apply" -> {
                Path staged = updater.stagedJar();
                if (staged == null || !Files.isRegularFile(staged)) {
                    player.sendMessage(plugin.getMessages().getPrefixed("update-nothing-to-apply"));
                    return;
                }

                var result = HotSwap.apply(plugin, staged, true, feedback);
                if (!result.applied() && !"поставлено в очередь".equals(result.reason())
                        && !"перенесено в главный поток".equals(result.reason())) {
                    player.sendMessage(plugin.getMessages().getPrefixed("update-hotswap-failed",
                            "reason", result.reason() == null ? "?" : result.reason()));
                }
            }
            case "reload" -> {
                // stop/reload/start сканируют диск — не держим главный поток
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        updater.stop();
                        updater.reload();
                        updater.start();
                    } catch (Throwable t) {
                        plugin.getLogger().warning("HahGps: не удалось перезагрузить обновлятор: " + t);
                    }
                    SubCommand.runSync(plugin, () -> {
                        if (player.isOnline()) {
                            player.sendMessage(plugin.getMessages().getPrefixed("update-reloaded"));
                        }
                    });
                });
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
        List<String> out = new ArrayList<>();
        for (String sub : SUBS) {
            if (sub.startsWith(prefix)) {
                out.add(sub);
            }
        }
        return out;
    }
}
