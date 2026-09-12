/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.misc.BetterChat;
import dev.monocle.client.systems.modules.misc.CommunityChat;
import dev.monocle.client.systems.config.Config;
import dev.monocle.client.utils.player.ChatUtils;
import dev.monocle.client.pathing.BaritoneUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChatScreen.class, priority = 1001)
public abstract class ChatScreenMixin {
    @Shadow
    protected EditBox input;

    @Inject(method = "init", at = @At(value = "RETURN"))
    private void onInit(CallbackInfo ci) {
        if (Modules.get().get(BetterChat.class).isInfiniteChatBox()) input.setMaxLength(Integer.MAX_VALUE);
        if (Modules.get().get(CommunityChat.class).isIrcChat()) input.setHint(Component.literal("[IRC]"));
    }

    // After vanilla normalization/history and its separate slash-command branch; before game-chat transforms.
    @Inject(method = "handleChatInput", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;sendChat(Ljava/lang/String;)V"), cancellable = true)
    private void routeTypedChat(String message, boolean addToHistory, CallbackInfo ci) {
        CommunityChat chat = Modules.get().get(CommunityChat.class);
        if (!CommunityChat.routeToIrc(chat.isIrcChat(), message, Config.get().prefix.get(),
            BaritoneUtils.IS_AVAILABLE ? BaritoneUtils.getPrefix() : "")) return;
        ci.cancel();
        try { chat.say(message); }
        catch (IllegalArgumentException | IllegalStateException e) {
            ChatUtils.error("IRC message not sent: %s Use .chat game to switch destinations.", e.getMessage());
        }
    }
}
