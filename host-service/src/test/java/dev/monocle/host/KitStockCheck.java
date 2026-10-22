package dev.monocle.host;

import com.google.gson.*;
import dev.monocle.coordinator.KitTypes;
import java.util.UUID;

/** Reporting must not require two stashes to have identical learned kit contents. */
final class KitStockCheck {
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static JsonObject catalog(String name, String id, JsonObject type, int count) {
        JsonObject stash=json("{crew:'Default',scope:'server\\nminecraft:the_end',kitTypes:{},kitCounts:{},kitAliases:{},containers:{},kitIdentityVersion:2,observed:1,inferred:0,unscanned:3,updatedAt:1,bounds:{name:'test',minX:0,maxX:2000,minY:100,maxY:100,minZ:0,maxZ:0,homeName:'test'}}");
        stash.addProperty("name",name);stash.getAsJsonObject("kitTypes").add(id,type);
        stash.getAsJsonObject("kitCounts").add(id,json("{complete:"+count+",incomplete:2}"));
        append(stash,id,type,count,false);append(stash,id,type,2,true);
        for(int i=0;i<3;i++)stash.getAsJsonObject("containers").add("unknown-"+i,json("{x:1500,y:100,z:0,block:'minecraft:chest',status:'unscanned',items:{},shulkers:[]}"));
        return stash;
    }
    private static void append(JsonObject stash,String id,JsonObject type,int count,boolean partial) {
        for(int i=0;i<count;i++){
            int x=stash.getAsJsonObject("containers").size();JsonObject container=json("{y:100,z:0,status:'observed',block:'minecraft:chest',items:{},shulkers:[]}");container.addProperty("x",x);
            JsonObject box=type.deepCopy();box.addProperty("kitTypeId",id);box.addProperty("slot",0);box.addProperty("quantity",1);box.addProperty("contentsKnown",true);
            if(partial)box.getAsJsonObject("items").addProperty("minecraft:piston",1);
            container.getAsJsonArray("shulkers").add(box);stash.getAsJsonObject("containers").add(x+",100,0",container);
        }
    }
    static void run() {
        String sourceId=UUID.randomUUID().toString(),targetId=UUID.randomUUID().toString();
        JsonObject a=json("{item:'minecraft:green_shulker_box',name:'TSR Redstone Kit',items:{'minecraft:piston':64,'minecraft:beacon':64}}");
        JsonObject b=json("{item:'minecraft:green_shulker_box',name:'TSR Redstone Kit',items:{'minecraft:piston':64,'minecraft:chest_minecart':1}}");
        JsonObject source=catalog("tango2",sourceId,a,520),target=catalog("main",targetId,b,999);
        target.getAsJsonObject("kitAliases").addProperty(sourceId,targetId);
        JsonObject snapshot=json("{stashes:[]}");snapshot.getAsJsonArray("stashes").add(source);snapshot.getAsJsonArray("stashes").add(target);
        JsonObject before=snapshot.deepCopy(),stock=ResourceApi.kitStock(snapshot,sourceId,s->s);
        assert stock.get("familyId").getAsString().equals(KitTypes.family(a));
        assert stock.get("basis").getAsString().equals("RecordedObservations");
        JsonObject main=stock.getAsJsonArray("stashes").get(1).getAsJsonObject();
        assert main.get("complete").getAsLong()==999 && main.get("incomplete").getAsLong()==2;
        assert main.get("aliasResolved").getAsBoolean() && main.get("resolvedKitTypeId").getAsString().equals(targetId);
        assert main.getAsJsonArray("variants").get(0).getAsJsonObject().getAsJsonObject("items").has("minecraft:chest_minecart");
        assert main.get("unscanned").getAsInt()==3 : "Recorded stock must retain unknown coverage";
        assert snapshot.equals(before) : "Stock reads never migrate or rewrite catalog state";
        main.getAsJsonArray("variants").get(0).getAsJsonObject().getAsJsonObject("items").addProperty("minecraft:piston",0);
        assert snapshot.equals(before) : "Returned manifests cannot mutate the host snapshot";
        assert ResourceApi.kitStock(snapshot,targetId,s->s).getAsJsonArray("stashes").size()==2 : "Either variant can query the family";
        target.getAsJsonObject("kitAliases").remove(sourceId);
        assert ResourceApi.kitStock(snapshot,sourceId,s->s).getAsJsonArray("stashes").get(1).getAsJsonObject().get("complete").getAsLong()==999 : "Family comparison also works before a transfer creates an alias";
        String third=UUID.randomUUID().toString();target.getAsJsonObject("kitTypes").add(third,a.deepCopy());target.getAsJsonObject("kitCounts").add(third,json("{complete:5,incomplete:1}"));
        append(target,third,a,5,false);
        main=ResourceApi.kitStock(snapshot,sourceId,s->s).getAsJsonArray("stashes").get(1).getAsJsonObject();
        assert main.get("complete").getAsLong()==1004 && main.getAsJsonArray("variants").size()==2;
        JsonObject other=a.deepCopy();other.addProperty("name","Unrelated kit");target.getAsJsonObject("kitTypes").add(UUID.randomUUID().toString(),other);
        assert ResourceApi.kitStock(snapshot,sourceId,s->s).getAsJsonArray("stashes").get(1).getAsJsonObject().getAsJsonArray("variants").size()==2;
        target.getAsJsonObject("kitTypes").add(targetId,a.deepCopy());target.getAsJsonObject("kitTypes").remove(third);
        target.getAsJsonObject("kitCounts").add(targetId,json("{complete:5,incomplete:1001}"));
        main=ResourceApi.kitStock(snapshot,sourceId,s->s).getAsJsonArray("stashes").get(1).getAsJsonObject();
        assert main.get("complete").getAsLong()==1004 && main.get("incomplete").getAsLong()==2 : "A substituted-item exemplar cannot relabel old full variants as incomplete";
        assert main.get("recordedBoxes").getAsLong()==1006;
        JsonObject partial=a.deepCopy();partial.add("items",json("{'minecraft:piston':1}"));append(target,targetId,partial,1,false);
        main=ResourceApi.kitStock(snapshot,sourceId,s->s).getAsJsonArray("stashes").get(1).getAsJsonObject();
        assert main.get("ambiguous").getAsLong()==1 && main.get("recordedBoxes").getAsLong()==1007 : "A partial box compatible with two versions is uncertain, never guessed complete";
        JsonObject first=target.getAsJsonObject("containers").getAsJsonObject("0,100,0"),second=target.getAsJsonObject("containers").getAsJsonObject("1,100,0"),thirdBox=target.getAsJsonObject("containers").getAsJsonObject("2,100,0").getAsJsonArray("shulkers").get(0).getAsJsonObject();
        first.addProperty("inferred",true);second.addProperty("status","removed");thirdBox.addProperty("contentsKnown",false);thirdBox.addProperty("quantity",3);
        before=snapshot.deepCopy();main=ResourceApi.kitStock(snapshot,sourceId,s->s).getAsJsonArray("stashes").get(1).getAsJsonObject();
        assert main.get("complete").getAsLong()==1001 && main.get("unknownContents").getAsLong()==3 && main.get("recordedBoxes").getAsLong()==1007 : "Unknown quantities remain separate; removed and inferred stock is not counted: "+main;
        assert main.get("inferred").getAsInt()==1 && snapshot.equals(before) : "Coverage comes from the same catalog as its counts, without mutation";
        JsonObject balance=json("{counts:{},items:{},uncertain:true}");balance.getAsJsonObject("counts").add(targetId,json("{complete:1200,incomplete:3}"));target.add("transferStock",balance);
        main=ResourceApi.kitStock(snapshot,sourceId,s->s).getAsJsonArray("stashes").get(1).getAsJsonObject();
        assert main.get("complete").getAsLong()==1001&&main.getAsJsonObject("transferEstimates").getAsJsonObject(targetId).get("complete").getAsLong()==1200 : "API estimates must not replace or add to observed stock";
        assert main.get("transferStockUncertain").getAsBoolean();
        assert ResourceApi.kitStock(snapshot,UUID.randomUUID().toString(),s->s)==null;
        try { ResourceApi.kitStock(snapshot,"invalid",s->s);throw new AssertionError("Invalid ID accepted"); }
        catch(IllegalArgumentException expected) { }
        System.out.println("Kit stock checks passed: cross-stash variants, aliases, family totals, unknown coverage and read-only isolation.");
    }
}
