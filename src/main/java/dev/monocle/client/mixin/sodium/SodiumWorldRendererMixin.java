/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin.sodium;

import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.render.NoRender;
import dev.monocle.client.systems.modules.render.Xray;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(SodiumWorldRenderer.class)
public abstract class SodiumWorldRendererMixin {
    @Unique
    private static final FogParameters DISABLED_FOG = new FogParameters(0, 0, 0, 0, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);

    @ModifyVariable(method = "setupTerrain", at = @At("HEAD"), argsOnly = true, name = "fogParameters")
    private FogParameters modifyFogParameters(FogParameters fogParameters) {
        if (Modules.get() == null) return fogParameters;

        if (Modules.get().get(NoRender.class).noFog()) return DISABLED_FOG;

        return fogParameters;
    }

    @ModifyVariable(method = "setupTerrain", at = @At("HEAD"), argsOnly = true, name = "useOcclusionCulling")
    private boolean modifyUseOcclusionCulling(boolean useOcclusionCulling) {
        return useOcclusionCulling && !Modules.get().isActive(Xray.class);
    }
}
