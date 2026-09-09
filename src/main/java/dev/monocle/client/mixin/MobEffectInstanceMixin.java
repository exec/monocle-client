/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.player.PotionSaver;
import dev.monocle.client.utils.Utils;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MobEffectInstance.class)
public abstract class MobEffectInstanceMixin {
    @Inject(method = "tickDownDuration", at = @At("HEAD"), cancellable = true)
    private void tick(CallbackInfo ci) {
        if (!Utils.canUpdate()) return;

        if (Modules.get().get(PotionSaver.class).shouldFreeze(((MobEffectInstance) (Object) this).getEffect().value())) {
            ci.cancel();
        }
    }
}
