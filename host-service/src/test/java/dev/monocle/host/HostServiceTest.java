package dev.monocle.host;

import com.google.gson.*;
import dev.monocle.client.systems.bots.BotLua;
import dev.monocle.client.systems.bots.BotHistory;
import dev.monocle.client.systems.modules.misc.swarm.SwarmConnection;
import dev.monocle.coordinator.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import static dev.monocle.coordinator.TaskWire.*;

/** Actual authenticated sockets + local HTTP + restart journals; game behavior is a simulated worker. */
public final class HostServiceTest {
    private static final String KEY = "test-key-default-12345678901234567890", OTHER_KEY = "test-key-other-12345678901234567890", TOKEN = "test-api-token-123456789012345678901234";
    private static final Map<String, String> CREWS = Map.of("Default", KEY, "Other", OTHER_KEY);
    private static void catalogLockCheck()throws Exception{
        Path directory=Files.createTempDirectory("monocle-catalog-lock-");
        JsonObject plan=JsonParser.parseString("{name:'Cache',homeName:'main',minX:0,maxX:0,minY:100,maxY:100,minZ:0,maxZ:0}").getAsJsonObject();
        StashCatalog.define(directory,"Default","server\nminecraft:the_end",plan);
        StashCatalog.save(directory,"Default","server\nminecraft:the_end",plan,JsonParser.parseString("{x:0,y:100,z:0,block:'minecraft:chest',capacity:27,slotCount:27,occupiedSlots:0,status:'observed',items:{},shulkers:[]}").getAsJsonObject());
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30)){
            for(String operation:List.of("status","stash-sort-preview")){
            JsonObject request=op(operation);request.addProperty("crew","Default");request.addProperty("scope","server\nminecraft:the_end");request.addProperty("name","Cache");request.addProperty("buffers",1);
            var read=new java.util.concurrent.FutureTask<JsonObject>(() ->host.control(request));Thread reader;
            synchronized(StashStore.class){
                reader=Thread.ofPlatform().start(read);
                long deadline=System.nanoTime()+2_000_000_000L;
                while(reader.getState()!=Thread.State.BLOCKED&&System.nanoTime()<deadline)Thread.sleep(1);
                assert reader.getState()==Thread.State.BLOCKED : operation+" is deliberately waiting for the database";
                var control=new java.util.concurrent.FutureTask<JsonObject>(() ->host.control(op("configuration-controls")));
                Thread.ofPlatform().start(control);
                assert control.get(2,java.util.concurrent.TimeUnit.SECONDS)!=null : "A database read cannot lock out worker control";
            }
            reader.join(5000);assert !reader.isAlive();assert read.get()!=null;
            }
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--webui")) { webUiPreview(); return; }
        if (args.length == 1 && args[0].equals("--catalog-lock")) { catalogLockCheck(); System.out.println("Catalog and sorting preview reads do not block worker control."); return; }
        if (args.length == 1 && args[0].equals("--rwp-only")) { monocleRwpWorkerCheck(); return; }
        boolean assertions = false; assert assertions = true; if (!assertions) throw new AssertionError("Enable assertions");
        if (args.length == 1 && args[0].equals("--fault-replay")) { faultReplayCheck(); return; }
        if (args.length == 1 && args[0].equals("--history")) {
            rollingHistoryCheck(); System.out.println("Rolling history checks passed: oldest completion, active protection and rejected submissions."); return;
        }
        assert HostService.ACTIONS.contains("Tpa") : "Standalone workers must be able to use the trusted TPA action";
        assert HostService.ACTIONS.contains("StashDeposit") : "An operator can safely store kits already carried after an interrupted pickup";
        KitStockCheck.run();
        catalogLockCheck();
        KitBotCheck.run();
        stashSortControlCheck();
        kitBotChatCheck();
        publicWorkerGatewayCheck();
        monocleRwpWorkerCheck();
        monocleRwpFollowReconnectCheck();
        publicWorkerActionsCheck();
        publicExtensionActionCheck();
        independentWorkerCheck();
        resourceApiCheck();
        operationsCheck();
        operatorDetachCheck();
        actionStallCheck();
        followPresetCheck();
        externalTpaCheck();
        autoTpyCheck();
        knownHostilesCheck();
        hostWideWarningsCheck();
        stashScanCheck();
        stashLiveCheck();
        stashMultiTransferCheck();
        stashTeamTransferCheck();
        rollingHistoryCheck();
        checkpointReleaseCheck();
        faultReplayCheck();
        for (String absent : List.of("net.minecraft.client.Minecraft", "net.fabricmc.loader.api.FabricLoader", "org.lwjgl.glfw.GLFW")) {
            try { Class.forName(absent); throw new AssertionError("Host requires game class " + absent); } catch (ClassNotFoundException expected) { }
        }
        Path directory = Files.createTempDirectory("monocle-host-check-");
        UUID workerId = UUID.randomUUID(), otherId = UUID.randomUUID();
        Map<UUID, JsonObject> checkpoints = new LinkedHashMap<>();
        UUID unfinished;
        try (HostService host = new HostService(directory, "127.0.0.1", 0, CREWS, 30);
             ControlApi api = new ControlApi(host, 0, TOKEN);
             HttpClient http = HttpClient.newHttpClient();
             Worker worker = new Worker(host.port(), KEY, workerId, checkpoints);
             Worker other = new Worker(host.port(), OTHER_KEY, otherId, new LinkedHashMap<>())) {
            await(() -> connected(host) == 2, worker, other);
            JsonObject supplyGet=op("worker-supply-get");supplyGet.addProperty("crew","Default");supplyGet.addProperty("worker",workerId.toString());
            JsonObject supplyEmpty=host.control(supplyGet),supplySave=supplyGet.deepCopy();supplySave.addProperty("op","worker-supply-save");supplySave.addProperty("expected",text(supplyEmpty,"revision"));
            supplySave.add("station",JsonParser.parseString("{server:'example.org',dimension:'minecraft:overworld',homeName:'bot-home',homeWarmupTicks:320,homeCooldownTicks:340,chest:{x:0,y:64,z:0},bed:{x:2,y:64,z:0},enderChest:{x:4,y:64,z:0}}"));
            assert text(host.control(supplySave),"homeName").equals("bot-home");
            rejects(()->host.control(supplySave));
            JsonObject wrongSupply=supplyGet.deepCopy();wrongSupply.addProperty("worker",otherId.toString());rejects(()->host.control(wrongSupply));
            assert text(host.control(supplyGet),"dimension").equals("minecraft:overworld");
            JsonObject chatRequest=op("chat");chatRequest.addProperty("crew","Default");chatRequest.addProperty("worker",workerId.toString());chatRequest.addProperty("commandId",UUID.randomUUID().toString());chatRequest.addProperty("text","/home stash1");
            host.control(chatRequest);await(()->worker.chatMessages.size()==1&&host.control(op("status")).getAsJsonArray("chat").size()==1,worker,other);
            assert other.chatMessages.isEmpty()&&worker.chatMessages.getFirst().equals("/home stash1") : "Chat remains isolated to the selected authenticated worker";
            chatRequest.addProperty("text","invalid\ncommand");rejects(()->host.control(chatRequest));
            JsonObject observed=host.control(op("status")).getAsJsonArray("workers").get(0).getAsJsonObject();
            assert observed.getAsJsonObject("diagnostics").get("tickAgeMs").getAsInt()==17 && observed.has("observationAgeMs") : "Idle workers expose fresh diagnostic data without a task";
            assert observed.getAsJsonObject("combat").getAsJsonObject("lastDamage").get("attribution").getAsString().equals("unknown");
            assert host.control(op("status")).getAsJsonArray("activity").asList().stream().map(JsonElement::getAsJsonObject)
                .anyMatch(e->text(e,"event").equals("worker-combat")&&e.has("combat")&&e.getAsJsonObject("combat").has("lastDamage")) : "Host events retain structured combat evidence, not only truncated detail";
            observed.getAsJsonObject("combat").addProperty("sequence",999);
            assert host.control(op("status")).getAsJsonArray("workers").get(0).getAsJsonObject().getAsJsonObject("combat").get("sequence").getAsInt()==1 : "Combat API evidence must be detached from retained peer reports";
            observed.getAsJsonObject("diagnostics").addProperty("tickAgeMs",999);
            assert host.control(op("status")).getAsJsonArray("workers").get(0).getAsJsonObject().getAsJsonObject("diagnostics").get("tickAgeMs").getAsInt()==17 : "API snapshots cannot mutate retained reports";
            assert request(http, api, op("status"), "wrong", false).statusCode() == 403;
            assert request(http, api, op("status"), TOKEN, true).statusCode() == 403;
            assert request(http, api, op("status"), TOKEN, false).statusCode() == 200;
            worker.hasPosition = false; worker.announce();
            await(() -> host.control(op("status")).getAsJsonArray("workers").asList().stream().anyMatch(w -> text(w.getAsJsonObject(), "id").equals(workerId.toString()) && !w.getAsJsonObject().has("x")), worker, other);
            assert request(http, api, op("status"), TOKEN, false).statusCode() == 200 : "A worker outside the world must not break host status";
            worker.hasPosition = true; worker.announce();
            boolean locked = false;
            try (HostService duplicate = new HostService(directory, "127.0.0.1", 0, CREWS, 30)) { throw new AssertionError("Second host acquired same journal"); }
            catch (IllegalStateException expected) { locked = true; }
            assert locked;
            JsonObject first = submit(workerId, 0); UUID firstId = UUID.fromString(text(first, "id"));
            assert request(http, api, first, TOKEN, false).statusCode() == 200;
            assert text(host.control(first), "id").equals(firstId.toString()) : "Same submission ID is idempotent";
            JsonObject inspectConfig = op("task-configuration"); inspectConfig.addProperty("id", firstId.toString());
            var configurationResponse = request(http, api, inspectConfig, TOKEN, false);
            assert configurationResponse.statusCode() == 200;
            JsonObject configuration = JsonParser.parseString(configurationResponse.body()).getAsJsonObject();
            assert configuration.has("profiles") && configuration.has("updates") && !configuration.has("package") && !configuration.has("hostOriginal");
            assert request(http, api, inspectConfig, "wrong", false).statusCode() == 403;
            assert request(http, api, op("configuration-controls"), TOKEN, false).statusCode() == 200;
            JsonObject previewRequest = op("preview-configuration"); previewRequest.addProperty("control", "speed");
            previewRequest.addProperty("active", true); previewRequest.addProperty("value", 5.5);
            assert request(http, api, previewRequest, "wrong", false).statusCode() == 403;
            var previewResponse = request(http, api, previewRequest, TOKEN, false);
            assert previewResponse.statusCode() == 200;
            assert JsonParser.parseString(previewResponse.body()).getAsJsonObject().getAsJsonObject("modules").has("speed");
            assert host.control(inspectConfig).equals(configuration) : "Previewing must not enqueue configuration updates";
            JsonObject collision = first.deepCopy(); collision.addProperty("priority", 3); rejects(() -> host.control(collision));
            await(() -> state(host, firstId).equals("Running"), worker, other);
            JsonObject readConfig=op("configuration-read");readConfig.addProperty("id",firstId.toString());readConfig.addProperty("worker",workerId.toString());readConfig.addProperty("profile","Current");readConfig.addProperty("module","speed");
            assert request(http,api,readConfig,"wrong",false).statusCode()==403;
            assert request(http,api,readConfig,TOKEN,false).statusCode()==200;
            JsonObject readResult=readConfig.deepCopy();readResult.addProperty("op","configuration-read-result");
            await(()->text(host.control(readResult),"status").equals("Snapshot"),worker,other);
            assert text(host.control(readResult).getAsJsonObject("report"),"note").equals("Test-only worker readback");
            JsonObject wrongWorker=readConfig.deepCopy();wrongWorker.addProperty("worker",otherId.toString());rejects(()->host.control(wrongWorker));
            // A running Lua frame legitimately has no native action between steps.
            JsonObject betweenSteps = worker.checkpoints.get(UUID.fromString(worker.current));
            JsonObject savedAction = betweenSteps.getAsJsonObject("action");
            String savedToken = text(betweenSteps, "token");
            betweenSteps.remove("action"); betweenSteps.remove("token"); worker.sendStatus(betweenSteps);
            await(() -> !taskView(host, firstId).getAsJsonObject("runs").getAsJsonObject(workerId.toString()).has("action"), worker, other);
            for (int i = 0; i < 20; i++) { worker.pump(); other.pump(); Thread.sleep(10); }
            assert worker.c.connected() && other.c.connected() && !text(host.control(op("status")), "status").startsWith("Coordinator stopped")
                : "Between-step reports must not crash the host or disconnect crews";
            betweenSteps.add("action", savedAction); betweenSteps.addProperty("token", savedToken); worker.sendStatus(betweenSteps);
            assert other.installs == 0 : "No workflow broadcasts across crews";
            JsonObject crossCrew = submit(otherId, 0); rejects(() -> host.control(crossCrew));
            JsonObject urgent = submit(workerId, 10); UUID urgentId = UUID.fromString(text(urgent, "id")); host.control(urgent);
            await(() -> state(host, firstId).equals("Suspended") && state(host, urgentId).equals("Running"), worker, other);
            JsonObject cancel = op("cancel"); cancel.addProperty("id", urgentId.toString()); host.control(cancel); host.control(cancel);
            await(() -> state(host, urgentId).equals("Cancelled") && state(host, firstId).equals("Running"), worker, other);
            assert worker.installs == 2 : "Preempted workflow resumes the same package, not a fresh execution";
            JsonObject detach=op("detach");detach.addProperty("id",firstId.toString());detach.addProperty("worker",workerId.toString());detach.addProperty("detached",true);
            assert request(http,api,detach,"wrong",false).statusCode()==403;
            JsonObject invalidDetach=detach.deepCopy();invalidDetach.addProperty("detached","true");rejects(()->host.control(invalidDetach));
            invalidDetach.addProperty("detached",true);invalidDetach.addProperty("worker",otherId.toString());rejects(()->host.control(invalidDetach));
            host.control(detach);await(()->state(host,firstId).equals("Suspended"),worker,other);
            JsonObject globalResume=op("resume");globalResume.addProperty("id",firstId.toString());host.control(globalResume);
            for(int i=0;i<20;i++){worker.pump();other.pump();Thread.sleep(10);}
            assert flag(taskView(host,firstId).getAsJsonObject("runs").getAsJsonObject(workerId.toString()),"operatorDetached");
            assert !state(host,firstId).equals("Running") : "Whole-job Resume must not silently rejoin detached workers";
            detach.addProperty("detached",false);host.control(detach);await(()->state(host,firstId).equals("Running"),worker,other);
            assert worker.installs==2 : "Rejoin resumes the checkpoint rather than replaying the package";
            JsonObject pause = op("pause"); pause.addProperty("id", firstId.toString()); host.control(pause);
            await(() -> state(host, firstId).equals("Paused") && worker.current.isEmpty(), worker, other);
            pause.addProperty("op", "resume"); host.control(pause); await(() -> state(host, firstId).equals("Running"), worker, other);
            pause.addProperty("op", "cancel"); host.control(pause); await(() -> state(host, firstId).equals("Cancelled") && !flag(taskView(host,firstId),"cleanupPending"), worker, other);
            assert host.control(op("status")).getAsJsonArray("tasks").isEmpty();
            assert host.control(op("status")).getAsJsonArray("history").size() == 2;
            pause.addProperty("op", "delete"); host.control(pause); assert host.control(op("status")).getAsJsonArray("history").size() == 1;
            JsonObject next = submit(workerId, 0); unfinished = UUID.fromString(text(next, "id")); host.control(next);
            await(() -> state(host, unfinished).equals("Running"), worker, other);
            // Drop the connection mid-run. Worker native cleanup is represented by a suspended checkpoint.
            worker.checkpoints.values().stream().filter(r -> text(r, "status").equals("Running")).forEach(r -> r.addProperty("status", "Suspended"));
        }
        try (HostService host = new HostService(directory, "127.0.0.1", 0, CREWS, 30);
             Worker worker = new Worker(host.port(), KEY, workerId, checkpoints)) {
            await(() -> connected(host)>0, worker);
            assert state(host, unfinished).equals("Inspection required") : "Host restart does not resume gameplay";
            int before = worker.resumes;
            for (int i = 0; i < 20; i++) { worker.pump(); Thread.sleep(10); }
            assert worker.resumes == before;
            JsonObject resume = op("resume"); resume.addProperty("id", unfinished.toString()); host.control(resume);
            await(() -> state(host, unfinished).equals("Running"), worker);
            assert worker.installs == 0 : "Reconnect continues durable worker execution instead of re-installing";
            worker.close();
            JsonObject cancel = op("cancel"); cancel.addProperty("id", unfinished.toString()); host.control(cancel);
            assert state(host, unfinished).equals("Cancelled") : "Host cancellation is immediate even with an offline worker";
            JsonObject delete = cancel.deepCopy(); delete.addProperty("op", "delete"); rejects(() -> host.control(delete));
            JsonObject resumeAgain = cancel.deepCopy(); resumeAgain.addProperty("op", "resume"); rejects(() -> host.control(resumeAgain));
            try (Worker returned = new Worker(host.port(), KEY, workerId, checkpoints)) {
                await(() -> checkpoints.values().stream().allMatch(r -> QueuePolicy.terminal(text(r,"status"))), returned);
                assert returned.installs == 0 && returned.resumes == 0 : "Reconnect reconciles cancellation, never resumes ghost work";
            }
        }
        // Missing executed worker checkpoint never replays side effects.
        JsonObject root = TaskFiles.read(directory.resolve("host-tasks.json"));
        JsonObject task = root.getAsJsonObject("tasks").getAsJsonObject(unfinished.toString());
        task.remove("cancelled"); task.addProperty("status", "Running");
        task.getAsJsonObject("runs").asMap().values().forEach(value -> value.getAsJsonObject().addProperty("status", "Running"));
        TaskFiles.write(directory.resolve("host-tasks.json"), root);
        try (HostService host = new HostService(directory, "127.0.0.1", 0, CREWS, 30);
             Worker lost = new Worker(host.port(), KEY, workerId, new LinkedHashMap<>())) {
            await(() -> connected(host) == 1, lost);
            assert state(host, unfinished).equals("Inspection required");
            assert lost.installs == 0;
            JsonObject cancel = op("cancel"); cancel.addProperty("id", unfinished.toString()); host.control(cancel);
            await(() -> state(host, unfinished).equals("Cancelled"), lost);
        }
        System.out.println("Standalone host checks passed without Minecraft: authenticated HTTP/sockets, crew isolation, real package frames, priorities, pause/resume/cancel, idempotent submissions, ownership lock, restart and missing-checkpoint recovery. Journals: " + directory);
        highway();
        initialSupplies();
        independentSupplyFailures();
        sharedSupplies();
        longHighway();
        workerRecovery();
        highwayReconnect();
        highwayRestart();
        cancellationRestart();
        webTransport();
        webTls();
        WebUiTest.run();
    }
    private static void operatorDetachCheck() throws Exception {
        Path directory=Files.createTempDirectory("monocle-operator-detach-");
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            Worker first=new Worker(host.port(),KEY,UUID.randomUUID(),new LinkedHashMap<>());
            Worker second=new Worker(host.port(),KEY,UUID.randomUUID(),new LinkedHashMap<>())) {
            await(()->connected(host)==2,first,second);
            JsonObject request=highwayRequest(first.id,second.id);UUID task=UUID.fromString(text(request,"id"));host.control(request);
            await(()->first.begun&&second.begun,first,second);
            JsonObject detach=op("detach");detach.addProperty("id",task.toString());detach.addProperty("worker",first.id.toString());detach.addProperty("detached",true);
            host.control(detach);
            await(()->first.nativeJob==null && first.current.isEmpty(),first,second);
            assert second.nativeJob!=null && second.nativeJob.getAsJsonArray("members").size()==1;
            assert flag(taskView(host,task).getAsJsonObject("runs").getAsJsonObject(first.id.toString()),"operatorDetached");
            second.advance=true;await(()->second.row>=2,first,second);second.advance=false;
            JsonObject resume=op("resume");resume.addProperty("id",task.toString());host.control(resume);
            for(int i=0;i<20;i++){first.pump();second.pump();Thread.sleep(10);}
            assert first.nativeJob==null && first.current.isEmpty() : "Detached native worker stays released across whole-job Resume";
            detach.addProperty("detached",false);host.control(detach);
            await(()->first.nativeJob!=null&&first.begun&&second.nativeJob.getAsJsonArray("members").size()==2,first,second);
            assert first.installs==1 : "Rejoin must resume the original checkpoint";
            first.delayWithdrawal=true;detach.addProperty("detached",true);host.control(detach);
            await(()->first.withdrawalRequests>1,first,second);
            detach.addProperty("detached",false);host.control(detach);first.delayWithdrawal=false;
            await(()->first.nativeJob!=null&&first.begun&&second.nativeJob.getAsJsonArray("members").size()==2,first,second);
            assert first.installs==1 : "Rejoin during withdrawal must not replay or strand the worker";
            resume.addProperty("op","cancel");host.control(resume);await(()->first.nativeJob==null&&second.nativeJob==null,first,second);
        }
        System.out.println("Operator detach checks passed: safe native withdrawal, other worker progress, explicit rejoin and cancellation.");
    }
    private static void actionStallCheck() throws Exception {
        Path directory=Files.createTempDirectory("monocle-action-stall-");UUID id=UUID.randomUUID(),otherId=UUID.randomUUID(),taskId;
        Map<UUID,JsonObject> checkpoints=new LinkedHashMap<>(),otherCheckpoints=new LinkedHashMap<>();
        JsonObject stalled;
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            Worker worker=new Worker(host.port(),KEY,id,checkpoints);Worker other=new Worker(host.port(),KEY,otherId,otherCheckpoints)) {
            await(()->connected(host)==2,worker,other);JsonObject request=submit(id,0);request.getAsJsonArray("workers").add(otherId.toString());taskId=UUID.fromString(text(request,"id"));host.control(request);
            await(()->!worker.current.isEmpty()&&!other.current.isEmpty(),worker,other);
            JsonObject run=checkpoints.get(UUID.fromString(worker.current));run.addProperty("status","Suspended");
            JsonObject progress=JsonParser.parseString("{phase:'Travel',expectedNextEvent:'Safe destination arrival',progressAgeMs:360000,recoveryAttempts:2,state:'Paused'}").getAsJsonObject();progress.addProperty("incidentId",UUID.randomUUID().toString());run.add("actionProgress",progress);stalled=run.deepCopy();
            worker.current="";worker.sendStatus(run);worker.heartbeat();
            await(()->flag(taskView(host,taskId).getAsJsonObject("runs").getAsJsonObject(id.toString()),"stallPaused"),worker,other);
            int resumes=worker.resumes;for(int i=0;i<120;i++){worker.pump();other.pump();Thread.sleep(10);}
            assert worker.resumes==resumes && text(taskView(host,taskId).getAsJsonObject("runs").getAsJsonObject(otherId.toString()),"status").equals("Running") : "One stall cannot auto-resume or pause unrelated work";
        }
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            Worker worker=new Worker(host.port(),KEY,id,checkpoints);Worker other=new Worker(host.port(),KEY,otherId,otherCheckpoints)) {
            await(()->connected(host)==2,worker,other);for(int i=0;i<120;i++){worker.pump();other.pump();Thread.sleep(10);}
            assert flag(taskView(host,taskId).getAsJsonObject("runs").getAsJsonObject(id.toString()),"stallPaused") && worker.resumes==0 : "The stall hold survives host restart/reconnect";
            host.control(opWithId("resume",taskId));await(()->!worker.current.isEmpty(),worker,other);
            worker.sendStatus(stalled);worker.heartbeat();for(int i=0;i<30;i++){worker.pump();other.pump();Thread.sleep(10);}
            assert !flag(taskView(host,taskId).getAsJsonObject("runs").getAsJsonObject(id.toString()),"stallPaused") : "An old paused report cannot undo explicit Resume";
            JsonObject run=checkpoints.values().iterator().next();run.addProperty("status","Suspended");run.getAsJsonObject("actionProgress").addProperty("state","Paused");run.getAsJsonObject("actionProgress").addProperty("incidentId",UUID.randomUUID().toString());worker.current="";worker.sendStatus(run);worker.heartbeat();
            await(()->flag(taskView(host,taskId).getAsJsonObject("runs").getAsJsonObject(id.toString()),"stallPaused"),worker,other);
            host.control(opWithId("cancel",taskId));await(()->text(taskView(host,taskId).getAsJsonObject("runs").getAsJsonObject(id.toString()),"status").equals("Cancelled"),worker,other);
        }
        System.out.println("Action stall socket checks passed: per-worker hold, explicit Resume, stale reports, restart/reconnect and cancellation.");
    }

    private static void kitBotChatCheck() throws Exception {
        Path directory=Files.createTempDirectory("monocle-kitbot-chat-");
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);Worker worker=new Worker(host.port(),KEY,UUID.randomUUID(),new LinkedHashMap<>())){
            await(()->connected(host)==1,worker);
            JsonObject bounds=JsonParser.parseString("{name:'main',homeName:'main',minX:0,maxX:2,minY:100,maxY:100,minZ:0,maxZ:0}").getAsJsonObject();StashCatalog.define(directory,"Default",worker.scope,StashCatalog.plan(bounds));
            JsonObject config=JsonParser.parseString("{enabled:true,crew:'Default',source:'main',minimumStock:250,cooldownSeconds:86400,whitelist:[{name:'iotacism',uuid:'ebe1d0a2-a08f-4223-8a92-8d50b6431dc4'}]}").getAsJsonObject();config.addProperty("worker",worker.id.toString());config.addProperty("scope",worker.scope);
            JsonObject save=op("kitbot-save");save.add("config",config);save.addProperty("expected",text(host.control(op("kitbot-get")),"revision"));host.control(save);
            worker.inLobby=true;worker.announce();await(()->text(host.control(op("status")).getAsJsonArray("workers").get(0).getAsJsonObject().getAsJsonObject("worldReadiness"),"state").equals("Lobby"),worker);
            JsonObject message=op("unused");message.remove("op");message.addProperty("type","worker-chat");message.addProperty("direction","received");message.addProperty("text","[unknown player head] iotacism whispers: kit pvp 2");worker.c.send(message.toString());await(()->host.control(op("status")).getAsJsonArray("chat").size()==1,worker);
            assert host.control(op("kitbot-get")).getAsJsonObject("requests").isEmpty();
            worker.inLobby=false;worker.announce();await(()->text(host.control(op("status")).getAsJsonArray("workers").get(0).getAsJsonObject().getAsJsonObject("worldReadiness"),"state").equals("Ready"),worker);
            message.addProperty("text","iotacism » kit pvp 2");worker.c.send(message.toString());message.addProperty("text","other whispers: kit pvp 2");worker.c.send(message.toString());message.addProperty("direction","sent");message.addProperty("text","iotacism whispers: kit pvp 2");worker.c.send(message.toString());await(()->host.control(op("status")).getAsJsonArray("chat").size()>=4,worker);assert host.control(op("kitbot-get")).getAsJsonObject("requests").isEmpty();
            message.addProperty("direction","received");worker.c.send(message.toString());await(()->host.control(op("kitbot-get")).getAsJsonObject("requests").size()==1,worker);await(()->worker.chatMessages.size()==1,worker);
            assert worker.chatMessages.getFirst().startsWith("/msg iotacism ");assert host.control(op("status")).getAsJsonArray("tasks").isEmpty();
            worker.c.send(message.toString());await(()->host.control(op("status")).getAsJsonArray("chat").size()>=7,worker);assert host.control(op("kitbot-get")).getAsJsonObject("requests").size()==1;
            JsonObject other=JsonParser.parseString("{name:'other',uuid:'00000000-0000-0000-0000-000000000002',cooldownSeconds:0,maxKits:1}").getAsJsonObject();config.getAsJsonArray("whitelist").add(other);save.addProperty("expected",text(host.control(op("kitbot-get")),"revision"));host.control(save);
            message.addProperty("text","other whispers: kit pvp 2");worker.c.send(message.toString());await(()->worker.chatMessages.size()==2,worker);
            assert worker.chatTimes.getLast()-worker.chatTimes.getFirst()>=4_900_000_000L:"DM queue enforces five seconds per courier";
            assert worker.chatMessages.getLast().contains("Maximum kits per request: 1")&&host.control(op("status")).getAsJsonArray("tasks").isEmpty();
            assert host.control(op("kitbot-get")).getAsJsonObject("requests").entrySet().stream().anyMatch(e->text(e.getValue().getAsJsonObject(),"status").equals("LimitExceeded"));
            int receipts=host.control(op("kitbot-get")).getAsJsonObject("requests").size();message.addProperty("text","iotacism whispers: kits");worker.c.send(message.toString());await(()->worker.chatMessages.size()==3,worker);
            assert worker.chatMessages.getLast().equals("/msg iotacism Kits 1: No kits in stock.")&&host.control(op("kitbot-get")).getAsJsonObject("requests").size()==receipts;
        }
        System.out.println("Kitbot socket checks passed: authenticated courier, ready-world gate, lobby/public/sent exclusion and durable request deduplication.");
    }

    private static void webUiPreview() throws Exception {
        Path directory=Files.createTempDirectory("monocle-ui-preview-");
        try (HostService host = new HostService(directory, "127.0.0.1", 0, CREWS, 30);
             ControlApi api = new ControlApi(host, 0, TOKEN);
             Worker first = new Worker(host.port(), KEY, UUID.randomUUID(), new LinkedHashMap<>());
             Worker second = new Worker(host.port(), KEY, UUID.randomUUID(), new LinkedHashMap<>());
             Worker scout = new Worker(host.port(), OTHER_KEY, UUID.randomUUID(), new LinkedHashMap<>())) {
            JsonObject config=new JsonObject();config.addProperty("bind","127.0.0.1");config.addProperty("workerPort",host.port());config.addProperty("apiPort",api.port());config.addProperty("apiToken",TOKEN);config.addProperty("historyDays",30);config.add("crews",new Gson().toJsonTree(CREWS));TaskFiles.write(directory.resolve("host-config.json"),config);
            System.out.println("Preview data directory: "+directory);
            first.name = "Atlas"; second.name = "AtlasBot"; scout.name = "Scout";
            first.announce(); second.announce(); scout.announce();
            await(() -> connected(host) == 3, first, second, scout);
            JsonObject request = highwayRequest(first.id, second.id); request.addProperty("name", "Long road · native highway");
            request.getAsJsonObject("args").addProperty("length", 100000); host.control(request);
            System.out.println("Preview WebUI: http://127.0.0.1:" + api.port() + "/ui/");
            System.out.println("Test-only operator token: " + TOKEN + ". Simulated workers only; no live host data.");
            while (!Thread.currentThread().isInterrupted()) { first.pump(); second.pump(); scout.pump(); Thread.sleep(10); }
        }
    }
    private static void webTransport() throws Exception {
        Path directory = Files.createTempDirectory("monocle-web-host-check-");
        UUID id = UUID.randomUUID(), tcpId = UUID.randomUUID(), otherId = UUID.randomUUID();
        Map<UUID, JsonObject> checkpoints = new LinkedHashMap<>();
        try (HostService host = new HostService(directory, "127.0.0.1", 0, CREWS, 30, 0);
             ControlApi api = new ControlApi(host, 0, TOKEN);
             TlsProxy apiTls = new TlsProxy(api.port());
             HttpClient http = HttpClient.newBuilder().sslContext(TlsProxy.context()).build();
             Worker worker = new Worker("ws://127.0.0.1:" + host.webPort() + "/v1/workers", KEY, id, checkpoints);
             Worker tcp = new Worker(host.port(), KEY, tcpId, new LinkedHashMap<>());
             Worker other = new Worker("ws://127.0.0.1:" + host.webPort() + "/v1/workers", OTHER_KEY, otherId, new LinkedHashMap<>())) {
            String endpoint = "ws://127.0.0.1:" + host.webPort() + "/v1/workers";
            await(() -> connected(host) == 3, worker, tcp, other);
            URI status = URI.create("https://localhost:" + apiTls.port() + "/v1/status");
            assert http.send(HttpRequest.newBuilder(status).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 403;
            var response = http.send(HttpRequest.newBuilder(status).header("Authorization", "Bearer " + TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString());
            assert response.statusCode() == 200 && JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("workers").size() == 3;
            rejects(() -> CrewTransport.worker("ws://example.com/v1/workers", 6969));
            rejects(() -> CrewTransport.worker("wss://secret@example.com/v1/workers", 6969));
            rejects(() -> CrewTransport.worker("wss://example.com/v1/workers?token=secret", 6969));
            rejects(() -> CrewTransport.worker("https://example.com/v1/workers", 6969));
            try (var rejected = new AutoConnection(endpoint, "wrong-crew-key-12345678901234567890")) {
                await(() -> rejected.c.closed(), worker, tcp, other);
                assert !rejected.c.connected();
            }
            JsonObject request = submit(id, 0); UUID task = UUID.fromString(text(request, "id"));
            HttpRequest control = HttpRequest.newBuilder(URI.create("https://localhost:" + apiTls.port() + "/v1/control"))
                .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build();
            assert http.send(control, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
            await(() -> state(host, task).equals("Running"), worker, tcp, other);
            assert tcp.installs == 0 && other.installs == 0 : "Web transport preserves worker and crew routing";
            JsonObject pause = op("pause"); pause.addProperty("id", task.toString()); host.control(pause);
            await(() -> state(host, task).equals("Paused") && worker.current.isEmpty(), worker, tcp, other);
            pause.addProperty("op", "resume"); host.control(pause);
            await(() -> state(host, task).equals("Running"), worker, tcp, other);
            worker.close(); pause.addProperty("op", "cancel"); host.control(pause);
            assert state(host, task).equals("Cancelled");
            try (Worker reconnected = new Worker(endpoint, KEY, id, checkpoints)) {
                await(() -> checkpoints.values().stream().allMatch(r -> text(r, "status").equals("Cancelled")), reconnected, tcp, other);
                assert reconnected.installs == 0 && reconnected.resumes == 0 : "Web reconnect reconciles cancellation without replay";
                JsonObject highway = highwayRequest(id, tcpId); UUID highwayId = UUID.fromString(text(highway, "id")); host.control(highway);
                await(() -> reconnected.begun && tcp.begun, reconnected, tcp, other);
                reconnected.advance = tcp.advance = true; reconnected.announce(); tcp.announce();
                await(() -> state(host, highwayId).equals("Complete"), reconnected, tcp, other);
                assert reconnected.nativeJob == null && tcp.nativeJob == null && other.installs == 0
                    : "Mixed TCP/WebSocket crews use the same verified highway completion";
            }
        }
        System.out.println("Web host checks passed: authenticated API, crew routing, mixed TCP/WebSocket highway, pause/resume and offline cancellation reconciliation.");
    }
    private static void webTls() throws Exception {
        javax.net.ssl.SSLContext previous = javax.net.ssl.SSLContext.getDefault();
        try (CrewListener listener = new CrewListener("127.0.0.1", 0, selector -> SwarmConnection.credentialSelector(KEY).equals(selector) ? KEY : null, 0);
             TlsProxy tls = new TlsProxy(listener.webPort())) {
            String url = "wss://localhost:" + tls.port() + "/v1/workers";
            try (AutoConnection untrusted = new AutoConnection(url, KEY)) {
                await(untrusted.c::closed); assert !untrusted.c.connected() : "Untrusted TLS certificates must be rejected";
            }
            javax.net.ssl.SSLContext.setDefault(TlsProxy.context());
            try (AutoConnection wrongName = new AutoConnection("wss://127.0.0.1:" + tls.port() + "/v1/workers", KEY)) {
                await(wrongName.c::closed); assert !wrongName.c.connected() : "Trusted certificates still require hostname validation";
            }
            try (AutoConnection worker = new AutoConnection(url, KEY)) {
                await(() -> worker.c.connected() && Arrays.stream(listener.connections()).anyMatch(c -> c != null && c.connected()));
                SwarmConnection host = Arrays.stream(listener.connections()).filter(c -> c != null && c.connected()).findFirst().orElseThrow();
                String frame = "🌸".repeat(CrewFrames.MAX_BYTES / 4);
                assert worker.c.send(frame); await(() -> frame.equals(host.poll()));
                assert host.send(frame); await(() -> frame.equals(worker.c.poll()));
                assert host.send("cancelled-execution-ack"); await(() -> "cancelled-execution-ack".equals(worker.c.poll()));
                try (HttpClient http = HttpClient.newHttpClient()) {
                    for (String suffix : List.of("/wrong-path", "/v1/workers?token=secret")) {
                        try { http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + listener.webPort() + suffix), new java.net.http.WebSocket.Listener() {}).get(3, java.util.concurrent.TimeUnit.SECONDS); throw new AssertionError("Wrong endpoint accepted"); }
                        catch (java.util.concurrent.ExecutionException expected) { }
                    }
                    try { http.newWebSocketBuilder().header("Origin", "https://browser.invalid").buildAsync(URI.create("ws://127.0.0.1:" + listener.webPort() + "/v1/workers"), new java.net.http.WebSocket.Listener() {}).get(3, java.util.concurrent.TimeUnit.SECONDS); throw new AssertionError("Browser-origin worker accepted"); }
                    catch (java.util.concurrent.ExecutionException expected) { }
                    var closed = new java.util.concurrent.CompletableFuture<Integer>();
                    var oversized = http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + listener.webPort() + "/v1/workers"), new java.net.http.WebSocket.Listener() {
                        public java.util.concurrent.CompletionStage<?> onClose(java.net.http.WebSocket ws, int code, String reason) { closed.complete(code); return null; }
                    }).get(3, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        oversized.sendText("x".repeat(CrewFrames.MAX_BYTES + 1), true).join();
                        assert closed.get(5, java.util.concurrent.TimeUnit.SECONDS) == 1009 : "Remote oversized frames must close before protocol decoding";
                    } finally { oversized.abort(); }
                }
                assert host.connected() && worker.c.connected() : "Rejected connections cannot disrupt a healthy authenticated worker";
            }
        } finally { javax.net.ssl.SSLContext.setDefault(previous); }
        System.out.println("HTTPS/WSS checks passed: real TLS proxy, certificate trust/hostname validation, authenticated ordered Unicode frames, endpoint/origin guards and bounded frame rejection.");
    }
    private static final class AutoConnection implements AutoCloseable {
        final SwarmConnection c;
        AutoConnection(String endpoint, String key) {
            c = new SwarmConnection(CrewTransport.worker(endpoint, 6969), key, false); c.start();
        }
        public void close() { c.disconnect(); }
    }
    private static void cancellationRestart() throws Exception {
        Path directory=Files.createTempDirectory("monocle-cancel-restart-check-");
        UUID workerId=UUID.randomUUID(), task; Map<UUID,JsonObject> checkpoints=new LinkedHashMap<>();
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,0);
            Worker worker=new Worker(host.port(),KEY,workerId,checkpoints)) {
            await(()->connected(host)>0,worker);
            JsonObject request=submit(workerId,0); task=UUID.fromString(text(request,"id"));host.control(request);
            await(()->state(host,task).equals("Running"),worker);
            worker.close(); JsonObject cancel=op("cancel");cancel.addProperty("id",task.toString());host.control(cancel);
            assert state(host,task).equals("Cancelled") && flag(taskView(host,task),"cleanupPending");
        }
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,0)) {
            assert state(host,task).equals("Cancelled") : "Host restart cannot revoke a persisted cancellation";
            JsonObject delete=op("delete");delete.addProperty("id",task.toString());rejects(()->host.control(delete));
            try(Worker returned=new Worker(host.port(),KEY,workerId,checkpoints)) {
                await(()->checkpoints.values().stream().allMatch(r->text(r,"status").equals("Cancelled")),returned);
                assert returned.resumes==0 && returned.installs==0 : "Stale Running checkpoints receive cancellation, not restart permission";
            }
        }
    }
    private static void longHighway() throws Exception {
        Path directory=Files.createTempDirectory("monocle-long-highway-check-"); UUID id=UUID.randomUUID();
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            Worker worker=new Worker(host.port(),KEY,id,new LinkedHashMap<>())) {
            await(()->connected(host)==1,worker);
            JsonObject request=highwayRequest(id,UUID.randomUUID());request.getAsJsonArray("workers").remove(1);
            request.getAsJsonObject("args").addProperty("length",100_000);host.control(request);
            UUID task=UUID.fromString(text(request,"id"));await(()->worker.begun,worker);
            assert worker.nativeJob.get("length").getAsInt()==100_000 : "Full length reaches the worker protocol";
            Path telemetry=Path.of(text(host.control(op("status")).getAsJsonObject("highways").getAsJsonObject("Default"),"telemetryPath"));
            await(()->{
                try { return Files.exists(telemetry) && Files.readString(telemetry).contains("\"event\":\"wait-observed\""); }
                catch(java.io.IOException e) { return false; }
            },worker);
            String observedLog=Files.readString(telemetry);
            assert observedLog.contains("\"lastPermitSent\"") && observedLog.contains("\"reportAgeMs\"") && observedLog.contains("\"diagnostics\"");
            assert !observedLog.contains(KEY) && !observedLog.contains("\"profiles\"") && !observedLog.contains("\"script\"") : "Log decision inputs, never captured profiles, scripts or crew credentials";
            worker.row=90_000;worker.announce();
            await(()->taskView(host,task).has("highwayProgress") && taskView(host,task).get("highwayProgress").getAsInt()==90_000,worker);
            worker.row=99_999;worker.advance=true;worker.announce();
            await(()->state(host,task).equals("Complete"),worker);
            assert taskView(host,task).get("highwayProgress").getAsInt()==100_000 && worker.nativeJob==null;
        }
        System.out.println("100k highway socket checks passed: dispatch, late checkpoint and completion.");
    }
    private static void highwayRestart() throws Exception {
        Path directory=Files.createTempDirectory("monocle-native-restart-check-");
        UUID id=UUID.randomUUID(),task;Map<UUID,JsonObject> checkpoints=new LinkedHashMap<>();JsonObject saved;
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);Worker worker=new Worker(host.port(),KEY,id,checkpoints)) {
            await(()->connected(host)==1,worker);
            JsonObject request=highwayRequest(id,UUID.randomUUID());request.getAsJsonArray("workers").remove(1);task=UUID.fromString(text(request,"id"));host.control(request);
            await(()->worker.begun,worker);worker.detach();await(()->worker.detached,worker);worker.reserve();await(()->!worker.supplyLock.isEmpty(),worker);
            await(() -> host.control(op("status")).getAsJsonObject("highways").getAsJsonObject("Default").getAsJsonObject("supplyContainers").has(id.toString()), worker);
            await(() -> {
                JsonObject journal = TaskFiles.read(directory.resolve("highway-"+UUID.nameUUIDFromBytes("Default".getBytes(StandardCharsets.UTF_8))+".json"));
                return journal.has("supplyContainers") && journal.getAsJsonObject("supplyContainers").has(id.toString());
            }, worker);
            saved=worker.nativeJob.deepCopy();
        }
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);Worker worker=new Worker(host.port(),KEY,id,checkpoints)) {
            worker.nativeJob=saved;worker.detached=true;worker.announce();
            await(()->connected(host)==1,worker);
            assert state(host,task).equals("Inspection required");
            JsonObject command=op("resume");command.addProperty("id",task.toString());
            boolean refused=false;try{host.control(command);}catch(IllegalStateException expected){refused=true;}assert refused : "Never replay an interrupted container transaction";
            command.addProperty("op","cancel");host.control(command);await(()->state(host,task).equals("Cancelled") && worker.nativeJob==null,worker);
            JsonObject recovery=TaskFiles.read(directory.resolve("ended-"+text(saved,"job")+"-supplies.json"));
            assert recovery.getAsJsonObject("supplyContainers").has(id.toString()) : "Ending after a restart preserves observed containers without a crew-wide lock";
        }
        System.out.println("Native restart/uncertain-supply checks passed: explicit inspection, no replay, durable cancellation and archived supply ownership.");
    }
    private static void workerRecovery() throws Exception {
        Path directory=Files.createTempDirectory("monocle-worker-recovery-check-"); UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            Worker first=new Worker(host.port(),KEY,a,new LinkedHashMap<>()); Worker second=new Worker(host.port(),KEY,b,new LinkedHashMap<>())) {
            await(()->connected(host)==2,first,second);
            host.control(highwayRequest(a,b)); await(()->first.begun && second.begun,first,second);
            String execution=text(first.nativeJob,"job");
            first.recoveryOnly=true; first.recoveryReady=false; first.announce();
            await(()->second.nativeJob.getAsJsonArray("activeMembers").size()==1,first,second);
            assert first.restores==0 && !second.nativePaused : "Recovery does not restore early or freeze the other builder";
            first.recoveryReady=true; first.announce();
            await(()->first.restores>0,first,second);
            assert first.restores==1 && text(first.nativeJob,"job").equals(execution) : "Restore the same execution exactly once";
        }
    }

    private static void highwayReconnect() throws Exception {
        Path directory=Files.createTempDirectory("monocle-world-reconnect-check-");
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            Worker first=new Worker(host.port(),KEY,a,new LinkedHashMap<>());
            Worker second=new Worker(host.port(),KEY,b,new LinkedHashMap<>())) {
            await(()->connected(host)==2,first,second);
            JsonObject request=highwayRequest(a,b);host.control(request);
            await(()->first.begun && second.begun,first,second);
            JsonObject saved=second.nativeJob.deepCopy();
            second.close();
            try(Worker returned=new Worker(host.port(),KEY,b,second.checkpoints)) {
                // Runtime resumes before the native assignment's hello. The old participant socket is dead.
                returned.current=second.current;returned.announce();
                JsonObject resume=op("resume");resume.addProperty("id",text(request,"id"));host.control(resume);
                for(int i=0;i<60;i++) { first.pump();returned.pump();Thread.sleep(10); }
                assert !text(host.control(op("status")),"status").startsWith("Coordinator stopped") : "A pending native reconnect cannot kill the host";
                assert returned.nativeResumes==0 && first.c.connected();
                returned.nativeJob=saved;returned.connectionStopped=true;returned.reconnectReady=false;returned.announce();
                for(int i=0;i<40;i++) { first.pump();returned.pump();Thread.sleep(10); }
                assert returned.nativeResumes==0 : "Loaded assignment without a ready world still waits";
                returned.reconnectReady=true;returned.scope="test.invalid\nminecraft:the_end";returned.announce();
                for(int i=0;i<40;i++) { first.pump();returned.pump();Thread.sleep(10); }
                assert returned.nativeResumes==0 : "A ready but wrong dimension cannot resume the highway";
                returned.scope="test.invalid\nminecraft:the_nether";returned.announce();
                await(()->returned.nativeResumes>0,first,returned);
                assert text(returned.nativeJob,"job").equals(text(saved,"job")) && returned.installs==0;
                resume.addProperty("op","cancel");host.control(resume);
                await(()->first.nativeJob==null && returned.nativeJob==null,first,returned);
            }
        }
        System.out.println("Native reconnect checks passed: runtime-before-assignment race, missing/wrong world, deferred resume and cancellation.");
    }

    private static void sharedSupplies() throws Exception {
        Path directory = Files.createTempDirectory("monocle-shared-supplies-");
        try (HostService host = new HostService(directory,"127.0.0.1",0,CREWS,30);
             Worker donor = new Worker(host.port(),KEY,UUID.randomUUID(),new LinkedHashMap<>());
             Worker recipient = new Worker(host.port(),KEY,UUID.randomUUID(),new LinkedHashMap<>())) {
            await(() -> connected(host)==2, donor,recipient);
            JsonObject request = highwayRequest(donor.id,recipient.id);
            JsonObject policy = new JsonObject(); policy.addProperty("enabled",true); policy.addProperty("trash",true);
            policy.addProperty("paving",512);policy.addProperty("picks",3);policy.addProperty("food",16);policy.addProperty("filler",64);
            request.getAsJsonObject("package").getAsJsonObject("geometry").getAsJsonObject("layout").add("inventory",policy);
            host.control(request); await(() -> donor.begun && recipient.begun,donor,recipient);
            recipient.detach(); await(() -> recipient.detached,donor,recipient);
            assert !donor.detached : "A requesting worker cannot initiate a crew-wide pit stop";
            for (Worker w : List.of(donor,recipient)) {
                w.ledger = new JsonObject(); w.ledger.addProperty("version",1); w.ledger.addProperty("busy",false); w.ledger.addProperty("idle",true);
                for (String tier : List.of("loose","shulkers","echest","reserve","target")) {
                    JsonArray values = new JsonArray();
                    for (int r=0;r<5;r++) values.add(r==0 ? tier.equals("target") ? 512 : tier.equals("shulkers") && w==donor ? 1728 : 0 : 0);
                    w.ledger.add(tier,values);
                }
                w.exchangeState = new JsonObject(); w.exchangeState.addProperty("stage","idle"); w.exchangeState.addProperty("need",w==recipient?0:-1); w.announce();
            }
            await(() -> donor.offer != null && recipient.offer != null,donor,recipient);
            assert text(donor.offer,"phase").equals("shared") && !donor.offer.has("proposal") : "Request must use shared restocking, never a throw/rendezvous transaction";
            JsonObject container = new JsonObject(); container.addProperty("x",0);container.addProperty("y",116);container.addProperty("z",99);
            donor.exchangeState.add("container",container); donor.exchangeState.addProperty("stage","serving");donor.announce();
            await(() -> recipient.offer.has("container"),donor,recipient);
            assert recipient.offer.get("container").equals(container) : "Requester follows the donor's observed supply position";
            String firstAttempt = text(recipient.offer,"id");
            recipient.exchangeState.addProperty("stage","complete");recipient.announce();
            await(() -> text(donor.offer,"phase").equals("complete"),donor,recipient);
            await(() -> !text(recipient.offer,"id").equals(firstAttempt) && text(recipient.offer,"phase").equals("shared"),donor,recipient);
            donor.exchangeState.add("container",container);donor.exchangeState.addProperty("stage","serving");donor.announce();
            await(() -> recipient.offer.has("container"),donor,recipient);
            donor.exchangeState.remove("container");donor.announce();
            await(() -> !recipient.offer.has("container"),donor,recipient);
            recipient.exchangeState.addProperty("stage","failed");recipient.announce();
            await(() -> text(donor.offer,"phase").equals("cancelled"),donor,recipient);
            assert !donor.nativePaused && !recipient.nativePaused : "Unavailable supplies release the shared attempt without pausing the highway";
            JsonObject cancel=op("cancel");cancel.add("id",request.get("id"));host.control(cancel);
            await(() -> donor.nativeJob==null && recipient.nativeJob==null,donor,recipient);
        }
        System.out.println("Shared supply socket checks passed: ordinary restock dispatch, live container advertisement/removal, retry and cancellation.");
    }

    private static void initialSupplies() throws Exception {
        for (int width : new int[] {3, 5}) {
            Path directory = Files.createTempDirectory("monocle-initial-supplies-");
            UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
            try (HostService host = new HostService(directory, "127.0.0.1", 0, CREWS, 30);
                 Worker first = new Worker(host.port(), KEY, a, new LinkedHashMap<>());
                 Worker second = new Worker(host.port(), KEY, b, new LinkedHashMap<>());
                 Worker third = new Worker(host.port(), KEY, c, new LinkedHashMap<>())) {
                await(() -> connected(host) == 3, first, second, third);
                JsonObject request = highwayRequest(a, b);
                request.getAsJsonArray("workers").add(c.toString());
                request.getAsJsonObject("package").getAsJsonObject("geometry").getAsJsonObject("layout").addProperty("width", width);
                host.control(request);
                await(() -> first.begun && second.begun && third.begun, first, second, third);
                int generation = first.nativeJob.get("generation").getAsInt();
                first.detach();
                await(() -> first.detached, first, second, third);
                assert !second.detached && !third.detached : "Only the requesting worker detaches, regardless of width";
                second.detach(); third.detach();
                await(() -> first.detached && second.detached && third.detached, first, second, third);
                await(() -> first.nativeJob.getAsJsonObject("suppliers").size()==3, first, second, third); // The third grant can arrive before the first worker's updated roster.
                assert first.row == 0 && second.row == 0 && third.row == 0 : "Initial stock-outs cannot require road progress";
                assert first.nativeJob.get("generation").getAsInt() == generation : "Pit stops retain the world verification generation";
                var sites = first.nativeJob.getAsJsonObject("suppliers");
                java.util.Set<Integer> positions = new java.util.HashSet<>();
                for (var entry : sites.entrySet()) {
                    JsonObject site = entry.getValue().getAsJsonObject();
                    assert HighwayCoordinator.validSupplySite(first.nativeJob, site);
                    if (width == 5) {
                        assert HighwayCoordinator.laneSite(site) && site.get("z").getAsInt() == 100;
                        positions.add(site.get("x").getAsInt());
                    } else positions.add(site.get("z").getAsInt());
                }
                assert positions.equals(width == 5 ? java.util.Set.of(-2, 0, 2) : java.util.Set.of(97, 94, 91));
                first.reserve();
                await(() -> !first.supplyLock.isEmpty(), first, second, third);
                first.delayLanding = true;
                first.release();
                await(() -> first.frontUpdates >= 2, first, second, third);
                assert first.detached : "A fresh front does not authorize joining before the worker lands";
                first.delayLanding = false; first.announce();
                await(() -> !first.detached, first, second, third);
                assert second.detached && third.detached : "One recovered container cannot finish another worker's supply task";
                JsonObject cancel = op("cancel"); cancel.add("id", request.get("id")); host.control(cancel);
                await(() -> first.nativeJob == null && second.nativeJob == null && third.nativeJob == null, first, second, third);
            }
        }
    }

    private static void independentSupplyFailures() throws Exception {
        Path directory = Files.createTempDirectory("monocle-independent-supply-check-");
        try (HostService host = new HostService(directory, "127.0.0.1", 0, CREWS, 30);
             Worker first = new Worker(host.port(), KEY, UUID.randomUUID(), new LinkedHashMap<>());
             Worker second = new Worker(host.port(), KEY, UUID.randomUUID(), new LinkedHashMap<>());
             Worker third = new Worker(host.port(), KEY, UUID.randomUUID(), new LinkedHashMap<>())) {
            await(() -> connected(host) == 3, first, second, third);
            JsonObject request = highwayRequest(first.id, second.id); request.getAsJsonArray("workers").add(third.id.toString());
            request.getAsJsonObject("args").addProperty("length", 512);
            third.supplyProtocol = 1; third.announce();
            UUID task = UUID.fromString(text(request, "id")); host.control(request);
            await(() -> text(taskView(host, task), "detail").contains("Update all highway workers"), first, second, third);
            assert first.nativeJob == null && second.nativeJob == null && third.nativeJob == null : "Mixed restock protocols must not partially start a crew";
            third.supplyProtocol = 2; third.announce();
            await(() -> first.begun && second.begun && third.begun, first, second, third);
            for (Worker w : List.of(first, second, third)) { w.row = 40; w.announce(); }
            await(() -> highwayStatus(host).get("progress").getAsInt() == 40, first, second, third);
            first.detach(); await(() -> first.detached, first, second, third); first.reserve();
            second.advance = third.advance = true;
            await(() -> second.row >= 48 && third.row >= 48, first, second, third);
            assert !first.supplyLock.isEmpty() && !second.nativePaused && !third.nativePaused : "An unrecovered local container does not stop other lanes";
            second.advance = false; second.detach(); await(() -> second.detached, first, second, third); second.reserve();
            int before = third.row; await(() -> third.row >= before + 5, first, second, third);
            third.advance = false; third.detach(); await(() -> third.detached, first, second, third);
            int front = highwayStatus(host).get("progress").getAsInt();
            assert front > 48 && first.row == 40;
            first.release();
            await(() -> first.serviceFront != null, first, second, third);
            assert first.serviceFront.get("z").getAsInt() == 100 + front : "All-detached return targets the unfinished front, not the original supply site";
            assert first.detached : "A supplier still at the old site cannot claim it is back at the work front";
            first.row = front; first.announce(); await(() -> !first.detached, first, second, third);
            assert second.detached && third.detached;
            assert highwayStatus(host).getAsJsonObject("supplyContainers").has(second.id.toString());
            first.advance = true;
            int resumedAt = first.row; await(() -> first.row >= resumedAt + 5, first, second, third);
            second.row = first.row; second.ignoreJoin = true; second.release();
            await(() -> second.rejectedJoins > 0, first, second, third);
            await(() -> !second.returning && HighwayCoordinator.detachedMembers(first.nativeJob).contains(second.id), first, second, third);
            assert second.detached && !first.nativePaused : "Missing rejoin ACK rolls back only the returner, not the crew";
            int afterRejected = first.row; await(() -> first.row >= afterRejected + 5, first, second, third);
            await(() -> !highwayStatus(host).getAsJsonObject("supplyContainers").has(second.id.toString()), first, second, third);
            JsonObject savedThird = third.nativeJob.deepCopy(); third.close();
            int afterDisconnect = first.row; await(() -> first.row >= afterDisconnect + 5, first, second);
            assert !first.nativePaused : "A disconnected supplier never pauses the native job";
            second.ignoreJoin = false; second.row = first.row; second.release();
            await(() -> !second.detached, first, second);
            JsonObject failed = second.checkpoints.get(UUID.fromString(second.current));
            failed.addProperty("status", "Suspending"); failed.addProperty("requestedStatus", "Failed"); second.sendStatus(failed); second.heartbeat();
            await(() -> second.nativeJob.has("awayMembers") && second.nativeJob.getAsJsonObject("awayMembers").has(second.id.toString()), first, second);
            int afterFailure = first.row; await(() -> first.row >= afterFailure + 5, first, second);
            assert !first.nativePaused && state(host, task).equals("Running") : "One failed workflow cannot cancel or pause a healthy worker";
            JsonObject cancel = op("cancel"); cancel.addProperty("id", task.toString()); host.control(cancel);
            assert state(host, task).equals("Cancelled");
            await(() -> first.nativeJob == null && second.nativeJob == null, first, second);
            try (Worker returned = new Worker(host.port(), KEY, third.id, third.checkpoints)) {
                returned.nativeJob = savedThird; returned.current = third.current; returned.announce();
                await(() -> returned.nativeJob == null && !flag(taskView(host, task), "cleanupPending"), first, second, returned);
                assert returned.installs == 0 && returned.resumes == 0 : "Reconnect honors cancellation instead of replaying the supply trip";
            }
        }
        System.out.println("Independent restocking failure checks passed: concurrent containers, all-detached front, lost rejoin ACK, offline supplier, failed worker, cancellation and reconnect.");
    }

    private static JsonObject highwayStatus(HostService host) {
        return host.control(op("status")).getAsJsonObject("highways").getAsJsonObject("Default");
    }

    private static void highway() throws Exception {
        Path directory=Files.createTempDirectory("monocle-native-host-check-");
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            ControlApi api=new ControlApi(host,0,TOKEN);
            HttpClient http=HttpClient.newHttpClient();
            Worker first=new Worker(host.port(),KEY,a,new LinkedHashMap<>());
            Worker second=new Worker(host.port(),KEY,b,new LinkedHashMap<>())) {
            await(()->connected(host)==2,first,second);
            JsonObject request=highwayRequest(a,b);
            request.getAsJsonObject("package").getAsJsonObject("geometry").getAsJsonObject("layout").addProperty("width",3);
            UUID task=UUID.fromString(text(request,"id"));host.control(request);
            await(()->first.nativeJob!=null && second.nativeJob!=null && first.begun && second.begun,first,second);
            assert first.nativeJob.get("job").equals(second.nativeJob.get("job"));
            assert first.nativeJob.get("index").getAsInt()!=second.nativeJob.get("index").getAsInt();
            assert first.nativeJob.get("hostMember").getAsString().isEmpty() : "No fake participating host/world authority";
            String execution=text(first.nativeJob,"job");
            JsonObject inspection=op("resolve-transfers");inspection.addProperty("crew","Default");inspection.addProperty("execution",execution);
            rejects(()->host.control(inspection));inspection.addProperty("confirmed",false);rejects(()->host.control(inspection));
            inspection.addProperty("confirmed",true);inspection.addProperty("execution",UUID.randomUUID().toString());rejects(()->host.control(inspection));
            inspection.addProperty("execution",execution);host.control(inspection);
            await(()->first.inspections==1 && second.inspections==1,first,second);
            assert text(first.nativeJob,"job").equals(execution) : "Inspection does not replace the highway execution";
            assert host.control(op("status")).getAsJsonObject("highways").getAsJsonObject("Default").getAsJsonArray("workers")
                .asList().stream().allMatch(w->w.getAsJsonObject().has("exchange")) : "Expose worker transfer diagnostics, not just the generic highway label";
            first.ledger=JsonParser.parseString("{version:1,busy:false,idle:true,loose:[64,2,16,0,1],shulkers:[128,3,32,0,0],echest:[512,6,64,0,2],reserve:[128,2,16,0,2],target:[512,3,64,64,4],inventory:{'minecraft:obsidian':192},storage:{'minecraft:obsidian':512},echestKnown:true}").getAsJsonObject();first.announce();
            await(()->highwayStatus(host).getAsJsonObject("resourceCounts").getAsJsonObject("total").get("obsidian").getAsInt()==704,first,second);
            JsonObject firstResources=highwayStatus(host).getAsJsonArray("workers").asList().stream().map(JsonElement::getAsJsonObject).filter(w->text(w,"id").equals(first.id.toString())).findFirst().orElseThrow().getAsJsonObject("resourceCounts");
            assert firstResources.getAsJsonObject("inventory").get("pickaxes").getAsInt()==5 && firstResources.getAsJsonObject("enderChest").get("food").getAsInt()==64;
            JsonObject resourceView=JsonParser.parseString(resourceRequest(http,api,"GET","/v1/workers/"+a+"/resources",null,TOKEN).body()).getAsJsonObject();
            assert resourceView.get("known").getAsBoolean() && resourceView.has("observedAt")
                && resourceView.getAsJsonObject("counts").getAsJsonObject("total").get("pavingBlocks").getAsInt()==704
                && !resourceView.getAsJsonObject("counts").getAsJsonObject("total").has("obsidian");
            first.roadForecast=JsonParser.parseString("{nextBlocks:16,blocksPerSecond:2.1,ageTicks:10}").getAsJsonObject();first.announce();
            await(()->highwayStatus(host).has("roadPrediction"),first,second);
            assert highwayStatus(host).getAsJsonObject("roadPrediction").get("blocksPerSecond").getAsDouble()==2.1;
            JsonObject control=op("pause");control.addProperty("id",task.toString());host.control(control);
            await(()->first.nativePaused && second.nativePaused
                && first.checkpoints.values().stream().anyMatch(r->text(r,"status").equals("Suspending")),first,second);
            control.addProperty("op","resume");host.control(control);
            await(()->!first.nativePaused && !second.nativePaused && state(host,task).equals("Running")
                && first.checkpoints.values().stream().noneMatch(r->text(r,"status").equals("Suspending")),first,second);
            assert text(first.nativeJob,"job").equals(execution) && first.installs==1 : "Native resume preserves assignment and workflow frames";
            // A builder-only restock pause is invisible to the workflow's Running status.
            first.nativePaused=true;first.announce();int nativeResumes=first.nativeResumes;
            host.control(control);
            await(()->!first.nativePaused && first.nativeResumes>nativeResumes,first,second);
            int dutyGeneration=second.nativeJob.get("generation").getAsInt();
            first.moduleOff=true;first.announce();
            await(()->second.nativeJob.getAsJsonArray("activeMembers").size()==1,first,second);
            second.advance=true;await(()->second.row>=2,first,second);second.advance=false;
            first.row=second.row;first.moduleOff=false;first.announce();
            await(()->second.nativeJob.getAsJsonArray("activeMembers").size()==2
                && !first.nativeJob.getAsJsonObject("awayMembers").has(a.toString()),first,second);
            assert second.nativeJob.get("generation").getAsInt()==dutyGeneration && first.installs==1
                : "Manual leave/rejoin must not restart the crew, workflow or world generation";
            first.detach();
            await(()->first.detached && second.nativeJob.getAsJsonArray("activeMembers").size()==1,first,second);
            int generation=second.nativeJob.get("generation").getAsInt();
            first.reserve();await(()->!first.supplyLock.isEmpty(),first,second);
            second.advance=true;await(()->second.row>=7,first,second);second.advance=false;
            assert second.nativeJob.get("generation").getAsInt()==generation : "Detached supplies preserve live world ACKs";
            assert !first.supplyLock.isEmpty() : "Other workers move without stealing a granted container reservation";
            first.release();await(()->!first.detached && second.nativeJob.getAsJsonArray("activeMembers").size()==2,first,second);
            first.advance=second.advance=true;
            await(()->state(host,task).equals("Complete"),first,second);
            assert first.nativeJob==null && second.nativeJob==null : "Complete waits for native END acknowledgments";
            assert host.control(op("status")).getAsJsonObject("highways").getAsJsonObject("Default").get("pendingEnds").getAsInt()==0;
            JsonObject another=highwayRequest(a,b);UUID next=UUID.fromString(text(another,"id"));first.advance=second.advance=false;host.control(another);
            await(()->first.nativeJob!=null && second.nativeJob!=null,first,second);
            second.ignoreEnd=true;second.close();
            JsonObject cancel=op("cancel");cancel.addProperty("id",next.toString());host.control(cancel);
            await(()->first.nativeJob==null,first);
            assert state(host,next).equals("Cancelled") : "Offline END delivery does not gate host cancellation";
            JsonObject replacement=highwayRequest(a,b);replacement.getAsJsonArray("workers").remove(1);
            UUID replacementId=UUID.fromString(text(replacement,"id"));host.control(replacement);
            await(()->first.nativeJob!=null && first.begun,first);
            first.advance=true;await(()->state(host,replacementId).equals("Complete"),first);first.advance=false;
            assert host.control(op("status")).getAsJsonObject("highways").getAsJsonObject("Default").get("pendingEnds").getAsInt()==1 : "Offline cancellation receipt remains durable while unrelated work completes";
            replacement.addProperty("id",UUID.randomUUID().toString());host.control(replacement);
            await(()->first.nativeJob!=null && first.begun,first);
            String replacementExecution=text(first.nativeJob,"job");
            assert !replacementExecution.equals(text(second.nativeJob,"job")) : "Available workers start replacement work while the old worker is offline";
            JsonObject savedJob=second.nativeJob.deepCopy();
            second.checkpoints.values().forEach(r->{ if(!QueuePolicy.terminal(text(r,"status")))r.addProperty("status","Suspended"); });
            try(Worker returned=new Worker(host.port(),KEY,b,second.checkpoints)) {
                returned.nativeJob=savedJob;returned.row=0;returned.announce();
                await(()->returned.nativeJob==null && returned.checkpoints.values().stream().allMatch(r->QueuePolicy.terminal(text(r,"status"))),first,returned);
                assert returned.nativeJob==null;
                assert returned.resumes==0 && returned.installs==0;
                assert text(first.nativeJob,"job").equals(replacementExecution) : "Late END acknowledgment must not clear the replacement job";
            }
            JsonObject end=op("end-highway");end.addProperty("crew","Default");host.control(end);
            assert state(host,UUID.fromString(text(replacement,"id"))).equals("Cancelled") : "Native End must also cancel its owning workflow";
            await(()->first.nativeJob==null && first.current.isEmpty(),first);
        }
        System.out.println("Native highway socket checks passed: shared preparation, worker authority, detached supply/lane updates, moving rejoin, verified completion and durable offline cancellation. Journals: "+directory);
    }
    private static JsonObject highwayRequest(UUID a,UUID b) {
        JsonObject request=submit(a,0);request.getAsJsonArray("workers").add(b.toString());request.remove("script");
        var workflows=new dev.monocle.client.systems.bots.BotWorkflows(Path.of("build", "test-builtins-"+UUID.randomUUID()+".json"));
        JsonObject packaged=workflows.packageWorkflows(dev.monocle.client.systems.bots.BotWorkflows.DEFAULT_ID);
        JsonObject profiles=new JsonObject();profiles.add("Current",new JsonObject());packaged.add("profiles",profiles);
        JsonObject geometry=new JsonObject(),layout=new JsonObject();geometry.addProperty("scope","test.invalid\nminecraft:the_nether");geometry.addProperty("x",0);geometry.addProperty("y",116);geometry.addProperty("z",100);
        layout.addProperty("dx",0);layout.addProperty("dz",1);layout.addProperty("heading","South");layout.addProperty("width",5);layout.addProperty("height",3);
        layout.addProperty("operation","Build");layout.addProperty("floor","Replace");layout.addProperty("blocks","minecraft:obsidian");
        for(String flag:List.of("railings","supports","above"))layout.addProperty(flag,true);geometry.add("layout",layout);packaged.add("geometry",geometry);
        request.add("package",packaged);JsonObject args=new JsonObject();args.addProperty("length",16);request.add("args",args);return request;
    }
    private static HttpResponse<String> request(HttpClient http, ControlApi api, JsonObject command, String token, boolean origin) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + "/control")).timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        if (origin) builder.header("Origin", "https://example.invalid");
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(command.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> resourceRequest(HttpClient http,ControlApi api,String method,String path,JsonObject body,String token) throws Exception {
        return resourceRequest(http,api,method,path,body,token,UUID.randomUUID().toString(),null);
    }
    private static HttpResponse<String> resourceRequest(HttpClient http,ControlApi api,String method,String path,JsonObject body,String token,String commandId,String revision) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+api.port()+path)).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+token);
        if(body!=null)builder.header("Content-Type","application/json");
        if(!method.equals("GET"))builder.header("Idempotency-Key",commandId);
        if(revision!=null)builder.header("If-Match",revision);
        return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static void resourceApiCheck() throws Exception {
        Path directory=Files.createTempDirectory("monocle-resources-check-");
        JsonObject config=new JsonObject();config.add("crews",new Gson().toJsonTree(CREWS));TaskFiles.write(directory.resolve("host-config.json"),config);
        UUID workerId=UUID.randomUUID();
        String workflowCommandId=UUID.randomUUID().toString();
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            ControlApi api=new ControlApi(host,0,TOKEN);
            HttpClient http=HttpClient.newHttpClient();
            Worker worker=new Worker(host.port(),KEY,workerId,new LinkedHashMap<>())) {
            await(()->connected(host)==1,worker);
            assert resourceRequest(http,api,"GET","/v1/workers",null,"wrong").statusCode()==403;
            String unknownKit=UUID.randomUUID().toString();
            assert resourceRequest(http,api,"GET","/v1/kits/"+unknownKit+"/stock",null,TOKEN).statusCode()==404;
            assert resourceRequest(http,api,"GET","/v1/kits/invalid/stock",null,TOKEN).statusCode()==400;
            assert resourceRequest(http,api,"GET","/v1/kits/"+unknownKit+"/stock?unexpected=1",null,TOKEN).statusCode()==400;
            assert resourceRequest(http,api,"GET","/v1/kits/"+unknownKit+"/stock",null,"wrong").statusCode()==403;
            JsonObject identity=JsonParser.parseString(resourceRequest(http,api,"GET","/v1/players/"+worker.name,null,TOKEN).body()).getAsJsonObject();
            assert text(identity,"id").equals(workerId.toString()) && text(identity,"name").equals(worker.name) : "Connected Minecraft names resolve without an external lookup";
            assert resourceRequest(http,api,"GET","/v1/players/invalid.name",null,TOKEN).statusCode()==400;
            assert resourceRequest(http,api,"GET","/v1/players/"+worker.name,null,"wrong").statusCode()==403;
            JsonObject profile=new JsonObject();profile.addProperty("id",workerId.toString().replace("-",""));profile.addProperty("name",worker.name);
            assert text(ResourceApi.checkedPlayerIdentity(profile,worker.name.toLowerCase(java.util.Locale.ROOT)),"id").equals(workerId.toString());
            rejects(()->ResourceApi.checkedPlayerIdentity(profile,"DifferentName"));
            profile.addProperty("id","invalid");rejects(()->ResourceApi.checkedPlayerIdentity(profile,worker.name));
            var crossSite=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+api.port()+"/v1/workers"))
                .header("Authorization","Bearer "+TOKEN).header("Origin","https://example.invalid").GET().build();
            assert http.send(crossSite,HttpResponse.BodyHandlers.ofString()).statusCode()==403;
            assert resourceRequest(http,api,"GET","/v1/workers/%2e%2e",null,TOKEN).statusCode()==400;
            JsonObject workers=JsonParser.parseString(resourceRequest(http,api,"GET","/v1/workers?limit=1",null,TOKEN).body()).getAsJsonObject();
            assert workers.getAsJsonArray("items").size()==1 && text(workers.getAsJsonArray("items").get(0).getAsJsonObject(),"id").equals(workerId.toString());
            String crewId=text(workers.getAsJsonArray("items").get(0).getAsJsonObject(),"crewId");
            assert UUID.fromString(crewId)!=null;
            var defaultCrew=resourceRequest(http,api,"GET","/v1/crews/"+crewId,null,TOKEN);
            assert defaultCrew.statusCode()==200 && text(JsonParser.parseString(defaultCrew.body()).getAsJsonObject(),"name").equals("Default");
            assert resourceRequest(http,api,"GET","/v1/workers?connected=no",null,TOKEN).statusCode()==400;
            JsonObject firstEvents=JsonParser.parseString(resourceRequest(http,api,"GET","/v1/events",null,TOKEN).body()).getAsJsonObject();
            String eventCursor=text(firstEvents,"nextCursor");
            JsonObject unknownResources=JsonParser.parseString(resourceRequest(http,api,"GET","/v1/workers/"+workerId+"/resources",null,TOKEN).body()).getAsJsonObject();
            assert !unknownResources.get("known").getAsBoolean() && !unknownResources.has("counts") : "No highway report is unknown, not zero stock";
            assert resourceRequest(http,api,"DELETE","/v1/workers/"+workerId,null,TOKEN).statusCode()==409 : "Connected workers cannot be forgotten";
            assert resourceRequest(http,api,"GET","/v1/configuration-controls",null,TOKEN).statusCode()==200;
            JsonObject workflowPage=JsonParser.parseString(resourceRequest(http,api,"GET","/v1/workflows",null,TOKEN).body()).getAsJsonObject();
            String workflowId=text(workflowPage.getAsJsonArray("items").get(0).getAsJsonObject(),"id");
            assert resourceRequest(http,api,"GET","/v1/workflows/"+workflowId,null,TOKEN).statusCode()==200;
            JsonObject body=new JsonObject();body.addProperty("id",UUID.randomUUID().toString());body.addProperty("crewId",crewId);body.addProperty("name","Resource route wait");
            JsonArray targets=new JsonArray();targets.add(workerId.toString());body.add("workerIds",targets);
            JsonObject scope=new JsonObject();scope.addProperty("server","test.invalid");scope.addProperty("dimension","minecraft:the_nether");body.add("scope",scope);
            JsonObject savedWorkflow=new JsonObject();savedWorkflow.addProperty("name","Wait");savedWorkflow.addProperty("folder","Tests");
            savedWorkflow.add("package",JsonParser.parseString("{\"version\":1,\"entry\":\"main\",\"programs\":{\"main\":{\"name\":\"Wait\",\"script\":\"return function(ctx) return bot.wait(200) end\"}},\"profiles\":{\"Current\":{}},\"highways\":{}}").getAsJsonObject());
            var createdWorkflow=resourceRequest(http,api,"POST","/v1/workflows",savedWorkflow,TOKEN,workflowCommandId,null);
            assert createdWorkflow.statusCode()==201 : createdWorkflow.body();
            assert resourceRequest(http,api,"POST","/v1/workflows",savedWorkflow,TOKEN,workflowCommandId,null).body().equals(createdWorkflow.body()) : "Lost reply replays the durable result";
            JsonObject changedWorkflow=savedWorkflow.deepCopy();changedWorkflow.addProperty("name","Different intent");
            assert resourceRequest(http,api,"POST","/v1/workflows",changedWorkflow,TOKEN,workflowCommandId,null).statusCode()==409 : "Reused command ID cannot mutate a different workflow";
            var missingKey=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+api.port()+"/v1/workflows"))
                .header("Authorization","Bearer "+TOKEN).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(savedWorkflow.toString())).build();
            assert http.send(missingKey,HttpResponse.BodyHandlers.ofString()).statusCode()==428;
            String uncertainId=UUID.randomUUID().toString();
            host.operatorOperations().begin(uncertainId,TaskFiles.hash("POST\n/v1/workflows\nnull\n"+changedWorkflow));
            var uncertain=resourceRequest(http,api,"POST","/v1/workflows",changedWorkflow,TOKEN,uncertainId,null);
            assert uncertain.statusCode()==202 && text(JsonParser.parseString(uncertain.body()).getAsJsonObject(),"state").equals("outcome_uncertain") : "Interrupted intent is not replayed";
            JsonObject operation=JsonParser.parseString(resourceRequest(http,api,"GET","/v1/operations/"+workflowCommandId,null,TOKEN).body()).getAsJsonObject();
            assert text(operation,"state").equals("completed") && operation.get("httpStatus").getAsInt()==201 && !operation.has("fingerprint");
            JsonObject newerEvents=JsonParser.parseString(resourceRequest(http,api,"GET","/v1/events?cursor="+eventCursor,null,TOKEN).body()).getAsJsonObject();
            assert newerEvents.getAsJsonArray("items").asList().stream().anyMatch(value->workflowCommandId.equals(text(value.getAsJsonObject(),"correlationId"))) : "Command receipt is visible in the change feed";
            body.addProperty("workflowId",text(JsonParser.parseString(createdWorkflow.body()).getAsJsonObject(),"id"));
            var created=resourceRequest(http,api,"POST","/v1/jobs",body,TOKEN);
            assert created.statusCode()==202 : created.body();
            String jobId=text(JsonParser.parseString(created.body()).getAsJsonObject(),"id");
            String startOperation=text(JsonParser.parseString(created.body()).getAsJsonObject(),"operationId");
            assert resourceRequest(http,api,"GET","/v1/jobs/"+jobId,null,TOKEN).statusCode()==200;
            assert resourceRequest(http,api,"GET","/v1/jobs/"+jobId+"/configuration",null,TOKEN).statusCode()==200;
            await(()->state(host,UUID.fromString(jobId)).equals("Running"),worker);
            assert text(host.operatorOperation(startOperation),"state").equals("completed") : "Running the accepted execution completes its correlated start receipt";
            String pauseId=UUID.randomUUID().toString();JsonObject empty=new JsonObject();
            var paused=resourceRequest(http,api,"POST","/v1/jobs/"+jobId+"/pause",empty,TOKEN,pauseId,null);assert paused.statusCode()==202;
            await(()->text(host.operatorOperation(pauseId),"state").equals("completed"),worker);
            assert host.operatorOperation(pauseId).getAsJsonObject("receipt").getAsJsonObject("workers").getAsJsonObject(workerId.toString()).has("acknowledgedAt");
            assert resourceRequest(http,api,"POST","/v1/jobs/"+jobId+"/pause",empty,TOKEN,pauseId,null).body().equals(paused.body()) : "Retries retain the original response even as receipt evidence advances";
            String resumeId=UUID.randomUUID().toString();assert resourceRequest(http,api,"POST","/v1/jobs/"+jobId+"/resume",empty,TOKEN,resumeId,null).statusCode()==202;
            await(()->text(host.operatorOperation(resumeId),"state").equals("completed"),worker);
            JsonObject guided=new JsonObject();guided.addProperty("control","toggle-auto-eat");guided.addProperty("active",true);guided.addProperty("workerId",workerId.toString());
            var configurationUpdate=resourceRequest(http,api,"PATCH","/v1/jobs/"+jobId+"/configuration",guided,TOKEN);
            String configurationId=text(JsonParser.parseString(configurationUpdate.body()).getAsJsonObject(),"operationId");
            assert configurationUpdate.statusCode()==202 : configurationUpdate.body();
            assert JsonParser.parseString(configurationUpdate.body()).getAsJsonObject().getAsJsonObject("updates").getAsJsonObject(workerId.toString()).get("requestedRevision").getAsInt()==1;
            JsonObject invalidGuided=guided.deepCopy();invalidGuided.addProperty("control","unsafe-unknown-setting");
            assert resourceRequest(http,api,"PATCH","/v1/jobs/"+jobId+"/configuration",invalidGuided,TOKEN).statusCode()==400;
            await(()->text(host.control(inspectJobConfiguration(jobId)).getAsJsonObject("updates").getAsJsonObject(workerId.toString()),"status").equals("Accepted"),worker);
            assert text(host.operatorOperation(configurationId),"state").equals("completed");
            String cancelId=UUID.randomUUID().toString();assert resourceRequest(http,api,"POST","/v1/jobs/"+jobId+"/cancel",new JsonObject(),TOKEN,cancelId,null).statusCode()==202;
            assert text(JsonParser.parseString(resourceRequest(http,api,"GET","/v1/jobs/"+jobId,null,TOKEN).body()).getAsJsonObject(),"state").equals("cancelled");
            await(()->worker.current.isEmpty()&&!flag(taskView(host,UUID.fromString(jobId)),"cleanupPending"),worker);
            assert text(host.operatorOperation(cancelId),"state").equals("completed");
            JsonObject coldReceipt=new OperatorOperations(directory.resolve("operator-operations.json")).get(cancelId);assert text(coldReceipt,"state").equals("completed")&&coldReceipt.has("receipt") : "Worker outcomes survive journal reload without an HTTP poll";
            assert JsonParser.parseString(resourceRequest(http,api,"GET","/v1/capabilities",null,TOKEN).body()).getAsJsonObject().getAsJsonArray("portableActions").size()==3;
            for(String type:List.of("workers.wait.v1","workers.travel.v1","workers.drop-items.v1")) {
                JsonObject portable=body.deepCopy();portable.addProperty("id",UUID.randomUUID().toString());portable.remove("workflowId");portable.remove("name");
                JsonObject action=new JsonObject(),arguments=new JsonObject();action.addProperty("type",type);
                switch(type) {
                    case "workers.wait.v1" -> arguments.addProperty("ticks",20);
                    case "workers.travel.v1" -> {arguments.add("scope",scope.deepCopy());arguments.addProperty("x",10);arguments.addProperty("y",116);arguments.addProperty("z",100);arguments.addProperty("radius",2);}
                    case "workers.drop-items.v1" -> {arguments.addProperty("item","minecraft:obsidian");arguments.addProperty("count",1);arguments.addProperty("recipientWorkerId",workerId.toString());}
                }
                action.add("arguments",arguments);portable.add("action",action);
                var accepted=resourceRequest(http,api,"POST","/v1/jobs",portable,TOKEN);
                assert accepted.statusCode()==202 : accepted.body();
                String portableId=text(portable,"id");
                JsonObject inspect=op("task-get");inspect.addProperty("id",portableId);
                JsonObject saved=host.control(inspect);
                assert text(saved.getAsJsonObject("package"),"entry").equals(type.equals("workers.wait.v1")?"task-wait":type.equals("workers.travel.v1")?"task-travel":"task-drop");
                assert saved.getAsJsonObject("args").has(type.equals("workers.wait.v1")?"ticks":type.equals("workers.travel.v1")?"x":"item");
                await(()->state(host,UUID.fromString(portableId)).equals("Running"),worker);
                assert resourceRequest(http,api,"POST","/v1/jobs/"+portableId+"/cancel",new JsonObject(),TOKEN).statusCode()==202;
                await(()->worker.current.isEmpty()&&!flag(taskView(host,UUID.fromString(portableId)),"cleanupPending"),worker);
            }
            JsonObject invalidPortable=body.deepCopy();invalidPortable.addProperty("id",UUID.randomUUID().toString());invalidPortable.remove("workflowId");
            JsonObject badAction=new JsonObject(),badArgs=new JsonObject();badAction.addProperty("type","workers.set-profile.v1");badAction.add("arguments",badArgs);invalidPortable.add("action",badAction);
            assert resourceRequest(http,api,"POST","/v1/jobs",invalidPortable,TOKEN).statusCode()==400 : "Unimplemented profile revisions cannot silently become local names";
            badAction.addProperty("type","workers.travel.v1");badArgs.add("scope",scope.deepCopy());badArgs.getAsJsonObject("scope").addProperty("dimension","minecraft:overworld");badArgs.addProperty("x",1);badArgs.addProperty("y",116);badArgs.addProperty("z",1);
            assert resourceRequest(http,api,"POST","/v1/jobs",invalidPortable,TOKEN).statusCode()==400 : "Portable travel cannot cross the declared world scope";
            JsonObject scripted=body.deepCopy();scripted.addProperty("id",UUID.randomUUID().toString());scripted.remove("workflowId");
            scripted.add("package",JsonParser.parseString("{\"version\":1,\"entry\":\"main\",\"programs\":{\"main\":{\"name\":\"UI wait\",\"script\":\"return function(ctx) return bot.wait(200) end\"}},\"profiles\":{\"Current\":{}},\"highways\":{}}").getAsJsonObject());
            String scriptCommand=UUID.randomUUID().toString();
            var scriptReply=resourceRequest(http,api,"POST","/v1/jobs",scripted,TOKEN,scriptCommand,null);
            assert scriptReply.statusCode()==202 : "Wizard-captured scripts use the resource job route";
            assert resourceRequest(http,api,"POST","/v1/jobs",scripted,TOKEN,scriptCommand,null).body().equals(scriptReply.body()) : "Uncertain UI submissions replay the same receipt";
            String scriptedId=text(scripted,"id");await(()->state(host,UUID.fromString(scriptedId)).equals("Running"),worker);
            assert resourceRequest(http,api,"POST","/v1/jobs/"+scriptedId+"/cancel",new JsonObject(),TOKEN).statusCode()==202;
            await(()->worker.current.isEmpty()&&!flag(taskView(host,UUID.fromString(scriptedId)),"cleanupPending"),worker);
            JsonObject stashDefinition=new JsonObject(),bounds=new JsonObject();stashDefinition.addProperty("crewId",crewId);
            stashDefinition.addProperty("name","Test depot");stashDefinition.add("world",scope.deepCopy());
            for(String axis:List.of("X","Y","Z")) {
                int coordinate=axis.equals("X")?10:axis.equals("Y")?116:100;
                bounds.addProperty("min"+axis,coordinate);bounds.addProperty("max"+axis,coordinate);
            }
            bounds.addProperty("homeName","testhome");stashDefinition.add("bounds",bounds);
            var createdStash=resourceRequest(http,api,"POST","/v1/stashes",stashDefinition,TOKEN);
            assert createdStash.statusCode()==201 : createdStash.body();
            String stashId=text(JsonParser.parseString(createdStash.body()).getAsJsonObject(),"id");
            await(()->worker.stashCatalog.asList().stream().anyMatch(value->text(value.getAsJsonObject(),"name").equals("Test depot")),worker);
            int catalogs=worker.stashCatalogReports;
            for(int i=0;i<10;i++)worker.heartbeat();
            Thread.sleep(250);worker.pump();
            assert worker.stashCatalogReports==catalogs : "Checkpoint heartbeats must not reread and resend the entire stash catalog";
            assert resourceRequest(http,api,"POST","/v1/stashes",stashDefinition,TOKEN).statusCode()==409 : "Creation cannot overwrite observed stash data";
            assert !JsonParser.parseString(resourceRequest(http,api,"GET","/v1/stashes/"+stashId+"/resources",null,TOKEN).body()).getAsJsonObject().get("complete").getAsBoolean() : "An unscanned stash is not known empty";
            String stashRevision=text(JsonParser.parseString(createdStash.body()).getAsJsonObject(),"revision");
            JsonObject edit=new JsonObject(),changes=new JsonObject();changes.addProperty("homeName","otherhome");changes.addProperty("lazyMode",false);edit.add("bounds",changes);
            var editedStash=resourceRequest(http,api,"PATCH","/v1/stashes/"+stashId,edit,TOKEN,UUID.randomUUID().toString(),stashRevision);
            assert editedStash.statusCode()==200 : editedStash.body();
            assert text(JsonParser.parseString(editedStash.body()).getAsJsonObject().getAsJsonObject("bounds"),"homeName").equals("otherhome");
            await(()->worker.stashCatalog.asList().stream().anyMatch(value->text(value.getAsJsonObject().getAsJsonObject("bounds"),"homeName").equals("otherhome")),worker);
            assert resourceRequest(http,api,"PATCH","/v1/stashes/"+stashId,edit,TOKEN,UUID.randomUUID().toString(),stashRevision).statusCode()==412 : "Stale stash edits must fail";
            assert resourceRequest(http,api,"PATCH","/v1/stashes/"+stashId,edit,TOKEN).statusCode()==428 : "Stash edits need a revision";
            JsonObject moved=new JsonObject(),moveBounds=new JsonObject();moveBounds.addProperty("maxX",11);moved.add("bounds",moveBounds);
            String editRevision=text(JsonParser.parseString(editedStash.body()).getAsJsonObject(),"revision");
            var movedStash=resourceRequest(http,api,"PATCH","/v1/stashes/"+stashId,moved,TOKEN,UUID.randomUUID().toString(),editRevision);
            assert movedStash.statusCode()==200 : "Unscanned bounds can be reselected";
            JsonObject savedDefinition=JsonParser.parseString(movedStash.body()).getAsJsonObject();
            JsonObject observed=new JsonObject();observed.addProperty("x",10);observed.addProperty("y",116);observed.addProperty("z",100);observed.addProperty("status","observed");observed.addProperty("block","minecraft:chest");observed.addProperty("capacity",54);observed.addProperty("slotCount",54);observed.addProperty("occupiedSlots",0);observed.add("items",new JsonObject());
            observed.add("parts",JsonParser.parseString("[{x:10,y:116,z:100},{x:11,y:116,z:100}]"));
            StashCatalog.save(directory,text(savedDefinition,"crew"),text(savedDefinition,"scope"),savedDefinition.getAsJsonObject("bounds"),observed);
            String observedRevision=text(JsonParser.parseString(resourceRequest(http,api,"GET","/v1/stashes/"+stashId,null,TOKEN).body()).getAsJsonObject(),"revision");
            assert resourceRequest(http,api,"PATCH","/v1/stashes/"+stashId,moved,TOKEN,UUID.randomUUID().toString(),observedRevision).statusCode()==200 : "Unchanged bounds preserve observations";
            JsonObject shrink=new JsonObject(),shrinkBounds=new JsonObject();shrinkBounds.addProperty("minX",11);shrink.add("bounds",shrinkBounds);
            String latestRevision=text(JsonParser.parseString(resourceRequest(http,api,"GET","/v1/stashes/"+stashId,null,TOKEN).body()).getAsJsonObject(),"revision");
            assert resourceRequest(http,api,"PATCH","/v1/stashes/"+stashId,shrink,TOKEN,UUID.randomUUID().toString(),latestRevision).statusCode()==409 : "Observed bounds cannot silently shed inventory history";
            JsonObject expansion=new JsonObject(),expandedBounds=new JsonObject();expandedBounds.addProperty("maxX",12);expansion.add("bounds",expandedBounds);
            var expandedStash=resourceRequest(http,api,"PATCH","/v1/stashes/"+stashId,expansion,TOKEN,UUID.randomUUID().toString(),latestRevision);
            assert expandedStash.statusCode()==200 : "Observed stash bounds may safely expand: "+expandedStash.body();
            savedDefinition=JsonParser.parseString(expandedStash.body()).getAsJsonObject();
            StashCatalog.define(directory,text(savedDefinition,"crew"),text(savedDefinition,"scope"),savedDefinition.getAsJsonObject("bounds"),workerId.toString());
            JsonObject homeChange=new JsonObject(),homeBounds=new JsonObject();homeBounds.addProperty("homeWarmupTicks",100);homeChange.add("bounds",homeBounds);
            String workerHomeRevision=text(JsonParser.parseString(resourceRequest(http,api,"GET","/v1/stashes/"+stashId,null,TOKEN).body()).getAsJsonObject(),"revision");
            assert resourceRequest(http,api,"PATCH","/v1/stashes/"+stashId,homeChange,TOKEN,UUID.randomUUID().toString(),workerHomeRevision).statusCode()==409 : "Default home edits cannot silently override worker-specific routes";
            JsonObject stashResources=JsonParser.parseString(resourceRequest(http,api,"GET","/v1/stashes/"+stashId+"/resources",null,TOKEN).body()).getAsJsonObject();
            assert stashResources.get("complete").getAsBoolean() : "Recorded empty containers are known empty";
            String sortPath="/v1/stashes/"+stashId+"/sort-preview?buffers=1&carrySlots=34";
            assert resourceRequest(http,api,"GET",sortPath,null,"wrong").statusCode()==403;
            JsonObject beforeSort=StashCatalog.get(directory,text(savedDefinition,"crew"),text(savedDefinition,"scope"),"Test depot");
            var sortReply=resourceRequest(http,api,"GET",sortPath,null,TOKEN);assert sortReply.statusCode()==200:sortReply.body();
            JsonObject sortPreview=JsonParser.parseString(sortReply.body()).getAsJsonObject();assert flag(sortPreview,"executionSupported")&&sortPreview.getAsJsonObject("summary").get("emptyColumns").getAsInt()==1;
            JsonObject sortControl=op("stash-sort-preview");for(String key:List.of("crew","scope","name"))sortControl.add(key,savedDefinition.get(key).deepCopy());
            sortControl.addProperty("buffers",1);sortControl.addProperty("carrySlots",34);sortControl.addProperty("expected",text(sortPreview,"stashRevision"));assert host.control(sortControl).equals(sortPreview);
            sortControl.addProperty("expected","stale");rejects(()->host.control(sortControl));
            sortControl.remove("expected");sortControl.addProperty("crew","unknown");rejects(()->host.control(sortControl));
            assert resourceRequest(http,api,"GET",sortPath+"&execute=true",null,TOKEN).statusCode()==400;
            assert resourceRequest(http,api,"GET",sortPath+"&categoryIds=unknown",null,TOKEN).statusCode()==400;
            assert resourceRequest(http,api,"GET",sortPath+"&categoryIds=",null,TOKEN).statusCode()==200;
            assert resourceRequest(http,api,"GET",sortPath.replace("buffers=1","buffers=0"),null,TOKEN).statusCode()==400;
            JsonObject afterSort=StashCatalog.get(directory,text(savedDefinition,"crew"),text(savedDefinition,"scope"),"Test depot");assert beforeSort.equals(afterSort):"Preview changed the stash";
            JsonObject sourceBounds=savedDefinition.getAsJsonObject("bounds").deepCopy();sourceBounds.addProperty("name","Kit source");
            JsonObject sourceObserved=observed.deepCopy();sourceObserved.addProperty("block","minecraft:chest");
            sourceObserved.addProperty("occupiedSlots",1);
            JsonObject box=JsonParser.parseString("{slot:0,item:'minecraft:blue_shulker_box',quantity:1,name:'Supplies',contentsKnown:true,items:{'minecraft:obsidian':64}}").getAsJsonObject();
            sourceObserved.add("shulkers",new JsonArray());sourceObserved.getAsJsonArray("shulkers").add(box);sourceObserved.getAsJsonObject("items").addProperty("minecraft:blue_shulker_box",1);sourceObserved.getAsJsonObject("items").addProperty("minecraft:obsidian",64);
            StashCatalog.save(directory,text(savedDefinition,"crew"),text(savedDefinition,"scope"),sourceBounds,sourceObserved);
            var kitStock=resourceRequest(http,api,"GET","/v1/kits/"+StashCatalog.kitTypeId(box)+"/stock",null,TOKEN);
            assert kitStock.statusCode()==200 : kitStock.body();
            JsonObject stockRow=JsonParser.parseString(kitStock.body()).getAsJsonObject().getAsJsonArray("stashes").get(0).getAsJsonObject();
            assert text(stockRow,"name").equals("Kit source") && stockRow.get("complete").getAsInt()==1 : "Authenticated stock route uses the catalog's recorded counts";
            String reservePath="/v1/stashes/"+stashId+"/column-reservations",reserveRevision=text(JsonParser.parseString(resourceRequest(http,api,"GET","/v1/stashes/"+stashId,null,TOKEN).body()).getAsJsonObject(),"revision");
            JsonObject reserve=JsonParser.parseString("{columns:[{x:10,y:116,z:100,axis:'Vertical'}]}").getAsJsonObject();reserve.addProperty("kitTypeId",StashCatalog.kitTypeId(box));
            assert resourceRequest(http,api,"PUT",reservePath,reserve,"wrong").statusCode()==403;
            assert resourceRequest(http,api,"PUT",reservePath,reserve,TOKEN).statusCode()==428;
            String reserveKey=UUID.randomUUID().toString();var reserved=resourceRequest(http,api,"PUT",reservePath,reserve,TOKEN,reserveKey,reserveRevision);
            assert reserved.statusCode()==200 : reserved.body();assert resourceRequest(http,api,"PUT",reservePath,reserve,TOKEN,reserveKey,reserveRevision).body().equals(reserved.body()) : "Reservation retries return the original receipt";
            assert resourceRequest(http,api,"PUT",reservePath,reserve,TOKEN,UUID.randomUUID().toString(),reserveRevision).statusCode()==412;
            JsonObject reservedDb=JsonParser.parseString(resourceRequest(http,api,"GET","/v1/stashes/"+stashId,null,TOKEN).body()).getAsJsonObject();assert reservedDb.getAsJsonObject("columnReservations").getAsJsonObject("10,100").get("manual").getAsBoolean();
            reserve.addProperty("kitTypeId","");assert resourceRequest(http,api,"PUT",reservePath,reserve,TOKEN,UUID.randomUUID().toString(),text(reservedDb,"revision")).statusCode()==200;
            JsonObject scan=new JsonObject();scan.addProperty("id",UUID.randomUUID().toString());scan.add("workerIds",targets.deepCopy());
            var startedScan=resourceRequest(http,api,"POST","/v1/stashes/"+stashId+"/scan",scan,TOKEN);
            assert startedScan.statusCode()==202 : startedScan.body();
            String scanId=text(scan,"id");
            await(()->state(host,UUID.fromString(scanId)).equals("Running"),worker);
            JsonObject scanRun=taskView(host,UUID.fromString(scanId)).getAsJsonObject("runs").getAsJsonObject(workerId.toString());
            assert text(scanRun.getAsJsonObject("action"),"type").equals("StashScan") : "Catalog scan dispatches the existing worker action";
            assert resourceRequest(http,api,"POST","/v1/jobs/"+scanId+"/cancel",new JsonObject(),TOKEN).statusCode()==202;
            await(()->worker.current.isEmpty()&&!flag(taskView(host,UUID.fromString(scanId)),"cleanupPending"),worker);
            JsonObject missingHome=stashDefinition.deepCopy();missingHome.addProperty("name","Missing home");missingHome.getAsJsonObject("bounds").remove("homeName");
            assert resourceRequest(http,api,"POST","/v1/stashes",missingHome,TOKEN).statusCode()==400 : "Stash definitions require /home routing";
            JsonObject draft=new JsonObject();draft.addProperty("id",UUID.randomUUID().toString());draft.addProperty("name","Draft wait");
            draft.addProperty("server","test.invalid");draft.addProperty("dimension","minecraft:the_nether");draft.addProperty("priority",0);
            draft.add("args",new JsonObject());draft.add("package",savedWorkflow.get("package").deepCopy());
            var savedDraft=resourceRequest(http,api,"POST","/v1/drafts",draft,TOKEN);
            assert savedDraft.statusCode()==201 : savedDraft.body();
            String draftId=text(draft,"id");
            assert resourceRequest(http,api,"GET","/v1/drafts/"+draftId,null,TOKEN).statusCode()==200;
            assert JsonParser.parseString(resourceRequest(http,api,"GET","/v1/drafts",null,TOKEN).body()).getAsJsonObject().getAsJsonArray("items").size()==1;
            JsonObject dispatch=new JsonObject();dispatch.addProperty("crewId",crewId);dispatch.add("workerIds",targets.deepCopy());
            var dispatched=resourceRequest(http,api,"POST","/v1/drafts/"+draftId+"/dispatch",dispatch,TOKEN);
            assert dispatched.statusCode()==202 && text(JsonParser.parseString(dispatched.body()).getAsJsonObject(),"id").equals(draftId) : dispatched.body();
            assert resourceRequest(http,api,"GET","/v1/drafts/"+draftId,null,TOKEN).statusCode()==404;
            assert resourceRequest(http,api,"POST","/v1/jobs/"+draftId+"/cancel",new JsonObject(),TOKEN).statusCode()==202;
            await(()->worker.current.isEmpty()&&!flag(taskView(host,UUID.fromString(draftId)),"cleanupPending"),worker);
            JsonObject createCrew=new JsonObject();createCrew.addProperty("id",UUID.randomUUID().toString());createCrew.addProperty("name","Resource API crew");
            var createdCrew=resourceRequest(http,api,"POST","/v1/crews",createCrew,TOKEN);
            assert createdCrew.statusCode()==201 : createdCrew.body();
            String newCrewId=text(JsonParser.parseString(createdCrew.body()).getAsJsonObject(),"id");
            JsonObject rename=new JsonObject();rename.addProperty("name","Renamed crew");
            String revision=text(JsonParser.parseString(createdCrew.body()).getAsJsonObject(),"revision");
            assert text(JsonParser.parseString(resourceRequest(http,api,"PATCH","/v1/crews/"+newCrewId,rename,TOKEN,UUID.randomUUID().toString(),revision).body()).getAsJsonObject(),"name").equals("Renamed crew");
            assert resourceRequest(http,api,"PATCH","/v1/crews/"+newCrewId,rename,TOKEN,UUID.randomUUID().toString(),revision).statusCode()==412 : "Stale edit must not overwrite another operator";
            assert resourceRequest(http,api,"PATCH","/v1/crews/"+newCrewId,rename,TOKEN).statusCode()==428 : "Edits need an explicit revision";
            JsonObject crews=JsonParser.parseString(resourceRequest(http,api,"GET","/v1/crews?limit=1",null,TOKEN).body()).getAsJsonObject();
            assert crews.getAsJsonArray("items").size()==1 && crews.has("nextCursor");
            assert resourceRequest(http,api,"GET","/v1/crews?limit=1&cursor="+text(crews,"nextCursor"),null,TOKEN).statusCode()==200;
            worker.close();await(()->connected(host)==0 && eventSeen(host,"worker-disconnected",workerId));
            assert resourceRequest(http,api,"DELETE","/v1/workers/"+workerId,null,TOKEN).statusCode()==200;
            assert resourceRequest(http,api,"GET","/v1/workers/"+workerId,null,TOKEN).statusCode()==404;
            for(int i=0;i<130;i++)host.control(op("configuration-controls"));
            var gap=resourceRequest(http,api,"GET","/v1/events?cursor="+eventCursor,null,TOKEN);
            assert gap.statusCode()==409 && JsonParser.parseString(gap.body()).getAsJsonObject().get("snapshotRequired").getAsBoolean() : "Expired cursors require a fresh snapshot";
        }
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            ControlApi api=new ControlApi(host,0,TOKEN);
            HttpClient http=HttpClient.newHttpClient()) {
            JsonObject operation=JsonParser.parseString(resourceRequest(http,api,"GET","/v1/operations/"+workflowCommandId,null,TOKEN).body()).getAsJsonObject();
            assert text(operation,"state").equals("completed") && operation.get("httpStatus").getAsInt()==201 : "Command receipt survives host restart";
        }
    }
    private static JsonObject inspectJobConfiguration(String id) {JsonObject request=op("task-configuration");request.addProperty("id",id);return request;}
    @SuppressWarnings("unchecked")
    private static void rollingHistoryCheck() throws Exception {
        Path directory = Files.createTempDirectory("monocle-history-check-");
        UUID workerId = UUID.randomUUID();
        try (HostService host = new HostService(directory, "127.0.0.1", 0, CREWS, 30);
             Worker worker = new Worker(host.port(), KEY, workerId, new LinkedHashMap<>())) {
            await(() -> connected(host) == 1, worker);
            JsonObject request = submit(workerId, 0); host.control(request);
            UUID active = UUID.fromString(text(request, "id")), oldest = UUID.randomUUID();
            JsonObject pause = op("pause"); pause.addProperty("id", active.toString()); host.control(pause);
            var field = HostService.class.getDeclaredField("tasks"); field.setAccessible(true);
            synchronized (host) {
                Map<UUID, JsonObject> tasks = (Map<UUID, JsonObject>) field.get(host);
                JsonObject template = tasks.get(active).deepCopy();
                for (int i = 0; i < 63; i++) {
                    UUID id = i == 62 ? oldest : UUID.randomUUID();
                    JsonObject history = template.deepCopy(); history.addProperty("id", id.toString());
                    history.addProperty("status", "Cancelled"); history.addProperty("finishedAt", i == 62 ? 1L : System.currentTimeMillis());
                    history.getAsJsonObject("runs").entrySet().forEach(e -> e.getValue().getAsJsonObject().addProperty("status", "Cancelled"));
                    tasks.put(id, history);
                }
                JsonObject invalid = submit(workerId, 0); invalid.addProperty("name", "");
                rejects(() -> host.control(invalid));
                assert tasks.size() == 64 && tasks.containsKey(oldest) : "Rejected submissions must preserve history";
                host.control(submit(workerId, 0));
                assert tasks.size() == 64 && !tasks.containsKey(oldest) && tasks.containsKey(active)
                    : "Rolling history removes the oldest finished record, protecting active jobs";
                tasks.values().forEach(t -> t.addProperty("status", "Paused"));
                rejects(() -> host.control(submit(workerId, 0)));
                assert tasks.size() == 64 : "A full active queue must never be evicted";
            }
        }
    }
    private static void knownHostilesCheck() throws Exception {
        Path directory=Files.createTempDirectory("monocle-hostiles-check-");
        UUID workerId=UUID.randomUUID(), otherId=UUID.randomUUID(), attacker=UUID.randomUUID();
        JsonObject evidence=new JsonObject(),damage=new JsonObject(),actor=new JsonObject();
        evidence.addProperty("version",1);evidence.addProperty("sequence",2);
        damage.addProperty("attribution","server_reported");damage.addProperty("scope","test.invalid\nminecraft:the_nether");damage.addProperty("at",1001);
        actor.addProperty("uuid",attacker.toString());actor.addProperty("name","Enemy");actor.addProperty("player",true);damage.add("attacker",actor);evidence.add("lastDamage",damage);
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            Worker worker=new Worker(host.port(),KEY,workerId,new LinkedHashMap<>());
            Worker other=new Worker(host.port(),OTHER_KEY,otherId,new LinkedHashMap<>())) {
            worker.hostilesSupported=true;worker.safeReconnectSupported=true;worker.announce();
            await(()->worker.hostileCatalog!=null&&connected(host)==2,worker,other);
            JsonObject add=op("hostile-add");add.addProperty("server","TEST.invalid:25565");add.addProperty("name","Manual");host.control(add);
            await(()->worker.hostileCatalog.getAsJsonArray("players").size()==1,worker,other);
            assert other.hostileFrames==0 : "Older clients must never receive an unknown frame";
            other.hostilesSupported=true;other.announce();
            await(()->other.hostileCatalog!=null&&other.hostileCatalog.getAsJsonArray("players").size()==1,worker,other);
            assert worker.hostileCatalog.equals(other.hostileCatalog) : "Shared across authenticated crews on this host";
            JsonObject policy=op("hostile-policy");policy.addProperty("enabled",true);policy.addProperty("safeReconnect",true);policy.addProperty("reconnectSeconds",300);host.control(policy);
            await(()->worker.hostileCatalog.get("enabled").getAsBoolean()&&other.hostileCatalog.get("enabled").getAsBoolean(),worker,other);
            assert worker.hostileCatalog.get("safeReconnect").getAsBoolean()&&worker.hostileCatalog.get("reconnectSeconds").getAsInt()==300;
            policy.addProperty("reconnectSeconds",0);rejects(()->host.control(policy));policy.addProperty("reconnectSeconds",300);
            policy.addProperty("enabled","true");rejects(()->host.control(policy));
            worker.combatOverride=evidence;worker.announce();
            await(()->worker.hostileCatalog.getAsJsonArray("players").size()==2&&other.hostileCatalog.getAsJsonArray("players").size()==2,worker,other);
            JsonObject enemy=worker.hostileCatalog.getAsJsonArray("players").get(1).getAsJsonObject();
            assert text(enemy,"uuid").equals(attacker.toString())&&text(enemy,"source").equals("attack");
            var incident=ResourceApi.route(host,"GET",java.net.URI.create("/v1/incidents?kind=damage&limit=1&search=Enemy"),null,null);
            assert incident.code()==200&&incident.body().getAsJsonArray("items").size()==1;
            JsonObject sighting=JsonParser.parseString("{id:'"+UUID.randomUUID()+"',at:1002,scope:'test.invalid\\nminecraft:the_nether',trusted:false,player:{uuid:'"+UUID.randomUUID()+"',name:'Visitor',x:1,y:116,z:3}}").getAsJsonObject();
            worker.playerSightings=new JsonArray();worker.playerSightings.add(sighting);worker.announce();
            await(()->host.incidentPage(null,100,"sighting","").getAsJsonArray("items").size()==1,worker,other);
            assert host.incidentPage(null,100,"sighting","").getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("evidence").get("classification").getAsString().equals("neutral");
            worker.announce();
            JsonObject remove=op("hostile-remove");remove.add("id",enemy.get("id").deepCopy());host.control(remove);
            await(()->worker.hostileCatalog.getAsJsonArray("players").size()==1&&other.hostileCatalog.getAsJsonArray("players").size()==1,worker,other);
            worker.announce();
            // The next frame is a barrier: this acknowledgement proves the replayed hello was processed.
            policy.addProperty("enabled",false);host.control(policy);worker.announce();
            await(()->!worker.hostileCatalog.get("enabled").getAsBoolean(),worker,other);
            assert host.control(op("status")).getAsJsonObject("knownHostiles").getAsJsonArray("players").size()==1;
        }
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            Worker worker=new Worker(host.port(),KEY,workerId,new LinkedHashMap<>())) {
            worker.hostilesSupported=true;worker.combatOverride=evidence;worker.announce();
            await(()->worker.hostileCatalog!=null,worker);
            assert worker.hostileCatalog.getAsJsonArray("players").size()==1 : "Forgiveness persists through worker reconnect and host restart";
            damage.addProperty("at",1002);evidence.addProperty("sequence",3);worker.announce();
            await(()->worker.hostileCatalog.getAsJsonArray("players").size()==2,worker);
        }
        System.out.println("Known-hostile socket checks passed: capability gating, cross-crew sync, manual controls, server-attributed learning, durable forgiveness and reconnect.");
    }
    private static void hostWideWarningsCheck() throws Exception {
        Path directory=Files.createTempDirectory("monocle-host-wide-");UUID enemy=UUID.randomUUID();String scope="test.invalid\nminecraft:the_nether";
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            Worker observer=new Worker(host.port(),KEY,UUID.randomUUID(),new LinkedHashMap<>());
            Worker other=new Worker(host.port(),OTHER_KEY,UUID.randomUUID(),new LinkedHashMap<>());
            Worker legacy=new Worker(host.port(),KEY,UUID.randomUUID(),new LinkedHashMap<>())) {
            observer.hostilesSupported=other.hostilesSupported=legacy.hostilesSupported=true;
            observer.hostileWarningsSupported=other.hostileWarningsSupported=true;
            JsonObject player=JsonParser.parseString("{uuid:'"+enemy+"',name:'Enemy',trusted:false,x:1,y:116,z:3}").getAsJsonObject();
            JsonArray players=new JsonArray();players.add(player);PlayerSightings view=new PlayerSightings();view.observe(scope,players,1000);
            observer.playerChecks=view.checks(1000);observer.inLobby=true;observer.playerSightings=view.snapshot();
            JsonObject combat=new JsonObject(),damage=new JsonObject(),attacker=new JsonObject();
            attacker.addProperty("uuid",enemy.toString());attacker.addProperty("name","Enemy");attacker.addProperty("player",true);
            damage.add("attacker",attacker);damage.addProperty("attribution","server_reported");damage.addProperty("scope",scope);damage.addProperty("at",9999);combat.add("lastDamage",damage);observer.combatOverride=combat;
            other.announce();legacy.announce();observer.announce();
            await(()->observer.playerCheckReceipts>0&&other.hostileCatalog!=null&&legacy.hostileCatalog!=null,observer,other,legacy);
            assert host.control(op("status")).getAsJsonObject("knownHostiles").getAsJsonArray("players").isEmpty():"Lobby damage cannot learn hostiles";
            assert host.incidentPage(null,100,"sighting","").getAsJsonArray("items").isEmpty();
            assert host.incidentPage(null,100,"damage","Enemy").getAsJsonArray("items").isEmpty():"Lobby events cannot enter incident logs";
            JsonObject add=op("hostile-add");add.addProperty("server","test.invalid");add.addProperty("name","Enemy");add.addProperty("uuid",enemy.toString());
            JsonObject entry=host.control(add).getAsJsonArray("players").get(0).getAsJsonObject();
            observer.combatOverride=null;observer.announce();
            await(()->observer.hostileCatalog.getAsJsonArray("players").size()==1&&other.hostileCatalog.getAsJsonArray("players").size()==1,observer,other,legacy);
            assert observer.hostileWarnings.isEmpty()&&other.hostileWarnings.isEmpty():"Even a known hostile in a lobby produces no alarm";
            observer.inLobby=false;observer.announce();
            await(()->observer.hostileWarnings.size()==1&&other.hostileWarnings.size()==1,observer,other,legacy);
            assert !observer.hostileCatalog.get("enabled").getAsBoolean():"Guard off still warns both crews";
            assert legacy.hostileWarnings.isEmpty()&&legacy.playerCheckReceipts==0:"Old clients receive no unknown frames";
            assert observer.hostileWarnings.getFirst().getAsJsonObject("warning").equals(other.hostileWarnings.getFirst().getAsJsonObject("warning"));
            JsonObject status=host.control(op("status"));assert status.getAsJsonArray("hostileWarnings").size()==1;
            assert status.getAsJsonArray("workers").asList().stream().filter(w->w.getAsJsonObject().get("hostileWarningsSupported").getAsBoolean()).count()==2;
            int receipts=observer.playerCheckReceipts;observer.announce();await(()->observer.playerCheckReceipts>receipts,observer,other,legacy);
            assert observer.hostileWarnings.size()==1&&other.hostileWarnings.size()==1:"Standing in view never spams alarms";
            JsonObject arm=op("hostile-policy");arm.addProperty("enabled",true);host.control(arm);
            await(()->observer.hostileWarnings.size()==2&&other.hostileWarnings.size()==2,observer,other,legacy);
            assert observer.hostileWarnings.get(0).getAsJsonObject("warning").get("id").equals(observer.hostileWarnings.get(1).getAsJsonObject("warning").get("id")):"Arming reissues the same live alarm";
            try(Worker joined=new Worker(host.port(),OTHER_KEY,UUID.randomUUID(),new LinkedHashMap<>())){
                joined.hostilesSupported=joined.hostileWarningsSupported=true;joined.announce();await(()->joined.hostileWarnings.size()==1,observer,other,legacy,joined);
            }
            JsonObject remove=op("hostile-remove");remove.add("id",entry.get("id").deepCopy());host.control(remove);
            assert host.control(op("status")).getAsJsonArray("hostileWarnings").isEmpty():"Forgiveness removes the live warning";
        }
        System.out.println("Host-wide socket checks passed: negative lookups, lobby incident/learning exclusion, guard-off cross-crew warnings, old-client gating, dedup, active reconnect and forgiveness.");
    }
    static JsonObject op(String op) { JsonObject request = new JsonObject(); request.addProperty("op", op); return request; }
    private static void stashMultiTransferCheck() throws Exception {
        Path dir=Files.createTempDirectory("monocle-host-multi-kits-");UUID workerId=UUID.randomUUID();String scope="test.invalid\nminecraft:the_nether";
        JsonObject source=StashCatalog.plan(JsonParser.parseString("{name:'Source',homeName:'source',minX:0,maxX:0,minY:116,maxY:116,minZ:0,maxZ:0,scanMode:'Full',lazyMode:false}").getAsJsonObject());
        JsonObject destination=source.deepCopy();destination.addProperty("name","Destination");destination.addProperty("homeName","destination");destination.addProperty("minX",100);destination.addProperty("maxX",102);
        StashCatalog.define(dir,"Default",scope,source);StashCatalog.define(dir,"Default",scope,destination);
        JsonObject observed=JsonParser.parseString("{x:0,y:116,z:0,status:'observed',block:'minecraft:chest',items:{'minecraft:blue_shulker_box':1,'minecraft:obsidian':1728},shulkers:[{slot:0,item:'minecraft:blue_shulker_box',quantity:1,name:'Alpha',contentsKnown:true,items:{'minecraft:obsidian':1728}}]}").getAsJsonObject();
        StashCatalog.save(dir,"Default",scope,source,observed);
        try(HostService host=new HostService(dir,"127.0.0.1",0,CREWS,30);Worker worker=new Worker(host.port(),KEY,workerId,new LinkedHashMap<>())){
            await(()->connected(host)==1,worker);
            JsonObject args=JsonParser.parseString("{name:'Source',targetStashName:'Destination',count:36,depositCarried:true}").getAsJsonObject();
            JsonObject prepare=op("workflow-prepare");prepare.addProperty("id","task-stash-transfer");prepare.addProperty("scope",scope);prepare.add("args",args);
            JsonObject job=submit(workerId,0);job.addProperty("name","All stash kits");job.add("args",args);job.add("package",host.control(prepare));host.control(job);
            UUID taskId=UUID.fromString(text(job,"id"));await(()->worker.lastKitPlan!=null,worker);
            assert flag(worker.lastKitPlan,"success")&&text(worker.lastKitPlan.getAsJsonObject("result").getAsJsonObject("args"),"kitTypeId").equals(StashCatalog.kitTypeId(observed.getAsJsonArray("shulkers").get(0).getAsJsonObject()));
            assert flag(worker.lastKitPlan.getAsJsonObject("result").getAsJsonObject("args"),"depositCarried")&&text(worker.lastKitPlan.getAsJsonObject("result").getAsJsonObject("args").getAsJsonObject("targetStash"),"name").equals("Destination");
            assert taskView(host,taskId).getAsJsonObject("kitJob").getAsJsonArray("kitTypeIds").size()==1 : "Status retains the frozen multi-kit selection";
            JsonObject taskGet=op("task-get");taskGet.addProperty("id",taskId.toString());JsonObject saved=host.control(taskGet);
            assert saved.getAsJsonArray("supportedActions").asList().stream().map(JsonElement::getAsString).collect(java.util.stream.Collectors.toSet()).equals(Set.of("Wait","KitTransferPlan","StashResupply","StashDeposit")) : "Advertise only transfer actions; 0.13.36 workers reject more than eleven host capabilities";
            JsonObject savedRun=saved.getAsJsonObject("runs").getAsJsonObject(workerId.toString());assert savedRun.has("kitPlanToken")&&savedRun.has("kitPlan") : "Planning replies are durable and idempotent";
            JsonObject cancel=op("cancel");cancel.addProperty("id",taskId.toString());host.control(cancel);await(()->worker.current.isEmpty()&&state(host,taskId).equals("Cancelled"),worker);
            JsonObject many=job.deepCopy();many.addProperty("id",UUID.randomUUID().toString());many.getAsJsonArray("workers").add(UUID.randomUUID().toString());rejects(()->host.control(many));
            JsonObject alternate=source.deepCopy();alternate.addProperty("name","Alternate");alternate.addProperty("homeName","alternate");alternate.addProperty("minX",200);alternate.addProperty("maxX",200);StashCatalog.define(dir,"Default",scope,alternate);
            JsonObject alternateContents=observed.deepCopy();alternateContents.addProperty("x",200);StashCatalog.save(dir,"Default",scope,alternate,alternateContents);
            String kit=StashCatalog.kitTypeId(observed.getAsJsonArray("shulkers").get(0).getAsJsonObject());
            JsonObject roundArgs=JsonParser.parseString("{count:36,depositCarried:true,routes:[{name:'Source',kitTypeId:'"+kit+"',targetStashName:'Destination'},{name:'Alternate',kitTypeId:'"+kit+"',targetStashName:'Destination'}]}").getAsJsonObject();
            prepare.add("args",roundArgs);JsonObject round=submit(workerId,0);round.add("args",roundArgs);round.add("package",host.control(prepare));worker.lastKitPlan=null;host.control(round);
            UUID roundId=UUID.fromString(text(round,"id"));await(()->worker.lastKitPlan!=null,worker);
            assert flag(worker.lastKitPlan,"success")&&flag(worker.lastKitPlan.getAsJsonObject("result").getAsJsonObject("args"),"oneBatch");
            JsonObject roundView=taskView(host,roundId);assert roundView.getAsJsonObject("kitJob").getAsJsonArray("routes").size()==2&&!roundView.getAsJsonObject("runs").getAsJsonObject(workerId.toString()).has("kitRoutePlans");
            taskGet.addProperty("id",roundId.toString());assert host.control(taskGet).getAsJsonObject("runs").getAsJsonObject(workerId.toString()).getAsJsonObject("kitRoutePlans").size()==1 : "The first source manifest is persisted before its reply, not sent in polling summaries";
            cancel.addProperty("id",roundId.toString());host.control(cancel);await(()->worker.current.isEmpty()&&state(host,roundId).equals("Cancelled"),worker);
        }
    }
    private static void stashTeamTransferCheck() throws Exception {
        Path dir=Files.createTempDirectory("monocle-host-team-kits-");String scope="test.invalid\nminecraft:the_nether";
        JsonObject source=StashCatalog.plan(JsonParser.parseString("{name:'Source',homeName:'source',minX:10000,maxX:10004,minY:116,maxY:116,minZ:0,maxZ:0,scanMode:'Full',lazyMode:false}").getAsJsonObject());
        JsonObject destination=source.deepCopy();destination.addProperty("name","Destination");destination.addProperty("homeName","destination");destination.addProperty("minX",20000);destination.addProperty("maxX",20004);
        StashCatalog.define(dir,"Default",scope,destination);
        JsonObject observed=JsonParser.parseString("{x:10000,y:116,z:0,status:'observed',block:'minecraft:chest',items:{'minecraft:blue_shulker_box':1,'minecraft:obsidian':1728},shulkers:[{slot:0,item:'minecraft:blue_shulker_box',quantity:1,name:'Alpha',contentsKnown:true,items:{'minecraft:obsidian':1728}}]}").getAsJsonObject();
        for(int x=10000;x<=10004;x+=2){observed.addProperty("x",x);StashCatalog.save(dir,"Default",scope,source,observed);}
        try(HostService host=new HostService(dir,"127.0.0.1",0,CREWS,30);Worker leader=new Worker(host.port(),KEY,UUID.randomUUID(),new LinkedHashMap<>());Worker second=new Worker(host.port(),KEY,UUID.randomUUID(),new LinkedHashMap<>());Worker third=new Worker(host.port(),KEY,UUID.randomUUID(),new LinkedHashMap<>())){
            leader.name="Leader";second.name="Second";third.name="Third";leader.announce();second.announce();third.announce();await(()->connected(host)==3,leader,second,third);
            JsonObject args=JsonParser.parseString("{name:'Source',targetStashName:'Destination',count:36}").getAsJsonObject();args.addProperty("leader",leader.id.toString());
            JsonObject prepare=op("workflow-prepare");prepare.addProperty("id",StashTransferTeam.WORKFLOW);prepare.addProperty("scope",scope);prepare.add("args",args);
            JsonObject job=submit(leader.id,0);job.addProperty("name","Three-worker stash transfer");job.add("args",args);job.add("package",host.control(prepare));job.getAsJsonArray("workers").add(second.id.toString());job.getAsJsonArray("workers").add(third.id.toString());host.control(job);
            UUID taskId=UUID.fromString(text(job,"id"));await(()->leader.lastKitPlan!=null&&second.resumes>0&&third.resumes>0,leader,second,third);
            assert flag(leader.lastKitPlan,"success")&&text(leader.lastKitPlan.getAsJsonObject("result").getAsJsonObject("action"),"type").equals("StashVisit");assert second.lastKitPlan==null&&third.lastKitPlan==null : "TPA waits for actual home send, not the plan";
            JsonObject nativeRun=leader.checkpoints.values().iterator().next();nativeRun.add("action",leader.lastKitPlan.getAsJsonObject("result").get("action").deepCopy());nativeRun.addProperty("token",UUID.randomUUID().toString());nativeRun.add("stashTravel",JsonParser.parseString("{name:'Source',homeSent:true,arrived:false}"));leader.sendStatus(nativeRun);
            await(()->second.lastKitPlan!=null&&third.lastKitPlan!=null,leader,second,third);
            for(Worker follower:List.of(second,third)){
                JsonObject tpa=follower.lastKitPlan.getAsJsonObject("result").getAsJsonObject("action");assert text(tpa,"type").equals("Tpa")&&text(tpa,"target").equals(leader.id.toString());
                JsonObject r=follower.checkpoints.values().iterator().next();r.add("action",tpa.deepCopy());r.addProperty("token",UUID.randomUUID().toString());follower.sendStatus(r);
            }
            await(()->second.tpaSends.size()==1&&third.tpaSends.size()==1&&leader.tpaAccepts.size()==2,leader,second,third);
            for(Worker follower:List.of(second,third)){JsonObject r=follower.checkpoints.values().iterator().next();r.addProperty("token",UUID.randomUUID().toString());follower.sendStatus(r);}
            await(()->second.tpaSends.size()==2&&third.tpaSends.size()==2&&leader.tpaAccepts.size()==4,leader,second,third);
            assert text(second.tpaSends.getFirst(),"name").equals("Leader")&&text(third.tpaSends.getFirst(),"name").equals("Leader") : "Both followers request while the leader's home warmup is still active";
            JsonObject view=taskView(host,taskId);assert view.has("crewTransfer")&&!view.has("stashTeam")&&view.getAsJsonObject("kitJob").get("delivered").getAsInt()==0;
            JsonObject cancel=op("cancel");cancel.addProperty("id",taskId.toString());host.control(cancel);await(()->state(host,taskId).equals("Cancelled"),leader,second,third);
        }
        try(HostService restarted=new HostService(dir,"127.0.0.1",0,CREWS,30)){assert restarted.control(op("status")).getAsJsonArray("history").get(0).getAsJsonObject().getAsJsonObject("crewTransfer").getAsJsonObject("members").size()==3 : "The crew barrier survives coordinator restart";}
        System.out.println("Crew transfer socket checks passed: three installs, home-send release, two immediate TPAs/accepts, compact telemetry, cancellation and restart.");
    }
    private static void stashScanCheck() throws Exception {
        Path dir=Files.createTempDirectory("monocle-host-stash-check-");UUID workerId=UUID.randomUUID();
        try(HostService host=new HostService(dir,"127.0.0.1",0,CREWS,30);Worker worker=new Worker(host.port(),KEY,workerId,new LinkedHashMap<>())){
            await(()->host.control(op("status")).getAsJsonArray("workers").size()==1,worker);
            JsonObject definition=message("stash-definition");definition.addProperty("scope","test.invalid\nminecraft:the_nether");definition.add("stash",JsonParser.parseString("{\"name\":\"Depot\",\"homeName\":\"depot\",\"minX\":0,\"maxX\":1,\"minY\":116,\"maxY\":116,\"minZ\":0,\"maxZ\":0}").getAsJsonObject());worker.c.send(definition.toString());
            await(()->host.control(op("status")).getAsJsonArray("stashes").size()==1,worker);
            JsonObject imported=message("stash-import");imported.addProperty("scope","test.invalid\nminecraft:the_nether");imported.add("stash",definition.get("stash").deepCopy());imported.add("observation",JsonParser.parseString("{\"x\":1,\"y\":116,\"z\":0,\"status\":\"observed\",\"block\":\"minecraft:chest\",\"reason\":\"\",\"items\":{\"minecraft:obsidian\":64},\"shulkers\":[]}").getAsJsonObject());worker.c.send(imported.toString());
            await(()->host.control(op("status")).getAsJsonArray("stashes").get(0).getAsJsonObject().getAsJsonObject("items").has("minecraft:obsidian"),worker);
            imported.addProperty("exportId",UUID.randomUUID().toString());worker.c.send(imported.toString());await(()->worker.exportAcks.size()==1,worker);
            assert text(worker.exportAcks.getLast(),"outcome").equals("accepted");
            JsonObject newer=imported.getAsJsonObject("observation").deepCopy();newer.getAsJsonObject("items").addProperty("minecraft:obsidian",128);
            StashCatalog.record(dir,"Default",text(imported,"scope"),definition.getAsJsonObject("stash"),newer);
            worker.c.send(imported.toString());await(()->worker.exportAcks.size()==2,worker);
            assert StashCatalog.get(dir,"Default",text(imported,"scope"),"Depot").getAsJsonObject("containers").getAsJsonObject("1,116,0").getAsJsonObject("items").get("minecraft:obsidian").getAsInt()==128 : "A socket retry cannot replay an already-receipted read";
            StashCatalog.record(dir,"Default",text(imported,"scope"),definition.getAsJsonObject("stash"),imported.getAsJsonObject("observation"));
            JsonObject wrongWorld=imported.deepCopy();wrongWorld.addProperty("scope","test.invalid\nminecraft:the_end");wrongWorld.addProperty("exportId",UUID.randomUUID().toString());worker.c.send(wrongWorld.toString());await(()->worker.exportAcks.size()==3,worker);
            assert text(worker.exportAcks.getLast(),"outcome").equals("deferred")&&worker.c.connected() : "World-report races defer exports without disconnecting workers";
            JsonObject stale=definition.deepCopy();stale.addProperty("exportId",UUID.randomUUID().toString());stale.addProperty("definitionRevision","stale");stale.getAsJsonObject("stash").addProperty("homeName","old-home");worker.c.send(stale.toString());await(()->worker.exportAcks.size()==4,worker);
            assert text(worker.exportAcks.getLast(),"outcome").equals("conflict")&&worker.c.connected();
            try(ControlApi api=new ControlApi(host,0,TOKEN);HttpClient http=HttpClient.newHttpClient()) {
                JsonObject catalog=JsonParser.parseString(resourceRequest(http,api,"GET","/v1/stashes",null,TOKEN).body()).getAsJsonObject();
                String stashId=text(catalog.getAsJsonArray("items").get(0).getAsJsonObject(),"id");
                JsonObject detail=JsonParser.parseString(resourceRequest(http,api,"GET","/v1/stashes/"+stashId,null,TOKEN).body()).getAsJsonObject();
                assert text(detail,"id").equals(stashId) && detail.getAsJsonObject("containers").size()==1
                    : "Stash inspection resolves the listed resource ID and exposes observed containers";
                JsonObject resources=JsonParser.parseString(resourceRequest(http,api,"GET","/v1/stashes/"+stashId+"/resources",null,TOKEN).body()).getAsJsonObject();
                assert resources.getAsJsonObject("items").get("minecraft:obsidian").getAsInt()==64 && resources.has("observedAt");
            }
            JsonObject request=submit(workerId,0);UUID task=UUID.fromString(text(request,"id"));request.addProperty("name","Inspect depot");request.addProperty("script","return function(ctx) return bot.stash_scan(ctx.args) end");
            request.add("args",JsonParser.parseString("{\"name\":\"Depot\",\"homeName\":\"depot\",\"minX\":0,\"maxX\":1,\"minY\":116,\"maxY\":116,\"minZ\":0,\"maxZ\":0}").getAsJsonObject());host.control(request);
            await(()->state(host,task).equals("Running"),worker);
            Set<String> transferEvents=new HashSet<>();for(JsonElement event:host.control(op("status")).getAsJsonArray("activity"))transferEvents.add(text(event.getAsJsonObject(),"event"));
            assert transferEvents.containsAll(Set.of("worker-transfer-begin","worker-transfer-queued")) : "Package transfer stages must be visible before diagnosing worker movement";
            JsonObject run=worker.checkpoints.values().iterator().next();
            JsonObject observation=JsonParser.parseString("{\"x\":0,\"y\":116,\"z\":0,\"status\":\"observed\",\"block\":\"minecraft:chest\",\"reason\":\"\",\"items\":{\"minecraft:stone\":1728},\"shulkers\":[]}").getAsJsonObject();
            JsonObject message=message("stash-findings");message.addProperty("run",text(run,"run"));message.addProperty("task",task.toString());message.add("action",run.get("action").deepCopy());message.add("token",run.get("token"));message.addProperty("delivery",1);message.add("observation",observation);
            worker.c.send(message.toString());await(()->worker.stashAcks==1,worker);
            JsonObject scanGet=op("task-get");scanGet.addProperty("id",task.toString());
            JsonObject scanRun=host.control(scanGet).getAsJsonObject("runs").getAsJsonObject(workerId.toString());
            assert scanRun.has("stashRecord")&&text(scanRun.getAsJsonObject("stashRecord"),"basis").matches("[a-f0-9]{64}")
                : "Scan receipts retain the committed watch revision without rereading the catalog";
            worker.c.send(message.toString());await(()->worker.stashAcks==2,worker);
            long observedEvents=host.control(op("status")).getAsJsonArray("activity").asList().stream()
                .map(JsonElement::getAsJsonObject).filter(e->text(e,"event").equals("stash-container-observed")&&text(e,"runOrTask").equals(task.toString())).count();
            assert observedEvents==1 : "Duplicate findings re-acknowledge without counting another container read";
            for(int sequence=2;sequence<=9;sequence++){JsonObject next=message.deepCopy();next.addProperty("delivery",sequence);worker.c.send(next.toString());}
            await(()->worker.stashAcks==10,worker);
            JsonObject persisted=TaskFiles.read(dir.resolve("host-tasks.json")).getAsJsonObject("tasks").getAsJsonObject(task.toString()).getAsJsonObject("runs").getAsJsonObject(workerId.toString());
            assert persisted.get("stashDelivery").getAsInt()==9 : "A streamed scan window is committed before any acknowledgement releases the worker's durable outbox";
            JsonObject stash=host.control(op("status")).getAsJsonArray("stashes").get(0).getAsJsonObject();assert stash.getAsJsonObject("items").get("minecraft:stone").getAsInt()==1728 : "Duplicate delivery is acknowledged, not double-counted";
            JsonObject inspect=op("stash-get");inspect.addProperty("crew","Default");inspect.addProperty("scope","test.invalid\nminecraft:the_nether");inspect.addProperty("name","Depot");assert host.control(inspect).getAsJsonObject("containers").size()==2;
            JsonObject cancel=op("cancel");cancel.addProperty("id",task.toString());host.control(cancel);await(()->worker.current.isEmpty()&&state(host,task).equals("Cancelled"),worker);
            JsonObject box=JsonParser.parseString("{slot:0,item:'minecraft:purple_shulker_box',quantity:1,name:'Raid kit',contentsKnown:true,items:{'minecraft:obsidian':64}}").getAsJsonObject();
            JsonObject kitObservation=observation.deepCopy();kitObservation.addProperty("x",1);kitObservation.getAsJsonArray("shulkers").add(box);
            StashCatalog.save(dir,"Default","test.invalid\nminecraft:the_nether",definition.getAsJsonObject("stash"),kitObservation);
            JsonObject args=definition.getAsJsonObject("stash").deepCopy();args.addProperty("kitTypeId",StashCatalog.kitTypeId(box));args.addProperty("count",1);args.addProperty("destination","Carry");
            JsonObject prepare=op("workflow-prepare");prepare.addProperty("id","task-kit-delivery");prepare.addProperty("scope","test.invalid\nminecraft:the_nether");prepare.add("args",args);
            JsonObject delivery=submit(workerId,0);delivery.remove("script");delivery.addProperty("name","Deliver raid kit");delivery.add("args",args);delivery.add("package",host.control(prepare));host.control(delivery);
            JsonObject kitView=taskView(host,UUID.fromString(text(delivery,"id"))).getAsJsonObject("kitJob");
            assert text(kitView,"kitTypeId").equals(StashCatalog.kitTypeId(box))&&kitView.get("count").getAsInt()==1&&text(kitView,"name").equals("Depot") : "Kit usage metadata survives the redacted status view";
            await(()->state(host,UUID.fromString(text(delivery,"id"))).equals("Running"),worker);
            assert worker.checkpoints.get(UUID.fromString(worker.current)).getAsJsonObject("action").has("picks") : "Kit-delivery presets still prepare source withdrawals";
            cancel.addProperty("id",text(delivery,"id"));host.control(cancel);await(()->worker.current.isEmpty()&&state(host,UUID.fromString(text(delivery,"id"))).equals("Cancelled"),worker);
            JsonObject courierArgs=args.deepCopy();courierArgs.addProperty("destination","Player");courierArgs.addProperty("returnHome",true);
            UUID recipient=UUID.randomUUID();courierArgs.addProperty("recipient",recipient.toString());courierArgs.addProperty("recipientName","KitRecipient");
            prepare.add("args",courierArgs);
            JsonObject courier=submit(workerId,0);courier.remove("script");courier.add("args",courierArgs);courier.add("package",host.control(prepare));
            UUID courierId=UUID.fromString(text(courier,"id"));host.control(courier);await(()->state(host,courierId).equals("Running"),worker);
            JsonObject envelope=worker.installedEnvelope,routed=envelope.getAsJsonObject("dispatch").getAsJsonObject("args");
            assert envelope.getAsJsonObject("dispatch").getAsJsonArray("supportedActions").asList().stream().anyMatch(a->a.getAsString().equals("StashVisit"));
            assert text(routed.getAsJsonObject("returnStash"),"dimension").equals("minecraft:the_nether");
            String courierScript=text(envelope.getAsJsonObject("programs").getAsJsonObject("task-kit-delivery"),"script");
            var take=BotLua.next(courierScript,new JsonObject(),routed,null,null);
            var tpa=BotLua.next(courierScript,take.state(),routed,JsonParser.parseString("{picked:1,nextPick:1,ok:true}").getAsJsonObject(),null);
            int sent=worker.tpaSends.size();replayAction(host,worker,courierId,tpa.action());await(()->worker.tpaSends.size()>sent,worker);
            assert text(worker.tpaSends.get(sent),"name").equals("KitRecipient")&&text(worker.tpaSends.get(sent),"target").equals(recipient.toString());
            assert worker.tpaAccepts.isEmpty() : "External recipients accept their own TPA; the host does not accept for them";
            worker.scope="test.invalid\nminecraft:the_end";worker.heartbeat();
            JsonObject arrival=JsonParser.parseString("{ok:true,deliveryAnchor:{x:0,y:116,z:0,yaw:0,dimension:'minecraft:the_end'}}").getAsJsonObject();
            var drop=BotLua.next(courierScript,tpa.state(),routed,arrival,null);replayAction(host,worker,courierId,drop.action());
            var home=BotLua.next(courierScript,drop.state(),routed,JsonParser.parseString("{ok:true,dropped:1}").getAsJsonObject(),null);replayAction(host,worker,courierId,home.action());
            assert state(host,courierId).equals("Running")&&text(home.action(),"type").equals("StashVisit") : "A cross-dimension courier remains active until its return";
            worker.scope="test.invalid\nminecraft:the_nether";worker.heartbeat();
            var finished=BotLua.next(courierScript,home.state(),routed,JsonParser.parseString("{ok:true}").getAsJsonObject(),null);replayAction(host,worker,courierId,finished.action());
            await(()->state(host,courierId).equals("Complete"),worker);
            assert taskView(host,courierId).getAsJsonObject("kitJob").get("delivered").getAsInt()==1;
            JsonObject emptyDestination=observation.deepCopy();emptyDestination.add("items",new JsonObject());
            StashCatalog.save(dir,"Default","test.invalid\nminecraft:the_nether",definition.getAsJsonObject("stash"),emptyDestination);
            kitObservation.add("items",JsonParser.parseString("{'minecraft:purple_shulker_box':1,'minecraft:obsidian':64}").getAsJsonObject());
            StashCatalog.save(dir,"Default","test.invalid\nminecraft:the_nether",definition.getAsJsonObject("stash"),kitObservation);
            JsonObject deposit=submit(workerId,0),depositArgs=definition.getAsJsonObject("stash").deepCopy();
            depositArgs.addProperty("kitTypeId",StashCatalog.kitTypeId(box));depositArgs.addProperty("count",1);
            deposit.add("args",depositArgs);deposit.addProperty("script","return function(ctx) return bot.stash_store(ctx.args) end");host.control(deposit);
            UUID depositId=UUID.fromString(text(deposit,"id"));await(()->state(host,depositId).equals("Running"),worker);
            JsonObject depositRun=worker.checkpoints.get(UUID.fromString(worker.current));
            assert text(depositRun.getAsJsonObject("action"),"type").equals("StashDeposit")
                && !depositRun.getAsJsonObject("action").has("picks") : "A carried-kit deposit is not reinterpreted as a withdrawal because it has a kit ID";
            JsonObject reservation=message("stash-findings");reservation.addProperty("task",depositId.toString());reservation.add("run",depositRun.get("run"));reservation.add("token",depositRun.get("token"));reservation.add("action",depositRun.get("action").deepCopy());reservation.addProperty("delivery",1);
            int beforeReservation=worker.stashAcks;
            worker.c.send(reservation.toString());await(()->worker.stashAcks==beforeReservation+1,worker);
            assert worker.lastStashAck.getAsJsonObject("column").get("x").getAsInt()==1&&!worker.lastStashAck.get("permit").getAsBoolean() : "A reservation request needs no fabricated observation";
            JsonObject editReservation=op("stash-reserve-columns");editReservation.addProperty("crew","Default");editReservation.addProperty("scope","test.invalid\nminecraft:the_nether");editReservation.addProperty("name","Depot");editReservation.addProperty("kitTypeId","");editReservation.addProperty("expected",text(StashCatalog.summary(StashCatalog.get(dir,"Default","test.invalid\nminecraft:the_nether","Depot")),"revision"));
            editReservation.add("columns",JsonParser.parseString("[{x:1,y:116,z:0,axis:'X'}]").getAsJsonArray());rejects(()->host.control(editReservation));
            assert StashCatalog.get(dir,"Default","test.invalid\nminecraft:the_nether","Depot").getAsJsonObject("columnReservations").has("1,0") : "Operators cannot revoke an active worker's deposit lease";
            JsonObject changed=observation.deepCopy();changed.addProperty("x",1);reservation.add("observation",changed);reservation.addProperty("delivery",2);
            worker.c.send(reservation.toString());await(()->worker.stashAcks==beforeReservation+2,worker);
            assert worker.lastStashAck.getAsJsonObject("column").get("x").getAsInt()==0&&!worker.lastStashAck.get("permit").getAsBoolean() : "The real socket binding reports changed stock and redirects before authorizing a deposit";
            cancel.addProperty("id",depositId.toString());host.control(cancel);await(()->worker.current.isEmpty()&&state(host,depositId).equals("Cancelled")&&!flag(taskView(host,depositId),"cleanupPending"),worker);
            editReservation.add("columns",JsonParser.parseString("[{x:0,y:116,z:0,axis:'X'}]").getAsJsonArray());editReservation.addProperty("expected",text(StashCatalog.summary(StashCatalog.get(dir,"Default","test.invalid\nminecraft:the_nether","Depot")),"revision"));host.control(editReservation);
        }
        try(HostService restarted=new HostService(dir,"127.0.0.1",0,CREWS,30)){assert restarted.control(op("status")).getAsJsonArray("stashes").size()==1;}
    }
    private static void stashLiveCheck() throws Exception {
        Path dir=Files.createTempDirectory("monocle-host-live-stash-");String scope="test.invalid\nminecraft:the_nether";
        JsonObject p=StashCatalog.plan(JsonParser.parseString("{name:'Live',homeName:'live',minX:0,maxX:40,minY:116,maxY:116,minZ:0,maxZ:0,lazyMode:false}").getAsJsonObject());StashCatalog.define(dir,"Default",scope,p);
        JsonObject observed=JsonParser.parseString("{x:0,y:116,z:0,status:'observed',block:'minecraft:chest',slotCount:27,occupiedSlots:1,items:{'minecraft:stone':64},shulkers:[]}").getAsJsonObject();
        for(int i=0;i<40;i++){observed.addProperty("x",i);StashCatalog.record(dir,"Default",scope,p,observed);}observed.addProperty("x",0);
        JsonObject large=p.deepCopy();large.addProperty("name","Large");large.addProperty("maxX",4096);StashCatalog.define(dir,"Default",scope,large);
        Path largeFile=dir.resolve("stashes").resolve(TaskFiles.hash("Default\n"+scope+"\nLarge")+".sqlite");JsonObject largeDb=StashStore.read(largeFile);
        for(int i=0;i<4097;i++){JsonObject r=observed.deepCopy();r.addProperty("x",i);largeDb.getAsJsonObject("containers").add(StashCatalog.containerKey(r),r);}StashStore.write(largeFile,largeDb);
        try(HostService host=new HostService(dir,"127.0.0.1",0,CREWS,30);Worker worker=new Worker(host.port(),KEY,UUID.randomUUID(),new LinkedHashMap<>())){
            worker.liveStashes=true;worker.announce();await(()->worker.stashWatch.size()>=2,worker);
            assert worker.stashWatch.stream().mapToInt(m->m.getAsJsonArray("records").size()).sum()==40 : "Watch manifests arrive in paced bounded pages";
            JsonObject previous=StashCatalog.get(dir,"Default",scope,"Live").getAsJsonObject("containers").getAsJsonObject("0,116,0");
            JsonObject live=message("stash-live");live.addProperty("name","Live");live.addProperty("scope",scope);live.addProperty("basis",StashCatalog.observationBasis(previous));live.addProperty("eventId",UUID.randomUUID().toString());
            observed.getAsJsonObject("items").addProperty("minecraft:stone",128);StashCatalog.attachObservation(live,observed);worker.c.send(live.toString());await(()->worker.liveAcks.size()==1,worker);
            assert text(worker.liveAcks.getLast(),"outcome").equals("applied");String revision=text(StashCatalog.summary(StashCatalog.get(dir,"Default",scope,"Live")),"revision");
            worker.c.send(live.toString());await(()->worker.liveAcks.size()==2,worker);assert text(worker.liveAcks.getLast(),"outcome").equals("unchanged")&&text(StashCatalog.summary(StashCatalog.get(dir,"Default",scope,"Live")),"revision").equals(revision);
            JsonObject removed=JsonParser.parseString("{x:0,y:116,z:0,status:'removed',block:'minecraft:air',items:{},shulkers:[]}").getAsJsonObject();live.addProperty("eventId",UUID.randomUUID().toString());StashCatalog.attachObservation(live,removed);worker.c.send(live.toString());await(()->worker.liveAcks.size()==3,worker);assert text(worker.liveAcks.getLast(),"outcome").equals("conflict");
            live.addProperty("basis",text(worker.liveAcks.getLast(),"basis"));live.addProperty("eventId",UUID.randomUUID().toString());worker.c.send(live.toString());await(()->worker.liveAcks.size()==4,worker);assert text(worker.liveAcks.getLast(),"outcome").equals("applied");
            live.addProperty("scope","test.invalid\nminecraft:the_end");live.addProperty("eventId",UUID.randomUUID().toString());worker.c.send(live.toString());await(()->worker.liveAcks.size()==5,worker);assert text(worker.liveAcks.getLast(),"outcome").equals("deferred")&&worker.c.connected() : "World-report races defer telemetry instead of disconnecting the worker";
            assert eventSeen(host,"stash-live-applied",worker.id)&&eventSeen(host,"stash-live-conflict",worker.id);
            assert worker.stashWatch.stream().noneMatch(m->text(m.getAsJsonObject("stash"),"name").equals("Large"))&&worker.c.connected() : "An old worker remains connected without an oversized baseline";
            worker.stashContainerLimit=StashCatalog.MAX_CONTAINERS;worker.heartbeat();
            await(()->worker.stashWatch.stream().filter(m->text(m.getAsJsonObject("stash"),"name").equals("Large")).mapToInt(m->m.getAsJsonArray("records").size()).sum()==4097,worker);
            assert worker.stashWatch.stream().anyMatch(m->m.get("parts").getAsInt()>128)&&worker.c.connected() : "Capability negotiation delivers large baselines over real sockets";
            JsonObject batch=message("stash-live-batch");JsonArray entries=new JsonArray();batch.add("entries",entries);
            for(int x:List.of(5,39,40)){JsonObject e=message("stash-live");e.addProperty("name","Live");e.addProperty("scope",scope);e.addProperty("eventId",UUID.randomUUID().toString());JsonObject db=StashCatalog.get(dir,"Default",scope,"Live");e.addProperty("basis",StashCatalog.observationBasis(db.getAsJsonObject("containers").getAsJsonObject(x+",116,0")));JsonObject o=observed.deepCopy();o.addProperty("x",x);o.getAsJsonObject("items").addProperty("minecraft:stone",256);StashCatalog.attachObservation(e,o);entries.add(e);}
            worker.c.send(batch.toString());await(()->worker.liveBatchAcks.size()==1,worker);
            assert worker.liveBatchAcks.getLast().getAsJsonArray("entries").asList().stream().allMatch(v->text(v.getAsJsonObject(),"outcome").equals("applied"))&&worker.c.connected();
            worker.c.send(batch.toString());await(()->worker.liveBatchAcks.size()==2,worker);
            assert worker.liveBatchAcks.getLast().getAsJsonArray("entries").asList().stream().allMatch(v->text(v.getAsJsonObject(),"outcome").equals("unchanged")) : "Real socket batch retries are idempotent";
            entries.forEach(v->v.getAsJsonObject().addProperty("scope","test.invalid\nminecraft:the_end"));worker.c.send(batch.toString());await(()->worker.liveBatchAcks.size()==3,worker);
            assert worker.liveBatchAcks.getLast().getAsJsonArray("entries").asList().stream().allMatch(v->text(v.getAsJsonObject(),"outcome").equals("deferred"))&&worker.c.connected();
        }
    }
    private static JsonObject submit(UUID worker, int priority) {
        JsonObject request = op("submit"); request.addProperty("id", UUID.randomUUID().toString()); request.addProperty("name", "Wait test"); request.addProperty("crew", "Default");
        request.addProperty("priority", priority); request.addProperty("server", "test.invalid"); request.addProperty("dimension", "minecraft:the_nether");
        JsonArray workers = new JsonArray(); workers.add(worker.toString()); request.add("workers", workers);
        request.addProperty("script", "return function(ctx) if not ctx.state.waited then ctx.state.waited=true; return bot.wait(200) end; return bot.done() end"); return request;
    }
    private static String state(HostService host, UUID id) {
        return text(taskView(host,id),"status");
    }
    private static JsonObject taskView(HostService host, UUID id) {
        JsonObject status = host.control(op("status"));
        for (String key : List.of("tasks", "history")) for (JsonElement entry : status.getAsJsonArray(key)) if (text(entry.getAsJsonObject(), "id").equals(id.toString())) return entry.getAsJsonObject();
        return new JsonObject();
    }
    private static void await(BooleanSupplier done, Worker... workers) throws Exception {
        long deadline = System.nanoTime() + 6_000_000_000L;
        do {
            for (Worker worker : workers) worker.pump();
            if (done.getAsBoolean()) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for host/worker state");
    }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("Expected rejection"); } catch (IllegalArgumentException | IllegalStateException expected) { }
    }
    private static void publicWorkerGatewayCheck() throws Exception {
        var rate=new PublicWorkerGateway.Session(UUID.randomUUID());
        for(int i=0;i<64;i++)assert rate.allowFrame(1_000L);
        assert !rate.allowFrame(1_000L) && rate.allowFrame(1_000_001_000L)
            : "A noisy worker must be disconnected, while a new time window recovers";
        UUID worker=UUID.randomUUID(), other=UUID.randomUUID();
        String token="interop-worker-secret-12345678901234567890", otherToken="interop-other-secret-12345678901234567890";
        Path directory=Files.createTempDirectory("monocle-public-worker-check-");
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            PublicWorkerGateway gateway=new PublicWorkerGateway(0,Map.of(worker,token,other,otherToken),host);
            HttpClient http=HttpClient.newHttpClient()) {
            URI endpoint=URI.create("ws://127.0.0.1:"+gateway.getPort()+PublicWorkerGateway.PATH);
            var listener=new java.net.http.WebSocket.Listener() {
                final java.util.concurrent.BlockingQueue<String> frames=new java.util.concurrent.LinkedBlockingQueue<>();
                @Override public void onOpen(java.net.http.WebSocket socket) { socket.request(1); }
                @Override public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket socket,CharSequence text,boolean last) {
                    assert last;frames.add(text.toString());socket.request(1);return null;
                }
            };
            handshakeRejected(http.newWebSocketBuilder().header("Authorization","Bearer wrong-secret-12345678901234567890").buildAsync(endpoint,new java.net.http.WebSocket.Listener() {}));
            handshakeRejected(http.newWebSocketBuilder().header("Authorization","Bearer "+token).header("Origin","https://browser.invalid").buildAsync(endpoint,new java.net.http.WebSocket.Listener() {}));
            handshakeRejected(http.newWebSocketBuilder().header("Authorization","Bearer "+token).buildAsync(URI.create(endpoint+"/wrong"),new java.net.http.WebSocket.Listener() {}));
            java.net.http.WebSocket socket=http.newWebSocketBuilder().header("Authorization","Bearer "+token).buildAsync(endpoint,listener).join();
            JsonObject hello=JsonParser.parseString("{apiVersion:'workers.monocle.dev/v1',type:'session.hello',sequence:0,payload:{supportedVersions:['workers.monocle.dev/v1'],capabilities:[{id:'workers.wait.v1'}],lastHostSequence:0,lastWorkerSequence:0}}").getAsJsonObject();
            hello.addProperty("messageId",UUID.randomUUID().toString());hello.addProperty("correlationId",UUID.randomUUID().toString());
            hello.addProperty("sentAt",java.time.Instant.now().toString());hello.getAsJsonObject("payload").addProperty("workerId",worker.toString());
            socket.sendText(hello.toString(),true).join();
            JsonObject accepted=JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject();
            assert text(accepted,"type").equals("session.accepted")&&text(accepted,"correlationId").equals(text(hello,"correlationId"));
            assert text(accepted,"apiVersion").equals(PublicWorkerGateway.LEGACY_VERSION)
                && text(accepted.getAsJsonObject("payload"),"version").equals(PublicWorkerGateway.LEGACY_VERSION);
            assert accepted.getAsJsonObject("payload").get("executionEnabled").getAsBoolean() : "The host advertises its narrow Wait adapter";
            JsonObject observation=hello.deepCopy();observation.addProperty("type","worker.observation");observation.addProperty("sequence",1);
            observation.addProperty("messageId",UUID.randomUUID().toString());observation.add("payload",JsonParser.parseString("{observedAt:'2026-09-28T12:00:00Z',scope:{server:'play.example.org',dimension:'minecraft:the_nether'},position:{x:12,y:116,z:34}}").getAsJsonObject());
            observation.addProperty("workerId",worker.toString());socket.sendText(observation.toString(),true).join();
            JsonObject reconcile=hello.deepCopy();reconcile.addProperty("type","state.reconcile");reconcile.addProperty("sequence",2);
            reconcile.addProperty("messageId",UUID.randomUUID().toString());reconcile.addProperty("workerId",worker.toString());
            reconcile.add("payload",JsonParser.parseString("{lastHostSequence:0,lastWorkerSequence:1,activeExecutions:[{jobId:'34bc05e5-6973-4629-bd69-644d6fbf3a3b',executionId:'c9547876-e3d8-4713-97d3-35a751718ccd',generation:1}]}").getAsJsonObject());
            socket.sendText(reconcile.toString(),true).join();
            JsonObject reconciled=JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject();
            assert text(reconciled,"type").equals("state.reconciled") && text(reconciled.getAsJsonObject("payload").getAsJsonArray("decisions").get(0).getAsJsonObject(),"decision").equals("inspect") : "Unknown public executions cannot resume";
            JsonObject view=host.control(op("status")).getAsJsonArray("workers").asList().stream().map(JsonElement::getAsJsonObject).filter(w->text(w,"id").equals(worker.toString())).findFirst().orElseThrow();
            assert flag(view,"reconciled") && flag(view,"connected") && text(view,"scope").equals("play.example.org\nminecraft:the_nether") && view.get("x").getAsDouble()==12;
            hello.addProperty("apiVersion",PublicWorkerGateway.VERSION);
            socket.sendText(hello.toString(),true).join();
            JsonObject rejected=JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject();
            assert text(rejected,"type").equals("protocol.error") : "A legacy session cannot switch versions midstream";
            socket.abort();
            await(()->host.control(op("status")).getAsJsonArray("workers").asList().stream().map(JsonElement::getAsJsonObject)
                .anyMatch(w->text(w,"id").equals(worker.toString())&&!flag(w,"connected")&&!flag(w,"reconciled")));
            var wrongVersion=new java.net.http.WebSocket.Listener() {
                final java.util.concurrent.BlockingQueue<String> frames=new java.util.concurrent.LinkedBlockingQueue<>();
                @Override public void onOpen(java.net.http.WebSocket socket) { socket.request(1); }
                @Override public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket socket,CharSequence text,boolean last) { frames.add(text.toString());socket.request(1);return null; }
            };
            java.net.http.WebSocket second=http.newWebSocketBuilder().header("Authorization","Bearer "+otherToken).buildAsync(endpoint,wrongVersion).join();
            hello.getAsJsonObject("payload").addProperty("workerId",other.toString());hello.getAsJsonObject("payload").getAsJsonArray("supportedVersions").set(0,new JsonPrimitive("workers.monocle.dev/v2"));
            second.sendText(hello.toString(),true).join();
            JsonObject versionError=JsonParser.parseString(wrongVersion.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject();
            assert text(versionError,"type").equals("protocol.error")&&text(versionError.getAsJsonObject("payload"),"code").equals("unsupported_version");
            second.abort();
        }
        System.out.println("Public worker registration checks passed: credentials, observation, conservative reconciliation, offline roster, and no legacy execution.");
    }
    private static void monocleRwpWorkerCheck() throws Exception {
        UUID worker = UUID.randomUUID();
        String token = "monocle-rwp-worker-secret-12345678901234567890";
        Path directory = Files.createTempDirectory("monocle-rwp-worker-");
        Path journal = directory.resolve("rwp-execution.json");
        try (HostService host = new HostService(directory, "127.0.0.1", 0, CREWS, 30);
             PublicWorkerGateway gateway = new PublicWorkerGateway(0, Map.of(worker, token), host)) {
            String address = "ws://127.0.0.1:" + gateway.getPort() + RwpWorkerConnection.PATH;
            JsonObject observation = JsonParser.parseString("{observedAt:'2026-09-30T12:00:00Z',name:'Unbans',scope:{server:'play.example.org',dimension:'minecraft:the_nether'},position:{x:12,y:116,z:34}}").getAsJsonObject();
            try (RwpWorkerConnection first = new RwpWorkerConnection(address, worker, token, journal)) {
                first.connect();
                await(() -> {
                    first.poll(() -> observation);
                    return first.connected() && first.hostImplementation().startsWith("dev.monocle.host ")
                        && host.control(op("status")).getAsJsonArray("workers").asList().stream()
                        .map(JsonElement::getAsJsonObject).anyMatch(w -> text(w,"id").equals(worker.toString())
                            && flag(w,"connected") && flag(w,"reconciled") && text(w,"name").equals("Unbans") && text(w,"scope").equals("play.example.org\nminecraft:the_nether")
                            && text(w.getAsJsonObject("implementation"),"id").equals("dev.monocle.client")
                            && w.getAsJsonArray("capabilities").asList().stream().anyMatch(capability -> capability.getAsString().equals("workers.wait.v1")));
                });
                JsonObject resource=ResourceApi.route(host,"GET",URI.create("/v1/workers/"+worker),null,null).body();
                assert text(resource.getAsJsonObject("implementation"),"id").equals("dev.monocle.client")
                    && resource.getAsJsonArray("capabilities").asList().stream().anyMatch(c->c.getAsString().equals("workers.wait.v1"))
                    : "Operator readback must retain the worker's identity and advertised actions";
                JsonObject move = op("crew-move"); move.addProperty("crew", "Default"); move.addProperty("worker", worker.toString()); host.control(move);
                UUID job = submitPublicWait(host, worker, 40);
                await(() -> { first.poll(() -> observation); return state(host, job).equals("Running") && first.work().startsWith("running"); });
                first.gameTick(); first.gameTick();
                assert first.work().contains("38 ticks remaining") : "Wait ticks must be saved before disconnect";
                await(() -> text(taskView(host, job).getAsJsonObject("runs").getAsJsonObject(worker.toString()), "detail").contains("ticks remaining"));
            }
            // WebSocket close is asynchronous; do not race the gateway's live-owner duplicate protection.
            await(() -> host.control(op("status")).getAsJsonArray("workers").asList().stream()
                .map(JsonElement::getAsJsonObject).anyMatch(w -> text(w,"id").equals(worker.toString()) && !flag(w,"connected")));
            try (RwpWorkerConnection resumed = new RwpWorkerConnection(address, worker, token, journal)) {
                assert resumed.work().contains("38 ticks remaining") : "Wait checkpoint must survive client restart";
                resumed.connect();
                await(() -> { resumed.poll(() -> observation); return resumed.connected(); });
                UUID job = UUID.fromString(text(TaskFiles.read(journal).getAsJsonObject("run"), "jobId"));
                await(() -> { resumed.poll(() -> observation); resumed.gameTick(); return state(host, job).equals("Complete") && resumed.work().equals("idle"); });
                UUID travel = submitPublicTravel(host, worker);
                await(() -> { resumed.poll(() -> observation); return state(host, travel).equals("Running") && resumed.activeTravel() != null; });
                assert text(resumed.activeTravel().getAsJsonObject("action"), "type").equals("workers.travel.v1");
                resumed.reportTravelProgress("Walking toward destination");
                await(() -> text(taskView(host, travel).getAsJsonObject("runs").getAsJsonObject(worker.toString()), "detail").equals("Walking toward destination"));
                rejects(() -> resumed.completeTravel(observation));
                JsonObject arrived = observation.deepCopy();
                arrived.addProperty("observedAt", java.time.Instant.now().toString());
                JsonObject position = arrived.getAsJsonObject("position");
                position.addProperty("x", 10); position.addProperty("y", 116); position.addProperty("z", -10);
                resumed.completeTravel(arrived);
                await(() -> { resumed.poll(() -> arrived); return state(host, travel).equals("Complete") && resumed.work().equals("idle"); });
                UUID stoppedTravel = submitPublicTravel(host, worker);
                await(() -> { resumed.poll(() -> observation); return state(host, stoppedTravel).equals("Running") && resumed.activeTravel() != null; });
                assert text(TaskFiles.read(journal).getAsJsonObject("run").getAsJsonObject("action"), "type").equals("workers.travel.v1");
                JsonObject stopTravel = op("cancel"); stopTravel.addProperty("id", stoppedTravel.toString()); host.control(stopTravel);
                await(() -> { resumed.poll(() -> observation); return resumed.activeTravel() == null && resumed.work().startsWith("cancelling"); });
                assert flag(taskView(host, stoppedTravel), "cleanupPending") : "Host cannot assume game-thread movement release";
                resumed.acknowledgeMovementCancellation();
                await(() -> { resumed.poll(() -> observation); return resumed.work().equals("idle") && !flag(taskView(host, stoppedTravel), "cleanupPending"); });
                UUID follow = submitPublicFollow(host, worker, 3);
                await(() -> { resumed.poll(() -> observation); return state(host, follow).equals("Running") && resumed.activeMovement() != null; });
                assert resumed.activeTravel() == null : "Follow must not be mistaken for fixed-destination Travel";
                assert text(resumed.activeMovement().getAsJsonObject("action"), "type").equals(RwpWorkerConnection.FOLLOW);
                rejects(resumed::completeFollow);
                assert !resumed.followTick() && !resumed.followTick();
                assert TaskFiles.read(journal).getAsJsonObject("run").get("remainingTicks").getAsInt() == 1;
                assert resumed.followTick() && resumed.followFinished();
                resumed.completeFollow(); // Simulated game thread has released its native controller.
                await(() -> { resumed.poll(() -> observation); return state(host, follow).equals("Complete") && resumed.work().equals("idle"); });
                UUID unlimited = submitPublicFollow(host, worker, 0);
                await(() -> { resumed.poll(() -> observation); return state(host, unlimited).equals("Running") && resumed.activeMovement() != null; });
                for (int i = 0; i < 10; i++) assert !resumed.followTick();
                assert !resumed.followFinished();
                rejects(resumed::completeFollow);
                resumed.reportTravelProgress("Following the visible player");
                await(() -> text(taskView(host, unlimited).getAsJsonObject("runs").getAsJsonObject(worker.toString()), "detail").equals("Following the visible player"));
                JsonObject stopFollow = op("cancel"); stopFollow.addProperty("id", unlimited.toString()); host.control(stopFollow);
                await(() -> { resumed.poll(() -> observation); return resumed.work().startsWith("cancelling") && resumed.activeMovement() == null; });
                assert flag(taskView(host, unlimited), "cleanupPending");
                try (RwpWorkerConnection saved = new RwpWorkerConnection(address, worker, token, journal)) {
                    assert saved.work().startsWith("cancelling") : "Pending game-thread cleanup survives restart";
                }
                resumed.acknowledgeMovementCancellation();
                await(() -> { resumed.poll(() -> observation); return resumed.work().equals("idle") && !flag(taskView(host, unlimited), "cleanupPending"); });
                UUID cancelled = submitPublicWait(host, worker, 40);
                await(() -> { resumed.poll(() -> observation); return state(host, cancelled).equals("Running") && resumed.work().startsWith("running"); });
            }
            await(() -> host.control(op("status")).getAsJsonArray("workers").asList().stream()
                .map(JsonElement::getAsJsonObject).anyMatch(w -> text(w,"id").equals(worker.toString()) && !flag(w,"connected")));
            UUID cancelled = UUID.fromString(text(TaskFiles.read(journal).getAsJsonObject("run"), "jobId"));
            JsonObject cancel = op("cancel"); cancel.addProperty("id", cancelled.toString()); host.control(cancel);
            try (RwpWorkerConnection returned = new RwpWorkerConnection(address, worker, token, journal)) {
                returned.connect();
                try{await(() -> { returned.poll(() -> observation); return returned.connected() && returned.work().equals("idle") && !flag(taskView(host, cancelled), "cleanupPending"); });}
                catch(AssertionError e){throw new AssertionError("Reconnected cancellation: "+returned.work()+" connected="+returned.connected()+" task="+taskView(host,cancelled),e);}
            }
        }
        rejects(() -> new RwpWorkerConnection("ws://example.org:6972" + RwpWorkerConnection.PATH, worker, token, directory.resolve("invalid.json")));
        System.out.println("Monocle RWP checks passed: durable Wait/Travel/Follow, bounded and unlimited Follow, game-thread cancellation handoff, restart and reconciliation.");
    }
    private static void monocleRwpFollowReconnectCheck() throws Exception {
        UUID worker = UUID.randomUUID(); String token = "monocle-follow-reconnect-secret-1234567890";
        Path directory = Files.createTempDirectory("monocle-follow-reconnect-"); Path journal = directory.resolve("rwp-execution.json");
        JsonObject observation = JsonParser.parseString("{observedAt:'2026-10-11T12:00:00Z',scope:{server:'play.example.org',dimension:'minecraft:the_nether'},position:{x:12,y:116,z:34}}").getAsJsonObject();
        try (HostService host = new HostService(directory, "127.0.0.1", 0, CREWS, 30);
             PublicWorkerGateway gateway = new PublicWorkerGateway(0, Map.of(worker, token), host)) {
            String address = "ws://127.0.0.1:" + gateway.getPort() + RwpWorkerConnection.PATH;
            UUID job;
            try (RwpWorkerConnection first = new RwpWorkerConnection(address, worker, token, journal)) {
                first.connect(); await(() -> { first.poll(() -> observation); return first.connected(); });
                JsonObject move = op("crew-move"); move.addProperty("crew", "Default"); move.addProperty("worker", worker.toString()); host.control(move);
                job = submitPublicFollow(host, worker, 5);
                await(() -> { first.poll(() -> observation); return first.activeMovement() != null; });
                assert !first.followTick() && !first.followTick();
            }
            await(() -> host.control(op("status")).getAsJsonArray("workers").asList().stream().map(JsonElement::getAsJsonObject)
                .anyMatch(w -> text(w, "id").equals(worker.toString()) && !flag(w, "connected")));
            try (RwpWorkerConnection resumed = new RwpWorkerConnection(address, worker, token, journal)) {
                assert resumed.activeMovement() == null && !resumed.followTick() : "Saved work is not fresh authority";
                resumed.connect(); await(() -> { resumed.poll(() -> observation); return resumed.activeMovement() != null; });
                assert resumed.activeMovement().get("remainingTicks").getAsInt() == 3 : "Reconnect cannot reset the Follow interval";
                assert !resumed.followTick() && !resumed.followTick() && resumed.followTick();
                resumed.completeFollow();
                await(() -> { resumed.poll(() -> observation); return state(host, job).equals("Complete") && resumed.work().equals("idle"); });
            }
        }
        System.out.println("Monocle Follow reconnect check passed: authority reconciliation and retained interval, without game simulation.");
    }

    private static UUID submitPublicFollow(HostService host, UUID worker, int ticks) {
        UUID job = UUID.randomUUID();
        JsonObject request = op("submit"); request.addProperty("id", job.toString()); request.addProperty("crew", "Default");
        request.addProperty("name", "Public Monocle Follow"); request.addProperty("server", "play.example.org"); request.addProperty("dimension", "minecraft:the_nether");
        JsonArray workers = new JsonArray(); workers.add(worker.toString()); request.add("workers", workers);
        JsonObject action = new JsonObject(), args = new JsonObject(); action.addProperty("type", RwpWorkerConnection.FOLLOW);
        args.addProperty("target", UUID.randomUUID().toString()); args.addProperty("radius", 2); args.addProperty("ticks", ticks);
        action.add("arguments", args); request.add("publicAction", action); host.control(request); return job;
    }
    private static JsonObject publicFrame(UUID worker,int sequence,String type,JsonObject payload) {
        JsonObject frame=new JsonObject();frame.addProperty("apiVersion",PublicWorkerGateway.VERSION);frame.addProperty("type",type);
        frame.addProperty("messageId",UUID.randomUUID().toString());frame.addProperty("correlationId",UUID.randomUUID().toString());
        frame.addProperty("workerId",worker.toString());frame.addProperty("sequence",sequence);
        frame.addProperty("sentAt",java.time.Instant.now().toString());frame.add("payload",payload);return frame;
    }
    private static UUID submitPublicWait(HostService host,UUID worker) { return submitPublicWait(host,worker,40); }
    private static UUID submitPublicWait(HostService host,UUID worker,int ticks) {
        UUID job=UUID.randomUUID();JsonObject prepare=op("workflow-prepare");prepare.addProperty("id","task-wait");
        prepare.addProperty("scope","play.example.org\nminecraft:the_nether");JsonObject args=new JsonObject();args.addProperty("ticks",ticks);prepare.add("args",args);
        JsonObject request=op("submit");request.addProperty("id",job.toString());request.addProperty("crew","Default");request.addProperty("name","Public Wait");
        request.addProperty("server","play.example.org");request.addProperty("dimension","minecraft:the_nether");
        JsonArray workers=new JsonArray();workers.add(worker.toString());request.add("workers",workers);request.add("args",args);request.add("package",host.control(prepare));
        host.control(request);return job;
    }
    private static UUID submitPublicTravel(HostService host,UUID worker) {
        UUID job=UUID.randomUUID();JsonObject prepare=op("workflow-prepare");prepare.addProperty("id","task-travel");
        prepare.addProperty("scope","play.example.org\nminecraft:the_nether");
        JsonObject args=JsonParser.parseString("{x:10,y:116,z:-10,radius:2}").getAsJsonObject();prepare.add("args",args);
        JsonObject request=op("submit");request.addProperty("id",job.toString());request.addProperty("crew","Default");request.addProperty("name","Public Travel");
        request.addProperty("server","play.example.org");request.addProperty("dimension","minecraft:the_nether");
        JsonArray workers=new JsonArray();workers.add(worker.toString());request.add("workers",workers);request.add("args",args);request.add("package",host.control(prepare));
        host.control(request);return job;
    }
    private static void publicWorkerActionsCheck() throws Exception {
        assert PublicWorkerGateway.hasOutboundCapacity(31) && !PublicWorkerGateway.hasOutboundCapacity(32)
            : "A stalled worker must not accumulate unbounded outbound frames";
        UUID worker=UUID.randomUUID();String token="public-wait-secret-12345678901234567890";
        HttpClient http=HttpClient.newHttpClient();
        Path directory=Files.createTempDirectory("monocle-public-wait-");
        JsonObject waitCompletion=new JsonObject();
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            PublicWorkerGateway gateway=new PublicWorkerGateway(0,Map.of(worker,token),host)) {
            var listener=new java.net.http.WebSocket.Listener(){
                final java.util.concurrent.BlockingQueue<String> frames=new java.util.concurrent.LinkedBlockingQueue<>();
                @Override public void onOpen(java.net.http.WebSocket socket){socket.request(1);}
                @Override public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket socket,CharSequence text,boolean last){assert last;frames.add(text.toString());socket.request(1);return null;}
            };
            java.net.http.WebSocket socket=http.newWebSocketBuilder().header("Authorization","Bearer "+token)
                .buildAsync(URI.create("ws://127.0.0.1:"+gateway.getPort()+PublicWorkerGateway.PATH),listener).join();
            JsonObject hello=JsonParser.parseString("{supportedVersions:['rwp/1-draft'],implementation:{id:'dev.monocle.test',version:'1'},capabilities:[{id:'workers.wait.v1'},{id:'workers.travel.v1'}],lastHostSequence:0,lastWorkerSequence:0,workerId:'"+worker+"'}").getAsJsonObject();
            socket.sendText(publicFrame(worker,0,"session.hello",hello).toString(),true).join();
            assert text(JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject(),"type").equals("session.accepted");
            assert eventSeen(host,"worker-connected",worker) : "Public worker connection belongs in the operator feed";
            JsonObject observation=JsonParser.parseString("{observedAt:'2026-09-28T12:00:00Z',scope:{server:'play.example.org',dimension:'minecraft:the_nether'}}").getAsJsonObject();
            socket.sendText(publicFrame(worker,1,"worker.observation",observation).toString(),true).join();
            JsonObject reconcile=JsonParser.parseString("{lastHostSequence:0,lastWorkerSequence:1,activeExecutions:[]}").getAsJsonObject();
            socket.sendText(publicFrame(worker,2,"state.reconcile",reconcile).toString(),true).join();
            assert text(JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject(),"type").equals("state.reconciled");
            JsonObject move=op("crew-move");move.addProperty("crew","Default");move.addProperty("worker",worker.toString());host.control(move);
            UUID job=submitPublicWait(host,worker);
            JsonObject assignment=JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject();
            assert text(assignment,"type").equals("execution.assign");
            JsonObject ref=assignment.getAsJsonObject("payload").deepCopy();
            assert text(ref.getAsJsonObject("action"),"type").equals("workers.wait.v1") && ref.getAsJsonObject("action").getAsJsonObject("arguments").get("ticks").getAsInt()==40;
            for(String type:List.of("execution.accepted","execution.started","execution.completed")){
                JsonObject report=publicFrame(worker,type.equals("execution.accepted")?3:type.equals("execution.started")?4:5,type,ref);
                if(type.equals("execution.completed"))waitCompletion=report.deepCopy();
                socket.sendText(report.toString(),true).join();
                JsonObject ack=JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject();
                assert text(ack,"type").equals("message.ack") && text(ack.getAsJsonObject("payload"),"messageId").equals(text(report,"messageId"))
                    && text(ack.getAsJsonObject("payload"),"result").equals("accepted");
                String expected=type.equals("execution.accepted")?"Ready":type.equals("execution.started")?"Running":"Complete";
                await(()->text(taskView(host,job).getAsJsonObject("runs").getAsJsonObject(worker.toString()),"status").equals(expected));
            }
            await(()->state(host,job).equals("Complete"));
            UUID cancelled=submitPublicWait(host,worker);
            JsonObject next=JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject();
            assert text(next,"type").equals("execution.assign");JsonObject cancelRef=next.getAsJsonObject("payload").deepCopy();
            socket.sendText(publicFrame(worker,6,"execution.accepted",cancelRef).toString(),true).join();
            assert text(JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject(),"type").equals("message.ack");
            await(()->text(taskView(host,cancelled).getAsJsonObject("runs").getAsJsonObject(worker.toString()),"status").equals("Ready"));
            JsonObject stop=op("cancel");stop.addProperty("id",cancelled.toString());host.control(stop);
            JsonObject cancel=JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject();
            assert text(cancel,"type").equals("execution.cancel") && text(cancel.getAsJsonObject("payload"),"executionId").equals(text(cancelRef,"executionId"));
            cancelRef.addProperty("cleanup","acknowledged");socket.sendText(publicFrame(worker,7,"execution.cancelled",cancelRef).toString(),true).join();
            assert text(JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject(),"type").equals("message.ack");
            await(()->text(taskView(host,cancelled).getAsJsonObject("runs").getAsJsonObject(worker.toString()),"status").equals("Cancelled"));
            rejects(()->submitPublicTravel(host,worker)); // Travel requires a fresh position, not merely a matching world.
            JsonObject positioned=observation.deepCopy();positioned.add("position",JsonParser.parseString("{x:0,y:116,z:0}"));
            socket.sendText(publicFrame(worker,8,"worker.observation",positioned).toString(),true).join();
            await(()->host.control(op("status")).getAsJsonArray("workers").asList().stream().map(JsonElement::getAsJsonObject)
                .anyMatch(w->text(w,"id").equals(worker.toString()) && flag(w,"positionFresh")));
            UUID resumed=submitPublicTravel(host,worker);
            JsonObject third=JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject();
            assert text(third,"type").equals("execution.assign");JsonObject resumeRef=third.getAsJsonObject("payload").deepCopy();
            assert text(resumeRef.getAsJsonObject("action"),"type").equals("workers.travel.v1")
                && resumeRef.getAsJsonObject("action").getAsJsonObject("arguments").get("x").getAsDouble()==10;
            socket.sendText(publicFrame(worker,9,"execution.accepted",resumeRef).toString(),true).join();
            assert text(JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject(),"type").equals("message.ack");
            await(()->text(taskView(host,resumed).getAsJsonObject("runs").getAsJsonObject(worker.toString()),"status").equals("Ready"));
            socket.sendText(publicFrame(worker,10,"execution.started",resumeRef).toString(),true).join();
            assert text(JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject(),"type").equals("message.ack");
            await(()->text(taskView(host,resumed).getAsJsonObject("runs").getAsJsonObject(worker.toString()),"status").equals("Running"));
            socket.abort();
            await(()->host.control(op("status")).getAsJsonArray("workers").asList().stream().map(JsonElement::getAsJsonObject)
                .anyMatch(w->text(w,"id").equals(worker.toString())&&!flag(w,"connected")) && eventSeen(host,"worker-disconnected",worker));
            var resumedListener=new java.net.http.WebSocket.Listener(){
                final java.util.concurrent.BlockingQueue<String> frames=new java.util.concurrent.LinkedBlockingQueue<>();
                @Override public void onOpen(java.net.http.WebSocket connection){connection.request(1);}
                @Override public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket connection,CharSequence text,boolean last){assert last;frames.add(text.toString());connection.request(1);return null;}
            };
            java.net.http.WebSocket reconnect=http.newWebSocketBuilder().header("Authorization","Bearer "+token)
                .buildAsync(URI.create("ws://127.0.0.1:"+gateway.getPort()+PublicWorkerGateway.PATH),resumedListener).join();
            reconnect.sendText(publicFrame(worker,0,"session.hello",hello).toString(),true).join();
            assert text(JsonParser.parseString(resumedListener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject(),"type").equals("session.accepted");
            reconnect.sendText(publicFrame(worker,1,"worker.observation",positioned).toString(),true).join();
            JsonObject claim=new JsonObject();for(String key:List.of("jobId","executionId","generation"))claim.add(key,resumeRef.get(key).deepCopy());
            JsonArray active=new JsonArray();active.add(claim);reconcile.add("activeExecutions",active);
            reconnect.sendText(publicFrame(worker,2,"state.reconcile",reconcile).toString(),true).join();
            JsonObject decision=JsonParser.parseString(resumedListener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject();
            assert text(decision,"type").equals("state.reconciled") && text(decision.getAsJsonObject("payload").getAsJsonArray("decisions").get(0).getAsJsonObject(),"decision").equals("continue");
            rejects(()->host.publicWorkerReport(worker,UUID.randomUUID(),"execution.completed",resumeRef)); // Starting position is not arrival.
            JsonObject arrived=observation.deepCopy();arrived.add("position",JsonParser.parseString("{x:10,y:116,z:-10}"));
            reconnect.sendText(publicFrame(worker,3,"worker.observation",arrived).toString(),true).join();
            await(()->host.control(op("status")).getAsJsonArray("workers").asList().stream().map(JsonElement::getAsJsonObject)
                .anyMatch(w->text(w,"id").equals(worker.toString()) && w.has("x") && w.get("x").getAsDouble()==10));
            reconnect.sendText(publicFrame(worker,4,"execution.completed",resumeRef).toString(),true).join();
            assert text(JsonParser.parseString(resumedListener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject(),"type").equals("message.ack");
            await(()->state(host,resumed).equals("Complete"));
            JsonObject retry=waitCompletion.deepCopy();retry.addProperty("sequence",5);retry.addProperty("sentAt",java.time.Instant.now().toString());
            reconnect.sendText(retry.toString(),true).join();
            JsonObject duplicate=JsonParser.parseString(resumedListener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject();
            assert text(duplicate,"type").equals("message.ack") && text(duplicate.getAsJsonObject("payload"),"result").equals("duplicate");
            assert TaskFiles.read(directory.resolve("host-tasks.json")).getAsJsonObject("publicReportReceipts")
                .getAsJsonObject(worker.toString()).has(text(waitCompletion,"messageId"));
            JsonObject conflict=retry.deepCopy();conflict.addProperty("sequence",6);conflict.getAsJsonObject("payload").addProperty("generation",999);
            reconnect.sendText(conflict.toString(),true).join();
            JsonObject conflictError=JsonParser.parseString(resumedListener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject();
            assert text(conflictError,"type").equals("protocol.error") && text(conflictError.getAsJsonObject("payload"),"code").equals("message_id_conflict");
            reconnect.abort();
        }
        try(HostService reopened=new HostService(directory,"127.0.0.1",0,CREWS,30)){
            JsonObject reordered=new JsonObject();
            var keys=new java.util.ArrayList<>(waitCompletion.getAsJsonObject("payload").keySet());
            java.util.Collections.reverse(keys);
            for(String key:keys)reordered.add(key,waitCompletion.getAsJsonObject("payload").get(key).deepCopy());
            assert reopened.publicWorkerReport(worker,UUID.fromString(text(waitCompletion,"messageId")),"execution.completed",reordered)
                : "A report already committed before shutdown must be recognized after restart";
        }
        System.out.println("Public Wait/Travel checks passed: durable dispatch, arrival, cancellation, reconnect and report retries.");
    }
    private static void publicExtensionActionCheck() throws Exception {
        UUID worker=UUID.randomUUID(),job=UUID.randomUUID();
        Path directory=Files.createTempDirectory("monocle-public-extension-");
        String type="dev.example.inspect.v1",token="public-extension-secret-1234567890123456";
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            PublicWorkerGateway gateway=new PublicWorkerGateway(0,Map.of(worker,token),host)) {
            var listener=new java.net.http.WebSocket.Listener(){
                final java.util.concurrent.BlockingQueue<String> frames=new java.util.concurrent.LinkedBlockingQueue<>();
                @Override public void onOpen(java.net.http.WebSocket socket){socket.request(1);}
                @Override public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket socket,CharSequence text,boolean last){frames.add(text.toString());socket.request(1);return null;}
            };
            java.net.http.WebSocket socket=HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization","Bearer "+token)
                .buildAsync(URI.create("ws://127.0.0.1:"+gateway.getPort()+PublicWorkerGateway.PATH),listener).join();
            JsonObject hello=JsonParser.parseString("{supportedVersions:['rwp/1-draft'],implementation:{id:'dev.monocle.test',version:'1'},capabilities:[{id:'"+type+"'}],lastHostSequence:0,lastWorkerSequence:0,workerId:'"+worker+"'}").getAsJsonObject();
            socket.sendText(publicFrame(worker,0,"session.hello",hello).toString(),true).join();
            JsonObject accepted=JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject();
            assert text(accepted,"type").equals("session.accepted") && flag(accepted.getAsJsonObject("payload"),"extensionRouting");
            JsonObject observation=JsonParser.parseString("{observedAt:'2026-09-30T12:00:00Z',scope:{server:'play.example.org',dimension:'minecraft:the_nether'}}").getAsJsonObject();
            socket.sendText(publicFrame(worker,1,"worker.observation",observation).toString(),true).join();
            JsonObject reconcile=JsonParser.parseString("{lastHostSequence:0,lastWorkerSequence:1,activeExecutions:[]}").getAsJsonObject();
            socket.sendText(publicFrame(worker,2,"state.reconcile",reconcile).toString(),true).join();
            assert text(JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject(),"type").equals("state.reconciled");
            JsonObject move=op("crew-move");move.addProperty("crew","Default");move.addProperty("worker",worker.toString());host.control(move);
            JsonObject capabilities=ResourceApi.route(host,"GET",URI.create("/v1/capabilities"),new JsonObject(),null).body();
            assert flag(capabilities,"extensionRouting");
            String crewId=ResourceApi.route(host,"GET",URI.create("/v1/crews"),new JsonObject(),null).body().getAsJsonArray("items").asList().stream()
                .map(JsonElement::getAsJsonObject).filter(c->text(c,"name").equals("Default")).map(c->text(c,"id")).findFirst().orElseThrow();
            JsonObject submission=new JsonObject();submission.addProperty("id",job.toString());submission.addProperty("crewId",crewId);
            submission.addProperty("name","Public extension");submission.add("workerIds",new Gson().toJsonTree(List.of(worker.toString())));
            submission.add("scope",observation.get("scope").deepCopy());
            JsonObject action=JsonParser.parseString("{type:'"+type+"',arguments:{message:'keep this opaque',steps:[1,2,3]}}").getAsJsonObject();
            submission.add("action",action);
            JsonObject unsupported=submission.deepCopy();unsupported.addProperty("id",UUID.randomUUID().toString());
            unsupported.getAsJsonObject("action").addProperty("type","dev.example.other.v1");
            rejects(()->ResourceApi.route(host,"POST",URI.create("/v1/jobs"),unsupported,null));
            JsonObject reserved=submission.deepCopy();reserved.addProperty("id",UUID.randomUUID().toString());
            reserved.getAsJsonObject("action").addProperty("type","workers.fly.v1");
            rejects(()->ResourceApi.route(host,"POST",URI.create("/v1/jobs"),reserved,null));
            JsonObject oversized=submission.deepCopy();oversized.addProperty("id",UUID.randomUUID().toString());
            oversized.getAsJsonObject("action").getAsJsonObject("arguments").addProperty("message","x".repeat(8_192));
            rejects(()->ResourceApi.route(host,"POST",URI.create("/v1/jobs"),oversized,null));
            JsonObject privateTarget=submission.deepCopy();privateTarget.addProperty("id",UUID.randomUUID().toString());
            privateTarget.getAsJsonArray("workerIds").set(0,new JsonPrimitive(UUID.randomUUID().toString()));
            rejects(()->ResourceApi.route(host,"POST",URI.create("/v1/jobs"),privateTarget,null));
            JsonObject response=ResourceApi.route(host,"POST",URI.create("/v1/jobs"),submission,null).body();
            assert response.getAsJsonObject("action").equals(action) && !response.has("package");
            JsonObject assignment=JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject();
            assert text(assignment,"type").equals("execution.assign");
            JsonObject ref=assignment.getAsJsonObject("payload").deepCopy();
            assert ref.getAsJsonObject("action").equals(action) && !taskView(host,job).has("package");
            int sequence=3;
            for(String reportType:List.of("execution.accepted","execution.started","execution.completed")) {
                socket.sendText(publicFrame(worker,sequence++,reportType,ref).toString(),true).join();
                assert text(JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject(),"type").equals("message.ack");
            }
            await(()->state(host,job).equals("Complete"));
            assert text(taskView(host,job).getAsJsonObject("runs").getAsJsonObject(worker.toString()),"detail").contains("not independently game-verified");
            UUID uncertain=UUID.randomUUID();JsonObject second=submission.deepCopy();second.addProperty("id",uncertain.toString());
            ResourceApi.route(host,"POST",URI.create("/v1/jobs"),second,null);
            JsonObject secondAssignment=JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject();
            assert text(secondAssignment,"type").equals("execution.assign");
            JsonObject secondRef=secondAssignment.getAsJsonObject("payload").deepCopy();
            for(String reportType:List.of("execution.accepted","execution.started")) {
                socket.sendText(publicFrame(worker,sequence++,reportType,secondRef).toString(),true).join();
                assert text(JsonParser.parseString(listener.frames.poll(3,java.util.concurrent.TimeUnit.SECONDS)).getAsJsonObject(),"type").equals("message.ack");
            }
            await(()->state(host,uncertain).equals("Running"));
            host.publicWorkerReconcile(worker,reconcile); // A lost extension checkpoint must not replay an unknown side effect.
            assert text(taskView(host,uncertain).getAsJsonObject("runs").getAsJsonObject(worker.toString()),"status").equals("Inspection required");
            assert state(host,uncertain).equals("Inspection required");
            socket.abort();
        }
        try(HostService reopened=new HostService(directory,"127.0.0.1",0,CREWS,30)) {
            assert text(taskView(reopened,job).getAsJsonObject("publicAction"),"type").equals(type);
        }
        System.out.println("Public extension check passed: exact capability, opaque action, worker-reported outcome and restart persistence.");
    }
    private static void independentWorkerCheck() throws Exception {
        int node;
        try {
            Process probe=new ProcessBuilder("node","-p","Number(process.versions.node.split('.')[0])").start();
            node=Integer.parseInt(new String(probe.getInputStream().readAllBytes()).trim());
            if(probe.waitFor()!=0)throw new IllegalStateException("Cannot check Node version");
        } catch(java.io.IOException unavailable) {
            System.out.println("Independent worker check skipped: Node 26 is not installed.");return;
        }
        if(node<26){System.out.println("Independent worker check skipped: Node 26 is required.");return;}
        Path script=Path.of("src/test/rwp-mock-worker.mjs");assert Files.isRegularFile(script);
        UUID worker=UUID.randomUUID();String token="independent-worker-secret-1234567890123456";
        Path log=Files.createTempFile("rwp-mock-worker-",".log");
        try(HostService host=new HostService(Files.createTempDirectory("monocle-independent-worker-"),"127.0.0.1",0,CREWS,30);
            PublicWorkerGateway gateway=new PublicWorkerGateway(0,Map.of(worker,token),host)) {
            ProcessBuilder builder=new ProcessBuilder("node",script.toAbsolutePath().toString()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("RWP_URL","ws://127.0.0.1:"+gateway.getPort()+PublicWorkerGateway.PATH);
            builder.environment().put("RWP_TOKEN",token);builder.environment().put("RWP_WORKER_ID",worker.toString());
            builder.environment().put("RWP_SERVER","play.example.org");builder.environment().put("RWP_DIMENSION","minecraft:the_nether");
            Path probe=Path.of("src/test/rwp-conformance.mjs");assert Files.isRegularFile(probe);
            ProcessBuilder check=new ProcessBuilder("node",probe.toAbsolutePath().toString()).redirectErrorStream(true);
            check.environment().putAll(builder.environment());
            Process checked=check.start();
            if(!checked.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)){checked.destroyForcibly();throw new AssertionError("Independent RWP probe timed out");}
            assert checked.exitValue()==0 : new String(checked.getInputStream().readAllBytes());
            await(()->host.control(op("status")).getAsJsonArray("workers").asList().stream().map(JsonElement::getAsJsonObject)
                .anyMatch(w->text(w,"id").equals(worker.toString()) && !flag(w,"connected")));
            Process process=builder.start();
            try {
                await(()->host.control(op("status")).getAsJsonArray("workers").asList().stream().map(JsonElement::getAsJsonObject)
                    .anyMatch(w->text(w,"id").equals(worker.toString()) && flag(w,"connected") && flag(w,"reconciled")));
                JsonObject move=op("crew-move");move.addProperty("crew","Default");move.addProperty("worker",worker.toString());host.control(move);
                UUID wait=submitPublicWait(host,worker,2);await(()->state(host,wait).equals("Complete"));
                UUID travel=submitPublicTravel(host,worker);await(()->state(host,travel).equals("Complete"));
                UUID cancelled=submitPublicWait(host,worker,400);
                await(()->text(taskView(host,cancelled).getAsJsonObject("runs").getAsJsonObject(worker.toString()),"status").equals("Running"));
                JsonObject stop=op("cancel");stop.addProperty("id",cancelled.toString());host.control(stop);
                await(()->text(taskView(host,cancelled).getAsJsonObject("runs").getAsJsonObject(worker.toString()),"status").equals("Cancelled"));
                UUID restarted=submitPublicWait(host,worker,40);
                await(()->text(taskView(host,restarted).getAsJsonObject("runs").getAsJsonObject(worker.toString()),"status").equals("Running"));
                process.destroy();process.waitFor(2,java.util.concurrent.TimeUnit.SECONDS);
                await(()->host.control(op("status")).getAsJsonArray("workers").asList().stream().map(JsonElement::getAsJsonObject)
                    .anyMatch(w->text(w,"id").equals(worker.toString()) && !flag(w,"connected")));
                process=builder.start(); // A fresh implementation has no checkpoint; the host must issue a new generation.
                await(()->state(host,restarted).equals("Complete"));
                assert taskView(host,restarted).getAsJsonObject("runs").getAsJsonObject(worker.toString()).get("generation").getAsInt()==2;
                assert process.isAlive() : "Independent worker exited unexpectedly";
            } finally {
                process.destroy();if(!process.waitFor(2,java.util.concurrent.TimeUnit.SECONDS))process.destroyForcibly();
                if(Files.size(log)>0)System.err.println(Files.readString(log));
            }
        }
        System.out.println("Independent Node checks passed: host probe, Wait, simulated Travel, and cancellation without Monocle libraries.");
    }
    private static void handshakeRejected(java.util.concurrent.CompletableFuture<java.net.http.WebSocket> connection) {
        try { connection.join(); throw new AssertionError("Unauthorized public worker handshake accepted"); }
        catch (java.util.concurrent.CompletionException expected) { }
    }
    private static long connected(HostService host) { return host.control(op("status")).getAsJsonArray("workers").asList().stream().filter(w->flag(w.getAsJsonObject(),"connected")).count(); }
    private static boolean eventSeen(HostService host,String type,UUID worker) {
        return host.control(op("status")).getAsJsonArray("activity").asList().stream().map(JsonElement::getAsJsonObject)
            .anyMatch(event->type.equals(text(event,"event"))&&worker.toString().equals(text(event,"worker")));
    }

    private static void checkpointReleaseCheck() throws Exception {
        Path directory=Files.createTempDirectory("monocle-release-check-");UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);Worker first=new Worker(host.port(),KEY,a,new LinkedHashMap<>());Worker second=new Worker(host.port(),KEY,b,new LinkedHashMap<>())) {
            await(()->connected(host)==2,first,second);JsonObject request=highwayRequest(a,b);UUID job=UUID.fromString(text(request,"id"));host.control(request);
            await(()->first.nativeJob!=null && second.nativeJob!=null && first.begun && second.begun,first,second);
            first.row=second.row=7;first.announce();second.announce();await(()->taskView(host,job).has("highwayProgress") && taskView(host,job).get("highwayProgress").getAsInt()>=7,first,second);
            UUID remaining=UUID.randomUUID();JsonObject release=op("task-release");release.addProperty("id",job.toString());release.addProperty("newId",remaining.toString());host.control(release);host.control(release);
            JsonObject assign=op("draft-assign");assign.addProperty("id",remaining.toString());assign.addProperty("crew","Default");assign.add("workers",request.get("workers").deepCopy());rejects(()->host.control(assign));
            await(()->first.nativeJob==null && second.nativeJob==null && !flag(taskView(host,job),"cleanupPending"),first,second);
            assert taskView(host,job).has("nativeDefinition") : "History preserves geometry without retaining execution ownership";
            host.control(assign);await(()->first.nativeJob!=null && second.nativeJob!=null && first.begun && second.begun,first,second);
            assert first.row>=7 && second.row>=7 : "A reassigned crew starts at the verified checkpoint, not the origin";
            JsonObject end=op("cancel");end.addProperty("id",remaining.toString());host.control(end);await(()->first.nativeJob==null && second.nativeJob==null,first,second);
        }
        System.out.println("Native release checks passed: final cancellation, recovery-gated reassignment, immutable verified checkpoint and saved history geometry.");
    }

    private static void followPresetCheck() throws Exception {
        try(HostService host=new HostService(Files.createTempDirectory("monocle-follow-host-"),"127.0.0.1",0,CREWS,30);
            Worker leader=new Worker(host.port(),KEY,UUID.randomUUID(),new LinkedHashMap<>());
            Worker follower=new Worker(host.port(),KEY,UUID.randomUUID(),new LinkedHashMap<>());
            Worker stranger=new Worker(host.port(),OTHER_KEY,UUID.randomUUID(),new LinkedHashMap<>())) {
            await(()->connected(host)==3,leader,follower,stranger);
            JsonObject args=new JsonObject();args.addProperty("target",leader.id.toString());args.addProperty("radius",3);
            JsonObject prepare=op("workflow-prepare");prepare.addProperty("id","task-follow");prepare.addProperty("scope","test.invalid\nminecraft:the_nether");prepare.add("args",args);
            JsonObject packet=host.control(prepare),request=op("submit");request.addProperty("id",UUID.randomUUID().toString());request.addProperty("name","Follow leader");request.addProperty("crew","Default");request.addProperty("server","test.invalid");request.addProperty("dimension","minecraft:the_nether");request.add("package",packet);request.add("args",args);
            JsonArray workers=new JsonArray();workers.add(follower.id.toString());request.add("workers",workers);
            JsonObject invalid=request.deepCopy();invalid.getAsJsonObject("args").addProperty("target",follower.id.toString());rejects(()->host.control(invalid));
            JsonObject foreign=request.deepCopy();foreign.getAsJsonObject("args").addProperty("target",stranger.id.toString());rejects(()->host.control(foreign));
            host.control(request);
            JsonObject task=host.control(op("status")).getAsJsonArray("tasks").get(0).getAsJsonObject();
            assert task.getAsJsonObject("runs").has(follower.id.toString())&&!task.getAsJsonObject("runs").has(leader.id.toString());
            assert !packet.getAsJsonObject("profiles").getAsJsonObject("Current").getAsJsonObject("kill-aura").get("active").getAsBoolean();
        }
    }
    private static void operationsCheck() throws Exception {
        UUID released=UUID.randomUUID(),pending=UUID.randomUUID();
        JsonObject old=JsonParser.parseString("{status:'Cancelled',runs:{'"+released+"':{status:'Cancelled'},'"+pending+"':{status:'Inspection required'}}}").getAsJsonObject();
        assert !HostService.workerJobPending(old,released) && HostService.workerJobPending(old,pending);
        old.addProperty("status","Running");assert HostService.workerJobPending(old,released);
        Path directory=Files.createTempDirectory("monocle-operations-host-");
        OperatorOperations probe=new OperatorOperations(directory.resolve("receipt-probe.json"));UUID target=UUID.randomUUID();
        JsonObject receiptTask=JsonParser.parseString("{status:'Running',runs:{'"+target+"':{id:'"+UUID.randomUUID()+"',status:'Running'}}}").getAsJsonObject();String first="";
        for(int i=0;i<35;i++) {
            String command=UUID.randomUUID().toString();if(i==0)first=command;probe.begin(command,"probe-"+i);probe.finish(command,202,new JsonObject(),true);
            CommandReceipts.request(receiptTask,command,i%2==0?"pause":"resume",target);
            for(var entry:receiptTask.getAsJsonObject("controlReceipts").entrySet())probe.observe(entry.getKey(),entry.getValue().getAsJsonObject());
            CommandReceipts.prune(receiptTask);
        }
        assert !receiptTask.getAsJsonObject("controlReceipts").has(first)&&text(new OperatorOperations(directory.resolve("receipt-probe.json")).get(first),"state").equals("superseded") : "Archive terminal HTTP evidence before compacting task receipts";
        JsonObject config=new JsonObject();config.addProperty("apiToken",TOKEN);config.addProperty("apiPort",6970);config.addProperty("workerPort",6969);config.addProperty("bind","127.0.0.1");config.addProperty("historyDays",30);config.add("crews",new Gson().toJsonTree(CREWS));TaskFiles.write(directory.resolve("host-config.json"),config);
        UUID workerId=UUID.randomUUID();String crewId="crew-"+UUID.randomUUID();JsonObject create=op("crew-create");create.addProperty("id",crewId.substring(5));create.addProperty("name","Scouts");
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);Worker worker=new Worker(host.port(),KEY,workerId,new LinkedHashMap<>())) {
            await(()->connected(host)==1,worker);
            JsonObject highway=op("submit-highway");highway.addProperty("id",UUID.randomUUID().toString());highway.addProperty("name","Westbound highway");highway.addProperty("crew","Default");
            JsonArray highwayWorkers=new JsonArray();highwayWorkers.add(workerId.toString());highway.add("workers",highwayWorkers);highway.addProperty("server","test.invalid");highway.addProperty("dimension","minecraft:the_nether");highway.addProperty("priority",0);
            highway.addProperty("x",123);highway.addProperty("y",116);highway.addProperty("z",-456);highway.addProperty("length",10000);highway.addProperty("direction","West");
            JsonObject prepared=host.highwaySubmission(highway),geometry=prepared.getAsJsonObject("package").getAsJsonObject("geometry"),layout=geometry.getAsJsonObject("layout");
            assert text(prepared,"op").equals("submit") && text(geometry,"scope").equals("test.invalid\nminecraft:the_nether");
            assert geometry.get("x").getAsInt()==123 && geometry.get("y").getAsInt()==116 && geometry.get("z").getAsInt()==-456;
            assert layout.get("dx").getAsInt()==-1 && layout.get("dz").getAsInt()==0 && text(layout,"heading").equals("West") && layout.get("width").getAsInt()==5 && layout.get("height").getAsInt()==3;
            assert prepared.getAsJsonObject("args").get("length").getAsInt()==10000 && prepared.getAsJsonObject("package").getAsJsonObject("profiles").has("Current");
            JsonObject invalid=highway.deepCopy();invalid.addProperty("direction","Diagonal");rejects(()->host.highwaySubmission(invalid));
            host.control(create);host.control(create);assert host.control(op("status")).getAsJsonArray("crews").size()==3;
            JsonObject rename=op("crew-rename");rename.addProperty("crew",crewId);rename.addProperty("name","Scout crew");host.control(rename);
            assert host.control(op("status")).getAsJsonObject("crewLabels").get(crewId).getAsString().equals("Scout crew");
            JsonObject packet=op("workflow-get");packet.addProperty("id","task-wait");JsonObject exported=host.control(packet).getAsJsonObject("package");
            JsonObject draft=new JsonObject();UUID job=UUID.randomUUID();draft.addProperty("id",job.toString());draft.addProperty("name","Queued wait");draft.addProperty("server","test.invalid");draft.addProperty("dimension","minecraft:the_nether");draft.addProperty("priority",0);draft.add("args",new JsonObject());draft.add("package",exported);
            JsonObject save=op("draft-save");save.add("draft",draft);host.control(save);assert host.control(op("status")).getAsJsonArray("tasks").isEmpty();assert host.control(op("status")).getAsJsonArray("drafts").size()==1;
            JsonObject assign=op("draft-assign");assign.addProperty("id",job.toString());assign.addProperty("crew","Default");JsonArray targets=new JsonArray();targets.add(workerId.toString());assign.add("workers",targets);host.control(assign);host.control(assign);
            await(()->state(host,job).equals("Running"),worker);assert host.control(op("status")).getAsJsonArray("drafts").isEmpty();
            JsonObject move=op("crew-move");move.addProperty("crew",crewId);move.addProperty("worker",workerId.toString());rejects(()->host.control(move));
            JsonObject cancel=op("cancel");cancel.addProperty("id",job.toString());host.control(cancel);await(()->!flag(taskView(host,job),"cleanupPending"),worker);
            String moveId=UUID.randomUUID().toString();synchronized(host){host.operatorOperations().begin(moveId,"move-probe");host.operatorCommand(moveId);try{host.control(move);}finally{host.operatorCommand("");}host.operatorOperations().finish(moveId,202,new JsonObject(),true);}
            assert text(host.operatorOperation(moveId),"state").equals("delivery_queued") : "Sending assignment is not a completed crew move";
            await(()->!worker.assignedKey.isEmpty(),worker);String key=worker.assignedKey;assert key.equals(TaskFiles.read(directory.resolve("host-config.json")).getAsJsonObject("crews").get(crewId).getAsString());
            worker.close();try(Worker reconnected=new Worker(host.port(),key,workerId,new LinkedHashMap<>())) {
                await(()->host.control(op("status")).getAsJsonArray("workers").asList().stream().anyMatch(w->text(w.getAsJsonObject(),"crew").equals(crewId) && flag(w.getAsJsonObject(),"connected")),reconnected);
                await(()->text(host.operatorOperation(moveId),"state").equals("completed"),reconnected);
                assert host.control(op("status")).getAsJsonArray("workers").size()==1 : "Reconnects deduplicate the persistent roster";
                JsonObject delete=op("crew-delete");delete.addProperty("crew",crewId);rejects(()->host.control(delete));
            }
            JsonObject setting=op("host-settings");setting.addProperty("historyDays",7);host.control(setting);assert host.control(op("status")).get("historyDays").getAsInt()==7;
            assert !host.control(op("status")).toString().contains(key) : "Status never reveals crew credentials";
        }
        Map<String,String> crews=new LinkedHashMap<>();TaskFiles.read(directory.resolve("host-config.json")).getAsJsonObject("crews").entrySet().forEach(e->crews.put(e.getKey(),e.getValue().getAsString()));
        try(HostService host=new HostService(directory,"127.0.0.1",0,crews,7)) {
            assert host.control(op("status")).getAsJsonObject("crewLabels").get(crewId).getAsString().equals("Scout crew");
            assert host.control(op("status")).getAsJsonArray("workers").size()==1;
            assert !flag(host.control(op("status")).getAsJsonArray("workers").get(0).getAsJsonObject(),"positionFresh");
            JsonObject delete=op("crew-delete");delete.addProperty("crew",crewId);host.control(delete);
        }
        System.out.println("Operations host checks passed: crew identity/credentials, immutable drafts, job assignment, protected moves, authenticated reconnect, offline roster and retention.");
    }

    private static void externalTpaCheck() throws Exception {
        Path directory=Files.createTempDirectory("monocle-external-tpa-host-");
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            Worker worker=new Worker(host.port(),KEY,UUID.randomUUID(),new LinkedHashMap<>())) {
            await(()->connected(host)==1,worker);
            JsonObject task=submit(worker.id,0);UUID taskId=UUID.fromString(text(task,"id"));host.control(task);
            await(()->state(host,taskId).equals("Running"),worker);
            JsonObject run=worker.checkpoints.get(UUID.fromString(worker.current)),action=new JsonObject();
            action.addProperty("type","Tpa");action.addProperty("external",true);action.addProperty("targetName","Recipient");action.addProperty("target",UUID.randomUUID().toString());
            run.add("action",action);worker.sendStatus(run);
            await(()->worker.tpaSends.size()==1,worker);
            JsonObject sent=worker.tpaSends.getFirst();
            assert text(sent,"type").equals("task-tpa-send") && text(sent,"run").equals(text(run,"run")) && text(sent,"token").equals(text(run,"token"));
            assert text(sent,"name").equals("Recipient") && text(sent,"target").equals(text(action,"target")) && text(sent,"dimension").equals("minecraft:the_nether");
            for(int i=0;i<20;i++){worker.pump();Thread.sleep(10);}
            assert worker.tpaSends.size()==1 : "External TPA is sent once, with a single task prefix, without requiring a host-connected recipient";
        }
    }

    private static void autoTpyCheck() throws Exception {
        Path directory=Files.createTempDirectory("monocle-auto-tpy-host-");
        JsonObject config=new JsonObject();config.addProperty("historyDays",30);config.addProperty("autoTpy",true);config.add("crews",new Gson().toJsonTree(CREWS));TaskFiles.write(directory.resolve("host-config.json"),config);
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            Worker requester=new Worker(host.port(),KEY,UUID.randomUUID(),new LinkedHashMap<>());
            Worker target=new Worker(host.port(),KEY,UUID.randomUUID(),new LinkedHashMap<>());
            Worker outsider=new Worker(host.port(),OTHER_KEY,UUID.randomUUID(),new LinkedHashMap<>())) {
            requester.name="Requester";target.name=outsider.name="Target";requester.announce();target.announce();outsider.announce();
            await(()->connected(host)==3&&requester.autoTpy&&target.autoTpy&&outsider.autoTpy,requester,target,outsider);
            long started=System.nanoTime();requester.requestTpa("Target");
            await(()->target.tpaAccepts.size()==1,requester,target,outsider);
            assert System.nanoTime()-started>=400_000_000L : "Auto TPY must preserve the requested ten-tick ordering delay";
            assert outsider.tpaAccepts.isEmpty()&&text(target.tpaAccepts.getFirst(),"requester").equals("Requester") : "Auto TPY stays inside the authenticated crew";
            JsonObject setting=op("host-settings");setting.addProperty("autoTpy",false);host.control(setting);
            await(()->!requester.autoTpy&&!target.autoTpy,requester,target,outsider);
            requester.requestTpa("Target");for(int i=0;i<80;i++){requester.pump();target.pump();outsider.pump();Thread.sleep(10);}
            assert target.tpaAccepts.size()==1&&!TaskFiles.read(directory.resolve("host-config.json")).get("autoTpy").getAsBoolean() : "Disabled host policy persists and sends no acceptance";
            JsonObject args=new JsonObject();args.addProperty("target",target.id.toString());args.addProperty("radius",3);
            JsonObject prepare=op("workflow-prepare");prepare.addProperty("id","task-bodyguard");prepare.addProperty("scope",requester.scope);prepare.add("args",args);
            JsonObject submit=op("submit");submit.addProperty("id",UUID.randomUUID().toString());submit.addProperty("name","Protect Target");submit.addProperty("crew","Default");submit.addProperty("server","test.invalid");submit.addProperty("dimension","minecraft:the_nether");submit.addProperty("priority",0);submit.add("args",args);submit.add("package",host.control(prepare));JsonArray guards=new JsonArray();guards.add(requester.id.toString());submit.add("workers",guards);host.control(submit);
            requester.requestBodyguardTpa("Target",target.id);await(()->target.tpaAccepts.size()==2,requester,target,outsider);
            assert outsider.tpaAccepts.isEmpty():"Bodyguard recovery accepts only on the protected same-crew client, even with general Auto TPY disabled";
        }
        System.out.println("Auto TPY checks passed: host-owned policy, exact same-crew routing, durable disable and authenticated bodyguard recovery.");
    }

    /** Real coordinator and Lua workflow; inventory/terrain outcomes are deliberately simulated. */
    private static void faultReplayCheck() throws Exception {
        Path dir=Files.createTempDirectory("monocle-fault-replay-");
        String scope="test.invalid\nminecraft:the_nether";
        UUID workerId=UUID.randomUUID();Map<UUID,JsonObject> checkpoints=new LinkedHashMap<>();
        JsonObject source=StashCatalog.plan(JsonParser.parseString("{name:'Source',homeName:'source',minX:0,maxX:0,minY:116,maxY:116,minZ:0,maxZ:0,scanMode:'Full',lazyMode:false}").getAsJsonObject());
        JsonObject destination=source.deepCopy();destination.addProperty("name","Target");destination.addProperty("homeName","target");destination.addProperty("minX",100);destination.addProperty("maxX",102);
        JsonObject box=JsonParser.parseString("{slot:0,item:'minecraft:blue_shulker_box',quantity:1,name:'Replay kit',contentsKnown:true,items:{'minecraft:obsidian':1728}}").getAsJsonObject();
        JsonObject stock=JsonParser.parseString("{x:0,y:116,z:0,status:'observed',block:'minecraft:chest',items:{'minecraft:blue_shulker_box':1,'minecraft:obsidian':1728},shulkers:[]}").getAsJsonObject();stock.getAsJsonArray("shulkers").add(box);
        JsonObject secondBox=box.deepCopy();secondBox.addProperty("slot",1);
        JsonObject sourceStock=stock.deepCopy();sourceStock.getAsJsonArray("shulkers").add(secondBox);sourceStock.add("items",JsonParser.parseString("{'minecraft:blue_shulker_box':2,'minecraft:obsidian':3456}"));
        StashCatalog.save(dir,"Default",scope,source,sourceStock);
        JsonObject empty=stock.deepCopy();empty.addProperty("x",100);empty.add("items",new JsonObject());empty.add("shulkers",new JsonArray());
        StashCatalog.save(dir,"Default",scope,destination,empty);
        String kit=StashCatalog.kitTypeId(box),script;JsonObject args,luaState,retry,firstAck;UUID jobId,execution;
        try(HostService host=new HostService(dir,"127.0.0.1",0,CREWS,30);Worker worker=new Worker(host.port(),KEY,workerId,checkpoints)) {
            await(()->connected(host)==1,worker);
            args=source.deepCopy();args.addProperty("kitTypeId",kit);args.addProperty("count",2);args.addProperty("destination","Stash");args.addProperty("targetStashName","Target");
            JsonObject prepare=op("workflow-prepare");prepare.addProperty("id","task-kit-delivery");prepare.addProperty("scope",scope);prepare.add("args",args);
            JsonObject job=submit(workerId,0);job.remove("script");job.add("args",args);job.add("package",host.control(prepare));host.control(job);jobId=UUID.fromString(text(job,"id"));
            await(()->state(host,jobId).equals("Running"),worker);execution=UUID.fromString(worker.current);
            JsonObject envelope=worker.installedEnvelope;script=text(envelope.getAsJsonObject("programs").getAsJsonObject(text(envelope,"entry")),"script");args=envelope.getAsJsonObject("dispatch").getAsJsonObject("args").deepCopy();
            var first=BotLua.next(script,new JsonObject(),args,null,null);assert text(first.action(),"type").equals("StashResupply");
            var deposit=BotLua.next(script,first.state(),args,JsonParser.parseString("{picked:1,nextPick:0,sourceExhausted:false}").getAsJsonObject(),null);luaState=deposit.state();
            replayAction(host,worker,jobId,deposit.action());
            JsonObject run=checkpoints.get(execution),reserve=replayFinding(jobId,run,1,null);worker.c.send(reserve.toString());await(()->worker.stashAcks==1,worker);
            empty.addProperty("x",worker.lastStashAck.getAsJsonObject("column").get("x").getAsInt());
            worker.c.send(replayFinding(jobId,run,2,empty).toString());await(()->worker.stashAcks==2,worker);assert flag(worker.lastStashAck,"permit");
            JsonObject deposited=stock.deepCopy();deposited.addProperty("x",empty.get("x").getAsInt());
            retry=replayFinding(jobId,run,3,deposited);worker.c.send(retry.toString());
            // Wait for the durable host commit without consuming its socket reply: the ACK is lost.
            await(()->StashCatalog.summary(StashCatalog.get(dir,"Default",scope,"Target")).getAsJsonObject("items").has("minecraft:obsidian"));
            assert worker.stashAcks==2;
            JsonObject get=op("task-get");get.addProperty("id",jobId.toString());firstAck=host.control(get).getAsJsonObject("runs").getAsJsonObject(workerId.toString()).getAsJsonObject("depositAck").deepCopy();
            run.addProperty("status","Suspended");
        }
        try(HostService host=new HostService(dir,"127.0.0.1",0,CREWS,30);Worker worker=new Worker(host.port(),KEY,workerId,checkpoints)) {
            await(()->connected(host)==1,worker);assert state(host,jobId).equals("Inspection required");assert worker.installs==0&&worker.resumes==0;
            JsonObject resume=op("resume");resume.addProperty("id",jobId.toString());host.control(resume);await(()->state(host,jobId).equals("Running"),worker);
            assert worker.current.equals(execution.toString())&&worker.installs==0 : "Restart resumes ownership, never reinstalls side effects";
            worker.c.send(retry.toString());await(()->worker.stashAcks==1,worker);assert worker.lastStashAck.equals(firstAck) : "Lost ACK retry returns the durable lease and approval";
            assert StashCatalog.summary(StashCatalog.get(dir,"Default",scope,"Target")).getAsJsonObject("items").get("minecraft:obsidian").getAsInt()==1728 : "A replay is not another deposit";
            var next=BotLua.next(script,luaState,args,JsonParser.parseString("{deposited:1,missing:0}").getAsJsonObject(),null);
            assert text(next.action(),"type").equals("StashResupply") : "A short load does not complete the two-kit job";
            replayAction(host,worker,jobId,next.action());
            var deposit=BotLua.next(script,next.state(),args,JsonParser.parseString("{picked:1,nextPick:1,sourceExhausted:true}").getAsJsonObject(),null);
            replayAction(host,worker,jobId,deposit.action());JsonObject run=checkpoints.get(execution);
            int before=worker.stashAcks;worker.c.send(replayFinding(jobId,run,1,null).toString());await(()->worker.stashAcks==before+1,worker);
            JsonObject both=stock.deepCopy();both.addProperty("x",worker.lastStashAck.getAsJsonObject("column").get("x").getAsInt());
            both.getAsJsonArray("shulkers").add(secondBox.deepCopy());both.add("items",JsonParser.parseString("{'minecraft:blue_shulker_box':2,'minecraft:obsidian':3456}"));
            worker.c.send(replayFinding(jobId,run,2,both).toString());await(()->worker.stashAcks==before+2,worker);assert flag(worker.lastStashAck,"permit");
            // A delayed observation from the prior action must not replace the newer stock.
            worker.c.send(retry.toString());worker.announce();await(()->connected(host)==1,worker);
            var done=BotLua.next(script,deposit.state(),args,JsonParser.parseString("{deposited:1,missing:0}").getAsJsonObject(),null);
            assert text(done.action(),"type").equals("Done")&&done.action().getAsJsonObject("result").get("delivered").getAsInt()==2;
            replayAction(host,worker,jobId,done.action());await(()->state(host,jobId).equals("Complete"),worker);
            assert StashCatalog.summary(StashCatalog.get(dir,"Default",scope,"Target")).getAsJsonObject("items").get("minecraft:obsidian").getAsInt()==3456;
            assert taskView(host,jobId).getAsJsonObject("kitJob").get("delivered").getAsInt()==2&&worker.installs==0;
        }
        // Cancellation during uncertain cleanup remains authoritative through an offline host restart.
        UUID cancelled;
        try(HostService host=new HostService(dir,"127.0.0.1",0,CREWS,30);Worker worker=new Worker(host.port(),KEY,workerId,checkpoints)) {
            await(()->connected(host)==1,worker);JsonObject job=submit(workerId,0);host.control(job);cancelled=UUID.fromString(text(job,"id"));await(()->state(host,cancelled).equals("Running"),worker);
            worker.holdCancellation=true;host.control(opWithId("cancel",cancelled));
            await(()->text(taskView(host,cancelled).getAsJsonObject("runs").getAsJsonObject(workerId.toString()),"status").equals("Inspection required"),worker);
            assert state(host,cancelled).equals("Cancelled");rejects(()->host.control(opWithId("resume",cancelled)));
        }
        try(HostService host=new HostService(dir,"127.0.0.1",0,CREWS,30);Worker worker=new Worker(host.port(),KEY,workerId,checkpoints)) {
            worker.holdCancellation=true;await(()->connected(host)==1,worker);
            assert state(host,cancelled).equals("Cancelled")&&worker.installs==0&&worker.resumes==0 : "Reconnect must never revive cancelled work";
            assert CommandReceipts.summary(taskView(host,cancelled)).contains("outcome uncertain");
            worker.holdCancellation=false;await(()->CommandReceipts.summary(taskView(host,cancelled)).contains("completed"),worker);
            assert worker.installs==0&&worker.resumes==0;
        }
        // An executed run whose worker journal disappears cannot be automatically recreated.
        UUID lost;
        try(HostService host=new HostService(dir,"127.0.0.1",0,CREWS,30);Worker worker=new Worker(host.port(),KEY,workerId,checkpoints)) {
            await(()->connected(host)==1,worker);JsonObject job=submit(workerId,0);host.control(job);lost=UUID.fromString(text(job,"id"));await(()->state(host,lost).equals("Running"),worker);
        }
        try(HostService host=new HostService(dir,"127.0.0.1",0,CREWS,30);Worker worker=new Worker(host.port(),KEY,workerId,new LinkedHashMap<>())) {
            await(()->connected(host)==1,worker);assert state(host,lost).equals("Inspection required")&&worker.installs==0&&worker.resumes==0;
            host.control(opWithId("cancel",lost));await(()->CommandReceipts.summary(taskView(host,lost)).contains("completed"),worker);
            assert state(host,lost).equals("Cancelled")&&worker.installs==0&&worker.resumes==0 : "An authenticated absent checkpoint settles cancellation without replay";
        }
        System.out.println("Fault replay passed: two-load Lua kit delivery, lost ACK + host restart, stale action report, cancellation + uncertain cleanup + reconnect.");
    }
    private static JsonObject opWithId(String op,UUID id) {JsonObject request=op(op);request.addProperty("id",id.toString());return request;}
    private static JsonObject replayFinding(UUID job,JsonObject run,int delivery,JsonObject observation) {
        JsonObject report=message("stash-findings");report.addProperty("task",job.toString());report.add("run",run.get("run"));report.add("token",run.get("token"));report.add("action",run.get("action").deepCopy());report.addProperty("delivery",delivery);if(observation!=null)report.add("observation",observation.deepCopy());return report;
    }
    private static void replayAction(HostService host,Worker worker,UUID job,JsonObject action) throws Exception {
        JsonObject run=worker.checkpoints.get(UUID.fromString(worker.current));
        if(text(action,"type").equals("Done")){run.addProperty("status","Complete");run.add("workflowResult",action.get("result").deepCopy());run.remove("action");run.remove("token");worker.current="";worker.sendStatus(run);worker.heartbeat();return;}
        run.add("action",action.deepCopy());run.addProperty("token",UUID.randomUUID().toString());worker.sendStatus(run);
        await(()->text(taskView(host,job).getAsJsonObject("runs").getAsJsonObject(worker.id.toString()),"token").equals(text(run,"token")),worker);
    }
    private static void stashSortControlCheck()throws Exception{
        Path root=Files.createTempDirectory("sort-host-");String scope="test.invalid\nminecraft:the_nether";UUID id=UUID.randomUUID();
        JsonObject bounds=JsonParser.parseString("{name:'Sort',homeName:'sort',minX:0,maxX:6,minY:116,maxY:116,minZ:100,maxZ:100,lazyMode:false}").getAsJsonObject();
        for(int x=0;x<8;x+=2){JsonObject o=JsonParser.parseString("{y:116,z:100,status:'observed',block:'minecraft:chest',reason:'',capacity:27,slotCount:27,occupiedSlots:0,items:{},shulkers:[]}").getAsJsonObject();o.addProperty("x",x);if(x==2){o.addProperty("occupiedSlots",1);o.add("items",JsonParser.parseString("{'minecraft:black_shulker_box':1,'minecraft:obsidian':1728}"));o.getAsJsonArray("shulkers").add(JsonParser.parseString("{slot:0,item:'minecraft:black_shulker_box',name:'Obby',contentsKnown:true,quantity:1,items:{'minecraft:obsidian':1728}}"));}StashCatalog.save(root,"Default",scope,bounds,o);}
        JsonObject cats=KitCategories.get(root,"Default",scope),category=KitCategories.definition("storage-obsidian","Obsidian","Items",List.of("minecraft:obsidian"));category.addProperty("usage","Storage");KitCategories.save(root,"Default",scope,text(cats,"revision"),category);
        try(HostService host=new HostService(root,"127.0.0.1",0,CREWS,30);Worker worker=new Worker(host.port(),KEY,id,new LinkedHashMap<>())){
            await(()->connected(host)==1,worker);JsonObject preview=op("stash-sort-preview");preview.addProperty("crew","Default");preview.addProperty("scope",scope);preview.addProperty("name","Sort");preview.addProperty("buffers",1);preview.addProperty("growthColumns",1);
            JsonObject plan=host.control(preview),start=preview.deepCopy();start.addProperty("op","stash-sort-start");start.addProperty("id",UUID.randomUUID().toString());start.addProperty("worker",id.toString());start.addProperty("planId",text(plan,"planId"));rejects(()->host.control(start));
            worker.sortSupported=true;worker.heartbeat();await(()->host.control(op("status")).getAsJsonArray("workers").asList().stream().anyMatch(w->flag(w.getAsJsonObject(),"stashSortSupported")),worker);
            JsonObject stale=start.deepCopy();stale.addProperty("planId","old");rejects(()->host.control(stale));
            UUID job=UUID.fromString(text(host.control(start),"id"));assert text(host.control(start),"id").equals(job.toString()):"Sort starts must be idempotent";
            await(()->worker.lastKitPlan!=null,worker);assert flag(worker.lastKitPlan,"success")&&text(worker.lastKitPlan.getAsJsonObject("result").getAsJsonObject("action"),"type").equals("StashScan");
            JsonObject view=taskView(host,job);assert flag(view.getAsJsonObject("stashSort"),"leased")&&view.getAsJsonObject("stashSort").get("columns").isJsonPrimitive():"Status exposes a compact journal summary";
            rejects(()->host.control(submit(id,0)));
            JsonObject edit=op("kit-categories-save");edit.addProperty("crew","Default");edit.addProperty("scope",scope);edit.addProperty("expected",text(KitCategories.get(root,"Default",scope),"revision"));edit.add("category",category);rejects(()->host.control(edit));
            JsonObject failed=worker.checkpoints.values().iterator().next();String failedRun=text(failed,"run");failed.addProperty("status","Failed");failed.remove("action");failed.remove("token");worker.current="";worker.sendStatus(failed);worker.heartbeat();await(()->text(taskView(host,job),"status").equals("Failed"),worker);
            assert flag(taskView(host,job).getAsJsonObject("stashSort"),"retryableRead");host.control(opWithId("resume",job));String retryRun=text(taskView(host,job).getAsJsonObject("runs").getAsJsonObject(id.toString()),"id");assert !failedRun.equals(retryRun):"A failed read needs a new worker run, not a replayed terminal checkpoint";host.control(opWithId("resume",job));assert retryRun.equals(text(taskView(host,job).getAsJsonObject("runs").getAsJsonObject(id.toString()),"id"));await(()->worker.installs==2&&!worker.current.isEmpty(),worker);worker.sendStatus(failed);await(()->text(taskView(host,job),"status").equals("Running"),worker);
            host.control(opWithId("cancel",job));await(()->BotHistory.taskFinished(taskView(host,job),false),worker);
            rejects(()->host.control(submit(id,0)));rejects(()->host.control(opWithId("delete",job)));
            JsonObject release=opWithId("stash-sort-release",job);rejects(()->host.control(release));release.addProperty("confirmCargoRecovered",true);assert !flag(host.control(release),"leased");host.control(opWithId("delete",job));
        }
        System.out.println("Sort host checks passed: capability gate, stale preview, idempotent start, real plan socket, exclusive lease and explicit cancelled-cargo release.");
    }
    private static final class Worker implements AutoCloseable {
        final SwarmConnection c;
        final UUID id;
        final Map<UUID, JsonObject> checkpoints;
        String current = "", digest, transfer;
        final List<String> chunks = new ArrayList<>();
        final List<String> chatMessages = new ArrayList<>();
        final List<Long> chatTimes = new ArrayList<>();
        final List<JsonObject> tpaAccepts = new ArrayList<>();
        final List<JsonObject> tpaSends = new ArrayList<>();
        int count, installs, resumes;
        int stashAcks;
        JsonObject lastStashAck;
        JsonObject lastKitPlan;
        JsonObject installedEnvelope;
        boolean holdCancellation;
        JsonArray stashCatalog=new JsonArray();
        int stashCatalogReports;
        long announceAt;
        JsonObject nativeJob;
        JsonObject ledger, exchangeState, offer;
        JsonObject roadForecast;
        boolean hostilesSupported;
        boolean hostileWarningsSupported, inLobby;
        boolean safeReconnectSupported;
        JsonArray playerSightings;
        JsonObject playerChecks;
        final List<JsonObject> hostileWarnings=new ArrayList<>();
        int playerCheckReceipts;
        JsonObject combatOverride, hostileCatalog;
        int hostileFrames;
        JsonObject serviceFront;
        boolean recoveryOnly, recoveryReady=true;
        boolean reconnectReady=true;
        boolean connectionStopped;
        int supplyProtocol = 2;
        String scope="test.invalid\nminecraft:the_nether";
        boolean liveStashes;
        boolean sortSupported;
        int stashContainerLimit;
        final List<JsonObject> stashWatch=new ArrayList<>(),liveAcks=new ArrayList<>(),liveBatchAcks=new ArrayList<>(),exportAcks=new ArrayList<>();
        String name = "Worker";
        boolean hasPosition = true;
        int restores;
        int row;
        boolean begun, advance, detached, returning, ignoreEnd, nativePaused, moduleOff;
        boolean ignoreJoin;
        boolean autoTpy;
        boolean delayLanding;
        boolean delayWithdrawal;int withdrawalRequests;
        int frontUpdates;
        int rejectedJoins;
        int nativeResumes, inspections;
        String supplyLock="";
        String assignedKey="";
        Worker(int port, String key, UUID id, Map<UUID, JsonObject> checkpoints) throws Exception {
            this(CrewTransport.worker("127.0.0.1", port), key, id, checkpoints);
        }
        Worker(String endpoint, String key, UUID id, Map<UUID, JsonObject> checkpoints) throws Exception {
            this(CrewTransport.worker(endpoint, 6969), key, id, checkpoints);
        }
        private Worker(CrewTransport transport, String key, UUID id, Map<UUID, JsonObject> checkpoints) throws Exception {
            this.id = id; this.checkpoints = checkpoints;
            c = new SwarmConnection(transport, key, false); c.start();
            await(c::connected);
            announce();
        }
        private void announce() {
            JsonObject hello = new JsonObject(); hello.addProperty("type", "hello"); hello.addProperty("id", id.toString()); hello.addProperty("name", name);
            hello.addProperty("taskProtocol", 1); hello.addProperty("scope", scope); hello.addProperty("job", "");
            JsonObject readiness=new JsonObject();readiness.addProperty("state",inLobby?"Lobby":"Ready");readiness.addProperty("reason","Simulated world readiness");hello.add("worldReadiness",readiness);
            hello.addProperty("available",nativeJob==null&&current.isEmpty());
            hello.addProperty("chatProtocol",1);
            hello.addProperty("reconnectReady",reconnectReady); hello.addProperty("supplyProtocol",supplyProtocol);
            hello.addProperty("initialStockProtocol",1); hello.addProperty("initialStockReady",true);
            hello.addProperty("connectionStopped",connectionStopped);
            JsonObject diagnostics=new JsonObject();diagnostics.addProperty("version",1);diagnostics.addProperty("tickAgeMs",17);hello.add("diagnostics",diagnostics);
            hello.add("combat",combatOverride==null?JsonParser.parseString("{version:1,sequence:1,lastDamage:{kind:'damage',at:1000,cause:'explosion',attribution:'unknown'}}"):combatOverride.deepCopy());
            if(hostilesSupported)hello.addProperty("knownHostilesProtocol",1);
            if(hostileWarningsSupported)hello.addProperty("hostileWarningsProtocol",1);
            hello.addProperty("inLobby",inLobby);
            if(playerChecks!=null)hello.add("playerChecks",playerChecks.deepCopy());
            if(safeReconnectSupported)hello.addProperty("safeReconnectProtocol",1);
            if(playerSightings!=null)hello.add("playerSightings",playerSightings.deepCopy());
            if (hasPosition) { hello.addProperty("x", 0); hello.addProperty("y", 116); hello.addProperty("z", 100); }
            if(nativeJob!=null) {
                hello.addProperty("assignmentLoaded",!recoveryOnly); hello.addProperty("recoveryReady",recoveryReady);
                hello.addProperty("job",text(nativeJob,"job"));hello.addProperty("generation",nativeJob.get("generation").getAsInt());
                hello.addProperty("currentRow",row);hello.addProperty("verifiedBase",row+1);hello.addProperty("verifiedMask",31);hello.addProperty("currentResolved",true);
                hello.addProperty("phase",nativePaused?"paused":detached?"resupplying":row==nativeJob.get("length").getAsInt()?"complete":begun?"building":"ready");hello.addProperty("begun",begun);hello.addProperty("regroupReady",true);
                hello.addProperty("serviceReturning",returning);hello.addProperty("serviceReady",returning && !delayLanding);hello.addProperty("ack",supplyLock);
                var containers = new JsonArray();
                if (!supplyLock.isEmpty()) {
                    var site = new JsonObject(); site.addProperty("x",0); site.addProperty("y",116); site.addProperty("z",99+row); containers.add(site);
                }
                hello.add("supplyContainers",containers);
                hello.addProperty("moduleOff",moduleOff);
                hello.addProperty("awayReady",nativeJob.has("awayMembers") && nativeJob.getAsJsonObject("awayMembers").has(id.toString()));
                JsonObject exchange=new JsonObject();exchange.addProperty("stage","idle");exchange.addProperty("need",-1);hello.add("exchange",exchangeState==null?exchange:exchangeState.deepCopy());
                hello.addProperty("sharedSupplyProtocol",1);
                if (ledger != null) hello.add("inventory",ledger.deepCopy());
                if (roadForecast != null) hello.add("roadPrediction",roadForecast.deepCopy());
                hello.addProperty("x",0);hello.addProperty("z",100+row);hello.addProperty("available",false);
                for(String key:List.of("suppliers","serviceRevisions")) if(nativeJob.has(key))hello.add(key,nativeJob.get(key).deepCopy());
                if(returning) for(JsonElement id:nativeJob.getAsJsonArray("activeMembers")) if(!id.getAsString().equals(this.id.toString())) { hello.addProperty("renderedCrew",id.getAsString());break; }
            }
            c.send(hello.toString());
            heartbeat(); announceAt = System.nanoTime();
        }
        private void heartbeat() {
            JsonObject message = message("worker"); message.addProperty("current", current);
            message.addProperty("stashCatalogProtocol",1);
            if(sortSupported)message.addProperty("stashSortProtocol",1);
            if(liveStashes)message.addProperty("stashLiveProtocol",1);
            if(stashContainerLimit>0)message.addProperty("stashContainerLimit",stashContainerLimit);
            JsonArray runs = new JsonArray(); checkpoints.values().forEach(r -> { JsonObject value = new JsonObject(); value.addProperty("run", text(r, "run")); value.addProperty("status", text(r, "status")); runs.add(value); });
            message.add("runs", runs); c.send(message.toString());
        }
        void pump() {
            if (!c.connected()) return;
            if (System.nanoTime() - announceAt > 200_000_000L) announce();
            String wire;
            while ((wire = c.poll()) != null) {
                JsonObject message = JsonParser.parseString(wire).getAsJsonObject();
                switch (text(message, "type")) {
                    case "task-known-hostiles" -> { hostileCatalog=message.getAsJsonObject("catalog").deepCopy();KnownHostiles.validate(hostileCatalog);hostileFrames++; }
                    case "task-hostile-warning" -> {HostileWarnings.validate(message);assert hostileCatalog!=null&&hostileCatalog.get("revision").equals(message.get("revision")):"Catalog precedes warning";hostileWarnings.add(message.deepCopy());}
                    case "task-player-check-result" -> {HostileWarnings.validateReceipt(message);playerCheckReceipts++;}
                    case "task-configure" -> { JsonObject run=checkpoints.get(UUID.fromString(text(message,"run")));
                        run.add("configRevision",message.get("revision").deepCopy());run.addProperty("configError","");sendStatus(run); }
                    case "task-configuration-read" -> {
                        JsonObject report=new JsonObject();report.addProperty("note","Test-only worker readback");report.add("rows",new JsonArray());
                        for(JsonObject reply:ConfigurationReadback.replies(message,report))c.send(reply.toString());
                    }
                    case "manage-chat" -> { assert text(message,"scope").equals(scope);chatMessages.add(BotChat.command(text(message,"text")));chatTimes.add(System.nanoTime());JsonObject report=new JsonObject();report.addProperty("type","worker-chat");report.addProperty("direction","sent");report.addProperty("text",text(message,"text"));c.send(report.toString()); }
                    case "heartbeat" -> autoTpy=flag(message,"autoTpy");
                    case "task-tpa-accept" -> tpaAccepts.add(message.deepCopy());
                    case "task-tpa-send" -> tpaSends.add(message.deepCopy());
                    case "assign-crew" -> { assignedKey=c.openSecret(text(message,"sealedKey")); }
                    case "task-begin" -> { transfer = text(message, "run"); digest = text(message, "hash"); count = message.get("count").getAsInt(); chunks.clear(); }
                    case "task-chunk" -> {
                        assert text(message, "run").equals(transfer) && message.get("index").getAsInt() == chunks.size(); chunks.add(text(message, "data"));
                        if (chunks.size() == count) {
                            String data = String.join("", chunks); assert TaskFiles.hash(data).equals(digest);
                            JsonObject envelope = JsonParser.parseString(new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8)).getAsJsonObject();
                            JsonObject metadata = envelope.remove("dispatch").getAsJsonObject(); HostService.checkedPackage(envelope);
                            installedEnvelope=envelope.deepCopy();installedEnvelope.add("dispatch",metadata.deepCopy());
                            if(!Set.of("task-stash-transfer","task-stash-transfer-team","task-stash-sort").contains(text(envelope,"entry")))assert metadata.getAsJsonArray("supportedActions").asList().stream().anyMatch(v -> v.getAsString().equals("Highway"));
                            assert metadata.getAsJsonArray("supportedActions").size()<=(metadata.getAsJsonObject("args").has("returnStash")?14:11) : "Courier return advertises StashVisit; old jobs retain their legacy capability bound";
                            var decision = BotLua.next(text(envelope.getAsJsonObject("programs").getAsJsonObject(text(envelope, "entry")), "script"), new JsonObject(), metadata.getAsJsonObject("args"), null, null);
                            if (text(decision.action(), "type").equals("RecoverSupplies")) {
                                assert metadata.getAsJsonArray("supportedActions").asList().stream().anyMatch(v -> v.getAsString().equals("RecoverSupplies"));
                                decision = BotLua.next(text(envelope.getAsJsonObject("programs").getAsJsonObject(text(envelope, "entry")), "script"), decision.state(), metadata.getAsJsonObject("args"), new JsonObject(), null);
                            }
                            if(text(decision.action(),"type").equals("StashScan")){decision.action().addProperty("workerIndex",metadata.get("workerIndex").getAsInt());decision.action().addProperty("workerCount",metadata.get("workerCount").getAsInt());}
                            assert Set.of("Wait","Travel","DropItems","Highway","StashScan","StashResupply","StashDeposit","KitTransferPlan","StashSortPlan").contains(text(decision.action(), "type"));
                            assert !checkpoints.containsKey(UUID.fromString(transfer));
                            JsonObject run = new JsonObject(); run.addProperty("run", transfer); run.addProperty("status", "Ready");run.add("action",decision.action());run.addProperty("token",UUID.randomUUID().toString()); checkpoints.put(UUID.fromString(transfer), run); installs++; sendStatus(run);
                        }
                    }
                    case "task-control" -> {
                        JsonObject run = checkpoints.get(UUID.fromString(text(message, "run"))); if (run == null) break;
                        if(message.has("controlId"))run.add("controlId",message.get("controlId").deepCopy());
                        if(text(message,"command").equals("resume")&&text(run,"requestedStatus").equals("Cancelled")){
                            run.addProperty("status",holdCancellation?"Inspection required":"Cancelled");run.addProperty("inspectionRequired",holdCancellation);current="";sendStatus(run);heartbeat();break;
                        }
                        if(holdCancellation&&text(message,"command").equals("cancel")){run.addProperty("status","Inspection required");run.addProperty("requestedStatus","Cancelled");run.addProperty("inspectionRequired",true);current="";sendStatus(run);heartbeat();break;}
                        if (!QueuePolicy.terminal(text(run, "status"))) {
                            String command = text(message, "command");
                            if(command.equals("cancel"))run.remove("inspectionRequired");
                            if (!command.equals("resume") && nativeJob!=null && current.equals(text(run,"run"))) {
                                // Real BotRuntime retains control while nativeBusy(), including when merely paused.
                                run.addProperty("status","Suspending");run.addProperty("requestedStatus",command.equals("pause")?"Suspended":"Cancelled");
                                sendStatus(run);heartbeat();break;
                            }
                            if (command.equals("resume")) { assert current.isEmpty() || current.equals(text(run, "run")); current = text(run, "run"); resumes++;run.remove("requestedStatus");if(run.has("actionProgress")){run.getAsJsonObject("actionProgress").addProperty("state","Working");run.getAsJsonObject("actionProgress").remove("incidentId");} }
                            else if (current.equals(text(run, "run"))) current = "";
                            run.addProperty("status", command.equals("resume") ? "Running" : command.equals("pause") ? "Suspended" : "Cancelled");
                        }
                        sendStatus(run); heartbeat();
                    }
                    case "task-stash-ack" -> { assert message.get("delivery").getAsInt()>0;lastStashAck=message.deepCopy();stashAcks++; }
                    case "task-stash-catalog" -> { assert message.getAsJsonArray("stashes").size()<=64;stashCatalog=message.getAsJsonArray("stashes").deepCopy();stashCatalogReports++; }
                    case "task-stash-watch" -> stashWatch.add(message.deepCopy());
                    case "task-stash-live-ack" -> liveAcks.add(message.deepCopy());
                    case "task-stash-live-batch-ack" -> liveBatchAcks.add(message.deepCopy());
                    case "task-stash-export-ack" -> exportAcks.add(message.deepCopy());
                    case "prepare", "reconfigure" -> { nativeJob=message.deepCopy();row=message.get("startRow").getAsInt();begun=nativePaused=false;announce(); }
                    case "restore" -> { assert recoveryOnly && recoveryReady; restores++; recoveryOnly=false; nativeJob=message.deepCopy(); row=message.get("startRow").getAsInt(); detached=HighwayCoordinator.detachedMembers(nativeJob).contains(id); returning=detached; announce(); }
                    case "begin" -> { begun=true;announce(); }
                    case "window" -> { if(advance && !nativePaused && !detached && row<message.get("limit").getAsInt() && (message.get("mask").getAsInt()&1)!=0) { row++;announce(); } }
                    case "service-detach", "service-join", "service-away", "service-back" -> {
                        boolean self = text(message, "supplier").equals(id.toString());
                        if (self && ignoreJoin && detached && Set.of("service-join", "service-back").contains(text(message, "type"))) { rejectedJoins++; break; }
                        nativeJob=HighwayCoordinator.serviceUpdateAssignment(nativeJob,message);
                        detached=HighwayCoordinator.detachedMembers(nativeJob).contains(id);
                        if(!detached || self && text(message,"type").equals("service-detach"))returning=false;announce();
                    }
                    case "reserve", "grant" -> { supplyLock=text(message,"lock");announce(); }
                    case "release" -> { supplyLock="";announce(); }
                    case "end" -> {
                        if(ignoreEnd)break;
                        if(nativeJob!=null && text(nativeJob,"job").equals(text(message,"job")))nativeJob=null;
                        if(nativeJob==null && !current.isEmpty()) {
                            JsonObject run=checkpoints.get(UUID.fromString(current));
                            if(run.has("requestedStatus")) {run.addProperty("status",text(run,"requestedStatus"));run.remove("requestedStatus");current="";sendStatus(run);}
                        }
                        JsonObject ack=new JsonObject();ack.addProperty("type","ended");ack.addProperty("job",text(message,"job"));c.send(ack.toString());announce();
                    }
                    case "task-result" -> {
                        JsonObject r=checkpoints.get(UUID.fromString(text(message,"run")));if(r==null || !text(r,"token").equals(text(message,"token")))break;
                        if(r.has("action")&&Set.of("KitTransferPlan","StashSortPlan").contains(text(r.getAsJsonObject("action"),"type"))&&!QueuePolicy.terminal(text(r,"status"))){lastKitPlan=message.deepCopy();break;}
                        if(!QueuePolicy.terminal(text(r,"status")))r.addProperty("status",flag(message,"success")?"Complete":"Failed");r.remove("action");r.remove("token");if(current.equals(text(r,"run")))current="";sendStatus(r);heartbeat();
                    }
                    case "pause" -> {nativePaused=true;announce();}
                    case "resume" -> {nativePaused=connectionStopped=false;nativeResumes++;announce();}
                    case "resource-inspected" -> {inspections++;announce();}
                    case "stock-scan-complete", "stock-complete" -> { announce(); }
                    case "resource-exchange" -> {
                        offer=message.getAsJsonObject("offer").deepCopy();
                        if(exchangeState==null)exchangeState=new JsonObject();
                        if(!text(exchangeState,"id").equals(text(offer,"id"))) {exchangeState.addProperty("stage","idle");exchangeState.remove("container");}
                        exchangeState.add("id",offer.get("id"));exchangeState.add("sequence",offer.get("sequence"));
                        if(!text(offer,"phase").equals("shared")) exchangeState.add("stage",offer.get("phase"));
                        announce();
                    }
                    case "supply-containers" -> { }
                    case "service-front" -> { serviceFront = message.deepCopy(); frontUpdates++; announce(); }
                    case "withdraw" -> {
                        withdrawalRequests++;if(delayWithdrawal){announce();break;}
                        if(nativeJob!=null&&text(nativeJob,"job").equals(text(message,"job"))&&nativeJob.get("generation").equals(message.get("generation"))) {
                            nativeJob=null;begun=false;detached=false;returning=false;
                        }
                        JsonObject ack=message.deepCopy();ack.addProperty("type","withdrawn");ack.addProperty("worker",id.toString());c.send(ack.toString());announce();
                    }
                    case "regroup", "regroupCancel", "lost", "nudge", "anticipate-supply" -> { announce(); }
                    default -> throw new AssertionError("Unexpected host message " + text(message, "type"));
                }
            }
        }
        private void sendStatus(JsonObject run) { JsonObject message = run.deepCopy(); message.addProperty("type", "task-status"); message.addProperty("detail", "Simulated native worker"); message.addProperty("readbackVersion",1); message.addProperty("configurationVersion",1); c.send(message.toString()); }
        void nativeSend(String type,boolean detach) {
            JsonObject m=new JsonObject();m.addProperty("type",type);m.addProperty("job",text(nativeJob,"job"));m.addProperty("generation",nativeJob.get("generation").getAsInt());
            m.addProperty("serviceRevision",HighwayCoordinator.serviceRevision(nativeJob,id));m.addProperty("detach",detach);
            m.addProperty("x",0);m.addProperty("y",116);m.addProperty("z",97);m.addProperty("lock",supplyLock);c.send(m.toString());
        }
        void detach(){nativeSend("request",true);}
        void requestTpa(String target){JsonObject request=new JsonObject();request.addProperty("type","worker-tpa-request");request.addProperty("request",UUID.randomUUID().toString());request.addProperty("target",target);request.addProperty("scope",scope);c.send(request.toString());}
        void requestBodyguardTpa(String target,UUID targetId){JsonObject request=new JsonObject();request.addProperty("type","worker-tpa-request");request.addProperty("request",UUID.randomUUID().toString());request.addProperty("target",target);request.addProperty("targetId",targetId.toString());request.addProperty("scope",scope);request.addProperty("bodyguard",true);c.send(request.toString());}
        void reserve(){supplyLock="local-container";announce();}
        void release(){supplyLock="";returning=true;announce();}
        @Override public void close() { c.disconnect(); }
    }
}
