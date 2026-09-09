/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.render.NoRender;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BeaconRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BeaconBeamOwner;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.NonNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BeaconRenderer.class)
public abstract class BeaconRendererMixin<T extends BlockEntity & BeaconBeamOwner> implements BlockEntityRenderer<@NonNull T, BeaconRenderState> {
    @Inject(method = "submitBeaconBeam(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/resources/Identifier;FFIIIFF)V", at = @At("HEAD"), cancellable = true)
    private static void onRender(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, Identifier beamLocation, float scale, float animationTime, int beamStart, int height, int color, float solidBeamRadius, float beamGlowRadius, CallbackInfo ci) {
        if (Modules.get().get(NoRender.class).noBeaconBeams()) ci.cancel();
    }
}
