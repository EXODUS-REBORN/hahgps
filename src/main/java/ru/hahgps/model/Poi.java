package ru.hahgps.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Точка интереса (POI).
 */
public class Poi {

    private final int id;
    private final String name;
    private final int categoryId;
    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final String description;
    private final String author;
    private final long createdAt;

    private double rating;
    private int ratingCount;

    public Poi(int id, String name, int categoryId, String world, double x, double y, double z,
               String description, String author, long createdAt) {
        this.id = id;
        this.name = name;
        this.categoryId = categoryId;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.description = description;
        this.author = author;
        this.createdAt = createdAt;
        this.rating = 0.0;
        this.ratingCount = 0;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getCategoryId() {
        return categoryId;
    }

    public String getWorld() {
        return world;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public String getDescription() {
        return description;
    }

    public String getAuthor() {
        return author;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public double getRating() {
        return rating;
    }

    public void setRating(double rating) {
        this.rating = rating;
    }

    public int getRatingCount() {
        return ratingCount;
    }

    public void setRatingCount(int ratingCount) {
        this.ratingCount = ratingCount;
    }

    public Location getLocation() {
        World w = Bukkit.getWorld(world);
        if (w == null) {
            return null;
        }
        return new Location(w, x, y, z);
    }

    public String getFormattedDate() {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(createdAt));
    }

    @Override
    public String toString() {
        return "Poi{" + name + " (" + x + ", " + y + ", " + z + ") @ " + world + "}";
    }
}
