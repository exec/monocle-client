/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.utils.entity.fakeplayer;

import com.mojang.authlib.GameProfile;
import dev.monocle.client.mixin.AbstractClientPlayerAccessor;
import dev.monocle.client.mixin.EntityAccessor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

import static dev.monocle.client.MonocleClient.mc;

public class FakePlayerEntity extends RemotePlayer {
    /**
     * Disables entity push with this fake player
     */
    public boolean doNotPush;
    /**
     * Stops rendering the fake player when you are inside it
     */
    public boolean hideWhenInsideCamera;
    /**
     * Prevents you from interacting with the fake player; will also prevent TargetUtils selecting it as a target
     */
    public boolean noHit;

    public FakePlayerEntity(Player player, String name, float health, boolean copyInv) {
        super(mc.level, new GameProfile(UUID.randomUUID(), name));

        copyPosition(player);

        yRotO = getYRot();
        xRotO = getXRot();
        yHeadRot = player.yHeadRot;
        yHeadRotO = yHeadRot;
        yBodyRot = player.yBodyRot;
        yBodyRotO = yBodyRot;

        getAttributes().assignAllValues(player.getAttributes());
        setPose(player.getPose());

        if (health <= 20) {
            setHealth(health);
        } else {
            setHealth(health);
            setAbsorptionAmount(health - 20);
        }

        if (copyInv) getInventory().replaceWith(player.getInventory());
    }

    @Override
    public int getId() {
        return ((EntityAccessor) this).monocle$getId();
    }

    public void spawn() {
        unsetRemoved();
        mc.level.addEntity(this);
    }

    public void despawn() {
        mc.level.removeEntity(getId(), RemovalReason.DISCARDED);
        setRemoved(RemovalReason.DISCARDED);
    }

    @Nullable
    @Override
    protected PlayerInfo getPlayerInfo() {
        PlayerInfo entry = super.getPlayerInfo();

        if (entry == null) {
            ((AbstractClientPlayerAccessor) this).monocle$setPlayerInfo(mc.getConnection().getPlayerInfo(mc.player.getUUID()));
        }

        return entry;
    }
}
