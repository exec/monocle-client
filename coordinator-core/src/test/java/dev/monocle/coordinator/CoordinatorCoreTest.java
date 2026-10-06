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
        boolean enabled = false; assert enabled = true;
        if (!enabled) throw new IllegalStateException("Run with assertions enabled");
        workerFrames();
        liveConfiguration();
        configurationInspection();
        guidedConfiguration();
        configurationReadback();
        stashScan();
        stashColumns();
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
        JsonArray deposited=new JsonArray();deposited.add(JsonParser.parseString("{x:100,y:116,z:0}"));StashCatalog.invalidateDeposited(root,"A","server\nnether","Overflow",deposited);
        assert StashCatalog.get(root,"A","server\nnether","Overflow").getAsJsonObject("containers").getAsJsonObject("100,116,0").get("status").getAsString().equals("unscanned");
        assert StashCatalog.get(root,"A","server\nnether","Overflow").getAsJsonObject("containers").getAsJsonObject("100,116,0").get("reason").getAsString().contains("attempted") : "An attempted transfer cannot be reported as a verified deposit";
        JsonObject missed=observation.deepCopy();missed.addProperty("status","unscanned");missed.add("items",new JsonObject());missed.addProperty("reason","Opening timed out");
        StashCatalog.save(root,"A","server\nnether",p,missed);
        assert StashCatalog.list(root).get(0).getAsJsonObject().get("unscanned").getAsInt()==2;
        JsonObject inferred=observation.deepCopy();inferred.addProperty("inferred",true);StashCatalog.save(root,"A","server\nnether",p,inferred);
        assert StashCatalog.list(root).get(0).getAsJsonObject().get("inferred").getAsInt()==1&&StashCatalog.list(root).get(0).getAsJsonObject().get("observed").getAsInt()==1 : "Estimates never masquerade as observed containers";
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
        JsonObject badColumn=column.deepCopy();badColumn.addProperty("y",117);rejects(()->StashCatalog.checkedColumn(p,badColumn));
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
