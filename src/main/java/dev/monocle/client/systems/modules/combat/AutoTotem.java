/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.combat;

import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.PlayerUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.component.DataComponents;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Items;

public class AutoTotem extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Determines when to hold a totem, strict will always hold.")
        .defaultValue(Mode.Smart)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("The ticks between slot movements.")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private final Setting<Integer> health = sgGeneral.add(new IntSetting.Builder()
        .name("health")
        .description("The health to hold a totem at.")
        .defaultValue(10)
        .range(0, 36)
        .sliderMax(36)
        .visible(() -> mode.get() == Mode.Smart)
        .build()
    );

    private final Setting<Boolean> elytra = sgGeneral.add(new BoolSetting.Builder()
        .name("elytra")
        .description("Will always hold a totem when flying with elytra.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Smart)
        .build()
    );

    private final Setting<Boolean> fall = sgGeneral.add(new BoolSetting.Builder()
        .name("fall")
        .description("Will hold a totem when fall damage could kill you.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Smart)
        .build()
    );

    private final Setting<Boolean> explosion = sgGeneral.add(new BoolSetting.Builder()
        .name("explosion")
        .description("Will hold a totem when explosion damage could kill you.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Smart)
        .build()
    );

    public boolean locked;
    private final Setting<Integer> releaseDelay = sgGeneral.add(new IntSetting.Builder()
        .name("safe-hold-ticks").description("Keep Smart mode's offhand lock briefly after danger passes, avoiding rapid swaps.")
        .defaultValue(10).range(0, 100).visible(() -> mode.get() == Mode.Smart).build());
    private final Setting<Integer> reserveWarning = sgGeneral.add(new IntSetting.Builder()
        .name("reserve-warning").description("Warn when carried totems, including offhand, fall to this count. Zero disables warnings.")
        .defaultValue(2).range(0, 36).build());
    private int safeTicks;
    private boolean warned;
    private String status = "Ready";
    private int totems, ticks;

    public AutoTotem() {
        super(Categories.Combat, "auto-totem", "Automatically equips a totem in your offhand.");
    }

    @Override
    public void onActivate() {
        ticks = delay.get();
        locked = false;
        safeTicks = 0;
        warned = false;
    }

    @Override
    public void onDeactivate() {
        locked = false;
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1000)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || !mc.player.isAlive()) {
            locked = false;
            safeTicks = 0;
            totems = 0;
            return;
        }

        if (ticks < delay.get()) ticks++;

        totems = carriedTotems(mc.player.getInventory(), mc.player.getOffhandItem());
        boolean danger = shouldHoldTotem();
        safeTicks = danger ? releaseDelay.get() : Math.max(0, safeTicks - 1);
        locked = danger || safeTicks > 0;
        if (reserveWarning.get() > 0 && totems <= reserveWarning.get()) {
            if (!warned) warning("Totem reserve low: %d carried (including offhand).", totems);
            warned = true;
        } else warned = false;
        status = !locked ? "Standby" : mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) ? "Protected" : "Needs totem";

        if (!locked || mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
        if (totems == 0) { status = "No totems"; return; }
        if (ticks < delay.get()) { status = "Waiting for swap delay"; return; }
        if (!(mc.player.containerMenu instanceof InventoryMenu)
            && !dev.monocle.client.systems.modules.misc.InventoryTweaks.storageMenu(mc.player.containerMenu)) {
            status = "Open player inventory to equip";
            return;
        }

        int slot = sourceSlot(mc.player.containerMenu, mc.player.getInventory(), mc.player.getOffhandItem(), mc.player);
        if (slot < 0) { status = "No accessible totem slot"; return; }
        if (mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.OFF_HAND) mc.gameMode.releaseUsingItem(mc.player);
        // Native offhand SWAP preserves the cursor and returns the displaced item to the source.
        InvUtils.quickSwap().fromId(40).toId(slot);
        status = "Equipping";
        ticks = 0;
    }

    public boolean needsInventory() {
        return isLocked() && mc.player != null && !mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) && totems > 0;
    }

    public static int carriedTotems(Container inventory, ItemStack offhand) {
        int count = offhand.is(Items.TOTEM_OF_UNDYING) ? offhand.getCount() : 0;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(Items.TOTEM_OF_UNDYING)) count += stack.getCount();
        }
        return count;
    }

    public static int sourceSlot(net.minecraft.world.inventory.AbstractContainerMenu menu, Container inventory,
                                 ItemStack displaced, net.minecraft.world.entity.player.Player player) {
        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.getSlot(i);
            if (slot.container == inventory && slot.getContainerSlot() >= 0 && slot.getContainerSlot() < 36
                && slot.isActive() && !slot.isFake() && slot.mayPickup(player) && slot.getItem().is(Items.TOTEM_OF_UNDYING)
                && (displaced.isEmpty() || slot.mayPlace(displaced) && slot.getMaxStackSize(displaced) >= displaced.getCount())) return i;
        }
        return -1;
    }

    private boolean shouldHoldTotem() {
        if (mode.get() == Mode.Strict) return true;

        float remainingHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount()
            - PlayerUtils.possibleHealthReductions(explosion.get(), fall.get());
        boolean flying = elytra.get()
            && mc.player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)
            && mc.player.isFallFlying();
        return remainingHealth <= health.get() || flying;
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onReceivePacket(PacketEvent.Receive event) {
        if (mc.player == null || mc.level == null) return;
        if (!(event.packet instanceof ClientboundEntityEventPacket p)) return;
        if (p.getEventId() != EntityEvent.PROTECTED_FROM_DEATH) return;

        var player = mc.player;
        var world = mc.level;
        long revision = activationRevision();
        mc.execute(() -> {
            if (!isActive() || activationRevision() != revision || mc.player != player || mc.level != world) return;
            if (p.getEntity(world) != player) return;
            ticks = delay.get();
            safeTicks = releaseDelay.get();
        });
    }

    public boolean isLocked() {
        return isActive() && locked;
    }

    @Override
    public String getInfoString() {
        return status + " · " + totems + " totems";
    }

    public enum Mode {
        Smart,
        Strict
    }
}
