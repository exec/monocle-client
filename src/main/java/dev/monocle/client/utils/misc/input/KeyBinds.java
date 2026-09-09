/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.utils.misc.input;

import com.mojang.blaze3d.platform.InputConstants;
import dev.monocle.client.MonocleClient;
import net.minecraft.client.KeyMapping;

public class KeyBinds {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(MonocleClient.identifier("monocle-client"));

    public static KeyMapping OPEN_GUI = new KeyMapping("key.monocle-client.open-gui", InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, CATEGORY);
    public static KeyMapping OPEN_COMMANDS = new KeyMapping("key.monocle-client.open-commands", InputConstants.Type.KEYSYM, InputConstants.KEY_PERIOD, CATEGORY);

    private KeyBinds() {
    }

    public static KeyMapping[] apply(KeyMapping[] binds) {
        // Add key binding
        KeyMapping[] newBinds = new KeyMapping[binds.length + 2];

        System.arraycopy(binds, 0, newBinds, 0, binds.length);
        newBinds[binds.length] = OPEN_GUI;
        newBinds[binds.length + 1] = OPEN_COMMANDS;

        return newBinds;
    }
}
