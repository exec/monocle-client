package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Content families distinguish unrelated kits; counts still use the fullest observed exemplar. */
public final class KitTypes {
    private KitTypes() { }
    public static String family(JsonObject box){
        if(!TaskWire.text(box,"item").endsWith("shulker_box"))return "";
        return uuid("monocle-kit-v1\n"+TaskWire.text(box,"item")+"\n"+TaskWire.text(box,"name"));
    }
    private static String uuid(String value){return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();}
    public static String id(JsonObject box){
        if(!TaskWire.flag(box,"contentsKnown"))return "";
        if(box.has("kitTypeId"))return UUID.fromString(TaskWire.text(box,"kitTypeId")).toString();
        String family=family(box);return family.isEmpty()?"":uuid("monocle-kit-v2\n"+family+"\n"+String.join("\n",new TreeSet<>(box.getAsJsonObject("items").keySet())));
    }
    private static int overlap(JsonObject a,JsonObject b){int count=0;for(String key:a.keySet())if(b.has(key)&&a.get(key).getAsLong()>0&&b.get(key).getAsLong()>0)count++;return count;}
    static long fullness(JsonObject items){long count=0;for(var e:items.entrySet())count+=e.getValue().getAsLong();return count;}
    public static String resolve(JsonObject types,JsonObject box){
        JsonObject raw=box.deepCopy();raw.remove("kitTypeId");String exact=id(raw);if(exact.isEmpty()||types.has(exact))return exact;
        String family=family(raw),best="";int score=0;
        // shortcut: item overlap groups related partial kits; explicit signatures are needed to split shared-item variants.
        for(var e:types.entrySet()){
            JsonObject type=e.getValue().getAsJsonObject();if(!family.equals(TaskWire.text(type,"familyId")))continue;
            int shared=overlap(type.getAsJsonObject("items"),raw.getAsJsonObject("items"));
            if(shared>score||shared>0&&shared==score&&(best.isEmpty()||e.getKey().compareTo(best)<0)){best=e.getKey();score=shared;}
        }
        return best.isEmpty()?exact:best;
    }
    public static void learn(JsonObject db,JsonObject observation){
        learn(db,observation,null);
    }
    /** Only a validated host-owned deposit plan may supply classification context. */
    static void learn(JsonObject db,JsonObject observation,JsonObject criteria){
        if(!TaskWire.text(observation,"status").equals("observed")||TaskWire.flag(observation,"inferred"))return;
        JsonObject types=db.getAsJsonObject("kitTypes");
        for(JsonElement value:observation.getAsJsonArray("shulkers")){
            JsonObject box=value.getAsJsonObject();String id=criteria!=null&&matches(box,criteria)?canonical(db,TaskWire.text(criteria,"kitTypeId")):resolve(types,box);if(id.isEmpty())continue;box.addProperty("kitTypeId",id);
            if(db.has("kitAliases")&&!db.getAsJsonObject("kitAliases").has(family(box)))db.getAsJsonObject("kitAliases").addProperty(family(box),id);
            JsonObject items=box.getAsJsonObject("items"),old=types.has(id)?types.getAsJsonObject(id):null;
            if(old!=null&&fullness(old.getAsJsonObject("items"))>=fullness(items))continue;
            JsonObject type=new JsonObject();type.addProperty("id",id);type.addProperty("familyId",family(box));
            for(String field:List.of("item","name"))type.addProperty(field,TaskWire.text(box,field));type.add("items",items.deepCopy());type.addProperty("learnedAt",db.has("kitIdentityVersion")?System.currentTimeMillis():observation.has("observedAt")?observation.get("observedAt").getAsLong():0);types.add(id,type);
        }
    }
    public static void ensure(JsonObject db){
        if(db.isEmpty()||!db.has("containers"))return;
        if(db.has("kitIdentityVersion")&&db.get("kitIdentityVersion").getAsInt()==2)return;
        JsonObject old=db.has("kitTypes")?db.getAsJsonObject("kitTypes"):new JsonObject(),types=new JsonObject(),aliases=new JsonObject();
        for(var e:old.entrySet()){
            JsonObject type=e.getValue().getAsJsonObject().deepCopy();type.addProperty("contentsKnown",true);type.remove("kitTypeId");String id=id(type);if(id.isEmpty())continue;
            type.remove("contentsKnown");type.addProperty("id",id);type.addProperty("familyId",family(type));types.add(id,type);aliases.addProperty(e.getKey(),id);
        }
        db.add("kitTypes",types);db.add("kitAliases",aliases);
        for(var e:db.getAsJsonObject("containers").entrySet())learn(db,e.getValue().getAsJsonObject());
        for(var e:db.getAsJsonObject("containers").entrySet())for(JsonElement v:e.getValue().getAsJsonObject().getAsJsonArray("shulkers")){
            JsonObject box=v.getAsJsonObject();String id=resolve(types,box);if(!id.isEmpty())box.addProperty("kitTypeId",id);
        }
        for(String field:List.of("columnReservations","fullColumns"))if(db.has(field))for(var e:db.getAsJsonObject(field).entrySet())references(db,e.getValue().getAsJsonObject());
        for(var e:db.getAsJsonObject("containers").entrySet())references(db,e.getValue().getAsJsonObject());
        db.addProperty("kitIdentityVersion",2);
    }
    private static void references(JsonObject db,JsonObject object){
        if(object.has("kitTypeId"))object.addProperty("kitTypeId",canonical(db,TaskWire.text(object,"kitTypeId")));
        for(String field:List.of("columnKitTypeIds","withdrawnKitTypes"))if(object.has(field)){JsonArray values=new JsonArray();for(JsonElement id:object.getAsJsonArray(field))values.add(canonical(db,id.getAsString()));object.add(field,values);}
    }
    public static String canonical(JsonObject db,String id){return db.has("kitAliases")&&db.getAsJsonObject("kitAliases").has(id)?db.getAsJsonObject("kitAliases").get(id).getAsString():id;}
    /** Link a foreign stash's related kit ID to this stash's existing fullest exemplar. */
    static boolean bind(JsonObject db,JsonObject criteria){
        String id=TaskWire.text(criteria,"kitTypeId");if(!criteria.has("kitIdentities")||!criteria.getAsJsonObject("kitIdentities").has(id)||db.getAsJsonObject("kitTypes").has(canonical(db,id)))return false;
        JsonObject descriptor=criteria.getAsJsonObject("kitIdentities").getAsJsonObject(id);String best="";int score=0;
        for(var e:db.getAsJsonObject("kitTypes").entrySet()){
            JsonObject type=e.getValue().getAsJsonObject();if(!TaskWire.text(descriptor,"familyId").equals(TaskWire.text(type,"familyId")))continue;
            int shared=overlap(type.getAsJsonObject("items"),criteria.getAsJsonObject("kitExemplar"));if(shared>score||shared>0&&shared==score&&(best.isEmpty()||e.getKey().compareTo(best)<0)){best=e.getKey();score=shared;}
        }
        if(best.isEmpty())return false;db.getAsJsonObject("kitAliases").addProperty(id,best);return true;
    }
    public static JsonObject identities(JsonObject types){
        JsonObject result=new JsonObject();for(var e:types.entrySet()){JsonObject identity=new JsonObject(),type=e.getValue().getAsJsonObject(),keys=new JsonObject();identity.addProperty("familyId",TaskWire.text(type,"familyId"));type.getAsJsonObject("items").keySet().forEach(k->keys.addProperty(k,1));identity.add("items",keys);result.add(e.getKey(),identity);}return result;
    }
    /** Reconstruct reporting variants from real box contents, not historical transfer aliases. */
    public static JsonObject stockVariants(JsonObject db,String family){
        Map<String,JsonObject> exemplars=new LinkedHashMap<>();List<JsonObject> boxes=new ArrayList<>();long unknown=0;
        for(var type:db.getAsJsonObject("kitTypes").entrySet()){
            JsonObject candidate=type.getValue().getAsJsonObject().deepCopy();if(!family.equals(family(candidate)))continue;
            candidate.remove("kitTypeId");candidate.addProperty("contentsKnown",true);String signature=id(candidate);JsonObject prior=exemplars.get(signature);
            if(prior==null||fullness(candidate.getAsJsonObject("items"))>fullness(prior.getAsJsonObject("items")))exemplars.put(signature,candidate);
        }
        for(var entry:db.getAsJsonObject("containers").entrySet()){
            JsonObject container=entry.getValue().getAsJsonObject();if(!TaskWire.text(container,"status").equals("observed")||TaskWire.flag(container,"inferred"))continue;
            for(JsonElement value:container.getAsJsonArray("shulkers")){
                JsonObject box=value.getAsJsonObject();if(!family.equals(family(box)))continue;
                if(!TaskWire.flag(box,"contentsKnown")){unknown+=box.get("quantity").getAsLong();continue;}
                boxes.add(box);JsonObject raw=box.deepCopy();raw.remove("kitTypeId");String signature=id(raw);JsonObject prior=exemplars.get(signature);
                if(prior==null||fullness(raw.getAsJsonObject("items"))>fullness(prior.getAsJsonObject("items")))exemplars.put(signature,raw);
            }
        }
        Map<String,JsonObject> variants=new LinkedHashMap<>();Map<String,Set<String>> catalogIds=new LinkedHashMap<>();
        Map<String,List<String>> parents=new HashMap<>();
        for(JsonObject box:boxes){
            JsonObject raw=box.deepCopy();raw.remove("kitTypeId");String rawSignature=id(raw);
            List<String> maximal=parents.computeIfAbsent(rawSignature,ignored->{
                Set<String> keys=box.getAsJsonObject("items").keySet();List<String> candidates=new ArrayList<>();
                for(var e:exemplars.entrySet())if(e.getValue().getAsJsonObject("items").keySet().containsAll(keys)
                    && (!keys.isEmpty()||e.getValue().getAsJsonObject("items").isEmpty()))candidates.add(e.getKey());
                // A missing-item subset has a parent only when one maximal compatible signature exists.
                return candidates.stream().filter(a->candidates.stream().noneMatch(b->!a.equals(b)
                && exemplars.get(b).getAsJsonObject("items").size()>exemplars.get(a).getAsJsonObject("items").size()
                && exemplars.get(b).getAsJsonObject("items").keySet().containsAll(exemplars.get(a).getAsJsonObject("items").keySet()))).toList();
            });
            boolean ambiguous=maximal.size()!=1;String signature=ambiguous?rawSignature:maximal.getFirst();
            JsonObject exemplar=exemplars.get(signature),variant=variants.get(signature);
            if(variant==null){
                variant=new JsonObject();variant.addProperty("signatureId",signature);variant.add("items",exemplar.get("items").deepCopy());
                for(String key:List.of("complete","incomplete","ambiguous","boxes"))variant.addProperty(key,0L);variants.put(signature,variant);catalogIds.put(signature,new LinkedHashSet<>());
            }
            long quantity=box.get("quantity").getAsLong();String field=ambiguous?"ambiguous":StashCatalog.kitIncomplete(exemplar.getAsJsonObject("items"),box.getAsJsonObject("items"))?"incomplete":"complete";
            variant.addProperty(field,variant.get(field).getAsLong()+quantity);variant.addProperty("boxes",variant.get("boxes").getAsLong()+quantity);
            String assigned=TaskWire.text(box,"kitTypeId");if(!assigned.isEmpty())catalogIds.get(signature).add(assigned);
        }
        JsonObject result=new JsonObject();JsonArray rows=new JsonArray();long complete=0,incomplete=0,ambiguous=0,total=0;
        for(var entry:variants.entrySet()){
            JsonObject variant=entry.getValue();JsonArray ids=new JsonArray();catalogIds.get(entry.getKey()).forEach(ids::add);variant.add("catalogKitTypeIds",ids);rows.add(variant);
            complete+=variant.get("complete").getAsLong();incomplete+=variant.get("incomplete").getAsLong();ambiguous+=variant.get("ambiguous").getAsLong();total+=variant.get("boxes").getAsLong();
        }
        result.add("variants",rows);result.addProperty("complete",complete);result.addProperty("incomplete",incomplete);result.addProperty("ambiguous",ambiguous);result.addProperty("unknownContents",unknown);result.addProperty("recordedBoxes",total+unknown);return result;
    }
    public static String identity(JsonObject box,JsonObject criteria){
        if(!TaskWire.flag(box,"contentsKnown"))return "";
        JsonObject raw=box.deepCopy();raw.remove("kitTypeId");
        if(criteria.has("kitTypeId")&&criteria.has("kitExemplar")&&criteria.has("kitIdentities")){
            String target=TaskWire.text(criteria,"kitTypeId");JsonObject types=criteria.getAsJsonObject("kitIdentities");
            if(types.has(target)&&family(raw).equals(TaskWire.text(types.getAsJsonObject(target),"familyId"))&&overlap(criteria.getAsJsonObject("kitExemplar"),raw.getAsJsonObject("items"))>0)return target;
        }
        if(criteria.has("kitTypeId")&&criteria.has("kitExemplar")&&TaskWire.text(criteria,"kitTypeId").equals(family(raw))&&overlap(criteria.getAsJsonObject("kitExemplar"),raw.getAsJsonObject("items"))>0)return TaskWire.text(criteria,"kitTypeId");
        if(criteria.has("kitIdentities")){String id=resolve(criteria.getAsJsonObject("kitIdentities"),raw);if(criteria.getAsJsonObject("kitIdentities").has(id))return id;}
        if(criteria.has("kitTypeId")&&criteria.has("kitExemplar")){
            String target=TaskWire.text(criteria,"kitTypeId");JsonObject exemplar=raw.deepCopy();exemplar.add("items",criteria.get("kitExemplar"));
            if((target.equals(family(raw))||target.equals(id(exemplar)))&&(overlap(criteria.getAsJsonObject("kitExemplar"),raw.getAsJsonObject("items"))>0||criteria.getAsJsonObject("kitExemplar").isEmpty()&&raw.getAsJsonObject("items").isEmpty()))return target;
        }
        return id(raw);
    }
    public static boolean matches(JsonObject box,JsonObject criteria){return TaskWire.text(criteria,"kitTypeId").equals(identity(box,criteria));}
    /** Relocation cannot relabel an overlapping same-name kit just because it contains one requested item. */
    public static String sortIdentity(JsonObject box,JsonObject types){
        JsonObject raw=box.deepCopy();raw.remove("kitTypeId");String exact=id(raw);if(exact.isEmpty()||types.has(exact))return exact;
        Set<String> keys=raw.getAsJsonObject("items").keySet();if(keys.isEmpty())return "";String family=family(raw),found="";
        for(var e:types.entrySet())if(family.equals(TaskWire.text(e.getValue().getAsJsonObject(),"familyId"))&&e.getValue().getAsJsonObject().getAsJsonObject("items").keySet().containsAll(keys)){if(!found.isEmpty())return "";found=e.getKey();}return found;
    }
}
