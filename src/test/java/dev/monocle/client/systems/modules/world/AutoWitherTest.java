package dev.monocle.client.systems.modules.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;

/** Small native check for the exact seven-block Wither shape and material budget. */
public final class AutoWitherTest {
    public static void main(String[] args) {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions) throw new IllegalStateException("Run with -ea.");

        BlockPos base = new BlockPos(10, 64, -20);
        List<BlockPos> parts = AutoWither.parts(base, Direction.EAST);
        assert parts.equals(List.of(
            new BlockPos(10, 64, -20), new BlockPos(10, 65, -20),
            new BlockPos(9, 65, -20), new BlockPos(11, 65, -20),
            new BlockPos(9, 66, -20), new BlockPos(11, 66, -20), new BlockPos(10, 66, -20)));
        assert AutoWither.nextSite(base, Direction.SOUTH, 24).equals(new BlockPos(10, 64, 4));
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
        assert !AutoWither.leaveStop(1, 2, false) && AutoWither.leaveStop(2, 2, false);
        assert AutoWither.leaveStop(1, 2, true) : "The total limit overrides per-stop grouping";
        assert AutoWither.pairable(0) && AutoWither.pairable(4);
        assert !AutoWither.pairable(5) && !AutoWither.pairable(6) : "Never pipeline the summoning skull";
        System.out.println("Auto Wither geometry and capacity checks passed.");
    }
}
