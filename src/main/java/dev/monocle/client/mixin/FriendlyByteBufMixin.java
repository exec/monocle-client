/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.misc.AntiPacketKick;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(FriendlyByteBuf.class)
public abstract class FriendlyByteBufMixin {
    @ModifyArg(method = "readNbt(Lio/netty/buffer/ByteBuf;)Lnet/minecraft/nbt/CompoundTag;", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;readNbt(Lio/netty/buffer/ByteBuf;Lnet/minecraft/nbt/NbtAccounter;)Lnet/minecraft/nbt/Tag;"))
    private static NbtAccounter xlPackets(NbtAccounter sizeTracker) {
        return Modules.get().isActive(AntiPacketKick.class) ? NbtAccounter.unlimitedHeap() : sizeTracker;
    }
}
