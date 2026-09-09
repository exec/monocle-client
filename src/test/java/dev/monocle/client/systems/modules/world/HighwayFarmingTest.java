package dev.monocle.client.systems.modules.world;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Called by HighwaySupplyTest after the native item bootstrap. */
final class HighwayFarmingTest {
    static void check() throws Exception {
        var state = Class.forName(HighwayBuilder.class.getName() + "$State");
        var farming = state.getDeclaredField("MineEnderChests");
        farming.setAccessible(true);
        var room = farming.get(null).getClass().getDeclaredMethod("obsidianRoom", Container.class);
        room.setAccessible(true);

        var inventory = new SimpleContainer(41);
        for (int slot = 0; slot < 36; slot++) inventory.setItem(slot, new ItemStack(Items.DIAMOND, 64));
        assert (int) room.invoke(null, inventory) == 0 : "Empty equipment/offhand slots cannot receive farmed obsidian";

        inventory.setItem(0, new ItemStack(Items.OBSIDIAN, 56));
        assert (int) room.invoke(null, inventory) == 8 : "A full inventory can accept one complete chest drop into its hotbar";
        inventory.getItem(0).setCount(57);
        assert (int) room.invoke(null, inventory) == 7 : "Seven free places do not fit an eight-obsidian drop";
        inventory.setItem(35, new ItemStack(Items.OBSIDIAN, 63));
        assert (int) room.invoke(null, inventory) == 8 : "Combine partial hotbar and main-inventory stacks";

        inventory.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Reserved blocks"));
        assert (int) room.invoke(null, inventory) == 1 : "Plain chest drops cannot merge into renamed obsidian";
        inventory.getItem(35).set(DataComponents.CUSTOM_NAME, Component.literal("Other blocks"));
        inventory.setItem(40, new ItemStack(Items.OBSIDIAN));
        assert (int) room.invoke(null, inventory) == 0 : "Offhand merge room is excluded along with component-mismatched stacks";

        inventory.setItem(7, ItemStack.EMPTY);
        assert (int) room.invoke(null, inventory) == 64 : "A cleared filler slot receives a full obsidian stack";
        assert (int) room.invoke(null, new SimpleContainer(41)) == 36 * 64 : "Only the 36 player storage slots count";
        var restricted = new SimpleContainer(1) {
            @Override public int getMaxStackSize() { return 16; }
        };
        assert (int) room.invoke(null, restricted) == 16 : "Use native container stack limits";
    }
}
