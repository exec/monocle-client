/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.movement.Velocity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import static dev.monocle.client.MonocleClient.mc;

@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
    @WrapOperation(method = "handleEntityEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/FishingHook;pullEntity(Lnet/minecraft/world/entity/Entity;)V"))
    private void preventFishingRodPull(FishingHook instance, Entity entity, Operation<Void> original) {
        if (!instance.level().isClientSide() || entity != mc.player) original.call(instance, entity);

        Velocity velocity = Modules.get().get(Velocity.class);
        if (!velocity.isActive() || !velocity.fishing.get()) original.call(instance, entity);
    }
}
