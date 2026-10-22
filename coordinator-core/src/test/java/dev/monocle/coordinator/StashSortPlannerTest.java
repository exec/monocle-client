package dev.monocle.coordinator;

import com.google.gson.*;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.text;

public final class StashSortPlannerTest {
    private static final String A="00000000-0000-0000-0000-000000000001",B="00000000-0000-0000-0000-000000000002",A2="00000000-0000-0000-0000-000000000003";
    private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    private static JsonObject db(){
        JsonObject db=json("{name:'Test',crew:'Default',scope:'server\\nminecraft:the_end',bounds:{minX:0,maxX:50,minY:64,maxY:70,minZ:0,maxZ:50},containers:{},kitTypes:{},kitAliases:{},columnReservations:{}}");
        for(String id:List.of(A,B,A2)){JsonObject type=new JsonObject();type.addProperty("id",id);type.addProperty("name",id);type.add("items",json(id.equals(B)?"{'minecraft:crying_obsidian':64}":"{'minecraft:obsidian':64}"));db.getAsJsonObject("kitTypes").add(id,type);}return db;
    }
    private static JsonObject categories(){
        JsonObject c=json("{revision:'cats-1',categories:{}}");
        for(String[] a:List.of(new String[]{"obsidian","Obsidian","minecraft:obsidian"},new String[]{"crying-obsidian","Crying obsidian","minecraft:crying_obsidian"})){
            JsonObject rule=KitCategories.definition(a[0],a[1],"Items",List.of(a[2]));c.getAsJsonObject("categories").add(TaskWire.text(rule,"id"),rule);
        }return c;
    }
    private static String category(String slug){return categories().getAsJsonObject("categories").entrySet().stream().filter(e->TaskWire.text(e.getValue().getAsJsonObject(),"slug").equals(slug)).findFirst().orElseThrow().getKey();}
    private static JsonObject chest(JsonObject db,int x,int y,int z,int capacity,String... kits){
        JsonObject o=new JsonObject();o.addProperty("x",x);o.addProperty("y",y);o.addProperty("z",z);o.addProperty("block","minecraft:chest");o.addProperty("status","observed");o.addProperty("capacity",capacity);o.addProperty("occupiedSlots",kits.length);o.addProperty("observedAt",1000);o.add("items",new JsonObject());JsonArray boxes=new JsonArray();
        for(String kit:kits){JsonObject b=new JsonObject();b.addProperty("slot",boxes.size());b.addProperty("kitTypeId",kit);b.addProperty("quantity",1);b.addProperty("contentsKnown",true);b.addProperty("item","minecraft:red_shulker_box");b.addProperty("name","Same name, different contents");b.add("items",db.getAsJsonObject("kitTypes").getAsJsonObject(kit).get("items").deepCopy());boxes.add(b);}
        o.add("shulkers",boxes);db.getAsJsonObject("containers").add(x+","+y+","+z,o);return o;
    }
    private static long n(JsonObject p,String key){return p.getAsJsonObject("summary").get(key).getAsLong();}
    private static JsonObject preview(JsonObject db){return StashSortPlanner.preview(db,categories(),1,2,List.of());}
    private static void rejects(Runnable r){try{r.run();throw new AssertionError("Invalid sort preview accepted");}catch(IllegalArgumentException expected){}}
    public static void run(){
        JsonObject d=db();chest(d,0,64,0,4,B,B);chest(d,2,64,0,4,A);chest(d,4,64,0,4,A2);chest(d,6,64,0,4,B);chest(d,8,64,0,4);chest(d,10,64,0,4);
        JsonObject before=d.deepCopy(),cats=categories(),beforeCats=cats.deepCopy(),p=StashSortPlanner.preview(d,cats,1,2,List.of());
        assert d.equals(before)&&cats.equals(beforeCats):"Read-only planner mutated inputs";
        assert p.equals(preview(d)):"Same snapshot and policy must produce the same plan ID";
        assert n(p,"columns")==6&&n(p,"emptyColumns")==2&&n(p,"projectedEmptyColumns")==4&&n(p,"netColumnsFreed")==2;
        assert n(p,"movableKits")==5&&n(p,"kitsToMove")==5&&n(p,"minimumInventoryLoads")==3&&n(p,"pairwiseInventoryLoads")==4;
        assert p.getAsJsonObject("summary").get("cyclicDependencies").getAsBoolean();
        assert p.get("feasibleForMovableColumns").getAsBoolean()&&p.get("completeMappedCoverage").getAsBoolean()&&p.get("executionSupported").getAsBoolean();
        long outbound=p.getAsJsonArray("moves").asList().stream().mapToLong(v->v.getAsJsonObject().get("kits").getAsLong()).sum();assert outbound==5;
        JsonObject ordered=StashSortPlanner.preview(d,categories(),1,2,List.of(category("crying-obsidian")));
        assert n(ordered,"kitsToMove")<n(p,"kitsToMove"):"A chosen order can preserve more stock";
        assert !TaskWire.text(ordered,"planId").equals(TaskWire.text(p,"planId"));
        JsonObject gaps=StashSortPlanner.preview(d,categories(),1,2,List.of(),null,1);assert n(gaps,"growthColumnsReserved")==1&&n(gaps,"unassignedEmptyColumns")==2;
        JsonObject gap=gaps.getAsJsonArray("columns").asList().stream().map(JsonElement::getAsJsonObject).filter(c->TaskWire.flag(c,"growth")).findFirst().orElseThrow();int gapIndex=gap.get("index").getAsInt();assert text(gap,"target").equals(text(gaps.getAsJsonArray("columns").get(gapIndex-2).getAsJsonObject(),"target"))&&gap.get("plannedKits").getAsInt()==0:"Growth must belong to its physical left neighbor";
        rejects(()->StashSortPlanner.preview(d,categories(),1,2,List.of(),null,3));
        JsonObject sorted=db();chest(sorted,0,64,0,4,A,A2);chest(sorted,2,64,0,4,B,B,B);chest(sorted,4,64,0,4);assert n(preview(sorted),"kitsToMove")==0;
        JsonObject overlap=categories();JsonObject extra=KitCategories.definition("also-obsidian","Also obsidian","Kits",List.of(A));overlap.getAsJsonObject("categories").add(TaskWire.text(extra,"id"),extra);
        JsonObject ambiguous=StashSortPlanner.preview(d,overlap,1,2,List.of());assert n(ambiguous,"pinnedColumns")==1;assert ambiguous.getAsJsonArray("unclassifiedKitTypeIds").contains(new JsonPrimitive(A));
        extra.addProperty("usage","Kitbot");JsonObject separated=StashSortPlanner.preview(d,overlap,1,2,List.of());
        assert n(separated,"pinnedColumns")==0&&!separated.getAsJsonObject("categoryDefinitions").has(TaskWire.text(extra,"id")):"Kitbot subcategories cannot compete with storage groups";
        rejects(()->StashSortPlanner.preview(d,overlap,1,2,List.of(),List.of(TaskWire.text(extra,"id"))));extra.remove("usage");
        JsonObject selected=StashSortPlanner.preview(d,overlap,1,2,List.of(),List.of(category("obsidian"),category("crying-obsidian")));assert n(selected,"pinnedColumns")==0&&n(selected,"movableKits")==5;
        assert n(StashSortPlanner.preview(d,categories(),1,2,List.of(),List.of()),"pinnedColumns")==4:"No selected categories must not silently enable all";
        rejects(()->StashSortPlanner.preview(d,categories(),1,2,List.of(),List.of("unknown")));
        JsonObject unknown=d.deepCopy();unknown.getAsJsonObject("containers").getAsJsonObject("8,64,0").addProperty("status","unscanned");assert n(preview(unknown),"emptyColumns")==1&&n(preview(unknown),"pinnedColumns")==1;
        unknown=d.deepCopy();unknown.getAsJsonObject("containers").getAsJsonObject("8,64,0").addProperty("inferred",true);assert n(preview(unknown),"emptyColumns")==1;
        JsonObject loose=d.deepCopy();loose.getAsJsonObject("containers").getAsJsonObject("8,64,0").addProperty("occupiedSlots",1);assert n(preview(loose),"pinnedColumns")==1;
        JsonObject missing=d.deepCopy();missing.getAsJsonObject("containers").getAsJsonObject("8,64,0").remove("occupiedSlots");assert n(preview(missing),"pinnedColumns")==1;
        JsonObject duplicate=d.deepCopy();duplicate.getAsJsonObject("containers").add("same-double-chest",duplicate.getAsJsonObject("containers").get("0,64,0").deepCopy());assert n(preview(duplicate),"movableKits")==5&&n(preview(duplicate),"duplicateRecordsIgnored")==1;
        JsonObject variant=d.deepCopy();variant.getAsJsonObject("kitAliases").addProperty(A2,A);assert n(preview(variant),"pinnedColumns")==0;
        hopperGeometry();
        for(int seed=0;seed<30;seed++){
            Random random=new Random(seed);JsonObject layout=db();long total=0;
            for(int col=0;col<12;col++){
                String[] kits=new String[col<8?random.nextInt(7):0];for(int i=0;i<kits.length;i++)kits[i]=List.of(A,A2,B).get(random.nextInt(3));
                total+=kits.length;chest(layout,col*2,64,0,6,kits);
            }
            JsonObject proposal=StashSortPlanner.preview(layout,categories(),2,3,List.of());
            assert proposal.get("feasibleForMovableColumns").getAsBoolean();assert n(proposal,"keptInPlace")+n(proposal,"kitsToMove")==total;
            assert proposal.getAsJsonArray("moves").asList().stream().mapToLong(v->v.getAsJsonObject().get("kits").getAsLong()).sum()==n(proposal,"kitsToMove");
        }
        rejects(()->StashSortPlanner.preview(d,categories(),0,34,List.of()));rejects(()->StashSortPlanner.preview(d,categories(),1,37,List.of()));
        rejects(()->StashSortPlanner.preview(d,categories(),1,34,List.of(category("obsidian"),category("obsidian"))));
        rejects(()->StashSortPlanner.preview(d,categories(),1,34,List.of(UUID.randomUUID().toString())));
        JsonObject tooManyBuffers=StashSortPlanner.preview(d,categories(),4,34,List.of());assert !tooManyBuffers.get("feasibleForMovableColumns").getAsBoolean();
        System.out.println("Stash sort checks passed: category consolidation, conservation, deterministic snapshots, geometry, buffers, unknowns and no writes.");
    }
    private static void hopperGeometry(){
        for(boolean alongX:List.of(true,false))for(int sign:List.of(-1,1)){
            JsonObject d=db();
            for(int col=0;col<3;col++)for(int row=0;row<2;row++){
                int x=20+(alongX?col*2:sign*row),z=20+(alongX?sign*row:col*2);
                chest(d,x,64+row,z,4,row==0&&col==0?new String[]{A}:new String[]{});
            }
            int hx=20+(alongX?0:sign),hz=20+(alongX?sign:0);
            JsonObject h=chest(d,hx,64,hz,5,A,A,A);h.addProperty("block","minecraft:hopper");
            JsonObject p=preview(d);assert n(p,"columns")==3&&n(p,"movableKits")==4&&n(p,"chestSlots")==24&&n(p,"hopperSlotsExcludedFromDestinationCapacity")==5&&n(p,"unmappedContainers")==0;
            assert n(p,"keptInPlace")==4;
        }
        JsonObject d=db();chest(d,0,64,0,1,A);chest(d,0,65,1,1);chest(d,2,64,0,1);chest(d,2,65,1,1);
        JsonObject h=chest(d,0,64,1,5,A,A,A);h.addProperty("block","minecraft:hopper");
        JsonObject p=preview(d);assert n(p,"capacityShortfall")==2&&!p.get("feasibleForMovableColumns").getAsBoolean()&&p.getAsJsonArray("moves").isEmpty():"Hopper stock must not disappear or become imaginary deposit capacity";
        JsonObject holes=db();chest(holes,0,64,0,4,A);chest(holes,0,66,2,4);chest(holes,2,64,0,4);assert n(preview(holes),"pinnedColumns")==1;
    }
}
