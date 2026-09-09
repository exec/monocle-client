package dev.monocle.client.systems.modules.world;

import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.entity.player.PlayerMoveEvent;
import dev.monocle.client.events.packets.InventoryEvent;
import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.events.render.Render3DEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.gui.widgets.WLabel;
import dev.monocle.client.gui.widgets.containers.WVerticalList;
import dev.monocle.client.gui.widgets.pressable.WButton;
import dev.monocle.client.mixininterface.IVec3;
import dev.monocle.client.modintegration.PrinterIntegration;
import dev.monocle.client.pathing.PathManagers;
import dev.monocle.client.renderer.ShapeMode;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.player.AutoGap;
import dev.monocle.client.systems.modules.movement.elytrafly.ElytraFly;
import dev.monocle.client.systems.modules.movement.elytrafly.ElytraFlightModes;
import dev.monocle.client.systems.modules.player.AutoEat;
import dev.monocle.client.systems.modules.render.Freecam;
import dev.monocle.client.utils.render.color.Color;
import dev.monocle.client.utils.world.PrinterFlight;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Monocle navigates and restocks; the separately installed Sakura Printer owns placement. */
public final class PrinterHelper extends Module {
    private final SettingGroup sgFlight = settings.createGroup("Flight");
    private final SettingGroup sgSupplies = settings.createGroup("Supplies");
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final Setting<Double> speed = sgFlight.add(new DoubleSetting.Builder().name("flight-speed")
        .description("Travel speed in blocks per tick; slows automatically near a printing position.")
        .defaultValue(.3).range(.05, 1).sliderRange(.05, .8).build());
    private final Setting<Boolean> restockEnabled = sgSupplies.add(new BoolSetting.Builder().name("restock-shulkers")
        .description("Land outside the build and refill from carried shulkers; always recover our boxes.").defaultValue(true).build());
    private final Setting<Boolean> searchEchest = sgSupplies.add(new BoolSetting.Builder().name("search-ender-chests")
        .description("Use a nearby accessible ender chest first, otherwise place our own away from the build.")
        .defaultValue(false).visible(restockEnabled::get).build());
    private final Setting<Integer> maxShulkers = sgSupplies.add(new IntSetting.Builder().name("max-shulkers-per-visit")
        .description("Maximum useful shulkers to take from an ender chest, subject to recovery space.")
        .defaultValue(2).range(1, 8).sliderRange(1, 8).visible(() -> restockEnabled.get() && searchEchest.get()).build());
    private final Setting<Integer> supplyRadius = sgSupplies.add(new IntSetting.Builder().name("supply-search-radius")
        .description("Loaded-world radius for accessible chests and landing sites.").defaultValue(64).range(16, 128)
        .sliderRange(16, 128).visible(restockEnabled::get).build());
    private final Setting<Integer> supplyClearance = sgSupplies.add(new IntSetting.Builder().name("restock-clearance")
        .description("Keep restocking sites this many blocks outside the full schematic bounds.")
        .defaultValue(8).range(2, 32).sliderRange(2, 16).visible(restockEnabled::get).build());
    private final Setting<Boolean> supplyRotate = sgSupplies.add(new BoolSetting.Builder().name("rotate-for-supplies")
        .description("Rotate for container placement, opening and recovery. Printing uses Sakura's rotation setting.")
        .defaultValue(true).visible(restockEnabled::get).build());
    private final Setting<Double> minHealth = sgSafety.add(new DoubleSetting.Builder().name("minimum-health")
        .description("Stop printing and give movement control back below this health plus absorption.")
        .defaultValue(12).range(1, 36).sliderRange(1, 20).build());
    private final Setting<Boolean> renderRoute = sgSafety.add(new BoolSetting.Builder().name("show-escape-route")
        .description("Draw the reserved exit in pink and the current target in mint.").defaultValue(true).build());

