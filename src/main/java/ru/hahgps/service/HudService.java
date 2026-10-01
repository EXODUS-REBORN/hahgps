package ru.hahgps.service;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.model.TransportType;

import java.lang.reflect.Method;

public class HudService {

    private final boolean itemsAdderAvailable;

    private volatile Object wrapperInstance;
    private volatile Method getInstanceMethod;
    private volatile Method sendMethod;
    private volatile Method removeMethod;
    private volatile boolean apiBroken;

    public HudService() {
        this.itemsAdderAvailable = Bukkit.getPluginManager().getPlugin("ItemsAdder") != null;
    }

    public boolean isItemsAdderAvailable() {
        return itemsAdderAvailable;
    }

    public void updateHud(Player player, TransportType transport, String targetName, double distance) {
        if (!itemsAdderAvailable || player == null || !player.isOnline()) {
            return;
        }
        if (!Double.isFinite(distance) || distance < 0) {
            return;
        }
        if (!ensureMainThread(() -> updateHud(player, transport, targetName, distance))) {
            return;
        }
        if (!resolveApi()) {
            return;
        }
        try {
            String icon = getIconName(transport);
            int meters = distance >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) distance;
            String text = icon + " " + (targetName == null ? "" : targetName) + " | " + meters + "m";
            sendMethod.invoke(wrapperInstance, player.getUniqueId().toString(), text);
        } catch (Throwable ignored) {
            // один сбойный вызов не должен сыпать рефлексией каждый тик
            apiBroken = true;
        }
    }

    public void clearHud(Player player) {
        if (!itemsAdderAvailable || player == null) {
            return;
        }
        if (!ensureMainThread(() -> clearHud(player))) {
            return;
        }
        if (!resolveApi()) {
            return;
        }
        try {
            removeMethod.invoke(wrapperInstance, player.getUniqueId().toString());
        } catch (Throwable ignored) {
            apiBroken = true;
        }
    }

    /**
     * ItemsAdder работает с инвентарями/пакетами — вызов обязан идти с главного потока.
     */
    private boolean ensureMainThread(Runnable retry) {
        if (Bukkit.isPrimaryThread()) {
            return true;
        }
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (plugin != null && plugin.isEnabled()) {
            try {
                Bukkit.getScheduler().runTask(plugin, retry);
            } catch (Throwable ignored) {
                // плагин выключается — тихо бросаем вызов
            }
        }
        return false;
    }

    private boolean resolveApi() {
        if (apiBroken) {
            return false;
        }
        if (sendMethod == null) {
            synchronized (this) {
                if (sendMethod == null) {
                    try {
                        Class<?> hudApi = Class.forName("dev.lone.itemsadder.api.FontImages.FontImageWrapper");
                        getInstanceMethod = hudApi.getMethod("getInstance");
                        sendMethod = hudApi.getMethod("sendFontImage", String.class, String.class);
                        removeMethod = hudApi.getMethod("removeFontImage", String.class);
                    } catch (Throwable t) {
                        apiBroken = true;
                        return false;
                    }
                }
            }
        }
        if (wrapperInstance == null) {
            try {
                Object instance = getInstanceMethod.invoke(null);
                if (instance == null) {
                    // ItemsAdder ещё не инициализировался — попробуем в следующем тике
                    return false;
                }
                wrapperInstance = instance;
            } catch (Throwable t) {
                apiBroken = true;
                return false;
            }
        }
        return true;
    }

    private String getIconName(TransportType type) {
        if (type == null) {
            return "hahgps:icon_foot";
        }
        return switch (type) {
            case FOOT -> "hahgps:icon_foot";
            case CAR -> "hahgps:icon_car";
            case TRANSIT -> "hahgps:icon_transit";
            case AIR -> "hahgps:icon_air";
            case WATER -> "hahgps:icon_water";
        };
    }
}
