/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.render.NoRender;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractSignRenderer.class)
public abstract class AbstractSignRendererMixin {
    @ModifyExpressionValue(method = "submitSignText", at = @At(value = "CONSTANT", args = {"intValue=4", "ordinal=1"}))
    private int loopTextLengthProxy(int i) {
        if (Modules.get().get(NoRender.class).noSignText()) return 0;
        return i;
    }
}
