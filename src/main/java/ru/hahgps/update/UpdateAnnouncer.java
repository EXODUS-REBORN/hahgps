package ru.hahgps.update;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Оповещения об обновлении: приходят только игрокам из групп LuckPerms,
 * перечисленных в {@code обновление.группы-оповещения} (по умолчанию 41, 42, 103, 555).
 * OP и право {@code gps.admin} не считаются — только эти группы.
 *
 * <p>Проверка идёт через неявные разрешения {@code group.<имя>}, которые LuckPerms
 * выдаёт каждому участнику группы (включая унаследованные) — отдельная зависимость
 * от API LuckPerms не нужна. Список групп можно сменить в config.yml и перечитать
 * через {@code /gps update reload}.
 */
public final class UpdateAnnouncer {

    /** Группы по умолчанию: их можно сменить в config.yml. */
    private static final List<String> DEFAULT_GROUPS = List.of("41", "42", "103", "555");

    private final HahGpsPlugin plugin;
    private volatile List<String> groups = new ArrayList<>(DEFAULT_GROUPS);

    public UpdateAnnouncer(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Новый список групп из конфига: пустые отбрасываются, пустой список — значения по умолчанию. */
    public void setGroups(List<String> groups) {
        List<String> cleaned = new ArrayList<>();
        if (groups != null) {
            for (String group : groups) {
                if (group == null) continue;
                String name = group.trim();
                if (!name.isEmpty()) cleaned.add(name);
            }
        }
        this.groups = cleaned.isEmpty() ? new ArrayList<>(DEFAULT_GROUPS) : cleaned;
    }

    public List<String> groups() {
        return new ArrayList<>(groups);
    }

    /** true, если игрок состоит в одной из групп оповещения (LuckPerms). */
    public boolean isPrivileged(Player player) {
        if (player == null || groups.isEmpty()) return false;
        for (String group : groups) {
            if (player.hasPermission("group." + group)) return true;
        }
        return false;
    }

    /**
     * Сообщение с префиксом плагина всем онлайн-игрокам из групп оповещения.
     * Текст берётся из messages_*.yml по ключу, аргументы подставляются как {имя}.
     * В лог уходит всегда (даже если никого нет онлайн или плагин выгружается).
     */
    public void announce(String key, String... args) {
        Component message = plugin.getMessages().getPrefixed(key, args);
        plugin.getLogger().info(PlainTextComponentSerializer.plainText().serialize(message));
        runOnMain(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (isPrivileged(player)) {
                    player.sendMessage(message);
                }
            }
        });
    }

    private void runOnMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, action);
        } catch (Throwable ignored) {
            // плагин выгружается — сообщение уже в логе
        }
    }
}
