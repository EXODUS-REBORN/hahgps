package ru.hahgps.command.subcommands;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.model.Category;

import java.util.List;

public class CategoryDeleteCommand implements SubCommand {

    @Override
    public String name() {
        return "delete";
    }

    @Override
    public String usage() {
        return "/gps category delete <имя>";
    }

    @Override
    public String permission() {
        return "gps.category.delete";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (args.length < 1) {
            player.sendMessage(plugin.getMessages().getPrefixed("usage", "usage", usage()));
            return;
        }
        String name = args[0];
        // защита стандартных категорий — как в CategoriesGui, иначе команда
        // обходила бы запрет, который GUI применяет
        ru.hahgps.model.Category existing = plugin.getDataService().getCachedCategoryByName(name);
        if (existing != null && existing.isDefaultCategory()) {
            player.sendMessage(plugin.getMessages().getPrefixed("cannot-delete-default"));
            return;
        }
        // отсутствие в кэше не считаем «не найдено»: категория могла появиться извне —
        // проверку доверяем БД, её результат ниже
        plugin.getCategoryService().deleteCategory(name)
                .whenComplete((deleted, error) -> plugin.getDataService().runSync(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        ru.hahgps.command.SubCommand.sendError(player, error);
                        return;
                    }
                    if (!Boolean.TRUE.equals(deleted)) {
                        player.sendMessage(plugin.getMessages().getPrefixed("category-not-found",
                                "name", name));
                        return;
                    }
                    player.sendMessage(plugin.getMessages().getPrefixed("category-deleted", "name", name));
                }));
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            // кэш вместо запроса БД с join() на главном потоке
            return HahGpsPlugin.getInstance().getDataService().getCachedCategories().stream()
                    .map(Category::getName)
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
