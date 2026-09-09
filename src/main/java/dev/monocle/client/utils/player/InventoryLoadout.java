/* Monocle inventory loadout planning. Minecraft performs the actual slot transactions. */
package dev.monocle.client.utils.player;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Replans a single legal move from current contents; never mutates a slot or drops an item. */
public final class InventoryLoadout {
    private InventoryLoadout() {}

    public record Rule(ItemStack template, int amount, int hotbarSlot) {
        public Rule {
            if (template == null || template.isEmpty() || amount < 1 || amount > 2304 || hotbarSlot < -1 || hotbarSlot > 8)
                throw new IllegalArgumentException("A loadout rule needs an item, 1–2304 items, and an optional hotbar slot 1–9.");
            template = template.copyWithCount(1);
        }

        @Override public ItemStack template() { return template.copy(); }
    }

    /** Menu slot IDs, not player inventory indexes. A swap always carries the entire source stack. */
    public record Move(int from, int to, int amount) {}

    public static List<Rule> validateRules(List<Rule> rules) {
        if (rules == null || rules.size() > 36) throw new IllegalArgumentException("A loadout supports at most 36 rules.");
        int pins = 0;
        for (Rule rule : rules) {
            if (rule == null) throw new IllegalArgumentException("A loadout rule cannot be null.");
            if (rule.hotbarSlot >= 0) {
                int bit = 1 << rule.hotbarSlot;
                if ((pins & bit) != 0) throw new IllegalArgumentException("Each hotbar slot can have only one rule.");
                pins |= bit;
            }
        }
        return List.copyOf(rules);
    }

