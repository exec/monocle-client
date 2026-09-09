/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.combat;

import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.player.AutoEat;
import dev.monocle.client.systems.modules.player.AutoGap;
import dev.monocle.client.systems.modules.player.AutoMend;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.player.FindItemResult;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Items;

import static meteordevelopment.orbit.EventPriority.HIGHEST;

public class Offhand extends Module {
    private final SettingGroup sgCombat = settings.createGroup("Combat");
    private final SettingGroup sgTotem = settings.createGroup("Totem");

    //Combat

    private final Setting<Integer> delayTicks = sgCombat.add(new IntSetting.Builder()
        .name("item-switch-delay")
        .description("The delay in ticks between slot movements.")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );
    private final Setting<Item> preferredItem = sgCombat.add(new EnumSetting.Builder<Item>()
        .name("item")
        .description("Which item to hold in your offhand.")
        .defaultValue(Item.Crystal)
        .build()
    );

    private final Setting<Boolean> hotbar = sgCombat.add(new BoolSetting.Builder()
        .name("hotbar")
        .description("Whether to use items from your hotbar.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rightGapple = sgCombat.add(new BoolSetting.Builder()
        .name("right-gapple")
        .description("Will switch to a gapple when holding right click.(DO NOT USE WITH POTION ON)")
        .defaultValue(false)
        .build()
    );


    private final Setting<Boolean> swordGap = sgCombat.add(new BoolSetting.Builder()
        .name("sword-gapple")
        .description("Will switch to a gapple when holding a sword and right click.")
        .defaultValue(false)
        .visible(rightGapple::get)
        .build()
    );

    private final Setting<Boolean> alwaysSwordGap = sgCombat.add(new BoolSetting.Builder()
        .name("always-gap-on-sword")
        .description("Holds an Enchanted Golden Apple when you are holding a sword.")
        .defaultValue(false)
        .visible(() -> !rightGapple.get())
        .build()
    );


    private final Setting<Boolean> alwaysPot = sgCombat.add(new BoolSetting.Builder()
        .name("always-pot-on-sword")
        .description("Will switch to a potion when holding a sword")
        .defaultValue(false)
        .visible(() -> !rightGapple.get() && !alwaysSwordGap.get())
        .build()
    );
    private final Setting<Boolean> potionClick = sgCombat.add(new BoolSetting.Builder()
        .name("sword-pot")
        .description("Will switch to a potion when holding a sword and right click.")
        .defaultValue(false)
        .visible(() -> !rightGapple.get() && !alwaysPot.get() && !alwaysSwordGap.get())
        .build()
    );

    //Totem

    private final Setting<Double> minHealth = sgTotem.add(new DoubleSetting.Builder()
        .name("min-health")
        .description("Will hold a totem when below this amount of health.")
        .defaultValue(10)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final Setting<Boolean> elytra = sgTotem.add(new BoolSetting.Builder()
        .name("elytra")
        .description("Will always hold a totem while flying with an elytra.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> falling = sgTotem.add(new BoolSetting.Builder()
        .name("falling")
        .description("Will hold a totem if fall damage could kill you.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> explosion = sgTotem.add(new BoolSetting.Builder()
        .name("explosion")
        .description("Will hold a totem when explosion damage could kill you.")
        .defaultValue(true)
        .build()
    );


    private boolean sentMessage;

    public boolean locked;

    private int totems, ticks;

    public Offhand() {
        super(Categories.Combat, "offhand", "Allows you to hold specified items in your offhand.");
    }

    @Override
    public void onActivate() {
        ticks = delayTicks.get();
        sentMessage = false;
        locked = false;
    }

    @Override
    public void onDeactivate() {
        locked = false;
    }

    @EventHandler(priority = HIGHEST + 999)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) {
            locked = false;
            totems = 0;
            return;
        }

        if (ticks < delayTicks.get()) ticks++;

        FindItemResult totem = InvUtils.find(Items.TOTEM_OF_UNDYING);
        totems = totem.count();
        AutoTotem autoTotem = Modules.get().get(AutoTotem.class);
        locked = totem.found() && shouldHoldTotem();
        Item desired = locked ? Item.Totem : desiredItem(autoTotem);

        if (!locked && (Modules.get().get(AutoEat.class).eating
            || Modules.get().get(AutoGap.class).isEating()
            || Modules.get().isActive(AutoMend.class))) return;
        if (mc.player.getOffhandItem().is(desired.item)) {
            sentMessage = false;
            return;
        }

        if (!locked && autoTotem.isLocked()) return;
        if (ticks < delayTicks.get()
            || !(mc.player.containerMenu instanceof InventoryMenu)
            || !mc.player.containerMenu.getCarried().isEmpty()) return;

        FindItemResult item = locked
            ? totem
            : InvUtils.find(stack -> stack.is(desired.item), hotbar.get() ? 0 : 9, 35);

        if (!item.found()) {
            if (!sentMessage) {
                warning("Chosen item not found.");
                sentMessage = true;
            }
            return;
        }

        InvUtils.move().from(item.slot()).toOffhand();
        sentMessage = false;
        ticks = 0;
    }

    private boolean shouldHoldTotem() {
        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        boolean low = health - PlayerUtils.possibleHealthReductions(explosion.get(), falling.get()) <= minHealth.get();
        boolean flying = elytra.get()
            && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)
            && mc.player.isFallFlying();
        return low || flying;
    }

    private Item desiredItem(AutoTotem autoTotem) {
        boolean sword = mc.player.getMainHandItem().is(ItemTags.SWORDS);
        boolean weapon = sword || mc.player.getMainHandItem().getItem() instanceof AxeItem;
        boolean clicking = mc.gui.screen() == null
            && mc.options.keyUse.isDown()
            && !autoTotem.isLocked()
            && !usableItem()
            && !mc.player.isUsingItem();

        if (rightGapple.get()) return clicking && (!swordGap.get() || sword) ? Item.EGap : preferredItem.get();
        if (alwaysSwordGap.get() && weapon) return Item.EGap;
        if (potionClick.get() && clicking && sword) return Item.Potion;
        if (alwaysPot.get() && weapon) return Item.Potion;
        return preferredItem.get();
    }

    private boolean usableItem() {
        // What counts as a Usable Item
        return mc.player.getMainHandItem().getItem() == Items.BOW
            || mc.player.getMainHandItem().getItem() == Items.TRIDENT
            || mc.player.getMainHandItem().getItem() == Items.CROSSBOW
            || Utils.isFood(mc.player.getMainHandItem());
    }

    @Override
    public String getInfoString() {
        return preferredItem.get().name();
    }

    public enum Item {
        // Items the module could put on your offhand
        EGap(Items.ENCHANTED_GOLDEN_APPLE),
        Gap(Items.GOLDEN_APPLE),
        Crystal(Items.END_CRYSTAL),
        Totem(Items.TOTEM_OF_UNDYING),
        Shield(Items.SHIELD),
        Potion(Items.POTION);
        final net.minecraft.world.item.Item item;

        Item(net.minecraft.world.item.Item item) {
            this.item = item;
        }
    }

}
