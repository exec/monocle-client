/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.movement.Jesus;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import static dev.monocle.client.MonocleClient.mc;

@Mixin(PowderSnowBlock.class)
public abstract class PowderSnowBlockMixin {
    @ModifyReturnValue(method = "canEntityWalkOnPowderSnow", at = @At("RETURN"))
    private static boolean onCanWalkOnPowderSnow(boolean original, Entity entity) {
        if (entity == mc.player && Modules.get().get(Jesus.class).canWalkOnPowderSnow()) return true;
        return original;
    }
}
