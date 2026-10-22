package dev.monocle.client.systems.bots;

import com.google.gson.*;
import dev.monocle.coordinator.StashCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import java.util.*;

public final class BotStashObserverTest {
    static void run() throws Exception {
        var directory=java.nio.file.Files.createTempDirectory("monocle-live-journal-");var path=directory.resolve("outbox.json");
        JsonObject journal=JsonParser.parseString("{pending:[{eventId:'recover-after-crash'}]}").getAsJsonObject();
        try{BotStashObserver.journalFirst(path,journal,m->{assert dev.monocle.coordinator.TaskFiles.read(path).equals(journal);throw new IllegalStateException("Simulated crash during local apply");});throw new AssertionError("Crash not simulated");}catch(IllegalStateException expected){}
        assert dev.monocle.coordinator.TaskFiles.read(path).equals(journal) : "Remote intent survives a crash during local application";
        final boolean[] applied={false};var blocked=directory.resolve("not-a-directory");java.nio.file.Files.createFile(blocked);
        try{BotStashObserver.journalFirst(blocked.resolve("outbox.json"),journal,m->applied[0]=true);throw new AssertionError("Invalid journal path accepted");}catch(IllegalStateException expected){}
        assert !applied[0] : "Local state cannot advance when journaling failed";
        java.nio.file.Files.delete(path);java.nio.file.Files.delete(blocked);java.nio.file.Files.delete(directory);
        net.minecraft.nbt.CompoundTag oldSetting=new net.minecraft.nbt.CompoundTag();oldSetting.putInt("value",10);assert oldSetting.getDoubleOr("value",0d)==10d : "DoubleSetting reads existing integer minutes without a unit migration";
        var encoded=new net.minecraft.nbt.CompoundTag();encoded.putDouble("value",dev.monocle.client.systems.modules.world.StashManager.cooldownMinutes(320));assert dev.monocle.client.systems.modules.world.StashManager.cooldownTicks(encoded.getDoubleOr("value",0d))==320 : "A 16-second cooldown survives fractional-minute NBT round trips";
        var states=new HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        var entities=new HashMap<BlockPos,net.minecraft.world.level.block.entity.BlockEntity>();
        var world=new net.minecraft.world.level.BlockGetter(){
            public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos p){return entities.get(p);}
            public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos p){return states.getOrDefault(p,Blocks.AIR.defaultBlockState());}
            public net.minecraft.world.level.material.FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
            public int getMinY(){return -64;}public int getHeight(){return 384;}
        };
        BlockPos p=new BlockPos(0,64,0);var single=Blocks.CHEST.defaultBlockState();states.put(p,single);entities.put(p,new net.minecraft.world.level.block.entity.ChestBlockEntity(p,single));
        assert BotStashObserver.geometry(world,p).get("capacity").getAsInt()==27;
        var left=single.setValue(ChestBlock.TYPE,ChestType.LEFT);BlockPos other=p.relative(ChestBlock.getConnectedDirection(left));var right=single.setValue(ChestBlock.TYPE,ChestType.RIGHT);
        states.put(p,left);states.put(other,right);entities.put(other,new net.minecraft.world.level.block.entity.ChestBlockEntity(other,right));
        assert !BotStashObserver.geometryReady(world,p,v->!v.equals(other))&&BotStashObserver.geometryReady(world,p,v->true) : "A double chest cannot shrink because its partner's chunk is unloaded";
        assert BotStashObserver.geometry(world,p).equals(BotStashObserver.geometry(world,other))&&BotStashObserver.geometry(world,p).get("capacity").getAsInt()==54 : "Either half resolves the same physical double chest";
        JsonObject halfBounds=JsonParser.parseString("{minX:"+other.getX()+",maxX:"+other.getX()+",minY:64,maxY:64,minZ:"+other.getZ()+",maxZ:"+other.getZ()+"}").getAsJsonObject();
        assert BotStashObserver.ownedGeometry(halfBounds,BotStashObserver.geometry(world,p)).get("x").getAsInt()==other.getX() : "A selection containing only the other half retains its actual full menu";
        entities.remove(p);states.remove(p);assert BotStashObserver.geometry(world,p)==null&&BotStashObserver.geometry(world,other).get("capacity").getAsInt()==27;
        states.put(p,Blocks.HOPPER.defaultBlockState());entities.put(p,new net.minecraft.world.level.block.entity.HopperBlockEntity(p,states.get(p)));
        assert BotStashObserver.geometry(world,p).get("capacity").getAsInt()==5 : "Non-chest storage never assumes a double chest";
        assert BotStashObserver.removedStorage(Blocks.AIR.defaultBlockState(),p)&&BotStashObserver.removedStorage(Blocks.ENDER_CHEST.defaultBlockState(),p)&&!BotStashObserver.removedStorage(Blocks.CHEST.defaultBlockState(),p) : "Unloaded chest metadata is not removal; a replacement private ender chest is no longer stash storage";
        JsonObject geometry=BotStashObserver.geometry(world,other),unknown=BotStashObserver.observation(geometry,null,"Storage discovered; contents not yet observed");
        assert unknown.get("status").getAsString().equals("unscanned")&&!unknown.has("occupiedSlots")&&unknown.getAsJsonObject("items").isEmpty();
        var stacks=new ArrayList<net.minecraft.world.item.ItemStack>();for(int i=0;i<27;i++)stacks.add(net.minecraft.world.item.ItemStack.EMPTY);stacks.set(26,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OBSIDIAN,64));
        JsonObject contents=BotStashObserver.observation(geometry,stacks,"contents");assert contents.get("occupiedSlots").getAsInt()==1&&contents.getAsJsonObject("items").get("minecraft:obsidian").getAsInt()==64;
        JsonObject plan=StashCatalog.plan(JsonParser.parseString("{name:'Live',homeName:'live',minX:-2,maxX:2,minY:64,maxY:64,minZ:-2,maxZ:2}").getAsJsonObject());JsonObject record=StashCatalog.watchRecord(contents);BotStashObserver.validateRecord(plan,record);
        record.addProperty("basis","not-a-hash");try{BotStashObserver.validateRecord(plan,record);throw new AssertionError("Invalid basis accepted");}catch(IllegalArgumentException expected){}
        assert BotStashObserver.freshState(32767,0)&&BotStashObserver.freshState(8,8)&&!BotStashObserver.freshState(8,7) : "Menu sequence wrap is valid, older deltas are not";
        Map<String,JsonObject> queue=new LinkedHashMap<>();JsonObject old=new JsonObject();old.addProperty("queueKey","same chest");old.addProperty("eventId","old");JsonObject newer=old.deepCopy();newer.addProperty("eventId","new");queue.put("same chest",newer);
        BotStashObserver.park(queue,old);assert queue.get("same chest").equals(newer) : "Reconnect/world change cannot overwrite a newer queued snapshot with the old in-flight one";
        queue.clear();BotStashObserver.park(queue,old);assert queue.get("same chest").equals(old) : "An unacknowledged snapshot survives reconnect when nothing newer exists";
        JsonObject upper=geometry.deepCopy();upper.addProperty("y",65);upper.getAsJsonArray("parts").get(0).getAsJsonObject().addProperty("y",65);
        JsonObject inferred=BotStashScan.inferredObservation(contents,upper);assert inferred.get("y").getAsInt()==65&&inferred.getAsJsonArray("parts").get(0).getAsJsonObject().get("y").getAsInt()==65&&contents.get("y").getAsInt()==64 : "Lazy inference copies contents, not another chest's physical coordinates";
        JsonObject larger=upper.deepCopy();larger.addProperty("capacity",54);assert BotStashScan.inferredObservation(contents,larger)==null : "A differently sized chest needs a real read, not a copied slot count";
        Map<String,JsonObject> watched=new LinkedHashMap<>();JsonObject paired=geometry.deepCopy();paired.add("parts",JsonParser.parseString("[{x:0,y:64,z:0},{x:1,y:64,z:0}]").getAsJsonArray());paired.addProperty("x",0);paired.addProperty("z",0);watched.put("0,64,0",paired);
        assert BotStashObserver.relatedAnchors(watched,new BlockPos(1,64,0)).equals(List.of(new BlockPos(0,64,0))) : "A noncanonical half updates its adjacent anchor";
        assert BotStashObserver.relatedAnchors(watched,new BlockPos(0,65,0)).isEmpty() : "Other rows do not share double-chest geometry";
        Map<Integer,JsonArray> pages=new LinkedHashMap<>();int count=StashCatalog.MAX_CONTAINERS/StashCatalog.WATCH_PAGE_SIZE;
        for(int i=0;i<count;i++){JsonArray page=new JsonArray();for(int j=0;j<StashCatalog.WATCH_PAGE_SIZE;j++){JsonObject r=new JsonObject();r.addProperty("x",i*StashCatalog.WATCH_PAGE_SIZE+j);r.addProperty("y",64);r.addProperty("z",0);page.add(r);}pages.put(i,page);}
        assert BotStashObserver.watchRecords(pages,count).size()==StashCatalog.MAX_CONTAINERS : "A complete large baseline fits without truncation";
        pages.get(count-1).set(0,pages.get(0).get(0));try{BotStashObserver.watchRecords(pages,count);throw new AssertionError("Duplicate large-baseline coordinate accepted");}catch(IllegalArgumentException expected){}
        pages.remove(count-1);try{BotStashObserver.watchRecords(pages,count);throw new AssertionError("Missing large-baseline page accepted");}catch(IllegalArgumentException expected){}
        discovery();
        liveBatches();
    }
    @SuppressWarnings("unchecked")
    private static void discovery() throws Exception {
        Map<String,JsonObject> records=new LinkedHashMap<>();for(int x=-32;x<32;x++){JsonObject r=liveEntry(x).getAsJsonObject("observation");records.put(StashCatalog.containerKey(r),r);}
        var index=BotStashObserver.indexChunks(records);assert index.size()==4&&index.values().stream().mapToInt(Set::size).sum()==64;
        assert index.get(new net.minecraft.world.level.ChunkPos(-1,0)).size()==16 : "Chunk discovery visits only that chunk's known coordinates, including negative coordinates";
        JsonObject bounds=JsonParser.parseString("{minX:-17,maxX:16,minZ:-1,maxZ:0}").getAsJsonObject();
        assert BotStashObserver.intersectsChunk(bounds,new net.minecraft.world.level.ChunkPos(-2,-1))&&BotStashObserver.intersectsChunk(bounds,new net.minecraft.world.level.ChunkPos(1,0))&&!BotStashObserver.intersectsChunk(bounds,new net.minecraft.world.level.ChunkPos(2,0));
        JsonObject known=liveEntry(0).getAsJsonObject("observation"),flight=liveEntry(0);flight.addProperty("queueKey","cell");flight.getAsJsonObject("observation").addProperty("capacity",27);
        JsonObject queued=flight.deepCopy();queued.getAsJsonObject("observation").addProperty("status","observed");queued.getAsJsonObject("observation").addProperty("capacity",54);
        assert BotStashObserver.latest(known,queued,List.of(flight),"cell")==queued.getAsJsonObject("observation") : "Newest queued geometry wins over in-flight and acknowledged records";
        assert BotStashObserver.latest(known,null,List.of(flight),"cell")==flight.getAsJsonObject("observation");
        assert BotStashObserver.latest(known,null,List.of(flight),"other")==known;
        assert BotStashObserver.sameGeometry(queued.getAsJsonObject("observation"),known)&&!BotStashObserver.sameGeometry(flight.getAsJsonObject("observation"),known) : "Discovery preserves queued contents but never hides a resize or a revert to old geometry";
        JsonObject removed=known.deepCopy();removed.addProperty("status","removed");assert !BotStashObserver.sameGeometry(removed,known) : "Recreated storage still needs a new report";
        var observer=new BotStashObserver(null);var type=Class.forName(BotStashObserver.class.getName()+"$Watch");var constructor=type.getDeclaredConstructor(JsonObject.class,String.class,String.class,String.class,Map.class);constructor.setAccessible(true);
        var watch=constructor.newInstance(bounds,"world","crew","owner",new LinkedHashMap<String,JsonObject>());
        var publish=BotStashObserver.class.getDeclaredMethod("publishTo",type,JsonObject.class);publish.setAccessible(true);
        bounds.addProperty("minY",64);bounds.addProperty("maxY",64);bounds.addProperty("name","Live");
        String queueKey="world\nLive\nowner\n0,64,0";flight.addProperty("queueKey",queueKey);flight.add("observation",known.deepCopy());
        var field=BotStashObserver.class.getDeclaredField("flights");field.setAccessible(true);var flights=(Map<String,JsonObject>)field.get(observer);flights.put(flight.get("eventId").getAsString(),flight);
        publish.invoke(observer,watch,known.deepCopy());field=BotStashObserver.class.getDeclaredField("pending");field.setAccessible(true);var pending=(Map<String,JsonObject>)field.get(observer);
        assert pending.isEmpty()&&flights.size()==1 : "A repeated chunk cannot queue another report while the first is awaiting acknowledgement";
        queued.addProperty("queueKey",queueKey);pending.put(queueKey,queued);publish.invoke(observer,watch,known.deepCopy());
        assert pending.get(queueKey)==queued&&pending.get(queueKey).getAsJsonObject("observation").get("status").getAsString().equals("observed") : "Repeated discovery cannot replace pending server-confirmed contents";
        var record=type.getDeclaredMethod("record",JsonObject.class);record.setAccessible(true);record.invoke(watch,liveEntry(48).getAsJsonObject("observation"));
        var chunks=type.getDeclaredMethod("chunks");chunks.setAccessible(true);assert ((Map<net.minecraft.world.level.ChunkPos,Set<BlockPos>>)chunks.invoke(watch)).get(new net.minecraft.world.level.ChunkPos(3,0)).contains(new BlockPos(48,64,0)) : "Acknowledged new coordinates join the removal/catch-up index";
        Map<String,BotStashObserver.PayloadSize> sizes=new HashMap<>();int bytes=BotStashObserver.payloadBytes(flight,sizes);var cached=sizes.get(flight.get("eventId").getAsString());
        assert BotStashObserver.payloadBytes(flight,sizes)==bytes&&sizes.get(flight.get("eventId").getAsString())==cached : "Queue budget checks reuse immutable payload sizes instead of serializing the entire queue per discovery";
        flight.addProperty("basis","a".repeat(64));assert BotStashObserver.payloadBytes(flight,sizes)==flight.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length&&sizes.get(flight.get("eventId").getAsString())!=cached : "Basis changes refresh the exact UTF-8 size without weakening queue limits";
        JsonObject replacement=flight.deepCopy();replacement.addProperty("reason","different payload");assert BotStashObserver.payloadBytes(replacement,sizes)==replacement.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length : "Restored/replaced entries cannot reuse another object's cached size";
    }
    @SuppressWarnings("unchecked")
    private static void liveBatches() throws Exception {
        var selected=new ArrayList<JsonObject>();
        for(int i=0;i<32;i++){JsonObject entry=liveEntry(i);assert BotStashObserver.fitsBatch(selected,entry,true);selected.add(entry);}
        assert !BotStashObserver.fitsBatch(selected,liveEntry(32),true)&&!BotStashObserver.fitsBatch(List.of(selected.getFirst()),liveEntry(32),false) : "Batch count and old-host fallback are bounded";
        JsonObject large=liveEntry(32);large.getAsJsonObject("observation").addProperty("status","observed");JsonObject items=large.getAsJsonObject("observation").getAsJsonObject("items");for(int i=0;i<128;i++)items.addProperty("minecraft:item_"+"x".repeat(500)+i,1);
        assert !BotStashObserver.fitsBatch(List.of(selected.getFirst()),large,true)&&BotStashObserver.fitsBatch(List.of(),large,true) : "Oversized contents fall back to an independently bounded single frame";
        var observer=new BotStashObserver(null);var field=BotStashObserver.class.getDeclaredField("flights");field.setAccessible(true);var flights=(Map<String,JsonObject>)field.get(observer);
        field=BotStashObserver.class.getDeclaredField("pending");field.setAccessible(true);var pending=(Map<String,JsonObject>)field.get(observer);
        selected.forEach(m->flights.put(m.get("eventId").getAsString(),m));JsonObject old=selected.getFirst(),newer=old.deepCopy();newer.addProperty("eventId",UUID.randomUUID().toString());pending.put(old.get("queueKey").getAsString(),newer);
        JsonObject ack=new JsonObject();ack.add("eventId",old.get("eventId"));ack.addProperty("outcome","applied");ack.addProperty("basis","a".repeat(64));
        observer.acknowledgeOwned(ack,"other host");assert flights.size()==32 : "Another host cannot clear in-flight observations";
        observer.acknowledgeOwned(ack,"test owner");assert flights.size()==31&&pending.get(old.get("queueKey").getAsString()).get("basis").getAsString().equals("a".repeat(64)) : "A partial ACK clears only its event and refreshes a newer queued observation's basis";
        observer.acknowledgeOwned(ack,"test owner");assert flights.size()==31 : "Duplicate ACKs do not clear another event";
        ack.add("eventId",selected.get(1).get("eventId"));ack.addProperty("outcome","deferred");observer.acknowledgeOwned(ack,"test owner");assert flights.size()==31 : "Deferred world races retain the in-flight update";
        flights.values().forEach(m->BotStashObserver.park(pending,m));assert pending.size()==32&&pending.get(old.get("queueKey").getAsString()).equals(newer) : "Disconnect parks every unacknowledged event without overwriting newer snapshots";
        var path=java.nio.file.Files.createTempDirectory("monocle-batch-outbox-").resolve("outbox.json");JsonObject journal=new JsonObject();JsonArray queue=new JsonArray();pending.values().forEach(queue::add);journal.add("pending",queue);BotStashObserver.journalFirst(path,journal,m->{});assert dev.monocle.coordinator.TaskFiles.read(path).getAsJsonArray("pending").size()==32 : "Batches retain the compatible flat recovery journal";
    }
    private static JsonObject liveEntry(int x){
        JsonObject entry=new JsonObject();entry.addProperty("name","Live");entry.addProperty("scope","server\nnether");entry.addProperty("basis","");entry.addProperty("eventId",UUID.randomUUID().toString());entry.addProperty("owner","test owner");entry.addProperty("queueKey","cell "+x);entry.addProperty("watchKey","watch");
        entry.add("observation",JsonParser.parseString("{x:"+x+",y:64,z:0,status:'unscanned',block:'minecraft:chest',capacity:54,items:{},shulkers:[]}").getAsJsonObject());return entry;
    }
}
