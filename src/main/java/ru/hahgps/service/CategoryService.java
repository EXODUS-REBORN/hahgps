package ru.hahgps.service;
import ru.hahgps.model.Category;
import ru.hahgps.storage.DataService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
public class CategoryService {

    private final DataService dataService;

    public CategoryService(DataService dataService) {
        this.dataService = dataService;
    }

    public CompletableFuture<Category> createCategory(String name) {
        return dataService.createCategory(name, false);
    }

    public CompletableFuture<Boolean> deleteCategory(String name) {
        return dataService.getCategoryByName(name).thenCompose(category -> {
            if (category == null) {
                return CompletableFuture.completedFuture(false);
            }
            return dataService.deleteCategory(category.getId());
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
            for (String name : names) {
                future = future.thenCompose(v -> dataService.createCategory(name, true)
                        .thenApply(c -> null));
            }
            return future;
        });
    }
}
