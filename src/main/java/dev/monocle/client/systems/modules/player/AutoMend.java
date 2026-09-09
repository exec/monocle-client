/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.player;

import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.settings.BoolSetting;
import dev.monocle.client.settings.ItemListSetting;
import dev.monocle.client.settings.IntSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.combat.AutoTotem;
import dev.monocle.client.systems.modules.combat.Offhand;
import dev.monocle.client.systems.modules.combat.KillAura;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.client.systems.modules.world.PrinterHelper;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;

public class AutoMend extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<Item>> blacklist = sgGeneral.add(new ItemListSetting.Builder()
        .name("blacklist")
        .description("Item blacklist.")
        .filter(item -> item.components().get(DataComponents.DAMAGE) != null)
        .bypassFilterWhenSavingAndLoading()
        .build()
    );

    private final Setting<Boolean> force = sgGeneral.add(new BoolSetting.Builder()
        .name("force")
        .description("Replaces item in offhand even if there is some other non-repairable item.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Automatically disables when there are no more items to repair.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> target = sgGeneral.add(new IntSetting.Builder()
        .name("target-durability").description("Repair carried Mending gear to this durability percentage.")
        .defaultValue(95).range(1, 100).sliderRange(1, 100).build());

    private int source = -1, previousDamage = -1, idleTicks, queued;
    private long repaired;
    private ItemStack original = ItemStack.EMPTY, repairing = ItemStack.EMPTY;
    private Object owner;
    private String status = "Ready";

    public AutoMend() {
        super(Categories.Player, "auto-mend", "Repairs the most damaged carried Mending gear first, preserving your offhand when possible.");
    }

    @Override
    public void onActivate() {
        clearOwnership();
        repaired = 0;
        idleTicks = queued = 0;
        status = "Ready";
    }

    @Override
    public void onDeactivate() {
        if (source >= 0 && !restore()) warning("Offhand restoration deferred to you: inventory changed or is busy. Original item was left in slot %d.", source + 1);
        clearOwnership();
    }

    private boolean inventoryReady() {
        return mc.player != null && mc.gui.screen() == null
            && mc.player.containerMenu instanceof InventoryMenu
            && mc.player.containerMenu.getCarried().isEmpty() && !mc.player.isUsingItem();
    }

    private boolean safetyBusy() {
        Offhand offhand = Modules.get().get(Offhand.class);
        AutoEat eat = Modules.get().get(AutoEat.class);
        return Modules.get().get(AutoTotem.class).isLocked()
            || (offhand.isActive() && offhand.locked)
            || eat.eating || eat.isActive() && eat.shouldEat()
            || Modules.get().get(AutoGap.class).isEating()
            || Modules.get().get(KillAura.class).attacking;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!inventoryReady()) { status = "Waiting for inventory / item use"; return; }
        if (source >= 0 && (owner != mc.player || !ownsPair())) {
            warning("Repair handoff changed; leaving inventory untouched. Check your original offhand item.");
            clearOwnership();
            toggle();
            return;
        }
        if (safetyBusy()) { status = "Yielding to safety / food / combat"; return; }
        if (Modules.get().isActive(HighwayBuilder.class)
            || Modules.get().get(PrinterHelper.class).controlsInventory() || mc.player.isFallFlying()) {
            status = "Waiting for building / flight to stop";
            return;
        }
        queued = 0;
        int best = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!eligible(stack)) continue;
            queued++;
            if (best == -1 || durability(stack) < durability(mc.player.getInventory().getItem(best))) best = i;
        }
        ItemStack held = mc.player.getOffhandItem();
        if (eligible(held)) {
            if (!sameRepairItem(held, repairing)) {
                repairing = held.copy();
                previousDamage = held.getDamageValue();
                idleTicks = 0;
            }
            int damage = held.getDamageValue();
            if (previousDamage >= 0 && damage < previousDamage) { repaired += previousDamage - damage; idleTicks = 0; }
            else idleTicks = Math.min(200, idleTicks + 1);
            previousDamage = damage;
            status = (idleTicks >= 100 ? "Waiting for repair XP: " : "Mending: ")
                + held.getHoverName().getString() + " " + (int) durability(held) + "% / " + target.get() + "%";
            return;
        }
        if (previousDamage >= 0 && sameRepairItem(held, repairing)) repaired += Math.max(0, previousDamage - held.getDamageValue());
        previousDamage = -1;
        if (source >= 0) {
            if (restore()) clearOwnership();
            else status = "Waiting to restore offhand";
            return;
        }
        if (best == -1) {
            status = "Target reached";
            if (autoDisable.get()) { info("Carried gear reached the repair target; disabling."); toggle(); }
            return;
        }
        if (!held.isEmpty() && !force.get() && (blacklist.get().contains(held.getItem())
            || !Utils.hasEnchantments(held, Enchantments.MENDING))) { status = "Clear offhand or enable Force"; return; }
        source = best;
        owner = mc.player;
        original = held.copy();
        repairing = mc.player.getInventory().getItem(source).copy();
        previousDamage = repairing.getDamageValue();
        idleTicks = 0;
        swapOffhand(source);
        status = "Preparing " + repairing.getHoverName().getString();
    }

    private boolean eligible(ItemStack stack) {
        return belowTarget(stack, target.get()) && !blacklist.get().contains(stack.getItem())
            && Utils.hasEnchantments(stack, Enchantments.MENDING);
    }

    static double durability(ItemStack stack) {
        return stack.getMaxDamage() <= 0 ? 100 : 100.0 * (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage();
    }

    static boolean belowTarget(ItemStack stack, int target) {
        return stack.isDamaged() && durability(stack) < target;
    }

    static boolean sameRepairItem(ItemStack current, ItemStack expected) {
        if (current.isEmpty() || expected.isEmpty() || current.getCount() != expected.getCount()) return false;
        ItemStack normalized = current.copy();
        normalized.setDamageValue(expected.getDamageValue());
        return ItemStack.isSameItemSameComponents(normalized, expected);
    }

    private boolean ownsPair() {
        return sameRepairItem(mc.player.getOffhandItem(), repairing)
            && ItemStack.matches(mc.player.getInventory().getItem(source), original);
    }

    public boolean reservesSlot(int slot) {
        return isActive() && owner == mc.player && source == slot;
    }

    private boolean restore() {
        if (!inventoryReady() || owner != mc.player || safetyBusy() || !ownsPair()) return false;
        swapOffhand(source);
        return true;
    }

    private void swapOffhand(int slot) {
        // Native SWAP button 40 exchanges with the offhand without touching the cursor.
        InvUtils.quickSwap().fromId(40).to(slot);
    }

    private void clearOwnership() {
        source = -1;
        owner = null;
        original = repairing = ItemStack.EMPTY;
        previousDamage = -1;
    }

    @Override
    public String getInfoString() { return status + " · " + queued + " queued · " + repaired + " durability repaired"; }
}
