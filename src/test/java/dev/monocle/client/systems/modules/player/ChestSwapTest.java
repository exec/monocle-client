package dev.monocle.client.systems.modules.player;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.List;
import dev.monocle.client.systems.modules.movement.elytrafly.ElytraFly;

/** ./gradlew chestSwapCheck */
public final class ChestSwapTest {
    public static void main(String[] args) throws Exception {
        boolean enabled = false;
        assert enabled = true;
        if (!enabled) throw new IllegalStateException("Assertions required");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(DataComponentInitializers.PendingComponents::apply);
        ItemStack wings = new ItemStack(Items.ELYTRA);
        ItemStack armor = new ItemStack(Items.NETHERITE_CHESTPLATE);
        assert ChestSwap.usable(wings, true) && !ChestSwap.usable(wings, false);
        assert ChestSwap.usable(armor, false) && !ChestSwap.usable(armor, true);
        assert !ChestSwap.usable(ItemStack.EMPTY, false);
        assert !ChestSwap.usable(new ItemStack(Items.NETHERITE_BOOTS), false);
        wings.setDamageValue(wings.getMaxDamage() - 1);
        assert !ChestSwap.usable(wings, true) : "Broken glider must not be selected";
        wings.setDamageValue(wings.getMaxDamage() - 2);
        assert ChestSwap.usable(wings, true);
        var loop = calls(ChestSwap.class, "ChestSwap$SwapListener.class", "onTick");
        assert loop.containsAll(List.of("isAlive", "hasAutopilotRequest", "onGround", "isFallFlying", "usable", "cancelRequest", "tryEquip", "warning"));
        assert !loop.contains("swap") : "Retries must be directional";
        assert calls(ChestSwap.class, "ChestSwap.class", "tryEquip").containsAll(List.of("getCarried", "isUsingItem", "hasEnchantments"));
        assert calls(ChestSwap.class, "ChestSwap.class", "equipChestplate").contains("reservesSlot");
        assert calls(ElytraFly.class, "ElytraFly.class", "onTick").containsAll(List.of("isFallFlying", "onGround", "requestEquip"));
        assert calls(ElytraFly.class, "ElytraFly.class", "onActivate").contains("cancelRequest");
        assert calls(ElytraFly.class, "ElytraFly.class", "onDeactivate").contains("requestEquip");
        System.out.println("Chest Swap checks passed: directional equipment, broken gliders, landing/retry guards, lifecycle cancellation and mending ownership.");
    }

    private static List<String> calls(Class<?> type, String resource, String method) throws Exception {
        try (var bytes = type.getResourceAsStream(resource)) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            return compiled.methods().stream().filter(m -> m.methodName().equalsString(method)).findFirst().orElseThrow()
                .code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
        }
    }
}
