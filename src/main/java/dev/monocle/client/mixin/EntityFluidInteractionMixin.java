/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.movement.Velocity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import static dev.monocle.client.MonocleClient.mc;

@Mixin(EntityFluidInteraction.class)
public abstract class EntityFluidInteractionMixin {
    @ModifyExpressionValue(
        method = "update",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;getFlow(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;")
    )
    private Vec3 modifyFluidFlow(Vec3 flow, final Entity entity, final boolean ignoreCurrent) {
        if (entity != mc.player) return flow;

        Velocity velocity = Modules.get().get(Velocity.class);
        if (velocity.isActive() && velocity.liquids.get()) {
            double h = velocity.getHorizontal(velocity.liquidsHorizontal);
            double v = velocity.getVertical(velocity.liquidsVertical);
            flow = flow.multiply(h, v, h);
        }

        return flow;
    }
}
