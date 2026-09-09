/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.world;

import dev.monocle.client.MonocleClient;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.screens.HighwayBuilderScreen;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.gui.widgets.pressable.WButton;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import dev.monocle.client.events.game.GameLeftEvent;
import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.events.render.Render2DEvent;
import dev.monocle.client.events.render.Render3DEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.mixin.MinecraftMixin;
import dev.monocle.client.mixin.ShulkerBoxMenuAccessor;
import dev.monocle.client.mixininterface.IVec3;
import dev.monocle.client.renderer.ShapeMode;
import dev.monocle.client.renderer.text.TextRenderer;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.combat.KillAura;
import dev.monocle.client.systems.modules.movement.Velocity;
import dev.monocle.client.systems.modules.movement.speed.Speed;
import dev.monocle.client.systems.modules.player.*;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.entity.EntityAgeTest;
import dev.monocle.client.utils.entity.SortPriority;
import dev.monocle.client.utils.entity.TargetUtils;
import dev.monocle.client.utils.misc.HorizontalDirection;
import dev.monocle.client.utils.misc.MBlockPos;
import dev.monocle.client.utils.player.*;
import dev.monocle.client.utils.render.NametagUtils;
import dev.monocle.client.utils.render.RenderUtils;
import dev.monocle.client.utils.render.color.Color;
import dev.monocle.client.utils.render.color.SettingColor;
import dev.monocle.client.utils.world.BlockUtils;
import dev.monocle.client.utils.world.Dir;
import dev.monocle.client.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.ClientInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Range;
import org.joml.Vector3d;
import org.jspecify.annotations.NonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

@SuppressWarnings("ConstantConditions")
public class HighwayBuilder extends Module {
    public enum Operation { Build, Repair, ClearTunnel, Pave }
    public enum Heading { Facing, North, NorthEast, East, SouthEast, South, SouthWest, West, NorthWest }
    public enum Floor {
        Replace,
        PlaceMissing
    }

    public enum MobWeapons {
        CrossbowOnly, NonCrossbow, Any;

        boolean accepts(boolean crossbow) {
            return this == Any || (this == CrossbowOnly) == crossbow;
        }
    }

    public enum MobPursuit {
        CrossbowOnly, AllTargets, Never;

        boolean allows(boolean crossbow) {
            return this == AllTargets || this == CrossbowOnly && crossbow;
        }
    }

    public enum Rotation {
        None(false, false),
        Mine(true, false),
        Place(false, true),
        Both(true, true);

        public final boolean mine, place;

        Rotation(boolean mine, boolean place) {
            this.mine = mine;
            this.place = place;
        }
    }

    public enum BlockadeType {
        Full(6),
        Partial(4),
        Shulker(3);

        public final int columns;

