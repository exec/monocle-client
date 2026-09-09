/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.utils.player;

import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.game.GameLeftEvent;
import dev.monocle.client.events.game.OpenScreenEvent;
import dev.monocle.client.events.world.BlockActivateEvent;
import dev.monocle.client.utils.PreInit;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EnderChestBlock;

import java.util.Arrays;

import static dev.monocle.client.MonocleClient.mc;

public class EChestMemory {
    public static final NonNullList<ItemStack> ITEMS = NonNullList.create();
    private static int echestOpenedState;
    private static boolean isKnown = false;

    private EChestMemory() {
    }

    @PreInit
    public static void init() {
        MonocleClient.EVENT_BUS.subscribe(EChestMemory.class);
    }

    @EventHandler
    private static void onBlockActivate(BlockActivateEvent event) {
        if (event.blockState.getBlock() instanceof EnderChestBlock && echestOpenedState == 0) echestOpenedState = 1;
    }

    @EventHandler
    private static void onOpenScreenEvent(OpenScreenEvent event) {
        if (echestOpenedState == 1 && event.screen instanceof ContainerScreen) {
            echestOpenedState = 2;
            return;
        }
        if (echestOpenedState == 0) return;

        if (!(mc.gui.screen() instanceof ContainerScreen)) return;
        ChestMenu container = ((ContainerScreen) mc.gui.screen()).getMenu();
        if (container == null) return;
        remember(container.getContainer());
        echestOpenedState = 0;
    }

    @EventHandler
    private static void onLeaveEvent(GameLeftEvent event) {
        clear();
    }

    public static void remember(Container inventory) {
        ITEMS.clear();
        for (int i = 0; i < inventory.getContainerSize(); i++) ITEMS.add(inventory.getItem(i).copy());
        isKnown = true;
    }

    public static void copyTo(ItemStack[] items) {
        Arrays.fill(items, ItemStack.EMPTY);
        for (int i = 0; i < Math.min(items.length, ITEMS.size()); i++) items[i] = ITEMS.get(i).copy();
    }

    public static void clear() {
        ITEMS.clear();
        isKnown = false;
        echestOpenedState = 0;
    }

    public static boolean isKnown() {
        return isKnown;
    }

    public static boolean isKnown(int slots) {
        return isKnown && ITEMS.size() >= slots;
    }
}
