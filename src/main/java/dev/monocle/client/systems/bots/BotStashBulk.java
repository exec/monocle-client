package dev.monocle.client.systems.bots;

import com.google.gson.JsonObject;
import dev.monocle.client.utils.player.InvUtils;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/** A bounded click burst; only exact post-move server snapshots produce receipts. */
final class BotStashBulk {
    static final int MIN_SIZE = 4, MOVES_PER_TICK = 4;
    record Move(int source, int destination, ItemStack stack) {}
    final List<Move> moves;
    final int inventoryStart;
    int sentTick, sent;
    final boolean withdrawal, quick, mayRefill;
    private final List<List<ItemStack>> inventoryStates;
    int verified, previousMenu = -1;
    boolean reopening;
    private JsonObject lastSnapshot;

    private BotStashBulk(List<Move> moves, int inventoryStart, int tick, boolean withdrawal, boolean quick,
                         boolean mayRefill, List<List<ItemStack>> states) {
        this.moves = List.copyOf(moves); this.inventoryStart = inventoryStart; sentTick = tick;
        this.withdrawal = withdrawal; this.quick = quick; this.mayRefill = mayRefill; inventoryStates = List.copyOf(states);
    }

    static BotStashBulk plan(List<ItemStack> contents, int inventoryStart, int remaining, int tick,
                             boolean withdrawal, boolean quick, boolean mayRefill, IntPredicate eligible) {
        // Live hopper sources can change between slot pairs; verify each pickup before choosing the next.
        if (withdrawal && mayRefill) return null;
        if (remaining < MIN_SIZE || inventoryStart < 1 || inventoryStart > 54 || contents.size() != inventoryStart + 36) return null;
        int destinationStart = withdrawal ? inventoryStart : 0, destinationEnd = withdrawal ? contents.size() : inventoryStart;
        int capacity=(int)contents.subList(destinationStart,destinationEnd).stream().filter(ItemStack::isEmpty).count();
        int limit=Math.min(remaining,Math.min(capacity,withdrawal?MIN_SIZE:36));
        if(limit<MIN_SIZE)return null;
        int sourceStart = withdrawal ? 0 : inventoryStart, sourceEnd = withdrawal ? inventoryStart : contents.size();
        List<Integer> sources = new ArrayList<>();
        for (int source = sourceStart; source < sourceEnd && sources.size() < limit; source++) {
            ItemStack stack = contents.get(source);
            if (stack.getCount() == 1 && stack.getMaxStackSize() == 1 && stack.getItem() instanceof BlockItem item
                && item.getBlock() instanceof ShulkerBoxBlock && eligible.test(source)) sources.add(source);
        }
        if (sources.size() < MIN_SIZE) return null;
        List<ItemStack> simulated = new ArrayList<>(BotStashDeposit.copyContents(contents));
        List<Move> moves = new ArrayList<>();
        List<List<ItemStack>> states = new ArrayList<>();
        states.add(List.copyOf(simulated.subList(inventoryStart, simulated.size())));
        for (int source : sources) {
            ItemStack stack = simulated.get(source);
            int destination = -1;
            if (withdrawal && quick) {
                for (int slot = simulated.size() - 1; slot >= inventoryStart; slot--) if (simulated.get(slot).isEmpty()) { destination = slot; break; }
            } else {
                int start = withdrawal ? inventoryStart : 0, end = withdrawal ? simulated.size() : inventoryStart;
                for (int slot = start; slot < end; slot++) if (simulated.get(slot).isEmpty()) { destination = slot; break; }
            }
            if (destination < 0) break;
            moves.add(new Move(source, destination, stack));
            simulated.set(source, ItemStack.EMPTY); simulated.set(destination, stack);
            states.add(List.copyOf(simulated.subList(inventoryStart, simulated.size())));
        }
        return moves.size() < MIN_SIZE ? null : new BotStashBulk(moves, inventoryStart, tick, withdrawal, quick, mayRefill, states);
    }

