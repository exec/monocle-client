package dev.monocle.client.systems.modules.player;

import dev.monocle.client.systems.modules.combat.KillAura;
import java.util.List;

/** ./gradlew autoToolCheck; hotbar policy and compiled integration checks. */
public final class AutoToolTest {
    public static void main(String[] args) throws Exception {
        boolean enabled = false;
        assert enabled = true;
        if (!enabled) throw new IllegalStateException("Assertions required");
        for (int selected = 0; selected < 9; selected++) {
            int destination = AutoTool.swordHotbarSlot(selected);
            assert destination >= 0 && destination < 9 && destination != selected : "Preserve the builder's selected tool";
        }
        for (int invalid : new int[] {-1, 9, 40}) {
            try { AutoTool.swordHotbarSlot(invalid); throw new AssertionError("Invalid selected slot accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        var sword = calls(AutoTool.class, "combatSwordSlot");
        assert sword.containsAll(List.of("isActive", "getCarried", "isUsingItem", "shouldStopUsing", "allows", "test", "getAttackDamage", "quickSwap", "fromId", "to", "isSameItemSameComponents"));
        assert !sword.contains("move") && !sword.contains("drop");
        assert calls(AutoTool.class, "onTick").contains("miningOwnedElsewhere");
        assert calls(AutoTool.class, "onStartBreakingBlock").contains("miningOwnedElsewhere");
        var equip = calls(KillAura.class, "equipWeapon");
        assert equip.contains("combatSwordSlot") && equip.contains("resetAttackStrengthTicker");
        var tick = calls(KillAura.class, "onTick");
        assert tick.indexOf("equipWeapon") < tick.indexOf("delayCheck") : "Switch weapons before testing attack cooldown";
        var stop = calls(KillAura.class, "stopAttacking");
        assert stop.containsAll(List.of("getSelectedSlot", "sameWeapon", "isUsingItem")) : "Restoration must respect handoffs";
        System.out.println("Auto Tool checks passed: builder-slot preservation, sword inventory guards, mining ownership, cooldown order and restoration guards.");
    }

    private static List<String> calls(Class<?> type, String method) throws Exception {
        try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            return compiled.methods().stream().filter(m -> m.methodName().equalsString(method)).findFirst().orElseThrow()
                .code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
        }
    }
}
