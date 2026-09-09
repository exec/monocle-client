/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixininterface;

public interface IGuiMessageVisible extends IGuiMessage {
    boolean monocle$isStartOfEntry();

    void monocle$setStartOfEntry(boolean start);
}
