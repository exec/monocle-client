package dev.monocle.client.systems.bots;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.packets.InventoryEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.player.AutoEat;
import dev.monocle.client.systems.modules.player.AutoGap;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.Rotations;
import dev.monocle.client.utils.player.SlotUtils;
import dev.monocle.client.utils.world.BlockUtils;
import dev.monocle.client.utils.world.TickRate;
import dev.monocle.coordinator.SupplyRecovery;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedStack;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.phys.*;
import java.util.*;

import static dev.monocle.client.MonocleClient.mc;
import static dev.monocle.coordinator.TaskWire.text;

/** Game adapter for RecoverSupplies. Never owns the highway or issues a second placement/drop. */
public final class BotSupplyRecovery {
    private static SupplyRecovery journal;
    private final Bots bots;
    private final int searchRadius, retryTicks;
    private final double flyBeyond;
    private BotActions travel;
    private Vec3 destination;
    private int ticks, unchanged, inventoryAt = -1;
    private String selected = "", detail = "Reconciling workflow supplies";
    private boolean listening;
    private List<ItemStack> inventory = List.of();
    private BlockPos mining;
    private int miningUntil;

    BotSupplyRecovery(Bots bots, JsonObject policy) {
        this.bots = bots;
        searchRadius = policy.has("searchRadius") ? policy.get("searchRadius").getAsInt() : 16;
        retryTicks = policy.has("retryTicks") ? policy.get("retryTicks").getAsInt() : 100;
        flyBeyond = policy.has("flyBeyond") ? policy.get("flyBeyond").getAsDouble() : 8;
    }
    private static SupplyRecovery journal() {
        if (journal == null) journal = new SupplyRecovery(MonocleClient.FOLDER.toPath().resolve("workflow-supply-recovery.json"));
        return journal;
    }
    private static String owner() { return mc.player.getUUID().toString(); }
    private static String scope() { return (mc.getCurrentServer()==null ? "local:"+Utils.getWorldName() : mc.getCurrentServer().ip) + "\n" + mc.level.dimension().identifier(); }
    private static List<JsonObject> records() { return journal().pending(owner(),scope()); }
    private static BlockPos position(JsonObject r) { return new BlockPos(r.get("x").getAsInt(),r.get("y").getAsInt(),r.get("z").getAsInt()); }
    private static ItemStack decode(JsonElement s) { return ItemStack.CODEC.parse(mc.player.registryAccess().createSerializationContext(JsonOps.INSTANCE),s).getOrThrow(); }
    private static JsonElement encode(ItemStack s) { return ItemStack.CODEC.encodeStart(mc.player.registryAccess().createSerializationContext(JsonOps.INSTANCE),s.copyWithCount(1)).getOrThrow(); }
    private static boolean matches(ItemStack expected, ItemStack actual) {
        return !actual.isEmpty() && actual.is(expected.getItem()) && Objects.equals(expected.get(DataComponents.CUSTOM_NAME),actual.get(DataComponents.CUSTOM_NAME));
    }
    private static int count(ItemStack expected) { int n=0; for(int i=0;i<36;i++) if(matches(expected,mc.player.getInventory().getItem(i))) n+=mc.player.getInventory().getItem(i).getCount(); return n; }
    private static JsonObject at(BlockPos pos) { return records().stream().filter(r -> position(r).equals(pos)).findFirst().orElse(null); }
    public static boolean container(ItemStack stack) { return stack.is(Items.ENDER_CHEST) || stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock; }

