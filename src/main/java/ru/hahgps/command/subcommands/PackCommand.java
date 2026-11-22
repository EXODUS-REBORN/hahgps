package ru.hahgps.command.subcommands;

import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.service.ResourcePackService;

import java.util.List;

/**
 * {@code /gps pack} — отправить игроку ресурспак со стрелками.
 *
 * Пак не навязывается при входе: команда — единственный способ его получить,
 * и клиент ещё спросит подтверждение.
 */
public class PackCommand implements SubCommand {

    @Override
    public String name() {
        return "pack";
    }

    @Override
    public String usage() {
        return "/gps pack";
    }

    @Override
    public String permission() {
        return "gps.pack";
    }

    @Override
    public void execute(Player player, String[] args) {
        HahGpsPlugin plugin = HahGpsPlugin.getInstance();
        ResourcePackService service = plugin.getResourcePackService();
        if (service == null) {
            player.sendMessage(plugin.getMessages().getPrefixed("pack-disabled"));
            return;
        }
        service.send(player);
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        return List.of();
    }
}
