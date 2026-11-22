package ru.hahgps.model;
import org.bukkit.Location;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class NavigationSession {

    private final UUID playerId;
    private final String targetName;

    /**
     * Цель маршрута. Для трекера iTrak она живая: тик навигации подменяет
     * точку, когда трекер сдвинулся, поэтому поле не final.
     */
    private volatile Location target;
    private volatile List<Location> path;
    private volatile double totalDistance;

    private final long startTime;
    private final TransportType transport;

    /** id трекера iTrak или null для обычной точки. */
    private final Integer trackerId;

    private volatile long lastPathRebuild;
    private volatile boolean rebuildInFlight;
    private volatile Location lastRebuildOrigin;

    private int segmentIndex;
    private double remainingDistance;
    private boolean loggingEnabled;
    private String lastHint;
    /** Ключ последней строки боссбара: пакет шлём только при изменении. */
    private String lastBarKey;
    private float lastBarProgress = -1.0f;

    public NavigationSession(UUID playerId, String targetName, Location target,
                             List<Location> path, double totalDistance, TransportType transport) {
        this(playerId, targetName, target, path, totalDistance, transport, null);
    }

    public NavigationSession(UUID playerId, String targetName, Location target,
                             List<Location> path, double totalDistance, TransportType transport,
                             Integer trackerId) {
        this.playerId = playerId;
        this.targetName = targetName;
        this.target = target;
        this.path = new ArrayList<>(path);
        this.totalDistance = totalDistance;
        this.startTime = System.currentTimeMillis();
        this.transport = transport;
        this.trackerId = trackerId;
        this.segmentIndex = 0;
        this.remainingDistance = totalDistance;
        this.loggingEnabled = true;
        this.lastHint = "";
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getTargetName() {
        return targetName;
    }

    public Location getTarget() {
        return target;
    }

    /** Только для живой цели трекера: тик навигации двигает точку сам. */
    public void setTarget(Location target) {
        this.target = target;
    }

    public List<Location> getPath() {
        // Неизменяемый вид: путь читает карта, и писать в него извне нельзя.
        return java.util.Collections.unmodifiableList(path);
    }

    /** Новый путь после перестройки маршрута к движущемуся трекеру. */
    public void setPath(List<Location> path) {
        this.path = path == null ? new ArrayList<>() : new ArrayList<>(path);
    }

    public double getTotalDistance() {
        return totalDistance;
    }

    public void setTotalDistance(double totalDistance) {
        this.totalDistance = totalDistance;
    }

    public long getStartTime() {
        return startTime;
    }

    public TransportType getTransport() {
        return transport;
    }

    public Integer getTrackerId() {
        return trackerId;
    }

    public boolean isTrackingLiveTarget() {
        return trackerId != null;
    }

    public long getLastPathRebuild() {
        return lastPathRebuild;
    }

    public void setLastPathRebuild(long lastPathRebuild) {
        this.lastPathRebuild = lastPathRebuild;
    }

    public boolean isRebuildInFlight() {
        return rebuildInFlight;
    }

    public void setRebuildInFlight(boolean rebuildInFlight) {
        this.rebuildInFlight = rebuildInFlight;
    }

    public Location getLastRebuildOrigin() {
        return lastRebuildOrigin;
    }

    public void setLastRebuildOrigin(Location lastRebuildOrigin) {
        this.lastRebuildOrigin = lastRebuildOrigin;
    }

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public void setSegmentIndex(int segmentIndex) {
        this.segmentIndex = segmentIndex;
    }

    public double getRemainingDistance() {
        return remainingDistance;
    }

    public void setRemainingDistance(double remainingDistance) {
        this.remainingDistance = remainingDistance;
    }

    public boolean isLoggingEnabled() {
        return loggingEnabled;
    }

    public void setLoggingEnabled(boolean loggingEnabled) {
        this.loggingEnabled = loggingEnabled;
    }

    public String getLastHint() {
        return lastHint;
    }

    public void setLastHint(String lastHint) {
        this.lastHint = lastHint;
    }

    public String getLastBarKey() {
        return lastBarKey;
    }

    public void setLastBarKey(String lastBarKey) {
        this.lastBarKey = lastBarKey;
    }

    public float getLastBarProgress() {
        return lastBarProgress;
    }

    public void setLastBarProgress(float lastBarProgress) {
        this.lastBarProgress = lastBarProgress;
    }

    public Location getNextWaypoint() {
        if (segmentIndex < path.size()) {
            return path.get(segmentIndex);
        }
        return target;
    }
}
