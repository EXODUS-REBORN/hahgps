package ru.hahgps.model;

public class Category {

    private final int id;
    private final String name;
    private final boolean defaultCategory;

    public Category(int id, String name, boolean defaultCategory) {
        this.id = id;
        this.name = name;
        this.defaultCategory = defaultCategory;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isDefaultCategory() {
        return defaultCategory;
    }

    @Override
    public String toString() {
        return "Category{" + name + "}";
    }
}
