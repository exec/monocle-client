/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.misc;

import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.world.ServerConnectBeginEvent;
import dev.monocle.client.settings.BoolSetting;
import dev.monocle.client.settings.DoubleSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;

public class AutoReconnect extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Double> time = sgGeneral.add(new DoubleSetting.Builder()
        .name("delay")
        .description("The amount of seconds to wait before reconnecting to the server.")
        .defaultValue(3.5)
        .min(0)
        .decimalPlaces(1)
        .build()
    );

    public final Setting<Boolean> button = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-buttons")
        .description("Will hide the buttons related to Auto Reconnect.")
        .defaultValue(false)
        .build()
    );

    public Pair<ServerAddress, ServerData> lastServerConnection;

    public AutoReconnect() {
        super(Categories.Misc, "auto-reconnect", "Automatically reconnects when disconnected from a server.");
        MonocleClient.EVENT_BUS.subscribe(new StaticListener());
    }

    private class StaticListener {
        @EventHandler
        private void onGameJoined(ServerConnectBeginEvent event) {
            lastServerConnection = new ObjectObjectImmutablePair<>(event.address, event.info);
        }
    }
}
