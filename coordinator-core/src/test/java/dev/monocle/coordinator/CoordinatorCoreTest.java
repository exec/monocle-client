package dev.monocle.coordinator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.monocle.client.systems.bots.BotLua;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Standalone replay of production policies, without a Minecraft runtime or live worker. */
public final class CoordinatorCoreTest {
    private static final UUID WORKER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static void supplyRecovery() throws Exception {
        var path = java.nio.file.Files.createTempDirectory("monocle-supply-recovery-check-").resolve("records.json");
        var journal = new SupplyRecovery(path);
        JsonObject r = new JsonObject(); r.addProperty("id",UUID.randomUUID().toString()); r.addProperty("owner",WORKER.toString());
        r.addProperty("scope","server\nnether"); r.addProperty("x",0); r.addProperty("y",116); r.addProperty("z",-100);
        r.addProperty("stage","placing"); r.addProperty("baseline",2); r.addProperty("expected",1); r.add("stack",new JsonObject());
        journal.put(r);
        var restarted = new SupplyRecovery(path);
        assert restarted.pending(WORKER.toString(),"server\nnether").size()==1;
        assert restarted.pending(OTHER.toString(),"server\nnether").isEmpty();
        assert restarted.pending(WORKER.toString(),"other\nnether").isEmpty();
        assert SupplyRecovery.confirmed(true,true,true,false,2,2,1,true);
        for (boolean fresh : new boolean[]{true,false}) for(boolean loaded:new boolean[]{true,false})
            assert SupplyRecovery.confirmed(fresh,loaded,true,false,3,2,1,false)==(fresh && loaded);
        assert !SupplyRecovery.confirmed(true,true,true,true,3,2,1,false);
        assert !SupplyRecovery.confirmed(true,true,false,false,3,2,1,false);
        assert !SupplyRecovery.confirmed(true,true,true,false,2,2,1,false);
        JsonObject bad=r.deepCopy(); bad.addProperty("x",30_000_000); rejects(() -> restarted.put(bad));
        assert restarted.pending(WORKER.toString(),"server\nnether").size()==1;
        restarted.resolved(r.get("id").getAsString());
        assert new SupplyRecovery(path).pending(WORKER.toString(),"server\nnether").isEmpty();
        JsonObject invalid = new JsonObject(); invalid.addProperty("version",1); TaskFiles.write(path,invalid);
        rejects(() -> new SupplyRecovery(path));
        assert TaskFiles.read(path).equals(invalid) : "Malformed recovery data must never be overwritten as an empty ledger";
    }

    public static void main(String[] args) throws Exception {
        boolean enabled = false; assert enabled = true;
        if (!enabled) throw new IllegalStateException("Run with assertions enabled");
        supplyRecovery();
        for (String absent : List.of("net.minecraft.client.Minecraft", "net.fabricmc.loader.api.FabricLoader", "org.lwjgl.glfw.GLFW")) {
            try { Class.forName(absent, false, CoordinatorCoreTest.class.getClassLoader()); throw new AssertionError("Core acquired game dependency: " + absent); }
            catch (ClassNotFoundException expected) { }
        }
        List<String> expected = List.of("older", "urgent", "older", "urgent", "older", "Cancelled", "resume", "Cancelled", "Inspection required");
        assert replay(false).equals(expected);
        assert replay(true).equals(expected) : "Serialized checkpoints must preserve the same decision trace";
        for (int priority : new int[]{-1000, -1, 0, 1, 1000}) QueuePolicy.checkedPriority(priority);
        for (int priority : new int[]{Integer.MIN_VALUE, -1001, 1001, Integer.MAX_VALUE})
            rejects(() -> QueuePolicy.checkedPriority(priority));

        for (String state : List.of("Queued", "Sending", "Ready", "Running", "Suspending", "Suspended", "Inspection required", "Complete", "Failed", "Cancelled")) {
            JsonObject task = task("test", 1), run = run(task); run.addProperty("status", state);
            QueuePolicy.reconcileMissingRun(task, run);
            String next = state.equals("Queued") || state.equals("Sending") ? "Queued" : QueuePolicy.terminal(state) ? state : "Inspection required";
            assert run.get("status").getAsString().equals(next) : state;
            task = task("cancel", 1); task.addProperty("cancelled", true); run = run(task); run.addProperty("status", state);
            QueuePolicy.reconcileMissingRun(task, run);
            assert run.get("status").getAsString().equals(QueuePolicy.terminal(state) ? state : "Cancelled");
        }
        JsonObject issued = task("interrupted", 1); run(issued).addProperty("status", "Sending"); run(issued).addProperty("resumeSent", true);
        QueuePolicy.reconcileMissingRun(issued, run(issued));
        assert QueuePolicy.choose(List.of(issued), WORKER) == null : "An executed checkpoint cannot be replayed as an unsent package";
        for (String state : List.of("Complete", "Cancelled", "Failed")) {
            JsonObject ended = task("ended", 100); ended.addProperty("status", state);
            assert !QueuePolicy.pause(ended) && !QueuePolicy.cancel(ended);
            rejects(() -> QueuePolicy.resume(ended));
            assert QueuePolicy.summarizedStatus(ended).equals(state);
            assert QueuePolicy.choose(List.of(ended), WORKER) == null;
        }
        JsonObject perWorker = task("per-worker", 3);
        perWorker.getAsJsonObject("overrides").addProperty(WORKER.toString(), 0);
        assert QueuePolicy.priority(perWorker, WORKER) == 0 && QueuePolicy.priority(perWorker, OTHER) == 3;
        assert QueuePolicy.choose(List.of(perWorker), OTHER) == null;
        timing(); lua(); observations(); verification(); dispatch();
        System.out.println("Coordinator core checks passed without Minecraft/Fabric/LWJGL: queues, recovery, teleport, Lua, player observations and row verification authority.");
    }

