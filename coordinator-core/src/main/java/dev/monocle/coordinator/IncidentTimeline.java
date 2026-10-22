package dev.monocle.coordinator;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Bounded observational history. Logging failure never gates a worker or a safety disconnect. */
public final class IncidentTimeline {
    private final Deque<JsonObject> entries = new ArrayDeque<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private final Map<String, String> latest = new LinkedHashMap<>();
    private final Consumer<JsonObject> sink;
    private CrewTelemetry log;
    private int unreadable;
    private Consumer<JsonObject> observer = _ -> { };

    public void onEvent(Consumer<JsonObject> observer) { this.observer = Objects.requireNonNull(observer); }

    public IncidentTimeline(Path file) {
        log = new CrewTelemetry(file);
        sink = log::event;
        for (int generation = 3; generation >= 0; generation--) {
            Path source = generation == 0 ? file : file.resolveSibling(file.getFileName() + "." + generation);
            if (!Files.exists(source)) continue;
            try (var lines = Files.newBufferedReader(source)) {
                if (Files.size(source) > 16 * 1024 * 1024) { unreadable++; continue; }
                String line;
                while ((line = lines.readLine()) != null) {
                    try {
                        JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                        TaskFiles.jsonBytes(event, 8192);
                        if (!TaskWire.text(event, "id").matches("[a-f0-9]{64}")
                            || !Set.of("sighting", "damage", "death", "crystal", "hostile_logout", "reconnect").contains(TaskWire.text(event, "kind"))
                            || TaskWire.text(event, "workerName").length() > 64 || TaskWire.text(event, "crew").length() > 64
                            || TaskWire.text(event, "sourceKey").length() > 100 || event.get("receivedAt").getAsBigDecimal().longValueExact() < 0) throw new IllegalArgumentException();
                        UUID.fromString(TaskWire.text(event, "workerId")); TaskFiles.jsonBytes(event.getAsJsonObject("evidence"), 4096);
                        retain(event);
                    } catch (RuntimeException e) { unreadable++; }
                }
            } catch (IOException e) { unreadable++; }
        }
    }
    IncidentTimeline(Consumer<JsonObject> sink) { this.sink = sink; }

    public void observe(UUID worker, String name, String crew, JsonObject report, Set<UUID> workers, KnownHostiles hostiles) {
        if (TaskWire.flag(report, "inLobby")) return;
        if (report.has("combat")) {
            JsonObject combat = report.getAsJsonObject("combat");
            TaskFiles.jsonBytes(combat, 4096);
            for (String field : List.of("lastDamage", "lastDeath", "lastCrystalExplosion", "lastHostileLogout")) {
                if (combat.has(field)) record(worker, name, crew, field, combat.getAsJsonObject(field));
            }
        }
        if (report.has("safeReconnect") && report.getAsJsonObject("safeReconnect").has("lastEvent")) {
            record(worker, name, crew, "lastReconnect", report.getAsJsonObject("safeReconnect").getAsJsonObject("lastEvent"));
        }
        if (report.has("playerSightings")) {
            JsonArray sightings = report.getAsJsonArray("playerSightings");
            PlayerSightings.validate(sightings);
            for (JsonElement value : sightings) {
                JsonObject evidence = value.getAsJsonObject();
                TaskFiles.jsonBytes(evidence, 1024);
                UUID id = UUID.fromString(TaskWire.text(evidence.getAsJsonObject("player"), "uuid"));
                if (id.equals(worker) || workers.contains(id)) continue;
                JsonObject observed = evidence.deepCopy();
                String playerName = TaskWire.text(observed.getAsJsonObject("player"), "name");
                String classification = hostiles.match(KnownHostiles.serverOf(TaskWire.text(observed, "scope")), id, playerName) != null ? "hostile"
                    : TaskWire.flag(observed, "trusted") ? "friendly" : "neutral";
                observed.addProperty("classification", classification);
                record(worker, name, crew, "sighting", observed);
            }
        }
    }

