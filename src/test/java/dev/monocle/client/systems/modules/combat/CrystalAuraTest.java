package dev.monocle.client.systems.modules.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** ./gradlew crystalAuraCheck */
public final class CrystalAuraTest {
    public static void main(String[] args) {
        boolean enabled = false;
        assert enabled = true;
        if (!enabled) throw new IllegalStateException("Assertions required");

        assert CrystalAura.confirmationTicksForPing(0) == 4;
        assert CrystalAura.confirmationTicksForPing(100) == 5;
        assert CrystalAura.confirmationTicksForPing(2_000) == 12;
        assert CrystalAura.planScore(8, 2, 0, false) > CrystalAura.planScore(8, 6, 0, false);
        assert CrystalAura.planScore(6, 2, 1, true) > CrystalAura.planScore(12, 1, 0, false);

        BlockPos feet = new BlockPos(0, 64, 0);
        List<List<BlockPos>> east = CrystalAura.coverLayouts(feet, new Vec3(4.5, 65, 0.5));
        assert east.equals(List.of(List.of(feet.east()), List.of(feet.east(), feet.east().above())));

        List<List<BlockPos>> diagonal = CrystalAura.coverLayouts(feet, new Vec3(4.5, 65, 4.5));
        assert diagonal.contains(List.of(feet.east(), feet.south()));
        assert diagonal.stream().allMatch(layout -> layout.size() <= 2);

        System.out.println("Crystal Aura checks passed: adaptive confirmations, combat scoring and minimal cardinal/diagonal cover geometry.");
    }
}
