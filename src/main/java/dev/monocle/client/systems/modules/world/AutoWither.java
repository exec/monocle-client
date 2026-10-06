package dev.monocle.client.systems.modules.world;

import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.entity.player.PlaceBlockEvent;
import dev.monocle.client.events.entity.player.PlayerMoveEvent;
import dev.monocle.client.events.packets.InventoryEvent;
import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.mixininterface.IVec3;
import dev.monocle.client.pathing.PathManagers;
import dev.monocle.client.settings.BoolSetting;
import dev.monocle.client.settings.IntSetting;
import dev.monocle.client.settings.Setting;
import dev.monocle.client.settings.SettingGroup;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.movement.elytrafly.ElytraFly;
import dev.monocle.client.systems.modules.movement.elytrafly.ElytraFlightModes;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.player.FindItemResult;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.Rotations;
import dev.monocle.client.utils.world.BlockUtils;
import dev.monocle.client.utils.world.PrinterFlight;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Builds server-confirmed Withers at reachable sites; movement is optional in Spam mode. */
public final class AutoWither extends Module {
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Boolean> spam = general.add(new BoolSetting.Builder().name("spam")
        .description("Build Withers at reachable air sites as you move around.")
        .defaultValue(false).build());
    private final Setting<Boolean> autopilot = general.add(new BoolSetting.Builder().name("autopilot")
        .description("Let Auto Wither steer between sites. Only applies while Spam is enabled.")
        .defaultValue(false).visible(spam::get).build());
    private final Setting<Boolean> excludeThirdHead = general.add(new BoolSetting.Builder().name("exclude-third-head")
        .description("Leave each verified structure with two heads for manual completion; never summon it.")
        .defaultValue(false).build());
    private final Setting<Integer> witherCount = general.add(new IntSetting.Builder().name("wither-count")
        .description("Stop after this many completed Withers (or two-head structures). Zero means unlimited.")
        .defaultValue(0).range(0, 100000).sliderRange(0, 64).build());
    private final Setting<Integer> withersPerStop = general.add(new IntSetting.Builder().name("withers-per-stop")
        .description("Build this many nearby Withers before autopilot flies to the next area. Clusters are dangerous.")
        .defaultValue(1).range(1, 32).sliderRange(1, 12).visible(() -> spam.get() && autopilot.get()).build());
    private final Setting<Integer> delay = general.add(new IntSetting.Builder().name("delay")
        .description("Minimum ticks between structures. Zero adds no artificial delay; placement still waits for server confirmation.")
        .defaultValue(0).range(0, 100).sliderRange(0, 40).visible(spam::get).build());
    private final Setting<Integer> spacing = general.add(new IntSetting.Builder().name("spacing")
        .description("Distance between autopilot sites. Manual flight may place closer together.")
        .defaultValue(24).range(20, 64).sliderRange(20, 48).visible(() -> spam.get() && autopilot.get()).build());

    private enum Phase { Idle, Approach, Restock, Build, Spawn, Escape, Paused }
    private record Supply(int soulSand, int soulSoil, int skulls) {
        int souls() { return soulSand + soulSoil; }
        int capacity(boolean exclude, boolean starter) { return AutoWither.capacity(souls(), skulls, exclude, starter); }
    }
    private record Pending(BlockPos pos, Block expected, int sequence, int sentTick, int attempts, boolean acked) {}
    private record Incomplete(int step, Direction side) {}

    private Phase phase = Phase.Idle;
    private ClientLevel world;
    private Direction forward, side;
    private BlockPos cursor, site, manualBase, spotOrigin;
    private int manualSequence = -1, step, stepRetries, nextTick, launchTick = -1, noProgressTick, siteTick, completed, builtAtSpot;
    private double flightRamp;
    private Vec3 lastPosition, lastTarget, escapeTarget, groundVelocity = Vec3.ZERO;
    private Pending pending, secondPending;
    private boolean sending, sendingSecond, manualConfirmed, queuedPlacement;
    private final Set<UUID> withersBefore = new HashSet<>();
    private final Map<BlockPos, Incomplete> incomplete = new LinkedHashMap<>();
    private final Map<BlockPos, Integer> rejectedSites = new LinkedHashMap<>();
    private PrinterRestock restock;
    private String status = "Place a soul sand block to begin, or enable Spam.";

    public AutoWither() {
        super(Categories.World, "auto-wither", "Builds verified Wither structures and flies to new sites in Spam mode.");
    }

