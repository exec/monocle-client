/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.events.entity.player;

import dev.monocle.client.events.Cancellable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;

public class InteractEntityEvent extends Cancellable {
    private static final InteractEntityEvent INSTANCE = new InteractEntityEvent();

    public Entity entity;
    public InteractionHand hand;

    public static InteractEntityEvent get(Entity entity, InteractionHand hand) {
        INSTANCE.setCancelled(false);
        INSTANCE.entity = entity;
        INSTANCE.hand = hand;
        return INSTANCE;
    }
}
