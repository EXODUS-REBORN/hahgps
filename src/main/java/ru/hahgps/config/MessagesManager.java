package ru.hahgps.config;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.hahgps.HahGpsPlugin;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class MessagesManager {


    /**
     * Ревизия файла сообщений. Растёт при любом изменении текстов, а не только
     * палитры: иначе исправленный текст не доезжает до серверов, где
     * messages_ru.yml уже лежит (он не перезаписывается, пока версия файла
     * не ниже текущей). Старая копия сохраняется рядом как {@code .bak}.
     */
    public static final int PALETTE_VERSION = 6;

    private final HahGpsPlugin plugin;
    private volatile YamlConfiguration messages;
    private volatile YamlConfiguration defaults;
    private volatile String language = "ru";

    private final Map<String, String> cache = new java.util.concurrent.ConcurrentHashMap<>();

    public MessagesManager(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Загрузка сообщений выбранного языка.
     *
     * Любая ошибка (нет бандла, битый YAML) не должна ронять плагин:
     * остаёмся на встроенном эталоне из jar, а если и его нет — на дефолтах
     * предыдущего состояния. Поля подменяются только после полного успеха,
     * иначе getLanguage() врал бы про язык, которого фактически нет.
     */
    public void load(String language) {
        String lang = resolveLanguage(language);

        YamlConfiguration bundled = loadBundled(lang);

        File file = new File(plugin.getDataFolder(), "messages_" + lang + ".yml");
        if (!file.exists()) {
            try {
                plugin.saveResource("messages_" + lang + ".yml", false);
            } catch (IllegalArgumentException missing) {
                plugin.getLogger().warning("HahGps: в плагине нет messages_" + lang
                        + ".yml — использую встроенные тексты.");
            }
        }

        YamlConfiguration loaded = null;
        if (file.exists()) {
            try {
                loaded = YamlConfiguration.loadConfiguration(file);
            } catch (Exception e) {
                plugin.getLogger().warning("HahGps: не удалось прочитать messages_" + lang
                        + ".yml (" + e.getMessage() + ") — использую встроенные тексты.");
            }
        }
        if (loaded == null && bundled == null) {
            // Нет ни файла, ни эталона: язык не поддерживаем, откат на русский.
            if (!"ru".equals(lang)) {
                load("ru");
            }
            return;
        }

        this.language = lang;
        this.defaults = bundled;
        this.messages = loaded != null ? loaded : bundled;

        // Кэш чистим ДО подмены полей: иначе конкурентный get(), прочитавший
        // старый messages между присвоениями, может оставить протухшую запись.
        this.cache.clear();

        if (this.messages != null && this.messages != bundled) {
            int fileVersion = this.messages.getInt("palette-version", 1);
            if (bundled != null) {
                this.messages.setDefaults(bundled);
                this.messages.options().copyDefaults(true);
            }
            migrate(file, fileVersion);
        }
    }

    /** Нормализация языка: пустое/битое/неподдержанное значение даёт ru. */
    private String resolveLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "ru";
        }
        String lang = language.toLowerCase(java.util.Locale.ROOT);
        return lang.equals("ru") || lang.equals("en") ? lang : "ru";
    }

    private YamlConfiguration loadBundled(String lang) {
        String resource = "messages_" + lang + ".yml";
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось прочитать эталон " + resource + ": " + e.getMessage());
            return null;
        }
    }


    private void migrate(File file, int fileVersion) {
        if (fileVersion < PALETTE_VERSION && defaults != null) {
            replaceWithDefaults(file);
            return;
        }
        boolean changed = false;
        for (String key : messages.getKeys(true)) {
            if (messages.isConfigurationSection(key)) continue;
            String value = messages.getString(key);
            if (value == null) continue;
            String migrated = remapPalette(value);
            if (!migrated.equals(value)) {
                messages.set(key, migrated);
                changed = true;
            }
        }
        if (changed) {
            // Версию выставляем и здесь: иначе ремап палитры повторялся бы
            // на каждом старте и перекрашивал бы осознанно изменённые админом цвета.
            messages.set("palette-version", PALETTE_VERSION);
            save(file, "Сообщения обновлены до палитры hahtojer");
        }
    }

    private void replaceWithDefaults(File file) {
        File backup = new File(plugin.getDataFolder(), file.getName() + ".bak");
        try {
            java.nio.file.Files.copy(file.toPath(), backup.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось сделать бэкап " + file.getName() + ": " + e.getMessage());
        }
        YamlConfiguration cfg = messages;


        java.util.Set<String> bundled = defaults.getKeys(true);
        for (String key : new java.util.ArrayList<>(cfg.getKeys(true))) {
            if (cfg.isConfigurationSection(key)) continue;
            if (!bundled.contains(key)) {
                cfg.set(key, null);
            }
        }
        for (String key : bundled) {
            if (defaults.isConfigurationSection(key)) continue;
            cfg.set(key, defaults.get(key));
        }
        cfg.set("palette-version", PALETTE_VERSION);
        save(file, "Файл сообщений переведён на палитру hahtojer (старая копия — " + backup.getName() + ")");
    }

    private void save(File file, String successLog) {
        try {
            messages.save(file);
            plugin.getLogger().info(successLog + ": " + file.getName());
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось сохранить " + file.getName() + ": " + e.getMessage());
        }
    }


    private static final String NEW_PREFIX =
            "<gray>[</gray><bold><gradient:#2464c8:#8fe9f5>GPS</gradient></bold><gray>]</gray>";

    private static final String OLD_PREFIX =
            "<gray>[</gray><font:minecraft:alt><gradient:#2464c8:#8fe9f5>GPS</gradient></font><gray>]</gray>";


    static String remapPalette(String text) {
        String out = text
                .replace("#b8dcff", "#8fe9f5")
                .replace("#c8e4ff", "#66aaff")
                .replace("#e8f4ff", "#8fe9f5");

        out = out.replace("<#8fc6ff>[GPS]<reset>", NEW_PREFIX)
                .replace("<#3f86ff>[GPS]<reset>", NEW_PREFIX)
                .replace(OLD_PREFIX, NEW_PREFIX);
        return out;
    }

    public void reload(String language) {
        load(language);
    }

    public String getLanguage() {
        return language;
    }

    /**
     * Есть ли ключ в файле или во встроенном эталоне. Заменяет проверку по
     * строке «Missing: …»: раньше вызывающий код угадывал отсутствие перевода
     * через startsWith по тексту заглушки.
     */
    public boolean has(String key) {
        if (key == null) {
            return false;
        }
        YamlConfiguration cfg = messages;
        if (cfg != null && cfg.contains(key)) {
            return true;
        }
        YamlConfiguration def = defaults;
        return def != null && def.contains(key);
    }

    public String get(String key, String... args) {
        String raw = cache.get(key);
        if (raw == null) {
            YamlConfiguration cfg = messages;
            raw = cfg == null ? null : cfg.getString(key, null);
            YamlConfiguration def = defaults;
            if (raw == null && def != null) {
                raw = def.getString(key, null);
            }
            if (raw == null) raw = "<#3f86ff>Missing: " + key;
            cache.put(key, raw);
        }
        return format(raw, args);
    }

    public Component getComponent(String key, String... args) {
        return safe(get(key, args));
    }

    public Component getComponentRaw(String raw) {
        return safe(raw);
    }

    public Component getPrefixed(String key, String... args) {
        return safe(get("prefix", args) + get(key, args));
    }


    private Component safe(String mini) {
        return ru.hahgps.gui.GuiTheme.safe(mini);
    }

    private static final java.util.Set<String> DYNAMIC_VALUES = java.util.Set.of(
            "name", "query", "world", "category", "author", "type",
            "error", "reason", "language", "id1", "id2");


    private String format(String raw, String... args) {
        String result = raw;
        for (int i = 0; i + 1 < args.length; i += 2) {
            String key = args[i];
            String plain = args[i + 1] == null ? "" : args[i + 1];
            if (DYNAMIC_VALUES.contains(key)) {
                plain = ru.hahgps.gui.GuiTheme.clipPlain(plain, ru.hahgps.gui.GuiTheme.NAME_MAX);
            }
            String value = escape(plain);
            result = result.replace("{" + key + "}", value);
        }
        return result;
    }

    private static String escape(String value) {


        String v = value.replaceAll("(?i)§[0-9a-fk-or]", "").replace("§", "");

        return v.replace("\\", "\\\\").replace("<", "\\<");
    }
}
