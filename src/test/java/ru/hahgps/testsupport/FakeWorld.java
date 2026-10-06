package ru.hahgps.testsupport;

import org.bukkit.World;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Заглушка мира для юнит-тестов.
 *
 * {@code Location} требует непустой {@code World}, а поднимать Bukkit в тестах
 * не нужно: путь и хранилище мировые методы, кроме имени и equals, не вызывают.
 */
public final class FakeWorld {

    private FakeWorld() {
    }

    public static World named(String name) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> handle(proxy, method, args, name));
    }

    private static Object handle(Object proxy, Method method, Object[] args, String name) {
        switch (method.getName()) {
            case "getName":
                return name;
            case "equals":
                return args != null && args.length == 1 && proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "FakeWorld[" + name + "]";
            default:
                break;
        }
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return 0;
    }
}
