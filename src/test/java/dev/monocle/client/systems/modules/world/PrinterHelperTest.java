package dev.monocle.client.systems.modules.world;

import dev.monocle.client.utils.world.PrinterFlight;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.HashSet;
import java.util.List;

/** Native geometry and production-boundary checks, without a renderer or optional mods. */
public final class PrinterHelperTest {
    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions) throw new IllegalStateException("Run with -ea.");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        AABB build = PrinterSafety.enclosing(List.of(new AABB(-8, 1, -8, -5, 4, -5), new AABB(5, 8, 5, 9, 12, 9)));
        assert build.equals(new AABB(-8, 1, -8, 9, 12, 9)) : "Reserve the full build, including empty space between subregions";
        assert PrinterSafety.depth(new Vec3(0, 6, 0), build) > PrinterSafety.depth(new Vec3(8, 6, 0), build) : "Deep work comes before exterior work";
        assert PrinterSafety.depth(new Vec3(10, 6, 0), build) == 0;

        Vec3 inside = new Vec3(.5, 4, .5), outside = new Vec3(12, 4, .5);
        List<Vec3> exit = List.of(inside, new Vec3(6.5, 4, .5), outside);
        var protectedExit = PrinterSafety.corridor(exit, .72, .7);
        AABB player = PrinterFlight.body(inside, .72, .7);
        for (int x = 0; x <= 12; x++) {
            assert !PrinterSafety.mayPlace(new BlockPos(x, 4, 0), protectedExit, player) : "Never seal the way out in front of the player";
            assert !PrinterSafety.mayPlace(new BlockPos(x, 3, 0), protectedExit, player) : "Reserve multi-block placement/support neighbors too";
        }
        assert PrinterSafety.mayPlace(new BlockPos(1, 4, 5), protectedExit, player) : "Work away from the escape corridor remains printable";
        assert !PrinterSafety.mayPlace(new BlockPos(6, 20, 0), protectedExit, player, true, false, -64)
            : "Falling blocks must not collapse onto a reserved escape corridor";
        assert !PrinterSafety.mayPlace(new BlockPos(1, 4, 5), protectedExit, player, false, true, -64)
            : "Do not assume fluid flow respects the static block-cell reservation";
        assert !PrinterSafety.mayPlace(new BlockPos(0, 4, 0), List.of(), player) : "Never place into the player, even after reaching the exterior";

        Vec3 later = new Vec3(7.5, 4, .5);
        var remainingExit = PrinterSafety.corridor(List.of(later, outside), .72, .7);
        assert PrinterSafety.mayPlace(new BlockPos(2, 4, 0), remainingExit, PrinterFlight.body(later, .72, .7))
            : "After moving outward, the corridor behind us can be filled";
        assert !PrinterSafety.mayPlace(new BlockPos(9, 4, 0), remainingExit, PrinterFlight.body(later, .72, .7));

        var scan = new PrinterSafety.Scan(List.of(new AABB(-2, -1, -3, 2, 2, 1)));
        var seen = new HashSet<BlockPos>();
        while (scan.hasNext()) assert seen.add(scan.next()) : "Every cell is visited once in one bounds box";
        assert seen.size() == 48 && seen.contains(new BlockPos(-2, -1, -3)) && seen.contains(new BlockPos(1, 1, 0));
        assert !seen.contains(new BlockPos(2, 2, 1)) : "AABB maxima are exclusive";
        var huge = new PrinterSafety.Scan(List.of(new AABB(0, 0, 0, 1_000_000, 300, 1_000_000)));
        for (int i = 0; i < 4096; i++) assert huge.next().equals(new BlockPos(i, 0, 0));
        assert huge.hasNext() : "Large schematics stream in bounded batches without allocating their full volume";
        assert !new PrinterSafety.Scan(List.of()).hasNext();

        ClassModel helper = compiled("dev.monocle.client.systems.modules.world.PrinterHelper");
        assert calls(helper, "reserveExit", "setPlacementFilter") : "The safe corridor must actually gate Sakura's placement candidates";
        assert calls(helper, "onDeactivate", "close") && calls(helper, "onDeactivate", "clearAutopilot") : "Stopping releases the exact owned controls";
        assert calls(helper, "pause", "suspend") : "Safety pause cancels our queued action before returning movement to the user";
        assert calls(helper, "scanTick", "desiredState") && calls(helper, "scanTick", "matches") : "Scan live transformed schematic states, not raw block items";
        assert calls(helper, "beginRestock", "requiredItems") : "Material requirements come from Litematica, including special counts";
        assert calls(helper, "onServerBlockAck", "onServerBlockAck") : "Server prediction acknowledgments reach container recovery";
        ClassModel packets = compiled("dev.monocle.client.mixin.ClientPacketListenerMixin");
        for (String name : List.of("onServerBlockAck", "onServerBlockUpdate", "onSupplyItemPickup", "onServerCorrection"))
            assert packets.methods().stream().anyMatch(method -> method.code().stream().flatMap(code -> code.elementList().stream())
                .anyMatch(element -> element instanceof InvokeInstruction call && call.owner().asInternalName().endsWith("/PrinterHelper")
                    && call.name().equalsString(name))) : "Native packet-tail event missing: " + name;
        System.out.println("Printer Helper checks passed: inside-out priority, full-build bounds, protected/closing escape corridor, bounded scans and production integration boundaries.");
    }

    private static boolean calls(ClassModel model, String methodName, String called) {
        return model.methods().stream().filter(method -> method.methodName().equalsString(methodName))
            .flatMap(method -> method.code().stream()).flatMap(code -> code.elementList().stream())
            .anyMatch(element -> element instanceof InvokeInstruction call && call.name().equalsString(called));
    }

    private static ClassModel compiled(String name) throws Exception {
        try (var input = ClassLoader.getSystemResourceAsStream(name.replace('.', '/') + ".class")) {
            if (input == null) throw new IllegalStateException("Missing compiled class " + name);
            return ClassFile.of().parse(input.readAllBytes());
        }
    }
}
