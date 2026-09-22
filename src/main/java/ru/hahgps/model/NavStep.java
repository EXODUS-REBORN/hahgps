package ru.hahgps.model;

import java.util.List;

public final class NavStep {

    public enum Kind {
        WALK,
        RIDE,
        ARRIVE
    }

    private final Kind kind;
    private final TransportType transport;
    private final String title;
    private final String detail;
    private final String line;
    private final List<Vec> points;
    private final List<String> labels;
    private final double[] cumulative;
    private final double length;

    public NavStep(Kind kind, TransportType transport, String title, String detail, String line,
                   List<Vec> points, List<String> labels, double startDistance) {
        this.kind = kind;
        this.transport = transport;
        this.title = title;
        this.detail = detail;
        this.line = line == null ? "" : line;
        this.points = List.copyOf(points);
        this.labels = List.copyOf(labels);
        this.cumulative = new double[this.points.size()];
        double acc = startDistance;
        for (int i = 0; i < this.points.size(); i++) {
            if (i > 0) {
                acc += this.points.get(i - 1).distanceTo(this.points.get(i));
            }
            cumulative[i] = acc;
        }
        this.length = this.points.isEmpty() ? 0.0D : acc;
    }

    public Kind kind() {
        return kind;
    }

    public TransportType transport() {
        return transport;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return detail;
    }

    public String line() {
        return line;
    }

    public boolean hasLine() {
        return !line.isBlank();
    }

    public int stops() {
        return Math.max(0, points.size() - 1);
    }

    public List<Vec> points() {
        return points;
    }

    public List<String> labels() {
        return labels;
    }

    public double length() {
        return length;
    }

    public String world() {
        return points.isEmpty() ? null : points.get(0).world();
    }

    public Vec target() {
        return points.isEmpty() ? null : points.get(points.size() - 1);
    }

    public String targetLabel() {
        return labels.isEmpty() ? "-" : labels.get(labels.size() - 1);
    }

    public double distanceToTarget(Vec from) {
        Vec target = target();
        return target == null ? 0.0D : from.distanceTo(target);
    }

    public double remainingDistance(Vec from) {
        if (points.isEmpty()) {
            return 0.0D;
        }
        if (points.size() == 1) {
            return from.distanceTo(points.get(0));
        }
        double[] info = new double[2];
        int segment = nearestSegment(from, info);
        if (segment < 0) {
            return from.distanceTo(target());
        }
        Vec start = points.get(segment);
        Vec end = points.get(segment + 1);
        double traveled = cumulative[segment] + info[0] * start.distanceTo(end);
        return Math.max(0.0D, length - traveled) + info[1];
    }

    public double distanceFromPolyline(Vec from) {
        if (points.isEmpty()) {
            return 0.0D;
        }
        if (points.size() == 1) {
            return from.distanceTo(points.get(0));
        }
        double[] info = new double[2];
        if (nearestSegment(from, info) < 0) {
            return from.distanceTo(target());
        }
        return info[1];
    }

    public String nextLabel(Vec from, double skipDistance) {
        if (labels.isEmpty()) {
            return "-";
        }
        if (points.size() == 1) {
            return labels.get(0);
        }
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            if (!from.sameWorld(points.get(i))) {
                continue;
            }
            double distance = from.distanceTo(points.get(i));
            if (distance < best) {
                best = distance;
                nearest = i;
            }
        }
        for (int i = nearest + 1; i < points.size(); i++) {
            if (from.distanceTo(points.get(i)) > skipDistance) {
                return labels.get(i);
            }
        }
        return labels.get(labels.size() - 1);
    }

    public TurnInfo nextTurn(Vec from, double straightDegrees, double slightDegrees, double sharpDegrees) {
        if (points.isEmpty()) {
            return new TurnInfo(Turn.ARRIVE, 0.0D, "-");
        }
        if (points.size() < 2) {
            return new TurnInfo(Turn.ARRIVE, from.distanceTo(points.get(0)), targetLabel());
        }
        double[] info = new double[2];
        int segment = nearestSegment(from, info);
        if (segment < 0) {
            return new TurnInfo(Turn.STRAIGHT, length, targetLabel());
        }
        double traveled = cumulative[segment] + info[0] * points.get(segment).distanceTo(points.get(segment + 1));

        for (int i = segment + 1; i < points.size() - 1; i++) {
            double delta = angleDelta(points.get(i - 1), points.get(i), points.get(i + 1));
            if (Math.abs(delta) >= straightDegrees) {
                double distance = Math.max(0.0D, cumulative[i] - traveled);
                return new TurnInfo(Turn.fromAngle(delta, straightDegrees, slightDegrees, sharpDegrees),
                        distance, labels.get(i));
            }
        }

        double remaining = Math.max(0.0D, length - traveled);
        if (remaining <= 3.0D) {
            return new TurnInfo(Turn.ARRIVE, remaining, targetLabel());
        }
        return new TurnInfo(Turn.STRAIGHT, remaining, targetLabel());
    }

    private int nearestSegment(Vec from, double[] infoOut) {
        int best = -1;
        double bestDeviation = Double.MAX_VALUE;
        double bestProgress = 0.0D;
        for (int i = 0; i < points.size() - 1; i++) {
            Vec a = points.get(i);
            Vec b = points.get(i + 1);
            if (!from.sameWorld(a)) {
                continue;
            }
            double segmentLength = a.distanceTo(b);
            double t = projection(from, a, b, segmentLength);
            double deviation = from.distanceTo(a.add(b.sub(a).mul(t)));
            if (deviation < bestDeviation) {
                bestDeviation = deviation;
                best = i;
                bestProgress = t;
            }
        }
        if (infoOut != null && infoOut.length >= 2) {
            infoOut[0] = bestProgress;
            infoOut[1] = bestDeviation == Double.MAX_VALUE ? from.distanceTo(target()) : bestDeviation;
        }
        return best;
    }

    private static double angleDelta(Vec a, Vec b, Vec c) {
        double delta = bearing(b, c) - bearing(a, b);
        while (delta > 180.0D) {
            delta -= 360.0D;
        }
        while (delta < -180.0D) {
            delta += 360.0D;
        }
        return delta;
    }

    private static double bearing(Vec from, Vec to) {
        return Math.toDegrees(Math.atan2(to.x() - from.x(), -(to.z() - from.z())));
    }

    private static double projection(Vec from, Vec a, Vec b, double segmentLength) {
        if (segmentLength <= 0.0001D) {
            return 0.0D;
        }
        Vec ab = b.sub(a);
        Vec ap = from.sub(a);
        double dot = ab.x() * ap.x() + ab.y() * ap.y() + ab.z() * ap.z();
        double t = dot / (segmentLength * segmentLength);
        return Math.max(0.0D, Math.min(1.0D, t));
    }
}
