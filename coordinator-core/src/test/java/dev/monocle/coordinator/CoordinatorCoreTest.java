package dev.monocle.coordinator;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.monocle.client.systems.bots.BotLua;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Standalone replay of production policies, without a Minecraft runtime or live worker. */
public final class CoordinatorCoreTest {
    private static final UUID WORKER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static void supplyRecovery() throws Exception {
        var path = java.nio.file.Files.createTempDirectory("monocle-supply-recovery-check-").resolve("records.json");
        var journal = new SupplyRecovery(path);
        JsonObject r = new JsonObject(); r.addProperty("id",UUID.randomUUID().toString()); r.addProperty("owner",WORKER.toString());
        r.addProperty("scope","server\nnether"); r.addProperty("x",0); r.addProperty("y",116); r.addProperty("z",-100);
        r.addProperty("stage","placing"); r.addProperty("baseline",2); r.addProperty("expected",1); r.add("stack",new JsonObject());
        journal.put(r);
        var restarted = new SupplyRecovery(path);
        assert restarted.pending(WORKER.toString(),"server\nnether").size()==1;
        assert restarted.pending(OTHER.toString(),"server\nnether").isEmpty();
        assert restarted.pending(WORKER.toString(),"other\nnether").isEmpty();
        assert SupplyRecovery.confirmed(true,true,true,false,2,2,1,true);
        for (boolean fresh : new boolean[]{true,false}) for(boolean loaded:new boolean[]{true,false})
            assert SupplyRecovery.confirmed(fresh,loaded,true,false,3,2,1,false)==(fresh && loaded);
        assert !SupplyRecovery.confirmed(true,true,true,true,3,2,1,false);
        assert !SupplyRecovery.confirmed(true,true,false,false,3,2,1,false);
        assert !SupplyRecovery.confirmed(true,true,true,false,2,2,1,false);
        JsonObject bad=r.deepCopy(); bad.addProperty("x",30_000_000); rejects(() -> restarted.put(bad));
        assert restarted.pending(WORKER.toString(),"server\nnether").size()==1;
        restarted.resolved(r.get("id").getAsString());
        assert new SupplyRecovery(path).pending(WORKER.toString(),"server\nnether").isEmpty();
        JsonObject invalid = new JsonObject(); invalid.addProperty("version",1); TaskFiles.write(path,invalid);
        rejects(() -> new SupplyRecovery(path));
        assert TaskFiles.read(path).equals(invalid) : "Malformed recovery data must never be overwritten as an empty ledger";
    }

    private static void liveConfiguration() {
        JsonObject task = new JsonObject(), runs = new JsonObject(), run = new JsonObject();
        run.addProperty("id", UUID.randomUUID().toString()); run.addProperty("status", "Running");
        runs.add(WORKER.toString(), run); task.add("runs", runs); task.addProperty("status", "Running");
        JsonObject modules = JsonParser.parseString("{\"speed\":{\"active\":true,\"settings\":\"{}\"}}").getAsJsonObject();
        rejects(() -> TaskWire.configure(task, WORKER, modules));
        run.addProperty("configurationVersion", 1);
        TaskWire.configure(task, WORKER, modules);
        assert TaskWire.configurationToSend(run, 0).get("revision").getAsInt() == 1;
        assert TaskWire.configurationToSend(run, 999) == null;
        assert TaskWire.configurationToSend(run, 1000) != null;
        JsonObject before = task.deepCopy();
        rejects(() -> TaskWire.configure(task, WORKER, modules));
        assert task.equals(before) : "Pending updates cannot be silently replaced";
        JsonObject ack = TaskWire.message("status"); ack.addProperty("run", TaskWire.text(run, "id")); ack.addProperty("status", "Running"); ack.addProperty("configRevision", 1);
        TaskWire.applyStatus(task, run, ack, true);
        assert TaskWire.configurationToSend(run, 2000) == null;
        TaskWire.configure(task, null, modules);
        assert TaskWire.configurationToSend(run, 2001).get("revision").getAsInt() == 2;
        ack.addProperty("configRevision", 2); ack.addProperty("configError", "Unknown setting");
        TaskWire.applyStatus(task, run, ack, true);
        assert TaskWire.text(run, "status").equals("Running") && TaskWire.configurationToSend(run, 3000) == null : "Rejected settings do not stop the job or retry forever";
        ack.addProperty("configRevision", 1); ack.addProperty("configError", ""); TaskWire.applyStatus(task, run, ack, true);
        assert run.get("configRevision").getAsInt() == 2 && TaskWire.text(run, "configError").equals("Unknown setting");
        rejects(() -> TaskWire.configure(task, OTHER, modules));
        rejects(() -> TaskWire.checkedConfiguration(JsonParser.parseString("{\"highway-builder\":{\"active\":true,\"settings\":\"{}\"}}").getAsJsonObject()));
        task.addProperty("cancelled", true); rejects(() -> TaskWire.configure(task, WORKER, modules));
    }

