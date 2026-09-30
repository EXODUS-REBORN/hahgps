package ru.hahgps.model;

/**
 * Вид транспорта. Выбирается при выборе цели и влияет на прокладку маршрута.
 */
public enum TransportType {

    FOOT("foot", true),
    CAR("car", true);

    private final String key;
    private final boolean available;

    TransportType(String key, boolean available) {
        this.key = key;
        this.available = available;
    }

    public String getKey() {
        return key;
    }

    public boolean isAvailable() {
        return available;
    }

    public static TransportType fromKey(String key) {
        for (TransportType type : values()) {
            if (type.key.equalsIgnoreCase(key)) {
                return type;
            }
        }
        return FOOT;
    }
}
