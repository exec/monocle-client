package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.Path;
import java.util.*;

/** Operator-owned home definitions and read-only provisioning proposals, never withdrawal authority. */
public final class WorkerSupplyStations {
    private WorkerSupplyStations() { }
    private static final Map<String,Integer> LOADOUT=Map.of(
        "minecraft:netherite_helmet",1,"minecraft:netherite_leggings",1,"minecraft:netherite_boots",1,
        "minecraft:elytra",1,"minecraft:totem_of_undying",1,
        "minecraft:enchanted_golden_apple",64,"minecraft:experience_bottle",64);
    private static Path file(Path root,String crew,String worker){
        UUID.fromString(worker);return root.resolve("worker-supply-stations").resolve(TaskFiles.hash(crew+"\n"+worker)+".json");
    }
    public static JsonObject loadout(){JsonObject r=new JsonObject();LOADOUT.forEach(r::addProperty);return r;}
    public static synchronized JsonObject get(Path root,String crew,String worker){
        JsonObject r=TaskFiles.read(file(root,crew,worker));
        if(!r.isEmpty())r=checked(r);
        r.addProperty("revision",TaskFiles.hash(r.toString()));return r;
    }
    public static synchronized JsonObject save(Path root,String crew,String worker,String expected,JsonObject definition){
        if(!TaskWire.text(get(root,crew,worker),"revision").equals(expected))throw new IllegalStateException("Supply station changed; refresh before editing");
        JsonObject r=checked(definition);TaskFiles.write(file(root,crew,worker),r);return get(root,crew,worker);
    }
    public static JsonObject checked(JsonObject input){
        Set<String> fields=Set.of("server","dimension","homeName","homeWarmupTicks","homeCooldownTicks","chest","bed","enderChest");
        if(input==null||!input.keySet().equals(fields))throw new IllegalArgumentException("A supply station needs server, Overworld dimension, home route, chest, bed and enderChest positions");
        JsonObject r=input.deepCopy();
        for(String key:List.of("server","dimension","homeName"))if(!r.get(key).isJsonPrimitive()||!r.getAsJsonPrimitive(key).isString())throw new IllegalArgumentException("Supply station "+key+" must be text");
        String server=TaskWire.text(r,"server"),home=TaskWire.text(r,"homeName");
        if(server.isBlank()||server.length()>255||server.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c)))throw new IllegalArgumentException("Invalid supply station server");
        if(!TaskWire.text(r,"dimension").equals("minecraft:overworld"))throw new IllegalArgumentException("Worker beds must be in the Overworld");
        if(!home.matches("[A-Za-z0-9_-]{1,32}"))throw new IllegalArgumentException("A supply station needs a single safe /home name");
        StashCatalog.integer(r,"homeWarmupTicks",0,72000);StashCatalog.integer(r,"homeCooldownTicks",0,1728000);
        Set<String> positions=new HashSet<>();
        for(String key:List.of("chest","bed","enderChest")){
            if(!r.get(key).isJsonObject())throw new IllegalArgumentException("Invalid "+key+" position");
            JsonObject p=r.getAsJsonObject(key);if(!p.keySet().equals(Set.of("x","y","z")))throw new IllegalArgumentException("Position needs x, y and z only");
            int x=StashCatalog.integer(p,"x",-29_900_000,29_900_000),y=StashCatalog.integer(p,"y",-64,319),z=StashCatalog.integer(p,"z",-29_900_000,29_900_000);
            if(!positions.add(x+","+y+","+z))throw new IllegalArgumentException("Supply fixtures must have distinct positions");
            if(key.equals("chest"))continue;
            JsonObject chest=r.getAsJsonObject("chest");
            if(Math.abs((long)x-chest.get("x").getAsInt())>16||Math.abs((long)y-chest.get("y").getAsInt())>8||Math.abs((long)z-chest.get("z").getAsInt())>16)throw new IllegalArgumentException("Keep supply fixtures within the small home area");
        }
        return r;
    }
    private record Candidate(String scope,String stash,String kit,JsonObject items,long available,boolean uncertain) { }
    /** Reuses canonical, complete-only stash stock; names and shulker colors never imply equipment. */
    public static JsonObject plan(Path root,String crew,JsonObject station,JsonArray sources,int sets){
        JsonObject definition=station.deepCopy();definition.remove("revision");checked(definition);
        if(sets<1||sets>16||sources==null||sources.isEmpty()||sources.size()>16)throw new IllegalArgumentException("Choose 1–16 spare loadouts and 1–16 source stashes");
        List<Candidate> choices=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(JsonElement value:sources){
            if(!value.isJsonObject()||!value.getAsJsonObject().keySet().equals(Set.of("scope","name")))throw new IllegalArgumentException("Each source needs scope and name only");
            JsonObject source=value.getAsJsonObject();String scope=TaskWire.text(source,"scope"),name=TaskWire.text(source,"name");
            if(!scope.startsWith(TaskWire.text(definition,"server")+"\n")||scope.indexOf('\n')!=scope.lastIndexOf('\n')||!scope.substring(scope.indexOf('\n')+1).matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Supply source must be on the station's server");
            if(!seen.add(scope+"\n"+name))throw new IllegalArgumentException("Duplicate supply source");
            JsonObject db=StashCatalog.get(root,crew,scope,name);if(db.isEmpty())throw new IllegalArgumentException("Unknown supply source stash: "+name);
            JsonObject summary=StashCatalog.summary(db);
            for(var entry:KitCategories.stock(db).entrySet()){
                JsonObject row=entry.getValue().getAsJsonObject();long available=row.has("estimatedComplete")?row.get("estimatedComplete").getAsLong():row.get("complete").getAsLong();
                if(available>0)choices.add(new Candidate(scope,name,entry.getKey(),row.getAsJsonObject("items"),available,TaskWire.flag(summary,"transferStockUncertain")));
            }
        }
        return proposal(definition,choices,sets);
    }
    private static double coverage(JsonObject items,Map<String,Long> missing){
        double result=0;for(var e:missing.entrySet())if(e.getValue()>0&&items.has(e.getKey()))result+=Math.min(e.getValue(),items.get(e.getKey()).getAsLong())/(double)e.getValue();return result;
    }
    private static JsonObject proposal(JsonObject station,List<Candidate> choices,int sets){
        Map<String,Long> missing=new TreeMap<>();LOADOUT.forEach((k,v)->missing.put(k,(long)v*sets));
        Map<Candidate,Integer> selected=new LinkedHashMap<>();
        // Bound one chest's provisioning proposal; a mixed PvP kit may cover several needs at once.
        for(int slot=0;slot<54;slot++){
            Candidate best=null;double score=0;
            for(Candidate c:choices){
                if(selected.getOrDefault(c,0)>=c.available)continue;
                double next=coverage(c.items,missing);
                if(next>score||next==score&&next>0&&best!=null&&c.items.size()<best.items.size()){best=c;score=next;}
            }
            if(best==null)break;
            selected.merge(best,1,Integer::sum);
            for(var e:missing.entrySet())if(best.items.has(e.getKey()))e.setValue(Math.max(0,e.getValue()-best.items.get(e.getKey()).getAsLong()));
        }
        JsonArray picks=new JsonArray();JsonObject contents=new JsonObject();boolean uncertain=false;
        for(var e:selected.entrySet()){
            Candidate c=e.getKey();JsonObject pick=new JsonObject();pick.addProperty("scope",c.scope);pick.addProperty("source",c.stash);pick.addProperty("kitTypeId",c.kit);pick.addProperty("count",e.getValue());pick.addProperty("stockUncertain",c.uncertain);picks.add(pick);uncertain|=c.uncertain;
            for(var item:c.items.entrySet())contents.addProperty(item.getKey(),(contents.has(item.getKey())?contents.get(item.getKey()).getAsLong():0)+item.getValue().getAsLong()*e.getValue());
        }
        JsonObject shortage=new JsonObject();missing.forEach((k,v)->{if(v>0)shortage.addProperty(k,v);});
        JsonObject r=new JsonObject();r.add("station",station.deepCopy());r.add("loadout",loadout());r.addProperty("spareLoadouts",sets);r.add("kits",picks);r.add("suppliedItems",contents);r.add("missingItems",shortage);r.addProperty("complete",shortage.isEmpty());r.addProperty("stockUncertain",uncertain);r.addProperty("executionSupported",false);
        r.addProperty("detail","Read-only stocking proposal; no items moved. Gear enchantments, durability, bed spawn and fixture blocks still require live verification.");return r;
    }
}
