/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.misc.BetterChat;
import dev.monocle.client.systems.modules.render.NoRender;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$1", remap = false)
public abstract class ChatHudLineConsumerMixin {
    // Player Heads, also draw immediately when line is set
    @Inject(method = "accept", at = @At("HEAD"))
    private void setLine(GuiMessage.Line line, int lineIndex, float alpha, CallbackInfo ci) {
        Modules.get().get(BetterChat.class).line = line;
    }

    // No Message Signature Indicator
    @ModifyExpressionValue(method = "accept", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/chat/GuiMessage$Line;tag()Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;"))
    private GuiMessageTag onRender_modifyIndicator(GuiMessageTag indicator) {
        return Modules.get().get(NoRender.class).noMessageSignatureIndicator() ? null : indicator;
    }
}
