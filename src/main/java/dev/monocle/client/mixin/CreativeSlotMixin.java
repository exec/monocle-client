/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.mixininterface.ISlot;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$SlotWrapper")
public abstract class CreativeSlotMixin implements ISlot {
    @Shadow
    @Final
    private Slot target;

    @Override
    public int monocle$getIndex() {
        return target.index;
    }

    @Override
    public int monocle$getSlot() {
        return target.getContainerSlot();
    }
}
