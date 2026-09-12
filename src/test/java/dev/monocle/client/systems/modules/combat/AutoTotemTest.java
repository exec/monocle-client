package dev.monocle.client.systems.modules.combat;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import dev.monocle.client.systems.modules.player.AutoReplenish;
import java.util.List;

public final class AutoTotemTest {
    public static void main(String[] args) throws Exception {
        boolean enabled = false;
        assert enabled = true;
        if (!enabled) throw new IllegalStateException("Assertions required");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(DataComponentInitializers.PendingComponents::apply);
        var inventory = new SimpleContainer(41);
        inventory.setItem(0, new ItemStack(Items.TOTEM_OF_UNDYING));
        inventory.setItem(35, new ItemStack(Items.TOTEM_OF_UNDYING));
        inventory.setItem(40, new ItemStack(Items.TOTEM_OF_UNDYING));
        assert AutoTotem.carriedTotems(inventory, inventory.getItem(40)) == 3 : "Offhand counted once";
        var storage = new SimpleContainer(new ItemStack(Items.TOTEM_OF_UNDYING));
        var menu = new Menu();
        menu.add(new Slot(storage, 0, 0, 0));
        menu.add(new Slot(inventory, 35, 0, 0));
        var cursor = new ItemStack(Items.NETHERRACK, 64);
        menu.setCarried(cursor);
        assert AutoTotem.sourceSlot(menu, inventory, new ItemStack(Items.DIAMOND, 64), null) == 1 : "Map player slot through the current menu; never steal container totems";
        assert ItemStack.matches(cursor, menu.getCarried());
        menu.slots.set(1, new Slot(inventory, 35, 0, 0) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        });
        assert AutoTotem.sourceSlot(menu, inventory, new ItemStack(Items.DIAMOND), null) == -1 : "Displaced offhand must fit";
        assert AutoTotem.sourceSlot(menu, inventory, ItemStack.EMPTY, null) == 1;
        var tick = calls(AutoTotem.class, "onTick");
        assert tick.containsAll(List.of("sourceSlot", "quickSwap", "fromId", "toId", "releaseUsingItem"));
        assert !tick.contains("move") && !tick.contains("drop") : "Safety swap must not use cursor-based transfers";
        assert calls(AutoTotem.class, "onReceivePacket").containsAll(List.of("execute", "activationRevision"));
        assert calls(AutoReplenish.class, "onTick").containsAll(List.of("isBusy", "needsInventory", "controlsChest", "fillItems"));
        assert calls(AutoReplenish.class, "findItem").contains("isPinnedSlot");
        System.out.println("Auto Totem checks passed: carried reserves, menu slot mapping, displaced-item safety, cursor preservation policy and module handoff guards.");
    }
    private static final class Menu extends AbstractContainerMenu {
        Menu() { super(null, 1); }
        void add(Slot slot) { addSlot(slot); }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }
    private static List<String> calls(Class<?> type, String name) throws Exception {
        try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            var model = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            return model.methods().stream().filter(m -> m.methodName().equalsString(name)).findFirst().orElseThrow()
                .code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(i -> i.name().stringValue()).toList();
        }
    }
}
