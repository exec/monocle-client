package dev.monocle.client.systems.bots;

import com.google.gson.*;
import java.nio.file.Files;
import java.util.UUID;
import java.util.Map;
import static dev.monocle.client.systems.bots.BotTaskData.*;

/** Real Lua decisions reduced through the live checkpoint code, without a Minecraft client. */
public final class BotRuntimeTest {
    public static void run() throws Exception {
        JsonObject run = run("""
            return function(ctx)
              if not ctx.state.called then
                ctx.state.called = true
                return bot.call('child', {number=ctx.args.number})
              end
              assert(ctx.result.ok and ctx.result.value.answer == ctx.args.number * 2)
              ctx.state.answer = ctx.result.value.answer
              return bot.done(ctx.state.answer)
            end
            """);
        program(run, "child", """
            return function(ctx)
              if not ctx.state.waited then
                ctx.state.waited = true
                return bot.wait(4)
              end
              assert(ctx.result.ok)
              return bot.done({answer=ctx.args.number * 2})
            end
            """);
        BotRuntime.top(run).getAsJsonObject("args").addProperty("number", 7);
        BotRuntime.validateCheckpoint(id(run), run);
        assert !decide(run).nativeAction();
        assert run.getAsJsonArray("stack").size() == 2 && text(BotRuntime.top(run), "program").equals("child");
        assert decide(run).nativeAction();
        String token = text(BotRuntime.top(run), "token");
        UUID.fromString(token);
        assert BotRuntime.top(run).getAsJsonObject("action").get("ticks").getAsInt() == 4;
        invalid(() -> BotRuntime.applyDecision(run, new BotLua.Decision(new JsonObject(), object("{type:'Done'}"))));

        // An actual disk round-trip preserves the nested frames, args and action identity.
        var directory = Files.createTempDirectory("monocle-runtime-check-");
        var checkpoint = directory.resolve("worker.json");
        JsonObject restored;
        try {
            write(checkpoint, run);
            restored = read(checkpoint);
            assert restored.equals(run);
        } finally { Files.deleteIfExists(checkpoint); Files.deleteIfExists(directory); }
        BotRuntime.validateCheckpoint(id(restored), restored);
        assert token.equals(text(BotRuntime.top(restored), "token"));
        // Native execution has its own tests; deliver its recorded result to the real Lua frame.
        JsonObject child = BotRuntime.top(restored);
        child.remove("action"); child.remove("token"); child.add("result", object("{ok:true,detail:'Wait complete'}"));
        assert !decide(restored).nativeAction();
        assert restored.getAsJsonArray("stack").size() == 1;
        assert BotRuntime.top(restored).getAsJsonObject("state").get("called").getAsBoolean();
        assert BotRuntime.top(restored).getAsJsonObject("result").getAsJsonObject("value").get("answer").getAsInt() == 14;
        BotRuntime.Transition done = decide(restored);
        assert done.finish().equals("Complete") && BotRuntime.top(restored) == null;
        assert BotRuntime.savedFinish(restored).equals("Complete");
        BotRuntime.validateCheckpoint(id(restored), restored);
        restored.addProperty("requestedStatus", "Cancelled");
        BotRuntime.validateCheckpoint(id(restored), restored); // Cancellation during empty-stack cleanup is recoverable too.

        JsonObject failed = run("return function(ctx) return bot.fail('Out of obsidian') end");
        assert decide(failed).detail().equals("Out of obsidian") : "bot.fail uses detail, not message";
        JsonObject recursive = run("return function(ctx) return bot.call('main') end");
        for (int i = 1; i < 16; i++) decide(recursive);
        assert recursive.getAsJsonArray("stack").size() == 16;
        invalid(() -> decide(recursive));
        invalid(() -> decide(run("return function(ctx) return bot.call('unbundled') end")));
        invalid(() -> decide(run("return function(ctx) return bot.highway({workflow='unbundled'}) end")));
        for (String action : java.util.List.of("Travel", "StashHunt", "DropItems", "Modules", "Tpa", "Highway")) {
            JsonObject restricted = run("return function(ctx) return bot.done() end");
            restricted.add("supportedActions", object("{allowed:['Wait']}").getAsJsonArray("allowed"));
            JsonObject nativeAction = new JsonObject(); nativeAction.addProperty("type", action);
            invalid(() -> BotRuntime.applyDecision(restricted, new BotLua.Decision(new JsonObject(), nativeAction)));
            assert !BotRuntime.top(restricted).has("action") : "Unsupported host action rejected before acquiring native controls";
        }
        JsonObject supported = run("return function(ctx) return bot.wait(2) end");
        supported.add("supportedActions", object("{allowed:['Wait']}").getAsJsonArray("allowed"));
        BotRuntime.validateCheckpoint(id(supported), supported);
        assert decide(supported).nativeAction();
        checkpoints();
        cancellation();
        System.out.println("Bot runtime checks passed: nested Lua calls, durable frames/results/tokens, bounded stack, terminal cleanup, uncertain teleport/drop checkpoints and credential isolation.");
    }

