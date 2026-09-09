package dev.monocle.client.systems.modules.combat;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.util.HashSet;
import java.util.List;

/** ./gradlew surroundCheck */
public final class SurroundTest {
    public static void main(String[] args) throws Exception {
        boolean enabled = false;
        assert enabled = true;
        if (!enabled) throw new IllegalStateException("Assertions required");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BlockPos origin = new BlockPos(-12, 64, 33);
        var feet = Surround.positions(origin, false);
        var doubled = Surround.positions(origin, true);
        assert feet.size() == 4 && doubled.size() == 8 && new HashSet<>(doubled).size() == 8;
        assert doubled.subList(0, 4).equals(feet) : "Ordered feet-first plan";
        assert !doubled.contains(origin) && !doubled.contains(origin.above());
        for (BlockPos pos : feet) assert pos.getY() == origin.getY() && pos.distManhattan(origin) == 1;
        for (BlockPos pos : feet) assert doubled.contains(pos.above());
        assert Surround.protective(Blocks.OBSIDIAN.defaultBlockState());
        assert Surround.protective(Blocks.BEDROCK.defaultBlockState());
        assert !Surround.protective(Blocks.NETHERRACK.defaultBlockState());
        assert !Surround.protective(Blocks.AIR.defaultBlockState());
        assert !Surround.protective(Blocks.LAVA.defaultBlockState());
        assert !Surround.acknowledged(20, 19) && Surround.acknowledged(20, 20) && Surround.acknowledged(20, 21);
        assert !Surround.acknowledged(Integer.MAX_VALUE, 100) : "Scheduled rotations are not sent placements";
        var tick = calls("onTick");
        assert tick.indexOf("resolved") < tick.indexOf("materials") : "Completion must scan the whole plan, independently of supplies/budget";
        assert tick.containsAll(List.of("positions", "resolved", "inventoryReady", "startPrediction", "materials", "place"));
        assert calls("resolved").containsAll(List.of("hasChunkAt", "containsKey", "protective")) : "Local predicted blocks cannot count as resolved";
        var materials = calls("materials");
        assert materials.containsAll(List.of("find", "findInHotbar", "quickSwap", "fromId")) && !materials.contains("drop");
        assert calls("onDeactivate").contains("clear");
        System.out.println("Surround checks passed: full-ring geometry, protective states, ACK boundaries, completion/prediction and inventory guards.");
    }

    private static List<String> calls(String method) throws Exception {
        try (var bytes = Surround.class.getResourceAsStream("Surround.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            return compiled.methods().stream().filter(m -> m.methodName().equalsString(method)).findFirst().orElseThrow()
                .code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
        }
    }
}
