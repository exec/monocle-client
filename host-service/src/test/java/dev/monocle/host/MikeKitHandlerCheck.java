package dev.monocle.host;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import dev.monocle.coordinator.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.monocle.coordinator.TaskWire.*;

final class MikeKitHandlerCheck {
    private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    private static void rejects(Runnable r){try{r.run();throw new AssertionError("Invalid input accepted");}catch(IllegalArgumentException|IllegalStateException expected){}}
    static JsonObject reply(String arguments){return json("{role:'assistant',content:'Gotchu, queued.',tool_calls:[{function:{name:'request_kits',arguments:"+arguments+"}}]}");}
    static void run() throws Exception {
        concurrencyCheck();
        JsonObject settings=MikeKitHandler.defaults();assert !flag(settings,"enabled");MikeKitHandler.checked(settings);
        settings.addProperty("enabled",true);rejects(()->MikeKitHandler.checked(settings));settings.addProperty("model","synthetic-tool-model");rejects(()->MikeKitHandler.checked(settings));settings.addProperty("apiKey","synthetic-ollama-api-key");MikeKitHandler.checked(settings);
        for(String endpoint:List.of("relative","file:///tmp/model","http://user:secret@localhost:11434","http://localhost/api/chat","http://localhost:0","http://localhost/?token=secret")){
            JsonObject invalid=settings.deepCopy();invalid.addProperty("endpoint",endpoint);rejects(()->MikeKitHandler.checked(invalid));
        }
        String id="00000000-0000-0000-0000-000000000101";
        JsonObject rule=KitCategories.definition("paving","Paving supplies","Items",List.of("minecraft:obsidian")),categories=new JsonObject();categories.add(text(rule,"id"),rule);
        JsonObject storage=KitCategories.definition("private-storage-group","Storage only","Items",List.of("minecraft:obsidian"));storage.addProperty("usage","Storage");categories.add(text(storage,"id"),storage);
        // Synthetic privacy canaries; these are not live stash records or locations.
        JsonObject stock=new JsonObject(),kit=json("{name:'PRIVATE_KIT_CANARY',complete:54321,estimatedComplete:98765,homeName:'PRIVATE_HOME_CANARY',x:246810,items:{'minecraft:obsidian':1728}}");kit.addProperty("id",id);stock.add(id,kit);
        JsonArray menu=KitBot.mikeCatalog(categories,stock,new JsonObject(),250);assert menu.size()==1;
        JsonObject row=menu.get(0).getAsJsonObject();assert row.keySet().equals(Set.of("category","description","availability","inStock","contents"));assert text(row,"availability").equals("Plenty");
        row.addProperty("secret","PRIVATE_ROW_CANARY");
        JsonObject body=MikeKitHandler.requestBody(settings,"Could I get two paving kits?",5,menu);String wire=body.toString();
        for(String secret:List.of("54321","98765","246810","1728","PRIVATE_",id,"private-storage-group","homeName","estimatedComplete","kitTypeId","synthetic-ollama-api-key"))assert !wire.contains(secret):secret;
        assert !text(body.getAsJsonArray("messages").get(0).getAsJsonObject(),"content").contains("\"publicProvenance\":"):"Unconfigured hosts make no provenance claim";
        settings.addProperty("provenance","Synthetic public donation project");
        String provenance=text(MikeKitHandler.requestBody(settings,"Where did the kits come from?",5,menu).getAsJsonArray("messages").get(0).getAsJsonObject(),"content");
        assert provenance.contains("\"publicProvenance\":\"Synthetic public donation project\"")&&provenance.contains("If asked where kits came from");
        for(JsonElement invalid:List.of(new JsonPrimitive(1),new JsonPrimitive("x".repeat(257)),new JsonPrimitive("bad\nline"))){JsonObject bad=settings.deepCopy();bad.add("provenance",invalid);rejects(()->MikeKitHandler.checked(bad));}
        row.addProperty("availability","54321 kits");rejects(()->MikeKitHandler.requestBody(settings,"What can I request?",5,menu));row.addProperty("availability","Plenty");
        assert flag(row,"inStock");JsonObject named=MikeKitHandler.requestBody(settings,"What kits are available?",5,menu,new JsonArray(),"SyntheticUser");assert named.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("\"requestingPlayer\":\"SyntheticUser\"");
        rejects(()->MikeKitHandler.requestBody(settings,"Hi",5,menu,new JsonArray(),"Bad Name"));
        row.addProperty("availability","Out of stock");row.addProperty("inStock",true);JsonObject sold=MikeKitHandler.requestBody(settings,"List kits",5,menu);String system=text(sold.getAsJsonArray("messages").get(0).getAsJsonObject(),"content");assert system.contains("\"inStock\":false")&&system.contains("explicitly label unavailable entries as out of stock");row.addProperty("availability","Plenty");
        assert !flag(body,"stream")&&!flag(body,"think")&&body.getAsJsonArray("messages").size()==2;
        assert body.getAsJsonArray("tools").get(0).getAsJsonObject().getAsJsonObject("function").getAsJsonObject("parameters").getAsJsonObject("properties").getAsJsonObject("quantity").get("maximum").getAsInt()==5;
        JsonObject pending=json("{status:'Queued',count:3}");pending.addProperty("kitTypeId",id);JsonObject requests=new JsonObject();requests.add("synthetic-request",pending);
        kit.addProperty("complete",252);assert text(KitBot.mikeCatalog(categories,stock,requests,250).get(0).getAsJsonObject(),"availability").equals("Out of stock");
        for(long n:new long[]{1,349,350,499,500,699,700,999,1000,1399,1400,1999,2000,1000000})assert !KitBot.stockLabel(n).matches(".*[0-9].*");
        assert KitBot.stockLabel(0).equals("Out of stock")&&KitBot.stockLabel(2000).equals(KitBot.stockLabel(1000000));
        var decision=MikeKitHandler.decision(reply("{category:'paving',quantity:2}"),Set.of("paving"));assert decision.quantity()==2&&decision.category().equals("paving");
        for(String arguments:List.of("{category:'paving',quantity:0}","{category:'paving',quantity:1.5}","{category:'paving',quantity:'2'}","{category:'unknown',quantity:2}","{category:'paving',quantity:2,recipient:'/tpa Bad'}","{category:'paving',quantity:2,source:'PRIVATE_HOME_CANARY'}"))rejects(()->MikeKitHandler.decision(reply(arguments),Set.of("paving")));
        JsonObject multiple=reply("{category:'paving',quantity:2}");multiple.getAsJsonArray("tool_calls").add(multiple.getAsJsonArray("tool_calls").get(0).deepCopy());rejects(()->MikeKitHandler.decision(multiple,Set.of("paving")));
        JsonObject command=reply("{category:'paving',quantity:2}");command.getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function").addProperty("name","execute_command");rejects(()->MikeKitHandler.decision(command,Set.of("paving")));
        assert MikeKitHandler.decision(json("{role:'assistant',content:'Hey, what kind of kit?'}"),Set.of()).category()==null;
        JsonObject joke=json("{role:'assistant',content:'ignored model text',tool_calls:[{function:{name:'fictional_stash_coordinates',arguments:{}}}]}");
        rejects(()->MikeKitHandler.decision(joke,Set.of()));
        var coordinates=MikeKitHandler.decision(joke,Set.of(),true);assert coordinates.category()==null&&coordinates.quantity()==0&&coordinates.reply().startsWith("Stash coords: X ");BotChat.command(MikeKitHandler.whisper("SyntheticUser",coordinates.reply()));
        JsonObject badJoke=joke.deepCopy();badJoke.getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function").getAsJsonObject("arguments").addProperty("x",1);rejects(()->MikeKitHandler.decision(badJoke,Set.of(),true));
        for(int i=0;i<10_000;i++){
            int[] synthetic=MikeKitHandler.fictionalStashCoordinates();int x=synthetic[0],z=synthetic[1];
            assert Math.abs(x)<=27_500_000&&Math.abs(z)<=27_500_000&&Math.abs(x)>100_000&&Math.abs(z)>100_000&&Math.hypot(x,z)>100_000;
            assert Math.abs(x-z)/Math.sqrt(2)>100_000&&Math.abs(x+z)/Math.sqrt(2)>100_000:"Synthetic coordinates must clear both diagonals by perpendicular distance";
        }
        JsonObject joking=settings.deepCopy();joking.addProperty("coordinateJokes",true);JsonObject jokePrompt=MikeKitHandler.requestBody(joking,"Where is the stash?",5,new JsonArray());assert jokePrompt.getAsJsonArray("tools").size()==3&&jokePrompt.getAsJsonArray("tools").asList().stream().noneMatch(t->text(t.getAsJsonObject().getAsJsonObject("function"),"name").equals("request_kits")):"Read tools work without any kit stock and have no ordering tool";
        joking.addProperty("coordinateJokes","true");rejects(()->MikeKitHandler.checked(joking));
        JsonObject list=json("{role:'assistant',content:'ignored prose list',tool_calls:[{function:{name:'list_kits',arguments:{page:2}}}]}");var menuDecision=MikeKitHandler.decision(list,Set.of());assert menuDecision.menuPage()==2&&menuDecision.category()==null&&menuDecision.quantity()==0&&menuDecision.reply().isEmpty();
        for(String args:List.of("{}","{page:0}","{page:129}","{page:1.5}","{page:'2'}","{page:2,recipient:'Other'}")){JsonObject invalid=list.deepCopy();invalid.getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function").add("arguments",json(args));rejects(()->MikeKitHandler.decision(invalid,Set.of()));}
        String ref="k-0123456789abcdef";var specific=MikeKitHandler.decision(reply("{category:'paving',quantity:2,kit:'"+ref+"'}"),Set.of("paving"));assert specific.kit().equals(ref)&&specific.inspection()==null;
        rejects(()->MikeKitHandler.decision(reply("{category:'paving',quantity:2,kit:'internal-uuid'}"),Set.of("paving")));
        JsonObject inspection=json("{role:'assistant',content:'',tool_calls:[{function:{name:'inspect_kits',arguments:{items:['blocks']}}}]}");var read=MikeKitHandler.decision(inspection,Set.of());assert read.inspection()!=null&&read.category()==null;
        JsonObject publicResult=KitBotContents.inspect(json("{}"),categories,stock,new JsonObject(),250);JsonObject continued=MikeKitHandler.continueInspection(body,read.inspection(),publicResult);JsonArray exchange=continued.getAsJsonArray("messages");assert exchange.size()==4&&text(exchange.get(3).getAsJsonObject(),"role").equals("tool")&&text(exchange.get(3).getAsJsonObject(),"tool_name").equals("inspect_kits");assert body.getAsJsonArray("messages").size()==2;
        JsonObject leaked=publicResult.deepCopy();leaked.addProperty("source","PRIVATE_SOURCE_CANARY");rejects(()->MikeKitHandler.continueInspection(body,read.inspection(),leaked));
        JsonObject correction=MikeKitHandler.correctResponse(body);assert correction.getAsJsonArray("messages").size()==3&&text(correction.getAsJsonArray("messages").get(2).getAsJsonObject(),"content").contains("NO action was executed")&&body.getAsJsonArray("messages").size()==2;
        assert MikeKitHandler.errorCode(new IllegalArgumentException("Mike can call only one tool per whisper")).equals("multiple_tools")&&MikeKitHandler.errorCode(new IllegalArgumentException("PRIVATE_PROVIDER_ERROR_CANARY")).equals("invalid_response");
        var gift=MikeKitHandler.decision(reply("{category:'paving',quantity:2,recipient:'GiftFriend'}"),Set.of("paving"));assert gift.recipient().equals("GiftFriend");
        JsonObject ack=MikeKitHandler.acknowledgementBody(settings,json("{id:'PRIVATE_ID_CANARY',name:'Requester',recipientName:'GiftFriend',kitName:'Synthetic paving',category:'paving',count:2,submission:{homeName:'PRIVATE_HOME_CANARY'},recipient:'PRIVATE_UUID_CANARY',complete:54321}"),"Give two paving kits to GiftFriend");
        assert ack.getAsJsonArray("tools").isEmpty()&&ack.toString().contains("GiftFriend")&&ack.toString().contains("Synthetic paving");for(String secret:List.of("PRIVATE_","54321","homeName","submission"))assert !ack.toString().contains(secret):"Acknowledgements contain only confirmed public order fields";
        assert MikeKitHandler.namesRecipient("For ANN!","Ann")&&!MikeKitHandler.namesRecipient("I have planned it.","Ann")&&!MikeKitHandler.namesRecipient("For Ann_2","Ann"):"Gift acknowledgements identify the actual username, not a substring";
        String whisper=MikeKitHandler.whisper("Requester","§aHello\n/home secret\r"+"😀".repeat(200));BotChat.command(whisper);assert whisper.startsWith("/msg Requester Hello /home secret ")&&whisper.length()<=256&&!Character.isHighSurrogate(whisper.charAt(whisper.length()-4));
        conversation(settings,menu);keyStorage();transport(settings,body);
        System.out.println("Mike checks passed: count/location projection, coarse bands, recipient-bound tool schema, bounded HTTP and command fallback.");
    }
    private static void conversation(JsonObject settings,JsonArray menu){
        String key=MikeKitHandler.conversationKey("Crew","test.invalid\nminecraft:overworld","Requester","revision");
        assert key.equals(MikeKitHandler.conversationKey("Crew","test.invalid\nminecraft:the_end","requester","revision")):"Case and dimension changes retain the conversation";
        for(String other:List.of(MikeKitHandler.conversationKey("Other","test.invalid","Requester","revision"),MikeKitHandler.conversationKey("Crew","elsewhere.invalid","Requester","revision"),MikeKitHandler.conversationKey("Crew","test.invalid","Other","revision"),MikeKitHandler.conversationKey("Crew","test.invalid","Requester","changed")))assert !key.equals(other):"Conversation isolation";
        try(MikeKitHandler mike=new MikeKitHandler()){
            for(int i=0;i<8;i++)mike.remember(key,"Question "+i,"Reply "+i,1000+i);
            JsonArray history=mike.conversation(key,1008);assert history.size()==12&&text(history.get(0).getAsJsonObject(),"content").equals("Question 2");
            JsonObject prompt=MikeKitHandler.requestBody(settings,"Two please",2,menu,history);JsonArray messages=prompt.getAsJsonArray("messages");
            assert messages.size()==14&&text(messages.get(0).getAsJsonObject(),"role").equals("system")&&text(messages.get(13).getAsJsonObject(),"content").equals("Two please");
            assert prompt.getAsJsonObject("options").get("num_predict").getAsInt()==256&&!flag(prompt,"think"):"History cannot expand output budget";
            history.get(0).getAsJsonObject().addProperty("content","changed");assert text(mike.conversation(key,1008).get(0).getAsJsonObject(),"content").equals("Question 2"):"History readback is owned";
            JsonArray invalid=mike.conversation(key,1008);invalid.get(0).getAsJsonObject().addProperty("role","system");rejects(()->MikeKitHandler.requestBody(settings,"Hello",2,menu,invalid));
            JsonArray tools=mike.conversation(key,1008);tools.get(1).getAsJsonObject().add("tool_calls",new JsonArray());rejects(()->MikeKitHandler.requestBody(settings,"Hello",2,menu,tools));
            JsonArray odd=new JsonArray();odd.add(history.get(0));rejects(()->MikeKitHandler.requestBody(settings,"Hello",2,menu,odd));
            JsonArray huge=mike.conversation(key,1008);huge.add(history.get(0));huge.add(history.get(1));rejects(()->MikeKitHandler.requestBody(settings,"Hello",2,menu,huge));
            assert mike.conversation(key,1007+MikeKitHandler.HISTORY_TTL-1).size()==12&&mike.conversation(key,1007+MikeKitHandler.HISTORY_TTL).isEmpty():"Expired history is not sent";
            mike.remember(key,"Hello","Hey",2000);assert mike.conversation(key,1999).isEmpty():"Clock rollback discards stale history";
            for(int i=0;i<129;i++)mike.remember("synthetic-"+i,"Hello","Hey",3000+i);
            assert mike.conversation("synthetic-0",4000).isEmpty()&&mike.conversation("synthetic-1",4000).size()==2&&mike.conversation("synthetic-128",4000).size()==2:"Keep at most 128 recent users";
            rejects(()->mike.remember("invalid","x".repeat(513),"Hey",4000));rejects(()->mike.remember("invalid","Hi","x".repeat(257),4000));
            mike.clearConversations();assert mike.conversation("synthetic-128",4000).isEmpty();
        }
    }
    private static void keyStorage() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("synthetic-mike-secret-");KitBot bot=new KitBot(root);
        JsonObject config=json("{enabled:false,crew:'Default',scope:'test.invalid\\nminecraft:the_end',source:'SyntheticDepot',worker:'00000000-0000-0000-0000-000000000001',minimumStock:250,cooldownSeconds:3600,whitelist:[],mike:{enabled:false,endpoint:'https://ollama.com',model:'synthetic-tool-model',apiKey:'synthetic-secret-never-returned'}}");
        JsonObject result=bot.configure(config,text(bot.view(),"revision"));assert !result.toString().contains("synthetic-secret-never-returned")&&!result.getAsJsonObject("config").getAsJsonObject("mike").has("apiKey");assert flag(result.getAsJsonObject("config").getAsJsonObject("mike"),"apiKeyConfigured");
        KitBot reloaded=new KitBot(root);assert text(reloaded.mikeSettings(),"apiKey").equals("synthetic-secret-never-returned");
        JsonObject next=result.getAsJsonObject("config").deepCopy();next.getAsJsonObject("mike").addProperty("apiKey","");bot.configure(next,text(bot.view(),"revision"));assert text(bot.mikeSettings(),"apiKey").equals("synthetic-secret-never-returned");
        next.getAsJsonObject("mike").addProperty("provenance","Synthetic public donation project");bot.configure(next,text(bot.view(),"revision"));
        next.getAsJsonObject("mike").remove("provenance");bot.configure(next,text(bot.view(),"revision"));assert text(new KitBot(root).mikeSettings(),"provenance").equals("Synthetic public donation project"):"Older settings saves preserve public provenance";
        next.getAsJsonObject("mike").addProperty("provenance","");bot.configure(next,text(bot.view(),"revision"));assert text(bot.mikeSettings(),"provenance").isEmpty():"An explicit blank clears public provenance";
        next.getAsJsonObject("mike").addProperty("coordinateJokes",true);bot.configure(next,text(bot.view(),"revision"));next.getAsJsonObject("mike").remove("coordinateJokes");bot.configure(next,text(bot.view(),"revision"));assert flag(new KitBot(root).mikeSettings(),"coordinateJokes"):"Older saves preserve the coordinate-joke setting";
        next.getAsJsonObject("mike").addProperty("coordinateJokes",false);bot.configure(next,text(bot.view(),"revision"));assert !flag(bot.mikeSettings(),"coordinateJokes");
        next.getAsJsonObject("mike").addProperty("endpoint","https://other.invalid");rejects(()->bot.configure(next,text(bot.view(),"revision")));assert text(bot.mikeSettings(),"endpoint").equals("https://ollama.com");
        next.getAsJsonObject("mike").addProperty("clearApiKey",true);bot.configure(next,text(bot.view(),"revision"));assert text(bot.mikeSettings(),"apiKey").isEmpty()&&!flag(bot.view().getAsJsonObject("config").getAsJsonObject("mike"),"apiKeyConfigured");
        if(java.nio.file.Files.getFileStore(root).supportsFileAttributeView("posix"))assert java.nio.file.Files.getPosixFilePermissions(root.resolve("kitbot.json")).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
    private static void transport(JsonObject settings,JsonObject body) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger mode=new AtomicInteger();CountDownLatch gate=new CountDownLatch(1),entered=new CountDownLatch(1);
        server.createContext("/api/chat",exchange->{
            try{
                assert exchange.getRequestMethod().equals("POST")&&exchange.getRequestHeaders().getFirst("Authorization").equals("Bearer synthetic-ollama-api-key");JsonObject sent=json(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assert sent.equals(body);
                entered.countDown();if(mode.get()==3)gate.await(5,TimeUnit.SECONDS);
                int code=mode.get()==1?500:200;
                String response=mode.get()==2?"x".repeat(32769):mode.get()==1?"PRIVATE_ERROR_CANARY":"{\"done\":true,\"message\":"+reply("{category:'paving',quantity:2}")+"}";
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}
        });server.start();JsonObject local=settings.deepCopy();local.addProperty("endpoint","http://127.0.0.1:"+server.getAddress().getPort());
        try(MikeKitHandler mike=new MikeKitHandler()){
            for(int testMode=0;testMode<3;testMode++){
                mode.set(testMode);UUID id=UUID.randomUUID();assert mike.submit(id,local,body);MikeKitHandler.Result result=await(mike);assert result.id().equals(id);
                if(testMode==0)assert result.error().isEmpty()&&MikeKitHandler.decision(result.message(),Set.of("paving")).quantity()==2;
                else assert !result.error().isBlank()&&!result.error().contains("PRIVATE_ERROR_CANARY");
            }
            mode.set(3);for(int i=0;i<8;i++)assert mike.submit(UUID.randomUUID(),local,body);assert !mike.submit(UUID.randomUUID(),local,body);assert mike.status().get("pending").getAsInt()==8;
            gate.countDown();for(int i=0;i<8;i++)assert await(mike).error().isEmpty();assert mike.status().get("pending").getAsInt()==0;
        }finally{gate.countDown();server.stop(0);}
    }
    static void concurrencyCheck()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService workers=Executors.newFixedThreadPool(2);
        CountDownLatch firstStarted=new CountDownLatch(1),releaseFirst=new CountDownLatch(1),secondStarted=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();server.setExecutor(workers);
        server.createContext("/api/chat",exchange->{
            try{
                exchange.getRequestBody().readAllBytes();int call=calls.incrementAndGet();
                if(call==1){firstStarted.countDown();releaseFirst.await(5,TimeUnit.SECONDS);}else secondStarted.countDown();
                byte[] bytes="{\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":\"Synthetic reply\"}}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}
        });server.start();
        JsonObject settings=MikeKitHandler.defaults();settings.addProperty("enabled",true);settings.addProperty("model","synthetic");settings.addProperty("endpoint","http://127.0.0.1:"+server.getAddress().getPort());
        try(MikeKitHandler mike=new MikeKitHandler()){
            UUID slow=UUID.randomUUID(),fast=UUID.randomUUID();JsonObject body=MikeKitHandler.requestBody(settings,"Synthetic request",1,new JsonArray());
            assert mike.submit(slow,settings,body)&&firstStarted.await(2,TimeUnit.SECONDS);
            assert mike.submit(fast,settings,body)&&secondStarted.await(2,TimeUnit.SECONDS) : "One slow conversation must not block the second courier's request";
            assert await(mike).id().equals(fast);releaseFirst.countDown();assert await(mike).id().equals(slow);assert mike.status().get("pending").getAsInt()==0;
        }finally{releaseFirst.countDown();server.stop(0);workers.shutdownNow();}
    }
    private static MikeKitHandler.Result await(MikeKitHandler mike) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);MikeKitHandler.Result result;
        while((result=mike.poll())==null&&System.nanoTime()<end)Thread.sleep(10);
        assert result!=null:"No Mike result before deadline";return result;
    }
}
