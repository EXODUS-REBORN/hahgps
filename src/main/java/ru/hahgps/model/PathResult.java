package ru.hahgps.model;

import java.util.List;

public record PathResult(List<String> nodes, List<Edge> edges, double distance) {
}
