/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.events.entity.player;

public class BlockBreakingCooldownEvent {
    private static final BlockBreakingCooldownEvent INSTANCE = new BlockBreakingCooldownEvent();

    public int cooldown;

    public static BlockBreakingCooldownEvent get(int cooldown) {
        INSTANCE.cooldown = cooldown;
        return INSTANCE;
    }
}
