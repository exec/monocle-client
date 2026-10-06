package dev.monocle.client.systems.bots;

import com.google.gson.*;
import dev.monocle.client.events.packets.InventoryEvent;
import dev.monocle.client.systems.modules.misc.swarm.CrewInventory;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import net.minecraft.network.HashedStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.*;
import java.util.*;
import static dev.monocle.client.MonocleClient.mc;

/** Server-confirmed stash-box withdrawal followed by ender-chest deposit. */
final class BotStashResupply {
    private final JsonObject action;private final JsonArray picks,receipt=new JsonArray();private int index,batchLimit,wait,menu=-1,withdrawSent=-1,withdrawSlot=-1,withdrawBefore,deposited,depositBefore=-1,depositSlot=-1,depositAt;private JsonObject withdrawnBox;private BlockPos target;private boolean depositing,finished,withdrawConfirmed,menuReady;
    private List<ItemStack> serverContents=List.of(),depositContents=List.of();private ItemStack depositStack=ItemStack.EMPTY;private int serverCarried=-1;
    private int unavailableTicks,noProgressTicks;private double closest=Double.POSITIVE_INFINITY;private String waiting="",sourceIssue="";private boolean openingRequested;
    BotStashResupply(JsonObject action){this.action=action;this.picks=action.getAsJsonArray("picks");index=action.has("pickIndex")?action.get("pickIndex").getAsInt():0;}
    boolean done(){return finished&&mc.player.containerMenu==mc.player.inventoryMenu;}
    boolean targetIsHopper(){return target!=null&&mc.level.getBlockEntity(target) instanceof HopperBlockEntity;}
    BlockPos navigationTarget(){return BotStashScan.navigationTarget(target);}
    boolean approaching(){return target!=null&&!finished&&(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target))>20.25||!targetIsHopper()&&!BotStashScan.visibleFrom(mc.player.getEyePosition(),target));}
    BlockPos target(){return target;}
    boolean hasWithdrawal(){return !receipt.isEmpty();}
    JsonObject result(){JsonObject r=new JsonObject();r.addProperty("stash",action.get("name").getAsString());if(action.has("catalogCrew"))r.add("catalogCrew",action.get("catalogCrew").deepCopy());r.add("withdrawn",receipt.deepCopy());if(action.has("kitTypeId")){r.addProperty("picked",receipt.size());r.addProperty("nextPick",index);r.addProperty("sourceExhausted",sourceIssue.isEmpty()&&index>=picks.size());if(!sourceIssue.isEmpty())r.addProperty("sourceIssue",sourceIssue);}return r;}
    JsonObject workflowResult(){JsonObject r=result();if(action.has("kitTypeId"))r.remove("withdrawn");return r;}
    JsonObject snapshot(){JsonObject s=new JsonObject();s.addProperty("index",index);s.addProperty("batchLimit",batchLimit);s.addProperty("depositing",depositing);s.addProperty("deposited",deposited);s.addProperty("withdrawPending",withdrawSent>=0);s.addProperty("depositPending",depositBefore>=0);s.addProperty("finished",finished);s.addProperty("sourceIssue",sourceIssue);s.add("receipt",receipt.deepCopy());return s;}
    void restore(JsonObject s){
        if(s.has("withdrawPending")&&s.get("withdrawPending").getAsBoolean()||s.has("depositPending")&&s.get("depositPending").getAsBoolean())throw new IllegalStateException("Stash transfer was interrupted during an unconfirmed inventory move; inspect the containers before retrying");
        index=s.get("index").getAsInt();batchLimit=s.has("batchLimit")?s.get("batchLimit").getAsInt():action.has("kitTypeId")?action.get("count").getAsInt():0;
        depositing=s.get("depositing").getAsBoolean();deposited=s.has("deposited")?s.get("deposited").getAsInt():0;finished=s.has("finished")&&s.get("finished").getAsBoolean();receipt.addAll(s.getAsJsonArray("receipt"));
        sourceIssue=s.has("sourceIssue")?s.get("sourceIssue").getAsString():"";
        if(index<0||index>picks.size()||batchLimit<0||batchLimit>36||receipt.size()>wanted()||deposited<0||deposited>wanted()||sourceIssue.length()>1024||!sourceIssue.isEmpty()&&(!finished||receipt.isEmpty()))throw new IllegalArgumentException("Invalid stash resupply checkpoint");
    }
    private int wanted(){return action.has("kitTypeId")?batchLimit>0?batchLimit:action.get("count").getAsInt():picks.size();}
    private boolean transferAll(){return action.has("transferAll")&&action.get("transferAll").getAsBoolean();}
    static boolean sourceContainer(BlockEntity entity){return entity instanceof BaseContainerBlockEntity;}
    /** Recover only known pickups. An uncertain click must never become a retry or a partial success. */
    boolean recoverSourceIssue(String issue){
        if(withdrawSent>=0||depositBefore>=0||depositing||!action.has("kitTypeId")||!action.has("depositMode")||!action.get("depositMode").getAsString().equals("Carry")||receipt.isEmpty())return false;
        sourceIssue=issue;finished=true;return true;
    }
    private void sourceProblem(String issue){
        String reason=issue+" · "+action.get("name").getAsString()+" · source "+(target==null?index:target.toShortString())+" · picked "+receipt.size()+"/"+wanted();
        if(recoverSourceIssue(reason))return;throw new IllegalStateException(reason);
    }
    private void nextSource(){index++;target=null;wait=unavailableTicks=noProgressTicks=0;closest=Double.POSITIVE_INFINITY;waiting="";close();}
    boolean approachTimedOut(double distance){
        if(Double.isFinite(distance)&&(!Double.isFinite(closest)||distance<closest-.25)){closest=distance;noProgressTicks=0;}
        return ++noProgressTicks>200;
    }
    boolean finishSourceSearch(){
        if(depositing||receipt.size()>=wanted()||index<picks.size())return false;
        if(!transferAll()){sourceProblem("Mapped sources did not contain enough matching kits");return finished;}
        finished=true;return true;
    }
    static int batchSize(int requested,int free,boolean carry){if(free<1)throw new IllegalStateException("Kit pickup needs at least one empty inventory slot");if(carry&&free<requested)throw new IllegalStateException("Carry destination needs "+requested+" empty inventory slots; only "+free+" are free");return Math.min(requested,free);}
    private static BlockPos pickPosition(JsonObject pick){return new BlockPos(pick.get("x").getAsInt(),pick.get("y").getAsInt(),pick.get("z").getAsInt());}
    static boolean keepSourceOpen(JsonArray picks,int index,BlockPos target,int collected,int wanted){return collected<wanted&&index<picks.size()&&pickPosition(picks.get(index).getAsJsonObject()).equals(target);}
    String detail(){return !waiting.isEmpty()?waiting:depositing?"Filling ender chest from stash supplies":"Collecting stash shulker "+Math.min(receipt.size()+1,wanted())+"/"+wanted()+(target==null?"":" · "+target.toShortString());}
    void tick(){
        if(done())return;if(finished){close();return;}
        if(action.has("kitTypeId")&&batchLimit==0){
            int free=0;for(int i=0;i<36;i++)if(mc.player.getInventory().getItem(i).isEmpty())free++;
            if(countCarried()>0)throw new IllegalStateException("Move matching kits out of carried inventory before this job so personal boxes cannot be delivered");
            batchLimit=batchSize(action.get("count").getAsInt(),free,action.has("destination")&&action.get("destination").getAsString().equals("Carry"));
        }
        // Consume an authoritative pickup receipt before checking whether its container changed.
        if(withdrawSent>=0&&withdrawConfirmed){withdraw();return;}
        if(finishSourceSearch()){close();return;}
        if(!depositing&&receipt.size()>=wanted()){close();if(action.has("depositMode")&&action.get("depositMode").getAsString().equals("Carry")){finished=true;return;}depositing=true;target=findEchest();wait=0;if(target==null)throw new IllegalStateException("Put an accessible ender chest within 16 blocks of the mapped stash");}
        if(done())return;
        if(target==null)target=pickPosition(picks.get(index).getAsJsonObject());
        if(!mc.level.getChunkSource().hasChunk(target.getX()>>4,target.getZ()>>4)){
            waiting="Waiting for source chunk at "+target.toShortString();if(approachTimedOut(mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(target))))sourceProblem("Source chunk was not received and approach made no progress for 200 ticks");return;
        }
        if(!depositing&&!sourceContainer(mc.level.getBlockEntity(target))){
            String block=BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(target).getBlock()).toString();
            waiting="Rechecking source "+target.toShortString()+" ("+block+")";
            if(++unavailableTicks>40)sourceProblem("Expected a storage container, found "+block);return;
        }
        unavailableTicks=0;waiting="";
        if(approaching()){
            if(approachTimedOut(mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(target))))sourceProblem("No useful approach progress after 200 ticks");return;
        }
        noProgressTicks=0;
        if(mc.player.containerMenu==mc.player.inventoryMenu){if(++wait>200){sourceProblem("Container did not open after 200 ticks");return;}if(wait%20==1)open();return;}
        if(!openingRequested){waiting="Waiting for another container to close before opening "+target.toShortString();if(++wait>200)sourceProblem("Another container prevented source opening");return;}
        if(menu<0)menu=mc.player.containerMenu.containerId;if(mc.player.containerMenu.containerId!=menu)return;
        if(!menuReady){if(++wait>200){sourceProblem("Container did not send contents after 200 ticks");return;}if(wait%10==0)requestSync();return;}
        if(depositing)deposit();else withdraw();
    }
    private void open(){
        openingRequested=true;
        BotStashScan.openContainer(target);
    }
    private void withdraw(){
        JsonObject pick=picks.get(index).getAsJsonObject();boolean dynamic=pick.has("dynamic")&&pick.get("dynamic").getAsBoolean();
        if(withdrawSent>=0){
            if(withdrawConfirmed){JsonObject actual=pick.deepCopy();actual.addProperty("slot",withdrawSlot);actual.add("items",withdrawnBox.get("items").deepCopy());receipt.add(actual);withdrawSent=withdrawSlot=-1;withdrawnBox=null;withdrawConfirmed=false;
                if(!dynamic)index++;if(!keepSourceOpen(picks,index,target,receipt.size(),wanted())){target=null;close();wait=unavailableTicks=noProgressTicks=0;closest=Double.POSITIVE_INFINITY;}return;}
            if(mc.player.tickCount-withdrawSent>60)throw new IllegalStateException("Kit withdrawal was not confirmed; inspect the source chest before retrying");if((mc.player.tickCount-withdrawSent)%10==0)requestSync();return;
        }
        int sourceSlot=dynamic?-1:pick.get("slot").getAsInt();
        if(dynamic){for(int i=0;i<mc.player.containerMenu.slots.size();i++){var slot=mc.player.containerMenu.getSlot(i);if(slot.container==mc.player.getInventory())break;if(matches(slot.getItem(),pick)){sourceSlot=i;break;}}}
        if(sourceSlot<0){nextSource();return;}
        if(sourceSlot>=mc.player.containerMenu.slots.size()||mc.player.containerMenu.getSlot(sourceSlot).container==mc.player.getInventory()||!matches(mc.player.containerMenu.getSlot(sourceSlot).getItem(),pick))throw new IllegalStateException("Selected stash shulker changed since its scan");
        withdrawnBox=BotStashScan.classify(List.of(mc.player.containerMenu.getSlot(sourceSlot).getItem())).get(0).getAsJsonObject();
        withdrawSlot=sourceSlot;withdrawBefore=countCarried();InvUtils.shiftClick().slotId(sourceSlot);withdrawSent=mc.player.tickCount;requestSync();
    }
    private void deposit(){
        int empty=-1;for(int i=0;i<mc.player.containerMenu.slots.size();i++){var s=mc.player.containerMenu.getSlot(i);if(s.container==mc.player.getInventory())break;if(!s.hasItem()){empty=i;break;}}
        int inventory=-1;for(int i=0;i<36;i++)if(matchesAny(mc.player.getInventory().getItem(i))){inventory=i;break;}
        if(action.has("kitTypeId")){
            if(deposited>=wanted()){finished=true;close();return;}
            if(depositBefore>=0){if(mc.player.tickCount-depositAt>60)throw new IllegalStateException("Kit ender-chest deposit was not confirmed; inspect the chest before retrying");if((mc.player.tickCount-depositAt)%10==0)requestSync();return;}
            List<ItemStack> contents=mc.player.containerMenu.slots.stream().takeWhile(s->s.container!=mc.player.getInventory()).map(s->s.getItem()).toList();
            if(!mc.player.containerMenu.getCarried().isEmpty()||countCarried()!=serverCarried||!BotStashDeposit.sameContents(contents,serverContents)){if(++wait>100)throw new IllegalStateException("Ender-chest inventory did not reconcile with its server snapshot; no further kit was moved");if(wait%10==1)requestSync();return;}
            wait=0;
            if(inventory<0||empty<0)throw new IllegalStateException("Ender chest has no room for all requested kits; remaining boxes are carried");
            depositBefore=serverCarried;depositSlot=empty;depositContents=BotStashDeposit.copyContents(serverContents);depositStack=mc.player.getInventory().getItem(inventory).copy();depositAt=mc.player.tickCount;InvUtils.moveConfirmed(inventory,empty);return;
        }
        if(inventory<0||empty<0){finished=true;close();return;}InvUtils.shiftClick().slot(inventory);wait=0;
    }
    void inventory(InventoryEvent event){
        if(!openingRequested)return;
        if(mc.player.containerMenu!=mc.player.inventoryMenu&&event.packet.containerId()==mc.player.containerMenu.containerId&&event.packet.items().size()==mc.player.containerMenu.slots.size()){
            menu=event.packet.containerId();menuReady=true;
            int slots=0;while(slots<mc.player.containerMenu.slots.size()&&mc.player.containerMenu.getSlot(slots).container!=mc.player.getInventory())slots++;
            serverContents=BotStashDeposit.copyContents(event.packet.items().subList(0,slots));serverCarried=carriedIn(event);
        }
        if(withdrawSent>=0&&menu>=0&&event.packet.containerId()==menu&&withdrawSlot<event.packet.items().size()){
            JsonObject pick=picks.get(index).getAsJsonObject();int carried=carriedIn(event);
            if(!matches(event.packet.items().get(withdrawSlot),pick)){
                if(carried<withdrawBefore+1)throw new IllegalStateException("Source kit vanished without a server-confirmed inventory pickup");
                withdrawConfirmed=true;
            }
        }
        if(depositBefore<0||menu<0||event.packet.containerId()!=menu||depositSlot>=event.packet.items().size())return;
        int carried=carriedIn(event);
        if(carried==depositBefore)return;
        if(carried!=depositBefore-1||!event.packet.carriedItem().isEmpty()||depositSlot>=serverContents.size()||!depositContents.get(depositSlot).isEmpty()||!ItemStack.matches(serverContents.get(depositSlot),depositStack))return;
        deposited++;depositBefore=depositSlot=-1;depositContents=List.of();depositStack=ItemStack.EMPTY;
    }
    private int carriedIn(InventoryEvent event){int carried=0;for(int i=0;i<Math.min(event.packet.items().size(),mc.player.containerMenu.slots.size());i++)if(mc.player.containerMenu.getSlot(i).container==mc.player.getInventory()&&matchesAny(event.packet.items().get(i)))carried+=event.packet.items().get(i).getCount();return carried;}
    private int countCarried(){int count=0;for(int i=0;i<36;i++)if(matchesAny(mc.player.getInventory().getItem(i)))count+=mc.player.getInventory().getItem(i).getCount();return count;}
    private boolean matchesAny(ItemStack stack){if(action.has("kitTypeId"))return BotStashScan.matchesKit(stack,action);for(JsonElement e:picks)if(matches(stack,e.getAsJsonObject()))return true;return false;}
    private boolean matches(ItemStack stack,JsonObject pick){
        if(!(stack.getItem() instanceof BlockItem b)||!(b.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock))return false;
        if(pick.has("kitTypeId"))return pick.get("kitTypeId").equals(action.get("kitTypeId"))&&pick.get("incomplete").equals(action.get("incomplete"))&&BotStashScan.matchesKit(stack,action);
        JsonObject box=BotStashScan.classify(List.of(stack)).get(0).getAsJsonObject();
        return pick.get("resource").getAsString().equals(box.get("dominant").getAsString())&&!box.get("mixed").getAsBoolean();
    }
    private BlockPos findEchest(){return BlockPos.betweenClosedStream(mc.player.blockPosition().offset(-16,-4,-16),mc.player.blockPosition().offset(16,4,16)).filter(p->mc.level.getBlockEntity(p) instanceof EnderChestBlockEntity).min(Comparator.comparingDouble(p->Vec3.atCenterOf(p).distanceToSqr(mc.player.position()))).map(BlockPos::immutable).orElse(null);}
    private void requestSync(){mc.getConnection().send(HighwayBuilder.cursorSyncRequest(menu,HashedStack.create(mc.player.containerMenu.getCarried(),mc.getConnection().decoratedHashOpsGenenerator())));}
    void close(){if(mc.player!=null&&mc.player.containerMenu!=mc.player.inventoryMenu)mc.player.closeContainer();menu=-1;menuReady=openingRequested=false;serverContents=List.of();serverCarried=-1;}
}
