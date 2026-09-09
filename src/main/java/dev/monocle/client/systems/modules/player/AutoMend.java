/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.player;

import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.settings.BoolSetting;
import dev.monocle.client.settings.ItemListSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.combat.AutoTotem;
import dev.monocle.client.systems.modules.combat.Offhand;
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

    private boolean didMove;

    public AutoMend() {
        super(Categories.Player, "auto-mend", "Automatically replaces items in your offhand with mending when fully repaired.");
    }

    @Override
    public void onActivate() {
        didMove = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        Offhand offhand = Modules.get().get(Offhand.class);
        if (mc.player == null
            || !(mc.player.containerMenu instanceof InventoryMenu)
            || !mc.player.containerMenu.getCarried().isEmpty()
            || Modules.get().get(AutoTotem.class).isLocked()
            || (offhand.isActive() && offhand.locked)
            || Modules.get().get(AutoEat.class).eating
            || Modules.get().get(AutoGap.class).isEating()) return;
        if (shouldWait()) return;

        int slot = getSlot();

        if (slot == -1) {
            if (autoDisable.get()) {
                info("Repaired all items, disabling");

                if (didMove) {
                    int emptySlot = getEmptySlot();
                    if (emptySlot != -1) InvUtils.move().fromOffhand().to(emptySlot);
                }

                toggle();
            }
        } else {
            if (!mc.player.isUsingItem()) {
                InvUtils.move().from(slot).toOffhand();
                didMove = true;
            }
        }
    }

    private boolean shouldWait() {
        ItemStack itemStack = mc.player.getOffhandItem();

        if (itemStack.isEmpty()) return false;

        if (Utils.hasEnchantments(itemStack, Enchantments.MENDING)) {
            return itemStack.isDamaged();
        }

        return !force.get();
    }

    private int getSlot() {
        for (int i = 0; i < mc.player.getInventory().getNonEquipmentItems().size(); i++) {
            ItemStack itemStack = mc.player.getInventory().getItem(i);
            if (blacklist.get().contains(itemStack.getItem())) continue;

            if (Utils.hasEnchantments(itemStack, Enchantments.MENDING) && itemStack.isDamaged()) {
                return i;
            }
        }

        return -1;
    }

    private int getEmptySlot() {
        for (int i = 0; i < mc.player.getInventory().getNonEquipmentItems().size(); i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        }

        return -1;
    }
}
