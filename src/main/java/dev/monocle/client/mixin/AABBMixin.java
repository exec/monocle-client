/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import dev.monocle.client.mixininterface.IAABB;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(AABB.class)
public abstract class AABBMixin implements IAABB {
    @Shadow
    @Final
    @Mutable
    public double minX;
    @Shadow
    @Final
    @Mutable
    public double minY;
    @Shadow
    @Final
    @Mutable
    public double minZ;

    @Shadow
    @Final
    @Mutable
    public double maxX;
    @Shadow
    @Final
    @Mutable
    public double maxY;
    @Shadow
    @Final
    @Mutable
    public double maxZ;

    @Override
    public void monocle$expand(double v) {
        this.minX -= v;
        this.minY -= v;
        this.minZ -= v;
        this.maxX += v;
        this.maxY += v;
        this.maxZ += v;
    }

    @Override
    public void monocle$set(double x1, double y1, double z1, double x2, double y2, double z2) {
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }
}