    private static void dispatch() {
        JsonObject older = task("older", 0), urgent = task("urgent", 10);
        assert QueuePolicy.dispatch(null, older, WORKER).command().equals("transfer");
        run(older).addProperty("status", "Ready");
        assert QueuePolicy.dispatch(null, older, WORKER).command().equals("resume");
        run(older).addProperty("status", "Running");
        assert QueuePolicy.dispatch(older, urgent, WORKER).command().equals("pause");
        assert QueuePolicy.dispatch(older, older, WORKER).command().equals("external");
        run(older).addProperty("status", "Suspending");
        assert QueuePolicy.dispatch(older, urgent, WORKER).command().isEmpty() : "Cleanup completes before another task starts";
        run(older).addProperty("requestedStatus", "Suspended");
        assert QueuePolicy.dispatch(older, older, WORKER).command().equals("resume") : "Resume can retract suspension of the same native execution";
        assert QueuePolicy.dispatch(older, urgent, WORKER).command().isEmpty() : "A priority diversion still waits for cleanup";
        QueuePolicy.pause(older);
        assert QueuePolicy.dispatch(older, older, WORKER).command().isEmpty();
        QueuePolicy.resume(older);
        for (String terminal : List.of("Cancelled", "Failed", "Complete")) {
            run(older).addProperty("requestedStatus", terminal);
            assert QueuePolicy.dispatch(older, older, WORKER).command().isEmpty() : "Never retract terminal cleanup";
        }
        QueuePolicy.cancel(older);
        assert QueuePolicy.summarizedStatus(older).equals("Cancelled") : "Worker cleanup does not gate the host's cancellation decision";
        assert !dev.monocle.client.systems.bots.BotHistory.taskFinished(older, false) : "Unacknowledged cancellation must survive history deletion/retention";
        assert QueuePolicy.choose(List.of(older), WORKER) == null;
        rejects(() -> QueuePolicy.resume(older));
        assert QueuePolicy.dispatch(older, urgent, WORKER).command().equals("cancel");
        JsonObject report = new JsonObject(); report.addProperty("run", WORKER.toString()); report.addProperty("status", "Inspection required");
        run(urgent).addProperty("id", WORKER.toString());
        assert TaskWire.applyStatus(urgent, run(urgent), report, true);
        assert urgent.get("paused").getAsBoolean();
        QueuePolicy.resume(urgent); report.addProperty("status", "Running");
        assert TaskWire.applyStatus(urgent, run(urgent), report, true) && !run(urgent).has("resumeInspection");
        report.addProperty("status", "Complete"); TaskWire.applyStatus(urgent, run(urgent), report, true);
        report.addProperty("status", "Running"); assert !TaskWire.applyStatus(urgent, run(urgent), report, true);
    }

