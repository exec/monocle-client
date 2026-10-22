package dev.monocle.client.systems.bots;

import com.google.gson.*;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.packets.InventoryEvent;
import dev.monocle.client.systems.modules.misc.swarm.CrewInventory;
import dev.monocle.client.utils.world.BlockUtils;
import dev.monocle.coordinator.StashCatalog;
import dev.monocle.coordinator.TaskWire;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.Path;
import static dev.monocle.client.MonocleClient.mc;

/** Read-only scanner: container I/O and a bounded, durable host outbox advance independently. */
public final class BotStashScan {
    public static final String WORKFLOW="task-stash-scan";
    private final JsonObject plan;
    private int cursor,discovered,observed,inferred,unscanned,removed,waitTicks,openTicks,attempts,menu=-1,beforeMenu=-1,delivery=1;
    private final Set<Long> missingChunks=new HashSet<>();
    private final List<Integer> candidateCells=new ArrayList<>();
    private int scanOrder=2;
    private boolean reverseRow,reverseLayer,reverseHeight;
    private BlockPos target,stance;
    private BotScanCoverage.Stop coverage;
    private BlockPos coverageFailedTarget;
    private int coverageRetryTick;
    private long coverageMicros;
    BotScanCoverage.Stop coverageStop(){
        if(coverage!=null&&!coverage.covers(target)&&candidateCells.stream().map(this::cell).noneMatch(coverage::covers))coverage=null;
        return coverage;
    }
    void rejectCoverage(){coverage=null;coverageFailedTarget=target;coverageRetryTick=mc.player.tickCount+100;}
    void planCoverage(java.util.function.Function<BlockPos,Vec3> floor,java.util.function.Predicate<Vec3> walkingSafe,java.util.function.Predicate<Vec3> flyingSafe,boolean allowFlying,double walkingEye,double flyingEye){
        if(!approaching()||target.equals(coverageFailedTarget)&&mc.player.tickCount<coverageRetryTick)return;
        if(mc.player.position().distanceToSqr(Vec3.atCenterOf(target))>144){coverage=null;return;}
        if(coverageStop()!=null&&coverage.covers(target)&&(coverage.flying()?flyingSafe:walkingSafe).test(coverage.feet())
            &&visibleFrom(coverage.eye(),target))return;
        if(mc.player.tickCount<coverageRetryTick)return;
        long started=System.nanoTime();
        coverage=BotScanCoverage.choose(target,candidateCells.stream().map(this::cell).toList(),mc.player.position(),walkingEye,flyingEye,
            floor,walkingSafe,flyingSafe,(eye,p)->loaded(p)&&blockedChestLid(mc.level,p)==null&&visibleFrom(eye,p),allowFlying);
        coverageMicros=(System.nanoTime()-started)/1000;coverageRetryTick=mc.player.tickCount+10;
    }
    boolean coverageReadSafe(){return target==null||coverage!=null&&coverage.covers(target)
        &&mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target))<=3.5*3.5;}
    static final int OUTBOX_LIMIT=32,OUTBOX_BYTES=2*1024*1024,WRITE_LIMIT=8,UPLOAD_WINDOW=8;
    private final List<JsonObject> outbox=new ArrayList<>();
    private record Write(CompletableFuture<Void> future,int through){}
    private final Deque<Write> writes=new ArrayDeque<>();
    private int outboxBytes,durableThrough;
    private Path checkpoint;
    private String checkpointBinding="";
    private JsonObject export;
    public void export(JsonObject template){export=template==null?null:template.deepCopy();}
    private static final ExecutorService CATALOG_WRITER = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(32),r->{Thread t=new Thread(r,"monocle-stash-catalog");t.setDaemon(true);return t;});
    private List<ItemStack> captured;
    private int closeTicks;
    private boolean openedAny;
    private JsonObject bottomTemplate,topTemplate,lazyTemplate;
    private boolean bottomUniform=true,topUniform=true;
    private String phase="Discovering",reason="Looking for containers",lastAction="Scan started";
    private long lastProgress=System.currentTimeMillis();
    private double approachBest=Double.POSITIVE_INFINITY;
    private String scope="";
    private static String currentScope(){return mc.level==null?"":(mc.getCurrentServer()==null?"local":mc.getCurrentServer().ip)+"\n"+mc.level.dimension().identifier();}
    private int previousMissing;
    dev.monocle.coordinator.ActionProgress.Observation progress() {
        return BotActions.progress("Scan: " + phase, captured != null ? "Container close confirmation" : approaching() ? "Reach scan container" : phase.equals("Draining") ? "Durable scan outbox / host receipts" : "Container contents / discovery",
            cursor + "/" + observed + "/" + inferred + "/" + unscanned + "/" + removed + "/" + delivery, target, false, approaching() && captured == null);
    }
    public BotStashScan(JsonObject source){
        plan=StashCatalog.plan(source);if(!columnMap()&&!plan.get("lazyMode").getAsBoolean())scanOrder=4;
        if(plan.has("scanCells")){scanOrder=1;cursor=volume();for(JsonElement value:plan.getAsJsonArray("scanCells"))candidateCells.add(scanCellIndex(plan,value.getAsJsonObject()));discovered=candidateCells.size();}
    }
    static int scanCellIndex(JsonObject plan,JsonObject cell){int w=plan.get("maxX").getAsInt()-plan.get("minX").getAsInt()+1,d=plan.get("maxZ").getAsInt()-plan.get("minZ").getAsInt()+1,h=plan.get("maxY").getAsInt()-plan.get("minY").getAsInt()+1,y=cell.get("y").getAsInt()-plan.get("minY").getAsInt(),layer=y==0?0:h-y;return (layer*d+cell.get("z").getAsInt()-plan.get("minZ").getAsInt())*w+cell.get("x").getAsInt()-plan.get("minX").getAsInt();}
    static void verifySortGeometry(net.minecraft.world.level.Level level,BlockPos target,JsonObject action){
        if(!action.has("sortFootprint"))return;
        Set<BlockPos> allowed=new HashSet<>();for(JsonElement value:action.getAsJsonArray("sortFootprint")){JsonObject p=value.getAsJsonObject();allowed.add(new BlockPos(p.get("x").getAsInt(),p.get("y").getAsInt(),p.get("z").getAsInt()));}
        if(!allowed.contains(target))throw new IllegalStateException("Sorting left its mapped physical footprint");
        var hopper=net.minecraft.world.level.block.Blocks.HOPPER;
        for(BlockPos cell:chestHalves(level,target)){
            if(!allowed.contains(cell))throw new IllegalStateException("Double chest changed during sorting; inventory retained");
            var state=level.getBlockState(cell);
            if(state.is(hopper)&&!allowed.contains(cell.relative(state.getValue(net.minecraft.world.level.block.HopperBlock.FACING))))throw new IllegalStateException("Hopper drains outside this sorting column; inspect its routing");
            for(var direction:net.minecraft.core.Direction.values()){
                BlockPos neighbor=cell.relative(direction);var adjacent=level.getBlockState(neighbor);
                if(!level.hasChunkAt(neighbor))throw new IllegalStateException("Neighboring sorting chunks are not loaded; no inventory move authorized");
                if(adjacent.is(hopper)&&unsafeSortHopperLink(cell,neighbor,adjacent.getValue(net.minecraft.world.level.block.HopperBlock.FACING),allowed))throw new IllegalStateException("An unmapped hopper exchanges items with this sorting column; scan its complete chain");
            }
        }
    }
    static boolean unsafeSortHopperLink(BlockPos cell,BlockPos hopper,net.minecraft.core.Direction facing,Set<BlockPos> allowed){return !allowed.contains(hopper)&&(hopper.equals(cell.below())||hopper.relative(facing).equals(cell));}
    private boolean columnMap(){return plan.get("scanMode").getAsString().equals("Column Map");}
    private int volume(){return (plan.get("maxX").getAsInt()-plan.get("minX").getAsInt()+1)*(columnMap()?1:plan.get("maxY").getAsInt()-plan.get("minY").getAsInt()+1)*(plan.get("maxZ").getAsInt()-plan.get("minZ").getAsInt()+1);}
    static int layerY(int layer,int layers){return layer==0?0:layer==1?layers-1:layers-layer;}
    private BlockPos cell(int i){int w=plan.get("maxX").getAsInt()-plan.get("minX").getAsInt()+1,d=plan.get("maxZ").getAsInt()-plan.get("minZ").getAsInt()+1,layers=plan.get("maxY").getAsInt()-plan.get("minY").getAsInt()+1,layer=i/w/d;return new BlockPos(plan.get("minX").getAsInt()+i%w,plan.get("minY").getAsInt()+(columnMap()?0:layerY(layer,layers)),plan.get("minZ").getAsInt()+i/w%d);}
    /** Queue entries remain canonical coordinates; only discovery order changes. */
    static int scanIndex(int cursor,int width,int depth,boolean reverseRow,boolean reverseLayer){
        int plane=width*depth,length=Math.max(width,depth),row=cursor%plane/length,along=cursor%length;
        if(reverseRow)along=length-1-along;
        if(reverseLayer)row=Math.min(width,depth)-1-row;
        return cursor/plane*plane+(width>=depth?row*width+along:along*width+row);
    }
    static int discoveryIndex(int index,int width,int depth,boolean reverseRow,boolean reverseLayer){
        int plane=width*depth,x=index%width,z=index%plane/width,along=width>=depth?x:z,row=width>=depth?z:x;
        if(reverseRow)along=Math.max(width,depth)-1-along;
        if(reverseLayer)row=Math.min(width,depth)-1-row;
        return index/plane*plane+row*Math.max(width,depth)+along;
    }
    /** Four-block cross-sections put neighboring chests, hoppers and heights in the same sweep. */
    record ScanBand(int start,int length,int bottom,int height,int side,int breadth){}
    static ScanBand scanBand(int cursor,int width,int depth,int layers){
        int length=Math.max(width,depth),breadth=Math.min(width,depth),bottom=cursor/(length*breadth*4)*4;
        int height=Math.min(4,layers-bottom),side=(cursor-bottom*length*breadth)/(length*height*4)*4;
        int sideSize=Math.min(4,breadth-side);
        return new ScanBand(bottom*length*breadth+side*length*height,length*height*sideSize,bottom,height,side,sideSize);
    }
    static int bandIndex(int cursor,int width,int depth,int layers,boolean reverseRow,boolean reverseLayer,boolean reverseHeight){
        ScanBand band=scanBand(cursor,width,depth,layers);int offset=cursor-band.start();
        int along=offset/(band.height()*band.breadth()),side=band.side()+offset/band.height()%band.breadth(),y=band.bottom()+offset%band.height();
        int bandNumber=band.bottom()/4*((Math.min(width,depth)+3)/4)+band.side()/4;
        if(reverseRow^(bandNumber%2==1))along=Math.max(width,depth)-1-along;
        if(reverseLayer^(band.bottom()/4%2==1))side=Math.min(width,depth)-1-side;
        if(reverseHeight)y=layers-1-y;
        int layer=y==0?0:y==layers-1?1:layers-y;
        return layer*width*depth+(width>=depth?side*width+along:along*width+side);
    }
    static int bandDiscoveryIndex(int index,int width,int depth,int layers,boolean reverseRow,boolean reverseLayer,boolean reverseHeight){
        int x=index%width,z=index%(width*depth)/width,y=layerY(index/(width*depth),layers),along=width>=depth?x:z,side=width>=depth?z:x;
        int length=Math.max(width,depth),breadth=Math.min(width,depth);
        if(reverseHeight)y=layers-1-y;
        int bottom=y/4*4,height=Math.min(4,layers-bottom);
        if(reverseLayer^(bottom/4%2==1))side=breadth-1-side;
        int sideStart=side/4*4,sideSize=Math.min(4,breadth-sideStart),bandNumber=bottom/4*((breadth+3)/4)+sideStart/4;
        if(reverseRow^(bandNumber%2==1))along=length-1-along;
        return bottom*length*breadth+sideStart*length*height+along*height*sideSize+(side-sideStart)*height+y-bottom;
    }
    private int width(){return plan.get("maxX").getAsInt()-plan.get("minX").getAsInt()+1;}
    private int depth(){return plan.get("maxZ").getAsInt()-plan.get("minZ").getAsInt()+1;}
    private int layers(){return columnMap()?1:plan.get("maxY").getAsInt()-plan.get("minY").getAsInt()+1;}
    static boolean nearerHighEnd(double position,int min,int max){return position>(min+(double)max+1)/2;}
    private void orientRow(){
        Vec3 eye=mc.player.getEyePosition();boolean alongX=width()>=depth();
        reverseRow=nearerHighEnd(alongX?eye.x:eye.z,plan.get(alongX?"minX":"minZ").getAsInt(),plan.get(alongX?"maxX":"maxZ").getAsInt());
        if(scanOrder>=3?cursor==0:cursor%(width()*depth())==0)reverseLayer=nearerHighEnd(alongX?eye.z:eye.x,plan.get(alongX?"minZ":"minX").getAsInt(),plan.get(alongX?"maxZ":"maxX").getAsInt());
        if(scanOrder>=3&&cursor==0)reverseHeight=nearerHighEnd(eye.y,plan.get("minY").getAsInt(),plan.get("maxY").getAsInt());
    }
    static boolean bandReverseRow(int cursor,int width,int depth,int layers,boolean nearerHigh){
        ScanBand band=scanBand(cursor,width,depth,layers);
        int number=band.bottom()/4*((Math.min(width,depth)+3)/4)+band.side()/4;
        return nearerHigh^(number%2==1);
    }
    /** Only walk ahead while the outstanding server read remains comfortably within reach. */
    public BlockPos walkingLookAhead(BlockPos preferred){
        if(!mc.player.onGround()||!canQueue(outbox.size(),outboxBytes,writes.size()))return null;
        List<BlockPos> candidates=new ArrayList<>(candidateCells.stream().map(this::cell).toList());
        if(preferred!=null&&candidates.remove(preferred))candidates.addFirst(preferred);
        return walkingLookAhead(target,candidates,mc.player.getEyePosition(),mc.player.getY());
    }
    static BlockPos walkingLookAhead(BlockPos reading,List<BlockPos> candidates,Vec3 eye,double feetY){
        if(reading!=null&&eye.distanceToSqr(Vec3.atCenterOf(reading))>3.5*3.5)return null;
        for(BlockPos p:candidates){
            double distance=eye.distanceToSqr(Vec3.atCenterOf(p));
            if(distance>3.5*3.5&&distance<=8*8&&Math.abs(p.getY()-feetY)<=1.5)return p;
        }
        return null;
    }
    private boolean loaded(BlockPos p){return mc.level.getChunkSource().hasChunk(p.getX()>>4,p.getZ()>>4);}
    static boolean includeContainer(boolean lazyMode,boolean hopper){return !lazyMode||!hopper;}
    public boolean done(){return cursor>=volume()&&candidateCells.isEmpty()&&target==null&&outbox.isEmpty()&&writes.isEmpty();}
    public BlockPos target(){return target;}
    public BlockPos stance(){return stance;}
    public boolean approaching(){return phase.equals("Approaching")&&target!=null;}
    public boolean targetIsHopper(){return target!=null&&mc.level.getBlockEntity(target) instanceof HopperBlockEntity;}
    public BlockPos navigationTarget(){
        return navigationTarget(target);
    }
    static BlockPos navigationTarget(BlockPos target){
        if(!(mc.level.getBlockEntity(target) instanceof HopperBlockEntity))return target;
        List<BlockPos> perches=new ArrayList<>();
        for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL){BlockPos chest=target.relative(direction);if(mc.level.getBlockState(chest).getBlock() instanceof ChestBlock&&mc.level.getBlockState(chest.above()).isAir()&&mc.level.getBlockState(chest.above(2)).isAir())perches.add(chest.above());}
        if(mc.level.getBlockState(target.below()).getBlock() instanceof ChestBlock&&mc.level.getBlockState(target.above()).isAir()&&mc.level.getBlockState(target.above(2)).isAir())perches.add(target.above());
        return perches.stream().min(Comparator.comparingDouble(p->Vec3.atBottomCenterOf(p).distanceToSqr(mc.player.position()))).orElse(target);
    }
    public boolean hasHopperPerch(){return targetIsHopper()&&!navigationTarget().equals(target);}
    public boolean suppressScreen(){return target!=null&&beforeMenu>=0;}
    public boolean ownsRead(BlockPos pos){return suppressScreen()&&chestHalves(mc.level,target).contains(pos);}
    static boolean shouldCloseHiddenMenu(int current,int inventory,boolean opened,boolean visibleContainer){return opened&&current!=inventory&&!visibleContainer;}
    private boolean hiddenScanMenu(){return mc.player!=null&&shouldCloseHiddenMenu(mc.player.containerMenu.containerId,mc.player.inventoryMenu.containerId,openedAny,mc.gui.screen() instanceof AbstractContainerScreen<?>);}
    public String detail(){return phase+": "+reason+" · "+observed+" observed, "+unscanned+" unscanned";}
    private void progress(String action){lastAction=action;lastProgress=System.currentTimeMillis();}
    /** Bounded look-ahead, selected from the actual eye position rather than a fixed compass sweep. */
    static BlockPos nearestContainer(List<BlockPos> candidates,Vec3 eye,java.util.function.Predicate<BlockPos> opening){
        BlockPos best=null;double bestDistance=Double.POSITIVE_INFINITY;boolean bestOpen=false;
        for(BlockPos p:candidates){
            double distance=Vec3.atCenterOf(p).distanceToSqr(eye);boolean open=opening.test(p);
            if(best==null||open&&!bestOpen||open==bestOpen&&(distance<bestDistance||distance==bestDistance&&p.asLong()<best.asLong())){
                best=p;bestDistance=distance;bestOpen=open;
            }
        }
        return best;
    }
    public void tick(){
        if(scope.isEmpty())scope=currentScope();
        if(!scope.equals(currentScope()))throw new IllegalStateException("Stash scan belongs to another world");
        drainWrites();
        if(captured!=null){phase="Confirming";reason="Allowing the server menu to settle";if(++closeTicks>=1){List<ItemStack> items=captured;captured=null;finish(items,"");}return;}
        if(beforeMenu<0&&hiddenScanMenu()){mc.player.closeContainer();phase="Closing";reason="Closing a delayed scan menu before the next container";return;}
        if(target==null){
            if(!canQueue(outbox.size(),outboxBytes,writes.size())){phase="Draining";reason="Scan buffer full: "+outbox.size()+" awaiting host, "+writes.size()+" local writes";return;}
            if(!Bots.get().stashObserver.catalogReady()){phase="Discovering";reason="Loading known storage for removal reconciliation";return;}
            phase="Discovering";reason="Looking for containers";
            int layerSize=(plan.get("maxX").getAsInt()-plan.get("minX").getAsInt()+1)*(plan.get("maxZ").getAsInt()-plan.get("minZ").getAsInt()+1);
            int layer=candidateCells.isEmpty()?cursor/layerSize:candidateCells.getFirst()/layerSize;
            // ponytail: 64-container look-ahead, not a global travelling-salesman route; keep tick work and journals bounded.
            for(int budget=0;budget<512&&cursor<volume()&&candidateCells.size()<64;budget++){
                // Switch old band checkpoints only after draining their queued coordinates at a band boundary.
                if(scanOrder==3&&candidateCells.isEmpty()&&cursor==scanBand(cursor,width(),depth(),layers()).start())scanOrder=4;
                if(scanOrder>=3&&cursor==0)orientRow();
                if(scanOrder==4){
                    ScanBand band=scanBand(cursor,width(),depth(),layers());
                    if(cursor==band.start()){
                        if(!candidateCells.isEmpty())break;
                        boolean alongX=width()>=depth();
                        reverseRow=bandReverseRow(cursor,width(),depth(),layers(),nearerHighEnd(alongX?mc.player.getX():mc.player.getZ(),plan.get(alongX?"minX":"minZ").getAsInt(),plan.get(alongX?"maxX":"maxZ").getAsInt()));
                    }
                }
                if(scanOrder==2&&cursor%Math.max(width(),depth())==0){
                    // Lazy/legacy row scans retain their bookend ordering; full scans cross band boundaries.
                    if(!candidateCells.isEmpty())break;
                    orientRow();
                }
                if(plan.get("lazyMode").getAsBoolean()&&cursor/layerSize!=layer)break;
                int index=scanOrder>=3?bandIndex(cursor,width(),depth(),layers(),reverseRow,reverseLayer,reverseHeight):scanOrder==2?scanIndex(cursor,width(),depth(),reverseRow,reverseLayer):cursor;cursor++;BlockPos p=cell(index);
                if(!loaded(p)){missingChunks.add(new BlockPos(p.getX()>>4,0,p.getZ()>>4).asLong());continue;}
                Bots.get().stashObserver.reconcile(p);
                if(!(mc.level.getBlockEntity(p) instanceof BaseContainerBlockEntity entity)){
                    if(Bots.get().stashObserver.knownSource(p)&&BotStashObserver.removedStorage(mc.level.getBlockState(p),p)&&!mc.level.getBlockStatePredictionHandler().serverVerifiedStates.containsKey(p.asLong())&&StashCatalog.owns(plan,p.getX(),p.getY(),p.getZ())){if(++discovered>StashCatalog.MAX_CONTAINERS)throw new IllegalStateException("Scan exceeded "+StashCatalog.MAX_CONTAINERS+" containers");candidateCells.add(index);}continue;
                }
                if(!includeContainer(plan.get("lazyMode").getAsBoolean(),entity instanceof HopperBlockEntity))continue;
                var state=mc.level.getBlockState(p);
                if(state.getBlock() instanceof ChestBlock&&state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE){
                    BlockPos other=p.relative(ChestBlock.getConnectedDirection(state));
                    if(StashCatalog.contains(plan,other.getX(),other.getY(),other.getZ())&&other.asLong()<p.asLong())continue;
                }
                if(!StashCatalog.owns(plan,p.getX(),p.getY(),p.getZ()))continue;
                if(++discovered>StashCatalog.MAX_CONTAINERS)throw new IllegalStateException("Scan exceeded "+StashCatalog.MAX_CONTAINERS+" containers");
                candidateCells.add(index);
            }
            Vec3 eye=mc.player.getEyePosition();
            List<BlockPos> choices=candidateCells.stream().map(this::cell).toList();
            BotScanCoverage.Stop stop=coverageStop();
            if(stop!=null)choices=choices.stream().filter(stop::covers).toList();
            target=nearestContainer(choices,eye,
                p->eye.distanceToSqr(Vec3.atCenterOf(p))<=20.25&&(mc.level.getBlockEntity(p) instanceof HopperBlockEntity||visibleFrom(eye,p)));
            if(target==null){if(cursor>=volume()){
                phase=outbox.isEmpty()&&writes.isEmpty()?"Complete":"Draining";
                reason=phase.equals("Draining")?"All containers read; waiting for "+outbox.size()+" durable host receipts":unscanned==0&&previousMissing+missingChunks.size()==0?(columnMap()?"Bottom layer mapped; upper columns are assumed, not counted":"Every discovered container observed"):"Scan finished with gaps; inspect unscanned containers and missing chunks";
            }return;}
            candidateCells.removeIf(index->cell(index).equals(target));
            stance=null;waitTicks=openTicks=attempts=0;menu=beforeMenu=-1;approachBest=Double.POSITIVE_INFINITY;progress("Selected nearby container at "+target.toShortString());
        }
        if(loaded(target))verifySortGeometry(mc.level,target,plan);
        if(loaded(target)&&mc.player.containerMenu==mc.player.inventoryMenu){
            BlockPos blocked=blockedChestLid(mc.level,target);
            if(blocked!=null){finish(null,"Chest lid blocked by "+BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(blocked).getBlock())+" at "+blocked.toShortString());return;}
        }
        if(loaded(target)&&BotStashObserver.removedStorage(mc.level.getBlockState(target),target)&&!mc.level.getBlockStatePredictionHandler().serverVerifiedStates.containsKey(target.asLong())){finish(null,"Confirmed mapped storage is no longer present");return;}
        if(infer()){return;}
        if(waitingExpired()){finish(null,"No useful progress toward a reachable opening or no server contents within ten seconds");return;}
        if(!loaded(target)){phase="Waiting for chunks";reason="Container chunk is not received";return;}
        if(!(mc.level.getBlockEntity(target) instanceof BaseContainerBlockEntity)){finish(null,"Container disappeared or changed");return;}
        if(mc.player.containerMenu!=mc.player.inventoryMenu){if(beforeMenu>=0)menu=mc.player.containerMenu.containerId;phase="Reading";reason=menu>=0?"Waiting for server-confirmed container contents":"Waiting for another inventory screen to close";return;}
        if(mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target))>4.5*4.5||!targetIsHopper()&&!visibleFrom(mc.player.getEyePosition(),target)){
            stance=target;
            phase="Approaching";reason="Finding a read-only route to "+target.toShortString();return;
        }
        stance=null;phase="Opening";reason="Requesting container contents";
        if(++openTicks%20==1&&attempts++<6){
            beforeMenu=mc.player.inventoryMenu.containerId;openedAny=true;
            openContainer(target);
            progress("Requested opening at "+target.toShortString());
        }
    }
    static boolean containerTarget(BlockEntity entity){return entity instanceof BaseContainerBlockEntity||entity instanceof EnderChestBlockEntity;}
    /** Physical, enabled hopper links only. Unconnected/mixed layouts keep independent chest handling. */
    record HopperColumn(List<BlockPos> chests){
        BlockPos bottom(){return chests.getFirst();}
        BlockPos top(){return chests.getLast();}
        boolean fed(){return chests.size()>1;}
        int refillTicks(){return 40+8*(chests.size()-1);}
    }
    static HopperColumn hopperColumn(BlockGetter world,BlockPos chest,JsonObject bounds){
        chest=hopperOutlet(world,chest,bounds);
        BlockPos bottom=chestHalves(world,chest).stream().filter(p->StashCatalog.contains(bounds,p.getX(),p.getY(),p.getZ()))
            .min(Comparator.comparingLong(BlockPos::asLong)).orElse(chest);
        for(BlockPos next;(next=linkedChest(world,bottom,bounds,false))!=null;)bottom=next;
        List<BlockPos> chests=new ArrayList<>();chests.add(bottom);
        // Links strictly ascend/descend, and stash Y bounds cap the traversal without a graph/cache.
        for(BlockPos next;(next=linkedChest(world,chests.getLast(),bounds,true))!=null;)chests.add(next);
        return new HopperColumn(List.copyOf(chests));
    }
    /** A catalogued hopper's stock can already have moved into its output chest. */
    static BlockPos hopperOutlet(BlockGetter world,BlockPos source,JsonObject bounds){
        BlockPos next=source;Set<BlockPos> visited=new HashSet<>();
        // shortcut: follow 256 output hops; larger transport networks retain direct hopper reads.
        while(visited.size()<256&&visited.add(next)&&StashCatalog.contains(bounds,next.getX(),next.getY(),next.getZ())){
            var state=world.getBlockState(next);
            if(state.getBlock() instanceof ChestBlock)return next;
            if(!(state.getBlock() instanceof HopperBlock)||!state.getValue(HopperBlock.ENABLED))break;
            next=next.relative(state.getValue(HopperBlock.FACING));
        }
        return source;
    }
    static List<BlockPos> chestHalves(BlockGetter world,BlockPos chest){
        var state=world.getBlockState(chest);
        if(!(state.getBlock() instanceof ChestBlock))return List.of();
        if(state.getValue(ChestBlock.TYPE)==ChestType.SINGLE)return List.of(chest);
        BlockPos other=chest.relative(ChestBlock.getConnectedDirection(state));var partner=world.getBlockState(other);
        return partner.getBlock()==state.getBlock()&&partner.getValue(ChestBlock.TYPE)!=ChestType.SINGLE
            &&partner.getValue(ChestBlock.TYPE)!=state.getValue(ChestBlock.TYPE)&&partner.getValue(ChestBlock.FACING)==state.getValue(ChestBlock.FACING)
            ?List.of(chest,other):List.of(chest);
    }
    private static BlockPos linkedChest(BlockGetter world,BlockPos chest,JsonObject bounds,boolean upstream){
        Set<BlockPos> candidates=new HashSet<>();
        for(BlockPos half:chestHalves(world,chest))for(Direction direction:Direction.values()){
            if(!upstream&&direction!=Direction.DOWN)continue;
            BlockPos hopper=half.relative(direction);var state=world.getBlockState(hopper);
            if(!(state.getBlock() instanceof HopperBlock)||!state.getValue(HopperBlock.ENABLED))continue;
            BlockPos output=hopper.relative(state.getValue(HopperBlock.FACING));
            if(upstream&&!output.equals(half))continue;
            BlockPos next=upstream?hopper.above():output;
            if(upstream?next.getY()<=chest.getY():next.getY()>=chest.getY())continue;
            chestHalves(world,next).stream().filter(p->StashCatalog.contains(bounds,p.getX(),p.getY(),p.getZ()))
                .min(Comparator.comparingLong(BlockPos::asLong)).ifPresent(candidates::add);
        }
        // Branches are not a homogeneous column: never merge separate inventories by proximity.
        return candidates.size()==1?candidates.iterator().next():null;
    }
    /** Same solid-lid rule as vanilla ChestBlock; either connected half can prevent opening. */
    static BlockPos blockedChestLid(BlockGetter world,BlockPos target){
        var state=world.getBlockState(target);
        if(!(state.getBlock() instanceof ChestBlock))return null;
        BlockPos lid=target.above();
        if(world.getBlockState(lid).isRedstoneConductor(world,lid))return lid;
        if(state.getValue(ChestBlock.TYPE)==ChestType.SINGLE)return null;
        BlockPos other=target.relative(ChestBlock.getConnectedDirection(state));var partner=world.getBlockState(other);
        if(partner.getBlock()!=state.getBlock()||partner.getValue(ChestBlock.TYPE)==ChestType.SINGLE
            ||partner.getValue(ChestBlock.TYPE)==state.getValue(ChestBlock.TYPE)||partner.getValue(ChestBlock.FACING)!=state.getValue(ChestBlock.FACING))return null;
        lid=other.above();return world.getBlockState(lid).isRedstoneConductor(world,lid)?lid:null;
    }
    static void openContainer(BlockPos target){
        // Containers consume a normal, non-sneaking click before the held item's placement logic.
        // Do not click a replaced/missing container, where a held kit could instead be placed.
        if(!containerTarget(mc.level.getBlockEntity(target)))throw new IllegalStateException("Stash container disappeared before opening; no held item was placed");
        BlockUtils.interact(openingHit(mc.player.getEyePosition(),target),InteractionHand.MAIN_HAND,false);
    }
    static BlockHitResult openingHit(Vec3 eye,BlockPos target){
        Vec3 center=Vec3.atCenterOf(target),delta=eye.subtract(center);
        Direction face=Math.abs(delta.x)>=Math.abs(delta.z)&&Math.abs(delta.x)>.75?delta.x>0?Direction.EAST:Direction.WEST
            :Math.abs(delta.z)>.75?delta.z>0?Direction.SOUTH:Direction.NORTH:delta.y>0?Direction.UP:Direction.DOWN;
        return new BlockHitResult(center.add(face.getStepX()*.5,face.getStepY()*.5,face.getStepZ()*.5),face,target,false);
    }
    static boolean visibleFrom(Vec3 eye,BlockPos target){
        return mc.level.clip(new ClipContext(eye,Vec3.atCenterOf(target),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player)).getBlockPos().equals(target);
    }
    boolean waitingExpired(){return ++waitTicks>200;}
    /** Only a new closest approach resets the watchdog; oscillation and motion away do not count. */
    public boolean approachProgress(Vec3 eye){
        if(target==null||menu>=0||captured!=null)return false;
        double distance=eye.distanceTo(Vec3.atCenterOf(target));
        if(!Double.isFinite(distance))return false;
        if(!Double.isFinite(approachBest)){approachBest=distance;return false;}
        if(distance>approachBest-.25)return false;
        approachBest=distance;waitTicks=0;progress("Closer to container ("+Math.round(distance*10)/10d+" blocks)");return true;
    }
    public void inventory(InventoryEvent event){
        if(target==null||captured!=null||beforeMenu<0||mc.player==null||!scope.equals(currentScope())||mc.player.containerMenu==mc.player.inventoryMenu
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
        o.addProperty("status",items==null&&loaded(target)&&!mc.level.getBlockStatePredictionHandler().serverVerifiedStates.containsKey(target.asLong())&&BotStashObserver.removedStorage(mc.level.getBlockState(target),target)?"removed":items==null?"unscanned":"observed");o.addProperty("reason",failure);
        JsonObject geometry=BotStashObserver.geometry(mc.level,target);if(geometry!=null)for(String key:List.of("capacity","parts"))o.add(key,geometry.get(key).deepCopy());
        JsonObject totals=items==null?new JsonObject():CrewInventory.manifest(items);JsonArray boxes=classify(items==null?List.of():items);
        for(var value:boxes){JsonObject b=value.getAsJsonObject();if(b.get("legacy").getAsBoolean())b.getAsJsonObject("items").entrySet().forEach(i->totals.addProperty(i.getKey(),(totals.has(i.getKey())?totals.get(i.getKey()).getAsInt():0)+i.getValue().getAsInt()*b.get("quantity").getAsInt()));}
        o.add("items",totals);o.add("shulkers",boxes);
        if(items!=null)recordSlots(o,items);
        if(items!=null)learn(o,items);else {if(target.getY()==plan.get("minY").getAsInt())bottomUniform=false;if(target.getY()==plan.get("maxY").getAsInt())topUniform=false;lazyTemplate=null;}
        save(o,items==null,failure);
    }
    private void save(JsonObject o,boolean missed,String failure){
        JsonObject observation=StashCatalog.observation(plan,o);
        outbox.add(observation);outboxBytes+=observationBytes(observation);
        String status=TaskWire.text(observation,"status");
        if(status.equals("removed"))removed++;else if(status.equals("unscanned"))unscanned++;else {observed++;if(TaskWire.flag(observation,"inferred"))inferred++;}
        close();target=stance=null;approachBest=Double.POSITIVE_INFINITY;
        JsonObject saved=snapshot();Path destination=checkpoint;String binding=checkpointBinding;
        CompletableFuture<Void> future=saveLocal(MonocleClient.FOLDER.toPath(),scope,plan,observation,export,destination,binding,saved);
        writes.addLast(new Write(future,reads()));
        progress(missed?"Queued unscanned container: "+failure:"Queued server-confirmed contents");phase="Discovering";
    }
    static boolean canQueue(int observations,int bytes,int writes){return observations<OUTBOX_LIMIT&&bytes<=OUTBOX_BYTES-StashCatalog.MAX_OBSERVATION_BYTES&&writes<WRITE_LIMIT;}
    private static int observationBytes(JsonObject o){return dev.monocle.coordinator.TaskFiles.jsonBytes(o,StashCatalog.MAX_OBSERVATION_BYTES).length;}
    private int reads(){return observed+unscanned+removed;}
    private void drainWrites(){while(!writes.isEmpty()&&writes.getFirst().future().isDone()){
        Write write=writes.getFirst();write.future().join();writes.removeFirst();durableThrough=Math.max(durableThrough,write.through());
    }}
    public boolean independentCheckpoint(){return checkpoint!=null;}
    public void checkpoint(Path root,String binding){checkpoint(root,currentScope(),binding);}
    void checkpoint(Path root,String world,String binding){
        JsonObject saved=StashCatalog.scanCheckpoint(root,world,plan,binding);
        if(!saved.isEmpty()){
            StashCatalog.integer(saved,"version",1,1);
            if(!binding.equals(TaskWire.text(saved,"binding"))||!dev.monocle.coordinator.TaskFiles.hash(plan.toString()).equals(TaskWire.text(saved,"plan")))throw new IllegalArgumentException("Scan checkpoint belongs to another action");
            restore(saved.getAsJsonObject("scan"));
        }
        checkpoint=root;checkpointBinding=binding;scope=world;
    }
    static CompletableFuture<Void> saveLocal(Path root,String scope,JsonObject plan,JsonObject observation){
        return saveLocal(root,scope,plan,observation,null);
    }
    public static CompletableFuture<Void> queueExport(Path root,JsonObject export){
        JsonObject copy=export.deepCopy();return CompletableFuture.runAsync(()->dev.monocle.coordinator.StashExports.enqueue(root,TaskWire.text(copy,"owner"),TaskWire.text(copy,"worker"),copy.getAsJsonObject("message")),CATALOG_WRITER);
    }
    static CompletableFuture<Void> saveLocal(Path root,String scope,JsonObject plan,JsonObject observation,JsonObject export){
        return saveLocal(root,scope,plan,observation,export,null,"",null);
    }
    static CompletableFuture<Void> saveLocal(Path root,String scope,JsonObject plan,JsonObject observation,JsonObject export,Path checkpoint,String binding,JsonObject scan){
        JsonObject assignment=plan.deepCopy(),copy=observation.deepCopy();
        JsonObject scanCopy=scan==null?null:scan.deepCopy();
        JsonObject envelope=export==null?null:export.deepCopy();
        return CompletableFuture.runAsync(()->{
            StashCatalog.observation(assignment,copy);
            if(envelope!=null){JsonObject m=envelope.getAsJsonObject("message");m.addProperty("type","task-stash-import");StashCatalog.attachObservation(m,copy);dev.monocle.coordinator.StashExports.enqueue(root,TaskWire.text(envelope,"owner"),TaskWire.text(envelope,"worker"),m);}
            if(checkpoint!=null){JsonObject saved=new JsonObject();saved.addProperty("version",1);saved.addProperty("binding",binding);saved.addProperty("plan",dev.monocle.coordinator.TaskFiles.hash(assignment.toString()));saved.add("scan",scanCopy);StashCatalog.recordScan(root,scope,assignment,copy,saved);}
            else StashCatalog.record(root,"Local",scope,assignment,copy);
        },CATALOG_WRITER);
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
        JsonObject o=inferredObservation(lazyTemplate,BotStashObserver.geometry(mc.level,target));if(o==null)return false;save(o,false,"");return true;
    }
    static JsonObject inferredObservation(JsonObject template,JsonObject geometry){
        if(geometry==null||!geometry.get("capacity").equals(template.get("slotCount")))return null;
        JsonObject o=template.deepCopy();for(String key:List.of("x","y","z","parts","capacity"))o.add(key,geometry.get(key).deepCopy());
        o.addProperty("inferred",true);o.addProperty("reason","Lazy Mode: matching full homogeneous bottom and top layers");return o;
    }
    static void recordSlots(JsonObject observation,List<ItemStack> items){
        observation.addProperty("slotCount",items.size());observation.addProperty("occupiedSlots",items.stream().filter(s->!s.isEmpty()).count());
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
    static boolean matchesKit(ItemStack stack, JsonObject criteria){
        JsonArray boxes=classify(List.of(stack));
        if(boxes.isEmpty())return false;
        JsonObject box=boxes.get(0).getAsJsonObject();
        return (criteria.has("sortBatch")?criteria.get("kitTypeId").getAsString().equals(dev.monocle.coordinator.KitTypes.sortIdentity(box,criteria.getAsJsonObject("kitIdentities"))):dev.monocle.coordinator.KitTypes.matches(box,criteria))
            &&(criteria.has("includeIncomplete")&&criteria.get("includeIncomplete").getAsBoolean()||!criteria.has("kitExemplar")||StashCatalog.kitIncomplete(criteria.getAsJsonObject("kitExemplar"),box.getAsJsonObject("items"))==criteria.get("incomplete").getAsBoolean());
    }
    static JsonArray classify(List<ItemStack> items,JsonObject criteria){
        JsonArray boxes=classify(items);for(JsonElement value:boxes){JsonObject box=value.getAsJsonObject();String id=criteria.has("sortBatch")?dev.monocle.coordinator.KitTypes.sortIdentity(box,criteria.getAsJsonObject("kitIdentities")):dev.monocle.coordinator.KitTypes.identity(box,criteria);if(!id.isEmpty())box.addProperty("kitTypeId",id);}return boxes;
    }
    public void close(){captured=null;closeTicks=0;if(mc.player!=null&&(menu>=0&&mc.player.containerMenu.containerId==menu||hiddenScanMenu()))mc.player.closeContainer();menu=beforeMenu=-1;}
    public boolean hasPending(){drainWrites();return !outbox.isEmpty()&&delivery<=durableThrough;}
    boolean canYieldInventory(){return captured==null&&beforeMenu<0;}
    public JsonObject pending(){return hasPending()?outbox.getFirst().deepCopy():null;}
    public List<JsonObject> uploadWindow(){
        return uploadWindow(0);
    }
    public List<JsonObject> uploadWindow(int sentThrough){
        if(!hasPending())return List.of();
        int end=Math.min(UPLOAD_WINDOW,Math.min(outbox.size(),durableThrough-delivery+1)),start=Math.max(0,sentThrough-delivery+1);
        return start>=end?List.of():outbox.subList(start,end).stream().map(JsonObject::deepCopy).toList();
    }
    public int delivery(){return delivery;}
    public void acknowledge(int id){if(hasPending()&&id==delivery){outboxBytes-=observationBytes(outbox.removeFirst());delivery++;}}
    public JsonObject telemetry(){
        JsonObject t=new JsonObject();t.addProperty("name",plan.get("name").getAsString());t.addProperty("phase",phase);t.addProperty("reason",reason);t.addProperty("discovery",cursor);t.addProperty("volume",volume());
        t.addProperty("noProgressTicks",waitTicks);if(Double.isFinite(approachBest))t.addProperty("closestOpeningDistance",approachBest);
        t.addProperty("routeOrder",scanOrder==4?"Nearest-end serpentine bands":scanOrder==3?"Nearby container bands":scanOrder==2?"Nearest-end rows":"Legacy raster (resumed)");
        t.addProperty("discovered",discovered);t.addProperty("queuedContainers",candidateCells.size());t.addProperty("observed",observed-inferred);t.addProperty("inferred",inferred);t.addProperty("unscanned",unscanned);t.addProperty("removed",removed);t.addProperty("missingChunks",previousMissing+missingChunks.size());
        t.addProperty("queuedObservations",outbox.size());t.addProperty("queuedBytes",outboxBytes);t.addProperty("localWrites",writes.size());t.addProperty("durableReads",durableThrough);t.addProperty("hostConfirmed",delivery-1);
        t.addProperty("coveragePosition",coverage==null?"":coverage.feet().toString());t.addProperty("coverageContainers",coverage==null?0:coverage.containers().size());t.addProperty("coverageSearchMicros",coverageMicros);
        t.addProperty("target",target==null?"":target.toShortString());t.addProperty("movementTarget",stance==null?"":stance.toShortString());t.addProperty("lastAction",lastAction);t.addProperty("lastProgressAt",lastProgress);t.addProperty("menu",menu);t.addProperty("attempts",attempts);return t;
    }
    public JsonObject snapshot(){JsonObject s=telemetry();s.addProperty("cursor",cursor);s.addProperty("delivery",delivery);s.addProperty("scanOrder",scanOrder);s.addProperty("reverseRow",reverseRow);s.addProperty("reverseLayer",reverseLayer);s.addProperty("reverseHeight",reverseHeight);JsonArray candidates=new JsonArray();candidateCells.forEach(candidates::add);s.add("candidateCells",candidates);if(target!=null){s.addProperty("targetX",target.getX());s.addProperty("targetY",target.getY());s.addProperty("targetZ",target.getZ());}JsonArray queued=new JsonArray();outbox.forEach(o->queued.add(o.deepCopy()));s.add("outbox",queued);return s;}
    public void restore(JsonObject s){
        coverage=null;coverageFailedTarget=null;coverageRetryTick=0;coverageMicros=0;
        target=stance=null;outbox.clear();outboxBytes=0;writes.clear();captured=null;menu=beforeMenu=-1;
        cursor=StashCatalog.integer(s,"cursor",0,volume());delivery=StashCatalog.integer(s,"delivery",1,StashCatalog.MAX_CONTAINERS+1);
        // Old checkpoints must finish in their original raster order, not reinterpret visited cells.
        scanOrder=s.has("scanOrder")?StashCatalog.integer(s,"scanOrder",1,4):1;
        if(scanOrder>=2){
            for(String key:scanOrder>=3?List.of("reverseRow","reverseLayer","reverseHeight"):List.of("reverseRow","reverseLayer"))if(!s.has(key)||!s.get(key).isJsonPrimitive()||!s.getAsJsonPrimitive(key).isBoolean())throw new IllegalArgumentException("Invalid saved scan direction");
            reverseRow=s.get("reverseRow").getAsBoolean();reverseLayer=s.get("reverseLayer").getAsBoolean();
            if(scanOrder>=3){if(columnMap()||plan.get("lazyMode").getAsBoolean())throw new IllegalArgumentException("Spatial bands require a full non-lazy scan");reverseHeight=s.get("reverseHeight").getAsBoolean();}
        }
        discovered=StashCatalog.integer(s,"discovered",0,StashCatalog.MAX_CONTAINERS);int reported=StashCatalog.integer(s,"observed",0,StashCatalog.MAX_CONTAINERS);inferred=s.has("inferred")?StashCatalog.integer(s,"inferred",0,StashCatalog.MAX_CONTAINERS):0;observed=reported+inferred;unscanned=StashCatalog.integer(s,"unscanned",0,StashCatalog.MAX_CONTAINERS);
        removed=s.has("removed")?StashCatalog.integer(s,"removed",0,StashCatalog.MAX_CONTAINERS):0;
        previousMissing=StashCatalog.integer(s,"missingChunks",0,1_048_576);
        if(s.has("targetX")){target=new BlockPos(StashCatalog.integer(s,"targetX",-29_900_000,29_900_000),StashCatalog.integer(s,"targetY",-2048,2048),StashCatalog.integer(s,"targetZ",-29_900_000,29_900_000));if(!StashCatalog.owns(plan,target.getX(),target.getY(),target.getZ()))throw new IllegalArgumentException("Invalid saved scan target");}
        candidateCells.clear();
        if(s.has("candidateCells")){
            JsonArray candidates=s.getAsJsonArray("candidateCells");if(candidates.size()>64)throw new IllegalArgumentException("Too many queued scan containers");
            for(JsonElement value:candidates){JsonObject checked=new JsonObject();checked.add("index",value);int index=StashCatalog.integer(checked,"index",0,scanOrder==1?cursor-1:volume()-1);BlockPos p=cell(index);
                if(scanOrder==2){int position=discoveryIndex(index,width(),depth(),reverseRow,reverseLayer),length=Math.max(width(),depth());if(position>=cursor||position/length!=(cursor-1)/length)throw new IllegalArgumentException("Queued scan container is outside the discovered row");}
                if(scanOrder>=3){int position=bandDiscoveryIndex(index,width(),depth(),layers(),reverseRow,reverseLayer,reverseHeight);if(position>=cursor||scanOrder==4&&scanBand(position,width(),depth(),layers()).start()!=scanBand(cursor-1,width(),depth(),layers()).start())throw new IllegalArgumentException("Queued scan container has not been discovered");}
                if(candidateCells.contains(index)||p.equals(target)||!StashCatalog.owns(plan,p.getX(),p.getY(),p.getZ()))throw new IllegalArgumentException("Invalid queued scan container");candidateCells.add(index);
            }
        }
        if(s.has("pending")){
            JsonObject old=StashCatalog.observation(plan,s.getAsJsonObject("pending"));
            if(target==null||!target.equals(new BlockPos(old.get("x").getAsInt(),old.get("y").getAsInt(),old.get("z").getAsInt())))throw new IllegalArgumentException("Invalid legacy pending scan target");
            outbox.add(old);target=null;
        }
        if(s.has("outbox")){
            if(!s.get("outbox").isJsonArray()||s.getAsJsonArray("outbox").size()>OUTBOX_LIMIT||s.has("pending")&&!s.getAsJsonArray("outbox").isEmpty())throw new IllegalArgumentException("Invalid scan outbox");
            for(JsonElement value:s.getAsJsonArray("outbox"))outbox.add(StashCatalog.observation(plan,value.getAsJsonObject()));
        }
        for(JsonObject o:outbox)outboxBytes+=observationBytes(o);
        if(outboxBytes>OUTBOX_BYTES||reads()+candidateCells.size()+(target!=null?1:0)!=discovered||delivery!=reads()-outbox.size()+1)
            throw new IllegalArgumentException("Inconsistent saved scan receipt");
        durableThrough=reads();
    }
}
