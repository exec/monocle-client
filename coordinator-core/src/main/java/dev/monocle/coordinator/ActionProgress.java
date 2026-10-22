package dev.monocle.coordinator;

import com.google.gson.JsonObject;
import java.util.Set;
import java.util.UUID;

/** Client-thread watchdog. Only verified work and approach/mining improvement reset its clock. */
public final class ActionProgress {
    public static final long STALL_MS = 120_000;
    public static final int MAX_RECOVERIES = 2;
    public record Observation(String phase, String expected, String milestone, String target,
                              double distance, double effort, boolean waiting, boolean navigation) { }
    public enum Decision { Continue, Replan, Pause }
    private String milestone = "", target = "", phase = "Starting", expected = "First action tick", incident = "";
    private double bestDistance = Double.MAX_VALUE, bestEffort;
    private long lastSample, age, retryAge;
    private int attempts;
    private String state = "Working";
    private boolean initialized;

    public Decision observe(Observation observation, long nowMs) {
        boolean freshSample = lastSample == 0;
        long elapsed = freshSample ? 0 : Math.clamp(nowMs - lastSample, 0, 1_000);
        lastSample = nowMs;
        phase = observation.phase(); expected = observation.expected();
        if (state.equals("Paused")) return Decision.Pause;
        if (observation.waiting()) { state = "Waiting"; return Decision.Continue; }
        if (!initialized || !milestone.equals(observation.milestone())) {
            initialized = true; milestone = observation.milestone(); age = retryAge = 0; attempts = 0; incident = "";
            bestDistance = Double.MAX_VALUE; bestEffort = 0;
        }
        boolean sameTarget = target.equals(observation.target());
        if (!sameTarget) { target = observation.target(); bestDistance = Double.MAX_VALUE; bestEffort = 0; }
        boolean improvement = !freshSample && sameTarget && (observation.distance() < bestDistance - .25 || observation.effort() > bestEffort + .001);
        if (freshSample || !sameTarget || improvement) { bestDistance = observation.distance(); bestEffort = observation.effort(); }
        // Target/phase churn, jitter and our own retries are not evidence of productive work.
        if (improvement) age = retryAge = 0;
        else if (!observation.waiting()) { age = Math.min(86_400_000, age + elapsed); retryAge += elapsed; }
        state = observation.waiting() ? "Waiting" : attempts > 0 ? "Recovering" : "Working";
        if (observation.waiting() || retryAge < STALL_MS) return Decision.Continue;
        if (incident.isEmpty()) incident = UUID.randomUUID().toString();
        if (observation.navigation() && attempts < MAX_RECOVERIES) {
            attempts++; retryAge = 0; state = "Recovering"; return Decision.Replan;
        }
        state = "Paused"; return Decision.Pause;
    }
    public void resetClock() { lastSample = 0; }
    public void resume() { lastSample = age = retryAge = 0; attempts = 0; incident = ""; state = "Working"; }
    public boolean paused() { return state.equals("Paused"); }
    public JsonObject report() {
        JsonObject value = new JsonObject(); value.addProperty("phase", phase); value.addProperty("expectedNextEvent", expected);
        value.addProperty("progressAgeMs", age); value.addProperty("recoveryAttempts", attempts); value.addProperty("state", state);
        if (!target.isEmpty()) value.addProperty("target", target);
        if (bestDistance < Double.MAX_VALUE) value.addProperty("closestDistance", bestDistance);
        if (!incident.isEmpty()) value.addProperty("incidentId", incident);
        return value;
    }
    public JsonObject snapshot() {
        JsonObject value = report(); value.addProperty("milestone", milestone); value.addProperty("target", target);
        value.addProperty("bestDistance", bestDistance); value.addProperty("bestEffort", bestEffort);
        value.addProperty("retryAgeMs", retryAge); value.addProperty("initialized", initialized); return value;
    }
    public void restore(JsonObject saved) {
        JsonObject report = saved.deepCopy();
        for (String key : Set.of("milestone", "target", "bestDistance", "bestEffort", "retryAgeMs", "initialized")) report.remove(key);
        checked(report);
        phase = report.get("phase").getAsString(); expected = report.get("expectedNextEvent").getAsString();
        state = report.get("state").getAsString(); incident = TaskWire.text(report, "incidentId");
        age = report.get("progressAgeMs").getAsLong(); attempts = report.get("recoveryAttempts").getAsInt();
        milestone = string(saved, "milestone", 128); target = string(saved, "target", 128);
        bestDistance = finite(saved, "bestDistance"); bestEffort = finite(saved, "bestEffort");
        retryAge = integer(saved, "retryAgeMs", 86_400_000);
        if (!saved.has("initialized") || !saved.getAsJsonPrimitive("initialized").isBoolean()) throw new IllegalArgumentException("Invalid progress checkpoint");
        initialized = saved.get("initialized").getAsBoolean(); resetClock();
    }
    /** Optional, bounded telemetry; old workers do not need to supply it. Validate before host mutations. */
    public static JsonObject checked(JsonObject value) {
        if (!Set.of("phase", "expectedNextEvent", "progressAgeMs", "recoveryAttempts", "state", "incidentId", "target", "closestDistance").containsAll(value.keySet()))
            throw new IllegalArgumentException("Unknown action progress field");
        string(value, "phase", 128); string(value, "expectedNextEvent", 256);
        integer(value, "progressAgeMs", 86_400_000); integer(value, "recoveryAttempts", MAX_RECOVERIES);
        String state = string(value, "state", 16);
        if (!Set.of("Working", "Waiting", "Recovering", "Paused").contains(state)) throw new IllegalArgumentException("Invalid action progress state");
        if (value.has("incidentId")) UUID.fromString(string(value, "incidentId", 36));
        if (value.has("target")) string(value, "target", 128);
        if (value.has("closestDistance") && finite(value,"closestDistance") > 100_000_000) throw new IllegalArgumentException("Invalid action progress distance");
        if (state.equals("Paused") && !value.has("incidentId")) throw new IllegalArgumentException("A stalled pause needs its incident ID");
        return value.deepCopy();
    }
    public static String describe(JsonObject value) {
        return TaskWire.text(value, "state") + " · " + TaskWire.text(value, "phase") + " · awaiting " + TaskWire.text(value, "expectedNextEvent")
            + " · " + value.get("progressAgeMs").getAsLong() / 1000 + "s without progress · " + value.get("recoveryAttempts") + "/" + MAX_RECOVERIES + " navigation retries"
            + (value.has("target") ? " · target " + TaskWire.text(value,"target") : "")
            + (value.has("closestDistance") ? " · closest approach " + String.format(java.util.Locale.ROOT,"%.1f",value.get("closestDistance").getAsDouble()) + " blocks" : "");
    }
    public static boolean recoveryChanged(JsonObject before, JsonObject after) {
        if (after == null || !after.has("incidentId")) return false;
        return before == null || !TaskWire.text(before,"incidentId").equals(TaskWire.text(after,"incidentId"))
            || !before.get("recoveryAttempts").equals(after.get("recoveryAttempts"))
            || TaskWire.text(after,"state").equals("Paused") && !TaskWire.text(before,"state").equals("Paused");
    }
    private static String string(JsonObject value, String key, int limit) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isString() || value.get(key).getAsString().length() > limit)
            throw new IllegalArgumentException("Invalid action progress " + key);
        return value.get(key).getAsString();
    }
    private static long integer(JsonObject value, String key, long max) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException("Invalid action progress " + key);
        long number;
        try { number = value.get(key).getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException error) { throw new IllegalArgumentException("Invalid action progress " + key, error); }
        if (number < 0 || number > max) throw new IllegalArgumentException("Invalid action progress " + key); return number;
    }
    private static double finite(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException("Invalid action progress " + key);
        double number = value.get(key).getAsDouble();
        if (!Double.isFinite(number) || number < 0) throw new IllegalArgumentException("Invalid action progress " + key); return number;
    }
}
