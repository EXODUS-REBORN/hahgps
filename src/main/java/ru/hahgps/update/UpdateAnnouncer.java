package ru.hahgps.update;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;

import java.util.ArrayList;
import java.util.List;


public final class UpdateAnnouncer {


    private static final List<String> DEFAULT_GROUPS = List.of("41", "42", "103", "555");

    private final HahGpsPlugin plugin;
    private volatile List<String> groups = new ArrayList<>(DEFAULT_GROUPS);

    public UpdateAnnouncer(HahGpsPlugin plugin) {
        this.plugin = plugin;
    }


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


    public boolean isPrivileged(Player player) {
        if (player == null || groups.isEmpty()) return false;
        for (String group : groups) {
            if (player.hasPermission("group." + group)) return true;
        }
        return false;
    }


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

        }
    }
}
