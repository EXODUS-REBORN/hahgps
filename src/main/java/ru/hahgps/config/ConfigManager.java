package ru.hahgps.config;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.map.MapView;
import ru.hahgps.HahGpsPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Чтение конфигурации.
 *
 * Все значения, которые потребители парсят через {@code valueOf} или которые
 * участвуют в арифметике, проверяются здесь: опечатка в config.yml не должна
 * ронять плагин посреди игры необработанным IllegalArgumentException.
 */
public class ConfigManager {

    /** Языки, для которых в jar есть messages_XX.yml. */
    private static final List<String> LANGUAGES = List.of("ru", "en");

    private static final List<String> DEFAULT_CATEGORIES = List.of("Общее", "Магазины", "Развлечения");

    private final HahGpsPlugin plugin;
    private FileConfiguration config;

    public ConfigManager(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        validate();
    }

    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        validate();
    }

    /**
     * Проверка значений с деградацией к дефолту и предупреждением в лог.
     * Вызывается после каждой (пере)загрузки конфига.
     */
    private void validate() {
        if (config == null) {
            return;
        }
        checkEnum("navigation.bossbar-color", "BLUE", BarColor::valueOf, v -> v != null);
        checkEnum("navigation.bossbar-style", "SOLID", BarStyle::valueOf, v -> v != null);
        checkEnum("map.zoom", "CLOSE", MapView.Scale::valueOf, v -> v != null);

        String language = config.getString("language", "ru");
        if (language == null || !LANGUAGES.contains(language.toLowerCase(Locale.ROOT))) {
            warn("language", String.valueOf(language),
                    "нет messages_" + language + ".yml в плагине (доступны: ru, en) — использую ru");
            config.set("language", "ru");
        }

        double radius = config.getDouble("navigation.arrival-radius", 2.0);
        if (!Double.isFinite(radius) || radius <= 0.0) {
            warn("navigation.arrival-radius", String.valueOf(radius),
                    "радиус прибытия должен быть положительным числом — использую 2.0");
            config.set("navigation.arrival-radius", 2.0);
        }

        double threshold = config.getDouble("search.similarity-threshold", 0.4);
        if (!Double.isFinite(threshold) || threshold < 0.0 || threshold > 1.0) {
            warn("search.similarity-threshold", String.valueOf(threshold),
                    "порог похожести должен быть в диапазоне 0..1 — использую 0.4");
            config.set("search.similarity-threshold", 0.4);
        }

        int port = config.getInt("storage.mysql.port", 3306);
        if (port < 1 || port > 65535) {
            warn("storage.mysql.port", String.valueOf(port),
                    "порт вне диапазона 1..65535 — использую 3306");
            config.set("storage.mysql.port", 3306);
        }
    }

    private <T> void checkEnum(String path, String fallback, java.util.function.Function<String, T> parser,
                               java.util.function.Predicate<T> validator) {
        String raw = config.getString(path, fallback);
        try {
            T value = parser.apply(raw.toUpperCase(Locale.ROOT));
            if (value != null && validator.test(value)) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // невалидное имя — сообщим ниже
        }
        warn(path, String.valueOf(raw), "неизвестное значение — использую " + fallback);
        config.set(path, fallback);
    }

    private void warn(String path, String value, String detail) {
        plugin.getLogger().warning("HahGps: config.yml, " + path + " = «" + value + "»: " + detail + ".");
    }

    public FileConfiguration get() {
        return config;
    }

    /** Язык интерфейса: только те, для которых есть messages_XX.yml в jar. */
    public String getLanguage() {
        String language = config.getString("language", "ru");
        if (language != null && LANGUAGES.contains(language.toLowerCase(Locale.ROOT))) {
            return language.toLowerCase(Locale.ROOT);
        }
        return "ru";
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

    /** Шифрование соединения с MySQL: значение из конфига, а не жёсткий false. */
    public boolean getMysqlUseSsl() {
        return config.getBoolean("storage.mysql.use-ssl", false);
    }

    public int getUpdateInterval() {
        int interval = config.getInt("navigation.update-interval", 5);
        return Math.max(1, Math.min(20, interval));
    }

    public double getArrivalRadius() {
        double radius = config.getDouble("navigation.arrival-radius", 2.0);
        return Double.isFinite(radius) && radius > 0.0 ? radius : 2.0;
    }

    /** Цвет боссбара. Значение уже проверено в validate(), но переживаем и прямой вызов. */
    public String getBossbarColor() {
        String name = config.getString("navigation.bossbar-color", "BLUE");
        return isEnumValue(name, BarColor.values()) ? name.toUpperCase(Locale.ROOT) : "BLUE";
    }

    public String getBossbarStyle() {
        String name = config.getString("navigation.bossbar-style", "SOLID");
        return isEnumValue(name, BarStyle.values()) ? name.toUpperCase(Locale.ROOT) : "SOLID";
    }

    private static boolean isEnumValue(String name, Object[] values) {
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return Arrays.stream(values).anyMatch(v -> upper.equals(((Enum<?>) v).name()));
    }

    public boolean isLogHints() {
        return config.getBoolean("navigation.log-hints", true);
    }

    public boolean isShowArrow() {
        return config.getBoolean("navigation.show-arrow", true);
    }

    /**
     * HUD ItemsAdder рисует картинку поверх экрана — по умолчанию выключен,
     * всё направление показывает боссбар вверху.
     */
    public boolean isHudEnabled() {
        return config.getBoolean("navigation.hud", false);
    }

    public String getMapZoom() {
        String name = config.getString("map.zoom", "CLOSE");
        return isEnumValue(name, MapView.Scale.values()) ? name.toUpperCase(Locale.ROOT) : "CLOSE";
    }

    public boolean isMapShowPois() {
        return config.getBoolean("map.show-pois", true);
    }

    public boolean isMapShowRoute() {
        return config.getBoolean("map.show-route", true);
    }

    public boolean isMapShowPlayers() {
        return config.getBoolean("map.show-players", false);
    }

    public boolean isTransportAvailable(String key) {
        return config.getBoolean("transport." + key, false);
    }

    public int getSearchMaxResults() {
        return config.getInt("search.max-results", 10);
    }

    public double getSearchThreshold() {
        double threshold = config.getDouble("search.similarity-threshold", 0.4);
        return Double.isFinite(threshold) ? Math.max(0.0, Math.min(1.0, threshold)) : 0.4;
    }

    /**
     * Категории по умолчанию. Старый config.yml без этого ключа получил бы
     * пустой список, и тогда точки было бы некуда крепить — поэтому есть
     * встроенный набор.
     */
    public List<String> getDefaultCategories() {
        List<String> configured = config.getStringList("default-categories");
        if (configured == null || configured.isEmpty()) {
            return DEFAULT_CATEGORIES;
        }
        return configured;
    }
}
