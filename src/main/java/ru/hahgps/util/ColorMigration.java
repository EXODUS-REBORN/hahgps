package ru.hahgps.util;

import org.bukkit.configuration.file.FileConfiguration;
import java.util.Map;

public final class ColorMigration {

    private ColorMigration() {
    }

    private static final Map<String, String> LEGACY_MAP = Map.of(
            "a", "b",
            "e", "b",
            "6", "9",
            "d", "b",
            "2", "3",
            "5", "9",
            "4", "c"
    );

    public static void migrate(FileConfiguration config) {
        for (String key : config.getKeys(true)) {
            String value = config.getString(key);
            if (value == null) continue;
            String migrated = migrateString(value);
            if (!migrated.equals(value)) {
                config.set(key, migrated);
            }
        }
    }

    private static String migrateString(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                String replacement = LEGACY_MAP.get(String.valueOf(code));
                if (replacement != null) {
                    sb.append('§').append(replacement.charAt(0));
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
