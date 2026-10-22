package dev.monocle.client.systems.bots;

import com.google.gson.*;
import dev.monocle.coordinator.TaskWire;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.client.systems.modules.world.PrinterHelper;
import dev.monocle.client.utils.Utils;
import net.minecraft.core.registries.BuiltInRegistries;
import java.nio.file.Path;
import java.util.*;
import static dev.monocle.client.MonocleClient.mc;
import static dev.monocle.client.systems.bots.BotTaskData.*;

/** Worker-only resumable Lua frames. All Minecraft access and decisions run on its client thread. */
final class BotRuntime {
    private final Bots bots;
    private final BotActions actions;
    private CrystalFightRecorder fightRecorder;
    private final Path file = MonocleClient.FOLDER.toPath().resolve("bot-worker-tasks.json");
    private final Map<UUID, JsonObject> runs = new LinkedHashMap<>();
    private UUID current;
    private UUID landingResume;
    private BotActions profileLanding;
    private boolean loaded, recoveryRestored, connectionSuspended;
    private String failure, requested = "", requestedDetail = "", actionLoaded = "";
    private JsonObject interruptedOriginal;
    private int ticks, surveySendCursor;
    private String lastStashStatus = "";
    private String lastStashFinding = "";
    private long lastStashFindingSentAt;
    private String scanUploadToken="";
    private int scanUploaded;
    private long scanUploadRetryAt;
    private String homeSentToken = "";
    private String tpaReplyToken="",tpaReplyCommand="";
    private volatile String lastCommand="";
    private long nextHistoryCleanup;
    BotRuntime(Bots bots) { this.bots = bots; actions = new BotActions(bots); }
    private void load() {
        if (failure != null) throw new IllegalStateException(failure);
        if (loaded) return;
        try {
            JsonObject root = read(file);
            if (!root.isEmpty()) {
                integer(root, "version", 1, 1);
                JsonObject records = root.getAsJsonObject("runs");
                if (records.size() > 64) throw new IllegalArgumentException("Too many worker checkpoints");
                for (var entry : records.entrySet()) {
                    UUID id = UUID.fromString(entry.getKey()); JsonObject run = entry.getValue().getAsJsonObject().deepCopy();
                    validateCheckpoint(id, run);
                    if (terminal(text(run, "status")) && hasUncertainAction(run)) { run.addProperty("requestedStatus", text(run, "status")); run.addProperty("requestedDetail", "Reconcile the interrupted native action before finalizing"); }
                    if (!terminal(text(run, "status")) || hasUncertainAction(run)) { run.addProperty("status", "Inspection required"); run.addProperty("detail", "Restarted: host must Resume after checking the saved action"); }
                    runs.put(id, run);
                }
                if (root.has("original")) interruptedOriginal = BotProfiles.validate(root.getAsJsonObject("original"));
            }
            loaded = true;
            boolean migrated = false;
            for (JsonObject run : runs.values()) migrated |= BotHistory.stamp(run, terminal(text(run, "status")) && !hasUncertainAction(run), System.currentTimeMillis());
            if (migrated) save();
        } catch (RuntimeException e) { failure = e.getMessage(); throw e; }
    }
    private void save() {
        for (JsonObject run : runs.values()) BotHistory.stamp(run, terminal(text(run, "status")) && !hasUncertainAction(run), System.currentTimeMillis());
        JsonObject root = new JsonObject(), records = new JsonObject(); root.addProperty("version", 1);
        for (var e : runs.entrySet()) records.add(e.getKey().toString(), e.getValue());
        root.add("runs", records);
        if (BotProfiles.leased()) root.add("original", BotProfiles.originalSnapshot());
        else if (interruptedOriginal != null) root.add("original", interruptedOriginal.deepCopy());
        write(file, root);
    }
    boolean hasWork() { load(); return runs.values().stream().anyMatch(r -> !terminal(text(r, "status"))); }
    void discardStaleRecovery() {
        load();
        if (bots.crew.localAssigned()) throw new IllegalStateException("End the active crew job before discarding recovery records");
        BotSupplyRecovery.discardCurrent();
        bots.crew.discardPreviousTransfer();
    }
    boolean ownsControls() { return current != null || landingResume != null; }
    boolean ownsScanRead(net.minecraft.core.BlockPos pos){return current!=null&&actions.ownsScanRead(pos);}
    boolean allowsNative() {
        load();
        if(landingResume!=null)return false;
        if (current == null) return runs.values().stream().noneMatch(BotRuntime::blocksNewWork);
        return requested.isEmpty() && top() != null && top().has("action") && text(top().getAsJsonObject("action"), "type").equals("Highway") && actions.recoveryReady();
    }
    List<JsonObject> views() { load(); return runs.values().stream().map(JsonObject::deepCopy).toList(); }
    private JsonObject receivedRun(UUID task) {
        load();
        if(current!=null&&text(run(),"task").equals(task.toString()))return run();
        return runs.values().stream().filter(r->text(r,"task").equals(task.toString())).reduce((older,newer)->newer).orElseThrow(()->new IllegalArgumentException("Unknown received job"));
    }
    JsonObject configuration(UUID task) {
        JsonObject run = receivedRun(task);
        JsonObject view = new JsonObject(), workers = new JsonObject();
        view.add("package",run.get("package")); workers.add(mc.getUser().getProfileId().toString(),run); view.add("runs",workers);
        JsonObject result = dev.monocle.coordinator.TaskConfiguration.inspect(view);
        result.addProperty("attribution", "Received host-defined job: " + text(run,"name") + " · host " + (text(run,"sourceHost").isEmpty()?"not recorded on this older job":text(run,"sourceHost")) + " · crew " + text(run,"crew") + " · task " + task);
        return result;
    }
    JsonObject compare(UUID task, String profile, String module) {
        return compareRun(receivedRun(task),profile,module);
    }
    private JsonObject compareRun(JsonObject run, String profile, String module) {
        JsonObject personal=BotProfiles.leased()?BotProfiles.originalSnapshot():interruptedOriginal;
        if (profile.equals("Latest live request / worker")) {
            if(!run.has("configuration"))throw new IllegalArgumentException("No retained live request on this worker; send a new edit first");
            JsonObject report=BotProfiles.compareModule(run.getAsJsonObject("configuration").getAsJsonObject("modules"),module,personal);
            report.addProperty("note","Latest live request, revision "+text(run.getAsJsonObject("configuration"),"revision")+". Only this patch is shown, not a cumulative effective profile. "+text(run,"configError")+" "+report.get("note").getAsString());return report;
        }
        JsonObject profiles = run.getAsJsonObject("package").getAsJsonObject("profiles");
        if (!profiles.has(profile)) throw new IllegalArgumentException("Unknown received profile");
        return BotProfiles.compareModule(profiles.getAsJsonObject(profile), module, personal);
    }
    void readConfiguration(JsonObject request, String owner) {
        load(); UUID id = UUID.fromString(text(request,"run")); JsonObject run = runs.get(id);
        if (run == null || !ownedBy(run,owner) || !text(run,"task").equals(text(request,"task"))) return;
        UUID.fromString(text(request,"request"));
        JsonObject report;
        try {
            if(text(request,"profile").equals("Latest live request / worker")&&(!run.has("configuration")||!text(run.getAsJsonObject("configuration"),"revision").equals(text(request,"configurationRevision"))))
                throw new IllegalStateException("The requested live revision is not retained on this worker yet. Check its acknowledgement, then request again.");
            report = compareRun(run,text(request,"profile"),text(request,"module"));
        }
        catch (RuntimeException e) { report = new JsonObject(); report.addProperty("note",bounded("Readback unavailable: " + e.getMessage())); report.add("rows",new JsonArray()); }
        for (JsonObject response : dev.monocle.coordinator.ConfigurationReadback.replies(request,report)) send(response);
    }
    static boolean terminal(String status) { return dev.monocle.coordinator.QueuePolicy.terminal(status); }
    static boolean ignoresControl(String status,String command) { return terminal(status) && !(status.equals("Failed") && command.equals("cancel")); }
    void install(UUID id, JsonObject metadata, JsonObject packaged, String owner) {
        load();
        if (!bots.acceptCrew.get()) throw new IllegalStateException("Trusted host task execution is disabled on this worker");
        JsonObject existing = runs.get(id);
        if (existing != null) {
            if (!owner.equals(text(existing, "owner")) || !hash(packaged.toString()).equals(hash(existing.getAsJsonObject("package").toString()))) throw new IllegalArgumentException("Task run identity was reused");
            for (String key : List.of("task", "crew", "args", "server", "dimension", "workerIndex", "workerCount", "supportedActions")) if ((existing.has(key) || key.equals("supportedActions")) && !Objects.equals(metadata.get(key), existing.get(key))) throw new IllegalArgumentException("Task run arguments or assignment changed");
            sendStatus(id); return;
        }
        JsonObject run = metadata.deepCopy();
        UUID.fromString(text(run, "task")); BotJobs.name(text(run, "name"));
        JsonObject args = run.has("args") ? run.getAsJsonObject("args") : new JsonObject();
        if (args.toString().length() > BotLua.MAX_STATE) throw new IllegalArgumentException("Task arguments are too large");
        run.addProperty("run", id.toString()); run.addProperty("owner", owner);
        run.addProperty("sourceHost",bots.ipAddress.get());
        run.add("package", checkedPackage(packaged)); run.addProperty("status", "Ready"); run.addProperty("detail", "Package validated; waiting for host to start");
        JsonArray stack = new JsonArray(); stack.add(frame(text(packaged, "entry"), args)); run.add("stack", stack);
        run.remove("effectiveProfile"); run.remove("requestedStatus"); run.remove("requestedDetail");
        validateCheckpoint(id, run);
        UUID old = retirementCandidate(runs, current);
        JsonObject retired = old == null ? null : runs.remove(old);
        runs.put(id, run);
        try { save(); } catch (RuntimeException e) { runs.remove(id); if (old != null) runs.put(old, retired); throw e; }
        sendStatus(id);
    }
    static UUID retirementCandidate(Map<UUID, JsonObject> runs, UUID current) {
        return runs.size() < 64 ? null : runs.entrySet().stream()
            .filter(e -> !e.getKey().equals(current) && terminal(text(e.getValue(), "status")) && !hasUncertainAction(e.getValue()))
            .map(Map.Entry::getKey).findFirst().orElseThrow(() -> new IllegalStateException("Worker checkpoint queue is full; active work and unresolved receipts are retained for inspection"));
    }
    static JsonObject frame(String program, JsonObject args) {
        JsonObject f = new JsonObject(); f.addProperty("program", program); f.add("args", args.deepCopy()); f.add("state", new JsonObject()); f.add("result", new JsonObject()); return f;
    }
    static String savedFinish(JsonObject run) {
        String status = text(run, "requestedStatus");
        if (!status.isEmpty() && !Set.of("Complete", "Cancelled", "Failed", "Suspended").contains(status)) throw new IllegalArgumentException("Invalid pending task outcome");
        return status;
    }
    static boolean uncertainTeleport(JsonObject frame) {
        return frame != null && frame.has("action") && text(frame.getAsJsonObject("action"), "type").equals("Tpa")
            && (frame.has("commandSent") && frame.get("commandSent").getAsBoolean()
                || frame.has("native") && frame.getAsJsonObject("native").has("tpaSent") && frame.getAsJsonObject("native").get("tpaSent").getAsBoolean());
    }
    static boolean hasUncertainAction(JsonObject run) {
        JsonObject frame = top(run);
        if (frame == null || !frame.has("native")) return uncertainTeleport(frame);
        JsonObject nativeState = frame.getAsJsonObject("native");
        return nativeState.has("pendingDrop") || pendingStashMove(nativeState)
            || text(nativeState, "state").equals("Running") && uncertainTeleport(frame);
    }
    private static boolean pendingStashMove(JsonObject nativeState) {
        for (String key : List.of("stashResupply", "stashDeposit")) if (nativeState.has(key)) {
            JsonObject stash = nativeState.getAsJsonObject(key);
            for (String flag : List.of("withdrawPending", "depositPending", "discardPending", "pending"))
                if (stash.has(flag) && stash.get(flag).getAsBoolean()) return true;
        }
        return false;
    }
    static boolean needsActionRestore(JsonObject frame, String loadedToken, String outcome) {
        if (frame == null || !frame.has("action") || text(frame, "token").equals(loadedToken)) return false;
        // Cancel retains uncertain stash receipts, but must not restore/replay their failed move.
        if (outcome.equals("Cancelled") && frame.has("native") && pendingStashMove(frame.getAsJsonObject("native"))) return false;
        return outcome.isEmpty() || frame.has("native");
    }
    static boolean nativeFinished(JsonObject snapshot) { return Set.of("Complete", "Failed").contains(text(snapshot, "state")); }
    static Optional<JsonObject> unresolvedOther(Map<UUID, JsonObject> runs, UUID next) {
        return runs.entrySet().stream().filter(e -> !e.getKey().equals(next) && blocksNewWork(e.getValue())).map(Map.Entry::getValue).findFirst();
    }
    /** A host-cancelled record remains durable for later inspection, but cannot strand future work. */
    static boolean blocksNewWork(JsonObject run) {
        return hasUncertainAction(run) && !text(run, "requestedStatus").equals("Cancelled") && !text(run, "status").equals("Cancelled");
    }
    static boolean sameServer(JsonObject run, String server) { return !run.has("server") || text(run, "server").equalsIgnoreCase(server); }
    private String server() { return mc.getCurrentServer() == null ? "local" : mc.getCurrentServer().ip; }
    static void validateCheckpoint(UUID id, JsonObject run) {
        if(run.has("controlId"))UUID.fromString(text(run,"controlId"));
        if (!id.toString().equals(text(run, "run"))) throw new IllegalArgumentException("Checkpoint run identity mismatch");
        UUID.fromString(text(run, "task")); BotJobs.name(text(run, "name"));
        if (text(run, "owner").isBlank() || text(run, "owner").length() > 128) throw new IllegalArgumentException("Missing checkpoint owner");
        if (run.has("server") && (text(run, "server").isBlank() || text(run, "server").length() > 1024 || text(run, "server").chars().anyMatch(Character::isISOControl))) throw new IllegalArgumentException("Invalid task server");
        if (!Set.of("Ready", "Running", "Suspending", "Suspended", "Inspection required", "Complete", "Cancelled", "Failed").contains(text(run, "status"))) throw new IllegalArgumentException("Invalid checkpoint status");
        JsonObject packaged = checkedPackage(run.getAsJsonObject("package"));
        if (run.has("supportedActions")) {
            JsonArray supported = run.getAsJsonArray("supportedActions");
            if (supported == null || supported.isEmpty() || supported.size() > 14) throw new IllegalArgumentException("Invalid host capabilities");
            for (JsonElement action : supported) if (!Set.of("Travel", "StashHunt", "StashScan", "StashVisit", "StashResupply", "StashDeposit", "KitTransferPlan", "StashSortPlan", "DropItems", "Wait", "Modules", "Tpa", "SetProfile", "Highway", "RecoverSupplies").contains(action.getAsString())) throw new IllegalArgumentException("Unknown host capability");
        }
        String pending = savedFinish(run);
        JsonArray stack = run.getAsJsonArray("stack");
        if (stack == null || stack.size() > 16 || stack.isEmpty() && !terminal(text(run, "status")) && !terminal(pending)) throw new IllegalArgumentException("Invalid workflow call stack");
        for (JsonElement element : stack) {
            JsonObject frame = element.getAsJsonObject();
            if (!packaged.getAsJsonObject("programs").has(text(frame, "program"))) throw new IllegalArgumentException("Unbundled checkpoint workflow");
            for (String key : List.of("state", "args", "result")) if (!frame.has(key) || !frame.get(key).isJsonObject() || frame.get(key).toString().length() > BotLua.MAX_STATE)
                throw new IllegalArgumentException("Invalid checkpoint " + key);
            if (frame.has("action")) {
                if (frame != stack.get(stack.size() - 1)) throw new IllegalArgumentException("Only the active frame may own a native action");
                JsonObject action = BotActions.validate(frame.getAsJsonObject("action")); UUID.fromString(text(frame, "token"));
                if (frame.has("commandSent") && (!text(action, "type").equals("Tpa") || !frame.get("commandSent").getAsJsonPrimitive().isBoolean())) throw new IllegalArgumentException("Unexpected teleport command checkpoint");
                if (frame.has("profileApplied") && (!text(action, "type").equals("SetProfile") || !frame.get("profileApplied").getAsJsonPrimitive().isBoolean())) throw new IllegalArgumentException("Unexpected profile checkpoint");
                if (frame.has("native")) {
                    JsonObject nativeState = frame.getAsJsonObject("native"); integer(nativeState, "version", 1, 1);
                    if (!action.equals(BotActions.validate(nativeState.getAsJsonObject("action")))) throw new IllegalArgumentException("Native checkpoint belongs to a different action");
                }
            } else if (frame.has("native") || frame.has("token") || frame.has("commandSent") || frame.has("profileApplied")) throw new IllegalArgumentException("Orphaned native checkpoint");
        }
        if (run.has("effectiveProfile")) BotProfiles.validate(run.getAsJsonObject("effectiveProfile"));
    }
    private JsonObject run() { return runs.get(current); }
    private JsonObject top() { return top(run()); }
    static JsonObject top(JsonObject run) {
        if (run == null) return null;
        JsonArray stack = run.getAsJsonArray("stack"); return stack.isEmpty() ? null : stack.get(stack.size() - 1).getAsJsonObject();
    }
    void control(UUID id, String command, String owner) {
        control(id,command,owner,"");
    }
    void control(UUID id, String command, String owner,String controlId) {
        load(); JsonObject run = runs.get(id);
        if (run == null) return;
        if (!owner.equals(text(run, "owner"))) throw new IllegalArgumentException("This host does not own the saved task");
        if(!Set.of("pause","resume","cancel").contains(command))throw new IllegalArgumentException("Invalid task control");
        if(id.equals(landingResume)&&(!command.equals("resume")||grounded()))clearProfileLanding();
        if(!controlId.isEmpty()){UUID.fromString(controlId);if(!controlId.equals(text(run,"controlId"))){run.addProperty("controlId",controlId);save();}}
        if (ignoresControl(text(run, "status"),command)) { sendStatus(id); return; }
        if (text(run,"status").equals("Failed") && !run.has("failureDetail")) run.addProperty("failureDetail",text(run,"detail"));
        switch (command) {
            case "resume" -> {
                if (!Utils.canUpdate() || !dev.monocle.client.utils.network.WorldReadiness.INSTANCE.ready() || !sameServer(run, server())) { run.addProperty("detail", "Wait for the task's original Minecraft world and local chunk before resuming"); sendStatus(id); return; }
                if (current != null && !current.equals(id)) { run.addProperty("detail", "Waiting for the current task to finish its safe handoff"); sendStatus(id); return; }
                if (!bots.acceptCrew.get()) throw new IllegalStateException("Worker has disabled task execution");
                if (run.has("actionProgress") && text(run.getAsJsonObject("actionProgress"),"state").equals("Paused") && !savedFinish(run).equals("Cancelled")) {
                    run.remove("actionProgress"); run.remove("progressToken");
                    if (top(run) != null && top(run).has("native")) top(run).getAsJsonObject("native").remove("actionProgress");
                    if (id.equals(current)) actions.resumeProgress();
                }
                if (current == null) {
                    if(landingResume!=null&&!id.equals(landingResume)){run.addProperty("detail","Waiting for another task's landing recovery");sendStatus(id);return;}
                    Optional<JsonObject> unfinished = unresolvedOther(runs, id);
                    if (unfinished.isPresent()) { run.addProperty("detail", "Reconcile the recorded drop/teleport in '" + bounded(text(unfinished.get(), "name")) + "' before starting another task"); sendStatus(id); return; }
                    if (nativeBusy()) { run.addProperty("detail", "Waiting for the source highway to release this worker"); sendStatus(id); return; }
                    if (!grounded()) {
                        landingResume=id;if(profileLanding==null)profileLanding=new BotActions(bots);
                        run.addProperty("detail", "Landing safely before applying a task profile"); sendStatus(id); return;
                    }
                    if (interruptedOriginal != null || !recoveryRestored) { run.addProperty("detail", "Waiting for original profile recovery before resuming"); sendStatus(id); return; }
                    actions.bindTask(id);
                    current = id; requested = savedFinish(run); requestedDetail = text(run, "requestedDetail"); actionLoaded = "";
                    run.addProperty("status", "Running"); run.addProperty("detail", "Resuming workflow");
                    try {
                        BotProfiles.begin(); save();
                        JsonObject profile=(run.has("effectiveProfile") ? run.getAsJsonObject("effectiveProfile") : run.getAsJsonObject("package").getAsJsonObject("profiles").getAsJsonObject("Current")).deepCopy();
                        if(text(run.getAsJsonObject("package"),"entry").equals("task-crystal-guard"))for(String module:List.of("crystal-aura","kill-aura","anchor-aura","bed-aura")){
                            JsonObject value=profile.has(module)?profile.getAsJsonObject(module):new JsonObject();value.addProperty("active",false);
                            if(!value.has("settings"))value.addProperty("settings","{}");profile.add(module,value);
                        }
                        BotProfiles.apply(profile);
                    }
                    catch (RuntimeException e) { finish("Failed", "Profile could not be applied: " + e.getMessage()); return; }
                }
                if (fightRecorder == null && text(run.getAsJsonObject("package"), "entry").equals("task-crystal-guard")) {
                    try {
                        fightRecorder = CrystalFightRecorder.start(UUID.fromString(text(run, "task")), text(run, "server"), text(run, "dimension"), run.getAsJsonObject("args"));
                        run.addProperty("fightLog", fightRecorder.path().toString()); save();
                        bots.info("Crystal Guard fight log: %s", fightRecorder.path());
                    } catch (Exception | LinkageError e) { finish("Failed", "Crystal fight recorder could not start: " + e.getMessage()); return; }
                }
                if (requested.equals("Suspended")) {
                    connectionSuspended = false;
                    requested = requestedDetail = ""; run.remove("requestedStatus"); run.remove("requestedDetail");
                    if (top() != null && text(top(), "token").equals(actionLoaded) && !actionLoaded.isEmpty()) actions.resume();
                    run.addProperty("status", "Running"); run.addProperty("detail", "Resuming workflow"); save();
                }
            }
            case "pause", "cancel" -> {
                if (current != null && current.equals(id)) {
                    connectionSuspended = false;
                    finish(command.equals("cancel") ? "Cancelled" : "Suspended", command.equals("cancel") ? "Cancelled by host" : "Paused by host");
                }
                else {
                    // An interrupted destructive action still needs reconciliation; cancelling its
                    // queue entry is not permission to pretend its packet was never sent.
                    if (command.equals("cancel") && hasUncertainAction(run)) {
                        run.addProperty("requestedStatus", "Cancelled"); run.addProperty("requestedDetail", "Cancelled by host; reconcile the recorded action first");
                        run.addProperty("status", "Inspection required"); run.addProperty("detail", "Resume to settle the recorded action without replaying it, then cancellation completes");
                    } else { run.addProperty("status", command.equals("cancel") ? "Cancelled" : "Suspended"); run.addProperty("detail", command.equals("cancel") ? "Cancelled by host" : "Paused by host"); }
                    save();
                }
            }
            default -> throw new IllegalArgumentException("Unknown task control");
        }
        sendStatus(id);
    }
    void disconnected() {
        tpaReplyToken=tpaReplyCommand=lastCommand="";
        clearProfileLanding();
        if (current == null) return;
        closeFightRecorder();
        actions.disconnected();
        if (requested.isEmpty()) {
            connectionSuspended = top() != null && top().has("action") && text(top().getAsJsonObject("action"), "type").equals("Highway");
            finish("Suspended", "Connection lost; safely checkpointing");
        }
    }
    private boolean grounded() { return Utils.canUpdate() && mc.player.onGround() && !mc.player.isFallFlying(); }
    private void clearProfileLanding(){if(profileLanding!=null)profileLanding.stop();profileLanding=null;landingResume=null;}
    private void tickProfileLanding(){
        UUID id=landingResume;JsonObject pending=runs.get(id);
        if(pending==null||!bots.isWorker()||!bots.acceptCrew.get()||!ownedBy(pending,bots.worker.credentialId())||nativeBusy()
            ||!sameServer(pending,server())||!text(pending,"dimension").equals(mc.level.dimension().identifier().toString())){clearProfileLanding();return;}
        try{
            boolean landed=profileLanding.landForProfile();
            pending.addProperty("detail",landed?"Landed; waiting for the host's Resume":"Landing before task profile · "+bounded(profileLanding.detail()));
            if(landed)clearProfileLanding();
        }catch(RuntimeException error){clearProfileLanding();pending.addProperty("detail","Landing recovery stopped: "+bounded(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()));}
        if(ticks%20==0)sendStatus(id);
    }
    private boolean nativeBusy() { return bots.crew.localAssigned() || Modules.get().get(HighwayBuilder.class).hasJob() || Modules.get().get(PrinterHelper.class).isActive() || Modules.get().get(dev.monocle.client.systems.modules.world.StashManager.class).isScanning(); }
    void tick() {
        load();
        long now = System.currentTimeMillis();
        if (now >= nextHistoryCleanup) {
            nextHistoryCleanup = now + 60_000;
            int days = dev.monocle.client.systems.config.Config.get().botJobHistoryDays.get();
            Map<UUID, JsonObject> removed = new LinkedHashMap<>();
            for (var e : List.copyOf(runs.entrySet())) if (!e.getKey().equals(current) && terminal(text(e.getValue(), "status")) && !hasUncertainAction(e.getValue())
                && BotHistory.expired(BotHistory.finishedAt(e.getValue()), now, days)) { removed.put(e.getKey(), e.getValue()); runs.remove(e.getKey()); }
            if (!removed.isEmpty()) try { save(); } catch (RuntimeException e) { runs.putAll(removed); throw e; }
        }
        ticks++;
        // Task status is transport state, not world state. Menus/disconnect screens still acknowledge controls.
        if (ticks % 20 == 0) heartbeat();
        if (!Utils.canUpdate()) { disconnected(); return; }
        if (!dev.monocle.client.utils.network.WorldReadiness.INSTANCE.ready()) {
            disconnected();
            if(current!=null){run().addProperty("detail",dev.monocle.client.utils.network.WorldReadiness.INSTANCE.snapshot().get("reason").getAsString());if(ticks%20==0)sendStatus(current);}
            return;
        }
        // Only a host Resume authorizes this movement; no profile or job action runs in flight.
        if(landingResume!=null){tickProfileLanding();return;}
        if (!recoveryRestored) {
            if (interruptedOriginal != null) {
                if (nativeBusy() || !grounded()) return;
                BotProfiles.recoverOriginal(interruptedOriginal); interruptedOriginal = null; save();
            }
            recoveryRestored = true;
        }
        if (current != null) {
            if (!bots.isWorker() || !ownedBy(run(), bots.worker.credentialId())) disconnected();
            if (!sameServer(run(), server())) {
                disconnected(); run().addProperty("detail", "Join the original Minecraft server to settle the recorded action safely");
                if (ticks % 20 == 0) { checkpointAction(); sendStatus(current); }
                return; // Do not reconcile an old server's pending drop against a different inventory.
            }
            try {
                if (fightRecorder != null) fightRecorder.sample();
                // A live connection is not permission to resume. Wait for the host to reconcile
                // this saved execution and explicitly resume it (or deliver its cancellation).
                JsonObject f = top();
                // An action with no native checkpoint has not acquired controls or emitted
                // packets. Cancelling it must not start it just to immediately stop it.
                if (needsActionRestore(f, actionLoaded, requested) && !loadAction(f)) {
                    run().addProperty("detail","Landing before the next action's movement/profile handoff · "+bounded(actions.detail()));
                    if(ticks%20==0){checkpointAction();sendStatus(current);}return;
                }
                if (!requested.isEmpty()) {
                    settleFinish();
                } else if (f == null) finish("Complete", "Workflow complete");
                else if (f.has("action")) tickAction(f);
                else {if(actions.stashHandoff())actions.tick();decide(f);}
                boolean scanProgressed=false;
                if (current != null && actions.stashTelemetry() != null && typeOfCurrentScan()) {
                    String signature=scanProgressSignature(actionLoaded,actions.stashTelemetry());
                    if (!signature.equals(lastStashStatus)) {
                        if(!actions.independentScanCheckpoint()){checkpointAction();scanProgressed=true;}
                        lastStashStatus=signature;
                    }
                }
                sendPendingStashFinding();
                if(scanProgressed)sendStatus(current);
                else if(current!=null&&ticks%20==0&&scanPeriodicCheckpoint(typeOfCurrentScan(),ticks)){
                    if(!actions.independentScanCheckpoint())checkpointAction();
                    sendStatus(current);
                }
            } catch (RuntimeException e) {
                if (current != null) {
                    requested = finishOutcome(requested, "Failed"); requestedDetail = e.getMessage() == null ? "Workflow failed" : e.getMessage();
                    run().addProperty("status", "Suspending"); run().addProperty("requestedStatus", requested); run().addProperty("requestedDetail", requestedDetail);
                    run().addProperty("detail", "Failed; awaiting safe cleanup: " + bounded(requestedDetail));
                    sendStatus(current); // Do not hide a failed Highway while waiting for its host release.
                }
            }
        }
    }
    private void heartbeat() {
        if (bots.isWorker()) {
            JsonObject heartbeat = message("worker"); heartbeat.addProperty("current", current == null ? "" : current.toString());heartbeat.addProperty("stashCatalogProtocol",1);heartbeat.addProperty("stashLiveProtocol",1);heartbeat.addProperty("kitCategoriesProtocol",1);heartbeat.addProperty("stashContainerLimit",dev.monocle.coordinator.StashCatalog.MAX_CONTAINERS);
            heartbeat.addProperty("stashSortProtocol",1);
            JsonArray states = new JsonArray();
            for (var e : runs.entrySet()) if (ownedBy(e.getValue(), bots.worker.credentialId())) { JsonObject s = new JsonObject(); s.addProperty("run", e.getKey().toString()); s.addProperty("status", text(e.getValue(), "status")); s.addProperty("readbackVersion",1);s.addProperty("worldReady",Utils.canUpdate()&&dev.monocle.client.utils.network.WorldReadiness.INSTANCE.ready());if(e.getValue().has("controlId"))s.add("controlId",e.getValue().get("controlId").deepCopy()); states.add(s); }
            if (current != null && !ownedBy(run(), bots.worker.credentialId())) heartbeat.addProperty("current", "");
            heartbeat.add("runs", states); send(heartbeat);
            sendSurveyFindings();
        }
    }
    private boolean loadAction(JsonObject frame) {
        if(!actions.prepareNext(frame.getAsJsonObject("action")))return false;
        if (anchorRecovery(frame, mc.player.position())) save(); // Save the fixed search area before any movement, including legacy resumes.
        if (frame.has("native")) actions.restore(frame.getAsJsonObject("native")); else actions.start(frame.getAsJsonObject("action"));
        if(text(frame.getAsJsonObject("action"),"type").equals("StashScan")){
            String binding=hash(current+"\n"+text(frame,"token"));
            actions.scanCheckpoint(MonocleClient.FOLDER.toPath(),binding);
        }
        actions.resume(); actionLoaded = text(frame, "token");
        if (uncertainTeleport(frame)) actions.markTpaSent(); // Observe the outcome; never resend a checkpointed command intent.
        return true;
    }
    static boolean anchorRecovery(JsonObject frame, net.minecraft.world.phys.Vec3 position) {
        JsonObject action = frame.getAsJsonObject("action");
        if (!text(action, "type").equals("RecoverSupplies") || action.has("x")) return false;
        JsonObject anchored = action.deepCopy();
        anchored.addProperty("x", position.x); anchored.addProperty("y", position.y); anchored.addProperty("z", position.z);
        anchored = BotActions.validate(anchored);
        frame.add("action", anchored);
        if (frame.has("native")) frame.getAsJsonObject("native").add("action", anchored.deepCopy());
        return true;
    }
    private void tickAction(JsonObject frame) {
        JsonObject action = frame.getAsJsonObject("action"); String type = text(action, "type");
        if (type.equals("SetProfile") && !frame.has("profileApplied")) {
            String name = text(action, "name"); JsonObject profiles = run().getAsJsonObject("package").getAsJsonObject("profiles");
            if (!profiles.has(name)) throw new IllegalArgumentException("Profile is not included by the host: " + name);
            if (nativeBusy() || dev.monocle.client.pathing.BaritoneUtils.movementBusy() || !grounded()) {
                run().addProperty("detail", "Waiting for safe grounding/native release before applying a profile");
                actions.awaitingProgress("Safe grounding / native controller release before applying profile");
                run().add("actionProgress", actions.actionProgress()); run().addProperty("progressToken", actionLoaded); return;
            }
            // The original personal settings were checkpointed before the first application.
            BotProfiles.apply(profiles.getAsJsonObject(name)); run().add("effectiveProfile", BotProfiles.captureEffective());
            frame.addProperty("profileApplied", true); save(); actions.externalResult(true, "Applied bundled profile " + name, new JsonObject());
        } else if (type.equals("SetProfile") && frame.has("profileApplied") && text(actions.snapshot(), "state").equals("Running")) {
            // The effective profile is reapplied by Resume; a crash after its durable marker
            // but before the native completion callback must not strand this action.
            actions.externalResult(true, "Applied bundled profile " + text(action, "name"), new JsonObject());
        }
        if(type.equals("Highway")&&!bots.crew.resourceFailure().isEmpty()) actions.externalResult(false,bots.crew.resourceFailure(),new JsonObject());
        JsonObject status = actions.tick(true);
        if (!nativeFinished(status)) {
            actions.watchProgress(); run().add("actionProgress", actions.actionProgress()); run().addProperty("progressToken", actionLoaded);
            if (actions.progressPaused()) {
                String reason = "Action stalled: " + dev.monocle.coordinator.ActionProgress.describe(actions.actionProgress()) + " · " + text(status, "detail");
                MonocleClient.LOG.warn("[Workers] {} · run {} · action {}", reason, current, actionLoaded);
                finish("Suspended", reason); return;
            }
        }
        if(type.equals("StashVisit")&&actions.stashTravel().get("homeSent").getAsBoolean()&&!homeSentToken.equals(actionLoaded)){
            checkpointAction();sendStatus(current);homeSentToken=actionLoaded;
        }
        run().addProperty("detail", type.equals("Highway") && actions.recoveryReady() && !nativeFinished(status)
            ? bots.crew.localAssigned() ? "Highway: " + bots.crew.localStatus()
                : "Highway profile ready; waiting for host assignment and the other targeted workers"
            : text(status, "detail"));
        if (actions.checkpointRequired()) { checkpointAction(); actions.checkpointSaved(); }
        if (nativeFinished(status)) {
            if (Set.of("StashScan","StashResupply","StashDeposit").contains(type)) { checkpointAction(); sendStatus(current); }
            boolean success = text(status, "state").equals("Complete");
            JsonObject result = status.has("result") ? status.getAsJsonObject("result").deepCopy() : new JsonObject();
            if(type.equals("DropItems")&&!success)result.addProperty("dropped",action.get("count").getAsInt()-status.get("remaining").getAsInt());
            result.addProperty("ok", success); result.addProperty("detail", text(status, "detail"));
            if (!success && !(action.has("allowFailure") && action.get("allowFailure").getAsBoolean())) { finish("Failed", text(status, "detail")); return; }
            if (!actions.stashHandoff()&&(!actions.requestSuspend() || !grounded())) { run().addProperty("detail", "Action finished; awaiting safe cleanup · "+bounded(actions.detail())); return; }
            frame.add("result", result); frame.remove("action"); frame.remove("native"); frame.remove("profileApplied"); frame.remove("commandSent"); frame.remove("token"); run().remove("actionProgress"); run().remove("progressToken"); actionLoaded = ""; save();
        }
    }
    private void decide(JsonObject frame) {
        JsonObject packaged = run().getAsJsonObject("package"), programs = packaged.getAsJsonObject("programs");
        String id = text(frame, "program");
        if (!programs.has(id)) throw new IllegalArgumentException("Workflow was not bundled by the host: " + id);
        BotLua.Decision decision = BotLua.next(text(programs.getAsJsonObject(id), "script"), frame.getAsJsonObject("state"), frame.getAsJsonObject("args"), frame.getAsJsonObject("result"), world());
        Transition next = applyDecision(run(), decision);
        save(); // The Lua state and new action/call token are durable before any native operation.
        if (!next.finish().isEmpty()) finish(next.finish(), next.detail());
        else if (next.nativeAction()) { loadAction(top()); checkpointAction(); sendStatus(current); }
    }
    record Transition(String finish, String detail, boolean nativeAction) { }
    /** Pure frame reduction shared by live decisions and executable checkpoint regression tests. */
    static Transition applyDecision(JsonObject run, BotLua.Decision decision) {
        JsonObject frame = top(run);
        if (frame == null || frame.has("action")) throw new IllegalStateException("A decision needs an idle workflow frame");
        JsonObject packaged = run.getAsJsonObject("package"), programs = packaged.getAsJsonObject("programs");
        frame.add("state", decision.state()); JsonObject action = decision.action();
        switch (text(action, "type")) {
            case "Done" -> {
                JsonArray stack = run.getAsJsonArray("stack"); stack.remove(stack.size() - 1);
                if (stack.isEmpty()) {
                    if(action.has("result"))run.add("workflowResult",action.get("result").deepCopy());
                    run.addProperty("requestedStatus", "Complete"); run.addProperty("requestedDetail", "Workflow complete");
                    return new Transition("Complete", "Workflow complete", false);
                }
                JsonObject result = new JsonObject(); result.addProperty("ok", true);
                if (action.has("result")) result.add("value", action.get("result").deepCopy());
                top(run).add("result", result);
            }
            case "Fail" -> { return new Transition("Failed", text(action, "detail"), false); }
            case "Call" -> {
                String child = text(action, "workflow"); JsonArray stack = run.getAsJsonArray("stack");
                if (!programs.has(child) || stack.size() >= 16) throw new IllegalArgumentException("Missing nested workflow or call depth exceeds 16: " + child);
                stack.add(frame(child, action.has("args") ? action.getAsJsonObject("args") : new JsonObject()));
            }
            default -> {
                if (run.has("supportedActions") && run.getAsJsonArray("supportedActions").asList().stream().noneMatch(value -> value.getAsString().equals(text(action, "type"))))
                    throw new IllegalArgumentException("This host does not support " + text(action, "type") + " tasks yet");
                if (Set.of("StashHunt","StashScan").contains(text(action, "type")) || text(action,"type").equals("Travel") && action.has("bodyguard") && action.get("bodyguard").getAsBoolean()) {
                    action.addProperty("workerIndex", run.has("workerIndex") ? integer(run, "workerIndex", 0, 15) : 0);
                    action.addProperty("workerCount", run.has("workerCount") ? integer(run, "workerCount", 1, 16) : 1);
                    if (!action.has("dimension") && run.has("dimension")) action.add("dimension", run.get("dimension").deepCopy());
                }
                JsonObject next = BotActions.validate(action);
                if (text(next, "type").equals("Highway")) {
                    String workflow = text(next, "workflow");
                    if (!packaged.getAsJsonObject("highways").has(workflow)) throw new IllegalArgumentException("Highway preset was not bundled: " + workflow);
                    BotWorkflows.operation(packaged.getAsJsonObject("highways").getAsJsonObject(workflow));
                }
                frame.add("action", next); frame.addProperty("token", UUID.randomUUID().toString());
                return new Transition("", "", true);
            }
        }
        return new Transition("", "", false);
    }
    private JsonObject world() {
        JsonObject world = new JsonObject(); world.addProperty("x", mc.player.getX()); world.addProperty("y", mc.player.getY()); world.addProperty("z", mc.player.getZ());
        world.addProperty("dimension", mc.level.dimension().identifier().toString()); world.addProperty("health", mc.player.getHealth());
        world.addProperty("id", mc.player.getUUID().toString()); world.addProperty("name", mc.player.getName().getString());
        JsonObject inventory = new JsonObject();
        for (int slot = 0; slot < 36; slot++) { var stack = mc.player.getInventory().getItem(slot); if (stack.isEmpty()) continue; String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); inventory.addProperty(item, (inventory.has(item) ? inventory.get(item).getAsInt() : 0) + stack.getCount()); }
        world.add("inventory", inventory);
        JsonArray nearby = new JsonArray();
        for (var player : mc.level.players()) if (nearby.size() < 64 && player != mc.player && player.distanceToSqr(mc.player) <= 1024) { JsonObject p = new JsonObject(); p.addProperty("id", player.getUUID().toString()); p.addProperty("name", player.getName().getString()); p.addProperty("x", player.getX()); p.addProperty("y", player.getY()); p.addProperty("z", player.getZ()); nearby.add(p); }
        world.add("nearby", nearby); return world;
    }
    private void checkpointAction() { if (current == null) return; if (top() != null && top().has("action") && text(top(), "token").equals(actionLoaded)) top().add("native", actions.snapshot()); save(); }
    private void finish(String state, String detail) {
        if (current == null) return;
        if (state.equals("Suspended") && terminal(requested)) return; // Pause cannot turn a pending failure/completion back into resumable work.
        if (actions.stashFlightActive()) actions.requestSuspend(); // Keep controlled flight until a stash worker lands.
        else actions.disconnected(); // Revoke ground travel before checkpointing or waiting for native cleanup.
        requested = finishOutcome(requested, state); requestedDetail = bounded(detail == null || detail.isBlank() ? state : detail);
        run().addProperty("status", "Suspending"); run().addProperty("requestedStatus", requested); run().addProperty("requestedDetail", requestedDetail);
        run().addProperty("detail", "Awaiting safe cleanup: " + requestedDetail);
        checkpointAction(); sendStatus(current);
    }
    static String finishOutcome(String pending, String next) {
        if (pending.equals("Cancelled") || next.equals("Cancelled")) return "Cancelled";
        return next.equals("Suspended") && terminal(pending) ? pending : next;
    }
    private void settleFinish() {
        JsonObject run = run();
        run.addProperty("status", "Suspending"); run.addProperty("requestedStatus", requested); run.addProperty("requestedDetail", bounded(requestedDetail));
        if (nativeBusy()) { run.addProperty("detail", "Waiting for native highway release: " + bounded(requestedDetail) + " · " + Modules.get().get(HighwayBuilder.class).crewDiagnostics()); return; }
        if (!mc.player.containerMenu.getCarried().isEmpty()) { run.addProperty("detail", "Settle the carried cursor item before handing off the task; no inventory click will be replayed"); return; }
        if (!actions.requestSuspend()) {
            JsonObject status = actions.tick();
            // Destructive intent was already saved. The regular one-second checkpoint
            // suffices while observing/landing; final handoff below is always durable.
            run.addProperty("detail", "Cleanup: " + bounded(text(status, "detail")));
            return;
        }
        if (!grounded()) { run.addProperty("detail", "Land safely before handing off task profiles"); return; }
        if (top() != null && top().has("action") && text(top(), "token").equals(actionLoaded)) top().add("native", actions.snapshot());
        run.add("effectiveProfile", BotProfiles.captureEffective());
        actions.stop(); BotProfiles.restore();
        closeFightRecorder();
        run.addProperty("status", requested); run.addProperty("detail", bounded(requestedDetail)); run.remove("requestedStatus"); run.remove("requestedDetail");
        BotHistory.stamp(run, terminal(requested) && !hasUncertainAction(run), System.currentTimeMillis());
        save(); // Do not release runtime ownership until the final checkpoint is durable.
        UUID id = current; current = null; requested = requestedDetail = actionLoaded = ""; sendStatus(id);
    }
    private void closeFightRecorder() {
        if (fightRecorder == null) return;
        try { fightRecorder.close(); }
        catch (RuntimeException error) { bots.error("Crystal Guard fight log could not finish cleanly: %s", error.getMessage()); }
        finally { fightRecorder = null; }
    }
    private static String bounded(String value) { return value == null ? "" : value.substring(0, Math.min(900, value.length())); }
    void chat(String plain){
        if(current==null||!bots.isWorker()||!ownedBy(run(),bots.worker.credentialId())||top()==null||!text(top(),"token").equals(actionLoaded))return;
        if(!teleportReplyCurrent(tpaReplyToken,actionLoaded,tpaReplyCommand,lastCommand))return;
        if(actions.rejectTeleport(plain)){checkpointAction();sendStatus(current);}
    }
    void sentCommand(String command){lastCommand=command;}
    static boolean teleportReplyCurrent(String issuedToken,String currentToken,String issuedCommand,String lastCommand){return !issuedCommand.isEmpty()&&issuedToken.equals(currentToken)&&issuedCommand.equalsIgnoreCase(lastCommand);}
    void external(UUID id, String token, boolean success, String detail, JsonObject result) {
        if (!matches(id, token)) return;
        String type = text(top().getAsJsonObject("action"), "type");
        if (type.equals("Tpa") && !success && uncertainTeleport(top())) return; // A sent request still has to be observed through completion/timeout.
        if (!Set.of("Highway", "SetProfile", "KitTransferPlan", "StashSortPlan").contains(type) && !(type.equals("Tpa") && !success)) throw new IllegalArgumentException("This action is not host-managed");
        if (!token.equals(actionLoaded)&&!loadAction(top()))return;
        if (nativeFinished(actions.snapshot())) return; // Retransmitted results cannot overwrite the first terminal outcome.
        actions.externalResult(success, detail, result); checkpointAction();
    }
    boolean matches(UUID id, String token) { return id.equals(current) && bots.isWorker() && ownedBy(run(), bots.worker.credentialId()) && top() != null && top().has("action") && token.equals(text(top(), "token")); }
    void teleport(UUID id, String token, String targetName, UUID targetId, String dimension) {
        if (!requested.isEmpty() || !matches(id, token) || !text(top().getAsJsonObject("action"), "type").equals("Tpa") || uncertainTeleport(top())) return;
        if (!targetName.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Invalid TPA player name");
        if(!token.equals(actionLoaded)&&!loadAction(top()))return;
        // Persist intent before issuing: uncertain restart never repeats an already-issued teleport request.
        if(nativeFinished(actions.snapshot()))return;
        JsonObject action = top().getAsJsonObject("action"); action.addProperty("target", targetId.toString()); action.addProperty("dimension", dimension);
        actions.stop(); actions.start(action); actionLoaded = token;
        top().addProperty("commandSent", true); actions.markTpaSent(); top().add("native", actions.snapshot()); save();
        tpaReplyToken=token;tpaReplyCommand="tpa "+targetName;lastCommand="";
        bots.sendWorkflowTpa(targetName); checkpointAction();
    }
    void configure(UUID id, JsonObject modules, int revision, String owner) {
        load(); JsonObject run = runs.get(id);
        if (run == null || !ownedBy(run, owner)) throw new IllegalArgumentException("Host does not own this execution");
        if (revision < 1) throw new IllegalArgumentException("Invalid configuration revision");
        if (run.has("configRevision") && revision <= run.get("configRevision").getAsInt()) { sendStatus(id); return; }
        if (!id.equals(current) || !requested.isEmpty() || !bots.acceptCrew.get() || !Utils.canUpdate() || !dev.monocle.client.utils.network.WorldReadiness.INSTANCE.ready()) { sendStatus(id); return; }
        String error = "";
        try {
            JsonObject config=new JsonObject();config.addProperty("revision",revision);config.add("modules",TaskWire.checkedConfiguration(modules));run.add("configuration",config);
            BotProfiles.applyLive(modules); run.add("effectiveProfile", BotProfiles.captureEffective());
        }
        catch (RuntimeException e) { error = bounded("Configuration rejected: " + e.getMessage()); }
        run.addProperty("configRevision", revision); run.addProperty("configError", error);
        save(); sendStatus(id);
    }
    void sendStatus(UUID id) {
        JsonObject run = runs.get(id); if (run == null || !bots.isWorker() || !ownedBy(run, bots.worker.credentialId())) return;
        JsonObject m = message("status"); m.addProperty("run", id.toString()); m.addProperty("task", text(run, "task")); m.addProperty("status", text(run, "status")); m.addProperty("detail", bounded(text(run, "detail")));
        m.addProperty("connectionSuspended", id.equals(current) && connectionSuspended);
        m.addProperty("configurationVersion", 1);
        m.addProperty("readbackVersion", 1);
        if(run.has("controlId"))m.add("controlId",run.get("controlId").deepCopy());
        m.addProperty("worldReady",Utils.canUpdate()&&dev.monocle.client.utils.network.WorldReadiness.INSTANCE.ready());
        for (String key : List.of("configRevision", "configError")) if (run.has(key)) m.add(key, run.get(key).deepCopy());
        if (run.has("requestedStatus")) m.addProperty("requestedStatus", text(run, "requestedStatus"));
        if (run.has("workflowResult")) m.add("workflowResult",run.get("workflowResult").deepCopy());
        JsonArray stack = run.getAsJsonArray("stack");
        if (!stack.isEmpty()) { JsonObject f = stack.get(stack.size() - 1).getAsJsonObject(); if (f.has("action")) { m.add("action", f.get("action").deepCopy()); m.addProperty("token", text(f, "token")); m.addProperty("commandSent", f.has("commandSent")); } }
        if (m.has("action") && text(m,"token").equals(text(run,"progressToken")) && run.has("actionProgress")) m.add("actionProgress", run.get("actionProgress").deepCopy());
        if(id.equals(current)){
            if(typeOfCurrentScan()){JsonObject value=actions.stashTelemetry();if(value!=null)m.add("stashScan",value);}
            if(reportsNativeReceipt(m,actionLoaded,"StashResupply")){JsonObject value=actions.stashWithdrawal();if(value!=null)m.add("stashWithdrawal",value);}
            if(reportsNativeReceipt(m,actionLoaded,"StashDeposit")){JsonObject value=actions.stashDeposit();if(value!=null)m.add("stashDeposit",value);}
            if(reportsNativeReceipt(m,actionLoaded,"StashVisit")){JsonObject value=actions.stashTravel();if(value!=null)m.add("stashTravel",value);}
        }
        send(m);
    }
    static boolean reportsNativeReceipt(JsonObject message,String loadedToken,String type){
        return message.has("action")&&!loadedToken.isEmpty()&&loadedToken.equals(text(message,"token"))&&type.equals(text(message.getAsJsonObject("action"),"type"));
    }
    private boolean typeOfCurrentScan(){return top()!=null&&top().has("action")&&text(top().getAsJsonObject("action"),"type").equals("StashScan");}
    static String scanProgressSignature(String token,JsonObject telemetry){return token+"/"+telemetry.get("observed")+"/"+telemetry.get("unscanned")+"/"+telemetry.get("removed")+"/"+telemetry.get("inferred");}
    private void sendPendingStashFinding(){
        if(current==null||!actions.hasStashPending())return;
        if(typeOfCurrentScan()){
            long now=System.nanoTime();int first=actions.stashDelivery();
            if(!actionLoaded.equals(scanUploadToken)){scanUploadToken=actionLoaded;scanUploaded=0;scanUploadRetryAt=0;}
            if(now>=scanUploadRetryAt){scanUploaded=first-1;scanUploadRetryAt=now+3_000_000_000L;}
            List<JsonObject> batch=actions.scanUploads(scanUploaded);int start=Math.max(first,scanUploaded+1);
            for(int i=0;i<batch.size();i++){sendStashFinding(start+i,batch.get(i));scanUploaded=start+i;}
            return;
        }
        String finding=actionLoaded+"/"+actions.stashDelivery();
        long now=System.nanoTime();
        if(stashFindingDue(finding,lastStashFinding,now,lastStashFindingSentAt,typeOfCurrentScan())){sendStashFinding();lastStashFinding=finding;lastStashFindingSentAt=now;}
    }
    static boolean scanPeriodicCheckpoint(boolean scan,int tick){return !scan||tick%100==0;}
    static boolean stashFindingDue(String finding,String previous,long now,long sentAt){return stashFindingDue(finding,previous,now,sentAt,false);}
    static boolean stashFindingDue(String finding,String previous,long now,long sentAt,boolean scan){return !finding.equals(previous)||now-sentAt>=(scan?3_000_000_000L:1_000_000_000L);}
    static boolean currentStashAcknowledgement(boolean pending,int expected,int received){return pending&&expected==received;}
    private void sendStashFinding(){
        sendStashFinding(actions.stashDelivery(),actions.stashPending());
    }
    private void sendStashFinding(int delivery,JsonObject pending){
        if(current==null||top()==null||!top().has("action")||!Set.of("StashScan","StashDeposit").contains(text(top().getAsJsonObject("action"),"type")))return;
        JsonObject m=message("stash-findings");m.addProperty("run",current.toString());m.add("task",run().get("task"));m.add("token",top().get("token"));m.add("action",top().get("action").deepCopy());
        m.addProperty("delivery",delivery);
        if(typeOfCurrentScan())dev.monocle.coordinator.StashCatalog.attachObservation(m,pending);
        else{if(pending.has("observation"))dev.monocle.coordinator.StashCatalog.attachObservation(m,pending.getAsJsonObject("observation"));if(pending.has("exhaustedColumn"))m.add("exhaustedColumn",pending.get("exhaustedColumn").deepCopy());}
        send(m);
    }
    void acknowledgeStash(UUID id,String token,JsonObject ack,String owner){
        load();JsonObject r=runs.get(id);if(!ownedBy(r,owner)||!id.equals(current)||!token.equals(actionLoaded))return;
        int delivery=integer(ack,"delivery",1,dev.monocle.coordinator.StashCatalog.MAX_CONTAINERS);
        if(!currentStashAcknowledgement(actions.hasStashPending(),actions.stashDelivery(),delivery))return;
        if(typeOfCurrentScan()){
            if(ack.has("record"))bots.stashObserver.scanSaved(text(top().getAsJsonObject("action"),"name"),ack.getAsJsonObject("record"));
            actions.acknowledgeStash(delivery);
        }else if(dev.monocle.coordinator.TaskWire.flag(ack,"deposit")&&top()!=null&&top().has("action")&&text(top().getAsJsonObject("action"),"type").equals("StashDeposit"))actions.acknowledgeStashDeposit(ack);else return;
        if(!typeOfCurrentScan()||!actions.independentScanCheckpoint())checkpointAction();
    }

    private void sendSurveyFindings() {
        int sent = 0;
        var entries = List.copyOf(runs.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(Math.floorMod(surveySendCursor++, entries.size()));
            JsonObject run = entry.getValue();
            if (!ownedBy(run, bots.worker.credentialId())) continue;
            JsonObject frame = top(run);
            if (frame == null || !frame.has("native") || !frame.getAsJsonObject("native").has("survey")) continue;
            JsonArray batch = BotStashHunt.batch(frame.getAsJsonObject("native").getAsJsonObject("survey"));
            if (batch.isEmpty()) continue;
            JsonObject m = message("survey-findings"); m.addProperty("run", entry.getKey().toString()); m.add("task", run.get("task"));
            m.add("token", frame.get("token")); m.add("action", frame.get("action").deepCopy()); m.add("findings", batch); send(m);
            if (++sent >= 4) break; // Bounded retries, including cancelled runs whose notebook receipts were delayed.
        }
    }
    void acknowledgeSurvey(UUID id, String token, int delivery, String owner) {
        load(); JsonObject run = runs.get(id);
        if (!ownedBy(run, owner)) return;
        JsonObject frame = top(run);
        if (frame == null || !token.equals(text(frame, "token")) || !frame.has("native") || !frame.getAsJsonObject("native").has("survey")) return;
        if (id.equals(current) && token.equals(actionLoaded)) {
            actions.acknowledgeSurvey(delivery); frame.add("native", actions.snapshot());
        } else BotStashHunt.acknowledge(frame.getAsJsonObject("native").getAsJsonObject("survey"), delivery);
        save();
    }
    static boolean ownedBy(JsonObject run, String owner) { return run != null && !owner.isBlank() && text(run, "owner").equals(owner); }
    private void send(JsonObject m) { if (bots.isWorker()) bots.worker.send(m.toString()); }
}
