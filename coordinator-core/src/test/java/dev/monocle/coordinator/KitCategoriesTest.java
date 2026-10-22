package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public final class KitCategoriesTest {
    private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    public static JsonObject stockFixture(int quantity){
        JsonObject db=json("{kitTypes:{},containers:{},kitAliases:{}}"),box=json("{item:'minecraft:black_shulker_box',name:'Obby',contentsKnown:true,quantity:1,slot:0,items:{'minecraft:obsidian':1728}}");
        for(int i=0;i<quantity;i++){JsonObject o=json("{status:'observed',items:{'minecraft:black_shulker_box':1,'minecraft:obsidian':1728},shulkers:[]}");o.getAsJsonArray("shulkers").add(box.deepCopy());KitTypes.learn(db,o);db.getAsJsonObject("containers").add(String.valueOf(i),o);}return db;
    }
    public static void run() throws Exception {
        JsonObject variants=json("{kitTypes:{}}"),va=json("{item:'minecraft:black_shulker_box',name:'Same',contentsKnown:true,quantity:1,items:{'minecraft:obsidian':64,'minecraft:end_crystal':64}}"),vb=va.deepCopy();vb.add("items",json("{'minecraft:obsidian':64,'minecraft:netherite_sword':1}"));JsonObject observed=json("{status:'observed',shulkers:[]}");observed.getAsJsonArray("shulkers").add(va);observed.getAsJsonArray("shulkers").add(vb);for(JsonElement value:observed.getAsJsonArray("shulkers")){JsonObject type=value.getAsJsonObject().deepCopy();type.addProperty("familyId",KitTypes.family(type));variants.getAsJsonObject("kitTypes").add(KitTypes.id(type),type);}
        JsonObject identities=KitTypes.identities(variants.getAsJsonObject("kitTypes"));assert KitTypes.sortIdentity(vb,identities).equals(KitTypes.id(vb));JsonObject partial=va.deepCopy();partial.remove("kitTypeId");partial.add("items",json("{'minecraft:obsidian':1}"));assert KitTypes.sortIdentity(partial,identities).isEmpty():"Sorting must not relabel ambiguous same-name variants";
        JsonObject scoped=KitCategories.definition("storage-tools","Tools","Items",List.of("minecraft:netherite_pickaxe"));
        assert KitCategories.usedFor(scoped,"Storage")&&KitCategories.usedFor(scoped,"Kitbot");
        scoped.addProperty("usage","Storage");assert KitCategories.checked(scoped).equals(scoped);
        assert KitCategories.usedFor(scoped,"Storage")&&!KitCategories.usedFor(scoped,"Kitbot");
        JsonObject wire=KitCategories.placementRule(scoped);assert !wire.has("usage")&&wire.keySet().equals(Set.of("id","name","slug","mode","members"))&&scoped.has("usage"):"Old deposit workers must receive the original five-field rule";
        scoped.addProperty("usage","Kitbot");assert !KitCategories.usedFor(scoped,"Storage")&&KitCategories.usedFor(scoped,"Kitbot");
        scoped.addProperty("usage","Both");assert KitCategories.usedFor(scoped,"Storage")&&KitCategories.usedFor(scoped,"Kitbot");
        for(JsonElement invalid:List.of(new JsonPrimitive("other"),new JsonPrimitive(2),new JsonArray(),JsonNull.INSTANCE)){
            scoped.add("usage",invalid);try{KitCategories.checked(scoped);throw new AssertionError("Invalid usage accepted");}catch(IllegalArgumentException expected){}
        }
        Path root=Files.createTempDirectory("kit-categories-");JsonObject rule=KitCategories.definition("obsidian","Obsidian","Items",List.of("minecraft:obsidian")),db=stockFixture(250),stock=KitCategories.stock(db);String id=stock.keySet().iterator().next();
        assert stock.getAsJsonObject(id).get("complete").getAsInt()==250;
        assert KitCategories.matches(rule,id,json("{'minecraft:obsidian':1728}"));assert !KitCategories.matches(rule,id,json("{'minecraft:obsidian':64,'minecraft:end_crystal':64}"));assert !KitCategories.matches(rule,id,new JsonObject());
        assert KitCategories.stock(stockFixture(249)).getAsJsonObject(id).get("complete").getAsInt()==249;
        db.getAsJsonObject("containers").getAsJsonObject("0").addProperty("inferred",true);db.getAsJsonObject("containers").getAsJsonObject("1").addProperty("status","removed");
        assert KitCategories.stock(db).getAsJsonObject(id).get("complete").getAsInt()==248;
        JsonObject view=KitCategories.get(root,"Default","server\nminecraft:the_end");String rev=TaskWire.text(view,"revision");KitCategories.save(root,"Default","server\nminecraft:the_end",rev,rule);
        try{KitCategories.save(root,"Default","server\nminecraft:the_end",rev,rule);throw new AssertionError("Stale revision accepted");}catch(IllegalStateException expected){}
        JsonObject seeded=KitCategories.seed(root,"Other","server\nminecraft:the_end",stockFixture(250),250);assert seeded.getAsJsonObject("categories").size()==1;
        assert KitCategories.seed(root,"Other","server\nminecraft:the_end",stockFixture(249),250).equals(seeded):"Rescan never deletes operator categories";
        JsonObject manual=KitCategories.definition("test","Test","Kits",List.of(id));assert KitCategories.matches(manual,id,new JsonObject());assert !KitCategories.matches(manual,UUID.randomUUID().toString(),json("{'minecraft:obsidian':1728}"));
        assert StashCatalog.categoryContentsMatch(db.getAsJsonObject("containers").getAsJsonObject("2"),rule);
        JsonObject loose=db.getAsJsonObject("containers").getAsJsonObject("2").deepCopy();loose.getAsJsonObject("items").addProperty("minecraft:ender_pearl",1);assert !StashCatalog.categoryContentsMatch(loose,rule);
        rule.addProperty("usage","Storage");KitCategories.save(root,"Default","server\nminecraft:the_end",TaskWire.text(KitCategories.get(root,"Default","server\nminecraft:the_end"),"revision"),rule);
        assert TaskWire.text(KitCategories.get(root,"Default","server\nminecraft:the_end").getAsJsonObject("categories").getAsJsonObject(TaskWire.text(rule,"id")),"usage").equals("Storage");
        columns(root,rule);
        System.out.println("Kit category checks passed: complete-only stock, 249/250 boundary, exact contents and revision guards.");
    }
    private static void columns(Path root,JsonObject rule){
        String scope="server\nminecraft:the_end",worker=UUID.randomUUID().toString(),token=UUID.randomUUID().toString();
        JsonObject p=StashCatalog.plan(json("{name:'Columns',homeName:'main',minX:0,maxX:4,minY:100,maxY:102,minZ:0,maxZ:0}"));StashCatalog.define(root,"Default",scope,p);
        JsonObject o=stockFixture(1).getAsJsonObject("containers").getAsJsonObject("0").deepCopy();o.addProperty("x",0);o.addProperty("y",100);o.addProperty("z",0);o.addProperty("block","minecraft:chest");StashCatalog.save(root,"Default",scope,p,o);
        String kit=StashCatalog.kitTypeId(o.getAsJsonArray("shulkers").get(0).getAsJsonObject());JsonArray columns=new JsonArray();columns.add(json("{x:0,y:100,z:0,axis:'Vertical'}"));
        StashCatalog.setColumnReservations(root,"Default",scope,"Columns",TaskWire.text(StashCatalog.summary(StashCatalog.get(root,"Default",scope,"Columns")),"revision"),"",TaskWire.text(rule,"id"),columns);
        JsonObject action=p.deepCopy();action.addProperty("type","StashDeposit");action.addProperty("kitTypeId",kit);action.addProperty("count",1);JsonObject run=new JsonObject();run.addProperty("id",worker);run.addProperty("token",token);run.add("action",action);
        JsonObject task=json("{crew:'Default',server:'server',dimension:'minecraft:the_end',id:'columns-test',runs:{}}");task.getAsJsonObject("runs").add(worker,run);JsonObject m=json("{type:'stash-findings',task:'columns-test',delivery:1}");m.addProperty("run",worker);m.addProperty("token",token);m.add("action",action.deepCopy());
        assert StashCatalog.accept(root,task,run.deepCopy(),worker,"Default",m).has("error"):"Legacy workers cannot interpret category columns";
        JsonObject ack=StashCatalog.accept(root,task,run,worker,"Default",m,true);assert ack.has("columnCategory")&&!ack.get("permit").getAsBoolean();assert !ack.getAsJsonObject("columnCategory").has("usage"):"Old workers must accept scoped placement rules";
        m.addProperty("delivery",2);m.add("observation",o);ack=StashCatalog.accept(root,task,run,worker,"Default",m,true);assert ack.get("permit").getAsBoolean();assert StashCatalog.accept(root,task,run,worker,"Default",m,true).equals(ack);
        assert StashCatalog.get(root,"Default",scope,"Columns").getAsJsonObject("columnReservations").getAsJsonObject("0,0").has("categoryId"):"Allocation must preserve category ownership";
    }
}
