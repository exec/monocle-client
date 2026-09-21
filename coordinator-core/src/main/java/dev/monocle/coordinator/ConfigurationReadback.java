package dev.monocle.coordinator;

import com.google.gson.*;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.*;

/** On-demand, bounded session cache. Never part of the worker's recurring telemetry or durable job. */
public final class ConfigurationReadback {
    public static final int MAX_LENGTH = 48_000;
    private final Map<UUID, JsonObject> samples = new LinkedHashMap<>();

    public JsonObject request(JsonObject task, UUID worker, String profile, String module, long now) {
        JsonObject runs = task.getAsJsonObject("runs");
        if (!runs.has(worker.toString())) throw new IllegalArgumentException("Worker is not assigned to this job");
        JsonObject run = runs.getAsJsonObject(worker.toString());
        if (!run.has("readbackVersion") || run.get("readbackVersion").getAsInt() != 1)
            throw new IllegalArgumentException("Worker needs Monocle 0.8.2 or newer and must report its state first");
        if (!task.getAsJsonObject("package").getAsJsonObject("profiles").has(profile)
            && !(profile.equals("Latest live request / worker") && run.has("configuration"))) throw new IllegalArgumentException("Unknown captured profile or live request");
        if (!module.matches("[a-z0-9-]{1,80}")) throw new IllegalArgumentException("Invalid module name");
        JsonObject old = samples.get(worker);
        if (old != null && now - old.get("requestedAt").getAsLong() < 1000) throw new IllegalArgumentException("Wait one second between readbacks");
        JsonObject request = message("configuration-read");
        request.addProperty("request", UUID.randomUUID().toString()); request.addProperty("task", text(task,"id"));
        request.addProperty("run", text(run,"id")); request.addProperty("profile", profile); request.addProperty("module", module);
        if(profile.equals("Latest live request / worker"))request.add("configurationRevision",run.getAsJsonObject("configuration").get("revision").deepCopy());
        request.addProperty("requestedAt", now); request.addProperty("status", "Pending");
        samples.remove(worker); samples.put(worker, request);
        if (samples.size() > 128) samples.remove(samples.keySet().iterator().next());
        return request.deepCopy();
    }

    public boolean accept(UUID worker, JsonObject response, long now) {
        JsonObject pending = samples.get(worker);
        if (pending == null || !text(pending,"status").equals("Pending") || now - pending.get("requestedAt").getAsLong() > 10_000) return false;
        for (String key : List.of("request","task","run","profile","module"))
            if (!text(pending,key).equals(text(response,key))) return false;
        int index = response.get("index").getAsBigDecimal().intValueExact(), count = response.get("count").getAsBigDecimal().intValueExact();
        String chunk = text(response,"payload");
        if (count < 1 || count > 16 || index < 0 || index >= count || chunk.length() > 4000) throw new IllegalArgumentException("Invalid readback chunk");
        int next = pending.has("_next") ? pending.get("_next").getAsInt() : 0;
        if (index != next || next > 0 && count != pending.get("_count").getAsInt()) return false;
        String payload = text(pending,"_payload") + chunk;
        if (payload.length() > MAX_LENGTH*4/3) throw new IllegalArgumentException("Readback exceeds its limit");
        pending.addProperty("_payload",payload); pending.addProperty("_next",next+1); pending.addProperty("_count",count);
        if (next+1 < count) return false;
        byte[] bytes=Base64.getDecoder().decode(payload);
        String json=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        if(!Arrays.equals(bytes,json.getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new IllegalArgumentException("Invalid readback encoding");
        JsonObject result = checkedReport(JsonParser.parseString(json).getAsJsonObject());
        pending.remove("_payload"); pending.remove("_next"); pending.remove("_count");
        pending.add("report", result); pending.addProperty("receivedAt", now); pending.addProperty("status", "Snapshot");
        return true;
    }

    public JsonObject get(UUID task, UUID worker, long now) {
        JsonObject sample = samples.get(worker);
        if (sample == null || !text(sample,"task").equals(task.toString())) { JsonObject empty = new JsonObject(); empty.addProperty("status","Not requested"); return empty; }
        JsonObject result = sample.deepCopy();
        result.remove("_payload"); result.remove("_next"); result.remove("_count");
        if (text(result,"status").equals("Pending") && now - result.get("requestedAt").getAsLong() > 10_000)
            result.addProperty("status", "Timed out — worker offline, busy, or no longer owns this job; request again");
        return result;
    }

    public static List<JsonObject> replies(JsonObject request, JsonObject report) {
        String payload = Base64.getEncoder().encodeToString(TaskFiles.jsonBytes(checkedReport(report),MAX_LENGTH)); int count = (payload.length()+3999)/4000;
        List<JsonObject> result = new ArrayList<>();
        for (int index=0; index<count; index++) {
            JsonObject reply = message("configuration-report");
            for (String key : List.of("request","task","run","profile","module")) reply.add(key,request.get(key).deepCopy());
            reply.addProperty("index",index); reply.addProperty("count",count);
            reply.addProperty("payload",payload.substring(index*4000,Math.min(payload.length(),(index+1)*4000)));
            result.add(reply);
        }
        return result;
    }

    public static JsonObject checkedReport(JsonObject report) {
        TaskFiles.jsonBytes(report,MAX_LENGTH);
        if (report == null || report.toString().length() > MAX_LENGTH || !report.keySet().equals(Set.of("note","rows"))
            || !report.get("note").isJsonPrimitive() || !report.getAsJsonPrimitive("note").isString() || text(report,"note").length() > 2048
            || !report.get("rows").isJsonArray() || report.getAsJsonArray("rows").size() > 512) throw new IllegalArgumentException("Invalid configuration readback");
        for (JsonElement entry : report.getAsJsonArray("rows")) {
            JsonObject row = entry.getAsJsonObject();
            if (!row.keySet().equals(Set.of("setting","personal","requested","current"))) throw new IllegalArgumentException("Invalid comparison row");
            for (var value : row.entrySet()) if (!value.getValue().isJsonPrimitive() || !value.getValue().getAsJsonPrimitive().isString()
                || value.getValue().getAsString().length() > 4096) throw new IllegalArgumentException("Oversized comparison value");
        }
        return report.deepCopy();
    }
}
