package dev.monocle.client.utils.player;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;

/** Run with assertions enabled; native Slot operations emulate ordinary PICKUP without a game world. */
public final class InventoryTransferTest {
    public static void main(String[] args) {
        boolean assertionsEnabled = false;
        assert assertionsEnabled = true;
        if (!assertionsEnabled) throw new IllegalStateException("Run with assertions enabled (-ea).");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(DataComponentInitializers.PendingComponents::apply);

        for (int amount : new int[] { 1, 2, 15, 31, 32, 63, 64 }) {
            for (int existing : new int[] { 0, 64 - amount }) {
                Menu menu = new Menu(new ItemStack(Items.OBSIDIAN, 64), new ItemStack(Items.OBSIDIAN, existing));
                InventoryTransfer transfer = new InventoryTransfer(menu, new InventoryLoadout.Move(0, 1, amount), null);
                run(menu, transfer);
                assert transfer.moved() == amount;
                assert menu.slots.get(0).getItem().getCount() == 64 - amount;
                assert menu.slots.get(1).getItem().getCount() == existing + amount;
                if (existing == 64 - amount) assert menu.clicks <= 3 : "A capacity-limited merge needs no per-item clicks";
            }
        }

        Menu full = new Menu(new ItemStack[36]);
        for (Slot slot : full.slots) slot.set(new ItemStack(Items.DIAMOND, 64));
        full.slots.get(0).set(new ItemStack(Items.OBSIDIAN, 64));
        full.slots.get(35).set(new ItemStack(Items.STONE, 64));
        run(full, new InventoryTransfer(full, new InventoryLoadout.Move(0, 35, 64), null));
        assert full.slots.get(0).getItem().is(Items.STONE) && full.slots.get(35).getItem().is(Items.OBSIDIAN);
        assert full.clicks == 3 : "A full inventory can swap without an empty slot";

        ItemStack named = new ItemStack(Items.OBSIDIAN, 16);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep me"));
        Menu names = new Menu(named, new ItemStack(Items.OBSIDIAN, 16));
        reject(names, new InventoryLoadout.Move(0, 1, 8));
        run(names, new InventoryTransfer(names, new InventoryLoadout.Move(0, 1, 16), null));
        assert names.slots.get(1).getItem().get(DataComponents.CUSTOM_NAME).getString().equals("Keep me");
        assert !names.slots.get(0).getItem().has(DataComponents.CUSTOM_NAME);

        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.OBSIDIAN, 32))));
        Menu boxes = new Menu(box, new ItemStack(Items.SHULKER_BOX));
        run(boxes, new InventoryTransfer(boxes, new InventoryLoadout.Move(0, 1, 1), null));
        assert ItemStack.matches(box, boxes.slots.get(1).getItem()) : "Container contents survive a swap";
        reject(new Menu(new ItemStack(Items.BUNDLE), new ItemStack(Items.DIAMOND)), new InventoryLoadout.Move(0, 1, 1));
        reject(new Menu(new ItemStack(Items.DIAMOND), new ItemStack(Items.BUNDLE)), new InventoryLoadout.Move(0, 1, 1));

        Menu limited = new Menu(new ItemStack(Items.OBSIDIAN, 64), ItemStack.EMPTY);
        limited.slots.set(1, new Slot(limited.inventory, 1, 0, 0) {
            @Override public int getMaxStackSize() { return 16; }
        });
        reject(limited, new InventoryLoadout.Move(0, 1, 17));
        run(limited, new InventoryTransfer(limited, new InventoryLoadout.Move(0, 1, 16), null));
        assert limited.slots.get(1).getItem().getCount() == 16 && limited.clicks == 3;

        for (InventoryLoadout.Move invalid : List.of(new InventoryLoadout.Move(-999, 1, 1),
            new InventoryLoadout.Move(0, 2, 1), new InventoryLoadout.Move(0, 0, 1),
            new InventoryLoadout.Move(0, 1, 0), new InventoryLoadout.Move(0, 1, -1), new InventoryLoadout.Move(0, 1, 65))) {
            reject(new Menu(new ItemStack(Items.OBSIDIAN, 64), ItemStack.EMPTY), invalid);
        }
        Menu locked = new Menu(new ItemStack(Items.OBSIDIAN), ItemStack.EMPTY);
        locked.slots.set(0, new Slot(locked.inventory, 0, 0, 0) {
            @Override public boolean mayPickup(Player player) { return false; }
        });
        reject(locked, new InventoryLoadout.Move(0, 1, 1));
        Menu readonly = new Menu(new ItemStack(Items.OBSIDIAN), ItemStack.EMPTY);
        readonly.slots.set(1, new Slot(readonly.inventory, 1, 0, 0) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        });
        reject(readonly, new InventoryLoadout.Move(0, 1, 1));

        Menu cancelled = new Menu(new ItemStack(Items.OBSIDIAN, 64), ItemStack.EMPTY);
        InventoryTransfer transfer = new InventoryTransfer(cancelled, new InventoryLoadout.Move(0, 1, 7), null);
        assert !transfer.tick(0, cancelled::pickup) && cancelled.clicks == 0;
        assert !transfer.tick(1, cancelled::pickup) && cancelled.getCarried().getCount() == 64;
        List<ItemStack> cancelledContents = contents(cancelled);
        transfer.cancel();
        assert transfer.tick(4, (slot, button) -> { throw new AssertionError("No late clicks after cancellation"); });
        assert !transfer.failed() && ItemStack.listMatches(cancelledContents, contents(cancelled));
        Menu cancelledDuringClick = new Menu(new ItemStack(Items.OBSIDIAN, 64), ItemStack.EMPTY);
        InventoryTransfer duringClick = new InventoryTransfer(cancelledDuringClick, new InventoryLoadout.Move(0, 1, 7), null);
        assert duringClick.tick(4, (slot, button) -> {
            cancelledDuringClick.pickup(slot, button);
            duringClick.cancel();
        });
        assert cancelledDuringClick.clicks == 1 && cancelledDuringClick.getCarried().getCount() == 64;

        for (int mutation : new int[] { 0, 1, 2 }) {
            Menu changed = new Menu(new ItemStack(Items.OBSIDIAN, 64), ItemStack.EMPTY);
            transfer = new InventoryTransfer(changed, new InventoryLoadout.Move(0, 1, 7), null);
            if (mutation == 0) changed.slots.get(0).set(new ItemStack(Items.STONE, 64));
            else {
                assert !transfer.tick(1, changed::pickup);
                if (mutation == 1) changed.slots.get(1).set(new ItemStack(Items.DIAMOND));
                else changed.setCarried(new ItemStack(Items.STONE, 64));
            }
            List<ItemStack> changedContents = contents(changed);
            assert transfer.tick(4, (slot, button) -> { throw new AssertionError("Replan after external mutation"); });
            assert transfer.failed() && ItemStack.listMatches(changedContents, contents(changed));
        }

        Menu denied = new Menu(new ItemStack(Items.OBSIDIAN), ItemStack.EMPTY);
        transfer = new InventoryTransfer(denied, new InventoryLoadout.Move(0, 1, 1), null);
        int[] deniedClicks = { 0 };
        assert transfer.tick(4, (slot, button) -> deniedClicks[0]++);
        assert transfer.failed() && transfer.moved() == 0 && deniedClicks[0] == 1 : "Denied prediction stops immediately";
        denied.setCarried(new ItemStack(Items.DIAMOND));
        reject(denied, new InventoryLoadout.Move(0, 1, 1));
        System.out.println("Inventory transfer checks passed: exact quantities, stack/component conservation, full-inventory swaps, click budgets, cancellation and stale-state guards.");
    }

    private static void reject(Menu menu, InventoryLoadout.Move move) {
        List<ItemStack> before = contents(menu);
        InventoryTransfer transfer = new InventoryTransfer(menu, move, null);
        assert transfer.tick(4, (slot, button) -> { throw new AssertionError("Invalid transfer cannot click"); });
        assert transfer.failed() && transfer.error() != null;
        assert ItemStack.listMatches(before, contents(menu));
    }

    private static void run(Menu menu, InventoryTransfer transfer) {
        List<ItemStack> before = contents(menu);
        int ticks = 0;
        boolean done;
        do {
            int clicks = menu.clicks;
            done = transfer.tick(4, menu::pickup);
            assert menu.clicks - clicks <= 4 : "Click budget includes pickup and remainder return";
            List<ItemStack> after = contents(menu);
            for (ItemStack stack : before) assert count(before, stack) == count(after, stack) : "Never lose an item or its components";
            for (ItemStack stack : after) assert count(before, stack) == count(after, stack) : "Never create an item or change its components";
            assert ++ticks < 100 : "Transfer must terminate";
        } while (!done);
        assert !transfer.failed() : transfer.error();
        assert menu.getCarried().isEmpty() : "Successful transfers return any remainder";
    }

    private static int count(List<ItemStack> contents, ItemStack template) {
        return contents.stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, template)).mapToInt(ItemStack::getCount).sum();
    }

    private static List<ItemStack> contents(Menu menu) {
        List<ItemStack> result = new ArrayList<>();
        for (Slot slot : menu.slots) result.add(slot.getItem().copy());
        result.add(menu.getCarried().copy());
        return result;
    }

    private static final class Menu extends AbstractContainerMenu {
        final SimpleContainer inventory;
        int clicks;

        Menu(ItemStack... stacks) {
            super(null, 1);
            inventory = new SimpleContainer(stacks.length);
            for (int i = 0; i < stacks.length; i++) {
                if (stacks[i] != null) inventory.setItem(i, stacks[i].copy());
                addSlot(new Slot(inventory, i, 0, 0));
            }
        }

        void pickup(int id, int button) {
            assert id >= 0 && id < slots.size() : "Never drop or click outside";
            assert button == 0 || button == 1;
            clicks++;
            Slot slot = slots.get(id);
            ItemStack cursor = getCarried();
            if (cursor.isEmpty()) setCarried(slot.safeTake(button == 0 ? Integer.MAX_VALUE : (slot.getItem().getCount() + 1) / 2, Integer.MAX_VALUE, null));
            else if (slot.getItem().isEmpty() || ItemStack.isSameItemSameComponents(cursor, slot.getItem())) {
                setCarried(slot.safeInsert(cursor, button == 0 ? cursor.getCount() : 1));
            } else if (slot.mayPickup(null) && slot.mayPlace(cursor) && cursor.getCount() <= slot.getMaxStackSize(cursor)) {
                ItemStack displaced = slot.safeTake(Integer.MAX_VALUE, Integer.MAX_VALUE, null);
                setCarried(slot.safeInsert(cursor));
                assert getCarried().isEmpty();
                setCarried(displaced);
            }
        }

        @Override public ItemStack quickMoveStack(Player player, int slot) { throw new AssertionError("PICKUP only"); }
        @Override public boolean stillValid(Player player) { return true; }
    }
}
