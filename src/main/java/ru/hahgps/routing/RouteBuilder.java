package ru.hahgps.routing;

import ru.hahgps.config.Messages;
import ru.hahgps.config.PluginConfig;
import ru.hahgps.model.Edge;
import ru.hahgps.model.Graph;
import ru.hahgps.model.NavStep;
import ru.hahgps.model.Node;
import ru.hahgps.model.PathResult;
import ru.hahgps.model.Route;
import ru.hahgps.model.TransportType;
import ru.hahgps.model.Vec;
import ru.hahgps.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RouteBuilder {

    private final Graph graph;
    private final AStarPathfinder pathfinder;
    private final PluginConfig config;
    private final Messages messages;

    public RouteBuilder(Graph graph, AStarPathfinder pathfinder, PluginConfig config, Messages messages) {
        this.graph = graph;
        this.pathfinder = pathfinder;
        this.config = config;
        this.messages = messages;
    }

    public Route build(Vec from, Node destination, TransportType transport) {
        if (destination == null) {
            return null;
        }
        if (transport != TransportType.FOOT) {
            Route multimodal = buildMultimodal(from, destination, transport);
            if (multimodal != null) {
                return multimodal;
            }
            List<NavStep> fallback = new ArrayList<>();
            NavStep walk = walkLeg(from, destination);
            if (walk != null) {
                fallback.add(walk);
            }
            if (fallback.isEmpty()) {
                fallback.add(arrivalStep(destination));
            }
            return Route.of(TransportType.FOOT, destination, fallback, "gps.degraded");
        }

        List<NavStep> steps = new ArrayList<>();
        NavStep walk = walkLeg(from, destination);
        if (walk != null) {
            steps.add(walk);
        }
        if (steps.isEmpty()) {
            steps.add(arrivalStep(destination));
        }
        return Route.of(TransportType.FOOT, destination, steps, null);
    }

    private Route buildMultimodal(Vec from, Node destination, TransportType transport) {
        Node board = graph.nearest(from, transport);
        if (board == null) {
            return null;
        }
        Node alight = graph.nearest(destination.vec(), transport);
        if (alight == null) {
            return null;
        }
        PathResult ride = board.getId().equals(alight.getId())
                ? new PathResult(List.of(board.getId()), List.of(), 0.0D)
                : pathfinder.find(graph, board.getId(), alight.getId(), transport);
        if (ride == null) {
            return null;
        }

        List<NavStep> steps = new ArrayList<>();
        NavStep toBoard = walkLeg(from, board);
        if (toBoard != null) {
            steps.add(toBoard);
        }
        appendRide(steps, ride, transport);
        if (!alight.getId().equals(destination.getId())) {
            NavStep fromAlight = walkLeg(alight.vec(), destination);
            if (fromAlight != null) {
                steps.add(fromAlight);
            }
        }
        if (steps.isEmpty()) {
            steps.add(arrivalStep(destination));
        }
        return Route.of(transport, destination, steps, null);
    }

    private NavStep walkLeg(Vec from, Node to) {
        double direct = from.distanceTo(to.vec());
        if (direct <= config.walkApproachThreshold()) {
            return null;
        }

        Node startFoot = graph.nearest(from, TransportType.FOOT);
        Node endFoot = to.allows(TransportType.FOOT) ? to : graph.nearest(to.vec(), TransportType.FOOT);

        List<Vec> points = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        boolean straightWalk = startFoot == null || endFoot == null;

        if (!straightWalk) {
            PathResult path = pathfinder.find(graph, startFoot.getId(), endFoot.getId(), TransportType.FOOT);
            if (path == null) {
                straightWalk = true;
            } else {
                List<Vec> networkPoints = new ArrayList<>();
                List<String> networkLabels = new ArrayList<>();
                if (from.distanceTo(startFoot.vec()) > config.walkApproachThreshold()) {
                    add(networkPoints, networkLabels, startFoot);
                }
                for (String id : path.nodes()) {
                    Node node = graph.node(id);
                    if (node != null) {
                        add(networkPoints, networkLabels, node);
                    }
                }
                if (endFoot != to) {
                    add(networkPoints, networkLabels, to);
                }
                if (networkPoints.isEmpty() || polylineLength(from, networkPoints) > direct * config.maxWalkDetour()) {
                    straightWalk = true;
                } else {
                    points.addAll(networkPoints);
                    labels.addAll(networkLabels);
                }
            }
        }

        if (straightWalk) {
            add(points, labels, to);
        }
        if (points.isEmpty()) {
            return null;
        }
        double length = polylineLength(from, points);
        if (length < config.minStepLength()) {
            return null;
        }

        String title = messages.raw("steps.walk-title", "target", to.display());
        String detail = messages.raw("steps.walk-detail",
                "distance", Text.distance(length),
                "points", String.valueOf(points.size()));
        return new NavStep(NavStep.Kind.WALK, TransportType.FOOT, title, detail, "", points, labels,
                from.distanceTo(points.get(0)));
    }

    private static double polylineLength(Vec from, List<Vec> points) {
        if (points.isEmpty()) {
            return 0.0D;
        }
        double length = from.distanceTo(points.get(0));
        for (int i = 1; i < points.size(); i++) {
            length += points.get(i - 1).distanceTo(points.get(i));
        }
        return length;
    }

    private void appendRide(List<NavStep> steps, PathResult ride, TransportType transport) {
        List<Edge> edges = ride.edges();
        List<String> nodeIds = ride.nodes();
        int index = 0;
        while (index < edges.size()) {
            String line = safe(edges.get(index).getLine());
            int end = index + 1;
            while (end < edges.size() && safe(edges.get(end).getLine()).equals(line)) {
                end++;
            }

            List<Vec> points = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (int i = index; i <= end && i < nodeIds.size(); i++) {
                Node node = graph.node(nodeIds.get(i));
                if (node != null) {
                    add(points, labels, node);
                }
            }
            if (points.size() >= 2) {
                double length = 0.0D;
                for (int i = 1; i < points.size(); i++) {
                    length += points.get(i - 1).distanceTo(points.get(i));
                }
                String target = labels.get(labels.size() - 1);
                String title;
                String detail;
                if (line.isEmpty()) {
                    title = messages.raw("steps.ride-title",
                            "verb", transport.verb(),
                            "target", target);
                    detail = messages.raw("steps.ride-detail",
                            "distance", Text.distance(length),
                            "points", String.valueOf(points.size() - 1));
                } else {
                    title = messages.raw("steps.ride-title-line",
                            "line", line,
                            "target", target);
                    detail = messages.raw("steps.ride-detail-line",
                            "line", line,
                            "stops", String.valueOf(end - index),
                            "distance", Text.distance(length));
                }
                steps.add(new NavStep(NavStep.Kind.RIDE, transport, title, detail, line, points, labels, 0.0D));
            }
            index = end;
        }
    }

    private NavStep arrivalStep(Node destination) {
        String title = messages.raw("steps.arrive-title");
        return new NavStep(NavStep.Kind.ARRIVE, TransportType.FOOT, title, title, "",
                List.of(destination.vec()), List.of(destination.display()), 0.0D);
    }

    private static void add(List<Vec> points, List<String> labels, Node node) {
        Vec vec = node.vec();
        if (!points.isEmpty() && points.get(points.size() - 1).distanceTo(vec) < 0.5D) {
            return;
        }
        points.add(vec);
        labels.add(node.display());
    }

    private static String safe(String value) {
        return Objects.requireNonNullElse(value, "");
    }
}
