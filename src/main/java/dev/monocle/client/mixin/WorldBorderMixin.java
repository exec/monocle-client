/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.world.Collisions;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldBorder.class)
public abstract class WorldBorderMixin {
    @Inject(method = "isInsideCloseToBorder", at = @At("HEAD"), cancellable = true)
    private void canCollide(CallbackInfoReturnable<Boolean> cir) {
        if (Modules.get().get(Collisions.class).ignoreBorder()) cir.setReturnValue(false);
    }

    @Inject(method = "isWithinBounds(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void contains(CallbackInfoReturnable<Boolean> cir) {
        if (Modules.get().get(Collisions.class).ignoreBorder()) cir.setReturnValue(true);
    }
}
