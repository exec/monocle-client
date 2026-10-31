package dev.monocle.host;

import com.google.gson.*;
import dev.monocle.coordinator.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import static dev.monocle.coordinator.TaskWire.*;
import static dev.monocle.coordinator.StashCatalog.integer;

/** Server whispers are trusted server text, not cryptographically authenticated player messages. */
final class KitBot {
    private static final int CREDIT_LIMIT=27;
    private static final Pattern WHISPER=Pattern.compile("^(?:\\[(?:unknown player|[A-Za-z0-9_]{1,16}) head\\] )?([A-Za-z0-9_]{1,16}) whispers: (kit [a-z][a-z0-9-]{0,47}(?: [1-9][0-9]?(?: k-[a-f0-9]{16})?)?|(?:kits|menu)(?: [1-9][0-9]{0,5})?)$");
    private static final Pattern CONVERSATION=Pattern.compile("^(?:\\[(?:unknown player|[A-Za-z0-9_]{1,16}) head\\] )?([A-Za-z0-9_]{1,16}) whispers: (.{1,512})$");
    private final Path root,file;
    private final CreditBank bank;
    record Courier(String id,String name,boolean queued) {}
    private final java.util.function.Function<String,Courier> courierSelector;
    private JsonObject db;
    private final Map<String,JsonObject> publicPeople=new LinkedHashMap<>();
    private final Map<String,Long> administratorSeen=new HashMap<>();
    private record CreditOffer(String category,int quantity,String kit,JsonObject recipient,long at) {}
    private final Map<String,CreditOffer> creditOffers=new LinkedHashMap<>();
    KitBot(Path root){this(root,null);}
    KitBot(Path root,CreditBank bank){this(root,bank,null);}
    KitBot(Path root,CreditBank bank,java.util.function.Function<String,Courier> courierSelector){this.courierSelector=courierSelector;this.root=root;this.bank=bank;file=root.resolve("kitbot.json");db=bank==null?new JsonObject():bank.kitbot();boolean migrate=db.isEmpty()&&bank!=null;if(db.isEmpty())db=TaskFiles.read(file);if(db.isEmpty()){
        db.addProperty("version",1);db.add("config",new JsonObject());db.add("requests",new JsonObject());
    }else{if(integer(db,"version",1,1)!=1||db.getAsJsonObject("requests").size()>512)throw new IllegalArgumentException("Invalid kitbot journal");checked(db.getAsJsonObject("config"));if(Files.exists(file))privateFile();}if(migrate)bank.saveKitbot(db,null,0);}
    JsonObject view(){JsonObject out=db.deepCopy();out.addProperty("revision",TaskFiles.hash(db.getAsJsonObject("config").toString()));if(out.getAsJsonObject("config").has("mike")){JsonObject m=out.getAsJsonObject("config").getAsJsonObject("mike");m.addProperty("apiKeyConfigured",!text(m,"apiKey").isEmpty());m.remove("apiKey");}return out;}
    private static JsonObject checked(JsonObject c){
        if(c.isEmpty())return c;
        Set<String> required=Set.of("enabled","crew","scope","source","worker","minimumStock","cooldownSeconds","whitelist");
        if(!c.keySet().containsAll(required)||!Set.of("enabled","crew","scope","source","worker","minimumStock","cooldownSeconds","whitelist","mike","sources","publicAccess","excludedKitFamilies","categoryMinimumStock","categoryCooldownSeconds","backupWorker").containsAll(c.keySet()))throw new IllegalArgumentException("Invalid kitbot configuration fields");
        if(c.has("categoryMinimumStock")){
            if(!c.get("categoryMinimumStock").isJsonObject()||c.getAsJsonObject("categoryMinimumStock").size()>128)throw new IllegalArgumentException("Select up to 128 category stock minimums");
            JsonObject minima=c.getAsJsonObject("categoryMinimumStock");for(String slug:minima.keySet()){
                if(!slug.matches("[a-z][a-z0-9-]{0,47}"))throw new IllegalArgumentException("Category stock minimums use exact request words");integer(minima,slug,0,1_000_000);
            }
        }
        if(c.has("categoryCooldownSeconds")){
            if(!c.get("categoryCooldownSeconds").isJsonObject()||c.getAsJsonObject("categoryCooldownSeconds").size()>128)throw new IllegalArgumentException("Select up to 128 limited category cooldowns");
            JsonObject limits=c.getAsJsonObject("categoryCooldownSeconds");for(String slug:limits.keySet()){
                if(!slug.matches("[a-z][a-z0-9-]{0,47}"))throw new IllegalArgumentException("Limited categories use exact request words");integer(limits,slug,1,2_592_000);
            }
        }
        if(c.has("publicAccess")&&(!c.get("publicAccess").isJsonPrimitive()||!c.getAsJsonPrimitive("publicAccess").isBoolean()))throw new IllegalArgumentException("publicAccess must be boolean");
        if(c.has("excludedKitFamilies")){
            if(!c.get("excludedKitFamilies").isJsonArray()||c.getAsJsonArray("excludedKitFamilies").size()>128)throw new IllegalArgumentException("Exclude up to 128 kit families");
            Set<String> excluded=new HashSet<>();for(JsonElement value:c.getAsJsonArray("excludedKitFamilies")){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||!UUID.fromString(value.getAsString()).toString().equals(value.getAsString())||!excluded.add(value.getAsString()))throw new IllegalArgumentException("Excluded kit families require distinct canonical UUIDs");}
        }
        if(c.has("backupWorker")&&(!c.get("backupWorker").isJsonPrimitive()||!c.getAsJsonPrimitive("backupWorker").isString()||!text(c,"backupWorker").isEmpty()&&(!UUID.fromString(text(c,"backupWorker")).toString().equals(text(c,"backupWorker"))||text(c,"backupWorker").equals(text(c,"worker")))))throw new IllegalArgumentException("Choose a distinct backup courier UUID or leave it empty");
        sourceNames(c);
        if(c.has("mike"))MikeKitHandler.checked(c.getAsJsonObject("mike"));
        for(String key:List.of("crew","scope","source","worker"))if(!c.get(key).isJsonPrimitive()||!c.getAsJsonPrimitive(key).isString())throw new IllegalArgumentException(key+" must be a string");
        if(!c.get("enabled").isJsonPrimitive()||!c.getAsJsonPrimitive("enabled").isBoolean())throw new IllegalArgumentException("enabled must be boolean");
        UUID.fromString(text(c,"worker"));String scope=text(c,"scope");int split=scope.indexOf('\n');
        if(split<1||split!=scope.lastIndexOf('\n')||scope.length()>384||scope.substring(0,split).chars().anyMatch(Character::isISOControl)||!scope.substring(split+1).matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Invalid kitbot world");
        if(text(c,"crew").isBlank()||text(c,"source").isBlank())throw new IllegalArgumentException("Select crew and source stash");
        integer(c,"minimumStock",250,1_000_000);integer(c,"cooldownSeconds",0,259200);
        JsonArray list=c.getAsJsonArray("whitelist");if(list==null||list.size()>128)throw new IllegalArgumentException("Whitelist limit is 128");Set<String> names=new HashSet<>(),ids=new HashSet<>();
        for(JsonElement v:list){JsonObject p=v.getAsJsonObject();String name=text(p,"name"),id=text(p,"uuid");
            if(!p.keySet().containsAll(Set.of("name","uuid"))||!Set.of("name","uuid","cooldownSeconds","maxKits","role").containsAll(p.keySet())||!name.matches("[A-Za-z0-9_]{1,16}")||!UUID.fromString(id).toString().equals(id)||!names.add(name.toLowerCase(Locale.ROOT))||!ids.add(id))throw new IllegalArgumentException("Whitelist needs distinct exact usernames and UUIDs, with optional cooldownSeconds, maxKits and role");
            if(p.has("role")&&(!p.get("role").isJsonPrimitive()||!p.getAsJsonPrimitive("role").isString()||!Set.of("User","Administrator").contains(text(p,"role"))))throw new IllegalArgumentException("Role must be User or Administrator");
            if(p.has("cooldownSeconds"))integer(p,"cooldownSeconds",0,259200);
            if(p.has("maxKits"))integer(p,"maxKits",1,36);
        }
        return c;
    }
    JsonObject configure(JsonObject c,String expected){
        if(!text(view(),"revision").equals(expected))throw new IllegalStateException("Kitbot configuration changed; reload");
        c=c.deepCopy();JsonObject previous=mikeSettings();
        if(!c.has("excludedKitFamilies")&&db.getAsJsonObject("config").has("excludedKitFamilies"))c.add("excludedKitFamilies",db.getAsJsonObject("config").get("excludedKitFamilies").deepCopy());
        if(!c.has("categoryMinimumStock")&&db.getAsJsonObject("config").has("categoryMinimumStock"))c.add("categoryMinimumStock",db.getAsJsonObject("config").get("categoryMinimumStock").deepCopy());
        if(!c.has("categoryCooldownSeconds")&&db.getAsJsonObject("config").has("categoryCooldownSeconds"))c.add("categoryCooldownSeconds",db.getAsJsonObject("config").get("categoryCooldownSeconds").deepCopy());
        if(!c.has("backupWorker")&&db.getAsJsonObject("config").has("backupWorker"))c.add("backupWorker",db.getAsJsonObject("config").get("backupWorker").deepCopy());
        if(!c.has("mike")&&db.getAsJsonObject("config").has("mike"))c.add("mike",previous.deepCopy());
        if(c.has("mike")){
            JsonObject m=c.getAsJsonObject("mike");
            for(String field:List.of("provenance","coordinateJokes"))if(!m.has(field)&&previous.has(field))m.add(field,previous.get(field).deepCopy());
            for(String key:List.of("clearApiKey","apiKeyConfigured"))if(m.has(key)&&(!m.get(key).isJsonPrimitive()||!m.getAsJsonPrimitive(key).isBoolean()))throw new IllegalArgumentException(key+" must be boolean");
            if(m.has("apiKey")&&(!m.get("apiKey").isJsonPrimitive()||!m.getAsJsonPrimitive("apiKey").isString()))throw new IllegalArgumentException("Mike API key must be text");
            boolean clear=flag(m,"clearApiKey");String supplied=text(m,"apiKey");
            if(clear&&!supplied.isEmpty())throw new IllegalArgumentException("Choose either replacing or clearing Mike's API key");
            if(!clear&&supplied.isEmpty()&&!text(previous,"apiKey").isEmpty()){
                if(!java.net.URI.create(text(previous,"endpoint")).resolve("/api/chat").equals(java.net.URI.create(text(m,"endpoint")).resolve("/api/chat")))throw new IllegalArgumentException("Re-enter or clear Mike's API key when changing the Ollama endpoint");
                supplied=text(previous,"apiKey");
            }
            m.remove("clearApiKey");m.remove("apiKeyConfigured");m.addProperty("apiKey",clear?"":supplied);
        }
        checked(c);JsonObject next=db.deepCopy();next.add("config",c);save(next);publicPeople.clear();administratorSeen.clear();creditOffers.clear();return view();
    }
    static List<String> sourceNames(JsonObject config){
        LinkedHashSet<String> names=new LinkedHashSet<>();names.add(text(config,"source"));
        if(config.has("sources")){
            if(!config.get("sources").isJsonArray()||config.getAsJsonArray("sources").size()>16)throw new IllegalArgumentException("Select up to 16 additional source stashes");
            for(JsonElement value:config.getAsJsonArray("sources")){
                if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||value.getAsString().isBlank()||value.getAsString().length()>128||value.getAsString().chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid kitbot source stash");
                names.add(value.getAsString());
            }
        }
        return List.copyOf(names);
    }
    private List<JsonObject> catalogs(){
        JsonObject c=db.getAsJsonObject("config");return sourceNames(c).stream().map(name->StashCatalog.get(root,text(c,"crew"),text(c,"scope"),name)).filter(source->!source.isEmpty()).toList();
    }
    // A variant qualifies in one source; taking the maximum never double-counts overlapping selections.
    static JsonObject combinedStock(List<JsonObject> stocks){
        JsonObject result=new JsonObject();for(JsonObject stock:stocks)for(var entry:stock.entrySet()){
            JsonObject row=entry.getValue().getAsJsonObject();
            if(!result.has(entry.getKey())||row.get("complete").getAsLong()>result.getAsJsonObject(entry.getKey()).get("complete").getAsLong())result.add(entry.getKey(),row.deepCopy());
        }return result;
    }
    private JsonObject dispatchStock(JsonObject catalog){
        JsonObject result=KitCategories.stock(catalog),config=db.getAsJsonObject("config");Set<String> excluded=new HashSet<>();
        if(config.has("excludedKitFamilies"))for(JsonElement id:config.getAsJsonArray("excludedKitFamilies"))excluded.add(id.getAsString());
        result.entrySet().removeIf(e->excluded.contains(KitTypes.family(e.getValue().getAsJsonObject()))||excluded.contains(text(e.getValue().getAsJsonObject(),"familyId")));
        return result;
    }
    private JsonObject categories(){JsonObject c=db.getAsJsonObject("config");return KitCategories.get(root,text(c,"crew"),text(c,"scope")).getAsJsonObject("categories");}
    private JsonObject stock(){return combinedStock(catalogs().stream().map(this::dispatchStock).toList());}
    private JsonObject availableStock(){return KitBotAvailability.stock(db,categories(),stock(),System.currentTimeMillis());}
    static String[] parse(String raw){if(raw==null||raw.chars().anyMatch(Character::isISOControl))return null;var m=WHISPER.matcher(raw);if(!m.matches()||!m.group(2).startsWith("kit "))return null;String[] command=m.group(2).split(" ");int count=command.length>=3?Integer.parseInt(command[2]):1;if(count>36)return null;return command.length==4?new String[]{m.group(1),command[1],String.valueOf(count),command[3]}:new String[]{m.group(1),command[1],String.valueOf(count)};}
    private JsonObject person(String name){for(JsonElement v:db.getAsJsonObject("config").getAsJsonArray("whitelist")){JsonObject p=v.getAsJsonObject();if(text(p,"name").equalsIgnoreCase(name))return p;}return flag(db.getAsJsonObject("config"),"publicAccess")?publicPeople.get(name.toLowerCase(Locale.ROOT)):null;}
    boolean administrator(String name){JsonObject p=person(name);return p!=null&&text(p,"role").equals("Administrator")&&System.currentTimeMillis()-administratorSeen.getOrDefault(name.toLowerCase(Locale.ROOT),0L)<90_000;}
    JsonObject identity(String name){JsonObject p=person(name);if(p==null)throw new IllegalArgumentException("Unknown requesting player");JsonObject out=new JsonObject();out.addProperty("id",text(p,"uuid"));out.addProperty("name",text(p,"name"));return out;}
    void observeSender(String raw,JsonObject sender){
        String[] whisper=BotChat.whisper(raw);if(whisper==null)return;
        administratorSeen.remove(whisper[0].toLowerCase(Locale.ROOT));if(sender==null)return;
        if(sender==null||!sender.keySet().equals(Set.of("name","uuid"))||!text(sender,"name").equalsIgnoreCase(whisper[0])||!UUID.fromString(text(sender,"uuid")).toString().equals(text(sender,"uuid")))throw new IllegalArgumentException("Whisper sender profile must match its exact username and UUID");
        for(String field:List.of("name","uuid"))if(!sender.get(field).isJsonPrimitive()||!sender.getAsJsonPrimitive(field).isString())throw new IllegalArgumentException("Invalid whisper profile fields");
        for(JsonElement value:db.getAsJsonObject("config").getAsJsonArray("whitelist")){JsonObject known=value.getAsJsonObject();if(text(known,"name").equalsIgnoreCase(whisper[0])&&!text(known,"uuid").equals(text(sender,"uuid")))throw new IllegalArgumentException("Whisper profile does not match the whitelisted account");}
        JsonObject known=person(whisper[0]);if(known!=null&&text(known,"role").equals("Administrator"))administratorSeen.put(whisper[0].toLowerCase(Locale.ROOT),System.currentTimeMillis());
        if(!flag(db.getAsJsonObject("config"),"publicAccess"))return;
        String key=whisper[0].toLowerCase(Locale.ROOT);JsonObject person=new JsonObject();person.addProperty("name",text(sender,"name"));person.addProperty("uuid",text(sender,"uuid"));person.addProperty("cooldownSeconds",3600);person.addProperty("maxKits",1);
        publicPeople.remove(key);while(publicPeople.size()>=128)publicPeople.remove(publicPeople.keySet().iterator().next());publicPeople.put(key,person);
    }
    JsonObject unresolvedSender(String raw){
        String[] whisper=BotChat.whisper(raw);if(!flag(db.getAsJsonObject("config"),"publicAccess")||whisper==null||person(whisper[0])!=null)return null;
        JsonObject reply=new JsonObject();reply.addProperty("name",whisper[0]);reply.addProperty("status","IdentityUnavailable");return reply;
    }
    JsonObject listing(String raw){
        if(raw==null||raw.chars().anyMatch(Character::isISOControl))return null;var m=WHISPER.matcher(raw);if(!m.matches()||m.group(2).startsWith("kit "))return null;
        JsonObject person=person(m.group(1));if(person==null)return null;String[] command=m.group(2).split(" ");int page=command.length==2?Integer.parseInt(command[1]):1;
        if(command[0].equals("menu"))return menu(m.group(1),page);
        JsonObject c=db.getAsJsonObject("config"),stock=availableStock();List<String> entries=new ArrayList<>();
        if(!stock.isEmpty()){
            JsonObject categories=KitCategories.get(root,text(c,"crew"),text(c,"scope")).getAsJsonObject("categories"),minima=categoryMinimums(c,person);
            for(var e:stock.entrySet()){
                JsonObject kit=e.getValue().getAsJsonObject();long count=available(e.getKey(),kit,db.getAsJsonObject("requests"));
                List<JsonObject> matching=categories.entrySet().stream().map(v->v.getValue().getAsJsonObject()).filter(v->KitCategories.usedFor(v,"Kitbot")&&KitCategories.matches(v,e.getKey(),kit.getAsJsonObject("items"))).toList();
                List<String> slugs=matching.stream().filter(v->count>0&&count>=categoryMinimum(minimumStock(c,person),minima,text(v,"slug"))).map(v->text(v,"slug")).sorted().toList();
                if(slugs.isEmpty()&&(!matching.isEmpty()||count<=0||count<minimumStock(c,person)))continue;
                String name=text(kit,"name").replaceAll("§[0-9a-fk-orA-FK-OR]","").replaceAll("\\p{Cntrl}"," ").strip();if(name.isEmpty())name=text(kit,"item").replace("minecraft:","");
                entries.add(name+" ["+(slugs.isEmpty()?"unlisted":String.join("/",slugs))+"]");
            }
        }
        entries.sort(String.CASE_INSENSITIVE_ORDER);List<String> pages=pages(text(person,"name"),entries);
        JsonObject reply=new JsonObject();reply.addProperty("name",text(person,"name"));reply.addProperty("status","Listing");reply.addProperty("text",page<=pages.size()?pages.get(page-1):"No such page. Available pages: 1–"+pages.size()+".");return reply;
    }
    JsonObject menu(String name,int page){
        if(person(name)==null)return null;if(page<1||page>999999)throw new IllegalArgumentException("Invalid menu page");
        List<String> pages=menuPages(name,mikeCatalog(name));JsonObject reply=new JsonObject();reply.addProperty("name",name);reply.addProperty("status","Listing");reply.addProperty("text",page<=pages.size()?pages.get(page-1):"No such page. Available pages: 1–"+pages.size()+".");return reply;
    }
    static List<String> menuPages(String name,JsonArray catalog){
        int budget=256-("/msg "+name+" ").length(),total=1,assumed;List<String> pages;
        List<JsonObject> rows=catalog.asList().stream().map(JsonElement::getAsJsonObject).sorted(Comparator.comparingInt((JsonObject row)->flag(row,"inStock")?0:1).thenComparing(row->text(row,"category"))).toList();
        if(rows.isEmpty())return List.of("Kits 1/1: No categories configured.");
        // Page totals and next-page hints count against the same whisper budget as request words.
        do{
            assumed=total;pages=new ArrayList<>();int page=1;String section="";StringBuilder line=new StringBuilder("Kits 1/"+assumed+" ");
            for(JsonObject row:rows){
                String group=flag(row,"inStock")?"In:":"Out:",slug=text(row,"category");
                String addition=section.isEmpty()?group+slug:section.equals(group)?","+slug:" "+group+slug;
                String hint=page<assumed?" | menu "+(page+1):"";
                if(!section.isEmpty()&&line.length()+addition.length()+hint.length()>budget){
                    pages.add(line+hint);page++;line=new StringBuilder("Kits "+page+"/"+assumed+" ");addition=group+slug;
                }
                line.append(addition);section=group;
            }
            pages.add(line.toString());total=pages.size();
        }while(total!=assumed);
        return pages;
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
    boolean receiver(String worker,String crew,String scope){JsonObject c=db.getAsJsonObject("config");return flag(c,"enabled")&&(text(c,"worker").equals(worker)||!text(c,"backupWorker").isEmpty()&&text(c,"backupWorker").equals(worker))&&text(c,"crew").equals(crew)&&text(c,"scope").substring(0,text(c,"scope").indexOf('\n')).equals(scope.split("\n",-1)[0]);}
    static boolean projectAccess(JsonObject config,JsonObject person){
        return flag(config,"publicAccess")&&person!=null&&!text(person,"uuid").isEmpty()&&config.has("whitelist")&&config.getAsJsonArray("whitelist").asList().stream().anyMatch(value->text(value.getAsJsonObject(),"uuid").equals(text(person,"uuid")));
    }
    static int minimumStock(JsonObject config,JsonObject person){return projectAccess(config,person)?109:config.get("minimumStock").getAsInt();}
    static JsonObject categoryMinimums(JsonObject config,JsonObject person){
        JsonObject minima=config.has("categoryMinimumStock")?config.getAsJsonObject("categoryMinimumStock").deepCopy():new JsonObject();
        if(projectAccess(config,person))for(String slug:List.copyOf(minima.keySet()))minima.addProperty(slug,Math.min(109,minima.get(slug).getAsInt()));return minima;
    }
    static int categoryMinimum(int fallback,JsonObject minima,String slug){return minima.has(slug)?minima.get(slug).getAsInt():fallback;}
    static long cooldown(JsonObject config,JsonObject person){if(projectAccess(config,person))return 0;return (person.has("cooldownSeconds")?person.get("cooldownSeconds").getAsLong():config.has("cooldownSeconds")?config.get("cooldownSeconds").getAsLong():3600)*1000;}
    private long cooldownFor(JsonObject person,boolean paid){long base=cooldown(db.getAsJsonObject("config"),person);return paid?Math.min(base,600_000):base;}
    static int maxKits(JsonObject person){return person.has("maxKits")?person.get("maxKits").getAsInt():5;}
    static String cooldownOwner(JsonObject request){return request.has("requester")?text(request,"requester"):text(request,"recipient");}
    private long credits(JsonObject person){return bank==null?0:bank.account(text(person,"uuid")).get("balance").getAsLong();}
    JsonObject creditPolicy(String name,long now){
        JsonObject person=person(name),out=new JsonObject();if(person==null)return out;
        out.addProperty("creditBalance",credits(person));out.addProperty("maximumFreeKitsPerRequest",maxKits(person));out.addProperty("maximumCreditKitsPerRequest",CREDIT_LIMIT);
        out.addProperty("freeCooldownSeconds",cooldownFor(person,false)/1000);out.addProperty("creditCooldownSeconds",cooldownFor(person,true)/1000);
        JsonObject paid=cooldownReply(name+" whispers: allowance",now,true);out.addProperty("creditCooldownRemainingSeconds",paid==null?0:(paid.get("cooldownUntil").getAsLong()-now+999)/1000);
        JsonObject cooldown=cooldownReply(name+" whispers: allowance",now);out.addProperty("freeCooldownRemainingSeconds",cooldown==null?0:(cooldown.get("cooldownUntil").getAsLong()-now+999)/1000);return out;
    }
    JsonObject balanceReply(String raw){
        String[] whisper=BotChat.whisper(raw);if(whisper==null||!Set.of("balance","credits").contains(whisper[1].toLowerCase(Locale.ROOT).strip()))return null;
        JsonObject person=person(whisper[0]);if(person==null)return null;JsonObject out=new JsonObject();out.addProperty("name",text(person,"name"));out.addProperty("status","Listing");out.addProperty("text","Your kit credit balance is "+credits(person)+". Your free hourly kit is separate.");return out;
    }
    JsonObject confirmCredits(String raw,long now,OperationsLibrary library){return confirmCredits(raw,now,library,"");}
    JsonObject confirmCredits(String raw,long now,OperationsLibrary library,String receiver){
        String[] whisper=BotChat.whisper(raw);if(whisper==null)return null;JsonObject person=person(whisper[0]);if(person==null)return null;
        CreditOffer offer=creditOffers.remove(text(person,"uuid"));if(offer==null)return null;
        String answer=whisper[1].strip().toLowerCase(Locale.ROOT).replaceAll("[.!]+$","");
        boolean yes=Set.of("yes","yes please","confirm","yes use credits","yes use my credits","use credits","use my credits").contains(answer);
        if(!yes&&!Set.of("no","no thanks","cancel").contains(answer))return null;
        JsonObject reply=new JsonObject();reply.addProperty("name",text(person,"name"));reply.addProperty("status","Listing");
        if(!yes){reply.addProperty("text","Credit purchase cancelled. No credits were spent.");return reply;}
        if(now-offer.at()>=120_000||requestedSince(whisper[0],offer.at())){reply.addProperty("text","That credit offer expired. Ask for the kits again; no credits were spent.");return reply;}
        JsonObject order=receive(whisper[0],offer.category(),offer.quantity(),offer.kit(),now,library,offer.recipient(),"Credits",receiver);
        if(order!=null)return order;
        reply.addProperty("text","That purchase is not available right now. Ask again shortly; no credits were spent.");return reply;
    }
    JsonObject offerCredits(String name,String category,int quantity,String kit,long now,OperationsLibrary library,JsonObject recipient){
        return cooldownReply(name+" whispers: allowance",now,false)==null?null:receive(name,category,quantity,kit,now,library,recipient,"Free");
    }
    boolean mayDiscussCredits(String raw){String[] whisper=BotChat.whisper(raw);if(whisper==null||whisper[1].startsWith("kit "))return false;JsonObject person=person(whisper[0]);return person!=null&&(whisper[1].startsWith("buy ")||whisper[1].toLowerCase(Locale.ROOT).matches("(?s).*\\b(?:credits?|balance)\\b.*")||credits(person)>0);}
    JsonObject cooldownReply(String raw,long now){String message=messageText(raw);return cooldownReply(raw,now,message!=null&&MikeKitHandler.creditSpendingRequested(message));}
    private JsonObject cooldownReply(String raw,long now,boolean paid){
        if(raw==null||raw.chars().anyMatch(Character::isISOControl))return null;
        var match=CONVERSATION.matcher(raw);if(!match.matches())return null;
        JsonObject person=person(match.group(1));if(person==null||administrator(match.group(1)))return null;
        long duration=cooldownFor(person,paid),until=0;
        if(duration==0)return null;
        for(var entry:db.getAsJsonObject("requests").entrySet()){
            JsonObject request=entry.getValue().getAsJsonObject();
            if(cooldownOwner(request).equals(text(person,"uuid"))&&cooldownApplies(request,paid))until=Math.max(until,request.get("at").getAsLong()+duration);
        }
        if(until<=now)return null;
        JsonObject reply=new JsonObject();reply.addProperty("name",text(person,"name"));reply.addProperty("status","Cooldown");reply.addProperty("payment",paid?"Credits":"Free");reply.addProperty("cooldownUntil",until);return reply;
    }
    String cooldownMessage(String name,long now){return cooldownMessage(name,now,false);}
    String cooldownMessage(String name,long now,boolean paid){if(person(name)==null)return null;JsonObject reply=cooldownReply(name+" whispers: allowance",now,paid);return cooldownText(reply==null?0:reply.get("cooldownUntil").getAsLong()-now);}
    private static boolean cooldownApplies(JsonObject order,boolean paid){return paid?order.has("creditsCharged"):!text(order,"payment").equals("Credits")&&!text(order,"status").equals("LimitExceeded");}
    static String cooldownText(long remainingMs){
        if(remainingMs<=0)return "Your kit cooldown has ended. You can request kits again.";
        long seconds=(remainingMs+999)/1000;
        return "You're on cooldown. You can request kits again in "+(seconds>=3600?seconds/3600+"h ":"")+(seconds>=60?seconds/60%60+"m ":"")+seconds%60+"s.";
    }
    JsonObject mikeSettings(){JsonObject c=db.getAsJsonObject("config");return c.has("mike")?c.getAsJsonObject("mike").deepCopy():MikeKitHandler.defaults();}
    private static boolean prunable(JsonObject request,long now){
        if(KitBotAvailability.unresolved(request))return false;
        if(request.has("timeoutNotice")&&!request.getAsJsonObject("timeoutNotice").has("sentAt"))return false;
        if(text(request,"payment").equals("Credits")&&request.has("creditsCharged")&&!text(request,"creditSettlement").equals("Settled"))return false;
        return !Set.of("Pending","Queued","Running").contains(text(request,"status"))&&now-request.get("at").getAsLong()>=Math.max(request.has("creditsCharged")?600_000:5000,request.has("cooldownMs")?request.get("cooldownMs").getAsLong():259_200_000L);
    }
    private boolean eligible(JsonObject person,long now){
        return eligible(person,now,false);
    }
    private boolean eligible(JsonObject person,long now,boolean paid){return eligible(person,now,paid,null);}
    private boolean eligible(JsonObject person,long now,boolean paid,String recipient){
        long duration=cooldownFor(person,paid);boolean parallel=cooldownFor(person,false)==0;
        for(var e:db.getAsJsonObject("requests").entrySet()){
            JsonObject r=e.getValue().getAsJsonObject();if(!cooldownOwner(r).equals(text(person,"uuid")))continue;
            if(now-r.get("at").getAsLong()<5000)return false;
            if(Set.of("Pending","Queued","Running").contains(text(r,"status"))&&(!parallel||recipient!=null&&recipient.equals(text(r,"recipient"))))return false;
            if(cooldownApplies(r,paid)&&now-r.get("at").getAsLong()<duration)return false;
        }
        if(db.getAsJsonObject("requests").size()>=512&&db.getAsJsonObject("requests").entrySet().stream().noneMatch(e->prunable(e.getValue().getAsJsonObject(),now)))return false;
        return db.getAsJsonObject("requests").entrySet().stream().filter(e->Set.of("Pending","Queued","Running").contains(text(e.getValue().getAsJsonObject(),"status"))).count()<8;
    }
    static String messageText(String raw){
        if(raw==null||raw.chars().anyMatch(Character::isISOControl))return null;
        var match=CONVERSATION.matcher(raw);return match.matches()?match.group(2):null;
    }
    JsonObject mikeRequest(String raw,long now){
        if(!flag(mikeSettings(),"enabled")||raw==null||raw.chars().anyMatch(Character::isISOControl))return null;
        var match=CONVERSATION.matcher(raw);if(!match.matches())return null;
        JsonObject person=person(match.group(1));if(person==null||!administrator(match.group(1))&&!eligible(person,now)&&!(mayDiscussCredits(raw)&&eligible(person,now,true)))return null;
        JsonObject request=new JsonObject();request.addProperty("name",text(person,"name"));request.addProperty("message",match.group(2));request.addProperty("maxKits",credits(person)>0?Math.max(maxKits(person),CREDIT_LIMIT):maxKits(person));return request;
    }
    boolean canAskMike(String name,long now){JsonObject p=person(name);return flag(mikeSettings(),"enabled")&&p!=null&&(administrator(name)||eligible(p,now)||eligible(p,now,true));}
    boolean requestedSince(String name,long at){JsonObject p=person(name);return p==null||db.getAsJsonObject("requests").entrySet().stream().map(e->e.getValue().getAsJsonObject()).anyMatch(r->cooldownOwner(r).equals(text(p,"uuid"))&&r.get("at").getAsLong()>=at);}
    /** Public projection only: never pass catalogs, variant records or request journals to a model. */
    JsonArray mikeCatalog(){return mikeCatalog("");}
    JsonArray mikeCatalog(String name){
        JsonObject c=db.getAsJsonObject("config");
        return mikeCatalog(KitCategories.get(root,text(c,"crew"),text(c,"scope")).getAsJsonObject("categories"),availableStock(),db.getAsJsonObject("requests"),minimumStock(c,person(name)),categoryMinimums(c,person(name)));
    }
    static JsonArray mikeCatalog(JsonObject categories,JsonObject stock,JsonObject requests,int minimum){
        return mikeCatalog(categories,stock,requests,minimum,new JsonObject());
    }
    static JsonArray mikeCatalog(JsonObject categories,JsonObject stock,JsonObject requests,int minimum,JsonObject minima){
        JsonArray result=new JsonArray();
        for(JsonObject category:categories.entrySet().stream().map(e->e.getValue().getAsJsonObject()).filter(c->KitCategories.usedFor(c,"Kitbot")).sorted(Comparator.comparing(c->text(c,"slug"))).toList()){
            long total=0;Set<String> contents=new TreeSet<>();
            for(var entry:stock.entrySet()){
                JsonObject kit=entry.getValue().getAsJsonObject();if(!KitCategories.matches(category,entry.getKey(),kit.getAsJsonObject("items")))continue;
                long count=available(entry.getKey(),kit,requests);if(count<=0||count<categoryMinimum(minimum,minima,text(category,"slug")))continue;
                total=Math.min(1_000_000_000L,total+count);contents.addAll(kit.getAsJsonObject("items").keySet());
            }
            JsonObject row=new JsonObject();row.addProperty("category",text(category,"slug"));row.addProperty("description",text(category,"name"));row.addProperty("availability",stockLabel(total));row.addProperty("inStock",total>0);
            JsonArray items=new JsonArray();contents.stream().filter(s->s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")&&s.length()<=128).limit(8).forEach(items::add);row.add("contents",items);result.add(row);
        }
        return result;
    }
    static String stockLabel(long count){
        if(count<=0)return "Out of stock";
        if(count<350)return "Very little";
        if(count<500)return "Not a lot";
        if(count<700)return "A little bit";
        if(count<1000)return "Some";
        if(count<1400)return "A reasonable amount";
        if(count<2000)return "Quite a bit";
        return "Plenty";
    }
    static long available(String id,JsonObject kit,JsonObject requests){long count=kit.get("complete").getAsLong();for(var r:requests.entrySet()){JsonObject request=r.getValue().getAsJsonObject();if(Set.of("Pending","Queued","Running").contains(text(request,"status"))&&id.equals(text(request,"kitTypeId")))count-=request.get("count").getAsInt();}return count;}
    JsonObject inspect(String name,JsonObject query){
        JsonObject person=person(name);if(person==null)throw new IllegalArgumentException("Unknown kit requester");JsonObject c=db.getAsJsonObject("config");
        return KitBotContents.inspect(query,KitCategories.get(root,text(c,"crew"),text(c,"scope")).getAsJsonObject("categories"),availableStock(),db.getAsJsonObject("requests"),minimumStock(c,person),categoryMinimums(c,person));
    }
    static JsonObject select(JsonObject category,JsonObject stock,JsonObject requests,int minimum,int quantity){return select(category,stock,requests,minimum,quantity,"");}
    static JsonObject select(JsonObject category,JsonObject stock,JsonObject requests,int minimum,int quantity,String kitRef){
        if(!KitCategories.usedFor(category,"Kitbot"))return null;
        JsonObject best=null;long most=-1;
        for(var e:stock.entrySet()){
            JsonObject kit=e.getValue().getAsJsonObject();if(!KitCategories.matches(category,e.getKey(),kit.getAsJsonObject("items"))||!kitRef.isEmpty()&&!KitBotContents.reference(e.getKey()).equals(kitRef))continue;
            long count=available(e.getKey(),kit,requests);
            if(count>=minimum&&count>=quantity&&count>most){most=count;best=kit;}
        }return best;
    }
    /** Persist the exact submission before dispatch; retries reuse its ID and never create a second delivery. */
    JsonObject receive(String raw,long now,OperationsLibrary library){return receive(raw,now,library,"");}
    JsonObject receive(String raw,long now,OperationsLibrary library,String receiver){
        String[] whisper=BotChat.whisper(raw);boolean paid=whisper!=null&&whisper[1].startsWith("buy ");
        String[] parsed=parse(paid?raw.replaceFirst("whispers: buy ","whispers: kit "):raw);if(parsed==null)return null;return receive(parsed[0],parsed[1],Integer.parseInt(parsed[2]),parsed.length==4?parsed[3]:"",now,library,null,paid?"Credits":"Free",receiver);
    }
    JsonObject receive(String name,String categoryName,int quantity,String kitRef,long now,OperationsLibrary library){
        return receive(name,categoryName,quantity,kitRef,now,library,null);
    }
    JsonObject receive(String name,String categoryName,int quantity,String kitRef,long now,OperationsLibrary library,JsonObject recipient){
        return receive(name,categoryName,quantity,kitRef,now,library,recipient,"Free");
    }
    JsonObject receive(String name,String categoryName,int quantity,String kitRef,long now,OperationsLibrary library,JsonObject recipient,String payment){return receive(name,categoryName,quantity,kitRef,now,library,recipient,payment,"");}
    JsonObject receive(String name,String categoryName,int quantity,String kitRef,long now,OperationsLibrary library,JsonObject recipient,String payment,String receiver){
        if(!Set.of("Free","Credits").contains(payment))throw new IllegalArgumentException("Payment must be Free or Credits");boolean paid=payment.equals("Credits");
        if(!name.matches("[A-Za-z0-9_]{1,16}")||!categoryName.matches("[a-z][a-z0-9-]{0,47}")||quantity<1||quantity>36||!kitRef.isEmpty()&&!KitBotContents.validReference(kitRef))throw new IllegalArgumentException("Invalid kit request");
        JsonObject c=db.getAsJsonObject("config"),person=person(name);
        if(person==null)return null;
        String target=recipient==null?text(person,"uuid"):text(recipient,"id"),targetName=recipient==null?text(person,"name"):text(recipient,"name");
        if(!targetName.matches("[A-Za-z0-9_]{1,16}")||!UUID.fromString(target).toString().equals(target))throw new IllegalArgumentException("Invalid delivery recipient identity");
        boolean eligible=eligible(person,now,paid,target),offer=!paid&&!eligible&&cooldownReply(name+" whispers: allowance",now,false)!=null&&credits(person)>=quantity&&quantity<=CREDIT_LIMIT&&eligible(person,now,true,target);
        if(!eligible&&!offer){
            JsonObject cooldown=cooldownReply(name+" whispers: allowance",now,paid);if(cooldown!=null)return paid?cooldown:null;
            for(var entry:db.getAsJsonObject("requests").entrySet()){JsonObject order=entry.getValue().getAsJsonObject();if(cooldownOwner(order).equals(text(person,"uuid"))&&now-order.get("at").getAsLong()<5000)return null;}
            JsonObject reply=new JsonObject();reply.addProperty("name",text(person,"name"));reply.addProperty("status","Listing");
            boolean existing=db.getAsJsonObject("requests").entrySet().stream().map(e->e.getValue().getAsJsonObject()).anyMatch(order->cooldownOwner(order).equals(text(person,"uuid"))&&Set.of("Pending","Queued","Running").contains(text(order,"status"))&&target.equals(text(order,"recipient")));
            reply.addProperty("text",existing?"You already have a delivery to "+targetName+" in progress. No additional order was created.":"I can't queue another delivery right now. Please try again shortly; no extra order or credit charge was created.");return reply;
        }
        int limit=paid||offer?CREDIT_LIMIT:maxKits(person);boolean overLimit=quantity>limit,insufficient=paid&&credits(person)<quantity;
        String crew=text(c,"crew"),scope=text(c,"scope");JsonObject categories=categories(),catalog=new JsonObject(),chosen=null,category=null,blockedCandidate=null;
        if(!overLimit)for(var e:categories.entrySet())if(KitCategories.usedFor(e.getValue().getAsJsonObject(),"Kitbot")&&text(e.getValue().getAsJsonObject(),"slug").equals(categoryName))category=e.getValue().getAsJsonObject();
        if(category!=null&&!insufficient)for(JsonObject source:catalogs()){
            JsonObject sourceStock=dispatchStock(source);int minimum=categoryMinimum(minimumStock(c,person),categoryMinimums(c,person),categoryName);
            JsonObject candidate=select(category,KitBotAvailability.stock(db,categories,sourceStock,now),db.getAsJsonObject("requests"),minimum,quantity,kitRef);
            if(candidate!=null&&(chosen==null||candidate.get("complete").getAsLong()>chosen.get("complete").getAsLong())){chosen=candidate;catalog=source;}
            if(blockedCandidate==null)blockedCandidate=select(category,sourceStock,new JsonObject(),minimum,quantity,kitRef);
        }
        JsonObject limitedReply=KitBotAvailability.rejection(db,categories,chosen==null?blockedCandidate:chosen,text(person,"name"),quantity,now);if(limitedReply!=null){creditOffers.remove(text(person,"uuid"));return limitedReply;}
        if(offer){
            JsonObject reply=new JsonObject();reply.addProperty("name",text(person,"name"));
            if(chosen==null){reply.addProperty("status","OutOfStock");return reply;}
            creditOffers.entrySet().removeIf(e->now-e.getValue().at()>=120_000);String owner=text(person,"uuid");
            creditOffers.remove(owner);while(creditOffers.size()>=128)creditOffers.remove(creditOffers.keySet().iterator().next());
            creditOffers.put(owner,new CreditOffer(categoryName,quantity,KitBotContents.reference(text(chosen,"id")),recipient==null?null:recipient.deepCopy(),now));
            reply.addProperty("status","Listing");reply.addProperty("text",cooldownMessage(name,now)+" Spend "+quantity+" credit"+(quantity==1?"":"s")+" on "+quantity+" "+categoryName+" kit"+(quantity==1?"":"s")+(recipient==null?"":" for "+targetName)+"? Reply yes within 2m, or no. Nothing charged.");return reply;
        }
        Courier courier=null;
        if(chosen!=null){
            String preferred=receiver.isEmpty()?text(c,"worker"):receiver;
            courier=courierSelector==null?new Courier(text(c,"worker"),"",false):courierSelector.apply(preferred);
            if(courier==null){JsonObject unavailable=new JsonObject();unavailable.addProperty("name",text(person,"name"));unavailable.addProperty("status","Listing");unavailable.addProperty("text","No configured courier is ready right now. Please try again shortly; no order or credit charge was created.");return unavailable;}
            if(!UUID.fromString(courier.id()).toString().equals(courier.id())||!Set.of(text(c,"worker"),text(c,"backupWorker")).contains(courier.id()))throw new IllegalStateException("Selected courier is not configured");
            if(!courier.name().isEmpty()&&!courier.name().matches("[A-Za-z0-9_]{1,16}"))throw new IllegalStateException("Invalid courier display name");
        }
        creditOffers.remove(text(person,"uuid"));
        JsonObject r=new JsonObject();String id=UUID.randomUUID().toString();r.addProperty("id",id);r.addProperty("at",now);r.addProperty("requester",text(person,"uuid"));r.addProperty("recipient",target);r.addProperty("recipientName",targetName);r.addProperty("name",text(person,"name"));r.addProperty("category",categoryName);r.addProperty("count",quantity);
        if(!kitRef.isEmpty())r.addProperty("kit",kitRef);
        r.addProperty("noticeVersion",1);r.addProperty("worker",courier==null?text(c,"worker"):courier.id());if(courier!=null){r.addProperty("courierName",courier.name());r.addProperty("courierQueued",courier.queued());}r.addProperty("crew",crew);r.addProperty("scope",scope);
        r.addProperty("payment",payment);r.addProperty("status",overLimit?"LimitExceeded":insufficient?"InsufficientCredits":chosen==null?"OutOfStock":"Pending");r.addProperty("maxKits",limit);r.addProperty("cooldownMs",overLimit||insufficient||paid&&chosen==null?0:cooldownFor(person,paid));
        if(overLimit)r.addProperty("detail","Request exceeds this user's "+limit+"-kit cap; no delivery queued or cooldown consumed");
        if(chosen!=null){
            KitBotAvailability.reserve(db,categories,chosen,r);
            r.addProperty("kitName",KitBotContents.kitName(chosen));
            r.addProperty("kitTypeId",text(chosen,"id"));JsonObject args=catalog.getAsJsonObject("bounds").deepCopy();args.addProperty("name",text(catalog.getAsJsonObject("bounds"),"name"));args.addProperty("kitTypeId",text(chosen,"id"));args.add("kitExemplar",chosen.get("items").deepCopy());args.addProperty("count",quantity);args.addProperty("destination","Player");args.addProperty("recipient",target);args.addProperty("recipientName",targetName);args.addProperty("returnHome",true);args.addProperty("returnByDeath",true);
            JsonObject submit=new JsonObject();submit.addProperty("op","submit");submit.addProperty("id",id);submit.addProperty("name","Kitbot "+categoryName);submit.addProperty("crew",crew);int split=scope.indexOf('\n');submit.addProperty("server",scope.substring(0,split));submit.addProperty("dimension",scope.substring(split+1));submit.addProperty("priority",-1000);JsonArray workers=new JsonArray();workers.add(courier.id());submit.add("workers",workers);submit.add("args",args);submit.add("package",library.prepare("task-kit-delivery",scope,args));r.add("submission",submit);
        }
        if(paid&&chosen!=null){r.addProperty("creditsCharged",quantity);r.addProperty("creditSettlement","Pending");}
        JsonObject next=db.deepCopy(),requests=next.getAsJsonObject("requests");while(requests.size()>=512){String oldest=requests.entrySet().stream().filter(e->prunable(e.getValue().getAsJsonObject(),now)).findFirst().orElseThrow().getKey();requests.remove(oldest);}requests.add(id,r);
        if(paid&&chosen!=null){bank.saveKitbot(next,r,-1);db=next;}else save(next);return r.deepCopy();
    }
    boolean hasPendingDelivery(String worker){return db.getAsJsonObject("requests").entrySet().stream().map(e->e.getValue().getAsJsonObject()).anyMatch(r->text(r,"worker").equals(worker)&&Set.of("Pending","Queued","Running").contains(text(r,"status")));}
    List<JsonObject> pending(){
        List<JsonObject> pending=db.getAsJsonObject("requests").entrySet().stream().map(e->e.getValue().getAsJsonObject()).filter(r->text(r,"status").equals("Pending")).map(JsonObject::deepCopy).toList();
        if(pending.isEmpty()||!db.getAsJsonObject("config").has("excludedKitFamilies")||db.getAsJsonObject("config").getAsJsonArray("excludedKitFamilies").isEmpty())return pending;
        JsonObject allowed=stock();List<JsonObject> result=new ArrayList<>();
        for(JsonObject order:pending)if(allowed.has(text(order,"kitTypeId")))result.add(order);else submissionFailed(text(order,"id"),"Selected kit is excluded or no longer available to the kitbot",true);
        return result;
    }
    void state(String id,String status,String detail){JsonObject r=db.getAsJsonObject("requests").getAsJsonObject(id);if(r==null||text(r,"status").equals(status)&&text(r,"detail").equals(detail))return;JsonObject next=db.deepCopy();r=next.getAsJsonObject("requests").getAsJsonObject(id);r.addProperty("status",status);r.addProperty("detail",detail);r.remove("submission");save(next);}
    void reconcile(Map<UUID,JsonObject> tasks){reconcile(tasks,System.currentTimeMillis());}
    void reconcile(Map<UUID,JsonObject> tasks,long now){
        JsonObject availability=KitBotAvailability.reconcile(db,tasks,text(db.getAsJsonObject("config"),"worker"),now);if(availability!=null)save(availability);
        for(var e:db.getAsJsonObject("requests").entrySet()){
            JsonObject r=e.getValue().getAsJsonObject(),task=tasks.get(UUID.fromString(e.getKey()));if(task==null)continue;
            if(Set.of("Queued","Running").contains(text(r,"status"))){String status=text(task,"status"),detail=text(task,"detail");if(QueuePolicy.terminal(status)&&detail.isEmpty()&&task.has("runs"))detail=task.getAsJsonObject("runs").entrySet().stream().findFirst().map(run->text(run.getValue().getAsJsonObject(),"detail")).orElse("");state(e.getKey(),QueuePolicy.terminal(status)?status:status.equals("Running")?"Running":"Queued",detail);}
            if(!text(r,"payment").equals("Credits")||!r.has("creditsCharged")||text(r,"creditSettlement").equals("Settled")||!QueuePolicy.terminal(text(task,"status")))continue;
            JsonObject run=task.getAsJsonObject("runs").getAsJsonObject(r.has("worker")?text(r,"worker"):text(db.getAsJsonObject("config"),"worker"));
            if(run==null||!QueuePolicy.terminal(text(run,"status")))continue;
            JsonObject receipt=run.has("deliveryReceipt")?run.getAsJsonObject("deliveryReceipt"):null;
            if(receipt==null&&text(run,"status").equals("Complete")&&run.has("workflowResult")&&run.getAsJsonObject("workflowResult").has("delivered")){
                receipt=new JsonObject();receipt.addProperty("expected",r.get("count").getAsInt());receipt.addProperty("delivered",integer(run.getAsJsonObject("workflowResult"),"delivered",0,r.get("count").getAsInt()));receipt.addProperty("uncertain",false);
            }
            if(receipt==null||receipt.get("uncertain").getAsBoolean())continue;
            receipt=DeliveryReceipt.checked(receipt,r.get("count").getAsInt());settle(e.getKey(),r.get("count").getAsLong()-receipt.get("delivered").getAsLong(),receipt);
        }
        for(var entry:List.copyOf(db.getAsJsonObject("requests").entrySet())){
            JsonObject order=entry.getValue().getAsJsonObject(),task=tasks.get(UUID.fromString(entry.getKey()));if(task==null||!QueuePolicy.terminal(text(task,"status"))||order.has("timeoutNotice")||!order.has("noticeVersion"))continue;
            JsonObject run=task.getAsJsonObject("runs").getAsJsonObject(text(order,"worker"));if(run==null||!QueuePolicy.terminal(text(run,"status"))||!run.has("deliveryReceipt"))continue;
            JsonObject notice=timeoutNotice(order,DeliveryReceipt.checked(run.getAsJsonObject("deliveryReceipt"),order.get("count").getAsInt()));
            if(notice!=null){JsonObject next=db.deepCopy();next.getAsJsonObject("requests").getAsJsonObject(entry.getKey()).add("timeoutNotice",notice);save(next);}
        }
    }
    void submissionFailed(String id,String detail,boolean unsubmitted){
        JsonObject next=db.deepCopy(),order=next.getAsJsonObject("requests").getAsJsonObject(id);if(order==null)return;
        order.addProperty("status","Failed");order.addProperty("detail",detail);order.remove("submission");
        if(unsubmitted)KitBotAvailability.resolve(next,order,0,System.currentTimeMillis());
        if(unsubmitted&&text(order,"payment").equals("Credits")&&order.has("creditsCharged")&&!text(order,"creditSettlement").equals("Settled")){
            order.addProperty("creditSettlement","Settled");long refund=order.get("count").getAsLong();order.addProperty("creditsRefunded",refund);bank.saveKitbot(next,order,refund);db=next;
        }else save(next);
    }
    private void settle(String id,long refund,JsonObject receipt){
        JsonObject next=db.deepCopy(),order=next.getAsJsonObject("requests").getAsJsonObject(id);if(text(order,"creditSettlement").equals("Settled"))return;
        order.addProperty("creditSettlement","Settled");order.addProperty("creditsRefunded",refund);JsonObject notice=timeoutNotice(order,receipt);if(notice!=null)order.add("timeoutNotice",notice);bank.saveKitbot(next,order,refund);db=next;
    }
    JsonObject resolveCredit(String id,int delivered,String reason){
        JsonObject existing=db.getAsJsonObject("requests").getAsJsonObject(id);
        if(existing==null||!text(existing,"payment").equals("Credits")||!existing.has("creditsCharged")||!QueuePolicy.terminal(text(existing,"status")))throw new IllegalArgumentException("Select a finished charged credit order");
        if(delivered<0||delivered>existing.get("count").getAsInt()||reason.isBlank()||reason.length()>160||reason.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Supply confirmed delivered count and a review reason (1–160 characters)");
        long refund=existing.get("count").getAsLong()-delivered;
        if(text(existing,"creditSettlement").equals("Settled")){if(existing.get("creditsRefunded").getAsLong()!=refund)throw new IllegalStateException("This credit order already has a different final settlement");return settlementView(existing);}
        JsonObject next=db.deepCopy(),order=next.getAsJsonObject("requests").getAsJsonObject(id);order.addProperty("creditSettlement","Settled");order.addProperty("creditsRefunded",refund);order.addProperty("creditSettlementReason",reason);order.addProperty("creditSettlementActor","host-administrator");KitBotAvailability.resolve(next,order,delivered,System.currentTimeMillis());bank.saveKitbot(next,order,refund);db=next;return settlementView(order);
    }
    private static JsonObject timeoutNotice(JsonObject order,JsonObject receipt){
        if(!order.has("noticeVersion")||order.has("timeoutNotice")||!text(receipt,"failureReason").equals("tpa_timeout"))return null;
        boolean paid=text(order,"payment").equals("Credits");if(paid&&(!text(order,"creditSettlement").equals("Settled")||order.get("creditsRefunded").getAsLong()!=order.get("creditsCharged").getAsLong()))return null;
        JsonObject n=new JsonObject();n.addProperty("id",UUID.nameUUIDFromBytes(("tpa-timeout-notice:"+text(order,"id")).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        for(String key:List.of("worker","crew","scope","name"))n.add(key,order.get(key).deepCopy());
        n.addProperty("text","The TPA to "+text(order,"recipientName")+" timed out after 60s. "+(paid?"Your credits were refunded. ":"No credits were spent. ")+"Any cooldown still applies.");return n;
    }
    List<JsonObject> pendingNotices(long now){return db.getAsJsonObject("requests").entrySet().stream().map(e->e.getValue().getAsJsonObject()).filter(r->r.has("timeoutNotice")).map(r->r.getAsJsonObject("timeoutNotice")).filter(n->!n.has("sentAt")&&(!n.has("attemptAt")||now-n.get("attemptAt").getAsLong()>=30_000)).map(JsonObject::deepCopy).toList();}
    void noticeAttempted(String id,long now){noticeUpdate(id,"attemptAt",now,null,null,null,null);}
    void noticeSent(String id,String worker,String crew,String scope,String command,long now){noticeUpdate(id,"sentAt",now,worker,crew,scope,command);}
    private void noticeUpdate(String id,String key,long now,String worker,String crew,String scope,String command){
        for(var entry:db.getAsJsonObject("requests").entrySet()){
            JsonObject order=entry.getValue().getAsJsonObject();if(!order.has("timeoutNotice"))continue;JsonObject n=order.getAsJsonObject("timeoutNotice");if(!text(n,"id").equals(id)||n.has("sentAt"))continue;
            if(key.equals("sentAt")&&!n.has("attemptAt"))return;
            if(worker!=null&&(!text(n,"worker").equals(worker)||!text(n,"crew").equals(crew)||!BotChat.commandScope(command,text(n,"scope"),scope)||!MikeKitHandler.whisper(text(n,"name"),text(n,"text")).equals(command)))return;
            JsonObject next=db.deepCopy();next.getAsJsonObject("requests").getAsJsonObject(entry.getKey()).getAsJsonObject("timeoutNotice").addProperty(key,now);save(next);return;
        }
    }
    private static JsonObject settlementView(JsonObject order){JsonObject out=new JsonObject();for(String key:List.of("id","payment","creditsCharged","creditsRefunded","creditSettlement"))if(order.has(key))out.add(key,order.get(key).deepCopy());return out;}
    private void privateFile(){try{if(Files.getFileStore(file).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));}catch(IOException e){throw new IllegalStateException("Cannot restrict kitbot settings file permissions");}}
    private void save(JsonObject next){if(bank!=null)bank.saveKitbot(next,null,0);else{TaskFiles.write(file,next);privateFile();}db=next;}
}