    private static void observations() {
        String scope = "example.org\nminecraft:the_nether";
        var site = new PlayerObservation.Position(0, 116, 100);
        JsonObject hello = new JsonObject(); hello.addProperty("id", WORKER.toString()); hello.addProperty("name", "Worker"); hello.addProperty("scope", scope);
        hello.addProperty("x", 0); hello.addProperty("y", 116); hello.addProperty("z", 101);
        var worker = PlayerObservation.fromHello(hello, 100);
        JsonObject diagnostics = new JsonObject(); diagnostics.addProperty("gate", "inventory-preparation");
        hello.add("diagnostics", diagnostics);
        assert PlayerObservation.fromHello(hello, 100).equals(worker) : "Debug data never participates in world/position authority";
        diagnostics.addProperty("oversized", "x".repeat(8192)); rejects(() -> PlayerObservation.fromHello(hello, 100));
        hello.add("diagnostics", new com.google.gson.JsonArray()); rejects(() -> PlayerObservation.fromHello(hello, 100));
        hello.remove("diagnostics");
        hello.addProperty("z", 999);
        assert worker.position().z() == 101 : "Snapshots cannot change when protocol JSON is mutated";
        assert worker.fresh(100) && worker.fresh(100 + PlayerObservation.MAX_AGE - 1);
        assert !worker.fresh(99) && !worker.fresh(100 + PlayerObservation.MAX_AGE);
        assert worker.nearby(scope, site, 1, false, 100);
        assert !worker.nearby("other\nminecraft:the_nether", site, 16, true, 100);
        assert !worker.nearby(scope, new PlayerObservation.Position(0, 117, 100), 16, true, 100);
        assert !worker.nearby(scope, site, 16, true, 100 + PlayerObservation.MAX_AGE);
        var diagonal = new PlayerObservation(OTHER, "Other", scope, new PlayerObservation.Position(16, 116, 116), 100);
        assert diagonal.nearby(scope, site, 16, true, 100) && !diagonal.nearby(scope, site, 16, false, 100);
        var unknown = new PlayerObservation(OTHER, "Other", "", null, 100);
        assert !unknown.inWorld("", 100) && !unknown.nearby(scope, site, 1000, true, 100);
        assert PlayerObservation.target("worker", null, List.of(worker), 100).equals(worker) : "External host needs no local player";
        assert PlayerObservation.target(WORKER.toString(), null, List.of(), 100) == null : "Only supplied crew members may be selected";
        assert PlayerObservation.target("Other", null, List.of(unknown), 100) == null;
        assert PlayerObservation.target("worker", null, List.of(worker), 100 + PlayerObservation.MAX_AGE) == null;
        var duplicate = new PlayerObservation(OTHER, "Worker", scope, site, 100);
        assert PlayerObservation.target("worker", null, List.of(worker, duplicate), 100) == null : "Ambiguous names fail closed";
        assert PlayerObservation.target(WORKER.toString(), null, List.of(worker, duplicate), 100).equals(worker);
        assert PlayerObservation.anchor(List.of(WORKER), OTHER, null, Map.of(WORKER, worker), scope, site, 100).equals(WORKER);
        assert PlayerObservation.anchor(List.of(WORKER), WORKER, null, Map.of(WORKER, worker), scope, site, 100) == null;
        assert PlayerObservation.anchor(List.of(WORKER), OTHER, null, Map.of(WORKER, worker), scope, site, 100 + PlayerObservation.MAX_AGE) == null;
        assert PlayerObservation.anchor(List.of(OTHER), WORKER, worker, Map.of(), scope, site, 100) == null : "Local host must be an active member";
        assert PlayerObservation.anchor(List.of(WORKER), OTHER, worker, Map.of(), scope, site, 100).equals(WORKER);
        assert PlayerObservation.anchor(List.of(WORKER), OTHER, worker, Map.of(), "different", site, 100) == null;
        hello.remove("z"); rejects(() -> PlayerObservation.fromHello(hello, 100));
        hello.addProperty("z", Double.NaN); rejects(() -> PlayerObservation.fromHello(hello, 100));
        hello.addProperty("z", "101"); rejects(() -> PlayerObservation.fromHello(hello, 100));
        hello.remove("x"); hello.remove("y"); hello.remove("z");
        assert PlayerObservation.fromHello(hello, 100).position() == null;
        // Monotonic clocks can have a negative origin or wrap; elapsed subtraction still works.
        var negative = new PlayerObservation(WORKER, "Worker", scope, site, -1000);
        assert negative.fresh(-999);
        var wrapped = new PlayerObservation(WORKER, "Worker", scope, site, Long.MAX_VALUE - 10);
        assert wrapped.fresh(Long.MIN_VALUE + 10);
    }

