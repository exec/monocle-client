package dev.monocle.client.systems.modules.combat;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import java.util.List;

/** ./gradlew autoLogCheck; tests policy without disconnecting or launching Minecraft. */
public final class AutoLogTest {
    public static void main(String[] args) throws Exception {
        boolean enabled = false;
        assert enabled = true;
        if (!enabled) throw new IllegalStateException("Assertions required");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(net.minecraft.data.registries.VanillaRegistries.createLookup())
            .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        var totem = new ItemStack(Items.TOTEM_OF_UNDYING);
        var box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(totem.copy())));
        assert AutoLog.countTotems(List.of(totem, box, ItemStack.EMPTY), totem) == 2 : "Count inventory and offhand once; never count inaccessible shulker contents";
        assert AutoLog.countTotems(List.of(box), ItemStack.EMPTY) == 0;
        assert !AutoLog.gearLow(ItemStack.EMPTY, 100);
        assert !AutoLog.gearLow(totem, 100);
        var elytra = new ItemStack(Items.ELYTRA);
        assert !AutoLog.gearLow(elytra, 10);
        elytra.setDamageValue(elytra.getMaxDamage() - 1);
        assert AutoLog.gearLow(elytra, 10) && !AutoLog.gearLow(elytra, 0);
        var armor = new ItemStack(Items.DIAMOND_CHESTPLATE);
        armor.set(DataComponents.MAX_DAMAGE, 100);
        armor.setDamageValue(90);
        assert !AutoLog.gearLow(armor, 10) : "Reserve is strictly below the configured percentage";
        armor.setDamageValue(91);
        assert AutoLog.gearLow(armor, 10);
        assert !AutoLog.confirmed(1000, -1, 0);
        assert AutoLog.confirmed(100, 100, 0);
        assert !AutoLog.confirmed(119, 100, 1) && AutoLog.confirmed(120, 100, 1);
        assert !AutoLog.confirmed(120, 100, 1.01) && AutoLog.confirmed(121, 100, 1.01);
        assert AutoLog.warningDue(0, -200) && !AutoLog.warningDue(199, 0) && AutoLog.warningDue(200, 0);
        assert AutoLog.warningDue(0, 1000) : "A new world must not inherit the old warning cooldown";
        try (var bytes = AutoLog.class.getResourceAsStream("AutoLog.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var trigger = compiled.methods().stream().filter(m -> m.methodName().equalsString("trigger")).findFirst().orElseThrow();
            var instructions = trigger.code().orElseThrow().elementList();
            int warning = -1, disconnect = -1;
            for (int i = 0; i < instructions.size(); i++) {
                if (instructions.get(i) instanceof java.lang.classfile.instruction.InvokeInstruction call) {
                    if (call.name().equalsString("warning")) warning = i;
                    if (call.name().equalsString("handleDisconnect")) disconnect = i;
                }
            }
            assert warning >= 0 && disconnect > warning;
            assert instructions.subList(warning, disconnect).stream().anyMatch(java.lang.classfile.instruction.ReturnInstruction.class::isInstance)
                : "Alert-only handling must return before the disconnection path";
            var receive = compiled.methods().stream().filter(m -> m.methodName().equalsString("onReceivePacket")).findFirst().orElseThrow();
            assert receive.code().orElseThrow().elementList().stream().anyMatch(e -> e instanceof java.lang.classfile.instruction.InvokeInstruction call && call.name().equalsString("execute"))
                : "Packet-driven actions must be dispatched to the client thread";
        }
        System.out.println("Auto Log checks passed: carried totem counts, gear reserves, confirmation windows, warning throttling and client-thread/alert-only guards.");
    }
}
