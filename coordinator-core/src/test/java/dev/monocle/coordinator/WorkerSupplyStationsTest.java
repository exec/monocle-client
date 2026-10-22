package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public final class WorkerSupplyStationsTest {
    private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    private static void rejects(Runnable action){try{action.run();throw new AssertionError("Invalid station/plan accepted");}catch(IllegalArgumentException|IllegalStateException expected){}}
    private static JsonObject station(){return json("{server:'example.org',dimension:'minecraft:overworld',homeName:'bot-home',homeWarmupTicks:320,homeCooldownTicks:340,chest:{x:0,y:64,z:0},bed:{x:2,y:64,z:0},enderChest:{x:4,y:64,z:0}}");}
    public static void run() throws Exception {
        Path root=Files.createTempDirectory("worker-supplies-");String worker=UUID.randomUUID().toString(),crew="Default",scope="example.org\nminecraft:the_end";
        JsonObject empty=WorkerSupplyStations.get(root,crew,worker),definition=station();
        JsonObject saved=WorkerSupplyStations.save(root,crew,worker,TaskWire.text(empty,"revision"),definition);
        assert saved.equals(WorkerSupplyStations.get(root,crew,worker));
        assert WorkerSupplyStations.get(root,"Other",worker).size()==1:"Crew isolation";
        rejects(()->WorkerSupplyStations.save(root,crew,worker,TaskWire.text(empty,"revision"),definition));
        for(String key:List.of("homeName","dimension","homeCooldownTicks","chest")){
            JsonObject bad=station();bad.remove(key);rejects(()->WorkerSupplyStations.checked(bad));
        }
        JsonObject bad=station();bad.addProperty("dimension","minecraft:the_end");JsonObject end=bad;rejects(()->WorkerSupplyStations.checked(end));
        bad=station();bad.addProperty("homeName","bot\nkill");JsonObject unsafe=bad;rejects(()->WorkerSupplyStations.checked(unsafe));
        bad=station();bad.addProperty("autoRecoverDeath",true);JsonObject unimplemented=bad;rejects(()->WorkerSupplyStations.checked(unimplemented));
        JsonObject far=station();far.getAsJsonObject("bed").addProperty("x",17);rejects(()->WorkerSupplyStations.checked(far));
        JsonObject fractional=station();fractional.getAsJsonObject("bed").addProperty("x",1.5);rejects(()->WorkerSupplyStations.checked(fractional));
        JsonObject same=station();same.add("bed",same.get("chest").deepCopy());rejects(()->WorkerSupplyStations.checked(same));
        JsonObject bounds=StashCatalog.plan(json("{name:'source',homeName:'source',minX:0,maxX:0,minY:64,maxY:64,minZ:0,maxZ:0}"));
        StashCatalog.define(root,crew,scope,bounds);
        JsonObject o=json("{x:0,y:64,z:0,block:'minecraft:chest',capacity:54,status:'observed',items:{},shulkers:[]}");
        JsonObject gear=WorkerSupplyStations.loadout();gear.addProperty("minecraft:enchanted_golden_apple",128);gear.addProperty("minecraft:experience_bottle",192);
        JsonObject box=json("{slot:0,item:'minecraft:red_shulker_box',name:'misleading name',quantity:4,contentsKnown:true,legacy:false,items:{}}");box.add("items",gear);
        o.getAsJsonArray("shulkers").add(box);StashCatalog.save(root,crew,scope,bounds,o);
        JsonArray sources=new JsonArray();JsonObject source=new JsonObject();source.addProperty("scope",scope);source.addProperty("name","source");sources.add(source);
        JsonObject plan=WorkerSupplyStations.plan(root,crew,saved,sources,4);
        assert plan.get("complete").getAsBoolean();assert !plan.get("executionSupported").getAsBoolean();
        assert plan.getAsJsonArray("kits").size()==1;assert plan.getAsJsonArray("kits").get(0).getAsJsonObject().get("count").getAsInt()==4;
        assert plan.getAsJsonObject("suppliedItems").get("minecraft:enchanted_golden_apple").getAsInt()==512;
        assert !plan.getAsJsonObject("loadout").has("minecraft:netherite_chestplate");
        JsonObject shortage=WorkerSupplyStations.plan(root,crew,saved,sources,5);
        assert !shortage.get("complete").getAsBoolean();assert shortage.getAsJsonObject("missingItems").get("minecraft:elytra").getAsInt()==1;
        assert StashCatalog.get(root,crew,scope,"source").getAsJsonObject("containers").getAsJsonObject("0,64,0").getAsJsonArray("shulkers").get(0).getAsJsonObject().get("quantity").getAsInt()==4:"Proposal moved inventory";
        JsonArray duplicate=sources.deepCopy();duplicate.add(source);rejects(()->WorkerSupplyStations.plan(root,crew,saved,duplicate,4));
        JsonArray foreign=sources.deepCopy();foreign.get(0).getAsJsonObject().addProperty("scope","other.org\nminecraft:the_end");rejects(()->WorkerSupplyStations.plan(root,crew,saved,foreign,4));
        rejects(()->WorkerSupplyStations.plan(root,crew,saved,sources,17));
        System.out.println("Worker supply station checks passed: persistence, crew isolation, complete-kit proposals, shortages and no execution.");
    }
}
