/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.events.render;

import dev.monocle.client.events.Cancellable;
import net.minecraft.client.resources.model.cuboid.ItemTransform;

public class ApplyTransformationEvent extends Cancellable {
    private static final ApplyTransformationEvent INSTANCE = new ApplyTransformationEvent();

    public ItemTransform transformation;
    public boolean leftHanded;

    public static ApplyTransformationEvent get(ItemTransform transformation, boolean leftHanded) {
        INSTANCE.setCancelled(false);

        INSTANCE.transformation = transformation;
        INSTANCE.leftHanded = leftHanded;

        return INSTANCE;
    }
}
