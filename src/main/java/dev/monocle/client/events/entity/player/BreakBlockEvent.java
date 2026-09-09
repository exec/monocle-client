/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.events.entity.player;

import dev.monocle.client.events.Cancellable;
import net.minecraft.core.BlockPos;

public class BreakBlockEvent extends Cancellable {
    private static final BreakBlockEvent INSTANCE = new BreakBlockEvent();

    public BlockPos blockPos;

    public static BreakBlockEvent get(BlockPos blockPos) {
        INSTANCE.setCancelled(false);
        INSTANCE.blockPos = blockPos;
        return INSTANCE;
    }
}
