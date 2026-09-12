package dev.monocle.client.systems.modules.world;

import java.util.ArrayDeque;

/** Bounded one-second distance samples; session throughput needs only distance and elapsed time. */
final class HighwayHud {
    enum Phase {
        Mining("Excavation"), Paving("Paving / advance"), Verification("Server / verification wait"), Crew("Crew wait"),
        Supply("Supply handling"), Travel("Supply travel"), Recovery("Sealing / recovery"),
        Entities("Entities / combat / eating"), Paused("Paused / unavailable"), Other("Alignment / other");
        final String label;
        Phase(String label) { this.label = label; }
    }
    private static final Phase[] PHASES = Phase.values();
    private final double[] phaseSeconds = new double[PHASES.length];
    private double timingAt;
    private Phase phase = Phase.Other;
    private boolean timingRunning;
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
        accountTime(time);
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

    void resetTiming(double time) {
        java.util.Arrays.fill(phaseSeconds, 0);
        timingAt = time; phase = Phase.Other; timingRunning = true;
    }
    void resumeTiming(double time) {
        if (timingRunning) return;
        timingRunning = true; phase = Phase.Paused;
        accountTime(time); // A same-job native handoff is downtime, not a new timing session.
        phase = Phase.Other;
    }
    void timingPhase(double time, Phase next) { accountTime(time); phase = next; }
    void stopTiming(double time) { accountTime(time); timingRunning = false; }
    private void accountTime(double time) {
        if (!timingRunning || !Double.isFinite(time) || time < timingAt) return;
        phaseSeconds[phase.ordinal()] += time - timingAt;
        timingAt = time;
    }
    double timingSeconds(Phase phase) { return phaseSeconds[phase.ordinal()]; }
    String timingSummary() {
        double total = java.util.Arrays.stream(phaseSeconds).sum();
        if (total <= 0) return "Start a highway job to collect timings.";
        StringBuilder text = new StringBuilder(String.format(java.util.Locale.ROOT, "Local job time: %.1fs", total));
        for (Phase phase : PHASES) {
            double seconds = timingSeconds(phase);
            text.append(String.format(java.util.Locale.ROOT, "\n%s: %.1fs (%.1f%%)", phase.label, seconds, seconds / total * 100));
        }
        return text.toString();
    }

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