    public static void main(String[] args) throws Exception {
        boolean enabled = false; assert enabled = true;
        if (!enabled) throw new IllegalStateException("Run with assertions enabled");
        liveConfiguration();
        stashScan();
        supplyRecovery();
        highwayStartup();
        independentSupplies();
        OperationsLibraryTest.run();
        CrewTelemetryTest.run();
        for (String absent : List.of("net.minecraft.client.Minecraft", "net.fabricmc.loader.api.FabricLoader", "org.lwjgl.glfw.GLFW")) {
            try { Class.forName(absent, false, CoordinatorCoreTest.class.getClassLoader()); throw new AssertionError("Core acquired game dependency: " + absent); }
            catch (ClassNotFoundException expected) { }
        }
        List<String> expected = List.of("older", "urgent", "older", "urgent", "older", "Cancelled", "resume", "Cancelled", "Inspection required");
        assert replay(false).equals(expected);
        assert replay(true).equals(expected) : "Serialized checkpoints must preserve the same decision trace";
        for (int priority : new int[]{-1000, -1, 0, 1, 1000}) QueuePolicy.checkedPriority(priority);
        for (int priority : new int[]{Integer.MIN_VALUE, -1001, 1001, Integer.MAX_VALUE})
            rejects(() -> QueuePolicy.checkedPriority(priority));

        for (String state : List.of("Queued", "Sending", "Ready", "Running", "Suspending", "Suspended", "Inspection required", "Complete", "Failed", "Cancelled")) {
            JsonObject task = task("test", 1), run = run(task); run.addProperty("status", state);
            QueuePolicy.reconcileMissingRun(task, run);
            String next = state.equals("Queued") || state.equals("Sending") ? "Queued" : QueuePolicy.terminal(state) ? state : "Inspection required";
            assert run.get("status").getAsString().equals(next) : state;
            task = task("cancel", 1); task.addProperty("cancelled", true); run = run(task); run.addProperty("status", state);
            QueuePolicy.reconcileMissingRun(task, run);
            assert run.get("status").getAsString().equals(QueuePolicy.terminal(state) ? state : "Cancelled");
        }
        JsonObject issued = task("interrupted", 1); run(issued).addProperty("status", "Sending"); run(issued).addProperty("resumeSent", true);
        QueuePolicy.reconcileMissingRun(issued, run(issued));
        assert QueuePolicy.choose(List.of(issued), WORKER) == null : "An executed checkpoint cannot be replayed as an unsent package";
        for (String state : List.of("Complete", "Cancelled", "Failed")) {
            JsonObject ended = task("ended", 100); ended.addProperty("status", state);
            assert !QueuePolicy.pause(ended) && !QueuePolicy.cancel(ended);
            rejects(() -> QueuePolicy.resume(ended));
            assert QueuePolicy.summarizedStatus(ended).equals(state);
            assert QueuePolicy.choose(List.of(ended), WORKER) == null;
        }
        JsonObject perWorker = task("per-worker", 3);
        perWorker.getAsJsonObject("overrides").addProperty(WORKER.toString(), 0);
        assert QueuePolicy.priority(perWorker, WORKER) == 0 && QueuePolicy.priority(perWorker, OTHER) == 3;
        assert QueuePolicy.choose(List.of(perWorker), OTHER) == null;
        timing(); lua(); observations(); verification(); dispatch();
        System.out.println("Coordinator core checks passed without Minecraft/Fabric/LWJGL: queues, recovery, teleport, Lua, player observations and row verification authority.");
    }
    private static void stashScan() throws Exception {
        JsonObject p=JsonParser.parseString("{\"name\":\"Depot\",\"minX\":-2,\"maxX\":2,\"minY\":116,\"maxY\":117,\"minZ\":-1,\"maxZ\":1}").getAsJsonObject();
        p=StashCatalog.plan(p);
        assert p.get("homeName").getAsString().isEmpty()&&p.get("homeWarmupTicks").getAsInt()==300&&p.get("homeCooldownTicks").getAsInt()==12_000;
        JsonObject home=p.deepCopy();home.addProperty("homeName","main-stash_1");home.addProperty("homeWarmupTicks",400);home.addProperty("homeCooldownTicks",24_000);assert StashCatalog.plan(home).get("homeName").getAsString().equals("main-stash_1");
        JsonObject badHome=p.deepCopy();badHome.addProperty("homeName","bad home");rejects(()->StashCatalog.plan(badHome));
        for(int workers=1;workers<=5;workers++)for(int x=-2;x<=2;x++)for(int y=116;y<=117;y++)for(int z=-1;z<=1;z++){
            int owners=0;for(int index=0;index<workers;index++){JsonObject a=p.deepCopy();a.addProperty("workerCount",workers);a.addProperty("workerIndex",index);if(StashCatalog.owns(a,x,y,z))owners++;}assert owners==1;
        }
        var root=java.nio.file.Files.createTempDirectory("monocle-stash-check-");
        StashCatalog.define(root,"A","server\nnether",p);
        StashCatalog.define(root,"A","server\nnether",home,"00000000-0000-0000-0000-000000000001");
        JsonObject routed=StashCatalog.route(root,"A","server\nnether",p,"00000000-0000-0000-0000-000000000001");assert routed.get("homeName").getAsString().equals("main-stash_1")&&StashCatalog.list(root).get(0).getAsJsonObject().getAsJsonObject("homes").size()==1;
        StashCatalog.cacheRemote(root,StashCatalog.list(root));assert StashCatalog.remote(root).size()==1;
        assert StashCatalog.list(root).size()==1&&StashCatalog.list(root).get(0).getAsJsonObject().get("observed").getAsInt()==0 : "Exported definitions are visible before scanning";
        JsonObject observation=JsonParser.parseString("{\"x\":0,\"y\":116,\"z\":0,\"status\":\"observed\",\"block\":\"minecraft:chest\",\"reason\":\"\",\"items\":{\"minecraft:stone\":1728},\"shulkers\":[]}").getAsJsonObject();
        StashCatalog.save(root,"A","server\nnether",p,observation);StashCatalog.save(root,"A","server\nnether",p,observation);
        assert StashCatalog.list(root).size()==1 && StashCatalog.list(root).get(0).getAsJsonObject().getAsJsonObject("items").get("minecraft:stone").getAsInt()==1728 : "Retries never add stock twice";
        JsonObject missed=observation.deepCopy();missed.addProperty("status","unscanned");missed.add("items",new JsonObject());missed.addProperty("reason","Opening timed out");
        StashCatalog.save(root,"A","server\nnether",p,missed);
        assert StashCatalog.list(root).get(0).getAsJsonObject().get("unscanned").getAsInt()==1;
        JsonObject inferred=observation.deepCopy();inferred.addProperty("inferred",true);StashCatalog.save(root,"A","server\nnether",p,inferred);
        assert StashCatalog.list(root).get(0).getAsJsonObject().get("inferred").getAsInt()==1&&StashCatalog.list(root).get(0).getAsJsonObject().get("observed").getAsInt()==0 : "Estimates never masquerade as observed containers";
        assert StashCatalog.get(root,"B","server\nnether","Depot").isEmpty();
        JsonObject invalid=observation.deepCopy();invalid.addProperty("x",3);JsonObject assignment=p;
        rejects(()->StashCatalog.save(root,"A","server\nnether",assignment,invalid));
        invalid.addProperty("x",0);invalid.getAsJsonObject("items").addProperty("minecraft:stone",-1);rejects(()->StashCatalog.observation(assignment,invalid));
        JsonObject refillDb=JsonParser.parseString("{containers:{'1,116,2':{status:'observed',shulkers:[{slot:0,dominant:'minecraft:obsidian',mixed:false,items:{'minecraft:obsidian':1728}},{slot:1,dominant:'minecraft:golden_apple',mixed:false,items:{'minecraft:golden_apple':1728}}]},'2,116,2':{status:'observed',inferred:true,shulkers:[{slot:0,dominant:'minecraft:obsidian',mixed:false,items:{'minecraft:obsidian':1728}}]}}}").getAsJsonObject();
        JsonObject needs=JsonParser.parseString("{'minecraft:obsidian':3456,'minecraft:golden_apple':1728,'minecraft:netherrack':100}").getAsJsonObject();JsonArray refill=StashCatalog.refill(refillDb,needs,"minecraft:obsidian",27);
        assert refill.size()==2&&refill.get(0).getAsJsonObject().get("resource").getAsString().equals("minecraft:obsidian") : "Primary shortage is first; inferred stock and sub-box shortages are skipped";
        JsonObject withdrawalPlan=p.deepCopy();withdrawalPlan.addProperty("name","Withdrawals");StashCatalog.define(root,"A","server\nnether",withdrawalPlan);JsonObject withdrawalObservation=observation.deepCopy();JsonArray receipt=refill.deepCopy();receipt.forEach(v->{v.getAsJsonObject().addProperty("x",0);v.getAsJsonObject().addProperty("z",0);});StashCatalog.save(root,"A","server\nnether",withdrawalPlan,withdrawalObservation);StashCatalog.invalidateWithdrawn(root,"A","server\nnether","Withdrawals",receipt);
        assert StashCatalog.get(root,"A","server\nnether","Withdrawals").getAsJsonObject("containers").getAsJsonObject("0,116,0").get("status").getAsString().equals("unscanned") : "Withdrawn containers cannot remain authoritative";
        var decision=BotLua.next("return function(ctx) return bot.stash_scan(ctx.args) end",new JsonObject(),p,null,null);
        assert decision.action().get("type").getAsString().equals("StashScan");
        JsonObject telemetry=JsonParser.parseString("{\"name\":\"Depot\",\"phase\":\"Reading\",\"reason\":\"Awaiting contents\",\"target\":\"0,116,0\",\"movementTarget\":\"\",\"lastAction\":\"Requested opening\",\"discovery\":1,\"volume\":30,\"discovered\":1,\"observed\":0,\"unscanned\":0,\"missingChunks\":0,\"attempts\":1}").getAsJsonObject();
        JsonObject run=new JsonObject();for(int i=0;i<100;i++){telemetry.addProperty("reason","Retry "+i);StashCatalog.telemetry(run,telemetry);}assert run.getAsJsonArray("stashEvents").size()==64;
        StashCatalog.telemetry(run,telemetry);assert run.getAsJsonArray("stashEvents").size()==64;
    }

