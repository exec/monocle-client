package dev.monocle.client.systems.bots;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.List;

/** Small native boundary/journal/route checks, without constructing a Minecraft window. */
public final class BotActionsTest {
    public static void main(String[] args) throws Exception {
        stashScan();
        assert BotStashResupply.batchSize(27,3,false)==3&&BotStashResupply.batchSize(2,8,false)==2;
        var sourcePicks=JsonParser.parseString("[{x:-16,y:64,z:0,slot:0},{x:-16,y:64,z:0,slot:1},{x:-15,y:64,z:0,slot:0}]").getAsJsonArray();
        var sourceChest=new BlockPos(-16,64,0);
        assert BotStashResupply.keepSourceOpen(sourcePicks,1,sourceChest,1,3) : "Confirmed withdrawals from the same chest do not reopen it";
        assert BotStashResupply.keepSourceOpen(sourcePicks,0,sourceChest,1,3) : "Dynamic column picks retain the current chest";
        assert !BotStashResupply.keepSourceOpen(sourcePicks,2,sourceChest,2,3)
            &&!BotStashResupply.keepSourceOpen(sourcePicks,3,sourceChest,3,4)
            &&!BotStashResupply.keepSourceOpen(sourcePicks,1,sourceChest,3,3) : "Changing chests, exhausted picks and complete batches still close";
        try { BotStashResupply.batchSize(4,3,true); throw new AssertionError("Carry cannot batch without a destination"); }
        catch (IllegalStateException expected) { }
        var kitBatch=json("{type:'StashResupply',name:'Depot',homeName:'depot',minX:0,maxX:0,minY:0,maxY:0,minZ:0,maxZ:0,kitTypeId:'00000000-0000-0000-0000-000000000001',kitExemplar:{},incomplete:false,count:2,pickIndex:1,picks:[{x:0,y:0,z:0,slot:0,resource:'minecraft:purple_shulker_box'}]}");
        assert BotActions.validate(kitBatch).get("pickIndex").getAsInt()==1;
        kitBatch.addProperty("pickIndex",2);
        try { BotActions.validate(kitBatch); throw new AssertionError("A batch cannot resume beyond its saved source picks"); }
        catch (IllegalArgumentException expected) { }
        try { BotStashResupply.batchSize(4,0,false); throw new AssertionError("Pickup cannot start with no free slot"); }
        catch (IllegalStateException expected) { }
        boolean enabled = false; assert enabled = true;
        JsonObject repeated=kitBatch.deepCopy();repeated.addProperty("pickIndex",1);repeated.addProperty("count",36);repeated.addProperty("transferAll",true);repeated.addProperty("depositMode","Carry");
        assert BotActions.validate(repeated).get("count").getAsInt()==36&&BotStashResupply.batchSize(36,36,false)==36;
        repeated.addProperty("count",37);try{BotActions.validate(repeated);throw new AssertionError("Inventory has only 36 slots");}catch(IllegalArgumentException expected){}repeated.addProperty("count",36);
        JsonObject exhausted=json("{index:1,batchLimit:36,depositing:false,deposited:0,withdrawPending:false,depositPending:false,finished:false,receipt:[]}");
        BotStashResupply emptySource=new BotStashResupply(repeated);emptySource.restore(exhausted);
        assert emptySource.finishSourceSearch()&&emptySource.workflowResult().get("picked").getAsInt()==0&&emptySource.workflowResult().get("sourceExhausted").getAsBoolean();
        exhausted.getAsJsonArray("receipt").add(json("{slot:0,items:{'minecraft:obsidian':64}}"));
        BotStashResupply partialSource=new BotStashResupply(repeated);partialSource.restore(exhausted);
        assert partialSource.finishSourceSearch()&&partialSource.workflowResult().get("picked").getAsInt()==1 : "A partial final load must be delivered";
        repeated.addProperty("transferAll",false);BotStashResupply finiteSource=new BotStashResupply(repeated);finiteSource.restore(exhausted);
        assert finiteSource.finishSourceSearch()&&finiteSource.workflowResult().has("sourceIssue")&&!finiteSource.workflowResult().get("sourceExhausted").getAsBoolean()
            : "Confirmed partial loads can be delivered before reporting a source shortage";
        var partialRecovery=new BotStashResupply(repeated);partialRecovery.restore(finiteSource.snapshot());
        assert partialRecovery.workflowResult().get("sourceIssue").equals(finiteSource.workflowResult().get("sourceIssue")) : "Deferred source failure survives a checkpoint";
        var noPickup=new BotStashResupply(repeated);
        assert !noPickup.recoverSourceIssue("Unavailable source") : "No pickup cannot masquerade as a successful empty batch";
        for(int i=0;i<200;i++)assert !noPickup.approachTimedOut(10+(i%2)) : "A bounded approach tolerates jitter briefly";
        assert noPickup.approachTimedOut(10) : "Oscillation does not renew the approach deadline";
        assert !noPickup.approachTimedOut(9.5) : "A genuinely closer approach renews progress";
        var localTrip=repeated.deepCopy();localTrip.addProperty("pickIndex",0);
        assert BotActions.localStashTrip(localTrip,new dev.monocle.coordinator.PlayerObservation.Position(200,0,0));
        assert !BotActions.localStashTrip(localTrip,new dev.monocle.coordinator.PlayerObservation.Position(300,0,0));
        localTrip.getAsJsonArray("picks").get(0).getAsJsonObject().addProperty("x",1000);
        assert !BotActions.localStashTrip(localTrip,new dev.monocle.coordinator.PlayerObservation.Position(0,0,0)) : "Withdrawal distance uses the actual source, not a nearby cuboid edge";
        JsonObject denseBox=new JsonObject(),denseItems=new JsonObject();for(int i=0;i<100;i++)denseItems.addProperty("minecraft:item_"+i,64);denseBox.add("items",denseItems);
        exhausted.getAsJsonArray("receipt").remove(0);for(int i=0;i<36;i++)exhausted.getAsJsonArray("receipt").add(denseBox.deepCopy());
        BotStashResupply fullSource=new BotStashResupply(repeated);fullSource.restore(exhausted);
        assert fullSource.result().toString().length()>32_768&&fullSource.workflowResult().toString().length()<1000 : "Full receipts stay available to host telemetry without overflowing Lua state";
        if (!enabled) throw new IllegalStateException("Run with -ea");
        for (String state : List.of("Running", "Failed", "Complete")) {
            assert BotActions.shouldTick(state, true, false, true) : "Landing/cleanup must tick even after failure or suspension";
            assert BotActions.shouldTick(state, true, true, false) : "Pending issued drops remain observable";
        }
        assert !BotActions.shouldTick("Complete", false, false, false);
        assert !BotActions.shouldTick("Running", true, false, false);
        var travel = BotActions.validate(json("{\"type\":\"Travel\",\"x\":-12000,\"y\":64,\"z\":20000}"));
        var follow=BotActions.validate(json("{\"type\":\"Travel\",\"follow\":true,\"target\":\"00000000-0000-0000-0000-000000000001\"}"));
        assert follow.get("ticks").getAsInt()==0&&!follow.has("x");
        var guard=BotActions.validate(json("{type:'Travel',follow:true,bodyguard:true,target:'00000000-0000-0000-0000-000000000001',targetName:'Subject',workerIndex:1,workerCount:3,radius:3}"));
        var crystalGuard=BotActions.validate(json("{type:'Travel',follow:true,bodyguard:true,crystalGuard:true,target:'00000000-0000-0000-0000-000000000001',targetName:'Subject',combatTargets:['Enemy']}"));
        assert crystalGuard.getAsJsonArray("protectedPlayers").size()==1 && crystalGuard.getAsJsonArray("combatTargets").get(0).getAsString().equals("Enemy");
        var tooManyProtected=json("{type:'Travel',follow:true,bodyguard:true,crystalGuard:true,target:'00000000-0000-0000-0000-000000000001',targetName:'Subject'}");
        var protectedPlayers=new com.google.gson.JsonArray();for(int i=2;i<=17;i++)protectedPlayers.add("00000000-0000-0000-0000-%012d".formatted(i));tooManyProtected.add("protectedPlayers",protectedPlayers);
        try { BotActions.validate(tooManyProtected); throw new AssertionError("Subject addition exceeded protected roster limit"); }
        catch (IllegalArgumentException expected) {}
        Vec3 center=BotActions.formationGoal(Vec3.ZERO,0,3,1,3),leftGuard=BotActions.formationGoal(Vec3.ZERO,0,3,0,3),rightGuard=BotActions.formationGoal(Vec3.ZERO,0,3,2,3);
        assert BotActions.retreatGoal(new Vec3(2,64,0),Vec3.ZERO,16).equals(new Vec3(18,64,0));
        assert guard.get("workerCount").getAsInt()==3&&Math.abs(center.z+3)<1e-9&&Math.abs(leftGuard.x+rightGuard.x)<1e-9&&Math.abs(leftGuard.z-rightGuard.z)<1e-9;
        Vec3 inherited=new Vec3(.4,.1,.2);assert BotActions.formationVelocity(Vec3.ZERO,Vec3.ZERO,inherited,1).equals(inherited);
        assert BotActions.formationVelocity(Vec3.ZERO,new Vec3(100,0,0),Vec3.ZERO,2).length()<=2.0000001;
        assert BotActions.formationWalkSpeed(new Vec3(.2,0,0),.75)==4&&BotActions.formationWalkSpeed(new Vec3(2,0,0),20)==20;
        var recovery = BotActions.validate(json("{\"type\":\"RecoverSupplies\"}"));
        assert !BotActions.shouldRecover("Highway", false) : "A fresh native highway must not replay an unrelated supply journal";
        assert BotActions.shouldRecover("RecoverSupplies", false);
        assert recovery.get("flyBeyond").getAsDouble() == 8 && recovery.get("searchRadius").getAsInt() == 16;
        assert !recovery.has("inspectTransfersBefore") : "Inspection is never automatic";
        recoveryArea();
        assert BotActions.validate(json("{\"type\":\"RecoverSupplies\",\"inspectTransfersBefore\":1000}")).get("inspectTransfersBefore").getAsLong() == 1000;
        assert travel.get("radius").getAsDouble() == 2;
        var original = json("{\"type\":\"Modules\",\"modules\":{\"kill-aura\":true,\"auto-eat\":true}}");
        assert BotActions.validate(original).get("ticks").getAsInt() == 0 && !original.has("ticks") : "Validation must not mutate the caller's snapshot";
        assert BotActions.validate(json("{\"type\":\"Wait\",\"ticks\":1728000}")).get("ticks").getAsInt() == 1728000;
        assert BotActions.validate(json("{\"type\":\"DropItems\",\"item\":\"minecraft:obsidian\",\"count\":128}")).get("count").getAsInt() == 128;
        var teleport = BotActions.validate(json("{\"type\":\"Tpa\",\"target\":\"Some_Player\",\"dimension\":\"minecraft:the_nether\"}"));
        assert teleport.get("warmupTicks").getAsInt() == 300 && teleport.get("acceptDelayTicks").getAsInt()==10 && teleport.get("radius").getAsDouble() == 8;
        for (String invalid : List.of(
            "{\"type\":\"Command\",\"command\":\"anything\"}",
            "{\"type\":\"RecoverSupplies\",\"inspectTransfersBefore\":0}",
            "{\"type\":\"RecoverSupplies\",\"inspectTransfersBefore\":1.5}",
            "{\"type\":\"RecoverSupplies\",\"inspectTransfersBefore\":\"1000\"}",
            "{\"type\":\"RecoverSupplies\",\"inspectTransfersBefore\":999999999999999}",
            "{\"type\":\"RecoverSupplies\",\"searchRadius\":33}", "{\"type\":\"RecoverSupplies\",\"retryTicks\":0}",
            "{\"type\":\"RecoverSupplies\",\"x\":0}",
            "{\"type\":\"RecoverSupplies\",\"y\":116,\"z\":0}",
            "{\"type\":\"RecoverSupplies\",\"x\":0,\"y\":116,\"z\":30000000}",
            "{\"type\":\"RecoverSupplies\",\"execution\":\"wrong-job\"}",
            "{\"type\":\"Wait\",\"ticks\":1.5}", "{\"type\":\"Wait\",\"ticks\":-1}",
            "{\"type\":\"Travel\",\"x\":30000001,\"y\":64,\"z\":0}",
            "{\"type\":\"Travel\",\"x\":0,\"y\":64,\"z\":0,\"radius\":0}",
            "{\"type\":\"Travel\",\"follow\":true,\"bodyguard\":true,\"target\":\"00000000-0000-0000-0000-000000000001\",\"workerIndex\":3,\"workerCount\":3}",
            "{\"type\":\"Travel\",\"follow\":true,\"bodyguard\":true,\"target\":\"00000000-0000-0000-0000-000000000001\",\"targetName\":\"bad name\"}",
            "{type:'Travel',follow:true,crystalGuard:true,target:'00000000-0000-0000-0000-000000000001'}",
            "{type:'Travel',follow:true,bodyguard:true,crystalGuard:true,target:'00000000-0000-0000-0000-000000000001',targetName:'Subject',combatTargets:['bad name']}",
            "{\"type\":\"Travel\",\"x\":\"0\",\"y\":64,\"z\":0}",
            "{\"type\":\"DropItems\",\"item\":\"obsidian\",\"count\":1}",
            "{\"type\":\"DropItems\",\"item\":\"minecraft:obsidian\",\"count\":0}",
            "{\"type\":\"Modules\",\"modules\":{\"highway-builder\":true}}",
            "{\"type\":\"Modules\",\"modules\":{\"printer-helper\":false}}",
            "{\"type\":\"Modules\",\"modules\":{\"kill-aura\":\"true\"}}",
            "{\"type\":\"SetProfile\",\"name\":\"../other\"}",
            "{\"type\":\"Tpa\",\"target\":\"x /op someone\"}",
            "{\"type\":\"Tpa\",\"target\":\"Player\",\"radius\":17}",
            "{\"type\":\"Tpa\",\"target\":\"Player\",\"timeoutTicks\":20,\"warmupTicks\":20}")) {
            try { BotActions.validate(json(invalid)); throw new AssertionError("Accepted unsafe action: " + invalid); }
            catch (IllegalArgumentException expected) {}
        }
        for (int stack = 1; stack <= 99; stack++) for (int count = 1; count <= 200; count++) {
            int dropped = 0, left = stack;
            while (left > 0 && dropped < count) {
                int n = BotActions.nextDropCount(count - dropped, left);
                assert n == 1 || n == left : "Native THROW supports one item or the entire stack, not arbitrary partial stack counts";
                assert n <= count - dropped && n <= left;
                dropped += n; left -= n;
            }
            assert dropped == Math.min(stack, count);
        }
        assert BotActions.confirmedDrop(128, 64, 64, 64, 0);
        assert BotActions.confirmedDrop(128, 1, 127, 64, 63);
        assert !BotActions.confirmedDrop(128, 64, 128, 64, 0) : "A local slot prediction alone cannot confirm a drop";
        assert !BotActions.confirmedDrop(128, 64, 64, 64, 64) : "A separate inventory change cannot confirm this slot";
        assert !BotActions.confirmedDrop(128, 1, 126, 64, 63) : "Unexpected additional disposal is an uncertain operation, never a retry";
        for (String state : List.of("Running", "Complete", "Failed")) for (boolean paused : List.of(false, true)) {
            assert !BotActions.observesPendingDrop(state, paused, false);
            assert BotActions.observesPendingDrop(state, paused, true) == (!state.equals("Running") || paused)
                : "A timed-out or paused drop keeps observing late confirmation without restarting the action";
        }
        for (Vec3 goal : List.of(new Vec3(1_000_000, 100, 0), new Vec3(-1_000_000, -20, 1000), new Vec3(0, 64, 0))) {
            Vec3 start = new Vec3(0, 64, 0), next = BotActions.localGoal(start, goal, 8);
            assert next.distanceTo(start) <= 8.0000001;
            assert start.equals(goal) || next.distanceTo(goal) < start.distanceTo(goal);
        }
        try (var bytes = BotActions.class.getResourceAsStream("BotActions.class")) {
            ClassModel code = ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code, "drop").containsAll(List.of("handleContainerInput", "refreshInventory", "inventoryReady", "aimRecipient", "matches", "encodeStart"));
            assert calls(code, "drop").stream().filter("handleContainerInput"::equals).count() == 1;
            assert !calls(code, "restore").contains("handleContainerInput") && !calls(code, "settleDrop").contains("handleContainerInput")
                : "Recovery may refresh and reconcile but must never resend the destructive click";
            assert calls(code, "tick").containsAll(List.of("observesPendingDrop", "listen", "settleDrop"))
                && calls(code, "release").containsAll(List.of("issuedDrop", "listen"))
                : "Terminal cleanup keeps its inventory observer and runs the same reconciliation path";
            assert calls(code, "inventory").containsAll(List.of("isSameThread", "items", "confirmedDrop", "isSameItemSameComponents"));
            assert calls(code, "travel").containsAll(List.of("requestAutopilot", "safeVelocity", "next", "standable", "safeLandingBelow", "brakeFlight"));
            assert calls(code, "survey").contains("next") : "Survey and travel both follow the persistent local flight route";
            assert calls(code, "correction").contains("resetFlightRoutes") && calls(code, "disconnected").contains("resetFlightRoutes")
                : "Server corrections and reconnects cannot keep a completed stash flight route from the old position";
            assert !calls(code, "travel").contains("handleContainerInput") && !calls(code, "travel").contains("breakWorkBlock") && !calls(code, "travel").contains("placeWorkBlock");
            assert calls(code, "verified").containsAll(List.of("hasChunkAt", "getBlockStatePredictionHandler", "containsKey"));
            assert calls(code, "landBeforeHandoff").containsAll(List.of("onGround", "safeLandingBelow", "requestAutopilot", "safeVelocity"))
                : "Preemption must land safely before a task profile can disable ElytraFly";
            assert !calls(code, "teleport").contains("sendCommand") && calls(code, "teleport").containsAll(List.of("dimension", "players", "distanceToSqr"));
            assert calls(code, "tick").contains("teleport") : "An already-issued teleport remains observed while suspension is requested";
            assert calls(code, "tick").contains("disconnected") && calls(code, "disconnected").containsAll(List.of("disconnected", "releaseMovement"))
                : "World/connection loss must reach the recovery child that owns travel input";
            var tick = code.methods().stream().filter(m -> m.methodName().equalsString("tick")&&m.methodType().stringValue().equals("(Z)Lcom/google/gson/JsonObject;")).findFirst().orElseThrow().code().orElseThrow();
            assert tick.elementList().stream().filter(e -> e instanceof InvokeInstruction call
                && call.owner().asInternalName().equals("dev/monocle/client/systems/bots/BotSupplyRecovery") && call.name().equalsString("tick")
                ).count() == 1 : "Only normal execution runs recovery; cleanup cannot reopen the supply search while landing";
        }
        try (var bytes = BotSupplyRecovery.class.getResourceAsStream("BotSupplyRecovery.class")) {
            var code = ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code, "suspend").containsAll(List.of("requestSuspend", "tick", "stopTravel"))
                && !calls(code, "suspend").contains("move") : "Suspension drives only the existing travel to landing; it cannot start another recovery trip";
            assert calls(code, "disconnected").contains("disconnected") && !calls(code, "disconnected").contains("tick")
                : "A disconnect releases child travel without executing another movement step";
            assert calls(code, "tick").contains("selectedRecords") && calls(code, "move").contains("inArea");
        }
        try (var bytes = BotRuntime.class.getResourceAsStream("BotRuntime.class")) {
            var code = ClassFile.of().parse(bytes.readAllBytes());
            var load = calls(code, "loadAction");
            assert load.indexOf("anchorRecovery") < load.indexOf("save") && load.indexOf("save") < load.indexOf("restore");
            assert load.indexOf("prepareNext")<load.indexOf("start")&&load.indexOf("prepareNext")<load.indexOf("restore")
                : "A pending landing must finish before loading a new native action or applying its external results";
            assert calls(code,"tickAction").contains("stashHandoff")&&calls(code,"teleport").indexOf("loadAction")<calls(code,"teleport").indexOf("sendWorkflowTpa")
                : "Compatible stash steps can keep flight, but host teleport requests cannot bypass grounding";
            var finish = calls(code, "finish");
            assert finish.contains("disconnected") && finish.indexOf("disconnected") < finish.indexOf("checkpointAction")
                : "Stop movement as soon as a finish/pause/cancel is requested, before waiting on persistence or cleanup";
        }
        System.out.println("Bot action checks passed: validation, exact drop planning/confirmation, no resend on recovery, bounded travel and native authority guards.");
    }
    private static void recoveryArea() {
        Vec3 origin = new Vec3(-81953.5, 116, .5);
        var frame = json("{\"action\":{\"type\":\"RecoverSupplies\"},\"native\":{\"action\":{\"type\":\"RecoverSupplies\"}}}");
        assert BotRuntime.anchorRecovery(frame, origin);
        assert frame.get("action").equals(frame.getAsJsonObject("native").get("action")) : "Legacy resume migrates both copies of the checkpointed action";
        var restored = json(frame.toString());
        assert !BotRuntime.anchorRecovery(restored, origin.add(100, 0, 0));
        assert restored.equals(frame) : "Walking/restarting cannot shift the search area farther down a chain of historical records";
        var explicit = json("{\"action\":{\"type\":\"RecoverSupplies\",\"x\":-81954,\"y\":116,\"z\":0}}");
        assert !BotRuntime.anchorRecovery(explicit, origin.add(100, 0, 0));
        String job = "9cebee5a-4fb0-4a0f-981e-f08d4c8aace5";
        var nearby = json("{\"x\":-81954,\"y\":116,\"z\":0,\"execution\":\"" + job + "\"}");
        var edge = nearby.deepCopy(); edge.addProperty("x", -81938);
        var outside = nearby.deepCopy(); outside.addProperty("x", -81937);
        var oldSite = nearby.deepCopy(); oldSite.addProperty("x", -53250);
        var otherJob = nearby.deepCopy(); otherJob.addProperty("execution", "486ebbc6-37f7-4280-b863-51cdb39b82ab");
        var records = List.of(nearby, edge, outside, oldSite, otherJob);
        var before = records.toString();
        assert BotSupplyRecovery.selectedRecords(records, origin, 16, "").equals(List.of(nearby, edge, otherJob));
        assert BotSupplyRecovery.selectedRecords(records, origin, 16, job).equals(List.of(nearby, edge));
        assert BotSupplyRecovery.selectedRecords(List.of(outside, oldSite), origin, 16, job).isEmpty()
            : "No nearby obligations must never fall back to the next historical site";
        assert BotSupplyRecovery.selectedRecords(List.of(nearby), origin.add(0, 17, 0), 16, job).isEmpty();
        assert before.equals(records.toString()) : "Excluded journal entries remain intact";
        var input = new dev.monocle.client.utils.player.CustomPlayerInput();
        input.forward(true); input.tick();
        assert input.hasForwardImpulse();
        input.stop();
        assert input.keyPresses.equals(net.minecraft.world.entity.player.Input.EMPTY) && input.getMoveVector().equals(net.minecraft.world.phys.Vec2.ZERO)
            : "Cancellation clears both keys and their cached movement before the next player tick";
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void stashScan() throws Exception {
        BlockPos highChest=new BlockPos(0,12,0);
        assert BotActions.scanHover(new Vec3(1.5,13.5,.5),highChest) : "A side hover one to two blocks above the chest is valid";
        assert !BotActions.scanHover(new Vec3(1.5,15,.5),highChest) : "Do not hover far above the opening";
        assert BotActions.stashFlightGoal(new Vec3(1.5,11,.5),highChest,false,true) : "Visible chest faces are reachable from below or beside a high chest";
        assert !BotActions.stashFlightGoal(new Vec3(1.5,11,.5),highChest,true,true) : "Hoppers still require an overhead approach";
        List<Vec3> flightOut=List.of(new Vec3(0,1,0),new Vec3(0,4,0),new Vec3(3,4,0));
        assert BotActions.retreatStep(flightOut.getLast(),flightOut,2)==1
            &&BotActions.retreatStep(flightOut.get(1),flightOut,1)==0 : "Stash landing retraces the verified outbound flight route";
        Vec3 landingOrigin=new Vec3(.5,12,.5);
        assert BotActions.nearbyLanding(landingOrigin,BotActions.nearbyLandingCells(landingOrigin,3),48,p->p.x==2.5&&p.z==2.5).equals(new Vec3(2.5,12,2.5))
            &&BotActions.nearbyLanding(landingOrigin,BotActions.nearbyLandingCells(landingOrigin,1),8,p->false)==null : "Restarted flight can find a bounded lateral landing without saved breadcrumbs";
        assert BotActions.nearbyLanding(landingOrigin,BotActions.nearbyLandingCells(landingOrigin,3),48,p->p.x==2.5&&p.z==1.5).equals(new Vec3(2.5,12,1.5))
            : "Safe landings between the cardinal and diagonal spokes must not be missed";
        var landingCandidates=new java.util.HashSet<Vec3>();
        var landingCells=BotActions.nearbyLandingCells(landingOrigin,16);
        assert BotActions.nearbyLanding(landingOrigin,landingCells,16,p->{assert landingCandidates.add(p);return false;})==null;
        assert landingCandidates.size()==16&&landingCells.hasNext() : "One tick checks only its budget and preserves the rest of the landing search";
        while(landingCells.hasNext())assert BotActions.nearbyLanding(landingOrigin,landingCells,16,p->{assert landingCandidates.add(p);return false;})==null;
        assert landingCandidates.size()==33*33-1 : "Every nearby cell is considered once, within the fixed landing radius";
        assert BotActions.stashFlightNeeded(249,237,false)&&!BotActions.stashFlightNeeded(239,237,false)
            &&BotActions.stashFlightNeeded(239,237,true) : "High chests switch from walking to flight and keep flight until landing";
        assert BotStashScan.openingHit(new Vec3(2,14,.5),highChest).getDirection()==net.minecraft.core.Direction.EAST : "Stacked chests open from the exposed side";
        assert java.util.stream.IntStream.range(0,5).map(i->BotStashScan.layerY(i,5)).boxed().toList().equals(List.of(0,4,3,2,1)) : "Lazy scan visits bottom, top, then downward";
        assert !BotStashScan.includeContainer(true,true)&&BotStashScan.includeContainer(true,false)&&BotStashScan.includeContainer(false,true) : "Lazy scans skip hoppers; full audits retain them";
        assert BotStashScan.shouldCloseHiddenMenu(3,0,true,false) : "A delayed second menu must close even when its ID differs from the captured menu";
        assert !BotStashScan.shouldCloseHiddenMenu(3,0,false,false) : "Do not close a menu the scan never opened";
        assert !BotStashScan.shouldCloseHiddenMenu(3,0,true,true) : "Do not close a visible user container";
        JsonObject plan=BotActions.validate(json("{\"type\":\"StashScan\",\"name\":\"Depot\",\"homeName\":\"depot\",\"minX\":0,\"maxX\":0,\"minY\":116,\"maxY\":116,\"minZ\":0,\"maxZ\":0}"));
        stashFlightHandoffs(plan);
        var scan=new BotStashScan(plan);JsonObject checkpoint=scan.snapshot();checkpoint.addProperty("cursor",1);checkpoint.addProperty("discovered",1);checkpoint.addProperty("observed",1);
        checkpoint.addProperty("targetX",0);checkpoint.addProperty("targetY",116);checkpoint.addProperty("targetZ",0);
        checkpoint.add("pending",json("{\"x\":0,\"y\":116,\"z\":0,\"status\":\"observed\",\"block\":\"minecraft:chest\",\"reason\":\"\",\"items\":{\"minecraft:stone\":64},\"shulkers\":[]}"));
        scan.restore(checkpoint);assert !scan.done();scan.acknowledge(2);assert scan.pending()!=null;
        var restored=new BotStashScan(plan);restored.restore(scan.snapshot());restored.acknowledge(1);assert restored.done()&&restored.pending()==null;
        assert !scan.approachProgress(new Vec3(2,116.5,.5)) : "Host acknowledgement waits do not count as navigation progress";
        var approaching=new BotStashScan(plan);var approachCheckpoint=approaching.snapshot();
        approachCheckpoint.addProperty("cursor",1);approachCheckpoint.addProperty("discovered",1);
        approachCheckpoint.addProperty("targetX",0);approachCheckpoint.addProperty("targetY",116);approachCheckpoint.addProperty("targetZ",0);approaching.restore(approachCheckpoint);
        Vec3 chestCenter=new Vec3(.5,116.5,.5);
        assert !approaching.approachProgress(chestCenter.add(10,0,0)) : "First observation establishes a baseline, not movement";
        for(int i=0;i<200;i++){
            assert !approaching.approachProgress(chestCenter.add(i%2==0?9.8:10.2,0,0)) : "Oscillation around the same location cannot postpone the timeout indefinitely";
            assert !approaching.waitingExpired();
        }
        assert approaching.waitingExpired()&&approaching.telemetry().get("noProgressTicks").getAsInt()==201 : "Motion without useful progress still expires the actual watchdog";
        assert approaching.approachProgress(chestCenter.add(9.7,0,0))&&approaching.telemetry().get("closestOpeningDistance").getAsDouble()==9.7;
        assert approaching.telemetry().get("noProgressTicks").getAsInt()==0 : "Meaningful progress renews the timeout";
        assert !approaching.approachProgress(chestCenter.add(11,0,0))&&!approaching.approachProgress(chestCenter.add(9.7,0,0)) : "Retreating and returning to an old closest point do not advance the scan";
        assert approaching.approachProgress(chestCenter.add(0,9.4,0)) : "Vertical approach is measured as well as horizontal travel";
        assert !approaching.approachProgress(new Vec3(Double.NaN,0,0));
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(net.minecraft.data.registries.VanillaRegistries.createLookup()).forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        batchDeposits();
        var chestState=net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState();
        // Live failure: Baritone stood flush against a chest at the world edge, with open sky above.
        var chest=chestState.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,BlockPos.ZERO).bounds()
            .move(29496179,237,-29130829);
        var feet=new Vec3(29496179.303270917,237,-29130829.237500012);
        double width=(double).6f;
        var body=dev.monocle.client.utils.world.PrinterFlight.body(feet,width,1.8);
        java.util.function.Predicate<net.minecraft.world.phys.AABB> clear=b->!b.intersects(chest);
        assert !dev.monocle.client.utils.world.PrinterFlight.segmentClear(feet,feet.add(0,1.15,0),width+.12,1.8,clear)
            : "The old padding falsely collides with a chest beside the bot";
        assert BotActions.stashTakeoffClear(body,clear) : "Actual standing bounds can take off beside that chest";
        var ceiling=new net.minecraft.world.phys.AABB(body.minX,239.5,body.minZ,body.maxX,240,body.maxZ);
        assert !BotActions.stashTakeoffClear(body,b->!b.intersects(ceiling)) : "Real ceilings still prevent takeoff";
        assert !BotActions.stashTakeoffClear(body.move(0,0,.1),clear) : "Real chest collisions are not ignored";
        assert !dev.monocle.client.utils.world.PrinterFlight.route(feet,feet.add(0,2,0),width,.7,clear).isEmpty()
            : "The first flight segment must not reintroduce the padded chest collision";
        assert BotStashScan.containerTarget(new net.minecraft.world.level.block.entity.ChestBlockEntity(BlockPos.ZERO,chestState));
        assert BotStashScan.containerTarget(new net.minecraft.world.level.block.entity.HopperBlockEntity(BlockPos.ZERO,net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState()));
        assert BotStashResupply.sourceContainer(new net.minecraft.world.level.block.entity.HopperBlockEntity(BlockPos.ZERO,net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState()))
            &&BotStashResupply.sourceContainer(new net.minecraft.world.level.block.entity.ChestBlockEntity(BlockPos.ZERO,chestState))
            &&!BotStashResupply.sourceContainer(new net.minecraft.world.level.block.entity.EnderChestBlockEntity(BlockPos.ZERO,net.minecraft.world.level.block.Blocks.ENDER_CHEST.defaultBlockState()))
            &&!BotStashResupply.sourceContainer(null) : "Scanned hoppers are valid withdrawal sources; absent containers and personal ender chests are not";
        assert BotStashScan.containerTarget(new net.minecraft.world.level.block.entity.EnderChestBlockEntity(BlockPos.ZERO,net.minecraft.world.level.block.Blocks.ENDER_CHEST.defaultBlockState()));
        assert !BotStashScan.containerTarget(null) : "A vanished container must not place the held kit";
        var far=new net.minecraft.world.level.block.entity.ChestBlockEntity(new BlockPos(-16,80,2),chestState);
        var near=new net.minecraft.world.level.block.entity.ChestBlockEntity(new BlockPos(-16,64,0),chestState);
        var outside=new net.minecraft.world.level.block.entity.ChestBlockEntity(new BlockPos(-15,64,0),chestState);
        var echest=new net.minecraft.world.level.block.entity.EnderChestBlockEntity(new BlockPos(-16,64,1),net.minecraft.world.level.block.Blocks.ENDER_CHEST.defaultBlockState());
        var containers=List.of(far,echest,outside,near);
        assert BotStashDeposit.nearestChest(containers,new BlockPos(-16,64,0),new BlockPos(-16,80,2),Vec3.atCenterOf(outside.getBlockPos()),java.util.Set.of()).equals(near.getBlockPos());
        assert BotStashDeposit.nearestChest(containers,new BlockPos(-16,64,0),new BlockPos(-16,80,2),Vec3.atCenterOf(near.getBlockPos()),java.util.Set.of(near.getBlockPos().asLong())).equals(far.getBlockPos())
            : "Destination discovery uses actual loaded chests, inclusive vertical bounds and rejected-full-chest exclusions";
        assert BotStashDeposit.nearestChest(List.of(),BlockPos.ZERO,BlockPos.ZERO,Vec3.ZERO,java.util.Set.of())==null;
        var box=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        box.set(net.minecraft.core.component.DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE,64))));
        JsonObject dedicated=BotStashScan.classify(List.of(box)).get(0).getAsJsonObject();assert dedicated.get("dominant").getAsString().equals("minecraft:stone")&&!dedicated.get("mixed").getAsBoolean();
        JsonObject kit=new JsonObject();kit.addProperty("kitTypeId",dev.monocle.coordinator.StashCatalog.kitTypeId(dedicated));kit.add("kitExemplar",dedicated.get("items").deepCopy());kit.addProperty("incomplete",false);
        assert BotStashScan.matchesKit(box,kit)&&!BotStashScan.matchesKit(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OBSIDIAN),kit);
        box.set(net.minecraft.core.component.DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE,32))));
        assert !BotStashScan.matchesKit(box,kit);kit.addProperty("incomplete",true);assert BotStashScan.matchesKit(box,kit);
        kit.addProperty("incomplete",false);kit.addProperty("includeIncomplete",true);assert BotStashScan.matchesKit(box,kit);
        var differentKit=box.copy();differentKit.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Different kit"));
        assert !BotStashScan.matchesKit(differentKit,kit) : "Include incomplete does not permit a different kit type";
        String kitId=kit.get("kitTypeId").getAsString();
        JsonObject depositObservation=new JsonObject();depositObservation.addProperty("status","observed");depositObservation.add("items",dev.monocle.client.systems.modules.misc.swarm.CrewInventory.manifest(List.of(box)));depositObservation.add("shulkers",BotStashScan.classify(List.of(box)));
        assert dev.monocle.coordinator.StashCatalog.columnOccupant(depositObservation).equals(kitId) : "Column ownership uses the real manifest including shulker contents, not just outer box counts";
        assert BotStashDeposit.compatibleContents(List.of(net.minecraft.world.item.ItemStack.EMPTY,box),kitId)
            &&!BotStashDeposit.compatibleContents(List.of(differentKit),kitId)
            &&!BotStashDeposit.compatibleContents(List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE)),kitId)
            : "Destination preflight allows only empty slots or the exact kit type, including incomplete boxes";
        box.set(net.minecraft.core.component.DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OBSIDIAN,64),new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_SWORD))));
        assert BotStashScan.classify(List.of(box)).get(0).getAsJsonObject().get("mixed").getAsBoolean();
        try(var bytes=BotStashScan.class.getResourceAsStream("BotStashScan.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());var invoked=code.methods().stream().filter(m->m.code().isPresent()).flatMap(m->m.code().get().elementList().stream()).filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).map(i->i.name().stringValue()).toList();
            assert !invoked.contains("handleContainerInput")&&!invoked.contains("startDestroyBlock")&&!invoked.contains("drop") : "Discovery never moves items or destroys containers";
            assert calls(code,"inventory").containsAll(List.of("containerId","finish","getItem"));
            assert calls(code,"tick").containsAll(List.of("openContainer","visibleFrom")) : "Chest openings require a visible face; hopper inspection may target through an adjacent chest";
            assert calls(code,"openContainer").containsAll(List.of("containerTarget","interact"))
                &&!calls(code,"openContainer").contains("getInventory")&&!calls(code,"tick").contains("setSelectedSlot")
                : "Even a full inventory opens existing containers without selecting an empty hand or shuffling kits";
        }
        for(Class<?> type:List.of(BotStashResupply.class,BotStashDeposit.class))try(var bytes=type.getResourceAsStream(type.getSimpleName()+".class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code,"open").equals(List.of("openContainer")) : "Every stash opener shares the full-inventory-safe interaction";
            if(type==BotStashResupply.class)assert calls(code,"tick").contains("sourceContainer")&&calls(code,"navigationTarget").contains("navigationTarget")
                : "Withdrawals validate scanned containers and reuse hopper scan navigation";
            var transfer=calls(code,type==BotStashDeposit.class?"tick":"deposit");
            assert transfer.contains("moveConfirmed")&&!transfer.contains("move") : "Kit deposits share explicit-slot, forced server-confirmation clicks without local prediction";
        }
        try(var bytes=BotActions.class.getResourceAsStream("BotActions.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code,"scanStash").contains("moveToStashContainer")&&calls(code,"moveToStashContainer").contains("moveTo")
                : "Solo and worker stash actions share the hybrid walking/flight route";
            assert calls(code,"resupplyStash").containsAll(List.of("navigationTarget","targetIsHopper","stashInteractionPaused"))
                &&calls(code,"depositStash").contains("stashInteractionPaused") : "Withdrawals use hopper approaches; all transfers yield to eating or missing server response";
            assert calls(code,"flyToStashChest").contains("gliderDurability")
                && calls(code,"flyToStashChest").containsAll(List.of("stashTakeoffClear","getBoundingBox"))
                && !calls(code,"flyToStashChest").contains("stashLandingAvailable")
                && calls(code,"landBeforeHandoff").contains("retreatStep")
                && calls(code,"fail").contains("stashFlightActive") : "Elevated stash work can return along its route without a landing beside the chest";
            assert calls(code,"release").contains("stopStashNavigation")&&calls(code,"disconnected").contains("stopStashNavigation") : "Cancel, pause and disconnect release scan path ownership";
            assert calls(code,"screen").containsAll(List.of("suppressScreen","cancel")) : "Automated container menus never replace the user's screen";
            assert calls(code,"completeStash").containsAll(List.of("landBeforeHandoff","holdStashPosition","recordStashFlight"))
                &&calls(code,"prepareNext").containsAll(List.of("compatibleStashFlight","requestSuspend","tick"))
                : "Flight handoffs preserve steering and the return trail; incompatible steps drive the existing landing cleanup";
            assert calls(code,"scanStash").contains("approachProgress")&&!calls(code,"scanStash").contains("moved")
                : "Scan movement resets require useful closest-approach progress";
        }
        try(var bytes=BotStashDeposit.class.getResourceAsStream("BotStashDeposit.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code,"tick").containsAll(List.of("close","open","requestSync","moveConfirmed"))
                &&calls(code,"inventory").contains("observeContents")
                : "Forced post-placement server contents confirm each exact kit before hoppers can move it onward";
            assert calls(code,"findChest").containsAll(List.of("blockEntities","nearestChest"))&&!calls(code,"findChest").contains("betweenClosedStream")
                : "Choosing the next deposit chest must not rescan every air block in a large stash";
        }
        try(var bytes=BotActions.class.getResourceAsStream("/dev/monocle/client/pathing/BaritoneUtils$StashNavigation.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code,"<init>").stream().filter("forbid"::equals).count()==3 : "Scan navigation forbids mining, placing and inventory rearrangement";
            assert calls(code,"close").containsAll(List.of("getGoal","cancelEverything","forEach","clear")) : "Owned paths stop and temporary settings are restored";
        }
    }
    private static void stashFlightHandoffs(JsonObject plan){
        String world="minecraft:the_nether";
        var previous=plan.deepCopy();previous.addProperty("dimension",world);
        var position=new dev.monocle.coordinator.PlayerObservation.Position(.5,118,.5);
        for(String type:List.of("StashScan","StashResupply","StashDeposit")){
            var next=plan.deepCopy();next.addProperty("type",type);
            assert BotActions.compatibleStashFlight(previous,next,world,position) : "Nearby stash actions share the same flight lease";
            next.addProperty("dimension","minecraft:overworld");assert !BotActions.compatibleStashFlight(previous,next,world,position);
        }
        var far=plan.deepCopy();far.addProperty("minX",100);far.addProperty("maxX",100);
        assert !BotActions.compatibleStashFlight(previous,far,world,position) : "A remote stash must land before /home or travel handoff";
        for(String type:List.of("Tpa","SetProfile","Modules","Wait","Travel","Highway"))assert !BotActions.compatibleStashFlight(previous,json("{type:'"+type+"'}"),world,position);
        assert !BotActions.compatibleStashFlight(null,plan,world,position)&&!BotActions.compatibleStashFlight(previous,null,world,position)
            &&!BotActions.compatibleStashFlight(previous,plan,"minecraft:overworld",position);
        var trail=new java.util.ArrayList<Vec3>();
        assert BotActions.recordStashFlight(trail,Vec3.ZERO)&&BotActions.recordStashFlight(trail,new Vec3(.2,0,0))&&trail.size()==1;
        assert BotActions.recordStashFlight(trail,new Vec3(.5,0,0))&&trail.size()==2;
        assert BotActions.retreatStep(trail.getLast(),trail,1)==0 : "Landing retraces actually flown positions across workflow steps";
        trail.clear();for(int i=0;i<dev.monocle.client.utils.world.PrinterFlight.MAX_NODES;i++)assert BotActions.recordStashFlight(trail,new Vec3(i,0,0));
        assert !BotActions.recordStashFlight(trail,new Vec3(dev.monocle.client.utils.world.PrinterFlight.MAX_NODES,0,0)) : "The return trail stays bounded without discarding its safe origin";
    }
    private static void batchDeposits(){
        var click=dev.monocle.client.utils.player.InvUtils.confirmedClick(10,82);
        assert click.containerId()==10&&click.stateId()==-1&&click.slotNum()==82&&click.buttonNum()==0
            &&click.containerInput()==net.minecraft.world.inventory.ContainerInput.PICKUP&&click.changedSlots().isEmpty()
            &&click.carriedItem()==net.minecraft.network.HashedStack.EMPTY : "Explicit placement forces vanilla's post-click snapshot, without client-predicted slots";
        var first=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        first.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("First"));
        var second=first.copy();second.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Second"));
        var empty=net.minecraft.world.item.ItemStack.EMPTY;
        var deposit=new BotStashDeposit(json("{count:2}"));assert deposit.acceptMenu(10);
        deposit.beginDeposit(2,1,first,10,List.of(first,empty,empty));
        deposit.observeContents(List.of(first,empty,empty),1);
        assert deposit.snapshot().get("pending").getAsBoolean()&&deposit.snapshot().get("confirmed").getAsInt()==0 : "A pre-existing kit cannot confirm the explicit destination";
        assert !deposit.acceptMenu(11) : "A different menu cannot acknowledge an in-flight move";
        try{deposit.beginDeposit(1,2,second,11,List.of(first,empty,empty));throw new AssertionError("Never replay an unconfirmed move");}catch(IllegalStateException expected){}
        try{new BotStashDeposit(json("{count:2}")).restore(deposit.snapshot());throw new AssertionError("Uncertain moves cannot be replayed after restart");}catch(IllegalStateException expected){}
        deposit.observeContents(List.of(first,second,empty),1);
        assert deposit.snapshot().get("confirmed").getAsInt()==0 : "Wrong kit data cannot acknowledge a move";
        deposit.observeContents(List.of(empty,first,empty),1);
        assert deposit.snapshot().get("confirmed").getAsInt()==1&&!deposit.snapshot().get("pending").getAsBoolean() : "The post-placement snapshot verifies the exact destination even if a hopper drained an earlier slot";
        deposit.observeContents(List.of(empty,first,empty),1);
        assert deposit.snapshot().get("confirmed").getAsInt()==1 : "Duplicate snapshots cannot count a kit twice";
        deposit.beginDeposit(1,2,second,12,List.of(empty,first,empty));
        deposit.observeContents(List.of(empty,empty,second),0);
        assert deposit.snapshot().get("confirmed").getAsInt()==2 : "Previously confirmed kits may move onward through hoppers";
        var restored=new BotStashDeposit(json("{count:2}"));restored.restore(deposit.snapshot());assert restored.snapshot().get("confirmed").getAsInt()==2;
        var baseline=BotStashDeposit.copyContents(List.of(first));var original=first.copy();first.setCount(2);
        assert net.minecraft.world.item.ItemStack.matches(baseline.get(0),original) : "Predicted changes cannot mutate a receipt baseline";first.setCount(1);

        var inventory=new net.minecraft.world.entity.player.Inventory(null,new net.minecraft.world.entity.EntityEquipment());
        for(int i=0;i<36;i++)inventory.setItem(i,first.copy());
        var bottom=new net.minecraft.world.SimpleContainer(54);for(int i=0;i<36;i++)bottom.setItem(i,second.copy());
        var upper=new net.minecraft.world.SimpleContainer(54);var crossing=new BotStashDeposit(json("{count:36}"));int carried=36;
        for(var chest:List.of(bottom,upper)){
            var menu=net.minecraft.world.inventory.ChestMenu.sixRows(carried,inventory,chest);
            for(int i=0;i<18;i++){
                int destination=chest==bottom?36+i:i,source=-1;
                for(int slot=54;slot<menu.slots.size();slot++)if(menu.getSlot(slot).hasItem()){source=slot;break;}
                crossing.beginDeposit(carried,destination,menu.getSlot(source).getItem(),i,java.util.stream.IntStream.range(0,54).mapToObj(chest::getItem).toList());
                menu.setCarried(menu.getSlot(source).getItem().copy());menu.getSlot(source).setByPlayer(empty);carried--;
                assert net.minecraft.world.item.ItemStack.matches(menu.getCarried(),first);
                crossing.observeContents(java.util.stream.IntStream.range(0,54).mapToObj(chest::getItem).toList(),carried);
                assert crossing.snapshot().get("pending").getAsBoolean() : "Pickup alone is not a destination receipt";
                menu.getSlot(destination).setByPlayer(menu.getCarried());menu.setCarried(empty);
                assert menu.getCarried().isEmpty();
                crossing.observeContents(java.util.stream.IntStream.range(0,54).mapToObj(chest::getItem).toList(),carried);
            }
        }
        assert crossing.snapshot().get("confirmed").getAsInt()==36&&carried==0&&bottom.getItem(53).getCount()==1&&upper.getItem(17).getCount()==1 : "Explicit deposits cross double-chest layers without a predicted quick-move destination";
    }
    private static List<String> calls(ClassModel type, String method) {
        return type.methods().stream().filter(m -> m.methodName().equalsString(method)).flatMap(m->m.code().orElseThrow().elementList().stream())
            .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).map(call -> call.name().stringValue()).toList();
    }
}
