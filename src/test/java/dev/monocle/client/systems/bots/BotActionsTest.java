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
        assert recovery.get("flyBeyond").getAsDouble() == 8 && recovery.get("searchRadius").getAsInt() == 16;
        assert travel.get("radius").getAsDouble() == 2;
        var original = json("{\"type\":\"Modules\",\"modules\":{\"kill-aura\":true,\"auto-eat\":true}}");
        assert BotActions.validate(original).get("ticks").getAsInt() == 0 && !original.has("ticks") : "Validation must not mutate the caller's snapshot";
        assert BotActions.validate(json("{\"type\":\"Wait\",\"ticks\":1728000}")).get("ticks").getAsInt() == 1728000;
        assert BotActions.validate(json("{\"type\":\"DropItems\",\"item\":\"minecraft:obsidian\",\"count\":128}")).get("count").getAsInt() == 128;
        var teleport = BotActions.validate(json("{\"type\":\"Tpa\",\"target\":\"Some_Player\",\"dimension\":\"minecraft:the_nether\"}"));
        assert teleport.get("warmupTicks").getAsInt() == 100 && teleport.get("radius").getAsDouble() == 8;
        for (String invalid : List.of(
            "{\"type\":\"Command\",\"command\":\"anything\"}",
            "{\"type\":\"RecoverSupplies\",\"searchRadius\":33}", "{\"type\":\"RecoverSupplies\",\"retryTicks\":0}",
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
        }
        System.out.println("Bot action checks passed: validation, exact drop planning/confirmation, no resend on recovery, bounded travel and native authority guards.");
    }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static List<String> calls(ClassModel type, String method) {
        return type.methods().stream().filter(m -> m.methodName().equalsString(method)).findFirst().orElseThrow().code().orElseThrow().elementList().stream()
            .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).map(call -> call.name().stringValue()).toList();
    }
}
