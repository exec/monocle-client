/* Monocle inventory transfers. Vanilla owns the actual slot interactions. */
package dev.monocle.client.utils.player;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.function.BiConsumer;

/** One bounded, cancellable PICKUP transfer; never clicks outside the inventory. */
public final class InventoryTransfer {
    private final AbstractContainerMenu menu;
    private final InventoryLoadout.Move move;
    private final Player player;
    private Slot source, destination;
    private ItemStack expectedSource, expectedDestination, expectedCursor;
    private boolean swap, done;
    private int phase, remaining, moved;
    private String error;

    public InventoryTransfer(AbstractContainerMenu menu, InventoryLoadout.Move move, Player player) {
        this.menu = menu;
        this.move = move;
        this.player = player;
        if (menu == null || move == null || move.from() < 0 || move.to() < 0
            || move.from() >= menu.slots.size() || move.to() >= menu.slots.size() || move.from() == move.to()) {
            fail("Invalid inventory slot.");
            return;
        }

        source = menu.slots.get(move.from());
        destination = menu.slots.get(move.to());
        expectedSource = source.getItem().copy();
        expectedDestination = destination.getItem().copy();
        expectedCursor = ItemStack.EMPTY;
        remaining = move.amount();
        if (!menu.getCarried().isEmpty()) fail("Put the cursor stack away before transferring.");
        // Bundle PICKUP overrides insert/extract contents instead of performing a normal stack swap.
        else if (expectedSource.has(DataComponents.BUNDLE_CONTENTS) || expectedDestination.has(DataComponents.BUNDLE_CONTENTS)) {
            fail("Move bundles manually to preserve their contents.");
        }
        else if (!usable(source) || !usable(destination) || !source.mayPickup(player)
            || source.container == destination.container && source.getContainerSlot() == destination.getContainerSlot()) {
            fail("This slot cannot be moved.");
        } else if (expectedSource.isEmpty() || remaining <= 0 || remaining > expectedSource.getCount()
            || !destination.mayPlace(expectedSource)) {
            fail("The requested item cannot move to that slot.");
        } else {
            swap = !expectedDestination.isEmpty() && !ItemStack.isSameItemSameComponents(expectedSource, expectedDestination);
            if (swap) {
                if (remaining != expectedSource.getCount() || !destination.mayPickup(player)
                    || !source.mayPlace(expectedDestination) || limit(destination, expectedSource) < remaining
                    || limit(source, expectedDestination) < expectedDestination.getCount()) {
                    fail("These stacks cannot be swapped safely.");
                }
            } else if (limit(destination, expectedSource) - expectedDestination.getCount() < remaining
                || remaining < expectedSource.getCount() && (!source.mayPlace(expectedSource)
                    || limit(source, expectedSource) < expectedSource.getCount() - remaining)) {
                fail("There is not enough room for the requested transfer.");
            }
        }
    }

    /** Executes at most clickBudget native clicks; true means completed, cancelled, or failed. */
    public boolean tick(int clickBudget, BiConsumer<Integer, Integer> click) {
        for (int i = 0; i < clickBudget && !done; i++) {
            if (!unchanged()) {
                fail("Inventory changed during transfer. Put any cursor stack away and retry.");
                break;
            }

            int slot, button = 0, transferred = 0;
            if (phase == 0) {
                if (!source.mayPickup(player)) {
                    fail("The source slot is no longer available.");
                    break;
                }
                slot = move.from();
                expectedCursor = expectedSource;
                expectedSource = ItemStack.EMPTY;
                phase = 1;
            } else if (phase == 1) {
                slot = move.to();
                if (!destination.mayPlace(expectedCursor) || swap && !destination.mayPickup(player)) {
                    fail("The destination slot is no longer available.");
                    break;
                }
                if (swap) {
                    if (limit(destination, expectedCursor) < expectedCursor.getCount()) {
                        fail("The destination slot no longer has room.");
                        break;
                    }
                    ItemStack displaced = expectedDestination;
                    expectedDestination = expectedCursor;
                    expectedCursor = displaced;
                    transferred = remaining;
                } else {
                    int room = limit(destination, expectedCursor) - expectedDestination.getCount();
                    if (room < remaining) {
                        fail("The destination slot no longer has room.");
                        break;
                    }
                    // Left-click only when vanilla will insert exactly what remains of the request.
                    button = remaining == expectedCursor.getCount() || remaining == room ? 0 : 1;
                    transferred = button == 0 ? remaining : 1;
                    expectedDestination = expectedCursor.copyWithCount(expectedDestination.getCount() + transferred);
                    expectedCursor = expectedCursor.copyWithCount(expectedCursor.getCount() - transferred);
                }
                remaining -= transferred;
                if (remaining == 0) phase = 2;
            } else {
                if (!source.mayPlace(expectedCursor) || limit(source, expectedCursor) < expectedCursor.getCount()) {
                    fail("The remainder cannot return to its original slot. Put the cursor stack away.");
                    break;
                }
                slot = move.from();
                expectedSource = expectedCursor;
                expectedCursor = ItemStack.EMPTY;
            }

            click.accept(slot, button);
            if (!unchanged()) {
                fail("The inventory did not accept that transfer. Put any cursor stack away and retry.");
                break;
            }
            moved += transferred;
            done |= phase == 2 && expectedCursor.isEmpty();
        }
        return done;
    }

    public void cancel() { done = true; }
    public boolean failed() { return error != null; }
    public String error() { return error; }
    public int moved() { return moved; }

    private boolean unchanged() {
        return move.from() < menu.slots.size() && move.to() < menu.slots.size()
            && menu.slots.get(move.from()) == source && menu.slots.get(move.to()) == destination
            && usable(source) && usable(destination)
            && ItemStack.matches(expectedSource, source.getItem())
            && ItemStack.matches(expectedDestination, destination.getItem())
            && ItemStack.matches(expectedCursor, menu.getCarried());
    }

    private static boolean usable(Slot slot) { return slot.isActive() && !slot.isFake(); }
    private static int limit(Slot slot, ItemStack stack) { return Math.min(slot.getMaxStackSize(stack), stack.getMaxStackSize()); }
    private void fail(String message) { error = message; done = true; }
}
