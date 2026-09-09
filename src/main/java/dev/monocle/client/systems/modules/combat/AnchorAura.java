/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.combat;

import dev.monocle.client.events.render.Render3DEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.renderer.ShapeMode;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.utils.entity.DamageUtils;
import dev.monocle.client.utils.entity.EntityUtils;
import dev.monocle.client.utils.entity.SortPriority;
import dev.monocle.client.utils.entity.TargetUtils;
import dev.monocle.client.utils.player.FindItemResult;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.PlayerUtils;
import dev.monocle.client.utils.player.Rotations;
import dev.monocle.client.utils.render.color.SettingColor;
import dev.monocle.client.utils.world.BlockIterator;
import dev.monocle.client.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class AnchorAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlace = settings.createGroup("Place");
    private final SettingGroup sgBreak = settings.createGroup("Break");
    private final SettingGroup sgPause = settings.createGroup("Pause");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Range in which to target players.")
        .defaultValue(10)
        .min(0)
        .sliderMax(16)
        .build()
    );

    private final Setting<SortPriority> targetPriority = sgGeneral.add(new EnumSetting.Builder<SortPriority>()
        .name("target-priority")
        .description("How to select the player to target.")
        .defaultValue(SortPriority.LowestHealth)
        .build()
    );

    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("The minimum damage to inflict on your target.")
        .defaultValue(7)
        .min(0)
        .sliderMax(36)
        .build()
    );

    private final Setting<Double> maxSelfDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-self-damage")
        .description("The maximum damage to inflict on yourself.")
        .defaultValue(7)
        .min(0)
        .sliderMax(36)
        .build()
    );

    private final Setting<Boolean> antiSuicide = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-suicide")
        .description("Will not place and break anchors if they will kill you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Switches to your previous slot after using anchors.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates server-side towards the anchors being placed/broken.")
        .defaultValue(true)
        .build()
    );

    // Place

    private final Setting<Boolean> place = sgPlace.add(new BoolSetting.Builder()
        .name("place")
        .description("Allows Anchor Aura to place anchors.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> placeDelay = sgPlace.add(new IntSetting.Builder()
        .name("place-delay")
        .description("The tick delay between placing anchors.")
        .defaultValue(5)
        .range(0, 10)
        .visible(place::get)
        .build()
    );

    private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("The range at which anchors can be placed.")
        .defaultValue(4)
        .range(0, 6)
        .visible(place::get)
        .build()
    );

    private final Setting<Double> placeWallsRange = sgPlace.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("Range in which to place anchors when behind blocks.")
        .defaultValue(4)
        .range(0, 6)
        .visible(place::get)
        .build()
    );

    private final Setting<Boolean> airPlace = sgPlace.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Allows Anchor Aura to place anchors in the air.")
        .defaultValue(true)
        .visible(place::get)
        .build()
    );

    // Break

    private final Setting<Integer> chargeDelay = sgBreak.add(new IntSetting.Builder()
        .name("charge-delay")
        .description("The tick delay it takes to charge anchors.")
        .defaultValue(1)
        .range(0, 10)
        .build()
    );

    private final Setting<Integer> breakDelay = sgBreak.add(new IntSetting.Builder()
        .name("break-delay")
        .description("The tick delay it takes to break anchors.")
        .defaultValue(1)
        .range(0, 10)
        .build()
    );

    private final Setting<Double> breakRange = sgBreak.add(new DoubleSetting.Builder()
        .name("break-range")
        .description("Range in which to break anchors.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> breakWallsRange = sgBreak.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("Range in which to break anchors when behind blocks.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    // Pause

    private final Setting<Boolean> pauseOnUse = sgPause.add(new BoolSetting.Builder()
        .name("pause-on-use")
        .description("Pauses while using an item.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnMine = sgPause.add(new BoolSetting.Builder()
        .name("pause-on-mine")
        .description("Pauses while mining blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnCA = sgPause.add(new BoolSetting.Builder()
        .name("pause-on-CA")
        .description("Pauses while Crystal Aura is placing.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<Boolean> swing = sgRender.add(new BoolSetting.Builder()
        .name("swing")
        .description("Whether to swing your hand client-side.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the block where it is placing an anchor.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color for positions to be placed.")
        .defaultValue(new SettingColor(15, 255, 211, 41))
        .visible(() -> render.get() && shapeMode.get().sides())
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color for positions to be placed.")
        .defaultValue(new SettingColor(15, 255, 211))
        .visible(() -> render.get() && shapeMode.get().lines())
        .build()
    );

    private float bestPlaceDamage;
    private final BlockPos.MutableBlockPos bestPlacePos = new BlockPos.MutableBlockPos();

    private float bestBreakDamage;
    private final BlockPos.MutableBlockPos bestBreakPos = new BlockPos.MutableBlockPos();

    private BlockPos renderBlockPos;
    private int placeDelayLeft, chargeDelayLeft, breakDelayLeft;
    private Player target;

    public AnchorAura() {
        super(Categories.Combat, "anchor-aura", "Automatically places and breaks Respawn Anchors to harm entities.");
    }

    @Override
    public void onActivate() {
        renderBlockPos = null;
        placeDelayLeft = placeDelay.get();
        chargeDelayLeft = 0;
        breakDelayLeft = 0;
        target = null;
    }

    @Override
    public void onDeactivate() {
        renderBlockPos = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.level.dimension().equals(Level.NETHER)) {
            error("You can't blow up respawn anchors in this dimension, disabling.");
            toggle();
            return;
        }

        // Pause
        if (shouldPause()) {
            renderBlockPos = null;
            return;
        }

        // Find a target
        if (TargetUtils.isBadTarget(target, targetRange.get())) {
            renderBlockPos = null;
            target = TargetUtils.getPlayerTarget(targetRange.get(), targetPriority.get());
            if (TargetUtils.isBadTarget(target, targetRange.get())) return;
        }

        doAnchorAura();
    }

    private void doAnchorAura() {
        bestPlaceDamage = 0;
        bestBreakDamage = 0;

        FindItemResult anchor = InvUtils.findInHotbar(Items.RESPAWN_ANCHOR);
        FindItemResult glowstone = InvUtils.findInHotbar(Items.GLOWSTONE);
        boolean canPlace = place.get() && anchor.found() && glowstone.found();
        double playerHealth = PlayerUtils.getTotalHealth();

        int iteratorRange = (int) Math.ceil(canPlace ? Math.max(placeRange.get(), breakRange.get()) : breakRange.get());
        BlockIterator.register(iteratorRange, iteratorRange, (blockPos, blockState) -> {
            boolean isPlacing = blockState.getBlock() != Blocks.RESPAWN_ANCHOR;
            if (isPlacing && !canPlace) return;
            if (!isPlacing && blockState.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) == 0 && !glowstone.found()) return;

            double baseRange = isPlacing ? placeRange.get() : breakRange.get();
            double wallsRange = isPlacing ? placeWallsRange.get() : breakWallsRange.get();
            if (isOutOfRange(blockPos, baseRange, wallsRange)) return;

            if (isPlacing) {
                if (!BlockUtils.canPlaceBlock(blockPos, true, Blocks.RESPAWN_ANCHOR)) return;
                if (!airPlace.get() && isAirPlace(blockPos)) return;
            }

            float bestDamage = isPlacing ? bestPlaceDamage : bestBreakDamage;
            Vec3 center = Vec3.atCenterOf(blockPos);
            float targetDamage = DamageUtils.anchorDamage(target, center);
            if (targetDamage < minDamage.get() || targetDamage <= bestDamage) return;

            float selfDamage = DamageUtils.anchorDamage(mc.player, center);
            if (selfDamage > maxSelfDamage.get()) return;
            if (antiSuicide.get() && playerHealth - selfDamage <= 0) return;

            if (isPlacing) {
                bestPlaceDamage = targetDamage;
                bestPlacePos.set(blockPos);
            } else {
                bestBreakDamage = targetDamage;
                bestBreakPos.set(blockPos);
            }
        });

        BlockIterator.after(() -> {
            renderBlockPos = null;

            if (bestBreakDamage > 0) {
                doBreak(bestBreakPos.immutable(), glowstone);
            } else if (bestPlaceDamage > 0) {
                doPlace(bestPlacePos.immutable(), anchor);
            }
        });
    }

    private void doPlace(BlockPos pos, FindItemResult anchor) {
        renderBlockPos = pos;

        if (placeDelayLeft < placeDelay.get()) {
            placeDelayLeft++;
            return;
        }

        if (BlockUtils.place(pos, anchor, rotate.get(), 50, swing.get(), true, swapBack.get())) placeDelayLeft = 0;
    }

    private void doBreak(BlockPos pos, FindItemResult glowstone) {
        renderBlockPos = pos;

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), 40, () -> doInteract(pos, glowstone));
        } else {
            doInteract(pos, glowstone);
        }
    }

    private void doInteract(BlockPos pos, FindItemResult glowstone) {
        BlockState blockState = mc.level.getBlockState(pos);
        if (blockState.getBlock() != Blocks.RESPAWN_ANCHOR) return;

        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), BlockUtils.getDirection(pos), pos, true);
        int charges = blockState.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES);
        boolean interacted = false;

        try {
            if (charges == 0) {
                if (chargeDelayLeft < chargeDelay.get()) {
                    chargeDelayLeft++;
                    return;
                }
                if (!useItem(hit, glowstone)) return;

                interacted = true;
                chargeDelayLeft = 0;
                charges = 1;
            }

            if (charges > 0) {
                if (breakDelayLeft < breakDelay.get()) {
                    breakDelayLeft++;
                    return;
                }

                FindItemResult detonator = InvUtils.findInHotbar(item -> item.getItem() != Items.GLOWSTONE);
                if (!useItem(hit, detonator)) return;

                interacted = true;
                breakDelayLeft = 0;
            }
        } finally {
            if (interacted && swapBack.get()) InvUtils.swapBack();
        }
    }

    private boolean useItem(BlockHitResult hit, FindItemResult item) {
        if (!item.found()) return false;

        InteractionHand hand = item.isOffhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        if (hand == InteractionHand.MAIN_HAND && !InvUtils.swap(item.slot(), swapBack.get())) return false;

        BlockUtils.interact(hit, hand, swing.get());
        return true;
    }

    private boolean isOutOfRange(BlockPos blockPos, double baseRange, double wallsRange) {
        Vec3 pos = Vec3.atCenterOf(blockPos);
        if (!PlayerUtils.isWithin(pos, baseRange)) return true;

        ClipContext clipContext = new ClipContext(mc.player.getEyePosition(), pos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
        BlockHitResult result = mc.level.clip(clipContext);
        if (result == null || !result.getBlockPos().equals(blockPos))
            return !PlayerUtils.isWithin(pos, wallsRange);

        return false;
    }

    private boolean isAirPlace(BlockPos blockPos) {
        for (Direction direction : Direction.values()) {
            if (!mc.level.getBlockState(blockPos.relative(direction)).canBeReplaced()) return false;
        }

        return true;
    }

    private boolean shouldPause() {
        if (pauseOnUse.get() && mc.player.isUsingItem()) return true;

        if (pauseOnMine.get() && mc.gameMode.isDestroying()) return true;

        CrystalAura crystalAura = Modules.get().get(CrystalAura.class);
        return pauseOnCA.get() && crystalAura.isActive() && crystalAura.kaTimer > 0;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || renderBlockPos == null) return;

        event.renderer.box(renderBlockPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }

    @Override
    public String getInfoString() {
        return EntityUtils.getName(target);
    }
}
