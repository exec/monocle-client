package dev.monocle.client.systems.modules;

import dev.monocle.client.systems.modules.player.AutoEat;
import dev.monocle.client.systems.modules.player.AutoGap;
import dev.monocle.client.systems.modules.combat.KillAura;
import dev.monocle.client.systems.modules.combat.AutoArmor;
import dev.monocle.client.systems.modules.combat.Surround;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.util.List;

/** ./gradlew moduleHandoffCheck: checks the compiled runtime paths without starting a client. */
public final class ModuleHandoffTest {
    public static void main(String[] args) throws Exception {
        boolean enabled = false;
        assert enabled = true;
        if (!enabled) throw new IllegalStateException("Assertions required");
        assert method(Module.class, "toggle").elementList().stream().anyMatch(e -> e instanceof FieldInstruction f && f.name().equalsString("activationRevision"));
        for (var type : List.of(AutoEat.class, AutoGap.class)) {
            assert calls(type, "stopEating").containsAll(List.of("getSelectedSlot", "isSameItemSameComponents"));
            assert calls(type, "startEating").contains("activationRevision");
            try (var stream = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                var compiled = ClassFile.of().parse(stream.readAllBytes());
                assert compiled.methods().stream().filter(m -> m.methodName().stringValue().startsWith("lambda$stopEating"))
                    .anyMatch(m -> m.code().orElseThrow().elementList().stream().anyMatch(e -> e instanceof InvokeInstruction call && call.name().equalsString("activationRevision")))
                    : "Resume only modules whose toggle revision is still owned";
            }
        }
        assert calls(AutoArmor.class, "onPreTick").contains("reservesSlot");
        assert calls(KillAura.class, "attack").containsAll(List.of("isActive", "shouldPause", "acceptableWeapon"));
        assert calls(KillAura.class, "onTick").contains("activationRevision");
        assert calls(Surround.class, "onDeactivate").contains("activationRevision");
        assert calls(AutoGap.class, "onTick").containsAll(List.of("getCarried", "getSelectedSlot"));
        System.out.println("Module handoff checks passed: revision-owned restoration, food slot identity, mending reserves and deferred combat guards.");
    }

    private static java.lang.classfile.CodeModel method(Class<?> type, String name) throws Exception {
        try (var stream = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return ClassFile.of().parse(stream.readAllBytes()).methods().stream().filter(m -> m.methodName().equalsString(name)).findFirst().orElseThrow().code().orElseThrow();
        }
    }
    private static List<String> calls(Class<?> type, String method) throws Exception {
        return method(type, method).elementList().stream().filter(InvokeInstruction.class::isInstance)
            .map(InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
    }
}
