package dev.monocle.coordinator;

import com.google.gson.*;
import dev.monocle.client.systems.bots.*;
import java.nio.file.*;
import java.util.*;
import static dev.monocle.coordinator.TaskWire.*;

/** Three-worker replay through the real Lua/native command boundary; no game or network required. */
final class StashTransferTeamCheck {
    private static final List<String> IDS=List.of("00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000002","00000000-0000-0000-0000-000000000003");
    private static final String SCOPE="server\nminecraft:the_end";
    private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    private static void rejects(Runnable r){try{r.run();throw new AssertionError("Invalid crew operation accepted");}catch(IllegalArgumentException|IllegalStateException expected){}}
    private final Path root;
    private JsonObject task=new JsonObject();
    private final Map<UUID,PlayerObservation> observations=new HashMap<>();
    private final Map<String,JsonObject> states=new HashMap<>();
    private final String script=new BotWorkflows(null).get(StashTransferTeam.WORKFLOW).script();
    private final String kit;
    private StashTransferTeamCheck() throws Exception {
        root=Files.createTempDirectory("monocle-team-transfer-");
        JsonObject source=StashCatalog.plan(json("{name:'Source',homeName:'source',minX:0,maxX:8,minY:116,maxY:116,minZ:0,maxZ:0,scanMode:'Full',lazyMode:false}"));
        JsonObject box=json("{slot:0,item:'minecraft:blue_shulker_box',quantity:1,name:'Alpha',contentsKnown:true,items:{'minecraft:obsidian':1728}}");kit=StashCatalog.kitTypeId(box);
        for(int x=0;x<9;x++){JsonObject o=json("{y:116,z:0,status:'observed',block:'minecraft:chest',items:{'minecraft:blue_shulker_box':1,'minecraft:obsidian':1728},shulkers:[]}");o.addProperty("x",x);o.getAsJsonArray("shulkers").add(box.deepCopy());StashCatalog.save(root,"A",SCOPE,source,o);}
        JsonObject destination=source.deepCopy();destination.addProperty("name","Destination");destination.addProperty("homeName","destination");destination.addProperty("minX",10000);destination.addProperty("maxX",10008);
        for(int x=10000;x<=10008;x++){JsonObject o=json("{y:116,z:0,status:'observed',block:'minecraft:chest',items:{},shulkers:[]}");o.addProperty("x",x);StashCatalog.save(root,"A",SCOPE,destination,o);}
        JsonObject request=json("{name:'Source',targetStashName:'Destination',count:36}");
        rejects(()->StashTransferTeam.prepare(root,"A",SCOPE,request,IDS.subList(0,1)));
        assert text(StashTransferTeam.prepare(root,"A",SCOPE,request,IDS.subList(0,2)),"leader").equals(IDS.getFirst());
        rejects(()->StashTransferTeam.prepare(root,"A",SCOPE,request,List.of(IDS.getFirst(),IDS.getFirst())));
        JsonObject args=StashTransferTeam.prepare(root,"A",SCOPE,request,IDS);
        task.addProperty("id",UUID.randomUUID().toString());task.addProperty("crew","A");task.addProperty("server","server");task.addProperty("dimension","minecraft:the_end");task.add("args",args);task.add("package",new BotWorkflows(null).packageWorkflows(StashTransferTeam.WORKFLOW));task.add("runs",new JsonObject());
        for(String id:IDS){JsonObject run=new JsonObject();run.addProperty("id",UUID.randomUUID().toString());run.addProperty("status","Running");task.getAsJsonObject("runs").add(id,run);decision(id,BotLua.next(script,new JsonObject(),args,null,null));position(id,10000);}
    }
    private JsonObject run(String id){return task.getAsJsonObject("runs").getAsJsonObject(id);}
    private void position(String id,int x){observations.put(UUID.fromString(id),new PlayerObservation(UUID.fromString(id),"Worker",SCOPE,new PlayerObservation.Position(x,116,0),1_000_000));}
    private void decision(String id,BotLua.Decision d){states.put(id,d.state());run(id).add("action",d.action());run(id).addProperty("token",UUID.randomUUID().toString());}
    private JsonObject poll(String id){JsonObject reply=StashTransferTeam.next(root,task,run(id),id,observations,1_000_000,1000);if(reply!=null){assert reply.equals(StashTransferTeam.next(root,task,run(id),id,observations,1_000_000,1000));decision(id,BotLua.next(script,states.get(id),task.getAsJsonObject("args"),reply,null));}return reply;}
    private void finish(String id,JsonObject receipt){decision(id,BotLua.next(script,states.get(id),task.getAsJsonObject("args"),receipt,null));}
    private void homeSent(String id){JsonObject m=message("status");m.addProperty("run",text(run(id),"id"));m.addProperty("status","Running");m.addProperty("token",text(run(id),"token"));m.add("action",run(id).get("action").deepCopy());JsonObject travel=json("{homeSent:true,arrived:false}");travel.addProperty("name",text(run(id).getAsJsonObject("action"),"name"));m.add("stashTravel",travel);TaskWire.applyStatus(task,run(id),m,true);}
    private JsonObject action(String id){return run(id).getAsJsonObject("action");}
    private void travel(int x){
        boolean local=observations.get(UUID.fromString(IDS.getFirst())).position().x()==x;
        assert poll(IDS.getFirst())!=null&&text(action(IDS.getFirst()),"type").equals("StashVisit");
        if(!local){
            assert poll(IDS.get(1))==null : "No TPA while leader is waiting for its home cooldown";
            homeSent(IDS.getFirst());
            String stale=text(run(IDS.getFirst()),"stashTravelToken");run(IDS.getFirst()).addProperty("stashTravelToken",UUID.randomUUID().toString());assert poll(IDS.get(1))==null;run(IDS.getFirst()).addProperty("stashTravelToken",stale);
        }
        for(String id:IDS.subList(1,3)){
            if(text(action(id),"type").equals("KitTransferPlan"))assert poll(id)!=null;
            assert text(action(id),"type").equals(local?"Wait":"Tpa") : "Followers are released before the leader arrives";
            if(!local)assert action(id).get("warmupTicks").getAsInt()==300&&text(action(id).getAsJsonObject("arrivalStash"),"name").equals(x==0?"Source":"Destination");
        }
        for(String id:IDS){position(id,x);finish(id,json("{ok:true}"));}
        assert poll(IDS.getFirst())==null&&poll(IDS.get(1))==null : "Arrival alone is not permission to race a slower worker";
        assert poll(IDS.get(2))!=null;assert poll(IDS.getFirst())!=null&&poll(IDS.get(1))!=null;
    }
    static void run() throws Exception {
        var test=new StashTransferTeamCheck();
        JsonObject malformed=test.action(IDS.getFirst()).deepCopy();malformed.addProperty("step",1);rejects(()->StashTransferTeam.checkedRequest(malformed));
        test.task.addProperty("paused",true);assert test.poll(IDS.getFirst())==null;test.task.addProperty("paused",false);
        test.run(IDS.get(1)).addProperty("status","Inspection required");assert test.poll(IDS.getFirst())==null : "Resume must wait for slower checkpoint reconciliation, not fail the first returning worker";test.run(IDS.get(1)).addProperty("status","Running");
        test.travel(0);
        Set<Integer> sources=new HashSet<>();
        for(String id:IDS){JsonObject a=test.action(id);for(JsonElement p:a.getAsJsonArray("picks"))assert sources.add(p.getAsJsonObject().get("x").getAsInt());assert a.get("requireLocal").getAsBoolean();assert a.size()<=32&&!a.has("leader")&&!a.has("tpaWarmupTicks") : "Coordinator-only fields cannot overflow native action validation";}
        assert sources.size()==9;
        test.finish(IDS.getFirst(),json("{ok:true,picked:2,nextPick:1,sourceExhausted:false}"));assert test.poll(IDS.getFirst())==null;
        // Restart before slower workers finish; the first worker's pickup acknowledgement is durable.
        test.task=JsonParser.parseString(test.task.toString()).getAsJsonObject();assert test.poll(IDS.getFirst())==null;
        test.finish(IDS.get(1),json("{ok:true,picked:3,nextPick:2,sourceExhausted:false}"));assert test.poll(IDS.get(1))==null;
        test.finish(IDS.get(2),json("{ok:true,picked:1,nextPick:3,sourceExhausted:true}"));assert test.poll(IDS.get(2))==null : "Followers wait for the leader's next home command, not its arrival";
        assert test.poll(IDS.getFirst())!=null;test.homeSent(IDS.getFirst());assert test.poll(IDS.get(1))!=null&&test.poll(IDS.get(2))!=null;
        // If the server snapshots the old position, retry the teleport, not any inventory action.
        test.finish(IDS.get(1),json("{ok:false}"));assert test.poll(IDS.get(1))!=null&&text(test.action(IDS.get(1)),"type").equals("Tpa");
        for(String id:IDS){test.position(id,10000);test.finish(id,json("{ok:true}"));}
        assert test.poll(IDS.getFirst())==null&&test.poll(IDS.get(1))==null;assert test.poll(IDS.get(2))!=null;assert test.poll(IDS.getFirst())!=null&&test.poll(IDS.get(1))!=null;
        Set<Integer> columns=new HashSet<>();
        for(String id:IDS){
            JsonObject a=test.action(id);assert text(a,"type").equals("StashDeposit");
            JsonObject m=message("stash-findings");m.addProperty("task",text(test.task,"id"));m.addProperty("run",text(test.run(id),"id"));m.addProperty("token",text(test.run(id),"token"));m.add("action",a.deepCopy());m.addProperty("delivery",1);
            JsonObject ack=StashCatalog.accept(test.root,test.task,test.run(id),id,"A",m);assert columns.add(ack.getAsJsonObject("column").get("x").getAsInt()) : "Each worker leases a separate destination column";
            assert ack.equals(StashCatalog.accept(test.root,test.task,test.run(id),id,"A",m));assert test.run(id).getAsJsonArray("depositExcluded").isEmpty() : "Other workers' temporary leases must not become permanent exclusions";
        }
        // Lost replies + a departed worker + host reload cannot hand out its unresolved lease.
        test.task=JsonParser.parseString(test.task.toString()).getAsJsonObject();
        test.observations.remove(UUID.fromString(IDS.get(1)));test.run(IDS.get(1)).addProperty("status","Inspection required");
        Set<Integer> replayedColumns=new HashSet<>();
        for(String id:IDS){
            JsonObject m=message("stash-findings");m.addProperty("task",text(test.task,"id"));m.addProperty("run",text(test.run(id),"id"));m.addProperty("token",text(test.run(id),"token"));m.add("action",test.action(id).deepCopy());m.addProperty("delivery",1);
            JsonObject ack=StashCatalog.accept(test.root,test.task,test.run(id),id,"A",m);
            assert replayedColumns.add(ack.getAsJsonObject("column").get("x").getAsInt())&&test.run(id).get("depositDelivery").getAsInt()==1;
        }
        assert replayedColumns.equals(columns) : "Reconnect returns existing reservations, not replacement inventory work";
        test.position(IDS.get(1),10000);test.run(IDS.get(1)).addProperty("status","Running");
        test.finish(IDS.getFirst(),json("{ok:true}"));assert test.poll(IDS.getFirst())==null;assert test.poll(IDS.getFirst())==null;assert test.task.getAsJsonObject("stashTeam").get("delivered").getAsInt()==2 : "Deposit completion counted only once despite retry";
        test.finish(IDS.get(1),json("{ok:true,deposited:1,missing:2}"));assert test.poll(IDS.get(1))==null;test.finish(IDS.get(2),json("{ok:true}"));assert test.poll(IDS.get(2))==null;
        assert test.task.getAsJsonObject("stashTeam").get("delivered").getAsInt()==4&&test.task.getAsJsonObject("stashTeam").get("missing").getAsInt()==2 : "Crew receipts count confirmed deposits, not manually removed kits";
        // Cancellation never issues another inventory command, including a pending barrier reply.
        test.task.addProperty("cancelled",true);assert test.poll(IDS.getFirst())==null;test.task.addProperty("cancelled",false);
        // The next source leg begins only after all deposits, preserving distinct per-worker cursors.
        test.travel(0);
        assert test.action(IDS.getFirst()).get("pickIndex").getAsInt()==1&&test.action(IDS.get(1)).get("pickIndex").getAsInt()==2&&test.action(IDS.get(2)).get("pickIndex").getAsInt()==0;
        for(int empty=0;empty<2;empty++){
            for(String id:IDS)test.finish(id,json("{ok:true,picked:0,nextPick:3,sourceExhausted:true}"));
            assert test.poll(IDS.getFirst())==null&&test.poll(IDS.get(1))==null;
            if(empty==1){assert test.poll(IDS.get(2)).get("done").getAsBoolean();assert test.poll(IDS.getFirst()).get("done").getAsBoolean()&&test.poll(IDS.get(1)).get("done").getAsBoolean();}
            else{assert test.poll(IDS.get(2))!=null;test.travel(0);}
        }
        assert test.task.getAsJsonObject("stashTeam").get("delivered").getAsInt()==4&&test.task.getAsJsonObject("stashTeam").getAsJsonObject("byKit").get(test.kit).getAsInt()==4;
        assert IDS.stream().allMatch(id->text(test.action(id),"type").equals("Done"));
        System.out.println("Crew stash transfer checks passed: overlapping home/TPA warmups, three workers, disjoint inventory work, barriers, retry, restart, cancellation and exhaustion.");
    }
}
