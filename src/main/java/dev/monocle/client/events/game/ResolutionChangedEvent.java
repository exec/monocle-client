/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.events.game;

public class ResolutionChangedEvent {
    private static final ResolutionChangedEvent INSTANCE = new ResolutionChangedEvent();

    public static ResolutionChangedEvent get() {
        return INSTANCE;
    }
}
