package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.Path;
import java.util.*;

/** Shared, server-scoped safety list. Crystal suspects and plaintext death messages never add players. */
public final class KnownHostiles {
    private final Path file;
    private JsonObject state;

    public KnownHostiles(Path file) {
        this.file = file;
        state = TaskFiles.read(file);
        if (state.isEmpty()) {
            state.addProperty("version", 1);
            state.addProperty("revision", 0);
            state.addProperty("enabled", false);
            state.add("players", new JsonArray());
            state.add("receipts", new JsonObject());
        }
        validate(state);
        defaults(state);
        if (!state.has("receipts")) state.add("receipts", new JsonObject());
        if (state.getAsJsonObject("receipts").size() > 256) throw new IllegalArgumentException("Too many hostile report receipts");
        for (var receipt : state.getAsJsonObject("receipts").entrySet()) {
            UUID.fromString(receipt.getKey());
            if (!receipt.getValue().getAsString().matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid hostile report receipt");
        }
    }

    public JsonObject snapshot() {
        JsonObject copy = state.deepCopy();
        copy.remove("receipts");
        return copy;
    }
    public boolean enabled() { return state.get("enabled").getAsBoolean(); }
    public boolean safeReconnect() { return state.get("safeReconnect").getAsBoolean(); }
    public int reconnectSeconds() { return state.get("reconnectSeconds").getAsInt(); }
    public long revision() { return state.get("revision").getAsLong(); }

    public void accept(JsonObject snapshot) {
        validate(snapshot);
        JsonObject next = snapshot.deepCopy();
        defaults(next);
        next.add("receipts", new JsonObject());
        if (!snapshot.equals(snapshot())) commit(next);
    }
    public void policy(boolean enabled) {
        policy(enabled, safeReconnect(), reconnectSeconds());
    }
    public void policy(boolean enabled, boolean reconnect, int seconds) {
        if (seconds < 5 || seconds > 3600) throw new IllegalArgumentException("Safety reconnect wait must be 5–3600 seconds");
        if (enabled == enabled() && reconnect == safeReconnect() && seconds == reconnectSeconds()) return;
        JsonObject next = state.deepCopy();
        next.addProperty("enabled", enabled);
        next.addProperty("safeReconnect", reconnect);
        next.addProperty("reconnectSeconds", seconds);
        changed(next);
        commit(next);
    }
    public JsonObject add(String server, String name, String uuid) {
        JsonObject next = state.deepCopy();
        JsonObject entry = add(next, server, name, uuid, "manual", "");
        changed(next);
        commit(next);
        return entry.deepCopy();
    }
    public void remove(String id) {
        UUID.fromString(id);
        JsonObject next = state.deepCopy();
        boolean found = next.getAsJsonArray("players").asList().removeIf(p -> TaskWire.text(p.getAsJsonObject(), "id").equals(id));
        if (!found) throw new IllegalArgumentException("Unknown hostile player");
        changed(next);
        commit(next);
    }

    public boolean observe(UUID reporter, String scope, JsonObject combat, Set<UUID> protectedPlayers) {
        if (combat == null || !combat.has("lastDamage") || !combat.get("lastDamage").isJsonObject()) return false;
        JsonObject damage = combat.getAsJsonObject("lastDamage");
        if (damage.has("attackerTrusted") && damage.get("attackerTrusted").isJsonPrimitive()
            && damage.getAsJsonPrimitive("attackerTrusted").isBoolean() && damage.get("attackerTrusted").getAsBoolean()) return false;
        if (!TaskWire.text(damage, "attribution").equals("server_reported") || !damage.has("attacker") || !damage.get("attacker").isJsonObject()) return false;
        JsonObject actor = damage.getAsJsonObject("attacker");
        String uuid = TaskWire.text(actor, "uuid"), name = TaskWire.text(actor, "name");
        if (!actor.has("player") || !actor.get("player").isJsonPrimitive()
            || !actor.getAsJsonPrimitive("player").isBoolean() || !actor.get("player").getAsBoolean()) return false;
        String server;
        try {
            UUID id = UUID.fromString(uuid);
            if (id.equals(reporter) || protectedPlayers.contains(id)) return false;
            username(name);
            server = serverOf(scope);
            if (!serverOf(TaskWire.text(damage, "scope")).equals(server)) return false;
        } catch (IllegalArgumentException e) { return false; }
        String hash = TaskFiles.hash(damage.toString()), key = reporter.toString();
        JsonObject receipts = state.getAsJsonObject("receipts");
        if (receipts.has(key) && receipts.get(key).getAsString().equals(hash)) return false;
        JsonObject next = state.deepCopy();
        add(next, server, name, uuid, "attack", key);
        boolean changed = !state.get("players").equals(next.get("players"));
        JsonObject seen = next.getAsJsonObject("receipts");
        seen.remove(key);
        seen.addProperty(key, hash);
        while (seen.size() > 256) seen.remove(seen.keySet().iterator().next());
        if (changed) changed(next);
        commit(next);
        return changed;
    }

    private static JsonObject add(JsonObject next, String server, String name, String uuid, String source, String reporter) {
        server = server(server);
        username(name);
        if (!uuid.isEmpty()) uuid = UUID.fromString(uuid).toString();
        JsonArray players = next.getAsJsonArray("players");
        JsonObject entry = null;
        for (JsonElement value : players) {
            JsonObject p = value.getAsJsonObject();
            if (!p.get("server").getAsString().equals(server)) continue;
            String known = TaskWire.text(p, "uuid");
            if ((!uuid.isEmpty() && uuid.equals(known))
                || ((uuid.isEmpty() || known.isEmpty()) && name.equalsIgnoreCase(TaskWire.text(p, "name")))) {
                entry = p;
                break;
            }
        }
        if (entry == null) {
            if (players.size() >= 256) throw new IllegalStateException("Known hostile list is full (256)");
            entry = new JsonObject();
            entry.addProperty("id", UUID.randomUUID().toString());
            entry.addProperty("server", server);
            entry.addProperty("name", name);
            entry.addProperty("uuid", uuid);
            entry.addProperty("source", source);
            entry.addProperty("reporter", reporter);
            entry.addProperty("addedAt", System.currentTimeMillis());
            players.add(entry);
        } else {
            entry.addProperty("name", name);
            if (!uuid.isEmpty()) entry.addProperty("uuid", uuid);
        }
        return entry;
    }

    public static String match(JsonObject snapshot, String server, UUID uuid, String name) {
        server = server(server);
        for (JsonElement value : snapshot.getAsJsonArray("players")) {
            JsonObject p = value.getAsJsonObject();
            String id = TaskWire.text(p, "uuid");
            if (server.equals(TaskWire.text(p, "server"))
                && (id.isEmpty() ? name.equalsIgnoreCase(TaskWire.text(p, "name")) : uuid.toString().equals(id))) return TaskWire.text(p, "name");
        }
        return null;
    }
    public String match(String server, UUID uuid, String name) { return match(state, server, uuid, name); }

    public static String serverOf(String scope) {
        int newline = scope.indexOf('\n');
        if (newline < 1) throw new IllegalArgumentException("Expected server and dimension");
        return server(scope.substring(0, newline));
    }
    public static String server(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(":25565")) normalized = normalized.substring(0, normalized.length() - 6);
        if (normalized.isEmpty() || normalized.length() > 255
            || normalized.chars().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c))) throw new IllegalArgumentException("Invalid server address");
        return normalized;
    }
    private static void username(String name) {
        if (!name.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Use a Minecraft username (1–16 letters, digits or underscores)");
    }

    public static void validate(JsonObject snapshot) {
        TaskFiles.jsonBytes(snapshot, 196_608);
        if (!snapshot.has("version") || snapshot.get("version").getAsBigDecimal().intValueExact() != 1) throw new IllegalArgumentException("Invalid hostile list version");
        if (!snapshot.has("enabled") || !snapshot.get("enabled").isJsonPrimitive() || !snapshot.getAsJsonPrimitive("enabled").isBoolean()) throw new IllegalArgumentException("Invalid hostile guard policy");
        if (snapshot.has("safeReconnect") && (!snapshot.get("safeReconnect").isJsonPrimitive() || !snapshot.getAsJsonPrimitive("safeReconnect").isBoolean())) throw new IllegalArgumentException("Invalid safe reconnect policy");
        if (snapshot.has("reconnectSeconds")) {
            if (!snapshot.get("reconnectSeconds").isJsonPrimitive() || !snapshot.getAsJsonPrimitive("reconnectSeconds").isNumber()) throw new IllegalArgumentException("Invalid safety reconnect wait");
            int seconds = snapshot.get("reconnectSeconds").getAsBigDecimal().intValueExact();
            if (seconds < 5 || seconds > 3600) throw new IllegalArgumentException("Safety reconnect wait must be 5–3600 seconds");
        }
        if (!snapshot.has("revision") || snapshot.get("revision").getAsBigDecimal().longValueExact() < 0) throw new IllegalArgumentException("Invalid hostile list revision");
        JsonArray players = snapshot.getAsJsonArray("players");
        if (players == null || players.size() > 256) throw new IllegalArgumentException("Too many hostile players");
        Set<String> ids = new HashSet<>(), identities = new HashSet<>();
        for (JsonElement value : players) {
            JsonObject p = value.getAsJsonObject();
            for (String field : List.of("id", "server", "name", "uuid", "source", "reporter")) {
                if (!p.has(field) || !p.get(field).isJsonPrimitive() || !p.getAsJsonPrimitive(field).isString()) throw new IllegalArgumentException("Invalid hostile player " + field);
            }
            String id = UUID.fromString(TaskWire.text(p, "id")).toString();
            String server = server(TaskWire.text(p, "server")), name = TaskWire.text(p, "name"), uuid = TaskWire.text(p, "uuid");
            username(name);
            if (!uuid.isEmpty()) uuid = UUID.fromString(uuid).toString();
            if (!id.equals(TaskWire.text(p, "id")) || !server.equals(TaskWire.text(p, "server")) || !uuid.equals(TaskWire.text(p, "uuid"))) throw new IllegalArgumentException("Hostile identity must be canonical");
            String reporter = TaskWire.text(p, "reporter");
            if (!reporter.isEmpty() && !UUID.fromString(reporter).toString().equals(reporter)) throw new IllegalArgumentException("Invalid hostile reporter");
            if (!Set.of("manual", "attack").contains(TaskWire.text(p, "source")) || !p.has("addedAt") || p.get("addedAt").getAsBigDecimal().longValueExact() < 0) throw new IllegalArgumentException("Invalid hostile evidence metadata");
            if (!ids.add(id) || !identities.add(server + "\n" + (uuid.isEmpty() ? name.toLowerCase(Locale.ROOT) : uuid))) throw new IllegalArgumentException("Duplicate hostile player");
        }
    }
    private static void changed(JsonObject next) { next.addProperty("revision", Math.addExact(next.get("revision").getAsLong(), 1)); }
    private static void defaults(JsonObject value) {
        if (!value.has("safeReconnect")) value.addProperty("safeReconnect", false);
        if (!value.has("reconnectSeconds")) value.addProperty("reconnectSeconds", 300);
    }
    private void commit(JsonObject next) {
        validate(next);
        TaskFiles.write(file, next);
        state = next;
    }
}
