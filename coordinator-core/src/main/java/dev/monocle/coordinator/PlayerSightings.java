package dev.monocle.coordinator;

import com.google.gson.*;
import java.util.*;

/** Entrances only: a player must be absent for ten seconds before another visit is recorded. */
public final class PlayerSightings {
    private final Map<UUID, Long> present = new LinkedHashMap<>();
    private final Deque<JsonObject> events = new ArrayDeque<>();
    private String scope = "";
    private long sampledAt = Long.MIN_VALUE;
    private JsonArray current = new JsonArray();
    private int checkOffset;

    public void resetView() { present.clear(); current = new JsonArray(); checkOffset = 0; sampledAt = Long.MIN_VALUE; scope = ""; }
    public void observe(String scope, JsonArray players, long now) {
        if (!scope.equals(this.scope)) { resetView(); this.scope = scope; }
        // The caller samples once a second, but a pre-logout report must refresh visibility immediately.
        sampledAt = now;
        current = new JsonArray();
        for (int i = 0; i < Math.min(32, players.size()); i++) {
            JsonObject event = new JsonObject(); event.addProperty("id", UUID.randomUUID().toString());
            event.addProperty("at", now); event.addProperty("scope", scope);
            JsonObject player = players.get((checkOffset + i) % players.size()).getAsJsonObject();
            event.add("player", player.deepCopy()); event.addProperty("trusted", TaskWire.flag(player, "trusted")); current.add(event);
        }
        checkOffset = players.isEmpty() ? 0 : (checkOffset + current.size()) % players.size();
        present.values().removeIf(at -> now < at || now - at >= 10_000);
        int added = 0;
        for (JsonElement value : players) {
            JsonObject player = value.getAsJsonObject();
            UUID id = UUID.fromString(TaskWire.text(player, "uuid"));
            if (!present.containsKey(id) && added++ < 32) {
                JsonObject event = new JsonObject(); event.addProperty("id", UUID.randomUUID().toString());
                event.addProperty("at", now); event.addProperty("scope", scope); event.add("player", player.deepCopy());
                event.addProperty("trusted", TaskWire.flag(player, "trusted"));
                events.addLast(event); while (events.size() > 32) events.removeFirst();
            } else if (!present.containsKey(id)) continue; // Leave overflow entrants for the next sample.
            present.put(id, now);
        }
        while (present.size() > 1024) present.remove(present.keySet().iterator().next());
    }
    public JsonArray snapshot() { JsonArray result = new JsonArray(); events.forEach(e -> result.add(e.deepCopy())); return result; }
    /** Current view only, never the retained entrance history; worker and host clocks need not agree. */
    public JsonObject checks(long now) {
        long age = sampledAt == Long.MIN_VALUE || now < sampledAt ? 5000 : Math.min(5000, now - sampledAt);
        JsonObject result = new JsonObject(); result.addProperty("ageMs", age);
        result.add("sightings", age < 5000 ? current.deepCopy() : new JsonArray()); return result;
    }
    public static void validateChecks(JsonObject checks) {
        var age = checks.get("ageMs");
        if (age == null || !age.isJsonPrimitive() || !age.getAsJsonPrimitive().isNumber()
            || age.getAsBigDecimal().longValueExact() < 0 || age.getAsBigDecimal().longValueExact() > 5000) throw new IllegalArgumentException("Invalid player check age");
        validate(checks.getAsJsonArray("sightings"));
    }
    public static void validate(JsonArray sightings) {
        if (sightings.size() > 32) throw new IllegalArgumentException("Too many player sightings");
        for (JsonElement value : sightings) {
            JsonObject event = value.getAsJsonObject(); TaskFiles.jsonBytes(event, 1024);
            UUID.fromString(TaskWire.text(event, "id"));
            if (event.get("at").getAsBigDecimal().longValueExact() < 0 || TaskWire.text(event, "scope").length() > 1024) throw new IllegalArgumentException("Invalid sighting metadata");
            KnownHostiles.serverOf(TaskWire.text(event, "scope"));
            JsonObject player = event.getAsJsonObject("player");
            UUID.fromString(TaskWire.text(player, "uuid"));
            if (!TaskWire.text(player, "name").matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Invalid sighted player");
            for (String key : List.of("x", "y", "z")) {
                if (!player.has(key) || !player.get(key).isJsonPrimitive() || !player.getAsJsonPrimitive(key).isNumber() || !Double.isFinite(player.get(key).getAsDouble())) throw new IllegalArgumentException("Invalid sighting position");
            }
            if (!event.has("trusted") || !event.get("trusted").isJsonPrimitive() || !event.getAsJsonPrimitive("trusted").isBoolean()) throw new IllegalArgumentException("Invalid sighting classification");
        }
    }
}
