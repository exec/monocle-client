package dev.monocle.host;

import com.google.gson.*;
import dev.monocle.coordinator.*;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.*;

/** A category's single delivery slot is reserved before dispatch and timed from confirmed handoff. */
final class KitBotAvailability {
    static Set<String> matching(JsonObject db,JsonObject categories,String id,JsonObject kit){
        Set<String> result=new LinkedHashSet<>();JsonObject config=db.getAsJsonObject("config");
        if(!config.has("categoryCooldownSeconds"))return result;
        for(var value:categories.entrySet()){
            JsonObject category=value.getValue().getAsJsonObject();String slug=text(category,"slug");
            if(config.getAsJsonObject("categoryCooldownSeconds").has(slug)&&KitCategories.usedFor(category,"Kitbot")&&KitCategories.matches(category,id,kit.getAsJsonObject("items")))result.add(slug);
        }
        return result;
    }
    private static boolean held(JsonObject db,String slug){
        return db.getAsJsonObject("requests").entrySet().stream().map(e->e.getValue().getAsJsonObject())
            .anyMatch(r->r.has("limitedCategories")&&r.getAsJsonArray("limitedCategories").asList().stream().anyMatch(v->v.getAsString().equals(slug))
                &&!Set.of("Delivered","Released").contains(text(r,"limitedDeliveryState")));
    }
    static long until(JsonObject db,String slug){
        JsonObject config=db.getAsJsonObject("config");
        if(!config.has("categoryCooldownSeconds")||!config.getAsJsonObject("categoryCooldownSeconds").has(slug)||!db.has("categoryDeliveries")||!db.getAsJsonObject("categoryDeliveries").has(slug))return 0;
        return db.getAsJsonObject("categoryDeliveries").get(slug).getAsLong()+config.getAsJsonObject("categoryCooldownSeconds").get(slug).getAsLong()*1000;
    }
    static JsonObject stock(JsonObject db,JsonObject categories,JsonObject stock,long now){
        if(!db.getAsJsonObject("config").has("categoryCooldownSeconds")||db.getAsJsonObject("config").getAsJsonObject("categoryCooldownSeconds").isEmpty())return stock;
        Set<String> blocked=new HashSet<>();for(String slug:db.getAsJsonObject("config").getAsJsonObject("categoryCooldownSeconds").keySet())if(held(db,slug)||until(db,slug)>now)blocked.add(slug);
        if(blocked.isEmpty())return stock;
        JsonObject result=stock.deepCopy();
        for(var entry:result.entrySet())if(matching(db,categories,entry.getKey(),entry.getValue().getAsJsonObject()).stream().anyMatch(blocked::contains))entry.getValue().getAsJsonObject().addProperty("complete",0);
        return result;
    }
    static JsonObject rejection(JsonObject db,JsonObject categories,JsonObject kit,String name,int quantity,long now){
        if(kit==null)return null;
        Set<String> limited=matching(db,categories,text(kit,"id"),kit);if(limited.isEmpty())return null;
        String message=null;
        if(quantity>1)message="This limited category allows only one kit per delivery, shared by everyone. Request one kit.";
        else for(String slug:limited){
            if(held(db,slug)){message="That limited kit is reserved by an ongoing or unconfirmed delivery. Please try again later.";break;}
            long remaining=until(db,slug)-now;if(remaining>0){long seconds=(remaining+999)/1000;message="That limited kit is out of stock for everyone. Available again in "+seconds/3600+"h "+seconds/60%60+"m "+seconds%60+"s.";break;}
        }
        if(message==null)return null;
        JsonObject reply=new JsonObject();reply.addProperty("name",name);reply.addProperty("status","Listing");reply.addProperty("text",message);return reply;
    }
    static void reserve(JsonObject db,JsonObject categories,JsonObject kit,JsonObject order){
        Set<String> limited=matching(db,categories,text(kit,"id"),kit);if(limited.isEmpty())return;
        JsonArray slugs=new JsonArray();limited.forEach(slugs::add);order.add("limitedCategories",slugs);order.addProperty("limitedDeliveryState","Reserved");
    }
    static boolean unresolved(JsonObject order){return order.has("limitedCategories")&&!Set.of("Delivered","Released").contains(text(order,"limitedDeliveryState"));}
    static void resolve(JsonObject db,JsonObject order,int delivered,long now){
        if(!unresolved(order))return;
        order.addProperty("limitedDeliveryState",delivered>0?"Delivered":"Released");
        if(delivered>0){
            if(!db.has("categoryDeliveries"))db.add("categoryDeliveries",new JsonObject());
            for(JsonElement slug:order.getAsJsonArray("limitedCategories"))db.getAsJsonObject("categoryDeliveries").addProperty(slug.getAsString(),Math.max(now,db.getAsJsonObject("categoryDeliveries").has(slug.getAsString())?db.getAsJsonObject("categoryDeliveries").get(slug.getAsString()).getAsLong():0));
            order.addProperty("limitedDeliveredAt",now);
        }
    }
    static JsonObject reconcile(JsonObject db,Map<UUID,JsonObject> tasks,String defaultWorker,long now){
        JsonObject next=null;
        for(var entry:db.getAsJsonObject("requests").entrySet()){
            JsonObject order=entry.getValue().getAsJsonObject();if(!unresolved(order))continue;
            JsonObject task=tasks.get(UUID.fromString(entry.getKey()));if(task==null)continue;
            JsonObject run=task.getAsJsonObject("runs").getAsJsonObject(order.has("worker")?text(order,"worker"):defaultWorker);if(run==null)continue;
            JsonObject receipt=run.has("deliveryReceipt")?DeliveryReceipt.checked(run.getAsJsonObject("deliveryReceipt"),order.get("count").getAsInt()):null;
            Integer delivered=null;boolean uncertain=false;
            if(receipt!=null&&receipt.get("delivered").getAsInt()>0)delivered=1;
            else if(QueuePolicy.terminal(text(run,"status"))&&QueuePolicy.terminal(text(task,"status"))){
                if(receipt!=null&&!flag(receipt,"uncertain"))delivered=0;
                else if(receipt==null&&text(run,"status").equals("Complete")&&run.has("workflowResult")&&run.getAsJsonObject("workflowResult").has("delivered"))
                    delivered=StashCatalog.integer(run.getAsJsonObject("workflowResult"),"delivered",0,order.get("count").getAsInt());
                else uncertain=!text(order,"limitedDeliveryState").equals("Uncertain");
            }
            if(delivered==null&&!uncertain)continue;
            if(next==null)next=db.deepCopy();JsonObject changed=next.getAsJsonObject("requests").getAsJsonObject(entry.getKey());
            if(delivered!=null)resolve(next,changed,delivered,now);else changed.addProperty("limitedDeliveryState","Uncertain");
        }
        return next;
    }
}
