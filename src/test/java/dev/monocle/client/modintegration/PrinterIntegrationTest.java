package dev.monocle.client.modintegration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

import java.lang.classfile.ClassFile;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.List;

/** Run without Litematica/Printer on the runtime classpath: loading the optional facade must remain safe. */
public final class PrinterIntegrationTest {
    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions) throw new IllegalStateException("Run with -ea");

        assert PrinterMixinPlugin.versionError("0.28.8", "0.29.6", "3.2.2") == null;
        for (String[] unsupported : new String[][] {
            {null, null, null}, {"0.28.8", "0.29.6", null}, {"0.28.8", null, "3.2.2"},
            {null, "0.29.6", "3.2.2"}, {"0.28.7", "0.29.6", "3.2.2"},
            {"0.28.8", "0.29.5", "3.2.2"}, {"0.28.8", "0.29.6", "3.2.1"},
            {"0.28.8", "0.29.6", "3.2.2-unverified-fork"}
        }) assert PrinterMixinPlugin.versionError(unsupported[0], unsupported[1], unsupported[2]) != null;

        for (Direction.Axis axis : Direction.Axis.values()) {
            AABB box = PrinterIntegration.clippedBox(new BlockPos(2, 2, 2), new BlockPos(-2, -2, -2), axis, -1, 1);
            for (int x = -3; x <= 3; x++) for (int y = -3; y <= 3; y++) for (int z = -3; z <= 3; z++) {
                BlockPos pos = new BlockPos(x, y, z);
                int layer = switch (axis) { case X -> x; case Y -> y; case Z -> z; };
                boolean expected = x >= -2 && x <= 2 && y >= -2 && y <= 2 && z >= -2 && z <= 2 && layer >= -1 && layer <= 1;
                assert PrinterIntegration.contains(List.of(box), pos) == expected : "Bounds include both block corners and clip only the selected layer axis";
            }
            assert PrinterIntegration.clippedBox(BlockPos.ZERO, new BlockPos(2, 2, 2), axis, 3, 4) == null;
            assert PrinterIntegration.clippedBox(BlockPos.ZERO, new BlockPos(2, 2, 2), axis, 2, 1) == null;
        }

        // No active session must not initialize optional classes, inspect a Minecraft singleton, or interfere with Sakura.
        assert PrinterIntegration.isIdle();
        assert PrinterIntegration.placementBounds().isEmpty();
        assert PrinterIntegration.fullPlacementBounds().isEmpty();
        assert !PrinterIntegration.isInScope(BlockPos.ZERO);
        assert !PrinterIntegration.ready(BlockPos.ZERO);
        assert PrinterIntegration.desiredState(BlockPos.ZERO) == null;
        assert PrinterIntegration.requiredItems(BlockPos.ZERO).isEmpty();
        assert !PrinterIntegration.matches(BlockPos.ZERO);
        assert !PrinterIntegration.canPrint(BlockPos.ZERO);
        assert !PrinterIntegration.ownsPrinter(new Object());
        assert PrinterIntegration.allowNewJobs(new Object());
        assert PrinterIntegration.beforeActionTick(new Object());
        PrinterIntegration.afterActionTick(new Object());
        assert PrinterIntegration.beginJob(new Object(), BlockPos.ZERO);
        assert !PrinterIntegration.setPlacementFilter(pos -> false);
        PrinterIntegration.setPrinting(true);
        PrinterIntegration.suspend();
        PrinterIntegration.close();

        try (var bytes = PrinterIntegrationTest.class.getResourceAsStream("/dev/monocle/client/modintegration/PrinterIntegration.class")) {
            var outer = ClassFile.of().parse(bytes.readAllBytes());
            for (var method : outer.methods()) for (var code : method.code().stream().toList()) for (var element : code.elementList()) {
                if (element instanceof InvokeInstruction call)
                    assert !optional(call.owner().asInternalName()) : "Optional calls belong in the lazy backend, not the module-facing facade";
            }
        }
        try (var bytes = PrinterIntegrationTest.class.getResourceAsStream("/dev/monocle/client/modintegration/PrinterIntegration$Backend.class")) {
            var backend = ClassFile.of().parse(bytes.readAllBytes());
            var probe = backend.methods().stream().filter(method -> method.methodName().equalsString("canPrint")).findFirst().orElseThrow();
            boolean nativeProbe = false;
            for (var element : probe.code().orElseThrow().elementList()) if (element instanceof InvokeInstruction call) {
                nativeProbe |= call.owner().asInternalName().endsWith("/guides/Guide") && call.name().equalsString("canExecute");
                assert !call.name().equalsString("execute") && !call.name().equalsString("addActions") && !call.name().equalsString("send")
                    : "Eligibility probing must never run or enqueue a placement action";
            }
            assert nativeProbe : "Use Sakura's native placement eligibility instead of inventing another printer";
        }
        for (String[] hook : new String[][] {
            {"PrinterMixin", "allowNewJobs"}, {"PrinterMixin", "allowPlacement"}, {"PrinterMixin", "beginJob"},
            {"ActionHandlerMixin", "beforeActionTick"}, {"ActionHandlerMixin", "afterActionTick"}
        }) {
            try (var bytes = PrinterIntegrationTest.class.getResourceAsStream("/dev/monocle/client/mixin/printer/" + hook[0] + ".class")) {
                var compiled = ClassFile.of().parse(bytes.readAllBytes());
                // A method reference is invokedynamic; its named target appears in the constant pool, not as an invoke instruction.
                assert java.util.stream.StreamSupport.stream(compiled.constantPool().spliterator(), false)
                    .anyMatch(entry -> entry instanceof java.lang.classfile.constantpool.Utf8Entry text && text.equalsString(hook[1]))
                    : "Missing compiled session/escape-path hook: " + hook[1];
            }
        }
        try (var bytes = PrinterIntegrationTest.class.getResourceAsStream("/dev/monocle/client/mixin/TextureAtlasMixin.class")) {
            var atlas = ClassFile.of().parse(bytes.readAllBytes());
            var guard = atlas.methods().stream().filter(method -> method.methodName().equalsString("monocle$waitForGlobalUniform")).findFirst().orElseThrow();
            boolean globalsChecked = false, readySkipsCancellation = false, cancels = false;
            for (var element : guard.code().orElseThrow().elementList()) {
                if (element instanceof BranchInstruction branch) {
                    assert globalsChecked && branch.opcode() == Opcode.IFNONNULL : "Only an unavailable native Globals buffer may defer atlas ticking";
                    readySkipsCancellation = true;
                }
                if (element instanceof InvokeInstruction call) {
                    if (call.owner().asInternalName().equals("com/mojang/blaze3d/systems/RenderSystem")) {
                        assert call.name().equalsString("getGlobalSettingsUniform") : "The startup guard must not replace GPU state or disable validation";
                        globalsChecked = true;
                    } else {
                        assert globalsChecked && readySkipsCancellation && call.name().equalsString("cancel") : "Cancel only on the null-uniform path";
                        cancels = true;
                    }
                }
            }
            assert cancels : "Defer the first atlas tick until native frame globals have been initialized";
        }
        System.out.println("Printer integration checks passed: optional/version isolation, clipped scope, new/queued-job escape guards and native first-frame atlas uniform readiness.");
    }

    private static boolean optional(String owner) {
        return owner.startsWith("fi/dy/masa/") || owner.startsWith("me/aleksilassila/");
    }
}