    private static void independentSupplies() {
        JsonObject empty=JsonParser.parseString("{loose:[0,0,0,0,0],shulkers:[0,0,0,0,0],echest:[0,0,0,0,0],target:[512,3,16,0,4],echestKnown:true}").getAsJsonObject();
        assert !ResourceLedger.possibleDonor(empty,ResourceLedger.MATERIALS);
        empty.addProperty("echestKnown",false);assert !ResourceLedger.possibleDonor(empty,ResourceLedger.MATERIALS);
        empty.getAsJsonArray("loose").set(4,new com.google.gson.JsonPrimitive(1));assert ResourceLedger.possibleDonor(empty,ResourceLedger.MATERIALS);
        empty.addProperty("echestKnown",true);empty.getAsJsonArray("shulkers").set(0,new com.google.gson.JsonPrimitive(513));assert ResourceLedger.possibleDonor(empty,ResourceLedger.MATERIALS);
        JsonObject absent=JsonParser.parseString("{independentSupplies:true,workflow:{version:1,id:'highway-default',name:'Highway Builder',actions:['Excavating','Paving','InventoryShulkers'],duty:'Build'},members:['"+WORKER+"'],activeMembers:[],awayMembers:{'"+WORKER+"':true},suppliers:{}}").getAsJsonObject();
        HighwayCoordinator.applyWorkflowDuties(absent, java.util.Set.of());
        absent.remove("awayMembers");rejects(()->HighwayCoordinator.applyWorkflowDuties(absent,java.util.Set.of()));
        assert RoadForecast.breakTicks(0.1)==10 && RoadForecast.breakTicks(2)==1;
        assert RoadForecast.miningTicks(0.1,true,2)==7.5 && RoadForecast.miningTicks(0.1,false,2)==12;
        assert RoadForecast.rate(100,100,1000,20,5,10)==4;
        assert RoadForecast.rate(0,0,0,20,5,10)==0;
        JsonObject prediction=JsonParser.parseString("{nextBlocks:100,blocksPerSecond:4,ageTicks:10}").getAsJsonObject();
        JsonArray predictionWorkers=new JsonArray();JsonObject predictionWorker=new JsonObject();predictionWorker.add("roadPrediction",prediction);predictionWorkers.add(predictionWorker);
        assert RoadForecast.crew(predictionWorkers).get("nextBlocks").getAsInt()==100;
        prediction.addProperty("nextBlocks",1025);rejects(()->RoadForecast.checked(prediction));
        BotChat chat=new BotChat();UUID commandId=UUID.randomUUID();assert chat.first(commandId)&&!chat.first(commandId);
        assert BotChat.command("/home stash1").equals("/home stash1");rejects(()->BotChat.command("hello\n/stop"));rejects(()->BotChat.command("x".repeat(257)));
        for(int i=0;i<300;i++)chat.append(WORKER,"Default","Worker","world","received","message "+i);
        assert chat.json().size()==256 && chat.json().get(0).getAsJsonObject().get("text").getAsString().equals("message 44");
        BotChat colors=new BotChat();UUID other=UUID.randomUUID();
        JsonArray parts=JsonParser.parseString("[{text:'[Rank] ',color:'#ff5500'},{text:'Alice: hello',color:'#55ffff'}]").getAsJsonArray();
        colors.append(WORKER,"Default","Worker","world","received","[Rank] Alice: hello",parts);
        colors.append(other,"Default","Other","world","received","[Rank] Alice: hello",parts);
        JsonArray grouped=BotChat.grouped(colors.json());
        assert grouped.size()==1 && grouped.get(0).getAsJsonObject().getAsJsonArray("recipients").size()==2;
        assert grouped.get(0).getAsJsonObject().get("parts").equals(parts);
        colors.append(other,"Default","Other","world","received","[Rank] Alice: hello",parts);
        assert BotChat.grouped(colors.json()).size()==2 : "Repeated messages from one worker are not duplicates";
        colors.append(other,"Default","Other","world","received","Private message",null);
        assert BotChat.grouped(colors.json()).size()==3 : "Preserve distinct DMs";
        colors.append(WORKER,"Default","Worker","world","received","wrong",parts);
        assert !colors.json().get(colors.json().size()-1).getAsJsonObject().has("parts") : "Mismatched color runs degrade to plain chat";
        rejects(()->colors.append(WORKER,"Default","Worker","world","received","x",JsonParser.parseString("[{text:'x',color:'red;bad'}]").getAsJsonArray()));
        JsonObject ledger=JsonParser.parseString("{loose:[64,2,16,0,1],shulkers:[128,3,32,0,0],echest:[512,6,64,0,2],inventory:{},storage:{},echestKnown:true}").getAsJsonObject();
        JsonObject counts=ResourceLedger.resourceCounts(ledger);
        assert counts.getAsJsonObject("inventory").get("obsidian").getAsInt()==192 && counts.getAsJsonObject("inventory").get("pickaxes").getAsInt()==5;
        assert counts.getAsJsonObject("enderChest").get("food").getAsInt()==64 && counts.getAsJsonObject("total").get("obsidian").getAsInt()==704 && counts.get("enderChestKnown").getAsBoolean();
        JsonObject assignment = JsonParser.parseString("{x:0,y:116,z:100,length:512,layout:{dx:0,dz:1,width:5}}").getAsJsonObject();
        var locations = JsonParser.parseString("[{x:1,y:116,z:137},{x:2,y:116,z:137}]");
        assert HighwayCoordinator.checkedSupplyContainers(assignment, locations).size() == 2;
        assert HighwayCoordinator.checkedSupplyContainers(assignment, null).isEmpty();
        for (String invalid : List.of("null", "{}", "[{}]", "[null]", "[{x:1,y:116,z:137.5}]",
            "[{x:'1',y:116,z:137}]", "[{x:2147483648,y:116,z:137}]", "[{x:18,y:116,z:137}]",
            "[{x:0,y:127,z:137}]", "[{x:0,y:116,z:-29}]", "[{x:0,y:116,z:625}]", "[{},{},{}]"))
            assert HighwayCoordinator.checkedSupplyContainers(assignment, JsonParser.parseString(invalid)).isEmpty() : invalid;
        assignment.addProperty("independentSupplies", true);
        assignment.add("activeMembers", new com.google.gson.JsonArray());
        var away = new JsonObject(); away.addProperty(WORKER.toString(), true); assignment.add("awayMembers", away);
        HighwayCoordinator.validateActiveMembers(assignment, java.util.Set.of(WORKER));
        assignment.getAsJsonArray("activeMembers").add(WORKER.toString());
        rejects(() -> HighwayCoordinator.validateActiveMembers(assignment, java.util.Set.of(WORKER)));
    }

