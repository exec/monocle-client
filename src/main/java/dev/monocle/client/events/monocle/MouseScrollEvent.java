/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.events.monocle;

import dev.monocle.client.events.Cancellable;

public class MouseScrollEvent extends Cancellable {
    private static final MouseScrollEvent INSTANCE = new MouseScrollEvent();

    public double value;

    public static MouseScrollEvent get(double value) {
        INSTANCE.setCancelled(false);
        INSTANCE.value = value;
        return INSTANCE;
    }
}
