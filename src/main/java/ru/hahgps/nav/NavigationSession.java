package ru.hahgps.nav;

import net.kyori.adventure.bossbar.BossBar;
import ru.hahgps.model.NavStep;
import ru.hahgps.model.Route;
import ru.hahgps.model.TransportType;
import ru.hahgps.model.Vec;

import java.util.UUID;

public final class NavigationSession {

    private final UUID playerId;
    private final String destinationId;
    private final TransportType transport;
    private final long startedAt = System.currentTimeMillis();

    private Route route;
    private int index;
    private long lastRecomputeAt;
    private BossBar bossBar;
    private Object itemsAdderHud;
    private Object itemsAdderTotalHud;
    private Object itemsAdderHolder;
    private String itemsAdderImages = "";
    private int lastHudQuantity = Integer.MIN_VALUE;
    private int lastTotalQuantity = Integer.MIN_VALUE;

    public NavigationSession(UUID playerId, String destinationId, TransportType transport, Route route) {
        this.playerId = playerId;
        this.destinationId = destinationId;
        this.transport = transport;
        this.route = route;
    }

    public UUID playerId() {
        return playerId;
    }

    public String destinationId() {
        return destinationId;
    }

    public TransportType transport() {
        return transport;
    }

    public Route route() {
        return route;
    }

    public void setRoute(Route route) {
        this.route = route;
        this.index = 0;
    }

    public int index() {
        return index;
    }

    public void advance() {
        index++;
    }

    public NavStep currentStep() {
        if (route == null || index < 0 || index >= route.steps().size()) {
            return null;
        }
        return route.steps().get(index);
    }

    public double remainingDistance(Vec from) {
        if (route == null) {
            return 0.0D;
        }
        double total = 0.0D;
        NavStep current = currentStep();
        if (current != null) {
            total += current.remainingDistance(from);
        }
        for (int i = index + 1; i < route.steps().size(); i++) {
            total += route.steps().get(i).length();
        }
        return total;
    }

    public long startedAt() {
        return startedAt;
    }

    public long lastRecomputeAt() {
        return lastRecomputeAt;
    }

    public void setLastRecomputeAt(long value) {
        this.lastRecomputeAt = value;
    }

    public BossBar bossBar() {
        return bossBar;
    }

    public void setBossBar(BossBar bossBar) {
        this.bossBar = bossBar;
    }

    public Object itemsAdderHud() {
        return itemsAdderHud;
    }

    public void setItemsAdderHud(Object hud) {
        this.itemsAdderHud = hud;
    }

    public int lastHudQuantity() {
        return lastHudQuantity;
    }

    public void setLastHudQuantity(int value) {
        this.lastHudQuantity = value;
    }

    public Object itemsAdderTotalHud() {
        return itemsAdderTotalHud;
    }

    public void setItemsAdderTotalHud(Object hud) {
        this.itemsAdderTotalHud = hud;
    }

    public Object itemsAdderHolder() {
        return itemsAdderHolder;
    }

    public void setItemsAdderHolder(Object holder) {
        this.itemsAdderHolder = holder;
    }

    public String itemsAdderImages() {
        return itemsAdderImages;
    }

    public void setItemsAdderImages(String key) {
        this.itemsAdderImages = key == null ? "" : key;
    }

    public int lastTotalQuantity() {
        return lastTotalQuantity;
    }

    public void setLastTotalQuantity(int value) {
        this.lastTotalQuantity = value;
    }
}