    private static void highwayStartup() throws Exception {
        var library = new dev.monocle.client.systems.bots.BotWorkflows(
            java.nio.file.Files.createTempDirectory("monocle-highway-start-check-").resolve("workflows.json"));
        var packaged = library.packageWorkflows("highway-default");
        for (var entry : packaged.getAsJsonObject("highways").entrySet()) {
            String script = packaged.getAsJsonObject("programs").getAsJsonObject(entry.getKey()).get("script").getAsString();
            JsonObject args = new JsonObject(); args.addProperty("length", 128);
            var first = BotLua.next(script, new JsonObject(), args, new JsonObject(), new JsonObject());
            assert TaskWire.text(first.action(), "type").equals("Highway") : "Native jobs must not visit historical supplies before joining their new crew";
            assert TaskWire.text(first.action(), "workflow").equals(entry.getKey());
            var finished = BotLua.next(script, first.state(), args, new JsonObject(), new JsonObject());
            assert TaskWire.text(finished.action(), "type").equals("Done");
        }
        var recovery = library.packageWorkflows("task-recover");
        String script = recovery.getAsJsonObject("programs").getAsJsonObject("task-recover").get("script").getAsString();
        assert TaskWire.text(BotLua.next(script, new JsonObject(), new JsonObject(), new JsonObject(), new JsonObject()).action(), "type").equals("RecoverSupplies")
            : "Explicit recovery workflows must remain available";
    }

