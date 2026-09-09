/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixininterface;

import net.minecraft.network.chat.Component;

public interface IChatHud {
    void monocle$add(Component message, int id);
}