    @Override public void onActivate() {
        if (mc.player == null || mc.level == null || mc.gameMode == null) { disable(); return; }
        if (Modules.get().get(HighwayBuilder.class).isActive() || Modules.get().get(PrinterHelper.class).isActive()
            || PathManagers.get().isPathing()) { error("Stop Highway Builder, Printer Helper, or pathing first."); disable(); return; }
        world = mc.level;
        phase = Phase.Idle;
        cursor = site = manualBase = spotOrigin = null;
        pending = secondPending = null; restock = null; queuedPlacement = false; escapeTarget = null;
        incomplete.clear();
        rejectedSites.clear();
        manualSequence = -1; step = stepRetries = nextTick = 0; launchTick = -1;
        withersBefore.clear();
        forward = mc.player.getDirection();
        side = forward.getClockWise();
        completed = builtAtSpot = 0;
        flightRamp = 0;
        lastPosition = mc.player.position();
        noProgressTick = mc.player.tickCount;
        if (steering()) {
            ElytraFly fly = fly();
            ItemStack glider = mc.player.getItemBySlot(EquipmentSlot.CHEST);
            if (fly.flightMode.get() != ElytraFlightModes.Vanilla || !glider.has(DataComponents.GLIDER)
                || glider.isDamageableItem() && glider.getMaxDamage() - glider.getDamageValue() <= 10) {
                error("Autopilot needs an equipped, usable elytra and Vanilla ElytraFly mode."); disable(); return;
            }
            if (!fly.isActive()) fly.enable();
        }
        if (!spam.get()) { phase = Phase.Idle; status = "Place the bottom-center soul sand or soul soil block."; return; }
        cursor = mc.player.blockPosition().relative(forward, 3);
        phase = Phase.Approach;
        status = "Finding a reachable air site.";
    }

    @Override public void onDeactivate() {
        if (steering() && Modules.get().get(ElytraFly.class) != null) fly().clearAutopilot();
        if (restock != null) {
            String recovery = restock.cancel();
            if (!recovery.isBlank() && mc.player != null) warning("%s", recovery);
        }
        if (site != null && step > 0 && mc.player != null)
            warning("A partial Wither structure may remain at %s.", site.toShortString());
        restock = null; pending = secondPending = null; manualBase = site = cursor = spotOrigin = null; lastTarget = escapeTarget = null; queuedPlacement = false;
        incomplete.clear();
        rejectedSites.clear();
        groundVelocity = Vec3.ZERO; phase = Phase.Idle; world = null;
    }