        BlockadeType(int columns) {
            this.columns = columns;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDigging = settings.createGroup("Digging", false);
    private final SettingGroup sgPaving = settings.createGroup("Paving", false);
    private final SettingGroup sgInventory = settings.createGroup("Inventory", false);
    private final SettingGroup sgMobs = settings.createGroup("Mob Handling", false);
    private final SettingGroup sgRenderDigging = settings.createGroup("Render Digging", false);
    private final SettingGroup sgRenderPaving = settings.createGroup("Render Paving", false);
    private final SettingGroup sgUi = settings.createGroup("UI", false);
    private final Setting<AverageWindow> averageWindow = sgUi.add(new EnumSetting.Builder<AverageWindow>()
        .name("average-blocks/sec-window")
        .description("Forward highway length per real second, not blocks placed. Includes supply trips, waits and pauses. Session uses total distance / elapsed time.")
        .defaultValue(AverageWindow.TenSeconds)
        .build());

    public enum AverageWindow {
        FiveSeconds(5, "5 seconds"), TenSeconds(10, "10 seconds"), ThirtySeconds(30, "30 seconds"),
        SixtySeconds(60, "60 seconds"), Session(0, "Session");
        final int seconds;
        private final String label;
        AverageWindow(int seconds, String label) { this.seconds = seconds; this.label = label; }
        @Override public String toString() { return label; }
    }

    private final HighwayHud hud = new HighwayHud();

    // Mob handling

    private final Setting<Boolean> clearPiglins = sgMobs.add(new BoolSetting.Builder()
        .name("clear-piglin-obstructions")
        .description("Opt-in combat for selected piglins blocking paving or walking. Defaults to crossbow-holding piglins only. Can anger a swarm! Uses a pickaxe, normal melee reach and full cooldown; never enables Kill Aura. Excluded mobs are waited on, never skipped.")
        .defaultValue(false)
        .onChanged(enabled -> { if (!enabled && this.mobReturn != null && this.job) pauseJob("Piglin clearing disabled. Nearby mobs may still be angry; Resume to return to work without attacking."); })
        .build()
    );

    private final Setting<Set<EntityType<?>>> mobTypes = sgMobs.add(new EntityTypeListSetting.Builder()
        .name("combat-targets").description("Piglin species eligible for obstruction combat. Weapon, age and name filters also apply. Players and unrelated mobs are never targets.")
        .defaultValue(EntityTypes.PIGLIN).filter(type -> obstructionPiglin(type, false))
        .onChanged(value -> mobSettingsChanged()).visible(clearPiglins::get).build()
    );

    private final Setting<MobWeapons> mobWeapons = sgMobs.add(new EnumSetting.Builder<MobWeapons>()
        .name("combat-target-weapons").description("Filter targets by whether either hand holds a crossbow. NonCrossbow includes melee weapons and empty hands. This does not choose your own weapon.")
        .defaultValue(MobWeapons.CrossbowOnly).onChanged(value -> mobSettingsChanged()).visible(clearPiglins::get).build()
    );

    private final Setting<EntityAgeTest> mobAge = sgMobs.add(new EnumSetting.Builder<EntityAgeTest>()
        .name("combat-target-age").description("Allow adult piglins, babies, or both; all other target filters still apply.")
        .defaultValue(EntityAgeTest.Both).onChanged(value -> mobSettingsChanged()).visible(clearPiglins::get).build()
    );

    private final Setting<Boolean> mobIgnoreNamed = sgMobs.add(new BoolSetting.Builder()
        .name("combat-ignore-named").description("Protect named piglins even if they match the other filters.")
        .defaultValue(true).onChanged(value -> mobSettingsChanged()).visible(clearPiglins::get).build()
    );

    private final Setting<MobPursuit> mobPursuit = sgMobs.add(new EnumSetting.Builder<MobPursuit>()
        .name("combat-pursuit").description("Which allowed targets may be chased. Never only engages targets already in melee reach; a locked target leaving reach is waited on until it returns or combat times out.")
        .defaultValue(MobPursuit.CrossbowOnly).onChanged(value -> mobSettingsChanged()).visible(clearPiglins::get).build()
    );

    private final Setting<Integer> mobWaitTicks = sgMobs.add(new IntSetting.Builder()
        .name("combat-wait-ticks").description("Wait this many ticks for a blocker to move before engaging (20 ticks = about one second). Zero engages immediately.")
        .defaultValue(40).range(0, 1200).sliderRange(0, 200)
        .onChanged(value -> mobSettingsChanged()).visible(clearPiglins::get).build()
    );

    private final Setting<Integer> mobTimeout = sgMobs.add(new IntSetting.Builder()
        .name("combat-timeout-seconds").description("Maximum active combat time, including pursuit and waiting for melee reach. Pauses instead of abandoning a living target. Eating and server-lag waits do not count.")
        .defaultValue(20).range(1, 120).sliderRange(5, 60)
        .onChanged(value -> mobSettingsChanged()).visible(clearPiglins::get).build()
    );

    private final Setting<Boolean> mobRotate = sgMobs.add(new BoolSetting.Builder()
        .name("combat-rotate").description("Aim before attacking, independently of mining/placement rotation. Turning this off sends attacks without an extra aim rotation; walking still faces the route.")
        .defaultValue(true).onChanged(value -> mobSettingsChanged()).visible(clearPiglins::get).build()
    );

    private final Setting<Double> mobMinHealth = sgMobs.add(new DoubleSetting.Builder()
        .name("combat-min-health").description("Stop and release controls at or below this health (2 health = 1 heart). Does not count absorption.")
        .defaultValue(12).range(1, 20).sliderRange(1, 20).onChanged(value -> mobSettingsChanged()).visible(clearPiglins::get).build()
    );

    private final Setting<Double> mobChaseRange = sgMobs.add(new DoubleSetting.Builder()
        .name("combat-pursuit-range").description("Maximum distance from the encounter. Pursuit stays on confirmed, level ground near the job; this does not extend melee reach.")
        .defaultValue(6).range(1, 8).sliderRange(1, 8).onChanged(value -> mobSettingsChanged()).visible(clearPiglins::get).build()
    );

    // General

    private final Setting<Operation> operation = sgGeneral.add(new EnumSetting.Builder<Operation>()
        .name("operation")
        .description("Build clears and paves. Repair fills gaps and edges without mining existing blocks. ClearTunnel only excavates. Pave fills the floor of an already clear tunnel.")
        .defaultValue(Operation.Build).build()
    );

    private final Setting<Heading> heading = sgGeneral.add(new EnumSetting.Builder<Heading>()
        .name("direction").description("Facing captures your direction when starting; the job keeps its heading until stopped.")
        .defaultValue(Heading.Facing).build()
    );

    private final Setting<Integer> length = sgGeneral.add(new IntSetting.Builder()
        .name("length").description("Completed road distance in blocks. Zero builds indefinitely; diagonals count their actual distance.")
        .defaultValue(0).min(0).sliderRange(0, 1000).build()
    );

    private final Setting<Integer> width = sgGeneral.add(new IntSetting.Builder()
        .name("width")
        .description("Width of the roadway, excluding the railings. Diagonal roads require at least 3.")
        .defaultValue(4)
        .range(1, 5)
        .sliderRange(1, 5)
        .onChanged(value -> { if (value < 2 && this.doubleEnderChests != null) this.doubleEnderChests.set(false); })
        .build()
    );

    private final Setting<Integer> height = sgGeneral.add(new IntSetting.Builder()
        .name("height")
        .description("Clearance above the floor. The builder moves onto temporary steps when mining reach requires it.")
        .defaultValue(3)
        .range(2, 7)
        .sliderRange(2, 7)
        .build()
    );

    private final Setting<Floor> floor = sgGeneral.add(new EnumSetting.Builder<Floor>()
        .name("floor")
        .description("Build only: replace unsuitable floor blocks, or preserve the floor and fill gaps. Repair and Pave always preserve existing blocks.")
        .defaultValue(Floor.Replace)
        .visible(() -> operation.get() == Operation.Build)
        .build()
    );

    private final Setting<Boolean> railings = sgGeneral.add(new BoolSetting.Builder()
        .name("railings")
        .description("Builds railings next to the highway.")
        .defaultValue(true)
        .visible(() -> operation.get() == Operation.Build || operation.get() == Operation.Repair)
        .build()
    );

    private final Setting<Boolean> cornerBlock = sgGeneral.add(new BoolSetting.Builder()
        .name("corner-support-block")
        .description("Places a support block underneath the railings, to prevent air placing.")
        .defaultValue(true)
        .visible(this::hasPreviewRailings)
        .build()
    );

    private final Setting<Boolean> mineAboveRailings = sgGeneral.add(new BoolSetting.Builder()
        .name("mine-above-railings")
        .description("Mines blocks above railings.")
        .defaultValue(true)
        .visible(() -> operation.get() == Operation.Build)
        .build()
    );

    private final Setting<Rotation> rotation = sgGeneral.add(new EnumSetting.Builder<Rotation>()
        .name("rotation")
        .description("Mode of rotation.")
        .defaultValue(Rotation.Both)
        .build()
    );

    private final Setting<Boolean> disconnectOnToggle = sgGeneral.add(new BoolSetting.Builder()
        .name("disconnect-on-toggle")
        .description("Disconnect on a build failure. Otherwise preserve the job and pause with an explanation; manually stopping never disconnects.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pauseOnLag = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-lag")
        .description("Pauses the current process while the server stops responding.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> destroyCrystalTraps = sgGeneral.add(new BoolSetting.Builder()
        .name("destroy-crystal-traps")
        .description("Use a bow to defuse crystal traps safely from a distance. An infinity bow is recommended.")
        .defaultValue(true)
        .build()
    );

    // Digging

    private final Setting<Boolean> doubleMine = sgDigging.add(new BoolSetting.Builder()
        .name("double-mine")
        .description("Whether to double mine blocks when applicable (normal mine and packet mine simultaneously).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> fastBreak = sgDigging.add(new BoolSetting.Builder()
        .name("fast-break")
        .description("Whether to finish breaking blocks faster than normal while double mining.")
        .defaultValue(true)
        .visible(doubleMine::get)
        .build()
    );

    private final Setting<Boolean> dontBreakTools = sgDigging.add(new BoolSetting.Builder()
        .name("dont-break-tools")
        .description("Don't break tools.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> breakDurability = sgDigging.add(new IntSetting.Builder()
        .name("durability-percentage")
        .description("The durability percentage at which to stop using a tool.")
        .defaultValue(2)
        .range(1, 100)
        .sliderRange(1, 100)
        .visible(dontBreakTools::get)
        .build()
    );

    private final Setting<Integer> savePickaxes = sgDigging.add(new IntSetting.Builder()
        .name("save-pickaxes")
        .description("How many pickaxes to ensure are saved. Hitting this number in your inventory will trigger a restock or the module toggling off.")
        .defaultValue(1)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    private final Setting<Integer> breakDelay = sgDigging.add(new IntSetting.Builder()
        .name("break-delay")
        .description("The delay between breaking blocks.")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgDigging.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("The maximum amount of blocks that can be mined in a tick. Only applies to blocks instantly breakable.")
        .defaultValue(1)
        .range(1, 100)
        .sliderRange(1, 25)
        .build()
    );

    // Paving

    public final Setting<List<Block>> blocksToPlace = sgPaving.add(new BlockListSetting.Builder()
        .name("blocks-to-place")
        .description("Blocks it is allowed to place.")
        .defaultValue(Blocks.OBSIDIAN)
        .filter(block -> Block.isShapeFullBlock(block.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)))
        .build()
    );

    private final Setting<Double> placeRange = sgPaving.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("The maximum distance at which you can place blocks.")
        .defaultValue(7.0)
        .range(1, 7)
        .sliderRange(1, 7)
        .build()
    );

    private final Setting<Integer> blocksAheadToPave = sgPaving.add(new IntSetting.Builder()
        .name("blocks-ahead-to-pave")
        .description("Rows to pave ahead. Values above 2 are experimental; extra rows use spare placement budget within Place Range, without extra movement. Underfoot verification is unchanged.")
        .defaultValue(2)
        .range(1, 5)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Integer> placeDelay = sgPaving.add(new IntSetting.Builder()
        .name("place-delay")
        .description("The delay between placing blocks.")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private final Setting<Integer> placementsPerTick = sgPaving.add(new IntSetting.Builder()
        .name("placements-per-tick")
        .description("The maximum amount of blocks that can be placed in a tick.")
        .defaultValue(1)
        .min(1)
        .build()
    );

    // Inventory

    private final Setting<List<Block>> fillerBlocks = sgInventory.add(new BlockListSetting.Builder()
        .name("filler-blocks")
        .description("Expendable blocks for liquid plugs, temporary steps and containment. May be discarded automatically to make inventory space; paving blocks and containers are protected.")
        .defaultValue(Blocks.NETHERRACK, Blocks.COBBLESTONE, Blocks.BLACKSTONE, Blocks.BASALT)
        .filter(block -> !(block instanceof FallingBlock) && Block.isShapeFullBlock(block.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)))
        .build()
    );

    private final Setting<List<Item>> trashItems = sgInventory.add(new ItemListSetting.Builder()
        .name("trash-items")
        .description("Items that are considered trash and can be thrown out.")
        .defaultValue(
            Items.NETHERRACK, Items.QUARTZ, Items.GOLD_NUGGET, Items.GOLDEN_SWORD, Items.GLOWSTONE_DUST,
            Items.GLOWSTONE, Items.BLACKSTONE, Items.BASALT, Items.GHAST_TEAR, Items.SOUL_SAND, Items.SOUL_SOIL,
            Items.ROTTEN_FLESH, Items.MAGMA_BLOCK
        )
        .build()
    );

    private final Setting<Integer> inventoryDelay = sgInventory.add(new IntSetting.Builder()
        .name("inventory-delay")
        .description("Delay in ticks on inventory interactions.")
        .defaultValue(3)
        .min(0)
        .build()
    );

    private final Setting<Boolean> ejectUselessShulkers = sgInventory.add(new BoolSetting.Builder()
        .name("eject-useless-shulkers")
        .description("Ignored legacy setting. Use Keep Shulkers to control supply cleanup.")
        .defaultValue(false)
        .visible(() -> false)
        .build()
    );

    private final Setting<Boolean> keepShulkers = sgInventory.add(new BoolSetting.Builder()
        .name("keep-shulkers")
        .description("Keep all shulkers. WARNING: turning this OFF drops empty boxes AND boxes without road/filler blocks, ender chests, pickaxes or allowed food, including valuable unrelated kits. Mixed boxes containing any such supply are kept.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> searchEnderChest = sgInventory.add(new BoolSetting.Builder()
        .name("search-ender-chest")
        .description("Searches your ender chest to find items to use. Be careful with this one, especially if you let it search through shulkers.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> doubleEnderChests = sgInventory.add(new BoolSetting.Builder()
        .name("double-ender-chests")
        .description("Place two adjacent, same-facing ender chests to search 54 slots on supported servers. Aligns placement even with rotation off. Forced off below width 2; uses one chest if only one is carried.")
        .defaultValue(true)
        .visible(() -> searchEnderChest.get() && width.get() >= 2)
        .onChanged(value -> { if (value && width.get() < 2) this.doubleEnderChests.set(false); })
        .build()
    );

    private final Setting<Boolean> requireSilkTouchForEnderChestRecovery = sgInventory.add(new BoolSetting.Builder()
        .name("require-silk-touch-for-ender-chest-recovery")
        .description("Require a Silk Touch pickaxe to recover a placed supply ender chest. When off, a normal pickaxe may recover it as 8 obsidian, even when bulk ender-chest mining is disabled.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> searchShulkers = sgInventory.add(new BoolSetting.Builder()
        .name("search-shulkers")
        .description("Searches through shulkers to find items to use.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxShulkersPerRestock = sgInventory.add(new IntSetting.Builder()
        .name("max-shulkers-per-restock")
        .description("Maximum useful shulkers to take per ender-chest visit, before loose supplies. Uses available inventory space, discarding expendable filler as needed while preserving pickup reserves.")
        .defaultValue(2)
        .range(1, 36)
        .sliderRange(1, 8)
        .visible(() -> searchEnderChest.get() && searchShulkers.get())
        .build()
    );

    private final Setting<Integer> minEmpty = sgInventory.add(new IntSetting.Builder()
        .name("minimum-empty-slots")
        .description("Free inventory slots kept during restocking and obsidian conversion. Container recovery reserves one extra pickup slot per placed container.")
        .defaultValue(3)
        .sliderRange(0, 9)
        .min(0)
        .build()
    );

    private final Setting<Boolean> mineEnderChests = sgInventory.add(new BoolSetting.Builder()
        .name("mine-ender-chests")
        .description("Allow consuming spare ender-chest blocks to make obsidian; separate from retrieving their stored contents.")
        .defaultValue(true)
        .build()
    );

    private final Setting<BlockadeType> blockadeType = sgInventory.add(new EnumSetting.Builder<BlockadeType>()
        .name("echest-blockade-type")
        .description("What blockade type to use (the structure placed when mining echests).")
        .defaultValue(BlockadeType.Full)
        .visible(mineEnderChests::get)
        .build()
    );

    public final Setting<Integer> saveEchests = sgInventory.add(new IntSetting.Builder()
        .name("save-ender-chests")
        .description("How many ender chests to ensure are saved. Hitting this number in your inventory will trigger a restock or the module toggling off.")
        .defaultValue(2)
        .range(0, 64)
        .sliderRange(0, 64)
        .visible(mineEnderChests::get)
        .build()
    );

    private final Setting<Boolean> rebreakEchests = sgInventory.add(new BoolSetting.Builder()
        .name("instantly-rebreak-echests")
        .description("Whether or not to use the instant rebreak exploit to break echests.")
        .defaultValue(false)
        .visible(mineEnderChests::get)
        .build()
    );

    private final Setting<Integer> rebreakTimer = sgInventory.add(new IntSetting.Builder()
        .name("rebreak-delay")
        .description("Delay between rebreak attempts.")
        .defaultValue(0)
        .sliderMax(20)
        .visible(() -> mineEnderChests.get() && rebreakEchests.get())
        .build()
    );

    // Render Digging

    private final Setting<Boolean> renderMine = sgRenderDigging.add(new BoolSetting.Builder()
        .name("render-blocks-to-mine")
        .description("Render blocks to be mined.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> renderMineShape = sgRenderDigging.add(new EnumSetting.Builder<ShapeMode>()
        .name("blocks-to-mine-shape-mode")
        .description("How the blocks to be mined are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> renderMineSideColor = sgRenderDigging.add(new ColorSetting.Builder()
        .name("blocks-to-mine-side-color")
        .description("Color of blocks to be mined.")
        .defaultValue(new SettingColor(225, 25, 25, 25))
        .build()
    );

    private final Setting<SettingColor> renderMineLineColor = sgRenderDigging.add(new ColorSetting.Builder()
        .name("blocks-to-mine-line-color")
        .description("Color of blocks to be mined.")
        .defaultValue(new SettingColor(225, 25, 25))
        .build()
    );

    // Render Paving

    private final Setting<Boolean> renderPlace = sgRenderPaving.add(new BoolSetting.Builder()
        .name("render-blocks-to-place")
        .description("Render blocks to be placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> renderPlaceShape = sgRenderPaving.add(new EnumSetting.Builder<ShapeMode>()
        .name("blocks-to-place-shape-mode")
        .description("How the blocks to be placed are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> renderPlaceSideColor = sgRenderPaving.add(new ColorSetting.Builder()
        .name("blocks-to-place-side-color")
        .description("Color of blocks to be placed.")
        .defaultValue(new SettingColor(25, 25, 225, 25))
        .build()
    );

    private final Setting<SettingColor> renderPlaceLineColor = sgRenderPaving.add(new ColorSetting.Builder()
        .name("blocks-to-place-line-color")
        .description("Color of blocks to be placed.")
        .defaultValue(new SettingColor(25, 25, 225))
        .build()
    );

    private HorizontalDirection dir, leftDir, rightDir;

    private ClientInput prevInput;
    private CustomPlayerInput input;

    private State state, lastState;
    private IBlockPosProvider blockPosProvider;

    public Vec3 start;
    public int blocksBroken, blocksPlaced;
    private final MBlockPos lastBreakingPos = new MBlockPos();
    private boolean displayInfo, warned;
    private boolean suspended = true, inventory = true;
    private int placeTimer, breakTimer, count, containerId, queuedHotbarSlots;
    private final RestockTask restockTask = new RestockTask(this);
    private final Set<EndCrystal> ignoreCrystals = new ReferenceOpenHashSet<>();
    public boolean drawingBow;
    public DoubleMineBlock normalMining, packetMining;

    private final MBlockPos posRender2 = new MBlockPos();
    private final MBlockPos posRender3 = new MBlockPos();

    private boolean job, paused, preview, listening;
    private String status = "Ready to configure", waiting = "";
    private ClientLevel jobWorld;
    private BlockPos workOrigin;
    private Vec3 walkGoal;
    private List<HighwayPlan.Cell> walkPath = List.of();
    private int walkIndex, walkTicks, walkFailures, idleTicks, actionEpoch;
    private double lastWalkDistance = Double.MAX_VALUE;
    private double completedDistance;
    private int testLength;
    private boolean advancing;
    private boolean waitingForMob;
    private record PavingTarget(BlockPos position, boolean filler, boolean replace) {}
    private List<PavingTarget> advancingPaving = List.of();
    private final ArrayDeque<List<PavingTarget>> pavingChecks = new ArrayDeque<>();
    private final Set<BlockPos> temporarySteps = new HashSet<>();
    // Retain nearby holes after confirmation; true means retries must use road material.
    private final Map<BlockPos, Boolean> liquidPlacements = new HashMap<>();
    private BlockPos pavingRepairTarget;
    private final HashMap<BlockPos, BlockState> pendingBreaks = new HashMap<>();
    private final HashMap<BlockPos, Block> pendingPlaces = new HashMap<>();
    private final HashMap<BlockPos, Integer> placeSequences = new HashMap<>(), breakSequences = new HashMap<>();
    private BlockPos placingTarget;
    private List<Object> jobLayout = List.of();
    private LivingEntity blockingMob, combatMob;
    private int mobWaitSince, mobLastBlockedTick, mobTicks, mobPreviousSlot = -1, mobToolSlot = -1;
    private Vec3 mobReturn;
    private boolean verifyAfterCombat;

    private List<Object> layout() {
        return List.of(operation.get(), width.get(), height.get(), floor.get(), railings.get(), cornerBlock.get(), mineAboveRailings.get(), List.copyOf(blocksToPlace.get()));
    }

    public HighwayBuilder() {
        super(Categories.World, "highway-builder", "Build, repair or clear a highway with managed supplies and recoverable jobs.");
        runInMainMenu = true;
        autoSubscribe = false;
    }

    public Settings buildSettings() {
        Settings view = new Settings();
        SettingGroup build = view.createGroup("Build");
        for (Setting<?> setting : List.of(operation, heading, width, height, length, floor, railings, cornerBlock, mineAboveRailings, blocksToPlace)) build.add(setting);
        SettingGroup supplies = view.createGroup("Supplies", false);
        for (Setting<?> setting : List.of(searchShulkers, searchEnderChest, maxShulkersPerRestock, doubleEnderChests, keepShulkers, mineEnderChests, dontBreakTools, breakDurability, savePickaxes, saveEchests, minEmpty, fillerBlocks, trashItems)) supplies.add(setting);
        SettingGroup mobs = view.createGroup("Mob Handling", false);
        for (Setting<?> setting : List.of(clearPiglins, mobTypes, mobWeapons, mobAge, mobIgnoreNamed, mobPursuit,
            mobWaitTicks, mobTimeout, mobRotate, mobMinHealth, mobChaseRange)) mobs.add(setting);
        return view;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WButton button = theme.button("Open Highway Builder");
        button.action = () -> mc.gui.setScreen(new HighwayBuilderScreen(theme, this));
        return button;
    }

    public boolean hasJob() { return job; }
    public boolean isJobPaused() { return job && paused; }
    public boolean controlsPlayer() { return isActive() && job && !paused && !suspended && Utils.canUpdate() && mc.level == jobWorld; }
    public boolean doesDig() { return operation.get() == Operation.Build || operation.get() == Operation.ClearTunnel; }
    public boolean doesPave() { return operation.get() != Operation.ClearTunnel; }
    public int getPreviewWidth() { return width.get(); }
    public int getPreviewHeight() { return height.get(); }
    public boolean hasPreviewFloor() { return doesPave(); }
    public boolean hasPreviewRailings() { return doesPave() && operation.get() != Operation.Pave && railings.get(); }
    public boolean hasPreviewSupports() { return hasPreviewRailings() && cornerBlock.get(); }
    public boolean isPreviewEnabled() { return preview; }
    public Vec3 jobWorkPosition() { return workOrigin == null ? mc.player.position() : Vec3.atBottomCenterOf(workOrigin); }

    private HorizontalDirection selectedHeading() {
        return heading.get() == Heading.Facing ? HorizontalDirection.get(mc.player.getYRot()) : HorizontalDirection.valueOf(heading.get().name());
    }

    public String getStatus() {
        if (paused && job) return "Paused: " + status;
        return job && !waiting.isEmpty() ? waiting : status;
    }

    @Override
    public String getInfoString() {
        return getHudStatus() + " · " + Math.round(hud.distance()) + " blocks · " + getPavingRate();
    }

    public boolean isHudHealthy() { return job && !paused && !suspended && !hud.jammed(); }
    public String getHudStatus() { return isHudHealthy() ? "Healthy" : getStatus(); }
    public String getPavingRate() { return String.format(java.util.Locale.ROOT, "%.1f blocks/sec paved", hud.rate(averageWindow.get().seconds)); }

    private void updateHud() {
        double progress = completedDistance + pavingChecks.size() * (dir.diagonal ? Math.sqrt(2) : 1);
        if (controlsPlayer() && state == State.Forward && advancing) {
            Vec3 offset = mc.player.position().subtract(jobWorkPosition());
            double step = dir.diagonal ? Math.sqrt(2) : 1;
            progress += Math.clamp((offset.x * dir.offsetX + offset.z * dir.offsetZ) / step, 0, step);
        }
        hud.update(System.nanoTime() / 1e9, progress, (long) blocksBroken + blocksPlaced);
    }

    public String getPlanSummary() {
        if (!Utils.canUpdate()) return "Join a world to choose the highway origin.";
        HorizontalDirection direction = job ? dir : selectedHeading();
        int distance = testLength > 0 ? testLength : length.get();
        return "%s · %s (%s) · floor Y %d · %d wide × %d clear%s · %s".formatted(
            operation.get(), direction.name, direction.axis, (job ? workOrigin.getY() : mc.player.getBlockY()) - 1,
            width.get(), height.get(), hasPreviewRailings() ? " + railings" : "",
            distance == 0 ? "continuous" : Math.round(completedDistance) + " / " + distance + " blocks");
    }

    public String getSuppliesSummary() {
        if (!Utils.canUpdate()) return "Inventory unavailable.";
        return "%d building blocks\n%d usable pickaxes (%d reserved)\n%d ender chests (%d reserved)\n%d free slots".formatted(
            State.Forward.countItem(this, s -> s.getItem() instanceof BlockItem bi && blocksToPlace.get().contains(bi.getBlock())),
            State.Forward.countItem(this, this::usablePickaxe), savePickaxes.get(),
            State.Forward.countItem(this, s -> s.is(Items.ENDER_CHEST)), saveEchests.get(), emptySlots());
    }

    public String getReadiness() {
        String problem = readinessProblem();
        if (problem != null) return problem;
        return "Ready. Placement: " + placeRange.get() + " blocks. Mining: " + String.format("%.1f", mc.player.blockInteractionRange()) + " blocks; moves closer as needed.";
    }

    private String readinessProblem() {
        if (!Utils.canUpdate()) return "Join a world first.";
        HorizontalDirection direction = job ? dir : selectedHeading();
        if (direction != null && direction.diagonal && width.get() < 3) return "Diagonal highways need a width of at least 3.";
        if (doesPave() && blocksToPlace.get().isEmpty()) return "Choose at least one building material.";
        if (!mc.player.isCreative() && doesDig() && State.Forward.countItem(this, this::usablePickaxe) <= savePickaxes.get() && !hasStoredSupply(this::usablePickaxe))
            return "Add a usable pickaxe above the " + savePickaxes.get() + " reserved, or lower the reserve.";
        if (doesPave() && !State.Forward.hasItem(this, s -> s.getItem() instanceof BlockItem bi && blocksToPlace.get().contains(bi.getBlock())) && !hasStoredSupply(s -> s.getItem() instanceof BlockItem bi && (blocksToPlace.get().contains(bi.getBlock()) || mineEnderChests.get() && blocksToPlace.get().contains(Blocks.OBSIDIAN) && s.is(Items.ENDER_CHEST)))
            && !(mineEnderChests.get() && blocksToPlace.get().contains(Blocks.OBSIDIAN) && State.Forward.countItem(this, s -> s.is(Items.ENDER_CHEST)) > saveEchests.get()))
            return "Add building blocks or enable a supply source you are carrying.";
        return null;
    }

    private boolean hasStoredSupply(Predicate<ItemStack> wanted) {
        ItemStack[] contents = new ItemStack[27];
        if (searchShulkers.get()) {
            for (int i = 0; i < 36; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (!Utils.isShulker(stack.getItem())) continue;
                Utils.getItemsInContainerItem(stack, contents);
                for (ItemStack item : contents) if (wanted.test(item)) return true;
            }
        }
        if (searchEnderChest.get() && State.Forward.hasItem(this, s -> s.is(Items.ENDER_CHEST))) {
            int slots = enderChestSearchSlots();
            if (!EChestMemory.isKnown(slots)) return true; // A single-chest observation cannot rule out the second half.
            for (ItemStack stack : EChestMemory.ITEMS.subList(0, Math.min(slots, EChestMemory.ITEMS.size()))) {
                if (wanted.test(stack)) return true;
                if (searchShulkers.get() && Utils.isShulker(stack.getItem())) {
                    Utils.getItemsInContainerItem(stack, contents);
                    for (ItemStack item : contents) if (wanted.test(item)) return true;
                }
            }
        }
        return false;
    }

    private int enderChestSearchSlots() {
        return doubleEnderChests.get() && width.get() >= 2
            && State.Forward.countItem(this, stack -> stack.is(Items.ENDER_CHEST)) >= 2 ? 54 : 27;
    }

    private boolean hasSupplyContainer() {
        return searchShulkers.get() && State.Forward.hasItem(this, s -> Utils.isShulker(s.getItem()))
            || searchEnderChest.get() && State.Forward.hasItem(this, s -> s.is(Items.ENDER_CHEST));
    }

    private boolean needsFoodRestock() {
        return Modules.get().get(AutoEat.class).isActive() && mc.player.getFoodData().getFoodLevel() <= 14
            && !State.Forward.hasItem(this, stack -> Utils.isFood(stack) && !Modules.get().get(AutoEat.class).blacklist.get().contains(stack.getItem()));
    }

    private boolean usablePickaxe(ItemStack stack) {
        return stack.is(ItemTags.PICKAXES) && (!dontBreakTools.get() || HighwayPlan.usableTool(stack.getMaxDamage() - stack.getDamageValue(), stack.getMaxDamage(), breakDurability.get()));
    }

    private int emptySlots() {
        int free = 0;
        for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).isEmpty()) free++;
        return free;
    }

    public void startJob() { if (!job) enable(); }
    public void startTestRun() { if (!job) { testLength = 20; startJob(); } }
    public void stopJob() { if (isActive()) disable(); }
    public void pauseJob() { pauseJob("Manually paused"); }

    public void pauseJob(String reason) {
        if (!job) { status = reason; return; }
        boolean changed = !paused || !status.equals(reason);
        paused = true;
        status = reason;
        waiting = "";
        releaseControls();
        if (changed && Utils.canUpdate()) warning("Paused: %s", reason);
    }

    public void resumeJob() {
        if (!job || !paused) return;
        if (!Utils.canUpdate()) { status = "Join the original world before resuming."; return; }
        if (mc.level != jobWorld) { status = "World changed. Stop this job and start at the new position."; return; }
        if (mc.player.position().distanceToSqr(jobWorkPosition()) > 144) { status = "Return within 12 blocks of the build position to resume."; return; }
        if (heading.get() != Heading.Facing && selectedHeading() != dir) { status = "Stop the current job before changing its direction."; return; }
        String problem = state == State.Restock ? null : readinessProblem();
        if (problem != null) { status = problem; return; }
        boolean changed = !layout().equals(jobLayout);
        if (changed && (!pavingChecks.isEmpty() || !temporarySteps.isEmpty() || switch (state) {
            case Restock, ThrowOutTrash, MineEnderChests, PlaceEChestBlockade, MineEChestBlockade, PlaceShulkerBlockade, MineShulkerBlockade, ReLevel -> true;
            default -> false;
        })) {
            status = "Finish paving/supply/step recovery before changing the layout, or Stop and start a new job.";
            return;
        }
        paused = suspended = false;
        waiting = "";
        idleTicks = 0;
        mobTicks = 0;
        updateVariables();
        enableVelocity();
        if (changed) {
            jobLayout = layout();
            advancing = false;
            advancingPaving = List.of();
            pendingBreaks.clear();
            pendingPlaces.clear();
            liquidPlacements.clear();
            setState(State.Forward);
        }
        status = "Resuming " + operation.get().toString().toLowerCase();
    }

    private void enableVelocity() {
        Velocity velocity = Modules.get().get(Velocity.class);
        if (!velocity.isActive()) {
            velocity.enable();
            info("Auto-Toggled Velocity for Highway Builder");
        }
    }

    private void listen(boolean enabled) {
        if (listening == enabled) return;
        listening = enabled;
        if (enabled) MonocleClient.EVENT_BUS.subscribe(this);
        else MonocleClient.EVENT_BUS.unsubscribe(this);
    }

    public void setPreview(boolean enabled) {
        preview = enabled;
        listen(job || preview);
        updatePreview();
    }

    public void updatePreview() {
        if (job || !preview || !Utils.canUpdate()) return;
        dir = selectedHeading();
        leftDir = dir.rotateLeftSkipOne();
        rightDir = leftDir.opposite();
        workOrigin = mc.player.blockPosition();
        blockPosProvider = new PlannedBlockPosProvider();
    }

    private void releaseControls() {
        actionEpoch++;
        pendingPlaces.keySet().removeIf(pos -> !placeSequences.containsKey(pos));
        if (input != null) input.stop();
        if (Utils.canUpdate()) {
            if (mc.player.input == input && prevInput != null) mc.player.input = prevInput;
            if (drawingBow) mc.gameMode.releaseUsingItem(mc.player);
            stopWorkMining();
            restoreMobSlot();
        }
        drawingBow = false;
        normalMining = packetMining = null;
        walkGoal = null;
        walkPath = List.of();
        walkFailures = 0;
    }

    private void stopWorkMining() {
        if (normalMining != null) mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, normalMining.blockPos, normalMining.direction));
        if (packetMining != null) mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, packetMining.blockPos, packetMining.direction));
        mc.gameMode.stopDestroyBlock();
        normalMining = packetMining = null;
    }

    @Override
    public void onActivate() {
        String problem = readinessProblem();
        if (problem != null) {
            status = problem;
            disable();
            return;
        }

        dir = selectedHeading();
        leftDir = dir.rotateLeftSkipOne();
        rightDir = leftDir.opposite();
        workOrigin = mc.player.blockPosition();
        start = Vec3.atBottomCenterOf(workOrigin);
        jobLayout = layout();
        jobWorld = mc.level;
        job = true;
        paused = suspended = false;
        advancing = false;
        advancingPaving = List.of();
        pavingChecks.clear();
        blockingMob = combatMob = null;
        mobReturn = null;
        verifyAfterCombat = false;
        mobPreviousSlot = mobToolSlot = -1;
        completedDistance = 0;
        blocksBroken = blocksPlaced = idleTicks = 0;
        hud.reset(System.nanoTime() / 1e9);
        resetPredictionTracking();
        pendingBreaks.clear();
        pendingPlaces.clear();
        liquidPlacements.clear();
        breakSequences.clear();
        placeSequences.clear();
        temporarySteps.clear();
        restockTask.complete();
        State.Restock.resetSupplyJob(this);
        updateVariables();
        blockPosProvider = new PlannedBlockPosProvider();
        state = State.Forward;
        status = "Aligning with highway";
        waiting = "";
        listen(true);
        enableVelocity();
        setState(State.Center);

        if (Modules.get().get(InstantRebreak.class).isActive())
            warning("Instant Rebreak may interfere with this job; use the builder's ender-chest rebreak setting.");
        if (Modules.get().get(Speed.class).isActive() && dir.diagonal)
            warning("Speed is active and may prevent diagonal alignment.");
    }

    @Override
    public void onDeactivate() {
        boolean hadJob = job;
        if (job) updateHud();
        releaseControls();
        if (job && Utils.canUpdate()) {
            info("Completed road: (highlight)%.1f(default) blocks. Broken: (highlight)%d(default). Placed: (highlight)%d(default).",
                completedDistance, blocksBroken, blocksPlaced);
            if (!temporarySteps.isEmpty()) info("Stopped with %d temporary work step(s) left nearby.", temporarySteps.size());
        }
        job = false;
        paused = false;
        suspended = true;
        testLength = 0;
        resetPredictionTracking();
        pendingBreaks.clear();
        pendingPlaces.clear();
        liquidPlacements.clear();
        breakSequences.clear();
        placeSequences.clear();
        restockTask.complete();
        pavingChecks.clear();
        advancingPaving = List.of();
        State.Restock.resetSupplyJob(this);
        if (hadJob && !status.startsWith("Completed")) status = "Stopped: " + Math.round(completedDistance) + " blocks completed";
        blockingMob = combatMob = null;
        mobReturn = null;
        verifyAfterCombat = false;
        listen(preview);
    }

    @Override
    public void error(String message, Object... args) {
        String reason = String.format(message, args);
        pauseJob(reason);
        if (disconnectOnToggle.get() && Utils.canUpdate()) disconnect("%s", reason);
    }

    private void errorEarly(String message, Object... args) {
        error(message, args);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!job) { updatePreview(); return; }
        updateHud();
        if (!Utils.canUpdate()) return;
        if (mc.level != jobWorld) { pauseJob("World changed. Stop this job before starting here."); return; }
        if (paused || suspended) return;
        if (idleTicks >= 40 && mc.player.tickCount % 100 == 0) {
            MonocleClient.LOG.info("Highway stalled: state={}, status={}, feet={}, held={}, pendingPlaces={}, pendingBreaks={}, normalMine={}, packetMine={}, vanillaMining={}, flush={}, probe={}",
                state, status, mc.player.position(), mc.player.getMainHandItem(), pendingPlaces, pendingBreaks,
                normalMining == null ? null : normalMining.blockPos, packetMining == null ? null : packetMining.blockPos,
                mc.gameMode.isDestroying(), predictionFlushRequested, predictionProbeSequence);
        }
        if (!layout().equals(jobLayout)) { pauseJob("Setup changed. Review the plan, then Resume to apply it."); return; }
        if (width.get() < 3 && dir.diagonal) { pauseJob("Diagonal highways require width 3 or greater."); return; }

        String reason = Modules.get().get(AutoEat.class).eating || Modules.get().get(AutoGap.class).isEating() ? "Paused for eating"
            : Modules.get().get(KillAura.class).attacking ? "Paused for combat"
            : pauseOnLag.get() && TickRate.INSTANCE.getTimeSinceLastTick() >= 1.5f ? "Waiting for server" : "";
        if (!reason.isEmpty()) {
            waiting = reason;
            input.stop();
            actionEpoch++;
            pendingPlaces.keySet().removeIf(pos -> !placeSequences.containsKey(pos));
            if (drawingBow) { mc.gameMode.releaseUsingItem(mc.player); drawingBow = false; }
            return;
        }
        waiting = "";
        if (mc.player.input != input) { pauseJob("Another feature took movement control."); return; }
        if (mc.player.position().distanceToSqr(jobWorkPosition()) > 144) { pauseJob("Moved too far from the build position."); return; }
        if (!recoverCursor()) return;
        if (state.recoverSealing(this)) return;
        if (tickPredictionFlush()) return;

        if (tickMobCombat()) return;

        count = queuedHotbarSlots = 0;
        waitingForMob = false;
        if (mc.player.getY() < workOrigin.getY() - 0.5 && state != State.ReLevel) setState(State.ReLevel);
        if ((state == State.MineFront || state == State.MineFloor || state == State.MineRailings || state == State.MineAboveRailings) && needsBarrierSealing()) {
            actionEpoch++;
            pendingPlaces.keySet().removeIf(pos -> !placeSequences.containsKey(pos));
            stopWorkMining();
            setState(State.FillLiquids);
        }
        tickDoubleMine();
        if (!paused) state.tick(this);
        if (waitingForMob) paveWhileWaiting();

        if (breakTimer > 0) breakTimer--;
        if (placeTimer > 0) placeTimer--;
        if (++idleTicks > 400 && !paused && !state.retryReturn(this)) pauseJob("No progress for 20 seconds while " + status.toLowerCase()
            + " (" + pendingPlaces.size() + " placements, " + pendingBreaks.size() + " breaks awaiting confirmation). Check the server response, then Resume.");
    }

    private int pendingPredictionSince = -1, predictionProbeTick = -1, predictionProbeSequence = -1, predictionProbeAttempts;
    private boolean predictionFlushRequested, sendingPredictionProbe;

    private void resetPredictionTracking() {
        pendingPredictionSince = predictionProbeTick = predictionProbeSequence = -1;
        predictionProbeAttempts = 0;
        predictionFlushRequested = sendingPredictionProbe = false;
    }

    private void requestPredictionFlush() {
        if (!predictionFlushRequested) {
            actionEpoch++;
            pendingPlaces.keySet().removeIf(pos -> !placeSequences.containsKey(pos));
        }
        predictionFlushRequested = true;
        input.stop();
        // A known canceled prediction needs verification before another section can use it as support.
        pendingPredictionSince = mc.player.tickCount - 12;
    }

    static boolean predictionProbeDue(int tick, int pendingSince, int previousProbe) {
        return pendingSince >= 0 && tick - pendingSince >= 12 && (previousProbe < 0 || tick - previousProbe >= 40);
    }

    static boolean sealingRetryDue(int stalledTicks) { return stalledTicks >= 40; }

    static boolean serverConfirmedBreak(BlockState before, BlockState serverState) {
        return before != null && !before.equals(serverState);
    }

    private boolean tickPredictionFlush() {
        // Explicit reconciliation must not wait on a mining task that it is meant to recover.
        if (predictionFlushRequested) stopWorkMining();
        boolean pending = predictionFlushRequested || !pendingBreaks.isEmpty()
            || pendingPlaces.keySet().stream().anyMatch(pos -> !isBackgroundPaving(pos));
        if (!pending && predictionProbeSequence < 0) {
            pendingPredictionSince = -1;
            predictionProbeAttempts = 0;
            return false;
        }
        int tick = mc.player.tickCount;
        if (pendingPredictionSince < 0) pendingPredictionSince = tick;
        if (predictionProbeSequence >= 0) {
            input.stop();
            status = "Waiting for server to resolve block predictions";
            if (tick - predictionProbeTick < 40) return true;
            if (predictionProbeAttempts >= 3 || tick - predictionProbeTick >= 120) {
                predictionProbeSequence = -1;
                predictionProbeAttempts = 0;
                predictionFlushRequested = true;
                pauseJob("The server has not acknowledged block verification. No new blocks were placed; Resume to retry.");
                return true;
            }
        }
        if (normalMining != null || packetMining != null || mc.gameMode.isDestroying()
            || !predictionFlushRequested && state.name().startsWith("Mine"))
            return predictionProbeSequence >= 0;
        if (predictionProbeSequence < 0 && !predictionProbeDue(tick, pendingPredictionSince, predictionProbeTick)) {
            if (predictionFlushRequested) {
                input.stop();
                status = "Waiting to verify canceled placement";
                return true;
            }
            return false;
        }
        BlockPos probe = BlockPos.containing(mc.player.getEyePosition());
        if (!mc.level.getBlockState(probe).isAir()) {
            if (predictionProbeSequence >= 0) return true;
            if (predictionFlushRequested) {
                pauseJob("Move into clear space before verifying the canceled placement, then Resume.");
                return true;
            }
            return false;
        }
        input.stop();
        status = "Verifying stalled block predictions";
        actionEpoch++;
        pendingPlaces.keySet().removeIf(pos -> !placeSequences.containsKey(pos));
        predictionProbeTick = tick;
        predictionProbeAttempts++;
        sendingPredictionProbe = true;
        try {
            // A sequenced ABORT acknowledges prior predictions without starting a mine or placing a block.
            mc.gameMode.startPrediction(mc.level, sequence -> {
                predictionProbeSequence = sequence;
                return new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, probe, Direction.DOWN, sequence);
            });
        } finally {
            sendingPredictionProbe = false;
        }
        return true;
    }

    public void onServerBlockUpdate(BlockPos position, BlockState serverState) {
        if (!job || !Utils.canUpdate() || mc.level != jobWorld) return;
        int pendingBefore = pendingPlaces.size() + pendingBreaks.size();
        Block expected = pendingPlaces.get(position);
        if (expected != null && serverState.is(expected)) {
            pendingPlaces.remove(position);
            placeSequences.remove(position);
            blocksPlaced++;
            idleTicks = 0;
        }
        if (serverConfirmedBreak(pendingBreaks.get(position), serverState)) {
            pendingBreaks.remove(position);
            breakSequences.remove(position);
            blocksBroken++;
            idleTicks = 0;
        }
        if (!predictionFlushRequested && pendingPlaces.size() + pendingBreaks.size() < pendingBefore)
            pendingPredictionSince = mc.player.tickCount;
    }

    public void onServerBlockAck(int sequence) {
        if (!job || !Utils.canUpdate() || mc.level != jobWorld) return;
        int pendingBefore = pendingPlaces.size() + pendingBreaks.size();
        boolean probeAcknowledged = predictionProbeSequence >= 0 && sequence >= predictionProbeSequence;
        if (probeAcknowledged) {
            predictionProbeSequence = -1;
            predictionProbeAttempts = 0;
            predictionFlushRequested = false;
        }
        pendingPlaces.entrySet().removeIf(entry -> {
            Integer sent = placeSequences.get(entry.getKey());
            if (sent != null && sent <= sequence) {
                if (mc.level.getBlockState(entry.getKey()).is(entry.getValue())) {
                    blocksPlaced++;
                    idleTicks = 0;
                }
                placeSequences.remove(entry.getKey());
                return true;
            }
            return false;
        });
        pendingBreaks.entrySet().removeIf(entry -> {
            Integer sent = breakSequences.get(entry.getKey());
            if (sent == null) return probeAcknowledged && normalMining == null && packetMining == null && !mc.gameMode.isDestroying();
            if (sent > sequence) return false;
            if (serverConfirmedBreak(entry.getValue(), mc.level.getBlockState(entry.getKey()))) {
                blocksBroken++;
                idleTicks = 0;
            } else if ((!probeAcknowledged && state != State.Forward) || normalMining != null || packetMining != null || mc.gameMode.isDestroying()) {
                return false; // A START acknowledgment is not the end of an ongoing mine.
            }
            breakSequences.remove(entry.getKey());
            return true;
        });
        if (!predictionFlushRequested && pendingPlaces.size() + pendingBreaks.size() < pendingBefore)
            pendingPredictionSince = mc.player.tickCount;
    }

    @EventHandler
    private void onPacketSent(PacketEvent.Sent event) {
        if (!job) return;
        if (event.packet instanceof ServerboundUseItemOnPacket packet) {
            if (placingTarget != null) placeSequences.put(placingTarget, packet.getSequence());
        } else if (event.packet instanceof ServerboundPlayerActionPacket packet) {
            if ((packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                || packet.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) && pendingBreaks.containsKey(packet.getPos()))
                breakSequences.put(packet.getPos().immutable(), packet.getSequence());
            if (packet.getAction() == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK && !sendingPredictionProbe) {
                pendingBreaks.remove(packet.getPos());
                breakSequences.remove(packet.getPos());
            }
        }
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundContainerSetContentPacket p) containerId = p.containerId();
    }

    @EventHandler
    private void onGameLeave(GameLeftEvent event) {
        if (job) pauseJob("Disconnected. Stop and restart after checking the new world and position.");
        suspended = true;
        inventory = false;
    }

    private HighwayPlan.Cell cell(BlockPos pos) { return new HighwayPlan.Cell(pos.getX(), pos.getY(), pos.getZ()); }
    private BlockPos block(HighwayPlan.Cell pos) { return new BlockPos(pos.x(), pos.y(), pos.z()); }

    private boolean clearBody(BlockPos feet) {
        if (!mc.level.hasChunkAt(feet)) return false;
        for (int y = 0; y < 2; y++) {
            BlockState state = mc.level.getBlockState(feet.above(y));
            if (!state.getFluidState().isEmpty() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.POWDER_SNOW)) return false;
        }
        return mc.level.noCollision(mc.player, new AABB(feet.getX() + 0.19, feet.getY() + 0.01, feet.getZ() + 0.19, feet.getX() + 0.81, feet.getY() + 1.8, feet.getZ() + 0.81));
    }

    private boolean standable(HighwayPlan.Cell cell) {
        BlockPos feet = block(cell);
        if (!clearBody(feet) || pendingPlaces.containsKey(feet.below())) return false;
        BlockState floorState = mc.level.getBlockState(feet.below());
        return floorState.getFluidState().isEmpty() && !floorState.is(Blocks.MAGMA_BLOCK) && !floorState.is(Blocks.CACTUS)
            && !floorState.is(Blocks.CAMPFIRE) && !floorState.is(Blocks.SOUL_CAMPFIRE)
            && Block.isShapeFullBlock(floorState.getCollisionShape(mc.level, feet.below()));
    }

    private boolean safeStep(HighwayPlan.Cell from, HighwayPlan.Cell to) {
        if (to.y() > from.y()) return clearBody(block(from).above());
        if (to.y() < from.y()) return clearBody(block(to).above());
        return true;
    }

    static boolean obstructionPiglin(EntityType<?> type, boolean named) {
        return !named && (type == EntityTypes.PIGLIN || type == EntityTypes.PIGLIN_BRUTE || type == EntityTypes.ZOMBIFIED_PIGLIN);
    }

    private boolean mobTargetAllowed(LivingEntity target) {
        // Keep the family boundary at runtime too: explicit IDs can bypass a setting widget's filter.
        return obstructionPiglin(target.getType(), mobIgnoreNamed.get() && target.hasCustomName())
            && mobTypes.get().contains(target.getType()) && mobAge.get().test(target)
            && mobWeapons.get().accepts(target.isHolding(Items.CROSSBOW));
    }

    private boolean mobMayPursue(LivingEntity target) {
        return mobPursuit.get().allows(target.isHolding(Items.CROSSBOW));
    }

    private void mobSettingsChanged() {
        blockingMob = null;
        if (mobReturn == null) return; // No combat callback owns work; do not cancel unrelated paving predictions.
        if (controlsPlayer()) resetMobMovement();
        else actionEpoch++;
    }

    static boolean withinMobArea(Vec3 position, Vec3 anchor, double range) {
        return Math.abs(position.y - anchor.y) <= 2
            && Math.hypot(position.x - anchor.x, position.z - anchor.z) <= range;
    }

    private BlockPos walkingFeet() {
        return BlockPos.containing(mc.player.getX(), HighwayPlan.roadFeetY(mc.player.getY(), workOrigin.getY()), mc.player.getZ());
    }

    private boolean walkingStandable(HighwayPlan.Cell feet) {
        // ponytail: combat uses flat, local routes only; add stepped pursuit if elevated targets become a real need.
        if (!standable(feet)) return false;
        if (mobReturn == null) return true;
        Vec3 point = Vec3.atBottomCenterOf(block(feet));
        if (feet.y() != mobReturn.y || !withinMobArea(point, mobReturn, mobChaseRange.get()) || point.distanceToSqr(jobWorkPosition()) > 121) return false;
        // A touching mob must not invalidate current footing, but do not route through occupied cells.
        return feet.equals(cell(walkingFeet())) || mc.level.getEntitiesOfClass(LivingEntity.class,
            mc.player.getBoundingBox().move(point.subtract(mc.player.position())),
            entity -> entity != mc.player && entity.isAlive() && !entity.isSpectator()).isEmpty();
    }

    private void resetMobMovement() {
        actionEpoch++;
        pendingPlaces.keySet().removeIf(pos -> !placeSequences.containsKey(pos));
        queuedHotbarSlots = 0;
        input.stop();
        stopWorkMining();
        walkGoal = null;
        walkPath = List.of();
    }

    private void restoreMobSlot() {
        if (mobPreviousSlot >= 0 && mc.player.getInventory().getSelectedSlot() == mobToolSlot) InvUtils.swap(mobPreviousSlot, false);
        mobPreviousSlot = mobToolSlot = -1;
    }

    private boolean waitForMob(AABB area) {
        LivingEntity blocker = mc.level.getEntitiesOfClass(LivingEntity.class, area,
            entity -> entity != mc.player && entity.isAlive() && !entity.isSpectator() && entity.blocksBuilding
                && !entity.isPassengerOfSameVehicle(mc.player)).stream()
            .min(Comparator.<LivingEntity>comparingInt(entity -> mobTargetAllowed(entity) ? 0 : 1)
                .thenComparingDouble(entity -> entity.distanceToSqr(mc.player))).orElse(null);
        if (blocker == null) return false;
        waitingForMob = true;
        input.stop();
        idleTicks = 0; // A real entity obstruction is a wait, not a failed block/server timeout.
        walkTicks = 0;
        status = "Waiting for " + blocker.getName().getString() + " to clear the work area";
        int tick = mc.player.tickCount;
        if (blockingMob != blocker || tick - mobLastBlockedTick > Math.max(5L, 1L + placeDelay.get())) {
            blockingMob = blocker;
            mobWaitSince = tick;
        }
        mobLastBlockedTick = tick;
        if (!clearPiglins.get() || state != State.Forward || mobReturn != null
            || !mobTargetAllowed(blocker) || tick - mobWaitSince < mobWaitTicks.get()) return true;
        if (!mobMayPursue(blocker) && (!mc.player.isWithinAttackRange(mc.player.getMainHandItem(), blocker.getBoundingBox(), 0)
            || !mc.player.hasLineOfSight(blocker))) {
            status = "Waiting for " + blocker.getName().getString() + " to enter melee reach (pursuit disabled for this target)";
            return true;
        }
        Vec3 anchor = Vec3.atBottomCenterOf(walkingFeet());
        if (!mc.player.onGround() || !standable(cell(walkingFeet()))) {
            pauseJob("Cannot start piglin clearing without confirmed safe footing.");
            return true;
        }
        resetMobMovement();
        combatMob = blocker;
        mobReturn = anchor;
        mobTicks = 0;
        verifyAfterCombat = true;
        status = "Clearing blocking " + blocker.getName().getString();
        return true;
    }

    private boolean mobAttackReady(LivingEntity target) {
        return controlsPlayer() && clearPiglins.get() && combatMob == target && !target.isRemoved() && target.isAlive()
            && mobTargetAllowed(target)
            && mc.player.getHealth() > mobMinHealth.get() && mc.player.onGround()
            && !mc.player.isUsingItem() && mc.player.containerMenu == mc.player.inventoryMenu
            && mc.player.containerMenu.getCarried().isEmpty()
            && !Modules.get().get(AutoEat.class).eating && !Modules.get().get(AutoGap.class).isEating()
            && !Modules.get().get(KillAura.class).attacking
            && (!pauseOnLag.get() || TickRate.INSTANCE.getTimeSinceLastTick() < 1.5f)
            && usablePickaxe(mc.player.getMainHandItem())
            && State.Forward.countItem(this, this::usablePickaxe) > savePickaxes.get()
            && withinMobArea(target.position(), mobReturn, mobChaseRange.get())
            && mc.level.getEntitiesOfClass(EndCrystal.class, mc.player.getBoundingBox().inflate(6), Entity::isAlive).isEmpty()
            && walkingStandable(cell(walkingFeet()))
            && mc.player.getAttackStrengthScale(0) >= 1
            && mc.player.isWithinAttackRange(mc.player.getMainHandItem(), target.getBoundingBox(), 0)
            && mc.player.hasLineOfSight(target);
    }

    private boolean tickMobCombat() {
        if (mobReturn == null) return false;
        input.stop();
        mc.player.setSprinting(false);
        idleTicks = 0;
        if (!clearPiglins.get() && combatMob != null) {
            combatMob = null;
            resetMobMovement();
        }
        if (combatMob != null && combatMob.isDeadOrDying()) {
            combatMob = null; // Health/death state confirms the kill; an unload/removal alone never does.
            resetMobMovement();
            restoreMobSlot();
            mobTicks = 0;
        }
        if (++mobTicks > mobTimeout.get() * 20) { pauseJob("Piglin clearing timed out. Check nearby threats, then Resume or disable piglin clearing."); return true; }
        if (mc.player.getHealth() <= mobMinHealth.get()) { pauseJob("Low health during piglin clearing. Take control and recover before resuming."); return true; }
        if (combatMob == null) {
            restoreMobSlot();
            status = "Returning to verify paving after piglin clearing";
            if (walkToWorkPosition(mobReturn)) {
                resetMobMovement();
                mobReturn = null;
                blockingMob = null;
            }
            return true;
        }
        LivingEntity target = combatMob;
        if (target.isRemoved() || mc.level.getEntity(target.getId()) != target) {
            pauseJob("The blocking piglin disappeared without a confirmed death. Check for threats; disable piglin clearing to abandon pursuit.");
            return true;
        }
        if (!mobTargetAllowed(target)) {
            pauseJob("The engaged piglin no longer matches your target filters. It may still be angry; review filters or disable piglin clearing before resuming.");
            return true;
        }
        if (!withinMobArea(target.position(), mobReturn, mobChaseRange.get())) {
            pauseJob("Piglin pursuit reached its limit. Take control or disable piglin clearing.");
            return true;
        }
        if (!mc.player.onGround() || !walkingStandable(cell(walkingFeet()))) {
            pauseJob("Lost safe footing during piglin clearing. Take control before resuming.");
            return true;
        }
        if (!mc.level.getEntitiesOfClass(EndCrystal.class, mc.player.getBoundingBox().inflate(6), Entity::isAlive).isEmpty()) {
            pauseJob("End crystal near piglin combat. Clear the threat before resuming.");
            return true;
        }
        if (mc.player.isUsingItem() || mc.player.containerMenu != mc.player.inventoryMenu) {
            status = "Waiting for item use or inventory to close before piglin clearing";
            return true;
        }
        if (State.Forward.countItem(this, this::usablePickaxe) <= savePickaxes.get()) {
            pauseJob("Piglin clearing needs a usable pickaxe above the configured tool reserve.");
            return true;
        }
        if (mobPreviousSlot < 0) mobPreviousSlot = mc.player.getInventory().getSelectedSlot();
        int slot = State.Forward.findAndMoveToHotbar(this, this::usablePickaxe);
        if (slot < 0 || !controlsPlayer()) return true;
        if (mobToolSlot != slot || mc.player.getInventory().getSelectedSlot() != slot) {
            mobToolSlot = slot;
            InvUtils.swap(slot, false);
            mc.player.resetAttackStrengthTicker();
            return true;
        }
        status = "Clearing " + target.getName().getString() + " (" + Math.ceil(target.getHealth()) + " health)";
        if (mc.player.isWithinAttackRange(mc.player.getMainHandItem(), target.getBoundingBox(), 0) && mc.player.hasLineOfSight(target)) {
            walkGoal = null;
            walkPath = List.of();
            if (mobAttackReady(target)) {
                int epoch = actionEpoch;
                Runnable attack = () -> {
                    if (epoch != actionEpoch || !mobAttackReady(target)) return;
                    mc.player.setSprinting(false);
                    mc.gameMode.attack(mc.player, target);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                };
                if (mobRotate.get()) {
                    Vec3 aim = target.getEyePosition();
                    Rotations.rotate(Rotations.getYaw(aim), Rotations.getPitch(aim), 100, attack);
                } else attack.run();
            }
            return true;
        }
        if (!mobMayPursue(target)) {
            resetMobMovement();
            status = "Waiting for engaged " + target.getName().getString() + " to return to melee reach (pursuit disabled for this target)";
            return true;
        }
        // Pick a reachable stance beside the mob, never its occupied tile or a temporary work step.
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos center = BlockPos.containing(target.getX(), mobReturn.y, target.getZ());
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            BlockPos feet = center.offset(x, 0, z);
            Vec3 eye = Vec3.atBottomCenterOf(feet).add(0, mc.player.getEyeHeight(), 0);
            if (!walkingStandable(cell(feet)) || new AABB(feet).expandTowards(0, 0.8, 0).intersects(target.getBoundingBox())
                || target.getBoundingBox().distanceToSqr(eye) > 4
                || mc.level.clip(new ClipContext(eye, target.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() != HitResult.Type.MISS) continue;
            candidates.add(feet);
        }
        candidates.sort(Comparator.comparingDouble(feet -> Vec3.atBottomCenterOf(feet).distanceToSqr(mc.player.position())));
        for (BlockPos feet : candidates) {
            if (HighwayPlan.route(cell(walkingFeet()), cell(feet), this::walkingStandable, this::safeStep).isEmpty()) continue;
            walkToWorkPosition(Vec3.atBottomCenterOf(feet));
            return true;
        }
        pauseJob("No safe, level approach to the blocking piglin inside the pursuit limit. Take control or disable piglin clearing.");
        return true;
    }

    public boolean walkToWorkPosition(Vec3 target) {
        if (!controlsPlayer()) return false;
        if (predictionFlushRequested) { input.stop(); return false; }
        target = walkingTarget(target, workOrigin.getY());
        Vec3 current = mc.player.position();
        double horizontal = Math.hypot(target.x - current.x, target.z - current.z);
        if (horizontal < 0.15 && Math.abs(target.y - current.y) < 0.25 && mc.player.onGround()) {
            input.stop();
            walkGoal = null;
            walkPath = List.of();
            walkFailures = 0;
            return true;
        }
        if (walkGoal == null || walkGoal.distanceToSqr(target) > 0.01) {
            walkGoal = target;
            walkPath = HighwayPlan.route(cell(walkingFeet()), cell(BlockPos.containing(target)), this::walkingStandable, this::safeStep);
            walkIndex = walkPath.size() > 1 ? 1 : 0;
            walkTicks = 0;
            lastWalkDistance = Double.MAX_VALUE;
            if (walkPath.isEmpty()) {
                input.stop();
                walkGoal = null; // Recompute against fresh world/confirmation state, not a cached failure.
                BlockPos goal = BlockPos.containing(target);
                String reason = pendingPlaces.containsKey(goal.below()) ? "destination footing awaiting confirmation"
                    : !clearBody(goal) ? "destination body space obstructed, hazardous or unloaded"
                    : !standable(cell(goal)) ? "destination has no verified safe floor"
                    : "no connected safe steps inside the local route limit";
                status = "Rechecking walking route: " + reason;
                if (++walkFailures == 1 || walkFailures == 40) {
                    MonocleClient.LOG.info("Highway route rejected: from={}, to={}, reason={}, floor={}, body={}, above={}, pendingPlaces={}",
                        walkingFeet(), goal, reason, mc.level.getBlockState(goal.below()), mc.level.getBlockState(goal), mc.level.getBlockState(goal.above()), pendingPlaces);
                }
                if (walkFailures >= 40) pauseJob("No safe walking route to " + goal.toShortString() + ": " + reason + ". Check the route, then Resume.");
                else if (!pendingPlaces.isEmpty() || !pendingBreaks.isEmpty()) requestPredictionFlush();
                return false;
            }
            walkFailures = 0;
        }
        if (walkPath.isEmpty()) return false;
        HighwayPlan.Cell next = walkPath.get(walkIndex);
        Vec3 point = walkIndex == walkPath.size() - 1 ? target : Vec3.atBottomCenterOf(block(next));
        if (!walkingStandable(next)) {
            walkGoal = null;
            input.stop();
            return false;
        }
        if (current.distanceToSqr(point) < 0.09 && walkIndex < walkPath.size() - 1) {
            next = walkPath.get(++walkIndex);
            point = walkIndex == walkPath.size() - 1 ? target : Vec3.atBottomCenterOf(block(next));
            lastWalkDistance = Double.MAX_VALUE;
        }
        if (!walkingStandable(next) || !safeStep(cell(walkingFeet()), next)) {
            walkGoal = null;
            input.stop();
            status = "Rechecking changed walking step";
            return false;
        }
        double distance = current.distanceTo(point);
        if (distance < lastWalkDistance - 0.025) {
            walkTicks = 0;
            idleTicks = 0;
            lastWalkDistance = distance;
        } else if (++walkTicks > 100) {
            if (!state.retryReturn(this)) pauseJob("Movement blocked while " + status.toLowerCase() + ". Clear the path, then Resume.");
            return false;
        }
        if (mobReturn == null && waitForMob(mc.player.getBoundingBox().expandTowards(point.subtract(current).normalize().scale(0.5)))) return false;
        input.stop();
        mc.player.setYRot((float) Rotations.getYaw(point));
        input.forward(true);
        boolean climbing = next.y() > mc.player.getY() + 0.4;
        input.jump(climbing && mc.player.onGround());
        return false;
    }

    static Vec3 backstepTarget(Vec3 position, float yaw) {
        return position.subtract(Vec3.directionFromRotation(0, yaw).normalize());
    }

    static Vec3 walkingTarget(Vec3 position, int roadY) {
        // Use the same road-height tolerance as walkingFeet; flooring 63.999999 puts the goal inside a Y=64 road.
        return Math.abs(position.y - roadY) <= 0.25 ? new Vec3(position.x, roadY, position.z) : position;
    }

    private boolean advanceRoad() {
        if (predictionFlushRequested) { input.stop(); return false; }
        Vec3 next = Vec3.atBottomCenterOf(workOrigin.offset(dir.offsetX, 0, dir.offsetZ));
        Vec3 current = mc.player.position();
        if (reachedNextSection()) {
            walkGoal = null;
            walkPath = List.of();
            return true;
        }
        if (pendingPlaces.containsKey(BlockPos.containing(next).below())) {
            input.stop();
            status = "Waiting for the next supporting block";
            return false;
        }
        if (current.distanceToSqr(next) > 9 || Math.abs(current.y - next.y) > 0.25) return walkToWorkPosition(next);

        // Sweep the player's footprint, including both diagonal transition cells.
        int samples = Math.max(1, (int) Math.ceil(current.distanceTo(next) * 4));
        for (int i = 0; i <= samples; i++) {
            Vec3 point = current.lerp(next, (double) i / samples);
            if (waitForMob(mc.player.getBoundingBox().move(point.subtract(current)))) return false;
            int feetY = HighwayPlan.roadFeetY(point.y, workOrigin.getY());
            for (double x : new double[] {-0.29, 0.29}) for (double z : new double[] {-0.29, 0.29}) {
                if (!standable(cell(BlockPos.containing(point.x + x, feetY, point.z + z)))) {
                    input.stop();
                    status = "Waiting for a clear, supported path";
                    return false;
                }
            }
        }
        input.stop();
        mc.player.setYRot((float) Rotations.getYaw(next.add(dir.offsetX * 0.5, 0, dir.offsetZ * 0.5)));
        input.forward(true);
        return false;
    }

    private boolean reachedNextSection() {
        Vec3 next = Vec3.atBottomCenterOf(workOrigin.offset(dir.offsetX, 0, dir.offsetZ));
        return HighwayPlan.passedSection(dir.offsetX, dir.offsetZ, mc.player.getX() - next.x, mc.player.getZ() - next.z)
            && Math.abs(mc.player.getY() - next.y) < 0.25 && mc.player.onGround();
    }

    private List<PavingTarget> pavingTargets() {
        return pavingTargets(0);
    }

    private List<PavingTarget> pavingTargets(int sectionOffset) {
        BlockPos origin = workOrigin.offset(dir.offsetX * sectionOffset, 0, dir.offsetZ * sectionOffset);
        return HighwayPlan.paving(dir.offsetX, dir.offsetZ, width.get(), doesPave(), hasPreviewRailings(), hasPreviewSupports()).stream()
            .map(p -> new PavingTarget(origin.offset(p.position().x(), p.position().y(), p.position().z()), p.filler(),
                operation.get() == Operation.Build && !p.filler() && (p.position().y() == 0 || floor.get() == Floor.Replace))).toList();
    }

    private boolean pavingWithinLength(int sectionOffset) {
        int limit = testLength > 0 ? testLength : length.get();
        return HighwayPlan.withinLength(dir.diagonal, completedDistance, pavingChecks.size() + sectionOffset + 1, limit);
    }

    private void paveAhead() {
        for (int offset = 1; offset < blocksAheadToPave.get(); offset++) {
            if (pavingWithinLength(offset)) paveAvailable(pavingTargets(offset), false);
        }
    }

    private boolean isTrailingPaving(BlockPos pos) {
        for (List<PavingTarget> section : pavingChecks) {
            if (section == pavingChecks.peekLast()) break;
            for (PavingTarget target : section) if (target.position().equals(pos)) return true;
        }
        return false;
    }

    private boolean isBackgroundPaving(BlockPos pos) {
        if (isTrailingPaving(pos)) return true;
        // Include the whole supported window so lowering the setting cannot promote in-flight attempts to blocking work.
        for (int offset = 1; offset < 5; offset++) {
            if (pavingTargets(offset).stream().anyMatch(target -> target.position().equals(pos))) return true;
        }
        if (pavingTargets().stream().noneMatch(target -> target.position().equals(pos))) return false;
        Vec3 next = Vec3.atBottomCenterOf(workOrigin.offset(dir.offsetX, 0, dir.offsetZ));
        // Approaching edge blocks are speculative, but the next step's actual footing must resolve.
        return !mc.player.getBoundingBox().expandTowards(next.subtract(mc.player.position())).move(0, -0.05, 0)
            .intersects(new AABB(pos));
    }

    private void retirePavingSection() {
        for (PavingTarget retired : pavingChecks.removeFirst()) {
            pendingPlaces.remove(retired.position());
            placeSequences.remove(retired.position());
        }
        completedDistance += dir.diagonal ? Math.sqrt(2) : 1;
        idleTicks = 0;
    }

    private void paveBehind() {
        for (List<PavingTarget> section : pavingChecks) {
            if (section == pavingChecks.peekLast()) break;
            paveAvailable(section, waitingForMob);
        }
    }

    private boolean verifyUnderfoot() {
        if (pavingChecks.isEmpty()) return true; // The starting platform is outside this job's paving.
        status = "Verifying paving underfoot";
        boolean verified = paveSection(pavingChecks.getLast(), true);
        if (!verified && controlsPlayer() && state == State.Forward) {
            // An ACK wait holds movement, not the remaining placement budget.
            if (pavingWithinLength(0)) paveAvailable(pavingTargets(), false);
            paveAhead();
            paveBehind();
        }
        return verified;
    }

    private void paveWhileWaiting() {
        if (!waitingForMob || !controlsPlayer() || state != State.Forward || mobReturn != null || predictionFlushRequested) return;
        // The blocked cells remain required work. This pass never advances or changes the mob we're waiting on.
        for (List<PavingTarget> section : pavingChecks) paveAvailable(section, true);
        if (pavingWithinLength(0)) paveAvailable(pavingTargets(), true);
        for (int offset = 1; offset < blocksAheadToPave.get(); offset++) {
            if (pavingWithinLength(offset)) paveAvailable(pavingTargets(offset), true);
        }
    }

    private void paveAvailable(List<PavingTarget> targets, boolean opportunistic) {
        // Preplace only from here, using carried supplies. No extra walking, excavation or restock for speculative rows.
        if (!controlsPlayer() || state != State.Forward || mobReturn != null || predictionFlushRequested || !pendingBreaks.isEmpty()) return;
        for (PavingTarget target : targets) {
            if (count >= placementsPerTick.get() || placeTimer > 0) return;
            BlockPos pos = target.position();
            if (!mc.level.hasChunkAt(pos)) {
                if (opportunistic) continue;
                return;
            }
            if (pendingPlaces.containsKey(pos) || !mc.level.getBlockState(pos).canBeReplaced()) continue;
            int slot = liquidSupplySlot(mc.player.getInventory(), !target.filler(), fillerBlocks.get(), blocksToPlace.get());
            if (slot < 0) return;
            BlockItem material = (BlockItem) mc.player.getInventory().getItem(slot).getItem();
            if (pos.distToCenterSqr(mc.player.getEyePosition()) > placeRange.get() * placeRange.get()
                || !BlockUtils.canPlaceBlock(pos, true, material.getBlock())) {
                if (opportunistic) continue;
                return;
            }
            if (slot >= 9) {
                int hotbarSlot = State.Forward.findHotbarSlot(this, false);
                if (hotbarSlot < 0 || !controlsPlayer()) return;
                InvUtils.move().from(slot).toHotbar(hotbarSlot);
                slot = hotbarSlot;
            }
            if (placeWorkBlock(pos, slot)) count++;
            else if (!opportunistic) return;
            if (!controlsPlayer() || predictionFlushRequested) return;
        }
    }

    private boolean paveSection(List<PavingTarget> targets, boolean verifying) {
        input.stop();
        boolean complete = true;
        for (PavingTarget target : targets) {
            BlockPos pos = target.position();
            if (!mc.level.hasChunkAt(pos)) { complete = false; continue; }
            if (pendingPlaces.containsKey(pos)) {
                if (verifying) complete = false;
                continue;
            }
            BlockState existing = mc.level.getBlockState(pos);
            if (!existing.canBeReplaced()) {
                if (verifying && pavingNeedsMining(existing, target.replace(), blocksToPlace.get())) {
                    pavingRepairTarget = pos;
                    setState(State.MinePaving);
                    return false;
                }
                continue;
            }
            if (count >= placementsPerTick.get() || placeTimer > 0) { complete = false; continue; }
            if (pos.distToCenterSqr(mc.player.getEyePosition()) > placeRange.get() * placeRange.get()) {
                if (verifying) reach(pos, placeRange.get());
                return false;
            }
            int slot = target.filler() ? State.Forward.findBlocksToPlacePrioritizeTrash(this) : State.Forward.findBlocksToPlace(this);
            if (slot < 0 || !controlsPlayer() || state != State.Forward) return false;
            if (mc.player.getInventory().getItem(slot).getItem() instanceof BlockItem material) {
                for (AABB shape : material.getBlock().defaultBlockState().getCollisionShape(mc.level, pos).toAabbs()) {
                    if (waitForMob(shape.move(pos))) return false;
                }
            }
            if (placeWorkBlock(pos, slot)) count++;
            else complete = false;
            if (verifying) complete = false;
        }
        return complete;
    }

    private boolean reach(BlockPos target, double range) {
        if (target.distToCenterSqr(mc.player.getEyePosition()) <= range * range) { input.stop(); walkGoal = null; walkPath = List.of(); return true; }
        if (walkGoal != null) { walkToWorkPosition(walkGoal); return false; }
        List<BlockPos> candidates = new ArrayList<>();
        for (int y = 0; y <= 2; y++) {
            for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
                BlockPos feet = workOrigin.offset(x, y, z);
                if (target.distToCenterSqr(Vec3.atBottomCenterOf(feet).add(0, mc.player.getEyeHeight(), 0)) <= range * range) candidates.add(feet);
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(mc.player.position())));
        for (BlockPos feet : candidates) {
            if (!standable(cell(feet))) continue;
            if (HighwayPlan.route(cell(walkingFeet()), cell(feet), this::standable, this::safeStep).isEmpty()) continue;
            status = "Moving within reach";
            walkToWorkPosition(Vec3.atBottomCenterOf(feet));
            return false;
        }
        // Build a recoverable step only in the already-cleared work area, never replace existing blocks.
        if (doesDig()) {
            for (BlockPos feet : candidates) {
                BlockPos support = feet.below();
                if (feet.getY() <= workOrigin.getY() || !clearBody(feet) || !standable(cell(support))) continue;
                if (support.equals(workOrigin) || !mc.level.getBlockState(support).isAir() || !BlockUtils.canPlace(support) || support.distToCenterSqr(mc.player.getEyePosition()) > placeRange.get() * placeRange.get()) continue;
                if ((feet.getX() - workOrigin.getX()) * dir.offsetX + (feet.getZ() - workOrigin.getZ()) * dir.offsetZ > 0) continue;
                boolean approach = false;
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    BlockPos beside = support.relative(side);
                    if (!standable(cell(beside)) || !clearBody(beside.above())) continue;
                    if (!HighwayPlan.route(cell(walkingFeet()), cell(beside), this::standable, this::safeStep).isEmpty()) { approach = true; break; }
                }
                if (!approach) continue;
                int slot = State.Forward.findBlocksToPlacePrioritizeTrash(this);
                if (slot == -1 || paused) return false;
                if (placeWorkBlock(support, slot)) { temporarySteps.add(support.immutable()); status = "Placing temporary work step"; }
                return false;
            }
        }
        pauseJob("Cannot reach block " + target.toShortString() + " safely (" + String.format("%.1f", range) + "-block reach). Add a safe working step or clear an approach, then Resume.");
        return false;
    }

    private boolean placeWorkBlock(BlockPos position, int slot) {
        return placeWorkBlock(position, slot, null);
    }

    private boolean placementBlocked(BlockPos position, String reason) {
        if (state == State.Restock) status = "Restocking: " + reason + " at " + position.toShortString();
        return false;
    }

    private boolean placeWorkBlock(BlockPos position, int slot, Direction facing) {
        if (!controlsPlayer()) return false;
        if (mobReturn != null) return placementBlocked(position, "returning from combat");
        if (predictionFlushRequested) return placementBlocked(position, "waiting for server verification");
        if (placeTimer > 0) return placementBlocked(position, "waiting for placement delay");
        if (slot < 0 || slot > 8) return placementBlocked(position, "no supply hotbar slot");
        BlockPos target = position.immutable();
        if (pendingPlaces.containsKey(target)) return placementBlocked(target, "waiting for placement confirmation");
        ItemStack stack = mc.player.getInventory().getItem(slot);
        if (!(stack.getItem() instanceof BlockItem item)) return placementBlocked(target, "supply item missing from hotbar");
        if (target.distToCenterSqr(mc.player.getEyePosition()) > placeRange.get() * placeRange.get()) return placementBlocked(target, "supply position outside reach");
        if (!BlockUtils.canPlaceBlock(target, true, item.getBlock())) return placementBlocked(target, "supply placement blocked by collision or world bounds");
        if (state == State.Restock) status = "Restocking: placing supply container";
        int epoch = actionEpoch;
        pendingPlaces.put(target, item.getBlock());
        // Rotation callbacks still need the original stack until this tick's placement batch is sent.
        if (facing != null || rotation.get().place) queuedHotbarSlots |= 1 << slot;
        Runnable place = () -> {
            if (actionEpoch != epoch) return;
            if (!controlsPlayer() || !pendingPlaces.containsKey(target) || !waiting.isEmpty() || !ItemStack.isSameItemSameComponents(mc.player.getInventory().getItem(slot), stack)
                || target.distToCenterSqr(mc.player.getEyePosition()) > placeRange.get() * placeRange.get()) {
                pendingPlaces.remove(target);
                return;
            }
            placingTarget = target;
            try {
                BlockUtils.place(target, InteractionHand.MAIN_HAND, slot, false, 0, true, true, true);
            } finally {
                placingTarget = null;
                // Another module may cancel the interaction before a packet is sent.
                if (!placeSequences.containsKey(target)) {
                    // Keep a canceled background prediction unresolved until a server update or window retirement.
                    // It must not stall the foreground job or be mistaken for confirmed paving.
                    if (!isBackgroundPaving(target)) {
                        pendingPlaces.remove(target);
                        placementBlocked(target, "placement interaction not sent");
                        if (mc.level.getBlockState(target).is(item.getBlock())) requestPredictionFlush();
                    }
                }
            }
        };
        if (facing != null) Rotations.rotate(facing.getOpposite().toYRot(), Rotations.getPitch(target), 0, true, place);
        else if (rotation.get().place) Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target), place);
        else place.run();
        placeTimer = placeDelay.get();
        return true;
    }

