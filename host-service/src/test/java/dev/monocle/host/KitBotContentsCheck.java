package dev.monocle.host;

import com.google.gson.*;
import dev.monocle.coordinator.*;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.*;

final class KitBotContentsCheck {
    private static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    private static void rejects(Runnable action){try{action.run();throw new AssertionError("Invalid inspection accepted");}catch(IllegalArgumentException expected){}}
    static void run(){
        String a="00000000-0000-0000-0000-000000000101",b="00000000-0000-0000-0000-000000000102",c="00000000-0000-0000-0000-000000000103",privateId="00000000-0000-0000-0000-000000000104";
        JsonObject stock=new JsonObject(),categories=new JsonObject(),requests=new JsonObject();
        stock.add(a,json("{name:'Synthetic stone A',complete:300,homeName:'PRIVATE_HOME_CANARY',x:246810,items:{'minecraft:stone':64,'minecraft:glass':32,'minecraft:diamond_sword':1}}"));
        stock.add(b,json("{name:'Synthetic stone B',complete:600,items:{'minecraft:stone':128,'minecraft:glass':16,'minecraft:diamond_pickaxe':1}}"));
        stock.add(c,json("{name:'Synthetic rare stone',complete:108,items:{'minecraft:stone':256,'minecraft:glass':64}}"));
        stock.add(privateId,json("{name:'PRIVATE_STORAGE_CANARY',complete:54321,items:{'minecraft:stone':1728}}"));
        for(var e:stock.entrySet())e.getValue().getAsJsonObject().addProperty("id",e.getKey());
        JsonObject rule=KitCategories.definition("build","Synthetic build","Kits",List.of(a,b,c)),storage=KitCategories.definition("private-storage","Storage only","Kits",List.of(privateId));storage.addProperty("usage","Storage");categories.add(text(rule,"id"),rule);categories.add(text(storage,"id"),storage);
        JsonObject result=KitBotContents.inspect(json("{category:'build',items:['minecraft:stone']}"),categories,stock,requests,270);JsonArray rows=result.getAsJsonArray("kits");
        assert rows.size()==2&&text(rows.get(0).getAsJsonObject(),"kit").equals(KitBotContents.reference(b));
        assert result.getAsJsonObject("matchingItemsPerKitRange").equals(json("{min:64,max:128}"))&&result.getAsJsonObject("blocksPerKitRange").equals(json("{min:96,max:144}"));
        assert rows.get(1).getAsJsonObject().getAsJsonObject("contentsPerKit").get("minecraft:glass").getAsInt()==32;
        for(String secret:List.of(a,b,c,privateId,"PRIVATE_","246810","54321","\"complete\":","homeName","estimatedComplete"))assert !result.toString().contains(secret):secret;
        JsonObject minimum=KitBotContents.inspect(json("{minimums:{'minecraft:stone':64,'minecraft:glass':20}}"),categories,stock,requests,270);assert minimum.getAsJsonArray("kits").size()==1&&text(minimum.getAsJsonArray("kits").get(0).getAsJsonObject(),"kit").equals(KitBotContents.reference(a));
        assert text(KitBot.select(rule,stock,requests,270,1),"id").equals(b)&&text(KitBot.select(rule,stock,requests,270,1,KitBotContents.reference(a)),"id").equals(a):"An exact contents choice overrides the ordinary most-stocked variant";
        assert KitBot.select(rule,stock,requests,270,1,KitBotContents.reference(c))==null&&KitBot.select(rule,stock,requests,270,1,KitBotContents.reference(privateId))==null:"Exact references cannot bypass availability or category membership";
        JsonObject unavailable=KitBotContents.inspect(json("{category:'build',includeUnavailable:true}"),categories,stock,requests,270);assert unavailable.getAsJsonArray("kits").size()==3&&!flag(unavailable.getAsJsonArray("kits").get(0).getAsJsonObject(),"inStock");
        JsonObject exact=json("{includeUnavailable:true}");exact.addProperty("kit",KitBotContents.reference(c));assert KitBotContents.inspect(exact,categories,stock,requests,270).getAsJsonArray("kits").size()==1;
        JsonObject reserved=json("{status:'Queued',count:331}");reserved.addProperty("kitTypeId",b);requests.add("synthetic-pending",reserved);assert KitBotContents.inspect(json("{}"),categories,stock,requests,270).getAsJsonArray("kits").size()==1;assert KitBotContents.inspect(json("{}"),categories,stock,requests,109).getAsJsonArray("kits").size()==2;
        assert KitBotContents.matches("minecraft:stone_bricks","stone")&&!KitBotContents.matches("minecraft:redstone","stone")&&!KitBotContents.matches("minecraft:stone_bricks","minecraft:stone");
        assert KitBotContents.matches("minecraft:oak_planks","wood")&&KitBotContents.matches("minecraft:obsidian","blocks")&&!KitBotContents.matches("minecraft:diamond_sword","blocks")&&!KitBotContents.matches("minecraft:experience_bottle","blocks");
        for(String invalid:List.of("{source:'private'}","{recipient:'Other'}","{kit:'internal-uuid'}","{items:[1]}","{minimums:{stone:1}}","{minimums:{'minecraft:stone':0}}","{minimums:{'minecraft:stone':'2'}}","{minimums:{'minecraft:stone':1.5}}","{page:0}","{page:129}","{includeUnavailable:'true'}"))rejects(()->KitBotContents.checked(json(invalid)));
        assert Arrays.equals(KitBot.parse("SyntheticUser whispers: kit build 2 "+KitBotContents.reference(a)),new String[]{"SyntheticUser","build","2",KitBotContents.reference(a)});
        Set<String> seen=new HashSet<>();JsonArray members=rule.getAsJsonArray("members");
        for(int i=5;i<14;i++){String id="00000000-0000-0000-0000-%012d".formatted(i);JsonObject kit=stock.getAsJsonObject(a).deepCopy();kit.addProperty("id",id);stock.add(id,kit);members.add(id);}
        JsonObject before=stock.deepCopy();
        for(int page=1;page<=3;page++){JsonObject q=json("{}");q.addProperty("page",page);JsonObject r=KitBotContents.inspect(q,categories,stock,new JsonObject(),270);for(JsonElement value:r.getAsJsonArray("kits"))assert seen.add(text(value.getAsJsonObject(),"kit"));assert r.getAsJsonObject("blocksPerKitRange").equals(json("{min:96,max:144}")):"Ranges cover all results, not only a page";}
        assert seen.size()==11&&stock.equals(before):"Paged inspection is exhaustive and read-only";
        System.out.println("Public kit-content checks passed: per-kit ranges, exact references, filters/ranking, pagination, stock reservations and private-data projection.");
    }
}
