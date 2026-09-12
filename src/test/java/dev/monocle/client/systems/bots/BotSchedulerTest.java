package dev.monocle.client.systems.bots;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;

/** Queue policy only: no client, transport, files or Minecraft world required. */
final class BotSchedulerTest {
    static void run() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        for (int value : new int[] {-1000, -1, 0, 1, 1000}) BotScheduler.checkedPriority(value);
        for (int value : new int[] {Integer.MIN_VALUE, -1001, 1001, Integer.MAX_VALUE}) {
            try { BotScheduler.checkedPriority(value); throw new AssertionError("Accepted priority " + value); }
            catch (IllegalArgumentException expected) { }
        }

        JsonObject older = task(first, 3), newer = task(first, 3), urgent = task(first, 10);
        assert BotScheduler.choose(List.of(older, newer), first) == older : "Equal priorities preserve queue insertion order";
        assert BotScheduler.choose(List.of(older, urgent, newer), first) == urgent : "Greater priority wins";
        older.getAsJsonObject("overrides").addProperty(first.toString(), 11);
        assert BotScheduler.priority(older, first) == 11;
        assert BotScheduler.priority(older, second) == 3 : "Overrides must not leak to another worker";
        assert BotScheduler.choose(List.of(older, urgent), first) == older;
        older.getAsJsonObject("overrides").addProperty(first.toString(), 0);
        assert BotScheduler.priority(older, first) == 0 : "Zero is a real override, not absence";
        assert BotScheduler.choose(List.of(older, urgent), first) == urgent;
        assert BotScheduler.choose(List.of(older, urgent), second) == null : "Only explicitly targeted workers can run a task";

        for (String key : List.of("paused", "cancelled")) {
            JsonObject blocked = task(first, 100);
            blocked.addProperty(key, true);
            assert BotScheduler.choose(List.of(blocked, newer), first) == newer : key + " jobs cannot preempt";
        }
        for (String status : List.of("Complete", "Cancelled", "Failed")) {
            assert BotScheduler.terminal(status);
            JsonObject finishedTask = task(first, 100), finishedRun = task(first, 100);
            finishedTask.addProperty("status", status);
            assert !BotHistory.taskFinished(finishedTask, false) : "A terminal label alone cannot retire unfinished worker runs";
            JsonObject archived = finishedTask.deepCopy(); archived.getAsJsonObject("runs").getAsJsonObject(first.toString()).addProperty("status", status);
            assert BotHistory.taskFinished(archived, false);
            assert !BotHistory.taskFinished(archived, true) : "Return-to-crew reservations outlive nominal task completion";
            for (String pending : List.of("highway", "nativeDefinition", "hostOriginal")) {
                JsonObject cleanup = archived.deepCopy(); cleanup.addProperty(pending, "retained");
                assert !BotHistory.taskFinished(cleanup, false) : "Never expire native cleanup or profile restoration";
            }
            finishedRun.getAsJsonObject("runs").getAsJsonObject(first.toString()).addProperty("status", status);
            assert BotScheduler.choose(List.of(finishedTask, finishedRun, newer), first) == newer
                : "Neither finished tasks nor finished per-worker runs restart";
        }
        for (String status : List.of("Queued", "Sending", "Ready", "Running", "Suspending", "Suspended", "Inspection required", "Paused", "Cancelling"))
            assert !BotScheduler.terminal(status) : "Unfinished recovery must retain ownership: " + status;

        JsonObject multi = task(first, 10);
        multi.getAsJsonObject("runs").add(second.toString(), task(second, 1).getAsJsonObject("runs").get(second.toString()));
        multi.getAsJsonObject("runs").getAsJsonObject(first.toString()).addProperty("status", "Complete");
        assert BotScheduler.choose(List.of(multi, newer), first) == newer;
        assert BotScheduler.choose(List.of(multi), second) == multi : "One worker completing does not retire another worker's run";
        assert BotScheduler.choose(List.of(), first) == null;

