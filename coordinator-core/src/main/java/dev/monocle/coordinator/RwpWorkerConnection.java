package dev.monocle.coordinator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;

/** Durable RWP Wait and Travel execution; Minecraft movement remains in the client. */
public final class RwpWorkerConnection implements AutoCloseable {
    public static final String PATH = "/v1/interop/workers", VERSION = "rwp/1-draft";
    private final UUID workerId;
    private final String implementationVersion;
    private final Path journal;
    private final WebSocketClient socket;
    private JsonObject run, observedScope;
    private long nextOutbound, nextInbound, reportSentAt, progressSentAt;
    private int observationTicks;
    private volatile boolean accepted, reconciled, stopped;
    private volatile String failure = "";
    private volatile String hostImplementation = "";
    private boolean reconcileSent, allowed, waiting;

    public static boolean isEndpoint(String address) { return address.contains(PATH); }

    public RwpWorkerConnection(String address, UUID workerId, String credential, Path journal) {
        this(address, workerId, credential, journal, "dev");
    }

    public RwpWorkerConnection(String address, UUID workerId, String credential, Path journal, String implementationVersion) {
        URI uri = URI.create(address);
        if (!List.of("ws", "wss").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null || !PATH.equals(uri.getPath())
            || uri.getPort() == 0 || uri.getPort() > 65535)
            throw new IllegalArgumentException("Use wss://HOST[:PORT]" + PATH + " without URL credentials");
        if (uri.getScheme().equals("ws") && !List.of("127.0.0.1", "localhost", "::1").contains(uri.getHost()))
            throw new IllegalArgumentException("Plain ws:// RWP is only allowed on loopback");
        if (credential == null || credential.length() < 24 || credential.length() > 128 || credential.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("RWP worker token must be 24–128 characters");
        if (implementationVersion == null || implementationVersion.length() > 64 || !implementationVersion.matches("[A-Za-z0-9][A-Za-z0-9._+-]*"))
            throw new IllegalArgumentException("Invalid worker implementation version");
        this.workerId = workerId;
        this.implementationVersion = implementationVersion;
        this.journal = journal;
        JsonObject saved = TaskFiles.read(journal);
        if (!saved.isEmpty()) {
            if (!saved.has("version") || saved.get("version").getAsInt() != 1 || !workerId.toString().equals(string(saved, "workerId")))
                throw new IllegalStateException("Invalid RWP worker checkpoint; original left untouched");
            if (saved.has("run")) {
                if (!saved.get("run").isJsonObject()) throw new IllegalStateException("Invalid RWP worker checkpoint; original left untouched");
                run = saved.getAsJsonObject("run"); validateRun(run);
            }
        }
        socket = new WebSocketClient(uri, new Draft_6455(List.of(), 16_000), Map.of("Authorization", "Bearer " + credential), 5000) {
            @Override public void onOpen(ServerHandshake handshake) { hello(); }
            @Override public void onMessage(String frame) { receive(frame); }
            @Override public void onMessage(java.nio.ByteBuffer frame) { fail("RWP requires text frames"); }
            @Override public void onClose(int code, String reason, boolean remote) { stopped = true; }
            @Override public void onError(Exception error) { fail("RWP connection failed: " + error.getClass().getSimpleName()); }
        };
        socket.setDaemon(true);
        socket.setConnectionLostTimeout(10);
    }

    public void connect() { socket.connect(); }
    public boolean connected() { return reconciled && !stopped && socket.isOpen(); }
    public boolean active() { return !stopped; }
    public String failure() { return failure; }
    public String hostImplementation() { return hostImplementation; }
    public synchronized String work() { return run == null ? "idle" : string(run, "phase") + (run.has("action") ? " · Travel" : " · " + run.get("remainingTicks").getAsInt() + " ticks remaining"); }

    /** A running, reconciled Travel assignment; null also revokes movement authority. */
    public synchronized JsonObject activeTravel() {
        return connected() && allowed && run != null && run.has("action") && string(run, "phase").equals("running")
            && sameScope(run.getAsJsonObject("scope")) ? run.deepCopy() : null;
    }

    /** Called only after the Minecraft Travel action reports safe-footing arrival. */
    public synchronized void completeTravel(JsonObject observation) {
        if (activeTravel() == null || !sameScope(observation.getAsJsonObject("scope"))) throw new IllegalStateException("Travel has no current authority");
        JsonObject target = run.getAsJsonObject("action").getAsJsonObject("arguments"), position = observation.getAsJsonObject("position");
        double dx = position.get("x").getAsDouble() - target.get("x").getAsDouble();
        double dy = position.get("y").getAsDouble() - target.get("y").getAsDouble();
        double dz = position.get("z").getAsDouble() - target.get("z").getAsDouble();
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
            || Math.hypot(Math.hypot(dx, dy), dz) > target.get("radius").getAsDouble())
            throw new IllegalArgumentException("Travel destination not observed");
        send("worker.observation", observation, UUID.randomUUID());
        run.addProperty("phase", "completed"); queueReport("execution.completed", reference(run));
    }

