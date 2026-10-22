package dev.monocle.client.systems.modules.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;

/** Small native check for the exact seven-block Wither shape and material budget. */
public final class AutoWitherTest {
    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions) throw new IllegalStateException("Run with -ea.");

        BlockPos base = new BlockPos(10, 64, -20);
        List<BlockPos> parts = AutoWither.parts(base, Direction.EAST);
        assert parts.equals(List.of(
            new BlockPos(10, 64, -20), new BlockPos(10, 65, -20),
            new BlockPos(9, 65, -20), new BlockPos(11, 65, -20),
            new BlockPos(9, 66, -20), new BlockPos(11, 66, -20), new BlockPos(10, 66, -20)));
        for (Direction forward : Direction.Plane.HORIZONTAL) {
            for (int gap : new int[] { 0, 1, 4, 16, 64 }) {
                Set<BlockPos> occupied = new HashSet<>();
                double previousProgress = Double.NEGATIVE_INFINITY;
                for (int index = 0; index < 64; index++) {
                    BlockPos site = AutoWither.lineSite(base, forward, index, gap);
                    assert site.getY() == base.getY() : "Never stack subsequent Withers vertically";
                    for (BlockPos part : AutoWither.parts(site, forward)) {
                        assert occupied.add(part) : "Every placement belongs to exactly one Wither";
                        Vec3 offset = Vec3.atCenterOf(part).subtract(Vec3.atCenterOf(base));
                        assert Math.abs(offset.x * forward.getClockWise().getStepX()
                            + offset.z * forward.getClockWise().getStepZ()) == 2 : "Keep both structures out of the center corridor";
                    }
                    if (index >= 2) {
                        BlockPos previous = AutoWither.lineSite(base, forward, index - 2, gap);
                        assert site.equals(previous.relative(forward, 3 + gap));
                        assert AutoWither.parts(previous, forward).get(3).relative(forward, gap + 1)
                            .equals(AutoWither.parts(site, forward).get(2)) : "The gap counts empty blocks between arms, not center distance";
                    }
                    for (int step = 0; step <= 7; step++) {
                        Vec3 position = AutoWither.linePosition(base, forward, index, step, false, gap);
                        Vec3 offset = position.subtract(Vec3.atBottomCenterOf(base));
                        double progress = offset.x * forward.getStepX() + offset.z * forward.getStepZ();
                        assert progress >= previousProgress : "Confirmed progress never moves the player backward";
                        previousProgress = progress;
                        assert position.y == base.getY() + 1;
                        for (BlockPos part : AutoWither.parts(site, forward)) {
                            assert part.distToCenterSqr(position.add(0, .4, 0)) < 25 : "All parts stay in flying reach";
                        }
                    }
                    Vec3 end = AutoWither.linePosition(base, forward, index, 7, false, gap);
                    Vec3 next = AutoWither.linePosition(base, forward, index + 1, 0, false, gap);
                    assert end.add(forward.getStepX() * (index % 2 == 0 ? 0 : gap), 0,
                        forward.getStepZ() * (index % 2 == 0 ? 0 : gap)).equals(next) : "Cross the gap only after completing both sides";
                    assert AutoWither.linePosition(base, forward, index, 6, true, gap).equals(end);
                }
            }
        }
        assert AutoWither.capacity(40, 30, false, false) == 10;
        assert AutoWither.capacity(40, 20, true, false) == 10;
        assert AutoWither.capacity(3, 3, false, true) == 1;
        assert AutoWither.capacity(3, 3, false, false) == 0;
        assert AutoWither.capacity(4, 2, false, false) == 0;
        assert AutoWither.enough(3, 3, 1, false) : "A hand-placed stem only needs three more soul blocks";
        assert AutoWither.enough(0, 1, 6, false) : "Returning to a six-piece structure only needs its last head";
        assert !AutoWither.enough(0, 0, 6, false);
        assert AutoWither.enough(0, 0, 6, true) : "Two-head mode is complete at six pieces";
        assert !AutoWither.countReached(100, 0) : "Zero count is unlimited";
        assert !AutoWither.countReached(1, 2) && AutoWither.countReached(2, 2);
        assert AutoWither.pairable(0) && AutoWither.pairable(4);
        assert !AutoWither.pairable(5) && !AutoWither.pairable(6) : "Never pipeline the summoning skull";
        assert AutoWither.placementAllowed(0) && AutoWither.placementAllowed(1);
        assert !AutoWither.placementAllowed(2) && !AutoWither.placementAllowed(3) : "At most two internal placements in a tick";
        assert AutoWither.boundaryPairAllowed(true, 0, 0, 0);
        assert AutoWither.boundaryPairAllowed(true, 0, 0, 2);
        assert !AutoWither.boundaryPairAllowed(true, 0, 1, 2) : "Do not prebuild beyond the count limit";
        assert !AutoWither.boundaryPairAllowed(true, 1, 0, 0) : "Respect inter-Wither delay";
        assert !AutoWither.boundaryPairAllowed(false, 0, 0, 0) : "Manual placement does not prebuild an unselected site";
        try(var bytes=AutoWither.class.getResourceAsStream("AutoWither.class")){
            var code=ClassFile.of().parse(bytes.readAllBytes());
            var calls=code.methods().stream().filter(m->m.methodName().equalsString("queuePlacement"))
                .flatMap(m->m.code().orElseThrow().elementList().stream()).filter(InvokeInstruction.class::isInstance)
                .map(InvokeInstruction.class::cast).map(call->call.name().stringValue()).toList();
            assert calls.containsAll(List.of("useItemOn","placementAllowed","reachable","swapBack"))
                &&!calls.contains("rotate") : "Production placement sends directly, preserving reach, packet budget and hotbar restoration";
            assert code.methods().stream().noneMatch(m->m.methodName().stringValue().startsWith("lambda$queuePlacement$"))
                : "The second placement cannot be serialized behind a rotation callback";
            var boundaryCalls = code.methods().stream().filter(m -> m.methodName().equalsString("prefetchNextStem"))
                .flatMap(m -> m.code().orElseThrow().elementList().stream()).filter(InvokeInstruction.class::isInstance)
                .map(InvokeInstruction.class::cast).map(call -> call.name().stringValue()).toList();
            assert boundaryCalls.containsAll(List.of("boundaryPairAllowed", "carried", "hasNeeded", "lineSite",
                "siteValid", "reachable", "queuePlacement")) : "Boundary placements retain completion budgets and site safety";
        }
        System.out.println("Auto Wither geometry and capacity checks passed.");
    }
}
