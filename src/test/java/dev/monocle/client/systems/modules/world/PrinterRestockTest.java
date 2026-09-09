package dev.monocle.client.systems.modules.world;

import dev.monocle.client.utils.player.InventoryLoadout;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Native inventory/geometry assertions plus compiled guards around world-changing boundaries. */
public final class PrinterRestockTest {
    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions) throw new IllegalStateException("Run with -ea.");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(DataComponentInitializers.PendingComponents::apply);

        inventoryTargets();
        for (boolean borrowed : new boolean[] {false, true}) for (boolean owned : new boolean[] {false, true})
            assert PrinterRestock.mayRecover(borrowed, owned) == (owned && !borrowed) : "Borrowed or unconfirmed containers must never be mined";
        for (boolean borrowed : new boolean[] {false, true}) for (boolean block : new boolean[] {false, true}) for (boolean item : new boolean[] {false, true})
            assert PrinterRestock.placementOwned(borrowed, block, item) == (!borrowed && block && item)
                : "Ownership needs both the authoritative placed block and our consumed inventory item";
        List<AABB> build = List.of(new AABB(0, 0, 0, 10, 20, 10));
        assert !PrinterRestock.outsideBuild(new BlockPos(5, 10, 5), build, 8) : "Empty cells inside the enclosing build volume are protected too";
        assert !PrinterRestock.outsideBuild(new BlockPos(17, 0, 5), build, 8);
        assert PrinterRestock.outsideBuild(new BlockPos(18, 0, 5), build, 8);
        assert !PrinterRestock.outsideBuild(new BlockPos(-8, 0, 5), build, 8);
        assert PrinterRestock.outsideBuild(new BlockPos(-9, 0, 5), build, 8);
        assert PrinterRestock.outsideBuild(new BlockPos(5, 28, 5), build, 8) : "The enclosing 3D clearance is respected above the schematic too";
        compiledBoundaries();
        System.out.println("Printer restock checks passed: inventory targets, exact merging/reserves, build exclusion, owned recovery and native action guards.");
    }

    private static void inventoryTargets() {
        var inventory = new SimpleContainer(36);
        var storage = new SimpleContainer(27);
        var slots = new ArrayList<Slot>();
        for (int i = 0; i < 27; i++) slots.add(new Slot(storage, i, 0, 0));
        for (int i = 9; i < 36; i++) slots.add(new Slot(inventory, i, 0, 0));
        for (int i = 0; i < 9; i++) slots.add(new Slot(inventory, i, 0, 0));
        var targets = Map.of(Items.OBSIDIAN, 80, Items.NETHERRACK, 32);
        inventory.setItem(0, new ItemStack(Items.OBSIDIAN, 60));
        inventory.setItem(18, new ItemStack(Items.OBSIDIAN, 16));
        storage.setItem(0, new ItemStack(Items.OBSIDIAN, 64));
        storage.setItem(1, new ItemStack(Items.NETHERRACK, 64));
        storage.setItem(2, new ItemStack(Items.DIAMOND, 64));
        assert PrinterRestock.missing(inventory, targets, Items.OBSIDIAN) == 4 : "Targets are total carried counts, not an additional deficit";
        assert PrinterRestock.missing(inventory, targets, Items.DIAMOND) == 0;
        assert !PrinterRestock.fulfilled(inventory, targets);
        var starting = Map.of(Items.OBSIDIAN, 76, Items.NETHERRACK, 0);
        assert !PrinterRestock.madeProgress(inventory, starting);
        InventoryLoadout.Move move = PrinterRestock.materialMove(slots, inventory, targets, 1);
        assert move != null && move.from() == 0 && move.to() == 54 && move.amount() == 4 : "Top up the native hotbar stack exactly";
        assert inventory.getItem(0).getCount() == 60 && storage.getItem(0).getCount() == 64 : "Planning never mutates real contents";

        inventory.setItem(0, new ItemStack(Items.OBSIDIAN, 64));
        assert PrinterRestock.madeProgress(inventory, starting) && !PrinterRestock.fulfilled(inventory, targets)
            : "A useful partial refill can resume building even when other materials are still missing";
        assert !PrinterRestock.madeProgress(inventory, Map.of(Items.NETHERRACK, 0)) : "Unrequested materials do not count as refill progress";
        for (int i = 1; i < 36; i++) if (inventory.getItem(i).isEmpty()) inventory.setItem(i, new ItemStack(Items.DIAMOND, 64));
        inventory.setItem(35, ItemStack.EMPTY);
        assert PrinterRestock.materialMove(slots, inventory, targets, 1) == null : "Do not consume the only recovery slot";
        move = PrinterRestock.materialMove(slots, inventory, targets, 0);
        assert move != null && move.from() == 1 && move.amount() == 32 : "Borrowed chests do not require their own recovery slot";
        inventory.setItem(3, new ItemStack(Items.NETHERRACK, 24));
        move = PrinterRestock.materialMove(slots, inventory, targets, 1);
        assert move != null && move.to() == 57 && move.amount() == 8 : "An existing matching stack can be filled even while empties are reserved";
        inventory.getItem(3).set(DataComponents.CUSTOM_NAME, Component.literal("Keep distinct"));
        assert PrinterRestock.materialMove(slots, inventory, targets, 1) == null : "Component-distinct materials must not be merged";
        inventory.setItem(3, new ItemStack(Items.NETHERRACK, 32));
        assert PrinterRestock.fulfilled(inventory, targets);
        assert PrinterRestock.materialMove(slots, inventory, targets, 0) == null : "Do not fill inventory with extra supplies or unrelated valuables";
    }

    private static void compiledBoundaries() throws Exception {
        ClassModel model;
        try (var stream = PrinterRestock.class.getResourceAsStream("PrinterRestock.class")) {
            if (stream == null) throw new IllegalStateException("Missing compiled helper.");
            model = ClassFile.of().parse(stream.readAllBytes());
        }
        for (MethodModel method : model.methods()) for (InvokeInstruction call : calls(method)) {
            String owner = call.owner().asInternalName(), name = call.name().stringValue();
            assert !(owner.endsWith("/InvUtils") && (name.equals("drop") || name.equals("dropHand"))) : "Printer restocking never discards equipment or schematic materials";
            assert !(owner.endsWith("/Inventory") && (name.equals("setItem") || name.equals("removeItem") || name.equals("clearContent"))) : "Use real native container transactions, never direct inventory writes";
            if (owner.endsWith("/MultiPlayerGameMode") && name.equals("handleContainerInput"))
                assert method.methodName().equalsString("click") : "All clicks pass through exact menu/screen and cancellation guards";
        }
        assert calls(method(model, "materialMove")).stream().anyMatch(call -> call.name().equalsString("restockTransferSlot")) : "Reuse the reserve-aware highway slot policy";
        assert calls(method(model, "transferSupplies")).stream().anyMatch(call -> call.name().equalsString("materialMove"));
        assert calls(method(model, "recoverBlock")).stream().anyMatch(call -> call.name().equalsString("mayRecover"));
        assert calls(method(model, "collect")).stream().anyMatch(call -> call.name().equalsString("supplyPickupComplete")) : "Ground disappearance alone cannot confirm recovery";
        assert calls(method(model, "onSupplyItemPickup")).stream().anyMatch(call -> call.name().equalsString("trackDrop"));
        assert calls(method(model, "trackDrop")).stream().anyMatch(call -> call.name().equalsString("sameSupplyDrop"));
        assert calls(method(model, "choose")).stream().anyMatch(call -> call.name().equalsString("outsideBuild")) : "Existing chests inside the protected build are excluded too";
        assert calls(method(model, "navigationFailed")).stream().anyMatch(call -> call.name().equalsString("resetContainer")) : "An inaccessible borrowed chest must allow another candidate";
        assert calls(method(model, "safeFeet")).stream().anyMatch(call -> call.name().equalsString("isWithinBounds"));
        assert calls(method(model, "findSite")).stream().anyMatch(call -> call.name().equalsString("outsideBuild"));
        assert calls(method(model, "place")).stream().anyMatch(call -> call.name().equalsString("outsideBuild")) : "Recheck protection before placing";
        assert calls(method(model, "tickTransfer")).stream().anyMatch(call -> call.name().equalsString("requestSync")) : "Native prediction is not server confirmation";
        assert calls(method(model, "partialOrFail")).stream().anyMatch(call -> call.name().equalsString("madeProgress"));
        boolean owned = false, pending = false, pickup = false, cursor = false;
        for (var element : method(model, "complete").code().orElseThrow().elementList()) {
            if (element instanceof FieldInstruction field && field.opcode() == Opcode.GETFIELD) {
                owned |= field.name().equalsString("owned");
                pending |= field.name().equalsString("placementSent");
                pickup |= field.name().equalsString("trackingDrop");
            }
            if (element instanceof InvokeInstruction call) cursor |= call.name().equalsString("getCarried");
            if (element instanceof FieldInstruction field && field.opcode() == Opcode.GETSTATIC && field.name().equalsString("Done"))
                assert owned && pending && pickup && cursor : "Full and partial success cannot abandon containers or a cursor stack";
        }
        assert calls(method(model, "onServerBlockAck")).stream().anyMatch(call -> call.name().equalsString("getBlockState")) : "Resolve ownership against vanilla's acknowledged world state";
        assert calls(method(model, "onInventory")).stream().anyMatch(call -> call.name().equalsString("countExact")) : "Confirm our item was consumed before claiming a matching placed block";
        assert calls(method(model, "confirmOwnership")).stream().anyMatch(call -> call.name().equalsString("placementOwned"));
        assert calls(method(model, "pause")).stream().anyMatch(call -> call.name().equalsString("cancel"));
        boolean guardedCallback = false, guardedMine = false;
        for (MethodModel method : model.methods()) {
            if (method.methodName().stringValue().startsWith("lambda$runAimed$")) {
                boolean epoch = false, suspended = false, arrived = false;
                for (var element : method.code().orElseThrow().elementList()) {
                    if (element instanceof FieldInstruction field && field.opcode() == Opcode.GETFIELD) {
                        epoch |= field.name().equalsString("epoch");
                        suspended |= field.name().equalsString("suspended");
                    }
                    if (element instanceof InvokeInstruction call) {
                        arrived |= call.name().equalsString("arrived");
                        if (call.owner().asInternalName().equals("java/lang/Runnable") && call.name().equalsString("run")) {
                            assert epoch && suspended && arrived : "A deferred rotation must recheck lifetime and ground arrival";
                            guardedCallback = true;
                        }
                    }
                }
            }
            if (method.methodName().stringValue().startsWith("lambda$recoverBlock$")) {
                boolean ownership = false;
                for (InvokeInstruction call : calls(method)) {
                    ownership |= call.name().equalsString("mayRecover");
                    if (call.name().equalsString("breakBlock")) { assert ownership : "Recheck container ownership inside the mining callback"; guardedMine = true; }
                }
            }
        }
        assert guardedCallback && guardedMine;
    }

    private static MethodModel method(ClassModel model, String name) {
        return model.methods().stream().filter(method -> method.methodName().equalsString(name)).findFirst().orElseThrow();
    }

    private static List<InvokeInstruction> calls(MethodModel method) {
        return method.code().stream().flatMap(code -> code.elementList().stream()).filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).toList();
    }
}
