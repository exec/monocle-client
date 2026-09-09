/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.combat;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.mixin.BlockBehaviourAccessor;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.entity.EntityUtils;
import dev.monocle.client.utils.player.FindItemResult;
import dev.monocle.client.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Quiver extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Safety");


    private final Setting<List<MobEffect>> effects = sgGeneral.add(new StatusEffectListSetting.Builder()
        .name("effects")
        .description("Which effects to shoot you with.")
        .defaultValue(MobEffects.STRENGTH.value())
        .build()
    );

    private final Setting<Integer> cooldown = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown")
        .description("How many ticks between shooting effects (19 minimum for NCP).")
        .defaultValue(10)
        .range(0, 40)
        .sliderRange(0, 40)
        .build()
    );

    private final Setting<Boolean> checkEffects = sgGeneral.add(new BoolSetting.Builder()
        .name("check-effects")
        .description("Won't shoot you with effects you already have.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> silentBow = sgGeneral.add(new BoolSetting.Builder()
        .name("silent-bow")
        .description("Takes a bow from your inventory to quiver.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-info")
        .description("Sends info about quiver checks in chat.")
        .defaultValue(false)
        .build()
    );

    // Safety

    private final Setting<Boolean> onlyInHoles = sgSafety.add(new BoolSetting.Builder()
        .name("only-in-holes")
        .description("Only quiver when you're in a hole.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyOnGround = sgSafety.add(new BoolSetting.Builder()
        .name("only-on-ground")
        .description("Only quiver when you're on the ground.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> minHealth = sgSafety.add(new DoubleSetting.Builder()
        .name("min-health")
        .description("How much health you must have to quiver.")
        .defaultValue(10)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final IntList arrowSlots = new IntArrayList();
    private boolean prepared, bowFromHotbar, charging, ownsUse, wasUsePressed;
    private int timer, originalSelectedSlot, bowSlot, bowInventorySlot, stagedArrowSlot;
    private final BlockPos.MutableBlockPos testPos = new BlockPos.MutableBlockPos();

    public Quiver() {
        super(Categories.Combat, "quiver", "Shoots arrows at yourself.");
    }

    @Override
    public void onActivate() {
        prepared = false;
        bowFromHotbar = false;
        charging = false;
        ownsUse = false;
        stagedArrowSlot = -1;
        arrowSlots.clear();

        if (mc.player == null || mc.level == null) {
            toggle();
            return;
        }

        FindItemResult bow = InvUtils.find(Items.BOW);
        if (!shouldQuiver(bow)) {
            toggle();
            return;
        }

        wasUsePressed = mc.options.keyUse.isDown();
        ownsUse = true;
        mc.options.keyUse.setDown(false);
        mc.gameMode.releaseUsingItem(mc.player);

        originalSelectedSlot = mc.player.getInventory().getSelectedSlot();
        bowSlot = bow.isHotbar() ? bow.slot() : originalSelectedSlot;
        bowInventorySlot = -1;
        timer = 0;

        if (!bow.isMainHand()) {
            if (bow.isHotbar()) {
                bowFromHotbar = true;
                if (!InvUtils.swap(bow.slot(), false)) {
                    toggle();
                    return;
                }
            } else {
                if (!canMoveInventory()) {
                    toggle();
                    return;
                }
                bowInventorySlot = bow.slot();
                prepared = true;
                InvUtils.move().from(bowInventorySlot).to(originalSelectedSlot);
                if (!mc.player.getMainHandItem().is(Items.BOW)) {
                    toggle();
                    return;
                }
            }
        }

        prepared = true;

        Set<MobEffect> usedEffects = new HashSet<>();

        for (int i = 0; i < mc.player.getInventory().getNonEquipmentItems().size(); i++) {
            if (i == mc.player.getInventory().getSelectedSlot()) continue;

            ItemStack item = mc.player.getInventory().getItem(i);
            if (!item.is(Items.TIPPED_ARROW) || item.get(DataComponents.POTION_CONTENTS) == null) continue;

            for (MobEffectInstance instance : item.get(DataComponents.POTION_CONTENTS).getAllEffects()) {
                MobEffect effect = instance.getEffect().value();
                if (this.effects.get().contains(effect)
                    && usedEffects.add(effect)
                    && (!hasEffect(effect) || !checkEffects.get())) {
                    arrowSlots.add(i);
                    break;
                }
            }
        }

        if (arrowSlots.isEmpty()) toggle();
    }

    @Override
    public void onDeactivate() {
        if (ownsUse) {
            mc.options.keyUse.setDown(wasUsePressed);
            if (mc.player != null) mc.gameMode.releaseUsingItem(mc.player);
        }
        if (mc.player == null) {
            prepared = false;
            charging = false;
            ownsUse = false;
            return;
        }

        restoreArrow();

        if (prepared) {
            if (bowFromHotbar) InvUtils.swap(originalSelectedSlot, false);
            else if (bowInventorySlot != -1 && canMoveInventory()) {
                InvUtils.swap(bowSlot, false);
                InvUtils.move().from(bowSlot).to(bowInventorySlot);
            }
        }

        prepared = false;
        charging = false;
        ownsUse = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || !prepared) {
            if (isActive()) toggle();
            return;
        }

        if (!mc.player.getInventory().getItem(bowSlot).is(Items.BOW) || !InvUtils.swap(bowSlot, false) || !shouldQuiver(null)) {
            toggle();
            return;
        }
        if (arrowSlots.isEmpty()) {
            toggle();
            return;
        }

        if (timer > 0) {
            timer--;
            return;
        }

        if (!charging) {
            int arrowSlot = arrowSlots.getInt(0);
            if (!mc.player.getInventory().getItem(arrowSlot).is(Items.TIPPED_ARROW) || !canMoveInventory()) {
                arrowSlots.removeInt(0);
                return;
            }

            stagedArrowSlot = arrowSlot;
            if (stagedArrowSlot != 9) InvUtils.move().from(stagedArrowSlot).to(9);
            if (!mc.player.getInventory().getItem(9).is(Items.TIPPED_ARROW)) {
                restoreArrow();
                return;
            }

            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            charging = mc.player.isUsingItem();
            if (!charging) {
                mc.options.keyUse.setDown(false);
                restoreArrow();
            }
        } else {
            if (BowItem.getPowerForTime(mc.player.getTicksUsingItem()) >= 0.12) {
                arrowSlots.removeInt(0);

                mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(mc.player.getYRot(), -90, mc.player.onGround(), mc.player.horizontalCollision));
                mc.options.keyUse.setDown(false);
                mc.gameMode.releaseUsingItem(mc.player);
                restoreArrow();

                charging = false;
                timer = cooldown.get();
            }
        }
    }

    private boolean shouldQuiver(FindItemResult bow) {
        if (bow != null && (!bow.found() || (!bow.isHotbar() && !bow.isMainHand() && !silentBow.get()))) {
            if (chatInfo.get()) error("Couldn't find a usable bow, disabling.");
            return false;
        }

        if (bow == null && !mc.player.getMainHandItem().is(Items.BOW)) return false;

        if (!headIsOpen()) {
            if (chatInfo.get()) error("Not enough space to quiver, disabling.");
            return false;
        }

        if (EntityUtils.getTotalHealth(mc.player) < minHealth.get()) {
            if (chatInfo.get()) error("Not enough health to quiver, disabling.");
            return false;
        }

        if (onlyOnGround.get() && !mc.player.onGround()) {
            if (chatInfo.get()) error("You are not on the ground, disabling.");
            return false;
        }

        if (onlyInHoles.get() && !isSurrounded(mc.player)) {
            if (chatInfo.get()) error("You are not in a hole, disabling.");
            return false;
        }

        return true;
    }

    private void restoreArrow() {
        if (stagedArrowSlot != -1 && stagedArrowSlot != 9 && canMoveInventory()) {
            InvUtils.move().from(9).to(stagedArrowSlot);
        }
        stagedArrowSlot = -1;
    }

    private boolean canMoveInventory() {
        return mc.player.containerMenu instanceof InventoryMenu && mc.player.containerMenu.getCarried().isEmpty();
    }

    private boolean headIsOpen() {
        testPos.set(mc.player.blockPosition().offset(0, 1, 0));
        BlockState pos1 = mc.level.getBlockState(testPos);
        if (((BlockBehaviourAccessor) pos1.getBlock()).monocle$isHasCollision()) return false;

        testPos.offset(0, 1, 0);
        BlockState pos2 = mc.level.getBlockState(testPos);
        return !((BlockBehaviourAccessor) pos2.getBlock()).monocle$isHasCollision();
    }

    private boolean hasEffect(MobEffect effect) {
        for (MobEffectInstance statusEffect : mc.player.getActiveEffects()) {
            if (statusEffect.getEffect().value().equals(effect)) return true;
        }

        return false;
    }

    private boolean isSurrounded(Player target) {
        for (Direction dir : Direction.values()) {
            if (dir == Direction.UP || dir == Direction.DOWN) continue;

            testPos.set(target.blockPosition()).relative(dir);
            Block block = mc.level.getBlockState(testPos).getBlock();

            if (block != Blocks.OBSIDIAN && block != Blocks.BEDROCK && block != Blocks.RESPAWN_ANCHOR
                && block != Blocks.CRYING_OBSIDIAN && block != Blocks.NETHERITE_BLOCK) {
                return false;
            }
        }

        return true;
    }
}