    public static List<Rule> capture(Container inventory) {
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            int existing = -1;
            if (i >= 9) {
                for (int j = 0; j < rules.size(); j++) {
                    if (rules.get(j).hotbarSlot == -1 && matches(rules.get(j).template, stack)) {
                        existing = j;
                        break;
                    }
                }
            }
            if (existing < 0) rules.add(new Rule(stack, stack.getCount(), i < 9 ? i : -1));
            else {
                Rule rule = rules.get(existing);
                rules.set(existing, new Rule(rule.template, rule.amount + stack.getCount(), -1));
            }
        }
        return List.copyOf(rules);
    }

    public static CompoundTag toTag(List<Rule> rules, HolderLookup.Provider registries) {
        if (registries == null) throw new IllegalArgumentException("Item registries are unavailable.");
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        ListTag entries = new ListTag();
        for (Rule rule : validateRules(rules)) {
            CompoundTag entry = new CompoundTag();
            entry.put("stack", ItemStack.CODEC.encodeStart(ops, rule.template).result()
                .orElseThrow(() -> new IllegalArgumentException("Cannot encode a loadout item.")));
            entry.putInt("amount", rule.amount);
            entry.putInt("pin", rule.hotbarSlot);
            entries.add(entry);
        }
        CompoundTag tag = new CompoundTag();
        tag.put("rules", entries);
        return tag;
    }

    public static List<Rule> fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        if (registries == null) throw new IllegalArgumentException("Item registries are unavailable.");
        if (tag == null || !(tag.get("rules") instanceof ListTag entries) || entries.size() > 36)
            throw new IllegalArgumentException("A saved loadout needs a list of at most 36 rules.");
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        List<Rule> rules = new ArrayList<>();
        for (var value : entries) {
            if (!(value instanceof CompoundTag entry) || !(entry.get("stack") instanceof CompoundTag stack)
                || !(entry.get("amount") instanceof IntTag amount) || !(entry.get("pin") instanceof IntTag pin))
                throw new IllegalArgumentException("A saved loadout rule is missing an item, integer amount or integer pin.");
            ItemStack decoded = ItemStack.CODEC.parse(ops, stack).result()
                .orElseThrow(() -> new IllegalArgumentException("Cannot decode a saved loadout item."));
            rules.add(new Rule(decoded, amount.intValue(), pin.intValue()));
        }
        return validateRules(rules);
    }

    /** Wear is not a different tool; names, enchantments, container contents and all other data are. */
    public static boolean matches(ItemStack template, ItemStack stack) {
        if (template.isEmpty() || stack.isEmpty() || !ItemStack.isSameItem(template, stack)) return false;
        if (!template.isDamageableItem() || !stack.isDamageableItem()) return ItemStack.isSameItemSameComponents(template, stack);
        ItemStack a = template.copy(), b = stack.copy();
        a.remove(DataComponents.DAMAGE);
        b.remove(DataComponents.DAMAGE);
        return ItemStack.isSameItemSameComponents(a, b);
    }

    public static int count(Container inventory, ItemStack template) {
        int count = 0;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            ItemStack stack = inventory.getItem(i);
            if (matches(template, stack)) count += stack.getCount();
        }
        return count;
    }

    public static int target(List<Rule> rules, ItemStack template) {
        int count = 0;
        for (Rule rule : rules) if (matches(rule.template, template)) count += rule.amount;
        return count;
    }

    public static Move refill(List<Slot> slots, Container inventory, List<Rule> rules) {
        for (int from = 0; from < slots.size(); from++) {
            Slot source = slots.get(from);
            if (source.container == inventory || !usable(source) || !source.hasItem()) continue;
            ItemStack stack = source.getItem();
            int missing = target(rules, stack) - count(inventory, stack);
            if (missing <= 0) continue;
            // Pinned stacks first, then merge existing stacks before consuming an empty slot.
            for (Rule rule : rules) {
                if (rule.hotbarSlot < 0 || !matches(rule.template, stack)) continue;
                int to = playerSlot(slots, inventory, rule.hotbarSlot);
                if (to < 0) continue;
                Slot destination = slots.get(to);
                int needed = Math.min(rule.amount, stack.getMaxStackSize()) - destination.getItem().getCount();
                int amount = Math.min(Math.min(missing, needed), Math.min(stack.getCount(), capacity(destination, stack)));
                if (amount > 0) return new Move(from, to, amount);
            }
            for (int pass = 0; pass < 2; pass++) {
                for (int index = 9; index < 45; index++) {
                    int playerIndex = index % 36;
                    if (pin(rules, playerIndex) != null) continue;
                    int to = playerSlot(slots, inventory, playerIndex);
                    if (to < 0 || slots.get(to).getItem().isEmpty() != (pass == 1)) continue;
                    int amount = Math.min(missing, Math.min(stack.getCount(), capacity(slots.get(to), stack)));
                    if (amount > 0) return new Move(from, to, amount);
                }
            }
        }
        return null;
    }

    /** Deposits only known surplus; unknown items and explicit protections are never touched. */
    public static Move deposit(List<Slot> slots, Container inventory, List<Rule> rules, Predicate<ItemStack> protectedItem) {
        for (int index = 9; index < 45; index++) {
            int playerIndex = index % 36;
            int from = playerSlot(slots, inventory, playerIndex);
            if (from < 0 || !usable(slots.get(from))) continue;
            ItemStack stack = slots.get(from).getItem();
            int wanted = target(rules, stack);
            if (stack.isEmpty() || wanted == 0 || protectedItem.test(stack)) continue;
            int excess = Math.min(count(inventory, stack) - wanted, available(stack, pin(rules, playerIndex)));
            if (excess <= 0) continue;
            for (int pass = 0; pass < 2; pass++) {
                for (int to = 0; to < slots.size(); to++) {
                    Slot destination = slots.get(to);
                    if (destination.container == inventory || destination.getItem().isEmpty() != (pass == 1)) continue;
                    int amount = Math.min(excess, capacity(destination, stack));
                    if (amount > 0) return new Move(from, to, amount);
                }
            }
        }
        return null;
    }

    /** Consolidates compatible main-inventory stacks without rearranging the hotbar. */
    public static Move compact(List<Slot> slots, Container inventory, List<Rule> rules) {
        for (int index = 9; index < 35; index++) {
            int to = playerSlot(slots, inventory, index);
            if (to < 0 || !slots.get(to).hasItem()) continue;
            for (int later = index + 1; later < 36; later++) {
                int from = playerSlot(slots, inventory, later);
                if (from < 0 || !usable(slots.get(from))) continue;
                ItemStack stack = slots.get(from).getItem();
                int amount = Math.min(stack.getCount(), capacity(slots.get(to), stack));
                if (amount > 0) return new Move(from, to, amount);
            }
        }
        return null;
    }

    /** Restores pins using safe full-stack exchanges or exact merges, including a completely full inventory. */
    public static Move arrange(List<Slot> slots, Container inventory, List<Rule> rules) {
        for (Rule rule : rules) {
            if (rule.hotbarSlot < 0) continue;
            int to = playerSlot(slots, inventory, rule.hotbarSlot);
            if (to < 0 || !usable(slots.get(to))) continue;
            Slot destination = slots.get(to);
            ItemStack held = destination.getItem();
            boolean matching = matches(rule.template, held);
            int needed = Math.min(rule.amount, rule.template.getMaxStackSize()) - (matching ? held.getCount() : 0);
            if (needed <= 0) continue;
            for (int index = 9; index < 45; index++) {
                int playerIndex = index % 36;
                int from = playerSlot(slots, inventory, playerIndex);
                if (from < 0 || from == to || !usable(slots.get(from))) continue;
                Slot source = slots.get(from);
                ItemStack stack = source.getItem();
                if (!matches(rule.template, stack)) continue;
                int available = available(stack, pin(rules, playerIndex));
                if (available <= 0) continue;
                if (!held.isEmpty() && !matching) {
                    if (available == stack.getCount() && destination.mayPlace(stack) && source.mayPlace(held)
                        && stack.getCount() <= limit(destination, stack) && held.getCount() <= limit(source, held))
                        return new Move(from, to, stack.getCount());
                } else {
                    int amount = Math.min(needed, Math.min(available, capacity(destination, stack)));
                    if (amount > 0) return new Move(from, to, amount);
                }
            }
        }
        return null;
    }

    /** A safe player-inventory destination for cursor recovery; never swaps or throws the cursor away. */
    public static int cursorDestination(List<Slot> slots, Container inventory, ItemStack carried) {
        for (int pass = 0; pass < 2; pass++) {
            for (int index = 9; index < 45; index++) {
                int to = playerSlot(slots, inventory, index % 36);
                if (to >= 0 && slots.get(to).getItem().isEmpty() == (pass == 1) && capacity(slots.get(to), carried) > 0) return to;
            }
        }
        return -1;
    }

    private static Rule pin(List<Rule> rules, int slot) {
        if (slot >= 9) return null;
        for (Rule rule : rules) if (rule.hotbarSlot == slot) return rule;
        return null;
    }

    private static int available(ItemStack stack, Rule pin) {
        return stack.getCount() - (pin != null && matches(pin.template, stack) ? Math.min(pin.amount, stack.getMaxStackSize()) : 0);
    }

    private static int playerSlot(List<Slot> slots, Container inventory, int index) {
        if (index >= inventory.getContainerSize()) return -1;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.container == inventory && slot.getContainerSlot() == index && usable(slot)) return i;
        }
        return -1;
    }

    // Bundle slot-click hooks can insert/extract contents instead of performing a plain stack move.
    private static boolean usable(Slot slot) { return slot.isActive() && !slot.isFake() && !slot.getItem().has(DataComponents.BUNDLE_CONTENTS); }

    private static int limit(Slot slot, ItemStack stack) { return Math.min(slot.getMaxStackSize(stack), stack.getMaxStackSize()); }

    private static int capacity(Slot slot, ItemStack incoming) {
        if (incoming.isEmpty() || incoming.has(DataComponents.BUNDLE_CONTENTS) || !usable(slot) || !slot.mayPlace(incoming)) return 0;
        ItemStack held = slot.getItem();
        if (!held.isEmpty() && !ItemStack.isSameItemSameComponents(held, incoming)) return 0;
        return Math.max(0, limit(slot, incoming) - held.getCount());
    }
}
