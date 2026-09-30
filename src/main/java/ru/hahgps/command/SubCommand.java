package ru.hahgps.command;
import org.bukkit.entity.Player;
import java.util.List;

public interface SubCommand {

    String name();

    String usage();

    String permission();

    default List<SubCommand> children() {
        return List.of();
    }

    default boolean isLeaf() {
        return children().isEmpty();
    }

    void execute(Player player, String[] args);

    List<String> tabComplete(Player player, String[] args);
}
