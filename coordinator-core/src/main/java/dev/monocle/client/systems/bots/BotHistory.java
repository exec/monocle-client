package dev.monocle.client.systems.bots;

import com.google.gson.JsonObject;
import java.util.UUID;
import dev.monocle.coordinator.QueuePolicy;
import dev.monocle.coordinator.TaskWire;

/** Shared history policy; execution/recovery journals are deliberately not history. */
public final class BotHistory {
    public static boolean stamp(JsonObject record, boolean finished, long now) {
        if (!finished) return record.remove("finishedAt") != null;
        if (record.has("finishedAt")) { finishedAt(record); return false; }
        record.addProperty("finishedAt", now); return true;
    }
    public static long finishedAt(JsonObject record) {
        if (!record.has("finishedAt")) return 0;
        long time = record.get("finishedAt").getAsBigDecimal().longValueExact();
        if (time < 0) throw new IllegalArgumentException("Invalid job history timestamp");
        return time;
    }
    public static boolean expired(long finished, long now, int days) {
        return days >= 0 && finished > 0 && now >= finished && now - finished >= days * 86_400_000L;
    }
    public static boolean taskFinished(JsonObject task, boolean pendingReturn) {
        return QueuePolicy.terminal(TaskWire.text(task, "status")) && !task.has("highway") && !task.has("nativeDefinition") && !task.has("hostOriginal") && !pendingReturn
            && task.getAsJsonObject("runs").entrySet().stream().allMatch(e -> QueuePolicy.terminal(TaskWire.text(e.getValue().getAsJsonObject(), "status")));
    }
    public static boolean nativeFinished(JsonObject record) {
        return java.util.Set.of("Complete", "Cancelled").contains(TaskWire.text(record, "status")) && TaskWire.text(record, "crew").isEmpty();
    }
    public static UUID nativeId(JsonObject task) {
        for (String key : new String[] {"historyJobId", "highway"})
            if (task.has(key)) return UUID.fromString(task.get(key).getAsString());
        for (String key : new String[] {"nativeDefinition", "highwayDefinition"})
            if (task.has(key) && task.getAsJsonObject(key).has("id")) return UUID.fromString(task.getAsJsonObject(key).get("id").getAsString());
        JsonObject packaged = task.getAsJsonObject("package");
        if (packaged != null && packaged.has("geometry") && packaged.getAsJsonObject("geometry").has("id"))
            return UUID.fromString(packaged.getAsJsonObject("geometry").get("id").getAsString());
        return null;
    }
}
