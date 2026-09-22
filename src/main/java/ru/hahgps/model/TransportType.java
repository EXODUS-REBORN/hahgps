package ru.hahgps.model;

import org.bukkit.Color;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

public enum TransportType {

    FOOT("foot", "Пеший", "👣", "Идите", Color.fromRGB(0xE6E6E6), 4.3, 4.0,
            List.of("пеший", "пешком", "foot", "walk")),
    CAR("car", "Авто", "🚗", "Поезжайте", Color.fromRGB(0xFFD400), 16.0, 9.0,
            List.of("авто", "машина", "car", "drive")),
    TRANSIT("transit", "Общественный транспорт", "🚌", "Следуйте", Color.fromRGB(0x2E6BFF), 24.0, 12.0,
            List.of("общественный", "транспорт", "автобус", "метро", "transit", "bus")),
    AIR("air", "Авиа / Вертолёты", "🚁", "Летите", Color.fromRGB(0x9B4DFF), 42.0, 16.0,
            List.of("авиа", "вертолет", "вертолёт", "air", "fly")),
    WATER("water", "Водный", "🚢", "Плывите", Color.fromRGB(0x30E0E0), 10.0, 9.0,
            List.of("водный", "вода", "лодка", "корабль", "water", "boat", "sail"));

    private final String id;
    private final String displayName;
    private final String icon;
    private final String verb;
    private final Color color;
    private final double defaultSpeed;
    private final double defaultArriveRadius;
    private final List<String> aliases;

    TransportType(String id, String displayName, String icon, String verb, Color color,
                  double defaultSpeed, double defaultArriveRadius, List<String> aliases) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.verb = verb;
        this.color = color;
        this.defaultSpeed = defaultSpeed;
        this.defaultArriveRadius = defaultArriveRadius;
        this.aliases = aliases;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String icon() {
        return icon;
    }

    public String verb() {
        return verb;
    }

    public Color color() {
        return color;
    }

    public double defaultSpeed() {
        return defaultSpeed;
    }

    public double defaultArriveRadius() {
        return defaultArriveRadius;
    }

    public static TransportType byId(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String key = input.trim().toLowerCase(Locale.ROOT);
        for (TransportType type : values()) {
            if (type.id.equals(key)) {
                return type;
            }
        }
        for (TransportType type : values()) {
            if (type.aliases.contains(key)) {
                return type;
            }
        }
        return null;
    }

    public static String ids() {
        StringBuilder sb = new StringBuilder();
        for (TransportType type : values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(type.id);
        }
        return sb.toString();
    }

    public static List<String> idList() {
        List<String> list = new ArrayList<>(values().length);
        for (TransportType type : values()) {
            list.add(type.id);
        }
        return list;
    }

    public static EnumSet<TransportType> parseSet(String input) {
        EnumSet<TransportType> result = EnumSet.noneOf(TransportType.class);
        if (input == null || input.isBlank()) {
            return result;
        }
        for (String part : input.split("[,;|\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            TransportType type = byId(part);
            if (type == null) {
                throw new IllegalArgumentException(part);
            }
            result.add(type);
        }
        return result;
    }

    public static String join(Collection<TransportType> types) {
        if (types == null || types.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (TransportType type : values()) {
            if (!types.contains(type)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(type.id);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    public static String joinIcons(Collection<TransportType> types) {
        if (types == null || types.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (TransportType type : values()) {
            if (types.contains(type)) {
                sb.append(type.icon);
            }
        }
        return sb.toString();
    }
}
