/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.player;

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
import net.minecraft.world.Container;

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
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> foodSlot = sgGeneral.add(new IntSetting.Builder()
        .name("food-hotbar-slot").description("Use an empty hotbar slot first; otherwise swap food into this slot. The displaced stack moves into the food's old inventory slot and stays there. Nothing is dropped.")
        .defaultValue(9).range(1, 9).sliderRange(1, 9).visible(searchInventory::get).build()
    );

    private final Setting<Boolean> protectNamed = sgGeneral.add(new BoolSetting.Builder()
        .name("protect-named-food").description("Do not automatically eat food with a custom name, in addition to the blacklist.")
        .defaultValue(true).build()
    );

    private final Setting<Priority> prioritise = sgGeneral.add(new EnumSetting.Builder<Priority>()
        .name("food-priority")
        .description("Choose nutrition, saturation or LeastWaste. LeastWaste favors food that fits the missing hunger; low health favors saturation instead.")
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
    private int slot = -1, prevSlot = -1, retryTicks, eatingTicks, lastFoodCount, lastHunger;
    private boolean wasUsePressed;
    private String status = "Idle";

    private final java.util.Map<Module, Long> wasAura = new java.util.HashMap<>();
    private Object eatingWorld;
    private ItemStack eatingStack = ItemStack.EMPTY;
    private boolean wasBaritone = false;

    public AutoEat() {
        super(Categories.Player, "auto-eat", "Food management for travel and building: full-inventory supplies, protected food and hunger-aware selection.");
    }

    @Override public void onActivate() { retryTicks = eatingTicks = 0; status = "Ready"; }

    @Override
    public void onDeactivate() {
        stopEating();
        retryTicks = 0;
        status = "Inactive";
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
        if (retryTicks > 0) { retryTicks--; return; }
        if (Modules.get().get(AutoGap.class).isEating() || !thresholdReached()) {
            if (eating) stopEating();
            status = Modules.get().get(AutoGap.class).isEating() ? "Yielding to Auto Gap" : "Ready";
            return;
        }

        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.containerMenu.getCarried().isEmpty() || mc.gui.screen() != null) {
            if (eating) stopEating();
            status = "Waiting for inventory / screen";
            return;
        }
        if (eating && slot != SlotUtils.OFFHAND && mc.player.getInventory().getSelectedSlot() != slot) {
            stopEating();
            retryTicks = 20;
            status = "Yielding to slot change";
            return;
        }

        if (!eating && mc.player.isUsingItem()) return;

        if (!eating || !canEat(stackIn(slot))) {
            int nextSlot = findSlot();
            if (nextSlot == -1 || (eating ? !changeSlot(nextSlot) : !startEating(nextSlot))) {
                if (eating) stopEating();
                status = "No usable food available";
                return;
            }
            eatingTicks = 0;
        }

        int foodCount = stackIn(slot).getCount(), hunger = mc.player.getFoodData().getFoodLevel();
        if (foodCount != lastFoodCount || hunger != lastHunger) eatingTicks = 0;
        lastFoodCount = foodCount;
        lastHunger = hunger;
        if (++eatingTicks >= 100) {
            stopEating();
            retryTicks = 40;
            status = "Eating stalled; retrying shortly";
            return;
        }

        status = "Eating " + stackIn(slot).getHoverName().getString();
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
        eatingWorld = mc.level;
        eatingTicks = 0;
        lastFoodCount = stackIn(this.slot).getCount();
        lastHunger = mc.player.getFoodData().getFoodLevel();

        wasAura.clear();
        if (pauseAuras.get()) {
            for (Class<? extends Module> klass : AURAS) {
                Module module = Modules.get().get(klass);

                if (module.isActive()) {
                    module.toggle();
                    wasAura.put(module, module.activationRevision());
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

        if (mc.player != null && mc.level == eatingWorld && ItemStack.isSameItemSameComponents(stackIn(slot), eatingStack)
            && (slot == SlotUtils.OFFHAND || mc.player.getInventory().getSelectedSlot() == slot)) {
            if (mc.player.isUsingItem() && mc.player.getUsedItemHand() == (slot == SlotUtils.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND)
                && mc.player.getUseItem().get(DataComponents.FOOD) != null) mc.gameMode.releaseUsingItem(mc.player);
            if (slot != SlotUtils.OFFHAND && SlotUtils.isHotbar(prevSlot)) InvUtils.swap(prevSlot, false);
        }
        setPressed(wasUsePressed);

        eating = false;
        slot = -1;
        prevSlot = -1;

        wasAura.forEach((module, revision) -> {
            if (mc.level == eatingWorld && !module.isActive() && module.activationRevision() == revision) module.enable();
        });
        wasAura.clear();

        if (wasBaritone) PathManagers.get().resume();
        wasBaritone = false;
    }

    private void setPressed(boolean pressed) {
        mc.options.keyUse.setDown(pressed);
    }

    /**
     * Prepares a slot for eating. Uses offhand or hotbar directly.
     * Uses a cursor-free native hotbar swap for inventory food. Displaced stacks stay in inventory.
     */
    private boolean changeSlot(int slot) {
        // offhand: use directly
        if (slot == SlotUtils.OFFHAND) {
            this.slot = SlotUtils.OFFHAND;
            eatingStack = stackIn(slot).copy();
            return true;
        }

        // hotbar: select
        if (SlotUtils.isHotbar(slot)) {
            InvUtils.swap(slot, false);
            this.slot = slot;
            eatingStack = stackIn(slot).copy();
            return true;
        }

        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.containerMenu.getCarried().isEmpty()) return false;
        int target = foodHotbarSlot(mc.player.getInventory(), foodSlot.get() - 1);
        ItemStack food = mc.player.getInventory().getItem(slot).copy();
        // quickSwap's source is the hotbar button (0–8), not a container slot ID.
        InvUtils.quickSwap().fromId(target).to(slot);
        if (!ItemStack.isSameItemSameComponents(food, mc.player.getInventory().getItem(target))) return false;
        InvUtils.swap(target, false);
        this.slot = target;
        eatingStack = stackIn(target).copy();
        return true;
    }

    public boolean shouldEat() {
        return mc.player != null && retryTicks == 0 && mc.gui.screen() == null
            && mc.player.containerMenu == mc.player.inventoryMenu && mc.player.containerMenu.getCarried().isEmpty()
            && thresholdReached() && findSlot() != -1;
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
            bestValue = score(offhand.get(DataComponents.FOOD));
        }

        int end = searchInventory.get() ? SlotUtils.MAIN_END : SlotUtils.HOTBAR_END;

        for (int i = SlotUtils.HOTBAR_START; i <= end; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!canEat(stack)) continue;

            float value = score(stack.get(DataComponents.FOOD));
            if (best < 0 || value > bestValue) {
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
            && foodAllowed(stack, blacklist.get(), protectNamed.get())
            && (mc.player.getFoodData().needsFood() || food.canAlwaysEat());
    }

    private ItemStack stackIn(int slot) {
        return slot == SlotUtils.OFFHAND ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(slot);
    }

    private float score(FoodProperties food) {
        return foodScore(food, prioritise.get(), 20 - mc.player.getFoodData().getFoodLevel(), mc.player.getHealth() <= healthThreshold.get());
    }

    static int foodHotbarSlot(Container inventory, int preferred) {
        if (preferred < 0 || preferred > 8) throw new IllegalArgumentException("Invalid food hotbar slot");
        for (int i = 0; i < 9; i++) if (inventory.getItem(i).isEmpty()) return i;
        return preferred;
    }

    static boolean foodAllowed(ItemStack stack, List<Item> blacklist, boolean protectNamed) {
        return !blacklist.contains(stack.getItem()) && (!protectNamed || !stack.has(DataComponents.CUSTOM_NAME));
    }

    static float foodScore(FoodProperties food, Priority priority, int missingHunger, boolean lowHealth) {
        if (priority != Priority.LeastWaste) return priority.value(food);
        return lowHealth ? food.saturation() : food.saturation() - Math.max(0, food.nutrition() - missingHunger) * 100;
    }

    @Override public String getInfoString() { return status; }

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
        Saturation,
        LeastWaste;

        public float value(FoodProperties food) {
            return switch (this) {
                case Combined -> food.nutrition() + food.saturation();
                case Hunger -> food.nutrition();
                case Saturation -> food.saturation();
                case LeastWaste -> food.saturation();
            };
        }
    }
}