    private enum Phase { Scan, Select, Travel, Print, Restock, Complete }
    private Phase phase = Phase.Scan, afterTravel = Phase.Select;
    private boolean session, paused, printing, retreating;
    private String status = "Enable with one Litematica placement and Sakura Printer installed.";
    private ClientLevel world;
    private List<AABB> bounds = List.of();
    private AABB build;
    private PrinterSafety.Scan scan;
    private PriorityQueue<BlockPos> candidates;
    private Comparator<BlockPos> order;
    private List<BlockPos> work = List.of();
    private final Map<BlockPos, Integer> deferred = new HashMap<>();
    private long scanned, scanMissing, scanUnknown, scanUnsupported, lastMissing, lastUnknown, lastUnsupported;
    private BlockPos unknownPosition, firstUnknown, target;
    private List<Vec3> route = List.of(), escape = List.of(), travelExit = List.of();
    private List<AABB> protectedExit = List.of();
    private int routeIndex, printSince, moveSince, idleSince, launchSince = -1, launchAttempts;
    private int pendingSequence = -1, pendingSince, correctionWait, settleSince, cleanPasses;
    private boolean corrected;
    private boolean escapeChanged;
    private Vec3 lastMove, groundVelocity = Vec3.ZERO;
    private double retreatDepth = Double.POSITIVE_INFINITY;
    private PrinterRestock restock;
    private WLabel statusLabel;
    private static final Color EXIT = new Color(255, 90, 190, 180), TARGET = new Color(80, 220, 185, 210);

    public PrinterHelper() {
        super(Categories.World, "printer-helper", "Inside-out flight and supplies for Sakura Litematica Printer, with a reserved way out.");
    }

    @Override public void onActivate() {
        if (mc.player == null || mc.level == null) return;
        reset();
        String problem = PrinterIntegration.availabilityError();
        if (problem == null) problem = flightError();
        if (problem == null) problem = conflict();
        if (problem == null) problem = PrinterIntegration.begin();
        if (problem != null) { error("%s", problem); disable(); return; }
        session = true;
        world = mc.level;
        bounds = PrinterIntegration.placementBounds();
        try {
            build = PrinterSafety.enclosing(PrinterIntegration.fullPlacementBounds());
            startScan();
        } catch (IllegalArgumentException invalid) {
            error("Cannot start Printer Helper: %s", invalid.getMessage());
            disable(); return;
        }
        if (!fly().isActive()) { fly().enable(); info("Auto-toggled ElytraFly for Printer Helper; it stays on when Helper stops."); }
        fly().requestAutopilot(Vec3.ZERO);
        info("Printer Helper started. Keeps an escape corridor open; restocking uses carried shulkers%s.", searchEchest.get() ? " and optional ender chests" : " only");
    }

    @Override public void onDeactivate() {
        if (restock != null) {
            String warning = restock.cancel();
            if (warning != null && !warning.isBlank() && mc.player != null) warning("%s", warning);
        }
        PrinterIntegration.close();
        if (Modules.get().get(ElytraFly.class) != null) fly().clearAutopilot();
        reset();
        status = "Stopped. ElytraFly settings and existing containers were left intact.";
    }

    private void reset() {
        session = paused = printing = retreating = corrected = false;
        world = null; bounds = List.of(); build = null; scan = null; work = List.of(); route = escape = travelExit = List.of();
        protectedExit = List.of(); candidates = null; restock = null; target = null; deferred.clear();
        scanned = scanMissing = scanUnknown = scanUnsupported = lastMissing = lastUnknown = lastUnsupported = 0;
        pendingSequence = launchSince = -1; cleanPasses = launchAttempts = correctionWait = settleSince = 0;
        retreatDepth = Double.POSITIVE_INFINITY; phase = Phase.Scan;
        escapeChanged = false; groundVelocity = Vec3.ZERO;
    }

    private ElytraFly fly() { return Modules.get().get(ElytraFly.class); }
    private double width() { return mc.player.getBbWidth() + .12; }
    private double height() { return Math.max(.7, mc.player.getBbHeight()); }
    private int tick() { return mc.player.tickCount; }

