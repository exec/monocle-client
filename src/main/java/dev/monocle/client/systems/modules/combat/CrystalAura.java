/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.combat;

import it.unimi.dsi.fastutil.ints.*;
import dev.monocle.client.events.entity.EntityAddedEvent;
import dev.monocle.client.events.entity.EntityRemovedEvent;
import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.events.render.Render2DEvent;
import dev.monocle.client.events.render.Render3DEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.mixininterface.IAABB;
import dev.monocle.client.mixininterface.IClipContext;
import dev.monocle.client.mixininterface.IVec3;
import dev.monocle.client.renderer.ShapeMode;
import dev.monocle.client.renderer.text.TextRenderer;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.friends.Friends;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.utils.entity.DamageUtils;
import dev.monocle.client.utils.entity.EntityUtils;
import dev.monocle.client.utils.entity.Target;
import dev.monocle.client.utils.misc.Keybind;
import dev.monocle.client.utils.player.FindItemResult;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.PlayerUtils;
import dev.monocle.client.utils.player.Rotations;
import dev.monocle.client.utils.render.NametagUtils;
import dev.monocle.client.utils.render.RenderUtils;
import dev.monocle.client.utils.render.color.Color;
import dev.monocle.client.utils.render.color.SettingColor;
import dev.monocle.client.utils.world.BlockIterator;
import dev.monocle.client.utils.world.BlockUtils;
import dev.monocle.client.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.*;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class CrystalAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSwitch = settings.createGroup("Switch");
    private final SettingGroup sgPlace = settings.createGroup("Place");
    private final SettingGroup sgFacePlace = settings.createGroup("Face Place");
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

    private final Setting<Boolean> predictMovement = sgGeneral.add(new BoolSetting.Builder()
        .name("predict-movement")
        .description("Predicts target movement.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("Minimum damage the crystal needs to deal to your target.")
        .defaultValue(6)
        .min(0)
        .build()
    );

    private final Setting<Double> maxDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-damage")
        .description("Maximum damage crystals can deal to yourself.")
        .defaultValue(8)
        .range(0, 36)
        .sliderMax(36)
        .build()
    );

    private final Setting<Boolean> antiSuicide = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-suicide")
        .description("Will not place and break crystals if they will kill you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> healthReserve = sgGeneral.add(new DoubleSetting.Builder()
        .name("health-reserve")
        .description("Minimum health and absorption left after self damage.")
        .defaultValue(6)
        .range(0, 36)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> protectFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("protect-friends")
        .description("Rejects explosions that would seriously hurt a friend.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxFriendDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-friend-damage")
        .description("Maximum damage an explosion may deal to a friend.")
        .defaultValue(4)
        .range(0, 36)
        .sliderMax(20)
        .visible(protectFriends::get)
        .build()
    );

    private final Setting<Boolean> ignoreNakeds = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-nakeds")
        .description("Ignore players with no items.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates server-side towards the crystals being hit/placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<YawStepMode> yawStepMode = sgGeneral.add(new EnumSetting.Builder<YawStepMode>()
        .name("yaw-steps-mode")
        .description("When to run the yaw steps check.")
        .defaultValue(YawStepMode.Break)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Double> yawSteps = sgGeneral.add(new DoubleSetting.Builder()
        .name("yaw-steps")
        .description("Maximum number of degrees its allowed to rotate in one tick.")
        .defaultValue(180)
        .range(1, 180)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Entities to attack.")
        .onlyAttackable()
        .defaultValue(EntityTypes.PLAYER, EntityTypes.WARDEN, EntityTypes.WITHER)
        .build()
    );

    // Switch

    private final Setting<AutoSwitchMode> autoSwitch = sgSwitch.add(new EnumSetting.Builder<AutoSwitchMode>()
        .name("auto-switch")
        .description("Switches to crystals in your hotbar once a target is found.")
        .defaultValue(AutoSwitchMode.Silent)
        .build()
    );

    private final Setting<Integer> switchDelay = sgSwitch.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("The delay in ticks to wait to break a crystal after switching hotbar slot.")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private final Setting<Boolean> noGapSwitch = sgSwitch.add(new BoolSetting.Builder()
        .name("no-gap-switch")
        .description("Won't auto switch if you're holding a gapple.")
        .defaultValue(true)
        .visible(() -> autoSwitch.get() == AutoSwitchMode.Normal)
        .build()
    );

    private final Setting<Boolean> noBowSwitch = sgSwitch.add(new BoolSetting.Builder()
        .name("no-bow-switch")
        .description("Won't auto switch if you're holding a bow.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> antiWeakness = sgSwitch.add(new BoolSetting.Builder()
        .name("anti-weakness")
        .description("Switches to tools with so you can break crystals with the weakness effect.")
        .defaultValue(true)
        .build()
    );

    // Place

    private final Setting<Boolean> doPlace = sgPlace.add(new BoolSetting.Builder()
        .name("place")
        .description("If the CA should place crystals.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> placeDelay = sgPlace.add(new IntSetting.Builder()
        .name("place-delay")
        .description("The delay in ticks to wait to place a crystal after it's exploded.")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("Range in which to place crystals.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> placeWallsRange = sgPlace.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("Range in which to place crystals when behind blocks.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> placement112 = sgPlace.add(new BoolSetting.Builder()
        .name("1.12-placement")
        .description("Uses 1.12 crystal placement.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SupportMode> support = sgPlace.add(new EnumSetting.Builder<SupportMode>()
        .name("base-builder")
        .description("Actively airplaces obsidian bases when they produce the best crystal attack.")
        .defaultValue(SupportMode.Confirmed)
        .build()
    );

    private final Setting<Integer> supportDelay = sgPlace.add(new IntSetting.Builder()
        .name("base-confirm-delay")
        .description("Additional delay after the server confirms an obsidian placement.")
        .defaultValue(0)
        .min(0)
        .visible(() -> support.get() != SupportMode.Disabled)
        .build()
    );

    private final Setting<Boolean> smartCover = sgPlace.add(new BoolSetting.Builder()
        .name("smart-cover")
        .description("Places up to two damage-simulated obsidian cover blocks when they make an otherwise unsafe attack safe.")
        .defaultValue(true)
        .build()
    );

    // Face place

    private final Setting<Boolean> facePlace = sgFacePlace.add(new BoolSetting.Builder()
        .name("face-place")
        .description("Will face-place when target is below a certain health or armor durability threshold.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> facePlaceHealth = sgFacePlace.add(new DoubleSetting.Builder()
        .name("face-place-health")
        .description("The health the target has to be at to start face placing.")
        .defaultValue(8)
        .min(1)
        .sliderMin(1)
        .sliderMax(36)
        .visible(facePlace::get)
        .build()
    );

    private final Setting<Double> facePlaceDurability = sgFacePlace.add(new DoubleSetting.Builder()
        .name("face-place-durability")
        .description("The durability threshold percentage to be able to face-place.")
        .defaultValue(2)
        .min(1)
        .sliderMin(1)
        .sliderMax(100)
        .visible(facePlace::get)
        .build()
    );

    private final Setting<Boolean> facePlaceArmor = sgFacePlace.add(new BoolSetting.Builder()
        .name("face-place-missing-armor")
        .description("Automatically starts face placing when a target misses a piece of armor.")
        .defaultValue(false)
        .visible(facePlace::get)
        .build()
    );

    private final Setting<Keybind> forceFacePlace = sgFacePlace.add(new KeybindSetting.Builder()
        .name("force-face-place")
        .description("Starts face place when this button is pressed.")
        .defaultValue(Keybind.none())
        .build()
    );

    // Break

    private final Setting<Boolean> doBreak = sgBreak.add(new BoolSetting.Builder()
        .name("break")
        .description("If the CA should break crystals.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> breakDelay = sgBreak.add(new IntSetting.Builder()
        .name("break-delay")
        .description("The delay in ticks to wait to break a crystal after it's placed.")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> smartDelay = sgBreak.add(new BoolSetting.Builder()
        .name("smart-delay")
        .description("Only breaks crystals when the target can receive damage.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> breakRange = sgBreak.add(new DoubleSetting.Builder()
        .name("break-range")
        .description("Range in which to break crystals.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> breakWallsRange = sgBreak.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("Range in which to break crystals when behind blocks.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> onlyBreakOwn = sgBreak.add(new BoolSetting.Builder()
        .name("only-own")
        .description("Only breaks own crystals.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> breakAttempts = sgBreak.add(new IntSetting.Builder()
        .name("break-attempts")
        .description("How many times to hit a crystal before stopping to target it.")
        .defaultValue(2)
        .sliderMin(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Integer> ticksExisted = sgBreak.add(new IntSetting.Builder()
        .name("ticks-existed")
        .description("Amount of ticks a crystal needs to have lived for it to be attacked by CrystalAura.")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private final Setting<Integer> attackFrequency = sgBreak.add(new IntSetting.Builder()
        .name("attack-frequency")
        .description("Maximum hits to do per second.")
        .defaultValue(25)
        .min(1)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Boolean> fastBreak = sgBreak.add(new BoolSetting.Builder()
        .name("fast-break")
        .description("Ignores break delay and tries to break the crystal as soon as it's spawned in the world.")
        .defaultValue(true)
        .build()
    );

    // Pause

    public final Setting<PauseMode> pauseOnUse = sgPause.add(new EnumSetting.Builder<PauseMode>()
        .name("pause-on-use")
        .description("Which processes should be paused while using an item.")
        .defaultValue(PauseMode.Place)
        .build()
    );

    public final Setting<PauseMode> pauseOnMine = sgPause.add(new EnumSetting.Builder<PauseMode>()
        .name("pause-on-mine")
        .description("Which processes should be paused while mining a block.")
        .defaultValue(PauseMode.None)
        .build()
    );

    private final Setting<Boolean> pauseOnLag = sgPause.add(new BoolSetting.Builder()
        .name("pause-on-lag")
        .description("Whether to pause if the server is not responding.")
        .defaultValue(true)
        .build()
    );

    public final Setting<List<Module>> pauseModules = sgPause.add(new ModuleListSetting.Builder()
        .name("pause-modules")
        .description("Pauses while any of the selected modules are active.")
        .defaultValue(BedAura.class)
        .build()
    );

    public final Setting<Double> pauseHealth = sgPause.add(new DoubleSetting.Builder()
        .name("pause-health")
        .description("Pauses when you go below a certain health.")
        .defaultValue(5)
        .range(0, 36)
        .sliderRange(0, 36)
        .build()
    );

    // Render

    public final Setting<SwingMode> swingMode = sgRender.add(new EnumSetting.Builder<SwingMode>()
        .name("swing-mode")
        .description("How to swing when placing.")
        .defaultValue(SwingMode.Both)
        .build()
    );

    private final Setting<RenderMode> renderMode = sgRender.add(new EnumSetting.Builder<RenderMode>()
        .name("render-mode")
        .description("The mode to render in.")
        .defaultValue(RenderMode.Normal)
        .build()
    );

    private final Setting<Boolean> renderPlace = sgRender.add(new BoolSetting.Builder()
        .name("render-place")
        .description("Renders a block overlay over the block the crystals are being placed on.")
        .defaultValue(true)
        .visible(() -> renderMode.get() == RenderMode.Normal)
        .build()
    );

    private final Setting<Integer> placeRenderTime = sgRender.add(new IntSetting.Builder()
        .name("place-time")
        .description("How long to render placements.")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Normal && renderPlace.get())
        .build()
    );

    private final Setting<Boolean> renderBreak = sgRender.add(new BoolSetting.Builder()
        .name("render-break")
        .description("Renders a block overlay over the block the crystals are broken on.")
        .defaultValue(false)
        .visible(() -> renderMode.get() == RenderMode.Normal)
        .build()
    );

    private final Setting<Integer> breakRenderTime = sgRender.add(new IntSetting.Builder()
        .name("break-time")
        .description("How long to render breaking for.")
        .defaultValue(13)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Normal && renderBreak.get())
        .build()
    );

    private final Setting<Integer> smoothness = sgRender.add(new IntSetting.Builder()
        .name("smoothness")
        .description("How smoothly the render should move around.")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Smooth)
        .build()
    );

    private final Setting<Double> height = sgRender.add(new DoubleSetting.Builder()
        .name("height")
        .description("How tall the gradient should be.")
        .defaultValue(0.7)
        .min(0)
        .sliderMax(1)
        .visible(() -> renderMode.get() == RenderMode.Gradient)
        .build()
    );

    private final Setting<Integer> renderTime = sgRender.add(new IntSetting.Builder()
        .name("render-time")
        .description("How long to render placements.")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Smooth || renderMode.get() == RenderMode.Fading)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(() -> renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the block overlay.")
        .defaultValue(new SettingColor(255, 255, 255, 45))
        .visible(() -> shapeMode.get().sides() && renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the block overlay.")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> shapeMode.get().lines() && renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<Boolean> renderDamageText = sgRender.add(new BoolSetting.Builder()
        .name("damage")
        .description("Renders crystal damage text in the block overlay.")
        .defaultValue(true)
        .visible(() -> renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<SettingColor> damageColor = sgRender.add(new ColorSetting.Builder()
        .name("damage-color")
        .description("The color of the damage text.")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> renderMode.get() != RenderMode.None && renderDamageText.get())
        .build()
    );

    private final Setting<Double> damageTextScale = sgRender.add(new DoubleSetting.Builder()
        .name("damage-scale")
        .description("How big the damage text should be.")
        .defaultValue(1.25)
        .min(1)
        .sliderMax(4)
        .visible(() -> renderMode.get() != RenderMode.None && renderDamageText.get())
        .build()
    );

    // Fields

    private Item mainItem, offItem;

    private int breakTimer, placeTimer, switchTimer, ticksPassed;
    private final List<LivingEntity> targets = new ArrayList<>();

    private final Vec3 vec3d = new Vec3(0, 0, 0);
    private final Vec3 playerEyePos = new Vec3(0, 0, 0);
    private final Vector3d vec3 = new Vector3d();
    private final BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
    private final AABB box = new AABB(0, 0, 0, 0, 0, 0);

    private final Vec3 vec3dRayTraceEnd = new Vec3(0, 0, 0);
    private ClipContext clipContext;

    private final IntSet placedCrystals = new IntOpenHashSet();
    private boolean placing;
    private int placingTimer;
    public int kaTimer;
    private final BlockPos.MutableBlockPos placingCrystalBlockPos = new BlockPos.MutableBlockPos();

    private final IntSet removed = new IntOpenHashSet();
    private final Int2IntMap attemptedBreaks = new Int2IntOpenHashMap();
    private final Int2IntMap waitingToExplode = new Int2IntOpenHashMap();
    private int attacks;

    private PlacementPlan activePlan;
    private BlockPos pendingBlock;
    private BlockPos sendingBlock;
    private int pendingBlockSequence = -1;
    private int pendingBlockTicks;
    private boolean pendingBlockAcknowledged;
    private boolean pendingBlockSpeculative;
    private final Map<BlockPos, Integer> inhibitedBases = new HashMap<>();

    private double serverYaw;

    private LivingEntity bestTarget;
    private double bestTargetDamage;
    private int bestTargetTimer;

    private boolean didRotateThisTick;
    private boolean isLastRotationPos;
    private final Vec3 lastRotationPos = new Vec3(0, 0, 0);
    private double lastYaw, lastPitch;
    private int lastRotationTimer;

    private int placeRenderTimer, breakRenderTimer;
    private final BlockPos.MutableBlockPos placeRenderPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos breakRenderPos = new BlockPos.MutableBlockPos();
    private AABB renderBoxOne, renderBoxTwo;

    private double renderDamage;

    public CrystalAura() {
        super(Categories.Combat, "crystal-aura", "Automatically places and attacks crystals.");
    }

    @Override
    public void onActivate() {
        breakTimer = 0;
        placeTimer = 0;
        ticksPassed = 0;

        clipContext = new ClipContext(new Vec3(0, 0, 0), new Vec3(0, 0, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);

        placing = false;
        placingTimer = 0;
        kaTimer = 0;

        attacks = 0;

        clearPlan();
        inhibitedBases.clear();

        serverYaw = mc.player.getYRot();

        bestTargetDamage = 0;
        bestTargetTimer = 0;

        lastRotationTimer = getLastRotationStopDelay();

        placeRenderTimer = 0;
        breakRenderTimer = 0;
    }

    @Override
    public void onDeactivate() {
        targets.clear();

        placedCrystals.clear();

        attemptedBreaks.clear();
        waitingToExplode.clear();
        clearPlan();
        inhibitedBases.clear();

        removed.clear();

        bestTarget = null;
    }

    private int getLastRotationStopDelay() {
        return Math.max(10, placeDelay.get() / 2 + breakDelay.get() / 2 + 10);
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onPreTick(TickEvent.Pre event) {
        // Update last rotation
        didRotateThisTick = false;
        lastRotationTimer++;

        // Decrement placing timer
        if (placing) {
            if (placingTimer > 0) placingTimer--;
            else placing = false;
        }

        if (kaTimer > 0) kaTimer--;

        if (ticksPassed < 20) ticksPassed++;
        else {
            ticksPassed = 0;
            attacks = 0;
        }

        // Decrement best target timer
        if (bestTargetTimer > 0) bestTargetTimer--;
        bestTargetDamage = 0;

        // Decrement break, place and switch timers
        if (breakTimer > 0) breakTimer--;
        if (placeTimer > 0) placeTimer--;
        if (switchTimer > 0) switchTimer--;

        // Decrement render timers
        if (placeRenderTimer > 0) placeRenderTimer--;
        if (breakRenderTimer > 0) breakRenderTimer--;

        mainItem = mc.player.getMainHandItem().getItem();
        offItem = mc.player.getOffhandItem().getItem();

        // Update waiting to explode crystals and mark them as existing if reached threshold
        for (IntIterator it = waitingToExplode.keySet().iterator(); it.hasNext(); ) {
            int id = it.nextInt();
            int ticks = waitingToExplode.get(id);

            if (ticks >= confirmationTicks()) {
                it.remove();
                removed.remove(id);
            } else {
                waitingToExplode.put(id, ticks + 1);
            }
        }
        inhibitedBases.entrySet().removeIf(entry -> entry.getValue() <= mc.player.tickCount);

        // Set player eye pos
        ((IVec3) playerEyePos).monocle$set(mc.player.position().x, mc.player.position().y + mc.player.getEyeHeight(mc.player.getPose()), mc.player.position().z);

        // Find targets, break and place
        findTargets();

        if (!targets.isEmpty()) {
            if (!didRotateThisTick) doBreak();
            if (!didRotateThisTick) doPlace();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST - 666)
    private void onPreTickLast(TickEvent.Pre event) {
        // Rotate to last rotation
        if (rotate.get() && lastRotationTimer < getLastRotationStopDelay() && !didRotateThisTick) {
            Rotations.rotate(isLastRotationPos ? Rotations.getYaw(lastRotationPos) : lastYaw, isLastRotationPos ? Rotations.getPitch(lastRotationPos) : lastPitch, -100, null);
        }
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (!(event.entity instanceof EndCrystal)) return;

        if (placing && event.entity.blockPosition().equals(placingCrystalBlockPos)) {
            placing = false;
            placingTimer = 0;
            placedCrystals.add(event.entity.getId());
        }

        if (fastBreak.get() && !didRotateThisTick && attacks < attackFrequency.get()) {
            float damage = getBreakDamage(event.entity, true);
            if (damage > 0) doBreak(event.entity);
        }
    }

    @EventHandler
    private void onEntityRemoved(EntityRemovedEvent event) {
        if (event.entity instanceof EndCrystal) {
            placedCrystals.remove(event.entity.getId());
            removed.remove(event.entity.getId());
            waitingToExplode.remove(event.entity.getId());
        }
    }

    private void setRotation(boolean isPos, Vec3 pos, double yaw, double pitch) {
        didRotateThisTick = true;
        isLastRotationPos = isPos;

        if (isPos) ((IVec3) lastRotationPos).monocle$set(pos.x, pos.y, pos.z);
        else {
            lastYaw = yaw;
            lastPitch = pitch;
        }

        lastRotationTimer = 0;
    }

    // Break

    private void doBreak() {
        if (!doBreak.get() || breakTimer > 0 || switchTimer > 0 || attacks >= attackFrequency.get()) return;
        if (shouldPause(PauseMode.Break)) return;

        float bestDamage = 0;
        Entity crystal = null;

        // Find best crystal to break
        for (Entity entity : mc.level.entitiesForRendering()) {
            float damage = getBreakDamage(entity, true);

            if (damage > bestDamage) {
                bestDamage = damage;
                crystal = entity;
            }
        }

        // Break the crystal
        if (crystal != null) doBreak(crystal);
    }

    private float getBreakDamage(Entity entity, boolean checkCrystalAge) {
        if (!(entity instanceof EndCrystal)) return 0;

        // Check only break own
        if (onlyBreakOwn.get() && !placedCrystals.contains(entity.getId())) return 0;

        // Check if it should already be removed
        if (removed.contains(entity.getId())) return 0;

        // Check attempted breaks
        if (attemptedBreaks.get(entity.getId()) >= breakAttempts.get()) return 0;

        // Check crystal age
        if (checkCrystalAge && entity.tickCount < ticksExisted.get()) return 0;

        // Check range
        if (isOutOfRange(entity.position(), entity.blockPosition(), false)) return 0;

        // Check damage to self and anti suicide
        blockPos.set(entity.blockPosition()).move(0, -1, 0);
        float selfDamage = DamageUtils.crystalDamage(mc.player, entity.position(), predictMovement.get(), blockPos);
        if (!isSelfDamageSafe(selfDamage) || !isFriendDamageSafe(entity.position(), blockPos, List.of())) return 0;

        float damage = 0;
        for (LivingEntity target : targets) {
            if (smartDelay.get() && target.hurtTime > 0) continue;
            float candidate = DamageUtils.crystalDamage(target, entity.position(), predictMovement.get(), blockPos);
            if (candidate < minimumDamage(target)) continue;
            if (candidate > damage) {
                damage = candidate;
                bestTarget = target;
                bestTargetDamage = candidate;
                bestTargetTimer = 10;
            }
        }
        if (damage == 0) return 0;

        return damage;
    }

    private void doBreak(Entity crystal) {
        // Anti weakness
        if (antiWeakness.get()) {
            MobEffectInstance weakness = mc.player.getEffect(MobEffects.WEAKNESS);
            MobEffectInstance strength = mc.player.getEffect(MobEffects.STRENGTH);

            // Check for strength
            if (weakness != null && (strength == null || strength.getAmplifier() <= weakness.getAmplifier())) {
                // Check if the item in your hand is already valid
                if (!isValidWeaknessItem(mc.player.getMainHandItem(), crystal)) {
                    // Find valid item to break with
                    if (!InvUtils.swap(InvUtils.findInHotbar(stack -> isValidWeaknessItem(stack, crystal)).slot(), false))
                        return;

                    switchTimer = 1;
                    return;
                }
            }
        }

        // Rotate and attack
        boolean attacked = true;

        if (rotate.get()) {
            double yaw = Rotations.getYaw(crystal);
            double pitch = Rotations.getPitch(crystal, Target.Feet);

            if (doYawSteps(yaw, pitch)) {
                setRotation(true, crystal.position(), 0, 0);
                Rotations.rotate(yaw, pitch, 50, () -> attackCrystal(crystal));

                breakTimer = breakDelay.get();
            } else {
                attacked = false;
            }
        } else {
            attackCrystal(crystal);
            breakTimer = breakDelay.get();
        }

        if (attacked) {
            // Update state
            removed.add(crystal.getId());
            attemptedBreaks.put(crystal.getId(), attemptedBreaks.get(crystal.getId()) + 1);
            waitingToExplode.put(crystal.getId(), 0);

            // Break render
            breakRenderPos.set(crystal.blockPosition().below());
            breakRenderTimer = breakRenderTime.get();
        }
    }

    private boolean isValidWeaknessItem(ItemStack itemStack, Entity crystal) {
        return DamageUtils.getAttackDamage(mc.player, crystal, itemStack) > 0;
    }

    private void attackCrystal(Entity entity) {
        // Attack
        mc.player.connection.send(new ServerboundAttackPacket(entity.getId()));

        InteractionHand hand = InvUtils.findInHotbar(Items.END_CRYSTAL).getHand();
        if (hand == null) hand = InteractionHand.MAIN_HAND;

        if (swingMode.get().client()) mc.player.swing(hand);
        if (swingMode.get().packet()) mc.getConnection().send(new ServerboundSwingPacket(hand));

        attacks++;
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (event.packet instanceof ServerboundSetCarriedItemPacket) {
            switchTimer = switchDelay.get();
        }
        if (sendingBlock != null && event.packet instanceof ServerboundUseItemOnPacket packet) {
            pendingBlockSequence = packet.getSequence();
        }
    }

    // Place

    private void doPlace() {
        if (!doPlace.get() || placeTimer > 0) return;
        if (shouldPause(PauseMode.Place)) return;

        if (activePlan != null) {
            advancePlan();
            return;
        }
        if (placing) return;

        // Return if there are no crystals in hotbar or offhand
        if (!InvUtils.testInHotbar(Items.END_CRYSTAL)) return;

        // Return if there are no crystals in either hand and auto switch mode is none
        if (autoSwitch.get() != AutoSwitchMode.None) {
            if (noGapSwitch.get() && autoSwitch.get() == AutoSwitchMode.Normal && offItem != Items.END_CRYSTAL) {
                if (mainItem == Items.ENCHANTED_GOLDEN_APPLE
                    || offItem == Items.ENCHANTED_GOLDEN_APPLE
                    || mainItem == Items.GOLDEN_APPLE
                    || offItem == Items.GOLDEN_APPLE) return;
            }
            if (noBowSwitch.get() && (mainItem == Items.BOW || offItem == Items.BOW)) return;
        } else if (mainItem != Items.END_CRYSTAL && offItem != Items.END_CRYSTAL) return;

        // Check for multiplace
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (getBreakDamage(entity, false) > 0) return;
        }

        AtomicReference<PlacementPlan> best = new AtomicReference<>();
        boolean hasObsidian = InvUtils.findInHotbar(Items.OBSIDIAN).found();

        // Find best position to place the crystal on
        BlockIterator.register((int) Math.ceil(placeRange.get()), (int) Math.ceil(placeRange.get()), (bp, blockState) -> {
            boolean hasBlock = blockState.is(Blocks.BEDROCK) || blockState.is(Blocks.OBSIDIAN);
            boolean needsSupport = !hasBlock;
            if (needsSupport && (support.get() == SupportMode.Disabled || !hasObsidian || !blockState.canBeReplaced()
                || inhibitedBases.containsKey(bp) || !BlockUtils.canPlaceBlock(bp, true, Blocks.OBSIDIAN))) return;

            // Check if there is air on top
            blockPos.set(bp.getX(), bp.getY() + 1, bp.getZ());
            if (!mc.level.getBlockState(blockPos).isAir()) return;

            if (placement112.get()) {
                blockPos.move(0, 1, 0);
                if (!mc.level.getBlockState(blockPos).isAir()) return;
            }

            // Check range
            ((IVec3) vec3d).monocle$set(bp.getX() + 0.5, bp.getY() + 1, bp.getZ() + 0.5);
            blockPos.set(bp).move(0, 1, 0);
            if (isOutOfRange(vec3d, blockPos, true)) return;

            // Check if it can be placed
            double x = bp.getX();
            double y = bp.getY() + 1;
            double z = bp.getZ();
            ((IAABB) box).monocle$set(x, y, z, x + 1, y + (placement112.get() ? 1 : 2), z + 1);

            if (intersectsWithEntities(box)) return;

            PlacementPlan candidate = evaluatePlacement(bp.immutable(), needsSupport);
            if (candidate != null && betterPlan(candidate, best.get())) best.set(candidate);
        });

        BlockIterator.after(() -> {
            if (best.get() == null || activePlan != null) return;
            activePlan = best.get();
            advancePlan();
        });
    }

    private PlacementPlan evaluatePlacement(BlockPos base, boolean needsSupport) {
        Vec3 explosion = new Vec3(base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5);
        PlacementPlan best = null;

        for (LivingEntity target : targets) {
            if (smartDelay.get() && target.hurtTime > 0) continue;

            double targetDamage = DamageUtils.crystalDamage(target, explosion, predictMovement.get(), base);
            if (targetDamage < minimumDamage(target)) continue;

            double selfDamage = DamageUtils.crystalDamage(mc.player, explosion, predictMovement.get(), base);
            List<BlockPos> cover = List.of();

            if (!isSelfDamageSafe(selfDamage) || !isFriendDamageSafe(explosion, base, cover)) {
                CoverResult result = smartCover.get() && InvUtils.findInHotbar(Items.OBSIDIAN).found()
                    ? findCover(base, explosion, target) : null;
                if (result == null) continue;
                targetDamage = result.targetDamage();
                selfDamage = result.selfDamage();
                cover = result.blocks();
            }

            int placements = (needsSupport ? 1 : 0) + cover.size();
            boolean lethal = targetDamage >= EntityUtils.getTotalHealth(target);
            double score = planScore(targetDamage, selfDamage, placements, lethal);
            PlacementPlan candidate = new PlacementPlan(base, target, targetDamage, selfDamage, needsSupport, cover, score);
            if (betterPlan(candidate, best)) best = candidate;
        }

        if (best != null && best.targetDamage() > bestTargetDamage) {
            bestTarget = best.target();
            bestTargetDamage = best.targetDamage();
            bestTargetTimer = 10;
        }
        return best;
    }

    private CoverResult findCover(BlockPos base, Vec3 explosion, LivingEntity target) {
        CoverResult best = null;
        for (List<BlockPos> layout : coverLayouts(mc.player.blockPosition(), explosion)) {
            boolean placeable = true;
            for (BlockPos pos : layout) {
                if (pos.equals(base) || pos.equals(base.above()) || !BlockUtils.canPlaceBlock(pos, true, Blocks.OBSIDIAN)
                    || isOutOfRange(Vec3.atCenterOf(pos), pos, true)) {
                    placeable = false;
                    break;
                }
            }
            if (!placeable) continue;

            double selfDamage = DamageUtils.crystalDamage(mc.player, explosion, predictMovement.get(), base, layout);
            if (!isSelfDamageSafe(selfDamage) || !isFriendDamageSafe(explosion, base, layout)) continue;

            double targetDamage = DamageUtils.crystalDamage(target, explosion, predictMovement.get(), base, layout);
            if (targetDamage < minimumDamage(target)) continue;

            CoverResult candidate = new CoverResult(List.copyOf(layout), targetDamage, selfDamage);
            if (best == null || planScore(targetDamage, selfDamage, layout.size(), targetDamage >= EntityUtils.getTotalHealth(target))
                > planScore(best.targetDamage(), best.selfDamage(), best.blocks().size(), best.targetDamage() >= EntityUtils.getTotalHealth(target))) best = candidate;
        }
        return best;
    }

    static List<List<BlockPos>> coverLayouts(BlockPos feet, Vec3 explosion) {
        int x = Double.compare(explosion.x, feet.getX() + 0.5);
        int z = Double.compare(explosion.z, feet.getZ() + 0.5);
        if (x == 0 && z == 0) return List.of();

        boolean xFirst = Math.abs(explosion.x - feet.getX() - 0.5) >= Math.abs(explosion.z - feet.getZ() - 0.5);
        BlockPos primary = feet.offset(xFirst ? x : 0, 0, xFirst ? 0 : z);
        List<List<BlockPos>> layouts = new ArrayList<>();
        layouts.add(List.of(primary));
        layouts.add(List.of(primary, primary.above()));

        if (x != 0 && z != 0) {
            BlockPos secondary = feet.offset(xFirst ? 0 : x, 0, xFirst ? z : 0);
            layouts.add(List.of(secondary));
            layouts.add(List.of(secondary, secondary.above()));
            layouts.add(List.of(primary, secondary));
        }
        return layouts;
    }

    private boolean advancePlan() {
        if (activePlan == null) return false;

        if (pendingBlock != null) {
            boolean present = mc.level.getBlockState(pendingBlock).is(Blocks.OBSIDIAN) || mc.level.getBlockState(pendingBlock).is(Blocks.BEDROCK);
            if (present && (pendingBlockAcknowledged || pendingBlockSpeculative)) {
                clearPendingBlock();
                placeTimer = Math.max(placeTimer, supportDelay.get());
            } else if (++pendingBlockTicks >= confirmationTicks()) {
                inhibitAndClearPlan();
            }
            return true;
        }

        if (!activePlan.target().isAlive() || activePlan.target().distanceToSqr(mc.player) > targetRange.get() * targetRange.get()) {
            clearPlan();
            return false;
        }

        for (BlockPos cover : activePlan.cover()) {
            if (!mc.level.getBlockState(cover).is(Blocks.OBSIDIAN) && !mc.level.getBlockState(cover).is(Blocks.BEDROCK)) {
                if (!BlockUtils.canPlaceBlock(cover, true, Blocks.OBSIDIAN)) {
                    inhibitAndClearPlan();
                    return true;
                }
                return placePlanBlock(cover, false);
            }
        }

        if (activePlan.needsSupport() && !mc.level.getBlockState(activePlan.base()).is(Blocks.OBSIDIAN)
            && !mc.level.getBlockState(activePlan.base()).is(Blocks.BEDROCK)) {
            if (!BlockUtils.canPlaceBlock(activePlan.base(), true, Blocks.OBSIDIAN)) {
                inhibitAndClearPlan();
                return true;
            }
            return placePlanBlock(activePlan.base(), support.get() == SupportMode.Speculative);
        }

        placePlannedCrystal(activePlan);
        return true;
    }

    private boolean placePlanBlock(BlockPos pos, boolean speculative) {
        FindItemResult obsidian = InvUtils.findInHotbar(Items.OBSIDIAN);
        if (!obsidian.found()) {
            inhibitAndClearPlan();
            return true;
        }

        pendingBlock = pos.immutable();
        pendingBlockSequence = Integer.MAX_VALUE;
        pendingBlockTicks = 0;
        pendingBlockAcknowledged = false;
        pendingBlockSpeculative = speculative;
        Runnable place = () -> {
            if (activePlan == null || !pos.equals(pendingBlock)) return;
            sendingBlock = pos;
            try {
                if (!BlockUtils.place(pos, obsidian, false, 50, swingMode.get().client(), true, true)) inhibitAndClearPlan();
            } finally {
                sendingBlock = null;
            }
        };

        if (rotate.get()) {
            Vec3 center = Vec3.atCenterOf(pos);
            double yaw = Rotations.getYaw(center);
            double pitch = Rotations.getPitch(center);
            if (yawStepMode.get() != YawStepMode.Break && !doYawSteps(yaw, pitch)) {
                clearPendingBlock();
                return true;
            }
            setRotation(true, center, 0, 0);
            Rotations.rotate(yaw, pitch, 50, place);
        } else place.run();
        return true;
    }

    private void placePlannedCrystal(PlacementPlan plan) {
        Vec3 explosion = new Vec3(plan.base().getX() + 0.5, plan.base().getY() + 1, plan.base().getZ() + 0.5);
        double targetDamage = DamageUtils.crystalDamage(plan.target(), explosion, predictMovement.get(), plan.base());
        double selfDamage = DamageUtils.crystalDamage(mc.player, explosion, predictMovement.get(), plan.base());
        if (targetDamage < minimumDamage(plan.target()) || !isSelfDamageSafe(selfDamage)
            || !isFriendDamageSafe(explosion, plan.base(), List.of()) || isOutOfRange(explosion, plan.base().above(), true)) {
            clearPlan();
            return;
        }

        double x = plan.base().getX();
        double y = plan.base().getY() + 1;
        double z = plan.base().getZ();
        ((IAABB) box).monocle$set(x, y, z, x + 1, y + (placement112.get() ? 1 : 2), z + 1);
        if (!mc.level.getBlockState(plan.base().above()).isAir()
            || placement112.get() && !mc.level.getBlockState(plan.base().above(2)).isAir()
            || intersectsWithEntities(box)) {
            clearPlan();
            return;
        }

        BlockHitResult result = getPlaceInfo(plan.base());
        ((IVec3) vec3d).monocle$set(
            result.getBlockPos().getX() + 0.5 + result.getDirection().getUnitVec3i().getX() * 0.5,
            result.getBlockPos().getY() + 0.5 + result.getDirection().getUnitVec3i().getY() * 0.5,
            result.getBlockPos().getZ() + 0.5 + result.getDirection().getUnitVec3i().getZ() * 0.5
        );

        Runnable place = () -> placeCrystal(result, targetDamage);
        if (rotate.get()) {
            double yaw = Rotations.getYaw(vec3d);
            double pitch = Rotations.getPitch(vec3d);
            if (yawStepMode.get() != YawStepMode.Break && !doYawSteps(yaw, pitch)) return;
            setRotation(true, vec3d, 0, 0);
            activePlan = null;
            Rotations.rotate(yaw, pitch, 50, place);
        } else {
            activePlan = null;
            place.run();
        }
        placeTimer += placeDelay.get();
    }

    private BlockHitResult getPlaceInfo(BlockPos blockPos) {
        ((IVec3) vec3d).monocle$set(mc.player.getX(), mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()), mc.player.getZ());

        for (Direction side : Direction.values()) {
            ((IVec3) vec3dRayTraceEnd).monocle$set(
                blockPos.getX() + 0.5 + side.getUnitVec3i().getX() * 0.5,
                blockPos.getY() + 0.5 + side.getUnitVec3i().getY() * 0.5,
                blockPos.getZ() + 0.5 + side.getUnitVec3i().getZ() * 0.5
            );

            ((IClipContext) clipContext).monocle$set(vec3d, vec3dRayTraceEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
            BlockHitResult result = mc.level.clip(clipContext);

            if (result != null && result.getType() == HitResult.Type.BLOCK && result.getBlockPos().equals(blockPos)) {
                return result;
            }
        }

        Direction side = blockPos.getY() > vec3d.y ? Direction.DOWN : Direction.UP;
        return new BlockHitResult(vec3d, side, blockPos, false);
    }

    private void placeCrystal(BlockHitResult result, double damage) {
        FindItemResult item = InvUtils.findInHotbar(Items.END_CRYSTAL);
        if (!item.found()) return;

        int prevSlot = mc.player.getInventory().getSelectedSlot();

        if (autoSwitch.get() != AutoSwitchMode.None && !item.isOffhand()) InvUtils.swap(item.slot(), false);

        InteractionHand hand = item.getHand();
        if (hand == null) return;

        mc.gameMode.startPrediction(mc.level, sequence -> new ServerboundUseItemOnPacket(hand, result, sequence));

        if (swingMode.get().client()) mc.player.swing(hand);
        if (swingMode.get().packet()) mc.getConnection().send(new ServerboundSwingPacket(hand));

        placing = true;
        placingTimer = confirmationTicks();
        kaTimer = 8;
        placingCrystalBlockPos.set(result.getBlockPos()).move(0, 1, 0);

        placeRenderPos.set(result.getBlockPos());
        renderDamage = damage;

        if (renderMode.get() == RenderMode.Normal) {
            placeRenderTimer = placeRenderTime.get();
        } else {
            placeRenderTimer = renderTime.get();
            if (renderMode.get() == RenderMode.Fading) {
                RenderUtils.renderTickingBlock(
                    placeRenderPos, sideColor.get(),
                    lineColor.get(), shapeMode.get(),
                    0, renderTime.get(), true,
                    false
                );
            }
        }

        // Switch back
        if (autoSwitch.get() == AutoSwitchMode.Silent) InvUtils.swap(prevSlot, false);
    }

    public void onServerBlockAck(int sequence) {
        if (!isActive() || pendingBlock == null || pendingBlockSequence == Integer.MAX_VALUE) return;
        if (sequence >= pendingBlockSequence) pendingBlockAcknowledged = true;
    }

    public void onServerBlockUpdate(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (!isActive() || pendingBlock == null || !pendingBlock.equals(pos)) return;
        if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK)) pendingBlockAcknowledged = true;
    }

    private boolean isSelfDamageSafe(double damage) {
        double health = EntityUtils.getTotalHealth(mc.player);
        return damage <= maxDamage.get() && health - damage >= healthReserve.get() && (!antiSuicide.get() || damage < health);
    }

    private boolean isFriendDamageSafe(Vec3 explosion, BlockPos base, List<BlockPos> cover) {
        if (!protectFriends.get()) return true;
        for (Player friend : mc.level.players()) {
            if (friend == mc.player || Friends.get().shouldAttack(friend)) continue;
            double damage = DamageUtils.crystalDamage(friend, explosion, predictMovement.get(), base, cover);
            if (damage > maxFriendDamage.get() || damage >= EntityUtils.getTotalHealth(friend)) return false;
        }
        return true;
    }

    private double minimumDamage(LivingEntity target) {
        return shouldFacePlace(target) ? Math.min(minDamage.get(), 1.5) : minDamage.get();
    }

    static double planScore(double targetDamage, double selfDamage, int blockPlacements, boolean lethal) {
        return targetDamage - selfDamage * 0.35 - blockPlacements * 0.65 + (lethal ? 20 : 0);
    }

    private static boolean betterPlan(PlacementPlan candidate, PlacementPlan current) {
        if (current == null) return true;
        if (Math.abs(candidate.targetDamage() - current.targetDamage()) <= 1) {
            if (candidate.selfDamage() != current.selfDamage()) return candidate.selfDamage() < current.selfDamage();
            if (candidate.blockPlacements() != current.blockPlacements()) return candidate.blockPlacements() < current.blockPlacements();
        }
        return candidate.score() > current.score();
    }

    static int confirmationTicksForPing(int ping) {
        return Mth.clamp((int) Math.ceil(Math.max(0, ping) / 50.0) + 3, 4, 12);
    }

    private int confirmationTicks() {
        return confirmationTicksForPing(PlayerUtils.getPing());
    }

    private void inhibitAndClearPlan() {
        if (activePlan != null) inhibitedBases.put(activePlan.base(), mc.player.tickCount + confirmationTicks() * 2);
        clearPlan();
    }

    private void clearPlan() {
        activePlan = null;
        clearPendingBlock();
    }

    private void clearPendingBlock() {
        pendingBlock = null;
        sendingBlock = null;
        pendingBlockSequence = -1;
        pendingBlockTicks = 0;
        pendingBlockAcknowledged = false;
        pendingBlockSpeculative = false;
    }

    // Yaw steps

    @EventHandler
    private void onPacketSent(PacketEvent.Sent event) {
        if (event.packet instanceof ServerboundMovePlayerPacket packet) {
            serverYaw = packet.getYRot((float) serverYaw);
        }
    }

    public boolean doYawSteps(double targetYaw, double targetPitch) {
        targetYaw = Mth.wrapDegrees(targetYaw) + 180;
        double serverYaw = Mth.wrapDegrees(this.serverYaw) + 180;

        if (distanceBetweenAngles(serverYaw, targetYaw) <= yawSteps.get()) return true;

        double delta = Math.abs(targetYaw - serverYaw);
        double yaw = this.serverYaw;

        if (serverYaw < targetYaw) {
            if (delta < 180) yaw += yawSteps.get();
            else yaw -= yawSteps.get();
        } else {
            if (delta < 180) yaw -= yawSteps.get();
            else yaw += yawSteps.get();
        }

        setRotation(false, null, yaw, targetPitch);
        Rotations.rotate(yaw, targetPitch, -100, null); // Priority -100 so it sends the packet as the last one, im pretty sure it doesn't matte but idc
        return false;
    }

    private static double distanceBetweenAngles(double alpha, double beta) {
        double phi = Math.abs(beta - alpha) % 360;
        return phi > 180 ? 360 - phi : phi;
    }

    // Face place

    private boolean shouldFacePlace(LivingEntity target) {
        if (!facePlace.get()) return false;
        if (forceFacePlace.get().isPressed() || EntityUtils.getTotalHealth(target) <= facePlaceHealth.get()) return true;

        for (EquipmentSlot slot : EquipmentSlotGroup.ARMOR) {
            ItemStack itemStack = target.getItemBySlot(slot);
            if (itemStack == null || itemStack.isEmpty()) {
                if (facePlaceArmor.get()) return true;
            } else if (itemStack.isDamageableItem()
                && (double) (itemStack.getMaxDamage() - itemStack.getDamageValue()) / itemStack.getMaxDamage() * 100 <= facePlaceDurability.get()) return true;
        }
        return false;
    }

    // Others

    private boolean shouldPause(PauseMode process) {
        if (mc.player.isUsingItem() || mc.options.keyUse.isDown()) {
            if (pauseOnUse.get().matches(process)) return true;
        }

        if (pauseOnLag.get() && TickRate.INSTANCE.getTimeSinceLastTick() >= 1.0f) return true;
        for (Module module : pauseModules.get()) if (module.isActive()) return true;
        if (pauseOnMine.get().matches(process) && mc.gameMode.isDestroying()) return true;
        return (EntityUtils.getTotalHealth(mc.player) <= pauseHealth.get());
    }

    private boolean isOutOfRange(Vec3 vec3d, BlockPos blockPos, boolean place) {
        ((IClipContext) clipContext).monocle$set(playerEyePos, vec3d, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);

        BlockHitResult result = mc.level.clip(clipContext);

        if (result == null || !result.getBlockPos().equals(blockPos)) // Is behind wall
            return !PlayerUtils.isWithin(vec3d, (place ? placeWallsRange : breakWallsRange).get());
        return !PlayerUtils.isWithin(vec3d, (place ? placeRange : breakRange).get());
    }

    @Override
    public String getInfoString() {
        return bestTarget != null && bestTargetTimer > 0 ? EntityUtils.getName(bestTarget) : null;
    }

    private void findTargets() {
        targets.clear();

        // Living Entities
        for (Entity entity : mc.level.entitiesForRendering()) {
            // Ignore non-living
            if (!(entity instanceof LivingEntity livingEntity)) continue;

            // Player
            if (livingEntity instanceof Player player) {
                if (player.getAbilities().instabuild || livingEntity == mc.player) continue;
                if (!player.isAlive() || !Friends.get().shouldAttack(player)) continue;

                if (ignoreNakeds.get()) {
                    if (player.getOffhandItem().isEmpty()
                        && player.getMainHandItem().isEmpty()
                        && player.getItemBySlot(EquipmentSlot.FEET).isEmpty()
                        && player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
                        && player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                        && player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                    ) continue;
                }
            }

            // Animals, water animals, monsters, bats, misc
            if (!(entities.get().contains(livingEntity.getType()))) continue;

            // Close enough to damage
            if (livingEntity.distanceToSqr(mc.player) > targetRange.get() * targetRange.get()) continue;

            targets.add(livingEntity);
        }
    }

    private boolean intersectsWithEntities(AABB box) {
        return EntityUtils.intersectsWithEntity(box, entity -> !entity.isSpectator() && !removed.contains(entity.getId()));
    }

    // Rendering

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (renderMode.get() == RenderMode.None) return;

        switch (renderMode.get()) {
            case Normal -> {
                if (renderPlace.get() && placeRenderTimer > 0) {
                    event.renderer.box(placeRenderPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
                }
                if (renderBreak.get() && breakRenderTimer > 0) {
                    event.renderer.box(breakRenderPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
                }
            }

            case Smooth -> {
                if (placeRenderTimer <= 0) return;

                if (renderBoxOne == null) renderBoxOne = new AABB(placeRenderPos);
                if (renderBoxTwo == null) renderBoxTwo = new AABB(placeRenderPos);
                else ((IAABB) renderBoxTwo).monocle$set(placeRenderPos);

                double offsetX = (renderBoxTwo.minX - renderBoxOne.minX) / smoothness.get();
                double offsetY = (renderBoxTwo.minY - renderBoxOne.minY) / smoothness.get();
                double offsetZ = (renderBoxTwo.minZ - renderBoxOne.minZ) / smoothness.get();

                ((IAABB) renderBoxOne).monocle$set(
                    renderBoxOne.minX + offsetX,
                    renderBoxOne.minY + offsetY,
                    renderBoxOne.minZ + offsetZ,
                    renderBoxOne.maxX + offsetX,
                    renderBoxOne.maxY + offsetY,
                    renderBoxOne.maxZ + offsetZ
                );

                event.renderer.box(renderBoxOne, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }

            case Gradient -> {
                if (placeRenderTimer <= 0) return;

                Color bottom = new Color(0, 0, 0, 0);

                int x = placeRenderPos.getX();
                int y = placeRenderPos.getY() + 1;
                int z = placeRenderPos.getZ();

                if (shapeMode.get().sides()) {
                    event.renderer.quadHorizontal(x, y, z, x + 1, z + 1, sideColor.get());
                    event.renderer.gradientQuadVertical(x, y, z, x + 1, y - height.get(), z, bottom, sideColor.get());
                    event.renderer.gradientQuadVertical(x, y, z, x, y - height.get(), z + 1, bottom, sideColor.get());
                    event.renderer.gradientQuadVertical(x + 1, y, z, x + 1, y - height.get(), z + 1, bottom, sideColor.get());
                    event.renderer.gradientQuadVertical(x, y, z + 1, x + 1, y - height.get(), z + 1, bottom, sideColor.get());
                }

                if (shapeMode.get().lines()) {
                    event.renderer.line(x, y, z, x + 1, y, z, lineColor.get());
                    event.renderer.line(x, y, z, x, y, z + 1, lineColor.get());
                    event.renderer.line(x + 1, y, z, x + 1, y, z + 1, lineColor.get());
                    event.renderer.line(x, y, z + 1, x + 1, y, z + 1, lineColor.get());

                    event.renderer.line(x, y, z, x, y - height.get(), z, lineColor.get(), bottom);
                    event.renderer.line(x + 1, y, z, x + 1, y - height.get(), z, lineColor.get(), bottom);
                    event.renderer.line(x, y, z + 1, x, y - height.get(), z + 1, lineColor.get(), bottom);
                    event.renderer.line(x + 1, y, z + 1, x + 1, y - height.get(), z + 1, lineColor.get(), bottom);
                }
            }
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (renderMode.get() == RenderMode.None || !renderDamageText.get()) return;
        if (placeRenderTimer <= 0 && breakRenderTimer <= 0) return;

        if (renderMode.get() == RenderMode.Smooth) {
            if (renderBoxOne == null) return;
            vec3.set(renderBoxOne.minX + 0.5, renderBoxOne.minY + 0.5, renderBoxOne.minZ + 0.5);
        } else vec3.set(placeRenderPos.getX() + 0.5, placeRenderPos.getY() + 0.5, placeRenderPos.getZ() + 0.5);

        if (NametagUtils.to2D(vec3, damageTextScale.get())) {
            NametagUtils.begin(vec3);
            TextRenderer.get().begin(event.graphics, 1, false, true);

            String text = String.format("%.1f", renderDamage);
            double w = TextRenderer.get().getWidth(text) / 2;
            TextRenderer.get().render(text, -w, 0, damageColor.get(), true);

            TextRenderer.get().end();
            NametagUtils.end();
        }
    }

    private record PlacementPlan(BlockPos base, LivingEntity target, double targetDamage, double selfDamage,
                                 boolean needsSupport, List<BlockPos> cover, double score) {
        int blockPlacements() {
            return (needsSupport ? 1 : 0) + cover.size();
        }
    }

    private record CoverResult(List<BlockPos> blocks, double targetDamage, double selfDamage) {}

    public enum YawStepMode {
        Break,
        All,
    }

    public enum AutoSwitchMode {
        Normal,
        Silent,
        None
    }

    public enum SupportMode {
        Disabled,
        Confirmed,
        Speculative
    }

    public enum PauseMode {
        Both,
        Place,
        Break,
        None;

        public boolean matches(PauseMode process) {
            return this == process || this == PauseMode.Both;
        }
    }

    public enum SwingMode {
        Both,
        Packet,
        Client,
        None;

        public boolean packet() {
            return this == Packet || this == Both;
        }

        public boolean client() {
            return this == Client || this == Both;
        }
    }

    public enum RenderMode {
        Normal,
        Smooth,
        Fading,
        Gradient,
        None
    }
}
