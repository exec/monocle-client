package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.text;

/** Crew/world-owned kit rules; contents rules never treat a PvP kit's incidental obsidian as a resource kit. */
public final class KitCategories {
    private KitCategories() { }
    public static final int MAX_CATEGORIES=128;
    private static Path file(Path root,String crew,String scope){return root.resolve("kit-categories").resolve(TaskFiles.hash(crew+"\n"+scope)+".json");}
    public static JsonObject checked(JsonObject input){
        if(!input.keySet().containsAll(Set.of("id","name","slug","mode","members"))||!Set.of("id","name","slug","mode","members","usage").containsAll(input.keySet()))throw new IllegalArgumentException("A category needs id, name, slug, mode, members and optional usage");
        if(input.has("usage")&&(!input.get("usage").isJsonPrimitive()||!input.getAsJsonPrimitive("usage").isString()||!Set.of("Storage","Kitbot","Both").contains(text(input,"usage"))))throw new IllegalArgumentException("Category usage must be Storage, Kitbot or Both");
        for(String key:List.of("id","name","slug","mode"))if(!input.get(key).isJsonPrimitive()||!input.getAsJsonPrimitive(key).isString())throw new IllegalArgumentException("Category "+key+" must be a string");
        JsonObject c=input.deepCopy();if(!UUID.fromString(text(c,"id")).toString().equals(text(c,"id")))throw new IllegalArgumentException("Invalid category ID");
        String name=text(c,"name"),slug=text(c,"slug"),mode=text(c,"mode");
        if(name.isBlank()||name.length()>64||name.chars().anyMatch(Character::isISOControl)||!slug.matches("[a-z][a-z0-9-]{0,47}")||!Set.of("Items","Kits").contains(mode))throw new IllegalArgumentException("Invalid category name, slug or mode");
        if(!c.get("members").isJsonArray())throw new IllegalArgumentException("Category members must be an array");
        JsonArray members=c.getAsJsonArray("members");Set<String> seen=new HashSet<>();
        if(members.isEmpty()||members.size()>128)throw new IllegalArgumentException("Select 1–128 category members");
        for(JsonElement value:members){
            if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Invalid category member");
            String member=value.getAsString();
            if(mode.equals("Items")?!member.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||member.length()>128:!UUID.fromString(member).toString().equals(member))throw new IllegalArgumentException("Invalid category member");
            if(!seen.add(member))throw new IllegalArgumentException("Duplicate category member");
        }
        return c;
    }
    /** Untagged rules retain their pre-split behavior. Reservation matching is independent of discovery usage. */
    public static boolean usedFor(JsonObject category,String usage){return category!=null&&(!category.has("usage")||text(category,"usage").equals("Both")||text(category,"usage").equals(usage));}
    public static JsonObject placementRule(JsonObject category){JsonObject rule=category.deepCopy();rule.remove("usage");return rule;}
    public static boolean matches(JsonObject category,String kit,JsonObject items){
        if(category==null)return false;
        Set<String> members=new HashSet<>();category.getAsJsonArray("members").forEach(v->members.add(v.getAsString()));
        if(text(category,"mode").equals("Kits"))return members.contains(kit);
        return !items.isEmpty()&&items.entrySet().stream().allMatch(e->members.contains(e.getKey())&&e.getValue().getAsLong()>0);
    }
    public static JsonObject get(Path root,String crew,String scope){
        JsonObject db=TaskFiles.read(file(root,crew,scope));if(db.isEmpty()){db.addProperty("version",1);db.add("categories",new JsonObject());}
        if(db.get("version").getAsInt()!=1||db.getAsJsonObject("categories").size()>MAX_CATEGORIES)throw new IllegalStateException("Invalid category catalog");
        Set<String> slugs=new HashSet<>();
        for(var e:db.getAsJsonObject("categories").entrySet()){
            JsonObject category=checked(e.getValue().getAsJsonObject());if(!e.getKey().equals(text(category,"id"))||!slugs.add(text(category,"slug")))throw new IllegalStateException("Invalid category identity");
        }
        db.addProperty("revision",revision(db));return db;
    }
    private static String revision(JsonObject db){JsonObject copy=db.deepCopy();copy.remove("revision");return TaskFiles.hash(copy.toString());}
    public static synchronized JsonObject save(Path root,String crew,String scope,String expected,JsonObject category){
        JsonObject db=get(root,crew,scope);if(!text(db,"revision").equals(expected))throw new IllegalStateException("Category revision changed; reload before editing");
        JsonObject c=checked(category),categories=db.getAsJsonObject("categories");String id=text(c,"id");
        if(!categories.has(id)&&categories.size()>=MAX_CATEGORIES)throw new IllegalArgumentException("Category limit is "+MAX_CATEGORIES);
        for(var e:categories.entrySet())if(!e.getKey().equals(id)&&text(e.getValue().getAsJsonObject(),"slug").equals(text(c,"slug")))throw new IllegalArgumentException("Category slug already exists");
        categories.add(id,c);db.remove("revision");TaskFiles.write(file(root,crew,scope),db);return get(root,crew,scope);
    }
    public static JsonObject category(Path root,String crew,String scope,String id){return get(root,crew,scope).getAsJsonObject("categories").getAsJsonObject(id);}
    /** Group once, then reuse the existing variant/partial-kit accounting without rescanning every box for every family. */
    public static JsonObject stock(JsonObject db){
        Map<String,JsonObject> families=new LinkedHashMap<>();
        for(var e:db.getAsJsonObject("kitTypes").entrySet()){
            String family=KitTypes.family(e.getValue().getAsJsonObject());JsonObject group=families.computeIfAbsent(family,k->{
                JsonObject g=new JsonObject(),record=new JsonObject(),containers=new JsonObject();g.add("kitTypes",new JsonObject());record.addProperty("status","observed");record.add("shulkers",new JsonArray());containers.add("stock",record);g.add("containers",containers);return g;
            });group.getAsJsonObject("kitTypes").add(e.getKey(),e.getValue());
        }
        for(var e:db.getAsJsonObject("containers").entrySet()){
            JsonObject o=e.getValue().getAsJsonObject();if(!text(o,"status").equals("observed")||TaskWire.flag(o,"inferred"))continue;
            for(JsonElement box:o.getAsJsonArray("shulkers")){
                JsonObject group=families.get(KitTypes.family(box.getAsJsonObject()));if(group!=null)group.getAsJsonObject("containers").getAsJsonObject("stock").getAsJsonArray("shulkers").add(box);
            }
        }
        JsonObject result=new JsonObject();
        for(var family:families.entrySet())for(JsonElement value:KitTypes.stockVariants(family.getValue(),family.getKey()).getAsJsonArray("variants")){
            JsonObject v=value.getAsJsonObject();String id=text(v,"signatureId");JsonObject type=db.getAsJsonObject("kitTypes").getAsJsonObject(id);
            if(type==null)continue; // Uncatalogued variants cannot be dispatched using a different historical alias.
            JsonObject row=type.deepCopy();row.addProperty("id",id);
            for(String key:List.of("complete","incomplete","ambiguous"))row.add(key,v.get(key).deepCopy());result.add(id,row);
        }
        for(var entry:StashStock.estimates(db).entrySet()){
            JsonObject type=db.getAsJsonObject("kitTypes").getAsJsonObject(entry.getKey());if(type==null)continue;
            JsonObject row=result.has(entry.getKey())?result.getAsJsonObject(entry.getKey()):type.deepCopy();
            if(!row.has("complete")){row.addProperty("complete",0);row.addProperty("incomplete",0);row.addProperty("ambiguous",0);row.addProperty("id",entry.getKey());}
            JsonObject estimate=entry.getValue().getAsJsonObject();row.addProperty("estimatedComplete",estimate.has("complete")?estimate.get("complete").getAsLong():0);row.addProperty("estimatedIncomplete",estimate.has("incomplete")?estimate.get("incomplete").getAsLong():0);
            result.add(entry.getKey(),row);
        }
        return result;
    }
    public static JsonObject definition(String slug,String name,String mode,Collection<String> members){
        JsonObject c=new JsonObject();c.addProperty("id",UUID.nameUUIDFromBytes(("monocle-category-v1\n"+slug).getBytes(StandardCharsets.UTF_8)).toString());c.addProperty("slug",slug);c.addProperty("name",name);c.addProperty("mode",mode);JsonArray list=new JsonArray();members.forEach(list::add);c.add("members",list);return checked(c);
    }
    /** Explicit operator action; preserves storage matching and gives building kits specific request words. */
    public static synchronized JsonObject seed(Path root,String crew,String scope,JsonObject source,int minimum){
        if(minimum<1||minimum>1_000_000)throw new IllegalArgumentException("Category discovery minimum must be 1–1000000");
        JsonObject db=get(root,crew,scope),stock=stock(source);List<JsonObject> proposed=new ArrayList<>();
        // The broad building rule remains useful for storage, while requests select a specific kit.
        for(var entry:db.getAsJsonObject("categories").deepCopy().entrySet())if(text(entry.getValue().getAsJsonObject(),"slug").equals("building")&&usedFor(entry.getValue().getAsJsonObject(),"Kitbot")){
            JsonObject storage=entry.getValue().getAsJsonObject().deepCopy();storage.addProperty("usage","Storage");db=save(root,crew,scope,text(db,"revision"),storage);
        }
        List<JsonObject> existing=db.getAsJsonObject("categories").entrySet().stream().map(e->e.getValue().getAsJsonObject()).toList();
        for(String[] item:List.of(new String[]{"obsidian","Obsidian","minecraft:obsidian"},new String[]{"crying-obsidian","Crying obsidian","minecraft:crying_obsidian"},new String[]{"gapples","Enchanted golden apples","minecraft:enchanted_golden_apple"},new String[]{"xp","Experience bottles","minecraft:experience_bottle"}))
            proposed.add(definition(item[0],item[1],"Items",List.of(item[2])));
        List<String> pvp=new ArrayList<>();for(var e:stock.entrySet()){
            JsonObject row=e.getValue().getAsJsonObject(),items=row.getAsJsonObject("items");
            if(row.get("complete").getAsLong()>=minimum&&items.has("minecraft:end_crystal")&&(items.has("minecraft:netherite_sword")||items.has("minecraft:diamond_sword")))pvp.add(e.getKey());
        }
        if(!pvp.isEmpty())proposed.add(definition("pvp","PvP","Kits",pvp));
        for(String[] group:List.of(new String[]{"tools","Tools"},new String[]{"redstone","Redstone"},new String[]{"trims","Armor trims"},new String[]{"shulkers","Shulker supplies"},new String[]{"storage","Chests and hoppers"},new String[]{"hunter","Hunter"},new String[]{"grief","Grief supplies"})){
            List<String> ids=new ArrayList<>();for(var e:stock.entrySet()){
                JsonObject row=e.getValue().getAsJsonObject(),items=row.getAsJsonObject("items");if(row.get("complete").getAsLong()<minimum)continue;String name=text(row,"name").toLowerCase(Locale.ROOT);
                boolean match=switch(group[0]){
                    case "tools"->name.contains("tools")&&!items.has("minecraft:end_crystal");case "redstone"->name.contains("redstone kit");
                    case "trims"->items.keySet().stream().anyMatch(k->k.endsWith("_armor_trim_smithing_template"));case "shulkers"->items.keySet().equals(Set.of("minecraft:shulker_shell","minecraft:chest"));
                    case "storage"->items.keySet().equals(Set.of("minecraft:chest","minecraft:hopper"));case "hunter"->name.contains("hunter kit");case "grief"->items.has("minecraft:soul_sand")&&items.has("minecraft:wither_skeleton_skull");default->false;};
                if(match)ids.add(e.getKey());
            }if(!ids.isEmpty())proposed.add(definition(group[0],group[1],"Kits",ids));
        }
        for(String[] group:List.of(new String[]{"dyes","Dyes","_dye"},new String[]{"wool","Wool","_wool"},new String[]{"concrete","Concrete","_concrete"},new String[]{"wood","Wood","_log"})){
            Set<String> allowed=new TreeSet<>();for(var e:stock.entrySet()){JsonObject row=e.getValue().getAsJsonObject();Set<String> keys=row.getAsJsonObject("items").keySet();if(row.get("complete").getAsLong()>=minimum&&!keys.isEmpty()&&keys.stream().allMatch(k->k.endsWith(group[2])||group[0].equals("wood")&&(k.endsWith("_stem")||k.equals("minecraft:bamboo_block"))))allowed.addAll(keys);}
            if(!allowed.isEmpty())proposed.add(definition(group[0],group[1],"Items",allowed));
        }
        for(var e:stock.entrySet()){
            JsonObject row=e.getValue().getAsJsonObject();if(row.get("complete").getAsLong()<minimum)continue;
            boolean build=text(row,"name").toLowerCase(Locale.ROOT).contains("build");
            if(existing.stream().anyMatch(c->usedFor(c,"Kitbot")&&(!build||text(c,"mode").equals("Kits")&&c.getAsJsonArray("members").size()==1&&!text(c,"slug").equals("building"))&&matches(c,e.getKey(),row.getAsJsonObject("items")))
                ||!build&&proposed.stream().anyMatch(c->existing.stream().noneMatch(old->text(old,"slug").equals(text(c,"slug")))&&matches(c,e.getKey(),row.getAsJsonObject("items"))))continue;
            Set<String> items=row.getAsJsonObject("items").keySet();String label=!build&&items.size()==1?items.iterator().next().replace("minecraft:","").replace('_',' '):text(row,"name");
            String slug=label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("^-|-$","");if(slug.isEmpty()||!Character.isLetter(slug.charAt(0)))slug="kit-"+e.getKey().substring(0,8);if(slug.length()>38)slug=slug.substring(0,38);
            final String base=slug;if(proposed.stream().anyMatch(c->text(c,"slug").equals(base))||existing.stream().anyMatch(c->text(c,"slug").equals(base)))slug+="-"+e.getKey().substring(0,8);
            label=label.replaceAll("[\\p{Cntrl}]","");if(label.length()>64)label=label.substring(0,64);
            proposed.add(definition(slug,label.isBlank()?"Kit "+e.getKey().substring(0,8):label,"Kits",List.of(e.getKey())));
        }
        for(JsonObject c:proposed){
            boolean stocked=stock.entrySet().stream().anyMatch(e->e.getValue().getAsJsonObject().get("complete").getAsLong()>=minimum&&matches(c,e.getKey(),e.getValue().getAsJsonObject().getAsJsonObject("items")));
            if(!stocked||db.getAsJsonObject("categories").entrySet().stream().anyMatch(e->text(e.getValue().getAsJsonObject(),"slug").equals(text(c,"slug"))))continue;
            c.addProperty("usage","Kitbot");db=save(root,crew,scope,text(db,"revision"),c);
        }
        return db;
    }
}
