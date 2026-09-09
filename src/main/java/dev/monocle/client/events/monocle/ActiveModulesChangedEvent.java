/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.events.monocle;

public class ActiveModulesChangedEvent {
    private static final ActiveModulesChangedEvent INSTANCE = new ActiveModulesChangedEvent();

    public static ActiveModulesChangedEvent get() {
        return INSTANCE;
    }
}
