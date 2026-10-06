package dev.monocle.client.systems.bots;

import com.google.gson.*;
import dev.monocle.client.events.packets.InventoryEvent;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.coordinator.StashCatalog;
import dev.monocle.client.systems.modules.misc.swarm.CrewInventory;
import net.minecraft.network.HashedStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.*;
import java.util.*;
import static dev.monocle.client.MonocleClient.mc;

/** Deposits a bounded number of exact kit boxes; uncertain clicks stop instead of being replayed. */
final class BotStashDeposit {
    private final JsonObject action;
    private final Set<Long> tried=new HashSet<>();
    private final JsonArray touched=new JsonArray();
    private BlockPos target;
    private JsonObject column,pendingFinding=new JsonObject();
    private int delivery=1,findingMode,hostWait;
    private boolean approved;
    private String failure="",verificationMismatch="";
    private int menu=-1,confirmed,pendingBefore=-1,pendingDest=-1,pendingAt,openWait,pendingStage;
    private ItemStack pendingStack=ItemStack.EMPTY;
    private List<ItemStack> serverContents=List.of(),pendingContents=List.of();
    private int serverCarried=-1;
    private JsonObject verification;
    private boolean finished,menuReady;
    BotStashDeposit(JsonObject action){this.action=action;}
    boolean done(){return finished&&mc.player.containerMenu==mc.player.inventoryMenu;}
    boolean approaching(){return target!=null&&(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target))>20.25||!BotStashScan.visibleFrom(mc.player.getEyePosition(),target));}
    BlockPos target(){return target;}
    boolean hasTouched(){return !touched.isEmpty();}
    String detail(){return "Storing kit "+confirmed+"/"+action.get("count").getAsInt()+(pendingFinding!=null?" · waiting for host column reservation/observation acknowledgement":target==null?" · locating a reserved-column chest":" · chest "+target.toShortString())+(pendingStage>0?" · verifying chest contents":"");}
    JsonObject pending(){return pendingFinding==null?null:pendingFinding.deepCopy();}
    int delivery(){return delivery;}
    void acknowledge(JsonObject ack){
        if(pendingFinding==null||!ack.has("deposit")||!ack.get("deposit").getAsBoolean()||ack.get("delivery").getAsInt()!=delivery)return;
        if(ack.has("error")){failure=ack.get("error").getAsString();pendingFinding=null;return;}
        JsonObject next=StashCatalog.checkedColumn(action,ack.getAsJsonObject("column"));boolean changed=column==null||!column.equals(next);
        column=next;approved=findingMode==1&&ack.has("permit")&&ack.get("permit").getAsBoolean()&&!changed;
        pendingFinding=null;hostWait=0;delivery++;
        if(changed){tried.clear();target=null;close();}else if(findingMode==1&&!approved){target=null;close();}
        if(findingMode==2)approved=true;
    }
    JsonObject result(){JsonObject value=new JsonObject();value.addProperty("stash",action.get("name").getAsString());if(action.has("catalogCrew"))value.add("catalogCrew",action.get("catalogCrew").deepCopy());value.add("touched",touched.deepCopy());return value;}
    JsonObject snapshot(){JsonObject s=new JsonObject();s.addProperty("confirmed",confirmed);s.addProperty("pending",pendingBefore>=0);s.addProperty("finished",finished);s.add("touched",touched.deepCopy());s.addProperty("delivery",delivery);s.addProperty("findingMode",findingMode);if(column!=null)s.add("column",column.deepCopy());if(pendingFinding!=null)s.add("finding",pendingFinding.deepCopy());if(verification!=null)s.add("verification",verification.deepCopy());JsonArray skipped=new JsonArray();tried.forEach(skipped::add);s.add("tried",skipped);return s;}
    void restore(JsonObject s){if(s.get("pending").getAsBoolean())throw new IllegalStateException("Kit deposit was interrupted before all destination contents were verified; inspect the target chest before retrying");confirmed=StashCatalog.integer(s,"confirmed",0,action.get("count").getAsInt());finished=s.get("finished").getAsBoolean();touched.addAll(s.getAsJsonArray("touched"));if(s.has("delivery"))delivery=StashCatalog.integer(s,"delivery",1,4096);if(s.has("column"))column=StashCatalog.checkedColumn(action,s.getAsJsonObject("column"));findingMode=s.has("findingMode")?StashCatalog.integer(s,"findingMode",0,2):0;pendingFinding=s.has("finding")?s.getAsJsonObject("finding").deepCopy():new JsonObject();if(s.has("tried")){if(s.getAsJsonArray("tried").size()>4096)throw new IllegalArgumentException("Too many destination chest checkpoints");for(JsonElement p:s.getAsJsonArray("tried"))tried.add(p.getAsLong());}}
    void tick(){
        if(!failure.isEmpty())throw new IllegalStateException(failure);
        if(done())return;
        if(pendingFinding!=null){if(++hostWait>200)throw new IllegalStateException("Host did not acknowledge the destination column; no further kit was deposited");return;}
        if(confirmed>=action.get("count").getAsInt()){finished=true;close();return;}
        if(target==null){target=findChest();approved=false;if(target==null){pendingFinding=new JsonObject();pendingFinding.addProperty("exhaustedColumn",true);findingMode=0;hostWait=0;return;}}
        if(approaching())return;
        if(pendingStage>0){
            if(mc.player.tickCount-pendingAt>100)throw new IllegalStateException("Kit deposit verification timed out at "+target+" (menu "+menu+", stage "+pendingStage+")"+(verificationMismatch.isEmpty()?"":"; "+verificationMismatch)+"; inspect it before retrying");
            if(mc.player.containerMenu==mc.player.inventoryMenu||mc.player.containerMenu.containerId!=menu)return;
            if((mc.player.tickCount-pendingAt)%10==0)requestSync();
            return;
        }
        if(mc.player.containerMenu==mc.player.inventoryMenu){if(++openWait%20==1)open();return;}
        if(menu<0)menu=mc.player.containerMenu.containerId;
        if(mc.player.containerMenu.containerId!=menu)return;
        if(!menuReady){if(++openWait>100)throw new IllegalStateException("Destination chest never sent its contents; no kit was moved");if(openWait%10==0)requestSync();return;}
        if(!approved){requestSync();return;}
        List<ItemStack> contents=new ArrayList<>();for(var slot:mc.player.containerMenu.slots){if(slot.container==mc.player.getInventory())break;contents.add(slot.getItem());}
        // Local click prediction and late resync packets can disagree. Never use prediction as the next move's baseline.
        if(!mc.player.containerMenu.getCarried().isEmpty()||countCarried()!=serverCarried||!sameContents(contents,serverContents)){if(++openWait>100)throw new IllegalStateException("Destination inventory did not reconcile with its server snapshot; no further kit was moved");if(openWait%10==1)requestSync();return;}
        openWait=0;
        if(!compatibleContents(contents,action.get("kitTypeId").getAsString())){observeColumn(contents,1);return;}
        int empty=-1;
        for(int i=0;i<mc.player.containerMenu.slots.size();i++){var slot=mc.player.containerMenu.getSlot(i);if(slot.container==mc.player.getInventory())break;if(!slot.hasItem()){empty=i;break;}}
        if(empty<0){tried.add(target.asLong());close();target=null;return;}
        int source=-1;for(int i=0;i<36;i++)if(matches(mc.player.getInventory().getItem(i))){source=i;break;}
        if(source<0)throw new IllegalStateException("The requested kit is no longer carried; no deposit packet sent");
        beginDeposit(serverCarried,empty,mc.player.getInventory().getItem(source),mc.player.tickCount,serverContents);
        JsonObject position=new JsonObject();position.addProperty("x",target.getX());position.addProperty("y",target.getY());position.addProperty("z",target.getZ());
        if(touched.asList().stream().noneMatch(v->v.getAsJsonObject().equals(position)))touched.add(position);
        InvUtils.moveConfirmed(source,empty);
    }
    void inventory(InventoryEvent event){
        if(mc.player.containerMenu==mc.player.inventoryMenu||event.packet.containerId()!=mc.player.containerMenu.containerId)return;
        if(event.packet.items().size()!=mc.player.containerMenu.slots.size())return;
        if(!acceptMenu(event.packet.containerId()))return;
        if(pendingFinding!=null)return;
        int carried=0;for(int i=0;i<Math.min(event.packet.items().size(),mc.player.containerMenu.slots.size());i++)if(mc.player.containerMenu.getSlot(i).container==mc.player.getInventory()&&matches(event.packet.items().get(i)))carried+=event.packet.items().get(i).getCount();
        int slots=0;while(slots<mc.player.containerMenu.slots.size()&&mc.player.containerMenu.getSlot(slots).container!=mc.player.getInventory())slots++;
        List<ItemStack> contents=event.packet.items().subList(0,slots);
        serverContents=copyContents(contents);serverCarried=carried;
        if(pendingStage==0&&!approved){observeColumn(contents,1);return;}
        if(pendingStage!=1)return;
        if(pendingStage==1&&carried!=pendingBefore){
            verification=new JsonObject();verification.addProperty("menu",event.packet.containerId());verification.addProperty("stateId",event.packet.stateId());verification.addProperty("cursorCount",event.packet.carriedItem().getCount());
        }
        if(!event.packet.carriedItem().isEmpty())return;
        observeContents(contents,carried);
        if(pendingStage==0&&(confirmed>=action.get("count").getAsInt()||contents.stream().noneMatch(ItemStack::isEmpty)))observeColumn(contents,2);
    }
    private void observeColumn(List<ItemStack> contents,int mode){
        JsonObject observation=new JsonObject();observation.addProperty("x",target.getX());observation.addProperty("y",target.getY());observation.addProperty("z",target.getZ());observation.addProperty("block","minecraft:chest");observation.addProperty("status","observed");observation.addProperty("reason","Observed during kit deposit");observation.add("items",CrewInventory.manifest(contents));observation.add("shulkers",BotStashScan.classify(contents));
        pendingFinding=new JsonObject();pendingFinding.add("observation",observation);findingMode=mode;hostWait=0;approved=false;
    }
    boolean acceptMenu(int id){
        if(pendingStage==0)menu=id;
        if(menu<0||id!=menu)return false;
        menuReady=true;return true;
    }
    void beginDeposit(int carried,int destination,ItemStack stack,int tick,List<ItemStack> contents){
        if(pendingStage!=0||pendingBefore>=0)throw new IllegalStateException("Previous kit deposit still needs verification");
        if(destination<0||destination>=contents.size()||!contents.get(destination).isEmpty()||stack.getCount()!=1)throw new IllegalStateException("Kit deposit needs one kit and an empty server-confirmed destination");
        pendingBefore=carried;pendingDest=destination;pendingAt=tick;pendingStage=1;pendingStack=stack.copy();pendingContents=copyContents(contents);verificationMismatch="";
    }
    void observeContents(List<ItemStack> contents,int carried){
        if(pendingStage!=1||carried==pendingBefore)return;
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
        confirmed++;pendingBefore=pendingDest=-1;pendingStage=0;pendingStack=ItemStack.EMPTY;pendingContents=List.of();
    }
    static List<ItemStack> copyContents(List<ItemStack> contents){return contents.stream().map(ItemStack::copy).toList();}
    static boolean sameContents(List<ItemStack> before,List<ItemStack> after){
        if(before.size()!=after.size())return false;
        for(int i=0;i<before.size();i++)if(!ItemStack.matches(before.get(i),after.get(i)))return false;
        return true;
    }
    private int countCarried(){int count=0;for(int i=0;i<36;i++)if(matches(mc.player.getInventory().getItem(i)))count+=mc.player.getInventory().getItem(i).getCount();return count;}
    private boolean matches(ItemStack stack){return BotStashScan.matchesKit(stack,action);}
    static boolean compatibleContents(List<ItemStack> contents,String kit){
        for(ItemStack stack:contents){if(stack.isEmpty())continue;JsonArray boxes=BotStashScan.classify(List.of(stack));if(boxes.isEmpty()||!kit.equals(StashCatalog.kitTypeId(boxes.get(0).getAsJsonObject())))return false;}return true;
    }
    private BlockPos findChest(){
        BlockPos min=new BlockPos(action.get("minX").getAsInt(),action.get("minY").getAsInt(),action.get("minZ").getAsInt());
        BlockPos max=new BlockPos(action.get("maxX").getAsInt(),action.get("maxY").getAsInt(),action.get("maxZ").getAsInt());
        List<BlockEntity> eligible=new ArrayList<>();
        for(BlockEntity entity:Utils.blockEntities()){
            BlockPos p=entity.getBlockPos();if(column==null||!StashCatalog.columnContains(column,p.getX(),p.getY(),p.getZ()))continue;
            var state=mc.level.getBlockState(p);if(state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock&&state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)!=net.minecraft.world.level.block.state.properties.ChestType.SINGLE){BlockPos other=p.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));if(StashCatalog.contains(action,other.getX(),other.getY(),other.getZ())&&other.asLong()<p.asLong())continue;}
            eligible.add(entity);
        }
        BlockPos result=nearestChest(eligible,min,max,mc.player.position(),tried);
        if(result==null&&column!=null){BlockPos anchor=new BlockPos(column.get("x").getAsInt(),column.get("y").getAsInt(),column.get("z").getAsInt());if(!tried.contains(anchor.asLong()))return anchor;}
        return result;
    }
    static BlockPos nearestChest(Iterable<? extends BlockEntity> entities,BlockPos min,BlockPos max,Vec3 from,Set<Long> tried){
        AABB bounds=new AABB(min.getX(),min.getY(),min.getZ(),max.getX()+1d,max.getY()+1d,max.getZ()+1d);BlockPos nearest=null;double distance=Double.POSITIVE_INFINITY;
        for(BlockEntity entity:entities){
            BlockPos p=entity.getBlockPos();
            if(!(entity instanceof ChestBlockEntity)||tried.contains(p.asLong())||!bounds.contains(Vec3.atCenterOf(p)))continue;
            double candidate=Vec3.atCenterOf(p).distanceToSqr(from);
            if(candidate<distance){nearest=p.immutable();distance=candidate;}
        }
        return nearest;
    }
    private void open(){
        BotStashScan.openContainer(target);
    }
    private void requestSync(){mc.getConnection().send(HighwayBuilder.cursorSyncRequest(menu,HashedStack.create(mc.player.containerMenu.getCarried(),mc.getConnection().decoratedHashOpsGenenerator())));}
    void close(){if(mc.player!=null&&mc.player.containerMenu!=mc.player.inventoryMenu)mc.player.closeContainer();menu=-1;menuReady=false;openWait=0;serverContents=List.of();serverCarried=-1;}
}
