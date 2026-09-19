package dev.monocle.client.systems.modules.world;

import dev.monocle.client.settings.EnumSetting;
import dev.monocle.client.utils.entity.EntityAgeTest;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Run with ./gradlew highwayMobCheck; no world, fake player, or live combat is needed. */
public final class HighwayMobTest {
    public static void main(String[] args) throws Exception {
        boolean assertionsEnabled = false;
        assert assertionsEnabled = true;
        if (!assertionsEnabled) throw new IllegalStateException("Run with assertions enabled (-ea).");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        int allowed = 0;
        for (var type : BuiltInRegistries.ENTITY_TYPE) {
            boolean piglin = type == EntityTypes.PIGLIN || type == EntityTypes.PIGLIN_BRUTE || type == EntityTypes.ZOMBIFIED_PIGLIN;
            assert HighwayBuilder.obstructionPiglin(type, false) == piglin : "Never acquire players, pets, or unrelated mob types";
            assert !HighwayBuilder.obstructionPiglin(type, true) : "Named entities remain protected, including piglins";
            if (HighwayBuilder.obstructionPiglin(type, false)) allowed++;
        }
        assert allowed == 3;
        assert !HighwayBuilder.obstructionPiglin(null, false);
        configurablePolicies();

        Vec3 anchor = new Vec3(-30.5, 64, 20.5);
        for (int range = 1; range <= 8; range++) for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) for (int y = -3; y <= 3; y++) {
            assert HighwayBuilder.withinMobArea(anchor.add(x, y, z), anchor, range) == (Math.abs(y) <= 2 && x * x + z * z <= range * range)
                : "Pursuit is a horizontal circle with a separate two-block vertical limit";
        }
        assert HighwayBuilder.withinMobArea(new Vec3(3, 2, 4), Vec3.ZERO, 5);
        assert !HighwayBuilder.withinMobArea(new Vec3(Math.nextUp(5d), 0, 0), Vec3.ZERO, 5);
        assert !HighwayBuilder.withinMobArea(new Vec3(0, Math.nextUp(2d), 0), Vec3.ZERO, 5);
        assert !HighwayBuilder.withinMobArea(new Vec3(Double.NaN, 0, 0), Vec3.ZERO, 5);
        assert !HighwayBuilder.withinMobArea(new Vec3(Double.POSITIVE_INFINITY, 0, 0), Vec3.ZERO, 5);
        boundedRoutes();
        compiledGuards();
        System.out.println("Highway mob checks passed: configurable targeting/pursuit, native attack guards, paving verification, ordered second-row preplacement and entity-wait-only opportunistic paving.");
    }

    private static void configurablePolicies() throws IOException {
        for (var weapons : HighwayBuilder.MobWeapons.values()) for (var pursuit : HighwayBuilder.MobPursuit.values()) {
            for (boolean crossbow : new boolean[] { false, true }) {
                boolean accepted = switch (weapons) {
                    case CrossbowOnly -> crossbow;
                    case NonCrossbow -> !crossbow;
                    case Any -> true;
                };
                boolean chase = switch (pursuit) {
                    case CrossbowOnly -> crossbow;
                    case AllTargets -> true;
                    case Never -> false;
                };
                assert weapons.accepts(crossbow) == accepted : "Weapon filter must not silently broaden targets";
                assert pursuit.allows(crossbow) == chase : "Pursuit is independent from eligibility to attack in reach";
            }
            for (var age : EntityAgeTest.values()) for (boolean ignoreNamed : new boolean[] { false, true }) {
                // Settings use native NBT string values; module construction itself requires a live GPU.
                CompoundTag saved = new CompoundTag();
                saved.putString("weapons", weapons.toString());
                saved.putString("pursuit", pursuit.toString());
                saved.putString("age", age.toString());
                saved.putBoolean("ignore-named", ignoreNamed);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                NbtIo.write(saved, new DataOutputStream(bytes));
                CompoundTag restored = NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
                assert restored.equals(saved);
                assert enumValue(HighwayBuilder.MobWeapons.values(), restored.getStringOr("weapons", "")) == weapons;
                assert enumValue(HighwayBuilder.MobPursuit.values(), restored.getStringOr("pursuit", "")) == pursuit;
                assert enumValue(EntityAgeTest.values(), restored.getStringOr("age", "")) == age;
                assert restored.getBooleanOr("ignore-named", !ignoreNamed) == ignoreNamed;
            }
        }
        assert !HighwayBuilder.MobWeapons.CrossbowOnly.accepts(false) && !HighwayBuilder.MobPursuit.CrossbowOnly.allows(false);
        assert HighwayBuilder.MobWeapons.Any.accepts(false) && !HighwayBuilder.MobPursuit.Never.allows(false)
            : "Allowing a sword piglin as a target must not implicitly authorize chasing it";
        assert EntityAgeTest.Both.test(null) && EntityAgeTest.Baby.negate() == EntityAgeTest.Adult && EntityAgeTest.Adult.negate() == EntityAgeTest.Baby;
        for (boolean ignoreNamed : new boolean[] { false, true }) {
            assert HighwayBuilder.obstructionPiglin(EntityTypes.PIGLIN, ignoreNamed) == !ignoreNamed;
            assert !HighwayBuilder.obstructionPiglin(EntityTypes.PLAYER, ignoreNamed) : "Turning off named protection cannot authorize players";
        }
    }

    private static <T extends Enum<?>> T enumValue(T[] values, String saved) {
        return java.util.Arrays.stream(values).filter(value -> value.toString().equalsIgnoreCase(saved)).findFirst().orElseThrow();
    }

    private static void boundedRoutes() {
        var start = new HighwayPlan.Cell(0, 64, 0);
        Vec3 anchor = new Vec3(0.5, 64, 0.5);
        Predicate<HighwayPlan.Cell> flat = cell -> cell.y() == 64
            && HighwayBuilder.withinMobArea(new Vec3(cell.x() + 0.5, cell.y(), cell.z() + 0.5), anchor, 3);
        var boundary = new HighwayPlan.Cell(3, 64, 0);
        assert HighwayPlan.route(start, boundary, flat, (a, b) -> true).size() == 4;
        var detour = HighwayPlan.route(start, boundary, flat.and(cell -> !cell.equals(new HighwayPlan.Cell(1, 64, 0))), (a, b) -> true);
        assert !detour.isEmpty() && detour.stream().allMatch(flat) : "A detour may avoid a hole but never leave the pursuit area";
        assert HighwayPlan.route(start, new HighwayPlan.Cell(4, 64, 0), flat, (a, b) -> true).isEmpty();
        assert HighwayPlan.route(start, new HighwayPlan.Cell(0, 65, 0), flat, (a, b) -> true).isEmpty() : "Combat routing does not climb work steps";
        assert HighwayPlan.route(start, new HighwayPlan.Cell(2, 64, 0), flat.and(cell -> cell.x() != 1), (a, b) -> true).isEmpty()
            : "An unreachable target cannot authorize routing around the boundary";
    }

    private static void compiledGuards() throws Exception {
        ClassModel builder = compiled(HighwayBuilder.class);
        MethodModel ready = method(builder, "mobAttackReady");
        for (String required : List.of("controlsPlayer", "mobTargetAllowed", "isAlive", "isRemoved", "getHealth",
            "onGround", "isUsingItem", "getCarried", "isEmpty", "usablePickaxe", "countItem", "withinMobArea", "walkingStandable",
            "getAttackStrengthScale", "isWithinAttackRange", "hasLineOfSight", "getTimeSinceLastTick", "isEating", "getEntitiesOfClass")) {
            assert call(ready, required) >= 0 : "Missing pre-attack safety gate: " + required;
        }
        for (String required : List.of("clearPiglins", "combatMob", "mobMinHealth", "savePickaxes", "containerMenu", "inventoryMenu", "eating", "attacking"))
            assert field(ready, required, Opcode.GETFIELD) >= 0 : "Missing pre-attack protection: " + required;
        assert field(ready, "placeRange", Opcode.GETFIELD) < 0 : "Seven-block placement reach must never become melee reach";
        MethodModel targetFilter = method(builder, "mobTargetAllowed"), pursuitFilter = method(builder, "mobMayPursue");
        for (String required : List.of("obstructionPiglin", "hasCustomName", "contains", "test", "accepts", "isHolding"))
            assert call(targetFilter, required) >= 0 : "Every acquisition and callback must use all live target filters";
        for (String required : List.of("mobIgnoreNamed", "mobTypes", "mobAge", "mobWeapons"))
            assert field(targetFilter, required, Opcode.GETFIELD) >= 0;
        assert field(targetFilter, "CROSSBOW", Opcode.GETSTATIC) >= 0 && field(pursuitFilter, "CROSSBOW", Opcode.GETSTATIC) >= 0;
        assert field(pursuitFilter, "mobPursuit", Opcode.GETFIELD) >= 0 && call(pursuitFilter, "allows") >= 0 && call(pursuitFilter, "isHolding") >= 0;
        MethodModel nativeHands = compiled(LivingEntity.class).methods().stream()
            .filter(candidate -> candidate.methodName().equalsString("isHolding") && call(candidate, "getOffhandItem") >= 0).findFirst().orElseThrow();
        assert call(nativeHands, "getMainHandItem") >= 0 : "Native crossbow detection includes either hand, not just main hand";
        for (String required : List.of("lifecycle", "jobWorld"))
            assert field(method(builder, "controlsPlayer"), required, Opcode.GETFIELD) >= 0 : "Attack callbacks must honor job ownership and lifecycle";
        MethodModel footing = method(builder, "walkingStandable");
        assert call(footing, "standable") >= 0 && call(footing, "withinMobArea") >= 0
            && call(footing, "getEntitiesOfClass") >= 0 && call(footing, "isEmpty") >= 0
            : "Combat route cells need both confirmed footing and an unoccupied living-entity path";

        MethodModel combat = method(builder, "tickMobCombat");
        assert call(combat, "isDeadOrDying") >= 0 && call(combat, "isDeadOrDying") < call(combat, "isRemoved")
            : "Death must be observed explicitly; removal or unloading alone never confirms a kill";
        assert call(combat, "restoreMobSlot") >= 0 && call(combat, "walkToWorkPosition") >= 0;
        assert call(combat, "mobTargetAllowed") >= 0 && field(combat, "mobTimeout", Opcode.GETFIELD) >= 0;
        assert field(combat, "mobRotate", Opcode.GETFIELD) >= 0 && call(combat, "rotate") >= 0 && call(combat, "run") >= 0
            : "Configured rotation and direct attacks must share the same guarded Runnable";
        int noChase = call(combat, "mobMayPursue"), route = call(combat, "route");
        assert noChase >= 0 && noChase < route : "Pursuit policy must gate route generation";
        List<CodeElement> stationary = elements(combat).subList(noChase, route);
        assert stationary.stream().anyMatch(element -> element instanceof InvokeInstruction invoke && invoke.name().equalsString("resetMobMovement"))
            && stationary.stream().anyMatch(element -> element instanceof Instruction instruction && instruction.opcode() == Opcode.IRETURN)
            : "An engaged no-chase mob leaving reach must stop movement and wait";
        assert stationary.stream().noneMatch(element -> element instanceof FieldInstruction write && write.opcode() == Opcode.PUTFIELD
            && (write.name().equalsString("combatMob") || write.name().equalsString("mobReturn")))
            : "No-chase must not clear the live target or start returning as though it died";
        MethodModel changed = method(builder, "mobSettingsChanged");
        assert call(changed, "resetMobMovement") >= 0 && field(changed, "actionEpoch", Opcode.PUTFIELD) >= 0;
        assert field(changed, "mobReturn", Opcode.GETFIELD) >= 0
            && elements(changed).subList(0, call(changed, "resetMobMovement")).stream()
                .anyMatch(element -> element instanceof Instruction instruction && instruction.opcode() == Opcode.RETURN)
            : "Changing combat settings outside an encounter must not invalidate queued highway paving";
        MethodModel resetMovement = method(builder, "resetMobMovement");
        assert call(resetMovement, "stop") >= 0 && field(resetMovement, "walkGoal", Opcode.PUTFIELD) >= 0
            && field(resetMovement, "walkPath", Opcode.PUTFIELD) >= 0 && field(resetMovement, "actionEpoch", Opcode.PUTFIELD) >= 0
            : "Restricting live policies must invalidate queued attacks and owned route movement";
        MethodModel attack = builder.methods().stream().filter(candidate -> candidate.methodName().stringValue().startsWith("lambda$tickMobCombat$")
            && call(candidate, "attack") >= 0).findFirst().orElseThrow();
        assert field(attack, "actionEpoch", Opcode.GETFIELD) >= 0
            && field(attack, "actionEpoch", Opcode.GETFIELD) < call(attack, "mobAttackReady")
            && call(attack, "mobAttackReady") < call(attack, "attack") : "Rotated callbacks revalidate cancellation and all native attack gates before sending";
        assert field(method(builder, "releaseControls"), "actionEpoch", Opcode.PUTFIELD) >= 0;
        assert builder.methods().stream().anyMatch(candidate -> candidate.methodName().equalsString("pauseJob") && call(candidate, "releaseControls") >= 0);
        assert call(method(builder, "onDeactivate"), "releaseControls") >= 0 : "Disabling cancels queued combat";

        MethodModel wait = obstructionWait(builder);
        int acquire = field(wait, "combatMob", Opcode.PUTFIELD);
        assert acquire >= 0 && call(wait, "mobTargetAllowed") < acquire && call(wait, "mobTargetAllowed") >= 0;
        assert field(wait, "clearPiglins", Opcode.GETFIELD) >= 0 && field(wait, "clearPiglins", Opcode.GETFIELD) < acquire;
        assert field(wait, "state", Opcode.GETFIELD) >= 0 && field(wait, "mobReturn", Opcode.GETFIELD) >= 0;
        assert field(wait, "mobWaitTicks", Opcode.GETFIELD) >= 0 && field(wait, "mobWaitTicks", Opcode.GETFIELD) < acquire;
        assert call(wait, "mobMayPursue") >= 0 && call(wait, "mobMayPursue") < call(wait, "isWithinAttackRange")
            && call(wait, "isWithinAttackRange") < acquire && call(wait, "hasLineOfSight") < acquire
            : "No-chase targets need native melee reach and sight before combat is acquired";
        assert call(wait, "standable") >= 0 && call(wait, "resetMobMovement") < acquire;
        MethodModel obstructionFilter = builder.methods().stream().filter(candidate -> candidate.methodName().stringValue().startsWith("lambda$waitForMob$")
            && call(candidate, "isAlive") >= 0).findFirst().orElseThrow();
        assert call(obstructionFilter, "isSpectator") >= 0 && call(obstructionFilter, "isPassengerOfSameVehicle") >= 0
            && field(obstructionFilter, "blocksBuilding", Opcode.GETFIELD) >= 0 && field(obstructionFilter, "player", Opcode.GETFIELD) >= 0
            : "Only a living physical obstruction, not self, a spectator, or a shared-vehicle passenger, may start the wait";
        assert call(obstructionFilter, "mobTargetAllowed") < 0 : "Excluded mobs must still obstruct the builder and be waited on";
        for (MethodModel candidate : builder.methods()) {
            List<Instruction> instructions = elements(candidate).stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
            for (int i = 0; i < instructions.size(); i++) {
                if (!(instructions.get(i) instanceof FieldInstruction write) || write.opcode() != Opcode.PUTFIELD || !write.name().equalsString("combatMob")) continue;
                if (candidate.methodName().equalsString("waitForMob")) continue;
                assert Set.of("onActivate", "onDeactivate", "tickMobCombat", "refreshJobWorld").contains(candidate.methodName().stringValue());
                assert instructions.subList(Math.max(0, i - 3), i).stream().anyMatch(instruction -> instruction.opcode() == Opcode.ACONST_NULL)
                    : "Only waitForMob may acquire a combat target; other paths may only clear it";
            }
        }

        settingsGuards(builder);

        Class<?> states = Class.forName(HighwayBuilder.class.getName() + "$State");
        var forwardField = states.getDeclaredField("Forward");
        forwardField.setAccessible(true);
        MethodModel forward = method(compiled(forwardField.get(null).getClass()), "tick");
        int verify = field(forward, "verifyAfterCombat", Opcode.GETFIELD), advance = call(forward, "advanceRoad");
        assert verify >= 0 && verify < call(forward, "paveSection") && call(forward, "paveSection") < advance
            && field(forward, "verifyAfterCombat", Opcode.PUTFIELD) < advance : "Verify the interrupted paving before moving forward again";
        pavingGuards(builder, forward);
    }

    private static void pavingGuards(ClassModel builder, MethodModel forward) {
        MethodModel tick = method(builder, "onTick"), wait = obstructionWait(builder);
        int reset = field(tick, "waitingForMob", Opcode.PUTFIELD), signal = field(wait, "waitingForMob", Opcode.PUTFIELD);
        assert reset >= 0 && reset < call(tick, "tick") && call(tick, "tick") < call(tick, "paveWhileWaiting")
            && field(tick, "waitingForMob", Opcode.GETFIELD) < call(tick, "paveWhileWaiting")
            : "Only the current tick's real entity wait may enable work after state/movement processing";
        assert signal > call(wait, "getEntitiesOfClass") && signal < call(wait, "stop")
            && elements(wait).subList(0, signal).stream().anyMatch(element -> element instanceof Instruction instruction && instruction.opcode() == Opcode.IRETURN)
            : "An empty obstruction query must return before signaling an entity wait";
        for (MethodModel candidate : builder.methods()) {
            if (candidate.code().isEmpty()) continue;
            List<Instruction> instructions = elements(candidate).stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
            for (int i = 1; i < instructions.size(); i++) {
                if (!(instructions.get(i) instanceof FieldInstruction write) || write.opcode() != Opcode.PUTFIELD || !write.name().equalsString("waitingForMob")) continue;
                assert instructions.get(i - 1) instanceof ConstantInstruction : "Entity wait must be explicitly set or cleared";
                Object value = ((ConstantInstruction) instructions.get(i - 1)).constantValue();
                assert Integer.valueOf(candidate.methodName().equalsString("waitForMob") || candidate.methodName().equalsString("clearBlockingBoat") ? 1 : 0).equals(value)
                    : "Only a physical obstruction may set the flag; normal ticks and lifecycle paths clear it";
            }
        }

        MethodModel available = method(builder, "paveAvailable"), waiting = method(builder, "paveWhileWaiting"), second = method(builder, "paveAhead");
        assert field(second, "blocksAheadToPave", Opcode.GETFIELD) >= 0
            && field(waiting, "blocksAheadToPave", Opcode.GETFIELD) >= 0 : "Normal and entity-wait speculative passes must honor the slider";
        for (String name : List.of("controlsPlayer", "hasChunkAt", "distToCenterSqr", "getEyePosition", "containsKey", "canBeReplaced",
            "liquidSupplySlot", "canPlaceBlock", "findHotbarSlot", "placeWorkBlock"))
            assert call(available, name) >= 0 : "Optional paving lost a live placement/supply guard: " + name;
        for (String name : List.of("state", "mobReturn", "predictionFlushRequested", "count", "placementsPerTick", "placeTimer", "placeRange", "pendingPlaces"))
            assert field(available, name, Opcode.GETFIELD) >= 0 : "Optional paving must retain ownership, prediction and rate limits: " + name;
        MethodModel pending = method(builder, "hasPendingExcavation"), background = method(builder, "isBackgroundExcavation");
        assert call(available, "hasPendingExcavation") < 0 && field(pending, "pendingBreaks", Opcode.GETFIELD) >= 0 && call(pending, "anyMatch") >= 0
            : "Keep excavation receipts for row entry, without blocking optional paving elsewhere";
        assert builder.methods().stream().anyMatch(candidate -> candidate.methodName().stringValue().startsWith("lambda$hasPendingExcavation$")
            && call(candidate, "isBackgroundExcavation") >= 0) : "Only speculative pending breaks may bypass that gate";
        assert field(background, "speculativeBreaks", Opcode.GETFIELD) >= 0 && field(background, "workOrigin", Opcode.GETFIELD) >= 0
            && call(background, "contains") >= 0 && call(background, "backgroundRow") >= 0
            : "A pending break must promote back to mandatory work when its row approaches";
        for (String name : List.of("waitingForMob", "state", "mobReturn", "predictionFlushRequested"))
            assert field(waiting, name, Opcode.GETFIELD) >= 0 : "The wait-only pass must be gated before enumerating targets";
        assert call(waiting, "controlsPlayer") >= 0 && field(available, "Forward", Opcode.GETSTATIC) >= 0
            && field(waiting, "Forward", Opcode.GETSTATIC) >= 0;
        for (MethodModel optional : List.of(available, waiting, second)) {
            for (String forbidden : List.of("reach", "walkToWorkPosition", "advanceRoad", "setState", "findBlocksToPlace", "findBlocksToPlacePrioritizeTrash",
                "setMaterials", "breakWorkBlock", "waitForMob", "stop", "rotate", "place"))
                assert call(optional, forbidden) < 0 : "Optional paving must not move, restock, excavate, retarget mobs or bypass placement ownership: " + forbidden;
            for (String unchanged : List.of("workOrigin", "completedDistance", "advancing", "advancingPaving", "pavingChecks", "blockingMob", "combatMob", "mobWaitSince"))
                assert field(optional, unchanged, Opcode.PUTFIELD) < 0 : "Preplacement must not count as required progress or change the blocker";
        }
        assert normalStopGuard(available, call(available, "hasChunkAt"), call(available, "liquidSupplySlot"))
            && normalStopGuard(available, call(available, "canPlaceBlock"), call(available, "findHotbarSlot"))
            && normalStopGuard(available, call(available, "placeWorkBlock"), elements(available).size())
            : "Outside an entity wait, unavailable left-hand cells must stop the optional pass, not skip to the right";
        for (MethodModel optional : List.of(second, waiting)) {
            List<Instruction> instructions = elements(optional).stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
            for (int i = 1; i < instructions.size(); i++) if (instructions.get(i) instanceof InvokeInstruction invoke && invoke.name().equalsString("paveAvailable")) {
                assert instructions.get(i - 1) instanceof ConstantInstruction constant
                    && Integer.valueOf(optional == waiting ? 1 : 0).equals(constant.constantValue())
                    : "Only the entity-wait wrapper enables skipping unavailable cells";
            }
        }
        assert call(second, "pavingWithinLength") >= 0 && call(second, "pavingWithinLength") < call(second, "paveAvailable")
            && call(method(builder, "pavingWithinLength"), "withinLength") >= 0 : "Preplacement must not extend a finite job past its requested length";
        int latestPreplace = -1, latestAdvance = -1;
        for (int i = 0; i < elements(forward).size(); i++) if (elements(forward).get(i) instanceof InvokeInstruction invoke) {
            if (invoke.name().equalsString("paveAhead")) latestPreplace = i;
            if (invoke.name().equalsString("advanceRoad")) {
                assert latestPreplace > latestAdvance : "Both starting and continuing movement preplace before issuing forward input";
                latestAdvance = i;
            }
        }
        assert latestAdvance >= 0;
        MethodModel underfoot = method(builder, "verifyUnderfoot"), behind = method(builder, "paveBehind");
        assert call(forward, "reachedNextSection") < call(forward, "verifyUnderfoot")
            && call(forward, "verifyUnderfoot") < call(forward, "advanceRoad")
            : "Update the occupied section, then verify its full width before another forward step";
        assert field(forward, "mobReturn", Opcode.GETFIELD) < call(forward, "advanceRoad")
            && field(forward, "mobReturn", Opcode.GETFIELD) >= 0 : "Speculative paving must still yield immediately when a blocker starts combat";
        assert call(underfoot, "getLast") >= 0 && call(underfoot, "getLast") < call(underfoot, "paveSection");
        var underfootInstructions = elements(underfoot).stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
        for (int i = 1; i < underfootInstructions.size(); i++) {
            if (underfootInstructions.get(i) instanceof InvokeInstruction invoke && invoke.name().equalsString("paveSection"))
                assert underfootInstructions.get(i - 1) instanceof ConstantInstruction constant && Integer.valueOf(1).equals(constant.constantValue())
                    : "Underfoot verification must wait on pending server predictions";
        }
        assert call(underfoot, "paveAvailable") >= 0 && call(underfoot, "paveAhead") >= 0 : "Use spare placement budget during the underfoot ACK wait";
        assert call(behind, "peekLast") >= 0 && call(behind, "paveAvailable") >= 0 && call(behind, "paveSection") < 0
            && call(behind, "reach") < 0 && call(behind, "stop") < 0 : "Trailing repairs must not gate or redirect movement";
        assert call(method(builder, "isBackgroundPaving"), "isTrailingPaving") >= 0;
        assert builder.methods().stream().anyMatch(candidate -> candidate.methodName().stringValue().startsWith("lambda$tickPredictionFlush$")
            && call(candidate, "isBackgroundPaving") >= 0) : "Background ACK waits must not invoke the foreground prediction watchdog";
        assert call(method(builder, "retirePavingSection"), "remove") >= 0 : "Retiring an optional row must retire its own pending tracking too";
    }

    /** Check the actual boolean branch, not just that the opportunistic parameter is read somewhere. */
    private static boolean normalStopGuard(MethodModel method, int start, int end) {
        List<CodeElement> code = elements(method);
        for (int i = start; i < end - 1; i++) {
            if (!(code.get(i) instanceof LoadInstruction load) || load.slot() != 2
                || !(code.get(i + 1) instanceof BranchInstruction branch)) continue;
            int destination = i + 1;
            if (branch.opcode() == Opcode.IFEQ) {
                destination = -1;
                for (int j = 0; j < code.size(); j++) if (code.get(j) instanceof LabelTarget label && label.label().equals(branch.target())) { destination = j; break; }
            } else if (branch.opcode() != Opcode.IFNE) continue;
            if (destination >= 0 && code.stream().skip(destination + 1).filter(Instruction.class::isInstance).map(Instruction.class::cast)
                .findFirst().orElseThrow().opcode() == Opcode.RETURN) return true;
        }
        return false;
    }

    private static void settingsGuards(ClassModel builder) throws IOException {
        Map<String, Object> defaults = Map.ofEntries(
            Map.entry("clearPiglins", 1), Map.entry("mobTypes", "PIGLIN"), Map.entry("mobWeapons", "CrossbowOnly"),
            Map.entry("mobPursuit", "CrossbowOnly"), Map.entry("mobAge", "Both"), Map.entry("mobIgnoreNamed", 1),
            Map.entry("mobWaitTicks", 40), Map.entry("mobTimeout", 20), Map.entry("mobRotate", 1),
            Map.entry("mobMinHealth", 10d), Map.entry("mobChaseRange", 6d));
        MethodModel constructor = method(builder, "<init>"), ui = method(builder, "buildSettings");
        for (var entry : defaults.entrySet()) {
            List<CodeElement> setting = settingElements(constructor, entry.getKey());
            Object value = null;
            boolean found = false;
            for (CodeElement element : setting) {
                if (element instanceof ConstantInstruction constant) value = constant.constantValue();
                if (element instanceof FieldInstruction field && field.opcode() == Opcode.GETSTATIC) value = field.name().stringValue();
                if (element instanceof InvokeInstruction invoke && invoke.name().equalsString("defaultValue")) {
                    assert entry.getValue().equals(value) : entry.getKey() + " has the wrong actual constructor default: " + value;
                    found = true;
                    break;
                }
            }
            assert found && field(ui, entry.getKey(), Opcode.GETFIELD) >= 0 : "Expose each combat setting in the builder UI";
            assert setting.stream().anyMatch(element -> element instanceof InvokeInstruction invoke && invoke.name().equalsString("onChanged"))
                : "Live combat setting changes must invalidate stale actions";
        }
        for (var range : Map.of("mobWaitTicks", List.of(0, 1200), "mobTimeout", List.of(1, 120)).entrySet()) {
            List<Number> numbers = new java.util.ArrayList<>();
            boolean found = false;
            for (CodeElement element : settingElements(constructor, range.getKey())) {
                if (element instanceof ConstantInstruction constant && constant.constantValue() instanceof Number number) numbers.add(number);
                if (element instanceof InvokeInstruction invoke && invoke.name().equalsString("range")) {
                    assert numbers.subList(numbers.size() - 2, numbers.size()).equals(range.getValue());
                    found = true;
                    break;
                }
            }
            assert found : "Combat timing controls need real bounded ranges, not only slider limits";
        }
        assert settingElements(constructor, "mobTypes").stream().filter(element -> element instanceof FieldInstruction field
                && field.opcode() == Opcode.GETSTATIC && field.owner().asInternalName().equals("net/minecraft/world/entity/EntityTypes"))
            .map(element -> ((FieldInstruction) element).name().stringValue()).toList().equals(List.of("PIGLIN"))
            : "Only regular piglins belong in the default species set, not brutes or zombified piglins";
        ClassModel enumSetting = compiled(EnumSetting.class);
        assert call(method(enumSetting, "save"), "putString") >= 0 && call(method(enumSetting, "load"), "parse") >= 0
            && call(method(enumSetting, "parseImpl"), "equalsIgnoreCase") >= 0
            : "Native NBT enum values above must match the actual setting serializer and parser";
    }

    private static List<CodeElement> settingElements(MethodModel constructor, String name) {
        List<CodeElement> code = elements(constructor);
        int end = field(constructor, name, Opcode.PUTFIELD), start = end - 1;
        assert end >= 0 : "Missing actual setting field " + name;
        while (start >= 0 && !(code.get(start) instanceof FieldInstruction field && field.opcode() == Opcode.PUTFIELD)) start--;
        return code.subList(start + 1, end);
    }

    private static MethodModel obstructionWait(ClassModel model) {
        MethodModel strict = model.methods().stream().filter(m -> m.methodName().equalsString("waitForMob") && m.methodType().stringValue().endsWith("AABB;)Z")).findFirst().orElseThrow();
        List<Instruction> code = elements(strict).stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
        assert code.stream().anyMatch(i -> i.opcode() == Opcode.ICONST_0) : "Placement must never ignore crew collisions";
        return model.methods().stream().filter(m -> m.methodName().equalsString("waitForMob") && m.methodType().stringValue().endsWith("AABB;Z)Z")).findFirst().orElseThrow();
    }

    private static List<CodeElement> elements(MethodModel method) { return method.code().orElseThrow().elementList(); }
    private static MethodModel method(ClassModel model, String name) {
        return model.methods().stream().filter(method -> method.methodName().equalsString(name)).findFirst().orElseThrow();
    }
    private static int call(MethodModel method, String name) {
        if (method.code().isEmpty()) return -1;
        List<CodeElement> elements = elements(method);
        for (int i = 0; i < elements.size(); i++) if (elements.get(i) instanceof InvokeInstruction call && call.name().equalsString(name)) return i;
        return -1;
    }
    private static int field(MethodModel method, String name, Opcode opcode) {
        List<CodeElement> elements = elements(method);
        for (int i = 0; i < elements.size(); i++) if (elements.get(i) instanceof FieldInstruction field && field.opcode() == opcode && field.name().equalsString(name)) return i;
        return -1;
    }
    private static ClassModel compiled(Class<?> type) throws IOException {
        try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (bytes == null) throw new AssertionError("Missing compiled " + type.getName());
            return ClassFile.of().parse(bytes.readAllBytes());
        }
    }
}
