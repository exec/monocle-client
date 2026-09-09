/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.events.entity.player;

/**
 * @see net.minecraft.client.entity.ClientAvatarState#updateBob(float)
 */
public class PlayerTickMovementEvent {
    private static final PlayerTickMovementEvent INSTANCE = new PlayerTickMovementEvent();

    public static PlayerTickMovementEvent get() {
        return INSTANCE;
    }
}
