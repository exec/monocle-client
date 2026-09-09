/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.player;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import dev.monocle.client.events.entity.player.ItemUseCrosshairTargetEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.pathing.PathManagers;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.combat.AnchorAura;
import dev.monocle.client.systems.modules.combat.BedAura;
import dev.monocle.client.systems.modules.combat.CrystalAura;
import dev.monocle.client.systems.modules.combat.KillAura;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

public class AutoEat extends Module {
    @SuppressWarnings("unchecked")
    private static final Class<? extends Module>[] AURAS = new Class[]{KillAura.class, CrystalAura.class, AnchorAura.class, BedAura.class};

    // Settings groups
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgThreshold = settings.createGroup("Threshold");

    // General
    public final Setting<List<Item>> blacklist = sgGeneral.add(new ItemListSetting.Builder()
        .name("blacklist")
        .description("Which items to not eat.")
        .defaultValue(
            Items.ENCHANTED_GOLDEN_APPLE,
            Items.GOLDEN_APPLE,
            Items.CHORUS_FRUIT,
            Items.POISONOUS_POTATO,
            Items.PUFFERFISH,
            Items.CHICKEN,
            Items.ROTTEN_FLESH,
            Items.SPIDER_EYE,
            Items.SUSPICIOUS_STEW
        )
        .filter(Utils::isFood)
        .bypassFilterWhenSavingAndLoading()
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

    private final Setting<Boolean> searchInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("search-inventory")
        .description("Search the full inventory for food, not only the hotbar.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Priority> prioritise = sgGeneral.add(new EnumSetting.Builder<Priority>()
        .name("food-priority")
        .description("Which aspect of the food to prioritise selecting for.")
        .defaultValue(Priority.Saturation)
        .build()
    );

    // Threshold
    private final Setting<ThresholdMode> thresholdMode = sgThreshold.add(new EnumSetting.Builder<ThresholdMode>()
        .name("threshold-mode")
        .description("The threshold mode to trigger auto eat.\n'Both' == health AND hunger, 'Any' == health OR hunger")
        .defaultValue(ThresholdMode.Any)
        .build()
    );

    private final Setting<Double> healthThreshold = sgThreshold.add(new DoubleSetting.Builder()
        .name("health-threshold")
        .description("The level of health you eat at.")
        .defaultValue(10)
        .range(1, 19)
        .sliderRange(1, 19)
        .visible(() -> thresholdMode.get() != ThresholdMode.Hunger)
        .build()
    );

    private final Setting<Integer> hungerThreshold = sgThreshold.add(new IntSetting.Builder()
        .name("hunger-threshold")
        .description("The level of hunger you eat at.")
        .defaultValue(16)
        .range(1, 19)
        .sliderRange(1, 19)
        .visible(() -> thresholdMode.get() != ThresholdMode.Health)
        .build()
    );

    // Module state
    public boolean eating;
    private int slot, prevSlot;
    private boolean wasUsePressed;

    private final List<Class<? extends Module>> wasAura = new ReferenceArrayList<>();
    private boolean wasBaritone = false;

    public AutoEat() {
        super(Categories.Player, "auto-eat", "Automatically eats food.");
    }

    @Override
    public void onDeactivate() {
        stopEating();
    }

    /**
     * Main tick handler for the module's eating logic
     */
    @EventHandler(priority = EventPriority.LOW)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) {
            if (eating) stopEating();
            return;
        }
        if (Modules.get().get(AutoGap.class).isEating() || !thresholdReached()) {
            if (eating) stopEating();
            return;
        }

        if (!eating && mc.player.isUsingItem()) return;

        if (!eating || !canEat(stackIn(slot))) {
            int nextSlot = findSlot();
            if (nextSlot == -1 || (eating ? !changeSlot(nextSlot) : !startEating(nextSlot))) {
                if (eating) stopEating();
                return;
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
        if (wasBaritone) {
            PathManagers.get().pause();
        }

        return true;
    }

    private void eat() {
        setPressed(true);
        if (!mc.player.isUsingItem()) {
            mc.gameMode.useItem(mc.player, slot == SlotUtils.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        }
    }

    void stopEating() {
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

    /**
     * Prepares a slot for eating. Uses offhand or hotbar directly.
     * Moves a main-inventory item to an empty hotbar slot; returns false if none.
     */
    private boolean changeSlot(int slot) {
        // offhand: use directly
        if (slot == SlotUtils.OFFHAND) {
            this.slot = SlotUtils.OFFHAND;
            return true;
        }

        // hotbar: select
        if (SlotUtils.isHotbar(slot)) {
            InvUtils.swap(slot, false);
            this.slot = slot;
            return true;
        }

        // main inventory: move to empty hotbar, abort if none
        int emptySlot = InvUtils.find(ItemStack::isEmpty, SlotUtils.HOTBAR_START, SlotUtils.HOTBAR_END).slot();
        if (emptySlot == -1) return false;

        InvUtils.move().from(slot).toHotbar(emptySlot);
        InvUtils.swap(emptySlot, false);
        this.slot = emptySlot;
        return true;
    }

    public boolean shouldEat() {
        return mc.player != null && thresholdReached() && findSlot() != -1;
    }

    private boolean thresholdReached() {
        boolean healthLow = mc.player.getHealth() <= healthThreshold.get();
        boolean hungerLow = mc.player.getFoodData().getFoodLevel() <= hungerThreshold.get();
        return thresholdMode.get().test(healthLow, hungerLow);
    }

    private int findSlot() {
        int best = -1;
        float bestValue = -1;

        ItemStack offhand = mc.player.getOffhandItem();
        if (canEat(offhand)) {
            best = SlotUtils.OFFHAND;
            bestValue = prioritise.get().value(offhand.get(DataComponents.FOOD));
        }

        boolean canUseInventory = searchInventory.get()
            && InvUtils.find(ItemStack::isEmpty, SlotUtils.HOTBAR_START, SlotUtils.HOTBAR_END).found();
        int end = canUseInventory ? SlotUtils.MAIN_END : SlotUtils.HOTBAR_END;

        for (int i = SlotUtils.HOTBAR_START; i <= end; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!canEat(stack)) continue;

            float value = prioritise.get().value(stack.get(DataComponents.FOOD));
            if (value > bestValue) {
                bestValue = value;
                best = i;
            }
        }

        return best;
    }

    private boolean canEat(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food != null
            && Utils.isFood(stack)
            && !blacklist.get().contains(stack.getItem())
            && (mc.player.getFoodData().needsFood() || food.canAlwaysEat());
    }

    private ItemStack stackIn(int slot) {
        return slot == SlotUtils.OFFHAND ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(slot);
    }

    public enum ThresholdMode {
        Health,
        Hunger,
        Any,
        Both;

        public boolean test(boolean health, boolean hunger) {
            return switch (this) {
                case Health -> health;
                case Hunger -> hunger;
                case Any -> health || hunger;
                case Both -> health && hunger;
            };
        }
    }

    public enum Priority {
        Combined,
        Hunger,
        Saturation;

        public float value(FoodProperties food) {
            return switch (this) {
                case Combined -> food.nutrition() + food.saturation();
                case Hunger -> food.nutrition();
                case Saturation -> food.saturation();
            };
        }
    }
}
