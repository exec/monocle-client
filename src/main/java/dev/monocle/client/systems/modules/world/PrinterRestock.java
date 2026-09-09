package dev.monocle.client.systems.modules.world;

import dev.monocle.client.events.packets.InventoryEvent;
import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.player.EChestMemory;
import dev.monocle.client.utils.player.InventoryLoadout;
import dev.monocle.client.utils.player.InventoryTransfer;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.Rotations;
import dev.monocle.client.utils.world.BlockUtils;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.monocle.client.MonocleClient.mc;

/** One printer restock visit. The owning module handles navigation and exclusive player control. */
public final class PrinterRestock {
    private enum Stage { Choose, FindSite, Place, Open, Transfer, Break, Collect, Done }

    private final ClientLevel world;
    private final LocalPlayer player;
    private final Map<Item, Integer> desired;
    private final Map<Item, Integer> startingCounts = new HashMap<>();
    private final List<AABB> buildBounds;
    private final boolean searchEchest, rotate;
    private final int maxShulkers, searchRadius, clearance;
    private final List<ItemStack> visitedBoxes = new ArrayList<>();
    private final Set<BlockPos> rejectedChests = new HashSet<>();
    private final Set<BlockPos> rejectedSites = new HashSet<>();
    private Stage stage = Stage.Choose;
    private String error, status = "Checking carried supplies", afterRecoveryError;
    private boolean cancelled, suspended, chestVisited, borrowed, owned, placementSent, queued, sendingPlace, sendingMine;
    private int epoch, stalledTicks, actionDelay, openTicks, shulkersTaken, placeSequence = -1, mineSequence = -1;
    private boolean placedBlockConfirmed, placedItemConfirmed;
    private int placementInventoryBefore;
    private boolean breakConfirmed, trackingDrop, takenByOther;
    private ItemStack container = ItemStack.EMPTY, recoveryItem = ItemStack.EMPTY;
    private BlockPos containerPos;
    private Vec3 destination;
    private List<BlockPos> siteColumns;
    private int siteIndex, siteTop, siteBottom;
    private final Set<UUID> oldDrops = new HashSet<>();
    private final Map<UUID, Integer> recoveryDrops = new HashMap<>();
    private int collected, recoveryCount;

    private AbstractContainerMenu menu;
    private Screen menuScreen;
    private InventoryTransfer transfer;
    private InventoryLoadout.Move confirmingMove;
    private ItemStack expectedFrom = ItemStack.EMPTY, expectedTo = ItemStack.EMPTY, expectedCursor = ItemStack.EMPTY;
    private boolean awaitingSync, synced, takingBox, opening, sendingClick;
    private boolean finalSync;
    private int syncTick, corrections;

    /** Desired counts are inventory targets, not additional amounts. Never discards items or boxes. */
    public PrinterRestock(Map<Item, Integer> desired, List<AABB> buildBounds, boolean searchEchest,
                          int maxShulkers, int searchRadius, boolean rotate, int restockClearance) {
        if (desired == null || buildBounds == null || maxShulkers < 1 || maxShulkers > 36
            || searchRadius < 1 || searchRadius > 128 || restockClearance < 0 || restockClearance > 128)
            throw new IllegalArgumentException("Invalid printer restock request.");
        var targets = new LinkedHashMap<Item, Integer>();
        desired.forEach((item, count) -> {
            if (item == null || count == null || count < 0 || count > 2304) throw new IllegalArgumentException("Invalid material target.");
            if (count > 0) targets.put(item, count);
        });
        this.desired = Map.copyOf(targets);
        this.buildBounds = List.copyOf(buildBounds);
        this.searchEchest = searchEchest;
        this.maxShulkers = maxShulkers;
        this.searchRadius = searchRadius;
        this.rotate = rotate;
        clearance = restockClearance;
        world = mc.level;
        player = mc.player;
        if (world == null || mc.player == null) fail("Join a world before restocking.");
        else for (Item item : this.desired.keySet()) startingCounts.put(item, itemCount(player.getInventory(), item));
    }

    public Vec3 destination() { return destination; }
    public boolean needsGround() { return destination != null && !isDone(); }
    public boolean isDone() { return stage == Stage.Done; }
    public String error() { return error; }
    public String status() { return error == null ? status : error; }

    /** Try another unloaded/unreachable candidate only before opening/placing anything at this site. */
    public boolean navigationFailed() {
        if (cancelled || !context() || containerPos == null || owned || placementSent || trackingDrop || opening
            || transfer != null || awaitingSync || menu != null && !menu.getCarried().isEmpty()) {
            fail("Cannot safely reach the supply site. " + recoveryWarning());
            return false;
        }
        epoch++;
        queued = false;
        if (borrowed) {
            rejectedChests.add(containerPos);
            resetContainer();
            stage = Stage.Choose;
            status = "Trying another accessible ender chest";
        } else {
            rejectedSites.add(containerPos);
            clearMenu();
            beginSiteSearch();
        }
        return true;
    }

