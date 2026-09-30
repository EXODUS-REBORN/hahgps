package ru.hahgps.service;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.hahgps.model.TransportType;

public class HudService {

    private final boolean itemsAdderAvailable;

    public HudService() {
        this.itemsAdderAvailable = Bukkit.getPluginManager().getPlugin("ItemsAdder") != null;
    }

    public boolean isItemsAdderAvailable() {
        return itemsAdderAvailable;
    }

    public void updateHud(Player player, TransportType transport, String targetName, double distance) {
        if (!itemsAdderAvailable) {
            return;
        }
        try {
            String icon = getIconName(transport);
            String text = icon + " " + targetName + " | " + (int) distance + "m";
            Class<?> hudApi = Class.forName("dev.lone.itemsadder.api.FontImages.FontImageWrapper");
            Object instance = hudApi.getMethod("getInstance").invoke(null);
            hudApi.getMethod("sendFontImage", String.class, String.class)
                    .invoke(instance, player.getUniqueId().toString(), text);
        } catch (Throwable ignored) {
        }
    }

    public void clearHud(Player player) {
        if (!itemsAdderAvailable) {
            return;
        }
        try {
            Class<?> hudApi = Class.forName("dev.lone.itemsadder.api.FontImages.FontImageWrapper");
            Object instance = hudApi.getMethod("getInstance").invoke(null);
            hudApi.getMethod("removeFontImage", String.class)
                    .invoke(instance, player.getUniqueId().toString());
        } catch (Throwable ignored) {
        }
    }

    private String getIconName(TransportType type) {
        return switch (type) {
            case FOOT -> "hahgps:icon_foot";
            case CAR -> "hahgps:icon_car";
            case TRANSIT -> "hahgps:icon_transit";
            case AIR -> "hahgps:icon_air";
            case WATER -> "hahgps:icon_water";
        };
    }
}
