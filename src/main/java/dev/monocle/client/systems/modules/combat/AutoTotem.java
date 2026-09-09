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
import dev.monocle.client.utils.player.FindItemResult;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
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
    private int totems, ticks;

    public AutoTotem() {
        super(Categories.Combat, "auto-totem", "Automatically equips a totem in your offhand.");
    }

    @Override
    public void onActivate() {
        ticks = delay.get();
        locked = false;
    }

    @Override
    public void onDeactivate() {
        locked = false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) {
            locked = false;
            totems = 0;
            return;
        }

        if (ticks < delay.get()) ticks++;

        FindItemResult result = InvUtils.find(Items.TOTEM_OF_UNDYING);
        totems = result.count();
        locked = shouldHoldTotem();

        if (!locked || !result.found() || mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
        if (ticks < delay.get()
            || !(mc.player.containerMenu instanceof InventoryMenu)
            || !mc.player.containerMenu.getCarried().isEmpty()) return;

        InvUtils.move().from(result.slot()).toOffhand();
        ticks = 0;
    }

    private boolean shouldHoldTotem() {
        if (mode.get() == Mode.Strict) return true;

        float remainingHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount()
            - PlayerUtils.possibleHealthReductions(explosion.get(), fall.get());
        boolean flying = elytra.get()
            && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)
            && mc.player.isFallFlying();
        return remainingHealth <= health.get() || flying;
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onReceivePacket(PacketEvent.Receive event) {
        if (mc.player == null || mc.level == null) return;
        if (!(event.packet instanceof ClientboundEntityEventPacket p)) return;
        if (p.getEventId() != EntityEvent.PROTECTED_FROM_DEATH) return;

        Entity entity = p.getEntity(mc.level);
        if (entity == null || !(entity.equals(mc.player))) return;

        ticks = delay.get();
    }

    public boolean isLocked() {
        return isActive() && locked;
    }

    @Override
    public String getInfoString() {
        return String.valueOf(totems);
    }

    public enum Mode {
        Smart,
        Strict
    }
}
