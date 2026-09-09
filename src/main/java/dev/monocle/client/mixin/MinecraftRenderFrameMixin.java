/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.renderer.MeshUniforms;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.misc.InventoryTweaks;
import dev.monocle.client.utils.render.postprocess.ChamsShader;
import dev.monocle.client.utils.render.postprocess.OutlineUniforms;
import dev.monocle.client.utils.render.postprocess.PostProcessShader;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static dev.monocle.client.MonocleClient.mc;

@Mixin(Minecraft.class)
public abstract class MinecraftRenderFrameMixin {
    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;endFrame()V", shift = At.Shift.AFTER))
    private void monocle$afterRenderFrame(boolean advanceGameTime, CallbackInfo ci) {
        MeshUniforms.flipFrame();
        PostProcessShader.flipFrame();
        ChamsShader.flipFrame();
        OutlineUniforms.flipFrame();

        Modules modules = Modules.get();
        if (modules == null || mc.player == null) return;

        InventoryTweaks inventoryTweaks = modules.get(InventoryTweaks.class);
        if (inventoryTweaks != null && inventoryTweaks.frameInput()) {
            ((MinecraftAccessor) mc).monocle$handleInputEvents();
        }
    }
}
