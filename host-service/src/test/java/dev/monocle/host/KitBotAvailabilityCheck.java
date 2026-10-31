package dev.monocle.host;

import com.google.gson.*;
import dev.monocle.coordinator.*;
import java.nio.file.*;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.*;

final class KitBotAvailabilityCheck {
    private static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    private static Map<UUID,JsonObject> task(JsonObject order,String status,int delivered,boolean uncertain){
        JsonObject run=new JsonObject();run.addProperty("status",status);run.addProperty("detail","Synthetic outcome");
        JsonObject receipt=new JsonObject();receipt.addProperty("expected",1);receipt.addProperty("delivered",delivered);receipt.addProperty("uncertain",uncertain);run.add("deliveryReceipt",receipt);
        JsonObject task=new JsonObject(),runs=new JsonObject();runs.add(text(order,"worker"),run);task.add("runs",runs);task.addProperty("status",status);return Map.of(UUID.fromString(text(order,"id")),task);
    }
    static void run()throws Exception{
        Path root=Files.createTempDirectory("synthetic-limited-kits-");long now=System.currentTimeMillis();
        String scope="synthetic.invalid\nminecraft:overworld",first="00000000-0000-0000-0000-000000000091",second="00000000-0000-0000-0000-000000000092";
        // Independently invented synthetic geometry and contents; no real map art or stash data.
        JsonObject bounds=json("{name:'SyntheticArtDepot',homeName:'synthetic-home',minX:1760,maxX:1760,minY:84,maxY:84,minZ:-2560,maxZ:-2560}");StashCatalog.define(root,"Default",scope,bounds);
        JsonObject box=json("{item:'minecraft:green_shulker_box',name:'Synthetic art kit',quantity:1,contentsKnown:true,items:{'minecraft:filled_map':27}}"),row=json("{x:1760,y:84,z:-2560,status:'observed',block:'minecraft:chest',capacity:27,slotCount:27,occupiedSlots:3,items:{'minecraft:green_shulker_box':3},shulkers:[]}");
        for(int i=0;i<3;i++){JsonObject copy=box.deepCopy();copy.addProperty("slot",i);row.getAsJsonArray("shulkers").add(copy);}StashCatalog.save(root,"Default",scope,bounds,row);
        String id=StashCatalog.kitTypeId(box);JsonObject categories=new JsonObject();
        for(String slug:List.of("synthetic-art","synthetic-alias")){
            JsonObject category=KitCategories.definition(slug,"Synthetic art","Kits",List.of(id));KitCategories.save(root,"Default",scope,text(KitCategories.get(root,"Default",scope),"revision"),category);categories.add(text(category,"id"),category);
        }
        JsonObject config=json("{enabled:true,publicAccess:true,crew:'Default',source:'SyntheticArtDepot',worker:'00000000-0000-0000-0000-000000000093',minimumStock:270,cooldownSeconds:3600,whitelist:[{name:'SyntheticOne',uuid:'00000000-0000-0000-0000-000000000091',cooldownSeconds:0,maxKits:27},{name:'SyntheticTwo',uuid:'00000000-0000-0000-0000-000000000092',cooldownSeconds:0,maxKits:27}],categoryMinimumStock:{'synthetic-art':0,'synthetic-alias':0},categoryCooldownSeconds:{'synthetic-art':86400}}");config.addProperty("scope",scope);config.addProperty("backupWorker","00000000-0000-0000-0000-000000000095");
        OperationsLibrary library=new OperationsLibrary(root.resolve("operations.json"));
        try(CreditBank bank=new CreditBank(root)){
            bank.adjust(UUID.randomUUID().toString(),first,"SyntheticOne",10,"Synthetic credits","test");bank.adjust(UUID.randomUUID().toString(),second,"SyntheticTwo",10,"Synthetic credits","test");
            KitBot bot=new KitBot(root,bank);bot.configure(config,text(bot.view(),"revision"));
            assert bot.mikeCatalog("SyntheticOne").asList().stream().allMatch(v->flag(v.getAsJsonObject(),"inStock"));
            JsonObject zeroStock=KitCategories.stock(StashCatalog.get(root,"Default",scope,"SyntheticArtDepot"));zeroStock.getAsJsonObject(id).addProperty("complete",0);zeroStock.getAsJsonObject(id).addProperty("estimatedComplete",9999);
            assert KitBot.mikeCatalog(categories,zeroStock,new JsonObject(),270,config.getAsJsonObject("categoryMinimumStock")).asList().stream().noneMatch(v->flag(v.getAsJsonObject(),"inStock"));
            assert KitBotContents.inspect(json("{}"),categories,zeroStock,new JsonObject(),270,config.getAsJsonObject("categoryMinimumStock")).getAsJsonArray("kits").isEmpty() : "Zero minimum still needs an actual complete kit";
            JsonObject excessive=bot.receive("SyntheticOne","synthetic-art",2,"",now,library,null,"Credits");assert text(excessive,"status").equals("Listing")&&text(excessive,"text").contains("one kit")&&bot.pending().isEmpty()&&bank.account(first).get("balance").getAsInt()==10;
            JsonObject paid=bot.receive("SyntheticOne","synthetic-art",1,"",now,library,null,"Credits");assert text(paid,"status").equals("Pending")&&bank.account(first).get("balance").getAsInt()==9;
            JsonObject other=bot.receive("SyntheticTwo","synthetic-alias",1,KitBotContents.reference(id),now,library,null,"Credits","00000000-0000-0000-0000-000000000095");assert text(other,"status").equals("Listing")&&text(other,"text").contains("reserved")&&bank.account(second).get("balance").getAsInt()==10 : "Aliases, exact references, paid orders and project users share one slot";
            JsonObject rawStock=KitCategories.stock(StashCatalog.get(root,"Default",scope,"SyntheticArtDepot")),unrelated=rawStock.getAsJsonObject(id).deepCopy();String unrelatedId="00000000-0000-0000-0000-000000000094";unrelated.addProperty("id",unrelatedId);rawStock.add(unrelatedId,unrelated);
            JsonObject gated=KitBotAvailability.stock(bot.view(),categories,rawStock,now);assert gated.getAsJsonObject(id).get("complete").getAsInt()==0&&gated.getAsJsonObject(unrelatedId).get("complete").getAsInt()==3 : "Limited availability does not block unrelated stock";
            assert bot.mikeCatalog("SyntheticTwo").asList().stream().noneMatch(v->flag(v.getAsJsonObject(),"inStock"));
            assert bot.inspect("SyntheticTwo",json("{}")).getAsJsonArray("kits").isEmpty();
            assert bot.inspect("SyntheticTwo",json("{includeUnavailable:true}")).getAsJsonArray("kits").asList().stream().noneMatch(v->flag(v.getAsJsonObject(),"inStock"));
            assert text(bot.listing("SyntheticTwo whispers: kits"),"text").contains("No kits in stock");
            JsonObject oldClient=config.deepCopy();oldClient.remove("categoryCooldownSeconds");bot.configure(oldClient,text(bot.view(),"revision"));
            bot=new KitBot(root,bank);assert bot.view().getAsJsonObject("config").getAsJsonObject("categoryCooldownSeconds").equals(config.getAsJsonObject("categoryCooldownSeconds"));
            assert text(bot.receive("SyntheticTwo","synthetic-art",1,"",now+6000,library),"status").equals("Listing") : "Restart cannot erase an accepted reservation";
            bot.state(text(paid,"id"),"Running","");bot.reconcile(task(paid,"Failed",0,false),now+7000);assert bank.account(first).get("balance").getAsInt()==10;
            JsonObject accepted=bot.receive("SyntheticOne","synthetic-art",1,"",now+10000,library);assert text(accepted,"status").equals("Pending") : "Confirmed zero-delivery failure releases the global slot";
            bot.state(text(accepted,"id"),"Running","");bot.reconcile(task(accepted,"Running",1,false),now+12000);long deliveredAt=bot.view().getAsJsonObject("categoryDeliveries").get("synthetic-art").getAsLong();assert deliveredAt==now+12000;
            bot.reconcile(task(accepted,"Failed",1,false),now+20000);assert bot.view().getAsJsonObject("categoryDeliveries").get("synthetic-art").getAsLong()==deliveredAt : "Return-home failure cannot reset or extend a confirmed handoff";
            bot=new KitBot(root,bank);long availableAt=deliveredAt+86_400_000;
            assert text(bot.receive("SyntheticTwo","synthetic-art",1,"",availableAt-1,library),"status").equals("Listing");
            JsonObject next=bot.receive("SyntheticTwo","synthetic-alias",1,"",availableAt,library);assert text(next,"status").equals("Pending") : "Available exactly 24h after handoff, not acceptance";
            bot.submissionFailed(text(next,"id"),"Synthetic pre-dispatch failure",true);
            JsonObject uncertain=bot.receive("SyntheticOne","synthetic-art",1,"",availableAt+6000,library,null,"Credits");assert text(uncertain,"status").equals("Pending");
            bot.state(text(uncertain,"id"),"Running","");bot.reconcile(task(uncertain,"Failed",0,true),availableAt+7000);assert KitBotAvailability.unresolved(bot.view().getAsJsonObject("requests").getAsJsonObject(text(uncertain,"id")));
            assert text(bot.receive("SyntheticTwo","synthetic-art",1,"",availableAt+2*86_400_000,library),"text").contains("reserved") : "Uncertain handoffs remain held beyond the nominal interval";
            bot.resolveCredit(text(uncertain,"id"),0,"Synthetic confirmed no handoff");assert bank.account(first).get("balance").getAsInt()==10;
            JsonObject recovered=bot.receive("SyntheticTwo","synthetic-art",1,"",availableAt+2*86_400_000+6000,library);assert text(recovered,"status").equals("Pending");bot.submissionFailed(text(recovered,"id"),"Synthetic never submitted",true);
            JsonObject durable=bot.view();durable.getAsJsonObject("requests").entrySet().clear();assert KitBotAvailability.until(durable,"synthetic-art")==availableAt : "Delivery history pruning cannot erase a category timer";
            for(String invalid:List.of("[]","null","{'synthetic-art':0}","{'synthetic-art':-1}","{'synthetic-art':1.5}","{'synthetic-art':'86400'}","{'synthetic-art':2592001}","{'BAD WORD':1}")){
                JsonObject candidate=config.deepCopy();candidate.add("categoryCooldownSeconds",JsonParser.parseString(invalid));try{bot.configure(candidate,text(bot.view(),"revision"));throw new AssertionError("Invalid limited cooldown accepted: "+invalid);}catch(IllegalArgumentException expected){}
            }
            JsonObject cleared=config.deepCopy();cleared.add("categoryCooldownSeconds",new JsonObject());bot.configure(cleared,text(bot.view(),"revision"));assert bot.view().getAsJsonObject("config").getAsJsonObject("categoryCooldownSeconds").isEmpty();
        }
        System.out.println("Limited kit availability passed: shared free/paid/alias/exact access, zero floors, admission holds, handoff timing, failure recovery, uncertain review and SQLite restart.");
    }
}
