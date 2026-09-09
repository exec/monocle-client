/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.events.entity.player;

import dev.monocle.client.events.Cancellable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

public class InteractBlockEvent extends Cancellable {
    private static final InteractBlockEvent INSTANCE = new InteractBlockEvent();

    public InteractionHand hand;
    public BlockHitResult result;

    public static InteractBlockEvent get(InteractionHand hand, BlockHitResult result) {
        INSTANCE.setCancelled(false);
        INSTANCE.hand = hand;
        INSTANCE.result = result;
        return INSTANCE;
    }
}
