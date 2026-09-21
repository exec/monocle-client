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
        if (args.length == 1 && args[0].equals("--webui")) { webUiPreview(); return; }
        boolean assertions = false; assert assertions = true; if (!assertions) throw new AssertionError("Enable assertions");
        if (args.length == 1 && args[0].equals("--history")) {
            rollingHistoryCheck(); System.out.println("Rolling history checks passed: oldest completion, active protection and rejected submissions."); return;
        }
        assert HostService.ACTIONS.contains("Tpa") : "Standalone workers must be able to use the trusted TPA action";
        operationsCheck();
        autoTpyCheck();
        stashScanCheck();
        rollingHistoryCheck();
        checkpointReleaseCheck();
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
            JsonObject chatRequest=op("chat");chatRequest.addProperty("crew","Default");chatRequest.addProperty("worker",workerId.toString());chatRequest.addProperty("commandId",UUID.randomUUID().toString());chatRequest.addProperty("text","/home stash1");
            host.control(chatRequest);await(()->worker.chatMessages.size()==1&&host.control(op("status")).getAsJsonArray("chat").size()==1,worker,other);
            assert other.chatMessages.isEmpty()&&worker.chatMessages.getFirst().equals("/home stash1") : "Chat remains isolated to the selected authenticated worker";
            chatRequest.addProperty("text","invalid\ncommand");rejects(()->host.control(chatRequest));
            JsonObject observed=host.control(op("status")).getAsJsonArray("workers").get(0).getAsJsonObject();
            assert observed.getAsJsonObject("diagnostics").get("tickAgeMs").getAsInt()==17 && observed.has("observationAgeMs") : "Idle workers expose fresh diagnostic data without a task";
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
                String frame = "Pink shulkers 🌸 " + "x".repeat(15000);
                assert worker.c.send(frame); await(() -> frame.equals(host.poll()));
                assert host.send("cancelled-execution-ack"); await(() -> "cancelled-execution-ack".equals(worker.c.poll()));
                try (HttpClient http = HttpClient.newHttpClient()) {
                    for (String suffix : List.of("/wrong-path", "/v1/workers?token=secret")) {
                        try { http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + listener.webPort() + suffix), new java.net.http.WebSocket.Listener() {}).get(3, java.util.concurrent.TimeUnit.SECONDS); throw new AssertionError("Wrong endpoint accepted"); }
                        catch (java.util.concurrent.ExecutionException expected) { }
                    }
                    try { http.newWebSocketBuilder().header("Origin", "https://browser.invalid").buildAsync(URI.create("ws://127.0.0.1:" + listener.webPort() + "/v1/workers"), new java.net.http.WebSocket.Listener() {}).get(3, java.util.concurrent.TimeUnit.SECONDS); throw new AssertionError("Browser-origin worker accepted"); }
                    catch (java.util.concurrent.ExecutionException expected) { }
                }
                CrewTransport oversized = CrewTransport.worker("ws://127.0.0.1:" + listener.webPort() + "/v1/workers", 6969);
                try { oversized.open(); oversized.read(); oversized.write("x".repeat(65000)); await(oversized::closed); }
                finally { oversized.close(); }
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
    static JsonObject op(String op) { JsonObject request = new JsonObject(); request.addProperty("op", op); return request; }
    private static void stashScanCheck() throws Exception {
        Path dir=Files.createTempDirectory("monocle-host-stash-check-");UUID workerId=UUID.randomUUID();
        try(HostService host=new HostService(dir,"127.0.0.1",0,CREWS,30);Worker worker=new Worker(host.port(),KEY,workerId,new LinkedHashMap<>())){
            await(()->host.control(op("status")).getAsJsonArray("workers").size()==1,worker);
            JsonObject definition=message("stash-definition");definition.addProperty("scope","test.invalid\nminecraft:the_nether");definition.add("stash",JsonParser.parseString("{\"name\":\"Depot\",\"minX\":0,\"maxX\":1,\"minY\":116,\"maxY\":116,\"minZ\":0,\"maxZ\":0}").getAsJsonObject());worker.c.send(definition.toString());
            await(()->host.control(op("status")).getAsJsonArray("stashes").size()==1,worker);
            JsonObject imported=message("stash-import");imported.addProperty("scope","test.invalid\nminecraft:the_nether");imported.add("stash",definition.get("stash").deepCopy());imported.add("observation",JsonParser.parseString("{\"x\":1,\"y\":116,\"z\":0,\"status\":\"observed\",\"block\":\"minecraft:chest\",\"reason\":\"\",\"items\":{\"minecraft:obsidian\":64},\"shulkers\":[]}").getAsJsonObject());worker.c.send(imported.toString());
            await(()->host.control(op("status")).getAsJsonArray("stashes").get(0).getAsJsonObject().getAsJsonObject("items").has("minecraft:obsidian"),worker);
            JsonObject request=submit(workerId,0);UUID task=UUID.fromString(text(request,"id"));request.addProperty("name","Inspect depot");request.addProperty("script","return function(ctx) return bot.stash_scan(ctx.args) end");
            request.add("args",JsonParser.parseString("{\"name\":\"Depot\",\"minX\":0,\"maxX\":1,\"minY\":116,\"maxY\":116,\"minZ\":0,\"maxZ\":0}").getAsJsonObject());host.control(request);
            await(()->state(host,task).equals("Running"),worker);
            JsonObject run=worker.checkpoints.values().iterator().next();
            JsonObject observation=JsonParser.parseString("{\"x\":0,\"y\":116,\"z\":0,\"status\":\"observed\",\"block\":\"minecraft:chest\",\"reason\":\"\",\"items\":{\"minecraft:stone\":1728},\"shulkers\":[]}").getAsJsonObject();
            JsonObject message=message("stash-findings");message.addProperty("run",text(run,"run"));message.addProperty("task",task.toString());message.add("action",run.get("action").deepCopy());message.add("token",run.get("token"));message.addProperty("delivery",1);message.add("observation",observation);
            worker.c.send(message.toString());await(()->worker.stashAcks==1,worker);
            worker.c.send(message.toString());await(()->worker.stashAcks==2,worker);
            JsonObject stash=host.control(op("status")).getAsJsonArray("stashes").get(0).getAsJsonObject();assert stash.getAsJsonObject("items").get("minecraft:stone").getAsInt()==1728 : "Duplicate delivery is acknowledged, not double-counted";
            JsonObject inspect=op("stash-get");inspect.addProperty("crew","Default");inspect.addProperty("scope","test.invalid\nminecraft:the_nether");inspect.addProperty("name","Depot");assert host.control(inspect).getAsJsonObject("containers").size()==2;
            JsonObject cancel=op("cancel");cancel.addProperty("id",task.toString());host.control(cancel);await(()->worker.current.isEmpty()&&state(host,task).equals("Cancelled"),worker);
        }
        try(HostService restarted=new HostService(dir,"127.0.0.1",0,CREWS,30)){assert restarted.control(op("status")).getAsJsonArray("stashes").size()==1;}
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
    private static long connected(HostService host) { return host.control(op("status")).getAsJsonArray("workers").asList().stream().filter(w->flag(w.getAsJsonObject(),"connected")).count(); }

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

    private static void operationsCheck() throws Exception {
        UUID released=UUID.randomUUID(),pending=UUID.randomUUID();
        JsonObject old=JsonParser.parseString("{status:'Cancelled',runs:{'"+released+"':{status:'Cancelled'},'"+pending+"':{status:'Inspection required'}}}").getAsJsonObject();
        assert !HostService.workerJobPending(old,released) && HostService.workerJobPending(old,pending);
        old.addProperty("status","Running");assert HostService.workerJobPending(old,released);
        Path directory=Files.createTempDirectory("monocle-operations-host-");
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
            host.control(move);await(()->!worker.assignedKey.isEmpty(),worker);String key=worker.assignedKey;assert key.equals(TaskFiles.read(directory.resolve("host-config.json")).getAsJsonObject("crews").get(crewId).getAsString());
            worker.close();try(Worker reconnected=new Worker(host.port(),key,workerId,new LinkedHashMap<>())) {
                await(()->host.control(op("status")).getAsJsonArray("workers").asList().stream().anyMatch(w->text(w.getAsJsonObject(),"crew").equals(crewId) && flag(w.getAsJsonObject(),"connected")),reconnected);
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
        }
        System.out.println("Auto TPY checks passed: host-owned policy, ten-tick delay, exact same-crew routing and durable disable.");
    }

    private static final class Worker implements AutoCloseable {
        final SwarmConnection c;
        final UUID id;
        final Map<UUID, JsonObject> checkpoints;
        String current = "", digest, transfer;
        final List<String> chunks = new ArrayList<>();
        final List<String> chatMessages = new ArrayList<>();
        final List<JsonObject> tpaAccepts = new ArrayList<>();
        int count, installs, resumes;
        int stashAcks;
        long announceAt;
        JsonObject nativeJob;
        JsonObject ledger, exchangeState, offer;
        JsonObject roadForecast;
        JsonObject serviceFront;
        boolean recoveryOnly, recoveryReady=true;
        boolean reconnectReady=true;
        boolean connectionStopped;
        int supplyProtocol = 2;
        String scope="test.invalid\nminecraft:the_nether";
        String name = "Worker";
        boolean hasPosition = true;
        int restores;
        int row;
        boolean begun, advance, detached, returning, ignoreEnd, nativePaused, moduleOff;
        boolean ignoreJoin;
        boolean autoTpy;
        boolean delayLanding;
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
            hello.addProperty("chatProtocol",1);
            hello.addProperty("reconnectReady",reconnectReady); hello.addProperty("supplyProtocol",supplyProtocol);
            hello.addProperty("initialStockProtocol",1); hello.addProperty("initialStockReady",true);
            hello.addProperty("connectionStopped",connectionStopped);
            JsonObject diagnostics=new JsonObject();diagnostics.addProperty("version",1);diagnostics.addProperty("tickAgeMs",17);hello.add("diagnostics",diagnostics);
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
                    case "task-configuration-read" -> {
                        JsonObject report=new JsonObject();report.addProperty("note","Test-only worker readback");report.add("rows",new JsonArray());
                        for(JsonObject reply:ConfigurationReadback.replies(message,report))c.send(reply.toString());
                    }
                    case "manage-chat" -> { assert text(message,"scope").equals(scope);chatMessages.add(BotChat.command(text(message,"text")));JsonObject report=new JsonObject();report.addProperty("type","worker-chat");report.addProperty("direction","sent");report.addProperty("text",text(message,"text"));c.send(report.toString()); }
                    case "heartbeat" -> autoTpy=flag(message,"autoTpy");
                    case "task-tpa-accept" -> tpaAccepts.add(message.deepCopy());
                    case "assign-crew" -> { assignedKey=c.openSecret(text(message,"sealedKey")); }
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
                            if(text(decision.action(),"type").equals("StashScan")){decision.action().addProperty("workerIndex",metadata.get("workerIndex").getAsInt());decision.action().addProperty("workerCount",metadata.get("workerCount").getAsInt());}
                            assert Set.of("Wait","Highway","StashScan").contains(text(decision.action(), "type"));
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
                    case "task-stash-ack" -> { assert message.get("delivery").getAsInt()==1;stashAcks++; }
                    case "task-stash-catalog" -> { assert message.getAsJsonArray("stashes").size()<=64; }
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
                    case "regroup", "regroupCancel", "lost", "nudge", "anticipate-supply" -> { announce(); }
                    default -> throw new AssertionError("Unexpected host message " + text(message, "type"));
                }
            }
        }
        private void sendStatus(JsonObject run) { JsonObject message = run.deepCopy(); message.addProperty("type", "task-status"); message.addProperty("detail", "Simulated native worker"); message.addProperty("readbackVersion",1); c.send(message.toString()); }
        void nativeSend(String type,boolean detach) {
            JsonObject m=new JsonObject();m.addProperty("type",type);m.addProperty("job",text(nativeJob,"job"));m.addProperty("generation",nativeJob.get("generation").getAsInt());
            m.addProperty("serviceRevision",HighwayCoordinator.serviceRevision(nativeJob,id));m.addProperty("detach",detach);
            m.addProperty("x",0);m.addProperty("y",116);m.addProperty("z",97);m.addProperty("lock",supplyLock);c.send(m.toString());
        }
        void detach(){nativeSend("request",true);}
        void requestTpa(String target){JsonObject request=new JsonObject();request.addProperty("type","worker-tpa-request");request.addProperty("request",UUID.randomUUID().toString());request.addProperty("target",target);request.addProperty("scope",scope);c.send(request.toString());}
        void reserve(){supplyLock="local-container";announce();}
        void release(){supplyLock="";returning=true;announce();}
        @Override public void close() { c.disconnect(); }
    }
}
