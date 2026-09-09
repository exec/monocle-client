package dev.monocle.client.utils.player;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;

import static dev.monocle.client.utils.player.InventoryLoadout.*;

/** Native slot/component checks; no renderer, module singleton, or test framework. */
public final class InventoryLoadoutTest {
    public static void main(String[] args) {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions) throw new IllegalStateException("Run with assertions enabled (-ea).");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(DataComponentInitializers.PendingComponents::apply);
        captureAndIdentity();
        persistence();
        for (int size : new int[] {27, 54}) refillAndDeposit(size);
        pinnedArrangement();
        compactAndCursor();
        restrictedSlots();
        System.out.println("Inventory loadout checks passed: snapshots, component identity and persistence, exact quantities, pinned swaps, double containers, safe deposits, compaction and cursor recovery.");
    }

    private static void persistence() {
        var registries = VanillaRegistries.createLookup();
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        tool.setDamageValue(100);
        tool.set(DataComponents.CUSTOM_NAME, Component.literal("Saved working pick"));
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), 5);
        tool.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());
        ItemStack box = new ItemStack(Items.DYED_SHULKER_BOX.blue());
        box.set(DataComponents.CUSTOM_NAME, Component.literal("Saved supplies"));
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(tool.copy(), new ItemStack(Items.OBSIDIAN, 64))));
        List<Rule> rules = List.of(new Rule(tool, 1, 0), new Rule(box, 2, -1));
        CompoundTag saved = toTag(rules, registries);
        List<Rule> restored = fromTag(saved, registries);
        assert restored.size() == rules.size();
        for (int i = 0; i < rules.size(); i++) {
            assert ItemStack.matches(rules.get(i).template(), restored.get(i).template()) : "Native CODEC preserves names, enchantments, damage, color and nested box contents";
            assert restored.get(i).amount() == rules.get(i).amount() && restored.get(i).hotbarSlot() == rules.get(i).hotbarSlot();
        }
        assert saved.equals(toTag(restored, registries));
        assert fromTag(toTag(List.of(), registries), registries).isEmpty();
        invalid(() -> fromTag(new CompoundTag(), registries));
        invalid(() -> fromTag(saved, null));
        invalid(() -> toTag(rules, null));

        CompoundTag noAmount = saved.copy();
        noAmount.getListOrEmpty("rules").getCompoundOrEmpty(0).remove("amount");
        invalid(() -> fromTag(noAmount, registries));
        CompoundTag fractionalAmount = saved.copy();
        fractionalAmount.getListOrEmpty("rules").getCompoundOrEmpty(0).putDouble("amount", 1.5);
        invalid(() -> fromTag(fractionalAmount, registries));
        CompoundTag zeroAmount = saved.copy();
        zeroAmount.getListOrEmpty("rules").getCompoundOrEmpty(0).putInt("amount", 0);
        invalid(() -> fromTag(zeroAmount, registries));
        CompoundTag missingStack = saved.copy();
        missingStack.getListOrEmpty("rules").getCompoundOrEmpty(0).remove("stack");
        invalid(() -> fromTag(missingStack, registries));
        CompoundTag air = saved.copy();
        air.getListOrEmpty("rules").getCompoundOrEmpty(0).getCompoundOrEmpty("stack").putString("id", "minecraft:air");
        invalid(() -> fromTag(air, registries));
        CompoundTag unknownItem = saved.copy();
        unknownItem.getListOrEmpty("rules").getCompoundOrEmpty(0).getCompoundOrEmpty("stack").putString("id", "monocle:missing_item");
        invalid(() -> fromTag(unknownItem, registries));
        CompoundTag duplicatePin = saved.copy();
        duplicatePin.getListOrEmpty("rules").getCompoundOrEmpty(1).putInt("pin", 0);
        invalid(() -> fromTag(duplicatePin, registries));
        ListTag oversized = new ListTag();
        for (int i = 0; i < 37; i++) oversized.add(saved.getListOrEmpty("rules").getCompoundOrEmpty(1).copy());
        CompoundTag tooMany = new CompoundTag();
        tooMany.put("rules", oversized);
        invalid(() -> fromTag(tooMany, registries));
        assert saved.equals(toTag(rules, registries)) : "Failed decoding never edits the preserved saved blob";
    }

    private static void captureAndIdentity() {
        SimpleContainer inventory = new SimpleContainer(41);
        inventory.setItem(0, new ItemStack(Items.OBSIDIAN, 16));
        inventory.setItem(1, new ItemStack(Items.OBSIDIAN, 32));
        inventory.setItem(9, new ItemStack(Items.OBSIDIAN, 64));
        inventory.setItem(35, new ItemStack(Items.OBSIDIAN, 64));
        inventory.setItem(40, new ItemStack(Items.OBSIDIAN, 64));
        List<Rule> rules = capture(inventory);
        assert rules.size() == 3 && rules.get(0).hotbarSlot() == 0 && rules.get(1).hotbarSlot() == 1;
        assert rules.get(2).hotbarSlot() == -1 && rules.get(2).amount() == 128;
        assert target(rules, inventory.getItem(0)) == 176 && count(inventory, inventory.getItem(0)) == 176 : "Armor and offhand are outside a loadout";
        inventory.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Changed after saving"));
        rules.get(0).template().set(DataComponents.CUSTOM_NAME, Component.literal("Changed through accessor"));
        assert rules.get(0).template().get(DataComponents.CUSTOM_NAME) == null : "Capture and accessor must not expose a mutable saved template";

        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack worn = tool.copy();
        worn.setDamageValue(400);
        assert matches(tool, worn) : "Using a captured tool cannot make the loadout forget it";
        worn.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this pick"));
        assert !matches(tool, worn) : "Different names are not interchangeable";
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(VanillaRegistries.createLookup().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), 5);
        ItemStack enchanted = tool.copy();
        enchanted.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());
        assert !matches(tool, enchanted) : "Never substitute a differently enchanted tool";
        ItemStack wornEnchanted = enchanted.copy();
        wornEnchanted.setDamageValue(100);
        assert matches(enchanted, wornEnchanted);

        ItemStack box = new ItemStack(Items.DYED_SHULKER_BOX.blue());
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.OBSIDIAN, 64))));
        ItemStack otherBox = box.copy();
        otherBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 64))));
        assert !matches(box, otherBox) && matches(box, box.copy()) : "Shulker contents are part of a saved kit's identity";
        assert !matches(ItemStack.EMPTY, ItemStack.EMPTY);
        invalid(() -> new Rule(tool, 0, -1));
        invalid(() -> new Rule(tool, 2305, -1));
        invalid(() -> new Rule(tool, 1, 9));
        invalid(() -> new Rule(ItemStack.EMPTY, 1, -1));
        invalid(() -> validateRules(List.of(new Rule(tool, 1, 0), new Rule(tool, 1, 0))));
    }

    private static void refillAndDeposit(int size) {
        SimpleContainer chest = new SimpleContainer(size), inventory = new SimpleContainer(41);
        List<Slot> slots = menu(chest, inventory);
        ItemStack obsidian = new ItemStack(Items.OBSIDIAN);
        List<Rule> rules = List.of(new Rule(obsidian, 16, 0), new Rule(obsidian, 64, -1));
        inventory.setItem(0, new ItemStack(Items.OBSIDIAN, 10));
        inventory.setItem(40, new ItemStack(Items.OBSIDIAN, 64));
        chest.setItem(size - 1, new ItemStack(Items.OBSIDIAN, 64));
        chest.setItem(size - 2, new ItemStack(Items.OBSIDIAN, 64));
        chest.setItem(0, new ItemStack(Items.DIAMOND, 64));
        Move first = refill(slots, inventory, rules);
        assert first != null && first.from() == size - 2 && first.to() == size + 27 && first.amount() == 6;
        assert inventory.getItem(0).getCount() == 10 && chest.getItem(size - 2).getCount() == 64 : "Planning cannot mutate the menu";
        for (int i = 0; i < 100; i++) {
            Move move = refill(slots, inventory, rules);
            if (move == null) break;
            execute(slots, move);
            assert i < 99 : "Refill must terminate without oscillation";
        }
        assert count(inventory, obsidian) == 80 && inventory.getItem(0).getCount() == 16 : "Refill exact quantities, including partially filled hotbar stacks";
        assert count(inventory, new ItemStack(Items.DIAMOND)) == 0 && inventory.getItem(40).getCount() == 64;
        inventory.setItem(9, new ItemStack(Items.OBSIDIAN, 64));
        inventory.setItem(10, new ItemStack(Items.OBSIDIAN, 64));
        inventory.setItem(11, new ItemStack(Items.DIAMOND, 64));
        assert deposit(slots, inventory, rules, stack -> true) == null : "Explicit protection overrides excess";
        for (int i = 0; i < 100; i++) {
            Move move = deposit(slots, inventory, rules, stack -> false);
            if (move == null) break;
            execute(slots, move);
            assert i < 99 : "Deposit must stop at the target";
        }
        assert count(inventory, obsidian) == 80 && inventory.getItem(0).getCount() == 16;
        assert inventory.getItem(11).getCount() == 64 : "Unknown items are never included in Deposit Excess";
        assert deposit(slots, inventory, rules, stack -> false) == null;

        for (int i = 0; i < 36; i++) inventory.setItem(i, new ItemStack(Items.DIAMOND, 64));
        assert refill(slots, inventory, rules) == null : "A full inventory does not authorize replacing or dropping another item";
    }

    private static void pinnedArrangement() {
        SimpleContainer chest = new SimpleContainer(27), inventory = new SimpleContainer(41);
        List<Slot> slots = menu(chest, inventory);
        for (int i = 0; i < 36; i++) inventory.setItem(i, new ItemStack(Items.DIRT, 64));
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        pick.set(DataComponents.CUSTOM_NAME, Component.literal("Working pick"));
        inventory.setItem(35, pick.copy());
        inventory.setItem(40, pick.copy());
        List<Rule> rules = List.of(new Rule(pick, 1, 0));
        Move swap = arrange(slots, inventory, rules);
        assert swap != null && swap.from() == 53 && swap.to() == 54 && swap.amount() == 1 : "A full inventory can exchange a pinned tool without an empty slot";
        execute(slots, swap);
        assert matches(pick, inventory.getItem(0)) && inventory.getItem(35).is(Items.DIRT) && inventory.getItem(35).getCount() == 64;
        assert arrange(slots, inventory, rules) == null : "An arranged pin must not be shuffled again";

        inventory.clearContent();
        ItemStack obsidian = new ItemStack(Items.OBSIDIAN);
        rules = List.of(new Rule(obsidian, 10, 0), new Rule(obsidian, 10, 1));
        inventory.setItem(0, new ItemStack(Items.OBSIDIAN, 10));
        assert arrange(slots, inventory, rules) == null : "One matching pin must not cannibalize another pin's required quantity";
        inventory.setItem(0, new ItemStack(Items.OBSIDIAN, 15));
        Move partial = arrange(slots, inventory, rules);
        assert partial != null && partial.amount() == 5;
        execute(slots, partial);
        assert inventory.getItem(0).getCount() == 10 && inventory.getItem(1).getCount() == 5;
        assert arrange(slots, inventory, rules) == null;
        inventory.setItem(35, new ItemStack(Items.OBSIDIAN, 64));
        execute(slots, arrange(slots, inventory, rules));
        assert inventory.getItem(0).getCount() == 10 && inventory.getItem(1).getCount() == 10 && inventory.getItem(35).getCount() == 59;
    }

    private static void compactAndCursor() {
        SimpleContainer chest = new SimpleContainer(54), inventory = new SimpleContainer(41);
        List<Slot> slots = menu(chest, inventory);
        ItemStack named = new ItemStack(Items.OBSIDIAN, 20);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Reserved"));
        inventory.setItem(0, named.copy());
        inventory.setItem(9, named.copy());
        inventory.setItem(10, new ItemStack(Items.OBSIDIAN, 20));
        inventory.setItem(35, named.copyWithCount(40));
        Move move = compact(slots, inventory, List.of());
        assert move != null && move.from() == 80 && move.to() == 54 && move.amount() == 40;
        execute(slots, move);
        assert inventory.getItem(9).getCount() == 60 && inventory.getItem(35).isEmpty();
        assert inventory.getItem(0).getCount() == 20 && inventory.getItem(10).getCount() == 20 : "Compaction preserves hotbar and component-distinct items";
        assert compact(slots, inventory, List.of()) == null;
        assert cursorDestination(slots, inventory, named) == 54 : "Recover the cursor into compatible main-inventory space before using an empty slot";
        for (int i = 0; i < 36; i++) inventory.setItem(i, new ItemStack(Items.DIAMOND, 64));
        assert cursorDestination(slots, inventory, named) == -1 : "No recovery destination must not turn into an outside/drop slot";
    }

    private static void restrictedSlots() {
        SimpleContainer chest = new SimpleContainer(27), inventory = new SimpleContainer(36);
        chest.setItem(0, new ItemStack(Items.OBSIDIAN, 64));
        List<Rule> rules = List.of(new Rule(new ItemStack(Items.OBSIDIAN), 64, 0));
        List<Slot> slots = new ArrayList<>();
        slots.add(new Slot(chest, 0, 0, 0));
        slots.add(new Slot(inventory, 0, 0, 0) {
            @Override public int getMaxStackSize(ItemStack stack) { return 8; }
        });
        assert refill(slots, inventory, rules).amount() == 8 : "Native destination limits bound each transfer";
        slots.set(1, new Slot(inventory, 0, 0, 0) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
        });
        assert refill(slots, inventory, rules) == null;
        slots.set(1, new Slot(inventory, 0, 0, 0) {
            @Override public boolean isActive() { return false; }
        });
        assert refill(slots, inventory, rules) == null;
        slots.set(1, new Slot(inventory, 0, 0, 0));
        inventory.setItem(0, new ItemStack(Items.BUNDLE));
        inventory.setItem(9, new ItemStack(Items.OBSIDIAN, 64));
        slots.add(new Slot(inventory, 9, 0, 0));
        assert arrange(slots, inventory, rules) == null : "Never click another stack onto a bundle and accidentally insert it";
        assert cursorDestination(slots, inventory, new ItemStack(Items.BUNDLE)) == -1 : "Bundle cursor hooks need manual handling";
        inventory.clearContent();
        chest.setItem(0, new ItemStack(Items.BUNDLE));
        assert refill(slots, inventory, List.of(new Rule(new ItemStack(Items.BUNDLE), 1, -1))) == null;
    }

    private static List<Slot> menu(SimpleContainer chest, SimpleContainer inventory) {
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < chest.getContainerSize(); i++) slots.add(new Slot(chest, i, 0, 0));
        for (int i = 9; i < 36; i++) slots.add(new Slot(inventory, i, 0, 0));
        for (int i = 0; i < 9; i++) slots.add(new Slot(inventory, i, 0, 0));
        for (int i = 36; i < inventory.getContainerSize(); i++) slots.add(new Slot(inventory, i, 0, 0));
        return slots;
    }

    private static void execute(List<Slot> slots, Move move) {
        assert move != null && move.from() >= 0 && move.to() >= 0 && move.amount() > 0;
        Slot source = slots.get(move.from()), destination = slots.get(move.to());
        ItemStack from = source.getItem(), to = destination.getItem();
        if (!to.isEmpty() && !ItemStack.isSameItemSameComponents(from, to)) {
            assert move.amount() == from.getCount();
            ItemStack displaced = to.copy();
            destination.set(from.copy());
            source.set(displaced);
        } else {
            ItemStack carried = source.safeTake(move.amount(), move.amount(), null);
            assert destination.safeInsert(carried).isEmpty() : "Planned amount must fit without loss or cursor residue";
        }
    }

    private static void invalid(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid saved loadout was accepted");
    }
}
