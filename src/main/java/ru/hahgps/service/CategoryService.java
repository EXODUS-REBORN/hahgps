package ru.hahgps.service;

import ru.hahgps.model.Category;
import ru.hahgps.storage.DataService;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Сервис категорий.
 */
public class CategoryService {

    private final DataService dataService;

    public CategoryService(DataService dataService) {
        this.dataService = dataService;
    }

    /**
     * Создать категорию.
     */
    public CompletableFuture<Category> createCategory(String name) {
        return dataService.createCategory(name, false);
    }

    /**
     * Удалить категорию.
     */
    public CompletableFuture<Boolean> deleteCategory(String name) {
        return dataService.getCategoryByName(name).thenCompose(category -> {
            if (category == null) {
                return CompletableFuture.completedFuture(false);
            }
            return dataService.deleteCategory(category.getId());
        });
    }

    /**
     * Список категорий.
     */
    public CompletableFuture<List<Category>> listCategories() {
        return dataService.getCategories();
    }

    /**
     * Создать стандартные категории при первом запуске.
     */
    public CompletableFuture<Void> seedDefaults(List<String> names) {
        return dataService.getCategories().thenCompose(existing -> {
            if (!existing.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
            for (String name : names) {
                future = future.thenCompose(v -> dataService.createCategory(name, true)
                        .thenApply(c -> null));
            }
            return future;
        });
    }
}
