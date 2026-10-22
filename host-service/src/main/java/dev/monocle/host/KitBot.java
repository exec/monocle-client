package dev.monocle.host;

import com.google.gson.*;
import dev.monocle.coordinator.*;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import static dev.monocle.coordinator.TaskWire.*;
import static dev.monocle.coordinator.StashCatalog.integer;

/** Server whispers are trusted server text, not cryptographically authenticated player messages. */
final class KitBot {
    private static final Pattern WHISPER=Pattern.compile("^(?:\\[(?:unknown player|[A-Za-z0-9_]{1,16}) head\\] )?([A-Za-z0-9_]{1,16}) whispers: (kit [a-z][a-z0-9-]{0,47}(?: [1-9][0-9]?)?|kits(?: [1-9][0-9]{0,5})?)$");
    private final Path root,file;
    private JsonObject db;
    KitBot(Path root){this.root=root;file=root.resolve("kitbot.json");db=TaskFiles.read(file);if(db.isEmpty()){
        db.addProperty("version",1);db.add("config",new JsonObject());db.add("requests",new JsonObject());
    }else{if(integer(db,"version",1,1)!=1||db.getAsJsonObject("requests").size()>512)throw new IllegalArgumentException("Invalid kitbot journal");checked(db.getAsJsonObject("config"));}}
    JsonObject view(){JsonObject out=db.deepCopy();out.addProperty("revision",TaskFiles.hash(db.getAsJsonObject("config").toString()));return out;}
    private static JsonObject checked(JsonObject c){
        if(c.isEmpty())return c;
        if(!c.keySet().equals(Set.of("enabled","crew","scope","source","worker","minimumStock","cooldownSeconds","whitelist")))throw new IllegalArgumentException("Invalid kitbot configuration fields");
        for(String key:List.of("crew","scope","source","worker"))if(!c.get(key).isJsonPrimitive()||!c.getAsJsonPrimitive(key).isString())throw new IllegalArgumentException(key+" must be a string");
        if(!c.get("enabled").isJsonPrimitive()||!c.getAsJsonPrimitive("enabled").isBoolean())throw new IllegalArgumentException("enabled must be boolean");
        UUID.fromString(text(c,"worker"));String scope=text(c,"scope");int split=scope.indexOf('\n');
        if(split<1||split!=scope.lastIndexOf('\n')||scope.length()>384||scope.substring(0,split).chars().anyMatch(Character::isISOControl)||!scope.substring(split+1).matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Invalid kitbot world");
        if(text(c,"crew").isBlank()||text(c,"source").isBlank())throw new IllegalArgumentException("Select crew and source stash");
        integer(c,"minimumStock",250,1_000_000);integer(c,"cooldownSeconds",0,259200);
        JsonArray list=c.getAsJsonArray("whitelist");if(list==null||list.size()>128)throw new IllegalArgumentException("Whitelist limit is 128");Set<String> names=new HashSet<>(),ids=new HashSet<>();
        for(JsonElement v:list){JsonObject p=v.getAsJsonObject();String name=text(p,"name"),id=text(p,"uuid");
            if(!p.keySet().containsAll(Set.of("name","uuid"))||!Set.of("name","uuid","cooldownSeconds","maxKits").containsAll(p.keySet())||!name.matches("[A-Za-z0-9_]{1,16}")||!UUID.fromString(id).toString().equals(id)||!names.add(name.toLowerCase(Locale.ROOT))||!ids.add(id))throw new IllegalArgumentException("Whitelist needs distinct exact usernames and UUIDs, with optional cooldownSeconds and maxKits");
            if(p.has("cooldownSeconds"))integer(p,"cooldownSeconds",0,259200);
            if(p.has("maxKits"))integer(p,"maxKits",1,36);
        }
        return c;
    }
    JsonObject configure(JsonObject c,String expected){if(!text(view(),"revision").equals(expected))throw new IllegalStateException("Kitbot configuration changed; reload");checked(c);JsonObject next=db.deepCopy();next.add("config",c.deepCopy());save(next);return view();}
    static String[] parse(String raw){if(raw==null||raw.chars().anyMatch(Character::isISOControl))return null;var m=WHISPER.matcher(raw);if(!m.matches()||!m.group(2).startsWith("kit "))return null;String[] command=m.group(2).split(" ");int count=command.length==3?Integer.parseInt(command[2]):1;if(count>36)return null;return new String[]{m.group(1),command[1],String.valueOf(count)};}
    private JsonObject person(String name){for(JsonElement v:db.getAsJsonObject("config").getAsJsonArray("whitelist")){JsonObject p=v.getAsJsonObject();if(text(p,"name").equalsIgnoreCase(name))return p;}return null;}
    JsonObject listing(String raw){
        if(raw==null||raw.chars().anyMatch(Character::isISOControl))return null;var m=WHISPER.matcher(raw);if(!m.matches()||!m.group(2).startsWith("kits"))return null;
        JsonObject person=person(m.group(1));if(person==null)return null;String[] command=m.group(2).split(" ");int page=command.length==2?Integer.parseInt(command[1]):1;
        JsonObject c=db.getAsJsonObject("config"),catalog=StashCatalog.get(root,text(c,"crew"),text(c,"scope"),text(c,"source"));List<String> entries=new ArrayList<>();
        if(!catalog.isEmpty()){
            JsonObject categories=KitCategories.get(root,text(c,"crew"),text(c,"scope")).getAsJsonObject("categories");
            for(var e:KitCategories.stock(catalog).entrySet()){
                JsonObject kit=e.getValue().getAsJsonObject();long count=available(e.getKey(),kit,db.getAsJsonObject("requests"));if(count<c.get("minimumStock").getAsInt())continue;
                List<String> slugs=categories.entrySet().stream().map(v->v.getValue().getAsJsonObject()).filter(v->KitCategories.usedFor(v,"Kitbot")&&KitCategories.matches(v,e.getKey(),kit.getAsJsonObject("items"))).map(v->text(v,"slug")).sorted().toList();
                String name=text(kit,"name").replaceAll("§[0-9a-fk-orA-FK-OR]","").replaceAll("\\p{Cntrl}"," ").strip();if(name.isEmpty())name=text(kit,"item").replace("minecraft:","");
                entries.add(name+" ["+(slugs.isEmpty()?"unlisted":String.join("/",slugs))+"]");
            }
        }
        entries.sort(String.CASE_INSENSITIVE_ORDER);List<String> pages=pages(text(person,"name"),entries);
        JsonObject reply=new JsonObject();reply.addProperty("name",text(person,"name"));reply.addProperty("status","Listing");reply.addProperty("text",page<=pages.size()?pages.get(page-1):"No such page. Available pages: 1–"+pages.size()+".");return reply;
    }
    static List<String> pages(String name,List<String> entries){
        // The client chat boundary is 256 UTF-16 units, including /msg, username and spaces.
        int budget=256-("/msg "+name+" ").length();List<String> pages=new ArrayList<>();String prefix="Kits 1: ";StringBuilder page=new StringBuilder(prefix);
        for(String entry:entries){
            if(page.length()>prefix.length()&&page.length()+2+entry.length()+4>budget){pages.add(page+",...");prefix="Kits "+(pages.size()+1)+": ";page=new StringBuilder(prefix);}
            int max=budget-prefix.length()-4;if(entry.length()>max){int end=max-3;if(Character.isHighSurrogate(entry.charAt(end-1)))end--;entry=entry.substring(0,end)+"...";}
            if(page.length()>prefix.length())page.append(", ");page.append(entry);
        }
        pages.add(page.length()==prefix.length()?prefix+"No kits in stock.":page.toString());return pages;
    }
    boolean receiver(String worker,String crew,String scope){JsonObject c=db.getAsJsonObject("config");return flag(c,"enabled")&&text(c,"worker").equals(worker)&&text(c,"crew").equals(crew)&&text(c,"scope").substring(0,text(c,"scope").indexOf('\n')).equals(scope.split("\n",-1)[0]);}
    static long cooldown(JsonObject config,JsonObject person){return (person.has("cooldownSeconds")?person.get("cooldownSeconds").getAsLong():config.has("cooldownSeconds")?config.get("cooldownSeconds").getAsLong():3600)*1000;}
    static int maxKits(JsonObject person){return person.has("maxKits")?person.get("maxKits").getAsInt():5;}
    private static long available(String id,JsonObject kit,JsonObject requests){long count=kit.get("complete").getAsLong();for(var r:requests.entrySet()){JsonObject request=r.getValue().getAsJsonObject();if(Set.of("Pending","Queued","Running").contains(text(request,"status"))&&id.equals(text(request,"kitTypeId")))count-=request.get("count").getAsInt();}return count;}
    static JsonObject select(JsonObject category,JsonObject stock,JsonObject requests,int minimum,int quantity){
        if(!KitCategories.usedFor(category,"Kitbot"))return null;
        JsonObject best=null;long most=-1;
        for(var e:stock.entrySet()){
            JsonObject kit=e.getValue().getAsJsonObject();if(!KitCategories.matches(category,e.getKey(),kit.getAsJsonObject("items")))continue;
            long count=available(e.getKey(),kit,requests);
            if(count>=minimum&&count>=quantity&&count>most){most=count;best=kit;}
        }return best;
    }
    /** Persist the exact submission before dispatch; retries reuse its ID and never create a second delivery. */
    JsonObject receive(String raw,long now,OperationsLibrary library){
        String[] parsed=parse(raw);if(parsed==null)return null;JsonObject c=db.getAsJsonObject("config"),person=person(parsed[0]);
        if(person==null)return null;
        for(var e:db.getAsJsonObject("requests").entrySet()){JsonObject r=e.getValue().getAsJsonObject();if(text(r,"recipient").equals(text(person,"uuid"))&&(now-r.get("at").getAsLong()<Math.max(5000,text(r,"status").equals("LimitExceeded")?0:cooldown(c,person))||Set.of("Pending","Queued","Running").contains(text(r,"status"))))return null;}
        if(db.getAsJsonObject("requests").entrySet().stream().filter(e->Set.of("Pending","Queued","Running").contains(text(e.getValue().getAsJsonObject(),"status"))).count()>=8)return null;
        int limit=maxKits(person);boolean overLimit=Integer.parseInt(parsed[2])>limit;
        String crew=text(c,"crew"),scope=text(c,"scope");JsonObject catalog=overLimit?new JsonObject():StashCatalog.get(root,crew,scope,text(c,"source")),category=null;
        if(!overLimit)for(var e:KitCategories.get(root,crew,scope).getAsJsonObject("categories").entrySet())if(KitCategories.usedFor(e.getValue().getAsJsonObject(),"Kitbot")&&text(e.getValue().getAsJsonObject(),"slug").equals(parsed[1]))category=e.getValue().getAsJsonObject();
        JsonObject chosen=category==null||catalog.isEmpty()?null:select(category,KitCategories.stock(catalog),db.getAsJsonObject("requests"),c.get("minimumStock").getAsInt(),Integer.parseInt(parsed[2]));
        JsonObject r=new JsonObject();String id=UUID.randomUUID().toString();r.addProperty("id",id);r.addProperty("at",now);r.addProperty("recipient",text(person,"uuid"));r.addProperty("name",text(person,"name"));r.addProperty("category",parsed[1]);r.addProperty("count",Integer.parseInt(parsed[2]));
        r.addProperty("status",overLimit?"LimitExceeded":chosen==null?"OutOfStock":"Pending");r.addProperty("maxKits",limit);
        if(overLimit)r.addProperty("detail","Request exceeds this user's "+limit+"-kit cap; no delivery queued or cooldown consumed");
        if(chosen!=null){
            r.addProperty("kitTypeId",text(chosen,"id"));JsonObject args=catalog.getAsJsonObject("bounds").deepCopy();args.addProperty("name",text(c,"source"));args.addProperty("kitTypeId",text(chosen,"id"));args.add("kitExemplar",chosen.get("items").deepCopy());args.addProperty("count",Integer.parseInt(parsed[2]));args.addProperty("destination","Player");args.addProperty("recipient",text(person,"uuid"));args.addProperty("recipientName",text(person,"name"));args.addProperty("returnHome",true);
            JsonObject submit=new JsonObject();submit.addProperty("op","submit");submit.addProperty("id",id);submit.addProperty("name","Kitbot "+parsed[1]);submit.addProperty("crew",crew);int split=scope.indexOf('\n');submit.addProperty("server",scope.substring(0,split));submit.addProperty("dimension",scope.substring(split+1));submit.addProperty("priority",-1000);JsonArray workers=new JsonArray();workers.add(text(c,"worker"));submit.add("workers",workers);submit.add("args",args);submit.add("package",library.prepare("task-kit-delivery",scope,args));r.add("submission",submit);
        }
        JsonObject next=db.deepCopy(),requests=next.getAsJsonObject("requests");while(requests.size()>=512){String oldest=requests.entrySet().stream().filter(e->!Set.of("Pending","Queued","Running").contains(text(e.getValue().getAsJsonObject(),"status"))).findFirst().orElseThrow().getKey();requests.remove(oldest);}requests.add(id,r);save(next);return r.deepCopy();
    }
    List<JsonObject> pending(){return db.getAsJsonObject("requests").entrySet().stream().map(e->e.getValue().getAsJsonObject()).filter(r->text(r,"status").equals("Pending")).map(JsonObject::deepCopy).toList();}
    void state(String id,String status,String detail){JsonObject r=db.getAsJsonObject("requests").getAsJsonObject(id);if(r==null||text(r,"status").equals(status)&&text(r,"detail").equals(detail))return;JsonObject next=db.deepCopy();r=next.getAsJsonObject("requests").getAsJsonObject(id);r.addProperty("status",status);r.addProperty("detail",detail);r.remove("submission");save(next);}
    void reconcile(Map<UUID,JsonObject> tasks){for(var e:db.getAsJsonObject("requests").entrySet()){JsonObject r=e.getValue().getAsJsonObject();if(!Set.of("Queued","Running").contains(text(r,"status")))continue;JsonObject task=tasks.get(UUID.fromString(e.getKey()));if(task!=null){String status=text(task,"status"),detail=text(task,"detail");if(QueuePolicy.terminal(status)&&detail.isEmpty()&&task.has("runs"))detail=task.getAsJsonObject("runs").entrySet().stream().findFirst().map(run->text(run.getValue().getAsJsonObject(),"detail")).orElse("");state(e.getKey(),QueuePolicy.terminal(status)?status:status.equals("Running")?"Running":"Queued",detail);}}}
    private void save(JsonObject next){TaskFiles.write(file,next);db=next;}
}
