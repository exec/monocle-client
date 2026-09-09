/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.combat;

import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.events.render.Render3DEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.mixin.DirectionAccessor;
import dev.monocle.client.renderer.ShapeMode;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.entity.DamageUtils;
import dev.monocle.client.utils.player.FindItemResult;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.PlayerUtils;
import dev.monocle.client.utils.player.Rotations;
import dev.monocle.client.utils.render.color.Color;
import dev.monocle.client.utils.render.color.SettingColor;
import dev.monocle.client.utils.world.BlockUtils;
import dev.monocle.client.utils.world.Dir;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.SortedSet;
import java.util.HashMap;

public class Surround extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgToggles = settings.createGroup("Toggles");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("What blocks to use for surround.")
        .defaultValue(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.NETHERITE_BLOCK)
        .filter(this::blockFilter)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay, in ticks, between block placements.")
        .min(0)
        .defaultValue(0)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to place in one tick.")
        .defaultValue(1)
        .min(1)
        .build()
    );

    private final Setting<Center> center = sgGeneral.add(new EnumSetting.Builder<Center>()
        .name("center")
        .description("Teleports you to the center of the block.")
        .defaultValue(Center.Incomplete)
        .build()
    );

    private final Setting<Boolean> doubleHeight = sgGeneral.add(new BoolSetting.Builder()
        .name("double-height")
        .description("Places obsidian on top of the original surround blocks to prevent people from face-placing you.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyOnGround = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-ground")
        .description("Works only when you are standing on blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> airPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Allows Surround to place blocks in the air.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> toggleModules = sgGeneral.add(new BoolSetting.Builder()
        .name("toggle-modules")
        .description("Turn off other modules when surround is activated.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> toggleBack = sgGeneral.add(new BoolSetting.Builder()
        .name("toggle-back-on")
        .description("Turn the other modules back on when surround is deactivated.")
        .defaultValue(false)
        .visible(toggleModules::get)
        .build()
    );

    private final Setting<List<Module>> modules = sgGeneral.add(new ModuleListSetting.Builder()
        .name("modules")
        .description("Which modules to disable on activation.")
        .visible(toggleModules::get)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Automatically faces towards the obsidian being placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> protect = sgGeneral.add(new BoolSetting.Builder()
        .name("protect")
        .description("Attempts to break crystals around surround positions to prevent surround break.")
        .defaultValue(true)
        .build()
    );

    // Toggles

    private final Setting<Boolean> toggleOnYChange = sgToggles.add(new BoolSetting.Builder()
        .name("toggle-on-y-change")
        .description("Automatically disables when your y level changes (step, jumping, etc).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> toggleOnComplete = sgToggles.add(new BoolSetting.Builder()
        .name("toggle-on-complete")
        .description("Toggles off when all blocks are placed.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> toggleOnDeath = sgToggles.add(new BoolSetting.Builder()
        .name("toggle-on-death")
        .description("Toggles off when you die.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<Boolean> swing = sgRender.add(new BoolSetting.Builder()
        .name("swing")
        .description("Render your hand swinging when placing surround blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders a block overlay where the obsidian will be placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderBelow = sgRender.add(new BoolSetting.Builder()
        .name("below")
        .description("Renders the block below you.")
        .defaultValue(false)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> safeSideColor = sgRender.add(new ColorSetting.Builder()
        .name("safe-side-color")
        .description("The side color for safe blocks.")
        .defaultValue(new SettingColor(13, 255, 0, 0))
        .visible(() -> render.get() && shapeMode.get() != ShapeMode.Lines)
        .build()
    );

    private final Setting<SettingColor> safeLineColor = sgRender.add(new ColorSetting.Builder()
        .name("safe-line-color")
        .description("The line color for safe blocks.")
        .defaultValue(new SettingColor(13, 255, 0, 0))
        .visible(() -> render.get() && shapeMode.get() != ShapeMode.Sides)
        .build()
    );

    private final Setting<SettingColor> normalSideColor = sgRender.add(new ColorSetting.Builder()
        .name("normal-side-color")
        .description("The side color for normal blocks.")
        .defaultValue(new SettingColor(0, 255, 238, 12))
        .visible(() -> render.get() && shapeMode.get() != ShapeMode.Lines)
        .build()
    );

    private final Setting<SettingColor> normalLineColor = sgRender.add(new ColorSetting.Builder()
        .name("normal-line-color")
        .description("The line color for normal blocks.")
        .defaultValue(new SettingColor(0, 255, 238, 100))
        .visible(() -> render.get() && shapeMode.get() != ShapeMode.Sides)
        .build()
    );

    private final Setting<SettingColor> unsafeSideColor = sgRender.add(new ColorSetting.Builder()
        .name("unsafe-side-color")
        .description("The side color for unsafe blocks.")
        .defaultValue(new SettingColor(204, 0, 0, 12))
        .visible(() -> render.get() && shapeMode.get() != ShapeMode.Lines)
        .build()
    );

    private final Setting<SettingColor> unsafeLineColor = sgRender.add(new ColorSetting.Builder()
        .name("unsafe-line-color")
        .description("The line color for unsafe blocks.")
        .defaultValue(new SettingColor(204, 0, 0, 100))
        .visible(() -> render.get() && shapeMode.get() != ShapeMode.Sides)
        .build()
    );

    public ArrayList<Module> toActivate = new ArrayList<>();
    private final HashMap<Module, Long> disabledRevisions = new HashMap<>();
    private int timer;
    private final Setting<Boolean> searchInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("search-inventory").description("Promote permitted surround blocks from inventory; displaced hotbar items stay in inventory.")
        .defaultValue(true).build());
    private final HashMap<BlockPos, Integer> pending = new HashMap<>(), attempted = new HashMap<>();
    private BlockPos anchor, placingTarget;
    private Object world;
    private int epoch, probeTick = -100;
    private String status = "Incomplete";

    public Surround() {
        super(Categories.Combat, "surround", "Surrounds you in blocks to prevent massive crystal damage.");
    }

    // Render

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get()) return;

        BlockPos playerPos = mc.player.blockPosition();

        // Below
        if (renderBelow.get()) draw(playerPos.below(), event, 0);

        for (Direction direction : DirectionAccessor.monocle$getHorizontal()) {
            BlockPos renderPos = playerPos.relative(direction);

            // Regular surround positions
            draw(renderPos, event, doubleHeight.get() ? Dir.UP : 0);

            // Double height
            if (doubleHeight.get()) draw(renderPos.above(), event, Dir.DOWN);
        }
    }

    private void draw(BlockPos renderPos, Render3DEvent event, int exclude) {
        Color sideColor = getSideColor(renderPos);
        Color lineColor = getLineColor(renderPos);
        event.renderer.box(renderPos, sideColor, lineColor, shapeMode.get(), exclude);
    }

    // Function

    @Override
    public void onActivate() {
        pending.clear();
        attempted.clear();
        anchor = null;
        world = mc.level;
        epoch++;
        probeTick = -100;
        toActivate.clear();
        disabledRevisions.clear();
        // Center on activate
        if (center.get() == Center.OnActivate) PlayerUtils.centerPlayer();

        // Reset delay
        timer = delay.get();

        if (toggleModules.get() && !modules.get().isEmpty() && mc.level != null && mc.player != null) {
            for (Module module : modules.get()) {
                if (module != this && module.isActive()) {
                    module.toggle();
                    toActivate.add(module);
                    disabledRevisions.put(module, module.activationRevision());
                }
            }
        }
    }

    @Override
    public void onDeactivate() {
        epoch++;
        pending.clear();
        attempted.clear();
        if (toggleBack.get() && !toActivate.isEmpty() && mc.level != null && mc.player != null) {
            for (Module module : toActivate) {
                if (!module.isActive() && disabledRevisions.getOrDefault(module, -1L) == module.activationRevision()) module.enable();
            }
        }
        toActivate.clear();
        disabledRevisions.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        if (world != mc.level) { toggle(); return; }
        // Toggle if Y level changed
        if (toggleOnYChange.get() && mc.player.yo != mc.player.getY()) {
            toggle();
            return;
        }

        // Wait till player is on ground
        if (onlyOnGround.get() && !mc.player.onGround()) { status = "Waiting for ground"; return; }

        // Centering player
        if (center.get() == Center.Always) PlayerUtils.centerPlayer();

        BlockPos playerPos = mc.player.blockPosition();
        if (!playerPos.equals(anchor)) {
            anchor = playerPos.immutable();
            pending.clear();
            attempted.clear();
            epoch++;
        }
        List<BlockPos> ring = positions(anchor, doubleHeight.get());
        int verified = 0;
        for (BlockPos pos : ring) if (resolved(pos)) verified++;
        status = verified == ring.size() ? "Protected" : "Incomplete " + verified + "/" + ring.size();
        if (verified == ring.size()) {
            if (toggleOnComplete.get()) toggle();
            else if (protect.get() && inventoryReady()) {
                FindItemResult block = InvUtils.findInHotbar(stack -> blocks.get().contains(Block.byItem(stack.getItem())));
                if (block.found()) for (BlockPos pos : ring) place(pos, block);
            }
            return;
        }
        if (!inventoryReady()) { status += " — inventory / item use busy"; return; }
        if (center.get() == Center.Incomplete) PlayerUtils.centerPlayer();
        int tick = mc.player.tickCount;
        if (!pending.isEmpty() && tick - probeTick >= 40 && !mc.gameMode.isDestroying()
            && attempted.values().stream().anyMatch(t -> tick - t >= 20)) {
            probeTick = tick;
            mc.gameMode.startPrediction(mc.level, sequence -> {
                pending.replaceAll((pos, sent) -> sent == Integer.MAX_VALUE && tick - attempted.getOrDefault(pos, tick) >= 20 ? sequence : sent);
                return new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, anchor.below(), Direction.DOWN, sequence);
            });
        }
        if (timer++ < delay.get()) return;
        timer = 0;
        FindItemResult block = materials();
        if (!block.found()) { status += " — out of permitted blocks"; return; }
        int placed = 0;
        for (BlockPos pos : ring) {
            if (resolved(pos)) { if (protect.get()) place(pos, block); continue; }
            if (pending.containsKey(pos)) continue;
            if (!airPlace.get() && isAirPlace(pos) && mc.level.getBlockState(pos).canBeReplaced()) {
                if (place(pos.below(), block) && ++placed >= blocksPerTick.get()) break;
            }
            if (place(pos, block) && ++placed >= blocksPerTick.get()) break;
        }
        status += !pending.isEmpty() ? " — awaiting server" : placed == 0 ? " — blocked / no placement support" : " — placing";
    }

    static List<BlockPos> positions(BlockPos anchor, boolean doubled) {
        List<BlockPos> result = new ArrayList<>(8);
        for (Direction direction : Direction.Plane.HORIZONTAL) result.add(anchor.relative(direction));
        if (doubled) for (Direction direction : Direction.Plane.HORIZONTAL) result.add(anchor.relative(direction).above());
        return result;
    }

    static boolean protective(BlockState state) {
        return !state.canBeReplaced() && (state.getBlock().defaultDestroyTime() < 0 || state.getBlock().getExplosionResistance() >= 600);
    }

    private boolean resolved(BlockPos pos) {
        return mc.level.hasChunkAt(pos) && !pending.containsKey(pos) && protective(mc.level.getBlockState(pos));
    }

    private boolean inventoryReady() {
        return mc.gui.screen() == null && mc.player.containerMenu == mc.player.inventoryMenu
            && mc.player.containerMenu.getCarried().isEmpty() && !mc.player.isUsingItem();
    }

    private FindItemResult materials() {
        Predicate<ItemStack> allowed = stack -> blocks.get().contains(Block.byItem(stack.getItem()));
        FindItemResult found = InvUtils.findInHotbar(allowed);
        if (found.found() || !searchInventory.get()) return found;
        found = InvUtils.find(allowed, 9, 35);
        if (!found.found()) return found;
        int destination = mc.player.getInventory().getSelectedSlot() == 8 ? 7 : 8;
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).isEmpty()) { destination = i; break; }
        InvUtils.quickSwap().fromId(destination).to(found.slot());
        return InvUtils.findInHotbar(allowed);
    }

    private boolean place(BlockPos placePos, FindItemResult block) {
        // Attempt to place
        boolean placed = false;
        if (!pending.containsKey(placePos) && mc.player.tickCount - attempted.getOrDefault(placePos, -100) >= 5
            && mc.level.hasChunkAt(placePos) && (airPlace.get() || !isAirPlace(placePos)) && BlockUtils.canPlace(placePos, true)) {
            int currentEpoch = epoch;
            ItemStack expected = (block.isOffhand() ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(block.slot())).copy();
            pending.put(placePos.immutable(), Integer.MAX_VALUE);
            attempted.put(placePos.immutable(), mc.player.tickCount);
            Runnable action = () -> {
                if (!isActive() || epoch != currentEpoch || mc.level != world || !inventoryReady()
                    || !mc.player.blockPosition().equals(anchor)
                    || !ItemStack.isSameItemSameComponents(expected, block.isOffhand() ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(block.slot()))) {
                    if (epoch == currentEpoch) pending.remove(placePos);
                    return;
                }
                placingTarget = placePos;
                try {
                    if (!BlockUtils.place(placePos, block, false, 100, swing.get(), true)) pending.remove(placePos);
                } finally { placingTarget = null; }
            };
            if (rotate.get()) Rotations.rotate(Rotations.getYaw(net.minecraft.world.phys.Vec3.atCenterOf(placePos)), Rotations.getPitch(net.minecraft.world.phys.Vec3.atCenterOf(placePos)), 100, action);
            else action.run();
            placed = true;
        }

        // Check if the block is being mined
        boolean beingMined = false;
        for (SortedSet<BlockDestructionProgress> progresses : mc.level.destructionProgress().values()) {
            if (progresses.isEmpty()) continue;

            if (progresses.last().getPos().equals(placePos)) {
                beingMined = true;
                break;
            }
        }

        boolean isThreat = mc.level.getBlockState(placePos).canBeReplaced() || beingMined;

        // If the block is air or is being mined, destroy nearby crystals to be safe
        if (protect.get() && !placed && isThreat) {
            AABB box = new AABB(
                placePos.getX() - 1, placePos.getY() - 1, placePos.getZ() - 1,
                placePos.getX() + 1, placePos.getY() + 1, placePos.getZ() + 1
            );

            Predicate<Entity> entityPredicate = entity -> entity instanceof EndCrystal && DamageUtils.crystalDamage(mc.player, entity.position()) < PlayerUtils.getTotalHealth();

            for (Entity crystal : mc.level.getEntities((Entity) null, box, entityPredicate)) {
                if (rotate.get()) {
                    int currentEpoch = epoch;
                    Rotations.rotate(Rotations.getYaw(crystal), Rotations.getPitch(crystal), () -> {
                        if (isActive() && epoch == currentEpoch && mc.level == world && crystal.isAlive()
                            && DamageUtils.crystalDamage(mc.player, crystal.position()) < PlayerUtils.getTotalHealth())
                            mc.player.connection.send(new ServerboundAttackPacket(crystal.getId()));
                    });
                } else {
                    mc.player.connection.send(new ServerboundAttackPacket(crystal.getId()));
                }

                mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            }
        }

        return placed;
    }

    @EventHandler
    private void onPacketSent(PacketEvent.Sent event) {
        if (placingTarget != null && event.packet instanceof ServerboundUseItemOnPacket packet)
            pending.put(placingTarget.immutable(), packet.getSequence());
    }

    public void onServerBlockAck(int sequence) {
        if (!isActive() || mc.level != world || mc.player == null) return;
        pending.entrySet().removeIf(entry -> acknowledged(entry.getValue(), sequence));
    }

    static boolean acknowledged(int sent, int ack) { return sent != Integer.MAX_VALUE && sent <= ack; }

    public void onServerBlockUpdate(BlockPos pos, BlockState state) {
        if (isActive() && mc.level == world && protective(state)) pending.remove(pos);
    }

    @Override
    public String getInfoString() { return status; }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundPlayerCombatKillPacket packet) {
            int currentEpoch = epoch;
            mc.execute(() -> {
                if (isActive() && epoch == currentEpoch && mc.level == world && mc.player != null
                    && packet.playerId() == mc.player.getId() && toggleOnDeath.get()) {
                    toggle();
                    info("Toggled off because you died.");
                }
            });
        }
    }

    private BlockType getBlockType(BlockPos pos) {
        if (pending.containsKey(pos)) return BlockType.Pending;
        BlockState blockState = mc.level.getBlockState(pos);

        // Unbreakable eg. bedrock
        if (blockState.getBlock().defaultDestroyTime() < 0) return BlockType.Safe;
            // Blast resistant eg. obsidian
        else if (blockState.getBlock().getExplosionResistance() >= 600) return BlockType.Normal;
            // Anything else
        else return BlockType.Unsafe;
    }

    private Color getSideColor(BlockPos pos) {
        return switch (getBlockType(pos)) {
            case Safe -> safeSideColor.get();
            case Normal -> normalSideColor.get();
            case Unsafe -> unsafeSideColor.get();
            case Pending -> new Color(230, 180, 60, 30);
        };
    }

    private Color getLineColor(BlockPos pos) {
        return switch (getBlockType(pos)) {
            case Safe -> safeLineColor.get();
            case Normal -> normalLineColor.get();
            case Unsafe -> unsafeLineColor.get();
            case Pending -> new Color(230, 180, 60, 180);
        };
    }

    private boolean isAirPlace(BlockPos blockPos) {
        for (Direction direction : Direction.values()) {
            if (!mc.level.getBlockState(blockPos.relative(direction)).canBeReplaced()) return false;
        }
        return true;
    }

    private boolean blockFilter(Block block) {
        return block.getExplosionResistance() >= 600 && block.defaultDestroyTime() >= 0 && block != Blocks.REINFORCED_DEEPSLATE;
    }

    public enum Center {
        Never,
        OnActivate,
        Incomplete,
        Always
    }

    public enum BlockType {
        Safe,
        Normal,
        Pending,
        Unsafe
    }
}
