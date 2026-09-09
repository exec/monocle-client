/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.player;

import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.combat.AutoTotem;
import dev.monocle.client.systems.modules.combat.Offhand;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class AutoReplenish extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> minCount = sgGeneral.add(new IntSetting.Builder()
        .name("min-count")
        .description("Replenish a slot when it reaches this item count.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 63)
        .build()
    );

    private final Setting<Integer> tickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("How long in ticks to wait between replenishing your hotbar.")
        .defaultValue(1)
        .min(0)
        .build()
    );

    private final Setting<Boolean> offhand = sgGeneral.add(new BoolSetting.Builder()
        .name("offhand")
        .description("Whether or not to replenish items in your offhand.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> unstackable = sgGeneral.add(new BoolSetting.Builder()
        .name("unstackable")
        .description("Replenish unstackable items.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sameEnchants = sgGeneral.add(new BoolSetting.Builder()
        .name("same-enchants")
        .description("Only replace unstackables with items that have the same enchants.")
        .defaultValue(true)
        .visible(unstackable::get)
        .build()
    );

    private final Setting<Boolean> searchHotbar = sgGeneral.add(new BoolSetting.Builder()
        .name("search-hotbar")
        .description("Combine stacks in your hotbar/offhand as a last resort.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<Item>> excludedItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("excluded-items")
        .description("Items that won't be replenished.")
        .build()
    );

    /**
     * Represents the items the player had last tick. Indices 0-8 represent the
     * hotbar from left to right, index 9 represents the player's offhand
     */
    private final ItemStack[] items = new ItemStack[10];
    private boolean prevHadOpenScreen;
    private int tickDelayLeft;

    public AutoReplenish() {
        super(Categories.Player, "auto-replenish", "Automatically refills items in your hotbar, main hand, or offhand.");
    }

    @Override
    public void onActivate() {
        for (int i = 0; i < items.length; i++) items[i] = ItemStack.EMPTY;
        if (mc.player != null) fillItems();
        tickDelayLeft = tickDelay.get();
        prevHadOpenScreen = mc.gui.screen() != null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (mc.gui.screen() == null && prevHadOpenScreen) {
            fillItems();
        }

        prevHadOpenScreen = mc.gui.screen() != null;
        if (!(mc.player.containerMenu instanceof InventoryMenu)
            || mc.gui.screen() != null
            || !mc.player.containerMenu.getCarried().isEmpty()
            || mc.player.isUsingItem()
            || Modules.get().get(AutoEat.class).eating
            || Modules.get().get(AutoGap.class).isEating()
            || Modules.get().isActive(AutoMend.class)) return;

        if (tickDelayLeft > 0) {
            tickDelayLeft--;
            return;
        }

        // Hotbar
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (checkSlot(i, stack)) {
                tickDelayLeft = tickDelay.get();
                return;
            }
        }

        // Offhand
        if (offhand.get() && canUseOffhand()) {
            ItemStack stack = mc.player.getOffhandItem();
            checkSlot(9, stack);
        }

        tickDelayLeft = tickDelay.get();
    }

    private boolean checkSlot(int slot, ItemStack stack) {
        ItemStack prevStack = items[slot];
        items[slot] = stack.copy();

        if (slot == 9) slot = SlotUtils.OFFHAND;

        if (excludedItems.get().contains(stack.getItem())) return false;
        if (excludedItems.get().contains(prevStack.getItem())) return false;

        int fromSlot = -1;

        // If there are still items left in the stack, but it just crossed the threshold
        if (stack.isStackable() && !stack.isEmpty() && stack.getCount() <= minCount.get()) {
            fromSlot = findItem(stack, slot, minCount.get() - stack.getCount() + 1);
        }

        // If the stack just went from above the threshold to empty in a single tick
        // this can happen if the threshold is set low enough while using modules that
        // place many blocks per tick, like surround or holefiller
        if (prevStack.isStackable() && stack.isEmpty() && !prevStack.isEmpty()) {
            fromSlot = findItem(prevStack, slot, minCount.get() + 1);
        }

        // Unstackable items
        if (unstackable.get() && !prevStack.isStackable() && stack.isEmpty() && !prevStack.isEmpty()) {
            fromSlot = findItem(prevStack, slot, 1);
        }

        // eliminate occasional loops when moving items from hotbar to itself
        if (fromSlot == -1 || fromSlot == mc.player.getInventory().getSelectedSlot()) return false;
        if (fromSlot < 9 && fromSlot < slot && slot != mc.player.getInventory().getSelectedSlot() && slot != SlotUtils.OFFHAND)
            return false;

        InvUtils.move().from(fromSlot).to(slot);
        return true;
    }

    private int findItem(ItemStack lookForStack, int excludedSlot, int goodEnoughCount) {
        int slot = -1;
        int count = 0;

        for (int i = SlotUtils.MAIN_END; i >= (searchHotbar.get() ? SlotUtils.HOTBAR_START : SlotUtils.MAIN_START); i--) {
            if (i == excludedSlot) continue;

            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() != lookForStack.getItem()) continue;
            if (lookForStack.isStackable() && !ItemStack.isSameItemSameComponents(lookForStack, stack)) continue;
            if (!lookForStack.isStackable() && sameEnchants.get() && !stack.getEnchantments().equals(lookForStack.getEnchantments())) continue;

            if (stack.getCount() > count) {
                slot = i;
                count = stack.getCount();

                if (count >= goodEnoughCount) break;
            }
        }

        if (searchHotbar.get() && excludedSlot != SlotUtils.OFFHAND && canUseOffhand()) {
            ItemStack stack = mc.player.getOffhandItem();
            boolean matches = stack.getItem() == lookForStack.getItem()
                && (lookForStack.isStackable()
                    ? ItemStack.isSameItemSameComponents(lookForStack, stack)
                    : !sameEnchants.get() || stack.getEnchantments().equals(lookForStack.getEnchantments()));
            if (matches && stack.getCount() > count) slot = SlotUtils.OFFHAND;
        }

        return slot;
    }

    private boolean canUseOffhand() {
        return !Modules.get().get(AutoTotem.class).isLocked()
            && !Modules.get().isActive(Offhand.class);
    }

    private void fillItems() {
        for (int i = 0; i < 9; i++) {
            items[i] = mc.player.getInventory().getItem(i).copy();
        }

        items[9] = mc.player.getOffhandItem().copy();
    }
}