    /** Called inside the actual placement callback, after all guards, before its packet. */
    public static void placing(BlockPos pos, ItemStack stack, String workflow, String execution) {
        if (!container(stack)) return;
        JsonObject old = at(pos);
        if (old != null) {
            if (text(old,"stage").equals("placing") && matches(decode(old.get("stack")),stack)) return;
            throw new IllegalStateException("Recover the previous supply operation at " + pos.toShortString() + " before placing again");
        }
        JsonObject r = new JsonObject(); r.addProperty("id",UUID.randomUUID().toString()); r.addProperty("owner",owner()); r.addProperty("scope",scope());
        r.addProperty("workflow",workflow); r.addProperty("x",pos.getX()); r.addProperty("y",pos.getY()); r.addProperty("z",pos.getZ());
        r.addProperty("execution",execution);
        r.addProperty("stage","placing"); r.add("stack",encode(stack)); r.addProperty("baseline",count(stack)); r.addProperty("expected",1);
        journal().put(r);
    }
    public static void breaking(BlockPos pos, ItemStack tool) {
        JsonObject r = at(pos); if(r == null || text(r,"stage").equals("breaking")) return;
        ItemStack original = decode(r.get("stack"));
        boolean obsidian = original.is(Items.ENDER_CHEST) && !Utils.hasEnchantment(tool,Enchantments.SILK_TOUCH);
        ItemStack drop = obsidian ? new ItemStack(Items.OBSIDIAN) : original;
        r.add("container",r.get("stack").deepCopy()); r.add("stack",encode(drop));
        r.addProperty("stage","breaking"); r.addProperty("baseline",count(drop)); r.addProperty("expected",obsidian ? 8 : 1);
        journal().put(r);
    }
    /** Only the native restocker's already-confirmed pickup path may retire its obligation. */
    public static void recovered(BlockPos pos) { JsonObject r=at(pos); if(r!=null) journal().resolved(text(r,"id")); }

