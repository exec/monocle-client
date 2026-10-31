package dev.monocle.client.systems.bots;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.monocle.client.events.packets.InventoryEvent;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.coordinator.StashCatalog;
import dev.monocle.coordinator.TaskFiles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.HashedStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import static dev.monocle.client.MonocleClient.mc;

/** Private cargo buffer; internal moves never generate stash stock receipts. */
final class BotStashBuffer {
    private final JsonObject action;
    private BlockPos target;
    private int menu=-1,wait,slots,moved,limit=-1,sent,inventoryBefore=-1,reopens,initialBuffer=-1;
    private String fingerprint="";
    private List<ItemStack> contents=List.of();
    private ItemStack cursor=ItemStack.EMPTY;
    private BotStashBulk bulk;
    private final Set<BlockPos> triedViews=new HashSet<>();
    private boolean opening,finished,reconciling,pairChecked;
    BotStashBuffer(JsonObject action){this.action=action;}
    BlockPos target(){return target;}
    boolean approaching(){return target!=null&&(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target))>20.25||!BotStashScan.visibleFrom(mc.player.getEyePosition(),target));}
    boolean done(){return finished&&mc.player.containerMenu==mc.player.inventoryMenu;}
    boolean canYieldInventory(){return bulk==null&&!reconciling&&cursor.isEmpty();}
    dev.monocle.coordinator.ActionProgress.Observation progress(){
        boolean moving=approaching()&&bulk==null&&!reconciling;
        return BotActions.progress("Ender cargo",moving?"Reach ender chest":bulk!=null||reconciling?"Server cargo reconciliation":"Container contents",moved+"/"+limit,target,false,moving);
    }
    String detail(){return "Ender-chest "+action.get("operation").getAsString()+" · "+moved+" kits verified"+(reconciling?" · reconciling reopened cargo":"");}
    private boolean take(){return action.get("operation").getAsString().equals("Take");}
    private boolean inspect(){return action.get("operation").getAsString().equals("Inspect");}
    private boolean stow(){return action.get("operation").getAsString().equals("Stow");}
    private int expected(){return stow()&&initialBuffer>=0?initialBuffer:action.get("buffered").getAsInt();}
    JsonObject result(){JsonObject r=new JsonObject();r.addProperty("capacity",slots);r.addProperty("moved",moved);r.addProperty("buffered",expected()+(take()?-moved:moved));return r;}
    JsonObject snapshot(){
        JsonObject s=new JsonObject();s.addProperty("initialBuffer",initialBuffer);s.addProperty("moved",moved);s.addProperty("limit",limit);s.addProperty("slots",slots);s.addProperty("finished",finished);
        s.addProperty("pending",bulk!=null||reconciling);s.addProperty("sent",bulk==null?Math.max(moved,sent):moved+bulk.sent-bulk.verified);
        s.addProperty("inventoryBefore",inventoryBefore);s.addProperty("fingerprint",fingerprint);return s;
    }
    void restore(JsonObject s){
        initialBuffer=s.has("initialBuffer")?StashCatalog.integer(s,"initialBuffer",-1,54):-1;
        moved=StashCatalog.integer(s,"moved",0,36);limit=StashCatalog.integer(s,"limit",-1,36);slots=StashCatalog.integer(s,"slots",0,54);
        finished=s.get("finished").getAsBoolean();reconciling=s.get("pending").getAsBoolean();sent=StashCatalog.integer(s,"sent",moved,36);
        inventoryBefore=StashCatalog.integer(s,"inventoryBefore",-1,36);fingerprint=s.get("fingerprint").getAsString();
        if(stow()&&(limit>=0||moved>0||reconciling)&&initialBuffer<0||limit>=0&&moved>limit||reconciling&&(finished||inventoryBefore<0||!fingerprint.matches("[a-f0-9]{64}")))throw new IllegalArgumentException("Invalid ender cargo checkpoint");
    }
    void tick(){
        if(finished){close();return;}
        if(target==null){target=nearest();if(target==null)throw new IllegalStateException("No nearby ender chest for transport; cargo retained");triedViews.add(target);}
        if(approaching())return;
        if(mc.player.containerMenu==mc.player.inventoryMenu){
            if(opening&&menu>=0){retainPendingMoves();clearMenu();}
            if(++wait%10==1){opening=true;BotStashScan.openContainer(target);}if(wait>100)throw new IllegalStateException("Ender chest did not open; cargo retained");return;
        }
        if(!opening){mc.player.closeContainer();return;}
        if(menu>=0&&menu!=mc.player.containerMenu.containerId){reopen("menu changed from "+menu+" to "+mc.player.containerMenu.containerId);return;}
        if(contents.isEmpty()){if(++wait>100)throw new IllegalStateException("Ender chest sent no snapshot; cargo retained");return;}
        if(slots!=27&&slots!=54)throw new IllegalStateException("Unexpected ender chest capacity; cargo retained");
        if(slots==27&&!pairChecked){
            pairChecked=true;BlockPos partner=partner();
            if(partner!=null&&triedViews.size()<8&&triedViews.add(partner)){target=partner;close();return;}
        }
        if(!cursor.isEmpty()||!mc.player.containerMenu.getCarried().isEmpty()){
            if(++wait>100)throw new IllegalStateException("Ender chest cursor did not clear; inspect retained cargo");requestSync();return;
        }
        if(bulk!=null){
            bulk.send(menu,mc.player.tickCount,contents,cursor);
            if(mc.player.tickCount-bulk.sentTick>=20)reopen("confirmation timed out");
            return;
        }
        if(!BotStashDeposit.sameContents(contents,mc.player.containerMenu.slots.stream().map(s->s.getItem()).toList())){
            if(++wait>100)throw new IllegalStateException("Ender chest snapshot did not reconcile; cargo retained");if(wait%10==1)requestSync();return;
        }
        int inBuffer=count(contents.subList(0,slots)),inInventory=count(contents.subList(slots,contents.size()));
        if(stow()&&initialBuffer<0)initialBuffer=inBuffer;
        if(reconciling){
            int delta=reconciledMove(expected(),inventoryBefore,inBuffer,inInventory,moved,sent,take());
            if(delta<0||!fingerprint.equals(fingerprint(contents)))throw new IllegalStateException("Ender cargo changed during interruption; inspect it before resuming");
            moved=delta;reconciling=false;
        }
        if(inBuffer!=expected()+(take()?-moved:moved)){
            // A single chest can expose only half of the server's double ender inventory.
            if(limit<0&&moved==0&&!reconciling&&nextView(inBuffer))return;
            throw new IllegalStateException("Ender chest cargo differs from this job's buffer; personal items retained");
        }
        if(inspect()){if(expected()!=0||contents.subList(0,slots).stream().anyMatch(s->!s.isEmpty()))throw new IllegalStateException("Empty the ender chest before enabling kit transport");finished=true;close();return;}
        if(limit<0){
            inventoryBefore=inInventory;fingerprint=fingerprint(contents);
            int free=(int)contents.subList(take()?slots:0,take()?contents.size():slots).stream().filter(ItemStack::isEmpty).count();
            limit=Math.min(action.get("count").getAsInt(),Math.min(free,take()?expected():inInventory));
            if(limit<1)throw new IllegalStateException("No free space or matching cargo for ender transport; cargo retained");
        }
        if(moved==limit){finished=true;close();return;}
        wait=0;
        bulk=BotStashBulk.plan(contents,slots,limit-moved,mc.player.tickCount,take(),false,false,slot->BotStashScan.matchesKit(contents.get(slot),action),1);
        if(bulk==null)throw new IllegalStateException("Ender cargo cannot be moved safely; cargo retained");
        bulk.send(menu,mc.player.tickCount,contents,cursor);
    }
    static int reconciledMove(int before,int inventoryBefore,int after,int inventoryAfter,int verified,int sent,boolean take){
        int moved=take?before-after:after-before;
        return moved>=verified&&moved<=sent&&inventoryAfter==inventoryBefore+(take?moved:-moved)?moved:-1;
    }
    private int count(List<ItemStack> stacks){int n=0;for(ItemStack stack:stacks)if(BotStashScan.matchesKit(stack,action))n+=stack.getCount();return n;}
    private String fingerprint(List<ItemStack> stacks){
        List<String> cargo=new ArrayList<>();for(ItemStack stack:stacks)if(BotStashScan.matchesKit(stack,action))cargo.add(ItemStack.CODEC.encodeStart(mc.player.registryAccess().createSerializationContext(JsonOps.INSTANCE),stack).getOrThrow().toString());
        Collections.sort(cargo);return TaskFiles.hash(cargo.toString());
    }
    private BlockPos nearest(){
        return nearest(Utils.blockEntities(),mc.player.getEyePosition(),action.get("minY").getAsInt(),action.get("maxY").getAsInt(),triedViews);
    }
    private boolean nextView(int observed){
        if(triedViews.size()>=8)return false;
        BlockPos next=nearest();if(next==null)return false;
        dev.monocle.client.MonocleClient.LOG.warn("[Workers] Trying another ender chest before any cargo move; expected={}, observed={}, slots={}, views={}/8",expected(),observed,slots,triedViews.size());
        close();target=next;triedViews.add(next);pairChecked=false;return true;
    }
    static BlockPos nearest(Iterable<? extends net.minecraft.world.level.block.entity.BlockEntity> entities,Vec3 from,int minY,int maxY){
        return nearest(entities,from,minY,maxY,Set.of());
    }
    static BlockPos nearest(Iterable<? extends net.minecraft.world.level.block.entity.BlockEntity> entities,Vec3 from,int minY,int maxY,Set<BlockPos> excluded){
        Map<BlockPos,net.minecraft.world.level.block.state.BlockState> chests=new HashMap<>();
        for(var entity:entities)if(entity instanceof EnderChestBlockEntity)chests.put(entity.getBlockPos(),entity.getBlockState());
        BlockPos best=null;double distance=128*128;boolean bestPaired=false;
        for(var entry:chests.entrySet()){BlockPos p=entry.getKey();
            // Nearby in X/Z is not nearby when an old ender chest is buried below the stash.
            if(excluded.contains(p)||p.getY()<minY-4||p.getY()>maxY+4)continue;
            double d=Vec3.atCenterOf(p).distanceToSqr(from);if(d>=128*128)continue;
            boolean paired=false;
            for(Direction side:Direction.Plane.HORIZONTAL){var other=chests.get(p.relative(side));
                if(other!=null&&other.getValue(EnderChestBlock.FACING)==entry.getValue().getValue(EnderChestBlock.FACING)){paired=true;break;}}
            if(best==null||paired&&!bestPaired||paired==bestPaired&&d<distance){best=p.immutable();distance=d;bestPaired=paired;}}
        return best;
    }
    private BlockPos partner(){
        var state=mc.level.getBlockState(target);
        if(!(state.getBlock() instanceof EnderChestBlock))return null;
        for(Direction d:Direction.Plane.HORIZONTAL){BlockPos p=target.relative(d);var other=mc.level.getBlockState(p);
            if(other.getBlock() instanceof EnderChestBlock&&other.getValue(EnderChestBlock.FACING)==state.getValue(EnderChestBlock.FACING))return p;}
        return null;
    }
    void inventory(InventoryEvent e){
        if(!opening||mc.player.containerMenu==mc.player.inventoryMenu||e.packet.containerId()!=mc.player.containerMenu.containerId||e.packet.items().size()!=mc.player.containerMenu.slots.size())return;
        if(menu>=0&&menu!=e.packet.containerId())retainPendingMoves();
        menu=e.packet.containerId();contents=BotStashDeposit.copyContents(e.packet.items());cursor=e.packet.carriedItem().copy();
        slots=0;while(slots<mc.player.containerMenu.slots.size()&&mc.player.containerMenu.getSlot(slots).container!=mc.player.getInventory())slots++;
        if(bulk!=null){moved+=bulk.observe(contents,cursor);if(bulk.complete())bulk=null;}
    }
    private void requestSync(){if(menu>=0)mc.getConnection().send(HighwayBuilder.cursorSyncRequest(menu,HashedStack.create(mc.player.containerMenu.getCarried(),mc.getConnection().decoratedHashOpsGenenerator())));}
    private void clearMenu(){opening=false;menu=-1;contents=List.of();cursor=ItemStack.EMPTY;}
    void retainPendingMoves(){if(bulk!=null){sent=moved+bulk.sent-bulk.verified;bulk=null;reconciling=true;}}
    private void reopen(String reason){
        dev.monocle.client.MonocleClient.LOG.warn("[Workers] Reopening ender cargo after {}; operation={}, verified={}, attempt={}/3",reason,action.get("operation"),moved,reopens+1);
        close();if(++reopens>3)throw new IllegalStateException("Ender cargo reopening exhausted; cargo retained for resume");
    }
    void close(){retainPendingMoves();if(mc.player!=null&&mc.player.containerMenu!=mc.player.inventoryMenu)mc.player.closeContainer();clearMenu();wait=0;}
}
