package dev.monocle.client.systems.bots;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.monocle.coordinator.StashCatalog;
import dev.monocle.client.utils.world.PrinterFlight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.List;

/** Small native boundary/journal/route checks, without constructing a Minecraft window. */
public final class BotActionsTest {
    private static void writeSortBoundaries(){
        BlockPos cell=new BlockPos(0,65,0);assert BotStashScan.unsafeSortHopperLink(cell,cell.below(),net.minecraft.core.Direction.DOWN,java.util.Set.of(cell)):"An external hopper can pull from below without pointing at the chest";
        assert BotStashScan.unsafeSortHopperLink(cell,cell.east(),net.minecraft.core.Direction.WEST,java.util.Set.of(cell));assert !BotStashScan.unsafeSortHopperLink(cell,cell.below(),net.minecraft.core.Direction.DOWN,java.util.Set.of(cell,cell.below()));
        assert BotActions.validate(json("{type:'StashSortPlan',sequence:0}")).has("sequence");
        try{BotActions.validate(json("{type:'StashSortPlan',sequence:-1}"));throw new AssertionError();}catch(IllegalArgumentException expected){}
        JsonObject bounds=json("{name:'Sort',homeName:'sort',minX:0,maxX:8,minY:64,maxY:73,minZ:0,maxZ:4}");
        for(int y=64;y<=73;y++)for(int x=0;x<=8;x++)for(int z=0;z<=4;z++){
            JsonObject p=new JsonObject();p.addProperty("x",x);p.addProperty("y",y);p.addProperty("z",z);int index=BotStashScan.scanCellIndex(bounds,p);
            assert index%9==x&&index/9%5==z&&64+BotStashScan.layerY(index/45,10)==y:"Targeted verification scanned another chest";
        }
        JsonObject scan=bounds.deepCopy();scan.addProperty("type","StashScan");scan.add("scanCells",JsonParser.parseString("[{x:0,y:64,z:0},{x:0,y:64,z:0}]"));try{BotActions.validate(scan);throw new AssertionError("Duplicate targeted cell accepted");}catch(IllegalArgumentException expected){}
    }
    public static void main(String[] args) throws Exception {
        JsonObject rwpFollow = json("{scope:{server:'play.example.org',dimension:'minecraft:the_nether'},action:{type:'dev.monocle.follow.v1',arguments:{target:'00000000-0000-0000-0000-000000000002',radius:2,ticks:100}}}");
        JsonObject nativeFollow = BotActions.validate(dev.monocle.coordinator.RwpWorkerConnection.nativeMovement(rwpFollow));
        assert nativeFollow.get("follow").getAsBoolean() && nativeFollow.get("adaptiveFlight").getAsBoolean()
            && nativeFollow.get("ticks").getAsInt() == 0 : "RWP must reuse native Follow while retaining its own durable timer";
        progressSafety();
        endermanLook();
        stashScan();
        writeSortBoundaries();
        BotScanCoverageTest.run();
        portalTravel();
        successfulTakeoff();
        walkingRouteCache();
        stashEmptySources();
        interleavedEmptySources();
        missingStashSources();
        roundRobinActions();
        singleColumnDeposit();
        delayedDepositAcknowledgement();
        stashHotPaths();
        idleSlotGuards();
        closedSourceMenus();
        bulkTransfers();
        var navigationGoal=new Object();var otherGoal=new Object();
        assert dev.monocle.client.pathing.BaritoneUtils.retainsStashGoal(navigationGoal,navigationGoal,otherGoal);
        assert dev.monocle.client.pathing.BaritoneUtils.retainsStashGoal(navigationGoal,null,navigationGoal) : "Baritone clears its own goal on arrival or path failure; that is not a takeover";
        assert !dev.monocle.client.pathing.BaritoneUtils.retainsStashGoal(navigationGoal,otherGoal,navigationGoal)
            &&!dev.monocle.client.pathing.BaritoneUtils.retainsStashGoal(navigationGoal,null,otherGoal) : "Foreign replacement goals still stop our navigation";
        var baritoneInput=Class.forName("baritone.utils.PlayerMovementInput");
        assert dev.monocle.client.pathing.BaritoneUtils.releasesStashInput(true,false,baritoneInput)
            &&!dev.monocle.client.pathing.BaritoneUtils.releasesStashInput(false,false,baritoneInput)
            &&!dev.monocle.client.pathing.BaritoneUtils.releasesStashInput(true,true,baritoneInput)
            &&!dev.monocle.client.pathing.BaritoneUtils.releasesStashInput(true,false,net.minecraft.client.player.KeyboardInput.class)
            &&!dev.monocle.client.pathing.BaritoneUtils.releasesStashInput(true,false,dev.monocle.client.utils.player.CustomPlayerInput.class)
            : "Only an idle, owned Baritone input may be restored to keyboard input; active or foreign owners are preserved";
        // Check the exact pause type without constructing a Minecraft/Baritone manager.
        try(var in=BotActionsTest.class.getResourceAsStream("/dev/monocle/client/pathing/BaritonePathManager.class")){
            var manager=ClassFile.of().parse(in.readAllBytes());
            assert manager.methods().stream().filter(m->m.methodName().equalsString("isPauseProcess"))
                .flatMap(m->m.code().orElseThrow().elementList().stream())
                .anyMatch(e->e instanceof java.lang.classfile.instruction.TypeCheckInstruction check
                    &&check.opcode()==java.lang.classfile.Opcode.INSTANCEOF
                    &&check.type().asInternalName().equals("dev/monocle/client/pathing/BaritonePathManager$BaritoneProcess"))
                : "Only Monocle's actual pause controller is exempt, not arbitrary temporary processes";
        }
        assert !dev.monocle.client.pathing.BaritonePathManager.isPauseProcess(otherGoal)
            &&!dev.monocle.client.pathing.BaritonePathManager.isPauseProcess(null);
        assert !dev.monocle.client.pathing.BaritoneUtils.foreignStashController(navigationGoal,navigationGoal,true)
            &&!dev.monocle.client.pathing.BaritoneUtils.foreignStashController(navigationGoal,otherGoal,false)
            &&!dev.monocle.client.pathing.BaritoneUtils.foreignStashController(navigationGoal,null,true)
            &&dev.monocle.client.pathing.BaritoneUtils.foreignStashController(navigationGoal,otherGoal,true)
            : "Our temporary eating/combat pause is not a takeover; active unrelated controllers still are";
        assert BotActions.validate(json("{type:'KitTransferPlan',index:128}")).get("index").getAsInt()==128;
        try{BotActions.validate(json("{type:'KitTransferPlan',index:1,targetStashName:'Hijacked'}"));throw new AssertionError("Worker planning cannot redirect a deposit");}catch(IllegalArgumentException expected){}
        assert BotStashResupply.batchSize(27,3,false)==3&&BotStashResupply.batchSize(2,8,false)==2;
        var pickup=new BotStashResupply(json("{picks:[]}"));
        var box=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        JsonObject slotObservation=new JsonObject();BotStashScan.recordSlots(slotObservation,List.of(box,net.minecraft.world.item.ItemStack.EMPTY,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE,1)));
        assert slotObservation.get("slotCount").getAsInt()==3&&slotObservation.get("occupiedSlots").getAsInt()==2 : "Empty slots are free; kits and partial stacks each occupy one physical slot";
        var wrongBox=box.copy();wrongBox.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Different kit"));
        var pickupBefore=List.of(box,net.minecraft.world.item.ItemStack.EMPTY,net.minecraft.world.item.ItemStack.EMPTY);
        pickup.beginWithdrawal(3,0,2,box,100,pickupBefore,false);
        assert !pickup.observeWithdrawal(List.of(net.minecraft.world.item.ItemStack.EMPTY,net.minecraft.world.item.ItemStack.EMPTY,net.minecraft.world.item.ItemStack.EMPTY),3,box,1)
            : "Source removal with the kit still on the cursor is an intermediate receipt, not a failure";
        assert !pickup.observeWithdrawal(List.of(net.minecraft.world.item.ItemStack.EMPTY,net.minecraft.world.item.ItemStack.EMPTY,net.minecraft.world.item.ItemStack.EMPTY),3,net.minecraft.world.item.ItemStack.EMPTY,2)
            : "A negative/stale snapshot waits for verification without replaying the click";
        assert !pickup.observeWithdrawal(List.of(box,net.minecraft.world.item.ItemStack.EMPTY,box),4,net.minecraft.world.item.ItemStack.EMPTY,3)
            &&!pickup.observeWithdrawal(List.of(net.minecraft.world.item.ItemStack.EMPTY,net.minecraft.world.item.ItemStack.EMPTY,wrongBox),4,net.minecraft.world.item.ItemStack.EMPTY,4)
            &&!pickup.observeWithdrawal(List.of(net.minecraft.world.item.ItemStack.EMPTY,net.minecraft.world.item.ItemStack.EMPTY,box),5,net.minecraft.world.item.ItemStack.EMPTY,5)
            : "Source change, exact destination box and exactly one carried gain are all required";
        assert pickup.observeWithdrawal(List.of(net.minecraft.world.item.ItemStack.EMPTY,net.minecraft.world.item.ItemStack.EMPTY,box),4,net.minecraft.world.item.ItemStack.EMPTY,6);
        assert pickup.observeWithdrawal(List.of(box,net.minecraft.world.item.ItemStack.EMPTY,net.minecraft.world.item.ItemStack.EMPTY),3,box,1)
            : "Late snapshots cannot revoke a verified pickup before its receipt is consumed";
        assert pickup.snapshot().getAsJsonObject("verification").get("destinationSlot").getAsInt()==2;
        try{pickup.beginWithdrawal(4,0,2,box,101,pickupBefore,false);throw new AssertionError("An unconsumed pickup cannot be replayed");}catch(IllegalStateException expected){}
        var hopperPickup=new BotStashResupply(json("{picks:[]}"));
        try{hopperPickup.beginWithdrawal(3,0,2,box,100,List.of(box,net.minecraft.world.item.ItemStack.EMPTY,box),true);throw new AssertionError("Pre-existing destination kits cannot confirm a new pickup");}catch(IllegalStateException expected){}
        try{hopperPickup.beginWithdrawal(3,0,2,box,100,List.of(wrongBox,net.minecraft.world.item.ItemStack.EMPTY,net.minecraft.world.item.ItemStack.EMPTY),true);throw new AssertionError("Source must match its server snapshot before sending");}catch(IllegalStateException expected){}
        hopperPickup.beginWithdrawal(3,0,2,box,100,pickupBefore,true);
        assert !hopperPickup.observeWithdrawal(List.of(box,net.minecraft.world.item.ItemStack.EMPTY,net.minecraft.world.item.ItemStack.EMPTY),3,box,1)
            &&!hopperPickup.observeWithdrawal(List.of(box,net.minecraft.world.item.ItemStack.EMPTY,box),3,net.minecraft.world.item.ItemStack.EMPTY,2)
            &&!hopperPickup.observeWithdrawal(List.of(box,net.minecraft.world.item.ItemStack.EMPTY,wrongBox),4,net.minecraft.world.item.ItemStack.EMPTY,3)
            &&!hopperPickup.observeWithdrawal(List.of(box,net.minecraft.world.item.ItemStack.EMPTY,box),5,net.minecraft.world.item.ItemStack.EMPTY,4)
            : "Refill does not weaken exact destination, +1 count or cursor checks";
        assert hopperPickup.observeWithdrawal(List.of(box,net.minecraft.world.item.ItemStack.EMPTY,box),4,net.minecraft.world.item.ItemStack.EMPTY,5)
            : "Identical hopper refill cannot invalidate an authoritative pickup into a previously empty carried slot";
        assert hopperPickup.snapshot().getAsJsonObject("verification").get("sourceMayRefill").getAsBoolean();
        assert hopperPickup.observeWithdrawal(List.of(box,net.minecraft.world.item.ItemStack.EMPTY,net.minecraft.world.item.ItemStack.EMPTY),3,box,1)
            : "Late packets cannot revoke the hopper pickup receipt";
        withdrawalReconciliation(box,wrongBox);
        silentWithdrawalRecovery(box,wrongBox);
        unappliedWithdrawalRecovery(box,wrongBox);
        incidentalKits(box,wrongBox);
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
        JsonObject occupied=exhausted.deepCopy();occupied.addProperty("index",0);
        var shortened=new BotStashResupply(repeated);shortened.restore(occupied);
        assert shortened.finishFullBatch()&&shortened.snapshot().get("batchLimit").getAsInt()==1
            &&shortened.workflowResult().get("inventoryFull").getAsBoolean()
            &&!shortened.workflowResult().get("sourceExhausted").getAsBoolean()
            &&!shortened.workflowResult().has("sourceIssue") : "An incidental item ends the confirmed partial load, not the source or job";
        var fullResumed=new BotStashResupply(repeated);fullResumed.restore(shortened.snapshot());
        assert fullResumed.workflowResult().equals(shortened.workflowResult())&&repeated.get("count").getAsInt()==36
            : "The shortened batch survives reconnects without reducing later loads";
        var noRoom=new BotStashResupply(repeated);
        assert !noRoom.finishFullBatch() : "No confirmed kits is not a deliverable partial batch";
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
        assert !noPickup.approachNeedsFlight();
        for(int i=0;i<59;i++)noPickup.approachTimedOut(9.5);
        assert noPickup.approachNeedsFlight() : "A stuck walking source gets a flight fallback before its approach deadline";
        assert noPickup.approachDiagnostic().get("idleTicks").getAsInt()>=60
            &&noPickup.approachDiagnostic().get("closestDistance").getAsDouble()==9.5 : "Checkpoint diagnostics expose stalled approach progress";
        noPickup.approachTimedOut(9);assert !noPickup.approachNeedsFlight() : "Useful closer progress resets the fallback clock";
        var localTrip=repeated.deepCopy();localTrip.addProperty("pickIndex",0);
        assert BotActions.localStashTrip(localTrip,new dev.monocle.coordinator.PlayerObservation.Position(200,0,0));
        assert !BotActions.localStashTrip(localTrip,new dev.monocle.coordinator.PlayerObservation.Position(300,0,0));
        localTrip.getAsJsonArray("picks").get(0).getAsJsonObject().addProperty("x",1000);
        assert !BotActions.localStashTrip(localTrip,new dev.monocle.coordinator.PlayerObservation.Position(0,0,0)) : "Withdrawal distance uses the actual source, not a nearby cuboid edge";
        var atStash=new dev.monocle.coordinator.PlayerObservation.Position(1000,0,0);
        assert BotActions.stashHomeReached("minecraft:the_end","minecraft:the_end",localTrip,atStash);
        assert !BotActions.stashHomeReached("minecraft:the_end","minecraft:overworld",localTrip,atStash)
            &&!BotActions.stashHomeReached("minecraft:the_end","minecraft:the_end",localTrip,new dev.monocle.coordinator.PlayerObservation.Position(0,0,0))
            : "Home arrival needs both the right dimension and the actual source location";
        assert !BotActions.stashHomeTimedOut(15_000,15_000)&&!BotActions.stashHomeTimedOut(75_000,15_000)
            &&BotActions.stashHomeTimedOut(75_001,15_000) : "A delayed home gets 60 seconds beyond warmup, not permission to navigate from the old stash";
        assert !BotActions.stashHomeRetry(1,75_000,15_000)&&BotActions.stashHomeRetry(1,75_001,15_000)
            &&BotActions.stashHomeRetry(2,75_001,15_000)&&BotActions.stashHomeRetry(3,75_001,15_000)
            &&!BotActions.stashHomeRetry(0,75_001,15_000)&&!BotActions.stashHomeRetry(3,75_001,0)
            : "Interrupted homes retry only after a sent command's arrival deadline, without a terminal attempt cap";
        assert BotActions.stashHomeWaiting(1,75_001,15_000,31_000)&&BotActions.stashHomeWaiting(3,150_000,0,31_000)
            &&BotActions.stashHomeWaiting(0,10_000,15_000,31_000)&&BotActions.stashHomeWaiting(0,10_000,0,31_000)
            &&!BotActions.stashHomeWaiting(0,32_000,0,31_000) : "Warmup, cooldown and pending home retries do not trip the generic stall watchdog";
        var homeWatch=new dev.monocle.coordinator.ActionProgress();
        for(long time=1_000;time<=600_000;time+=1_000){
            boolean waiting=BotActions.stashHomeWaiting(3,time,15_000,31_000);
            assert homeWatch.observe(new dev.monocle.coordinator.ActionProgress.Observation("StashResupply","Home arrival / retry","","",Double.MAX_VALUE,0,waiting,false),time)==dev.monocle.coordinator.ActionProgress.Decision.Continue;
        }
        assert !homeWatch.paused()&&homeWatch.report().get("progressAgeMs").getAsLong()==0 : "A lost home can keep retrying without a false 120-second inspection pause";
        assert BotActions.stashHomeArrivalUsesCooldown(true,1)&&BotActions.stashHomeArrivalUsesCooldown(false,1)
            &&BotActions.stashHomeArrivalUsesCooldown(true,0)&&!BotActions.stashHomeArrivalUsesCooldown(false,0)
            : "Confirmed arrivals after reconnect renew the real cooldown; ordinary local trips do not";
        int openingAttempts=0;for(int tick=1;tick<=200;tick++)if(BotStashDeposit.retryOpening(tick,sourceChest))openingAttempts++;
        assert openingAttempts==10 : "Opening retries keep the existing one-second interval";
        try{BotStashDeposit.retryOpening(201,sourceChest);throw new AssertionError("An unopened deposit chest cannot retry indefinitely");}
        catch(IllegalStateException expected){assert expected.getMessage().contains(sourceChest.toShortString())&&expected.getMessage().contains("kits are carried");}
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
            assert calls(code, "handleCorrection").contains("resetFlightRoutes") && calls(code, "disconnected").contains("resetFlightRoutes")
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
        System.out.println("Bot action checks passed: validation, exact drop planning/confirmation, no resend on recovery, portal-safe bounded travel and native authority guards.");
    }
    private static void progressSafety() throws Exception {
        var pickup = new BotStashResupply(JsonParser.parseString("{picks:[]}").getAsJsonObject());
        var sent=BotStashResupply.class.getDeclaredField("withdrawSent");sent.setAccessible(true);sent.setInt(pickup,1);
        assert !pickup.progress().navigation() && pickup.progress().expected().equals("Server inventory confirmation") : "Uncertain withdrawal cannot authorize a navigation replan";
        var deposit = deposit(new JsonObject());
        var stage=BotStashDeposit.class.getDeclaredField("pendingStage");stage.setAccessible(true);stage.setInt(deposit,1);
        var finding=BotStashDeposit.class.getDeclaredField("pendingFinding");finding.setAccessible(true);finding.set(deposit,null);
        assert !deposit.progress().navigation() && deposit.progress().expected().equals("Server deposit reconciliation") : "Deposit reconciliation remains owned by its guarded executor";
        finding.set(deposit,new JsonObject());
        assert !deposit.progress().navigation() && deposit.progress().expected().contains("acknowledgement") : "A pending destination lease must not be reset or stolen";
        try(var in=BotActionsTest.class.getResourceAsStream("BotActions.class")){
            var model=ClassFile.of().parse(in.readAllBytes());
            var watch=model.methods().stream().filter(m->m.methodName().equalsString("watchProgress")).findFirst().orElseThrow();
            var calls=watch.code().orElseThrow().elementList().stream().filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).map(c->c.name().stringValue()).toList();
            assert calls.contains("crewNudge") && calls.contains("resetFlightRoutes") && calls.contains("stopStashNavigation")&&calls.contains("deferSourceApproach");
            assert !calls.contains("handleContainerInput") && !calls.contains("sendCommand") && !calls.contains("stop") && !calls.contains("setActive") : "The watchdog cannot replay side effects or disable flight in midair";
        }
    }

    private static void walkingRouteCache() {
        var cache=new BotActions.WalkingRoute();
        var start=new dev.monocle.client.systems.modules.world.HighwayPlan.Cell(0,1,0);
        var goal=new dev.monocle.client.systems.modules.world.HighwayPlan.Cell(2,1,0);
        var step=new dev.monocle.client.systems.modules.world.HighwayPlan.Cell(1,1,0);
        int[] searches={0};
        java.util.function.Supplier<List<dev.monocle.client.systems.modules.world.HighwayPlan.Cell>> search=()->{searches[0]++;return List.of(start,step,goal);};
        for(int tick=100;tick<105;tick++)assert cache.get(start,goal,tick,c->true,search).size()==3;
        assert searches[0]==1 : "Stationary travel must not repeat BFS every tick";
        cache.get(start,goal,105,c->true,search);assert searches[0]==2;
        cache.get(start,goal,106,c->!c.equals(step),search);assert searches[0]==3 : "Blocked next steps replan immediately";
        cache.get(step,goal,106,c->true,search);assert searches[0]==4 : "Moving to another cell invalidates the route";
        cache.get(step,start,106,c->true,search);assert searches[0]==5 : "Moving targets replan immediately";
        cache.reset();cache.get(step,start,106,c->true,search);assert searches[0]==6;
        cache.get(step,start,10,c->true,search);assert searches[0]==7 : "A reset tick counter cannot retain an old-world route";
        var empty=new BotActions.WalkingRoute();int[] failed={0};
        for(int tick=0;tick<5;tick++)assert empty.get(start,goal,tick,c->true,()->{failed[0]++;return List.of();}).isEmpty();
        assert failed[0]==1 : "Failed searches also have a bounded retry interval";
    }

    private static void endermanLook() throws Exception {
        var forward = new Vec3(0, 0, 8);
        var below = new Vec3(0, -8, 0);
        var above = new Vec3(0, 8, 0);
        var safe = dev.monocle.client.systems.modules.world.EndermanLook.safeRotation(Vec3.ZERO, 0, 0, List.of(forward));
        assert safe.x == 90 && safe.y == 0 : "Late navigation gaze is redirected without turning the worker";
        safe = dev.monocle.client.systems.modules.world.EndermanLook.safeRotation(Vec3.ZERO, 0, 0, List.of(forward, below));
        assert safe.x == -90 && safe.y == 0 : "A flying worker cannot look down at a neutral Enderman below it";
        safe = dev.monocle.client.systems.modules.world.EndermanLook.safeRotation(Vec3.ZERO, 0, 0, List.of(forward, below, above));
        assert safe.x == 0 && safe.y == 45 : "Both vertical directions blocked still permits a safe sideways gaze";
        safe = dev.monocle.client.systems.modules.world.EndermanLook.safeRotation(Vec3.ZERO, 90, 20, List.of(forward));
        assert safe.x == 20 && safe.y == 90 : "Safe camera/navigation aim is untouched";
        safe = dev.monocle.client.systems.modules.world.EndermanLook.safeRotation(Vec3.ZERO, 0, 0, List.of(new Vec3(.3, 0, 8)));
        assert safe.x == 90 : "Vanilla gaze is a cone, not exact equality";
        var surrounded = new java.util.ArrayList<Vec3>(List.of(forward, below, above));
        for (int turn = 0; turn < 8; turn++) surrounded.add(Vec3.directionFromRotation(0, turn * 45).scale(8));
        assert dev.monocle.client.systems.modules.world.EndermanLook.safeRotation(Vec3.ZERO, 0, 0, surrounded) == null
            : "Never choose an unsafe fallback when every tested direction is occupied";
        assert dev.monocle.client.systems.modules.world.EndermanLook.safeRotation(Vec3.ZERO, 0, 0, List.of(Vec3.ZERO)).x == 0;
        safe = dev.monocle.client.systems.modules.world.EndermanLook.safeRotation(new Vec3(0, 8, 8), 0, 90, List.of(forward));
        assert safe.x == -90 : "Retained server aim must be rechecked when movement brings an Enderman under the worker";
        try (var bytes = dev.monocle.client.systems.modules.world.EndermanLook.class.getResourceAsStream("EndermanLook.class")) {
            var code = ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code, "onSend").containsAll(List.of("hasRotation", "getX", "getY", "getZ", "safeRotation", "monocle$setPitch", "monocle$setYaw", "sendReplacement"))
                : "The actual outgoing rotation and position, not an early-tick camera snapshot, must be guarded";
            assert calls(code, "sendReplacement").containsAll(List.of("monocle$getTag", "monocle$setTag", "sendSilently"))
                : "Replacing a rotationless movement packet preserves its tags and avoids recursive sends";
            assert !calls(code, "onSend").contains("setXRot") && !calls(code, "onSend").contains("setYRot")
                : "Enderman avoidance cannot redirect the client camera or Baritone steering";
        }
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
        assert BotActions.stashFlightDurabilitySafe(64,false)&&!BotActions.stashFlightDurabilitySafe(63,false)
            : "Stash takeoff needs 64 durability, not the old 161";
        assert BotActions.stashFlightDurabilitySafe(63,true)&&BotActions.stashFlightDurabilitySafe(33,true)
            &&!BotActions.stashFlightDurabilitySafe(32,true) : "Ongoing flight lands at 32 without reapplying the takeoff threshold";
        assert !BotActions.stashFlightDurabilitySafe(0,false)&&!BotActions.stashFlightDurabilitySafe(0,true)
            &&BotActions.stashFlightDurabilitySafe(Integer.MAX_VALUE,false) : "Missing/broken gliders cannot fly; unbreakable gliders can";
        BlockPos highChest=new BlockPos(0,12,0);
        Vec3 center=Vec3.atCenterOf(highChest);
        assert BotActions.stashFinalApproach(center.add(32,0,0),highChest)
            &&!BotActions.stashFinalApproach(center.add(32.01,0,0),highChest)
            &&!BotActions.stashFinalApproach(center.add(0,33,0),highChest)
            &&!BotActions.stashFinalApproach(center,null)
            : "Only the bounded final interaction route can waive the cruise buffer, including vertical distance";
        assert BotActions.scanHover(new Vec3(.5,16.05,.5),highChest) : "Hopper hover leaves three blocks above the full target block";
        assert !BotActions.scanHover(new Vec3(1.5,15,.5),highChest) : "Do not hover far above the opening";
        assert BotActions.stashFlightGoal(new Vec3(1.5,11,.5),highChest,false,true) : "Visible chest faces are reachable from below or beside a high chest";
        assert !BotActions.stashFlightGoal(new Vec3(1.5,11,.5),highChest,true,true) : "Hoppers still require an overhead approach";
        for(Vec3 from:List.of(new Vec3(100,20,0),new Vec3(-100,20,0),new Vec3(0,20,100),new Vec3(0,20,-100))){
            Vec3 goal=BotActions.stashCruiseGoal(from,highChest);
            assert goal.y>=from.y&&goal.y>=16 : "Cruise does not dive toward a low chest";
            assert !PrinterFlight.cruiseEnvelope(PrinterFlight.body(goal,.6,.6)).intersects(new net.minecraft.world.phys.AABB(highChest))
                : "Long-distance goals stop outside the chest clearance envelope";
        }
        List<Vec3> openingRoute=List.of(new Vec3(3.5,12,.5),new Vec3(2.5,12,.5),new Vec3(1.5,12,.5));
        Vec3 almostOpen=new Vec3(1.6,12,.5);
        assert BotActions.stashFlightStep(almostOpen,openingRoute,2,false)==2
            : "Stopping 0.1 blocks short must not consume the final waypoint while the chest face is still hidden";
        assert BotActions.stashFlightStep(almostOpen,openingRoute,2,true)==3
            : "An actual reachable opening can finish the approach without exact coordinate matching";
        assert BotActions.stashFlightStep(new Vec3(2.6,12,.5),openingRoute,1,false)==2
            : "Intermediate corners retain their movement tolerance";
        assert BotActions.stashFlightStep(almostOpen,openingRoute,3,false)==3;
        assert BotActions.stashFlightStep(Vec3.ZERO,List.of(),0,false)==0;
        assert !BotActions.stashFlightStalled(almostOpen,almostOpen,10,69)&&BotActions.stashFlightStalled(almostOpen,almostOpen,10,70)
            : "Three seconds of confirmed no movement triggers recovery, not unbounded identical replans";
        assert !BotActions.stashFlightStalled(almostOpen,almostOpen.add(.5,0,0),10,100)&&!BotActions.stashFlightStalled(null,almostOpen,10,100)
            : "Real progress and an uninitialized watcher cannot count as a stall";
        assert BotActions.stashFlightStalled(almostOpen,almostOpen,Integer.MAX_VALUE-30,Integer.MIN_VALUE+30) : "Watchdog survives tick rollover";
        assert BotActions.stashApproachSpeed(new Vec3(12,12,.5),new Vec3(4,12,.5),highChest,2)==1
            : "Clear travel within the 32-block approach route need not crawl at opening speed";
        assert BotActions.stashApproachSpeed(new Vec3(5,12,.5),new Vec3(3,12,.5),highChest,2)==.28
            &&BotActions.stashApproachSpeed(new Vec3(12,12,.5),new Vec3(11,12,.5),highChest,2)==.28
            : "Final chest approaches and nearby route corners retain the precise speed";
        assert BotActions.stashApproachSpeed(new Vec3(12,12,.5),new Vec3(4,12,.5),highChest,.1)==.1
            : "Approach travel never exceeds the user's slower ElytraFly setting";
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
        Vec3 underLip=new Vec3(.5,.8,-.4);AABB chestLip=new AABB(0,0,0,1,1,1);
        java.util.function.Predicate<AABB> lipClear=body->!body.intersects(chestLip);
        var lipRoute=BotActions.nearbyLandingRoute(underLip,List.of(BlockPos.ZERO).iterator(),1,.6,.6,lipClear,
            point->PrinterFlight.landingBelow(point,.6,.6,1.8,4,p->p.equals(BlockPos.ZERO)?net.minecraft.world.phys.shapes.Shapes.block():net.minecraft.world.phys.shapes.Shapes.empty(),lipClear)!=null);
        assert lipRoute!=null&&lipRoute.lift().equals(underLip.add(0,2,0))&&lipRoute.target().equals(new Vec3(.5,2.8,.5))
            : "A worker below a chest lip rises through checked air before crossing onto support";
        assert BotActions.nearbyLandingRoute(underLip,List.of(BlockPos.ZERO).iterator(),1,.6,.6,
            body->lipClear.test(body)&&!body.intersects(new AABB(-1,1.5,-1,2,2,2)),point->true)==null
            : "Landing recovery cannot rise through a ceiling or cross the chest lip";
        assert BotActions.stashFlightNeeded(249,237,false)&&!BotActions.stashFlightNeeded(239,237,false)
            &&BotActions.stashFlightNeeded(239,237,true)&&BotActions.stashFlightNeeded(237,249,false)
            &&!BotActions.stashFlightNeeded(237,239,false) : "Lower and higher chests can switch to flight; nearby rows still walk";
        assert !BotActions.stashCruiseNeeded(4,false,false,true)&&BotActions.stashCruiseNeeded(4.01,false,false,true)
            &&BotActions.stashCruiseNeeded(20,false,false,true)
            : "Stash travel prefers flight beyond interaction distance, including short trips";
        assert BotActions.stashFlightRetryAllowed(0)&&BotActions.stashFlightRetryAllowed(2)&&!BotActions.stashFlightRetryAllowed(3)
            : "Three failed flight attempts select walking instead of an unlimited jumping loop";
        assert BotActions.stashWalkingRetry(true,true,false,false,true) : "Stalled walking fallback can renew the flight budget";
        assert BotActions.stashGlideRequest(3,false)&&BotActions.stashGlideRequest(7,false)
            &&!BotActions.stashGlideRequest(3,true)&&!BotActions.stashGlideRequest(2,false)&&!BotActions.stashGlideRequest(4,false)
            : "Airborne workers retry glide confirmation at a bounded cadence without requiring another jump";
        assert !BotActions.stashWalkingRetry(true,true,true,false,true)&&!BotActions.stashWalkingRetry(true,true,false,true,true)
            &&!BotActions.stashWalkingRetry(true,false,false,false,true)&&!BotActions.stashWalkingRetry(true,true,false,false,false)
            &&!BotActions.stashWalkingRetry(false,true,false,false,true) : "Pauses, collision recovery, usable walking and missing flight equipment cannot trigger a flight handoff";
        assert BotActions.stashCruiseNeeded(40,true,false,true)&&BotActions.stashCruiseNeeded(64,false,true,true)
            : "Crossing the takeoff threshold does not interrupt launch or flight";
        assert !BotActions.stashCruiseNeeded(200,false,false,false)
            : "Missing or unusable flight equipment retains the walking path";
        Vec3 cruiseStart=new Vec3(.5,238,.5),cruiseGoal=new Vec3(200.5,238,.5);
        var cruiseRoute=new dev.monocle.client.utils.world.PrinterFlight.LocalRoute();
        Vec3 cruiseStep=cruiseRoute.next(cruiseStart,cruiseGoal,0,.6,.7,b->true);
        assert cruiseStep.equals(cruiseStart.add(8,0,0))
            &&dev.monocle.client.utils.world.PrinterFlight.safeVelocity(cruiseStart,cruiseStep,1,.6,.7,b->true).equals(new Vec3(1,0,0))
            : "A 200-block trip uses bounded, swept cruise segments rather than the 32-block chest approach search";
        assert dev.monocle.client.utils.world.PrinterFlight.safeVelocity(cruiseStart,cruiseStep,.1,.6,.7,b->true).length()<=.10000001
            : "Cruise steering can respect a slower configured ElytraFly ceiling";
        assert Math.abs(BotActions.stashCruiseSpeed(0,10.789473684210531,true,0,1.5)-.15)<1e-9
            : "Stash cruise inherits ElytraFly's acceleration step instead of taking off at full speed";
        double cruiseSpeed=0;
        for(int i=0;i<20;i++)cruiseSpeed=BotActions.stashCruiseSpeed(cruiseSpeed,10.789473684210531,true,0,1.5);
        assert cruiseSpeed==2.5&&BotActions.stashCruiseSpeed(2.5,.4,true,0,1.5)==.4
            : "Cruise caps at 50 BPS and immediately honors a lowered configured ceiling";
        assert BotActions.stashCruiseSpeed(0,10,false,0,1.5)==2.5
            &&BotActions.stashCruiseSpeed(0,.1,false,0,1.5)==.1;
        try(var stream=BotActions.class.getResourceAsStream("BotActions.class")){
            var code=ClassFile.of().parse(stream.readAllBytes());
            assert calls(code,"brakeFlight").contains("requestAutopilot")
                &&calls(code,"flyToStashChest").contains("requestConfiguredAutopilot")
                : "Cruise uses its checked higher ceiling while braking retains the ordinary autopilot";
            for(String method:List.of("brakeFlight","resetFlightRoutes","resetStashFlightProgress"))
                assert code.methods().stream().filter(m->m.methodName().equalsString(method)).allMatch(m->m.code().orElseThrow().elementStream()
                    .anyMatch(e->e instanceof java.lang.classfile.instruction.FieldInstruction field
                        &&field.opcode()==java.lang.classfile.Opcode.PUTFIELD&&field.name().equalsString("stashCruiseSpeed"))
                    ||calls(code,method).contains("resetFlightRoutes"))
                    : "Braking, corrections and new travel reset the acceleration ramp: "+method;
        }
        assert BotStashScan.openingHit(new Vec3(2,14,.5),highChest).getDirection()==net.minecraft.core.Direction.EAST : "Stacked chests open from the exposed side";
        assert java.util.stream.IntStream.range(0,5).map(i->BotStashScan.layerY(i,5)).boxed().toList().equals(List.of(0,4,3,2,1)) : "Lazy scan visits bottom, top, then downward";
        assert !BotStashScan.includeContainer(true,true)&&BotStashScan.includeContainer(true,false)&&BotStashScan.includeContainer(false,true) : "Lazy scans skip hoppers; full audits retain them";
        assert BotStashScan.shouldCloseHiddenMenu(3,0,true,false) : "A delayed second menu must close even when its ID differs from the captured menu";
        assert !BotStashScan.shouldCloseHiddenMenu(3,0,false,false) : "Do not close a menu the scan never opened";
        assert !BotStashScan.shouldCloseHiddenMenu(3,0,true,true) : "Do not close a visible user container";
        JsonObject plan=BotActions.validate(json("{\"type\":\"StashScan\",\"name\":\"Depot\",\"homeName\":\"depot\",\"minX\":0,\"maxX\":0,\"minY\":116,\"maxY\":116,\"minZ\":0,\"maxZ\":0}"));
        stashFlightHandoffs(plan);
        var scanDirectory=java.nio.file.Files.createTempDirectory("monocle-scan-writer-");
        JsonObject storedObservation=json("{x:0,y:116,z:0,status:'observed',block:'minecraft:chest',reason:'',items:{'minecraft:stone':64},shulkers:[]}");
        java.util.concurrent.CompletableFuture<Void> localSave;
        synchronized(dev.monocle.coordinator.StashCatalog.class){
            localSave=BotStashScan.saveLocal(scanDirectory,"server\nnether",plan,storedObservation);
            assert !localSave.isDone() : "Catalog contention must not block the caller/game thread";
            storedObservation.getAsJsonObject("items").addProperty("minecraft:stone",1);
        }
        localSave.get(10,java.util.concurrent.TimeUnit.SECONDS);
        var storedCatalog=dev.monocle.coordinator.StashCatalog.get(scanDirectory,"Local","server\nnether","Depot");
        assert storedCatalog.getAsJsonObject("containers").getAsJsonObject("0,116,0").getAsJsonObject("items").get("minecraft:stone").getAsInt()==64 : "The background writer owns an immutable observation copy";
        var invalidObservation=storedObservation.deepCopy();invalidObservation.addProperty("x",1);
        try{BotStashScan.saveLocal(scanDirectory,"server\nnether",plan,invalidObservation).get(10,java.util.concurrent.TimeUnit.SECONDS);throw new AssertionError("Invalid async observation accepted");}
        catch(java.util.concurrent.ExecutionException expected){assert expected.getCause() instanceof IllegalArgumentException;}
        assert dev.monocle.coordinator.StashCatalog.get(scanDirectory,"Local","server\nnether","Depot").equals(storedCatalog) : "Failed writes preserve the last durable catalog";
        JsonObject export=new JsonObject(),exportMessage=dev.monocle.coordinator.TaskWire.message("stash-definition");export.addProperty("owner","host:6969#selector");export.addProperty("worker",java.util.UUID.randomUUID().toString());exportMessage.add("stash",plan);exportMessage.addProperty("scope","server\nnether");export.add("message",exportMessage);
        BotStashScan.saveLocal(scanDirectory,"server\nnether",plan,storedObservation,export).get(10,java.util.concurrent.TimeUnit.SECONDS);
        assert dev.monocle.coordinator.StashExports.next(scanDirectory,"host:6969#selector",export.get("worker").getAsString())!=null : "A solo scan checkpoints its offline export before it may advance";
        var failedDirectory=java.nio.file.Files.createTempDirectory("monocle-export-write-failure-");java.nio.file.Files.createFile(failedDirectory.resolve("stash-export-outbox-sqlite-v1"));
        try{BotStashScan.saveLocal(failedDirectory,"server\nnether",plan,storedObservation,export).get(10,java.util.concurrent.TimeUnit.SECONDS);throw new AssertionError("Failed export persisted only locally");}catch(java.util.concurrent.ExecutionException expected){}
        assert dev.monocle.coordinator.StashCatalog.get(failedDirectory,"Local","server\nnether","Depot").isEmpty() : "Scan cannot claim success when its export journal failed";
        java.nio.file.Files.delete(failedDirectory.resolve("stash-export-outbox-sqlite-v1"));java.nio.file.Files.delete(failedDirectory);
        dev.monocle.coordinator.StashStore.close(scanDirectory);
        try(var files=java.nio.file.Files.walk(scanDirectory)){for(var path:files.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}
        var scan=new BotStashScan(plan);JsonObject checkpoint=scan.snapshot();checkpoint.addProperty("cursor",1);checkpoint.addProperty("discovered",1);checkpoint.addProperty("observed",1);
        checkpoint.addProperty("targetX",0);checkpoint.addProperty("targetY",116);checkpoint.addProperty("targetZ",0);
        checkpoint.add("pending",json("{\"x\":0,\"y\":116,\"z\":0,\"status\":\"observed\",\"block\":\"minecraft:chest\",\"reason\":\"\",\"items\":{\"minecraft:stone\":64},\"shulkers\":[]}"));
        scan.restore(checkpoint);assert !scan.done()&&scan.hasPending();scan.acknowledge(2);assert scan.pending()!=null;
        var copied=scan.pending();copied.getAsJsonObject("items").addProperty("minecraft:stone",1);
        for(int i=0;i<1000;i++)assert scan.hasPending();
        assert scan.pending().getAsJsonObject("items").get("minecraft:stone").getAsInt()==64 : "Cheap pending checks never expose mutable observations";
        var restored=new BotStashScan(plan);restored.restore(scan.snapshot());restored.acknowledge(1);assert restored.done()&&!restored.hasPending()&&restored.pending()==null;
        stashScanOrdering();
        stashScanPipeline();
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
        var requestedMenu=BotStashScan.class.getDeclaredField("beforeMenu");requestedMenu.setAccessible(true);requestedMenu.setInt(approaching,0);
        assert approaching.approachProgress(chestCenter.add(0,9,0)) : "An unanswered opening request must not disable useful approach progress";
        assert !approaching.approachProgress(new Vec3(Double.NaN,0,0));
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(net.minecraft.data.registries.VanillaRegistries.createLookup()).forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        stashBlockedLids();
        personalSupplyCaches();
        deliveryHandoffs();
        stashHopperColumns();
        batchDeposits();
        exactDepositEvidence();
        var chestState=net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState();
        // Reproduce the live failure using synthetic world-edge coordinates, never an operator's stash location.
        var chest=chestState.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,BlockPos.ZERO).bounds()
            .move(29999979,237,-29999829);
        var feet=new Vec3(29999979.303270917,237,-29999829.237500012);
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
        kitVariants(box,kit);
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
        String otherKit=dev.monocle.coordinator.StashCatalog.kitTypeId(BotStashScan.classify(List.of(differentKit)).get(0).getAsJsonObject());
        JsonObject category=dev.monocle.coordinator.KitCategories.definition("selected","Selected kits","Kits",java.util.List.of(kitId,otherKit));
        assert BotStashDeposit.compatibleContents(List.of(box,differentKit),kit,category);
        assert !BotStashDeposit.compatibleContents(List.of(box,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE)),kit,category);
        JsonObject stone=dev.monocle.coordinator.KitCategories.definition("stone","Stone","Items",java.util.List.of("minecraft:stone"));
        assert BotStashDeposit.compatibleContents(List.of(box,differentKit),kit,stone):"Contents categories ignore box names";
        assert BotStashDeposit.compatibleContents(List.of(box,differentKit),java.util.Set.of(kitId,otherKit))
            &&!BotStashDeposit.compatibleContents(List.of(box,differentKit),java.util.Set.of(kitId))
            &&!BotStashDeposit.compatibleContents(List.of(box,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE)),java.util.Set.of(kitId,otherKit))
            : "Shared columns accept only explicitly listed boxes, never unrelated loose stock";
        box.set(net.minecraft.core.component.DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OBSIDIAN,64),new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_SWORD))));
        assert !BotStashDeposit.compatibleContents(List.of(box),kit,stone):"Incidental building blocks do not qualify a mixed kit";
        assert BotStashScan.classify(List.of(box)).get(0).getAsJsonObject().get("mixed").getAsBoolean();
        try(var bytes=BotStashScan.class.getResourceAsStream("BotStashScan.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());var invoked=code.methods().stream().filter(m->m.code().isPresent()).flatMap(m->m.code().get().elementList().stream()).filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).map(i->i.name().stringValue()).toList();
            assert !invoked.contains("handleContainerInput")&&!invoked.contains("startDestroyBlock")&&!invoked.contains("drop") : "Discovery never moves items or destroys containers";
            assert calls(code,"ownsRead").containsAll(List.of("suppressScreen","chestHalves","contains"))
                : "A scanner owns only its active physical container, including either double-chest half";
            assert calls(code,"inventory").containsAll(List.of("containerId","finish","getItem"));
            assert calls(code,"tick").containsAll(List.of("openContainer","visibleFrom")) : "Chest openings require a visible face; hopper inspection may target through an adjacent chest";
            var tickCalls=calls(code,"tick");
            assert tickCalls.indexOf("blockedChestLid")>=0&&tickCalls.indexOf("blockedChestLid")<tickCalls.indexOf("infer")
                &&tickCalls.indexOf("blockedChestLid")<tickCalls.indexOf("waitingExpired")
                : "Known blocked lids skip before lazy inference, pathing or opening watchdog waits";
            assert calls(code,"openContainer").containsAll(List.of("containerTarget","interact"))
                &&!calls(code,"openContainer").contains("getInventory")&&!calls(code,"tick").contains("setSelectedSlot")
                : "Even a full inventory opens existing containers without selecting an empty hand or shuffling kits";
        }
        try(var bytes=BotStashObserver.class.getResourceAsStream("BotStashObserver.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            var sent=calls(code,"sent");assert sent.contains("ownsScanRead")&&sent.indexOf("ownsScanRead")<sent.indexOf("geometry")
                : "Scanner-owned opens are not independently republished by passive observation";
        }
        for(Class<?> type:List.of(BotStashResupply.class,BotStashDeposit.class))try(var bytes=type.getResourceAsStream(type.getSimpleName()+".class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code,"open").equals(List.of("openContainer")) : "Every stash opener shares the full-inventory-safe interaction";
            if(type==BotStashResupply.class)assert calls(code,"tick").contains("sourceContainer")&&calls(code,"navigationTarget").contains("navigationTarget")
                : "Withdrawals validate scanned containers and reuse hopper scan navigation";
            if(type==BotStashResupply.class)assert calls(code,"withdraw").containsAll(List.of("sameContents","sourceMayRefill","beginWithdrawal","moveSlotsConfirmed"))
                &&!calls(code,"withdraw").contains("shiftClick") : "Source pickups use a reconciled snapshot and the shared explicit-slot confirmed move, never predicted shift-clicks";
            if(type==BotStashResupply.class)assert calls(code,"tick").indexOf("settleWithdrawal")<calls(code,"tick").indexOf("sourceContainer")
                &&calls(code,"settleWithdrawal").containsAll(List.of("needsWithdrawalSnapshot","canReopenWithdrawal","beginWithdrawalReopen","close","open","canReselectWithdrawal","reselectWithdrawal","requestSync"))
                &&calls(code,"inventory").contains("acceptMenu") : "Pending pickups hold position and retain their deadline; retries need a fresh bound menu before source reselection";
            if(type==BotStashResupply.class)assert calls(code,"settleWithdrawal").indexOf("deferUnappliedWithdrawal")<calls(code,"settleWithdrawal").indexOf("reselectWithdrawal")
                : "Exhausted, authoritatively unapplied pickups defer before another click is attempted";
            var transfer=calls(code,type==BotStashDeposit.class?"tick":"deposit");
            assert transfer.contains("moveConfirmed")&&!transfer.contains("move") : "Kit deposits share explicit-slot, forced server-confirmation clicks without local prediction";
        }
        try(var bytes=BotActions.class.getResourceAsStream("BotActions.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code,"scanStash").contains("moveToStashContainer")&&calls(code,"moveToStashContainer").contains("walkToStashContainer")&&calls(code,"walkToStashContainer").contains("moveTo")
                : "Solo and worker stash actions share the hybrid walking/flight route";
            assert calls(code,"scanStash").containsAll(List.of("walkingLookAhead","walkToStashContainer"))&&!calls(code,"walkToStashContainer").contains("flyToStashChest")
                : "Scan read-ahead can preserve walking but cannot initiate a flight during an outstanding read";
            assert calls(code,"scanStash").containsAll(List.of("planCoverage","coverageReadSafe"))&&calls(code,"moveToStashContainer").containsAll(List.of("coverageStop","rejectCoverage"))
                &&calls(code,"flyToStashChest").containsAll(List.of("route","routeToInteraction","rejectCoverage"))
                : "Production coverage uses checked walking/flight and retains the single-chest fallback";
            assert calls(code,"prepareStashHome").containsAll(List.of("stashHomeReached","stashHomeTimedOut","holdStashPosition"))
                &&calls(code,"tick").indexOf("prepareStashHome")<calls(code,"tick").indexOf("resupplyStash")
                : "Every stash action gates inventory and navigation on observed home arrival";
            assert calls(code,"prepareStashHome").containsAll(List.of("stashHomeRetry","hasChunk","getTimeSinceLastTick","recordStashHomeUse"))
                &&calls(code,"disconnected").contains("resetStashHome")&&calls(code,"restore").contains("resetStashHome")
                &&!calls(code,"resetStashHome").contains("start")
                : "Restart recovery rechecks travel against a loaded world without resetting or replaying native inventory work";
            assert calls(code,"watchProgress").contains("stashHomeWaiting") : "Stash home retry waiting reaches the production watchdog";
            assert calls(code,"prepareStashHome").stream().filter("stashHomeArrivalUsesCooldown"::equals).count()==2
                &&calls(code,"prepareStashHome").stream().filter("recordStashHomeUse"::equals).count()==3
                : "Both normal and reconnected home arrivals update the server-wide cooldown";
            assert calls(code,"moveToStashContainer").contains("stashCruiseNeeded")
                &&calls(code,"flyToStashChest").containsAll(List.of("next","safeVelocity","routeToInteraction","stashFlightStalled","escapeRoute"))
                : "Every stash action shares long-distance local flight and precise final chest approach";
            assert calls(code,"moveToStashContainer").containsAll(List.of("stashWalkingRetry","paused","stopStashNavigation","stashFlightStalled","resetStashFlightProgress"))
                : "All stash action types can recover idle walking without stealing paused or unreleased Baritone movement";
            assert calls(code,"flyToStashChest").contains("stashFlightFailed")
                &&calls(code,"stashFlightFailed").contains("stashFlightRetryAllowed")
                &&calls(code,"maintainStashClearance").contains("stashFlightFailed")
                : "Takeoff, missing opening routes and clearance failures all use the bounded flight retry budget";
            assert calls(code,"snapshot").contains("diagnostic") : "Walking goal/executor diagnostics survive into checkpoints";
            assert calls(code,"flyToStashChest").contains("stashFinalApproach")
                &&calls(code,"maintainStashClearance").containsAll(List.of("stashFinalApproach","clearBody"))
                : "The clearance maintainer cannot lift a collision-checked final approach back out of opening reach";
            assert calls(code,"maintainStashClearance").contains("stashFlightWidth")
                : "Clearance recovery and cruise routing must use the same padded player width";
            assert calls(code,"flyToStashChest").indexOf("resetStashFlightProgress")<calls(code,"flyToStashChest").indexOf("jumpFromGround")
                &&calls(code,"resetStashFlightProgress").contains("reset")
                : "Takeoff establishes the current destination; changing targets discards old cruise and escape paths";
            assert calls(code,"resupplyStash").containsAll(List.of("navigationTarget","targetIsHopper","stashInteractionPaused"))
                &&calls(code,"depositStash").contains("stashInteractionPaused") : "Withdrawals use hopper approaches; all transfers yield to eating or missing server response";
            assert calls(code,"flyToStashChest").contains("gliderDurability")
                && calls(code,"flyToStashChest").containsAll(List.of("stashTakeoffClear","getBoundingBox","stashFlightStep","getEyePosition","visibleFrom"))
                && !calls(code,"flyToStashChest").contains("stashLandingAvailable")
                && calls(code,"landBeforeHandoff").contains("retreatStep")
                && calls(code,"fail").contains("stashFlightActive") : "Elevated stash work can return along its route without a landing beside the chest";
            assert List.of("tick","moveToStashContainer","flyToStashChest").stream()
                .allMatch(method->calls(code,method).contains("stashFlightDurabilitySafe"))
                : "Landing, cruise eligibility and chest approaches share the same durability policy";
            assert calls(code,"release").contains("stopStashNavigation")&&calls(code,"disconnected").contains("stopStashNavigation") : "Cancel, pause and disconnect release scan path ownership";
            assert calls(code,"acquireMovement").indexOf("stopStashNavigation")<calls(code,"acquireMovement").indexOf("movementBusy")
                &&calls(code,"acquireMovement").containsAll(List.of("brakeFlight","getClass","fail"))
                : "Worker steering first releases its own walking path, waits for safe cancellation, and diagnoses genuine input replacement";
            assert calls(code,"start").contains("resetStashFlightProgress")&&calls(code,"disconnected").contains("resetStashFlightProgress")
                &&!calls(code,"packet").contains("resetStashFlightProgress") : "Server corrections invalidate routes without erasing stalled-motion evidence";
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
            assert calls(code,"tick").containsAll(List.of("settleDeposit","sameContents","reconcileInterruptedDeposit"))&&calls(code,"settleDeposit").containsAll(List.of("canReopen","beginReopen","close","open","canRetry","retryDeposit","moveConfirmed"))
                &&calls(code,"inventory").contains("recordVerification")
                : "Stalled deposits use a fresh-menu barrier and exact inventory reconciliation before a bounded retry";
            assert calls(code,"findChest").containsAll(List.of("blockEntities","nearestChest"))&&!calls(code,"findChest").contains("betweenClosedStream")
                : "Choosing the next deposit chest must not rescan every air block in a large stash";
            assert calls(code,"findChest").containsAll(List.of("hopperColumn","top"))&&calls(code,"tick").contains("waitingForDrain")
                : "Hopper-fed deposits select the top and wait for drainage, not lower-chest fallback";
        }
        try(var bytes=BotStashResupply.class.getResourceAsStream("BotStashResupply.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code,"tick").stream().filter("sourceApproachProblem"::equals).count()==4
                : "Chunk, movement, opening and contents timeouts all use guarded unread-source recovery";
            assert calls(code,"tick").containsAll(List.of("hopperColumn","chooseSourceChest"))&&calls(code,"chooseSourceChest").containsAll(List.of("nextSourceChest","recheckEmptyOutlet"))&&!calls(code,"tick").contains("skipKnownEmpty")&&calls(code,"withdraw").containsAll(List.of("waitingForRefill","sourceRefillBudget","skipEmptySourceColumn","advanceSourceChest","recheckEmptyOutlet","requestSync","recordDeferredSource","nextSource","rememberEmptySource","remove"))
                : "Withdrawals skip settled empty chains but preserve upper fallback for mixed or uncertain outlets and server-confirmed clicks";
        }
        try(var bytes=BotActions.class.getResourceAsStream("BotActions.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code,"start").contains("homeCooldownKey")&&!calls(code,"stop").contains("clear")&&!calls(code,"disconnected").contains("clear")
                &&calls(code,"bindTask").contains("bindEmptySources")
                : "Task-scoped empty memory survives /home suspension, cleanup and resume";
            try(var runtime=BotRuntime.class.getResourceAsStream("BotRuntime.class")){
                assert calls(ClassFile.of().parse(runtime.readAllBytes()),"control").contains("bindTask") : "Accepted runtime ownership binds the cache to its task";
            }
        }
        try(var bytes=BotActions.class.getResourceAsStream("/dev/monocle/client/pathing/BaritoneUtils$StashNavigation.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code,"<init>").stream().filter("forbid"::equals).count()==3 : "Scan navigation forbids mining, placing and inventory rearrangement";
            assert calls(code,"moveTo").containsAll(List.of("retainsStashGoal","mostRecentGoal","mostRecentInControl")) : "Cleared goals use Baritone's real last goal";
            assert code.methods().stream().filter(m->m.methodName().stringValue().startsWith("lambda$moveTo$")).anyMatch(m->calls(code,m.methodName().stringValue()).contains("foreignStashController"))
                &&calls(code,"moveTo").contains("paused")&&code.methods().stream().filter(m->m.methodName().stringValue().startsWith("lambda$paused$")).anyMatch(m->calls(code,m.methodName().stringValue()).contains("isPauseProcess")) : "Production navigation distinguishes our temporary pause from an unrelated controller";
            assert calls(code,"release").contains("retainsStashGoal")&&code.methods().stream().filter(m->m.methodName().stringValue().startsWith("lambda$release$")).anyMatch(m->calls(code,m.methodName().stringValue()).contains("foreignStashController")) : "Cleanup releases our paused/cleared goal without cancelling an unrelated controller";
            assert calls(code,"release").containsAll(List.of("getGoal","cancelEverything","forEach","clear","hasPath","getInProgress")) : "Owned paths and calculations stop before temporary settings are restored";
            assert calls(code,"release").containsAll(List.of("clearAllKeys","releasesStashInput"))
                : "Owned completed paths also clear residual forced keys before the worker acquires flight input";
            assert calls(code,"diagnostic").containsAll(List.of("getCurrent","getPosition","movements","isInputForcedDown","isInGoal"))
                &&calls(code,"status").containsAll(List.of("getCurrent","getPosition","getDest","isInputForcedDown"))
                : "Host walking details identify the current ascent/jump, while checkpoints retain goal and path progress";
        }
    }

    private static void stashBlockedLids(){
        var states=new java.util.HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        var air=net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        var world=new net.minecraft.world.level.BlockGetter(){
            public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos p){return null;}
            public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos p){return states.getOrDefault(p,air);}
            public net.minecraft.world.level.material.FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
            public int getMinY(){return -64;}
            public int getHeight(){return 384;}
        };
        var chest=net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState();BlockPos p=new BlockPos(0,64,0);
        states.put(p,chest);
        states.put(p.above(),net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());
        assert BotStashScan.blockedChestLid(world,p).equals(p.above());
        for(var allowed:List.of(net.minecraft.world.level.block.Blocks.AIR,net.minecraft.world.level.block.Blocks.CHEST,
            net.minecraft.world.level.block.Blocks.HOPPER,net.minecraft.world.level.block.Blocks.GLASS,net.minecraft.world.level.block.Blocks.OAK_SLAB)){
            states.put(p.above(),allowed.defaultBlockState());assert BotStashScan.blockedChestLid(world,p)==null : "Non-blocking lid: "+allowed;
        }
        var type=net.minecraft.world.level.block.ChestBlock.TYPE;
        var left=chest.setValue(type,net.minecraft.world.level.block.state.properties.ChestType.LEFT);
        BlockPos other=p.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(left));
        states.put(p,left);states.put(other,chest.setValue(type,net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
        states.put(other.above(),net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());
        assert BotStashScan.blockedChestLid(world,p).equals(other.above()) : "The other half's lid blocks the entire double chest";
        states.put(p.above(),net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());states.put(other.above(),air);
        assert BotStashScan.blockedChestLid(world,other).equals(p.above()) : "Check double chests from either half";
        states.put(p.above(),air);states.put(other,air);states.put(other.above(),net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());
        assert BotStashScan.blockedChestLid(world,p)==null : "An absent partner cannot falsely block a surviving chest";
        states.put(p,net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState());states.put(p.above(),net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());
        assert BotStashScan.blockedChestLid(world,p)==null : "Hoppers have no chest lid";
    }
    private static void interleavedEmptySources() throws Exception {
        var bottom=new BlockPos(0,64,0);var chain=new BotStashScan.HopperColumn(List.of(bottom,bottom.above(2),bottom.above(4)));
        var action=json("{name:'Source',kitTypeId:'00000000-0000-0000-0000-000000000001',count:36,transferAll:true,depositMode:'Carry',picks:[{x:0,y:64,z:0,resource:'minecraft:blue_shulker_box'}]}");
        var empty=List.of(net.minecraft.world.item.ItemStack.EMPTY);
        var remembered=new java.util.LinkedHashMap<String,Long>();var work=new BotStashResupply(action,remembered,"scope");
        assert work.refreshSourceColumn(chain)&&work.sourceRefillBudget(empty,100,20)==16;
        for(int tick=0;tick<=16;tick++)work.waitingForRefill(16);
        assert !work.skipEmptySourceColumn(empty,chain.refillTicks())&&remembered.isEmpty()
            : "A short outlet wait cannot declare upstream storage empty";
        assert work.advanceSourceChest()&&work.target().equals(bottom.above(2))&&work.sourceRefillBudget(empty,120,20)==0;
        assert work.advanceSourceChest()&&work.target().equals(chain.top())&&work.sourceRefillBudget(empty,125,20)==0
            : "Verified empty upper chests advance without another per-chest refill delay";
        assert !work.advanceSourceChest()&&work.recheckEmptyOutlet()&&work.target().equals(bottom)&&!work.recheckEmptyOutlet()
            : "The final outlet is explicitly reopened exactly once, even though its earlier empty snapshot is remembered";
        assert work.sourceRefillBudget(empty,140,20)==chain.refillTicks()&&work.waitingForRefill(chain.refillTicks())
            &&!work.skipEmptySourceColumn(empty,chain.refillTicks()) : "Scanning time overlaps propagation but never shortens its remaining safety window";
        assert work.sourceRefillBudget(empty,140,10)==2*chain.refillTicks()
            &&!work.skipEmptySourceColumn(empty,2*chain.refillTicks()) : "Slow server ticks still extend outlet verification";
        var saved=work.snapshot();var restored=new BotStashResupply(action);restored.restore(saved);
        var columnField=BotStashResupply.class.getDeclaredField("sourceColumn");columnField.setAccessible(true);columnField.set(restored,chain);
        var targetField=BotStashResupply.class.getDeclaredField("target");targetField.setAccessible(true);
        assert restored.chooseSourceChest()&&restored.target().equals(bottom)&&restored.nextSourceChest(chain)==null
            : "Recovery must select the final outlet even when every source chest already has an empty receipt";
        assert restored.sourceRefillBudget(empty,1_000,20)==chain.refillTicks()&&restored.waitingForRefill(chain.refillTicks())
            &&!restored.skipEmptySourceColumn(empty,chain.refillTicks()) : "Restore keeps the outlet obligation but starts a conservative new tick clock";
        assert work.sourceRefillBudget(empty,100+chain.refillTicks(),20)==chain.refillTicks()
            &&!work.waitingForRefill(chain.refillTicks())&&work.skipEmptySourceColumn(empty,chain.refillTicks())&&remembered.size()==3;
        var invalid=saved.deepCopy();invalid.addProperty("emptyOutletPending",false);
        try{new BotStashResupply(action).restore(invalid);throw new AssertionError("Orphan outlet recheck accepted");}catch(IllegalArgumentException expected){}
        invalid=saved.deepCopy();invalid.addProperty("index",1);
        try{new BotStashResupply(action).restore(invalid);throw new AssertionError("Outlet obligation hidden past source-list end");}catch(IllegalArgumentException expected){}
        var mixed=new BotStashResupply(action);assert mixed.refreshSourceColumn(chain);
        assert mixed.sourceRefillBudget(List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PISTON)),100,20)==0
            &&mixed.advanceSourceChest()&&mixed.sourceRefillBudget(empty,110,20)==0&&!mixed.recheckEmptyOutlet()
            : "A mixed outlet also reaches empty upper chests immediately, without claiming the blocked column empty";
        for(String mode:List.of("fixed","local","echest")){
            var criteria=action.deepCopy();
            if(mode.equals("fixed"))criteria.addProperty("transferAll",false);
            if(mode.equals("local"))criteria.addProperty("requireLocal",true);
            if(mode.equals("echest"))criteria.addProperty("depositMode","EnderChest");
            var legacy=new BotStashResupply(criteria);targetField.set(legacy,bottom);assert legacy.refreshSourceColumn(chain);
            assert legacy.sourceRefillBudget(empty,100,20)==(mode.equals("local")?16:chain.refillTicks())
                &&!legacy.recheckEmptyOutlet()&&!legacy.snapshot().get("emptyOutletPending").getAsBoolean()
                : "Other transfer policies and crew leases retain their existing timing";
            try{legacy.restore(saved);throw new AssertionError("Outlet checkpoint accepted outside drain policy");}catch(IllegalArgumentException expected){}
        }
        var pending=new BotStashResupply(action);assert pending.refreshSourceColumn(chain);pending.sourceRefillBudget(empty,100,20);
        var box=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        pending.beginWithdrawal(0,0,1,box,101,List.of(box,net.minecraft.world.item.ItemStack.EMPTY),true);
        try{pending.recheckEmptyOutlet();throw new AssertionError("Uncertain inventory move abandoned for outlet recheck");}catch(IllegalStateException expected){}
        try(var bytes=BotStashResupply.class.getResourceAsStream("BotStashResupply.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code,"withdraw").contains("resetEmptyOutletCheck")&&calls(code,"advanceSourcePick").contains("resetEmptyOutletCheck")
                : "Confirmed pickups and changing source candidates discard the old empty-column window";
        }
    }
    private static void stashEmptySources(){
        var remembered=new java.util.LinkedHashMap<String,Long>();
        var firstTask=java.util.UUID.randomUUID();
        var bound=BotActions.bindEmptySources(remembered,null,firstTask);
        remembered.put("server\nworld\n42",System.currentTimeMillis()+60_000);
        assert BotActions.bindEmptySources(remembered,bound,firstTask).equals(firstTask)&&remembered.size()==1
            : "Deposit, /home suspension and resuming the same task preserve learned physical empties";
        var secondTask=java.util.UUID.randomUUID();
        assert BotActions.bindEmptySources(remembered,bound,secondTask).equals(secondTask)&&remembered.isEmpty()
            : "Starting a different task retires the previous task's empty-source hints";
        remembered.put("preserved",1L);
        try{BotActions.bindEmptySources(remembered,secondTask,null);throw new AssertionError("Missing task identity accepted");}catch(NullPointerException expected){}
        assert remembered.size()==1 : "Invalid task binding cannot clear existing evidence";
        remembered.clear();
        var action=json("{name:'Source',kitTypeId:'00000000-0000-0000-0000-000000000001',count:36,transferAll:true,depositMode:'Carry',picks:[{x:0,y:64,z:0,resource:'minecraft:blue_shulker_box'}]}");
        var bottom=new BlockPos(0,64,0);var chain=new BotStashScan.HopperColumn(List.of(bottom,bottom.above(2)));
        long now=System.currentTimeMillis();
        var first=new BotStashResupply(action,remembered,"server\nworld");assert first.refreshSourceColumn(chain);
        for(int tick=0;tick<=chain.refillTicks();tick++)first.waitingForRefill(chain.refillTicks());
        assert !first.skipEmptySourceColumn(List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PISTON)),chain.refillTicks())&&remembered.isEmpty()
            : "A chest with other kits or items is not an empty chest";
        assert first.skipEmptySourceColumn(List.of(net.minecraft.world.item.ItemStack.EMPTY),chain.refillTicks())&&remembered.size()==2;
        var other=action.deepCopy();other.addProperty("kitTypeId","00000000-0000-0000-0000-000000000002");other.getAsJsonArray("picks").get(0).getAsJsonObject().addProperty("resource","minecraft:red_shulker_box");
        var next=new BotStashResupply(other,remembered,"server\nworld");
        assert next.nextSourceChest(chain)==null : "Another kit and another pickup action reuse the same physical empties";
        assert new BotStashResupply(other,remembered,"server\nother-world").nextSourceChest(chain).equals(bottom)
            &&new BotStashResupply(other,remembered,"other-server\nworld").nextSourceChest(chain).equals(bottom) : "Coordinates from another server or dimension never suppress a source";
        var leased=other.deepCopy();leased.addProperty("requireLocal",true);
        assert new BotStashResupply(leased,remembered,"server\nworld").nextSourceChest(chain).equals(bottom) : "Leased crew pickups still verify their exact source";
        for(String flag:List.of("sourceBlocked","sourceUncertain")){
            var saved=next.snapshot();saved.addProperty(flag,true);
            var uncertain=new BotStashResupply(other,remembered,"server\nworld");uncertain.restore(saved);
            assert uncertain.nextSourceChest(chain).equals(bottom) : "Changed or mixed chains ignore stale empty-source shortcuts";
        }
        var end=next.snapshot();end.addProperty("index",1);
        var verify=new BotStashResupply(other,remembered,"server\nworld");verify.restore(end);
        assert !verify.result().get("sourceExhausted").getAsBoolean()&&!verify.finishSourceSearch()&&verify.snapshot().get("index").getAsInt()==0
            &&verify.nextSourceChest(chain).equals(bottom)&&verify.detail().contains("Verifying cached empty") : "A cache-only zero result triggers actual verification before reporting exhaustion";
        var rechecking=verify.snapshot();var resumed=new BotStashResupply(other,remembered,"server\nworld");resumed.restore(rechecking);
        assert resumed.nextSourceChest(chain).equals(bottom) : "Checkpoint recovery cannot skip a required fresh verification";
        rechecking.addProperty("index",1);var done=new BotStashResupply(other,remembered,"server\nworld");done.restore(rechecking);
        assert done.finishSourceSearch()&&done.result().get("sourceExhausted").getAsBoolean() : "One actual empty verification finishes without looping on the cache";
        first.rememberEmptySource(bottom,now);
        assert first.sourceKnownEmpty(bottom,now+30*60_000L-1)&&!first.sourceKnownEmpty(bottom,now+30*60_000L) : "Restocked sources become eligible when empty memory expires";
        var bounded=new java.util.LinkedHashMap<String,Long>();var large=new BotStashResupply(action,bounded,"large");
        for(int i=0;i<4097;i++)large.rememberEmptySource(bottom.offset(i,0,0),now);
        assert bounded.size()==4096&&!large.sourceKnownEmpty(bottom,now)&&large.sourceKnownEmpty(bottom.offset(4096,0,0),now) : "Large stashes evict the oldest hint rather than growing an unbounded cache";
    }
    private static void missingStashSources() throws Exception {
        var action=json("{name:'Depot',count:36,kitTypeId:'00000000-0000-0000-0000-000000000001',transferAll:true,depositMode:'Carry',picks:[{x:0,y:64,z:0,resource:'minecraft:shulker_box'},{x:1,y:64,z:0,resource:'minecraft:shulker_box'}]}");
        BlockPos bottom=new BlockPos(0,64,0),top=bottom.above(2);
        var missing=new BotStashResupply(action);assert missing.refreshSourceColumn(new BotStashScan.HopperColumn(List.of(bottom)));
        missing.skipMissingSource("minecraft:air");
        assert missing.snapshot().get("index").getAsInt()==1&&!missing.result().has("sourceIssue")&&missing.result().get("picked").getAsInt()==0
            : "A missing source advances without failing or inventing a pickup";
        var restored=new BotStashResupply(action);restored.restore(missing.snapshot());
        assert restored.snapshot().get("index").getAsInt()==1 : "Restart does not rewind past the skipped source";
        assert missing.refreshSourceColumn(new BotStashScan.HopperColumn(List.of(bottom.offset(1,0,0))));missing.skipMissingSource("minecraft:obsidian");
        assert missing.finishSourceSearch()&&missing.result().get("sourceExhausted").getAsBoolean() : "All vanished sources terminate an until-empty search cleanly";
        var chain=new BotStashResupply(action);assert chain.refreshSourceColumn(new BotStashScan.HopperColumn(List.of(bottom,top)));
        chain.skipMissingSource("minecraft:air");
        assert chain.target().equals(top)&&chain.snapshot().get("index").getAsInt()==0 : "A disappeared outlet does not skip the remaining connected chest";
        var partial=chain.snapshot();partial.getAsJsonArray("receipt").add(json("{slot:0,items:{'minecraft:obsidian':64}}"));
        var collected=new BotStashResupply(action);collected.restore(partial);assert collected.refreshSourceColumn(new BotStashScan.HopperColumn(List.of(top)));
        collected.skipMissingSource("minecraft:air");assert collected.result().get("picked").getAsInt()==1&&!collected.result().has("sourceIssue") : "Confirmed pickups survive a missing later source";
        var unread=new BotStashResupply(action);assert unread.refreshSourceColumn(new BotStashScan.HopperColumn(List.of(bottom)));
        assert unread.deferSourceApproach("No useful approach progress after 200 ticks")&&unread.snapshot().get("index").getAsInt()==1
            &&unread.result().get("sourceDeferred").getAsBoolean()&&!unread.sourceKnownEmpty(bottom,System.currentTimeMillis())
            : "An unread timed-out source is deferred, not reported empty, and later sources remain eligible";
        var resumedUnread=new BotStashResupply(action);resumedUnread.restore(unread.snapshot());
        assert resumedUnread.result().equals(unread.result()) : "Source deferrals survive reconnect without losing their cursor";
        assert resumedUnread.refreshSourceColumn(new BotStashScan.HopperColumn(List.of(bottom.offset(1,0,0))));
        assert resumedUnread.deferSourceApproach("No path")&&resumedUnread.finishSourceSearch()
            &&!resumedUnread.result().get("sourceExhausted").getAsBoolean() : "All deferred sources are unresolved, never successful exhaustion";
        for(String timeout:List.of("Container did not open after 200 ticks","Container did not send contents after 200 ticks")){
            for(int picked:List.of(0,2)){
                var saved=new BotStashResupply(action).snapshot();
                for(int i=0;i<picked;i++)saved.getAsJsonArray("receipt").add(json("{slot:0,items:{'minecraft:obsidian':64}}"));
                var timedOut=new BotStashResupply(action);timedOut.restore(saved);
                assert timedOut.refreshSourceColumn(new BotStashScan.HopperColumn(List.of(bottom)));
                assert timedOut.deferSourceApproach(timeout)&&!timedOut.finishSourceSearch()
                    &&timedOut.snapshot().get("index").getAsInt()==1&&timedOut.result().get("picked").getAsInt()==picked
                    &&!timedOut.result().has("sourceIssue")&&!timedOut.sourceKnownEmpty(bottom,System.currentTimeMillis())
                    : "Unread containers preserve confirmed kits and continue to later sources instead of ending the batch";
                var reconnected=new BotStashResupply(action);reconnected.restore(timedOut.snapshot());
                assert reconnected.result().equals(timedOut.result())&&reconnected.result().getAsJsonArray("deferredSources").get(0).getAsString().contains(timeout)
                    : "Opening timeout evidence and confirmed pickups survive reconnect";
            }
        }
        for(String policy:List.of("fixed","crew","echest")){
            var strict=action.deepCopy();
            if(policy.equals("fixed"))strict.addProperty("transferAll",false);
            if(policy.equals("crew"))strict.addProperty("requireLocal",true);
            if(policy.equals("echest"))strict.addProperty("depositMode","EnderChest");
            var guarded=new BotStashResupply(strict);
            var guardedTarget=BotStashResupply.class.getDeclaredField("target");guardedTarget.setAccessible(true);guardedTarget.set(guarded,bottom);
            assert !guarded.deferSourceApproach("No path") : "Approach deferral must not weaken "+policy+" requirements";
        }
        var pending=new BotStashResupply(action);assert pending.refreshSourceColumn(new BotStashScan.HopperColumn(List.of(bottom)));
        var box=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        pending.beginWithdrawal(1,0,1,box,100,List.of(box,net.minecraft.world.item.ItemStack.EMPTY),false);
        assert !pending.deferSourceApproach("No path") : "Approach timeout cannot erase an uncertain pickup";
        try{pending.skipMissingSource("minecraft:air");throw new AssertionError("Missing source erased an uncertain pickup");}catch(IllegalStateException expected){}
        assert pending.snapshot().get("withdrawPending").getAsBoolean()&&pending.snapshot().get("index").getAsInt()==0;
        for(String field:List.of("depositBefore","discardSlot")){
            var blocked=new BotStashResupply(action);assert blocked.refreshSourceColumn(new BotStashScan.HopperColumn(List.of(bottom)));
            var pendingField=BotStashResupply.class.getDeclaredField(field);pendingField.setAccessible(true);pendingField.setInt(blocked,0);
            assert !blocked.deferSourceApproach("No path") : "Approach timeout cannot erase an uncertain "+field;
            try{blocked.skipMissingSource("minecraft:air");throw new AssertionError("Missing source erased an uncertain "+field);}catch(IllegalStateException expected){}
        }
        var held=new BotStashResupply(action);assert held.refreshSourceColumn(new BotStashScan.HopperColumn(List.of(bottom)));
        var cursor=BotStashResupply.class.getDeclaredField("serverCursor");cursor.setAccessible(true);cursor.set(held,box);
        assert !held.deferSourceApproach("Container did not send contents after 200 ticks")&&held.snapshot().get("index").getAsInt()==0
            : "An unread container cannot be skipped while the server cursor still holds a kit";
        assert BotStashResupply.missingSourceBlock(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())
            &&BotStashResupply.missingSourceBlock(net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState())
            &&!BotStashResupply.missingSourceBlock(net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState())
            &&!BotStashResupply.missingSourceBlock(net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState())
            : "A block entity still loading is not a vanished storage block";
    }
    private static void stashHopperColumns(){
        var states=new java.util.HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        var air=net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        var world=new net.minecraft.world.level.BlockGetter(){
            public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos p){return null;}
            public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos p){return states.getOrDefault(p,air);}
            public net.minecraft.world.level.material.FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
            public int getMinY(){return -64;}
            public int getHeight(){return 384;}
        };
        var chest=net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState();
        var hopper=net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState();
        var bounds=json("{minX:0,maxX:12,minY:64,maxY:90,minZ:0,maxZ:12}");
        BlockPos bottom=new BlockPos(0,64,0);
        // Rotate the diagonal storage, then test vertical stacks with an intervening hopper.
        for(var flow:List.of(net.minecraft.core.Direction.NORTH,net.minecraft.core.Direction.WEST,net.minecraft.core.Direction.DOWN)){
            states.clear();BlockPos top=bottom;
            for(int i=0;i<10;i++){
                top=flow==net.minecraft.core.Direction.NORTH?bottom.offset(0,i,i):flow==net.minecraft.core.Direction.WEST?bottom.offset(i,i,0):bottom.above(2*i);
                states.put(top,chest);if(i>0)states.put(top.below(),hopper.setValue(net.minecraft.world.level.block.HopperBlock.FACING,flow));
            }
            for(BlockPos p:new java.util.ArrayList<>(states.keySet()))if(states.get(p).getBlock() instanceof net.minecraft.world.level.block.ChestBlock){
                var chain=BotStashScan.hopperColumn(world,p,bounds);
                assert chain.fed()&&chain.bottom().equals(bottom)&&chain.top().equals(top)&&chain.chests().size()==10
                    : "All mapped heights resolve to the same physical bottom/top, never geometric proximity";
            }
            for(BlockPos p:new java.util.ArrayList<>(states.keySet()))if(states.get(p).getBlock() instanceof net.minecraft.world.level.block.HopperBlock){
                var routed=BotStashScan.hopperColumn(world,p,bounds);
                assert routed.bottom().equals(bottom)&&routed.top().equals(top) : "Historical hopper stock is searched in the connected outlet chest, not just the hopper";
                var pick=json("{x:"+p.getX()+",y:"+p.getY()+",z:"+p.getZ()+",slot:3,resource:'minecraft:shulker_box'}");
                var actual=BotStashResupply.sourcePick(pick,routed.bottom(),routed.fed());
                assert actual.get("y").getAsInt()==bottom.getY()&&actual.get("dynamic").getAsBoolean() : "Routed hopper withdrawals record the actual chest and reselect its live slot";
            }
            var chain=BotStashScan.hopperColumn(world,top,bounds);
            var refill=new BotStashResupply(json("{picks:[]}"));var drain=deposit(json("{count:1}"));
            for(int tick=0;tick<chain.refillTicks();tick++)assert refill.waitingForRefill(chain.refillTicks())&&drain.waitingForDrain(chain.refillTicks());
            assert !refill.waitingForRefill(chain.refillTicks())&&!drain.waitingForDrain(chain.refillTicks()) : "Empty outlets/full inputs never wait forever";
            assert chain.refillTicks()>=9*8 : "Refill allows propagation through every hopper, not just one transfer tick";
            assert BotStashResupply.emptySourceRefillTicks(chain,20)==chain.refillTicks()
                &&BotStashResupply.emptySourceRefillTicks(chain,10)==2*chain.refillTicks()
                &&BotStashResupply.emptySourceRefillTicks(chain,100)==chain.refillTicks()
                &&BotStashResupply.emptySourceRefillTicks(chain,Double.NaN)==chain.refillTicks() : "Drainage inference accounts for slow server ticks and remains finite with unknown rates";
            assert BotStashResupply.sourceRefillTicks(chain,List.of(net.minecraft.world.item.ItemStack.EMPTY))==16
                &&BotStashResupply.sourceRefillTicks(chain,List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PISTON)))==0
                &&BotStashResupply.sourceRefillTicks(chain,List.of(net.minecraft.world.item.ItemStack.EMPTY,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PISTON)))==0
                : "Empty withdrawal outlets wait at most two hopper cycles; wrong-kit stock skips the refill wait entirely";
            var fallbackAction=bounds.deepCopy();fallbackAction.addProperty("name","Fallback");fallbackAction.addProperty("count",36);
            fallbackAction.add("picks",JsonParser.parseString("[{x:0,y:64,z:0,resource:'minecraft:shulker_box'}]").getAsJsonArray());
            var fallback=new BotStashResupply(fallbackAction);assert fallback.refreshSourceColumn(chain);
            var emptyColumn=new BotStashResupply(fallbackAction);assert emptyColumn.refreshSourceColumn(chain);
            var emptyContents=List.of(net.minecraft.world.item.ItemStack.EMPTY);
            assert emptyColumn.emptySourceColumn(emptyContents)&&!emptyColumn.skipEmptySourceColumn(emptyContents,chain.refillTicks()) : "A newly empty bottom cannot skip stock still propagating through hoppers";
            for(int tick=0;tick<chain.refillTicks();tick++)assert emptyColumn.waitingForRefill(chain.refillTicks());
            assert !emptyColumn.waitingForRefill(chain.refillTicks());
            assert !emptyColumn.skipEmptySourceColumn(emptyContents,chain.refillTicks()*2) : "Low TPS requires the longer propagation window";
            assert !emptyColumn.skipEmptySourceColumn(emptyContents,16) : "A short refill window cannot justify skipping the chain";
            assert !emptyColumn.skipEmptySourceColumn(List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PISTON)),chain.refillTicks()) : "Non-matching stock is not an empty outlet";
            assert emptyColumn.skipEmptySourceColumn(emptyContents,chain.refillTicks())&&emptyColumn.nextSourceChest(chain)==null : "Settled empty outlets skip every connected mapped height without opening upper chests";
            var settled=emptyColumn.snapshot();var restoredEmpty=new BotStashResupply(fallbackAction);restoredEmpty.restore(settled);
            assert restoredEmpty.nextSourceChest(chain)==null : "Empty-chain memory survives an action checkpoint";
            for(String uncertainty:List.of("sourceBlocked","sourceUncertain")){
                var uncertain=settled.deepCopy();uncertain.add("drainedSources",new JsonArray());uncertain.addProperty(uncertainty,true);
                var unresolved=new BotStashResupply(fallbackAction);unresolved.restore(uncertain);assert unresolved.refreshSourceColumn(chain);
                assert !unresolved.emptySourceColumn(emptyContents)&&!unresolved.skipEmptySourceColumn(emptyContents,chain.refillTicks()) : "Blocked or changed chains retain upper-chest verification";
            }
            var leased=fallbackAction.deepCopy();var localOnly=new BotStashResupply(leased);assert localOnly.refreshSourceColumn(chain);leased.addProperty("requireLocal",true);
            assert !localOnly.emptySourceColumn(emptyContents)&&!localOnly.skipEmptySourceColumn(emptyContents,chain.refillTicks()) : "An empty outlet cannot skip another worker's source lease";
            var independent=new BotStashResupply(fallbackAction);assert independent.refreshSourceColumn(new BotStashScan.HopperColumn(List.of(bottom)));
            assert !independent.emptySourceColumn(emptyContents) : "Unconnected storage stays independently searchable";
            for(int row=0;row<chain.chests().size();row++){
                assert fallback.target().equals(chain.chests().get(row)) : "Direct fallback must ascend one connected row at a time";
                if(row>0)assert !fallback.emptySourceColumn(emptyContents) : "An empty intermediate chest cannot prove the lower outlet stayed empty";
                boolean advanced=fallback.advanceSourceChest();
                assert advanced==(row+1<chain.chests().size());
                if(row==1){
                    assert fallback.refreshSourceColumn(chain)&&fallback.target().equals(chain.chests().get(2)) : "Refreshing the same chain must not rewind to the empty bottom";
                    var saved=fallback.snapshot();saved.addProperty("sourceBlocked",true);
                    var restored=new BotStashResupply(fallbackAction);restored.restore(saved);
                    assert restored.nextSourceChest(chain).equals(chain.chests().get(2))&&restored.snapshot().get("sourceBlocked").getAsBoolean()
                        : "Recovery skips already checked rows and retains uncertainty about mixed hopper stock";
                    var duplicate=saved.deepCopy();duplicate.getAsJsonArray("drainedSources").add(duplicate.getAsJsonArray("drainedSources").get(0));
                    try{new BotStashResupply(fallbackAction).restore(duplicate);throw new AssertionError("Duplicate checked source accepted");}catch(IllegalArgumentException expected){}
                    var outside=saved.deepCopy();outside.getAsJsonArray("drainedSources").set(0,new com.google.gson.JsonPrimitive(bottom.below().asLong()+":minecraft:shulker_box"));
                    try{new BotStashResupply(fallbackAction).restore(outside);throw new AssertionError("Checked source outside stash accepted");}catch(IllegalArgumentException expected){}
                }
            }
            assert fallback.nextSourceChest(chain)==null : "Duplicate mapped heights do not reopen checked-empty inventories";
            var anotherResource=fallbackAction.deepCopy();anotherResource.getAsJsonArray("picks").add(JsonParser.parseString("{x:0,y:64,z:0,resource:'minecraft:red_shulker_box'}"));
            var otherSnapshot=fallback.snapshot();otherSnapshot.addProperty("index",1);
            var otherPickup=new BotStashResupply(anotherResource);otherPickup.restore(otherSnapshot);
            assert otherPickup.nextSourceChest(chain).equals(chain.bottom()) : "An empty source for one resource does not hide another resource";
            JsonObject mapped=json("{x:0,y:82,z:0,slot:9,resource:'minecraft:shulker_box',dynamic:false}");
            var original=mapped.deepCopy();var actual=BotStashResupply.sourcePick(mapped,chain.bottom(),true);
            assert mapped.equals(original)&&actual.get("y").getAsInt()==64&&actual.get("dynamic").getAsBoolean()
                &&actual.get("resource").equals(mapped.get("resource")) : "The actual outlet goes into the receipt without mutating host picks or resource criteria";
            assert BotStashResupply.sourcePick(mapped,new BlockPos(0,82,0),false)==mapped : "Independent chest slots stay unchanged when the target has not moved";
            assert BotStashResupply.sourcePick(mapped,chain.bottom(),false).get("dynamic").getAsBoolean() : "A hopper redirected to a single chest still uses live slots and actual source coordinates";
        }
        states.clear();states.put(bottom,chest);states.put(bottom.above(2),chest);
        states.put(bottom.above(),hopper);
        assert BotStashScan.hopperOutlet(world,bottom.above(),bounds).equals(bottom);
        assert BotStashScan.hopperOutlet(world,bottom.above(),json("{minX:0,maxX:12,minY:65,maxY:90,minZ:0,maxZ:12}")).equals(bottom.above()) : "Never redirect to an outlet outside the selected stash";
        states.put(bottom.above(),hopper.setValue(net.minecraft.world.level.block.HopperBlock.ENABLED,false));
        assert BotStashScan.hopperOutlet(world,bottom.above(),bounds).equals(bottom.above()) : "Locked hopper stock stays at the hopper";
        states.remove(bottom.above());
        assert !BotStashScan.hopperColumn(world,bottom,bounds).fed() : "Unconnected vertical chests stay independent";
        assert !BotStashResupply.sourceMayRefill(world,bottom) : "Static chests retain source-removal confirmation";
        states.put(bottom.above(),hopper);
        assert BotStashResupply.sourceMayRefill(world,bottom)&&BotStashResupply.sourceMayRefill(world,bottom.above())
            : "A physically fed single-container sort must allow identical source-slot refill";
        states.put(bottom.above(),hopper.setValue(net.minecraft.world.level.block.HopperBlock.ENABLED,false));
        assert !BotStashResupply.sourceMayRefill(world,bottom) : "A locked hopper does not feed a chest";
        states.put(bottom.above(),hopper.setValue(net.minecraft.world.level.block.HopperBlock.FACING,net.minecraft.core.Direction.EAST));
        assert !BotStashResupply.sourceMayRefill(world,bottom) : "An adjacent hopper pointing away is not a feed";
        states.remove(bottom.above());
        states.put(bottom.above(3),hopper);states.put(bottom.above(4),hopper);
        assert BotStashScan.hopperOutlet(world,bottom.above(4),bounds).equals(bottom.above(2)) : "A sequence of hoppers resolves to its chest outlet";
        states.remove(bottom.above(3));states.remove(bottom.above(4));
        states.put(new BlockPos(5,70,5),hopper.setValue(net.minecraft.world.level.block.HopperBlock.FACING,net.minecraft.core.Direction.EAST));
        states.put(new BlockPos(6,70,5),hopper.setValue(net.minecraft.world.level.block.HopperBlock.FACING,net.minecraft.core.Direction.WEST));
        assert BotStashScan.hopperOutlet(world,new BlockPos(5,70,5),bounds).equals(new BlockPos(5,70,5)) : "Hopper cycles are bounded and never manufacture an outlet";
        states.put(bottom.above(),hopper.setValue(net.minecraft.world.level.block.HopperBlock.FACING,net.minecraft.core.Direction.EAST));
        assert !BotStashScan.hopperColumn(world,bottom,bounds).fed() : "A nearby hopper pointing away is not a feeder";
        states.put(bottom.above(),hopper.setValue(net.minecraft.world.level.block.HopperBlock.ENABLED,false));
        assert !BotStashScan.hopperColumn(world,bottom,bounds).fed() : "Redstone-locked hoppers cannot justify bottom-only withdrawal";
        states.put(bottom.above(),hopper);var clipped=bounds.deepCopy();clipped.addProperty("maxY",64);
        assert !BotStashScan.hopperColumn(world,bottom,clipped).fed() : "Never follow a hopper to storage outside the stash selection";
        bottom=new BlockPos(4,64,4);
        var left=chest.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.LEFT);
        BlockPos partner=bottom.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(left));
        states.clear();states.put(bottom,left);states.put(partner,chest.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
        BlockPos top=partner.above(2);states.put(top,chest);states.put(partner.above(),hopper);
        var first=BotStashScan.hopperColumn(world,bottom,bounds);var second=BotStashScan.hopperColumn(world,partner,bounds);
        assert first.fed()&&first.equals(second)&&first.top().equals(top) : "A hopper connected to either double-chest half feeds the whole inventory";
        assert BotStashResupply.sourceMayRefill(world,bottom)&&BotStashResupply.sourceMayRefill(world,partner)
            : "Either double-chest half receives the partner's feed, independent of the action's leased route";
        states.put(bottom.above(2),chest);states.put(bottom.above(),hopper);
        assert !BotStashScan.hopperColumn(world,bottom,bounds).fed() : "Two independent inputs are a branch, not a homogeneous column";
        assert BotStashResupply.sourceMayRefill(world,bottom) : "Ambiguous routing still permits physical refill, never bulk withdrawal";
        var box=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        var action=json("{name:'Deferred',kitTypeId:'00000000-0000-0000-0000-000000000001',count:36,transferAll:true,picks:[{x:0,y:64,z:0,resource:'minecraft:red_shulker_box'}]}");
        var deferred=new BotStashResupply(action);deferred.recordDeferredSource(bottom,List.of(box,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PISTON)));
        JsonObject checkpoint=deferred.snapshot();checkpoint.addProperty("index",1);checkpoint.addProperty("finished",true);
        var resumed=new BotStashResupply(action);resumed.restore(checkpoint);
        assert resumed.result().get("sourceDeferred").getAsBoolean()&&!resumed.result().get("sourceExhausted").getAsBoolean()
            &&resumed.result().get("picked").getAsInt()==0&&resumed.result().getAsJsonArray("deferredSources").get(0).getAsString().contains("minecraft:piston")
            : "An exhausted candidate list with a mismatched outlet stays deferred across checkpoint recovery, with actual item diagnostics";
        assert resumed.snapshot().get("deferredSources").equals(deferred.snapshot().get("deferredSources"));
        var refreshing=new BotStashResupply(action);
        var shortChain=new BotStashScan.HopperColumn(List.of(bottom,bottom.above(2)));
        var longerChain=new BotStashScan.HopperColumn(List.of(bottom,bottom.above(2),bottom.above(4)));
        assert refreshing.refreshSourceColumn(shortChain);
        assert refreshing.waitingForRefill(1)&&!refreshing.waitingForRefill(1);
        assert refreshing.refreshSourceColumn(longerChain)&&refreshing.waitingForRefill(1)
            &&refreshing.target().equals(bottom)&&refreshing.detail().contains("Rechecking changed hopper connection")
            &&!refreshing.snapshot().get("sourceUncertain").getAsBoolean()
            : "Newly visible upstream chests refresh the route/refill deadline instead of failing";
        assert refreshing.refreshSourceColumn(shortChain)&&refreshing.snapshot().get("sourceUncertain").getAsBoolean()
            : "Losing an upstream connection cannot prove source exhaustion";
        assert !refreshing.refreshSourceColumn(longerChain) : "Three no-progress refreshes bound a flapping source";
        var refreshedCheckpoint=refreshing.snapshot();var refreshedResume=new BotStashResupply(action);refreshedResume.restore(refreshedCheckpoint);
        assert !refreshedResume.refreshSourceColumn(longerChain)&&refreshedResume.snapshot().get("sourceUncertain").getAsBoolean()
            : "Reconnect does not erase uncertainty or reset the bounded refresh budget";
        refreshedCheckpoint.addProperty("sourceRefreshes",4);
        try{new BotStashResupply(action).restore(refreshedCheckpoint);throw new AssertionError("Invalid refresh count accepted");}catch(IllegalArgumentException expected){}
        var pendingRefresh=new BotStashResupply(action);pendingRefresh.beginWithdrawal(0,0,1,box,100,List.of(box,net.minecraft.world.item.ItemStack.EMPTY),true);
        try{pendingRefresh.refreshSourceColumn(shortChain);throw new AssertionError("Unconfirmed pickup discarded by refresh");}catch(IllegalStateException expected){}
        try{pendingRefresh.advanceSourceChest();throw new AssertionError("Unconfirmed pickup discarded by source advance");}catch(IllegalStateException expected){}
        try{pendingRefresh.skipEmptySourceColumn(List.of(net.minecraft.world.item.ItemStack.EMPTY),shortChain.refillTicks());throw new AssertionError("Unconfirmed pickup discarded by empty-chain inference");}catch(IllegalStateException expected){}
        var leasedAction=action.deepCopy();var leasedRefresh=new BotStashResupply(leasedAction);assert leasedRefresh.refreshSourceColumn(shortChain);
        leasedAction.addProperty("requireLocal",true);
        assert !leasedRefresh.advanceSourceChest()&&leasedRefresh.target().equals(bottom)
            &&leasedRefresh.snapshot().getAsJsonArray("drainedSources").isEmpty() : "Direct upper-chest fallback cannot escape a crew source lease";
        assert !leasedRefresh.refreshSourceColumn(new BotStashScan.HopperColumn(List.of(bottom.below(2),bottom)))
            &&leasedRefresh.target().equals(bottom) : "A column refresh cannot move a crew source lease to another chest";
        var topologyDeferred=new BotStashResupply(action);
        topologyDeferred.recordDeferredSource(bottom,List.of(),"Hopper connection did not stabilize after 3 rechecks");
        var topologyCheckpoint=topologyDeferred.snapshot();topologyCheckpoint.addProperty("index",1);topologyCheckpoint.addProperty("finished",true);
        var topologyResume=new BotStashResupply(action);topologyResume.restore(topologyCheckpoint);
        assert topologyResume.workflowResult().get("sourceDeferred").getAsBoolean()&&!topologyResume.workflowResult().get("sourceExhausted").getAsBoolean()
            &&topologyResume.workflowResult().getAsJsonArray("deferredSources").get(0).getAsString().contains("Hopper connection")
            : "Unstable topology is a retryable, diagnosed deferred source, never an empty-source receipt";
        JsonObject invalid=checkpoint.deepCopy();invalid.getAsJsonArray("deferredSources").add("extra");
        try{new BotStashResupply(action).restore(invalid);throw new AssertionError("Too many deferred sources accepted");}catch(IllegalArgumentException expected){}
        var manyAction=action.deepCopy();for(int i=0;i<8;i++)manyAction.getAsJsonArray("picks").add(action.getAsJsonArray("picks").get(0).deepCopy());
        var manyDeferred=new BotStashResupply(manyAction);for(int i=0;i<9;i++)manyDeferred.recordDeferredSource(bottom.offset(i,0,0),List.of(box));
        assert manyDeferred.snapshot().getAsJsonArray("deferredSources").size()==9&&manyDeferred.workflowResult().getAsJsonArray("deferredSources").size()==8
            : "Keep full native diagnostics but bound the Lua receipt's sample size";
    }
    private static void stashScanOrdering(){
        Vec3 walkingEye=new Vec3(.5,65.5,.5);
        BlockPos reading=new BlockPos(0,64,0),ahead=new BlockPos(5,64,0);
        assert BotStashScan.walkingLookAhead(reading,List.of(ahead),walkingEye,64).equals(ahead);
        assert BotStashScan.walkingLookAhead(null,List.of(ahead),walkingEye,64).equals(ahead) : "Closing a completed read must not force a movement gap";
        assert BotStashScan.walkingLookAhead(reading,List.of(ahead),new Vec3(4.5,65.5,.5),64)==null : "Brake before outrunning the server read";
        assert BotStashScan.walkingLookAhead(reading,List.of(new BlockPos(5,70,0)),walkingEye,64)==null : "Read-ahead must not initiate a vertical flight handoff";
        for(int cursor=0;cursor<129*8*8;cursor++){
            var band=BotStashScan.scanBand(cursor,129,8,8);
            if(cursor!=band.start())continue;
            for(boolean high:List.of(false,true)){
                boolean reverse=BotStashScan.bandReverseRow(cursor,129,8,8,high);
                int first=BotStashScan.bandIndex(cursor,129,8,8,reverse,false,false);
                assert first%129==(high?128:0) : "Each band must start at the nearby end even after empty bands";
            }
        }
        for(int width:List.of(1,3,4,5,7))for(int depth:List.of(1,3,4,5,7))for(int layers:List.of(1,3,4,5,7))
            for(boolean row:List.of(false,true))for(boolean side:List.of(false,true))for(boolean high:List.of(false,true)){
                var cells=new java.util.HashSet<Integer>();
                for(int i=0;i<width*depth*layers;i++){
                    int index=BotStashScan.bandIndex(i,width,depth,layers,row,side,high);
                    assert index>=0&&index<width*depth*layers&&cells.add(index) : "Spatial bands must cover every coordinate exactly once, including ragged edges";
                    assert BotStashScan.bandDiscoveryIndex(index,width,depth,layers,row,side,high)==i;
                    var band=BotStashScan.scanBand(i,width,depth,layers);
                    assert i>=band.start()&&i<band.start()+band.length()&&band.height()<=4&&band.breadth()<=4;
                }
            }
        for(boolean rotated:List.of(false,true)){
            int width=rotated?8:129,depth=rotated?129:8,layers=8;
            var nearby=new java.util.ArrayList<BlockPos>();
            for(int i=0;i<64;i++){
                int index=BotStashScan.bandIndex(i,width,depth,layers,false,false,false);
                nearby.add(new BlockPos(index%width,BotStashScan.layerY(index/(width*depth),layers),index%(width*depth)/width));
            }
            assert nearby.contains(new BlockPos(0,0,0))&&nearby.contains(new BlockPos(0,1,1))&&nearby.contains(new BlockPos(1,1,0))
                : "Reachable chests and adjacent/elevated hoppers enter the same bounded candidate window in either orientation";
            int boundary=BotStashScan.scanBand(0,width,depth,layers).length();
            var acrossBoundary=new java.util.ArrayList<BlockPos>();
            for(int i=boundary-16;i<boundary+48;i++){
                int index=BotStashScan.bandIndex(i,width,depth,layers,false,false,false);
                acrossBoundary.add(new BlockPos(index%width,BotStashScan.layerY(index/(width*depth),layers),index%(width*depth)/width));
            }
            BlockPos chest=rotated?new BlockPos(3,0,128):new BlockPos(128,0,3),hopper=rotated?new BlockPos(4,0,128):new BlockPos(128,0,4);
            assert acrossBoundary.contains(chest)&&acrossBoundary.contains(hopper) : "Neighboring bands turn at the same long-axis end, without a forced return to the opposite edge";
            Vec3 eye=Vec3.atCenterOf(chest);assert BotStashScan.nearestContainer(List.of(hopper,chest),eye,p->eye.distanceToSqr(Vec3.atCenterOf(p))<=20.25).equals(chest);
            assert BotStashScan.nearestContainer(List.of(hopper),eye,p->eye.distanceToSqr(Vec3.atCenterOf(p))<=20.25).equals(hopper)
                : "The adjacent hopper is scanned from the same position after the chest";
        }
        for(int width:List.of(1,3,7))for(int depth:List.of(1,3,7))for(boolean row:List.of(false,true))for(boolean layer:List.of(false,true)){
            var cells=new java.util.HashSet<Integer>();
            for(int i=0;i<width*depth*3;i++){
                int index=BotStashScan.scanIndex(i,width,depth,row,layer);
                assert index>=0&&index<width*depth*3&&cells.add(index);
                assert BotStashScan.discoveryIndex(index,width,depth,row,layer)==i : "Discovery and canonical coordinates round-trip in either orientation";
            }
        }
        // Wider than the 64-container queue: complete each row, then start at the nearby end.
        for(boolean rotated:List.of(false,true)){
            int width=rotated?4:257,depth=rotated?257:4;
            Vec3 eye=new Vec3(.5,64.5,.5);double distance=0;var visited=new java.util.HashSet<BlockPos>();
            for(int row=0;row<4;row++){
                boolean reverse=BotStashScan.nearerHighEnd(rotated?eye.z:eye.x,0,256);
                assert reverse==(row%2==1) : "Rows snake, rather than returning to a fixed starting edge";
                var queued=new java.util.ArrayList<BlockPos>();int cursor=row*257,end=cursor+257;
                while(cursor<end||!queued.isEmpty()){
                    while(cursor<end&&queued.size()<64){int index=BotStashScan.scanIndex(cursor++,width,depth,reverse,false);if((rotated?index/width:index%width)%2==0)queued.add(new BlockPos(index%width,64,index/width));}
                    BlockPos next=BotStashScan.nearestContainer(queued,eye,p->false);
                    if(next==null)continue;
                    assert visited.add(next)&&queued.remove(next);
                    distance+=eye.distanceTo(Vec3.atCenterOf(next));eye=Vec3.atCenterOf(next);
                }
            }
            assert visited.size()==4*129&&distance==4*256+3 : "Wide rows have no full-width return legs in X or Z: "+distance;
        }
        for(boolean rotated:List.of(false,true)){
            var raster=new java.util.ArrayList<BlockPos>();
            for(int z=0;z<4;z++)for(int x=0;x<32;x++)raster.add(rotated?new BlockPos(-z*3,64,x*2):new BlockPos(x*2,64,z*3));
            raster.sort(java.util.Comparator.<BlockPos>comparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX));
            Vec3 eye=Vec3.atCenterOf(raster.getFirst());double oldDistance=0,newDistance=0;
            for(BlockPos p:raster){Vec3 next=Vec3.atCenterOf(p);oldDistance+=eye.distanceTo(next);eye=next;}
            eye=Vec3.atCenterOf(raster.getFirst());int cursor=0;var lookAhead=new java.util.ArrayList<BlockPos>();var visited=new java.util.HashSet<BlockPos>();
            while(cursor<raster.size()||!lookAhead.isEmpty()){
                while(cursor<raster.size()&&lookAhead.size()<64)lookAhead.add(raster.get(cursor++));
                BlockPos next=BotStashScan.nearestContainer(lookAhead,eye,p->false);
                assert visited.add(next)&&lookAhead.remove(next) : "Every discovered chest is selected exactly once";
                newDistance+=eye.distanceTo(Vec3.atCenterOf(next));eye=Vec3.atCenterOf(next);
            }
            assert visited.size()==raster.size()&&newDistance<oldDistance*.75
                : "Nearest-container look-ahead removes raster return trips in either orientation: "+newDistance+" vs "+oldDistance;
        }
        BlockPos hidden=new BlockPos(1,64,0),open=new BlockPos(3,64,0);
        assert BotStashScan.nearestContainer(List.of(hidden,open),new Vec3(.5,64.5,.5),open::equals).equals(open)
            : "An immediately openable chest wins over a closer occluded chest";
        assert BotStashScan.nearestContainer(List.of(),Vec3.ZERO,p->true)==null;
        int[] openingChecks={0};BotStashScan.nearestContainer(List.of(hidden,open),Vec3.ZERO,p->{openingChecks[0]++;return false;});
        assert openingChecks[0]==2 : "Each candidate's visibility/reach is evaluated once, not repeatedly by a sorting comparator";
        JsonObject plan=BotActions.validate(json("{type:'StashScan',name:'Queue',homeName:'queue',minX:0,maxX:3,minY:64,maxY:64,minZ:0,maxZ:1,lazyMode:false}"));
        var scan=new BotStashScan(plan);JsonObject checkpoint=scan.snapshot();checkpoint.remove("scanOrder");checkpoint.addProperty("cursor",8);checkpoint.addProperty("discovered",3);
        checkpoint.addProperty("targetX",0);checkpoint.addProperty("targetY",64);checkpoint.addProperty("targetZ",0);checkpoint.add("candidateCells",JsonParser.parseString("[4,5]"));
        scan.restore(checkpoint);var resumed=new BotStashScan(plan);resumed.restore(scan.snapshot());
        assert !resumed.done()&&resumed.snapshot().getAsJsonArray("candidateCells").equals(checkpoint.getAsJsonArray("candidateCells"))
            : "Discovery reaching the selection end cannot lose queued, still-unscanned containers on resume";
        assert resumed.snapshot().get("scanOrder").getAsInt()==1 : "Pre-upgrade checkpoints keep their original traversal until complete";
        for(String invalid:List.of("[4,4]","[0,4]","[4,8]","[-1,4]","[4,1.5]")){
            JsonObject bad=checkpoint.deepCopy();bad.add("candidateCells",JsonParser.parseString(invalid));
            try{new BotStashScan(plan).restore(bad);throw new AssertionError("Invalid scan queue accepted: "+invalid);}catch(IllegalArgumentException expected){}
        }
        JsonObject reversed=new BotStashScan(plan).snapshot();reversed.addProperty("scanOrder",2);reversed.addProperty("reverseRow",true);reversed.addProperty("cursor",2);reversed.addProperty("discovered",2);
        reversed.add("candidateCells",JsonParser.parseString("[3,2]"));var snake=new BotStashScan(plan);snake.restore(reversed);
        var snakeResume=new BotStashScan(plan);snakeResume.restore(snake.snapshot());
        assert snakeResume.snapshot().get("reverseRow").getAsBoolean()&&snakeResume.snapshot().getAsJsonArray("candidateCells").equals(reversed.getAsJsonArray("candidateCells"))
            : "Reversed discovery can queue canonical indices beyond the cursor and resume without losing them";
        for(String invalid:List.of("[3,1]","[3,7]","[3,3]")){
            JsonObject bad=reversed.deepCopy();bad.add("candidateCells",JsonParser.parseString(invalid));
            try{new BotStashScan(plan).restore(bad);throw new AssertionError("Undiscovered or foreign-row queue accepted: "+invalid);}catch(IllegalArgumentException expected){}
        }
        JsonObject bad=reversed.deepCopy();bad.addProperty("reverseRow","true");
        try{new BotStashScan(plan).restore(bad);throw new AssertionError("Non-boolean direction accepted");}catch(IllegalArgumentException expected){}
        JsonObject bandPlan=BotActions.validate(json("{type:'StashScan',name:'Bands',homeName:'bands',minX:0,maxX:8,minY:64,maxY:68,minZ:0,maxZ:4,lazyMode:false}"));
        JsonObject bandCheckpoint=new BotStashScan(bandPlan).snapshot();assert bandCheckpoint.get("scanOrder").getAsInt()==4;
        bandCheckpoint.addProperty("reverseRow",true);bandCheckpoint.addProperty("reverseLayer",true);bandCheckpoint.addProperty("reverseHeight",true);
        bandCheckpoint.addProperty("cursor",5);bandCheckpoint.addProperty("discovered",2);
        JsonArray bandQueue=new JsonArray();bandQueue.add(BotStashScan.bandIndex(0,9,5,5,true,true,true));bandQueue.add(BotStashScan.bandIndex(4,9,5,5,true,true,true));bandCheckpoint.add("candidateCells",bandQueue);
        var bands=new BotStashScan(bandPlan);bands.restore(bandCheckpoint);var bandResume=new BotStashScan(bandPlan);bandResume.restore(bands.snapshot());
        assert bandResume.snapshot().get("scanOrder").getAsInt()==4 : "Adaptive band direction survives checkpoints";
        JsonObject largePlan=bandPlan.deepCopy();largePlan.addProperty("maxX",StashCatalog.MAX_CONTAINERS-1);largePlan.addProperty("maxY",64);largePlan.addProperty("maxZ",0);
        JsonObject largeCheckpoint=new BotStashScan(largePlan).snapshot();largeCheckpoint.addProperty("cursor",StashCatalog.MAX_CONTAINERS);largeCheckpoint.addProperty("delivery",StashCatalog.MAX_CONTAINERS+1);largeCheckpoint.addProperty("discovered",StashCatalog.MAX_CONTAINERS);largeCheckpoint.addProperty("observed",StashCatalog.MAX_CONTAINERS);
        var largeScan=new BotStashScan(largePlan);largeScan.restore(largeCheckpoint);assert largeScan.snapshot().get("delivery").getAsInt()==StashCatalog.MAX_CONTAINERS+1&&largeScan.done() : "Large scans retain counters past the old 4096-container boundary";
        assert bandResume.snapshot().getAsJsonArray("candidateCells").equals(bandQueue)&&bandResume.snapshot().get("reverseHeight").getAsBoolean();
        JsonObject crossedBand=bandCheckpoint.deepCopy();crossedBand.addProperty("scanOrder",3);crossedBand.addProperty("cursor",146);JsonArray crossQueue=new JsonArray();
        crossQueue.add(BotStashScan.bandIndex(143,9,5,5,true,true,true));crossQueue.add(BotStashScan.bandIndex(145,9,5,5,true,true,true));crossedBand.add("candidateCells",crossQueue);
        var crossing=new BotStashScan(bandPlan);crossing.restore(crossedBand);assert crossing.snapshot().getAsJsonArray("candidateCells").equals(crossQueue)
            : "A candidate window crossing bands survives restart; nearby hoppers do not require a separate pass";
        JsonObject invalidCrossing=crossedBand.deepCopy();invalidCrossing.addProperty("scanOrder",4);
        try{new BotStashScan(bandPlan).restore(invalidCrossing);throw new AssertionError("Adaptive bands accepted a queue from a previous direction");}catch(IllegalArgumentException expected){}
        for(int invalid:List.of(BotStashScan.bandIndex(5,9,5,5,true,true,true),BotStashScan.bandIndex(145,9,5,5,true,true,true))){
            JsonObject invalidBand=bandCheckpoint.deepCopy();invalidBand.getAsJsonArray("candidateCells").set(1,new com.google.gson.JsonPrimitive(invalid));
            try{new BotStashScan(bandPlan).restore(invalidBand);throw new AssertionError("Undiscovered band accepted");}catch(IllegalArgumentException expected){}
        }
        bandCheckpoint.remove("reverseHeight");
        try{new BotStashScan(bandPlan).restore(bandCheckpoint);throw new AssertionError("Missing height direction accepted");}catch(IllegalArgumentException expected){}
        bandPlan.addProperty("lazyMode",true);assert new BotStashScan(bandPlan).snapshot().get("scanOrder").getAsInt()==2 : "Lazy bookend scans keep bottom/top rows";
        bandPlan.addProperty("lazyMode",false);bandPlan.addProperty("scanMode","Column Map");assert new BotStashScan(bandPlan).snapshot().get("scanOrder").getAsInt()==2 : "Column mapping never visits upper layers";
    }
    private static void stashFlightHandoffs(JsonObject plan){
        JsonObject visit=plan.deepCopy();visit.addProperty("type","StashVisit");assert BotActions.validate(visit).get("homeWarmupTicks").getAsInt()==300;
        JsonObject tpa=json("{type:'Tpa',target:'Leader',warmupTicks:300,timeoutTicks:1500}");tpa.add("arrivalStash",visit);
        JsonObject checked=BotActions.validate(tpa);assert BotActions.teleportStashReached(checked,new dev.monocle.coordinator.PlayerObservation.Position(plan.get("minX").getAsInt(),plan.get("minY").getAsInt(),plan.get("minZ").getAsInt()));
        // Synthetic coordinates separate the old stash from the destination.
        assert !BotActions.teleportStashReached(checked,new dev.monocle.coordinator.PlayerObservation.Position(10000,116,10000)) : "Seeing the leader beside us at the old stash is not successful TPA arrival";
        assert BotActions.validate(json("{type:'KitTransferPlan',step:0,receipt:{}}")).get("step").getAsInt()==0;
        try{BotActions.validate(json("{type:'KitTransferPlan',step:0,receipt:{ok:'true'}}"));throw new AssertionError("Crew receipt flags must be booleans");}catch(IllegalArgumentException expected){}
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
    private static void delayedDepositAcknowledgement() throws Exception {
        JsonObject action=json("{count:31,name:'Target',minX:0,maxX:4,minY:116,maxY:119,minZ:0,maxZ:3}");
        var deposit=deposit(action);
        int warnings=0;for(int i=0;i<201;i++)if(deposit.waitForHost(i*50_000_000L))warnings++;
        assert warnings==1 : "Slow acknowledgement warns once without abandoning the receipt";
        assert deposit.hasPending()&&deposit.pending()!=null&&deposit.delivery()==1 : "A delayed host cannot release the unapproved deposit gate";
        deposit.pending().addProperty("exhaustedColumn",true);
        for(int i=0;i<1000;i++)assert deposit.hasPending();
        assert !deposit.pending().has("exhaustedColumn") : "Presence checks preserve the privately owned pending receipt";
        deposit.waitForHost(59_999_999_999L);
        JsonObject ack=json("{deposit:true,delivery:1,permit:false,column:{x:2,y:116,z:0,axis:'X'}}");
        var column=BotStashDeposit.class.getDeclaredField("column");column.setAccessible(true);column.set(deposit,ack.getAsJsonObject("column"));
        deposit.acknowledge(ack);
        assert !deposit.hasPending()&&deposit.pending()==null&&deposit.delivery()==2 : "A delayed receipt still releases the exact pending observation";
        deposit.acknowledge(ack);assert deposit.delivery()==2 : "A duplicate receipt never advances the delivery cursor";
        var disconnected=deposit(action);disconnected.waitForHost(0);
        try{disconnected.waitForHost(60_000_000_000L);throw new AssertionError("Missing host ACK waited forever");}catch(IllegalStateException expected){assert expected.getMessage().contains("delivery 1, verified 0/31");}
        var restored=deposit(action);restored.restore(disconnected.snapshot());restored.waitForHost(70_000_000_000L);
        assert restored.pending()!=null&&restored.delivery()==1 : "Reconnect gets a fresh bounded wait without replacing the pending receipt";
    }
    private static void stashHotPaths() throws Exception {
        assert BotActions.stashCollisionWalking(300,100)&&BotActions.stashCollisionWalking(300,299)
            &&!BotActions.stashCollisionWalking(300,300)&&!BotActions.stashCollisionWalking(300,0)
            &&!BotActions.stashCollisionWalking(0,100) : "Server collision walking is bounded and stale tick clocks do not strand new worlds";
        var nearWall=new net.minecraft.world.phys.Vec3(1.31,2,.5);
        var wall=new net.minecraft.world.phys.AABB(0,0,0,1,4,1);
        java.util.function.Predicate<net.minecraft.world.phys.AABB> clear=body->!body.intersects(wall);
        assert dev.monocle.client.utils.world.PrinterFlight.segmentClear(nearWall,nearWall.add(0,.1,0),.6,.6,clear)
            &&!dev.monocle.client.utils.world.PrinterFlight.segmentClear(nearWall,nearWall.add(0,.1,0),BotActions.stashFlightWidth(.6),.6,clear)
            : "Stash flight rejects wall-skimming paths that have no lateral safety margin";
        try(var bytes=BotActions.class.getResourceAsStream("BotActions.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code,"correction").contains("execute")
                &&calls(code,"handleCorrection").containsAll(List.of("getSource","stopStashNavigation","brakeFlight"))
                &&calls(code,"maintainStashClearance").containsAll(List.of("stashCollisionWalking","landBeforeHandoff"))
                &&calls(code,"moveToStashContainer").contains("stashCollisionWalking")
                &&calls(code,"flyToStashChest").contains("stashFlightWidth")
                &&!calls(code,"landBeforeHandoff").contains("stashFlightWidth")
                : "Server wall damage lands before a bounded walking retry; flight margin never changes partial-surface landing support";
            assert calls(code,"stashInteractionPaused").contains("maintainStashClearance") : "Clearance is maintained during travel, container reads and host waits";
            assert calls(code,"maintainStashClearance").containsAll(List.of("clearanceExit","safeVelocity","landBeforeHandoff"))
                : "Takeoff seeks a collision-checked clearance exit or lands, rather than squeezing through cruise obstacles";
            assert code.methods().stream().filter(m->m.methodName().equalsString("maintainStashClearance"))
                .flatMap(m->m.code().orElseThrow().elementList().stream())
                .filter(java.lang.classfile.instruction.ConstantInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.ConstantInstruction.class::cast)
                .noneMatch(c->"Landing before continuing stash work".equals(c.constantValue()))
                : "The actual landing blocker must survive into action status";
            assert calls(code,"landBeforeHandoff").indexOf("nearbyLanding")<calls(code,"landBeforeHandoff").indexOf("retreatStep")
                : "Search nearby support before retracing a long flight trail";
            assert calls(code,"tick").contains("maintainStashClearance") : "A waiting workflow handoff must retain cruise clearance too";
            for(String method:List.of("landBeforeHandoff","maintainStashClearance","requestSuspend","resetFlightRoutes"))
                assert code.methods().stream().filter(m->m.methodName().equalsString(method))
                    .flatMap(m->m.code().orElseThrow().elementList().stream())
                    .anyMatch(e->e instanceof java.lang.classfile.instruction.FieldInstruction field&&field.name().equalsString("landing"))
                    : "Deliberate landing remains exempt until grounded, and new routes reset that state";
            assert !calls(code,"clearBody").contains("getMaxY") : "Build height is not the flight ceiling above top-row chests";
        }
        try(var bytes=BotRuntime.class.getResourceAsStream("BotRuntime.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code,"tick").contains("detail")&&calls(code,"tickAction").contains("detail")
                : "Workflow handoffs also expose the actual landing blocker to the host";
            for(String method:List.of("sendPendingStashFinding","acknowledgeStash")){
                var names=calls(code,method);
                assert names.contains("hasStashPending")&&!names.contains("stashPending") : "Presence checks must not clone container manifests";
            }
            var status=calls(code,"sendStatus");
            for(String method:List.of("stashTelemetry","stashWithdrawal","stashDeposit","stashTravel"))
                assert java.util.Collections.frequency(status,method)==1 : "Build each status receipt at most once";
        }
        try(var bytes=BotStashResupply.class.getResourceAsStream("BotStashResupply.class")){
            var names=calls(ClassFile.of().parse(bytes.readAllBytes()),"inventory");
            assert java.util.Collections.frequency(names,"carriedIn")==1 : "Classify packet inventory once for pickup and deposit verification";
            assert java.util.Collections.frequency(names,"copyContents")==1 : "Container contents share a read-only view of the copied full-menu snapshot";
        }
        for(Class<?> type:List.of(BotStashResupply.class,BotStashDeposit.class))try(var bytes=type.getResourceAsStream(type.getSimpleName()+".class")){
            var names=calls(ClassFile.of().parse(bytes.readAllBytes()),"slot");
            assert names.containsAll(List.of("getContainerId","updatedSlot"))&&!names.contains("observeWithdrawal")&&!names.contains("observeContents")&&!names.contains("observeBulk")
                : "Idle deltas stay menu-scoped and cannot acknowledge transfers";
        }
        try(var bytes=BotActions.class.getResourceAsStream("BotActions.class")){
            assert java.util.Collections.frequency(calls(ClassFile.of().parse(bytes.readAllBytes()),"slot"),"slot")==3
                : "The worker routes slot events to personal supplies or both shared stash executors";
        }
    }
    private static void closedSourceMenus() throws Exception {
        var source=new BotStashResupply(json("{picks:[]}"));
        var opening=BotStashResupply.class.getDeclaredField("openingRequested");opening.setAccessible(true);opening.setBoolean(source,true);
        assert source.acceptMenu(41);
        var receipt=BotStashResupply.class.getDeclaredField("receipt");receipt.setAccessible(true);((JsonArray)receipt.get(source)).add(json("{x:1,y:64,z:1}"));
        var contents=BotStashResupply.class.getDeclaredField("serverMenuContents");contents.setAccessible(true);contents.set(source,List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE)));
        var cursor=BotStashResupply.class.getDeclaredField("serverCursor");cursor.setAccessible(true);cursor.set(source,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE));
        var checkpoint=source.snapshot();
        assert !source.resetClosedMenu(41,true)&&!source.resetClosedMenu(0,false) : "A live menu or held current cursor is not an abandoned idle lease";
        assert source.resetClosedMenu(0,true)&&source.snapshot().equals(checkpoint)&&source.hasWithdrawal()&&((List<?>)contents.get(source)).isEmpty()
            &&((net.minecraft.world.item.ItemStack)cursor.get(source)).isEmpty()&&!source.acceptMenu(41)
            : "Escape invalidates the old snapshot/opening marker, including a stale cursor, but preserves confirmed pickups";
        opening.setBoolean(source,true);assert source.acceptMenu(44)&&!source.acceptMenu(41);
        assert source.resetClosedMenu(45,true)&&!source.acceptMenu(45) : "A replacement menu must be explicitly closed and reopened before reading it";
        opening.setBoolean(source,true);assert source.acceptMenu(46);
        for(String name:List.of("withdrawSent","depositBefore","discardSlot")){
            var pending=BotStashResupply.class.getDeclaredField(name);pending.setAccessible(true);pending.setInt(source,1);
            assert !source.resetClosedMenu(0,true)&&source.acceptMenu(46)&&!source.acceptMenu(47) : "An uncertain "+name+" stays bound to its original menu";
            pending.setInt(source,-1);
        }
        var slots=new ArrayList<net.minecraft.world.item.ItemStack>(java.util.Collections.nCopies(63,net.minecraft.world.item.ItemStack.EMPTY));for(int i=0;i<4;i++)slots.set(i,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHULKER_BOX));
        var bulk=BotStashBulk.plan(slots,27,4,10,true,false,false,i->true);assert bulk!=null;
        var bulkField=BotStashResupply.class.getDeclaredField("bulk");bulkField.setAccessible(true);bulkField.set(source,bulk);
        assert !source.resetClosedMenu(0,true) : "Escape cannot turn an issued bulk transfer into an idle retry";
        try(var bytes=BotStashResupply.class.getResourceAsStream("BotStashResupply.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());var tick=calls(code,"tick");
            assert tick.indexOf("resetClosedMenu")>tick.indexOf("settleWithdrawal")&&tick.indexOf("resetClosedMenu")<tick.indexOf("open")
                &&calls(code,"close").contains("forgetMenu")&&calls(code,"resetClosedMenu").contains("forgetMenu")
                : "Production reconciles pending pickups first and discards an idle stale menu before reopening";
        }
    }
    private static void idleSlotGuards() throws Exception {
        var event=dev.monocle.client.events.packets.ContainerSlotUpdateEvent.get(null);
        for(String pending:List.of("withdrawSent","depositBefore","discardSlot")){
            var pickup=new BotStashResupply(json("{picks:[]}"));
            for(String flag:List.of("openingRequested","menuReady")){var field=BotStashResupply.class.getDeclaredField(flag);field.setAccessible(true);field.setBoolean(pickup,true);}
            var field=BotStashResupply.class.getDeclaredField(pending);field.setAccessible(true);field.setInt(pickup,1);
            pickup.slot(event);assert pickup.snapshot().getAsJsonArray("receipt").isEmpty() : "A pending move rejects deltas before reading the packet or client";
        }
        for(String pending:List.of("pendingStage","reconciling")){
            var deposit=deposit(json("{count:1}"));
            var ready=BotStashDeposit.class.getDeclaredField("menuReady");ready.setAccessible(true);ready.setBoolean(deposit,true);
            var inventory=BotStashDeposit.class.getDeclaredField("serverInventory");inventory.setAccessible(true);inventory.set(deposit,java.util.Collections.nCopies(36,net.minecraft.world.item.ItemStack.EMPTY));
            var finding=BotStashDeposit.class.getDeclaredField("pendingFinding");finding.setAccessible(true);finding.set(deposit,null);
            var field=BotStashDeposit.class.getDeclaredField(pending);field.setAccessible(true);if(pending.equals("pendingStage"))field.setInt(deposit,1);else field.setBoolean(deposit,true);
            deposit.slot(event);assert deposit.snapshot().get("confirmed").getAsInt()==0 : "Interrupted and pending deposits still need full receipts";
        }
    }
    private static void successfulTakeoff() throws Exception {
        try (var bytes = BotActions.class.getResourceAsStream("BotActions.class")) {
            var code = ClassFile.of().parse(bytes.readAllBytes()).methods().stream()
                .filter(m -> m.methodName().equalsString("travel") && m.methodTypeSymbol().parameterCount() == 5)
                .findFirst().orElseThrow().code().orElseThrow().elementList();
            int attemptsReset = -1, timerReset = -1, resets = 0;
            for (int i = 1; i < code.size(); i++) {
                if (!(code.get(i) instanceof java.lang.classfile.instruction.FieldInstruction field)
                    || field.opcode() != java.lang.classfile.Opcode.PUTFIELD
                    || !(code.get(i - 1) instanceof java.lang.classfile.instruction.ConstantInstruction constant)) continue;
                if (field.name().equalsString("launchAttempts") && Integer.valueOf(0).equals(constant.constantValue())) { attemptsReset = i; resets++; }
                if (field.name().equalsString("launchTick") && Integer.valueOf(-1).equals(constant.constantValue()) && attemptsReset >= 0 && timerReset < 0) timerReset = i;
            }
            assert resets == 1 && timerReset > attemptsReset : "Successful travel flight clears its consumed attempts and stale launch timer";
            boolean guardedByGliding = false;
            for (int i = 0; i < attemptsReset - 1; i++) {
                if (!(code.get(i) instanceof InvokeInstruction call) || !call.name().equalsString("isFallFlying")
                    || !(code.get(i + 1) instanceof java.lang.classfile.instruction.BranchInstruction branch)
                    || branch.opcode() != java.lang.classfile.Opcode.IFEQ) continue;
                for (int j = timerReset + 1; j < code.size(); j++)
                    if (code.get(j) instanceof java.lang.classfile.instruction.LabelTarget label && label.label().equals(branch.target())) guardedByGliding = true;
            }
            assert guardedByGliding : "Only observed gliding resets failures; jumping or waiting for a launch must not reset the retry budget";
        }
    }

    private static void portalTravel() throws Exception {
        assert BotActions.travelHazard(null) : "Unverified terrain remains unsafe";
        assert !BotActions.safeSupport(null);
        for(var block:List.of(net.minecraft.world.level.block.Blocks.CHEST,net.minecraft.world.level.block.Blocks.HOPPER,net.minecraft.world.level.block.Blocks.OBSIDIAN))
            assert BotActions.safeSupport(block.defaultBlockState()) : "Real chest/hopper geometry may provide safe landing support";
        for(var block:List.of(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK,net.minecraft.world.level.block.Blocks.CACTUS,
            net.minecraft.world.level.block.Blocks.CAMPFIRE,net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE,
            net.minecraft.world.level.block.Blocks.END_PORTAL,net.minecraft.world.level.block.Blocks.END_GATEWAY,
            net.minecraft.world.level.block.Blocks.NETHER_PORTAL,net.minecraft.world.level.block.Blocks.WATER,net.minecraft.world.level.block.Blocks.LAVA))
            assert !BotActions.safeSupport(block.defaultBlockState()) : "Partial-surface landing cannot waive hazards";
        for (var hazard : List.of(net.minecraft.world.level.block.Blocks.WATER, net.minecraft.world.level.block.Blocks.LAVA,
            net.minecraft.world.level.block.Blocks.FIRE, net.minecraft.world.level.block.Blocks.SOUL_FIRE, net.minecraft.world.level.block.Blocks.POWDER_SNOW))
            assert BotActions.travelHazard(hazard.defaultBlockState()) : "Existing travel hazards remain blocked";
        for (var ordinary : List.of(net.minecraft.world.level.block.Blocks.AIR, net.minecraft.world.level.block.Blocks.CAVE_AIR,
            net.minecraft.world.level.block.Blocks.OBSIDIAN, net.minecraft.world.level.block.Blocks.END_PORTAL_FRAME))
            assert !BotActions.travelHazard(ordinary.defaultBlockState()) : "Ordinary blocks and portal frames still use normal collision checks";
        BlockPos portalPosition = new BlockPos(0, 64, 0);
        Vec3 start = new Vec3(-2.5, 64, .5), goal = new Vec3(2.5, 64, .5);
        for (var portal : List.of(net.minecraft.world.level.block.Blocks.END_PORTAL, net.minecraft.world.level.block.Blocks.END_GATEWAY,
            net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) {
            var state = portal.defaultBlockState();
            assert state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, portalPosition).isEmpty()
                : "Portal contact cannot be guarded by solid-block collision alone";
            assert BotActions.travelHazard(state) : "Worker travel must reject every portal type";
            java.util.function.Predicate<net.minecraft.world.phys.AABB> clear = box -> {
                for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                    BlockPos.containing(Math.nextDown(box.maxX), Math.nextDown(box.maxY), Math.nextDown(box.maxZ))))
                    if (BotActions.travelHazard(pos.equals(portalPosition) ? state : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())) return false;
                return true;
            };
            assert !PrinterFlight.segmentClear(start, goal, .72, 1.8, clear) : "Swept travel cannot skip a frameless portal between endpoints";
            assert !clear.test(PrinterFlight.body(Vec3.atBottomCenterOf(portalPosition), .72, 1.8)) : "Portal cells cannot be standing or landing destinations";
            assert !clear.test(PrinterFlight.body(new Vec3(-.1, 64, .5), .72, 1.8)) : "Sideways body overlap counts even when feet are beside a portal";
            assert !clear.test(PrinterFlight.body(new Vec3(.5, 63.1, .5), .72, 1.8)) : "A portal at head height is unsafe even when the feet cell is clear";
            assert clear.test(PrinterFlight.body(new Vec3(.5, 65.1, .5), .72, 1.8)) : "Flight wholly above a portal is not blacklisted";
            assert PrinterFlight.route(start, Vec3.atBottomCenterOf(portalPosition), .72, 1.8, clear).isEmpty()
                : "An unsafe destination cannot become a valid detour endpoint";
            var route = PrinterFlight.route(start, goal, .72, 1.8, clear);
            assert route.size() > 1 : "A loaded route around a portal remains available";
            for (int i = 1; i < route.size(); i++)
                assert PrinterFlight.segmentClear(route.get(i - 1), route.get(i), .72, 1.8, clear) : "Every detour segment avoids portal contact";
        }
        try (var bytes = BotActions.class.getResourceAsStream("BotActions.class")) {
            var code = ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code, "clearBody").contains("travelHazard") && calls(code, "safeSupport").contains("travelHazard")
                &&calls(code,"safeFloor").contains("safeSupport")&&calls(code,"safeLandingShape").containsAll(List.of("verified","safeSupport","getCollisionShape"))
                : "Live body and footing checks both apply portal hazards";
            assert calls(code, "standable").contains("clearBody") && calls(code, "safeLandingBelow").contains("landingBelow")
                : "Walking destinations and landing probes retain the shared body guard";
        }
    }
    private static void singleColumnDeposit(){
        JsonObject action=json("{count:35,name:'Target',homeName:'main',minX:0,maxX:4,minY:116,maxY:119,minZ:0,maxZ:3,targetColumn:{x:2,y:116,z:0,axis:'X'}}");
        var deposit=deposit(action);deposit.finishFullColumn(35);
        assert deposit.result().get("columnFull").getAsBoolean()&&deposit.result().get("retained").getAsInt()==35&&deposit.result().get("missing").getAsInt()==0;
        var snapshot=deposit.snapshot();var restored=deposit(action);restored.restore(snapshot);
        assert restored.result().equals(deposit.result())&&restored.pending()==null : "A completed full-column checkpoint cannot reserve or transfer again";
        snapshot.addProperty("retained",34);try{deposit(action).restore(snapshot);throw new AssertionError("All retained kits need accounting");}catch(IllegalArgumentException expected){}
        var pending=deposit(action);pending.beginDeposit(35,0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHULKER_BOX),100,List.of(net.minecraft.world.item.ItemStack.EMPTY));
        try{pending.finishFullColumn(35);throw new AssertionError("A pending click cannot become a full-column stop");}catch(IllegalStateException expected){}
        var ordinary=deposit(json("{count:35}"));try{ordinary.finishFullColumn(35);throw new AssertionError("Ordinary deposits cannot invent a limit");}catch(IllegalStateException expected){}
        var removed=deposit(action);removed.finishFullColumn(33);
        assert removed.result().get("retained").getAsInt()==33&&removed.result().get("missing").getAsInt()==2 : "Manually removed kits are not reported as still carried";
        var removedRestored=deposit(action);removedRestored.restore(removed.snapshot());assert removedRestored.result().equals(removed.result());
        var extra=deposit(action);extra.finishFullColumn(36);assert extra.result().get("retained").getAsInt()==35 : "Unrequested kits do not inflate this batch receipt";
        var legacyHost=deposit(action);legacyHost.acknowledge(json("{deposit:true,delivery:1,permit:false,column:{x:0,y:116,z:0,axis:'X'}}"));
        assert legacyHost.snapshot().get("confirmed").getAsInt()==0&&legacyHost.pending()==null : "An old host selecting another column is rejected without reconnect loops or item movement";
        JsonObject validated=action.deepCopy();validated.addProperty("type","StashDeposit");validated.addProperty("kitTypeId","00000000-0000-0000-0000-000000000001");BotActions.validate(validated);
        validated.add("columnKitTypeIds",json("{ids:['00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002']}").get("ids"));BotActions.validate(validated);
        JsonObject invalid=validated.deepCopy();invalid.getAsJsonArray("columnKitTypeIds").remove(0);try{BotActions.validate(invalid);throw new AssertionError("Delivered kit missing from sharing rule accepted");}catch(IllegalArgumentException expected){}
        validated.getAsJsonObject("targetColumn").addProperty("x",5);try{BotActions.validate(validated);throw new AssertionError("Out-of-bounds column accepted");}catch(IllegalArgumentException expected){}
    }
    private static BotStashDeposit depositOpeningGuards() throws Exception {
        var deposit=deposit(json("{count:2}"));
        var packet=new net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket(10,1,
            java.util.Collections.nCopies(90,net.minecraft.world.item.ItemStack.EMPTY),net.minecraft.world.item.ItemStack.EMPTY);
        var event=dev.monocle.client.events.packets.InventoryEvent.get(packet);
        var pending=BotStashDeposit.class.getDeclaredField("pendingFinding");pending.setAccessible(true);
        var target=BotStashDeposit.class.getDeclaredField("target");target.setAccessible(true);
        var beforeMenu=BotStashDeposit.class.getDeclaredField("beforeMenu");beforeMenu.setAccessible(true);
        var original=deposit.snapshot();deposit.inventory(event);
        assert deposit.snapshot().equals(original)&&!deposit.acceptMenu(10) : "Initial reservation cannot read an unrelated source chest";
        pending.set(deposit,null);original=deposit.snapshot();deposit.inventory(event);
        assert deposit.snapshot().equals(original)&&!deposit.acceptMenu(10) : "After the host ACK, /home still has no selected destination";
        beforeMenu.setInt(deposit,0);deposit.inventory(event);
        assert !deposit.acceptMenu(10) : "An opening marker alone cannot replace a missing target";
        beforeMenu.setInt(deposit,-1);target.set(deposit,new BlockPos(0,64,0));deposit.inventory(event);
        assert !deposit.acceptMenu(10) : "Selecting a chest is not requesting its opening";
        beforeMenu.setInt(deposit,0);pending.set(deposit,new JsonObject());original=deposit.snapshot();deposit.inventory(event);
        assert deposit.snapshot().equals(original)&&!deposit.acceptMenu(10) : "Contents cannot overwrite an observation awaiting its host ACK";
        pending.set(deposit,null);
        assert !deposit.acceptMenu(0)&&deposit.acceptMenu(10)&&deposit.acceptMenu(10)&&!deposit.acceptMenu(11)
            : "Only the new, bound chest menu may supply receipts, even before the first click";
        var restored=deposit(json("{count:2}"));restored.restore(deposit.snapshot());restored.inventory(event);
        assert !restored.acceptMenu(10) : "A checkpoint cannot restore ownership of an old open menu";
        return deposit;
    }
    private static BotStashDeposit deposit(JsonObject action){
        JsonObject complete=action.deepCopy();
        for(String axis:List.of("X","Y","Z")){if(!complete.has("min"+axis))complete.addProperty("min"+axis,-64);if(!complete.has("max"+axis))complete.addProperty("max"+axis,384);}
        complete.addProperty("workerCount",1);complete.addProperty("workerIndex",0);return new BotStashDeposit(complete);
    }
    private static void exactDepositEvidence()throws Exception{
        var empty=net.minecraft.world.item.ItemStack.EMPTY;
        var box=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        box.set(net.minecraft.core.component.DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OBSIDIAN,64))));
        var deposit=deposit(json("{name:'Target',count:2}"));var target=BotStashDeposit.class.getDeclaredField("target");target.setAccessible(true);target.set(deposit,new BlockPos(1,64,2));
        deposit.beginDeposit(2,0,box,1,List.of(empty,empty));deposit.observeContents(List.of(box,empty),1);deposit.observeContents(List.of(box,empty),1);
        JsonObject evidence=deposit.result().getAsJsonArray("depositedBoxes").get(0).getAsJsonObject();
        assert deposit.result().getAsJsonArray("depositedBoxes").size()==1&&evidence.get("x").getAsInt()==1&&evidence.get("z").getAsInt()==2&&evidence.getAsJsonObject("items").get("minecraft:obsidian").getAsInt()==64 : "Exact actual contents, destination and single-count evidence survive duplicate acknowledgements";
        assert !deposit.workflowResult().has("depositedBoxes") : "Large item evidence stays out of bounded Lua results";
        JsonObject saved=deposit.snapshot();var restored=deposit(json("{name:'Target',count:2}"));restored.restore(saved);assert restored.result().equals(deposit.result());
        saved.remove("depositedBoxes");saved.remove("receiptComplete");var legacy=deposit(json("{name:'Target',count:2}"));legacy.restore(saved);assert !legacy.result().get("receiptComplete").getAsBoolean()&&legacy.result().getAsJsonArray("depositedBoxes").isEmpty() : "Old checkpoints keep their count but do not invent missing kit contents";
        var invalid=deposit.snapshot();invalid.getAsJsonArray("depositedBoxes").get(0).getAsJsonObject().addProperty("x",5000);try{deposit(json("{name:'Target',count:2}")).restore(invalid);throw new AssertionError("Out-of-stash deposit evidence accepted");}catch(IllegalArgumentException expected){}
    }
    private static void batchDeposits() throws Exception {
        var click=dev.monocle.client.utils.player.InvUtils.confirmedClick(10,82);
        assert click.containerId()==10&&click.stateId()==-1&&click.slotNum()==82&&click.buttonNum()==0
            &&click.containerInput()==net.minecraft.world.inventory.ContainerInput.PICKUP&&click.changedSlots().isEmpty()
            &&click.carriedItem()==net.minecraft.network.HashedStack.EMPTY : "Explicit placement forces vanilla's post-click snapshot, without client-predicted slots";
        var first=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        first.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("First"));
        var second=first.copy();second.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Second"));
        var empty=net.minecraft.world.item.ItemStack.EMPTY;
        var deposit=depositOpeningGuards();
        deposit.beginDeposit(2,1,first,10,List.of(first,empty,empty));
        try{deposit.finishMissingCarried();throw new AssertionError("Missing-kit recovery must never waive a pending click");}catch(IllegalStateException expected){}
        assert !deposit.approaching() : "An unconfirmed deposit holds position even if opening reach is lost";
        deposit.observeContents(List.of(first,empty,empty),1);
        assert deposit.snapshot().get("pending").getAsBoolean()&&deposit.snapshot().get("confirmed").getAsInt()==0 : "A pre-existing kit cannot confirm the explicit destination";
        assert !deposit.acceptMenu(11) : "A different menu cannot acknowledge an in-flight move";
        try{deposit.beginDeposit(1,2,second,11,List.of(first,empty,empty));throw new AssertionError("Never replay an unconfirmed move");}catch(IllegalStateException expected){}
        var interrupted=deposit(json("{count:2}"));interrupted.restore(deposit.snapshot());
        assert interrupted.snapshot().get("pending").getAsBoolean()&&interrupted.snapshot().get("reconciling").getAsBoolean()
            &&!interrupted.reconcileInterruptedDeposit() : "An interrupted move restores into fresh reconciliation, never blind replay";
        try{interrupted.beginDeposit(2,1,first,11,List.of(first,empty,empty));throw new AssertionError("Interrupted moves cannot restart before fresh reconciliation");}catch(IllegalStateException expected){}
        deposit.observeContents(List.of(first,second,empty),1);
        assert deposit.snapshot().get("confirmed").getAsInt()==0 : "Wrong kit data cannot acknowledge a move";
        deposit.observeContents(List.of(empty,first,empty),1);
        assert deposit.snapshot().get("confirmed").getAsInt()==1&&!deposit.snapshot().get("pending").getAsBoolean() : "The post-placement snapshot verifies the exact destination even if a hopper drained an earlier slot";
        assert !deposit.acceptMenu(11)&&deposit.acceptMenu(10) : "Finishing one click cannot rebind verification to another menu";
        deposit.observeContents(List.of(empty,first,empty),1);
        assert deposit.snapshot().get("confirmed").getAsInt()==1 : "Duplicate snapshots cannot count a kit twice";
        deposit.beginDeposit(1,2,second,12,List.of(empty,first,empty));
        deposit.observeContents(List.of(empty,empty,second),0);
        assert deposit.snapshot().get("confirmed").getAsInt()==2 : "Previously confirmed kits may move onward through hoppers";
        var restored=deposit(json("{count:2}"));restored.restore(deposit.snapshot());assert restored.snapshot().get("confirmed").getAsInt()==2;
        var shortfall=deposit(json("{name:'Destination',count:35}"));
        var receiptTarget=BotStashDeposit.class.getDeclaredField("target");receiptTarget.setAccessible(true);receiptTarget.set(shortfall,new BlockPos(0,64,0));
        for(int i=0;i<33;i++){shortfall.beginDeposit(35-i,0,first,10+i,List.of(empty));shortfall.observeContents(List.of(first),34-i);}
        shortfall.finishMissingCarried();
        assert shortfall.result().get("deposited").getAsInt()==33&&shortfall.result().get("missing").getAsInt()==2&&shortfall.snapshot().get("finished").getAsBoolean();
        var recoveredShortfall=deposit(json("{name:'Destination',count:35}"));recoveredShortfall.restore(shortfall.snapshot());
        assert recoveredShortfall.result().equals(shortfall.result()) : "Restart preserves verified deposits and missing kits separately";
        var noCarried=deposit(json("{name:'Destination',count:2}"));noCarried.finishMissingCarried();
        assert noCarried.result().get("deposited").getAsInt()==0&&noCarried.result().get("missing").getAsInt()==2 : "Manual removal of the entire batch is not a bot deposit";
        var baseline=BotStashDeposit.copyContents(List.of(first));var original=first.copy();first.setCount(2);
        assert net.minecraft.world.item.ItemStack.matches(baseline.get(0),original) : "Predicted changes cannot mutate a receipt baseline";first.setCount(1);
        var packet=new java.util.ArrayList<>(List.of(first.copy(),second.copy(),empty));
        var menuSnapshot=BotStashDeposit.copyContents(packet);var containerSnapshot=menuSnapshot.subList(0,2);
        packet.get(0).setCount(2);packet.set(1,empty);
        assert containerSnapshot.get(0).getCount()==1&&net.minecraft.world.item.ItemStack.matches(containerSnapshot.get(1),second)
            &&containerSnapshot.get(0)==menuSnapshot.get(0) : "Shared container/full-menu views are detached from mutable packet stacks";
        try{containerSnapshot.set(0,empty);throw new AssertionError("Server snapshot view is mutable");}catch(UnsupportedOperationException expected){}

        var delta=BotStashDeposit.updatedSlot(menuSnapshot,10,11,1,empty);
        assert delta!=null&&delta.get(1).isEmpty()&&net.minecraft.world.item.ItemStack.matches(menuSnapshot.get(1),second)
            : "Hopper slot updates refresh the idle baseline without altering earlier transfer receipts";
        var mutable=first.copy();delta=BotStashDeposit.updatedSlot(menuSnapshot,10,11,2,mutable);mutable.setCount(42);
        assert delta.get(2).getCount()==1 : "Server delta stacks cannot be mutated by later local prediction";
        assert BotStashDeposit.updatedSlot(menuSnapshot,-1,11,0,empty)==null
            &&BotStashDeposit.updatedSlot(menuSnapshot,11,10,0,empty)==null
            &&BotStashDeposit.updatedSlot(menuSnapshot,11,12,-1,empty)==null
            &&BotStashDeposit.updatedSlot(menuSnapshot,11,12,3,empty)==null
            : "No unread snapshot, stale state, cursor slot or out-of-menu update may authorize a pickup";
        assert BotStashDeposit.updatedSlot(menuSnapshot,32767,0,0,empty)!=null
            &&BotStashDeposit.updatedSlot(menuSnapshot,0,32767,0,empty)==null
            : "Menu state rollover stays ordered";
        assert BotStashDeposit.mismatchedSlots(menuSnapshot,delta).equals(List.of(2));
        assert BotStashDeposit.reopenIdleSnapshot(20,0,false,empty,empty)
            &&BotStashDeposit.reopenIdleSnapshot(20,1,false,empty,empty)
            &&!BotStashDeposit.reopenIdleSnapshot(19,0,false,empty,empty)
            &&!BotStashDeposit.reopenIdleSnapshot(21,0,false,empty,empty)
            &&!BotStashDeposit.reopenIdleSnapshot(20,2,false,empty,empty)
            &&!BotStashDeposit.reopenIdleSnapshot(20,0,true,empty,empty)
            &&!BotStashDeposit.reopenIdleSnapshot(20,0,false,first,empty)
            &&!BotStashDeposit.reopenIdleSnapshot(20,0,false,empty,first)
            : "Idle snapshot recovery is bounded and cannot close a pending move or either held cursor";

        var inventory=new net.minecraft.world.entity.player.Inventory(null,new net.minecraft.world.entity.EntityEquipment());
        for(int i=0;i<36;i++)inventory.setItem(i,first.copy());
        var bottom=new net.minecraft.world.SimpleContainer(54);for(int i=0;i<36;i++)bottom.setItem(i,second.copy());
        var upper=new net.minecraft.world.SimpleContainer(54);var crossing=deposit(json("{count:36}"));int carried=36;
        receiptTarget.set(crossing,new BlockPos(0,64,0));
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
        depositReconciliation(first,second);
        depositRestart(first,second);
    }
    private static void bulkTransfers() throws Exception {
        var empty=net.minecraft.world.item.ItemStack.EMPTY;
        var kit=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        kit.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Bulk kit"));
        var wrong=kit.copy();wrong.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Other kit"));
        var click=dev.monocle.client.utils.player.InvUtils.confirmedQuickMove(7,80);
        assert click.containerId()==7&&click.stateId()==-1&&click.slotNum()==80&&click.buttonNum()==0
            &&click.containerInput()==net.minecraft.world.inventory.ContainerInput.QUICK_MOVE&&click.changedSlots().isEmpty()
            &&click.carriedItem()==net.minecraft.network.HashedStack.EMPTY : "Bulk shift-clicks still force authoritative snapshots without prediction";
        for(int rows:List.of(3,6))for(boolean withdrawal:List.of(true,false)){
            int start=rows*9;
            var inventory=new net.minecraft.world.entity.player.Inventory(null,new net.minecraft.world.entity.EntityEquipment());
            var chest=new net.minecraft.world.SimpleContainer(start);
            var menu=rows==3?net.minecraft.world.inventory.ChestMenu.threeRows(7,inventory,chest):net.minecraft.world.inventory.ChestMenu.sixRows(7,inventory,chest);
            assert BotStashBulk.supportedMenu(menu);
            for(int i=0;i<8;i++)menu.getSlot((withdrawal?0:start)+i).setByPlayer(kit.copy());
            // Preserve a personal non-kit slot and skip a visually similar but different kit.
            menu.getSlot((withdrawal?0:start)+1).setByPlayer(wrong.copy());
            menu.getSlot(menu.slots.size()-1).setByPlayer(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,3));
            var before=BotStashDeposit.copyContents(menu.slots.stream().map(s->s.getItem()).toList());
            var batch=startedBulk(before,start,4,100,withdrawal,true,false,s->net.minecraft.world.item.ItemStack.matches(before.get(s),kit));
            assert batch!=null&&batch.quick&&batch.moves.size()==4&&batch.moves.stream().noneMatch(m->m.source()==(withdrawal?1:start+1));
            assert batch.moves.getFirst().destination()==(withdrawal?menu.slots.size()-2:0) : "Withdrawals follow vanilla reverse inventory order; deposits fill forward";
            assert BotStashBulk.plan(before,start,3,100,withdrawal,true,false,s->true)==null : "Small quantities retain exact-slot transfers";
            assert BotStashBulk.plan(before,start,35,100,withdrawal,true,false,s->s<(withdrawal?3:start+3))==null : "Three eligible slots do not justify a burst";
            assert batch.observe(before,empty)==0&&!batch.canFallback(before,empty) : "An unchanged old menu cannot trigger replay";
            for(var move:batch.moves){
                menu.quickMoveStack(null,move.source());
                var after=BotStashDeposit.copyContents(menu.slots.stream().map(s->s.getItem()).toList());
                assert net.minecraft.world.item.ItemStack.matches(after.get(move.destination()),kit) : "Planned destinations match actual vanilla ChestMenu quick-move";
                assert batch.observe(after,kit)==0 : "A held cursor cannot acknowledge a transfer";
                assert batch.observe(after,empty)==1;
                assert batch.observe(after,empty)==0 : "Duplicate acknowledgements cannot count twice";
            }
            assert batch.complete()&&menu.getSlot(menu.slots.size()-1).getItem().getCount()==3;
        }
        // Dynamic withdrawals use single-kit confirmation; hopper-fed deposits can still burst.
        for(boolean withdrawal:List.of(true,false)){
            int start=5;
            var before=new ArrayList<>(java.util.Collections.nCopies(start+36,empty));
            for(int i=0;i<4;i++)before.set((withdrawal?0:start)+i,kit.copy());
            var batch=startedBulk(before,start,36,100,withdrawal,false,true,s->true);
            if(withdrawal){
                assert batch==null : "A changing hopper source must confirm one box before selecting another";
                assert BotStashBulk.plan(before,start,36,100,true,true,true,s->true)==null : "Quick-move cannot bypass the dynamic-source guard";
                continue;
            }
            assert batch!=null&&!batch.quick;
            var after=new ArrayList<>(before);
            for(int step=0;step<4;step++){
                var move=batch.moves.get(step);after.set(move.source(),empty);
                assert batch.observe(after,kit)==0 : "First half of an exact-slot pair is not a completed transfer";
                after.set(move.destination(),move.stack());
                if(withdrawal)after.set(move.source(),kit.copy());
                if(!withdrawal&&step>0)after.set(batch.moves.get(step-1).destination(),empty);
                assert batch.observe(after,empty)==1 : "Source refill and previously confirmed destination drainage are allowed";
            }
            assert batch.complete();
        }
        var before=new ArrayList<>(java.util.Collections.nCopies(27+36,empty));
        for(int i=0;i<5;i++)before.set(27+i,kit.copy());
        var batch=startedBulk(before,27,4,100,false,true,false,s->true);
        assert batch!=null;
        var after=new ArrayList<>(before);var first=batch.moves.getFirst();
        after.set(first.source(),empty);after.set(first.destination(),wrong);
        assert batch.observe(after,empty)==0 : "Player count loss alone is not a receipt; the exact kit must appear";
        batch.reopen(10);assert !batch.acceptsMenu(10)&&batch.acceptsMenu(11)&&!batch.canFallback(after,empty)
            : "Unknown changed inventory cannot fall back, and the old menu is excluded";
        after.set(first.destination(),first.stack());assert batch.observe(after,empty)==1;
        assert batch.canFallback(after,empty) : "A fresh menu can prove a confirmed partial prefix and untouched remaining sources";
        after.set(27+4,empty);assert !batch.canFallback(after,empty) : "Unexpected removal of a protected/unplanned slot blocks retry";
        after.set(27+4,kit.copy());
        var second=batch.moves.get(1);after.set(second.source(),empty);after.set(second.destination(),second.stack());
        assert !batch.canFallback(after,empty) : "An unacknowledged additional move is not silently replayed";
        assert batch.observe(after,empty)==1&&batch.canFallback(after,empty);

        var lostReceipts=startedBulk(before,27,4,100,false,true,false,s->true);
        var finalSnapshot=new ArrayList<>(before);
        for(var move:lostReceipts.moves){finalSnapshot.set(move.source(),empty);finalSnapshot.set(move.destination(),move.stack());}
        assert lostReceipts.observe(finalSnapshot,empty)==4&&lostReceipts.complete() : "A full final snapshot may prove every unacknowledged destination";
        var eatingReceipt=startedBulk(before,27,4,100,false,true,false,s->true);
        var eatingSnapshot=new ArrayList<>(finalSnapshot);
        eatingSnapshot.set(62,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE,61));
        assert eatingReceipt.observe(eatingSnapshot,empty)==4&&eatingReceipt.complete()
            : "Unrelated food changes cannot invalidate exact source and destination kit receipts";
        assert eatingReceipt.diagnostic().getAsJsonObject("lastSnapshot").getAsJsonArray("inventoryChanges").size()==5;
        var pearl=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENDER_PEARL);
        var pearlReceipt=startedBulk(before,27,4,100,false,true,false,s->true);
        var pearlSnapshot=new ArrayList<>(finalSnapshot);pearlSnapshot.set(27,pearl);
        assert pearlReceipt.observe(pearlSnapshot,empty)==4&&pearlReceipt.complete()
            : "A pearl in a freshly freed source slot cannot invalidate exact kit destination receipts";
        var partialPearl=startedBulk(before,27,4,100,false,true,false,s->true);
        var partialPearlSnapshot=new ArrayList<>(before);partialPearlSnapshot.set(27,pearl);partialPearlSnapshot.set(0,kit.copy());
        assert partialPearl.observe(partialPearlSnapshot,empty)==1;
        var next=partialPearl.moves.get(1);partialPearlSnapshot.set(next.source(),empty);partialPearlSnapshot.set(next.destination(),kit.copy());
        assert partialPearl.observe(partialPearlSnapshot,empty)==1 : "Previously verified source slots may receive non-kit drops too";
        var drainedReceipt=startedBulk(before,27,4,100,false,false,false,s->true);
        finalSnapshot.set(0,empty);
        assert drainedReceipt.observe(finalSnapshot,empty)==0 : "Skipped receipts plus hopper drainage must not invent deposits";
        drainedReceipt.reopen(10);assert !drainedReceipt.canFallback(finalSnapshot,empty);
        assert drainedReceipt.missingAfterReopen(finalSnapshot,empty)==4
            : "Missing destination evidence after a new menu retires absent cargo without inventing four deposits";
        var recovery=startedBulk(before,27,4,100,false,true,false,s->true);
        assert recovery.missingAfterReopen(before,empty)==-1 : "The old menu cannot authorize retirement or retry";
        recovery.reopen(10);
        assert recovery.missingAfterReopen(before,empty)==0;
        var nonPrefix=new ArrayList<>(before);nonPrefix.set(28,empty);nonPrefix.set(30,empty);
        assert recovery.missingAfterReopen(nonPrefix,empty)==2 : "A partial non-prefix result can continue only retained sources";
        assert recovery.missingAfterReopen(nonPrefix,kit)==-1 : "A held kit must settle before any fresh selection";
        nonPrefix.set(31,empty);assert recovery.missingAfterReopen(nonPrefix,empty)==-1 : "Unplanned kit removal remains guarded";
        nonPrefix.set(31,kit.copy());nonPrefix.set(28,wrong);
        assert recovery.missingAfterReopen(nonPrefix,empty)==-1 : "A different box in a pending source is not an empty source";
        nonPrefix.set(28,empty);nonPrefix.set(62,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE,62));
        assert recovery.missingAfterReopen(nonPrefix,empty)==2 : "Food and other non-kit changes do not strand known carried cargo";
        nonPrefix.set(28,pearl);
        assert recovery.missingAfterReopen(nonPrefix,empty)==2
            : "A pearl replacing absent cargo after the new-menu barrier is logged as missing, not a deposit receipt";
        var guardedWithdrawal=startedBulk(pickupContents(kit,empty),27,4,100,true,true,false,s->true);
        var occupiedPickup=pickupContents(kit,empty);occupiedPickup.set(guardedWithdrawal.moves.getFirst().destination(),pearl);
        guardedWithdrawal.reopen(10);
        assert guardedWithdrawal.observe(occupiedPickup,empty)==0&&!guardedWithdrawal.canFallback(occupiedPickup,empty)
            : "A pearl in a planned pickup destination is not proof of an unchanged withdrawal";
        var withdrawalRecovery=startedBulk(pickupContents(kit,empty),27,4,100,true,true,false,s->true);
        withdrawalRecovery.reopen(10);
        assert withdrawalRecovery.missingAfterReopen(pickupContents(kit,empty),empty)==-1 : "Withdrawal ownership still requires positive receipt evidence";
        before.get(27).setCount(2);
        assert batch.moves.getFirst().stack().getCount()==1 : "Planning owns its item snapshots";
        before.get(27).setCount(1);
        var tooFull=new ArrayList<>(before);for(int i=3;i<27;i++)tooFull.set(i,wrong);
        assert BotStashBulk.plan(tooFull,27,35,100,false,true,false,s->true)==null : "Near-full chests use the exact-slot fallback";
        var stacked=new ArrayList<>(before);stacked.get(27).setCount(2);stacked.set(31,empty);
        assert BotStashBulk.plan(stacked,27,35,100,false,true,false,s->true)==null : "Illegal stacked boxes are never included in a burst";
        before.get(27).setCount(1);

        var deposit=depositOpeningGuards();
        var bulkField=BotStashDeposit.class.getDeclaredField("bulk");bulkField.setAccessible(true);
        bulkField.set(deposit,startedBulk(before,27,4,100,false,true,false,s->true));
        var one=new ArrayList<>(before);one.set(27,empty);one.set(0,kit.copy());
        deposit.observeBulk(one,empty);deposit.observeBulk(one,empty);
        var saved=deposit.snapshot();assert saved.get("pending").getAsBoolean()&&saved.get("confirmed").getAsInt()==1;
        var restored=deposit(json("{count:4}"));restored.restore(saved);
        assert restored.snapshot().get("reconciling").getAsBoolean()&&restored.snapshot().get("confirmed").getAsInt()==1;
        try{deposit.finishMissingCarried();throw new AssertionError("A pending burst must not become a missing-kit success");}catch(IllegalStateException expected){}
        var depositRecovery=deposit(json("{name:'Destination',count:5}"));
        var retiring=startedBulk(before,27,4,100,false,true,false,s->true);
        bulkField.set(depositRecovery,retiring);
        assert !depositRecovery.reconcileBulk(nonPrefix,empty);
        retiring.reopen(10);assert depositRecovery.reconcileBulk(nonPrefix,empty);
        assert depositRecovery.result().get("deposited").getAsInt()==0&&depositRecovery.result().get("missing").getAsInt()==2
            &&depositRecovery.snapshot().get("bulkDisabled").getAsBoolean()&&!depositRecovery.snapshot().get("pending").getAsBoolean();
        assert !depositRecovery.reconcileBulk(nonPrefix,empty) : "A retired burst is never counted twice";
        var restoredRetirement=deposit(json("{name:'Destination',count:5}"));restoredRetirement.restore(depositRecovery.snapshot());
        assert restoredRetirement.result().get("missing").getAsInt()==2&&!restoredRetirement.snapshot().get("finished").getAsBoolean()
            : "A partial uncertain retirement survives checkpoints while retained cargo continues";
        var pickupAction=json("{name:'Source',count:8,depositMode:'Carry',picks:[{resource:'minecraft:obsidian',x:0,y:64,z:0,slot:0,dynamic:true}]}");
        pickupAction.addProperty("kitTypeId",dev.monocle.coordinator.StashCatalog.kitTypeId(BotStashScan.classify(List.of(kit)).get(0).getAsJsonObject()));
        var pickup=new BotStashResupply(pickupAction);
        var pickupBefore=new ArrayList<>(java.util.Collections.nCopies(27+36,empty));for(int i=0;i<4;i++)pickupBefore.set(i,kit.copy());
        var pickupBatch=startedBulk(pickupBefore,27,8,100,true,false,false,s->true);
        var pickupBulk=BotStashResupply.class.getDeclaredField("bulk");pickupBulk.setAccessible(true);pickupBulk.set(pickup,pickupBatch);
        var sourceColumn=BotStashResupply.class.getDeclaredField("sourceColumn");sourceColumn.setAccessible(true);sourceColumn.set(pickup,new BotStashScan.HopperColumn(List.of(new BlockPos(0,64,0),new BlockPos(0,66,0))));
        var target=BotStashResupply.class.getDeclaredField("target");target.setAccessible(true);target.set(pickup,new BlockPos(0,64,0));
        var pickupAfter=new ArrayList<>(pickupBefore);
        for(int i=0;i<4;i++){
            var move=pickupBatch.moves.get(i);pickupAfter.set(move.source(),empty);pickupAfter.set(move.destination(),move.stack());
            pickup.observeBulk(pickupAfter,empty);
            assert pickup.result().get("picked").getAsInt()==i+1 : "Every authoritative pickup immediately becomes a durable receipt";
            if(i==0){
                pickup.observeBulk(pickupAfter,empty);assert pickup.result().get("picked").getAsInt()==1;
                var interrupted=pickup.snapshot();assert interrupted.get("withdrawPending").getAsBoolean();
                try{new BotStashResupply(pickupAction).restore(interrupted);throw new AssertionError("An uncertain burst must not replay after restart");}catch(IllegalStateException expected){}
                try{pickup.advanceSourceChest();throw new AssertionError("A pending burst holds its source until reconciled");}catch(IllegalStateException expected){}
            }
        }
        assert !pickup.snapshot().get("withdrawPending").getAsBoolean()&&pickup.result().get("nextPick").getAsInt()==0;
        var disabled=BotStashResupply.class.getDeclaredField("bulkDisabled");disabled.setAccessible(true);disabled.setBoolean(pickup,true);
        var restoredPickup=new BotStashResupply(pickupAction);restoredPickup.restore(pickup.snapshot());
        assert restoredPickup.result().get("picked").getAsInt()==4&&restoredPickup.snapshot().get("bulkDisabled").getAsBoolean()
            : "Confirmed receipts and a selected fallback survive an action checkpoint";
        var echest=new BotStashResupply(pickupAction);
        var depositing=BotStashResupply.class.getDeclaredField("depositing");depositing.setAccessible(true);depositing.setBoolean(echest,true);
        pickupBulk.set(echest,startedBulk(before,27,4,100,false,true,false,s->true));
        echest.observeBulk(one,empty);echest.observeBulk(one,empty);
        assert echest.snapshot().get("depositPending").getAsBoolean()&&echest.snapshot().get("deposited").getAsInt()==1;
        for(Class<?> type:List.of(BotStashDeposit.class,BotStashResupply.class))try(var bytes=type.getResourceAsStream(type.getSimpleName()+".class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code,"settleBulk").containsAll(List.of("reopen","close","open","sameContents",type==BotStashDeposit.class?"reconcileBulk":"canFallback")) : "Fallback needs a new server menu and authoritative remaining inventory";
            assert calls(code,"acceptMenu").contains("acceptsMenu") : "Old-menu receipts cannot unlock fallback";
            assert calls(code,"inventory").contains("observeBulk") : "Receipts are recorded on packet arrival, not a later game tick";
        }
        try(var bytes=BotStashBulk.class.getResourceAsStream("BotStashBulk.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code,"send").containsAll(List.of("sendConfirmedQuickMove","sendConfirmedMove"))&&!calls(code,"send").contains("handleContainerInput")
                : "Both burst modes retain forced server snapshots and avoid predicted client clicks";
        }
        capacityBatches(kit,wrong);
    }
    private static BotStashBulk startedBulk(List<net.minecraft.world.item.ItemStack> contents,int start,int remaining,int tick,
                                           boolean withdrawal,boolean quick,boolean refill,java.util.function.IntPredicate eligible){
        var batch=BotStashBulk.plan(contents,start,remaining,tick,withdrawal,quick,refill,eligible);
        if(batch!=null)assert !batch.nextMoves(tick,contents,net.minecraft.world.item.ItemStack.EMPTY).isEmpty();
        return batch;
    }
    private static void capacityBatches(net.minecraft.world.item.ItemStack kit,net.minecraft.world.item.ItemStack wrong){
        var empty=net.minecraft.world.item.ItemStack.EMPTY;
        for(int slots:List.of(5,27,54))for(boolean quick:List.of(false,true)){
            var contents=new ArrayList<>(java.util.Collections.nCopies(slots+36,empty));
            for(int i=slots;i<contents.size();i++)contents.set(i,kit.copy());
            int capacity=Math.min(slots,36);
            var batch=BotStashBulk.plan(contents,slots,36,100,false,quick,false,s->true);
            assert batch!=null&&batch.moves.size()==capacity : "Plan the full carried inventory or exactly the available container capacity";
            assert batch.observe(contents,empty)==0&&batch.sent==0 : "A plan alone never issues or verifies a move";
            int tick=100;
            while(!batch.complete()){
                int before=batch.sent;
                var next=batch.nextMoves(tick,contents,empty);
                assert next.size()==Math.min(4,capacity-before)&&batch.sentTick==tick;
                assert batch.nextMoves(tick+1,contents,empty).isEmpty() : "A second part cannot precede authoritative receipts for the first";
                assert batch.sentTick==tick : "Waiting must not extend the confirmation timeout";
                for(var move:next){
                    contents.set(move.source(),empty);contents.set(move.destination(),move.stack());
                    assert batch.observe(contents,empty)==1;
                }
                assert batch.nextMoves(tick,contents,empty).isEmpty() : "Receipts arriving this tick cannot exceed the per-tick packet budget";
                tick++;
            }
            assert batch.verified==capacity&&batch.sent==capacity&&batch.nextMoves(tick,contents,empty).isEmpty();
        }
        var contents=new ArrayList<>(java.util.Collections.nCopies(54+36,empty));
        for(int i=54;i<contents.size();i++)contents.set(i,kit.copy());
        contents.set(88,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE,64));
        contents.set(89,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
        assert BotStashBulk.plan(contents,54,36,100,false,false,false,s->true).moves.size()==34
            : "An empty double chest accepts all 34 kits without consuming personal food or gear";
        for(int i=20;i<54;i++)contents.set(i,wrong.copy());
        assert BotStashBulk.plan(contents,54,34,100,false,false,false,s->true).moves.size()==20
            : "34 carried kits and 20 free destinations plan exactly 20";
        assert BotStashBulk.plan(contents,54,9,100,false,false,false,s->true).moves.size()==9 : "Never exceed the job remainder";
        assert BotStashBulk.plan(contents,54,36,100,false,false,false,s->s<61).moves.size()==7 : "Only eligible, unprotected cargo counts";
        var blocked=BotStashBulk.plan(contents,54,36,100,false,false,false,s->true);
        var changed=new ArrayList<>(contents);changed.set(0,wrong.copy());
        assert blocked.nextMoves(100,changed,empty).isEmpty()&&blocked.sent==0 : "Do not send into a destination occupied since planning";
        assert blocked.nextMoves(100,contents,kit).isEmpty()&&blocked.sent==0 : "A held cursor blocks the entire click pair";
        blocked.nextMoves(100,contents,empty);
        for(var move:blocked.moves.subList(0,4)){contents.set(move.source(),empty);contents.set(move.destination(),move.stack());}
        assert blocked.observe(contents,empty)==4;
        changed=new ArrayList<>(contents);changed.set(blocked.moves.get(4).source(),wrong.copy());
        assert blocked.nextMoves(101,changed,empty).isEmpty()&&blocked.sent==4 : "Unsent kit identity must still match";
        blocked.reopen(10);
        assert blocked.nextMoves(102,contents,empty).isEmpty()&&blocked.canFallback(contents,empty)
            &&blocked.missingAfterReopen(contents,empty)==0 : "Reopening retires the plan instead of issuing its unsent remainder";
        changed=new ArrayList<>(contents);changed.set(blocked.moves.get(4).source(),empty);
        assert blocked.missingAfterReopen(changed,empty)==-1 : "An unsent source cannot be retired as an uncertain issued deposit";
        var unissued=BotStashBulk.plan(contents,54,16,100,false,false,false,s->true);
        changed=new ArrayList<>(contents);
        for(var move:unissued.moves){changed.set(move.source(),empty);changed.set(move.destination(),move.stack());}
        assert unissued.observe(changed,empty)==0 : "Server inventory changes cannot acknowledge unissued moves";
        var shifted=BotStashBulk.plan(contents,54,16,100,false,true,false,s->true);
        changed=new ArrayList<>(contents);changed.set(0,empty);
        assert shifted.nextMoves(100,changed,empty).isEmpty() : "Quick-move ordering is rechecked against newly emptied earlier slots";
        var fed=BotStashBulk.plan(contents,54,16,100,false,false,true,s->true);
        var next=fed.nextMoves(100,contents,empty);
        for(var move:next){contents.set(move.source(),empty);contents.set(move.destination(),move.stack());}
        assert fed.observe(contents,empty)==4;
        contents.set(next.getFirst().destination(),empty);
        assert fed.nextMoves(101,contents,empty).size()==4&&fed.sentTick==101
            : "Previously verified destinations may drain while later exact-slot deposits continue";
        var source=new ArrayList<>(java.util.Collections.nCopies(54+36,empty));
        for(int i=0;i<54;i++)source.set(i,kit.copy());
        assert BotStashBulk.plan(source,54,36,100,true,true,false,s->true).moves.size()==4
            : "Withdrawal batch size is unchanged";
    }
    private static List<net.minecraft.world.item.ItemStack> pickupContents(net.minecraft.world.item.ItemStack kit,net.minecraft.world.item.ItemStack empty){
        var result=new ArrayList<>(java.util.Collections.nCopies(27+36,empty));
        for(int i=0;i<4;i++)result.set(i,kit.copy());
        return result;
    }
    private static void depositRestart(net.minecraft.world.item.ItemStack first,net.minecraft.world.item.ItemStack second) throws Exception {
        var empty=net.minecraft.world.item.ItemStack.EMPTY;
        var original=depositOpeningGuards();
        original.beginDeposit(2,0,first,10,List.of(empty,empty));original.observeContents(List.of(first,empty),1);
        var inventory=new ArrayList<>(java.util.Collections.nCopies(36,empty));inventory.set(8,second);
        original.beginDeposit(1,1,second,11,List.of(first,empty),8,inventory);
        var saved=original.snapshot();
        for(boolean applied:List.of(false,true)){
            var restored=deposit(json("{name:'Destination',count:2}"));restored.restore(saved);
            assert restored.snapshot().get("confirmed").getAsInt()==1&&restored.snapshot().get("pending").getAsBoolean()
                &&restored.pending()!=null&&restored.detail().contains("Recovering interrupted deposit") : "Keep confirmed receipts and renew host authority before revisiting stock";
            assert !restored.acceptMenu(10)&&!restored.canRetry(List.of(first,empty),inventory,empty)&&!restored.reconcileInterruptedDeposit()
                : "Saved menus and old slot predictions never authorize a restart click";
            try{restored.finishMissingCarried();throw new AssertionError("Recovery must first establish server-observed stock");}catch(IllegalStateException expected){}
            var resumed=deposit(json("{name:'Destination',count:2}"));resumed.restore(restored.snapshot());
            assert resumed.snapshot().get("pending").getAsBoolean()&&!resumed.reconcileInterruptedDeposit() : "Another disconnect preserves the reconciliation obligation";
            for(String field:List.of("approved","menuReady")){var f=BotStashDeposit.class.getDeclaredField(field);f.setAccessible(true);f.setBoolean(restored,true);}
            for(String field:List.of("menu","beforeMenu","serverAt","serverCarried")){var f=BotStashDeposit.class.getDeclaredField(field);f.setAccessible(true);f.setInt(restored,field.equals("serverCarried")?applied?0:1:field.equals("beforeMenu")?0:11);}
            var fresh=BotStashDeposit.class.getDeclaredField("serverInventory");fresh.setAccessible(true);
            assert !restored.reconcileInterruptedDeposit() : "An opening and a count alone are not a complete server inventory";
            var carried=applied?new ArrayList<>(java.util.Collections.nCopies(36,empty)):inventory;
            fresh.set(restored,carried);
            var cursor=BotStashDeposit.class.getDeclaredField("serverCursor");cursor.setAccessible(true);cursor.set(restored,second);
            assert !restored.reconcileInterruptedDeposit() : "A held cursor kit must settle before recovery can transfer anything";
            cursor.set(restored,empty);
            assert restored.reconcileInterruptedDeposit()&&!restored.snapshot().get("pending").getAsBoolean()
                &&restored.snapshot().get("confirmed").getAsInt()==1 : "Fresh stock clears the gate without inventing an old deposit receipt";
            if(applied){
                restored.finishMissingCarried();
                assert restored.result().get("deposited").getAsInt()==1&&restored.result().get("missing").getAsInt()==1
                    : "A box absent after interruption is logged as no longer carried, not fabricated as a verified deposit";
            }else{
                restored.beginDeposit(1,1,second,12,List.of(first,empty),8,carried);
                var receiptTarget=BotStashDeposit.class.getDeclaredField("target");receiptTarget.setAccessible(true);receiptTarget.set(restored,new BlockPos(0,64,0));
                restored.observeContents(List.of(first,second),0);restored.observeContents(List.of(first,second),0);
                assert restored.result().get("deposited").getAsInt()==2&&restored.result().get("missing").getAsInt()==0
                    : "A rolled-back kit is transferred from its fresh source and counted exactly once";
            }
        }
        var invalid=saved.deepCopy();invalid.addProperty("finished",true);
        try{deposit(json("{count:2}")).restore(invalid);throw new AssertionError("A finished checkpoint cannot contain an unverified move");}catch(IllegalArgumentException expected){}
    }
    private static void depositReconciliation(net.minecraft.world.item.ItemStack box,net.minecraft.world.item.ItemStack wrong) throws Exception {
        var empty=net.minecraft.world.item.ItemStack.EMPTY;
        List<net.minecraft.world.item.ItemStack> original=new ArrayList<>(java.util.Collections.nCopies(36,empty));original.set(8,box);
        var contents=List.of(empty,empty);
        var pending=depositOpeningGuards();pending.beginDeposit(1,1,box,10,contents,8,original);
        assert pending.canReopen(contents,original,empty) : "An unchanged server inventory can reopen, but is not itself permission to replay a click";
        assert !pending.canRetry(contents,original,empty) : "Even an unchanged receipt needs a new menu barrier";
        var held=new ArrayList<>(original);held.set(8,empty);
        assert pending.canReopen(contents,held,box) : "The exact kit on the cursor can return to its freed inventory slot on close";
        assert !pending.canReopen(contents,held,wrong)&&!pending.canReopen(contents,original,box)
            : "Wrong cursor items or a cursor kit without its freed source never authorize recovery";
        var changed=new ArrayList<>(original);changed.set(9,wrong);
        assert !pending.canReopen(contents,changed,empty)&&!pending.canReopen(List.of(empty,wrong),original,empty)
            : "Other inventory mutations and occupied destinations cannot be called unchanged";
        pending.beginReopen(30);
        assert !pending.canRetry(contents,original,empty)&&pending.snapshot().get("pending").getAsBoolean()
            : "Closing is neither a deposit receipt nor retry permission";
        var restart=deposit(json("{count:2}"));restart.restore(pending.snapshot());
        assert !restart.canRetry(contents,original,empty)&&!restart.reconcileInterruptedDeposit()&&restart.snapshot().get("pending").getAsBoolean()
            : "A reopened uncertain move cannot replay its old slot after restart";
        var opening=BotStashDeposit.class.getDeclaredField("beforeMenu");opening.setAccessible(true);opening.setInt(pending,0);
        assert !pending.acceptMenu(10)&&pending.acceptMenu(11) : "Late packets from the pre-recovery menu cannot satisfy the fresh-menu barrier";
        assert !pending.canRetry(contents,held,empty)&&!pending.canRetry(contents,original,box)&&!pending.canRetry(List.of(empty,wrong),original,empty)
            : "A fresh menu still needs empty cursor/destination and the original cargo restored";
        assert pending.canRetry(contents,original,empty);
        var pearl=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENDER_PEARL);
        var incidental=new ArrayList<>(original);incidental.set(9,pearl);
        assert pending.canRetry(contents,incidental,empty) : "Unrelated non-kit pickups do not prevent retrying retained cargo";
        try{pending.retryDeposit(31,contents,held,empty);throw new AssertionError("Missing kit replayed");}catch(IllegalStateException expected){}
        pending.retryDeposit(31,contents,original,empty);
        pending.beginReopen(51);opening.setInt(pending,0);
        assert !pending.acceptMenu(11)&&pending.acceptMenu(12);
        pending.retryDeposit(52,contents,original,empty);
        assert !pending.canReopen(contents,original,empty) : "Each individual kit gets at most two reopen/retry attempts";
        try{pending.beginReopen(72);throw new AssertionError("Unbounded deposit retries");}catch(IllegalStateException expected){}
        pending.observeContents(List.of(empty,box),0);pending.observeContents(List.of(empty,box),0);
        assert pending.snapshot().get("confirmed").getAsInt()==1&&!pending.snapshot().get("pending").getAsBoolean()
            : "A recovered move still needs the exact destination receipt, counted only once";
        var alreadyMoved=depositOpeningGuards();alreadyMoved.beginDeposit(1,1,box,10,contents,8,original);alreadyMoved.beginReopen(30);
        alreadyMoved.observeContents(List.of(empty,box),0);
        assert alreadyMoved.snapshot().get("confirmed").getAsInt()==1&&!alreadyMoved.canRetry(contents,original,empty)
            : "A fresh-menu snapshot proving the original deposit completes it instead of replaying";
        var drained=depositOpeningGuards();drained.beginDeposit(1,1,box,10,contents,8,original);drained.beginReopen(30);opening.setInt(drained,0);assert drained.acceptMenu(11);
        drained.observeContents(contents,0);
        assert !drained.canRetry(contents,held,empty)&&drained.snapshot().get("pending").getAsBoolean()
            : "Inventory depletion without an exact destination receipt stays uncertain, even if a hopper might have moved the box";
        var relocated=depositOpeningGuards();relocated.beginDeposit(1,1,box,10,contents,8,original);relocated.beginReopen(30);opening.setInt(relocated,0);assert relocated.acceptMenu(11);
        var returned=new ArrayList<>(held);returned.set(0,box.copy());
        assert relocated.canRetry(contents,returned,empty) : "Vanilla cursor return may restore the exact kit in a different formerly empty slot";
        var replaced=new ArrayList<>(returned);replaced.set(1,wrong);
        assert !relocated.canRetry(contents,replaced,empty) : "One restored kit cannot excuse unrelated inventory mutations";
        returned.set(8,pearl);returned.set(9,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE,62));
        assert relocated.canRetry(contents,returned,empty) : "A pearl may occupy the old source while the exact returned kit is safely reselected elsewhere";
        relocated.retryDeposit(31,contents,returned,empty);
        assert relocated.snapshot().getAsJsonObject("verification").get("sourceInventorySlot").getAsInt()==0
            &&relocated.canReopen(contents,returned,empty) : "Retry uses the returned kit's actual slot and a new exact inventory baseline";
        relocated.observeContents(List.of(empty,box),0);assert relocated.snapshot().get("confirmed").getAsInt()==1;
    }
    private static void roundRobinActions() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("monocle-round-robin-actions-");String scope="server\nminecraft:the_end",worker=java.util.UUID.randomUUID().toString();
        var bounds=json("{name:'Source',homeName:'source',lazyMode:false,minX:0,maxX:0,minY:64,maxY:64,minZ:0,maxZ:0}");
        var box=json("{slot:0,item:'minecraft:blue_shulker_box',quantity:1,name:'Kit',contentsKnown:true,items:{'minecraft:obsidian':1728}}");
        String kit=dev.monocle.coordinator.StashCatalog.kitTypeId(box);
        for(int i=0;i<3;i++){
            var stash=bounds.deepCopy();stash.addProperty("name",i==0?"Source":i==1?"Other":"Target");stash.addProperty("minX",i*10);stash.addProperty("maxX",i*10);
            dev.monocle.coordinator.StashCatalog.define(root,"A",scope,stash);
            if(i<2){var seen=json("{x:0,y:64,z:0,status:'observed',block:'minecraft:chest',items:{},shulkers:[]}");seen.addProperty("x",i*10);seen.getAsJsonArray("shulkers").add(box);dev.monocle.coordinator.StashCatalog.save(root,"A",scope,stash,seen);}
        }
        var request=json("{count:36,routes:[{name:'Source',kitTypeId:'"+kit+"',targetStashName:'Target'},{name:'Other',kitTypeId:'"+kit+"',targetStashName:'Target'}]}");
        var workflows=new BotWorkflows(null);var task=json("{crew:'A',server:'server',dimension:'minecraft:the_end'}");task.add("args",dev.monocle.coordinator.StashCatalog.kitTransfer(root,"A",scope,request,worker));task.add("package",workflows.packageWorkflows("task-stash-transfer"));
        var plan=dev.monocle.coordinator.StashCatalog.kitTransferPlan(root,task,worker,json("{type:'KitTransferPlan',index:1}")).getAsJsonObject("args");
        var taking=BotLua.next(workflows.get("task-kit-delivery").script(),new JsonObject(),plan,null,null);
        assert BotActions.validate(taking.action()).get("oneBatch").getAsBoolean() : "Actual planned Round Robin actions fit the native trust boundary";
        var mixedPlan=plan.deepCopy();mixedPlan.addProperty("mixedLoad",true);
        assert BotActions.validate(mixedPlan).get("mixedLoad").getAsBoolean() : "Mixed pickup plans preserve the native validation boundary";
        var mixedReceipt=new BotStashResupply(mixedPlan).workflowResult();
        assert mixedReceipt.has("batchCapacity")&&mixedReceipt.getAsJsonArray("personalBoxes").isEmpty() : "Mixed workflows receive an explicit inventory budget and personal-box baseline";
        mixedPlan.addProperty("mixedLoad","yes");try{BotActions.validate(mixedPlan);throw new AssertionError("Malformed mixed collection accepted");}catch(IllegalArgumentException expected){}
        var partial=BotLua.next(workflows.get("task-kit-delivery").script(),taking.state(),plan,json("{picked:1,nextPick:0,sourceExhausted:false,inventoryFull:true}"),null);
        assert partial.action().get("type").getAsString().equals("StashDeposit")&&partial.action().get("count").getAsInt()==1;
        var partialDone=BotLua.next(workflows.get("task-kit-delivery").script(),partial.state(),plan,json("{deposited:1,missing:0}"),null);
        var partialResult=partialDone.action().getAsJsonObject("result");
        assert partialResult.get("delivered").getAsInt()==1&&partialResult.get("nextPick").getAsInt()==0
            &&!partialResult.get("sourceExhausted").getAsBoolean() : "A full inventory delivers one verified kit without skipping its unfinished chest";
        var ongoing=plan.deepCopy();ongoing.remove("oneBatch");
        var continuous=BotLua.next(workflows.get("task-kit-delivery").script(),partial.state(),ongoing,json("{deposited:1,missing:0}"),null);
        assert continuous.action().get("type").getAsString().equals("StashResupply")
            &&continuous.action().get("count").getAsInt()==36&&continuous.action().get("pickIndex").getAsInt()==0
            : "After a partial deposit the normal transfer immediately starts a full-sized batch at the same source";
        var storing=BotLua.next(workflows.get("task-kit-delivery").script(),taking.state(),plan,json("{picked:1,nextPick:1,sourceExhausted:true}"),null);
        assert BotActions.validate(storing.action()).get("count").getAsInt()==1;
        assert plan.getAsJsonArray("knownKitTypeIds").asList().stream().anyMatch(id->id.getAsString().equals(kit));
        String other="00000000-0000-0000-0000-000000000002";
        plan.getAsJsonArray("knownKitTypeIds").add(other);
        var picked=json("{picked:2,nextPick:0,sourceExhausted:false,inventoryFull:true,incidental:[{kitTypeId:'"+other+"',kitExemplar:{'minecraft:crying_obsidian':1728},count:1,includeIncomplete:true,incomplete:false}],protectedBoxes:[]}");
        var primary=BotLua.next(workflows.get("task-kit-delivery").script(),taking.state(),plan,picked,null);
        assert primary.action().get("kitTypeId").getAsString().equals(kit)&&primary.action().get("count").getAsInt()==2;
        var extra=BotLua.next(workflows.get("task-kit-delivery").script(),primary.state(),plan,json("{deposited:2,missing:0}"),null);
        assert extra.action().get("kitTypeId").getAsString().equals(other)&&extra.action().get("count").getAsInt()==1
            &&!extra.action().has("targetColumn")&&!extra.action().has("columnKitTypeIds")&&BotActions.validate(extra.action()).has("protectedBoxes")
            : "Known incidental kits use their own reserved columns and protect pre-job supplies";
        var completed=BotLua.next(workflows.get("task-kit-delivery").script(),extra.state(),plan,json("{deposited:1,missing:0}"),null);
        assert completed.action().getAsJsonObject("result").get("delivered").getAsInt()==2
            &&completed.action().getAsJsonObject("result").get("incidentalDelivered").getAsInt()==1
            : "Incidental deposits never inflate requested-kit receipts";
        picked.addProperty("picked",0);
        var extraOnly=BotLua.next(workflows.get("task-kit-delivery").script(),taking.state(),plan,picked,null);
        var afterWait=BotLua.next(workflows.get("task-kit-delivery").script(),extraOnly.state(),plan,json("{}"),null);
        assert afterWait.action().get("kitTypeId").getAsString().equals(other) : "An incidental-only full load is deposited, not mistaken for source exhaustion";
    }
    private static void kitVariants(net.minecraft.world.item.ItemStack original,JsonObject originalCriteria){
        var unrelated=original.copy();unrelated.set(net.minecraft.core.component.DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EXPERIENCE_BOTTLE,64))));
        var criteria=originalCriteria.deepCopy();criteria.addProperty("includeIncomplete",true);
        assert !BotStashScan.matchesKit(unrelated,criteria) : "Same name and color cannot turn unrelated contents into incomplete kits";
        var legacy=criteria.deepCopy();legacy.addProperty("kitTypeId",dev.monocle.coordinator.KitTypes.family(BotStashScan.classify(List.of(original)).get(0).getAsJsonObject()));
        assert BotStashScan.matchesKit(original,legacy)&&!BotStashScan.matchesKit(unrelated,legacy) : "Captured v1 jobs use their exemplar, not name alone";
        assert BotStashDeposit.compatibleContents(List.of(original),legacy)&&!BotStashDeposit.compatibleContents(List.of(unrelated),legacy);
        var full=original.copy();full.set(net.minecraft.core.component.DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE,64),new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OBSIDIAN,64))));
        JsonObject db=json("{containers:{},kitTypes:{},kitAliases:{},kitIdentityVersion:2}"),record=json("{status:'observed',shulkers:[]}");record.add("shulkers",BotStashScan.classify(List.of(full,unrelated)));dev.monocle.coordinator.KitTypes.learn(db,record);
        var action=criteria.deepCopy();String id=dev.monocle.coordinator.StashCatalog.kitTypeId(BotStashScan.classify(List.of(full)).get(0).getAsJsonObject());action.addProperty("kitTypeId",id);action.add("kitExemplar",BotStashScan.classify(List.of(full)).get(0).getAsJsonObject().get("items").deepCopy());action.add("kitIdentities",dev.monocle.coordinator.KitTypes.identities(db.getAsJsonObject("kitTypes")));
        assert BotStashScan.matchesKit(original,action)&&BotStashDeposit.compatibleContents(List.of(original),action)&&!BotStashDeposit.compatibleContents(List.of(unrelated),action) : "Missing-item partial kits share the learned full-kit identity at pickup and deposit";
        var validated=json("{type:'StashDeposit',name:'Target',homeName:'target',minX:0,maxX:0,minY:0,maxY:0,minZ:0,maxZ:0,count:1}");action.entrySet().forEach(e->validated.add(e.getKey(),e.getValue().deepCopy()));BotActions.validate(validated);
        for(String malformed:List.of("[]","{'not-a-uuid':{familyId:'x',items:{}}}","{'00000000-0000-0000-0000-000000000001':{familyId:'00000000-0000-0000-0000-000000000001',items:{'minecraft:stone':2}}}","{'00000000-0000-0000-0000-000000000001':{familyId:'00000000-0000-0000-0000-000000000001',items:{},extra:1}}")){
            var bad=validated.deepCopy();bad.add("kitIdentities",JsonParser.parseString(malformed));try{BotActions.validate(bad);throw new AssertionError("Malformed kit identities accepted");}catch(IllegalArgumentException expected){}
        }
    }
    private static void incidentalKits(net.minecraft.world.item.ItemStack box,net.minecraft.world.item.ItemStack wrong) throws Exception {
        var personal=json("{slot:0,item:'minecraft:purple_shulker_box',quantity:1,name:'Food',contentsKnown:true,items:{'minecraft:golden_apple':64}}");
        var extra=personal.deepCopy();extra.addProperty("slot",1);
        JsonArray before=new JsonArray();before.add(personal);JsonArray current=before.deepCopy();current.add(extra);
        var acquired=BotStashResupply.newBoxes(before,current,"requested");
        assert acquired.size()==1&&acquired.get(0).getAsJsonObject().get("slot").getAsInt()==1 : "Personal boxes stay protected when an identical extra is collected";
        assert BotStashResupply.newBoxes(before,current,dev.monocle.coordinator.StashCatalog.kitTypeId(extra)).isEmpty();
        assert BotStashResupply.newBoxes(before,before,"requested").isEmpty() : "Pre-existing unknown kits are never discard candidates";
        var action=json("{name:'Source',picks:[],kitTypeId:'00000000-0000-0000-0000-000000000001',count:36,knownKitTypeIds:[],targetStash:{}}");
        var empty=net.minecraft.world.item.ItemStack.EMPTY;
        var original=new ArrayList<>(java.util.Collections.nCopies(38,empty));original.set(0,box);
        var opening=BotStashResupply.class.getDeclaredField("openingRequested");opening.setAccessible(true);
        var pickup=new BotStashResupply(action);opening.setBoolean(pickup,true);assert pickup.acceptMenu(60);
        pickup.beginWithdrawal(0,0,2,box,100,original,true,2);
        var collision=new ArrayList<>(original);collision.set(2,wrong);collision.set(0,empty);
        assert pickup.canReopenWithdrawal(collision,0,empty)&&!pickup.canReopenWithdrawal(collision,0,wrong)
            : "A fresh incidental shulker can reconcile an unapplied click only with an empty cursor";
        pickup.beginWithdrawalReopen(120,empty);opening.setBoolean(pickup,true);assert pickup.acceptMenu(61);
        assert pickup.canReselectWithdrawal(collision,0,empty)&&!pickup.observeWithdrawal(collision,0,empty,2);
        pickup.reselectWithdrawal(collision,0,empty);
        assert !pickup.snapshot().get("withdrawPending").getAsBoolean()&&pickup.result().get("picked").getAsInt()==0;
        var changed=new ArrayList<>(original);changed.set(2,box);
        var probe=new BotStashResupply(action);probe.beginWithdrawal(0,0,2,box,100,original,true,2);
        assert !probe.onlyIncidentalAdditions(changed) : "An expected box is never treated as an unapplied incidental pickup";
        changed.set(2,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE));assert !probe.onlyIncidentalAdditions(changed);
        original.set(3,wrong);var lost=new ArrayList<>(original);lost.set(3,empty);probe=new BotStashResupply(action);probe.beginWithdrawal(0,0,2,box,100,original,true,2);
        assert !probe.onlyIncidentalAdditions(lost) : "Personal stock removals still forbid replay";
    }
    private static void silentWithdrawalRecovery(net.minecraft.world.item.ItemStack box,net.minecraft.world.item.ItemStack wrong) throws Exception {
        var empty=net.minecraft.world.item.ItemStack.EMPTY;
        var original=new ArrayList<>(java.util.Collections.nCopies(38,empty));original.set(0,box);
        var action=json("{name:'Source',picks:[],kitTypeId:'00000000-0000-0000-0000-000000000001',count:36}");
        var opening=BotStashResupply.class.getDeclaredField("openingRequested");opening.setAccessible(true);
        var serverAt=BotStashResupply.class.getDeclaredField("serverAt");serverAt.setAccessible(true);
        var pending=new BotStashResupply(action);opening.setBoolean(pending,true);assert pending.acceptMenu(10);
        pending.beginWithdrawal(0,0,2,box,100,original,true,2);
        assert !pending.needsWithdrawalSnapshot(119,empty)&&pending.needsWithdrawalSnapshot(120,empty)
            &&pending.needsWithdrawalSnapshot(201,empty) : "A missing response requests a fresh read before the old timeout, including a delayed tick";
        assert !pending.needsWithdrawalSnapshot(120,box)&&!pending.needsWithdrawalSnapshot(120,wrong)
            : "Silent recovery never closes a held cursor without authoritative ownership evidence";
        serverAt.setInt(pending,120);
        assert !pending.needsWithdrawalSnapshot(120,empty)&&pending.canReopenWithdrawal(original,0,empty)
            : "Received snapshots retain the existing guarded reconciliation path";
        serverAt.setInt(pending,-1);pending.beginWithdrawalReopen(120,empty);
        assert !pending.needsWithdrawalSnapshot(140,empty)&&!pending.canReselectWithdrawal(original,0,empty)
            &&pending.snapshot().get("withdrawPending").getAsBoolean() : "Reopening alone does not retire or replay the pickup";
        opening.setBoolean(pending,true);assert !pending.acceptMenu(10)&&pending.acceptMenu(11);
        var received=new ArrayList<>(original);received.set(2,box);
        assert pending.observeWithdrawal(received,1,empty,1)&&pending.observeWithdrawal(received,1,empty,1)
            &&!pending.needsWithdrawalSnapshot(140,empty) : "Fresh inventory confirms a previously applied silent pickup exactly once";
        var unapplied=new BotStashResupply(action);opening.setBoolean(unapplied,true);assert unapplied.acceptMenu(20);
        unapplied.beginWithdrawal(0,0,2,box,100,original,true,2);unapplied.beginWithdrawalReopen(120,empty);
        opening.setBoolean(unapplied,true);assert unapplied.acceptMenu(21);
        assert !unapplied.observeWithdrawal(original,0,empty,1)&&unapplied.canReselectWithdrawal(original,0,empty);
        unapplied.reselectWithdrawal(original,0,empty);
        assert !unapplied.snapshot().get("withdrawPending").getAsBoolean()&&unapplied.result().get("picked").getAsInt()==0;
        unapplied.beginWithdrawal(0,0,2,box,121,original,true,2);
        assert unapplied.needsWithdrawalSnapshot(141,empty);unapplied.beginWithdrawalReopen(141,empty);
        opening.setBoolean(unapplied,true);assert unapplied.acceptMenu(22);
        unapplied.reselectWithdrawal(original,0,empty);unapplied.beginWithdrawal(0,0,2,box,142,original,true,2);
        assert !unapplied.needsWithdrawalSnapshot(162,empty) : "Missing packets cannot bypass the two-reopen limit";
        var legacy=new BotStashResupply(action);legacy.beginWithdrawal(0,0,2,box,100,original,true);
        assert !legacy.needsWithdrawalSnapshot(120,empty) : "Recovery requires the exact original player-inventory baseline";
    }
    private static void withdrawalReconciliation(net.minecraft.world.item.ItemStack box,net.minecraft.world.item.ItemStack wrong) throws Exception {
        var empty=net.minecraft.world.item.ItemStack.EMPTY;
        List<net.minecraft.world.item.ItemStack> original=new ArrayList<>(java.util.Collections.nCopies(38,empty));original.set(0,box);original.set(1,wrong);
        for(int i=3;i<=30;i++)original.set(i,box.copy());
        var action=json("{name:'Source',picks:[],kitTypeId:'00000000-0000-0000-0000-000000000001',count:36}");
        var opening=BotStashResupply.class.getDeclaredField("openingRequested");opening.setAccessible(true);
        var pending=new BotStashResupply(action);opening.setBoolean(pending,true);assert pending.acceptMenu(10);
        pending.beginWithdrawal(28,0,2,box,100,original,true,2);
        assert !pending.approaching() : "An unconfirmed pickup cannot wander away or bypass its verification deadline";
        var shifted=new ArrayList<>(original);shifted.set(0,empty);
        assert pending.canReopenWithdrawal(original,28,empty)&&pending.canReopenWithdrawal(shifted,28,empty)
            : "Unchanged player inventory can reconcile even if a hopper moved the selected source before clicking";
        assert pending.canReopenWithdrawal(shifted,28,box)&&!pending.canReopenWithdrawal(shifted,28,wrong)
            : "Only the exact expected cursor box can be safely returned during close";
        var changed=new ArrayList<>(shifted);changed.set(37,wrong);
        assert !pending.canReopenWithdrawal(changed,28,empty)&&!pending.canReopenWithdrawal(shifted,29,empty);
        assert !pending.canReselectWithdrawal(shifted,28,empty) : "A negative snapshot in the old menu never authorizes replay";
        pending.beginWithdrawalReopen(120,empty);
        assert pending.snapshot().get("withdrawPending").getAsBoolean()&&!pending.canReselectWithdrawal(shifted,28,empty);
        try{new BotStashResupply(action).restore(pending.snapshot());throw new AssertionError("Pending pickup replayed after restart");}catch(IllegalStateException expected){}
        opening.setBoolean(pending,true);assert !pending.acceptMenu(10)&&pending.acceptMenu(11)&&!pending.acceptMenu(12)
            : "Only the freshly opened source menu can reconcile the pending pickup";
        assert !pending.observeWithdrawal(shifted,28,empty,23)&&pending.canReselectWithdrawal(shifted,28,empty);
        try{pending.reselectWithdrawal(changed,28,empty);throw new AssertionError("Unrelated inventory changes ignored");}catch(IllegalStateException expected){}
        pending.reselectWithdrawal(shifted,28,empty);
        assert !pending.snapshot().get("withdrawPending").getAsBoolean()&&pending.result().get("picked").getAsInt()==0
            : "An unapplied pickup is cleared for reselection, never counted as a receipt";
        var fresh=new ArrayList<>(shifted);fresh.set(1,box);
        pending.beginWithdrawal(28,1,2,box,121,fresh,true,2);
        pending.beginWithdrawalReopen(141,empty);opening.setBoolean(pending,true);assert !pending.acceptMenu(11)&&pending.acceptMenu(12);
        pending.reselectWithdrawal(fresh,28,empty);pending.beginWithdrawal(28,1,2,box,142,fresh,true,2);
        assert !pending.canReopenWithdrawal(fresh,28,empty) : "Reselecting an unchanged source does not reset the two-attempt limit";
        try{pending.beginWithdrawalReopen(162,empty);throw new AssertionError("Unbounded pickup recovery");}catch(IllegalStateException expected){}
        var received=new ArrayList<>(fresh);received.set(2,box);
        assert pending.observeWithdrawal(received,29,empty,24)&&pending.observeWithdrawal(fresh,28,empty,23)
            : "A real exact pickup confirms once and late snapshots cannot revoke it";
        assert pending.snapshot().getAsJsonObject("verification").get("reopenAttempts").getAsInt()==2;

        var held=new BotStashResupply(action);opening.setBoolean(held,true);assert held.acceptMenu(20);
        held.beginWithdrawal(28,0,2,box,100,original,false,2);
        assert !held.canReopenWithdrawal(original,28,box)&&held.canReopenWithdrawal(shifted,28,box)
            : "Ordinary sources need the held kit's original slot freed before cursor recovery";
        held.beginWithdrawalReopen(120,box);opening.setBoolean(held,true);assert !held.acceptMenu(20)&&held.acceptMenu(21);
        assert !held.canReselectWithdrawal(shifted,28,empty) : "A known held kit cannot disappear and be called an unapplied move";
        var returned=new ArrayList<>(shifted);returned.set(37,box);
        var wrongReturn=new ArrayList<>(shifted);wrongReturn.set(37,wrong);
        assert !held.observeWithdrawal(wrongReturn,29,empty,2)&&!held.observeWithdrawal(returned,29,box,3)
            : "Wrong kits and occupied cursors do not confirm recovery";
        var extra=new ArrayList<>(returned);extra.set(36,wrong);
        assert !held.observeWithdrawal(extra,29,empty,4) : "An exact kit gain cannot excuse unrelated inventory changes";
        var extraAtDestination=new ArrayList<>(shifted);extraAtDestination.set(2,box);extraAtDestination.set(36,wrong);
        assert !held.observeWithdrawal(extraAtDestination,29,empty,4) : "Reopening requires exact inventory reconciliation even at the originally intended destination";
        assert held.observeWithdrawal(returned,29,empty,5)
            &&held.snapshot().getAsJsonObject("verification").get("destinationSlot").getAsInt()==37
            &&held.snapshot().getAsJsonObject("verification").get("reconciledInventoryReceipt").getAsBoolean()
            : "Closing can return the held pickup to a different previously empty player slot without losing or repeating it";
        assert held.observeWithdrawal(shifted,28,box,1) : "Recovered receipts also resist late intermediate packets";

        var serverClosed=new BotStashResupply(action);opening.setBoolean(serverClosed,true);assert serverClosed.acceptMenu(30);
        serverClosed.beginWithdrawal(28,0,2,box,100,original,false,2);serverClosed.beginWithdrawalReopen(120,empty);opening.setBoolean(serverClosed,true);assert serverClosed.acceptMenu(31);
        var stillSource=new ArrayList<>(original);stillSource.set(37,box);
        assert !serverClosed.observeWithdrawal(stillSource,29,empty,2) : "An ordinary unchanged source cannot claim a kit gained elsewhere";
        assert serverClosed.observeWithdrawal(returned,29,empty,3) : "Fresh exact inventory gain and ordinary source removal also reconcile a server-closed menu";
        withdrawalBenignInventoryChanges(box,wrong);
    }
    private static void unappliedWithdrawalRecovery(net.minecraft.world.item.ItemStack box,net.minecraft.world.item.ItemStack wrong) throws Exception {
        var empty=net.minecraft.world.item.ItemStack.EMPTY;
        var original=new ArrayList<>(java.util.Collections.nCopies(38,empty));original.set(0,box);original.set(3,box.copy());
        var action=json("{name:'Source',picks:[{x:1,y:2,z:3,resource:'minecraft:purple_shulker_box'}],kitTypeId:'00000000-0000-0000-0000-000000000001',count:36,transferAll:true,depositMode:'Carry'}");
        var opening=BotStashResupply.class.getDeclaredField("openingRequested");opening.setAccessible(true);
        var target=BotStashResupply.class.getDeclaredField("target");target.setAccessible(true);
        var held=BotStashResupply.class.getDeclaredField("withdrawHeldOnClose");held.setAccessible(true);
        var receipts=BotStashResupply.class.getDeclaredField("receipt");receipts.setAccessible(true);
        for(String mode:List.of("drain","local","ender-chest","fixed-count")){
            var criteria=action.deepCopy();
            if(mode.equals("local"))criteria.addProperty("requireLocal",true);
            if(mode.equals("ender-chest"))criteria.addProperty("depositMode","EnderChest");
            if(mode.equals("fixed-count"))criteria.addProperty("transferAll",false);
            var pending=new BotStashResupply(criteria);target.set(pending,new net.minecraft.core.BlockPos(1,2,3));
            ((JsonArray)receipts.get(pending)).add(json("{x:1,y:2,z:3,resource:'minecraft:purple_shulker_box',items:{'minecraft:obsidian':64}}"));
            opening.setBoolean(pending,true);assert pending.acceptMenu(10);
            pending.beginWithdrawal(1,0,2,box,100,original,true,2);
            assert !pending.deferUnappliedWithdrawal(original,1,empty) : "An old menu cannot settle an unapplied pickup";
            pending.beginWithdrawalReopen(120,empty);opening.setBoolean(pending,true);assert pending.acceptMenu(11);
            assert !pending.observeWithdrawal(original,1,empty,1)&&!pending.deferUnappliedWithdrawal(original,1,empty)
                : "The first reconciliation still permits a bounded reselection";
            pending.reselectWithdrawal(original,1,empty);pending.beginWithdrawal(1,0,2,box,121,original,true,2);
            pending.beginWithdrawalReopen(141,empty);
            assert !pending.deferUnappliedWithdrawal(original,1,empty) : "A second reopen needs a new server menu";
            opening.setBoolean(pending,true);assert !pending.acceptMenu(11)&&pending.acceptMenu(12);
            assert !pending.observeWithdrawal(original,1,empty,2);
            var lost=new ArrayList<>(original);lost.set(3,empty);
            var changed=new ArrayList<>(original);changed.set(3,wrong);
            assert !pending.deferUnappliedWithdrawal(lost,1,empty)&&!pending.deferUnappliedWithdrawal(changed,1,empty)
                &&!pending.deferUnappliedWithdrawal(original,2,empty)&&!pending.deferUnappliedWithdrawal(original,1,box)
                : "Missing/changed cargo, ambiguous gains and held cursors remain unresolved";
            held.setBoolean(pending,true);assert !pending.deferUnappliedWithdrawal(original,1,empty);held.setBoolean(pending,false);
            if(!mode.equals("drain")){
                assert !pending.deferUnappliedWithdrawal(original,1,empty)&&pending.snapshot().get("withdrawPending").getAsBoolean()
                    : "Local leases, ender-chest stocking and exact-count pickups retain their existing guards";
                continue;
            }
            var before=pending.snapshot().getAsJsonArray("receipt").deepCopy();
            assert pending.deferUnappliedWithdrawal(original,1,empty);
            var snapshot=pending.snapshot();
            assert !snapshot.get("withdrawPending").getAsBoolean()&&snapshot.get("index").getAsInt()==1
                &&snapshot.getAsJsonArray("receipt").equals(before)&&snapshot.getAsJsonArray("drainedSources").isEmpty()
                &&snapshot.getAsJsonArray("deferredSources").size()==1&&snapshot.getAsJsonObject("verification").get("unappliedDeferred").getAsBoolean()
                : "Deferral retains confirmed cargo, clears only the unapplied move and never records emptiness";
            assert pending.finishSourceSearch();
            var result=pending.workflowResult();
            assert result.get("picked").getAsInt()==1&&result.get("sourceDeferred").getAsBoolean()&&!result.get("sourceExhausted").getAsBoolean();
            var restored=new BotStashResupply(criteria);restored.restore(pending.snapshot());
            assert restored.workflowResult().get("picked").getAsInt()==1&&restored.workflowResult().get("sourceDeferred").getAsBoolean()
                : "Restart preserves the partial load and unresolved-source report without replay";
        }
    }
    private static void withdrawalBenignInventoryChanges(net.minecraft.world.item.ItemStack box,net.minecraft.world.item.ItemStack wrong) throws Exception {
        var empty=net.minecraft.world.item.ItemStack.EMPTY;
        var original=new ArrayList<>(java.util.Collections.nCopies(38,empty));original.set(0,box);
        for(int i=3;i<=30;i++)original.set(i,box.copy());
        original.set(31,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE,64));
        var tool=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE);tool.setDamageValue(3);original.set(32,tool);
        var action=json("{name:'Source',picks:[],kitTypeId:'00000000-0000-0000-0000-000000000001',count:36}");
        var opening=BotStashResupply.class.getDeclaredField("openingRequested");opening.setAccessible(true);
        for(boolean destinationPickup:List.of(false,true)){
            var pending=new BotStashResupply(action);opening.setBoolean(pending,true);assert pending.acceptMenu(10);
            pending.beginWithdrawal(28,0,2,box,100,original,true,2);
            var changed=new ArrayList<>(original);changed.set(0,empty);
            changed.set(31,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE,63));
            var damaged=tool.copy();damaged.setDamageValue(4);changed.set(32,damaged);
            if(destinationPickup)changed.set(2,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENDER_PEARL));
            assert pending.canReopenWithdrawal(changed,28,empty)&&!pending.canReopenWithdrawal(changed,29,empty)
                : "Food, tool wear and non-kit drops allow negative reconciliation, never a false kit gain";
            assert !pending.canReselectWithdrawal(changed,28,empty) : "Only a fresh menu can authorize reselection";
            pending.beginWithdrawalReopen(120,empty);opening.setBoolean(pending,true);assert pending.acceptMenu(11);
            assert !pending.observeWithdrawal(changed,28,empty,2)&&pending.canReselectWithdrawal(changed,28,empty);
            var evidence=pending.snapshot().getAsJsonObject("verification");
            assert !evidence.get("unchangedPlayerInventory").getAsBoolean()&&evidence.get("unchangedCargoInventory").getAsBoolean()
                &&evidence.getAsJsonArray("inventoryChanges").size()==(destinationPickup?3:2)
                &&evidence.getAsJsonArray("inventoryChanges").asList().stream().noneMatch(e->e.getAsJsonObject().get("cargoChanged").getAsBoolean())
                : "Logs identify benign changed slots, counts and damage instead of just an ambiguous mismatch flag";
            var lost=new ArrayList<>(changed);lost.set(3,empty);
            assert !pending.canReselectWithdrawal(lost,28,empty) : "Missing pre-existing cargo stays blocking";
            var altered=new ArrayList<>(changed);altered.set(3,wrong.copy());
            assert !pending.canReselectWithdrawal(altered,28,empty) : "Different or component-changed shulkers stay blocking";
            pending.reselectWithdrawal(changed,28,empty);
            assert !pending.snapshot().get("withdrawPending").getAsBoolean()&&pending.result().get("picked").getAsInt()==0;
        }
        var held=new BotStashResupply(action);opening.setBoolean(held,true);assert held.acceptMenu(20);
        held.beginWithdrawal(28,0,2,box,100,original,false,2);
        var returned=new ArrayList<>(original);returned.set(0,empty);returned.set(37,box.copy());
        returned.set(31,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE,62));
        returned.set(32,empty);
        held.beginWithdrawalReopen(120,box);opening.setBoolean(held,true);assert held.acceptMenu(21);
        assert !held.canReselectWithdrawal(returned,28,empty) : "A known held kit never becomes an unapplied click";
        var ambiguous=new ArrayList<>(returned);ambiguous.set(36,box.copy());
        assert !held.observeWithdrawal(ambiguous,29,empty,1) : "Two new matching kits cannot prove which one was returned";
        assert held.observeWithdrawal(returned,29,empty,2)&&held.snapshot().getAsJsonObject("verification").get("destinationSlot").getAsInt()==37
            : "An exact returned pickup can coexist with eating and non-cargo equipment changes";
        assert held.observeWithdrawal(original,28,box,1) : "Stale replies cannot revoke a confirmed pickup";
        var incidentalAction=action.deepCopy();incidentalAction.add("knownKitTypeIds",new JsonArray());incidentalAction.add("targetStash",new JsonObject());
        var incidental=new BotStashResupply(incidentalAction);incidental.beginWithdrawal(28,0,2,box,100,original,true,2);
        var extra=new ArrayList<>(original);extra.set(2,wrong.copy());extra.set(31,empty);
        assert incidental.onlyIncidentalAdditions(extra)&&incidental.canReopenWithdrawal(extra,28,empty)
            : "Incidental foreign kit handling tolerates simultaneous food consumption";
        extra.set(3,empty);assert !incidental.onlyIncidentalAdditions(extra) : "Incidental additions never mask existing cargo loss";
    }
    private static void personalSupplyCaches() throws Exception {
        var apples=net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE;
        var xp=net.minecraft.world.item.Items.EXPERIENCE_BOTTLE;
        assert BotActions.stashMendMayDefer(xp,false,64)&&BotActions.stashMendMayDefer(xp,false,156)
            : "Unavailable XP is optional while the elytra retains the takeoff reserve";
        assert !BotActions.stashMendMayDefer(xp,false,63)&&!BotActions.stashMendMayDefer(xp,true,156)
            &&!BotActions.stashMendMayDefer(apples,false,156)&&!BotActions.stashMendMayDefer(null,false,156)
            : "Last-glider reserve, pending withdrawals and food failures cannot be bypassed";
        var worn=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ELYTRA);
        worn.setDamageValue(worn.getMaxDamage()-63);
        var usable=worn.copy();usable.setDamageValue(usable.getMaxDamage()-64);
        var healthy=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ELYTRA);
        assert BotActions.stashSpareElytra(List.of(worn,usable,healthy),worn)==2 : "Use the healthiest loose spare without discarding the old elytra";
        assert BotActions.stashSpareElytra(List.of(worn,usable),worn)==1;
        assert BotActions.stashSpareElytra(List.of(worn),worn)==-1;
        var full=new java.util.ArrayList<net.minecraft.world.item.ItemStack>(java.util.Collections.nCopies(36,worn));full.add(healthy);
        assert BotActions.stashSpareElytra(full,worn)==-1 : "Equipment and extra slots are not spare inventory";
        full.set(35,healthy);assert BotActions.stashSpareElytra(full,worn)==35 : "A full inventory can swap its last loose spare, retaining worn gear";
        var enchants=new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        enchants.set(net.minecraft.data.registries.VanillaRegistries.createLookup().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
            .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.BINDING_CURSE),1);
        var bound=worn.copy();bound.set(net.minecraft.core.component.DataComponents.ENCHANTMENTS,enchants.toImmutable());
        assert BotActions.stashSpareElytra(List.of(healthy),bound)==-1 : "A bound chest slot cannot be swapped";
        var box=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        box.set(net.minecraft.core.component.DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new net.minecraft.world.item.ItemStack(apples,64))));
        assert BotPersonalSupplies.supplyBox(box,apples)&&!BotPersonalSupplies.supplyBox(box,xp);
        var mixed=box.copy();mixed.set(net.minecraft.core.component.DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new net.minecraft.world.item.ItemStack(apples,64),new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OBSIDIAN,64))));
        assert !BotPersonalSupplies.supplyBox(mixed,apples):"Never drain apples out of a PvP kit";
        assert !BotPersonalSupplies.supplyBox(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHULKER_BOX),apples):"Unknown/empty boxes are not supplies";
        String prefix=BotPersonalSupplies.key("worker-a","server-a\nminecraft:the_end",apples);
        assert !prefix.equals(BotPersonalSupplies.key("worker-b","server-a\nminecraft:the_end",apples));
        assert !prefix.equals(BotPersonalSupplies.key("worker-a","server-b\nminecraft:the_end",apples));
        assert !prefix.equals(BotPersonalSupplies.key("worker-a","server-a\nminecraft:the_nether",apples));
        assert !prefix.equals(BotPersonalSupplies.key("worker-a","server-a\nminecraft:the_end",xp));
        var directory=java.nio.file.Files.createTempDirectory("personal-supplies-test");var file=directory.resolve("caches.json");
        try {
            var cache=new BotPersonalSupplies(file);var first=new BlockPos(2,64,2);var second=new BlockPos(20,64,20);
            String firstKey=prefix+"\n"+first.asLong(),secondKey=prefix+"\n"+second.asLong();
            cache.save(firstKey,first,"minecraft:red_shulker_box",false);
            cache.save(secondKey,second,"minecraft:red_shulker_box",true);
            var restarted=new BotPersonalSupplies(file);
            assert BotPersonalSupplies.position(restarted.saved(firstKey)).equals(first)&&!restarted.saved(firstKey).get("confirmed").getAsBoolean():"Durable pre-placement intent survives client/job restart";
            assert restarted.saved(secondKey).get("confirmed").getAsBoolean():"Another site must not erase the first cache";
            assert restarted.nearest(prefix,Vec3.atCenterOf(first),p->true).equals(firstKey);
            assert restarted.nearest(prefix,Vec3.atCenterOf(first),p->p.equals(second)).equals(secondKey):"Ignore unloaded caches without deleting them";
            assert restarted.nearest(prefix,new Vec3(1000,64,1000),p->true)==null:"No long-distance stale-cache chase";
            assert restarted.saved(firstKey)!=null;
            var invalid=new JsonObject();invalid.addProperty("x",Integer.MIN_VALUE);invalid.addProperty("y",64);invalid.addProperty("z",0);
            try{BotPersonalSupplies.position(invalid);throw new AssertionError("Invalid cache position accepted");}catch(IllegalArgumentException expected){}
        } finally {java.nio.file.Files.deleteIfExists(file);java.nio.file.Files.delete(directory);}
        assert BotPersonalSupplies.confirmedWithdrawal(8,64)&&!BotPersonalSupplies.confirmedWithdrawal(8,8)&&!BotPersonalSupplies.confirmedWithdrawal(8,0);
        var obsolete=new BotPersonalSupplies(java.nio.file.Path.of("unused-personal-cache.json"));
        var resource=BotPersonalSupplies.class.getDeclaredField("resource");resource.setAccessible(true);resource.set(obsolete,apples);
        assert obsolete.canCancel(null,true)&&obsolete.canCancel(xp,true)&&!obsolete.canCancel(apples,true)
            : "Restored food or a changed maintenance need cancels only the obsolete request";
        assert !obsolete.canCancel(null,false) : "A held cursor retains the personal supply lease";
        var sent=BotPersonalSupplies.class.getDeclaredField("sent");sent.setAccessible(true);sent.setInt(obsolete,100);
        assert !obsolete.canCancel(null,true)&&!obsolete.canCancel(xp,true) : "An issued supply withdrawal must reconcile even when its need disappears";
        resource.set(obsolete,null);sent.setInt(obsolete,-1);assert !obsolete.canCancel(null,true);
        var resupply=new BotStashResupply(json("{picks:[]}"));assert resupply.canYieldInventory();
        var pending=BotStashResupply.class.getDeclaredField("withdrawSent");pending.setAccessible(true);pending.setInt(resupply,1);
        assert !resupply.canYieldInventory():"Mending cannot steal a pending withdrawal menu";
        var deposit=deposit(json("{}"));
        var finding=BotStashDeposit.class.getDeclaredField("pendingFinding");finding.setAccessible(true);finding.set(deposit,null);
        assert deposit.canYieldInventory();
        pending=BotStashDeposit.class.getDeclaredField("pendingStage");pending.setAccessible(true);pending.setInt(deposit,1);
        assert !deposit.canYieldInventory():"Mending cannot steal a pending deposit menu";
        try(var actions=BotActions.class.getResourceAsStream("BotActions.class");var supplies=BotPersonalSupplies.class.getResourceAsStream("BotPersonalSupplies.class")){
            var maintenance=ClassFile.of().parse(actions.readAllBytes());
            assert calls(maintenance,"tickStashMaintenance").containsAll(List.of("requestWorkerMaintenance","landBeforeHandoff","workerYielding","canYieldInventory","inventoryReady","refreshInventory","canCancel","canRestock","stashMendMayDefer","stashSpareElytra","toArmor"));
            assert calls(maintenance,"tickStashMaintenance").indexOf("inventoryReady")<calls(maintenance,"tickStashMaintenance").indexOf("stashSpareElytra")
                &&calls(maintenance,"tickStashMaintenance").indexOf("landBeforeHandoff")<calls(maintenance,"tickStashMaintenance").indexOf("toArmor")
                : "Loose spare swaps require synchronized inventory and grounded handoff, outside native pending-click leases";
            assert calls(maintenance,"tickStashMaintenance").indexOf("inventoryReady")<calls(maintenance,"tickStashMaintenance").indexOf("personalFoodCount")
                &&calls(maintenance,"tickStashMaintenance").indexOf("canCancel")<calls(maintenance,"tickStashMaintenance").indexOf("approaching")
                : "Synchronization gates shortage decisions; obsolete cache trips cancel before navigation";
            assert calls(maintenance,"personalFoodCount").containsAll(List.of("foodCount","getOffhandItem"));
            assert calls(maintenance,"watchProgress").contains("inventoryReady") : "Waiting for inventory is not a generic action stall";
            var compiled=ClassFile.of().parse(supplies.readAllBytes());
            assert calls(compiled,"slot").contains("confirm");
            assert !calls(compiled,"tick").contains("confirmedWithdrawal"):"A predicted local inventory gain is not a server receipt";
        }
    }
    private static void deliveryHandoffs() throws Exception {
        var empty=net.minecraft.world.item.ItemStack.EMPTY;
        var kit=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        var food=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE,64);
        var inventory=new ArrayList<net.minecraft.world.item.ItemStack>();
        for(int i=0;i<36;i++)inventory.add(i<9?food.copy():kit.copy());
        int delivered=0,bursts=0;
        while(delivered<27){
            var drops=BotActions.planDropBurst(inventory,27-delivered,s->s.is(net.minecraft.world.item.Items.SHULKER_BOX));
            assert !drops.isEmpty()&&drops.size()<=4&&drops.stream().allMatch(d->d.slot()>=9) : "Drop directly from main inventory without a hotbar staging round trip";
            assert !BotActions.confirmedDropBurst(drops,inventory,empty,27-delivered,27-delivered) : "Prediction cannot acknowledge a burst";
            int n=drops.stream().mapToInt(BotActions.Drop::amount).sum();
            for(var drop:drops)inventory.set(drop.slot(),empty);
            assert BotActions.confirmedDropBurst(drops,inventory,empty,27-delivered,27-delivered-n);
            delivered+=n;bursts++;
        }
        assert delivered==27&&bursts==7&&inventory.stream().filter(s->s.is(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE)).count()==9
            : "Seven bounded bursts deliver 27 kits, preserving every hotbar supply stack";
        assert !BotActions.confirmedDropBurst(List.of(),inventory,empty,0,0);
        var mixed=new ArrayList<net.minecraft.world.item.ItemStack>();for(int i=0;i<36;i++)mixed.add(kit.copy());
        var four=BotActions.planDropBurst(mixed,12,s->s.is(net.minecraft.world.item.Items.SHULKER_BOX));
        assert four.size()==4&&four.getFirst().slot()==0 : "Hotbar and main slots share one bounded direct-drop path";
        mixed.set(0,empty);
        assert !BotActions.confirmedDropBurst(four,mixed,empty,36,35) : "An intermediate server snapshot cannot complete the full burst";
        for(int i=1;i<4;i++)mixed.set(i,empty);
        assert BotActions.confirmedDropBurst(four,mixed,empty,36,32);
        assert BotActions.planDropBurst(mixed,2,s->s.is(net.minecraft.world.item.Items.SHULKER_BOX)).size()==2 : "Never round the requested kit count up to the burst budget";
        inventory.set(35,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE,64));
        var partial=BotActions.planDropBurst(inventory,3,s->s.is(net.minecraft.world.item.Items.STONE));
        assert partial.size()==1&&partial.getFirst().amount()==1 : "Partial stacks retain native one-item semantics";
        inventory.set(35,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE,63));
        assert BotActions.confirmedDropBurst(partial,inventory,empty,64,63);
        assert !BotActions.confirmedDropBurst(partial,inventory,food,64,63);
        assert !BotActions.confirmedDropBurst(partial,inventory,empty,64,62);
        var record=json("{amount:2,beforeTotal:2,drops:[{slot:9,amount:1,stack:{id:'minecraft:stone'}},{slot:35,amount:1,stack:{id:'minecraft:stone'}}]}");
        BotActions.validateDropBurstRecord(record);
        var large=json("{amount:256,beforeTotal:256,drops:[{slot:9,amount:64,stack:{id:'minecraft:stone',count:64}},{slot:10,amount:64,stack:{id:'minecraft:stone',count:64}},{slot:11,amount:64,stack:{id:'minecraft:stone',count:64}},{slot:12,amount:64,stack:{id:'minecraft:stone',count:64}}]}");
        BotActions.validateDropBurstRecord(large);
        for(var bad:List.of(json("{amount:2,beforeTotal:2,drops:[]}"),json("{amount:2,beforeTotal:2,drops:[{slot:9,amount:1,stack:{}},{slot:9,amount:1,stack:{}}]}"),json("{amount:2,beforeTotal:2,drops:[{slot:36,amount:2,stack:{}}]}"),json("{amount:1,beforeTotal:2,drops:[{slot:9,amount:2,stack:{}}]}"))){
            try{BotActions.validateDropBurstRecord(bad);throw new AssertionError("Malformed burst accepted");}catch(IllegalArgumentException expected){}
        }
        assert !BotActions.confirmedDropSwap(kit,empty,kit,empty,empty,14,14) : "An unchanged server inventory is not a swap confirmation";
        assert !BotActions.confirmedDropSwap(kit,food,empty,kit,empty,14,14) : "Do not lose track of displaced supplies";
        assert !BotActions.confirmedDropSwap(kit,empty,empty,kit,food,14,14) : "Held cursors cannot confirm staging";
        assert !BotActions.confirmedDropSwap(kit,empty,empty,kit,empty,14,13) : "Inventory removal is not hotbar staging";
        var anchor=BotDelivery.anchor(Vec3.ZERO,0,"minecraft:the_nether");
        assert BotDelivery.anchor(Vec3.ZERO,720_090,"minecraft:the_nether").get("yaw").getAsFloat()==90 : "Normalize accumulated rotation without changing the captured facing";
        Vec3 from=new Vec3(0,0,-1);
        assert BotDelivery.goal(from,anchor,p->true).distanceTo(new Vec3(0,0,3))<1e-6 : "Walk three blocks away in front of the captured facing";
        Vec3 fallback=BotDelivery.goal(from,anchor,p->p.z<=.00001);
        assert fallback!=null&&fallback.z<=.00001 : "An unsafe front uses a supported side instead";
        assert BotDelivery.goal(from,anchor,p->false)==null : "Unknown or unsupported ground cannot authorize movement";
        assert !BotDelivery.corridor(Vec3.ZERO,new Vec3(1,0,0),p->p.x<.4||p.x>.6) : "Check intervening footing, not only endpoints";
        assert BotDelivery.goal(new Vec3(20,0,0),anchor,p->true)==null : "Never chase a distant frozen recipient position";
        assert BotDelivery.goal(new Vec3(0,2,0),anchor,p->true)==null : "Do not walk off a ledge to reach the recipient's height";
        var saved=BotDelivery.checked(anchor);anchor.addProperty("x",4);assert BotDelivery.position(saved).equals(Vec3.ZERO) : "The original anchor is immutable";
        var invalid=saved.deepCopy();invalid.addProperty("x",Double.NaN);
        try{BotDelivery.checked(invalid);throw new AssertionError("Non-finite handoff accepted");}catch(IllegalArgumentException expected){}
        float pitch=BotDelivery.pitch(1.62,0,3);
        assert Math.abs(BotDelivery.landingDistance(1.62,pitch)-3)<.1 : "Nominal three-block arc lands near the original feet";
        assert BotDelivery.landingDistance(1.62,70)<BotDelivery.landingDistance(1.62,0) : "Looking down shortens the throw";
        var external=json("{external:true,warmupTicks:300}");var crew=json("{warmupTicks:300}");
        assert BotActions.teleportMayArrive(external,"minecraft:the_end","minecraft:the_nether",true,300,true) : "External arrival uses recipient proximity across dimensions";
        assert BotActions.teleportMayArrive(external,"minecraft:the_end","minecraft:the_end",false,0,true) : "A recipient already nearby does not need a TPA";
        assert !BotActions.teleportMayArrive(external,"minecraft:the_end","minecraft:the_end",true,299,true) : "An issued request still waits out its warmup";
        assert !BotActions.teleportMayArrive(external,"minecraft:the_end","minecraft:the_nether",true,300,false);
        assert !BotActions.teleportMayArrive(crew,"minecraft:the_end","minecraft:the_nether",true,300,true) : "Known crew/stash dimensions retain their guard";
        assert BotActions.teleportMayArrive(crew,"minecraft:the_end","minecraft:the_end",true,300,true);
        assert BotActions.teleportRejected("Running","Tpa",true,10,"Player not found!");
        assert !BotActions.teleportRejected("Running","Tpa",false,10,"Player not found!");
        assert !BotActions.teleportRejected("Running","Tpa",true,201,"Player not found!");
        assert !BotActions.teleportRejected("Complete","Tpa",true,10,"Player not found!");
        assert !BotActions.teleportRejected("Running","Tpa",true,10,"[iotacism head] iotacism » Player not found!");
        assert BotRuntime.teleportReplyCurrent("token","token","tpa iotacism","tpa Iotacism");
        assert !BotRuntime.teleportReplyCurrent("old-token","new-token","tpa iotacism","tpa iotacism");
        assert !BotRuntime.teleportReplyCurrent("token","token","tpa iotacism","msg other Gotchu"):"An unrelated DM failure must not abort the pending recipient's teleport";
        assert !BotRuntime.teleportReplyCurrent("token","token","tpa iotacism",""):"Restored or unissued commands retain uncertainty guards";
        try{BotActions.validate(json("{type:DropItems,item:'minecraft:stone',count:1,handoff:true}"));throw new AssertionError("Handoff without recipient accepted");}catch(IllegalArgumentException expected){}
        try(var stream=BotActions.class.getResourceAsStream("BotActions.class")){
            var code=ClassFile.of().parse(stream.readAllBytes());
            assert calls(code,"deliveryMove").containsAll(List.of("corridor","onGround","monocle$set")) : "The actual move, after speed modules, must be checked and capped";
            assert calls(code,"drop").indexOf("send")<calls(code,"drop").indexOf("handleContainerInput") : "Send the throw direction before its inventory packet";
            assert calls(code,"prepareDelivery").containsAll(List.of("goal","corridor","aimRecipient"));
            assert calls(code,"drop").containsAll(List.of("planDropBurst","sendDropBurst"))&&calls(code,"inventory").containsAll(List.of("confirmedDropSwap","confirmedDropBurst"))
                : "Production bursts use the tested planner and server receipt; legacy issued swaps remain observable";
            assert calls(code,"sendDropBurst").indexOf("send")<calls(code,"sendDropBurst").indexOf("handleContainerInput");
            assert calls(code,"sendDropBurst").contains("matches")&&!calls(code,"restore").contains("sendDropBurst")&&!calls(code,"settleDrop").contains("sendDropBurst")
                : "Each packet checks its source, and recovery never repeats a burst";
            assert !calls(code,"validateDropBurstRecord").contains("decodeStack") : "Restored metadata is validated without a loaded player";
            assert calls(code,"snapshot").contains("deepCopy")&&calls(code,"restore").contains("checked") : "Frozen anchors survive checkpoints with validation";
        }
    }
    private static void stashScanPipeline() throws Exception {
        JsonObject plan=BotActions.validate(json("{type:'StashScan',name:'Pipeline',homeName:'main',minX:0,maxX:63,minY:116,maxY:116,minZ:0,maxZ:0,lazyMode:false}"));
        var scan=new BotStashScan(plan);JsonObject state=scan.snapshot();
        state.addProperty("cursor",10);state.addProperty("discovered",10);state.addProperty("observed",8);
        state.addProperty("delivery",1);state.addProperty("targetX",9);state.addProperty("targetY",116);state.addProperty("targetZ",0);
        state.add("candidateCells",JsonParser.parseString("[8]"));JsonArray queued=new JsonArray();
        for(int i=0;i<8;i++){JsonObject o=json("{x:0,y:116,z:0,status:'observed',block:'minecraft:chest',reason:'',items:{'minecraft:stone':64},shulkers:[]}");o.addProperty("x",i);queued.add(o);}
        state.add("outbox",queued);scan.restore(state);
        assert scan.hasPending()&&scan.uploadWindow().size()==8&&!scan.done();
        assert scan.uploadWindow(8).isEmpty()&&scan.uploadWindow(5).size()==3 : "Already in-flight manifests are not copied and reserialized every tick";
        assert !scan.approachProgress(new Vec3(30,116,.5))&&scan.approachProgress(new Vec3(20,116,.5))
            : "Pending host receipts never prevent progress toward the next chest";
        scan.acknowledge(2);assert scan.delivery()==1 : "An out-of-order receipt cannot drop a buffered read";
        scan.acknowledge(1);assert scan.delivery()==2&&scan.target().equals(new BlockPos(9,116,0))
            : "Receipt completion must not clear the scanner's independent movement target";
        scan.acknowledge(1);assert scan.delivery()==2;
        var detached=scan.uploadWindow();detached.getFirst().getAsJsonObject("items").addProperty("minecraft:stone",1);
        assert scan.pending().getAsJsonObject("items").get("minecraft:stone").getAsInt()==64;
        var resumed=new BotStashScan(plan);resumed.restore(scan.snapshot());
        assert resumed.delivery()==2&&resumed.uploadWindow().size()==7&&resumed.target().equals(scan.target());
        assert BotStashScan.canQueue(31,0,7)&&!BotStashScan.canQueue(32,0,0)&&!BotStashScan.canQueue(1,0,8)
            &&!BotStashScan.canQueue(1,BotStashScan.OUTBOX_BYTES,0) : "Backpressure bounds observations, bytes and background writes";
        JsonObject full=state.deepCopy();full.addProperty("cursor",32);full.addProperty("discovered",32);full.addProperty("observed",32);
        for(String key:List.of("targetX","targetY","targetZ"))full.remove(key);full.add("candidateCells",new JsonArray());JsonArray all=new JsonArray();
        for(int i=0;i<32;i++){JsonObject o=queued.get(0).getAsJsonObject().deepCopy();o.addProperty("x",i);all.add(o);}full.add("outbox",all);
        var fullScan=new BotStashScan(plan);fullScan.restore(full);assert fullScan.uploadWindow().size()==8;
        for(int i=1;i<=8;i++)fullScan.acknowledge(i);
        assert fullScan.delivery()==9&&fullScan.uploadWindow(8).size()==8
            &&fullScan.uploadWindow(16).isEmpty() : "A full buffer streams only one bounded window and refills it as receipts arrive";
        JsonObject invalid=state.deepCopy();invalid.addProperty("delivery",2);
        try{new BotStashScan(plan).restore(invalid);throw new AssertionError("Inconsistent queue sequence accepted");}catch(IllegalArgumentException expected){}
        invalid=state.deepCopy();for(int i=8;i<33;i++)invalid.getAsJsonArray("outbox").add(queued.get(0).deepCopy());
        try{new BotStashScan(plan).restore(invalid);throw new AssertionError("Oversized scan outbox accepted");}catch(IllegalArgumentException expected){}

        var dir=java.nio.file.Files.createTempDirectory("monocle-pipelined-scan-");var path=dir;
        String binding="same-action";JsonObject durable=state.deepCopy();java.util.concurrent.CompletableFuture<Void> saved;
        synchronized(dev.monocle.coordinator.StashCatalog.class){
            saved=BotStashScan.saveLocal(dir,"server\nnether",plan,queued.get(0).getAsJsonObject(),null,path,binding,durable);
            assert !saved.isDone();durable.addProperty("observed",999);
        }
        saved.get(10,java.util.concurrent.TimeUnit.SECONDS);
        var diskResume=new BotStashScan(plan);diskResume.checkpoint(path,"server\nnether",binding);
        assert diskResume.independentCheckpoint()&&diskResume.uploadWindow().size()==8&&diskResume.telemetry().get("observed").getAsInt()==8
            : "The scan journal restores all buffered reads independently of an older worker-history checkpoint";
        for(int i=1;i<=8;i++)diskResume.acknowledge(i);
        assert !diskResume.hasPending()&&diskResume.target()!=null;
        var replay=new BotStashScan(plan);replay.checkpoint(path,"server\nnether",binding);
        assert replay.delivery()==1&&replay.uploadWindow().size()==8 : "A crash before the next journal safely replays acknowledged reads";
        var otherAction=new BotStashScan(plan);otherAction.checkpoint(path,"server\nnether","different-action");assert !otherAction.hasPending() : "Another action cannot inherit this scan's outbox";
        JsonObject other=plan.deepCopy();other.addProperty("maxX",62);
        try{new BotStashScan(other).checkpoint(path,"server\nnether",binding);throw new AssertionError("Changed scan bounds accepted");}catch(IllegalArgumentException expected){}

        var watermark=BotStashScan.class.getDeclaredField("durableThrough");watermark.setAccessible(true);watermark.setInt(replay,0);
        var writesField=BotStashScan.class.getDeclaredField("writes");writesField.setAccessible(true);
        var writeType=Class.forName(BotStashScan.class.getName()+"$Write");var ctor=writeType.getDeclaredConstructor(java.util.concurrent.CompletableFuture.class,int.class);ctor.setAccessible(true);
        java.util.concurrent.CompletableFuture<Void> barrier=new java.util.concurrent.CompletableFuture<>();
        @SuppressWarnings("unchecked") var writes=(java.util.Deque<Object>)writesField.get(replay);writes.add(ctor.newInstance(barrier,8));
        assert replay.uploadWindow().isEmpty() : "An in-memory read is not uploadable before its durable checkpoint finishes";
        barrier.complete(null);assert replay.uploadWindow().size()==8;
        watermark.setInt(replay,0);var failed=new java.util.concurrent.CompletableFuture<Void>();failed.completeExceptionally(new java.io.IOException("disk failed"));writes.add(ctor.newInstance(failed,8));
        try{replay.uploadWindow();throw new AssertionError("Failed scan checkpoint was uploaded");}catch(java.util.concurrent.CompletionException expected){}
        try{replay.uploadWindow();throw new AssertionError("Failed disk barrier vanished after the first exception");}catch(java.util.concurrent.CompletionException expected){}
        dev.monocle.coordinator.StashStore.close(dir);
        try(var files=java.nio.file.Files.walk(dir)){for(var p:files.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(p);}
        try(var bytes=BotStashScan.class.getResourceAsStream("BotStashScan.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code,"tick").containsAll(List.of("drainWrites","canQueue","openContainer"))&&!calls(code,"tick").contains("hasPending")
                : "The production scanner only blocks on bounded backpressure, never on a single host acknowledgement";
        }
    }
    private static List<String> calls(ClassModel type, String method) {
        return type.methods().stream().filter(m -> m.methodName().equalsString(method)).flatMap(m->m.code().orElseThrow().elementList().stream())
            .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).map(call -> call.name().stringValue()).toList();
    }
}
