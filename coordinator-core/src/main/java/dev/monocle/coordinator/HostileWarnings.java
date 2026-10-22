package dev.monocle.coordinator;

import com.google.gson.*;
import java.util.*;

/** Host-wide, short-lived alarms from authenticated current views, not historical sightings. */
public final class HostileWarnings {
    private record Alarm(JsonObject warning, long expires) { }
    private final Map<String, Alarm> active = new LinkedHashMap<>();
    private final Map<Object, Set<String>> delivered = new HashMap<>();
    private boolean armed;

    public List<JsonObject> observe(UUID worker, String name, String crew, JsonObject report, KnownHostiles hostiles, Set<UUID> workers, long now) {
        expire(hostiles, now);
        if (TaskWire.flag(report, "inLobby") || !report.has("playerChecks")) return List.of();
        JsonObject checks = report.getAsJsonObject("playerChecks"); PlayerSightings.validateChecks(checks);
        long age = checks.get("ageMs").getAsLong(); if (age >= 5000) return List.of();
        List<JsonObject> added = new ArrayList<>();
        for (JsonElement value : checks.getAsJsonArray("sightings")) {
            JsonObject sighting = value.getAsJsonObject(), player = sighting.getAsJsonObject("player");
            if (!TaskWire.text(report, "scope").equals(TaskWire.text(sighting, "scope")) || TaskWire.flag(sighting, "trusted")) continue;
            UUID id = UUID.fromString(TaskWire.text(player, "uuid")); if (worker.equals(id) || workers.contains(id)) continue;
            String server = KnownHostiles.serverOf(TaskWire.text(sighting, "scope"));
            if (hostiles.match(server, id, TaskWire.text(player, "name")) == null) continue;
            String key = server + "\n" + id;
            Alarm previous = active.get(key); JsonObject warning;
            if (previous != null) warning = previous.warning.deepCopy();
            else {
                warning = new JsonObject(); warning.addProperty("id", UUID.randomUUID().toString());
            }
            warning.addProperty("server", server); warning.add("sighting", sighting.deepCopy());
            warning.addProperty("observerId", worker.toString()); warning.addProperty("observerName", name.matches("[A-Za-z0-9_]{1,16}") ? name : "Worker_" + worker.toString().substring(0,8)); warning.addProperty("crew", crew);
            if (previous == null) added.add(warning.deepCopy());
            active.put(key, new Alarm(warning, now + 10_000 - age));
            while (active.size() > 256) active.remove(active.keySet().iterator().next());
        }
        return added;
    }
    private void expire(KnownHostiles hostiles, long now) {
        if (hostiles.enabled() && !armed) delivered.clear();
        armed = hostiles.enabled();
        active.values().removeIf(a -> {
            JsonObject p = a.warning.getAsJsonObject("sighting").getAsJsonObject("player");
            return now >= a.expires || a.expires - now > 10_000 || hostiles.match(TaskWire.text(a.warning, "server"), UUID.fromString(TaskWire.text(p, "uuid")), TaskWire.text(p, "name")) == null;
        });
        Set<String> ids = new HashSet<>(); active.values().forEach(a -> ids.add(TaskWire.text(a.warning, "id")));
        delivered.values().forEach(seen -> seen.retainAll(ids));
    }
    public List<JsonObject> pending(Object recipient, KnownHostiles hostiles, long now) {
        expire(hostiles, now); Set<String> seen = delivered.computeIfAbsent(recipient, _ -> new HashSet<>());
        List<JsonObject> frames = new ArrayList<>();
        for (Alarm a : active.values()) if (!seen.contains(TaskWire.text(a.warning, "id"))) {
            JsonObject frame = TaskWire.message("hostile-warning"); frame.add("warning", a.warning.deepCopy());
            frame.addProperty("remainingMs", a.expires - now); frame.addProperty("revision", hostiles.revision()); frames.add(frame);
        }
        return frames;
    }
    public void delivered(Object recipient, JsonObject frame) { delivered.computeIfAbsent(recipient, _ -> new HashSet<>()).add(TaskWire.text(frame.getAsJsonObject("warning"), "id")); }
    public void retainRecipients(Set<?> recipients) { delivered.keySet().retainAll(recipients); }
    public void clear() { active.clear(); delivered.clear(); armed = false; }
    public JsonArray snapshot(KnownHostiles hostiles, long now) {
        expire(hostiles, now); JsonArray result = new JsonArray();
        active.values().forEach(a -> { JsonObject warning = a.warning.deepCopy(); warning.addProperty("expiresAt", a.expires); result.add(warning); }); return result;
    }
    public static JsonObject receipt(JsonObject report, long revision) {
        JsonObject result = TaskWire.message("player-check-result");
        result.addProperty("checked", TaskWire.flag(report, "inLobby") || report.getAsJsonObject("playerChecks").get("ageMs").getAsLong() >= 5000 ? 0 : report.getAsJsonObject("playerChecks").getAsJsonArray("sightings").size());
        result.addProperty("revision", revision); return result;
    }
    public static void validateReceipt(JsonObject receipt) {
        TaskFiles.jsonBytes(receipt, 1024);
        for (String key : List.of("checked", "revision")) {
            var number = receipt.get(key);
            if (number == null || !number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber()
                || number.getAsBigDecimal().longValueExact() < 0 || key.equals("checked") && number.getAsBigDecimal().longValueExact() > 32) throw new IllegalArgumentException("Invalid player check receipt");
        }
    }
    public static void validate(JsonObject frame) {
        TaskFiles.jsonBytes(frame, 4096);
        var remaining = frame.get("remainingMs");
        if (remaining == null || !remaining.isJsonPrimitive() || !remaining.getAsJsonPrimitive().isNumber()
            || remaining.getAsBigDecimal().longValueExact() < 1 || remaining.getAsBigDecimal().longValueExact() > 10_000) throw new IllegalArgumentException("Invalid hostile warning lifetime");
        var revision = frame.get("revision");
        if (revision == null || !revision.isJsonPrimitive() || !revision.getAsJsonPrimitive().isNumber() || revision.getAsBigDecimal().longValueExact() < 0) throw new IllegalArgumentException("Invalid hostile warning revision");
        JsonObject warning = frame.getAsJsonObject("warning");
        UUID.fromString(TaskWire.text(warning, "id")); UUID.fromString(TaskWire.text(warning, "observerId"));
        if (!TaskWire.text(warning, "observerName").matches("[A-Za-z0-9_]{1,16}") || TaskWire.text(warning, "crew").length() > 64) throw new IllegalArgumentException("Invalid hostile observer");
        JsonArray sightings = new JsonArray(); sightings.add(warning.getAsJsonObject("sighting")); PlayerSightings.validate(sightings);
        if (!KnownHostiles.serverOf(TaskWire.text(warning.getAsJsonObject("sighting"), "scope")).equals(TaskWire.text(warning, "server"))) throw new IllegalArgumentException("Invalid hostile warning server");
    }
}
