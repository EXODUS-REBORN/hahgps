package ru.hahgps.model;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

public final class Edge {

    private String from;
    private String to;
    private final EnumSet<TransportType> allowedTypes = EnumSet.noneOf(TransportType.class);
    private boolean oneWay;
    private double weight = 1.0D;
    private double speed;
    private String line = "";
    private String name = "";
    private double length;

    public Edge(String from, String to, Collection<TransportType> types) {
        this.from = from;
        this.to = to;
        if (types != null) {
            allowedTypes.addAll(types);
        }
    }

    public static String key(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    public String key() {
        return key(from, to);
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    public void setDirection(String from, String to) {
        if (from == null || to == null || !connects(from, to)) {
            return;
        }
        this.from = from;
        this.to = to;
    }

    public Set<TransportType> allowedTypes() {
        return allowedTypes;
    }

    public boolean allows(TransportType type) {
        return allowedTypes.contains(type);
    }

    public boolean allowsAny(Collection<TransportType> types) {
        for (TransportType type : types) {
            if (allowedTypes.contains(type)) {
                return true;
            }
        }
        return false;
    }

    public void setAllowedTypes(Collection<TransportType> types) {
        allowedTypes.clear();
        if (types != null) {
            allowedTypes.addAll(types);
        }
    }

    public void addAllowedType(TransportType type) {
        allowedTypes.add(type);
    }

    public void removeAllowedType(TransportType type) {
        allowedTypes.remove(type);
    }

    public boolean isOneWay() {
        return oneWay;
    }

    public void setOneWay(boolean oneWay) {
        this.oneWay = oneWay;
    }

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = Math.max(0.01D, weight);
    }

    public String getLine() {
        return line;
    }

    public void setLine(String line) {
        this.line = line == null ? "" : line;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public double getLength() {
        return length;
    }

    public void setLength(double length) {
        this.length = length;
    }

    public double getSpeed() {
        return speed;
    }

    public void setSpeed(double speed) {
        this.speed = Math.max(0.0D, speed);
    }

    public double speedFor(double layerSpeed) {
        return speed > 0.0D ? speed : layerSpeed;
    }

    public boolean canTraverse(String fromNodeId, TransportType type) {
        if (!allowedTypes.contains(type)) {
            return false;
        }
        return !oneWay || from.equals(fromNodeId);
    }

    public String other(String nodeId) {
        return from.equals(nodeId) ? to : from;
    }

    public boolean connects(String a, String b) {
        return (from.equals(a) && to.equals(b)) || (from.equals(b) && to.equals(a));
    }

    public String pairLabel() {
        return from + " ↔ " + to;
    }

    @Override
    public String toString() {
        return "Edge{" + pairLabel() + " " + allowedTypes + (oneWay ? " one-way" : "") + " w=" + weight + "}";
    }
}
