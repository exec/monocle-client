/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerboundMovePlayerPacket.class)
public interface ServerboundMovePlayerPacketAccessor {
    @Mutable
    @Accessor("yRot")
    void monocle$setYaw(float yaw);

    @Mutable
    @Accessor("xRot")
    void monocle$setPitch(float pitch);

    @Mutable
    @Accessor("y")
    void monocle$setY(double y);

    @Mutable
    @Accessor("onGround")
    void monocle$setOnGround(boolean onGround);
}
