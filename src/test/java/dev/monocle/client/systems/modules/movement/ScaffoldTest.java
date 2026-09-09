package dev.monocle.client.systems.modules.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Set;

/** ./gradlew scaffoldCheck; movement policy and compiled integration guards. */
public final class ScaffoldTest {
    public static void main(String[] args) throws Exception {
        boolean enabled = false;
        assert enabled = true;
        if (!enabled) throw new IllegalStateException("Assertions required");
        Vec3 feet = new Vec3(.5, 64, .5);
        Set<BlockPos> floor = Set.of(new BlockPos(0, 63, 0));
        assert Scaffold.safeFraction(feet, new Vec3(.2, 0, 0), floor::contains) == 1;
        double edge = Scaffold.safeFraction(feet, new Vec3(1, 0, 0), floor::contains);
        assert edge < .5 && edge >= .4 : "Stop before the next unsupported center cell";
        assert Scaffold.safeFraction(feet, new Vec3(1, 0, 0), p -> floor.contains(p) || p.equals(new BlockPos(1, 63, 0))) == 1;
        assert Scaffold.safeFraction(feet, new Vec3(.2, 0, .2), p -> false) == 0 : "Unknown/pending floor is not support";
        assert Scaffold.safeFraction(feet, new Vec3(3, 0, 0), p -> p.getX() != 1) < .2 : "Do not tunnel across a missing intermediate block";
        assert Scaffold.safeFraction(new Vec3(-.5, 64, -.5), new Vec3(-1, 0, 0), p -> p.equals(new BlockPos(-1, 63, -1))) < .6;
        assert Scaffold.safeFraction(feet, new Vec3(5, 0, 0), p -> true) == 0 : "Bound sweep work";
        assert Scaffold.safeFraction(feet, new Vec3(Double.NaN, 0, 0), p -> true) == 0;
        assert calls("onMove").containsAll(List.of("onGround", "anotherBuilder", "safeFraction", "monocle$setXZ"));
        assert calls("place").containsAll(List.of("inventoryReady", "containsKey", "quickSwap", "reservesSlot", "activationRevision"));
        assert !calls("place").contains("drop");
        assert calls("onServerBlockAck").contains("removeIf");
        assert calls("onServerBlockUpdate").containsAll(List.of("isActive", "containsKey", "isShapeFullBlock"));
        assert calls("towering").contains("isEmpty") : "Tower must wait for predictions";
        System.out.println("Scaffold checks passed: confirmed-footing sweeps, negative coordinates, bounded movement, inventory and prediction guards.");
    }
    private static List<String> calls(String method) throws Exception {
        try (var stream = Scaffold.class.getResourceAsStream("Scaffold.class")) {
            return java.lang.classfile.ClassFile.of().parse(stream.readAllBytes()).methods().stream()
                .filter(m -> m.methodName().equalsString(method)).findFirst().orElseThrow().code().orElseThrow().elementList().stream()
                .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
        }
    }
}
