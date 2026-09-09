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
import dev.monocle.client.utils.entity.SortPriority;
import dev.monocle.client.utils.entity.TargetUtils;
import dev.monocle.client.utils.player.FindItemResult;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.PlayerUtils;
import dev.monocle.client.utils.render.color.SettingColor;
import dev.monocle.client.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class AutoWeb extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("The range at which webs can be placed.")
        .defaultValue(4)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> placeWallsRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("Range in which to place webs when behind blocks.")
        .defaultValue(4)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<SortPriority> priority = sgGeneral.add(new EnumSetting.Builder<SortPriority>()
        .name("target-priority")
        .description("How to filter targets within range.")
        .defaultValue(SortPriority.LowestDistance)
        .build()
    );

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("The maximum distance to target players.")
        .defaultValue(10)
        .min(0)
        .sliderMax(30)
        .build()
    );

    private final Setting<Boolean> predictMovement = sgGeneral.add(new BoolSetting.Builder()
        .name("predict-movement")
        .description("Predict target movement to account for ping.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> ticksToPredict = sgGeneral.add(new DoubleSetting.Builder()
        .name("ticks-to-predict")
        .description("How many ticks ahead we should predict for.")
        .defaultValue(10)
        .min(1)
        .sliderMax(30)
        .visible(predictMovement::get)
        .build()
    );

    private final Setting<Boolean> doubles = sgGeneral.add(new BoolSetting.Builder()
        .name("doubles")
        .description("Places webs in the target's upper hitbox as well as the lower hitbox.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates towards the webs when placing.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders an overlay where webs are placed.")
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
        .description("The side color of the placed web rendering.")
        .defaultValue(new SettingColor(239, 231, 244, 31))
        .visible(() -> render.get() && shapeMode.get().sides())
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the placed web rendering.")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> render.get() && shapeMode.get().lines())
        .build()
    );

    private final List<BlockPos> placePositions = new ArrayList<>();
    private Player target = null;

    public AutoWeb() {
        super(Categories.Combat, "auto-web", "Automatically places webs on other players.");
    }

    @Override
    public void onActivate() {
        target = null;
    }

    @Override
    public void onDeactivate() {
        placePositions.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        placePositions.clear();

        if (TargetUtils.isBadTarget(target, targetRange.get())) {
            target = TargetUtils.getPlayerTarget(targetRange.get(), priority.get());
            if (TargetUtils.isBadTarget(target, targetRange.get())) return;
        }

        // Grab webs from hotbar
        FindItemResult webs = InvUtils.findInHotbar(Items.COBWEB);
        if (!webs.found()) return;

        Vec3 pos = target.position();

        // Prediction mode via target's movement delta
        if (predictMovement.get()) {
            double dx = target.getX() - target.xo;
            double dy = target.getY() - target.yo;
            double dz = target.getZ() - target.zo;
            pos = pos.add(dx * ticksToPredict.get(), dy * ticksToPredict.get(), dz * ticksToPredict.get());
        }

        BlockPos blockPos = BlockPos.containing(pos);

        if (canPlaceWebAt(blockPos)) {
            BlockUtils.place(blockPos, webs, rotate.get(), 0, false);
            placePositions.add(blockPos);
        }

        if (doubles.get() && canPlaceWebAt(blockPos.above())) {
            BlockUtils.place(blockPos.above(), webs, rotate.get(), 0, false);
            placePositions.add(blockPos.above());
        }
    }

    private boolean canPlaceWebAt(BlockPos blockPos) {
        if (!mc.level.getBlockState(blockPos).canBeReplaced()) return false;

        // Check raycast and range
        return !isOutOfRange(blockPos);
    }

    private boolean isOutOfRange(BlockPos blockPos) {
        Vec3 pos = Vec3.atCenterOf(blockPos);
        if (!PlayerUtils.isWithin(pos, placeRange.get())) return true;

        ClipContext clipContext = new ClipContext(mc.player.getEyePosition(), pos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
        BlockHitResult result = mc.level.clip(clipContext);
        if (result == null || !result.getBlockPos().equals(blockPos))
            return !PlayerUtils.isWithin(pos, placeWallsRange.get());

        return false;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || placePositions.isEmpty()) return;

        for (BlockPos placePosition : placePositions) {
            event.renderer.box(placePosition, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
}
