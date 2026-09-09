/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.combat;

import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.settings.BoolSetting;
import dev.monocle.client.settings.IntSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.player.FindItemResult;
import dev.monocle.client.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class BowSpam extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCrossbows = settings.createGroup("Crossbows");

    private final Setting<Integer> charge = sgGeneral.add(new IntSetting.Builder()
        .name("charge")
        .description("How long to charge the bow before releasing in ticks.")
        .defaultValue(5)
        .range(4, 20)
        .sliderRange(4, 20)
        .build()
    );

    private final Setting<Boolean> onlyWhenHoldingRightClick = sgGeneral.add(new BoolSetting.Builder()
        .name("when-holding-right-click")
        .description("Works only when holding right click.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> spamCrossbows = sgCrossbows.add(new BoolSetting.Builder()
        .name("spam-crossbows")
        .description("Whether to spam loaded crossbows; takes priority over charging bows.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> crossbowDelay = sgCrossbows.add(new IntSetting.Builder()
        .name("crossbow-delay")
        .description("Delay between shooting crossbows in ticks.")
        .defaultValue(10)
        .sliderRange(0, 20)
        .min(0)
        .build()
    );

    private final Setting<Boolean> searchInventory = sgCrossbows.add(new BoolSetting.Builder()
        .name("search-inventory")
        .description("Whether to search your inventory to find loaded crossbows.")
        .defaultValue(true)
        .build()
    );

    private boolean pressingUse;
    private boolean wasUsePressed;
    private int ticks;

    public BowSpam() {
        super(Categories.Combat, "bow-spam", "Spams bows and crossbows.", "auto-bow", "crossbow-spam", "auto-crossbow");
    }

    @Override
    public void onActivate() {
        pressingUse = false;
        ticks = 0;
    }

    @Override
    public void onDeactivate() {
        releaseUse();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) {
            releaseUse();
            return;
        }

        if (ticks < crossbowDelay.get()) ticks++;

        FindItemResult crossbow = searchInventory.get() ? InvUtils.find(this::crossbow) : InvUtils.findInHotbar(this::crossbow);
        if (spamCrossbows.get() && crossbow.found()) {
            releaseUse();
            if (ticks >= crossbowDelay.get()) shootCrossbow(crossbow);
            return;
        }

        if (!mc.player.getAbilities().instabuild && !InvUtils.find(stack -> stack.getItem() instanceof ArrowItem).found()) {
            releaseUse();
            return;
        }

        InteractionHand hand = mc.player.getMainHandItem().is(Items.BOW)
            ? InteractionHand.MAIN_HAND
            : mc.player.getOffhandItem().is(Items.BOW) ? InteractionHand.OFF_HAND : null;
        if (hand == null || onlyWhenHoldingRightClick.get() && !mc.options.keyUse.isDown()) {
            releaseUse();
            return;
        }

        if (!onlyWhenHoldingRightClick.get()) pressUse();
        if (!mc.player.isUsingItem()) mc.gameMode.useItem(mc.player, hand);
        if (mc.player.getTicksUsingItem() >= charge.get()) mc.gameMode.releaseUsingItem(mc.player);
    }

    private void shootCrossbow(FindItemResult crossbow) {
        if (crossbow.isOffhand()) {
            mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            ticks = 0;
            return;
        }

        int slot = crossbow.slot();
        int inventorySlot = -1;
        if (!crossbow.isHotbar()) {
            if (!(mc.player.containerMenu instanceof InventoryMenu) || !mc.player.containerMenu.getCarried().isEmpty()) return;

            FindItemResult hotbar = InvUtils.find(stack -> stack.isEmpty() || stack.is(Items.CROSSBOW) || stack.is(Items.ARROW), 0, 8);
            if (!hotbar.found()) return;

            inventorySlot = crossbow.slot();
            slot = hotbar.slot();
            InvUtils.quickSwap().fromId(slot).to(inventorySlot);
            if (!crossbow(mc.player.getInventory().getItem(slot))) {
                InvUtils.quickSwap().fromId(slot).to(inventorySlot);
                return;
            }
        }

        int previousSlot = mc.player.getInventory().getSelectedSlot();
        if (InvUtils.swap(slot, false)) {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            InvUtils.swap(previousSlot, false);
            ticks = 0;
        }

        if (inventorySlot != -1) InvUtils.quickSwap().fromId(slot).to(inventorySlot);
    }

    private void pressUse() {
        if (!pressingUse) {
            wasUsePressed = mc.options.keyUse.isDown();
            pressingUse = true;
        }
        mc.options.keyUse.setDown(true);
    }

    private void releaseUse() {
        if (!pressingUse) return;
        mc.options.keyUse.setDown(wasUsePressed);
        pressingUse = false;
    }

    private boolean crossbow(ItemStack stack) {
        return stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack);
    }
}
