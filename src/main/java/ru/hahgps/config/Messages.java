package ru.hahgps.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.hahgps.HahGPSPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Messages {

    private final HahGPSPlugin plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final Map<String, String> rawValues = new HashMap<>();
    private final Map<String, Component> cache = new HashMap<>();
    private String prefix = "";

    public Messages(HahGPSPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        rawValues.clear();
        cache.clear();
        for (String key : cfg.getKeys(true)) {
            if (cfg.isConfigurationSection(key)) {
                continue;
            }
            if (cfg.isList(key)) {
                List<String> list = cfg.getStringList(key);
                for (int i = 0; i < list.size(); i++) {
                    rawValues.put(key + "." + i, list.get(i));
                }
                rawValues.put(key + ".$size", String.valueOf(list.size()));
            } else {
                String value = cfg.getString(key);
                if (value != null) {
                    rawValues.put(key, value);
                }
            }
        }
        prefix = rawValues.getOrDefault("prefix", "");
    }

    public String raw(String key, String... placeholders) {
        String value = rawValues.getOrDefault(key, key);
        return apply(value, placeholders);
    }

    public Component get(String key, String... placeholders) {
        String prepared = raw(key, placeholders);
        return cache.computeIfAbsent(prepared, this::parse);
    }

    public Component prefixed(String key, String... placeholders) {
        String prepared = apply(prefix, placeholders) + raw(key, placeholders);
        return cache.computeIfAbsent(prepared, this::parse);
    }

    public List<Component> lines(String key, String... placeholders) {
        int size = Integer.parseInt(rawValues.getOrDefault(key + ".$size", "0"));
        List<Component> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(get(key + "." + i, placeholders));
        }
        if (result.isEmpty()) {
            result.add(get(key, placeholders));
        }
        return result;
    }

    public void send(CommandSender sender, String key, String... placeholders) {
        sender.sendMessage(prefixed(key, placeholders));
    }

    public void sendRaw(CommandSender sender, String key, String... placeholders) {
        sender.sendMessage(get(key, placeholders));
    }

    public void sendLines(CommandSender sender, String key, String... placeholders) {
        for (Component line : lines(key, placeholders)) {
            sender.sendMessage(line);
        }
    }

    private Component parse(String value) {
        try {
            return mini.deserialize(value);
        } catch (Throwable throwable) {
            return Component.text(value);
        }
    }

    private static String apply(String template, String... placeholders) {
        if (template == null || template.isEmpty() || placeholders == null || placeholders.length == 0) {
            return template == null ? "" : template;
        }
        String result = template;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String key = placeholders[i];
            String value = escape(placeholders[i + 1]);
            result = result.replace("{" + key + "}", value);
        }
        return result;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("<", "\\<");
    }
}