    public synchronized void failTravel() {
        if (activeTravel() == null) return;
        JsonObject report = reference(run); report.addProperty("code", "travel_failed");
        run.addProperty("phase", "failed"); queueReport("execution.failed", report);
    }

    /** Replaceable operator telemetry; never used as completion evidence. */
    public synchronized void reportTravelProgress(String detail) {
        if (activeTravel() != null) progress(detail, null);
    }

    /** Called on the game thread to publish current world and retry durable reports. */
    public synchronized void poll(Supplier<JsonObject> observation) {
        if (!accepted || stopped || !socket.isOpen()) return;
        if (!reconcileSent || ++observationTicks >= 20) {
            observationTicks = 0;
            JsonObject seen = observation.get(); observedScope = seen.getAsJsonObject("scope").deepCopy();
            send("worker.observation", seen, UUID.randomUUID());
            if (!reconcileSent || waiting) reconcile();
        }
        if (reconciled && run != null && run.has("pending") && System.nanoTime() - reportSentAt >= 1_000_000_000L)
            sendPending();
    }

    /** Exactly one call per Minecraft client tick; never counts time while offline or awaiting the host. */
    public synchronized void gameTick() {
        if (!connected() || !allowed || run == null || run.has("action") || !string(run, "phase").equals("running") || !sameScope(run.getAsJsonObject("scope"))) return;
        try {
            int remaining = run.get("remainingTicks").getAsInt();
            if (remaining > 0) { run.addProperty("remainingTicks", remaining - 1); save(); }
            if (remaining > 1) progress("Waiting · " + (remaining - 1) + " ticks remaining", remaining - 1);
            if (remaining <= 1) { run.addProperty("phase", "completed"); queueReport("execution.completed", reference(run)); }
        } catch (RuntimeException error) { fail("RWP Wait checkpoint failed: " + error.getMessage()); }
    }

    private synchronized void hello() {
        JsonObject payload = new JsonObject(); payload.addProperty("workerId", workerId.toString());
        JsonArray versions = new JsonArray(); versions.add(VERSION); payload.add("supportedVersions", versions);
        JsonObject implementation = new JsonObject(); implementation.addProperty("id", "dev.monocle.client");
        implementation.addProperty("version", implementationVersion); payload.add("implementation", implementation);
        JsonArray capabilities = new JsonArray();
        for (String id : List.of("workers.wait.v1", "workers.travel.v1")) { JsonObject capability = new JsonObject(); capability.addProperty("id", id); capabilities.add(capability); }
        payload.add("capabilities", capabilities); payload.addProperty("lastHostSequence", 0); payload.addProperty("lastWorkerSequence", 0);
        send("session.hello", payload, UUID.randomUUID());
    }

