/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.movement.speed.modes;

import dev.monocle.client.events.entity.player.PlayerMoveEvent;
import dev.monocle.client.mixininterface.IVec3;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.movement.Anchor;
import dev.monocle.client.systems.modules.movement.speed.SpeedMode;
import dev.monocle.client.systems.modules.movement.speed.SpeedModes;
import dev.monocle.client.utils.player.PlayerUtils;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

public class Vanilla extends SpeedMode {
    public Vanilla() {
        super(SpeedModes.Vanilla);
    }

    @Override
    public void onMove(PlayerMoveEvent event) {
        Vec3 vel = PlayerUtils.getHorizontalVelocity(settings.vanillaSpeed.get());
        double velX = vel.x();
        double velZ = vel.z();

        if (mc.player.hasEffect(MobEffects.SPEED)) {
            double value = (mc.player.getEffect(MobEffects.SPEED).getAmplifier() + 1) * 0.205;
            velX += velX * value;
            velZ += velZ * value;
        }

        Anchor anchor = Modules.get().get(Anchor.class);
        if (anchor.isActive() && anchor.controlMovement) {
            velX = anchor.deltaX;
            velZ = anchor.deltaZ;
        }

        ((IVec3) event.movement).monocle$set(velX, event.movement.y, velZ);
    }
}
