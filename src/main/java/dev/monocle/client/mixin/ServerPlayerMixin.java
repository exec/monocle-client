/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.movement.Anchor;
import dev.monocle.client.systems.modules.movement.Scaffold;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin extends LivingEntity {
    protected ServerPlayerMixin(EntityType<? extends LivingEntity> entityType, Level world) {
        super(entityType, world);
    }

    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
    public void dontJump(CallbackInfo ci) {
        if (!level().isClientSide()) return;

        Anchor module = Modules.get().get(Anchor.class);
        if (module.isActive() && module.cancelJump) ci.cancel();
        else if (Modules.get().get(Scaffold.class).towering()) ci.cancel();
    }
}
