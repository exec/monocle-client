/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.misc.AntiPacketKick;
import net.minecraft.network.CompressionDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CompressionDecoder.class)
public abstract class CompressionDecoderMixin {
    @ModifyExpressionValue(method = "decode", at = @At(value = "CONSTANT", args = "intValue=8388608"))
    private int monocle$maximizeUncompressedPacketLimit(int original) {
        return Modules.get().isActive(AntiPacketKick.class) ? Integer.MAX_VALUE : original;
    }
}