    /** Keep the old packet burst budget, and confirm it before sending the next part of a larger deposit. */
    List<Move> nextMoves(int tick,List<ItemStack> contents,ItemStack cursor) {
        if(reopening||sent>=moves.size()||verified!=sent||sent>0&&tick<=sentTick||!cursor.isEmpty()
            ||contents.size()!=inventoryStart+36||!sameKitInventory(inventoryStates.get(sent),contents.subList(inventoryStart,contents.size())))return List.of();
        List<ItemStack> simulated=new ArrayList<>(contents);
        int end=Math.min(sent+MOVES_PER_TICK,moves.size());
        for(int step=sent;step<end;step++){
            Move move=moves.get(step);
            if(!ItemStack.matches(simulated.get(move.source()),move.stack())||!simulated.get(move.destination()).isEmpty())return List.of();
            if(quick){
                int first=-1;
                if(withdrawal){for(int slot=simulated.size()-1;slot>=inventoryStart;slot--)if(simulated.get(slot).isEmpty()){first=slot;break;}}
                else{for(int slot=0;slot<inventoryStart;slot++)if(simulated.get(slot).isEmpty()){first=slot;break;}}
                if(first!=move.destination())return List.of();
            }
            simulated.set(move.source(),ItemStack.EMPTY);simulated.set(move.destination(),move.stack());
        }
        List<Move> next=moves.subList(sent,end);sent=end;sentTick=tick;return next;
    }
    void send(int menu,int tick,List<ItemStack> contents,ItemStack cursor) {
        for (Move move : nextMoves(tick,contents,cursor)) {
            if (quick) InvUtils.sendConfirmedQuickMove(menu, move.source());
            else InvUtils.sendConfirmedMove(menu, move.source(), move.destination());
        }
    }

    int observe(List<ItemStack> contents, ItemStack cursor) {
        recordSnapshot(contents, cursor);
        if (!cursor.isEmpty() || contents.size() != inventoryStart + 36) return 0;
        List<ItemStack> inventory = contents.subList(inventoryStart, contents.size());
        for (int prefix = verified + 1; prefix <= sent; prefix++) {
            if (!sameKitInventory(inventoryStates.get(prefix), inventory)) continue;
            for (int step = verified; step < prefix; step++) {
                Move move = moves.get(step);
                if (!ItemStack.matches(contents.get(move.destination()), move.stack())
                    || withdrawal && !mayRefill && ItemStack.matches(contents.get(move.source()), move.stack())) return 0;
            }
            int count = prefix - verified; verified = prefix; return count;
        }
        return 0;
    }