    private void breakWorkBlock(BlockPos position) {
        BlockPos target = position.immutable();
        BlockState before = mc.level.getBlockState(target);
        int epoch = actionEpoch;
        State miningState = state;
        Runnable mine = () -> {
            if (!controlsPlayer() || epoch != actionEpoch || state != miningState || !waiting.isEmpty() || !mc.level.getBlockState(target).equals(before)) return;
            if (target.distToCenterSqr(mc.player.getEyePosition()) > mc.player.blockInteractionRange() * mc.player.blockInteractionRange()) return;
            liquidPlacements.remove(target); // Intentionally cleared tunnel plugs must not be filled again behind us.
            pendingBreaks.putIfAbsent(target, before);
            BlockUtils.breakBlock(target, true);
        };
        if (rotation.get().mine) Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target), mine);
        else mine.run();
    }

    @EventHandler
    private void onRender2d(Render2DEvent event) {
        if (!job || !Utils.canUpdate()) return;

        TextRenderer text = TextRenderer.get();
        text.begin(event.graphics, 1, false, true);
        String prefix = "Highway Builder · ";
        String health = getHudStatus();
        text.render(prefix, 8, 8, Color.WHITE, true);
        double x = 8 + text.getWidth(prefix);
        text.render(health, x, 8, isHudHealthy() ? new Color(100, 220, 145) : new Color(245, 187, 90), true);
        text.render(" · " + Math.round(hud.distance()) + " blocks · " + getPavingRate(), x + text.getWidth(health), 8, Color.WHITE, true);
        text.end();
        if (!controlsPlayer() || !renderMine.get()) return;

        if (normalMining != null) normalMining.renderLetter(event.graphics);
        if (packetMining != null) packetMining.renderLetter(event.graphics);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!Utils.canUpdate() || !(job || preview) || blockPosProvider == null || dir.diagonal && width.get() < 3) return;
        if (preview) {
            BlockPos saved = workOrigin;
            try {
                for (int step = 0; step < 3; step++) {
                    workOrigin = saved.offset(dir.offsetX * step, 0, dir.offsetZ * step);
                    if (doesDig()) render(event, blockPosProvider.getFront(), p -> true, true);
                    if (doesPave()) render(event, blockPosProvider.getFloor(), p -> true, false);
                    if (hasPreviewRailings()) render(event, blockPosProvider.getRailings(0), p -> true, false);
                    if (hasPreviewSupports()) render(event, blockPosProvider.getRailings(-1), p -> true, false);
                }
            } finally {
                workOrigin = saved;
            }
        }
        if (!job || suspended || mc.level != jobWorld) return;

        if (renderMine.get() && doesDig()) {
            render(event, blockPosProvider.getFront(), mBlockPos -> canMine(mBlockPos, true), true);
            if (operation.get() == Operation.Build && floor.get() == Floor.Replace)
                render(event, blockPosProvider.getFloor(), mBlockPos -> canMine(mBlockPos, false), true);
            if (hasPreviewRailings())
                render(event, blockPosProvider.getRailings(0), mBlockPos -> canMine(mBlockPos, false), true);
            if (operation.get() == Operation.Build && mineAboveRailings.get())
                render(event, blockPosProvider.getRailings(1), mBlockPos -> canMine(mBlockPos, true), true);
            if (state == State.MineEChestBlockade)
                render(event, blockPosProvider.getBlockade(true, blockadeType.get()), mBlockPos -> canMine(mBlockPos, true), true);
        }

        if (renderPlace.get()) {
            render(event, blockPosProvider.getLiquids(), mBlockPos -> canPlace(mBlockPos, true), false);

            if (hasPreviewRailings()) {
                render(event, blockPosProvider.getRailings(0), mBlockPos -> canPlace(mBlockPos, false), false);

                if (cornerBlock.get()) {
                    // make sure we only render corner support blocks if we are actually planning to place a block there
                    render(event, blockPosProvider.getRailings(-1), mBlockPos -> {
                        boolean valid = false;
                        for (MBlockPos pos : blockPosProvider.getRailings(0)) {
                            if (!blocksToPlace.get().contains(pos.getState().getBlock()) && pos.add(0, -1, 0).equals(mBlockPos)) {
                                valid = true;
                                break;
                            }
                        }

                        return valid && canPlace(mBlockPos, false);
                    }, false);
                }
            }

            if (doesPave()) render(event, blockPosProvider.getFloor(), mBlockPos -> canPlace(mBlockPos, false), false);
            if (pavingWithinLength(1)) {
                for (PavingTarget target : pavingTargets(1)) {
                    if (mc.level.hasChunkAt(target.position()) && mc.level.getBlockState(target.position()).canBeReplaced())
                        event.renderer.box(target.position(), renderPlaceSideColor.get(), renderPlaceLineColor.get(), renderPlaceShape.get(), 0);
                }
            }
            if (state == State.PlaceEChestBlockade)
                render(event, blockPosProvider.getBlockade(false, blockadeType.get()), mBlockPos -> canPlace(mBlockPos, false), false);
        }
    }

    private void render(Render3DEvent event, MBPIterator it, Predicate<MBlockPos> predicate, boolean mine) {
        Color sideColor = mine ? renderMineSideColor.get() : renderPlaceSideColor.get();
        Color lineColor = mine ? renderMineLineColor.get() : renderPlaceLineColor.get();
        ShapeMode shapeMode = mine ? renderMineShape.get() : renderPlaceShape.get();

        for (MBlockPos pos : it) {
            posRender2.set(pos);

            if (predicate.test(posRender2)) {
                int excludeDir = 0;

                for (Direction side : Direction.values()) {
                    posRender3.set(posRender2).add(side.getStepX(), side.getStepY(), side.getStepZ());

                    it.save();
                    for (MBlockPos p : it) {
                        if (p.equals(posRender3) && predicate.test(p)) excludeDir |= Dir.get(side);
                    }
                    it.restore();
                }

                event.renderer.box(posRender2.getBlockPos(), sideColor, lineColor, shapeMode, excludeDir);
            }
        }
    }

    private void updateVariables() {
        if (mc.player.input != input) prevInput = mc.player.input;
        mc.player.input = input = new CustomPlayerInput();

        placeTimer = breakTimer = count = 0;
        ignoreCrystals.clear();

        normalMining = null;
        packetMining = null;
    }

    private void setState(State state) {
        setState(state, this.state);
    }

    private void setState(State state, State lastState) {
        if (!controlsPlayer()) return;
        // A task handoff must not leave the previous task's mining controller blocking verification.
        if (this.state != state) stopWorkMining();
        this.lastState = lastState;
        this.state = state;

        input.stop();
        status = switch (state) {
            case Center -> "Aligning with highway";
            case Forward -> "Building";
            case ReLevel -> "Recovering build elevation";
            case FillLiquids -> "Sealing liquids";
            case MineFront, MineFloor, MineRailings, MineAboveRailings -> "Clearing blocks";
            case MinePaving -> "Repairing server-restored paving";
            case PlaceCornerBlock, PlaceRailings, PlaceFloor -> "Paving";
            case ThrowOutTrash -> "Making inventory space";
            case Restock -> "Restocking " + restockTask.item();
            case PlaceEChestBlockade, MineEChestBlockade, MineEnderChests -> "Processing ender chests";
            case PlaceShulkerBlockade, MineShulkerBlockade -> "Securing supplies";
            case DefuseCrystalTraps -> "Clearing crystal hazard";
        };
        state.start(this);
    }

    private int getWidthLeft() {
        return HighwayPlan.left(width.get());
    }

    private int getWidthRight() {
        return HighwayPlan.right(width.get());
    }

    private boolean canMine(MBlockPos pos, boolean mineBlocksToPlace) {
        BlockState state = pos.getState();
        return BlockUtils.canBreak(pos.getBlockPos(), state) && (mineBlocksToPlace || !blocksToPlace.get().contains(state.getBlock()));
    }

    private boolean canPlace(MBlockPos pos, boolean liquids) {
        return liquids ? !pos.getState().getFluidState().isEmpty() : pos.getState().canBeReplaced();
    }

    static boolean sealableLiquid(BlockState state, double distanceSquared, double range) {
        return state.canBeReplaced() && !state.getFluidState().isEmpty() && distanceSquared <= range * range;
    }

    static boolean pavingNeedsMining(BlockState state, boolean replace, List<Block> materials) {
        return replace && !state.canBeReplaced() && !materials.contains(state.getBlock());
    }

    static boolean allowsContainerRecoveryTool(BlockState state, boolean requireSilkTouch, boolean silkTouch) {
        return !state.is(Blocks.ENDER_CHEST) || !requireSilkTouch || silkTouch;
    }

    private Set<BlockPos> liquidBarrier() {
        Set<BlockPos> barrier = new HashSet<>();
        for (MBlockPos pos : blockPosProvider.getLiquids()) barrier.add(pos.getBlockPos().immutable());
        for (HighwayPlan.Cell pos : HighwayPlan.liquidInlets(dir.offsetX, dir.offsetZ, width.get(), height.get(),
            operation.get() == Operation.Build && mineAboveRailings.get())) barrier.add(workOrigin.offset(pos.x(), pos.y(), pos.z()));
        return barrier;
    }

    private List<BlockPos> liquidTargets() {
        liquidPlacements.keySet().removeIf(pos -> pos.distToCenterSqr(jobWorkPosition()) > 144);
        Set<BlockPos> barrier = liquidBarrier();
        Set<BlockPos> targets = new HashSet<>();
        Set<BlockPos> passage = new HashSet<>();
        targets.addAll(barrier);
        targets.addAll(liquidPlacements.keySet());
        for (MBlockPos pos : blockPosProvider.getFront()) {
            BlockPos body = pos.getBlockPos().immutable();
            targets.add(body);
            passage.add(body);
            passage.add(body.offset(dir.offsetX, 0, dir.offsetZ));
        }
        for (MBlockPos pos : blockPosProvider.getFloor()) targets.add(pos.getBlockPos().immutable());
        Vec3 eye = mc.player.getEyePosition();
        return targets.stream()
            .filter(pos -> doesDig() || !passage.contains(pos))
            .filter(pos -> needsLiquidSeal(mc.level.getBlockState(pos), liquidPlacements.containsKey(pos), false, pos.distToCenterSqr(eye), placeRange.get()))
            .sorted(Comparator.comparingInt((BlockPos pos) -> passage.contains(pos) && !barrier.contains(pos) ? 1 : 0)
                .thenComparingDouble(pos -> pos.distToCenterSqr(eye)))
            .toList();
    }

    static boolean needsLiquidSeal(BlockState state, boolean knownHole, boolean pendingSeal, double distanceSquared, double range) {
        return pendingSeal || (knownHole ? state.canBeReplaced() && distanceSquared <= range * range
            : sealableLiquid(state, distanceSquared, range));
    }

    private boolean needsPassageSealing() {
        for (MBlockPos pos : blockPosProvider.getFront()) {
            if (needsLiquidSeal(pos.getState(), liquidPlacements.containsKey(pos.getBlockPos()), pendingPlaces.containsKey(pos.getBlockPos()),
                pos.getBlockPos().distToCenterSqr(mc.player.getEyePosition()), placeRange.get())) return true;
        }
        return false;
    }

    private boolean needsBarrierSealing() {
        Set<BlockPos> barrier = liquidBarrier();
        barrier.addAll(liquidPlacements.keySet());
        for (BlockPos pos : barrier) {
            if (needsLiquidSeal(mc.level.getBlockState(pos), liquidPlacements.containsKey(pos), pendingPlaces.containsKey(pos),
                pos.distToCenterSqr(mc.player.getEyePosition()), placeRange.get())) return true;
        }
        return false;
    }

    private void sealLiquids() {
        if (!controlsPlayer() || mobReturn != null) return;
        State sealingState = state;
        Set<BlockPos> road = new HashSet<>();
        for (PavingTarget target : pavingTargets()) if (!target.filler()) {
            road.add(target.position());
            road.add(target.position().offset(-dir.offsetX, 0, -dir.offsetZ));
            road.add(target.position().offset(dir.offsetX, 0, dir.offsetZ));
        }
        for (List<PavingTarget> section : pavingChecks) {
            for (PavingTarget target : section) if (!target.filler()) road.add(target.position());
        }
        for (BlockPos pos : liquidTargets()) {
            if (count >= placementsPerTick.get() || placeTimer > 0) return;
            if (pendingPlaces.containsKey(pos)) continue;
            boolean roadFloor = liquidPlacements.getOrDefault(pos, false) || road.contains(pos);
            int slot = liquidSupplySlot(mc.player.getInventory(), roadFloor, fillerBlocks.get(), blocksToPlace.get());
            if (slot >= 9) {
                int hotbarSlot = sealingState.findHotbarSlot(this, false);
                if (!controlsPlayer() || state != sealingState) return;
                if (hotbarSlot < 0) continue; // Keep existing queued sources; try this filler again next tick.
                InvUtils.move().from(slot).toHotbar(hotbarSlot);
                slot = hotbarSlot;
            } else if (slot < 0) {
                // Optional edge sealing may use carried supplies but never starts a restock.
                if (sealingState == State.Forward || count > 0) continue;
                slot = roadFloor ? sealingState.findBlocksToPlace(this) : sealingState.findBlocksToPlacePrioritizeTrash(this);
            }
            if (slot < 0 || !controlsPlayer() || state != sealingState) return;
            if (placeWorkBlock(pos, slot)) {
                liquidPlacements.put(pos, roadFloor);
                count++;
            }
        }
    }

    static int liquidSupplySlot(Container inventory, boolean roadFloor, List<Block> filler, List<Block> road) {
        for (int priority = roadFloor ? 1 : 0; priority < 2; priority++) {
            for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (priority == 0 ? isExpendableFiller(stack, filler, road)
                    : stack.getItem() instanceof BlockItem block && road.contains(block.getBlock())) return slot;
            }
        }
        return -1;
    }

    static int hotbarSwapSlot(Container inventory, boolean replaceTools, int queuedSlots, List<Block> filler, List<Block> road, List<Item> trash) {
        int bestSlot = -1, bestRank = Integer.MAX_VALUE;
        for (int slot = 0; slot < Math.min(9, inventory.getContainerSize()); slot++) {
            if ((queuedSlots & (1 << slot)) != 0) continue;
            ItemStack stack = inventory.getItem(slot);
            boolean tool = AutoTool.isTool(stack);
            boolean paving = stack.getItem() instanceof BlockItem block && road.contains(block.getBlock());
            boolean valuable = tool || stack.isDamageableItem()
                || stack.getItem().components().has(net.minecraft.core.component.DataComponents.FOOD)
                    && stack.getItem().components().has(net.minecraft.core.component.DataComponents.CONSUMABLE)
                || stack.getItem() instanceof BlockItem block && block.getBlock() instanceof BaseEntityBlock;
            int rank = stack.isEmpty() ? 0
                : !valuable && (isExpendableFiller(stack, filler, road) || !paving && trash.contains(stack.getItem())) ? 1
                : replaceTools && tool ? 2 : paving ? 3 : valuable ? 5 : 4;
            if (rank < bestRank || rank == bestRank && rank == 3 && stack.getCount() < inventory.getItem(bestSlot).getCount()) {
                bestSlot = slot;
                bestRank = rank;
            }
        }
        return bestSlot;
    }

    private void disconnect(String message, Object... args) {
        MutableComponent text = Component.literal(String.format("%s[%s%s%s] %s", ChatFormatting.GRAY, ChatFormatting.BLUE, title, ChatFormatting.GRAY, ChatFormatting.RED) + String.format(message, args)).append("\n");
        text.append(getStatsText());

        mc.getConnection().getConnection().disconnect(text);
    }

    public MutableComponent getStatsText() {
        MutableComponent text = Component.literal(String.format("%sCompleted road: %s%.1f\n", ChatFormatting.GRAY, ChatFormatting.WHITE, completedDistance));
        text.append(String.format("%sBlocks broken: %s%d\n", ChatFormatting.GRAY, ChatFormatting.WHITE, blocksBroken));
        text.append(String.format("%sBlocks placed: %s%d", ChatFormatting.GRAY, ChatFormatting.WHITE, blocksPlaced));

        return text;
    }

    private void tickDoubleMine() {
        // could add clientside block breaking to speed the system up, but it would probably make it too vulnerable to desyncs
        if (normalMining != null) {
            if (normalMining.shouldRemove()) {
                mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, normalMining.blockPos, normalMining.direction));
                normalMining = null;
                DoubleMineBlock.rateLimited = true;
            } else if (mc.level.getBlockState(normalMining.blockPos).getBlock() != normalMining.block) {
                normalMining = null;
                count++;
                DoubleMineBlock.rateLimited = false;
            } else if (normalMining.isReady()) {
                normalMining.stopDestroying();
            }

            mc.player.swing(InteractionHand.MAIN_HAND);
        }

        if (packetMining != null) {
            if (packetMining.shouldRemove()) {
                mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, packetMining.blockPos, packetMining.direction));
                packetMining = null;
            } else if (mc.level.getBlockState(packetMining.blockPos).getBlock() != packetMining.block) {
                packetMining = null;
                count++;
            }
        }
    }

    private net.minecraft.world.inventory.AbstractContainerMenu cursorMenu;
    private boolean cursorSyncPending, cursorSynced, cursorRecoveryRequired, cursorDiscardReady;
    private int cursorSyncTick, cursorStoreFailures;
    private ItemStack cursorBeforeStore = ItemStack.EMPTY, confirmedCursor = ItemStack.EMPTY;

    private void resetCursorRecovery() {
        cursorMenu = null;
        cursorSyncPending = cursorSynced = cursorRecoveryRequired = false;
        cursorDiscardReady = false;
        cursorStoreFailures = 0;
        cursorBeforeStore = ItemStack.EMPTY;
        confirmedCursor = ItemStack.EMPTY;
    }

    @EventHandler
    private void onCursorInventory(dev.monocle.client.events.packets.InventoryEvent event) {
        // InventoryEvent runs after vanilla applies the full menu contents on the client thread.
        if (job && mc.player != null && cursorSyncPending && mc.player.containerMenu == cursorMenu
            && event.packet.containerId() == cursorMenu.containerId) {
            cursorSyncPending = false;
            cursorSynced = true;
            cursorRecoveryRequired = false;
            confirmedCursor = cursorMenu.getCarried().copy();
            if (!cursorBeforeStore.isEmpty()) {
                if (cursorStoreProgress(cursorBeforeStore, cursorMenu.getCarried())) {
                    cursorStoreFailures = 0;
                    idleTicks = 0;
                } else cursorStoreFailures++;
                cursorBeforeStore = ItemStack.EMPTY;
            } else if (cursorMenu.getCarried().isEmpty()) idleTicks = 0;
        }
    }

    private boolean recoverCursor() {
        var menu = mc.player.containerMenu;
        if (cursorMenu != menu) {
            resetCursorRecovery();
            cursorMenu = menu;
        }
        if (!cursorSyncPending && !cursorRecoveryRequired && menu.getCarried().isEmpty()) {
            resetCursorRecovery();
            return true;
        }
        if (!controlsPlayer()) return false;
        input.stop();
        status = "Synchronizing inventory cursor";
        if (cursorSyncPending) {
            if (mc.player.tickCount - cursorSyncTick >= 120) {
                cursorSyncPending = cursorSynced = false;
                cursorStoreFailures = 0;
                cursorBeforeStore = ItemStack.EMPTY;
                pauseJob("The server has not synchronized your inventory. Your items are preserved; Resume to retry.");
            }
            return false;
        }
        if (!cursorSynced || !ItemStack.matches(confirmedCursor, menu.getCarried())) {
            cursorDiscardReady = false;
            actionEpoch++;
            pendingPlaces.keySet().removeIf(pos -> !placeSequences.containsKey(pos));
            requestCursorSync();
            return false;
        }
        // Filler is expendable even when a slot looks empty or previous stows were rejected.
        if (cursorFillerDiscardAllowed(confirmedCursor, menu.getCarried(), fillerBlocks.get(), blocksToPlace.get())) {
            mc.player.setYRot(dir.yaw + 90);
            mc.player.setXRot(-15);
            status = "Discarding expendable filler from cursor";
            if (!cursorDiscardReady) { cursorDiscardReady = true; return false; }
            cursorDiscardReady = false;
            cursorBeforeStore = menu.getCarried().copy();
            mc.gameMode.handleContainerInput(menu.containerId, net.minecraft.world.inventory.AbstractContainerMenu.SLOT_CLICKED_OUTSIDE,
                0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
            requestCursorSync();
            return false;
        }
        if (cursorStoreFailures >= 3) {
            resetCursorRecovery();
            pauseJob("The server is not accepting the held item into your inventory. Your items are preserved; Resume to retry.");
            return false;
        }
        int destination = cursorRecoverySlot(menu.slots, mc.player.getInventory(), menu.getCarried());
        if (destination < 0) {
            destination = cursorFillerSwapSlot(menu.slots, mc.player.getInventory(), menu.getCarried(), fillerBlocks.get(), blocksToPlace.get());
        }
        if (destination < 0) {
            resetCursorRecovery();
            pauseJob("Inventory full: free a player inventory slot for the held item, then Resume. No items were dropped.");
            return false;
        }
        status = "Putting held item in inventory";
        cursorDiscardReady = false;
        cursorBeforeStore = menu.getCarried().copy();
        mc.gameMode.handleContainerInput(menu.containerId, destination, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
        // Successful predicted clicks have no guaranteed ACK. Ask for the resulting server contents.
        requestCursorSync();
        return false;
    }

    private void requestCursorSync() {
        cursorSynced = false;
        cursorSyncPending = cursorRecoveryRequired = true;
        cursorSyncTick = mc.player.tickCount;
        mc.getConnection().send(cursorSyncRequest(cursorMenu.containerId,
            net.minecraft.network.HashedStack.create(cursorMenu.getCarried(), mc.getConnection().decoratedHashOpsGenenerator())));
    }

    static net.minecraft.network.protocol.game.ServerboundContainerClickPacket cursorSyncRequest(int menuId, net.minecraft.network.HashedStack carried) {
        // Slot -1 is a no-op (unlike outside/drop slot -999); impossible state -1 requests full server state.
        return new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(menuId, -1, (short) -1, (byte) 0,
            net.minecraft.world.inventory.ContainerInput.PICKUP, it.unimi.dsi.fastutil.ints.Int2ObjectMaps.emptyMap(), carried);
    }

    static boolean cursorStoreProgress(ItemStack before, ItemStack after) {
        return after.isEmpty() || ItemStack.isSameItemSameComponents(before, after) && after.getCount() < before.getCount();
    }

    static boolean cursorFillerDiscardAllowed(ItemStack confirmed, ItemStack carried, List<Block> filler, List<Block> road) {
        return ItemStack.matches(confirmed, carried) && isExpendableFiller(confirmed, filler, road);
    }

    static int cursorRecoverySlot(List<net.minecraft.world.inventory.Slot> slots, Container inventory, ItemStack carried) {
        if (carried.isEmpty()) return -1;
        // Prefer an empty player slot; merge only if every player slot is occupied.
        for (boolean emptyOnly : new boolean[] {true, false}) {
            for (int inventorySlot = 0; inventorySlot < Math.min(36, inventory.getContainerSize()); inventorySlot++) {
                for (int menuSlot = 0; menuSlot < slots.size(); menuSlot++) {
                    var slot = slots.get(menuSlot);
                    if (slot.container != inventory || slot.getContainerSlot() != inventorySlot || !slot.isActive() || slot.isFake() || !slot.mayPlace(carried)) continue;
                    ItemStack existing = slot.getItem();
                    if (existing.isEmpty() && slot.getMaxStackSize(carried) > 0) return menuSlot;
                    if (!emptyOnly && ItemStack.isSameItemSameComponents(existing, carried) && existing.getCount() < slot.getMaxStackSize(carried)) return menuSlot;
                }
            }
        }
        return -1;
    }

    static boolean isExpendableFiller(ItemStack stack, List<Block> filler, List<Block> road) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem item
            && !(item.getBlock() instanceof BaseEntityBlock) && filler.contains(item.getBlock()) && !road.contains(item.getBlock());
    }

    static int cursorFillerSwapSlot(List<net.minecraft.world.inventory.Slot> slots, Container inventory, ItemStack carried, List<Block> filler, List<Block> road) {
        if (carried.isEmpty()) return -1;
        int smallest = -1;
        for (int i = 0; i < slots.size(); i++) {
            var slot = slots.get(i);
            if (slot.container != inventory || slot.getContainerSlot() < 0 || slot.getContainerSlot() >= 36
                || !slot.isActive() || slot.isFake() || !slot.mayPlace(carried) || slot.getMaxStackSize(carried) < carried.getCount()) continue;
            if (isExpendableFiller(slot.getItem(), filler, road) && (smallest < 0 || slot.getItem().getCount() < slots.get(smallest).getItem().getCount())) smallest = i;
        }
        return smallest;
    }

    static int expendableFillerSlot(Container inventory, List<Block> filler, List<Block> road) {
        int smallest = -1;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isExpendableFiller(stack, filler, road) && (smallest < 0 || stack.getCount() < inventory.getItem(smallest).getCount())) smallest = i;
        }
        return smallest;
    }

    static int shulkerRestockSlot(Container source, Predicate<ItemStack> useful, int taken, int maximum) {
        if (taken >= maximum) return -1;
        for (int i = 0; i < source.getContainerSize(); i++) {
            ItemStack stack = source.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock && useful.test(stack)) return i;
        }
        return -1;
    }

    static int restockTransferSlot(List<net.minecraft.world.inventory.Slot> slots, Container inventory, ItemStack incoming, int emptyReserve) {
        if (incoming.isEmpty()) return -1;
        int empty = 0, emptyDestination = -1;
        for (int index = 0; index < Math.min(36, inventory.getContainerSize()); index++) {
            if (inventory.getItem(index).isEmpty()) empty++;
        }
        // Inventory order tops up hotbar stacks before matching stacks in the main inventory.
        for (int inventorySlot = 0; inventorySlot < Math.min(36, inventory.getContainerSize()); inventorySlot++) {
            for (int menuSlot = 0; menuSlot < slots.size(); menuSlot++) {
                var slot = slots.get(menuSlot);
                if (slot.container != inventory || slot.getContainerSlot() != inventorySlot
                    || !slot.isActive() || slot.isFake() || !slot.mayPlace(incoming)) continue;
                ItemStack existing = slot.getItem();
                int limit = slot.getMaxStackSize(incoming);
                if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, incoming) && existing.getCount() < limit) return menuSlot;
                if (existing.isEmpty() && limit > 0 && emptyDestination < 0) emptyDestination = menuSlot;
            }
        }
        return empty > emptyReserve ? emptyDestination : -1;
    }

    public void onSupplyItemPickup(int itemId, int collectorId, int amount) {
        if (!job || !Utils.canUpdate() || mc.level != jobWorld) return;
        State.Restock.supplyItemPickup(this, itemId, collectorId, amount);
    }

    static boolean sameSupplyDrop(ItemStack expected, ItemStack actual) {
        if (expected.isEmpty() || actual.isEmpty() || !actual.is(expected.getItem())) return false;
        if (!(expected.getItem() instanceof BlockItem item) || !(item.getBlock() instanceof ShulkerBoxBlock)) return true;
        return java.util.Objects.equals(actual.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME), expected.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME));
    }

    static boolean shouldEjectShulker(ItemStack box, boolean keep, Predicate<ItemStack> usefulSupply, net.minecraft.core.HolderLookup.Provider registries) {
        if (keep || box.isEmpty() || !(box.getItem() instanceof BlockItem item) || !(item.getBlock() instanceof ShulkerBoxBlock)) return false;
        if (box.has(net.minecraft.core.component.DataComponents.CONTAINER_LOOT)) return false;
        if (box.getOrDefault(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.EMPTY)
            .nonEmptyItemCopyStream().anyMatch(usefulSupply)) return false;
        var legacy = box.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA);
        if (legacy != null) {
            if (legacy.type() != net.minecraft.world.level.block.entity.BlockEntityTypes.SHULKER_BOX || registries == null) return false;
            var tag = legacy.copyTagWithoutId();
            var items = tag.getList("Items");
            if (items.isEmpty() || tag.contains("LootTable")) return false;
            var ops = registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
            for (var entry : items.get()) {
                var parsed = net.minecraft.world.ItemStackWithSlot.CODEC.parse(ops, entry).result();
                // Unknown or malformed contents must never become an apparently empty disposable box.
                if (parsed.isEmpty() || !parsed.get().isValidInContainer(27) || usefulSupply.test(parsed.get().stack())) return false;
            }
        }
        return true;
    }

    static int supplyPickupAmount(boolean localCollector, int packetAmount, int groundAmount) {
        return localCollector ? Math.max(0, Math.min(packetAmount, groundAmount)) : 0;
    }

    static boolean supplyPickupComplete(int collected, int required, boolean dropPresent, boolean takenByOther) {
        return required > 0 && collected >= required && !dropPresent && !takenByOther;
    }

    private enum State {
        Center {
            private Vec3 target;

            @Override
            protected void start(HighwayBuilder b) {
                target = Vec3.atBottomCenterOf(b.walkingFeet());
            }

            @Override
            protected void tick(HighwayBuilder b) {
                b.status = "Aligning with highway";
                if (b.walkToWorkPosition(target)) b.setState(b.lastState);
            }
        },

        Forward {
            @Override
            protected void tick(HighwayBuilder b) {
                if (!b.pendingBreaks.isEmpty()) {
                    b.status = "Waiting for server to confirm excavation";
                    b.input.stop();
                    return;
                }
                if (b.advancing && b.reachedNextSection()) {
                    b.advancing = false;
                    b.workOrigin = b.workOrigin.offset(b.dir.offsetX, 0, b.dir.offsetZ);
                    b.pavingChecks.addLast(b.advancingPaving);
                    b.advancingPaving = List.of();
                    while (b.pavingChecks.size() > HighwayPlan.PAVING_LOOKBACK + 1) b.retirePavingSection();
                    b.idleTicks = 0;
                }
                if (b.verifyAfterCombat) {
                    b.status = "Verifying paving after piglin clearing";
                    if (!b.paveSection(b.advancing ? b.advancingPaving : b.pavingTargets(), true)) return;
                    b.verifyAfterCombat = false;
                }
                if (!b.verifyUnderfoot()) return;
                if (b.advancing) {
                    if (checkPassage(b)) return;
                    b.paveSection(b.advancingPaving, false);
                    if (!b.controlsPlayer() || b.state != this || b.mobReturn != null) return;
                    b.paveAhead();
                    b.paveBehind();
                    if (!b.controlsPlayer() || b.state != this) return;
                    b.status = "Moving to next section";
                    b.advanceRoad();
                    b.sealLiquids();
                    return;
                }

                boolean atEnd = !b.pavingWithinLength(0);
                if (atEnd) {
                    b.paveBehind();
                    while (!b.pavingChecks.isEmpty()) b.retirePavingSection();
                    b.status = "Completed " + String.format("%.1f", b.completedDistance) + " blocks";
                    b.stopJob();
                    return;
                }

                if (b.destroyCrystalTraps.get() && isCrystalTrap(b)) {
                    b.setState(DefuseCrystalTraps);
                    return;
                }
                if (b.paused) return;
                if (b.hasSupplyContainer() && b.needsFoodRestock()) {
                    b.restockTask.setFood();
                    return;
                }
                if (checkExcavation(b)) return;
                if (!b.temporarySteps.isEmpty()) {
                    if (b.mc.player.getY() > b.workOrigin.getY() + 0.25) {
                        b.status = "Returning before removing work steps";
                        if (!b.walkToWorkPosition(b.jobWorkPosition())) return;
                    }
                    b.temporarySteps.removeIf(p -> b.mc.level.getBlockState(p).isAir());
                    if (!b.temporarySteps.isEmpty()) {
                        BlockPos step = b.temporarySteps.stream().max(Comparator.comparingInt(BlockPos::getY)).orElseThrow();
                        if (!b.reach(step, b.mc.player.blockInteractionRange())) return;
                        int slot = findAndMoveBestToolToHotbar(b, b.mc.level.getBlockState(step), false);
                        if (slot < 0 || b.paused) return;
                        InvUtils.swap(slot, false);
                        b.status = "Removing temporary work steps";
                        b.breakWorkBlock(step);
                        return;
                    }
                }

                List<PavingTarget> targets = b.pavingTargets();
                b.status = "Paving ahead";
                b.paveSection(targets, false);
                if (!b.controlsPlayer() || b.state != this || b.mobReturn != null) return;
                b.paveAhead();
                b.paveBehind();
                if (!b.controlsPlayer() || b.state != this) return;
                b.advancingPaving = targets;
                b.advancing = true;
                b.status = "Moving to next section";
                b.advanceRoad();
                if (b.controlsPlayer() && b.state == this) b.sealLiquids();
            }

            private boolean checkExcavation(HighwayBuilder b) {
                if (checkPassage(b)) return true;
                if (b.operation.get() == Operation.Build && b.floor.get() == Floor.Replace && needsToMine(b, b.blockPosProvider.getFloor(), false)) {
                    b.setState(b.needsBarrierSealing() ? FillLiquids : MineFloor);
                    return true;
                }
                if (b.operation.get() == Operation.Build && b.railings.get() && needsToMine(b, b.blockPosProvider.getRailings(0), false)) {
                    b.setState(b.needsBarrierSealing() ? FillLiquids : MineRailings);
                    return true;
                }
                if (b.operation.get() == Operation.Build && b.mineAboveRailings.get() && hasObstruction(b, b.blockPosProvider.getRailings(1))) {
                    b.setState(b.needsBarrierSealing() ? FillLiquids : MineAboveRailings);
                    return true;
                }
                return false;
            }

            private boolean checkPassage(HighwayBuilder b) {
                if (b.doesDig() && b.needsPassageSealing()) {
                    b.setState(FillLiquids);
                    return true;
                }
                if (!b.doesDig()) {
                    for (MBlockPos pos : b.blockPosProvider.getFront()) {
                        if (!pos.getState().getFluidState().isEmpty()) {
                            b.pauseJob("Liquid in the passage. Use Build to seal and clear it; Repair and Pave only seal the floor and outside edges.");
                            return true;
                        }
                    }
                }
                if (hasObstruction(b, b.blockPosProvider.getFront())) {
                    if (!b.doesDig()) b.pauseJob("Passage obstructed. Repair and Pave preserve existing blocks; clear it or choose Build.");
                    else b.setState(b.needsBarrierSealing() ? FillLiquids : MineFront);
                    return true;
                }
                return false;
            }

            private boolean hasObstruction(HighwayBuilder b, MBPIterator it) {
                for (MBlockPos pos : it) {
                    BlockState state = pos.getState();
                    if (!state.getCollisionShape(b.mc.level, pos.getBlockPos()).isEmpty()) return true;
                }
                return false;
            }

            private boolean needsToMine(HighwayBuilder b, MBPIterator it, boolean mineBlocksToPlace) {
                for (MBlockPos pos : it) {
                    if (!pos.getState().isAir() && !pos.getState().canBeReplaced() && (mineBlocksToPlace || !b.blocksToPlace.get().contains(pos.getState().getBlock()))) return true;
                }
                return false;
            }

            private boolean isCrystalTrap(HighwayBuilder b) {
                for (Entity entity : b.mc.level.entitiesForRendering()) {
                    if (!(entity instanceof EndCrystal crystal) || !PlayerUtils.isWithin(crystal, 24)) continue;
                    Vec3 eye = b.mc.player.getEyePosition();
                    if (b.mc.level.clip(new ClipContext(eye, crystal.position().add(0, 0.5, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, b.mc.player)).getType() != HitResult.Type.MISS) continue;
                    if (PlayerUtils.isWithin(crystal, 12)) {
                        b.pauseJob("Crystal within 12 blocks. Clear the hazard before resuming.");
                        return false;
                    }
                    return true;
                }
                return false;
            }
        },

        ReLevel {
            private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            private BlockPos startPos;
            private int timer = 30;

            @Override
            protected void start(HighwayBuilder b) {
                startPos = BlockPos.containing(b.start);
            }

            @Override
            protected void tick(HighwayBuilder b) {
                Vec3 vec = b.mc.player.position().add(b.mc.player.getDeltaMovement()).add(0, -0.75, 0);
                pos.set(b.mc.player.getBlockX(), vec.y, b.mc.player.getBlockZ());

                if (pos.getY() >= b.mc.player.blockPosition().getY()) {
                    pos.setY(b.mc.player.blockPosition().getY() - 1);
                }

                if (pos.getY() >= startPos.getY()) pos.setY(startPos.getY() - 1);

                if (b.mc.player.getY() > b.start.y - 0.5 && !b.mc.level.getBlockState(pos).canBeReplaced()) {
                    b.input.jump(false);

                    if (timer > 0) timer--;
                    else {
                        b.setState(Forward);
                        timer = 30;
                    }

                    return;
                }

                if (b.placeTimer > 0) return;

                if (timer < 30) timer = 30;
                b.input.jump(true);

                int slot = -1;
                if (pos.getY() == startPos.below().getY()) {
                    // we would prefer the block flush with the highway to be an appropriate placement block, not trash
                    slot = findAndMoveToHotbar(b, itemStack -> itemStack.getItem() instanceof BlockItem blockItem && b.blocksToPlace.get().contains(blockItem.getBlock()));
                }

                if (slot == -1) {
                    slot = findAcceptablePlacementBlock(b);
                    if (slot == -1) return;
                }

                if (BlockUtils.place(pos.immutable(), InteractionHand.MAIN_HAND, slot, b.rotation.get().place, 100, true, true, false)) {
                    if (b.renderPlace.get())
                        RenderUtils.renderTickingBlock(pos.immutable(), b.renderPlaceSideColor.get(), b.renderPlaceLineColor.get(), b.renderPlaceShape.get(), 0, 5, true, false);
                    b.placeTimer = b.placeDelay.get();
                }
            }

            private int findAcceptablePlacementBlock(HighwayBuilder b) {
                // still should prioritise trash
                int slot = findAndMoveToHotbar(b, itemStack -> {
                    return itemStack.getItem() instanceof BlockItem bi && b.fillerBlocks.get().contains(bi.getBlock());
                });

                // next we prioritise placement blocks
                if (slot == -1) slot = findAndMoveToHotbar(b, itemStack -> {
                    if (!(itemStack.getItem() instanceof BlockItem bi)) return false;
                    return b.blocksToPlace.get().contains(bi.getBlock());
                });

                // falling is an emergency; in this case only, we allow access to any whole block in your inventory
                return slot != -1 ? slot : findAndMoveToHotbar(b, itemStack -> {
                    if (!(itemStack.getItem() instanceof BlockItem bi)) return false;
                    if (Utils.isShulker(bi)) return false;
                    Block block = bi.getBlock();

                    if (!Block.isShapeFullBlock(block.defaultBlockState().getCollisionShape(b.mc.level, pos)))
                        return false;
                    return !(block instanceof FallingBlock) || !FallingBlock.isFree(b.mc.level.getBlockState(pos));
                });
            }
        },

        FillLiquids {
            private int stalledTicks, confirmedPlacements, backstepTicks;
            private Vec3 backstep;
            private boolean verifyingRecovery;

            @Override
            protected void start(HighwayBuilder b) {
                stalledTicks = backstepTicks = 0;
                confirmedPlacements = b.blocksPlaced;
                backstep = null;
                verifyingRecovery = false;
            }

            @Override
            protected boolean recoverSealing(HighwayBuilder b) {
                if (verifyingRecovery) {
                    if (b.predictionFlushRequested || b.predictionProbeSequence >= 0) return false;
                    verifyingRecovery = false;
                    b.placeTimer = 0;
                    resetReturnPath(b);
                    b.setState(Forward); // Rescan the passage and barrier after vanilla resolves predictions.
                    return true;
                }
                if (backstep != null) {
                    b.input.stop();
                    b.status = "Backing up one block before retrying sealing";
                    if (++backstepTicks > 100 || !safeBackstep(b, backstep)) {
                        backstep = null;
                        stalledTicks = 0;
                        b.pauseJob("Cannot safely complete the sealing-recovery backstep. Check the footing behind you, then Resume.");
                        return true;
                    }
                    Vec3 current = b.mc.player.position();
                    if (current.distanceToSqr(backstep) < 0.0225) {
                        backstep = null;
                        resetReturnPath(b);
                        b.stopWorkMining();
                        verifyingRecovery = true;
                        b.requestPredictionFlush();
                    } else if (b.waitForMob(b.mc.player.getBoundingBox().expandTowards(backstep.subtract(current)))) {
                        backstepTicks = 0;
                    } else {
                        b.mc.player.setYRot((float) Rotations.getYaw(backstep) + 180);
                        b.input.backward(true);
                    }
                    return true;
                }
                if (confirmedPlacements != b.blocksPlaced) {
                    confirmedPlacements = b.blocksPlaced;
                    stalledTicks = 0;
                }
                if (!sealingRetryDue(++stalledTicks)) return false;
                if (!b.needsPassageSealing() && !b.needsBarrierSealing()) {
                    stalledTicks = 0;
                    return false;
                }
                Vec3 target = backstepTarget(b.mc.player.position(), b.mc.player.getYRot());
                if (!safeBackstep(b, target)) {
                    stalledTicks = 0;
                    b.pauseJob("No safe footing for the sealing-recovery backstep. Clear the space behind you, then Resume.");
                    return true;
                }
                b.resetMobMovement(); // Cancel owned mining/queued actions before moving away from the seal.
                resetReturnPath(b);
                backstep = target;
                backstepTicks = 0;
                b.status = "Backing up one block before retrying sealing";
                return true;
            }

            @Override
            protected void tick(HighwayBuilder b) {
                b.input.stop();
                b.status = "Sealing the passage";
                // Seal the incoming flow before excavating the previous barrier; keep the placement batch full.
                b.sealLiquids();
                if (b.controlsPlayer() && b.state == this && !b.needsPassageSealing() && !b.needsBarrierSealing()) b.setState(Forward);
            }
        },

        MinePaving {
            @Override
            protected void tick(HighwayBuilder b) {
                b.input.stop();
                BlockPos target = b.pavingRepairTarget;
                b.status = "Repairing paving at " + target.toShortString();
                if (!pavingNeedsMining(b.mc.level.getBlockState(target), true, b.blocksToPlace.get())) {
                    b.setState(Forward);
                    return;
                }
                if (b.mc.player.getBoundingBox().move(0, -0.05, 0).intersects(new AABB(target))) {
                    // A final-section correction can be underfoot. Step off before breaking its support.
                    for (Direction side : Direction.Plane.HORIZONTAL) {
                        BlockPos feet = BlockPos.containing(b.mc.player.getX(), HighwayPlan.roadFeetY(b.mc.player.getY(), b.workOrigin.getY()), b.mc.player.getZ()).relative(side);
                        if (!b.standable(b.cell(feet)) || feet.below().equals(target)) continue;
                        if (HighwayPlan.route(b.cell(b.walkingFeet()), b.cell(feet), b::standable, b::safeStep).isEmpty()) continue;
                        b.walkToWorkPosition(Vec3.atBottomCenterOf(feet));
                        return;
                    }
                    b.pauseJob("Cannot step off the paving block at " + target.toShortString() + " to repair it. Add a nearby foothold, then Resume.");
                    return;
                }
                mine(b, new PositionIterator(List.of(target), false), true, Forward, this);
            }
        },

        MineFront {
            @Override
            protected void start(HighwayBuilder b) {
                mine(b, b.blockPosProvider.getFront(), true, Forward, this);
            }

            @Override
            protected void tick(HighwayBuilder b) {
                mine(b, b.blockPosProvider.getFront(), true, Forward, this);
            }
        },

        MineFloor {
            @Override
            protected void start(HighwayBuilder b) {
                mine(b, b.blockPosProvider.getFloor(), false, Forward, this);
            }

            @Override
            protected void tick(HighwayBuilder b) {
                mine(b, b.blockPosProvider.getFloor(), false, Forward, this);
            }
        },

        MineRailings {
            @Override
            protected void start(HighwayBuilder b) {
                mine(b, b.blockPosProvider.getRailings(0), false, Forward, this);
            }

            @Override
            protected void tick(HighwayBuilder b) {
                mine(b, b.blockPosProvider.getRailings(0), false, Forward, this);
            }
        },

        MineAboveRailings {
            @Override
            protected void start(HighwayBuilder b) {
                mine(b, b.blockPosProvider.getRailings(1), true, Forward, this);
            }

            @Override
            protected void tick(HighwayBuilder b) {
                mine(b, b.blockPosProvider.getRailings(1), true, Forward, this);
            }
        },

        PlaceCornerBlock {
            @Override
            protected void start(HighwayBuilder b) {
                int slot = findBlocksToPlacePrioritizeTrash(b);
                if (slot == -1) return;

                place(b, b.blockPosProvider.getRailings(-1), slot, Forward);
            }

            @Override
            protected void tick(HighwayBuilder b) {
                int slot = findBlocksToPlacePrioritizeTrash(b);
                if (slot == -1) return;

                place(b, b.blockPosProvider.getRailings(-1), slot, Forward);
            }
        },

        PlaceRailings {
            @Override
            protected void start(HighwayBuilder b) {
                int slot = findBlocksToPlace(b);
                if (slot == -1) return;

                place(b, b.blockPosProvider.getRailings(0), slot, Forward);
            }

            @Override
            protected void tick(HighwayBuilder b) {
                int slot = findBlocksToPlace(b);
                if (slot == -1) return;

                place(b, b.blockPosProvider.getRailings(0), slot, Forward);
            }
        },

        PlaceFloor {
            @Override
            protected void start(HighwayBuilder b) {
                int slot = findBlocksToPlace(b);
                if (slot == -1) return;

                place(b, b.blockPosProvider.getFloor(), slot, Forward);
            }

            @Override
            protected void tick(HighwayBuilder b) {
                int slot = findBlocksToPlace(b);
                if (slot == -1) return;

                place(b, b.blockPosProvider.getFloor(), slot, Forward);
            }
        },

        ThrowOutTrash {
            private int skipSlot;
            private boolean timerEnabled,firstTick,threwItems;
            private int timer;

            @Override
            protected void start(HighwayBuilder b) {
                int biggestCount = 0;

                for (int i = 0; i < b.mc.player.getInventory().getNonEquipmentItems().size(); i++) {
                    ItemStack itemStack = b.mc.player.getInventory().getItem(i);

                    // Ordinary cleanup keeps one working filler stack; supply-space recovery may reclaim it too.
                    if (isExpendableFiller(itemStack, b.fillerBlocks.get(), b.blocksToPlace.get()) && itemStack.getCount() > biggestCount) {
                        biggestCount = itemStack.getCount();
                        skipSlot = i;

                        if (biggestCount >= 64) break;
                    }
                }

                if (biggestCount == 0) skipSlot = -1;
                timerEnabled = false;
                firstTick = true;
                threwItems = false;
            }

            @Override
            protected void tick(HighwayBuilder b) {
                if (timerEnabled) {
                    if (timer > 0) timer--;
                    else b.setState(b.lastState);

                    return;
                }

                b.mc.player.setYRot(b.dir.opposite().yaw);
                b.mc.player.setXRot(-25);

                if (firstTick) {
                    firstTick = false;
                    return;
                }

                if (!b.recoverCursor()) return;

                for (int i = 0; i < b.mc.player.getInventory().getNonEquipmentItems().size(); i++) {
                    if (i == skipSlot) continue;

                    ItemStack itemStack = b.mc.player.getInventory().getItem(i);

                    if (canDiscardSupplyTrash(b, itemStack)) {
                        InvUtils.drop().slot(i);
                        threwItems = true;
                        return;
                    }
                }

                timerEnabled = true;
                timer = threwItems ? 10 : 1;
            }
        },

        PlaceEChestBlockade {
            @Override
            protected void tick(HighwayBuilder b) {
                int slot = findBlocksToPlacePrioritizeTrash(b);
                if (slot == -1) return;

                place(b, b.blockPosProvider.getBlockade(false, b.blockadeType.get()), slot, MineEnderChests);
            }
        },

        MineEChestBlockade {
            @Override
            protected void tick(HighwayBuilder b) {
                mine(b, b.blockPosProvider.getBlockade(true, b.blockadeType.get()), true, Center, Forward);
            }
        },

        MineEnderChests {
            private static final MBlockPos pos = new MBlockPos();
            private int minimumObsidian;
            private boolean first,primed,initialized,trashTurned;
            private boolean stopTimerEnabled;
            private int stopTimer,moveTimer,rebreakTimer,timeout,inventoryTimer;

            @Override
            protected void start(HighwayBuilder b) {
                if (b.lastState != Center && b.lastState != ThrowOutTrash && b.lastState != PlaceEChestBlockade) {
                    b.setState(Center);
                    return;
                } else if (b.lastState == Center) {
                    b.setState(ThrowOutTrash);
                    return;
                } else if (b.lastState == ThrowOutTrash) {
                    b.setState(PlaceEChestBlockade);
                    return;
                }

                initialized = trashTurned = false;
                inventoryTimer = 0;
                first = true;
                moveTimer = timeout = 0;

                stopTimerEnabled = false;
                primed = false;
            }

            @Override
            protected void tick(HighwayBuilder b) {
                if (stopTimerEnabled) {
                    if (stopTimer > 0) stopTimer--;
                    else b.setState(MineEChestBlockade);

                    return;
                }

                HorizontalDirection dir = b.dir.diagonal ? b.dir.rotateLeft().rotateLeftSkipOne() : b.dir.opposite();
                pos.set(b.mc.player).offset(dir);

                if (!initialized) {
                    b.input.stop();
                    if (!makeObsidianRoom(b)) return;
                    int emptySlots = 0;
                    for (int i = 0; i < b.mc.player.getInventory().getNonEquipmentItems().size(); i++) {
                        if (b.mc.player.getInventory().getItem(i).isEmpty()) emptySlots++;
                    }
                    int held = countItem(b, stack -> stack.is(Items.OBSIDIAN));
                    int minimumSlots = Math.max(emptySlots - b.minEmpty.get(), 1);
                    // Each chest drops eight: never plan a final drop that cannot fit in partial stacks.
                    minimumObsidian = Math.min(minimumSlots * 64, held + obsidianRoom(b.mc.player.getInventory()) / 8 * 8);
                    initialized = true;
                }

                // Move
                if (moveTimer > 0) {
                    b.mc.player.setYRot(dir.yaw);
                    b.input.forward(moveTimer > 2);

                    moveTimer--;
                    return;
                }

                // Check for obsidian count
                int obsidianCount = 0;

                for (Entity entity : b.mc.level.getEntities(b.mc.player, new AABB(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 2, pos.z + 1))) {
                    if (entity instanceof ItemEntity itemEntity && itemEntity.getItem().getItem() == Items.OBSIDIAN) {
                        obsidianCount += itemEntity.getItem().getCount();
                    }
                }

                for (int i = 0; i < b.mc.player.getInventory().getNonEquipmentItems().size(); i++) {
                    ItemStack itemStack = b.mc.player.getInventory().getItem(i);
                    if (itemStack.getItem() == Items.OBSIDIAN) obsidianCount += itemStack.getCount();
                }

                if (obsidianCount >= minimumObsidian) {
                    stopTimerEnabled = true;
                    stopTimer = 12;
                    return;
                }

                if (!makeObsidianRoom(b)) return;

                BlockPos bp = pos.getBlockPos().immutable();

                // Check block state
                BlockState blockState = b.mc.level.getBlockState(bp);

                if (blockState.getBlock() == Blocks.ENDER_CHEST) {
                    if (b.mc.gui.screen() instanceof ContainerScreen screen) {
                        // wait for the screen to be properly loaded
                        if (screen.getMenu().containerId != b.containerId) return;

                        b.mc.gui.screen().onClose();
                    }

                    // if we don't know what's in your echest, open it quickly while we have one available to check
                    if (!EChestMemory.isKnown()) {
                        int epoch = b.actionEpoch;
                        Runnable open = () -> {
                            if (!b.controlsPlayer() || b.actionEpoch != epoch) return;
                            b.mc.gameMode.useItemOn(b.mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(bp), Direction.UP, bp, false));
                        };
                        if (b.rotation.get().place) Rotations.rotate(Rotations.getYaw(bp), Rotations.getPitch(bp), open);
                        else open.run();

                        return;
                    }

                    if (first) {
                        moveTimer = 8;
                        first = false;
                        return;
                    }

                    // Mine ender chest
                    int slot = findAndMoveBestToolToHotbar(b, blockState, true);
                    if (slot == -1) {
                        if (b.state == this && !b.isJobPaused()) b.error("Cannot find pickaxe without silk touch to mine ender chests.");
                        return;
                    }

                    InvUtils.swap(slot, false);

                    if (b.rebreakEchests.get() && primed) {
                        timeout++;
                        if (timeout > 60) {
                            primed = false;
                            timeout = 0;
                            return;
                        }

                        if (rebreakTimer > 0) {
                            rebreakTimer--;
                            return;
                        }

                        rebreakTimer = b.rebreakTimer.get();

                        int epoch = b.actionEpoch;
                        Runnable rebreak = () -> {
                            if (!b.controlsPlayer() || b.actionEpoch != epoch || bp.distToCenterSqr(b.mc.player.getEyePosition()) > b.mc.player.blockInteractionRange() * b.mc.player.blockInteractionRange()) return;
                            b.pendingBreaks.putIfAbsent(bp, blockState);
                            b.mc.gameMode.startPrediction(b.mc.level, sequence ->
                                new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, bp, BlockUtils.getDirection(bp), sequence));
                        };
                        if (b.rotation.get().mine) Rotations.rotate(Rotations.getYaw(bp), Rotations.getPitch(bp), rebreak);
                        else rebreak.run();
                    } else {
                        b.breakWorkBlock(bp);
                    }
                } else {
                    // Place ender chest
                    int slot = findAndMoveToHotbar(b, itemStack -> itemStack.getItem() == Items.ENDER_CHEST);
                    if (slot == -1 || countItem(b, stack -> stack.getItem().equals(Items.ENDER_CHEST)) <= b.saveEchests.get()) {
                        stopTimerEnabled = true;
                        stopTimer = 12;
                        return;
                    }

                    if (countItem(b, stack -> stack.is(ItemTags.PICKAXES)) <= b.savePickaxes.get()) {
                        if (b.searchEnderChest.get() || b.searchShulkers.get()) {
                            b.restockTask.setPickaxes();
                            return;
                        }
                    }

                    if (!first) primed = true;

                    b.placeWorkBlock(bp, slot);
                    timeout = 0;
                }
            }

            private static int obsidianRoom(Container inventory) {
                ItemStack drop = new ItemStack(Items.OBSIDIAN);
                int room = 0;
                for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
                    ItemStack stack = inventory.getItem(i);
                    if (stack.isEmpty()) room += inventory.getMaxStackSize(drop);
                    else if (ItemStack.isSameItemSameComponents(drop, stack))
                        room += Math.max(0, inventory.getMaxStackSize(stack) - stack.getCount());
                }
                return room;
            }

            private boolean makeObsidianRoom(HighwayBuilder b) {
                if (inventoryTimer > 0) {
                    b.input.stop();
                    inventoryTimer--;
                    return false;
                }
                if (!b.recoverCursor()) return false;
                if (obsidianRoom(b.mc.player.getInventory()) >= 8) {
                    trashTurned = false;
                    return true;
                }
                b.input.stop();
                int filler = expendableFillerSlot(b.mc.player.getInventory(), b.fillerBlocks.get(), b.blocksToPlace.get());
                if (filler < 0) {
                    b.error("Free room for eight obsidian before converting another ender chest. No expendable filler remains.");
                    return false;
                }
                b.status = "Making room for ender-chest obsidian";
                b.mc.player.setYRot((float) Rotations.getYaw(Vec3.atCenterOf(pos.getBlockPos())) + 180);
                b.mc.player.setXRot(-15);
                if (!trashTurned) trashTurned = true;
                else {
                    InvUtils.drop().slot(filler);
                    trashTurned = false;
                    b.idleTicks = 0;
                }
                inventoryTimer = Math.max(3, b.inventoryDelay.get());
                return false;
            }
        },

        Restock {
            private static final MBlockPos pos = new MBlockPos();
            private static final ItemStack[] ITEMS = new ItemStack[27];
            private int minimumSlots,stopTimer,delayTimer,shulkersTaken;
            private boolean breakContainer,indicateStopping,emptyChestVisit;
            private Predicate<ItemStack> shulkerPredicate;
            private int slot = -1;
            private boolean session, initialized, protectedSite, pickupPending, returnPending, trackedContainer, resumeChestFarm, placementSent, containerOwned, trashTurned;
            private Vec3 returnPosition;
            private Vec3 returnBackstep;
            private int returnBackstepTicks;
            private float returnYaw;
            private ItemStack containerItem = ItemStack.EMPTY, recoveryItem = ItemStack.EMPTY;
            private int recoveryBaseline, recoveryCount, recoveredAmount, operationTicks, lastSupplyCount;
            private boolean recoveryTakenByOther;
            private final Set<java.util.UUID> previousGroundItems = new HashSet<>();
            private int recoveryDropId = -1;
            private java.util.UUID recoveryDropUuid;
            private final java.util.List<BlockPos> protectionPlan = new java.util.ArrayList<>();
            private final java.util.Map<BlockPos, Block> protectionBlocks = new java.util.LinkedHashMap<>();
            private int protectionTicks;
            private BlockPos secondChest;
            private Vec3 pairApproach;
            private Direction pairFacing;
            private boolean secondPlacementSent, secondOwned, pairReady;
            private int doubleOpenAttempts;

            @Override
            protected boolean retryReturn(HighwayBuilder b) {
                if (!returnPending || returnBackstep != null || !b.controlsPlayer() || b.predictionFlushRequested) return false;
                Vec3 target = backstepTarget(b.mc.player.position(), b.mc.player.getYRot());
                if (!safeBackstep(b, target)) return false;
                returnBackstep = target;
                returnBackstepTicks = 0;
                resetReturnPath(b);
                b.status = "Backing up one block before retrying return";
                b.info("Return path stalled. Backing up one block, then retrying.");
                return true;
            }

            @Override
            protected void start(HighwayBuilder b) {
                if (!session) {
                    returnBackstep = null;
                    returnPosition = b.mc.player.position();
                    returnYaw = b.mc.player.getYRot();
                    session = true;
                    protectedSite = pickupPending = returnPending = trackedContainer = false;
                    resumeChestFarm = b.lastState == MineEnderChests;
                    protectionPlan.clear();
                    protectionBlocks.clear();
                    protectionTicks = 0;
                    delayTimer = 0;
                    trashTurned = false;
                    containerItem = recoveryItem = ItemStack.EMPTY;
                }
                initialized = false;
                slot = -1;
                operationTicks = 0;

                // set the predicate to test for shulker boxes
                if (shulkerPredicate == null) setShulkerPredicate(b);

                if (b.restockTask.tasksInactive()) {
                    session = false;
                    b.setState(Forward);
                    return;
                }

                if (b.lastState != Center && b.lastState != ThrowOutTrash && b.lastState != PlaceShulkerBlockade && b.lastState != this) {
                    b.setState(Center);
                    return;
                } else if (b.lastState == Center) {
                    b.setState(ThrowOutTrash);
                    return;
                }

                HorizontalDirection supplyDirection = b.dir.diagonal ? b.dir.rotateLeft().rotateLeftSkipOne() : b.dir.opposite();
                BlockPos feet = b.walkingFeet();
                pos.set(feet.getX(), feet.getY(), feet.getZ()).offset(supplyDirection);
                if (!b.recoverCursor()) return;

                // firstly search your inventory for shulkers that have the items you need
                if (slot == -1 && b.searchShulkers.get()) {
                    slot = findAndMoveToHotbar(b, shulkerPredicate);
                    if (b.isJobPaused() || b.state != this) return;

                    if (slot != -1 && !protectedSite && requiresProtection(b)) {
                        protectedSite = true;
                        b.setState(PlaceShulkerBlockade);
                        return;
                    }
                }

                // next search your ender chest for raw items and shulkers containing items
                if (slot == -1 && b.searchEnderChest.get() && countItem(b, stack -> stack.getItem().equals(Items.ENDER_CHEST)) > 0) {
                    // todo handle pulling ecs from shulker boxes so we can search through them

                    int searchSlots = b.enderChestSearchSlots();
                    boolean stop = EChestMemory.isKnown(searchSlots);
                    if (stop) {
                        for (ItemStack stack : EChestMemory.ITEMS.subList(0, Math.min(searchSlots, EChestMemory.ITEMS.size()))) {
                            if (b.restockTask.materials && stack.getItem() instanceof BlockItem bi) {
                                if (b.blocksToPlace.get().contains(bi.getBlock()) || (b.mineEnderChests.get() && b.blocksToPlace.get().contains(Blocks.OBSIDIAN) && bi == Items.ENDER_CHEST)) {
                                    stop = false;
                                    break;
                                }
                            }
                            if (b.restockTask.pickaxes && b.usablePickaxe(stack)) {
                                stop = false;
                                break;
                            }
                            if (b.restockTask.food && Utils.isFood(stack) && !Modules.get().get(AutoEat.class).blacklist.get().contains(stack.getItem())) {
                                stop = false;
                                break;
                            }

                            if (b.searchShulkers.get() && shulkerPredicate.test(stack)) {
                                stop = false;
                                break;
                            }
                        }
                    }

                    if (!stop) slot = findAndMoveToHotbar(b, itemStack -> itemStack.getItem() == Items.ENDER_CHEST);
                    if (b.isJobPaused() || b.state != this) return;
                }

                // by this point we have searched shulkers and your ender chest, and no more items could be found to pull from
                if (slot == -1) {
                    boolean restockOccurred = (
                        (b.restockTask.materials && (hasItem(b, stack -> stack.getItem() instanceof BlockItem bi && b.blocksToPlace.get().contains(bi.getBlock())) || b.mineEnderChests.get() && b.blocksToPlace.get().contains(Blocks.OBSIDIAN) && countItem(b, itemStack -> itemStack.getItem() == Items.ENDER_CHEST) > b.saveEchests.get())) ||
                            (b.restockTask.pickaxes && countItem(b, b::usablePickaxe) > b.savePickaxes.get()) ||
                            (b.restockTask.food && hasItem(b, itemStack -> Utils.isFood(itemStack) && !Modules.get().get(AutoEat.class).blacklist.get().contains(itemStack.getItem())))
                    );

                    if (restockOccurred) {
                        b.restockTask.complete();
                        returnPending = true;
                        initialized = true;
                    } else b.error("Unable to perform restock for '" + b.restockTask.item() + "'.");

                    return;
                }

                ItemStack supplyStack = b.mc.player.getInventory().getItem(slot);
                int containers = supplyStack.is(Items.ENDER_CHEST) && b.enderChestSearchSlots() == 54 && !resumeChestFarm ? 2 : 1;
                int restockSlots = emptySlots(b) - b.minEmpty.get() - containers + (supplyStack.getCount() <= containers ? 1 : 0);

                if (restockSlots <= 0 && !hasPartialRestockRoom(b)) {
                    if (b.minEmpty.get() < 35 && discardForSupplySpace(b)) return;
                    b.error("No room for restocking in the 36 inventory slots: free a slot beyond the reserve or make space in a matching stack.");
                    return;
                }

                if (emptySlots(b) == 0 && b.mc.player.getInventory().getItem(slot).getCount() > 1) {
                    if (discardForSupplySpace(b)) return;
                    b.error("Free one inventory slot for supply-container recovery before restocking from this stack.");
                    return;
                }

                minimumSlots = b.restockTask.materials ? Math.max(1, restockSlots) : 1;

                secondChest = null;
                secondPlacementSent = secondOwned = pairReady = false;
                doubleOpenAttempts = 0;
                shulkersTaken = 0;
                emptyChestVisit = false;
                if (b.mc.player.getInventory().getItem(slot).is(Items.ENDER_CHEST) && b.enderChestSearchSlots() == 54 && !resumeChestFarm) {
                    HighwayPlan.SupplyLayout layout = HighwayPlan.supplyLayout(b.dir.offsetX, b.dir.offsetZ, b.width.get(), true);
                    BlockPos first = b.workOrigin.offset(layout.positions().getFirst().x(), 0, layout.positions().getFirst().z());
                    pos.set(first.getX(), first.getY(), first.getZ());
                    secondChest = b.workOrigin.offset(layout.positions().get(1).x(), 0, layout.positions().get(1).z());
                    pairApproach = Vec3.atBottomCenterOf(b.workOrigin.offset(layout.approach().x(), 0, layout.approach().z()));
                    pairFacing = Direction.getApproximateNearest(layout.facing().x(), 0, layout.facing().z());
                }

                // Quick fix for a specific issue - if your pickaxe breaks while mining echests, it will start a new
                // task to restock pickaxes. However, there will be an echest placed down in the same position specified
                // above, and if you have the search echest setting enabled it will assume it needs to pull items from
                // your echest, even if you have a shulker full of pickaxes in your inventory.
                breakContainer = b.mc.level.getBlockState(pos.getBlockPos()).getBlock() == Blocks.ENDER_CHEST;
                containerOwned = breakContainer && resumeChestFarm;
                placementSent = false;
                if (breakContainer && !containerOwned) {
                    b.pauseJob("An existing ender chest occupies the supply position. Move it or choose a clear work position before restocking.");
                    return;
                }

                indicateStopping = false;
                delayTimer = b.inventoryDelay.get();
                containerItem = b.mc.player.getInventory().getItem(slot).copy();
                containerItem.setCount(1);
                trackedContainer = false;
                initialized = true;
                lastSupplyCount = availableSupplyCount(b);
            }

            @Override
            protected void tick(HighwayBuilder b) {
                // Pickup can finish before its walking target; only explicit walking may hold input this tick.
                b.input.stop();
                if (pickupPending) {
                    collectContainer(b);
                    return;
                }
                if (returnPending) {
                    if (returnBackstep != null) {
                        b.status = "Backing up one block before retrying return";
                        if (++returnBackstepTicks > 100) {
                            b.pauseJob("Could not complete the return-recovery backstep. Check the footing, then Resume.");
                            returnBackstep = null;
                            return;
                        }
                        Vec3 current = b.mc.player.position();
                        if (current.distanceToSqr(returnBackstep) < 0.0225 && b.mc.player.onGround()) {
                            returnBackstep = null;
                            resetReturnPath(b);
                        } else {
                            if (!safeBackstep(b, returnBackstep)) {
                                b.pauseJob("No safe footing for the return-recovery backstep. Clear the space behind you, then Resume.");
                                returnBackstep = null;
                                return;
                            }
                            if (b.waitForMob(b.mc.player.getBoundingBox().expandTowards(returnBackstep.subtract(current)))) {
                                returnBackstepTicks = 0;
                                return;
                            }
                            b.mc.player.setYRot((float) Rotations.getYaw(returnBackstep) + 180);
                            b.input.backward(true);
                        }
                        return;
                    }
                    b.status = "Returning to build position";
                    if (!b.walkToWorkPosition(returnPosition)) return;
                    b.mc.player.setYRot(returnYaw);
                    returnPending = false;
                    if (emptyChestVisit) {
                        emptyChestVisit = false;
                        initialized = false;
                        b.pauseJob("No usable supplies could be taken from the ender chest. The placed containers are recovered; free space for a shulker plus its contents, lower the empty-slot reserve, or add loose supplies, then Resume.");
                        return;
                    }
                    if (indicateStopping || b.restockTask.tasksInactive()) {
                        b.restockTask.complete();
                        session = false;
                        if (protectedSite) b.setState(MineShulkerBlockade);
                        else finishSupplyJob(b);
                    } else start(b);
                    return;
                }
                if (!initialized) {
                    if (delayTimer > 0) { delayTimer--; return; }
                    start(b);
                    return;
                }

                int supplyCount = availableSupplyCount(b);
                if (supplyCount != lastSupplyCount) {
                    operationTicks = 0;
                    b.idleTicks = 0;
                    lastSupplyCount = supplyCount;
                }
                if (++operationTicks > 600) {
                    operationTicks = 0;
                    b.pauseJob("Restocking made no progress. Check the supply container and inventory space, then resume.");
                    return;
                }
                // this should only tick if there's a valid slot we can restock from
                if (slot == -1) {
                    b.error("Invalid restocking action.");
                    return;
                }

                if (indicateStopping && !breakContainer) {
                    if (stopTimer > 0) stopTimer--;
                    else {
                        returnPending = true;
                    }

                    return;
                }

                // prevent tasks executing when they shouldn't
                if (b.restockTask.tasksInactive()) {
                    if (containerOwned || secondOwned) {
                        breakContainer = indicateStopping = true;
                    } else {
                        returnPending = true;
                        return;
                    }
                }

                if (delayTimer > 0) {
                    delayTimer--;
                    return;
                }

                if (secondChest != null && !breakContainer && !pairReady && !prepareDoubleChest(b)) return;

                // calculate the amount of materials we have already pulled
                int slotsPulled = 0;
                if (b.restockTask.materials) {
                    slotsPulled += countSlots(b, itemStack -> itemStack.getItem() instanceof BlockItem bi && b.blocksToPlace.get().contains(bi.getBlock()));
                    if (b.mineEnderChests.get() && b.blocksToPlace.get().contains(Blocks.OBSIDIAN))
                        slotsPulled += (Math.max(0, countItem(b, itemStack -> itemStack.getItem() == Items.ENDER_CHEST) - b.saveEchests.get()) * 8) / 64;
                }
                if (b.restockTask.pickaxes)
                    slotsPulled += countSlots(b, b::usablePickaxe) - b.savePickaxes.get();
                if (b.restockTask.food)
                    slotsPulled += countSlots(b, itemStack -> Utils.isFood(itemStack) && !Modules.get().get(AutoEat.class).blacklist.get().contains(itemStack.getItem()));


                // whether we have pulled the minimum amount of items we want
                boolean suppliedEnough = (slotsPulled >= minimumSlots
                    || emptySlots(b) <= reservedRestockSlots(b) && discardableSupplySlot(b) < 0) && !hasPartialRestockRoom(b);
                if (suppliedEnough && !containerItem.is(Items.ENDER_CHEST)
                    && !indicateStopping && !b.mc.level.getBlockState(pos.getBlockPos()).isAir()) {
                    indicateStopping = true;
                    breakContainer = true;
                    stopTimer = 12;
                    if (b.mc.gui.screen() != null) b.mc.gui.screen().onClose();
                    return;
                }

                // Check block state
                BlockPos blockPos = pos.getBlockPos().immutable();
                BlockState blockState = b.mc.level.getBlockState(blockPos);

                if (blockState.getBlock() instanceof ShulkerBoxBlock || blockState.getBlock() == Blocks.ENDER_CHEST) {
                    if (placementSent && containerItem.getItem() instanceof BlockItem item && item.getBlock() == blockState.getBlock()) containerOwned = true;
                    if (!containerOwned) {
                        b.pauseJob("An existing container occupies the supply position. Move it before continuing; its contents will be preserved.");
                        return;
                    }
                }

                if (breakContainer && !blockState.isAir()) {
                    if (b.mc.gui.screen() != null) b.mc.gui.screen().onClose();
                    handleContainerBlock(b, blockPos);
                    return;
                }

                switch (blockState.getBlock()) {
                    // if we have placed a shulker box there should be items inside we want
                    case ShulkerBoxBlock _ -> {
                        if (b.mc.gui.screen() instanceof ShulkerBoxScreen screen) {
                            // wait for the screen to be properly loaded
                            if (screen.getMenu().containerId != b.containerId) return;

                            Container inv = ((ShulkerBoxMenuAccessor) screen.getMenu()).monocle$getContainer();

                            if (restockItems(b, inv)) {
                                delayTimer = b.inventoryDelay.get();
                                return;
                            }

                            // we have taken everything we can from the shulker box, and since slotsPulled >= minimumSlots is false, we should keep going
                            // close the screen, break the shulker box, look for more containers to loot from
                            b.mc.gui.screen().onClose();
                            breakContainer = true;
                        } else {
                            if (!b.searchShulkers.get()) breakContainer = true;
                            handleContainerBlock(b, blockPos);
                        }
                    }

                    // we are either pulling items themselves, or shulkers containing items from your ec
                    case EnderChestBlock _ -> {
                        if (b.mc.gui.screen() instanceof ContainerScreen screen) {
                            // wait for the screen to be properly loaded
                            if (screen.getMenu().containerId != b.containerId) return;

                            Container inv = screen.getMenu().getContainer();

                            if (secondOwned && b.doubleEnderChests.get() && inv.getContainerSize() < 54) {
                                b.mc.gui.screen().onClose();
                                delayTimer = Math.max(3, b.inventoryDelay.get());
                                if (++doubleOpenAttempts > 2) {
                                    doubleOpenAttempts = 0;
                                    b.pauseJob("The server did not open 54 ender-chest slots. Resume to retry, or disable Double Ender Chests to search this inventory; both placed chests will still be recovered.");
                                }
                                return;
                            }

                            // Reserve the requested useful boxes before loose supplies fill the remaining slots.
                            if (takeShulkerBatch(b, inv)) return;
                            if (!suppliedEnough && restockItems(b, inv)) {
                                delayTimer = b.inventoryDelay.get();
                                return;
                            }

                            // The batch is full, unavailable or space-limited; recover both placed chests normally.
                            emptyChestVisit = shulkersTaken == 0 && slotsPulled <= 0;
                            if (suppliedEnough && slotsPulled > 0) {
                                indicateStopping = true;
                                stopTimer = 12;
                            }
                            b.mc.gui.screen().onClose();
                            breakContainer = true;
                        } else {
                            if (!b.searchEnderChest.get()) breakContainer = true;
                            handleContainerBlock(b, blockPos);
                        }
                    }

                    // handling when there is no container there
                    case AirBlock _ -> {
                        // indicates we have just broken a container
                        if (breakContainer) {
                            breakContainer = false;
                            if (!trackedContainer) {
                                b.pauseJob("The supply container disappeared before recovery could be tracked. Recover it before resuming.");
                                breakContainer = true;
                                return;
                            }
                            pickupPending = true;
                            operationTicks = 0;
                            return;
                        }

                        if (containerOwned) {
                            b.pauseJob("The supply container disappeared unexpectedly. Check and recover it before starting a new job.");
                            return;
                        }
                        if (!b.mc.level.getBlockState(blockPos.below()).isFaceSturdy(b.mc.level, blockPos.below(), Direction.UP)
                            || !b.mc.level.getBlockState(blockPos.above()).isAir()) {
                            b.pauseJob("Restocking needs a solid floor and clear space above the supply box behind you.");
                            return;
                        }
                        if (b.placeWorkBlock(blockPos, slot)) placementSent = true;
                    }

                    // the only valid blocks should be air, a shulker box, or an ender chest
                    // if there is another type of block, assume something has gone wrong and error out (e.g. lava flowed in)
                    default -> b.error("Invalid block at container restocking position?");
                }
            }

            private boolean prepareDoubleChest(HighwayBuilder b) {
                BlockPos first = pos.getBlockPos().immutable();
                BlockState firstState = b.mc.level.getBlockState(first);
                BlockState secondState = b.mc.level.getBlockState(secondChest);
                if (b.pendingPlaces.containsKey(first) || b.pendingPlaces.containsKey(secondChest)) {
                    b.status = "Confirming double ender-chest placement";
                    return false;
                }
                if (placementSent && firstState.is(Blocks.ENDER_CHEST)) containerOwned = true;
                if (secondPlacementSent && secondState.is(Blocks.ENDER_CHEST)) secondOwned = true;
                for (BlockPos target : List.of(first, secondChest)) {
                    boolean owned = target.equals(first) ? containerOwned : secondOwned;
                    BlockState existing = b.mc.level.getBlockState(target);
                    if (owned) {
                        if (!existing.is(Blocks.ENDER_CHEST) || existing.getValue(EnderChestBlock.FACING) != pairFacing) {
                            b.pauseJob("A double ender chest is missing or has the wrong facing at " + target.toShortString() + ". Check the placed pair before resuming.");
                            return false;
                        }
                    } else if (!existing.isAir()) {
                        b.pauseJob("Double ender-chest space is occupied at " + target.toShortString() + ". Existing blocks and containers will not be replaced.");
                        return false;
                    }
                    if (b.pendingPlaces.containsKey(target.below())) return false;
                    if (!b.mc.level.getBlockState(target.below()).isFaceSturdy(b.mc.level, target.below(), Direction.UP)
                        || !b.mc.level.getBlockState(target.above()).isAir()) {
                        b.pauseJob("Double ender chests need two supported, clear road positions at " + target.toShortString() + ".");
                        return false;
                    }
                }
                if (containerOwned && secondOwned) {
                    pairReady = true;
                    return true;
                }
                boolean placingSecond = containerOwned;
                BlockPos target = placingSecond ? secondChest : first;
                Vec3 stance = placingSecond ? pairApproach.add(secondChest.getX() - first.getX(), 0, secondChest.getZ() - first.getZ()) : pairApproach;
                b.status = placingSecond ? "Aligning for the second ender chest" : "Aligning for double ender chests";
                if (!b.walkToWorkPosition(stance)) return false;
                int chestSlot = findAndMoveToHotbar(b, stack -> stack.is(Items.ENDER_CHEST));
                if (chestSlot < 0 || b.isJobPaused() || b.state != this) {
                    if (chestSlot < 0 && !b.isJobPaused()) b.pauseJob("Add an ender chest to finish placing the supply pair, then Resume.");
                    return false;
                }
                slot = chestSlot;
                if (b.placeWorkBlock(target, slot, pairFacing)) {
                    if (placingSecond) secondPlacementSent = true;
                    else placementSent = true;
                }
                return false;
            }

            private boolean restockItems(HighwayBuilder b, Container inv) {
                if (!b.recoverCursor()) return true;
                if (b.restockTask.materials) {
                    // take raw material
                    if (grabFromInventory(b, inv, itemStack -> itemStack.getItem() instanceof BlockItem bi && b.blocksToPlace.get().contains(bi.getBlock())))
                        return true;

                    // prefer taking raw material before echests
                    if (b.mineEnderChests.get() && b.blocksToPlace.get().contains(Blocks.OBSIDIAN)) {
                        if (grabFromInventory(b, inv, itemStack -> itemStack.getItem() == Items.ENDER_CHEST)) return true;
                    }
                }
                if (b.restockTask.pickaxes) {
                    if (grabFromInventory(b, inv, b::usablePickaxe)) return true;
                }
                if (b.restockTask.food) {
                    return grabFromInventory(b, inv, itemStack -> Utils.isFood(itemStack) && !Modules.get().get(AutoEat.class).blacklist.get().contains(itemStack.getItem()));
                }

                return false;
            }

            private int reservedRestockSlots(HighwayBuilder b) {
                return b.minEmpty.get() + (secondOwned ? 2 : 1);
            }

            private boolean takeShulkerBatch(HighwayBuilder b, Container inv) {
                if (!b.searchShulkers.get()) return false;
                int source = shulkerRestockSlot(inv, shulkerPredicate, shulkersTaken, b.maxShulkersPerRestock.get());
                int reserve = reservedRestockSlots(b) + 1; // Leave room to unpack a collected box after recovering the chests.
                if (source < 0 || reserve >= 36) return false;
                if (!b.recoverCursor()) return true;
                var menu = b.mc.player.containerMenu;
                ItemStack incoming = inv.getItem(source).copyWithCount(1);
                int destination = restockTransferSlot(menu.slots, b.mc.player.getInventory(), incoming, reserve);
                if (destination < 0) return discardForSupplySpace(b);
                trashTurned = false;
                int before = menu.getSlot(destination).getItem().getCount();
                // Right-click the destination to take exactly ONE, even if a server allows stacked boxes.
                b.mc.gameMode.handleContainerInput(menu.containerId, source, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, b.mc.player);
                b.mc.gameMode.handleContainerInput(menu.containerId, destination, 1, net.minecraft.world.inventory.ContainerInput.PICKUP, b.mc.player);
                if (!menu.getCarried().isEmpty())
                    b.mc.gameMode.handleContainerInput(menu.containerId, source, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, b.mc.player);
                ItemStack after = menu.getSlot(destination).getItem();
                if (ItemStack.isSameItemSameComponents(incoming, after) && after.getCount() == before + 1) {
                    shulkersTaken++;
                    operationTicks = b.idleTicks = 0;
                }
                b.status = "Taking shulkers " + shulkersTaken + "/" + b.maxShulkersPerRestock.get();
                delayTimer = b.inventoryDelay.get();
                return true;
            }

            // scans the inventory, takes out the first item that matches the predicate and returns
            private boolean grabFromInventory(HighwayBuilder b, Container inv, Predicate<ItemStack> filterItem) {
                boolean matching = false;
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack source = inv.getItem(i);
                    if (!filterItem.test(source)) continue;
                    matching = true;
                    int destination = restockTransferSlot(b.mc.player.containerMenu.slots, b.mc.player.getInventory(), source, reservedRestockSlots(b));
                    if (destination < 0) continue;
                    // PICKUP merges only into this destination and returns any remainder to its source.
                    InvUtils.move().fromId(i).toId(destination);
                    return true;
                }

                return matching && reservedRestockSlots(b) < 36 && discardForSupplySpace(b);
            }

            private boolean wantsRestockItem(HighwayBuilder b, ItemStack stack) {
                return b.restockTask.materials && stack.getItem() instanceof BlockItem block
                    && (b.blocksToPlace.get().contains(block.getBlock()) || b.mineEnderChests.get() && b.blocksToPlace.get().contains(Blocks.OBSIDIAN) && stack.is(Items.ENDER_CHEST))
                    || b.restockTask.pickaxes && b.usablePickaxe(stack)
                    || b.restockTask.food && Utils.isFood(stack) && !Modules.get().get(AutoEat.class).blacklist.get().contains(stack.getItem());
            }

            private boolean hasPartialRestockRoom(HighwayBuilder b) {
                Container source = b.mc.player.getInventory();
                if (b.mc.gui.screen() instanceof ShulkerBoxScreen screen && screen.getMenu().containerId == b.containerId)
                    source = ((ShulkerBoxMenuAccessor) screen.getMenu()).monocle$getContainer();
                else if (b.mc.gui.screen() instanceof ContainerScreen screen && screen.getMenu().containerId == b.containerId)
                    source = screen.getMenu().getContainer();
                for (int i = 0; i < source.getContainerSize(); i++) {
                    ItemStack stack = source.getItem(i);
                    if (wantsRestockItem(b, stack) && restockTransferSlot(b.mc.player.containerMenu.slots,
                        b.mc.player.getInventory(), stack, Integer.MAX_VALUE) >= 0) return true;
                }
                return false;
            }

            private void setShulkerPredicate(HighwayBuilder b) {
                shulkerPredicate = itemStack -> {
                    if (!Utils.isShulker(itemStack.getItem())) return false;
                    Utils.getItemsInContainerItem(itemStack, ITEMS);

                    for (ItemStack stack : ITEMS) {
                        if (wantsRestockItem(b, stack)) return true;
                    }

                    return false;
                };
            }

            private void handleContainerBlock(HighwayBuilder b, BlockPos bp) {
                if (breakContainer) {
                    if (!reservePickupSpace(b)) return;
                    BlockState state = b.mc.level.getBlockState(bp);
                    int toolSlot = findContainerTool(b, state);
                    if (toolSlot == -1 || b.isJobPaused() || b.state != this) return;
                    boolean silkTouch = Utils.hasEnchantment(b.mc.player.getInventory().getItem(toolSlot), Enchantments.SILK_TOUCH);
                    Item chestDrop = silkTouch ? Items.ENDER_CHEST : Items.OBSIDIAN;
                    if (!trackedContainer || state.is(Blocks.ENDER_CHEST) && !recoveryItem.is(chestDrop)) {
                        if (state.getBlock() == Blocks.ENDER_CHEST) {
                            recoveryItem = new ItemStack(chestDrop);
                            recoveryCount = silkTouch ? 1 : 8;
                        } else {
                            recoveryItem = containerItem.copy();
                            recoveryCount = 1;
                        }
                        recoveryBaseline = countItem(b, stack -> sameSupplyDrop(recoveryItem, stack));
                    }
                    if (!trackedContainer) {
                        recoveredAmount = 0;
                        recoveryTakenByOther = false;
                        previousGroundItems.clear();
                        recoveryDropId = -1;
                        recoveryDropUuid = null;
                        for (Entity entity : b.mc.level.getEntities(b.mc.player, new AABB(bp).inflate(4))) {
                            if (entity instanceof ItemEntity) previousGroundItems.add(entity.getUUID());
                        }
                        trackedContainer = true;
                    }
                    InvUtils.swap(toolSlot, false);

                    b.breakWorkBlock(bp);
                } else {
                    int epoch = b.actionEpoch;
                    BlockPos openTarget = secondOwned && doubleOpenAttempts % 2 == 1 ? secondChest : bp;
                    Runnable open = () -> {
                        if (!b.controlsPlayer() || b.actionEpoch != epoch) return;
                        b.mc.gameMode.useItemOn(b.mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(openTarget), Direction.UP, openTarget, false));
                    };
                    if (b.rotation.get().place) {
                        Rotations.rotate(Rotations.getYaw(openTarget), Rotations.getPitch(openTarget), open);
                    } else open.run();

                    delayTimer = b.inventoryDelay.get();
                }
            }

            @Override
            protected void resetSupplyJob(HighwayBuilder b) {
                returnBackstep = null;
                b.resetCursorRecovery();
                if (session && (pickupPending || (containerOwned || placementSent) && !returnPending) && Utils.canUpdate()) {
                    b.warning("Supply recovery was interrupted at " + pos.getBlockPos().toShortString() + ". Check that your container is back in your inventory.");
                }
                if (secondChest != null && (secondPlacementSent || secondOwned) && Utils.canUpdate())
                    b.warning("Check the second ender chest at " + secondChest.toShortString() + " before leaving the supply area.");
                secondChest = null;
                pairApproach = null;
                pairFacing = null;
                secondPlacementSent = secondOwned = pairReady = false;
                doubleOpenAttempts = 0;
                session = initialized = protectedSite = pickupPending = returnPending = trackedContainer = placementSent = containerOwned = trashTurned = false;
                shulkersTaken = delayTimer = 0;
                emptyChestVisit = false;
                containerItem = recoveryItem = ItemStack.EMPTY;
                previousGroundItems.clear();
                recoveryDropId = -1;
                recoveryDropUuid = null;
                recoveredAmount = recoveryCount = 0;
                recoveryTakenByOther = false;
                returnPosition = null;
                slot = -1;
                protectionPlan.clear();
                protectionBlocks.clear();
                resumeChestFarm = false;
            }

            private boolean requiresProtection(HighwayBuilder b) {
                BlockPos container = pos.getBlockPos();
                if (!b.mc.level.getBlockState(container.below()).isFaceSturdy(b.mc.level, container.below(), Direction.UP)) return true;
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    BlockPos beside = container.relative(direction);
                    if (!b.mc.level.getBlockState(beside).getFluidState().isEmpty()
                        || !b.mc.level.getBlockState(beside.above()).getFluidState().isEmpty()
                        || !b.mc.level.getBlockState(beside.below()).isFaceSturdy(b.mc.level, beside.below(), Direction.UP)) return true;
                }
                return false;
            }

            @Override
            protected void finishSupplyJob(HighwayBuilder b) {
                // Only opt into discarding boxes after all placed containers are recovered and we have returned.
                if (!b.keepShulkers.get()) b.setState(ThrowOutTrash, resumeChestFarm ? MineEnderChests : Forward);
                else if (resumeChestFarm) b.setState(MineEnderChests, PlaceEChestBlockade);
                else b.setState(Forward);
            }

            @Override
            protected boolean protectSupplySite(HighwayBuilder b, boolean clear) {
                if (++protectionTicks > 600) {
                    protectionTicks = 0;
                    b.pauseJob("The temporary supply protection is blocked. Clear access to the supply area, then resume.");
                    return false;
                }
                if (clear) {
                    var iterator = protectionBlocks.entrySet().iterator();
                    while (iterator.hasNext()) {
                        var entry = iterator.next();
                        BlockState state = b.mc.level.getBlockState(entry.getKey());
                        if (state.isAir()) {
                            iterator.remove();
                            protectionTicks = 0;
                            continue;
                        }
                        if (state.getBlock() != entry.getValue()) {
                            b.pauseJob("A temporary protection block changed. Check the supply area before continuing cleanup.");
                            return false;
                        }
                        int tool = findContainerTool(b, state);
                        if (tool == -1) return false;
                        InvUtils.swap(tool, false);
                        BlockPos target = entry.getKey();
                        b.breakWorkBlock(target);
                        return false;
                    }
                    return true;
                }

                if (protectionPlan.isEmpty()) {
                    BlockPos floor = pos.getBlockPos().below().immutable();
                    if (b.mc.level.getBlockState(floor).canBeReplaced()) protectionPlan.add(floor);
                    for (MBlockPos target : b.blockPosProvider.getBlockade(false, BlockadeType.Shulker)) {
                        BlockPos block = target.getBlockPos().immutable();
                        if (b.mc.level.getBlockState(block.below()).canBeReplaced() && !protectionPlan.contains(block.below())) protectionPlan.add(block.below());
                        protectionPlan.add(block);
                    }
                }
                if (b.placeTimer > 0) return false;
                for (BlockPos target : protectionPlan) {
                    if (!b.mc.level.getBlockState(target).canBeReplaced()) continue;
                    if (!hasItem(b, stack -> stack.getItem() instanceof BlockItem block && (b.fillerBlocks.get().contains(block.getBlock()) || b.blocksToPlace.get().contains(block.getBlock())))) {
                        b.pauseJob("Add permitted filler or road blocks to protect this exposed supply area before restocking.");
                        return false;
                    }
                    int material = findBlocksToPlacePrioritizeTrash(b);
                    if (material == -1 || b.isJobPaused() || b.state != PlaceShulkerBlockade) return false;
                    Block block = ((BlockItem) b.mc.player.getInventory().getItem(material).getItem()).getBlock();
                    if (b.placeWorkBlock(target, material)) {
                        protectionBlocks.put(target, block);
                        b.placeTimer = b.placeDelay.get();
                    }
                    return false;
                }
                protectionTicks = 0;
                return true;
            }

            private int findContainerTool(HighwayBuilder b, BlockState state) {
                int bestSlot = -1;
                double bestScore = -1;
                for (int i = 0; i < b.mc.player.getInventory().getNonEquipmentItems().size(); i++) {
                    ItemStack stack = b.mc.player.getInventory().getItem(i);
                    if (!b.usablePickaxe(stack)) continue;
                    if (state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) continue;
                    boolean silkTouch = state.is(Blocks.ENDER_CHEST) && Utils.hasEnchantment(stack, Enchantments.SILK_TOUCH);
                    if (!allowsContainerRecoveryTool(state, b.requireSilkTouchForEnderChestRecovery.get(), silkTouch)) continue;
                    double score = stack.getDestroySpeed(state);
                    if (silkTouch) score += 1000;
                    if (score > bestScore) {
                        bestSlot = i;
                        bestScore = score;
                    }
                }
                if (bestSlot == -1) {
                    b.pauseJob(state.is(Blocks.ENDER_CHEST) && b.requireSilkTouchForEnderChestRecovery.get()
                        ? "A usable Silk Touch pickaxe is required for ender-chest recovery. Add one above the durability reserve or turn off Require Silk Touch for Ender Chest Recovery in Advanced, then Resume."
                        : "A usable pickaxe is needed to recover the supply container. Add one, then resume.");
                    return -1;
                }
                if (bestSlot < 9) return bestSlot;
                int hotbarSlot = findHotbarSlot(b, true);
                if (hotbarSlot == -1) return -1;
                InvUtils.move().from(bestSlot).toHotbar(hotbarSlot);
                return hotbarSlot;
            }

            private int emptySlots(HighwayBuilder b) {
                return countSlots(b, ItemStack::isEmpty);
            }

            private int availableSupplyCount(HighwayBuilder b) {
                if (b.restockTask.pickaxes) return countItem(b, b::usablePickaxe);
                if (b.restockTask.food) return countItem(b, stack -> Utils.isFood(stack) && !Modules.get().get(AutoEat.class).blacklist.get().contains(stack.getItem()));
                return countItem(b, stack -> stack.getItem() instanceof BlockItem block && b.blocksToPlace.get().contains(block.getBlock()))
                    + (b.mineEnderChests.get() && b.blocksToPlace.get().contains(Blocks.OBSIDIAN) ? countItem(b, stack -> stack.is(Items.ENDER_CHEST)) * 8 : 0);
            }

            private boolean trackRecoveryDrop(ItemEntity item) {
                if (!sameSupplyDrop(recoveryItem, item.getItem())) return false;
                if (!Utils.isShulker(recoveryItem.getItem())) return true;
                if (previousGroundItems.contains(item.getUUID())) return false;
                if (recoveryDropUuid != null) return recoveryDropId == item.getId() && recoveryDropUuid.equals(item.getUUID());
                if (!new AABB(pos.getBlockPos()).inflate(4).intersects(item.getBoundingBox())) return false;
                recoveryDropId = item.getId();
                recoveryDropUuid = item.getUUID();
                return true;
            }

            @Override
            protected void supplyItemPickup(HighwayBuilder b, int itemId, int collectorId, int amount) {
                if (!session || !trackedContainer || !Utils.isShulker(recoveryItem.getItem()) || returnPending || !(breakContainer || pickupPending)) return;
                if (!(b.mc.level.getEntity(itemId) instanceof ItemEntity item) || !trackRecoveryDrop(item)) return;
                boolean localCollector = collectorId == b.mc.player.getId();
                int collected = supplyPickupAmount(localCollector, amount, item.getItem().getCount());
                if (!localCollector && amount > 0) recoveryTakenByOther = true;
                recoveredAmount += collected;
                if (collected > 0) {
                    operationTicks = 0;
                    b.idleTicks = 0;
                }
            }

            private int discardableSupplySlot(HighwayBuilder b) {
                int filler = expendableFillerSlot(b.mc.player.getInventory(), b.fillerBlocks.get(), b.blocksToPlace.get());
                if (filler >= 0) return filler;
                for (int i = 0; i < b.mc.player.getInventory().getNonEquipmentItems().size(); i++) {
                    if (canDiscardSupplyTrash(b, b.mc.player.getInventory().getItem(i))) return i;
                }
                return -1;
            }

            private boolean discardForSupplySpace(HighwayBuilder b) {
                if (!b.recoverCursor()) return true;
                int discard = discardableSupplySlot(b);
                if (discard < 0) { trashTurned = false; return false; }
                b.status = "Making inventory room for supplies";
                // Throw away from the container and wait for the server to receive our facing first.
                b.mc.player.setYRot((float) Rotations.getYaw(Vec3.atCenterOf(pos.getBlockPos())) + 180);
                b.mc.player.setXRot(-15);
                if (!trashTurned) trashTurned = true;
                else {
                    InvUtils.drop().slot(discard);
                    trashTurned = false;
                    operationTicks = b.idleTicks = 0;
                }
                delayTimer = Math.max(3, b.inventoryDelay.get());
                return true;
            }

            private boolean reservePickupSpace(HighwayBuilder b) {
                if (!b.recoverCursor()) return false;
                if (emptySlots(b) > 0) {
                    trashTurned = false;
                    return true;
                }
                if (discardForSupplySpace(b)) return false;
                b.pauseJob("Free one inventory slot to recover the supply container. It will stay in place until there is room.");
                return false;
            }

            private void collectContainer(HighwayBuilder b) {
                boolean shulker = Utils.isShulker(recoveryItem.getItem());
                b.status = shulker ? "Collecting shulker" : "Collecting ender-chest drops";
                Entity groundDrop = b.mc.level.getEntity(recoveryDropId);
                boolean dropPresent = groundDrop instanceof ItemEntity && groundDrop.getUUID().equals(recoveryDropUuid);
                if (shulker ? supplyPickupComplete(recoveredAmount, recoveryCount, dropPresent, recoveryTakenByOther)
                    : countItem(b, stack -> sameSupplyDrop(recoveryItem, stack)) >= recoveryBaseline + recoveryCount) {
                    pickupPending = false;
                    if (secondOwned) {
                        // Reuse single-container recovery, with a fresh baseline after the first pickup.
                        pos.set(secondChest.getX(), secondChest.getY(), secondChest.getZ());
                        secondChest = null;
                        secondOwned = secondPlacementSent = false;
                        containerOwned = placementSent = true;
                        containerItem = new ItemStack(Items.ENDER_CHEST);
                        trackedContainer = false;
                        recoveryItem = ItemStack.EMPTY;
                        breakContainer = true;
                        doubleOpenAttempts = 0;
                    } else returnPending = true;
                    operationTicks = 0;
                    b.idleTicks = 0;
                    return;
                }
                if (recoveryTakenByOther) {
                    b.pauseJob("Another player collected the supply drop. Retrieve it, then Stop and start a new job; Resume cannot verify that transfer.");
                    return;
                }
                if (delayTimer > 0) {
                    delayTimer--;
                    return;
                }
                if (!b.mc.level.getBlockState(pos.getBlockPos()).isAir()) {
                    pickupPending = false;
                    breakContainer = true;
                    return;
                }
                if (!reservePickupSpace(b)) return;
                if (++operationTicks > 300) {
                    operationTicks = 0;
                    b.pauseJob("The server has not confirmed that you picked up the supply drop. Check the ground near the supply position; the builder will not leave it behind.");
                    return;
                }
                Vec3 pickupPosition = Vec3.atBottomCenterOf(pos.getBlockPos());
                for (Entity entity : b.mc.level.getEntities(b.mc.player, new AABB(pos.getBlockPos()).inflate(4))) {
                    if (entity instanceof ItemEntity item && trackRecoveryDrop(item)) {
                        pickupPosition = Vec3.atBottomCenterOf(item.blockPosition());
                        break;
                    }
                }
                if (secondOwned && !b.standable(b.cell(BlockPos.containing(pickupPosition))))
                    pickupPosition = Vec3.atBottomCenterOf(pos.getBlockPos());
                b.walkToWorkPosition(pickupPosition);
            }

            private int countSlots(HighwayBuilder b, Predicate<ItemStack> predicate) {
                int count = 0;
                for (int i = 0; i < b.mc.player.getInventory().getNonEquipmentItems().size(); i++) {
                    ItemStack stack = b.mc.player.getInventory().getItem(i);
                    if (predicate.test(stack)) count++;
                }

                return count;
            }
        },

        PlaceShulkerBlockade {
            @Override
            protected void tick(HighwayBuilder b) {
                if (Restock.protectSupplySite(b, false)) b.setState(Restock);
            }
        },

        MineShulkerBlockade {
            @Override
            protected void tick(HighwayBuilder b) {
                if (Restock.protectSupplySite(b, true)) Restock.finishSupplyJob(b);
            }
        },

        DefuseCrystalTraps {
            private int cooldown,shots;
            private EndCrystal target;

            @Override
            protected void start(HighwayBuilder b) {
                shots = cooldown = 0;
                target = null;
                if (!InvUtils.find(Items.BOW).found() || (!InvUtils.find(itemStack -> itemStack.getItem() instanceof ArrowItem).found() && !b.mc.player.getAbilities().instabuild)) {
                    b.pauseJob("Crystal ahead. Add a bow and arrows, or clear the hazard before resuming.");
                }
            }

            /**
             * Need to perform the linked injection to ensure that vanilla code does not interfere with us drawing our
             * bow. The {@link net.minecraft.client.Minecraft#handleKeybinds} method is only called when you are not in a screen,
             * meaning we cannot draw our bow using {@link net.minecraft.client.Options#keyUse} since it would not work if you are in a
             * screen. Similarly, drawing our bow by {@link net.minecraft.client.multiplayer.MultiPlayerGameMode#useItem} would get
             * cancelled by default within the handleKeybinds method if you do not have the use key held down,
             * essentially meaning without the following injection it would not work if you don't have a screen open.
             *
             * @see MinecraftMixin#wrapStopUsing(MultiPlayerGameMode, Player)
             */
            @Override
            protected void tick(HighwayBuilder b) {
                if (!b.mc.player.isCreative() && !InvUtils.find(stack -> stack.getItem() instanceof ArrowItem).found()) {
                    b.pauseJob("Out of arrows while clearing a crystal. Refill or clear the hazard, then Resume.");
                    return;
                }
                if (cooldown > 0) {
                    cooldown--;
                    return;
                }

                if (!InvUtils.testInMainHand(Items.BOW)) {
                    int slot = findAndMoveToHotbar(b, itemStack -> itemStack.getItem() instanceof BowItem);
                    if (slot == -1) {
                        b.pauseJob("No bow available to clear the crystal. Add one or clear the hazard, then Resume.");
                        return;
                    }

                    InvUtils.swap(slot, false);
                }

                EndCrystal potentialTarget = (EndCrystal) TargetUtils.get(entity -> {
                    if (!(entity instanceof EndCrystal endCrystal)) return false;
                    if (PlayerUtils.isWithin(endCrystal, 12) || !PlayerUtils.isWithin(endCrystal, 24)) return false;
                    if (b.ignoreCrystals.contains(endCrystal)) return false;

                    Vec3 vec1 = new Vec3(0, 0, 0);
                    Vec3 vec2 = new Vec3(0, 0, 0);

                    ((IVec3) vec1).monocle$set(b.mc.player.getX(), b.mc.player.getY() + b.mc.player.getEyeHeight(), b.mc.player.getZ());
                    ((IVec3) vec2).monocle$set(entity.getX(), entity.getY() + 0.5, entity.getZ());
                    return b.mc.level.clip(new ClipContext(vec1, vec2, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, b.mc.player)).getType() == HitResult.Type.MISS;
                }, SortPriority.LowestDistance);

                if (target == null || target.isRemoved()) {
                    if (potentialTarget == null) {
                        b.setState(Forward);
                        b.mc.gameMode.releaseUsingItem(b.mc.player);
                        b.drawingBow = false;
                        return;
                    } else {
                        target = potentialTarget;
                        shots = 0;
                    }
                }

                if (shots >= 3) {
                    shots = 0;
                    b.pauseJob("Crystal still present after three shots. Check the hazard before resuming.");
                    return;
                }

                b.mc.player.setYRot((float) Rotations.getYaw(target));

                float pitch = aim(b, target);
                if (Float.isNaN(pitch)) b.mc.player.setXRot((float) Rotations.getPitch(target));
                else b.mc.player.setXRot(pitch);

                if (BowItem.getPowerForTime(b.mc.player.getTicksUsingItem() - 3) >= 1.0f) {
                    b.mc.gameMode.releaseUsingItem(b.mc.player);
                    b.drawingBow = false;
                    cooldown = 20;
                    shots++;
                } else {
                    b.drawingBow = true;
                    b.mc.gameMode.useItem(b.mc.player, InteractionHand.MAIN_HAND);
                }
            }

            private float aim(HighwayBuilder b, Entity target) {
                // Velocity based on bow charge.
                float velocity = BowItem.getPowerForTime(b.mc.player.getTicksUsingItem());

                // Positions
                Vec3 pos = target.position();

                double relativeX = pos.x - b.mc.player.getX();
                double relativeY = pos.y + 0.5 - b.mc.player.getEyeY(); // aiming a little bit above the bottom of the crystal, hopefully prevents shooting the floor or failing the raytrace check
                double relativeZ = pos.z - b.mc.player.getZ();

                // Calculate the pitch
                double hDistance = Math.sqrt(relativeX * relativeX + relativeZ * relativeZ);
                double hDistanceSq = hDistance * hDistance;
                float g = 0.006f;
                float velocitySq = velocity * velocity;

                return (float) -Math.toDegrees(Math.atan((velocitySq - Math.sqrt(velocitySq * velocitySq - g * (g * hDistanceSq + 2 * relativeY * velocitySq))) / (g * hDistance)));
            }
        };

        protected boolean canDiscardSupplyTrash(HighwayBuilder b, ItemStack stack) {
            if (Utils.isShulker(stack.getItem())) return shouldEjectShulker(stack, b.keepShulkers.get(), supply ->
                supply.getItem() instanceof BlockItem block && (b.blocksToPlace.get().contains(block.getBlock()) || b.fillerBlocks.get().contains(block.getBlock()))
                    || supply.is(Items.ENDER_CHEST) || supply.is(ItemTags.PICKAXES)
                    || Utils.isFood(supply) && !Modules.get().get(AutoEat.class).blacklist.get().contains(supply.getItem()), b.mc.player.registryAccess());
            if (isExpendableFiller(stack, b.fillerBlocks.get(), b.blocksToPlace.get())) return true;
            if (stack.isEmpty() || !b.trashItems.get().contains(stack.getItem())) return false;
            if (AutoTool.isTool(stack) || stack.isDamageableItem() || Utils.isFood(stack)
                || stack.getItem() instanceof BlockItem block && block.getBlock() instanceof BaseEntityBlock) return false;
            return !(stack.getItem() instanceof BlockItem block) || !b.blocksToPlace.get().contains(block.getBlock());
        }

        protected void resetSupplyJob(HighwayBuilder b) {
        }

        protected boolean retryReturn(HighwayBuilder b) {
            return false;
        }

        protected boolean recoverSealing(HighwayBuilder b) { return false; }

        protected void resetReturnPath(HighwayBuilder b) {
            b.input.stop();
            b.walkGoal = null;
            b.walkPath = List.of();
            b.walkIndex = b.walkTicks = b.idleTicks = 0;
            b.lastWalkDistance = Double.MAX_VALUE;
        }

        protected boolean safeBackstep(HighwayBuilder b, Vec3 target) {
            if (!b.mc.player.onGround() || target.distanceToSqr(b.jobWorkPosition()) > 121) return false;
            Vec3 current = b.mc.player.position();
            if (!b.mc.level.noCollision(b.mc.player, b.mc.player.getBoundingBox().expandTowards(target.subtract(current)))) return false;
            // Check the whole retreat, including both edges of the player's footprint.
            for (int step = 0; step <= 4; step++) {
                Vec3 point = current.lerp(target, step / 4.0);
                for (double x : new double[] {-0.29, 0.29}) for (double z : new double[] {-0.29, 0.29}) {
                    BlockPos feet = BlockPos.containing(point.x + x, b.walkingFeet().getY(), point.z + z);
                    if (!b.mc.level.getWorldBorder().isWithinBounds(feet) || !b.standable(b.cell(feet))) return false;
                }
            }
            return true;
        }

        protected void supplyItemPickup(HighwayBuilder b, int itemId, int collectorId, int amount) {
        }

        protected boolean protectSupplySite(HighwayBuilder b, boolean clear) {
            return true;
        }

        protected void finishSupplyJob(HighwayBuilder b) {
        }

        protected void start(HighwayBuilder b) {
        }

        protected abstract void tick(HighwayBuilder b);

        protected void mine(HighwayBuilder b, MBPIterator it, boolean mineBlocksToPlace, State nextState, State lastState) {
            boolean breaking = false;
            boolean finishedBreaking = false; // if you can multi break this lets you mine blocks between tasks in a single tick

            // extract all candidates for double mining and enqueue them to be mined. After those we can break the remaining
            // blocks normally
            if (b.doubleMine.get()) {
                // todo hold the best mining tool before performing the double mining checks so we dont double mine blocks unnecessarily
                ArrayDeque<BlockPos> toDoubleMine = new ArrayDeque<>();

                it.save();
                it.forEach(pos -> {
                    // only want to double mine blocks that we can mine, that are not instamined, and we are not already mining
                    if (
                        BlockUtils.canBreak(pos.getBlockPos(), pos.getState())
                            && (mineBlocksToPlace || !b.blocksToPlace.get().contains(pos.getState().getBlock()))
                            && !BlockUtils.canInstaBreak(pos.getBlockPos()) && (!Modules.get().get(SpeedMine.class).instamine() || pos.getState().getDestroyProgress(b.mc.player, b.mc.level, pos.getBlockPos()) <= 0.5)
                            && (b.normalMining == null || !pos.getBlockPos().equals(b.normalMining.blockPos))
                            && (b.packetMining == null || !pos.getBlockPos().equals(b.packetMining.blockPos))
                            && pos.getBlockPos().distToCenterSqr(b.mc.player.getEyePosition()) <= b.mc.player.blockInteractionRange() * b.mc.player.blockInteractionRange()
                    ) {
                        toDoubleMine.add(pos.getBlockPos().mutable());
                    }
                });

                // have to save and restore the iterator from the beginning to make sure the subsequent loop can use it properly
                it.restore();

                // repeating the code for swapping to a tool, since we don't want to start mining a block if we don't
                // have a tool to mine it with, but also we want to lock the slot to the tool while we are mining even
                // the ArrayDequeue is empty
                if (!toDoubleMine.isEmpty()) {
                    int slot = findAndMoveBestToolToHotbar(b, b.mc.level.getBlockState(toDoubleMine.peek()), false);
                    if (slot == -1) return;

                    InvUtils.swap(slot, false);
                    doubleMine(b, toDoubleMine);
                }

                if (b.normalMining != null || b.packetMining != null) {
                    int slot = findAndMoveBestToolToHotbar(b, b.normalMining != null ? b.normalMining.blockState : b.packetMining.blockState, false);
                    if (slot == -1) return;

                    InvUtils.swap(slot, false);
                    return;
                }
            }

            for (MBlockPos pos : it) {
                if (b.count >= b.blocksPerTick.get()) return;
                if (b.breakTimer > 0) return;

                BlockState state = pos.getState();
                if (state.isAir() || state.canBeReplaced() && !state.getFluidState().isEmpty()
                    || (!mineBlocksToPlace && b.blocksToPlace.get().contains(state.getBlock()))) continue;

                if (!BlockUtils.canBreak(pos.getBlockPos(), state)) {
                    b.pauseJob("Unbreakable obstruction at " + pos.getBlockPos().toShortString() + ".");
                    return;
                }
                if (!b.reach(pos.getBlockPos(), b.mc.player.blockInteractionRange())) return;

                int slot = findAndMoveBestToolToHotbar(b, state, false);
                if (slot == -1) return;

                InvUtils.swap(slot, false);

                BlockPos mcPos = pos.getBlockPos();
                boolean multiBreak = b.blocksPerTick.get() > 1 && BlockUtils.canInstaBreak(mcPos) && !b.rotation.get().mine;
                if (BlockUtils.canBreak(mcPos)) {
                    b.breakWorkBlock(mcPos);
                    breaking = true;

                    b.breakTimer = b.breakDelay.get();

                    if (!b.lastBreakingPos.equals(pos)) {
                        b.lastBreakingPos.set(pos);
                    }

                    b.count++;

                    // can only multi break if we aren't rotating and the block can be insta-mined
                    if (!multiBreak) break;
                }

                if (!it.hasNext() && BlockUtils.canInstaBreak(mcPos)) finishedBreaking = true;
            }

            // we quickly jump to the next state, to remove micro delays in the process and allow us to break blocks
            // between tasks if we can multi break
            if (finishedBreaking || !breaking) {
                b.setState(nextState, lastState);
            }
        }

        private void doubleMine(HighwayBuilder b, ArrayDeque<BlockPos> blocks) {
            if (b.breakTimer > 0) return;

            if (b.normalMining == null) {
                DoubleMineBlock block = new DoubleMineBlock(b, blocks.pop());
                b.normalMining = block.startDestroying();

                b.breakTimer = b.breakDelay.get();
                if (b.breakTimer > 0) return;
            }

            if (DoubleMineBlock.rateLimited) return;

            if (b.packetMining == null && !blocks.isEmpty()) {
                DoubleMineBlock block = new DoubleMineBlock(b, blocks.pop());

                if (block != null) {
                    b.packetMining = b.normalMining.packetMine();
                    b.normalMining = block.startDestroying();

                    b.breakTimer = b.breakDelay.get();
                }
            }
        }

        protected void place(HighwayBuilder b, MBPIterator it, int slot, State nextState) {
            boolean placed = false;
            boolean finishedPlacing = false;

            for (MBlockPos pos : it) {
                if (b.count >= it.placementsPerTick(b)) return;
                if (b.placeTimer > 0) return;

                if (!pos.getState().canBeReplaced()) continue;
                if (!b.reach(pos.getBlockPos(), b.placeRange.get())) return;

                if (b.placeWorkBlock(pos.getBlockPos(), slot)) {
                    placed = true;
                    b.placeTimer = b.placeDelay.get();

                    b.count++;
                    if (b.placementsPerTick.get() == 1) break;
                }

                if (!it.hasNext()) finishedPlacing = true;
            }

            if (finishedPlacing || !placed) b.setState(nextState);
        }

        private int findSlot(HighwayBuilder b, Predicate<ItemStack> predicate, boolean hotbar) {
            for (int i = hotbar ? 0 : 9; i < (hotbar ? 9 : b.mc.player.getInventory().getNonEquipmentItems().size()); i++) {
                if (predicate.test(b.mc.player.getInventory().getItem(i))) return i;
            }

            return -1;
        }

        protected int findHotbarSlot(HighwayBuilder b, boolean replaceTools) {
            if (!b.recoverCursor()) return -1;
            // PICKUP exchanges a displaced hotbar stack back into the incoming item's main-inventory slot.
            return hotbarSwapSlot(b.mc.player.getInventory(), replaceTools, b.queuedHotbarSlots,
                b.fillerBlocks.get(), b.blocksToPlace.get(), b.trashItems.get());
        }

        protected boolean hasItem(HighwayBuilder b, Predicate<ItemStack> predicate) {
            for (int i = 0; i < b.mc.player.getInventory().getNonEquipmentItems().size(); i++) {
                if (predicate.test(b.mc.player.getInventory().getItem(i))) return true;
            }

            return false;
        }

        protected int countItem(HighwayBuilder b, Predicate<ItemStack> predicate) {
            int count = 0;
            for (int i = 0; i < b.mc.player.getInventory().getNonEquipmentItems().size(); i++) {
                ItemStack stack = b.mc.player.getInventory().getItem(i);
                if (predicate.test(stack)) count += stack.getCount();
            }

            return count;
        }

        protected int findAndMoveToHotbar(HighwayBuilder b, Predicate<ItemStack> predicate) {
            if (!b.recoverCursor()) return -1;
            // Check hotbar
            int slot = findSlot(b, predicate, true);
            if (slot != -1) return slot;

            // Check inventory
            slot = findSlot(b, predicate, false);

            // Return if no items were found
            if (slot == -1) return -1;

            int hotbarSlot = findHotbarSlot(b, false);
            if (hotbarSlot == -1) return -1;

            // Move items from inventory to hotbar
            InvUtils.move().from(slot).toHotbar(hotbarSlot);

            return hotbarSlot;
        }

        protected int findAndMoveBestToolToHotbar(HighwayBuilder b, BlockState blockState, boolean noSilkTouch) {
            if (!b.recoverCursor()) return -1;
            // Check for creative
            if (b.mc.player.isCreative()) return b.mc.player.getInventory().getSelectedSlot();

            // Find best tool
            double bestScore = -1;
            int bestSlot = -1;

            for (int i = 0; i < b.mc.player.getInventory().getNonEquipmentItems().size(); i++) {
                double score = AutoTool.getScore(b.mc.player.getInventory().getItem(i), blockState, false, false, AutoTool.EnchantPreference.None, itemStack -> {
                    if (noSilkTouch && Utils.hasEnchantment(itemStack, Enchantments.SILK_TOUCH)) return false;
                    return !b.dontBreakTools.get() || HighwayPlan.usableTool(itemStack.getMaxDamage() - itemStack.getDamageValue(), itemStack.getMaxDamage(), b.breakDurability.get());
                });

                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }

            if (bestSlot == -1) {
                if (!blockState.requiresCorrectToolForDrops()) {
                    for (int i = 0; i < 9; i++) if (!b.mc.player.getInventory().getItem(i).isDamageableItem()) return i;
                }
                if (!b.restockTask.pickaxes && b.hasSupplyContainer()) { b.restockTask.setPickaxes(); return -1; }
                b.pauseJob(noSilkTouch ? "Need a usable pickaxe without Silk Touch to convert ender chests." : "No suitable tool above the durability reserve. Add a tool, then Resume.");
                return -1;
            }

            ItemStack bestStack = b.mc.player.getInventory().getItem(bestSlot);
            if (bestStack.is(ItemTags.PICKAXES)) {
                int count = countItem(b, b::usablePickaxe);

                // If we are in the process of restocking pickaxes and happen to need one, we should allow using it
                // as long as it has enough durability, since we will obtain more shortly thereafter
                if (count <= b.savePickaxes.get() && !(b.restockTask.pickaxes && b.usablePickaxe(bestStack))) {
                    if (!b.restockTask.pickaxes && (b.searchEnderChest.get() || b.searchShulkers.get())) {
                        b.restockTask.setPickaxes();
                    } else {
                        b.error("Found less than the minimum amount of pickaxes required: " + count + "/" + (b.savePickaxes.get() + 1));
                    }

                    return -1;
                }
            }

            // Check if the tool is already in hotbar
            if (bestSlot < 9) return bestSlot;

            // Find hotbar slot to move to
            int hotbarSlot = findHotbarSlot(b, true);
            if (hotbarSlot == -1) return -1;

            // Move tool from inventory to hotbar
            InvUtils.move().from(bestSlot).toHotbar(hotbarSlot);

            return hotbarSlot;
        }

        protected int findBlocksToPlace(HighwayBuilder b) {
            if (!b.recoverCursor()) return -1;
            // find a block and move it to your hotbar
            Predicate<ItemStack> road = stack -> stack.getItem() instanceof BlockItem block && b.blocksToPlace.get().contains(block.getBlock());
            int slot = findAndMoveToHotbar(b, road);

            if (slot == -1) {
                if (b.isJobPaused() || !b.recoverCursor()) return -1;
                if (hasItem(b, road)) return -1; // A queued hotbar source must finish before this carried stack can move.
                if (b.mineEnderChests.get() && b.blocksToPlace.get().contains(Blocks.OBSIDIAN) && countItem(b, stack -> stack.getItem().equals(Items.ENDER_CHEST)) > b.saveEchests.get()) {
                    // can grind echests for obsidian
                    b.setState(MineEnderChests);
                } else if (b.searchEnderChest.get() || b.searchShulkers.get()) {
                    // start restocking if we're allowed
                    b.restockTask.setMaterials();
                } else {
                    b.error("Out of blocks to place.");
                }

                return -1;
            }

            return slot;
        }

        protected int findBlocksToPlacePrioritizeTrash(HighwayBuilder b) {
            if (!b.recoverCursor()) return -1;
            Predicate<ItemStack> filler = stack -> isExpendableFiller(stack, b.fillerBlocks.get(), b.blocksToPlace.get());
            int slot = findAndMoveToHotbar(b, filler);

            if (slot == -1 && (b.isJobPaused() || !b.recoverCursor() || hasItem(b, filler))) return -1;
            return slot != -1 ? slot : findBlocksToPlace(b);
        }
    }

    private interface MBPIterator extends Iterator<MBlockPos>, Iterable<MBlockPos> {
        void save();

        void restore();

        @NonNull
        @Override
        default Iterator<MBlockPos> iterator() {
            return this;
        }

        default int placementsPerTick(HighwayBuilder b) {
            return b.placementsPerTick.get();
        }
    }

    private static class MBPIteratorFilter implements MBPIterator {
        private final MBPIterator it;
        private final Predicate<MBlockPos> predicate;

        private MBlockPos pos;
        private boolean isOld = true;

        private boolean pisOld = true;

        public MBPIteratorFilter(MBPIterator it, Predicate<MBlockPos> predicate) {
            this.it = it;
            this.predicate = predicate;
        }

        @Override
        public void save() {
            it.save();
            pisOld = isOld;
            isOld = true;
        }

        @Override
        public void restore() {
            it.restore();
            isOld = pisOld;
        }

        @Override
        public boolean hasNext() {
            if (isOld) {
                isOld = false;
                pos = null;

                while (it.hasNext()) {
                    pos = it.next();

                    if (predicate.test(pos)) return true;
                    else pos = null;
                }
            }

            return pos != null && predicate.test(pos);
        }

        @Override
        public MBlockPos next() {
            isOld = true;
            return pos;
        }
    }

    private interface IBlockPosProvider {
        MBPIterator getFront();

        MBPIterator getFloor();

        /**
         * state:
         * 1 for above the railings,
         * 0 for the railings themselves,
         * -1 for the block under the railings
         */
        MBPIterator getRailings(int state);

        MBPIterator getLiquids();

        MBPIterator getBlockade(boolean mine, BlockadeType type);
    }

    private class PlannedBlockPosProvider implements IBlockPosProvider {
        private MBPIterator positions(List<HighwayPlan.Cell> cells) {
            return new PositionIterator(cells.stream().map(p -> workOrigin.offset(p.x(), p.y(), p.z())).toList(), false);
        }

        @Override public MBPIterator getFront() {
            return positions(HighwayPlan.front(dir.offsetX, dir.offsetZ, width.get(), height.get()));
        }
        @Override public MBPIterator getFloor() {
            return positions(HighwayPlan.floor(dir.offsetX, dir.offsetZ, width.get()));
        }
        @Override public MBPIterator getRailings(int level) {
            return positions(HighwayPlan.railings(dir.offsetX, dir.offsetZ, width.get(), height.get(), level));
        }
        @Override public MBPIterator getLiquids() {
            return positions(HighwayPlan.liquids(dir.offsetX, dir.offsetZ, width.get(), height.get(), mineAboveRailings.get()));
        }

        @Override
        public MBPIterator getBlockade(boolean mine, BlockadeType type) {
            HorizontalDirection back = dir.diagonal ? dir.rotateLeft().rotateLeftSkipOne() : dir.opposite();
            HorizontalDirection side = dir.diagonal ? back.rotateLeftSkipOne() : leftDir;
            BlockPos origin = mc.player.blockPosition().offset(back.offsetX, 0, back.offsetZ);
            List<BlockPos> positions = new ArrayList<>();
            for (int i = mine ? -1 : 0; i < type.columns; i++) {
                BlockPos p = switch (i) {
                    case -1 -> origin;
                    case 0 -> origin.offset(back.offsetX, 0, back.offsetZ);
                    case 1 -> origin.offset(side.offsetX, 0, side.offsetZ);
                    case 2 -> origin.offset(-side.offsetX, 0, -side.offsetZ);
                    case 3 -> origin.offset(-back.offsetX * 2, 0, -back.offsetZ * 2);
                    case 4 -> origin.offset(-back.offsetX + side.offsetX, 0, -back.offsetZ + side.offsetZ);
                    case 5 -> origin.offset(-back.offsetX - side.offsetX, 0, -back.offsetZ - side.offsetZ);
                    default -> throw new IllegalArgumentException();
                };
                for (int y = !dir.diagonal && width.get() == 1 && railings.get() && i > 0 ? 1 : 0; y < 2; y++) positions.add(p.above(y));
            }
            return new PositionIterator(positions, true);
        }
    }

    private static class PositionIterator implements MBPIterator {
        private final List<BlockPos> positions;
        private final boolean singlePlacement;
        private final MBlockPos pos = new MBlockPos();
        private int index, saved;

        PositionIterator(List<BlockPos> positions, boolean singlePlacement) {
            this.positions = positions;
            this.singlePlacement = singlePlacement;
        }
        @Override public boolean hasNext() { return index < positions.size(); }
        @Override public MBlockPos next() {
            BlockPos next = positions.get(index++);
            return pos.set(next.getX(), next.getY(), next.getZ());
        }
        @Override public void save() { saved = index; index = 0; }
        @Override public void restore() { index = saved; }
        @Override public int placementsPerTick(HighwayBuilder b) { return singlePlacement ? 1 : b.placementsPerTick.get(); }
    }

    public static class DoubleMineBlock {
        public static boolean rateLimited = false;
        public final BlockPos blockPos;
        public final BlockState blockState;

        private final Block block;
        private final Direction direction;
        private final HighwayBuilder b;
        private final Vector3d vec3 = new Vector3d(0);

        private int normalStartTime, packetStartTime, firstStopTime = -1;
        private boolean packet;

        public DoubleMineBlock(HighwayBuilder b, BlockPos pos) {
            this.b = b;
            this.blockPos = pos;
            this.blockState = b.mc.level.getBlockState(this.blockPos);
            this.block = this.blockState.getBlock();
            this.direction = BlockUtils.getDirection(pos);
            this.packet = false;
        }

        public DoubleMineBlock startDestroying() {
            if (!b.controlsPlayer()) return this;
            b.liquidPlacements.remove(blockPos);
            b.pendingBreaks.putIfAbsent(blockPos.immutable(), blockState);
            b.mc.gameMode.startPrediction(b.mc.level, sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, this.blockPos, this.direction, sequence));
            normalStartTime = b.mc.player.tickCount;
            return this;
        }

        public DoubleMineBlock stopDestroying() {
            if (!b.controlsPlayer() || !b.waiting.isEmpty()) return this;
            if (firstStopTime < 0) firstStopTime = b.mc.player.tickCount;
            b.mc.gameMode.startPrediction(b.mc.level, sequence -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, this.blockPos, this.direction, sequence));
            return this;
        }

        public DoubleMineBlock packetMine() {
            packetStartTime = b.mc.player.tickCount;
            packet = true;
            return stopDestroying();
        }

        public boolean isReady() {
            return progress() >= (b.fastBreak.get() ? 0.7 : 1.0);
        }

        public boolean shouldRemove() {
            boolean distance = blockPos.distToCenterSqr(b.mc.player.getEyePosition()) > b.mc.player.blockInteractionRange() * b.mc.player.blockInteractionRange();

            // Bound the wait after submitting completion, without timing out legitimate slow mining.
            // Changing the held item or resending STOP must not extend that wait indefinitely.
            boolean timeout = miningConfirmationExpired(b.mc.player.tickCount, firstStopTime);

            return distance || timeout;
        }

        public double progress() {
            int slot = b.mc.player.getInventory().getSelectedSlot();
            return BlockUtils.getBreakDelta(slot, blockState) * ((b.mc.player.tickCount - (packet ? packetStartTime : normalStartTime)) + 1);
        }

        public void renderLetter(GuiGraphicsExtractor graphics) {
            vec3.set(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5);
            if (!NametagUtils.to2D(vec3, 2)) return;

            NametagUtils.begin(vec3);
            TextRenderer.get().begin(graphics, 1.0, false, true);

            String letter = packet ? "P" : "N";
            double w = TextRenderer.get().getWidth(letter) / 2.0;
            TextRenderer.get().render(letter, -w, 0.0, Color.WHITE, true);

            TextRenderer.get().end();
            NametagUtils.end();
        }
    }

    static boolean miningConfirmationExpired(int tick, int firstStopTick) {
        return firstStopTick >= 0 && tick - firstStopTick >= 60;
    }

    private class RestockTask {
        public boolean materials;
        public boolean pickaxes;
        public boolean food;
        private final HighwayBuilder b;

        public RestockTask(HighwayBuilder b) {
            this.b = b;
        }

        public void setMaterials() {
            setTask(0);
        }

        public void setPickaxes() {
            setTask(1);
        }

        public void setFood() {
            setTask(2);
        }

        private void setTask(@Range(from = 0, to = 2) int value) {
            complete();

            switch (value) {
                case 0 -> materials = true;
                case 1 -> pickaxes = true;
                case 2 -> food = true;
            }

            setState(State.Restock);
            b.info("Starting new restock task for " + item());
        }

        public void complete() {
            materials = false;
            pickaxes = false;
            food = false;
        }

        public boolean tasksInactive() {
            return !materials && !pickaxes && !food;
        }

        public String item() {
            if (materials) return "building materials";
            if (pickaxes) return "pickaxes";
            if (food) return "food";
            return "unknown";
        }
    }
}
