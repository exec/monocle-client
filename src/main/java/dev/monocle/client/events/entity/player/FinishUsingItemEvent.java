/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.events.entity.player;

import net.minecraft.world.item.ItemStack;

public class FinishUsingItemEvent {
    private static final FinishUsingItemEvent INSTANCE = new FinishUsingItemEvent();

    public ItemStack itemStack;

    public static FinishUsingItemEvent get(ItemStack itemStack) {
        INSTANCE.itemStack = itemStack;
        return INSTANCE;
    }
}
