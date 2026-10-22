package dev.monocle.host;

import com.google.gson.*;
import dev.monocle.coordinator.*;
import java.nio.file.*;
import java.util.*;

final class KitBotCheck {
    static void run() throws Exception {
        directMessagesCheck();
        assert Arrays.equals(KitBot.parse("[unknown player head] iotacism whispers: kit pvp 2"),new String[]{"iotacism","pvp","2"});
        assert Arrays.equals(KitBot.parse("iotacism whispers: kit obsidian"),new String[]{"iotacism","obsidian","1"});
        for(String raw:List.of("iotacism » kit pvp 2","someone whispers: iotacism whispers: kit pvp 2","You whisper to iotacism: kit pvp 2","iotacism whispers: kit pvp 37","iotacism whispers: kit pvp 0","iotacism whispers: kit pvp 2\n/home main","iotacism whispers: thanks"))assert KitBot.parse(raw)==null:raw;
        JsonObject type=new JsonObject(),items=new JsonObject();String id=UUID.randomUUID().toString();items.addProperty("minecraft:obsidian",1728);type.add("items",items);type.addProperty("id",id);type.addProperty("complete",250);JsonObject stock=new JsonObject();stock.add(id,type);JsonObject rule=KitCategories.definition("obsidian","Obsidian","Items",List.of("minecraft:obsidian")),requests=new JsonObject();
        assert KitBot.select(rule,stock,requests,250,36)!=null;
        rule.addProperty("usage","Storage");assert KitBot.select(rule,stock,requests,250,1)==null;rule.addProperty("usage","Kitbot");assert KitBot.select(rule,stock,requests,250,1)!=null;rule.remove("usage");
        JsonObject pending=new JsonObject();pending.addProperty("status","Queued");pending.addProperty("kitTypeId",id);pending.addProperty("count",1);requests.add(UUID.randomUUID().toString(),pending);assert KitBot.select(rule,stock,requests,250,1)==null;
        pending.addProperty("status","Complete");assert KitBot.select(rule,stock,requests,250,1)!=null;type.addProperty("complete",249);assert KitBot.select(rule,stock,requests,250,1)==null;
        Path root=Files.createTempDirectory("kitbot-");KitBot bot=new KitBot(root);JsonObject config=JsonParser.parseString("{enabled:true,crew:'Default',scope:'server\\nminecraft:the_end',source:'main',worker:'00000000-0000-0000-0000-000000000001',minimumStock:250,cooldownSeconds:86400,whitelist:[{name:'iotacism',uuid:'ebe1d0a2-a08f-4223-8a92-8d50b6431dc4'}]}").getAsJsonObject();
        bot.configure(config,TaskWire.text(bot.view(),"revision"));assert bot.receiver("00000000-0000-0000-0000-000000000001","Default","server\nminecraft:the_nether");assert !bot.receiver("00000000-0000-0000-0000-000000000002","Default","server\nminecraft:the_end");
        OperationsLibrary library=new OperationsLibrary(root.resolve("operations.json"));assert bot.receive("other whispers: kit pvp 2",1_000_000,library)==null;
        assert KitBot.maxKits(new JsonObject())==5;
        JsonObject excessive=bot.receive("iotacism whispers: kit pvp 6",900_000,library);assert TaskWire.text(excessive,"status").equals("LimitExceeded")&&excessive.get("maxKits").getAsInt()==5&&!excessive.has("submission")&&bot.pending().isEmpty();
        assert bot.receive("iotacism whispers: kit pvp 6",904_999,library)==null:"Over-cap replies still have the duplicate guard";
        assert TaskWire.text(bot.receive("iotacism whispers: kit pvp 2",1_000_000,library),"status").equals("OutOfStock");assert bot.receive("iotacism whispers: kit pvp 2",1_001_000,library)==null;
        bot=new KitBot(root);assert bot.receive("iotacism whispers: kit pvp 2",1_002_000,library)==null:"Reconnect cannot reset cooldown";
        JsonObject person=config.getAsJsonArray("whitelist").get(0).getAsJsonObject();assert KitBot.cooldown(new JsonObject(),new JsonObject())==3_600_000;
        person.addProperty("cooldownSeconds",3600);bot.configure(config,TaskWire.text(bot.view(),"revision"));assert KitBot.cooldown(config,person)==3_600_000;
        assert bot.receive("iotacism whispers: kit pvp 2",4_599_999,library)==null;assert bot.receive("iotacism whispers: kit pvp 2",4_600_000,library)!=null;
        person.addProperty("cooldownSeconds",0);bot.configure(config,TaskWire.text(bot.view(),"revision"));assert KitBot.cooldown(config,person)==0;
        assert bot.receive("iotacism whispers: kit pvp 2",4_604_999,library)==null;assert bot.receive("iotacism whispers: kit pvp 2",4_605_000,library)!=null;
        person.addProperty("cooldownSeconds",-1);try{bot.configure(config,TaskWire.text(bot.view(),"revision"));throw new AssertionError("Negative user cooldown accepted");}catch(IllegalArgumentException expected){}person.addProperty("cooldownSeconds",3600);bot.configure(config,TaskWire.text(bot.view(),"revision"));
        JsonObject p=StashCatalog.plan(JsonParser.parseString("{name:'main',homeName:'main',minX:0,maxX:5,minY:100,maxY:100,minZ:0,maxZ:0}").getAsJsonObject());StashCatalog.define(root,"Default","server\nminecraft:the_end",p);
        JsonObject box=JsonParser.parseString("{slot:0,item:'minecraft:black_shulker_box',name:'Obby',quantity:1,contentsKnown:true,items:{'minecraft:obsidian':1728}}").getAsJsonObject();
        for(int x=0;x<5;x++){JsonObject o=JsonParser.parseString("{y:100,z:0,status:'observed',block:'minecraft:chest',reason:'',items:{'minecraft:black_shulker_box':50,'minecraft:obsidian':86400},shulkers:[]}").getAsJsonObject();o.addProperty("x",x);for(int slot=0;slot<50;slot++){JsonObject b=box.deepCopy();b.addProperty("slot",slot);o.getAsJsonArray("shulkers").add(b);}StashCatalog.save(root,"Default","server\nminecraft:the_end",p,o);}
        KitCategories.seed(root,"Default","server\nminecraft:the_end",StashCatalog.get(root,"Default","server\nminecraft:the_end","main"),250);
        JsonObject beforeList=bot.view();JsonObject listing=bot.listing("[unknown player head] iotacism whispers: kits");assert TaskWire.text(listing,"text").equals("Kits 1: Obby [obsidian]");assert bot.view().equals(beforeList):"Listings do not consume delivery quotas or create jobs";
        JsonObject storage=KitCategories.definition("storage-paving","Paving","Items",List.of("minecraft:obsidian"));storage.addProperty("usage","Storage");
        JsonObject catalog=KitCategories.get(root,"Default","server\nminecraft:the_end");KitCategories.save(root,"Default","server\nminecraft:the_end",TaskWire.text(catalog,"revision"),storage);
        assert TaskWire.text(bot.listing("iotacism whispers: kits"),"text").equals("Kits 1: Obby [obsidian]"):"Storage groups are not delivery aliases";
        JsonObject denied=bot.receive("iotacism whispers: kit storage-paving 1",10_000_000,library);assert TaskWire.text(denied,"status").equals("OutOfStock")&&!denied.has("submission");
        assert TaskWire.text(bot.listing("iotacism whispers: kits 2"),"text").startsWith("No such page");
        for(String raw:List.of("other whispers: kits","iotacism » kits","You whisper to iotacism: kits","iotacism whispers: kits 0","iotacism whispers: kits -1","iotacism whispers: kits 1000000","iotacism whispers: kits 2\n/home main"))assert bot.listing(raw)==null:raw;
        assert KitBot.parse("iotacism whispers: kits")==null;
        List<String> entries=new ArrayList<>();for(int i=0;i<100;i++)entries.add("Kit-"+i+" [pvp]");List<String> pages=KitBot.pages("ABCDEFGHIJKLMNOP",entries);assert pages.size()>2;
        String joined=String.join("|",pages);for(String entry:entries)assert joined.contains(entry):"Lost kit across page boundary: "+entry;
        for(int i=0;i<pages.size();i++){BotChat.command("/msg ABCDEFGHIJKLMNOP "+pages.get(i));assert pages.get(i).startsWith("Kits "+(i+1)+": ");assert pages.get(i).endsWith(",...")==i<pages.size()-1;}
        String longName="😀".repeat(200);String longPage=KitBot.pages("iotacism",List.of(longName,"Next kit")).getFirst();assert longPage.endsWith("...,...");assert !Character.isHighSurrogate(longPage.charAt(longPage.indexOf("...")-1));BotChat.command("/msg iotacism "+longPage);
        person.addProperty("maxKits",2);bot.configure(config,TaskWire.text(bot.view(),"revision"));
        JsonObject custom=bot.receive("iotacism whispers: kit obsidian 3",90_000_000,library);assert TaskWire.text(custom,"status").equals("LimitExceeded")&&custom.get("maxKits").getAsInt()==2;
        for(int invalid:List.of(0,37)){person.addProperty("maxKits",invalid);try{bot.configure(config,TaskWire.text(bot.view(),"revision"));throw new AssertionError("Invalid cap accepted");}catch(IllegalArgumentException expected){}}person.addProperty("maxKits",2);
        JsonObject accepted=bot.receive("iotacism whispers: kit obsidian 2",90_005_000,library);assert TaskWire.text(accepted,"status").equals("Pending"):"Over-cap requests do not consume the one-hour cooldown";assert accepted.getAsJsonObject("submission").getAsJsonObject("args").get("returnHome").getAsBoolean();
        bot=new KitBot(root);assert bot.pending().size()==1&&bot.pending().getFirst().equals(accepted);assert bot.receive("iotacism whispers: kit obsidian 2",100_006_000,library)==null;
        Path file=root.resolve("kitbot.json"),backup=root.resolve("kitbot.saved");Files.move(file,backup);Files.createDirectory(file);Files.createFile(file.resolve("block"));
        try{bot.state(TaskWire.text(accepted,"id"),"Queued","");throw new AssertionError("Failed journal write accepted");}catch(IllegalStateException expected){}
        assert bot.pending().size()==1:"A failed write must not publish an in-memory acceptance transition";
        Files.delete(file.resolve("block"));Files.delete(file);Files.move(backup,file);
        bot.state(TaskWire.text(accepted,"id"),"Queued","");assert new KitBot(root).pending().isEmpty();
        JsonObject failed=JsonParser.parseString("{status:'Failed',runs:{worker:{detail:'Delivery incomplete; returned and redeposited 2 kits'}}}").getAsJsonObject();bot.reconcile(Map.of(UUID.fromString(TaskWire.text(accepted,"id")),failed));
        assert TaskWire.text(bot.view().getAsJsonObject("requests").getAsJsonObject(TaskWire.text(accepted,"id")),"detail").contains("redeposited 2 kits");
        System.out.println("Kitbot checks passed: whisper-only parser, stock reservations, whitelist and durable cooldown.");
    }
    private static void directMessagesCheck(){
        DirectMessages queue=new DirectMessages();UUID worker=UUID.randomUUID(),other=UUID.randomUUID();List<String> sent=new ArrayList<>();
        assert queue.add(worker,"crew","scope","1","/msg iotacism First",1000);assert queue.add(worker,"crew","scope","2","/msg iotacism Second",1000);assert queue.add(worker,"crew","scope","duplicate","/msg iotacism Second",1000);
        queue.drain(worker,"crew","scope",1000,m->{sent.add(m.text());return true;});assert sent.size()==1;
        queue.drain(worker,"crew","scope",5999,m->{throw new AssertionError("DM sent before five seconds");});
        queue.drain(worker,"crew","scope",6000,m->false);queue.drain(worker,"crew","scope",6000,m->{sent.add(m.text());return true;});assert sent.size()==2;
        queue.drain(worker,"crew","scope",11000,m->{throw new AssertionError("Duplicate queued reply");});
        assert queue.add(worker,"crew","scope","3","/msg iotacism Old world",11000);queue.drain(worker,"crew","other-world",11000,m->{throw new AssertionError("Wrong-world replay");});
        assert queue.add(worker,"crew","scope","4","/msg iotacism Expired",11000);queue.drain(worker,"crew","scope",131001,m->{throw new AssertionError("Expired reply");});
        for(int i=0;i<16;i++)assert queue.add(worker,"crew","scope","id"+i,"/msg iotacism Bounded "+i,140000);assert !queue.add(worker,"crew","scope","full","/msg iotacism Overflow",140000);
        assert queue.add(other,"crew","scope","5","/w iotacism Other worker",140000);queue.drain(other,"crew","scope",140000,m->{sent.add(m.text());return true;});assert sent.size()==3;
        assert DirectMessages.isDm("/tell iotacism Hi")&&!DirectMessages.isDm("/home main");
    }
}
