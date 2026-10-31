package dev.monocle.host;

import com.google.gson.*;
import dev.monocle.coordinator.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.*;

/** Public per-kit facts only. Stored copy counts, source identities and geometry never leave KitBot. */
final class KitBotContents {
    static final int PAGE_SIZE=4;
    private static final Set<String> BLOCK_ITEMS=loadBlocks();
    private static Set<String> loadBlocks(){
        try(var stream=KitBotContents.class.getResourceAsStream("/minecraft-block-items.json")){
            if(stream==null)throw new IllegalStateException("Missing Minecraft block-item data");
            Set<String> ids=new HashSet<>();JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("blockItems").forEach(v->ids.add(v.getAsString()));return Set.copyOf(ids);
        }catch(IOException e){throw new IllegalStateException("Could not load Minecraft block-item data",e);}
    }
    static String reference(String id){return "k-"+TaskFiles.hash("kitbot-public-v1\n"+id).substring(0,16);}
    static boolean validReference(String value){return value.matches("k-[a-f0-9]{16}");}
    static String kitName(JsonObject kit){
        String name=text(kit,"name").replaceAll("§[0-9a-fk-orA-FK-OR]","").replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]"," ").strip();
        if(name.isEmpty())name=text(kit,"item").replace("minecraft:","").replace('_',' ');
        if(name.length()>96)name=name.substring(0,Character.isHighSurrogate(name.charAt(95))?95:96);return name;
    }
    static JsonObject checked(JsonObject input){
        if(input==null||!Set.of("category","kit","name","items","minimums","page","includeUnavailable").containsAll(input.keySet()))throw new IllegalArgumentException("Unsupported kit inspection fields");
        JsonObject q=input.deepCopy();
        for(String field:List.of("category","kit"))if(q.has(field)&&(!q.get(field).isJsonPrimitive()||!q.getAsJsonPrimitive(field).isString()||(field.equals("kit")?!validReference(text(q,field)):!text(q,field).matches("[a-z][a-z0-9-]{0,47}"))))throw new IllegalArgumentException("Invalid inspection "+field);
        if(q.has("name")&&(!q.get("name").isJsonPrimitive()||!q.getAsJsonPrimitive("name").isString()||text(q,"name").isBlank()||text(q,"name").length()>96||text(q,"name").chars().anyMatch(Character::isISOControl)))throw new IllegalArgumentException("Invalid kit name filter");
        if(!q.has("items"))q.add("items",new JsonArray());
        if(!q.get("items").isJsonArray()||q.getAsJsonArray("items").size()>8)throw new IllegalArgumentException("Select at most eight item filters");
        for(int i=0;i<q.getAsJsonArray("items").size();i++){
            JsonElement value=q.getAsJsonArray("items").get(i);if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Invalid item filter");String normalized=value.getAsString().strip().toLowerCase(Locale.ROOT);
            if(normalized.isEmpty()||!normalized.matches("[a-z0-9_:. /-]{1,80}"))throw new IllegalArgumentException("Use item IDs, item words, blocks or wood for inspection");q.getAsJsonArray("items").set(i,new JsonPrimitive(normalized));
        }
        if(!q.has("minimums"))q.add("minimums",new JsonObject());
        if(!q.get("minimums").isJsonObject()||q.getAsJsonObject("minimums").size()>8)throw new IllegalArgumentException("Select at most eight required items");
        for(String id:q.getAsJsonObject("minimums").keySet()){
            if(!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||id.length()>128)throw new IllegalArgumentException("Required items use exact item IDs");StashCatalog.integer(q.getAsJsonObject("minimums"),id,1,1_000_000);
        }
        if(!q.has("page"))q.addProperty("page",1);StashCatalog.integer(q,"page",1,128);
        if(!q.has("includeUnavailable"))q.addProperty("includeUnavailable",false);
        if(!q.get("includeUnavailable").isJsonPrimitive()||!q.getAsJsonPrimitive("includeUnavailable").isBoolean())throw new IllegalArgumentException("includeUnavailable must be boolean");
        return q;
    }
    static boolean matches(String id,String selector){
        if(selector.contains(":"))return id.equals(selector);String word=selector.replace(' ','_').replace('-','_');
        if(word.equals("blocks")||word.equals("building_blocks"))return BLOCK_ITEMS.contains(id);
        String path=id.substring(id.indexOf(':')+1);
        if(word.equals("wood"))return List.of("oak_","spruce_","birch_","jungle_","acacia_","dark_oak_","mangrove_","cherry_","pale_oak_","bamboo_","crimson_","warped_").stream().anyMatch(path::startsWith)||path.startsWith("stripped_");
        return path.equals(word)||path.startsWith(word+"_")||path.endsWith("_"+word)||path.contains("_"+word+"_");
    }
    private static long amount(JsonObject items,java.util.function.Predicate<String> select){return items.entrySet().stream().filter(e->select.test(e.getKey())).mapToLong(e->e.getValue().getAsLong()).sum();}
    private static JsonObject range(List<JsonObject> rows,String field){
        JsonObject range=new JsonObject();if(!rows.isEmpty()){range.addProperty("min",rows.stream().mapToLong(r->r.get(field).getAsLong()).min().orElseThrow());range.addProperty("max",rows.stream().mapToLong(r->r.get(field).getAsLong()).max().orElseThrow());}return range;
    }
    static JsonObject inspect(JsonObject query,JsonObject categories,JsonObject stock,JsonObject requests,int minimum){
        return inspect(query,categories,stock,requests,minimum,new JsonObject());
    }
    static JsonObject inspect(JsonObject query,JsonObject categories,JsonObject stock,JsonObject requests,int minimum,JsonObject minima){
        JsonObject q=checked(query);List<JsonObject> rows=new ArrayList<>();Set<String> references=new HashSet<>();
        for(var entry:stock.entrySet()){
            JsonObject kit=entry.getValue().getAsJsonObject(),contents=kit.getAsJsonObject("items");String ref=reference(entry.getKey());
            List<String> words=categories.entrySet().stream().map(e->e.getValue().getAsJsonObject()).filter(c->KitCategories.usedFor(c,"Kitbot")&&KitCategories.matches(c,entry.getKey(),contents)).map(c->text(c,"slug")).sorted().toList();
            if(words.isEmpty()||q.has("category")&&!words.contains(text(q,"category"))||q.has("kit")&&!ref.equals(text(q,"kit")))continue;
            if(!references.add(ref))throw new IllegalStateException("Conflicting public kit references");
            long copies=KitBot.available(entry.getKey(),kit,requests);int required=words.stream().filter(word->!q.has("category")||word.equals(text(q,"category"))).mapToInt(word->KitBot.categoryMinimum(minimum,minima,word)).min().orElse(minimum);boolean available=copies>0&&copies>=required;
            if(!available&&!flag(q,"includeUnavailable"))continue;
            if(q.getAsJsonObject("minimums").entrySet().stream().anyMatch(e->!contents.has(e.getKey())||contents.get(e.getKey()).getAsLong()<e.getValue().getAsLong()))continue;
            List<String> selectors=q.getAsJsonArray("items").asList().stream().map(JsonElement::getAsString).toList();
            long matching=amount(contents,id->selectors.isEmpty()?(q.getAsJsonObject("minimums").isEmpty()||q.getAsJsonObject("minimums").has(id)):selectors.stream().anyMatch(s->matches(id,s)));
            if(!selectors.isEmpty()&&matching==0)continue;
            JsonObject row=new JsonObject();row.addProperty("kit",ref);
            String name=kitName(kit);row.addProperty("name",name);
            if(q.has("name")&&!name.toLowerCase(Locale.ROOT).contains(text(q,"name").toLowerCase(Locale.ROOT)))continue;
            row.addProperty("shulker",text(kit,"item"));
            JsonArray aliases=new JsonArray();words.stream().filter(word->!available||copies>0&&copies>=KitBot.categoryMinimum(minimum,minima,word)).forEach(aliases::add);row.add("categories",aliases);row.addProperty("inStock",available);row.addProperty("availability",available?KitBot.stockLabel(copies):"Out of stock");
            JsonObject perKit=new JsonObject();contents.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->perKit.add(e.getKey(),e.getValue().deepCopy()));row.add("contentsPerKit",perKit);
            row.addProperty("matchingItemsPerKit",matching);row.addProperty("blocksPerKit",amount(contents,BLOCK_ITEMS::contains));rows.add(row);
        }
        rows.sort(Comparator.comparingLong((JsonObject r)->r.get("matchingItemsPerKit").getAsLong()).reversed().thenComparing(r->text(r,"kit")));
        int page=q.get("page").getAsInt(),start=Math.min(rows.size(),(page-1)*PAGE_SIZE),end=Math.min(rows.size(),start+PAGE_SIZE);JsonArray selected=new JsonArray();rows.subList(start,end).forEach(selected::add);
        JsonObject out=new JsonObject();out.addProperty("units","All quantities are items inside ONE complete kit, never stored copies. Contents are learned exemplars, not a guarantee about unobserved or incomplete boxes.");out.addProperty("page",page);out.addProperty("hasMore",end<rows.size());out.addProperty("matchingVariants",rows.size());out.addProperty("order","matchingItemsPerKit descending");out.add("matchingItemsPerKitRange",range(rows,"matchingItemsPerKit"));out.add("blocksPerKitRange",range(rows,"blocksPerKit"));out.add("kits",selected);
        if(out.toString().getBytes(StandardCharsets.UTF_8).length>24_576)throw new IllegalStateException("Kit inspection exceeds public context budget");return out;
    }
    static void checkPublicResult(JsonObject result){
        if(!result.keySet().equals(Set.of("units","page","hasMore","matchingVariants","order","matchingItemsPerKitRange","blocksPerKitRange","kits")))throw new IllegalArgumentException("Unexpected public kit inspection fields");
        for(JsonElement value:result.getAsJsonArray("kits"))if(!value.getAsJsonObject().keySet().equals(Set.of("kit","name","shulker","categories","inStock","availability","contentsPerKit","matchingItemsPerKit","blocksPerKit")))throw new IllegalArgumentException("Unexpected public kit variant fields");
    }
}