    String detail() { return detail; }
    boolean tick() {
        ticks++;
        if (!listening) { MonocleClient.EVENT_BUS.subscribe(this); listening=true; }
        if (ticks % 20 == 1 && mc.player.containerMenu == mc.player.inventoryMenu) refresh();
        List<JsonObject> pending=records();
        if(pending.isEmpty()) {
            String concern=bots.crew.workflowRecoveryConcern();
            if(!concern.isEmpty()) { detail=concern; return false; }
            close(); return true;
        }
        JsonObject r=pending.stream().min(Comparator.comparingDouble(v -> position(v).distToCenterSqr(mc.player.position()))).orElseThrow();
        if(!selected.equals(text(r,"id"))) { stopTravel(); selected=text(r,"id"); unchanged=0; }
        BlockPos pos=position(r); ItemStack expected=decode(r.get("stack"));
        boolean loaded=mc.level.hasChunkAt(pos) && !mc.level.getBlockStatePredictionHandler().serverVerifiedStates.containsKey(pos.asLong());
        var drops=mc.level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(searchRadius),e -> matches(expected,e.getItem()));
        int carried=inventory.stream().filter(s -> matches(expected,s)).mapToInt(ItemStack::getCount).sum();
        if(SupplyRecovery.confirmed(inventoryAt>=0 && ticks-inventoryAt<=40,loaded,loaded && mc.level.getBlockState(pos).isAir(),!drops.isEmpty(),carried,r.get("baseline").getAsInt(),r.get("expected").getAsInt(),text(r,"stage").equals("placing"))) {
            journal().resolved(selected);
            if (records().stream().noneMatch(other -> text(other,"execution").equals(text(r,"execution")))) bots.crew.workflowRecoveryComplete(text(r,"execution"));
            stopTravel(); inventoryAt=-1; detail="Supply recovery confirmed by inventory and world"; return false;
        }
        mining=null;
        if (mc.player.isUsingItem() || Modules.get().get(AutoEat.class).eating || Modules.get().get(AutoGap.class).isEating() || TickRate.INSTANCE.getTimeSinceLastTick()>=1.5f) { stopTravel(); detail="Recovery yielding to food / server lag"; return false; }
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()) { stopTravel(); detail="Recovery waiting for the current inventory transaction"; return false; }
        if (++unchanged % retryTicks == 0) { stopTravel(); mc.gameMode.stopDestroyBlock(); refresh(); }
        if (!loaded || pos.distToCenterSqr(mc.player.position())>16) {
            move(approach(pos),"Travelling to recorded supplies"); return false;
        }
        var state=mc.level.getBlockState(pos);
        if (!state.isAir()) {
            ItemStack original=decode(r.has("container") ? r.get("container") : r.get("stack"));
            boolean owned=container(original) && original.getItem() instanceof BlockItem bi && state.is(bi.getBlock());
            if(owned && state.getBlock() instanceof ShulkerBoxBlock) owned=mc.level.getBlockEntity(pos) instanceof ShulkerBoxBlockEntity box && Objects.equals(original.get(DataComponents.CUSTOM_NAME),box.getCustomName());
            if(!owned) { stopTravel(); detail="Inspection needed: recorded container was replaced at "+pos.toShortString(); return false; }
            if(!hasRoom(expected)) { stopTravel(); detail="Recovery needs inventory room; supplies remain protected at "+pos.toShortString(); return false; }
            if(!mc.player.onGround() || mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos))>Math.pow(Math.min(4.5,mc.player.blockInteractionRange()),2)) { move(approach(pos),"Approaching owned supply container"); return false; }
            stopTravel(); int slot=toolSlot(state, r);
            if(slot<0) { detail="Recovery needs a usable pickaxe at "+pos.toShortString(); return false; }
            if(slot>8) { InvUtils.move().from(slot).toHotbar(mc.player.getInventory().getSelectedSlot()); inventoryAt=-1; detail="Preparing recovery tool"; return false; }
            InvUtils.swap(slot,false); breaking(pos,mc.player.getMainHandItem());
            mc.player.setYRot((float)Rotations.getYaw(pos)); mc.player.setXRot((float)Rotations.getPitch(pos));
            mining=pos; miningUntil=mc.player.tickCount+1; detail="Recovering owned container at "+pos.toShortString(); return false;
        }
        if(!drops.isEmpty()) {
            if(!hasRoom(expected)) { stopTravel(); detail="Recovery needs inventory room for the tracked drop"; return false; }
            ItemEntity drop=drops.stream().filter(e -> !r.has("drop") || text(r,"drop").equals(e.getUUID().toString())).min(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player))).orElse(null);
            // A name/color match without a witnessed identity is ambiguous when several candidates exist.
            if(drop!=null && (r.has("drop") || drops.size()==1)) {
                if(!r.has("drop")) { r.addProperty("drop",drop.getUUID().toString()); journal().put(r); }
                move(drop.position(),"Collecting tracked supply drop"); return false;
            }
        }
        stopTravel(); detail="Searching recorded supply site / awaiting inventory evidence at "+pos.toShortString();
        return false;
    }
    private boolean hasRoom(ItemStack expected) {
        for(int i=0;i<36;i++) { ItemStack s=mc.player.getInventory().getItem(i); if(s.isEmpty() || ItemStack.isSameItemSameComponents(expected,s) && s.getCount()<s.getMaxStackSize()) return true; }
        return false;
    }
    private int toolSlot(net.minecraft.world.level.block.state.BlockState state, JsonObject record) {
        int best=-1; float speed=0;
        for(int i=0;i<36;i++) { ItemStack s=mc.player.getInventory().getItem(i);
            if(!s.is(ItemTags.PICKAXES) || s.isDamageableItem() && s.getMaxDamage()-s.getDamageValue()<=1) continue;
            if(state.is(Blocks.ENDER_CHEST) && text(record,"stage").equals("breaking") && Utils.hasEnchantment(s,Enchantments.SILK_TOUCH)!=decode(record.get("stack")).is(Items.ENDER_CHEST)) continue;
            if (!Modules.get().get(HighwayBuilder.class).allowsRecoveryTool(state,s)) continue;
            if(s.getDestroySpeed(state)>speed) { best=i; speed=s.getDestroySpeed(state); }
        }
        return best;
    }
    private Vec3 approach(BlockPos pos) {
        for(var dir:net.minecraft.core.Direction.Plane.HORIZONTAL) { BlockPos feet=pos.relative(dir); if(mc.level.hasChunkAt(feet) && mc.level.getBlockState(feet).isAir() && mc.level.getBlockState(feet.above()).isAir() && mc.level.getBlockState(feet.below()).isFaceSturdy(mc.level,feet.below(),net.minecraft.core.Direction.UP)) return Vec3.atBottomCenterOf(feet); }
        return Vec3.atBottomCenterOf(pos.offset(0,0,1));
    }
    private void move(Vec3 target,String message) {
        if(destination==null || destination.distanceToSqr(target)>.25) {
            stopTravel(); destination=target; travel=new BotActions(bots);
            JsonObject a=new JsonObject(); a.addProperty("type","Travel"); a.addProperty("x",target.x); a.addProperty("y",target.y); a.addProperty("z",target.z); a.addProperty("radius",.6); a.addProperty("flyBeyond",flyBeyond); travel.start(a);
        }
        JsonObject status=travel.tick(); detail=message+" · "+text(status,"detail");
        if(!text(status,"state").equals("Running")) { stopTravel(); refresh(); }
    }
    private void stopTravel() { if(travel!=null) { travel.stop(); travel=null; } destination=null; }
    private void refresh() { mc.getConnection().send(HighwayBuilder.cursorSyncRequest(mc.player.inventoryMenu.containerId,HashedStack.create(mc.player.inventoryMenu.getCarried(),mc.getConnection().decoratedHashOpsGenenerator()))); }
    @EventHandler private void inventory(InventoryEvent e) {
        if(!mc.isSameThread() || !Utils.canUpdate() || e.packet.containerId()!=mc.player.inventoryMenu.containerId) return;
        List<ItemStack> items=e.packet.items(); List<ItemStack> snapshot=new ArrayList<>();
        for(int i=0;i<36;i++) { int slot=SlotUtils.indexToId(i); if(slot>=items.size()) return; snapshot.add(items.get(slot).copy()); }
        inventory=List.copyOf(snapshot); inventoryAt=ticks;
    }
    @EventHandler private void mine(TickEvent.Pre event) {
        if(mining==null || !Utils.canUpdate() || mc.player.tickCount>miningUntil || mc.player.isUsingItem() || bots.crew.localAssigned()
            || Modules.get().get(AutoEat.class).eating || Modules.get().get(AutoGap.class).isEating() || TickRate.INSTANCE.getTimeSinceLastTick()>=1.5f
            || mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(mining))>Math.pow(Math.min(4.5,mc.player.blockInteractionRange()),2)) return;
        JsonObject r=at(mining); if(r==null || !text(r,"id").equals(selected)) return;
        ItemStack original=decode(r.has("container")?r.get("container"):r.get("stack"));
        var state=mc.level.getBlockState(mining); ItemStack tool=mc.player.getMainHandItem();
        if (!tool.is(ItemTags.PICKAXES) || tool.isDamageableItem() && tool.getMaxDamage()-tool.getDamageValue()<=1
            || !Modules.get().get(HighwayBuilder.class).allowsRecoveryTool(state,tool)) return;
        if (state.is(Blocks.ENDER_CHEST) && Utils.hasEnchantment(tool,Enchantments.SILK_TOUCH)!=decode(r.get("stack")).is(Items.ENDER_CHEST)) return;
        if (state.getBlock() instanceof ShulkerBoxBlock && (!(mc.level.getBlockEntity(mining) instanceof ShulkerBoxBlockEntity box)
            || !Objects.equals(original.get(DataComponents.CUSTOM_NAME),box.getCustomName()))) return;
        if(container(original) && original.getItem() instanceof BlockItem bi && state.is(bi.getBlock())) BlockUtils.breakBlock(mining,true);
    }
    boolean suspend() { return travel==null || travel.requestSuspend(); }
    void close() { mining=null; stopTravel(); if(listening) { MonocleClient.EVENT_BUS.unsubscribe(this); listening=false; } if(Utils.canUpdate()) mc.gameMode.stopDestroyBlock(); }
}
