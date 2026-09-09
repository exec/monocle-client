/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.events.game;

import dev.monocle.client.events.Cancellable;

public class SendMessageEvent extends Cancellable {
    private static final SendMessageEvent INSTANCE = new SendMessageEvent();

    public String message;

    public static SendMessageEvent get(String message) {
        INSTANCE.setCancelled(false);
        INSTANCE.message = message;
        return INSTANCE;
    }
}