        assert BotScheduler.sameTeleportServer("example.org:25565\nminecraft:overworld", "EXAMPLE.ORG:25565\nminecraft:the_nether")
            : "Cross-dimension TPA is allowed on the same server";
        assert !BotScheduler.sameTeleportServer("one.test\nminecraft:overworld", "two.test\nminecraft:overworld");
        assert !BotScheduler.sameTeleportServer("one.test:25565\nminecraft:overworld", "one.test:25566\nminecraft:overworld");
        assert !BotScheduler.sameTeleportServer("", "");
        assert !BotScheduler.sameTeleportServer("one.test", "one.test");
        assert !BotScheduler.sameTeleportServer("one.test\n", "one.test\n");
        assert BotScheduler.validTeleportTtl(1000, 1000);
        assert BotScheduler.validTeleportTtl(1000, 31_000);
        assert !BotScheduler.validTeleportTtl(1000, 999);
        assert !BotScheduler.validTeleportTtl(1000, 31_001);
        assert !BotScheduler.validTeleportTtl(Long.MIN_VALUE, Long.MAX_VALUE) : "TTL subtraction cannot overflow into a valid interval";

        JsonObject teleport = new JsonObject(); teleport.addProperty("warmup", 60);
        assert !BotScheduler.teleportWarmupReady(teleport, 100_000) : "Time before the command acknowledgment never counts as warmup";
        teleport.addProperty("acknowledgedAt", 10_000);
        assert !BotScheduler.teleportWarmupReady(teleport, 9_999);
        assert !BotScheduler.teleportWarmupReady(teleport, 12_999);
        assert BotScheduler.teleportWarmupReady(teleport, 13_000);
        teleport.addProperty("accepted", true);
        assert !BotScheduler.teleportWarmupReady(teleport, 13_000) : "Acceptance intent is at-most-once";
        teleport.remove("accepted"); teleport.addProperty("recovered", true);
        assert !BotScheduler.teleportWarmupReady(teleport, 13_000) : "Host restart cannot silently accept an old request";

