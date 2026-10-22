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
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.player.CustomPlayerInput;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
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
    private final CustomPlayerInput input = new CustomPlayerInput();
    private ClientInput previousInput;
    private LocalPlayer inputPlayer;
    private String status = "Idle";
    static final int MAX_WALK_TICKS = 1200;

    public LobbySkip() {
        super(Categories.Movement, "lobby-skip", "Walks through the selected server's lobby, then stops after the world changes.");
        runInMainMenu = true;
    }

    @Override public void onActivate() {
        // An off/on cycle while already in a real world must not masquerade as a fresh login.
        if (!armed) armed = mc.level == null || isInLobby();
        lobbyWorld = mc.level;
        walkingTicks = 0;
        skipping = armed && !transferPending;
        transferPending = false;
        worldLeaving = false;
    }
    @Override public void onDeactivate() {
        releaseInput();
        if (!transferPending) { lobbyWorld = null; walkingTicks = 0; skipping = false; }
        if (!worldLeaving) armed = false;
    }

    /** Explicit operator intent, unlike toggling the module in an already joined world. */
    public void startSkipping() {
        if (mc.player == null || mc.level == null || mc.getCurrentServer() == null
            || !supportedServer(mc.getCurrentServer().ip)) throw new IllegalStateException("Join the 6b6t lobby before starting Lobby Skip");
        if (!isActive()) toggle();
        releaseInput();
        lobbyWorld = mc.level;
        walkingTicks = 0;
        armed = skipping = true;
        dev.monocle.client.utils.network.WorldReadiness.INSTANCE.markLobby();
        transferPending = worldLeaving = false;
        status = "Walking through lobby";
        info("Lobby Skip started explicitly; stops on world transfer or after 60 seconds.");
    }

    public void stopSkipping() { armed = transferPending = false; finishLobby("Stopped by operator"); }
    public String status() { return status; }
    public boolean isInLobby() { return dev.monocle.client.utils.network.WorldReadiness.INSTANCE.isInLobby(); }
    @Override public String getInfoString() { return status; }

    public static boolean supportedServer(String address) {
        return dev.monocle.client.utils.network.WorldReadiness.lobbyServer(address);
    }

    static boolean withinWalkBudget(int ticks) { return ticks < MAX_WALK_TICKS; }

    private void releaseInput() {
        input.stop();
        if (inputPlayer != null && inputPlayer.input == input) inputPlayer.input = previousInput;
        inputPlayer = null;
        previousInput = null;
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onGameLeft(GameLeftEvent event) { worldLeaving = true; releaseInput(); }

    @EventHandler
    private void onConnect(ServerConnectBeginEvent event) {
        releaseInput();
        armed = supportedServer(event.info.ip) && !transferPending;
        skipping = false;
        lobbyWorld = null;
        walkingTicks = 0;
        status = armed ? "Waiting for lobby" : "Waiting for world";
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (!armed && !worldLeaving) return;
        lobbyWorld = mc.level;
        walkingTicks = 0;
        skipping = armed && !transferPending;
        transferPending = worldLeaving = false;
        status = skipping ? "Walking through lobby" : "World joined";
    }

    private void finishLobby(String reason) {
        // Stopping movement does not prove we left the lobby.
        skipping = armed = false;
        status = reason;
        releaseInput();
    }

    private void leaveLobby(String reason) {
        finishLobby(reason);
    }

    public void onServerRespawn() { if (isActive() && mc.isSameThread()) leaveLobby("World changed"); }

    public void onServerTeleport(ClientboundPlayerPositionPacket packet) {
        if (isActive() && mc.isSameThread() && mc.player != null && walkingTicks >= 20
            && !packet.relatives().contains(Relative.X) && !packet.relatives().contains(Relative.Z)
            && packet.change().position().distanceToSqr(mc.player.position()) > 256) leaveLobby("Lobby teleport detected");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!skipping) return;
        if (mc.level == null || mc.player == null) { releaseInput(); return; }
        if (lobbyWorld == null) lobbyWorld = mc.level;
        if (mc.level != lobbyWorld) { leaveLobby("World changed"); return; }
        if (!withinWalkBudget(walkingTicks)) {
            finishLobby("Timed out; start again to retry");
            warning("Lobby Skip stopped after 60 seconds without a world transfer.");
            return;
        }
        if (inputPlayer != null && inputPlayer != mc.player) releaseInput();
        if (inputPlayer == null) {
            if (!(mc.player.input instanceof KeyboardInput)) { finishLobby("Another feature owns movement"); return; }
            inputPlayer = mc.player;
            previousInput = mc.player.input;
            mc.player.input = input;
        }
        if (mc.player.input != input) { finishLobby("Another feature took movement"); return; }
        walkingTicks++;
        input.forward(true); // Independent of focus, chat, pause screens and key resets.
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        // Receipt stops walking, but player protection waits for the client-thread world transition.
        if (event.packet instanceof ClientboundRespawnPacket) { finishLobby("World changed"); return; }
        if (event.packet instanceof ClientboundTransferPacket || event.packet instanceof ClientboundStartConfigurationPacket) {
            transferPending = true;
            finishLobby("World transfer detected");
            return;
        }
    }
}
