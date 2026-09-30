package ru.hahgps.command.subcommands;

import org.bukkit.entity.Player;
import ru.hahgps.HahGpsPlugin;
import ru.hahgps.command.SubCommand;
import ru.hahgps.model.TransportType;
import ru.hahgps.service.GraphService;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class EdgeCommand implements SubCommand {

    @Override
    public String name() {
        return "edge";
    }

    @Override
    public String usage() {
        return "/gps edge <connect|allowed> <id1> <id2> [types]";
    }

    @Override
    public String permission() {
        return "gps.admin";
    }

    @Override
    public List<SubCommand> children() {
        return List.of(new EdgeConnectCommand(), new EdgeAllowedCommand());
    }

    @Override
    public void execute(Player player, String[] args) {
        player.sendMessage(HahGpsPlugin.getInstance().getMessages().getPrefixed("usage", "usage", usage()));
    }

    @Override
    public List<String> tabComplete(Player player, String[] args) {
        return List.of();
    }

    private static class EdgeConnectCommand implements SubCommand {
        @Override
        public String name() {
            return "connect";
        }

        @Override
        public String usage() {
            return "/gps edge connect <id1> <id2> [foot,car,transit,air,water]";
        }

        @Override
        public String permission() {
            return "gps.admin";
        }

        @Override
        public void execute(Player player, String[] args) {
            if (args.length < 2) {
                player.sendMessage(HahGpsPlugin.getInstance().getMessages().getPrefixed("usage", "usage", usage()));
                return;
            }
            try {
                int id1 = Integer.parseInt(args[0]);
                int id2 = Integer.parseInt(args[1]);
                Set<TransportType> types = EnumSet.of(TransportType.FOOT, TransportType.CAR);
                if (args.length >= 3) {
                    types = EnumSet.noneOf(TransportType.class);
                    for (String t : args[2].split(",")) {
                        types.add(TransportType.fromKey(t.trim()));
                    }
                }
                GraphService graphService = HahGpsPlugin.getInstance().getGraphService();
                if (graphService.connectNodes(id1, id2, types)) {
                    player.sendMessage(HahGpsPlugin.getInstance().getMessages().getPrefixed("edge-connected",
                            "id1", String.valueOf(id1), "id2", String.valueOf(id2)));
                } else {
                    player.sendMessage(HahGpsPlugin.getInstance().getMessages().getPrefixed("edge-not-found"));
                }
            } catch (NumberFormatException e) {
                player.sendMessage(HahGpsPlugin.getInstance().getMessages().getPrefixed("usage", "usage", usage()));
            }
        }

        @Override
        public List<String> tabComplete(Player player, String[] args) {
            if (args.length == 1 || args.length == 2) {
                List<String> ids = new ArrayList<>();
                for (var node : HahGpsPlugin.getInstance().getGraphService().getNodes()) {
                    ids.add(String.valueOf(node.getId()));
                }
                return ids;
            }
            if (args.length == 3) {
                return List.of("foot,car", "foot,car,transit", "foot,car,transit,air,water");
            }
            return List.of();
        }
    }

    private static class EdgeAllowedCommand implements SubCommand {
        @Override
        public String name() {
            return "allowed";
        }

        @Override
        public String usage() {
            return "/gps edge allowed <id1> <id2> <add|remove> <type>";
        }

        @Override
        public String permission() {
            return "gps.admin";
        }

        @Override
        public void execute(Player player, String[] args) {
            if (args.length < 4) {
                player.sendMessage(HahGpsPlugin.getInstance().getMessages().getPrefixed("usage", "usage", usage()));
                return;
            }
            try {
                int id1 = Integer.parseInt(args[0]);
                int id2 = Integer.parseInt(args[1]);
                String action = args[2].toLowerCase();
                TransportType type = TransportType.fromKey(args[3]);
                GraphService graphService = HahGpsPlugin.getInstance().getGraphService();
                boolean success;
                if (action.equals("add")) {
                    success = graphService.addTransportToEdge(id1, id2, type);
                } else {
                    success = graphService.removeTransportFromEdge(id1, id2, type);
                }
                if (success) {
                    player.sendMessage(HahGpsPlugin.getInstance().getMessages().getPrefixed("edge-updated"));
                } else {
                    player.sendMessage(HahGpsPlugin.getInstance().getMessages().getPrefixed("edge-not-found"));
                }
            } catch (NumberFormatException e) {
                player.sendMessage(HahGpsPlugin.getInstance().getMessages().getPrefixed("usage", "usage", usage()));
            }
        }

        @Override
        public List<String> tabComplete(Player player, String[] args) {
            if (args.length == 3) {
                return List.of("add", "remove");
            }
            if (args.length == 4) {
                List<String> types = new ArrayList<>();
                for (TransportType t : TransportType.values()) {
                    types.add(t.getKey());
                }
                return types;
            }
            return List.of();
        }
    }
}
