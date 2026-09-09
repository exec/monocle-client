/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.render.NoRender;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.SpawnerRenderer;
import net.minecraft.client.renderer.blockentity.state.SpawnerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpawnerRenderer.class)
public abstract class SpawnerRendererMixin implements BlockEntityRenderer<SpawnerBlockEntity, SpawnerRenderState> {
    @Inject(method = "submitEntityInSpawner", at = @At("HEAD"), cancellable = true)
    private static void onRenderDisplayEntity(CallbackInfo ci) {
        if (Modules.get().get(NoRender.class).noMobInSpawner()) ci.cancel();
    }
}
