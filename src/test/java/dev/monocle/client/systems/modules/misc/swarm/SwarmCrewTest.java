package dev.monocle.client.systems.modules.misc.swarm;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

import dev.monocle.client.systems.bots.Bots;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.net.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** ./gradlew swarmCrewCheck: real loopback transport plus production boundary/ownership policies. */
public final class SwarmCrewTest {
    public static void main(String[] args) throws Exception {
        boolean assertions = false; assert assertions = true;
        if (!assertions) throw new IllegalStateException("Enable assertions");
        assert SwarmCrew.temporaryOffDuty(true, false, true) : "Eating workers must leave active lane ownership";
        assert SwarmCrew.temporaryOffDuty(true, true, false) && !SwarmCrew.temporaryOffDuty(false, false, true);
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(DataComponentInitializers.PendingComponents::apply);
        SwarmCrew standalone = new SwarmCrew(null);
        var arbitrary = new net.minecraft.core.BlockPos(123, 64, -456);
        assert SwarmCrew.supplyReturnTarget(null, arbitrary, 60).equals(arbitrary);
        assert SwarmCrew.supplyReturnTarget(null, arbitrary, 61) == null : "Stale host destinations cannot keep a worker running";
        assert SwarmCrew.supplyReturnTarget(null, arbitrary, -1) == null : "A clock reset invalidates the old target";
        assert SwarmCrew.supplyReturnTarget(null, null, 0) == null : "No observed crew/front means stop movement, never visit the old supply origin";
        assert SwarmCrew.supplyReturnTarget(arbitrary, arbitrary.north(100), 61).equals(arbitrary) : "Live crewmates override stale host coordinates";
        var ownSupply = com.google.gson.JsonParser.parseString("[{x:123,y:64,z:-456}]").getAsJsonArray();
        assert SwarmCrew.containsSupplyPosition(ownSupply, arbitrary) && SwarmCrew.containsSupplyPosition(ownSupply, arbitrary.below());
        assert !SwarmCrew.containsSupplyPosition(ownSupply, arbitrary.east()) : "A neighboring supplier's container is not ours";
        supplyPlacementPrediction(arbitrary);
        assert !standalone.assigned() && !standalone.hold();
        assert standalone.allowsWork(arbitrary) && standalone.requestSupply(arbitrary) && standalone.clearance(arbitrary);
        assert !standalone.protectedPosition(arbitrary) : "No crew assignment means no change to standalone behavior";
        assert !standalone.activeCoworker(UUID.randomUUID()) : "Standalone walking must not ignore other players";
        assert standalone.owns(arbitrary) && standalone.readyToAdvance(arbitrary);
        assert SwarmCrew.validPickupCenter(arbitrary, arbitrary.offset(-7, 10, 7));
        assert !SwarmCrew.validPickupCenter(arbitrary, arbitrary.offset(8, 0, 0));
        assert !SwarmCrew.validPickupCenter(arbitrary, arbitrary.offset(0, 11, 0));
        assert !SwarmCrew.validPickupCenter(null, arbitrary) : "Clearance requests cannot create an unreserved supply site";
        assert SwarmCrew.crewPickupExclusion(2.24) && !SwarmCrew.crewPickupExclusion(2.25) : "Crew mates yield only the real pickup radius";
        compactSupplies();
        assert SwarmCrew.supplyRendezvousReady(arbitrary, arbitrary.offset(0, 0, 2));
        assert !SwarmCrew.supplyRendezvousReady(arbitrary, arbitrary.offset(0, 0, 3));
        assert !SwarmCrew.supplyRendezvousReady(arbitrary, arbitrary.offset(0, 0, 16)) : "Do not stop a flight sixteen blocks before its rendezvous";
        assert !SwarmCrew.supplyRendezvousReady(arbitrary, arbitrary.above());
        remoteCoordinator(arbitrary);
        reusableJobsAndAdmissions();
        verificationAuthority();
        detachedSuppliesAndDuties();
        externalBorrowing();
        offDutyMembership();
        cancellationCleanup();
        stalledHandoffs();
        emptySupplyReservation();
        sharedBreakOrder();
        workUpdates();
        cachedOwnership();
        renderedReturns();
        for (int width = 2; width <= 5; width++) for (int crew = 2; crew <= width; crew++) {
            for (int row = -5; row < 100; row++) {
                Set<Integer> anchors = new HashSet<>();
                for (int worker = 0; worker < crew; worker++) {
                    int column = SwarmCrew.anchorColumn(width, crew, worker);
                    assert anchors.add(column) : "Workers must have distinct walking lanes";
                    assert SwarmCrew.laneOwner(width, crew, column, row) == worker : "A walking lane must never change owner";
                }
                int assigned = 0;
                for (int column = 0; column < width; column++) {
                    int owner = SwarmCrew.laneOwner(width, crew, column, row);
                    assert owner >= 0 && owner < crew; assigned++;
                }
                assert assigned == width;
                assert SwarmCrew.laneOwner(width, crew, -1, row) == 0;
                assert SwarmCrew.laneOwner(width, crew, width, row) == crew - 1;
            }
        }
        assert SwarmCrew.laneOwner(5, 2, 2, 1) == SwarmCrew.laneOwner(5, 2, 2, 2) : "A contested lane has one stable owner";
        assert SwarmCrew.anchorColumn(5, 2, 0) == 1 && SwarmCrew.anchorColumn(5, 2, 1) == 3 : "Five-wide crews stand on blocks 2 and 4";
        assert SwarmCrew.anchorColumn(5, 3, 0) == 0 && SwarmCrew.anchorColumn(5, 3, 1) == 2 && SwarmCrew.anchorColumn(5, 3, 2) == 4 : "Three players stand on blocks 1, 3 and 5";
        for (int contested : new int[] {1, 3}) for (int row = 0; row < 20; row++) for (int y = 0; y < 3; y++)
            assert SwarmCrew.laneOwner(5, 3, contested, row + y) == contested / 2 : "Contested columns never checkerboard";
        var lane = new net.minecraft.world.phys.Vec3(10.5, 64, -10.5);
        assert SwarmCrew.laneReady(lane, lane, true);
        assert !SwarmCrew.laneReady(lane, lane, false);
        assert !SwarmCrew.laneReady(lane.add(.31, 0, 0), lane, true) : "Being in the correct block is not enough; center on it";
        assert SwarmCrew.nearLane(lane.add(7, 0, 0), lane);
        assert SwarmCrew.nearLane(lane.add(16, 0, 16), lane) : "An admitted worker can walk from the corner of the 32-block square";
        assert !SwarmCrew.nearLane(lane.add(25, 0, 0), lane);
        assert !SwarmCrew.nearLane(lane.add(0, 1, 0), lane) : "Automatic setup must not climb or drop levels";
        assert SwarmCrew.nearLane(lane.add(0, -.000001, 0), lane);
        assert SwarmCrew.positioningBlocker(null, false, false, false, true, false) == null;
        assert SwarmCrew.positioningScreenAllowed(null);
        assert SwarmCrew.positioningScreenAllowed(net.minecraft.client.gui.screens.ChatScreen.class);
        assert SwarmCrew.positioningScreenAllowed(net.minecraft.client.gui.screens.PauseScreen.class);
        assert SwarmCrew.positioningScreenAllowed(dev.monocle.client.gui.tabs.TabScreen.class) : "Inspecting Bots must not stop automatic positioning";
        assert SwarmCrew.positioningScreenAllowed(dev.monocle.client.gui.screens.ModuleScreen.class);
        assert !SwarmCrew.positioningScreenAllowed(net.minecraft.client.gui.screens.inventory.InventoryScreen.class);
        assert !SwarmCrew.positioningScreenAllowed(net.minecraft.client.gui.screens.inventory.ContainerScreen.class);
        assert SwarmCrew.positioningBlocker("PauseScreen", false, false, false, true, false).contains("PauseScreen");
        assert SwarmCrew.positioningBlocker(null, true, false, false, true, false).contains("job");
        assert SwarmCrew.positioningBlocker(null, false, true, false, true, false).contains("riding");
        assert SwarmCrew.positioningBlocker(null, false, false, true, true, false).contains("gliding");
        assert SwarmCrew.positioningBlocker(null, false, false, false, false, false).contains("onGround=false");
        assert SwarmCrew.positioningBlocker(null, false, false, false, true, true).contains("input");
        assert SwarmCrew.workPhase(true, true, "Manually paused").equals("blocked") : "Local builder pauses must be visible to the host's Resume action";
        assert SwarmCrew.workPhase(true, false, "Building").equals("building");
        assert SwarmCrew.workPhase(false, false, "Completed 128 blocks").equals("complete");
        assert SwarmCrew.workPhase(false, false, "Stopped").equals("stopped");
        var oldJob = new com.google.gson.JsonObject();
        UUID formerHost = UUID.randomUUID(), formerWorker = UUID.randomUUID();
        oldJob.addProperty("index", 0);
        oldJob.add("members", new com.google.gson.Gson().toJsonTree(List.of(formerHost.toString(), formerWorker.toString())));
        assert SwarmCrew.legacyHostRecord(oldJob, formerHost);
        assert SwarmCrew.legacyHostRecord(oldJob, UUID.randomUUID()) : "Legacy host server UUID may differ from the launcher UUID on offline-mode servers";
        oldJob.addProperty("index", 1);
        assert !SwarmCrew.legacyHostRecord(oldJob, formerHost);
        oldJob.addProperty("host", formerHost.toString());
        assert SwarmCrew.legacyHostRecord(oldJob, formerHost);
        assert !SwarmCrew.legacyHostRecord(oldJob, formerWorker) : "New remote-only lane zero is not the host";
        lifecycleRecords(formerHost, formerWorker);
        assert SwarmCrew.slowestRow(128, 5, 4) == 4;
        assert SwarmCrew.leadLimit(128, SwarmCrew.slowestRow(128, 5, 0, 5)) == 5 : "Workers at rows 0 and 5 may work concurrently, but row 6 waits";
        assert SwarmCrew.leadLimit(128, SwarmCrew.slowestRow(128, 5, 1, 5)) == 6 : "The forward window slides as soon as the slowest worker moves";
        assert SwarmCrew.slowestRow(128) == 0;
        assert SwarmCrew.leadLimit(16, 14) == 16 : "Do not exceed the common road length";
        int mask = SwarmCrew.resolvedMask(11, 15, row -> row != 14);
        assert SwarmCrew.verifiedRow(11, mask, 11) && SwarmCrew.verifiedRow(11, mask, 12) && SwarmCrew.verifiedRow(11, mask, 13);
        assert !SwarmCrew.verifiedRow(11, mask, 14) && SwarmCrew.verifiedRow(11, mask, 15) : "A missing distant row must not impose an all-five-rows barrier";
        assert !SwarmCrew.verifiedRow(11, mask, 10) && !SwarmCrew.verifiedRow(11, mask, 16);
        assert SwarmCrew.resolvedMask(16, 15, row -> true) == 0 : "Fastest worker waits at the five-row separation limit";
        // Authority and observation snapshots are exercised without Minecraft in coordinator-core.
        for (int[] dir : new int[][] {{1,0},{-1,0},{0,1},{0,-1}}) {
            for (int step = -2; step <= 34; step++) {
                int x = 100 + step * dir[0], z = -100 + step * dir[1];
                boolean first = SwarmCrew.within(x, z, 100, -100, dir[0], dir[1], 16);
                boolean second = SwarmCrew.within(x, z, 100 + 16 * dir[0], -100 + 16 * dir[1], dir[0], dir[1], 16);
                assert !(first && second) : "Sections must not overlap";
                assert (first || second) == (step >= 1 && step <= 32) : "No seam gaps, no speculative overshoot";
            }
            BlockPos rear = new BlockPos(100 + 10 * dir[0], 116, -100 + 10 * dir[1]);
            BlockPos front = rear.offset(dir[0] * 2, 0, dir[1] * 2);
            assert SwarmCrew.fartherBack(dir[0], dir[1], rear, front);
            assert !SwarmCrew.fartherBack(dir[0], dir[1], front, rear);
        }
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        Set<UUID> members = Set.of(a, b, c);
        assert !SwarmCrew.barrierReady(members, a, Set.of(b));
        assert SwarmCrew.barrierReady(members, a, Set.of(b, c));
        assert !SwarmCrew.barrierReady(members, UUID.randomUUID(), members);
        assert !SwarmCrew.barrierReady(members, b, Set.of(b, c)) : "New owner requires a fresh barrier";
        SimpleContainer inventory = new SimpleContainer(41);
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        assert SwarmCrew.returnSlot(inventory, box) == -1;
        inventory.setItem(20, box.copy()); assert SwarmCrew.returnSlot(inventory, box) == 20;
        inventory.setItem(0, box.copy()); assert SwarmCrew.returnSlot(inventory, box) == -1 : "Never pick between identical kits";
        inventory.setItem(0, ItemStack.EMPTY); inventory.getItem(20).setCount(2);
        assert SwarmCrew.returnSlot(inventory, box) == -1 : "Do not throw a stack of server-stacked boxes";
        String key = "loopback-test-key-not-a-real-secret";
        assert SwarmConnection.authentic(key, "session:host:0:message", SwarmConnection.mac(key, "session:host:0:message"));
        assert !SwarmConnection.authentic(key, "session:host:1:message", SwarmConnection.mac(key, "session:host:0:message"));
        assert !SwarmConnection.authentic(key, "other-session:host:0:message", SwarmConnection.mac(key, "session:host:0:message"));
        transport(key, key, true);
        transport(key, "a-different-test-key-at-least-24", false);
        int[] delays = {1, 1, 2, 4, 8, 10, 10};
        for (int i = 0; i < delays.length; i++) assert Bots.retrySeconds(i) == delays[i];
        assert Bots.retrySeconds(100) == 10;
        workerConnection(key);
        System.out.println("Swarm crew checks passed: authenticated two-way loopback, ordering, disconnects, bounded frames, section seams, reservation barriers and ambiguous shulker refusal.");
    }
    private static void compactSupplies() {
        BlockPos center = new BlockPos(-120, 116, 500);
        assert !SwarmCrew.outsidePickupArea(null, center);
        assert !SwarmCrew.outsidePickupArea(new JsonObject(), center);
        for (int[] direction : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int dx = direction[0], dz = direction[1];
            var area = SwarmCrew.supplyWorkArea(center, dx, dz, true);
            BlockPos container = center.offset(-dx, 0, -dz), front = center.offset(dx * 3, 0, dz * 3);
            assert !area.contains(net.minecraft.world.phys.Vec3.atCenterOf(front));
            assert !area.contains(net.minecraft.world.phys.Vec3.atCenterOf(front.offset(dx, 0, dz)))
                : "Three-row staging cannot protect the active crew's underfoot or next-row work";
            assert !area.inflate(.5, 0, .5).intersects(new net.minecraft.world.phys.AABB(front).deflate(.15, 0, .15))
                : "The compact travel fence must not intersect a worker at the active front";
            for (int back : new int[] {1, 3, 5, 7}) for (int side : new int[] {0, 1}) {
                BlockPos supply = center.offset(-dx * back + dz * side, 0, -dz * back - dx * side);
                assert area.contains(net.minecraft.world.phys.Vec3.atCenterOf(supply))
                    : "Single/paired containers and all backward relocation candidates stay protected";
            }
            var report = new JsonObject(); report.addProperty("x", front.getX()); report.addProperty("y", front.getY()); report.addProperty("z", front.getZ());
            assert SwarmCrew.outsidePickupArea(report, container) : "The working crew need not join the supply barrier";
            BlockPos queued = center.offset(-3 * dx, 0, -3 * dz);
            report.addProperty("x", queued.getX()); report.addProperty("z", queued.getZ());
            assert !SwarmCrew.outsidePickupArea(report, container) : "An adjacent queued supplier must yield before the grant";
            BlockPos yielded = center.offset(-5 * dx, 0, -5 * dz);
            report.addProperty("x", yielded.getX()); report.addProperty("z", yielded.getZ());
            assert SwarmCrew.outsidePickupArea(report, container) : "A short yield clears recovery without a distant supply trip";
            assert SwarmCrew.supplyWorkArea(center, dx, dz, false).contains(net.minecraft.world.phys.Vec3.atCenterOf(front))
                : "Existing supply records keep their original full protection area";
        }
    }

