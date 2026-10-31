package dev.monocle.client.systems.bots;

import com.google.gson.*;
import java.nio.file.Files;
import java.util.UUID;
import java.util.Map;
import static dev.monocle.client.systems.bots.BotTaskData.*;

/** Real Lua decisions reduced through the live checkpoint code, without a Minecraft client. */
public final class BotRuntimeTest {
    public static void run() throws Exception {
        assert BotRuntime.stashFindingDue("new/1","old/1",10,10) : "New observations upload immediately";
        assert !BotRuntime.stashFindingDue("same/1","same/1",999_999_999L,0)
            &&BotRuntime.stashFindingDue("same/1","same/1",1_000_000_000L,0) : "Retries use wall time, not accelerated game ticks";
        assert BotRuntime.stashFindingDue("same/2","same/1",10,10) : "The next receipt does not wait behind a previous retry";
        assert !BotRuntime.stashFindingDue("same/1","same/1",2_999_999_999L,0,true)
            &&BotRuntime.stashFindingDue("same/1","same/1",3_000_000_000L,0,true)
            &&BotRuntime.stashFindingDue("new/2","same/1",10,10,true) : "Scans back off duplicates without delaying a new read";
        assert !BotRuntime.scanPeriodicCheckpoint(true,20)&&BotRuntime.scanPeriodicCheckpoint(true,100)
            &&BotRuntime.scanPeriodicCheckpoint(false,20) : "Only read-only scan movement reduces periodic checkpoint churn";
        assert BotRuntime.currentStashAcknowledgement(true,4,4);
        assert !BotRuntime.currentStashAcknowledgement(false,5,4)
            &&!BotRuntime.currentStashAcknowledgement(true,5,4)
            &&!BotRuntime.currentStashAcknowledgement(true,4,5) : "Duplicate/stale/future receipts cannot trigger full checkpoint saves";
        JsonObject scanStatus=object("{phase:'Opening',reason:'Requesting contents',target:'0,116,0',observed:4,unscanned:1}");
        String signature=BotRuntime.scanProgressSignature("scan-1",scanStatus);
        scanStatus.addProperty("phase","Reading");scanStatus.addProperty("reason","Waiting for contents");scanStatus.addProperty("target","1,116,0");
        assert signature.equals(BotRuntime.scanProgressSignature("scan-1",scanStatus)) : "Read-only display transitions do not force full worker checkpoints";
        scanStatus.addProperty("observed",5);
        assert !signature.equals(BotRuntime.scanProgressSignature("scan-1",scanStatus)) : "New locally durable observations checkpoint before upload";
        signature=BotRuntime.scanProgressSignature("scan-1",scanStatus);scanStatus.addProperty("removed",1);
        assert !signature.equals(BotRuntime.scanProgressSignature("scan-1",scanStatus)) : "Removed-container observations also checkpoint before upload";
        assert !BotRuntime.scanProgressSignature("scan-1",scanStatus).equals(BotRuntime.scanProgressSignature("scan-2",scanStatus)) : "A new scan token cannot inherit the previous scan's checkpoint marker";
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
        JsonObject restored;
        try (var journal=new dev.monocle.coordinator.TaskJournal(directory.resolve("worker.sqlite"),directory.resolve("worker.json"),"runs")) {
            JsonObject root=new JsonObject(),records=new JsonObject();root.addProperty("version",1);records.add(id(run).toString(),run);root.add("runs",records);
            journal.write(root);
            restored = journal.read().getAsJsonObject("runs").getAsJsonObject(id(run).toString());
            assert restored.equals(run);
        } finally { try(var files=Files.list(directory)){for(var file:files.toList())Files.delete(file);}Files.delete(directory); }
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
        assert restored.get("workflowResult").getAsInt()==14 : "Root results must survive final cleanup and reach the host";
        JsonObject receiptStatus=object("{action:{type:'StashDeposit'},token:'deposit-1'}");
        assert BotRuntime.reportsNativeReceipt(receiptStatus,"deposit-1","StashDeposit");
        assert !BotRuntime.reportsNativeReceipt(receiptStatus,"deposit-2","StashDeposit")&&!BotRuntime.reportsNativeReceipt(receiptStatus,"deposit-1","StashResupply");
        receiptStatus.remove("action");assert !BotRuntime.reportsNativeReceipt(receiptStatus,"deposit-1","StashDeposit") : "Cleanup and idle workflow frames cannot resend a detached receipt";
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
        stashTransfer();
        cancellation();
        checkpointRetention();
        System.out.println("Bot runtime checks passed: nested Lua calls, durable frames/results/tokens, bounded stack, safe checkpoint retention, terminal cleanup, uncertain teleport/drop checkpoints and credential isolation.");
    }

