package dev.monocle.client.systems.bots;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.util.*;

/** Exercises the real catalog without starting Minecraft or touching a user's saved work. */
final class BotJobsTest {
    static void run() throws Exception {
        completionReconciliation();
        var directory = Files.createTempDirectory("monocle-job-check-");
        try {
            var path = directory.resolve("bot-jobs.json");
            BotJobs jobs = new BotJobs(path);
            JsonObject original = definition(); UUID id = UUID.fromString(original.get("id").getAsString());
            assert dev.monocle.client.systems.modules.misc.swarm.SwarmCrew.workSharing(original.getAsJsonObject("layout"))
                == dev.monocle.client.systems.modules.misc.swarm.SwarmCrew.WorkSharing.Lanes;
            original.getAsJsonObject("layout").addProperty("workSharing", "BreakOrder");
            JsonObject badMode = original.deepCopy(); badMode.getAsJsonObject("layout").addProperty("workSharing", "garbage");
            try { BotJobs.checked(badMode); throw new AssertionError("Unknown sharing mode accepted"); }
            catch (IllegalArgumentException expected) { }
            jobs.put(original);
            original.addProperty("name", "Caller changed its copy");
            assert jobs.records.get(id).get("name").getAsString().equals("North road");
            assert BotJobs.unfinished(jobs.records.get(id));
            JsonObject active = jobs.records.get(id).deepCopy();
            active.addProperty("crew", "stable-crew-id"); active.addProperty("status", "Running");
            active.addProperty("execution", UUID.randomUUID().toString()); active.addProperty("progress", 32);
            jobs.put(active);
            BotJobs restored = new BotJobs(path); restored.load();
            assert restored.records.get(id).equals(active) : "A restart retains work, progress and its crew claim";
            assert restored.records.get(id).getAsJsonObject("layout").get("workSharing").getAsString().equals("BreakOrder")
                : "The host's chosen sharing mode survives catalog persistence and crew assignment";
            JsonObject released = active.deepCopy(); released.addProperty("crew", ""); released.addProperty("status", "Unassigned"); released.remove("execution");
            restored.put(released);
            assert restored.records.get(id).get("progress").getAsInt() == 32 : "Releasing a crew must not discard progress";
            JsonObject next = restored.records.get(id).deepCopy(); next.addProperty("crew", "second-crew-id");
            next.addProperty("execution", UUID.randomUUID().toString()); next.addProperty("status", "Positioning"); restored.put(next);
            assert !next.get("execution").equals(active.get("execution")) : "Late cancellation targets one execution, not every run of this job";
            next.addProperty("status", "Cancelled"); restored.put(next); assert !BotJobs.unfinished(restored.records.get(id));
            next.addProperty("status", "Complete"); next.addProperty("progress", 128); restored.put(next); assert !BotJobs.unfinished(restored.records.get(id));
            assert restored.records.get(id).get("layout").equals(active.get("layout"));
            assert !BotHistory.nativeFinished(restored.records.get(id)) : "A completed road with a crew claim is still recovery, not history";
            next.addProperty("crew", ""); restored.put(next);
            long finished = BotHistory.finishedAt(restored.records.get(id));
            assert finished > 0;
            BotJobs history = new BotJobs(path); history.load();
            assert BotHistory.finishedAt(history.records.get(id)) == finished : "Restart must not renew history retention";
            assert !BotHistory.expired(finished, finished + 30L * 86_400_000 - 1, 30);
            assert BotHistory.expired(finished, finished + 30L * 86_400_000, 30);
            assert BotHistory.expired(finished, finished, 0) : "Zero retention removes safely finished history";
            assert !BotHistory.expired(finished, finished - 1, 0) : "Clock rollback does not expire future-dated history";
            assert !BotHistory.expired(0, Long.MAX_VALUE, 30) : "Unknown completion times are not guessed";
            for (String state : List.of("Paused", "Inspection required", "Running", "Releasing")) {
                JsonObject unfinished = next.deepCopy(); unfinished.addProperty("status", state);
                assert !BotHistory.nativeFinished(unfinished);
                assert !BotJobs.checked(unfinished).has("finishedAt") : "Reopening work removes its old retention date";
            }
            JsonObject legacy = next.deepCopy(); legacy.remove("finishedAt");
            assert BotHistory.stamp(legacy, true, 1234) && !BotHistory.stamp(legacy, true, 5678);
            assert BotHistory.finishedAt(legacy) == 1234;
            JsonObject task = new JsonObject(), packaged = new JsonObject(); packaged.add("geometry", next.deepCopy()); task.add("package", packaged);
            assert BotHistory.nativeId(task).equals(id) : "Legacy native tasks retain their original catalog ID inside captured geometry";
            UUID newer = UUID.randomUUID(); task.addProperty("historyJobId", newer.toString());
            assert BotHistory.nativeId(task).equals(newer) : "The executed native job ID takes precedence over captured geometry";
            task.remove("package"); assert BotHistory.nativeId(task).equals(newer) : "History link outlives native execution cleanup";

            for (String name : List.of("", " ", "x".repeat(49), "bad\nname")) bad(() -> BotJobs.name(name));
            for (String key : List.of("id", "scope", "name")) {
                JsonObject invalid = definition(); invalid.addProperty(key, ""); bad(() -> BotJobs.checked(invalid));
            }
            for (int progress : new int[]{-1, 129}) { JsonObject invalid = definition(); invalid.addProperty("progress", progress); bad(() -> BotJobs.checked(invalid)); }
            JsonObject geometry = definition(); geometry.getAsJsonObject("layout").addProperty("width", 6); bad(() -> BotJobs.checked(geometry));
            JsonObject unknown = definition(); unknown.addProperty("type", "Workflow"); bad(() -> BotJobs.checked(unknown));
            JsonObject mismatch = definition(); mismatch.add("workflow", BotWorkflows.legacyPlan("ClearTunnel")); bad(() -> BotJobs.checked(mismatch));
            JsonObject forged = definition(); forged.getAsJsonObject("workflow").addProperty("duty", "Pave");
            assert BotJobs.checked(forged).getAsJsonObject("workflow").get("duty").getAsString().equals("Build");
            JsonObject legacyRepair = definition(); legacyRepair.remove("workflow"); legacyRepair.getAsJsonObject("layout").addProperty("operation", "Repair");
            assert BotJobs.checked(legacyRepair).getAsJsonObject("workflow").get("duty").getAsString().equals("Pave");
            JsonObject status = definition(); status.addProperty("status", "typo"); bad(() -> BotJobs.checked(status));
            for (String key : List.of("x", "y", "z", "length", "progress")) {
                JsonObject overflow = definition(); overflow.addProperty(key, Long.MIN_VALUE); bad(() -> BotJobs.checked(overflow));
                JsonObject fractional = definition(); fractional.addProperty(key, 1.5); bad(() -> BotJobs.checked(fractional));
            }

            String saved = Files.readString(path);
            JsonObject invalid = definition(); invalid.addProperty("length", 0); bad(() -> restored.put(invalid));
            assert Files.readString(path).equals(saved) : "Invalid edits cannot overwrite persisted jobs";
            // A malformed catalog blocks writes rather than silently replacing all prior work.
            Files.writeString(path, "{invalid-json");
            BotJobs broken = new BotJobs(path);
            bad(() -> broken.load()); bad(() -> broken.put(definition()));
            assert Files.readString(path).equals("{invalid-json");
            Files.writeString(path, saved);
            BotJobs reloaded = new BotJobs(path); reloaded.load(); reloaded.remove(id);
            assert reloaded.records.isEmpty();
            BotJobs empty = new BotJobs(path); empty.load(); assert empty.records.isEmpty();
        } finally {
            try (var files = Files.walk(directory)) { for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }
    private static JsonObject definition() {
        JsonObject job = new JsonObject(); job.addProperty("id", UUID.randomUUID().toString()); job.addProperty("name", "North road");
        job.addProperty("scope", "test-server\nminecraft:the_nether"); job.addProperty("x", 0); job.addProperty("y", 116); job.addProperty("z", 1000);
        job.addProperty("length", 128); job.addProperty("progress", 0);
        JsonObject layout = new JsonObject(); layout.addProperty("dx", 0); layout.addProperty("dz", 1); layout.addProperty("width", 5); layout.addProperty("height", 3);
        layout.addProperty("heading", "South"); layout.addProperty("operation", "Build"); layout.addProperty("floor", "Replace");
        layout.addProperty("railings", true); layout.addProperty("supports", false); layout.addProperty("above", true); layout.addProperty("blocks", "minecraft:obsidian");
        job.add("layout", layout); return BotJobs.checked(job);
    }
    private static void completionReconciliation() {
        for (String status : List.of("Running", "Rebalancing", "Inspection required", "Complete", "Releasing", "Cancelled")) {
            JsonObject finished = definition();
            finished.addProperty("length", 512); finished.addProperty("progress", 512);
            finished.addProperty("crew", "crew-id"); finished.addProperty("execution", UUID.randomUUID().toString());
            finished.addProperty("status", status);
            for (boolean nativeBusy : List.of(false, true)) for (boolean workflowBusy : List.of(false, true)) {
                if (!nativeBusy && !workflowBusy) continue;
                JsonObject blocked = finished.deepCopy();
                assert !BotJobs.settleReleased(blocked, true, nativeBusy, workflowBusy, false);
                assert blocked.equals(finished) : "Full progress cannot bypass a live journal, receipts or workflow cleanup";
            }
            JsonObject unknown = finished.deepCopy();
            BotJobs.settleReleased(unknown, false, false, false, false);
            assert BotJobs.text(unknown, "status").equals("Inspection required") && !BotJobs.text(unknown, "crew").isEmpty();
            assert BotJobs.settleReleased(finished, true, false, false, false);
            assert BotJobs.text(finished, "status").equals(status.equals("Cancelled") ? "Cancelled" : "Complete");
            assert !finished.has("execution") && BotJobs.text(finished, "crew").isEmpty();
            assert BotHistory.nativeFinished(finished) : "Released finished roads leave Jobs and appear in unified History";
            assert !BotJobs.settleReleased(finished, true, false, false, false) : "Repeated catalog polling is idempotent";
        }
        JsonObject partial = definition(); partial.addProperty("progress", 511); partial.addProperty("length", 512);
        partial.addProperty("crew", "crew-id"); partial.addProperty("status", "Inspection required");
        assert !BotJobs.settleReleased(partial, true, false, false, false);
        partial.addProperty("progress", 512);
        assert !BotJobs.settleReleased(partial, true, false, false, true) : "Archived supplies prevent automatic orphan repair";
        partial.addProperty("status", "Complete");
        assert BotJobs.settleReleased(partial, true, false, false, true);
        assert BotJobs.text(partial, "status").equals("Inspection required") && !BotJobs.text(partial, "crew").isEmpty();
    }
    private static void bad(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid persisted job must be refused"); }
        catch (IllegalArgumentException | IllegalStateException expected) { }
    }
}
