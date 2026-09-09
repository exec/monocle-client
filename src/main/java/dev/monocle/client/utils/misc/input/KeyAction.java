/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.utils.misc.input;

import com.mojang.blaze3d.platform.InputConstants;

public enum KeyAction {
    Press,
    Repeat,
    Release;

    public static KeyAction get(int action) {
        return switch (action) {
            case InputConstants.PRESS -> Press;
            case InputConstants.RELEASE -> Release;
            default -> Repeat;
        };
    }
}
