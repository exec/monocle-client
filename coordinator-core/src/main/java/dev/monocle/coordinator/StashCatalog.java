package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Shared observations, column ownership and wire validation for both hosts. Mapped stock is not a delivery guarantee. */
public final class StashCatalog {
    public static final int MAX_OBSERVATION_BYTES = 512_000;
    private StashCatalog() {}
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
        String home=TaskWire.text(p,"homeName").strip();
        if (!home.matches("[A-Za-z0-9_-]{1,48}")) throw new IllegalArgumentException("Give the stash its required /home name using letters, numbers, underscores or dashes");
        p.addProperty("homeName",home);
        if (!p.has("homeWarmupTicks")) p.addProperty("homeWarmupTicks",300);
        if (!p.has("homeCooldownTicks")) p.addProperty("homeCooldownTicks",12_000);
        integer(p,"homeWarmupTicks",0,72_000);integer(p,"homeCooldownTicks",0,1_728_000);
        integer(p, "workerCount", 1, 16); integer(p, "workerIndex", 0, p.get("workerCount").getAsInt() - 1);
        return p;
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
        if (!Set.of("observed","unscanned").contains(TaskWire.text(o,"status"))) throw new IllegalArgumentException("Invalid container observation status");
        if(o.has("inferred")&&(!o.get("inferred").isJsonPrimitive()||!o.getAsJsonPrimitive("inferred").isBoolean()))throw new IllegalArgumentException("Invalid inferred marker");
        counts(o.getAsJsonObject("items"));
        if (TaskWire.text(o,"reason").length()>512 || TaskWire.text(o,"block").length()>128) throw new IllegalArgumentException("Container detail is too long");
        if (!o.has("shulkers")) o.add("shulkers",new JsonArray());
        JsonArray boxes=o.getAsJsonArray("shulkers");
        if(boxes.size()>216)throw new IllegalArgumentException("Too many container shulkers");
        for(JsonElement value:boxes) {
            JsonObject b=value.getAsJsonObject();counts(b.getAsJsonObject("items"));
            if(TaskWire.text(b,"item").length()>128 || TaskWire.text(b,"name").length()>128 || TaskWire.text(b,"dominant").length()>128)throw new IllegalArgumentException("Shulker detail is too long");
            integer(b,"slot",0,215);integer(b,"quantity",1,99);
        }
        if(TaskWire.text(o,"status").equals("unscanned") && (!o.getAsJsonObject("items").isEmpty() || !boxes.isEmpty()))throw new IllegalArgumentException("Unscanned containers cannot report contents");
        return o;
    }
    private static Path file(Path root,String crew,String scope,String name) {
        return root.resolve("stashes").resolve(TaskFiles.hash(crew+"\n"+scope+"\n"+name)+".json");
    }
    public static JsonObject save(Path root,String crew,String scope,JsonObject assignment,JsonObject input) {
        JsonObject p=plan(assignment),o=observation(p,input);
        if(scope.isBlank()||scope.length()>1200)throw new IllegalArgumentException("Invalid stash world scope");
        Path path=file(root,crew,scope,TaskWire.text(p,"name"));
        JsonObject db=TaskFiles.read(path);
        if(db.isEmpty()) {db.addProperty("version",1);db.addProperty("name",TaskWire.text(p,"name"));db.addProperty("crew",crew);db.addProperty("scope",scope);db.add("containers",new JsonObject());}
        ensureKitTypes(db);
        JsonObject records=db.getAsJsonObject("containers");String key=o.get("x")+","+o.get("y")+","+o.get("z");
        if(!records.has(key)&&records.size()>=4096)throw new IllegalArgumentException("Limit one stash to 4096 containers");
        o.addProperty("observedAt",System.currentTimeMillis());records.add(key,o);learnKits(db,o);db.add("bounds",p);db.addProperty("updatedAt",System.currentTimeMillis());
        // ponytail: one atomic JSON rewrite per observation, move to SQLite if large-stash write throughput matters.
        TaskFiles.write(path,db);return summary(db);
    }
    public static JsonObject define(Path root,String crew,String scope,JsonObject assignment) {
        JsonObject p=plan(assignment);Path path=file(root,crew,scope,TaskWire.text(p,"name"));JsonObject db=TaskFiles.read(path);
        if(db.isEmpty()){db.addProperty("version",1);db.addProperty("name",TaskWire.text(p,"name"));db.addProperty("crew",crew);db.addProperty("scope",scope);db.add("containers",new JsonObject());}
        db.add("bounds",p);db.addProperty("updatedAt",System.currentTimeMillis());TaskFiles.write(path,db);return summary(db);
    }
    public static JsonObject revise(Path root,String crew,String scope,String name,String expected,JsonObject changes) {
        JsonObject db=get(root,crew,scope,name);
        if(db.isEmpty())throw new IllegalArgumentException("Unknown stash");
        if(changes.isEmpty())throw new IllegalArgumentException("Specify a stash setting to change");
        if(!TaskWire.text(summary(db),"revision").equals(expected))throw new IllegalStateException("Stash revision changed; refresh before editing");
        JsonObject bounds=db.getAsJsonObject("bounds").deepCopy();
        for(var change:changes.entrySet()) {
            if(!Set.of("minX","maxX","minY","maxY","minZ","maxZ","homeName","homeWarmupTicks","homeCooldownTicks","lazyMode","scanMode").contains(change.getKey()))
                throw new IllegalArgumentException("Unsupported stash setting: "+change.getKey());
            bounds.add(change.getKey(),change.getValue().deepCopy());
        }
        JsonObject validated=plan(bounds);
        for(String axis:List.of("X","Y","Z"))for(String edge:List.of("min","max"))
            if(!Objects.equals(db.getAsJsonObject("bounds").get(edge+axis),validated.get(edge+axis)) && !db.getAsJsonObject("containers").isEmpty())
                throw new IllegalStateException("Cannot change observed stash bounds; create a new stash to preserve its inventory history");
        if(db.has("homes") && !db.getAsJsonObject("homes").isEmpty()
            && List.of("homeName","homeWarmupTicks","homeCooldownTicks").stream().anyMatch(changes::has))
            throw new IllegalStateException("This stash has worker-specific /home settings; update them in the worker before changing the default");
        return define(root,crew,scope,validated);
    }
    public static JsonObject summary(JsonObject db) {
        ensureKitTypes(db);
        JsonObject s=new JsonObject(),totals=new JsonObject(),columns=new JsonObject(),kitCounts=new JsonObject();int observed=0,unscanned=0,inferred=0;
        for(String key:List.of("version","name","crew","scope","bounds","homes","updatedAt"))if(db.has(key))s.add(key,db.get(key).deepCopy());
        JsonObject kitTypes=db.has("kitTypes")?db.getAsJsonObject("kitTypes"):new JsonObject();
        int bottom=db.has("bounds")?db.getAsJsonObject("bounds").get("minY").getAsInt():Integer.MIN_VALUE;
        for(var value:db.getAsJsonObject("containers").entrySet()) {
            JsonObject o=value.getValue().getAsJsonObject();
            if(!TaskWire.text(o,"status").equals("observed")){unscanned++;continue;}
            observed++;
            if(o.has("inferred")&&o.get("inferred").getAsBoolean())inferred++;
            o.getAsJsonObject("items").entrySet().forEach(i->totals.addProperty(i.getKey(),(totals.has(i.getKey())?totals.get(i.getKey()).getAsLong():0)+i.getValue().getAsLong()));
            if(o.get("y").getAsInt()==bottom&&!o.has("inferred")){
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
        s.remove("containers");s.add("items",totals);s.add("columns",columns);s.addProperty("mappedColumns",columns.size());s.add("kitTypes",kitTypes.deepCopy());s.add("kitCounts",kitCounts);s.addProperty("observed",observed-inferred);s.addProperty("inferred",inferred);s.addProperty("unscanned",unscanned);
        s.addProperty("revision",TaskFiles.hash(db.toString()));return s;
    }
    public static JsonArray list(Path root) {
        JsonArray result=new JsonArray();Path dir=root.resolve("stashes");if(!Files.exists(dir))return result;
        try(var paths=Files.list(dir)) {paths.filter(p->p.getFileName().toString().endsWith(".json")).limit(256).forEach(p->{JsonObject db=TaskFiles.read(p);if(!db.isEmpty())result.add(summary(db));});}
        catch(IOException e){throw new IllegalStateException("Could not read stash catalog",e);}return result;
    }
    /** Keep the authenticated 16 KiB crew frame for catalog sync small; detailed catalog stays on the host. */
    public static JsonObject wireSummary(JsonObject summary){
        JsonObject compact=summary.deepCopy();compact.remove("columns");
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
    public static JsonObject define(Path root,String crew,String scope,JsonObject assignment,String worker) {
        JsonObject summary=define(root,crew,scope,assignment),db=get(root,crew,scope,TaskWire.text(assignment,"name"));
        JsonObject homes=db.has("homes")?db.getAsJsonObject("homes"):new JsonObject(),route=new JsonObject();
        JsonObject plan=plan(assignment);route.addProperty("name",TaskWire.text(plan,"homeName"));route.add("warmupTicks",plan.get("homeWarmupTicks"));route.add("cooldownTicks",plan.get("homeCooldownTicks"));homes.add(UUID.fromString(worker).toString(),route);db.add("homes",homes);db.addProperty("updatedAt",System.currentTimeMillis());TaskFiles.write(file(root,crew,scope,TaskWire.text(plan,"name")),db);return summary(db);
    }
    public static JsonObject route(Path root,String crew,String scope,JsonObject assignment,String worker) {
        JsonObject result=plan(assignment),db=get(root,crew,scope,TaskWire.text(result,"name"));
        if(db.has("homes")&&db.getAsJsonObject("homes").has(worker)){
            JsonObject home=db.getAsJsonObject("homes").getAsJsonObject(worker);result.addProperty("homeName",TaskWire.text(home,"name"));result.add("homeWarmupTicks",home.get("warmupTicks").deepCopy());result.add("homeCooldownTicks",home.get("cooldownTicks").deepCopy());
        }
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
        JsonObject cache=new JsonObject();cache.addProperty("version",1);cache.addProperty("updatedAt",System.currentTimeMillis());cache.add("stashes",checked);TaskFiles.write(root.resolve("stash-host-catalog.json"),cache);
    }
    public static JsonArray remote(Path root){JsonObject cache=TaskFiles.read(root.resolve("stash-host-catalog.json"));return cache.has("stashes")?cache.getAsJsonArray("stashes"):new JsonArray();}
    public static JsonObject get(Path root,String crew,String scope,String name) {JsonObject db=TaskFiles.read(file(root,crew,scope,name));ensureKitTypes(db);return db;}
    public static String kitTypeId(JsonObject box){
        if(!box.has("contentsKnown")||!box.get("contentsKnown").getAsBoolean())return "";
        String item=TaskWire.text(box,"item"),name=TaskWire.text(box,"name");
        if(!item.endsWith("shulker_box"))return "";
        return UUID.nameUUIDFromBytes(("monocle-kit-v1\n"+item+"\n"+name).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static long fullness(JsonObject items){long count=0;for(var entry:items.entrySet())count+=entry.getValue().getAsLong();return count;}
    public static boolean kitIncomplete(JsonObject exemplar,JsonObject candidate){
        for(var entry:exemplar.entrySet())if(!candidate.has(entry.getKey())||candidate.get(entry.getKey()).getAsLong()<entry.getValue().getAsLong())return true;
        return false;
    }
    private static void learnKits(JsonObject db,JsonObject observation){
        if(!TaskWire.text(observation,"status").equals("observed")||observation.has("inferred")&&observation.get("inferred").getAsBoolean()||!observation.has("shulkers"))return;
        JsonObject types=db.has("kitTypes")?db.getAsJsonObject("kitTypes"):new JsonObject();
        for(JsonElement element:observation.getAsJsonArray("shulkers")){
            JsonObject box=element.getAsJsonObject();String id=kitTypeId(box);if(id.isEmpty())continue;
            JsonObject items=box.getAsJsonObject("items"),old=types.has(id)?types.getAsJsonObject(id):null;
            if(old!=null&&fullness(old.getAsJsonObject("items"))>=fullness(items))continue;
            JsonObject type=new JsonObject();type.addProperty("id",id);type.addProperty("item",TaskWire.text(box,"item"));type.addProperty("name",TaskWire.text(box,"name"));type.add("items",items.deepCopy());type.addProperty("learnedAt",System.currentTimeMillis());types.add(id,type);
        }
        db.add("kitTypes",types);
    }
    private static void ensureKitTypes(JsonObject db){
        if(db.isEmpty()||db.has("kitTypes")||!db.has("containers"))return;
        for(var value:db.getAsJsonObject("containers").entrySet())learnKits(db,value.getValue().getAsJsonObject());
        if(!db.has("kitTypes"))db.add("kitTypes",new JsonObject());
    }
    /** Select observed boxes first, then search unobserved chests in matching mapped columns at execution time. */
    public static JsonArray kitPicks(JsonObject db,String typeId,int count,boolean incomplete){
        return kitPicks(db,typeId,count,incomplete,false,false);
    }
    private static JsonArray kitPicks(JsonObject db,String typeId,int count,boolean incomplete,boolean includeIncomplete,boolean all){
        UUID.fromString(typeId);
        if(count<1||count>54||db==null||!db.has("kitTypes")||!db.getAsJsonObject("kitTypes").has(typeId))throw new IllegalArgumentException("Unknown kit type or invalid count");
        JsonObject exemplar=db.getAsJsonObject("kitTypes").getAsJsonObject(typeId).getAsJsonObject("items");
        if(all&&(!TaskWire.text(db.getAsJsonObject("bounds"),"scanMode").equals("Full")||TaskWire.flag(db.getAsJsonObject("bounds"),"lazyMode")))throw new IllegalStateException("Transfer all needs a full, non-lazy source scan");
        JsonArray picks=new JsonArray();Set<String> columns=new LinkedHashSet<>();
        for(var entry:db.getAsJsonObject("containers").entrySet()){
            JsonObject observed=entry.getValue().getAsJsonObject();
            if(!TaskWire.text(observed,"status").equals("observed")||TaskWire.flag(observed,"inferred")){
                // Our own withdrawals invalidate quantities, not the known container's location.
                if(all&&TaskWire.text(observed,"reason").equals("Contents changed by a confirmed stash withdrawal; rescan required")&&!TaskWire.flag(observed,"inferred")){
                    if(picks.size()==54)throw new IllegalStateException("Transfer all supports at most 54 matching source containers; split this stash selection");
                    picks.add(kitPick(observed,typeId,incomplete,TaskWire.text(db.getAsJsonObject("kitTypes").getAsJsonObject(typeId),"item")));continue;
                }
                if(all)throw new IllegalStateException("Transfer all needs a full, non-lazy source scan without unknown containers");
                continue;
            }
            for(JsonElement element:observed.getAsJsonArray("shulkers")){
                JsonObject box=element.getAsJsonObject();if(!typeId.equals(kitTypeId(box)))continue;
                if(db.has("bounds")&&observed.get("y").getAsInt()==db.getAsJsonObject("bounds").get("minY").getAsInt())columns.add(observed.get("x")+","+observed.get("z"));
                if(!includeIncomplete&&kitIncomplete(exemplar,box.getAsJsonObject("items"))!=incomplete)continue;
                JsonObject pick=kitPick(observed,typeId,incomplete,TaskWire.text(box,"item"));
                if(!picks.isEmpty()&&sameChest(picks.get(picks.size()-1).getAsJsonObject(),pick))continue;
                if(picks.size()==54){if(all)throw new IllegalStateException("Transfer all supports at most 54 matching source chests; split this stash selection");continue;}
                picks.add(pick);
            }
        }
        if(!all&&db.has("bounds"))for(String column:columns){String[] xz=column.split(",");int x=Integer.parseInt(xz[0]),z=Integer.parseInt(xz[1]);JsonObject bounds=db.getAsJsonObject("bounds");
            for(int y=bounds.get("minY").getAsInt()+1;y<=bounds.get("maxY").getAsInt()&&picks.size()<54;y++){
                String key=x+","+y+","+z;JsonObject known=db.getAsJsonObject("containers").has(key)?db.getAsJsonObject("containers").getAsJsonObject(key):null;
                if(known!=null&&TaskWire.text(known,"status").equals("observed")&&!known.has("inferred"))continue;
                JsonObject candidate=new JsonObject();candidate.addProperty("x",x);candidate.addProperty("y",y);candidate.addProperty("z",z);candidate.addProperty("slot",0);candidate.addProperty("dynamic",true);candidate.addProperty("kitTypeId",typeId);candidate.addProperty("resource",TaskWire.text(db.getAsJsonObject("kitTypes").getAsJsonObject(typeId),"item"));candidate.addProperty("incomplete",incomplete);picks.add(candidate);
            }
        }
        if(picks.isEmpty())throw new IllegalStateException("No observed or mapped source chest for this kit; scan or refresh the column before dispatch");
        return picks;
    }
    private static JsonObject kitPick(JsonObject observed,String typeId,boolean incomplete,String item){
        JsonObject pick=new JsonObject();pick.addProperty("slot",0);pick.addProperty("dynamic",true);
        for(String axis:List.of("x","y","z"))pick.add(axis,observed.get(axis).deepCopy());
        pick.addProperty("kitTypeId",typeId);pick.addProperty("resource",item);pick.addProperty("incomplete",incomplete);return pick;
    }
    private static boolean sameChest(JsonObject a,JsonObject b){return a.get("x").equals(b.get("x"))&&a.get("y").equals(b.get("y"))&&a.get("z").equals(b.get("z"));}
    /** Empty, one exact kit type, or mixed/unknown/loose items. Unknown stock is never free. */
    public static String columnOccupant(JsonObject observed){
        if(!TaskWire.text(observed,"status").equals("observed")||TaskWire.flag(observed,"inferred"))return "mixed";
        JsonObject items=observed.getAsJsonObject("items");JsonArray boxes=observed.getAsJsonArray("shulkers");
        if(items.isEmpty()&&boxes.isEmpty())return "";
        String type="";JsonObject totals=new JsonObject();
        for(JsonElement element:boxes){JsonObject box=element.getAsJsonObject();String id=kitTypeId(box);if(id.isEmpty()||!type.isEmpty()&&!type.equals(id))return "mixed";type=id;String item=TaskWire.text(box,"item");int quantity=box.get("quantity").getAsInt();totals.addProperty(item,(totals.has(item)?totals.get(item).getAsLong():0)+quantity);
            // CrewInventory.manifest includes modern shulker contents, but legacy NBT boxes count only the box itself.
            if(!TaskWire.flag(box,"legacy"))for(var content:box.getAsJsonObject("items").entrySet())totals.addProperty(content.getKey(),(totals.has(content.getKey())?totals.get(content.getKey()).getAsLong():0)+content.getValue().getAsLong()*quantity);
        }
        return type.isEmpty()||!totals.equals(items)?"mixed":type;
    }
    public static JsonObject checkedColumn(JsonObject plan,JsonObject input){
        int x=integer(input,"x",-29_900_000,29_900_000),y=integer(input,"y",-2048,2048),z=integer(input,"z",-29_900_000,29_900_000);
        if(y!=plan.get("minY").getAsInt()||!contains(plan,x,y,z)||!Set.of("X","Z","Vertical").contains(TaskWire.text(input,"axis")))throw new IllegalArgumentException("Invalid destination column");
        JsonObject column=new JsonObject();column.addProperty("x",x);column.addProperty("y",y);column.addProperty("z",z);column.addProperty("axis",TaskWire.text(input,"axis"));
        return column;
    }
    public static boolean columnContains(JsonObject column,int x,int y,int z){
        int dx=x-column.get("x").getAsInt(),dy=y-column.get("y").getAsInt(),dz=z-column.get("z").getAsInt();
        if(dy<0)return false;
        return switch(TaskWire.text(column,"axis")){case "X"->dx==0&&(dz==0||Math.abs(dz)==dy);case "Z"->dz==0&&(dx==0||Math.abs(dx)==dy);default->dx==0&&dz==0;};
    }
    private static String columnKey(JsonObject column){return column.get("x")+","+column.get("z");}
    private static String columnAxis(JsonObject db){
        Set<Integer> xs=new HashSet<>(),zs=new HashSet<>();int bottom=db.getAsJsonObject("bounds").get("minY").getAsInt();
        for(var entry:db.getAsJsonObject("containers").entrySet()){JsonObject o=entry.getValue().getAsJsonObject();if(o.get("y").getAsInt()==bottom){xs.add(o.get("x").getAsInt());zs.add(o.get("z").getAsInt());}}
        return zs.size()==1&&xs.size()>1?"X":xs.size()==1&&zs.size()>1?"Z":"Vertical";
    }
    /** Kit ownership survives jobs and restarts. Unobserved columns are never allocated. */
    public static JsonObject reserveColumn(Path root,String crew,String scope,String name,String kit,Set<String> excluded){
        UUID.fromString(kit);JsonObject db=get(root,crew,scope,name);if(db.isEmpty())throw new IllegalArgumentException("Unknown destination stash");
        JsonObject reservations=db.has("columnReservations")?db.getAsJsonObject("columnReservations"):new JsonObject();
        JsonObject selected=null;int best=3,bottom=db.getAsJsonObject("bounds").get("minY").getAsInt();
        for(var entry:db.getAsJsonObject("containers").entrySet()){
            JsonObject o=entry.getValue().getAsJsonObject();String key=columnKey(o);if(o.get("y").getAsInt()!=bottom||excluded.contains(key))continue;
            String occupant=columnOccupant(o);JsonObject reservation=reservations.has(key)?reservations.getAsJsonObject(key):null;
            if(reservation!=null&&(TaskWire.flag(reservation,"blocked")||!kit.equals(TaskWire.text(reservation,"kitTypeId"))))continue;
            if(!occupant.isEmpty()&&!occupant.equals(kit))continue;
            int rank=occupant.equals(kit)?0:reservation!=null?1:2;if(rank>=best)continue;
            selected=new JsonObject();for(String axis:List.of("x","y","z"))selected.add(axis,o.get(axis).deepCopy());selected.addProperty("axis",columnAxis(db));best=rank;
        }
        if(selected==null)return null;
        JsonObject reservation=new JsonObject();reservation.addProperty("kitTypeId",kit);reservation.addProperty("reservedAt",System.currentTimeMillis());reservations.add(columnKey(selected),reservation);db.add("columnReservations",reservations);
        db.addProperty("updatedAt",System.currentTimeMillis());TaskFiles.write(file(root,crew,scope,name),db);return selected;
    }
    public static JsonObject kitAction(Path root,String crew,String scope,JsonObject request,String worker){
        String sourceCrew=get(root,crew,scope,TaskWire.text(request,"name")).isEmpty()?"Local":crew;
        JsonObject routed=route(root,sourceCrew,scope,request,worker);String id=TaskWire.text(request,"kitTypeId");int count=integer(request,"count",1,36);
        for(String key:List.of("transferAll","includeIncomplete"))if(request.has(key)&&(!request.get(key).isJsonPrimitive()||!request.getAsJsonPrimitive(key).isBoolean()))throw new IllegalArgumentException("Expected boolean "+key);
        boolean all=TaskWire.flag(request,"transferAll"),includeIncomplete=TaskWire.flag(request,"includeIncomplete");
        if(includeIncomplete&&TaskWire.flag(request,"incomplete"))throw new IllegalArgumentException("Incomplete removal cannot also include complete kits");
        String destination=TaskWire.text(request,"destination");if(!Set.of("Player","Ender Chest","Stash","Carry").contains(destination))throw new IllegalArgumentException("Choose Player, Ender Chest, Stash, or Carry for a kit withdrawal");
        if(all&&!destination.equals("Stash"))throw new IllegalArgumentException("Transfer all requires a destination stash");
        JsonObject db=get(root,sourceCrew,scope,TaskWire.text(routed,"name"));JsonArray picks=kitPicks(db,id,count,TaskWire.flag(request,"incomplete"),includeIncomplete,all);
        routed.add("picks",picks);routed.addProperty("type","StashResupply");routed.addProperty("kitTypeId",id);routed.addProperty("count",count);routed.addProperty("kitItem",TaskWire.text(db.getAsJsonObject("kitTypes").getAsJsonObject(id),"item"));
        routed.addProperty("destination",destination);routed.addProperty("depositMode",destination.equals("Ender Chest")?"Ender Chest":"Carry");routed.addProperty("catalogCrew",sourceCrew);
        routed.addProperty("incomplete",request.has("incomplete")&&request.get("incomplete").getAsBoolean());routed.add("kitExemplar",db.getAsJsonObject("kitTypes").getAsJsonObject(id).get("items").deepCopy());
        if(destination.equals("Player")){
            routed.addProperty("recipient",UUID.fromString(TaskWire.text(request,"recipient")).toString());
            String name=TaskWire.text(request,"recipientName");if(!name.matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("Specify the recipient's exact Minecraft username");routed.addProperty("recipientName",name);
        }
        if(destination.equals("Stash")){
            String targetName=TaskWire.text(request,"targetStashName");if(targetName.equals(TaskWire.text(routed,"name")))throw new IllegalArgumentException("Choose a different destination stash");
            String targetCrew=get(root,crew,scope,targetName).isEmpty()?"Local":crew;
            JsonObject targetDb=get(root,targetCrew,scope,targetName);if(targetDb.isEmpty())throw new IllegalArgumentException("Define the destination stash in this world first");
            JsonObject target=route(root,targetCrew,scope,targetDb.getAsJsonObject("bounds"),worker);
            if(overlaps(routed,target))throw new IllegalArgumentException("Source and destination stash selections must not overlap");
            target.addProperty("catalogCrew",targetCrew);target.addProperty("kitTypeId",id);target.addProperty("count",count);target.add("kitExemplar",routed.get("kitExemplar").deepCopy());target.add("incomplete",routed.get("incomplete").deepCopy());target.addProperty("includeIncomplete",includeIncomplete);routed.add("targetStash",target);
        }
        int limit=all?24_000:7_500;
        while(!all&&routed.toString().length()>limit&&picks.size()>1)picks.remove(picks.size()-1);
        if(routed.toString().length()>limit)throw new IllegalStateException("Kit action exceeds the workflow data limit; split this stash selection or shorten its exemplar");
        return routed;
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
    public static JsonObject invalidateWithdrawn(Path root,String crew,String scope,String name,JsonArray picks){
        if(picks==null||picks.isEmpty()||picks.size()>54)throw new IllegalArgumentException("Invalid stash withdrawal receipt");
        JsonObject db=get(root,crew,scope,name);if(db.isEmpty())throw new IllegalArgumentException("Unknown stash");JsonObject containers=db.getAsJsonObject("containers");
        Set<String> touched=new HashSet<>();JsonObject bounds=plan(db.getAsJsonObject("bounds"));
        for(JsonElement element:picks){JsonObject pick=element.getAsJsonObject();int x=integer(pick,"x",-29_900_000,29_900_000),y=integer(pick,"y",-2048,2048),z=integer(pick,"z",-29_900_000,29_900_000);if(!contains(bounds,x,y,z))throw new IllegalArgumentException("Withdrawal source is outside the stash");touched.add(x+","+y+","+z);}
        for(String key:touched){JsonObject record=containers.has(key)?containers.getAsJsonObject(key):new JsonObject();String[] xyz=key.split(",");record.addProperty("x",Integer.parseInt(xyz[0]));record.addProperty("y",Integer.parseInt(xyz[1]));record.addProperty("z",Integer.parseInt(xyz[2]));record.addProperty("status","unscanned");record.addProperty("reason","Contents changed by a confirmed stash withdrawal; rescan required");record.add("items",new JsonObject());record.add("shulkers",new JsonArray());record.remove("inferred");record.addProperty("observedAt",System.currentTimeMillis());containers.add(key,record);}
        db.addProperty("updatedAt",System.currentTimeMillis());TaskFiles.write(file(root,crew,scope,name),db);return summary(db);
    }
    public static JsonObject invalidateDeposited(Path root,String crew,String scope,String name,JsonArray touched){
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
    public static void depositReceipt(Path root,String crew,String scope,JsonObject run,JsonObject message){
        if(!message.has("stashDeposit")||TaskWire.text(run,"stashDepositToken").equals(TaskWire.text(message,"token")))return;
        JsonObject receipt=message.getAsJsonObject("stashDeposit"),action=message.getAsJsonObject("action");
        if(action==null||!TaskWire.text(action,"type").equals("StashDeposit")||!TaskWire.text(receipt,"stash").equals(TaskWire.text(action,"name")))throw new IllegalArgumentException("Deposit receipt does not match its action");
        String catalogCrew=TaskWire.text(receipt,"catalogCrew");if(catalogCrew.isEmpty())catalogCrew=crew;if(!catalogCrew.equals(crew)&&!catalogCrew.equals("Local"))throw new IllegalArgumentException("Deposit belongs to another crew");
        JsonArray touched=receipt.getAsJsonArray("touched");if(touched==null||touched.isEmpty()||touched.size()>36)throw new IllegalArgumentException("Invalid stash deposit receipt");
        for(JsonElement value:touched){JsonObject p=value.getAsJsonObject();if(!contains(action,integer(p,"x",-29_900_000,29_900_000),integer(p,"y",-2048,2048),integer(p,"z",-29_900_000,29_900_000)))throw new IllegalArgumentException("Deposit receipt is outside the destination stash");}
        // Reserved deposits send actual observations before/after each verified batch. A late status must not erase them.
        if(!TaskWire.text(run,"depositToken").equals(TaskWire.text(message,"token")))invalidateDeposited(root,catalogCrew,scope,TaskWire.text(receipt,"stash"),touched);
        run.addProperty("stashDepositToken",TaskWire.text(message,"token"));
    }
    public static JsonObject accept(Path root,JsonObject task,JsonObject run,String worker,String crew,JsonObject message) {
        if(!TaskWire.text(task,"crew").equals(crew)||!task.getAsJsonObject("runs").has(worker)||!TaskWire.text(run,"id").equals(TaskWire.text(message,"run"))
            ||!TaskWire.text(task,"id").equals(TaskWire.text(message,"task")))throw new IllegalArgumentException("Stash observation belongs to another assignment");
        if(!TaskWire.text(run,"token").equals(TaskWire.text(message,"token"))||!Objects.equals(run.get("action"),message.get("action")))return null;
        JsonObject p=plan(message.getAsJsonObject("action"));
        if(TaskWire.text(p,"type").equals("StashDeposit"))return acceptDeposit(root,task,run,crew,message,p);
        if(!TaskWire.text(p,"type").equals("StashScan")||p.get("workerIndex").getAsInt()!=List.copyOf(task.getAsJsonObject("runs").keySet()).indexOf(worker)
            ||p.get("workerCount").getAsInt()!=task.getAsJsonObject("runs").size())throw new IllegalArgumentException("Wrong stash worker partition");
        JsonObject o=observation(p,message.getAsJsonObject("observation"));int delivery=integer(message,"delivery",1,4096);
        int received=TaskWire.text(run,"stashToken").equals(TaskWire.text(message,"token"))&&run.has("stashDelivery")?integer(run,"stashDelivery",0,4096):0;
        if(delivery>received){
            if(delivery!=received+1)throw new IllegalArgumentException("Stash observation delivery has a gap");
            save(root,crew,TaskWire.text(task,"server")+"\n"+TaskWire.text(task,"dimension"),p,o);
            run.addProperty("stashToken",TaskWire.text(message,"token"));run.addProperty("stashDelivery",delivery);
        }
        JsonObject ack=TaskWire.message("stash-ack");ack.addProperty("run",TaskWire.text(run,"id"));ack.addProperty("token",TaskWire.text(message,"token"));ack.addProperty("delivery",delivery);return ack;
    }
    private static JsonObject acceptDeposit(Path root,JsonObject task,JsonObject run,String crew,JsonObject message,JsonObject p){
        String scope=TaskWire.text(task,"server")+"\n"+TaskWire.text(task,"dimension"),name=TaskWire.text(p,"name"),kit=TaskWire.text(p,"kitTypeId"),catalogCrew=TaskWire.text(p,"catalogCrew");
        UUID.fromString(kit);if(catalogCrew.isEmpty())catalogCrew=crew;if(!catalogCrew.equals(crew)&&!catalogCrew.equals("Local"))throw new IllegalArgumentException("Destination belongs to another crew");
        JsonObject db=get(root,catalogCrew,scope,name);if(db.isEmpty())throw new IllegalArgumentException("Unknown destination stash");
        for(String axis:List.of("X","Y","Z"))for(String edge:List.of("min","max"))if(!p.get(edge+axis).equals(db.getAsJsonObject("bounds").get(edge+axis)))throw new IllegalArgumentException("Destination bounds changed; restart this transfer");
        String binding=catalogCrew+"\n"+scope+"\n"+name+"\n"+kit;
        if(!binding.equals(TaskWire.text(run,"depositBinding"))){run.addProperty("depositBinding",binding);run.remove("depositColumn");run.add("depositExcluded",new JsonArray());}
        int delivery=integer(message,"delivery",1,4096),received=TaskWire.text(run,"depositToken").equals(TaskWire.text(message,"token"))&&run.has("depositDelivery")?integer(run,"depositDelivery",0,4096):0;
        JsonObject intent=new JsonObject();for(String key:List.of("observation","exhaustedColumn"))if(message.has(key))intent.add(key,message.get(key).deepCopy());String hash=TaskFiles.hash(intent.toString());
        if(delivery<=received){if(delivery<received)return null;if(!hash.equals(TaskWire.text(run,"depositIntent")))throw new IllegalArgumentException("Deposit finding changed during retry");return run.getAsJsonObject("depositAck").deepCopy();}
        if(delivery!=received+1)throw new IllegalArgumentException("Deposit observation delivery has a gap");
        Set<String> excluded=new HashSet<>();for(JsonElement value:run.getAsJsonArray("depositExcluded"))excluded.add(value.getAsString());
        if(message.has("exhaustedColumn")&&(!message.get("exhaustedColumn").isJsonPrimitive()||!message.getAsJsonPrimitive("exhaustedColumn").isBoolean()))throw new IllegalArgumentException("Expected boolean exhaustedColumn");
        JsonObject column=run.has("depositColumn")?checkedColumn(p,run.getAsJsonObject("depositColumn")):null;boolean permit=false;
        JsonObject reservation=column!=null&&db.has("columnReservations")?db.getAsJsonObject("columnReservations").getAsJsonObject(columnKey(column)):null;
        boolean owned=reservation!=null&&!TaskWire.flag(reservation,"blocked")&&kit.equals(TaskWire.text(reservation,"kitTypeId"));
        if(message.has("observation")){
            JsonObject o=observation(p,message.getAsJsonObject("observation"));
            if(column==null||!columnContains(column,o.get("x").getAsInt(),o.get("y").getAsInt(),o.get("z").getAsInt())||!TaskWire.text(o,"status").equals("observed")||TaskWire.flag(o,"inferred"))throw new IllegalArgumentException("Deposit observation must confirm its reserved column");
            save(root,catalogCrew,scope,db.getAsJsonObject("bounds"),o);db=get(root,catalogCrew,scope,name);
            String occupant=columnOccupant(o);permit=owned&&(occupant.isEmpty()||occupant.equals(kit));
            if(!occupant.isEmpty()&&!occupant.equals(kit)){
                JsonObject reservations=db.has("columnReservations")?db.getAsJsonObject("columnReservations"):new JsonObject(),changed=new JsonObject();
                changed.addProperty("kitTypeId",occupant.equals("mixed")?kit:occupant);changed.addProperty("blocked",occupant.equals("mixed")||o.get("y").getAsInt()!=p.get("minY").getAsInt());changed.addProperty("reason","Worker observed different or mixed contents before depositing");changed.addProperty("observedAt",System.currentTimeMillis());
                reservations.add(columnKey(column),changed);db.add("columnReservations",reservations);TaskFiles.write(file(root,catalogCrew,scope,name),db);excluded.add(columnKey(column));column=null;
            }
        }
        if(TaskWire.flag(message,"exhaustedColumn")){if(column==null||message.has("observation"))throw new IllegalArgumentException("Invalid exhausted destination column");excluded.add(columnKey(column));column=null;}
        if(column!=null&&!owned){excluded.add(columnKey(column));column=null;permit=false;}
        if(column==null)column=reserveColumn(root,catalogCrew,scope,name,kit,excluded);
        JsonObject ack=TaskWire.message("stash-ack");ack.addProperty("run",TaskWire.text(run,"id"));ack.addProperty("token",TaskWire.text(message,"token"));ack.addProperty("delivery",delivery);ack.addProperty("deposit",true);ack.addProperty("permit",permit);
        if(column==null){run.remove("depositColumn");ack.addProperty("error","No observed free or matching-kit column remains; scan more destination columns or free space");}else{run.add("depositColumn",column);ack.add("column",column.deepCopy());}
        JsonArray exhausted=new JsonArray();excluded.stream().sorted().forEach(exhausted::add);run.add("depositExcluded",exhausted);
        run.addProperty("depositToken",TaskWire.text(message,"token"));run.addProperty("depositDelivery",delivery);run.addProperty("depositIntent",hash);run.add("depositAck",ack.deepCopy());return ack;
    }
    public static void telemetry(JsonObject run,JsonObject input) {
        TaskFiles.jsonBytes(input,5000);JsonObject t=input.deepCopy();
        for(String key:List.of("name","phase","reason","target","movementTarget","lastAction"))if(TaskWire.text(t,key).length()>512)throw new IllegalArgumentException("Stash telemetry detail too long");
        for(String key:List.of("discovery","volume","discovered","observed","unscanned","missingChunks","attempts"))integer(t,key,0,1_048_576);
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
