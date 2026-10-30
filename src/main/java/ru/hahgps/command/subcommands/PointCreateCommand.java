package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;

import java.util.List;

public class PointCreateCommand implements SubCommand {

    /** Длина колонки name в БД (VARCHAR(64)). */
    private static final int MAX_NAME = 64;

    @Override
    public String name() {
        return "create";
    }

    @Override
    public String usage() {
        return "/gps point create <имя> [категория] [описание]";
    }

    @Override
    public String permission() {
        return "gps.point.create";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (args.length < 1) {
            player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
            return;
        }
        String name = args[0];
        if (name.length() > MAX_NAME) {
            // Колонка в БД — VARCHAR(64): SQLite длину не проверяет, MySQL
            // падает с data-too-long. Игрок должен получить один и тот же
            // ответ на любой базе.
            player.sendMessage(plugin.getMessages().getPrefixed("name-too-long",
                    "max", String.valueOf(MAX_NAME)));
            return;
        }
        String category = args.length > 1 ? args[1] : null;
        String description = args.length > 2 ? join(args, 2) : "";
        if (args.length > 1) {
            // Названия категорий могут содержать пробелы (по умолчанию —
            // «Близкие места»), а аргументы команды режутся по пробелам.
            // Берём самое длинное совпадение с существующей категорией,
            // остаток уходит в описание.
            int words = matchingCategoryWords(args, 1);
            if (words > 0) {
                category = join(args, 1, 1 + words);
                description = args.length > 1 + words ? join(args, 1 + words) : "";
            }
        }

        final String categoryName = category;
        final String descriptionText = description;
        plugin.getPoiService().createPoi(player, name, categoryName, descriptionText)
                .whenComplete((outcome, error) -> plugin.getDataService().runSync(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        if (ru.hahgps.command.SubCommand.isDuplicate(error)) {
                            player.sendMessage(plugin.getMessages().getPrefixed("point-exists",
                                    "name", name));
                        } else {
                            ru.hahgps.command.SubCommand.sendError(player, error);
                        }
                        return;
                    }
                    if (outcome == null || outcome.poi() == null) {
                        // Категорий нет вовсе: точку некуда положить.
                        player.sendMessage(plugin.getMessages().getPrefixed("point-no-categories"));
                        return;
                    }
                    if (outcome.fallbackCategory() != null) {
                        player.sendMessage(plugin.getMessages().getPrefixed("point-category-fallback",
                                "name", categoryName,
                                "category", outcome.fallbackCategory().getName()));
                    }
                    player.sendMessage(plugin.getMessages().getPrefixed("point-created", "name", name));
                }));
    }

    /** Сколько слов начиная с {@code from} совпадают с названием категории. */
    private static int matchingCategoryWords(String[] args, int from) {
        int best = 0;
        var dataService = HahGpsPlugin.getInstance().getDataService();
        if (dataService == null) {
            return 0;
        }
        for (var category : dataService.getCachedCategories()) {
            String[] words = category.getName().trim().split("\\s+");
            if (words.length <= best || from + words.length > args.length) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < words.length; i++) {
                if (!words[i].equalsIgnoreCase(args[from + i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                best = words.length;
            }
        }
        return best;
    }

    private static String join(String[] args, int from) {
        return join(args, from, args.length);
    }

    private static String join(String[] args, int from, int to) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, Math.min(to, args.length)));
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            return args[0].isEmpty() ? List.of("<имя>") : List.of();
        }
        if (args.length == 2) {
            return HahGpsPlugin.getInstance().getDataService().getCachedCategories().stream()
                    .map(c -> c.getName())
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
