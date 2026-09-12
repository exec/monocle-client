package dev.monocle.coordinator;

import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Shared host decisions over existing task records. No clock, world, transport or files are read here.
 * Callers own these records on one thread and must persist mutations before sending commands.
 * Inputs are internal validated records, not an unauthenticated control API.
 */
public final class QueuePolicy {
    private QueuePolicy() { }

    public static boolean terminal(String status) { return Set.of("Complete", "Cancelled", "Failed").contains(status); }
    public static void checkedPriority(int value) {
        if (value < -1000 || value > 1000) throw new IllegalArgumentException("Priority must be -1000 through 1000");
    }
    public static int priority(JsonObject task, UUID worker) {
        JsonObject overrides = task.getAsJsonObject("overrides");
        return overrides.has(worker.toString()) ? overrides.get(worker.toString()).getAsInt() : task.get("priority").getAsInt();
    }
    public static JsonObject choose(Collection<JsonObject> tasks, UUID worker) {
        JsonObject best = null;
        for (JsonObject task : tasks) {
            if (flag(task, "paused") || flag(task, "cancelled") || terminal(text(task, "status"))
                || !task.getAsJsonObject("runs").has(worker.toString()) || terminal(text(run(task, worker), "status"))) continue;
            if (best == null || priority(task, worker) > priority(best, worker)) best = task;
        }
        return best; // Caller preserves insertion order; UI sorting must not change FIFO ties.
    }
    public static boolean preempts(JsonObject active, JsonObject next, UUID worker) {
        return next != null && next != active && priority(next, worker) > priority(active, worker);
    }
    public record Dispatch(JsonObject task, String command) { }
    /** Generic workflow scheduling, after the adapter has resolved native lane/TPA reservations. */
    public static Dispatch dispatch(JsonObject active, JsonObject next, UUID worker) {
        if (active != null) {
            if (flag(active, "cancelled")) return new Dispatch(active, "cancel");
            if (text(run(active, worker), "status").equals("Suspending")) return new Dispatch(active,
                resumingSuspension(run(active, worker)) && next == active && !flag(active, "paused") ? "resume" : "");
            return new Dispatch(active, flag(active, "paused") || preempts(active, next, worker) ? "pause" : "external");
        }
        if (next == null) return new Dispatch(null, "");
        String state = text(run(next, worker), "status");
        return new Dispatch(next, Set.of("Queued", "Sending").contains(state) ? "transfer"
            : Set.of("Ready", "Suspended", "Inspection required").contains(state) ? "resume" : "");
    }
    public static boolean pause(JsonObject task) {
        if (terminal(text(task, "status")) || flag(task, "cancelled")) return false;
        task.addProperty("paused", true); task.addProperty("status", "Paused");
        task.addProperty("detail", "Pausing at safe recovery checkpoints"); return true;
    }
    public static boolean resumingSuspension(JsonObject run) {
        return text(run, "status").equals("Suspending") && text(run, "requestedStatus").equals("Suspended");
    }
    public static void resume(JsonObject task) {
        if (terminal(text(task, "status"))) throw new IllegalStateException("Queue a new task to repeat finished work");
        if (flag(task, "cancelled")) throw new IllegalStateException("Cancellation is already pending; it cannot be resumed as work");
        for (var entry : task.getAsJsonObject("runs").entrySet())
            if (text(entry.getValue().getAsJsonObject(), "status").equals("Inspection required")) entry.getValue().getAsJsonObject().addProperty("resumeInspection", true);
        task.addProperty("paused", false); task.addProperty("status", "Queued"); task.addProperty("detail", "Resuming saved workflow frames");
    }
    public static boolean cancel(JsonObject task) {
        if (terminal(text(task, "status"))) return false;
        task.addProperty("cancelled", true); task.addProperty("paused", false);
        // The decision is authoritative now; run checkpoints retain delivery/cleanup debt.
        task.addProperty("status", "Cancelled"); task.addProperty("detail", "Cancelled by host. Offline workers reconcile on reconnect; resource recovery remains recorded.");
        return true;
    }
    public static void reconcileMissingRun(JsonObject task, JsonObject run) {
        if (terminal(text(run, "status"))) return;
        boolean cancel = flag(task, "cancelled");
        // Missing executed checkpoints must never replay a drop or teleport automatically.
        boolean unsent = !flag(run, "resumeSent") && Set.of("Queued", "Sending").contains(text(run, "status"));
        run.addProperty("status", cancel ? "Cancelled" : unsent ? "Queued" : "Inspection required");
        run.addProperty("detail", cancel ? "Worker confirms no saved execution; cancelled" : unsent ? "Worker needs the task package resent" : "Worker checkpoint missing; cancel this task and inspect prior effects before creating another");
        if (!cancel && !unsent) { task.addProperty("paused", true); task.addProperty("status", "Inspection required"); }
    }
    public static String cancellationCommand(JsonObject run) {
        // Resume only saved cleanup intent, never the cancelled Lua workflow.
        return text(run, "status").equals("Inspection required") && text(run, "requestedStatus").equals("Cancelled") ? "resume" : "cancel";
    }
    public static String summarizedStatus(JsonObject task) {
        if (flag(task, "cancelled")) return "Cancelled";
        if (terminal(text(task, "status"))) return text(task, "status");
        List<JsonObject> runs = task.getAsJsonObject("runs").entrySet().stream().map(e -> e.getValue().getAsJsonObject()).toList();
        if (runs.stream().allMatch(r -> terminal(text(r, "status")))) return flag(task, "cancelled") ? "Cancelled" : runs.stream().anyMatch(r -> text(r, "status").equals("Failed")) ? "Failed" : "Complete";
        if (flag(task, "paused")) return text(task, "status").equals("Inspection required") ? "Inspection required" : "Paused";
        if (runs.stream().anyMatch(r -> text(r, "status").equals("Running"))) return "Running";
        if (runs.stream().anyMatch(r -> text(r, "status").equals("Suspended"))) return "Suspended";
        return "Queued";
    }
    public static boolean teleportPending(JsonObject tpa, JsonObject run, long now) {
        return !terminal(text(run, "status")) && text(tpa, "token").equals(text(run, "token"))
            && run.has("action") && text(run.getAsJsonObject("action"), "type").equals("Tpa")
            && tpa.has("deadline") && tpa.get("deadline").getAsLong() > now;
    }
    public static boolean sameTeleportServer(String first, String second) {
        int a = first.indexOf('\n'), b = second.indexOf('\n');
        return a > 0 && b > 0 && a < first.length() - 1 && b < second.length() - 1
            && first.substring(0, a).equalsIgnoreCase(second.substring(0, b));
    }
    public static boolean validTeleportTtl(long now, long expiry) { return expiry >= now && expiry - now >= 0 && expiry - now <= 30_000; }
    public static boolean teleportWarmupReady(JsonObject tpa, long now) {
        if (!tpa.has("acknowledgedAt") || flag(tpa, "recovered") || flag(tpa, "accepted")) return false;
        long acknowledged = tpa.get("acknowledgedAt").getAsLong();
        if (now < acknowledged) return false;
        var value = tpa.getAsJsonPrimitive("warmup");
        if (value == null || !value.isNumber()) throw new IllegalArgumentException("Missing integer: warmup");
        int warmup;
        try { warmup = value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Invalid integer: warmup", e); }
        if (warmup < 0 || warmup > 72_000) throw new IllegalArgumentException("Out of range: warmup");
        return now - acknowledged >= warmup * 50L;
    }
    private static JsonObject run(JsonObject task, UUID worker) { return task.getAsJsonObject("runs").getAsJsonObject(worker.toString()); }
    private static boolean flag(JsonObject value, String key) { return value.has(key) && value.get(key).getAsBoolean(); }
    private static String text(JsonObject value, String key) { return value.has(key) ? value.get(key).getAsString() : ""; }
}
