package dev.monocle.client.systems.modules.combat;

import dev.monocle.client.systems.modules.player.ChestSwap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.List;

/** ./gradlew autoArmorCheck; policy and compiled runtime guard checks without launching a client. */
public final class AutoArmorTest {
    public static void main(String[] args) throws Exception {
        boolean enabled = false;
        assert enabled = true;
        if (!enabled) throw new IllegalStateException("Assertions required");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(DataComponentInitializers.PendingComponents::apply);
        ItemStack boots = new ItemStack(Items.NETHERITE_BOOTS);
        boots.setDamageValue(boots.getMaxDamage() - 1);
        assert AutoArmor.worn(boots, 10);
        assert !AutoArmor.worn(ItemStack.EMPTY, 99);
        assert !AutoArmor.worn(new ItemStack(Items.OBSIDIAN), 99);
        assert !AutoArmor.worn(new ItemStack(Items.NETHERITE_BOOTS), 99);
        assert AutoArmor.durabilityPercent(boots) < 1;
        assert AutoArmor.shouldReplace(30, 20, 5, 80, true, 20) : "Worn armor can use a lower-rated usable spare";
        assert !AutoArmor.shouldReplace(30, -1, 5, -1, true, 20) : "Missing spare never becomes a swap";
        assert !AutoArmor.shouldReplace(30, 20, 80, 100, false, 20) : "Healthy armor keeps its protection preference";
        assert AutoArmor.shouldReplace(30, 31, 80, 90, false, 20);
        assert AutoArmor.shouldReplace(30, 30, 60, 80, false, 20);
        assert !AutoArmor.shouldReplace(30, 30, 61, 80, false, 20);
        assert !AutoArmor.shouldReplace(30, 30, 80, 60, false, 20) : "Do not swap straight back";
        assert !AutoArmor.shouldReplace(30, 30, 1, 100, false, 100) : "100 disables durability-only upgrades";
        assert AutoArmor.shouldReplace(-1, 0, 100, 100, false, 20) : "Fill empty slots even with zero-rated gear";
        assert !AutoArmor.shouldReplace(30, 20, 5, 4, true, 20);
        for (int current = 1; current <= 100; current++) {
            for (int spare = 1; spare <= 100; spare++) {
                if (AutoArmor.shouldReplace(30, 30, current, spare, false, 20))
                    assert !AutoArmor.shouldReplace(30, 30, spare, current, false, 20);
            }
        }
        var calculate = calls("AutoArmor$ArmorPiece.class", "calculate");
        assert calculate.containsAll(List.of("controlsChest", "isFallFlying", "onGround", "isActive", "worn"));
        var apply = calls("AutoArmor$ArmorPiece.class", "apply");
        assert apply.containsAll(List.of("shouldReplace", "moveToEmpty"));
        // Ensure removal stays opt-in, rather than accidentally reverting to legacy stripping behavior.
        try (var bytes = AutoArmor.class.getResourceAsStream("AutoArmor$ArmorPiece.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var method = compiled.methods().stream().filter(m -> m.methodName().equalsString("apply")).findFirst().orElseThrow();
            assert method.code().orElseThrow().elementList().stream().anyMatch(e -> e instanceof java.lang.classfile.instruction.FieldInstruction f
                && f.name().equalsString("removeWithoutSpare"));
            var calc = compiled.methods().stream().filter(m -> m.methodName().equalsString("calculate")).findFirst().orElseThrow();
            assert calc.code().orElseThrow().elementList().stream().anyMatch(e -> e instanceof java.lang.classfile.instruction.FieldInstruction f
                && f.name().equalsString("pinned"));
        }
        try (var bytes = ChestSwap.class.getResourceAsStream("ChestSwap.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            assert compiled.methods().stream().anyMatch(m -> m.methodName().equalsString("controlsChest"));
        }
        System.out.println("Auto Armor checks passed: durability reserves, replacement ranking, no equal-score oscillation, and flight/pin/removal guards.");
    }

    private static List<String> calls(String resource, String name) throws Exception {
        try (var bytes = AutoArmor.class.getResourceAsStream(resource)) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            return compiled.methods().stream().filter(m -> m.methodName().equalsString(name)).findFirst().orElseThrow()
                .code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
        }
    }
}
