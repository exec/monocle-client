/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixininterface;

import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

@SuppressWarnings("UnusedReturnValue")
public interface IVec3 {
    Vec3 monocle$set(double x, double y, double z);

    default Vec3 monocle$set(Vec3i vec) {
        return monocle$set(vec.getX(), vec.getY(), vec.getZ());
    }

    default Vec3 monocle$set(Vector3d vec) {
        return monocle$set(vec.x, vec.y, vec.z);
    }

    default Vec3 monocle$set(Vec3 pos) {
        return monocle$set(pos.x, pos.y, pos.z);
    }

    Vec3 monocle$setXZ(double x, double z);

    Vec3 monocle$setY(double y);
}
