/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.render.Fullbright;
import dev.monocle.client.systems.modules.render.Xray;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.util.profiling.Profiler;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Lightmap.class)
public abstract class LightmapMixin {
    @Shadow
    @Final
    private GpuTexture texture;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void render$fullbright(LightmapRenderState renderState, CallbackInfo ci) {
        if (Modules.get().get(Fullbright.class).getGamma() || Modules.get().isActive(Xray.class)) {
            var profile = Profiler.get();
            profile.push("lightmap");

            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(texture, new Vector4f(1));

            profile.pop();
            ci.cancel();
        }
    }
}
