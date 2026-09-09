/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.misc;

import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.settings.PacketListSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import org.jspecify.annotations.NonNull;

import java.util.Set;

public class PacketCanceller extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Set<PacketType<? extends @NonNull Packet<?>>>> s2cPackets = sgGeneral.add(new PacketListSetting.Builder()
        .name("S2C-packets")
        .description("Server-to-client packets to cancel.")
        .clientbound()
        .build()
    );

    private final Setting<Set<PacketType<? extends @NonNull Packet<?>>>> c2sPackets = sgGeneral.add(new PacketListSetting.Builder()
        .name("C2S-packets")
        .description("Client-to-server packets to cancel.")
        .serverbound()
        .build()
    );

    public PacketCanceller() {
        super(Categories.Misc, "packet-canceller", "Allows you to cancel certain packets.");
        runInMainMenu = true;
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onReceivePacket(PacketEvent.Receive event) {
        if (s2cPackets.get().contains(event.packet.type())) event.cancel();
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onSendPacket(PacketEvent.Send event) {
        if (c2sPackets.get().contains(event.packet.type())) event.cancel();
    }
}
