package ru.hahgps.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import ru.hahgps.HahGpsPlugin;

import java.io.File;
import java.util.List;

/**
 * Менеджер конфигурации.
 */
public class ConfigManager {

    private final HahGpsPlugin plugin;
    private FileConfiguration config;

    public ConfigManager(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
    }

    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
    }

    public FileConfiguration get() {
        return config;
    }

    public String getLanguage() {
        return config.getString("language", "ru");
    }

    public String getStorageType() {
        return config.getString("storage.type", "sqlite");
    }

    public String getSqliteFile() {
        return config.getString("storage.sqlite.file", "hahgps.db");
    }

    public String getMysqlHost() {
        return config.getString("storage.mysql.host", "localhost");
    }

    public int getMysqlPort() {
        return config.getInt("storage.mysql.port", 3306);
    }

    public String getMysqlDatabase() {
        return config.getString("storage.mysql.database", "hahgps");
    }

    public String getMysqlUsername() {
        return config.getString("storage.mysql.username", "root");
    }

    public String getMysqlPassword() {
        return config.getString("storage.mysql.password", "password");
    }

    public int getUpdateInterval() {
        int interval = config.getInt("navigation.update-interval", 5);
        return Math.max(1, Math.min(20, interval));
    }

    public double getArrivalRadius() {
        return config.getDouble("navigation.arrival-radius", 2.0);
    }

    public String getBossbarColor() {
        return config.getString("navigation.bossbar-color", "BLUE");
    }

    public String getBossbarStyle() {
        return config.getString("navigation.bossbar-style", "SOLID");
    }

    public boolean isLogHints() {
        return config.getBoolean("navigation.log-hints", true);
    }

    public boolean isShowArrow() {
        return config.getBoolean("navigation.show-arrow", true);
    }

    public int getMapRadius() {
        return config.getInt("map.radius", 64);
    }

    public String getMapZoom() {
        return config.getString("map.zoom", "CLOSE");
    }

    public boolean isMapRenderAsync() {
        return config.getBoolean("map.render-async", true);
    }

    public boolean isMapShowPois() {
        return config.getBoolean("map.show-pois", true);
    }

    public boolean isMapShowRoute() {
        return config.getBoolean("map.show-route", true);
    }

    public boolean isTransportAvailable(String key) {
        return config.getBoolean("transport." + key, false);
    }

    public List<String> getTransportPoints(String key) {
        return config.getStringList("transport." + key);
    }

    public int getSearchMaxResults() {
        return config.getInt("search.max-results", 10);
    }

    public double getSearchThreshold() {
        return config.getDouble("search.similarity-threshold", 0.4);
    }

    public List<String> getDefaultCategories() {
        return config.getStringList("default-categories");
    }
}
