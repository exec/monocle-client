/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.player;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import dev.monocle.client.events.entity.player.ItemUseCrosshairTargetEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.pathing.PathManagers;
import dev.monocle.client.settings.BoolSetting;
import dev.monocle.client.settings.IntSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.combat.AnchorAura;
import dev.monocle.client.systems.modules.combat.BedAura;
import dev.monocle.client.systems.modules.combat.CrystalAura;
import dev.monocle.client.systems.modules.combat.KillAura;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.Holder;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;

public class AutoGap extends Module {
    @SuppressWarnings("unchecked")
    private static final Class<? extends Module>[] AURAS = new Class[]{KillAura.class, CrystalAura.class, AnchorAura.class, BedAura.class};

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPotions = settings.createGroup("Potions");
    private final SettingGroup sgHealth = settings.createGroup("Health");

    // General

    private final Setting<Boolean> allowEgap = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-egap")
        .description("Allow eating E-Gaps over Gaps if found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> always = sgGeneral.add(new BoolSetting.Builder()
        .name("always")
        .description("If it should always eat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pauseAuras = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-auras")
        .description("Pauses all auras when eating.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseBaritone = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-baritone")
        .description("Pause baritone when eating.")
        .defaultValue(true)
        .build()
    );

    // Potions
    private final Setting<Boolean> beforeExpiry = sgPotions.add(new BoolSetting.Builder()
        .name("before-expiry")
        .description("If it should eat before potion effects expire.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> expiryThreshold = sgPotions.add(new IntSetting.Builder()
        .name("expiry-threshold")
        .description("Time in ticks before the potion effect expires to start eating.")
        .defaultValue(60)
        .min(0)
        .sliderMax(200)
        .visible(beforeExpiry::get)
        .build()
    );

    private final Setting<Boolean> potionsRegeneration = sgPotions.add(new BoolSetting.Builder()
        .name("potions-regeneration")
        .description("If it should eat when Regeneration runs out.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> potionsFireResistance = sgPotions.add(new BoolSetting.Builder()
        .name("potions-fire-resistance")
        .description("If it should eat when Fire Resistance runs out. Requires E-Gaps.")
        .defaultValue(true)
        .visible(allowEgap::get)
        .build()
    );

    private final Setting<Boolean> potionsAbsorption = sgPotions.add(new BoolSetting.Builder()
        .name("potions-absorption")
        .description("If it should eat when Absorption runs out. Requires E-Gaps.")
        .defaultValue(false)
        .visible(allowEgap::get)
        .build()
    );

    // Health

    private final Setting<Boolean> healthEnabled = sgHealth.add(new BoolSetting.Builder()
        .name("health-enabled")
        .description("If it should eat when health drops below threshold.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> healthThreshold = sgHealth.add(new IntSetting.Builder()
        .name("health-threshold")
        .description("Health threshold to eat at. Includes absorption.")
        .defaultValue(20)
        .min(0)
        .sliderMax(40)
        .build()
    );

    private boolean requiresEGap;

    private boolean eating;
    private int slot, prevSlot;
    private boolean wasUsePressed;

    private final List<Class<? extends Module>> wasAura = new ReferenceArrayList<>();
    private boolean wasBaritone;

    public AutoGap() {
        super(Categories.Player, "auto-gap", "Automatically eats Gaps or E-Gaps.");
    }

    @Override
    public void onDeactivate() {
        stopEating();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) {
            if (eating) stopEating();
            return;
        }

        if (!shouldEat()) {
            if (eating) stopEating();
            return;
        }

        AutoEat autoEat = Modules.get().get(AutoEat.class);
        if (!eating && mc.player.isUsingItem() && !autoEat.eating) return;

        if (!eating || !isSuitable(stackIn(slot))) {
            int nextSlot = findSlot();
            if (nextSlot == -1) {
                if (eating) stopEating();
                return;
            }

            if (eating) {
                if (!changeSlot(nextSlot)) {
                    stopEating();
                    return;
                }
            } else {
                autoEat.stopEating();
                if (!startEating(nextSlot)) return;
            }
        }

        eat();
    }

    @EventHandler
    private void onItemUseCrosshairTarget(ItemUseCrosshairTargetEvent event) {
        if (eating) event.target = null;
    }

    private boolean startEating(int slot) {
        prevSlot = mc.player.getInventory().getSelectedSlot();
        wasUsePressed = mc.options.keyUse.isDown();
        if (!changeSlot(slot)) return false;

        eating = true;

        wasAura.clear();
        if (pauseAuras.get()) {
            for (Class<? extends Module> klass : AURAS) {
                Module module = Modules.get().get(klass);

                if (module.isActive()) {
                    wasAura.add(klass);
                    module.toggle();
                }
            }
        }

        wasBaritone = pauseBaritone.get() && PathManagers.get().isPathing();
        if (wasBaritone) PathManagers.get().pause();

        return true;
    }

    private void eat() {
        setPressed(true);
        if (!mc.player.isUsingItem()) {
            mc.gameMode.useItem(mc.player, slot == SlotUtils.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        }
    }

    private void stopEating() {
        if (!eating && wasAura.isEmpty() && !wasBaritone) return;

        if (mc.player != null && SlotUtils.isHotbar(prevSlot)) InvUtils.swap(prevSlot, false);
        setPressed(wasUsePressed);

        eating = false;
        slot = -1;
        prevSlot = -1;

        for (Class<? extends Module> klass : wasAura) {
            Modules.get().get(klass).enable();
        }
        wasAura.clear();

        if (wasBaritone) PathManagers.get().resume();
        wasBaritone = false;
    }

    private void setPressed(boolean pressed) {
        mc.options.keyUse.setDown(pressed);
    }

    private boolean changeSlot(int slot) {
        if (slot != SlotUtils.OFFHAND && !InvUtils.swap(slot, false)) return false;
        this.slot = slot;
        return true;
    }

    private boolean shouldEat() {
        requiresEGap = false;

        if (always.get()) return true;
        if (shouldEatPotions()) return true;
        return shouldEatHealth();
    }

    private boolean shouldEatPotions() {
        Map<Holder<MobEffect>, MobEffectInstance> effects = mc.player.getActiveEffectsMap();
        boolean shouldEat = false;

        // Regeneration
        if (potionsRegeneration.get()) {
            MobEffectInstance effect = effects.get(MobEffects.REGENERATION);
            shouldEat = effect == null || (beforeExpiry.get() && effect.getDuration() <= expiryThreshold.get());
        }

        // Fire resistance
        if (allowEgap.get() && potionsFireResistance.get()) {
            MobEffectInstance effect = effects.get(MobEffects.FIRE_RESISTANCE);
            if (effect == null || (beforeExpiry.get() && effect.getDuration() <= expiryThreshold.get())) {
                requiresEGap = true;
                shouldEat = true;
            }
        }

        // Absorption
        if (allowEgap.get() && potionsAbsorption.get()) {
            MobEffectInstance effect = effects.get(MobEffects.ABSORPTION);
            if (effect == null || (beforeExpiry.get() && effect.getDuration() <= expiryThreshold.get())) {
                requiresEGap = true;
                shouldEat = true;
            }
        }

        return shouldEat;
    }

    private boolean shouldEatHealth() {
        if (!healthEnabled.get()) return false;

        int health = Math.round(mc.player.getHealth() + mc.player.getAbsorptionAmount());
        return health < healthThreshold.get();
    }

    private int findSlot() {
        int enchanted = mc.player.getOffhandItem().is(Items.ENCHANTED_GOLDEN_APPLE) && allowEgap.get()
            ? SlotUtils.OFFHAND
            : -1;

        if (!requiresEGap && mc.player.getOffhandItem().is(Items.GOLDEN_APPLE)) return SlotUtils.OFFHAND;

        for (int i = SlotUtils.HOTBAR_START; i <= SlotUtils.HOTBAR_END; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!requiresEGap && stack.is(Items.GOLDEN_APPLE)) return i;
            if (enchanted == -1 && allowEgap.get() && stack.is(Items.ENCHANTED_GOLDEN_APPLE)) enchanted = i;
        }

        return enchanted;
    }

    private boolean isSuitable(ItemStack stack) {
        Item item = stack.getItem();
        return item == Items.ENCHANTED_GOLDEN_APPLE && allowEgap.get()
            || item == Items.GOLDEN_APPLE && !requiresEGap;
    }

    private ItemStack stackIn(int slot) {
        return slot == SlotUtils.OFFHAND ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(slot);
    }

    public boolean isEating() {
        return isActive() && eating;
    }
}
