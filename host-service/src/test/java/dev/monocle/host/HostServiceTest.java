package dev.monocle.host;

import com.google.gson.*;
import dev.monocle.client.systems.bots.BotLua;
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
    public static void main(String[] args) throws Exception {
        boolean assertions = false; assert assertions = true; if (!assertions) throw new AssertionError("Enable assertions");
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
            await(() -> host.control(op("status")).getAsJsonArray("workers").size() == 2, worker, other);
            JsonObject observed=host.control(op("status")).getAsJsonArray("workers").get(0).getAsJsonObject();
            assert observed.getAsJsonObject("diagnostics").get("tickAgeMs").getAsInt()==17 && observed.has("observationAgeMs") : "Idle workers expose fresh diagnostic data without a task";
            observed.getAsJsonObject("diagnostics").addProperty("tickAgeMs",999);
            assert host.control(op("status")).getAsJsonArray("workers").get(0).getAsJsonObject().getAsJsonObject("diagnostics").get("tickAgeMs").getAsInt()==17 : "API snapshots cannot mutate retained reports";
            assert request(http, api, op("status"), "wrong", false).statusCode() == 403;
            assert request(http, api, op("status"), TOKEN, true).statusCode() == 403;
            assert request(http, api, op("status"), TOKEN, false).statusCode() == 200;
            boolean locked = false;
            try (HostService duplicate = new HostService(directory, "127.0.0.1", 0, CREWS, 30)) { throw new AssertionError("Second host acquired same journal"); }
            catch (IllegalStateException expected) { locked = true; }
            assert locked;
            JsonObject first = submit(workerId, 0); UUID firstId = UUID.fromString(text(first, "id"));
            assert request(http, api, first, TOKEN, false).statusCode() == 200;
            assert text(host.control(first), "id").equals(firstId.toString()) : "Same submission ID is idempotent";
            JsonObject collision = first.deepCopy(); collision.addProperty("priority", 3); rejects(() -> host.control(collision));
            await(() -> state(host, firstId).equals("Running"), worker, other);
            assert other.installs == 0 : "No workflow broadcasts across crews";
            JsonObject crossCrew = submit(otherId, 0); rejects(() -> host.control(crossCrew));
            JsonObject urgent = submit(workerId, 10); UUID urgentId = UUID.fromString(text(urgent, "id")); host.control(urgent);
            await(() -> state(host, firstId).equals("Suspended") && state(host, urgentId).equals("Running"), worker, other);
            JsonObject cancel = op("cancel"); cancel.addProperty("id", urgentId.toString()); host.control(cancel); host.control(cancel);
            await(() -> state(host, urgentId).equals("Cancelled") && state(host, firstId).equals("Running"), worker, other);
            assert worker.installs == 2 : "Preempted workflow resumes the same package, not a fresh execution";
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
            await(() -> !host.control(op("status")).getAsJsonArray("workers").isEmpty(), worker);
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
            await(() -> host.control(op("status")).getAsJsonArray("workers").size() == 1, lost);
            assert state(host, unfinished).equals("Inspection required");
            assert lost.installs == 0;
            JsonObject cancel = op("cancel"); cancel.addProperty("id", unfinished.toString()); host.control(cancel);
            await(() -> state(host, unfinished).equals("Cancelled"), lost);
        }
        System.out.println("Standalone host checks passed without Minecraft: authenticated HTTP/sockets, crew isolation, real package frames, priorities, pause/resume/cancel, idempotent submissions, ownership lock, restart and missing-checkpoint recovery. Journals: " + directory);
        highway();
        workerRecovery();
        highwayReconnect();
        highwayRestart();
        cancellationRestart();
    }
    private static void cancellationRestart() throws Exception {
        Path directory=Files.createTempDirectory("monocle-cancel-restart-check-");
        UUID workerId=UUID.randomUUID(), task; Map<UUID,JsonObject> checkpoints=new LinkedHashMap<>();
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,0);
            Worker worker=new Worker(host.port(),KEY,workerId,checkpoints)) {
            await(()->!host.control(op("status")).getAsJsonArray("workers").isEmpty(),worker);
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
    private static void highwayRestart() throws Exception {
        Path directory=Files.createTempDirectory("monocle-native-restart-check-");
        UUID id=UUID.randomUUID(),task;Map<UUID,JsonObject> checkpoints=new LinkedHashMap<>();JsonObject saved;
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);Worker worker=new Worker(host.port(),KEY,id,checkpoints)) {
            await(()->host.control(op("status")).getAsJsonArray("workers").size()==1,worker);
            JsonObject request=highwayRequest(id,UUID.randomUUID());request.getAsJsonArray("workers").remove(1);task=UUID.fromString(text(request,"id"));host.control(request);
            await(()->worker.begun,worker);worker.detach();await(()->worker.detached,worker);worker.reserve();await(()->!worker.supplyLock.isEmpty(),worker);
            saved=worker.nativeJob.deepCopy();
        }
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);Worker worker=new Worker(host.port(),KEY,id,checkpoints)) {
            worker.nativeJob=saved;worker.detached=true;worker.announce();
            await(()->host.control(op("status")).getAsJsonArray("workers").size()==1,worker);
            assert state(host,task).equals("Inspection required");
            JsonObject command=op("resume");command.addProperty("id",task.toString());
            boolean refused=false;try{host.control(command);}catch(IllegalStateException expected){refused=true;}assert refused : "Never replay an interrupted container transaction";
            command.addProperty("op","cancel");host.control(command);await(()->state(host,task).equals("Cancelled") && worker.nativeJob==null,worker);
            JsonObject recovery=TaskFiles.read(directory.resolve("ended-"+text(saved,"job")+"-supplies.json"));
            assert text(recovery,"supplyOwner").equals(id.toString()) && !text(recovery,"supplyPosition").isEmpty() : "Ending after a restart preserves uncertain container ownership/location";
        }
        System.out.println("Native restart/uncertain-supply checks passed: explicit inspection, no replay, durable cancellation and archived supply ownership.");
    }
    private static void workerRecovery() throws Exception {
        Path directory=Files.createTempDirectory("monocle-worker-recovery-check-"); UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            Worker first=new Worker(host.port(),KEY,a,new LinkedHashMap<>()); Worker second=new Worker(host.port(),KEY,b,new LinkedHashMap<>())) {
            await(()->host.control(op("status")).getAsJsonArray("workers").size()==2,first,second);
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
            await(()->host.control(op("status")).getAsJsonArray("workers").size()==2,first,second);
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
                returned.nativeJob=saved;returned.reconnectReady=false;returned.announce();
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

    private static void highway() throws Exception {
        Path directory=Files.createTempDirectory("monocle-native-host-check-");
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        try(HostService host=new HostService(directory,"127.0.0.1",0,CREWS,30);
            Worker first=new Worker(host.port(),KEY,a,new LinkedHashMap<>());
            Worker second=new Worker(host.port(),KEY,b,new LinkedHashMap<>())) {
            await(()->host.control(op("status")).getAsJsonArray("workers").size()==2,first,second);
            JsonObject request=highwayRequest(a,b);UUID task=UUID.fromString(text(request,"id"));host.control(request);
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
    static JsonObject op(String op) { JsonObject request = new JsonObject(); request.addProperty("op", op); return request; }
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
    private static final class Worker implements AutoCloseable {
        final SwarmConnection c;
        final UUID id;
        final Map<UUID, JsonObject> checkpoints;
        String current = "", digest, transfer;
        final List<String> chunks = new ArrayList<>();
        int count, installs, resumes;
        long announceAt;
        JsonObject nativeJob;
        boolean recoveryOnly, recoveryReady=true;
        boolean reconnectReady=true;
        String scope="test.invalid\nminecraft:the_nether";
        int restores;
        int row;
        boolean begun, advance, detached, returning, ignoreEnd, nativePaused;
        int nativeResumes, inspections;
        String supplyLock="";
        Worker(int port, String key, UUID id, Map<UUID, JsonObject> checkpoints) throws Exception {
            this.id = id; this.checkpoints = checkpoints;
            c = new SwarmConnection(new Socket("127.0.0.1", port), key, false); c.start();
            await(c::connected);
            announce();
        }
        private void announce() {
            JsonObject hello = new JsonObject(); hello.addProperty("type", "hello"); hello.addProperty("id", id.toString()); hello.addProperty("name", "Worker");
            hello.addProperty("taskProtocol", 1); hello.addProperty("scope", scope); hello.addProperty("job", "");
            hello.addProperty("reconnectReady",reconnectReady);
            JsonObject diagnostics=new JsonObject();diagnostics.addProperty("version",1);diagnostics.addProperty("tickAgeMs",17);hello.add("diagnostics",diagnostics);
            hello.addProperty("x", 0); hello.addProperty("y", 116); hello.addProperty("z", 100);
            if(nativeJob!=null) {
                hello.addProperty("assignmentLoaded",!recoveryOnly); hello.addProperty("recoveryReady",recoveryReady);
                hello.addProperty("job",text(nativeJob,"job"));hello.addProperty("generation",nativeJob.get("generation").getAsInt());
                hello.addProperty("currentRow",row);hello.addProperty("verifiedBase",row+1);hello.addProperty("verifiedMask",31);hello.addProperty("currentResolved",true);
                hello.addProperty("phase",nativePaused?"paused":detached?"resupplying":row==16?"complete":begun?"building":"ready");hello.addProperty("begun",begun);hello.addProperty("regroupReady",true);
                hello.addProperty("serviceReturning",returning);hello.addProperty("serviceReady",returning);hello.addProperty("ack",supplyLock);
                JsonObject exchange=new JsonObject();exchange.addProperty("stage","idle");exchange.addProperty("need",-1);hello.add("exchange",exchange);
                hello.addProperty("x",0);hello.addProperty("z",100+row);hello.addProperty("available",false);
                for(String key:List.of("suppliers","serviceRevisions")) if(nativeJob.has(key))hello.add(key,nativeJob.get(key).deepCopy());
                if(returning) for(JsonElement id:nativeJob.getAsJsonArray("activeMembers")) if(!id.getAsString().equals(this.id.toString())) { hello.addProperty("renderedCrew",id.getAsString());break; }
            }
            c.send(hello.toString());
            heartbeat(); announceAt = System.nanoTime();
        }
        private void heartbeat() {
            JsonObject message = message("worker"); message.addProperty("current", current);
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
                    case "heartbeat" -> { }
                    case "task-begin" -> { transfer = text(message, "run"); digest = text(message, "hash"); count = message.get("count").getAsInt(); chunks.clear(); }
                    case "task-chunk" -> {
                        assert text(message, "run").equals(transfer) && message.get("index").getAsInt() == chunks.size(); chunks.add(text(message, "data"));
                        if (chunks.size() == count) {
                            String data = String.join("", chunks); assert TaskFiles.hash(data).equals(digest);
                            JsonObject envelope = JsonParser.parseString(new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8)).getAsJsonObject();
                            JsonObject metadata = envelope.remove("dispatch").getAsJsonObject(); HostService.checkedPackage(envelope);
                            assert metadata.getAsJsonArray("supportedActions").asList().stream().anyMatch(v -> v.getAsString().equals("Highway"));
                            var decision = BotLua.next(text(envelope.getAsJsonObject("programs").getAsJsonObject(text(envelope, "entry")), "script"), new JsonObject(), metadata.getAsJsonObject("args"), null, null);
                            if (text(decision.action(), "type").equals("RecoverSupplies")) {
                                assert metadata.getAsJsonArray("supportedActions").asList().stream().anyMatch(v -> v.getAsString().equals("RecoverSupplies"));
                                decision = BotLua.next(text(envelope.getAsJsonObject("programs").getAsJsonObject(text(envelope, "entry")), "script"), decision.state(), metadata.getAsJsonObject("args"), new JsonObject(), null);
                            }
                            assert Set.of("Wait","Highway").contains(text(decision.action(), "type"));
                            assert !checkpoints.containsKey(UUID.fromString(transfer));
                            JsonObject run = new JsonObject(); run.addProperty("run", transfer); run.addProperty("status", "Ready");run.add("action",decision.action());run.addProperty("token",UUID.randomUUID().toString()); checkpoints.put(UUID.fromString(transfer), run); installs++; sendStatus(run);
                        }
                    }
                    case "task-control" -> {
                        JsonObject run = checkpoints.get(UUID.fromString(text(message, "run"))); if (run == null) break;
                        if (!QueuePolicy.terminal(text(run, "status"))) {
                            String command = text(message, "command");
                            if (!command.equals("resume") && nativeJob!=null && current.equals(text(run,"run"))) {
                                // Real BotRuntime retains control while nativeBusy(), including when merely paused.
                                run.addProperty("status","Suspending");run.addProperty("requestedStatus",command.equals("pause")?"Suspended":"Cancelled");
                                sendStatus(run);heartbeat();break;
                            }
                            if (command.equals("resume")) { assert current.isEmpty() || current.equals(text(run, "run")); current = text(run, "run"); resumes++;run.remove("requestedStatus"); }
                            else if (current.equals(text(run, "run"))) current = "";
                            run.addProperty("status", command.equals("resume") ? "Running" : command.equals("pause") ? "Suspended" : "Cancelled");
                        }
                        sendStatus(run); heartbeat();
                    }
                    case "prepare", "reconfigure" -> { nativeJob=message.deepCopy();row=message.get("startRow").getAsInt();begun=nativePaused=false;announce(); }
                    case "restore" -> { assert recoveryOnly && recoveryReady; restores++; recoveryOnly=false; nativeJob=message.deepCopy(); row=message.get("startRow").getAsInt(); detached=HighwayCoordinator.detachedMembers(nativeJob).contains(id); returning=detached; announce(); }
                    case "begin" -> { begun=true;announce(); }
                    case "window" -> { if(advance && !nativePaused && !detached && row<message.get("limit").getAsInt() && (message.get("mask").getAsInt()&1)!=0) { row++;announce(); } }
                    case "service-detach", "service-join" -> {
                        nativeJob=HighwayCoordinator.serviceUpdateAssignment(nativeJob,message);
                        detached=HighwayCoordinator.detachedMembers(nativeJob).contains(id);
                        if(!detached)returning=false;announce();
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
                        if(!QueuePolicy.terminal(text(r,"status")))r.addProperty("status",flag(message,"success")?"Complete":"Failed");r.remove("action");r.remove("token");if(current.equals(text(r,"run")))current="";sendStatus(r);heartbeat();
                    }
                    case "pause" -> {nativePaused=true;announce();}
                    case "resume" -> {nativePaused=false;nativeResumes++;announce();}
                    case "resource-inspected" -> {inspections++;announce();}
                    case "regroup", "regroupCancel", "lost", "nudge", "anticipate-supply", "service-front" -> { announce(); }
                    default -> throw new AssertionError("Unexpected host message " + text(message, "type"));
                }
            }
        }
        private void sendStatus(JsonObject run) { JsonObject message = run.deepCopy(); message.addProperty("type", "task-status"); message.addProperty("detail", "Simulated native worker"); c.send(message.toString()); }
        void nativeSend(String type,boolean detach) {
            JsonObject m=new JsonObject();m.addProperty("type",type);m.addProperty("job",text(nativeJob,"job"));m.addProperty("generation",nativeJob.get("generation").getAsInt());
            m.addProperty("serviceRevision",HighwayCoordinator.serviceRevision(nativeJob,id));m.addProperty("detach",detach);
            m.addProperty("x",0);m.addProperty("y",116);m.addProperty("z",97);m.addProperty("lock",supplyLock);c.send(m.toString());
        }
        void detach(){nativeSend("request",true);}
        void reserve(){nativeSend("request",false);}
        void release(){nativeSend("release",false);returning=true;announce();}
        @Override public void close() { c.disconnect(); }
    }
}
