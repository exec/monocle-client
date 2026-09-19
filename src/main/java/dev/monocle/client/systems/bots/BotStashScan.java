package dev.monocle.client.systems.bots;

import com.google.gson.*;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.packets.InventoryEvent;
import dev.monocle.client.systems.modules.misc.swarm.CrewInventory;
import dev.monocle.coordinator.StashCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.*;
import java.util.*;
import static dev.monocle.client.MonocleClient.mc;

/** Read-only native scanner. One outstanding observation; the host must durably save it before scanning continues. */
public final class BotStashScan {
    public static final String WORKFLOW="task-stash-scan";
    private final JsonObject plan;
    private int cursor,discovered,observed,inferred,unscanned,waitTicks,attempts,menu=-1,beforeMenu=-1,delivery=1;
    private final Set<Long> missingChunks=new HashSet<>();
    private BlockPos target,stance;
    private JsonObject pending;
    private List<ItemStack> captured;
    private int closeTicks;
    private JsonObject bottomTemplate,topTemplate,lazyTemplate;
    private boolean bottomUniform=true,topUniform=true;
    private String phase="Discovering",reason="Looking for containers",lastAction="Scan started";
    private long lastProgress=System.currentTimeMillis();
    private String scope="";
    private static String currentScope(){return mc.level==null?"":(mc.getCurrentServer()==null?"local":mc.getCurrentServer().ip)+"\n"+mc.level.dimension().identifier();}
    private int previousSlot=-1,selectedSlot=-1,previousMissing;
    public BotStashScan(JsonObject source){plan=StashCatalog.plan(source);}
    private int volume(){return (plan.get("maxX").getAsInt()-plan.get("minX").getAsInt()+1)*(plan.get("maxY").getAsInt()-plan.get("minY").getAsInt()+1)*(plan.get("maxZ").getAsInt()-plan.get("minZ").getAsInt()+1);}
    static int layerY(int layer,int layers){return layer==0?0:layer==1?layers-1:layers-layer;}
    private BlockPos cell(int i){int w=plan.get("maxX").getAsInt()-plan.get("minX").getAsInt()+1,d=plan.get("maxZ").getAsInt()-plan.get("minZ").getAsInt()+1,layers=plan.get("maxY").getAsInt()-plan.get("minY").getAsInt()+1,layer=i/w/d;return new BlockPos(plan.get("minX").getAsInt()+i%w,plan.get("minY").getAsInt()+layerY(layer,layers),plan.get("minZ").getAsInt()+i/w%d);}
    private boolean loaded(BlockPos p){return mc.level.getChunkSource().hasChunk(p.getX()>>4,p.getZ()>>4);}
    static boolean includeContainer(boolean lazyMode,boolean hopper){return !lazyMode||!hopper;}
    public boolean done(){return cursor>=volume()&&target==null&&pending==null;}
    public BlockPos target(){return target;}
    public BlockPos stance(){return stance;}
    public boolean approaching(){return phase.equals("Approaching")&&target!=null;}
    public boolean targetIsHopper(){return target!=null&&mc.level.getBlockEntity(target) instanceof HopperBlockEntity;}
    public BlockPos navigationTarget(){
        if(!targetIsHopper())return target;
        List<BlockPos> perches=new ArrayList<>();
        for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL){BlockPos chest=target.relative(direction);if(mc.level.getBlockState(chest).getBlock() instanceof ChestBlock&&mc.level.getBlockState(chest.above()).isAir()&&mc.level.getBlockState(chest.above(2)).isAir())perches.add(chest.above());}
        if(mc.level.getBlockState(target.below()).getBlock() instanceof ChestBlock&&mc.level.getBlockState(target.above()).isAir()&&mc.level.getBlockState(target.above(2)).isAir())perches.add(target.above());
        return perches.stream().min(Comparator.comparingDouble(p->Vec3.atBottomCenterOf(p).distanceToSqr(mc.player.position()))).orElse(target);
    }
    public boolean hasHopperPerch(){return targetIsHopper()&&!navigationTarget().equals(target);}
    public boolean suppressScreen(){return target!=null&&beforeMenu>=0&&pending==null;}
    public String detail(){return phase+": "+reason+" · "+observed+" observed, "+unscanned+" unscanned";}
    private void progress(String action){lastAction=action;lastProgress=System.currentTimeMillis();}
    public void tick(){
        if(scope.isEmpty())scope=currentScope();
        if(!scope.equals(currentScope()))throw new IllegalStateException("Stash scan belongs to another world");
        if(pending!=null){phase="Awaiting host save";reason="Observation "+delivery+" awaiting durable acknowledgement";return;}
        if(captured!=null){phase="Confirming";reason="Allowing the server menu to settle";if(++closeTicks>=2){List<ItemStack> items=captured;captured=null;finish(items,"");}return;}
        if(target==null){
            phase="Discovering";reason="Looking for containers";
            for(int budget=0;budget<512&&cursor<volume();budget++){
                BlockPos p=cell(cursor++);
                if(!loaded(p)){missingChunks.add(new BlockPos(p.getX()>>4,0,p.getZ()>>4).asLong());continue;}
                if(!(mc.level.getBlockEntity(p) instanceof BaseContainerBlockEntity entity)||!includeContainer(plan.get("lazyMode").getAsBoolean(),entity instanceof HopperBlockEntity))continue;
                var state=mc.level.getBlockState(p);
                if(state.getBlock() instanceof ChestBlock&&state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE){
                    BlockPos other=p.relative(ChestBlock.getConnectedDirection(state));
                    if(StashCatalog.contains(plan,other.getX(),other.getY(),other.getZ())&&other.asLong()<p.asLong())continue;
                }
                if(!StashCatalog.owns(plan,p.getX(),p.getY(),p.getZ()))continue;
                if(++discovered>4096)throw new IllegalStateException("Scan exceeded 4096 containers");
                target=p;stance=null;waitTicks=attempts=0;menu=beforeMenu=-1;progress("Found container at "+p.toShortString());break;
            }
            if(target==null){if(cursor>=volume()){phase="Complete";reason=unscanned==0&&previousMissing+missingChunks.size()==0?"Every discovered container observed":"Scan finished with gaps; inspect unscanned containers and missing chunks";}return;}
        }
        if(infer()){return;}
        if(++waitTicks>200){finish(null,"No reachable opening or server contents within ten seconds");return;}
        if(!loaded(target)){phase="Waiting for chunks";reason="Container chunk is not received";return;}
        if(!(mc.level.getBlockEntity(target) instanceof BaseContainerBlockEntity)){finish(null,"Container disappeared or changed");return;}
        if(mc.player.containerMenu!=mc.player.inventoryMenu){if(beforeMenu>=0)menu=mc.player.containerMenu.containerId;phase="Reading";reason=menu>=0?"Waiting for server-confirmed container contents":"Waiting for another inventory screen to close";return;}
        if(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target))>4.5*4.5){
            stance=target;
            phase="Approaching";reason="Baritone finding a read-only route to "+target.toShortString();return;
        }
        stance=null;phase="Opening";reason="Requesting container contents";
        if(waitTicks%40==1&&attempts++<3){
            int safe=-1;
            for(int i=0;i<9;i++){ItemStack held=mc.player.getInventory().getItem(i);if(held.isEmpty()||held.is(net.minecraft.tags.ItemTags.PICKAXES)){safe=i;break;}}
            if(safe<0){finish(null,"Keep a pickaxe or empty slot in the hotbar to open containers without placing held blocks");return;}
            if(previousSlot<0)previousSlot=mc.player.getInventory().getSelectedSlot();selectedSlot=safe;
            mc.player.getInventory().setSelectedSlot(safe);
            beforeMenu=mc.player.inventoryMenu.containerId;
            mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(target),net.minecraft.core.Direction.UP,target,false));
            progress("Requested opening at "+target.toShortString());
        }
    }
    public void moved(){waitTicks=0;progress("Approaching container");}
    public void inventory(InventoryEvent event){
        if(target==null||pending!=null||beforeMenu<0||mc.player==null||!scope.equals(currentScope())||mc.player.containerMenu==mc.player.inventoryMenu
            ||event.packet.containerId()!=mc.player.containerMenu.containerId||event.packet.containerId()==beforeMenu)return;
        menu=event.packet.containerId();
        List<ItemStack> items=new ArrayList<>();
        for(var slot:mc.player.containerMenu.slots)if(slot.container!=mc.player.getInventory()&&!slot.isFake())items.add(slot.getItem().copy());
        if(items.isEmpty()||items.size()>216){finish(null,"Unsupported container menu");return;}
        captured=items;closeTicks=0;phase="Confirming";reason="Server contents received";
    }
    private void finish(List<ItemStack> items,String failure){
        JsonObject o=new JsonObject();o.addProperty("x",target.getX());o.addProperty("y",target.getY());o.addProperty("z",target.getZ());
        o.addProperty("block",BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(target).getBlock()).toString());
        o.addProperty("status",items==null?"unscanned":"observed");o.addProperty("reason",failure);
        JsonObject totals=items==null?new JsonObject():CrewInventory.manifest(items);JsonArray boxes=classify(items==null?List.of():items);
        for(var value:boxes){JsonObject b=value.getAsJsonObject();if(b.get("legacy").getAsBoolean())b.getAsJsonObject("items").entrySet().forEach(i->totals.addProperty(i.getKey(),(totals.has(i.getKey())?totals.get(i.getKey()).getAsInt():0)+i.getValue().getAsInt()*b.get("quantity").getAsInt()));}
        o.add("items",totals);o.add("shulkers",boxes);
        if(items!=null)learn(o,items);else {if(target.getY()==plan.get("minY").getAsInt())bottomUniform=false;if(target.getY()==plan.get("maxY").getAsInt())topUniform=false;lazyTemplate=null;}
        save(o,items==null,failure);
    }
    private void save(JsonObject o,boolean missed,String failure){
        StashCatalog.save(MonocleClient.FOLDER.toPath(),"Local",scope,plan,o);
        pending=StashCatalog.observation(plan,o);if(missed)unscanned++;else {observed++;if(o.has("inferred"))inferred++;}
        progress(missed?"Container unscanned: "+failure:o.has("inferred")?"Estimated container from matching bookend layers":"Saved server-confirmed contents");close();phase="Awaiting host save";
    }
    private void learn(JsonObject o,List<ItemStack> items){
        boolean full=!items.isEmpty()&&items.stream().allMatch(s->!s.isEmpty()&&s.getCount()>=s.getMaxStackSize())&&o.getAsJsonObject("items").size()==1;
        JsonObject sample=full?o.deepCopy():null;int y=target.getY(),min=plan.get("minY").getAsInt(),max=plan.get("maxY").getAsInt();
        if(y==min){if(sample==null||bottomTemplate!=null&&!same(bottomTemplate,sample))bottomUniform=false;else if(bottomTemplate==null)bottomTemplate=sample;}
        if(y==max){if(sample==null||topTemplate!=null&&!same(topTemplate,sample))topUniform=false;else if(topTemplate==null)topTemplate=sample;if(bottomUniform&&topUniform&&bottomTemplate!=null&&topTemplate!=null&&same(bottomTemplate,topTemplate))lazyTemplate=bottomTemplate;else lazyTemplate=null;}
    }
    private static boolean same(JsonObject a,JsonObject b){return a.get("block").equals(b.get("block"))&&a.get("items").equals(b.get("items"))&&a.get("shulkers").equals(b.get("shulkers"));}
    private boolean infer(){
        if(!plan.get("lazyMode").getAsBoolean()||lazyTemplate==null||target.getY()==plan.get("minY").getAsInt()||target.getY()==plan.get("maxY").getAsInt()||!BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(target).getBlock()).toString().equals(lazyTemplate.get("block").getAsString()))return false;
        JsonObject o=lazyTemplate.deepCopy();o.addProperty("x",target.getX());o.addProperty("y",target.getY());o.addProperty("z",target.getZ());o.addProperty("inferred",true);o.addProperty("reason","Lazy Mode: matching full homogeneous bottom and top layers");save(o,false,"");return true;
    }
    static JsonArray classify(List<ItemStack> items){
        JsonArray boxes=new JsonArray();
        for(int i=0;i<items.size();i++){
            ItemStack stack=items.get(i);if(!(stack.getItem() instanceof net.minecraft.world.item.BlockItem b)||!(b.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock))continue;
            JsonObject box=new JsonObject();box.addProperty("slot",i);box.addProperty("item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());box.addProperty("quantity",stack.getCount());
            box.addProperty("name",stack.has(DataComponents.CUSTOM_NAME)?stack.getHoverName().getString().substring(0,Math.min(128,stack.getHoverName().getString().length())):"");
            ItemStack[] contents=new ItemStack[27];Arrays.fill(contents,ItemStack.EMPTY);
            if(stack.has(DataComponents.CONTAINER)){
                var stored=stack.get(DataComponents.CONTAINER).allItemsCopyStream().limit(27).toList();
                for(int slot=0;slot<stored.size();slot++)contents[slot]=stored.get(slot);
            }else if(stack.has(DataComponents.BLOCK_ENTITY_DATA))dev.monocle.client.utils.Utils.getItemsInContainerItem(stack,contents);
            JsonObject manifest=CrewInventory.manifest(Arrays.asList(contents));box.add("items",manifest);
            box.addProperty("dominant",manifest.entrySet().stream().max(Comparator.comparingLong(e->e.getValue().getAsLong())).map(Map.Entry::getKey).orElse(""));
            box.addProperty("mixed",manifest.size()>1);box.addProperty("contentsKnown",stack.has(DataComponents.CONTAINER)||stack.has(DataComponents.BLOCK_ENTITY_DATA));box.addProperty("legacy",!stack.has(DataComponents.CONTAINER)&&stack.has(DataComponents.BLOCK_ENTITY_DATA));boxes.add(box);
        }
        return boxes;
    }
    public void close(){captured=null;closeTicks=0;if(mc.player!=null){if(menu>=0&&mc.player.containerMenu.containerId==menu)mc.player.closeContainer();if(previousSlot>=0&&mc.player.getInventory().getSelectedSlot()==selectedSlot)mc.player.getInventory().setSelectedSlot(previousSlot);}menu=beforeMenu=previousSlot=selectedSlot=-1;}
    public JsonObject pending(){return pending==null?null:pending.deepCopy();}
    public int delivery(){return delivery;}
    public void acknowledge(int id){if(pending!=null&&id==delivery){pending=null;target=stance=null;delivery++;progress("Host saved observation");}}
    public JsonObject telemetry(){
        JsonObject t=new JsonObject();t.addProperty("name",plan.get("name").getAsString());t.addProperty("phase",phase);t.addProperty("reason",reason);t.addProperty("discovery",cursor);t.addProperty("volume",volume());
        t.addProperty("discovered",discovered);t.addProperty("observed",observed-inferred);t.addProperty("inferred",inferred);t.addProperty("unscanned",unscanned);t.addProperty("missingChunks",previousMissing+missingChunks.size());
        t.addProperty("target",target==null?"":target.toShortString());t.addProperty("movementTarget",stance==null?"":stance.toShortString());t.addProperty("lastAction",lastAction);t.addProperty("lastProgressAt",lastProgress);t.addProperty("menu",menu);t.addProperty("attempts",attempts);return t;
    }
    public JsonObject snapshot(){JsonObject s=telemetry();s.addProperty("cursor",cursor);s.addProperty("delivery",delivery);if(target!=null){s.addProperty("targetX",target.getX());s.addProperty("targetY",target.getY());s.addProperty("targetZ",target.getZ());}if(pending!=null)s.add("pending",pending.deepCopy());return s;}
    public void restore(JsonObject s){
        cursor=StashCatalog.integer(s,"cursor",0,volume());delivery=StashCatalog.integer(s,"delivery",1,4097);
        discovered=StashCatalog.integer(s,"discovered",0,4096);int reported=StashCatalog.integer(s,"observed",0,4096);inferred=s.has("inferred")?StashCatalog.integer(s,"inferred",0,4096):0;observed=reported+inferred;unscanned=StashCatalog.integer(s,"unscanned",0,4096);
        previousMissing=StashCatalog.integer(s,"missingChunks",0,1_048_576);
        if(s.has("targetX")){target=new BlockPos(StashCatalog.integer(s,"targetX",-29_900_000,29_900_000),StashCatalog.integer(s,"targetY",-2048,2048),StashCatalog.integer(s,"targetZ",-29_900_000,29_900_000));if(!StashCatalog.owns(plan,target.getX(),target.getY(),target.getZ()))throw new IllegalArgumentException("Invalid saved scan target");}
        if(s.has("pending"))pending=StashCatalog.observation(plan,s.getAsJsonObject("pending"));
        if(observed+unscanned>discovered || delivery != observed+unscanned+(pending==null?1:0)
            ||pending!=null&&(target==null||target.getX()!=pending.get("x").getAsInt()||target.getY()!=pending.get("y").getAsInt()||target.getZ()!=pending.get("z").getAsInt()))throw new IllegalArgumentException("Inconsistent saved scan receipt");
    }
}
