package ru.hahgps.model;

import java.util.EnumSet;
import java.util.Set;

public class GraphEdge {

    private final GraphNode from;
    private final GraphNode to;
    private final double weight;
    private final Set<TransportType> allowedTransport;

    public GraphEdge(GraphNode from, GraphNode to, double weight, Set<TransportType> allowedTransport) {
        this.from = from;
        this.to = to;
        this.weight = weight;
        this.allowedTransport = allowedTransport;
    }

    public GraphNode getFrom() {
        return from;
    }

    public GraphNode getTo() {
        return to;
    }

    public double getWeight() {
        return weight;
    }

    public Set<TransportType> getAllowedTransport() {
        return allowedTransport;
    }

    public boolean allows(TransportType type) {
        return allowedTransport.contains(type);
    }
}
