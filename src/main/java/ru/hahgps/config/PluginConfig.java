package ru.hahgps.config;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import ru.hahgps.HahGPSPlugin;
import ru.hahgps.model.TransportType;
import ru.hahgps.model.Turn;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PluginConfig {

    private final HahGPSPlugin plugin;

    private final Map<TransportType, Double> speeds = new EnumMap<>(TransportType.class);
    private final Map<TransportType, Double> arriveRadius = new EnumMap<>(TransportType.class);
    private final Map<TransportType, String> transportImages = new EnumMap<>(TransportType.class);
    private final Map<Turn, String> turnImages = new EnumMap<>(Turn.class);
    private final Map<String, Double> surfaces = new LinkedHashMap<>();

    private double heuristicFactor;
    private double nodePenalty;
    private int maxIterations;
    private double walkApproachThreshold;
    private double maxWalkDetour;
    private double minStepLength;
    private double recomputeDistance;
    private long recomputeCooldownMs;

    private double turnStraightDegrees;
    private double turnSlightDegrees;
    private double turnSharpDegrees;

    private boolean bossBarEnabled;
    private boolean actionBarEnabled;
    private int hudPeriodTicks;
    private boolean itemsAdderEnabled;
    private String itemsAdderHudId;
    private String itemsAdderTotalHudId;
    private boolean itemsAdderQuantity;

    private int visualPeriodTicks;
    private double visualViewDistance;
    private double visualParticleStep;
    private float visualParticleSize;
    private double visualAirOffset;
    private boolean visualLabels;
    private double visualLabelHeight;
    private int visualMaxLabels;

    private int autosaveSeconds;
    private String storageFile;

    public PluginConfig(HahGPSPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        speeds.clear();
        arriveRadius.clear();
        transportImages.clear();
        turnImages.clear();
        surfaces.clear();

        for (TransportType type : TransportType.values()) {
            speeds.put(type, cfg.getDouble("routing.speeds." + type.id(), type.defaultSpeed()));
            arriveRadius.put(type, cfg.getDouble("routing.arrive-radius." + type.id(), type.defaultArriveRadius()));
            transportImages.put(type, cfg.getString("hud.itemsadder.transport-images." + type.id(),
                    "hahgps:icon_" + type.id()));
        }
        for (Turn turn : Turn.values()) {
            turnImages.put(turn, cfg.getString("hud.itemsadder.turn-images." + turn.id(),
                    "hahgps:turn_" + turn.id().replace('-', '_')));
        }
        ConfigurationSection surfaceSection = cfg.getConfigurationSection("routing.surfaces");
        if (surfaceSection != null) {
            for (String key : surfaceSection.getKeys(false)) {
                surfaces.put(key.toLowerCase(), surfaceSection.getDouble(key, 0.0D));
            }
        }
        if (surfaces.isEmpty()) {
            surfaces.put("highway", 34.0D);
            surfaces.put("asphalt", 20.0D);
            surfaces.put("gravel", 9.0D);
            surfaces.put("ground", 6.0D);
            surfaces.put("path", 4.5D);
        }

        heuristicFactor = cfg.getDouble("routing.heuristic-factor", 1.0D);
        nodePenalty = cfg.getDouble("routing.node-penalty", 0.4D);
        maxIterations = Math.max(100, cfg.getInt("routing.max-iterations", 60000));
        walkApproachThreshold = cfg.getDouble("routing.walk-approach-threshold", 6.0D);
        maxWalkDetour = cfg.getDouble("routing.max-walk-detour", 2.0D);
        minStepLength = cfg.getDouble("routing.min-step-length", 1.5D);
        recomputeDistance = cfg.getDouble("routing.recompute-distance", 26.0D);
        recomputeCooldownMs = cfg.getLong("routing.recompute-cooldown-ms", 5000L);

        turnStraightDegrees = cfg.getDouble("navigation.turn-straight-degrees", 20.0D);
        turnSlightDegrees = cfg.getDouble("navigation.turn-slight-degrees", 45.0D);
        turnSharpDegrees = cfg.getDouble("navigation.turn-sharp-degrees", 135.0D);

        bossBarEnabled = cfg.getBoolean("hud.bossbar", true);
        actionBarEnabled = cfg.getBoolean("hud.actionbar", true);
        hudPeriodTicks = Math.max(1, cfg.getInt("hud.update-period-ticks", 5));
        itemsAdderEnabled = cfg.getBoolean("hud.itemsadder.enabled", true);
        itemsAdderHudId = cfg.getString("hud.itemsadder.hud-id", "hahgps:nav");
        itemsAdderTotalHudId = cfg.getString("hud.itemsadder.total-hud-id", "hahgps:nav_total");
        itemsAdderQuantity = cfg.getBoolean("hud.itemsadder.set-quantity", true);

        visualPeriodTicks = Math.max(2, cfg.getInt("visualization.update-period-ticks", 20));
        visualViewDistance = cfg.getDouble("visualization.view-distance", 96.0D);
        visualParticleStep = Math.max(0.5D, cfg.getDouble("visualization.particle-step", 3.0D));
        visualParticleSize = (float) cfg.getDouble("visualization.particle-size", 1.0D);
        visualAirOffset = cfg.getDouble("visualization.air-height-offset", 2.5D);
        visualLabels = cfg.getBoolean("visualization.labels", true);
        visualLabelHeight = cfg.getDouble("visualization.label-height", 2.2D);
        visualMaxLabels = Math.max(0, cfg.getInt("visualization.max-labels", 40));

        autosaveSeconds = Math.max(5, cfg.getInt("storage.autosave-seconds", 60));
        storageFile = cfg.getString("storage.file", "graph.yml");
    }

    public double speed(TransportType type) {
        return speeds.getOrDefault(type, type.defaultSpeed());
    }

    public double arriveRadius(TransportType type) {
        return arriveRadius.getOrDefault(type, type.defaultArriveRadius());
    }

    public double heuristicFactor() {
        return heuristicFactor;
    }

    public double nodePenalty() {
        return nodePenalty;
    }

    public int maxIterations() {
        return maxIterations;
    }

    public double walkApproachThreshold() {
        return walkApproachThreshold;
    }

    public double maxWalkDetour() {
        return maxWalkDetour;
    }

    public double minStepLength() {
        return minStepLength;
    }

    public double recomputeDistance() {
        return recomputeDistance;
    }

    public long recomputeCooldownMs() {
        return recomputeCooldownMs;
    }

    public double turnStraightDegrees() {
        return turnStraightDegrees;
    }

    public double turnSlightDegrees() {
        return turnSlightDegrees;
    }

    public double turnSharpDegrees() {
        return turnSharpDegrees;
    }

    public boolean bossBarEnabled() {
        return bossBarEnabled;
    }

    public boolean actionBarEnabled() {
        return actionBarEnabled;
    }

    public int hudPeriodTicks() {
        return hudPeriodTicks;
    }

    public boolean itemsAdderEnabled() {
        return itemsAdderEnabled;
    }

    public String itemsAdderHudId() {
        return itemsAdderHudId;
    }

    public String itemsAdderTotalHudId() {
        return itemsAdderTotalHudId;
    }

    public boolean itemsAdderQuantity() {
        return itemsAdderQuantity;
    }

    public String transportImage(TransportType type) {
        return transportImages.get(type);
    }

    public String turnImage(Turn turn) {
        return turnImages.get(turn);
    }

    public Map<String, Double> surfaces() {
        return surfaces;
    }

    public boolean hasSurface(String name) {
        return name != null && surfaces.containsKey(name.toLowerCase());
    }

    public double surfaceSpeed(String name) {
        return surfaces.getOrDefault(name == null ? "" : name.toLowerCase(), 0.0D);
    }

    public int visualPeriodTicks() {
        return visualPeriodTicks;
    }

    public double visualViewDistance() {
        return visualViewDistance;
    }

    public double visualParticleStep() {
        return visualParticleStep;
    }

    public float visualParticleSize() {
        return visualParticleSize;
    }

    public double visualAirOffset() {
        return visualAirOffset;
    }

    public boolean visualLabels() {
        return visualLabels;
    }

    public double visualLabelHeight() {
        return visualLabelHeight;
    }

    public int visualMaxLabels() {
        return visualMaxLabels;
    }

    public int autosaveSeconds() {
        return autosaveSeconds;
    }

    public String storageFile() {
        return storageFile;
    }

    public BossBar.Color barColor(TransportType type) {
        return switch (type) {
            case FOOT -> BossBar.Color.WHITE;
            case CAR -> BossBar.Color.YELLOW;
            case TRANSIT -> BossBar.Color.BLUE;
            case AIR -> BossBar.Color.PURPLE;
            case WATER -> BossBar.Color.GREEN;
        };
    }
}
