/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.world;

import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.mixin.ServerboundMovePlayerPacketAccessor;
import dev.monocle.client.mixininterface.IServerboundMovePlayerPacket;
import dev.monocle.client.settings.BoolSetting;
import dev.monocle.client.settings.EnumSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.entity.Target;
import dev.monocle.client.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class EndermanLook extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> lookMode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("look-mode")
        .description("How this module behaves.")
        .defaultValue(Mode.Away)
        .build()
    );

    private final Setting<Boolean> stun = sgGeneral.add(new BoolSetting.Builder()
        .name("stun-hostiles")
        .description("Automatically stares at hostile endermen to stun them in place.")
        .defaultValue(true)
        .visible(() -> lookMode.get() == Mode.Away)
        .build()
    );

    private LocalPlayer lastPlayer;
    private float lastYaw, lastPitch;

    public EndermanLook() {
        super(Categories.World, "enderman-look", "Either looks at all Endermen or prevents you from looking at Endermen.");
    }

    @Override public void onActivate() { lastPlayer = null; }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        // if either are true nothing happens when you look at an enderman
        if (mc.player.getItemBySlot(EquipmentSlot.HEAD).is(ItemTags.GAZE_DISGUISE_EQUIPMENT) || mc.player.getAbilities().instabuild)
            return;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof EnderMan enderman) || !enderman.isAlive() || !mc.player.hasLineOfSight(enderman))
                continue;

            switch (lookMode.get()) {
                case Away -> {
                    if (enderman.isCreepy() && stun.get())
                        Rotations.rotate(Rotations.getYaw(enderman), Rotations.getPitch(enderman, Target.Head), -75, null);
                }
                case At -> {
                    if (!enderman.isCreepy())
                        Rotations.rotate(Rotations.getYaw(enderman), Rotations.getPitch(enderman, Target.Head), -75, null);
                }
            }
        }
    }

    // Navigation and queued module rotations can change aim after Tick.Pre. Guard the
    // actual outgoing aim instead, without changing the camera or movement direction.
    @EventHandler(priority = EventPriority.LOWEST)
    private void onSend(PacketEvent.Send event) {
        if (event.isCancelled() || mc.player == null || mc.level == null
            || !(event.packet instanceof ServerboundMovePlayerPacket packet)) return;

        if (lastPlayer != mc.player) {
            lastPlayer = mc.player;
            lastYaw = mc.player.getYRot(); lastPitch = mc.player.getXRot();
        }
        float yaw = packet.getYRot(lastYaw), pitch = packet.getXRot(lastPitch);
        if (lookMode.get() != Mode.Away || mc.player.getAbilities().instabuild
            || mc.player.getItemBySlot(EquipmentSlot.HEAD).is(ItemTags.GAZE_DISGUISE_EQUIPMENT)) {
            lastYaw = yaw; lastPitch = pitch;
            return;
        }

        Vec3 eye = new Vec3(packet.getX(mc.player.getX()),
            packet.getY(mc.player.getY()) + mc.player.getEyeY() - mc.player.getY(), packet.getZ(mc.player.getZ()));
        List<Vec3> eyes = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof EnderMan enderman && enderman.isAlive() && !enderman.isCreepy()
                && mc.player.hasLineOfSight(enderman)) eyes.add(enderman.getEyePosition());
        }
        Vec2 safe = safeRotation(eye, yaw, pitch, eyes);
        if (safe == null) {
            // No tested direction is safe: retain the previous server aim, but keep moving.
            event.cancel();
            sendReplacement(event, packet, packet.hasPosition()
                ? new ServerboundMovePlayerPacket.Pos(packet.getX(0), packet.getY(0), packet.getZ(0), packet.isOnGround(), packet.horizontalCollision())
                : new ServerboundMovePlayerPacket.StatusOnly(packet.isOnGround(), packet.horizontalCollision()));
        } else if (safe.x != pitch || safe.y != yaw) {
            if (packet.hasRotation()) {
                var accessor = (ServerboundMovePlayerPacketAccessor) packet;
                accessor.monocle$setPitch(safe.x);
                accessor.monocle$setYaw(safe.y);
            } else {
                event.cancel();
                sendReplacement(event, packet, packet.hasPosition()
                    ? new ServerboundMovePlayerPacket.PosRot(packet.getX(0), packet.getY(0), packet.getZ(0), safe.y, safe.x, packet.isOnGround(), packet.horizontalCollision())
                    : new ServerboundMovePlayerPacket.Rot(safe.y, safe.x, packet.isOnGround(), packet.horizontalCollision()));
            }
            Rotations.setCamRotation(safe.y, safe.x);
        }
        if (safe != null) { lastYaw = safe.y; lastPitch = safe.x; }
    }

    private static void sendReplacement(PacketEvent.Send event, ServerboundMovePlayerPacket original, ServerboundMovePlayerPacket replacement) {
        ((IServerboundMovePlayerPacket) replacement).monocle$setTag(((IServerboundMovePlayerPacket) original).monocle$getTag());
        event.sendSilently(replacement);
    }

    /** Pitch/yaw, using vanilla's gaze cone. Down is not safe when flying over an Enderman. */
    public static Vec2 safeRotation(Vec3 eye, float yaw, float pitch, List<Vec3> eyes) {
        if (!gazesAt(eye, yaw, pitch, eyes)) return new Vec2(pitch, yaw);
        for (float vertical : new float[] {90, -90}) {
            if (!gazesAt(eye, yaw, vertical, eyes)) return new Vec2(vertical, yaw);
        }
        for (int turn = 0; turn < 8; turn++) {
            float side = yaw + turn * 45;
            if (!gazesAt(eye, side, 0, eyes)) return new Vec2(0, side);
        }
        return null;
    }

    private static boolean gazesAt(Vec3 eye, float yaw, float pitch, List<Vec3> eyes) {
        Vec3 look = Vec3.directionFromRotation(pitch, yaw).normalize();
        for (Vec3 target : eyes) {
            Vec3 delta = target.subtract(eye);
            double distance = delta.length();
            if (distance > 1e-6 && look.dot(delta.scale(1 / distance)) > 1 - .025 / distance) return true;
        }
        return false;
    }

    public enum Mode {
        At,
        Away
    }
}