    public boolean controlsInventory() { return isActive() && phase != Phase.Idle && phase != Phase.Paused; }
    public boolean controlsPlayer() { return isActive() && steering() && phase != Phase.Idle && phase != Phase.Paused; }
    private boolean steering() { return spam.get() && autopilot.get(); }
    @Override public String getInfoString() {
        return status + (completed > 0 ? " · " + completed + " built" : "")
            + (incomplete.isEmpty() ? "" : " · " + incomplete.size() + " unfinished");
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onTick(TickEvent.Pre event) {
        if (world == null || mc.player == null || mc.level != world) { if (isActive()) stop("World changed"); return; }
        groundVelocity = Vec3.ZERO;
        if (phase == Phase.Idle || phase == Phase.Paused) return;
        try { tick(); }
        catch (RuntimeException failure) {
            MonocleClient.LOG.error("Auto Wither stopped after a failed operation", failure);
            stop("Operation failed (" + failure.getClass().getSimpleName() + "); inspect the current structure");
        }
    }

    private void tick() {
        if (steering() && (mc.player.isPassenger() || mc.player.isInWater() || mc.player.isInLava())) {
            stop("Flight interrupted by riding or fluid"); return;
        }
        if (mc.player.isUsingItem() && (phase == Phase.Build || phase == Phase.Restock)) {
            holdFlight(); status = "Waiting for item use"; return;
        }
        if (phase == Phase.Approach) { approach(); return; }
        if (phase == Phase.Restock) { restock(); return; }
        if (phase == Phase.Spawn) { awaitSpawn(); return; }
        if (phase == Phase.Escape) {
            if (moveTo(escapeTarget, false)) {
                BlockPos departed = spotOrigin == null ? site : spotOrigin;
                site = spotOrigin = null; step = builtAtSpot = 0;
                if (countReached()) disable();
                else {
                    cursor = nextSite(departed, forward, spacing.get());
                    phase = Phase.Approach;
                    status = "Finding the next Wither area";
                }
            }
            return;
        }
        if (phase != Phase.Build || site == null) return;
        holdFlight();
        if (pending != null) { verifyPending(); return; }
        if (queuedPlacement) return;
        int limit = excludeThirdHead.get() ? 6 : 7;
        if (step >= limit) { finishSite(); return; }
        if (mc.player.tickCount < nextTick) return;
        if (!structureStillValid()) {
            if (spam.get()) { site = null; step = 0; phase = Phase.Approach; status = "Structure changed; finding another site"; }
            else stop("Structure changed at " + site.toShortString());
            return;
        }
        if (queuePlacement(step, false) && pairable(step)) queuePlacement(step + 1, true);
    }

    static boolean pairable(int step) { return step >= 0 && step + 1 < 6; }

    /** The first six blocks may be pipelined in pairs; the summoning skull is never speculative. */
    private boolean queuePlacement(int placementStep, boolean second) {
        List<BlockPos> parts = parts(site, side);
        BlockPos target = parts.get(placementStep);
        Item item = placementStep < 4 ? soulItem() : Items.WITHER_SKELETON_SKULL;
        if (item == null || !InvUtils.find(item).found()) {
            if (second) return false;
            if (hasNeeded(potential(), step)) {
                if (!mc.player.onGround() && !steering()) { status = "Land to unpack Wither materials"; return false; }
                beginRestock(); return false;
            }
            stop("Materials changed before this structure was complete at " + site.toShortString()); return false;
        }
        FindItemResult found = InvUtils.findInHotbar(item);
        if (!found.found()) {
            if (second) return false;
            if (mc.player.containerMenu != mc.player.inventoryMenu
                || !mc.player.containerMenu.getCarried().isEmpty()) { status = "Waiting for inventory to close"; return false; }
            int source = InvUtils.find(item).slot();
            if (source < 0) { beginRestock(); return false; }
            int destination = emptyHotbar();
            if (destination < 0) destination = mc.player.getInventory().getSelectedSlot();
            InvUtils.move().from(source).toHotbar(destination);
            status = "Moving Wither materials into the hotbar";
            return false;
        }
        Block expected = placementStep < 4 ? item == Items.SOUL_SOIL ? Blocks.SOUL_SOIL : Blocks.SOUL_SAND : Blocks.WITHER_SKELETON_SKULL;
        BlockPos support = support(parts, placementStep);
        boolean airPlace = placementStep == 0 && mc.level.getBlockState(support).canBeReplaced();
        BlockPos clicked = airPlace ? target : support;
        Direction face = airPlace ? mc.player.getMotionDirection().getOpposite()
            : placementStep == 2 ? side.getOpposite() : placementStep == 3 ? side : Direction.UP;
        Vec3 hit = airPlace ? Vec3.atCenterOf(target)
            : Vec3.atCenterOf(clicked).add(face.getStepX() * .5, face.getStepY() * .5, face.getStepZ() * .5);
        if (!reachable(target)) {
            if (second) return false;
            if (spam.get() && !steering()) { rememberIncomplete(); phase = Phase.Approach; }
            else if (steering()) moveTo(stance(site, side), false);
            else status = "Move within reach of " + target.toShortString();
            return false;
        }
        if (!mc.level.getBlockState(target).canBeReplaced()) {
            if (second) return false;
            if (spam.get()) { rememberIncomplete(); phase = Phase.Approach; }
            else status = "Placement blocked at " + target.toShortString();
            return false;
        }
        int verifiedStep = step;
        int slot = found.slot();
        InteractionHand hand = found.isOffhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        if (placementStep == 6) {
            withersBefore.clear();
            for (WitherBoss wither : world.getEntitiesOfClass(WitherBoss.class, new AABB(site).inflate(5)))
                withersBefore.add(wither.getUUID());
        }
        queuedPlacement = true;
        Rotations.rotate(Rotations.getYaw(hit), Rotations.getPitch(hit), 50, () -> {
            queuedPlacement = false;
            if (!isActive() || phase != Phase.Build || step != verifiedStep || mc.level != world || site == null
                || (second ? pending == null || pending.sequence < 0 || secondPending != null
                    || !mc.level.getBlockState(pending.pos).is(pending.expected) : pending != null)
                || !mc.level.getBlockState(target).canBeReplaced() || !(hand == InteractionHand.OFF_HAND
                    ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(slot)).is(item)) return;
            if (hand == InteractionHand.MAIN_HAND) InvUtils.swap(slot, true);
            sending = true; sendingSecond = second;
            if (second) secondPending = new Pending(target, expected, -1, mc.player.tickCount, 0, false);
            else pending = new Pending(target, expected, -1, mc.player.tickCount, stepRetries, false);
            try {
                mc.gameMode.useItemOn(mc.player, hand, new BlockHitResult(hit, face, clicked, false));
            } finally { sending = false; if (hand == InteractionHand.MAIN_HAND) InvUtils.swapBack(); }
            if (second && secondPending != null && secondPending.sequence < 0) secondPending = null;
            if (!second && pending != null && pending.sequence < 0) pending = null;
        });
        status = "Placing " + (placementStep + 1) + "/" + (excludeThirdHead.get() ? 6 : 7) + " at " + site.toShortString();
        return true;
    }

    private void approach() {
        if (site == null) {
            site = resumedSite();
            if (site == null && potential().capacity(excludeThirdHead.get(), false) == 0) { stop("No complete Wither materials remain"); return; }
            if (site == null) site = findLocalSite();
            if (site == null && steering()) site = spotOrigin == null ? findSite() : findClusterSite();
            if (site == null && steering() && builtAtSpot > 0) {
                escapeTarget = stance(spotOrigin.relative(forward, spacing.get()), side);
                phase = Phase.Escape;
                status = "No more clear cluster sites; leaving this area";
                return;
            }
            if (site == null) { status = "Move near clear air to continue Wither Spam"; return; }
            if (steering() && spotOrigin == null) spotOrigin = site;
            stepRetries = 0;
        }
        if (!reachable(parts(site, side).get(step))) {
            if (steering()) { if (!moveTo(stance(site, side), false)) return; }
            else {
                BlockPos unfinished = site;
                if (spam.get()) rememberIncomplete();
                status = "Move within reach of the unfinished Wither at " + unfinished.toShortString();
                return;
            }
        }
        if (!hasNeeded(carried(), step)) {
            if (!mc.player.onGround() && !steering()) { status = "Land to unpack carried Wither materials"; return; }
            beginRestock(); return;
        }
        phase = Phase.Build;
        siteTick = mc.player.tickCount;
        status = "Building at " + site.toShortString();
    }

    private void beginRestock() {
        Supply known = potential();
        if (!hasNeeded(known, step)) { stop("Not enough materials to complete this structure"); return; }
        Map<Item, Integer> desired = new HashMap<>();
        if (known.soulSand > 0) desired.put(Items.SOUL_SAND, Math.min(64, known.soulSand));
        if (known.soulSoil > 0) desired.put(Items.SOUL_SOIL, Math.min(64, known.soulSoil));
        desired.put(Items.WITHER_SKELETON_SKULL, Math.min(48, known.skulls));
        restock = new PrinterRestock(desired, List.of(new AABB(site).inflate(8)), false, 36, 16, true, 8);
        phase = Phase.Restock;
        status = "Unpacking carried Wither materials";
    }

    private void restock() {
        if (restock == null) { phase = Phase.Approach; return; }
        restock.tick();
        status = restock.status();
        if (restock.error() != null) { stop("Restock stopped: " + restock.error()); return; }
        if (restock.isDone()) {
            restock = null;
            if (!hasNeeded(carried(), step)) { stop("Shulker restock did not yield enough to complete the structure"); return; }
            phase = Phase.Approach;
            return;
        }
        Vec3 destination = restock.destination();
        if (destination != null && steering() && !moveTo(destination, true)) status = "Restocking: " + status;
        else if (destination != null && !steering()) status = "Restocking nearby; move to " + BlockPos.containing(destination).toShortString();
    }

    private void verifyPending() {
        if (pending == null) return;
        if (step == 6 && (newWither() != null || summonedStructureGone())) { confirmed(); return; }
        if (pending.sequence < 0) { pending = null; return; }
        int elapsed = mc.player.tickCount - pending.sentTick;
        if (pending.acked && elapsed >= 1 && mc.level.getBlockState(pending.pos).is(pending.expected)) {
            confirmed(); return;
        }
        if (elapsed > 40) {
            if (spam.get() && !steering() && (!world.hasChunkAt(pending.pos) || !reachable(pending.pos))) {
                pending = secondPending = null; rememberIncomplete(); phase = Phase.Approach;
                status = "Left an unfinished Wither; return within reach to retry"; return;
            }
            if (pending.attempts >= 3) {
                if (spam.get()) {
                    rejectSite(); pending = secondPending = null; rememberIncomplete(); phase = Phase.Approach;
                    status = "Unconfirmed block; trying another site";
                }
                else stop("Server did not confirm " + pending.pos.toShortString() + "; inspect the partial structure");
                return;
            }
            if (mc.level.getBlockState(pending.pos).canBeReplaced()) {
                stepRetries++;
                pending = null;
                status = "Retrying unconfirmed block";
            } else if (spam.get()) {
                rejectSite(); pending = secondPending = null; rememberIncomplete(); phase = Phase.Approach;
                status = "Placement blocked; trying another site";
            } else stop("Unconfirmed block at " + pending.pos.toShortString() + " is occupied; inspect it");
        }
    }

    private void confirmed() {
        if (pending == null) return;
        boolean last = step == 6;
        pending = secondPending;
        secondPending = null;
        step++;
        stepRetries = 0;
        if (last) {
            phase = Phase.Spawn;
            siteTick = mc.player.tickCount;
        }
    }

    private void awaitSpawn() {
        holdFlight();
        if (newWither() != null || summonedStructureGone()) {
            finishSite();
            return;
        }
        if (mc.player.tickCount - siteTick > 100) {
            if (spam.get() && !steering()) {
                warning("Wither spawn not confirmed at %s; returning there later if the structure remains.", site.toShortString());
                step = 6; rememberIncomplete(); phase = Phase.Approach;
            } else stop("The final head did not produce a visible Wither at " + site.toShortString());
        }
        else status = "Waiting for the new Wither to appear";
    }

    private void finishSite() {
        completed++;
        incomplete.remove(site);
        if (steering()) {
            if (spotOrigin == null) spotOrigin = site;
            builtAtSpot++;
            if (leaveStop(builtAtSpot, withersPerStop.get(), countReached())) {
                escapeTarget = stance(spotOrigin.relative(forward, spacing.get()), side);
                site = null; step = 0;
                phase = Phase.Escape;
                status = "Leaving this Wither cluster";
                return;
            }
        }
        if (!spam.get()) {
            info("Completed %s at %s.", excludeThirdHead.get() ? "a two-head structure" : "a Wither", site.toShortString());
            site = null; step = 0; phase = Phase.Idle;
            if (countReached()) disable();
            else status = "Place another bottom-center soul block";
            return;
        }
        if (countReached()) { site = null; step = 0; disable(); return; }
        site = null; step = 0; stepRetries = 0; pending = secondPending = null;
        nextTick = mc.player.tickCount + delay.get();
        phase = Phase.Approach;
        status = steering() ? "Building another Wither in this area" : "Finding another reachable Wither site";
    }
    private boolean countReached() { return countReached(completed, witherCount.get()); }
    static boolean countReached(int completed, int limit) { return limit > 0 && completed >= limit; }
    static boolean leaveStop(int built, int perStop, boolean totalDone) { return totalDone || built >= perStop; }

    private void onManualBase() {
        if (manualBase == null || !manualConfirmed || phase != Phase.Idle) return;
        forward = mc.player.getDirection(); side = forward.getClockWise();
        site = manualBase; manualBase = null; manualConfirmed = false;
        step = 1; // The player placed the stem's bottom block.
        if (!siteValid(site, true)) { stop("The placed block has no clear Wither shape at " + site.toShortString()); return; }
        if (!hasNeeded(potential(), step)) { stop("Need three more soul blocks and " + (excludeThirdHead.get() ? 2 : 3) + " skulls"); return; }
        phase = Phase.Build;
        siteTick = mc.player.tickCount;
    }

    @EventHandler private void onPlace(PlaceBlockEvent event) {
        if (spam.get() || phase != Phase.Idle || sending || event.isCancelled() || !soul(event.block)) return;
        manualBase = event.blockPos.immutable(); manualSequence = -1; manualConfirmed = false;
    }
    @EventHandler private void onSent(PacketEvent.Sent event) {
        if (!(event.packet instanceof ServerboundUseItemOnPacket packet)) return;
        if (sending && sendingSecond && secondPending != null)
            secondPending = new Pending(secondPending.pos, secondPending.expected, packet.getSequence(), secondPending.sentTick, secondPending.attempts, false);
        else if (sending && pending != null)
            pending = new Pending(pending.pos, pending.expected, packet.getSequence(), pending.sentTick, pending.attempts, false);
        else if (manualBase != null && phase == Phase.Idle) manualSequence = packet.getSequence();
    }
    public void onServerBlockAck(int sequence) {
        if (!isActive() || mc.level != world) return;
        if (manualBase != null && manualSequence >= 0 && sequence >= manualSequence) {
            manualConfirmed = soul(mc.level.getBlockState(manualBase).getBlock());
            if (!manualConfirmed) manualBase = null;
            onManualBase();
        }
        if (pending != null && pending.sequence >= 0 && sequence >= pending.sequence) {
            if (step == 6 && newWither() != null) confirmed();
            else pending = new Pending(pending.pos, pending.expected, pending.sequence, pending.sentTick, pending.attempts, true);
        }
        if (secondPending != null && secondPending.sequence >= 0 && sequence >= secondPending.sequence)
            secondPending = new Pending(secondPending.pos, secondPending.expected, secondPending.sequence,
                secondPending.sentTick, secondPending.attempts, true);
    }
    public void onServerBlockUpdate(BlockPos pos, BlockState state) {
        if (!isActive() || mc.level != world) return;
        if (manualBase != null && manualBase.equals(pos) && soul(state.getBlock())) { manualConfirmed = true; onManualBase(); }
        if (pending != null && pending.pos.equals(pos) && state.is(pending.expected)) confirmed();
        if (secondPending != null && secondPending.pos.equals(pos) && state.is(secondPending.expected))
            secondPending = new Pending(secondPending.pos, secondPending.expected, secondPending.sequence,
                secondPending.sentTick, secondPending.attempts, true);
    }
    public void onSupplyItemPickup(int itemId, int collectorId, int amount) {
        if (restock != null) restock.onSupplyItemPickup(itemId, collectorId, amount);
    }
    @EventHandler private void onInventory(InventoryEvent event) { if (restock != null) restock.onInventory(event); }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onMove(PlayerMoveEvent event) {
        if (controlsPlayer() && event.type == MoverType.SELF
            && mc.player != null && mc.player.onGround())
            ((IVec3) event.movement).monocle$set(groundVelocity.x, event.movement.y, groundVelocity.z);
    }

    private boolean moveTo(Vec3 target, boolean land) {
        Vec3 from = mc.player.position();
        Vec3 waypoint = builtAtSpot > 0 && builtAtSpot < withersPerStop.get() ? target : avoidWithers(from, target);
        if (waypoint == null) { stop("No clear route around a nearby Wither"); return false; }
        boolean finalTarget = waypoint.equals(target);
        target = waypoint;
        if (!target.equals(lastTarget)) { lastTarget = target; lastPosition = from; noProgressTick = mc.player.tickCount; }
        if (from.distanceToSqr(lastPosition) > .25) { lastPosition = from; noProgressTick = mc.player.tickCount; }
        if (mc.player.tickCount - noProgressTick > 60) { stop("No movement progress toward " + BlockPos.containing(target).toShortString()); return false; }
        double distance = from.distanceTo(target);
        if (distance < (land ? .3 : .65) && (!land || mc.player.onGround())) { holdFlight(); return finalTarget; }
        ElytraFly fly = fly();
        if (mc.player.isFallFlying()) {
            if (land && Math.hypot(from.x - target.x, from.z - target.z) < .5) {
                fly.requestAutopilot(Vec3.ZERO); mc.player.stopFallFlying(); mc.player.setDeltaMovement(0, -.08, 0); return false;
            }
            Vec3 delta = target.subtract(from);
            double speed = fly.horizontalSpeed.get();
            if (fly.acceleration.get()) {
                flightRamp = Math.min(speed, flightRamp + fly.accelerationMin.get() + fly.accelerationStep.get() * .1);
                speed = flightRamp;
            }
            Vec3 movement = delta.length() <= speed ? delta : delta.scale(speed / delta.length());
            if (!PrinterFlight.segmentClear(from, from.add(movement), mc.player.getBbWidth() + .12, mc.player.getBbHeight(), this::clear)) {
                stop("Flight corridor is obstructed or unloaded; no blind movement"); return false;
            }
            if (!fly.requestConfiguredAutopilot(movement)) stop("ElytraFly rejected movement control");
            else status = "Flying to " + BlockPos.containing(target).toShortString();
            return false;
        }
        if (mc.player.onGround() && Math.abs(target.y - from.y) < .2 && distance < 6
            && PrinterFlight.segmentClear(from, target, mc.player.getBbWidth() + .12, 1.8, this::clear)
            && supported(target)) {
            Vec3 delta = target.subtract(from);
            groundVelocity = delta.length() <= .22 ? delta : delta.scale(.22 / delta.length());
            status = "Walking to " + BlockPos.containing(target).toShortString();
            return false;
        }
        if (!fly.isActive() || fly.flightMode.get() != ElytraFlightModes.Vanilla
            || !mc.player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)) {
            stop("Movement needs an equipped elytra and Vanilla ElytraFly"); return false;
        }
        fly.requestAutopilot(Vec3.ZERO);
        if (mc.player.onGround()) {
            flightRamp = 0;
            if (!PrinterFlight.segmentClear(from, from.add(0, 1.5, 0), mc.player.getBbWidth() + .12, 1.8, this::clear)) {
                stop("No clear space to take off"); return false;
            }
            mc.player.jumpFromGround(); launchTick = mc.player.tickCount;
        } else if (launchTick >= 0 && mc.player.tickCount - launchTick >= 3 && (mc.player.tickCount - launchTick) % 4 == 3)
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        status = "Taking off with ElytraFly";
        return false;
    }

