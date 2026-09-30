package ru.hahgps.config;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import ru.hahgps.HahGpsPlugin;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class MessagesManager {

    private final HahGpsPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private YamlConfiguration messages;
    private String language = "ru";
    private final Map<String, String> cache = new HashMap<>();

    public MessagesManager(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(String language) {
        this.language = language;
        File file = new File(plugin.getDataFolder(), "messages_" + language + ".yml");
        if (!file.exists()) {

            plugin.saveResource("messages_" + language + ".yml", false);
        }
        this.messages = YamlConfiguration.loadConfiguration(file);
        this.cache.clear();
    }

    public void reload(String language) {
        load(language);
    }

    public String getLanguage() {
        return language;
    }

    public String get(String key, String... args) {
        String raw = cache.get(key);
        if (raw == null) {
            raw = messages.getString(key, "<red>Missing: " + key);
            cache.put(key, raw);
        }
        return format(raw, args);
    }

    public Component getComponent(String key, String... args) {
        return miniMessage.deserialize(get(key, args));
    }

    public Component getComponentRaw(String raw) {
        return miniMessage.deserialize(raw);
    }

    public Component getPrefixed(String key, String... args) {
        return miniMessage.deserialize(get("prefix", args) + get(key, args));
    }

    private String format(String raw, String... args) {
        String result = raw;
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 < args.length) {
                result = result.replace("{" + args[i] + "}", args[i + 1]);
            }
        }
        return result;
    }
}
