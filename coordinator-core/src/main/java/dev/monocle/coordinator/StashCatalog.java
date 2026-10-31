package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.google.gson.stream.JsonWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Shared observations, column ownership and wire validation for both hosts. Mapped stock is not a delivery guarantee. */
public final class StashCatalog {
    public static final class DefinitionConflict extends IllegalStateException {
        private DefinitionConflict(String detail){super(detail);}
    }
    public static final int MAX_OBSERVATION_BYTES = 512_000;
    public static final int MAX_CONTAINERS = 16_384, LEGACY_MAX_CONTAINERS = 4096, WATCH_PAGE_SIZE = 32;
    public static final int LIVE_BATCH_SIZE = 32, LIVE_BATCH_BYTES = 64 * 1024;
    private StashCatalog() {}
    private record CachedStash(String revision,JsonObject database,JsonObject summary) {}
    private static final Map<Path,CachedStash> summaries=new LinkedHashMap<>();
    private record CachedLocalWatch(String revision,JsonObject header,JsonObject view) {}
    private static final Map<Path,CachedLocalWatch> localWatches=new LinkedHashMap<>();
    private static CachedStash cached(Path file){
        file=file.toAbsolutePath().normalize();String revision=StashStore.revision(file);
        synchronized(summaries){
            CachedStash prior=summaries.get(file);if(prior!=null&&prior.revision.equals(revision))return prior;
        }
        JsonObject db=StashStore.read(file);CachedStash next=new CachedStash(revision,db,db.isEmpty()?new JsonObject():summary(db));
        synchronized(summaries){summaries.put(file,next);while(summaries.size()>32)summaries.remove(summaries.keySet().iterator().next());}
        return next;
    }
    public static void attachObservation(JsonObject message, JsonObject observation) {
        byte[] raw = TaskFiles.jsonBytes(observation, MAX_OBSERVATION_BYTES);
        if (raw.length <= 8_000) message.add("observation", observation.deepCopy());
        else try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream zip = new GZIPOutputStream(bytes)) { zip.write(raw); }
            message.addProperty("observationGzip", Base64.getEncoder().encodeToString(bytes.toByteArray()));
        } catch (IOException e) { throw new IllegalStateException("Could not compress stash observation", e); }
        try { TaskFiles.jsonBytes(message, CrewFrames.MAX_BYTES); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Stash observation exceeds the worker frame after compression; split the stash scan", e); }
    }
    public static JsonObject readObservation(JsonObject message) {
        if (message.has("observation") == message.has("observationGzip")) throw new IllegalArgumentException("Supply exactly one stash observation");
        if (message.has("observation")) return message.getAsJsonObject("observation");
        String encoded = TaskWire.text(message, "observationGzip");
        if (encoded.length() > CrewFrames.MAX_BYTES) throw new IllegalArgumentException("Compressed stash observation is too large");
        try (GZIPInputStream zip = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            byte[] raw = zip.readNBytes(MAX_OBSERVATION_BYTES + 1);
            if (raw.length > MAX_OBSERVATION_BYTES) throw new IllegalArgumentException("Expanded stash observation is too large");
            JsonObject observation = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            TaskFiles.jsonBytes(observation, MAX_OBSERVATION_BYTES);
            return observation;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid compressed stash observation: " + e.getMessage(), e); }
    }
    public static JsonObject bounds(JsonObject source) {
        JsonObject p = source.deepCopy();
        String name = TaskWire.text(p, "name");
        if (name.isBlank() || name.length() > 48 || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Give the stash a name of at most 48 characters");
        long volume = 1;
        for (String axis : List.of("X", "Y", "Z")) {
            int limit = axis.equals("Y") ? 2048 : 29_900_000;
            int min = integer(p, "min" + axis, -limit, limit), max = integer(p, "max" + axis, -limit, limit);
            if (max < min) throw new IllegalArgumentException("Stash minimum must not exceed maximum");
            volume *= (long) max - min + 1;
            if (volume > 1_048_576) throw new IllegalArgumentException("Limit a stash selection to 1,048,576 blocks");
        }
        return p;
    }
    public static JsonObject plan(JsonObject source) {
        JsonObject p = bounds(source);
        if (!p.has("scanMode")) p.addProperty("scanMode", "Full");
        if (!Set.of("Full", "Column Map").contains(TaskWire.text(p, "scanMode"))) throw new IllegalArgumentException("Invalid stash scan mode");
        if (!p.has("workerCount")) p.addProperty("workerCount", 1);
        if (!p.has("workerIndex")) p.addProperty("workerIndex", 0);
        if (!p.has("lazyMode")) p.addProperty("lazyMode", true);
        if (!p.get("lazyMode").isJsonPrimitive() || !p.getAsJsonPrimitive("lazyMode").isBoolean()) throw new IllegalArgumentException("Invalid stash lazyMode");
        if(p.has("returnByDeath")&&(!p.get("returnByDeath").isJsonPrimitive()||!p.getAsJsonPrimitive("returnByDeath").isBoolean()))throw new IllegalArgumentException("Expected boolean returnByDeath");
        if(p.has("assumeEmpty")&&(!p.get("assumeEmpty").isJsonPrimitive()||!p.getAsJsonPrimitive("assumeEmpty").isBoolean()))throw new IllegalArgumentException("Invalid stash assumeEmpty");
        if(p.has("fillAllocatedColumns")&&(!p.get("fillAllocatedColumns").isJsonPrimitive()||!p.getAsJsonPrimitive("fillAllocatedColumns").isBoolean()))throw new IllegalArgumentException("Expected boolean fillAllocatedColumns");
        String home=TaskWire.text(p,"homeName").strip();
        if (!home.matches("[A-Za-z0-9_-]{1,48}")) throw new IllegalArgumentException("Give the stash its required /home name using letters, numbers, underscores or dashes");
        p.addProperty("homeName",home);
        if (!p.has("homeWarmupTicks")) p.addProperty("homeWarmupTicks",300);
        if (!p.has("homeCooldownTicks")) p.addProperty("homeCooldownTicks",12_000);
        integer(p,"homeWarmupTicks",0,72_000);integer(p,"homeCooldownTicks",0,1_728_000);
        integer(p, "workerCount", 1, 16); integer(p, "workerIndex", 0, p.get("workerCount").getAsInt() - 1);
        if(p.has("targetColumn"))p.add("targetColumn",checkedColumn(p,p.getAsJsonObject("targetColumn")));
        if(p.has("columnKitTypeIds"))columnKitTypes(p);
        for(String field:List.of("scanCells","sortFootprint"))if(p.has(field)){
            if(!p.get(field).isJsonArray()||p.getAsJsonArray(field).isEmpty()||p.getAsJsonArray(field).size()>(field.equals("scanCells")?64:128))throw new IllegalArgumentException("Invalid "+field);
            Set<String> seen=new HashSet<>();for(JsonElement value:p.getAsJsonArray(field)){JsonObject c=value.getAsJsonObject();int x=integer(c,"x",-29_900_000,29_900_000),y=integer(c,"y",-2048,2048),z=integer(c,"z",-29_900_000,29_900_000);if(!contains(p,x,y,z)||!seen.add(x+","+y+","+z))throw new IllegalArgumentException("Invalid or duplicate "+field+" position");}
        }
        if(p.has("sortBatch")){UUID.fromString(TaskWire.text(p,"sortBatch"));if(!p.has("sortFootprint"))throw new IllegalArgumentException("A sorting batch needs its physical footprint");}
        return p;
    }
    static JsonObject sortRows(Path root,String crew,String scope,String name,Set<String> keys){return StashStore.readRows(file(root,crew,scope,name),keys);}
    public static JsonObject bufferAction(JsonObject source){
        JsonObject a=plan(source);UUID.fromString(TaskWire.text(a,"kitTypeId"));
        String op=TaskWire.text(a,"operation");if(!Set.of("Inspect","Store","Take","Stow").contains(op))throw new IllegalArgumentException("Invalid ender buffer operation");
        int buffered=integer(a,"buffered",0,54);integer(a,"count",1,36);
        if(!a.has("incomplete"))a.addProperty("incomplete",false);
        for(String key:List.of("incomplete","includeIncomplete"))if(a.has(key)&&(!a.get(key).isJsonPrimitive()||!a.getAsJsonPrimitive(key).isBoolean()))throw new IllegalArgumentException("Expected boolean "+key);
        if(!a.has("kitExemplar")||!a.get("kitExemplar").isJsonObject())throw new IllegalArgumentException("Ender transport needs its kit identity");
        if(op.equals("Inspect")&&buffered!=0||op.equals("Take")&&buffered==0)throw new IllegalArgumentException("Invalid expected ender cargo");
        return a;
    }
    public static Set<String> columnKitTypes(JsonObject plan){
        String kit=TaskWire.text(plan,"kitTypeId");UUID.fromString(kit);
        if(!plan.has("columnKitTypeIds"))return Set.of(kit);
        if(!plan.has("targetColumn")||!plan.get("columnKitTypeIds").isJsonArray())throw new IllegalArgumentException("Shared kit types require a locked destination column");
        JsonArray ids=plan.getAsJsonArray("columnKitTypeIds");Set<String> result=new LinkedHashSet<>();
        if(ids.size()<2||ids.size()>16)throw new IllegalArgumentException("Select 2–16 shared column kit types");
        for(JsonElement id:ids){if(!id.isJsonPrimitive()||!id.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Invalid shared column kit type");String value=id.getAsString();if(!UUID.fromString(value).toString().equals(value)||!result.add(value))throw new IllegalArgumentException("Duplicate or invalid shared column kit type");}
        if(!result.contains(kit))throw new IllegalArgumentException("The delivered kit must belong to its shared column");
        return result;
    }
    public static boolean nearby(JsonObject plan, PlayerObservation.Position position) {
        return nearby(plan,position,32);
    }
    public static boolean nearby(JsonObject plan, PlayerObservation.Position position,int radius) {
        return position!=null&&radius>=0&&radius<=256&&distanceToBounds(plan,position)<=(long)radius*radius;
    }
    public static JsonObject scanRoute(Path root,String crew,String scope,JsonObject assignment,String worker,Collection<PlayerObservation> participants,long now){
        JsonObject routed=route(root,crew,scope,assignment,worker);
        // Leave room for the TPA landing radius so arrivals still skip /home.
        PlayerObservation anchor=participants.stream().filter(p->p!=null&&p.inWorld(scope,now)&&p.position()!=null&&distanceToBounds(routed,p.position())<=24*24)
            .min(Comparator.comparingDouble((PlayerObservation p)->distanceToBounds(routed,p.position())).thenComparing(p->p.id().toString())).orElse(null);
        PlayerObservation self=participants.stream().filter(p->p!=null&&p.id().toString().equals(worker)).findFirst().orElse(null);
        if(anchor!=null&&self!=null&&self.inWorld(scope,now)&&self.position()!=null&&!nearby(routed,self.position())&& !anchor.id().equals(self.id()))
            routed.addProperty("scanAnchor",anchor.id().toString());
        return routed;
    }
    private static double distanceToBounds(JsonObject plan,PlayerObservation.Position p){
        double x=Math.max(0,Math.max(plan.get("minX").getAsInt()-p.x(),p.x()-(plan.get("maxX").getAsInt()+1)));
        double y=Math.max(0,Math.max(plan.get("minY").getAsInt()-p.y(),p.y()-(plan.get("maxY").getAsInt()+1)));
        double z=Math.max(0,Math.max(plan.get("minZ").getAsInt()-p.z(),p.z()-(plan.get("maxZ").getAsInt()+1)));
        return x*x+y*y+z*z;
    }
    public static int integer(JsonObject p, String key, int min, int max) {
        try {
            if (!p.get(key).getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
            int n = p.get(key).getAsBigDecimal().intValueExact();
            if (n < min || n > max) throw new IllegalArgumentException();
            return n;
        } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid stash " + key); }
    }
    public static boolean contains(JsonObject p, int x, int y, int z) {
        return x >= p.get("minX").getAsInt() && x <= p.get("maxX").getAsInt()
            && y >= p.get("minY").getAsInt() && y <= p.get("maxY").getAsInt()
            && z >= p.get("minZ").getAsInt() && z <= p.get("maxZ").getAsInt();
    }
    public static boolean owns(JsonObject p, int x, int y, int z) {
        long i = ((long) y - p.get("minY").getAsInt()) * (p.get("maxZ").getAsInt() - p.get("minZ").getAsInt() + 1)
            + z - p.get("minZ").getAsInt();
        i = i * (p.get("maxX").getAsInt() - p.get("minX").getAsInt() + 1) + x - p.get("minX").getAsInt();
        return contains(p,x,y,z) && i % p.get("workerCount").getAsInt() == p.get("workerIndex").getAsInt();
    }
    private static void counts(JsonObject items) {
        if (items == null || items.size() > 512) throw new IllegalArgumentException("Too many stash item types");
        for (var item : items.entrySet()) {
            if (!item.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid stash item identifier");
            integer(items,item.getKey(),1,10_000_000);
        }
    }
    public static JsonObject observation(JsonObject p, JsonObject input) {
        TaskFiles.jsonBytes(input, MAX_OBSERVATION_BYTES);
        JsonObject o = input.deepCopy();
        int x=integer(o,"x",-29_900_000,29_900_000), y=integer(o,"y",-2048,2048), z=integer(o,"z",-29_900_000,29_900_000);
        if (!owns(p,x,y,z)) throw new IllegalArgumentException("Container is outside this worker's stash assignment");
        if (!Set.of("observed","unscanned","removed").contains(TaskWire.text(o,"status"))) throw new IllegalArgumentException("Invalid container observation status");
        if(o.has("capacity"))integer(o,"capacity",1,216);
        if(o.has("parts")){
            JsonArray parts=o.getAsJsonArray("parts");if(parts.size()<1||parts.size()>2)throw new IllegalArgumentException("Invalid physical container parts");
            Set<String> unique=new HashSet<>();boolean anchor=false;
            for(JsonElement part:parts){JsonObject half=part.getAsJsonObject();int hx=integer(half,"x",x-1,x+1),hy=integer(half,"y",y,y),hz=integer(half,"z",z-1,z+1);
                if(Math.abs(hx-x)+Math.abs(hz-z)>1||!unique.add(hx+","+hy+","+hz))throw new IllegalArgumentException("Invalid physical container part");anchor|=hx==x&&hz==z;}
            if(!anchor||parts.size()==2&&!Set.of("minecraft:chest","minecraft:trapped_chest").contains(TaskWire.text(o,"block")))throw new IllegalArgumentException("Invalid double chest parts");
        }
        if(o.has("slotCount")!=o.has("occupiedSlots"))throw new IllegalArgumentException("Supply both container slot counts");
        if(o.has("slotCount")){
            int slots=integer(o,"slotCount",1,216),occupied=integer(o,"occupiedSlots",0,slots);
            if(!TaskWire.text(o,"status").equals("observed"))throw new IllegalArgumentException("Unscanned containers cannot report slot occupancy");
            if(o.has("shulkers")&&o.getAsJsonArray("shulkers").size()>occupied)throw new IllegalArgumentException("Shulkers exceed occupied slots");
        }
        if(o.has("inferred")&&(!o.get("inferred").isJsonPrimitive()||!o.getAsJsonPrimitive("inferred").isBoolean()))throw new IllegalArgumentException("Invalid inferred marker");
        counts(o.getAsJsonObject("items"));
        if (TaskWire.text(o,"reason").length()>512 || TaskWire.text(o,"block").length()>128) throw new IllegalArgumentException("Container detail is too long");
        if (!o.has("shulkers")) o.add("shulkers",new JsonArray());
        JsonArray boxes=o.getAsJsonArray("shulkers");
        if(boxes.size()>216)throw new IllegalArgumentException("Too many container shulkers");
        for(JsonElement value:boxes) {
            JsonObject b=value.getAsJsonObject();b.remove("kitTypeId");counts(b.getAsJsonObject("items"));
            if(TaskWire.text(b,"item").length()>128 || TaskWire.text(b,"name").length()>128 || TaskWire.text(b,"dominant").length()>128)throw new IllegalArgumentException("Shulker detail is too long");
            integer(b,"slot",0,o.has("slotCount")?o.get("slotCount").getAsInt()-1:215);integer(b,"quantity",1,99);
        }
        if(!TaskWire.text(o,"status").equals("observed") && (!o.getAsJsonObject("items").isEmpty() || !boxes.isEmpty()))throw new IllegalArgumentException("Unobserved containers cannot report contents");
        if(TaskWire.text(o,"status").equals("removed")&&(o.has("capacity")||o.has("parts")||TaskWire.flag(o,"inferred")))throw new IllegalArgumentException("Removed storage cannot report capacity or inferred contents");
        return o;
    }
    public static String containerKey(JsonObject o){return o.get("x")+","+o.get("y")+","+o.get("z");}
    public static String observationBasis(JsonObject o){return o==null?"":TaskFiles.hash(o.toString());}
    private static JsonObject material(JsonObject o){JsonObject v=o.deepCopy();for(String key:List.of("observedAt","reporter","eventId","reason","previousBlock","previousParts"))v.remove(key);return v;}
    /** Live reports cannot redefine storage or overwrite a newer observation from another worker. */
    public static synchronized JsonObject acceptLive(Path root,String crew,String scope,String worker,JsonObject message){
        String name=TaskWire.text(message,"name"),event=TaskWire.text(message,"eventId");UUID.fromString(event);UUID.fromString(worker);
        if(name.isBlank()||name.length()>48)throw new IllegalArgumentException("Invalid live stash name");
        JsonObject raw=readObservation(message);
        JsonObject db=StashStore.readRows(file(root,crew,scope,name),Set.of(containerKey(raw)));if(db.isEmpty()||!db.has("bounds")){JsonObject retired=TaskWire.message("stash-live-ack");retired.addProperty("eventId",event);retired.addProperty("outcome","retired");retired.addProperty("basis","");return retired;}
        JsonObject assignment=plan(db.getAsJsonObject("bounds"));assignment.addProperty("workerCount",1);assignment.addProperty("workerIndex",0);
        JsonObject o=observation(assignment,raw),records=db.getAsJsonObject("containers");String key=containerKey(o);
        JsonObject previous=records.has(key)?records.getAsJsonObject(key):null;
        String basis=TaskWire.text(message,"basis");if(!basis.isEmpty()&&!basis.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid live stash basis");
        boolean same=previous!=null&&(event.equals(TaskWire.text(previous,"eventId"))||material(previous).equals(material(o))
            ||TaskWire.text(o,"status").equals("unscanned")&&TaskWire.text(o,"reason").equals("Storage discovered; contents not yet observed")
            &&!TaskWire.text(previous,"status").equals("removed")&&TaskWire.text(previous,"block").equals(TaskWire.text(o,"block"))
            &&Objects.equals(previous.get("parts"),o.get("parts"))&&Objects.equals(previous.get("capacity"),o.get("capacity")));
        boolean matches=basis.equals(observationBasis(previous));
        String outcome=same?"unchanged":matches?"applied":"conflict";
        boolean verifiedAgain=same&&matches&&!event.equals(TaskWire.text(previous,"eventId"))&&TaskWire.text(o,"status").equals("observed")&&!TaskWire.flag(o,"inferred");
        if(matches&&(!same||verifiedAgain)){
            if(TaskWire.text(o,"status").equals("removed")&&previous!=null){o.addProperty("previousBlock",TaskWire.text(previous,"block"));if(previous.has("parts"))o.add("previousParts",previous.get("parts").deepCopy());}
            o.addProperty("reporter",worker);o.addProperty("eventId",event);db=storeObservation(root,crew,scope,assignment,o);
            previous=db.getAsJsonObject("containers").getAsJsonObject(key);
        }
        JsonObject ack=TaskWire.message("stash-live-ack");ack.addProperty("eventId",event);ack.addProperty("outcome",outcome);ack.addProperty("basis",observationBasis(previous));
        if(previous!=null)ack.add("record",watchRecord(previous));return ack;
    }
    /** Host-owned mirrors use their own current row basis; standalone local watches retain CAS conflicts. */
    public static synchronized JsonObject acceptLocalLive(Path root,JsonObject message){
        String crew=TaskWire.text(message,"crew"),scope=TaskWire.text(message,"scope"),name=TaskWire.text(message,"name");
        JsonObject local=message;
        if(!TaskWire.text(message,"owner").isEmpty()){
            String cell=containerKey(message.getAsJsonObject("observation"));Path path=file(root,crew,scope,name);
            JsonObject db=StashStore.readRows(path,Set.of(cell));
            if(db.isEmpty()){define(root,crew,scope,message.getAsJsonObject("plan"));db=StashStore.readRows(path,Set.of(cell));}
            local=message.deepCopy();local.addProperty("basis",observationBasis(db.getAsJsonObject("containers").getAsJsonObject(cell)));
        }
        return acceptLive(root,crew,scope,TaskWire.text(message,"worker"),local);
    }
    /** Validate the whole envelope before applying any entry; each durable result remains independently retryable. */
    public static JsonArray liveBatch(JsonObject message){
        TaskFiles.jsonBytes(message,LIVE_BATCH_BYTES);
        JsonArray entries=message.getAsJsonArray("entries");if(entries==null||entries.isEmpty()||entries.size()>LIVE_BATCH_SIZE)throw new IllegalArgumentException("Invalid live stash batch size");
        Set<String> events=new HashSet<>(),cells=new HashSet<>();int expanded=0;
        for(JsonElement value:entries){JsonObject entry=value.getAsJsonObject();String scope=TaskWire.text(entry,"scope"),name=TaskWire.text(entry,"name"),event=TaskWire.text(entry,"eventId"),basis=TaskWire.text(entry,"basis");
            UUID.fromString(event);if(!events.add(event)||scope.length()>384||!scope.contains("\n")||name.isBlank()||name.length()>48||!basis.isEmpty()&&!basis.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid live stash batch entry");
            JsonObject o=readObservation(entry);expanded+=TaskFiles.jsonBytes(o,LIVE_BATCH_BYTES).length;if(expanded>LIVE_BATCH_BYTES)throw new IllegalArgumentException("Expanded live stash batch is too large");
            JsonObject p=new JsonObject();for(String axis:List.of("X","Y","Z")){int limit=axis.equals("Y")?2048:29_900_000;int n=integer(o,axis.toLowerCase(Locale.ROOT),-limit,limit);p.addProperty("min"+axis,n);p.addProperty("max"+axis,n);}p.addProperty("workerCount",1);p.addProperty("workerIndex",0);observation(p,o);
            if(!cells.add(scope+"\n"+name+"\n"+containerKey(o)))throw new IllegalArgumentException("Duplicate live stash batch coordinate");
        }
        return entries;
    }
    public static synchronized JsonObject acceptLiveBatch(Path root,String crew,String worker,String currentScope,JsonObject message){
        UUID.fromString(worker);JsonArray entries=liveBatch(message),results=new JsonArray();
        for(JsonElement value:entries){JsonObject entry=value.getAsJsonObject();String scope=TaskWire.text(entry,"scope");
            if(scope.equals(currentScope))results.add(acceptLive(root,crew,scope,worker,entry));
            else {JsonObject ack=TaskWire.message("stash-live-ack");ack.addProperty("eventId",TaskWire.text(entry,"eventId"));ack.addProperty("outcome","deferred");results.add(ack);}
        }
        JsonObject ack=TaskWire.message("stash-live-batch-ack");ack.add("entries",results);TaskFiles.jsonBytes(ack,LIVE_BATCH_BYTES);return ack;
    }
    public static JsonObject watchRecord(JsonObject o){JsonObject r=new JsonObject();for(String key:List.of("x","y","z","status","block","capacity","parts","previousParts","slotCount","occupiedSlots","inferred","observedAt"))if(o.has(key))r.add(key,o.get(key).deepCopy());r.addProperty("basis",observationBasis(o));return r;}
    private static JsonObject watchPlan(JsonObject db){
        try{return plan(db.getAsJsonObject("bounds"));}
        catch(IllegalArgumentException e){return null;} // Legacy definitions without a home remain stored, but cannot authorize observations.
    }
    private static List<JsonObject> watchedContainers(JsonObject db,JsonObject plan){
        // Old scans sometimes persisted approach targets outside the selection; never authorize those rows.
        return db.getAsJsonObject("containers").entrySet().stream().map(e->e.getValue().getAsJsonObject())
            .filter(o->contains(plan,o.get("x").getAsInt(),o.get("y").getAsInt(),o.get("z").getAsInt())).toList();
    }
    /** Observer baselines need geometry and CAS hashes, not operator aggregates or kit contents. Call off the game thread. */
    public static JsonArray localWatches(Path root,boolean host){
        return localWatches(root,host,Set.of());
    }
    private static boolean localWatchIncluded(JsonObject header,boolean host,Set<String> hostOwned){
        return !header.isEmpty()&&header.has("bounds")&&(host||TaskWire.text(header,"crew").equals("Local"))
            &&!hostOwned.contains(TaskWire.text(header,"scope")+"\n"+TaskWire.text(header,"name"));
    }
    public static JsonArray localWatches(Path root,boolean host,Set<String> hostOwned){
        JsonArray result=new JsonArray();Path dir=root.resolve("stashes");if(!Files.exists(dir))return result;
        try(var paths=Files.list(dir)){for(Path path:paths.filter(p->p.getFileName().toString().endsWith(".sqlite")).sorted().limit(256).toList()){
            path=path.toAbsolutePath().normalize();String revision=StashStore.revision(path);CachedLocalWatch cached;
            synchronized(localWatches){cached=localWatches.get(path);}
            if(cached==null||!cached.revision.equals(revision))cached=new CachedLocalWatch(revision,StashStore.watchHeader(path),null);
            if(localWatchIncluded(cached.header,host,hostOwned)&&cached.view==null&&watchPlan(cached.header)!=null){
                JsonObject db=StashStore.read(path),header=new JsonObject();
                for(String key:List.of("name","scope","crew","bounds"))if(db.has(key))header.add(key,db.get(key).deepCopy());
                JsonObject view=null,plan=header.has("bounds")?watchPlan(header):null;
                if(localWatchIncluded(header,host,hostOwned)&&plan!=null){
                    view=header.deepCopy();JsonObject records=new JsonObject();
                    watchedContainers(db,plan).forEach(o->records.add(containerKey(o),watchRecord(o)));view.add("containers",records);
                }
                cached=new CachedLocalWatch(revision,header,view);
            }
            if(localWatchIncluded(cached.header,host,hostOwned)&&cached.view!=null)result.add(cached.view.deepCopy());
            synchronized(localWatches){localWatches.put(path,cached);while(localWatches.size()>32)localWatches.remove(localWatches.keySet().iterator().next());}
        }}catch(IOException e){throw new IllegalStateException("Could not read local stash watches",e);}return result;
    }
    /** Small independently bounded frames, not a growing manifest in every worker heartbeat. */
    public static List<JsonObject> watchFrames(Path root,String crew){
        return watchFrames(root,crew,MAX_CONTAINERS);
    }
    public static List<JsonObject> watchFrames(Path root,String crew,int limit){
        if(limit<1||limit>MAX_CONTAINERS)throw new IllegalArgumentException("Invalid live stash container limit");
        List<JsonObject> frames=new ArrayList<>();Path dir=root.resolve("stashes");if(!Files.exists(dir))return frames;
        try(var paths=Files.list(dir)){for(Path path:paths.filter(p->p.getFileName().toString().endsWith(".sqlite")).sorted().limit(256).toList()){
            JsonObject db=cached(path).database;if(!crew.equals(TaskWire.text(db,"crew"))||!db.has("bounds"))continue;
            if(watchPlan(db)==null)continue;ensureKitTypes(db);
            JsonObject plan=new JsonObject();for(String key:List.of("name","minX","maxX","minY","maxY","minZ","maxZ","homeName","homeWarmupTicks","homeCooldownTicks","scanMode","lazyMode"))if(db.getAsJsonObject("bounds").has(key))plan.add(key,db.getAsJsonObject("bounds").get(key).deepCopy());
            List<JsonObject> records=watchedContainers(db,plan).stream().map(StashCatalog::watchRecord).toList();
            if(records.size()>limit)continue; // Older workers must not receive a truncated or oversized baseline.
            String epoch=UUID.randomUUID().toString();int count=Math.max(1,(records.size()+WATCH_PAGE_SIZE-1)/WATCH_PAGE_SIZE);
            for(int part=0;part<count;part++){JsonObject m=TaskWire.message("stash-watch");m.addProperty("scope",TaskWire.text(db,"scope"));m.add("stash",plan.deepCopy());m.addProperty("epoch",epoch);m.addProperty("part",part);m.addProperty("parts",count);JsonArray chunk=new JsonArray();records.subList(part*WATCH_PAGE_SIZE,Math.min(records.size(),(part+1)*WATCH_PAGE_SIZE)).forEach(chunk::add);m.add("records",chunk);TaskFiles.jsonBytes(m,CrewFrames.MAX_BYTES);frames.add(m);}
        }}catch(IOException e){throw new IllegalStateException("Could not read live stash watches",e);}return frames;
    }
    private static Path file(Path root,String crew,String scope,String name) {
        return root.resolve("stashes").resolve(TaskFiles.hash(crew+"\n"+scope+"\n"+name)+".sqlite");
    }
    public static JsonObject save(Path root,String crew,String scope,JsonObject assignment,JsonObject input) {
        storeObservation(root,crew,scope,assignment,input);
        return summary(get(root,crew,scope,TaskWire.text(assignment,"name")));
    }
    /** Observation ingestion does not need the expensive operator-facing aggregate. */
    public static void record(Path root,String crew,String scope,JsonObject assignment,JsonObject input) {
        storeObservation(root,crew,scope,assignment,input);
    }
    public static synchronized void recordScan(Path root,String scope,JsonObject assignment,JsonObject input,JsonObject checkpoint){
        storeObservation(root,"Local",scope,assignment,input,"","",null,checkpoint);
    }
    public static JsonObject scanCheckpoint(Path root,String scope,JsonObject assignment,String binding){
        return StashStore.checkpoint(file(root,"Local",scope,TaskWire.text(assignment,"name")),binding);
    }
    private static synchronized JsonObject storeObservation(Path root,String crew,String scope,JsonObject assignment,JsonObject input) {
        return storeObservation(root,crew,scope,assignment,input,"","");
    }
    private static JsonObject storeObservation(Path root,String crew,String scope,JsonObject assignment,JsonObject input,String worker,String exportId) {
        return storeObservation(root,crew,scope,assignment,input,worker,exportId,null);
    }
    private static JsonObject storeObservation(Path root,String crew,String scope,JsonObject assignment,JsonObject input,String worker,String exportId,JsonObject deposit) {
        return storeObservation(root,crew,scope,assignment,input,worker,exportId,deposit,null);
    }
    private static JsonObject storeObservation(Path root,String crew,String scope,JsonObject assignment,JsonObject input,String worker,String exportId,JsonObject deposit,JsonObject checkpoint) {
        JsonObject p=plan(assignment),validated=observation(p,input);
        if(scope.isBlank()||scope.length()>1200)throw new IllegalArgumentException("Invalid stash world scope");
        Path path=file(root,crew,scope,TaskWire.text(p,"name"));
        Set<String> keys=new HashSet<>();keys.add(containerKey(validated));
        if(validated.has("parts"))for(JsonElement part:validated.getAsJsonArray("parts"))keys.add(containerKey(part.getAsJsonObject()));
        return StashStore.update(path,keys,(db,totalCount)->{
        JsonObject o=validated.deepCopy();
        if(db.isEmpty()) {db.addProperty("version",1);db.addProperty("name",TaskWire.text(p,"name"));db.addProperty("crew",crew);db.addProperty("scope",scope);db.add("containers",new JsonObject());}
        ensureKitTypes(db);
        Set<String> changed=new HashSet<>();JsonObject records=db.getAsJsonObject("containers");String key=o.get("x")+","+o.get("y")+","+o.get("z");changed.add(key);
        if(!records.has(key)&&totalCount>=MAX_CONTAINERS){String oldest=records.entrySet().stream().filter(e->TaskWire.text(e.getValue().getAsJsonObject(),"status").equals("removed")).min(Comparator.comparingLong(e->observedAt(e.getValue().getAsJsonObject()))).map(Map.Entry::getKey).orElseThrow(()->new IllegalArgumentException("Limit one stash to "+MAX_CONTAINERS+" containers"));records.remove(oldest);changed.add(oldest);}
        // A running job's captured definition must not undo a later expansion or route edit.
        if(!db.has("bounds"))setBounds(db,p);
        if(!contains(db.getAsJsonObject("bounds"),o.get("x").getAsInt(),o.get("y").getAsInt(),o.get("z").getAsInt()))throw new IllegalArgumentException("Container is outside the current stash bounds");
        if(db.has("fullColumns")&&(TaskWire.text(o,"status").equals("removed")||storageChest(o)&&TaskWire.text(o,"status").equals("observed")&&!TaskWire.flag(o,"inferred")
            &&o.has("slotCount")&&o.get("occupiedSlots").getAsInt()<o.get("slotCount").getAsInt())){
            JsonObject position=o;db.getAsJsonObject("fullColumns").entrySet().removeIf(e->columnContains(e.getValue().getAsJsonObject(),position.get("x").getAsInt(),position.get("y").getAsInt(),position.get("z").getAsInt()));
        }
        if(o.has("parts"))for(JsonElement part:o.getAsJsonArray("parts")){String alias=containerKey(part.getAsJsonObject());if(!alias.equals(key)&&records.has(alias)&&!TaskWire.text(records.getAsJsonObject(alias),"status").equals("removed")){
            JsonObject retired=part.getAsJsonObject().deepCopy();retired.addProperty("status","removed");retired.addProperty("block",TaskWire.text(o,"block"));retired.addProperty("reason","Merged into physical container "+key);retired.addProperty("observedAt",System.currentTimeMillis());retired.add("items",new JsonObject());retired.add("shulkers",new JsonArray());records.add(alias,retired);changed.add(alias);
        }}
        JsonObject prior=records.has(key)?records.getAsJsonObject(key):null;
        if(legacyDiscovery(prior,o)){
            // Learning geometry is not a new contents read; keep the historical snapshot and its freshness.
            JsonObject enriched=prior.deepCopy();for(String field:List.of("capacity","parts","reporter","eventId"))if(o.has(field))enriched.add(field,o.get(field).deepCopy());o=enriched;
        }else o.addProperty("observedAt",System.currentTimeMillis());
        if(prior==null||TaskWire.text(prior,"status").equals("removed")!=TaskWire.text(o,"status").equals("removed")||!Objects.equals(prior.get("parts"),o.get("parts"))){
            JsonArray history=db.has("containerEvents")?db.getAsJsonArray("containerEvents"):new JsonArray();JsonObject event=new JsonObject();for(String field:List.of("x","y","z","status","block","capacity","parts","reporter","eventId"))if(o.has(field))event.add(field,o.get(field).deepCopy());event.addProperty("observedAt",System.currentTimeMillis());history.add(event);while(history.size()>256)history.remove(0);db.add("containerEvents",history);
        }
        records.add(key,o);KitTypes.learn(db,o,deposit);StashStock.observed(db,p,o);db.addProperty("updatedAt",System.currentTimeMillis());
        if(!exportId.isEmpty())receipt(db,worker,exportId);
        return changed;
        },checkpoint);
    }
    private static boolean legacyDiscovery(JsonObject prior,JsonObject o){
        if(prior==null||prior.has("parts")||!TaskWire.text(prior,"status").equals("observed")||!TaskWire.text(o,"status").equals("unscanned")
            ||!TaskWire.text(o,"reason").equals("Storage discovered; contents not yet observed")||!TaskWire.text(prior,"block").equals(TaskWire.text(o,"block"))||!o.has("capacity")||!o.has("parts"))return false;
        for(String field:List.of("slotCount","capacity"))if(prior.has(field)&&!prior.get(field).equals(o.get("capacity")))return false;
        for(JsonElement box:prior.getAsJsonArray("shulkers"))if(box.getAsJsonObject().get("slot").getAsInt()>=o.get("capacity").getAsInt())return false;
        return true;
    }
    public static synchronized JsonObject define(Path root,String crew,String scope,JsonObject assignment) {
        JsonObject p=plan(assignment);Path path=file(root,crew,scope,TaskWire.text(p,"name"));JsonObject db=StashStore.read(path);
        if(db.isEmpty()){db.addProperty("version",1);db.addProperty("name",TaskWire.text(p,"name"));db.addProperty("crew",crew);db.addProperty("scope",scope);db.add("containers",new JsonObject());}
        validateExpansion(db,p);setBounds(db,p);db.addProperty("updatedAt",System.currentTimeMillis());StashStore.write(path,db);return summary(db);
    }
    private static void validateExpansion(JsonObject db,JsonObject p){
        if(!db.has("bounds")||db.getAsJsonObject("containers").isEmpty())return;
        for(String axis:List.of("X","Y","Z"))if(p.get("min"+axis).getAsInt()>db.getAsJsonObject("bounds").get("min"+axis).getAsInt()||p.get("max"+axis).getAsInt()<db.getAsJsonObject("bounds").get("max"+axis).getAsInt())
            throw new DefinitionConflict("Observed stash bounds can expand but not shrink; create a new stash to move or reduce its selection");
    }
    /** Stock observations do not invalidate an operator's definition edit. */
    public static String definitionRevision(JsonObject db){
        JsonObject definition=new JsonObject();if(db.has("bounds"))definition.add("bounds",definitionFields(db.getAsJsonObject("bounds"),true));
        JsonObject homes=new JsonObject();if(db.has("homes"))db.getAsJsonObject("homes").entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->homes.add(e.getKey(),e.getValue().deepCopy()));
        definition.add("homes",homes);return TaskFiles.hash(definition.toString());
    }
    private static JsonObject definitionFields(JsonObject p,boolean home){
        JsonObject result=new JsonObject();for(String key:List.of("name","minX","maxX","minY","maxY","minZ","maxZ","scanMode","lazyMode","assumeEmpty"))if(p.has(key))result.add(key,p.get(key).deepCopy());
        if(home)for(String key:List.of("homeName","homeWarmupTicks","homeCooldownTicks"))if(p.has(key))result.add(key,p.get(key).deepCopy());return result;
    }
    /** A worker may edit its route, never replace another worker's or the host's default route. */
    public static synchronized JsonObject importDefinition(Path root,String crew,String scope,JsonObject assignment,String worker,String expected){
        UUID.fromString(worker);JsonObject p=plan(assignment),db=get(root,crew,scope,TaskWire.text(p,"name"));
        if(db.isEmpty()){
            if(!expected.isEmpty())throw new DefinitionConflict("Stash was removed; refresh before exporting its definition");
            return define(root,crew,scope,p,worker);
        }
        JsonObject route=new JsonObject();route.addProperty("name",TaskWire.text(p,"homeName"));route.add("warmupTicks",p.get("homeWarmupTicks"));route.add("cooldownTicks",p.get("homeCooldownTicks"));
        JsonObject homes=db.has("homes")?db.getAsJsonObject("homes"):new JsonObject();
        boolean sameBounds=definitionFields(db.getAsJsonObject("bounds"),false).equals(definitionFields(p,false));
        boolean sameRoute=homes.has(worker)&&homes.get(worker).equals(route);
        if(sameBounds&&sameRoute)return summary(db);
        if(!(sameBounds&&!homes.has(worker))&&!definitionRevision(db).equals(expected))throw new DefinitionConflict("Stash definition changed; reload it before exporting edits");
        validateExpansion(db,p);
        for(String key:List.of("homeName","homeWarmupTicks","homeCooldownTicks"))p.add(key,db.getAsJsonObject("bounds").get(key).deepCopy());
        setBounds(db,p);homes.add(worker,route);db.add("homes",homes);db.addProperty("updatedAt",System.currentTimeMillis());StashStore.write(file(root,crew,scope,TaskWire.text(p,"name")),db);return summary(db);
    }
    private static void receipt(JsonObject db,String worker,String exportId){
        JsonObject receipts=db.has("exportReceipts")?db.getAsJsonObject("exportReceipts"):new JsonObject();receipts.addProperty(worker,exportId);db.add("exportReceipts",receipts);
    }
    /** One outstanding export per worker; its receipt shares the observation's atomic checkpoint. */
    public static synchronized JsonObject acceptExport(Path root,String crew,String worker,JsonObject message){
        UUID.fromString(worker);String id=TaskWire.text(message,"exportId");UUID.fromString(id);String scope=TaskWire.text(message,"scope");
        if(scope.length()>384||!scope.contains("\n"))throw new IllegalArgumentException("Invalid stash scope");
        JsonObject p=plan(message.getAsJsonObject("stash")),db=StashStore.readRows(file(root,crew,scope,TaskWire.text(p,"name")),Set.of());
        JsonObject ack=TaskWire.message("stash-export-ack");ack.addProperty("exportId",id);
        if(db.has("exportReceipts")&&id.equals(TaskWire.text(db.getAsJsonObject("exportReceipts"),worker))){ack.addProperty("outcome","accepted");return ack;}
        try{
            if(TaskWire.text(message,"type").equals("task-stash-definition")){
                importDefinition(root,crew,scope,p,worker,TaskWire.text(message,"definitionRevision"));db=get(root,crew,scope,TaskWire.text(p,"name"));receipt(db,worker,id);StashStore.write(file(root,crew,scope,TaskWire.text(p,"name")),db);
            }else if(TaskWire.text(message,"type").equals("task-stash-import")){
                JsonObject observation=readObservation(message);observation(p,observation);
                if(db.isEmpty()){importDefinition(root,crew,scope,p,worker,TaskWire.text(message,"definitionRevision"));}
                else if(!contains(db.getAsJsonObject("bounds"),observation.get("x").getAsInt(),observation.get("y").getAsInt(),observation.get("z").getAsInt()))throw new DefinitionConflict("Stash bounds changed; queued container is outside the current selection");
                db=storeObservation(root,crew,scope,p,observation,worker,id);
            }else throw new IllegalArgumentException("Unsupported stash export");
            ack.addProperty("outcome","accepted");ack.addProperty("definitionRevision",definitionRevision(db));
        }catch(DefinitionConflict e){
            ack.addProperty("outcome","conflict");ack.addProperty("detail",e.getMessage());
        }
        return ack;
    }
    private static void setBounds(JsonObject db,JsonObject bounds){
        if(db.has("bounds"))for(String axis:List.of("X","Y","Z"))for(String edge:List.of("min","max"))
            if(!Objects.equals(db.getAsJsonObject("bounds").get(edge+axis),bounds.get(edge+axis)))db.remove("fullColumns");
        db.add("bounds",bounds);
    }
    public static synchronized JsonObject revise(Path root,String crew,String scope,String name,String expected,JsonObject changes) {
        JsonObject db=get(root,crew,scope,name);
        if(db.isEmpty())throw new IllegalArgumentException("Unknown stash");
        if(changes.isEmpty())throw new IllegalArgumentException("Specify a stash setting to change");
        if(!TaskWire.text(summary(db),"revision").equals(expected))throw new IllegalStateException("Stash revision changed; refresh before editing");
        JsonObject bounds=db.getAsJsonObject("bounds").deepCopy();
        for(var change:changes.entrySet()) {
            if(!Set.of("minX","maxX","minY","maxY","minZ","maxZ","homeName","homeWarmupTicks","homeCooldownTicks","lazyMode","scanMode","assumeEmpty").contains(change.getKey()))
                throw new IllegalArgumentException("Unsupported stash setting: "+change.getKey());
            bounds.add(change.getKey(),change.getValue().deepCopy());
        }
        JsonObject validated=plan(bounds);
        validateExpansion(db,validated);
        if(db.has("homes") && !db.getAsJsonObject("homes").isEmpty()
            && List.of("homeName","homeWarmupTicks","homeCooldownTicks").stream().anyMatch(changes::has))
            throw new IllegalStateException("This stash has worker-specific /home settings; update them in the worker before changing the default");
        return define(root,crew,scope,validated);
    }
    public static JsonObject summary(JsonObject db) {
        ensureKitTypes(db);
        JsonObject s=new JsonObject(),totals=new JsonObject(),estimatedTotals=new JsonObject(),columns=new JsonObject(),kitCounts=new JsonObject();int observed=0,unscanned=0,inferred=0;
        for(String key:List.of("version","name","crew","scope","bounds","homes","updatedAt","kitReserves","columnFill"))if(db.has(key))s.add(key,db.get(key).deepCopy());
        int removed=0,assumedEmpty=0;long assumedSlots=0;
        JsonObject kitTypes=db.has("kitTypes")?db.getAsJsonObject("kitTypes"):new JsonObject();
        int bottom=columnBottom(db);
        for(var value:db.getAsJsonObject("containers").entrySet()) {
            JsonObject o=value.getValue().getAsJsonObject();
            if(TaskWire.text(o,"status").equals("removed")){removed++;continue;}
            if(!TaskWire.text(o,"status").equals("observed")){unscanned++;if(assumedEmptyCandidate(db,o)){assumedEmpty++;assumedSlots+=o.get("capacity").getAsInt();}continue;}
            observed++;
            if(o.has("inferred")&&o.get("inferred").getAsBoolean())inferred++;
            JsonObject destination=TaskWire.flag(o,"inferred")?estimatedTotals:totals;
            o.getAsJsonObject("items").entrySet().forEach(i->destination.addProperty(i.getKey(),(destination.has(i.getKey())?destination.get(i.getKey()).getAsLong():0)+i.getValue().getAsLong()));
            if(storageChest(o)&&o.get("y").getAsInt()==bottom&&!TaskWire.flag(o,"inferred")){
                JsonObject column=new JsonObject();column.addProperty("x",o.get("x").getAsInt());column.addProperty("z",o.get("z").getAsInt());
                column.addProperty("assumption","Upper containers in this column are assumed to have the same contents; quantity is unknown until observed");
                column.add("items",o.get("items").deepCopy());columns.add(o.get("x")+","+o.get("z"),column);
                String occupant=columnOccupant(o);column.addProperty("kitTypeId",occupant.equals("mixed")?"":occupant);column.addProperty("available",occupant.isEmpty());
                if(db.has("columnReservations")&&db.getAsJsonObject("columnReservations").has(o.get("x")+","+o.get("z"))){column.add("reservation",db.getAsJsonObject("columnReservations").get(o.get("x")+","+o.get("z")).deepCopy());column.addProperty("available",false);}
            }
            if(o.has("inferred")&&o.get("inferred").getAsBoolean())continue;
            for(JsonElement valueBox:o.getAsJsonArray("shulkers")){
                JsonObject box=valueBox.getAsJsonObject();String id=kitTypeId(box);
                if(id.isEmpty()||!kitTypes.has(id))continue;
                JsonObject type=kitTypes.getAsJsonObject(id),count=kitCounts.has(id)?kitCounts.getAsJsonObject(id):new JsonObject();
                count.addProperty("complete",(count.has("complete")?count.get("complete").getAsInt():0)+(kitIncomplete(type.getAsJsonObject("items"),box.getAsJsonObject("items"))?0:box.get("quantity").getAsInt()));
                count.addProperty("incomplete",(count.has("incomplete")?count.get("incomplete").getAsInt():0)+(kitIncomplete(type.getAsJsonObject("items"),box.getAsJsonObject("items"))?box.get("quantity").getAsInt():0));
                kitCounts.add(id,count);
            }
        }
        s.remove("containers");s.add("items",totals);s.add("estimatedItems",estimatedTotals);s.add("columns",columns);s.addProperty("mappedColumns",columns.size());s.add("kitTypes",kitTypes.deepCopy());if(db.has("kitAliases"))s.add("kitAliases",db.get("kitAliases").deepCopy());s.add("kitCounts",kitCounts);s.addProperty("observed",observed-inferred);s.addProperty("inferred",inferred);s.addProperty("unscanned",unscanned);
        if(db.has("transferStock")){s.add("transferKitCounts",StashStock.estimates(db));s.add("transferItems",db.getAsJsonObject("transferStock").get("items").deepCopy());s.addProperty("transferStockUncertain",TaskWire.flag(db.getAsJsonObject("transferStock"),"uncertain"));}
        s.addProperty("assumedEmptyContainers",assumedEmpty);s.addProperty("assumedEmptySlots",assumedSlots);
        s.addProperty("removed",removed);s.add("storage",storage(db));s.addProperty("revision",revisionHash(db));s.addProperty("definitionRevision",definitionRevision(db));return s;
    }
    static String revisionHash(JsonElement value){
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(JsonWriter writer=new JsonWriter(new OutputStreamWriter(new DigestOutputStream(OutputStream.nullOutputStream(),digest),StandardCharsets.UTF_8))){canonical(writer,value);}
            return HexFormat.of().formatHex(digest.digest());
        }catch(IOException|NoSuchAlgorithmException e){throw new IllegalStateException("Could not hash stash revision",e);}
    }
    private static void canonical(JsonWriter writer,JsonElement value)throws IOException{
        if(value.isJsonObject()){
            writer.beginObject();for(String key:new TreeSet<>(value.getAsJsonObject().keySet())){writer.name(key);canonical(writer,value.getAsJsonObject().get(key));}writer.endObject();
        }else if(value.isJsonArray()){
            writer.beginArray();for(JsonElement item:value.getAsJsonArray())canonical(writer,item);writer.endArray();
        }else writer.jsonValue(value.toString());
    }
    /** Physical storage slots, not nested kit contents. Hoppers are transport, not stash capacity. */
    private static JsonObject storage(JsonObject db){
        int containers=0,slots=0,occupied=0,unknown=0,estimated=0;
        for(var value:db.getAsJsonObject("containers").entrySet()){
            JsonObject o=value.getValue().getAsJsonObject();String block=TaskWire.text(o,"block");
            if(TaskWire.text(o,"status").equals("removed"))continue;
            if(!Set.of("minecraft:chest","minecraft:trapped_chest","minecraft:barrel").contains(block))continue;
            containers++;slots+=o.has("slotCount")?o.get("slotCount").getAsInt():o.has("capacity")?o.get("capacity").getAsInt():block.equals("minecraft:barrel")?27:54;
            if(!o.has("slotCount")&&!o.has("capacity"))estimated++;
            if(!TaskWire.text(o,"status").equals("observed")||TaskWire.flag(o,"inferred")){unknown++;continue;}
            if(o.has("occupiedSlots")){occupied+=o.get("occupiedSlots").getAsInt();continue;}
            // Legacy scans lack stack boundaries; double chests are an explicit estimate until rescanned.
            JsonArray boxes=o.getAsJsonArray("shulkers");Set<Integer> boxSlots=new HashSet<>();Map<String,Long> outer=new HashMap<>();
            o.getAsJsonObject("items").entrySet().forEach(i->outer.put(i.getKey(),i.getValue().getAsLong()));
            for(JsonElement element:boxes){JsonObject box=element.getAsJsonObject();boxSlots.add(box.get("slot").getAsInt());long count=box.get("quantity").getAsLong();
                outer.merge(TaskWire.text(box,"item"),-count,Long::sum);
                box.getAsJsonObject("items").entrySet().forEach(i->outer.merge(i.getKey(),-count*i.getValue().getAsLong(),Long::sum));
            }
            occupied+=boxSlots.size();if(outer.values().stream().anyMatch(n->n>0))unknown++;
        }
        JsonObject result=new JsonObject();result.addProperty("containers",containers);result.addProperty("slots",slots);result.addProperty("occupiedSlots",occupied);result.addProperty("unknownContainers",unknown);result.addProperty("estimatedContainers",estimated);return result;
    }
    public static String catalogRevision(Path root){
        Path dir=root.resolve("stashes");if(!Files.exists(dir))return "";
        StringBuilder revision=new StringBuilder();
        try(var paths=Files.list(dir)){for(Path file:paths.filter(p->p.getFileName().toString().endsWith(".sqlite")).sorted().limit(256).toList())revision.append(file.getFileName()).append(':').append(StashStore.revision(file)).append('\n');}
        catch(IOException e){throw new IllegalStateException("Could not read stash revisions",e);}return revision.toString();
    }
    public static JsonArray list(Path root) {
        JsonArray result=new JsonArray();Path dir=root.resolve("stashes");if(!Files.exists(dir))return result;
        List<CachedStash> cached=new ArrayList<>();List<JsonObject> databases=new ArrayList<>();
        try(var paths=Files.list(dir)) {paths.filter(p->p.getFileName().toString().endsWith(".sqlite")).sorted().limit(256).forEach(p->{CachedStash entry=cached(p);if(!entry.database.isEmpty()){cached.add(entry);databases.add(entry.database);}});}
        catch(IOException e){throw new IllegalStateException("Could not read stash catalog",e);}
        Map<String,JsonObject> latest=new HashMap<>();
        for(JsonObject db:databases)for(var cell:db.getAsJsonObject("containers").entrySet()){
            String key=TaskWire.text(db,"scope")+"\n"+cell.getKey();JsonObject observation=cell.getValue().getAsJsonObject(),previous=latest.get(key);
            // An estimate must never replace a real observation or a confirmed inventory invalidation.
            boolean inferred=TaskWire.flag(observation,"inferred");
            if(previous==null || TaskWire.flag(previous,"inferred")&&!inferred
                || TaskWire.flag(previous,"inferred")==inferred && observedAt(observation)>observedAt(previous))latest.put(key,observation);
        }
        for(CachedStash entry:cached){JsonObject db=entry.database;
            JsonObject view=entry.summary.deepCopy(),unique=new JsonObject(),records=new JsonObject();
            db.entrySet().forEach(e->unique.add(e.getKey(),e.getValue()));
            for(var cell:db.getAsJsonObject("containers").entrySet())
                if(latest.get(TaskWire.text(db,"scope")+"\n"+cell.getKey())==cell.getValue())records.add(cell.getKey(),cell.getValue());
            unique.add("containers",records);JsonObject counts=records.size()==db.getAsJsonObject("containers").size()?view:summary(unique);
            view.add("deduplicatedItems",counts.get("items"));view.add("deduplicatedKitCounts",counts.get("kitCounts"));
            view.addProperty("duplicateContainers",db.getAsJsonObject("containers").size()-records.size());result.add(view);
        }
        return result;
    }
    private static long observedAt(JsonObject observation){return observation.has("observedAt")?observation.get("observedAt").getAsLong():0;}
    /** Keep the authenticated 16 KiB crew frame for catalog sync small; detailed catalog stays on the host. */
    public static JsonObject wireSummary(JsonObject summary){
        JsonObject compact=summary.deepCopy();compact.remove("kitReserves");compact.remove("columns");compact.remove("columnFill");compact.remove("deduplicatedItems");compact.remove("deduplicatedKitCounts");compact.remove("kitAliases");compact.remove("estimatedItems");compact.remove("transferKitCounts");compact.remove("transferItems");compact.remove("transferStockUncertain");
        if(compact.has("items")){
            JsonObject source=compact.getAsJsonObject("items"),items=new JsonObject();compact.addProperty("itemTypes",source.size());
            source.entrySet().stream().sorted((a,b)->Long.compare(b.getValue().getAsLong(),a.getValue().getAsLong())).limit(24).forEach(e->items.add(e.getKey(),e.getValue().deepCopy()));compact.add("items",items);
        }
        JsonObject types=new JsonObject(),counts=new JsonObject();
        if(compact.has("kitTypes"))for(var entry:compact.getAsJsonObject("kitTypes").entrySet()){
            if(types.size()>=8)break;JsonObject source=entry.getValue().getAsJsonObject(),type=new JsonObject();
            for(String key:List.of("id","item","name"))if(source.has(key))type.add(key,source.get(key).deepCopy());types.add(entry.getKey(),type);
            if(compact.has("kitCounts")&&compact.getAsJsonObject("kitCounts").has(entry.getKey()))counts.add(entry.getKey(),compact.getAsJsonObject("kitCounts").get(entry.getKey()).deepCopy());
        }
        compact.add("kitTypes",types);compact.add("kitCounts",counts);return compact;
    }
    public static synchronized JsonObject define(Path root,String crew,String scope,JsonObject assignment,String worker) {
        JsonObject summary=define(root,crew,scope,assignment),db=get(root,crew,scope,TaskWire.text(assignment,"name"));
        JsonObject homes=db.has("homes")?db.getAsJsonObject("homes"):new JsonObject(),route=new JsonObject();
        JsonObject plan=plan(assignment);route.addProperty("name",TaskWire.text(plan,"homeName"));route.add("warmupTicks",plan.get("homeWarmupTicks"));route.add("cooldownTicks",plan.get("homeCooldownTicks"));homes.add(UUID.fromString(worker).toString(),route);db.add("homes",homes);db.addProperty("updatedAt",System.currentTimeMillis());StashStore.write(file(root,crew,scope,TaskWire.text(plan,"name")),db);return summary(db);
    }
    public static JsonObject route(Path root,String crew,String scope,JsonObject assignment,String worker) {
        JsonObject result=plan(assignment),db=get(root,crew,scope,TaskWire.text(result,"name"));
        if(db.has("homes")&&db.getAsJsonObject("homes").has(worker)){
            JsonObject home=db.getAsJsonObject("homes").getAsJsonObject(worker);result.addProperty("homeName",TaskWire.text(home,"name"));result.add("homeWarmupTicks",home.get("warmupTicks").deepCopy());result.add("homeCooldownTicks",home.get("cooldownTicks").deepCopy());
        }
        result.addProperty("dimension",scope.substring(scope.indexOf('\n')+1));
        return plan(result);
    }
    public static JsonObject refillAction(Path root,String crew,String scope,JsonObject request,String worker){
        JsonObject routed=route(root,crew,scope,request,worker),needs=request.getAsJsonObject("needs");String primary=TaskWire.text(request,"primary");
        JsonArray picks=refill(get(root,crew,scope,TaskWire.text(routed,"name")),needs,primary,request.has("enderSlots")?integer(request,"enderSlots",1,54):54);
        if(picks.isEmpty())throw new IllegalStateException("The mapped stash has no observed matching shulker for this shortage");routed.add("picks",picks);routed.addProperty("type","StashResupply");return routed;
    }
    public static void cacheRemote(Path root,JsonArray summaries){
        if(summaries==null||summaries.size()>64)throw new IllegalArgumentException("Invalid remote stash catalog");
        JsonArray checked=new JsonArray();for(JsonElement value:summaries){JsonObject s=value.getAsJsonObject();if(TaskWire.text(s,"name").length()>48||TaskWire.text(s,"scope").length()>384||!s.has("bounds")||!s.has("items"))throw new IllegalArgumentException("Invalid remote stash summary");checked.add(s.deepCopy());}
        JsonObject cache=new JsonObject();cache.addProperty("version",1);cache.addProperty("updatedAt",System.currentTimeMillis());cache.add("stashes",checked);TaskFiles.write(root.resolve("stash-host-catalog-sqlite-v1.json"),cache);
    }
    public static JsonArray remote(Path root){JsonObject cache=TaskFiles.read(root.resolve("stash-host-catalog-sqlite-v1.json"));return cache.has("stashes")?cache.getAsJsonArray("stashes"):new JsonArray();}
    public static JsonObject get(Path root,String crew,String scope,String name) {JsonObject db=StashStore.read(file(root,crew,scope,name));ensureKitTypes(db);return db;}
    public static String kitTypeId(JsonObject box){
        return KitTypes.id(box);
    }
    public static boolean kitIncomplete(JsonObject exemplar,JsonObject candidate){
        for(var entry:exemplar.entrySet())if(!candidate.has(entry.getKey())||candidate.get(entry.getKey()).getAsLong()<entry.getValue().getAsLong())return true;
        return false;
    }
    private static void ensureKitTypes(JsonObject db){
        KitTypes.ensure(db);
    }
    /** Select observed boxes first, then search unobserved chests in matching mapped columns at execution time. */
    public static JsonArray kitPicks(JsonObject db,String typeId,int count,boolean incomplete){
        return kitPicks(db,typeId,count,incomplete,false,false);
    }
    private static JsonArray kitPicks(JsonObject db,String typeId,int count,boolean incomplete,boolean includeIncomplete,boolean all){
        return kitPicks(db,typeId,count,incomplete,includeIncomplete,all,false);
    }
    private static JsonArray kitPicks(JsonObject db,String typeId,int count,boolean incomplete,boolean includeIncomplete,boolean all,boolean observedOnly){
        typeId=KitTypes.canonical(db,typeId);
        UUID.fromString(typeId);
        if(count<1||count>54||db==null||!db.has("kitTypes")||!db.getAsJsonObject("kitTypes").has(typeId))throw new IllegalArgumentException("Unknown kit type or invalid count");
        JsonObject exemplar=db.getAsJsonObject("kitTypes").getAsJsonObject(typeId).getAsJsonObject("items");
        if(all&&(!TaskWire.text(db.getAsJsonObject("bounds"),"scanMode").equals("Full")||TaskWire.flag(db.getAsJsonObject("bounds"),"lazyMode")))throw new IllegalStateException("Transfer all needs a full, non-lazy source scan");
        JsonArray picks=new JsonArray();Set<String> columns=new LinkedHashSet<>();
        for(var entry:db.getAsJsonObject("containers").entrySet()){
            JsonObject observed=entry.getValue().getAsJsonObject();
            if(TaskWire.text(observed,"status").equals("removed"))continue;
            if(!TaskWire.text(observed,"status").equals("observed")||TaskWire.flag(observed,"inferred")){
                // Our own withdrawals invalidate quantities, not the known container's location.
                if(all&&TaskWire.text(observed,"reason").equals("Contents changed by a confirmed stash withdrawal; rescan required")&&!TaskWire.flag(observed,"inferred")){
                    if(observed.has("withdrawnKitTypes")&&!observed.getAsJsonArray("withdrawnKitTypes").asList().contains(new JsonPrimitive(typeId)))continue;
                    if(picks.size()==2048)throw new IllegalStateException("Transfer all supports at most 2048 matching source containers; split this stash selection");
                    picks.add(kitPick(observed,typeId,incomplete,TaskWire.text(db.getAsJsonObject("kitTypes").getAsJsonObject(typeId),"item")));continue;
                }
                if(all&&!observedOnly)throw new IllegalStateException("Transfer all needs a full, non-lazy source scan without unknown containers");
                continue;
            }
            for(JsonElement element:observed.getAsJsonArray("shulkers")){
                JsonObject box=element.getAsJsonObject();if(!typeId.equals(kitTypeId(box)))continue;
                if(db.has("bounds")&&observed.get("y").getAsInt()==db.getAsJsonObject("bounds").get("minY").getAsInt())columns.add(observed.get("x")+","+observed.get("z"));
                if(!includeIncomplete&&kitIncomplete(exemplar,box.getAsJsonObject("items"))!=incomplete)continue;
                JsonObject pick=kitPick(observed,typeId,incomplete,TaskWire.text(box,"item"));
                if(!picks.isEmpty()&&sameChest(picks.get(picks.size()-1).getAsJsonObject(),pick))continue;
                if(picks.size()==(all?2048:54)){if(all)throw new IllegalStateException("Transfer all supports at most 2048 matching source chests; split this stash selection");continue;}
                picks.add(pick);
            }
        }
        if(!all&&db.has("bounds"))for(String column:columns){String[] xz=column.split(",");int x=Integer.parseInt(xz[0]),z=Integer.parseInt(xz[1]);JsonObject bounds=db.getAsJsonObject("bounds");
            for(int y=bounds.get("minY").getAsInt()+1;y<=bounds.get("maxY").getAsInt()&&picks.size()<54;y++){
                String key=x+","+y+","+z;JsonObject known=db.getAsJsonObject("containers").has(key)?db.getAsJsonObject("containers").getAsJsonObject(key):null;
                if(known!=null&&(TaskWire.text(known,"status").equals("removed")||TaskWire.text(known,"status").equals("observed")&&!known.has("inferred")))continue;
                JsonObject candidate=new JsonObject();candidate.addProperty("x",x);candidate.addProperty("y",y);candidate.addProperty("z",z);candidate.addProperty("slot",0);candidate.addProperty("dynamic",true);candidate.addProperty("kitTypeId",typeId);candidate.addProperty("resource",TaskWire.text(db.getAsJsonObject("kitTypes").getAsJsonObject(typeId),"item"));candidate.addProperty("incomplete",incomplete);picks.add(candidate);
            }
        }
        if(picks.isEmpty())throw new IllegalStateException("No observed or mapped source chest for this kit; scan or refresh the column before dispatch");
        return picks;
    }
    private static int sourceScanGaps(JsonObject db){
        return (int)db.getAsJsonObject("containers").asMap().values().stream().map(JsonElement::getAsJsonObject)
            .filter(o->!TaskWire.text(o,"status").equals("removed")&&(!TaskWire.text(o,"status").equals("observed")||TaskWire.flag(o,"inferred")))
            .filter(o->!TaskWire.text(o,"reason").equals("Contents changed by a confirmed stash withdrawal; rescan required")).count();
    }
    private static JsonObject kitPick(JsonObject observed,String typeId,boolean incomplete,String item){
        JsonObject pick=new JsonObject();pick.addProperty("slot",0);pick.addProperty("dynamic",true);
        for(String axis:List.of("x","y","z"))pick.add(axis,observed.get(axis).deepCopy());
        pick.addProperty("kitTypeId",typeId);pick.addProperty("resource",item);pick.addProperty("incomplete",incomplete);return pick;
    }
    /** Numeric triples keep large manifests inside the existing byte budget; old position objects remain valid. */
    public static JsonObject sourcePosition(JsonElement value){
        JsonObject position;
        if(value!=null&&value.isJsonArray()){
            JsonArray xyz=value.getAsJsonArray();if(xyz.size()!=3)throw new IllegalArgumentException("Source position needs three coordinates");
            position=new JsonObject();int i=0;for(String key:List.of("x","y","z"))position.add(key,xyz.get(i++).deepCopy());
        }else if(value!=null&&value.isJsonObject())position=value.getAsJsonObject();
        else throw new IllegalArgumentException("Invalid source position");
        integer(position,"x",-29_900_000,29_900_000);integer(position,"y",-2048,2048);integer(position,"z",-29_900_000,29_900_000);return position;
    }
    private static boolean sameChest(JsonObject a,JsonObject b){return a.get("x").equals(b.get("x"))&&a.get("y").equals(b.get("y"))&&a.get("z").equals(b.get("z"));}
    /** Empty, one exact kit type, or mixed/unknown/loose items. Unknown stock is never free. */
    public static String columnOccupant(JsonObject observed){
        return columnOccupant(observed,null);
    }
    public static boolean columnContentsMatch(JsonObject observed,Set<String> allowed){
        return !columnOccupant(observed,allowed).equals("mixed");
    }
    public static boolean categoryContentsMatch(JsonObject observed,JsonObject category){
        Set<String> ids=new HashSet<>();
        for(JsonElement v:observed.getAsJsonArray("shulkers")){JsonObject box=v.getAsJsonObject();String id=kitTypeId(box);if(!TaskWire.flag(box,"contentsKnown")||!KitCategories.matches(category,id,box.getAsJsonObject("items")))return false;ids.add(id);}
        return columnContentsMatch(observed,ids);
    }
    private static String columnOccupant(JsonObject observed,Set<String> allowed){
        if(!TaskWire.text(observed,"status").equals("observed")||TaskWire.flag(observed,"inferred"))return "mixed";
        JsonObject items=observed.getAsJsonObject("items");JsonArray boxes=observed.getAsJsonArray("shulkers");
        if(items.isEmpty()&&boxes.isEmpty())return "";
        String type="";JsonObject totals=new JsonObject();
        for(JsonElement element:boxes){JsonObject box=element.getAsJsonObject();String id=kitTypeId(box);if(id.isEmpty()||(allowed==null?!type.isEmpty()&&!type.equals(id):!allowed.contains(id)))return "mixed";type=id;String item=TaskWire.text(box,"item");int quantity=box.get("quantity").getAsInt();totals.addProperty(item,(totals.has(item)?totals.get(item).getAsLong():0)+quantity);
            // CrewInventory.manifest includes modern shulker contents, but legacy NBT boxes count only the box itself.
            if(!TaskWire.flag(box,"legacy"))for(var content:box.getAsJsonObject("items").entrySet())totals.addProperty(content.getKey(),(totals.has(content.getKey())?totals.get(content.getKey()).getAsLong():0)+content.getValue().getAsLong()*quantity);
        }
        return type.isEmpty()||!totals.equals(items)?"mixed":type;
    }
    public static JsonObject checkedColumn(JsonObject plan,JsonObject input){
        int x=integer(input,"x",-29_900_000,29_900_000),y=integer(input,"y",-2048,2048),z=integer(input,"z",-29_900_000,29_900_000);
        if(!contains(plan,x,y,z)||!Set.of("X","Z","Vertical").contains(TaskWire.text(input,"axis")))throw new IllegalArgumentException("Invalid destination column");
        JsonObject column=new JsonObject();column.addProperty("x",x);column.addProperty("y",y);column.addProperty("z",z);column.addProperty("axis",TaskWire.text(input,"axis"));
        for(String field:List.of("alternateX","alternateZ"))if(input.has(field)){
            int offset=integer(input,field,-1,1);
            if(offset==0||!TaskWire.text(column,"axis").equals("Vertical")||input.has(field.equals("alternateX")?"alternateZ":"alternateX")
                ||!contains(plan,x+(field.equals("alternateX")?offset:0),y+1,z+(field.equals("alternateZ")?offset:0)))throw new IllegalArgumentException("Invalid alternating destination column");
            column.addProperty(field,offset);
        }
        return column;
    }
    public static boolean columnContains(JsonObject column,int x,int y,int z){
        int dx=x-column.get("x").getAsInt(),dy=y-column.get("y").getAsInt(),dz=z-column.get("z").getAsInt();
        if(dy<0)return false;
        if(column.has("alternateX")||column.has("alternateZ"))return dx==(dy%2)*(column.has("alternateX")?column.get("alternateX").getAsInt():0)&&dz==(dy%2)*(column.has("alternateZ")?column.get("alternateZ").getAsInt():0);
        return switch(TaskWire.text(column,"axis")){case "X"->dx==0&&(dz==0||Math.abs(dz)==dy);case "Z"->dz==0&&(dx==0||Math.abs(dx)==dy);default->dx==0&&dz==0;};
    }
    /** Alternating stacks need both adjacent levels witnessed; nearby banks alone prove no connection. */
    public static JsonObject mappedColumn(JsonObject db,JsonObject anchor){
        JsonObject column=new JsonObject();for(String key:List.of("x","y","z"))column.add(key,anchor.get(key).deepCopy());column.addProperty("axis",columnAxis(db));
        int x=anchor.get("x").getAsInt(),y=anchor.get("y").getAsInt(),z=anchor.get("z").getAsInt();JsonObject rows=db.getAsJsonObject("containers");
        JsonObject direct=rows.getAsJsonObject(x+","+(y+1)+","+z);if(direct!=null&&storageChest(direct))return column;
        JsonObject above=rows.getAsJsonObject(x+","+(y+2)+","+z);
        if(above==null||!storageChest(above))return column;
        String field="";int offset=0,matches=0;
        for(int[] d:List.of(new int[]{1,0},new int[]{-1,0},new int[]{0,1},new int[]{0,-1})){
            JsonObject odd=rows.getAsJsonObject((x+d[0])+","+(y+1)+","+(z+d[1]));
            if(odd!=null&&storageChest(odd)){matches++;field=d[0]==0?"alternateZ":"alternateX";offset=d[0]==0?d[1]:d[0];}
        }
        if(matches==1){column.addProperty("axis","Vertical");column.addProperty(field,offset);}
        return column;
    }
    private static String columnKey(JsonObject column){return column.get("x")+","+column.get("z");}
    private static Set<String> allocatedColumns(JsonObject plan){
        if(!plan.has("allocatedColumns"))return Set.of();
        if(!TaskWire.flag(plan,"fillAllocatedColumns")||plan.has("targetColumn")||!plan.get("allocatedColumns").isJsonArray())throw new IllegalArgumentException("Column allocations require the allocation-fill policy");
        JsonArray columns=plan.getAsJsonArray("allocatedColumns");if(columns.isEmpty()||columns.size()>128)throw new IllegalArgumentException("Allocate 1–128 destination columns");
        Set<String> keys=new LinkedHashSet<>();for(JsonElement value:columns)if(!keys.add(columnKey(checkedColumn(plan,value.getAsJsonObject()))))throw new IllegalArgumentException("Duplicate allocated column");return keys;
    }
    private static boolean storageChest(JsonObject o){return !TaskWire.text(o,"status").equals("removed")&&Set.of("minecraft:chest","minecraft:trapped_chest","").contains(TaskWire.text(o,"block"));}
    private static boolean assumedEmptyCandidate(JsonObject db,JsonObject o){
        return TaskWire.flag(db.getAsJsonObject("bounds"),"assumeEmpty")&&storageChest(o)&&!TaskWire.text(o,"block").isEmpty()
            &&TaskWire.text(o,"status").equals("unscanned")&&!TaskWire.flag(o,"inferred")
            &&TaskWire.text(o,"reason").equals("Storage discovered; contents not yet observed")
            &&o.has("capacity")&&o.get("capacity").getAsInt()>0&&o.getAsJsonObject("items").isEmpty()&&o.getAsJsonArray("shulkers").isEmpty();
    }
    private static int columnBottom(JsonObject db){
        return db.getAsJsonObject("containers").entrySet().stream().map(e->e.getValue().getAsJsonObject()).filter(StashCatalog::storageChest)
            .mapToInt(o->o.get("y").getAsInt()).min().orElse(Integer.MIN_VALUE);
    }
    private static String columnAxis(JsonObject db){
        Set<Integer> xs=new HashSet<>(),zs=new HashSet<>();int bottom=columnBottom(db);
        for(var entry:db.getAsJsonObject("containers").entrySet()){JsonObject o=entry.getValue().getAsJsonObject();if(storageChest(o)&&o.get("y").getAsInt()==bottom){xs.add(o.get("x").getAsInt());zs.add(o.get("z").getAsInt());}}
        return zs.size()==1&&xs.size()>1?"X":xs.size()==1&&zs.size()>1?"Z":"Vertical";
    }
    /** Advisory per-stash stock floors, separate from column ownership and worker withdrawal policy. */
    public static synchronized JsonObject setKitReserve(Path root,String crew,String scope,String name,String expected,String kit,int floor){
        if(floor<0||floor>1_000_000)throw new IllegalArgumentException("Reserve must be 0–1,000,000 complete kits");
        JsonObject db=get(root,crew,scope,name);
        if(db.isEmpty())throw new IllegalArgumentException("Unknown stash");
        if(!TaskWire.text(summary(db),"revision").equals(expected))throw new IllegalStateException("Stash revision changed; refresh before editing reserves");
        kit=KitTypes.canonical(db,kit);
        if(!db.getAsJsonObject("kitTypes").has(kit))throw new IllegalArgumentException("Unknown kit type in this stash");
        JsonObject reserves=db.has("kitReserves")?db.getAsJsonObject("kitReserves"):new JsonObject();
        if(floor==0)reserves.remove(kit);else reserves.addProperty(kit,floor);
        db.add("kitReserves",reserves);db.addProperty("updatedAt",System.currentTimeMillis());
        StashStore.write(file(root,crew,scope,name),db);return summary(db);
    }
    /** One revision-checked write for an operator's entire selection; no inventory is moved. */
    public static synchronized JsonObject setColumnReservations(Path root,String crew,String scope,String name,String expected,String kit,JsonArray columns){
        return setColumnReservations(root,crew,scope,name,expected,kit,"",columns);
    }
    public static synchronized JsonObject setColumnReservations(Path root,String crew,String scope,String name,String expected,String kit,String categoryId,JsonArray columns){
        JsonObject db=get(root,crew,scope,name);
        if(db.isEmpty())throw new IllegalArgumentException("Unknown stash");
        if(!TaskWire.text(summary(db),"revision").equals(expected))throw new IllegalStateException("Stash revision changed; refresh before reserving columns");
        kit=KitTypes.canonical(db,kit);
        if(!kit.isEmpty()&&!categoryId.isEmpty())throw new IllegalArgumentException("Choose kit or category, not both");
        JsonObject category=categoryId.isEmpty()?null:KitCategories.category(root,crew,scope,categoryId);
        if(!categoryId.isEmpty()&&category==null)throw new IllegalArgumentException("Unknown category");
        if(columns==null||columns.isEmpty()||columns.size()>4096)throw new IllegalArgumentException("Select 1–4096 recorded columns");
        JsonObject type=null;
        if(!kit.isEmpty()){
            UUID.fromString(kit);type=db.getAsJsonObject("kitTypes").getAsJsonObject(kit);
            if(type==null)for(JsonElement value:list(root)){
                JsonObject source=value.getAsJsonObject();
                String candidate=KitTypes.canonical(source,kit);
                if(QueuePolicy.sameTeleportServer(TaskWire.text(source,"scope"),scope)&&(crew.equals(TaskWire.text(source,"crew"))||TaskWire.text(source,"crew").equals("Local"))&&source.getAsJsonObject("kitTypes").has(candidate)){
                    kit=candidate;type=source.getAsJsonObject("kitTypes").getAsJsonObject(kit);break;
                }
            }
            if(type==null)throw new IllegalArgumentException("Unknown kit type in this crew and Minecraft server");
            JsonObject criteria=new JsonObject(),identity=new JsonObject();criteria.addProperty("kitTypeId",kit);criteria.add("kitExemplar",type.get("items").deepCopy());identity.add(kit,type.deepCopy());criteria.add("kitIdentities",identity);KitTypes.bind(db,criteria);kit=KitTypes.canonical(db,kit);
            JsonObject prior=db.getAsJsonObject("kitTypes").getAsJsonObject(kit);if(prior!=null&&KitTypes.fullness(prior.getAsJsonObject("items"))>=KitTypes.fullness(type.getAsJsonObject("items")))type=prior;
        }
        JsonObject reservations=db.has("columnReservations")?db.getAsJsonObject("columnReservations"):new JsonObject();Set<String> keys=new HashSet<>();int bottom=columnBottom(db);
        for(JsonElement value:columns){
            if(!value.isJsonObject()||!Set.of("x","y","z","axis","alternateX","alternateZ").containsAll(value.getAsJsonObject().keySet()))throw new IllegalArgumentException("Invalid column fields");
            JsonObject column=checkedColumn(db.getAsJsonObject("bounds"),value.getAsJsonObject());String key=columnKey(column);
            JsonObject anchor=db.getAsJsonObject("containers").getAsJsonObject(column.get("x")+","+column.get("y")+","+column.get("z"));
            if(anchor==null||!storageChest(anchor)||column.get("y").getAsInt()!=bottom||!keys.add(key))throw new IllegalArgumentException("Select distinct recorded bottom-row chest columns");
            JsonObject mapped=mappedColumn(db,anchor);
            if(mapped.has("alternateX")||mapped.has("alternateZ"))column=mapped;
            else if(column.has("alternateX")||column.has("alternateZ"))throw new IllegalArgumentException("Alternating column must match mapped chest geometry");
            if(kit.isEmpty()&&category==null){reservations.remove(key);continue;}
            for(var entry:db.getAsJsonObject("containers").entrySet()){
                JsonObject o=entry.getValue().getAsJsonObject();
                if(!storageChest(o)||!columnContains(column,o.get("x").getAsInt(),o.get("y").getAsInt(),o.get("z").getAsInt())||!TaskWire.text(o,"status").equals("observed")||TaskWire.flag(o,"inferred"))continue;
                String occupant=columnOccupant(o);
                if(category!=null?!categoryContentsMatch(o,category):!occupant.isEmpty()&&!occupant.equals(kit))throw new IllegalStateException("Column "+key+" contains another kit or loose items; nothing was reserved");
            }
            JsonObject reservation=new JsonObject();reservation.addProperty(category==null?"kitTypeId":"categoryId",category==null?kit:categoryId);reservation.addProperty("manual",true);reservation.add("column",column);reservation.addProperty("reservedAt",System.currentTimeMillis());reservations.add(key,reservation);
        }
        if(type!=null){type=type.deepCopy();type.addProperty("id",kit);db.getAsJsonObject("kitTypes").add(kit,type);}
        db.add("columnReservations",reservations);db.addProperty("updatedAt",System.currentTimeMillis());StashStore.write(file(root,crew,scope,name),db);return summary(db);
    }
    /** Kit ownership survives jobs and restarts. Unobserved columns are never allocated. */
    public static synchronized JsonObject reserveColumn(Path root,String crew,String scope,String name,String kit,Set<String> excluded){
        return reserveColumn(root,crew,scope,name,kit,excluded,null,Set.of(kit));
    }
    private static boolean reservationMatches(JsonObject reservation,Set<String> allowed){
        if(reservation==null||TaskWire.flag(reservation,"blocked"))return false;
        Set<String> saved=new HashSet<>();
        if(reservation.has("columnKitTypeIds"))for(JsonElement id:reservation.getAsJsonArray("columnKitTypeIds"))saved.add(id.getAsString());
        else saved.add(TaskWire.text(reservation,"kitTypeId"));
        return saved.equals(allowed);
    }
    private static boolean columnObservedFull(JsonObject db,JsonObject column){
        JsonObject fill=db.has("columnFill")?db.getAsJsonObject("columnFill").getAsJsonObject(columnKey(column)):null;
        if(fill!=null&&!TaskWire.flag(fill,"uncertain")&&fill.get("capacity").getAsLong()>0&&fill.get("kits").getAsLong()>=fill.get("capacity").getAsLong())return true;
        Set<Integer> rows=new HashSet<>();
        for(JsonElement value:db.getAsJsonObject("containers").asMap().values()){
            JsonObject o=value.getAsJsonObject();if(!storageChest(o)||!columnContains(column,o.get("x").getAsInt(),o.get("y").getAsInt(),o.get("z").getAsInt()))continue;
            if(!TaskWire.text(o,"status").equals("observed")||TaskWire.flag(o,"inferred")||!o.has("slotCount")
                ||o.get("occupiedSlots").getAsInt()<o.get("slotCount").getAsInt())return false;
            rows.add(o.get("y").getAsInt());
        }
        for(int y=column.get("y").getAsInt();y<=db.getAsJsonObject("bounds").get("maxY").getAsInt();y++)if(!rows.contains(y))return false;
        return !rows.isEmpty();
    }
    private static synchronized JsonObject reserveColumn(Path root,String crew,String scope,String name,String kit,Set<String> excluded,JsonObject requested,Set<String> allowed){
        return reserveColumn(root,crew,scope,name,kit,excluded,requested,allowed,false);
    }
    private static synchronized JsonObject reserveColumn(Path root,String crew,String scope,String name,String kit,Set<String> excluded,JsonObject requested,Set<String> allowed,boolean categorySupport){
        UUID.fromString(kit);JsonObject db=get(root,crew,scope,name);if(db.isEmpty())throw new IllegalArgumentException("Unknown destination stash");
        kit=KitTypes.canonical(db,kit);Set<String> normalized=new LinkedHashSet<>();for(String id:allowed)normalized.add(KitTypes.canonical(db,id));allowed=normalized;
        if(requested!=null)requested=checkedColumn(db.getAsJsonObject("bounds"),requested);
        JsonObject reservations=db.has("columnReservations")?db.getAsJsonObject("columnReservations"):new JsonObject();
        JsonObject categories=categorySupport?KitCategories.get(root,crew,scope).getAsJsonObject("categories"):new JsonObject();
        JsonObject selected=null;int best=8,bottom=columnBottom(db);
        List<JsonObject> candidates=db.getAsJsonObject("containers").asMap().values().stream().map(JsonElement::getAsJsonObject).toList();
        if(TaskWire.flag(db.getAsJsonObject("bounds"),"assumeEmpty"))candidates=candidates.stream().sorted(Comparator.comparingInt((JsonObject o)->o.get("z").getAsInt()).thenComparingInt(o->o.get("x").getAsInt())).toList();
        for(JsonObject o:candidates){
            String key=columnKey(o);if(!storageChest(o)||o.get("y").getAsInt()!=bottom||excluded.contains(key))continue;
            if(requested!=null&&(!key.equals(columnKey(requested))||o.get("y").getAsInt()!=requested.get("y").getAsInt()))continue;
            String occupant=columnOccupant(o);JsonObject reservation=reservations.has(key)?reservations.getAsJsonObject(key):null;
            JsonObject category=reservation==null?null:categories.getAsJsonObject(TaskWire.text(reservation,"categoryId"));
            JsonObject incoming=db.getAsJsonObject("kitTypes").getAsJsonObject(kit);
            boolean categoryOwned=category!=null&&!TaskWire.flag(reservation,"blocked")&&incoming!=null&&KitCategories.matches(category,kit,incoming.getAsJsonObject("items"));
            boolean upgrade=requested!=null&&allowed.size()>1&&reservation!=null&&!reservation.has("columnKitTypeIds")&&!TaskWire.flag(reservation,"blocked")&&allowed.contains(TaskWire.text(reservation,"kitTypeId"));
            if(reservation!=null&&!categoryOwned&&!reservationMatches(reservation,allowed)&&!upgrade)continue;
            boolean withdrawn=(categoryOwned||reservationMatches(reservation,allowed))&&TaskWire.text(o,"status").equals("unscanned")
                &&TaskWire.text(o,"reason").equals("Contents changed by a confirmed stash withdrawal; rescan required");
            boolean assumed=assumedEmptyCandidate(db,o);
            if(!withdrawn&&!assumed&&(categoryOwned?!categoryContentsMatch(o,category):!columnContentsMatch(o,allowed)))continue;
            if(db.has("fullColumns")&&db.getAsJsonObject("fullColumns").has(key))continue;
            int rank=reservation!=null&&TaskWire.flag(reservation,"manual")?0:occupant.equals(kit)||withdrawn?1:reservation!=null?2:3;
            if(rank>=best)continue;
            JsonObject candidate;
            if(requested!=null)candidate=requested.deepCopy();
            else if(reservation!=null&&reservation.has("column"))candidate=checkedColumn(db.getAsJsonObject("bounds"),reservation.getAsJsonObject("column"));
            else candidate=mappedColumn(db,o);
            if(columnObservedFull(db,candidate))continue;
            selected=candidate;best=rank;
        }
        if(selected==null)return null;
        StashStock.startColumn(db,selected);
        if(!reservations.has(columnKey(selected))||!reservations.getAsJsonObject(columnKey(selected)).has("categoryId")&&(!TaskWire.flag(reservations.getAsJsonObject(columnKey(selected)),"manual")||!reservationMatches(reservations.getAsJsonObject(columnKey(selected)),allowed))){
            JsonObject reservation=reservations.has(columnKey(selected))?reservations.getAsJsonObject(columnKey(selected)).deepCopy():new JsonObject();if(!allowed.contains(TaskWire.text(reservation,"kitTypeId")))reservation.addProperty("kitTypeId",kit);reservation.addProperty("reservedAt",System.currentTimeMillis());
            if(allowed.size()>1){JsonArray ids=new JsonArray();allowed.forEach(ids::add);reservation.add("columnKitTypeIds",ids);reservation.add("column",selected.deepCopy());}
            reservations.add(columnKey(selected),reservation);
        }db.add("columnReservations",reservations);
        db.addProperty("updatedAt",System.currentTimeMillis());StashStore.write(file(root,crew,scope,name),db);return selected;
    }
    public static JsonObject kitAction(Path root,String crew,String scope,JsonObject request,String worker){
        String sourceCrew=get(root,crew,scope,TaskWire.text(request,"name")).isEmpty()?"Local":crew;
        JsonObject sourceRequest=request.deepCopy();sourceRequest.remove("targetColumn");sourceRequest.remove("columnKitTypeIds");
        sourceRequest.remove("observedSourcesOnly");sourceRequest.remove("sourceScanGaps");
        JsonObject routed=route(root,sourceCrew,scope,sourceRequest,worker);String id=TaskWire.text(request,"kitTypeId");int count=integer(request,"count",1,36);
        for(String key:List.of("transferAll","includeIncomplete","returnHome","returnByDeath","enderTransport","fillAllocatedColumns","observedSourcesOnly"))if(request.has(key)&&(!request.get(key).isJsonPrimitive()||!request.getAsJsonPrimitive(key).isBoolean()))throw new IllegalArgumentException("Expected boolean "+key);
        boolean all=TaskWire.flag(request,"transferAll"),includeIncomplete=TaskWire.flag(request,"includeIncomplete");
        if(includeIncomplete&&TaskWire.flag(request,"incomplete"))throw new IllegalArgumentException("Incomplete removal cannot also include complete kits");
        String destination=TaskWire.text(request,"destination");if(!Set.of("Player","Ender Chest","Stash","Carry").contains(destination))throw new IllegalArgumentException("Choose Player, Ender Chest, Stash, or Carry for a kit withdrawal");
        if(TaskWire.flag(request,"returnByDeath")&&(!destination.equals("Player")||!TaskWire.flag(request,"returnHome")))throw new IllegalArgumentException("Respawn return requires a player delivery with a return route");
        if(request.has("targetColumn")&&!destination.equals("Stash"))throw new IllegalArgumentException("A destination column requires a stash delivery");
        if(request.has("columnKitTypeIds"))columnKitTypes(request);
        // targetColumn belongs to the destination, never to the source bounds.
        routed.remove("targetColumn");
        routed.remove("columnKitTypeIds");
        if(all&&!destination.equals("Stash"))throw new IllegalArgumentException("Transfer all requires a destination stash");
        boolean fill=TaskWire.flag(request,"fillAllocatedColumns");
        if(fill&&(!all||!destination.equals("Stash")||request.has("targetColumn")||TaskWire.flag(request,"oneBatch")||TaskWire.flag(request,"mixedLoad")))throw new IllegalArgumentException("Column filling requires a sequential until-empty stash transfer");
        if(TaskWire.flag(request,"enderTransport")&&(!all||!destination.equals("Stash")||request.has("targetColumn")||TaskWire.flag(request,"oneBatch")||TaskWire.flag(request,"mixedLoad")))throw new IllegalArgumentException("Ender transport requires a sequential until-empty stash transfer");
        if(TaskWire.flag(request,"enderTransport"))routed.addProperty("enderTransport",true);
        JsonObject db=get(root,sourceCrew,scope,TaskWire.text(routed,"name"));id=KitTypes.canonical(db,id);JsonArray picks=kitPicks(db,id,count,TaskWire.flag(request,"incomplete"),includeIncomplete,all,TaskWire.flag(request,"observedSourcesOnly"));
        if(all&&picks.size()>54){
            boolean triples=picks.size()>512;JsonArray compact=new JsonArray();
            for(JsonElement value:picks){JsonObject position=value.getAsJsonObject();if(triples){JsonArray xyz=new JsonArray();for(String key:List.of("x","y","z"))xyz.add(position.get(key).deepCopy());compact.add(xyz);}else{position.keySet().retainAll(Set.of("x","y","z"));compact.add(position);}}
            picks=compact;
            routed.addProperty("compactPicks",true);
        }
        routed.add("picks",picks);routed.addProperty("type","StashResupply");routed.addProperty("kitTypeId",id);routed.addProperty("count",count);routed.addProperty("kitItem",TaskWire.text(db.getAsJsonObject("kitTypes").getAsJsonObject(id),"item"));
        routed.addProperty("destination",destination);routed.addProperty("depositMode",destination.equals("Ender Chest")?"Ender Chest":"Carry");routed.addProperty("catalogCrew",sourceCrew);
        routed.addProperty("incomplete",request.has("incomplete")&&request.get("incomplete").getAsBoolean());routed.add("kitExemplar",db.getAsJsonObject("kitTypes").getAsJsonObject(id).get("items").deepCopy());
        JsonObject identities=new JsonObject();identities.add(id,KitTypes.identities(db.getAsJsonObject("kitTypes")).get(id));routed.add("kitIdentities",identities);
        if(destination.equals("Player")){
            routed.addProperty("recipient",UUID.fromString(TaskWire.text(request,"recipient")).toString());
            String name=TaskWire.text(request,"recipientName");if(!name.matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("Specify the recipient's exact Minecraft username");routed.addProperty("recipientName",name);
            routed.addProperty("dimension",scope.substring(scope.indexOf('\n')+1));
            if(TaskWire.flag(request,"returnHome")){
                JsonObject back=route(root,sourceCrew,scope,db.getAsJsonObject("bounds"),worker);
                back.addProperty("type","StashVisit");back.addProperty("destination","Player");back.addProperty("dimension",TaskWire.text(routed,"dimension"));routed.add("returnStash",back);
            }else routed.remove("returnStash");
        }
        if(destination.equals("Stash")){
            String targetName=TaskWire.text(request,"targetStashName"),targetScope=scope;
            if(request.has("targetDimension")){
                String dimension=TaskWire.text(request,"targetDimension");if(dimension.length()>128||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Invalid destination dimension");
                targetScope=scope.substring(0,scope.indexOf('\n')+1)+dimension;
            }
            if(targetScope.equals(scope)&&targetName.equals(TaskWire.text(routed,"name")))throw new IllegalArgumentException("Choose a different destination stash");
            String targetCrew=get(root,crew,targetScope,targetName).isEmpty()?"Local":crew;
            JsonObject targetDb=get(root,targetCrew,targetScope,targetName);if(targetDb.isEmpty())throw new IllegalArgumentException("Define the destination stash in its selected dimension first");
            KitTypes.bind(targetDb,routed);
            JsonObject catalogIdentities=KitTypes.identities(db.getAsJsonObject("kitTypes"));KitTypes.identities(targetDb.getAsJsonObject("kitTypes")).entrySet().forEach(e->catalogIdentities.add(e.getKey(),e.getValue().deepCopy()));
            Map<String,List<String>> families=new LinkedHashMap<>();catalogIdentities.entrySet().forEach(e->families.computeIfAbsent(TaskWire.text(e.getValue().getAsJsonObject(),"familyId"),k->new ArrayList<>()).add(e.getKey()));
            Set<String> known=new LinkedHashSet<>(catalogIdentities.keySet());for(var family:families.entrySet())if(family.getValue().size()==1)known.add(family.getKey());else for(String type:family.getValue())identities.add(type,catalogIdentities.get(type).deepCopy());
            if(known.size()>512)throw new IllegalArgumentException("Incidental kit recognition exceeds 512 catalog types");
            JsonArray knownIds=new JsonArray();known.forEach(knownIds::add);routed.add("knownKitTypeIds",knownIds);
            JsonObject target=route(root,targetCrew,targetScope,targetDb.getAsJsonObject("bounds"),worker);
            if(targetScope.equals(scope)&&overlaps(routed,target))throw new IllegalArgumentException("Source and destination stash selections must not overlap");
            target.addProperty("catalogCrew",targetCrew);target.addProperty("kitTypeId",id);target.addProperty("count",count);target.add("kitExemplar",routed.get("kitExemplar").deepCopy());target.add("incomplete",routed.get("incomplete").deepCopy());target.addProperty("includeIncomplete",includeIncomplete);routed.add("targetStash",target);
            target.add("kitIdentities",identities.deepCopy());
            if(fill){
                routed.addProperty("fillAllocatedColumns",true);target.addProperty("fillAllocatedColumns",true);
                JsonObject back=route(root,sourceCrew,scope,db.getAsJsonObject("bounds"),worker);
                back.addProperty("catalogCrew",sourceCrew);back.addProperty("dimension",scope.substring(scope.indexOf('\n')+1));routed.add("returnStash",back);
            }
            if(request.has("targetColumn")){
                JsonObject column=checkedColumn(target,request.getAsJsonObject("targetColumn"));
                JsonObject anchor=targetDb.getAsJsonObject("containers").getAsJsonObject(column.get("x")+","+column.get("y")+","+column.get("z"));
                Set<String> allowed=new LinkedHashSet<>();for(String type:columnKitTypes(request))allowed.add(KitTypes.canonical(targetDb,KitTypes.canonical(db,type)));
                if(anchor==null||column.get("y").getAsInt()!=columnBottom(targetDb)||!storageChest(anchor)||!columnContentsMatch(anchor,allowed)&&!assumedEmptyCandidate(targetDb,anchor))throw new IllegalArgumentException("Select an observed compatible or explicitly assumed-empty bottom chest column");
                target.add("targetColumn",column);
                if(request.has("columnKitTypeIds")){JsonArray normalized=new JsonArray();for(String type:columnKitTypes(request)){String sourceId=KitTypes.canonical(db,type);normalized.add(sourceId.equals(id)?id:KitTypes.canonical(targetDb,sourceId));}target.add("columnKitTypeIds",normalized);}
            }
        }
        routed.remove("targetDimension");
        int limit=all?60_000:7_500;
        while(!all&&routed.toString().length()>limit&&picks.size()>1)picks.remove(picks.size()-1);
        if(routed.toString().length()>limit)throw new IllegalStateException("Kit action exceeds the workflow data limit; split this stash selection or shorten its exemplar");
        return routed;
    }
    /** Freeze only type IDs; resolve one bounded, current delivery plan at a time. */
    public static JsonObject kitTransfer(Path root,String crew,String scope,JsonObject request,String worker){
        if(request.has("columnAllocations")&&(request.has("routes")||TaskWire.flag(request,"mixedLoads")||TaskWire.flag(request,"enderTransport")||TaskWire.flag(request,"depositCarried")))throw new IllegalArgumentException("Column rotation uses sequential inventory loads with explicit destination allocations");
        if(TaskWire.flag(request,"fillAllocatedColumns")&&(request.has("routes")||TaskWire.flag(request,"mixedLoads")||TaskWire.flag(request,"depositCarried")))throw new IllegalArgumentException("Column filling needs sequential kit types and an empty carried-kit inventory");
        if(request.has("routes")){if(TaskWire.flag(request,"enderTransport"))throw new IllegalArgumentException("Ender transport uses sequential kit types");if(TaskWire.flag(request,"mixedLoads"))throw new IllegalArgumentException("Mixed loads use one source and one destination");return kitRoutes(root,crew,scope,request,worker);}
        if(request.has("targetColumn"))throw new IllegalArgumentException("Use Kit Delivery for a single-column transfer; multi-kit transfers allocate separate columns");
        String name=TaskWire.text(request,"name"),sourceCrew=get(root,crew,scope,name).isEmpty()?"Local":crew;
        JsonObject db=get(root,sourceCrew,scope,name);if(db.isEmpty())throw new IllegalArgumentException("Unknown source stash");
        int count=request.has("count")?integer(request,"count",1,36):36;
        for(String key:List.of("includeIncomplete","depositCarried","depositOnly","mixedLoads","enderTransport","fillAllocatedColumns","observedSourcesOnly"))if(request.has(key)&&(!request.get(key).isJsonPrimitive()||!request.getAsJsonPrimitive(key).isBoolean()))throw new IllegalArgumentException("Expected boolean "+key);
        if(TaskWire.flag(request,"observedSourcesOnly")&&(request.has("columnAllocations")||TaskWire.flag(request,"mixedLoads")))throw new IllegalArgumentException("Observed-only transfers use sequential kit types");
        if(TaskWire.flag(request,"enderTransport")&&TaskWire.flag(request,"mixedLoads"))throw new IllegalArgumentException("Ender transport uses sequential kit types");
        if(TaskWire.flag(request,"depositOnly")&&!TaskWire.flag(request,"depositCarried"))throw new IllegalArgumentException("Deposit-only recovery requires depositCarried");
        boolean include=TaskWire.flag(request,"includeIncomplete");JsonArray ids=new JsonArray();Set<String> seen=new LinkedHashSet<>();
        JsonArray selected=request.has("kitTypeIds")?request.getAsJsonArray("kitTypeIds"):null;
        if(TaskWire.flag(request,"mixedLoads")&&(selected==null||selected.size()<2||selected.size()>16||TaskWire.flag(request,"depositCarried")))throw new IllegalArgumentException("Mixed loads require 2–16 selected kit types and an empty kit inventory; carried recovery uses a separate job");
        if(selected!=null&&(selected.isEmpty()||selected.size()>128))throw new IllegalArgumentException("Select 1–128 kit types, or omit kitTypeIds for all known types");
        Collection<String> candidates=selected==null?db.getAsJsonObject("kitTypes").keySet():selected.asList().stream().map(JsonElement::getAsString).toList();
        String planningId="";
        for(String selectedId:candidates){String id=KitTypes.canonical(db,selectedId);
            UUID.fromString(id);if(!seen.add(id))throw new IllegalArgumentException("Duplicate kit type");
            try{kitPicks(db,id,count,false,include,true,TaskWire.flag(request,"observedSourcesOnly"));ids.add(id);if(planningId.isEmpty())planningId=id;}
            catch(IllegalStateException e){if(request.has("columnAllocations")&&e.getMessage().startsWith("No observed or mapped source chest"))ids.add(id);else if(selected!=null||!e.getMessage().startsWith("No observed or mapped source chest"))throw e;}
        }
        if(ids.isEmpty()||ids.size()>128||planningId.isEmpty())throw new IllegalArgumentException("Source needs 1–128 transferable kit types");
        JsonObject args=db.getAsJsonObject("bounds").deepCopy();args.addProperty("count",count);args.addProperty("destination","Stash");args.addProperty("targetStashName",TaskWire.text(request,"targetStashName"));args.addProperty("transferAll",true);args.addProperty("includeIncomplete",include);args.add("kitTypeIds",ids);
        if(TaskWire.flag(request,"observedSourcesOnly")){args.addProperty("observedSourcesOnly",true);args.addProperty("sourceScanGaps",sourceScanGaps(db));}
        if(request.has("targetDimension"))args.add("targetDimension",request.get("targetDimension").deepCopy());
        if(TaskWire.flag(request,"enderTransport"))args.addProperty("enderTransport",true);
        if(TaskWire.flag(request,"fillAllocatedColumns"))args.addProperty("fillAllocatedColumns",true);
        args.addProperty("depositCarried",TaskWire.flag(request,"depositCarried"));
        if(TaskWire.flag(request,"mixedLoads"))args.addProperty("mixedLoads",true);
        if(TaskWire.flag(request,"depositOnly"))args.addProperty("depositOnly",true);
        if(request.has("columnAllocations"))args.addProperty("fillAllocatedColumns",true);
        JsonObject first=args.deepCopy();first.remove("kitTypeIds");first.addProperty("kitTypeId",planningId);JsonObject planned=kitAction(root,crew,scope,first,worker);
        if(request.has("columnAllocations")){
            if(!request.get("columnAllocations").isJsonObject())throw new IllegalArgumentException("Specify column allocations by kit type");
            JsonObject allocations=request.getAsJsonObject("columnAllocations"),checked=new JsonObject();Set<String> used=new HashSet<>();
            if(allocations.size()!=ids.size())throw new IllegalArgumentException("Every selected kit needs a column allocation");
            for(JsonElement id:ids){String key=id.getAsString();JsonObject target=planned.getAsJsonObject("targetStash").deepCopy();target.add("allocatedColumns",allocations.get(key));allocatedColumns(target);JsonArray columns=new JsonArray();
                for(JsonElement value:target.getAsJsonArray("allocatedColumns")){JsonObject column=checkedColumn(target,value.getAsJsonObject());if(!used.add(columnKey(column)))throw new IllegalArgumentException("Kit allocations overlap");columns.add(column);}checked.add(key,columns);
            }
            args.add("columnAllocations",checked);
        }
        return args;
    }
    /** Route descriptors stay small; each source manifest is resolved and frozen on its first turn. */
    private static JsonObject kitRoutes(Path root,String crew,String scope,JsonObject request,String worker){
        for(String key:List.of("name","targetStashName","kitTypeId","kitTypeIds","targetColumn","depositOnly"))if(request.has(key))throw new IllegalArgumentException("Round Robin uses route-specific sources, kits and destinations; no "+key);
        if(!request.get("routes").isJsonArray())throw new IllegalArgumentException("Round Robin routes must be an array");
        JsonArray routes=request.getAsJsonArray("routes");if(routes.size()<2||routes.size()>16)throw new IllegalArgumentException("Choose 2–16 Round Robin routes");
        int count=request.has("count")?integer(request,"count",1,36):36;
        for(String key:List.of("includeIncomplete","depositCarried"))if(request.has(key)&&(!request.get(key).isJsonPrimitive()||!request.getAsJsonPrimitive(key).isBoolean()))throw new IllegalArgumentException("Expected boolean "+key);
        JsonObject args=new JsonObject();args.addProperty("count",count);args.addProperty("includeIncomplete",TaskWire.flag(request,"includeIncomplete"));args.addProperty("depositCarried",TaskWire.flag(request,"depositCarried"));
        JsonArray checked=new JsonArray();Set<String> seen=new HashSet<>();Map<String,String> destinations=new HashMap<>();
        for(JsonElement value:routes){
            if(!value.isJsonObject()||!value.getAsJsonObject().keySet().equals(Set.of("name","kitTypeId","targetStashName")))throw new IllegalArgumentException("Each route needs only name, kitTypeId and targetStashName");
            for(JsonElement field:value.getAsJsonObject().asMap().values())if(!field.isJsonPrimitive()||!field.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Route names and kit IDs must be strings");
            JsonObject route=new JsonObject();for(String key:List.of("name","kitTypeId","targetStashName"))route.addProperty(key,TaskWire.text(value.getAsJsonObject(),key));
            String kit=UUID.fromString(TaskWire.text(route,"kitTypeId")).toString();JsonObject source=get(root,crew,scope,TaskWire.text(route,"name"));if(source.isEmpty())source=get(root,"Local",scope,TaskWire.text(route,"name"));kit=KitTypes.canonical(source,kit);route.addProperty("kitTypeId",kit);
            if(!seen.add(route.toString()))throw new IllegalArgumentException("Duplicate Round Robin route");
            String target=TaskWire.text(route,"targetStashName"),previous=destinations.putIfAbsent(kit,target);
            if(previous!=null&&!previous.equals(target))throw new IllegalArgumentException("Routes for the same kit must share a destination so carried-kit recovery is unambiguous");
            kitAction(root,crew,scope,routeRequest(root,crew,scope,args,route),worker);checked.add(route);
        }
        args.add("routes",checked);return args;
    }
    private static JsonObject routeRequest(Path root,String crew,String scope,JsonObject args,JsonObject route){
        JsonObject db=get(root,crew,scope,TaskWire.text(route,"name"));if(db.isEmpty())db=get(root,"Local",scope,TaskWire.text(route,"name"));
        if(db.isEmpty())throw new IllegalArgumentException("Unknown Round Robin source stash");
        JsonObject request=db.getAsJsonObject("bounds").deepCopy();route.entrySet().forEach(e->request.add(e.getKey(),e.getValue().deepCopy()));for(String key:List.of("count","includeIncomplete","depositCarried"))request.add(key,args.get(key).deepCopy());
        request.addProperty("destination","Stash");request.addProperty("transferAll",true);return request;
    }
    /** Worker selects an index, never its own source, destination, or exemplar. */
    public static JsonObject kitTransferPlan(Path root,JsonObject task,String worker,JsonObject action){
        if(!TaskWire.text(task.getAsJsonObject("package"),"entry").equals("task-stash-transfer"))throw new IllegalArgumentException("Kit planning requires a stash transfer job");
        if(action.size()!=2||!TaskWire.text(action,"type").equals("KitTransferPlan"))throw new IllegalArgumentException("Kit planning accepts only type and index");
        if(task.getAsJsonObject("args").has("routes")){
            JsonObject args=task.getAsJsonObject("args");JsonArray routes=args.getAsJsonArray("routes");int index=integer(action,"index",1,routes.size());
            String crew=TaskWire.text(task,"crew"),scope=TaskWire.text(task,"server")+"\n"+TaskWire.text(task,"dimension");
            JsonObject plan=kitAction(root,crew,scope,routeRequest(root,crew,scope,args,routes.get(index-1).getAsJsonObject()),worker);
            plan.addProperty("oneBatch",true);JsonObject result=new JsonObject();result.add("args",plan);return result;
        }
        JsonObject args=task.getAsJsonObject("args").deepCopy();JsonArray ids=args.getAsJsonArray("kitTypeIds");
        int index=integer(action,"index",1,ids.size());String id=ids.get(index-1).getAsString();args.remove("kitTypeIds");args.remove("depositOnly");args.remove("mixedLoads");args.remove("columnAllocations");args.addProperty("kitTypeId",id);
        JsonObject result=new JsonObject();
        try{result.add("args",kitAction(root,TaskWire.text(task,"crew"),TaskWire.text(task,"server")+"\n"+TaskWire.text(task,"dimension"),args,worker));}
        catch(IllegalStateException e){if(!task.getAsJsonObject("args").has("columnAllocations")||!e.getMessage().startsWith("No observed or mapped source chest"))throw e;result.addProperty("sourceUnavailable",true);}
        return result;
    }
    public static JsonObject cachedKitTransferPlan(Path root,JsonObject task,JsonObject run,String worker){
        JsonObject action=run.getAsJsonObject("action");
        if(TaskWire.text(run,"kitPlanToken").equals(TaskWire.text(run,"token"))){
            if(!action.equals(run.get("kitPlanRequest")))throw new IllegalArgumentException("Kit plan changed under the same action token");
            return run.getAsJsonObject("kitPlan").deepCopy();
        }
        JsonObject result;
        if(task.getAsJsonObject("args").has("routes")||TaskWire.flag(task.getAsJsonObject("args"),"mixedLoads")){
            if(!TaskWire.text(task.getAsJsonObject("package"),"entry").equals("task-stash-transfer"))throw new IllegalArgumentException("Kit planning requires a stash transfer job");
            String field=task.getAsJsonObject("args").has("routes")?"routes":"kitTypeIds";
            JsonObject routes=run.has("kitRoutePlans")?run.getAsJsonObject("kitRoutePlans"):new JsonObject();String key=Integer.toString(integer(action,"index",1,task.getAsJsonObject("args").getAsJsonArray(field).size()));
            // Still validate the request boundary when reusing a frozen manifest.
            if(action.size()!=2||!TaskWire.text(action,"type").equals("KitTransferPlan"))throw new IllegalArgumentException("Kit planning accepts only type and index");
            result=routes.has(key)?routes.getAsJsonObject(key).deepCopy():kitTransferPlan(root,task,worker,action);routes.add(key,result.deepCopy());run.add("kitRoutePlans",routes);
        }else result=kitTransferPlan(root,task,worker,action);
        run.add("kitPlan",result);run.add("kitPlanRequest",action.deepCopy());run.addProperty("kitPlanToken",TaskWire.text(run,"token"));return result.deepCopy();
    }
    private static boolean overlaps(JsonObject a,JsonObject b){
        for(String axis:List.of("X","Y","Z"))if(a.get("max"+axis).getAsInt()<b.get("min"+axis).getAsInt()||b.get("max"+axis).getAsInt()<a.get("min"+axis).getAsInt())return false;
        return true;
    }
    /** Convert a worker shortage category into one batched, catalog-backed refill request. */
    public static JsonObject refillNeeds(JsonObject db,JsonObject inventory,int primary,String paving){
        if(db==null||db.isEmpty()||!db.has("containers"))return null;JsonObject needs=new JsonObject();String primaryItem="";
        for(var value:db.getAsJsonObject("containers").entrySet())for(JsonElement element:value.getValue().getAsJsonObject().getAsJsonArray("shulkers")){
            JsonObject box=element.getAsJsonObject();if(box.has("mixed")&&box.get("mixed").getAsBoolean())continue;String item=TaskWire.text(box,"dominant");
            int category=item.equals(paving)?0:item.endsWith("_pickaxe")?1:Set.of("minecraft:enchanted_golden_apple","minecraft:golden_apple").contains(item)?2:item.equals("minecraft:netherrack")?3:item.equals("minecraft:ender_chest")?4:-1;
            if(category<0)continue;int missing=Math.max(0,ResourceLedger.value(inventory,"target",category)-ResourceLedger.available(inventory,category)),boxCount=box.getAsJsonObject("items").has(item)?box.getAsJsonObject("items").get(item).getAsInt():0;
            if(missing>=boxCount&&boxCount>0)needs.addProperty(item,Math.max(needs.has(item)?needs.get(item).getAsInt():0,missing));if(category==primary&&boxCount>0)primaryItem=item;
        }
        if(primaryItem.isEmpty())return null;if(!needs.has(primaryItem))needs.addProperty(primaryItem,1);needs.addProperty("_primary",primaryItem);return needs;
    }
    /** Select real, observed shulkers for one batched refill. Primary need wins; other needs join only when a full box short. */
    public static JsonArray refill(JsonObject db,JsonObject needs,String primary,int enderSlots){
        if(db==null||!db.has("containers")||needs==null||needs.isEmpty()||needs.size()>16||primary==null||!needs.has(primary)||enderSlots<0||enderSlots>54)throw new IllegalArgumentException("Invalid stash refill request");
        record Box(JsonObject value,int priority,long stock){}
        List<Box> boxes=new ArrayList<>();
        for(var container:db.getAsJsonObject("containers").entrySet()){
            JsonObject observed=container.getValue().getAsJsonObject();if(!TaskWire.text(observed,"status").equals("observed")||observed.has("inferred")&&observed.get("inferred").getAsBoolean())continue;
            for(JsonElement element:observed.getAsJsonArray("shulkers")){
                JsonObject box=element.getAsJsonObject(),items=box.getAsJsonObject("items");String resource=TaskWire.text(box,"dominant");
                if(!needs.has(resource)||box.has("mixed")&&box.get("mixed").getAsBoolean()||!items.has(resource))continue;
                long missing=integer(needs,resource,0,10_000_000),stock=items.get(resource).getAsLong();
                if(!resource.equals(primary)&&missing<stock)continue; // A secondary resource joins only when at least one matching box short.
                JsonObject pick=box.deepCopy();String[] xyz=container.getKey().split(",",-1);if(xyz.length!=3)continue;
                try{pick.addProperty("x",Integer.parseInt(xyz[0]));pick.addProperty("y",Integer.parseInt(xyz[1]));pick.addProperty("z",Integer.parseInt(xyz[2]));}catch(NumberFormatException ignored){continue;}
                pick.addProperty("resource",resource);boxes.add(new Box(pick,resource.equals(primary)?0:1,stock));
            }
        }
        boxes.sort(Comparator.comparingInt(Box::priority).thenComparing(Comparator.comparingLong(Box::stock).reversed()));
        JsonArray result=new JsonArray();Map<String,Long> filled=new HashMap<>();
        for(Box box:boxes){if(result.size()>=enderSlots)break;String resource=TaskWire.text(box.value(),"resource");long missing=needs.get(resource).getAsLong();if(filled.getOrDefault(resource,0L)>=missing)continue;result.add(box.value());filled.merge(resource,box.stock(),Long::sum);}
        return result;
    }
    /** Removed stock is never left as a stale promise. A later scan restores authoritative contents. */
    public static synchronized JsonObject invalidateWithdrawn(Path root,String crew,String scope,String name,JsonArray picks){
        if(picks==null||picks.isEmpty()||picks.size()>54)throw new IllegalArgumentException("Invalid stash withdrawal receipt");
        JsonObject db=get(root,crew,scope,name);if(db.isEmpty())throw new IllegalArgumentException("Unknown stash");
        Set<String> touched=invalidateWithdrawn(db,picks);
        db.addProperty("updatedAt",System.currentTimeMillis());StashStore.record(file(root,crew,scope,name),db,touched);return summary(db);
    }
    private static Set<String> invalidateWithdrawn(JsonObject db,JsonArray picks){
        JsonObject containers=db.getAsJsonObject("containers");
        Set<String> touched=new HashSet<>();JsonObject bounds=plan(db.getAsJsonObject("bounds"));
        for(JsonElement element:picks){JsonObject pick=element.getAsJsonObject();int x=integer(pick,"x",-29_900_000,29_900_000),y=integer(pick,"y",-2048,2048),z=integer(pick,"z",-29_900_000,29_900_000);if(!contains(bounds,x,y,z))throw new IllegalArgumentException("Withdrawal source is outside the stash");touched.add(x+","+y+","+z);}
        if(db.has("fullColumns"))db.getAsJsonObject("fullColumns").entrySet().removeIf(e->{
            for(JsonElement value:picks){JsonObject p=value.getAsJsonObject();if(columnContains(e.getValue().getAsJsonObject(),p.get("x").getAsInt(),p.get("y").getAsInt(),p.get("z").getAsInt()))return true;}return false;
        });
        for(String key:touched){JsonObject record=containers.has(key)?containers.getAsJsonObject(key):new JsonObject();
            Set<String> types=new LinkedHashSet<>();if(record.has("withdrawnKitTypes"))record.getAsJsonArray("withdrawnKitTypes").forEach(v->types.add(KitTypes.canonical(db,v.getAsString())));
            if(record.has("shulkers"))record.getAsJsonArray("shulkers").forEach(v->{String id=kitTypeId(v.getAsJsonObject());if(!id.isEmpty())types.add(id);});
            for(JsonElement value:picks){JsonObject pick=value.getAsJsonObject();if(key.equals(pick.get("x")+","+pick.get("y")+","+pick.get("z"))&&!TaskWire.text(pick,"kitTypeId").isEmpty())types.add(KitTypes.canonical(db,TaskWire.text(pick,"kitTypeId")));}
            // Legacy unknown records keep conservative all-type searches until a real observation replaces them.
            boolean legacyUnknown=TaskWire.text(record,"reason").equals("Contents changed by a confirmed stash withdrawal; rescan required")&&!record.has("withdrawnKitTypes");
            if(!types.isEmpty()&&!legacyUnknown){JsonArray known=new JsonArray();types.forEach(known::add);record.add("withdrawnKitTypes",known);}
            String[] xyz=key.split(",");record.addProperty("x",Integer.parseInt(xyz[0]));record.addProperty("y",Integer.parseInt(xyz[1]));record.addProperty("z",Integer.parseInt(xyz[2]));record.addProperty("status","unscanned");record.addProperty("reason","Contents changed by a confirmed stash withdrawal; rescan required");record.add("items",new JsonObject());record.add("shulkers",new JsonArray());record.remove("inferred");record.remove("slotCount");record.remove("occupiedSlots");record.addProperty("observedAt",System.currentTimeMillis());containers.add(key,record);}
        return touched;
    }
    /** Capture the stock baseline before an authenticated worker starts mutating storage. */
    private static String actionScope(String scope,JsonObject action){
        if(!action.has("dimension"))return scope;
        String dimension=TaskWire.text(action,"dimension");
        if(dimension.length()>128||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Invalid stash action dimension");
        return scope.substring(0,scope.indexOf('\n')+1)+dimension;
    }
    public static synchronized void beginTransfer(Path root,String crew,String scope,JsonObject run,JsonObject message){
        if(!message.has("action"))return;
        JsonObject action=message.getAsJsonObject("action");
        scope=actionScope(scope,action);
        if(TaskWire.text(action,"type").equals("StashScan")&&message.has("stashScan")){
            JsonObject scan=message.getAsJsonObject("stashScan");
            if(TaskWire.text(scan,"phase").equals("Complete")&&TaskWire.text(action,"scanMode").equals("Full")&&!TaskWire.flag(action,"lazyMode")&&!action.has("scanCells")
                &&scan.get("unscanned").getAsInt()==0&&scan.get("missingChunks").getAsInt()==0){
                String name=TaskWire.text(action,"name");JsonObject db=get(root,crew,scope,name);
                if(!db.isEmpty()&&StashStock.reconcile(db))StashStore.record(file(root,crew,scope,name),db,Set.of());
            }
            return;
        }
        if(!Set.of("StashDeposit","StashResupply").contains(TaskWire.text(action,"type")))return;
        String catalogCrew=TaskWire.text(action,"catalogCrew");if(catalogCrew.isEmpty())catalogCrew=crew;
        if(!catalogCrew.equals(crew)&&!catalogCrew.equals("Local"))throw new IllegalArgumentException("Transfer belongs to another crew");
        String name=TaskWire.text(action,"name"),binding=catalogCrew+"\n"+scope+"\n"+name;
        if(binding.equals(TaskWire.text(run,"stockBaseline")))return;
        JsonObject db=get(root,catalogCrew,scope,name);
        if(!db.isEmpty()&&StashStock.begin(db))StashStore.record(file(root,catalogCrew,scope,name),db,Set.of());
        if(!db.isEmpty())run.addProperty("stockBaseline",binding);
    }

    /** Cumulative receipts advance by verified entries, not merely by action token. */
    public static synchronized void withdrawalReceipt(Path root,String crew,String scope,JsonObject run,JsonObject message){
        if(!message.has("stashWithdrawal"))return;
        String execution=TaskWire.text(run,"id"),token=TaskWire.text(message,"token");UUID.fromString(execution);UUID.fromString(token);
        if(!execution.equals(TaskWire.text(message,"run")))throw new IllegalArgumentException("Withdrawal belongs to another execution");
        JsonObject receipt=message.getAsJsonObject("stashWithdrawal"),action=message.getAsJsonObject("action");
        String name=TaskWire.text(receipt,"stash"),catalogCrew=TaskWire.text(receipt,"catalogCrew");if(catalogCrew.isEmpty())catalogCrew=crew;
        if(action==null||!TaskWire.text(action,"type").equals("StashResupply")||!name.equals(TaskWire.text(action,"name"))
            ||!catalogCrew.equals(crew)&&!catalogCrew.equals("Local"))throw new IllegalArgumentException("Withdrawal receipt does not match its action");
        scope=actionScope(scope,action);
        JsonArray picks=receipt.getAsJsonArray("withdrawn");if(picks==null||picks.isEmpty()||picks.size()>54)throw new IllegalArgumentException("Invalid stash withdrawal receipt");
        for(JsonElement value:picks){JsonObject p=value.getAsJsonObject();if(!contains(action,integer(p,"x",-29_900_000,29_900_000),integer(p,"y",-2048,2048),integer(p,"z",-29_900_000,29_900_000)))throw new IllegalArgumentException("Withdrawal is outside the assigned stash");if(p.has("items"))counts(p.getAsJsonObject("items"));}
        if(token.equals(TaskWire.text(run,"stashWithdrawalToken"))&&run.has("stashWithdrawalCount")&&picks.size()==run.get("stashWithdrawalCount").getAsInt()){
            if(!TaskFiles.hash(picks.toString()).equals(TaskWire.text(run,"stashWithdrawalHash")))throw new IllegalArgumentException("Confirmed withdrawal receipt changed during retry");
            return;
        }
        JsonObject db=get(root,catalogCrew,scope,name);if(db.isEmpty())throw new IllegalArgumentException("Unknown stash");
        JsonObject receipts=db.has("withdrawalReceipts")?db.getAsJsonObject("withdrawalReceipts"):new JsonObject();
        JsonObject prior=receipts.has(execution)?receipts.getAsJsonObject(execution):new JsonObject();
        int before=token.equals(TaskWire.text(prior,"token"))?integer(prior,"count",0,54):0;String hash=before>0?TaskWire.text(prior,"hash"):"";
        if(token.equals(TaskWire.text(run,"stashWithdrawalToken"))&&run.has("stashWithdrawalCount")&&integer(run,"stashWithdrawalCount",0,54)>before){before=run.get("stashWithdrawalCount").getAsInt();hash=TaskWire.text(run,"stashWithdrawalHash");}
        if(picks.size()<before)return;
        JsonArray prefix=new JsonArray();for(int i=0;i<before;i++)prefix.add(picks.get(i));
        if(before>0&&!TaskFiles.hash(prefix.toString()).equals(hash))throw new IllegalArgumentException("Confirmed withdrawal receipt changed during retry");
        if(picks.size()>before){
            JsonArray added=new JsonArray();for(int i=before;i<picks.size();i++)added.add(picks.get(i));
            StashStock.change(db,added,-1);
            Set<String> changed=invalidateWithdrawn(db,added);
            JsonObject checkpoint=new JsonObject();checkpoint.addProperty("token",token);checkpoint.addProperty("count",picks.size());checkpoint.addProperty("hash",TaskFiles.hash(picks.toString()));receipts.add(execution,checkpoint);
            // shortcut: retain 1024 recent executions; older executions retain their prefix checkpoint in the host task journal.
            while(receipts.size()>1024)receipts.remove(receipts.keySet().iterator().next());db.add("withdrawalReceipts",receipts);db.addProperty("updatedAt",System.currentTimeMillis());
            StashStore.record(file(root,catalogCrew,scope,name),db,changed);
        }
        run.addProperty("stashWithdrawalToken",token);run.addProperty("stashWithdrawalCount",picks.size());run.addProperty("stashWithdrawalHash",TaskFiles.hash(picks.toString()));
    }
    public static synchronized JsonObject invalidateDeposited(Path root,String crew,String scope,String name,JsonArray touched){
        if(touched==null||touched.isEmpty()||touched.size()>36)throw new IllegalArgumentException("Invalid stash deposit receipt");
        JsonObject db=get(root,crew,scope,name);if(db.isEmpty())throw new IllegalArgumentException("Unknown destination stash");
        JsonObject plan=plan(db.getAsJsonObject("bounds"));plan.addProperty("workerCount",1);plan.addProperty("workerIndex",0);
        for(JsonElement element:touched){JsonObject cell=element.getAsJsonObject();if(!contains(plan,integer(cell,"x",-29_900_000,29_900_000),integer(cell,"y",-2048,2048),integer(cell,"z",-29_900_000,29_900_000)))throw new IllegalArgumentException("Deposit receipt is outside the destination stash");}
        JsonObject result=null;
        for(JsonElement element:touched){JsonObject cell=element.getAsJsonObject(),o=new JsonObject();int x=cell.get("x").getAsInt(),y=cell.get("y").getAsInt(),z=cell.get("z").getAsInt();
            o.addProperty("x",x);o.addProperty("y",y);o.addProperty("z",z);o.addProperty("status","unscanned");o.addProperty("block","minecraft:chest");o.addProperty("reason","A kit deposit was attempted here; contents are unverified until rescanned");o.add("items",new JsonObject());o.add("shulkers",new JsonArray());result=save(root,crew,scope,plan,o);
        }
        return result;
    }
    public static JsonArray depositBoxes(JsonObject action,JsonArray boxes,int confirmed,boolean complete){
        if(confirmed<0||confirmed>36||boxes==null||boxes.size()>confirmed||complete&&boxes.size()!=confirmed)throw new IllegalArgumentException("Deposit evidence does not match confirmed count");
        for(JsonElement value:boxes){
            JsonObject box=value.getAsJsonObject(),o=new JsonObject();for(String axis:List.of("x","y","z"))o.add(axis,box.get(axis));
            o.addProperty("status","observed");o.addProperty("block","minecraft:chest");o.add("items",new JsonObject());JsonArray single=new JsonArray();single.add(box);o.add("shulkers",single);
            observation(action,o);integer(box,"quantity",1,1);
        }
        return boxes.deepCopy();
    }
    public static void depositReceipt(Path root,String crew,String scope,JsonObject run,JsonObject message){
        if(!message.has("stashDeposit"))return;
        JsonObject receipt=message.getAsJsonObject("stashDeposit"),action=message.getAsJsonObject("action");
        if(action!=null)scope=actionScope(scope,action);
        if(action==null||!TaskWire.text(action,"type").equals("StashDeposit")||!TaskWire.text(receipt,"stash").equals(TaskWire.text(action,"name")))throw new IllegalArgumentException("Deposit receipt does not match its action");
        String catalogCrew=TaskWire.text(receipt,"catalogCrew");if(catalogCrew.isEmpty())catalogCrew=crew;if(!catalogCrew.equals(crew)&&!catalogCrew.equals("Local"))throw new IllegalArgumentException("Deposit belongs to another crew");
        JsonArray touched=receipt.getAsJsonArray("touched");if(touched==null||touched.isEmpty()||touched.size()>36)throw new IllegalArgumentException("Invalid stash deposit receipt");
        for(JsonElement value:touched){JsonObject p=value.getAsJsonObject();if(!contains(action,integer(p,"x",-29_900_000,29_900_000),integer(p,"y",-2048,2048),integer(p,"z",-29_900_000,29_900_000)))throw new IllegalArgumentException("Deposit receipt is outside the destination stash");}
        if(receipt.has("depositedBoxes")){
            int confirmed=integer(receipt,"deposited",0,36);JsonArray boxes=receipt.getAsJsonArray("depositedBoxes");
            if(!receipt.has("receiptComplete")||!receipt.get("receiptComplete").isJsonPrimitive()||!receipt.getAsJsonPrimitive("receiptComplete").isBoolean()
                ||boxes.size()>confirmed||TaskWire.flag(receipt,"receiptComplete")&&boxes.size()!=confirmed)throw new IllegalArgumentException("Deposit evidence does not match confirmed count");
            boxes=depositBoxes(action,boxes,confirmed,TaskWire.flag(receipt,"receiptComplete"));
            JsonObject previous=run.has("stashDepositEvidence")?run.getAsJsonObject("stashDepositEvidence"):new JsonObject();
            if(TaskWire.text(previous,"token").equals(TaskWire.text(message,"token"))){
                JsonArray old=previous.getAsJsonArray("boxes");if(confirmed<previous.get("confirmed").getAsInt())return;
                if(boxes.size()<old.size())throw new IllegalArgumentException("Confirmed deposit evidence shrank");
                for(int i=0;i<old.size();i++)if(!old.get(i).equals(boxes.get(i)))throw new IllegalArgumentException("Confirmed deposit evidence changed during retry");
                if(TaskWire.flag(previous,"complete")&&!TaskWire.flag(receipt,"receiptComplete"))throw new IllegalArgumentException("Complete deposit evidence became incomplete");
                if(confirmed==previous.get("confirmed").getAsInt()&&old.equals(boxes)&&TaskWire.flag(previous,"complete")==TaskWire.flag(receipt,"receiptComplete"))return;
            }
            JsonObject db=get(root,catalogCrew,scope,TaskWire.text(receipt,"stash"));if(db.isEmpty())throw new IllegalArgumentException("Unknown destination stash");
            StashStock.begin(db);JsonObject checkpoints=db.getAsJsonObject("transferStock").getAsJsonObject("depositReceipts");
            String execution=TaskWire.text(run,"id");if(execution.isEmpty())execution=TaskWire.text(message,"token");
            JsonObject checkpoint=checkpoints.has(execution)?checkpoints.getAsJsonObject(execution):new JsonObject();
            int before=TaskWire.text(checkpoint,"token").equals(TaskWire.text(message,"token"))?checkpoint.get("count").getAsInt():0;
            if(boxes.size()<before)return;
            JsonArray prefix=new JsonArray();for(int i=0;i<before;i++)prefix.add(boxes.get(i));
            if(before>0&&!TaskFiles.hash(prefix.toString()).equals(TaskWire.text(checkpoint,"hash")))throw new IllegalArgumentException("Confirmed deposit receipt changed during retry");
            if(boxes.size()>before){
                JsonArray added=new JsonArray();for(int i=before;i<boxes.size();i++)added.add(boxes.get(i));
                JsonObject observed=new JsonObject();observed.addProperty("status","observed");observed.add("shulkers",added.deepCopy());KitTypes.learn(db,observed,action);
                StashStock.change(db,observed.getAsJsonArray("shulkers"),1);
                JsonObject next=new JsonObject();next.addProperty("token",TaskWire.text(message,"token"));next.addProperty("count",boxes.size());next.addProperty("hash",TaskFiles.hash(boxes.toString()));checkpoints.add(execution,next);
                // shortcut: match withdrawal checkpoint retention; older retries require the retained execution checkpoint.
                while(checkpoints.size()>1024)checkpoints.remove(checkpoints.keySet().iterator().next());
                db.addProperty("updatedAt",System.currentTimeMillis());StashStore.record(file(root,catalogCrew,scope,TaskWire.text(receipt,"stash")),db,Set.of());
            }
            JsonObject evidence=new JsonObject();evidence.addProperty("token",TaskWire.text(message,"token"));evidence.addProperty("confirmed",confirmed);evidence.addProperty("complete",TaskWire.flag(receipt,"receiptComplete"));evidence.add("boxes",boxes.deepCopy());run.add("stashDepositEvidence",evidence);
        }
        if(TaskWire.text(run,"stashDepositToken").equals(TaskWire.text(message,"token")))return;
        // Reserved deposits send actual observations before/after each verified batch. A late status must not erase them.
        if(!TaskWire.text(run,"depositToken").equals(TaskWire.text(message,"token")))invalidateDeposited(root,catalogCrew,scope,TaskWire.text(receipt,"stash"),touched);
        run.addProperty("stashDepositToken",TaskWire.text(message,"token"));
    }
    public static JsonObject accept(Path root,JsonObject task,JsonObject run,String worker,String crew,JsonObject message) {
        return accept(root,task,run,worker,crew,message,false);
    }
    /** Scan-only group commit; full upload windows flush immediately. */
    public static boolean scanAckBatchReady(int count,long ageNanos){return count>=8||count>0&&ageNanos>=100_000_000L;}
    /** Only skip repeat checkpoint work after accept has validated this assignment. */
    public static boolean scanFindingRecorded(JsonObject run,JsonObject message) {
        return message.has("action")&&TaskWire.text(message.getAsJsonObject("action"),"type").equals("StashScan")
            &&TaskWire.text(run,"stashToken").equals(TaskWire.text(message,"token"))
            &&run.has("stashDelivery")&&integer(message,"delivery",1,MAX_CONTAINERS)<=integer(run,"stashDelivery",0,MAX_CONTAINERS);
    }
    public static JsonObject accept(Path root,JsonObject task,JsonObject run,String worker,String crew,JsonObject message,boolean categories) {
        if(!TaskWire.text(task,"crew").equals(crew)||!task.getAsJsonObject("runs").has(worker)||!TaskWire.text(run,"id").equals(TaskWire.text(message,"run"))
            ||!TaskWire.text(task,"id").equals(TaskWire.text(message,"task")))throw new IllegalArgumentException("Stash observation belongs to another assignment");
        if(!TaskWire.text(run,"token").equals(TaskWire.text(message,"token"))||!Objects.equals(run.get("action"),message.get("action")))return null;
        JsonObject p=plan(message.getAsJsonObject("action"));
        if(TaskWire.text(p,"type").equals("StashDeposit"))return task.has("stashSort")?StashSortExecution.depositFinding(root,task,run,message):acceptDeposit(root,task,run,crew,message,p,categories);
        if(!TaskWire.text(p,"type").equals("StashScan")||p.get("workerIndex").getAsInt()!=List.copyOf(task.getAsJsonObject("runs").keySet()).indexOf(worker)
            ||p.get("workerCount").getAsInt()!=task.getAsJsonObject("runs").size())throw new IllegalArgumentException("Wrong stash worker partition");
        JsonObject o=observation(p,message.getAsJsonObject("observation"));int delivery=integer(message,"delivery",1,MAX_CONTAINERS);
        int received=TaskWire.text(run,"stashToken").equals(TaskWire.text(message,"token"))&&run.has("stashDelivery")?integer(run,"stashDelivery",0,MAX_CONTAINERS):0;
        if(delivery>received){
            if(delivery!=received+1)throw new IllegalArgumentException("Stash observation delivery has a gap");
            JsonObject saved=storeObservation(root,crew,actionScope(TaskWire.text(task,"server")+"\n"+TaskWire.text(task,"dimension"),p),p,o);
            run.add("stashRecord",watchRecord(saved.getAsJsonObject("containers").getAsJsonObject(containerKey(o))));
            run.addProperty("stashToken",TaskWire.text(message,"token"));run.addProperty("stashDelivery",delivery);
        }
        JsonObject ack=TaskWire.message("stash-ack");ack.addProperty("run",TaskWire.text(run,"id"));ack.addProperty("token",TaskWire.text(message,"token"));ack.addProperty("delivery",delivery);
        if(delivery==received||delivery==received+1)if(run.has("stashRecord"))ack.add("record",run.get("stashRecord").deepCopy());
        return ack;
    }
    private static synchronized JsonObject acceptDeposit(Path root,JsonObject task,JsonObject run,String crew,JsonObject message,JsonObject p,boolean categories){
        String scope=actionScope(TaskWire.text(task,"server")+"\n"+TaskWire.text(task,"dimension"),p),name=TaskWire.text(p,"name"),kit=TaskWire.text(p,"kitTypeId"),catalogCrew=TaskWire.text(p,"catalogCrew");
        UUID.fromString(kit);if(catalogCrew.isEmpty())catalogCrew=crew;if(!catalogCrew.equals(crew)&&!catalogCrew.equals("Local"))throw new IllegalArgumentException("Destination belongs to another crew");
        JsonObject db=get(root,catalogCrew,scope,name);if(db.isEmpty())throw new IllegalArgumentException("Unknown destination stash");
        for(String axis:List.of("X","Y","Z"))for(String edge:List.of("min","max"))if(!p.get(edge+axis).equals(db.getAsJsonObject("bounds").get(edge+axis)))throw new IllegalArgumentException("Destination bounds changed; restart this transfer");
        Set<String> allocation=allocatedColumns(p);
        String binding=catalogCrew+"\n"+scope+"\n"+name+"\n"+kit+(allocation.isEmpty()?"":"\n"+TaskFiles.hash(p.get("allocatedColumns").toString()));
        if(!binding.equals(TaskWire.text(run,"depositBinding"))){run.addProperty("depositBinding",binding);run.remove("depositColumn");run.add("depositExcluded",new JsonArray());run.add("depositFullExcluded",new JsonArray());}
        int delivery=integer(message,"delivery",1,4096),received=TaskWire.text(run,"depositToken").equals(TaskWire.text(message,"token"))&&run.has("depositDelivery")?integer(run,"depositDelivery",0,4096):0;
        JsonObject intent=new JsonObject();for(String key:List.of("observation","exhaustedColumn","unavailableColumn"))if(message.has(key))intent.add(key,message.get(key).deepCopy());String hash=TaskFiles.hash(intent.toString());
        if(delivery<=received){if(delivery<received)return null;if(!hash.equals(TaskWire.text(run,"depositIntent")))throw new IllegalArgumentException("Deposit finding changed during retry");return run.getAsJsonObject("depositAck").deepCopy();}
        if(delivery!=received+1)throw new IllegalArgumentException("Deposit observation delivery has a gap");
        if(KitTypes.bind(db,p))StashStore.write(file(root,catalogCrew,scope,name),db);
        Set<String> excluded=new HashSet<>();for(JsonElement value:run.getAsJsonArray("depositExcluded"))excluded.add(value.getAsString());
        Set<String> fullExcluded=new HashSet<>();if(run.has("depositFullExcluded"))for(JsonElement value:run.getAsJsonArray("depositFullExcluded"))fullExcluded.add(value.getAsString());
        for(String key:fullExcluded)if(!db.has("fullColumns")||!db.getAsJsonObject("fullColumns").has(key))excluded.remove(key);
        fullExcluded.retainAll(excluded);
        Set<String> occupied=new HashSet<>();
        if(task.has("package")&&TaskWire.text(task.getAsJsonObject("package"),"entry").equals(StashTransferTeam.WORKFLOW))for(var e:task.getAsJsonObject("runs").entrySet()){
            JsonObject other=e.getValue().getAsJsonObject();
            // Keep an offline worker's lease until its action is reconciled, not until a wall-clock expiry.
            if(other!=run&&other.has("depositColumn")&&other.has("action")&&TaskWire.text(other.getAsJsonObject("action"),"type").equals("StashDeposit")&&TaskWire.text(other,"depositBinding").equals(binding))occupied.add(columnKey(other.getAsJsonObject("depositColumn")));
        }
        for(String key:List.of("exhaustedColumn","unavailableColumn"))if(message.has(key)&&(!message.get(key).isJsonPrimitive()||!message.getAsJsonPrimitive(key).isBoolean()))throw new IllegalArgumentException("Expected boolean "+key);
        boolean unavailable=TaskWire.flag(message,"unavailableColumn");
        if(unavailable&&(message.has("observation")||TaskWire.flag(message,"exhaustedColumn")))throw new IllegalArgumentException("Unreadable columns cannot also claim observed contents or fullness");
        JsonObject column=run.has("depositColumn")?checkedColumn(p,run.getAsJsonObject("depositColumn")):null;boolean permit=false;
        JsonObject limit=p.has("targetColumn")?checkedColumn(p,p.getAsJsonObject("targetColumn")):null;
        if(limit!=null&&column!=null&&!limit.equals(column))throw new IllegalArgumentException("Deposit reservation left the selected destination column");
        if(column!=null&&occupied.contains(columnKey(column)))throw new IllegalStateException("Destination column is leased to another worker; no deposit permitted");
        JsonObject reservation=column!=null&&db.has("columnReservations")?db.getAsJsonObject("columnReservations").getAsJsonObject(columnKey(column)):null;
        Set<String> allowed=new LinkedHashSet<>();for(String id:columnKitTypes(p))allowed.add(KitTypes.canonical(db,id));kit=KitTypes.canonical(db,kit);
        boolean owned=reservationMatches(reservation,allowed);
        JsonObject category=categories&&reservation!=null&&reservation.has("categoryId")?KitCategories.category(root,catalogCrew,scope,TaskWire.text(reservation,"categoryId")):null;
        JsonObject incoming=db.getAsJsonObject("kitTypes").getAsJsonObject(kit);
        if(category!=null)owned=!TaskWire.flag(reservation,"blocked")&&incoming!=null&&KitCategories.matches(category,kit,incoming.getAsJsonObject("items"));
        if(StashStock.begin(db))StashStore.record(file(root,catalogCrew,scope,name),db,Set.of());
        if(message.has("observation")){
            JsonObject o=observation(p,message.getAsJsonObject("observation"));
            if(column==null||!columnContains(column,o.get("x").getAsInt(),o.get("y").getAsInt(),o.get("z").getAsInt())||!TaskWire.text(o,"status").equals("observed")||TaskWire.flag(o,"inferred"))throw new IllegalArgumentException("Deposit observation must confirm its reserved column");
            storeObservation(root,catalogCrew,scope,db.getAsJsonObject("bounds"),o,"","",p);db=get(root,catalogCrew,scope,name);
            o=db.getAsJsonObject("containers").getAsJsonObject(containerKey(o));String occupant=columnOccupant(o);boolean compatible=category!=null?categoryContentsMatch(o,category):columnContentsMatch(o,allowed);permit=owned&&compatible;
            if(!compatible){
                JsonObject reservations=db.has("columnReservations")?db.getAsJsonObject("columnReservations"):new JsonObject(),changed=reservation!=null&&TaskWire.flag(reservation,"manual")?reservation.deepCopy():new JsonObject();
                if(!TaskWire.flag(changed,"manual"))changed.addProperty("kitTypeId",occupant.equals("mixed")?kit:occupant);
                changed.addProperty("blocked",TaskWire.flag(changed,"manual")||occupant.equals("mixed")||o.get("y").getAsInt()!=column.get("y").getAsInt());changed.addProperty("reason","Worker observed different or mixed contents before depositing");changed.addProperty("observedAt",System.currentTimeMillis());
                reservations.add(columnKey(column),changed);db.add("columnReservations",reservations);StashStore.write(file(root,catalogCrew,scope,name),db);excluded.add(columnKey(column));column=null;
            }
        }
        if(unavailable){
            if(column==null)throw new IllegalArgumentException("No destination column to defer");
            if(limit==null){excluded.add(columnKey(column));column=null;permit=false;}
        }
        if(TaskWire.flag(message,"exhaustedColumn")){
            if(column==null||message.has("observation"))throw new IllegalArgumentException("Invalid exhausted destination column");
            if(owned){
                JsonObject full=db.has("fullColumns")?db.getAsJsonObject("fullColumns"):new JsonObject(),marked=column.deepCopy();
                marked.addProperty("observedAt",System.currentTimeMillis());full.add(columnKey(column),marked);db.add("fullColumns",full);
                db.addProperty("updatedAt",System.currentTimeMillis());StashStore.write(file(root,catalogCrew,scope,name),db);fullExcluded.add(columnKey(column));
            }
            excluded.add(columnKey(column));column=null;
        }
        if(column!=null&&!owned){excluded.add(columnKey(column));column=null;permit=false;}
        if(column!=null&&!message.has("observation")&&!TaskWire.flag(message,"exhaustedColumn")
            &&(db.has("fullColumns")&&db.getAsJsonObject("fullColumns").has(columnKey(column))||columnObservedFull(db,column))){
            excluded.add(columnKey(column));fullExcluded.add(columnKey(column));column=null;permit=false;
        }
        boolean columnFull=!unavailable&&limit!=null&&(owned&&TaskWire.flag(message,"exhaustedColumn")||!message.has("observation")
            &&(db.has("fullColumns")&&db.getAsJsonObject("fullColumns").has(columnKey(limit))||columnObservedFull(db,limit)));
        if(columnFull)column=limit;
        else if(column==null){Set<String> excludedColumns=new HashSet<>(excluded);excludedColumns.addAll(occupied);
            if(!allocation.isEmpty())for(JsonElement value:db.getAsJsonObject("containers").asMap().values()){String key=columnKey(value.getAsJsonObject());if(!allocation.contains(key))excludedColumns.add(key);}
            column=reserveColumn(root,catalogCrew,scope,name,kit,excludedColumns,limit,allowed,categories);}
        JsonObject ack=TaskWire.message("stash-ack");ack.addProperty("run",TaskWire.text(run,"id"));ack.addProperty("token",TaskWire.text(message,"token"));ack.addProperty("delivery",delivery);ack.addProperty("deposit",true);ack.addProperty("permit",permit);
        if(column==null){run.remove("depositColumn");ack.addProperty("error",TaskWire.flag(db.getAsJsonObject("bounds"),"assumeEmpty")?"No mapped compatible column remains; load destination chunks with live storage updates or scan more columns":"No observed free or matching-kit column remains; scan more destination columns or free space");if(limit==null)ack.addProperty("capacityUnavailable",true);}else{run.add("depositColumn",column);ack.add("column",column.deepCopy());}
        if(column!=null&&categories){JsonObject fresh=get(root,catalogCrew,scope,name),r=fresh.getAsJsonObject("columnReservations").getAsJsonObject(columnKey(column));if(r!=null&&r.has("categoryId"))ack.add("columnCategory",KitCategories.placementRule(KitCategories.category(root,catalogCrew,scope,TaskWire.text(r,"categoryId"))));}
        if(columnFull){ack.addProperty("columnFull",true);ack.addProperty("permit",false);}
        if(unavailable&&limit!=null){ack.addProperty("error","Selected destination column has unreadable chests; remaining kits retained");ack.addProperty("permit",false);}
        JsonArray exhausted=new JsonArray();excluded.stream().sorted().forEach(exhausted::add);run.add("depositExcluded",exhausted);
        JsonArray full=new JsonArray();fullExcluded.stream().sorted().forEach(full::add);run.add("depositFullExcluded",full);
        run.addProperty("depositToken",TaskWire.text(message,"token"));run.addProperty("depositDelivery",delivery);run.addProperty("depositIntent",hash);run.add("depositAck",ack.deepCopy());return ack;
    }
    public static void telemetry(JsonObject run,JsonObject input) {
        TaskFiles.jsonBytes(input,5000);JsonObject t=input.deepCopy();
        for(String key:List.of("name","phase","reason","target","movementTarget","lastAction"))if(TaskWire.text(t,key).length()>512)throw new IllegalArgumentException("Stash telemetry detail too long");
        for(String key:List.of("discovery","volume","discovered","observed","unscanned","missingChunks","attempts"))integer(t,key,0,1_048_576);
        if(t.has("removed"))integer(t,"removed",0,MAX_CONTAINERS);
        t.addProperty("runtimeStatus",TaskWire.text(run,"status"));t.addProperty("runtimeDetail",TaskWire.text(run,"detail"));
        String signature=TaskWire.text(t,"phase")+TaskWire.text(t,"reason")+TaskWire.text(t,"target")+t.get("observed")+t.get("unscanned")+TaskWire.text(t,"runtimeStatus")+TaskWire.text(t,"runtimeDetail");
        t.addProperty("receivedAt",System.currentTimeMillis());
        if(!signature.equals(TaskWire.text(run,"stashSignature"))){
            JsonArray history=run.has("stashEvents")?run.getAsJsonArray("stashEvents"):new JsonArray();history.add(t.deepCopy());while(history.size()>64)history.remove(0);
            run.add("stashEvents",history);run.addProperty("stashSignature",signature);
        }
        run.add("stashScan",t);
    }
}
