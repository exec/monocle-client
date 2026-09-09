/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.mixininterface.IClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Optional;

@Mixin(ClientboundExplodePacket.class)
public abstract class ClientboundExplodePacketMixin implements IClientboundExplodePacket {
    @Shadow
    @Final
    @Mutable
    private Optional<Vec3> playerKnockback;

    @Override
    public void monocle$setVelocityX(float velocity) {
        if (playerKnockback.isPresent()) {
            Vec3 kb = playerKnockback.get();
            playerKnockback = Optional.of(new Vec3(velocity, kb.y, kb.z));
        } else {
            playerKnockback = Optional.of(new Vec3(velocity, 0, 0));
        }
    }

    @Override
    public void monocle$setVelocityY(float velocity) {
        if (playerKnockback.isPresent()) {
            Vec3 kb = playerKnockback.get();
            playerKnockback = Optional.of(new Vec3(kb.x, velocity, kb.z));
        } else {
            playerKnockback = Optional.of(new Vec3(0, velocity, 0));
        }
    }

    @Override
    public void monocle$setVelocityZ(float velocity) {
        if (playerKnockback.isPresent()) {
            Vec3 kb = playerKnockback.get();
            playerKnockback = Optional.of(new Vec3(kb.x, kb.y, velocity));
        } else {
            playerKnockback = Optional.of(new Vec3(0, 0, velocity));
        }
    }
}