    private Vec3 avoidWithers(Vec3 from, Vec3 target) {
        Vec3 travel = target.subtract(from);
        if (travel.lengthSqr() < 1) return target;
        for (WitherBoss wither : world.getEntitiesOfClass(WitherBoss.class, new AABB(from, target).inflate(16), WitherBoss::isAlive)) {
            Vec3 danger = wither.position();
            double fraction = Math.max(0, Math.min(1, danger.subtract(from).dot(travel) / travel.lengthSqr()));
            if (danger.distanceToSqr(from.add(travel.scale(fraction))) >= 144
                || danger.distanceToSqr(from) < 144 && danger.distanceToSqr(target) > danger.distanceToSqr(from)) continue;
            double offset = danger.subtract(from).dot(new Vec3(side.getStepX(), 0, side.getStepZ())) > 0 ? -24 : 24;
            Vec3 detour = new Vec3(danger.x + side.getStepX() * offset, target.y, danger.z + side.getStepZ() * offset);
            if (clear(PrinterFlight.body(detour, mc.player.getBbWidth() + .12, mc.player.getBbHeight()))) return detour;
            detour = new Vec3(danger.x - side.getStepX() * offset, target.y, danger.z - side.getStepZ() * offset);
            if (clear(PrinterFlight.body(detour, mc.player.getBbWidth() + .12, mc.player.getBbHeight()))) return detour;
            return null;
        }
        return target;
    }

