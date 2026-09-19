/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.movement;

import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.events.game.GameLeftEvent;
import dev.monocle.client.events.game.GameJoinedEvent;
import dev.monocle.client.events.world.ServerConnectBeginEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.settings.EnumSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.world.entity.Relative;

/** Walk through the server's temporary lobby, then stop at the real world. */
public class LobbySkip extends Module {
    public enum Server {
        SixB6T;
        @Override public String toString() { return "6b6t"; }
    }

    private final Setting<Server> server = settings.getDefaultGroup().add(new EnumSetting.Builder<Server>()
        .name("server")
        .description("Lobby flow to use.")
        .defaultValue(Server.SixB6T)
        .build()
    );

    private ClientLevel lobbyWorld;
    private int walkingTicks;
    private boolean skipping, transferPending, armed, worldLeaving;

    public LobbySkip() {
        super(Categories.Movement, "lobby-skip", "Walks through the selected server's lobby, then stops after the world changes.");
        runInMainMenu = true;
    }

    @Override public void onActivate() {
        // An off/on cycle while already in a real world must not masquerade as a fresh login.
        if (!armed) armed = mc.level == null;
        lobbyWorld = mc.level;
        walkingTicks = 0;
        skipping = armed && !transferPending;
        transferPending = false;
        worldLeaving = false;
    }
    @Override public void onDeactivate() {
        mc.options.keyUp.setDown(false);
        if (!transferPending) { lobbyWorld = null; walkingTicks = 0; skipping = false; }
        if (!worldLeaving) armed = false;
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onGameLeft(GameLeftEvent event) { worldLeaving = true; }

    @EventHandler
    private void onConnect(ServerConnectBeginEvent event) { armed = true; }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (!armed && !worldLeaving) return;
        lobbyWorld = mc.level;
        walkingTicks = 0;
        skipping = armed && !transferPending;
        transferPending = worldLeaving = false;
    }

    private void finishLobby() {
        skipping = false;
        mc.options.keyUp.setDown(false);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!skipping) return;
        if (mc.level == null) return;
        if (lobbyWorld == null) lobbyWorld = mc.level;
        if (mc.level != lobbyWorld) { finishLobby(); return; }
        walkingTicks++;
        mc.options.keyUp.setDown(true);
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundRespawnPacket) { finishLobby(); return; }
        if (event.packet instanceof ClientboundTransferPacket || event.packet instanceof ClientboundStartConfigurationPacket) {
            transferPending = true;
            finishLobby();
            return;
        }
        if (event.packet instanceof ClientboundPlayerPositionPacket packet && mc.player != null && walkingTicks >= 20
            && !packet.relatives().contains(Relative.X) && !packet.relatives().contains(Relative.Z)
            && packet.change().position().distanceToSqr(mc.player.position()) > 256) finishLobby();
    }
}
