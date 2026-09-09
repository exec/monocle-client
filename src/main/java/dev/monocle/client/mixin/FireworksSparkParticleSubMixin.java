/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.render.NoRender;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.FireworkParticles;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = {FireworkParticles.SparkParticle.class, FireworkParticles.OverlayParticle.class})
public abstract class FireworksSparkParticleSubMixin {
    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void buildExplosionGeometry(QuadParticleRenderState particleTypeRenderState, Camera camera, float partialTickTime, CallbackInfo ci) {
        if (Modules.get().get(NoRender.class).noFireworkExplosions()) ci.cancel();
    }
}
