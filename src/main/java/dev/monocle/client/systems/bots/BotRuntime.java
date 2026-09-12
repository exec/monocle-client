package dev.monocle.client.systems.bots;

import com.google.gson.*;
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
    private final Path file = MonocleClient.FOLDER.toPath().resolve("bot-worker-tasks.json");
    private final Map<UUID, JsonObject> runs = new LinkedHashMap<>();
    private UUID current;
    private boolean loaded, recoveryRestored, connectionSuspended;
    private String failure, requested = "", requestedDetail = "", actionLoaded = "";
    private JsonObject interruptedOriginal;
    private int ticks, surveySendCursor;
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
    boolean ownsControls() { return current != null; }
    boolean allowsNative() {
        load();
        if (current == null) return runs.values().stream().noneMatch(BotRuntime::hasUncertainAction);
        return requested.isEmpty() && top() != null && top().has("action") && text(top().getAsJsonObject("action"), "type").equals("Highway") && actions.recoveryReady();
    }
    List<JsonObject> views() { load(); return runs.values().stream().map(JsonObject::deepCopy).toList(); }
    static boolean terminal(String status) { return dev.monocle.coordinator.QueuePolicy.terminal(status); }
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
        run.add("package", checkedPackage(packaged)); run.addProperty("status", "Ready"); run.addProperty("detail", "Package validated; waiting for host to start");
        JsonArray stack = new JsonArray(); stack.add(frame(text(packaged, "entry"), args)); run.add("stack", stack);
        run.remove("effectiveProfile"); run.remove("requestedStatus"); run.remove("requestedDetail");
        validateCheckpoint(id, run);
        UUID old = runs.size() < 64 ? null : runs.entrySet().stream().filter(e -> terminal(text(e.getValue(), "status"))).map(Map.Entry::getKey).findFirst().orElseThrow(() -> new IllegalStateException("Worker checkpoint queue is full"));
        JsonObject retired = old == null ? null : runs.remove(old);
        runs.put(id, run);
        try { save(); } catch (RuntimeException e) { runs.remove(id); if (old != null) runs.put(old, retired); throw e; }
        sendStatus(id);
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
        return nativeState.has("pendingDrop") || text(nativeState, "state").equals("Running") && uncertainTeleport(frame);
    }
    static boolean nativeFinished(JsonObject snapshot) { return Set.of("Complete", "Failed").contains(text(snapshot, "state")); }
    static Optional<JsonObject> unresolvedOther(Map<UUID, JsonObject> runs, UUID next) {
        return runs.entrySet().stream().filter(e -> !e.getKey().equals(next) && hasUncertainAction(e.getValue())).map(Map.Entry::getValue).findFirst();
    }
    static boolean sameServer(JsonObject run, String server) { return !run.has("server") || text(run, "server").equalsIgnoreCase(server); }
    private String server() { return mc.getCurrentServer() == null ? "local" : mc.getCurrentServer().ip; }
    static void validateCheckpoint(UUID id, JsonObject run) {
        if (!id.toString().equals(text(run, "run"))) throw new IllegalArgumentException("Checkpoint run identity mismatch");
        UUID.fromString(text(run, "task")); BotJobs.name(text(run, "name"));
        if (text(run, "owner").isBlank() || text(run, "owner").length() > 128) throw new IllegalArgumentException("Missing checkpoint owner");
        if (run.has("server") && (text(run, "server").isBlank() || text(run, "server").length() > 1024 || text(run, "server").chars().anyMatch(Character::isISOControl))) throw new IllegalArgumentException("Invalid task server");
        if (!Set.of("Ready", "Running", "Suspending", "Suspended", "Inspection required", "Complete", "Cancelled", "Failed").contains(text(run, "status"))) throw new IllegalArgumentException("Invalid checkpoint status");
        JsonObject packaged = checkedPackage(run.getAsJsonObject("package"));
        if (run.has("supportedActions")) {
            JsonArray supported = run.getAsJsonArray("supportedActions");
            if (supported == null || supported.isEmpty() || supported.size() > 8) throw new IllegalArgumentException("Invalid host capabilities");
            for (JsonElement action : supported) if (!Set.of("Travel", "StashHunt", "DropItems", "Wait", "Modules", "Tpa", "SetProfile", "Highway", "RecoverSupplies").contains(action.getAsString())) throw new IllegalArgumentException("Unknown host capability");
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
        load(); JsonObject run = runs.get(id);
        if (run == null) return;
        if (!owner.equals(text(run, "owner"))) throw new IllegalArgumentException("This host does not own the saved task");
        if (terminal(text(run, "status"))) { sendStatus(id); return; }
        switch (command) {
            case "resume" -> {
                if (!Utils.canUpdate() || !sameServer(run, server())) { run.addProperty("detail", "Join the task's original Minecraft server before resuming"); sendStatus(id); return; }
                if (current != null && !current.equals(id)) { run.addProperty("detail", "Waiting for the current task to finish its safe handoff"); sendStatus(id); return; }
                if (!bots.acceptCrew.get()) throw new IllegalStateException("Worker has disabled task execution");
                if (current == null) {
                    Optional<JsonObject> unfinished = unresolvedOther(runs, id);
                    if (unfinished.isPresent()) { run.addProperty("detail", "Reconcile the recorded drop/teleport in '" + bounded(text(unfinished.get(), "name")) + "' before starting another task"); sendStatus(id); return; }
                    if (nativeBusy()) { run.addProperty("detail", "Waiting for the source highway to release this worker"); sendStatus(id); return; }
                    if (!grounded()) { run.addProperty("detail", "Land safely before applying a task profile"); sendStatus(id); return; }
                    if (interruptedOriginal != null || !recoveryRestored) { run.addProperty("detail", "Waiting for original profile recovery before resuming"); sendStatus(id); return; }
                    current = id; requested = savedFinish(run); requestedDetail = text(run, "requestedDetail"); actionLoaded = "";
                    run.addProperty("status", "Running"); run.addProperty("detail", "Resuming workflow");
                    try {
                        BotProfiles.begin(); save();
                        BotProfiles.apply(run.has("effectiveProfile") ? run.getAsJsonObject("effectiveProfile") : run.getAsJsonObject("package").getAsJsonObject("profiles").getAsJsonObject("Current"));
                    }
                    catch (RuntimeException e) { finish("Failed", "Profile could not be applied: " + e.getMessage()); return; }
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
        if (current == null) return;
        actions.disconnected();
        if (requested.isEmpty()) {
            connectionSuspended = top() != null && top().has("action") && text(top().getAsJsonObject("action"), "type").equals("Highway");
            finish("Suspended", "Connection lost; safely checkpointing");
        }
    }
    private boolean grounded() { return Utils.canUpdate() && mc.player.onGround() && !mc.player.isFallFlying(); }
    private boolean nativeBusy() { return bots.crew.localAssigned() || Modules.get().get(HighwayBuilder.class).hasJob() || Modules.get().get(PrinterHelper.class).isActive(); }
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
                // A live connection is not permission to resume. Wait for the host to reconcile
                // this saved execution and explicitly resume it (or deliver its cancellation).
                JsonObject f = top();
                // An action with no native checkpoint has not acquired controls or emitted
                // packets. Cancelling it must not start it just to immediately stop it.
                if (f != null && f.has("action") && !text(f, "token").equals(actionLoaded) && (requested.isEmpty() || f.has("native"))) loadAction(f);
                if (!requested.isEmpty()) {
                    settleFinish();
                } else if (f == null) finish("Complete", "Workflow complete");
                else if (f.has("action")) tickAction(f);
                else decide(f);
                if (current != null && ticks % 20 == 0) { checkpointAction(); sendStatus(current); }
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
            JsonObject heartbeat = message("worker"); heartbeat.addProperty("current", current == null ? "" : current.toString());
            JsonArray states = new JsonArray();
            for (var e : runs.entrySet()) if (ownedBy(e.getValue(), bots.worker.credentialId())) { JsonObject s = new JsonObject(); s.addProperty("run", e.getKey().toString()); s.addProperty("status", text(e.getValue(), "status")); states.add(s); }
            if (current != null && !ownedBy(run(), bots.worker.credentialId())) heartbeat.addProperty("current", "");
            heartbeat.add("runs", states); send(heartbeat);
            sendSurveyFindings();
        }
    }
    private void loadAction(JsonObject frame) {
        if (frame.has("native")) actions.restore(frame.getAsJsonObject("native")); else actions.start(frame.getAsJsonObject("action"));
        actions.resume(); actionLoaded = text(frame, "token");
        if (uncertainTeleport(frame)) actions.markTpaSent(); // Observe the outcome; never resend a checkpointed command intent.
    }
    private void tickAction(JsonObject frame) {
        JsonObject action = frame.getAsJsonObject("action"); String type = text(action, "type");
        if (type.equals("SetProfile") && !frame.has("profileApplied")) {
            String name = text(action, "name"); JsonObject profiles = run().getAsJsonObject("package").getAsJsonObject("profiles");
            if (!profiles.has(name)) throw new IllegalArgumentException("Profile is not included by the host: " + name);
            if (nativeBusy() || !grounded()) { run().addProperty("detail", "Waiting for safe grounding/native release before applying a profile"); return; }
            // The original personal settings were checkpointed before the first application.
            BotProfiles.apply(profiles.getAsJsonObject(name)); run().add("effectiveProfile", BotProfiles.captureEffective());
            frame.addProperty("profileApplied", true); save(); actions.externalResult(true, "Applied bundled profile " + name, new JsonObject());
        } else if (type.equals("SetProfile") && frame.has("profileApplied") && text(actions.snapshot(), "state").equals("Running")) {
            // The effective profile is reapplied by Resume; a crash after its durable marker
            // but before the native completion callback must not strand this action.
            actions.externalResult(true, "Applied bundled profile " + text(action, "name"), new JsonObject());
        }
        JsonObject status = actions.tick();
        run().addProperty("detail", type.equals("Highway") && actions.recoveryReady() && !nativeFinished(status)
            ? bots.crew.localAssigned() ? "Highway: " + bots.crew.localStatus()
                : "Highway profile ready; waiting for host assignment and the other targeted workers"
            : text(status, "detail"));
        if (actions.checkpointRequired()) { checkpointAction(); actions.checkpointSaved(); }
        if (nativeFinished(status)) {
            boolean success = text(status, "state").equals("Complete");
            JsonObject result = status.has("result") ? status.getAsJsonObject("result").deepCopy() : new JsonObject();
            result.addProperty("ok", success); result.addProperty("detail", text(status, "detail"));
            if (!success && !(action.has("allowFailure") && action.get("allowFailure").getAsBoolean())) { finish("Failed", text(status, "detail")); return; }
            if (!actions.requestSuspend() || !grounded()) { run().addProperty("detail", "Action finished; awaiting safe cleanup before the next step"); return; }
            frame.add("result", result); frame.remove("action"); frame.remove("native"); frame.remove("profileApplied"); frame.remove("commandSent"); frame.remove("token"); actionLoaded = ""; save();
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
                if (text(action, "type").equals("StashHunt")) {
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
        run.addProperty("status", requested); run.addProperty("detail", bounded(requestedDetail)); run.remove("requestedStatus"); run.remove("requestedDetail");
        BotHistory.stamp(run, terminal(requested) && !hasUncertainAction(run), System.currentTimeMillis());
        save(); // Do not release runtime ownership until the final checkpoint is durable.
        UUID id = current; current = null; requested = requestedDetail = actionLoaded = ""; sendStatus(id);
    }
    private static String bounded(String value) { return value == null ? "" : value.substring(0, Math.min(900, value.length())); }
    void external(UUID id, String token, boolean success, String detail, JsonObject result) {
        if (!matches(id, token)) return;
        String type = text(top().getAsJsonObject("action"), "type");
        if (type.equals("Tpa") && !success && uncertainTeleport(top())) return; // A sent request still has to be observed through completion/timeout.
        if (!Set.of("Highway", "SetProfile").contains(type) && !(type.equals("Tpa") && !success)) throw new IllegalArgumentException("This action is not host-managed");
        if (!token.equals(actionLoaded)) loadAction(top());
        if (nativeFinished(actions.snapshot())) return; // Retransmitted results cannot overwrite the first terminal outcome.
        actions.externalResult(success, detail, result); checkpointAction();
    }
    boolean matches(UUID id, String token) { return id.equals(current) && bots.isWorker() && ownedBy(run(), bots.worker.credentialId()) && top() != null && top().has("action") && token.equals(text(top(), "token")); }
    void teleport(UUID id, String token, String targetName, UUID targetId, String dimension) {
        if (!requested.isEmpty() || !matches(id, token) || !text(top().getAsJsonObject("action"), "type").equals("Tpa") || uncertainTeleport(top())) return;
        if (!targetName.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Invalid TPA player name");
        // Persist intent before issuing: uncertain restart never repeats an already-issued teleport request.
        JsonObject action = top().getAsJsonObject("action"); action.addProperty("target", targetId.toString()); action.addProperty("dimension", dimension);
        actions.stop(); actions.start(action); actionLoaded = token;
        top().addProperty("commandSent", true); actions.markTpaSent(); top().add("native", actions.snapshot()); save();
        mc.getConnection().sendCommand("tpa " + targetName); checkpointAction();
    }
    void sendStatus(UUID id) {
        JsonObject run = runs.get(id); if (run == null || !bots.isWorker() || !ownedBy(run, bots.worker.credentialId())) return;
        JsonObject m = message("status"); m.addProperty("run", id.toString()); m.addProperty("task", text(run, "task")); m.addProperty("status", text(run, "status")); m.addProperty("detail", bounded(text(run, "detail")));
        m.addProperty("connectionSuspended", id.equals(current) && connectionSuspended);
        if (run.has("requestedStatus")) m.addProperty("requestedStatus", text(run, "requestedStatus"));
        JsonArray stack = run.getAsJsonArray("stack");
        if (!stack.isEmpty()) { JsonObject f = stack.get(stack.size() - 1).getAsJsonObject(); if (f.has("action")) { m.add("action", f.get("action").deepCopy()); m.addProperty("token", text(f, "token")); m.addProperty("commandSent", f.has("commandSent")); } }
        send(m);
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
