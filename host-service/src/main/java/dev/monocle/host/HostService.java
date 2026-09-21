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
    public static final List<String> ACTIONS = List.of("Wait", "Travel", "DropItems", "Modules", "Tpa", "SetProfile", "Highway", "RecoverSupplies", "StashScan", "StashResupply");
    private final Path journal;
    private final Path directory;
    private final OperationsLibrary library;
    private final Map<String, String> crewLabels = new LinkedHashMap<>();
    private final Map<UUID, JsonObject> roster = new LinkedHashMap<>();
    private final Deque<JsonObject> activity = new ArrayDeque<>();
    private final BotChat chat = new BotChat();
    private final Map<UUID, Long> chatAt = new HashMap<>();
    private final Map<UUID, AutoTpy> autoTpyRequests = new LinkedHashMap<>();
    private final CrewTelemetry events;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final CrewListener listener;
    private final Map<String, String> crews;
    private final Map<String, String> selectors = new ConcurrentHashMap<>();
    private final Map<UUID, JsonObject> tasks = new LinkedHashMap<>();
    private final Map<SwarmConnection, Peer> peers = new HashMap<>();
    private final Map<UUID, Long> commands = new HashMap<>();
    private final Map<String, HighwayHost> highways = new LinkedHashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("Monocle coordinator").factory());
    private String failure = "";
    private String rosterError = "";
    private String lastConnectionError = "";
    private boolean closed;
    private long heartbeatAt;
    private long historyAt;
    private long rosterAt;
    private int historyDays;
    private boolean autoTpy;
    private record AutoTpy(UUID requester, UUID target, String requesterName, String crew, String scope, long due, long expires) { }
    private static final class Peer {
        boolean chatSupported;
        boolean stashCatalogSupported;
        UUID id;
        String crew, current = "";
        PlayerObservation observation;
        JsonObject diagnostics;
        boolean reconciled;
        long seen;
        long stateLogAt;
        Transfer transfer;
        final Set<UUID> transferred = new HashSet<>();
    }
    private static final class Transfer {
        final UUID run; final String data; int next;
        Transfer(UUID run, String data) { this.run = run; this.data = data; }
    }

    public HostService(Path directory, String bind, int port, Map<String, String> crews, int historyDays) throws IOException {
        this(directory, bind, port, crews, historyDays, -1);
    }
    public HostService(Path directory, String bind, int port, Map<String, String> crews, int historyDays, int webPort) throws IOException {
        if (historyDays < -1 || historyDays > 3650) throw new IllegalArgumentException("historyDays must be -1 (keep) through 3650");
        this.historyDays = historyDays;
        this.directory = directory;
        this.crews = new ConcurrentHashMap<>(crews);
        if (crews.isEmpty() || crews.size() > 16) throw new IllegalArgumentException("Configure 1–16 crews");
        crews.forEach((name, key) -> {
            name(name); if (key.length() < 24 || key.length() > 128) throw new IllegalArgumentException("Crew keys must be 24–128 characters");
            if (selectors.put(SwarmConnection.credentialSelector(key), name) != null) throw new IllegalArgumentException("Every crew needs a distinct key");
        });
        Files.createDirectories(directory);
        journal = directory.resolve("host-tasks.json");
        events = new CrewTelemetry(directory.resolve("host-events.telemetry.jsonl"));
        lockChannel = FileChannel.open(directory.resolve("host.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        CrewListener opened = null;
        try {
            lock = lockChannel.tryLock();
            if (lock == null) throw new IllegalStateException("Another host owns this data directory");
            library = new OperationsLibrary(directory.resolve("bot-operations.json"));
            JsonObject config = TaskFiles.read(directory.resolve("host-config.json"));
            autoTpy = config.has("autoTpy") && config.get("autoTpy").isJsonPrimitive() && config.getAsJsonPrimitive("autoTpy").isBoolean() && config.get("autoTpy").getAsBoolean();
            if (config.has("crewLabels")) config.getAsJsonObject("crewLabels").entrySet().forEach(e -> {
                if (this.crews.containsKey(e.getKey())) crewLabels.put(e.getKey(), WorkflowPackages.label(e.getValue().getAsString()));
            });
            JsonObject savedRoster = TaskFiles.read(directory.resolve("worker-roster.json"));
            if (savedRoster.size() > 256) throw new IllegalArgumentException("Worker roster is too large");
            savedRoster.entrySet().forEach(e -> { JsonObject r=e.getValue().getAsJsonObject();r.addProperty("connected",false);r.addProperty("positionFresh",false);r.addProperty("reconciled",false);roster.put(UUID.fromString(e.getKey()),r); });
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
            listener = opened = new CrewListener(bind, port, selector -> { String crew = selectors.get(selector); return crew == null ? null : this.crews.get(crew); }, webPort);
            for (String crew : crews.keySet()) highways.put(crew, new HighwayHost(directory, crew, id -> nativeReady(crew, id), this::highwayCheckpoint));
        } catch (IOException | RuntimeException e) { if(opened!=null)opened.close();lockChannel.close(); ticker.shutdownNow(); throw e; }
        ticker.scheduleWithFixedDelay(this::tick, 0, 50, TimeUnit.MILLISECONDS);
    }

    public int port() { return listener.port(); }
    public int webPort() { return listener.webPort(); }
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
                        Peer rejected = peers.get(c);
                        if (rejected != null) logEvent("worker-message-rejected", rejected.id.toString(), "", e.getClass().getSimpleName());
                        c.disconnect(); peers.remove(c);
                        lastConnectionError = "Rejected worker message: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " · " + e.getMessage());
                        if (lastConnectionError.length() > 1024) lastConnectionError = lastConnectionError.substring(0, 1024);
                        System.err.println(lastConnectionError); break;
                    }
                }
            }
            long now = System.nanoTime();
            if (now-rosterAt >= 20_000_000_000L) {
                checkpointRoster();rosterAt=now;
            }
            if (now - heartbeatAt >= 1_000_000_000L) {
                JsonObject heartbeat=new JsonObject();heartbeat.addProperty("type","heartbeat");heartbeat.addProperty("autoTpy",autoTpy);heartbeat.add("crews",crewDiscovery());
                for (SwarmConnection c : listener.connections()) if (c != null && c.connected()) c.send(heartbeat.toString());
                heartbeatAt = now;
            }
            tickAutoTpy();
            for (var entry : List.copyOf(peers.entrySet())) {
                SwarmConnection c = entry.getKey(); Peer peer = entry.getValue();
                if (!c.connected() || !peer.reconciled || now - peer.seen >= 5_000_000_000L) continue;
                schedule(c, peer);
                flush(c, peer);
            }
            coordinateHighways();
            for (var entry:highways.entrySet()){entry.getValue().tick();dispatchStashResupply(entry.getKey(),entry.getValue());}
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
            logEvent("coordinator-failed", "", "", e.getClass().getSimpleName());
            failure = "Coordinator stopped: " + e.getClass().getSimpleName() + ". Inspect the journal/log before restarting.";
            System.err.println(failure); e.printStackTrace(System.err);
            listener.close(); // Worker connection-loss cleanup is safer than continuing after a failed durable intent.
        }
    }
    private void dispatchStashResupply(String crew,HighwayHost highway){
        JsonObject activeResupply=tasks.values().stream().filter(t->text(t,"crew").equals(crew)&&text(t,"workflowName").equals("Resupply from stash")&&!QueuePolicy.terminal(text(t,"status"))).findFirst().orElse(null);if(activeResupply!=null){highway.clearStashRequests(activeResupply.getAsJsonObject("runs").keySet());return;}
        JsonObject request=highway.pollStashRequest();if(request==null)return;UUID worker=UUID.fromString(text(request,"worker"));Peer peer=peers.values().stream().filter(p->p.id.equals(worker)&&p.observation!=null).findFirst().orElse(null);JsonObject report=highway.workerReport(worker);
        if(peer==null||report==null||!report.has("inventory")){highway.failStashRequest(request);return;}
        String scope=text(request,"scope");int split=scope.indexOf('\n'),resource=integer(request,"resource",0,4);if(split<1){highway.failStashRequest(request);return;}JsonObject highwayState=highway.snapshot();UUID anchor=highwayState.getAsJsonArray("activeMembers").asList().stream().map(v->UUID.fromString(v.getAsString())).filter(id->!id.equals(worker)).findFirst().orElse(null);if(anchor==null){highway.failStashRequest(request);return;}
        JsonObject chosen=null,args=null;
        for(JsonElement value:StashCatalog.list(directory)){JsonObject summary=value.getAsJsonObject();if(!text(summary,"crew").equals(crew)||!text(summary,"scope").equals(scope))continue;JsonObject db=StashCatalog.get(directory,crew,scope,text(summary,"name")),needs=stashNeeds(db,report.getAsJsonObject("inventory"),resource,highwayState);if(needs==null)continue;JsonObject bounds=db.getAsJsonObject("bounds").deepCopy();bounds.add("needs",needs);bounds.addProperty("primary",text(needs,"_primary"));needs.remove("_primary");bounds.addProperty("enderSlots",54);bounds.addProperty("target",anchor.toString());bounds.addProperty("warmupTicks",300);bounds.addProperty("acceptDelayTicks",10);chosen=summary;args=bounds;break;}
        if(chosen==null){highway.failStashRequest(request);return;}
        JsonObject operation=library.get("task-stash-resupply"),submit=new JsonObject();submit.addProperty("id",UUID.randomUUID().toString());submit.addProperty("crew",crew);submit.addProperty("name","Resupply from stash");submit.addProperty("server",scope.substring(0,split));submit.addProperty("dimension",scope.substring(split+1));submit.addProperty("priority",100);JsonArray workers=new JsonArray();workers.add(worker.toString());submit.add("workers",workers);submit.add("args",args);submit.add("package",operation.getAsJsonObject("package"));submit(submit);
    }
    private static JsonObject stashNeeds(JsonObject db,JsonObject inventory,int primary,JsonObject highway){
        if(db.isEmpty()||!db.has("containers"))return null;String paving=highway==null?"":text(highway.getAsJsonObject("layout"),"blocks");JsonObject needs=new JsonObject();String primaryItem="";
        for(var value:db.getAsJsonObject("containers").entrySet())for(JsonElement element:value.getValue().getAsJsonObject().getAsJsonArray("shulkers")){JsonObject box=element.getAsJsonObject();if(box.has("mixed")&&box.get("mixed").getAsBoolean())continue;String item=text(box,"dominant");int category=item.equals(paving)?0:item.endsWith("_pickaxe")?1:Set.of("minecraft:enchanted_golden_apple","minecraft:golden_apple").contains(item)?2:item.equals("minecraft:netherrack")?3:item.equals("minecraft:ender_chest")?4:-1;if(category<0)continue;int missing=Math.max(0,ResourceLedger.value(inventory,"target",category)-ResourceLedger.available(inventory,category)),boxCount=box.getAsJsonObject("items").has(item)?box.getAsJsonObject("items").get(item).getAsInt():0;if(missing>=boxCount&&boxCount>0)needs.addProperty(item,Math.max(needs.has(item)?needs.get(item).getAsInt():0,missing));if(category==primary&&boxCount>0)primaryItem=item;}
        if(primaryItem.isEmpty())return null;if(!needs.has(primaryItem))needs.addProperty(primaryItem,1);needs.addProperty("_primary",primaryItem);return needs;
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
            if (peer.observation == null) logEvent("worker-connected", peer.id.toString(), "", peer.crew);
            else if (!peer.observation.scope().equals(observation.scope())) logEvent("worker-world-changed", peer.id.toString(), peer.current, peer.observation.scope() + " -> " + observation.scope());
            peer.observation = observation;
            peer.diagnostics = message.has("diagnostics") ? message.getAsJsonObject("diagnostics").deepCopy() : null;
            peer.chatSupported = message.has("chatProtocol") && integer(message,"chatProtocol",1,1)==1;
            return;
        }
        Peer peer = peers.get(c); if (peer == null) throw new IllegalArgumentException("Announce worker identity first");
        if (type.equals("worker-chat")) {
            chat.append(peer.id,peer.crew,peer.observation.name(),peer.observation.scope(),text(message,"direction"),text(message,"text"),message.has("parts")?message.getAsJsonArray("parts"):null);
        } else if (type.equals("task-worker")) {
            peer.stashCatalogSupported=message.has("stashCatalogProtocol")&&integer(message,"stashCatalogProtocol",1,1)==1;
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
            persist(); peer.seen = System.nanoTime(); peer.reconciled = true;sendStashCatalog(c,peer);
        } else if (type.equals("task-status")) { update(peer, message, true); persist(); }
        else if(type.equals("task-stash-findings")) {
            JsonObject task=tasks.get(UUID.fromString(text(message,"task")));
            if(task==null||!task.getAsJsonObject("runs").has(peer.id.toString()))throw new IllegalArgumentException("Unknown stash task");
            JsonObject ack=StashCatalog.accept(directory,task,run(task,peer.id),peer.id.toString(),peer.crew,message);
            if(ack!=null){persist();c.send(ack.toString());logEvent("stash-container-observed",peer.id.toString(),text(task,"id"),text(message.getAsJsonObject("observation"),"status"));}
        }
        else if(type.equals("task-stash-definition")) {
            String scope=text(message,"scope");if(scope.length()>384||!scope.contains("\n")||peer.observation==null||!scope.equals(peer.observation.scope()))throw new IllegalArgumentException("Stash definition must match the worker's current world");
            JsonObject saved=StashCatalog.define(directory,peer.crew,scope,message.getAsJsonObject("stash"),peer.id.toString());persist();sendStashCatalog(c,peer);
            logEvent("stash-defined",peer.id.toString(),"",text(saved,"name"));
        }
        else if(type.equals("task-stash-import")) {
            String scope=text(message,"scope");if(scope.length()>384||!scope.contains("\n")||peer.observation==null||!scope.equals(peer.observation.scope()))throw new IllegalArgumentException("Stash import must match the worker's current world");
            JsonObject saved=StashCatalog.save(directory,peer.crew,scope,message.getAsJsonObject("stash"),message.getAsJsonObject("observation"));persist();
            logEvent("stash-container-imported",peer.id.toString(),"",text(saved,"name"));
        }
        else if(type.equals("worker-tpa-request")) requestAutoTpy(peer,message);
        else if(type.equals("crew-join-request")) {
            try{requestCrewJoin(c,peer,message);}
            catch(RuntimeException e){String detail=e.getMessage()==null?"Join is not currently available":e.getMessage();joinResult(c,null,false,detail.startsWith("Bring the worker")||detail.startsWith("Wait for"),detail);}
        }
        else highways.get(peer.crew).receive(c,message);
    }

    private JsonArray crewDiscovery() {
        JsonArray result=new JsonArray();
        for(String crew:crews.keySet()) {
            JsonObject row=new JsonObject(),task=activeHighwayTask(crew,null);HighwayHost highway=highways.get(crew);JsonObject state=highway.status();
            long connected=peers.entrySet().stream().filter(e->e.getKey().connected()&&e.getValue().crew.equals(crew)).count();
            row.addProperty("id",crew);row.addProperty("name",crewLabels.getOrDefault(crew,crew));row.addProperty("workers",connected);
            row.addProperty("capacity",HighwayCoordinator.MAX_CREW_MEMBERS);row.addProperty("status",text(state,"phase").isEmpty()?"idle":text(state,"phase"));
            row.addProperty("job",task==null?"":text(task,"id"));row.addProperty("jobName",task==null?"":text(task,"name"));row.addProperty("publicJoin",task!=null&&flag(task,"publicJoin"));result.add(row);
        }
        return result;
    }

    private JsonObject activeHighwayTask(String crew,UUID job) {
        return tasks.values().stream().filter(t->text(t,"crew").equals(crew)&&t.has("nativeDefinition")&&!flag(t,"cancelled")&&!QueuePolicy.terminal(text(t,"status"))
            &&(job==null||text(t,"id").equals(job.toString()))).findFirst().orElse(null);
    }

    private void requestCrewJoin(SwarmConnection connection,Peer peer,JsonObject message) {
        String target=text(message,"crew");UUID job=UUID.fromString(text(message,"job"));JsonObject task=activeHighwayTask(target,job);
        if(task==null)throw new IllegalArgumentException("That crew job is no longer joinable");
        if(!peer.crew.equals(target)) {
            if(!flag(task,"publicJoin"))throw new IllegalStateException("That job is not open for joining");
            moveWorker(connection,peer,target,job);return;
        }
        HighwayHost highway=highways.get(target);JsonObject runs=task.getAsJsonObject("runs");
        if(runs.has(peer.id.toString())) { if(pendingLateJoin(run(task,peer.id)))return;throw new IllegalStateException("This worker already belongs to the job"); }
        if(runs.size()>=Math.min(HighwayCoordinator.MAX_CREW_MEMBERS,task.getAsJsonObject("nativeDefinition").getAsJsonObject("layout").get("width").getAsInt()))throw new IllegalStateException("No spare highway lane");
        if(!peer.reconciled||!peer.current.isEmpty()||tasks.values().stream().anyMatch(t->workerJobPending(t,peer.id)))throw new IllegalStateException("Finish this worker's current task before joining");
        JsonObject joining=lateJoinRun(task);runs.add(peer.id.toString(),joining);persist();
        joinResult(connection,job,true,false,"Joining the live crew job");
        logEvent("worker-joining-job",peer.id.toString(),job.toString(),target);
    }
    private static void joinResult(SwarmConnection connection,UUID job,boolean accepted,boolean retry,String detail){JsonObject result=message("crew-join-result");result.addProperty("job",job==null?"":job.toString());result.addProperty("accepted",accepted);result.addProperty("retry",retry);result.addProperty("detail",detail);connection.send(result.toString());}
    private static boolean pendingLateJoin(JsonObject run){return run.has("joinJob")&&!flag(run,"joinAdmitted")&&!QueuePolicy.terminal(text(run,"status"));}
    private static JsonObject lateJoinRun(JsonObject task) {
        JsonObject reference=task.getAsJsonObject("runs").asMap().values().stream().map(JsonElement::getAsJsonObject).filter(HostService::highwayAction).findFirst()
            .orElseThrow(()->new IllegalStateException("Wait for an existing worker's current Highway action report before adding another"));
        JsonObject run=new JsonObject();run.addProperty("id",UUID.randomUUID().toString());run.addProperty("status","Queued");run.addProperty("detail","Preparing captured workflow/profile for late highway admission");
        run.addProperty("joinJob",text(task.getAsJsonObject("nativeDefinition"),"id"));run.add("joinAction",reference.getAsJsonObject("action").deepCopy());return run;
    }

    private void requestAutoTpy(Peer requester,JsonObject message) {
        if(!autoTpy)return;
        UUID request=UUID.fromString(text(message,"request"));if(autoTpyRequests.containsKey(request))return;
        if(autoTpyRequests.size()>=64)throw new IllegalStateException("Too many pending Auto TPY requests");
        String targetName=text(message,"target"),scope=text(message,"scope");
        if(!targetName.matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("Invalid TPA target");
        if(requester.observation==null||!requester.observation.scope().equals(scope))return;
        long fresh=System.nanoTime();List<Peer> matches=peers.entrySet().stream().filter(e->e.getKey().connected()).map(Map.Entry::getValue)
            .filter(p->p.observation!=null&&p.observation.fresh(fresh)&&p.crew.equals(requester.crew)&&!p.id.equals(requester.id)&&p.observation.name().equalsIgnoreCase(targetName)).toList();
        if(matches.size()!=1||!sameServer(scope,matches.getFirst().observation.scope()))return;
        long now=System.currentTimeMillis();autoTpyRequests.put(request,new AutoTpy(requester.id,matches.getFirst().id,requester.observation.name(),requester.crew,scope,now+500,now+10_000));
    }

    private void tickAutoTpy() {
        long now=System.currentTimeMillis(),fresh=System.nanoTime();
        for(var entry:List.copyOf(autoTpyRequests.entrySet())) {
            AutoTpy request=entry.getValue();if(!autoTpy||now>=request.expires()){autoTpyRequests.remove(entry.getKey());continue;}if(now<request.due())continue;
            var requester=peers.entrySet().stream().filter(e->e.getKey().connected()&&e.getValue().id.equals(request.requester())).findFirst().orElse(null);
            var target=peers.entrySet().stream().filter(e->e.getKey().connected()&&e.getValue().id.equals(request.target())).findFirst().orElse(null);
            if(requester==null||target==null||requester.getValue().observation==null||target.getValue().observation==null
                ||!requester.getValue().observation.fresh(fresh)||!target.getValue().observation.fresh(fresh)||!request.crew().equals(requester.getValue().crew)||!request.crew().equals(target.getValue().crew)
                ||!request.requesterName().equals(requester.getValue().observation.name())||!request.scope().equals(requester.getValue().observation.scope())||!sameServer(request.scope(),target.getValue().observation.scope())) {
                autoTpyRequests.remove(entry.getKey());continue;
            }
            JsonObject accept=message("tpa-accept");accept.addProperty("token",entry.getKey().toString());accept.addProperty("requester",request.requesterName());
            accept.addProperty("requesterId",request.requester().toString());accept.addProperty("target",request.target().toString());accept.addProperty("requesterScope",request.scope());
            accept.addProperty("targetScope",target.getValue().observation.scope());accept.addProperty("expires",request.expires());
            if(target.getKey().send(accept.toString())){autoTpyRequests.remove(entry.getKey());logEvent("worker-auto-tpy",request.target().toString(),"",request.requesterName());}
        }
    }
    private void sendStashCatalog(SwarmConnection c,Peer peer){if(!peer.stashCatalogSupported)return;JsonObject m=TaskWire.message("stash-catalog");JsonArray list=new JsonArray();for(JsonElement value:StashCatalog.list(directory)){JsonObject s=value.getAsJsonObject();if(text(s,"crew").equals(peer.crew))list.add(s.deepCopy());if(list.size()==64)break;}m.add("stashes",list);c.send(m.toString());}
    private void update(Peer peer, JsonObject message, boolean full) {
        UUID.fromString(text(message, "run"));
        for (JsonObject task : tasks.values()) if (task.getAsJsonObject("runs").has(peer.id.toString())) {
            JsonObject run = run(task, peer.id); if (!text(run, "id").equals(text(message, "run"))) continue;
            if (!text(task, "crew").equals(peer.crew)) throw new IllegalArgumentException("Report belongs to another crew");
            if(full && message.has("action")) {
                JsonObject action=message.getAsJsonObject("action");String type=text(action,"type");
                if(!ACTIONS.contains(type))throw new IllegalArgumentException("Unsupported native action");
                if(type.equals("StashScan"))StashCatalog.plan(action);
                if(type.equals("Highway")) {
                    JsonObject packaged=task.getAsJsonObject("package");
                    if(!packaged.has("geometry") || !packaged.getAsJsonObject("highways").has(text(action,"workflow"))) throw new IllegalArgumentException("Unknown Highway preset");
                    if(action.has("length"))integer(action,"length",16,HighwayJobs.MAX_LENGTH);
                    for(String axis:List.of("x","y","z"))if(action.has(axis))integer(action,axis,axis.equals("y")?-2048:-29_900_000,axis.equals("y")?2048:29_900_000);
                }
            }
            if(full&&message.has("stashWithdrawal")&&!text(run,"stashWithdrawalToken").equals(text(message,"token"))){JsonObject receipt=message.getAsJsonObject("stashWithdrawal");StashCatalog.invalidateWithdrawn(directory,peer.crew,text(task,"server")+"\n"+text(task,"dimension"),text(receipt,"stash"),receipt.getAsJsonArray("withdrawn"));run.addProperty("stashWithdrawalToken",text(message,"token"));}
            String previousStatus = text(run, "status"), previousDetail = text(run, "detail");
            HighwayHost highway = highways.get(text(task, "crew"));
            boolean isolateInspection = task.has("nativeDefinition") && highway != null && ownsHighway(task, highway) && highway.independentSupplies();
            if (!TaskWire.applyStatus(task, run, message, full, isolateInspection)) return;
            String status = text(run, "status");
            long now = System.nanoTime();
            if (!previousStatus.equals(status) || !previousDetail.equals(text(run, "detail")) && now - peer.stateLogAt >= 1_000_000_000L) {
                peer.stateLogAt = now;
                logEvent("worker-state", peer.id.toString(), text(run, "id"), "task=" + text(task, "id") + " " + previousStatus + " -> " + status + ": " + text(run, "detail"));
            }
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
            if (Set.of("Queued", "Sending").contains(text(run, "status")) && !text(run, "id").equals(peer.current)) {
                run.addProperty("status", "Cancelled"); run.addProperty("detail", "Cancelled before start"); peer.transfer = null; persist();
            }
            else {
                command(c, run, QueuePolicy.cancellationCommand(run));
            }
        }
        JsonObject active = eligible.stream().filter(task -> task.getAsJsonObject("runs").has(peer.id.toString()) && text(run(task, peer.id), "id").equals(peer.current)).findFirst().orElse(null);
        if (active == null && !peer.current.isEmpty()) return; // Foreign/unknown work requires manual cleanup on the worker.
        if (active != null && !flag(active, "cancelled") && !flag(active, "paused")) {
            JsonObject config = TaskWire.configurationToSend(run(active, peer.id), System.currentTimeMillis());
            if (config != null) c.send(config.toString());
        }
        QueuePolicy.Dispatch dispatch = QueuePolicy.dispatch(active, QueuePolicy.choose(eligible, peer.id), peer.id);
        if (dispatch.task() == null) return;
        JsonObject run = run(dispatch.task(), peer.id);
        switch (dispatch.command()) {
            case "transfer" -> transfer(c, peer, dispatch.task(), run);
            case "resume", "pause", "cancel" -> {
                // Independent crews redistribute a preempted worker's duties on its next report.
                if (dispatch.command().equals("pause") && active != null && active.has("nativeDefinition")) {
                    HighwayHost highway=highways.get(peer.crew);
                    if (ownsHighway(active,highway) && highway.assigned() && !highway.independentSupplies() && !highway.paused()) highway.pause();
                }
                command(c, run, dispatch.command());
            }
            default -> dispatchTpa(c, peer, dispatch.task(), run);
        }
    }

    private void dispatchTpa(SwarmConnection connection, Peer requester, JsonObject task, JsonObject run) {
        JsonObject action = run.getAsJsonObject("action");
        if (action == null || !text(action, "type").equals("Tpa")) return;
        String selector = text(action, "target");
        Peer target = null;
        long now = System.nanoTime();
        for (Peer candidate : peers.values()) {
            if (!candidate.crew.equals(requester.crew) || candidate.observation == null || !candidate.observation.fresh(now)
                || candidate.id.equals(requester.id) || !(candidate.id.toString().equals(selector) || candidate.observation.name().equalsIgnoreCase(selector))) continue;
            if (target != null) return; // Ambiguous names never select an arbitrary crewmate.
            target = candidate;
        }
        if (target == null || !sameServer(requester.observation.scope(), target.observation.scope())) return;
        if(flag(run,"commandSent")){
            long sent=run.has("tpaSentAt")?run.get("tpaSentAt").getAsLong():0,delay=(action.has("acceptDelayTicks")?integer(action,"acceptDelayTicks",0,200):10)*50L;
            if(flag(run,"tpaAccepted")||System.currentTimeMillis()-sent<delay)return;
            SwarmConnection targetConnection=null;for(var entry:peers.entrySet())if(entry.getValue()==target&&entry.getKey().connected()){targetConnection=entry.getKey();break;}if(targetConnection==null)return;
            JsonObject accept=TaskWire.message("tpa-accept");accept.addProperty("token",text(run,"token"));accept.addProperty("requester",requester.observation.name());accept.addProperty("requesterId",requester.id.toString());accept.addProperty("target",target.id.toString());accept.addProperty("requesterScope",requester.observation.scope());accept.addProperty("targetScope",target.observation.scope());accept.addProperty("expires",System.currentTimeMillis()+10_000);
            if(targetConnection.send(accept.toString())){run.addProperty("tpaAccepted",true);persist();logEvent("worker-tpa-accepted",target.id.toString(),text(run,"id"),requester.observation.name());}return;
        }
        JsonObject command = new JsonObject();
        command.addProperty("type", "task-tpa-send"); command.addProperty("run", text(run, "id")); command.addProperty("token", text(run, "token"));
        command.addProperty("name", target.observation.name()); command.addProperty("target", target.id.toString());
        command.addProperty("dimension", target.observation.scope().substring(target.observation.scope().indexOf('\n') + 1));
        if (connection.send(command.toString())) { run.addProperty("commandSent", true);run.addProperty("tpaSentAt",System.currentTimeMillis()); persist(); logEvent("worker-tpa-sent", requester.id.toString(), text(run, "id"), target.observation.name()); }
    }

    private static boolean sameServer(String first, String second) {
        int a = first.indexOf('\n'), b = second.indexOf('\n');
        return a > 0 && b > 0 && first.substring(0, a).equalsIgnoreCase(second.substring(0, b));
    }
    private void transfer(SwarmConnection c, Peer peer, JsonObject task, JsonObject run) {
        UUID id = UUID.fromString(text(run, "id"));
        if (peer.transfer != null || peer.transferred.contains(id)) return;
        JsonObject envelope = TaskWire.envelope(task, peer.id);
        JsonObject metadata=envelope.getAsJsonObject("dispatch"),args=metadata.getAsJsonObject("args");if(args.has("needs")&&args.has("primary"))metadata.add("args",StashCatalog.refillAction(directory,peer.crew,text(task,"server")+"\n"+text(task,"dimension"),args,peer.id.toString()));else if(args.has("name")&&args.has("minX"))metadata.add("args",StashCatalog.route(directory,peer.crew,text(task,"server")+"\n"+text(task,"dimension"),args,peer.id.toString()));
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
        boolean sent = c.send(TaskWire.control(id, command).toString());
        Peer peer = peers.get(c);
        logEvent(sent ? "worker-control-sent" : "worker-control-send-failed", peer == null ? "" : peer.id.toString(), id.toString(), command);
        if (sent) commands.put(id, now);
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
            task.addProperty("highwayProgress",integer(snapshot,"progress",0,HighwayJobs.MAX_LENGTH));
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
                    for(UUID id:workers) {
                        JsonObject joining=run(task,id);if(!pendingLateJoin(joining)||!nativeReady(crew,id))continue;
                        try {
                            JsonObject tokens=task.getAsJsonObject("highwayTokens");if(!tokens.has(id.toString())){tokens.addProperty(id.toString(),text(joining,"token"));persist();}
                            if(!highway.isParticipant(id))highway.addWorker(id);
                            if(highway.isParticipant(id)){joining.addProperty("joinAdmitted",true);persist();}
                        } catch(IllegalStateException e){task.addProperty("detail","Late join waiting: "+e.getMessage());}
                    }
                    if(flag(task,"cancelled")) highway.endJob();
                    else if(failed && !highway.independentSupplies()) { if(highway.assigned()) highway.releaseJob(); else highway.endJob(); }
                    else if(highway.assigned()) {
                        boolean ready=workers.stream().filter(id->!pendingLateJoin(run(task,id))).allMatch(id -> nativeReady(crew,id) && peer(crew,id).current.equals(text(run(task,id),"id")));
                        if(flag(task,"paused") || !ready && !highway.independentSupplies()) { if(!highway.paused() && !highway.isReleasing()) highway.pause(); }
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
                task.add("nativeDefinition",definition);task.add("highwayDefinition",definition.deepCopy());task.remove("preparedDefinition");JsonObject tokens=new JsonObject();for(UUID id:workers)tokens.addProperty(id.toString(),text(run(task,id),"token"));task.add("highwayTokens",tokens);
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
        logEvent("control-request", "", text(request, "id"), op);
        if (op.equals("submit")) return submit(request);
        if (op.equals("configuration-controls")) return dev.monocle.coordinator.JobSettingControls.catalog();
        if (op.equals("preview-configuration")) return dev.monocle.coordinator.JobSettingControls.preview(request);
        if (op.equals("submit-highway")) return submit(highwaySubmission(request));
        if (op.equals("stash-get")) {
            if(!crews.containsKey(text(request,"crew")))throw new IllegalArgumentException("Unknown crew");
            return StashCatalog.get(directory,text(request,"crew"),text(request,"scope"),text(request,"name"));
        }
        if (op.equals("workflow-list")) { JsonObject r=new JsonObject();r.add("workflows",library.list());return r; }
        if (op.equals("workflow-get")) return library.get(text(request,"id"));
        if (op.equals("workflow-save")) return library.save(text(request,"id"),text(request,"name"),text(request,"folder"),request.getAsJsonObject("package"));
        if (op.equals("workflow-duplicate")) { JsonObject original=library.get(text(request,"source"));return library.save(text(request,"id"),text(request,"name"),text(request,"folder"),original.getAsJsonObject("package")); }
        if (op.equals("workflow-delete")) { library.delete(text(request,"id"));return status(); }
        if (op.equals("draft-save")) return library.saveDraft(request.getAsJsonObject("draft"));
        if (op.equals("draft-get")) return library.draft(text(request,"id"));
        if (op.equals("draft-delete")) { library.deleteDraft(text(request,"id"));return status(); }
        if (op.equals("draft-assign")) {
            if(library.drafts().asList().stream().noneMatch(v->text(v.getAsJsonObject(),"id").equals(text(request,"id")))) {
                JsonObject existing=tasks.get(UUID.fromString(text(request,"id")));JsonArray targeted=request.getAsJsonArray("workers");
                if(existing==null || !text(existing,"crew").equals(text(request,"crew")) || targeted==null || targeted.size()!=existing.getAsJsonObject("runs").size()
                    || targeted.asList().stream().map(JsonElement::getAsString).distinct().count()!=targeted.size() || targeted.asList().stream().anyMatch(v->!existing.getAsJsonObject("runs").has(v.getAsString())))throw new IllegalArgumentException("Unknown job or assignment differs from the accepted request");
                JsonObject result=new JsonObject();result.addProperty("id",text(existing,"id"));result.addProperty("status",text(existing,"status"));return result;
            }
            JsonObject draft=library.draft(text(request,"id"));
            if(draft.has("sourceTask")) { JsonObject source=tasks.get(UUID.fromString(text(draft,"sourceTask")));if(source!=null && !BotHistory.taskFinished(source,source.has("nativeDefinition")))throw new IllegalStateException("The previous crew is still completing cancellation/recovery; its saved work cannot be reassigned yet"); }
            draft.addProperty("op","submit");draft.addProperty("crew",text(request,"crew"));draft.add("workers",request.getAsJsonArray("workers").deepCopy());
            JsonObject result=submit(draft);library.deleteDraft(text(request,"id"));return result;
        }
        if (op.startsWith("crew-")) return manageCrew(request);
        if (op.equals("chat")) {
            String content=BotChat.command(text(request,"text"));UUID command=UUID.fromString(text(request,"commandId"));String crew=text(request,"crew");
            if(!crews.containsKey(crew))throw new IllegalArgumentException("Unknown crew");
            UUID worker=request.has("worker")?UUID.fromString(text(request,"worker")):null;
            var targets=peers.entrySet().stream().filter(e->e.getKey().connected()&&e.getValue().crew.equals(crew)&&(worker==null||worker.equals(e.getValue().id))).toList();
            if(targets.isEmpty())throw new IllegalArgumentException("No connected workers in this target");
            long now=System.nanoTime();
            for(var target:targets) { Peer p=target.getValue();if(!p.chatSupported)throw new IllegalStateException("Install 0.7.54 or newer on "+p.observation.name());
                if(p.observation.scope().isEmpty())throw new IllegalStateException("Worker is not in a world");
                if(now-chatAt.getOrDefault(p.id,0L)<1_000_000_000L)throw new IllegalStateException("Wait one second between messages per worker"); }
            JsonArray delivered=new JsonArray();
            for(var target:targets) {Peer p=target.getValue();JsonObject m=new JsonObject();m.addProperty("type","manage-chat");m.addProperty("id",command.toString());m.addProperty("text",content);m.addProperty("scope",p.observation.scope());
                if(target.getKey().send(m.toString())) {delivered.add(p.id.toString());chatAt.put(p.id,now);} }
            JsonObject result=new JsonObject();result.add("delivered",delivered);result.addProperty("status","Sent to connected clients; not a server acknowledgement");return result;
        }
        if (op.equals("host-settings")) {
            if(!request.has("historyDays")&&!request.has("autoTpy"))throw new IllegalArgumentException("Specify a host setting");
            int days=request.has("historyDays")?integer(request,"historyDays",-1,3650):historyDays;
            boolean nextAutoTpy=autoTpy;
            if(request.has("autoTpy")){if(!request.get("autoTpy").isJsonPrimitive()||!request.getAsJsonPrimitive("autoTpy").isBoolean())throw new IllegalArgumentException("autoTpy must be true or false");nextAutoTpy=request.get("autoTpy").getAsBoolean();}
            JsonObject config=TaskFiles.read(directory.resolve("host-config.json"));
            if(config.isEmpty())throw new IllegalStateException("This host has no persistent configuration");
            config.addProperty("historyDays",days);config.addProperty("autoTpy",nextAutoTpy);TaskFiles.write(directory.resolve("host-config.json"),config);historyDays=days;autoTpy=nextAutoTpy;return status();
        }
        if (op.equals("worker-forget")) {
            UUID worker=UUID.fromString(text(request,"worker"));
            if(peers.entrySet().stream().anyMatch(e->e.getKey().connected()&&e.getValue().id.equals(worker)))
                throw new IllegalStateException("Disconnect the worker before removing it from the roster");
            roster.remove(worker);checkpointRoster();logEvent("worker-forgotten",worker.toString(),"","operator removed offline worker");return status();
        }
        if (op.equals("discard-stale-recovery")) {
            if (!flag(request, "confirmed")) throw new IllegalArgumentException("Explicit confirmation is required to discard unrecovered supplies");
            UUID worker = UUID.fromString(text(request, "worker")); String crew = text(request, "crew");
            var target = peers.entrySet().stream().filter(e -> e.getKey().connected() && e.getValue().id.equals(worker) && e.getValue().crew.equals(crew)).findFirst().orElseThrow(() -> new IllegalArgumentException("Worker is not connected in this crew"));
            JsonObject command = new JsonObject(); command.addProperty("type", "task-discard-stale-recovery");
            if (!target.getKey().send(command.toString())) throw new IllegalStateException("Could not deliver the recovery discard command");
            logEvent("worker-recovery-discarded", worker.toString(), "", "operator confirmed"); JsonObject result = new JsonObject(); result.addProperty("worker", worker.toString()); result.addProperty("status", "Sent"); return result;
        }
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
        if (op.equals("task-configuration")) return dev.monocle.coordinator.TaskConfiguration.inspect(task);
        if (op.equals("task-get")) { JsonObject result=task.deepCopy();result.remove("requestHash");if(!result.has("nativeDefinition") && result.has("highwayDefinition"))result.add("nativeDefinition",result.get("highwayDefinition").deepCopy());return result; }
        if (op.equals("task-release")) {
            for(var item:library.drafts())if(text(item.getAsJsonObject(),"sourceTask").equals(id.toString())) { cancelTask(task);persist();return item.getAsJsonObject(); }
            if(!task.has("nativeDefinition"))throw new IllegalArgumentException("Only native highway jobs have a releasable verified work checkpoint");
            JsonObject definition=task.getAsJsonObject("nativeDefinition").deepCopy();int progress=task.has("highwayProgress")?integer(task,"highwayProgress",0,integer(definition,"length",16,100000)):integer(definition,"progress",0,integer(definition,"length",16,100000));
            if(progress>=integer(definition,"length",16,100000))throw new IllegalArgumentException("Highway is already complete");
            String newId=UUID.fromString(text(request,"newId")).toString();definition.addProperty("id",newId);definition.addProperty("progress",progress);definition.addProperty("crew","");definition.addProperty("status","Unassigned");definition.remove("execution");
            JsonObject draft=new JsonObject();draft.addProperty("id",newId);draft.addProperty("sourceTask",id.toString());draft.addProperty("name",text(task,"name"));draft.addProperty("server",text(task,"server"));draft.addProperty("dimension",text(task,"dimension"));draft.addProperty("priority",integer(task,"priority",-1000,1000));draft.add("args",task.get("args").deepCopy());draft.add("package",task.get("package").deepCopy());draft.add("nativeDefinition",HighwayJobs.checked(definition));
            library.saveDraft(draft);cancelTask(task);persist();return draft;
        }
        JsonObject previous = task.deepCopy();
        try {
            switch (op) {
                case "public-join" -> {
                    if(!task.has("nativeDefinition")||QueuePolicy.terminal(text(task,"status"))||flag(task,"cancelled"))throw new IllegalStateException("Only a live native highway job can accept public joins");
                    if(!request.has("enabled")||!request.get("enabled").isJsonPrimitive()||!request.getAsJsonPrimitive("enabled").isBoolean())throw new IllegalArgumentException("enabled must be true or false");
                    task.addProperty("publicJoin",request.get("enabled").getAsBoolean());
                }
                case "configure" -> TaskWire.configure(task, request.has("worker") ? UUID.fromString(text(request, "worker")) : null, request.getAsJsonObject("modules"));
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

    private void logEvent(String type, String worker, String run, String detail) {
        JsonObject event = new JsonObject(); event.addProperty("event", type); event.addProperty("worker", worker);
        event.addProperty("at",System.currentTimeMillis());
        event.addProperty("runOrTask", run.substring(0, Math.min(run.length(), 96))); event.addProperty("detail", detail.substring(0, Math.min(detail.length(), 1024)));
        events.event(event);
        activity.addLast(event.deepCopy());while(activity.size()>128)activity.removeFirst();
    }

    private JsonObject manageCrew(JsonObject request) {
        String op=text(request,"op"),id=text(request,"crew");
        if (op.equals("crew-create")) {
            UUID.fromString(text(request,"id"));id="crew-"+text(request,"id");
            if(crews.containsKey(id))return status();
            if(crews.size()>=16)throw new IllegalArgumentException("Keep at most 16 crews");
            String label=uniqueCrewLabel(text(request,"name"),"");byte[] random=new byte[32];new java.security.SecureRandom().nextBytes(random);String key=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            String createdCrew=id;HighwayHost highway=new HighwayHost(directory,id,worker->nativeReady(createdCrew,worker),this::highwayCheckpoint);
            Map<String,String> changed=new LinkedHashMap<>(crews);changed.put(id,key);Map<String,String> labels=new LinkedHashMap<>(crewLabels);labels.put(id,label);saveCrews(changed,labels);
            crews.put(id,key);crewLabels.put(id,label);highways.put(id,highway);selectors.put(SwarmConnection.credentialSelector(key),id);return status();
        }
        if(!crews.containsKey(id))throw new IllegalArgumentException("Unknown crew");
        if(op.equals("crew-rename")) {
            String label=uniqueCrewLabel(text(request,"name"),id);Map<String,String> labels=new LinkedHashMap<>(crewLabels);labels.put(id,label);saveCrews(crews,labels);crewLabels.put(id,label);return status();
        }
        if(op.equals("crew-delete")) {
            if(crews.size()==1)throw new IllegalArgumentException("Keep at least one crew");
            HighwayHost highway=highways.get(id);
            if(highway.assigned() || highway.recoveryRecord()!=null || highway.pendingEndCount()>0 || tasks.values().stream().anyMatch(t->text(t,"crew").equals(text(request,"crew")))
                || peers.entrySet().stream().anyMatch(e->e.getKey().connected() && e.getValue().crew.equals(text(request,"crew"))))
                throw new IllegalStateException("Move its workers and delete finished job history before deleting this crew; active jobs and recovery cannot be orphaned");
            Map<String,String> changed=new LinkedHashMap<>(crews);changed.remove(id);Map<String,String> labels=new LinkedHashMap<>(crewLabels);labels.remove(id);saveCrews(changed,labels);
            selectors.remove(SwarmConnection.credentialSelector(crews.remove(id)));crewLabels.remove(id);highways.remove(id);return status();
        }
        if(op.equals("crew-move")) {
            UUID worker=UUID.fromString(text(request,"worker"));
            var source=peers.entrySet().stream().filter(e->e.getKey().connected() && e.getValue().id.equals(worker)).findFirst().orElseThrow(()->new IllegalArgumentException("Worker is offline"));
            if(source.getValue().crew.equals(id))return status();
            JsonObject destination=activeHighwayTask(id,null);moveWorker(source.getKey(),source.getValue(),id,destination==null?null:UUID.fromString(text(destination,"id")));return status();
        }
        throw new IllegalArgumentException("Unknown crew operation");
    }
    private void moveWorker(SwarmConnection connection,Peer peer,String target,UUID joinJob) {
        HighwayHost highway=highways.get(peer.crew);
        if(!peer.reconciled||!peer.current.isEmpty()||highway.isParticipant(peer.id)||highway.recoveryRecord()!=null||highway.hasPendingEnds(Set.of(peer.id))||!highway.recoveryReady(peer.id)
            ||tasks.values().stream().anyMatch(t->workerJobPending(t,peer.id)))throw new IllegalStateException("Cancel or finish the worker's jobs and wait for cleanup before moving crews");
        JsonObject message=new JsonObject();message.addProperty("type","assign-crew");message.addProperty("crew",crewLabels.getOrDefault(target,target));message.addProperty("crewId",target);
        message.addProperty("sealedKey",connection.sealSecret(crews.get(target)));if(joinJob!=null)message.addProperty("joinJob",joinJob.toString());
        if(!connection.send(message.toString()))throw new IllegalStateException("Worker disconnected before reassignment");
    }
    private String uniqueCrewLabel(String name,String except) {
        name=WorkflowPackages.label(name);for(String id:crews.keySet())if(!id.equals(except) && crewLabels.getOrDefault(id,id).equalsIgnoreCase(name))throw new IllegalArgumentException("A crew already uses that name");return name;
    }

    static boolean workerJobPending(JsonObject task, UUID worker) {
        JsonObject runs=task.getAsJsonObject("runs");
        return runs.has(worker.toString()) && (!QueuePolicy.terminal(text(task,"status"))
            || !QueuePolicy.terminal(text(runs.getAsJsonObject(worker.toString()),"status")));
    }
    private void saveCrews(Map<String,String> changed,Map<String,String> labels) {
        JsonObject config=TaskFiles.read(directory.resolve("host-config.json"));if(config.isEmpty())throw new IllegalStateException("Initialize a persistent host configuration first");
        config.add("crews",new Gson().toJsonTree(changed));config.add("crewLabels",new Gson().toJsonTree(labels));TaskFiles.write(directory.resolve("host-config.json"),config);
    }

    private void cancelTask(JsonObject task) {
        QueuePolicy.cancel(task);
        for (JsonElement value : task.getAsJsonObject("runs").asMap().values()) commands.remove(UUID.fromString(text(value.getAsJsonObject(), "id")));
    }

    JsonObject highwaySubmission(JsonObject request) {
        int x=integer(request,"x",-29_900_000,29_900_000),y=integer(request,"y",-2048,2048),z=integer(request,"z",-29_900_000,29_900_000);
        int length=integer(request,"length",16,HighwayJobs.MAX_LENGTH);String direction=text(request,"direction");
        int dx=0,dz=0;String heading;
        switch(direction) {
            case "North" -> { dz=-1;heading="North"; }
            case "East" -> { dx=1;heading="East"; }
            case "South" -> { dz=1;heading="South"; }
            case "West" -> { dx=-1;heading="West"; }
            default -> throw new IllegalArgumentException("Choose North, East, South or West");
        }
        JsonArray workers=request.getAsJsonArray("workers");if(workers!=null&&workers.size()>HighwayCoordinator.MAX_CREW_MEMBERS)throw new IllegalArgumentException("Native highway jobs support at most 3 workers");
        JsonObject prepared=request.deepCopy(),packaged=library.get(dev.monocle.client.systems.bots.BotWorkflows.DEFAULT_ID).getAsJsonObject("package");
        JsonObject geometry=packaged.getAsJsonObject("geometry"),layout=geometry.getAsJsonObject("layout"),args=new JsonObject();
        geometry.addProperty("scope",text(request,"server")+"\n"+text(request,"dimension"));geometry.addProperty("x",x);geometry.addProperty("y",y);geometry.addProperty("z",z);
        layout.addProperty("dx",dx);layout.addProperty("dz",dz);layout.addProperty("heading",heading);
        args.addProperty("x",x);args.addProperty("y",y);args.addProperty("z",z);args.addProperty("length",length);
        prepared.addProperty("op","submit");prepared.add("args",args);prepared.add("package",packaged);return prepared;
    }

    private JsonObject submit(JsonObject request) {
        UUID id = UUID.fromString(text(request, "id"));
        String digest = TaskFiles.hash(request.toString());
        if (tasks.containsKey(id)) {
            if (!text(tasks.get(id), "requestHash").equals(digest)) throw new IllegalArgumentException("Task ID already used with different arguments");
            JsonObject result = new JsonObject(); result.addProperty("id", id.toString()); result.addProperty("status", text(tasks.get(id), "status")); return result;
        }
        String crew = text(request, "crew"); if (!crews.containsKey(crew)) throw new IllegalArgumentException("Unknown crew");
        String name = WorkflowPackages.label(text(request, "name")), server = text(request, "server"), dimension = text(request, "dimension");
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
        if(request.has("nativeDefinition")) {
            JsonObject definition=HighwayJobs.checked(request.getAsJsonObject("nativeDefinition"));
            if(!text(definition,"scope").equals(server+"\n"+dimension) || !packaged.getAsJsonObject("highways").has(text(definition.getAsJsonObject("workflow"),"id")))throw new IllegalArgumentException("Native checkpoint must match the task's world and bundled workflow");
            definition.addProperty("crew",crew);definition.addProperty("status","Assigning");task.add("preparedDefinition",definition);task.addProperty("highwayProgress",integer(definition,"progress",0,integer(definition,"length",16,100000)));
        }
        for (JsonElement value : workers) {
            UUID worker = UUID.fromString(value.getAsString()); if (runs.has(worker.toString())) throw new IllegalArgumentException("Duplicate worker");
            boolean present = peers.entrySet().stream().anyMatch(e -> e.getKey().connected() && e.getValue().id.equals(worker) && e.getValue().crew.equals(crew) && e.getValue().reconciled);
            if (!present) throw new IllegalArgumentException("Every worker must be connected and reconciled in this crew");
            JsonObject run = new JsonObject(); run.addProperty("id", UUID.randomUUID().toString()); run.addProperty("status", "Queued"); runs.add(worker.toString(), run);
        }
        task.addProperty("id", id.toString()); task.addProperty("requestHash", digest); task.addProperty("name", name); task.addProperty("workflowName", name); task.addProperty("crew", crew);
        task.addProperty("server", server); task.addProperty("dimension", dimension); task.addProperty("priority", priority); task.addProperty("status", "Queued");
        task.addProperty("publicJoin",false);
        task.add("runs", runs); task.add("overrides", new JsonObject()); task.add("args", args); task.add("package", packaged); task.add("supportedActions", new Gson().toJsonTree(ACTIONS));
        validateTask(id, task);
        for (String worker : runs.keySet()) TaskFiles.jsonBytes(TaskWire.envelope(task, UUID.fromString(worker)), TaskFiles.MAX_PACKAGE);
        JsonObject retired = tasks.size() < 64 ? null : tasks.values().stream()
            .filter(t -> BotHistory.taskFinished(t, t.has("nativeDefinition")))
            .min(Comparator.comparingLong(BotHistory::finishedAt))
            .orElseThrow(() -> new IllegalArgumentException("Keep at most 64 active tasks; finish or cancel one first"));
        UUID retiredId = retired == null ? null : UUID.fromString(text(retired, "id"));
        if (retiredId != null) tasks.remove(retiredId);
        tasks.put(id, task);
        try { persist(); } catch (RuntimeException e) {
            tasks.remove(id); if (retiredId != null) tasks.put(retiredId, retired); throw e;
        }
        JsonObject result = new JsonObject(); result.addProperty("id", id.toString()); result.addProperty("status", "Queued"); return result;
    }
    private JsonObject status() {
        JsonObject result = new JsonObject(); result.addProperty("status", failure.isEmpty() ? closed ? "Stopped" : "Running" : failure);
        result.addProperty("lastConnectionError", lastConnectionError);
        result.addProperty("telemetryError", events.error()); result.addProperty("telemetryDropped", events.dropped());
        result.addProperty("operationsVersion",1);result.addProperty("historyDays",historyDays);result.addProperty("autoTpy",autoTpy);
        result.addProperty("rosterError",rosterError);
        result.add("crewLabels",new Gson().toJsonTree(crewLabels));result.add("workflows",library.list());result.add("drafts",library.drafts());
        result.add("activity",new Gson().toJsonTree(activity));
        result.add("chat",chat.json());
        result.add("crewChat",dev.monocle.coordinator.BotChat.grouped(chat.json()));
        result.addProperty("workerPort", port()); result.addProperty("webPort", webPort()); result.add("capabilities", new Gson().toJsonTree(ACTIONS)); result.add("crews", new Gson().toJsonTree(crews.keySet()));
        JsonArray workers = new JsonArray(), active = new JsonArray(), history = new JsonArray();
        Set<UUID> online=new HashSet<>();
        for (var entry : peers.entrySet()) {
            Peer peer = entry.getValue(); JsonObject worker = new JsonObject(); worker.addProperty("id", peer.id.toString()); worker.addProperty("name", peer.observation.name());
            worker.addProperty("crew", peer.crew); worker.addProperty("scope", peer.observation.scope()); worker.addProperty("connected", entry.getKey().connected());
            worker.addProperty("reconciled", peer.reconciled); worker.addProperty("current", peer.current); worker.addProperty("positionFresh", peer.observation.position() != null && peer.observation.fresh(System.nanoTime()));
            worker.addProperty("chatSupported",peer.chatSupported);
            if (peer.observation.position() != null) {
                worker.addProperty("x", peer.observation.position().x()); worker.addProperty("y", peer.observation.position().y()); worker.addProperty("z", peer.observation.position().z());
            }
            worker.addProperty("observationAgeMs", Math.max(0, (System.nanoTime() - peer.observation.receivedAt()) / 1_000_000));
            if (peer.diagnostics != null) worker.add("diagnostics", peer.diagnostics.deepCopy());
            worker.addProperty("lastSeen",System.currentTimeMillis()-Math.max(0,(System.nanoTime()-peer.observation.receivedAt())/1_000_000));
            if(entry.getKey().connected()) { online.add(peer.id);roster.put(peer.id,worker); }
        }
        while(roster.size()>256)roster.remove(roster.keySet().iterator().next());
        roster.forEach((id,r)->{JsonObject view=r.deepCopy();if(!online.contains(id)){view.addProperty("connected",false);view.addProperty("positionFresh",false);view.addProperty("reconciled",false);view.addProperty("observationAgeMs",Math.max(0,System.currentTimeMillis()-r.get("lastSeen").getAsLong()));}workers.add(view);});
        for (JsonObject task : tasks.values()) {
            JsonObject view = task.deepCopy(); view.remove("package"); view.remove("requestHash"); view.remove("args");
            if(!view.has("nativeDefinition") && view.has("highwayDefinition"))view.add("nativeDefinition",view.get("highwayDefinition").deepCopy());
            view.addProperty("cleanupPending", QueuePolicy.terminal(text(task,"status")) && !BotHistory.taskFinished(task, task.has("nativeDefinition")));
            (QueuePolicy.terminal(text(task, "status")) ? history : active).add(view);
        }
        JsonObject nativeHighways=new JsonObject();highways.forEach((crew,highway)->nativeHighways.add(crew,highway.status()));result.add("highways",nativeHighways);
        result.add("workers", workers); result.add("tasks", active); result.add("history", history); result.add("stashes",StashCatalog.list(directory)); return result;
    }
    static JsonObject checkedPackage(JsonObject input) { return WorkflowPackages.checked(input); }
    private void checkpointRoster() {
        try { status();JsonObject saved=new JsonObject();roster.forEach((id,r)->saved.add(id.toString(),r));TaskFiles.write(directory.resolve("worker-roster.json"),saved);rosterError=""; }
        catch(RuntimeException e) { rosterError="Could not save the worker roster; coordination continues. "+e.getClass().getSimpleName(); }
    }
    private void validateTask(UUID id, JsonObject task) {
        if (!id.toString().equals(text(task, "id")) || !crews.containsKey(text(task, "crew"))) throw new IllegalArgumentException("Invalid saved task identity/crew");
        name(text(task, "name")); checkedPackage(task.getAsJsonObject("package")); integer(task, "priority", -1000, 1000);
        for(String key:List.of("preparedDefinition","highwayDefinition"))if(task.has(key)) {
            JsonObject definition=HighwayJobs.checked(task.getAsJsonObject(key));if(!text(definition,"scope").equals(text(task,"server")+"\n"+text(task,"dimension")))throw new IllegalArgumentException("Checkpoint world does not match task");
        }
        if (!text(task, "requestHash").matches("[a-f0-9]{64}") || text(task, "server").isBlank() || text(task, "server").length() > 1024 || text(task, "server").chars().anyMatch(Character::isISOControl)
            || !text(task, "dimension").matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || !task.get("args").isJsonObject() || task.get("args").toString().length() > BotLua.MAX_STATE)
            throw new IllegalArgumentException("Invalid saved task scope/arguments");
        if (!Set.of("Queued", "Paused", "Cancelling", "Running", "Suspended", "Inspection required", "Complete", "Failed", "Cancelled").contains(text(task, "status"))) throw new IllegalArgumentException("Invalid saved task state");
        for (String key : List.of("paused", "cancelled")) if (task.has(key) && (!task.get(key).isJsonPrimitive() || !task.getAsJsonPrimitive(key).isBoolean())) throw new IllegalArgumentException("Invalid saved control flag");
        if(task.has("publicJoin")&&(!task.get("publicJoin").isJsonPrimitive()||!task.getAsJsonPrimitive("publicJoin").isBoolean()))throw new IllegalArgumentException("Invalid public joining option");
        if(!task.has("publicJoin"))task.addProperty("publicJoin",false);
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
        checkpointRoster();
        listener.close(); ticker.shutdownNow();
        try { lock.release(); lockChannel.close(); } catch (IOException e) { throw new IllegalStateException("Cannot release host lock", e); }
    }
}
