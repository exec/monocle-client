package dev.monocle.client.systems.modules.world;

import dev.monocle.client.systems.modules.misc.InventoryTweaks;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.HashedStack;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Run with ./gradlew highwaySupplyCheck. Exercises production pickup and cursor recovery policies. */
public final class HighwaySupplyTest {
    public static void main(String[] args) throws Exception {
        boolean assertionsEnabled = false;
        assert assertionsEnabled = true;
        if (!assertionsEnabled) throw new IllegalStateException("Run with assertions enabled (-ea).");
        miningClassification();
        assert HighwayBuilder.fallbackToolSlot(-1,false,slot->slot==3)==3;
        assert HighwayBuilder.fallbackToolSlot(-1,true,slot->true)==-1;
        assert HighwayBuilder.fallbackToolSlot(2,false,slot->true)==2;
        assert HighwayBuilder.fallbackToolSlot(-1,false,slot->false)==-1;
        worldRecovery();
        lifecycleRecovery();
        crewRetreatRecovery();
        supplyPlacementClearance();
        var actualFeet = new net.minecraft.world.phys.Vec3(-49999.64178544834, 116, .4579810321312172);
        var padded = dev.monocle.client.utils.world.PrinterFlight.body(actualFeet, .72, 1.8);
        assert Math.floor(padded.minX) == -50001 : "Reproduce the live off-center front-edge failure";
        assert Math.floor(HighwayBuilder.crewTravelFooting(padded).minX) == -50000 : "Require floor beneath the body, not its collision padding";
        assert Math.floor(HighwayBuilder.crewTravelFooting(padded.move(-.2, 0, 0)).minX) == -50001 : "Real unsupported footing is still rejected";
        for (int[] direction : new int[][] {{1,0},{-1,0},{0,1},{0,-1}}) for (int lane = -2; lane <= 2; lane++) {
            var pair = HighwayPlan.laneSupplyLayout(direction[0], direction[1], 5, lane);
            assert pair.positions().size() == 2;
            for (var cell : pair.positions()) assert Math.abs(lane + cell.x() * direction[1] - cell.z() * direction[0]) <= 2
                : "Both ender chests stay inside the five-wide pavement, including outer lanes";
        }
        forecasts();
        for (boolean current : List.of(false, true)) for (boolean work : List.of(false, true)) for (boolean away : List.of(false, true))
            assert HighwayBuilder.crewWorldChunksReady(current, work, away) == (current && (work || away));
        assert HighwayBuilder.crewRepairRows(20, 0, 14).equals(List.of(20, 19, 18, 17, 16, 15))
            : "A reduced crew must reclaim every potentially orphaned lane row";
        assert HighwayBuilder.crewRepairRows(0, 0, 0).isEmpty();
        assert HighwayBuilder.crewRepairRows(20, 0, 2).equals(List.of(20, 19, 18));
        assert HighwayBuilder.supplyReservationChanged(new net.minecraft.core.BlockPos(0, 116, 10), new net.minecraft.core.BlockPos(0, 116, 11));
        assert !HighwayBuilder.supplyReservationChanged(new net.minecraft.core.BlockPos(0, 116, 10), new net.minecraft.core.BlockPos(0, 116, 10));
        assert HighwayBuilder.crewVerificationRepairRow(21, 0, 18, false) == 18
            : "An overshooting returner must reclaim its unfinished row 19, not wait forever at row 21";
        assert HighwayBuilder.crewVerificationRepairRow(21, 0, 18, true) == 21
            : "A peer whose duties are already resolved keeps working in place";
        assert HighwayBuilder.crewVerificationRepairRow(27, 0, 20, false) == 20;
        assert HighwayBuilder.crewVerificationRepairRow(28, 0, 20, false) == 28;
        assert HighwayBuilder.crewVerificationRepairRow(21, 20, 18, false) == 21;
        assert HighwayBuilder.crewVerificationRepairRow(18, 0, 18, false) == 18;
        assert HighwayBuilder.crewVerificationRepairRow(18, 0, 21, false) == 18;
        assert !HighwayBuilder.placementProbeDue(103, 100, -1);
        assert HighwayBuilder.placementProbeDue(104, 100, -1);
        assert !HighwayBuilder.placementProbeDue(105, 100, 104);
        assert HighwayBuilder.placementProbeDue(108, 100, 104);
        for (boolean boat : List.of(false, true)) for (boolean alive : List.of(false, true))
            for (boolean intersects : List.of(false, true)) for (boolean riding : List.of(false, true))
                assert HighwayBuilder.blockingBoatTarget(boat, alive, intersects, riding) == (boat && alive && intersects && !riding);
        for (boolean crew : List.of(false, true)) for (boolean job : List.of(false, true)) for (boolean ending : List.of(false, true)) {
            assert HighwayBuilder.retainCrewOnToggle(crew, job, ending) == (crew && job && !ending)
                : "Only manual off toggles retain bot work; completion/cancellation still really stop it";
        }
        assert !HighwayBuilder.crewFlightLeg(2) && HighwayBuilder.crewFlightLeg(2.01);
        assert HighwayBuilder.crewFlightLeg(1, false) && !HighwayBuilder.crewFlightLeg(.75, false)
            : "A returner flies into its lane instead of landing two blocks early";
        assert HighwayBuilder.crewSupplyFallback(new net.minecraft.core.BlockPos(8, 90, -4), 116, true).equals(new net.minecraft.core.BlockPos(8, 116, -4));
        assert HighwayBuilder.crewSupplyFallback(new net.minecraft.core.BlockPos(8, 90, -4), 116, false) == null
            : "A blocked rear supply route falls back only to the runner's current supported road block";
        assert !HighwayBuilder.crewFlightLeg(1, true) && HighwayBuilder.crewFlightLeg(3, true)
            : "Container staging retains its original landing distance";
        var crewOrigin = new net.minecraft.core.BlockPos(-10, 116, 20);
        for (int[] direction : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int dx = direction[0], dz = direction[1];
            for (int row : new int[] {0, 10, 100, 511, 512, 520}) {
                var feet = net.minecraft.world.phys.Vec3.atBottomCenterOf(crewOrigin.offset(dx * row, 0, dz * row));
                assert HighwayBuilder.crewRejoinRow(crewOrigin, dx, dz, 10, 502, feet) == Math.clamp(row, 10, 511)
                    : "Rejoin at the physical row, respecting the latest checkpoint and fixed job end";
            }
        }
        assert HighwayBuilder.crewFlightLeg(10) && HighwayBuilder.crewFlightLeg(12)
            : "Short supply legs still prefer flight when available";
        assert HighwayBuilder.crewFlightSpeed(1) == 1 && HighwayBuilder.crewFlightSpeed(.8) == .8;
        assert HighwayBuilder.crewFlightSpeed(4) == 1 && HighwayBuilder.crewFlightSpeed(Double.NaN) == 1
            : "Flight retains the shared collision-checked autopilot speed contract";
        var runStart = new net.minecraft.world.phys.Vec3(-10.5, 116, 20.5);
        for (var offset : List.of(new net.minecraft.world.phys.Vec3(10, 0, 0), new net.minecraft.world.phys.Vec3(-10, 0, 0),
            new net.minecraft.world.phys.Vec3(0, 0, 10), new net.minecraft.world.phys.Vec3(0, 0, -10), new net.minecraft.world.phys.Vec3(10, 0, 10))) {
            var velocity = HighwayBuilder.crewRunVelocity(runStart, runStart.add(offset), .72, box -> true);
            assert Math.abs(velocity.length() * 20 - 7) < 1e-9 : "Running is capped at 7 blocks/sec, including diagonals";
            assert velocity.dot(offset) > 0 && velocity.y == 0;
            assert HighwayBuilder.crewRunVelocity(runStart, runStart.add(offset), .72, box -> false).lengthSqr() == 0
                : "A newly blocked route brakes immediately";
        }
        var near = runStart.add(.05, 0, -.05);
        assert runStart.add(HighwayBuilder.crewRunVelocity(runStart, near, .72, box -> true)).distanceTo(near) < 1e-9
            : "The last running step must not overshoot and oscillate across the rendezvous";
        assert HighwayBuilder.crewRunVelocity(runStart, runStart, .72, box -> true).lengthSqr() == 0;
        assert HighwayBuilder.crewRunVelocity(runStart, runStart.add(10, 1, 0), .72, box -> true).y == 0
            : "Ground travel leaves vertical physics to Minecraft";
        for (int bits = 0; bits < 32; bits++) {
            assert HighwayBuilder.restartableSupply((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0, (bits & 16) != 0) == (bits == 0)
                : "Only an entirely uncommitted restock can release its reservation and restart";
        }
        assert !HighwayBuilder.supplyRetryDue(19, 0) && HighwayBuilder.supplyRetryDue(20, 0);
        assert HighwayBuilder.supplyRetryDue(600, 2) && HighwayBuilder.supplyRetryDue(600, 300) : "Changed surroundings remain retryable after the third attempt";
        for (int attempts = 0; attempts <= 3; attempts++) for (boolean world : new boolean[] {false, true})
            for (boolean grounded : new boolean[] {false, true}) for (boolean paused : new boolean[] {false, true}) {
                assert HighwayBuilder.crewRetryAllowed(attempts, world, grounded, paused) == (attempts < 3 && world && grounded && !paused)
                    : "Stall retries are bounded and cannot bypass world, landing or host-pause gates";
            }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(DataComponentInitializers.PendingComponents::apply);
        assert InventoryTweaks.dedicatedSupplyScore(new ItemStack[] {new ItemStack(Items.OBSIDIAN, 64), ItemStack.EMPTY}, s -> s.is(Items.OBSIDIAN)) == 64;
        assert InventoryTweaks.dedicatedSupplyScore(new ItemStack[] {new ItemStack(Items.OBSIDIAN, 64), new ItemStack(Items.NETHERITE_SWORD)}, s -> s.is(Items.OBSIDIAN)) == 0 : "PvP kits remain untouched";
        assert InventoryTweaks.dedicatedSupplyScore(new ItemStack[] {new ItemStack(Items.STONE, 32)}, s -> s.is(Items.STONE)) == 32 : "Paving is not hardcoded to obsidian";
        assert InventoryTweaks.dedicatedSupplyScore(new ItemStack[] {ItemStack.EMPTY}, s -> true) == 0;
        var glider = new ItemStack(Items.ELYTRA);
        assert HighwayBuilder.usableCrewGlider(glider);
        glider.setDamageValue(glider.getMaxDamage() - 10);
        assert !HighwayBuilder.usableCrewGlider(glider);
        glider.setDamageValue(glider.getMaxDamage() - 11);
        assert HighwayBuilder.usableCrewGlider(glider);
        assert !HighwayBuilder.usableCrewGlider(ItemStack.EMPTY) && !HighwayBuilder.usableCrewGlider(new ItemStack(Items.NETHERITE_CHESTPLATE));
        restockLoop();

        ItemStack expected = new ItemStack(Items.DYED_SHULKER_BOX.blue());
        expected.set(DataComponents.CUSTOM_NAME, Component.literal("Highway supplies"));
        expected.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.OBSIDIAN, 32))));
        assert HighwayBuilder.sameSupplyDrop(expected, expected.copy());
        assert !HighwayBuilder.sameSupplyDrop(expected, ItemStack.EMPTY);
        assert !HighwayBuilder.sameSupplyDrop(ItemStack.EMPTY, ItemStack.EMPTY);
        ItemStack otherColor = new ItemStack(Items.DYED_SHULKER_BOX.red());
        otherColor.set(DataComponents.CUSTOM_NAME, expected.get(DataComponents.CUSTOM_NAME));
        otherColor.set(DataComponents.CONTAINER, expected.get(DataComponents.CONTAINER));
        assert !HighwayBuilder.sameSupplyDrop(expected, otherColor) : "Color must match even when name and contents are identical";

        ItemStack anotherKit = expected.copy();
        anotherKit.set(DataComponents.CUSTOM_NAME, Component.literal("Emergency kit"));
        assert !HighwayBuilder.sameSupplyDrop(expected, anotherKit) : "Another named kit cannot confirm pickup";
        anotherKit = expected.copy();
        anotherKit.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.OBSIDIAN, 64))));
        assert HighwayBuilder.sameSupplyDrop(expected, anotherKit) : "Stale pre-restock contents must not hide the matching ground drop";
        anotherKit.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 32))));
        assert HighwayBuilder.sameSupplyDrop(expected, anotherKit) : "Ground identity depends on name and color, not changed contents";

        ItemStack empty = new ItemStack(Items.DYED_SHULKER_BOX.blue());
        ItemStack explicitEmpty = empty.copy();
        explicitEmpty.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        assert HighwayBuilder.sameSupplyDrop(empty, explicitEmpty) : "Absent and empty container components mean the same empty box";
        assert HighwayBuilder.sameSupplyDrop(new ItemStack(Items.OBSIDIAN), new ItemStack(Items.OBSIDIAN, 8));
        assert !HighwayBuilder.sameSupplyDrop(new ItemStack(Items.ENDER_CHEST), new ItemStack(Items.OBSIDIAN, 8));
        UUID oldDrop = UUID.randomUUID(), newDrop = UUID.randomUUID();
        assert HighwayBuilder.supplyDropCandidate(new ItemStack(Items.OBSIDIAN), new ItemStack(Items.OBSIDIAN, 8), Set.of(oldDrop), null, newDrop)
            : "Non-Silk-Touch ender-chest drops must acquire a tracked entity identity";
        assert !HighwayBuilder.supplyDropCandidate(new ItemStack(Items.OBSIDIAN), new ItemStack(Items.OBSIDIAN, 8), Set.of(oldDrop), null, oldDrop)
            : "Pre-existing nearby obsidian is not this recovery drop";
        assert !HighwayBuilder.supplyDropCandidate(new ItemStack(Items.OBSIDIAN), new ItemStack(Items.OBSIDIAN, 8), Set.of(), newDrop, oldDrop)
            : "A tracked recovery cannot jump to another matching stack";
        assert !HighwayBuilder.supplyRecoveryAbandoned(39, 299);
        assert HighwayBuilder.supplyRecoveryAbandoned(40, 1) && HighwayBuilder.supplyRecoveryAbandoned(1, 300)
            : "Solo and crew recovery share the same bounded fallback";
        ItemStack namedChest = new ItemStack(Items.ENDER_CHEST);
        namedChest.set(DataComponents.CUSTOM_NAME, Component.literal("Supplies"));
        assert HighwayBuilder.sameSupplyDrop(new ItemStack(Items.ENDER_CHEST), namedChest) : "Keep existing ender-chest count recovery independent of item names";
        assert !HighwayBuilder.sameSupplyDrop(expected, empty) : "An unnamed box cannot stand in for the named supply box";
        assert HighwayBuilder.supplyPickupAmount(true, 1, 1) == 1;
        assert HighwayBuilder.supplyPickupAmount(false, 1, 1) == 0 : "Another collector is not our pickup";
        assert HighwayBuilder.supplyPickupAmount(true, 0, 1) == 0;
        assert HighwayBuilder.supplyPickupAmount(true, 8, 1) == 1 : "Never count more than the real ground stack";
        assert !HighwayBuilder.supplyPickupComplete(0, 1, false, false) : "Burning, despawning or disappearing alone does not confirm pickup";
        assert !HighwayBuilder.supplyPickupComplete(1, 1, true, false) : "Wait until vanilla removes the collected ground entity";
        assert !HighwayBuilder.supplyPickupComplete(1, 1, false, true) : "Another player's pickup must not confirm recovery";
        assert HighwayBuilder.supplyPickupComplete(1, 1, false, false) : "A self-collector packet plus ground removal confirms the box regardless of inventory contents";
        assert HighwayBuilder.awaitingSupplyPlacement(10, 9, false);
        assert HighwayBuilder.awaitingSupplyPlacement(10, 6, true);
        assert !HighwayBuilder.awaitingSupplyPlacement(10, 6, false) : "An unobserved container retries placement; it never enters pickup recovery";
        int absent = 0;
        for (int tick = 0; tick < 39; tick++) absent = HighwayBuilder.supplyMissingTicks(absent, true, false, false);
        assert absent < 40 : "Allow delayed entity packets before declaring a supply unavailable";
        assert HighwayBuilder.supplyMissingTicks(absent, true, false, false) == 40;
        assert HighwayBuilder.supplyMissingTicks(absent, false, false, false) == 0 : "Unloaded chunks are not evidence of loss";
        assert HighwayBuilder.supplyMissingTicks(absent, true, true, false) == 0 : "Restored container cancels absence";
        assert HighwayBuilder.supplyMissingTicks(absent, true, false, true) == 0 : "A visible drop must still be collected";
        cursorRecovery();
        restockStopsWalking();
        miningRecovery();
        restockMerging();
        materialRefillCapacity();
        HighwayFarmingTest.check();
        inventorySupplySelection();
        shulkerBatching();
        stackedShulkerTransfer();
        shulkerRetention();
        liquidSealing();
        enderChestRecovery();
        cachedEnderChest();
        blockConfirmation();
        speculativeExcavation();
        speedMinePrediction();
        workflowSuppliesAndTravel();
        System.out.println("Highway supply checks passed: container recovery, reserve-aware shulker batches, filler-first inventory management, queued hotbar protection, liquid sealing, and optional Silk Touch recovery.");
    }

    private static void inventorySupplySelection() {
        var road = List.of(Blocks.OBSIDIAN);
        var filler = List.of(Blocks.NETHERRACK, Blocks.OBSIDIAN, Blocks.SHULKER_BOX, Blocks.ENDER_CHEST, Blocks.CHEST);
        var trash = List.of(Items.DIAMOND_PICKAXE, Items.SHULKER_BOX, Items.ENDER_CHEST, Items.OBSIDIAN, Items.DIRT);
        assert HighwayBuilder.isExpendableFiller(new ItemStack(Items.NETHERRACK), filler, road);
        for (var item : List.of(Items.OBSIDIAN, Items.SHULKER_BOX, Items.ENDER_CHEST, Items.CHEST, Items.DIAMOND_PICKAXE)) {
            assert !HighwayBuilder.isExpendableFiller(new ItemStack(item), filler, road)
                : "Paving, storage containers and tools must never become expendable filler";
        }
        assert !HighwayBuilder.isExpendableFiller(ItemStack.EMPTY, filler, road);

        SimpleContainer player = new SimpleContainer(41);
        for (int i = 0; i < 36; i++) player.setItem(i, new ItemStack(Items.DIAMOND, 64));
        player.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
        player.setItem(1, new ItemStack(Items.SHULKER_BOX));
        player.setItem(2, new ItemStack(Items.ENDER_CHEST));
        player.setItem(3, new ItemStack(Items.OBSIDIAN, 64));
        player.setItem(4, new ItemStack(Items.OBSIDIAN, 8));
        player.setItem(35, new ItemStack(Items.NETHERRACK, 12));
        player.setItem(40, new ItemStack(Items.NETHERRACK));
        assert HighwayBuilder.liquidSupplySlot(player, false, filler, road) == 35
            : "Reach the last main-inventory filler stack before spending hotbar paving blocks";
        assert HighwayBuilder.liquidSupplySlot(player, true, filler, road) == 3
            : "An actual road-floor hole still needs the configured paving material";
        assert HighwayBuilder.expendableFillerSlot(player, filler, road) == 35
            : "Recovery may discard filler but must not touch offhand, tools, containers or paving";
        assert HighwayBuilder.hotbarSwapSlot(player, false, 0, filler, road, trash) == 4
            : "A full hotbar can safely exchange its smallest paving stack into the incoming stack's source slot";
        // Headless bootstrap has no datapack item tags; shears exercise AutoTool's native type branch.
        player.setItem(0, new ItemStack(Items.SHEARS));
        assert HighwayBuilder.hotbarSwapSlot(player, true, 0, filler, road, trash) == 0
            : "Tool replacement may explicitly prefer the existing tool slot";
        player.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
        player.setItem(7, new ItemStack(Items.DIRT, 64));
        assert HighwayBuilder.hotbarSwapSlot(player, false, 0, filler, road, trash) == 7
            : "Actual trash ranks ahead of paving even if paving and valuables are in the trash list";
        player.setItem(8, ItemStack.EMPTY);
        assert HighwayBuilder.hotbarSwapSlot(player, false, 0, filler, road, trash) == 8;
        assert HighwayBuilder.hotbarSwapSlot(player, false, 1 << 8, filler, road, trash) == 7
            : "An empty-looking slot is still unavailable while a placement callback owns it";
        assert HighwayBuilder.hotbarSwapSlot(player, false, (1 << 8) | (1 << 7), filler, road, trash) == 4;
        assert HighwayBuilder.hotbarSwapSlot(player, false, (1 << 8) | (1 << 7) | (1 << 4), filler, road, trash) == 3
            : "Filler promotion must not overwrite paving already queued from the preferred swap slot";
        assert HighwayBuilder.hotbarSwapSlot(player, false, (1 << 9) - 1, filler, road, trash) == -1
            : "Defer promotion when all nine hotbar slots have queued work";

        // Simulate the native exchange, then ensure another promotion cannot overwrite its queued material.
        ItemStack displaced = player.getItem(4);
        player.setItem(4, player.getItem(35));
        player.setItem(35, displaced);
        int queued = (1 << 4) | (1 << 7) | (1 << 8);
        assert HighwayBuilder.hotbarSwapSlot(player, false, queued, filler, road, trash) == 3
            : "A later material promotion must use a different slot from already queued filler";
        assert player.getItem(35).is(Items.OBSIDIAN) && player.getItem(35).getCount() == 8
            : "The exchange preserves the displaced paving stack without an empty inventory slot";
        player.setItem(21, new ItemStack(Items.NETHERRACK, 5));
        assert HighwayBuilder.expendableFillerSlot(player, filler, road) == 21
            : "Free a slot by discarding the smallest filler stack, not the first or largest";
        List<ItemStack> before = new ArrayList<>();
        for (int i = 0; i < player.getContainerSize(); i++) before.add(player.getItem(i).copy());
        HighwayBuilder.expendableFillerSlot(player, filler, road);
        HighwayBuilder.liquidSupplySlot(player, false, filler, road);
        HighwayBuilder.hotbarSwapSlot(player, false, queued, filler, road, trash);
        for (int i = 0; i < before.size(); i++) assert ItemStack.matches(before.get(i), player.getItem(i))
            : "Slot selection itself must not move or discard items";
        player.setItem(4, ItemStack.EMPTY);
        player.setItem(21, ItemStack.EMPTY);
        assert HighwayBuilder.expendableFillerSlot(player, filler, road) == -1;
        assert HighwayBuilder.liquidSupplySlot(player, false, filler, road) == 3
            : "Paving remains the fallback only when no expendable filler is carried";
        player.setItem(3, ItemStack.EMPTY);
        player.setItem(35, ItemStack.EMPTY);
        assert HighwayBuilder.liquidSupplySlot(player, false, filler, road) == -1
            : "Never place a protected container merely because it was configured as filler";
        player.setItem(35, new ItemStack(Items.NETHERRACK));
        assert HighwayBuilder.liquidSupplySlot(player, true, filler, road) == -1
            : "Do not silently pave a permanent road hole with expendable filler";
    }

    private static void speculativeExcavation() throws Exception {
        var target = new net.minecraft.core.BlockPos(5, 64, 0);
        assert HighwayBuilder.excavationNeighborsSafe(target, p -> true);
        assert !HighwayBuilder.excavationNeighborsSafe(target, p -> !p.equals(target));
        for (var face : net.minecraft.core.Direction.values()) {
            assert !HighwayBuilder.excavationNeighborsSafe(target, p -> !p.equals(target.relative(face)))
                : "Every adjacent fluid/unloaded/unconfirmed cell must defer speculative excavation";
        }
        var emptyWorld = net.minecraft.world.level.EmptyBlockGetter.INSTANCE;
        assert HighwayBuilder.crewClearanceResolved(Blocks.AIR.defaultBlockState(), emptyWorld, target);
        for (var block : List.of(Blocks.NETHERRACK, Blocks.OBSIDIAN, Blocks.LAVA, Blocks.FIRE, Blocks.POWDER_SNOW))
            assert !HighwayBuilder.crewClearanceResolved(block.defaultBlockState(), emptyWorld, target) : "Required excavation must be clear and safe";
        var materials = List.of(Blocks.OBSIDIAN);
        assert HighwayBuilder.crewPavingResolved(Blocks.OBSIDIAN.defaultBlockState(), true, materials);
        assert !HighwayBuilder.crewPavingResolved(Blocks.NETHERRACK.defaultBlockState(), true, materials) : "Replace jobs cannot accept a restored filler block as road";
        assert HighwayBuilder.crewPavingResolved(Blocks.NETHERRACK.defaultBlockState(), false, materials) : "Repair and missing-floor jobs preserve existing solids";
        assert !HighwayBuilder.crewPavingResolved(Blocks.AIR.defaultBlockState(), false, materials);
        assert !HighwayBuilder.crewPavingResolved(Blocks.LAVA.defaultBlockState(), false, materials);
        try (var bytes = HighwayBuilder.class.getResourceAsStream("HighwayBuilder.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var constructor = compiled.methods().stream().filter(m -> m.methodName().equalsString("<init>")).findFirst().orElseThrow();
            String lookahead = null;
            int value = -1, checked = 0;
            for (var element : constructor.code().orElseThrow().elementList()) {
                if (element instanceof java.lang.classfile.instruction.ConstantInstruction constant) {
                    if (List.of("blocks-ahead-to-pave", "blocks-ahead-to-break").contains(constant.constantValue())) lookahead = (String) constant.constantValue();
                    else if (constant.constantValue() instanceof Integer number) value = number;
                }
                if (lookahead != null && element instanceof java.lang.classfile.instruction.InvokeInstruction call && call.name().equalsString("defaultValue")) {
                    assert value == 3 : lookahead + " must default to the proven three-row window";
                    checked++; lookahead = null;
                }
            }
            assert checked == 2 : "Both production setting builders must expose the proven three-row default";
            for (String method : List.of("breakAhead", "canBreakAhead", "speculativeBreakRetryDue", "speculativeMiningRange", "tickPredictionFlush", "paveAvailable", "crewReconfigureReady", "crewReleaseReady", "crewMayMine", "protectedCrewPaving", "plannedPavingTargets", "crewNeedsCleanup", "crewRowResolved", "crewVerifiedState", "crewCurrentRow")) {
                var code = compiled.methods().stream().filter(m -> m.methodName().equalsString(method))
                    .filter(m -> !method.equals("crewRowResolved") || m.methodType().stringValue().contains("ZLcom/google/gson/JsonObject;"))
                    .findFirst().orElseThrow().code().orElseThrow().elementList();
                var calls = code.stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                    .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(call -> call.name().stringValue()).toList();
                if (method.equals("breakAhead")) {
                    assert calls.containsAll(List.of("controlsPlayer", "doesDig", "immediateExcavation", "excavationAhead", "mine"));
                    assert calls.contains("plannedPavingTargets") && !calls.contains("pavingTargets") : "Excavators protect the whole crew's paving, not their empty placement assignment";
                    assert !calls.contains("reach") && !calls.contains("setState") && !calls.contains("pauseJob")
                        : "Speculative work must not walk ahead, replace the required state, or pause for an optional block";
                }
                if (method.equals("canBreakAhead")) assert calls.containsAll(List.of("crewMayMine", "speculativeMiningRange", "excavationNeighborsSafe", "getBlockEntity", "players"))
                    : "Background excavation retains bounded reach, supply ownership, liquid checks and player footing protection";
                if (method.equals("speculativeBreakRetryDue")) assert calls.containsAll(List.of("containsKey", "getOrDefault", "excavationRow"))
                    : "An unresolved speculative break retries at most once per newly advanced road row";
                if (method.equals("speculativeMiningRange")) assert calls.containsAll(List.of("blockInteractionRange", "max"))
                    : "Only speculative mining uses the greater of native interaction reach and Place Range";
                if (method.equals("tickPredictionFlush")) assert calls.contains("hasPendingExcavation");
                if (method.equals("paveAvailable")) assert !calls.contains("hasPendingExcavation")
                    : "Pending excavation receipts must not stop useful placements elsewhere";
                if (method.equals("crewReconfigureReady")) assert calls.containsAll(List.of("crewQuiesce", "crewNeedsCleanup"))
                    : "Rebalancing must drain packets and preserve active supply/cursor recovery";
                if (method.equals("crewReleaseReady")) assert calls.containsAll(List.of("crewNeedsCleanup", "crewQuiesce", "onGround", "isFallFlying"))
                    && !calls.contains("crewRowResolved") : "Cancellation preserves physical cleanup and safe footing without demanding a finished road";
                if (method.equals("crewMayMine")) assert calls.contains("protectedCrewPaving") : "All mining paths, including double mining, protect finished crew paving";
                if (method.equals("protectedCrewPaving")) assert calls.contains("plannedPavingTargets") && !calls.contains("pavingTargets");
                if (method.equals("plannedPavingTargets")) assert calls.contains("hasPreviewFloor") && !calls.contains("doesPave") && !calls.contains("ownsPaving")
                    : "Road geometry does not disappear when a worker is assigned excavation-only duties";
                if (method.equals("crewNeedsCleanup")) assert calls.containsAll(List.of("reconfigureReady", "getCarried"));
                if (method.equals("crewRowResolved")) {
                    var fullRow = compiled.methods().stream().filter(m -> m.methodName().equalsString(method)
                        && !m.methodType().stringValue().contains("ZLcom/google/gson/JsonObject;")).findFirst().orElseThrow().code().orElseThrow().elementList();
                    assert fullRow.stream().anyMatch(e -> e instanceof java.lang.classfile.instruction.ConstantInstruction c
                        && Integer.valueOf(0).equals(c.constantValue())) : "Public authority verification must disable ownership filtering";
                    assert calls.containsAll(List.of("isSameThread", "front", "paving", "crewVerifiedState", "crewClearanceResolved", "crewPavingResolved"));
                    assert !calls.contains("owns") && !calls.contains("pavingTargets") && !calls.contains("doesPave") && !calls.contains("doesDig")
                        : "Authority verifies the full job geometry, independent of this worker's role";
                }
                if (method.equals("crewVerifiedState")) {
                    assert calls.containsAll(List.of("hasChunkAt", "getBlockStatePredictionHandler", "containsKey", "getBlockState"));
                    var fields = code.stream().filter(java.lang.classfile.instruction.FieldInstruction.class::isInstance)
                        .map(java.lang.classfile.instruction.FieldInstruction.class::cast).map(field -> field.name().stringValue()).toList();
                    assert fields.containsAll(List.of("pendingPlaces", "pendingBreaks", "serverVerifiedStates"))
                        : "Never grant row authority from local ghost blocks or pending predictions from any module";
                }
                if (method.equals("crewCurrentRow")) assert calls.containsAll(List.of("isSameThread", "origin", "startPosition", "reachedRow"))
                    : "Report physically reached original-job progress, not granted rows or packet counts";
            }
            var gapCalls = compiled.methods().stream().filter(m -> m.methodName().equalsString("crewRepairVerificationGap")).findFirst().orElseThrow()
                .code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
            assert gapCalls.containsAll(List.of("controlsPlayer", "crewNeedsCleanup", "crewRowResolved", "crewVerificationRepairRow", "stopWorkMining", "workChanged"));
            assert !gapCalls.contains("walkToWorkPosition") && !gapCalls.contains("stopJob") && !gapCalls.contains("resetSupplyJob")
                : "Gap recovery reclaims logical work without forcing a retreat or restarting resource recovery";
        }
    }

    private static void workflowSuppliesAndTravel() throws Exception {
        var shulkers = dev.monocle.client.systems.bots.BotWorkflows.Action.InventoryShulkers;
        var contents = dev.monocle.client.systems.bots.BotWorkflows.Action.EnderChestContents;
        var farm = dev.monocle.client.systems.bots.BotWorkflows.Action.EnderChestFarm;
        assert HighwayBuilder.supplySources(null, true, false, true).equals(List.of(shulkers, farm));
        assert HighwayBuilder.supplySources(null, false, false, false).isEmpty() : "Solo source settings remain authoritative";
        for (var order : List.of(List.of(shulkers, contents, farm), List.of(farm, contents, shulkers), List.of(contents, shulkers, farm), List.of(contents), List.of(shulkers), List.of(farm))) {
            var plan = new com.google.gson.JsonObject();
            var actions = new com.google.gson.JsonArray();
            actions.add("Excavating");
            order.forEach(action -> actions.add(action.name()));
            actions.add("Paving");
            plan.add("actions", actions);
            assert HighwayBuilder.supplySources(plan, false, false, false).equals(order) : "Workflow source order overrides worker-local toggles";
            assert HighwayBuilder.supplySources(plan, true, true, true).equals(order) : "Removing a source must actually disable it";
        }
        try (var bytes = HighwayBuilder.class.getResourceAsStream("HighwayBuilder.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var travel = methodCalls(compiled, "tickCrewTravel");
            assert travel.containsAll(List.of("stop", "canResume", "recoverCursor", "travelWaypoint", "segmentClear", "safeVelocity", "requestAutopilot", "route", "forward", "brakeCrewFlight"));
            assert !travel.contains("breakWorkBlock") && !travel.contains("placeWorkBlock") && !travel.contains("setState") : "Travel may not excavate, pave or replace an active supply task";
            assert !travel.contains("set") : "Supply travel must not rewrite Speed or ElytraFly settings";
            assert travel.containsAll(List.of("crewFlightLeg", "crewFlightSpeed", "requestEquip", "clearAutopilot"));
            assert travel.contains("usableCrewGlider");
            var run = methodCalls(compiled, "onCrewTravelMove");
            assert run.containsAll(List.of("controlsPlayer", "crewDetachedSupply", "onGround", "isFallFlying", "isPassenger",
                "isInWater", "isInLava", "canResume", "isUsingItem", "isEating", "crewRunVelocity", "monocle$set"))
                : "Running must retain native ownership, pause, airborne, fluid and item-use gates";
            assert methodCalls(compiled, "crewRunVelocity").contains("safeVelocity") : "Recheck the swept body every movement, not only when finding the route";
            assert methodCalls(compiled, "releaseControls").contains("stopCrewFlight")
                && methodCalls(compiled, "endCrew").contains("stopCrewFlight") : "Stopping must clear the short-lived running request too";
            assert methodCalls(compiled, "onDeactivate").containsAll(List.of("retainCrewOnToggle", "pauseJob"));
            assert methodCalls(compiled, "onActivate").containsAll(List.of("resumeJob", "isPausedByHost"));
            assert methodCalls(compiled, "resumeJob").containsAll(List.of("isActive", "enable")) : "Host Resume also re-enables a manually disabled builder";
            assert methodCalls(compiled, "brakeCrewFlight").containsAll(List.of("controlsPlayer", "crewDetachedSupply", "isActive", "requestAutopilot"));
            assert methodCalls(compiled, "onTick").contains("brakeCrewFlight") : "Native restocking must renew the zero lease so enabled auto-takeoff cannot jump into a container";
            var tickCalls = methodCalls(compiled, "onTick");
            assert tickCalls.indexOf("pauseForEating") >= 0 && tickCalls.indexOf("pauseForEating") < tickCalls.indexOf("tickCrewTravel")
                && tickCalls.indexOf("pauseForEating") < tickCalls.indexOf("holdStep")
                : "Food must pause work before travel and crew holds can perform actions";
            assert tickCalls.indexOf("releaseSupply") < tickCalls.indexOf("pauseForEating")
                : "An eating stall must not block cancellation of a crew job without remaining cleanup";
            var foodPause = methodCalls(compiled, "pauseForEating");
            assert foodPause.containsAll(List.of("stop", "brakeCrewFlight", "miningInProgress", "stopWorkMining"))
                && !foodPause.contains("releaseUsingItem") : "Eating cancels existing mining without releasing newly started food use";
            assert methodCalls(compiled, "onTick").containsAll(List.of("crewAwaitingRejoin", "crewNeedsCleanup", "crewQuiesce"))
                : "Returning suppliers must wait for a host lane handoff instead of running Forward against the distant service origin";
            assert methodCalls(compiled, "resumeJob").contains("crewAwaitingRejoin") : "A safe rendezvous wait cannot require walking back to the old supply site";
            var travelClear = methodCalls(compiled, "crewTravelClear");
            assert travelClear.containsAll(List.of("loaded", "crewVerifiedState", "crewClearanceResolved", "isShapeFullBlock"));
            assert !travelClear.contains("noCollision") && !travelClear.contains("getEntities")
                : "Supply routing ignores entities while retaining block, chunk and footing checks";
            assert methodCalls(compiled, "crewReturnCorridorClear").containsAll(List.of("crewDetachedSupply", "controlsPlayer", "segmentClear"))
                : "Return overshoot must use a real body/landing sweep, not just assume the extra block is clear";
            assert HighwayBuilder.entitiesBlockWork(false) && !HighwayBuilder.entitiesBlockWork(true)
                : "Entities block exact work placement, never movement routing";
            assert methodCalls(compiled, "crewMayMine").containsAll(List.of("crewIsTraveling", "allowsWork", "ownsExcavation", "miningAvailable", "contains")) : "All mining retains duty ownership, shared-target exclusion, site reservation and temporary-step recovery";
            assert methodCalls(compiled, "crewPrepareRejoin").containsAll(List.of("onGround", "isFallFlying", "recoverCursor", "crewRestockIdle", "crewReconfigureReady"));
            assert methodCalls(compiled, "crewTravelSupply").contains("crewReconfigureReady")
                && methodCalls(compiled, "crewTravelRejoin").contains("crewRestockIdle") : "Travel cannot take ownership from unfinished native work";
            assert methodCalls(compiled, "crewCancelTravel").containsAll(List.of("stop", "stopCrewFlight", "brakeCrewFlight"));
            assert methodCalls(compiled, "crewBeginSupply").contains("crewCancelTravel") : "Rejected admission must revoke the previous return destination";
            assert methodCalls(compiled, "crewCloseSharedSupply").contains("crewCancelTravel") : "A withdrawn shared shulker must clear its travel target";
            assert compiled.methods().stream().noneMatch(m -> m.methodName().stringValue().contains("PitStop")) : "There is no collective inventory top-up controller";
            assert methodCalls(compiled, "crewYield").contains("crewSupplyYieldWaypoint") : "Active workers use routed retreat, not greedy steps away from supplies";
            assert methodCalls(compiled, "crewYield").contains("supplyYieldDistance")
                && methodCalls(compiled, "crewSupplyYieldWaypoint").contains("supplyYieldDistance")
                : "Yield arrival and route goals share the reservation's compact/legacy pickup margin";
            assert methodCalls(compiled, "relocateCrewSupplySite").containsAll(List.of("standable", "crewPositionWaypoint"))
                : "Relocation requires a supported reachable supply position";
            var yielding = methodCalls(compiled, "crewSupplyYieldWaypoint");
            assert yielding.containsAll(List.of("crewPositionWaypoint", "standable")) && !yielding.contains("placeWorkBlock") && !yielding.contains("breakWorkBlock")
                : "Yielding only walks supported routes; it cannot create an unverified road to escape";
            assert methodCalls(compiled, "sealLiquids").contains("plannedPavingTargets") && !methodCalls(compiled, "sealLiquids").contains("pavingTargets")
                : "Excavation-only workers must use paving material when sealing a shared floor hole";
            assert methodCalls(compiled, "needsPassageSealing").contains("ownsSealing") : "Do not enter a sealing state for another worker's plugs";
            assert !methodCalls(compiled, "placeWorkBlock").contains("requestSupply")
                : "Lava plugs must never create a supply lock that blocks the crew's Forward window";
            assert methodCalls(compiled, "placeWorkBlock").contains("allowsPlacement");
            assert methodCalls(compiled, "crewSealingTarget").containsAll(List.of("controlsPlayer", "liquidBarrier", "containsKey"));
            assert methodCalls(compiled, "retryCrewPause").containsAll(List.of("crewRetryAllowed", "isPausedByHost", "resumeJob", "resetReturnPath"));
            assert methodCalls(compiled, "onTick").containsAll(List.of("tickCrewSupplyRecovery", "isReleasing", "releaseSupply"));
            assert methodCalls(compiled, "tickCrewSupplyRecovery").containsAll(List.of("supplyStarted", "supplyProgress", "supplyRetryDue", "retrySupply", "resetReturnPath"));
            assert methodCalls(compiled, "crewAnticipateSupply").containsAll(List.of("sampleSupplyForecast", "anticipatedSupply", "setTask"))
                : "Forecast dispatch must use fresh inventory and the tested live-shortage priority";
            assert !methodCalls(compiled, "tickCrewSupplyRecovery").contains("stopWorkMining") : "One-second nudges must not repeatedly abort slow container mining";
            assert methodCalls(compiled, "relocateCrewSupplySite").containsAll(List.of("supplyPosition", "crewPositionWaypoint", "supplyLayout"));
            assert !methodCalls(compiled, "retryCrewPause").contains("setState") && !methodCalls(compiled, "retryCrewPause").contains("clear")
                : "Retry the current native action without deleting pending confirmations or recovery state";
        }
        var states = Class.forName(HighwayBuilder.class.getName() + "$State");
        for (String stateName : List.of("Restock", "MineEnderChests", "Forward")) {
            var field = states.getDeclaredField(stateName); field.setAccessible(true);
            Class<?> type = field.get(null).getClass();
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
                if (stateName.equals("Restock")) {
                    Object restock = field.get(null);
                    var session = type.getDeclaredField("session"); session.setAccessible(true);
                    var started = type.getDeclaredMethod("supplyStarted"); started.setAccessible(true);
                    boolean previous = session.getBoolean(restock);
                    try {
                        session.setBoolean(restock, false);
                        for (int tick = 0; tick <= 200; tick += 20) assert !(boolean) started.invoke(restock)
                            : "Waiting for admission, peer clearance or travel must not start the local restock retry clock";
                        session.setBoolean(restock, true);
                        assert (boolean) started.invoke(restock) : "Actual admitted restock attempts still get watchdog recovery";
                    } finally { session.setBoolean(restock, previous); }
                    assert methodCalls(compiled, "start").containsAll(List.of("supplyReady", "supplySources", "canFarmEnderChests", "findAndMoveToHotbar"));
                    assert methodCalls(compiled, "tick").containsAll(List.of("supplyCount", "isSameItemSameComponents"))
                        : "Recheck the replenished stock and the selected hotbar container instead of trusting an old slot";
                    assert compiled.methods().stream().anyMatch(method -> method.methodName().stringValue().startsWith("lambda$setShulkerPredicate$")
                        && methodCalls(compiled, method.methodName().stringValue()).contains("usableSupplyBox"))
                        : "Actual container searches must use the tested content/no-yield filter";
                    assert !methodCalls(compiled, "finishSupplyJob").contains("releaseSupply") : "Reservation survives trash cleanup and pending packet confirmation";
                } else if (stateName.equals("MineEnderChests")) {
                    assert methodCalls(compiled, "tick").contains("finishFarmRecovery");
                    assert methodCalls(compiled, "finishFarmRecovery").containsAll(List.of("crewVerifiedState", "recoverCursor", "breakWorkBlock", "getEntitiesOfClass", "walkToWorkPosition", "isEmpty"));
                } else {
                    assert java.util.Collections.frequency(methodCalls(compiled, "checkPassage"), "waitForExcavation") == 2
                        : "Both liquid and solid obstructions must support waiting for a dedicated excavator";
                    assert !methodCalls(compiled, "tick").contains("stopWorkMining")
                        : "A foreground ACK wait must not abort a speculative slow mine";
                    var wait = methodCalls(compiled, "waitForExcavation");
                    assert wait.containsAll(List.of("paveAvailable", "paveAhead", "paveBehind")) && !wait.contains("pauseJob") && !wait.contains("setState")
                        : "Waiting pavers continue reachable work without pausing or stealing excavation";
                }
            }
        }
    }

    private static void restockLoop() {
        assert HighwayBuilder.anticipatedSupply(true, 0, true, 4, 1, -2, 10, 30) == 0
            : "Four picks and zero obsidian need obsidian even when the forecast says materials are sustainable";
        assert HighwayBuilder.anticipatedSupply(true, 0, true, 1, 1, -2, -1, 30) == 1
            : "Restore the working pick first when both resources really are exhausted";
        assert HighwayBuilder.anticipatedSupply(true, 64, true, 4, 1, 20, 1000, 30) == 0;
        assert HighwayBuilder.anticipatedSupply(true, 64, true, 4, 1, -2, 10, 30) == 1;
        assert HighwayBuilder.anticipatedSupply(true, 64, true, 4, 1, -2, 1000, 30) == -1;
        assert HighwayBuilder.anticipatedSupply(false, 0, true, 4, 1, 0, 1000, 30) == -1;
        assert HighwayBuilder.restockMinimum(1, 1, 1, false) == 2;
        assert HighwayBuilder.restockMinimum(1, 4, 1, true) == 5
            : "An early pickaxe top-up cannot finish merely because the original four picks exceed the reserve";
        assert HighwayBuilder.restockMinimum(0, 128, 1, true) == 129
            : "Existing paving stock is not evidence that an early supply trip obtained anything";
        assert HighwayBuilder.restockMinimum(0, 0, 1, false) == 1;

        ItemStack picks = new ItemStack(Items.SHULKER_BOX);
        picks.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.NETHERITE_PICKAXE))));
        ItemStack road = new ItemStack(Items.SHULKER_BOX);
        road.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.OBSIDIAN, 64))));
        java.util.function.Predicate<ItemStack> obsidian = stack -> stack.is(Items.OBSIDIAN);
        java.util.function.Predicate<ItemStack> pickaxe = stack -> stack.is(Items.NETHERITE_PICKAXE);
        assert !usableSupplyBox(picks, obsidian, List.of());
        assert usableSupplyBox(road, obsidian, List.of());
        assert usableSupplyBox(picks, pickaxe, List.of());
        assert !usableSupplyBox(road, pickaxe, List.of()) : "Changing the task must change which contents qualify";
        for (int retry = 0; retry < 100; retry++) assert !usableSupplyBox(road.copy(), obsidian, List.of(road.copy()))
            : "An unchanged no-yield box must not be opened repeatedly after recovery";
        assert usableSupplyBox(road, obsidian, List.of(picks)) : "Rejecting one source must not blacklist every shulker";
        ItemStack replenished = road.copy();
        replenished.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.OBSIDIAN, 32))));
        assert usableSupplyBox(replenished, obsidian, List.of(road)) : "A changed inventory snapshot can be tried again";
        assert !usableSupplyBox(new ItemStack(Items.SHULKER_BOX), obsidian, List.of());
        assert !usableSupplyBox(ItemStack.EMPTY, obsidian, List.of());
    }

    private static boolean usableSupplyBox(ItemStack box, java.util.function.Predicate<ItemStack> wanted, List<ItemStack> rejected) {
        // Supply the decoded component snapshot without initializing Utils' GPU-dependent rendering fields.
        ItemStack[] contents = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).allItemsCopyStream().toArray(ItemStack[]::new);
        return HighwayBuilder.usableSupplyBox(box, contents, wanted, rejected);
    }

    private static void forecasts() {
        HighwaySupplyForecast forecast = new HighwaySupplyForecast();
        assert forecast.pavingSeconds(100) == HighwaySupplyForecast.UNKNOWN;
        assert forecast.pickaxeSeconds(10000) == HighwaySupplyForecast.UNKNOWN;
        for (int second = 0; second <= 20; second++) forecast.sample(second, true, 1000 - second * 10, 10000 - second * 5, 4);
        assert forecast.pavingSeconds(100) == 10 : "Net stock burn, not placement attempts";
        assert forecast.pickaxeSeconds(500) == 100;
        assert forecast.pickaxeSeconds(50000) == 10000 : "Carried shulker tools extend total runway";
        assert HighwayBuilder.forecastToolCapacity(List.of(1000, 500, 0, 100), 1) == 600;
        forecast.sample(21, false, 0, 0, 0);
        forecast.sample(50, true, 5000, 50000, 10);
        assert forecast.pavingSeconds(100) == 10 : "Restock gains and idle travel are excluded";
        assert forecast.pickaxeSeconds(500) == 100;
        for (int second = 51; second <= 70; second++) forecast.sample(second, true, 5000 + (second - 50) * 20, 50000, 10);
        assert forecast.pavingSeconds(100) == HighwaySupplyForecast.SUSTAINABLE : "Mined netherrack can outpace placements";
        assert !HighwaySupplyForecast.due(forecast.pavingSeconds(100), forecast.leadSeconds());
        assert !HighwaySupplyForecast.due(Double.NaN, 30) && !HighwaySupplyForecast.due(-1, 30);
        assert HighwaySupplyForecast.due(10, 30);
        for (int i = 0; i < 100; i++) forecast.supplied(1000000);
        assert forecast.leadSeconds() <= 120 : "A stuck trip cannot force endless premature resupply";
        HighwaySupplyForecast noWear = new HighwaySupplyForecast();
        for (int second = 0; second <= 20; second++) noWear.sample(second, true, 100, 10000, 2);
        assert noWear.pickaxeSeconds(10000) == HighwaySupplyForecast.UNKNOWN : "No observed wear is not immortal pickaxes";
        assert HighwaySupplyForecast.describe(360000).equals("~100h");
    }

    private static void lifecycleRecovery() throws Exception {
        var running = HighwayBuilder.Lifecycle.Running;
        var paused = running.pause();
        assert paused.hasJob() && paused.paused() : "Pausing preserves the execution";
        var waiting = paused.worldLost();
        assert waiting.hasJob() && waiting.paused() && waiting.suspended();
        assert waiting.pause() == waiting : "A host pause during disconnection cannot discard the world gate";
        assert waiting.worldLost() == waiting : "Repeated disconnect callbacks are idempotent";
        assert HighwayBuilder.Lifecycle.Idle.worldLost() == HighwayBuilder.Lifecycle.Idle;
        assert HighwayBuilder.Lifecycle.Idle.pause() == HighwayBuilder.Lifecycle.Idle : "Late pause cannot revive a cancelled job";
        for (var phase : HighwayBuilder.Lifecycle.values())
            assert (phase.hasJob() && !phase.paused() && !phase.suspended()) == (phase == running);
        for (String removed : List.of("job", "paused", "suspended", "waitingForWorld")) {
            assert java.util.Arrays.stream(HighwayBuilder.class.getDeclaredFields()).noneMatch(f -> f.getName().equals(removed))
                : "No writable lifecycle compatibility flags: " + removed;
        }
    }

    private static void worldRecovery() throws Exception {
        String original = "play.6b6t.org\nminecraft:the_nether\nworker-a";
        for (int tick = 0; tick < 1200; tick++) {
            assert !HighwayBuilder.worldResumeAllowed(original, "", false);
            assert !HighwayBuilder.worldResumeAllowed(original, original, false) : "Wait for fresh highway chunks";
            assert !HighwayBuilder.worldResumeAllowed(original, "other.org\nminecraft:the_nether\nworker-a", true);
            assert !HighwayBuilder.worldResumeAllowed(original, "play.6b6t.org\nminecraft:overworld\nworker-a", true);
            assert !HighwayBuilder.worldResumeAllowed(original, "play.6b6t.org\nminecraft:the_nether\nworker-b", true);
        }
        assert HighwayBuilder.worldResumeAllowed(original, new String(original), true) : "A new world object on the same account/server/dimension can recover";
        assert !HighwayBuilder.worldResumeAllowed("", "", true);
        assert HighwayBuilder.playerSessionChanged(true, true, 55262, 20703) : "Tick rollback invalidates old cooldowns even in the same world/player object";
        assert HighwayBuilder.playerSessionChanged(true, false, 100, 101) : "Respawn can replace the player without replacing the world";
        assert HighwayBuilder.playerSessionChanged(false, true, 100, 101);
        assert !HighwayBuilder.playerSessionChanged(true, true, 100, 100) : "Repeated same-tick checks are harmless";
        assert !HighwayBuilder.playerSessionChanged(true, true, 100, 101) : "Normal ticking preserves inventory throttling";
        try (var bytes = HighwayBuilder.class.getResourceAsStream("HighwayBuilder.class")) {
            var code = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var diagnostics = methodCalls(code, "diagnosticSnapshot");
            assert diagnostics.containsAll(List.of("nanoTime", "deepCopy", "limit", "diagnose")) : "Snapshots are bounded, cached and include supply state";
            assert diagnostics.stream().noneMatch(List.of("send", "swap", "enable", "disable", "resumeJob", "pauseJob", "releaseControls", "recoverCursor", "readinessProblem")::contains)
                : "Inspection must not change movement, modules, inventory, or recovery state";
            assert methodCalls(code, "crewDiagnostics").contains("diagnosticTickAge") : "Existing hosts receive the compact tick/gate summary";
            var suspend = methodCalls(code, "waitForWorld");
            assert suspend.contains("releaseControls") && !suspend.contains("pauseJob") && !suspend.contains("warning")
                : "World loss releases controls without emitting repeating pause notifications";
            assert methodCalls(code, "onGameLeave").contains("waitForWorld");
            assert methodCalls(code, "onTick").contains("waitForWorld") && methodCalls(code, "resumeJob").contains("refreshJobWorld");
            var refresh = methodCalls(code, "refreshJobWorld");
            assert refresh.containsAll(List.of("worldReady", "resetPredictionTracking", "resetCursorRecovery", "worldChanged"));
            assert methodCalls(code, "onTick").contains("jobSessionChanged") && refresh.contains("jobSessionChanged");
            var resetFields = code.methods().stream().filter(m -> m.methodName().equalsString("refreshJobWorld")).findFirst().orElseThrow()
                .code().orElseThrow().elementList().stream()
                .filter(java.lang.classfile.instruction.FieldInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.FieldInstruction.class::cast)
                .filter(f -> f.opcode() == java.lang.classfile.Opcode.PUTFIELD).map(f -> f.name().stringValue()).toList();
            assert resetFields.containsAll(List.of("jobPlayer", "jobPlayerTick", "crewInventoryTick", "crewLedgerTick", "crewTrashSlot", "crewTrashStack",
                "crewTravelTick", "crewLaunchTick", "crewRunUntil", "crewTravelProgressTick", "crewYieldSearchTick", "crewRetryAt"))
                : "Every player-clock cooldown/cache is invalidated with the old session";
            assert resetFields.stream().noneMatch(List.of("crewPendingResource", "restockTask", "crewReturnedStack", "crewSupplyOrigin")::contains)
                : "Clock recovery preserves outstanding supply work";
            assert !refresh.contains("resetSupplyJob") && !refresh.contains("complete") && !refresh.contains("recovered")
                : "Reconnect invalidates transient proofs, never claims supply recovery or discards ownership";
        }
    }

    private static void supplyPlacementClearance() throws Exception {
        var stateType = Class.forName(HighwayBuilder.class.getName() + "$State");
        var rejoin = HighwayBuilder.class.getDeclaredMethod("crewRejoinState", stateType);
        rejoin.setAccessible(true);
        for (var state : stateType.getEnumConstants()) {
            assert (boolean) rejoin.invoke(null, state) == List.of("Center", "Forward").contains(((Enum<?>) state).name())
                : "Restored idle Center can rejoin without running the road FSM; recovery states cannot";
        }
        for (var target : List.of(new net.minecraft.core.BlockPos(-81954, 116, 0), new net.minecraft.core.BlockPos(20, 116, -30))) {
            for (int[] direction : List.of(new int[] {1, 0}, new int[] {-1, 0}, new int[] {0, 1}, new int[] {0, -1})) {
                var farther = target.offset(-direction[0], 0, -direction[1]);
                assert HighwayBuilder.flexibleSupplyPosition(target, direction[0], direction[1], p -> p.equals(farther)).equals(farther)
                    : "A blocked preferred supply tile must select the next supported tile away from work";
                assert HighwayBuilder.flexibleSupplyPosition(target, direction[0], direction[1], p -> true).equals(target);
                assert HighwayBuilder.flexibleSupplyPosition(target, direction[0], direction[1], p -> false) == null;
            }
            for (var direction : dev.monocle.client.utils.misc.HorizontalDirection.values()) {
                var facing = HighwayPlan.supplyLayout(direction.offsetX, direction.offsetZ, 5, false).facing();
                var center = net.minecraft.world.phys.Vec3.atBottomCenterOf(target);
                // Feet are in the neighboring block, but 0.02 blocks of the body still clip the box.
                var feet = center.add(facing.x() * .78, 0, facing.z() * .78);
                var body = new net.minecraft.world.phys.AABB(feet.x - .3, feet.y, feet.z - .3, feet.x + .3, feet.y + 1.8, feet.z + .3);
                assert !net.minecraft.core.BlockPos.containing(feet).equals(target);
                assert new net.minecraft.world.phys.AABB(target).intersects(body);
                var stance = HighwayBuilder.supplyPlacementStance(target, facing);
                assert stance.equals(center.add(facing.x() * 2, 0, facing.z() * 2));
                var retreat = center.add(-facing.x() * 2, 0, -facing.z() * 2);
                assert HighwayBuilder.supplyPlacementStance(target, facing, point -> true).equals(stance);
                assert HighwayBuilder.supplyPlacementStance(target, facing, point -> point.equals(retreat)).equals(retreat)
                    : "An unpaved front cannot prevent restocking from the supported completed-road side";
                assert HighwayBuilder.supplyPlacementStance(target, facing, point -> false) == null
                    : "Never walk into an unsupported stance when both approaches fail";
                for (double distance : new double[] {.78, .86, 1, 1.25, 1.5, 1.84}) {
                    var approaching = center.add(facing.x() * distance, 0, facing.z() * distance);
                    assert approaching.distanceTo(stance) > .15
                        : "Clearing the collision boundary must not count as reaching the placement stance";
                }
                var closestAccepted = stance.add(-facing.x() * .15, 0, -facing.z() * .15);
                var cleared = body.move(closestAccepted.subtract(feet));
                assert !new net.minecraft.world.phys.AABB(target).inflate(1, 0, 1).intersects(cleared)
                    : "Even the walking arrival tolerance leaves a full block between body and container";
            }
        }
        try (var bytes = HighwayBuilder.class.getResourceAsStream("HighwayBuilder.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var resume = methodCalls(compiled, "resumeJob");
            var crewResume = methodCalls(compiled, "resumeCrewJob");
            assert crewResume.containsAll(List.of("isPausedByHost", "crewNeedsCleanup", "beginCrew", "enable", "resumeJob"))
                : "Host resume restores missing runners but respects host pause and supply recovery";
            assert crewResume.indexOf("crewNeedsCleanup") < crewResume.indexOf("beginCrew");
            assert methodCalls(compiled, "releaseControls").contains("<init>")
                : "Releasing input can fall back to native keyboard control";
            assert resume.contains("resumeDetachedReturn")
                && resume.indexOf("resumeDetachedReturn") < resume.indexOf("crewAwaitingRejoin")
                : "A displaced supplier must enter the live return path before the old work-position distance gate";
            assert resume.indexOf("isPausedByHost") < resume.indexOf("resumeDetachedReturn")
                : "Module activation must not override a host pause";
            var manualReturn = methodCalls(compiled, "crewPrepareManualReturn");
            for (String check : List.of("refreshJobWorld", "crewNeedsCleanup", "crewSupplyContainers")) {
                assert manualReturn.indexOf(check) >= 0 && manualReturn.indexOf(check) < manualReturn.indexOf("crewFinishResourceWait")
                    : "Manual return must preserve physical supply recovery and require the correct world: " + check;
            }
            assert !manualReturn.contains("finishCrewReturn") && !manualReturn.contains("resetPredictionTracking")
                : "A resume request cannot grant itself a lane or erase block confirmation state";
            var calls = methodCalls(compiled, "placeWorkBlock");
            assert calls.contains("supplyApproach") && calls.contains("walkToWorkPosition");
            assert calls.indexOf("supplyApproach") < calls.indexOf("canPlaceBlock")
                : "Both single and paired supply containers clear the player before the shared collision/placement gate";
        }
    }

    private static List<String> methodCalls(java.lang.classfile.ClassModel type, String name) {
        return type.methods().stream().filter(m -> m.methodName().equalsString(name)).flatMap(m -> m.code().orElseThrow().elementList().stream())
            .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance).map(java.lang.classfile.instruction.InvokeInstruction.class::cast)
            .map(call -> call.name().stringValue()).toList();
    }

    private static void crewRetreatRecovery() throws Exception {
        var work = new net.minecraft.world.phys.Vec3(-123.5, 116, 777.5);
        for (int[] direction : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int dx = direction[0], dz = direction[1];
            for (boolean shared : new boolean[] {false, true}) {
                assert HighwayBuilder.crewReturnTarget(dx, dz, work, work, shared) == null;
                for (double retreat : new double[] {.12, .15, .5, 1, 2, 6}) {
                    var player = work.add(-dx * retreat, 0, -dz * retreat);
                    var target = HighwayBuilder.crewReturnTarget(dx, dz, player, work, shared);
                    assert target != null && target.subtract(player).dot(new net.minecraft.world.phys.Vec3(dx, 0, dz)) > 0
                        : "Both crew modes must walk forward from a retreat, not wait on their own progress limit";
                    assert HighwayPlan.reachedRow(12, 12 - retreat, 512) < 12 : "Do not fake progress before walking";
                    // Even the earliest arrival accepted by the existing .15 walking tolerance reaches the reported row.
                    var landing = target.add(-dx * .149, 0, -dz * .149);
                    double physical = 12 + landing.subtract(work).dot(new net.minecraft.world.phys.Vec3(dx, 0, dz));
                    assert HighwayPlan.reachedRow(12, physical, 512) == 12;
                    assert HighwayBuilder.crewReturnTarget(dx, dz, landing, work, shared) == null;
                }
                assert HighwayBuilder.crewReturnTarget(dx, dz, work.add(dx * 2, 0, dz * 2), work, shared) == null
                    : "Passing the work center must not cause an unnecessary backward walk";
            }
            var sideways = work.add(dz, 0, -dx);
            assert HighwayBuilder.crewReturnTarget(dx, dz, sideways, work, false) == null : "Lanes keep their existing lateral reach behavior";
            assert HighwayBuilder.crewReturnTarget(dx, dz, sideways, work, true) != null : "Break Order returns to the shared center lane";
            var ahead = sideways.add(dx * 2, 0, dz * 2);
            var alignment = HighwayBuilder.crewReturnTarget(dx, dz, ahead, work, true);
            assert alignment != null && Math.abs(alignment.subtract(ahead).dot(new net.minecraft.world.phys.Vec3(dx, 0, dz))) < 1e-9
                : "Shared-lane alignment while ahead must only move sideways";
        }
        try (var bytes = HighwayBuilder.class.getResourceAsStream("HighwayBuilder.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var returning = methodCalls(compiled, "returnToCrewWorkRow");
            assert returning.containsAll(List.of("crewDetachedSupply", "crewReturnTarget", "walkToWorkPosition"));
            assert !returning.contains("readyToAdvance") && !returning.contains("setState") && !returning.contains("clear")
                : "Catch-up neither waits on next-row permission nor resets jobs/verification";
            for (String method : List.of("walkToWorkPosition", "advanceRoad")) {
                var code = compiled.methods().stream().filter(m -> m.methodName().equalsString(method)).findFirst().orElseThrow()
                    .code().orElseThrow().elementList().stream().filter(java.lang.classfile.Instruction.class::isInstance).toList();
                for (int i = 1; i < code.size(); i++) if (code.get(i) instanceof java.lang.classfile.instruction.InvokeInstruction call && call.name().equalsString("waitForMob"))
                    assert ((java.lang.classfile.Instruction) code.get(i - 1)).opcode() == java.lang.classfile.Opcode.ICONST_1
                        : "Only walking may pass active coworkers; placement still uses the strict overload";
            }
        }
        boolean checked = false;
        for (int i = 1; i < 30; i++) try (var bytes = HighwayBuilder.class.getResourceAsStream("HighwayBuilder$State$" + i + ".class")) {
            if (bytes == null) continue;
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var calls = methodCalls(compiled, "tick");
            if (calls.contains("readyToAdvance")) {
                assert calls.indexOf("returnToCrewWorkRow") >= 0 && calls.indexOf("returnToCrewWorkRow") < calls.indexOf("readyToAdvance");
                checked = true;
            }
        }
        assert checked : "Catch-up must be wired into the actual Forward state";
    }

    private static void miningClassification() throws Exception {
        assert !HighwayBuilder.shouldDoubleMine(false, 1, false) : "An instant-breaking pick must bypass double mining even while holding paving";
        assert !HighwayBuilder.shouldDoubleMine(false, 1.2, false);
        assert HighwayBuilder.shouldDoubleMine(false, .02, false) : "Slow obsidian still uses double mining";
        assert HighwayBuilder.shouldDoubleMine(false, .75, false);
        assert !HighwayBuilder.shouldDoubleMine(false, .75, true) : "Preserve SpeedMine's faster instant path";
        assert HighwayBuilder.shouldDoubleMine(false, .5, true);
        assert !HighwayBuilder.shouldDoubleMine(true, .02, false);
        for (double invalid : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
            assert !HighwayBuilder.shouldDoubleMine(false, invalid, false);
        try (var bytes = HighwayBuilder.class.getResourceAsStream("HighwayBuilder$State.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            assert methodCalls(compiled, "findAndMoveBestToolToHotbar").contains("findBestToolSlot")
                : "Execution and classification must share the same tool-selection policy";
            var selection = methodCalls(compiled, "findBestToolSlot");
            assert selection.contains("getScore") && !selection.contains("swap") && !selection.contains("move")
                : "Candidate scanning cannot alter inventory or the active tool";
            var classification = compiled.methods().stream().filter(m -> m.methodName().stringValue().startsWith("lambda$mine$"))
                .flatMap(m -> methodCalls(compiled, m.methodName().stringValue()).stream()).toList();
            assert classification.containsAll(List.of("findBestToolSlot", "getBreakDelta", "shouldDoubleMine", "computeIfAbsent"));
            assert !classification.contains("canInstaBreak") && !classification.contains("getDestroyProgress")
                : "Double-mine classification cannot accidentally use the held paving item's speed";
        }
        try (var bytes = HighwayBuilder.class.getResourceAsStream("HighwayBuilder.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            assert methodCalls(compiled, "onServerBlockUpdate").contains("workChanged");
            assert methodCalls(compiled, "onServerBlockAck").contains("workChanged")
                : "Both block updates and prediction ACKs wake crew verification";
        }
    }

    private static void speedMinePrediction() throws Exception {
        try (var bytes = HighwayBuilder.class.getResourceAsStream("/dev/monocle/client/mixin/MultiPlayerGameModeMixin.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var hook = compiled.methods().stream().filter(method -> method.methodName().equalsString("onStartDestroyBlock")).findFirst().orElseThrow();
            var calls = hook.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(call -> call.name().stringValue()).toList();
            assert !calls.contains("destroyBlock") : "Fast breaking must not replace a block before the prediction handler captures its server state";
            assert calls.stream().filter("startPrediction"::equals).count() == 2 : "Keep exactly one START and one STOP prediction";
            assert calls.containsAll(List.of("isCancelled", "instamine", "filter", "getDestroyProgress", "setReturnValue"))
                : "The prediction fix must preserve existing activation, cancellation, mining speed and block filters";
            int starts = 0, stops = 0;
            for (var method : compiled.methods()) {
                if (!method.methodName().stringValue().startsWith("lambda$onStartDestroyBlock$")) continue;
                var code = method.code().orElseThrow().elementList();
                boolean start = code.stream().anyMatch(element -> element instanceof java.lang.classfile.instruction.FieldInstruction field && field.name().equalsString("START_DESTROY_BLOCK"));
                boolean stop = code.stream().anyMatch(element -> element instanceof java.lang.classfile.instruction.FieldInstruction field && field.name().equalsString("STOP_DESTROY_BLOCK"));
                long destroys = code.stream().filter(element -> element instanceof java.lang.classfile.instruction.InvokeInstruction call && call.name().equalsString("destroyBlock")).count();
                if (start) { starts++; assert destroys == 1 : "The START prediction must own the local world mutation"; }
                if (stop) { stops++; assert destroys == 0 : "STOP must not predict another destruction"; }
            }
            assert starts == 1 && stops == 1;
        }
    }

    private static void shulkerBatching() {
        java.util.function.Predicate<ItemStack> useful = box -> box
            .getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItemCopyStream()
            .anyMatch(stack -> stack.is(Items.OBSIDIAN));
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.OBSIDIAN, 64))));
        for (int size : new int[] {27, 54}) {
            SimpleContainer chest = new SimpleContainer(size);
            chest.setItem(0, new ItemStack(Items.OBSIDIAN, 64));
            chest.setItem(1, new ItemStack(Items.SHULKER_BOX));
            chest.setItem(size - 3, box.copy());
            chest.setItem(size - 2, box.copy());
            chest.setItem(size - 1, box.copy());
            assert HighwayBuilder.shulkerRestockSlot(chest, useful, 0, 2) == size - 3
                : "Search useful boxes across the entire real single or double inventory";
            assert HighwayBuilder.shulkerRestockSlot(chest, stack -> true, 0, 2) == 1
                : "Even a permissive predicate cannot select raw items as a shulker";
            assert ItemStack.matches(chest.getItem(size - 3), box)
                : "Looking up the next box must not modify it before the actual transfer";

            SimpleContainer player = new SimpleContainer(41);
            List<Slot> slots = new ArrayList<>();
            for (int i = 0; i < 36; i++) {
                player.setItem(i, new ItemStack(Items.DIAMOND, 64));
                slots.add(new Slot(player, i, 0, 0));
            }
            // Default three empty slots, two chest pickups and one future unpacking slot stay free.
            int reserve = 6;
            for (int i = 0; i < reserve + 2; i++) player.setItem(i, ItemStack.EMPTY);
            for (int taken = 0; taken < 2; taken++) {
                int source = HighwayBuilder.shulkerRestockSlot(chest, useful, taken, 2);
                int destination = HighwayBuilder.restockTransferSlot(slots, player, chest.getItem(source), reserve);
                assert source == size - 3 + taken && destination == taken;
                player.setItem(destination, chest.removeItemNoUpdate(source));
            }
            assert HighwayBuilder.shulkerRestockSlot(chest, useful, 2, 2) == -1
                : "The default batch limit stops at two even when another useful box remains";
            assert HighwayBuilder.restockTransferSlot(slots, player, box, reserve) == -1
                : "Do not consume pickup, configured empty-space or future unpacking reserves";
            player.setItem(35, new ItemStack(Items.NETHERRACK, 5));
            int discard = HighwayBuilder.expendableFillerSlot(player, List.of(Blocks.NETHERRACK), List.of(Blocks.OBSIDIAN));
            assert discard == 35;
            player.removeItemNoUpdate(discard);
            assert HighwayBuilder.restockTransferSlot(slots, player, box, reserve) == 2
                : "Ejecting one expendable filler stack creates exactly one new shulker destination";
            player.setItem(2, box.copy());
            assert HighwayBuilder.restockTransferSlot(slots, player, box, reserve) == -1
                : "One available destination allows only one box, regardless of a higher batch limit";
            assert chest.getItem(size - 1).getCount() == 1 && useful.test(chest.getItem(size - 1));
            assert HighwayBuilder.shulkerRestockSlot(chest, useful, 1, 1) == -1;
            assert HighwayBuilder.shulkerRestockSlot(chest, useful, 0, 0) == -1;
            assert HighwayBuilder.shulkerRestockSlot(chest, useful, 3, 2) == -1;
        }
    }

    private static void stackedShulkerTransfer() {
        SimpleContainer chest = new SimpleContainer(54);
        SimpleContainer player = new SimpleContainer(36);
        ItemStack boxes = new ItemStack(Items.SHULKER_BOX, 3);
        boxes.set(DataComponents.MAX_STACK_SIZE, 64);
        boxes.set(DataComponents.CUSTOM_NAME, Component.literal("Road supplies"));
        boxes.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.OBSIDIAN, 64))));
        chest.setItem(53, boxes.copy());
        Slot source = new Slot(chest, 53, 0, 0);
        Slot destination = new Slot(player, 0, 0, 0);
        // These are the native slot operations used by left source / right destination / left source.
        for (int taken = 1; taken <= 2; taken++) {
            ItemStack cursor = source.safeTake(Integer.MAX_VALUE, Integer.MAX_VALUE, null);
            cursor = destination.safeInsert(cursor, 1);
            if (!cursor.isEmpty()) cursor = source.safeInsert(cursor);
            assert cursor.isEmpty() : "Return all extra boxes to the source instead of retaining a cursor stack";
            assert source.getItem().getCount() == 3 - taken;
            assert destination.getItem().getCount() == taken : "One batch step transfers exactly one, including when merging stacked boxes";
            assert ItemStack.isSameItemSameComponents(boxes, source.getItem());
            assert ItemStack.isSameItemSameComponents(boxes, destination.getItem()) : "Preserve the box name and contents";
        }
    }

    private static void miningRecovery() throws Exception {
        var left = new net.minecraft.core.BlockPos(-2, 64, 1);
        var slow = new net.minecraft.core.BlockPos(2, 64, 1);
        var far = new net.minecraft.core.BlockPos(0, 64, 4);
        var targets = List.of(left, slow, far);
        for (int tick = 0; tick < 1000; tick++) {
            assert HighwayBuilder.miningOrder(targets, slow, net.minecraft.core.BlockPos::getZ).equals(List.of(slow, left, far))
                : "Retain the current slow block, regardless of the watchdog's one-second interval";
        }
        assert HighwayBuilder.miningOrder(targets, far, net.minecraft.core.BlockPos::getZ).equals(targets)
            : "A nearer required row preempts a farther slow block";
        assert HighwayBuilder.miningOrder(targets, null, net.minecraft.core.BlockPos::getZ).equals(targets)
            : "Keep normal left-to-right order when no mine owns the tool";
        assert HighwayBuilder.miningOrder(List.of(far), slow, net.minecraft.core.BlockPos::getZ).equals(List.of(far))
            : "Never resurrect a target outside the current work list";
        assert HighwayBuilder.miningOrder(List.of(), slow, pos -> 0).isEmpty();
        for (int roadY : new int[] {-64, 0, 64, 120}) {
            for (double offset : new double[] {-0.25, -0.0000001, 0, 0.0000001, 0.25}) {
                var position = new net.minecraft.world.phys.Vec3(-12.3, roadY + offset, 9.7);
                var target = HighwayBuilder.walkingTarget(position, roadY);
                assert target.x == position.x && target.z == position.z;
                assert net.minecraft.core.BlockPos.containing(target).getY() == HighwayPlan.roadFeetY(position.y, roadY)
                    : "Route start and goal must agree on road elevation, including below zero";
                var goal = net.minecraft.core.BlockPos.containing(target);
                assert !HighwayPlan.route(new HighwayPlan.Cell(goal.getX() + 3, roadY, goal.getZ()),
                    new HighwayPlan.Cell(goal.getX(), goal.getY(), goal.getZ()), cell -> cell.y() == roadY, (from, to) -> true).isEmpty()
                    : "A clear three-block return must remain routable despite tiny destination Y errors";
            }
            var fallen = new net.minecraft.world.phys.Vec3(0, roadY - 0.26, 0);
            assert HighwayBuilder.walkingTarget(fallen, roadY).equals(fallen) : "Do not disguise a real elevation change";
        }
        assert !HighwayBuilder.miningConfirmationExpired(100_000, -1) : "Slow mining must not expire before completion was submitted";
        assert !HighwayBuilder.miningConfirmationExpired(159, 100);
        assert HighwayBuilder.miningConfirmationExpired(160, 100) : "A submitted completion expires after three seconds regardless of held item";
        assert HighwayBuilder.miningConfirmationExpired(100_000, 100);
        try (var bytes = HighwayBuilder.class.getResourceAsStream("HighwayBuilder.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            assert methodCalls(compiled, "crewNudge").contains("miningInProgress")
                && !methodCalls(compiled, "crewNudge").contains("requestPredictionFlush")
                : "Crew nudges must neither interrupt slow mining nor force a global prediction wait";
            assert methodCalls(compiled, "hasObstruction").contains("crewMayMine") && methodCalls(compiled, "needsToMine").contains("crewMayMine")
                : "Foreground priority and mining-state selection share the actual mining ownership guard";
            for (String name : List.of("setState", "tickPredictionFlush", "walkToWorkPosition")) {
                var method = compiled.methods().stream().filter(m -> m.methodName().equalsString(name)
                    && (!name.equals("setState") || m.methodTypeSymbol().parameterCount() == 2)).findFirst().orElseThrow();
                var calls = method.code().orElseThrow().elementList().stream()
                    .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                    .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(call -> call.name().stringValue()).toList();
                if (name.equals("walkToWorkPosition")) {
                    assert calls.containsAll(List.of("walkingTarget", "requestPredictionFlush", "walkingStandable", "safeStep"))
                        : "Walking must normalize goals, reconcile pending blocks and revalidate steps";
                } else assert calls.contains("stopWorkMining") : "Task transitions and explicit reconciliation must release old mining";
                assert !calls.contains("resetPredictionTracking") && !calls.contains("onServerBlockAck")
                    : "Recovery must preserve real server verification";
            }
        }
        try (var bytes = HighwayBuilder.DoubleMineBlock.class.getResourceAsStream("HighwayBuilder$DoubleMineBlock.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var method = compiled.methods().stream().filter(m -> m.methodName().equalsString("shouldRemove")).findFirst().orElseThrow();
            var calls = method.code().orElseThrow().elementList().stream()
                .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(call -> call.name().stringValue()).toList();
            assert calls.contains("miningConfirmationExpired") && !calls.contains("progress")
                : "Changing to a shulker must not disable the mining completion timeout";
        }
    }

    private static void restockStopsWalking() throws Exception {
        var origin = new net.minecraft.world.phys.Vec3(-12.3, 64, 9.7);
        for (int yaw = -360; yaw <= 360; yaw += 15) {
            var target = HighwayBuilder.backstepTarget(origin, yaw);
            assert Math.abs(target.distanceTo(origin) - 1) < 0.00001 : "Recovery retreats one block, including diagonal headings";
            assert target.y == origin.y : "Recovery must not intentionally descend";
            assert target.subtract(origin).dot(net.minecraft.world.phys.Vec3.directionFromRotation(0, yaw).normalize()) < -0.99999;
        }
        var input = new dev.monocle.client.utils.player.CustomPlayerInput();
        input.forward(true);
        input.tick();
        assert input.hasForwardImpulse();
        input.tick();
        assert input.hasForwardImpulse() : "Walking remains held across ticks until explicitly stopped";
        input.stop();
        input.tick();
        assert input.keyPresses.equals(net.minecraft.world.entity.player.Input.EMPTY);
        assert input.getMoveVector().x == 0 && input.getMoveVector().y == 0;
        input.forward(true);
        input.tick();
        assert input.hasForwardImpulse() : "An explicit walk may re-enable movement after the restock tick's stop guard";

        // Module construction needs a live GPU. Check the actual compiled entry guard without starting Minecraft.
        var stateClass = Class.forName(HighwayBuilder.class.getName() + "$State");
        java.lang.classfile.ClassModel stateCode;
        try (var bytes = stateClass.getResourceAsStream("/" + stateClass.getName().replace('.', '/') + ".class")) {
            stateCode = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
        }
        var restock = stateClass.getDeclaredField("Restock");
        restock.setAccessible(true);
        var restockClass = restock.get(null).getClass();
        try (var bytes = restockClass.getResourceAsStream("/" + restockClass.getName().replace('.', '/') + ".class")) {
            if (bytes == null) throw new AssertionError("Missing compiled Restock state");
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var tick = compiled.methods().stream()
                .filter(method -> method.methodName().equalsString("tick")).findFirst().orElseThrow();
            var instructions = tick.code().orElseThrow().elementList().stream()
                .filter(java.lang.classfile.Instruction.class::isInstance).map(java.lang.classfile.Instruction.class::cast).toList();
            assert instructions.get(1) instanceof java.lang.classfile.instruction.FieldInstruction field
                && field.name().equalsString("input") : "Restock must read the builder's movement input before any branch";
            assert instructions.get(2) instanceof java.lang.classfile.instruction.InvokeInstruction call
                && call.owner().asInternalName().equals("dev/monocle/client/utils/player/CustomPlayerInput")
                && call.name().equalsString("stop") : "Every Restock tick must stop held movement before pickup, second-chest mining, or an idle return";
            for (String name : List.of("retryReturn", "resetReturnPath", "safeBackstep", "tick")) {
                var owner = name.equals("safeBackstep") || name.equals("resetReturnPath") ? stateCode : compiled;
                var code = owner.methods().stream().filter(method -> method.methodName().equalsString(name)).findFirst().orElseThrow()
                    .code().orElseThrow().elementList();
                var calls = code.stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                    .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(call -> call.name().stringValue()).toList();
                if (name.equals("retryReturn")) {
                    assert calls.containsAll(List.of("controlsPlayer", "backstepTarget", "safeBackstep", "resetReturnPath"));
                    assert code.stream().anyMatch(element -> element instanceof java.lang.classfile.instruction.FieldInstruction field
                        && field.name().equalsString("returnPending")) : "Only returning supplies can trigger this recovery";
                }
                if (name.equals("safeBackstep")) {
                    assert calls.containsAll(List.of("onGround", "getBlockCollisions", "standable", "isWithinBounds"));
                    assert !calls.contains("noCollision") : "Backstep safety must ignore entity collisions";
                }
                if (name.equals("tick")) assert calls.containsAll(List.of("backward", "safeBackstep", "resetReturnPath", "walkToWorkPosition"));
                if (!name.equals("tick")) assert !calls.contains("toggle") && !calls.contains("resetSupplyJob") && !calls.contains("setState")
                    : "Reset pathing, never discard container ownership or the supply session";
            }
        }
        for (int ping : new int[] {0, 1, 25, 80, 125, 500, 2000, Integer.MAX_VALUE}) {
            long deadline = ping * 2_000_000L;
            assert !HighwayBuilder.sealingRetryDue(deadline - 1, ping);
            assert HighwayBuilder.sealingRetryDue(deadline, ping) && HighwayBuilder.sealingRetryDue(deadline + 1, ping)
                : "Recovery waits twice current ping, without the old two-second floor";
            assert HighwayBuilder.sealingProbeTicks(ping) == Math.max(1L, Math.ceilDiv(ping * 2L, 50));
        }
        assert HighwayBuilder.sealingRetryDue(0, -1) && HighwayBuilder.sealingProbeTicks(-1) == 1;
        var start = new net.minecraft.world.phys.Vec3(-100.5, 116, 30.5);
        for (float yaw : new float[] {-180, -90, 0, 45, 90, 180, 270}) {
            var target = HighwayBuilder.backstepTarget(start, yaw, .15);
            assert Math.abs(start.distanceTo(target) - .15) < 1e-8;
            var direction = target.subtract(start).normalize();
            assert !HighwayBuilder.sealingBackstepReached(start, start, target) : "The old .15 arrival tolerance cannot skip the entire nudge";
            assert !HighwayBuilder.sealingBackstepReached(start, start.add(direction.scale(.1)), target);
            assert HighwayBuilder.sealingBackstepReached(start, start.add(direction.scale(.14)), target);
            assert HighwayBuilder.sealingBackstepReached(start, start.add(direction.scale(.25)), target)
                : "Normal movement overshoot must stop the nudge, not keep walking backward";
            assert !HighwayBuilder.sealingBackstepReached(start, start.subtract(direction.scale(.15)), target);
            assert Math.abs(HighwayBuilder.backstepTarget(start, yaw).distanceTo(start) - 1) < 1e-8
                : "This patch does not alter the separate supply-return recovery distance";
        }
        var fill = stateClass.getDeclaredField("FillLiquids");
        for (var block : List.of(Blocks.NETHERRACK, Blocks.OBSIDIAN)) {
            assert HighwayBuilder.sealingRecoveryBlock(block.defaultBlockState(), false, false, false, false);
            for (int flags = 1; flags < 16; flags++)
                assert !HighwayBuilder.sealingRecoveryBlock(block.defaultBlockState(), (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0)
                    : "Stationary recovery must preserve plugs, pending placements, containers and players' footing";
        }
        for (var block : List.of(Blocks.AIR, Blocks.LAVA, Blocks.WATER))
            assert !HighwayBuilder.sealingRecoveryBlock(block.defaultBlockState(), false, false, false, false);
        fill.setAccessible(true);
        var fillClass = fill.get(null).getClass();
        try (var bytes = fillClass.getResourceAsStream("/" + fillClass.getName().replace('.', '/') + ".class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var code = compiled.methods().stream()
                .filter(method -> method.methodName().equalsString("recoverSealing")).findFirst().orElseThrow().code().orElseThrow().elementList();
            var calls = code.stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(call -> call.name().stringValue()).toList();
            assert calls.containsAll(List.of("sealingRetryDue", "safeBackstep", "backstepTarget", "backward", "waitForMob",
                "resetReturnPath", "requestPredictionFlush", "needsPassageSealing", "needsBarrierSealing", "getPing", "nanoTime", "sealingBackstepReached", "tickPredictionFlush"));
            assert !calls.contains("clear") && !calls.contains("resetPredictionTracking") && !calls.contains("onServerBlockAck")
                : "Recovery must await real server resolution, never fabricate confirmations or clear pending placements";
            assert !calls.contains("pauseJob") && calls.containsAll(List.of("retryFromHere", "monocle$getBreakingProgress", "sealingProbeTicks"))
                : "Footing rejection and blocked nudges must retry, not pause; advancing slow mines retain progress";
            var mining = methodCalls(compiled, "mineFromHere");
            assert mining.containsAll(List.of("doesDig", "crewMayMine", "protectedCrewPaving", "sealingRecoveryBlock", "findAndMoveBestToolToHotbar", "breakWorkBlock"));
            assert !mining.contains("reach") && !mining.contains("walkToWorkPosition") && !mining.contains("setState") && !mining.contains("pauseJob")
                : "The first retry mines from the existing position using native guarded breaking";
            assert methodCalls(compiled, "tick").contains("mineFromHere");
        }
    }

    private static void shulkerRetention() {
        try (var bytes = HighwaySupplyTest.class.getClassLoader().getResourceAsStream("dev/monocle/client/systems/modules/world/HighwayBuilder.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            for (String name : List.of("prepareCrewInventory", "crewMakeTransferRoom"))
                assert methodCalls(compiled, name).contains("discardCrewTrash") : "Every crew cleanup path must honor shulker retention";
            assert methodCalls(compiled, "discardCrewTrash").contains("discardShulker");
            assert methodCalls(compiled, "discardShulker").contains("shouldEjectShulker");
            var begin = compiled.methods().stream().filter(m -> m.methodName().equalsString("beginCrew")).findFirst().orElseThrow();
            assert begin.code().orElseThrow().elementList().stream().noneMatch(e -> e instanceof java.lang.classfile.instruction.FieldInstruction f
                && f.opcode() == java.lang.classfile.Opcode.GETFIELD && f.name().equalsString("keepShulkers")) : "Crew setup must not override Keep Shulkers";
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        var registries = VanillaRegistries.createLookup();
        java.util.function.Predicate<ItemStack> usefulSupply = stack -> stack.is(Items.OBSIDIAN) || stack.is(Items.NETHERRACK)
            || stack.is(Items.ENDER_CHEST) || stack.is(Items.DIAMOND_PICKAXE) || stack.is(Items.COOKED_BEEF);
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        assert !HighwayBuilder.shouldEjectShulker(box, true, usefulSupply, registries) : "Keep Shulkers preserves even an empty box";
        assert HighwayBuilder.shouldEjectShulker(box, false, usefulSupply, registries) : "Opting out allows empty supply boxes to be ejected";
        box.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        assert HighwayBuilder.shouldEjectShulker(box, false, usefulSupply, registries) : "Absent and explicit empty contents use the same policy";
        box.set(DataComponents.CUSTOM_NAME, Component.literal("Unrelated valuable kit"));
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND))));
        assert !HighwayBuilder.shouldEjectShulker(box, true, usefulSupply, registries) : "The safe default preserves unrelated named kits";
        assert HighwayBuilder.shouldEjectShulker(box, false, usefulSupply, registries) : "The explicit warning covers nonempty unrelated kits as well as empty boxes";
        for (var item : List.of(Items.OBSIDIAN, Items.NETHERRACK, Items.ENDER_CHEST, Items.DIAMOND_PICKAXE, Items.COOKED_BEEF)) {
            ItemStack supply = new ItemStack(item);
            if (supply.isDamageableItem()) supply.setDamageValue(supply.getMaxDamage() - 1);
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND), supply)));
            assert !HighwayBuilder.shouldEjectShulker(box, false, usefulSupply, registries) : "Keep a mixed box with any supply, independent of current task, available inventory space or tool wear";
        }
        assert !HighwayBuilder.shouldEjectShulker(new ItemStack(Items.ENDER_CHEST), false, usefulSupply, registries);
        assert !HighwayBuilder.shouldEjectShulker(ItemStack.EMPTY, false, usefulSupply, registries);

        box.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        box.set(DataComponents.CONTAINER_LOOT, new net.minecraft.world.item.component.SeededContainerLoot(net.minecraft.world.level.storage.loot.BuiltInLootTables.END_CITY_TREASURE, 0));
        assert !HighwayBuilder.shouldEjectShulker(box, false, usefulSupply, registries) : "Unresolved loot is unknown contents, not an empty box";
        box.remove(DataComponents.CONTAINER_LOOT);
        box.remove(DataComponents.CONTAINER);
        var legacy = new net.minecraft.nbt.CompoundTag();
        box.set(DataComponents.BLOCK_ENTITY_DATA, net.minecraft.world.item.component.TypedEntityData.of(net.minecraft.world.level.block.entity.BlockEntityTypes.SHULKER_BOX, legacy));
        assert !HighwayBuilder.shouldEjectShulker(box, false, usefulSupply, registries) : "Unknown legacy contents are kept, not guessed empty";
        legacy.put("Items", new net.minecraft.nbt.ListTag());
        box.set(DataComponents.BLOCK_ENTITY_DATA, net.minecraft.world.item.component.TypedEntityData.of(net.minecraft.world.level.block.entity.BlockEntityTypes.SHULKER_BOX, legacy));
        assert HighwayBuilder.shouldEjectShulker(box, false, usefulSupply, registries) : "Known empty legacy boxes may be ejected";
        var entries = new net.minecraft.nbt.ListTag();
        var ops = registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        entries.add(net.minecraft.world.ItemStackWithSlot.CODEC.encodeStart(ops, new net.minecraft.world.ItemStackWithSlot(0, new ItemStack(Items.OBSIDIAN))).getOrThrow());
        legacy.put("Items", entries);
        box.set(DataComponents.BLOCK_ENTITY_DATA, net.minecraft.world.item.component.TypedEntityData.of(net.minecraft.world.level.block.entity.BlockEntityTypes.SHULKER_BOX, legacy));
        assert !HighwayBuilder.shouldEjectShulker(box, false, usefulSupply, registries) : "Legacy supply contents remain protected";
        entries.clear();
        entries.add(new net.minecraft.nbt.CompoundTag());
        legacy.put("Items", entries);
        box.set(DataComponents.BLOCK_ENTITY_DATA, net.minecraft.world.item.component.TypedEntityData.of(net.minecraft.world.level.block.entity.BlockEntityTypes.SHULKER_BOX, legacy));
        assert !HighwayBuilder.shouldEjectShulker(box, false, usefulSupply, registries) : "Malformed legacy item data must not cause kit loss";
    }

    private static void restockMerging() {
        SimpleContainer player = new SimpleContainer(41);
        SimpleContainer chest = new SimpleContainer(27);
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) slots.add(new Slot(chest, i, 0, 0));
        for (int i = 9; i < 36; i++) slots.add(new Slot(player, i, 0, 0));
        for (int i = 0; i < 9; i++) slots.add(new Slot(player, i, 0, 0));
        slots.add(new Slot(player, 36, 0, 0));
        slots.add(new Slot(player, 40, 0, 0));
        for (int i = 0; i < 36; i++) player.setItem(i, new ItemStack(Items.DIAMOND, 64));
        ItemStack incoming = new ItemStack(Items.OBSIDIAN, 64);
        player.setItem(0, new ItemStack(Items.OBSIDIAN, 61));
        player.setItem(1, new ItemStack(Items.OBSIDIAN, 32));
        player.setItem(20, new ItemStack(Items.OBSIDIAN, 63));
        assert HighwayBuilder.restockTransferSlot(slots, player, incoming, 4) == 54 : "Top up a hotbar stack even when all 36 inventory slots are occupied";
        player.getItem(0).setCount(64);
        assert HighwayBuilder.restockTransferSlot(slots, player, incoming, 4) == 55 : "Continue into the next matching partial hotbar stack";
        player.getItem(1).setCount(64);
        assert HighwayBuilder.restockTransferSlot(slots, player, incoming, 4) == 38 : "Main inventory partial stacks are also valid destinations";
        player.getItem(20).setCount(64);
        assert HighwayBuilder.restockTransferSlot(slots, player, incoming, 4) == -1 : "Full matching stacks do not provide capacity; never overwrite other items";

        for (int i = 2; i < 6; i++) player.setItem(i, ItemStack.EMPTY);
        assert HighwayBuilder.restockTransferSlot(slots, player, incoming, 4) == -1 : "Keep the configured empty reserve plus the supply-container pickup slot";
        player.getItem(20).setCount(63);
        assert HighwayBuilder.restockTransferSlot(slots, player, incoming, 4) == 38 : "Use a partial stack without consuming the reserved empty slots";
        player.getItem(20).setCount(64);
        player.setItem(6, ItemStack.EMPTY);
        assert HighwayBuilder.restockTransferSlot(slots, player, incoming, 4) == 56 : "Use an empty player slot only after partial stacks and above the reserve";
        assert HighwayBuilder.restockTransferSlot(slots, player, incoming, Integer.MAX_VALUE) == -1 : "The capacity probe must not mistake empty slots for merge space";

        for (int i = 4; i < 7; i++) player.setItem(i, new ItemStack(Items.DIAMOND, 64));
        assert HighwayBuilder.restockTransferSlot(slots, player, incoming, 2) == -1 : "With a zero user reserve, preserve two pickup slots for the physical chest pair";
        player.setItem(4, ItemStack.EMPTY);
        assert HighwayBuilder.restockTransferSlot(slots, player, incoming, 2) == 56 : "Paired restocking may consume the third empty slot";
        player.setItem(2, incoming.copy());
        assert HighwayBuilder.restockTransferSlot(slots, player, incoming, 2) == -1 : "Stop consuming empty slots with room left for both recoveries";
        player.setItem(3, new ItemStack(Items.ENDER_CHEST));
        assert player.getItem(4).isEmpty() : "The second pickup still has room after the first chest is collected";

        ItemStack named = new ItemStack(Items.OBSIDIAN, 32);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Saved blocks"));
        player.setItem(0, named);
        assert HighwayBuilder.restockTransferSlot(slots, player, incoming, Integer.MAX_VALUE) == -1 : "Same item with different components cannot merge";
        assert HighwayBuilder.restockTransferSlot(slots, player, named.copy(), Integer.MAX_VALUE) == 54;
        assert HighwayBuilder.restockTransferSlot(slots, player, ItemStack.EMPTY, 0) == -1;

        List<Slot> restricted = List.of(new Slot(player, 0, 0, 0) {
            @Override public int getMaxStackSize(ItemStack stack) { return 32; }
        });
        assert HighwayBuilder.restockTransferSlot(restricted, player, named.copy(), 0) == -1 : "Respect the native destination capacity rather than assuming stacks of 64";
        named.setCount(31);
        assert HighwayBuilder.restockTransferSlot(restricted, player, named.copy(), Integer.MAX_VALUE) == 0;
    }

    private static void materialRefillCapacity() {
        for (int reserve : List.of(1, 4, 5)) {
            SimpleContainer player = new SimpleContainer(41), shulker = new SimpleContainer(27);
            List<Slot> menu = new ArrayList<>();
            for (int i = 0; i < 27; i++) { shulker.setItem(i, new ItemStack(Items.OBSIDIAN, 64)); menu.add(new Slot(shulker, i, 0, 0)); }
            for (int i = 0; i < 36; i++) menu.add(new Slot(player, i, 0, 0));
            player.setItem(0, new ItemStack(Items.ENDER_CHEST, 64)); // Eight hypothetical obsidian stacks must not satisfy this refill.
            for (int i = 1; i < 12; i++) player.setItem(i, new ItemStack(Items.DIAMOND, 64));
            int taken = 0;
            while (true) {
                int empty = 0; for (int i = 0; i < 36; i++) if (player.getItem(i).isEmpty()) empty++;
                boolean stop = HighwayBuilder.restockSatisfied(true, taken + 8, empty > reserve, false, taken > 0);
                if (stop) { assert empty == reserve && taken == 24 - reserve; break; }
                assert taken < 27 : "Refill must terminate at capacity";
                int destination = HighwayBuilder.restockTransferSlot(menu, player, shulker.getItem(taken), reserve);
                assert destination >= 27;
                menu.get(destination).set(shulker.removeItem(taken, 64)); taken++;
                assert taken != 1 || !HighwayBuilder.restockSatisfied(true, taken + 8, true, false, true)
                    : "One stack plus spare echests must not end a material refill with free inventory space";
            }
        }
        assert !HighwayBuilder.restockSatisfied(true, 36, false, true, true) : "Fill partial stacks even at the empty-slot reserve";
        assert !HighwayBuilder.restockSatisfied(true, 36, true, false, true) : "Expendable filler still provides usable space";
        assert !HighwayBuilder.restockSatisfied(true, 36, false, false, false) : "Capacity is not proof that the requested supply arrived";
        assert HighwayBuilder.restockSatisfied(false, 1, true, false, true) : "Tool and food quotas are unchanged";
        assert !HighwayBuilder.restockSatisfied(false, 0, true, false, true);
    }

    private static void blockConfirmation() {
        var original = Blocks.NETHERRACK.defaultBlockState();
        assert !HighwayBuilder.serverConfirmedBreak(original, original) : "An early START/STOP ACK with the original block is not completed mining";
        assert HighwayBuilder.serverConfirmedBreak(original, Blocks.AIR.defaultBlockState()) : "A later authoritative block update completes delayed mining without another ACK";
        assert HighwayBuilder.serverConfirmedBreak(original, Blocks.LAVA.defaultBlockState());
        assert !HighwayBuilder.serverConfirmedBreak(null, Blocks.AIR.defaultBlockState()) : "Untracked updates must not count as our work";
        assert !HighwayBuilder.predictionProbeDue(100, -1, -1);
        assert !HighwayBuilder.predictionProbeDue(111, 100, -1) : "Ordinary short ACK delays must not trigger probes";
        assert HighwayBuilder.predictionProbeDue(112, 100, -1);
        assert !HighwayBuilder.predictionProbeDue(130, 125, -1) : "Recent confirmed progress restarts the stale age even when requests remain in flight";
        assert !HighwayBuilder.predictionProbeDue(151, 100, 112) : "Rate-limit probes while waiting for the server";
        assert HighwayBuilder.predictionProbeDue(152, 100, 112);
    }

    private static void enderChestRecovery() {
        var chest = Blocks.ENDER_CHEST.defaultBlockState();
        assert HighwayBuilder.allowsContainerRecoveryTool(chest, false, false) : "Optional recovery must accept a normal pickaxe independently of bulk conversion or its reserve";
        assert HighwayBuilder.allowsContainerRecoveryTool(chest, false, true);
        assert !HighwayBuilder.allowsContainerRecoveryTool(chest, true, false) : "The explicit requirement must reject a non-Silk Touch pickaxe";
        assert HighwayBuilder.allowsContainerRecoveryTool(chest, true, true);
        assert HighwayBuilder.allowsContainerRecoveryTool(Blocks.SHULKER_BOX.defaultBlockState(), true, false) : "The setting must not restrict shulker recovery";
        assert HighwayBuilder.allowsContainerRecoveryTool(Blocks.NETHERRACK.defaultBlockState(), true, false) : "The setting must not restrict temporary supply protection cleanup";
    }

    private static void liquidSealing() {
        var lava = Blocks.LAVA.defaultBlockState();
        assert HighwayBuilder.sealableLiquid(lava, 49, 7) : "Seal reachable lava at the configured reach boundary";
        assert !HighwayBuilder.sealableLiquid(lava, 49.01, 7) : "Distant lava must not block reachable sealing and excavation";
        assert HighwayBuilder.sealableLiquid(lava.setValue(LiquidBlock.LEVEL, 7), 4, 7) : "Seal flowing lava as well as sources";
        assert HighwayBuilder.sealableLiquid(Blocks.WATER.defaultBlockState(), 4, 7);
        assert !HighwayBuilder.sealableLiquid(Blocks.STONE.defaultBlockState(), 4, 7);
        assert !HighwayBuilder.sealableLiquid(Blocks.AIR.defaultBlockState(), 4, 7);
        var waterlogged = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        assert !waterlogged.getFluidState().isEmpty();
        assert !HighwayBuilder.sealableLiquid(waterlogged, 4, 7) : "A waterlogged solid must not trap the liquid filling state";
        assert HighwayBuilder.needsLiquidSeal(lava, false, false, 4, 7);
        assert !HighwayBuilder.needsLiquidSeal(lava, false, false, 50, 7) : "Unreachable overhead liquid must not trap passage sealing";
        assert HighwayBuilder.needsLiquidSeal(Blocks.NETHERRACK.defaultBlockState(), true, true, 4, 7) : "A predicted inlet plug must be confirmed before mining the previous barrier";
        assert !HighwayBuilder.needsLiquidSeal(Blocks.NETHERRACK.defaultBlockState(), true, false, 4, 7) : "A confirmed solid inlet no longer blocks excavation";
        assert !HighwayBuilder.needsLiquidSeal(waterlogged, false, false, 4, 7) : "Waterlogged solids need excavation, not another filler block";
        assert HighwayBuilder.needsLiquidSeal(Blocks.AIR.defaultBlockState(), true, false, 49, 7) : "Remember a rejected plug even before lava flows back into the reopened hole";
        assert HighwayBuilder.needsLiquidSeal(lava, true, false, 49, 7) : "A confirmed plug that reopens needs another attempt";
        assert !HighwayBuilder.needsLiquidSeal(Blocks.AIR.defaultBlockState(), false, false, 4, 7) : "An intentionally excavated tunnel plug must not be rebuilt behind the player";
        assert !HighwayBuilder.needsLiquidSeal(Blocks.AIR.defaultBlockState(), true, false, 50, 7) : "Do not turn back for an out-of-reach old inlet";

        var road = List.of(Blocks.OBSIDIAN);
        assert HighwayBuilder.pavingNeedsMining(Blocks.STONE.defaultBlockState(), true, road) : "A server-restored solid must be mined and repaved automatically";
        assert !HighwayBuilder.pavingNeedsMining(Blocks.OBSIDIAN.defaultBlockState(), true, road);
        assert !HighwayBuilder.pavingNeedsMining(Blocks.AIR.defaultBlockState(), true, road) : "A rejected placement needs placing, not mining";
        assert !HighwayBuilder.pavingNeedsMining(lava, true, road) : "Paving over a liquid uses placement";
        assert !HighwayBuilder.pavingNeedsMining(Blocks.STONE.defaultBlockState(), false, road) : "Repair/Pave must still preserve existing solid roads";
    }

    private static void cachedEnderChest() {
        dev.monocle.client.utils.player.EChestMemory.clear();
        assert !dev.monocle.client.utils.player.EChestMemory.isKnown();
        assert !dev.monocle.client.utils.player.EChestMemory.isKnown(27);

        SimpleContainer doubleChest = new SimpleContainer(54);
        doubleChest.setItem(0, new ItemStack(Items.ENDER_CHEST, 2));
        doubleChest.setItem(27, new ItemStack(Items.OBSIDIAN, 32));
        doubleChest.setItem(53, new ItemStack(Items.DIAMOND_PICKAXE));
        dev.monocle.client.utils.player.EChestMemory.remember(doubleChest);
        assert dev.monocle.client.utils.player.EChestMemory.isKnown(54);
        assert dev.monocle.client.utils.player.EChestMemory.ITEMS.size() == 54;
        assert dev.monocle.client.utils.player.EChestMemory.ITEMS.get(53).is(Items.DIAMOND_PICKAXE) : "The last slot in the lower half must remain available to supply searches";
        doubleChest.getItem(27).setCount(1);
        doubleChest.setItem(53, ItemStack.EMPTY);
        assert dev.monocle.client.utils.player.EChestMemory.ITEMS.get(27).getCount() == 32 : "Closing the live inventory captures a snapshot, not mutable stack aliases";
        assert dev.monocle.client.utils.player.EChestMemory.ITEMS.get(53).is(Items.DIAMOND_PICKAXE);

        ItemStack[] preview = new ItemStack[64];
        java.util.Arrays.fill(preview, new ItemStack(Items.DIAMOND));
        dev.monocle.client.utils.player.EChestMemory.copyTo(preview);
        assert preview[27].getCount() == 32 && preview[53].is(Items.DIAMOND_PICKAXE);
        for (int i = 54; i < preview.length; i++) assert preview[i].isEmpty() : "Unused preview slots must not retain earlier contents";
        preview[27].setCount(2);
        assert dev.monocle.client.utils.player.EChestMemory.ITEMS.get(27).getCount() == 32 : "Changing a preview cannot mutate the supply snapshot";
        ItemStack[] smallPreview = new ItemStack[27];
        dev.monocle.client.utils.player.EChestMemory.copyTo(smallPreview);
        assert smallPreview[0].is(Items.ENDER_CHEST) && smallPreview[26].isEmpty() : "Existing 27-slot callers must copy safely from a double chest";
        dev.monocle.client.utils.player.EChestMemory.copyTo(new ItemStack[0]);

        dev.monocle.client.utils.player.EChestMemory.remember(new SimpleContainer(27));
        assert dev.monocle.client.utils.player.EChestMemory.isKnown(27);
        assert !dev.monocle.client.utils.player.EChestMemory.isKnown(54) : "A single-chest observation says nothing about the unseen lower half";
        assert dev.monocle.client.utils.player.EChestMemory.ITEMS.size() == 27 : "A smaller new observation must remove stale lower slots";
        dev.monocle.client.utils.player.EChestMemory.copyTo(preview);
        for (ItemStack item : preview) assert item.isEmpty();
        dev.monocle.client.utils.player.EChestMemory.clear();
        assert !dev.monocle.client.utils.player.EChestMemory.isKnown() && dev.monocle.client.utils.player.EChestMemory.ITEMS.isEmpty() : "Leaving a server clears knowledge and contents together";
    }

    private static void cursorRecovery() throws Exception {
        SimpleContainer player = new SimpleContainer(41);
        SimpleContainer chest = new SimpleContainer(27);
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) slots.add(new Slot(chest, i, 0, 0));
        for (int i = 9; i < 36; i++) slots.add(new Slot(player, i, 0, 0));
        for (int i = 0; i < 9; i++) slots.add(new Slot(player, i, 0, 0));
        slots.add(new Slot(player, 36, 0, 0));
        slots.add(new Slot(player, 40, 0, 0));
        ItemStack carried = new ItemStack(Items.OBSIDIAN, 32);

        assert HighwayBuilder.cursorRecoverySlot(slots, player, carried) == 54 : "Use the first player inventory slot, not the chest's first slot";
        player.setItem(0, new ItemStack(Items.DIAMOND, 64));
        assert HighwayBuilder.cursorRecoverySlot(slots, player, carried) == 55;
        player.setItem(0, new ItemStack(Items.OBSIDIAN, 61));
        assert HighwayBuilder.cursorRecoverySlot(slots, player, carried) == 55 : "Prefer an empty slot over a partial merge";
        for (int i = 1; i < 36; i++) player.setItem(i, new ItemStack(Items.DIAMOND, 64));
        assert HighwayBuilder.cursorRecoverySlot(slots, player, carried) == 54 : "A matching partial stack can receive items when no empty slot exists";
        player.setItem(0, new ItemStack(Items.OBSIDIAN, 64));
        assert HighwayBuilder.cursorRecoverySlot(slots, player, carried) == -1 : "Do not overwrite items or use container/equipment/offhand slots";
        ItemStack special = new ItemStack(Items.OBSIDIAN, 32);
        special.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this stack"));
        player.setItem(0, special);
        assert HighwayBuilder.cursorRecoverySlot(slots, player, carried) == -1 : "Different item components cannot merge";
        assert HighwayBuilder.cursorRecoverySlot(slots, player, special.copy()) == 54;
        assert HighwayBuilder.cursorRecoverySlot(slots, player, ItemStack.EMPTY) == -1;

        player.setItem(5, ItemStack.EMPTY);
        player.setItem(6, ItemStack.EMPTY);
        List<Slot> restricted = List.of(new Slot(player, 5, 0, 0) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        }, new Slot(player, 6, 0, 0));
        assert HighwayBuilder.cursorRecoverySlot(restricted, player, new ItemStack(Items.SHULKER_BOX)) == 1 : "Respect slot restrictions for valuable nonstackable items";

        var filler = List.of(Blocks.NETHERRACK, Blocks.OBSIDIAN, Blocks.SHULKER_BOX, Blocks.ENDER_CHEST);
        var road = List.of(Blocks.OBSIDIAN);
        ItemStack heldFiller = new ItemStack(Items.NETHERRACK, 32);
        assert HighwayBuilder.cursorRecoverySlot(slots, player, heldFiller) >= 0 : "The reported case has an apparently empty inventory slot";
        assert HighwayBuilder.cursorFillerDiscardAllowed(heldFiller.copy(), heldFiller, filler, road)
            : "Confirmed filler should be discarded directly even when a stow destination exists";
        assert !HighwayBuilder.cursorFillerDiscardAllowed(ItemStack.EMPTY, heldFiller, filler, road)
            : "A predicted filler cursor without server confirmation is never disposable";
        assert !HighwayBuilder.cursorFillerDiscardAllowed(carried, heldFiller, filler, road)
            : "A rejected valuable-item swap must not authorize dropping the predicted filler";
        assert !HighwayBuilder.cursorFillerDiscardAllowed(heldFiller, heldFiller.copyWithCount(31), filler, road)
            : "Reconfirm if the cursor changes during the direction-change wait";
        for (var item : List.of(Items.OBSIDIAN, Items.SHULKER_BOX, Items.ENDER_CHEST, Items.DIAMOND_PICKAXE)) {
            ItemStack protectedStack = new ItemStack(item);
            assert !HighwayBuilder.cursorFillerDiscardAllowed(protectedStack.copy(), protectedStack, filler, road)
                : "Road materials, containers and tools remain protected even after failed stows";
        }
        try (var bytes = HighwayBuilder.class.getResourceAsStream("HighwayBuilder.class")) {
            if (bytes == null) throw new AssertionError("Missing compiled HighwayBuilder");
            var recover = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes()).methods().stream()
                .filter(method -> method.methodName().equalsString("recoverCursor")).findFirst().orElseThrow();
            int discard = -1, failures = -1, destination = -1, index = 0;
            for (var element : recover.code().orElseThrow().elementList()) {
                if (element instanceof java.lang.classfile.instruction.InvokeInstruction call) {
                    if (call.name().equalsString("cursorFillerDiscardAllowed")) discard = index;
                    if (call.name().equalsString("cursorRecoverySlot")) destination = index;
                }
                if (element instanceof java.lang.classfile.instruction.FieldInstruction field
                    && field.opcode() == java.lang.classfile.Opcode.GETFIELD && field.name().equalsString("cursorStoreFailures")) failures = index;
                index++;
            }
            assert discard >= 0 && failures > discard && destination > discard
                : "Filler disposal must run before the repeated-stow pause or empty-slot check";
        }
        for (int i = 0; i < 36; i++) player.setItem(i, new ItemStack(Items.DIAMOND, 64));
        player.setItem(0, new ItemStack(Items.OBSIDIAN));
        player.setItem(1, new ItemStack(Items.SHULKER_BOX));
        player.setItem(2, new ItemStack(Items.ENDER_CHEST));
        player.setItem(9, new ItemStack(Items.NETHERRACK, 32));
        player.setItem(35, new ItemStack(Items.NETHERRACK, 5));
        player.setItem(36, new ItemStack(Items.NETHERRACK));
        player.setItem(40, new ItemStack(Items.NETHERRACK));
        chest.setItem(0, new ItemStack(Items.NETHERRACK));
        assert HighwayBuilder.cursorFillerSwapSlot(slots, player, carried, filler, road) == 53
            : "Stow a valuable held stack by exchanging the smallest filler in all 36 player slots, never a chest, equipment, offhand, road or container stack";
        List<ItemStack> before = new ArrayList<>();
        for (int i = 0; i < player.getContainerSize(); i++) before.add(player.getItem(i).copy());
        assert HighwayBuilder.cursorFillerSwapSlot(slots, player, new ItemStack(Items.SHULKER_BOX), filler, road) == 53;
        for (int i = 0; i < before.size(); i++) assert ItemStack.matches(before.get(i), player.getItem(i))
            : "Choosing a cursor swap must not predict the exchange or discard anything";
        assert chest.getItem(0).getCount() == 1 && carried.is(Items.OBSIDIAN) && carried.getCount() == 32;
        List<Slot> constrained = List.of(new Slot(player, 35, 0, 0) {
            @Override public int getMaxStackSize(ItemStack stack) { return 16; }
        }, new Slot(player, 9, 0, 0) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        }, new Slot(player, 35, 0, 0) {
            @Override public boolean isActive() { return false; }
        }, new Slot(player, 35, 0, 0) {
            @Override public boolean isFake() { return true; }
        }, new Slot(player, 9, 0, 0));
        assert HighwayBuilder.cursorFillerSwapSlot(constrained, player, carried, filler, road) == 4
            : "The full cursor must fit a real writable destination; a partial exchange is unsafe";
        assert HighwayBuilder.cursorFillerSwapSlot(constrained.subList(0, 4), player, carried, filler, road) == -1;
        assert HighwayBuilder.cursorFillerSwapSlot(slots, player, ItemStack.EMPTY, filler, road) == -1;
        player.setItem(9, new ItemStack(Items.DIAMOND));
        player.setItem(35, new ItemStack(Items.DIAMOND));
        assert HighwayBuilder.cursorFillerSwapSlot(slots, player, carried, filler, road) == -1
            : "Without ordinary player filler, preserve every other stack even if configured as filler";

        var refresh = HighwayBuilder.cursorSyncRequest(7, HashedStack.EMPTY);
        assert refresh.containerId() == 7 && refresh.stateId() == -1;
        assert refresh.slotNum() == -1 && refresh.slotNum() != AbstractContainerMenu.SLOT_CLICKED_OUTSIDE;
        assert refresh.buttonNum() == 0 && refresh.containerInput() == ContainerInput.PICKUP;
        assert refresh.changedSlots().isEmpty() : "Refreshing a ghost cursor must not move or discard an item";

        assert HighwayBuilder.cursorStoreProgress(carried, ItemStack.EMPTY);
        assert HighwayBuilder.cursorStoreProgress(carried, new ItemStack(Items.OBSIDIAN, 29));
        assert !HighwayBuilder.cursorStoreProgress(carried, carried.copy()) : "Server synchronization alone is not stow progress";
        assert !HighwayBuilder.cursorStoreProgress(carried, new ItemStack(Items.OBSIDIAN, 64));
        assert !HighwayBuilder.cursorStoreProgress(carried, new ItemStack(Items.DIAMOND));
        assert !HighwayBuilder.cursorStoreProgress(carried, special.copyWithCount(1));
    }
}
