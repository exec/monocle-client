package dev.monocle.client.systems.modules.misc.swarm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.monocle.client.systems.modules.player.AutoTool;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.List;
import java.util.function.Predicate;

/** Bot-only inventory policy and bounded, non-overlapping resource accounting. No clicks. */
public final class CrewInventory extends dev.monocle.coordinator.ResourceLedger {
    private CrewInventory() {}
    public static final List<Block> FILLER = List.of(Blocks.NETHERRACK, Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE,
        Blocks.BLACKSTONE, Blocks.BASALT, Blocks.STONE, Blocks.DEEPSLATE, Blocks.DIRT, Blocks.END_STONE);

    public static boolean keep(ItemStack stack, List<Block> paving, List<Block> filler, List<Item> extra) {
        if (stack.isEmpty()) return true;
        return AutoTool.isTool(stack) || stack.isDamageableItem() || stack.has(DataComponents.EQUIPPABLE)
            || stack.has(DataComponents.FOOD) || shulker(stack)
            || stack.is(Items.ENDER_CHEST) || stack.is(Items.TOTEM_OF_UNDYING) || stack.is(Items.FIREWORK_ROCKET)
            || stack.is(Items.ENDER_PEARL) || stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)
            || stack.is(Items.EXPERIENCE_BOTTLE) || stack.getItem() instanceof ArrowItem || stack.has(DataComponents.POTION_CONTENTS)
            || stack.has(DataComponents.CUSTOM_NAME) || stack.has(DataComponents.CONTAINER)
            || extra.contains(stack.getItem())
            || stack.getItem() instanceof BlockItem block && (paving.contains(block.getBlock()) || filler.contains(block.getBlock()));
    }
    private static boolean shulker(ItemStack stack) { return stack.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock; }

    /** Throw along the completed road, away from work, regardless of lane or crew membership. */
    public static float trashYaw(float forward) { return forward + 180; }

    public static int count(Container inventory, Predicate<ItemStack> accepts) {
        int n = 0;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) if (accepts.test(inventory.getItem(i))) n += inventory.getItem(i).getCount();
        return n;
    }

    public static int capacity(Container inventory, ItemStack item, int emptyReserve) {
        int free = 0, empty = 0;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
            ItemStack held = inventory.getItem(i);
            if (held.isEmpty()) empty++;
            else if (ItemStack.isSameItemSameComponents(held, item)) free += Math.max(0, held.getMaxStackSize() - held.getCount());
        }
        return free + Math.max(0, empty - emptyReserve) * item.getMaxStackSize();
    }

    public static int[] totals(Iterable<ItemStack> stacks, List<Predicate<ItemStack>> accepts, boolean nested) {
        int[] result = new int[accepts.size()];
        for (ItemStack stack : stacks) {
            for (int r = 0; r < accepts.size(); r++) if (accepts.get(r).test(stack)) result[r] += stack.getCount();
            if (nested && shulker(stack)) {
                for (ItemStack item : stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItemCopyStream().toList())
                    for (int r = 0; r < accepts.size(); r++) if (accepts.get(r).test(item)) result[r] += item.getCount() * stack.getCount();
            }
        }
        return result;
    }

    public static boolean unresolvedReceipt(JsonObject receipt) {
        return receipt != null && receipt.has("issued") && receipt.get("issued").getAsBoolean()
            && !java.util.Set.of("complete", "cancelled").contains(receipt.get("stage").getAsString());
    }

    /** Operator approval is bounded to receipts already present when inspection was confirmed. */
    public static void inspectTransferReceipt(java.nio.file.Path path, long before) {
        if (before <= 0 || before > System.currentTimeMillis()) throw new IllegalArgumentException("Invalid transfer inspection cutoff");
        JsonObject receipt = dev.monocle.coordinator.TaskFiles.read(path);
        if (!unresolvedReceipt(receipt)) return;
        try {
            if (java.nio.file.Files.getLastModifiedTime(path).toMillis() > before)
                throw new IllegalStateException("Transfer receipt changed after inspection; inspect it again");
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot inspect transfer receipt", e); }
        var archive = path.resolveSibling(path.getFileName() + ".inspected-" + dev.monocle.coordinator.TaskFiles.hash(receipt.toString()) + ".json");
        dev.monocle.coordinator.TaskFiles.write(archive, receipt); // Preserve the original before retiring the hold.
        receipt.addProperty("previousStage", receipt.get("stage").getAsString());
        receipt.addProperty("stage", "cancelled");
        receipt.addProperty("inspectedBefore", before);
        receipt.addProperty("inspectedAt", System.currentTimeMillis());
        dev.monocle.coordinator.TaskFiles.write(path, receipt);
    }
    public static boolean mayDrop(boolean issued, boolean matches, int available, int reserve, int amount) {
        return !issued && matches && amount > 0 && amount <= 99 && available >= reserve && amount <= available - reserve;
    }
    public static boolean mayDrop(boolean issued, boolean matches, int available, int reserve, int count, int units) {
        return !issued && matches && count > 0 && count <= 99 && units >= count && units <= available - reserve;
    }

    /** Follow the real stack; without a visible drop, sweep one block toward the donor and back. */
    static net.minecraft.core.BlockPos pickupTarget(net.minecraft.core.BlockPos meeting, int dx, int dz,
                                                    net.minecraft.world.phys.Vec3 drop, int elapsed) {
        if (drop != null && Double.isFinite(drop.lengthSqr()) && Math.abs(drop.y - meeting.getY()) < 1
            && net.minecraft.world.phys.Vec3.atBottomCenterOf(meeting).distanceToSqr(drop) <= 36)
            return net.minecraft.core.BlockPos.containing(drop.x, meeting.getY(), drop.z);
        return elapsed >= 20 && elapsed / 40 % 2 == 0 ? meeting.offset(dx, 0, dz) : meeting;
    }

    /** A compact manifest for inspection, not an executable item/slot description. */
    public static JsonObject manifest(Iterable<ItemStack> stacks) {
        JsonObject result = new JsonObject();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            result.addProperty(id, (result.has(id) ? result.get(id).getAsInt() : 0) + stack.getCount());
            if (shulker(stack)) {
                for (ItemStack item : stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItemCopyStream().toList()) {
                    String key = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
                    result.addProperty(key, (result.has(key) ? result.get(key).getAsInt() : 0) + item.getCount() * stack.getCount());
                }
            }
        }
        return result;
    }
}
