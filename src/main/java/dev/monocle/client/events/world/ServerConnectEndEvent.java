/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.events.world;

import java.net.InetSocketAddress;

public class ServerConnectEndEvent {
    private static final ServerConnectEndEvent INSTANCE = new ServerConnectEndEvent();
    public InetSocketAddress address;

    public static ServerConnectEndEvent get(InetSocketAddress address) {
        INSTANCE.address = address;
        return INSTANCE;
    }
}
