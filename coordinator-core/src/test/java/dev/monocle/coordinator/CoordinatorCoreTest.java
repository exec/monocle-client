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
    private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static void workerFrames() throws Exception {
        String full = "🌸".repeat(CrewFrames.MAX_BYTES / 4);
        assert CrewFrames.encode(full).length == CrewFrames.MAX_BYTES;
        rejects(() -> CrewFrames.encode(full + "x"));
        rejects(() -> CrewFrames.encode("x".repeat(CrewFrames.MAX_BYTES + 1)));
        rejects(() -> CrewFrames.encode("\ud800"));
        var buffer = new java.io.ByteArrayOutputStream();
        var out = new java.io.DataOutputStream(buffer);
        CrewFrames.write(out, full); CrewFrames.write(out, "next 🌸 record");
        var in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(buffer.toByteArray()));
        assert in.readInt() == CrewFrames.MAX_BYTES;
        in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(buffer.toByteArray()));
        assert CrewFrames.read(in).equals(full) && CrewFrames.read(in).equals("next 🌸 record");
        for (int length : new int[]{-1, 0, CrewFrames.MAX_BYTES + 1, Integer.MAX_VALUE}) {
            buffer.reset(); out.writeInt(length);
            invalidFrame(buffer.toByteArray()); // Reject the prefix without allocating or reading its payload.
        }
        buffer.reset(); out.writeInt(3); out.writeByte(1); invalidFrame(buffer.toByteArray());
        buffer.reset(); out.writeInt(2); out.write(new byte[]{(byte) 0xc3, 0x28}); invalidFrame(buffer.toByteArray());
        invalidFrame(new byte[]{1, 2});

        var queue = new CrewFrames.Queue();
        for (int i = 0; i < 4; i++) assert queue.offer(full);
        assert !queue.offer("x") : "Queues must enforce bytes, not just number of messages";
        assert queue.take().equals(full) && queue.offer(full);
        queue.clear(); assert queue.poll() == null;
        assert queue.poll(1, java.util.concurrent.TimeUnit.MILLISECONDS) == null;
        for (int i = 0; i < 128; i++) assert queue.offer("x");
        assert !queue.offer("x") : "Small messages must still respect the frame-count bound";
        for (int i = 0; i < 128; i++) assert queue.poll().equals("x");
        for (int i = 0; i < 4; i++) assert queue.offer(full) : "Draining must restore the byte budget exactly";
        queue.clear();
        rejects(() -> queue.offer(full + "x"));
        assert queue.offer("after rejected frame") && queue.poll().equals("after rejected frame");

        JsonObject status = new JsonObject(); status.addProperty("type", "task-status");
        JsonArray receipts = new JsonArray();
        for (int i = 0; i < 27; i++) {
            JsonObject receipt = new JsonObject(); receipt.addProperty("id", UUID.randomUUID().toString());
            receipt.addProperty("state", "Confirmed"); receipt.addProperty("stack", "x".repeat(700)); receipts.add(receipt);
        }
        status.add("withdrawals", receipts);
        assert status.toString().length() > 16_000;
        buffer.reset(); CrewFrames.write(out, status.toString());
        assert JsonParser.parseString(CrewFrames.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(buffer.toByteArray())))).equals(status)
            : "A full kit-inventory receipt set must fit in one native status frame";
        assert TaskFiles.MAX_PACKAGE == 4 * 1024 * 1024 : "Large workflow packages retain their existing chunked path";
    }

    private static void invalidFrame(byte[] bytes) {
        try {
            CrewFrames.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)));
            throw new AssertionError("Invalid frame accepted");
        } catch (java.io.IOException expected) { }
    }

    private static void teleportRecovery() {
        JsonObject requester = recoveryReport("Requester", "requested", 500, 20);
        JsonObject healthy = recoveryReport("Healthy", "", 2, 12);
        JsonObject waiting = recoveryReport("Waiting", "waiting", 0, 10);
        Map<UUID, JsonObject> reports = Map.of(WORKER, requester, OTHER, healthy, THIRD, waiting);
        assert HighwayCoordinator.recoveryTeleportTarget(WORKER, List.of(WORKER, OTHER, THIRD), reports).equals(OTHER)
            : "TPA recovery must choose a healthy crewmate, never one awaiting its own teleport";
        healthy.getAsJsonObject("diagnostics").addProperty("idleTicks", 400);
        assert HighwayCoordinator.recoveryTeleportTarget(WORKER, List.of(WORKER, OTHER, THIRD), reports) == null
            : "A stuck crewmate is not a recovery anchor";
    }

    private static JsonObject recoveryReport(String name, String recovery, int idleTicks, int row) {
        JsonObject report = new JsonObject(), diagnostics = new JsonObject();
        report.addProperty("name", name); report.addProperty("phase", "building"); report.addProperty("tpaRecovery", recovery);
        report.addProperty("currentRow", row); diagnostics.addProperty("idleTicks", idleTicks); report.add("diagnostics", diagnostics);
        return report;
    }

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

    private static void configurationInspection() {
        JsonObject task = JsonParser.parseString("{\"package\":{\"profiles\":{\"Current\":{\"speed\":{\"active\":true,\"settings\":\"{}\"}}},\"highways\":{},\"programs\":{\"private\":\"not part of inspection\"}},\"runs\":{},\"hostOriginal\":{\"secret\":true}}").getAsJsonObject();
        JsonObject run = JsonParser.parseString("{\"status\":\"Running\",\"configuration\":{\"revision\":2,\"modules\":{\"speed\":{\"active\":false,\"settings\":\"{}\"}}},\"configRevision\":1,\"configError\":\"old error\"}").getAsJsonObject();
        task.getAsJsonObject("runs").add(WORKER.toString(), run);
        JsonObject before = task.deepCopy(), view = TaskConfiguration.inspect(task);
        JsonObject update = view.getAsJsonObject("updates").getAsJsonObject(WORKER.toString());
        assert update.get("status").getAsString().equals("Pending") && update.get("error").getAsString().isEmpty();
        assert view.keySet().equals(java.util.Set.of("explanation", "profiles", "highways", "updates"));
        view.getAsJsonObject("profiles").getAsJsonObject("Current").remove("speed");
        update.getAsJsonObject("modules").remove("speed");
        assert task.equals(before) : "Inspecting or editing a returned view must not mutate the job";
        run.addProperty("configRevision", 2);
        assert TaskConfiguration.inspect(task).getAsJsonObject("updates").getAsJsonObject(WORKER.toString()).get("status").getAsString().equals("Rejected");
        run.addProperty("configError", "");
        assert TaskConfiguration.inspect(task).getAsJsonObject("updates").getAsJsonObject(WORKER.toString()).get("status").getAsString().equals("Accepted");
        run.getAsJsonObject("configuration").addProperty("revision", 3); task.addProperty("cancelled", true);
        assert TaskConfiguration.inspect(task).getAsJsonObject("updates").getAsJsonObject(WORKER.toString()).get("status").getAsString().equals("Ended without acknowledgement");
        assert TaskConfiguration.inspect(new JsonObject()).getAsJsonObject("profiles").isEmpty();
    }

    private static void guidedConfiguration() {
        assert JobSettingControls.catalog().getAsJsonArray("controls").size() == JobSettingControls.ALL.size();
        for (var control : JobSettingControls.ALL) {
            JsonObject request = new JsonObject(); request.addProperty("control", control.id()); request.addProperty("active", false);
            request.addProperty("value", control.example());
            JsonObject before = request.deepCopy(), preview = JobSettingControls.preview(request);
            assert request.equals(before);
            JsonObject modules = preview.getAsJsonObject("modules");
            assert modules.size() == 1 && !modules.getAsJsonObject(control.module()).get("active").getAsBoolean();
            if (!control.numeric()) assert modules.getAsJsonObject(control.module()).get("settings").getAsString().equals("{}");
            else {
                for (double bad : new double[]{control.min() - 1, control.max() + 1, Double.NaN, Double.POSITIVE_INFINITY}) {
                    request.addProperty("value", bad); rejects(() -> JobSettingControls.preview(request));
                }
                request.addProperty("value", "5"); rejects(() -> JobSettingControls.preview(request));
            }
            request.addProperty("active", "false"); rejects(() -> JobSettingControls.preview(request));
        }
        JsonObject request = JsonParser.parseString("{\"control\":\"eat-hunger\",\"active\":true,\"value\":5.5}").getAsJsonObject();
        rejects(() -> JobSettingControls.preview(request));
        request.addProperty("control", "highway-builder"); rejects(() -> JobSettingControls.preview(request));
    }

    private static void configurationReadback() {
        UUID taskId=UUID.randomUUID();JsonObject task=JsonParser.parseString("{\"package\":{\"profiles\":{\"Current\":{}}},\"runs\":{}}").getAsJsonObject();task.addProperty("id",taskId.toString());
        JsonObject run=new JsonObject();run.addProperty("id",UUID.randomUUID().toString());task.getAsJsonObject("runs").add(WORKER.toString(),run);
        ConfigurationReadback readbacks=new ConfigurationReadback();
        rejects(()->readbacks.request(task,WORKER,"Current","speed",1000));
        run.addProperty("readbackVersion",1);
        rejects(()->readbacks.request(task,OTHER,"Current","speed",1000));
        rejects(()->readbacks.request(task,WORKER,"missing","speed",1000));
        JsonObject request=readbacks.request(task,WORKER,"Current","speed",1000);
        rejects(()->readbacks.request(task,WORKER,"Current","speed",1500));
        JsonObject report=JsonParser.parseString("{\"note\":\"Snapshot\",\"rows\":[]}").getAsJsonObject();
        for(int i=0;i<70;i++){JsonObject row=new JsonObject();row.addProperty("setting","setting-"+i);for(String key:List.of("personal","requested","current"))row.addProperty(key,"quoted \\\"value\\\" 🧐 "+"x".repeat(50));report.getAsJsonArray("rows").add(row);}
        var replies=ConfigurationReadback.replies(request,report);assert replies.size()>1;
        assert !readbacks.accept(OTHER,replies.getFirst(),1600);
        assert !readbacks.accept(WORKER,replies.getLast(),1600):"Out-of-order data cannot be presented as a snapshot";
        for(JsonObject reply:replies){assert reply.toString().length()<16000;readbacks.accept(WORKER,reply,2000);}
        JsonObject result=readbacks.get(taskId,WORKER,2000);assert result.getAsJsonObject("report").equals(report);
        result.getAsJsonObject("report").addProperty("note","mutated");assert readbacks.get(taskId,WORKER,2000).getAsJsonObject("report").equals(report);
        assert !readbacks.accept(WORKER,replies.getFirst(),2001):"Duplicate replies do not overwrite a completed snapshot";
        var next=readbacks.request(task,WORKER,"Current","speed",3000);
        assert !readbacks.accept(WORKER,replies.getFirst(),3001):"Late replies cannot satisfy a new request";
        assert readbacks.get(taskId,WORKER,13001).get("status").getAsString().startsWith("Timed out");
        assert !readbacks.accept(WORKER,ConfigurationReadback.replies(next,report).getFirst(),13001);
        assert !task.has("report")&&!run.has("report"):"Readback is transient, not persistent telemetry";
        report.addProperty("extra","not allowed");rejects(()->ConfigurationReadback.checkedReport(report));
    }

    public static void main(String[] args) throws Exception {
        KitCategoriesTest.run();
        WorkerSupplyStationsTest.run();
        StashSortPlannerTest.run();
        StashSortExecutionTest.run();
        ActionProgressTest.run();
        boolean enabled = false; assert enabled = true;
        if (!enabled) throw new IllegalStateException("Run with assertions enabled");
        workerFrames();
        rwpFollow();
        CommandReceiptsTest.check();
        KnownHostilesTest.run();
        HostileWarningsTest.run();
        IncidentTimelineTest.run();
        DiscordNotificationsTest.run();
        liveConfiguration();
        configurationInspection();
        guidedConfiguration();
        configurationReadback();
        stashScan(); stashOverlap(); stashCapacity(); stashLive(); stashLiveBatch(); stashLegacyWatches(); stashExpansion(); stashExports();
        StashCatalogShouldCheck.run();
        stashColumns();
        fullColumnMemory();
        singleColumnTransfer();
        sharedColumnTransfer();
        stashManualColumns();
        stashMultiTransfer();
        StashTransferTeamCheck.run();
        supplyRecovery();
        teleportRecovery();
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
            assert !QueuePolicy.pause(ended);
            rejects(() -> QueuePolicy.resume(ended));
            assert QueuePolicy.summarizedStatus(ended).equals(state);
            assert QueuePolicy.choose(List.of(ended), WORKER) == null;
            if (state.equals("Failed")) {
                run(ended).addProperty("status", "Failed");
                run(ended).addProperty("detail", "Original uncertain inventory move");
                assert QueuePolicy.cancel(ended);
                assert QueuePolicy.summarizedStatus(ended).equals("Cancelled");
                assert run(ended).get("status").getAsString().equals("Inspection required");
                assert run(ended).get("failureDetail").getAsString().equals("Original uncertain inventory move");
                assert QueuePolicy.cancellationCommand(run(ended)).equals("cancel") : "Reopened cleanup never resumes failed work";
                assert !QueuePolicy.cancel(ended) : "Repeated cancellation is idempotent";
                run(ended).addProperty("status", "Failed");
                run(ended).addProperty("detail", "Late pre-cancel failure report");
                assert !QueuePolicy.cancellationAcknowledged("Failed") && QueuePolicy.cancellationAcknowledged("Cancelled") && QueuePolicy.cancellationAcknowledged("Complete");
                assert !dev.monocle.client.systems.bots.BotHistory.taskFinished(ended,false) : "A stale failure is not cancellation cleanup acknowledgement";
                run(ended).addProperty("id",UUID.randomUUID().toString());
                JsonObject cleanup=new JsonObject();cleanup.addProperty("run",run(ended).get("id").getAsString());cleanup.addProperty("status","Inspection required");
                assert !TaskWire.applyStatus(ended,run(ended),cleanup,false) : "Unrequested terminal reactivation remains forbidden";
                cleanup.addProperty("requestedStatus","Cancelled");
                assert TaskWire.applyStatus(ended,run(ended),cleanup,false) : "A failed execution can reopen only for acknowledged cancellation cleanup";
                run(ended).addProperty("status","Failed");
                assert QueuePolicy.cancel(ended) && run(ended).get("status").getAsString().equals("Inspection required")
                    : "Retry cancellation when an old client reports Failed instead of acknowledging cleanup";
                assert run(ended).get("failureDetail").getAsString().equals("Original uncertain inventory move");
                assert !QueuePolicy.cancel(ended);
            } else assert !QueuePolicy.cancel(ended);
        }
        JsonObject perWorker = task("per-worker", 3);
        perWorker.getAsJsonObject("overrides").addProperty(WORKER.toString(), 0);
        assert QueuePolicy.priority(perWorker, WORKER) == 0 && QueuePolicy.priority(perWorker, OTHER) == 3;
        assert QueuePolicy.choose(List.of(perWorker), OTHER) == null;
        timing(); lua(); observations(); verification(); dispatch();
        System.out.println("Coordinator core checks passed without Minecraft/Fabric/LWJGL: queues, recovery, teleport, Lua, player observations and row verification authority.");
    }
    private static void stashCapacity(){
        JsonObject db=JsonParser.parseString("{containers:{a:{x:0,y:0,z:0,block:'minecraft:chest',status:'observed',items:{'minecraft:stone':1},shulkers:[],slotCount:27,occupiedSlots:1},b:{x:1,y:0,z:0,block:'minecraft:chest',status:'observed',items:{},shulkers:[],slotCount:54,occupiedSlots:0},hopper:{x:2,y:0,z:0,block:'minecraft:hopper',status:'observed',items:{'minecraft:stone':64},shulkers:[],slotCount:5,occupiedSlots:1}}}").getAsJsonObject();
        JsonObject storage=StashCatalog.summary(db).getAsJsonObject("storage");
        assert storage.get("containers").getAsInt()==2&&storage.get("slots").getAsInt()==81&&storage.get("occupiedSlots").getAsInt()==1 : "Count physical slots, including partial stacks, but not hoppers";
        assert storage.get("estimatedContainers").getAsInt()==0&&storage.get("unknownContainers").getAsInt()==0;
        JsonObject legacy=JsonParser.parseString("{x:3,y:0,z:0,block:'minecraft:chest',status:'observed',items:{'minecraft:blue_shulker_box':1,'minecraft:obsidian':1728},shulkers:[{slot:4,item:'minecraft:blue_shulker_box',name:'Obby',quantity:1,items:{'minecraft:obsidian':1728}}]}").getAsJsonObject();
        db.getAsJsonObject("containers").add("legacy",legacy);
        storage=StashCatalog.summary(db).getAsJsonObject("storage");
        assert storage.get("slots").getAsInt()==135&&storage.get("occupiedSlots").getAsInt()==2&&storage.get("estimatedContainers").getAsInt()==1&&storage.get("unknownContainers").getAsInt()==0 : "Legacy nested items are not extra occupied storage slots";
        legacy.getAsJsonObject("items").addProperty("minecraft:stone",1);
        assert StashCatalog.summary(db).getAsJsonObject("storage").get("unknownContainers").getAsInt()==1 : "Legacy loose-item stack counts are unknown, not empty";
        legacy.remove("slotCount");legacy.addProperty("inferred",true);
        assert StashCatalog.summary(db).getAsJsonObject("storage").get("occupiedSlots").getAsInt()==1 : "Inferred stock is not measured occupancy";
        JsonObject p=JsonParser.parseString("{name:'Slots',homeName:'slots',minX:0,maxX:3,minY:0,maxY:0,minZ:0,maxZ:0}").getAsJsonObject();
        JsonObject measured=db.getAsJsonObject("containers").getAsJsonObject("a").deepCopy();StashCatalog.observation(StashCatalog.plan(p),measured);
        measured.addProperty("occupiedSlots",28);rejects(()->StashCatalog.observation(StashCatalog.plan(p),measured));
        measured.addProperty("occupiedSlots",1);measured.addProperty("status","unscanned");rejects(()->StashCatalog.observation(StashCatalog.plan(p),measured));
        measured.addProperty("status","observed");measured.remove("slotCount");rejects(()->StashCatalog.observation(StashCatalog.plan(p),measured));
    }
    private static JsonObject liveMessage(JsonObject o,String basis){JsonObject m=TaskWire.message("stash-live");m.addProperty("name","Live");m.addProperty("eventId",UUID.randomUUID().toString());m.addProperty("basis",basis);StashCatalog.attachObservation(m,o);return m;}
    private static void stashLive() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("monocle-stash-live-");String scope="server\nnether";
        JsonObject p=JsonParser.parseString("{name:'Live',homeName:'live',minX:0,maxX:3,minY:64,maxY:64,minZ:0,maxZ:0,lazyMode:false,scanMode:'Full'}").getAsJsonObject();StashCatalog.define(root,"A",scope,p);
        JsonObject stock=JsonParser.parseString("{x:0,y:64,z:0,status:'observed',block:'minecraft:chest',capacity:27,parts:[{x:0,y:64,z:0}],slotCount:27,occupiedSlots:1,items:{'minecraft:obsidian':64},shulkers:[]}").getAsJsonObject();
        JsonObject created=liveMessage(stock,"");assert TaskWire.text(StashCatalog.acceptLive(root,"A",scope,WORKER.toString(),created),"outcome").equals("applied");
        var record=StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("0,64,0");String basis=StashCatalog.observationBasis(record),revision=TaskWire.text(StashCatalog.list(root).get(0).getAsJsonObject(),"revision");
        assert TaskWire.text(StashCatalog.acceptLive(root,"A",scope,OTHER.toString(),liveMessage(stock,"")),"outcome").equals("unchanged");
        assert TaskWire.text(StashCatalog.list(root).get(0).getAsJsonObject(),"revision").equals(revision) : "Duplicate workers must not rewrite or recount storage";
        JsonObject changed=stock.deepCopy();changed.getAsJsonObject("items").addProperty("minecraft:obsidian",128);assert TaskWire.text(StashCatalog.acceptLive(root,"A",scope,WORKER.toString(),liveMessage(changed,basis)),"outcome").equals("applied");
        JsonObject removed=JsonParser.parseString("{x:0,y:64,z:0,status:'removed',block:'minecraft:air',reason:'Server-confirmed storage removal',items:{},shulkers:[]}").getAsJsonObject();
        assert TaskWire.text(StashCatalog.acceptLive(root,"A",scope,OTHER.toString(),liveMessage(removed,basis)),"outcome").equals("conflict") : "A stale removal cannot erase newer stock";
        record=StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("0,64,0");JsonObject deletion=liveMessage(removed,StashCatalog.observationBasis(record));
        assert TaskWire.text(StashCatalog.acceptLive(root,"A",scope,WORKER.toString(),deletion),"outcome").equals("applied");
        assert TaskWire.text(StashCatalog.acceptLive(root,"A",scope,WORKER.toString(),deletion),"outcome").equals("unchanged") : "Lost acknowledgement retries are idempotent";
        assert TaskWire.text(StashCatalog.acceptLive(root,"A",scope,OTHER.toString(),liveMessage(stock,basis)),"outcome").equals("conflict") : "Queued old contents cannot resurrect deleted storage";
        JsonObject summary=StashCatalog.list(root).get(0).getAsJsonObject();assert summary.get("removed").getAsInt()==1&&summary.getAsJsonObject("items").isEmpty()&&summary.get("unscanned").getAsInt()==0&&summary.getAsJsonObject("storage").get("slots").getAsInt()==0;
        record=StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("0,64,0");
        JsonObject fresh=stock.deepCopy();fresh.addProperty("status","unscanned");fresh.addProperty("reason","Storage discovered; contents not yet observed");fresh.remove("slotCount");fresh.remove("occupiedSlots");fresh.add("items",new JsonObject());
        assert TaskWire.text(StashCatalog.acceptLive(root,"A",scope,WORKER.toString(),liveMessage(fresh,StashCatalog.observationBasis(record))),"outcome").equals("applied");
        summary=StashCatalog.list(root).get(0).getAsJsonObject();assert summary.getAsJsonObject("storage").get("slots").getAsInt()==27&&summary.getAsJsonObject("storage").get("unknownContainers").getAsInt()==1&&summary.getAsJsonObject("columns").isEmpty() : "New unopened storage is capacity, not invented empty stock";
        JsonObject partner=stock.deepCopy();partner.addProperty("x",1);partner.add("parts",JsonParser.parseString("[{x:1,y:64,z:0}]").getAsJsonArray());StashCatalog.record(root,"A",scope,p,partner);
        record=StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("0,64,0");
        JsonObject joined=stock.deepCopy();joined.addProperty("capacity",54);joined.addProperty("slotCount",54);joined.add("parts",JsonParser.parseString("[{x:0,y:64,z:0},{x:1,y:64,z:0}]").getAsJsonArray());StashCatalog.acceptLive(root,"A",scope,WORKER.toString(),liveMessage(joined,StashCatalog.observationBasis(record)));
        summary=StashCatalog.list(root).get(0).getAsJsonObject();assert summary.getAsJsonObject("storage").get("slots").getAsInt()==54&&summary.getAsJsonObject("items").get("minecraft:obsidian").getAsInt()==64 : "Joining halves never double-counts old single chest observations";
        record=StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("0,64,0");StashCatalog.acceptLive(root,"A",scope,WORKER.toString(),liveMessage(removed,StashCatalog.observationBasis(record)));
        record=StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("1,64,0");JsonObject remaining=fresh.deepCopy();remaining.addProperty("x",1);remaining.add("parts",partner.get("parts"));StashCatalog.acceptLive(root,"A",scope,WORKER.toString(),liveMessage(remaining,StashCatalog.observationBasis(record)));
        assert StashCatalog.list(root).get(0).getAsJsonObject().getAsJsonObject("storage").get("slots").getAsInt()==27 : "Destroying one half preserves surviving capacity without claiming its old contents";
        JsonObject outside=stock.deepCopy();outside.addProperty("x",100);rejects(()->StashCatalog.acceptLive(root,"A",scope,WORKER.toString(),liveMessage(outside,"")));
        JsonObject legacy=stock.deepCopy();legacy.addProperty("x",2);legacy.remove("parts");legacy.remove("capacity");StashCatalog.record(root,"A",scope,p,legacy);record=StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("2,64,0");
        JsonObject presence=fresh.deepCopy();presence.addProperty("x",2);presence.add("parts",JsonParser.parseString("[{x:2,y:64,z:0}]").getAsJsonArray());StashCatalog.acceptLive(root,"A",scope,WORKER.toString(),liveMessage(presence,StashCatalog.observationBasis(record)));
        assert TaskWire.text(StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("2,64,0"),"status").equals("observed") : "Unchanged legacy geometry does not erase verified contents";
        assertLegacyDiscovery(root,scope,p,stock,fresh);
        JsonArray local=StashCatalog.localWatches(root,false);assert local.isEmpty() : "Workers do not load other crews' local catalogs";
        StashCatalog.record(root,"Local",scope,p,stock);local=StashCatalog.localWatches(root,false);assert local.size()==1;
        JsonObject localDb=local.get(0).getAsJsonObject(),baseline=localDb.getAsJsonObject("containers").getAsJsonObject("0,64,0");
        JsonObject saved=StashCatalog.get(root,"Local",scope,"Live").getAsJsonObject("containers").getAsJsonObject("0,64,0");
        assert baseline.equals(StashCatalog.watchRecord(saved))&&localDb.getAsJsonObject("bounds").equals(StashCatalog.get(root,"Local",scope,"Live").getAsJsonObject("bounds"));
        assert !baseline.has("items")&&!baseline.has("shulkers")&&!localDb.has("kitTypes")&&!localDb.has("storage") : "The game thread receives only compact watches, not stock or aggregates";
        assert StashCatalog.localWatches(root,true).size()==2 : "An in-game host watches all its saved crews";
        assert StashCatalog.localWatches(root.resolve("missing"),true).isEmpty();
        var frames=StashCatalog.watchFrames(root,"A");assert !frames.isEmpty()&&StashCatalog.watchFrames(root,"B").isEmpty();for(JsonObject frame:frames){TaskFiles.jsonBytes(frame,CrewFrames.MAX_BYTES);assert frame.getAsJsonArray("records").size()<=32&&!frame.has("kitTypes");}
        JsonObject unknown=liveMessage(stock,"");unknown.addProperty("name","Deleted stash");assert TaskWire.text(StashCatalog.acceptLive(root,"A",scope,WORKER.toString(),unknown),"outcome").equals("retired");
        assert StashCatalog.get(root,"A",scope,"Live").getAsJsonArray("containerEvents").size()>2 : "Storage lifecycle remains inspectable after recreation";
    }
    private static void stashLiveBatch() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("monocle-live-batch-");String scope="server\nnether";
        JsonObject plan=JsonParser.parseString("{name:'Live',homeName:'live',minX:0,maxX:63,minY:64,maxY:64,minZ:0,maxZ:0}").getAsJsonObject();StashCatalog.define(root,"A",scope,plan);
        JsonObject batch=TaskWire.message("stash-live-batch");JsonArray entries=new JsonArray();batch.add("entries",entries);
        JsonObject stock=JsonParser.parseString("{x:0,y:64,z:0,status:'unscanned',block:'minecraft:chest',capacity:54,items:{},shulkers:[]}").getAsJsonObject();
        for(int i=0;i<StashCatalog.LIVE_BATCH_SIZE;i++){stock.addProperty("x",i);JsonObject entry=liveMessage(stock.deepCopy(),"");entry.addProperty("scope",scope);entries.add(entry);}
        int bytes=TaskFiles.jsonBytes(batch,StashCatalog.LIVE_BATCH_BYTES).length;
        JsonObject ack=StashCatalog.acceptLiveBatch(root,"A",WORKER.toString(),scope,batch);assert ack.getAsJsonArray("entries").size()==32;
        assert ack.getAsJsonArray("entries").asList().stream().allMatch(v->TaskWire.text(v.getAsJsonObject(),"outcome").equals("applied"));
        JsonObject db=StashCatalog.get(root,"A",scope,"Live");assert db.getAsJsonObject("containers").size()==32;
        var file=root.resolve("stashes").resolve(TaskFiles.hash("A\n"+scope+"\nLive")+".sqlite");StashStore.clearCache();assert StashStore.read(file).getAsJsonObject("containers").size()==32 : "Every batched receipt is durable before ACK";
        ack=StashCatalog.acceptLiveBatch(root,"A",WORKER.toString(),scope,batch);assert ack.getAsJsonArray("entries").asList().stream().allMatch(v->TaskWire.text(v.getAsJsonObject(),"outcome").equals("unchanged")) : "A lost batch ACK cannot recount or rewrite stock";
        ack=StashCatalog.acceptLiveBatch(root,"A",WORKER.toString(),"other\nworld",batch);assert ack.getAsJsonArray("entries").asList().stream().allMatch(v->TaskWire.text(v.getAsJsonObject(),"outcome").equals("deferred"));
        JsonObject malformed=batch.deepCopy();malformed.getAsJsonArray("entries").get(31).getAsJsonObject().addProperty("basis","bad");rejects(()->StashCatalog.acceptLiveBatch(root,"A",WORKER.toString(),scope,malformed));assert StashStore.read(file).equals(db) : "Malformed later entries cannot partially apply earlier observations";
        JsonObject duplicate=batch.deepCopy();duplicate.getAsJsonArray("entries").set(1,duplicate.getAsJsonArray("entries").get(0).deepCopy());rejects(()->StashCatalog.liveBatch(duplicate));
        JsonObject tooMany=batch.deepCopy();tooMany.getAsJsonArray("entries").add(entries.get(0).deepCopy());rejects(()->StashCatalog.liveBatch(tooMany));
        JsonObject large=batch.deepCopy();large.addProperty("padding","x".repeat(StashCatalog.LIVE_BATCH_BYTES));rejects(()->StashCatalog.liveBatch(large));
        JsonObject mixed=batch.deepCopy();mixed.getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("basis",UUID.randomUUID().toString());rejects(()->StashCatalog.liveBatch(mixed));
        stock.addProperty("x",40);JsonObject partial=TaskWire.message("stash-live-batch");JsonArray pair=new JsonArray();partial.add("entries",pair);pair.add(liveMessage(stock.deepCopy(),""));stock.addProperty("x",100);pair.add(liveMessage(stock.deepCopy(),""));pair.forEach(v->v.getAsJsonObject().addProperty("scope",scope));
        rejects(()->StashCatalog.acceptLiveBatch(root,"A",WORKER.toString(),scope,partial));StashStore.clearCache();assert StashStore.read(file).getAsJsonObject("containers").has("40,64,0") : "A later storage failure preserves the committed prefix";
        pair.remove(1);assert TaskWire.text(StashCatalog.acceptLiveBatch(root,"A",WORKER.toString(),scope,partial).getAsJsonArray("entries").get(0).getAsJsonObject(),"outcome").equals("unchanged");
        System.out.println("32 passive observations: "+bytes+" bytes, durable independent receipts and retry checks passed.");
    }
    private static void stashLegacyWatches() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("monocle-legacy-watch-");String scope="server\nnether";
        JsonObject plan=JsonParser.parseString("{name:'Legacy',homeName:'legacy',minX:0,maxX:1,minY:64,maxY:64,minZ:0,maxZ:0}").getAsJsonObject();
        JsonObject stock=JsonParser.parseString("{x:0,y:64,z:0,status:'observed',block:'minecraft:chest',capacity:27,items:{'minecraft:obsidian':64},shulkers:[]}").getAsJsonObject();
        StashCatalog.record(root,"Local",scope,plan,stock);
        var file=root.resolve("stashes").resolve(TaskFiles.hash("Local\n"+scope+"\nLegacy")+".sqlite");JsonObject db=StashStore.read(file);
        JsonObject outside=stock.deepCopy();outside.addProperty("y",65);db.getAsJsonObject("containers").add(StashCatalog.containerKey(outside),outside);StashStore.write(file,db);
        var frames=StashCatalog.watchFrames(root,"Local",1);assert frames.size()==1&&frames.getFirst().getAsJsonArray("records").size()==1 : "Out-of-bounds legacy rows cannot poison or exhaust a worker baseline";
        JsonObject record=frames.getFirst().getAsJsonArray("records").get(0).getAsJsonObject();assert record.equals(StashCatalog.watchRecord(db.getAsJsonObject("containers").getAsJsonObject("0,64,0")));
        var local=StashCatalog.localWatches(root,false);assert local.size()==1&&local.get(0).getAsJsonObject().getAsJsonObject("containers").size()==1 : "Solo and in-game hosts use the same bounds filter";
        assert StashStore.read(file).equals(db) : "Watch filtering must not delete historical contents";
        db.getAsJsonObject("bounds").addProperty("homeName","");StashStore.write(file,db);
        assert StashCatalog.watchFrames(root,"Local").isEmpty()&&StashCatalog.localWatches(root,true).isEmpty() : "A legacy definition without a required home cannot abort other watches";
        assert StashStore.read(file).equals(db);
    }
    private static void assertLegacyDiscovery(java.nio.file.Path root,String scope,JsonObject p,JsonObject stock,JsonObject fresh){
        JsonObject legacy=stock.deepCopy();legacy.addProperty("x",3);for(String field:List.of("parts","capacity","slotCount","occupiedSlots"))legacy.remove(field);
        legacy.add("shulkers",JsonParser.parseString("[{slot:0,quantity:1,item:'minecraft:blue_shulker_box',name:'Kit',contentsKnown:true,items:{'minecraft:obsidian':64}}]").getAsJsonArray());
        StashCatalog.record(root,"A",scope,p,legacy);
        JsonObject record=StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("3,64,0");
        var path=root.resolve("stashes").resolve(TaskFiles.hash("A\n"+scope+"\nLive")+".sqlite");JsonObject db=StashStore.read(path);record.addProperty("observedAt",1234);db.getAsJsonObject("containers").add("3,64,0",record);StashStore.write(path,db);
        JsonObject presence=fresh.deepCopy();presence.addProperty("x",3);presence.add("parts",JsonParser.parseString("[{x:3,y:64,z:0}]").getAsJsonArray());
        JsonObject update=liveMessage(presence,StashCatalog.observationBasis(record));assert TaskWire.text(StashCatalog.acceptLive(root,"A",scope,WORKER.toString(),update),"outcome").equals("applied");
        JsonObject migrated=StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("3,64,0");
        assert TaskWire.text(migrated,"status").equals("observed")&&migrated.get("items").equals(record.get("items"))&&migrated.get("shulkers").equals(record.get("shulkers")) : "Discovery cannot wipe pre-slot-count verified stock or kits";
        assert migrated.get("observedAt").getAsLong()==1234&&!migrated.has("slotCount")&&!migrated.has("occupiedSlots") : "Geometry discovery neither refreshes nor invents a contents read";
        assert migrated.get("parts").equals(presence.get("parts"))&&migrated.get("capacity").getAsInt()==27;
        JsonArray events=StashCatalog.get(root,"A",scope,"Live").getAsJsonArray("containerEvents");assert events.get(events.size()-1).getAsJsonObject().get("observedAt").getAsLong()>1234 : "Geometry event time is distinct from historical contents freshness";
        assert TaskWire.text(StashCatalog.acceptLive(root,"A",scope,OTHER.toString(),liveMessage(presence,"")),"outcome").equals("unchanged") : "Once migrated, duplicate discovery is harmless";
        JsonObject read=migrated.deepCopy();read.addProperty("slotCount",27);read.addProperty("occupiedSlots",1);read.getAsJsonObject("items").addProperty("minecraft:obsidian",128);
        StashCatalog.acceptLive(root,"A",scope,WORKER.toString(),liveMessage(read,StashCatalog.observationBasis(migrated)));migrated=StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("3,64,0");
        assert migrated.get("observedAt").getAsLong()>1234&&migrated.getAsJsonObject("items").get("minecraft:obsidian").getAsInt()==128 : "An actual contents read still replaces and refreshes a migrated snapshot";
        presence.addProperty("capacity",54);presence.add("parts",JsonParser.parseString("[{x:2,y:64,z:0},{x:3,y:64,z:0}]").getAsJsonArray());
        StashCatalog.acceptLive(root,"A",scope,WORKER.toString(),liveMessage(presence,StashCatalog.observationBasis(migrated)));
        assert TaskWire.text(StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("3,64,0"),"status").equals("unscanned") : "A known shape change still requires a real read";
        legacy.addProperty("slotCount",54);legacy.addProperty("occupiedSlots",1);StashCatalog.record(root,"A",scope,p,legacy);
        record=StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("3,64,0");presence.addProperty("capacity",27);presence.add("parts",JsonParser.parseString("[{x:3,y:64,z:0}]").getAsJsonArray());
        StashCatalog.acceptLive(root,"A",scope,WORKER.toString(),liveMessage(presence,StashCatalog.observationBasis(record)));
        assert TaskWire.text(StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("3,64,0"),"status").equals("unscanned") : "Legacy slot-count evidence must not be contradicted";
        legacy.remove("slotCount");legacy.remove("occupiedSlots");legacy.addProperty("capacity",54);StashCatalog.record(root,"A",scope,p,legacy);
        record=StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("3,64,0");StashCatalog.acceptLive(root,"A",scope,WORKER.toString(),liveMessage(presence,StashCatalog.observationBasis(record)));
        assert TaskWire.text(StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("3,64,0"),"status").equals("unscanned") : "Known capacity changes cannot preserve legacy stock either";
        legacy.remove("capacity");legacy.getAsJsonArray("shulkers").get(0).getAsJsonObject().addProperty("slot",53);StashCatalog.record(root,"A",scope,p,legacy);
        record=StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("3,64,0");StashCatalog.acceptLive(root,"A",scope,WORKER.toString(),liveMessage(presence,StashCatalog.observationBasis(record)));
        assert TaskWire.text(StashCatalog.get(root,"A",scope,"Live").getAsJsonObject("containers").getAsJsonObject("3,64,0"),"status").equals("unscanned") : "Out-of-range legacy kit slots are evidence of an incompatible shape";
    }
    private static void stashExpansion() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("monocle-stash-expansion-");String scope="server\nnether";
        JsonObject p=JsonParser.parseString("{name:'Large',homeName:'large',minX:0,maxX:16384,minY:64,maxY:64,minZ:0,maxZ:0}").getAsJsonObject();StashCatalog.define(root,"A",scope,p);
        var file=root.resolve("stashes").resolve(TaskFiles.hash("A\n"+scope+"\nLarge")+".sqlite");JsonObject db=StashStore.read(file),records=db.getAsJsonObject("containers");
        JsonObject o=JsonParser.parseString("{x:0,y:64,z:0,status:'unscanned',block:'minecraft:chest',capacity:27,items:{},shulkers:[]}").getAsJsonObject();
        for(int x=0;x<StashCatalog.MAX_CONTAINERS;x++){o.addProperty("x",x);records.add(StashCatalog.containerKey(o),o.deepCopy());}StashStore.write(file,db);
        var frames=StashCatalog.watchFrames(root,"A");assert frames.size()==StashCatalog.MAX_CONTAINERS/StashCatalog.WATCH_PAGE_SIZE;
        assert frames.stream().mapToInt(f->f.getAsJsonArray("records").size()).sum()==StashCatalog.MAX_CONTAINERS;
        for(JsonObject frame:frames)TaskFiles.jsonBytes(frame,CrewFrames.MAX_BYTES);
        JsonObject baseline=StashCatalog.localWatches(root,true).get(0).getAsJsonObject().getAsJsonObject("containers");
        assert baseline.size()==StashCatalog.MAX_CONTAINERS&&baseline.getAsJsonObject("16383,64,0").equals(StashCatalog.watchRecord(records.getAsJsonObject("16383,64,0"))) : "Compact local watches retain the full 16,384-record baseline and exact CAS hashes";
        assert StashCatalog.watchFrames(root,"A",StashCatalog.LEGACY_MAX_CONTAINERS).isEmpty() : "Older workers get neither oversized nor partially truncated baselines";
        o.addProperty("x",StashCatalog.MAX_CONTAINERS);rejects(()->StashCatalog.record(root,"A",scope,p,o));
        records.getAsJsonObject("0,64,0").remove("capacity");records.getAsJsonObject("0,64,0").addProperty("status","removed");StashStore.write(file,db);
        StashCatalog.record(root,"A",scope,p,o);assert StashCatalog.get(root,"A",scope,"Large").getAsJsonObject("containers").size()==StashCatalog.MAX_CONTAINERS : "Only an old tombstone may make room at the limit";
        JsonObject action=StashCatalog.plan(p);action.addProperty("type","StashScan");JsonObject run=new JsonObject();run.addProperty("id",WORKER.toString());run.addProperty("token",OTHER.toString());run.addProperty("stashToken",OTHER.toString());run.addProperty("stashDelivery",4096);run.add("action",action);
        JsonObject task=new JsonObject();task.addProperty("id","large-scan");task.addProperty("crew","A");task.addProperty("server","server");task.addProperty("dimension","nether");JsonObject runs=new JsonObject();runs.add(WORKER.toString(),run);task.add("runs",runs);
        JsonObject finding=TaskWire.message("stash-findings");finding.addProperty("task","large-scan");finding.addProperty("run",WORKER.toString());finding.addProperty("token",OTHER.toString());finding.addProperty("delivery",4097);finding.add("action",action);finding.add("observation",o);
        assert StashCatalog.accept(root,task,run,WORKER.toString(),"A",finding).get("delivery").getAsInt()==4097&&run.get("stashDelivery").getAsInt()==4097 : "Large scans still receive durable ordered acknowledgements past 4096";

        JsonObject small=JsonParser.parseString("{name:'Grow',homeName:'oldhome',minX:0,maxX:2,minY:64,maxY:64,minZ:0,maxZ:0}").getAsJsonObject();
        JsonObject stock=JsonParser.parseString("{x:0,y:64,z:0,status:'observed',block:'minecraft:chest',slotCount:27,occupiedSlots:1,items:{'minecraft:obsidian':64},shulkers:[]}").getAsJsonObject();StashCatalog.record(root,"A",scope,small,stock);
        var growFile=root.resolve("stashes").resolve(TaskFiles.hash("A\n"+scope+"\nGrow")+".sqlite");JsonObject grow=StashStore.read(growFile);grow.add("columnReservations",JsonParser.parseString("{reserved:{manual:true}}").getAsJsonObject());StashStore.write(growFile,grow);
        JsonObject changes=JsonParser.parseString("{minX:-2,maxY:65,homeName:'newhome'}").getAsJsonObject();
        StashCatalog.revise(root,"A",scope,"Grow",TaskWire.text(StashCatalog.summary(grow),"revision"),changes);
        stock.getAsJsonObject("items").addProperty("minecraft:obsidian",128);StashCatalog.record(root,"A",scope,small,stock);
        grow=StashCatalog.get(root,"A",scope,"Grow");assert grow.getAsJsonObject("bounds").get("minX").getAsInt()==-2&&grow.getAsJsonObject("bounds").get("maxY").getAsInt()==65&&TaskWire.text(grow.getAsJsonObject("bounds"),"homeName").equals("newhome") : "An old scan cannot revert expanded bounds or a newer home route";
        assert grow.getAsJsonObject("columnReservations").has("reserved")&&grow.getAsJsonObject("containers").size()==1 : "Expansion preserves stock and reservations, without inventing new empty chests";
        String revision=TaskWire.text(StashCatalog.summary(grow),"revision");JsonObject shrink=JsonParser.parseString("{minX:-1}").getAsJsonObject();rejects(()->StashCatalog.revise(root,"A",scope,"Grow",revision,shrink));
    }
    private static void stashExports() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("monocle-stash-exports-");String scope="server\nnether",worker=WORKER.toString(),owner="host:6969#credential-selector";
        JsonObject p=StashCatalog.plan(JsonParser.parseString("{name:'Exports',homeName:'main',minX:0,maxX:300,minY:64,maxY:64,minZ:0,maxZ:0}").getAsJsonObject());
        StashCatalog.importDefinition(root,"A",scope,p,worker,"");String revision=StashCatalog.definitionRevision(StashCatalog.get(root,"A",scope,"Exports"));
        JsonObject o=JsonParser.parseString("{x:0,y:64,z:0,status:'observed',block:'minecraft:chest',items:{'minecraft:obsidian':64},shulkers:[]}").getAsJsonObject();StashCatalog.record(root,"A",scope,p,o);
        assert revision.equals(StashCatalog.definitionRevision(StashCatalog.get(root,"A",scope,"Exports"))) : "Reading stock must not invalidate definition edits";
        JsonObject route=p.deepCopy();route.addProperty("homeName","worker-home");route.addProperty("homeCooldownTicks",320);StashCatalog.importDefinition(root,"A",scope,route,worker,revision);
        JsonObject db=StashCatalog.get(root,"A",scope,"Exports");assert TaskWire.text(db.getAsJsonObject("bounds"),"homeName").equals("main")&&TaskWire.text(db.getAsJsonObject("homes").getAsJsonObject(worker),"name").equals("worker-home");
        rejects(()->StashCatalog.importDefinition(root,"A",scope,p,worker,revision));
        JsonObject expanded=p.deepCopy();expanded.addProperty("maxX",301);expanded.addProperty("homeName","host-home");StashCatalog.define(root,"A",scope,expanded);
        rejects(()->StashCatalog.define(root,"A",scope,p));rejects(()->StashCatalog.importDefinition(root,"A",scope,p,worker,""));
        JsonObject m=TaskWire.message("stash-import");m.add("stash",p);m.addProperty("scope",scope);m.addProperty("exportId",UUID.randomUUID().toString());m.addProperty("definitionRevision",revision);StashCatalog.attachObservation(m,o);
        assert TaskWire.text(StashCatalog.acceptExport(root,"A",worker,m),"outcome").equals("accepted") : "An old scan can export stock without undoing the newer definition";
        o.getAsJsonObject("items").addProperty("minecraft:obsidian",128);StashCatalog.record(root,"A",scope,expanded,o);
        assert TaskWire.text(StashCatalog.acceptExport(root,"A",worker,m),"outcome").equals("accepted");
        db=StashCatalog.get(root,"A",scope,"Exports");assert db.getAsJsonObject("containers").getAsJsonObject("0,64,0").getAsJsonObject("items").get("minecraft:obsidian").getAsInt()==128 : "Lost ACK retry, including after restart, cannot replay old contents";
        JsonObject definition=TaskWire.message("stash-definition");definition.add("stash",p);definition.addProperty("scope",scope);definition.addProperty("definitionRevision",revision);definition.addProperty("exportId",UUID.randomUUID().toString());
        assert TaskWire.text(StashCatalog.acceptExport(root,"A",worker,definition),"outcome").equals("conflict");
        assert TaskWire.text(StashCatalog.get(root,"A",scope,"Exports").getAsJsonObject("bounds"),"homeName").equals("host-home");
        JsonObject moving=p.deepCopy();moving.addProperty("name","Moving");StashCatalog.define(root,"A",scope,moving);
        JsonObject moved=moving.deepCopy();moved.addProperty("minX",301);moved.addProperty("maxX",600);StashCatalog.define(root,"A",scope,moved);
        JsonObject outside=m.deepCopy();outside.add("stash",moving);outside.addProperty("exportId",UUID.randomUUID().toString());
        assert TaskWire.text(StashCatalog.acceptExport(root,"A",worker,outside),"outcome").equals("conflict") : "An old read outside a moved, previously unobserved selection is retained for review, not a connection rejection";
        for(int x=0;x<300;x++){o.addProperty("x",x);StashCatalog.attachObservation(m,o);StashExports.enqueue(root,owner,worker,m);}
        try(var files=java.nio.file.Files.walk(root.resolve("stash-export-outbox-sqlite-v1"))){assert files.filter(f->f.toString().endsWith(".json")).count()==300 : "Offline scans are not truncated at the passive observer's 256-event budget";}
        assert StashExports.next(root,"other-host",worker)==null&&StashExports.next(root,owner,OTHER.toString())==null : "Exports cannot cross a host or account boundary";
        JsonObject first=StashExports.next(root,owner,worker),receipt=TaskWire.message("stash-export-ack");receipt.add("exportId",first.getAsJsonObject("message").get("exportId"));receipt.addProperty("outcome","accepted");
        StashExports.enqueue(root,owner,worker,first.getAsJsonObject("message"));StashExports.acknowledge(root,first,receipt);
        try(var files=java.nio.file.Files.walk(root.resolve("stash-export-outbox-sqlite-v1"))){assert files.filter(f->f.toString().endsWith(".json")).count()==300 : "An old receipt cannot delete a newer queued read of that chest";}
        JsonObject newer=StashExports.next(root,owner,worker);
        receipt.add("exportId",newer.getAsJsonObject("message").get("exportId"));receipt.addProperty("outcome","conflict");receipt.addProperty("detail","Definition changed");StashExports.acknowledge(root,newer,receipt);
        assert !StashExports.next(root,owner,worker).equals(newer) : "A conflicted export stays on disk but does not block unrelated cells";
        assert StashExports.next(root,owner,worker,"server\nother-world")==null : "A queued old-world export must not block another world's work";
        JsonObject accepted=StashExports.next(root,owner,worker);receipt.add("exportId",accepted.getAsJsonObject("message").get("exportId"));receipt.addProperty("outcome","deferred");StashExports.acknowledge(root,accepted,receipt);assert StashExports.next(root,owner,worker).equals(accepted);
        receipt.addProperty("outcome","accepted");StashExports.acknowledge(root,accepted,receipt);try(var files=java.nio.file.Files.walk(root.resolve("stash-export-outbox-sqlite-v1"))){assert files.filter(f->f.toString().endsWith(".json")).count()==299 : "Only a matching accepted receipt retires the export";}
    }
    private static void stashOverlap() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("monocle-stash-overlap-");
        JsonObject p=JsonParser.parseString("{name:'Old',homeName:'old',minX:0,maxX:2,minY:100,maxY:101,minZ:0,maxZ:1}").getAsJsonObject();
        JsonObject box=JsonParser.parseString("{slot:0,item:'minecraft:blue_shulker_box',quantity:1,name:'Kit',contentsKnown:true,items:{'minecraft:obsidian':64}}").getAsJsonObject();
        JsonObject observation=JsonParser.parseString("{x:0,y:100,z:0,status:'observed',block:'minecraft:chest',reason:'',items:{'minecraft:obsidian':64},shulkers:[]}").getAsJsonObject();
        observation.getAsJsonArray("shulkers").add(box);String kit=StashCatalog.kitTypeId(box);
        StashCatalog.save(root,"A","server\nnether",p,observation);
        JsonObject expanded=p.deepCopy();expanded.addProperty("name","Main");expanded.addProperty("maxX",3);
        JsonObject newer=observation.deepCopy();newer.getAsJsonObject("items").addProperty("minecraft:obsidian",128);
        newer.getAsJsonArray("shulkers").get(0).getAsJsonObject().addProperty("quantity",2);
        StashCatalog.save(root,"B","server\nnether",expanded,newer);
        // Fixed timestamps avoid relying on the two writes landing in different milliseconds.
        var oldFile=root.resolve("stashes").resolve(TaskFiles.hash("A\nserver\nnether\nOld")+".sqlite");
        JsonObject old=StashStore.read(oldFile);old.getAsJsonObject("containers").getAsJsonObject("0,100,0").addProperty("observedAt",0);StashStore.write(oldFile,old);
        JsonArray views=StashCatalog.list(root);
        assert overlapItems(views)==128 && overlapKits(views,kit)==2 : "Overlapping stashes/crews count a physical container only once";
        assert StashCatalog.get(root,"A","server\nnether","Old").getAsJsonObject("containers").size()==1 : "Aggregation must not erase old observations";
        StashCatalog.save(root,"A","other-server\nnether",p,observation);
        StashCatalog.save(root,"A","server\nend",p,observation);
        assert overlapItems(StashCatalog.list(root))==256 && overlapKits(StashCatalog.list(root),kit)==4 : "Server and dimension distinguish physically separate storage";
        JsonObject estimate=observation.deepCopy();estimate.addProperty("inferred",true);estimate.getAsJsonObject("items").addProperty("minecraft:obsidian",999);
        JsonObject estimated=p.deepCopy();estimated.addProperty("name","Estimate");StashCatalog.save(root,"A","server\nnether",estimated,estimate);
        assert overlapItems(StashCatalog.list(root))==256 : "A newer estimate cannot replace a measured observation";
        JsonObject empty=observation.deepCopy();empty.add("items",new JsonObject());empty.add("shulkers",new JsonArray());
        StashCatalog.save(root,"B","server\nnether",expanded,empty);
        assert overlapItems(StashCatalog.list(root))==128 && overlapKits(StashCatalog.list(root),kit)==2 : "A newly observed empty chest must suppress older stock";
        StashCatalog.save(root,"B","server\nnether",expanded,newer);
        JsonObject picks=new JsonObject();picks.addProperty("x",0);picks.addProperty("y",100);picks.addProperty("z",0);picks.addProperty("kitTypeId",kit);
        JsonArray withdrawn=new JsonArray();withdrawn.add(picks);StashCatalog.invalidateWithdrawn(root,"B","server\nnether","Main",withdrawn);
        assert overlapItems(StashCatalog.list(root))==128 && overlapKits(StashCatalog.list(root),kit)==2 : "A confirmed withdrawal invalidation must not resurrect an older stash scan";
        assert !StashCatalog.wireSummary(StashCatalog.list(root).get(0).getAsJsonObject()).has("deduplicatedItems") : "Detailed aggregates stay out of the bounded worker catalog frame";
    }
    private static long overlapItems(JsonArray views){long total=0;for(var value:views){JsonObject items=value.getAsJsonObject().getAsJsonObject("deduplicatedItems");if(items.has("minecraft:obsidian"))total+=items.get("minecraft:obsidian").getAsLong();}return total;}
    private static long overlapKits(JsonArray views,String kit){long total=0;for(var value:views){JsonObject counts=value.getAsJsonObject().getAsJsonObject("deduplicatedKitCounts");if(counts.has(kit))total+=counts.getAsJsonObject(kit).get("complete").getAsLong();}return total;}
    private static void stashScan() throws Exception {
        JsonObject p=JsonParser.parseString("{\"name\":\"Depot\",\"homeName\":\"main-stash_1\",\"minX\":-2,\"maxX\":2,\"minY\":116,\"maxY\":117,\"minZ\":-1,\"maxZ\":1}").getAsJsonObject();
        p=StashCatalog.plan(p);
        assert p.get("homeName").getAsString().equals("main-stash_1")&&p.get("homeWarmupTicks").getAsInt()==300&&p.get("homeCooldownTicks").getAsInt()==12_000;
        JsonObject missingHome=p.deepCopy();missingHome.remove("homeName");rejects(()->StashCatalog.plan(missingHome));
        JsonObject home=p.deepCopy();home.addProperty("homeName","main-stash_1");home.addProperty("homeWarmupTicks",400);home.addProperty("homeCooldownTicks",24_000);assert StashCatalog.plan(home).get("homeName").getAsString().equals("main-stash_1");
        JsonObject badHome=p.deepCopy();badHome.addProperty("homeName","bad home");rejects(()->StashCatalog.plan(badHome));
        for(int workers=1;workers<=5;workers++)for(int x=-2;x<=2;x++)for(int y=116;y<=117;y++)for(int z=-1;z<=1;z++){
            int owners=0;for(int index=0;index<workers;index++){JsonObject a=p.deepCopy();a.addProperty("workerCount",workers);a.addProperty("workerIndex",index);if(StashCatalog.owns(a,x,y,z))owners++;}assert owners==1;
        }
        var root=java.nio.file.Files.createTempDirectory("monocle-stash-check-");
        StashCatalog.define(root,"A","server\nnether",p);
        StashCatalog.define(root,"A","server\nnether",home,"00000000-0000-0000-0000-000000000001");
        JsonObject routed=StashCatalog.route(root,"A","server\nnether",p,"00000000-0000-0000-0000-000000000001");assert routed.get("homeName").getAsString().equals("main-stash_1")&&StashCatalog.list(root).get(0).getAsJsonObject().getAsJsonObject("homes").size()==1;
        long now=System.nanoTime();
        var near=new PlayerObservation(WORKER,"Near","server\nnether",new PlayerObservation.Position(0,116,0),now);
        var far=new PlayerObservation(OTHER,"Far","server\nnether",new PlayerObservation.Position(100,116,0),now);
        assert StashCatalog.nearby(p,near.position())&&!StashCatalog.nearby(p,far.position());
        assert StashCatalog.nearby(p,far.position(),128)&&!StashCatalog.nearby(p,new PlayerObservation.Position(200,116,0),128);
        assert StashCatalog.nearby(p,new PlayerObservation.Position(200,116,0),256)&&!StashCatalog.nearby(p,new PlayerObservation.Position(300,116,0),256);
        JsonObject joined=StashCatalog.scanRoute(root,"A","server\nnether",p,OTHER.toString(),List.of(near,far),now);
        assert joined.get("scanAnchor").getAsString().equals(WORKER.toString()) : "Remote scanners rendezvous with a nearby worker";
        assert !StashCatalog.scanRoute(root,"A","server\nnether",p,WORKER.toString(),List.of(near,far),now).has("scanAnchor");
        assert !StashCatalog.scanRoute(root,"A","server\nnether",p,OTHER.toString(),List.of(new PlayerObservation(WORKER,"Near","server\nnether",near.position(),now-PlayerObservation.MAX_AGE),far),now).has("scanAnchor") : "Stale anchors fall back to /home";
        String scanScript=new dev.monocle.client.systems.bots.BotWorkflows(null).get("task-stash-scan").script();
        var rendezvous=BotLua.next(scanScript,new JsonObject(),joined,null,null);
        assert rendezvous.action().get("type").getAsString().equals("Tpa")&&rendezvous.action().get("allowFailure").getAsBoolean();
        assert BotLua.next(scanScript,rendezvous.state(),joined,new JsonObject(),null).action().get("type").getAsString().equals("StashScan") : "Failed TPA must continue to /home fallback";
        StashCatalog.cacheRemote(root,StashCatalog.list(root));assert StashCatalog.remote(root).size()==1;
        assert StashCatalog.list(root).size()==1&&StashCatalog.list(root).get(0).getAsJsonObject().get("observed").getAsInt()==0 : "Exported definitions are visible before scanning";
        JsonObject observation=JsonParser.parseString("{\"x\":0,\"y\":116,\"z\":0,\"status\":\"observed\",\"block\":\"minecraft:chest\",\"reason\":\"\",\"items\":{\"minecraft:stone\":1728},\"shulkers\":[]}").getAsJsonObject();
        StashCatalog.save(root,"A","server\nnether",p,observation);StashCatalog.save(root,"A","server\nnether",p,observation);
        StashCatalog.record(root,"A","server\nnether",p,observation);
        assert StashCatalog.list(root).size()==1 && StashCatalog.list(root).get(0).getAsJsonObject().getAsJsonObject("items").get("minecraft:stone").getAsInt()==1728 : "Retries never add stock twice";
        JsonObject mapped=StashCatalog.list(root).get(0).getAsJsonObject();
        assert mapped.getAsJsonObject("columns").getAsJsonObject("0,0").getAsJsonObject("items").get("minecraft:stone").getAsInt()==1728;
        JsonObject columnPlan=p.deepCopy();columnPlan.addProperty("scanMode","Column Map");assert StashCatalog.plan(columnPlan).get("scanMode").getAsString().equals("Column Map");
        JsonObject box=JsonParser.parseString("{slot:0,item:'minecraft:purple_shulker_box',quantity:1,name:'Raid kit',contentsKnown:true,items:{'minecraft:obsidian':64,'minecraft:totem_of_undying':2}}").getAsJsonObject();
        JsonObject dense=observation.deepCopy(), contents=box.getAsJsonObject("items").deepCopy();
        for(int i=0;i<24;i++)contents.addProperty("minecraft:kit_item_"+i,64);
        JsonArray boxes=new JsonArray();for(int i=0;i<54;i++){JsonObject packed=box.deepCopy();packed.addProperty("slot",i);packed.add("items",contents.deepCopy());boxes.add(packed);}dense.add("shulkers",boxes);
        assert dense.toString().length()>32_000;
        StashCatalog.observation(p,dense);
        JsonObject wire=TaskWire.message("stash-findings");StashCatalog.attachObservation(wire,dense);
        assert wire.has("observationGzip") && TaskFiles.jsonBytes(wire,16_000).length<16_000 && StashCatalog.readObservation(wire).equals(dense);
        JsonObject kits=observation.deepCopy();kits.getAsJsonArray("shulkers").add(box);StashCatalog.save(root,"A","server\nnether",p,kits);
        String kitId=StashCatalog.kitTypeId(box);assert !kitId.isBlank();
        JsonObject partial=box.deepCopy();partial.getAsJsonObject("items").addProperty("minecraft:obsidian",32);
        JsonObject partialObservation=kits.deepCopy();partialObservation.addProperty("x",1);partialObservation.getAsJsonArray("shulkers").set(0,partial);StashCatalog.save(root,"A","server\nnether",p,partialObservation);
        JsonObject kitSummary=StashCatalog.list(root).get(0).getAsJsonObject();
        assert kitSummary.getAsJsonObject("kitCounts").getAsJsonObject(kitId).get("complete").getAsInt()==1&&kitSummary.getAsJsonObject("kitCounts").getAsJsonObject(kitId).get("incomplete").getAsInt()==1;
        assert kitSummary.getAsJsonObject("kitTypes").getAsJsonObject(kitId).getAsJsonObject("items").get("minecraft:obsidian").getAsInt()==64;
        assert StashCatalog.kitPicks(StashCatalog.get(root,"A","server\nnether","Depot"),kitId,1,false).size()>=1;
        assert StashCatalog.kitPicks(StashCatalog.get(root,"A","server\nnether","Depot"),kitId,1,true).get(0).getAsJsonObject().get("x").getAsInt()==1;
        JsonArray mappedKits=StashCatalog.kitPicks(StashCatalog.get(root,"A","server\nnether","Depot"),kitId,2,false);
        assert mappedKits.size()>=2&&mappedKits.get(0).getAsJsonObject().get("dynamic").getAsBoolean()&&mappedKits.get(1).getAsJsonObject().get("y").getAsInt()==117 : "Observed and unscanned chests are searched live, not bound to stale slots";
        JsonObject crowded=StashCatalog.get(root,"A","server\nnether","Depot");JsonArray crowdedBoxes=crowded.getAsJsonObject("containers").getAsJsonObject("0,116,0").getAsJsonArray("shulkers");
        for(int i=1;i<27;i++){JsonObject packed=box.deepCopy();packed.addProperty("slot",i);crowdedBoxes.add(packed);}
        JsonArray crowdedPicks=StashCatalog.kitPicks(crowded,kitId,27,false);
        assert crowdedPicks.get(0).getAsJsonObject().get("dynamic").getAsBoolean()
            &&java.util.stream.StreamSupport.stream(crowdedPicks.spliterator(),false).filter(e->e.getAsJsonObject().get("x").getAsInt()==0&&e.getAsJsonObject().get("y").getAsInt()==116).count()==1 : "A full chest is one live search target, not 27 stale slot promises";
        JsonArray upperReceipt=new JsonArray();upperReceipt.add(mappedKits.get(1).deepCopy());StashCatalog.invalidateWithdrawn(root,"A","server\nnether","Depot",upperReceipt);
        assert StashCatalog.get(root,"A","server\nnether","Depot").getAsJsonObject("containers").getAsJsonObject("0,117,0").get("status").getAsString().equals("unscanned") : "A discovered upper chest is recorded without rejecting the worker receipt";
        JsonObject overflow=p.deepCopy();overflow.addProperty("name","Overflow");overflow.addProperty("minX",100);overflow.addProperty("maxX",100);StashCatalog.define(root,"A","server\nnether",overflow);
        JsonObject kitRequest=p.deepCopy();kitRequest.addProperty("kitTypeId",kitId);kitRequest.addProperty("count",1);kitRequest.addProperty("destination","Stash");kitRequest.addProperty("targetStashName","Overflow");
        JsonObject kitAction=StashCatalog.kitAction(root,"A","server\nnether",kitRequest,WORKER.toString());
        assert kitAction.getAsJsonObject("targetStash").get("name").getAsString().equals("Overflow")&&kitAction.getAsJsonArray("picks").size()>=1;
        assert kitAction.getAsJsonObject("targetStash").getAsJsonObject("kitExemplar").get("minecraft:obsidian").getAsInt()==64&&!kitAction.getAsJsonObject("targetStash").get("incomplete").getAsBoolean();
        String kitScript=new dev.monocle.client.systems.bots.BotWorkflows(null).get("task-kit-delivery").script();
        BotLua.Decision pickup=BotLua.next(kitScript,new JsonObject(),kitAction,null,null);
        assert pickup.action().get("type").getAsString().equals("StashResupply");
        JsonObject firstReceipt=JsonParser.parseString("{picked:1,nextPick:1,ok:true}").getAsJsonObject();
        assert BotLua.next(kitScript,pickup.state(),kitAction,firstReceipt,null).action().get("type").getAsString().equals("StashDeposit");
        JsonObject multiRequest=kitRequest.deepCopy();multiRequest.addProperty("count",3);JsonObject multiKit=StashCatalog.kitAction(root,"A","server\nnether",multiRequest,WORKER.toString());
        BotLua.Decision first=BotLua.next(kitScript,new JsonObject(),multiKit,null,null),store=BotLua.next(kitScript,first.state(),multiKit,firstReceipt,null),again=BotLua.next(kitScript,store.state(),multiKit,new JsonObject(),null);
        assert store.action().get("count").getAsInt()==1&&again.action().get("type").getAsString().equals("StashResupply")&&again.action().get("count").getAsInt()==2&&again.action().get("pickIndex").getAsInt()==1 : "The next batch resumes after the confirmed source pick";
        BotLua.Decision finalStore=BotLua.next(kitScript,again.state(),multiKit,JsonParser.parseString("{picked:2,nextPick:1,ok:true}").getAsJsonObject(),null);
        assert finalStore.action().get("count").getAsInt()==2&&BotLua.next(kitScript,finalStore.state(),multiKit,new JsonObject(),null).action().get("type").getAsString().equals("Done");
        JsonObject playerRequest=kitRequest.deepCopy();playerRequest.addProperty("destination","Player");playerRequest.addProperty("recipient",OTHER.toString());playerRequest.addProperty("recipientName","KitRecipient");playerRequest.addProperty("incomplete",true);
        JsonObject playerKit=StashCatalog.kitAction(root,"A","server\nnether",playerRequest,WORKER.toString());
        BotLua.Decision playerPickup=BotLua.next(kitScript,new JsonObject(),playerKit,null,null),teleport=BotLua.next(kitScript,playerPickup.state(),playerKit,firstReceipt,null),drop=BotLua.next(kitScript,teleport.state(),playerKit,new JsonObject(),null);
        assert drop.action().get("type").getAsString().equals("DropItems")&&drop.action().get("incomplete").getAsBoolean()&&drop.action().getAsJsonObject("kitExemplar").get("minecraft:obsidian").getAsInt()==64;
        assert drop.action().get("handoff").getAsBoolean();
        playerRequest.addProperty("returnHome",true);
        JsonObject courier=StashCatalog.kitAction(root,"A","server\nnether",playerRequest,WORKER.toString());
        assert courier.getAsJsonObject("returnStash").get("dimension").getAsString().equals("nether")
            &&courier.getAsJsonObject("returnStash").get("homeName").equals(courier.get("homeName")) : "Return uses the source's worker-specific home and dimension";
        var courierTake=BotLua.next(kitScript,new JsonObject(),courier,null,null);
        var courierTpa=BotLua.next(kitScript,courierTake.state(),courier,firstReceipt,null);
        assert courierTpa.action().get("allowFailure").getAsBoolean();
        var arrival=JsonParser.parseString("{ok:true,deliveryAnchor:{x:5,y:64,z:8,yaw:90,dimension:'minecraft:the_end'}}").getAsJsonObject();
        var courierDrop=BotLua.next(kitScript,courierTpa.state(),courier,arrival,null);
        assert courierDrop.action().get("allowFailure").getAsBoolean()&&courierDrop.action().getAsJsonObject("deliveryAnchor").equals(arrival.getAsJsonObject("deliveryAnchor"));
        var courierHome=BotLua.next(kitScript,courierDrop.state(),courier,JsonParser.parseString("{ok:true,dropped:1}").getAsJsonObject(),null);
        assert courierHome.action().get("type").getAsString().equals("StashVisit") : "Final player delivery returns before declaring completion";
        var courierDone=BotLua.next(kitScript,courierHome.state(),courier,JsonParser.parseString("{ok:true}").getAsJsonObject(),null);
        assert courierDone.action().getAsJsonObject("result").get("returnedHome").getAsBoolean()&&courierDone.action().getAsJsonObject("result").get("delivered").getAsInt()==1;
        var tpaFailed=BotLua.next(kitScript,courierTpa.state(),courier,JsonParser.parseString("{ok:false,detail:'TPA not accepted'}").getAsJsonObject(),null);
        assert tpaFailed.action().get("type").getAsString().equals("StashVisit");
        var failedReturn=BotLua.next(kitScript,tpaFailed.state(),courier,JsonParser.parseString("{ok:true}").getAsJsonObject(),null);
        assert failedReturn.action().get("type").getAsString().equals("StashDeposit")&&failedReturn.action().get("count").getAsInt()==1&&failedReturn.action().get("name").equals(courier.get("name")) : "An unsuccessful request redeposits only its undelivered cargo at the source";
        assert BotLua.next(kitScript,failedReturn.state(),courier,JsonParser.parseString("{deposited:1,missing:0}").getAsJsonObject(),null).action().get("type").getAsString().equals("Fail");
        var partialHandoff=BotLua.next(kitScript,JsonParser.parseString("{phase:'dropping',batch:3,total:0}").getAsJsonObject(),courier,JsonParser.parseString("{ok:false,dropped:1,detail:'Recipient left during handoff'}").getAsJsonObject(),null);
        var partialReturn=BotLua.next(kitScript,partialHandoff.state(),courier,JsonParser.parseString("{ok:true}").getAsJsonObject(),null);
        assert partialReturn.action().get("type").getAsString().equals("StashDeposit")&&partialReturn.action().get("count").getAsInt()==2 : "Already delivered kits must never be returned or counted twice";
        var blocked=BotLua.next(kitScript,courierDrop.state(),courier,JsonParser.parseString("{ok:false,dropped:0,detail:'No supported approach'}").getAsJsonObject(),null);
        assert blocked.action().get("type").getAsString().equals("StashVisit")&&blocked.state().get("total").getAsInt()==0;
        assert TaskWire.text(BotLua.next(kitScript,courierDrop.state(),courier,JsonParser.parseString("{ok:false,detail:'Unknown receipt'}").getAsJsonObject(),null).action(),"type").equals("Fail") : "A failed drop without confirmation counts cannot claim the batch was delivered";
        var invalidReturn=playerRequest.deepCopy();invalidReturn.addProperty("returnHome","yes");
        try{StashCatalog.kitAction(root,"A","server\nnether",invalidReturn,WORKER.toString());throw new AssertionError("Invalid return flag accepted");}catch(IllegalArgumentException expected){}
        JsonArray deposited=new JsonArray();deposited.add(JsonParser.parseString("{x:100,y:116,z:0}"));StashCatalog.invalidateDeposited(root,"A","server\nnether","Overflow",deposited);
        assert StashCatalog.get(root,"A","server\nnether","Overflow").getAsJsonObject("containers").getAsJsonObject("100,116,0").get("status").getAsString().equals("unscanned");
        assert StashCatalog.get(root,"A","server\nnether","Overflow").getAsJsonObject("containers").getAsJsonObject("100,116,0").get("reason").getAsString().contains("attempted") : "An attempted transfer cannot be reported as a verified deposit";
        JsonObject missed=observation.deepCopy();missed.addProperty("status","unscanned");missed.add("items",new JsonObject());missed.addProperty("reason","Opening timed out");
        StashCatalog.save(root,"A","server\nnether",p,missed);
        JsonObject missedSummary=StashCatalog.summary(StashCatalog.get(root,"A","server\nnether","Depot"));
        assert missedSummary.get("unscanned").getAsInt()==2 : "Inspect Depot, not the filesystem's first entry (which may be Overflow)";
        JsonObject inferred=observation.deepCopy();inferred.addProperty("inferred",true);StashCatalog.save(root,"A","server\nnether",p,inferred);
        JsonObject inferredSummary=StashCatalog.summary(StashCatalog.get(root,"A","server\nnether","Depot"));
        assert inferredSummary.get("inferred").getAsInt()==1&&inferredSummary.get("observed").getAsInt()==1 : "Estimates never masquerade as observed containers";
        assert StashCatalog.get(root,"B","server\nnether","Depot").isEmpty();
        JsonObject invalid=observation.deepCopy();invalid.addProperty("x",3);JsonObject assignment=p;
        rejects(()->StashCatalog.save(root,"A","server\nnether",assignment,invalid));
        invalid.addProperty("x",0);invalid.getAsJsonObject("items").addProperty("minecraft:stone",-1);rejects(()->StashCatalog.observation(assignment,invalid));
        JsonObject refillDb=JsonParser.parseString("{containers:{'1,116,2':{status:'observed',shulkers:[{slot:0,dominant:'minecraft:obsidian',mixed:false,items:{'minecraft:obsidian':1728}},{slot:1,dominant:'minecraft:golden_apple',mixed:false,items:{'minecraft:golden_apple':1728}}]},'2,116,2':{status:'observed',inferred:true,shulkers:[{slot:0,dominant:'minecraft:obsidian',mixed:false,items:{'minecraft:obsidian':1728}}]}}}").getAsJsonObject();
        JsonObject inventory=JsonParser.parseString("{target:[3456,2,64,64,64],loose:[0,0,0,0,0],echest:[0,0,0,0,0],shulkers:[0,0,0,0,0]}").getAsJsonObject();JsonObject planned=StashCatalog.refillNeeds(refillDb,inventory,0,"minecraft:obsidian");assert planned!=null&&planned.get("_primary").getAsString().equals("minecraft:obsidian");
        JsonObject needs=JsonParser.parseString("{'minecraft:obsidian':3456,'minecraft:golden_apple':1728,'minecraft:netherrack':100}").getAsJsonObject();JsonArray refill=StashCatalog.refill(refillDb,needs,"minecraft:obsidian",27);
        assert refill.size()==2&&refill.get(0).getAsJsonObject().get("resource").getAsString().equals("minecraft:obsidian") : "Primary shortage is first; inferred stock and sub-box shortages are skipped";
        JsonObject withdrawalPlan=p.deepCopy();withdrawalPlan.addProperty("name","Withdrawals");StashCatalog.define(root,"A","server\nnether",withdrawalPlan);JsonObject withdrawalObservation=observation.deepCopy();JsonArray receipt=refill.deepCopy();receipt.forEach(v->{v.getAsJsonObject().addProperty("x",0);v.getAsJsonObject().addProperty("z",0);});StashCatalog.save(root,"A","server\nnether",withdrawalPlan,withdrawalObservation);StashCatalog.invalidateWithdrawn(root,"A","server\nnether","Withdrawals",receipt);
        assert StashCatalog.get(root,"A","server\nnether","Withdrawals").getAsJsonObject("containers").getAsJsonObject("0,116,0").get("status").getAsString().equals("unscanned") : "Withdrawn containers cannot remain authoritative";
        var decision=BotLua.next("return function(ctx) return bot.stash_scan(ctx.args) end",new JsonObject(),p,null,null);
        assert decision.action().get("type").getAsString().equals("StashScan");
        JsonObject telemetry=JsonParser.parseString("{\"name\":\"Depot\",\"phase\":\"Reading\",\"reason\":\"Awaiting contents\",\"target\":\"0,116,0\",\"movementTarget\":\"\",\"lastAction\":\"Requested opening\",\"discovery\":1,\"volume\":30,\"discovered\":1,\"observed\":0,\"unscanned\":0,\"missingChunks\":0,\"attempts\":1}").getAsJsonObject();
        JsonObject run=new JsonObject();for(int i=0;i<100;i++){telemetry.addProperty("reason","Retry "+i);StashCatalog.telemetry(run,telemetry);}assert run.getAsJsonArray("stashEvents").size()==64;
        StashCatalog.telemetry(run,telemetry);assert run.getAsJsonArray("stashEvents").size()==64;
        JsonObject wide=p.deepCopy();wide.addProperty("name","WideDepot");wide.addProperty("minX",0);wide.addProperty("maxX",60);wide.addProperty("scanMode","Full");wide.addProperty("lazyMode",false);
        StashCatalog.define(root,"A","server\nnether",wide);
        for(int x=0;x<54;x++){JsonObject chest=kits.deepCopy();chest.addProperty("x",x);StashCatalog.save(root,"A","server\nnether",wide,chest);}
        JsonObject wideRequest=wide.deepCopy();wideRequest.addProperty("kitTypeId",kitId);wideRequest.addProperty("count",2);wideRequest.addProperty("destination","Stash");wideRequest.addProperty("targetStashName","Overflow");
        JsonObject boundedAction=StashCatalog.kitAction(root,"A","server\nnether",wideRequest,WORKER.toString());
        assert boundedAction.toString().length()<=7_500&&boundedAction.getAsJsonArray("picks").size()<54 : "Crowded kit catalogs must fit the native-action wire limit";
        wideRequest.addProperty("transferAll",true);wideRequest.addProperty("includeIncomplete",true);wideRequest.addProperty("count",36);
        JsonObject all=StashCatalog.kitAction(root,"A","server\nnether",wideRequest,WORKER.toString());
        assert all.getAsJsonArray("picks").size()==54&&all.toString().length()<=24_000&&all.getAsJsonObject("targetStash").get("includeIncomplete").getAsBoolean() : "Transfer all never truncates its selected source chests";
        JsonObject allRun=JsonParser.parseString("{id:'"+WORKER+"',status:'Running'}").getAsJsonObject(),allTask=new JsonObject();
        JsonObject allReport=JsonParser.parseString("{run:'"+WORKER+"',status:'Running',token:'"+OTHER+"'}").getAsJsonObject();allReport.add("action",all);
        assert TaskWire.applyStatus(allTask,allRun,allReport,true) : "The complete source list fits the shared status wire limit";
        BotLua.Decision takeAll=BotLua.next(kitScript,new JsonObject(),all,null,null);
        BotLua.Decision storeAll=BotLua.next(kitScript,takeAll.state(),all,JsonParser.parseString("{picked:36,nextPick:0,sourceExhausted:false}").getAsJsonObject(),null);
        BotLua.Decision returnAll=BotLua.next(kitScript,storeAll.state(),all,new JsonObject(),null);
        var missingBatch=BotLua.next(kitScript,storeAll.state(),all,JsonParser.parseString("{deposited:33,missing:3}").getAsJsonObject(),null);
        assert missingBatch.action().get("type").getAsString().equals("StashResupply")&&missingBatch.state().get("total").getAsInt()==33&&missingBatch.state().get("missing").getAsInt()==3
            : "A manually moved batch logs the shortfall and starts the next load without phantom deliveries";
        try{BotLua.next(kitScript,storeAll.state(),all,JsonParser.parseString("{deposited:33,missing:0}").getAsJsonObject(),null);throw new AssertionError("Partial receipts must account for the entire requested batch");}catch(IllegalArgumentException expected){}
        assert returnAll.action().get("count").getAsInt()==36&&returnAll.action().get("pickIndex").getAsInt()==0 : "Revisit a source chest if the previous batch filled before it emptied";
        BotLua.Decision lastAll=BotLua.next(kitScript,returnAll.state(),all,JsonParser.parseString("{picked:5,nextPick:54,sourceExhausted:true}").getAsJsonObject(),null);
        assert lastAll.action().get("type").getAsString().equals("StashDeposit")&&lastAll.action().get("count").getAsInt()==5;
        BotLua.Decision recheckAll=BotLua.next(kitScript,lastAll.state(),all,new JsonObject(),null);
        assert recheckAll.action().get("type").getAsString().equals("StashResupply")&&recheckAll.action().get("pickIndex").getAsInt()==0&&recheckAll.state().get("total").getAsInt()==41 : "Hoppers may have refilled previously visited chests";
        JsonObject emptyPass=JsonParser.parseString("{picked:0,nextPick:54,sourceExhausted:true}").getAsJsonObject();
        BotLua.Decision waitAll=BotLua.next(kitScript,recheckAll.state(),all,emptyPass,null);
        assert waitAll.action().get("type").getAsString().equals("Wait")&&waitAll.action().get("ticks").getAsInt()==20;
        BotLua.Decision verifyAll=BotLua.next(kitScript,waitAll.state(),all,new JsonObject(),null);
        BotLua.Decision doneAll=BotLua.next(kitScript,verifyAll.state(),all,emptyPass,null);
        assert doneAll.action().get("type").getAsString().equals("Done")&&doneAll.action().getAsJsonObject("result").get("delivered").getAsInt()==41;
        BotLua.Decision refilled=BotLua.next(kitScript,verifyAll.state(),all,JsonParser.parseString("{picked:3,nextPick:54,sourceExhausted:true}").getAsJsonObject(),null);
        assert refilled.action().get("type").getAsString().equals("StashDeposit")&&refilled.state().get("emptyPasses").getAsInt()==0 : "A refill resets empty verification and is delivered";
        BotLua.Decision partialIssue=BotLua.next(kitScript,returnAll.state(),all,JsonParser.parseString("{picked:9,nextPick:1,sourceExhausted:false,sourceIssue:'Expected storage container, found air at 1,116,0'}").getAsJsonObject(),null);
        assert partialIssue.action().get("type").getAsString().equals("StashDeposit")&&partialIssue.action().get("count").getAsInt()==9;
        BotLua.Decision afterIssue=BotLua.next(kitScript,partialIssue.state(),all,new JsonObject(),null);
        assert afterIssue.action().get("type").getAsString().equals("Fail")&&afterIssue.action().toString().contains("Delivered 45 kits") : "Report source failure only after delivering the confirmed partial load";
        assert BotLua.next(kitScript,takeAll.state(),all,JsonParser.parseString("{picked:0,nextPick:0,sourceExhausted:false}").getAsJsonObject(),null).action().get("type").getAsString().equals("Fail");
        assert BotLua.next(kitScript,takeAll.state(),all,JsonParser.parseString("{picked:5,nextPick:0,sourceExhausted:true}").getAsJsonObject(),null).action().get("type").getAsString().equals("Fail");
        JsonObject deferred=JsonParser.parseString("{picked:0,nextPick:54,sourceExhausted:false,sourceDeferred:true,deferredSources:['Outlet 1,116,0 expects Alpha; observed Beta']}").getAsJsonObject();
        var deferDone=BotLua.next(kitScript,takeAll.state(),all,deferred,null);
        assert TaskWire.text(deferDone.action(),"type").equals("Done")&&deferDone.action().getAsJsonObject("result").get("sourceDeferred").getAsBoolean()
            &&!deferDone.action().getAsJsonObject("result").get("sourceExhausted").getAsBoolean() : "Zero-kit mismatched outlets defer the type, not the whole transfer, without claiming exhaustion";
        deferred.addProperty("picked",9);
        var deferStore=BotLua.next(kitScript,takeAll.state(),all,deferred,null);
        assert TaskWire.text(deferStore.action(),"type").equals("StashDeposit")&&deferStore.action().get("count").getAsInt()==9;
        var deferStored=BotLua.next(kitScript,deferStore.state(),all,new JsonObject(),null);
        assert TaskWire.text(deferStored.action(),"type").equals("Done")&&deferStored.action().getAsJsonObject("result").get("delivered").getAsInt()==9 : "Confirmed partial load is deposited before returning a deferral";
        JsonObject earlyDeferred=deferred.deepCopy();earlyDeferred.addProperty("picked",36);earlyDeferred.addProperty("nextPick",14);
        var earlyStore=BotLua.next(kitScript,takeAll.state(),all,earlyDeferred,null);
        var continueSources=BotLua.next(kitScript,earlyStore.state(),all,new JsonObject(),null);
        assert TaskWire.text(continueSources.action(),"type").equals("StashResupply")&&continueSources.action().get("pickIndex").getAsInt()==14 : "A deferred outlet must not restart a full load before the remaining candidates are visited";
        JsonObject lockedArgs=all.deepCopy();lockedArgs.getAsJsonObject("targetStash").add("targetColumn",JsonParser.parseString("{x:100,y:116,z:0,axis:'X'}"));
        var fullStop=BotLua.next(kitScript,storeAll.state(),lockedArgs,JsonParser.parseString("{deposited:10,missing:0,retained:26,columnFull:true}").getAsJsonObject(),null);
        assert TaskWire.text(fullStop.action(),"type").equals("Done")&&fullStop.action().getAsJsonObject("result").get("retained").getAsInt()==26&&!fullStop.action().getAsJsonObject("result").get("sourceExhausted").getAsBoolean() : "A full destination stops further pickups with undelivered kits retained, not missing";
        assert TaskWire.text(BotLua.next(kitScript,storeAll.state(),lockedArgs,JsonParser.parseString("{deposited:10,missing:0,retained:25,columnFull:true}").getAsJsonObject(),null).action(),"type").equals("Fail");
        assert TaskWire.text(BotLua.next(kitScript,storeAll.state(),all,JsonParser.parseString("{deposited:10,missing:0,retained:26,columnFull:true}").getAsJsonObject(),null).action(),"type").equals("Fail") : "Ordinary jobs cannot accept a full-column stop";
        deferred.addProperty("sourceExhausted",true);
        assert TaskWire.text(BotLua.next(kitScript,takeAll.state(),all,deferred,null).action(),"type").equals("Fail") : "A source cannot be both deferred and exhausted";
        JsonObject withdrawnSearch=StashCatalog.get(root,"A","server\nnether","WideDepot"),known=withdrawnSearch.getAsJsonObject("containers").getAsJsonObject("0,116,0");
        known.addProperty("status","unscanned");known.addProperty("reason","Contents changed by a confirmed stash withdrawal; rescan required");known.add("shulkers",new JsonArray());known.add("items",new JsonObject());
        // Exercise the shared selection through a temporary persisted catalog, not an alternate planner.
        StashCatalog.save(root,"A","server\nnether",wide,known);
        assert StashCatalog.kitAction(root,"A","server\nnether",wideRequest,WORKER.toString()).getAsJsonArray("picks").size()==54 : "Our own withdrawals invalidate quantities but retain live search targets";
        known.addProperty("reason","Opening timed out");StashCatalog.save(root,"A","server\nnether",wide,known);
        try{StashCatalog.kitAction(root,"A","server\nnether",wideRequest,WORKER.toString());throw new AssertionError("Unknown scan gaps are not known withdrawn sources");}catch(IllegalStateException expected){}
        known.addProperty("status","observed");known.add("shulkers",kits.getAsJsonArray("shulkers").deepCopy());known.add("items",kits.getAsJsonObject("items").deepCopy());StashCatalog.save(root,"A","server\nnether",wide,known);
        JsonObject extra=kits.deepCopy();extra.addProperty("x",54);StashCatalog.save(root,"A","server\nnether",wide,extra);
        try{StashCatalog.kitAction(root,"A","server\nnether",wideRequest,WORKER.toString());throw new AssertionError("Transfer all cannot silently omit source 55");}catch(IllegalStateException expected){}
        wideRequest.addProperty("destination","Carry");
        try{StashCatalog.kitAction(root,"A","server\nnether",wideRequest,WORKER.toString());throw new AssertionError("Repeat pickup needs somewhere to unload");}catch(IllegalArgumentException expected){}
        wideRequest.addProperty("destination","Stash");extra.addProperty("status","unscanned");extra.add("shulkers",new JsonArray());extra.add("items",new JsonObject());StashCatalog.save(root,"A","server\nnether",wide,extra);
        try{StashCatalog.kitAction(root,"A","server\nnether",wideRequest,WORKER.toString());throw new AssertionError("Unscanned sources cannot establish exhaustion");}catch(IllegalStateException expected){}
    }

    private static void stashMultiTransfer() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("monocle-multi-kits-");String scope="server\nminecraft:the_end";
        JsonObject bounds=StashCatalog.plan(JsonParser.parseString("{name:'Source',homeName:'source',minX:0,maxX:60,minY:116,maxY:116,minZ:0,maxZ:0,scanMode:'Full',lazyMode:false}").getAsJsonObject());
        StashCatalog.define(root,"A",scope,bounds);
        JsonObject box=JsonParser.parseString("{slot:0,item:'minecraft:blue_shulker_box',quantity:1,name:'Alpha',contentsKnown:true,items:{'minecraft:obsidian':1728}}").getAsJsonObject(),other=box.deepCopy();other.addProperty("name","Beta");
        String alpha=StashCatalog.kitTypeId(box),beta=StashCatalog.kitTypeId(other);
        for(int x=0;x<60;x++){
            JsonObject o=JsonParser.parseString("{y:116,z:0,status:'observed',block:'minecraft:chest',items:{'minecraft:blue_shulker_box':1,'minecraft:obsidian':1728},shulkers:[]}").getAsJsonObject();o.addProperty("x",x);o.getAsJsonArray("shulkers").add(x<30?box.deepCopy():other.deepCopy());StashCatalog.save(root,"A",scope,bounds,o);
        }
        JsonObject destination=bounds.deepCopy();destination.addProperty("name","Destination");destination.addProperty("homeName","destination");destination.addProperty("minX",100);destination.addProperty("maxX",102);StashCatalog.define(root,"A",scope,destination);
        JsonObject request=new JsonObject();request.addProperty("name","Source");request.addProperty("targetStashName","Destination");request.addProperty("count",36);
        JsonObject args=StashCatalog.kitTransfer(root,"A",scope,request,WORKER.toString());
        assert args.getAsJsonArray("kitTypeIds").size()==2&&args.get("transferAll").getAsBoolean()&&!args.get("depositCarried").getAsBoolean();
        JsonObject task=new JsonObject();task.addProperty("crew","A");task.addProperty("server","server");task.addProperty("dimension","minecraft:the_end");task.add("args",args);
        var workflows=new dev.monocle.client.systems.bots.BotWorkflows(null);task.add("package",workflows.packageWorkflows("task-stash-transfer"));
        assert task.getAsJsonObject("package").getAsJsonObject("programs").size()==2 : "Many kit types reuse one child program, not one program per kit";
        String script=workflows.get("task-stash-transfer").script();var planned=BotLua.next(script,new JsonObject(),args,null,null);
        JsonObject mixedRequest=request.deepCopy();mixedRequest.addProperty("mixedLoads",true);
        rejects(()->StashCatalog.kitTransfer(root,"A",scope,mixedRequest,WORKER.toString()));
        mixedRequest.add("kitTypeIds",args.get("kitTypeIds").deepCopy());
        JsonObject mixedArgs=StashCatalog.kitTransfer(root,"A",scope,mixedRequest,WORKER.toString());
        JsonObject mixedTask=task.deepCopy();mixedTask.add("args",mixedArgs);
        var mixed=BotLua.next(script,new JsonObject(),mixedArgs,null,null);
        JsonObject mixedRun=new JsonObject();mixedRun.addProperty("token",OTHER.toString());mixedRun.add("action",mixed.action());
        JsonObject mixedPlan=StashCatalog.cachedKitTransferPlan(root,mixedTask,mixedRun,WORKER.toString());
        mixed=BotLua.next(script,mixed.state(),mixedArgs,mixedPlan,null);
        assert TaskWire.text(mixed.action(),"type").equals("StashResupply")&&mixed.action().get("mixedLoad").getAsBoolean();
        var filledEarly=BotLua.next(script,mixed.state(),mixedArgs,JsonParser.parseString("{picked:1,nextPick:0,sourceExhausted:false,batchCapacity:34,personalBoxes:[],inventoryFull:true}").getAsJsonObject(),null);
        assert TaskWire.text(filledEarly.action(),"type").equals("Wait");
        filledEarly=BotLua.next(script,filledEarly.state(),mixedArgs,null,null);
        assert TaskWire.text(filledEarly.action(),"type").equals("StashDeposit")&&filledEarly.action().get("count").getAsInt()==1 : "Unrelated new inventory items end the load safely rather than requesting another pickup with no free slot";
        JsonObject shortLoad=JsonParser.parseString("{picked:1,nextPick:30,sourceExhausted:true,batchCapacity:34,personalBoxes:[]}").getAsJsonObject();
        assert TaskWire.text(BotLua.next(script,mixed.state(),mixedArgs,JsonParser.parseString("{picked:1,nextPick:30,sourceExhausted:true}").getAsJsonObject(),null).action(),"type").equals("Fail") : "Old workers cannot silently fake a mixed capacity receipt";
        mixed=BotLua.next(script,mixed.state(),mixedArgs,shortLoad,null);
        assert TaskWire.text(mixed.action(),"type").equals("KitTransferPlan")&&mixed.action().get("index").getAsInt()==2 : "One-kit partial loads seek the next selected type before travelling to deposit";
        JsonObject betaPlan=StashCatalog.kitTransferPlan(root,mixedTask,WORKER.toString(),mixed.action());
        mixed=BotLua.next(script,mixed.state(),mixedArgs,betaPlan,null);
        assert mixed.action().get("count").getAsInt()==33 : "The first server-confirmed inventory capacity, not 36 assumed slots, budgets the next collection";
        mixed=BotLua.next(script,mixed.state(),mixedArgs,JsonParser.parseString("{picked:33,nextPick:0,sourceExhausted:false,batchCapacity:33,personalBoxes:[]}").getAsJsonObject(),null);
        mixed=BotLua.next(script,mixed.state(),mixedArgs,null,null);
        assert TaskWire.text(mixed.action(),"type").equals("StashDeposit")&&TaskWire.text(mixed.action(),"kitTypeId").equals(alpha)&&mixed.action().get("count").getAsInt()==1;
        mixed=BotLua.next(script,mixed.state(),mixedArgs,JsonParser.parseString("{deposited:1,missing:0}").getAsJsonObject(),null);
        assert TaskWire.text(mixed.action(),"kitTypeId").equals(beta)&&mixed.action().get("count").getAsInt()==33 : "Mixed loads still verify and reserve deposits separately for each actual kit type";
        mixed=BotLua.next(script,mixed.state(),mixedArgs,JsonParser.parseString("{deposited:33,missing:0}").getAsJsonObject(),null);
        assert mixed.state().get("total").getAsInt()==34&&mixed.state().getAsJsonObject("byKit").get(alpha).getAsInt()==1&&mixed.state().getAsJsonObject("byKit").get(beta).getAsInt()==33;
        for(int i=0;i<16&&!TaskWire.text(mixed.action(),"type").equals("Done");i++){
            if(TaskWire.text(mixed.action(),"type").equals("KitTransferPlan"))mixed=BotLua.next(script,mixed.state(),mixedArgs,StashCatalog.kitTransferPlan(root,mixedTask,WORKER.toString(),mixed.action()),null);
            else if(TaskWire.text(mixed.action(),"type").equals("StashResupply"))mixed=BotLua.next(script,mixed.state(),mixedArgs,JsonParser.parseString("{picked:0,nextPick:30,sourceExhausted:true,batchCapacity:34,personalBoxes:[]}").getAsJsonObject(),null);
            else{assert TaskWire.text(mixed.action(),"type").equals("Wait");mixed=BotLua.next(script,mixed.state(),mixedArgs,null,null);}
        }
        assert TaskWire.text(mixed.action(),"type").equals("Done")&&mixed.action().getAsJsonObject("result").get("delivered").getAsInt()==34 : "Each selected type gets two empty verifications and receipts survive workflow serialization";
        mixedRequest.addProperty("depositCarried",true);rejects(()->StashCatalog.kitTransfer(root,"A",scope,mixedRequest,WORKER.toString()));
        assert TaskWire.text(planned.action(),"type").equals("KitTransferPlan")&&planned.action().get("index").getAsInt()==1;
        JsonObject run=new JsonObject();run.addProperty("token",OTHER.toString());run.add("action",planned.action());
        JsonObject plan=StashCatalog.cachedKitTransferPlan(root,task,run,WORKER.toString());assert plan.equals(StashCatalog.cachedKitTransferPlan(root,task,run,WORKER.toString()));
        assert plan.getAsJsonObject("args").getAsJsonArray("picks").size()==30&&plan.toString().length()<32_768;
        var called=BotLua.next(script,planned.state(),args,plan,null);assert TaskWire.text(called.action(),"type").equals("Call")&&TaskWire.text(called.action(),"workflow").equals("task-kit-delivery");
        JsonObject receipt=JsonParser.parseString("{ok:true,value:{delivered:35,sourceExhausted:true}}").getAsJsonObject();
        var next=BotLua.next(script,JsonParser.parseString(called.state().toString()).getAsJsonObject(),args,receipt,null);assert next.action().get("index").getAsInt()==2;
        // Withdrawing 30 Alpha chests cannot make them 30 false Beta targets or overflow the 54-source ceiling.
        StashCatalog.invalidateWithdrawn(root,"A",scope,"Source",plan.getAsJsonObject("args").getAsJsonArray("picks"));
        var secondPlan=StashCatalog.kitTransferPlan(root,task,WORKER.toString(),next.action());assert secondPlan.getAsJsonObject("args").getAsJsonArray("picks").size()==30;
        var secondCall=BotLua.next(script,next.state(),args,secondPlan,null);var done=BotLua.next(script,secondCall.state(),args,receipt,null);
        assert TaskWire.text(done.action(),"type").equals("Done")&&done.action().getAsJsonObject("result").get("delivered").getAsInt()==70&&done.action().getAsJsonObject("result").getAsJsonObject("byKit").size()==2;
        JsonObject deferred=JsonParser.parseString("{ok:true,value:{delivered:9,sourceExhausted:false,sourceDeferred:true,deferredSources:['Outlet holds Beta, not Alpha']}}").getAsJsonObject();
        var deferNext=BotLua.next(script,called.state(),args,deferred,null);
        assert deferNext.action().get("index").getAsInt()==2&&deferNext.state().getAsJsonObject("deferred").has(alpha) : "Other kit types get their turn before revisiting a blocked outlet";
        var otherCall=BotLua.next(script,deferNext.state(),args,secondPlan,null);
        var retry=BotLua.next(script,otherCall.state(),args,receipt,null);
        assert retry.action().get("index").getAsInt()==1&&retry.state().get("retrying").getAsBoolean();
        var retryCall=BotLua.next(script,JsonParser.parseString(retry.state().toString()).getAsJsonObject(),args,plan,null);
        var resolved=BotLua.next(script,retryCall.state(),args,receipt,null);
        assert TaskWire.text(resolved.action(),"type").equals("Done")&&resolved.action().getAsJsonObject("result").get("delivered").getAsInt()==79
            &&resolved.action().getAsJsonObject("result").getAsJsonObject("byKit").get(alpha).getAsInt()==44 : "Retry totals accumulate once; completed types are not replayed";
        var unresolved=BotLua.next(script,retryCall.state(),args,deferred,null);
        assert TaskWire.text(unresolved.action(),"type").equals("Fail")&&unresolved.action().toString().contains("53 kits")
            &&unresolved.state().getAsJsonObject("deferred").has(alpha) : "One bounded revisit, then an explicit unresolved report, never an infinite loop or false exhaustion";
        JsonObject manyArgs=args.deepCopy();JsonArray manyIds=new JsonArray();for(int i=0;i<128;i++)manyIds.add(new UUID(0,i+1).toString());manyArgs.add("kitTypeIds",manyIds);
        var many=BotLua.next(script,new JsonObject(),manyArgs,null,null);
        JsonObject manyDeferred=deferred.deepCopy();for(int i=0;i<7;i++)manyDeferred.getAsJsonObject("value").getAsJsonArray("deferredSources").add("x".repeat(512));
        for(int i=0;i<128;i++){
            var call=BotLua.next(script,many.state(),manyArgs,plan,null);
            many=BotLua.next(script,call.state(),manyArgs,manyDeferred,null);
        }
        assert many.action().get("index").getAsInt()==1&&many.state().getAsJsonObject("deferred").size()==128&&many.state().toString().length()<BotLua.MAX_STATE/2
            : "Many deferred types retain IDs and one diagnostic, not repeated megabytes of outlet manifests in Lua state";
        JsonObject bad=next.action().deepCopy();bad.addProperty("targetStashName","Hijacked");rejects(()->StashCatalog.kitTransferPlan(root,task,WORKER.toString(),bad));
        run.add("action",next.action());rejects(()->StashCatalog.cachedKitTransferPlan(root,task,run,WORKER.toString()));
        run.addProperty("token",THIRD.toString());assert StashCatalog.cachedKitTransferPlan(root,task,run,WORKER.toString()).equals(secondPlan);
        JsonObject duplicate=request.deepCopy();JsonArray ids=new JsonArray();ids.add(alpha);ids.add(alpha);duplicate.add("kitTypeIds",ids);rejects(()->StashCatalog.kitTransfer(root,"A",scope,duplicate,WORKER.toString()));
        duplicate.getAsJsonArray("kitTypeIds").remove(1);duplicate.getAsJsonArray("kitTypeIds").add(UUID.randomUUID().toString());rejects(()->StashCatalog.kitTransfer(root,"A",scope,duplicate,WORKER.toString()));
        JsonObject carryArgs=args.deepCopy();carryArgs.addProperty("depositCarried",true);JsonObject carryPlan=plan.deepCopy();carryPlan.addProperty("carried",35);
        var carryStart=BotLua.next(script,new JsonObject(),carryArgs,null,null);
        var storing=BotLua.next(script,carryStart.state(),carryArgs,carryPlan,null);assert TaskWire.text(storing.action(),"type").equals("StashDeposit")&&storing.action().get("count").getAsInt()==35;
        var checkOther=BotLua.next(script,storing.state(),carryArgs,new JsonObject(),null);assert checkOther.action().get("index").getAsInt()==2&&checkOther.state().get("total").getAsInt()==35;
        var partialRecovery=BotLua.next(script,storing.state(),carryArgs,JsonParser.parseString("{deposited:33,missing:2}").getAsJsonObject(),null);
        assert partialRecovery.action().get("index").getAsInt()==2&&partialRecovery.state().get("total").getAsInt()==33&&partialRecovery.state().get("recovered").getAsInt()==33&&partialRecovery.state().get("missing").getAsInt()==2;
        var missingChild=BotLua.next(script,called.state(),args,JsonParser.parseString("{ok:true,value:{delivered:33,missing:2,sourceExhausted:true}}").getAsJsonObject(),null);
        assert missingChild.state().get("total").getAsInt()==33&&missingChild.state().get("missing").getAsInt()==2 : "Parent transfer propagates actual child receipts";
        var replanned=BotLua.next(script,checkOther.state(),carryArgs,secondPlan,null);assert replanned.action().get("index").getAsInt()==1&&!replanned.state().get("preparing").getAsBoolean() : "Unload all selected carried types before attempting any pickup with a full inventory";
        JsonObject depositOnlyArgs=carryArgs.deepCopy();depositOnlyArgs.addProperty("depositOnly",true);
        var recoveryDone=BotLua.next(script,checkOther.state(),depositOnlyArgs,secondPlan,null);
        assert TaskWire.text(recoveryDone.action(),"type").equals("Done")&&recoveryDone.action().getAsJsonObject("result").get("recovered").getAsInt()==35
            &&!recoveryDone.action().getAsJsonObject("result").get("sourceExhausted").getAsBoolean() : "Deposit-only recovery finishes without starting any new withdrawal or claiming source exhaustion";
        JsonObject recoveryRequest=request.deepCopy();recoveryRequest.addProperty("depositOnly",true);rejects(()->StashCatalog.kitTransfer(root,"A",scope,recoveryRequest,WORKER.toString()));
        recoveryRequest.addProperty("depositCarried",true);assert StashCatalog.kitTransfer(root,"A",scope,recoveryRequest,WORKER.toString()).get("depositOnly").getAsBoolean();
        carryPlan.addProperty("carried",0);var recoveredCall=BotLua.next(script,replanned.state(),carryArgs,carryPlan,null);assert TaskWire.text(recoveredCall.action(),"type").equals("Call");
        var recoveredNext=BotLua.next(script,recoveredCall.state(),carryArgs,receipt,null);assert recoveredNext.state().getAsJsonObject("delivered").get(alpha).getAsInt()==70;
        assert TaskWire.text(BotLua.next(script,called.state(),args,JsonParser.parseString("{ok:true,value:{delivered:1.5,sourceExhausted:true}}").getAsJsonObject(),null).action(),"type").equals("Fail");
        assert !StashCatalog.kitTransfer(root,"A",scope,request,WORKER.toString()).getAsJsonArray("kitTypeIds").isEmpty() : "Withdrawn quantities are unknown, not exhausted";
        assert StashCatalog.kitTransferPlan(root,task,WORKER.toString(),planned.action()).getAsJsonObject("args").getAsJsonArray("picks").size()==30;
        stashRoundRobin(root,scope,bounds,box,other,alpha,beta,workflows);
        JsonObject lazy=bounds.deepCopy();lazy.addProperty("lazyMode",true);StashCatalog.define(root,"A",scope,lazy);rejects(()->StashCatalog.kitTransfer(root,"A",scope,request,WORKER.toString()));
    }
    private static void stashRoundRobin(java.nio.file.Path root,String scope,JsonObject bounds,JsonObject box,JsonObject other,String alpha,String beta,dev.monocle.client.systems.bots.BotWorkflows workflows) throws Exception {
        JsonObject source=bounds.deepCopy();source.addProperty("name","Other source");source.addProperty("homeName","other-source");source.addProperty("minX",200);source.addProperty("maxX",200);StashCatalog.define(root,"A",scope,source);
        JsonObject observed=JsonParser.parseString("{x:200,y:116,z:0,status:'observed',block:'minecraft:chest',items:{},shulkers:[]}").getAsJsonObject();observed.getAsJsonArray("shulkers").add(other);StashCatalog.save(root,"A",scope,source,observed);
        JsonObject destination=source.deepCopy();destination.addProperty("name","Other destination");destination.addProperty("homeName","other-destination");destination.addProperty("minX",300);destination.addProperty("maxX",302);StashCatalog.define(root,"A",scope,destination);
        JsonObject request=JsonParser.parseString("{count:36,routes:[{name:'Source',targetStashName:'Destination',kitTypeId:'"+alpha+"'},{name:'Other source',targetStashName:'Other destination',kitTypeId:'"+beta+"'}]}").getAsJsonObject();
        JsonObject args=StashCatalog.kitTransfer(root,"A",scope,request,WORKER.toString()),task=new JsonObject();task.addProperty("crew","A");task.addProperty("server","server");task.addProperty("dimension","minecraft:the_end");task.add("args",args);task.add("package",workflows.packageWorkflows("task-stash-transfer"));
        String script=workflows.get("task-stash-transfer").script(),child=workflows.get("task-kit-delivery").script();
        var step=BotLua.next(script,new JsonObject(),args,null,null);JsonObject run=new JsonObject();run.add("action",step.action());run.addProperty("token",UUID.randomUUID().toString());
        JsonObject first=StashCatalog.cachedKitTransferPlan(root,task,run,WORKER.toString());assert first.getAsJsonObject("args").get("oneBatch").getAsBoolean();
        var call=BotLua.next(script,step.state(),args,first,null);assert TaskWire.text(call.action(),"type").equals("Call");JsonObject childArgs=call.action().getAsJsonObject("args");
        var taking=BotLua.next(child,new JsonObject(),childArgs,null,null);
        var storing=BotLua.next(child,taking.state(),childArgs,JsonParser.parseString("{picked:35,nextPick:12,sourceExhausted:false}").getAsJsonObject(),null);
        assert TaskWire.text(storing.action(),"type").equals("StashDeposit")&&TaskWire.text(storing.action(),"name").equals("Destination");
        var batchDone=BotLua.next(child,storing.state(),childArgs,JsonParser.parseString("{deposited:33,missing:2}").getAsJsonObject(),null);
        assert TaskWire.text(batchDone.action(),"type").equals("Done")&&batchDone.action().getAsJsonObject("result").get("nextPick").getAsInt()==12;
        JsonObject receipt=new JsonObject();receipt.addProperty("ok",true);receipt.add("value",batchDone.action().get("result"));
        step=BotLua.next(script,JsonParser.parseString(call.state().toString()).getAsJsonObject(),args,receipt,null);assert step.action().get("index").getAsInt()==2;
        JsonObject second=StashCatalog.kitTransferPlan(root,task,WORKER.toString(),step.action());
        assert TaskWire.text(second.getAsJsonObject("args"),"homeName").equals("other-source")&&TaskWire.text(second.getAsJsonObject("args").getAsJsonObject("targetStash"),"homeName").equals("other-destination");
        call=BotLua.next(script,step.state(),args,second,null);
        step=BotLua.next(script,call.state(),args,routeReceipt(36,36,0,0,false,false),null);assert step.action().get("index").getAsInt()==1;
        call=BotLua.next(script,step.state(),args,first,null);assert call.action().getAsJsonObject("args").get("pickIndex").getAsInt()==12 : "Each route keeps its own source cursor across rotation and checkpoint JSON";
        taking=BotLua.next(child,new JsonObject(),call.action().getAsJsonObject("args"),null,null);assert taking.action().get("pickIndex").getAsInt()==12;
        step=BotLua.next(script,call.state(),args,routeReceipt(2,2,0,30,true,false),null);
        for(int i=0;i<4;i++){
            JsonObject plan=step.action().get("index").getAsInt()==1?first:second;call=BotLua.next(script,step.state(),args,plan,null);
            step=BotLua.next(script,call.state(),args,routeReceipt(0,0,0,plan.getAsJsonObject("args").getAsJsonArray("picks").size(),true,false),null);
            assert TaskWire.text(step.action(),"type").equals("Wait");step=BotLua.next(script,JsonParser.parseString(step.state().toString()).getAsJsonObject(),args,new JsonObject(),null);
        }
        assert TaskWire.text(step.action(),"type").equals("Done")&&step.action().getAsJsonObject("result").get("delivered").getAsInt()==71&&step.action().getAsJsonObject("result").get("missing").getAsInt()==2
            &&step.action().getAsJsonObject("result").getAsJsonArray("byRoute").size()==2 : "Both routes need two verified empty passes; missing kits never inflate deliveries";
        // A newly observed candidate cannot shift a saved route cursor under a fresh action token.
        JsonObject extra=observed.deepCopy();extra.addProperty("x",60);extra.getAsJsonArray("shulkers").set(0,box);StashCatalog.save(root,"A",scope,bounds,extra);
        run.addProperty("token",UUID.randomUUID().toString());assert StashCatalog.cachedKitTransferPlan(root,task,run,WORKER.toString()).equals(first);
        JsonObject restored=JsonParser.parseString(run.toString()).getAsJsonObject();restored.addProperty("token",UUID.randomUUID().toString());assert StashCatalog.cachedKitTransferPlan(root,task,restored,WORKER.toString()).equals(first) : "Frozen source manifests survive host restart";
        JsonObject hijack=run.getAsJsonObject("action").deepCopy();hijack.addProperty("targetStashName","Hijacked");run.add("action",hijack);rejects(()->StashCatalog.cachedKitTransferPlan(root,task,run,WORKER.toString()));
        JsonObject bad=request.deepCopy();bad.getAsJsonArray("routes").add(bad.getAsJsonArray("routes").get(0).deepCopy());JsonObject duplicate=bad;rejects(()->StashCatalog.kitTransfer(root,"A",scope,duplicate,WORKER.toString()));
        bad=request.deepCopy();bad.getAsJsonArray("routes").get(1).getAsJsonObject().addProperty("kitTypeId",alpha);JsonObject rejected=bad;rejects(()->StashCatalog.kitTransfer(root,"A",scope,rejected,WORKER.toString()));
        bad=request.deepCopy();bad.getAsJsonArray("routes").get(1).getAsJsonObject().addProperty("name","Missing");JsonObject missing=bad;rejects(()->StashCatalog.kitTransfer(root,"A",scope,missing,WORKER.toString()));
        bad=request.deepCopy();bad.getAsJsonArray("routes").get(1).getAsJsonObject().addProperty("count",1);JsonObject fields=bad;rejects(()->StashCatalog.kitTransfer(root,"A",scope,fields,WORKER.toString()));
        bad=request.deepCopy();bad.getAsJsonArray("routes").remove(1);JsonObject tooFew=bad;rejects(()->StashCatalog.kitTransfer(root,"A",scope,tooFew,WORKER.toString()));
        bad=request.deepCopy();for(int i=2;i<17;i++)bad.getAsJsonArray("routes").add(request.getAsJsonArray("routes").get(0).deepCopy());JsonObject tooMany=bad;rejects(()->StashCatalog.kitTransfer(root,"A",scope,tooMany,WORKER.toString()));
        bad=request.deepCopy();bad.addProperty("count",37);JsonObject overCap=bad;rejects(()->StashCatalog.kitTransfer(root,"A",scope,overCap,WORKER.toString()));
        bad=request.deepCopy();bad.addProperty("depositCarried","true");JsonObject booleanString=bad;rejects(()->StashCatalog.kitTransfer(root,"A",scope,booleanString,WORKER.toString()));
        args.addProperty("depositCarried",true);first.addProperty("carried",5);second.addProperty("carried",4);
        step=BotLua.next(script,new JsonObject(),args,null,null);storing=BotLua.next(script,step.state(),args,first,null);assert TaskWire.text(storing.action(),"name").equals("Destination");
        step=BotLua.next(script,storing.state(),args,JsonParser.parseString("{deposited:5,missing:0}").getAsJsonObject(),null);
        storing=BotLua.next(script,step.state(),args,second,null);assert TaskWire.text(storing.action(),"name").equals("Other destination");
        step=BotLua.next(script,storing.state(),args,JsonParser.parseString("{deposited:4,missing:0}").getAsJsonObject(),null);
        assert step.action().get("index").getAsInt()==1&&step.state().get("recovered").getAsInt()==9&&!step.state().get("preparing").getAsBoolean();
        args.addProperty("depositCarried",false);step=BotLua.next(script,new JsonObject(),args,null,null);call=BotLua.next(script,step.state(),args,first,null);
        assert TaskWire.text(BotLua.next(script,call.state(),args,routeReceipt(0,0,0,30,false,false),null).action(),"type").equals("Fail");
        assert TaskWire.text(BotLua.next(script,call.state(),args,routeReceipt(0,0,0,1,false,true),null).action(),"type").equals("Fail") : "A deferred empty pass must finish its source manifest";
        for(int i=0;i<2;i++){
            step=BotLua.next(script,call.state(),args,routeReceipt(0,0,0,30,false,true),null);
            if(i==1){assert TaskWire.text(step.action(),"type").equals("Wait")&&step.state().getAsJsonArray("routes").get(0).getAsJsonObject().get("blocked").getAsBoolean();break;}
            step=BotLua.next(script,step.state(),args,new JsonObject(),null);call=BotLua.next(script,step.state(),args,second,null);
            step=BotLua.next(script,call.state(),args,routeReceipt(1,1,0,0,false,false),null);call=BotLua.next(script,step.state(),args,first,null);
        }
        step=BotLua.next(script,step.state(),args,new JsonObject(),null);assert step.action().get("index").getAsInt()==2;
        for(int i=0;i<2;i++){
            call=BotLua.next(script,step.state(),args,second,null);step=BotLua.next(script,call.state(),args,routeReceipt(0,0,0,1,true,false),null);
            step=BotLua.next(script,step.state(),args,new JsonObject(),null);
        }
        assert TaskWire.text(step.action(),"type").equals("Fail")&&step.action().toString().contains("other routes finished") : "Blocked routes park without starving useful transfers or claiming exhaustion";
    }
    private static JsonObject routeReceipt(int picked,int delivered,int missing,int nextPick,boolean exhausted,boolean deferred){
        JsonObject result=new JsonObject(),value=new JsonObject();result.addProperty("ok",true);result.add("value",value);
        value.addProperty("picked",picked);value.addProperty("delivered",delivered);value.addProperty("missing",missing);value.addProperty("nextPick",nextPick);value.addProperty("sourceExhausted",exhausted);value.addProperty("sourceDeferred",deferred);
        if(deferred){JsonArray notes=new JsonArray();notes.add("Blocked outlet");value.add("deferredSources",notes);}return result;
    }
    private static void stashManualColumns() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("monocle-manual-columns-");String scope="server\nnether";
        JsonObject p=StashCatalog.plan(JsonParser.parseString("{name:'Manual',homeName:'vault',minX:0,maxX:8,minY:115,maxY:119,minZ:0,maxZ:3}").getAsJsonObject());
        JsonObject box=JsonParser.parseString("{slot:0,item:'minecraft:blue_shulker_box',quantity:1,name:'Alpha',contentsKnown:true,items:{'minecraft:obsidian':64}}").getAsJsonObject();String kit=StashCatalog.kitTypeId(box);
        for(int x=0;x<=8;x+=2){JsonObject o=JsonParser.parseString("{x:0,y:116,z:0,status:'observed',block:'minecraft:chest',items:{},shulkers:[]}").getAsJsonObject();o.addProperty("x",x);
            if(x==0){o.getAsJsonObject("items").addProperty("minecraft:blue_shulker_box",1);o.getAsJsonObject("items").addProperty("minecraft:obsidian",64);o.getAsJsonArray("shulkers").add(box);}
            if(x==8)o.getAsJsonObject("items").addProperty("minecraft:stone",1);
            StashCatalog.save(root,"A",scope,p,o);
        }
        JsonObject db=StashCatalog.get(root,"A",scope,"Manual");String revision=TaskWire.text(StashCatalog.summary(db),"revision");
        JsonArray selection=JsonParser.parseString("[{x:2,y:116,z:0,axis:'X'},{x:4,y:116,z:0,axis:'X'}]").getAsJsonArray();
        JsonObject saved=StashCatalog.setColumnReservations(root,"A",scope,"Manual",revision,kit,selection);
        assert saved.get("mappedColumns").getAsInt()==5 : "Column anchors come from the recorded chest row, not the cuboid floor";
        JsonObject selected=StashCatalog.reserveColumn(root,"A",scope,"Manual",kit,java.util.Set.of());assert selected.get("x").getAsInt()==2&&selected.get("y").getAsInt()==116 : "Manual empty column precedes existing matching stock";
        assert StashCatalog.get(root,"A",scope,"Manual").getAsJsonObject("columnReservations").getAsJsonObject("2,0").get("manual").getAsBoolean() : "Allocation retains manual ownership";
        assert StashCatalog.reserveColumn(root,"A",scope,"Manual",kit,java.util.Set.of("2,0")).get("x").getAsInt()==4 : "Overflow uses the next reserved column";
        rejects(()->StashCatalog.setColumnReservations(root,"A",scope,"Manual",revision,kit,selection));
        db=StashCatalog.get(root,"A",scope,"Manual");String current=TaskWire.text(StashCatalog.summary(db),"revision");
        JsonArray bad=JsonParser.parseString("[{x:6,y:116,z:0,axis:'X'},{x:8,y:116,z:0,axis:'X'}]").getAsJsonArray();
        rejects(()->StashCatalog.setColumnReservations(root,"A",scope,"Manual",current,kit,bad));assert StashCatalog.get(root,"A",scope,"Manual").equals(db) : "A conflicting range cannot partially reserve columns";
        JsonArray phantom=JsonParser.parseString("[{x:3,y:116,z:0,axis:'X'}]").getAsJsonArray();rejects(()->StashCatalog.setColumnReservations(root,"A",scope,"Manual",current,kit,phantom));
        JsonArray upper=JsonParser.parseString("[{x:2,y:117,z:1,axis:'X'}]").getAsJsonArray();rejects(()->StashCatalog.setColumnReservations(root,"A",scope,"Manual",current,kit,upper));
        JsonArray duplicate=selection.deepCopy();duplicate.add(selection.get(0).deepCopy());rejects(()->StashCatalog.setColumnReservations(root,"A",scope,"Manual",current,kit,duplicate));
        StashCatalog.setColumnReservations(root,"A",scope,"Manual",current,"",selection);
        assert StashCatalog.reserveColumn(root,"A",scope,"Manual",kit,java.util.Set.of()).get("x").getAsInt()==0 : "Clearing manual reservations restores matching-stock priority";
        JsonObject target=p.deepCopy();target.addProperty("name","Empty target");JsonObject empty=JsonParser.parseString("{x:2,y:116,z:0,status:'observed',block:'minecraft:chest',items:{},shulkers:[]}").getAsJsonObject();StashCatalog.save(root,"A",scope,target,empty);
        String targetRevision=TaskWire.text(StashCatalog.summary(StashCatalog.get(root,"A",scope,"Empty target")),"revision");
        StashCatalog.setColumnReservations(root,"A",scope,"Empty target",targetRevision,kit,JsonParser.parseString("[{x:2,y:116,z:0,axis:'X'}]").getAsJsonArray());
        assert StashCatalog.get(root,"A",scope,"Empty target").getAsJsonObject("kitTypes").has(kit) : "Known crew kits can reserve empty destination stashes";
    }
    private static void stashColumns() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("monocle-column-check-");String scope="server\nnether";
        JsonObject p=StashCatalog.plan(JsonParser.parseString("{name:'Columns',homeName:'vault',minX:0,maxX:10,minY:116,maxY:119,minZ:0,maxZ:3}").getAsJsonObject());
        JsonObject box=JsonParser.parseString("{slot:0,item:'minecraft:blue_shulker_box',quantity:1,name:'Alpha',contentsKnown:true,items:{'minecraft:obsidian':64}}").getAsJsonObject();
        JsonObject other=box.deepCopy();other.addProperty("name","Beta");String kit=StashCatalog.kitTypeId(box),foreign=StashCatalog.kitTypeId(other);
        JsonObject empty=JsonParser.parseString("{x:0,y:116,z:0,status:'observed',block:'minecraft:chest',reason:'',items:{},shulkers:[]}").getAsJsonObject();
        StashCatalog.define(root,"A",scope,p);
        for(int x=0;x<=10;x+=2){JsonObject cell=empty.deepCopy();cell.addProperty("x",x);
            if(x==0||x==10){cell.getAsJsonObject("items").addProperty("minecraft:blue_shulker_box",1);cell.getAsJsonObject("items").addProperty("minecraft:obsidian",64);cell.getAsJsonArray("shulkers").add(x==0?box:other);}
            if(x==6)cell.getAsJsonObject("items").addProperty("minecraft:stone",1);
            if(x==8)cell.addProperty("status","unscanned");
            StashCatalog.save(root,"A",scope,p,cell);
        }
        JsonObject column=StashCatalog.reserveColumn(root,"A",scope,"Columns",kit,java.util.Set.of());
        assert column.get("x").getAsInt()==0&&column.get("axis").getAsString().equals("X") : "Existing matching kits precede new empty columns";
        assert StashCatalog.columnContains(column,0,118,2)&&StashCatalog.columnContains(column,0,118,0)&&!StashCatalog.columnContains(column,2,118,2);
        assert StashCatalog.reserveColumn(root,"A",scope,"Columns",foreign,java.util.Set.of()).get("x").getAsInt()==10;
        assert StashCatalog.get(root,"A",scope,"Columns").getAsJsonObject("columnReservations").getAsJsonObject("0,0").get("kitTypeId").getAsString().equals(kit);
        JsonObject badColumn=column.deepCopy();badColumn.addProperty("y",115);rejects(()->StashCatalog.checkedColumn(p,badColumn));
        JsonObject run=new JsonObject();run.addProperty("id",WORKER.toString());run.addProperty("token",OTHER.toString());
        JsonObject action=p.deepCopy();action.addProperty("type","StashDeposit");action.addProperty("kitTypeId",kit);action.addProperty("count",1);run.add("action",action);
        JsonObject task=JsonParser.parseString("{crew:'A',server:'server',dimension:'nether',id:'columns-task',runs:{}}").getAsJsonObject();task.getAsJsonObject("runs").add(WORKER.toString(),run);
        JsonObject message=TaskWire.message("stash-findings");message.addProperty("task","columns-task");message.addProperty("run",WORKER.toString());message.addProperty("token",OTHER.toString());message.add("action",action.deepCopy());message.addProperty("delivery",1);
        JsonObject ack=StashCatalog.accept(root,task,run,WORKER.toString(),"A",message);
        assert !ack.get("permit").getAsBoolean()&&ack.getAsJsonObject("column").equals(column);
        assert StashCatalog.accept(root,task,run,WORKER.toString(),"A",message).equals(ack) : "Retry returns the same durable reservation";
        JsonObject staleRun=run.deepCopy(),gap=message.deepCopy();gap.addProperty("delivery",3);rejects(()->StashCatalog.accept(root,task,run,WORKER.toString(),"A",gap));
        JsonObject conflict=empty.deepCopy();conflict.getAsJsonObject("items").addProperty("minecraft:blue_shulker_box",1);conflict.getAsJsonObject("items").addProperty("minecraft:obsidian",64);conflict.getAsJsonArray("shulkers").add(other);
        message.addProperty("delivery",2);message.add("observation",conflict);
        ack=StashCatalog.accept(root,task,run,WORKER.toString(),"A",message);
        assert !ack.get("permit").getAsBoolean()&&ack.getAsJsonObject("column").get("x").getAsInt()==2 : "Live foreign kit observations change the column before any deposit";
        JsonObject db=StashCatalog.get(root,"A",scope,"Columns");
        assert StashCatalog.columnOccupant(db.getAsJsonObject("containers").getAsJsonObject("0,116,0")).equals(foreign);
        assert !StashCatalog.summary(db).getAsJsonObject("columns").getAsJsonObject("2,0").get("available").getAsBoolean();
        assert StashCatalog.reserveColumn(root,"A",scope,"Columns",foreign,java.util.Set.of("0,0","10,0")).get("x").getAsInt()==4 : "Another kit cannot take the newly reserved empty column";
        JsonObject changedRetry=message.deepCopy();changedRetry.add("observation",empty);rejects(()->StashCatalog.accept(root,task,run,WORKER.toString(),"A",changedRetry));
        JsonObject staleMessage=message.deepCopy();staleMessage.add("observation",empty);
        JsonObject staleAck=StashCatalog.accept(root,task,staleRun,WORKER.toString(),"A",staleMessage);
        assert !staleAck.get("permit").getAsBoolean()&&staleAck.getAsJsonObject("column").get("x").getAsInt()==2 : "An old worker cannot regain a column reserved for another kit even if it is now empty";
        JsonObject observed=conflict.deepCopy();observed.addProperty("x",2);observed.getAsJsonArray("shulkers").set(0,box);
        message.addProperty("delivery",3);message.add("observation",observed);
        assert StashCatalog.accept(root,task,run,WORKER.toString(),"A",message).get("permit").getAsBoolean();
        JsonObject receipt=message.deepCopy();receipt.add("stashDeposit",JsonParser.parseString("{stash:'Columns',touched:[{x:2,y:116,z:0}]}").getAsJsonObject());
        StashCatalog.depositReceipt(root,"A",scope,run,receipt);
        assert StashCatalog.get(root,"A",scope,"Columns").getAsJsonObject("containers").getAsJsonObject("2,116,0").get("status").getAsString().equals("observed") : "Late status receipts must preserve verified live observations";
        message.remove("observation");message.addProperty("delivery",4);message.addProperty("exhaustedColumn",true);
        assert StashCatalog.accept(root,task,run,WORKER.toString(),"A",message).has("error") : "Foreign reservations, loose items and unknown columns are never treated as available";
        assert !run.has("depositColumn");
        JsonObject spoof=message.deepCopy();spoof.addProperty("task","another-task");rejects(()->StashCatalog.accept(root,task,run,WORKER.toString(),"A",spoof));
        JsonObject betaRun=new JsonObject();betaRun.addProperty("id",WORKER.toString());betaRun.addProperty("token",THIRD.toString());JsonObject betaAction=action.deepCopy();betaAction.addProperty("kitTypeId",foreign);betaRun.add("action",betaAction);
        JsonObject betaMessage=message.deepCopy();betaMessage.remove("exhaustedColumn");betaMessage.add("action",betaAction);betaMessage.addProperty("token",THIRD.toString());betaMessage.addProperty("delivery",1);
        assert StashCatalog.accept(root,task,betaRun,WORKER.toString(),"A",betaMessage).getAsJsonObject("column").get("x").getAsInt()==10;
        JsonObject upper=observed.deepCopy();upper.addProperty("x",10);upper.addProperty("y",117);upper.addProperty("z",1);betaMessage.add("observation",upper);betaMessage.addProperty("delivery",2);
        assert !StashCatalog.accept(root,task,betaRun,WORKER.toString(),"A",betaMessage).get("permit").getAsBoolean();
        assert StashCatalog.get(root,"A",scope,"Columns").getAsJsonObject("columnReservations").getAsJsonObject("10,0").get("blocked").getAsBoolean() : "A conflicting upper chest blocks the mixed column, not merely that chest";
        JsonObject legacy=conflict.deepCopy();
        legacy.getAsJsonArray("shulkers").get(0).getAsJsonObject().addProperty("legacy",true);legacy.getAsJsonObject("items").remove("minecraft:obsidian");
        assert StashCatalog.columnOccupant(legacy).equals(foreign);
    }

    private static void fullColumnMemory() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("monocle-full-columns-");String scope="server\nnether";
        JsonObject p=StashCatalog.plan(JsonParser.parseString("{name:'Full memory',homeName:'vault',minX:0,maxX:4,minY:116,maxY:119,minZ:0,maxZ:3}").getAsJsonObject());
        JsonObject box=JsonParser.parseString("{slot:0,item:'minecraft:blue_shulker_box',quantity:1,name:'Alpha',contentsKnown:true,items:{'minecraft:obsidian':64}}").getAsJsonObject(),beta=box.deepCopy();beta.addProperty("name","Beta");
        String a=StashCatalog.kitTypeId(box),b=StashCatalog.kitTypeId(beta);
        JsonObject empty=JsonParser.parseString("{x:0,y:116,z:0,status:'observed',block:'minecraft:chest',items:{},shulkers:[],slotCount:54,occupiedSlots:0}").getAsJsonObject();
        StashCatalog.define(root,"A",scope,p);
        for(int x=0;x<=4;x+=2){JsonObject cell=empty.deepCopy();cell.addProperty("x",x);
            if(x<4){cell.addProperty("occupiedSlots",54);cell.getAsJsonObject("items").addProperty("minecraft:blue_shulker_box",54);cell.getAsJsonObject("items").addProperty("minecraft:obsidian",3456);
                for(int slot=0;slot<54;slot++){JsonObject kit=(x==0?box:beta).deepCopy();kit.addProperty("slot",slot);cell.getAsJsonArray("shulkers").add(kit);}}
            StashCatalog.record(root,"A",scope,p,cell);
        }
        JsonArray manual=new JsonArray();manual.add(JsonParser.parseString("{x:0,y:116,z:0,axis:'X'}"));
        StashCatalog.setColumnReservations(root,"A",scope,"Full memory",TaskWire.text(StashCatalog.summary(StashCatalog.get(root,"A",scope,"Full memory")),"revision"),a,manual);
        JsonObject run=new JsonObject();run.addProperty("id",WORKER.toString());run.addProperty("token",OTHER.toString());
        JsonObject action=p.deepCopy();action.addProperty("type","StashDeposit");action.addProperty("kitTypeId",a);action.addProperty("count",1);run.add("action",action);
        JsonObject task=JsonParser.parseString("{crew:'A',server:'server',dimension:'nether',id:'full-memory-task',runs:{}}").getAsJsonObject();task.getAsJsonObject("runs").add(WORKER.toString(),run);
        JsonObject message=TaskWire.message("stash-findings");message.addProperty("task","full-memory-task");message.addProperty("run",WORKER.toString());message.addProperty("token",OTHER.toString());message.add("action",action);message.addProperty("delivery",1);
        assert StashCatalog.accept(root,task,run,WORKER.toString(),"A",message).getAsJsonObject("column").get("x").getAsInt()==0;
        assert !StashCatalog.get(root,"A",scope,"Full memory").has("fullColumns") : "A full bottom snapshot alone is not proof that the column cannot accept stock";
        message.addProperty("exhaustedColumn",true);message.addProperty("delivery",2);
        JsonObject ack=StashCatalog.accept(root,task,run,WORKER.toString(),"A",message);
        assert ack.getAsJsonObject("column").get("x").getAsInt()==4 : "Even a full manual reservation must not outrank usable overflow space";
        assert StashCatalog.accept(root,task,run,WORKER.toString(),"A",message).equals(ack) : "Full-column retries remain idempotent";
        assert StashCatalog.get(root,"A",scope,"Full memory").getAsJsonObject("fullColumns").has("0,0");
        message.remove("exhaustedColumn");message.addProperty("delivery",1);message.addProperty("token",THIRD.toString());run.addProperty("token",THIRD.toString());action.addProperty("kitTypeId",b);
        assert StashCatalog.accept(root,task,run,WORKER.toString(),"A",message).getAsJsonObject("column").get("x").getAsInt()==2;
        message.addProperty("token",WORKER.toString());run.addProperty("token",WORKER.toString());action.addProperty("kitTypeId",a);
        assert StashCatalog.accept(root,task,run,WORKER.toString(),"A",message).getAsJsonObject("column").get("x").getAsInt()==4 : "Alternating kits must not rediscover full matching columns";
        JsonObject restarted=run.deepCopy();restarted.remove("depositBinding");restarted.remove("depositToken");
        task.getAsJsonObject("runs").add(WORKER.toString(),restarted);
        assert StashCatalog.accept(root,task,restarted,WORKER.toString(),"A",message).getAsJsonObject("column").get("x").getAsInt()==4 : "Disk-backed fullness survives a fresh run";
        message.addProperty("exhaustedColumn",true);message.addProperty("delivery",2);
        assert StashCatalog.accept(root,task,restarted,WORKER.toString(),"A",message).has("error") : "Known-full columns are not revisited when no usable space remains";
        assert StashCatalog.reserveColumn(root,"A",scope,"Full memory",b,java.util.Set.of()).get("x").getAsInt()==2 : "Another kit's compatible column stays available";
        JsonObject free=empty.deepCopy();free.addProperty("x",4);free.addProperty("y",118);free.addProperty("z",2);free.addProperty("inferred",true);
        StashCatalog.record(root,"A",scope,p,free);
        assert StashCatalog.get(root,"A",scope,"Full memory").getAsJsonObject("fullColumns").has("4,0") : "Inferred space cannot clear fullness";
        free.remove("inferred");JsonObject unknown=free.deepCopy();unknown.remove("slotCount");unknown.remove("occupiedSlots");unknown.addProperty("status","unscanned");StashCatalog.record(root,"A",scope,p,unknown);
        assert StashCatalog.get(root,"A",scope,"Full memory").getAsJsonObject("fullColumns").has("4,0") : "Unknown space cannot clear fullness";
        StashCatalog.record(root,"A",scope,p,free);
        JsonObject db=StashCatalog.get(root,"A",scope,"Full memory");assert !db.getAsJsonObject("fullColumns").has("4,0")&&db.getAsJsonObject("fullColumns").has("0,0") : "A fresh diagonal upper-chest read clears only its own full column";
        message.addProperty("delivery",3);
        message.remove("exhaustedColumn");
        assert StashCatalog.accept(root,task,restarted,WORKER.toString(),"A",message).getAsJsonObject("column").get("x").getAsInt()==4 : "Fresh space also removes this run's full-only exclusion";
        message.addProperty("delivery",4);
        message.addProperty("exhaustedColumn",true);
        assert StashCatalog.accept(root,task,restarted,WORKER.toString(),"A",message).has("error") : "A full fallback is checked at most once per binding; no allocation loop";
        JsonObject resized=p.deepCopy();resized.addProperty("maxY",120);StashCatalog.define(root,"A",scope,resized);
        assert !StashCatalog.get(root,"A",scope,"Full memory").has("fullColumns") : "Changed geometry invalidates column fullness, not inventory records";
    }

    private static void singleColumnTransfer() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("monocle-single-column-");String scope="server\nnether";
        JsonObject p=StashCatalog.plan(JsonParser.parseString("{name:'Limit',homeName:'vault',minX:0,maxX:4,minY:116,maxY:119,minZ:0,maxZ:3}").getAsJsonObject());
        JsonObject box=JsonParser.parseString("{slot:0,item:'minecraft:blue_shulker_box',quantity:1,name:'Alpha',contentsKnown:true,items:{'minecraft:obsidian':64}}").getAsJsonObject();
        String kit=StashCatalog.kitTypeId(box);
        JsonObject empty=JsonParser.parseString("{x:2,y:116,z:0,status:'observed',block:'minecraft:chest',items:{},shulkers:[]}").getAsJsonObject();
        JsonObject filled=empty.deepCopy();filled.addProperty("x",0);filled.getAsJsonObject("items").addProperty("minecraft:blue_shulker_box",1);filled.getAsJsonObject("items").addProperty("minecraft:obsidian",64);filled.getAsJsonArray("shulkers").add(box);
        StashCatalog.save(root,"A",scope,p,filled);StashCatalog.save(root,"A",scope,p,empty);
        JsonObject source=p.deepCopy();source.addProperty("name","Source");source.addProperty("minX",100);source.addProperty("maxX",104);JsonObject sourceCell=filled.deepCopy();sourceCell.addProperty("x",100);StashCatalog.save(root,"A",scope,source,sourceCell);
        JsonObject request=source.deepCopy();request.addProperty("kitTypeId",kit);request.addProperty("count",1);request.addProperty("destination","Stash");request.addProperty("targetStashName","Limit");request.add("targetColumn",JsonParser.parseString("{x:2,y:116,z:0,axis:'X'}"));
        JsonObject args=StashCatalog.kitAction(root,"A",scope,request,WORKER.toString()),action=args.getAsJsonObject("targetStash");action.addProperty("type","StashDeposit");
        assert !args.has("targetColumn")&&action.getAsJsonObject("targetColumn").get("x").getAsInt()==2 : "Destination bounds are independent of the source";
        JsonObject bad=request.deepCopy();bad.getAsJsonObject("targetColumn").addProperty("y",117);rejects(()->StashCatalog.kitAction(root,"A",scope,bad,WORKER.toString()));
        JsonObject wrongDestination=request.deepCopy();wrongDestination.addProperty("destination","Carry");rejects(()->StashCatalog.kitAction(root,"A",scope,wrongDestination,WORKER.toString()));
        rejects(()->StashCatalog.kitTransfer(root,"A",scope,request,WORKER.toString()));
        JsonObject run=JsonParser.parseString("{id:'"+WORKER+"',token:'"+OTHER+"'}").getAsJsonObject();run.add("action",action);
        JsonObject task=JsonParser.parseString("{crew:'A',server:'server',dimension:'nether',id:'limit-task',runs:{}}").getAsJsonObject();task.getAsJsonObject("runs").add(WORKER.toString(),run);
        JsonObject message=TaskWire.message("stash-findings");message.addProperty("task","limit-task");message.addProperty("run",WORKER.toString());message.addProperty("token",OTHER.toString());message.add("action",action);message.addProperty("delivery",1);
        JsonObject ack=StashCatalog.accept(root,task,run,WORKER.toString(),"A",message);
        assert ack.getAsJsonObject("column").equals(action.get("targetColumn")) : "The selected empty column overrides matching-stock allocation elsewhere";
        message.add("observation",empty);message.addProperty("delivery",2);ack=StashCatalog.accept(root,task,run,WORKER.toString(),"A",message);assert ack.get("permit").getAsBoolean();
        message.remove("observation");message.addProperty("exhaustedColumn",true);message.addProperty("delivery",3);
        ack=StashCatalog.accept(root,task,run,WORKER.toString(),"A",message);
        assert ack.get("columnFull").getAsBoolean()&&!ack.get("permit").getAsBoolean()&&ack.getAsJsonObject("column").equals(action.get("targetColumn"));
        assert StashCatalog.accept(root,task,run,WORKER.toString(),"A",message).equals(ack) : "The full-column acknowledgement is idempotent";
        assert StashCatalog.get(root,"A",scope,"Limit").getAsJsonObject("columnReservations").size()==1 : "No overflow column was allocated";
        JsonObject restarted=new JsonObject();restarted.addProperty("id",WORKER.toString());restarted.addProperty("token",THIRD.toString());restarted.add("action",action);task.getAsJsonObject("runs").add(WORKER.toString(),restarted);
        message.remove("exhaustedColumn");message.addProperty("token",THIRD.toString());message.addProperty("delivery",1);StashCatalog.accept(root,task,restarted,WORKER.toString(),"A",message);
        JsonObject foreign=filled.deepCopy();foreign.addProperty("x",2);foreign.getAsJsonArray("shulkers").get(0).getAsJsonObject().addProperty("name","Beta");message.add("observation",foreign);message.addProperty("delivery",2);
        assert StashCatalog.accept(root,task,restarted,WORKER.toString(),"A",message).has("error") : "Changed stock stops the locked job rather than selecting another column";
    }

    private static void sharedColumnTransfer() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("monocle-shared-column-");String scope="server\nnether";
        JsonObject p=StashCatalog.plan(JsonParser.parseString("{name:'Shared',homeName:'vault',minX:0,maxX:4,minY:116,maxY:119,minZ:0,maxZ:3}").getAsJsonObject());
        JsonObject alpha=JsonParser.parseString("{slot:0,item:'minecraft:blue_shulker_box',quantity:1,name:'Alpha',contentsKnown:true,items:{'minecraft:obsidian':64}}").getAsJsonObject(),beta=alpha.deepCopy();beta.addProperty("name","Beta");beta.addProperty("slot",1);
        String a=StashCatalog.kitTypeId(alpha),b=StashCatalog.kitTypeId(beta);
        JsonObject cell=JsonParser.parseString("{x:2,y:116,z:0,status:'observed',block:'minecraft:chest',items:{'minecraft:blue_shulker_box':1,'minecraft:obsidian':64},shulkers:[]}").getAsJsonObject();cell.getAsJsonArray("shulkers").add(alpha);
        StashCatalog.save(root,"A",scope,p,cell);StashCatalog.reserveColumn(root,"A",scope,"Shared",a,java.util.Set.of());
        JsonObject action=p.deepCopy();action.addProperty("type","StashDeposit");action.addProperty("kitTypeId",b);action.addProperty("count",1);action.add("targetColumn",JsonParser.parseString("{x:2,y:116,z:0,axis:'X'}"));JsonArray allowed=new JsonArray();allowed.add(a);allowed.add(b);action.add("columnKitTypeIds",allowed);
        assert StashCatalog.columnKitTypes(StashCatalog.plan(action)).equals(java.util.Set.of(a,b));
        JsonObject bad=action.deepCopy();bad.remove("targetColumn");rejects(()->StashCatalog.plan(bad));
        JsonObject duplicate=action.deepCopy();duplicate.getAsJsonArray("columnKitTypeIds").add(a);rejects(()->StashCatalog.plan(duplicate));
        JsonObject missing=action.deepCopy();missing.addProperty("kitTypeId",WORKER.toString());rejects(()->StashCatalog.plan(missing));
        JsonObject source=p.deepCopy();source.addProperty("name","Source");source.addProperty("minX",100);source.addProperty("maxX",104);JsonObject sourceCell=cell.deepCopy();sourceCell.addProperty("x",100);sourceCell.add("shulkers",new JsonArray());sourceCell.getAsJsonArray("shulkers").add(beta);StashCatalog.save(root,"A",scope,source,sourceCell);
        JsonObject request=source.deepCopy();for(String key:List.of("kitTypeId","count","targetColumn","columnKitTypeIds"))request.add(key,action.get(key).deepCopy());request.addProperty("destination","Stash");request.addProperty("targetStashName","Shared");
        JsonObject routed=StashCatalog.kitAction(root,"A",scope,request,WORKER.toString());assert !routed.has("columnKitTypeIds")&&routed.getAsJsonObject("targetStash").get("columnKitTypeIds").equals(allowed) : "Shared destination rules never reach source selection";
        JsonObject run=JsonParser.parseString("{id:'"+WORKER+"',token:'"+OTHER+"'}").getAsJsonObject();run.add("action",action);
        JsonObject task=JsonParser.parseString("{crew:'A',server:'server',dimension:'nether',id:'shared-task',runs:{}}").getAsJsonObject();task.getAsJsonObject("runs").add(WORKER.toString(),run);
        JsonObject message=TaskWire.message("stash-findings");message.addProperty("task","shared-task");message.addProperty("run",WORKER.toString());message.addProperty("token",OTHER.toString());message.add("action",action);message.addProperty("delivery",1);
        JsonObject ack=StashCatalog.accept(root,task,run,WORKER.toString(),"A",message);assert !ack.has("error")&&ack.get("column").equals(action.get("targetColumn")) : "Explicit sharing upgrades an existing compatible single-kit reservation";
        JsonObject reservation=StashCatalog.get(root,"A",scope,"Shared").getAsJsonObject("columnReservations").getAsJsonObject("2,0");assert reservation.get("columnKitTypeIds").equals(allowed)&&reservation.get("kitTypeId").getAsString().equals(a) : "Sharing persists while preserving the original primary kit";
        cell.getAsJsonArray("shulkers").add(beta);cell.getAsJsonObject("items").addProperty("minecraft:blue_shulker_box",2);cell.getAsJsonObject("items").addProperty("minecraft:obsidian",128);
        assert StashCatalog.columnOccupant(cell).equals("mixed")&&StashCatalog.columnContentsMatch(cell,java.util.Set.of(a,b));
        JsonObject loose=cell.deepCopy();loose.getAsJsonObject("items").addProperty("minecraft:stone",1);assert !StashCatalog.columnContentsMatch(loose,java.util.Set.of(a,b));
        JsonObject unknown=cell.deepCopy();unknown.addProperty("inferred",true);assert !StashCatalog.columnContentsMatch(unknown,java.util.Set.of(a,b));
        message.add("observation",cell);message.addProperty("delivery",2);ack=StashCatalog.accept(root,task,run,WORKER.toString(),"A",message);assert ack.get("permit").getAsBoolean() : "Only explicitly listed mixed kits permit a deposit";
        assert StashCatalog.accept(root,task,run,WORKER.toString(),"A",message).equals(ack);
        assert StashCatalog.reserveColumn(root,"A",scope,"Shared",a,java.util.Set.of())==null : "An ordinary single-kit job cannot commandeer a shared column";
        message.remove("observation");message.addProperty("exhaustedColumn",true);message.addProperty("delivery",3);ack=StashCatalog.accept(root,task,run,WORKER.toString(),"A",message);assert ack.get("columnFull").getAsBoolean()&&!ack.get("permit").getAsBoolean();
        assert StashCatalog.get(root,"A",scope,"Shared").getAsJsonObject("columnReservations").size()==1 : "Shared full columns never allocate overflow";
        message.remove("exhaustedColumn");message.add("observation",loose);message.addProperty("delivery",4);assert StashCatalog.accept(root,task,run,WORKER.toString(),"A",message).has("error");
        assert StashCatalog.get(root,"A",scope,"Shared").getAsJsonObject("columnReservations").getAsJsonObject("2,0").get("blocked").getAsBoolean() : "Unrelated loose stock still blocks sharing";
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
        assert HighwayCoordinator.validSharedContainer(assignment, JsonParser.parseString("{x:2,y:116,z:-28}").getAsJsonObject())
            : "A recipient may fly back through rendered highway to the donor's real shulker";
        for (String invalid : List.of("{}", "{x:6,y:116,z:137}", "{x:0,y:117,z:137}", "{x:0,y:116,z:-29}", "{x:0,y:116,z:613}"))
            assert !HighwayCoordinator.validSharedContainer(assignment, JsonParser.parseString(invalid).getAsJsonObject()) : invalid;
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
        JsonObject detached=task("detached",0);
        detached.getAsJsonObject("runs").add(OTHER.toString(),run(detached).deepCopy());
        run(detached).addProperty("status","Running");
        QueuePolicy.detach(detached,WORKER,true);
        assert QueuePolicy.choose(List.of(detached),WORKER)==null;
        assert QueuePolicy.choose(List.of(detached),OTHER)==detached;
        assert QueuePolicy.dispatch(detached,null,WORKER).command().equals("pause");
        run(detached).addProperty("status","Suspending");run(detached).addProperty("requestedStatus","Suspended");
        QueuePolicy.pause(detached);QueuePolicy.resume(detached);
        assert QueuePolicy.detached(checkpoint(detached),WORKER) : "Global resume and persistence preserve individual intent";
        assert QueuePolicy.dispatch(detached,detached,WORKER).command().isEmpty();
        QueuePolicy.detach(detached,WORKER,false);
        assert QueuePolicy.dispatch(detached,detached,WORKER).command().equals("resume");
        rejects(()->QueuePolicy.detach(detached,UUID.randomUUID(),true));
        QueuePolicy.cancel(detached);
        assert QueuePolicy.dispatch(detached,null,WORKER).command().equals("cancel");
        rejects(()->QueuePolicy.detach(detached,WORKER,false));
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
        report.addProperty("status", "Complete");report.add("workflowResult",JsonParser.parseString("{delivered:41,sourceExhausted:true}")); TaskWire.applyStatus(urgent, run(urgent), report, true);
        assert run(urgent).getAsJsonObject("workflowResult").get("delivered").getAsInt()==41;
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
        hello.addProperty("combat","invalid");rejects(()->PlayerObservation.fromHello(hello,100));
        JsonObject combat=new JsonObject();combat.addProperty("note","x".repeat(4096));hello.add("combat",combat);rejects(()->PlayerObservation.fromHello(hello,100));
        combat.addProperty("note","small");assert PlayerObservation.fromHello(hello,100)!=null;
        hello.remove("combat");
        JsonObject readiness=new JsonObject();readiness.addProperty("state","Lobby");readiness.addProperty("reason","Waiting for lobby exit");hello.add("worldReadiness",readiness);
        assert PlayerObservation.fromHello(hello,100).equals(worker);
        readiness.addProperty("state","Invented");rejects(()->PlayerObservation.fromHello(hello,100));
        readiness.addProperty("state","Ready");readiness.addProperty("reason","x".repeat(161));rejects(()->PlayerObservation.fromHello(hello,100));hello.remove("worldReadiness");
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
    private static void rwpFollow() {
        JsonObject execution = JsonParser.parseString("{scope:{server:'play.example.org',dimension:'minecraft:the_nether'},action:{type:'dev.monocle.follow.v1',arguments:{target:'" + OTHER + "',radius:2,ticks:100}}}").getAsJsonObject();
        JsonObject nativeAction = RwpWorkerConnection.nativeMovement(execution);
        assert nativeAction.get("type").getAsString().equals("Travel") && nativeAction.get("follow").getAsBoolean();
        assert nativeAction.get("adaptiveFlight").getAsBoolean() && nativeAction.get("ticks").getAsInt() == 0;
        assert nativeAction.get("dimension").getAsString().equals("minecraft:the_nether");
        assert execution.getAsJsonObject("action").getAsJsonObject("arguments").get("ticks").getAsInt() == 100 : "Mapping cannot mutate host authority";
        for (String invalid : List.of("{target:'bad',radius:2,ticks:0}", "{target:'"+OTHER+"',radius:9,ticks:0}",
            "{target:'"+OTHER+"',radius:2,ticks:-1}", "{target:'"+OTHER+"',radius:2,ticks:1.5}",
            "{target:'"+OTHER+"',radius:'2',ticks:0}", "{target:'"+OTHER+"',radius:2,ticks:0,bodyguard:true}")) {
            JsonObject bad = execution.deepCopy(); bad.getAsJsonObject("action").add("arguments", JsonParser.parseString(invalid));
            rejects(() -> RwpWorkerConnection.nativeMovement(bad));
        }
    }

    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (IllegalArgumentException | IllegalStateException expected) { }
    }
}
