/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.events.world;

import dev.monocle.client.events.Cancellable;
import net.minecraft.core.particles.ParticleOptions;

public class ParticleEvent extends Cancellable {
    private static final ParticleEvent INSTANCE = new ParticleEvent();

    public ParticleOptions particle;

    public static ParticleEvent get(ParticleOptions particle) {
        INSTANCE.setCancelled(false);
        INSTANCE.particle = particle;
        return INSTANCE;
    }
}
