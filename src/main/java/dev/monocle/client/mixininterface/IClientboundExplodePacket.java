/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixininterface;

public interface IClientboundExplodePacket {
    void monocle$setVelocityX(float velocity);

    void monocle$setVelocityY(float velocity);

    void monocle$setVelocityZ(float velocity);
}