    private static void verification() {
        Map<Integer, Boolean> host = Map.of(10, true, 11, true, 12, true, 13, true, 14, false, 15, true);
        var confirmed = new RowVerification.Progress(10, true, 11, 31);
        assert RowVerification.mask(true, 11, 15, Map.of(), confirmed) == 0 : "Missing host observations cannot fall back to worker claims";
        assert RowVerification.mask(true, 11, 15, host, new RowVerification.Progress(10, false, null, 0)) == 23;
        assert RowVerification.mask(false, 11, 15, Map.of(), confirmed) == 31;
        assert RowVerification.mask(false, 11, 15, host, null) == 0;
        assert RowVerification.mask(false, 11, 15, host, new RowVerification.Progress(10, true, 10, 31)) == 0;
        assert RowVerification.checkpoint(true, 0, 8, 10, host, List.of()) == 10;
        assert RowVerification.checkpoint(true, 0, 10, 10, Map.of(), List.of(confirmed)) == 9;
        assert RowVerification.checkpoint(false, 0, 10, 10, Map.of(), List.of(confirmed)) == 10;
        assert RowVerification.checkpoint(false, 0, 10, 10, host, List.of(new RowVerification.Progress(11, true, 12, 31))) == 9;
        assert RowVerification.checkpoint(false, 0, 10, 8, Map.of(), List.of(new RowVerification.Progress(8, false, 9, 31))) == 7;
        assert RowVerification.checkpoint(true, 5, 5, 5, Map.of(), List.of()) == 5;
        for (int bits = 0; bits < 32; bits++) for (int base = 11; base <= 17; base++) {
            var worker = new RowVerification.Progress(base - 1, true, base, bits);
            int expected = 0;
            for (int offset = 0; offset < 5 && base + offset <= 15; offset++) if ((bits & 1 << offset) != 0) expected |= 1 << offset;
            assert RowVerification.mask(false, base, 15, host, worker) == expected : "Preserve the rolling window without an all-rows barrier";
            for (int row = base - 1; row <= base + 5; row++)
                assert RowVerification.verifiedRow(base, expected, row) == (row >= base && row < base + 5 && row <= 15 && (bits & 1 << (row - base)) != 0);
        }
        JsonObject report = new JsonObject(); report.addProperty("currentRow", 10); report.addProperty("verifiedBase", 11); report.addProperty("verifiedMask", 31);
        assert RowVerification.Progress.fromReport(report).equals(RowVerification.Progress.fromReport(checkpoint(report)));
        report.addProperty("verifiedMask", 32); rejects(() -> RowVerification.Progress.fromReport(report));
        report.addProperty("verifiedMask", 1.5); rejectsArithmetic(() -> RowVerification.Progress.fromReport(report));
        assert !RowVerification.verifiedRow(Integer.MIN_VALUE, 31, Integer.MAX_VALUE);
    }

    private static void rejectsArithmetic(Runnable action) {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (ArithmeticException expected) { }
    }

