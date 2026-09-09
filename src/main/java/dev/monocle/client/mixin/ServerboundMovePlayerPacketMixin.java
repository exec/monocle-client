/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.mixininterface.IServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerboundMovePlayerPacket.class)
public abstract class ServerboundMovePlayerPacketMixin implements IServerboundMovePlayerPacket {
    @Unique
    private int tag;

    @Override
    public void monocle$setTag(int tag) {
        this.tag = tag;
    }

    @Override
    public int monocle$getTag() {
        return this.tag;
    }
}
