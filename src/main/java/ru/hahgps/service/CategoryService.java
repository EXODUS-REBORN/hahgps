package ru.hahgps.service;
import ru.hahgps.model.Category;
import ru.hahgps.storage.DataService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
public class CategoryService {

    /**
     * Почему категорию нельзя удалить — вызывающий код показывает разные тексты,
     * а не одно «не найдено» на все случаи.
     */
    public enum DeleteResult {
        DELETED,
        NOT_FOUND,
        NOT_EMPTY,
        DEFAULT_CATEGORY
    }

    private final DataService dataService;

    public CategoryService(DataService dataService) {
        this.dataService = dataService;
    }

    public CompletableFuture<Category> createCategory(String name) {
        return dataService.createCategory(name, false);
    }

    /**
     * Удаление категории с проверками: не стандартная и не занята точками.
     * Раньше удаление проходило молча, а точки оставались со ссылкой на
     * несуществующую категорию и пропадали из всех списков.
     */
    public CompletableFuture<DeleteResult> delete(String name) {
        return dataService.getCategoryByName(name).thenCompose(category -> {
            if (category == null) {
                return CompletableFuture.completedFuture(DeleteResult.NOT_FOUND);
            }
            if (category.isDefaultCategory()) {
                return CompletableFuture.completedFuture(DeleteResult.DEFAULT_CATEGORY);
            }
            return dataService.countPoisInCategory(category.getId()).thenCompose(count -> {
                if (count != null && count > 0) {
                    return CompletableFuture.completedFuture(DeleteResult.NOT_EMPTY);
                }
                return dataService.deleteCategory(category.getId())
                        .thenApply(deleted -> Boolean.TRUE.equals(deleted)
                                ? DeleteResult.DELETED
                                : DeleteResult.NOT_FOUND);
            });
        });
    }

    public CompletableFuture<List<Category>> listCategories() {
        return dataService.getCategories();
    }

    public CompletableFuture<Void> seedDefaults(List<String> names) {
        return dataService.getCategories().thenCompose(existing -> {
            if (!existing.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
            if (names == null) {
                return future;
            }
            for (String name : names) {
                future = future.thenCompose(v -> dataService.createCategory(name, true)
                        .thenApply(c -> null));
            }
            return future;
        }).exceptionally(error -> {
            ru.hahgps.HahGpsPlugin plugin = ru.hahgps.HahGpsPlugin.getInstance();
            if (plugin != null) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "HahGps: не удалось создать стандартные категории", error);
            }
            return null;
        });
    }
}