        JsonObject execution = new JsonObject(), action = new JsonObject();
        action.addProperty("type", "Tpa"); execution.add("action", action);
        execution.addProperty("token", first.toString()); execution.addProperty("status", "Running");
        teleport.addProperty("token", first.toString()); teleport.addProperty("deadline", 20_000);
        assert BotScheduler.teleportPending(teleport, execution, 19_999);
        execution.addProperty("status", "Suspending");
        assert BotScheduler.teleportPending(teleport, execution, 19_999) : "An outstanding request retains its anchor while safely settling";
        assert !BotScheduler.teleportPending(teleport, execution, 20_000);
        execution.addProperty("token", second.toString());
        assert !BotScheduler.teleportPending(teleport, execution, 19_999) : "A new action token releases the old target reservation";
        execution.addProperty("token", first.toString()); execution.addProperty("status", "Complete");
        assert !BotScheduler.teleportPending(teleport, execution, 19_999);
        execution.addProperty("status", "Running"); action.addProperty("type", "Travel");
        assert !BotScheduler.teleportPending(teleport, execution, 19_999);
        lateJoins();
        cancellations(first);
    }

    private static void cancellations(UUID worker) {
        for (String status : List.of("Queued", "Sending", "Ready", "Running", "Suspending", "Suspended", "Inspection required")) {
            JsonObject cancelled = task(worker, 0), r = cancelled.getAsJsonObject("runs").getAsJsonObject(worker.toString());
            cancelled.addProperty("cancelled", true); r.addProperty("status", status);
            BotScheduler.reconcileMissingRun(cancelled, r);
            assert r.get("status").getAsString().equals("Cancelled") : "An absent worker checkpoint cannot retain cancellation forever: " + status;
            assert BotScheduler.choose(List.of(cancelled), worker) == null;

            JsonObject active = task(worker, 0), missing = active.getAsJsonObject("runs").getAsJsonObject(worker.toString());
            missing.addProperty("status", status); BotScheduler.reconcileMissingRun(active, missing);
            if (List.of("Queued", "Sending").contains(status)) assert missing.get("status").getAsString().equals("Queued");
            else {
                assert missing.get("status").getAsString().equals("Inspection required");
                assert BotScheduler.choose(List.of(active), worker) == null : "Missing executed journals must never replay side effects";
            }
        }
        JsonObject interrupted = task(worker, 0), missing = interrupted.getAsJsonObject("runs").getAsJsonObject(worker.toString());
        missing.addProperty("status", "Sending"); missing.addProperty("resumeSent", true);
        BotScheduler.reconcileMissingRun(interrupted, missing);
        assert BotScheduler.choose(List.of(interrupted), worker) == null : "Persisted resume intent prevents replay even if the previous status acknowledgment was lost";
        JsonObject run = new JsonObject(); run.addProperty("status", "Inspection required");
        assert BotScheduler.cancellationCommand(run).equals("cancel");
        run.addProperty("requestedStatus", "Cancelled");
        assert BotScheduler.cancellationCommand(run).equals("resume") : "A restart reconciles saved cancellation rather than endlessly requesting it again";
        run.addProperty("status", "Suspending");
        assert BotScheduler.cancellationCommand(run).equals("cancel") : "Cleanup already running must not be resumed as gameplay";
    }

    private static void lateJoins() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        JsonObject task = task(first, 0), nativeRun = task.getAsJsonObject("runs").getAsJsonObject(first.toString());
        JsonObject packaged = JsonParser.parseString("{entry:'captured-highway',highways:{'captured-highway':{}},profiles:{Current:{}}}").getAsJsonObject();
        task.add("package", packaged); task.addProperty("highway", UUID.randomUUID().toString());
        nativeRun.addProperty("status", "Running"); nativeRun.addProperty("token", UUID.randomUUID().toString());
        nativeRun.add("action", JsonParser.parseString("{type:'Highway',workflow:'captured-highway'}"));
        JsonObject tokens = new JsonObject(); tokens.add(first.toString(), nativeRun.get("token")); task.add("highwayTokens", tokens);
        JsonObject original = task.deepCopy(), joining = BotScheduler.lateJoinRun(task);
        assert task.equals(original) : "Preparing a join must not mutate the captured package or existing workers";
        assert !joining.get("id").getAsString().equals(BotScheduler.lateJoinRun(task).get("id").getAsString()) : "Every worker gets its own execution identity";
        assert joining.get("joinJob").equals(task.get("highway")) && joining.get("joinAction").equals(nativeRun.get("action"));
        assert BotScheduler.pendingLateJoin(joining) : "Completion must retain a join while its package has not arrived";
        assert !BotScheduler.sameHighwayAction(task, second, joining) : "Two absent tokens are never a matching completion token";
        joining.add("action", joining.get("joinAction").deepCopy()); joining.addProperty("token", UUID.randomUUID().toString()); joining.addProperty("status", "Running");
        assert !BotScheduler.sameHighwayAction(task, second, joining) : "Prepared profile/action still needs host token registration";
        tokens.add(second.toString(), joining.get("token"));
        assert BotScheduler.sameHighwayAction(task, second, joining);
        joining.addProperty("joinAdmitted", true);
        assert !BotScheduler.pendingLateJoin(joining);
        joining.addProperty("token", UUID.randomUUID().toString());
        assert !BotScheduler.sameHighwayAction(task, second, joining) : "A late result cannot complete a newer action";
        joining.remove("joinAdmitted"); joining.addProperty("status", "Failed");
        assert !BotScheduler.pendingLateJoin(joining) : "A failed join cannot retain the source forever";
        packaged.addProperty("entry", "custom-lua");
        try { BotScheduler.lateJoinRun(task); throw new AssertionError("Late join must not replay arbitrary Lua side effects"); }
        catch (IllegalStateException expected) { assert expected.getMessage().contains("Lua"); }
    }

    private static JsonObject task(UUID worker, int priority) {
        JsonObject task = new JsonObject(), runs = new JsonObject(), run = new JsonObject();
        task.addProperty("priority", priority); task.addProperty("status", "Queued");
        task.add("overrides", new JsonObject());
        run.addProperty("status", "Queued"); runs.add(worker.toString(), run); task.add("runs", runs);
        return task;
    }
}