    private static List<String> replay(boolean reload) {
        List<String> trace = new ArrayList<>();
        JsonObject older = task("older", 3), newer = task("newer", 3), urgent = task("urgent", 10);
        trace.add(chosen(List.of(older, newer)));
        trace.add(chosen(List.of(older, urgent)));
        assert QueuePolicy.preempts(older, urgent, WORKER);
        assert !QueuePolicy.preempts(older, newer, WORKER) : "Tied jobs do not interrupt running work";
        assert QueuePolicy.pause(urgent);
        if (reload) urgent = checkpoint(urgent);
        trace.add(chosen(List.of(older, urgent)));
        QueuePolicy.resume(urgent); trace.add(chosen(List.of(older, urgent)));
        assert QueuePolicy.cancel(urgent);
        if (reload) urgent = checkpoint(urgent);
        trace.add(chosen(List.of(older, urgent)));
        JsonObject cancelled = urgent;
        rejects(() -> QueuePolicy.resume(cancelled));
        trace.add(QueuePolicy.summarizedStatus(urgent));
        run(urgent).addProperty("status", "Inspection required"); run(urgent).addProperty("requestedStatus", "Cancelled");
        trace.add(QueuePolicy.cancellationCommand(run(urgent)));
        QueuePolicy.reconcileMissingRun(urgent, run(urgent)); trace.add(QueuePolicy.summarizedStatus(urgent));
        run(older).addProperty("status", "Running"); run(older).addProperty("resumeSent", true);
        if (reload) older = checkpoint(older);
        QueuePolicy.reconcileMissingRun(older, run(older)); trace.add(QueuePolicy.summarizedStatus(older));
        return trace;
    }
    private static void timing() {
        JsonObject tpa = new JsonObject(); tpa.addProperty("warmup", 60);
        assert !QueuePolicy.teleportWarmupReady(tpa, 100_000);
        tpa.addProperty("acknowledgedAt", 10_000);
        assert !QueuePolicy.teleportWarmupReady(tpa, 9_999) && !QueuePolicy.teleportWarmupReady(tpa, 12_999);
        assert QueuePolicy.teleportWarmupReady(tpa, 13_000);
        for (String flag : List.of("accepted", "recovered")) {
            tpa.addProperty(flag, true); assert !QueuePolicy.teleportWarmupReady(tpa, 13_000); tpa.remove(flag);
        }
        tpa.addProperty("warmup", 1.5); rejects(() -> QueuePolicy.teleportWarmupReady(tpa, 13_000));
        tpa.addProperty("warmup", 72_001); rejects(() -> QueuePolicy.teleportWarmupReady(tpa, 13_000));
        assert QueuePolicy.validTeleportTtl(1000, 31_000) && !QueuePolicy.validTeleportTtl(1000, 31_001);
        assert !QueuePolicy.validTeleportTtl(1000, 999) && !QueuePolicy.validTeleportTtl(Long.MIN_VALUE, Long.MAX_VALUE);
        assert QueuePolicy.sameTeleportServer("EXAMPLE.org\nminecraft:overworld", "example.org\nminecraft:the_nether");
        assert !QueuePolicy.sameTeleportServer("a\nminecraft:overworld", "b\nminecraft:overworld");
        assert !QueuePolicy.sameTeleportServer("", "") && !QueuePolicy.sameTeleportServer("example.org", "example.org");
        JsonObject execution = new JsonObject(), action = new JsonObject(); action.addProperty("type", "Tpa"); execution.add("action", action);
        execution.addProperty("token", WORKER.toString()); execution.addProperty("status", "Running");
        tpa.addProperty("token", WORKER.toString()); tpa.addProperty("deadline", 20_000);
        assert QueuePolicy.teleportPending(tpa, execution, 19_999) && !QueuePolicy.teleportPending(tpa, execution, 20_000);
        execution.addProperty("token", OTHER.toString()); assert !QueuePolicy.teleportPending(tpa, execution, 19_999);
    }
    private static void lua() {
        String source = "return function(ctx) assert(os==nil and io==nil and luajava==nil and debug==nil); if not ctx.state.started then ctx.state.started=true; return bot.wait(20) end; return bot.done() end";
        JsonObject original = new JsonObject();
        BotLua.Decision first = BotLua.next(source, original, null, null, null);
        assert original.isEmpty() && first.action().get("type").getAsString().equals("Wait");
        BotLua.Decision resumed = BotLua.next(source, checkpoint(first.state()), null, null, null);
        assert resumed.action().get("type").getAsString().equals("Done");
        assert first.equals(BotLua.next(source, new JsonObject(), null, null, null));
        rejects(() -> BotLua.next("return function(ctx) while true do end end", new JsonObject(), null, null, null));
        rejects(() -> BotLua.next("return function(ctx) ctx.state.self=ctx.state; return bot.done() end", new JsonObject(), null, null, null));
    }
    private static JsonObject task(String name, int priority) {
        JsonObject task = new JsonObject(), runs = new JsonObject(), run = new JsonObject();
        run.addProperty("status", "Queued"); runs.add(WORKER.toString(), run);
        task.addProperty("name", name); task.addProperty("priority", priority); task.addProperty("status", "Queued");
        task.add("runs", runs); task.add("overrides", new JsonObject()); return task;
    }
    private static JsonObject run(JsonObject task) { return task.getAsJsonObject("runs").getAsJsonObject(WORKER.toString()); }
    private static String chosen(List<JsonObject> tasks) { return QueuePolicy.choose(tasks, WORKER).get("name").getAsString(); }
    private static JsonObject checkpoint(JsonObject value) { return JsonParser.parseString(value.toString()).getAsJsonObject(); }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (IllegalArgumentException | IllegalStateException expected) { }
    }
}