    public boolean ownsMenu() {
        if (!context()) return false;
        return menu != null && mc.player.containerMenu == menu && mc.gui.screen() == menuScreen
            || opening && expectedStorage(mc.player.containerMenu);
    }

    /** Manual/safety pauses must invalidate callbacks before the root releases player controls. */
    public void pause() {
        suspended = true;
        epoch++;
        queued = false;
        if (transfer != null) transfer.cancel();
        transfer = null;
        if (context() && stage == Stage.Break && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
    }

    public void resume() {
        if (cancelled) return;
        if (!context()) { fail("Restock belongs to another world. " + recoveryWarning()); return; }
        suspended = false;
        error = null;
        stalledTicks = corrections = 0;
        openTicks = opening ? mc.player.tickCount : 0;
        epoch++;
        queued = false;
        if (stage == Stage.FindSite) { rejectedSites.clear(); beginSiteSearch(); }
        if (stage == Stage.Choose) { chestVisited = false; rejectedChests.clear(); visitedBoxes.clear(); }
        // A manually closed menu is never clicked again. Reopen our known container instead.
        if (menu != null && (mc.player.containerMenu != menu || mc.gui.screen() != menuScreen)) {
            clearMenu();
            if (stage == Stage.Transfer) stage = Stage.Open;
        }
        if (menu != null) requestSync();
    }

    /** Stops immediately, leaving a real cursor stack to vanilla/the user; never clicks after cancellation. */
    public String cancel() {
        pause();
        cancelled = true;
        return recoveryWarning();
    }

    private String recoveryWarning() {
        return containerPos != null && (owned || placementSent || trackingDrop)
            ? (owned ? "Recover your supply container or drop at " : "Check the unconfirmed supply placement at ") + containerPos.toShortString() + " before leaving."
            : "";
    }

    public void tick() {
        if (cancelled || suspended || error != null || isDone()) return;
        if (!context()) { fail("World changed. " + recoveryWarning()); return; }
        if (menu != null && (mc.player.containerMenu != menu || mc.gui.screen() != menuScreen)) {
            fail("Supply inventory changed. Close the other screen, then Resume. " + recoveryWarning());
            return;
        }
        if (mc.player.isUsingItem()) { status = "Waiting for item use to finish"; return; }
        if (awaitingSync) {
            if (mc.player.tickCount - syncTick > 120) fail("Server did not synchronize the supply inventory. Items preserved; Resume to retry.");
            return;
        }
        if (stage == Stage.Choose) { choose(); return; }
        if (stage == Stage.FindSite) { findSite(); return; }
        if (!arrived()) { status = stage == Stage.Collect ? "Walking to the supply drop" : "Landing at the supply site"; return; }
        if (++stalledTicks > 600) { fail("Restocking made no progress. Check the supply site, then Resume. " + recoveryWarning()); return; }
        if (transfer != null) { tickTransfer(); return; }
        if (actionDelay > 0) { actionDelay--; return; }
        switch (stage) {
            case Place -> place();
            case Open -> open();
            case Transfer -> transferSupplies();
            case Break -> recoverBlock();
            case Collect -> collect();
            default -> { }
        }
    }

    private boolean context() { return world != null && mc.level == world && player != null && mc.player == player && player.isAlive() && mc.gameMode != null; }

    private boolean arrived() {
        return destination != null && mc.player.onGround() && !mc.player.isFallFlying()
            && Math.abs(mc.player.getY() - destination.y) < 0.3
            && Math.hypot(mc.player.getX() - destination.x, mc.player.getZ() - destination.z) < 0.4;
    }

    private void choose() {
        if (finalSync) {
            if (!readyPlayerInventory()) return;
            finalSync = false;
        }
        if (fulfilled(mc.player.getInventory(), desired)) { complete(false); return; }
        if (afterRecoveryError != null) { String message = afterRecoveryError; afterRecoveryError = null; partialOrFail(message); return; }
        int box = findCarriedBox();
        if (box >= 0) {
            container = mc.player.getInventory().getItem(box).copyWithCount(1);
            beginSiteSearch();
            return;
        }
        if (!searchEchest || chestVisited) {
            partialOrFail("No carried shulker has the missing materials" + (searchEchest ? "." : "; ender-chest search is off."));
            return;
        }
        BlockPos nearest = null;
        Vec3 nearestStance = null;
        double best = Double.MAX_VALUE;
        for (var entity : Utils.blockEntities()) {
            if (!(entity instanceof EnderChestBlockEntity) || rejectedChests.contains(entity.getBlockPos())) continue;
            BlockPos pos = entity.getBlockPos();
            double distance = pos.distToCenterSqr(mc.player.position());
            if (distance > searchRadius * searchRadius || distance >= best || !outsideBuild(pos, buildBounds, clearance) || !clearAbove(pos)) continue;
            Vec3 stance = supplyStance(pos, false);
            if (stance == null) continue;
            best = distance;
            nearest = pos.immutable();
            nearestStance = stance;
        }
        container = new ItemStack(Items.ENDER_CHEST);
        if (nearest != null) {
            containerPos = nearest;
            destination = nearestStance;
            borrowed = true;
            stage = Stage.Open;
            status = "Visiting a nearby ender chest";
        } else if (InvUtils.find(Items.ENDER_CHEST).found()) beginSiteSearch();
        else partialOrFail("No usable nearby ender chest and none carried. Add a chest or the missing materials.");
    }

    private int findCarriedBox() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (usefulBox(stack) && visitedBoxes.stream().noneMatch(previous -> ItemStack.isSameItemSameComponents(previous, stack))) return i;
        }
        return -1;
    }

    private boolean usefulBox(ItemStack stack) {
        if (!Utils.isShulker(stack.getItem())) return false;
        ItemStack[] contents = new ItemStack[27];
        Utils.getItemsInContainerItem(stack, contents);
        for (ItemStack item : contents) if (missing(mc.player.getInventory(), desired, item.getItem()) > 0 && !item.isEmpty()) return true;
        return false;
    }

    private void beginSiteSearch() {
        borrowed = owned = placementSent = false;
        containerPos = null;
        destination = null;
        stage = Stage.FindSite;
        status = "Finding an off-build supply landing";
        BlockPos center = mc.player.blockPosition();
        siteTop = Math.min(world.getMinY() + world.getHeight() - 3, center.getY() + 8);
        siteBottom = Math.max(world.getMinY() + 1, center.getY() - searchRadius);
        siteColumns = new ArrayList<>();
        for (int x = -searchRadius; x <= searchRadius; x++) for (int z = -searchRadius; z <= searchRadius; z++) {
            if (x * x + z * z <= searchRadius * searchRadius) siteColumns.add(new BlockPos(center.getX() + x, 0, center.getZ() + z));
        }
        siteColumns.sort(Comparator.comparingDouble(pos -> (double) (pos.getX() - center.getX()) * (pos.getX() - center.getX())
            + (double) (pos.getZ() - center.getZ()) * (pos.getZ() - center.getZ())));
        siteIndex = 0;
    }

    private void findSite() {
        // ponytail: bounded loaded-column scan, 64 columns/tick; no world reads on a background thread.
        for (int scanned = 0; scanned < 64 && siteIndex < siteColumns.size(); scanned++) {
            BlockPos column = siteColumns.get(siteIndex++);
            if (!world.hasChunkAt(column)) continue;
            for (int y = siteTop; y >= siteBottom; y--) {
                BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
                if (rejectedSites.contains(pos) || !outsideBuild(pos, buildBounds, clearance) || !world.getBlockState(pos).isAir() || !clearAbove(pos) || !safeFeet(pos)
                    || BlockUtils.isClickable(world.getBlockState(pos.below()).getBlock())) continue;
                Vec3 stance = supplyStance(pos, true);
                if (stance == null) continue;
                containerPos = pos;
                destination = stance;
                siteColumns = null;
                stage = Stage.Place;
                stalledTicks = 0;
                return;
            }
        }
        if (siteIndex >= siteColumns.size()) fail("No supported, clear restock site outside the build buffer within " + searchRadius + " blocks. Move near a safe landing and Resume.");
    }

    /** Chest and feet positions remain separate: never navigate into an existing chest's collision box. */
    private Vec3 supplyStance(BlockPos pos, boolean placing) {
        if (placing && !outsideBuild(pos, buildBounds, clearance)) return null;
        Vec3 best = null;
        double distance = Double.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos feet = pos.relative(direction);
            if (!outsideBuild(feet, buildBounds, clearance) || !safeFeet(feet) || !safeFeet(feet.relative(direction))) continue;
            Vec3 candidate = Vec3.atBottomCenterOf(feet);
            Vec3 eye = candidate.add(0, mc.player.getEyeHeight(), 0);
            BlockHitResult hit = world.clip(new ClipContext(eye, Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS && !hit.getBlockPos().equals(pos)) continue;
            double current = candidate.distanceToSqr(mc.player.position());
            if (current < distance) { distance = current; best = candidate; }
        }
        return best;
    }

    private boolean clearAbove(BlockPos pos) { return world.getBlockState(pos.above()).isAir() && world.getBlockState(pos.above(2)).isAir(); }

    private boolean safeFeet(BlockPos feet) {
        if (!world.hasChunkAt(feet) || !world.getWorldBorder().isWithinBounds(feet)) return false;
        BlockState floor = world.getBlockState(feet.below());
        if (!floor.getFluidState().isEmpty() || floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.CACTUS)
            || floor.is(Blocks.CAMPFIRE) || floor.is(Blocks.SOUL_CAMPFIRE)
            || !Block.isShapeFullBlock(floor.getCollisionShape(world, feet.below()))) return false;
        for (int y = 0; y < 3; y++) {
            BlockState state = world.getBlockState(feet.above(y));
            if (!state.getFluidState().isEmpty() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.POWDER_SNOW)) return false;
        }
        return world.noCollision(mc.player, new AABB(feet).deflate(0.19, 0, 0.19).expandTowards(0, 1, 0));
    }

    private void place() {
        status = "Placing the supply container";
        if (owned) { clearMenu(); stage = Stage.Open; return; }
        if (placementSent) return; // Ownership requires a server block update/sequence acknowledgment.
        if (!readyPlayerInventory()) return;
        if (!outsideBuild(containerPos, buildBounds, clearance) || !safeFeet(containerPos) || !clearAbove(containerPos)
            || !world.getBlockState(containerPos).isAir() || BlockUtils.isClickable(world.getBlockState(containerPos.below()).getBlock())) {
            fail("Supply site changed; existing blocks will not be replaced."); return;
        }
        int carriedContainer = carriedSlot(container);
        if (emptySlots(mc.player.getInventory()) == 0 && (carriedContainer < 0 || mc.player.getInventory().getItem(carriedContainer).getCount() > 1)) {
            partialOrFail("Free one inventory slot for supply-container recovery, then Resume. Nothing was discarded."); return;
        }
        int hotbar = ensureHotbar(container);
        if (hotbar < 0) return;
        runAimed(Stage.Place, Vec3.atCenterOf(containerPos), () -> {
            if (!world.getBlockState(containerPos).isAir() || !ItemStack.isSameItemSameComponents(container, mc.player.getInventory().getItem(hotbar))) return;
            InvUtils.swap(hotbar, false);
            BlockPos floor = containerPos.below();
            placementInventoryBefore = countExact(container);
            placedBlockConfirmed = placedItemConfirmed = false;
            sendingPlace = true;
            try {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
            } finally { sendingPlace = false; }
            if (placementSent) requestSync();
            actionDelay = 5;
        });
    }

    private void open() {
        status = borrowed ? "Opening the existing ender chest" : "Opening supplies";
        if (opening && expectedStorage(mc.player.containerMenu)) {
            bindMenu(mc.player.containerMenu);
            opening = false;
            stage = Stage.Transfer;
            if (container.is(Items.ENDER_CHEST)) chestVisited = true;
            stalledTicks = 0;
            return;
        }
        if (opening && mc.player.tickCount - openTicks > 80) {
            opening = false;
            if (borrowed) {
                rejectedChests.add(containerPos);
                resetContainer();
                stage = Stage.Choose;
            } else fail("The placed supply container did not open. Check access/permissions, then Resume. " + recoveryWarning());
            return;
        }
        if (!readyPlayerInventory()) return;
        BlockState state = world.getBlockState(containerPos);
        if (!(container.getItem() instanceof BlockItem item) || !state.is(item.getBlock())) {
            if (borrowed) { rejectedChests.add(containerPos); resetContainer(); stage = Stage.Choose; }
            else fail("The placed supply container is missing. " + recoveryWarning());
            return;
        }
        if (!clearAbove(containerPos)) { fail("The supply container's opening space is blocked. " + recoveryWarning()); return; }
        runAimed(Stage.Open, Vec3.atCenterOf(containerPos), () -> {
            clearMenu();
            if (!opening) openTicks = mc.player.tickCount;
            opening = true;
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(containerPos), Direction.UP, containerPos, false));
            actionDelay = 5;
        });
    }

    private boolean expectedStorage(AbstractContainerMenu candidate) {
        return container.is(Items.ENDER_CHEST) ? candidate instanceof ChestMenu : candidate instanceof ShulkerBoxMenu;
    }

    private void transferSupplies() {
        status = "Taking missing schematic materials";
        if (!readyCursor()) return;
        if (menu instanceof ChestMenu chest) EChestMemory.remember(chest.getContainer());
        int reserve = owned ? 1 : 0;
        // Boxes first, while preserving room both for our chest and for unpacking a box afterward.
        if (container.is(Items.ENDER_CHEST) && shulkersTaken < maxShulkers) {
            for (int from = 0; from < menu.slots.size(); from++) {
                Slot source = menu.getSlot(from);
                if (source.container == mc.player.getInventory() || !usefulBox(source.getItem())) continue;
                ItemStack incoming = source.getItem().copyWithCount(1);
                int to = HighwayBuilder.restockTransferSlot(menu.slots, mc.player.getInventory(), incoming, reserve + 1);
                if (to >= 0) { beginMove(new InventoryLoadout.Move(from, to, 1), true); return; }
            }
        }
        InventoryLoadout.Move move = materialMove(menu.slots, mc.player.getInventory(), desired, reserve + (shulkersTaken > 0 ? 1 : 0));
        if (move != null) { beginMove(move, false); return; }
        boolean usefulButFull = menu.slots.stream().anyMatch(source -> source.container != mc.player.getInventory()
            && source.hasItem() && missing(mc.player.getInventory(), desired, source.getItem().getItem()) > 0);
        if (usefulButFull && (!container.is(Items.ENDER_CHEST) || shulkersTaken == 0) && !fulfilled(mc.player.getInventory(), desired))
            afterRecoveryError = "Inventory is full of protected items. Free material space, then Resume; nothing was discarded.";
        if (Utils.isShulker(container.getItem())) visitedBoxes.add(container.copy());
        closeSupplyMenu();
        if (borrowed) {
            resetContainer();
            stage = Stage.Choose;
            finalSync = true;
        }
        else stage = Stage.Break;
    }

    private void recoverBlock() {
        status = "Recovering our supply container";
        if (!mayRecover(borrowed, owned)) { fail("Existing containers are never mined by Printer Helper."); return; }
        if (!readyPlayerInventory()) return;
        BlockState state = world.getBlockState(containerPos);
        if (state.isAir() && trackingDrop) {
            if (!breakConfirmed) return;
            clearMenu();
            stage = Stage.Collect;
            destination = Vec3.atBottomCenterOf(containerPos);
            return;
        }
        if (!(container.getItem() instanceof BlockItem item) || !state.is(item.getBlock())) { fail("Our supply block changed; it will not be mined. " + recoveryWarning()); return; }
        if (emptySlots(mc.player.getInventory()) < 1) { fail("Free one slot to recover the supply container. It remains placed at " + containerPos.toShortString() + "."); return; }
        int tool = recoveryTool(state);
        if (tool < 0) { fail("Add a usable pickaxe to recover the supply container. " + recoveryWarning()); return; }
        ItemStack pick = mc.player.getInventory().getItem(tool).copy();
        int hotbar = ensureHotbar(pick);
        if (hotbar < 0) return;
        boolean silk = Utils.hasEnchantment(pick, Enchantments.SILK_TOUCH);
        recoveryItem = container.is(Items.ENDER_CHEST) ? new ItemStack(silk ? Items.ENDER_CHEST : Items.OBSIDIAN) : container.copy();
        recoveryCount = container.is(Items.ENDER_CHEST) && !silk ? 8 : 1;
        if (!trackingDrop) {
            trackingDrop = true;
            for (ItemEntity drop : world.getEntitiesOfClass(ItemEntity.class, new AABB(containerPos).inflate(6))) oldDrops.add(drop.getUUID());
        }
        runAimed(Stage.Break, Vec3.atCenterOf(containerPos), () -> {
            if (!mayRecover(borrowed, owned) || !world.getBlockState(containerPos).equals(state)
                || !ItemStack.isSameItemSameComponents(pick, mc.player.getInventory().getItem(hotbar))) return;
            InvUtils.swap(hotbar, false);
            sendingMine = true;
            try { BlockUtils.breakBlock(containerPos, true); }
            finally { sendingMine = false; }
        });
    }

    private int recoveryTool(BlockState state) {
        int best = -1;
        double score = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.is(ItemTags.PICKAXES) || stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= 1
                || state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) continue;
            double current = stack.getDestroySpeed(state) + (state.is(Blocks.ENDER_CHEST) && Utils.hasEnchantment(stack, Enchantments.SILK_TOUCH) ? 1000 : 0);
            if (current > score) { score = current; best = i; }
        }
        return best;
    }

    private void collect() {
        status = "Collecting our supply drop";
        boolean present = false;
        for (ItemEntity drop : world.getEntitiesOfClass(ItemEntity.class, new AABB(containerPos).inflate(6))) {
            if (!trackDrop(drop)) continue;
            present = true;
            BlockPos feet = drop.blockPosition();
            if (safeFeet(feet) && outsideBuild(feet, buildBounds, clearance)) destination = Vec3.atBottomCenterOf(feet);
        }
        for (var entry : recoveryDrops.entrySet()) {
            if (world.getEntity(entry.getValue()) instanceof ItemEntity drop && drop.getUUID().equals(entry.getKey())) present = true;
        }
        if (takenByOther) { fail("Another player collected the supply drop. Recover it manually before stopping this restock. " + recoveryWarning()); return; }
        // A disappearance alone is not recovery; require the server's local-player pickup message too.
        if (HighwayBuilder.supplyPickupComplete(collected, recoveryCount, present, takenByOther)) {
            resetContainer();
            stage = Stage.Choose;
            finalSync = true;
            return;
        }
        if (!world.getBlockState(containerPos).isAir()) {
            if (collected > 0) { fail("Server restored the supply block after pickup. Check it manually. " + recoveryWarning()); return; }
            Vec3 stance = supplyStance(containerPos, false);
            if (stance == null) { fail("Cannot approach the restored supply block. " + recoveryWarning()); return; }
            destination = stance;
            stage = Stage.Break;
            breakConfirmed = false;
        }
    }

    private boolean trackDrop(ItemEntity drop) {
        if (!HighwayBuilder.sameSupplyDrop(recoveryItem, drop.getItem()) || oldDrops.contains(drop.getUUID())) return false;
        if (!recoveryDrops.containsKey(drop.getUUID()) && !new AABB(containerPos).inflate(6).intersects(drop.getBoundingBox())) return false;
        // A shulker is one entity; nearby same-name boxes are not interchangeable once ours is observed.
        if (Utils.isShulker(recoveryItem.getItem()) && !recoveryDrops.isEmpty() && !recoveryDrops.containsKey(drop.getUUID())) return false;
        if (recoveryDrops.put(drop.getUUID(), drop.getId()) == null && Utils.isShulker(recoveryItem.getItem()))
            visitedBoxes.add(drop.getItem().copyWithCount(1));
        return true;
    }

    public void onSupplyItemPickup(int itemId, int collectorId, int amount) {
        if (cancelled || !context() || !trackingDrop || !breakConfirmed && !world.getBlockState(containerPos).isAir()
            || !(world.getEntity(itemId) instanceof ItemEntity drop) || !trackDrop(drop)) return;
        boolean local = collectorId == mc.player.getId();
        if (!local && amount > 0) takenByOther = true;
        collected += HighwayBuilder.supplyPickupAmount(local, amount, drop.getItem().getCount());
        if (local && amount > 0) stalledTicks = 0;
    }

    private boolean readyPlayerInventory() {
        if (mc.player.containerMenu != mc.player.inventoryMenu || mc.gui.screen() != null) {
            fail("Close the unrelated screen, then Resume. " + recoveryWarning());
            return false;
        }
        if (menu == null) { bindMenu(mc.player.inventoryMenu); return false; }
        return readyCursor();
    }

    private boolean readyCursor() {
        if (!synced || !ItemStack.matches(expectedCursor, menu.getCarried())) { requestSync(); return false; }
        if (menu.getCarried().isEmpty()) return true;
        int destination = InventoryLoadout.cursorDestination(menu.slots, mc.player.getInventory(), menu.getCarried());
        if (destination < 0) { fail("No safe player slot for the inventory cursor. Put it away, then Resume; nothing was dropped."); return false; }
        click(destination, 0);
        requestSync();
        return false;
    }

    private void bindMenu(AbstractContainerMenu next) {
        menu = next;
        menuScreen = mc.gui.screen();
        requestSync();
    }

    private int ensureHotbar(ItemStack wanted) {
        int source = carriedSlot(wanted);
        if (source < 0) { fail("The selected supply item or tool is no longer in your inventory. " + recoveryWarning()); return -1; }
        if (source < 9) return source;
        int target = mc.player.getInventory().getSelectedSlot();
        if (mc.player.getInventory().getItem(target).has(DataComponents.BUNDLE_CONTENTS)) {
            target = -1;
            for (int i = 0; i < 9; i++) if (!mc.player.getInventory().getItem(i).has(DataComponents.BUNDLE_CONTENTS)) { target = i; break; }
            if (target < 0) { fail("Free a hotbar slot; bundles cannot be automatically swapped safely."); return -1; }
        }
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).isEmpty()) { target = i; break; }
        int from = menuSlot(menu.slots, mc.player.getInventory(), source), to = menuSlot(menu.slots, mc.player.getInventory(), target);
        beginMove(new InventoryLoadout.Move(from, to, mc.player.getInventory().getItem(source).getCount()), false);
        return -1;
    }

    private int carriedSlot(ItemStack wanted) {
        for (int i = 0; i < 36; i++) if (ItemStack.isSameItemSameComponents(wanted, mc.player.getInventory().getItem(i))) return i;
        return -1;
    }

    private int countExact(ItemStack wanted) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (ItemStack.isSameItemSameComponents(wanted, stack)) count += stack.getCount();
        }
        return count;
    }

    private void beginMove(InventoryLoadout.Move move, boolean box) {
        confirmingMove = move;
        takingBox = box;
        transfer = new InventoryTransfer(menu, move, mc.player);
        tickTransfer();
    }

    private void tickTransfer() {
        if (!arrived()) return;
        InventoryTransfer running = transfer;
        boolean done = running.tick(4, this::click);
        if (running.failed()) { fail(running.error() + " " + recoveryWarning()); return; }
        if (!done) return;
        expectedFrom = menu.getSlot(confirmingMove.from()).getItem().copy();
        expectedTo = menu.getSlot(confirmingMove.to()).getItem().copy();
        transfer = null;
        requestSync();
    }

    private void click(int slot, int button) {
        if (cancelled || suspended || error != null || menu == null || mc.player.containerMenu != menu || mc.gui.screen() != menuScreen) return;
        sendingClick = true;
        try { mc.gameMode.handleContainerInput(menu.containerId, slot, button, ContainerInput.PICKUP, mc.player); }
        finally { sendingClick = false; }
    }

    private void requestSync() {
        if (cancelled || suspended || error != null || !context() || menu == null) return;
        synced = false;
        awaitingSync = true;
        syncTick = mc.player.tickCount;
        expectedCursor = menu.getCarried().copy();
        sendingClick = true;
        try {
            // No-op slot -1 and stale revision ask for full server contents; never outside/drop slot -999.
            mc.getConnection().send(new ServerboundContainerClickPacket(menu.containerId, -1, (short) -1, (byte) 0,
                ContainerInput.PICKUP, it.unimi.dsi.fastutil.ints.Int2ObjectMaps.emptyMap(),
                HashedStack.create(menu.getCarried(), mc.getConnection().decoratedHashOpsGenenerator())));
        } finally { sendingClick = false; }
    }

    public void onInventory(InventoryEvent event) {
        if (cancelled || !context() || menu == null || mc.player.containerMenu != menu || !awaitingSync || event.packet.containerId() != menu.containerId) return;
        awaitingSync = false;
        synced = true;
        boolean corrected = !ItemStack.matches(expectedCursor, menu.getCarried())
            || confirmingMove != null && (!ItemStack.matches(expectedFrom, menu.getSlot(confirmingMove.from()).getItem())
                || !ItemStack.matches(expectedTo, menu.getSlot(confirmingMove.to()).getItem()));
        expectedCursor = menu.getCarried().copy();
        if (corrected) {
            if (++corrections >= 3) fail("Server repeatedly rejected inventory changes. Items preserved; Resume to retry.");
        } else {
            corrections = 0;
            if (confirmingMove != null) { stalledTicks = 0; if (takingBox) shulkersTaken++; }
        }
        confirmingMove = null;
        takingBox = false;
        if (stage == Stage.Place && placementSent) {
            placedItemConfirmed = countExact(container) == placementInventoryBefore - 1;
            confirmOwnership();
        }
    }

    private void closeSupplyMenu() {
        Screen screen = menuScreen;
        clearMenu();
        if (screen != null && mc.gui.screen() == screen) screen.onClose();
    }

    private void clearMenu() {
        if (transfer != null) transfer.cancel();
        transfer = null;
        menu = null;
        menuScreen = null;
        awaitingSync = synced = false;
        confirmingMove = null;
        takingBox = false;
    }

    private void runAimed(Stage expectedStage, Vec3 target, Runnable action) {
        if (queued) return;
        if (target.distanceToSqr(mc.player.getEyePosition()) > mc.player.blockInteractionRange() * mc.player.blockInteractionRange()) {
            fail("Supply container is outside interaction reach. Move closer, then Resume. " + recoveryWarning());
            return;
        }
        int expectedEpoch = epoch;
        queued = true;
        Runnable guarded = () -> {
            if (cancelled || suspended || error != null || !context() || epoch != expectedEpoch || stage != expectedStage) return;
            queued = false;
            if (!arrived() || mc.player.isUsingItem() || mc.player.containerMenu != mc.player.inventoryMenu || mc.gui.screen() != null) return;
            if (target.distanceToSqr(mc.player.getEyePosition()) > mc.player.blockInteractionRange() * mc.player.blockInteractionRange()) return;
            action.run();
        };
        if (rotate) Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target), guarded);
        else guarded.run();
    }

    public void onPacketSent(PacketEvent.Sent event) {
        if (cancelled || !context()) return;
        if (sendingPlace && event.packet instanceof ServerboundUseItemOnPacket packet) {
            placementSent = true;
            placeSequence = packet.getSequence();
        } else if (sendingMine && event.packet instanceof ServerboundPlayerActionPacket packet
            && (packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK || packet.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)) {
            mineSequence = packet.getSequence();
        } else if (!sendingClick && menu != null && event.packet instanceof ServerboundContainerClickPacket) {
            fail("Another inventory action interrupted restocking. Resume after finishing it. " + recoveryWarning());
        }
    }

    public void onServerBlockUpdate(BlockPos pos, BlockState state) {
        if (cancelled || !context() || containerPos == null || !containerPos.equals(pos)) return;
        if (placementSent && container.getItem() instanceof BlockItem item && state.is(item.getBlock())) {
            placedBlockConfirmed = true;
            confirmOwnership();
        }
        if (trackingDrop && mineSequence >= 0 && state.isAir()) { breakConfirmed = true; stalledTicks = 0; }
    }

    public void onServerBlockAck(int sequence) {
        if (cancelled || !context() || containerPos == null) return;
        BlockState state = world.getBlockState(containerPos);
        if (placementSent && placeSequence >= 0 && sequence >= placeSequence) {
            placeSequence = -1;
            if (container.getItem() instanceof BlockItem item && state.is(item.getBlock())) {
                placedBlockConfirmed = true;
                confirmOwnership();
            }
            else { placementSent = false; if (menu != null && !awaitingSync) requestSync(); }
        }
        if (trackingDrop && mineSequence >= 0 && sequence >= mineSequence && state.isAir()) { breakConfirmed = true; stalledTicks = 0; }
    }

    private void confirmOwnership() {
        // A matching block alone could be somebody else's concurrent placement. Require our consumed item too.
        if (placementOwned(borrowed, placedBlockConfirmed, placedItemConfirmed)) { owned = true; stalledTicks = 0; }
    }

    private void resetContainer() {
        clearMenu();
        borrowed = owned = placementSent = opening = trackingDrop = breakConfirmed = takenByOther = false;
        placedBlockConfirmed = placedItemConfirmed = false;
        placeSequence = mineSequence = -1;
        container = recoveryItem = ItemStack.EMPTY;
        containerPos = null;
        destination = null;
        oldDrops.clear();
        recoveryDrops.clear();
        collected = recoveryCount = stalledTicks = openTicks = 0;
    }

    private void fail(String message) { error = message; pause(); }

    private void partialOrFail(String message) {
        if (madeProgress(mc.player.getInventory(), startingCounts)) complete(true);
        else fail(message);
    }

    private void complete(boolean partial) {
        if (owned || placementSent || trackingDrop || transfer != null || awaitingSync
            || mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()) {
            fail("Finish supply recovery and put the cursor item away before resuming the build. " + recoveryWarning());
            return;
        }
        clearMenu();
        stage = Stage.Done;
        destination = null;
        status = partial ? "Supplies partially refilled; building to free room" : "Supplies ready";
    }

    static boolean mayRecover(boolean borrowed, boolean owned) { return owned && !borrowed; }

    static boolean placementOwned(boolean borrowed, boolean blockConfirmed, boolean itemConfirmed) { return !borrowed && blockConfirmed && itemConfirmed; }

    static int missing(Container inventory, Map<Item, Integer> desired, Item item) {
        return Math.max(0, desired.getOrDefault(item, 0) - itemCount(inventory, item));
    }

    static boolean madeProgress(Container inventory, Map<Item, Integer> startingCounts) {
        for (var entry : startingCounts.entrySet()) if (itemCount(inventory, entry.getKey()) > entry.getValue()) return true;
        return false;
    }

    private static int itemCount(Container inventory, Item item) {
        int count = 0;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) if (inventory.getItem(i).is(item)) count += inventory.getItem(i).getCount();
        return count;
    }

    static boolean fulfilled(Container inventory, Map<Item, Integer> desired) {
        for (Item item : desired.keySet()) if (missing(inventory, desired, item) > 0) return false;
        return true;
    }

    static InventoryLoadout.Move materialMove(List<Slot> slots, Container inventory, Map<Item, Integer> desired, int reserve) {
        for (int from = 0; from < slots.size(); from++) {
            Slot source = slots.get(from);
            if (source.container == inventory || !source.isActive() || source.isFake() || !source.hasItem()
                || source.getItem().has(DataComponents.BUNDLE_CONTENTS)) continue;
            ItemStack incoming = source.getItem();
            int needed = missing(inventory, desired, incoming.getItem());
            if (needed <= 0) continue;
            int to = HighwayBuilder.restockTransferSlot(slots, inventory, incoming, reserve);
            if (to < 0) continue;
            int room = Math.min(slots.get(to).getMaxStackSize(incoming), incoming.getMaxStackSize()) - slots.get(to).getItem().getCount();
            if (room > 0) return new InventoryLoadout.Move(from, to, Math.min(needed, Math.min(room, incoming.getCount())));
        }
        return null;
    }

    static boolean outsideBuild(BlockPos position, List<AABB> bounds, int clearance) {
        AABB cell = new AABB(position);
        for (AABB bound : bounds) if (bound.inflate(clearance).intersects(cell)) return false;
        return true;
    }

    private static int emptySlots(Container inventory) {
        int count = 0;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) if (inventory.getItem(i).isEmpty()) count++;
        return count;
    }

    private static int menuSlot(List<Slot> slots, Container inventory, int index) {
        for (int i = 0; i < slots.size(); i++) if (slots.get(i).container == inventory && slots.get(i).getContainerSlot() == index) return i;
        return -1;
    }
}
