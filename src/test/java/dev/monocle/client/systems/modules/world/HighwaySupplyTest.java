package dev.monocle.client.systems.modules.world;

import java.util.List;
import java.util.ArrayList;

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
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(DataComponentInitializers.PendingComponents::apply);

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
        cursorRecovery();
        restockStopsWalking();
        miningRecovery();
        restockMerging();
        HighwayFarmingTest.check();
        inventorySupplySelection();
        shulkerBatching();
        stackedShulkerTransfer();
        shulkerRetention();
        liquidSealing();
        enderChestRecovery();
        cachedEnderChest();
        blockConfirmation();
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
                if (name.equals("safeBackstep")) assert calls.containsAll(List.of("onGround", "noCollision", "standable", "isWithinBounds"));
                if (name.equals("tick")) assert calls.containsAll(List.of("backward", "safeBackstep", "resetReturnPath", "walkToWorkPosition"));
                if (!name.equals("tick")) assert !calls.contains("toggle") && !calls.contains("resetSupplyJob") && !calls.contains("setState")
                    : "Reset pathing, never discard container ownership or the supply session";
            }
        }
        for (int tick = 0; tick < 40; tick++) assert !HighwayBuilder.sealingRetryDue(tick);
        assert HighwayBuilder.sealingRetryDue(40) && HighwayBuilder.sealingRetryDue(41) : "Retry sealing at two active seconds, not three or twenty";
        var fill = stateClass.getDeclaredField("FillLiquids");
        fill.setAccessible(true);
        var fillClass = fill.get(null).getClass();
        try (var bytes = fillClass.getResourceAsStream("/" + fillClass.getName().replace('.', '/') + ".class")) {
            var code = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes()).methods().stream()
                .filter(method -> method.methodName().equalsString("recoverSealing")).findFirst().orElseThrow().code().orElseThrow().elementList();
            var calls = code.stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(call -> call.name().stringValue()).toList();
            assert calls.containsAll(List.of("sealingRetryDue", "safeBackstep", "backstepTarget", "backward", "waitForMob",
                "resetReturnPath", "requestPredictionFlush", "needsPassageSealing", "needsBarrierSealing"));
            assert !calls.contains("clear") && !calls.contains("resetPredictionTracking") && !calls.contains("onServerBlockAck")
                : "Recovery must await real server resolution, never fabricate confirmations or clear pending placements";
        }
    }

    private static void shulkerRetention() {
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
