package dev.monocle.host;

import com.google.gson.*;
import dev.monocle.client.systems.bots.BotLua;
import dev.monocle.client.systems.bots.BotHistory;
import dev.monocle.client.systems.modules.misc.swarm.SwarmConnection;
import dev.monocle.coordinator.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.monocle.coordinator.TaskWire.*;

/** One coordinator thread/monitor, real worker protocol, no game or account in this process. */
public final class HostService implements AutoCloseable {
    public static final List<String> ACTIONS = List.of("Wait", "Travel", "DropItems", "Modules", "SetProfile", "Highway", "RecoverSupplies");
    private final Path journal;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final CrewListener listener;
    private final Map<String, String> crews;
    private final Map<String, String> selectors = new HashMap<>();
    private final Map<UUID, JsonObject> tasks = new LinkedHashMap<>();
    private final Map<SwarmConnection, Peer> peers = new HashMap<>();
    private final Map<UUID, Long> commands = new HashMap<>();
    private final Map<String, HighwayHost> highways = new LinkedHashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("Monocle coordinator").factory());
    private String failure = "";
    private String lastConnectionError = "";
    private boolean closed;
    private long heartbeatAt;
    private long historyAt;
    private final int historyDays;
    private static final class Peer {
        UUID id;
        String crew, current = "";
        PlayerObservation observation;
        JsonObject diagnostics;
        boolean reconciled;
        long seen;
        Transfer transfer;
        final Set<UUID> transferred = new HashSet<>();
    }
    private static final class Transfer {
        final UUID run; final String data; int next;
        Transfer(UUID run, String data) { this.run = run; this.data = data; }
    }

    public HostService(Path directory, String bind, int port, Map<String, String> crews, int historyDays) throws IOException {
        if (historyDays < -1 || historyDays > 3650) throw new IllegalArgumentException("historyDays must be -1 (keep) through 3650");
        this.historyDays = historyDays;
        this.crews = Map.copyOf(crews);
        if (crews.isEmpty() || crews.size() > 16) throw new IllegalArgumentException("Configure 1–16 crews");
        crews.forEach((name, key) -> {
            name(name); if (key.length() < 24 || key.length() > 128) throw new IllegalArgumentException("Crew keys must be 24–128 characters");
            if (selectors.put(SwarmConnection.credentialSelector(key), name) != null) throw new IllegalArgumentException("Every crew needs a distinct key");
        });
        Files.createDirectories(directory);
        journal = directory.resolve("host-tasks.json");
        lockChannel = FileChannel.open(directory.resolve("host.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        CrewListener opened = null;
        try {
            lock = lockChannel.tryLock();
            if (lock == null) throw new IllegalStateException("Another host owns this data directory");
            JsonObject saved = TaskFiles.read(journal);
            if (!saved.isEmpty()) {
                if (integer(saved, "version", 1, 1) != 1 || !saved.has("tasks") || saved.getAsJsonObject("tasks").size() > 64) throw new IllegalArgumentException("Invalid host journal");
                for (var entry : saved.getAsJsonObject("tasks").entrySet()) {
                    UUID id = UUID.fromString(entry.getKey()); JsonObject task = entry.getValue().getAsJsonObject();
                    validateTask(id, task);
                    if (flag(task, "cancelled")) QueuePolicy.cancel(task);
                    else if (!QueuePolicy.terminal(text(task, "status"))) {
                        task.addProperty("paused", true); task.addProperty("status", "Inspection required");
                        task.addProperty("detail", "Host restarted; inspect worker checkpoints before Resume");
                    }
                    tasks.put(id, task);
                }
            }
            persist();
            listener = opened = new CrewListener(bind, port, selector -> { String crew = selectors.get(selector); return crew == null ? null : this.crews.get(crew); });
            for (String crew : crews.keySet()) highways.put(crew, new HighwayHost(directory, crew, id -> nativeReady(crew, id), this::highwayCheckpoint));
        } catch (IOException | RuntimeException e) { if(opened!=null)opened.close();lockChannel.close(); ticker.shutdownNow(); throw e; }
        ticker.scheduleWithFixedDelay(this::tick, 0, 50, TimeUnit.MILLISECONDS);
    }

    public int port() { return listener.port(); }
    private void persist() {
        JsonObject root = new JsonObject(), records = new JsonObject(); root.addProperty("version", 1);
        tasks.forEach((id, task) -> records.add(id.toString(), task)); root.add("tasks", records); TaskFiles.write(journal, root);
    }
    // ponytail: one coordinator monitor; shard by crew only if measured controller load warrants it.
    private synchronized void tick() {
        if (closed || !failure.isEmpty()) return;
        try {
            for (SwarmConnection c : listener.connections()) {
                if (c == null) continue;
                if (!c.connected()) { peers.remove(c); continue; }
                for (int i = 0; i < 32; i++) {
                    String wire = c.poll(); if (wire == null) break;
                    try { receive(c, JsonParser.parseString(wire).getAsJsonObject()); }
                    catch (RuntimeException e) {
                        // A failed checkpoint is not a bad worker. Stop dispatch entirely until repaired.
                        if (e instanceof IllegalStateException && e.getMessage() != null && e.getMessage().startsWith("Task checkpoint failed")) throw e;
                        c.disconnect(); peers.remove(c);
                        lastConnectionError = "Rejected worker message: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " · " + e.getMessage());
                        if (lastConnectionError.length() > 1024) lastConnectionError = lastConnectionError.substring(0, 1024);
                        System.err.println(lastConnectionError); break;
                    }
                }
            }
            long now = System.nanoTime();
            if (now - heartbeatAt >= 1_000_000_000L) {
                for (SwarmConnection c : listener.connections()) if (c != null && c.connected()) c.send("{\"type\":\"heartbeat\"}");
                heartbeatAt = now;
            }
            for (var entry : List.copyOf(peers.entrySet())) {
                SwarmConnection c = entry.getKey(); Peer peer = entry.getValue();
                if (!c.connected() || !peer.reconciled || now - peer.seen >= 5_000_000_000L) continue;
                schedule(c, peer);
                flush(c, peer);
            }
            coordinateHighways();
            for (HighwayHost highway : highways.values()) highway.tick();
            boolean changed = false;
            for (JsonObject task : tasks.values()) {
                String status = QueuePolicy.summarizedStatus(task);
                if(task.has("nativeDefinition") && QueuePolicy.terminal(status) && !flag(task,"cancelled")) status="Running";
                if (!status.equals(text(task, "status"))) { task.addProperty("status", status); changed = true; }
                changed |= BotHistory.stamp(task, BotHistory.taskFinished(task, task.has("nativeDefinition")), System.currentTimeMillis());
            }
            if (now - historyAt >= 60_000_000_000L) {
                historyAt = now;
                changed |= tasks.values().removeIf(task -> BotHistory.taskFinished(task, task.has("nativeDefinition")) && BotHistory.expired(BotHistory.finishedAt(task), System.currentTimeMillis(), historyDays));
            }
            if (changed) persist();
        } catch (RuntimeException e) {
            failure = "Coordinator stopped: " + e.getClass().getSimpleName() + ". Inspect the journal/log before restarting.";
            System.err.println(failure); e.printStackTrace(System.err);
            listener.close(); // Worker connection-loss cleanup is safer than continuing after a failed durable intent.
        }
    }

    private void receive(SwarmConnection c, JsonObject message) {
        String type = text(message, "type");
        if (type.equals("hello")) {
            if (integer(message, "taskProtocol", 1, 1) != 1) throw new IllegalArgumentException("Install Monocle 0.7.3 or newer");
            PlayerObservation observation = PlayerObservation.fromHello(message, System.nanoTime());
            Peer peer = peers.get(c);
            if (peer != null && !peer.id.equals(observation.id())) throw new IllegalArgumentException("Player identity changed");
            if (peers.entrySet().stream().anyMatch(e -> e.getKey() != c && e.getKey().connected() && e.getValue().id.equals(observation.id()))) throw new IllegalArgumentException("Duplicate live worker identity");
            if (peer == null) { peer = new Peer(); peer.id = observation.id(); peer.crew = selectors.get(c.credentialId()); peers.put(c, peer); }
            if (peer.crew == null) throw new IllegalArgumentException("Unknown authenticated crew");
            HighwayHost highway = highways.get(peer.crew);
            if (!highway.accepts(text(message,"job"))) throw new IllegalArgumentException("End the previous native crew job before switching hosts");
            highway.observe(c,message);
            peer.observation = observation;
            peer.diagnostics = message.has("diagnostics") ? message.getAsJsonObject("diagnostics").deepCopy() : null;
            return;
        }
        Peer peer = peers.get(c); if (peer == null) throw new IllegalArgumentException("Announce worker identity first");
        if (type.equals("task-worker")) {
            String current = text(message, "current"); if (!current.isEmpty()) UUID.fromString(current);
            JsonArray states = message.getAsJsonArray("runs");
            if (states == null || states.size() > 64) throw new IllegalArgumentException("Invalid worker checkpoint list");
            Set<String> present = new HashSet<>();
            for (JsonElement value : states) {
                JsonObject report = value.getAsJsonObject();
                if (!present.add(text(report, "run"))) throw new IllegalArgumentException("Duplicate worker checkpoint");
                update(peer, report, false);
            }
            peer.current = current;
            for (JsonObject task : tasks.values()) if (task.getAsJsonObject("runs").has(peer.id.toString()) && text(task, "crew").equals(peer.crew)) {
                JsonObject run = run(task, peer.id); UUID id = UUID.fromString(text(run, "id"));
                if (!present.contains(id.toString()) && (peer.transfer == null || !peer.transfer.run.equals(id)) && !peer.transferred.contains(id)) QueuePolicy.reconcileMissingRun(task, run);
            }
            persist(); peer.seen = System.nanoTime(); peer.reconciled = true;
        } else if (type.equals("task-status")) { update(peer, message, true); persist(); }
        else highways.get(peer.crew).receive(c,message);
    }
    private void update(Peer peer, JsonObject message, boolean full) {
        UUID.fromString(text(message, "run"));
        for (JsonObject task : tasks.values()) if (task.getAsJsonObject("runs").has(peer.id.toString())) {
            JsonObject run = run(task, peer.id); if (!text(run, "id").equals(text(message, "run"))) continue;
            if (!text(task, "crew").equals(peer.crew)) throw new IllegalArgumentException("Report belongs to another crew");
            if(full && message.has("action")) {
                JsonObject action=message.getAsJsonObject("action");String type=text(action,"type");
                if(!ACTIONS.contains(type))throw new IllegalArgumentException("Unsupported native action");
                if(type.equals("Highway")) {
                    JsonObject packaged=task.getAsJsonObject("package");
                    if(!packaged.has("geometry") || !packaged.getAsJsonObject("highways").has(text(action,"workflow"))) throw new IllegalArgumentException("Unknown Highway preset");
                    if(action.has("length"))integer(action,"length",16,4096);
                    for(String axis:List.of("x","y","z"))if(action.has(axis))integer(action,axis,axis.equals("y")?-2048:-29_900_000,axis.equals("y")?2048:29_900_000);
                }
            }
            if (!TaskWire.applyStatus(task, run, message, full)) return;
            String status = text(run, "status");
            if (Set.of("Running", "Suspending").contains(status)) peer.current = text(run, "id");
            else if (peer.current.equals(text(run, "id"))) peer.current = "";
            return;
        }
        // Unknown saved executions are not adopted or replayed. Their current ID still blocks new work.
    }

    private void schedule(SwarmConnection c, Peer peer) {
        List<JsonObject> eligible = tasks.values().stream().filter(task -> text(task, "crew").equals(peer.crew)).toList();
        for (JsonObject task : eligible) if (flag(task, "cancelled") && task.getAsJsonObject("runs").has(peer.id.toString())) {
            JsonObject run = run(task, peer.id);
            if (QueuePolicy.terminal(text(run, "status"))) continue;
            if (text(run, "status").equals("Queued")) { run.addProperty("status", "Cancelled"); persist(); }
            else {
                if (text(run, "status").equals("Sending")) { transfer(c, peer, task, run); if (peer.transfer != null) continue; }
                command(c, run, QueuePolicy.cancellationCommand(run));
            }
        }
        JsonObject active = eligible.stream().filter(task -> task.getAsJsonObject("runs").has(peer.id.toString()) && text(run(task, peer.id), "id").equals(peer.current)).findFirst().orElse(null);
        if (active == null && !peer.current.isEmpty()) return; // Foreign/unknown work requires manual cleanup on the worker.
        QueuePolicy.Dispatch dispatch = QueuePolicy.dispatch(active, QueuePolicy.choose(eligible, peer.id), peer.id);
        if (dispatch.task() == null) return;
        JsonObject run = run(dispatch.task(), peer.id);
        switch (dispatch.command()) {
            case "transfer" -> transfer(c, peer, dispatch.task(), run);
            case "resume", "pause", "cancel" -> {
                // A workflow may preempt one actor, but its unfinished lane cannot be silently abandoned.
                // Keep the native execution intact until every targeted actor returns to its Highway step.
                if (dispatch.command().equals("pause") && active != null && active.has("nativeDefinition")) {
                    HighwayHost highway=highways.get(peer.crew);
                    if (ownsHighway(active,highway) && highway.assigned() && !highway.paused()) highway.pause();
                }
                command(c, run, dispatch.command());
            }
            default -> { } // Supported actions execute entirely in the worker's existing Lua/native runtime.
        }
    }
    private void transfer(SwarmConnection c, Peer peer, JsonObject task, JsonObject run) {
        UUID id = UUID.fromString(text(run, "id"));
        if (peer.transfer != null || peer.transferred.contains(id)) return;
        JsonObject envelope = TaskWire.envelope(task, peer.id);
        String data = Base64.getEncoder().encodeToString(TaskFiles.jsonBytes(envelope, TaskFiles.MAX_PACKAGE));
        run.addProperty("status", "Sending"); run.addProperty("detail", "Sending immutable workflow and profiles"); persist();
        if (c.send(TaskWire.begin(id, data).toString())) peer.transfer = new Transfer(id, data);
    }
    private void flush(SwarmConnection c, Peer peer) {
        Transfer transfer = peer.transfer; if (transfer == null) return;
        int count = (transfer.data.length() + TaskFiles.CHUNK - 1) / TaskFiles.CHUNK;
        for (int sent = 0; sent < 4 && transfer.next < count; sent++, transfer.next++)
            if (!c.send(TaskWire.chunk(transfer.run, transfer.data, transfer.next).toString())) return;
        if (transfer.next == count) { peer.transferred.add(transfer.run); peer.transfer = null; }
    }
    private void command(SwarmConnection c, JsonObject run, String command) {
        UUID id = UUID.fromString(text(run, "id")); long now = System.nanoTime(); Long previous = commands.get(id);
        if (previous != null && now - previous < 1_000_000_000L) return;
        if (command.equals("resume") && !flag(run, "resumeSent")) { run.addProperty("resumeSent", true); persist(); }
        if (c.send(TaskWire.control(id, command).toString())) commands.put(id, now);
    }

    private Peer peer(String crew, UUID id) {
        return peers.entrySet().stream().filter(e -> e.getKey().connected() && e.getValue().crew.equals(crew) && e.getValue().id.equals(id))
            .map(Map.Entry::getValue).findFirst().orElse(null);
    }
    private static boolean highwayAction(JsonObject run) { return run.has("action") && text(run.getAsJsonObject("action"),"type").equals("Highway"); }
    private boolean nativeReady(String crew, UUID id) {
        Peer peer=peer(crew,id);
        if(peer==null || !peer.reconciled || System.nanoTime()-peer.seen>=5_000_000_000L) return false;
        return tasks.values().stream().filter(t -> !flag(t,"cancelled") && !flag(t,"paused") && !QueuePolicy.terminal(text(t,"status")) && text(t,"crew").equals(crew) && t.getAsJsonObject("runs").has(id.toString()))
            .anyMatch(t -> { JsonObject r=run(t,id); return text(r,"id").equals(peer.current) && text(r,"status").equals("Running") && highwayAction(r); });
    }
    private static boolean ownsHighway(JsonObject task, HighwayHost highway) {
        JsonObject snapshot=highway.snapshot();
        return task.has("nativeDefinition") && snapshot!=null && text(task.getAsJsonObject("nativeDefinition"),"id").equals(text(snapshot,"catalogId"));
    }
    private void highwayCheckpoint(JsonObject snapshot) {
        for(JsonObject task:tasks.values()) if(task.has("nativeDefinition") && text(task.getAsJsonObject("nativeDefinition"),"id").equals(text(snapshot,"catalogId"))) {
            task.addProperty("highwayProgress",integer(snapshot,"progress",0,4096));
            if(flag(snapshot,"roadComplete")) task.addProperty("highwayCompleted",true);
            persist(); return;
        }
    }
    private void coordinateHighways() {
        for(JsonObject task:tasks.values()) {
            String crew=text(task,"crew"); HighwayHost highway=highways.get(crew);
            List<UUID> workers=task.getAsJsonObject("runs").keySet().stream().map(UUID::fromString).toList();
            if(task.has("nativeDefinition")) {
                boolean failed=workers.stream().anyMatch(id -> text(run(task,id),"status").equals("Failed")
                    || Set.of("Failed", "Cancelled").contains(text(run(task,id),"requestedStatus")));
                if(ownsHighway(task,highway)) {
                    if(flag(task,"cancelled")) highway.endJob();
                    else if(failed) { if(highway.assigned()) highway.releaseJob(); else highway.endJob(); }
                    else if(highway.assigned()) {
                        boolean ready=workers.stream().allMatch(id -> nativeReady(crew,id) && peer(crew,id).current.equals(text(run(task,id),"id")));
                        if(flag(task,"paused") || !ready) { if(!highway.paused() && !highway.isReleasing()) highway.pause(); }
                        else if(!highway.isReleasing() && (highway.paused() || flag(task,"nativeResumeRequested"))) {
                            if (highway.tryResume()) { task.remove("nativeResumeRequested"); persist(); }
                        }
                    }
                }
                if(flag(task,"cancelled") && !ownsHighway(task,highway)) {
                    task.addProperty("historyJobId",text(task.getAsJsonObject("nativeDefinition"),"id"));
                    task.remove("nativeDefinition"); task.remove("highwayTokens"); task.remove("highwayCompleted"); persist();
                    continue; // END delivery and workflow cancellation retry independently of this crew's next job.
                }
                if(!highway.assigned() && highway.recoveryRecord()==null && !highway.hasPendingEnds(workers)) {
                    boolean waiting=false;
                    JsonObject tokens=task.getAsJsonObject("highwayTokens");
                    for(UUID id:workers) {
                        JsonObject r=run(task,id);
                        if(tokens!=null && highwayAction(r) && text(tokens,id.toString()).equals(text(r,"token")) && !QueuePolicy.terminal(text(r,"status"))) {
                            waiting=true; SwarmConnection c=highway.connectionForWorker(id); if(c==null)continue;
                            JsonObject result=TaskWire.message("result"); result.addProperty("run",text(r,"id"));result.addProperty("token",text(r,"token"));
                            result.addProperty("success",flag(task,"highwayCompleted") && !flag(task,"cancelled") && !failed);
                            result.addProperty("detail",flag(task,"cancelled")?"Highway cancelled":flag(task,"highwayCompleted")?"Highway complete":"Highway ended before verified completion; inspect its checkpoint");
                            c.send(result.toString());
                        }
                    }
                    if(!waiting) { task.remove("nativeDefinition");task.remove("highwayTokens");task.remove("highwayCompleted");persist(); }
                }
                continue;
            }
            if(QueuePolicy.terminal(text(task,"status")) || flag(task,"cancelled") || flag(task,"paused")) continue;
            if(workers.stream().noneMatch(id -> highwayAction(run(task,id)))) continue;
            if(highway.assigned() || highway.recoveryRecord()!=null) { task.addProperty("detail","Waiting for this crew's highway execution/cleanup"); continue; }
            if(workers.stream().anyMatch(id -> !nativeReady(crew,id) || !highway.recoveryReady(id) || !peer(crew,id).current.equals(text(run(task,id),"id")))) {
                task.addProperty("detail","Waiting for all targeted workers to reach the Highway step");continue;
            }
            try {
                JsonObject action=run(task,workers.getFirst()).getAsJsonObject("action");
                for(UUID id:workers) if(!action.equals(run(task,id).getAsJsonObject("action"))) throw new IllegalArgumentException("Every worker must use identical Highway arguments");
                JsonObject definition=HighwayJobs.definition(task,action);
                if(!text(definition,"scope").equals(text(task,"server")+"\n"+text(task,"dimension"))) throw new IllegalArgumentException("Exported geometry belongs to another server/dimension");
                for(var other:highways.entrySet()) if(!other.getKey().equals(crew) && other.getValue().snapshot()!=null && HighwayJobs.overlaps(definition,other.getValue().snapshot()))
                    throw new IllegalStateException("Work/supply area overlaps crew "+other.getKey());
                HighwayCoordinator.applyWorkflowDuties(definition,Set.copyOf(workers));
                task.add("nativeDefinition",definition);JsonObject tokens=new JsonObject();for(UUID id:workers)tokens.addProperty(id.toString(),text(run(task,id),"token"));task.add("highwayTokens",tokens);
                persist(); // Native intent and runtime tokens precede PREPARE, including host-crash recovery.
                highway.start(definition,new LinkedHashSet<>(workers));task.addProperty("detail","Coordinating native highway");persist();
            } catch(IllegalArgumentException e) {
                task.addProperty("paused",true);task.addProperty("detail","Highway needs inspection: "+e.getMessage());persist();
            } catch(IllegalStateException e) {
                if(e.getMessage()!=null && e.getMessage().startsWith("Task checkpoint failed")) throw e;
                task.addProperty("detail","Highway start deferred: "+e.getMessage());
                if(!highway.assigned() && highway.recoveryRecord()==null) {task.remove("nativeDefinition");task.remove("highwayTokens");} persist();
            }
        }
    }

    /** Serialized local control API. No arbitrary file paths, shell commands or code loading. */
    public synchronized JsonObject control(JsonObject request) {
        if (text(request, "op").equals("status")) return status();
        if (closed || !failure.isEmpty()) throw new IllegalStateException("Host is stopped; inspect status");
        String op = text(request, "op");
        if (op.equals("submit")) return submit(request);
        if (op.equals("resolve-transfers")) {
            HighwayHost highway=highways.get(text(request,"crew"));
            if(highway==null || !highway.assigned() || !text(highway.status(),"execution").equals(text(request,"execution")))
                throw new IllegalArgumentException("Inspect the current crew execution before resolving transfers");
            if(!request.has("confirmed") || !request.get("confirmed").isJsonPrimitive()
                || !request.getAsJsonPrimitive("confirmed").isBoolean() || !request.get("confirmed").getAsBoolean())
                throw new IllegalArgumentException("Confirm that the participants and dropped items have been inspected");
            highway.resolveInventoryTransfer();return highway.status();
        }
        if (op.equals("end-highway")) {
            HighwayHost highway=highways.get(text(request,"crew"));if(highway==null)throw new IllegalArgumentException("Unknown crew");
            for (JsonObject task : tasks.values()) if (ownsHighway(task,highway)) cancelTask(task);
            persist();
            highway.endJob();return highway.status();
        }
        UUID id = UUID.fromString(text(request, "id")); JsonObject task = tasks.get(id);
        if (task == null) throw new IllegalArgumentException("Unknown task");
        JsonObject previous = task.deepCopy();
        try {
            switch (op) {
                case "pause" -> QueuePolicy.pause(task);
                case "resume" -> {
                    HighwayHost highway=highways.get(text(task,"crew"));
                    if(ownsHighway(task,highway) && !highway.assigned() && highway.recoveryRecord()!=null)
                        throw new IllegalStateException("Host restart interrupted this native highway. Inspect supplies and saved progress, cancel the old execution, then submit the remaining work; it is not replayed automatically.");
                    QueuePolicy.resume(task);
                    if(ownsHighway(task,highway) && highway.assigned() && !highway.isReleasing()) task.addProperty("nativeResumeRequested",true);
                }
                case "cancel" -> cancelTask(task);
                case "priority" -> {
                    int priority = integer(request, "priority", -1000, 1000);
                    if (request.has("worker")) {
                        String worker = UUID.fromString(text(request, "worker")).toString();
                        if (!task.getAsJsonObject("runs").has(worker)) throw new IllegalArgumentException("Worker not in task");
                        task.getAsJsonObject("overrides").addProperty(worker, priority);
                    } else task.addProperty("priority", priority);
                }
                case "delete" -> {
                    if (!BotHistory.taskFinished(task, task.has("nativeDefinition"))) throw new IllegalArgumentException("Cancellation is final, but its delivery/recovery record must remain until workers acknowledge cleanup");
                    tasks.remove(id);
                }
                default -> throw new IllegalArgumentException("Unknown operation");
            }
            persist();
        } catch (RuntimeException e) { tasks.put(id, previous); throw e; }
        JsonObject result = new JsonObject(); result.addProperty("id", id.toString()); result.addProperty("status", op.equals("delete") ? "Deleted" : text(task, "status")); return result;
    }

    private void cancelTask(JsonObject task) {
        QueuePolicy.cancel(task);
        for (JsonElement value : task.getAsJsonObject("runs").asMap().values()) commands.remove(UUID.fromString(text(value.getAsJsonObject(), "id")));
    }

    private JsonObject submit(JsonObject request) {
        UUID id = UUID.fromString(text(request, "id"));
        String digest = TaskFiles.hash(request.toString());
        if (tasks.containsKey(id)) {
            if (!text(tasks.get(id), "requestHash").equals(digest)) throw new IllegalArgumentException("Task ID already used with different arguments");
            JsonObject result = new JsonObject(); result.addProperty("id", id.toString()); result.addProperty("status", text(tasks.get(id), "status")); return result;
        }
        if (tasks.size() >= 64) throw new IllegalArgumentException("Delete finished history before exceeding 64 tasks");
        String crew = text(request, "crew"); if (!crews.containsKey(crew)) throw new IllegalArgumentException("Unknown crew");
        String name = name(text(request, "name")), server = text(request, "server"), dimension = text(request, "dimension");
        if (server.isBlank() || server.length() > 1024 || server.chars().anyMatch(Character::isISOControl) || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Specify Minecraft server and dimension");
        JsonArray workers = request.getAsJsonArray("workers"); if (workers == null || workers.isEmpty() || workers.size() > 16) throw new IllegalArgumentException("Target 1–16 workers");
        int priority = request.has("priority") ? integer(request, "priority", -1000, 1000) : 0;
        JsonObject args = request.has("args") ? request.getAsJsonObject("args").deepCopy() : new JsonObject();
        if (args.toString().length() > BotLua.MAX_STATE) throw new IllegalArgumentException("Arguments too large");
        JsonObject packaged;
        if (request.has("package")) packaged = checkedPackage(request.getAsJsonObject("package"));
        else {
            String script = text(request, "script"); BotLua.validate(script);
            packaged = new JsonObject(); packaged.addProperty("version", 1); packaged.addProperty("entry", "main");
            JsonObject programs = new JsonObject(), program = new JsonObject(), profiles = new JsonObject();
            program.addProperty("name", name); program.addProperty("script", script); programs.add("main", program); profiles.add("Current", new JsonObject());
            packaged.add("programs", programs); packaged.add("profiles", profiles); packaged.add("highways", new JsonObject());
        }
        JsonObject task = new JsonObject(), runs = new JsonObject();
        for (JsonElement value : workers) {
            UUID worker = UUID.fromString(value.getAsString()); if (runs.has(worker.toString())) throw new IllegalArgumentException("Duplicate worker");
            boolean present = peers.entrySet().stream().anyMatch(e -> e.getKey().connected() && e.getValue().id.equals(worker) && e.getValue().crew.equals(crew) && e.getValue().reconciled);
            if (!present) throw new IllegalArgumentException("Every worker must be connected and reconciled in this crew");
            JsonObject run = new JsonObject(); run.addProperty("id", UUID.randomUUID().toString()); run.addProperty("status", "Queued"); runs.add(worker.toString(), run);
        }
        task.addProperty("id", id.toString()); task.addProperty("requestHash", digest); task.addProperty("name", name); task.addProperty("workflowName", name); task.addProperty("crew", crew);
        task.addProperty("server", server); task.addProperty("dimension", dimension); task.addProperty("priority", priority); task.addProperty("status", "Queued");
        task.add("runs", runs); task.add("overrides", new JsonObject()); task.add("args", args); task.add("package", packaged); task.add("supportedActions", new Gson().toJsonTree(ACTIONS));
        validateTask(id, task);
        for (String worker : runs.keySet()) TaskFiles.jsonBytes(TaskWire.envelope(task, UUID.fromString(worker)), TaskFiles.MAX_PACKAGE);
        tasks.put(id, task); try { persist(); } catch (RuntimeException e) { tasks.remove(id); throw e; }
        JsonObject result = new JsonObject(); result.addProperty("id", id.toString()); result.addProperty("status", "Queued"); return result;
    }
    private JsonObject status() {
        JsonObject result = new JsonObject(); result.addProperty("status", failure.isEmpty() ? closed ? "Stopped" : "Running" : failure);
        result.addProperty("lastConnectionError", lastConnectionError);
        result.addProperty("workerPort", port()); result.add("capabilities", new Gson().toJsonTree(ACTIONS)); result.add("crews", new Gson().toJsonTree(crews.keySet()));
        JsonArray workers = new JsonArray(), active = new JsonArray(), history = new JsonArray();
        for (var entry : peers.entrySet()) {
            Peer peer = entry.getValue(); JsonObject worker = new JsonObject(); worker.addProperty("id", peer.id.toString()); worker.addProperty("name", peer.observation.name());
            worker.addProperty("crew", peer.crew); worker.addProperty("scope", peer.observation.scope()); worker.addProperty("connected", entry.getKey().connected());
            worker.addProperty("reconciled", peer.reconciled); worker.addProperty("current", peer.current); worker.addProperty("positionFresh", peer.observation.fresh(System.nanoTime())); workers.add(worker);
            worker.addProperty("observationAgeMs", Math.max(0, (System.nanoTime() - peer.observation.receivedAt()) / 1_000_000));
            if (peer.diagnostics != null) worker.add("diagnostics", peer.diagnostics.deepCopy());
        }
        for (JsonObject task : tasks.values()) {
            JsonObject view = task.deepCopy(); view.remove("package"); view.remove("requestHash"); view.remove("args");
            view.addProperty("cleanupPending", QueuePolicy.terminal(text(task,"status")) && !BotHistory.taskFinished(task, task.has("nativeDefinition")));
            (QueuePolicy.terminal(text(task, "status")) ? history : active).add(view);
        }
        JsonObject nativeHighways=new JsonObject();highways.forEach((crew,highway)->nativeHighways.add(crew,highway.status()));result.add("highways",nativeHighways);
        result.add("workers", workers); result.add("tasks", active); result.add("history", history); return result;
    }
    static JsonObject checkedPackage(JsonObject input) {
        TaskFiles.jsonBytes(input, TaskFiles.MAX_PACKAGE); integer(input, "version", 1, 1);
        if (input.has("dispatch")) throw new IllegalArgumentException("Import a workflow package, not a dispatched job");
        JsonObject programs = input.getAsJsonObject("programs"), profiles = input.getAsJsonObject("profiles"), highways = input.getAsJsonObject("highways");
        if (programs == null || programs.isEmpty() || programs.size() > 32 || profiles == null || profiles.size() > 17 || !profiles.has("Current") || highways == null || highways.size()>32) throw new IllegalArgumentException("Incomplete workflow package");
        for(var entry:highways.entrySet()) {
            if(!programs.has(entry.getKey()))throw new IllegalArgumentException("Highway preset has no bundled program");
            dev.monocle.client.systems.bots.BotWorkflows.checkedPlan(entry.getValue().getAsJsonObject());
        }
        if(!highways.isEmpty()) {
            if(!input.has("geometry"))throw new IllegalArgumentException("Export the highway workflow from a client in the target world to capture its geometry and profile");
            JsonObject geometry=input.getAsJsonObject("geometry").deepCopy();
            geometry.addProperty("id",UUID.randomUUID().toString());geometry.addProperty("name","Exported highway");geometry.addProperty("length",128);geometry.addProperty("progress",0);
            HighwayJobs.checked(geometry);
        }
        for (var entry : programs.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid workflow ID");
            name(text(entry.getValue().getAsJsonObject(), "name")); BotLua.validate(text(entry.getValue().getAsJsonObject(), "script"));
        }
        if (!programs.has(text(input, "entry"))) throw new IllegalArgumentException("Missing workflow entry");
        for (var entry : profiles.entrySet()) {
            if (entry.getKey().isBlank() || entry.getKey().length() > 128 || entry.getKey().contains("/") || entry.getKey().contains("\\") || entry.getKey().chars().anyMatch(Character::isISOControl) || !entry.getValue().isJsonObject()) throw new IllegalArgumentException("Invalid profile");
            if (entry.getValue().getAsJsonObject().size() > 512 || TaskFiles.jsonBytes(entry.getValue().getAsJsonObject(), 524_288).length > 524_288) throw new IllegalArgumentException("Profile too large");
        }
        return input.deepCopy(); // Worker performs full native module/SNBT validation before installation/activation.
    }
    private void validateTask(UUID id, JsonObject task) {
        if (!id.toString().equals(text(task, "id")) || !crews.containsKey(text(task, "crew"))) throw new IllegalArgumentException("Invalid saved task identity/crew");
        name(text(task, "name")); checkedPackage(task.getAsJsonObject("package")); integer(task, "priority", -1000, 1000);
        if (!text(task, "requestHash").matches("[a-f0-9]{64}") || text(task, "server").isBlank() || text(task, "server").length() > 1024 || text(task, "server").chars().anyMatch(Character::isISOControl)
            || !text(task, "dimension").matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || !task.get("args").isJsonObject() || task.get("args").toString().length() > BotLua.MAX_STATE)
            throw new IllegalArgumentException("Invalid saved task scope/arguments");
        if (!Set.of("Queued", "Paused", "Cancelling", "Running", "Suspended", "Inspection required", "Complete", "Failed", "Cancelled").contains(text(task, "status"))) throw new IllegalArgumentException("Invalid saved task state");
        for (String key : List.of("paused", "cancelled")) if (task.has(key) && (!task.get(key).isJsonPrimitive() || !task.getAsJsonPrimitive(key).isBoolean())) throw new IllegalArgumentException("Invalid saved control flag");
        JsonObject runs = task.getAsJsonObject("runs"); if (runs == null || runs.isEmpty() || runs.size() > 16) throw new IllegalArgumentException("Invalid saved targets");
        Set<UUID> executions = new HashSet<>();
        for (var entry : runs.entrySet()) {
            UUID.fromString(entry.getKey()); JsonObject run = entry.getValue().getAsJsonObject();
            if (!executions.add(UUID.fromString(text(run, "id"))) || !Set.of("Queued", "Sending", "Ready", "Running", "Suspending", "Suspended", "Inspection required", "Complete", "Failed", "Cancelled").contains(text(run, "status"))) throw new IllegalArgumentException("Invalid saved execution");
            for (String key : List.of("resumeSent", "resumeInspection")) if (run.has(key) && (!run.get(key).isJsonPrimitive() || !run.getAsJsonPrimitive(key).isBoolean())) throw new IllegalArgumentException("Invalid saved execution flag");
        }
        for (var entry : task.getAsJsonObject("overrides").entrySet()) {
            if (!runs.has(entry.getKey())) throw new IllegalArgumentException("Invalid saved priority worker"); integer(task.getAsJsonObject("overrides"), entry.getKey(), -1000, 1000);
        }
        JsonArray capabilities=task.getAsJsonArray("supportedActions");
        if(capabilities==null || capabilities.isEmpty() || capabilities.size()>ACTIONS.size() || capabilities.asList().stream().anyMatch(v->!ACTIONS.contains(v.getAsString()))) throw new IllegalArgumentException("Invalid saved host capabilities");
    }
    static String name(String name) {
        if (name.isBlank() || name.length() > 64 || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Name must be 1–64 printable characters"); return name;
    }
    static int integer(JsonObject object, String key, int min, int max) {
        try {
            JsonPrimitive p = object.getAsJsonPrimitive(key); if (p == null || !p.isNumber()) throw new IllegalArgumentException("Missing integer " + key);
            int value = p.getAsBigDecimal().intValueExact(); if (value < min || value > max) throw new IllegalArgumentException("Invalid " + key); return value;
        } catch (ArithmeticException e) { throw new IllegalArgumentException("Invalid " + key, e); }
    }
    private static JsonObject run(JsonObject task, UUID worker) { return task.getAsJsonObject("runs").getAsJsonObject(worker.toString()); }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        listener.close(); ticker.shutdownNow();
        try { lock.release(); lockChannel.close(); } catch (IOException e) { throw new IllegalStateException("Cannot release host lock", e); }
    }
}
