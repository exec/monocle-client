package dev.monocle.host;

import com.google.gson.*;
import dev.monocle.coordinator.*;
import java.nio.file.*;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.*;

final class KitBotMinimumsCheck {
    private static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    static void run()throws Exception{
        Path root=Files.createTempDirectory("synthetic-category-minimums-");String scope="synthetic.invalid\nminecraft:the_end",user="00000000-0000-0000-0000-000000000081",other="00000000-0000-0000-0000-000000000082";
        // Independently invented synthetic geometry and kit contents.
        JsonObject bounds=StashCatalog.plan(json("{name:'SyntheticDepot',homeName:'synthetic-home',minX:1100,maxX:1101,minY:92,maxY:92,minZ:2048,maxZ:2048}"));StashCatalog.define(root,"Default",scope,bounds);
        JsonObject box=json("{item:'minecraft:pink_shulker_box',name:'Synthetic trim supplies',quantity:1,contentsKnown:true,items:{'minecraft:sentry_armor_trim_smithing_template':1728}}"),category=KitCategories.definition("trims","Synthetic trims","Kits",List.of(StashCatalog.kitTypeId(box)));
        String kit=StashCatalog.kitTypeId(box);JsonObject strict=KitCategories.definition("strict-trims","Synthetic strict trims","Kits",List.of(kit));
        for(int x=1100;x<=1101;x++){
            JsonObject row=json("{y:92,z:2048,status:'observed',block:'minecraft:chest',items:{'minecraft:pink_shulker_box':42},shulkers:[]}");row.addProperty("x",x);
            for(int slot=0;slot<42;slot++){JsonObject placed=box.deepCopy();placed.addProperty("slot",slot);row.getAsJsonArray("shulkers").add(placed);}StashCatalog.save(root,"Default",scope,bounds,row);
        }
        for(JsonObject rule:List.of(category,strict))KitCategories.save(root,"Default",scope,text(KitCategories.get(root,"Default",scope),"revision"),rule);
        JsonObject config=json("{enabled:true,publicAccess:true,crew:'Default',source:'SyntheticDepot',worker:'00000000-0000-0000-0000-000000000083',minimumStock:270,cooldownSeconds:3600,whitelist:[]}");config.addProperty("scope",scope);
        OperationsLibrary library=new OperationsLibrary(root.resolve("operations.json"));
        try(CreditBank bank=new CreditBank(root)){
            KitBot bot=new KitBot(root,bank);bot.configure(config,text(bot.view(),"revision"));
            assert bot.mikeCatalog().asList().stream().noneMatch(v->flag(v.getAsJsonObject(),"inStock"));
            JsonObject custom=config.deepCopy();custom.add("categoryMinimumStock",json("{trims:54,'strict-trims':500}"));bot.configure(custom,text(bot.view(),"revision"));
            JsonObject sender=json("{name:'SyntheticUser'}");sender.addProperty("uuid",user);bot.observeSender("SyntheticUser whispers: menu",sender);
            JsonArray catalog=bot.mikeCatalog("SyntheticUser");assert catalog.asList().stream().anyMatch(v->text(v.getAsJsonObject(),"category").equals("trims")&&flag(v.getAsJsonObject(),"inStock"));
            assert catalog.asList().stream().anyMatch(v->text(v.getAsJsonObject(),"category").equals("strict-trims")&&!flag(v.getAsJsonObject(),"inStock"));
            String listing=text(bot.listing("SyntheticUser whispers: kits"),"text");assert listing.contains("Synthetic trim supplies [trims]")&&!listing.contains("strict-trims");
            assert text(bot.menu("SyntheticUser",1),"text").contains("In:trims")&&text(bot.menu("SyntheticUser",1),"text").contains("Out:strict-trims");
            JsonObject inspection=bot.inspect("SyntheticUser",json("{}"));assert inspection.getAsJsonArray("kits").size()==1&&inspection.getAsJsonArray("kits").get(0).getAsJsonObject().getAsJsonArray("categories").toString().equals("[\"trims\"]");
            assert bot.inspect("SyntheticUser",json("{category:'strict-trims'}")).getAsJsonArray("kits").isEmpty() : "A cheap category does not lower another category's floor";
            assert !flag(bot.inspect("SyntheticUser",json("{category:'strict-trims',includeUnavailable:true}")).getAsJsonArray("kits").get(0).getAsJsonObject(),"inStock");
            bank.adjust(UUID.randomUUID().toString(),user,"SyntheticUser",40,"Synthetic reward","test");
            JsonObject refused=bot.receive("SyntheticUser","strict-trims",1,KitBotContents.reference(kit),1_000_000,library,null,"Credits");assert text(refused,"status").equals("OutOfStock")&&bank.account(user).get("balance").getAsLong()==40 : "Exact references retain the requested category's stock policy";
            JsonObject paid=bot.receive("SyntheticUser","trims",27,KitBotContents.reference(kit),1_005_000,library,null,"Credits");assert text(paid,"status").equals("Pending")&&bank.account(user).get("balance").getAsLong()==13;
            JsonObject second=json("{name:'SyntheticOther'}");second.addProperty("uuid",other);bot.observeSender("SyntheticOther whispers: kit trims 1",second);
            JsonObject free=bot.receive("SyntheticOther whispers: kit trims 1",1_005_000,library);assert text(free,"status").equals("Pending")&&!bank.account(other).get("exists").getAsBoolean() : "Free and paid orders use the same override";
            bot.state(text(paid,"id"),"Complete","");bot.state(text(free,"id"),"Complete","");
            JsonObject stock=KitCategories.stock(StashCatalog.get(root,"Default",scope,"SyntheticDepot")),categories=KitCategories.get(root,"Default",scope).getAsJsonObject("categories"),pending=new JsonObject(),reservation=json("{status:'Queued',count:31}");reservation.addProperty("kitTypeId",kit);pending.add("synthetic-reservation",reservation);
            assert KitBot.mikeCatalog(categories,stock,pending,270,json("{trims:54}")).asList().stream().noneMatch(v->flag(v.getAsJsonObject(),"inStock")) : "Pending reservations can put specialties out of stock";
            stock.getAsJsonObject(kit).addProperty("complete",53);stock.getAsJsonObject(kit).addProperty("estimatedComplete",99999);
            assert KitBotContents.inspect(json("{category:'trims'}"),categories,stock,new JsonObject(),270,json("{trims:54}")).getAsJsonArray("kits").isEmpty() : "Transfer estimates never replace confirmed stock";
            JsonObject project=json("{name:'SyntheticUser'}");project.addProperty("uuid",user);custom.getAsJsonArray("whitelist").add(project);bot.configure(custom,text(bot.view(),"revision"));
            assert KitBot.categoryMinimums(custom,project).equals(json("{trims:54,'strict-trims':109}")) : "Project users retain lower specialty floors and their expanded normal access";
            JsonObject oldClient=custom.deepCopy();oldClient.remove("categoryMinimumStock");bot.configure(oldClient,text(bot.view(),"revision"));assert bot.view().getAsJsonObject("config").getAsJsonObject("categoryMinimumStock").equals(custom.getAsJsonObject("categoryMinimumStock"));
            assert new KitBot(root,bank).view().getAsJsonObject("config").getAsJsonObject("categoryMinimumStock").equals(custom.getAsJsonObject("categoryMinimumStock")) : "Overrides survive saves by old clients and host restart";
            for(String invalid:List.of("[]","null","{trims:-1}","{trims:1.5}","{trims:'54'}","{trims:1000001}","{'BAD WORD':54}")){
                JsonObject candidate=custom.deepCopy();candidate.add("categoryMinimumStock",JsonParser.parseString(invalid));
                try{bot.configure(candidate,text(bot.view(),"revision"));throw new AssertionError("Invalid stock minimum accepted: "+invalid);}catch(IllegalArgumentException expected){}
            }
            JsonArray exclusions=new JsonArray();exclusions.add(KitTypes.family(box));custom.add("excludedKitFamilies",exclusions);bot.configure(custom,text(bot.view(),"revision"));
            assert bot.mikeCatalog("SyntheticUser").asList().stream().noneMatch(v->flag(v.getAsJsonObject(),"inStock"))&&bot.inspect("SyntheticUser",json("{includeUnavailable:true}")).getAsJsonArray("kits").isEmpty() : "A lower floor never bypasses family exclusions";
            custom.add("categoryMinimumStock",new JsonObject());bot.configure(custom,text(bot.view(),"revision"));assert bot.view().getAsJsonObject("config").getAsJsonObject("categoryMinimumStock").isEmpty() : "Explicit empty settings clear overrides";
        }
        System.out.println("Category stock minimum checks passed: menus, listings, inspection, exact free/paid orders, project access, reservations, exclusions and durable settings.");
    }
}
