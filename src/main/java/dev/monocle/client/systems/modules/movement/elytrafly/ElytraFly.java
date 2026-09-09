/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.modules.movement.elytrafly;

import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.entity.player.PlayerMoveEvent;
import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.mixin.BlockBehaviourAccessor;
import dev.monocle.client.mixininterface.IVec3;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.movement.elytrafly.modes.Bounce;
import dev.monocle.client.systems.modules.movement.elytrafly.modes.Packet;
import dev.monocle.client.systems.modules.movement.elytrafly.modes.Pitch40;
import dev.monocle.client.systems.modules.movement.elytrafly.modes.Vanilla;
import dev.monocle.client.systems.modules.player.ChestSwap;
import dev.monocle.client.systems.modules.player.Rotation;
import dev.monocle.client.systems.modules.render.Freecam;
import dev.monocle.client.utils.world.PrinterFlight;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class ElytraFly extends Module {
    private final SettingGroup sgFlight = settings.createGroup("Flight");
    private final SettingGroup sgAcceleration = settings.createGroup("Acceleration");
    private final SettingGroup sgTakeoff = settings.createGroup("Takeoff & Landing");
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgAutopilot = settings.createGroup("Autopilot", false);
    private final SettingGroup sgInventory = settings.createGroup("Inventory", false);
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced", false);

    // General

    public final Setting<ElytraFlightModes> flightMode = sgFlight.add(new EnumSetting.Builder<ElytraFlightModes>()
        .name("mode")
        .description("Vanilla: direct steering. Packet: packet-based flight. Pitch40: altitude cycling. Bounce: repeated gliding. Printer Helper requires Vanilla.")
        .defaultValue(ElytraFlightModes.Vanilla)
        .onModuleActivated(flightModesSetting -> onModeChanged(flightModesSetting.get()))
        .onChanged(this::onModeChanged)
        .build()
    );

    public final Setting<Boolean> autoTakeOff = sgTakeoff.add(new BoolSetting.Builder()
        .name("auto-take-off")
        .description("Automatically takes off when you hold jump without needing to double jump.")
        .defaultValue(false)
        .visible(() -> flightMode.get() != ElytraFlightModes.Pitch40 && flightMode.get() != ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Double> fallMultiplier = sgFlight.add(new DoubleSetting.Builder()
        .name("fall-multiplier")
        .description("Multiplies natural downward velocity. Zero removes natural descent; this does not change the sneak descent control.")
        .defaultValue(0.01)
        .min(0)
        .visible(() -> flightMode.get() != ElytraFlightModes.Pitch40 && flightMode.get() != ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Double> horizontalSpeed = sgFlight.add(new DoubleSetting.Builder()
        .name("horizontal-speed")
        .description("Cruising speed and acceleration target. Vanilla uses blocks per tick (1 = 20 blocks/sec at 20 TPS); server acceptance may reduce actual speed.")
        .defaultValue(1)
        .min(0)
        .visible(() -> flightMode.get() != ElytraFlightModes.Pitch40 && flightMode.get() != ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Double> verticalSpeed = sgFlight.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("Jump / sneak vertical speed multiplier. Vanilla adds 0.5 blocks per tick per unit, in addition to natural descent.")
        .defaultValue(1)
        .min(0)
        .visible(() -> flightMode.get() != ElytraFlightModes.Pitch40 && flightMode.get() != ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Boolean> acceleration = sgAcceleration.add(new BoolSetting.Builder()
        .name("acceleration")
        .description("Ramps toward Horizontal Speed instead of applying it immediately. Server corrections and unloaded-chunk stops reset the ramp.")
        .defaultValue(false)
        .visible(() -> flightMode.get() != ElytraFlightModes.Pitch40 && flightMode.get() != ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Double> accelerationStep = sgAcceleration.add(new DoubleSetting.Builder()
        .name("acceleration-step")
        .description("Ramp increment: each movement update adds Acceleration Start + (this value x 0.1), capped at Horizontal Speed.")
        .min(0.1)
        .max(5)
        .defaultValue(1)
        .visible(() -> flightMode.get() != ElytraFlightModes.Pitch40 && acceleration.get() && flightMode.get() != ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Double> accelerationMin = sgAcceleration.add(new DoubleSetting.Builder()
        .name("acceleration-start")
        .description("Legacy ramp offset: added on EVERY acceleration update, not just at takeoff. Kept unchanged so existing flight tuning behaves identically.")
        .min(0.1)
        .defaultValue(0)
        .visible(() -> flightMode.get() != ElytraFlightModes.Pitch40 && acceleration.get() && flightMode.get() != ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Boolean> stopInWater = sgSafety.add(new BoolSetting.Builder()
        .name("stop-in-water")
        .description("Stops flying in water.")
        .defaultValue(true)
        .visible(() -> flightMode.get() != ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Boolean> dontGoIntoUnloadedChunks = sgSafety.add(new BoolSetting.Builder()
        .name("no-unloaded-chunks")
        .description("Stops you from going into unloaded chunks.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> autoHover = sgTakeoff.add(new BoolSetting.Builder()
        .name("auto-hover")
        .description("Attempts to hover near the ground while holding sneak. May adjust pitch; not an automatic landing.")
        .defaultValue(false)
        .visible(() -> flightMode.get() != ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Boolean> noCrash = sgSafety.add(new BoolSetting.Builder()
        .name("no-crash")
        .description("Stops horizontal movement when the forward collision probe finds a wall. Not full pathfinding or a guarantee against crashes.")
        .defaultValue(false)
        .visible(() -> flightMode.get() != ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Integer> crashLookAhead = sgSafety.add(new IntSetting.Builder()
        .name("crash-look-ahead")
        .description("Wall-probe distance in blocks. Increase for faster flight; this is a forward ray, not a full-body clearance check.")
        .defaultValue(5)
        .range(1, 15)
        .sliderMin(1)
        .visible(() -> noCrash.get() && flightMode.get() != ElytraFlightModes.Bounce)
        .build()
    );

    private final Setting<Boolean> instaDrop = sgTakeoff.add(new BoolSetting.Builder()
        .name("insta-drop")
        .description("Stops gliding when you disable ElytraFly. You will fall: disable only where landing is safe.")
        .defaultValue(false)
        .visible(() -> flightMode.get() != ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Double> pitch40lowerBounds = sgAdvanced.add(new DoubleSetting.Builder()
        .name("pitch40-lower-bounds")
        .description(
            "The bottom height boundary for pitch40. You must be at least 40 blocks above this boundary when starting the module.\n" +
                "After descending below this boundary you will start pitching upwards."
        )
        .defaultValue(180)
        .min(-128)
        .sliderMax(360)
        .visible(() -> flightMode.get() == ElytraFlightModes.Pitch40)
        .build()
    );

    public final Setting<Double> pitch40upperBounds = sgAdvanced.add(new DoubleSetting.Builder()
        .name("pitch40-upper-bounds")
        .description(
            "The upper height boundary for pitch40. You must be above this boundary when starting the module.\n" +
                "When ascending above this boundary, if you are not already, you will start pitching downwards."
        )
        .defaultValue(220)
        .min(-128)
        .sliderMax(360)
        .visible(() -> flightMode.get() == ElytraFlightModes.Pitch40)
        .build()
    );

    public final Setting<Double> pitch40rotationSpeedUp = sgAdvanced.add(new DoubleSetting.Builder()
        .name("pitch40-rotate-speed-up")
        .description("The speed for pitch rotation upwards (degrees per tick).")
        .defaultValue(5.45)
        .min(1)
        .sliderMax(20)
        .visible(() -> flightMode.get() == ElytraFlightModes.Pitch40)
        .build()
    );

    public final Setting<Double> pitch40rotationSpeedDown = sgAdvanced.add(new DoubleSetting.Builder()
        .name("pitch40-rotate-speed-down")
        .description("The speed for pitch rotation downwards (degrees per tick).")
        .defaultValue(0.90)
        .min(0.5)
        .sliderMax(2)
        .visible(() -> flightMode.get() == ElytraFlightModes.Pitch40)
        .build()
    );

    public final Setting<Boolean> autoJump = sgTakeoff.add(new BoolSetting.Builder()
        .name("auto-jump")
        .description("Automatically jumps for you.")
        .defaultValue(true)
        .visible(() -> flightMode.get() == ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Rotation.LockMode> yawLockMode = sgAdvanced.add(new EnumSetting.Builder<Rotation.LockMode>()
        .name("yaw-lock")
        .description("Whether to enable yaw lock or not")
        .defaultValue(Rotation.LockMode.Smart)
        .visible(() -> flightMode.get() == ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Double> yaw = sgAdvanced.add(new DoubleSetting.Builder()
        .name("yaw")
        .description("The yaw angle to look at when using simple rotation lock in bounce mode.")
        .defaultValue(0)
        .range(0, 360)
        .sliderRange(0, 360)
        .visible(() -> flightMode.get() == ElytraFlightModes.Bounce && yawLockMode.get() == Rotation.LockMode.Simple)
        .build()
    );

    public final Setting<Boolean> lockPitch = sgAdvanced.add(new BoolSetting.Builder()
        .name("pitch-lock")
        .description("Whether to lock your pitch angle.")
        .defaultValue(true)
        .visible(() -> flightMode.get() == ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Double> pitch = sgAdvanced.add(new DoubleSetting.Builder()
        .name("pitch")
        .description("The pitch angle to look at when using the bounce mode.")
        .defaultValue(85)
        .range(0, 90)
        .sliderRange(0, 90)
        .visible(() -> flightMode.get() == ElytraFlightModes.Bounce && lockPitch.get())
        .build()
    );

    public final Setting<Boolean> restart = sgSafety.add(new BoolSetting.Builder()
        .name("restart")
        .description("Bounce only: restarts gliding after a server correction. Other modes already reset acceleration on correction.")
        .defaultValue(true)
        .visible(() -> flightMode.get() == ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Integer> restartDelay = sgSafety.add(new IntSetting.Builder()
        .name("restart-delay")
        .description("How many ticks to wait before restarting the elytra again after rubberbanding.")
        .defaultValue(7)
        .min(0)
        .sliderRange(0, 20)
        .visible(() -> flightMode.get() == ElytraFlightModes.Bounce && restart.get())
        .build()
    );

    public final Setting<Boolean> sprint = sgAdvanced.add(new BoolSetting.Builder()
        .name("sprint-constantly")
        .description("Sprints all the time. If turned off, it will only sprint when the player is touching the ground.")
        .defaultValue(true)
        .visible(() -> flightMode.get() == ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Boolean> manualTakeoff = sgTakeoff.add(new BoolSetting.Builder()
        .name("manual-takeoff")
        .description("Does not automatically take off.")
        .defaultValue(false)
        .visible(() -> flightMode.get() == ElytraFlightModes.Bounce)
        .build()
    );

    // Inventory

    public final Setting<Boolean> replace = sgSafety.add(new BoolSetting.Builder()
        .name("elytra-replace")
        .description("Attempts to equip a spare elytra from inventory at the durability threshold. Requires a healthier spare; does not repair it.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> replaceDurability = sgSafety.add(new IntSetting.Builder()
        .name("replace-durability")
        .description("Remaining durability points (not percent) at which to attempt a spare-elytra swap.")
        .defaultValue(2)
        .sliderRange(1, 500)
        .visible(replace::get)
        .build()
    );

    public final Setting<ChestSwapMode> chestSwap = sgTakeoff.add(new EnumSetting.Builder<ChestSwapMode>()
        .name("chest-swap")
        .description("Always: swap on enable and disable. WaitForGround: equip on enable, restore armor after a flight lands even while ElytraFly stays enabled. Never: leave equipment alone.")
        .defaultValue(ChestSwapMode.Never)
        .build()
    );

    public final Setting<Boolean> autoReplenish = sgInventory.add(new BoolSetting.Builder()
        .name("replenish-fireworks")
        .description("Moves fireworks into a selected hotbar slot.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> replenishSlot = sgInventory.add(new IntSetting.Builder()
        .name("replenish-slot")
        .description("Hotbar destination for rockets (1–9). The existing inventory move can exchange the item already in this slot.")
        .defaultValue(9)
        .range(1, 9)
        .sliderRange(1, 9)
        .visible(autoReplenish::get)
        .build()
    );

    // Autopilot

    public final Setting<Boolean> autoPilot = sgAutopilot.add(new BoolSetting.Builder()
        .name("auto-pilot")
        .description("Holds forward above Minimum Height. This is straight flight, not route planning; Printer Helper temporarily owns steering.")
        .defaultValue(false)
        .visible(() -> flightMode.get() != ElytraFlightModes.Pitch40 && flightMode.get() != ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Boolean> useFireworks = sgAutopilot.add(new BoolSetting.Builder()
        .name("use-fireworks")
        .description("Automatically uses available hotbar/offhand rockets at the configured interval, even if Auto Pilot is off.")
        .defaultValue(false)
        .visible(() -> flightMode.get() != ElytraFlightModes.Pitch40 && flightMode.get() != ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Double> autoPilotFireworkDelay = sgAutopilot.add(new DoubleSetting.Builder()
        .name("firework-delay")
        .description("The delay in seconds in between using fireworks if \"Use Fireworks\" is enabled.")
        .min(1)
        .defaultValue(8)
        .sliderMax(20)
        .visible(() -> useFireworks.get() && flightMode.get() != ElytraFlightModes.Pitch40 && flightMode.get() != ElytraFlightModes.Bounce)
        .build()
    );

    public final Setting<Double> autoPilotMinimumHeight = sgAutopilot.add(new DoubleSetting.Builder()
        .name("minimum-height")
        .description("World Y above which Auto Pilot holds forward. Does not climb to this height automatically.")
        .defaultValue(120)
        .min(-128)
        .sliderMax(260)
        .visible(() -> autoPilot.get() && flightMode.get() != ElytraFlightModes.Pitch40 && flightMode.get() != ElytraFlightModes.Bounce)
        .build()
    );

    private ElytraFlightMode currentMode = new Vanilla();
    private LocalPlayer landingPlayer;
    private ClientLevel landingWorld;
    private int landingTicks;
    private boolean flew;
    private Vec3 autopilotVelocity;
    private ClientLevel autopilotWorld;
    private LocalPlayer autopilotPlayer;
    private int autopilotTick;

    public ElytraFly() {
        super(Categories.Movement, "elytra-fly", "Gives you more control over your elytra.");
    }

    @Override
    public Module fromTag(CompoundTag tag) {
        CompoundTag migrated = tag.copy();
        if (tag.get("settings") instanceof CompoundTag saved) {
            CompoundTag settingsTag = saved.copy();
            ListTag groups = new ListTag();
            for (SettingGroup group : settings) {
                groups.add(regroupSettings(saved, group.name, group.sectionExpanded, name -> group.get(name) != null));
            }
            settingsTag.put("groups", groups);
            migrated.put("settings", settingsTag);
        }
        return super.fromTag(migrated);
    }

    /** Route old group entries by their unchanged setting keys; do not mutate saved profiles. */
    public static CompoundTag regroupSettings(CompoundTag saved, String destination, boolean expanded,
                                              java.util.function.Predicate<String> contains) {
        CompoundTag group = new CompoundTag();
        group.putString("name", destination);
        group.putBoolean("sectionExpanded", expanded);
        ListTag values = new ListTag();
        for (Tag entry : saved.getListOrEmpty("groups")) {
            if (!(entry instanceof CompoundTag source)) continue;
            if (source.getStringOr("name", "").equals(destination)) {
                group.putBoolean("sectionExpanded", source.getBooleanOr("sectionExpanded", expanded));
            }
            for (Tag value : source.getListOrEmpty("settings")) {
                if (value instanceof CompoundTag setting && contains.test(setting.getStringOr("name", ""))) {
                    values.add(setting.copy());
                }
            }
        }
        group.put("settings", values);
        return group;
    }

    /** A short-lived world-space request; zero also owns grounded restocking without taking off. */
    public boolean requestAutopilot(Vec3 velocity) {
        if (!mc.isSameThread()) throw new IllegalStateException("Request flight from the client thread.");
        if (velocity == null || !Double.isFinite(velocity.x) || !Double.isFinite(velocity.y) || !Double.isFinite(velocity.z)
            || velocity.lengthSqr() > 1 + 1e-9) throw new IllegalArgumentException("Autopilot velocity must be finite and at most one block/tick.");
        if (!isActive() || flightMode.get() != ElytraFlightModes.Vanilla || mc.player == null || mc.level == null
            || !mc.player.isAlive() || (mc.player.isInWater() || mc.player.isInLava()) && velocity.lengthSqr() != 0) {
            clearAutopilot();
            return false;
        }
        autopilotVelocity = new Vec3(velocity.x, velocity.y, velocity.z);
        autopilotWorld = mc.level;
        autopilotPlayer = mc.player;
        autopilotTick = mc.player.tickCount;
        return true;
    }

    /** Does not toggle ElytraFly or trigger chest-swap/instant-drop deactivation behavior. */
    public void clearAutopilot() {
        autopilotVelocity = null;
        autopilotWorld = null;
        autopilotPlayer = null;
    }

    public static boolean freshAutopilotRequest(int requestedTick, int currentTick) {
        int elapsed = currentTick - requestedTick;
        return elapsed >= 0 && elapsed <= 1;
    }

    public boolean hasAutopilotRequest() {
        if (autopilotVelocity != null && isActive() && flightMode.get() == ElytraFlightModes.Vanilla
            && mc.level == autopilotWorld && mc.player == autopilotPlayer && mc.player != null && mc.player.isAlive()
            && freshAutopilotRequest(autopilotTick, mc.player.tickCount)) return true;
        clearAutopilot();
        return false;
    }

    private boolean clearAutopilotBody(AABB box) {
        if (box.minY < mc.level.getMinY() || box.maxY > mc.level.getMaxY() + 1
            || !PrinterFlight.loaded(box, mc.level.getChunkSource()::hasChunk) || !mc.level.noCollision(mc.player, box)) return false;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
            BlockPos.containing(Math.nextDown(box.maxX), Math.nextDown(box.maxY), Math.nextDown(box.maxZ)))) {
            if (!mc.level.getWorldBorder().isWithinBounds(pos)) return false;
            BlockState state = mc.level.getBlockState(pos);
            if (!state.getFluidState().isEmpty() || state.is(net.minecraft.world.level.block.Blocks.FIRE)
                || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE) || state.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)) return false;
        }
        return true;
    }

    @Override
    public void onActivate() {
        clearAutopilot();
        Modules.get().get(ChestSwap.class).cancelRequest();
        landingPlayer = mc.player;
        landingWorld = mc.level;
        flew = false;
        landingTicks = 0;
        currentMode.onActivate();
        if ((chestSwap.get() == ChestSwapMode.Always || chestSwap.get() == ChestSwapMode.WaitForGround)
            && mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA && isActive()) {
            Modules.get().get(ChestSwap.class).requestEquip(true, false);
        }
    }

    @Override
    public void onDeactivate() {
        clearAutopilot();
        if (autoPilot.get()) mc.options.keyUp.setDown(false);

        if (mc.player != null && mc.player.isAlive() && mc.level == landingWorld
            && mc.player == landingPlayer && chestSwap.get() != ChestSwapMode.Never) {
            Modules.get().get(ChestSwap.class).requestEquip(false, chestSwap.get() == ChestSwapMode.WaitForGround);
        }
        flew = false;

        if (mc.player.isFallFlying() && instaDrop.get()) {
            enableInstaDropListener();
        }

        currentMode.onDeactivate();
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        boolean requested = hasAutopilotRequest();
        if (!(mc.player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER))) return;

        if (!requested) currentMode.autoTakeoff();

        if (requested) {
            if (mc.player.isFallFlying()) {
                Vec3 movement = autopilotVelocity;
                if (mc.player.isInWater() || mc.player.isInLava() || !PrinterFlight.segmentClear(mc.player.position(),
                    mc.player.position().add(movement), mc.player.getBbWidth(), mc.player.getBbHeight(), this::clearAutopilotBody)) movement = Vec3.ZERO;
                ((IVec3) event.movement).monocle$set(movement.x, movement.y, movement.z);
            }
            // Navigation owns movement, not view rotation, vanilla keys, fireworks or ground-following hover.
            return;
        }

        if (mc.player.isFallFlying()) {

            if (flightMode.get() != ElytraFlightModes.Bounce) {
                currentMode.velX = 0;
                currentMode.velY = event.movement.y;
                currentMode.velZ = 0;
                currentMode.forward = Vec3.directionFromRotation(0, mc.player.getYRot()).scale(0.1);
                currentMode.right = Vec3.directionFromRotation(0, mc.player.getYRot() + 90).scale(0.1);

                // Handle stopInWater
                if (mc.player.isInWater() && stopInWater.get()) {
                    mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                    return;
                }

                currentMode.handleFallMultiplier();
                currentMode.handleAutopilot();

                currentMode.handleAcceleration();
                currentMode.handleHorizontalSpeed(event);
                currentMode.handleVerticalSpeed(event);
            }

            int chunkX = (int) ((mc.player.getX() + currentMode.velX) / 16);
            int chunkZ = (int) ((mc.player.getZ() + currentMode.velZ) / 16);
            if (dontGoIntoUnloadedChunks.get()) {
                if (mc.level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    if (flightMode.get() != ElytraFlightModes.Bounce)
                        ((IVec3) event.movement).monocle$set(currentMode.velX, currentMode.velY, currentMode.velZ);
                } else {
                    currentMode.zeroAcceleration();
                    ((IVec3) event.movement).monocle$set(0, currentMode.velY, 0);
                }
            } else if (flightMode.get() != ElytraFlightModes.Bounce)
                ((IVec3) event.movement).monocle$set(currentMode.velX, currentMode.velY, currentMode.velZ);

            if (flightMode.get() != ElytraFlightModes.Bounce) currentMode.onPlayerMove();
        } else {
            if (currentMode.lastForwardPressed && flightMode.get() != ElytraFlightModes.Bounce) {
                mc.options.keyUp.setDown(false);
                currentMode.lastForwardPressed = false;
            }
        }

        if (noCrash.get() && mc.player.isFallFlying() && flightMode.get() != ElytraFlightModes.Bounce) {
            Vec3 lookAheadPos = mc.player.position().add(mc.player.getDeltaMovement().normalize().scale(crashLookAhead.get()));
            ClipContext clipContext = new ClipContext(mc.player.position(), new Vec3(lookAheadPos.x(), mc.player.getY(), lookAheadPos.z()), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
            BlockHitResult hitResult = mc.level.clip(clipContext);
            if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
                ((IVec3) event.movement).monocle$set(0, currentMode.velY, 0);
            }
        }

        if (autoHover.get() && mc.player.input.keyPresses.shift() && !Modules.get().get(Freecam.class).isActive() && mc.player.isFallFlying() && flightMode.get() != ElytraFlightModes.Bounce) {
            BlockState underState = mc.level.getBlockState(mc.player.blockPosition().below());
            Block under = underState.getBlock();
            BlockState under2State = mc.level.getBlockState(mc.player.blockPosition().below().below());
            Block under2 = under2State.getBlock();

            final boolean underCollidable = ((BlockBehaviourAccessor) under).monocle$isHasCollision() || !underState.getFluidState().isEmpty();
            final boolean under2Collidable = ((BlockBehaviourAccessor) under2).monocle$isHasCollision() || !under2State.getFluidState().isEmpty();

            if (!underCollidable && under2Collidable) {
                ((IVec3) event.movement).monocle$set(event.movement.x, -0.1f, event.movement.z);

                mc.player.setXRot(Mth.clamp(mc.player.getXRot(0), -50.f, 20.f)); // clamp between -50 and 20 (>= 30 will pop you off, but lag makes that threshold lower)
            }

            if (underCollidable) {
                ((IVec3) event.movement).monocle$set(event.movement.x, -0.03f, event.movement.z);

                mc.player.setXRot(Mth.clamp(mc.player.getXRot(0), -50.f, 20.f));

                if (mc.player.position().y <= mc.player.blockPosition().below().getY() + 1.34f) {
                    ((IVec3) event.movement).monocle$set(event.movement.x, 0, event.movement.z);
                    mc.player.setShiftKeyDown(false);
                }
            }
        }
    }

    public boolean canPacketEfly() {
        return isActive() && flightMode.get() == ElytraFlightModes.Packet && mc.player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER) && !mc.player.onGround();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        currentMode.onTick();
        if (mc.player != landingPlayer || mc.level != landingWorld || mc.player == null || !mc.player.isAlive()) {
            flew = false;
            landingTicks = 0;
            return;
        }
        if (chestSwap.get() != ChestSwapMode.WaitForGround || hasAutopilotRequest()) {
            flew = false;
            landingTicks = 0;
            return;
        }
        if (mc.player.isFallFlying()) flew = true;
        landingTicks = flew && mc.player.onGround() && !mc.player.isFallFlying() ? landingTicks + 1 : 0;
        if (landingTicks >= 2) {
            Modules.get().get(ChestSwap.class).requestEquip(false, true);
            flew = false;
            landingTicks = 0;
        }
    }

    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        currentMode.onPreTick();
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        currentMode.onPacketSend(event);
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundPlayerPositionPacket) {
            clearAutopilot();
            currentMode.zeroAcceleration();
        }

        currentMode.onPacketReceive(event);
    }

    private void onModeChanged(ElytraFlightModes mode) {
        clearAutopilot();
        switch (mode) {
            case Vanilla -> currentMode = new Vanilla();
            case Packet -> currentMode = new Packet();
            case Pitch40 -> {
                currentMode = new Pitch40();
                autoPilot.set(false); // Pitch 40 is an autopilot of its own
            }
            case Bounce -> currentMode = new Bounce();
        }
    }

    //Drop
    private class StaticInstaDropListener {
        @EventHandler
        private void onInstadropTick(TickEvent.Post event) {
            if (hasAutopilotRequest()) return;
            if (mc.player != null && mc.player.isFallFlying()) {
                mc.player.setDeltaMovement(0, 0, 0);
                mc.player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, mc.player.horizontalCollision));
            } else {
                disableInstaDropListener();
            }
        }
    }

    private final StaticInstaDropListener staticInstadropListener = new StaticInstaDropListener();

    protected void enableInstaDropListener() {
        MonocleClient.EVENT_BUS.subscribe(staticInstadropListener);
    }

    protected void disableInstaDropListener() {
        MonocleClient.EVENT_BUS.unsubscribe(staticInstadropListener);
    }

    @Override
    public String getInfoString() {
        return currentMode.getHudString();
    }

    public enum ChestSwapMode {
        Always,
        Never,
        WaitForGround
    }

    public enum AutoPilotMode {
        Vanilla,
        Pitch40
    }
}
