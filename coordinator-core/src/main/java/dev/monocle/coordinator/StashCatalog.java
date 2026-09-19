package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Shared observation store and wire validation for both kinds of host. Never treats observations as stock reservations. */
public final class StashCatalog {
    private StashCatalog() {}
    public static JsonObject plan(JsonObject source) {
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
        if (!p.has("workerCount")) p.addProperty("workerCount", 1);
        if (!p.has("workerIndex")) p.addProperty("workerIndex", 0);
        if (!p.has("lazyMode")) p.addProperty("lazyMode", true);
        if (!p.get("lazyMode").isJsonPrimitive() || !p.getAsJsonPrimitive("lazyMode").isBoolean()) throw new IllegalArgumentException("Invalid stash lazyMode");
        if (!p.has("homeName")) p.addProperty("homeName", "");
        String home=TaskWire.text(p,"homeName").strip();
        if (!home.isEmpty()&&!home.matches("[A-Za-z0-9_-]{1,48}")) throw new IllegalArgumentException("Home name may contain letters, numbers, underscores and dashes");
        p.addProperty("homeName",home);
        if (!p.has("homeWarmupTicks")) p.addProperty("homeWarmupTicks",300);
        if (!p.has("homeCooldownTicks")) p.addProperty("homeCooldownTicks",12_000);
        integer(p,"homeWarmupTicks",0,72_000);integer(p,"homeCooldownTicks",0,1_728_000);
        integer(p, "workerCount", 1, 16); integer(p, "workerIndex", 0, p.get("workerCount").getAsInt() - 1);
        return p;
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
        TaskFiles.jsonBytes(input, 32_000);
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
        JsonObject records=db.getAsJsonObject("containers");String key=o.get("x")+","+o.get("y")+","+o.get("z");
        if(!records.has(key)&&records.size()>=4096)throw new IllegalArgumentException("Limit one stash to 4096 containers");
        o.addProperty("observedAt",System.currentTimeMillis());records.add(key,o);db.add("bounds",p);db.addProperty("updatedAt",System.currentTimeMillis());
        // ponytail: one atomic JSON rewrite per observation, move to SQLite if large-stash write throughput matters.
        TaskFiles.write(path,db);return summary(db);
    }
    public static JsonObject define(Path root,String crew,String scope,JsonObject assignment) {
        JsonObject p=plan(assignment);Path path=file(root,crew,scope,TaskWire.text(p,"name"));JsonObject db=TaskFiles.read(path);
        if(db.isEmpty()){db.addProperty("version",1);db.addProperty("name",TaskWire.text(p,"name"));db.addProperty("crew",crew);db.addProperty("scope",scope);db.add("containers",new JsonObject());}
        db.add("bounds",p);db.addProperty("updatedAt",System.currentTimeMillis());TaskFiles.write(path,db);return summary(db);
    }
    public static JsonObject summary(JsonObject db) {
        JsonObject s=new JsonObject(),totals=new JsonObject();int observed=0,unscanned=0,inferred=0;
        for(String key:List.of("version","name","crew","scope","bounds","homes","updatedAt"))if(db.has(key))s.add(key,db.get(key).deepCopy());
        for(var value:db.getAsJsonObject("containers").entrySet()) {
            JsonObject o=value.getValue().getAsJsonObject();
            if(!TaskWire.text(o,"status").equals("observed")){unscanned++;continue;}
            observed++;
            if(o.has("inferred")&&o.get("inferred").getAsBoolean())inferred++;
            o.getAsJsonObject("items").entrySet().forEach(i->totals.addProperty(i.getKey(),(totals.has(i.getKey())?totals.get(i.getKey()).getAsLong():0)+i.getValue().getAsLong()));
        }
        s.remove("containers");s.add("items",totals);s.addProperty("observed",observed-inferred);s.addProperty("inferred",inferred);s.addProperty("unscanned",unscanned);return s;
    }
    public static JsonArray list(Path root) {
        JsonArray result=new JsonArray();Path dir=root.resolve("stashes");if(!Files.exists(dir))return result;
        try(var paths=Files.list(dir)) {paths.filter(p->p.getFileName().toString().endsWith(".json")).limit(256).forEach(p->{JsonObject db=TaskFiles.read(p);if(!db.isEmpty())result.add(summary(db));});}
        catch(IOException e){throw new IllegalStateException("Could not read stash catalog",e);}return result;
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
    public static JsonObject get(Path root,String crew,String scope,String name) {return TaskFiles.read(file(root,crew,scope,name));}
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
        Set<String> touched=new HashSet<>();
        for(JsonElement element:picks){JsonObject pick=element.getAsJsonObject();String key=integer(pick,"x",-29_900_000,29_900_000)+","+integer(pick,"y",-2048,2048)+","+integer(pick,"z",-29_900_000,29_900_000);if(!containers.has(key))throw new IllegalArgumentException("Withdrawal source is absent from the catalog");touched.add(key);}
        for(String key:touched){JsonObject record=containers.getAsJsonObject(key);record.addProperty("status","unscanned");record.addProperty("reason","Contents changed by a confirmed stash withdrawal; rescan required");record.add("items",new JsonObject());record.add("shulkers",new JsonArray());record.remove("inferred");record.addProperty("observedAt",System.currentTimeMillis());}
        db.addProperty("updatedAt",System.currentTimeMillis());TaskFiles.write(file(root,crew,scope,name),db);return summary(db);
    }
    public static JsonObject accept(Path root,JsonObject task,JsonObject run,String worker,String crew,JsonObject message) {
        if(!TaskWire.text(task,"crew").equals(crew)||!task.getAsJsonObject("runs").has(worker)||!TaskWire.text(run,"id").equals(TaskWire.text(message,"run"))
            ||!TaskWire.text(task,"id").equals(TaskWire.text(message,"task")))throw new IllegalArgumentException("Stash observation belongs to another assignment");
        if(!TaskWire.text(run,"token").equals(TaskWire.text(message,"token"))||!Objects.equals(run.get("action"),message.get("action")))return null;
        JsonObject p=plan(message.getAsJsonObject("action"));
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
