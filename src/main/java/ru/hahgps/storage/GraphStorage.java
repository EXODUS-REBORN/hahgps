package ru.hahgps.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import ru.hahgps.HahGPSPlugin;
import ru.hahgps.model.Edge;
import ru.hahgps.model.Graph;
import ru.hahgps.model.Node;
import ru.hahgps.model.TransportType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GraphStorage {

    private final HahGPSPlugin plugin;
    private final Graph graph;
    private File file;
    private volatile boolean dirty;

    public GraphStorage(HahGPSPlugin plugin, Graph graph) {
        this.plugin = plugin;
        this.graph = graph;
        this.file = new File(plugin.getDataFolder(), plugin.config().storageFile());
    }

    public File file() {
        return file;
    }

    public String fileName() {
        return file == null ? "graph.yml" : file.getName();
    }

    public void markDirty() {
        this.dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void load() {
        this.file = new File(plugin.getDataFolder(), plugin.config().storageFile());
        graph.clear();
        if (!file.exists()) {
            dirty = false;
            plugin.getLogger().info("Файл сетки не найден, создайте узлы командой /nav node create");
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        for (Map<?, ?> raw : cfg.getMapList("nodes")) {
            String id = asString(raw.get("id"), null);
            if (id == null || id.isBlank()) {
                continue;
            }
            Node node = new Node(id,
                    asString(raw.get("world"), "world"),
                    asDouble(raw.get("x"), 0.0D),
                    asDouble(raw.get("y"), 64.0D),
                    asDouble(raw.get("z"), 0.0D));
            node.setName(asString(raw.get("name"), ""));
            node.setAllowedTypes(parseTypes(raw.get("allowed")));
            graph.addNode(node);
        }

        for (Map<?, ?> raw : cfg.getMapList("edges")) {
            String from = asString(raw.get("from"), null);
            String to = asString(raw.get("to"), null);
            if (from == null || to == null || graph.node(from) == null || graph.node(to) == null) {
                continue;
            }
            EnumSet<TransportType> types = parseTypes(raw.get("allowed"));
            if (types.isEmpty()) {
                types.add(TransportType.FOOT);
            }
            Edge edge = graph.addEdge(from, to, types);
            edge.setOneWay(asBoolean(raw.get("oneway"), false));
            edge.setWeight(asDouble(raw.get("weight"), 1.0D));
            edge.setSpeed(asDouble(raw.get("speed"), 0.0D));
            edge.setLine(asString(raw.get("line"), ""));
            edge.setName(asString(raw.get("name"), ""));
        }

        graph.refreshAllLengths();
        dirty = false;
        plugin.getLogger().info("Сетка загружена: узлов " + graph.nodeCount() + ", рёбер " + graph.edgeCount());
    }

    public boolean save() {
        if (file == null) {
            return false;
        }
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("version", 1);

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Node node : graph.nodes()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", node.getId());
            entry.put("world", node.getWorld());
            entry.put("x", round(node.getX()));
            entry.put("y", round(node.getY()));
            entry.put("z", round(node.getZ()));
            entry.put("name", node.getName());
            entry.put("allowed", typeIds(node.allowedTypes()));
            nodes.add(entry);
        }
        cfg.set("nodes", nodes);

        List<Map<String, Object>> edges = new ArrayList<>();
        for (Edge edge : graph.edges()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("from", edge.getFrom());
            entry.put("to", edge.getTo());
            entry.put("allowed", typeIds(edge.allowedTypes()));
            entry.put("oneway", edge.isOneWay());
            entry.put("weight", round(edge.getWeight()));
            entry.put("speed", round(edge.getSpeed()));
            entry.put("line", edge.getLine());
            entry.put("name", edge.getName());
            edges.add(entry);
        }
        cfg.set("edges", edges);

        try {
            if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                plugin.getLogger().warning("Не удалось создать папку для файла сетки");
            }
            cfg.save(file);
            dirty = false;
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Ошибка сохранения сетки: " + exception.getMessage());
            return false;
        }
    }

    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    private static List<String> typeIds(Collection<TransportType> types) {
        List<String> ids = new ArrayList<>();
        for (TransportType type : TransportType.values()) {
            if (types.contains(type)) {
                ids.add(type.id());
            }
        }
        return ids;
    }

    private static EnumSet<TransportType> parseTypes(Object raw) {
        EnumSet<TransportType> types = EnumSet.noneOf(TransportType.class);
        if (raw instanceof Collection<?> collection) {
            for (Object element : collection) {
                TransportType type = TransportType.byId(String.valueOf(element));
                if (type != null) {
                    types.add(type);
                }
            }
        } else if (raw instanceof String string) {
            try {
                types.addAll(TransportType.parseSet(string));
            } catch (IllegalArgumentException ignored) {

            }
        }
        return types;
    }

    private static String asString(Object raw, String fallback) {
        return raw == null ? fallback : String.valueOf(raw);
    }

    private static double asDouble(Object raw, double fallback) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return raw == null ? fallback : Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static boolean asBoolean(Object raw, boolean fallback) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        return raw == null ? fallback : Boolean.parseBoolean(String.valueOf(raw));
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
