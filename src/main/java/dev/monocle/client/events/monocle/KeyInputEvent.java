/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.events.monocle;

import dev.monocle.client.events.Cancellable;
import dev.monocle.client.utils.misc.input.KeyAction;

public class KeyInputEvent extends Cancellable {
    private static final KeyInputEvent INSTANCE = new KeyInputEvent();

    public net.minecraft.client.input.KeyEvent input;
    public KeyAction action;

    public static KeyInputEvent get(net.minecraft.client.input.KeyEvent input, KeyAction action) {
        INSTANCE.setCancelled(false);
        INSTANCE.input = input;
        INSTANCE.action = action;
        return INSTANCE;
    }

    public int key() {
        return INSTANCE.input.key();
    }

    public int modifiers() {
        return INSTANCE.input.modifiers();
    }
}