    private void reconcile() {
        JsonObject payload = new JsonObject(); payload.addProperty("lastHostSequence", nextInbound - 1);
        payload.addProperty("lastWorkerSequence", nextOutbound - 1);
        JsonArray active = new JsonArray(); if (run != null) active.add(reference(run)); payload.add("activeExecutions", active);
        send("state.reconcile", payload, UUID.randomUUID()); reconcileSent = true;
    }

    private synchronized void send(String type, JsonObject payload, UUID messageId) {
        if (stopped || !socket.isOpen()) return;
        JsonObject frame = new JsonObject(); frame.addProperty("apiVersion", VERSION); frame.addProperty("type", type);
        frame.addProperty("messageId", messageId.toString()); frame.addProperty("correlationId", UUID.randomUUID().toString());
        frame.addProperty("workerId", workerId.toString()); frame.addProperty("sequence", nextOutbound++);
        frame.addProperty("sentAt", Instant.now().toString()); frame.add("payload", payload);
        if (frame.toString().getBytes(StandardCharsets.UTF_8).length > 16_000) { fail("RWP output is too large"); return; }
        socket.send(frame.toString());
    }

    private synchronized void receive(String raw) {
        try {
            if (raw.getBytes(StandardCharsets.UTF_8).length > 16_000) throw new IllegalArgumentException("Oversized RWP frame");
            JsonObject frame = JsonParser.parseString(raw).getAsJsonObject();
            UUID.fromString(string(frame, "messageId")); UUID.fromString(string(frame, "correlationId"));
            Instant.parse(string(frame, "sentAt"));
            if (!frame.get("payload").isJsonObject() || !VERSION.equals(string(frame, "apiVersion"))
                || !workerId.toString().equals(string(frame, "workerId")) || frame.get("sequence").getAsLong() != nextInbound++)
                throw new IllegalArgumentException("Invalid RWP host envelope");
            JsonObject payload = frame.getAsJsonObject("payload");
            switch (string(frame, "type")) {
                case "session.accepted" -> {
                    if (accepted || nextInbound != 1 || !VERSION.equals(string(payload, "version"))) throw new IllegalArgumentException("Unexpected RWP session acceptance");
                    JsonObject implementation=payload.getAsJsonObject("implementation");
                    String id=string(implementation,"id"),version=string(implementation,"version");
                    if(id.length()>128 || !id.matches("[a-z0-9]+(?:[.-][a-z0-9]+)*")
                        || version.length()>64 || !version.matches("[A-Za-z0-9][A-Za-z0-9._+-]*"))
                        throw new IllegalArgumentException("Invalid RWP host implementation identity");
                    hostImplementation=id+" "+version;
                    accepted = true;
                }
                case "state.reconciled" -> reconciled(payload);
                case "execution.assign" -> assign(payload);
                case "execution.cancel" -> cancel(payload);
                case "message.ack" -> acknowledge(payload);
                case "protocol.error" -> throw new IllegalArgumentException("Host rejected RWP session: " + string(payload, "code"));
                default -> throw new IllegalArgumentException("Unexpected RWP host message " + string(frame, "type"));
            }
        } catch (RuntimeException error) { fail(error.getMessage() == null ? "Invalid RWP host message" : error.getMessage()); }
    }

    private void reconciled(JsonObject payload) {
        if (!accepted || !reconcileSent) throw new IllegalArgumentException("RWP reconciliation was not requested");
        JsonArray decisions = payload.getAsJsonArray("decisions");
        if (decisions == null || decisions.size() != (run == null ? 0 : 1)) throw new IllegalArgumentException("RWP checkpoint decision mismatch");
        reconciled = true; waiting = allowed = false;
        if (run == null) return;
        JsonObject decision = decisions.get(0).getAsJsonObject();
        if (!sameReference(run, decision)) throw new IllegalArgumentException("RWP checkpoint identity mismatch");
        switch (string(decision, "decision")) {
            case "continue" -> { allowed = true; if (run.has("pending")) sendPending(); }
            case "cancel" -> cancel(decision);
            case "wait" -> waiting = true;
            case "inspect" -> {
                if (Set.of("completed", "cancelled", "failed").contains(string(run, "phase")) && run.has("pending")) sendPending();
                else { run.addProperty("phase", "inspection"); run.remove("pending"); save(); }
            }
            default -> throw new IllegalArgumentException("Unknown RWP checkpoint decision");
        }
    }