    private static void dispatch() {
        JsonObject older = task("older", 0), urgent = task("urgent", 10);
        assert QueuePolicy.dispatch(null, older, WORKER).command().equals("transfer");
        run(older).addProperty("status", "Ready");
        assert QueuePolicy.dispatch(null, older, WORKER).command().equals("resume");
        run(older).addProperty("status", "Running");
        assert QueuePolicy.dispatch(older, urgent, WORKER).command().equals("pause");
        assert QueuePolicy.dispatch(older, older, WORKER).command().equals("external");
        run(older).addProperty("status", "Suspending");
        assert QueuePolicy.dispatch(older, urgent, WORKER).command().isEmpty() : "Cleanup completes before another task starts";
        run(older).addProperty("requestedStatus", "Suspended");
        assert QueuePolicy.dispatch(older, older, WORKER).command().equals("resume") : "Resume can retract suspension of the same native execution";
        assert QueuePolicy.dispatch(older, urgent, WORKER).command().isEmpty() : "A priority diversion still waits for cleanup";
        QueuePolicy.pause(older);
        assert QueuePolicy.dispatch(older, older, WORKER).command().isEmpty();
        QueuePolicy.resume(older);
        for (String terminal : List.of("Cancelled", "Failed", "Complete")) {
            run(older).addProperty("requestedStatus", terminal);
            assert QueuePolicy.dispatch(older, older, WORKER).command().isEmpty() : "Never retract terminal cleanup";
        }
        QueuePolicy.cancel(older);
        assert QueuePolicy.summarizedStatus(older).equals("Cancelled") : "Worker cleanup does not gate the host's cancellation decision";
        assert !dev.monocle.client.systems.bots.BotHistory.taskFinished(older, false) : "Unacknowledged cancellation must survive history deletion/retention";
        assert QueuePolicy.choose(List.of(older), WORKER) == null;
        rejects(() -> QueuePolicy.resume(older));
        assert QueuePolicy.dispatch(older, urgent, WORKER).command().equals("cancel");
        JsonObject report = new JsonObject(); report.addProperty("run", WORKER.toString()); report.addProperty("status", "Inspection required");
        run(urgent).addProperty("id", WORKER.toString());
        assert TaskWire.applyStatus(urgent, run(urgent), report, true);
        assert urgent.get("paused").getAsBoolean();
        JsonObject independent = task("independent", 0); JsonObject independentRun = run(independent);
        independentRun.addProperty("id", WORKER.toString()); independent.addProperty("status", "Running"); independent.addProperty("paused", false);
        assert TaskWire.applyStatus(independent, independentRun, report, true, true);
        assert !independent.get("paused").getAsBoolean() && TaskWire.text(independentRun, "status").equals("Inspection required") : "One restarting worker must not pause an independent crew";
        assert !HighwayCoordinator.resumeMemberRequired(true, true, false) && !HighwayCoordinator.resumeMemberRequired(true, false, true);
        assert HighwayCoordinator.resumeMemberRequired(true, true, true) && HighwayCoordinator.resumeMemberRequired(false, false, false) : "Independent resume ignores offline/away members; shared resume still requires everyone";
        assert HighwayCoordinator.detachAllowed(List.of(WORKER), WORKER) : "The last builder can restock against the saved verified front";
        assert HighwayCoordinator.needsServiceFront(true, false) && HighwayCoordinator.needsServiceFront(false, true);
        assert !HighwayCoordinator.needsServiceFront(true, true) : "Visible active builders remain the freshest return target";
        assert HighwayCoordinator.serviceFrontRow(0, 128) == 1 : "A new job must publish its first work row, not the excluded origin";
        QueuePolicy.resume(urgent); report.addProperty("status", "Running");
        assert TaskWire.applyStatus(urgent, run(urgent), report, true) && !run(urgent).has("resumeInspection");
        report.addProperty("status", "Complete"); TaskWire.applyStatus(urgent, run(urgent), report, true);
        report.addProperty("status", "Running"); assert !TaskWire.applyStatus(urgent, run(urgent), report, true);
    }

