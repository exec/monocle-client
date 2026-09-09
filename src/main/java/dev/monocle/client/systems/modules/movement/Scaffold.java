/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.movement;

import com.google.common.collect.Streams;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.events.entity.player.PlayerMoveEvent;
import dev.monocle.client.mixininterface.IVec3;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.combat.KillAura;
import dev.monocle.client.systems.modules.player.AutoEat;
import dev.monocle.client.systems.modules.player.AutoGap;
import dev.monocle.client.systems.modules.player.AutoMend;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.client.systems.modules.world.PrinterHelper;
import dev.monocle.client.utils.player.Rotations;
import dev.monocle.client.renderer.ShapeMode;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.misc.ListMode;
import dev.monocle.client.utils.player.FindItemResult;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.PlayerUtils;
import dev.monocle.client.utils.render.RenderUtils;
import dev.monocle.client.utils.render.color.SettingColor;
import dev.monocle.client.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;

public class Scaffold extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final Setting<Boolean> edgeGuard = sgGeneral.add(new BoolSetting.Builder()
        .name("verified-edge-guard").description("Limit grounded walking to loaded full-block footing, excluding unresolved Scaffold predictions. Not fall protection while airborne.")
        .defaultValue(true).build());
    private final Setting<Boolean> searchInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("search-inventory").description("Promote permitted, unnamed building blocks from inventory. Never drops items or opens supplies.")
        .defaultValue(true).build());

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Selected blocks.")
        .build()
    );

    private final Setting<ListMode> blocksFilter = sgGeneral.add(new EnumSetting.Builder<ListMode>()
        .name("blocks-filter")
        .description("How to use the block list setting")
        .defaultValue(ListMode.Blacklist)
        .build()
    );

    private final Setting<Boolean> fastTower = sgGeneral.add(new BoolSetting.Builder()
        .name("fast-tower")
        .description("Whether or not to scaffold upwards faster.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> towerSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("tower-speed")
        .description("The speed at which to tower.")
        .defaultValue(0.5)
        .min(0)
        .sliderMax(1)
        .visible(fastTower::get)
        .build()
    );

    private final Setting<Boolean> whileMoving = sgGeneral.add(new BoolSetting.Builder()
        .name("while-moving")
        .description("Allows you to tower while moving.")
        .defaultValue(false)
        .visible(fastTower::get)
        .build()
    );

    private final Setting<Boolean> onlyOnClick = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-click")
        .description("Only places blocks when holding right click.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> renderSwing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Renders your client-side swing.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Automatically swaps to a block before placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates towards the blocks being placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> airPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Allow air place. This also allows you to modify scaffold radius.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> aheadDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("ahead-distance")
        .description("How far ahead to place blocks.")
        .defaultValue(0)
        .min(0)
        .sliderMax(1)
        .visible(() -> !airPlace.get())
        .build()
    );

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("closest-block-range")
        .description("How far can scaffold place blocks when you are in air.")
        .defaultValue(4)
        .min(0)
        .max(8)
        .sliderMax(8)
        .visible(() -> !airPlace.get())
        .build()
    );

    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("radius")
        .description("Scaffold radius.")
        .defaultValue(0)
        .min(0)
        .max(6)
        .visible(airPlace::get)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to place in one tick.")
        .defaultValue(3)
        .min(1)
        .visible(airPlace::get)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Whether to render blocks that have been placed.")
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
        .description("The side color of the target block rendering.")
        .defaultValue(new SettingColor(197, 137, 232, 10))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the target block rendering.")
        .defaultValue(new SettingColor(197, 137, 232))
        .visible(render::get)
        .build()
    );

    private final BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
    private final HashMap<BlockPos, Integer> pending = new HashMap<>(), attempted = new HashMap<>();
    private BlockPos placingTarget;
    private Object world;
    private int probeTick = -100;
    private String status = "Ready";

    @Override public void onActivate() { world = mc.level; pending.clear(); attempted.clear(); probeTick = -100; status = "Ready"; }
    @Override public void onDeactivate() { pending.clear(); attempted.clear(); }

    private boolean anotherBuilder() {
        return Modules.get().get(HighwayBuilder.class).controlsPlayer() || Modules.get().get(PrinterHelper.class).controlsInventory();
    }

    private boolean inventoryReady() {
        return mc.player != null && mc.level == world && mc.gui.screen() == null
            && mc.player.containerMenu == mc.player.inventoryMenu && mc.player.containerMenu.getCarried().isEmpty()
            && !mc.player.isUsingItem() && !Modules.get().get(AutoEat.class).eating
            && !Modules.get().get(AutoGap.class).isEating() && !Modules.get().get(KillAura.class).attacking && !anotherBuilder();
    }

    public Scaffold() {
        super(Categories.Movement, "scaffold", "Automatically places blocks under you.");
    }


    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        if (mc.level != world) { toggle(); return; }
        if (!inventoryReady()) { status = "Yielding to inventory / eating / combat / building"; return; }
        if (onlyOnClick.get() && !mc.options.keyUse.isDown()) return;
        int tick = mc.player.tickCount;
        attempted.entrySet().removeIf(entry -> !pending.containsKey(entry.getKey()) && tick - entry.getValue() > 40);
        if (!pending.isEmpty() && tick - probeTick >= 40 && !mc.gameMode.isDestroying()
            && attempted.values().stream().anyMatch(t -> tick - t >= 20)) {
            probeTick = tick;
            mc.gameMode.startPrediction(mc.level, sequence -> {
                pending.replaceAll((pos, sent) -> sent == Integer.MAX_VALUE && tick - attempted.getOrDefault(pos, tick) >= 20 ? sequence : sent);
                return new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    mc.player.blockPosition().below(), Direction.DOWN, sequence);
            });
        }
        status = pending.isEmpty() ? "Ready" : "Awaiting server: " + pending.size();

        Vec3 vec = mc.player.position().add(mc.player.getDeltaMovement()).add(0, -0.75, 0);
        if (airPlace.get()) {
            bp.set(vec.x(), vec.y(), vec.z());
        } else {
            Vec3 pos = mc.player.position();
            Vec3 velocity = mc.player.getDeltaMovement();
            if (edgeGuard.get() && velocity.horizontalDistanceSqr() > 0.000001 && !towering()) {
                double length = Math.sqrt(velocity.horizontalDistanceSqr());
                pos = pos.add(velocity.x + velocity.x / length * .6, 0, velocity.z + velocity.z / length * .6);
            }
            if (aheadDistance.get() != 0 && !towering() && !mc.level.getBlockState(mc.player.blockPosition().below()).getCollisionShape(mc.level, mc.player.blockPosition()).isEmpty()) {
                Vec3 dir = Vec3.directionFromRotation(0, mc.player.getYRot()).multiply(aheadDistance.get(), 0, aheadDistance.get());
                if (mc.options.keyUp.isDown()) pos = pos.add(dir.x, 0, dir.z);
                if (mc.options.keyDown.isDown()) pos = pos.add(-dir.x, 0, -dir.z);
                if (mc.options.keyLeft.isDown()) pos = pos.add(dir.z, 0, -dir.x);
                if (mc.options.keyRight.isDown()) pos = pos.add(-dir.z, 0, dir.x);
            }
            bp.set(pos.x, vec.y, pos.z);
        }
        if (mc.options.keyShift.isDown() && !mc.options.keyJump.isDown() && mc.player.getY() + vec.y > -1) {
            bp.setY(bp.getY() - 1);
        }
        if (bp.getY() >= mc.player.blockPosition().getY()) {
            bp.setY(mc.player.blockPosition().getY() - 1);
        }
        BlockPos targetBlock = bp.immutable();

        if (!airPlace.get() && (BlockUtils.getPlaceSide(bp) == null)) {
            Vec3 pos = mc.player.position();
            pos = pos.add(0, -0.98f, 0);
            pos.add(mc.player.getDeltaMovement());

            List<BlockPos> blockPosArray = new ArrayList<>();
            for (int x = (int) (mc.player.getX() - placeRange.get()); x < mc.player.getX() + placeRange.get(); x++) {
                for (int z = (int) (mc.player.getZ() - placeRange.get()); z < mc.player.getZ() + placeRange.get(); z++) {
                    for (int y = (int) Math.max(mc.level.getMinY(), mc.player.getY() - placeRange.get()); y < Math.min(mc.level.getMaxY(), mc.player.getY() + placeRange.get()); y++) {
                        bp.set(x, y, z);
                        if (BlockUtils.getPlaceSide(bp) == null) continue;
                        if (!BlockUtils.canPlace(bp)) continue;
                        if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(bp.relative(BlockUtils.getClosestPlaceSide(bp)))) > 36)
                            continue;
                        blockPosArray.add(new BlockPos(bp));
                    }
                }
            }
            if (blockPosArray.isEmpty()) { status = "No reachable placement support"; return; }

            blockPosArray.sort(Comparator.comparingDouble(blockPos -> blockPos.distSqr(targetBlock)));

            bp.set(blockPosArray.getFirst());
        }

        if (airPlace.get()) {
            List<BlockPos> blocks = new ArrayList<>();
            for (int x = (int) (bp.getX() - radius.get()); x <= bp.getX() + radius.get(); x++) {
                for (int z = (int) (bp.getZ() - radius.get()); z <= bp.getZ() + radius.get(); z++) {
                    BlockPos blockPos = BlockPos.containing(x, bp.getY(), z);
                    if (mc.player.position().distanceTo(Vec3.atCenterOf(blockPos)) <= radius.get() || (x == bp.getX() && z == bp.getZ())) {
                        blocks.add(blockPos);
                    }
                }
            }

            if (!blocks.isEmpty()) {
                blocks.sort(Comparator.comparingDouble(PlayerUtils::squaredDistanceTo));
                int counter = 0;
                for (BlockPos block : blocks) {
                    if (place(block)) {
                        counter++;
                    }

                    if (counter >= blocksPerTick.get()) {
                        break;
                    }
                }
            }
        } else {
            place(bp);
        }

        FindItemResult result = InvUtils.findInHotbar(itemStack -> validItem(itemStack, bp));
        if (fastTower.get() && pending.isEmpty() && mc.options.keyJump.isDown() && !mc.options.keyShift.isDown() && result.found() && (autoSwitch.get() || result.getHand() != null)) {
            Vec3 velocity = mc.player.getDeltaMovement();
            AABB playerBox = mc.player.getBoundingBox();
            if (Streams.stream(mc.level.getBlockCollisions(mc.player, playerBox.move(0, 1, 0))).toList().isEmpty()) {
                // If there is no block above the player: move the player up, so he can place another block
                if (whileMoving.get() || !PlayerUtils.isMoving()) {
                    velocity = new Vec3(velocity.x, towerSpeed.get(), velocity.z);
                }
                mc.player.setDeltaMovement(velocity);
            } else {
                // If there is a block above the player: move the player down, so he's on top of the placed block
                mc.player.setDeltaMovement(velocity.x, Math.ceil(mc.player.getY()) - mc.player.getY(), velocity.z);
                mc.player.setOnGround(true);
            }
        }
    }

    public boolean scaffolding() {
        return isActive() && inventoryReady() && (!onlyOnClick.get() || mc.options.keyUse.isDown());
    }

    public boolean towering() {
        if (!scaffolding() || !pending.isEmpty()) return false;
        FindItemResult result = InvUtils.findInHotbar(itemStack -> validItem(itemStack, bp));
        return scaffolding() && fastTower.get() && mc.options.keyJump.isDown() && !mc.options.keyShift.isDown() &&
            (whileMoving.get() || !PlayerUtils.isMoving()) && result.found() && (autoSwitch.get() || result.getHand() != null);
    }

    private boolean validItem(ItemStack itemStack, BlockPos pos) {
        if (!(itemStack.getItem() instanceof BlockItem)) return false;

        Block block = ((BlockItem) itemStack.getItem()).getBlock();

        if (!blocksFilter.get().allows(blocks.get().contains(block))) return false;

        if (!Block.isShapeFullBlock(block.defaultBlockState().getCollisionShape(mc.level, pos))) return false;
        return !(block instanceof FallingBlock); // Falling scaffold blocks are not stable footing.
    }

    private boolean place(BlockPos bp) {
        if (!inventoryReady() || pending.containsKey(bp) || pending.size() >= 128
            || mc.player.tickCount - attempted.getOrDefault(bp, -100) < 5 || !mc.level.hasChunkAt(bp)
            || !BlockUtils.canPlace(bp)) return false;
        FindItemResult item = InvUtils.findInHotbar(itemStack -> validItem(itemStack, bp));
        if (!item.found() && searchInventory.get() && autoSwitch.get()) {
            FindItemResult supply = InvUtils.find(stack -> validItem(stack, bp)
                && !stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)
                && !((BlockItem) stack.getItem()).getBlock().defaultBlockState().hasBlockEntity(), 9, 35);
            if (supply.found() && !Modules.get().get(AutoMend.class).reservesSlot(supply.slot())) {
                int destination = mc.player.getInventory().getSelectedSlot() == 8 ? 7 : 8;
                for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).isEmpty()) { destination = i; break; }
                InvUtils.quickSwap().fromId(destination).to(supply.slot());
                item = InvUtils.findInHotbar(stack -> validItem(stack, bp));
            }
        }
        if (!item.found()) { status = "Out of permitted building blocks"; return false; }

        if (item.getHand() == null && !autoSwitch.get()) return false;

        BlockPos target = bp.immutable();
        FindItemResult chosen = item;
        ItemStack expected = (chosen.isOffhand() ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(chosen.slot())).copy();
        long revision = activationRevision();
        pending.put(target, Integer.MAX_VALUE);
        attempted.put(target, mc.player.tickCount);
        Runnable action = () -> {
            if (!isActive() || activationRevision() != revision) return;
            if (!inventoryReady() || !ItemStack.isSameItemSameComponents(expected,
                chosen.isOffhand() ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(chosen.slot()))) { pending.remove(target); return; }
            placingTarget = target;
            try {
                if (!BlockUtils.place(target, chosen, false, 50, renderSwing.get(), true)) pending.remove(target);
            } finally { placingTarget = null; }
        };
        if (rotate.get()) Rotations.rotate(Rotations.getYaw(Vec3.atCenterOf(target)), Rotations.getPitch(Vec3.atCenterOf(target)), 50, action);
        else action.run();
        status = "Awaiting server: " + pending.size();
        return true;
    }

    @EventHandler private void onPacketSent(PacketEvent.Sent event) {
        if (placingTarget != null && event.packet instanceof ServerboundUseItemOnPacket packet) pending.put(placingTarget, packet.getSequence());
    }

    public void onServerBlockAck(int sequence) {
        if (!isActive() || mc.level != world) return;
        pending.entrySet().removeIf(entry -> {
            if (entry.getValue() == Integer.MAX_VALUE || entry.getValue() > sequence) return false;
            confirmed(entry.getKey());
            return true;
        });
    }

    public void onServerBlockUpdate(BlockPos pos, BlockState state) {
        if (isActive() && mc.level == world && pending.containsKey(pos) && Block.isShapeFullBlock(state.getCollisionShape(mc.level, pos))) {
            pending.remove(pos);
            confirmed(pos);
        }
    }

    private void confirmed(BlockPos pos) {
        if (render.get() && Block.isShapeFullBlock(mc.level.getBlockState(pos).getCollisionShape(mc.level, pos)))
            RenderUtils.renderTickingBlock(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0, 8, true, false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onMove(PlayerMoveEvent event) {
        if (!edgeGuard.get() || !isActive() || mc.player == null || mc.level != world || anotherBuilder()
            || event.type != MoverType.SELF || !mc.player.onGround() || mc.player.isFallFlying()
            || mc.player.isPassenger() || mc.player.isInWater() || mc.player.isInLava()
            || onlyOnClick.get() && !mc.options.keyUse.isDown()) return;
        Vec3 delta = event.movement;
        // ponytail: conservative full-block, center-footprint guard; slopes/slabs and airborne rescue are not modeled.
        double fraction = safeFraction(mc.player.position(), delta, pos -> !pending.containsKey(pos) && mc.level.hasChunkAt(pos)
            && Block.isShapeFullBlock(mc.level.getBlockState(pos).getCollisionShape(mc.level, pos)));
        if (fraction < 1) {
            ((IVec3) delta).monocle$setXZ(fraction == 0 ? 0 : delta.x * fraction, fraction == 0 ? 0 : delta.z * fraction);
            status = "Holding edge for confirmed footing";
        }
    }

    static double safeFraction(Vec3 feet, Vec3 delta, java.util.function.Predicate<BlockPos> supported) {
        double distance = Math.max(Math.abs(delta.x), Math.abs(delta.z));
        if (!Double.isFinite(distance) || distance > 4) return 0;
        int steps = Math.max(1, (int) Math.ceil(distance / .05));
        for (int i = 1; i <= steps; i++) {
            double fraction = (double) i / steps;
            if (!supported.test(BlockPos.containing(feet.x + delta.x * fraction, feet.y - .05, feet.z + delta.z * fraction)))
                return (double) (i - 1) / steps;
        }
        return 1;
    }

    @Override public String getInfoString() { return status; }

}