    private static void cancellation() throws Exception {
        for (String outcome : java.util.List.of("Suspended", "Failed", "Complete", "Cancelled")) {
            assert BotRuntime.finishOutcome("Cancelled", outcome).equals("Cancelled") : "Retries, failures and disconnects cannot undo host cancellation";
            assert BotRuntime.finishOutcome(outcome, "Cancelled").equals("Cancelled");
        }
        assert BotRuntime.finishOutcome("Failed", "Suspended").equals("Failed");
        assert BotRuntime.finishOutcome("", "Suspended").equals("Suspended");
        try (var bytes = BotRuntime.class.getResourceAsStream("BotRuntime.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var tick = compiled.methods().stream().filter(m -> m.methodName().equalsString("tick")).findFirst().orElseThrow();
            var calls = tick.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
            assert calls.indexOf("heartbeat") < calls.indexOf("canUpdate") : "World/menu readiness cannot suppress cancellation acknowledgments";
            assert !calls.contains("control") : "A worker tick must not authorize its own reconnect resume";
        }
        try (var bytes = BotScheduler.class.getResourceAsStream("BotScheduler.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var tick = compiled.methods().stream().filter(m -> m.methodName().equalsString("tick")).findFirst().orElseThrow();
            var calls = tick.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
            assert calls.indexOf("dispatchCancellation") >= 0 && calls.indexOf("dispatchCancellation") < calls.indexOf("canUpdate")
                && calls.indexOf("dispatchCancellation") < calls.indexOf("manageHighway") && calls.indexOf("dispatchCancellation") < calls.indexOf("schedule")
                : "A hung native job or unrelated higher-priority task cannot starve cancellation delivery";
        }
    }

    private static void checkpoints() {
        assert BotRuntime.nativeFinished(object("{state:'Complete'}")) && BotRuntime.nativeFinished(object("{state:'Failed'}"));
        assert !BotRuntime.nativeFinished(object("{state:'Running'}")) : "Only an unfinished native token may accept a host result";
        JsonObject run = run("return function(ctx) return bot.tpa({target='TestBot'}) end");
        assert BotRuntime.sameServer(run, "legacy.example") : "Old journals remain readable";
        run.addProperty("server", "anarchy.example:25565");
        assert BotRuntime.sameServer(run, "ANARCHY.EXAMPLE:25565");
        assert !BotRuntime.sameServer(run, "another.example:25565") && !BotRuntime.sameServer(run, "anarchy.example:25566");
        decide(run);
        JsonObject frame = BotRuntime.top(run), action = frame.getAsJsonObject("action");
        assert !BotRuntime.uncertainTeleport(frame);
        JsonObject nativeState = object("{version:1,state:'Running',tpaSent:false}"); nativeState.add("action", action.deepCopy());
        frame.add("native", nativeState); frame.addProperty("commandSent", true);
        BotRuntime.validateCheckpoint(id(run), run);
        assert BotRuntime.uncertainTeleport(frame) && BotRuntime.hasUncertainAction(run)
            : "Durable command intent wins over an older native snapshot; never resend it";
        assert BotRuntime.unresolvedOther(Map.of(id(run), run), UUID.randomUUID()).orElseThrow() == run;
        assert BotRuntime.unresolvedOther(Map.of(id(run), run), id(run)).isEmpty() : "Only the original checkpoint may reconcile its uncertain effects";
        run.addProperty("requestedStatus", "Cancelled");
        assert !BotRuntime.blocksNewWork(run) && BotRuntime.unresolvedOther(Map.of(id(run), run), UUID.randomUUID()).isEmpty()
            : "A host-cancelled receipt remains durable but cannot trap later work";
        run.remove("requestedStatus");
        frame.remove("commandSent"); nativeState.addProperty("tpaSent", true);
        assert BotRuntime.uncertainTeleport(frame) && BotRuntime.hasUncertainAction(run);
        nativeState.addProperty("state", "Failed");
        assert !BotRuntime.hasUncertainAction(run) : "A recorded teleport timeout has settled";
        assert BotRuntime.unresolvedOther(Map.of(id(run), run), UUID.randomUUID()).isEmpty();
        nativeState.add("pendingDrop", new JsonObject());
        assert BotRuntime.hasUncertainAction(run) : "An unresolved drop remains uncertain even after timeout";
        nativeState.remove("pendingDrop");
        assert BotRuntime.ownedBy(run, "test-credential") && !BotRuntime.ownedBy(run, "different-crew") && !BotRuntime.ownedBy(run, "");

        JsonObject malformed = run.deepCopy();
        BotRuntime.top(malformed).getAsJsonObject("native").getAsJsonObject("action").addProperty("target", "SomeoneElse");
        invalid(() -> BotRuntime.validateCheckpoint(id(malformed), malformed));
        JsonObject badToken = run.deepCopy(); BotRuntime.top(badToken).addProperty("token", "wrong");
        invalid(() -> BotRuntime.validateCheckpoint(id(badToken), badToken));
        JsonObject orphan = run.deepCopy(); BotRuntime.top(orphan).remove("action");
        invalid(() -> BotRuntime.validateCheckpoint(id(orphan), orphan));
        JsonObject badParent = run.deepCopy(); badParent.getAsJsonArray("stack").add(BotRuntime.frame("main", new JsonObject()));
        invalid(() -> BotRuntime.validateCheckpoint(id(badParent), badParent));
        JsonObject badStatus = run.deepCopy(); badStatus.addProperty("status", "Anything");
        invalid(() -> BotRuntime.validateCheckpoint(id(badStatus), badStatus));
        JsonObject badPending = run.deepCopy(); badPending.addProperty("requestedStatus", "Running");
        invalid(() -> BotRuntime.validateCheckpoint(id(badPending), badPending));
        JsonObject empty = run.deepCopy(); empty.add("stack", new JsonArray());
        invalid(() -> BotRuntime.validateCheckpoint(id(empty), empty));
        JsonObject wrongIdentity = run.deepCopy(); wrongIdentity.addProperty("run", UUID.randomUUID().toString());
        invalid(() -> BotRuntime.validateCheckpoint(id(run), wrongIdentity));
        JsonObject badProgram = run.deepCopy(); BotRuntime.top(badProgram).addProperty("program", "absent");
        invalid(() -> BotRuntime.validateCheckpoint(id(badProgram), badProgram));
    }
    private static UUID id(JsonObject run) { return UUID.fromString(text(run, "run")); }
    private static BotRuntime.Transition decide(JsonObject run) {
        JsonObject frame = BotRuntime.top(run);
        String script = text(run.getAsJsonObject("package").getAsJsonObject("programs").getAsJsonObject(text(frame, "program")), "script");
        return BotRuntime.applyDecision(run, BotLua.next(script, frame.getAsJsonObject("state"), frame.getAsJsonObject("args"), frame.getAsJsonObject("result"), new JsonObject()));
    }
    private static JsonObject run(String script) {
        JsonObject run = new JsonObject();
        run.addProperty("run", UUID.randomUUID().toString()); run.addProperty("task", UUID.randomUUID().toString());
        run.addProperty("name", "Test workflow"); run.addProperty("owner", "test-credential"); run.addProperty("status", "Running");
        JsonObject packaged = object("{version:1,entry:'main',programs:{},highways:{},profiles:{Current:{}}}"); run.add("package", packaged);
        program(run, "main", script);
        JsonArray stack = new JsonArray(); stack.add(BotRuntime.frame("main", new JsonObject())); run.add("stack", stack);
        return run;
    }
    private static void program(JsonObject run, String id, String script) {
        JsonObject program = new JsonObject(); program.addProperty("name", id); program.addProperty("script", script);
        run.getAsJsonObject("package").getAsJsonObject("programs").add(id, program);
    }
    private static JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private static void invalid(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid checkpoint/action accepted"); }
        catch (IllegalArgumentException | IllegalStateException expected) { }
    }
}
