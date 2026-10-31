package dev.monocle.client.systems.bots;

import com.google.gson.*;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.packets.InventoryEvent;
import dev.monocle.client.events.packets.ContainerSlotUpdateEvent;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.world.StashManager;
import dev.monocle.client.systems.modules.misc.swarm.CrewInventory;
import dev.monocle.client.systems.modules.misc.swarm.SwarmConnection;
import dev.monocle.coordinator.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.Path;
import static dev.monocle.client.MonocleClient.mc;

/** Passive, bounded observations. Never clicks, opens storage, or reads predicted menu slots. */
public final class BotStashObserver {
    private final Bots bots;
    private final Map<String,Watch> watches=new LinkedHashMap<>();
    private final Map<String,Assembly> assemblies=new LinkedHashMap<>();
    private final Map<String,JsonObject> pending=new LinkedHashMap<>();
    private final Map<String,PayloadSize> payloadSizes=new HashMap<>();
    private final Map<BlockPos,BlockState> confirmed=new LinkedHashMap<>();
    private final Set<net.minecraft.world.level.ChunkPos> catchup=new LinkedHashSet<>();
    private final Set<String> savedEvents=new LinkedHashSet<>();
    private final ConcurrentLinkedQueue<JsonObject> localAcks=new ConcurrentLinkedQueue<>();
    private final Map<BlockPos,Integer> openings=new LinkedHashMap<>();
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"monocle-stash-live");t.setDaemon(true);return t;});
    private CompletableFuture<JsonArray> loading;
    private CompletableFuture<Void> saving;
    private final Map<String,JsonObject> flights=new LinkedHashMap<>();
    private SwarmConnection batchConnection;
    private boolean dirty,restored;
    private int tick,menu=-1,menuTick,menuState=-1;
    private long sentAt,loadAt;
    private String world="",owner="";
    private BlockPos opened;
    private BlockState openedState;
    private String openedWorld="";
    private JsonObject openedGeometry;
    private List<ItemStack> contents;
    private int[] containerSlots;
    private boolean menuDirty;
    private long warningAt;
    record PayloadSize(JsonObject entry,String basis,int bytes){}
    private record Watch(JsonObject plan,String scope,String crew,String owner,Map<String,JsonObject> records,Map<net.minecraft.world.level.ChunkPos,Set<BlockPos>> chunks){
        Watch(JsonObject plan,String scope,String crew,String owner,Map<String,JsonObject> records){this(plan,scope,crew,owner,records,indexChunks(records));}
        void record(JsonObject r){BlockPos p=position(r);records.put(StashCatalog.containerKey(r),r);chunks.computeIfAbsent(chunkKey(p),k->new LinkedHashSet<>()).add(p);}
    }
    private static net.minecraft.world.level.ChunkPos chunkKey(BlockPos p){return new net.minecraft.world.level.ChunkPos(p.getX()>>4,p.getZ()>>4);}
    static Map<net.minecraft.world.level.ChunkPos,Set<BlockPos>> indexChunks(Map<String,JsonObject> records){
        Map<net.minecraft.world.level.ChunkPos,Set<BlockPos>> chunks=new HashMap<>();for(JsonObject r:records.values()){BlockPos p=position(r);chunks.computeIfAbsent(chunkKey(p),k->new LinkedHashSet<>()).add(p);}return chunks;
    }
    private static final class Assembly {final JsonObject plan;final String scope,owner;final int parts;final Map<Integer,JsonArray> pages=new HashMap<>();final long created=System.currentTimeMillis();Assembly(JsonObject p,String s,String o,int n){plan=p;scope=s;owner=o;parts=n;}}
    public BotStashObserver(Bots bots){this.bots=bots;}
    private Path root(){return MonocleClient.FOLDER.toPath();}
    private Path outbox(){return root().resolve("stash-live-outbox-sqlite-v1.json");}
    private boolean enabled(){return Modules.get().get(StashManager.class).liveUpdates();}
    private boolean export(){return Modules.get().get(StashManager.class).exportsLiveUpdates();}
    public void ready(JsonObject message,SwarmConnection connection){
        batchConnection=message.has("stashLiveBatchProtocol")&&StashCatalog.integer(message,"stashLiveBatchProtocol",1,1)==1?connection:null;
    }
    private boolean batching(){return batchConnection!=null&&batchConnection==bots.worker;}
    private static BlockPos position(JsonObject o){return new BlockPos(o.get("x").getAsInt(),o.get("y").getAsInt(),o.get("z").getAsInt());}
    private static String key(String scope,String name){return scope+"\n"+name;}
    private String sourceKey(String credential){return bots.ipAddress.get()+":"+bots.serverPort.get()+"#"+credential;}
    private boolean predicted(BlockPos p){return mc.level.getBlockStatePredictionHandler().serverVerifiedStates.containsKey(p.asLong());}
    private static String text(JsonObject o,String k){return TaskWire.text(o,k);}
    private static JsonObject coordinates(BlockPos p){JsonObject o=new JsonObject();o.addProperty("x",p.getX());o.addProperty("y",p.getY());o.addProperty("z",p.getZ());return o;}
    static JsonObject geometry(net.minecraft.world.level.BlockGetter level,BlockPos p){
        if(!(level.getBlockEntity(p) instanceof BaseContainerBlockEntity))return null;
        List<BlockPos> parts=BotStashScan.chestHalves(level,p).stream().sorted(Comparator.comparingLong(BlockPos::asLong)).toList();if(parts.isEmpty())parts=List.of(p);BlockPos anchor=parts.getFirst();
        JsonObject o=coordinates(anchor);o.addProperty("block",BuiltInRegistries.BLOCK.getKey(level.getBlockState(anchor).getBlock()).toString());
        JsonArray halves=new JsonArray();parts.forEach(v->halves.add(coordinates(v)));o.add("parts",halves);
        int capacity=level.getBlockEntity(p) instanceof net.minecraft.world.Container c?c.getContainerSize():27;
        o.addProperty("capacity",parts.size()==2?54:capacity);return o;
    }
    static boolean removedStorage(BlockState state,BlockPos p){
        if(!state.hasBlockEntity())return true;
        if(!(state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock block))return false;
        var entity=block.newBlockEntity(p,state);return entity!=null&&!(entity instanceof BaseContainerBlockEntity);
    }
    static boolean geometryReady(net.minecraft.world.level.BlockGetter world,BlockPos p,java.util.function.Predicate<BlockPos> loaded){
        if(!loaded.test(p))return false;
        var state=world.getBlockState(p);
        return !(state.getBlock() instanceof ChestBlock)||state.getValue(ChestBlock.TYPE)==net.minecraft.world.level.block.state.properties.ChestType.SINGLE||loaded.test(p.relative(ChestBlock.getConnectedDirection(state)));
    }
    static JsonObject observation(JsonObject geometry,List<ItemStack> items,String reason){
        JsonObject o=geometry.deepCopy();o.addProperty("status",items==null?"unscanned":"observed");o.addProperty("reason",reason);
        JsonArray boxes=BotStashScan.classify(items==null?List.of():items);JsonObject totals=items==null?new JsonObject():CrewInventory.manifest(items);
        for(JsonElement value:boxes){JsonObject box=value.getAsJsonObject();if(box.get("legacy").getAsBoolean())box.getAsJsonObject("items").entrySet().forEach(e->totals.addProperty(e.getKey(),(totals.has(e.getKey())?totals.get(e.getKey()).getAsInt():0)+e.getValue().getAsInt()*box.get("quantity").getAsInt()));}
        o.add("items",totals);o.add("shulkers",boxes);if(items!=null)BotStashScan.recordSlots(o,items);return o;
    }
    public void watch(JsonObject m,String source){
        source=sourceKey(source);
        JsonObject plan=StashCatalog.plan(m.getAsJsonObject("stash"));String scope=text(m,"scope"),epoch=text(m,"epoch");UUID.fromString(epoch);
        if(scope.length()>384||!scope.contains("\n"))throw new IllegalArgumentException("Invalid live stash scope");
        int count=StashCatalog.integer(m,"parts",1,StashCatalog.MAX_CONTAINERS/StashCatalog.WATCH_PAGE_SIZE),part=StashCatalog.integer(m,"part",0,count-1);JsonArray records=m.getAsJsonArray("records");if(records.size()>StashCatalog.WATCH_PAGE_SIZE)throw new IllegalArgumentException("Too many watch records");
        assemblies.entrySet().removeIf(e->System.currentTimeMillis()-e.getValue().created>120_000);
        if(!assemblies.containsKey(epoch)&&assemblies.size()>=256)throw new IllegalArgumentException("Too many watch assemblies");
        Assembly a=assemblies.get(epoch);if(a==null){a=new Assembly(plan,scope,source,count);assemblies.put(epoch,a);}
        if(a.parts!=count||!a.plan.equals(plan)||!a.scope.equals(scope)||!a.owner.equals(source))throw new IllegalArgumentException("Changed watch manifest");
        for(JsonElement value:records)validateRecord(plan,value.getAsJsonObject());
        int staged=assemblies.values().stream().flatMap(v->v.pages.values().stream()).mapToInt(JsonArray::size).sum();if(staged+records.size()>StashCatalog.MAX_CONTAINERS*2&&!a.pages.containsKey(part))throw new IllegalArgumentException("Live stash assembly budget exceeded");
        if(a.pages.containsKey(part)&&!a.pages.get(part).equals(records))throw new IllegalArgumentException("Changed watch page");a.pages.put(part,records.deepCopy());
        if(a.pages.size()!=count)return;
        Map<String,JsonObject> all=watchRecords(a.pages,count);
        watches.put(key(scope,text(plan,"name")),new Watch(plan,scope,"Local",source,all));assemblies.remove(epoch);
        if(!owner.equals(source))parkFlight();
        String acceptedSource=source;watches.entrySet().removeIf(e->!e.getValue().owner.isEmpty()&&!e.getValue().owner.equals(acceptedSource));owner=source;discoverLoaded();
    }
    static Map<String,JsonObject> watchRecords(Map<Integer,JsonArray> pages,int count){
        if(count<1||count>StashCatalog.MAX_CONTAINERS/StashCatalog.WATCH_PAGE_SIZE)throw new IllegalArgumentException("Invalid watch page count");
        Map<String,JsonObject> all=new LinkedHashMap<>();
        for(int i=0;i<count;i++){
            JsonArray page=pages.get(i);if(page==null||page.size()>StashCatalog.WATCH_PAGE_SIZE)throw new IllegalArgumentException("Invalid watch page");
            for(JsonElement v:page){JsonObject r=v.getAsJsonObject();if(all.put(StashCatalog.containerKey(r),r)!=null)throw new IllegalArgumentException("Duplicate watch coordinate");}
        }
        return all;
    }
    static void validateRecord(JsonObject plan,JsonObject r){
        TaskFiles.jsonBytes(r,1024);
        int x=StashCatalog.integer(r,"x",-29_900_000,29_900_000),y=StashCatalog.integer(r,"y",-2048,2048),z=StashCatalog.integer(r,"z",-29_900_000,29_900_000);
        if(!StashCatalog.contains(plan,x,y,z)||!text(r,"basis").matches("[a-f0-9]{64}")||!Set.of("observed","unscanned","removed").contains(text(r,"status")))throw new IllegalArgumentException("Invalid watch record");
        JsonObject checked=r.deepCopy();checked.add("items",new JsonObject());checked.add("shulkers",new JsonArray());StashCatalog.observation(plan,checked);
    }
    void scanSaved(String name,JsonObject record){
        for(Watch w:watches.values())if(w.scope.equals(world)&&text(w.plan,"name").equals(name)&&w.owner.equals(owner)){
            validateRecord(w.plan,record);w.record(record.deepCopy());
        }
    }
    public void acknowledge(JsonObject ack,String source){
        acknowledgeOwned(ack,sourceKey(source));
    }
    void acknowledgeOwned(JsonObject ack,String source){
        JsonObject flight=flights.get(text(ack,"eventId"));if(flight==null||!source.equals(text(flight,"owner")))return;
        String outcome=text(ack,"outcome");if(outcome.equals("deferred")){sentAt=System.currentTimeMillis();return;}
        if(!Set.of("applied","unchanged","conflict","retired").contains(outcome))throw new IllegalArgumentException("Invalid live stash acknowledgement");
        Watch w=watches.get(text(flight,"watchKey"));String cell=StashCatalog.containerKey(flight.getAsJsonObject("observation"));
        if(w!=null&&ack.has("record")){JsonObject record=ack.getAsJsonObject("record");validateRecord(w.plan,record);if(!StashCatalog.containerKey(record).equals(cell))throw new IllegalArgumentException("Mismatched live acknowledgement");w.record(record.deepCopy());}
        String queueKey=text(flight,"queueKey");
        if(outcome.equals("conflict")||outcome.equals("retired")){pending.remove(queueKey);if(outcome.equals("retired"))watches.remove(text(flight,"watchKey"));else warn("Live storage changed before delivery; stale snapshot discarded at "+cell+". Reopen or rescan to refresh contents.");}
        else if(pending.containsKey(queueKey))pending.get(queueKey).addProperty("basis",text(ack,"basis"));
        flights.remove(text(ack,"eventId"));dirty=true;if(!outcome.equals("deferred"))sentAt=0;
    }
    public void acknowledgeBatch(JsonObject ack,String source){
        TaskFiles.jsonBytes(ack,StashCatalog.LIVE_BATCH_BYTES);JsonArray entries=ack.getAsJsonArray("entries");
        if(entries==null||entries.isEmpty()||entries.size()>StashCatalog.LIVE_BATCH_SIZE)throw new IllegalArgumentException("Invalid live stash batch acknowledgement");
        Set<String> events=new HashSet<>();for(JsonElement value:entries){JsonObject entry=value.getAsJsonObject();UUID.fromString(text(entry,"eventId"));if(!events.add(text(entry,"eventId"))||!Set.of("applied","unchanged","conflict","retired","deferred").contains(text(entry,"outcome")))throw new IllegalArgumentException("Invalid live stash batch acknowledgement entry");}
        String owner=sourceKey(source);for(JsonElement value:entries)acknowledgeOwned(value.getAsJsonObject(),owner);
    }
    public void sent(net.minecraft.network.protocol.Packet<?> packet){
        if(!(packet instanceof ServerboundUseItemOnPacket))return;
        if(!mc.isSameThread()){mc.execute(()->sent(packet));return;}
        if(!enabled()||mc.level==null)return;
        if(packet instanceof ServerboundUseItemOnPacket p){BlockPos pos=p.getHitResult().getBlockPos();
            if(bots.tasks().ownsScanRead(pos)||Modules.get().get(StashManager.class).ownsScanRead(pos))return;
            if(geometry(mc.level,pos)!=null){openings.put(pos.immutable(),tick);while(openings.size()>8)openings.remove(openings.keySet().iterator().next());}}
    }
    public void opened(int id){
        flushMenu();clearMenu();if(!enabled()||mc.level==null||mc.player==null)return;
        openings.entrySet().removeIf(e->tick-e.getValue()>40);
        Set<BlockPos> targets=new HashSet<>();for(BlockPos p:openings.keySet()){JsonObject g=geometry(mc.level,p);if(g!=null)targets.add(position(g));}
        openings.clear();if(targets.size()!=1)return;
        BlockPos p=targets.iterator().next();JsonObject candidate=geometry(mc.level,p);if(watches.values().stream().noneMatch(w->w.scope.equals(world)&&ownedGeometry(w.plan,candidate)!=null))return;
        opened=p;openedState=mc.level.getBlockState(p);openedWorld=world;menu=id;
    }
    private void clearMenu(){opened=null;openedGeometry=null;contents=null;containerSlots=null;menu=-1;menuState=-1;menuDirty=false;openedWorld="";}
    public void inventory(InventoryEvent e){
        if(opened==null||mc.player==null||mc.level==null||!openedWorld.equals(world)||e.packet.containerId()!=menu||mc.player.containerMenu.containerId!=menu)return;
        JsonObject g=geometry(mc.level,opened);if(g==null||!mc.level.getBlockState(opened).equals(openedState))return;
        List<Integer> indexes=new ArrayList<>();for(int i=0;i<mc.player.containerMenu.slots.size();i++){var slot=mc.player.containerMenu.slots.get(i);if(slot.container!=mc.player.getInventory()&&!slot.isFake())indexes.add(i);}
        if(indexes.size()!=g.get("capacity").getAsInt()||indexes.stream().anyMatch(i->i>=e.packet.items().size()))return;
        openedGeometry=g;containerSlots=indexes.stream().mapToInt(Integer::intValue).toArray();contents=new ArrayList<>();for(int i:containerSlots)contents.add(e.packet.items().get(i).copy());menuState=e.packet.stateId();menuTick=tick;menuDirty=true;
    }
    public void slot(ContainerSlotUpdateEvent e){
        if(contents==null||e.packet.getContainerId()!=menu||!freshState(menuState,e.packet.getStateId()))return;
        for(int i=0;i<containerSlots.length;i++)if(containerSlots[i]==e.packet.getSlot()){contents.set(i,e.packet.getItem().copy());menuState=e.packet.getStateId();menuTick=tick;menuDirty=true;break;}
    }
    static boolean freshState(int before,int after){return before<0||((after-before)&32767)<16384;}
    private void flushMenu(){if(!menuDirty||openedGeometry==null)return;JsonObject o=observation(openedGeometry,contents,"Server-confirmed live container contents");for(Watch w:List.copyOf(watches.values()))if(w.scope.equals(openedWorld))publishTo(w,o);menuDirty=false;}
    public void block(BlockPos p,BlockState state){if(!enabled())return;if(confirmed.size()<StashCatalog.MAX_CONTAINERS*2||confirmed.containsKey(p))confirmed.put(p.immutable(),state);}
    public void chunk(LevelChunk c){if(!enabled()||mc.level==null||catchup.size()>=4096)return;
        for(Watch w:watches.values())if(w.scope.equals(world)&&intersectsChunk(w.plan,c.getPos())){catchup.add(c.getPos());return;}
    }
    static boolean intersectsChunk(JsonObject plan,net.minecraft.world.level.ChunkPos p){
        return p.x()>=(plan.get("minX").getAsInt()>>4)&&p.x()<=(plan.get("maxX").getAsInt()>>4)&&p.z()>=(plan.get("minZ").getAsInt()>>4)&&p.z()<=(plan.get("maxZ").getAsInt()>>4);
    }
    private void discoverChunk(LevelChunk c){
        Set<BlockPos> targets=new LinkedHashSet<>();for(Watch w:watches.values())if(w.scope.equals(world)&&intersectsChunk(w.plan,c.getPos())){
            targets.addAll(w.chunks.getOrDefault(c.getPos(),Set.of()));
            for(BlockPos p:c.getBlockEntities().keySet())if(StashCatalog.contains(w.plan,p.getX(),p.getY(),p.getZ()))targets.add(p);
        }
        for(BlockPos p:targets)block(p,c.getBlockState(p));
    }
    private void discoverLoaded(){if(!enabled()||mc.level==null||mc.player==null)return;int radius=Math.min(32,mc.options.renderDistance().get()),cx=mc.player.getBlockX()>>4,cz=mc.player.getBlockZ()>>4;
        for(Watch w:watches.values())if(w.scope.equals(world))for(int x=Math.max(cx-radius,w.plan.get("minX").getAsInt()>>4);x<=Math.min(cx+radius,w.plan.get("maxX").getAsInt()>>4);x++)for(int z=Math.max(cz-radius,w.plan.get("minZ").getAsInt()>>4);z<=Math.min(cz+radius,w.plan.get("maxZ").getAsInt()>>4);z++)if(catchup.size()<4096&&mc.level.getChunkSource().hasChunk(x,z))catchup.add(new net.minecraft.world.level.ChunkPos(x,z));
    }
    public void reconcile(BlockPos p){if(enabled()&&mc.level!=null&&!predicted(p)&&mc.level.getChunkSource().hasChunk(p.getX()>>4,p.getZ()>>4))block(p,mc.level.getBlockState(p));}
    public boolean knownSource(BlockPos p){String cell=p.getX()+","+p.getY()+","+p.getZ();return watches.values().stream().filter(w->w.scope.equals(world)).map(w->w.records.get(cell)).anyMatch(r->r!=null&&!text(r,"status").equals("removed"));}
    boolean knownOccupancy(JsonObject action,BlockPos p,boolean full){
        if(action.has("sortBatch")||dev.monocle.coordinator.TaskWire.flag(action,"requireLocal"))return false;
        Watch w=watches.get(key(world,text(action,"name")));if(w==null||!w.owner.isEmpty()&&!w.owner.equals(owner))return false;
        String cell=p.getX()+","+p.getY()+","+p.getZ();JsonObject record=latest(w,cell);
        if(record!=null&&!text(record,"status").equals("removed"))return observedOccupancy(record,full);
        for(BlockPos anchor:relatedAnchors(w.records,p))if(observedOccupancy(latest(w,anchor.getX()+","+anchor.getY()+","+anchor.getZ()),full))return true;
        return false;
    }
    static boolean observedOccupancy(JsonObject record,boolean full){
        if(record==null||!text(record,"status").equals("observed")||dev.monocle.coordinator.TaskWire.flag(record,"inferred")||!record.has("slotCount")||!record.has("occupiedSlots")||!record.has("observedAt"))return false;
        long at=record.get("observedAt").getAsLong(),now=System.currentTimeMillis();if(at<=0||at>now||now-at>86_400_000L)return false;
        int slots=record.get("slotCount").getAsInt(),occupied=record.get("occupiedSlots").getAsInt();
        return slots>0&&occupied==(full?slots:0);
    }
    public boolean catalogReady(){return restored;}
    private void physical(BlockPos p,BlockState confirmedState){
        if(mc.level==null||predicted(p)||!geometryReady(mc.level,p,v->mc.level.getChunkSource().hasChunk(v.getX()>>4,v.getZ()>>4))||!mc.level.getBlockState(p).equals(confirmedState))return;
        JsonObject g=geometry(mc.level,p);
        if(g!=null&&g.getAsJsonArray("parts").asList().stream().anyMatch(v->predicted(position(v.getAsJsonObject())))){confirmed.put(p,confirmedState);return;}
        for(Watch w:List.copyOf(watches.values()))if(w.scope.equals(world)&&StashCatalog.contains(w.plan,p.getX(),p.getY(),p.getZ())){
            JsonObject prior=w.records.get(p.getX()+","+p.getY()+","+p.getZ());
            if(g!=null){JsonObject owned=ownedGeometry(w.plan,g);if(owned!=null&&!sameGeometry(latest(w,StashCatalog.containerKey(owned)),owned))publishTo(w,observation(owned,null,"Storage discovered; contents not yet observed"));}
            else if(prior!=null&&!text(prior,"status").equals("removed")&&removedStorage(confirmedState,p)){
                JsonObject removed=coordinates(p);removed.addProperty("block",BuiltInRegistries.BLOCK.getKey(confirmedState.getBlock()).toString());removed.addProperty("status","removed");removed.addProperty("reason","Server-confirmed storage removal");removed.add("items",new JsonObject());removed.add("shulkers",new JsonArray());publishTo(w,removed);
                if(prior.has("parts"))for(JsonElement v:prior.getAsJsonArray("parts")){BlockPos other=position(v.getAsJsonObject());if(!other.equals(p))reconcile(other);}
            }
            // Changes to the non-canonical half also invalidate or resize the physical double chest.
            for(BlockPos anchor:relatedAnchors(w.records,p))reconcile(anchor);
        }
    }
    static boolean sameGeometry(JsonObject known,JsonObject o){
        return known!=null&&!text(known,"status").equals("removed")&&text(known,"block").equals(text(o,"block"))&&Objects.equals(known.get("parts"),o.get("parts"))&&Objects.equals(known.get("capacity"),o.get("capacity"));
    }
    private JsonObject latest(Watch w,String cell){
        String queueKey=key(w.scope,text(w.plan,"name"))+"\n"+w.owner+"\n"+cell;
        return latest(w.records.get(cell),pending.get(queueKey),flights.values(),queueKey);
    }
    static JsonObject latest(JsonObject known,JsonObject queued,Collection<JsonObject> flights,String queueKey){
        if(queued!=null)return queued.getAsJsonObject("observation");
        for(JsonObject flight:flights)if(text(flight,"queueKey").equals(queueKey))return flight.getAsJsonObject("observation");
        return known;
    }
    static List<BlockPos> relatedAnchors(Map<String,JsonObject> records,BlockPos p){
        List<BlockPos> anchors=new ArrayList<>();
        // A double chest's other half is always horizontally adjacent, regardless of stash size.
        for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL){BlockPos adjacent=p.relative(direction);JsonObject record=records.get(adjacent.getX()+","+adjacent.getY()+","+adjacent.getZ());
            if(record!=null&&record.has("parts"))for(JsonElement v:record.getAsJsonArray("parts"))if(position(v.getAsJsonObject()).equals(p))anchors.add(adjacent);
        }
        return anchors;
    }
    private void publishTo(Watch w,JsonObject o){
        o=ownedGeometry(w.plan,o);if(o==null)return;
        String cell=StashCatalog.containerKey(o),watchKey=key(w.scope,text(w.plan,"name")),queueKey=watchKey+"\n"+w.owner+"\n"+cell;
        JsonObject known=w.records.get(cell),latest=latest(w,cell);
        if((text(o,"status").equals("unscanned")&&sameGeometry(latest,o))||o.equals(latest))return;
        if(pending.size()+flights.size()>=256&&!pending.containsKey(queueKey)){warn("Live stash outbox full; rescan needed for uncaptured updates");return;}
        JsonObject m=new JsonObject();m.addProperty("watchKey",watchKey);m.addProperty("queueKey",queueKey);m.addProperty("scope",w.scope);m.addProperty("crew",w.crew);m.addProperty("owner",w.owner);m.addProperty("name",text(w.plan,"name"));m.addProperty("basis",known==null?"":text(known,"basis"));m.addProperty("eventId",UUID.randomUUID().toString());m.add("observation",StashCatalog.observation(w.plan,o));m.add("plan",w.plan.deepCopy());
        m.addProperty("worker",mc.getUser().getProfileId().toString());
        long bytes=payloadBytes(m,payloadSizes);
        for(JsonObject flight:flights.values())bytes+=payloadBytes(flight,payloadSizes);
        for(var entry:pending.entrySet())if(!entry.getKey().equals(queueKey))bytes+=payloadBytes(entry.getValue(),payloadSizes);
        if(bytes>3*1024*1024){warn("Live stash byte budget full; rescan needed for uncaptured updates");return;}
        pending.put(queueKey,m);dirty=true;
    }
    static int payloadBytes(JsonObject entry,Map<String,PayloadSize> sizes){
        String id=text(entry,"eventId"),basis=text(entry,"basis");PayloadSize size=sizes.get(id);
        // Queued observations are immutable; only their CAS basis changes after an acknowledgement.
        if(size==null||size.entry!=entry||!size.basis.equals(basis)){size=new PayloadSize(entry,basis,entry.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);sizes.put(id,size);}return size.bytes;
    }
    static JsonObject ownedGeometry(JsonObject plan,JsonObject o){
        if(o==null)return null;
        if(StashCatalog.contains(plan,o.get("x").getAsInt(),o.get("y").getAsInt(),o.get("z").getAsInt()))return o;
        if(o.has("parts"))for(JsonElement part:o.getAsJsonArray("parts")){JsonObject half=part.getAsJsonObject();if(StashCatalog.contains(plan,half.get("x").getAsInt(),half.get("y").getAsInt(),half.get("z").getAsInt())){JsonObject anchored=o.deepCopy();for(String key:List.of("x","y","z"))anchored.add(key,half.get(key));return anchored;}}
        return null;
    }
    private void warn(String text){if(System.currentTimeMillis()-warningAt<5000)return;warningAt=System.currentTimeMillis();MonocleClient.LOG.warn("[Workers] {}",text);}
    static void park(Map<String,JsonObject> pending,JsonObject flight){if(flight!=null)pending.putIfAbsent(text(flight,"queueKey"),flight);}
    private void parkFlight(){if(!flights.isEmpty()){flights.values().forEach(flight->park(pending,flight));flights.clear();dirty=true;}}
    static void journalFirst(java.nio.file.Path path,JsonObject journal,java.util.function.Consumer<JsonObject> apply){
        TaskFiles.write(path,journal);
        for(JsonElement entry:journal.getAsJsonArray("pending"))apply.accept(entry.getAsJsonObject());
    }
    public void tick(){
        tick++;String scope=mc.level==null?"":(mc.getCurrentServer()==null?"local":dev.monocle.client.utils.Utils.getServerIdentity())+"\n"+mc.level.dimension().identifier();
        if(!scope.equals(world)){flushMenu();parkFlight();clearMenu();openings.clear();confirmed.clear();catchup.clear();world=scope;loadAt=0;}
        if(!bots.isWorker()&&!owner.isEmpty()){owner="";parkFlight();}
        if(!enabled()){clearMenu();confirmed.clear();catchup.clear();}
        for(JsonObject ack;(ack=localAcks.poll())!=null;){Watch w=watches.get(text(ack,"watchKey"));if(w!=null&&w.owner.isEmpty()&&ack.has("record")){
            String cell=StashCatalog.containerKey(ack.getAsJsonObject("record")),queueKey=text(ack,"watchKey")+"\n\n"+cell;w.record(ack.getAsJsonObject("record"));
            JsonObject newer=pending.get(queueKey);if(newer!=null&&!text(newer,"eventId").equals(text(ack,"eventId"))){
                if(Set.of("applied","unchanged").contains(text(ack,"outcome")))newer.addProperty("basis",text(ack,"basis"));else {pending.remove(queueKey);warn("Local storage changed before delivery; reopen or rescan "+cell+" to refresh contents");}dirty=true;
            }
        }}
        if(menuDirty&&(tick-menuTick>=2||mc.player==null||mc.player.containerMenu.containerId!=menu)){flushMenu();if(mc.player==null||mc.player.containerMenu.containerId!=menu)clearMenu();}
        if(loading==null&&System.currentTimeMillis()-loadAt>10_000){loadAt=System.currentTimeMillis();boolean host=bots.isHost(),restore=!restored;
            Set<String> hostOwned=new HashSet<>();watches.forEach((key,watch)->{if(!watch.owner.isEmpty())hostOwned.add(key);});loading=CompletableFuture.supplyAsync(()->{
            JsonArray data=StashCatalog.localWatches(root(),host,hostOwned);
            if(restore){JsonObject journal=TaskFiles.read(outbox());TaskFiles.jsonBytes(journal,4*1024*1024);if(journal.has("pending"))data.add(journal);}return data;
        },writer);}
        if(loading!=null&&loading.isDone()){
            try{Set<String> localKeys=new HashSet<>();for(JsonElement v:loading.join()){JsonObject db=v.getAsJsonObject();if(db.has("pending")){for(JsonElement queued:db.getAsJsonArray("pending")){JsonObject q=queued.getAsJsonObject();if(pending.size()<256){StashCatalog.observation(StashCatalog.plan(q.getAsJsonObject("plan")),q.getAsJsonObject("observation"));pending.putIfAbsent(text(q,"queueKey"),q);}else warn("Live stash restore budget exceeded; rescan needed for uncaptured updates");}continue;}
                String k=key(text(db,"scope"),text(db,"name"));localKeys.add(k);if(watches.containsKey(k)&&!watches.get(k).owner.isEmpty())continue;
                JsonObject plan=StashCatalog.plan(db.getAsJsonObject("bounds"));plan.addProperty("workerCount",1);plan.addProperty("workerIndex",0);
                Map<String,JsonObject> records=new LinkedHashMap<>();db.getAsJsonObject("containers").entrySet().forEach(e->records.put(e.getKey(),e.getValue().getAsJsonObject()));watches.put(k,new Watch(plan,text(db,"scope"),text(db,"crew"),"",records));}
                watches.entrySet().removeIf(e->e.getValue().owner.isEmpty()&&!localKeys.contains(e.getKey()));
                restored=true;discoverLoaded();
            }catch(RuntimeException e){warn("Live stash catalog load failed: "+e.getMessage());}loading=null;
        }
        Set<String> queuedEvents=new HashSet<>(flights.keySet());pending.values().forEach(m->queuedEvents.add(text(m,"eventId")));payloadSizes.keySet().retainAll(queuedEvents);
        if(enabled()&&mc.level!=null){for(int i=0;i<2&&!catchup.isEmpty();i++){var p=catchup.iterator().next();catchup.remove(p);if(mc.level.getChunkSource().hasChunk(p.x(),p.z()))discoverChunk(mc.level.getChunk(p.x(),p.z()));}
            long deadline=System.nanoTime()+2_000_000;
            for(int i=0;i<32&&!confirmed.isEmpty()&&pending.size()+flights.size()<256&&System.nanoTime()<deadline;i++){var entry=confirmed.entrySet().iterator().next();confirmed.remove(entry.getKey());if(predicted(entry.getKey())){confirmed.put(entry.getKey(),entry.getValue());continue;}physical(entry.getKey(),entry.getValue());}}
        if(saving!=null){if(!saving.isDone())return;try{saving.join();}catch(RuntimeException e){warn("Live stash save failed; queued updates retained: "+e.getMessage());dirty=true;}saving=null;}
        if(dirty){JsonArray queue=new JsonArray();flights.values().forEach(m->queue.add(m.deepCopy()));pending.values().forEach(m->queue.add(m.deepCopy()));JsonObject journal=new JsonObject();journal.addProperty("version",1);journal.add("pending",queue);TaskFiles.jsonBytes(journal,4*1024*1024);
            saving=CompletableFuture.runAsync(()->journalFirst(outbox(),journal,m->{if(!savedEvents.contains(text(m,"eventId"))){
                JsonObject ack=StashCatalog.acceptLocalLive(root(),m);ack.add("watchKey",m.get("watchKey"));localAcks.add(ack);
                savedEvents.add(text(m,"eventId"));while(savedEvents.size()>512)savedEvents.remove(savedEvents.iterator().next());}}),writer);dirty=false;return;}
        if(!flights.isEmpty()){JsonObject first=flights.values().iterator().next();if(export()&&bots.isWorker()&&world.equals(text(first,"scope"))&&owner.equals(text(first,"owner"))&&System.currentTimeMillis()-sentAt>5000)send();return;}
        for(var iterator=pending.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();JsonObject m=entry.getValue();if(!text(m,"scope").equals(world))continue;
            if(!text(m,"owner").isEmpty()&&(!export()||!bots.isWorker()||!text(m,"owner").equals(owner)))continue;
            if(text(m,"owner").isEmpty()){iterator.remove();dirty=true;continue;}
            if(!fitsBatch(flights.values(),m,batching()))break;
            flights.put(text(m,"eventId"),m);sentAt=0;iterator.remove();dirty=true;
            if(flights.size()>=(batching()?StashCatalog.LIVE_BATCH_SIZE:1))break;
        }
    }
    static JsonObject liveWire(JsonObject entry){JsonObject wire=TaskWire.message("stash-live");for(String field:List.of("name","scope","basis","eventId"))wire.add(field,entry.get(field).deepCopy());StashCatalog.attachObservation(wire,entry.getAsJsonObject("observation"));return wire;}
    static boolean fitsBatch(Collection<JsonObject> selected,JsonObject candidate,boolean batch){
        if(selected.isEmpty())return true;if(!batch||selected.size()>=StashCatalog.LIVE_BATCH_SIZE)return false;
        JsonObject wire=TaskWire.message("stash-live-batch");JsonArray entries=new JsonArray();selected.forEach(m->entries.add(liveWire(m)));entries.add(liveWire(candidate));wire.add("entries",entries);
        try{StashCatalog.liveBatch(wire);return true;}catch(IllegalArgumentException e){return false;}
    }
    private void send(){JsonObject first=flights.values().iterator().next(),wire=liveWire(first);
        if(batching()&&flights.size()>1){wire=TaskWire.message("stash-live-batch");JsonArray entries=new JsonArray();flights.values().forEach(m->entries.add(liveWire(m)));wire.add("entries",entries);StashCatalog.liveBatch(wire);}
        if(bots.sendToHost(wire))sentAt=System.currentTimeMillis();
    }
}
