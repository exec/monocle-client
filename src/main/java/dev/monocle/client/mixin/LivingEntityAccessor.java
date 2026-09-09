/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.tags.TagKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Invoker("jumpInLiquid")
    void monocle$swimUpwards(TagKey<Fluid> fluid);

    @Accessor("jumping")
    boolean monocle$isJumping();

    @Accessor("noJumpDelay")
    int monocle$getJumpCooldown();

    @Accessor("noJumpDelay")
    void monocle$setJumpCooldown(int cooldown);
}
