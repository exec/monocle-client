package dev.monocle.host;

import com.google.gson.*;
import dev.monocle.coordinator.*;
import java.nio.file.*;
import java.util.*;

final class KitBotCheck {
    static void run() throws Exception {
        directMessagesCheck();publicAccessCheck();denseMenuCheck();KitBotContentsCheck.run();KitBotMinimumsCheck.run();KitBotAvailabilityCheck.run();
        assert Arrays.equals(KitBot.parse("[unknown player head] ExampleUser whispers: kit pvp 2"),new String[]{"ExampleUser","pvp","2"});
        assert Arrays.equals(KitBot.parse("ExampleUser whispers: kit obsidian"),new String[]{"ExampleUser","obsidian","1"});
        for(String raw:List.of("ExampleUser » kit pvp 2","someone whispers: ExampleUser whispers: kit pvp 2","You whisper to ExampleUser: kit pvp 2","ExampleUser whispers: kit pvp 37","ExampleUser whispers: kit pvp 0","ExampleUser whispers: kit pvp 2\n/home main","ExampleUser whispers: thanks"))assert KitBot.parse(raw)==null:raw;
        JsonObject type=new JsonObject(),items=new JsonObject();String id=UUID.randomUUID().toString();items.addProperty("minecraft:obsidian",1728);type.add("items",items);type.addProperty("id",id);type.addProperty("complete",250);JsonObject stock=new JsonObject();stock.add(id,type);JsonObject rule=KitCategories.definition("obsidian","Obsidian","Items",List.of("minecraft:obsidian")),requests=new JsonObject();
        assert KitBot.select(rule,stock,requests,250,36)!=null;
        rule.addProperty("usage","Storage");assert KitBot.select(rule,stock,requests,250,1)==null;rule.addProperty("usage","Kitbot");assert KitBot.select(rule,stock,requests,250,1)!=null;rule.remove("usage");
        JsonObject pending=new JsonObject();pending.addProperty("status","Queued");pending.addProperty("kitTypeId",id);pending.addProperty("count",1);requests.add(UUID.randomUUID().toString(),pending);assert KitBot.select(rule,stock,requests,250,1)==null;
        pending.addProperty("status","Complete");assert KitBot.select(rule,stock,requests,250,1)!=null;type.addProperty("complete",249);assert KitBot.select(rule,stock,requests,250,1)==null;
        Path root=Files.createTempDirectory("kitbot-");KitBot bot=new KitBot(root);JsonObject config=JsonParser.parseString("{enabled:true,crew:'Default',scope:'server\\nminecraft:the_end',source:'main',worker:'00000000-0000-0000-0000-000000000001',minimumStock:250,cooldownSeconds:86400,whitelist:[{name:'ExampleUser',uuid:'00000000-0000-0000-0000-000000000077'}]}").getAsJsonObject();
        bot.configure(config,TaskWire.text(bot.view(),"revision"));assert bot.receiver("00000000-0000-0000-0000-000000000001","Default","server\nminecraft:the_nether");assert !bot.receiver("00000000-0000-0000-0000-000000000002","Default","server\nminecraft:the_end");
        OperationsLibrary library=new OperationsLibrary(root.resolve("operations.json"));assert bot.receive("other whispers: kit pvp 2",1_000_000,library)==null;
        assert KitBot.maxKits(new JsonObject())==5;
        JsonObject excessive=bot.receive("ExampleUser whispers: kit pvp 6",900_000,library);assert TaskWire.text(excessive,"status").equals("LimitExceeded")&&excessive.get("maxKits").getAsInt()==5&&!excessive.has("submission")&&bot.pending().isEmpty();
        assert bot.cooldownReply("ExampleUser whispers: hello",900_001)==null : "An over-cap rejection does not create an hourly cooldown";
        assert bot.receive("ExampleUser whispers: kit pvp 6",904_999,library)==null:"Over-cap replies still have the duplicate guard";
        assert TaskWire.text(bot.receive("ExampleUser whispers: kit pvp 2",1_000_000,library),"status").equals("OutOfStock");assert bot.receive("ExampleUser whispers: kit pvp 2",1_001_000,library)==null;
        JsonObject notice=bot.cooldownReply("ExampleUser whispers: hello?",1_001_000);
        assert TaskWire.text(notice,"status").equals("Cooldown")&&notice.get("cooldownUntil").getAsLong()==87_400_000;
        assert KitBot.cooldownText(87_400_000-1_001_000).equals("You're on cooldown. You can request kits again in 23h 59m 59s.");
        assert bot.cooldownReply("other whispers: hello",1_001_000)==null&&bot.cooldownReply("ExampleUser » hello",1_001_000)==null : "Only verified eligible whisper identities get cooldown information";
        bot=new KitBot(root);assert bot.receive("ExampleUser whispers: kit pvp 2",1_002_000,library)==null:"Reconnect cannot reset cooldown";
        JsonObject person=config.getAsJsonArray("whitelist").get(0).getAsJsonObject();assert KitBot.cooldown(new JsonObject(),new JsonObject())==3_600_000;
        person.addProperty("cooldownSeconds",3600);bot.configure(config,TaskWire.text(bot.view(),"revision"));assert KitBot.cooldown(config,person)==3_600_000;
        assert bot.receive("ExampleUser whispers: kit pvp 2",4_599_999,library)==null;assert bot.receive("ExampleUser whispers: kit pvp 2",4_600_000,library)!=null;
        person.addProperty("cooldownSeconds",0);bot.configure(config,TaskWire.text(bot.view(),"revision"));assert KitBot.cooldown(config,person)==0;
        assert bot.cooldownReply("ExampleUser whispers: hello",4_600_001)==null : "Zero-cooldown users never receive an hourly refusal";
        assert bot.receive("ExampleUser whispers: kit pvp 2",4_604_999,library)==null;assert bot.receive("ExampleUser whispers: kit pvp 2",4_605_000,library)!=null;
        person.addProperty("cooldownSeconds",-1);try{bot.configure(config,TaskWire.text(bot.view(),"revision"));throw new AssertionError("Negative user cooldown accepted");}catch(IllegalArgumentException expected){}person.addProperty("cooldownSeconds",3600);bot.configure(config,TaskWire.text(bot.view(),"revision"));
        JsonObject p=StashCatalog.plan(JsonParser.parseString("{name:'main',homeName:'main',minX:0,maxX:5,minY:100,maxY:100,minZ:0,maxZ:0}").getAsJsonObject());StashCatalog.define(root,"Default","server\nminecraft:the_end",p);
        JsonObject box=JsonParser.parseString("{slot:0,item:'minecraft:black_shulker_box',name:'Obby',quantity:1,contentsKnown:true,items:{'minecraft:obsidian':1728}}").getAsJsonObject();
        for(int x=0;x<5;x++){JsonObject o=JsonParser.parseString("{y:100,z:0,status:'observed',block:'minecraft:chest',reason:'',items:{'minecraft:black_shulker_box':50,'minecraft:obsidian':86400},shulkers:[]}").getAsJsonObject();o.addProperty("x",x);for(int slot=0;slot<50;slot++){JsonObject b=box.deepCopy();b.addProperty("slot",slot);o.getAsJsonArray("shulkers").add(b);}StashCatalog.save(root,"Default","server\nminecraft:the_end",p,o);}
        KitCategories.seed(root,"Default","server\nminecraft:the_end",StashCatalog.get(root,"Default","server\nminecraft:the_end","main"),250);
        JsonObject beforeList=bot.view();JsonObject listing=bot.listing("[unknown player head] ExampleUser whispers: kits");assert TaskWire.text(listing,"text").equals("Kits 1: Obby [obsidian]");assert bot.view().equals(beforeList):"Listings do not consume delivery quotas or create jobs";
        JsonObject storage=KitCategories.definition("storage-paving","Paving","Items",List.of("minecraft:obsidian"));storage.addProperty("usage","Storage");
        JsonObject catalog=KitCategories.get(root,"Default","server\nminecraft:the_end");KitCategories.save(root,"Default","server\nminecraft:the_end",TaskWire.text(catalog,"revision"),storage);
        assert TaskWire.text(bot.listing("ExampleUser whispers: kits"),"text").equals("Kits 1: Obby [obsidian]"):"Storage groups are not delivery aliases";
        JsonObject denied=bot.receive("ExampleUser whispers: kit storage-paving 1",10_000_000,library);assert TaskWire.text(denied,"status").equals("OutOfStock")&&!denied.has("submission");
        assert TaskWire.text(bot.listing("ExampleUser whispers: kits 2"),"text").startsWith("No such page");
        for(String raw:List.of("other whispers: kits","ExampleUser » kits","You whisper to ExampleUser: kits","ExampleUser whispers: kits 0","ExampleUser whispers: kits -1","ExampleUser whispers: kits 1000000","ExampleUser whispers: kits 2\n/home main"))assert bot.listing(raw)==null:raw;
        assert KitBot.parse("ExampleUser whispers: kits")==null;
        List<String> entries=new ArrayList<>();for(int i=0;i<100;i++)entries.add("Kit-"+i+" [pvp]");List<String> pages=KitBot.pages("ABCDEFGHIJKLMNOP",entries);assert pages.size()>2;
        String joined=String.join("|",pages);for(String entry:entries)assert joined.contains(entry):"Lost kit across page boundary: "+entry;
        for(int i=0;i<pages.size();i++){BotChat.command("/msg ABCDEFGHIJKLMNOP "+pages.get(i));assert pages.get(i).startsWith("Kits "+(i+1)+": ");assert pages.get(i).endsWith(",...")==i<pages.size()-1;}
        String longName="😀".repeat(200);String longPage=KitBot.pages("ExampleUser",List.of(longName,"Next kit")).getFirst();assert longPage.endsWith("...,...");assert !Character.isHighSurrogate(longPage.charAt(longPage.indexOf("...")-1));BotChat.command("/msg ExampleUser "+longPage);
        person.addProperty("maxKits",2);bot.configure(config,TaskWire.text(bot.view(),"revision"));
        JsonObject custom=bot.receive("ExampleUser whispers: kit obsidian 3",90_000_000,library);assert TaskWire.text(custom,"status").equals("LimitExceeded")&&custom.get("maxKits").getAsInt()==2;
        for(int invalid:List.of(0,37)){person.addProperty("maxKits",invalid);try{bot.configure(config,TaskWire.text(bot.view(),"revision"));throw new AssertionError("Invalid cap accepted");}catch(IllegalArgumentException expected){}}person.addProperty("maxKits",2);
        JsonObject accepted=bot.receive("ExampleUser whispers: kit obsidian 2",90_005_000,library);assert TaskWire.text(accepted,"status").equals("Pending"):"Over-cap requests do not consume the one-hour cooldown";assert accepted.getAsJsonObject("submission").getAsJsonObject("args").get("returnHome").getAsBoolean();
        bot=new KitBot(root);assert bot.pending().size()==1&&bot.pending().getFirst().equals(accepted);assert TaskWire.text(bot.receive("ExampleUser whispers: kit obsidian 2",100_006_000,library),"text").contains("already have a delivery");
        // Synthetic secondary source; the primary is below the new threshold.
        JsonObject secondary=StashCatalog.plan(JsonParser.parseString("{name:'Secondary',homeName:'secondary',minX:20,maxX:25,minY:100,maxY:100,minZ:0,maxZ:0}").getAsJsonObject());StashCatalog.define(root,"Default","server\nminecraft:the_end",secondary);
        for(int x=20;x<26;x++){
            JsonObject observation=StashCatalog.get(root,"Default","server\nminecraft:the_end","main").getAsJsonObject("containers").entrySet().iterator().next().getValue().getAsJsonObject().deepCopy();observation.addProperty("x",x);StashCatalog.save(root,"Default","server\nminecraft:the_end",secondary,observation);
        }
        config.add("sources",JsonParser.parseString("['Secondary']"));config.addProperty("minimumStock",1000); // Below minimum, not merely missing a category.
        // Config validation keeps the existing lower bound of 250.
        bot.configure(config,TaskWire.text(bot.view(),"revision"));
        assert TaskWire.text(bot.mikeCatalog().get(0).getAsJsonObject(),"availability").equals("Out of stock");
        config.addProperty("minimumStock",250);
        JsonObject publicTest=JsonParser.parseString("{name:'SyntheticUser',uuid:'00000000-0000-0000-0000-000000000099',cooldownSeconds:3600,maxKits:1}").getAsJsonObject();config.getAsJsonArray("whitelist").add(publicTest);bot.configure(config,TaskWire.text(bot.view(),"revision"));
        JsonObject routed=bot.receive("SyntheticUser whispers: kit obsidian 1",110_000_000,library);
        assert TaskWire.text(routed,"status").equals("Pending");assert TaskWire.text(routed.getAsJsonObject("submission").getAsJsonObject("args"),"name").equals("Secondary"):"Use the stocked secondary source with its own bounds and home";
        bot.state(TaskWire.text(routed,"id"),"Cancelled","");config.getAsJsonArray("whitelist").remove(1);
        JsonObject strongest=KitBot.combinedStock(List.of(stock,stock));assert strongest.getAsJsonObject(id).get("complete").getAsInt()==249:"Overlapping sources cannot inflate stock";
        config.remove("sources");bot.configure(config,TaskWire.text(bot.view(),"revision"));
        Path file=root.resolve("kitbot.json"),backup=root.resolve("kitbot.saved");Files.move(file,backup);Files.createDirectory(file);Files.createFile(file.resolve("block"));
        try{bot.state(TaskWire.text(accepted,"id"),"Queued","");throw new AssertionError("Failed journal write accepted");}catch(IllegalStateException expected){}
        assert bot.pending().size()==1:"A failed write must not publish an in-memory acceptance transition";
        Files.delete(file.resolve("block"));Files.delete(file);Files.move(backup,file);
        bot.state(TaskWire.text(accepted,"id"),"Queued","");assert new KitBot(root).pending().isEmpty();
        JsonObject failed=JsonParser.parseString("{status:'Failed',runs:{worker:{detail:'Delivery incomplete; returned and redeposited 2 kits'}}}").getAsJsonObject();bot.reconcile(Map.of(UUID.fromString(TaskWire.text(accepted,"id")),failed));
        assert TaskWire.text(bot.view().getAsJsonObject("requests").getAsJsonObject(TaskWire.text(accepted,"id")),"detail").contains("redeposited 2 kits");
        JsonObject sender=JsonParser.parseString("{name:'GiftSender',uuid:'00000000-0000-0000-0000-000000000051',cooldownSeconds:3600,maxKits:5}").getAsJsonObject(),recipient=JsonParser.parseString("{name:'GiftRecipient',uuid:'00000000-0000-0000-0000-000000000052',cooldownSeconds:3600,maxKits:5}").getAsJsonObject();config.getAsJsonArray("whitelist").add(sender);config.getAsJsonArray("whitelist").add(recipient);bot.configure(config,TaskWire.text(bot.view(),"revision"));
        JsonObject identity=new JsonObject();identity.addProperty("id",TaskWire.text(recipient,"uuid"));identity.addProperty("name",TaskWire.text(recipient,"name"));JsonObject gifted=bot.receive("GiftSender","obsidian",1,"",200_000_000,library,identity);
        assert TaskWire.text(gifted,"status").equals("Pending")&&KitBot.cooldownOwner(gifted).equals(TaskWire.text(sender,"uuid"))&&TaskWire.text(gifted,"recipient").equals(TaskWire.text(recipient,"uuid"));
        JsonObject giftArgs=gifted.getAsJsonObject("submission").getAsJsonObject("args");assert TaskWire.text(giftArgs,"recipient").equals(TaskWire.text(recipient,"uuid"))&&TaskWire.text(giftArgs,"recipientName").equals("GiftRecipient");
        bot=new KitBot(root);assert bot.requestedSince("GiftSender",200_000_000)&&!bot.requestedSince("GiftRecipient",200_000_000);assert bot.receive("GiftSender whispers: kit obsidian 1",200_005_000,library)==null;
        assert bot.cooldownReply("GiftSender whispers: hello",200_005_000)!=null&&bot.cooldownReply("GiftRecipient whispers: hello",200_005_000)==null : "Only the gift requester owes the cooldown";
        assert bot.cooldownReply("GiftSender whispers: hello",203_600_000)==null : "Cooldown expires at the exact millisecond boundary";
        assert bot.receive("GiftRecipient whispers: kit pvp 1",200_005_000,library)!=null:"Receiving a gift never consumes the recipient's request cooldown";
        assert KitBot.cooldownOwner(JsonParser.parseString("{recipient:'legacy-owner'}").getAsJsonObject()).equals("legacy-owner"):"Legacy self-deliveries keep their original cooldown owner";
        System.out.println("Kitbot checks passed: whisper-only parser, stock reservations, whitelist and durable cooldown.");
    }
    private static void publicAccessCheck() throws Exception {
        Path root=Files.createTempDirectory("synthetic-public-kitbot-");KitBot bot=new KitBot(root);OperationsLibrary library=new OperationsLibrary(root.resolve("operations.json"));
        JsonObject config=JsonParser.parseString("{enabled:true,publicAccess:false,crew:'Default',scope:'synthetic.invalid\\nminecraft:overworld',source:'SyntheticDepot',worker:'00000000-0000-0000-0000-000000000001',minimumStock:500,cooldownSeconds:3600,whitelist:[]}").getAsJsonObject();
        JsonObject sender=JsonParser.parseString("{name:'SyntheticUser',uuid:'00000000-0000-0000-0000-000000000009'}").getAsJsonObject();String raw="SyntheticUser whispers: kit pvp 1";
        bot.configure(config,TaskWire.text(bot.view(),"revision"));bot.observeSender(raw,sender);assert bot.receive(raw,1000000,library)==null;
        config.addProperty("publicAccess",true);bot.configure(config,TaskWire.text(bot.view(),"revision"));assert bot.receive(raw,1000000,library)==null:"Unknown identities cannot receive public deliveries";
        JsonObject mismatch=sender.deepCopy();mismatch.addProperty("name","DifferentUser");try{bot.observeSender(raw,mismatch);throw new AssertionError("Mismatched sender accepted");}catch(IllegalArgumentException expected){}
        bot.observeSender(raw,sender);JsonObject cap=bot.receive("SyntheticUser whispers: kit pvp 2",1000000,library);assert TaskWire.text(cap,"status").equals("LimitExceeded")&&cap.get("maxKits").getAsInt()==1;
        JsonObject request=bot.receive(raw,1005000,library);assert TaskWire.text(request,"status").equals("OutOfStock")&&request.get("maxKits").getAsInt()==1;
        bot=new KitBot(root);bot.observeSender(raw,sender);assert bot.receive(raw,1005001,library)==null:"Public cooldown survives a restart";
        assert bot.receive(raw,4604999,library)==null&&bot.receive(raw,4605000,library)!=null:"Public limit is one accepted request per hour";
        config.addProperty("publicAccess",false);bot.configure(config,TaskWire.text(bot.view(),"revision"));assert bot.receive(raw,100000000,library)==null:"Restoring whitelist mode blocks public users";
        config.addProperty("minimumStock",270);JsonObject project=JsonParser.parseString("{name:'ProjectUser',uuid:'00000000-0000-0000-0000-000000000010',cooldownSeconds:3600,maxKits:5}").getAsJsonObject();config.getAsJsonArray("whitelist").add(project);
        assert KitBot.cooldown(config,project)==3600000&&KitBot.minimumStock(config,project)==270;
        config.addProperty("publicAccess",true);bot.configure(config,TaskWire.text(bot.view(),"revision"));assert KitBot.cooldown(config,project)==0&&KitBot.minimumStock(config,project)==109;
        assert KitBot.cooldown(config,sender)==3600000&&KitBot.minimumStock(config,sender)==270:"Public users do not inherit project access";
        JsonObject type=JsonParser.parseString("{id:'00000000-0000-0000-0000-000000000011',complete:109,items:{'minecraft:stone':1728}}").getAsJsonObject(),stock=new JsonObject();stock.add(TaskWire.text(type,"id"),type);JsonObject rule=KitCategories.definition("synthetic-stone","Synthetic stone","Items",List.of("minecraft:stone"));
        assert KitBot.select(rule,stock,new JsonObject(),KitBot.minimumStock(config,project),5)!=null;
        assert KitBot.select(rule,stock,new JsonObject(),KitBot.minimumStock(config,sender),5)==null;
        type.addProperty("complete",108);assert KitBot.select(rule,stock,new JsonObject(),KitBot.minimumStock(config,project),1)==null:"More than 108 means at least 109";
        JsonObject first=bot.receive("ProjectUser whispers: kit pvp 1",200000000,library);assert first!=null&&bot.receive("ProjectUser whispers: kit pvp 1",200004999,library)==null&&bot.receive("ProjectUser whispers: kit pvp 1",200005000,library)!=null:"Project users keep the five-second guard without an hourly cooldown";
        // Synthetic three-chest source exercises the same menu and submission paths as live requests.
        String scope=TaskWire.text(config,"scope");JsonObject plan=StashCatalog.plan(JsonParser.parseString("{name:'SyntheticDepot',homeName:'synthetic-depot',minX:0,maxX:2,minY:100,maxY:100,minZ:0,maxZ:0}").getAsJsonObject());StashCatalog.define(root,"Default",scope,plan);
        for(int x=0;x<3;x++){
            int count=x<2?54:1;JsonObject observation=JsonParser.parseString("{y:100,z:0,status:'observed',block:'minecraft:chest',reason:'',items:{},shulkers:[]}").getAsJsonObject();observation.addProperty("x",x);observation.getAsJsonObject("items").addProperty("minecraft:gray_shulker_box",count);
            for(int slot=0;slot<count;slot++){JsonObject box=JsonParser.parseString("{item:'minecraft:gray_shulker_box',name:'Synthetic stone',quantity:1,contentsKnown:true,items:{'minecraft:stone':1728}}").getAsJsonObject();box.addProperty("slot",slot);observation.getAsJsonArray("shulkers").add(box);}StashCatalog.save(root,"Default",scope,plan,observation);
        }
        KitCategories.seed(root,"Default",scope,StashCatalog.get(root,"Default",scope,"SyntheticDepot"),109);
        bot.observeSender(raw,sender);
        assert bot.mikeCatalog("ProjectUser").asList().stream().anyMatch(row->row.getAsJsonObject().get("inStock").getAsBoolean());
        assert bot.mikeCatalog("SyntheticUser").asList().stream().noneMatch(row->row.getAsJsonObject().get("inStock").getAsBoolean()):"Public Mike catalog keeps the public stock minimum";
        assert TaskWire.text(bot.listing("ProjectUser whispers: kits"),"text").contains("Synthetic stone")&&!TaskWire.text(bot.listing("SyntheticUser whispers: kits"),"text").contains("Synthetic stone");
        JsonObject beforeMenu=bot.view();assert TaskWire.text(bot.listing("ProjectUser whispers: menu"),"text").equals("Kits 1/1 In:stone")&&TaskWire.text(bot.listing("SyntheticUser whispers: menu"),"text").equals("Kits 1/1 Out:stone")&&beforeMenu.equals(bot.view()):"Compact menus use each person's stock threshold and never consume a quota";
        assert TaskWire.text(bot.listing("ProjectUser whispers: menu 2"),"text").startsWith("No such page");
        for(String invalid:List.of("Unknown whispers: menu","ProjectUser whispers: menu 0","ProjectUser whispers: menu -1","ProjectUser whispers: menu 1\n/home synthetic","ProjectUser » menu"))assert bot.listing(invalid)==null;
        JsonObject delivery=bot.receive("ProjectUser whispers: kit stone 5",200010000,library);assert TaskWire.text(delivery,"status").equals("Pending")&&delivery.get("cooldownMs").getAsLong()==0&&delivery.has("submission");
        assert TaskWire.text(bot.receive("ProjectUser whispers: kit stone 1",200020000,library),"text").contains("already have a delivery"):"Project access explains same-recipient duplicates without creating an order";bot.state(TaskWire.text(delivery,"id"),"Complete","");
        assert TaskWire.text(bot.receive("ProjectUser whispers: kit stone 1",200020000,library),"status").equals("Pending"):"A completed project delivery permits the next request immediately";
        config.addProperty("publicAccess",false);assert KitBot.cooldown(config,project)==3600000&&KitBot.minimumStock(config,project)==270:"Private mode restores configured limits";

    }
    private static void denseMenuCheck(){
        JsonArray simple=JsonParser.parseString("[{category:'zeta',inStock:false},{category:'pvp',inStock:true},{category:'alpha',inStock:true}]").getAsJsonArray();
        assert KitBot.menuPages("SyntheticUser",simple).equals(List.of("Kits 1/1 In:alpha,pvp Out:zeta"));
        for(String name:List.of("A","ABCDEFGHIJKLMNOP"))for(int size:List.of(0,1,9,10,32,100,128)){
            JsonArray rows=new JsonArray();Set<String> expected=new HashSet<>();
            for(int i=0;i<size;i++){JsonObject row=new JsonObject();String slug="kit-%03d-".formatted(i)+"a".repeat(40);expected.add(slug);row.addProperty("category",slug);row.addProperty("inStock",i%3!=0);rows.add(row);}
            List<String> pages=KitBot.menuPages(name,rows);Set<String> seen=new HashSet<>();int entries=0;
            for(int i=0;i<pages.size();i++){
                String page=pages.get(i);BotChat.command("/msg "+name+" "+page);assert page.startsWith("Kits "+(i+1)+"/"+pages.size());
                assert page.endsWith(" | menu "+(i+2))==(i+1<pages.size());
                var words=java.util.regex.Pattern.compile("kit-\\d{3}-a{40}").matcher(page);while(words.find()){assert seen.add(words.group()):"No duplicate category across pages";entries++;}
            }
            assert entries==size&&seen.equals(expected):"All exact request words survive pagination";
        }
    }
    private static void directMessagesCheck(){
        assert KitBot.cooldownText(1).endsWith("1s.")&&KitBot.cooldownText(1000).endsWith("1s.")&&KitBot.cooldownText(1001).endsWith("2s.");
        assert KitBot.cooldownText(60000).endsWith("1m 0s.")&&KitBot.cooldownText(3600000).endsWith("1h 0m 0s.");
        assert KitBot.cooldownText(0).equals("Your kit cooldown has ended. You can request kits again.");
        DirectMessages queue=new DirectMessages();UUID worker=UUID.randomUUID(),other=UUID.randomUUID();List<String> sent=new ArrayList<>();
        assert queue.add(worker,"crew","scope","1","/msg ExampleUser First",1000);assert queue.add(worker,"crew","scope","2","/msg ExampleUser Second",1000);assert queue.add(worker,"crew","scope","duplicate","/msg ExampleUser Second",1000);
        queue.drain(worker,"crew","scope",1000,m->{sent.add(m.text());return true;});assert sent.size()==1;
        queue.drain(worker,"crew","scope",5999,m->{throw new AssertionError("DM sent before five seconds");});
        queue.drain(worker,"crew","scope",6000,m->false);queue.drain(worker,"crew","scope",6000,m->{sent.add(m.text());return true;});assert sent.size()==2;
        queue.drain(worker,"crew","scope",11000,m->{throw new AssertionError("Duplicate queued reply");});
        assert queue.add(worker,"crew","scope","3","/msg ExampleUser Old world",11000);queue.drain(worker,"crew","other-world",11000,m->{throw new AssertionError("Wrong-world replay");});
        assert queue.add(worker,"crew","scope","4","/msg ExampleUser Expired",11000);queue.drain(worker,"crew","scope",131001,m->{throw new AssertionError("Expired reply");});
        for(int i=0;i<16;i++)assert queue.add(worker,"crew","scope","id"+i,"/msg ExampleUser Bounded "+i,140000);assert !queue.add(worker,"crew","scope","full","/msg ExampleUser Overflow",140000);
        assert queue.add(other,"crew","scope","5","/w ExampleUser Other worker",140000);queue.drain(other,"crew","scope",140000,m->{sent.add(m.text());return true;});assert sent.size()==3;
        assert DirectMessages.isDm("/tell ExampleUser Hi")&&!DirectMessages.isDm("/home main");
        String end="synthetic.invalid\nminecraft:the_end",overworld="synthetic.invalid\nminecraft:overworld";
        assert BotChat.commandScope("/msg SyntheticUser Receipt",end,overworld)&&!BotChat.commandScope("/home synthetic-home",end,overworld)&&!BotChat.commandScope("/msg SyntheticUser Receipt",end,"other.invalid\nminecraft:the_end") : "Whispers follow users across dimensions; world commands and other servers remain isolated";
        DirectMessages dimension=new DirectMessages();dimension.add(worker,"crew",end,UUID.randomUUID().toString(),"/msg SyntheticUser Bank receipt",1000);dimension.drain(worker,"crew",overworld,1000,m->{assert m.text().endsWith("Bank receipt");return true;});
        DirectMessages gift=new DirectMessages();List<String> notices=new ArrayList<>();List<DirectMessages.Message> pair=List.of(new DirectMessages.Message(worker,"crew","scope","gift","/msg Sender Acknowledged",1000),new DirectMessages.Message(worker,"crew","scope","gift:recipient","/msg Recipient Sender gifted a kit",1000));assert gift.addAll(pair,1000)&&gift.addAll(pair,1000);
        gift.drain(worker,"crew","scope",1000,m->{notices.add(m.text());return true;});assert notices.equals(List.of("/msg Sender Acknowledged"));gift.drain(worker,"crew","scope",5999,m->{throw new AssertionError("Gift recipient notified before DM cooldown");});gift.drain(worker,"crew","scope",6000,m->{notices.add(m.text());return true;});assert notices.get(1).startsWith("/msg Recipient ");gift.drain(worker,"crew","scope",11000,m->{throw new AssertionError("Gift notices replayed");});
        DirectMessages full=new DirectMessages();for(int i=0;i<15;i++)assert full.add(worker,"crew","scope","queued-"+i,"/msg Sender Existing "+i,1000);assert !full.addAll(pair,1000):"An over-capacity gift batch enqueues neither notice";
        int[] total={0};for(int i=0;i<17;i++)full.drain(worker,"crew","scope",1000+i*5000,m->{total[0]++;return true;});assert total[0]==15;
        DirectMessages cooldown=new DirectMessages();cooldown.sent(worker,1000);
        assert cooldown.cooldown(worker,"crew","scope","SyntheticUser",61000,1000)&&cooldown.cooldown(worker,"crew","scope","SyntheticUser",61000,2000);
        cooldown.drain(worker,"crew","scope",5999,m->{throw new AssertionError("Cooldown replies must share the DM rate limit");});
        cooldown.drain(worker,"crew","scope",6000,m->{UUID.fromString(m.id());assert m.text(6000).equals("/msg SyntheticUser You're on cooldown. You can request kits again in 55s.") : "Remaining time is calculated at send time";return true;});
        cooldown.drain(worker,"crew","scope",11000,m->{throw new AssertionError("Repeated whispers must coalesce while queued");});
        assert cooldown.cooldown(worker,"crew","scope","SyntheticUser",12000,11000);
        cooldown.drain(worker,"crew","scope",13000,m->{assert m.text(13000).endsWith("Your kit cooldown has ended. You can request kits again.");return true;});
    }
}