    private void assign(JsonObject payload) {
        if (!reconciled || !socket.isOpen()) throw new IllegalArgumentException("RWP assignment before reconciliation");
        if (run != null) {
            if (sameReference(run, payload) && string(run, "commandId").equals(string(payload, "commandId"))) {
                if (string(run, "phase").equals("accepted") && run.has("pending")) sendPending();
                return;
            }
            throw new IllegalArgumentException("RWP worker has unresolved work; refusing another assignment");
        }
        if (!sameScope(payload.getAsJsonObject("scope"))) throw new IllegalArgumentException("Assignment world is not observed");
        JsonObject action = payload.getAsJsonObject("action");
        String type = string(action, "type");
        if (!Set.of("workers.wait.v1", "workers.travel.v1").contains(type)) throw new IllegalArgumentException("Unsupported RWP assignment");
        int ticks = 0;
        if (type.equals("workers.wait.v1")) {
            ticks = action.getAsJsonObject("arguments").get("ticks").getAsBigDecimal().intValueExact();
            if (ticks < 0 || ticks > 1_728_000) throw new IllegalArgumentException("Invalid RWP Wait duration");
        } else validateTravel(action, payload.getAsJsonObject("scope"));
        JsonObject next = reference(payload); next.add("scope", payload.get("scope").deepCopy());
        next.addProperty("commandId", UUID.fromString(string(payload, "commandId")).toString());
        if (type.equals("workers.travel.v1")) next.add("action", action.deepCopy());
        next.addProperty("remainingTicks", ticks); next.addProperty("phase", "accepted");
        run = next; allowed = true; progressSentAt = 0; queueReport("execution.accepted", acceptedReference(next));
    }

    private void cancel(JsonObject payload) {
        if (run != null && !sameReference(run, payload)) throw new IllegalArgumentException("RWP cancellation identity mismatch");
        if (run == null) {
            run = reference(payload); run.add("scope", observedScope == null ? new JsonObject() : observedScope.deepCopy());
            run.addProperty("commandId", UUID.fromString(string(payload, "commandId")).toString());
            run.addProperty("remainingTicks", 0);
        }
        run.addProperty("phase", "cancelled");
        JsonObject report = reference(run); report.addProperty("cleanup", "acknowledged");
        queueReport("execution.cancelled", report);
    }

    private void acknowledge(JsonObject payload) {
        if (run == null || !run.has("pending") || !string(run.getAsJsonObject("pending"), "messageId").equals(string(payload, "messageId"))) return;
        if (!Set.of("accepted", "duplicate").contains(string(payload, "result"))) throw new IllegalArgumentException("RWP report was not accepted");
        switch (string(run, "phase")) {
            case "accepted" -> { run.addProperty("phase", "started"); queueReport("execution.started", reference(run)); }
            case "started" -> { run.addProperty("phase", "running"); run.remove("pending"); save(); }
            case "completed", "cancelled", "failed" -> { run = null; save(); }
            default -> throw new IllegalArgumentException("Unexpected RWP report acknowledgement");
        }
    }

    private void queueReport(String type, JsonObject payload) {
        JsonObject pending = new JsonObject(); pending.addProperty("type", type);
        pending.addProperty("messageId", UUID.randomUUID().toString()); pending.add("payload", payload);
        run.add("pending", pending); save();
        if (reconciled) sendPending();
    }

