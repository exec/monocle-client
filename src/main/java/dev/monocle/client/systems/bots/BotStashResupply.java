package dev.monocle.client.systems.bots;

import com.google.gson.*;
import dev.monocle.client.events.packets.InventoryEvent;
import dev.monocle.client.events.packets.ContainerSlotUpdateEvent;
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

/** Server-confirmed stash-box withdrawal, optionally followed by ender-chest deposit. */
final class BotStashResupply {
    private final JsonObject action;private final JsonArray picks,receipt=new JsonArray();private int index,batchLimit,wait,menu=-1,withdrawSent=-1,withdrawSlot=-1,withdrawBefore,deposited,depositBefore=-1,depositSlot=-1,depositAt;private JsonObject withdrawnBox;private BlockPos target;private boolean depositing,finished,withdrawConfirmed,menuReady;
    private List<ItemStack> serverContents=List.of(),serverMenuContents=List.of(),depositContents=List.of();private ItemStack depositStack=ItemStack.EMPTY;private int serverCarried=-1;
    private int withdrawDestination=-1;private ItemStack withdrawStack=ItemStack.EMPTY;private JsonObject verification;
    private List<ItemStack> withdrawContents=List.of();private ItemStack serverCursor=ItemStack.EMPTY;
    private int withdrawInventoryStart=-1,withdrawRetries,withdrawRecoveryMenu=-1,serverAt=-1;
    private boolean withdrawReopening,withdrawHeldOnClose;
    private boolean withdrawSourceMayRefill,sourceUncertain,sourceBlocked;
    private int unavailableTicks,noProgressTicks;private double closest=Double.POSITIVE_INFINITY;private String waiting="",sourceIssue="";private boolean openingRequested;
    private BotStashScan.HopperColumn sourceColumn;
    private final Set<String> drainedSources=new LinkedHashSet<>();
    private final JsonArray deferredSources=new JsonArray();
    private int refillWait,sourceRefreshes;
    private int emptyOutletSince=-1;
    private boolean emptyOutletPending,recheckingOutlet;
    private final Map<String,Long> emptySources;
    private final String emptyScope;
    private boolean cachedEmptyUsed,recheckingEmptySources;
    private JsonArray personalBoxes,incidental=new JsonArray();
    private int discardSlot=-1,discardAt;
    private boolean inventoryFull,sourceAdvancePending;
    private BotStashBulk bulk;
    private boolean bulkDisabled;
    private int serverState=-1,snapshotReopens;
    BotStashResupply(JsonObject action){this(action,new LinkedHashMap<>(),"");}
    BotStashResupply(JsonObject action,Map<String,Long> emptySources,String scope){this.action=action;this.emptySources=emptySources;this.emptyScope=scope+"\n";this.picks=action.getAsJsonArray("picks");index=action.has("pickIndex")?action.get("pickIndex").getAsInt():0;}
    boolean done(){return finished&&mc.player.containerMenu==mc.player.inventoryMenu;}
    boolean canYieldInventory(){return bulk==null&&withdrawSent<0&&depositBefore<0&&discardSlot<0&&serverCursor.isEmpty()&&!sourceAdvancePending;}
    boolean personalBox(ItemStack stack){
        if(personalBoxes==null)return receipt.isEmpty();
        JsonArray box=BotStashScan.classify(List.of(stack));
        return !box.isEmpty()&&personalBoxes.asList().stream().anyMatch(p->boxKey(p.getAsJsonObject()).equals(boxKey(box.get(0).getAsJsonObject())));
    }
    boolean targetIsHopper(){return target!=null&&mc.level.getBlockEntity(target) instanceof HopperBlockEntity;}
    static boolean sourceMayRefill(net.minecraft.world.level.BlockGetter world,BlockPos target){
        if(world.getBlockState(target).getBlock() instanceof net.minecraft.world.level.block.HopperBlock)return true;
        // A sort leases one container at a time; that does not stop its physical hopper feeds.
        for(BlockPos half:BotStashScan.chestHalves(world,target))for(var direction:net.minecraft.core.Direction.values()){
            BlockPos neighbor=half.relative(direction);var state=world.getBlockState(neighbor);
            if(state.getBlock() instanceof net.minecraft.world.level.block.HopperBlock&&state.getValue(net.minecraft.world.level.block.HopperBlock.ENABLED)
                &&neighbor.relative(state.getValue(net.minecraft.world.level.block.HopperBlock.FACING)).equals(half))return true;
        }
        return false;
    }
    BlockPos navigationTarget(){return BotStashScan.navigationTarget(target);}
    boolean approaching(){return bulk==null&&withdrawSent<0&&depositBefore<0&&target!=null&&!finished&&(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target))>20.25||!targetIsHopper()&&!BotStashScan.visibleFrom(mc.player.getEyePosition(),target));}
    BlockPos target(){return target;}
    boolean hasWithdrawal(){return !receipt.isEmpty();}
    dev.monocle.coordinator.ActionProgress.Observation progress() {
        boolean moving = approaching() && discardSlot < 0;
        return BotActions.progress(depositing ? "Ender-chest stocking" : "Stash withdrawal", moving ? "Reach source container" : withdrawSent >= 0 || depositBefore >= 0 || bulk != null || discardSlot >= 0 ? "Server inventory confirmation" : refillWait > 0 ? "Hopper refill" : "Container contents",
            receipt.size() + "/" + deposited + "/" + drainedSources.size(), target, false, moving);
    }
    JsonObject result(){JsonObject r=new JsonObject();r.addProperty("stash",action.get("name").getAsString());if(action.has("catalogCrew"))r.add("catalogCrew",action.get("catalogCrew").deepCopy());r.add("withdrawn",receipt.deepCopy());if(action.has("kitTypeId")){r.addProperty("picked",receipt.size());r.addProperty("nextPick",index);r.addProperty("sourceExhausted",sourceIssue.isEmpty()&&deferredSources.isEmpty()&&index>=picks.size()&&!(cachedEmptyUsed&&receipt.isEmpty()));if(!deferredSources.isEmpty()){r.addProperty("sourceDeferred",true);r.add("deferredSources",deferredSources.deepCopy());}if(!sourceIssue.isEmpty())r.addProperty("sourceIssue",sourceIssue);}return r;}
    JsonObject workflowResult(){
        JsonObject r=result();if(action.has("kitTypeId"))r.remove("withdrawn");
        if(dev.monocle.coordinator.TaskWire.flag(action,"mixedLoad")){
            r.addProperty("batchCapacity",batchLimit);
            r.add("personalBoxes",personalBoxes==null?new JsonArray():personalBoxes.deepCopy());
        }
        if(!incidental.isEmpty())r.add("incidental",incidental.deepCopy());
        if(!incidental.isEmpty()&&personalBoxes!=null){
            JsonArray protectedBoxes=new JsonArray();personalBoxes.forEach(e->{if(incidental.asList().stream().anyMatch(extra->dev.monocle.coordinator.KitTypes.matches(e.getAsJsonObject(),extra.getAsJsonObject())))protectedBoxes.add(e.deepCopy());});
            r.add("protectedBoxes",protectedBoxes);
        }
        if(inventoryFull)r.addProperty("inventoryFull",true);
        // ponytail: eight diagnostic samples fit the 32 KiB Lua envelope; checkpoints/logs retain every source.
        if(r.has("deferredSources")){JsonArray notes=new JsonArray();deferredSources.asList().stream().limit(8).forEach(notes::add);r.add("deferredSources",notes);}
        return r;
    }
    private boolean handlesIncidental(){return action.has("knownKitTypeIds")&&action.has("targetStash");}
    static String boxKey(JsonObject box){return box.get("item")+"/"+box.get("name")+"/"+box.get("items");}
    static JsonArray newBoxes(JsonArray personal,JsonArray current,String requested){
        Map<String,Integer> remaining=new HashMap<>();
        for(JsonElement e:personal){JsonObject box=e.getAsJsonObject();remaining.merge(boxKey(box),box.get("quantity").getAsInt(),Integer::sum);}
        JsonArray result=new JsonArray();
        for(JsonElement e:current){
            JsonObject box=e.getAsJsonObject();String key=boxKey(box);int count=box.get("quantity").getAsInt(),protectedCount=Math.min(count,remaining.getOrDefault(key,0));
            remaining.put(key,remaining.getOrDefault(key,0)-protectedCount);
            if(count>protectedCount&&!dev.monocle.coordinator.StashCatalog.kitTypeId(box).equals(requested)){
                JsonObject extra=box.deepCopy();extra.addProperty("quantity",count-protectedCount);result.add(extra);
            }
        }
        return result;
    }
    private boolean handleIncidental(){
        if(!handlesIncidental()||personalBoxes==null)return false;
        if(discardSlot>=0){
            if(serverAt>=discardAt&&serverCursor.isEmpty()&&serverMenuContents.get(discardSlot).isEmpty()){discardSlot=-1;wait=0;}
            else{waiting="Verifying incidental kit discard";if(mc.player.tickCount-discardAt>100)throw new IllegalStateException("Incidental discard was not confirmed; no discard was replayed");if((mc.player.tickCount-discardAt)%10==0)requestSync();return true;}
        }
        Set<String> known=new HashSet<>();action.getAsJsonArray("knownKitTypeIds").forEach(id->known.add(id.getAsString()));
        List<ItemStack> inventory=serverMenuContents.subList(serverContents.size(),serverMenuContents.size());
        JsonArray extras=newBoxes(personalBoxes,BotStashScan.classify(inventory,action),action.get("kitTypeId").getAsString());
        incidental=new JsonArray();
        for(JsonElement e:extras){
            JsonObject box=e.getAsJsonObject();String id=dev.monocle.coordinator.StashCatalog.kitTypeId(box);
            if(!known.contains(id)&&known.contains(dev.monocle.coordinator.KitTypes.family(box))){id=dev.monocle.coordinator.KitTypes.family(box);box.addProperty("kitTypeId",id);}
            if(!known.contains(id)){
                int slot=serverContents.size()+box.get("slot").getAsInt();
                // A partially protected illegal stacked shulker must not discard a personal box with it.
                if(box.get("quantity").getAsInt()!=serverMenuContents.get(slot).getCount())continue;
                discardSlot=slot;discardAt=mc.player.tickCount;
                dev.monocle.client.MonocleClient.LOG.info("[Workers] Discarding newly acquired unrecognized kit: {}",box);
                InvUtils.dropSlotConfirmed(slot);requestSync();return true;
            }
            JsonObject entry=null;for(JsonElement existing:incidental)if(existing.getAsJsonObject().get("kitTypeId").getAsString().equals(id)){entry=existing.getAsJsonObject();break;}
            if(entry==null){entry=new JsonObject();entry.addProperty("kitTypeId",id);entry.add("kitExemplar",box.get("items").deepCopy());entry.addProperty("includeIncomplete",true);entry.addProperty("incomplete",false);entry.addProperty("count",0);incidental.add(entry);}
            entry.addProperty("count",entry.get("count").getAsInt()+box.get("quantity").getAsInt());
        }
        return false;
    }
    JsonObject snapshot(){JsonObject s=new JsonObject();s.addProperty("index",index);s.addProperty("batchLimit",batchLimit);s.addProperty("depositing",depositing);s.addProperty("deposited",deposited);s.addProperty("withdrawPending",withdrawSent>=0||bulk!=null&&!depositing);s.addProperty("withdrawConfirmed",withdrawConfirmed);s.addProperty("depositPending",depositBefore>=0||bulk!=null&&depositing);s.addProperty("bulkDisabled",bulkDisabled);s.addProperty("discardPending",discardSlot>=0);s.addProperty("inventoryFull",inventoryFull);if(personalBoxes!=null)s.add("personalBoxes",personalBoxes.deepCopy());s.add("incidental",incidental.deepCopy());s.addProperty("finished",finished);s.addProperty("sourceIssue",sourceIssue);s.addProperty("sourceRefreshes",sourceRefreshes);s.addProperty("sourceUncertain",sourceUncertain);s.addProperty("sourceBlocked",sourceBlocked);s.addProperty("cachedEmptyUsed",cachedEmptyUsed);s.addProperty("recheckingEmptySources",recheckingEmptySources);s.addProperty("emptyOutletPending",emptyOutletPending);s.addProperty("recheckingOutlet",recheckingOutlet);JsonArray drained=new JsonArray();drainedSources.forEach(drained::add);s.add("drainedSources",drained);s.add("receipt",receipt.deepCopy());s.add("deferredSources",deferredSources.deepCopy());if(verification!=null)s.add("verification",verification.deepCopy());return s;}
    void restore(JsonObject s){
        if(s.has("withdrawPending")&&s.get("withdrawPending").getAsBoolean()||s.has("depositPending")&&s.get("depositPending").getAsBoolean()||s.has("discardPending")&&s.get("discardPending").getAsBoolean())throw new IllegalStateException("Stash transfer was interrupted during an unconfirmed inventory move; inspect the containers before retrying");
        bulkDisabled=s.has("bulkDisabled")&&s.get("bulkDisabled").getAsBoolean();
        if(s.has("personalBoxes")){personalBoxes=s.getAsJsonArray("personalBoxes").deepCopy();if(personalBoxes.size()>36)throw new IllegalArgumentException("Too many personal shulkers");}
        if(s.has("incidental")){incidental=s.getAsJsonArray("incidental").deepCopy();if(incidental.size()>36)throw new IllegalArgumentException("Too many incidental kits");}
        inventoryFull=s.has("inventoryFull")&&s.get("inventoryFull").getAsBoolean();
        index=s.get("index").getAsInt();batchLimit=s.has("batchLimit")?s.get("batchLimit").getAsInt():action.has("kitTypeId")?action.get("count").getAsInt():0;
        depositing=s.get("depositing").getAsBoolean();deposited=s.has("deposited")?s.get("deposited").getAsInt():0;finished=s.has("finished")&&s.get("finished").getAsBoolean();receipt.addAll(s.getAsJsonArray("receipt"));
        sourceIssue=s.has("sourceIssue")?s.get("sourceIssue").getAsString():"";
        sourceRefreshes=s.has("sourceRefreshes")?s.get("sourceRefreshes").getAsInt():0;
        sourceUncertain=s.has("sourceUncertain")&&s.get("sourceUncertain").getAsBoolean();
        sourceBlocked=s.has("sourceBlocked")&&s.get("sourceBlocked").getAsBoolean();
        cachedEmptyUsed=s.has("cachedEmptyUsed")&&s.get("cachedEmptyUsed").getAsBoolean();
        recheckingEmptySources=s.has("recheckingEmptySources")&&s.get("recheckingEmptySources").getAsBoolean();
        emptyOutletPending=s.has("emptyOutletPending")&&s.get("emptyOutletPending").getAsBoolean();
        recheckingOutlet=s.has("recheckingOutlet")&&s.get("recheckingOutlet").getAsBoolean();
        emptyOutletSince=-1; // A reconnect starts a conservative new propagation window, not an old player tick clock.
        if(recheckingOutlet&&!emptyOutletPending||emptyOutletPending&&(!canInspectRefillingColumn()||index>=picks.size()||depositing))throw new IllegalArgumentException("Invalid empty-outlet verification checkpoint");
        if(s.has("drainedSources")){
            JsonArray drained=s.getAsJsonArray("drainedSources");
            if(drained.size()>4096)throw new IllegalArgumentException("Too many checked stash sources");
            for(JsonElement entry:drained){
                if(!entry.isJsonPrimitive()||!entry.getAsJsonPrimitive().isString()||entry.getAsString().length()>512)throw new IllegalArgumentException("Invalid checked stash source");
                String key=entry.getAsString();int split=key.indexOf(':');
                if(split<1)throw new IllegalArgumentException("Invalid checked stash source");
                BlockPos position=BlockPos.of(Long.parseLong(key.substring(0,split)));String resource=key.substring(split+1);
                if(!key.equals(position.asLong()+":"+resource)||picks.asList().stream().noneMatch(p->p.getAsJsonObject().get("resource").getAsString().equals(resource))
                    ||action.has("minX")&&!dev.monocle.coordinator.StashCatalog.contains(action,position.getX(),position.getY(),position.getZ())||!drainedSources.add(key))throw new IllegalArgumentException("Invalid checked stash source");
            }
        }
        if(s.has("deferredSources")){
            JsonArray deferred=s.getAsJsonArray("deferredSources");
            if(deferred.size()>picks.size())throw new IllegalArgumentException("Too many deferred stash sources");
            for(JsonElement note:deferred)if(!note.isJsonPrimitive()||!note.getAsJsonPrimitive().isString()||note.getAsString().length()>512)throw new IllegalArgumentException("Invalid deferred stash source");
            deferredSources.addAll(deferred.deepCopy());
        }
        if(index<0||index>picks.size()||batchLimit<0||batchLimit>36||receipt.size()>wanted()||deposited<0||deposited>wanted()||sourceRefreshes<0||sourceRefreshes>3||sourceIssue.length()>1024||!sourceIssue.isEmpty()&&(!finished||receipt.isEmpty()))throw new IllegalArgumentException("Invalid stash resupply checkpoint");
    }
    private int wanted(){return action.has("kitTypeId")?batchLimit>0?batchLimit:action.get("count").getAsInt():picks.size();}
    private boolean transferAll(){return action.has("transferAll")&&action.get("transferAll").getAsBoolean();}
    static boolean sourceContainer(BlockEntity entity){return entity instanceof BaseContainerBlockEntity;}
    /** Recover only known pickups. An uncertain click must never become a retry or a partial success. */
    boolean recoverSourceIssue(String issue){
        if(bulk!=null||withdrawSent>=0||depositBefore>=0||depositing||!action.has("kitTypeId")||!action.has("depositMode")||!action.get("depositMode").getAsString().equals("Carry")||receipt.isEmpty())return false;
        sourceIssue=issue;finished=true;return true;
    }
    private void sourceProblem(String issue){
        String reason=issue+" · "+action.get("name").getAsString()+" · source "+(target==null?index:target.toShortString())+" · picked "+receipt.size()+"/"+wanted();
        if(recoverSourceIssue(reason))return;throw new IllegalStateException(reason);
    }
    boolean deferSourceApproach(String issue){
        if(!transferAll()||!action.has("kitTypeId")||!action.has("depositMode")||!action.get("depositMode").getAsString().equals("Carry")
            ||action.has("requireLocal")&&action.get("requireLocal").getAsBoolean()||target==null
            ||bulk!=null||withdrawSent>=0||depositBefore>=0||discardSlot>=0||depositing||!serverCursor.isEmpty())return false;
        recordDeferredSource(target,List.of(),issue+"; contents unread, source unresolved");
        advanceSourcePick();return true;
    }
    private void sourceApproachProblem(String issue){
        if(mc.player.containerMenu.getCarried().isEmpty()&&deferSourceApproach(issue)){
            dev.monocle.client.MonocleClient.LOG.warn("[Workers] {} · stash {} · continuing through other sources",deferredSources.get(deferredSources.size()-1),action.get("name"));
            close();return;
        }
        sourceProblem(issue);
    }
    private void nextSource(){advanceSourcePick();close();}
    private void advanceSourcePick(){index++;target=null;sourceColumn=null;sourceUncertain=sourceBlocked=false;withdrawRetries=snapshotReopens=0;refillWait=sourceRefreshes=0;wait=unavailableTicks=noProgressTicks=0;closest=Double.POSITIVE_INFINITY;waiting="";resetEmptyOutletCheck();}
    static boolean missingSourceBlock(net.minecraft.world.level.block.state.BlockState state){return !state.hasBlockEntity();}
    void skipMissingSource(String block){
        if(target==null||bulk!=null||withdrawSent>=0||depositBefore>=0||discardSlot>=0||depositing||!serverCursor.isEmpty())
            throw new IllegalStateException("Cannot skip a missing stash source during an unconfirmed inventory move");
        BlockPos missing=target;
        rememberEmptySource(missing,System.currentTimeMillis());
        if(sourceColumn==null||!advanceSourceChest())advanceSourcePick();
        waiting="Skipped missing source at "+missing.toShortString()+" ("+block+"); continuing";
    }
    private String sourceKey(BlockPos position){return position.asLong()+":"+picks.get(index).getAsJsonObject().get("resource").getAsString();}
    BlockPos nextSourceChest(BotStashScan.HopperColumn column){
        long now=System.currentTimeMillis();
        return column.chests().stream().filter(p->!drainedSources.contains(sourceKey(p))&&!skipKnownEmpty(p,now)).findFirst().orElse(null);
    }
    boolean sourceKnownEmpty(BlockPos position,long now){
        String key=emptyScope+position.asLong();Long until=emptySources.get(key);
        if(until!=null&&until>now)return true;
        emptySources.remove(key);return false;
    }
    void rememberEmptySource(BlockPos position,long now){
        if(action.has("sortBatch"))return;
        String key=emptyScope+position.asLong();
        // shortcut: remember 4096 physical empties for 30 minutes; a zero-result search rechecks them before exhaustion.
        if(emptySources.size()>=4096&&!emptySources.containsKey(key))emptySources.remove(emptySources.keySet().iterator().next());
        emptySources.put(key,now+30*60_000L);
    }
    private boolean skipKnownEmpty(BlockPos position,long now){
        if(action.has("sortBatch"))return false;
        if(recheckingEmptySources||sourceUncertain||sourceBlocked||action.has("requireLocal")&&action.get("requireLocal").getAsBoolean()||!sourceKnownEmpty(position,now))return false;
        cachedEmptyUsed=true;return true;
    }
    private void rememberSource(BlockPos position){
        // shortcut: retain 4096 checked sources; larger actions may reread old columns until cursors replace this cache.
        if(drainedSources.size()>=4096&&!drainedSources.contains(sourceKey(position)))drainedSources.remove(drainedSources.iterator().next());
        drainedSources.add(sourceKey(position));
    }
    boolean emptySourceColumn(List<ItemStack> contents){
        return sourceColumn!=null&&sourceColumn.fed()&&sourceColumn.bottom().equals(target)&&!sourceUncertain&&!sourceBlocked
            &&!(action.has("requireLocal")&&action.get("requireLocal").getAsBoolean())&&contents.stream().allMatch(ItemStack::isEmpty);
    }
    static int emptySourceRefillTicks(BotStashScan.HopperColumn column,double tps){
        return (int)Math.ceil(column.refillTicks()*20/(Double.isFinite(tps)&&tps>0?Math.max(1,Math.min(20,tps)):20));
    }
    private boolean canInspectRefillingColumn(){return transferAll()&&action.has("kitTypeId")&&action.has("depositMode")&&action.get("depositMode").getAsString().equals("Carry")&&!(action.has("requireLocal")&&action.get("requireLocal").getAsBoolean());}
    private void resetEmptyOutletCheck(){emptyOutletSince=-1;emptyOutletPending=recheckingOutlet=false;}
    int sourceRefillBudget(List<ItemStack> contents,int tick,double tps){
        if(!emptyOutletPending&&canInspectRefillingColumn()&&emptySourceColumn(contents)){emptyOutletPending=true;emptyOutletSince=tick;}
        if(emptyOutletPending){
            if(emptyOutletSince<0||tick<emptyOutletSince)emptyOutletSince=tick;
            if(!target.equals(sourceColumn.bottom()))return 0;
            if(!recheckingOutlet)return sourceRefillTicks(sourceColumn,contents);
            refillWait=Math.max(refillWait,tick-emptyOutletSince);
        }
        if(canInspectRefillingColumn()&&!target.equals(sourceColumn.bottom()))return 0;
        return emptySourceColumn(contents)?emptySourceRefillTicks(sourceColumn,tps):sourceRefillTicks(sourceColumn,contents);
    }
    boolean recheckEmptyOutlet(){
        if(!emptyOutletPending||recheckingOutlet||sourceColumn==null)return false;
        if(bulk!=null||withdrawSent>=0||depositBefore>=0||discardSlot>=0||depositing||!serverCursor.isEmpty())throw new IllegalStateException("Cannot recheck an outlet during an unconfirmed inventory move");
        target=sourceColumn.bottom();recheckingOutlet=true;refillWait=wait=unavailableTicks=noProgressTicks=0;closest=Double.POSITIVE_INFINITY;
        waiting="Rechecking emptied hopper outlet after upper chests at "+target.toShortString();return true;
    }
    boolean chooseSourceChest(){
        if(recheckingOutlet)target=sourceColumn.bottom();
        else{target=nextSourceChest(sourceColumn);if(target==null&&!recheckEmptyOutlet())return false;}
        return true;
    }
    boolean skipEmptySourceColumn(List<ItemStack> contents,int refillTicks){
        if(bulk!=null||withdrawSent>=0||depositBefore>=0||depositing)throw new IllegalStateException("Cannot skip a stash source during an unconfirmed inventory move");
        if(emptyOutletPending&&!recheckingOutlet||!emptySourceColumn(contents)||refillTicks<sourceColumn.refillTicks()||refillWait<=refillTicks)return false;
        // shortcut: a stable empty outlet implies drainage after propagation; revisit it on the workflow's next empty-verification pass.
        long now=System.currentTimeMillis();sourceColumn.chests().forEach(p->{rememberSource(p);rememberEmptySource(p,now);});return true;
    }
    boolean advanceSourceChest(){
        if(bulk!=null||withdrawSent>=0||depositBefore>=0||depositing)throw new IllegalStateException("Cannot advance a stash source during an unconfirmed inventory move");
        if(action.has("requireLocal")&&action.get("requireLocal").getAsBoolean())return false;
        rememberSource(target);BlockPos next=nextSourceChest(sourceColumn);
        if(next==null)return false;
        target=next;withdrawRetries=0;refillWait=wait=unavailableTicks=noProgressTicks=0;closest=Double.POSITIVE_INFINITY;
        waiting="Checking next chest above exhausted source at "+target.toShortString();return true;
    }
    static JsonObject sourcePick(JsonObject pick,BlockPos target,boolean fed){
        if(!fed&&pickPosition(pick).equals(target))return pick;
        JsonObject actual=pick.deepCopy();actual.addProperty("dynamic",true);actual.addProperty("x",target.getX());actual.addProperty("y",target.getY());actual.addProperty("z",target.getZ());return actual;
    }
    boolean waitingForRefill(int ticks){return ++refillWait<=ticks;}
    static int sourceRefillTicks(BotStashScan.HopperColumn column,List<ItemStack> contents){return contents.stream().anyMatch(s->!s.isEmpty())?0:Math.min(16,column.refillTicks());}
    boolean refreshSourceColumn(BotStashScan.HopperColumn current){
        if(bulk!=null||withdrawSent>=0||depositBefore>=0||depositing)throw new IllegalStateException("Cannot refresh a stash source during an unconfirmed inventory move");
        if(sourceRefreshes>=3||action.has("requireLocal")&&action.get("requireLocal").getAsBoolean()&&!current.bottom().equals(target))return false;
        // A disconnected or unloaded upstream chest is unresolved, not proof that the source is empty.
        sourceUncertain|=sourceColumn!=null&&!current.chests().containsAll(sourceColumn.chests());
        BlockPos next=target!=null&&current.chests().contains(target)?target:nextSourceChest(current);if(next==null)return false;
        sourceRefreshes++;sourceColumn=current;target=next;refillWait=wait=unavailableTicks=noProgressTicks=0;closest=Double.POSITIVE_INFINITY;
        if(emptyOutletPending){emptyOutletSince=-1;recheckingOutlet=false;}
        waiting="Rechecking changed hopper connection ("+sourceRefreshes+"/3) at "+target.toShortString();return true;
    }
    String recordDeferredSource(BlockPos position,List<ItemStack> contents){
        return recordDeferredSource(position,contents,"");
    }
    String recordDeferredSource(BlockPos position,List<ItemStack> contents,String reason){
        String expected=action.has("kitTypeId")?action.get("kitTypeId").getAsString():picks.get(index).getAsJsonObject().get("resource").getAsString();
        Map<String,Integer> observed=new LinkedHashMap<>();
        for(ItemStack stack:contents)if(!stack.isEmpty()&&(!(stack.getItem() instanceof BlockItem b)||!(b.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock)))observed.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getCount(),Integer::sum);
        JsonArray boxes=BotStashScan.classify(contents,action);
        for(JsonElement e:boxes){JsonObject box=e.getAsJsonObject();String label=box.get("item").getAsString()+"/"+box.get("name").getAsString()+"/"+dev.monocle.coordinator.StashCatalog.kitTypeId(box);observed.merge(label,box.get("quantity").getAsInt(),Integer::sum);}
        String note=(reason.isEmpty()?"":reason+" · ")+"Outlet "+position.toShortString()+" expects "+expected+"; observed "+observed;
        deferredSources.add(note.substring(0,Math.min(512,note.length())));
        return note;
    }
    boolean approachTimedOut(double distance){
        if(Double.isFinite(distance)&&(!Double.isFinite(closest)||distance<closest-.25)){closest=distance;noProgressTicks=0;}
        return ++noProgressTicks>200;
    }
    boolean approachNeedsFlight(){return !depositing&&noProgressTicks>=60;}
    JsonObject approachDiagnostic(){
        JsonObject diagnostic=new JsonObject();diagnostic.addProperty("idleTicks",noProgressTicks);
        if(Double.isFinite(closest))diagnostic.addProperty("closestDistance",closest);
        if(target!=null)diagnostic.addProperty("target",target.toShortString());
        return diagnostic;
    }
    boolean finishSourceSearch(){
        if(depositing||receipt.size()>=wanted()||index<picks.size())return false;
        if(cachedEmptyUsed&&receipt.isEmpty()&&deferredSources.isEmpty()&&!recheckingEmptySources){
            recheckingEmptySources=true;cachedEmptyUsed=false;drainedSources.clear();
            index=action.has("pickIndex")?action.get("pickIndex").getAsInt():0;
            waiting="Verifying cached empty sources before reporting exhaustion";return false;
        }
        if(!transferAll()){sourceProblem("Mapped sources did not contain enough matching kits");return finished;}
        finished=true;return true;
    }
    static int batchSize(int requested,int free,boolean carry){if(free<1)throw new IllegalStateException("Kit pickup needs at least one empty inventory slot");if(carry&&free<requested)throw new IllegalStateException("Carry destination needs "+requested+" empty inventory slots; only "+free+" are free");return Math.min(requested,free);}
    boolean finishFullBatch(){
        if(!action.has("kitTypeId"))return false;
        if(!receipt.isEmpty()){batchLimit=receipt.size();inventoryFull=true;return true;}
        if(handlesIncidental()&&!incidental.isEmpty()){inventoryFull=true;finished=true;return true;}
        return false;
    }
    private static BlockPos pickPosition(JsonObject pick){return new BlockPos(pick.get("x").getAsInt(),pick.get("y").getAsInt(),pick.get("z").getAsInt());}
    static boolean keepSourceOpen(JsonArray picks,int index,BlockPos target,int collected,int wanted){return collected<wanted&&index<picks.size()&&pickPosition(picks.get(index).getAsJsonObject()).equals(target);}
    String detail(){return bulk!=null?"Verifying stash burst "+bulk.verified+"/"+bulk.moves.size()+" · "+(bulk.quick?"shift-click":"exact-slot")+" transfers":!waiting.isEmpty()?waiting:depositing?"Filling ender chest from stash supplies":"Collecting stash shulker "+Math.min(receipt.size()+1,wanted())+"/"+wanted()+(target==null?"":" · "+target.toShortString());}
    void tick(){
        if(done())return;if(finished){close();return;}
        if(bulk!=null){settleBulk();return;}
        if(mc.player.containerMenu.containerId!=menu){
            if(discardSlot>=0&&mc.player.tickCount-discardAt>100)throw new IllegalStateException("Incidental discard was not confirmed; no discard was replayed");
            if(depositBefore>=0&&mc.player.tickCount-depositAt>100)throw new IllegalStateException("Ender-chest deposit was not confirmed; no uncertain move replayed");
        }
        if(action.has("kitTypeId")&&batchLimit==0){
            int free=0;for(int i=0;i<36;i++)if(mc.player.getInventory().getItem(i).isEmpty())free++;
            if(countCarried()>0)throw new IllegalStateException("Move matching kits out of carried inventory before this job so personal boxes cannot be delivered");
            batchLimit=batchSize(action.get("count").getAsInt(),free,action.has("destination")&&action.get("destination").getAsString().equals("Carry"));
            if(handlesIncidental())personalBoxes=BotStashScan.classify(java.util.stream.IntStream.range(0,36).mapToObj(i->mc.player.getInventory().getItem(i)).toList(),action);
        }
        // Consume an authoritative pickup receipt before checking whether its container changed.
        if(withdrawSent>=0&&withdrawConfirmed){withdraw();return;}
        if(withdrawSent>=0){settleWithdrawal();return;}
        int previousMenu=menu,currentMenu=mc.player.containerMenu.containerId;
        if(resetClosedMenu(currentMenu,mc.player.containerMenu.getCarried().isEmpty())){
            if(mc.player.containerMenu!=mc.player.inventoryMenu)mc.player.closeContainer();
            dev.monocle.client.MonocleClient.LOG.info("[Workers] Source menu {} closed/replaced by {}; reopening {} with fresh contents",previousMenu,currentMenu,target);
            waiting="Source menu closed; reopening with fresh contents";return;
        }
        if(handlesIncidental()&&menuReady&&!serverMenuContents.isEmpty()&&mc.player.containerMenu.containerId==menu&&mc.player.containerMenu.getCarried().isEmpty()
            &&BotStashDeposit.sameContents(mc.player.containerMenu.slots.stream().map(s->s.getItem()).toList(),serverMenuContents)&&handleIncidental())return;
        if(sourceAdvancePending){sourceAdvancePending=false;target=null;sourceColumn=null;close();return;}
        if(finishSourceSearch()){close();return;}
        if(!depositing&&receipt.size()>=wanted()){close();if(action.has("depositMode")&&action.get("depositMode").getAsString().equals("Carry")){finished=true;return;}depositing=true;target=findEchest();wait=0;if(target==null)throw new IllegalStateException("Put an accessible ender chest within 16 blocks of the mapped stash");}
        if(done())return;
        if(target==null)target=pickPosition(picks.get(index).getAsJsonObject());
        if(!mc.level.getChunkSource().hasChunk(target.getX()>>4,target.getZ()>>4)){
            waiting="Waiting for source chunk at "+target.toShortString();if(approachTimedOut(mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(target))))sourceApproachProblem("Source chunk was not received and approach made no progress for 200 ticks");return;
        }
        if(!depositing&&sourceColumn==null){
            BotStashScan.verifySortGeometry(mc.level,target,action);
            sourceColumn=action.has("sortBatch")?new BotStashScan.HopperColumn(List.of(target)):BotStashScan.hopperColumn(mc.level,target,action);
            // Legacy crew plans lease individual containers, not their connected outlet inventories.
            if(action.has("requireLocal")&&action.get("requireLocal").getAsBoolean()&&!target.equals(sourceColumn.bottom()))
                throw new IllegalStateException("Crew source plan splits a hopper column; use a solo transfer until whole-column source leases are available");
            if(!chooseSourceChest()){nextSource();return;}
        }
        if(!depositing&&!sourceContainer(mc.level.getBlockEntity(target))){
            var state=mc.level.getBlockState(target);String block=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            waiting="Rechecking source "+target.toShortString()+" ("+block+")";
            if(++unavailableTicks>40){
                if(missingSourceBlock(state)&&mc.player.containerMenu.getCarried().isEmpty()){
                    skipMissingSource(block);
                    dev.monocle.client.MonocleClient.LOG.info("[Workers] {} · stash {} · {} confirmed pickups",waiting,action.get("name"),receipt.size());
                    close();
                }
                else sourceProblem("Expected a storage container, found "+block);
            }return;
        }
        unavailableTicks=0;waiting="";
        if(approaching()){
            if(approachTimedOut(mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(target))))sourceApproachProblem("No useful approach progress after 200 ticks");return;
        }
        noProgressTicks=0;
        if(mc.player.containerMenu==mc.player.inventoryMenu){if(++wait>200){sourceApproachProblem("Container did not open after 200 ticks");return;}if(wait%20==1)open();return;}
        if(!openingRequested){waiting="Waiting for another container to close before opening "+target.toShortString();if(++wait>200)sourceProblem("Another container prevented source opening");return;}
        if(menu<0)menu=mc.player.containerMenu.containerId;
        if(mc.player.containerMenu.containerId!=menu){
            waiting="Source menu changed; waiting for cursor/inventory reconciliation";
            if(++wait>200)throw new IllegalStateException("Source menu changed before inventory reconciled; no uncertain move replayed");return;
        }
        if(!menuReady){if(++wait>200){sourceApproachProblem("Container did not send contents after 200 ticks");return;}if(wait%10==0)requestSync();return;}
        if(depositing)deposit();else withdraw();
    }
    private void open(){
        openingRequested=true;
        BotStashScan.openContainer(target);
    }
    private void withdraw(){
        JsonObject pick=sourcePick(picks.get(index).getAsJsonObject(),target,sourceColumn.fed());boolean dynamic=pick.has("dynamic")&&pick.get("dynamic").getAsBoolean();boolean repeat=dynamic&&action.has("kitTypeId");dynamic|=withdrawRetries>0;
        if(withdrawSent>=0){
            if(withdrawConfirmed){JsonObject actual=pick.deepCopy();actual.addProperty("slot",withdrawSlot);actual.add("items",withdrawnBox.get("items").deepCopy());receipt.add(actual);withdrawSent=withdrawSlot=withdrawDestination=-1;withdrawnBox=null;withdrawStack=ItemStack.EMPTY;withdrawConfirmed=withdrawReopening=withdrawHeldOnClose=false;withdrawContents=List.of();withdrawInventoryStart=-1;withdrawRetries=0;verification=null;
                resetEmptyOutletCheck();
                refillWait=sourceRefreshes=0;if(!repeat)index++;if(!(repeat&&receipt.size()<wanted())&&!keepSourceOpen(picks,index,target,receipt.size(),wanted())){
                    if(handlesIncidental())sourceAdvancePending=receipt.size()<wanted();
                    else{target=null;sourceColumn=null;close();wait=unavailableTicks=noProgressTicks=0;closest=Double.POSITIVE_INFINITY;}
                }return;}
            settleWithdrawal();return;
        }
        List<ItemStack> contents=mc.player.containerMenu.slots.stream().takeWhile(s->s.container!=mc.player.getInventory()).map(s->s.getItem()).toList();
        if(!mc.player.containerMenu.getCarried().isEmpty()||countCarried()!=serverCarried||!BotStashDeposit.sameContents(mc.player.containerMenu.slots.stream().map(s->s.getItem()).toList(),serverMenuContents)){
            waiting="Reconciling source inventory before the next pickup";
            if(BotStashDeposit.reopenIdleSnapshot(++wait,snapshotReopens,bulk!=null||discardSlot>=0||depositBefore>=0,serverCursor,mc.player.containerMenu.getCarried())){
                snapshotReopens++;
                dev.monocle.client.MonocleClient.LOG.warn("[Workers] Refreshing idle source snapshot at {} ({}/2): slots={}, carried={}/{}, state={}, age={}",target,snapshotReopens,BotStashDeposit.mismatchedSlots(mc.player.containerMenu.slots.stream().map(s->s.getItem()).toList(),serverMenuContents),countCarried(),serverCarried,serverState,mc.player.tickCount-serverAt);
                close();wait=0;return;
            }
            if(wait>100){
                if(discardSlot>=0||!serverCursor.isEmpty()||!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("Source inventory did not reconcile with its server snapshot; cursor/discard needs inspection");
                sourceApproachProblem("Source inventory did not reconcile with its server snapshot; no further kit was moved");return;
            }if(wait%10==1)requestSync();return;
        }
        wait=0;snapshotReopens=0;
        if(handleIncidental())return;
        if(contents.stream().anyMatch(s->!s.isEmpty()))emptySources.remove(emptyScope+target.asLong());
        int sourceSlot=dynamic?-1:pick.get("slot").getAsInt();
        if(dynamic){for(int i=0;i<mc.player.containerMenu.slots.size();i++){var slot=mc.player.containerMenu.getSlot(i);if(slot.container==mc.player.getInventory())break;if(matches(slot.getItem(),pick)){sourceSlot=i;break;}}}
        if(sourceSlot<0){
            if(sourceColumn.fed()){
                int refillTicks=sourceRefillBudget(contents,mc.player.tickCount,dev.monocle.client.utils.world.TickRate.INSTANCE.getTickRate());
                if(waitingForRefill(refillTicks)){
                    waiting="Waiting briefly for hopper refill at chest "+target.toShortString()+" ("+refillWait+"/"+refillTicks+" ticks)";
                    if(refillWait%10==1)requestSync();return;
                }
                var current=BotStashScan.hopperColumn(mc.level,target,action);
                if(!sourceColumn.equals(current)){
                    dev.monocle.client.MonocleClient.LOG.info("[Workers] Stash source {} hopper connection changed: {} -> {} (refresh {}/3)",action.get("name").getAsString(),sourceColumn.chests(),current.chests(),sourceRefreshes);
                    if(refreshSourceColumn(current)){close();return;}
                    if(!action.has("kitTypeId")||action.has("requireLocal")&&action.get("requireLocal").getAsBoolean()){sourceProblem("Hopper connection did not stabilize after rechecking");return;}
                    dev.monocle.client.MonocleClient.LOG.info("[Workers] Deferred stash source {}: {}",action.get("name").getAsString(),recordDeferredSource(target,contents,"Hopper connection did not stabilize after 3 rechecks"));
                    nextSource();return;
                }
                if(skipEmptySourceColumn(contents,refillTicks)){
                    dev.monocle.client.MonocleClient.LOG.info("[Workers] Stash source {} bottom {} remained empty after {} refill ticks; skipping {} connected chests for this pass",action.get("name").getAsString(),target.toShortString(),refillTicks,sourceColumn.chests().size());
                    nextSource();return;
                }
                if(contents.stream().anyMatch(s->!s.isEmpty())){
                    if(!action.has("kitTypeId")||action.has("requireLocal")&&action.get("requireLocal").getAsBoolean()){sourceProblem("Hopper outlet contains non-matching items; source exhaustion is not verified");return;}
                    sourceBlocked=true;
                }
                if(contents.stream().allMatch(ItemStack::isEmpty))rememberEmptySource(target,System.currentTimeMillis());
                BlockPos previous=target;
                if(advanceSourceChest()){
                    dev.monocle.client.MonocleClient.LOG.info("[Workers] Stash source {} has no matching kit at {}; checking next connected chest {}",action.get("name").getAsString(),previous.toShortString(),target.toShortString());
                    close();return;
                }
                if(recheckEmptyOutlet()){close();return;}
            }
            if(sourceUncertain||sourceBlocked){
                if(!action.has("kitTypeId")||action.has("requireLocal")&&action.get("requireLocal").getAsBoolean()){sourceProblem("Previously connected source chests are no longer verifiable");return;}
                dev.monocle.client.MonocleClient.LOG.info("[Workers] Deferred stash source {}: {}",action.get("name").getAsString(),recordDeferredSource(target,contents,sourceUncertain?"Previously connected source chests are no longer verifiable":"Non-matching stock may block kits in connecting hoppers; every reachable chest checked"));
            }
            if(contents.stream().allMatch(ItemStack::isEmpty))rememberEmptySource(target,System.currentTimeMillis());
            nextSource();return;
        }
        if(sourceSlot>=mc.player.containerMenu.slots.size()||mc.player.containerMenu.getSlot(sourceSlot).container==mc.player.getInventory()||!matches(mc.player.containerMenu.getSlot(sourceSlot).getItem(),pick))throw new IllegalStateException("Selected stash shulker changed since its scan");
        int destination=-1;for(int i=0;i<mc.player.containerMenu.slots.size();i++){var slot=mc.player.containerMenu.getSlot(i);if(slot.container==mc.player.getInventory()&&!slot.hasItem()&&i<serverMenuContents.size()&&serverMenuContents.get(i).isEmpty()){destination=i;break;}}
        if(destination<0){
            if(finishFullBatch()){close();return;}
            throw new IllegalStateException("Kit pickup has no empty carried slot; existing kits retained");
        }
        boolean mayRefill=sourceMayRefill(mc.level,target);
        if(repeat&&!bulkDisabled&&BotStashBulk.supportedMenu(mc.player.containerMenu)){
            bulk=BotStashBulk.plan(serverMenuContents,serverContents.size(),wanted()-receipt.size(),mc.player.tickCount,true,
                !mayRefill&&mc.player.containerMenu.getClass()==net.minecraft.world.inventory.ChestMenu.class,
                mayRefill,slot->matches(serverMenuContents.get(slot),pick));
            if(bulk!=null){bulk.send(menu,mc.player.tickCount,serverMenuContents,serverCursor);verification=bulk.diagnostic();return;}
        }
        withdrawnBox=BotStashScan.classify(List.of(mc.player.containerMenu.getSlot(sourceSlot).getItem()),action).get(0).getAsJsonObject();
        beginWithdrawal(serverCarried,sourceSlot,destination,mc.player.containerMenu.getSlot(sourceSlot).getItem(),mc.player.tickCount,serverMenuContents,mayRefill,serverContents.size());
        InvUtils.moveSlotsConfirmed(sourceSlot,destination);
    }
    private void settleWithdrawal(){
        int tick=mc.player.tickCount,age=tick-withdrawSent;
        if(needsWithdrawalSnapshot(tick,mc.player.containerMenu.getCarried())){
            dev.monocle.client.MonocleClient.LOG.info("[Workers] No server snapshot for stash pickup at {} after {} ticks; reopening for read-only reconciliation ({}/2)",target,age,withdrawRetries+1);
            beginWithdrawalReopen(tick,serverCursor);close();return;
        }
        if(age>100){
            String reason="Kit withdrawal was not confirmed at "+target+" after "+withdrawRetries+" reopen attempts; inspect the source chest before retrying"+(verification==null?"":" · "+verification);
            dev.monocle.client.MonocleClient.LOG.warn("[Workers] {}",reason);throw new IllegalStateException(reason);
        }
        if(!withdrawReopening&&age>=20&&mc.player.containerMenu==mc.player.inventoryMenu&&withdrawInventoryStart>=0&&withdrawRetries<2
            &&(serverCursor.isEmpty()||ItemStack.matches(serverCursor,withdrawStack))){beginWithdrawalReopen(tick,serverCursor);close();return;}
        if(withdrawReopening&&mc.player.containerMenu==mc.player.inventoryMenu){waiting="Reopening source for pickup reconciliation "+withdrawRetries+"/2";if(++wait%20==1)open();return;}
        if(mc.player.containerMenu==mc.player.inventoryMenu)return;
        int currentMenu=mc.player.containerMenu.containerId;
        if(menu<0&&(!withdrawReopening||currentMenu!=withdrawRecoveryMenu))menu=currentMenu;
        if(currentMenu!=menu)return;
        if(withdrawReopening&&menuReady&&canReselectWithdrawal(serverMenuContents,serverCarried,serverCursor)
            &&BotStashDeposit.sameContents(mc.player.containerMenu.slots.stream().map(s->s.getItem()).toList(),serverMenuContents)&&mc.player.containerMenu.getCarried().isEmpty()){
            if(deferUnappliedWithdrawal(serverMenuContents,serverCarried,serverCursor)){
                dev.monocle.client.MonocleClient.LOG.warn("[Workers] {} · continuing with {} confirmed pickups · {}",deferredSources.get(deferredSources.size()-1),receipt.size(),verification);
                close();return;
            }
            dev.monocle.client.MonocleClient.LOG.info("[Workers] Reconciled unapplied stash pickup at {}; reselecting current source contents: {}",target,verification);
            reselectWithdrawal(serverMenuContents,serverCarried,serverCursor);waiting="Reselecting source after unapplied pickup";wait=0;return;
        }
        if(!withdrawReopening&&age>=20&&serverAt>=withdrawSent&&canReopenWithdrawal(serverMenuContents,serverCarried,serverCursor)
            &&BotStashDeposit.sameContents(mc.player.containerMenu.slots.stream().map(s->s.getItem()).toList(),serverMenuContents)&&ItemStack.matches(mc.player.containerMenu.getCarried(),serverCursor)){
            dev.monocle.client.MonocleClient.LOG.info("[Workers] Reopening stash pickup at {} for reconciliation: {}",target,verification);
            beginWithdrawalReopen(tick,serverCursor);close();return;
        }
        waiting="Verifying stash pickup"+(withdrawReopening?" after source reopen "+withdrawRetries+"/2":"");
        if(age%10==0)requestSync();
    }
    private void deposit(){
        int empty=-1;for(int i=0;i<mc.player.containerMenu.slots.size();i++){var s=mc.player.containerMenu.getSlot(i);if(s.container==mc.player.getInventory())break;if(!s.hasItem()){empty=i;break;}}
        int inventory=-1;for(int i=0;i<36;i++)if(matchesAny(mc.player.getInventory().getItem(i))){inventory=i;break;}
        if(action.has("kitTypeId")){
            if(deposited>=wanted()){finished=true;close();return;}
            if(depositBefore>=0){if(mc.player.tickCount-depositAt>60)throw new IllegalStateException("Kit ender-chest deposit was not confirmed; inspect the chest before retrying");if((mc.player.tickCount-depositAt)%10==0)requestSync();return;}
            List<ItemStack> contents=mc.player.containerMenu.slots.stream().takeWhile(s->s.container!=mc.player.getInventory()).map(s->s.getItem()).toList();
            if(!mc.player.containerMenu.getCarried().isEmpty()||countCarried()!=serverCarried||!BotStashDeposit.sameContents(contents,serverContents)
                ||!BotStashDeposit.sameContents(mc.player.containerMenu.slots.stream().map(s->s.getItem()).toList(),serverMenuContents)){if(++wait>100)throw new IllegalStateException("Ender-chest inventory did not reconcile with its server snapshot; no further kit was moved");if(wait%10==1)requestSync();return;}
            wait=0;
            if(inventory<0||empty<0)throw new IllegalStateException("Ender chest has no room for all requested kits; remaining boxes are carried");
            if(!bulkDisabled&&BotStashBulk.supportedMenu(mc.player.containerMenu)){
                bulk=BotStashBulk.plan(serverMenuContents,serverContents.size(),wanted()-deposited,mc.player.tickCount,false,
                    mc.player.containerMenu.getClass()==net.minecraft.world.inventory.ChestMenu.class,false,slot->matchesAny(serverMenuContents.get(slot)));
                if(bulk!=null){bulk.send(menu,mc.player.tickCount,serverMenuContents,serverCursor);verification=bulk.diagnostic();return;}
            }
            depositBefore=serverCarried;depositSlot=empty;depositContents=BotStashDeposit.copyContents(serverContents);depositStack=mc.player.getInventory().getItem(inventory).copy();depositAt=mc.player.tickCount;InvUtils.moveConfirmed(inventory,empty);return;
        }
        if(inventory<0||empty<0){finished=true;close();return;}InvUtils.shiftClick().slot(inventory);wait=0;
    }
    void inventory(InventoryEvent event){
        if(!openingRequested||mc.player.containerMenu==mc.player.inventoryMenu||event.packet.containerId()!=mc.player.containerMenu.containerId
            ||event.packet.items().size()!=mc.player.containerMenu.slots.size())return;
        if(!acceptMenu(event.packet.containerId()))return;
        serverState=event.packet.stateId();
        int slots=0;while(slots<mc.player.containerMenu.slots.size()&&mc.player.containerMenu.getSlot(slots).container!=mc.player.getInventory())slots++;
        // Both read-only views belong to the same detached server snapshot.
        serverMenuContents=BotStashDeposit.copyContents(event.packet.items());
        serverContents=serverMenuContents.subList(0,slots);serverCarried=carriedIn(event);
        serverCursor=event.packet.carriedItem().copy();serverAt=mc.player.tickCount;
        if(bulk!=null){
            observeBulk(event.packet.items(),serverCursor);
            verification.addProperty("menu",event.packet.containerId());verification.addProperty("stateId",event.packet.stateId());verification.addProperty("packetTick",serverAt);
            return;
        }
        if(withdrawSent>=0&&menu>=0&&event.packet.containerId()==menu)observeWithdrawal(event.packet.items(),serverCarried,event.packet.carriedItem(),event.packet.stateId());
        if(depositBefore<0||menu<0||event.packet.containerId()!=menu||depositSlot>=event.packet.items().size())return;
        int carried=serverCarried;
        if(carried==depositBefore)return;
        if(carried!=depositBefore-1||!event.packet.carriedItem().isEmpty()||depositSlot>=serverContents.size()||!depositContents.get(depositSlot).isEmpty()||!ItemStack.matches(serverContents.get(depositSlot),depositStack))return;
        deposited++;depositBefore=depositSlot=-1;depositContents=List.of();depositStack=ItemStack.EMPTY;
    }
    void slot(ContainerSlotUpdateEvent event){
        if(!openingRequested||!menuReady||bulk!=null||withdrawSent>=0||depositBefore>=0||discardSlot>=0||!serverCursor.isEmpty()
            ||mc.player.containerMenu==mc.player.inventoryMenu||mc.player.containerMenu.containerId!=menu||event.packet.getContainerId()!=menu)return;
        var updated=BotStashDeposit.updatedSlot(serverMenuContents,serverState,event.packet.getStateId(),event.packet.getSlot(),event.packet.getItem());
        if(updated==null)return;
        int slots=serverContents.size(),slot=event.packet.getSlot();
        if(slot>=slots){ItemStack previous=serverMenuContents.get(slot),current=updated.get(slot);serverCarried+=(matchesAny(current)?current.getCount():0)-(matchesAny(previous)?previous.getCount():0);}
        serverMenuContents=updated;serverContents=updated.subList(0,slots);
        serverState=event.packet.getStateId();serverAt=mc.player.tickCount;
    }
    void observeBulk(List<ItemStack> items,ItemStack cursor){
        int before=bulk.verified,count=bulk.observe(items,cursor);
        if(depositing)deposited+=count;
        else for(int step=before;step<bulk.verified;step++){
            var move=bulk.moves.get(step);JsonObject actual=sourcePick(picks.get(index).getAsJsonObject(),target,sourceColumn.fed()).deepCopy();
            actual.addProperty("slot",move.source());actual.add("items",BotStashScan.classify(List.of(move.stack()),action).get(0).getAsJsonObject().get("items").deepCopy());receipt.add(actual);
        }
        verification=bulk.diagnostic();
        if(bulk.complete()){bulk=null;refillWait=sourceRefreshes=wait=0;waiting="";}
    }
    private void settleBulk(){
        if(menuReady&&mc.player.containerMenu.containerId==menu&&mc.player.containerMenu.getCarried().isEmpty())
            bulk.send(menu,mc.player.tickCount,serverMenuContents,serverCursor);
        int tick=mc.player.tickCount,age=tick-bulk.sentTick;
        if(age>100)throw new IllegalStateException("Bulk stash transfer remains unverified; no uncertain move replayed · "+bulk.diagnostic());
        if(!bulk.reopening&&age>=20){bulk.reopen(menu);close();return;}
        if(bulk.reopening&&mc.player.containerMenu==mc.player.inventoryMenu){waiting="Reopening stash for bulk transfer reconciliation";if(++wait%20==1)open();return;}
        if(bulk.reopening&&menuReady&&bulk.acceptsMenu(menu)&&bulk.canFallback(serverMenuContents,serverCursor)
            &&BotStashDeposit.sameContents(mc.player.containerMenu.slots.stream().map(s->s.getItem()).toList(),serverMenuContents)&&mc.player.containerMenu.getCarried().isEmpty()){
            dev.monocle.client.MonocleClient.LOG.warn("[Workers] Bulk stash transfer reconciled at {}; using exact-slot fallback for remaining kits: {}",target,bulk.diagnostic());
            bulk=null;bulkDisabled=true;wait=0;waiting="";return;
        }
        waiting="Verifying bulk stash transfer";
        if(menuReady&&age%10==0)requestSync();
    }
    void beginWithdrawal(int carried,int source,int destination,ItemStack stack,int tick,List<ItemStack> contents,boolean mayRefill){
        if(bulk!=null||withdrawSent>=0||carried<0||source<0||destination<0||source==destination||stack.getCount()!=1
            ||source>=contents.size()||destination>=contents.size()||!ItemStack.matches(contents.get(source),stack)||!contents.get(destination).isEmpty())
            throw new IllegalStateException("Kit pickup needs one server-confirmed box, an empty server-confirmed destination and no outstanding move");
        withdrawBefore=carried;withdrawSlot=source;withdrawDestination=destination;withdrawStack=stack.copy();withdrawSent=tick;withdrawConfirmed=false;withdrawSourceMayRefill=mayRefill;
        withdrawContents=BotStashDeposit.copyContents(contents);withdrawInventoryStart=-1;withdrawReopening=withdrawHeldOnClose=false;withdrawRecoveryMenu=-1;serverAt=-1;
        verification=new JsonObject();verification.addProperty("startedTick",tick);verification.addProperty("sourceSlot",source);verification.addProperty("destinationSlot",destination);verification.addProperty("before",carried);verification.addProperty("reopenAttempts",withdrawRetries);
    }
    void beginWithdrawal(int carried,int source,int destination,ItemStack stack,int tick,List<ItemStack> contents,boolean mayRefill,int inventoryStart){
        if(inventoryStart<1||contents.size()!=inventoryStart+36||source>=inventoryStart||destination<inventoryStart)throw new IllegalArgumentException("Pickup recovery needs a bounded container followed by the 36 player inventory slots");
        beginWithdrawal(carried,source,destination,stack,tick,contents,mayRefill);withdrawInventoryStart=inventoryStart;
    }
    boolean acceptMenu(int id){
        if(bulk!=null&&!bulk.acceptsMenu(id))return false;
        if(!openingRequested||withdrawReopening&&id==withdrawRecoveryMenu||menu>=0&&id!=menu)return false;
        menu=id;menuReady=true;return true;
    }
    boolean resetClosedMenu(int currentMenu,boolean cursorEmpty){
        if(menu<0||menu==currentMenu||!cursorEmpty||bulk!=null||withdrawSent>=0||depositBefore>=0||discardSlot>=0)return false;
        // An idle menu lease is obsolete after Escape; transfer evidence and receipts remain untouched.
        forgetMenu();wait=0;return true;
    }
    private boolean freshWithdrawalMenu(){return withdrawReopening&&openingRequested&&menuReady&&menu>=0&&menu!=withdrawRecoveryMenu;}
    private boolean unchangedPlayerInventory(List<ItemStack> items){
        return withdrawInventoryStart>=0&&items.size()==withdrawContents.size()
            &&BotStashDeposit.sameContents(items.subList(withdrawInventoryStart,items.size()),withdrawContents.subList(withdrawInventoryStart,withdrawContents.size()));
    }
    private boolean unchangedCargoInventory(List<ItemStack> items){
        if(withdrawInventoryStart<0||items.size()!=withdrawContents.size())return false;
        for(int i=withdrawInventoryStart;i<items.size();i++)if(!BotStashBulk.sameCargoSlot(withdrawContents.get(i),items.get(i)))return false;
        return true;
    }
    boolean onlyIncidentalAdditions(List<ItemStack> items){
        if(!handlesIncidental()||withdrawInventoryStart<0||items.size()!=withdrawContents.size())return false;
        boolean added=false;
        for(int i=withdrawInventoryStart;i<items.size();i++)if(!ItemStack.matches(items.get(i),withdrawContents.get(i))){
            ItemStack stack=items.get(i);
            if(BotStashBulk.sameCargoSlot(withdrawContents.get(i),stack))continue;
            JsonArray boxes=BotStashScan.classify(List.of(stack),action);
            if(!withdrawContents.get(i).isEmpty()||stack.isEmpty()||ItemStack.matches(stack,withdrawStack)||matchesAny(stack)||boxes.isEmpty()
                ||dev.monocle.coordinator.StashCatalog.kitTypeId(boxes.get(0).getAsJsonObject()).equals(action.get("kitTypeId").getAsString()))return false;
            added=true;
        }
        return added;
    }
    boolean canReopenWithdrawal(List<ItemStack> items,int carried,ItemStack cursor){
        if(withdrawSent<0||withdrawConfirmed||withdrawReopening||withdrawRetries>=2||carried!=withdrawBefore||!(unchangedCargoInventory(items)||cursor.isEmpty()&&onlyIncidentalAdditions(items)))return false;
        // Hoppers may have drained the selected source before the first click; reselect after reopening.
        return cursor.isEmpty()||ItemStack.matches(cursor,withdrawStack)&&(withdrawSourceMayRefill||items.get(withdrawSlot).isEmpty());
    }
    boolean needsWithdrawalSnapshot(int tick,ItemStack cursor){
        // A missing response may trigger a read, never a receipt or replay of the uncertain click.
        return withdrawSent>=0&&!withdrawConfirmed&&!withdrawReopening&&withdrawInventoryStart>=0&&withdrawRetries<2
            &&tick-withdrawSent>=20&&serverAt<withdrawSent&&serverCursor.isEmpty()&&cursor.isEmpty();
    }
    void beginWithdrawalReopen(int tick,ItemStack cursor){
        if(withdrawSent<0||withdrawConfirmed||withdrawReopening||withdrawInventoryStart<0||withdrawRetries>=2||!cursor.isEmpty()&&!ItemStack.matches(cursor,withdrawStack))throw new IllegalStateException("Pickup reconciliation is unavailable or exhausted");
        withdrawRecoveryMenu=menu;withdrawRetries++;withdrawReopening=true;withdrawHeldOnClose=ItemStack.matches(cursor,withdrawStack);withdrawSent=tick;menu=-1;menuReady=openingRequested=false;
        verification.addProperty("reopenAttempts",withdrawRetries);verification.addProperty("heldOnClose",withdrawHeldOnClose);
    }
    boolean canReselectWithdrawal(List<ItemStack> items,int carried,ItemStack cursor){
        return withdrawSent>=0&&!withdrawConfirmed&&freshWithdrawalMenu()&&!withdrawHeldOnClose&&cursor.isEmpty()&&carried==withdrawBefore&&(unchangedCargoInventory(items)||onlyIncidentalAdditions(items));
    }
    void reselectWithdrawal(List<ItemStack> items,int carried,ItemStack cursor){
        if(!canReselectWithdrawal(items,carried,cursor))throw new IllegalStateException("A fresh source menu must prove the previous pickup did not change player inventory before reselecting");
        withdrawSent=withdrawSlot=withdrawDestination=-1;withdrawReopening=withdrawHeldOnClose=false;withdrawnBox=null;withdrawStack=ItemStack.EMPTY;withdrawContents=List.of();withdrawInventoryStart=-1;
    }
    boolean deferUnappliedWithdrawal(List<ItemStack> items,int carried,ItemStack cursor){
        if(withdrawRetries<2||!canReselectWithdrawal(items,carried,cursor)||!transferAll()||!action.has("kitTypeId")
            ||!action.has("depositMode")||!action.get("depositMode").getAsString().equals("Carry")
            ||action.has("requireLocal")&&action.get("requireLocal").getAsBoolean()||target==null
            ||bulk!=null||depositBefore>=0||discardSlot>=0||depositing||!serverCursor.isEmpty())return false;
        // Fresh reopened inventory proves no pickup: defer this source, never claim it empty or add a receipt.
        recordDeferredSource(target,items.subList(0,withdrawInventoryStart),"Pickup unapplied after 2 source reopens; source deferred");
        reselectWithdrawal(items,carried,cursor);advanceSourcePick();
        verification.addProperty("unappliedDeferred",true);return true;
    }
    private int returnedPickupSlot(List<ItemStack> items){
        if(!freshWithdrawalMenu()||withdrawInventoryStart<0||items.size()!=withdrawContents.size())return -1;
        int received=-1;
        for(int i=withdrawInventoryStart;i<items.size();i++)if(!ItemStack.matches(items.get(i),withdrawContents.get(i))){
            if(BotStashBulk.sameCargoSlot(withdrawContents.get(i),items.get(i)))continue;
            if(received>=0||!withdrawContents.get(i).isEmpty()||!ItemStack.matches(items.get(i),withdrawStack))return -1;
            received=i;
        }
        return received;
    }
    boolean observeWithdrawal(List<ItemStack> items,int carried,ItemStack cursor,int stateId){
        if(withdrawSent<0||withdrawConfirmed)return withdrawConfirmed;
        if(withdrawReopening&&!freshWithdrawalMenu())return false;
        // Forced source-click snapshots legitimately show the box on the cursor, not in inventory yet.
        // A hopper may replace the source with an identical box before the second click's snapshot.
        // The previously empty carried slot, exact box, +1 carried count and empty cursor prove pickup.
        // Ordinary sources still require a changed source; no receipt ever replays the move.
        int returned=returnedPickupSlot(items);
        withdrawConfirmed=withdrawSlot<items.size()&&withdrawDestination<items.size()&&carried==withdrawBefore+1&&cursor.isEmpty()
            &&(!withdrawReopening||returned>=0)
            &&(withdrawSourceMayRefill||!ItemStack.matches(items.get(withdrawSlot),withdrawStack))&&ItemStack.matches(items.get(withdrawDestination),withdrawStack);
        if(carried==withdrawBefore+1&&cursor.isEmpty()&&returned>=0
            &&(withdrawSourceMayRefill||withdrawHeldOnClose||!ItemStack.matches(items.get(withdrawSlot),withdrawStack))){withdrawConfirmed=true;withdrawDestination=returned;}
        verification=new JsonObject();verification.addProperty("stateId",stateId);verification.addProperty("sourceSlot",withdrawSlot);verification.addProperty("destinationSlot",withdrawDestination);verification.addProperty("before",withdrawBefore);verification.addProperty("carried",carried);verification.addProperty("cursorCount",cursor.getCount());verification.addProperty("confirmed",withdrawConfirmed);
        verification.addProperty("reopenAttempts",withdrawRetries);verification.addProperty("reopening",withdrawReopening);verification.addProperty("heldOnClose",withdrawHeldOnClose);verification.addProperty("reconciledInventoryReceipt",withdrawConfirmed&&returned>=0);verification.addProperty("unchangedPlayerInventory",unchangedPlayerInventory(items));
        verification.addProperty("unchangedCargoInventory",unchangedCargoInventory(items));
        JsonArray changes=new JsonArray();
        if(withdrawInventoryStart>=0&&items.size()==withdrawContents.size())for(int i=withdrawInventoryStart;i<items.size();i++){
            ItemStack before=withdrawContents.get(i),after=items.get(i);
            if(ItemStack.matches(before,after))continue;
            JsonObject change=new JsonObject();change.addProperty("menuSlot",i);
            change.addProperty("beforeItem",BuiltInRegistries.ITEM.getKey(before.getItem()).toString());change.addProperty("beforeCount",before.getCount());change.addProperty("beforeDamage",before.getDamageValue());
            change.addProperty("afterItem",BuiltInRegistries.ITEM.getKey(after.getItem()).toString());change.addProperty("afterCount",after.getCount());change.addProperty("afterDamage",after.getDamageValue());
            change.addProperty("cargoChanged",!BotStashBulk.sameCargoSlot(before,after));changes.add(change);
        }
        verification.add("inventoryChanges",changes);
        verification.addProperty("packetTick",serverAt);verification.addProperty("menu",menu);verification.addProperty("cursorItem",BuiltInRegistries.ITEM.getKey(cursor.getItem()).toString());
        verification.addProperty("sourceMatchesExpected",withdrawSlot<items.size()&&ItemStack.matches(items.get(withdrawSlot),withdrawStack));verification.addProperty("destinationMatchesExpected",withdrawDestination<items.size()&&ItemStack.matches(items.get(withdrawDestination),withdrawStack));
        verification.addProperty("sourceMayRefill",withdrawSourceMayRefill);
        if(withdrawDestination<items.size()){verification.addProperty("destinationItem",BuiltInRegistries.ITEM.getKey(items.get(withdrawDestination).getItem()).toString());verification.addProperty("destinationCount",items.get(withdrawDestination).getCount());}
        return withdrawConfirmed;
    }
    private int carriedIn(InventoryEvent event){int carried=0;for(int i=0;i<Math.min(event.packet.items().size(),mc.player.containerMenu.slots.size());i++)if(mc.player.containerMenu.getSlot(i).container==mc.player.getInventory()&&matchesAny(event.packet.items().get(i)))carried+=event.packet.items().get(i).getCount();return carried;}
    private int countCarried(){int count=0;for(int i=0;i<36;i++)if(matchesAny(mc.player.getInventory().getItem(i)))count+=mc.player.getInventory().getItem(i).getCount();return count;}
    private boolean matchesAny(ItemStack stack){if(action.has("kitTypeId"))return BotStashScan.matchesKit(stack,action);for(JsonElement e:picks)if(matches(stack,e.getAsJsonObject()))return true;return false;}
    private boolean matches(ItemStack stack,JsonObject pick){
        if(!(stack.getItem() instanceof BlockItem b)||!(b.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock))return false;
        if(pick.has("kitTypeId"))return pick.get("kitTypeId").equals(action.get("kitTypeId"))&&pick.get("incomplete").equals(action.get("incomplete"))&&BotStashScan.matchesKit(stack,action);
        JsonObject box=BotStashScan.classify(List.of(stack),action).get(0).getAsJsonObject();
        return pick.get("resource").getAsString().equals(box.get("dominant").getAsString())&&!box.get("mixed").getAsBoolean();
    }
    private BlockPos findEchest(){return BlockPos.betweenClosedStream(mc.player.blockPosition().offset(-16,-4,-16),mc.player.blockPosition().offset(16,4,16)).filter(p->mc.level.getBlockEntity(p) instanceof EnderChestBlockEntity).min(Comparator.comparingDouble(p->Vec3.atCenterOf(p).distanceToSqr(mc.player.position()))).map(BlockPos::immutable).orElse(null);}
    private void requestSync(){mc.getConnection().send(HighwayBuilder.cursorSyncRequest(menu,HashedStack.create(mc.player.containerMenu.getCarried(),mc.getConnection().decoratedHashOpsGenenerator())));}
    private void forgetMenu(){menu=-1;menuReady=openingRequested=false;serverContents=serverMenuContents=List.of();serverCarried=serverAt=serverState=-1;serverCursor=ItemStack.EMPTY;}
    void close(){if(mc.player!=null&&mc.player.containerMenu!=mc.player.inventoryMenu)mc.player.closeContainer();forgetMenu();}
}
