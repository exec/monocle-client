package dev.monocle.client.systems.bots;

import com.google.gson.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Bounded immutable task packages and atomic journals, shared by host and worker. */
final class BotTaskData {
    static final int MAX_PACKAGE = 4 * 1024 * 1024, CHUNK = 2000, MAX_JOURNAL = 64 * 1024 * 1024;
    static final int MAX_TRANSFER = ((MAX_PACKAGE + 2) / 3) * 4;
    private BotTaskData() { }
    static JsonObject read(Path file) { return dev.monocle.coordinator.TaskFiles.read(file); }
    static void write(Path file, JsonObject root) { dev.monocle.coordinator.TaskFiles.write(file, root); }
    static String hash(String text) { return dev.monocle.coordinator.TaskFiles.hash(text); }
    static String encode(JsonObject input) { return Base64.getEncoder().encodeToString(jsonBytes(checkedPackage(input), MAX_PACKAGE)); }
    private static byte[] jsonBytes(JsonObject input, int limit) { return dev.monocle.coordinator.TaskFiles.jsonBytes(input, limit); }
    static JsonObject packageFor(BotWorkflows library, String id) {
        JsonObject value = library.packageWorkflows(id), profiles = new JsonObject();
        for (JsonElement name : value.getAsJsonArray("profileNames")) profiles.add(name.getAsString(), BotProfiles.capture(name.getAsString()));
        if (!profiles.has("Current")) profiles.add("Current", BotProfiles.capture("Current"));
        value.add("profiles", profiles); value.remove("profileNames");
        return checkedPackage(value);
    }
    static JsonObject checkedPackage(JsonObject input) {
        jsonBytes(input, MAX_PACKAGE);
        if (integer(input, "version", 1, 1) != 1) throw new IllegalArgumentException("Unsupported workflow package");
        JsonObject programs = input.getAsJsonObject("programs"), highways = input.getAsJsonObject("highways"), profiles = input.getAsJsonObject("profiles");
        if (programs == null || programs.isEmpty() || programs.size() > 32 || highways == null || highways.size() > 32 || profiles == null || profiles.size() > 17 || !profiles.has("Current"))
            throw new IllegalArgumentException("Incomplete workflow package");
        for (var entry : programs.entrySet()) {
            identifier(entry.getKey()); JsonObject program = entry.getValue().getAsJsonObject();
            BotJobs.name(text(program, "name")); BotLua.validate(text(program, "script"));
        }
        if (!programs.has(text(input, "entry"))) throw new IllegalArgumentException("Missing entry workflow");
        for (var entry : highways.entrySet()) { if (!programs.has(entry.getKey())) throw new IllegalArgumentException("Orphan highway plan"); BotWorkflows.checkedPlan(entry.getValue().getAsJsonObject()); }
        for (var entry : profiles.entrySet()) {
            String name = entry.getKey();
            if (name.isBlank() || name.length() > 128 || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid bundled profile name");
            BotProfiles.validate(entry.getValue().getAsJsonObject());
        }
        if (input.has("geometry")) checkedGeometry(input.getAsJsonObject("geometry"));
        return input.deepCopy();
    }
    private static void checkedGeometry(JsonObject geometry) {
        // Use the same geometry rules as native jobs; task capture must fail before a package is sent.
        JsonObject job = geometry.deepCopy();
        job.addProperty("id", "00000000-0000-0000-0000-000000000000"); job.addProperty("name", "Task geometry");
        job.addProperty("length", 16); job.addProperty("progress", 0);
        BotJobs.checked(job);
    }
    static void identifier(String id) { if (!id.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid workflow identifier"); }
    static String text(JsonObject o, String key) { return BotJobs.text(o, key); }
    static int integer(JsonObject o, String key, int min, int max) {
        try {
            JsonPrimitive p = o.getAsJsonPrimitive(key);
            if (p == null || !p.isNumber()) throw new IllegalArgumentException("Missing integer: " + key);
            int value = p.getAsBigDecimal().intValueExact();
            if (value < min || value > max) throw new IllegalArgumentException("Out of range: " + key);
            return value;
        } catch (ArithmeticException e) { throw new IllegalArgumentException("Invalid integer: " + key, e); }
    }
    static JsonObject message(String type) { JsonObject m = new JsonObject(); m.addProperty("type", "task-" + type); return m; }

    /** Ordered transfer on the existing authenticated connection; no file paths or executable Java are accepted. */
    static final class Incoming {
        final UUID run; final String hash; final int count; final List<String> chunks = new ArrayList<>();
        final long started = System.nanoTime(); int next;
        Incoming(JsonObject m) {
            run = UUID.fromString(text(m, "run")); hash = text(m, "hash"); count = integer(m, "count", 1, (MAX_TRANSFER + CHUNK - 1) / CHUNK);
            if (!hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid workflow digest");
        }
        boolean matchesBegin(JsonObject m) {
            return run.toString().equals(text(m, "run")) && hash.equals(text(m, "hash")) && count == integer(m, "count", 1, (MAX_TRANSFER + CHUNK - 1) / CHUNK);
        }
        JsonObject add(JsonObject m) {
            int index = integer(m, "index", 0, count - 1);
            if (!run.toString().equals(text(m, "run")) || index > next || System.nanoTime() - started > 120_000_000_000L)
                throw new IllegalArgumentException("Out-of-order or expired workflow transfer");
            String chunk = text(m, "data");
            if (chunk.isEmpty() || chunk.length() > CHUNK || (index < count - 1 && chunk.length() != CHUNK)
                || (long) index * CHUNK + chunk.length() > MAX_TRANSFER || !chunk.matches("[A-Za-z0-9+/]*={0,2}"))
                throw new IllegalArgumentException("Invalid or oversized workflow chunk");
            if (index < next) {
                if (!chunks.get(index).equals(chunk)) throw new IllegalArgumentException("A repeated workflow chunk changed its content");
                return null; // A retry never installs the same execution twice.
            }
            chunks.add(chunk); next++;
            if (next < count) return null;
            String content = String.join("", chunks);
            if (!hash(content).equals(hash)) throw new IllegalArgumentException("Workflow package digest mismatch");
            byte[] bytes = Base64.getDecoder().decode(content);
            if (bytes.length > MAX_PACKAGE) throw new IllegalArgumentException("Oversized workflow package");
            try { return checkedPackage(JsonParser.parseString(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()).getAsJsonObject()); }
            catch (CharacterCodingException | StackOverflowError e) { throw new IllegalArgumentException("Invalid workflow package encoding", e); }
        }
    }
}
