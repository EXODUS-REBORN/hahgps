package ru.hahgps.vmodel;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public final class Placement {

    public static final String FLOOR = "FLOOR";
    public static final String WALL = "WALL";
    public static final String CEILING = "CEILING";

    private Placement() {
    }

    public static String kindOf(BlockFace face) {
        if (face == null) return FLOOR;
        return switch (face) {
            case UP -> FLOOR;
            case DOWN -> CEILING;
            default -> WALL;
        };
    }

    public static Location origin(Block block, BlockFace face, ModelDefinition def) {
        Location origin = block.getLocation().add(0.5, 0.5, 0.5);
        if (face != null) {
            origin.add(face.getDirection().multiply(0.5));
        }
        origin.add(offsetFor(def, face));
        return origin;
    }

    public static Location precise(Vector hit, BlockFace face, ModelDefinition def, World world) {
        Location origin = new Location(world, round2(hit.getX()), round2(hit.getY()), round2(hit.getZ()));
        if (face != null) {
            origin.add(face.getDirection().multiply(0.01));
        }
        origin.add(offsetFor(def, face));
        origin.setX(round2(origin.getX()));
        origin.setY(round2(origin.getY()));
        origin.setZ(round2(origin.getZ()));
        return origin;
    }

    public static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public static Vector offsetFor(ModelDefinition def, BlockFace face) {
        if (def == null) {
            return new Vector();
        }
        double ox = def.offsetX();
        double oy = def.offsetY();
        double oz = def.offsetZ();
        if (!def.rotateToFace() || face == null || face == BlockFace.UP || face == BlockFace.DOWN) {
            return new Vector(ox, oy, oz);
        }
        return switch (face) {
            case NORTH -> new Vector(ox, oy, -oz);
            case SOUTH -> new Vector(-ox, oy, oz);
            case WEST -> new Vector(-oz, oy, -ox);
            case EAST -> new Vector(oz, oy, ox);
            default -> new Vector(ox, oy, oz);
        };
    }

    public static float yawFor(Player player, BlockFace face, boolean rotate) {
        float playerYaw = player == null ? 0f : player.getLocation().getYaw();
        if (!rotate || face == null || face == BlockFace.UP || face == BlockFace.DOWN) {
            return Math.round(playerYaw / 90f) * 90f;
        }
        return switch (face) {
            case NORTH -> 180f;
            case SOUTH -> 0f;
            case WEST -> 90f;
            case EAST -> -90f;
            default -> playerYaw;
        };
    }
}