    private static void stashTransfer(){
        var library=new BotWorkflows(null);JsonObject run=run(library.get("task-stash-transfer").script());
        program(run,"task-kit-delivery","return function(ctx) return bot.done({delivered=36,sourceExhausted=true}) end");
        JsonObject args=object("{kitTypeIds:['00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002']}");BotRuntime.top(run).add("args",args);
        for(int index=1;index<=2;index++){
            assert decide(run).nativeAction()&&BotRuntime.top(run).getAsJsonObject("action").get("index").getAsInt()==index;
            JsonObject frame=BotRuntime.top(run);frame.remove("action");frame.remove("token");frame.add("result",object("{ok:true,args:{count:36}}"));
            assert !decide(run).nativeAction()&&run.getAsJsonArray("stack").size()==2;
            assert !decide(run).nativeAction()&&run.getAsJsonArray("stack").size()==1;
        }
        assert decide(run).finish().equals("Complete")&&run.getAsJsonObject("workflowResult").get("delivered").getAsInt()==72 : "Live nested-call results are unwrapped and counted once per type";
    }
    private static void cancellation() throws Exception {
        assert !BotRuntime.ignoresControl("Failed","cancel") : "A failed run must acknowledge host cancellation even before reconnect";
        for(String command:java.util.List.of("pause","resume"))assert BotRuntime.ignoresControl("Failed",command) : "Failed work cannot resume or replay";
        for(String status:java.util.List.of("Complete","Cancelled"))assert BotRuntime.ignoresControl(status,"cancel")&&BotRuntime.ignoresControl(status,"resume");
        JsonObject stashFrame = object("{token:'saved-token',action:{type:'StashResupply'},native:{state:'Running',stashResupply:{withdrawPending:true}}}");
        assert BotRuntime.needsActionRestore(stashFrame, "", "") : "Resume retains uncertain-move inspection";
        assert BotRuntime.needsActionRestore(stashFrame, "", "Failed");
        assert !BotRuntime.needsActionRestore(stashFrame, "", "Cancelled") : "Cancellation must not repeatedly restore an unconfirmed stash click";
        assert !BotRuntime.needsActionRestore(stashFrame, "saved-token", "Cancelled") : "Live actions keep their existing cleanup executor";
        JsonObject retainedRun = object("{stack:[],status:'Failed'}"); retainedRun.getAsJsonArray("stack").add(stashFrame);
        assert BotRuntime.hasUncertainAction(retainedRun) && BotRuntime.blocksNewWork(retainedRun);
        retainedRun.addProperty("requestedStatus", "Cancelled");
        assert BotRuntime.hasUncertainAction(retainedRun) && !BotRuntime.blocksNewWork(retainedRun) : "Cancelled uncertain receipts remain durable without trapping new work";
        stashFrame.getAsJsonObject("native").add("stashResupply", object("{discardPending:true}"));
        assert BotRuntime.hasUncertainAction(retainedRun)&&!BotRuntime.needsActionRestore(stashFrame,"","Cancelled") : "Interrupted destructive discards are retained, never replayed on cancellation";
        stashFrame.getAsJsonObject("native").remove("stashResupply");
        stashFrame.getAsJsonObject("native").add("stashDeposit", object("{pending:true}"));
        assert !BotRuntime.needsActionRestore(stashFrame, "", "Cancelled") && BotRuntime.hasUncertainAction(retainedRun);
        stashFrame.getAsJsonObject("native").remove("stashDeposit");
        stashFrame.getAsJsonObject("native").add("stashBuffer", object("{pending:true}"));
        assert BotRuntime.hasUncertainAction(retainedRun)&&!BotRuntime.needsActionRestore(stashFrame,"","Cancelled")
            : "Interrupted ender cargo remains guarded and is not replayed during cancellation";
        stashFrame.getAsJsonObject("native").remove("stashBuffer");
        stashFrame.getAsJsonObject("native").add("pendingDrop", new JsonObject());
        assert !BotRuntime.needsActionRestore(stashFrame, "", "Cancelled")&&BotRuntime.hasUncertainAction(retainedRun) : "Cancelled drops retain evidence without restarting their executor";
        assert BotActions.dropNeedsSettlement("Running",true) : "An issued live drop gets its bounded confirmation window";
        assert !BotActions.dropNeedsSettlement("Failed",true)&&BotActions.dropNeedsSettlement("Failed",false) : "Cancellation can yield after an uncertain drop times out; pause retains reconciliation";
        JsonObject tpaFrame=object("{token:'saved-token',action:{type:'Tpa'},commandSent:true,native:{state:'Running',tpaSent:true}}");
        assert !BotRuntime.needsActionRestore(tpaFrame,"","Cancelled")&&BotRuntime.needsActionRestore(tpaFrame,"","") : "Cancel never resends or waits forever for an old TPA; ordinary resume observes it";
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
            assert calls.indexOf("heartbeat") < calls.indexOf("ready") && calls.indexOf("ready") < calls.indexOf("needsActionRestore") : "Passive readiness gates gameplay, not transport acknowledgements";
            assert calls.contains("sendPendingStashFinding") : "Every native tick dispatches pending findings";
            var actionTick=compiled.methods().stream().filter(m->m.methodName().equalsString("tickAction")).findFirst().orElseThrow();
            var actionCalls=actionTick.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance).map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c->c.name().stringValue()).toList();
            assert actionCalls.contains("watchProgress") && actionCalls.contains("progressPaused") && actionCalls.contains("finish") : "Stalls use the existing durable suspension/cleanup path, not direct executor cancellation";
            var findings=compiled.methods().stream().filter(m->m.methodName().equalsString("sendPendingStashFinding")).findFirst().orElseThrow();
            var findingCalls=findings.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c->c.name().stringValue()).toList();
            assert findingCalls.contains("hasStashPending")&&findingCalls.contains("sendStashFinding")
                &&!findingCalls.contains("stashTelemetry")&&findingCalls.contains("stashFindingDue")
                : "Deposit column requests upload without requiring scan telemetry; scan detection only selects the retry interval";
            assert findingCalls.contains("scanUploads") : "The production sender keeps a bounded window in flight rather than waiting for each individual receipt";
            var loader=compiled.methods().stream().filter(m->m.methodName().equalsString("loadAction")).findFirst().orElseThrow();
            assert loader.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).anyMatch(c->c.name().equalsString("scanCheckpoint"))
                : "Actual scan resume must restore its independent durable journal";
            assert !calls.contains("control") : "A worker tick must not authorize its own reconnect resume";
            assert calls.contains("needsActionRestore") : "Cancellation restore guard must be used in the live tick";
            var control = compiled.methods().stream().filter(m -> m.methodName().equalsString("control")&&m.methodTypeSymbol().parameterCount()==4).findFirst().orElseThrow();
            assert control.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).anyMatch(c -> c.name().equalsString("ignoresControl"));
            var controlCalls=control.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance).map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c->c.name().stringValue()).toList();
            assert controlCalls.indexOf("save")<controlCalls.indexOf("ignoresControl") : "Correlated intent is durable before terminal or world-wait acknowledgements";
            assert controlCalls.contains("ready") : "World loading and lobby presence cannot apply gameplay profiles";
            assert calls.contains("tickProfileLanding") : "An authorized airborne Resume must land before recovering or applying profiles";
            var landingMethod=compiled.methods().stream().filter(m->m.methodName().equalsString("tickProfileLanding")).findFirst().orElseThrow();
            var landingCalls=landingMethod.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance).map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c->c.name().stringValue()).toList();
            assert landingCalls.containsAll(java.util.List.of("ownedBy","sameServer","landForProfile","clearProfileLanding"))&&!landingCalls.contains("apply")&&!landingCalls.contains("control")
                : "Landing-only recovery checks host/world ownership and cannot apply profiles or authorize its own job resume";
            var configure=compiled.methods().stream().filter(m->m.methodName().equalsString("configure")).findFirst().orElseThrow();
            assert configure.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance).map(java.lang.classfile.instruction.InvokeInstruction.class::cast).anyMatch(c->c.name().equalsString("ready")) : "Live module edits also wait for a ready world";
            var cleanup = compiled.methods().stream().filter(m -> m.methodName().equalsString("settleFinish")).findFirst().orElseThrow();
            var cleanupCalls = cleanup.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
            assert cleanupCalls.indexOf("getCarried") < cleanupCalls.indexOf("requestSuspend") : "Never close a container with a carried cursor item";
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

    private static void checkpointRetention() throws Exception {
        Map<UUID, JsonObject> records = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 63; i++) {
            var record = run("return function(ctx) return bot.done() end"); record.addProperty("status", "Ready");
            records.put(id(record), record);
        }
        assert BotRuntime.retirementCandidate(records, null) == null : "A queue below capacity never discards work";
        var last = run("return function(ctx) return bot.done() end"); last.addProperty("status", "Ready"); records.put(id(last), last);
        var keys = java.util.List.copyOf(records.keySet());
        UUID uncertain = keys.get(0), current = keys.get(1), settled = keys.get(2), later = keys.get(3);
        records.get(current).addProperty("status", "Complete");
        records.get(later).addProperty("status", "Complete");
        for (String status : java.util.List.of("Cancelled", "Failed", "Complete")) {
            records.get(uncertain).addProperty("status", status);
            records.get(settled).addProperty("status", status);
            for (String receipt : java.util.List.of(
                "{action:{type:'DropItems'},native:{state:'Failed',pendingDrop:{}}}",
                "{action:{type:'StashResupply'},native:{state:'Failed',stashResupply:{withdrawPending:true}}}",
                "{action:{type:'StashResupply'},native:{state:'Failed',stashResupply:{discardPending:true}}}",
                "{action:{type:'StashDeposit'},native:{state:'Failed',stashDeposit:{depositPending:true}}}",
                "{action:{type:'StashDeposit'},native:{state:'Failed',stashDeposit:{pending:true}}}",
                "{action:{type:'Tpa'},commandSent:true}",
                "{action:{type:'Tpa'},native:{state:'Running',tpaSent:true}}")) {
                records.get(uncertain).getAsJsonArray("stack").set(0, object(receipt));
                JsonObject before = new JsonObject(); records.forEach((id, record) -> before.add(id.toString(), record.deepCopy()));
                assert BotRuntime.hasUncertainAction(records.get(uncertain));
                if (status.equals("Cancelled")) assert !BotRuntime.blocksNewWork(records.get(uncertain))
                    : "Preserving a cancelled receipt is not a new execution blocker";
                assert BotRuntime.retirementCandidate(records, current).equals(settled)
                    : "Capacity eviction skips uncertain receipts and the current run, choosing the oldest settled record";
                assert records.size() == 64;
                records.forEach((id, record) -> { assert record.equals(before.getAsJsonObject(id.toString())) : "Selection must not mutate recovery evidence"; });
            }
        }
        records.get(settled).addProperty("status", "Suspended");
        assert BotRuntime.retirementCandidate(records, current).equals(later) : "Paused jobs remain resumable rather than disposable";
        records.get(later).addProperty("status", "Inspection required");
        try { BotRuntime.retirementCandidate(records, current); throw new AssertionError("Protected recovery records were evicted to admit new work"); }
        catch (IllegalStateException expected) { assert expected.getMessage().contains("queue is full"); }
        assert records.size() == 64 && BotRuntime.hasUncertainAction(records.get(uncertain))
            : "A full protected queue is rejected before removing any existing checkpoint";
        try (var bytes = BotRuntime.class.getResourceAsStream("BotRuntime.class")) {
            var method = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes()).methods().stream()
                .filter(m -> m.methodName().equalsString("install")).findFirst().orElseThrow();
            var calls = method.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast)
                .filter(c -> c.name().equalsString("retirementCandidate") || c.owner().asInternalName().equals("java/util/Map"))
                .map(c -> c.name().stringValue()).toList();
            assert calls.contains("retirementCandidate") && calls.indexOf("retirementCandidate") < calls.indexOf("remove")
                : "Live admission checks retention before mutating the queue";
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
