/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixininterface;

import net.minecraft.core.BlockPos;

public interface IAABB {
    void monocle$expand(double v);

    void monocle$set(double x1, double y1, double z1, double x2, double y2, double z2);

    default void monocle$set(BlockPos pos) {
        monocle$set(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }
}
