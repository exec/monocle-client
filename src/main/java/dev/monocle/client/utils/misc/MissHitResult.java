/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.utils.misc;

import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.NonNull;

public class MissHitResult extends HitResult {
    public static final MissHitResult INSTANCE = new MissHitResult();

    private MissHitResult() {
        super(new Vec3(0, 0, 0));
    }

    @Override
    public @NonNull Type getType() {
        return Type.MISS;
    }
}
