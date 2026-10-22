package dev.monocle.coordinator;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import dev.monocle.client.systems.bots.BotLua;
import dev.monocle.client.systems.bots.BotWorkflows;
import static dev.monocle.coordinator.TaskWire.text;

public final class StashSortExecutionTest {
    private static final String CREW="Default",SCOPE="server\nminecraft:the_end",WORKER="00000000-0000-0000-0000-000000000001";
    private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    private static String key(JsonObject o){return o.get("x").getAsInt()+","+o.get("y").getAsInt()+","+o.get("z").getAsInt();}
    private static JsonObject box(String material){JsonObject b=json("{slot:0,item:'minecraft:black_shulker_box',name:'Same name',quantity:1,contentsKnown:true,items:{}}");b.getAsJsonObject("items").addProperty("minecraft:"+material,1728);return b;}
    private static JsonObject observation(int x,List<JsonObject> boxes){
        JsonObject o=json("{y:64,z:0,status:'observed',block:'minecraft:chest',reason:'',capacity:4,slotCount:4,occupiedSlots:0,items:{},shulkers:[]}");o.addProperty("x",x);int slot=0;
        for(JsonObject value:boxes){JsonObject b=value.deepCopy();b.addProperty("slot",slot++);o.getAsJsonArray("shulkers").add(b);}
        o.addProperty("occupiedSlots",slot);o.getAsJsonObject("items").addProperty("minecraft:black_shulker_box",slot);
        if(slot==0)o.getAsJsonObject("items").remove("minecraft:black_shulker_box");
        for(JsonObject b:boxes)for(var e:b.getAsJsonObject("items").entrySet())o.getAsJsonObject("items").addProperty(e.getKey(),(o.getAsJsonObject("items").has(e.getKey())?o.getAsJsonObject("items").get(e.getKey()).getAsLong():0)+e.getValue().getAsLong());return o;
    }
    public static void run()throws Exception{
        verificationRetries();
        for(int seed=0;seed<24;seed++)replay(seed);
        System.out.println("Write sort checks passed: growth ownership, cyclic mixed layouts, exact receipts, duplicate replies, host restarts, cargo conservation and reservations.");
    }
    private static void verificationRetries()throws Exception{
        Path root=Files.createTempDirectory("sort-read-retry-");JsonObject bounds=json("{name:'Main',homeName:'main',minX:0,maxX:4,minY:64,maxY:64,minZ:0,maxZ:0,lazyMode:false}");
        for(int x=0;x<=4;x+=2)StashCatalog.save(root,CREW,SCOPE,bounds,observation(x,x==2?List.of(box("obsidian")):List.of()));
        JsonObject cat=KitCategories.definition("storage-obsidian","Obsidian","Items",List.of("minecraft:obsidian"));KitCategories.save(root,CREW,SCOPE,text(KitCategories.get(root,CREW,SCOPE),"revision"),cat);
        JsonObject plan=StashSortPlanner.preview(StashCatalog.get(root,CREW,SCOPE,"Main"),KitCategories.get(root,CREW,SCOPE),1,3,List.of(),null,0),args=json("{name:'Main',buffers:1,carrySlots:3,growthColumns:0}");args.addProperty("planId",text(plan,"planId"));
        JsonObject task=json("{crew:'Default',server:'server',dimension:'minecraft:the_end',status:'Running',runs:{}}");task.addProperty("id",UUID.randomUUID().toString());task.add("args",args);task.add("stashSort",StashSortExecution.prepare(root,CREW,SCOPE,args,WORKER));JsonObject run=json("{status:'Running'}");run.addProperty("id",UUID.randomUUID().toString());task.getAsJsonObject("runs").add(WORKER,run);
        JsonObject s=task.getAsJsonObject("stashSort");s.addProperty("moved",180);s.addProperty("batches",6);JsonObject result=readPlan(root,task,run);
        for(int retry=0;retry<4;retry++){
            JsonObject action=result.getAsJsonObject("action");JsonObject o=observation(0,List.of());o.addProperty("status","unscanned");o.remove("slotCount");o.remove("occupiedSlots");recordRead(task,run,action,o);
            assert TaskWire.flag(s.getAsJsonObject("pending").getAsJsonObject("evidence").getAsJsonObject("0,64,0"),"retryable");
            if(retry==3){try{readPlan(root,task,run);throw new AssertionError("Unread scans retried forever");}catch(IllegalStateException expected){assert expected.getMessage().contains("three retries");}break;}
            result=readPlan(root,task,run);assert text(result.getAsJsonObject("action"),"type").equals("StashScan");assert s.getAsJsonObject("pending").get("scanRetries").getAsInt()==retry+1;
            JsonObject saved=task.deepCopy();assert result.equals(StashSortExecution.next(root,task,run,WORKER))&&task.equals(saved):"A duplicate planner request consumed another scan retry";
            task=JsonParser.parseString(task.toString()).getAsJsonObject();s=task.getAsJsonObject("stashSort");run=task.getAsJsonObject("runs").getAsJsonObject(WORKER);StashSortExecution.validateSaved(task);
        }
        task.addProperty("status","Failed");run.addProperty("status","Failed");run.addProperty("detail","Unread scan after retries");String old=text(run,"id");JsonObject columns=s.getAsJsonArray("columns").get(0).getAsJsonObject().deepCopy(),stock=s.getAsJsonObject("initialStock").deepCopy();
        JsonObject blocked=task.deepCopy();blocked.getAsJsonObject("stashSort").add("cargo",new JsonObject());try{StashSortExecution.retryRead(root,blocked);throw new AssertionError("Cargo recovery replayed a scan");}catch(IllegalStateException expected){}
        blocked=task.deepCopy();blocked.addProperty("cancelled",true);try{StashSortExecution.retryRead(root,blocked);throw new AssertionError("Cancelled sort resumed");}catch(IllegalStateException expected){}
        StashSortExecution.retryRead(root,task);run=task.getAsJsonObject("runs").getAsJsonObject(WORKER);assert !old.equals(text(run,"id"))&&text(s.getAsJsonObject("lastReadFailure"),"run").equals(old);assert s.get("moved").getAsInt()==180&&s.get("batches").getAsInt()==6&&s.get("cursor").getAsInt()==0&&stock.equals(s.get("initialStock"))&&columns.equals(s.getAsJsonArray("columns").get(0));assert !s.has("pending")&&s.get("sequence").getAsInt()==0;
        result=readPlan(root,task,run);JsonObject action=result.getAsJsonObject("action");assert text(action,"type").equals("StashScan");recordRead(task,run,action,observation(0,List.of(box("obsidian"))));
        try{readPlan(root,task,run);throw new AssertionError("A real stock mismatch was treated as an unread retry");}catch(IllegalStateException expected){assert expected.getMessage().contains("differs from the sorting ledger");}
        assert !s.getAsJsonObject("pending").has("scanRetries");StashSortExecution.validateSaved(task);
    }
    private static JsonObject readPlan(Path root,JsonObject task,JsonObject run){JsonObject a=json("{type:'StashSortPlan'}");a.add("sequence",task.getAsJsonObject("stashSort").get("sequence").deepCopy());run.add("action",a);run.addProperty("token",UUID.randomUUID().toString());return StashSortExecution.next(root,task,run,WORKER);}
    private static void recordRead(JsonObject task,JsonObject run,JsonObject action,JsonObject observation){run.add("action",action.deepCopy());run.addProperty("token",UUID.randomUUID().toString());JsonObject finding=new JsonObject();finding.add("action",action.deepCopy());finding.add("token",run.get("token").deepCopy());finding.add("observation",observation);StashSortExecution.finding(task,run,finding);}
    private static void replay(int seed)throws Exception{
        Path root=Files.createTempDirectory("stash-write-sort-");JsonObject bounds=json("{name:'Main',homeName:'main',minX:0,maxX:22,minY:64,maxY:64,minZ:0,maxZ:0,lazyMode:false}");
        Map<String,List<JsonObject>> world=new LinkedHashMap<>();JsonObject a=box("obsidian"),b=box("crying_obsidian");Random random=new Random(seed);
        for(int i=0;i<12;i++)world.put(i*2+",64,0",new ArrayList<>());
        if(seed==0){world.get("0,64,0").addAll(List.of(b,b,b));world.get("2,64,0").addAll(List.of(a,a,a));world.get("4,64,0").addAll(List.of(b,b));world.get("6,64,0").addAll(List.of(a,a));}
        else for(int i=0;i<8;i++)for(int n=0,limit=seed>=12?4:random.nextInt(5);n<limit;n++)world.get(i*2+",64,0").add(random.nextBoolean()?a:b);
        if(world.values().stream().mapToInt(List::size).sum()==0)world.get("0,64,0").add(a);
        for(var entry:world.entrySet())StashCatalog.save(root,CREW,SCOPE,bounds,observation(Integer.parseInt(entry.getKey().split(",")[0]),entry.getValue()));
        for(String material:List.of("obsidian","crying_obsidian")){JsonObject category=KitCategories.definition("storage-"+material.replace('_','-'),material,"Items",List.of("minecraft:"+material));category.addProperty("usage","Storage");JsonObject cats=KitCategories.get(root,CREW,SCOPE);KitCategories.save(root,CREW,SCOPE,text(cats,"revision"),category);}
        JsonObject db=StashCatalog.get(root,CREW,SCOPE,"Main"),cats=KitCategories.get(root,CREW,SCOPE),plan=StashSortPlanner.preview(db,cats,2,3,List.of(),null,1);
        JsonObject args=json("{name:'Main',buffers:2,carrySlots:3,growthColumns:1}");args.addProperty("planId",text(plan,"planId"));
        JsonObject task=json("{crew:'Default',server:'server',dimension:'minecraft:the_end',status:'Running',runs:{}}");task.addProperty("id",UUID.randomUUID().toString());task.add("args",args.deepCopy());task.add("stashSort",StashSortExecution.prepare(root,CREW,SCOPE,args,WORKER));JsonObject run=json("{status:'Running'}");run.addProperty("id",UUID.randomUUID().toString());task.getAsJsonObject("runs").add(WORKER,run);
        List<JsonObject> cargo=new ArrayList<>();JsonObject request=json("{type:'StashSortPlan',sequence:0}");long initial=world.values().stream().mapToLong(List::size).sum();boolean done=false,probedBlocked=false;
        String script=new BotWorkflows(null).get(StashSortExecution.WORKFLOW).script();var decision=BotLua.next(script,new JsonObject(),args,null,null);JsonObject lua=decision.state();assert decision.action().equals(request);
        for(int step=0;step<2000;step++){
            run.add("action",request.deepCopy());run.addProperty("token",UUID.randomUUID().toString());JsonObject result=StashSortExecution.next(root,task,run,WORKER),checkpoint=task.deepCopy();assert result.equals(StashSortExecution.next(root,task,run,WORKER))&&task.equals(checkpoint):"Lost planner reply replayed an instruction";
            if(TaskWire.flag(result,"done")){assert !StashSortExecution.holds(task)&&cargo.isEmpty();assert text(BotLua.next(script,lua,args,result,null).action(),"type").equals("Done");done=true;break;}
            JsonObject receipt=new JsonObject();
            if(TaskWire.flag(result,"probe")){boolean blocked=seed==0&&!probedBlocked;probedBlocked=true;receipt.addProperty("freeSlots",blocked?0:3);receipt.addProperty("shulkers",blocked?1:cargo.size());JsonObject reply=result.deepCopy();reply.add("freeSlots",receipt.get("freeSlots"));reply.add("shulkers",receipt.get("shulkers"));decision=BotLua.next(script,lua,args,reply,null);lua=decision.state();}
            else{
                JsonObject action=result.getAsJsonObject("action").deepCopy();
                if(text(action,"type").equals("StashScan")){
                    action.add("dimension",task.get("dimension").deepCopy());
                    JsonObject wrong=action.deepCopy();wrong.addProperty("dimension","minecraft:overworld");boolean rejected=false;try{StashSortExecution.validateAction(task,wrong);}catch(IllegalArgumentException expected){rejected=true;}assert rejected:"Scan enrichment accepted another dimension";
                    wrong=action.deepCopy();wrong.addProperty("minX",-1);rejected=false;try{StashSortExecution.validateAction(task,wrong);}catch(IllegalArgumentException expected){rejected=true;}assert rejected:"Scan enrichment accepted changed bounds";
                }
                if(text(action,"type").equals("StashResupply")){
                    if(seed==0)task.getAsJsonObject("stashSort").getAsJsonObject("pending").getAsJsonObject("action").remove("pickIndex"); // Replay a pre-fix journal with the native default added only by the client.
                    action.addProperty("pickIndex",0);JsonObject wrong=action.deepCopy();wrong.addProperty("pickIndex",1);boolean rejected=false;try{StashSortExecution.validateAction(task,wrong);}catch(IllegalArgumentException expected){rejected=true;}assert rejected:"Withdrawal enrichment changed the journaled pickup position";
                }
                StashSortExecution.validateAction(task,action);run.add("action",action);run.addProperty("token",UUID.randomUUID().toString());
                decision=BotLua.next(script,lua,args,result,null);lua=decision.state();assert decision.action().equals(result.get("action")):"Lua changed the journaled native action";
                switch(text(action,"type")){
                    case "Wait"->{assert TaskWire.flag(task.getAsJsonObject("stashSort"),"waitingInventory");}
                    case "StashScan"->{int delivery=0;for(JsonElement cell:action.getAsJsonArray("scanCells")){
                        JsonObject p=cell.getAsJsonObject(),o=observation(p.get("x").getAsInt(),world.get(key(p))),m=json("{type:'stash-findings'}");m.addProperty("task",text(task,"id"));m.addProperty("run",text(run,"id"));m.addProperty("token",text(run,"token"));m.add("action",action.deepCopy());m.addProperty("delivery",++delivery);m.add("observation",o);
                        assert StashCatalog.accept(root,task,run,WORKER,CREW,m,true)!=null;StashSortExecution.finding(task,run,m);
                    }}
                    case "StashResupply"->{
                        assert cargo.isEmpty();JsonArray taken=new JsonArray();int wanted=action.get("count").getAsInt();
                        for(JsonElement value:action.getAsJsonArray("picks")){JsonObject pick=value.getAsJsonObject();assert pick.get("kitTypeId").equals(action.get("kitTypeId"))&&pick.get("incomplete").equals(action.get("incomplete")):"Sorting pickup must satisfy the native kit matcher contract";}
                        for(JsonElement value:action.getAsJsonArray("picks")){JsonObject pick=value.getAsJsonObject();List<JsonObject> contents=world.get(key(pick));for(var it=contents.iterator();it.hasNext()&&cargo.size()<wanted;){JsonObject candidate=it.next();if(!StashCatalog.kitTypeId(candidate).equals(text(action,"kitTypeId")))continue;cargo.add(candidate);it.remove();JsonObject proof=pick.deepCopy();for(String axis:List.of("x","y","z"))proof.addProperty(axis,proof.get(axis).getAsDouble());proof.add("items",candidate.get("items").deepCopy());taken.add(proof);}}
                        assert cargo.size()==wanted:"Simulation lost source stock";JsonObject m=json("{stashWithdrawal:{}}");m.add("action",action);m.getAsJsonObject("stashWithdrawal").add("withdrawn",taken);m.addProperty("run",text(run,"id"));m.addProperty("token",text(run,"token"));m.getAsJsonObject("stashWithdrawal").addProperty("stash","Main");StashCatalog.beginTransfer(root,CREW,SCOPE,run,m);StashCatalog.withdrawalReceipt(root,CREW,SCOPE,run,m);StashSortExecution.evidence(task,run,m);JsonObject saved=task.deepCopy();StashCatalog.withdrawalReceipt(root,CREW,SCOPE,run,m);StashSortExecution.evidence(task,run,m);assert saved.equals(task):"Withdrawal receipt duplicated";receipt.addProperty("picked",cargo.size());
                    }
                    case "StashDeposit"->{
                        JsonObject dest=action.getAsJsonObject("targetColumn");List<JsonObject> contents=world.get(key(dest));assert contents.size()+cargo.size()<=4;
                        JsonObject finding=json("{type:'stash-findings',delivery:1}");finding.addProperty("task",text(task,"id"));finding.addProperty("run",text(run,"id"));finding.addProperty("token",text(run,"token"));finding.add("action",action);
                        JsonObject ack=StashCatalog.accept(root,task,run,WORKER,CREW,finding,true);assert !TaskWire.flag(ack,"permit");finding.addProperty("delivery",2);finding.add("observation",observation(dest.get("x").getAsInt(),contents));ack=StashCatalog.accept(root,task,run,WORKER,CREW,finding,true);assert TaskWire.flag(ack,"permit"):ack;
                        contents.addAll(cargo);JsonObject evidence=json("{stashDeposit:{receiptComplete:true,depositedBoxes:[]}}");evidence.add("action",action);evidence.getAsJsonObject("stashDeposit").addProperty("deposited",cargo.size());for(JsonObject box:cargo){JsonObject proof=box.deepCopy();proof.addProperty("kitTypeId",text(action,"kitTypeId"));evidence.getAsJsonObject("stashDeposit").getAsJsonArray("depositedBoxes").add(proof);}evidence.addProperty("token",text(run,"token"));evidence.addProperty("run",text(run,"id"));evidence.getAsJsonObject("stashDeposit").addProperty("stash","Main");JsonArray touched=new JsonArray();touched.add(dest.deepCopy());evidence.getAsJsonObject("stashDeposit").add("touched",touched);for(JsonElement proof:evidence.getAsJsonObject("stashDeposit").getAsJsonArray("depositedBoxes"))for(String axis:List.of("x","y","z"))proof.getAsJsonObject().add(axis,dest.get(axis).deepCopy());StashCatalog.depositReceipt(root,CREW,SCOPE,run,evidence);StashSortExecution.evidence(task,run,evidence);JsonObject saved=task.deepCopy();StashCatalog.depositReceipt(root,CREW,SCOPE,run,evidence);StashSortExecution.evidence(task,run,evidence);assert task.equals(saved):"Deposit receipt duplicated";receipt.addProperty("deposited",cargo.size());receipt.addProperty("missing",0);cargo.clear();
                    }
                    default->throw new AssertionError(action);
                }
                decision=BotLua.next(script,lua,args,receipt,null);lua=decision.state();
            }
            assert initial==world.values().stream().mapToLong(List::size).sum()+cargo.size();
            request=json("{type:'StashSortPlan'}");request.add("sequence",result.get("sequence"));request.add("receipt",receipt);
            assert request.equals(decision.action()):"Lua did not acknowledge the correct sorting instruction";lua=JsonParser.parseString(lua.toString()).getAsJsonObject();
            task=JsonParser.parseString(task.toString()).getAsJsonObject();run=task.getAsJsonObject("runs").getAsJsonObject(WORKER);
            StashSortExecution.validateSaved(task);
        }
        assert done:"Sorter did not converge";
        JsonObject after=StashCatalog.get(root,CREW,SCOPE,"Main");
        long estimated=StashStock.estimates(after).asMap().values().stream().mapToLong(e->e.getAsJsonObject().get("complete").getAsLong()+e.getAsJsonObject().get("incomplete").getAsLong()).sum();assert estimated==initial:"Internal sorting inflated or lost the stash's transfer-adjusted stock";
        for(JsonElement value:task.getAsJsonObject("stashSort").getAsJsonArray("columns")){JsonObject c=value.getAsJsonObject();assert TaskWire.flag(c,"finished");if(TaskWire.flag(c,"growth")){assert world.get(key(c)).isEmpty();JsonObject reservation=after.getAsJsonObject("columnReservations").getAsJsonObject(c.get("x")+","+c.get("z"));assert text(reservation,"categoryId").equals(text(c,"target")):"Growth reservation belongs to the wrong category";}}
    }
}
