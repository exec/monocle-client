package dev.monocle.client.systems.bots;

import com.google.gson.*;
import dev.monocle.coordinator.QueuePolicy;
import dev.monocle.coordinator.TaskWire;
import dev.monocle.coordinator.PlayerObservation;
import dev.monocle.coordinator.PlayerObservation.Position;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.misc.swarm.SwarmConnection;
import dev.monocle.client.systems.modules.misc.swarm.SwarmCrew;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.client.utils.Utils;
import net.minecraft.core.BlockPos;
import java.nio.file.Path;
import java.util.*;
import static dev.monocle.client.MonocleClient.mc;
import static dev.monocle.client.systems.bots.BotTaskData.*;

/** Host-authoritative task queues. A transport crew is not changed when a worker is borrowed. */
public final class BotScheduler {
    public record TaskView(UUID id, String name, String workflowName, String crewId, List<UUID> targets,
                           String status, String detail, int priority, Map<UUID, Integer> workerPriorities, boolean history) { }
    private final Bots bots;
    private final BotRuntime runtime;
    private final Path file = MonocleClient.FOLDER.toPath().resolve("bot-tasks.json");
    private final Map<UUID, JsonObject> tasks = new LinkedHashMap<>();
    private final Map<UUID, JsonObject> returns = new LinkedHashMap<>();
    private final Map<UUID, PlayerObservation> reports = new HashMap<>();
    private final Map<UUID, Long> seen = new HashMap<>();
    private final Map<UUID, String> workerCurrent = new HashMap<>();
    private final Map<UUID, SwarmConnection> workerSessions = new HashMap<>();
    private final Map<UUID, Transfer> transfers = new HashMap<>();
    private final Map<UUID, SwarmConnection> transferred = new HashMap<>();
    private final Map<UUID, Long> commands = new HashMap<>();
    private final Map<String, JsonObject> teleports = new HashMap<>();
    private final Map<UUID, AutoTpy> autoTpy = new LinkedHashMap<>();
    private final Set<String> autoTpyAccepting = new HashSet<>();
    private final Map<String, Long> accepted = new HashMap<>();
    private final Path acceptanceFile = MonocleClient.FOLDER.toPath().resolve("bot-tpa-accepts.json");
    private boolean acceptedLoaded;
    private BotTaskData.Incoming incoming;
    private boolean loaded;
    private String failure;
    private int ticks;
    private long savedAt;
    private boolean dirty;
    private record Transfer(UUID run, String data, String hash, JsonObject metadata, SwarmConnection connection, int next) { }
    private record AutoTpy(UUID requester, UUID target, String requesterName, String crew, String requesterScope, long due, long expires) { }
    public BotScheduler(Bots bots) { this.bots = bots; runtime = new BotRuntime(bots); }
    public Path exportWorkflow(String id) {
        identifier(id);
        JsonObject packaged = packageFor(bots.workflows(), id);
        if (!packaged.getAsJsonObject("highways").isEmpty()) {
            if (!Utils.canUpdate()) throw new IllegalStateException("Join the highway's server/dimension before exporting its workflow");
            JsonObject geometry = new JsonObject();
            geometry.addProperty("scope",scope());geometry.addProperty("x",mc.player.getBlockX());geometry.addProperty("y",mc.player.getBlockY());geometry.addProperty("z",mc.player.getBlockZ());
            geometry.add("layout",Modules.get().get(HighwayBuilder.class).crewLayout());packaged.add("geometry",geometry);
        }
        Path output = MonocleClient.FOLDER.toPath().resolve("workflow-exports").resolve(id + "-" + UUID.randomUUID() + ".json");
        write(output, packaged); return output;
    }
    private void hostOnly() { if (bots.mode.get() != Bots.Mode.Host) throw new IllegalStateException("The host manages task queues"); }
    private void load() {
        if (failure != null) throw new IllegalStateException(failure);
        if (loaded) return;
        try {
            JsonObject root = read(file);
            if (!root.isEmpty()) {
                integer(root, "version", 1, 1);
                if (root.getAsJsonObject("tasks").size() > 64) throw new IllegalArgumentException("Too many saved tasks");
                for (var entry : root.getAsJsonObject("tasks").entrySet()) {
                    JsonObject task = entry.getValue().getAsJsonObject(); UUID id = UUID.fromString(entry.getKey());
                    checkedPackage(task.getAsJsonObject("package")); integer(task, "priority", -1000, 1000);
                    if (flag(task, "cancelled")) QueuePolicy.cancel(task);
                    else if (!terminal(text(task, "status"))) { task.addProperty("status", "Inspection required"); task.addProperty("paused", true); task.addProperty("detail", "Host restarted. Inspect native job and task checkpoints, then Resume."); }
                    tasks.put(id, task);
                }
                if (root.has("returns")) for (var entry : root.getAsJsonObject("returns").entrySet()) returns.put(UUID.fromString(entry.getKey()), entry.getValue().getAsJsonObject());
            }
            loaded = true;
            if (stampHistory()) save();
        } catch (RuntimeException e) { failure = e.getMessage(); throw e; }
    }
    private void save() {
        stampHistory();
        JsonObject root = new JsonObject(), records = new JsonObject(), reservations = new JsonObject(); root.addProperty("version", 1);
        tasks.forEach((id, task) -> records.add(id.toString(), task)); returns.forEach((id, r) -> reservations.add(id.toString(), r));
        root.add("tasks", records); root.add("returns", reservations); write(file, root); dirty = false; savedAt = System.nanoTime();
    }
    public boolean hasWork() { if (bots.mode.get() == Bots.Mode.Worker) return runtime.hasWork(); load(); return !returns.isEmpty() || tasks.values().stream().anyMatch(t -> !terminal(text(t, "status"))); }
    public boolean usesCrew(String crew) { load(); return tasks.entrySet().stream().anyMatch(e -> !deletable(e.getKey(), e.getValue()) && crew.equals(text(e.getValue(), "crew"))) || returns.values().stream().anyMatch(r -> crew.equals(text(r, "crew"))); }
    public boolean workerBusy() { return runtime.ownsControls(); }
    public JsonArray stashDiagnostics(UUID taskId) {
        JsonArray result=new JsonArray();if(bots.mode.get()!=Bots.Mode.Host)return result;load();JsonObject t=tasks.get(taskId);if(t==null)return result;
        for(var entry:t.getAsJsonObject("runs").entrySet()){
            JsonObject r=entry.getValue().getAsJsonObject();if(!r.has("stashScan"))continue;
            JsonObject d=r.getAsJsonObject("stashScan").deepCopy();d.addProperty("worker",entry.getKey());d.addProperty("status",text(r,"status"));d.addProperty("detail",text(r,"detail"));
            if(r.has("stashEvents"))d.add("events",r.get("stashEvents").deepCopy());result.add(d);
        }
        return result;
    }
    public boolean allowsNative() { return runtime.allowsNative(); }
    public boolean reserves(UUID worker) { load(); return teleportPinned(worker) || returns.containsKey(worker) || tasks.values().stream().anyMatch(t -> t.getAsJsonObject("runs").has(worker.toString()) && (!terminal(text(t, "status")) || !terminal(text(run(t, worker), "status")))); }
    public List<TaskView> list() {
        if (bots.mode.get() == Bots.Mode.Worker) return runtime.views().stream().map(r -> new TaskView(UUID.fromString(text(r, "task")), text(r, "name"), text(r, "workflowName"), text(r, "crew"), List.of(mc.getUser().getProfileId()), text(r, "status"), text(r, "detail"), r.has("priority") ? r.get("priority").getAsInt() : 0, Map.of(), terminal(text(r, "status")) && !BotRuntime.hasUncertainAction(r))).toList();
        load(); return tasks.entrySet().stream().map(e -> view(e.getKey(), e.getValue())).toList();
    }
    private TaskView view(UUID id, JsonObject t) {
        Map<UUID, Integer> overrides = new LinkedHashMap<>(); t.getAsJsonObject("overrides").entrySet().forEach(e -> overrides.put(UUID.fromString(e.getKey()), e.getValue().getAsInt()));
        return new TaskView(id, text(t, "name"), text(t, "workflowName"), text(t, "crew"), workers(t), text(t, "status"), text(t, "detail"), t.get("priority").getAsInt(), Map.copyOf(overrides), terminal(text(t, "status")));
    }
    public UUID create(String name, String workflowId, String crewId, Set<UUID> targets, JsonObject args, int priority, Map<UUID, Integer> overrides) {
        if (workflowId.startsWith("package:")) return createPackage(name, workflowId.substring(8), crewId, targets, args, priority, overrides);
        hostOnly(); load();
        if (!bots.isHost() || !Utils.canUpdate()) throw new IllegalStateException("Start the host in a world before queuing work");
        if (targets.isEmpty() || targets.size() > 16 || targets.contains(mc.player.getUUID()) || !targets.containsAll(overrides.keySet())) throw new IllegalArgumentException("Choose 1–16 remote workers in this crew (native highways allow at most 3)");
        for (UUID worker : targets) if (!crewId.equals(bots.workerCrew(worker))) throw new IllegalArgumentException("Every target must be connected to the selected crew");
        if (args == null || args.toString().length() > BotLua.MAX_STATE) throw new IllegalArgumentException("Arguments must be a JSON object of at most 32 KiB");
        checkedPriority(priority); overrides.values().forEach(BotScheduler::checkedPriority);
        var workflow = bots.workflows().get(workflowId);
        JsonObject packaged = packageFor(bots.workflows(), workflowId);
        if (workflowId.equals("task-profile")) {
            String profile = text(args, "name"); if (profile.isEmpty()) profile = "Current";
            packaged.getAsJsonObject("profiles").add(profile, BotProfiles.capture(profile)); packaged = checkedPackage(packaged);
        }
        if (!workflow.script().isEmpty() && workflowId.startsWith("task-")) {
            String type = switch (workflowId) { case "task-stash-scan" -> "StashScan"; case "task-stash-hunt" -> "StashHunt"; case "task-travel" -> "Travel"; case "task-drop" -> "DropItems"; case "task-tpa" -> "Tpa"; case "task-wait" -> "Wait"; case "task-modules" -> "Modules"; case "task-profile" -> "SetProfile"; default -> ""; };
            if (!type.isEmpty()) { JsonObject action = args.deepCopy(); action.addProperty("type", type); BotActions.validate(action); }
        }
        if (!packaged.getAsJsonObject("highways").isEmpty()) {
            if (targets.size() > dev.monocle.coordinator.HighwayCoordinator.MAX_CREW_MEMBERS) throw new IllegalArgumentException("Native highway workflows support at most 3 workers");
            JsonObject geometry = new JsonObject();
            geometry.addProperty("scope", scope()); geometry.addProperty("x", mc.player.getBlockX()); geometry.addProperty("y", mc.player.getBlockY()); geometry.addProperty("z", mc.player.getBlockZ());
            geometry.add("layout", Modules.get().get(HighwayBuilder.class).crewLayout()); packaged.add("geometry", geometry);
        }
        UUID id = createCaptured(name, workflowId, workflow.name(), crewId, targets, args, priority, overrides, packaged, scope());
        bots.info("Queued %s for %d worker%s at priority %d.", name, targets.size(), targets.size() == 1 ? "" : "s", priority); return id;
    }
    /** Old highway forms are native workflow jobs too; no worker starts before its full configuration arrives. */
    private UUID createPackage(String name, String id, String crewId, Set<UUID> targets, JsonObject args, int priority, Map<UUID,Integer> overrides) {
        hostOnly();load();
        if (!bots.isHost() || !Utils.canUpdate()) throw new IllegalStateException("Start the host in a world before queuing work");
        if(targets.isEmpty() || targets.size()>16 || targets.contains(mc.player.getUUID()) || !targets.containsAll(overrides.keySet()))throw new IllegalArgumentException("Choose 1–16 remote workers");
        for(UUID worker:targets)if(!crewId.equals(bots.workerCrew(worker)))throw new IllegalArgumentException("Every target must be connected to this crew");
        checkedPriority(priority);overrides.values().forEach(BotScheduler::checkedPriority);
        if(args==null || args.toString().length()>BotLua.MAX_STATE)throw new IllegalArgumentException("Arguments too large");
        JsonObject record=bots.operations().get(id),packet=checkedPackage(record.getAsJsonObject("package"));
        if(!packet.getAsJsonObject("highways").isEmpty() && (!text(packet.getAsJsonObject("geometry"),"scope").equals(scope()) || targets.size()>dev.monocle.coordinator.HighwayCoordinator.MAX_CREW_MEMBERS))throw new IllegalArgumentException("Captured highway geometry must match this world, with at most 3 workers");
        return createCaptured(name,text(packet,"entry"),text(record,"name"),crewId,targets,args,priority,overrides,packet,scope());
    }

