package dev.monocle.client.systems.bots;

import com.google.gson.*;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.client.utils.misc.HorizontalDirection;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Durable work, independent of a crew or an individual execution's cancellation receipts. */
final class BotJobs {
    private static final Gson JSON = new Gson();
    final Map<UUID, JsonObject> records = new LinkedHashMap<>();
    private final Path file;
    private boolean loaded;
    private String failure;

    BotJobs(Path file) { this.file = file; }

    void load() {
        if (failure != null) throw new IllegalStateException(failure);
        if (loaded) return;
        try {
            Map<UUID, JsonObject> restored = new LinkedHashMap<>();
            boolean migrated = false;
            if (Files.exists(file)) {
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (root.get("version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported job catalog version");
                for (JsonElement element : root.getAsJsonArray("jobs")) {
                    JsonObject job = checked(element.getAsJsonObject());
                    migrated |= !job.equals(element);
                    if (restored.put(UUID.fromString(text(job, "id")), job) != null) throw new IllegalArgumentException("Duplicate job ID");
                }
            }
            records.putAll(restored);
            loaded = true;
            if (migrated) save();
        } catch (IOException | RuntimeException e) {
            failure = "Cannot read Workers jobs at " + file + ": " + e.getMessage() + ". The original file has been left untouched.";
            throw new IllegalStateException(failure, e);
        }
    }

    void save() {
        load();
        JsonObject root = new JsonObject(); root.addProperty("version", 1);
        JsonArray jobs = new JsonArray();
        records.values().forEach(job -> jobs.add(checked(job)));
        root.add("jobs", jobs);
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "bot-jobs-", ".tmp");
            Files.writeString(temporary, JSON.toJson(root));
            // Refuse an unsafe replacement if this filesystem cannot atomically preserve the previous catalog.
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new IllegalStateException("Could not save Workers jobs; assignment changes were stopped: " + e.getMessage(), e); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }

    void put(JsonObject job) {
        load();
        job = checked(job);
        UUID id = UUID.fromString(text(job, "id"));
        JsonObject previous = records.put(id, job);
        try { save(); }
        catch (RuntimeException e) { if (previous == null) records.remove(id); else records.put(id, previous); throw e; }
    }
    void remove(UUID id) {
        load();
        JsonObject previous = records.remove(id);
        try { save(); } catch (RuntimeException e) { if (previous != null) records.put(id, previous); throw e; }
    }

    static String text(JsonObject object, String key) { return object.has(key) ? object.get(key).getAsString() : ""; }
    private static int integer(JsonObject object, String key) {
        try {
            JsonPrimitive value = object.getAsJsonPrimitive(key);
            if (value == null || !value.isNumber()) throw new IllegalArgumentException("Missing integer " + key);
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException e) { throw new IllegalArgumentException("Invalid integer " + key, e); }
    }
    static String name(String name) {
        name = name.strip();
        if (name.isEmpty() || name.length() > 48 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Choose a name of 1–48 printable characters.");
        return name;
    }
    static JsonObject checked(JsonObject input) { return dev.monocle.coordinator.HighwayJobs.checked(input); }

    static boolean unfinished(JsonObject job) {
        return !Set.of("Complete", "Cancelled").contains(text(job, "status")) && job.get("progress").getAsInt() < job.get("length").getAsInt();
    }

    /** Reconcile only after native release, worker receipts and workflow cleanup have settled. */
    static boolean settleReleased(JsonObject job, boolean knownCrew, boolean nativeBusy, boolean workflowBusy, boolean supplies) {
        if (nativeBusy || workflowBusy || text(job, "crew").isEmpty()) return false;
        String previous = text(job, "status");
        boolean ended = Set.of("Releasing", "Cancelled", "Complete").contains(previous);
        boolean finishedRoad = job.get("progress").getAsInt() == job.get("length").getAsInt();
        // Older builds overwrote Complete with Rebalancing during release. Full verified progress
        // can repair that orphan, but neither a live recovery journal nor abandoned supplies can.
        if (!knownCrew || supplies && !Set.of("Releasing", "Cancelled").contains(previous) || !ended && !finishedRoad) {
            job.addProperty("status", "Inspection required");
            return !previous.equals("Inspection required");
        }
        job.addProperty("crew", ""); job.remove("execution");
        if (!previous.equals("Cancelled")) job.addProperty("status", finishedRoad ? "Complete" : previous.equals("Releasing") ? "Unassigned" : previous);
        return true;
    }
}
