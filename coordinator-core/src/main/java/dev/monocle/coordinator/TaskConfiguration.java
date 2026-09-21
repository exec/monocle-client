package dev.monocle.coordinator;

import com.google.gson.JsonObject;
import java.util.List;

/** Read-only, allowlisted view shared by both host interfaces; never guesses live settings. */
public final class TaskConfiguration {
    private TaskConfiguration() { }

    public static JsonObject inspect(JsonObject task) {
        JsonObject view = new JsonObject();
        view.addProperty("explanation", "Captured profiles are setting overlays saved when this job was created. Unspecified settings retain worker values. Native workflow capabilities also govern highway supplies. These are not live module readings; later workflow actions or local changes may alter them. Live requests affect this job, not future-job defaults. Only the latest request per worker is retained.");
        JsonObject packaged = task.has("package") ? task.getAsJsonObject("package") : new JsonObject();
        for (String key : List.of("profiles", "highways"))
            view.add(key, packaged.has(key) ? packaged.get(key).deepCopy() : new JsonObject());
        JsonObject updates = new JsonObject();
        if (task.has("runs")) for (var entry : task.getAsJsonObject("runs").entrySet()) {
            JsonObject run = entry.getValue().getAsJsonObject(), update = new JsonObject();
            int requested = run.has("configuration") ? run.getAsJsonObject("configuration").get("revision").getAsInt() : 0;
            int acknowledged = run.has("configRevision") ? run.get("configRevision").getAsInt() : 0;
            String error = TaskWire.text(run, "configError");
            String state = requested == 0 ? "No live request" : acknowledged < requested
                ? QueuePolicy.terminal(TaskWire.text(run, "status")) || TaskWire.flag(task, "cancelled") ? "Ended without acknowledgement" : "Pending"
                : error.isEmpty() ? "Accepted" : "Rejected";
            update.addProperty("status", state);
            update.addProperty("requestedRevision", requested);
            update.addProperty("acknowledgedRevision", acknowledged);
            // An error from an older revision must not describe a newly pending request.
            update.addProperty("error", acknowledged >= requested && requested > 0 ? error : "");
            update.add("modules", requested > 0 ? run.getAsJsonObject("configuration").get("modules").deepCopy() : new JsonObject());
            updates.add(entry.getKey(), update);
        }
        view.add("updates", updates);
        return view;
    }
}
