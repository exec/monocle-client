package dev.monocle.client.systems.modules.world;

import java.util.ArrayDeque;

/** Bounded one-second distance samples; session throughput needs only distance and elapsed time. */
final class HighwayHud {
    private record Sample(double time, double distance) {}
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private double started, now, distance, lastActivity;
    private long actions;

    void reset(double time) {
        samples.clear();
        started = now = lastActivity = time;
        distance = 0;
        actions = 0;
        samples.add(new Sample(time, 0));
    }

    void update(double time, double progress, long completedActions) {
        now = Math.max(now, time);
        if (progress > distance + 0.001 || completedActions != actions) lastActivity = now;
        distance = Math.max(distance, progress);
        actions = completedActions;
        if (now - samples.getLast().time >= 1) samples.addLast(new Sample(now, distance));
        while (samples.size() > 62) samples.removeFirst();
    }

    boolean jammed() { return now - lastActivity >= 3; }
    double distance() { return distance; }
    int sampleCount() { return samples.size(); }

    double rate(int windowSeconds) {
        double cutoff = windowSeconds == 0 ? started : Math.max(started, now - windowSeconds);
        if (now <= cutoff) return 0;
        if (windowSeconds == 0) return distance / (now - started);
        Sample before = samples.getFirst();
        for (Sample after : samples) {
            if (after.time > cutoff) {
                double baseline = before.distance + (after.distance - before.distance) * (cutoff - before.time) / (after.time - before.time);
                return Math.max(0, (distance - baseline) / (now - cutoff));
            }
            before = after;
        }
        double baseline = before.time == now ? distance
            : before.distance + (distance - before.distance) * (cutoff - before.time) / (now - before.time);
        return Math.max(0, (distance - baseline) / (now - cutoff));
    }
}
