package ru.hahgps.model;
import org.bukkit.Location;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class NavigationSession {

    private final UUID playerId;
    private final String targetName;
    private final Location target;
    private final List<Location> path;
    private final double totalDistance;
    private final long startTime;
    private final TransportType transport;

    private int segmentIndex;
    private double remainingDistance;
    private boolean loggingEnabled;
    private String lastHint;

    public NavigationSession(UUID playerId, String targetName, Location target,
                             List<Location> path, double totalDistance, TransportType transport) {
        this.playerId = playerId;
        this.targetName = targetName;
        this.target = target;
        this.path = new ArrayList<>(path);
        this.totalDistance = totalDistance;
        this.startTime = System.currentTimeMillis();
        this.transport = transport;
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

    public List<Location> getPath() {
        // Неизменяемый вид: путь читает карта, и писать в него извне нельзя.
        return java.util.Collections.unmodifiableList(path);
    }

    public double getTotalDistance() {
        return totalDistance;
    }

    public long getStartTime() {
        return startTime;
    }

    public TransportType getTransport() {
        return transport;
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

    public Location getNextWaypoint() {
        if (segmentIndex < path.size()) {
            return path.get(segmentIndex);
        }
        return target;
    }
}