    private static void observations() {
        String scope = "example.org\nminecraft:the_nether";
        var site = new PlayerObservation.Position(0, 116, 100);
        JsonObject hello = new JsonObject(); hello.addProperty("id", WORKER.toString()); hello.addProperty("name", "Worker"); hello.addProperty("scope", scope);
        hello.addProperty("x", 0); hello.addProperty("y", 116); hello.addProperty("z", 101);
        var worker = PlayerObservation.fromHello(hello, 100);
        JsonObject diagnostics = new JsonObject(); diagnostics.addProperty("gate", "inventory-preparation");
        hello.add("diagnostics", diagnostics);
        assert PlayerObservation.fromHello(hello, 100).equals(worker) : "Debug data never participates in world/position authority";
        diagnostics.addProperty("oversized", "x".repeat(8192)); rejects(() -> PlayerObservation.fromHello(hello, 100));
        hello.add("diagnostics", new com.google.gson.JsonArray()); rejects(() -> PlayerObservation.fromHello(hello, 100));
        hello.remove("diagnostics");
        hello.addProperty("z", 999);
        assert worker.position().z() == 101 : "Snapshots cannot change when protocol JSON is mutated";
        assert worker.fresh(100) && worker.fresh(100 + PlayerObservation.MAX_AGE - 1);
        assert !worker.fresh(99) && !worker.fresh(100 + PlayerObservation.MAX_AGE);
        assert worker.nearby(scope, site, 1, false, 100);
        assert !worker.nearby("other\nminecraft:the_nether", site, 16, true, 100);
        assert !worker.nearby(scope, new PlayerObservation.Position(0, 117, 100), 16, true, 100);
        assert !worker.nearby(scope, site, 16, true, 100 + PlayerObservation.MAX_AGE);
        var diagonal = new PlayerObservation(OTHER, "Other", scope, new PlayerObservation.Position(16, 116, 116), 100);
        assert diagonal.nearby(scope, site, 16, true, 100) && !diagonal.nearby(scope, site, 16, false, 100);
        var unknown = new PlayerObservation(OTHER, "Other", "", null, 100);
        assert !unknown.inWorld("", 100) && !unknown.nearby(scope, site, 1000, true, 100);
        assert PlayerObservation.target("worker", null, List.of(worker), 100).equals(worker) : "External host needs no local player";
        assert PlayerObservation.target(WORKER.toString(), null, List.of(), 100) == null : "Only supplied crew members may be selected";
        assert PlayerObservation.target("Other", null, List.of(unknown), 100) == null;
        assert PlayerObservation.target("worker", null, List.of(worker), 100 + PlayerObservation.MAX_AGE) == null;
        var duplicate = new PlayerObservation(OTHER, "Worker", scope, site, 100);
        assert PlayerObservation.target("worker", null, List.of(worker, duplicate), 100) == null : "Ambiguous names fail closed";
        assert PlayerObservation.target(WORKER.toString(), null, List.of(worker, duplicate), 100).equals(worker);
        assert PlayerObservation.anchor(List.of(WORKER), OTHER, null, Map.of(WORKER, worker), scope, site, 100).equals(WORKER);
        assert PlayerObservation.anchor(List.of(WORKER), WORKER, null, Map.of(WORKER, worker), scope, site, 100) == null;
        assert PlayerObservation.anchor(List.of(WORKER), OTHER, null, Map.of(WORKER, worker), scope, site, 100 + PlayerObservation.MAX_AGE) == null;
        assert PlayerObservation.anchor(List.of(OTHER), WORKER, worker, Map.of(), scope, site, 100) == null : "Local host must be an active member";
        assert PlayerObservation.anchor(List.of(WORKER), OTHER, worker, Map.of(), scope, site, 100).equals(WORKER);
        assert PlayerObservation.anchor(List.of(WORKER), OTHER, worker, Map.of(), "different", site, 100) == null;
        hello.remove("z"); rejects(() -> PlayerObservation.fromHello(hello, 100));
        hello.addProperty("z", Double.NaN); rejects(() -> PlayerObservation.fromHello(hello, 100));
        hello.addProperty("z", "101"); rejects(() -> PlayerObservation.fromHello(hello, 100));
        hello.remove("x"); hello.remove("y"); hello.remove("z");
        assert PlayerObservation.fromHello(hello, 100).position() == null;
        // Monotonic clocks can have a negative origin or wrap; elapsed subtraction still works.
        var negative = new PlayerObservation(WORKER, "Worker", scope, site, -1000);
        assert negative.fresh(-999);
        var wrapped = new PlayerObservation(WORKER, "Worker", scope, site, Long.MAX_VALUE - 10);
        assert wrapped.fresh(Long.MIN_VALUE + 10);
    }

