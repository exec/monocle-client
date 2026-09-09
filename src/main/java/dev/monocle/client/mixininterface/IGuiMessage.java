/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixininterface;

import com.mojang.authlib.GameProfile;

public interface IGuiMessage {
    String monocle$getText();

    int monocle$getId();

    void monocle$setId(int id);

    GameProfile monocle$getSender();

    void monocle$setSender(GameProfile profile);
}
