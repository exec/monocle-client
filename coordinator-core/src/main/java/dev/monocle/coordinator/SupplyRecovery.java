package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.Path;
import java.util.*;

/** Durable workflow obligations, independent of the game actor and of either host implementation. */
public final class SupplyRecovery {
    private final Path file;
    private final JsonObject records;
    public SupplyRecovery(Path file) {
        this.file = file;
        JsonObject root = TaskFiles.read(file);
        if (java.nio.file.Files.exists(file) && root.isEmpty()) throw new IllegalArgumentException("Empty recovery journal");
        if (!root.isEmpty()) integer(root, "version", 1, 1);
        if (!root.isEmpty() && (!root.has("records") || !root.get("records").isJsonObject())) throw new IllegalArgumentException("Missing recovery records");
        records = root.has("records") ? root.getAsJsonObject("records") : new JsonObject();
        if (records.size() > 128) throw new IllegalArgumentException("Too many unresolved supply operations");
        for (var e : records.entrySet()) {
            UUID.fromString(e.getKey()); checked(e.getValue().getAsJsonObject());
            if (!e.getKey().equals(TaskWire.text(e.getValue().getAsJsonObject(),"id"))) throw new IllegalArgumentException("Recovery record identity mismatch");
        }
    }
    public List<JsonObject> pending(String owner, String scope) {
        return records.entrySet().stream().map(e -> e.getValue().getAsJsonObject())
            .filter(r -> owner.equals(TaskWire.text(r,"owner")) && scope.equals(TaskWire.text(r,"scope")))
            .map(JsonObject::deepCopy).toList();
    }
    public void put(JsonObject record) {
        checked(record); String id = TaskWire.text(record,"id");
        if (!records.has(id) && records.size() >= 128) throw new IllegalStateException("Recover outstanding supplies before placing another container");
        JsonObject next = records.deepCopy(); next.add(id, record.deepCopy()); save(next);
    }
    public void resolved(String id) {
        UUID.fromString(id); JsonObject next = records.deepCopy(); next.remove(id); save(next);
    }
    private void save(JsonObject next) {
        JsonObject root = new JsonObject(); root.addProperty("version",1); root.add("records",next);
        TaskFiles.write(file,root); // A failed write must not change the in-memory ownership decision.
        records.entrySet().clear(); next.entrySet().forEach(e -> records.add(e.getKey(), e.getValue()));
    }
    public static JsonObject checked(JsonObject r) {
        UUID.fromString(TaskWire.text(r,"id")); UUID.fromString(TaskWire.text(r,"owner"));
        String scope = TaskWire.text(r,"scope"); if (scope.isBlank() || scope.length() > 1024) throw new IllegalArgumentException("Invalid recovery world");
        integer(r,"x",-29_999_984,29_999_984); integer(r,"z",-29_999_984,29_999_984); integer(r,"y",-2048,2048);
        integer(r,"baseline",0,1_048_576); integer(r,"expected",1,64);
        if (!Set.of("placing","breaking").contains(TaskWire.text(r,"stage")) || !r.has("stack") || !r.get("stack").isJsonObject()) throw new IllegalArgumentException("Invalid supply obligation");
        if (r.has("drop")) UUID.fromString(TaskWire.text(r,"drop"));
        return r;
    }
    private static void integer(JsonObject r, String key, int min, int max) {
        if (!r.has(key) || !r.get(key).isJsonPrimitive() || !r.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException("Invalid recovery " + key);
        int value = r.get(key).getAsBigDecimal().intValueExact();
        if (value < min || value > max) throw new IllegalArgumentException("Invalid recovery " + key);
    }
    /** Air/unloaded terrain alone can never prove that a resource was recovered. */
    public static boolean confirmed(boolean freshInventory, boolean loaded, boolean air, boolean dropPresent,
                                    int inventory, int baseline, int expected, boolean placing) {
        return freshInventory && loaded && air && !dropPresent
            && inventory >= baseline + (placing ? 0 : expected);
    }
}