    private static void verification() {
        Map<Integer, Boolean> host = Map.of(10, true, 11, true, 12, true, 13, true, 14, false, 15, true);
        var confirmed = new RowVerification.Progress(10, true, 11, 31);
        assert RowVerification.mask(true, 11, 15, Map.of(), confirmed) == 0 : "Missing host observations cannot fall back to worker claims";
        assert RowVerification.mask(true, 11, 15, host, new RowVerification.Progress(10, false, null, 0)) == 23;
        assert RowVerification.mask(false, 11, 15, Map.of(), confirmed) == 31;
        assert RowVerification.mask(false, 11, 15, host, null) == 0;
        assert RowVerification.mask(false, 11, 15, host, new RowVerification.Progress(10, true, 10, 31)) == 0;
        assert HighwayCoordinator.canAdvance(11, 15, 11, 1) : "A server-resolved row may advance";
        assert !HighwayCoordinator.canAdvance(11, 15, 11, 0) : "Missing paving in any lane must hold the crew";
        assert !HighwayCoordinator.canAdvance(16, 15, 11, 31) : "The row window remains bounded";
        assert RowVerification.checkpoint(true, 0, 8, 10, host, List.of()) == 10;
        assert RowVerification.checkpoint(true, 0, 10, 10, Map.of(), List.of(confirmed)) == 9;
        assert RowVerification.checkpoint(false, 0, 10, 10, Map.of(), List.of(confirmed)) == 10;
        assert RowVerification.checkpoint(false, 0, 10, 10, host, List.of(new RowVerification.Progress(11, true, 12, 31))) == 9;
        assert RowVerification.checkpoint(false, 0, 10, 8, Map.of(), List.of(new RowVerification.Progress(8, false, 9, 31))) == 7;
        assert RowVerification.checkpoint(true, 5, 5, 5, Map.of(), List.of()) == 5;
        for (int bits = 0; bits < 32; bits++) for (int base = 11; base <= 17; base++) {
            var worker = new RowVerification.Progress(base - 1, true, base, bits);
            int expected = 0;
            for (int offset = 0; offset < 5 && base + offset <= 15; offset++) if ((bits & 1 << offset) != 0) expected |= 1 << offset;
            assert RowVerification.mask(false, base, 15, host, worker) == expected : "Preserve the rolling window without an all-rows barrier";
            for (int row = base - 1; row <= base + 5; row++)
                assert RowVerification.verifiedRow(base, expected, row) == (row >= base && row < base + 5 && row <= 15 && (bits & 1 << (row - base)) != 0);
        }
        JsonObject report = new JsonObject(); report.addProperty("currentRow", 10); report.addProperty("verifiedBase", 11); report.addProperty("verifiedMask", 31);
        assert RowVerification.Progress.fromReport(report).equals(RowVerification.Progress.fromReport(checkpoint(report)));
        report.addProperty("verifiedMask", 32); rejects(() -> RowVerification.Progress.fromReport(report));
        report.addProperty("verifiedMask", 1.5); rejectsArithmetic(() -> RowVerification.Progress.fromReport(report));
        assert !RowVerification.verifiedRow(Integer.MIN_VALUE, 31, Integer.MAX_VALUE);
    }

    private static void rejectsArithmetic(Runnable action) {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (ArithmeticException expected) { }
    }

