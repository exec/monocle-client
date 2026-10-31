package dev.monocle.client.systems.bots;

import com.google.gson.*;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.packets.InventoryEvent;
import dev.monocle.client.events.packets.ContainerSlotUpdateEvent;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.world.BlockUtils;
import dev.monocle.coordinator.TaskFiles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import static dev.monocle.client.MonocleClient.mc;

/** Personal consumables, not cargo: intentionally placed caches outlive the job journal. */
final class BotPersonalSupplies {
    private final Path file;
    private JsonObject records;
    private Item resource;
    private String cacheKey;
    private BlockPos target;
    private List<ItemStack> contents = List.of();
    private int menu = -1, ticks, sent = -1, before;
    private boolean opening;
    private String detail = "";
    BotPersonalSupplies() { this(MonocleClient.FOLDER.toPath().resolve("worker-supply-caches.json")); }
    BotPersonalSupplies(Path file) { this.file = file; }
    static boolean supplyBox(ItemStack box, Item resource) {
        if (!(box.getItem() instanceof BlockItem b) || !(b.getBlock() instanceof ShulkerBoxBlock) || !box.has(DataComponents.CONTAINER)) return false;
        List<ItemStack> items = box.get(DataComponents.CONTAINER).allItemsCopyStream().filter(s -> !s.isEmpty()).toList();
        return !items.isEmpty() && items.stream().allMatch(s -> s.is(resource));
    }
    static String key(String owner, String scope, Item resource) {
        return owner + "\n" + scope + "\n" + BuiltInRegistries.ITEM.getKey(resource);
    }
    private String key() { return key(mc.player.getUUID().toString(), scope(), resource); }
    private static String scope() { return (mc.getCurrentServer() == null ? "local:" + Utils.getWorldName() : dev.monocle.client.utils.Utils.getServerIdentity()) + "\n" + mc.level.dimension().identifier(); }
    private JsonObject records() {
        if (records == null) {
            JsonObject saved = TaskFiles.read(file);
            if (!saved.isEmpty() && (!saved.has("version") || saved.get("version").getAsInt() != 1)) throw new IllegalStateException("Unsupported personal supply cache journal");
            records = saved.has("caches") ? saved.getAsJsonObject("caches").deepCopy() : new JsonObject();
            if (records.size() > 128) throw new IllegalStateException("Too many personal supply caches");
            for (var entry : records.entrySet()) position(entry.getValue().getAsJsonObject());
        }
        return records;
    }
    static BlockPos position(JsonObject record) {
        int x = record.get("x").getAsInt(), y = record.get("y").getAsInt(), z = record.get("z").getAsInt();
        if (Math.abs((long)x) > 30_000_000 || Math.abs((long)z) > 30_000_000 || y < -2048 || y > 2048) throw new IllegalArgumentException("Invalid supply cache position");
        return new BlockPos(x, y, z);
    }
    void save(String key, BlockPos position, String block, boolean confirmed) {
        JsonObject cache = new JsonObject(); cache.addProperty("x", position.getX()); cache.addProperty("y", position.getY()); cache.addProperty("z", position.getZ());
        cache.addProperty("block", block); cache.addProperty("confirmed", confirmed); cache.addProperty("updatedAt", System.currentTimeMillis());
        position(cache);
        if (records().size() >= 128 && !records().has(key)) throw new IllegalStateException("Personal supply cache limit reached; no box placed");
        records().add(key, cache); flush();
    }
    JsonObject saved(String key) { return records().has(key) ? records().getAsJsonObject(key).deepCopy() : null; }
    String nearest(String prefix, Vec3 from, Predicate<BlockPos> loaded) {
        return records().entrySet().stream().filter(e->e.getKey().startsWith(prefix+"\n"))
            .filter(e->loaded.test(position(e.getValue().getAsJsonObject()))&&from.distanceToSqr(Vec3.atCenterOf(position(e.getValue().getAsJsonObject())))<=32*32)
            .min(Comparator.comparingDouble(e->from.distanceToSqr(Vec3.atCenterOf(position(e.getValue().getAsJsonObject()))))).map(Map.Entry::getKey).orElse(null);
    }
    private void flush() { JsonObject root = new JsonObject(); root.addProperty("version", 1); root.add("caches", records()); TaskFiles.write(file, root); }
    static int count(Item item) { int count = 0; for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).is(item)) count += mc.player.getInventory().getItem(i).getCount(); return count; }
    static boolean confirmedWithdrawal(int baseline, int current) { return current > baseline; }
    BlockPos target() { return target; }
    boolean approaching() { return target!=null&&mc.level.getBlockEntity(target) instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity
        &&!mc.level.getBlockStatePredictionHandler().serverVerifiedStates.containsKey(target.asLong())
        &&(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target))>20.25||!BotStashScan.visibleFrom(mc.player.getEyePosition(),target)); }
    void approachTick() { if(++ticks>600)throw new IllegalStateException("Could not reach personal supply cache within 600 ticks; cache location retained"); }
    String detail() { return detail; }
    boolean busy() { return resource != null; }
    boolean canCancel(Item requested, boolean emptyCursor) { return resource != null && resource != requested && sent < 0 && emptyCursor; }
    private int supplySlot(Item requested, Predicate<ItemStack> cargo) {
        for (int i=0;i<36;i++) if(supplyBox(mc.player.getInventory().getItem(i),requested)&&!cargo.test(mc.player.getInventory().getItem(i))) return i;
        return -1;
    }
    boolean canRestock(Item requested, Predicate<ItemStack> cargo) {
        String known=nearest(key(mc.player.getUUID().toString(),scope(),requested),mc.player.position(),p->mc.level.getChunkSource().hasChunk(p.getX()>>4,p.getZ()>>4));
        return known!=null||supplySlot(requested,cargo)>=0;
    }
    static final class Unavailable extends IllegalStateException {
        Unavailable(String message) { super(message); }
    }
    JsonObject diagnostic() {
        JsonObject result = new JsonObject(); result.addProperty("detail", detail); result.addProperty("pendingWithdrawal", sent >= 0);
        if (target != null) result.addProperty("target", target.toShortString());
        if (resource != null) result.addProperty("resource", BuiltInRegistries.ITEM.getKey(resource).toString());
        return result;
    }
    /** Called only after landing, and outside the native transfer's pending-click lease. */
    boolean tick(Item requested, Predicate<ItemStack> cargo) {
        if (resource == null) { if (requested == null) return false; resource = requested; ticks = 0; }
        if (mc.player.isUsingItem()) { detail = "Waiting for food before personal restock"; return true; }
        if (++ticks > 600) throw new IllegalStateException("Personal " + resource + " restock timed out; cache retained for inspection");
        if (sent >= 0) {
            if (received) { close(); return true; }
            detail = "Verifying personal supply withdrawal (never replaying an uncertain click)";
            return true;
        }
        if (target == null) {
            cacheKey=nearest(key(),mc.player.position(),p->mc.level.getChunkSource().hasChunk(p.getX()>>4,p.getZ()>>4));
            JsonObject saved = cacheKey==null?null:saved(cacheKey);
            if (saved != null) {
                BlockPos known = position(saved);
                if (mc.level.getChunkSource().hasChunk(known.getX() >> 4, known.getZ() >> 4) && mc.player.position().distanceToSqr(Vec3.atCenterOf(known)) <= 32 * 32) {
                    if (BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(known).getBlock()).toString().equals(saved.get("block").getAsString())) target = known;
                    else if (saved.get("confirmed").getAsBoolean()&&!mc.level.getBlockStatePredictionHandler().serverVerifiedStates.containsKey(known.asLong())) { records().remove(cacheKey); flush(); }
                }
            }
            if (target == null) {
                int slot = supplySlot(resource,cargo);
                if (slot < 0) throw new Unavailable("No nearby personal " + resource + " cache or carried supply shulker; replenish consumables before continuing"
                    + " (inventoryReady=" + dev.monocle.client.utils.network.WorldReadiness.INSTANCE.inventoryReady() + ", carried=" + count(resource)
                    + ", offhand=" + BuiltInRegistries.ITEM.getKey(mc.player.getOffhandItem().getItem()) + " x" + mc.player.getOffhandItem().getCount() + ")");
                if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.containerMenu.getCarried().isEmpty()) { detail = "Waiting for inventory to place personal supply box"; return true; }
                if (slot > 8) { InvUtils.quickSwap().from(slot).toHotbar(mc.player.getInventory().getSelectedSlot()); detail = "Preparing personal supply shulker"; return true; }
                ItemStack box = mc.player.getInventory().getItem(slot);
                BlockPos site = BlockPos.betweenClosedStream(mc.player.blockPosition().offset(-3, -1, -3), mc.player.blockPosition().offset(3, 0, 3))
                    .filter(p -> mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(p)) <= 20.25 && mc.level.getBlockState(p.below()).isFaceSturdy(mc.level, p.below(), net.minecraft.core.Direction.UP)
                        && mc.level.getBlockState(p).canBeReplaced() && mc.level.getBlockState(p.above()).isAir()
                        && !mc.player.getBoundingBox().inflate(.25).intersects(new AABB(p)) && BlockUtils.canPlaceBlock(p, true, ((BlockItem)box.getItem()).getBlock()))
                    .min(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(mc.player.position()))).map(BlockPos::immutable).orElse(null);
                if (site == null) throw new IllegalStateException("No clear supported personal supply site within reach");
                String block = BuiltInRegistries.BLOCK.getKey(((BlockItem)box.getItem()).getBlock()).toString();
                // Durable intent precedes the packet; a placed food cache is not a cleanup-owned supply box.
                cacheKey=key()+"\n"+site.asLong();save(cacheKey, site, block, false); target = site;
                BlockUtils.place(site, InteractionHand.MAIN_HAND, slot, false, 0, true, true, true);
                detail = "Placing persistent personal supply cache at " + site.toShortString(); return true;
            }
        }
        JsonObject cache = saved(cacheKey);
        if (!BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(target).getBlock()).toString().equals(cache.get("block").getAsString())
            || mc.level.getBlockStatePredictionHandler().serverVerifiedStates.containsKey(target.asLong())) { detail = "Waiting for server-confirmed supply cache placement"; return true; }
        if (!cache.get("confirmed").getAsBoolean()) save(cacheKey, target, cache.get("block").getAsString(), true);
        if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) > 20.25 || !BotStashScan.visibleFrom(mc.player.getEyePosition(), target)) { detail = "Approaching personal supply cache"; return true; }
        if (mc.player.containerMenu == mc.player.inventoryMenu) {
            if (ticks % 10 == 1) { opening = true; BotStashScan.openContainer(target); }
            detail = "Opening personal supply cache"; return true;
        }
        if (!opening || !(mc.player.containerMenu instanceof ShulkerBoxMenu) || menu != mc.player.containerMenu.containerId || contents.isEmpty()) { detail = "Waiting for personal cache contents"; return true; }
        if (!mc.player.containerMenu.getCarried().isEmpty()) throw new IllegalStateException("Personal cache cursor is occupied; no supply move sent");
        int source = -1;
        for (int i = 0; i < 27; i++) if (contents.get(i).is(resource)) {
            if(resource==Items.ENCHANTED_GOLDEN_APPLE&&contents.get(i).has(DataComponents.CUSTOM_NAME)
                &&dev.monocle.client.systems.modules.Modules.get().get(dev.monocle.client.systems.modules.player.AutoEat.class).protectNamed.get())
                throw new IllegalStateException("Personal food cache contains named food; disable Protect named food in the job profile");
            source = i; break;
        }
        if (source < 0) { records().remove(cacheKey); flush(); close(); detail="Personal supply cache empty; looking for another carried shulker"; return true; }
        if (!ItemStack.matches(contents.get(source), mc.player.containerMenu.getSlot(source).getItem())) { detail = "Waiting for personal cache snapshot reconciliation"; return true; }
        boolean space = false;
        for (int i = 0; i < 36; i++) { ItemStack stack = mc.player.getInventory().getItem(i); if (stack.isEmpty() || ItemStack.isSameItemSameComponents(stack, contents.get(source)) && stack.getCount() < stack.getMaxStackSize()) { space = true; break; } }
        if (!space) throw new IllegalStateException("Personal supplies need an empty slot or a compatible partial stack; cargo was not discarded");
        before = count(resource); sent = mc.player.tickCount;
        mc.gameMode.handleContainerInput(menu, source, 0, ContainerInput.QUICK_MOVE, mc.player);
        detail = "Taking personal consumables from persistent cache"; return true;
    }
    void inventory(InventoryEvent event) {
        if (!opening || !(mc.player.containerMenu instanceof ShulkerBoxMenu) || event.packet.containerId() != mc.player.containerMenu.containerId || event.packet.items().size() != 63) return;
        menu = event.packet.containerId(); contents = event.packet.items().stream().map(ItemStack::copy).toList();
        confirm(event.packet.items().subList(27, 63));
    }
    private boolean received;
    private void confirm(List<ItemStack> inventory) { if (sent >= 0 && confirmedWithdrawal(before, inventory.stream().filter(s -> s.is(resource)).mapToInt(ItemStack::getCount).sum())) received = true; }
    void slot(ContainerSlotUpdateEvent event) {
        if (event.packet.getContainerId() != menu || contents.isEmpty() || event.packet.getSlot() < 0 || event.packet.getSlot() >= contents.size()) return;
        var updated = new ArrayList<>(contents); updated.set(event.packet.getSlot(), event.packet.getItem().copy()); contents = List.copyOf(updated); confirm(contents.subList(27, 63));
    }
    void close() {
        if (mc.player != null && menu >= 0 && mc.player.containerMenu.containerId == menu && mc.player.containerMenu.getCarried().isEmpty()) mc.player.closeContainer();
        resource = null; cacheKey=null; target = null; contents = List.of(); menu = sent = -1; ticks = 0; opening = received = false;
    }
}
