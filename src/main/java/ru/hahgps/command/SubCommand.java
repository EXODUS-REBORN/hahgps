package ru.hahgps.command;

import org.bukkit.entity.Player;

import java.util.List;

/**
 * Подкоманда /gps. Поддерживает вложенность (дерево команд).
 */
public interface SubCommand {

    String name();

    String usage();

    String permission();

    /**
     * Вложенные подкоманды.
     */
    default List<SubCommand> children() {
        return List.of();
    }

    /**
     * Лист команды (нет вложенных) — выполняет действие.
     */
    default boolean isLeaf() {
        return children().isEmpty();
    }

    void execute(Player player, String[] args);

    List<String> tabComplete(Player player, String[] args);
}
