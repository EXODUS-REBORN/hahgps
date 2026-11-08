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

        // Конфиги до 1.1.9: радиус локатора был 200 блоков, теперь по умолчанию
        // 20. Явно изменённое значение не трогаем — переносим только старый дефолт.
        if (!config.contains("itrak.scan-highlight-seconds")
                && config.getInt("itrak.scan-radius", 200) == 200) {
            config.set("itrak.scan-radius", 20);
        }
        // До 1.3.1 локаторы были на 10 и 15 блоков — старые предметы и конфиги
        // поднимаем до 20 и 30. Значение 10/15 — это прежний дефолт, а не выбор
        // админа: другой радиус (например, 5 или 50) не трогаем.
        if (config.getInt("itrak.scan-radius", 20) == 10) {
            config.set("itrak.scan-radius", 20);
        }
        if (config.getInt("itrak.locator-long-radius", 30) == 15) {
            config.set("itrak.locator-long-radius", 30);
        }

        checkRange("itrak.update-interval", 20, 5, 200);
        checkRange("itrak.stationary-minutes", 1, 1, 600);
        checkRange("itrak.particle-radius", 48, 8, 128);
        checkRange("itrak.scan-radius", 20, 1, 1000);
        checkRange("itrak.locator-long-radius", 30, 1, 1000);
        checkRange("itrak.scan-attempts", 5, 1, 10);
        checkRange("itrak.scan-cooldown-seconds", 300, 0, 86400);
        checkRange("itrak.scan-cooldown-long-seconds", 420, 0, 86400);
        checkRange("itrak.signal-range", 100_000, 0, 30_000_000);
        checkRange("itrak.signal-region-size", 512, 16, 4096);
        checkRange("itrak.detach-after-minutes", 30, 0, 10080);
        checkRange("itrak.take-radius", 5, 1, 32);
        checkRange("itrak.max-per-player", 8, 1, 64);
        checkRange("itrak.watch-interval-seconds", 5, 1, 600);
        checkRange("itrak.save-interval-seconds", 30, 5, 3600);
        checkRange("itrak.live-route-rebuild-seconds", 10, 2, 300);
        checkRange("itrak.live-route-rebuild-blocks", 16, 2, 256);
        checkRange("itrak.terminal-radius", 5, 1, 32);
        checkRange("itrak.terminal-highlight-seconds", 10, 1, 120);
        checkRange("itrak.scan-highlight-seconds", 10, 1, 120);
        checkRange("map.refresh-seconds", 5, 1, 60);
        checkRange("navigation.walk-max-distance", 128, 8, 512);
    }

    /** Числовая настройка с диапазоном: опечатка не должна ронять игру. */
    private void checkRange(String path, int fallback, int min, int max) {
        int value = config.getInt(path, fallback);
        if (value < min || value > max) {
            warn(path, String.valueOf(value),
                    "значение вне диапазона " + min + ".." + max + " — использую " + fallback);
            config.set(path, fallback);
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
        int interval = config.getInt("navigation.update-interval", 10);
        return Math.max(10, Math.min(40, interval));
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

    /** Свои трекеры iTrak на карте: цветные маячки, офлайн — контуром. */
    public boolean isMapShowTrackers() {
        return config.getBoolean("map.show-trackers", true);
    }

    /** Мышь двигает карту в руке (панорама без ходьбы), теперь — только с Shift. */
    public boolean isMapMousePan() {
        return config.getBoolean("map.mouse-pan", true);
    }

    /** Карта сама едет за игроком, а не остаётся на месте после каждого шага. */
    public boolean isMapFollowPlayer() {
        return config.getBoolean("map.follow-player", true);
    }

    /** Как часто обновлять маркеры на открытой карте, секунд. */
    public int getMapRefreshSeconds() {
        return Math.max(1, Math.min(60, config.getInt("map.refresh-seconds", 5)));
    }

    /** Чувствительность мышиного сдвига карты. */
    public double getMapPanSensitivity() {
        double value = config.getDouble("map.mouse-pan-sensitivity", 1.0);
        return Double.isFinite(value) ? Math.max(0.1, Math.min(5.0, value)) : 1.0;
    }

    /** Кэш карты мира: исследованные места не пропадают без загруженных чанков. */
    public boolean isMapWorldCache() {
        return config.getBoolean("map.world-cache", true);
    }

    /** Блоков на пиксель в кэше карты мира: 4, 8 или 16. */
    public int getMapCacheBlocksPerPixel() {
        int value = config.getInt("map.world-cache-blocks-per-pixel", 8);
        if (value <= 0) {
            return 8;
        }
        return Math.max(4, Math.min(16, value));
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

    // ------------------------------------------------------------------
    // iTrak: GPS-трекеры
    // ------------------------------------------------------------------

    public boolean isItrakEnabled() {
        return config.getBoolean("itrak.enabled", true);
    }

    /** Период тика трекеров в тиках: чаще раза в 5 тиков опрашивать сущности незачем. */
    public int getItrakUpdateInterval() {
        int value = config.getInt("itrak.update-interval", 20);
        return Math.max(5, Math.min(200, value));
    }

    /** Через сколько неподвижности трекер считается стоящим и предлагает маршрут. */
    public long getItrakStationaryMillis() {
        int minutes = Math.max(1, Math.min(600, config.getInt("itrak.stationary-minutes", 1)));
        return minutes * 60_000L;
    }

    public boolean isItrakStationaryNotify() {
        return config.getBoolean("itrak.stationary-notify", true);
    }

    /** Искать пеший путь по блокам, когда граф дорог не даёт маршрута. */
    public boolean isWalkFallbackEnabled() {
        return config.getBoolean("navigation.walk-fallback", true);
    }

    /** Насколько далеко искать пеший путь по блокам. */
    public double getWalkMaxDistance() {
        return Math.max(8, Math.min(512, config.getInt("navigation.walk-max-distance", 128)));
    }

    public boolean isItrakParticles() {
        return config.getBoolean("itrak.particles", true);
    }

    /**
     * Частицы-маркер видит только владелец трекера. Так чужой игрок не
     * замечает, что за ним следят, а найти трекер по-прежнему можно локатором
     * (он подсвечивает находку конкретному нашедшему).
     */
    public boolean isItrakParticlesOwnerOnly() {
        return config.getBoolean("itrak.particles-owner-only", true);
    }

    /** Радиус дальнего локатора (версия на 30 блоков), блоков. */
    public int getItrakLocatorLongRadius() {
        return Math.max(1, Math.min(1000, config.getInt("itrak.locator-long-radius", 30)));
    }

    /** Сколько удачных сканирований подряд выдерживает локатор до перезарядки. */
    public int getItrakScanAttempts() {
        return Math.max(1, Math.min(10, config.getInt("itrak.scan-attempts", 5)));
    }

    /**
     * Дальность сигнала, блоков: дальше трекер не отдаёт точные координаты.
     * 0 — без ограничения (точное место видно всегда).
     */
    public int getItrakSignalRange() {
        return Math.max(0, Math.min(30_000_000, config.getInt("itrak.signal-range", 100_000)));
    }

    /** Размер «квадрата» приблизительного района при потере сигнала, блоков. */
    public int getItrakSignalRegionSize() {
        return Math.max(16, Math.min(4096, config.getInt("itrak.signal-region-size", 512)));
    }

    /**
     * Через сколько минут без найденной сущности привязанный трекер
     * перестаёт считаться прицепленным. 0 — никогда (остаётся висеть).
     */
    public long getItrakDetachAfterMillis() {
        int minutes = Math.max(0, Math.min(10080, config.getInt("itrak.detach-after-minutes", 30)));
        return minutes * 60_000L;
    }

    public double getItrakParticleRadius() {
        return Math.max(8, Math.min(128, config.getInt("itrak.particle-radius", 48)));
    }

    public double getItrakScanRadius() {
        return Math.max(1, Math.min(1000, config.getInt("itrak.scan-radius", 20)));
    }

    /** Сколько секунд локатор подсвечивает найденные трекеры. */
    public int getItrakScanHighlightSeconds() {
        return Math.max(1, Math.min(120, config.getInt("itrak.scan-highlight-seconds", 10)));
    }

    /** Перезарядка обычного локатора, секунд (0 — без КД). */
    public int getItrakScanCooldownSeconds() {
        return Math.max(0, Math.min(86400, config.getInt("itrak.scan-cooldown-seconds", 300)));
    }

    /** Перезарядка дальнего локатора, секунд (0 — без КД). */
    public int getItrakScanCooldownLongSeconds() {
        return Math.max(0, Math.min(86400, config.getInt("itrak.scan-cooldown-long-seconds", 420)));
    }

    public double getItrakTakeRadius() {
        return Math.max(1, Math.min(32, config.getInt("itrak.take-radius", 5)));
    }

    public int getItrakMaxPerPlayer() {
        return Math.max(1, Math.min(64, config.getInt("itrak.max-per-player", 8)));
    }

    public int getItrakWatchIntervalSeconds() {
        return Math.max(1, Math.min(600, config.getInt("itrak.watch-interval-seconds", 5)));
    }

    public int getItrakSaveIntervalSeconds() {
        return Math.max(5, Math.min(3600, config.getInt("itrak.save-interval-seconds", 30)));
    }

    public long getItrakLiveRouteRebuildMillis() {
        return Math.max(2, Math.min(300, config.getInt("itrak.live-route-rebuild-seconds", 10))) * 1000L;
    }

    public double getItrakLiveRouteRebuildBlocks() {
        return Math.max(2, Math.min(256, config.getInt("itrak.live-route-rebuild-blocks", 16)));
    }

    /** Можно ли прицепить трекер к игроку (слежка за игроком). */
    public boolean isItrakAttachPlayers() {
        return config.getBoolean("itrak.attach-players", true);
    }

    /** Радиус терминала: трекеры в нём показываются по нажатию на блок. */
    public double getItrakTerminalRadius() {
        return Math.max(1, Math.min(32, config.getInt("itrak.terminal-radius", 5)));
    }

    public int getItrakTerminalHighlightSeconds() {
        return Math.max(1, Math.min(120, config.getInt("itrak.terminal-highlight-seconds", 10)));
    }

    /** Включены ли крафты iTrak. */
    public boolean isItrakCrafting() {
        return config.getBoolean("itrak.crafting", true);
    }

    /** Скорость броска трекера. */
    public double getItrakThrowSpeed() {
        double value = config.getDouble("itrak.throw-speed", 1.8);
        return Double.isFinite(value) ? Math.max(0.5, Math.min(4.0, value)) : 1.8;
    }

    /** Плывёт ли трекер по течению: выключение оставляет его на месте. */
    public boolean isItrakWaterCurrent() {
        return config.getBoolean("itrak.water-current", true);
    }

    /** Скорость дрейфа по течению, блоков в секунду. */
    public double getItrakWaterDriftSpeed() {
        double value = config.getDouble("itrak.water-drift-speed", 0.8);
        return Double.isFinite(value) ? Math.max(0.0, Math.min(5.0, value)) : 0.8;
    }

    /** Скорость падения в водопаде и с обрыва, блоков в секунду. */
    public double getItrakWaterFallSpeed() {
        double value = config.getDouble("itrak.water-fall-speed", 6.0);
        return Double.isFinite(value) ? Math.max(0.5, Math.min(32.0, value)) : 6.0;
    }
}
