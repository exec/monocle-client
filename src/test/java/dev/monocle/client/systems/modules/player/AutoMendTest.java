package dev.monocle.client.systems.modules.player;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.List;

/** ./gradlew autoMendCheck */
public final class AutoMendTest {
    public static void main(String[] args) throws Exception {
        boolean enabled = false;
        assert enabled = true;
        if (!enabled) throw new IllegalStateException("Assertions required");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(DataComponentInitializers.PendingComponents::apply);
        ItemStack pick = new ItemStack(Items.NETHERITE_PICKAXE);
        pick.setDamageValue(pick.getMaxDamage() / 2);
        ItemStack repaired = pick.copy();
        repaired.setDamageValue(1);
        assert AutoMend.belowTarget(pick, 95);
        assert !AutoMend.belowTarget(repaired, 95) && AutoMend.belowTarget(repaired, 100);
        assert AutoMend.durability(pick) < AutoMend.durability(repaired);
        assert AutoMend.sameRepairItem(repaired, pick) : "Repair damage changes must retain ownership";
        int damage = repaired.getDamageValue();
        AutoMend.sameRepairItem(repaired, pick);
        assert repaired.getDamageValue() == damage : "Identity checks must not mutate gear";
        repaired.set(DataComponents.CUSTOM_NAME, Component.literal("Another pick"));
        assert !AutoMend.sameRepairItem(repaired, pick) : "Different named gear is not ours";
        assert !AutoMend.sameRepairItem(new ItemStack(Items.TOTEM_OF_UNDYING), pick);
        assert !AutoMend.sameRepairItem(ItemStack.EMPTY, pick);
        assert !AutoMend.belowTarget(new ItemStack(Items.OBSIDIAN), 100);
        ItemStack elytra = new ItemStack(Items.ELYTRA);
        elytra.setDamageValue(elytra.getMaxDamage() - 1);
        assert AutoMend.durability(elytra) < AutoMend.durability(pick) : "Rank by fraction, not raw damage";
        try (var bytes = AutoMend.class.getResourceAsStream("AutoMend.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            for (String method : List.of("restore", "swapOffhand")) {
                var code = compiled.methods().stream().filter(m -> m.methodName().equalsString(method)).findFirst().orElseThrow().code().orElseThrow();
                var calls = code.elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                    .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
                assert calls.containsAll(method.equals("restore") ? List.of("inventoryReady", "safetyBusy", "ownsPair") : List.of("quickSwap", "fromId", "to"));
                assert !calls.contains("drop") && !calls.contains("move");
            }
        }
        System.out.println("Auto Mend checks passed: target boundaries, damage ranking, ownership identity and guarded native swaps.");
    }
}