    private void sendPending() {
        JsonObject pending = run.getAsJsonObject("pending");
        send(string(pending, "type"), pending.getAsJsonObject("payload"), UUID.fromString(string(pending, "messageId")));
        reportSentAt = System.nanoTime();
    }

    private void progress(String detail, Integer remainingTicks) {
        long now=System.nanoTime();
        if (progressSentAt!=0 && now-progressSentAt<1_000_000_000L) return;
        JsonObject payload=reference(run);
        payload.addProperty("detail",detail.length()>240?detail.substring(0,240):detail);
        if(remainingTicks!=null)payload.addProperty("remainingTicks",remainingTicks);
        send("execution.progress",payload,UUID.randomUUID());progressSentAt=now;
    }

    private void save() {
        JsonObject root = new JsonObject(); root.addProperty("version", 1); root.addProperty("workerId", workerId.toString());
        if (run != null) root.add("run", run);
        TaskFiles.write(journal, root);
    }

    private boolean sameScope(JsonObject scope) { return observedScope != null && observedScope.equals(scope); }
    private static boolean sameReference(JsonObject a, JsonObject b) {
        return List.of("jobId", "executionId", "generation").stream().allMatch(key -> a.get(key).equals(b.get(key)));
    }
    private static JsonObject reference(JsonObject source) {
        JsonObject result = new JsonObject();
        for (String key : List.of("jobId", "executionId", "generation")) result.add(key, source.get(key).deepCopy());
        UUID.fromString(string(result, "jobId")); UUID.fromString(string(result, "executionId"));
        if (result.get("generation").getAsLong() < 1) throw new IllegalArgumentException("Invalid RWP execution generation");
        return result;
    }
    private static JsonObject acceptedReference(JsonObject source) {
        JsonObject result = reference(source); result.addProperty("commandId", string(source, "commandId")); return result;
    }
    private static String string(JsonObject object, String key) { return object.get(key).getAsString(); }
    private static void validateRun(JsonObject run) {
        reference(run); UUID.fromString(string(run, "commandId"));
        if (!Set.of("accepted", "started", "running", "completed", "cancelled", "failed", "inspection").contains(string(run, "phase"))
            || run.get("remainingTicks").getAsInt() < 0 || run.get("remainingTicks").getAsInt() > 1_728_000
            || !run.get("scope").isJsonObject()) throw new IllegalStateException("Invalid RWP worker checkpoint; original left untouched");
        if (run.has("action")) validateTravel(run.getAsJsonObject("action"), run.getAsJsonObject("scope"));
        if (run.has("pending")) {
            JsonObject pending = run.getAsJsonObject("pending");
            UUID.fromString(string(pending, "messageId"));
            if (!Set.of("execution.accepted", "execution.started", "execution.completed", "execution.cancelled", "execution.failed").contains(string(pending, "type"))
                || !sameReference(run, pending.getAsJsonObject("payload"))) throw new IllegalStateException("Invalid RWP report checkpoint; original left untouched");
        }
    }

    private static void validateTravel(JsonObject action, JsonObject scope) {
        if (!string(action, "type").equals("workers.travel.v1")) throw new IllegalArgumentException("Invalid RWP Travel action");
        JsonObject args = action.getAsJsonObject("arguments");
        if (!args.getAsJsonObject("scope").equals(scope)) throw new IllegalArgumentException("Travel scope mismatch");
        for (String key : List.of("x", "y", "z", "radius")) {
            double value = args.get(key).getAsDouble();
            if (!Double.isFinite(value) || (key.equals("radius") && (value < .15 || value > 8))
                || (key.equals("y") && Math.abs(value) > 2048) || (!key.equals("y") && !key.equals("radius") && Math.abs(value) > 29_999_984))
                throw new IllegalArgumentException("Invalid RWP Travel " + key);
        }
    }

    private void fail(String reason) { failure = reason; close(); }
    @Override public void close() { stopped = true; socket.close(); }
}
