package dev.monocle.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void monocle$waitForGlobalUniform(CallbackInfo ci) {
        // A first texture tick can precede GameRenderer's initial Globals update during resource loading.
        // Defer animation until its native uniform exists; do not alter shader validation or GPU state.
        if (RenderSystem.getGlobalSettingsUniform() == null) ci.cancel();
    }
}
