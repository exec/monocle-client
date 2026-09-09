/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.systems.config.Config;
import dev.monocle.client.utils.player.TitleScreenCredits;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static dev.monocle.client.MonocleClient.identifier;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    public TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void onExtractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        if (Config.get().titleScreenCredits.get()) TitleScreenCredits.render(graphics);
    }

    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/LogoRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IF)V"))
    private void renderMonocleLogo(LogoRenderer renderer, GuiGraphicsExtractor graphics, int screenWidth, float alpha) {
        int logoWidth = Math.min(LogoRenderer.LOGO_WIDTH, screenWidth - 40);
        int logoHeight = logoWidth * 386 / 1018;
        // Scale the entire texture; the shorter overload crops it to the destination size.
        graphics.blit(RenderPipelines.GUI_TEXTURED, identifier("textures/gui/title/monocle.png"), (screenWidth - logoWidth) / 2, 15, 0, 0, logoWidth, logoHeight, 1018, 386, 1018, 386, ARGB.white(alpha));
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (Config.get().titleScreenCredits.get() && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (TitleScreenCredits.onClicked(event.x(), event.y())) cir.setReturnValue(true);
        }
    }
}
