/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixininterface;

import net.minecraft.world.item.ItemStack;

public interface IAbstractFurnaceMenu {
    boolean monocle$canSmelt(ItemStack itemStack);
}
