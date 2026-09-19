/*
 * Derived from AirMiner in Quiettee Utils (https://github.com/FragmentZero6b6t/quiettee-utils).
 * Copyright (c) FragmentZero6b6t. Licensed under GPL-3.0.
 */

package dev.monocle.client.systems.modules.player;

import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.mixin.ServerboundMovePlayerPacketAccessor;
import dev.monocle.client.settings.BoolSetting;
import dev.monocle.client.settings.DoubleSetting;
import dev.monocle.client.settings.IntSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

/** Retains normal mining speed while airborne by briefly claiming ground on break packets. */
public class AirMine extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Boolean> whileGliding = sgGeneral.add(new BoolSetting.Builder().name("while-gliding").description("Also claim ground while gliding.").defaultValue(false).build());
    private final Setting<Double> maxSpeed = sgGeneral.add(new DoubleSetting.Builder().name("max-speed").description("Do not claim ground above this horizontal speed.").defaultValue(1).min(0).sliderRange(0, 3).build());
    private final Setting<Integer> claimWindow = sgGeneral.add(new IntSetting.Builder().name("claim-window").description("How long each ground claim lasts, in ticks.").defaultValue(2).min(1).sliderRange(1, 10).build());

    private int claimLeft;
    private boolean claiming;
    private boolean preserveGlide;

    public AirMine() {
        super(Categories.Player, "air-mine", "Mines at normal speed while airborne.");
    }

    @Override public void onActivate() { claimLeft = 0; claiming = false; preserveGlide = false; }
    @Override public void onDeactivate() { if (claiming) restore(); else preserveGlide = false; }

    public boolean liftsAirPenalty() { return isActive() && canClaim(); }

    /** Keeps the fake ground claim from making the local Elytra model and physics drop. */
    public boolean preservesGlide() { return isActive() && preserveGlide; }

    private boolean canClaim() {
        return mc.player != null && mc.getConnection() != null && !mc.player.onGround()
            && (!mc.player.isFallFlying() || whileGliding.get()) && !mc.player.isCreative() && !mc.player.isSpectator()
            && mc.player.getDeltaMovement().horizontalDistance() <= maxSpeed.get();
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (claimLeft > 0 && --claimLeft == 0 && claiming) restore();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onSend(PacketEvent.Send event) {
        if (mc.player == null) return;
        if (event.packet instanceof ServerboundPlayerActionPacket action
            && (action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK || action.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)
            && canClaim()) claim();
        else if (event.packet instanceof ServerboundMovePlayerPacket move && claimLeft > 0 && move.hasPosition())
            ((ServerboundMovePlayerPacketAccessor) move).monocle$setOnGround(true);
    }

    private void claim() {
        preserveGlide = whileGliding.get() && mc.player.isFallFlying();
        mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot(), true, mc.player.horizontalCollision));
        claimLeft = claimWindow.get();
        claiming = true;
    }

    private void restore() {
        claiming = false;
        preserveGlide = false;
        if (mc.player != null && mc.getConnection() != null)
            mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot(), mc.player.onGround(), mc.player.horizontalCollision));
    }
}