    /** Old highway forms are native workflow jobs too; no worker starts before its full configuration arrives. */
    UUID queueHighway(JsonObject definition, String crew, Set<UUID> workers, boolean includeHost) {
        hostOnly(); load();
        JsonObject packaged = new JsonObject(), programs = new JsonObject(), highways = new JsonObject(), profiles = new JsonObject(), program = new JsonObject();
        packaged.addProperty("version", 1); packaged.addProperty("entry", "captured-highway");
        program.addProperty("name", text(definition, "name"));
        program.addProperty("script", "return function(ctx)\n if ctx.state.started then return bot.done() end\n ctx.state.started=true\n return bot.highway({workflow='captured-highway'})\nend");
        programs.add("captured-highway", program); highways.add("captured-highway", definition.get("workflow").deepCopy()); profiles.add("Current", BotProfiles.capture("Current"));
        packaged.add("programs", programs); packaged.add("highways", highways); packaged.add("profiles", profiles); packaged.add("geometry", definition.deepCopy());
        UUID id = createCaptured(text(definition, "name"), text(definition.getAsJsonObject("workflow"), "id"), text(definition.getAsJsonObject("workflow"), "name"), crew, workers, new JsonObject(), 0, Map.of(), packaged, text(definition, "scope"));
        JsonObject task = task(id); task.add("nativeDefinition", definition.deepCopy()); task.addProperty("includeHost", includeHost); save(); return id;
    }
    boolean pendingNativeJob(UUID id) {
        load(); return tasks.values().stream().anyMatch(t -> !terminal(text(t, "status")) && t.has("nativeDefinition") && text(t.getAsJsonObject("nativeDefinition"), "id").equals(id.toString()));
    }
    boolean controlNative(UUID id, String command) {
        load();
        for (var entry : tasks.entrySet()) {
            JsonObject t = entry.getValue();
            if (terminal(text(t, "status")) || !t.has("nativeDefinition") || !id.toString().equals(text(t.getAsJsonObject("nativeDefinition"), "id"))) continue;
            switch (command) { case "pause" -> pause(entry.getKey()); case "resume" -> resume(entry.getKey()); case "cancel" -> cancel(entry.getKey()); default -> throw new IllegalArgumentException("Unknown native task control"); }
            return true;
        }
        return false;
    }
    private UUID createCaptured(String name, String workflowId, String workflowName, String crewId, Set<UUID> targets, JsonObject args, int priority, Map<UUID, Integer> overrides, JsonObject packaged, String jobScope) {
        name = BotJobs.name(name); packaged = checkedPackage(packaged);
        String[] world = jobScope.split("\n", -1);
        if (world.length != 2 || world[0].isBlank() || world[1].isBlank() || jobScope.length() > 1024) throw new IllegalArgumentException("Choose a server and dimension for this task");
        if (tasks.size() >= 64) {
            UUID retiredId = tasks.entrySet().stream().filter(e -> deletable(e.getKey(), e.getValue()))
                .min(Comparator.comparingLong(e -> BotHistory.finishedAt(e.getValue())))
                .map(Map.Entry::getKey).orElseThrow(() -> new IllegalStateException("Keep at most 64 active tasks; finish or cancel one first"));
            bots.deleteTaskHistory(retiredId);
        }
        UUID id = UUID.randomUUID(); JsonObject t = new JsonObject(), runs = new JsonObject(), priorities = new JsonObject();
        t.addProperty("id", id.toString()); t.addProperty("name", BotJobs.name(name)); t.addProperty("workflowId", workflowId); t.addProperty("workflowName", workflowName); t.addProperty("crew", crewId);
        t.addProperty("server", world[0]);
        t.addProperty("dimension", world[1]);
        t.addProperty("priority", priority); t.addProperty("status", "Queued"); t.addProperty("detail", "Waiting for a worker checkpoint"); t.addProperty("paused", false); t.addProperty("cancelled", false);
        t.add("package", checkedPackage(packaged)); t.add("args", args.deepCopy());
        for (UUID worker : targets.stream().sorted().toList()) { JsonObject r = new JsonObject(); r.addProperty("id", UUID.randomUUID().toString()); r.addProperty("status", "Queued"); r.addProperty("detail", "Waiting in queue"); runs.add(worker.toString(), r); }
        overrides.forEach((worker, p) -> priorities.addProperty(worker.toString(), p)); t.add("overrides", priorities); t.add("runs", runs);
        tasks.put(id, t); try { save(); } catch (RuntimeException e) { tasks.remove(id); throw e; } return id;
    }
    private JsonObject task(UUID id) { load(); JsonObject t = tasks.get(id); if (t == null) throw new IllegalArgumentException("Unknown task"); return t; }
    public void pause(UUID id) { hostOnly(); if (QueuePolicy.pause(task(id))) save(); }
    public void configure(UUID id, UUID worker, JsonObject modules) {
        hostOnly(); JsonObject t = task(id), previous = t.deepCopy();
        TaskWire.configure(t, worker, modules);
        try { save(); } catch (RuntimeException e) { tasks.put(id, previous); throw e; }
    }
    public void resume(UUID id) {
        hostOnly(); JsonObject t = task(id); QueuePolicy.resume(t);
        if (t.has("highway")) t.addProperty("nativeResumeRequested", true);
        save(); manageHighway(t);
    }
    public void cancel(UUID id) {
        hostOnly(); JsonObject t = task(id); if (!QueuePolicy.cancel(t)) return;
        for (UUID worker : workers(t)) commands.remove(runId(run(t, worker)));
        save();
        manageHighway(t);
    }
    public void delete(UUID id) {
        hostOnly(); bots.deleteTaskHistory(id);
    }
    boolean deletable(UUID id, JsonObject t) {
        return BotHistory.taskFinished(t, returns.values().stream().anyMatch(r -> id.toString().equals(text(r, "task"))));
    }
    Map<UUID, JsonObject> historyRecords() { load(); if (stampHistory()) save(); return Collections.unmodifiableMap(tasks); }
    private boolean stampHistory() {
        boolean changed = false;
        for (var e : tasks.entrySet()) changed |= BotHistory.stamp(e.getValue(), deletable(e.getKey(), e.getValue()), System.currentTimeMillis());
        return changed;
    }
    void removeHistory(UUID id) {
        hostOnly(); JsonObject t = task(id);
        if (!deletable(id, t)) throw new IllegalStateException("Finish/cancel this task and its return handoff before deleting its history");
        tasks.remove(id); try { save(); } catch (RuntimeException e) { tasks.put(id, t); throw e; }
    }
    public void priority(UUID id, int value) { hostOnly(); checkedPriority(value); task(id).addProperty("priority", value); save(); }
    public void workerPriority(UUID id, UUID worker, int value) { hostOnly(); checkedPriority(value); JsonObject t = task(id); if (!t.getAsJsonObject("runs").has(worker.toString())) throw new IllegalArgumentException("Worker is not targeted by this job"); t.getAsJsonObject("overrides").addProperty(worker.toString(), value); save(); }
    static void checkedPriority(int value) { QueuePolicy.checkedPriority(value); }
    static int priority(JsonObject task, UUID worker) { return QueuePolicy.priority(task, worker); }
    static boolean terminal(String status) { return QueuePolicy.terminal(status); }
    private static List<UUID> workers(JsonObject task) { return task.getAsJsonObject("runs").keySet().stream().map(UUID::fromString).toList(); }
    private static JsonObject run(JsonObject t, UUID worker) { return t.getAsJsonObject("runs").getAsJsonObject(worker.toString()); }
    private static UUID runId(JsonObject r) { return UUID.fromString(text(r, "id")); }
    private static boolean flag(JsonObject o, String key) { return o.has(key) && o.get(key).getAsBoolean(); }
    private boolean fresh(UUID id) {
        Long received = seen.get(id);
        long now = System.nanoTime();
        return received != null && now - received >= 0 && now - received < PlayerObservation.MAX_AGE;
    }
    private SwarmConnection connection(UUID id) { String crew = bots.workerCrew(id); return crew.equals("Offline") ? null : bots.coordinator(crew).connectionForWorker(id); }
    private String scope() { return !Utils.canUpdate() ? "" : (mc.getCurrentServer() == null ? "local" : mc.getCurrentServer().ip) + "\n" + mc.level.dimension().identifier(); }
    private static Position position(BlockPos pos) { return new Position(pos.getX(), pos.getY(), pos.getZ()); }
    private PlayerObservation localObservation() {
        return !Utils.canUpdate() ? null : new PlayerObservation(mc.player.getUUID(), mc.player.getName().getString(), scope(), position(mc.player.blockPosition()), System.nanoTime());
    }
    private PlayerObservation observation(UUID id) {
        SwarmConnection current = connection(id);
        PlayerObservation report = reports.get(id);
        return current != null && current.connected() && workerSessions.get(id) == current && report != null && report.fresh(System.nanoTime()) ? report : null;
    }
    private Map<UUID, PlayerObservation> observations(String crew) {
        Map<UUID, PlayerObservation> current = new LinkedHashMap<>();
        for (UUID id : reports.keySet()) if (crew.equals(bots.workerCrew(id))) {
            PlayerObservation report = observation(id); if (report != null) current.put(id, report);
        }
        return current;
    }

