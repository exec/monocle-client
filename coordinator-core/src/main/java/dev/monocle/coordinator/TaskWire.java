package dev.monocle.coordinator;

import com.google.gson.*;
import java.util.*;

/** Shared dispatch envelope and report transitions; adapters validate native action semantics. */
public final class TaskWire {
    private TaskWire() { }
    public static String text(JsonObject object, String key) { return object.has(key) ? object.get(key).getAsString() : ""; }
    public static boolean flag(JsonObject object, String key) { return object.has(key) && object.get(key).getAsBoolean(); }
    public static JsonObject message(String type) { JsonObject m = new JsonObject(); m.addProperty("type", "task-" + type); return m; }
    public static JsonObject envelope(JsonObject task, UUID worker) {
        JsonObject metadata = new JsonObject();
        for (String key : List.of("id", "name", "crew", "workflowName", "args", "priority")) metadata.add(key.equals("id") ? "task" : key, task.get(key).deepCopy());
        for (String key : List.of("server", "dimension", "supportedActions")) if (task.has(key)) metadata.add(key, task.get(key).deepCopy());
        List<String> workers = List.copyOf(task.getAsJsonObject("runs").keySet());
        if (!workers.contains(worker.toString())) throw new IllegalArgumentException("Worker not targeted by task");
        metadata.addProperty("workerIndex", workers.indexOf(worker.toString())); metadata.addProperty("workerCount", workers.size());
        JsonObject envelope = task.getAsJsonObject("package").deepCopy(); envelope.add("dispatch", metadata); return envelope;
    }
    public static JsonObject begin(UUID run, String data) {
        JsonObject m = message("begin"); m.addProperty("run", run.toString()); m.addProperty("hash", TaskFiles.hash(data));
        m.addProperty("count", (data.length() + TaskFiles.CHUNK - 1) / TaskFiles.CHUNK); return m;
    }
    public static JsonObject chunk(UUID run, String data, int index) {
        JsonObject m = message("chunk"); m.addProperty("run", run.toString()); m.addProperty("index", index);
        m.addProperty("data", data.substring(index * TaskFiles.CHUNK, Math.min(data.length(), (index + 1) * TaskFiles.CHUNK))); return m;
    }
    public static JsonObject control(UUID run, String command) {
        if (!Set.of("resume", "pause", "cancel").contains(command)) throw new IllegalArgumentException("Invalid task control");
        JsonObject m = message("control"); m.addProperty("run", run.toString()); m.addProperty("command", command); return m;
    }
    public static boolean applyStatus(JsonObject task, JsonObject run, JsonObject message, boolean full) {
        String state = text(message, "status");
        UUID.fromString(text(message, "run"));
        if (!text(run, "id").equals(text(message, "run"))) throw new IllegalArgumentException("Wrong execution");
        if (!Set.of("Ready", "Running", "Suspending", "Suspended", "Inspection required", "Complete", "Failed", "Cancelled").contains(state)) throw new IllegalArgumentException("Invalid task execution state");
        if (QueuePolicy.terminal(text(run, "status")) && !QueuePolicy.terminal(state)) return false;
        JsonObject checked = message.deepCopy();
        if (full) {
            if (text(checked, "detail").length() > 1024) throw new IllegalArgumentException("Task status is too large");
            if (checked.has("action")) {
                if (!checked.get("action").isJsonObject() || checked.get("action").toString().length() > 8000) throw new IllegalArgumentException("Native action is too large");
                UUID.fromString(text(checked, "token"));
            }
        }
        run.addProperty("status", state);
        if (state.equals("Inspection required") && !flag(task, "cancelled") && !text(task, "status").equals("Inspection required") && !flag(run, "resumeInspection")) {
            task.addProperty("paused", true); task.addProperty("status", "Inspection required"); task.addProperty("detail", "Worker restarted; inspect the saved action and Resume from the host");
        }
        if (!state.equals("Inspection required")) run.remove("resumeInspection");
        if (full) {
            run.addProperty("detail", text(checked, "detail"));
            if (checked.has("action")) {
                run.add("action", checked.get("action")); run.addProperty("token", text(checked, "token")); run.addProperty("commandSent", flag(checked, "commandSent"));
            } else { run.remove("action"); run.remove("token"); }
            if (checked.has("requestedStatus")) run.addProperty("requestedStatus", text(checked, "requestedStatus")); else run.remove("requestedStatus");
            run.addProperty("connectionSuspended", flag(checked, "connectionSuspended"));
        }
        return true;
    }
}
