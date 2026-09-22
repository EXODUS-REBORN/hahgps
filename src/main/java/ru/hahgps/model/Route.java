package ru.hahgps.model;

import java.util.List;

public final class Route {

    private final TransportType transport;
    private final Node destination;
    private final List<NavStep> steps;
    private final double totalDistance;
    private final String noteKey;

    private Route(TransportType transport, Node destination, List<NavStep> steps, double totalDistance, String noteKey) {
        this.transport = transport;
        this.destination = destination;
        this.steps = List.copyOf(steps);
        this.totalDistance = totalDistance;
        this.noteKey = noteKey;
    }

    public static Route of(TransportType transport, Node destination, List<NavStep> steps, String noteKey) {
        double total = 0.0D;
        for (NavStep step : steps) {
            total += step.length();
        }
        return new Route(transport, destination, steps, total, noteKey);
    }

    public TransportType transport() {
        return transport;
    }

    public Node destination() {
        return destination;
    }

    public List<NavStep> steps() {
        return steps;
    }

    public double totalDistance() {
        return totalDistance;
    }

    public String noteKey() {
        return noteKey;
    }
}
