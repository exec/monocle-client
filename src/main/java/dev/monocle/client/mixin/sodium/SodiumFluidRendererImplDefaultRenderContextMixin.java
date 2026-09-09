/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin.sodium;

import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.render.Xray;
import dev.monocle.client.systems.modules.world.Ambience;
import dev.monocle.client.utils.render.color.Color;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

@Mixin(targets = "net.caffeinemc.mods.sodium.fabric.render.FluidRendererImpl$DefaultRenderContext", remap = false)
public abstract class SodiumFluidRendererImplDefaultRenderContextMixin {
    @Unique
    private Ambience ambience;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        ambience = Modules.get().get(Ambience.class);
    }

    @Inject(method = "getColorProvider", at = @At("HEAD"), cancellable = true)
    private void onGetColorProvider(Fluid fluid, @Nullable BlockTintSource blockTintSource, CallbackInfoReturnable<ColorProvider<FluidState>> cir) {
        if (ambience.isActive() && ambience.customLavaColor.get() && fluid.defaultFluidState().is(FluidTags.LAVA)) {
            cir.setReturnValue(this::lavaColorProvider);
        }
    }

    @Unique
    private void lavaColorProvider(LevelSlice slice, BlockPos pos, BlockPos.MutableBlockPos scratchPos, FluidState state, ModelQuadView quad, int[] output, boolean smooth) {
        Color c = ambience.lavaColor.get();
        int alpha = Xray.getFluidAlpha(state, pos);
        Arrays.fill(output, Color.fromRGBA(c.r, c.g, c.b, alpha != -1 ? alpha : c.a));
    }
}