    private String flightError() {
        if (fly().flightMode.get() != ElytraFlightModes.Vanilla) return "Set ElytraFly's flight mode to Vanilla first.";
        ItemStack glider = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!glider.has(DataComponents.GLIDER)) return "Equip an elytra before starting Printer Helper.";
        if (glider.isDamageableItem() && glider.getMaxDamage() - glider.getDamageValue() <= 10) return "Repair or replace your elytra first (10 durability or less).";
        return null;
    }

    private String conflict() {
        if (Modules.get().get(HighwayBuilder.class).hasJob()) return "Stop Highway Builder before starting Printer Helper.";
        if (Modules.get().isActive(SchematicSelector.class)) return "Disable Schematic Selector so its wand does not consume Printer interactions.";
        if (Modules.get().isActive(Freecam.class) || PathManagers.get().isPathing()) return "Stop Freecam or the active pathing job before using Printer Helper.";
        return null;
    }

    /** Inventory Manager must not rearrange either Printer's selected materials or its supply menu. */
    public boolean controlsInventory() { return isActive() && session && (!paused || restock != null); }

    @EventHandler(priority = EventPriority.HIGH)
    private void onTick(TickEvent.Pre event) {
        try { tickJob(); }
        catch (RuntimeException failure) {
            MonocleClient.LOG.error("Printer Helper stopped after a failed operation", failure);
            pause("An operation failed (" + failure.getClass().getSimpleName() + "). Check the log and any placed supplies before restarting Helper.");
        }
        finally { if (statusLabel != null) statusLabel.set(status.length() > 110 ? status.substring(0, 107) + "... (see chat)" : status); }
    }

    private void tickJob() {
        if (!session || mc.player == null || mc.level != world) return;
        groundVelocity = Vec3.ZERO;
        if (paused) {
            fly().clearAutopilot(); // Emergency/manual control must not depend on an unresponsive server ACK.
            return;
        }
        fly().requestAutopilot(Vec3.ZERO);
        String problem = PrinterIntegration.sessionError();
        if (problem == null) problem = flightError();
        if (problem == null) problem = conflict();
        if (problem != null) { pause(problem); return; }
        if (!fly().isActive()) { pause("ElytraFly was disabled. Enable Vanilla ElytraFly, then Resume."); return; }
        if (mc.player.getHealth() + mc.player.getAbsorptionAmount() < minHealth.get()) { pause("Low health. Take control and get safe, then Resume."); return; }
        if (mc.player.isInWater() || mc.player.isInLava() || mc.player.isPassenger()) { pause("Flight interrupted by fluid or riding. Move to a safe flight position, then Resume."); return; }
        if (corrected) {
            corrected = false; correctionWait = 10; route = List.of();
            setPrinting(false);
            PrinterIntegration.suspend();
            if (restock != null) restock.pause();
            status = "Server moved the player; rechecking the route.";
        }
        if (correctionWait > 0) {
            setPrinting(false);
            if (--correctionWait == 0) {
                if (restock != null) { restock.resume(); phase = Phase.Restock; }
                else phase = Phase.Select;
            }
            return;
        }
        if (mc.player.isUsingItem() || Modules.get().get(AutoEat.class).eating || Modules.get().get(AutoGap.class).isEating()) {
            setPrinting(false); PrinterIntegration.suspend(); status = "Waiting for eating/item use."; return;
        }
        if (mc.gui.screen() != null && (restock == null || !restock.ownsMenu())) {
            setPrinting(false); PrinterIntegration.suspend(); status = "Waiting for the screen to close."; return;
        }
        if (pendingSequence >= 0 && tick() - pendingSince > 600) { pause("Server has not acknowledged placement for 30 seconds. Resume after confirmation, or recover owned supplies before restarting Helper."); return; }
        if (phase != Phase.Print) setPrinting(false);
        if (!printing && !PrinterIntegration.isIdle()) { status = "Finishing the current Printer action before moving."; return; }
        if (phase != Phase.Print && pendingSequence >= 0) { status = "Waiting for server placement confirmation before moving or changing the exit."; return; }
        if (escapeChanged) {
            escapeChanged = false;
            if (!escape.isEmpty() && !validEscape(escape)) { pause("A server update blocked the reserved exit. Printer stopped; restore a safe way out before resuming."); return; }
        }
        if (phase != Phase.Restock && phase != Phase.Complete && scan != null) scanTick();
        if (paused) return;
        switch (phase) {
            case Scan -> status = "Scanning schematic: " + scanned + " positions checked.";
            case Select -> chooseWork();
            case Travel -> travel();
            case Print -> print();
            case Restock -> restockTick();
            case Complete -> setPrinting(false);
        }
    }

    private void startScan() {
        Vec3 origin = mc.player.position();
        order = Comparator.<BlockPos>comparingDouble(pos -> -PrinterSafety.depth(Vec3.atCenterOf(pos), build))
            .thenComparingDouble(pos -> origin.distanceToSqr(Vec3.atCenterOf(pos)));
        candidates = new PriorityQueue<>(order.reversed());
        scan = new PrinterSafety.Scan(bounds);
        scanned = scanMissing = scanUnknown = scanUnsupported = 0; unknownPosition = null;
        phase = Phase.Scan;
    }

    private void scanTick() {
        // ponytail: bounded streaming scan plus 512 candidates; successive passes cover larger builds without a world-sized queue.
        for (int budget = 4096; budget > 0 && scan.hasNext(); budget--) {
            BlockPos pos = scan.next(); scanned++;
            if (!PrinterIntegration.isInScope(pos)) continue;
            BlockState desired = PrinterIntegration.desiredState(pos);
            if (desired == null) { scanUnknown++; if (unknownPosition == null) unknownPosition = pos; continue; }
            if (desired.isAir() || PrinterIntegration.matches(pos)) continue;
            scanMissing++;
            if (!PrinterIntegration.printingInAir() && world.getBlockState(pos).canBeReplaced() && !hasSupport(pos)) {
                scanUnsupported++;
                continue;
            }
            if (candidates.size() < 512) candidates.add(pos);
            else if (order.compare(pos, candidates.peek()) < 0) { candidates.remove(); candidates.add(pos); }
        }
        if (scan.hasNext()) return;
        scan = null;
        lastMissing = scanMissing; lastUnknown = scanUnknown; lastUnsupported = scanUnsupported; firstUnknown = unknownPosition;
        work = candidates.stream().distinct().sorted(order).toList(); candidates = null;
        phase = Phase.Select; idleSince = tick();
    }

    private void chooseWork() {
        if (work.isEmpty()) {
            if (lastUnknown > 0) { pause("Schematic/world chunks are not loaded near " + firstUnknown.toShortString() + ". Load them, then Resume; unknown blocks are not counted as complete."); return; }
            if (lastMissing == 0) {
                if (pendingSequence >= 0 || tick() - settleSince < 40) { status = "Waiting for placement confirmation before final verification."; return; }
                if (++cleanPasses < 2) { startScan(); return; }
                phase = Phase.Complete;
                status = "Complete: all loaded non-air schematic blocks match. No excavation was performed.";
                info("%s", status);
                return;
            }
            if (lastUnsupported >= lastMissing) {
                pause("Remaining blocks have no loaded supporting neighbor. Add support or review Sakura's Print In Air setting, then Resume; they are not complete."); return;
            }
            startScan(); return;
        }
        if (!refreshEscape()) { pause("No verified way out. Printer is stopped; move to a safe position and Resume."); return; }
        if (!ensureFlying()) return;
        if (!reserveExit()) return;
        BlockPos materialTarget = null;
        boolean remaining = false;
        for (BlockPos pos : work) {
            if (PrinterIntegration.matches(pos)) continue;
            remaining = true;
            if (deferred.getOrDefault(pos, 0) > tick() && !(pos.equals(target) && PrinterIntegration.canPrint(pos))) continue;
            if (!hasMaterials(pos)) { if (materialTarget == null) materialTarget = pos; continue; }
            target = pos;
            if (PrinterIntegration.canPrint(pos)) { phase = Phase.Print; printSince = tick(); setPrinting(true); return; }
            Vec3 from = mc.player.position();
            List<Vec3> path;
            if (from.distanceTo(Vec3.atCenterOf(pos)) > 28) {
                Vec3 intermediate = from.add(Vec3.atCenterOf(pos).subtract(from).normalize().scale(24));
                path = PrinterFlight.route(from, intermediate, width(), height(), this::clear);
            } else {
                double reach = PrinterIntegration.printingReach() - .15;
                if (!Double.isFinite(reach) || reach <= 1 || reach > PrinterFlight.MAX_DISTANCE) {
                    pause("Set Sakura Printer reach above 1.15 and at most 32 blocks before resuming."); return;
                }
                path = PrinterFlight.routeToPlacement(from, pos, width(), height(), mc.player.getEyeHeight(), reach, this::clear,
                    point -> point.distanceToSqr(from) > .6 && (!retreating || PrinterSafety.depth(point, build) <= retreatDepth + .1));
            }
            if (!path.isEmpty() && (!retreating || PrinterSafety.depth(path.getLast(), build) <= retreatDepth + .1)
                && path.getLast().distanceToSqr(from) > .05) {
                defer(pos);
                beginTravel(path, Phase.Select); return;
            }
            defer(pos);
            return; // At most one bounded path search per tick.
        }
        if (!remaining) { cleanPasses = 0; startScan(); return; }
        if (materialTarget != null) { beginRestock(); return; }
        if (tick() - idleSince < 40) { status = "Rechecking nearby placements and supports."; return; }
        if (retreat()) return;
        pause("Remaining blocks need materials, support, a different placement angle, or a route beyond the local flight limit. Nothing was skipped. Check Sakura settings, then Resume.");
    }

    private void defer(BlockPos pos) {
        if (deferred.size() > 1024) deferred.clear();
        deferred.put(pos.immutable(), tick() + 100);
    }

    private boolean hasSupport(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            if (!world.hasChunkAt(neighbor)) continue;
            BlockState state = world.getBlockState(neighbor);
            if (!state.canBeReplaced() && state.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    private boolean hasMaterials(BlockPos pos) {
        for (ItemStack required : PrinterIntegration.requiredItems(pos)) {
            int count = 0;
            for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).is(required.getItem())) count += mc.player.getInventory().getItem(i).getCount();
            if (mc.player.getOffhandItem().is(required.getItem())) count += mc.player.getOffhandItem().getCount();
            if (count < required.getCount()) return false;
        }
        return true;
    }

    private void beginRestock() {
        if (!restockEnabled.get()) { pause("Missing schematic materials. Add supplies or enable shulker restocking, then Resume."); return; }
        Map<Item, Integer> desired = new LinkedHashMap<>();
        for (BlockPos pos : work) if (!PrinterIntegration.matches(pos)) {
            for (ItemStack stack : PrinterIntegration.requiredItems(pos)) {
                if (!stack.isEmpty()) desired.merge(stack.getItem(), stack.getCount(), (a, b) -> Math.min(stack.getMaxStackSize(), a + b));
            }
        }
        if (desired.isEmpty()) { pause("Printer needs an interaction/tool that has no ordinary material entry. Check the target, then Resume."); return; }
        restock = new PrinterRestock(desired, List.of(build), searchEchest.get(), maxShulkers.get(), supplyRadius.get(), supplyRotate.get(), supplyClearance.get());
        phase = Phase.Restock;
    }

    private void print() {
        if (!mc.player.isFallFlying()) { setPrinting(false); phase = Phase.Select; return; }
        if (!validEscape(escape)) { pause("The escape route changed. Printer stopped before adding more blocks; take control and Resume when safe."); return; }
        if (target == null || PrinterIntegration.matches(target) && pendingSequence < 0) {
            setPrinting(false); phase = Phase.Select; idleSince = tick(); return;
        }
        status = "Printing " + target.toShortString() + " | " + lastMissing + " missing at last scan";
        if (tick() - printSince > 100 && PrinterIntegration.isIdle()) {
            setPrinting(false); defer(target); phase = Phase.Select;
        } else setPrinting(true);
    }

    private void beginTravel(List<Vec3> path, Phase next) {
        setPrinting(false);
        if (!PrinterIntegration.isIdle() || pendingSequence >= 0 || path.isEmpty()) return;
        // Reserve only the part actually traversed, not an unvisited destination across a bent route.
        travelExit = escape;
        route = List.copyOf(path); routeIndex = 1; afterTravel = next; phase = Phase.Travel;
        moveSince = tick(); lastMove = mc.player.position();
    }

    private void travel() {
        while (routeIndex < route.size() && mc.player.position().distanceToSqr(route.get(routeIndex)) < .02) routeIndex++;
        List<Vec3> back = new ArrayList<>();
        back.add(mc.player.position());
        for (int i = Math.min(routeIndex - 1, route.size() - 1); i >= 0; i--) back.add(route.get(i));
        back.addAll(travelExit);
        if (back.size() > 8192 || !validEscape(back)) { pause("The return corridor changed or exceeded the local route limit. Printing remains stopped; restore a safe exit."); return; }
        escape = List.copyOf(back);
        if (routeIndex >= route.size()) { phase = afterTravel; idleSince = tick(); route = List.of(); return; }
        if (!ensureFlying()) return;
        Vec3 point = route.get(routeIndex);
        Vec3 velocity = PrinterFlight.safeVelocity(mc.player.position(), point, speed.get(), width(), height(), this::clear);
        if (velocity.lengthSqr() == 0) { route = List.of(); phase = afterTravel; status = "Route changed; replanning."; return; }
        if (!fly().requestAutopilot(velocity)) { pause("ElytraFly rejected the flight request. Check the flight mode, then Resume."); return; }
        if (mc.player.position().distanceToSqr(lastMove) > .09) { lastMove = mc.player.position(); moveSince = tick(); }
        if (tick() - moveSince > 100) { pause("Flight made no progress for five seconds. Take control, then Resume."); return; }
        status = "Flying to " + BlockPos.containing(route.getLast()).toShortString() + " with an exit reserved.";
    }

    private boolean ensureFlying() {
        if (mc.player.isFallFlying()) { launchSince = -1; launchAttempts = 0; return true; }
        setPrinting(false);
        if (!PrinterIntegration.isIdle() || pendingSequence >= 0) { status = "Waiting for Printer actions before takeoff."; return false; }
        if (launchSince < 0) {
            if (!mc.player.onGround()) { launchSince = tick(); }
            else {
                if (!PrinterFlight.segmentClear(mc.player.position(), mc.player.position().add(0, 1.5, 0), width(), mc.player.getBbHeight(), this::clear)) {
                    pause("Not enough clear space to take off. Start flying from an open position, then Resume."); return false;
                }
                if (++launchAttempts > 3) { pause("The server did not accept takeoff. Start gliding manually, then Resume."); return false; }
                mc.player.jumpFromGround(); launchSince = tick();
            }
        }
        if (!mc.player.onGround() && tick() - launchSince >= 3 && (tick() - launchSince) % 4 == 3)
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        if (tick() - launchSince > 30) launchSince = -1;
        status = "Taking off with ElytraFly.";
        return false;
    }

    private void restockTick() {
        if (restock == null) { startScan(); return; }
        restock.tick();
        status = restock.status();
        if (restock.error() != null) { pause(restock.error()); return; }
        if (restock.isDone()) { restock = null; deferred.clear(); startScan(); return; }
        Vec3 destination = restock.destination();
        if (destination == null) return;
        double distance = mc.player.position().distanceToSqr(destination);
        if (mc.player.onGround() && !mc.player.isFallFlying() && distance < .16) return;
        if (mc.player.onGround() && !mc.player.isFallFlying() && groundApproach(destination)) return;
        if (mc.player.isFallFlying() && distance < .12 && restock.needsGround()) {
            Vec3 landingFeet = new Vec3(mc.player.getX(), destination.y, mc.player.getZ());
            AABB standing = PrinterFlight.body(landingFeet, width(), 1.8);
            if (mc.player.getY() < destination.y - .05 || !supported(standing)
                || !PrinterFlight.segmentClear(mc.player.position(), landingFeet, width(), 1.8, this::clear)) {
                pause("The supply landing spot changed. Check it, then Resume."); return;
            }
            // The flight collision guard correctly refuses to enter the floor. End gliding over verified support;
            // native gravity/collision supplies the real onGround packet, without a spoof or teleport.
            mc.player.stopFallFlying();
            mc.player.setDeltaMovement(0, -.08, 0);
            return;
        }
        if (!ensureFlying()) return;
        if (!refreshEscape()) { pause("No verified exit toward supplies. Printer remains stopped."); return; }
        Vec3 goal = destination;
        if (mc.player.position().distanceTo(goal) > 28) goal = mc.player.position().add(goal.subtract(mc.player.position()).normalize().scale(24));
        List<Vec3> path = PrinterFlight.route(mc.player.position(), goal, width(), 1.8, this::clear);
        if (path.isEmpty()) {
            if (restock.navigationFailed()) { status = "Trying another accessible supply landing."; return; }
            pause(restock.error() != null ? restock.error() : "No clear flight route to the outside supply landing at "
                + BlockPos.containing(destination).toShortString() + ". Move closer or clear access, then Resume."); return;
        }
        beginTravel(path, Phase.Restock);
    }

    private boolean supported(AABB body) {
        if (!clear(body)) return false;
        int y = (int) Math.floor(body.minY - .05);
        for (int x = (int) Math.floor(body.minX); x <= (int) Math.floor(Math.nextDown(body.maxX)); x++) {
            for (int z = (int) Math.floor(body.minZ); z <= (int) Math.floor(Math.nextDown(body.maxZ)); z++) {
                BlockPos floor = new BlockPos(x, y, z);
                if (!world.hasChunkAt(floor)) return false;
                BlockState state = world.getBlockState(floor);
                if (!state.getFluidState().isEmpty() || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
                    || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE) || !state.isCollisionShapeFullBlock(world, floor)) return false;
            }
        }
        return true;
    }

    private boolean groundApproach(Vec3 destination) {
        Vec3 from = mc.player.position();
        if (Math.abs(from.y - destination.y) > .1 || from.distanceToSqr(destination) > 9) return false;
        java.util.function.Predicate<AABB> safe = box -> Math.abs(box.minY - from.y) <= .1
            && Math.abs(box.maxY - (from.y + 1.8)) <= .1
            && box.minX >= from.x - 4 && box.maxX <= from.x + 4 && box.minZ >= from.z - 4 && box.maxZ <= from.z + 4
            && !box.intersects(build.inflate(supplyClearance.get())) && supported(box);
        List<Vec3> path = PrinterFlight.route(from, destination, width(), 1.8, safe);
        if (path.isEmpty()) return false;
        Vec3 step = path.size() > 1 ? path.get(1) : destination;
        Vec3 velocity = PrinterFlight.safeVelocity(from, new Vec3(step.x, from.y, step.z), .15, width(), 1.8, safe);
        if (velocity.lengthSqr() == 0) return false;
        groundVelocity = velocity;
        status = "Walking safely to the recovered supply container.";
        return true;
    }

    private boolean refreshEscape() {
        List<Vec3> direct = PrinterFlight.escapeRoute(mc.player.position(), build, width(), height(), this::clear);
        if (!direct.isEmpty()) { escape = direct; return true; }
        // Reuse a verified breadcrumb corridor if the shape requires bends beyond a direct exit.
        for (int i = escape.size() - 1; i >= 0; i--) {
            if (!PrinterFlight.segmentClear(mc.player.position(), escape.get(i), width(), height(), this::clear)) continue;
            List<Vec3> joined = new ArrayList<>(); joined.add(mc.player.position()); joined.addAll(escape.subList(i, escape.size()));
            if (validEscape(joined)) { escape = List.copyOf(joined); return true; }
        }
        return false;
    }

    private boolean validEscape(List<Vec3> path) {
        if (path.isEmpty() || PrinterFlight.body(path.getLast(), width(), height()).intersects(build.inflate(1))) return false;
        Vec3 previous = mc.player.position();
        for (Vec3 point : path) {
            if (!PrinterFlight.segmentClear(previous, point, width(), height(), this::clear)) return false;
            previous = point;
        }
        return true;
    }

    private boolean reserveExit() {
        if (pendingSequence >= 0 || !PrinterIntegration.isIdle() || !validEscape(escape)) return false;
        protectedExit = PrinterSafety.corridor(escape, width(), height());
        List<AABB> snapshot = protectedExit;
        var player = mc.player;
        return PrinterIntegration.setPlacementFilter(pos -> {
            if (!session || paused || corrected || escapeChanged || mc.player != player || mc.level != world) return false;
            BlockState desired = PrinterIntegration.desiredState(pos);
            return desired != null && PrinterSafety.mayPlace(pos, snapshot, PrinterFlight.body(player.position(), width(), height()),
                desired.getBlock() instanceof FallingBlock, !desired.getFluidState().isEmpty(), world.getMinY())
                && (target == null || PrinterSafety.depth(Vec3.atCenterOf(pos), build) + .01 >= PrinterSafety.depth(Vec3.atCenterOf(target), build));
        });
    }

    private boolean retreat() {
        if (!refreshEscape() || escape.size() < 2) return false;
        Vec3 from = mc.player.position(), next = escape.get(1);
        if (from.distanceTo(next) > 2) next = from.add(next.subtract(from).normalize().scale(2));
        if (next.distanceToSqr(from) < .05) return false;
        List<Vec3> step = PrinterFlight.route(from, next, width(), height(), this::clear);
        if (step.isEmpty()) return false;
        retreating = true;
        retreatDepth = Math.min(retreatDepth, PrinterSafety.depth(next, build));
        deferred.clear();
        beginTravel(step, Phase.Select);
        status = "Working outward; leaving the escape corridor open until we pass.";
        return true;
    }

    private boolean clear(AABB box) {
        if (mc.level != world || box.minY < world.getMinY() || box.maxY > world.getMaxY() + 1
            || !PrinterFlight.loaded(box, world.getChunkSource()::hasChunk) || !world.noCollision(mc.player, box)) return false;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
            BlockPos.containing(Math.nextDown(box.maxX), Math.nextDown(box.maxY), Math.nextDown(box.maxZ)))) {
            if (!world.getWorldBorder().isWithinBounds(pos)) return false;
            BlockState state = world.getBlockState(pos);
            if (!state.getFluidState().isEmpty() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.COBWEB) || state.is(Blocks.POWDER_SNOW)) return false;
        }
        return true;
    }

    private void setPrinting(boolean enabled) { printing = enabled; PrinterIntegration.setPrinting(enabled); }

    public void pause(String reason) {
        if (paused) return;
        paused = true; setPrinting(false);
        PrinterIntegration.suspend();
        if (restock != null) restock.pause();
        groundVelocity = Vec3.ZERO;
        fly().clearAutopilot();
        status = "Paused: " + reason;
        if (pendingSequence >= 0) status += " Already-sent placements may still resolve; movement control is yours.";
        warning("%s", status);
    }

    public void resume() {
        if (!session || mc.level != world || mc.player == null) { warning("Restart Printer Helper in the intended world."); return; }
        String problem = PrinterIntegration.sessionError();
        if (problem != null) { warning("%s Restart Printer Helper after checking the placement.", problem); return; }
        paused = false; corrected = false; escapeChanged = false; correctionWait = 0; launchSince = -1; launchAttempts = 0;
        route = List.of(); deferred.clear(); idleSince = tick();
        if (restock != null) { restock.resume(); phase = Phase.Restock; }
        else { retreating = false; retreatDepth = Double.POSITIVE_INFINITY; startScan(); }
        status = "Resuming; rechecking materials and escape route.";
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onMove(PlayerMoveEvent event) {
        if (!session || paused || mc.player == null || mc.level != world || !mc.player.onGround()) return;
        // No stale walking input while mining/opening a supply container; launch supplies its own vertical jump.
        Vec3 movement = groundVelocity;
        if (movement.lengthSqr() > 0 && !supported(PrinterFlight.body(mc.player.position(), width(), 1.8)
            .minmax(PrinterFlight.body(mc.player.position().add(movement), width(), 1.8)))) movement = Vec3.ZERO;
        ((IVec3) event.movement).monocle$set(movement.x, event.movement.y, movement.z);
    }

    @EventHandler private void onInventory(InventoryEvent event) { if (session && restock != null) restock.onInventory(event); }
    @EventHandler private void onSent(PacketEvent.Sent event) {
        if (!session) return;
        if (restock != null) restock.onPacketSent(event);
        if (phase != Phase.Restock && event.packet instanceof ServerboundUseItemOnPacket packet) {
            if (pendingSequence < 0) pendingSince = tick();
            pendingSequence = Math.max(pendingSequence, packet.getSequence()); settleSince = tick(); cleanPasses = 0;
        }
    }
    /** Called after the native correction has been applied on the client thread. */
    public void onServerCorrection() {
        if (!session || mc.player == null || mc.level != world) return;
        corrected = true;
        groundVelocity = Vec3.ZERO;
        setPrinting(false);
        PrinterIntegration.suspend();
        if (restock != null) restock.pause();
        if (!paused) fly().requestAutopilot(Vec3.ZERO);
    }

    public void onServerBlockAck(int sequence) {
        if (!session || mc.player == null || mc.level != world) return;
        if (restock != null) restock.onServerBlockAck(sequence);
        if (pendingSequence >= 0 && sequence >= pendingSequence) { pendingSequence = -1; settleSince = tick(); }
    }

    public void onServerBlockUpdate(BlockPos pos, BlockState state) {
        if (!session || mc.player == null || mc.level != world) return;
        if (restock != null) restock.onServerBlockUpdate(pos, state);
        if (phase == Phase.Print && PrinterSafety.touches(pos, protectedExit)) {
            escapeChanged = true;
            setPrinting(false);
            PrinterIntegration.suspend();
        }
        if (PrinterIntegration.isInScope(pos)) {
            cleanPasses = 0;
            if (phase == Phase.Complete && !PrinterIntegration.matches(pos)) { startScan(); info("Server changed the build; verifying it again."); }
        }
    }

    public void onSupplyItemPickup(int itemId, int collectorId, int amount) {
        if (session && restock != null) restock.onSupplyItemPickup(itemId, collectorId, amount);
    }

    @Override public WWidget getWidget(GuiTheme theme) {
        WVerticalList panel = theme.verticalList();
        panel.add(theme.label("Sakura Printer + Litematica required. ElytraFly: Vanilla."));
        panel.add(theme.label("Builds inside-out; keeps a verified escape corridor open."));
        panel.add(theme.label("Preserves containers and materials. Does not excavate wrong blocks."));
        statusLabel = panel.add(theme.label(status.length() > 110 ? status.substring(0, 107) + "... (see chat)" : status)).widget();
        WButton pauseButton = panel.add(theme.button("Pause / Resume")).expandX().widget();
        pauseButton.action = () -> { if (paused) resume(); else pause("Requested by you. Resume when ready."); };
        panel.add(theme.button("Re-scan / Resume")).expandX().widget().action = this::resume;
        return panel;
    }

    @Override public String getInfoString() { return paused ? status : phase + (lastMissing > 0 ? " | " + lastMissing + " missing" : ""); }

    @EventHandler private void onRender(Render3DEvent event) {
        if (!session || !renderRoute.get() || mc.level != world) return;
        if (target != null) event.renderer.box(target, new Color(80, 220, 185, 20), TARGET, ShapeMode.Both, 0);
        for (int i = 1; i < escape.size(); i++) {
            Vec3 a = escape.get(i - 1), b = escape.get(i);
            event.renderer.line(a.x, a.y + .35, a.z, b.x, b.y + .35, b.z, EXIT);
        }
    }
}
