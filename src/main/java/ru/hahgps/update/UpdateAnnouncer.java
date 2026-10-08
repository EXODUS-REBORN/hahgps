package ru.hahgps.update;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import ru.hahgps.HahGpsPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;


/**
 * Рассылает сообщения об обновлении ТОЛЬКО приоритетным: группам по списку
 * (по умолчанию 41, 42, 103, 555 — технический директор, старший техник,
 * тех-админ, программист) и отдельным игрокам (по умолчанию haker_one).
 *
 * <p>Правила отбора:
 * <ul>
 *   <li>если установлен LuckPerms — проверяется именно группа игрока
 *       (основная и унаследованные), никакие права и никакой оператор
 *       сообщение не получают;</li>
 *   <li>если LuckPerms нет — берётся только ЯВНО выданное право
 *       {@code group.<имя>}: «звёздочка» оператора и право
 *       {@code gps.admin} не считаются;</li>
 *   <li>игрок из списка имён получает сообщения независимо от группы.</li>
 * </ul>
 */
public final class UpdateAnnouncer {

    public static final List<String> DEFAULT_GROUPS = List.of("41", "42", "103", "555");
    public static final List<String> DEFAULT_PLAYERS = List.of("haker_one");

    /** Неизменяемая цель: публикуется одной volatile-записью. */
    private record Targets(List<String> groups, Set<String> groupKeys,
                           List<String> players, Set<String> playerNames) {
    }

    private final HahGpsPlugin plugin;
    private volatile Targets targets = build(DEFAULT_GROUPS, DEFAULT_PLAYERS);

    private final LuckPerms luckPerms;

    public UpdateAnnouncer(HahGpsPlugin plugin) {
        this.plugin = plugin;
        this.luckPerms = LuckPerms.lookup();
    }

    public void setGroups(List<String> groups) {
        List<String> cleaned = clean(groups);
        this.targets = build(cleaned.isEmpty() ? DEFAULT_GROUPS : cleaned, targets.players());
    }

    /** Ники, которым оповещения приходят всегда, независимо от группы. */
    public void setPlayers(List<String> players) {
        List<String> cleaned = clean(players);
        this.targets = build(targets.groups(), cleaned.isEmpty() ? DEFAULT_PLAYERS : cleaned);
    }

    private static Targets build(List<String> groups, List<String> players) {
        return new Targets(
                groups.isEmpty() ? List.of() : List.copyOf(groups),
                normalize(groups),
                players.isEmpty() ? List.of() : List.copyOf(players),
                normalize(players));
    }

    public List<String> groups() {
        return new ArrayList<>(targets.groups());
    }

    public List<String> players() {
        return new ArrayList<>(targets.players());
    }

    /** Есть ли у игрока приоритетная группа либо он в списке имён. */
    public boolean isPrivileged(Player player) {
        if (player == null) return false;
        Targets current = targets;
        if (current.playerNames().contains(lower(player.getName()))) return true;
        if (current.groupKeys().isEmpty()) return false;

        if (luckPerms != null) {
            // LuckPerms есть — решение только по группам, оператор не исключается сам собой
            return luckPerms.hasAnyGroup(player.getUniqueId(), current.groupKeys());
        }
        for (String group : current.groupKeys()) {
            if (hasExplicitPermission(player, "group." + group)) return true;
        }
        return false;
    }

    public void announce(String key, String... args) {
        Component message = plugin.getMessages().getPrefixed(key, args);
        plugin.getLogger().info(PlainTextComponentSerializer.plainText().serialize(message));
        runOnMain(() -> {
            int sent = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!isPrivileged(player)) continue;
                player.sendMessage(message);
                sent++;
            }
            if (sent > 0) {
                plugin.getLogger().log(Level.FINE, "Оповещение об обновлении получили игроки: " + sent);
            }
        });
    }

    private void runOnMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, action);
        } catch (Throwable ignored) {

        }
    }

    /** Разрешение по приоритету (группы и ники), игнорируя регистр и лишние пробелы. */
    static Set<String> normalize(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String value : raw) {
            if (value == null) continue;
            String name = lower(value);
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    private static List<String> clean(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String value : raw) {
            if (value == null) continue;
            String name = value.trim();
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Право, выданное именно этому игроку ( LuckPerms-вложение, плагин или команда).
     * Право по умолчанию оператора «*» сюда не попадает — поэтому оператор
     * не получает оповещения просто из-за статуса.
     */
    private static boolean hasExplicitPermission(Player player, String node) {
        try {
            for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
                if (info != null && node.equals(info.getPermission())) {
                    return info.getValue();
                }
            }
        } catch (Throwable ignored) {

        }
        return false;
    }

    /** Тонкая обёртка над LuckPerms: без самого плагина работает как заглушка. */
    private static final class LuckPerms {

        private final Object userManager;
        private final Method getUser;
        private final Method getPrimaryGroup;
        private final Method getInheritedGroups;
        private final Method isLoaded;

        private LuckPerms(Object userManager, Method getUser,
                           Method getPrimaryGroup, Method getInheritedGroups, Method isLoaded) {
            this.userManager = userManager;
            this.getUser = getUser;
            this.getPrimaryGroup = getPrimaryGroup;
            this.getInheritedGroups = getInheritedGroups;
            this.isLoaded = isLoaded;
        }

        static LuckPerms lookup() {
            try {
                Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
                Object provider = providerClass.getMethod("get").invoke(null);
                if (provider == null) return null;

                Class<?> apiClass = Class.forName("net.luckperms.api.LuckPerms");
                Object api = apiClass.getMethod("getUserManager").invoke(provider);

                Class<?> managerClass = Class.forName("net.luckperms.api.user.manager.UserManager");
                Class<?> userClass = Class.forName("net.luckperms.api.user.LuckPermsUser");
                return new LuckPerms(
                        api,
                        managerClass.getMethod("getUser", UUID.class),
                        userClass.getMethod("getPrimaryGroup"),
                        userClass.getMethod("getInheritedGroups"),
                        managerClass.getMethod("isLoaded", UUID.class));
            } catch (Throwable ignored) {
                return null;
            }
        }

        boolean isLoaded(UUID uuid) {
            if (isLoaded == null) return true;
            try {
                return Boolean.TRUE.equals(isLoaded.invoke(userManager, uuid));
            } catch (Throwable ignored) {
                return true;
            }
        }

        boolean hasAnyGroup(UUID uuid, Set<String> wanted) {
            if (uuid == null || wanted.isEmpty()) return false;
            // isLoaded: getUser() для незагруженного игрока потенциально блокирующий
            // (чтение хранилища LuckPerms), а вызываем мы из главного потока.
            if (!isLoaded(uuid)) return false;
            try {
                Object user = getUser.invoke(userManager, uuid);
                if (user == null) return false;
                if (matches(getPrimaryGroup.invoke(user), wanted)) return true;
                Object inherited = getInheritedGroups.invoke(user);
                if (inherited instanceof Iterable<?> it) {
                    for (Object group : it) {
                        if (matches(group, wanted)) return true;
                    }
                }
                return false;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static boolean matches(Object group, Set<String> wanted) {
            if (group == null) return false;
            return wanted.contains(lower(group.toString()));
        }
    }
}