    private static void renderedReturns() throws Exception {
        BlockPos origin = new BlockPos(-120, 116, 500);
        assert SwarmCrew.serviceLandingReady(origin, net.minecraft.world.phys.Vec3.atBottomCenterOf(origin));
        assert !SwarmCrew.serviceLandingReady(null, net.minecraft.world.phys.Vec3.atBottomCenterOf(origin));
        assert !SwarmCrew.serviceLandingReady(origin, net.minecraft.world.phys.Vec3.atBottomCenterOf(origin).add(0, 0, -2))
            : "Do not add a trailing returner that would immediately stop the Break Order window";
        for (int[] direction : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int dx = direction[0], dz = direction[1];
            for (int row : new int[] {0, 1, 16, 64, 128, 256, 510, 511, 512}) {
                var model = net.minecraft.world.phys.Vec3.atBottomCenterOf(origin.offset(dx * row, 0, dz * row));
                BlockPos expected = origin.offset(dx * row, 0, dz * row);
                for (int side : new int[] {-2, -1, 0, 1, 2}) {
                    BlockPos lane = expected.offset(dz * side, 0, -dx * side);
                    BlockPos approach = SwarmCrew.returnApproach(origin, dx, dz, 512, lane, (from, to) -> {
                        assert from.equals(lane) && to.equals(lane.offset(dx * Math.min(2, 512 - row), 0, dz * Math.min(2, 512 - row)));
                        return true;
                    });
                    assert approach.equals(lane.offset(dx * Math.min(2, 512 - row), 0, dz * Math.min(2, 512 - row)))
                        : "Overshoot two blocks in the assigned lane, capped at the job end";
                    assert SwarmCrew.returnApproach(origin, dx, dz, 512, lane, (from, to) -> to.equals(lane.offset(dx, 0, dz)))
                        .equals(row < 512 ? lane.offset(dx, 0, dz) : lane) : "Keep the one-block fallback when only the second extra block is blocked";
                    assert SwarmCrew.returnApproach(origin, dx, dz, 512, lane, (from, to) -> false).equals(lane)
                        : "An unsafe/unconfirmed extension keeps the current rendezvous; it must not strand the returner";
                    if (row == 512) SwarmCrew.returnApproach(origin, dx, dz, 512, lane, (from, to) -> {
                        throw new AssertionError("Never inspect or target a row beyond the job end");
                    });
                }
                assert SwarmCrew.renderedReturnTarget(origin, dx, dz, 5, 512, model).equals(expected)
                    : "Any tracked on-road crew model is eligible, not only a 16-block radius";
                assert SwarmCrew.renderedReturnTarget(origin, dx, dz, 5, 512, model.add(dz * 2, 1, -dx * 2)).equals(expected)
                    : "Use live longitudinal progress while retaining the highway centerline and floor";
                assert SwarmCrew.renderedReturnTarget(origin, dx, dz, 5, 512, model.add(dz * 10, 0, -dx * 10)) == null;
                assert SwarmCrew.renderedReturnTarget(origin, dx, dz, 5, 512, model.add(0, 10, 0)) == null;
                if (row > 1 && row < 511)
                    assert SwarmCrew.renderedReturnTarget(origin, dx, dz, 5, 512, model.add(dx, 0, dz)).equals(expected.offset(dx, 0, dz))
                        : "Movement follows this tick's model without waiting for a host update or landing";
            }
        }
        UUID runner = UUID.randomUUID(), anchor = UUID.randomUUID(), stranger = UUID.randomUUID();
        JsonObject assignment = new JsonObject(), report = new JsonObject();
        assignment.add("members", new com.google.gson.Gson().toJsonTree(List.of(runner, anchor)));
        assignment.add("activeMembers", new com.google.gson.Gson().toJsonTree(List.of(anchor)));
        assert !SwarmCrew.validRenderedReturn(assignment, runner, report) : "Tab-list membership alone proves nothing";
        report.addProperty("renderedCrew", anchor.toString());
        assert SwarmCrew.validRenderedReturn(assignment, runner, report);
        assert !SwarmCrew.serviceReturnReady(assignment, runner, report, origin) : "Visibility alone must not stop the crew for a distant or airborne worker";
        report.addProperty("serviceReady", true);
        report.addProperty("x", origin.getX()); report.addProperty("y", origin.getY()); report.addProperty("z", origin.getZ() + 100);
        assert SwarmCrew.serviceReturnReady(assignment, runner, report, origin)
            : "A landed returner beside a live crewmate must not be rejected against a hundred-block-old host waypoint";
        report.addProperty("renderedCrew", runner.toString()); assert !SwarmCrew.validRenderedReturn(assignment, runner, report);
        report.addProperty("renderedCrew", stranger.toString()); assert !SwarmCrew.validRenderedReturn(assignment, runner, report);
        report.addProperty("renderedCrew", "bad uuid"); assert !SwarmCrew.validRenderedReturn(assignment, runner, report);
        report.add("renderedCrew", new JsonObject()); assert !SwarmCrew.validRenderedReturn(assignment, runner, report);
        report.addProperty("renderedCrew", anchor.toString());
        JsonObject away = new JsonObject(); away.addProperty(anchor.toString(), true); assignment.add("awayMembers", away);
        assert !SwarmCrew.validRenderedReturn(assignment, runner, report) : "Off-duty bots must not attract returners";
        away.remove(anchor.toString()); assignment.add("activeMembers", new com.google.gson.JsonArray());
        assert !SwarmCrew.validRenderedReturn(assignment, runner, report) : "A now-detached supplier is no longer a return anchor";
        assert !SwarmCrew.serviceReturnReady(assignment, runner, report, origin);
        report.addProperty("z", origin.getZ());
        assert SwarmCrew.serviceReturnReady(assignment, runner, report, origin) : "When everyone is supplying, the first return can restart from the verified checkpoint";
        try (var bytes = SwarmCrew.class.getResourceAsStream("SwarmCrew.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var visible = compiled.methods().stream().filter(m -> m.methodName().equalsString("renderedCrew")).findFirst().orElseThrow();
            var calls = visible.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
            assert calls.contains("getPlayerByUUID") && calls.contains("position") && !calls.contains("currentReport")
                : "Live flight destinations must come from local player entities, never host position telemetry";
            var protection = compiled.methods().stream().filter(m -> m.methodName().equalsString("protectedPosition")).findFirst().orElseThrow();
            var protectionCalls = protection.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
            assert protectionCalls.indexOf("containsSupplyPosition") >= 0
                && protectionCalls.indexOf("containsSupplyPosition") < protectionCalls.indexOf("observedSupplyAt")
                : "Own recovery must take precedence over the overlapping neighbor-area fallback";
            for (String method : List.of("applyServiceChange", "coordinateServiceReturn", "departSupply")) {
                var methods = compiled.methods().stream().filter(m -> m.methodName().equalsString(method)).toList();
                for (var m : methods) {
                    var invoked = m.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                        .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
                    assert java.util.Collections.disjoint(invoked, List.of("beginSupplyHandoff", "installAssignment", "endCrew", "beginCrew", "resetWindow", "crewQuiesce"))
                        : "Supply returns must not restart or quiesce the working crew: " + method;
                }
            }
        }
    }

    private static void cachedOwnership() throws Exception {
        var ids = java.util.stream.IntStream.range(0, 5).mapToObj(i -> UUID.randomUUID()).toList();
        JsonObject record = new JsonObject(), layout = new JsonObject(); record.add("layout", layout);
        for (int width = 1; width <= 5; width++) for (int count = 0; count <= width; count++) {
            layout.addProperty("width", width);
            record.add("members", new com.google.gson.Gson().toJsonTree(ids.subList(0, count)));
            for (int combination = 0; combination < (int) Math.pow(3, count); combination++) {
                JsonObject duties = new JsonObject(); record.add("duties", duties);
                for (int i = 0, code = combination; i < count; i++, code /= 3)
                    duties.addProperty(ids.get(i).toString(), List.of("Build", "Excavate", "Pave").get(code % 3));
                var cached = new SwarmCrew.WorkOwners(record);
                assert cached.matches(record.deepCopy());
                for (boolean excavation : new boolean[] {false, true}) for (int row = -5; row < 7; row++) for (int column = -1; column <= width; column++) {
                    int expected = SwarmCrew.capableOwner(width, count, column, row,
                        i -> SwarmCrew.dutyAllows(duties.get(ids.get(i).toString()).getAsString(), excavation));
                    assert cached.owner(excavation, column, row) == expected : "Cached ownership must preserve every duty/parity/railing decision";
                }
            }
        }
        SwarmCrew crew = new SwarmCrew(null, "Ownership checks"); set(crew, "assignment", record);
        var original = crew.workOwners();
        assert original == crew.workOwners() : "Stable assignments reuse the table";
        record.add("activeMembers", new com.google.gson.Gson().toJsonTree(ids.subList(0, 2)));
        var detached = crew.workOwners();
        assert detached != original && detached.members.equals(ids.subList(0, 2));
        record.getAsJsonObject("duties").addProperty(ids.getFirst().toString(), "Build");
        assert crew.workOwners() != detached : "Even in-place duty changes invalidate immediately";
        record.remove("activeMembers");
        assert crew.workOwners().members.equals(ids) : "Rejoining restores the full roster";
        record.add("activeMembers", new com.google.gson.Gson().toJsonTree(ids.subList(0, 2)));
        var full = crew.workOwners(); layout.addProperty("width", 3);
        assert crew.workOwners() != full;
        record.remove("duties"); JsonObject workflow = new JsonObject(); workflow.addProperty("duty", "Pave"); record.add("workflow", workflow);
        assert crew.workOwners().owner(true, 0, 0) == -1;
        workflow.addProperty("duty", "Excavate");
        assert crew.workOwners().owner(false, 0, 0) == -1 : "Fallback workflow duties are also part of the key";
    }

    private static void workUpdates() {
        for (int tick = 0; tick < 100; tick++) {
            assert SwarmCrew.workUpdateDue(tick, true, true) : "Changed readiness is published on the next client tick";
            assert SwarmCrew.workUpdateDue(tick, true, false) == (tick % 2 == 0) : "Keep periodic recovery heartbeats";
            assert SwarmCrew.workUpdateDue(tick, false, true) == (tick % 10 == 0) : "Idle discovery must not become a per-tick broadcast";
        }
        var previous = new com.google.gson.JsonObject();
        previous.addProperty("job", "job"); previous.addProperty("generation", 2); previous.addProperty("currentRow", 4);
        previous.addProperty("verifiedBase", 5); previous.addProperty("verifiedMask", 0);
        previous.addProperty("phase", "building");
        assert SwarmCrew.workReportChanged(null, previous);
        assert !SwarmCrew.workReportChanged(previous, previous.deepCopy());
        var cosmetic = previous.deepCopy(); cosmetic.addProperty("status", "Paving ~10m"); cosmetic.addProperty("supplyEta", 600);
        assert !SwarmCrew.workReportChanged(previous, cosmetic) : "Forecast text does not trigger an extra verification pass";
        for (String key : List.of("generation", "currentRow", "verifiedBase", "verifiedMask", "currentResolved", "regroupReady", "begun")) {
            var changed = previous.deepCopy(); changed.addProperty(key, 7);
            assert SwarmCrew.workReportChanged(previous, changed) : key;
        }
        var changed = previous.deepCopy(); changed.addProperty("phase", "blocked");
        assert SwarmCrew.workReportChanged(previous, changed);
        changed = previous.deepCopy(); changed.addProperty("job", "replacement");
        assert SwarmCrew.workReportChanged(previous, changed);
        changed = previous.deepCopy(); changed.add("mining", new com.google.gson.Gson().toJsonTree(List.of(42L)));
        assert SwarmCrew.workReportChanged(previous, changed);
        assert SwarmCrew.workReportChanged(changed, previous) : "Released mining claims must propagate promptly too";
        changed = previous.deepCopy(); changed.addProperty("verifiedMask", 31);
        assert SwarmCrew.workReportChanged(previous, changed) && SwarmCrew.workReportChanged(changed, previous)
            : "Both confirmation and server rollback trigger fresh verification";
    }

    private static void reusableJobsAndAdmissions() throws Exception {
        var front = new net.minecraft.core.BlockPos(0, 64, 0);
        assert SwarmCrew.joinNearby(front, front.offset(16, 0, -16));
        assert !SwarmCrew.joinNearby(front, front.offset(17, 0, 0));
        assert !SwarmCrew.joinNearby(front, front.above()) : "Admission cannot promise unsupported vertical routing";
        var current = new com.google.gson.JsonObject();
        current.addProperty("job", UUID.randomUUID().toString()); current.addProperty("catalogId", UUID.randomUUID().toString());
        current.addProperty("generation", 2); current.addProperty("startRow", 31);
        current.addProperty("x", 10); current.addProperty("y", 64); current.addProperty("z", -10); current.addProperty("length", 100);
        current.addProperty("name", "Persistent road"); current.addProperty("scope", "server\nnether");
        var layout = new com.google.gson.JsonObject(); layout.addProperty("dx", 0); layout.addProperty("dz", -1); current.add("layout", layout);
        var next = current.deepCopy(); next.addProperty("generation", 3);
        assert SwarmCrew.matchesGeneration(current.get("job").getAsString(), 2, current);
        assert !SwarmCrew.matchesGeneration(current.get("job").getAsString(), 2, next);
        var legacy = current.deepCopy(); legacy.remove("generation");
        assert !SwarmCrew.matchesGeneration(current.get("job").getAsString(), 2, legacy)
            : "A packet without a generation cannot cross a reconfiguration boundary";
        var initial = current.deepCopy(); initial.remove("generation");
        assert SwarmCrew.matchesGeneration(current.get("job").getAsString(), 0, initial)
            : "Initial-generation legacy packets remain compatible";
        assert SwarmCrew.reconfigurationReady(current, 2, next, true, false);
        assert !SwarmCrew.reconfigurationReady(current, 2, next, false, false) : "Do not replace lanes while old placement packets are unsettled";
        assert !SwarmCrew.reconfigurationReady(current, 2, next, true, true) : "Never discard a live supply recovery";
        next.addProperty("catalogId", UUID.randomUUID().toString());
        assert !SwarmCrew.reconfigurationReady(current, 2, next, true, false) : "Rebalancing cannot change the logical job";

        SwarmCrew remote = new SwarmCrew(null, "Remote road");
        set(remote, "assignment", current); set(remote, "job", current.get("job").getAsString()); set(remote, "generation", 2);
        set(remote, "phase", "building"); set(remote, "checkpointRow", 39);
        var snapshot = remote.jobSnapshot();
        assert snapshot.get("id").equals(current.get("catalogId")) && snapshot.get("execution").equals(current.get("job"));
        assert snapshot.get("progress").getAsInt() == 39 : "Resume at the slowest physically reached and server-resolved checkpoint";
        assert remote.startPosition().equals(new net.minecraft.core.BlockPos(10, 64, -41)) : "Original origin and current restart point are distinct";
        snapshot.getAsJsonObject("layout").addProperty("dx", 1);
        assert current.getAsJsonObject("layout").get("dx").getAsInt() == 0 : "Catalog snapshots must not alias live settings";
        var stale = new com.google.gson.JsonObject(); stale.addProperty("job", current.get("job").getAsString());
        stale.addProperty("generation", 1); stale.addProperty("type", "window"); stale.addProperty("base", 90); stale.addProperty("mask", 31);
        stale.addProperty("limit", 94); stale.addProperty("checkpoint", 89);
        var apply = crewMethod("apply", com.google.gson.JsonObject.class); apply.setAccessible(true); apply.invoke(remote, stale);
        assert remote.jobSnapshot().get("progress").getAsInt() == 39 : "A previous generation cannot advance the rebalanced job";
        stale.addProperty("generation", 2); apply.invoke(remote, stale);
        assert remote.jobSnapshot().get("progress").getAsInt() == 89;
        stale.addProperty("mask", 0); apply.invoke(remote, stale);
        var verified = crewField("verifiedMask"); verified.setAccessible(true);
        assert verified.getInt(remote) == 0 : "Server-restored blocks revoke a previous permit; permits are not monotonically accumulated";
        stale.addProperty("checkpoint", 87); apply.invoke(remote, stale);
        assert remote.jobSnapshot().get("progress").getAsInt() == 87 : "Physical rollback must rewind a released job's checkpoint";
        set(remote, "phase", "complete");
        assert remote.jobSnapshot().get("progress").getAsInt() == 100;
        current.remove("catalogId");
        assert remote.jobSnapshot().get("id").equals(current.get("job")) : "Legacy jobs retain a stable identity during catalog migration";
    }
    private static java.lang.reflect.Field crewField(String name) throws NoSuchFieldException {
        for(Class<?> c=SwarmCrew.class;c!=null;c=c.getSuperclass()) try { return c.getDeclaredField(name); } catch(NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(name);
    }
    private static java.lang.reflect.Method crewMethod(String name,Class<?>... args) throws NoSuchMethodException {
        for(Class<?> c=SwarmCrew.class;c!=null;c=c.getSuperclass()) try { return c.getDeclaredMethod(name,args); } catch(NoSuchMethodException ignored) { }
        throw new NoSuchMethodException(name);
    }
    private static void set(SwarmCrew crew, String name, Object value) throws Exception {
        var field = crewField(name); field.setAccessible(true); field.set(crew, value);
    }

    private static void emptySupplyReservation() throws Exception {
        SwarmCrew controller = new SwarmCrew(null, "Empty reservation regression");
        var record = new com.google.gson.JsonObject();
        set(controller, "assignment", record);
        var hold = crewMethod("sharedSupplyHold"); hold.setAccessible(true);
        assert !(boolean) hold.invoke(controller) : "A fresh job without a reservation must not query an immutable UUID set with null";
        UUID supplier = UUID.randomUUID();
        record.addProperty("detachedMember", supplier.toString());
        assert !(boolean) hold.invoke(controller) : "Legacy single-runner records also permit no current reservation";
        record.remove("detachedMember"); record.add("suppliers", new com.google.gson.JsonObject());
        assert !(boolean) hold.invoke(controller);
        set(controller, "supplyOwner", supplier);
        assert (boolean) hold.invoke(controller);
        record.getAsJsonObject("suppliers").add(supplier.toString(), new com.google.gson.JsonObject());
        assert !(boolean) hold.invoke(controller) : "A real detached owner never stops active road work";
        var stopped = crewField("stopped"); stopped.setAccessible(true);
        var manual = crewField("pausedBeforeDisconnect"); manual.setAccessible(true);
        var phase = crewField("phase"); phase.setAccessible(true);
        set(controller, "phase", "building");
        controller.disconnected();
        assert stopped.getBoolean(controller) && !manual.getBoolean(controller) : "Real connection loss remains eligible for recovery";
        Object waiting = phase.get(controller);
        for (int tick = 0; tick < 1200; tick++) controller.disconnected();
        assert phase.get(controller).equals(waiting) && !manual.getBoolean(controller)
            : "Repeated world/transport loss is one quiet transition, not a new manual pause every tick";
        set(controller, "stopped", false); set(controller, "phase", "building");
        controller.controllerFailed();
        assert stopped.getBoolean(controller) && manual.getBoolean(controller)
            : "A controller exception must block automatic reconnect/resume even with healthy sockets";
        assert phase.get(controller).equals("controller error / inspect job");
        controller.disconnected();
        assert manual.getBoolean(controller) : "A later transport event must not erase the error's manual-resume requirement";
        assert phase.get(controller).equals("controller error / inspect job") : "Repeated disconnect cannot overwrite a real fault";
        set(controller, "stopped", false); set(controller, "phase", "paused");
        controller.disconnected();
        assert manual.getBoolean(controller) : "A host pause before world loss still requires host Resume";
    }
    @SuppressWarnings("unchecked")
    private static void verificationAuthority() throws Exception {
        SwarmCrew remote = new SwarmCrew(null, "Workers only");
        String execution = UUID.randomUUID().toString(); UUID worker = UUID.randomUUID();
        var assignment = new com.google.gson.JsonObject();
        assignment.addProperty("job", execution); assignment.addProperty("hostMember", "");
        assignment.addProperty("scope", "test\nminecraft:the_nether");
        assignment.add("layout", new com.google.gson.JsonObject());
        assignment.addProperty("length", 100); assignment.addProperty("startRow", 0);
        assignment.add("members", new com.google.gson.Gson().toJsonTree(List.of(worker.toString())));
        set(remote, "assignment", assignment); set(remote, "job", execution); set(remote, "generation", 2);
        var report = new com.google.gson.JsonObject(); report.addProperty("job", execution); report.addProperty("generation", 2);
        report.addProperty("scope", "test\nminecraft:the_nether");
        report.addProperty("currentRow", 10); report.addProperty("currentResolved", true);
        report.addProperty("verifiedBase", 11); report.addProperty("verifiedMask", 31);
        var reports = crewField("reports"); reports.setAccessible(true);
        ((Map<UUID, com.google.gson.JsonObject>) reports.get(remote)).put(worker, report);
        var participants = crewField("participants"); participants.setAccessible(true);
        ((Map<UUID, SwarmConnection>) participants.get(remote)).put(worker, null);
        var coordinate = crewMethod("coordinateWindow"); coordinate.setAccessible(true);
        assert (boolean) coordinate.invoke(remote) : "Workers-only window consumes worker confirmations without accessing Minecraft's host world";
        var checkpoint = crewField("checkpointRow"); checkpoint.setAccessible(true);
        var permit = crewField("verifiedMask"); permit.setAccessible(true);
        assert checkpoint.getInt(remote) == 10 && permit.getInt(remote) == 31;
        report.addProperty("scope", "test\nminecraft:overworld");
        assert !(boolean) coordinate.invoke(remote) : "A worker in another dimension cannot verify this job";
        report.addProperty("scope", "test\nminecraft:the_nether");
        report.addProperty("generation", 1);
        assert !(boolean) coordinate.invoke(remote) : "Previous assignment observations cannot grant permits";
        report.addProperty("generation", 2);
        JsonObject staleSuppliers = new JsonObject(); staleSuppliers.add(worker.toString(), new JsonObject());
        report.add("suppliers", staleSuppliers);
        set(remote, "verifiedMask", 13);
        assert (boolean) coordinate.invoke(remote) && permit.getInt(remote) == 13
            : "A pending lane receipt keeps the old permit, never sends unknown mining owners";
        staleSuppliers.remove(worker.toString());
        assert (boolean) coordinate.invoke(remote) && permit.getInt(remote) == 31
            : "Fresh roster acknowledgment resumes normal verified permits";
        report.remove("suppliers");
        JsonObject revisions = new JsonObject(); revisions.addProperty(worker.toString(), 2);
        assignment.add("serviceRevisions", revisions); report.add("serviceRevisions", new JsonObject());
        set(remote, "verifiedMask", 13);
        assert (boolean) coordinate.invoke(remote) && permit.getInt(remote) == 13 : "Matching membership alone does not acknowledge a newer supply cycle";
        report.add("serviceRevisions", revisions.deepCopy());
        assert (boolean) coordinate.invoke(remote) && permit.getInt(remote) == 31 : "Acknowledged supply revision restores verified window updates";
        assignment.remove("serviceRevisions"); report.remove("serviceRevisions");
        report.addProperty("currentRow", 8); report.addProperty("verifiedBase", 9);
        assert (boolean) coordinate.invoke(remote) && checkpoint.getInt(remote) == 8 : "Actual rollback rewinds the reusable checkpoint";
        report.addProperty("currentResolved", false);
        assert (boolean) coordinate.invoke(remote) && checkpoint.getInt(remote) == 7 : "A restored block under the slowest worker forces replay of that row";
        UUID host = UUID.randomUUID(); assignment.addProperty("hostMember", host.toString());
        assignment.add("members", new com.google.gson.Gson().toJsonTree(List.of(host.toString(), worker.toString())));
        ((Map<UUID, SwarmConnection>) participants.get(remote)).put(host, null);
        ((Map<UUID, com.google.gson.JsonObject>) reports.get(remote)).put(host, report.deepCopy());
        set(remote, "verifiedMask", 0); set(remote, "checkpointRow", 0);
        assert !(boolean) coordinate.invoke(remote) && permit.getInt(remote) == 0 && checkpoint.getInt(remote) == 0
            : "A missing participating host never silently falls back to worker reports";
        assignment.add("activeMembers", new com.google.gson.Gson().toJsonTree(List.of(worker.toString())));
        assignment.addProperty("detachedMember", host.toString());
        assert (boolean) coordinate.invoke(remote) && permit.getInt(remote) == 31
            : "Explicitly detaching the host selects active-worker authority while the host remains coordinator";
        var supplier = ((Map<UUID, com.google.gson.JsonObject>) reports.get(remote)).get(host);
        supplier.addProperty("serviceReturning", true); supplier.addProperty("currentRow", 0);
        report.addProperty("currentRow", 60); report.addProperty("verifiedBase", 61); report.addProperty("currentResolved", true);
        assert (boolean) coordinate.invoke(remote) && permit.getInt(remote) == 31 && checkpoint.getInt(remote) == 60
            : "A stuck returner at row zero cannot cap active builders at row sixteen";
        var center = new net.minecraft.core.BlockPos(0, 64, 60);
        assert !SwarmCrew.distantSupplier(null, center);
        assert !SwarmCrew.distantSupplier(supplier, center) : "Missing position is never a clearance acknowledgment";
        supplier.addProperty("x", 0); supplier.addProperty("y", 64); supplier.addProperty("z", 47);
        assert SwarmCrew.distantSupplier(supplier, center);
        supplier.addProperty("z", 48); assert !SwarmCrew.distantSupplier(supplier, center);
        set(remote, "supplyOwner", worker); set(remote, "supplyCenter", center);
        var barrier = crewMethod("supplyBarrierReady"); barrier.setAccessible(true);
        assert !(boolean) barrier.invoke(remote) : "Nearby returning worker still has to yield";
        supplier.addProperty("z", 0);
        assert (boolean) barrier.invoke(remote) : "Distant returner cannot veto another worker's supply reservation";
        var compactSites = new JsonObject(); var compactSite = new JsonObject(); compactSite.addProperty("compact", true);
        compactSites.add(worker.toString(), compactSite); assignment.add("suppliers", compactSites);
        set(remote, "pickupCenter", center.offset(0, 0, -1));
        assert remote.supplyYieldDistance() == 3.5;
        supplier.addProperty("z", 63);
        assert (boolean) barrier.invoke(remote) : "A working bot three rows forward does not block compact supply admission";
        supplier.addProperty("z", 57);
        assert (boolean) barrier.invoke(remote) : "Detached supply admission cannot wait for adjacent crew members";
        var ackField = crewField("acknowledgments"); ackField.setAccessible(true);
        var receipts = (Set<UUID>) ackField.get(remote); receipts.add(host);
        assert (boolean) barrier.invoke(remote) : "The native yield receipt releases the compact barrier even before the next position report";
        receipts.clear(); supplier.addProperty("z", 55);
        assert (boolean) barrier.invoke(remote) : "A fresh clear position also releases the barrier if its receipt was delayed";
        supplier.remove("x");
        assert (boolean) barrier.invoke(remote) : "Missing unrelated crew telemetry cannot veto local restocking";
        supplier.addProperty("x", 0); assignment.remove("suppliers"); set(remote, "pickupCenter", null);
        assert remote.supplyYieldDistance() == 5.5 : "Legacy supply records keep their existing yield margin";
        assignment.addProperty("x", 0); assignment.addProperty("y", 64); assignment.addProperty("z", 0);
        var layout = new com.google.gson.JsonObject(); layout.addProperty("dx", 0); layout.addProperty("dz", 1); assignment.add("layout", layout);
        set(remote, "supplyOwner", null); set(remote, "ticks", 1); set(remote, "regroupRetryAfter", 100);
        supplier.addProperty("z", 58); supplier.addProperty("serviceReady", true);
        var returning = crewMethod("coordinateServiceReturn"); returning.setAccessible(true);
        returning.invoke(remote);
        var rejoining = crewField("rejoiningSupply"); rejoining.setAccessible(true);
        assert !rejoining.getBoolean(remote) : "A ready returner cannot immediately restart a timed-out regroup barrier";
        set(remote, "regroupRetryAfter", 0);
        supplier.addProperty("z", 0);
        returning.invoke(remote);
        assert !rejoining.getBoolean(remote) : "Arriving at an old rendezvous requires another travel leg, not a crew-wide regroup";
    }
    private static void sharedBreakOrder() throws Exception {
        var layout = new com.google.gson.JsonObject();
        assert SwarmCrew.workSharing(layout) == SwarmCrew.WorkSharing.Lanes : "Old saved jobs keep their lane behavior";
        layout.addProperty("workSharing", "BreakOrder");
        assert SwarmCrew.workSharing(layout) == SwarmCrew.WorkSharing.BreakOrder;
        for (int row = 0; row <= 512; row++) {
            assert SwarmCrew.leadLimit(512, row, SwarmCrew.WorkSharing.BreakOrder) == Math.min(512, row + 1);
            assert SwarmCrew.leadLimit(512, row, SwarmCrew.WorkSharing.Lanes, 12) == Math.min(512, row + 5);
        }
        layout.addProperty("workSharing", "Unknown");
        try { SwarmCrew.workSharing(layout); throw new AssertionError("Unknown strategy accepted"); }
        catch (IllegalArgumentException expected) { }
        for (int[] direction : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int dx = direction[0], dz = direction[1];
            List<BlockPos> targets = new ArrayList<>();
            BlockPos origin = new BlockPos(-123, 116, 777);
            for (int width = 2; width <= 5; width++) for (int count = 1; count <= width; count++) {
                Set<BlockPos> lanes = new HashSet<>();
                for (int member = 0; member < count; member++) {
                    assert SwarmCrew.workPosition(origin, dx, dz, width, count, member, SwarmCrew.WorkSharing.BreakOrder).equals(origin)
                        : "Break Order must give every worker the same center lane, including after roster changes";
                    lanes.add(SwarmCrew.workPosition(origin, dx, dz, width, count, member, SwarmCrew.WorkSharing.Lanes));
                }
                assert lanes.size() == count : "Lanes must retain distinct standing positions";
            }
            for (int row = 1; row <= 5; row++) for (int col = -2; col <= 2; col++) for (int y = 0; y < 3; y++)
                targets.add(origin.offset(row * dx - col * dz, y, row * dz + col * dx));
            Set<BlockPos> firstTargets = new HashSet<>();
            for (int worker = 0; worker < 5; worker++) {
                List<BlockPos> ordered = SwarmCrew.breakOrderTargets(targets, dx, dz, worker);
                assert ordered.size() == targets.size() && new HashSet<>(ordered).equals(new HashSet<>(targets))
                    : "Every order must retain the entire face, not a lane partition";
                assert firstTargets.add(ordered.getFirst()) : "The five patterns should start on different cells of a 5x3 face";
                int previousRow = 0;
                for (BlockPos pos : ordered) {
                    int row = (pos.getX() - origin.getX()) * dx + (pos.getZ() - origin.getZ()) * dz;
                    assert row >= previousRow : "Speculative ordering cannot promote far work ahead of the next row";
                    previousRow = row;
                }
            }
        }
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        BlockPos block = new BlockPos(0, 116, 2), other = block.east();
        Map<UUID, List<BlockPos>> reports = new LinkedHashMap<>();
        reports.put(first, List.of(block)); reports.put(second, List.of(block, other));
        Map<BlockPos, UUID> owners = SwarmCrew.shareMining(reports, Map.of());
        assert owners.get(block).equals(first) && owners.get(other).equals(second) : "Simultaneous claims resolve once in roster order";
        owners = SwarmCrew.shareMining(reports, Map.of(block, second));
        assert owners.get(block).equals(second) : "An incumbent slow miner retains its block";
        reports.remove(second);
        owners = SwarmCrew.shareMining(reports, owners);
        assert owners.equals(Map.of(block, first)) : "Leaving/restocking releases targets without a separate unlock exchange";
        assert SwarmCrew.shareMining(Map.of(), owners).isEmpty();
        var hello = new com.google.gson.JsonObject();
        assert SwarmCrew.reportedMining(hello).isEmpty() : "Legacy peers have no mining telemetry";
        hello.add("mining", new com.google.gson.Gson().toJsonTree(List.of(block.asLong(), other.asLong())));
        assert SwarmCrew.reportedMining(hello).equals(List.of(block, other));
        hello.add("mining", new com.google.gson.Gson().toJsonTree(List.of(1, 2, 3, 4)));
        try { SwarmCrew.reportedMining(hello); throw new AssertionError("Unbounded targets accepted"); }
        catch (IllegalArgumentException expected) { }
        hello.add("mining", new com.google.gson.Gson().toJsonTree(List.of("1.25")));
        try { SwarmCrew.reportedMining(hello); throw new AssertionError("Fractional packed position accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void cancellationCleanup() throws Exception {
        assert !SwarmCrew.releaseExpired(30_000_000_099L, 100);
        assert SwarmCrew.releaseExpired(30_000_000_100L, 100);
        assert SwarmCrew.releaseExpired(Long.MIN_VALUE + 29_999_999_900L, Long.MAX_VALUE - 99);
        var crew = new SwarmCrew(null, "Cancel test");
        String id = UUID.randomUUID().toString();
        var assignment = new com.google.gson.JsonObject(); assignment.addProperty("job", id);
        set(crew, "assignment", assignment); set(crew, "job", id); set(crew, "generation", 1);
        set(crew, "phase", "complete");
        assert !crew.roadComplete() : "A local builder's completion phase is not the full crew's verified outcome";
        assignment.addProperty("roadComplete", true);
        for (String phase : List.of("complete", "regrouping", "synchronized")) {
            set(crew, "phase", phase);
            assert crew.roadComplete() : "Verified completion survives cleanup phase changes";
        }
        set(crew, "releasing", true); set(crew, "regrouping", true);
        var apply = crewMethod("apply", com.google.gson.JsonObject.class); apply.setAccessible(true);
        for (String type : List.of("regroupCancel", "begin", "regroup", "reconfigure")) {
            var m = new com.google.gson.JsonObject(); m.addProperty("type", type); m.addProperty("job", id); m.addProperty("generation", 1);
            apply.invoke(crew, m);
            assert crew.isReleasing() : "Late " + type + " cannot replace or revive a cancelled execution";
        }
        try (var bytes = SwarmCrew.class.getResourceAsStream("SwarmCrew.class");
             var shared = SwarmCrew.class.getResourceAsStream("/dev/monocle/coordinator/HighwayCoordinator.class")) {
            var code = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var clear = code.methods().stream().filter(m -> m.methodName().equalsString("clearLocal")).findFirst().orElseThrow();
            var calls = clear.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
            assert calls.indexOf("archiveInterruptedSupplies") >= 0 && calls.indexOf("archiveInterruptedSupplies") < calls.indexOf("stopJob")
                && calls.indexOf("archiveInterruptedSupplies") < calls.indexOf("deleteIfExists") : "Preserve interrupted supply locations before clearing native state or the active journal";
            var sharedCode = java.lang.classfile.ClassFile.of().parse(shared.readAllBytes());
            var release = sharedCode.methods().stream().filter(m -> m.methodName().equalsString("releaseJob")).findFirst().orElseThrow();
            var releaseCalls = release.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
            assert releaseCalls.indexOf("checkpointJobs") >= 0 && releaseCalls.indexOf("checkpointJobs") < releaseCalls.indexOf("broadcast")
                : "Persist the completed catalog outcome before release enters regrouping";
            var coordinate = sharedCode.methods().stream().filter(m -> m.methodName().equalsString("coordinate")).findFirst().orElseThrow();
            assert coordinate.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).anyMatch(c -> c.name().equalsString("releaseJob"))
                : "Verified completion starts release without requiring a GUI or workflow wrapper";
            assert release.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.ConstantInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.ConstantInstruction.class::cast).noneMatch(c -> "Wait for the current crew synchronization to finish.".equals(c.constantValue()))
                : "Cancellation supersedes regrouping instead of being rejected by it";
        }
    }

    private static void supplyPlacementPrediction(BlockPos target) throws Exception {
        // Reproduce useItemOn ordering: validate air, predict a box locally, run the
        // outgoing packet guard, then record placementSent. Nearby suppliers overlap.
        var owned = new com.google.gson.JsonArray();
        var predicted = new java.util.HashSet<BlockPos>();
        java.util.function.BiPredicate<BlockPos, BlockPos> protectedByPeer = (checked, inFlight) ->
            !SwarmCrew.containsSupplyPosition(owned, checked, inFlight)
                && (predicted.contains(checked) || predicted.contains(checked.above()));
        assert !protectedByPeer.test(target, null) : "The air target passes the initial placement check";
        predicted.add(target);
        assert protectedByPeer.test(target, null) : "Reproduce the old guard rejecting its own predicted box";
        for (BlockPos hit : List.of(target, target.below())) {
            assert !protectedByPeer.test(hit, target) : "The validated in-flight placement must reach the server before placementSent is set";
        }
        BlockPos peer = target.east();
        predicted.add(peer);
        assert protectedByPeer.test(peer, target) && protectedByPeer.test(peer.below(), target)
            : "The in-flight exception must not expose a neighboring worker's box or footing";
        assert protectedByPeer.test(target, null) : "The exception ends immediately after the placement callback";
        // Paired chests use the same callback and must protect the already-placed first chest.
        JsonObject first = new JsonObject(); first.addProperty("x", target.getX()); first.addProperty("y", target.getY()); first.addProperty("z", target.getZ());
        owned.add(first);
        assert !protectedByPeer.test(target, peer) && !protectedByPeer.test(peer, peer);
        try (var bytes = SwarmCrew.class.getResourceAsStream("SwarmCrew.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var method = compiled.methods().stream().filter(m -> m.methodName().equalsString("protectedPosition")).findFirst().orElseThrow();
            assert method.code().orElseThrow().elementList().stream().anyMatch(e -> e instanceof java.lang.classfile.instruction.InvokeInstruction call
                && call.name().equalsString("crewPlacingSupply")) : "The live packet guard must use the in-flight target";
        }
    }

    private static void detachedSuppliesAndDuties() throws Exception {
        UUID first = UUID.randomUUID(), middle = UUID.randomUUID(), last = UUID.randomUUID();
        List<UUID> roster = List.of(first, middle, last);
        assert SwarmCrew.serviceMembers(roster, middle).equals(List.of(first, last));
        assert SwarmCrew.serviceMembers(roster, null).equals(roster) : "Returning suppliers regain their original roster order, not an appended lane";
        assert !SwarmCrew.sharedSupplyHold(middle, middle) : "A detached supplier must not freeze active movement or verification";
        assert SwarmCrew.sharedSupplyHold(middle, null) : "The serial fallback still protects nearby shared restocking";
        assert SwarmCrew.sharedSupplyHold(first, middle) : "An unrelated active supply reservation remains a shared hold";
        assert SwarmCrew.resumeLocalBuilder(true, false, true) : "A paused detached builder must resume even while other lanes are still positioning";
        assert !SwarmCrew.resumeLocalBuilder(true, false, false) : "Ordinary lane positioning has no builder to resume";
        assert !SwarmCrew.resumeLocalBuilder(false, true, true) : "A remote-only coordinator must not control an unrelated local builder";
        assert SwarmCrew.dutyAllows("Build", true) && SwarmCrew.dutyAllows("Build", false);
        assert SwarmCrew.dutyAllows("Excavate", true) && !SwarmCrew.dutyAllows("Excavate", false);
        assert !SwarmCrew.dutyAllows("Pave", true) && SwarmCrew.dutyAllows("Pave", false);
        for (int row = 0; row < 20; row++) for (int column = 0; column < 5; column++) {
            assert SwarmCrew.capableOwner(5, 3, column, row, index -> index == 1) == 1 : "A dedicated excavator owns all excavation, even outside its walking column";
            int paver = SwarmCrew.capableOwner(5, 3, column, row, index -> index != 1);
            assert paver == 0 || paver == 2 : "Paving goes only to workers with paving capability";
        }
        var record = new com.google.gson.JsonObject(); var layout = new com.google.gson.JsonObject();
        layout.addProperty("operation", "Build"); record.add("layout", layout);
        record.add("members", new com.google.gson.Gson().toJsonTree(roster.stream().map(UUID::toString).toList()));
        record.addProperty("startRow", 0); record.addProperty("length", 100);
        SwarmCrew.normalizeWorkflows(record, Set.copyOf(roster));
        assert record.getAsJsonObject("workflow").get("duty").getAsString().equals("Build");
        assert record.getAsJsonObject("duties").get(first.toString()).getAsString().equals("Build");
        SwarmCrew coordinator = new SwarmCrew(null, "Supply checks"); set(coordinator, "assignment", record); set(coordinator, "checkpointRow", 11);
        var canDetach = crewMethod("canDetach", UUID.class); canDetach.setAccessible(true);
        assert (boolean) canDetach.invoke(coordinator, middle);
        set(coordinator, "checkpointRow", 10);
        assert (boolean) canDetach.invoke(coordinator, middle) : "Early restocks also detach; native travel verifies the existing rear road";
        set(coordinator, "checkpointRow", 100);
        assert !(boolean) canDetach.invoke(coordinator, middle) : "Final-row completion must never emit a zero-length replacement assignment";
        set(coordinator, "checkpointRow", 11);
        var duties = record.getAsJsonObject("duties"); duties.addProperty(first.toString(), "Pave"); duties.addProperty(middle.toString(), "Excavate"); duties.addProperty(last.toString(), "Pave");
        assert (boolean) canDetach.invoke(coordinator, middle) : "A required specialist can restock; unfinishable cells wait for its return";
        assert (boolean) canDetach.invoke(coordinator, first);
        record.add("activeMembers", new com.google.gson.Gson().toJsonTree(List.of(last.toString())));
        assert (boolean) canDetach.invoke(coordinator, last) : "The verified host checkpoint anchors the front while every builder resupplies";
        multipleSupplyRuns(roster);
    }

    private static JsonObject supplyUpdate(String type, UUID worker, int revision, JsonObject site) {
        JsonObject update = new JsonObject(); update.addProperty("type", type); update.addProperty("supplier", worker.toString());
        update.addProperty("serviceRevision", revision);
        if (site != null) update.add("site", site.deepCopy());
        return update;
    }

    private static void rollingDepartures(JsonObject original, List<UUID> roster) {
        for (boolean active : List.of(false, true)) for (boolean off : List.of(false, true)) for (boolean away : List.of(false, true))
            assert SwarmCrew.guardsManualActions(active, off, away) == (active && !off && !away);
        UUID returning = roster.get(0);
        JsonObject off = supplyUpdate("service-away", returning, 1, null); off.addProperty("armed", true);
        JsonObject withdrawn = SwarmCrew.serviceUpdateAssignment(original, off);
        assert !SwarmCrew.activeMembers(withdrawn).contains(returning);
        assert SwarmCrew.serviceUpdateAssignment(withdrawn, off) == withdrawn;
        JsonObject back = SwarmCrew.serviceUpdateAssignment(withdrawn, supplyUpdate("service-back", returning, 2, null));
        assert SwarmCrew.activeMembers(back).equals(roster);
        assert SwarmCrew.serviceUpdateAssignment(back, off) == back : "An old withdrawal cannot undo a return";
        for (String key : List.of("job", "generation", "startRow", "layout", "length"))
            assert java.util.Objects.equals(original.get(key), back.get(key)) : "Rolling return reset " + key;
        JsonObject current = original.deepCopy();
        current.addProperty("supplyOwner", "existing recovery"); current.addProperty("serviceLock", "preserved lock");
        JsonObject before = current.deepCopy();
        for (int i = 0; i < roster.size(); i++) {
            UUID worker = roster.get(i);
            JsonObject planned = SwarmCrew.supplyAssignment(current, worker, true, 7);
            JsonObject site = planned.getAsJsonObject("suppliers").getAsJsonObject(worker.toString());
            JsonObject departure = supplyUpdate("service-detach", worker, 1, site);
            JsonObject next = SwarmCrew.serviceUpdateAssignment(current, departure);
            assert SwarmCrew.activeMembers(next).equals(roster.subList(i + 1, roster.size())) : "Each departure immediately redistributes the remaining lanes";
            for (String key : before.keySet()) assert before.get(key).equals(next.get(key)) : "Departure changed job geometry/progress/lock: " + key;
            if (current.has("suppliers")) for (var entry : current.getAsJsonObject("suppliers").entrySet())
                assert entry.getValue().equals(next.getAsJsonObject("suppliers").get(entry.getKey())) : "Another runner's recovery site must survive";
            assert SwarmCrew.serviceUpdateAssignment(next, departure) == next : "Duplicate departure cannot restart travel";
            assert !SwarmCrew.serviceRequestCurrent(next, worker, supplyUpdate("request", worker, 0, null));
            assert SwarmCrew.serviceRequestCurrent(next, worker, supplyUpdate("request", worker, 1, null));
            assert SwarmCrew.serviceRevision(current, worker) == 0 : "Do not mutate earlier recovery snapshots";
            current = next;
        }
        assert SwarmCrew.activeMembers(current).isEmpty() : "All suppliers can leave; retain the verified checkpoint";
        UUID worker = roster.get(1);
        JsonObject join = supplyUpdate("service-join", worker, 2, null);
        current = SwarmCrew.serviceUpdateAssignment(current, join);
        assert SwarmCrew.activeMembers(current).equals(List.of(worker));
        JsonObject site = SwarmCrew.supplyAssignment(current, worker, true, 20).getAsJsonObject("suppliers").getAsJsonObject(worker.toString());
        JsonObject departure = supplyUpdate("service-detach", worker, 3, site);
        current = SwarmCrew.serviceUpdateAssignment(current, departure);
        assert SwarmCrew.serviceUpdateAssignment(current, join) == current : "A delayed old return cannot undo a new departure";
        JsonObject resumed = SwarmCrew.serviceUpdateAssignment(current, supplyUpdate("service-join", worker, 4, null));
        assert SwarmCrew.serviceUpdateAssignment(resumed, departure) == resumed : "A delayed old departure cannot undo a new return";
        JsonObject caughtUp = SwarmCrew.serviceUpdateAssignment(original, supplyUpdate("service-join", worker, 4, null));
        assert SwarmCrew.activeMembers(caughtUp).equals(roster) && SwarmCrew.serviceRevision(caughtUp, worker) == 4;
        for (String invalid : List.of("stranger", "height", "side", "front")) {
            JsonObject bad = site.deepCopy(); UUID id = worker;
            switch (invalid) {
                case "stranger" -> id = UUID.randomUUID();
                case "height" -> bad.addProperty("y", original.get("y").getAsInt() + 1);
                case "side" -> bad.addProperty("x", original.get("x").getAsInt() + 1);
                case "front" -> bad.addProperty("z", original.get("z").getAsInt() + original.get("length").getAsInt());
            }
            try { SwarmCrew.serviceUpdateAssignment(original, supplyUpdate("service-detach", id, 1, bad)); throw new AssertionError("Accepted " + invalid); }
            catch (IllegalArgumentException expected) { }
        }
    }

    private static void multipleSupplyRuns(List<UUID> roster) throws Exception {
        var json = new com.google.gson.Gson();
        var record = new com.google.gson.JsonObject(); var layout = new com.google.gson.JsonObject();
        layout.addProperty("operation", "Build"); layout.addProperty("width", 5); layout.addProperty("dx", 0); layout.addProperty("dz", 1);
        record.add("layout", layout); record.addProperty("x", 0); record.addProperty("y", 116); record.addProperty("z", 108922);
        record.addProperty("job", UUID.randomUUID().toString()); record.addProperty("generation", 1); record.addProperty("startRow", 0); record.addProperty("length", 512);
        record.add("members", json.toJsonTree(roster.stream().map(UUID::toString).toList()));
        rollingDepartures(record, roster);
        var one = SwarmCrew.supplyAssignment(record, roster.get(0), true, 7);
        assert SwarmCrew.activeMembers(one).equals(roster.subList(1, 3));
        assert SwarmCrew.anchorColumn(5, 2, 0) == 1 && SwarmCrew.anchorColumn(5, 2, 1) == 3;
        var two = SwarmCrew.supplyAssignment(one, roster.get(1), true, 7);
        assert SwarmCrew.activeMembers(two).equals(List.of(roster.get(2)));
        assert SwarmCrew.anchorColumn(5, 1, 0) == 2;
        var sites = two.getAsJsonObject("suppliers");
        int firstSite = sites.getAsJsonObject(roster.get(0).toString()).get("z").getAsInt();
        int secondSite = sites.getAsJsonObject(roster.get(1).toString()).get("z").getAsInt();
        assert firstSite == 108922 + 7 - 3 : "Stage three rows behind the verified crew front";
        assert firstSite - secondSite == 3 : "Queued runners use compact three-row spacing";
        assert SwarmCrew.compactSite(sites.getAsJsonObject(roster.get(0).toString()));
        var legacy = one.deepCopy(); legacy.getAsJsonObject("suppliers").getAsJsonObject(roster.get(0).toString()).remove("compact");
        var mixed = SwarmCrew.supplyAssignment(legacy, roster.get(1), true, 7);
        assert firstSite - mixed.getAsJsonObject("suppliers").getAsJsonObject(roster.get(1).toString()).get("z").getAsInt() >= 16
            : "New compact sites must keep clear of an existing legacy reservation";
        var five = record.deepCopy(); var fiveRoster = new ArrayList<>(roster);
        fiveRoster.add(UUID.randomUUID()); fiveRoster.add(UUID.randomUUID());
        five.add("members", json.toJsonTree(fiveRoster.stream().map(UUID::toString).toList()));
        for (int i = 0; i < fiveRoster.size(); i++) {
            five = SwarmCrew.supplyAssignment(five, fiveRoster.get(i), true, 0);
            assert five.getAsJsonObject("suppliers").getAsJsonObject(fiveRoster.get(i).toString()).get("z").getAsInt() == 108922 - 3 * (i + 1)
                : "All five runners fit compact, distinct staging positions even before the first row";
        }
        assert one.getAsJsonObject("suppliers").size() == 1 : "A new handoff cannot mutate an earlier snapshot";
        var returned = SwarmCrew.supplyAssignment(two, roster.get(0), false, 30);
        assert SwarmCrew.activeMembers(returned).equals(List.of(roster.get(0), roster.get(2)));
        assert returned.getAsJsonObject("suppliers").get(roster.get(1).toString()).equals(sites.get(roster.get(1).toString()))
            : "First runner returns to the advanced front without resetting the second runner's site";
        var rolling = SwarmCrew.joinedSupplyAssignment(two, roster.get(0));
        assert SwarmCrew.activeMembers(rolling).equals(SwarmCrew.activeMembers(returned));
        for (String key : two.keySet()) if (!Set.of("suppliers", "activeMembers").contains(key))
            assert two.get(key).equals(rolling.get(key)) : "Rolling rejoin changed unrelated assignment state: " + key;
        assert rolling.getAsJsonObject("suppliers").get(roster.get(1).toString()).equals(sites.get(roster.get(1).toString()));
        assert SwarmCrew.joinedSupplyAssignment(rolling, roster.get(0)) == rolling : "Duplicate handoffs are idempotent";
        for (var sharing : SwarmCrew.WorkSharing.values()) for (int[] direction : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            var geometry = two.getAsJsonObject("layout");
            geometry.addProperty("dx", direction[0]); geometry.addProperty("dz", direction[1]); geometry.addProperty("width", 5);
            geometry.addProperty("workSharing", sharing.name());
            BlockPos center = new BlockPos(0, 116, 30);
            assert SwarmCrew.returningLane(two, roster.get(0), center).equals(
                SwarmCrew.workPosition(center, direction[0], direction[1], 5, 2, 0, sharing));
        }
        layout.addProperty("dx", 0); layout.addProperty("dz", 1);
        var allOut = SwarmCrew.supplyAssignment(two, roster.get(2), true, 7);
        assert SwarmCrew.activeMembers(allOut).isEmpty();
        var resumed = SwarmCrew.supplyAssignment(allOut, roster.get(1), false, 7);
        assert SwarmCrew.activeMembers(resumed).equals(List.of(roster.get(1)));
        SwarmCrew.validateActiveMembers(resumed, Set.copyOf(roster));
        var rollingFirst = SwarmCrew.joinedSupplyAssignment(allOut, roster.get(1));
        assert SwarmCrew.activeMembers(rollingFirst).equals(List.of(roster.get(1)));
        var rollingSecond = SwarmCrew.joinedSupplyAssignment(rollingFirst, roster.get(0));
        var rollingLast = SwarmCrew.joinedSupplyAssignment(rollingSecond, roster.get(2));
        assert SwarmCrew.activeMembers(rollingLast).equals(roster);
        assert rollingLast.get("generation").equals(allOut.get("generation"));
        assert rollingLast.getAsJsonObject("suppliers").isEmpty();
        SwarmCrew.validateActiveMembers(rollingLast, Set.copyOf(roster));
        SwarmCrew controller = new SwarmCrew(null, "Concurrent supplies");
        set(controller, "assignment", two); set(controller, "supplyOwner", roster.get(1));
        var shared = crewMethod("sharedSupplyHold"); shared.setAccessible(true);
        assert !(boolean) shared.invoke(controller) : "The second supplier is just as detached as the first";
        set(controller, "supplyOwner", roster.get(2));
        assert (boolean) shared.invoke(controller) : "Non-supply reservations retain their existing protection";
        var position = new com.google.gson.JsonObject(); position.addProperty("x", 0); position.addProperty("y", 116); position.addProperty("z", 109434);
        var rear = new net.minecraft.core.BlockPos(0, 116, 109424);
        assert SwarmCrew.outsideSupplyArea(position, rear) : "Builders ten rows ahead must not wait for an ACK to a distant rear container";
        position.addProperty("z", 109432);
        assert !SwarmCrew.outsideSupplyArea(position, rear) : "Nearby players still require clearance";
        assert !SwarmCrew.outsideSupplyArea(new com.google.gson.JsonObject(), rear);

        String lock = UUID.randomUUID().toString(), job = record.get("job").getAsString();
        var release = new com.google.gson.JsonObject(); release.addProperty("type", "release"); release.addProperty("job", job);
        release.addProperty("generation", 1); release.addProperty("lock", lock);
        assert SwarmCrew.supplyControlMatches(job, 3, roster.get(0), lock, roster.get(0), release)
            : "A valid release sent just before two lane handoffs must still reach its owner";
        assert !SwarmCrew.supplyControlMatches(job, 3, roster.get(0), lock, roster.get(1), release);
        assert !SwarmCrew.supplyControlMatches(job, 3, roster.get(0), "new-lock", roster.get(0), release);
        assert !SwarmCrew.supplyControlMatches("different-job", 3, roster.get(0), lock, roster.get(0), release);
        release.addProperty("type", "request");
        assert !SwarmCrew.supplyControlMatches(job, 3, roster.get(0), lock, roster.get(0), release) : "Old requests cannot change new lane work";
    }
    private static void externalBorrowing() throws Exception {
        List<UUID> roster = java.util.stream.IntStream.range(0, 5).mapToObj(i -> UUID.randomUUID()).toList();
        Set<UUID> selected = Set.of(roster.get(1), roster.get(3));
        var current = new com.google.gson.JsonObject(); var layout = new com.google.gson.JsonObject();
        layout.addProperty("operation", "Build"); layout.addProperty("width", 5); current.add("layout", layout);
        current.add("members", new com.google.gson.Gson().toJsonTree(roster.stream().map(UUID::toString).toList()));
        current.addProperty("generation", 3); current.addProperty("length", 100); current.addProperty("startRow", 11);
        SwarmCrew.normalizeWorkflows(current, Set.copyOf(roster));
        assert SwarmCrew.borrowingBlocker(current, selected, roster.get(0)).isEmpty();
        assert !SwarmCrew.borrowingBlocker(current, Set.copyOf(roster), null).isEmpty() : "Never abandon the final on-site anchor";
        assert !SwarmCrew.borrowingBlocker(current, Set.of(roster.get(0)), roster.get(0)).isEmpty() : "The participating host stays on-site";
        assert !SwarmCrew.borrowingBlocker(current, Set.of(UUID.randomUUID()), null).isEmpty();
        var overrides = new com.google.gson.JsonObject();
        overrides.add(roster.get(1).toString(), dev.monocle.client.systems.bots.BotWorkflows.legacyPlan("Pave"));
        current.add("memberWorkflows", overrides);
        SwarmCrew.normalizeWorkflows(current, Set.copyOf(roster));
        var next = SwarmCrew.borrowedAssignment(current, selected, 20);
        List<UUID> remaining = List.of(roster.get(0), roster.get(2), roster.get(4));
        assert SwarmCrew.activeMembers(next).equals(remaining);
        assert SwarmCrew.preferredMembers(next).equals(roster);
        assert next.getAsJsonObject("memberWorkflows").isEmpty() : "Borrowed workers own no active highway duties";
        assert next.getAsJsonObject("preferredWorkflows").has(roster.get(1).toString()) : "Their original workflow remains reserved for return";
        assert next.get("generation").getAsInt() == 4 && next.get("startRow").getAsInt() == 20;
        assert current.getAsJsonArray("members").size() == 5 : "Preparing handoff intent must not mutate the old live assignment";
        SwarmCrew.validatePreferredMembers(next, Set.copyOf(remaining));
        assert SwarmCrew.returnedMembers(roster, Set.copyOf(remaining), roster.get(1)).equals(List.of(roster.get(0), roster.get(1), roster.get(2), roster.get(4)));
        assert SwarmCrew.returnedMembers(roster, Set.copyOf(SwarmCrew.returnedMembers(roster, Set.copyOf(remaining), roster.get(1))), roster.get(3)).equals(roster);
        var reservation = next.getAsJsonObject("borrowedMembers").getAsJsonObject(roster.get(1).toString());
        var receipt = reservation.deepCopy();
        assert SwarmCrew.withdrawalMatches(reservation, receipt);
        receipt.addProperty("generation", 4); assert !SwarmCrew.withdrawalMatches(reservation, receipt) : "A different generation cannot acknowledge this withdrawal";
        receipt.addProperty("generation", 3); receipt.addProperty("token", UUID.randomUUID().toString());
        assert !SwarmCrew.withdrawalMatches(reservation, receipt) : "Another handoff cannot authorize scheduler dispatch";
        SwarmCrew coordinator = new SwarmCrew(null, "Borrow test"); set(coordinator, "assignment", next);
        assert coordinator.reservedReturns().equals(selected) && !coordinator.borrowReady(roster.get(1));
        reservation.addProperty("ready", true);
        assert coordinator.borrowReady(roster.get(1)) : "Dispatch waits for the matching safe-withdrawal acknowledgment";
        var invalid = next.deepCopy(); invalid.getAsJsonArray("members").add(roster.get(1).toString());
        try { SwarmCrew.validatePreferredMembers(invalid, new HashSet<>(invalid.getAsJsonArray("members").asList().stream().map(e -> UUID.fromString(e.getAsString())).toList())); throw new AssertionError("Borrowed worker cannot also own a lane"); }
        catch (IllegalArgumentException expected) { }
        for (UUID member : roster) if (!member.equals(roster.get(1))) overrides.add(member.toString(), dev.monocle.client.systems.bots.BotWorkflows.legacyPlan("Pave"));
        overrides.add(roster.get(1).toString(), dev.monocle.client.systems.bots.BotWorkflows.legacyPlan("ClearTunnel"));
        SwarmCrew.normalizeWorkflows(current, Set.copyOf(roster));
        assert !SwarmCrew.borrowingBlocker(current, selected, null).isEmpty() : "Do not borrow the sole excavator from a mixed-duty highway";
    }
    private static void offDutyMembership() {
        assert !SwarmCrew.rejoinArmed(false, true) : "Toggling off nearby must not immediately rejoin";
        assert SwarmCrew.rejoinArmed(false, false) && SwarmCrew.rejoinArmed(true, true) : "Leave range, then return, arms automatic rejoin";
        List<UUID> roster = java.util.stream.IntStream.range(0, 5).mapToObj(i -> UUID.randomUUID()).toList();
        var current = new com.google.gson.JsonObject(); var layout = new com.google.gson.JsonObject();
        layout.addProperty("operation", "Build"); layout.addProperty("width", 5); current.add("layout", layout);
        current.add("members", new com.google.gson.Gson().toJsonTree(roster.stream().map(UUID::toString).toList()));
        current.addProperty("generation", 3); current.addProperty("length", 512); current.addProperty("startRow", 100);
        SwarmCrew.normalizeWorkflows(current, Set.copyOf(roster));
        var away = new com.google.gson.JsonObject(); away.addProperty(roster.get(0).toString(), false); away.addProperty(roster.get(2).toString(), true);
        var next = SwarmCrew.availabilityAssignment(current, away, 354);
        assert next.get("members").equals(current.get("members")) : "Off-duty workers retain their full roster and cancellation membership";
        assert SwarmCrew.activeMembers(next).equals(List.of(roster.get(1), roster.get(3), roster.get(4)));
        assert next.get("generation").getAsInt() == 4 && next.get("startRow").getAsInt() == 354;
        var returned = SwarmCrew.availabilityAssignment(next, new com.google.gson.JsonObject(), 380);
        assert SwarmCrew.activeMembers(returned).equals(roster) : "Return restores original positions, including the host";
        assert !current.has("awayMembers") : "Planning cannot mutate live ownership before its barrier";
        var borrowed = SwarmCrew.borrowedAssignment(next, Set.of(roster.get(1)), 360);
        assert borrowed.getAsJsonArray("members").size() == 4 && SwarmCrew.activeMembers(borrowed).equals(List.of(roster.get(3), roster.get(4)));
        SwarmCrew.validateActiveMembers(borrowed, new HashSet<>(roster.stream().filter(id -> !id.equals(roster.get(1))).toList()));
        var all = new com.google.gson.JsonObject(); roster.forEach(id -> all.addProperty(id.toString(), true));
        try { SwarmCrew.availabilityAssignment(current, all, 354); throw new AssertionError("No highway without an active worker"); }
        catch (IllegalArgumentException expected) { }
        var corrupt = next.deepCopy(); corrupt.add("activeMembers", current.get("members").deepCopy());
        try { SwarmCrew.validateActiveMembers(corrupt, Set.copyOf(roster)); throw new AssertionError("Away and active ownership cannot overlap"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void remoteCoordinator(net.minecraft.core.BlockPos arbitrary) throws Exception {
        SwarmCrew remote = new SwarmCrew(null, "Remote road");
        var assignment = crewField("assignment"); assignment.setAccessible(true);
        var job = crewField("job"); job.setAccessible(true);
        var phase = crewField("phase"); phase.setAccessible(true);
        var apply = crewMethod("apply", com.google.gson.JsonObject.class); apply.setAccessible(true);
        assignment.set(remote, new com.google.gson.JsonObject()); job.set(remote, "isolated-job"); phase.set(remote, "positioning");
        assert remote.assigned() && !remote.localAssigned();
        assert !remote.hold() && remote.owns(arbitrary) && remote.allowsWork(arbitrary) && remote.readyToAdvance(arbitrary);
        assert remote.requestSupply(arbitrary) && remote.clearance(arbitrary) && !remote.protectedPosition(arbitrary);
        remote.position(); remote.guard(null); // Remote-only coordination must never access local movement or packet controls.
        var message = new com.google.gson.JsonObject(); message.addProperty("job", "isolated-job");
        message.addProperty("type", "begin"); apply.invoke(remote, message);
        assert phase.get(remote).equals("building") : "A remote-only host coordinates start without occupying a ready lane";
        message.addProperty("type", "pause"); apply.invoke(remote, message);
        assert phase.get(remote).equals("paused");
        message.addProperty("type", "resume"); apply.invoke(remote, message);
        assert phase.get(remote).equals("building") : "Remote resume must not reach the host's unrelated Highway Builder";
    }
    private static void lifecycleRecords(UUID host, UUID worker) throws Exception {
        var pending = new LinkedHashMap<String, Set<UUID>>();
        String old = UUID.randomUUID().toString(), current = UUID.randomUUID().toString();
        pending.put(old, new LinkedHashSet<>(Set.of(host, worker)));
        pending.put(current, new LinkedHashSet<>(Set.of(worker)));
        assert !SwarmCrew.acknowledgeEnd(pending, old, UUID.randomUUID()) : "Only a canceled member can acknowledge its ending";
        assert SwarmCrew.acknowledgeEnd(pending, old, worker);
        assert !SwarmCrew.acknowledgeEnd(pending, old, worker) : "Duplicate receipts are harmless";
        assert pending.get(old).contains(host) && pending.get(current).contains(worker) : "Receipt for an old job must not clear another crew/job";
        assert SwarmCrew.acknowledgeEnd(pending, old, host) && !pending.containsKey(old);
        var layout = new com.google.gson.JsonObject();
        layout.addProperty("dx", 0); layout.addProperty("dz", 1); layout.addProperty("width", 5); layout.addProperty("height", 3);
        var origin = new net.minecraft.core.BlockPos(0, 64, 0);
        var area = SwarmCrew.workArea(origin, layout, 128);
        assert area.intersects(SwarmCrew.workArea(origin.offset(15, 0, 0), layout, 128)) : "Separate supply locks need a pickup/yield buffer";
        assert !area.intersects(SwarmCrew.workArea(origin.offset(40, 0, 0), layout, 128));
        assert !area.intersects(SwarmCrew.workArea(origin.offset(0, 40, 0), layout, 128));
        var cross = layout.deepCopy(); cross.addProperty("dx", 1); cross.addProperty("dz", 0);
        assert area.intersects(SwarmCrew.workArea(origin.offset(-64, 0, 64), cross, 128));
        var record = new com.google.gson.JsonObject();
        record.addProperty("job", current); record.addProperty("index", 0); record.addProperty("count", 2);
        record.addProperty("x", 0); record.addProperty("y", 64); record.addProperty("z", 0); record.addProperty("length", 128);
        record.add("layout", layout); record.add("members", new com.google.gson.Gson().toJsonTree(List.of(host.toString(), worker.toString())));
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("monocle-bot-record-check-");
        java.nio.file.Path path = directory.resolve("recovery.json");
        try {
            SwarmCrew.writeRecord(path, record);
            assert SwarmCrew.readRecovery(path).equals(record) : "Atomic recovery record must survive a restart";
            record.addProperty("job", old); SwarmCrew.writeRecord(path, record);
            assert SwarmCrew.readRecovery(path).get("job").getAsString().equals(old) : "Replacing a journal must not leave stale assignment metadata";
        } finally { java.nio.file.Files.deleteIfExists(path); java.nio.file.Files.deleteIfExists(directory); }
    }
    private static void workerConnection(String key) throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        int port;
        try (ServerSocket unused = new ServerSocket(0, 1, loopback)) { port = unused.getLocalPort(); }
        var refused = new SwarmWorker("localhost", port, key);
        await(() -> !refused.isAlive());
        assert !refused.connected() && !refused.failure().isEmpty();
        // A later attempt can authenticate when the host becomes available. The constructor
        // must return before the server responds, leaving handshake work off the game thread.
        try (ServerSocket listener = new ServerSocket(port, 1, loopback)) {
            listener.setSoTimeout(5000);
            var worker = new SwarmWorker("localhost", port, key);
            var host = new SwarmConnection(listener.accept(), key, true);
            try {
                assert !worker.connected() : "TCP connection alone must not report authentication success";
                host.start();
                await(() -> host.connected() && worker.connected());
                reconnectRoster(host);
                assert worker.send("hello"); assert next(host).equals("hello");
                host.disconnect();
                await(() -> !worker.isAlive());
                assert !worker.connected();
            } finally { worker.disconnect(); host.disconnect(); worker.join(1000); host.join(1000); }
            var cancelled = new SwarmWorker("localhost", port, key);
            cancelled.disconnect();
            await(() -> !cancelled.isAlive());
            assert !cancelled.connected() && cancelled.closed();
        }
    }
    private static void stalledHandoffs() throws Exception {
        var report = new com.google.gson.JsonObject();
        report.addProperty("phase", "ready"); report.addProperty("begun", false);
        assert SwarmCrew.needsBeginRetry(report) : "A readiness race must get another start attempt";
        report.addProperty("begun", true);
        assert !SwarmCrew.needsBeginRetry(report) : "Never restart an already-owned builder";
        report.addProperty("begun", false);
        for (String phase : List.of("paused", "blocked", "positioning", "regrouping", "complete")) {
            report.addProperty("phase", phase); assert !SwarmCrew.needsBeginRetry(report);
        }
        assert !SwarmCrew.handoffExpired(699, 100);
        assert !SwarmCrew.reservationRetryDue(false, 199) && SwarmCrew.reservationRetryDue(false, 200);
        assert !SwarmCrew.reservationRetryDue(true, 10000) : "Never revoke a granted container reservation on a timeout";
        assert SwarmCrew.handoffExpired(700, 100);
        assert SwarmCrew.handoffExpired(Integer.MIN_VALUE + 300, Integer.MAX_VALUE - 299) : "Tick rollover must not strand a barrier";
        var crew = new SwarmCrew(null);
        set(crew, "phase", "regrouping"); set(crew, "regroupWasPaused", true); set(crew, "regrouping", true);
        assert crew.isPausedByHost() : "A membership handoff must retain the host's pause";
        set(crew, "regroupWasPaused", false); assert !crew.isPausedByHost();
        set(crew, "phase", "paused"); assert crew.isPausedByHost();
    }
    private static void reconnectRoster(SwarmConnection authenticated) throws Exception {
        UUID id = UUID.randomUUID(); String job = UUID.randomUUID().toString();
        var report = new com.google.gson.JsonObject();
        report.addProperty("job", job); report.addProperty("generation", 3); report.addProperty("scope", "server\nnether");
        Map<UUID, SwarmConnection> roster = new LinkedHashMap<>();
        try (Socket closedSocket = new Socket()) {
            var old = new SwarmConnection(closedSocket, "unused-test-credential-at-least-24", true); old.disconnect(); roster.put(id, old);
            report.addProperty("generation", 2);
            assert !SwarmCrew.rebindParticipant(roster, id, authenticated, job, 3, "server\nnether", report);
            assert roster.get(id) == old : "Stale lane generations cannot rebind";
            report.addProperty("generation", 3);
            assert !SwarmCrew.rebindParticipant(roster, id, authenticated, job, 3, "other-world", report);
            assert SwarmCrew.rebindParticipant(roster, id, authenticated, job, 3, "server\nnether", report);
            assert roster.get(id) == authenticated : "Same-execution reconnect replaces the dead socket";
            assert !SwarmCrew.rebindParticipant(roster, id, authenticated, job, 3, "server\nnether", report) : "Repeated hello is idempotent";
            try {
                SwarmCrew.rebindParticipant(roster, id, old, job, 3, "server\nnether", report);
                throw new AssertionError("A duplicate must not replace the live owner");
            } catch (IllegalArgumentException expected) { assert roster.get(id) == authenticated; }
            roster.put(id, null);
            try {
                SwarmCrew.rebindParticipant(roster, id, authenticated, job, 3, "server\nnether", report);
                throw new AssertionError("A worker cannot replace the local host participant");
            } catch (IllegalArgumentException expected) { assert roster.get(id) == null; }
        }
    }
    private static void transport(String hostKey, String workerKey, boolean success) throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Socket workerSocket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
            var host = new SwarmConnection(listener.accept(), hostKey, true);
            var worker = new SwarmConnection(workerSocket, workerKey, false);
            try {
                host.start(); worker.start();
                await(() -> host.connected() && worker.connected() || host.closed() || worker.closed());
                if (!success) {
                    await(() -> host.closed() && worker.closed());
                    assert !host.connected() && !worker.connected(); return;
                }
                assert host.connected() && worker.connected();
                for (int i = 0; i < 100; i++) { assert host.send("assignment:" + i); assert worker.send("report:" + i); }
                for (int i = 0; i < 100; i++) {
                    assert next(worker).equals("assignment:" + i); assert next(host).equals("report:" + i);
                }
                assert !host.send("x".repeat(16001));
                await(worker::closed);
            } finally { host.disconnect(); worker.disconnect(); host.join(1000); worker.join(1000); }
        }
    }
    private static String next(SwarmConnection c) throws Exception {
        String[] value = {null}; await(() -> (value[0] = c.poll()) != null); return value[0];
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) throw new AssertionError("Timed out waiting for loopback transport");
            Thread.sleep(5);
        }
    }
}
