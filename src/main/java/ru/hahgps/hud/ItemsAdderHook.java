package ru.hahgps.hud;

import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class ItemsAdderHook {

    private static boolean initialised;
    private static boolean available;

    private static Constructor<?> holderConstructor;
    private static Constructor<?> hudConstructor;
    private static Constructor<?> quantityConstructor;
    private static Constructor<?> customConstructor;
    private static Method setVisibleMethod;
    private static Method setFloatValueMethod;
    private static Method setFontImagesMethod;
    private static Method fontImageInstanceMethod;

    private ItemsAdderHook() {
    }

    public static void reset() {
        initialised = false;
        available = false;
        holderConstructor = null;
        hudConstructor = null;
        quantityConstructor = null;
        customConstructor = null;
        setVisibleMethod = null;
        setFloatValueMethod = null;
        setFontImagesMethod = null;
        fontImageInstanceMethod = null;
    }

    public static boolean available() {
        ensureInitialised();
        return available;
    }

    private static void ensureInitialised() {
        if (initialised) {
            return;
        }
        initialised = true;
        try {
            Class<?> holderClass = Class.forName("dev.lone.itemsadder.api.FontImages.PlayerHudsHolderWrapper");
            holderConstructor = holderClass.getConstructor(Player.class);

            Class<?> hudClass = Class.forName("dev.lone.itemsadder.api.FontImages.PlayerHudWrapper");
            hudConstructor = hudClass.getConstructor(holderClass, String.class);
            setVisibleMethod = hudClass.getMethod("setVisible", boolean.class);

            Class<?> quantityClass = Class.forName("dev.lone.itemsadder.api.FontImages.PlayerQuantityHudWrapper");
            quantityConstructor = quantityClass.getConstructor(holderClass, String.class);
            setFloatValueMethod = quantityClass.getMethod("setFloatValue", float.class);

            try {
                Class<?> customClass = Class.forName("dev.lone.itemsadder.api.FontImages.PlayerCustomHudWrapper");
                customConstructor = customClass.getConstructor(holderClass, String.class);
                setFontImagesMethod = customClass.getMethod("setFontImages", List.class);
            } catch (Throwable ignored) {
                customConstructor = null;
                setFontImagesMethod = null;
            }

            try {
                Class<?> fontImageClass = Class.forName("dev.lone.itemsadder.api.FontImages.FontImageWrapper");
                fontImageInstanceMethod = fontImageClass.getMethod("instance", String.class);
            } catch (Throwable ignored) {
                fontImageInstanceMethod = null;
            }

            available = true;
        } catch (Throwable throwable) {
            available = false;
            holderConstructor = null;
            hudConstructor = null;
            quantityConstructor = null;
        }
    }

    public static Object createHolder(Player player) {
        ensureInitialised();
        if (!available || holderConstructor == null) {
            return null;
        }
        try {
            return holderConstructor.newInstance(player);
        } catch (Throwable throwable) {
            return null;
        }
    }

    public static Object createCustomHud(Object holder, String hudId) {
        ensureInitialised();
        if (!available || customConstructor == null || holder == null || hudId == null || hudId.isBlank()) {
            return null;
        }
        try {
            return customConstructor.newInstance(holder, hudId);
        } catch (Throwable throwable) {
            return null;
        }
    }

    public static Object createQuantityHud(Object holder, String hudId) {
        ensureInitialised();
        if (!available || quantityConstructor == null || holder == null || hudId == null || hudId.isBlank()) {
            return null;
        }
        try {
            return quantityConstructor.newInstance(holder, hudId);
        } catch (Throwable throwable) {
            return null;
        }
    }

    public static Object createHud(Object holder, String hudId) {
        ensureInitialised();
        if (!available || hudConstructor == null || holder == null || hudId == null || hudId.isBlank()) {
            return null;
        }
        try {
            return hudConstructor.newInstance(holder, hudId);
        } catch (Throwable throwable) {
            return null;
        }
    }

    public static void setVisible(Object hud, boolean visible) {
        if (hud == null || setVisibleMethod == null) {
            return;
        }
        try {
            setVisibleMethod.invoke(hud, visible);
        } catch (Throwable ignored) {
        }
    }

    public static void setValue(Object hud, float value) {
        if (hud == null || setFloatValueMethod == null) {
            return;
        }
        try {
            setFloatValueMethod.invoke(hud, value);
        } catch (Throwable ignored) {
        }
    }

    public static boolean setImages(Object hud, List<String> imageIds) {
        if (hud == null || setFontImagesMethod == null || imageIds == null || imageIds.isEmpty()) {
            return false;
        }
        List<Object> images = new ArrayList<>(imageIds.size());
        for (String id : imageIds) {
            Object image = fontImage(id);
            if (image != null) {
                images.add(image);
            }
        }
        if (images.isEmpty()) {
            return false;
        }
        try {
            setFontImagesMethod.invoke(hud, images);
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    public static Object fontImage(String namespacedId) {
        ensureInitialised();
        if (!available || fontImageInstanceMethod == null || namespacedId == null || namespacedId.isBlank()) {
            return null;
        }
        try {
            return fontImageInstanceMethod.invoke(null, namespacedId);
        } catch (Throwable throwable) {
            return null;
        }
    }
}
