/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.combat;

import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.settings.BoolSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.PlayerUtils;
import dev.monocle.client.utils.player.Rotations;
import dev.monocle.client.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;

public class AntiBed extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> placeStringTop = sgGeneral.add(new BoolSetting.Builder()
        .name("place-string-top")
        .description("Places string above you.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> placeStringMiddle = sgGeneral.add(new BoolSetting.Builder()
        .name("place-string-middle")
        .description("Places string in your upper hitbox.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> placeStringBottom = sgGeneral.add(new BoolSetting.Builder()
        .name("place-string-bottom")
        .description("Places string at your feet.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyInHole = sgGeneral.add(new BoolSetting.Builder()
        .name("only-in-hole")
        .description("Only functions when you are standing in a hole.")
        .defaultValue(true)
        .build()
    );

    private boolean breaking;

    public AntiBed() {
        super(Categories.Combat, "anti-bed", "Places string to prevent beds being placed on you.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (onlyInHole.get() && !PlayerUtils.isInHole(true)) return;

        // Checking for and maybe breaking bed
        BlockPos head = mc.player.blockPosition().above();

        if (mc.level.getBlockState(head).getBlock() instanceof BedBlock && !breaking) {
            Rotations.rotate(Rotations.getYaw(head), Rotations.getPitch(head), 50, () -> sendMinePackets(head));
            breaking = true;
        } else if (breaking) {
            Rotations.rotate(Rotations.getYaw(head), Rotations.getPitch(head), 50, () -> sendStopPackets(head));
            breaking = false;
        }

        // String placement
        if (placeStringTop.get()) place(mc.player.blockPosition().above(2));
        if (placeStringMiddle.get()) place(mc.player.blockPosition().above(1));
        if (placeStringBottom.get()) place(mc.player.blockPosition());
    }

    private void place(BlockPos blockPos) {
        if (mc.level.getBlockState(blockPos).getBlock().asItem() != Items.STRING) {
            BlockUtils.place(blockPos, InvUtils.findInHotbar(Items.STRING), 50, false);
        }
    }

    private void sendMinePackets(BlockPos blockPos) {
        mc.gameMode.startPrediction(mc.level, sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, blockPos, Direction.UP, sequence));
        mc.gameMode.startPrediction(mc.level, sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, blockPos, Direction.UP, sequence));
    }

    private void sendStopPackets(BlockPos blockPos) {
        mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, blockPos, Direction.UP));
        mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
    }
}
