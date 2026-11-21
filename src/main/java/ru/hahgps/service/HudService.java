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
    private final java.util.Set<String> logged = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean apiBroken;
    private volatile long apiBrokenAt;

    private static final long API_RETRY_MS = 60_000L;

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
            sendMethod.invoke(wrapperInstance, hudArgs(sendMethod, player, text));
        } catch (Throwable t) {
            apiBroken = true;
            apiBrokenAt = System.currentTimeMillis();
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
            removeMethod.invoke(wrapperInstance, hudArgs(removeMethod, player, ""));
        } catch (Throwable t) {
            apiBroken = true;
            apiBrokenAt = System.currentTimeMillis();
        }
    }

    /**
     * Аргументы вызова ItemsAdder: в зависимости от подписи метода первым
     * идёт либо UUID-строка, либо Player.
     */
    private static Object[] hudArgs(Method method, Player player, String text) {
        if (method.getParameterCount() >= 2) {
            return firstParamIsString(method)
                    ? new Object[]{player.getUniqueId().toString(), text}
                    : new Object[]{player, text};
        }
        return firstParamIsString(method)
                ? new Object[]{player.getUniqueId().toString()}
                : new Object[]{player};
    }


    private boolean ensureMainThread(Runnable retry) {
        if (Bukkit.isPrimaryThread()) {
            return true;
        }
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        if (plugin != null && plugin.isEnabled()) {
            try {
                Bukkit.getScheduler().runTask(plugin, retry);
            } catch (Throwable ignored) {

            }
        }
        return false;
    }

    private boolean resolveApi() {
        if (apiBroken && System.currentTimeMillis() - apiBrokenAt < API_RETRY_MS) {
            return false;
        }
        if (apiBroken) {
            // Пробуем снять защёлку раз в минуту: транзиентная ошибка не должна
            // убивать HUD до перезапуска сервера.
            apiBroken = false;
        }
        if (sendMethod == null) {
            synchronized (this) {
                if (sendMethod == null) {
                    try {
                        Class<?> hudApi = Class.forName("dev.lone.itemsadder.api.FontImages.FontImageWrapper");
                        getInstanceMethod = hudApi.getMethod("getInstance");
                        sendMethod = findHudMethod(hudApi, "sendFontImage", 2);
                        removeMethod = findHudMethod(hudApi, "removeFontImage", 1);
                        if (sendMethod == null || removeMethod == null) {
                            throw new NoSuchMethodException("sendFontImage/removeFontImage не найдены у FontImageWrapper");
                        }
                    } catch (Throwable t) {
                        logOnce("HahGps: HUD ItemsAdder недоступен (проверьте сигнатуру API): " + t);
                        apiBroken = true;
                        apiBrokenAt = System.currentTimeMillis();
                        return false;
                    }
                }
            }
        }
        if (wrapperInstance == null) {
            try {
                Object instance = getInstanceMethod.invoke(null);
                if (instance == null) {
                    return false;
                }
                wrapperInstance = instance;
            } catch (Throwable t) {
                logOnce("HahGps: не удалось получить экземпляр FontImageWrapper: " + t);
                apiBroken = true;
                apiBrokenAt = System.currentTimeMillis();
                return false;
            }
        }
        return true;
    }

    /**
     * Поиск метода HUD по имени и числу параметров. ItemsAdder в разных сборках
     * отдаёт как (String, String), так и (Player, String): точный getMethod по
     * одной сигнатуре ронял HUD навсегда при первом же несовпадении.
     */
    private static Method findHudMethod(Class<?> api, String name, int params) {
        Method fallback = null;
        for (Method method : api.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != params) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (!String.class.equals(types[params - 1])) {
                continue;
            }
            if (String.class.equals(types[0])) {
                return method;
            }
            // Один параметр — это уже Player: у String-варианта последний
            // параметр тоже String, и он поймался выше. Раньше условие
            // params == 2 делало removeFontImage(Player) недостижимым, и HUD
            // отключался навсегда после первого remove.
            if (fallback == null && (params == 1 || params == 2)
                    && Player.class.isAssignableFrom(types[0])) {
                fallback = method;
            }
        }
        return fallback;
    }

    /** true, если первый параметр метода — строка (UUID), а не Player. */
    private static boolean firstParamIsString(Method method) {
        return method != null && method.getParameterCount() > 0
                && String.class.equals(method.getParameterTypes()[0]);
    }

    /** Пишем один раз на состояние, а не на каждый тик навигации. */
    private void logOnce(String text) {
        if (logged.add(text)) {
            Bukkit.getLogger().warning(text);
        }
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