    /** Called only after native identity/position/generation validation on this connection. */
    public void observeWorker(SwarmConnection connection, JsonObject message) {
        if (!connection.connected()) throw new IllegalStateException("Unauthenticated worker observation");
        PlayerObservation report = PlayerObservation.fromHello(message, System.nanoTime());
        if (workerSessions.put(report.id(), connection) != connection) workerCurrent.remove(report.id());
        reports.put(report.id(), report); seen.put(report.id(), report.receivedAt());
    }

    /** Called before native dispatch, on the same authenticated connection; all other crews stay isolated. */
    public boolean handle(SwarmConnection connection, JsonObject m, boolean hostSide) {
        String type = text(m, "type");
        if (!type.startsWith("task-")) return false;
        if (!connection.connected()) throw new IllegalStateException("Unauthenticated task message");
        if (hostSide) {
            load(); UUID worker = bots.allMembers().stream().filter(v -> connection(v.id()) == connection).map(SwarmCrew.MemberView::id).findFirst().orElseThrow(() -> new IllegalArgumentException("Worker must announce its identity first"));
            switch (type) {
                case "task-worker" -> {
                    String active = text(m, "current"); if (!active.isEmpty()) UUID.fromString(active); workerCurrent.put(worker, active); seen.put(worker, System.nanoTime());
                    JsonArray states = m.getAsJsonArray("runs"); if (states.size() > 64) throw new IllegalArgumentException("Too many worker states");
                    for (JsonElement value : states) updateStatus(worker, value.getAsJsonObject(), false);
                    reconcileWorker(worker, states, connection);
                    if(m.has("stashCatalogProtocol")&&integer(m,"stashCatalogProtocol",1,1)==1)sendStashCatalog(connection,bots.workerCrew(worker));
                }
                case "task-status" -> updateStatus(worker, m, true);
                case "task-survey-findings" -> receiveSurvey(worker, m);
                case "task-stash-findings" -> {
                    JsonObject task=tasks.get(UUID.fromString(text(m,"task")));
                    if(task==null||!task.getAsJsonObject("runs").has(worker.toString()))throw new IllegalArgumentException("Unknown stash task");
                    JsonObject ack=dev.monocle.coordinator.StashCatalog.accept(MonocleClient.FOLDER.toPath(),task,run(task,worker),worker.toString(),bots.workerCrew(worker),m);
                    if(ack!=null){dirty=true;save();connection.send(ack.toString());}
                }
                case "task-stash-definition" -> {
                    String scope=text(m,"scope");if(scope.length()>384||!scope.contains("\n"))throw new IllegalArgumentException("Invalid stash scope");
                    dev.monocle.coordinator.StashCatalog.define(MonocleClient.FOLDER.toPath(),bots.workerCrew(worker),scope,m.getAsJsonObject("stash"),worker.toString());sendStashCatalog(connection,bots.workerCrew(worker));
                }
                case "task-stash-import" -> {
                    String scope=text(m,"scope");if(scope.length()>384||!scope.contains("\n"))throw new IllegalArgumentException("Invalid stash scope");
                    dev.monocle.coordinator.StashCatalog.save(MonocleClient.FOLDER.toPath(),bots.workerCrew(worker),scope,m.getAsJsonObject("stash"),m.getAsJsonObject("observation"));
                }
                default -> throw new IllegalArgumentException("Worker cannot send task commands: " + type);
            }
            return true;
        }
        if (connection != bots.worker) throw new IllegalArgumentException("Task commands must come from the connected host");
        String owner = connection.credentialId();
        switch (type) {
            case "task-begin" -> {
                if (incoming != null && System.nanoTime() - incoming.started < 120_000_000_000L) {
                    if (incoming.matchesBegin(m)) return true;
                    if (incoming.run.toString().equals(text(m, "run"))) throw new IllegalArgumentException("Workflow digest changed during transfer");
                    if (incoming.next < incoming.count) throw new IllegalStateException("Another workflow transfer is in progress");
                }
                incoming = new Incoming(m);
            }
            case "task-chunk" -> {
                if (incoming == null) throw new IllegalStateException("No pending workflow package");
                JsonObject packaged = incoming.add(m);
                if (packaged != null) { UUID id = incoming.run; JsonObject metadata = packaged.remove("dispatch").getAsJsonObject(); runtime.install(id, metadata, packaged, owner); }
            }
            case "task-control" -> runtime.control(UUID.fromString(text(m, "run")), text(m, "command"), owner);
            case "task-configure" -> runtime.configure(UUID.fromString(text(m, "run")), m.getAsJsonObject("modules"), integer(m, "revision", 1, Integer.MAX_VALUE), owner);
            case "task-survey-ack" -> runtime.acknowledgeSurvey(UUID.fromString(text(m, "run")), text(m, "token"), integer(m, "delivery", 1, 1_048_576), owner);
            case "task-stash-ack" -> runtime.acknowledgeStash(UUID.fromString(text(m,"run")),text(m,"token"),integer(m,"delivery",1,4096),owner);
            case "task-stash-catalog" -> dev.monocle.coordinator.StashCatalog.cacheRemote(MonocleClient.FOLDER.toPath(),m.getAsJsonArray("stashes"));
            case "task-result" -> runtime.external(UUID.fromString(text(m, "run")), text(m, "token"), flag(m, "success"), text(m, "detail"), m.has("result") ? m.getAsJsonObject("result") : new JsonObject());
            case "task-tpa-send" -> runtime.teleport(UUID.fromString(text(m, "run")), text(m, "token"), text(m, "name"), UUID.fromString(text(m, "target")), text(m, "dimension"));
            case "task-tpa-accept" -> acceptTeleport(m);
            case "task-discard-stale-recovery" -> runtime.discardStaleRecovery();
            default -> throw new IllegalArgumentException("Unknown task command: " + type);
        }
        return true;
    }
    private void sendStashCatalog(SwarmConnection connection,String crew){JsonObject m=TaskWire.message("stash-catalog");JsonArray list=new JsonArray();for(JsonElement value:dev.monocle.coordinator.StashCatalog.list(MonocleClient.FOLDER.toPath())){JsonObject s=value.getAsJsonObject();if(text(s,"crew").equals(crew))list.add(s.deepCopy());if(list.size()==64)break;}m.add("stashes",list);connection.send(m.toString());}
    private void updateStatus(UUID worker, JsonObject message, boolean full) {
        String id = text(message, "run"), state = text(message, "status"); UUID.fromString(id);
        if (!Set.of("Ready", "Running", "Suspending", "Suspended", "Inspection required", "Complete", "Failed", "Cancelled").contains(state)) throw new IllegalArgumentException("Invalid task execution state");
        for (JsonObject t : tasks.values()) if (t.getAsJsonObject("runs").has(worker.toString())) {
            JsonObject r = run(t, worker); if (!text(r, "id").equals(id)) continue;
            if(full&&message.has("stashWithdrawal")&&!text(r,"stashWithdrawalToken").equals(text(message,"token"))){JsonObject receipt=message.getAsJsonObject("stashWithdrawal");dev.monocle.coordinator.StashCatalog.invalidateWithdrawn(MonocleClient.FOLDER.toPath(),text(t,"crew"),text(t,"server")+"\n"+text(t,"dimension"),text(receipt,"stash"),receipt.getAsJsonArray("withdrawn"));r.addProperty("stashWithdrawalToken",text(message,"token"));}
            if (!text(t, "crew").equals(bots.workerCrew(worker))) { if (terminal(text(r, "status"))) return; throw new IllegalArgumentException("Task status arrived through another crew"); }
            JsonObject checked = message.deepCopy();
            if (full && checked.has("action")) checked.add("action", BotActions.validate(checked.getAsJsonObject("action")));
            SwarmCrew highway = bots.coordinator(text(t, "crew"));
            boolean isolateInspection = t.has("nativeDefinition") && highway.assigned() && highway.independentSupplies();
            if (!TaskWire.applyStatus(t, r, checked, full, isolateInspection)) return;
            if (Set.of("Running", "Suspending").contains(state)) workerCurrent.put(worker, id);
            else if (id.equals(workerCurrent.get(worker))) workerCurrent.put(worker, "");
            dirty = true; return;
        }
    }
    public void disconnected() {
        runtime.disconnected(); transfers.clear(); transferred.clear(); autoTpy.clear(); autoTpyAccepting.clear(); incoming = null;
        reports.clear(); seen.clear(); workerSessions.clear(); workerCurrent.clear();
    }
    public void tick() {
        if (bots.mode.get() == Bots.Mode.Worker) { runtime.tick(); return; }
        load(); if (!bots.isHost()) return;
        // Control delivery must not depend on gameplay readiness or another job's cleanup.
        for (JsonObject t : tasks.values()) if (flag(t, "cancelled")) dispatchCancellation(t);
        if (!Utils.canUpdate()) return;
        ticks++;
        for (JsonObject t : List.copyOf(tasks.values())) {
            try { manageHighway(t); }
            catch (RuntimeException e) {
                t.addProperty("detail", "Native cleanup: " + e.getMessage()); dirty = true;
                bots.reportConnectionFailure("Native cleanup will retry: " + e.getMessage());
            }
        }
        for (UUID worker : bots.allMembers().stream().filter(m -> !m.id().equals(mc.player.getUUID()) && m.connected()).map(SwarmCrew.MemberView::id).toList()) {
            try { schedule(worker); }
            catch (RuntimeException e) {
                JsonObject active = activeTask(worker);
                if (active != null) { run(active, worker).addProperty("detail", "Scheduler retry: " + e.getMessage()); dirty = true; }
                bots.reportConnectionFailure("Worker scheduling will retry: " + e.getMessage());
            }
        }
        flushTransfers(); tickTeleports(); tickAutoTpy();
        if (ticks % 20 == 0) {
            for (JsonObject t : tasks.values()) summarize(t);
            if (dirty || System.nanoTime() - savedAt > 10_000_000_000L) save();
        }
    }
    private void reconcileWorker(UUID worker, JsonArray states, SwarmConnection connection) {
        Set<String> present = new HashSet<>();
        for (JsonElement value : states) present.add(text(value.getAsJsonObject(), "run"));
        for (JsonObject task : tasks.values()) if (text(task, "crew").equals(bots.workerCrew(worker)) && task.getAsJsonObject("runs").has(worker.toString())) {
            JsonObject run = run(task, worker);
            // Only a fresh connection's inventory can prove a previous transfer/checkpoint is absent.
            if (terminal(text(run, "status")) || present.contains(text(run, "id"))
                || transfers.containsKey(worker) || transferred.get(runId(run)) == connection) continue;
            reconcileMissingRun(task, run); dirty = true;
        }
    }
    static void reconcileMissingRun(JsonObject task, JsonObject run) {
        QueuePolicy.reconcileMissingRun(task, run);
    }
    private void dispatchCancellation(JsonObject task) {
        for (UUID worker : workers(task)) {
            JsonObject r = run(task, worker);
            if (terminal(text(r, "status"))) continue;
            if (text(r, "status").equals("Queued")) {
                r.addProperty("status", "Cancelled"); r.addProperty("detail", "Cancelled before dispatch"); dirty = true; continue;
            }
            SwarmConnection c = connection(worker);
            if (c == null || !c.connected()) { r.addProperty("detail", "Cancellation retained; waiting for worker reconnect"); continue; }
            if (!text(task, "crew").equals(bots.workerCrew(worker))) continue; // Never deliver another crew's retained controls.
            // Finish an in-flight install (which never starts work), then send cancel in socket order.
            if (text(r, "status").equals("Sending")) { transfer(task, worker, r); if (transfers.containsKey(worker)) continue; }
            control(worker, r, cancellationCommand(r));
        }
    }
    static String cancellationCommand(JsonObject run) {
        return QueuePolicy.cancellationCommand(run);
    }
    static JsonObject choose(Collection<JsonObject> tasks, UUID worker) {
        return QueuePolicy.choose(tasks, worker);
    }
    private JsonObject activeTask(UUID worker) {
        String active = workerCurrent.getOrDefault(worker, ""); if (active.isEmpty()) return null;
        for (JsonObject t : tasks.values()) if (t.getAsJsonObject("runs").has(worker.toString()) && text(run(t, worker), "id").equals(active)) return t;
        return null;
    }
    private void schedule(UUID worker) {
        if (!fresh(worker) || !workerCurrent.containsKey(worker)) return;
        JsonObject active = activeTask(worker), next = choose(tasks.values(), worker);
        if (active != null && !flag(active, "cancelled") && !flag(active, "paused")) {
            JsonObject config = TaskWire.configurationToSend(run(active, worker), System.currentTimeMillis());
            if (config != null) send(worker, config);
        }
        if (source(worker) == null && !returns.containsKey(worker) && !teleportPinned(worker)) {
            QueuePolicy.Dispatch dispatch = QueuePolicy.dispatch(active, next, worker);
            if (dispatch.task() == null) return;
            JsonObject r = run(dispatch.task(), worker);
            switch (dispatch.command()) {
                case "transfer" -> transfer(dispatch.task(), worker, r);
                case "resume", "pause", "cancel" -> control(worker, r, dispatch.command());
                case "external" -> handleExternal(dispatch.task(), worker, r);
                default -> { }
            }
            return;
        }
        if (active != null && returns.containsKey(worker) && text(returns.get(worker), "task").equals(text(active, "id"))
            && next != null && run(next, worker).has("action") && text(run(next, worker).getAsJsonObject("action"), "type").equals("Highway")) next = active;
        if (active == null && returns.containsKey(worker) && next != null && run(next, worker).has("action") && text(run(next, worker).getAsJsonObject("action"), "type").equals("Highway")) {
            JsonObject reservation = returns.get(worker);
            JsonObject returning = reservation.has("task") ? tasks.get(UUID.fromString(text(reservation, "task"))) : null;
            if (returning != null && !terminal(text(returning, "status")) && !flag(returning, "paused")) next = returning;
            else { returnWorker(worker); return; }
        }
        if (active != null) {
            JsonObject r = run(active, worker);
            boolean cleanup = text(r, "status").equals("Suspending");
            boolean cancel = flag(active, "cancelled"), pause = flag(active, "paused"), preempt = QueuePolicy.preempts(active, next, worker);
            if (preempt && teleportPinned(worker)) { r.addProperty("detail", "Reserved as the target of a pending crew teleport"); return; }
            if (cancel || pause || preempt || cleanup) {
                SwarmCrew source = source(worker);
                if (source != null && source.isParticipant(worker)) {
                    if (cleanup && (flag(r, "connectionSuspended") || QueuePolicy.resumingSuspension(r)) && !cancel && !pause && !preempt) {
                        if (source.canResume() && !source.isPausedByHost()) control(worker, r, "resume");
                        return; // A transport interruption is not a request to borrow this worker's lane.
                    }
                    if (cleanup && active.has("highway") && ownsHighway(active, source) && Set.of("Failed", "Complete", "Cancelled").contains(text(r, "requestedStatus"))) {
                        if (!source.independentSupplies()) {
                            active.addProperty("failedNative", !text(r, "requestedStatus").equals("Complete")); releaseHighway(active); return;
                        }
                        return; // Only this failed worker is off duty; ending the job later releases its cleanup.
                    }
                    if ((pause || cancel) && active.has("highway") && allTargeted(source, active)) {
                        if (cancel) releaseHighway(active); else if (!source.inspect().phase().equals("paused")) source.pause();
                        r.addProperty("detail", "Highway checkpoint retained; " + (cancel ? "recovering supplies before cancellation" : "paused by host")); return;
                    }
                    if (!borrow(worker, source)) { r.addProperty("detail", Objects.requireNonNullElse(source.borrowingReason(Set.of(worker)), "Waiting for safe withdrawal acknowledgement")); return; }
                }
                if (cleanup && !cancel) return;
                control(worker, r, cancel ? "cancel" : "pause"); return;
            }
            if (returns.containsKey(worker) && r.has("action") && text(r.getAsJsonObject("action"), "type").equals("Highway")) { returnWorker(worker); return; }
            handleExternal(active, worker, r); return;
        }
        // Cancel queued/suspended runs too; uninstalled runs have no effects and can be retired locally.
        for (JsonObject t : tasks.values()) if (flag(t, "cancelled") && t.getAsJsonObject("runs").has(worker.toString())) {
            JsonObject r = run(t, worker); if (terminal(text(r, "status"))) continue;
            if (text(r, "status").equals("Queued")) { r.addProperty("status", "Cancelled"); dirty = true; }
            else if (text(r, "status").equals("Inspection required") && text(r, "requestedStatus").equals("Cancelled")) { control(worker, r, "resume"); return; }
            else { control(worker, r, "cancel"); return; }
        }
        if (next == null) { returnWorker(worker); return; }
        if (teleportPinned(worker)) return;
        SwarmCrew source = source(worker);
        if (source != null && source.isParticipant(worker)) {
            JsonObject r = run(next, worker);
            if (next.has("highway") && text(next, "highway").equals(text(source.jobSnapshot(), "id")) && r.has("action") && text(r.getAsJsonObject("action"), "type").equals("Highway")) { control(worker, r, "resume"); return; }
            if (priority(next, worker) <= 0) { r.addProperty("detail", "Native highway has priority 0; raise this worker's task priority to interrupt it"); return; }
            if (!borrow(worker, source)) { r.addProperty("detail", Objects.requireNonNullElse(source.borrowingReason(Set.of(worker)), "Waiting for safe withdrawal acknowledgement")); return; }
        }
        if (source != null && source.reservedReturns().contains(worker) && !source.borrowReady(worker)) return;
        JsonObject r = run(next, worker);
        if (returns.containsKey(worker) && r.has("action") && text(r.getAsJsonObject("action"), "type").equals("Highway")) { returnWorker(worker); return; }
        if (text(r, "status").equals("Queued") || text(r, "status").equals("Sending")) transfer(next, worker, r);
        else if (Set.of("Ready", "Suspended", "Inspection required").contains(text(r, "status"))) control(worker, r, "resume");
    }
    private boolean allTargeted(SwarmCrew source, JsonObject task) { JsonObject snapshot = source.jobSnapshot(); return snapshot != null && snapshot.getAsJsonArray("members").asList().stream().allMatch(m -> m.getAsString().equals(text(snapshot, "hostMember")) || task.getAsJsonObject("runs").has(m.getAsString())); }
    private SwarmCrew source(UUID worker) { for (var preset : bots.presets()) { SwarmCrew c = bots.coordinator(preset.name()); if (c.assigned() && (c.isParticipant(worker) || c.reservedReturns().contains(worker))) return c; } return null; }
    private boolean borrow(UUID worker, SwarmCrew source) {
        if (source.borrowReady(worker)) return true;
        if (source.reservedReturns().contains(worker)) return false;
        String reason = source.borrowingReason(Set.of(worker)); if (reason != null) return false;
        String crew = bots.presets().stream().filter(p -> bots.coordinator(p.name()) == source).map(Bots.CrewPreset::name).findFirst().orElseThrow();
        JsonObject reservation = new JsonObject(); reservation.addProperty("crew", crew); returns.put(worker, reservation); save();
        try { source.requestBorrow(Set.of(worker)); } catch (RuntimeException e) { returns.remove(worker); save(); throw e; }
        return false;
    }
    private void transfer(JsonObject t, UUID worker, JsonObject r) {
        SwarmConnection c = connection(worker); if (c == null || !c.connected()) return;
        Transfer existing = transfers.get(worker); if (existing != null && existing.connection == c || transferred.get(runId(r)) == c) return;
        JsonObject envelope = TaskWire.envelope(t, worker), metadata = envelope.getAsJsonObject("dispatch"),args=metadata.getAsJsonObject("args");
        if(args.has("needs")&&args.has("primary"))metadata.add("args",dev.monocle.coordinator.StashCatalog.refillAction(MonocleClient.FOLDER.toPath(),text(t,"crew"),text(t,"server")+"\n"+text(t,"dimension"),args,worker.toString()));else if(args.has("name")&&args.has("minX"))metadata.add("args",dev.monocle.coordinator.StashCatalog.route(MonocleClient.FOLDER.toPath(),text(t,"crew"),text(t,"server")+"\n"+text(t,"dimension"),args,worker.toString()));
        String data = BotTaskData.encode(envelope);
        Transfer transfer = new Transfer(runId(r), data, hash(data), metadata, c, 0); transfers.put(worker, transfer);
        JsonObject begin = TaskWire.begin(transfer.run, data);
        r.addProperty("status", "Sending"); r.addProperty("detail", "Sending immutable workflow and profiles"); save();
        if (!c.send(begin.toString())) transfers.remove(worker);
    }
    private void flushTransfers() {
        for (var entry : List.copyOf(transfers.entrySet())) {
            Transfer t = entry.getValue(); if (!t.connection.connected()) { transfers.remove(entry.getKey()); continue; }
            int index = t.next, count = (t.data.length() + CHUNK - 1) / CHUNK;
            for (int sent = 0; sent < 4 && index < count; sent++, index++) { if (!t.connection.send(TaskWire.chunk(t.run, t.data, index).toString())) break; }
            if (index == count) { transfers.remove(entry.getKey()); transferred.put(t.run, t.connection); } else transfers.put(entry.getKey(), new Transfer(t.run, t.data, t.hash, t.metadata, t.connection, index));
        }
    }
    private void control(UUID worker, JsonObject r, String command) {
        UUID id = runId(r); long now = System.nanoTime(); if (now - commands.getOrDefault(id, 0L) < 1_000_000_000L) return;
        if (command.equals("resume") && !flag(r, "resumeSent")) {
            r.addProperty("resumeSent", true); save(); // An absent checkpoint after execution must never replay side effects.
        }
        send(worker, TaskWire.control(id, command)); commands.put(id, now);
    }
    private void send(UUID worker, JsonObject m) { SwarmConnection c = connection(worker); if (c != null && c.connected()) c.send(m.toString()); }
    private void receiveSurvey(UUID worker, JsonObject message) {
        JsonObject t = tasks.get(UUID.fromString(text(message, "task")));
        if (t == null) return; // A deleted task cannot import more observations; worker notebook remains the fallback.
        if (!text(t, "crew").equals(bots.workerCrew(worker)) || !t.getAsJsonObject("runs").has(worker.toString())) throw new IllegalArgumentException("Survey belongs to another crew");
        JsonObject r = run(t, worker);
        if (!text(r, "id").equals(text(message, "run"))) throw new IllegalArgumentException("Wrong survey execution");
        UUID.fromString(text(message, "token"));
        if (!text(r, "token").equals(text(message, "token")) || !Objects.equals(r.get("action"), message.get("action"))) return;
        JsonObject plan = BotActions.validate(message.getAsJsonObject("action"));
        if (!text(plan, "type").equals("StashHunt") || plan.get("workerIndex").getAsInt() != workers(t).indexOf(worker)
            || plan.get("workerCount").getAsInt() != workers(t).size()) throw new IllegalArgumentException("Wrong survey worker partition");
        JsonArray batch = message.getAsJsonArray("findings");
        if (batch.isEmpty() || batch.size() > BotStashHunt.BATCH) throw new IllegalArgumentException("Invalid findings batch size");
        int last = 0;
        for (JsonElement value : batch) {
            JsonObject finding = value.getAsJsonObject(); BotStashHunt.validateFinding(finding, plan);
            int delivery = integer(finding, "delivery", 1, 1_048_576);
            if (delivery <= last) throw new IllegalArgumentException("Findings are out of order"); last = delivery;
        }
        try {
            int received = text(r, "surveyToken").equals(text(message, "token")) && r.has("surveyDelivery") ? r.get("surveyDelivery").getAsInt() : 0;
            if (last > received) {
                JsonArray fresh = new JsonArray();
                for (JsonElement value : batch) if (value.getAsJsonObject().get("delivery").getAsInt() > received) fresh.add(value);
                Modules.get().get(dev.monocle.client.systems.modules.world.StashFinder.class).acceptSurvey(text(t, "server"), text(plan, "dimension"), fresh);
                r.addProperty("surveyToken", text(message, "token")); r.addProperty("surveyDelivery", last); save();
            }
            JsonObject ack = message("survey-ack"); ack.add("run", message.get("run")); ack.add("token", message.get("token")); ack.addProperty("delivery", last); send(worker, ack);
        } catch (IllegalStateException e) {
            r.addProperty("detail", "Findings retained by worker: " + e.getMessage()); dirty = true;
        }
    }
    private void handleExternal(JsonObject task, UUID worker, JsonObject r) {
        if (!r.has("action")) return;
        try { switch (text(r.getAsJsonObject("action"), "type")) {
            case "Tpa" -> prepareTeleport(task, worker, r);
            case "Highway" -> {
                if (pendingLateJoin(r)) admitPreparedWorker(task, worker, r);
                else if (flag(task, "highwayReleased")) { if (sameHighwayAction(task, worker, r)) result(worker, r, !flag(task, "cancelled") && !flag(task, "failedNative"), flag(task, "failedNative") ? "A highway worker failed; native execution safely released" : "Highway complete"); }
                else if (!task.has("highway")) startHighway(task);
            }
            default -> { }
        } } catch (IllegalArgumentException e) { result(worker, r, false, e.getMessage()); }
    }
    public boolean nativeReady(UUID worker) { JsonObject t = activeTask(worker); if (t == null || flag(t, "cancelled") || flag(t, "paused") || terminal(text(t, "status"))) return false; JsonObject r = run(t, worker); return r.has("action") && text(r.getAsJsonObject("action"), "type").equals("Highway") && text(r, "status").equals("Running"); }
    /** Return false only for legacy native jobs without a captured runtime/profile package. */
    public boolean joinHighway(String crew, UUID worker) {
        hostOnly(); load();
        SwarmCrew coordinator = bots.coordinator(crew);
        JsonObject owner = tasks.values().stream().filter(t -> t.has("highway") && text(t, "crew").equals(crew) && ownsHighway(t, coordinator)).findFirst().orElse(null);
        if (owner == null) return false;
        if (owner.getAsJsonObject("runs").has(worker.toString())) {
            if (pendingLateJoin(run(owner, worker))) return true;
            throw new IllegalStateException("This worker already has an execution of this task; use its existing task/return controls.");
        }
        if (terminal(text(owner, "status")) || flag(owner, "cancelled") || flag(owner, "highwayReleased") || !coordinator.assigned() || coordinator.inspect().phase().equals("complete"))
            throw new IllegalStateException("Choose a live, unfinished highway job first.");
        if (reserves(worker)) throw new IllegalStateException("This worker is reserved by another task or teleport.");
        if (!crew.equals(bots.workerCrew(worker)) || worker.equals(mc.player.getUUID()) || !fresh(worker)
            || coordinator.members().stream().noneMatch(m -> m.id().equals(worker) && m.connected() && m.available()))
            throw new IllegalStateException("Choose an idle connected remote worker in this crew.");
        JsonObject snapshot = coordinator.jobSnapshot(); PlayerObservation report = observation(worker);
        JsonArray preferred = snapshot.getAsJsonArray(snapshot.has("preferredMembers") ? "preferredMembers" : "members");
        long pending = owner.getAsJsonObject("runs").entrySet().stream().filter(e -> pendingLateJoin(e.getValue().getAsJsonObject()) && preferred.asList().stream().noneMatch(v -> v.getAsString().equals(e.getKey()))).count();
        if (preferred.size() + pending >= Math.min(dev.monocle.coordinator.HighwayCoordinator.MAX_CREW_MEMBERS, integer(snapshot.getAsJsonObject("layout"), "width", 1, 5))
            || workers(owner).size() >= dev.monocle.coordinator.HighwayCoordinator.MAX_CREW_MEMBERS)
            throw new IllegalStateException("No spare highway lane; existing return and join reservations are retained.");
        BlockPos front = coordinator.returnRendezvous();
        if (report == null || !report.nearby(text(snapshot, "scope"), position(front), 16, true, System.nanoTime()))
            throw new IllegalStateException("Bring the worker onto this highway floor within 16 blocks along each axis of the current front.");
        JsonObject joining = lateJoinRun(owner);
        owner.getAsJsonObject("runs").add(worker.toString(), joining);
        try { save(); } catch (RuntimeException e) { owner.getAsJsonObject("runs").remove(worker.toString()); throw e; }
        bots.info("Preparing %s with the highway task's captured workflow and profile before lane admission.", report.name());
        return true;
    }
    static JsonObject lateJoinRun(JsonObject task) {
        JsonObject packaged = task.getAsJsonObject("package");
        if (!packaged.getAsJsonObject("highways").has(text(packaged, "entry")))
            throw new IllegalStateException("Late joining is supported for native highway presets. This Lua program may already have changed profiles or transferred items; add its workers before starting instead of replaying earlier steps.");
        JsonObject reference = workers(task).stream().filter(w -> sameHighwayAction(task, w, run(task, w))).map(w -> run(task, w)).findFirst()
            .orElseThrow(() -> new IllegalStateException("Wait for an existing worker's current Highway action report before adding another."));
        JsonObject joining = new JsonObject(); joining.addProperty("id", UUID.randomUUID().toString()); joining.addProperty("status", "Queued");
        String highway=task.has("highway")?text(task,"highway"):text(task.getAsJsonObject("nativeDefinition"),"id");
        joining.addProperty("detail", "Preparing captured workflow/profile for late highway admission"); joining.addProperty("joinJob", highway);
        joining.add("joinAction", reference.getAsJsonObject("action").deepCopy());
        return joining;
    }
    static boolean pendingLateJoin(JsonObject run) { return run.has("joinJob") && !flag(run, "joinAdmitted") && !terminal(text(run, "status")); }
    private void admitPreparedWorker(JsonObject task, UUID worker, JsonObject r) {
        if (!text(r, "joinJob").equals(text(task, "highway")) || !r.getAsJsonObject("joinAction").equals(r.getAsJsonObject("action"))) {
            result(worker, r, false, "The captured late-join Highway action no longer matches its execution"); return;
        }
        // Register its distinct runtime token before admission, including the completion race
        // where the source finishes while its package is still being transferred.
        JsonObject tokens = task.getAsJsonObject("highwayTokens");
        if (!tokens.has(worker.toString())) {
            tokens.addProperty(worker.toString(), text(r, "token"));
            try { save(); } catch (RuntimeException e) { tokens.remove(worker.toString()); throw e; }
        }
        if (!sameHighwayAction(task, worker, r)) { result(worker, r, false, "Late-join action token changed unexpectedly"); return; }
        SwarmCrew coordinator = bots.coordinator(text(task, "crew"));
        if (flag(task, "highwayReleased") || !coordinator.assigned() || !ownsHighway(task, coordinator) || coordinator.inspect().phase().equals("complete")) {
            result(worker, r, false, "The highway finished before this worker could join"); return;
        }
        if (coordinator.jobSnapshot().getAsJsonArray("members").asList().stream().anyMatch(v -> v.getAsString().equals(worker.toString()))) {
            r.addProperty("joinAdmitted", true); save(); return;
        }
        if (!nativeReady(worker) || activeTask(worker) != task) return;
        if (coordinator.isParticipant(worker)) { r.addProperty("detail", "Profile prepared; synchronizing the expanded crew"); return; }
        try { coordinator.addWorker(worker); r.addProperty("detail", "Profile prepared; awaiting safe lane redistribution"); }
        catch (IllegalStateException e) { r.addProperty("detail", "Ready to join: " + e.getMessage()); }
    }
    private void startHighway(JsonObject task) {
        task.remove("nativeWait");
        if (workers(task).stream().anyMatch(w -> text(run(task, w), "status").equals("Failed"))) {
            for (UUID worker : workers(task)) { JsonObject r = run(task, worker); if (r.has("action") && text(r.getAsJsonObject("action"), "type").equals("Highway")) result(worker, r, false, "Another targeted worker failed before the highway could start"); }
            return;
        }
        String crew = text(task, "crew"); SwarmCrew coordinator = bots.coordinator(crew);
        if (flag(task, "includeHost") && (bots.crew.localAssigned() || Modules.get().get(HighwayBuilder.class).hasJob() || BotProfiles.leased() && !task.has("hostOriginal"))) {
            task.addProperty("nativeWait", "Waiting for the host's current native job and profile lease"); return;
        }
        if (coordinator.assigned() || coordinator.inspect().recovery()) { task.addProperty("nativeWait", "Waiting for this crew's current highway to finish or release"); return; }
        List<UUID> pending = workers(task).stream().filter(w -> !nativeReady(w) || activeTask(w) != task).toList();
        if (!pending.isEmpty()) { task.addProperty("nativeWait", "Waiting for " + pending.size() + " targeted workers to reach the Highway step"); return; }
        JsonObject action = run(task, workers(task).getFirst()).getAsJsonObject("action"), packaged = task.getAsJsonObject("package");
        for (UUID worker : workers(task)) if (!action.equals(run(task, worker).getAsJsonObject("action"))) throw new IllegalArgumentException("A shared Highway action must use identical arguments on every worker");
        JsonObject definition = dev.monocle.coordinator.HighwayJobs.definition(task, action);
        UUID id = UUID.fromString(text(definition,"id"));
        Set<UUID> nativeRoster = new LinkedHashSet<>(workers(task)); if (flag(task,"includeHost")) nativeRoster.add(mc.player.getUUID()); Bots.applyWorkflowDuties(definition,nativeRoster);
        task.add("nativeDefinition", definition.deepCopy()); save();
        try {
            if (flag(task, "includeHost") && (!task.has("hostOriginal") || !BotProfiles.leased())) {
                task.add("hostOriginal", BotProfiles.begin()); save(); BotProfiles.apply(packaged.getAsJsonObject("profiles").getAsJsonObject("Current"));
            }
            bots.startTaskHighway(definition, crew, new LinkedHashSet<>(workers(task)), flag(task, "includeHost"));
            task.addProperty("highway", id.toString()); task.add("highwayDefinition", definition);
            task.addProperty("historyJobId", id.toString());
            JsonObject tokens = new JsonObject(); for (UUID worker : workers(task)) tokens.addProperty(worker.toString(), text(run(task, worker), "token")); task.add("highwayTokens", tokens); save();
        } catch (RuntimeException e) {
            task.addProperty("nativeWait", "Highway start deferred: " + e.getMessage());
            if (!coordinator.assigned() && task.has("hostOriginal")) { BotProfiles.restore(); task.remove("hostOriginal"); }
            save();
        }
    }
    private void manageHighway(JsonObject task) {
        if (!task.has("highway") && !task.has("nativeDefinition") && !task.has("hostOriginal")) return;
        SwarmCrew c = bots.coordinator(text(task, "crew"));
        if (flag(task, "cancelled")) {
            UUID nativeId = BotHistory.nativeId(task);
            if (nativeId != null) { task.addProperty("historyJobId", nativeId.toString()); bots.cancelTaskHighway(nativeId); }
            if (ownsHighway(task, c)) c.endJob(); // Persists END recipients and archives supply debt before clearing ownership.
            if (task.has("hostOriginal")) { BotProfiles.restore(); task.remove("hostOriginal"); }
            task.remove("highway"); task.remove("highwayDefinition"); task.remove("highwayTokens"); task.remove("nativeDefinition");
            task.remove("highwayReleased"); task.remove("failedNative"); task.remove("highwayCompleted");
            save(); return; // Worker cancellation/cleanup continues without retaining the crew assignment.
        }
        if (!task.has("highway")) return;
        if (c.assigned() && !ownsHighway(task, c)) return;
        if (flag(task, "failedNative") && !c.independentSupplies()) releaseHighway(task);
        if (flag(task, "paused") && !flag(task, "cancelled")) { if (c.assigned() && !c.inspect().phase().equals("paused")) c.pause(); return; }
        if (flag(task, "nativeResumeRequested") && !flag(task, "failedNative") && c.assigned()
            && (c.independentSupplies() || workers(task).stream().allMatch(w -> nativeReady(w) && activeTask(w) == task)) && c.tryResume()) {
            task.remove("nativeResumeRequested"); save();
        }
        if (c.assigned() && c.roadComplete()) { if (!flag(task, "highwayCompleted")) { task.addProperty("highwayCompleted", true); save(); } releaseHighway(task); }
        if (!c.assigned() && !c.inspect().recovery() && !c.hasPendingEnds(workers(task))) {
            if (!flag(task, "highwayCompleted") && !flag(task, "cancelled")) {
                var record = bots.job(UUID.fromString(text(task, "highway")));
                if (record.progress() >= record.length()) task.addProperty("highwayCompleted", true);
                else { task.addProperty("failedNative", true); task.addProperty("detail", "Native highway ended before verified completion; inspect its saved job checkpoint"); }
            }
            if (task.has("hostOriginal")) { BotProfiles.restore(); task.remove("hostOriginal"); }
            task.addProperty("highwayReleased", true);
            for (UUID worker : workers(task)) { JsonObject r = run(task, worker); if (sameHighwayAction(task, worker, r)) result(worker, r, !pendingLateJoin(r) && !flag(task, "cancelled") && !flag(task, "failedNative"), pendingLateJoin(r) ? "Highway finished before this worker could join" : flag(task, "cancelled") ? "Highway cancelled" : flag(task, "failedNative") ? "Another highway worker failed" : "Highway complete"); }
            if (workers(task).stream().noneMatch(w -> !terminal(text(run(task, w), "status")) && (sameHighwayAction(task, w, run(task, w)) || pendingLateJoin(run(task, w))))) {
                task.remove("highway"); task.remove("highwayDefinition"); task.remove("highwayTokens"); task.remove("nativeDefinition"); task.remove("highwayReleased"); task.remove("failedNative"); task.remove("highwayCompleted");
            }
            dirty = true;
        }
    }
    private void releaseHighway(JsonObject task) {
        if (!task.has("highway")) return;
        SwarmCrew c = bots.coordinator(text(task, "crew"));
        if (c.assigned() && ownsHighway(task, c)) {
            try { c.releaseJob(); } catch (RuntimeException e) { task.addProperty("detail", "Recovering supplies: " + e.getMessage()); }
        }
    }
    private static boolean ownsHighway(JsonObject task, SwarmCrew crew) { JsonObject snapshot = crew.jobSnapshot(); String id = task.has("highway") ? text(task, "highway") : task.has("nativeDefinition") ? text(task.getAsJsonObject("nativeDefinition"), "id") : ""; return snapshot != null && id.equals(text(snapshot, "id")); }
    static boolean sameHighwayAction(JsonObject task, UUID worker, JsonObject run) { return task.has("highwayTokens") && task.getAsJsonObject("highwayTokens").has(worker.toString()) && !text(run, "token").isEmpty()
        && run.has("action") && text(run.getAsJsonObject("action"), "type").equals("Highway") && text(task.getAsJsonObject("highwayTokens"), worker.toString()).equals(text(run, "token")); }
    private void result(UUID worker, JsonObject r, boolean success, String detail) { JsonObject m = message("result"); m.addProperty("run", text(r, "id")); m.addProperty("token", text(r, "token")); m.addProperty("success", success); m.addProperty("detail", detail); send(worker, m); }
    private void returnWorker(UUID worker) {
        JsonObject reservation = returns.get(worker); if (reservation == null) return;
        SwarmCrew source = bots.coordinator(text(reservation, "crew"));
        if (!source.assigned()) { returns.remove(worker); dirty = true; return; }
        if (source.isParticipant(worker)) { returns.remove(worker); dirty = true; return; }
        if (!source.borrowReady(worker)) return;
        PlayerObservation report = observation(worker); BlockPos target = source.returnRendezvous();
        boolean nearby = report != null && report.nearby(text(source.jobSnapshot(), "scope"), position(target), 12, false, System.nanoTime());
        if (nearby) {
            JsonObject original = tasks.values().stream().filter(t -> t.has("highway") && ownsHighway(t, source) && t.getAsJsonObject("runs").has(worker.toString()) && !terminal(text(run(t, worker), "status"))).findFirst().orElse(null);
            if (original != null && !nativeReady(worker)) { control(worker, run(original, worker), "resume"); return; }
            try { source.requestReturn(worker); } catch (RuntimeException e) { reservation.addProperty("detail", e.getMessage()); } return;
        }
        if (reservation.has("task")) {
            JsonObject previous = tasks.get(UUID.fromString(text(reservation, "task")));
            if (previous != null && !terminal(text(previous, "status"))) return;
            if (previous != null && text(previous, "status").equals("Failed")) { reservation.addProperty("detail", "Return travel failed. Inspect the return task before retrying."); return; }
        }
        UUID anchor = anchor(source, worker); if (anchor == null) { reservation.addProperty("detail", "Waiting for a fresh site anchor"); return; }
        JsonObject args = new JsonObject(); args.addProperty("x", target.getX() + .5); args.addProperty("y", target.getY()); args.addProperty("z", target.getZ() + .5); args.addProperty("radius", 3);
        String sourceScope = text(source.jobSnapshot(), "scope"), dimension = sourceScope.substring(sourceScope.indexOf('\n') + 1); args.addProperty("dimension", dimension); args.addProperty("target", anchor.toString());
        if (report == null) { reservation.addProperty("detail", "Waiting for a fresh returning worker observation"); return; }
        args.addProperty("teleport", !sourceScope.equals(report.scope()));
        JsonObject packaged = packageFor(bots.workflows(), "task-travel"); JsonObject p = new JsonObject(); p.addProperty("name", "Return to highway");
        args.addProperty("warmupTicks",bots.teleportWarmupSeconds.get()*20);args.addProperty("acceptDelayTicks",Math.max(0,(bots.teleportAcceptDelayMs.get()+49)/50));
        p.addProperty("script", "return function(ctx)\n if ctx.args.teleport and not ctx.state.teleported then ctx.state.teleported=true; return bot.tpa({target=ctx.args.target, warmupTicks=ctx.args.warmupTicks, acceptDelayTicks=ctx.args.acceptDelayTicks, timeoutTicks=1200}) end\n if not ctx.state.walked then ctx.state.walked=true; return bot.travel(ctx.args) end\n return bot.done()\nend");
        packaged.getAsJsonObject("programs").add("task-return", p); packaged.addProperty("entry", "task-return");
        UUID id = createCaptured("Return to highway", "task-return", "Return to highway", text(reservation, "crew"), Set.of(worker), args, 0, Map.of(), packaged, sourceScope);
        reservation.addProperty("task", id.toString()); save();
    }
    private UUID anchor(SwarmCrew crew, UUID except) {
        JsonObject snapshot = crew.jobSnapshot(); if (snapshot == null) return null;
        List<UUID> active = snapshot.getAsJsonArray(snapshot.has("activeMembers") ? "activeMembers" : "members").asList().stream().map(v -> UUID.fromString(v.getAsString())).toList();
        Map<UUID, PlayerObservation> current = new HashMap<>();
        for (UUID id : active) { PlayerObservation report = observation(id); if (report != null) current.put(id, report); }
        return PlayerObservation.anchor(active, except, localObservation(), current, text(snapshot, "scope"), position(crew.returnRendezvous()), System.nanoTime());
    }
    private void prepareTeleport(JsonObject task, UUID worker, JsonObject r) {
        String key = text(r, "token"); UUID.fromString(key);
        if (teleports.containsKey(key)) return;
        if (r.has("tpa") && text(r.getAsJsonObject("tpa"), "token").equals(key)) {
            // An intent saved before a crash is deliberately not proof that the packet was sent.
            // Observe the worker's durable native action; never recreate /tpa or /tpaccept.
            JsonObject recovered = r.getAsJsonObject("tpa"); recovered.addProperty("recovered", true);
            teleports.put(key, recovered);
            if (!flag(r, "commandSent")) result(worker, r, false, "Teleport handshake interrupted before acknowledgment; queue a new attempt if it was not issued");
            return;
        }
        PlayerObservation requester = observation(worker);
        if (requester == null) return;
        JsonObject action = r.getAsJsonObject("action");
        PlayerObservation target = PlayerObservation.target(text(action, "target"), localObservation(), observations(text(task, "crew")).values(), System.nanoTime());
        UUID targetId = target == null ? null : target.id(); String targetName = target == null ? "" : target.name(), targetScope = target == null ? "" : target.scope();
        if (targetId == null || targetId.equals(worker) || !targetName.matches("[A-Za-z0-9_]{1,16}")) { result(worker, r, false, "TPA target must be an online crewmate or the host"); return; }
        if (!sameTeleportServer(requester.scope(), targetScope)) { result(worker, r, false, "TPA requester and target must be on the same Minecraft server"); return; }
        String requesterName = requester.name();
        if (!requesterName.matches("[A-Za-z0-9_]{1,16}")) { result(worker, r, false, "TPA requester has no valid player identity"); return; }
        JsonObject tpa = new JsonObject(); tpa.addProperty("run", text(r, "id")); tpa.addProperty("token", key); tpa.addProperty("worker", worker.toString()); tpa.addProperty("target", targetId.toString());
        tpa.addProperty("name", targetName); tpa.addProperty("dimension", targetScope.substring(targetScope.indexOf('\n') + 1));
        tpa.addProperty("requester", requesterName); tpa.addProperty("requesterScope", requester.scope()); tpa.addProperty("targetScope", targetScope);
        tpa.addProperty("warmup", integer(action, "warmupTicks", 0, 72_000));
        tpa.addProperty("acceptDelay",integer(action,"acceptDelayTicks",0,200));
        tpa.addProperty("deadline", System.currentTimeMillis() + integer(action, "timeoutTicks", 1, 1_728_000) * 50L);
        tpa.addProperty("sendIntent", true); r.add("tpa", tpa);
        save(); // Intent first: a crash between the checkpoint and packet is safe, not replayable.
        teleports.put(key, tpa);
        JsonObject command = tpa.deepCopy(); command.addProperty("type", "task-tpa-send"); send(worker, command);
    }
    private void tickTeleports() {
        for (var entry : List.copyOf(teleports.entrySet())) {
            JsonObject t = entry.getValue(); UUID worker = UUID.fromString(text(t, "worker")); JsonObject active = activeTask(worker);
            long now = System.currentTimeMillis();
            if (active == null) { teleports.remove(entry.getKey()); continue; }
            JsonObject r = run(active, worker);
            if (!teleportPending(t, r, now)) {
                if (text(r, "token").equals(entry.getKey()) && !flag(r, "commandSent")) result(worker, r, false, "Teleport request was not acknowledged before timeout");
                teleports.remove(entry.getKey()); continue;
            }
            if (flag(active, "cancelled") || Set.of("Cancelled", "Failed").contains(text(r, "requestedStatus"))) continue;
            if (flag(t, "recovered") || flag(t, "accepted") || !flag(r, "commandSent")) continue;
            if (!t.has("acknowledgedAt")) { t.addProperty("acknowledgedAt", now); save(); }
            if (!teleportWarmupReady(t, now)) continue;
            UUID target = UUID.fromString(text(t, "target"));
            PlayerObservation local = localObservation(), requester = observation(worker);
            boolean localTarget = local != null && target.equals(local.id());
            PlayerObservation targetReport = localTarget ? local : observation(target);
            String currentTargetScope = targetReport == null ? "" : targetReport.scope();
            if (requester == null || !text(t, "requester").equals(requester.name())
                || !sameTeleportServer(requester.scope(), currentTargetScope) || !currentTargetScope.equals(text(t, "targetScope"))) continue;
            if (!localTarget && !text(active, "crew").equals(bots.workerCrew(target))) continue;
            JsonObject accept = message("tpa-accept"); accept.addProperty("token", entry.getKey()); accept.addProperty("requester", text(t, "requester"));
            accept.addProperty("requesterId", worker.toString()); accept.addProperty("target", target.toString());
            accept.addProperty("requesterScope", requester.scope()); accept.addProperty("targetScope", currentTargetScope); accept.addProperty("expires", now + 10_000);
            t.addProperty("accepted", true); save(); // Acceptance is at-most-once across host reconnect/restart.
            if (localTarget) acceptTeleport(accept); else send(target, accept);
        }
    }

