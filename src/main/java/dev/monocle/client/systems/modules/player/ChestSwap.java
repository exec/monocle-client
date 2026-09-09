/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.player;

import dev.monocle.client.settings.BoolSetting;
import dev.monocle.client.settings.EnumSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.events.game.GameLeftEvent;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.orbit.EventHandler;
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
        .description("Chestplate material preference. Diamond and Netherite are strict filters; Any also allows weaker armor.")
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
    private boolean activationElytra;
    private Boolean requestedElytra;
    private Object requestPlayer, requestWorld;
    private boolean waitForGround;
    private int elapsed, retry, settled;
    private String status = "Ready";
    private final SwapListener swapListener = new SwapListener();
    private Object lastSwapPlayer;
    private int lastSwapTick;

    public boolean controlsChest() {
        return isActive() || requestedElytra != null && requestPlayer == mc.player && requestWorld == mc.level
            || mc.player != null && lastSwapPlayer == mc.player
            && mc.player.tickCount >= lastSwapTick && mc.player.tickCount - lastSwapTick < 40;
    }

    public ChestSwap() {
        super(Categories.Player, "chest-swap", "Automatically swaps between a chestplate and an elytra.");
    }

    @Override
    public void onActivate() {
        activationElytra = mc.player != null && !mc.player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER);
        swappedOnActivate = mc.player != null;
        requestEquip(activationElytra, false);
        if (!stayOn.get()) toggle();
    }

    @Override
    public void onDeactivate() {
        if (stayOn.get() && swappedOnActivate) requestEquip(!activationElytra, false);
        swappedOnActivate = false;
    }

    public void swap() {
        if (mc.player != null) requestEquip(!mc.player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER), false);
    }

    /** Directional requests are safe to retry: never toggle back after a predicted swap. */
    public void requestEquip(boolean elytra, boolean onGround) {
        cancelRequest();
        if (mc.player == null || mc.level == null) return;
        requestedElytra = elytra;
        requestPlayer = mc.player;
        requestWorld = mc.level;
        waitForGround = onGround;
        elapsed = retry = settled = 0;
        status = onGround ? "Waiting for landing" : "Equipping " + (elytra ? "elytra" : "chestplate");
        MonocleClient.EVENT_BUS.subscribe(swapListener);
    }

    public void cancelRequest() {
        requestedElytra = null;
        requestPlayer = requestWorld = null;
        MonocleClient.EVENT_BUS.unsubscribe(swapListener);
    }

    private class SwapListener {
        @EventHandler
        private void onLeft(GameLeftEvent event) { cancelRequest(); }

        @EventHandler
        private void onTick(TickEvent.Post event) {
            if (requestedElytra == null) return;
            if (mc.player != requestPlayer || mc.level != requestWorld || mc.player == null || !mc.player.isAlive()) {
                cancelRequest();
                return;
            }
            if (Modules.get().get(ElytraFly.class).hasAutopilotRequest()) return;
            if (waitForGround && (!mc.player.onGround() || mc.player.isFallFlying())) {
                status = "Waiting for landing";
                settled = 0;
                return;
            }
            if (++elapsed > 200) {
                warning("Could not equip %s: %s. Check inventory and retry.", requestedElytra ? "elytra" : "chestplate", status);
                cancelRequest();
                return;
            }
            ItemStack equipped = mc.player.getItemBySlot(EquipmentSlot.CHEST);
            if (usable(equipped, requestedElytra)) {
                // Local observation, not a server ACK. Allow a short window for inventory corrections.
                if (++settled >= 20) {
                    status = "Equipped " + (requestedElytra ? "elytra" : "chestplate");
                    cancelRequest();
                }
                return;
            }
            settled = 0;
            if (retry-- > 0) return;
            retry = 9;
            tryEquip(requestedElytra);
        }
    }

    private boolean tryEquip(boolean elytra) {
        if (mc.player == null
            || mc.player.isUsingItem()
            || !(mc.player.containerMenu instanceof InventoryMenu)
            || !mc.player.containerMenu.getCarried().isEmpty()) {
            status = "Waiting for inventory / item use";
            return false;
        }

        ItemStack currentItem = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (Utils.hasEnchantments(currentItem, Enchantments.BINDING_CURSE)) {
            status = "Equipped item has Curse of Binding";
            return false;
        }
        boolean submitted = elytra ? equipElytra() : equipChestplate();
        status = submitted ? "Waiting for equipment to settle" : "No usable " + (elytra ? "elytra" : "chestplate matching preference");
        return submitted;
    }

    public static boolean matchesTarget(ItemStack stack, boolean elytra) {
        return !stack.isEmpty() && stack.has(DataComponents.EQUIPPABLE)
            && stack.get(DataComponents.EQUIPPABLE).slot() == EquipmentSlot.CHEST
            && stack.has(DataComponents.GLIDER) == elytra;
    }

    private boolean equipChestplate() {
        int bestSlot = -1;
        int bestPriority = -1;
        int bestDurability = -1;

        for (int i = 0; i < mc.player.getInventory().getNonEquipmentItems().size(); i++) {
            ItemStack stack = mc.player.getInventory().getNonEquipmentItems().get(i);
            if (Modules.get().get(AutoMend.class).reservesSlot(i) || !usable(stack, false)) continue;
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
            if (Modules.get().get(AutoMend.class).reservesSlot(i) || !usable(stack, true)) continue;
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
            case Any -> item == Items.NETHERITE_CHESTPLATE ? 6 : item == Items.DIAMOND_CHESTPLATE ? 5
                : item == Items.IRON_CHESTPLATE ? 4 : item == Items.CHAINMAIL_CHESTPLATE ? 3
                : item == Items.GOLDEN_CHESTPLATE ? 2 : item == Items.LEATHER_CHESTPLATE ? 1 : -1;
            case Diamond -> item == Items.DIAMOND_CHESTPLATE ? 1 : -1;
            case Netherite -> item == Items.NETHERITE_CHESTPLATE ? 1 : -1;
            case PreferDiamond -> item == Items.DIAMOND_CHESTPLATE ? 2 : item == Items.NETHERITE_CHESTPLATE ? 1 : -1;
            case PreferNetherite -> item == Items.NETHERITE_CHESTPLATE ? 2 : item == Items.DIAMOND_CHESTPLATE ? 1 : -1;
        };
    }

    private int remainingDurability(ItemStack stack) {
        return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
    }

    public static boolean usable(ItemStack stack, boolean elytra) {
        return matchesTarget(stack, elytra) && (!stack.isDamageableItem()
            || stack.getMaxDamage() - stack.getDamageValue() > (elytra ? 1 : 0));
    }

    @Override
    public String getInfoString() { return status; }

    private boolean equip(int slot) {
        InvUtils.move().from(slot).toArmor(2);
        lastSwapPlayer = mc.player;
        lastSwapTick = mc.player.tickCount;
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
        Any,
        Diamond,
        Netherite,
        PreferDiamond,
        PreferNetherite
    }
}
