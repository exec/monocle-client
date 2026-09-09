/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.LastSeenMessagesTracker;
import net.minecraft.network.chat.SignedMessageChain;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.flag.FeatureFlagSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerAccessor {
    @Accessor("serverChunkRadius")
    int monocle$getServerChunkRadius();

    @Accessor("signedMessageEncoder")
    SignedMessageChain.Encoder monocle$getSignedMessageEncoder();

    @Accessor("lastSeenMessages")
    LastSeenMessagesTracker monocle$getLastSeenMessages();

    @Accessor("registryAccess")
    RegistryAccess.Frozen monocle$getRegistryAccess();

    @Accessor("enabledFeatures")
    FeatureFlagSet monocle$getEnabledFeatures();

    @Accessor("COMMAND_NODE_BUILDER")
    static ClientboundCommandsPacket.NodeBuilder<ClientSuggestionProvider> monocle$getCommandNodeFactory() {
        return null;
    }
}
