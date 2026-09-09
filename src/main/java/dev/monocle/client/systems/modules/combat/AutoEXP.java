/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.combat;

import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.player.FindItemResult;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.Rotations;
import dev.monocle.client.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

public class AutoEXP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Which items to repair.")
        .defaultValue(Mode.Both)
        .build()
    );

    private final Setting<Boolean> replenish = sgGeneral.add(new BoolSetting.Builder()
        .name("replenish")
        .description("Automatically replenishes exp into a selected hotbar slot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyGround = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-ground")
        .description("Only throw when the player is on the ground.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("exp-slot")
        .description("The slot to replenish exp into.")
        .visible(replenish::get)
        .defaultValue(6)
        .range(1, 9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<Integer> minThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("min-threshold")
        .description("The minimum durability percentage that an item needs to fall to, to be repaired.")
        .defaultValue(30)
        .range(1, 100)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Integer> maxThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("max-threshold")
        .description("The maximum durability percentage to repair items to.")
        .defaultValue(80)
        .range(1, 100)
        .sliderRange(1, 100)
        .build()
    );

    private int repairingI;

    public AutoEXP() {
        super(Categories.Combat, "auto-exp", "Automatically repairs your armor and tools in pvp.");
    }

    @Override
    public void onActivate() {
        repairingI = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.player.isUsingItem()) return;
        if (onlyGround.get() && !mc.player.onGround()) return;

        int startThreshold = Math.min(minThreshold.get(), maxThreshold.get());
        int targetThreshold = Math.max(minThreshold.get(), maxThreshold.get());

        if (repairingI == -1) {
            if (mode.get() != Mode.Hands) {
                for (EquipmentSlot slot : EquipmentSlotGroup.ARMOR) {
                    ItemStack stack = mc.player.getItemBySlot(slot);
                    if (needsRepair(stack, startThreshold)) {
                        repairingI = SlotUtils.ARMOR_START + slot.getIndex();
                        break;
                    }
                }
            }

            if (mode.get() != Mode.Armor && repairingI == -1) {
                for (InteractionHand hand : InteractionHand.values()) {
                    if (needsRepair(mc.player.getItemInHand(hand), startThreshold)) {
                        repairingI = hand == InteractionHand.MAIN_HAND ? mc.player.getInventory().getSelectedSlot() : SlotUtils.OFFHAND;
                        break;
                    }
                }
            }
        }

        if (repairingI != -1) {
            ItemStack repairing = mc.player.getInventory().getItem(repairingI);
            if (!hasMending(repairing) || durabilityPercent(repairing) >= targetThreshold) {
                repairingI = -1;
                return;
            }

            FindItemResult exp = InvUtils.find(Items.EXPERIENCE_BOTTLE);

            if (exp.found()) {
                if (!exp.isHotbar() && !exp.isOffhand()) {
                    if (!replenish.get()) return;
                    if (!(mc.player.containerMenu instanceof InventoryMenu) || !mc.player.containerMenu.getCarried().isEmpty()) return;

                    int hotbarSlot = slot.get() - 1;
                    InvUtils.move().from(exp.slot()).toHotbar(hotbarSlot);
                    if (!mc.player.getInventory().getItem(hotbarSlot).is(Items.EXPERIENCE_BOTTLE)) return;
                    exp = new FindItemResult(hotbarSlot, mc.player.getInventory().getItem(hotbarSlot).getCount());
                }

                FindItemResult selectedExp = exp;
                Rotations.rotate(mc.player.getYRot(), 90, () -> {
                    if (mc.player == null || mc.gameMode == null) return;
                    if (selectedExp.getHand() != null) {
                        if (mc.player.getItemInHand(selectedExp.getHand()).is(Items.EXPERIENCE_BOTTLE)) {
                            mc.gameMode.useItem(mc.player, selectedExp.getHand());
                        }
                    } else {
                        if (!mc.player.getInventory().getItem(selectedExp.slot()).is(Items.EXPERIENCE_BOTTLE)) return;
                        int previousSlot = mc.player.getInventory().getSelectedSlot();
                        if (!InvUtils.swap(selectedExp.slot(), false)) return;
                        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                        InvUtils.swap(previousSlot, false);
                    }
                });
            }
        }
    }

    private boolean needsRepair(ItemStack itemStack, double threshold) {
        return hasMending(itemStack) && durabilityPercent(itemStack) <= threshold;
    }

    private boolean hasMending(ItemStack stack) {
        return !stack.isEmpty() && stack.isDamageableItem() && Utils.hasEnchantments(stack, Enchantments.MENDING);
    }

    private double durabilityPercent(ItemStack stack) {
        return (stack.getMaxDamage() - stack.getDamageValue()) / (double) stack.getMaxDamage() * 100;
    }

    public enum Mode {
        Armor,
        Hands,
        Both
    }
}