    private boolean clear(AABB box) {
        if (box.minY < world.getMinY() || box.maxY > world.getMaxY() + 1
            || !PrinterFlight.loaded(box, world.getChunkSource()::hasChunk)
            || world.getBlockCollisions(mc.player, box).iterator().hasNext()) return false;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
            BlockPos.containing(Math.nextDown(box.maxX), Math.nextDown(box.maxY), Math.nextDown(box.maxZ)))) {
            if (!world.getWorldBorder().isWithinBounds(pos)) return false;
            BlockState state = world.getBlockState(pos);
            if (!state.getFluidState().isEmpty() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.COBWEB) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY)) return false;
        }
        return true;
    }
    private boolean supported(Vec3 feet) {
        BlockPos floor = BlockPos.containing(feet.x, feet.y - 1, feet.z);
        return world.hasChunkAt(floor) && world.getBlockState(floor).isCollisionShapeFullBlock(world, floor);
    }
    private void holdFlight() {
        if (steering() && mc.player != null && mc.player.isFallFlying() && fly().isActive()) {
            flightRamp = 0;
            fly().requestAutopilot(Vec3.ZERO);
        }
    }
    private ElytraFly fly() { return Modules.get().get(ElytraFly.class); }

    private boolean reachable(BlockPos pos) {
        return pos.distToCenterSqr(mc.player.getEyePosition()) <= 25;
    }
    private boolean hasNeeded(Supply supply, int progress) {
        return enough(supply.souls(), supply.skulls, progress, excludeThirdHead.get());
    }
    static boolean enough(int souls, int skulls, int progress, boolean excludeThirdHead) {
        return souls >= Math.max(0, 4 - progress)
            && skulls >= Math.max(0, (excludeThirdHead ? 6 : 7) - Math.max(4, progress));
    }
    private void rememberIncomplete() {
        if (site != null && step > 0) {
            // ponytail: session-local ledger; persist it if cross-session recovery becomes necessary.
            if (incomplete.size() >= 4096) incomplete.remove(incomplete.keySet().iterator().next());
            incomplete.put(site, new Incomplete(step, side));
        }
        site = null;
        step = 0;
    }
    private void rejectSite() {
        if (site == null) return;
        if (rejectedSites.size() >= 4096) rejectedSites.remove(rejectedSites.keySet().iterator().next());
        rejectedSites.put(site, mc.player.tickCount + 100);
    }
    private boolean rejected(BlockPos pos) {
        Integer until = rejectedSites.get(pos);
        if (until == null) return false;
        if (mc.player.tickCount < until) return true;
        rejectedSites.remove(pos);
        return false;
    }
    private BlockPos resumedSite() {
        Iterator<Map.Entry<BlockPos, Incomplete>> entries = incomplete.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<BlockPos, Incomplete> entry = entries.next();
            BlockPos base = entry.getKey();
            Incomplete progress = entry.getValue();
            if (rejected(base)) continue;
            if (!world.hasChunkAt(base)) continue;
            List<BlockPos> positions = parts(base, progress.side());
            boolean intact = true;
            for (int i = 0; i < progress.step(); i++) {
                Block block = world.getBlockState(positions.get(i)).getBlock();
                if (i < 4 ? !soul(block) : block != Blocks.WITHER_SKELETON_SKULL) { intact = false; break; }
            }
            if (!intact || !world.getBlockState(positions.get(progress.step())).canBeReplaced()) { entries.remove(); continue; }
            if (!reachable(positions.get(progress.step()))) continue;
            side = progress.side(); step = progress.step();
            entries.remove();
            return base;
        }
        step = 0;
        return null;
    }

    private BlockPos findLocalSite() {
        Direction facing = mc.player.getDirection();
        Direction across = facing.getClockWise();
        side = across;
        BlockPos player = mc.player.blockPosition();
        for (int distance = 2; distance <= 3; distance++) for (int lateral = 0; lateral <= 2; lateral++)
            for (int sign : new int[] {1, -1}) for (int down = 0; down <= 2; down++) {
                BlockPos candidate = player.relative(facing, distance).relative(across, lateral * sign).below(down);
                if (rejected(candidate) || !siteValid(candidate, false)
                    || steering() && builtAtSpot == 0 && nearWither(candidate)) continue;
                boolean reachable = true;
                for (BlockPos part : parts(candidate, across)) if (!reachable(part)) { reachable = false; break; }
                if (reachable) { side = across; return candidate; }
            }
        return null;
    }

    private BlockPos findSite() {
        if (cursor == null) return null;
        side = forward.getClockWise();
        for (int i = 0; i < 64; i++) {
            BlockPos candidate = cursor.relative(forward, i);
            if (rejected(candidate) || !siteValid(candidate, false) || nearWither(candidate)) continue;
            Vec3 stance = stance(candidate, side);
            if (clear(PrinterFlight.body(stance, mc.player.getBbWidth() + .12, mc.player.getBbHeight()))) return candidate;
        }
        return null;
    }
    private BlockPos findClusterSite() {
        if (spotOrigin == null) return null;
        side = forward.getClockWise();
        BlockPos nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (int radius = 3; radius <= 8; radius++) for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = spotOrigin.relative(direction, radius);
            if (rejected(candidate) || !siteValid(candidate, false)) continue;
            if (parts(candidate, side).stream().anyMatch(part -> !reachable(part))) continue;
            double candidateDistance = candidate.distToCenterSqr(mc.player.getEyePosition());
            if (candidateDistance < distance) { nearest = candidate; distance = candidateDistance; }
        }
        return nearest;
    }
    private boolean nearWither(BlockPos candidate) {
        return !world.getEntitiesOfClass(WitherBoss.class, new AABB(candidate).inflate(20), wither -> wither.isAlive()).isEmpty();
    }
    private boolean siteValid(BlockPos base, boolean starter) {
        if (!world.hasChunkAt(base) || !world.getWorldBorder().isWithinBounds(base) || base.getY() < world.getMinY()
            || base.getY() + 2 > world.getMaxY()) return false;
        List<BlockPos> positions = parts(base, side);
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            if (!world.hasChunkAt(pos) || !world.getWorldBorder().isWithinBounds(pos)) return false;
            if (starter && i == 0) { if (!soul(world.getBlockState(pos).getBlock())) return false; }
            else if (!BlockUtils.canPlaceBlock(pos, true, i < 4 ? Blocks.SOUL_SAND : Blocks.WITHER_SKELETON_SKULL)) return false;
        }
        return world.getBlockState(base.relative(side)).isAir()
            && world.getBlockState(base.relative(side.getOpposite())).isAir();
    }
    private boolean structureStillValid() {
        List<BlockPos> positions = parts(site, side);
        for (int i = 0; i < step; i++) {
            Block block = world.getBlockState(positions.get(i)).getBlock();
            if (i < 4 ? !soul(block) : block != Blocks.WITHER_SKELETON_SKULL) return false;
        }
        return true;
    }
    private WitherBoss newWither() {
        if (site == null) return null;
        return world.getEntitiesOfClass(WitherBoss.class, new AABB(site).inflate(5), entity -> entity.isAlive()
            && !withersBefore.contains(entity.getUUID())).stream().findFirst().orElse(null);
    }
    private boolean summonedStructureGone() {
        if (site == null || !world.hasChunkAt(site)) return false;
        for (BlockPos part : parts(site, side)) if (!world.getBlockState(part).isAir()) return false;
        return true;
    }
    private Item soulItem() {
        if (InvUtils.find(Items.SOUL_SAND).found()) return Items.SOUL_SAND;
        if (InvUtils.find(Items.SOUL_SOIL).found()) return Items.SOUL_SOIL;
        return null;
    }
    private static boolean soul(Block block) { return block == Blocks.SOUL_SAND || block == Blocks.SOUL_SOIL; }
    private int emptyHotbar() {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        return -1;
    }
    private Supply carried() { return countSupplies(false); }
    private Supply potential() { return countSupplies(true); }
    private Supply countSupplies(boolean nested) {
        int sand = 0, soil = 0, skull = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.is(Items.SOUL_SAND)) sand += stack.getCount();
            if (stack.is(Items.SOUL_SOIL)) soil += stack.getCount();
            if (stack.is(Items.WITHER_SKELETON_SKULL)) skull += stack.getCount();
            if (nested && Utils.isShulker(stack.getItem())) {
                ItemStack[] contents = new ItemStack[27];
                Utils.getItemsInContainerItem(stack, contents);
                for (ItemStack item : contents) {
                    if (item.is(Items.SOUL_SAND)) sand += item.getCount();
                    if (item.is(Items.SOUL_SOIL)) soil += item.getCount();
                    if (item.is(Items.WITHER_SKELETON_SKULL)) skull += item.getCount();
                }
            }
        }
        return new Supply(sand, soil, skull);
    }

    static int capacity(int soulBlocks, int skulls, boolean excludeThirdHead, boolean starter) {
        return Math.max(0, Math.min((soulBlocks + (starter ? 1 : 0)) / 4, skulls / (excludeThirdHead ? 2 : 3)));
    }
    static BlockPos nextSite(BlockPos base, Direction forward, int spacing) { return base.relative(forward, spacing); }
    static List<BlockPos> parts(BlockPos base, Direction side) {
        BlockPos center = base.above();
        return List.of(base, center, center.relative(side.getOpposite()), center.relative(side),
            center.relative(side.getOpposite()).above(), center.relative(side).above(), center.above());
    }
    private static BlockPos support(List<BlockPos> parts, int step) {
        return switch (step) {
            case 0 -> parts.get(0).below();
            case 1 -> parts.get(0);
            case 2, 3 -> parts.get(1);
            case 4 -> parts.get(2);
            case 5 -> parts.get(3);
            case 6 -> parts.get(1);
            default -> throw new IllegalArgumentException("Invalid Wither placement step");
        };
    }
    private static Vec3 stance(BlockPos base, Direction side) { return Vec3.atBottomCenterOf(base.relative(side, 3)); }

    private void stop(String reason) {
        status = reason;
        warning("%s", reason);
        phase = Phase.Paused;
        groundVelocity = Vec3.ZERO;
        if (restock != null) restock.pause();
        if (Modules.get().get(ElytraFly.class) != null) fly().clearAutopilot();
        disable();
    }
}
