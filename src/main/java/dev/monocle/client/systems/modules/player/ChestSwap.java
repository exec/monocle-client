/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.player;

import dev.monocle.client.settings.BoolSetting;
import dev.monocle.client.settings.EnumSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.config.Config;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.player.InvUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

public class ChestSwap extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Chestplate> chestplate = sgGeneral.add(new EnumSetting.Builder<Chestplate>()
        .name("chestplate")
        .description("Which type of chestplate to swap to.")
        .defaultValue(Chestplate.PreferNetherite)
        .build()
    );

    private final Setting<Boolean> stayOn = sgGeneral.add(new BoolSetting.Builder()
        .name("stay-on")
        .description("Stays on and activates when you turn it off.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> closeInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("close-inventory")
        .description("Sends inventory close after swap.")
        .defaultValue(true)
        .build()
    );

    private boolean swappedOnActivate;

    public ChestSwap() {
        super(Categories.Player, "chest-swap", "Automatically swaps between a chestplate and an elytra.");
    }

    @Override
    public void onActivate() {
        swappedOnActivate = trySwap();
        if (!stayOn.get()) toggle();
    }

    @Override
    public void onDeactivate() {
        if (stayOn.get() && swappedOnActivate) trySwap();
        swappedOnActivate = false;
    }

    public void swap() {
        trySwap();
    }

    private boolean trySwap() {
        if (mc.player == null
            || mc.player.isUsingItem()
            || !(mc.player.containerMenu instanceof InventoryMenu)
            || !mc.player.containerMenu.getCarried().isEmpty()) return false;

        ItemStack currentItem = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (Utils.hasEnchantments(currentItem, Enchantments.BINDING_CURSE)) return false;

        if (currentItem.has(DataComponents.GLIDER)) {
            return equipChestplate();
        } else if (currentItem.has(DataComponents.EQUIPPABLE) && currentItem.get(DataComponents.EQUIPPABLE).slot().getIndex() == EquipmentSlot.CHEST.getIndex()) {
            return equipElytra();
        } else {
            return equipChestplate() || equipElytra();
        }
    }

    private boolean equipChestplate() {
        int bestSlot = -1;
        int bestPriority = -1;
        int bestDurability = -1;

        for (int i = 0; i < mc.player.getInventory().getNonEquipmentItems().size(); i++) {
            ItemStack stack = mc.player.getInventory().getNonEquipmentItems().get(i);
            if (Utils.hasEnchantments(stack, Enchantments.BINDING_CURSE)) continue;

            int priority = chestplatePriority(stack.getItem());
            if (priority < 0) continue;
            int durability = remainingDurability(stack);

            if (priority > bestPriority || (priority == bestPriority && durability > bestDurability)) {
                bestSlot = i;
                bestPriority = priority;
                bestDurability = durability;
            }
        }

        return bestPriority >= 0 && equip(bestSlot);
    }

    private boolean equipElytra() {
        int bestSlot = -1;
        int bestDurability = -1;

        for (int i = 0; i < mc.player.getInventory().getNonEquipmentItems().size(); i++) {
            ItemStack stack = mc.player.getInventory().getNonEquipmentItems().get(i);
            if (!stack.has(DataComponents.GLIDER) || Utils.hasEnchantments(stack, Enchantments.BINDING_CURSE)) continue;

            int durability = remainingDurability(stack);
            if (durability > bestDurability) {
                bestSlot = i;
                bestDurability = durability;
            }
        }

        return bestSlot != -1 && equip(bestSlot);
    }

    private int chestplatePriority(Item item) {
        return switch (chestplate.get()) {
            case Diamond -> item == Items.DIAMOND_CHESTPLATE ? 1 : -1;
            case Netherite -> item == Items.NETHERITE_CHESTPLATE ? 1 : -1;
            case PreferDiamond -> item == Items.DIAMOND_CHESTPLATE ? 2 : item == Items.NETHERITE_CHESTPLATE ? 1 : -1;
            case PreferNetherite -> item == Items.NETHERITE_CHESTPLATE ? 2 : item == Items.DIAMOND_CHESTPLATE ? 1 : -1;
        };
    }

    private int remainingDurability(ItemStack stack) {
        return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
    }

    private boolean equip(int slot) {
        InvUtils.move().from(slot).toArmor(2);
        if (closeInventory.get()) {
            // Notchian clients send a Close Window packet with Window ID 0 to close their inventory even though there is never an Open Screen packet for the inventory.
            mc.getConnection().send(new ServerboundContainerClosePacket(0));
        }
        return true;
    }

    @Override
    public void sendToggledMsg() {
        if (stayOn.get()) super.sendToggledMsg();
        else if (Config.get().chatFeedback.get() && chatFeedback) info("Triggered (highlight)%s(default).", title);
    }

    public enum Chestplate {
        Diamond,
        Netherite,
        PreferDiamond,
        PreferNetherite
    }
}