    private void record(UUID worker, String name, String crew, String field, JsonObject evidence) {
        TaskFiles.jsonBytes(evidence, 4096);
        String key = worker + ":" + field;
        // Classification can change after a sighting; its stable source ID still denotes one observation.
        String hash = TaskFiles.hash(key + "\n" + (field.equals("sighting") ? TaskWire.text(evidence, "id") : evidence.toString()));
        if (seen.contains(hash) || !field.equals("sighting") && hash.equals(latest.get(key))) return;
        String kind = switch (field) {
            case "lastDamage" -> "damage";
            case "lastDeath" -> "death";
            case "lastCrystalExplosion" -> "crystal";
            case "lastHostileLogout" -> "hostile_logout";
            case "lastReconnect" -> "reconnect";
            default -> "sighting";
        };
        JsonObject event = new JsonObject();
        event.addProperty("id", hash); event.addProperty("kind", kind); event.addProperty("sourceKey", key);
        event.addProperty("workerId", worker.toString()); event.addProperty("workerName", clip(name, 64)); event.addProperty("crew", clip(crew, 64));
        event.addProperty("receivedAt", System.currentTimeMillis()); event.add("evidence", evidence.deepCopy());
        retain(event);
        try { sink.accept(event); } catch (RuntimeException e) { unreadable++; }
        try { observer.accept(event.deepCopy()); } catch(RuntimeException e) { unreadable++; }
    }
    private void retain(JsonObject event) {
        String id = TaskWire.text(event, "id");
        if (!seen.add(id)) return;
        entries.addLast(event.deepCopy());
        while (entries.size() > 2048) entries.removeFirst();
        while (seen.size() > 8192) seen.remove(seen.iterator().next());
        if (!TaskWire.text(event, "kind").equals("sighting")) latest.put(TaskWire.text(event, "sourceKey"), id);
        while (latest.size() > 1024) latest.remove(latest.keySet().iterator().next());
    }
    public JsonObject summary() {
        JsonObject result = new JsonObject(); result.addProperty("retained", entries.size()); result.addProperty("capacity", 2048);
        result.addProperty("unreadableRecords", unreadable); result.addProperty("dropped", log == null ? 0 : log.dropped()); result.addProperty("error", log == null ? "" : log.error());
        return result;
    }
    public JsonObject page(String before, int limit, String kind, String search) {
        if (limit < 1 || limit > 100 || search.length() > 100) throw new IllegalArgumentException("Invalid incident page size or search");
        if (!Set.of("", "sighting", "damage", "death", "crystal", "hostile_logout", "reconnect").contains(kind)) throw new IllegalArgumentException("Invalid incident kind");
        if (before != null && !before.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid incident cursor");
        List<JsonObject> all = new ArrayList<>(entries); Collections.reverse(all);
        int start = 0;
        if (before != null) {
            while (start < all.size() && !TaskWire.text(all.get(start), "id").equals(before)) start++;
            if (start == all.size()) { JsonObject result = summary(); result.addProperty("cursorExpired", true); result.add("items", new JsonArray()); return result; }
            start++;
        }
        JsonArray items = new JsonArray(); String next = ""; boolean more = false;
        String query = search.toLowerCase(Locale.ROOT);
        for (int i = start; i < all.size(); i++) {
            JsonObject entry = all.get(i);
            if (!kind.isEmpty() && !kind.equals(TaskWire.text(entry, "kind")) || !entry.toString().toLowerCase(Locale.ROOT).contains(query)) continue;
            if (items.size() == limit) { more = true; break; }
            items.add(entry.deepCopy()); next = TaskWire.text(entry, "id");
        }
        JsonObject result = summary(); result.add("items", items); result.addProperty("hasMore", more); result.addProperty("nextCursor", next);
        return result;
    }
    private static String clip(String value, int max) { return value.substring(0, Math.min(value.length(), max)); }
}
