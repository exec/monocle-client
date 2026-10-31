package dev.monocle.client.systems.bots;

import com.google.gson.*;
import dev.monocle.client.events.packets.InventoryEvent;
import dev.monocle.client.events.packets.ContainerSlotUpdateEvent;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.coordinator.StashCatalog;
import dev.monocle.coordinator.KitCategories;
import dev.monocle.client.systems.modules.misc.swarm.CrewInventory;
import net.minecraft.network.HashedStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.*;
import java.util.*;
import static dev.monocle.client.MonocleClient.mc;

/** Deposits exact kit boxes; interrupted moves reconcile fresh stock instead of replaying a click. */
final class BotStashDeposit {
    private final JsonObject action;
    private final Set<Long> tried=new HashSet<>();
    private final JsonArray touched=new JsonArray();
    private final JsonArray depositedBoxes=new JsonArray();
    private boolean receiptComplete=true;
    private BlockPos target;
    private JsonObject column,pendingFinding=new JsonObject();
    private JsonObject columnCategory;
    private int delivery=1,findingMode,hostWait;
    private long hostWaitStartedAt;
    private boolean approved;
    private String failure="",verificationMismatch="";
    private int menu=-1,beforeMenu=-1,confirmed,missing,pendingBefore=-1,pendingDest=-1,pendingAt,openWait,pendingStage;
    private ItemStack pendingStack=ItemStack.EMPTY;
    private List<ItemStack> serverContents=List.of(),pendingContents=List.of();
    private List<ItemStack> serverInventory=List.of(),pendingInventory=List.of();
    private ItemStack serverCursor=ItemStack.EMPTY;
    private int pendingSource=-1,recoveryAttempts,serverAt=-1,recoveryMenu=-1;
    private int serverCarried=-1;
    private JsonObject verification;
    private boolean finished,menuReady,columnFull,reconciling;
    private int retained;
    private int hopperRefillTicks,fullWait;
    private BotStashBulk bulk;
    private boolean bulkDisabled;
    private boolean columnUnreadable;
    private long capacityRetryAt;
    private int serverState=-1,snapshotReopens;
    BotStashDeposit(JsonObject action){this.action=action;}
    boolean done(){return finished&&pendingFinding==null&&mc.player.containerMenu==mc.player.inventoryMenu;}
    boolean canYieldInventory(){return bulk==null&&pendingStage==0&&pendingBefore<0&&!reconciling&&serverCursor.isEmpty()&&pendingFinding==null;}
    boolean approaching(){return bulk==null&&pendingStage==0&&target!=null&&(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target))>20.25||!BotStashScan.visibleFrom(mc.player.getEyePosition(),target));}
    BlockPos target(){return target;}
    int floorY(){return column==null?action.get("minY").getAsInt():column.get("y").getAsInt();}
    boolean hasTouched(){return !touched.isEmpty();}
    dev.monocle.coordinator.ActionProgress.Observation progress() {
        boolean moving = approaching() && pendingFinding == null && !reconciling && pendingBefore < 0;
        return BotActions.progress("Stash deposit", waitingForCapacity() ? "Destination capacity; retrying host every 10s" : pendingFinding != null ? "Host column / observation acknowledgement" : moving ? "Reach destination container" : pendingStage > 0 || bulk != null || reconciling ? "Server deposit reconciliation" : fullWait > 0 ? "Hopper drain" : "Container contents",
            confirmed + "/" + missing + "/" + delivery, target, false, moving);
    }
    String detail(){return (reconciling?"Recovering interrupted deposit · ":"")+"Storing kit "+confirmed+"/"+action.get("count").getAsInt()+(waitingForCapacity()?" · destination needs free compatible columns; retrying host every 10s":pendingFinding!=null?" · waiting for host column reservation/observation acknowledgement":target==null?" · locating a reserved-column chest":" · chest "+target.toShortString())+(bulk!=null?" · verifying burst "+bulk.verified+"/"+bulk.moves.size():pendingStage==2?" · reopening for deposit reconciliation "+recoveryAttempts+"/2":pendingStage>0?" · verifying chest contents":"")+(fullWait>0?" · waiting for top chest to drain through hoppers":"");}
    boolean hasPending(){return pendingFinding!=null;}
    JsonObject pending(){return pendingFinding==null?null:pendingFinding.deepCopy();}
    int delivery(){return delivery;}
    void acknowledge(JsonObject ack){
        if(pendingFinding==null||!ack.has("deposit")||!ack.get("deposit").getAsBoolean()||ack.get("delivery").getAsInt()!=delivery)return;
        if(ack.has("error")){if(dev.monocle.coordinator.TaskWire.flag(action,"fillAllocatedColumns")&&mc.player!=null&&mc.player.containerMenu.getCarried().isEmpty()&&finishAllocation(ack,countCarried())||deferCapacity(ack)){close();return;}failure=ack.get("error").getAsString();pendingFinding=null;return;}
        JsonObject next=StashCatalog.checkedColumn(action,ack.getAsJsonObject("column"));boolean changed=column==null||!column.equals(next);
        if(action.has("targetColumn")&&!next.equals(action.getAsJsonObject("targetColumn"))){failure="Host selected another destination column; remaining kits retained";pendingFinding=null;return;}
        if(ack.has("columnFull")&&ack.get("columnFull").getAsBoolean()){
            if(!action.has("targetColumn")||!pendingFinding.has("exhaustedColumn")||!pendingFinding.get("exhaustedColumn").getAsBoolean()){failure="Unexpected full-column acknowledgement; remaining kits retained";pendingFinding=null;return;}
            finishFullColumn(countCarried());pendingFinding=null;hostWait=0;delivery++;return;
        }
        columnCategory=ack.has("columnCategory")?KitCategories.checked(ack.getAsJsonObject("columnCategory")):null;
        column=next;approved=findingMode==1&&ack.has("permit")&&ack.get("permit").getAsBoolean()&&!changed;
        pendingFinding=null;hostWait=0;delivery++;
        if(changed){tried.clear();columnUnreadable=false;target=null;close();}else if(findingMode==1&&!approved){target=null;close();}
        if(findingMode==2)approved=true;
    }
    boolean waitingForCapacity(){return capacityRetryAt!=0;}
    private boolean capacityReply(JsonObject ack){
        return !(pendingFinding==null||!ack.has("deposit")||!ack.get("deposit").getAsBoolean()||ack.get("delivery").getAsInt()!=delivery
            ||!ack.has("error")||action.has("targetColumn")||!ack.has("capacityUnavailable")||!ack.get("capacityUnavailable").isJsonPrimitive()
            ||!ack.getAsJsonPrimitive("capacityUnavailable").isBoolean()||!ack.get("capacityUnavailable").getAsBoolean()
            ||ack.has("column")||ack.has("permit")&&ack.get("permit").getAsBoolean()||bulk!=null||pendingStage!=0||pendingBefore>=0||!serverCursor.isEmpty()||delivery>=4096);
    }
    boolean finishAllocation(JsonObject ack,int carried){
        if(!dev.monocle.coordinator.TaskWire.flag(action,"fillAllocatedColumns")||reconciling||!capacityReply(ack))return false;
        finishFullColumn(carried);delivery++;return true;
    }
    boolean deferCapacity(JsonObject ack){
        if(!capacityReply(ack))return false;
        pendingFinding=null;findingMode=0;hostWait=0;delivery++;
        if(confirmed+missing>=action.get("count").getAsInt()){finished=true;return true;}
        capacityRetryAt=System.nanoTime()+10_000_000_000L;
        column=null;target=null;approved=false;return true;
    }
    boolean retryCapacity(long now){
        if(!waitingForCapacity()||now<capacityRetryAt)return false;
        capacityRetryAt=0;pendingFinding=new JsonObject();findingMode=0;hostWait=0;return true;
    }
    JsonObject result(){JsonObject value=new JsonObject();value.addProperty("stash",action.get("name").getAsString());if(action.has("catalogCrew"))value.add("catalogCrew",action.get("catalogCrew").deepCopy());value.add("touched",touched.deepCopy());value.addProperty("deposited",confirmed);value.add("depositedBoxes",depositedBoxes.deepCopy());value.addProperty("receiptComplete",receiptComplete);value.addProperty("missing",missing);if(columnFull){value.addProperty("columnFull",true);value.addProperty("retained",retained);}return value;}
    JsonObject workflowResult(){JsonObject value=result();value.remove("depositedBoxes");value.remove("receiptComplete");return value;}
    String completionDetail(){return "Verified "+confirmed+" kit deposits"+(columnFull?"; selected column full, "+retained+" kits retained":missing>0?"; "+missing+" requested kits no longer carried, logged and continuing":"");}
    void finishFullColumn(int carried){
        if(!(action.has("targetColumn")||dev.monocle.coordinator.TaskWire.flag(action,"fillAllocatedColumns"))||bulk!=null||reconciling||pendingBefore>=0||pendingStage!=0)throw new IllegalStateException("A full-column stop needs an explicit allocation policy and no unverified deposit");
        if(carried<0)throw new IllegalArgumentException("Invalid carried kit count");
        int remaining=action.get("count").getAsInt()-confirmed-missing;
        retained=Math.min(remaining,carried);missing+=remaining-retained;columnFull=true;finished=true;pendingFinding=null;
    }
    void finishMissingCarried(){
        if(bulk!=null||reconciling||pendingBefore>=0||pendingStage!=0)throw new IllegalStateException("An unverified deposit cannot be skipped as a missing carried kit");
        missing=action.get("count").getAsInt()-confirmed;finished=true;
    }
    JsonObject snapshot(){JsonObject s=new JsonObject();if(columnCategory!=null)s.add("columnCategory",columnCategory.deepCopy());s.addProperty("capacityWaiting",waitingForCapacity());s.addProperty("confirmed",confirmed);s.addProperty("missing",missing);s.addProperty("pending",bulk!=null||pendingBefore>=0||reconciling);s.addProperty("bulkDisabled",bulkDisabled);s.addProperty("columnUnreadable",columnUnreadable);s.addProperty("reconciling",reconciling);s.addProperty("finished",finished);if(columnFull){s.addProperty("columnFull",true);s.addProperty("retained",retained);}s.add("touched",touched.deepCopy());s.add("depositedBoxes",depositedBoxes.deepCopy());s.addProperty("receiptComplete",receiptComplete);s.addProperty("delivery",delivery);s.addProperty("findingMode",findingMode);if(column!=null)s.add("column",column.deepCopy());if(pendingFinding!=null)s.add("finding",pendingFinding.deepCopy());if(verification!=null)s.add("verification",verification.deepCopy());JsonArray skipped=new JsonArray();tried.forEach(skipped::add);s.add("tried",skipped);return s;}
    void restore(JsonObject s){
        columnCategory=s.has("columnCategory")?KitCategories.checked(s.getAsJsonObject("columnCategory")):null;
        reconciling=s.get("pending").getAsBoolean();
        bulkDisabled=s.has("bulkDisabled")&&s.get("bulkDisabled").getAsBoolean();
        columnUnreadable=s.has("columnUnreadable")&&s.get("columnUnreadable").getAsBoolean();
        confirmed=StashCatalog.integer(s,"confirmed",0,action.get("count").getAsInt());missing=s.has("missing")?StashCatalog.integer(s,"missing",0,action.get("count").getAsInt()-confirmed):0;finished=s.get("finished").getAsBoolean();columnFull=s.has("columnFull")&&s.get("columnFull").getAsBoolean();retained=s.has("retained")?StashCatalog.integer(s,"retained",0,action.get("count").getAsInt()-confirmed-missing):0;
        if(s.has("depositedBoxes")){
            if(!s.has("receiptComplete")||!s.get("receiptComplete").isJsonPrimitive()||!s.getAsJsonPrimitive("receiptComplete").isBoolean())throw new IllegalArgumentException("Invalid deposit receipt checkpoint");
            receiptComplete=s.get("receiptComplete").getAsBoolean();
            depositedBoxes.addAll(StashCatalog.depositBoxes(action,s.getAsJsonArray("depositedBoxes"),confirmed,receiptComplete));
        }else receiptComplete=confirmed==0;
        if(reconciling&&(finished||confirmed+missing>=action.get("count").getAsInt())||columnFull&&(!(action.has("targetColumn")||dev.monocle.coordinator.TaskWire.flag(action,"fillAllocatedColumns"))||!finished||confirmed+missing+retained!=action.get("count").getAsInt())||!columnFull&&retained>0||finished&&missing>0&&confirmed+missing+retained!=action.get("count").getAsInt())throw new IllegalArgumentException("Invalid retained/missing-kit checkpoint");
        touched.addAll(s.getAsJsonArray("touched"));if(s.has("delivery"))delivery=StashCatalog.integer(s,"delivery",1,4096);if(s.has("column"))column=StashCatalog.checkedColumn(action,s.getAsJsonObject("column"));if(column!=null&&action.has("targetColumn")&&!column.equals(action.getAsJsonObject("targetColumn")))throw new IllegalArgumentException("Saved deposit left its selected column");findingMode=s.has("findingMode")?StashCatalog.integer(s,"findingMode",0,2):0;pendingFinding=s.has("finding")?s.getAsJsonObject("finding").deepCopy():columnFull?null:new JsonObject();
        if(s.has("tried")){if(s.getAsJsonArray("tried").size()>4096)throw new IllegalArgumentException("Too many destination chest checkpoints");for(JsonElement p:s.getAsJsonArray("tried"))tried.add(p.getAsLong());}
        // Do not guess whether the old click landed: keep receipts, renew the lease, and move only fresh verified carried stock.
        if(reconciling){findingMode=0;pendingFinding=new JsonObject();if(s.has("verification"))verification=s.getAsJsonObject("verification").deepCopy();}
        if(s.has("capacityWaiting")&&s.get("capacityWaiting").getAsBoolean()){
            if(finished||column!=null||s.has("finding")||action.has("targetColumn"))throw new IllegalArgumentException("Invalid destination capacity checkpoint");
            capacityRetryAt=System.nanoTime()+10_000_000_000L;pendingFinding=null;findingMode=0;
        }
    }
    boolean reconcileInterruptedDeposit(){
        if(!reconciling)return true;
        if(!approved||!menuReady||menu<0||beforeMenu<0||serverAt<0||serverCarried<0||serverInventory.size()!=36||!serverCursor.isEmpty())return false;
        reconciling=false;verificationMismatch="";return true;
    }
    void tick(){
        if(!failure.isEmpty())throw new IllegalStateException(failure);
        if(done())return;
        if(waitingForCapacity()&&!retryCapacity(System.nanoTime())){close();return;}
        if(pendingFinding!=null){
            if(waitForHost(System.nanoTime()))dev.monocle.client.MonocleClient.LOG.warn("[Workers] Delayed stash deposit acknowledgement: stash={}, delivery={}, verified={}/{}, mode={}; retaining the pending observation and retrying for up to 60s",action.get("name"),delivery,confirmed,action.get("count"),findingMode);
            return;
        }
        if(finished||confirmed+missing>=action.get("count").getAsInt()){finished=true;close();return;}
        if(target==null){target=findChest();approved=false;if(target==null){
            if(columnUnreadable&&action.has("targetColumn"))throw new IllegalStateException("Selected destination column has unreadable chests; remaining kits retained");
            pendingFinding=new JsonObject();pendingFinding.addProperty(columnUnreadable?"unavailableColumn":"exhaustedColumn",true);findingMode=0;hostWait=0;return;
        }}
        if(bulk!=null){settleBulk();return;}
        if(pendingStage>0){settleDeposit();return;}
        if(BotStashScan.blockedChestLid(mc.level,target)!=null){dev.monocle.client.MonocleClient.LOG.warn("[Workers] Destination lid blocked at {}; retaining cargo and trying another chest",target);deferChest();close();return;}
        if(approaching())return;
        BotStashScan.verifySortGeometry(mc.level,target,action);
        if(mc.player.containerMenu==mc.player.inventoryMenu){
            if(menu>=0){close();approved=false;}
            if(++openWait>200){dev.monocle.client.MonocleClient.LOG.warn("[Workers] Destination did not open at {}; retaining cargo and trying another chest",target);deferChest();close();return;}
            if(retryOpening(openWait,target))open();return;
        }
        if(beforeMenu<0||menu>=0&&mc.player.containerMenu.containerId!=menu){close();approved=false;return;}
        if(menu<0)menu=mc.player.containerMenu.containerId;
        if(!menuReady){if(++openWait>100){dev.monocle.client.MonocleClient.LOG.warn("[Workers] Destination contents not received at {}; retaining cargo and trying another chest",target);deferChest();close();return;}if(openWait%10==0)requestSync();return;}
        if(!approved){requestSync();return;}
        List<ItemStack> contents=new ArrayList<>();for(var slot:mc.player.containerMenu.slots){if(slot.container==mc.player.getInventory())break;contents.add(slot.getItem());}
        boolean interrupted=reconciling;
        // Local click prediction and late resync packets can disagree. Never use prediction as the next move's baseline.
        if(!mc.player.containerMenu.getCarried().isEmpty()||countCarried()!=serverCarried||!sameContents(contents,serverContents)||!sameContents(playerContents(),serverInventory)||!reconcileInterruptedDeposit()){
            if(reopenIdleSnapshot(++openWait,snapshotReopens,reconciling,serverCursor,mc.player.containerMenu.getCarried())){
                snapshotReopens++;
                dev.monocle.client.MonocleClient.LOG.warn("[Workers] Refreshing idle destination snapshot at {} ({}/2): chest slots={}, inventory slots={}, carried={}/{}, state={}, age={}",target,snapshotReopens,mismatchedSlots(contents,serverContents),mismatchedSlots(playerContents(),serverInventory),countCarried(),serverCarried,serverState,mc.player.tickCount-serverAt);
                close();approved=false;return;
            }
            if(openWait>100)throw new IllegalStateException("Destination inventory did not reconcile with its server snapshot; no further kit was moved");if(openWait%10==1)requestSync();return;
        }
        snapshotReopens=0;
        if(interrupted)dev.monocle.client.MonocleClient.LOG.warn("[Workers] Resuming interrupted stash deposit: stash={}, verified={}, currently carried={}, requested={}; old unverified click will not be replayed or counted as deposited; evidence={}",action.get("name"),confirmed,serverCarried,action.get("count"),verification);
        openWait=0;
        int source=-1;
        JsonArray eligible=action.has("protectedBoxes")?BotStashResupply.newBoxes(action.getAsJsonArray("protectedBoxes"),BotStashScan.classify(playerContents()),""):null;
        for(int i=0;i<36;i++)if(eligibleSource(i,eligible)){source=i;break;}
        if(source<0){
            finishMissingCarried();
            dev.monocle.client.MonocleClient.LOG.warn("[Workers] Stash deposit shortfall: stash={}, kit={}, requested={}, verified={}, no longer carried={}; no pending click, continuing without counting missing kits as deposited",action.get("name"),action.get("kitTypeId"),action.get("count"),confirmed,missing);
            observeColumn(contents,2);close();return;
        }
        if(!compatibleContents(contents,action,columnCategory)){observeColumn(contents,1);return;}
        int empty=-1;
        for(int i=0;i<mc.player.containerMenu.slots.size();i++){var slot=mc.player.containerMenu.getSlot(i);if(slot.container==mc.player.getInventory())break;if(!slot.hasItem()){empty=i;break;}}
        if(empty<0){
            var remaining=new HashSet<>(tried);remaining.add(target.asLong());
            // A full input says nothing about space below it, especially during inventory-sized bursts.
            if(hopperRefillTicks>0&&nextColumnChest(BotStashScan.hopperColumn(mc.level,target,action),column,remaining)==null
                &&waitingForDrain(Math.min(16,hopperRefillTicks))){if(fullWait%10==1)requestSync();return;}
            tried.add(target.asLong());close();target=null;return;
        }
        fullWait=0;
        JsonObject position=new JsonObject();position.addProperty("x",target.getX());position.addProperty("y",target.getY());position.addProperty("z",target.getZ());
        if(touched.asList().stream().noneMatch(v->v.getAsJsonObject().equals(position)))touched.add(position);
        if(!bulkDisabled&&BotStashBulk.supportedMenu(mc.player.containerMenu)){
            bulk=BotStashBulk.plan(mc.player.containerMenu.slots.stream().map(s->s.getItem()).toList(),serverContents.size(),action.get("count").getAsInt()-confirmed-missing,mc.player.tickCount,
                false,hopperRefillTicks==0&&mc.player.containerMenu.getClass()==net.minecraft.world.inventory.ChestMenu.class,false,
                slot->eligibleSource(mc.player.containerMenu.getSlot(slot).getContainerSlot(),eligible));
            if(bulk!=null){bulk.send(menu,mc.player.tickCount,menuContents(),serverCursor);verification=bulk.diagnostic();return;}
        }
        if(!InvUtils.canMoveConfirmed(source,empty)){requestSync();return;}
        beginDeposit(serverCarried,empty,mc.player.getInventory().getItem(source),mc.player.tickCount,serverContents,source,serverInventory);
        InvUtils.moveConfirmed(source,empty);
    }
    private boolean eligibleSource(int slot,JsonArray eligible){
        return slot>=0&&slot<36&&matches(serverInventory.get(slot))&&(eligible==null||eligible.asList().stream().anyMatch(e->e.getAsJsonObject().get("slot").getAsInt()==slot
            &&e.getAsJsonObject().get("quantity").getAsInt()==serverInventory.get(slot).getCount()));
    }
    void deferChest(){
        if(target==null||bulk!=null||pendingBefore>=0||pendingStage>0||reconciling||pendingFinding!=null||!serverCursor.isEmpty())throw new IllegalStateException("An unconfirmed deposit cannot skip its destination");
        tried.add(target.asLong());columnUnreadable=true;target=null;approved=false;
    }
    private void settleBulk(){
        if(menuReady&&mc.player.containerMenu.containerId==menu&&mc.player.containerMenu.getCarried().isEmpty()&&compatibleContents(serverContents,action,columnCategory))
            bulk.send(menu,mc.player.tickCount,menuContents(),serverCursor);
        int tick=mc.player.tickCount,age=tick-bulk.sentTick;
        if(age>100)throw new IllegalStateException("Bulk deposit remains unverified; no uncertain move replayed · "+bulk.diagnostic());
        if(!bulk.reopening&&age>=20){bulk.reopen(menu);close();return;}
        if(bulk.reopening&&mc.player.containerMenu==mc.player.inventoryMenu){if(retryOpening(++openWait,target))open();return;}
        if(bulk.reopening&&menuReady&&bulk.acceptsMenu(menu)
            &&sameContents(playerContents(),serverInventory)&&mc.player.containerMenu.getCarried().isEmpty()){
            JsonObject evidence=bulk.diagnostic();int before=missing;
            if(reconcileBulk(menuContents(),serverCursor)){
                dev.monocle.client.MonocleClient.LOG.warn("[Workers] Bulk deposit reconciled at {}; {} kits no longer carried but not counted as verified deposits; exact-slot fallback for retained kits: {}",target,missing-before,evidence);
                observeColumn(serverContents,2);return;
            }
        }
        if(menuReady&&age%10==0)requestSync();
    }
    boolean reconcileBulk(List<ItemStack> contents,ItemStack cursor){
        int gone=bulk==null?-1:bulk.missingAfterReopen(contents,cursor);
        if(gone<0)return false;
        verification=bulk.diagnostic();verification.addProperty("unverifiedNoLongerCarried",gone);
        missing+=gone;bulk=null;bulkDisabled=true;openWait=0;
        return true;
    }
    private List<ItemStack> menuContents(){
        List<ItemStack> items=new ArrayList<>(serverContents);
        for(var slot:mc.player.containerMenu.slots)if(slot.container==mc.player.getInventory())items.add(serverInventory.get(slot.getContainerSlot()));
        return items;
    }
    boolean waitForHost(long now){
        if(hostWait++==0)hostWaitStartedAt=now;
        if(now-hostWaitStartedAt>=60_000_000_000L)throw new IllegalStateException("Host did not acknowledge the destination column within 60s (delivery "+delivery+", verified "+confirmed+"/"+action.get("count").getAsInt()+"); no further kit was deposited");
        return hostWait==200;
    }
    private void settleDeposit(){
        int tick=mc.player.tickCount,age=tick-pendingAt;
        if(age>100){
            String reason="Kit deposit verification timed out at "+target+" (menu "+menu+", stage "+pendingStage+", reopen attempts "+recoveryAttempts+", source inventory slot "+pendingSource+", destination "+pendingDest+", carried "+pendingBefore+" -> "+serverCarried+", cursor "+serverCursor.getCount()+", snapshot tick "+serverAt+"); inspect it before retrying";
            dev.monocle.client.MonocleClient.LOG.warn("[Workers] {}; evidence={}; mismatch={}",reason,verification,verificationMismatch);throw new IllegalStateException(reason);
        }
        if(pendingStage==1&&age>=20&&(mc.player.containerMenu==mc.player.inventoryMenu||mc.player.containerMenu.containerId!=menu)&&pendingSource>=0&&recoveryAttempts<2){beginReopen(tick);close();return;}
        if(pendingStage==2&&mc.player.containerMenu==mc.player.inventoryMenu){if(++openWait%20==1)open();return;}
        if(mc.player.containerMenu==mc.player.inventoryMenu||mc.player.containerMenu.containerId!=menu)return;
        if(pendingStage==2&&sameContents(playerContents(),serverInventory)&&mc.player.containerMenu.getCarried().isEmpty()
            &&recoverOccupiedDestination(serverContents,serverInventory,serverCursor)){
            dev.monocle.client.MonocleClient.LOG.info("[Workers] Deposit destination filled during move at {}; original kit returned, selecting another slot or column",target);return;
        }
        if(pendingStage==2&&menuReady&&canRetry(serverContents,serverInventory,serverCursor)&&compatibleContents(serverContents,action,columnCategory)
            &&sameContents(playerContents(),serverInventory)&&mc.player.containerMenu.getCarried().isEmpty()){
            // Hopper slot updates can change the local destination while the reopened full snapshot is still empty.
            if(!InvUtils.canMoveConfirmed(restoredSource(serverInventory),pendingDest)){if(age%10==0)requestSync();return;}
            retryDeposit(tick,serverContents,serverInventory,serverCursor);
            dev.monocle.client.MonocleClient.LOG.info("[Workers] Retrying unchanged stash deposit at {}, source inventory slot={}, destination={}, attempt={}",target,pendingSource,pendingDest,recoveryAttempts);
            InvUtils.moveConfirmed(pendingSource,pendingDest);return;
        }
        if(pendingStage==1&&age>=20&&serverAt>=pendingAt&&canReopen(serverContents,serverInventory,serverCursor)
            &&sameContents(playerContents(),serverInventory)&&ItemStack.matches(mc.player.containerMenu.getCarried(),serverCursor)){
            dev.monocle.client.MonocleClient.LOG.info("[Workers] Reopening stash deposit at {} for reconciliation: {}",target,verification);
            beginReopen(tick);close();return;
        }
        if(age%10==0)requestSync();
    }
    static boolean retryOpening(int ticks,BlockPos target){
        if(ticks>200)throw new IllegalStateException("Destination chest did not open after 200 ticks at "+target.toShortString()+"; remaining kits are carried, no deposit was attempted here");
        return ticks%20==1;
    }
    void inventory(InventoryEvent event){
        // Late source packets can arrive during /home or host acknowledgement, before any destination opening.
        if(target==null||beforeMenu<0||finished||pendingFinding!=null)return;
        if(mc.player.containerMenu==mc.player.inventoryMenu||event.packet.containerId()!=mc.player.containerMenu.containerId)return;
        if(event.packet.items().size()!=mc.player.containerMenu.slots.size())return;
        if(!acceptMenu(event.packet.containerId()))return;
        serverState=event.packet.stateId();
        int carried=0;for(int i=0;i<Math.min(event.packet.items().size(),mc.player.containerMenu.slots.size());i++)if(mc.player.containerMenu.getSlot(i).container==mc.player.getInventory()&&matches(event.packet.items().get(i)))carried+=event.packet.items().get(i).getCount();
        int slots=0;while(slots<mc.player.containerMenu.slots.size()&&mc.player.containerMenu.getSlot(slots).container!=mc.player.getInventory())slots++;
        List<ItemStack> contents=event.packet.items().subList(0,slots);
        serverContents=copyContents(contents);serverCarried=carried;serverCursor=event.packet.carriedItem().copy();serverAt=mc.player.tickCount;
        List<ItemStack> inventory=new ArrayList<>(Collections.nCopies(36,ItemStack.EMPTY));
        for(int i=slots;i<mc.player.containerMenu.slots.size();i++){
            var slot=mc.player.containerMenu.getSlot(i);int index=slot.getContainerSlot();
            if(slot.container==mc.player.getInventory()&&index>=0&&index<36)inventory.set(index,event.packet.items().get(i));
        }
        serverInventory=copyContents(inventory);
        if(bulk!=null){
            observeBulk(event.packet.items(),serverCursor);
            verification.addProperty("menu",event.packet.containerId());verification.addProperty("stateId",event.packet.stateId());verification.addProperty("packetTick",serverAt);
            if(bulk==null){
                if(confirmed+missing>=action.get("count").getAsInt()||contents.stream().noneMatch(ItemStack::isEmpty))observeColumn(contents,2);
            }
            return;
        }
        if(pendingStage==0&&!approved){observeColumn(contents,1);return;}
        if(pendingStage==0)return;
        recordVerification(contents,carried,serverInventory,serverCursor);
        verification.addProperty("menu",event.packet.containerId());verification.addProperty("stateId",event.packet.stateId());verification.addProperty("packetTick",serverAt);
        if(!event.packet.carriedItem().isEmpty())return;
        observeContents(contents,carried);
        if(pendingStage==0&&(confirmed>=action.get("count").getAsInt()||contents.stream().noneMatch(ItemStack::isEmpty)))observeColumn(contents,2);
    }
    void slot(ContainerSlotUpdateEvent event){
        if(!menuReady||serverInventory.size()!=36||pendingFinding!=null||reconciling||pendingStage!=0||bulk!=null||!serverCursor.isEmpty()
            ||mc.player.containerMenu==mc.player.inventoryMenu||mc.player.containerMenu.containerId!=menu)return;
        var packet=event.packet;
        if(packet.getContainerId()!=menu)return;
        int slot=packet.getSlot(),containerSlots=serverContents.size();
        var before=new ArrayList<>(serverContents);
        for(int i=containerSlots;i<mc.player.containerMenu.slots.size();i++)before.add(serverInventory.get(mc.player.containerMenu.getSlot(i).getContainerSlot()));
        var updated=updatedSlot(before,serverState,packet.getStateId(),slot,packet.getItem());
        if(updated==null)return;
        serverContents=updated.subList(0,containerSlots);
        if(slot>=containerSlots){
            ItemStack previous=before.get(slot),current=updated.get(slot);
            serverCarried+=(matches(current)?current.getCount():0)-(matches(previous)?previous.getCount():0);
            var inventory=new ArrayList<>(serverInventory);inventory.set(mc.player.containerMenu.getSlot(slot).getContainerSlot(),current);serverInventory=List.copyOf(inventory);
        }
        serverState=packet.getStateId();serverAt=mc.player.tickCount;
    }
    // Slot packets keep an idle baseline fresh; they never confirm a pending transfer or seed an unread menu.
    static List<ItemStack> updatedSlot(List<ItemStack> before,int state,int next,int slot,ItemStack item){
        if(state<0||!BotStashObserver.freshState(state,next)||slot<0||slot>=before.size())return null;
        var result=new ArrayList<>(before);result.set(slot,item.copy());return List.copyOf(result);
    }
    static boolean reopenIdleSnapshot(int ticks,int attempts,boolean pending,ItemStack serverCursor,ItemStack cursor){
        return ticks==20&&attempts<2&&!pending&&serverCursor.isEmpty()&&cursor.isEmpty();
    }
    static List<Integer> mismatchedSlots(List<ItemStack> local,List<ItemStack> server){
        return java.util.stream.IntStream.range(0,Math.max(local.size(),server.size()))
            .filter(i->i>=local.size()||i>=server.size()||!ItemStack.matches(local.get(i),server.get(i))).boxed().toList();
    }
    void observeBulk(List<ItemStack> items,ItemStack cursor){
        int before=bulk.verified;confirmed+=bulk.observe(items,cursor);verification=bulk.diagnostic();
        for(int i=before;i<bulk.verified;i++){var move=bulk.moves.get(i);recordDeposit(move.stack(),move.destination());}
        if(bulk.complete())bulk=null;
    }
    private void recordDeposit(ItemStack stack,int slot){
        JsonArray boxes=BotStashScan.classify(List.of(stack),action);if(boxes.size()!=1||stack.getCount()!=1)throw new IllegalStateException("Confirmed deposit lacks exact kit evidence");
        JsonObject box=boxes.get(0).getAsJsonObject();box.addProperty("slot",slot);box.addProperty("x",target.getX());box.addProperty("y",target.getY());box.addProperty("z",target.getZ());depositedBoxes.add(box);
    }
    private void observeColumn(List<ItemStack> contents,int mode){
        JsonObject geometry=BotStashObserver.geometry(mc.level,target);if(geometry==null){geometry=new JsonObject();geometry.addProperty("block","minecraft:chest");}
        geometry=geometry.deepCopy();geometry.addProperty("x",target.getX());geometry.addProperty("y",target.getY());geometry.addProperty("z",target.getZ());
        JsonObject observation=BotStashObserver.observation(geometry,contents,"Observed during kit deposit");
        pendingFinding=new JsonObject();pendingFinding.add("observation",observation);findingMode=mode;hostWait=0;approved=false;
    }
    boolean acceptMenu(int id){
        if(target==null||beforeMenu<0||id==beforeMenu||finished||pendingFinding!=null)return false;
        if(bulk!=null&&!bulk.acceptsMenu(id))return false;
        if(pendingStage==2&&id==recoveryMenu)return false;
        if(menu<0)menu=id;
        if(menu<0||id!=menu)return false;
        menuReady=true;return true;
    }
    void beginDeposit(int carried,int destination,ItemStack stack,int tick,List<ItemStack> contents){
        if(bulk!=null||reconciling||pendingStage!=0||pendingBefore>=0)throw new IllegalStateException("Previous kit deposit still needs verification");
        if(destination<0||destination>=contents.size()||!contents.get(destination).isEmpty()||stack.getCount()!=1)throw new IllegalStateException("Kit deposit needs one kit and an empty server-confirmed destination");
        pendingBefore=carried;pendingDest=destination;pendingAt=tick;pendingStage=1;pendingStack=stack.copy();pendingContents=copyContents(contents);verificationMismatch="";
        pendingSource=-1;pendingInventory=List.of();recoveryAttempts=0;recoveryMenu=-1;serverAt=-1;verification=new JsonObject();
        verification.addProperty("startedTick",tick);verification.addProperty("before",carried);verification.addProperty("destinationSlot",destination);verification.add("expected",BotStashScan.classify(List.of(stack)));
    }
    void beginDeposit(int carried,int destination,ItemStack stack,int tick,List<ItemStack> contents,int source,List<ItemStack> inventory){
        if(source<0||source>=36||inventory.size()!=36||!ItemStack.matches(inventory.get(source),stack))throw new IllegalArgumentException("Deposit recovery needs the exact server-confirmed source inventory");
        beginDeposit(carried,destination,stack,tick,contents);pendingSource=source;pendingInventory=copyContents(inventory);verification.addProperty("sourceInventorySlot",source);
    }
    boolean canReopen(List<ItemStack> contents,List<ItemStack> inventory,ItemStack cursor){
        if(pendingStage!=1||recoveryAttempts>=2||pendingSource<0||inventory.size()!=36||contents.size()!=pendingContents.size())return false;
        if(cursor.isEmpty())return contents.get(pendingDest).isEmpty()&&restoredSource(inventory)==pendingSource;
        if(!ItemStack.matches(cursor,pendingStack)||!inventory.get(pendingSource).isEmpty())return false;
        for(int i=0;i<36;i++)if(i!=pendingSource&&!BotStashBulk.sameCargoSlot(pendingInventory.get(i),inventory.get(i)))return false;
        return true;
    }
    void beginReopen(int tick){
        if(pendingStage!=1||pendingSource<0||recoveryAttempts>=2)throw new IllegalStateException("Deposit reconciliation is unavailable or exhausted");
        recoveryMenu=menu;recoveryAttempts++;pendingStage=2;pendingAt=tick;menu=beforeMenu=-1;menuReady=false;verification.addProperty("reopenAttempts",recoveryAttempts);
    }
    boolean canRetry(List<ItemStack> contents,List<ItemStack> inventory,ItemStack cursor){
        return pendingStage==2&&menuReady&&menu>=0&&menu!=recoveryMenu&&pendingSource>=0&&cursor.isEmpty()&&contents.size()==pendingContents.size()
            &&contents.get(pendingDest).isEmpty()&&restoredSource(inventory)>=0;
    }
    boolean recoverOccupiedDestination(List<ItemStack> contents,List<ItemStack> inventory,ItemStack cursor){
        if(pendingStage!=2||!menuReady||menu<0||menu==recoveryMenu||pendingSource<0||!cursor.isEmpty()||inventory.size()!=36
            ||contents.size()!=pendingContents.size()||contents.get(pendingDest).isEmpty()||restoredSource(inventory)<0)return false;
        // The held kit returned; the occupied slot is not evidence that we deposited it.
        pendingBefore=pendingDest=pendingSource=-1;pendingStage=0;pendingStack=ItemStack.EMPTY;pendingContents=pendingInventory=List.of();
        verificationMismatch="";verification.addProperty("destinationReselected",true);openWait=0;return true;
    }
    private int restoredSource(List<ItemStack> inventory){
        if(pendingSource<0||inventory.size()!=36)return -1;
        boolean originalSource=ItemStack.matches(inventory.get(pendingSource),pendingStack);
        if(!originalSource&&BotStashBulk.shulker(inventory.get(pendingSource)))return -1;
        // Vanilla may return the held kit to another previously empty player slot on close.
        int returned=originalSource?pendingSource:-1;
        for(int i=0;i<36;i++)if(i!=pendingSource&&!BotStashBulk.sameCargoSlot(pendingInventory.get(i),inventory.get(i))){
            if(returned>=0||!pendingInventory.get(i).isEmpty()||!ItemStack.matches(inventory.get(i),pendingStack))return -1;
            returned=i;
        }
        return returned;
    }
    void retryDeposit(int tick,List<ItemStack> contents,List<ItemStack> inventory,ItemStack cursor){
        if(!canRetry(contents,inventory,cursor))throw new IllegalStateException("A fresh destination snapshot must prove the previous deposit was rolled back before retrying");
        pendingSource=restoredSource(inventory);pendingInventory=copyContents(inventory);pendingContents=copyContents(contents);pendingAt=tick;pendingStage=1;serverAt=-1;verificationMismatch="";
        verification.addProperty("retriedTick",tick);
        verification.addProperty("sourceInventorySlot",pendingSource);
    }
    private void recordVerification(List<ItemStack> contents,int carried,List<ItemStack> inventory,ItemStack cursor){
        verification=new JsonObject();verification.addProperty("startedTick",pendingAt);verification.addProperty("stage",pendingStage);verification.addProperty("reopenAttempts",recoveryAttempts);
        verification.addProperty("sourceInventorySlot",pendingSource);verification.addProperty("destinationSlot",pendingDest);verification.addProperty("before",pendingBefore);verification.addProperty("carried",carried);verification.addProperty("cursorCount",cursor.getCount());
        verification.add("cursor",BotStashScan.classify(List.of(cursor)));verification.add("expected",BotStashScan.classify(List.of(pendingStack)));
        verification.addProperty("cursorItem",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(cursor.getItem()).toString());
        verification.add("destination",CrewInventory.manifest(List.of(pendingDest<contents.size()?contents.get(pendingDest):ItemStack.EMPTY)));
        if(pendingSource>=0)verification.add("source",CrewInventory.manifest(List.of(inventory.get(pendingSource))));
        verification.addProperty("originalInventoryRestored",sameContents(inventory,pendingInventory));
    }
    void observeContents(List<ItemStack> contents,int carried){
        if(pendingStage==0||carried==pendingBefore)return;
        int destination=pendingDest;
        if(carried!=pendingBefore-1||destination>=contents.size()||!pendingContents.get(destination).isEmpty()||!ItemStack.matches(contents.get(destination),pendingStack)){
            // Refresh without replaying the move. A transient snapshot is not a final negative receipt.
            if(verification==null)verification=new JsonObject();
            ItemStack actual=pendingDest<contents.size()?contents.get(pendingDest):ItemStack.EMPTY;
            JsonArray exact=new JsonArray();for(int i=0;i<contents.size();i++)if(ItemStack.matches(contents.get(i),pendingStack))exact.add(i);
            JsonArray components=new JsonArray();Set<net.minecraft.core.component.DataComponentType<?>> keys=new HashSet<>(pendingStack.getComponents().keySet());keys.addAll(actual.getComponents().keySet());
            for(var key:keys)if(!Objects.equals(pendingStack.get(key),actual.get(key)))components.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(key)));
            verification.addProperty("slot",pendingDest);verification.addProperty("before",pendingBefore);verification.addProperty("carried",carried);verification.addProperty("actualItem",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(actual.getItem()).toString());verification.addProperty("actualCount",actual.getCount());verification.add("expected",BotStashScan.classify(List.of(pendingStack)));verification.add("actual",BotStashScan.classify(List.of(actual)));verification.add("exactSlots",exact);verification.add("differentComponents",components);
            JsonArray changes=new JsonArray();
            for(int i=0;i<contents.size();i++)if(i>=pendingContents.size()||!ItemStack.matches(pendingContents.get(i),contents.get(i))){
                JsonObject change=new JsonObject();change.addProperty("slot",i);change.add("before",BotStashScan.classify(List.of(i<pendingContents.size()?pendingContents.get(i):ItemStack.EMPTY)));change.add("after",BotStashScan.classify(List.of(contents.get(i))));
                JsonArray differences=new JsonArray();Set<net.minecraft.core.component.DataComponentType<?>> changedKeys=new HashSet<>(pendingStack.getComponents().keySet());changedKeys.addAll(contents.get(i).getComponents().keySet());
                for(var key:changedKeys)if(!Objects.equals(pendingStack.get(key),contents.get(i).get(key)))differences.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(key)));
                change.add("differentComponents",differences);changes.add(change);
            }
            verification.add("changes",changes);
            verificationMismatch="Carried kits "+pendingBefore+" -> "+carried+", slot "+pendingDest+"="+verification.get("actualItem").getAsString()+" x"+actual.getCount()+", exact kit slots="+exact+", differing components="+components;
            return;
        }
        verificationMismatch="";
        // This is the forced post-placement server snapshot, not client prediction or a delayed probe.
        // Verify now: hopper-fed storage is allowed to move this box afterwards.
        recordDeposit(pendingStack,pendingDest);confirmed++;pendingBefore=pendingDest=pendingSource=-1;pendingStage=0;pendingStack=ItemStack.EMPTY;pendingContents=pendingInventory=List.of();
    }
    static List<ItemStack> copyContents(List<ItemStack> contents){return contents.stream().map(ItemStack::copy).toList();}
    static boolean sameContents(List<ItemStack> before,List<ItemStack> after){
        if(before.size()!=after.size())return false;
        for(int i=0;i<before.size();i++)if(!ItemStack.matches(before.get(i),after.get(i)))return false;
        return true;
    }
    private int countCarried(){int count=0;for(int i=0;i<36;i++)if(matches(mc.player.getInventory().getItem(i)))count+=mc.player.getInventory().getItem(i).getCount();return count;}
    private List<ItemStack> playerContents(){List<ItemStack> items=new ArrayList<>();for(int i=0;i<36;i++)items.add(mc.player.getInventory().getItem(i));return items;}
    private boolean matches(ItemStack stack){return BotStashScan.matchesKit(stack,action);}
    static boolean compatibleContents(List<ItemStack> contents,String kit){
        return compatibleContents(contents,Set.of(kit));
    }
    static boolean compatibleContents(List<ItemStack> contents,Set<String> kits){
        for(ItemStack stack:contents){if(stack.isEmpty())continue;JsonArray boxes=BotStashScan.classify(List.of(stack));if(boxes.isEmpty()||!kits.contains(StashCatalog.kitTypeId(boxes.get(0).getAsJsonObject())))return false;}return true;
    }
    static boolean compatibleContents(List<ItemStack> contents,JsonObject action){
        return compatibleContents(contents,action,null);
    }
    static boolean compatibleContents(List<ItemStack> contents,JsonObject action,JsonObject category){
        Set<String> kits=StashCatalog.columnKitTypes(action);for(ItemStack stack:contents){if(stack.isEmpty())continue;JsonArray boxes=BotStashScan.classify(List.of(stack),action);if(boxes.isEmpty())return false;JsonObject box=boxes.get(0).getAsJsonObject();String id=StashCatalog.kitTypeId(box);if(category==null?!kits.contains(id):!dev.monocle.coordinator.TaskWire.flag(box,"contentsKnown")||!KitCategories.matches(category,id,box.getAsJsonObject("items")))return false;}return true;
    }
    boolean waitingForDrain(int ticks){return ++fullWait<=ticks;}
    private BlockPos findChest(){
        BlockPos min=new BlockPos(action.get("minX").getAsInt(),action.get("minY").getAsInt(),action.get("minZ").getAsInt());
        BlockPos max=new BlockPos(action.get("maxX").getAsInt(),action.get("maxY").getAsInt(),action.get("maxZ").getAsInt());
        hopperRefillTicks=0;
        if(column!=null){
            BlockPos anchor=new BlockPos(column.get("x").getAsInt(),column.get("y").getAsInt(),column.get("z").getAsInt());
            var chain=BotStashScan.hopperColumn(mc.level,anchor,action);
            if(chain.fed()){
                Set<Long> skipped=new HashSet<>(tried);for(BlockPos p:chain.chests())if(Bots.get().stashObserver.knownOccupancy(action,p,true))skipped.add(p.asLong());
                hopperRefillTicks=chain.refillTicks();return nextColumnChest(chain,column,skipped);
            }
        }
        List<BlockEntity> eligible=new ArrayList<>();
        for(BlockEntity entity:Utils.blockEntities()){
            BlockPos p=entity.getBlockPos();if(column==null||!StashCatalog.columnContains(column,p.getX(),p.getY(),p.getZ())||Bots.get().stashObserver.knownOccupancy(action,p,true))continue;
            var state=mc.level.getBlockState(p);if(state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock&&state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)!=net.minecraft.world.level.block.state.properties.ChestType.SINGLE){BlockPos other=p.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));if(StashCatalog.contains(action,other.getX(),other.getY(),other.getZ())&&other.asLong()<p.asLong())continue;}
            eligible.add(entity);
        }
        BlockPos result=destinationChest(eligible,min,max,mc.player.position(),tried,dev.monocle.coordinator.TaskWire.flag(action,"assumeEmpty"));
        if(result==null&&column!=null){BlockPos anchor=new BlockPos(column.get("x").getAsInt(),column.get("y").getAsInt(),column.get("z").getAsInt());if(!tried.contains(anchor.asLong())&&!Bots.get().stashObserver.knownOccupancy(action,anchor,true))return anchor;}
        return result;
    }
    static BlockPos nextColumnChest(BotStashScan.HopperColumn chain,JsonObject column,Set<Long> tried){
        for(BlockPos p:chain.chests())if(!StashCatalog.columnContains(column,p.getX(),p.getY(),p.getZ()))
            throw new IllegalStateException("Hopper chain leaves the reserved destination column; no kit deposited");
        for(int i=chain.chests().size()-1;i>=0;i--){BlockPos p=chain.chests().get(i);if(!tried.contains(p.asLong()))return p;}
        return null;
    }
    static BlockPos nearestChest(Iterable<? extends BlockEntity> entities,BlockPos min,BlockPos max,Vec3 from,Set<Long> tried){
        return destinationChest(entities,min,max,from,tried,false);
    }
    static BlockPos destinationChest(Iterable<? extends BlockEntity> entities,BlockPos min,BlockPos max,Vec3 from,Set<Long> tried,boolean topFirst){
        AABB bounds=new AABB(min.getX(),min.getY(),min.getZ(),max.getX()+1d,max.getY()+1d,max.getZ()+1d);BlockPos nearest=null;double distance=Double.POSITIVE_INFINITY;
        for(BlockEntity entity:entities){
            BlockPos p=entity.getBlockPos();
            if(!(entity instanceof ChestBlockEntity)||tried.contains(p.asLong())||!bounds.contains(Vec3.atCenterOf(p)))continue;
            double candidate=Vec3.atCenterOf(p).distanceToSqr(from);
            if(nearest==null||topFirst&&p.getY()>nearest.getY()||(!topFirst||p.getY()==nearest.getY())&&candidate<distance){nearest=p.immutable();distance=candidate;}
        }
        return nearest;
    }
    private void open(){
        beforeMenu=mc.player.containerMenu.containerId;
        BotStashScan.openContainer(target);
    }
    private void requestSync(){mc.getConnection().send(HighwayBuilder.cursorSyncRequest(menu,HashedStack.create(mc.player.containerMenu.getCarried(),mc.getConnection().decoratedHashOpsGenenerator())));}
    void close(){if(mc.player!=null&&mc.player.containerMenu!=mc.player.inventoryMenu)mc.player.closeContainer();menu=beforeMenu=-1;menuReady=false;openWait=fullWait=0;serverContents=serverInventory=List.of();serverCarried=serverAt=serverState=-1;serverCursor=ItemStack.EMPTY;}
}
