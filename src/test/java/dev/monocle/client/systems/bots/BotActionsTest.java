package dev.monocle.client.systems.bots;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.List;

/** Small native boundary/journal/route checks, without constructing a Minecraft window. */
public final class BotActionsTest {
    public static void main(String[] args) throws Exception {
        stashScan();
        boolean enabled = false; assert enabled = true;
        if (!enabled) throw new IllegalStateException("Run with -ea");
        for (String state : List.of("Running", "Failed", "Complete")) {
            assert BotActions.shouldTick(state, true, false, true) : "Landing/cleanup must tick even after failure or suspension";
            assert BotActions.shouldTick(state, true, true, false) : "Pending issued drops remain observable";
        }
        assert !BotActions.shouldTick("Complete", false, false, false);
        assert !BotActions.shouldTick("Running", true, false, false);
        var travel = BotActions.validate(json("{\"type\":\"Travel\",\"x\":-12000,\"y\":64,\"z\":20000}"));
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
            assert calls(code, "travel").containsAll(List.of("requestAutopilot", "safeVelocity", "route", "standable", "safeLandingBelow", "brakeFlight"));
            assert !calls(code, "travel").contains("handleContainerInput") && !calls(code, "travel").contains("breakWorkBlock") && !calls(code, "travel").contains("placeWorkBlock");
            assert calls(code, "verified").containsAll(List.of("hasChunkAt", "getBlockStatePredictionHandler", "containsKey"));
            assert calls(code, "landBeforeHandoff").containsAll(List.of("onGround", "safeLandingBelow", "requestAutopilot", "safeVelocity"))
                : "Preemption must land safely before a task profile can disable ElytraFly";
            assert !calls(code, "teleport").contains("sendCommand") && calls(code, "teleport").containsAll(List.of("dimension", "players", "distanceToSqr"));
            assert calls(code, "tick").contains("teleport") : "An already-issued teleport remains observed while suspension is requested";
            assert calls(code, "tick").contains("disconnected") && calls(code, "disconnected").containsAll(List.of("disconnected", "releaseMovement"))
                : "World/connection loss must reach the recovery child that owns travel input";
            var tick = code.methods().stream().filter(m -> m.methodName().equalsString("tick")).findFirst().orElseThrow().code().orElseThrow();
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
        assert java.util.stream.IntStream.range(0,5).map(i->BotStashScan.layerY(i,5)).boxed().toList().equals(List.of(0,4,3,2,1)) : "Lazy scan visits bottom, top, then downward";
        assert !BotStashScan.includeContainer(true,true)&&BotStashScan.includeContainer(true,false)&&BotStashScan.includeContainer(false,true) : "Lazy scans skip hoppers; full audits retain them";
        JsonObject plan=BotActions.validate(json("{\"type\":\"StashScan\",\"name\":\"Depot\",\"minX\":0,\"maxX\":0,\"minY\":116,\"maxY\":116,\"minZ\":0,\"maxZ\":0}"));
        var scan=new BotStashScan(plan);JsonObject checkpoint=scan.snapshot();checkpoint.addProperty("cursor",1);checkpoint.addProperty("discovered",1);checkpoint.addProperty("observed",1);
        checkpoint.addProperty("targetX",0);checkpoint.addProperty("targetY",116);checkpoint.addProperty("targetZ",0);
        checkpoint.add("pending",json("{\"x\":0,\"y\":116,\"z\":0,\"status\":\"observed\",\"block\":\"minecraft:chest\",\"reason\":\"\",\"items\":{\"minecraft:stone\":64},\"shulkers\":[]}"));
        scan.restore(checkpoint);assert !scan.done();scan.acknowledge(2);assert scan.pending()!=null;
        var restored=new BotStashScan(plan);restored.restore(scan.snapshot());restored.acknowledge(1);assert restored.done()&&restored.pending()==null;
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(net.minecraft.data.registries.VanillaRegistries.createLookup()).forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        var box=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        box.set(net.minecraft.core.component.DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE,64))));
        JsonObject dedicated=BotStashScan.classify(List.of(box)).get(0).getAsJsonObject();assert dedicated.get("dominant").getAsString().equals("minecraft:stone")&&!dedicated.get("mixed").getAsBoolean();
        box.set(net.minecraft.core.component.DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OBSIDIAN,64),new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_SWORD))));
        assert BotStashScan.classify(List.of(box)).get(0).getAsJsonObject().get("mixed").getAsBoolean();
        try(var bytes=BotStashScan.class.getResourceAsStream("BotStashScan.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());var invoked=code.methods().stream().filter(m->m.code().isPresent()).flatMap(m->m.code().get().elementList().stream()).filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).map(i->i.name().stringValue()).toList();
            assert !invoked.contains("handleContainerInput")&&!invoked.contains("startDestroyBlock")&&!invoked.contains("drop") : "Discovery never moves items or destroys containers";
            assert calls(code,"inventory").containsAll(List.of("containerId","finish","getItem"));
            assert calls(code,"tick").contains("useItemOn")&&!calls(code,"tick").contains("clip") : "In-reach hopper inspection can target through an adjacent chest";
        }
        try(var bytes=BotActions.class.getResourceAsStream("BotActions.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code,"scanStash").contains("moveTo")&&!calls(code,"scanStash").contains("travel") : "Solo and worker scans use Baritone, not the short walking planner";
            assert calls(code,"release").contains("stopStashNavigation")&&calls(code,"disconnected").contains("stopStashNavigation") : "Cancel, pause and disconnect release scan path ownership";
            assert calls(code,"screen").containsAll(List.of("suppressScreen","cancel")) : "Automated container menus never replace the user's screen";
        }
        try(var bytes=BotActions.class.getResourceAsStream("/dev/monocle/client/pathing/BaritoneUtils$StashNavigation.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            assert calls(code,"<init>").stream().filter("forbid"::equals).count()==3 : "Scan navigation forbids mining, placing and inventory rearrangement";
            assert calls(code,"close").containsAll(List.of("getGoal","cancelEverything","forEach","clear")) : "Owned paths stop and temporary settings are restored";
        }
    }
    private static List<String> calls(ClassModel type, String method) {
        return type.methods().stream().filter(m -> m.methodName().equalsString(method)).flatMap(m->m.code().orElseThrow().elementList().stream())
            .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).map(call -> call.name().stringValue()).toList();
    }
}