    public void requestAutoTpy(UUID requester, String crew, JsonObject message) {
        if (!bots.autoTpy.get()) return;
        UUID request = UUID.fromString(text(message, "request"));
        if (autoTpy.containsKey(request)) return;
        if (autoTpy.size() >= 64) throw new IllegalStateException("Too many pending Auto TPY requests");
        PlayerObservation observation = observation(requester);
        if (observation == null || !observation.scope().equals(text(message, "scope"))) return;
        queueAutoTpy(request, observation, crew, text(message, "target"));
    }

    public void requestLocalAutoTpy(String target) {
        PlayerObservation local = localObservation();
        if (local == null) return;
        for (String crew : bots.presets().stream().map(Bots.CrewPreset::name).toList()) if (bots.coordinator(crew).localAssigned()) {
            queueAutoTpy(UUID.randomUUID(), local, crew, target); return;
        }
    }

    private void queueAutoTpy(UUID request, PlayerObservation requester, String crew, String targetName) {
        if (!targetName.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Invalid TPA target");
        List<PlayerObservation> candidates = new ArrayList<>(observations(crew).values());
        PlayerObservation local = localObservation();
        if (local != null && bots.coordinator(crew).localAssigned()) candidates.add(local);
        List<PlayerObservation> matches = candidates.stream().filter(p -> !p.id().equals(requester.id()) && p.name().equalsIgnoreCase(targetName)).toList();
        if (matches.size() != 1 || !sameTeleportServer(requester.scope(), matches.getFirst().scope())) return;
        long now = System.currentTimeMillis();
        autoTpy.put(request, new AutoTpy(requester.id(), matches.getFirst().id(), requester.name(), crew, requester.scope(), now + 500, now + 10_000));
    }

    private void tickAutoTpy() {
        long now = System.currentTimeMillis();
        for (var entry : List.copyOf(autoTpy.entrySet())) {
            AutoTpy request = entry.getValue();
            if (!bots.autoTpy.get() || now >= request.expires()) { autoTpy.remove(entry.getKey()); continue; }
            if (now < request.due()) continue;
            PlayerObservation requester = request.requester().equals(mc.player.getUUID()) ? localObservation() : observation(request.requester());
            boolean localTarget = request.target().equals(mc.player.getUUID());
            PlayerObservation target = localTarget ? localObservation() : observation(request.target());
            boolean targetInCrew = localTarget ? bots.coordinator(request.crew()).localAssigned() : request.crew().equals(bots.workerCrew(request.target()));
            if (requester == null || target == null || !targetInCrew
                || !request.requesterName().equals(requester.name()) || !request.requesterScope().equals(requester.scope()) || !sameTeleportServer(requester.scope(), target.scope())) {
                autoTpy.remove(entry.getKey()); continue;
            }
            JsonObject accept = message("tpa-accept"); accept.addProperty("token", entry.getKey().toString()); accept.addProperty("requester", requester.name());
            accept.addProperty("requesterId", requester.id().toString()); accept.addProperty("target", target.id().toString());
            accept.addProperty("requesterScope", requester.scope()); accept.addProperty("targetScope", target.scope()); accept.addProperty("expires", request.expires());
            boolean sent;
            if (localTarget) {
                acceptCrewTeleport(accept); sent = true;
            } else {
                SwarmConnection connection = connection(request.target()); sent = connection != null && connection.connected() && connection.send(accept.toString());
            }
            if (sent) { autoTpy.remove(entry.getKey()); bots.info("Auto TPY accepted %s's request to %s.", requester.name(), target.name()); }
        }
    }
    public void acceptCrewTeleport(JsonObject message) {
        String token = text(message, "token"); UUID.fromString(token);
        autoTpyAccepting.add(token);
        try { acceptTeleport(message); }
        finally { autoTpyAccepting.remove(token); }
    }
    private void acceptTeleport(JsonObject m) {
        if (!Utils.canUpdate() || !text(m, "target").equals(mc.player.getUUID().toString())) return;
        String token = text(m, "token"), name = text(m, "requester"); UUID.fromString(token); UUID.fromString(text(m, "requesterId"));
        if (bots.mode.get() == Bots.Mode.Worker && (!bots.isWorker() || !bots.acceptCrew.get())) return;
        if (bots.mode.get() == Bots.Mode.Host && !autoTpyAccepting.contains(token) && (!teleports.containsKey(token) || !flag(teleports.get(token), "accepted"))) return;
        long now = System.currentTimeMillis(), expires = m.get("expires").getAsBigDecimal().longValueExact();
        if (!name.matches("[A-Za-z0-9_]{1,16}") || !validTeleportTtl(now, expires)
            || !scope().equals(text(m, "targetScope")) || !sameTeleportServer(scope(), text(m, "requesterScope"))) return;
        loadAcceptances();
        accepted.entrySet().removeIf(e -> e.getValue() < now);
        if (accepted.containsKey(token)) return;
        if (accepted.size() >= 256) throw new IllegalStateException("Too many pending teleport acceptances; wait for their short expiry");
        accepted.put(token, expires);
        JsonObject journal = new JsonObject(); accepted.forEach(journal::addProperty);
        write(acceptanceFile, journal); // A target restart cannot turn the same acceptance into a second command.
        mc.getConnection().sendCommand("tpy " + name);
    }
    private void loadAcceptances() {
        if (acceptedLoaded) return;
        JsonObject saved = read(acceptanceFile);
        if (saved.size() > 256) throw new IllegalArgumentException("Too many saved teleport acceptances");
        long now = System.currentTimeMillis();
        for (var entry : saved.entrySet()) {
            UUID.fromString(entry.getKey()); long expiry = entry.getValue().getAsBigDecimal().longValueExact();
            if (validTeleportTtl(now, expiry)) accepted.put(entry.getKey(), expiry);
        }
        acceptedLoaded = true;
    }
    /** A pending TPA recipient stays available as the requested anchor until observation or timeout. */
    public boolean teleportPinned(UUID target) {
        if (bots.mode.get() != Bots.Mode.Host) return false;
        load(); long now = System.currentTimeMillis();
        for (JsonObject task : tasks.values()) for (var entry : task.getAsJsonObject("runs").entrySet()) {
            JsonObject r = entry.getValue().getAsJsonObject();
            if (r.has("tpa") && target.toString().equals(text(r.getAsJsonObject("tpa"), "target")) && teleportPending(r.getAsJsonObject("tpa"), r, now)) return true;
        }
        return false;
    }
    static boolean teleportPending(JsonObject tpa, JsonObject run, long now) {
        return QueuePolicy.teleportPending(tpa, run, now);
    }
    static boolean sameTeleportServer(String first, String second) {
        return QueuePolicy.sameTeleportServer(first, second);
    }
    static boolean validTeleportTtl(long now, long expiry) { return QueuePolicy.validTeleportTtl(now, expiry); }
    static boolean teleportWarmupReady(JsonObject tpa, long now) {
        return QueuePolicy.teleportWarmupReady(tpa, now);
    }
    private void summarize(JsonObject t) {
        if (terminal(text(t, "status"))) return;
        String state = QueuePolicy.summarizedStatus(t);
        String detail = String.join(" · ", t.getAsJsonObject("runs").entrySet().stream().map(e -> {
            UUID id = UUID.fromString(e.getKey()); JsonObject r = e.getValue().getAsJsonObject(); String name = reports.containsKey(id) ? reports.get(id).name() : id.toString().substring(0, 8);
            return name + ": " + text(r, "status") + " — " + text(r, "detail");
        }).toList());
        if (!state.equals(text(t, "status"))) { t.addProperty("status", state); dirty = true; if (terminal(state)) bots.info("%s: %s", text(t, "name"), state); }
        detail = t.has("nativeWait") && !t.has("highway") && !terminal(state) ? text(t, "nativeWait") + " · " + detail : detail;
        if (!detail.equals(text(t, "detail"))) { t.addProperty("detail", detail); dirty = true; }
        dirty |= BotHistory.stamp(t, deletable(UUID.fromString(text(t, "id")), t), System.currentTimeMillis());
    }
}