    boolean complete() { return verified == moves.size(); }
    static boolean supportedMenu(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        return menu.getClass() == net.minecraft.world.inventory.ChestMenu.class || menu.getClass() == net.minecraft.world.inventory.HopperMenu.class;
    }
    boolean acceptsMenu(int id) { return !reopening || id != previousMenu; }
    void reopen(int menu) { reopening = true; previousMenu = menu; }
    boolean canFallback(List<ItemStack> contents, ItemStack cursor) {
        // A new menu is a server ordering barrier; unchanged remaining cargo proves no extra kit move landed.
        return reopening && !complete() && cursor.isEmpty() && contents.size() == inventoryStart + 36
            && sameKitInventory(inventoryStates.get(verified), contents.subList(inventoryStart, contents.size()));
    }
    private boolean sameKitInventory(List<ItemStack> expected, List<ItemStack> actual) {
        if (expected.size() != actual.size()) return false;
        for (int slot = 0; slot < expected.size(); slot++) {
            ItemStack before = expected.get(slot), after = actual.get(slot);
            if (ItemStack.matches(before, after)) continue;
            if (withdrawal) for (Move move : moves) if (move.destination() == inventoryStart + slot) return false;
            if (!sameCargoSlot(before, after)) return false;
        }
        return true;
    }
    int missingAfterReopen(List<ItemStack> contents, ItemStack cursor) {
        if (withdrawal || !reopening || !cursor.isEmpty() || contents.size() != inventoryStart + 36) return -1;
        int missing = 0;
        List<ItemStack> expected = inventoryStates.get(verified);
        for (int slot = inventoryStart; slot < contents.size(); slot++) {
            ItemStack before = expected.get(slot - inventoryStart), after = contents.get(slot);
            if (ItemStack.matches(before, after)) continue;
            boolean pendingSource = false;
            for (int step = verified; step < sent; step++) if (moves.get(step).source() == slot) pendingSource = true;
            if (pendingSource) {
                if (shulker(after)) return -1;
                missing++;
            } else if (!sameCargoSlot(before, after)) return -1;
        }
        // A fresh menu orders earlier clicks; only kits still carried may be selected again.
        return missing;
    }
    static boolean sameCargoSlot(ItemStack before, ItemStack after) {
        // Ground drops and food may change non-cargo slots, including a just-deposited kit's freed slot.
        return ItemStack.matches(before, after) || !shulker(before) && !shulker(after);
    }
    static boolean shulker(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock;
    }
    private void recordSnapshot(List<ItemStack> contents, ItemStack cursor) {
        lastSnapshot = new JsonObject(); lastSnapshot.addProperty("cursorCount", cursor.getCount());
        lastSnapshot.addProperty("slots", contents.size());
        if (contents.size() != inventoryStart + 36) return;
        com.google.gson.JsonArray changed = new com.google.gson.JsonArray();
        List<ItemStack> expected = inventoryStates.get(verified);
        for (int slot = inventoryStart; slot < contents.size(); slot++) {
            ItemStack before = expected.get(slot - inventoryStart), after = contents.get(slot);
            if (ItemStack.matches(before, after)) continue;
            JsonObject value = new JsonObject(); value.addProperty("slot", slot);
            value.addProperty("beforeItem", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(before.getItem()).toString());
            value.addProperty("beforeCount", before.getCount());
            value.addProperty("afterItem", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(after.getItem()).toString());
            value.addProperty("afterCount", after.getCount());
            var keys = new java.util.HashSet<>(before.getComponents().keySet()); keys.addAll(after.getComponents().keySet());
            com.google.gson.JsonArray components = new com.google.gson.JsonArray();
            for (var key : keys) if (!java.util.Objects.equals(before.get(key), after.get(key))) components.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(key)));
            value.add("differentComponents", components); changed.add(value);
        }
        lastSnapshot.add("inventoryChanges", changed);
        com.google.gson.JsonArray destinations = new com.google.gson.JsonArray();
        for (Move move : moves) {
            JsonObject value = new JsonObject(); value.addProperty("slot", move.destination());
            value.addProperty("matchesExpected", ItemStack.matches(contents.get(move.destination()), move.stack()));
            com.google.gson.JsonArray matching = new com.google.gson.JsonArray();
            for (int slot = 0; slot < inventoryStart; slot++) if (ItemStack.matches(contents.get(slot), move.stack())) matching.add(slot);
            value.add("matchingContainerSlots", matching); destinations.add(value);
        }
        lastSnapshot.add("destinations", destinations);
    }
    JsonObject diagnostic() {
        JsonObject value = new JsonObject(); value.addProperty("bulk", true); value.addProperty("size", moves.size());
        value.addProperty("sent",sent);value.addProperty("movesPerTick",MOVES_PER_TICK);
        value.addProperty("verified", verified); value.addProperty("quickMove", quick); value.addProperty("reopening", reopening);
        value.addProperty("startedTick", sentTick); value.addProperty("previousMenu", previousMenu);
        com.google.gson.JsonArray slots=new com.google.gson.JsonArray();
        for(Move move:moves){JsonObject slot=new JsonObject();slot.addProperty("source",move.source());slot.addProperty("destination",move.destination());slot.addProperty("name",move.stack().getHoverName().getString());slots.add(slot);}
        value.add("moves",slots);if(lastSnapshot!=null)value.add("lastSnapshot",lastSnapshot.deepCopy());return value;
    }
}
