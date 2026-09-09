/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.movement;

import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.settings.DoubleSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.phys.Vec3;

public class Spider extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("climb-speed")
        .description("The speed you go up blocks.")
        .defaultValue(0.2)
        .min(0.0)
        .build()
    );

    public Spider() {
        super(Categories.Movement, "spider", "Allows you to climb walls like a spider.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!mc.player.horizontalCollision) return;

        Vec3 velocity = mc.player.getDeltaMovement();
        if (velocity.y >= 0.2) return;

        mc.player.setDeltaMovement(velocity.x, speed.get(), velocity.z);
    }
}
