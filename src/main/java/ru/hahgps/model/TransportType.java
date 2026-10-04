package ru.hahgps.model;

public enum TransportType {



    FOOT("foot", 1.0, 0xAAAAAA),
    CAR("car", 2.0, 0x3F86FF),
    TRANSIT("transit", 1.5, 0x8FC6FF),
    AIR("air", 4.0, 0x4FD1E0),
    WATER("water", 1.2, 0x66AAFF);

    private final String key;
    private final double speed;
    private final int particleColor;

    TransportType(String key, double speed, int particleColor) {
        this.key = key;
        this.speed = speed;
        this.particleColor = particleColor;
    }

    public String getKey() {
        return key;
    }

    public double getSpeed() {
        return speed;
    }

    public int getParticleColor() {
        return particleColor;
    }

    public boolean isAvailable() {
        return true;
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
