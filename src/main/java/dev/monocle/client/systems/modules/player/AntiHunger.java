/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.player;

import dev.monocle.client.events.entity.player.SendMovementPacketsEvent;
import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.mixin.ServerboundMovePlayerPacketAccessor;
import dev.monocle.client.settings.BoolSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

public class AntiHunger extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> sprint = sgGeneral.add(new BoolSetting.Builder()
        .name("sprint")
        .description("Spoofs sprinting packets.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onGround = sgGeneral.add(new BoolSetting.Builder()
        .name("on-ground")
        .description("Spoofs the onGround flag.")
        .defaultValue(true)
        .build()
    );

    private boolean lastOnGround, ignorePacket;

    public AntiHunger() {
        super(Categories.Player, "anti-hunger", "Reduces (does NOT remove) hunger consumption.");
    }

    @Override
    public void onActivate() {
        lastOnGround = mc.player.onGround();
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (ignorePacket && event.packet instanceof ServerboundMovePlayerPacket) {
            ignorePacket = false;
            return;
        }

        if (mc.player.isPassenger() || mc.player.isInWater() || mc.player.isUnderWater()) return;

        if (event.packet instanceof ServerboundPlayerCommandPacket packet && sprint.get()) {
            if (packet.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING) event.cancel();
        }

        if (event.packet instanceof ServerboundMovePlayerPacket packet && onGround.get() && mc.player.onGround() && mc.player.fallDistance <= 0.0 && !mc.gameMode.isDestroying()) {
            ((ServerboundMovePlayerPacketAccessor) packet).monocle$setOnGround(false);
        }
    }

    @EventHandler
    private void onTick(SendMovementPacketsEvent.Pre event) {
        if (mc.player.onGround() && !lastOnGround && onGround.get()) {
            ignorePacket = true; // prevents you from not taking fall damage
        }

        lastOnGround = mc.player.onGround();
    }
}