    private static List<String> replay(boolean reload) {
        List<String> trace = new ArrayList<>();
        JsonObject older = task("older", 3), newer = task("newer", 3), urgent = task("urgent", 10);
        trace.add(chosen(List.of(older, newer)));
        trace.add(chosen(List.of(older, urgent)));
        assert QueuePolicy.preempts(older, urgent, WORKER);
        assert !QueuePolicy.preempts(older, newer, WORKER) : "Tied jobs do not interrupt running work";
        assert QueuePolicy.pause(urgent);
        if (reload) urgent = checkpoint(urgent);
        trace.add(chosen(List.of(older, urgent)));
        QueuePolicy.resume(urgent); trace.add(chosen(List.of(older, urgent)));
        assert QueuePolicy.cancel(urgent);
        if (reload) urgent = checkpoint(urgent);
        trace.add(chosen(List.of(older, urgent)));
        JsonObject cancelled = urgent;
        rejects(() -> QueuePolicy.resume(cancelled));
        trace.add(QueuePolicy.summarizedStatus(urgent));
        run(urgent).addProperty("status", "Inspection required"); run(urgent).addProperty("requestedStatus", "Cancelled");
        trace.add(QueuePolicy.cancellationCommand(run(urgent)));
        QueuePolicy.reconcileMissingRun(urgent, run(urgent)); trace.add(QueuePolicy.summarizedStatus(urgent));
        run(older).addProperty("status", "Running"); run(older).addProperty("resumeSent", true);
        if (reload) older = checkpoint(older);
        QueuePolicy.reconcileMissingRun(older, run(older)); trace.add(QueuePolicy.summarizedStatus(older));
        return trace;
    }
    private static void timing() {
        JsonObject tpa = new JsonObject(); tpa.addProperty("acceptDelay", 10);
        assert !QueuePolicy.teleportWarmupReady(tpa, 100_000);
        tpa.addProperty("acknowledgedAt", 10_000);
        assert !QueuePolicy.teleportWarmupReady(tpa, 9_999) && !QueuePolicy.teleportWarmupReady(tpa, 10_499);
        assert QueuePolicy.teleportWarmupReady(tpa, 10_500);
        for (String flag : List.of("accepted", "recovered")) {
            tpa.addProperty(flag, true); assert !QueuePolicy.teleportWarmupReady(tpa, 10_500); tpa.remove(flag);
        }
        tpa.addProperty("acceptDelay", 1.5); rejects(() -> QueuePolicy.teleportWarmupReady(tpa, 10_500));
        tpa.addProperty("acceptDelay", 201); rejects(() -> QueuePolicy.teleportWarmupReady(tpa, 10_500));
        assert QueuePolicy.validTeleportTtl(1000, 31_000) && !QueuePolicy.validTeleportTtl(1000, 31_001);
        assert !QueuePolicy.validTeleportTtl(1000, 999) && !QueuePolicy.validTeleportTtl(Long.MIN_VALUE, Long.MAX_VALUE);
        assert QueuePolicy.sameTeleportServer("EXAMPLE.org\nminecraft:overworld", "example.org\nminecraft:the_nether");
        assert !QueuePolicy.sameTeleportServer("a\nminecraft:overworld", "b\nminecraft:overworld");
        assert !QueuePolicy.sameTeleportServer("", "") && !QueuePolicy.sameTeleportServer("example.org", "example.org");
        JsonObject execution = new JsonObject(), action = new JsonObject(); action.addProperty("type", "Tpa"); execution.add("action", action);
        execution.addProperty("token", WORKER.toString()); execution.addProperty("status", "Running");
        tpa.addProperty("token", WORKER.toString()); tpa.addProperty("deadline", 20_000);
        assert QueuePolicy.teleportPending(tpa, execution, 19_999) && !QueuePolicy.teleportPending(tpa, execution, 20_000);
        execution.addProperty("token", OTHER.toString()); assert !QueuePolicy.teleportPending(tpa, execution, 19_999);
    }
    private static void lua() {
        String source = "return function(ctx) assert(os==nil and io==nil and luajava==nil and debug==nil); if not ctx.state.started then ctx.state.started=true; return bot.wait(20) end; return bot.done() end";
        JsonObject original = new JsonObject();
        BotLua.Decision first = BotLua.next(source, original, null, null, null);
        assert original.isEmpty() && first.action().get("type").getAsString().equals("Wait");
        BotLua.Decision resumed = BotLua.next(source, checkpoint(first.state()), null, null, null);
        assert resumed.action().get("type").getAsString().equals("Done");
        assert first.equals(BotLua.next(source, new JsonObject(), null, null, null));
        rejects(() -> BotLua.next("return function(ctx) while true do end end", new JsonObject(), null, null, null));
        rejects(() -> BotLua.next("return function(ctx) ctx.state.self=ctx.state; return bot.done() end", new JsonObject(), null, null, null));
    }
    private static JsonObject task(String name, int priority) {
        JsonObject task = new JsonObject(), runs = new JsonObject(), run = new JsonObject();
        run.addProperty("status", "Queued"); runs.add(WORKER.toString(), run);
        task.addProperty("name", name); task.addProperty("priority", priority); task.addProperty("status", "Queued");
        task.add("runs", runs); task.add("overrides", new JsonObject()); return task;
    }
    private static JsonObject run(JsonObject task) { return task.getAsJsonObject("runs").getAsJsonObject(WORKER.toString()); }
    private static String chosen(List<JsonObject> tasks) { return QueuePolicy.choose(tasks, WORKER).get("name").getAsString(); }
    private static JsonObject checkpoint(JsonObject value) { return JsonParser.parseString(value.toString()).getAsJsonObject(); }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (IllegalArgumentException | IllegalStateException expected) { }
    }
}
