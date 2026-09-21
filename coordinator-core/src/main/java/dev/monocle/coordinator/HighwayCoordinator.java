package dev.monocle.coordinator;

import com.google.gson.*;
import java.util.*;
import java.nio.file.*;
import dev.monocle.client.systems.bots.BotWorkflows;
import dev.monocle.client.systems.modules.misc.swarm.SwarmConnection;

/** Shared host state machine. P and the hooks isolate game observations/actions from coordination. */
public abstract class HighwayCoordinator<P> {
    public static final int MAX_CREW_MEMBERS = 3;
    private CrewTelemetry telemetry;
    private String windowDecision = "not evaluated";
    private long windowDecisionAt;
    private final Map<UUID, JsonObject> diagnosticPermits = new LinkedHashMap<>();
    private final Deque<JsonObject> diagnosticCommands = new ArrayDeque<>();
    private String telemetryCaptureError = "";
    private long telemetryErrorAt, diagnosticCommandSequence;
    private final Map<UUID, RecoveryTeleport> recoveryTeleports = new HashMap<>();
    private record RecoveryTeleport(UUID target, String token, int issued, boolean accepted) { }

    public Path telemetryPath() { return journal().resolveSibling(journal().getFileName() + ".telemetry.jsonl"); }
    public String telemetryError() { return !telemetryCaptureError.isEmpty() ? telemetryCaptureError : telemetry == null ? "" : telemetry.error(); }
    public long telemetryDropped() { return telemetry == null ? 0 : telemetry.dropped(); }

    /** Both host adapters log the same decision inputs. Logging failure cannot stop coordination. */
    protected void recordTelemetry() {
        if (!isHost() || telemetry == null && !assigned()) return;
        try {
            if (telemetry == null) telemetry = new CrewTelemetry(telemetryPath());
            long now = System.nanoTime(); if (!telemetry.due(now)) return;
            JsonObject d = new JsonObject(); d.addProperty("execution", job); d.addProperty("generation", generation);
            d.addProperty("phase", phase); d.addProperty("stopped", stopped); d.addProperty("begun", begun);
            d.addProperty("regrouping", regrouping); d.addProperty("releasing", releasing); d.addProperty("granted", granted);
            d.addProperty("supplyOwner", supplyOwner == null ? "" : supplyOwner.toString());
            d.addProperty("lock", lock); d.add("acknowledgments", JSON.toJsonTree(acknowledgments));
            d.addProperty("joining", String.valueOf(joiningWorker)); d.addProperty("detaching", String.valueOf(detachingWorker));
            d.addProperty("rejoining", String.valueOf(rejoiningWorker));
            d.addProperty("regroupTicks", regrouping ? ticks - regroupStarted : 0);
            d.addProperty("supplyRetryTicks", supplyRetryAfter - ticks);
            d.addProperty("windowDecision", windowDecision);
            d.addProperty("windowDecisionAgeMs", windowDecisionAt == 0 ? -1 : (now - windowDecisionAt) / 1_000_000);
            d.add("commandAttempts", JSON.toJsonTree(diagnosticCommands));
            if (assigned()) {
                d.addProperty("catalogId", str(assignment, "catalogId"));
                d.addProperty("scope", str(assignment, "scope")); d.addProperty("progress", safeProgress());
                d.addProperty("length", num(assignment, "length")); d.addProperty("hostAuthority", hostAuthority());
                d.add("activeMembers", JSON.toJsonTree(activeMembers())); d.add("suppliers", suppliers(assignment).deepCopy());
                if (resourceExchange.hostOffer != null) {
                    JsonObject exchange = new JsonObject();
                    for (String key : List.of("id", "phase", "sequence", "resource", "remaining", "donor", "recipient", "x", "y", "z"))
                        if (resourceExchange.hostOffer.has(key)) exchange.add(key, resourceExchange.hostOffer.get(key).deepCopy());
                    d.add("exchange", exchange);
                }
            }
            JsonArray workers = new JsonArray();
            for (UUID id : participants.keySet()) {
                JsonObject w = new JsonObject(), report = reports.get(id); w.addProperty("id", id.toString());
                if (assigned() && detachedMembers(assignment).contains(id)) w.addProperty("returnGate", serviceReturnGate(id));
                w.addProperty("fresh", currentReport(id) != null);
                w.addProperty("reportAgeMs", reportTimes.containsKey(id) ? (now - reportTimes.get(id)) / 1_000_000 : -1);
                if (report != null) for (String key : List.of("name", "job", "generation", "scope", "phase", "status", "currentRow", "verifiedBase", "verifiedMask", "currentResolved",
                    "serviceReturning", "serviceReady", "serviceRevisions", "renderedCrew", "begun", "regroupReady", "recoveryReady", "initialStockReady", "ack", "x", "y", "z", "diagnostics")) if (report.has(key)) w.add(key, report.get(key).deepCopy());
                if (report != null && report.has("inventory")) {
                    JsonObject inventory = new JsonObject(), source = report.getAsJsonObject("inventory");
                    for (String key : List.of("loose", "shulkers", "echest", "reserve", "target", "busy", "idle")) if (source.has(key)) inventory.add(key, source.get(key).deepCopy());
                    w.add("inventory", inventory);
                }
                if (report != null && report.has("exchange")) {
                    JsonObject exchange = new JsonObject(), source = report.getAsJsonObject("exchange");
                    for (String key : List.of("id", "sequence", "need", "stage", "stageTicks", "issued", "received", "detail"))
                        if (source.has(key)) exchange.add(key, source.get(key).deepCopy());
                    w.add("exchange", exchange);
                }
                JsonObject permit = diagnosticPermits.get(id);
                if (permit != null && job.equals(str(permit, "job"))) w.add("lastPermitSent", permit.deepCopy());
                if (requested.containsKey(id)) {
                    JsonObject request = new JsonObject();
                    for (String key : List.of("type", "job", "generation", "detach", "resource", "x", "y", "z"))
                        if (requested.get(id).has(key)) request.add(key, requested.get(id).get(key).deepCopy());
                    w.add("supplyRequest", request);
                }
                workers.add(w);
            }
            d.add("workers", workers); telemetry.sample(d, now); telemetryCaptureError = "";
        } catch (RuntimeException e) {
            // Diagnostics must never propagate into a bot controller's fail-closed gameplay path.
            telemetryCaptureError = "Snapshot failed: " + e.getClass().getSimpleName();
            long now = System.nanoTime();
            if (telemetryErrorAt == 0 || now - telemetryErrorAt > 60_000_000_000L) { telemetryErrorAt = now; System.err.println(telemetryCaptureError); }
        }
    }

    private void traceCommand(String recipient, JsonObject message) {
        if (!isHost() || Set.of("nudge", "window", "heartbeat").contains(str(message, "type"))) return;
        JsonObject event = new JsonObject(); event.addProperty("seq", ++diagnosticCommandSequence);
        event.addProperty("at", System.currentTimeMillis()); event.addProperty("recipient", recipient);
        for (String key : List.of("type", "job", "generation", "worker", "revision", "lock")) if (message.has(key)) event.add(key, message.get(key).deepCopy());
        if (diagnosticCommands.size() == 16) diagnosticCommands.removeFirst(); diagnosticCommands.addLast(event);
    }
    protected JsonArray cachedRoster;
    protected List<UUID> cachedMembers=List.of();
    protected static final Gson JSON = new Gson();
    protected static final int WORK_WINDOW=RowVerification.WINDOW, SUPPLY_SPACING=3;
    public enum WorkSharing {
        Lanes, Roles, BreakOrder;
        @Override public String toString() {
            return switch (this) { case Lanes -> "Lanes"; case Roles -> "Roles"; case BreakOrder -> "Break Order"; };
        }
    }


    protected final Map<P, UUID> miningOwners = new LinkedHashMap<>();

    protected boolean localParticipant;

    protected final Map<SwarmConnection, JsonObject> peers = new LinkedHashMap<>();

    protected final Map<UUID, JsonObject> reports = new LinkedHashMap<>();

    protected final Map<UUID, SwarmConnection> participants = new LinkedHashMap<>();

    protected final Set<UUID> acknowledgments = new HashSet<>();

    protected JsonObject assignment;

    protected String job = "", phase = "idle", lock = "", acknowledged = "";

    protected UUID supplyOwner;

    protected P supplyCenter;

    protected P pickupCenter;

    protected boolean granted, stopped, begun, backstepped;

    protected boolean pausedBeforeDisconnect;

    protected int regroupStarted, regroupRetryAfter, nextStartAttempt;

    protected int serviceReadyUntil;

    protected int forecastHintAfter;

    protected boolean regrouping, regroupReady, regroupWasPaused, releasing;
    private int initialStockDeadline;

    protected JsonObject availabilityChange;

    protected final Map<UUID, Boolean> offRangeArmed = new HashMap<>();

    protected long releaseStarted;

    protected boolean retryingSupply;

    protected int reservationStarted, supplyRetryAfter;

    protected UUID joiningWorker;

    protected final Set<UUID> borrowingWorkers = new LinkedHashSet<>();

    protected UUID detachingWorker;

    protected UUID rejoiningWorker;

    protected boolean supplyHandoff;

    protected boolean rejoiningSupply, serviceReturning, serviceFinished;

    protected P serviceFront;

    protected int generation;

    protected int ticks;

    protected int actualRow, verifiedBase, verifiedMask, leadLimit, checkpointRow;

    protected boolean workDirty;

    protected long lastHost, lastPermit;

    protected final Map<UUID, JsonObject> requested = new LinkedHashMap<>();

    protected final Map<UUID, Long> reportTimes = new HashMap<>();

    // Observations protect physical containers; they never reserve movement or work for the crew.
    protected JsonObject supplyContainers = new JsonObject();
    private final Map<UUID, Integer> pendingRejoins = new HashMap<>();

    protected final Map<String, Set<UUID>> pendingEnds = new LinkedHashMap<>();

    public static WorkSharing workSharing(JsonObject layout) {
        return layout.has("workSharing") ? WorkSharing.valueOf(layout.get("workSharing").getAsString()) : WorkSharing.Lanes;
    }

    public boolean assigned() { return assignment != null; }

    public void workChanged() { if (assigned()) workDirty = true; }

    public boolean isParticipant(UUID worker) { return participants.containsKey(worker) || worker.equals(joiningWorker); }

    public boolean localAssigned() { return assigned() && localParticipant; }

    public boolean detachedSupply() { return localAssigned() && detachedMembers(assignment).contains(me()); }

    public boolean independentSupplies() { return assigned() && TaskWire.flag(assignment, "independentSupplies"); }

    public boolean isReleasing() { return releasing; }

    public boolean roadComplete() { return assigned() && assignment.has("roadComplete") && assignment.get("roadComplete").getAsBoolean(); }

    public boolean away() { return localAssigned() && awayMembers(assignment).has(me().toString()); }

    protected static JsonObject awayMembers(JsonObject record) { return record.has("awayMembers") ? record.getAsJsonObject("awayMembers") : new JsonObject(); }

    protected UUID detachedMember() { return assigned() ? detachedMembers(assignment).stream().findFirst().orElse(null) : null; }

    public static Set<UUID> detachedMembers(JsonObject record) {
        if (record.has("suppliers")) return record.getAsJsonObject("suppliers").keySet().stream().map(UUID::fromString).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String legacy = str(record, "detachedMember");
        return legacy.isEmpty() ? Set.of() : Set.of(UUID.fromString(legacy));
    }

    protected static JsonObject suppliers(JsonObject record) {
        if (record.has("suppliers")) return record.getAsJsonObject("suppliers");
        JsonObject result = new JsonObject();
        for (UUID id : detachedMembers(record)) {
            JsonObject site = new JsonObject();
            site.addProperty("x", num(record, "serviceX")); site.addProperty("y", num(record, "serviceY")); site.addProperty("z", num(record, "serviceZ"));
            result.add(id.toString(), site);
        }
        return result;
    }

    protected boolean sharedSupplyHold() { return !independentSupplies() && supplyOwner != null && !detachedMembers(assignment).contains(supplyOwner); }

    public static boolean sharedSupplyHold(UUID owner, UUID detached) { return owner != null && !owner.equals(detached); }

    protected List<UUID> activeMembers() {
        var roster = assignment.getAsJsonArray(assignment.has("activeMembers") ? "activeMembers" : "members");
        if (!roster.equals(cachedRoster)) { cachedMembers = activeMembers(assignment); cachedRoster = roster.deepCopy(); }
        return cachedMembers;
    }

    public static List<UUID> activeMembers(JsonObject record) {
        var members = record.getAsJsonArray(record.has("activeMembers") ? "activeMembers" : "members");
        return members.asList().stream().map(member -> UUID.fromString(member.getAsString())).toList();
    }

    protected boolean hostAuthority() { return assigned() && !str(assignment, "hostMember").isEmpty() && activeMembers().contains(UUID.fromString(str(assignment, "hostMember"))); }

    protected String duty(UUID member) {
        return duty(assignment, member);
    }

    protected static String duty(JsonObject record, UUID member) {
        String duty = record.has("duties") ? str(record.getAsJsonObject("duties"), member.toString()) : "";
        return !duty.isEmpty() ? duty : defaultDuty(record);
    }

    protected static String defaultDuty(JsonObject record) {
        return record.has("workflow") ? str(record.getAsJsonObject("workflow"), "duty") : "Build";
    }

    public static boolean dutyAllows(String duty, boolean excavation) { return duty.isEmpty() || duty.equals("Build") || duty.equals(excavation ? "Excavate" : "Pave"); }

    public static List<UUID> preferredMembers(JsonObject record) {
        return record.getAsJsonArray(record.has("preferredMembers") ? "preferredMembers" : "members").asList().stream().map(id -> UUID.fromString(id.getAsString())).toList();
    }

    public static List<UUID> returnedMembers(List<UUID> preferred, Set<UUID> active, UUID returning) {
        return preferred.stream().filter(id -> active.contains(id) || id.equals(returning)).toList();
    }

    protected JsonObject borrowedMembers() {
        return assignment.has("borrowedMembers") ? assignment.getAsJsonObject("borrowedMembers") : new JsonObject();
    }

    public Set<UUID> reservedReturns() {
        if (!assigned()) return Set.of();
        Set<UUID> result = new LinkedHashSet<>();
        for (String id : borrowedMembers().keySet()) result.add(UUID.fromString(id));
        return Collections.unmodifiableSet(result);
    }

    public boolean borrowReady(UUID worker) {
        if (!assigned() || !borrowedMembers().has(worker.toString())) return false;
        JsonObject reservation = borrowedMembers().getAsJsonObject(worker.toString());
        return reservation.has("ready") && reservation.get("ready").getAsBoolean() && !participants.containsKey(worker);
    }
    public boolean borrowing(UUID worker) { return borrowingWorkers.contains(worker); }

    protected int startRow() { return assignment.has("startRow") ? num(assignment, "startRow") : 0; }

    protected int safeProgress() {
        // A permit is not progress: checkpoint only a server-resolved row physically reached by every member.
        return phase.equals("complete") ? num(assignment, "length") : Math.max(startRow(), checkpointRow);
    }

    public static int slowestRow(int limit, int... actual) {
        if (actual.length == 0) return 0;
        int minimum = limit;
        for (int row : actual) minimum = Math.min(minimum, Math.clamp(row, 0, limit));
        return minimum;
    }

    public static int leadLimit(int length, int slowest) { return Math.min(length, slowest + WORK_WINDOW); }

    public static int serviceFrontRow(int progress, int length) {
        return Math.max(1, Math.min(progress, length - 1));
    }

    public static int leadLimit(int length, int slowest, WorkSharing sharing, int renderDistance) {
        // A short physical leash keeps lane workers side-by-side. Long render-distance
        // windows were faster on paper but made restock/rejoin state unstable.
        return sharing == WorkSharing.BreakOrder ? Math.min(length, slowest + 1)
            : sharing == WorkSharing.Roles ? Math.min(length, slowest + 16)
            : leadLimit(length, slowest);
    }

    public static int roleLeadLimit(int length, int current, String duty, int excavationFront, int pavingFront) {
        int target = switch (duty) {
            case "Excavate" -> pavingFront + 16;
            case "Pave" -> excavationFront - 5;
            default -> Math.min(pavingFront + 8, excavationFront - 2);
        };
        return Math.max(current, Math.min(length, target));
    }

    public static boolean roleFormation(JsonObject record) {
        return roleFormation(record, activeMembers(record));
    }

    public static boolean roleFormation(JsonObject record, Collection<UUID> members) {
        if (workSharing(record.getAsJsonObject("layout")) != WorkSharing.Roles) return false;
        boolean excavator = false, paver = false;
        for (UUID member : members) {
            excavator |= duty(record, member).equals("Excavate");
            paver |= duty(record, member).equals("Pave");
        }
        return excavator && paver;
    }

    public static int leadLimit(int length, int slowest, WorkSharing sharing) {
        return leadLimit(length, slowest, sharing, 0);
    }

    public static boolean verifiedRow(int base, int mask, int row) {
        return RowVerification.verifiedRow(base, mask, row);
    }

    /** A crew may enter a row only after its full, server-resolved pattern is observed. */
    public static boolean canAdvance(int row, int limit, int base, int mask) {
        return row <= limit && verifiedRow(base, mask, row);
    }

    public static int resolvedMask(int base, int limit, java.util.function.IntPredicate resolved) {
        return RowVerification.resolvedMask(base, limit, resolved);
    }

    public static boolean barrierReady(Set<UUID> members, UUID owner, Set<UUID> acknowledgments) {
        return owner != null && members.contains(owner) && members.stream().allMatch(id -> id.equals(owner) || acknowledgments.contains(id));
    }

    public static List<UUID> serviceMembers(List<UUID> roster, UUID detached) { return roster.stream().filter(member -> !member.equals(detached)).toList(); }

    public static boolean resumeLocalBuilder(boolean participant, boolean begun, boolean detached) { return participant && (begun || detached); }

    protected static JsonObject message(String type) { var m = new JsonObject(); m.addProperty("type", type); return m; }

    protected JsonObject jobMessage(String type) { var m = message(type); m.addProperty("job", job); m.addProperty("generation", generation); return m; }

    public static boolean matchesGeneration(String job, int generation, JsonObject message) {
        // A missing generation is tolerated only for the initial, pre-reconfiguration
        // execution. Once a job has advanced, accepting it would let stale packets
        // mutate the current lane/supply state.
        return message != null && job != null && job.equals(str(message, "job"))
            && (message.has("generation") ? num(message, "generation") : generation == 0 ? 0 : Integer.MIN_VALUE) == generation;
    }

    public static boolean reconfigurationReady(JsonObject current, int generation, JsonObject next, boolean settled, boolean supplyReserved) {
        return settled && !supplyReserved && str(current, "job").equals(str(next, "job"))
            && str(current, "catalogId").equals(str(next, "catalogId")) && num(next, "generation") == generation + 1;
    }

    protected static String str(JsonObject m, String key) { return m.has(key) ? m.get(key).getAsString() : ""; }

    protected static int num(JsonObject m, String key) { return m.get(key).getAsInt(); }

    protected void broadcast(JsonObject m) {
        traceCommand("crew", m);
        for (var c : participants.values()) if (c != null) c.send(JSON.toJson(m));
        apply(m);
    }

    protected void coordinate(boolean workChanged) {
        if (isHost() && assigned() && !stopped && !phase.equals("paused")) {
            if (independentSupplies() && ticks % 10 == 0) coordinateSupplyContainers();
            if (regrouping) {
                try { if (!releasing && availabilityChange == null && detachingWorker == null && !rejoiningSupply && borrowingWorkers.isEmpty()) eligibleJoin(joiningWorker); }
                catch (IllegalStateException e) {
                    broadcast(jobMessage("regroupCancel")); joiningWorker = null;
                    warning("Worker admission canceled: %s", e.getMessage()); return;
                }
                if ((supplyHandoff || supplyOwner == null) && participants.keySet().stream().allMatch(id -> {
                    JsonObject report = currentReport(id);
                    return report != null && report.has("regroupReady") && report.get("regroupReady").getAsBoolean();
                })) {
                    if (!releasing && availabilityChange == null && !supplyHandoff && !coordinateWindow()) return; // Supply handoffs retain the last verified checkpoint.
                    if (detachingWorker != null && !canDetach(detachingWorker)) {
                        broadcast(jobMessage("regroupCancel")); detachingWorker = null;
                        info("The rear service area is no longer ready; using protected shared restocking instead."); return;
                    }
                    if (!releasing && availabilityChange == null && detachingWorker == null && !rejoiningSupply && borrowingWorkers.isEmpty()) try { eligibleJoin(joiningWorker); }
                    catch (IllegalStateException e) { broadcast(jobMessage("regroupCancel")); joiningWorker = null; warning("Worker admission canceled: %s", e.getMessage()); return; }
                    if (!releasing && safeProgress() == num(assignment, "length")) {
                        broadcast(jobMessage("regroupCancel")); joiningWorker = detachingWorker = null; rejoiningSupply = false; borrowingWorkers.clear();
                        return; // Let the existing builders report completion; never create a zero-length replacement.
                    }
                    if (releasing) endJob(); else if (availabilityChange != null) finishAvailability(); else if (!borrowingWorkers.isEmpty()) finishBorrow(); else if (detachingWorker != null || rejoiningSupply) finishServiceRegroup(); else finishRegroup();
                    return;
                }
                if (supplyOwner != null && !granted && supplyBarrierReady()) broadcast(reservation("grant"));
                return;
            }
            if (begun && !participants.isEmpty() && participants.keySet().stream().filter(id -> !awayMembers(assignment).has(id.toString())).allMatch(id -> currentReport(id) != null && "complete".equals(str(currentReport(id), "phase")))) {
                if (coordinateWindow() && checkpointRow == num(assignment, "length")) {
                    phase = "complete";
                    assignment.addProperty("roadComplete", true);
                    persist();
                    releaseJob(); // Also finish native jobs that have no workflow wrapper.
                    return;
                }
            }
            if (begun && ticks % 2 == 0) retryServiceUpdates();
            // The host may finish its own lane first in a sliding window; that does not finish the crew's job.
            if (begun && (phase.equals("complete") || detachedSupply())) phase = "building";
            if (begun && ticks % 20 == 0 && coordinateAvailability()) return;
            if (!begun && activeMembers().stream().allMatch(id -> currentReport(id) != null && "ready".equals(str(currentReport(id), "phase")))) broadcast(jobMessage("begin"));
            // Readiness can change while BEGIN is in flight. Retry only the members that did not start.
            if (begun && ticks % 10 == 0) for (UUID id : activeMembers()) {
                JsonObject r = currentReport(id);
                if (r != null && needsBeginRetry(r)) sendMember(id, jobMessage("begin"));
            }
            if (begun && !initialStocking() && !sharedSupplyHold() && workUpdateDue(ticks, true, workChanged)) coordinateWindow();
            if (detachedMember() != null || !pendingRejoins.isEmpty()) {
                coordinateServiceReturn();
                if (regrouping) return;
            }
            // A second hungry builder can leave while the first recovers its shulker.
            if (begun && ticks >= regroupRetryAfter) for (var entry : requested.entrySet()) {
                if (entry.getValue().has("detach") && entry.getValue().get("detach").getAsBoolean() && canDetach(entry.getKey())
                    && serviceRequestCurrent(assignment, entry.getKey(), entry.getValue())) {
                    departSupply(entry.getKey());
                    return;
                }
            }
            // Forecasts only use an idle supply slot; real stock-out requests always take precedence.
            if (begun && supplyOwner == null && detachedMember() == null && requested.isEmpty() && ticks >= forecastHintAfter && ticks % 20 == 0) {
                UUID candidate = null;
                double soonest = Double.POSITIVE_INFINITY;
                for (UUID id : activeMembers()) {
                    JsonObject report = currentReport(id);
                    if (report == null || !report.has("supplyEta") || !canDetach(id)) continue;
                    double eta = report.get("supplyEta").getAsDouble();
                    if (Double.isFinite(eta) && eta >= 0 && eta < soonest) { candidate = id; soonest = eta; }
                }
                if (candidate != null) {
                    sendMember(candidate, jobMessage("anticipate-supply"));
                    forecastHintAfter = ticks + 20; // Retry next second if ACKs made the builder decline the hint.
                }
            }
            // A returning runner no longer owns the supply lock. Other workers must still be
            // able to request shared restocking while that runner is catching up.
            if (independentSupplies()) return; // Detached workers admit their own local supply work.
            if (supplyOwner != null && !granted && supplyBarrierReady()) broadcast(reservation("grant"));
            if (supplyOwner != null && reservationRetryDue(granted, ticks - reservationStarted)) {
                // No grant was issued, so the owner cannot have started using a container.
                // Let clearance/road ACKs recover outside the shared hold before trying again.
                broadcast(reservation("release"));
                supplyRetryAfter = ticks + 100;
                warning("Supply clearance stalled before placement; releasing the hold for five seconds, then retrying.");
                return;
            }
            if (supplyOwner == null && ticks >= supplyRetryAfter && !requested.isEmpty()) {
                UUID next = requested.entrySet().stream().filter(entry -> !entry.getValue().has("detach") || !entry.getValue().get("detach").getAsBoolean())
                    .map(Map.Entry::getKey).findFirst().orElse(null);
                if (next == null) return;
                JsonObject request = requested.remove(next);
                supplyOwner = next; supplyCenter = point(num(request, "x"), num(request, "y"), num(request, "z"));
                lock = UUID.randomUUID().toString(); acknowledgments.clear(); granted = false;
                reservationStarted = ticks;
                broadcast(reservation("reserve"));
            }
        }
    }

    /** Fresh storage knowledge and roughly equal carried paving stock precede road permits. */
    private boolean initialStocking() {
        if (TaskWire.flag(assignment, "initialStocked")) return false;
        if (ticks >= initialStockDeadline) {
            assignment.addProperty("initialStockScanned", true); assignment.addProperty("initialStocked", true);
            broadcast(jobMessage("stock-scan-complete")); broadcast(jobMessage("stock-complete")); persist();
            warning("Initial stocking timed out; continuing with ordinary detached-restock fallbacks.");
            return false;
        }
        List<UUID> active = activeMembers();
        if (active.isEmpty() || !active.stream().allMatch(id -> {
            JsonObject report = currentReport(id);
            return report != null && TaskWire.flag(report, "initialStockReady");
        })) return true;
        if (!TaskWire.flag(assignment, "initialStockScanned")) {
            assignment.addProperty("initialStockScanned", true); broadcast(jobMessage("stock-scan-complete")); persist();
            info("Initial ender-chest scan complete; recovering the shared pair before balancing.");
            return true;
        }
        if (active.stream().anyMatch(id -> workerInventory(currentReport(id)) == null)) {
            assignment.addProperty("initialStocked", true); persist(); return false; // Protocol test/legacy host actor without an inventory ledger.
        }
        if (active.stream().anyMatch(id -> {
            JsonObject inventory = workerInventory(currentReport(id));
            return TaskWire.flag(inventory, "busy") || !TaskWire.flag(inventory, "idle");
        })) return true;
        UUID lowest = null; int min = Integer.MAX_VALUE, max = 0;
        for (UUID id : active) {
            int available = ResourceLedger.available(workerInventory(currentReport(id)), ResourceLedger.MATERIALS);
            if (available < min) { min = available; lowest = id; }
            max = Math.max(max, available);
        }
        if (max - min > 1728 && resourceExchange.hostOffer == null) {
            JsonObject report = currentReport(lowest);
            if (ResourceLedger.integer(report.getAsJsonObject("exchange"), "need") < 0) {
                JsonObject balance = jobMessage("resource-balance"); balance.addProperty("resource", ResourceLedger.MATERIALS);
                balance.addProperty("target", 1536); sendMember(lowest, balance);
            }
            return true;
        }
        if (resourceExchange.hostOffer != null) return true;
        assignment.addProperty("initialStocked", true); broadcast(jobMessage("stock-complete")); persist();
        info("Initial storage scan complete; carried paving stock is balanced within one shulker.");
        return false;
    }

    private JsonObject workerInventory(JsonObject report) {
        return report != null && report.has("inventory") ? report.getAsJsonObject("inventory") : null;
    }

    public static boolean needsBeginRetry(JsonObject report) {
        return str(report, "phase").equals("ready") && (!report.has("begun") || !report.get("begun").getAsBoolean());
    }

    public static boolean reservationRetryDue(boolean granted, int elapsed) { return !granted && elapsed >= 200; }

    public static boolean rejoinArmed(boolean armed, boolean nearby) { return armed || !nearby; }

    private boolean workflowReady(UUID id) {
        return !assignment.has("workflowMembers") || !assignment.getAsJsonArray("workflowMembers").contains(JSON.toJsonTree(id.toString()))
            || nativeReady(id);
    }

    protected boolean coordinateAvailability() {
        if (!assigned() || stopped || releasing || phase.equals("paused")) return false;
        P front = returnRendezvous();
        boolean resuming = false;
        for (UUID id : participants.keySet()) {
            if (participants.get(id) != null && !participants.get(id).connected()) continue;
            JsonObject r = currentReport(id);
            if (r == null || !r.has("x") || !str(assignment, "scope").equals(str(r, "scope"))) continue;
            boolean off = r.has("moduleOff") && r.get("moduleOff").getAsBoolean();
            if (str(r,"phase").equals("failed")) continue;
            if (!off) { offRangeArmed.remove(id); continue; }
            boolean nearby = validRenderedReturn(assignment, id, r) || hostNearby(front, point(num(r, "x"), num(r, "y"), num(r, "z")));
            boolean armed = rejoinArmed(offRangeArmed.getOrDefault(id, false), nearby);
            offRangeArmed.put(id, armed);
            if (nearby && armed) { sendMember(id, jobMessage("resume-builder")); resuming = true; }
        }
        if (resuming) return false; // Wait for fresh telemetry instead of withdrawing a just-resumed worker.
        if (regrouping || ticks < regroupRetryAfter || safeProgress() >= num(assignment, "length")) return false;
        JsonObject old = awayMembers(assignment), next = old.deepCopy();
        for (UUID id : participants.keySet()) {
            if (detachedMembers(assignment).contains(id) || id.equals(supplyOwner)) continue; // Keep physical supply ownership intact.
            JsonObject report = currentReport(id);
            if (participants.get(id) != null && !participants.get(id).connected()
                || report == null || !report.has("x") || !str(assignment, "scope").equals(str(report, "scope"))
                || independentSupplies() && !workflowReady(id)) {
                next.addProperty(id.toString(), true);
                continue;
            }
            boolean nearby = validRenderedReturn(assignment, id, report) || hostNearby(front, point(num(report, "x"), num(report, "y"), num(report, "z")));
            boolean off = report.has("moduleOff") && report.get("moduleOff").getAsBoolean();
            if (!old.has(id.toString())) {
                if (off) next.addProperty(id.toString(), offRangeArmed.getOrDefault(id, !nearby));
            } else {
                boolean armed = rejoinArmed(old.get(id.toString()).getAsBoolean(), nearby);
                next.addProperty(id.toString(), armed);
                if (nearby && (armed || !off) && report.has("awayReady") && report.get("awayReady").getAsBoolean()) next.remove(id.toString());
            }
        }
        if (old.keySet().equals(next.keySet())) {
            if (!old.equals(next)) { assignment.add("awayMembers", next); persist(); }
            return false;
        }
        Set<UUID> remaining = new LinkedHashSet<>(participants.keySet()); remaining.removeIf(id -> next.has(id.toString()));
        if (remaining.isEmpty() && !independentSupplies()) return false;
        try { if (!remaining.isEmpty()) applyWorkflowDuties(assignment.deepCopy(), remaining); }
        catch (IllegalArgumentException e) { return false; }
        // Restore returners before withdrawing workers, so a simultaneous swap never has an empty active roster.
        for (boolean withdrawing : List.of(false, true)) for (UUID id : participants.keySet()) {
            if (Objects.equals(old.get(id.toString()), next.get(id.toString()))) continue;
            if (next.has(id.toString()) != withdrawing) continue;
            JsonObject update = serviceChange(next.has(id.toString()) ? "service-away" : "service-back", id,
                Math.incrementExact(serviceRevision(assignment, id)));
            if (next.has(id.toString())) update.add("armed", next.get(id.toString()).deepCopy());
            broadcast(update);
        }
        info("Updating off-duty workers in place; %d builders cover the highway without a crew-wide stop.", activeMembers().size());
        return false;
    }

    protected void finishAvailability() {
        JsonObject next = availabilityAssignment(assignment, availabilityChange, safeProgress());
        next.addProperty("keepPaused", regroupWasPaused);
        int index = 0;
        for (var member : participants.entrySet()) {
            JsonObject message = next.deepCopy(); message.addProperty("type", "reconfigure"); message.addProperty("index", index++);
            if (!localParticipant && num(message, "index") == 0) installAssignment(message);
            if (member.getValue() == null) apply(message); else member.getValue().send(JSON.toJson(message));
        }
        reports.clear(); requested.clear(); acknowledgments.clear(); availabilityChange = null;
    }

    public static JsonObject availabilityAssignment(JsonObject current, JsonObject away, int progress) {
        if (progress < 0 || progress >= num(current, "length")) throw new IllegalArgumentException("Only unfinished work can redistribute duties");
        JsonObject next = current.deepCopy(); next.add("awayMembers", away.deepCopy());
        if (away.has(str(next, "detachedMember"))) next.addProperty("detachedMember", "");
        if (next.has("suppliers")) away.keySet().forEach(next.getAsJsonObject("suppliers")::remove);
        List<UUID> roster = next.getAsJsonArray("members").asList().stream().map(id -> UUID.fromString(id.getAsString())).toList();
        next.add("activeMembers", JSON.toJsonTree(roster.stream().filter(id -> !away.has(id.toString()) && !detachedMembers(next).contains(id)).map(UUID::toString).toList()));
        next.addProperty("generation", num(current, "generation") + 1); next.addProperty("startRow", progress);
        validateActiveMembers(next, Set.copyOf(roster));
        applyWorkflowDuties(next.deepCopy(), Set.copyOf(activeMembers(next)));
        return next;
    }

    private void coordinateSupplyContainers() {
        JsonObject next = supplyContainers.deepCopy();
        next.keySet().retainAll(participants.keySet().stream().map(UUID::toString).toList());
        for (UUID member : participants.keySet()) {
            JsonObject report = currentReport(member);
            if (report == null || !str(assignment, "scope").equals(str(report, "scope")) || !report.has("supplyContainers")) continue;
            JsonArray locations = checkedSupplyContainers(assignment, report.get("supplyContainers"));
            if (locations.isEmpty()) next.remove(member.toString()); else next.add(member.toString(), locations);
        }
        if (!next.equals(supplyContainers) || ticks % 20 == 0) {
            JsonObject update = jobMessage("supply-containers"); update.add("containers", next); broadcast(update);
        }
    }

    public static JsonArray checkedSupplyContainers(JsonObject assignment, JsonElement value) {
        JsonArray result = new JsonArray();
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > 2) return result;
        JsonObject layout = assignment.getAsJsonObject("layout");
        try {
            for (JsonElement entry : value.getAsJsonArray()) {
                JsonObject site = entry.getAsJsonObject(), checked = new JsonObject();
                for (String axis : List.of("x", "y", "z")) {
                    if (!site.getAsJsonPrimitive(axis).isNumber()) return new JsonArray();
                    checked.addProperty(axis, site.get(axis).getAsBigDecimal().intValueExact());
                }
                long x = (long) num(checked, "x") - num(assignment, "x"), z = (long) num(checked, "z") - num(assignment, "z");
                long row = x * num(layout, "dx") + z * num(layout, "dz"), side = x * num(layout, "dz") - z * num(layout, "dx");
                if (row < -128 || row > (long) num(assignment, "length") + 12 || Math.abs(side) > num(layout, "width") + 12
                    || Math.abs((long) num(checked, "y") - num(assignment, "y")) > 10) return new JsonArray();
                result.add(checked);
            }
        } catch (RuntimeException malformed) { return new JsonArray(); }
        return result;
    }

    public static boolean validSharedContainer(JsonObject assignment, JsonObject site) {
        try {
            JsonObject layout = assignment.getAsJsonObject("layout");
            long x = site.getAsJsonPrimitive("x").getAsBigDecimal().intValueExact() - num(assignment, "x");
            long y = site.getAsJsonPrimitive("y").getAsBigDecimal().intValueExact();
            long z = site.getAsJsonPrimitive("z").getAsBigDecimal().intValueExact() - num(assignment, "z");
            long row = x * num(layout, "dx") + z * num(layout, "dz");
            long side = x * num(layout, "dz") - z * num(layout, "dx");
            return y == num(assignment, "y") && row >= -128 && row <= num(assignment, "length")
                && Math.abs(side) <= num(layout, "width");
        } catch (RuntimeException malformed) { return false; }
    }

    protected boolean supplyBarrierReady() {
        // Detached actors own their local placement/clearance checks. A distant worker
        // must never veto recovery by withholding a crew-wide acknowledgement.
        if (supplyOwner != null && detachedMembers(assignment).contains(supplyOwner)) return true;
        Set<UUID> nearby = new HashSet<>(participants.keySet());
        if (compactReservation()) {
            for (UUID member : participants.keySet()) {
                JsonObject report = currentReport(member);
                if (!member.equals(supplyOwner) && report != null && str(assignment, "scope").equals(str(report, "scope"))
                    && hostOutsidePickup(report, pickupCenter)) nearby.remove(member);
            }
            return barrierReady(nearby, supplyOwner, acknowledgments);
        }
        for (UUID detached : detachedMembers(assignment)) {
            JsonObject report = currentReport(detached);
            if (!detached.equals(supplyOwner) && hostDistant(report, supplyCenter)) nearby.remove(detached);
        }
        // Active builders on the verified front need not retreat to acknowledge a
        // distant rear container. Missing/stale telemetry still cannot grant clearance.
        if (supplyOwner != null && detachedMembers(assignment).contains(supplyOwner)) for (UUID member : participants.keySet()) {
            JsonObject report = currentReport(member);
            if (!member.equals(supplyOwner) && report != null && str(assignment, "scope").equals(str(report, "scope")) && hostOutsideSupply(report, supplyCenter)) nearby.remove(member);
        }
        return barrierReady(nearby, supplyOwner, acknowledgments);
    }

    protected JsonObject currentReport(UUID id) {
        JsonObject report = reports.get(id);
        if (report == null || !matchesGeneration(job, generation, report)) return null;
        SwarmConnection connection = participants.get(id);
        if (connection != null && (peers.get(connection) != report || !connection.connected() || System.nanoTime() - reportTimes.getOrDefault(id, 0L) > 3_000_000_000L)) return null;
        return report;
    }

    protected boolean canDetach(UUID member) {
        // The verified checkpoint is the front while nobody is building; requiring a
        // live anchor here deadlocks the final active worker when it needs supplies.
        return ticks >= regroupRetryAfter && detachAllowed(activeMembers(), member) && safeProgress() < num(assignment, "length");
    }

    public static boolean detachAllowed(Collection<UUID> active, UUID member) { return active.contains(member); }

    protected void departSupply(UUID supplier) {
        int progress = safeProgress();
        JsonObject planned = laneRestocking(assignment)
            ? laneSupplyAssignment(assignment, supplier, progress) : supplyAssignment(assignment, supplier, true, progress);
        JsonObject departure = serviceChange("service-detach", supplier, Math.incrementExact(serviceRevision(assignment, supplier)));
        departure.add("site", suppliers(planned).get(supplier.toString()).deepCopy());
        broadcast(departure);
        info("%s is resupplying independently; remaining builders share the road.", memberName(supplier));
    }

    public static boolean laneRestocking(JsonObject current) {
        JsonObject layout = current.getAsJsonObject("layout");
        return num(layout, "width") == 5 && (num(layout, "dx") == 0 || num(layout, "dz") == 0);
    }

    public static boolean laneSite(JsonObject site) { return site != null && site.has("lane") && site.get("lane").getAsBoolean(); }

    public static JsonObject laneSupplyAssignment(JsonObject current, UUID worker, int progress) {
        JsonObject next = supplyAssignment(current, worker, true, progress);
        List<UUID> roster = preferredMembers(current);
        int index = roster.indexOf(worker);
        if (!laneRestocking(current) || index < 0) throw new IllegalArgumentException("Invalid lane supply worker");
        JsonObject layout = current.getAsJsonObject("layout"), site = suppliers(next).getAsJsonObject(worker.toString());
        int offset = 2 - (2 * index + 1) * 5 / (2 * roster.size());
        site.addProperty("x", num(current, "x") + num(layout, "dx") * progress + num(layout, "dz") * offset);
        site.addProperty("z", num(current, "z") + num(layout, "dz") * progress - num(layout, "dx") * offset);
        site.addProperty("lane", true);
        return next;
    }

    public static boolean validSupplySite(JsonObject current, JsonObject site) {
        JsonObject layout = current.getAsJsonObject("layout");
        long x = (long) num(site, "x") - num(current, "x"), z = (long) num(site, "z") - num(current, "z");
        long row = x * num(layout, "dx") + z * num(layout, "dz"), lateral = x * num(layout, "dz") - z * num(layout, "dx");
        return num(site, "y") == num(current, "y") && (laneSite(site)
            ? laneRestocking(current) && Math.abs(lateral) <= 2 && row >= 0 && row < num(current, "length")
            : lateral == 0 && row >= -90 && row <= num(current, "length") - (compactSite(site) ? SUPPLY_SPACING : 10));
    }

    protected void finishServiceRegroup() {
        boolean detaching = detachingWorker != null;
        UUID supplier = detaching ? detachingWorker : rejoiningWorker;
        if (detaching && supplier.equals(supplyOwner)) broadcast(reservation("release")); // The departing active builder has settled its old work/unused lock.
        JsonObject base = supplyAssignment(assignment, supplier, detaching, safeProgress());
        base.addProperty("keepPaused", regroupWasPaused);
        base.addProperty("supplyHandoff", true);
        int index = 0;
        for (var member : participants.entrySet()) {
            JsonObject next = base.deepCopy(); next.addProperty("type", "reconfigure"); next.addProperty("index", index++);
            if (!localParticipant && num(next, "index") == 0) installAssignment(next);
            if (member.getValue() == null) apply(next); else member.getValue().send(JSON.toJson(next));
        }
        detachingWorker = rejoiningWorker = null; rejoiningSupply = false; supplyHandoff = false; reports.clear(); requested.clear();
        info(detaching ? "%s is resupplying behind the crew; %d builders now share the road." : "%s returned to their original crew position; duties are restored.", memberName(supplier), activeMembers().size());
    }

    public static JsonObject supplyAssignment(JsonObject current, UUID worker, boolean detach, int progress) {
        JsonObject next = current.deepCopy(), runners = suppliers(current).deepCopy();
        List<UUID> roster = current.getAsJsonArray("members").asList().stream().map(id -> UUID.fromString(id.getAsString())).toList();
        if (progress < 0 || progress >= num(current, "length") || !roster.contains(worker)
            || detach != activeMembers(current).contains(worker) || !detach && !runners.has(worker.toString()))
            throw new IllegalArgumentException("Invalid supply lane handoff");
        if (detach) {
            // Distinct rear staging sites; the native travel sweep verifies even pre-job road.
            int row = progress - SUPPLY_SPACING;
            JsonObject direction = current.getAsJsonObject("layout");
            Map<Integer, Integer> occupied = new HashMap<>();
            for (var entry : runners.entrySet()) {
                JsonObject site = entry.getValue().getAsJsonObject();
                occupied.put((num(site, "x") - num(current, "x")) * num(direction, "dx") + (num(site, "z") - num(current, "z")) * num(direction, "dz"), compactSite(site) ? SUPPLY_SPACING : 16);
            }
            while (true) {
                int candidate = row;
                if (occupied.entrySet().stream().noneMatch(other -> Math.abs(other.getKey() - candidate) < other.getValue())) break;
                row -= SUPPLY_SPACING;
            }
            JsonObject layout = current.getAsJsonObject("layout"), site = new JsonObject();
            site.addProperty("x", num(current, "x") + num(layout, "dx") * row);
            site.addProperty("y", num(current, "y"));
            site.addProperty("z", num(current, "z") + num(layout, "dz") * row);
            site.addProperty("compact", true);
            runners.add(worker.toString(), site);
        } else runners.remove(worker.toString());
        next.add("suppliers", runners); next.remove("detachedMember");
        next.remove("serviceX"); next.remove("serviceY"); next.remove("serviceZ"); next.remove("serviceLock");
        next.add("activeMembers", JSON.toJsonTree(roster.stream().filter(id -> !runners.has(id.toString()) && !awayMembers(next).has(id.toString())).map(UUID::toString).toList()));
        next.addProperty("generation", num(current, "generation") + 1); next.addProperty("startRow", progress);
        validateActiveMembers(next, Set.copyOf(roster));
        return next;
    }

    protected void coordinateServiceReturn() {
        for (UUID member : List.copyOf(pendingRejoins.keySet())) {
            JsonObject report = currentReport(member);
            if (report != null && serviceRevision(report, member) == serviceRevision(assignment, member)
                && !detachedMembers(report).contains(member)) pendingRejoins.remove(member);
            else if (ticks - pendingRejoins.get(member) >= 20) {
                pendingRejoins.remove(member);
                if (activeMembers().contains(member) && safeProgress() < num(assignment, "length")) {
                    departSupply(member);
                    warning("%s has not accepted its work assignment; keeping it detached while other builders continue.", memberName(member));
                }
            }
        }
        for (UUID supplier : detachedMembers(assignment)) {
            coordinateServiceReturn(supplier);
            if (regrouping) return;
        }
    }

    protected String serviceReturnBlocker(UUID supplier) {
        if (!begun) return "waiting for job to begin";
        if (resourceTransfer(supplier)) return "inventory exchange active";
        JsonObject report = currentReport(supplier);
        if (report == null) return "waiting for fresh worker report";
        if (report.has("exchange") && num(report.getAsJsonObject("exchange"), "need") >= 0) return "worker requests resources";
        if (!independentSupplies() && supplier.equals(supplyOwner)) return "worker holds supply reservation";
        if (!TaskWire.flag(report, "serviceReturning")) return "worker has not finished supplies";
        if (serviceRevision(report, supplier) != serviceRevision(assignment, supplier))
            return "service revision mismatch: worker=" + serviceRevision(report, supplier) + ", host=" + serviceRevision(assignment, supplier);
        return "";
    }

    protected String serviceReturnGate(UUID supplier) {
        if (stopped || phase.equals("paused")) return "host paused/disconnected";
        if (regrouping || releasing) return "host changing/ending assignment";
        String blocker = serviceReturnBlocker(supplier);
        if (!blocker.isEmpty()) return blocker;
        P front = rowCenter(serviceFrontRow(safeProgress(), num(assignment, "length")));
        if (!hostReturnReady(assignment, supplier, currentReport(supplier), front)) return "waiting for worker landing/cleanup readiness";
        return ticks < regroupRetryAfter ? "waiting for handoff retry" : "ready to restore duties";
    }

    protected void coordinateServiceReturn(UUID supplier) {
        JsonObject report = currentReport(supplier);
        P front = rowCenter(serviceFrontRow(safeProgress(), num(assignment, "length")));
        if (checkpointRow == num(assignment, "length")
            && activeMembers().stream().allMatch(id -> currentReport(id) != null && "complete".equals(str(currentReport(id), "phase")))) {
            sendMember(supplier, jobMessage("service-complete"));
            return;
        }
        boolean currentReturn = report != null && TaskWire.flag(report, "serviceReturning")
            && serviceRevision(report, supplier) == serviceRevision(assignment, supplier);
        boolean rendered = currentReturn && validRenderedReturn(assignment, supplier, report);
        // Outside entity-tracking range (or with every worker resupplying), keep a
        // coarse approach point fresh. Navigation cannot wait on inventory exchange;
        // only restoring the worker's lane remains gated below.
        if (currentReturn && needsServiceFront(rendered, !activeMembers().isEmpty()) && ticks % 20 == 0) {
            JsonObject update = jobMessage("service-front"); update.addProperty("x", x(front)); update.addProperty("y", y(front)); update.addProperty("z", z(front));
            update.addProperty("serviceRevision", serviceRevision(assignment, supplier));
            sendMember(supplier, update);
        }
        if (!serviceReturnBlocker(supplier).isEmpty()) return;
        // Readiness refers to the worker's live entity destination, not an old host
        // checkpoint. The worker has landed and settled recovery before setting it.
        if (!hostReturnReady(assignment, supplier, report, front)) return;
        if (checkpointRow == num(assignment, "length")) return;
        if (ticks < regroupRetryAfter) return;
        JsonObject join = serviceChange("service-join", supplier, Math.incrementExact(serviceRevision(assignment, supplier)));
        broadcast(join);
        if (independentSupplies() && !detachedMembers(assignment).contains(supplier)) pendingRejoins.put(supplier, ticks);
        info("%s is merging into the moving crew; existing builders keep working.", memberName(supplier));
    }

    public static boolean needsServiceFront(boolean renderedCrew, boolean hasActiveBuilder) {
        return !renderedCrew || !hasActiveBuilder;
    }

    public static JsonObject joinedSupplyAssignment(JsonObject current, UUID supplier) {
        if (!detachedMembers(current).contains(supplier)) return current;
        JsonObject next = supplyAssignment(current, supplier, false, num(current, "startRow"));
        next.addProperty("generation", num(current, "generation"));
        return next;
    }

    protected void retryServiceUpdates() {
        // TCP is ordered, but a disconnect can lose an incremental update. Reconcile from
        // the worker's actual roster, not a timeout pretending that it accepted the handoff.
        for (UUID member : participants.keySet()) {
            JsonObject report = currentReport(member);
            if (report == null || !report.has("suppliers")) continue;
            for (var entry : serviceRevisions(assignment).entrySet()) {
                UUID supplier = UUID.fromString(entry.getKey());
                if (!participants.containsKey(supplier)) continue; // A borrowed worker belongs to another execution for now.
                if (serviceRevision(report, supplier) >= entry.getValue().getAsInt()) continue;
                boolean detached = detachedMembers(assignment).contains(supplier);
                boolean away = awayMembers(assignment).has(supplier.toString());
                JsonObject update = serviceChange(away ? "service-away" : detached ? "service-detach" : "service-back", supplier, entry.getValue().getAsInt());
                if (away) update.add("armed", awayMembers(assignment).get(supplier.toString()).deepCopy());
                if (detached) update.add("site", suppliers(assignment).get(supplier.toString()).deepCopy());
                sendMember(member, update);
            }
        }
    }

    protected JsonObject serviceChange(String type, UUID supplier, int revision) {
        JsonObject update = jobMessage(type);
        update.addProperty("supplier", supplier.toString()); update.addProperty("serviceRevision", revision);
        return update;
    }

    protected static JsonObject serviceRevisions(JsonObject record) {
        return record.has("serviceRevisions") ? record.getAsJsonObject("serviceRevisions") : new JsonObject();
    }

    public static int serviceRevision(JsonObject record, UUID supplier) {
        JsonObject revisions = serviceRevisions(record);
        return revisions.has(supplier.toString()) ? num(revisions, supplier.toString()) : 0;
    }

    public static boolean serviceRequestCurrent(JsonObject record, UUID supplier, JsonObject request) {
        return request.has("serviceRevision") && num(request, "serviceRevision") == serviceRevision(record, supplier);
    }

    public static JsonObject serviceUpdateAssignment(JsonObject current, JsonObject update) {
        UUID supplier = UUID.fromString(str(update, "supplier"));
        int revision = num(update, "serviceRevision");
        if (revision <= serviceRevision(current, supplier)) return current;
        List<UUID> roster = current.getAsJsonArray("members").asList().stream().map(id -> UUID.fromString(id.getAsString())).toList();
        if (!roster.contains(supplier)) throw new IllegalArgumentException("Supply update belongs to another crew");
        JsonObject next = current.deepCopy(), runners = suppliers(current).deepCopy();
        if (str(update, "type").equals("service-detach")) {
            if (awayMembers(current).has(supplier.toString())) throw new IllegalArgumentException("Off-duty worker cannot depart for supplies");
            JsonObject site = update.getAsJsonObject("site"), layout = current.getAsJsonObject("layout");
            if (!validSupplySite(current, site))
                throw new IllegalArgumentException("Invalid rear supply site");
            runners.add(supplier.toString(), site.deepCopy());
        } else if (str(update, "type").equals("service-join")) runners.remove(supplier.toString());
        else if (str(update, "type").equals("service-away")) {
            if (runners.has(supplier.toString())) throw new IllegalArgumentException("Recover supplies before going off duty");
            if (!update.has("armed") || !update.getAsJsonPrimitive("armed").isBoolean()) throw new IllegalArgumentException("Invalid return detection state");
            JsonObject away = awayMembers(next).deepCopy(); away.add(supplier.toString(), update.get("armed").deepCopy()); next.add("awayMembers", away);
        } else if (str(update, "type").equals("service-back")) {
            JsonObject away = awayMembers(next).deepCopy(); away.remove(supplier.toString()); next.add("awayMembers", away);
            runners.remove(supplier.toString()); // Reconciles a lost supply-return update too.
        }
        else throw new IllegalArgumentException("Invalid supply update type");
        next.add("suppliers", runners); next.remove("detachedMember");
        next.add("activeMembers", JSON.toJsonTree(roster.stream().filter(id -> !runners.has(id.toString()) && !awayMembers(next).has(id.toString())).map(UUID::toString).toList()));
        JsonObject revisions = serviceRevisions(current).deepCopy(); revisions.addProperty(supplier.toString(), revision); next.add("serviceRevisions", revisions);
        validateActiveMembers(next, Set.copyOf(roster));
        return next;
    }

    protected void sendMember(UUID member, JsonObject message) {
        traceCommand(member.toString(), message);
        SwarmConnection connection = participants.get(member);
        if (connection == null) apply(message); else connection.send(JSON.toJson(message));
    }

    protected void finishRegroup() {
        SwarmConnection joining = eligibleJoin(joiningWorker);
        Set<UUID> previous = Set.copyOf(participants.keySet());
        JsonObject base = assignment.deepCopy();
        List<UUID> preferred = new ArrayList<>(preferredMembers(base));
        if (!preferred.contains(joiningWorker)) preferred.add(joiningWorker);
        Map<UUID, SwarmConnection> nextMembers = new LinkedHashMap<>();
        for (UUID id : returnedMembers(preferred, participants.keySet(), joiningWorker)) {
            if (id.equals(joiningWorker)) nextMembers.put(id, joining);
            else if (participants.containsKey(id)) nextMembers.put(id, participants.get(id));
        }
        base.add("preferredMembers", JSON.toJsonTree(preferred.stream().map(UUID::toString).toList()));
        if (!base.has("preferredWorkflows")) base.add("preferredWorkflows", base.has("memberWorkflows") ? base.get("memberWorkflows").deepCopy() : new JsonObject());
        if (base.has("borrowedMembers")) base.getAsJsonObject("borrowedMembers").remove(joiningWorker.toString());
        base.addProperty("generation", generation + 1); base.addProperty("startRow", safeProgress());
        base.addProperty("keepPaused", regroupWasPaused);
        base.addProperty("count", nextMembers.size());
        base.add("members", JSON.toJsonTree(nextMembers.keySet().stream().map(UUID::toString).toList()));
        base.add("activeMembers", JSON.toJsonTree(nextMembers.keySet().stream().filter(id -> !awayMembers(base).has(id.toString())).map(UUID::toString).toList()));
        restoreMemberWorkflows(base, List.copyOf(nextMembers.keySet()));
        normalizeWorkflows(base, nextMembers.keySet());
        participants.clear(); participants.putAll(nextMembers);
        int index = 0;
        for (var member : participants.entrySet()) {
            JsonObject next = base.deepCopy(); next.addProperty("index", index++);
            next.addProperty("type", previous.contains(member.getKey()) ? "reconfigure" : "prepare");
            if (!localParticipant && num(next, "index") == 0) installAssignment(next);
            if (member.getValue() == null) apply(next);
            else member.getValue().send(JSON.toJson(next));
        }
        joiningWorker = null; reports.clear(); requested.clear(); acknowledgments.clear();
        info("Crew expanded to %d workers. Moving to the new lanes before resuming the saved front.", participants.size());
    }

    public static boolean acknowledgeEnd(Map<String, Set<UUID>> pending, String job, UUID member) {
        Set<UUID> members = pending.get(job);
        if (members == null || !members.remove(member)) return false;
        if (members.isEmpty()) pending.remove(job);
        return true;
    }

    public static boolean workReportChanged(JsonObject previous, JsonObject current) {
        if (previous == null) return true;
        for (String key : List.of("job", "generation", "currentRow", "verifiedBase", "verifiedMask", "currentResolved", "mining", "phase", "regroupReady", "begun", "serviceRevisions"))
            if (!Objects.equals(previous.get(key), current.get(key))) return true;
        return false;
    }

    protected void receive(UUID sender, JsonObject m) {
        if (!assigned() || !participants.containsKey(sender)) return;
        // A runner may send its release just before receiving a lane-only generation update.
        // The unchanged execution + reservation token, not the road generation, owns that container.
        boolean supplyControl = supplyControlMatches(job, generation, supplyOwner, lock, sender, m);
        if (!matchesGeneration(job, generation, m) && !supplyControl) return;
        switch (str(m, "type")) {
            case "request" -> {
                if (regrouping) return;
                if (!serviceRequestCurrent(assignment, sender, m)) return;
                if (independentSupplies() && !TaskWire.flag(m, "detach")) return;
                if (supplyOwner != null && supplyOwner.equals(sender) && !(m.has("detach") && m.get("detach").getAsBoolean() && activeMembers().contains(sender))) return;
                if (m.has("detach") && m.get("detach").getAsBoolean()) requested.put(sender, m.deepCopy());
                else requested.putIfAbsent(sender, m.deepCopy());
            }
            case "release" -> {
                if (sender.equals(supplyOwner) && lock.equals(str(m, "lock"))) {
                    JsonObject release = reservation("release");
                    release.addProperty("retry", m.has("retry") && m.get("retry").getAsBoolean());
                    broadcast(release);
                }
            }
            case "clearance" -> {
                if (sender.equals(supplyOwner) && lock.equals(str(m, "lock")) && hostPickupValid(supplyCenter, point(num(m, "x"), num(m, "y"), num(m, "z")))) broadcast(m);
            }
            case "return" -> {
                if (!sender.equals(supplyOwner)) return;
                UUID collector = UUID.fromString(str(m, "collector"));
                if (participants.containsKey(collector)) { m.addProperty("owner", sender.toString()); broadcast(m); }
            }
            default -> { }
        }
    }

    public static boolean supplyControlMatches(String job, int generation, UUID owner, String lock, UUID sender, JsonObject message) {
        return sender.equals(owner) && !lock.isEmpty() && lock.equals(str(message, "lock")) && job.equals(str(message, "job"))
            && message.has("generation") && num(message, "generation") >= 0 && num(message, "generation") <= generation
            && Set.of("release", "clearance", "return").contains(str(message, "type"));
    }

    protected JsonObject reservation(String type) {
        var m = jobMessage(type); m.addProperty("owner", supplyOwner.toString()); m.addProperty("lock", lock);
        m.addProperty("x", x(supplyCenter)); m.addProperty("y", y(supplyCenter)); m.addProperty("z", z(supplyCenter)); return m;
    }

    public static boolean validRenderedReturn(JsonObject assignment, UUID supplier, JsonObject report) {
        if (!report.has("renderedCrew") || !report.get("renderedCrew").isJsonPrimitive()
            || !report.getAsJsonPrimitive("renderedCrew").isString()) return false;
        try {
            UUID anchor = UUID.fromString(str(report, "renderedCrew"));
            return !anchor.equals(supplier) && activeMembers(assignment).contains(anchor) && !awayMembers(assignment).has(anchor.toString());
        } catch (IllegalArgumentException e) { return false; }
    }

    public static boolean compactSite(JsonObject site) { return site != null && site.has("compact") && site.get("compact").getAsBoolean(); }

    protected boolean compactReservation() { return assignment != null && assignment.has("suppliers") && supplyOwner != null && compactSite(assignment.getAsJsonObject("suppliers").getAsJsonObject(supplyOwner.toString())); }

    public static void validateActiveMembers(JsonObject record, Set<UUID> roster) {
        Set<UUID> expected = new LinkedHashSet<>(roster);
        for (var entry : awayMembers(record).entrySet()) {
            UUID id = UUID.fromString(entry.getKey());
            if (!roster.contains(id) || !entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException("Invalid off-duty worker");
            expected.remove(id);
        }
        Set<UUID> detached = detachedMembers(record);
        for (UUID supplier : detached) if (!expected.remove(supplier)) throw new IllegalArgumentException("Invalid detached supply membership");
        if (expected.isEmpty() && detached.isEmpty() && !TaskWire.flag(record, "independentSupplies")
            || !expected.equals(new LinkedHashSet<>(activeMembers(record)))) throw new IllegalArgumentException("Missing or overlapping active worker duties");
    }

    public static void normalizeWorkflows(JsonObject record, Set<UUID> roster) {
        String operation = str(record.getAsJsonObject("layout"), "operation");
        JsonObject workflow = record.has("workflow") ? BotWorkflows.checkedPlan(record.getAsJsonObject("workflow")) : BotWorkflows.legacyPlan(operation);
        String expected = BotWorkflows.operation(workflow);
        if (!operation.equals(expected) && !(operation.equals("Repair") && expected.equals("Pave"))) throw new IllegalArgumentException("Job geometry and workflow capabilities disagree");
        record.add("workflow", workflow);
        if (record.has("memberWorkflows")) for (var entry : record.getAsJsonObject("memberWorkflows").entrySet()) {
            if (!roster.contains(UUID.fromString(entry.getKey()))) throw new IllegalArgumentException("Worker workflow does not belong to this crew");
            entry.setValue(BotWorkflows.checkedPlan(entry.getValue().getAsJsonObject()));
        }
        applyWorkflowDuties(record, roster);
    }

    protected boolean coordinateWindow() {
        windowDecisionAt = System.nanoTime(); windowDecision = "evaluating reports";
        int length = num(assignment, "length");
        if (activeMembers().isEmpty()) { windowDecision = "no active builders; all detached or away"; return true; }
        Map<UUID, JsonObject> current = new LinkedHashMap<>();
        for (UUID id : activeMembers()) {
            JsonObject report = currentReport(id);
            if (independentSupplies() && pendingRejoins.containsKey(id)
                && (report == null || serviceRevision(report, id) != serviceRevision(assignment, id) || detachedMembers(report).contains(id))) continue;
            // Missing telemetry expires outstanding permits; it is not treated as a worker at row zero.
            if (report == null || !report.has("currentRow") || str(assignment, "scope").isEmpty() || !str(assignment, "scope").equals(str(report, "scope"))) {
                windowDecision = "missing/stale/wrong-scope report: " + id; return false;
            }
            int row = num(report, "currentRow");
            if (row < startRow() || row > length) throw new IllegalStateException("Worker reported progress outside the assigned job");
            current.put(id, report);
        }
        if (current.isEmpty()) { windowDecision = "waiting for a returning builder's assignment receipt"; return true; }
        int minimum = slowestRow(length, current.values().stream().mapToInt(r -> num(r, "currentRow")).toArray());
        UUID trailAuditor = current.entrySet().stream().min(Comparator.comparingInt(entry -> num(entry.getValue(), "currentRow"))).orElseThrow().getKey();
        WorkSharing sharing = workSharing(assignment.getAsJsonObject("layout"));
        boolean shared = sharing == WorkSharing.BreakOrder;
        int renderDistance = shared ? 0 : current.values().stream()
            .filter(report -> report.has("renderDistance"))
            .mapToInt(report -> num(report, "renderDistance"))
            .filter(distance -> distance >= 2 && distance <= 64)
            .min().orElse(0);
        boolean roles = roleFormation(assignment);
        int limit = leadLimit(length, minimum, sharing, renderDistance);
        int excavationFront = roles ? current.entrySet().stream().filter(entry -> duty(entry.getKey()).equals("Excavate"))
            .mapToInt(entry -> num(entry.getValue(), "currentRow")).min().orElse(minimum) : minimum;
        int pavingFront = roles ? current.entrySet().stream().filter(entry -> duty(entry.getKey()).equals("Pave"))
            .mapToInt(entry -> num(entry.getValue(), "currentRow")).min().orElse(minimum) : minimum;
        JsonObject sharedMining = new JsonObject();
        if (shared) {
            Map<UUID, List<P>> targets = new LinkedHashMap<>();
            current.forEach((id, report) -> {
                if (dutyAllows(duty(id), true)) targets.put(id, hostMining(report).stream().filter(this::hostMiningPosition).toList());
            });
            Map<P, UUID> owners = shareTargets(targets, miningOwners);
            miningOwners.clear(); miningOwners.putAll(owners);
            owners.forEach((pos, id) -> sharedMining.addProperty(Long.toString(packed(pos)), id.toString()));
        }
        // Only active builders bound the window. A stalled supply return must not freeze
        // the crew that already took over all of its duties.
        boolean hostAuthority = hostAuthority();
        if (hostAuthority && (!localParticipant || !worldAvailable() || !scope().equals(str(assignment, "scope")))) {
            windowDecision = "participating host world unavailable"; return false;
        }
        // All world reads stay on the client thread. Ordinary crews inspect five forward rows;
        // role formations extend only their dedicated excavation lead to sixteen.
        Map<Integer, Boolean> hostResolved = new HashMap<>();
        if (hostAuthority) {
            if (minimum != startRow()) hostResolved.put(minimum, hostRowResolved(rowCenter(minimum)));
            for (int row = minimum + 1; row <= limit; row++) hostResolved.put(row, hostRowResolved(rowCenter(row)));
        }
        Map<Integer, Boolean> worldRows = Map.copyOf(hostResolved);
        Map<UUID, RowVerification.Progress> progress = new LinkedHashMap<>();
        current.forEach((id, report) -> progress.put(id, RowVerification.Progress.fromReport(report)));
        checkpointRow = RowVerification.checkpoint(hostAuthority, startRow(), checkpointRow, minimum, worldRows, progress.values());
        windowDecision = "sending verification windows";
        for (var member : current.entrySet()) {
            JsonObject report = member.getValue();
            // A reconnect may still be applying a lane update. Keep its existing permit
            // until the roster receipt arrives; never send unknown Break Order owners.
            if (report.has("suppliers") && !report.getAsJsonObject("suppliers").keySet().equals(suppliers(assignment).keySet())) { windowDecision = "awaiting supplier roster ACK: " + member.getKey(); continue; }
            if (report.has("serviceRevisions") && !serviceRevisions(report).equals(serviceRevisions(assignment))) { windowDecision = "awaiting service revision ACK: " + member.getKey(); continue; }
            int row = num(report, "currentRow");
            int memberLimit = roles ? roleLeadLimit(length, row, duty(member.getKey()), excavationFront, pavingFront) : limit;
            int base = row + 1;
            int mask = RowVerification.mask(hostAuthority, base, memberLimit, worldRows, progress.get(member.getKey()));
            JsonObject permit = jobMessage("window");
            permit.addProperty("base", base); permit.addProperty("mask", mask); permit.addProperty("limit", memberLimit);
            permit.addProperty("checkpoint", checkpointRow);
            permit.addProperty("trailAuditor", member.getKey().equals(trailAuditor));
            JsonObject diagnosticPermit = permit.deepCopy(); diagnosticPermit.addProperty("sentAt", System.currentTimeMillis());
            diagnosticPermits.keySet().retainAll(participants.keySet()); diagnosticPermits.put(member.getKey(), diagnosticPermit);
            if (shared) permit.add("mining", sharedMining);
            SwarmConnection connection = participants.get(member.getKey());
            if (connection == null) apply(permit); else connection.send(JSON.toJson(permit));
        }
        return true;
    }
    public static void applyWorkflowDuties(JsonObject value, Set<UUID> roster) {
        JsonObject main = BotWorkflows.checkedPlan(value.getAsJsonObject("workflow"));
        String required = main.get("duty").getAsString(); BotWorkflows.operation(main);
        // An independent crew may temporarily have everyone away or resupplying.
        // Validate that absence, rather than rejecting the restore needed to rejoin.
        if (roster.isEmpty() && TaskWire.flag(value, "independentSupplies") && activeMembers(value).isEmpty()) {
            Set<UUID> members = new LinkedHashSet<>();
            value.getAsJsonArray("members").forEach(id -> members.add(UUID.fromString(id.getAsString())));
            if (!members.isEmpty()) { validateActiveMembers(value, members); return; }
        }
        JsonObject plans = value.has("memberWorkflows") ? value.getAsJsonObject("memberWorkflows") : new JsonObject(), duties = new JsonObject();
        boolean excavates = false, paves = false;
        for (UUID member : roster) {
            JsonObject plan = plans.has(member.toString()) ? BotWorkflows.checkedPlan(plans.getAsJsonObject(member.toString())) : main;
            BotWorkflows.operation(plan); String duty = plan.get("duty").getAsString();
            boolean dig = !duty.equals("Pave"), pave = !duty.equals("Excavate");
            if (required.equals("Pave") && dig || required.equals("Excavate") && pave)
                throw new IllegalArgumentException("A worker workflow cannot add work outside the job workflow.");
            excavates |= dig; paves |= pave; duties.addProperty(member.toString(), duty);
        }
        if (!required.equals("Pave") && !excavates || !required.equals("Excavate") && !paves)
            throw new IllegalArgumentException("This roster must cover all excavation and paving required by the job workflow.");
        value.add("duties", duties);
    }
    protected abstract boolean isHost();
    protected abstract boolean worldAvailable();
    protected abstract UUID me();
    protected abstract UUID hostIdentity();
    protected abstract String scope();
    protected abstract void info(String format, Object... args);
    protected abstract void warning(String format, Object... args);
    protected abstract void persist();
    protected abstract void apply(JsonObject message);
    protected abstract void installAssignment(JsonObject message);
    protected abstract String memberName(UUID worker);
    protected abstract P point(int x, int y, int z);
    protected abstract int x(P point);
    protected abstract int y(P point);
    protected abstract int z(P point);
    protected abstract long packed(P point);
    protected abstract P unpacked(long point);
    protected abstract boolean hostRowResolved(P point);
    protected boolean resourceTransfer(UUID worker) { return resourceExchange.inTransfer(worker); }
    protected abstract P rowCenter(int row);
    public abstract P returnRendezvous();
    protected static boolean workUpdateDue(int tick, boolean assigned, boolean changed) { return tick % (assigned ? 2 : 10) == 0 || assigned && changed; }
    protected List<P> hostMining(JsonObject report) {
        if (!report.has("mining")) return List.of();
        var targets = report.getAsJsonArray("mining");
        if (targets.size() > 3) throw new IllegalArgumentException("Too many mining targets");
        return targets.asList().stream().map(v -> unpacked(Long.parseLong(v.getAsString()))).toList();
    }
    protected boolean hostMiningPosition(P pos) {
        var layout = assignment.getAsJsonObject("layout");
        int dx=num(layout,"dx"), dz=num(layout,"dz"), width=num(layout,"width");
        int px=x(pos)-num(assignment,"x"), pz=z(pos)-num(assignment,"z"), row=px*dx+pz*dz;
        int column=width/2-(px*dz-pz*dx), py=y(pos)-num(assignment,"y");
        return row>=1 && row<=num(assignment,"length") && column>=-1 && column<=width && py>=-1 && py<=num(layout,"height");
    }
    private static <P> Map<P, UUID> shareTargets(Map<UUID,List<P>> reports, Map<P,UUID> previous) {
        Map<P,UUID> result=new LinkedHashMap<>();
        previous.forEach((pos,owner)-> { if(reports.getOrDefault(owner,List.of()).contains(pos)) result.put(pos,owner); });
        reports.forEach((owner,positions)->positions.forEach(pos->result.putIfAbsent(pos,owner))); return result;
    }
    private boolean hostNearby(P front,P worker) { return y(front)==y(worker) && Math.abs((long)x(front)-x(worker))<=16 && Math.abs((long)z(front)-z(worker))<=16; }
    private boolean hostOutsideSupply(JsonObject r,P p) { return r!=null && p!=null && r.has("x") && r.has("y") && r.has("z") && (Math.abs((long)num(r,"x")-x(p))>8 || Math.abs((long)num(r,"z")-z(p))>8 || Math.abs((long)num(r,"y")-y(p))>11); }
    private double distance(P a,P b) { double dx=(double)x(a)-x(b),dy=(double)y(a)-y(b),dz=(double)z(a)-z(b); return dx*dx+dy*dy+dz*dz; }
    private boolean hostDistant(JsonObject r,P p) { return r!=null && p!=null && r.has("x") && r.has("y") && r.has("z") && distance(p,point(num(r,"x"),num(r,"y"),num(r,"z")))>144; }
    private boolean hostPickupValid(P a,P b) { return a!=null && Math.abs((long)x(a)-x(b))<=7 && Math.abs((long)z(a)-z(b))<=7 && Math.abs((long)y(a)-y(b))<=10; }
    private boolean hostOutsidePickup(JsonObject r,P p) {
        if(r==null || p==null || !r.has("x") || !r.has("y") || !r.has("z")) return false;
        double distance=0;
        for(String axis:List.of("x","y","z")) { double target=(axis.equals("x")?x(p):axis.equals("y")?y(p):z(p))+.5; double delta=Math.max(0,Math.max(num(r,axis)-target,target-num(r,axis)-1)); distance+=delta*delta; }
        return distance>=12.25;
    }
    private boolean hostReturnReady(JsonObject a,UUID supplier,JsonObject r,P fallback) {
        if(!r.has("serviceReady") || !r.get("serviceReady").getAsBoolean()) return false;
        return validRenderedReturn(a,supplier,r) || r.has("x") && r.has("y") && r.has("z") && num(r,"y")==y(fallback) && distance(fallback,point(num(r,"x"),num(r,"y"),num(r,"z")))<=4;
    }

    protected final ResourceExchange resourceExchange = new ResourceExchange();
    public void resolveInventoryTransfer() {
        if (!isHost() || !assigned() || isReleasing()) throw new IllegalStateException("Inspect and resolve transfers on the active job host");
        if (resourceExchange.hostOffer != null) {
            resourceExchange.hostOffer.addProperty("inspected", true); resourceExchange.finish("cancelled");
        } else {
            var command = jobMessage("resource-inspected"); broadcast(command);
        }
    }
    protected final class ResourceExchange {
        public JsonObject hostOffer;
        private int hostSince, nextRequest;
        private final Map<UUID, Integer> unavailableUntil = new HashMap<>();
        public boolean inTransfer(UUID member) {
            return hostOffer != null && (str(hostOffer, "donor").equals(member.toString()) || str(hostOffer, "recipient").equals(member.toString()));
        }
        public JsonObject worker(UUID id) {
            JsonObject r = currentReport(id);
            try {
                if (r == null || !r.has("exchange") || !r.has("inventory")) return null;
                JsonObject inventory = r.getAsJsonObject("inventory"), exchange = r.getAsJsonObject("exchange");
                if (ResourceLedger.integer(inventory, "version") != 1 || !inventory.getAsJsonPrimitive("busy").isBoolean()
                    || inventory.has("echestKnown") && !inventory.getAsJsonPrimitive("echestKnown").isBoolean()
                    || !inventory.getAsJsonPrimitive("idle").isBoolean() || num(exchange, "need") < -1 || num(exchange, "need") >= ResourceLedger.RESOURCES) return null;
                for (String tier : List.of("loose", "shulkers", "echest", "reserve", "target"))
                    for (int resource = 0; resource < ResourceLedger.RESOURCES; resource++) ResourceLedger.value(inventory, tier, resource);
                return r;
            } catch (RuntimeException malformed) { return null; } // One bad/stale report cannot stop unrelated builders.
        }
        public JsonObject exchangeReport(UUID id) {
            JsonObject r = worker(id);
            if (r == null) return null;
            JsonObject e = r.getAsJsonObject("exchange");
            return hostOffer != null && str(e, "id").equals(str(hostOffer, "id")) && e.has("sequence")
                && num(e, "sequence") == num(hostOffer, "sequence") ? e : null;
        }
        public void coordinate() {
            if (!ResourceLedger.Policy.read(assignment.getAsJsonObject("layout")).enabled() || ticks % 10 != 0) return;
            if (hostOffer == null) {
                if (ticks < nextRequest) return;
                for (UUID recipient : participants.keySet()) {
                    JsonObject r = worker(recipient);
                    if (r == null || !r.has("sharedSupplyProtocol") || num(r,"sharedSupplyProtocol") != 1
                        || awayMembers(assignment).has(recipient.toString()) || recipient.equals(supplyOwner)) continue;
                    int resource = ResourceLedger.integer(r.getAsJsonObject("exchange"), "need");
                    if (resource < 0 || resource >= ResourceLedger.RESOURCES || !detachedMembers(assignment).contains(recipient)) continue;
                    UUID donor = null; int most = 0; boolean possible = false;
                    for (UUID candidate : participants.keySet()) {
                        JsonObject d = worker(candidate);
                        JsonObject observation=currentReport(candidate);
                        if(observation!=null&&str(observation,"phase").equals("failed"))continue;
                        if (!candidate.equals(recipient) && !awayMembers(assignment).has(candidate.toString())) {
                            if (d == null && participants.get(candidate) != null && participants.get(candidate).connected()) possible = true;
                            else if (d != null && num(d.getAsJsonObject("exchange"),"need") != resource && ResourceLedger.possibleDonor(d.getAsJsonObject("inventory"),resource)) possible = true;
                        }
                        if (candidate.equals(recipient) || d == null || !d.has("sharedSupplyProtocol") || num(d,"sharedSupplyProtocol") != 1
                            || candidate.equals(supplyOwner) || awayMembers(assignment).has(candidate.toString())
                            || ticks < unavailableUntil.getOrDefault(candidate, 0)
                            || !canDetach(candidate) && !detachedMembers(assignment).contains(candidate)
                            || !Set.of("building", "resupplying", "returning from supplies").contains(str(d, "phase"))
                            || !Set.of("idle", "complete", "cancelled").contains(str(d.getAsJsonObject("exchange"), "stage"))
                            || num(d.getAsJsonObject("exchange"), "need") == resource
                            || d.getAsJsonObject("inventory").get("busy").getAsBoolean()
                            || !d.getAsJsonObject("inventory").get("idle").getAsBoolean()) continue;
                        int surplus = Math.max(0, ResourceLedger.available(d.getAsJsonObject("inventory"), resource) - ResourceLedger.value(d.getAsJsonObject("inventory"), "target", resource));
                        if (surplus == 0 && ResourceLedger.possibleDonor(d.getAsJsonObject("inventory"),resource)) surplus = ResourceLedger.exchangeTarget(r.getAsJsonObject("inventory"),resource);
                        if (surplus > most) { most = surplus; donor = candidate; }
                    }
                    if (donor == null) {
                        if (!possible && r.has("resourceExhaustionProtocol") && num(r,"resourceExhaustionProtocol")==1) {
                            if(!onResourceExhausted(recipient,resource)){JsonObject failure=jobMessage("resource-exhausted");failure.addProperty("resource",resource);sendMember(recipient,failure);}
                        }
                        continue;
                    }
                    JsonObject ledger = r.getAsJsonObject("inventory");
                    int amount = Math.min(most, Math.max(1, ResourceLedger.exchangeTarget(ledger, resource) - ResourceLedger.value(ledger, "loose", resource)));
                    hostOffer = new JsonObject(); hostOffer.addProperty("id", UUID.randomUUID().toString());
                    boolean startup = !TaskWire.flag(assignment, "initialStocked");
                    hostOffer.addProperty("mode", startup ? "drop" : "shared");
                    hostOffer.addProperty("resource", resource); hostOffer.addProperty("donor", donor.toString()); hostOffer.addProperty("recipient", recipient.toString());
                    hostOffer.addProperty("remaining", startup ? Math.max(1728, amount) : amount); hostOffer.addProperty("sequence", 0); hostOffer.addProperty("phase", startup ? "gather" : "shared");
                    JsonObject meeting = suppliers(assignment).getAsJsonObject(recipient.toString());
                    // Two actors use the already-verified rear highway, never the active excavation face.
                    hostOffer.addProperty("x", num(meeting,"x")); hostOffer.addProperty("y", num(meeting,"y")); hostOffer.addProperty("z", num(meeting,"z"));
                    hostSince = ticks; saveHost(); dispatch();
                    if (activeMembers().contains(donor)) departSupply(donor);
                    info("Crew supplies: %s is opening supplies for %s (%s).", memberName(donor), memberName(recipient), ResourceLedger.name(resource));
                    break;
                }
                return;
            }
            UUID donor = UUID.fromString(str(hostOffer, "donor")), recipient = UUID.fromString(str(hostOffer, "recipient"));
            JsonObject d = exchangeReport(donor), r = exchangeReport(recipient);
            if (str(hostOffer, "phase").equals("shared")) {
                if (r != null && str(r, "stage").equals("complete")) { finish("complete"); return; }
                if (ticks - hostSince >= 600 || d != null && str(d, "stage").equals("failed")
                    || r != null && str(r, "stage").equals("failed")) { finish("cancelled"); return; }
                if (d != null && d.has("container")) {
                    JsonObject site = d.getAsJsonObject("container");
                    // A donor may leave its real box behind while the recipient catches up.
                    // Keep the target bounded to this job, not the donor's newest position.
                    if (currentReport(donor) != null && validSharedContainer(assignment, site)) hostOffer.add("container", site.deepCopy());
                    else hostOffer.remove("container");
                } else hostOffer.remove("container");
                dispatch(); return;
            }
            // A worker cannot acknowledge cancellation as safe after its drop was issued.
            // Agree on the preserved uncertain outcome so terminal delivery can finish.
            if (str(hostOffer, "phase").equals("cancelled")
                && (d != null && str(d, "stage").equals("uncertain") || r != null && str(r, "stage").equals("uncertain"))) {
                finish("uncertain"); return;
            }
            boolean terminal = Set.of("complete", "cancelled", "uncertain").contains(str(hostOffer, "phase"));
            if (!terminal && (d != null && Set.of("failed", "uncertain").contains(str(d, "stage")) || r != null && Set.of("failed", "uncertain").contains(str(r, "stage")))) {
                finish("uncertain".equals(d == null ? "" : str(d, "stage")) || "uncertain".equals(r == null ? "" : str(r, "stage")) ? "uncertain" : "cancelled"); return;
            }
            if (!terminal && ticks - hostSince > 1200) {
                if (!str(hostOffer, "phase").equals("drop")) { finish("cancelled"); return; }
                // Re-deliver the SAME intent. Workers retry pickup/server sync; an issued drop is never replayed.
                hostSince = ticks;
                info("Crew transfer is retrying pickup/confirmation of the existing drop at %s, %s, %s.", str(hostOffer, "x"), str(hostOffer, "y"), str(hostOffer, "z"));
            }
            switch (str(hostOffer, "phase")) {
                case "gather" -> {
                    if (d != null && d.has("proposal") && r != null && str(r, "stage").equals("meeting")) {
                        JsonObject proposal = d.getAsJsonObject("proposal");
                        int count = ResourceLedger.integer(proposal, "count");
                        int units = proposal.has("units") ? ResourceLedger.integer(proposal, "units") : count;
                        if (count < 1 || count > 99 || units < count || units > num(hostOffer, "remaining") || proposal.toString().length() > 32768) { finish("cancelled"); return; }
                        hostOffer.add("proposal", proposal.deepCopy()); hostOffer.addProperty("phase", "prepare"); hostSince = ticks; saveHost();
                    }
                }
                case "prepare" -> {
                    if (d != null && r != null && str(d, "stage").equals("ready") && str(r, "stage").equals("ready")) {
                        hostOffer.addProperty("phase", "drop"); hostSince = ticks; saveHost();
                    }
                }
                case "drop" -> {
                    if (d != null && r != null && ResourceLedger.transferConfirmed(str(d, "stage"), str(r, "stage"))) {
                        JsonObject proposal = hostOffer.getAsJsonObject("proposal");
                        int remaining = num(hostOffer, "remaining") - (proposal.has("units") ? num(proposal, "units") : num(proposal, "count"));
                        if (remaining <= 0) { finish("complete"); return; }
                        hostOffer.addProperty("remaining", remaining); hostOffer.addProperty("sequence", num(hostOffer, "sequence") + 1);
                        hostOffer.remove("proposal"); hostOffer.addProperty("phase", "gather"); hostSince = ticks; saveHost();
                    }
                }
                case "complete", "cancelled", "uncertain" -> {
                    boolean done = d != null && str(d, "stage").equals(str(hostOffer, "phase")) && r != null && str(r, "stage").equals(str(hostOffer, "phase"));
                    // Keep replaying the terminal instruction until both parties acknowledge it, including reconnects.
                    if (done || str(hostOffer,"mode").equals("shared") && ticks - hostSince >= 40) {
                        hostOffer = null; assignment.remove("resourceExchange"); persist(); nextRequest = ticks + 20; return;
                    }
                }
            }
            dispatch();
        }
        public void saveHost() { assignment.add("resourceExchange", hostOffer.deepCopy()); persist(); }
        public void dispatch() {
            JsonObject command = jobMessage("resource-exchange"); command.add("offer", hostOffer.deepCopy());
            sendMember(UUID.fromString(str(hostOffer, "donor")), command); sendMember(UUID.fromString(str(hostOffer, "recipient")), command);
        }
        public void finish(String phase) {
            hostOffer.addProperty("phase", phase); hostSince = ticks; saveHost(); dispatch();
            if (!phase.equals("complete") && str(hostOffer,"mode").equals("shared")) {
                unavailableUntil.put(UUID.fromString(str(hostOffer,"donor")), ticks + 100);
                info("Shared supply attempt ended; checking other currently available sources.");
            } else if (!phase.equals("complete")) warning("Crew transfer %s at %s, %s, %s. Any unconfirmed drop is not repeated; inspect the participants in Workers.", phase, str(hostOffer, "x"), str(hostOffer, "y"), str(hostOffer, "z"));
        }
    }
    /** External hosts may replace terminal exhaustion with a higher-priority stash workflow. */
    protected boolean onResourceExhausted(UUID worker,int resource){return false;}
    public SwarmConnection connectionForWorker(UUID id) { return peers.entrySet().stream().filter(e -> e.getKey().connected() && id.toString().equals(str(e.getValue(), "id"))).map(Map.Entry::getKey).findFirst().orElse(null); }

    protected boolean allMembersConnected() {
        return !isHost() || !participants.isEmpty() && participants.values().stream().allMatch(c -> c == null || c.connected());
    }

    public void addWorker(UUID worker) {
        if (!isHost() || !assigned() || stopped || !live() || !allMembersConnected() || phase.equals("complete")) throw new IllegalStateException("Choose a connected, unfinished job first.");
        if (regrouping) throw new IllegalStateException("A worker is already joining; wait for supply recovery and lane positioning.");
        if (ticks < regroupRetryAfter) throw new IllegalStateException("Previous handoff was deferred; allowing the current lanes to progress before retrying.");
        if (detachedMember() != null) throw new IllegalStateException("Wait for the resupplying worker to return before adding another worker.");
        if (participants.size() >= MAX_CREW_MEMBERS || participants.size() >= num(assignment.getAsJsonObject("layout"), "width"))
            throw new IllegalStateException("Highway crews are limited to three workers and available walking lanes.");
        if (!preferredMembers(assignment).contains(worker) && preferredMembers(assignment).size() >= num(assignment.getAsJsonObject("layout"), "width"))
            throw new IllegalStateException("The remaining lanes are reserved for returning workers.");
        eligibleJoin(worker);
        joiningWorker = worker;
        broadcast(jobMessage("regroup"));
        info("Adding %s. Finishing any supply recovery, then synchronizing and redistributing the lanes.", memberName(worker));
    }

    protected SwarmConnection eligibleJoin(UUID worker) {
        SwarmConnection connection = connectionForWorker(worker);
        JsonObject report = connection == null ? null : peers.get(connection);
        if (participants.containsKey(worker)) throw new IllegalStateException("This worker already participates in the job.");
        if (report == null || System.nanoTime() - reportTimes.getOrDefault(worker, 0L) > 3_000_000_000L
            || !str(assignment, "scope").equals(str(report, "scope")) || !report.has("available") || !report.get("available").getAsBoolean() && !nativeReady(worker))
            throw new IllegalStateException("The worker must be connected to this crew, idle, and on the same server and dimension.");
        var layout = assignment.getAsJsonObject("layout");
        P front = rowCenter(safeProgress());
        if (!report.has("x") || !hostNearby(front, point(num(report, "x"), num(report, "y"), num(report, "z"))))
            throw new IllegalStateException("Bring the worker onto the same highway floor within 16 blocks along each axis of the current front.");
        return connection;
    }

    public String borrowingReason(Set<UUID> workers) {
        if (!isHost() || !assigned() || stopped || !live() || !allMembersConnected() || !begun || phase.equals("complete")) return "Choose a connected, running highway job.";
        if (regrouping || !borrowingWorkers.isEmpty()) return "Wait for the current membership handoff.";
        if (ticks < regroupRetryAfter) return "Allow the current lanes to make progress before retrying the handoff.";
        if (detachedMember() != null) return "Wait for the detached supply worker to return.";
        for (UUID member : activeMembers()) {
            JsonObject report = currentReport(member);
            if (report == null || !str(assignment, "scope").equals(str(report, "scope")) || !report.has("x")
                || !hostNearby(returnRendezvous(), point(num(report, "x"), num(report, "y"), num(report, "z"))))
                return "Every active worker must report a fresh, on-site position before handing off the highway.";
        }
        String reason = borrowingBlocker(assignment, workers, localParticipant ? me() : null);
        return reason.isEmpty() ? null : reason;
    }
    public void requestBorrow(Set<UUID> workers) {
        String reason = borrowingReason(workers);
        if (reason != null) throw new IllegalStateException(reason);
        borrowingWorkers.addAll(workers);
        broadcast(jobMessage("regroup"));
        info("Safely handing off %d workers; confirming road work and recovering supply containers first.", workers.size());
    }

    protected void finishBorrow() {
        String blocker = borrowingBlocker(assignment, borrowingWorkers, localParticipant ? me() : null);
        if (!blocker.isEmpty()) {
            broadcast(jobMessage("regroupCancel")); borrowingWorkers.clear(); warning("Worker handoff canceled: %s", blocker); return;
        }
        JsonObject next = borrowedAssignment(assignment, borrowingWorkers, safeProgress());
        next.addProperty("keepPaused", regroupWasPaused);
        Map<UUID, SwarmConnection> remaining = new LinkedHashMap<>(participants);
        borrowingWorkers.forEach(remaining::remove);
        // Persist the complete handoff intent before removing old ownership or issuing withdrawals.
        try { JsonObject record = next.deepCopy(); record.addProperty("progress", safeProgress()); writeRecord(journal(), record); }
        catch (Exception e) { disconnected(); throw new IllegalStateException("Cannot save the worker handoff: " + e.getMessage()); }
        participants.clear(); participants.putAll(remaining);
        int index = 0;
        for (var member : participants.entrySet()) {
            JsonObject message = next.deepCopy(); message.addProperty("type", "reconfigure"); message.addProperty("index", index++);
            if (!localParticipant && num(message, "index") == 0) installAssignment(message);
            if (member.getValue() == null) apply(message); else member.getValue().send(JSON.toJson(message));
        }
        borrowingWorkers.clear(); reports.clear(); requested.clear(); acknowledgments.clear();
        retryWithdrawals();
        info("Highway duties redistributed. Borrowed workers become available only after their clean withdrawal is acknowledged.");
    }

    public static JsonObject borrowedAssignment(JsonObject current, Set<UUID> workers, int progress) {
        JsonObject next = current.deepCopy();
        if (progress < 0 || progress >= num(current, "length")) throw new IllegalArgumentException("Cannot borrow workers from a completed highway.");
        List<UUID> preferred = preferredMembers(current);
        List<UUID> remaining = current.getAsJsonArray("members").asList().stream().map(id -> UUID.fromString(id.getAsString())).filter(id -> !workers.contains(id)).toList();
        if (remaining.isEmpty()) throw new IllegalArgumentException("The highway needs an on-site anchor.");
        next.add("preferredMembers", JSON.toJsonTree(preferred.stream().map(UUID::toString).toList()));
        if (!next.has("preferredWorkflows")) next.add("preferredWorkflows", next.has("memberWorkflows") ? next.get("memberWorkflows").deepCopy() : new JsonObject());
        JsonObject reservations = next.has("borrowedMembers") ? next.getAsJsonObject("borrowedMembers") : new JsonObject();
        for (UUID worker : workers) {
            JsonObject reservation = new JsonObject(); reservation.addProperty("token", UUID.randomUUID().toString());
            reservation.addProperty("generation", current.has("generation") ? num(current, "generation") : 0); reservation.addProperty("ready", false);
            reservations.add(worker.toString(), reservation);
        }
        next.add("borrowedMembers", reservations);
        next.add("members", JSON.toJsonTree(remaining.stream().map(UUID::toString).toList()));
        next.add("activeMembers", JSON.toJsonTree(remaining.stream().filter(id -> !awayMembers(next).has(id.toString())).map(UUID::toString).toList()));
        next.addProperty("count", remaining.size()); next.addProperty("index", 0);
        next.addProperty("generation", (current.has("generation") ? num(current, "generation") : 0) + 1); next.addProperty("startRow", progress);
        restoreMemberWorkflows(next, remaining);
        normalizeWorkflows(next, Set.copyOf(remaining));
        return next;
    }

    protected static void restoreMemberWorkflows(JsonObject record, List<UUID> members) {
        JsonObject preferred = record.has("preferredWorkflows") ? record.getAsJsonObject("preferredWorkflows")
            : record.has("memberWorkflows") ? record.getAsJsonObject("memberWorkflows") : new JsonObject();
        JsonObject live = new JsonObject();
        for (UUID member : members) if (preferred.has(member.toString())) live.add(member.toString(), preferred.get(member.toString()).deepCopy());
        record.add("memberWorkflows", live);
    }

    protected void retryWithdrawals() {
        for (var entry : borrowedMembers().entrySet()) {
            JsonObject reservation = entry.getValue().getAsJsonObject();
            if (reservation.get("ready").getAsBoolean()) continue;
            SwarmConnection connection = connectionForWorker(UUID.fromString(entry.getKey()));
            if (connection == null) continue;
            JsonObject message = jobMessage("withdraw"); message.addProperty("generation", num(reservation, "generation"));
            message.addProperty("token", str(reservation, "token")); connection.send(JSON.toJson(message));
        }
    }

    public static boolean withdrawalMatches(JsonObject reservation, JsonObject message) {
        return reservation != null && str(reservation, "token").equals(str(message, "token"))
            && num(reservation, "generation") == num(message, "generation");
    }

    protected void acknowledgeWithdrawal(UUID member, JsonObject message) {
        if (!assigned() || !job.equals(str(message, "job")) || participants.containsKey(member)) return;
        JsonObject reservation = borrowedMembers().getAsJsonObject(member.toString());
        if (!withdrawalMatches(reservation, message) || reservation.get("ready").getAsBoolean()) return;
        reservation.addProperty("ready", true);
        try { persist(); }
        catch (RuntimeException e) { reservation.addProperty("ready", false); throw e; }
    }

    protected void loadEndings() {
        if (endingsLoaded) return;
        try {
            if (Files.exists(endings())) {
                JsonObject saved = JSON.fromJson(Files.readString(endings()), JsonObject.class);
                for (var entry : saved.entrySet()) {
                    UUID.fromString(entry.getKey());
                    Set<UUID> members = new LinkedHashSet<>();
                    for (var member : entry.getValue().getAsJsonArray()) members.add(UUID.fromString(member.getAsString()));
                    pendingEnds.put(entry.getKey(), members);
                }
            }
            endingsLoaded = true;
        } catch (Exception e) { throw new IllegalStateException("Cannot read pending job endings: " + e.getMessage()); }
    }

    protected void saveEndings() {
        try {
            JsonObject saved = new JsonObject();
            pendingEnds.forEach((id, members) -> saved.add(id, JSON.toJsonTree(members.stream().map(UUID::toString).toList())));
            writeRecord(endings(), saved);
        } catch (Exception e) { throw new IllegalStateException("Cannot save pending job endings: " + e.getMessage()); }
    }

    protected void sendPendingEnd(SwarmConnection connection, UUID member, String ending) {
        Set<UUID> pending = pendingEnds.get(ending);
        if (pending != null && pending.contains(member) && connection.connected()) {
            var m = message("end"); m.addProperty("job", ending); connection.send(JSON.toJson(m));
        }
    }

    public boolean hasPendingEnds(Collection<UUID> workers) {
        loadEndings();
        return pendingEnds.values().stream().anyMatch(members -> members.stream().anyMatch(workers::contains));
    }

    public void endJob() {
        if (!isHost()) throw new IllegalStateException("Only the host can End a crew job. Use the host's Workers tab.");
        JsonObject record = assigned() ? assignment : recoveryRecord();
        if (record == null && Files.exists(journal())) throw new IllegalStateException(recoveryError + ". The host cannot identify the other members; inspect the recovery file before clearing it manually.");
        checkpointJobs(); // Persist the final verified checkpoint before issuing irreversible execution cancellation.
        if (isHost() && record != null) {
            loadEndings();
            String ending = str(record, "job"); UUID.fromString(ending);
            Set<UUID> remaining = new LinkedHashSet<>();
            for (var member : record.getAsJsonArray("members")) remaining.add(UUID.fromString(member.getAsString()));
            if (record.has("borrowedMembers")) for (var entry : record.getAsJsonObject("borrowedMembers").entrySet()) {
                JsonObject reservation = entry.getValue().getAsJsonObject();
                if (!reservation.has("ready") || !reservation.get("ready").getAsBoolean()) remaining.add(UUID.fromString(entry.getKey()));
            }
            if (record.has("hostMember")) {
                if (!str(record, "hostMember").isEmpty()) remaining.remove(UUID.fromString(str(record, "hostMember")));
            } else if (num(record, "index") == 0) remaining.remove(UUID.fromString(record.getAsJsonArray("members").get(0).getAsString()));
            if (!remaining.isEmpty()) pendingEnds.put(ending, remaining);
            saveEndings(); // Durable before releasing the local assignment or sending cancellation.
            if (assigned() && isHost()) broadcast(jobMessage("pause"));
            for (var p : peers.entrySet()) sendPendingEnd(p.getKey(), UUID.fromString(str(p.getValue(), "id")), ending);
        }
        clearLocal();
        info("Job ended. Connected workers clear it automatically; offline workers clear it when they reconnect to this host.");
    }

    public void releaseJob() {
        if (!isHost()) throw new IllegalStateException("Release assignments from their host.");
        if (!assigned() || stopped || !allMembersConnected()) { endJob(); return; }
        if (releasing && regrouping) return; // Idempotent retries must not restart the barrier.
        if (roadComplete()) checkpointJobs(); // Save the result before cleanup changes the live phase.
        releaseStarted = System.nanoTime();
        joiningWorker = detachingWorker = null; rejoiningSupply = false; borrowingWorkers.clear();
        releasing = true;
        var request = jobMessage("regroup"); request.addProperty("releasing", true); broadcast(request);
        info("Stopping work; allowing up to 30 seconds for supply cleanup. Unresolved supplies will be recorded for inspection, not silently forgotten.");
    }

    public static boolean rebindParticipant(Map<UUID, SwarmConnection> participants, UUID id, SwarmConnection connection,
                                     String job, int generation, String scope, JsonObject report) {
        if (!participants.containsKey(id) || participants.get(id) == connection) return false;
        SwarmConnection previous = participants.get(id);
        if (previous == null || previous.connected()) throw new IllegalArgumentException("Another live connection owns this crew member");
        boolean restarting = recoveryOnly(report) && job.equals(str(report,"job")) && num(report,"generation") <= generation;
        if (!connection.connected() || !(matchesGeneration(job, generation, report) || restarting) || !scope.equals(str(report, "scope"))) return false;
        participants.put(id, connection);
        return true;
    }

    public static boolean recoveryOnly(JsonObject report) {
        return report != null && report.has("assignmentLoaded") && !TaskWire.flag(report,"assignmentLoaded") && !str(report,"job").isEmpty();
    }

    /** Same execution, fresh world verification. Recovery is a worker workflow phase, not a new job. */
    private void restoreWorkers() {
        if (releasing || regrouping || phase.equals("paused")) return;
        for (UUID id : List.copyOf(participants.keySet())) {
            SwarmConnection c=participants.get(id); JsonObject r=c==null?null:peers.get(c);
            if (c==null || !c.connected() || !recoveryOnly(r) || !job.equals(str(r,"job")) || !str(assignment,"scope").equals(str(r,"scope"))
                || System.nanoTime()-reportTimes.getOrDefault(id,0L)>3_000_000_000L || !nativeReady(id)) continue;
            // A recovering actor has no live mining claims. Roll duties over immediately.
            if (!stopped && begun && activeMembers().contains(id) && canDetach(id)) departSupply(id);
            if (!TaskWire.flag(r,"recoveryReady")) continue;
            JsonObject restore=assignment.deepCopy(); restore.addProperty("type","restore");
            restore.addProperty("index",assignment.getAsJsonArray("members").asList().stream().map(v -> UUID.fromString(v.getAsString())).toList().indexOf(id)); restore.addProperty("startRow",Math.min(safeProgress(),num(assignment,"length")-1));
            restore.addProperty("keepPaused",pausedBeforeDisconnect); c.send(JSON.toJson(restore));
        }
    }

    protected void resetWindow(int row) {
        miningOwners.clear();
        actualRow = checkpointRow = leadLimit = row; verifiedBase = row + 1; verifiedMask = 0; lastPermit = 0;
    }

    public void pause() { if (isHost() && assigned() && !phase.equals("complete")) broadcast(jobMessage("pause")); else throw new IllegalStateException("Use the job host to pause an unfinished crew job."); }

    public void resume() {
        if (phase.equals("complete")) throw new IllegalStateException("This job is complete. Inspect and End job before preparing another.");
        if (!tryResume()) throw new IllegalStateException("Wait for this same execution to reconnect on its original world; restarted or mismatched jobs require inspection.");
    }

    /** Expected reconnect delays are not controller faults. Persist the caller's intent until true. */
    public boolean tryResume() {
        if (!isHost() || !assigned() || releasing || phase.equals("complete") || !reconnectReady()) return false;
        for (var member : participants.entrySet()) if (member.getValue() != null) {
            if (!resumeMemberRequired(independentSupplies(), activeMembers().contains(member.getKey()), member.getValue().connected())) continue;
            JsonObject report = currentReport(member.getKey());
            if (report == null || !str(assignment, "scope").equals(str(report, "scope")) || !TaskWire.flag(report, "reconnectReady")) return false;
        }
        broadcast(jobMessage("resume"));
        return true;
    }

    public static boolean resumeMemberRequired(boolean independent, boolean active, boolean connected) {
        return !independent || active && connected;
    }

    protected boolean reconnectReady() {
        return assigned() && live() && scope().equals(str(assignment, "scope"))
            && (!localParticipant || localReconnectReady()) && (!isHost() || independentSupplies() || allMembersConnected());
    }

    public static boolean handoffExpired(int now, int started) { return now - started >= 600; }

    public static boolean releaseExpired(long now, long started) { return now - started >= 30_000_000_000L; }

    public static void writeRecord(Path path, JsonObject record) throws java.io.IOException { TaskFiles.write(path, record); }
    protected void startPrepared(JsonObject definition, Map<UUID,SwarmConnection> selected, boolean includeHost, P origin) {
        if (selected.isEmpty() || selected.size() > MAX_CREW_MEMBERS) throw new IllegalArgumentException("Highway crews support 1–3 workers");
        if (hasPendingEnds(selected.keySet()))
            throw new IllegalStateException("A selected worker must acknowledge its previous job ending before joining this one");
        definition = definition.deepCopy();
        for (var entry : selected.entrySet()) {
            if (entry.getValue() == null) continue;
            JsonObject hello = peers.get(entry.getValue());
            if (hello == null || !hello.has("supplyProtocol") || num(hello, "supplyProtocol") != 2)
                throw new IllegalStateException("Update all highway workers to Monocle 0.7.36 or newer for independent restocking");
            if (!hello.has("initialStockProtocol") || num(hello, "initialStockProtocol") != 1)
                throw new IllegalStateException("Update all highway workers for initial ender-chest stocking");
        }
        normalizeWorkflows(definition, selected.keySet());
        String id = UUID.randomUUID().toString();
        int progress = definition.has("progress") ? num(definition,"progress") : 0;
        int sectionLength = num(definition,"length"); String name = str(definition,"name"); JsonObject layout = definition.getAsJsonObject("layout");
        localParticipant = includeHost;
        participants.clear(); participants.putAll(selected); reports.clear(); recoveryTeleports.clear(); supplyContainers = new JsonObject(); pendingRejoins.clear();
        generation = 0;
        List<UUID> stockOwners = new ArrayList<>(participants.keySet());
        UUID initialStockOwner = stockOwners.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(stockOwners.size()));
        int index = 0;
        for (var member : participants.entrySet()) {
            var m = message("prepare");
            m.addProperty("job", id); m.addProperty("name", name); m.addProperty("scope", scope());
            m.addProperty("independentSupplies", true);
            m.add("workflowMembers", JSON.toJsonTree(participants.keySet().stream().filter(this::nativeReady).map(UUID::toString).toList()));
            m.addProperty("catalogId", str(definition, "id")); m.addProperty("generation", generation); m.addProperty("startRow", progress);
            m.addProperty("host", hostIdentity().toString()); m.addProperty("hostMember", includeHost ? me().toString() : "");
            m.addProperty("initialStockOwner", initialStockOwner.toString());
            m.addProperty("x", x(origin));
            m.addProperty("y", y(origin));
            m.addProperty("z", z(origin));
            m.addProperty("index", index++); m.addProperty("count", participants.size());
            m.addProperty("length", sectionLength); m.add("layout", layout.deepCopy());
            m.add("members", JSON.toJsonTree(participants.keySet().stream().map(UUID::toString).toList()));
            m.add("activeMembers", m.get("members").deepCopy());
            if (definition.has("duties")) m.add("duties", definition.get("duties").deepCopy());
            if (definition.has("workflow")) m.add("workflow", definition.get("workflow").deepCopy());
            if (definition.has("memberWorkflows")) m.add("memberWorkflows", definition.get("memberWorkflows").deepCopy());
            m.add("preferredMembers", m.get("members").deepCopy());
            m.add("preferredWorkflows", m.has("memberWorkflows") ? m.get("memberWorkflows").deepCopy() : new JsonObject());
            if (!includeHost && assignment == null) {
                assignment = m.deepCopy(); job = id; phase = "positioning";
                stopped = begun = backstepped = false; resetWindow(progress); persist();
            }
            if (member.getValue() == null) apply(m);
            else member.getValue().send(JSON.toJson(m));
        }
        info("Shared-width job prepared for %d players. Nearby lane positioning is automatic; chat and pause menus can stay open.", participants.size());
    }

    public static String borrowingBlocker(JsonObject record, Set<UUID> workers, UUID localHost) {
        List<UUID> active = activeMembers(record);
        if (workers.isEmpty() || !active.containsAll(workers)) return "Choose workers currently building this highway.";
        if (localHost != null && workers.contains(localHost)) return "The participating host remains on the highway; borrow remote workers.";
        Set<UUID> remaining = new LinkedHashSet<>(active); remaining.removeAll(workers);
        if (remaining.isEmpty()) return "Keep at least one worker at the highway as its site anchor.";
        try { applyWorkflowDuties(record.deepCopy(), remaining); }
        catch (IllegalArgumentException e) { return "Keep workers covering every required highway duty: " + e.getMessage(); }
        return "";
    }
    /** Controls with no local game actor. Also used by client hosts coordinating workers only. */
    protected void applyController(JsonObject m) {
        if (!assigned() || !matchesGeneration(job, generation, m)) return;
        String type = str(m, "type");
        switch (type) {
            case "supply-containers" -> {
                JsonObject next = m.getAsJsonObject("containers");
                if (!next.equals(supplyContainers)) { supplyContainers = next.deepCopy(); persist(); }
            }
            case "service-detach", "service-join", "service-away", "service-back" -> {
                if (stopped || releasing || regrouping || phase.equals("paused")) return;
                UUID supplier = UUID.fromString(str(m, "supplier"));
                if ((type.equals("service-join") || type.equals("service-back") || type.equals("service-away")) && supplier.equals(supplyOwner)) return;
                JsonObject next = serviceUpdateAssignment(assignment, m);
                if (next == assignment) return;
                assignment = next; requested.remove(supplier); workChanged(); persist();
            }
            case "begin" -> {
                if (releasing || begun || stopped || ticks < nextStartAttempt) return;
                begun = true; phase = assignment.has("keepPaused") && assignment.get("keepPaused").getAsBoolean() ? "paused" : "building";
                // Startup storage is an optimization, never a reason to hold a highway crew.
                initialStockDeadline = ticks + 80;
            }
            case "regroup" -> {
                if (releasing && !TaskWire.flag(m, "releasing")) return;
                if (!regrouping) regroupStarted = ticks;
                regroupWasPaused = phase.equals("paused"); regrouping = true; regroupReady = false;
                availabilityChange = m.has("awayMembers") ? m.getAsJsonObject("awayMembers").deepCopy() : null;
                supplyHandoff = TaskWire.flag(m, "supplyHandoff");
                if (supplyHandoff) {
                    UUID supplier = UUID.fromString(str(m, "supplier"));
                    if (TaskWire.flag(m, "detach")) { detachingWorker = supplier; rejoiningWorker = null; rejoiningSupply = false; }
                    else { rejoiningWorker = supplier; detachingWorker = null; rejoiningSupply = true; }
                }
                releasing |= TaskWire.flag(m, "releasing"); requested.clear(); phase = "regrouping";
            }
            case "regroupCancel" -> {
                if (releasing) return;
                regrouping = regroupReady = releasing = false; availabilityChange = null;
                supplyHandoff = false; detachingWorker = rejoiningWorker = null; rejoiningSupply = false;
                phase = regroupWasPaused ? "paused" : begun ? "building" : "positioning";
            }
            case "pause" -> { if (regrouping) regroupWasPaused = true; phase = "paused"; }
            case "resume" -> {
                if (stopped && reconnectReady()) { stopped = false; pausedBeforeDisconnect = false; lastPermit = 0; }
                if (!stopped) {
                    if (regrouping) regroupWasPaused = false;
                    if (assignment.has("keepPaused")) assignment.addProperty("keepPaused", false);
                    phase = regrouping ? "regrouping" : begun ? "building" : "positioning";
                }
            }
            case "lost" -> disconnected();
            case "reserve", "grant" -> {
                if (!lock.equals(str(m, "lock"))) { pickupCenter = null; reservationStarted = ticks; }
                supplyOwner = UUID.fromString(str(m, "owner")); supplyCenter = point(num(m, "x"), num(m, "y"), num(m, "z"));
                if (pickupCenter == null && compactReservation()) {
                    var layout = assignment.getAsJsonObject("layout");
                    pickupCenter = point(x(supplyCenter) - num(layout, "dx"), y(supplyCenter), z(supplyCenter) - num(layout, "dz"));
                }
                lock = str(m, "lock"); granted = type.equals("grant"); persist();
            }
            case "release" -> {
                if (!lock.equals(str(m, "lock"))) return;
                retryingSupply = false; supplyOwner = null; supplyCenter = pickupCenter = null;
                lock = acknowledged = ""; granted = backstepped = false; persist();
            }
            case "clearance" -> {
                P target = point(num(m, "x"), num(m, "y"), num(m, "z"));
                if (lock.equals(str(m, "lock")) && hostPickupValid(supplyCenter, target)) pickupCenter = target;
            }
            default -> { } // Window/return/resource messages operate only on game actors.
        }
    }

    protected void installRemoteAssignment(JsonObject m) {
        boolean keepSupply = supplyHandoff && TaskWire.flag(m, "supplyHandoff");
        assignment = m.deepCopy(); job = str(m, "job"); generation = num(m, "generation"); phase = "positioning";
        assignment.remove("supplyHandoff");
        stopped = begun = backstepped = regrouping = regroupReady = releasing = pausedBeforeDisconnect = false;
        serviceReturning = serviceFinished = false; serviceFront = null; serviceReadyUntil = 0; releaseStarted = 0;
        availabilityChange = null; nextStartAttempt = 0;
        if (!keepSupply) {
            retryingSupply = false; supplyRetryAfter = 0;
            supplyOwner = m.has("suppliers") ? null : detachedMember();
            JsonObject site = supplyOwner == null ? null : suppliers(m).getAsJsonObject(supplyOwner.toString());
            supplyCenter = site == null ? null : point(num(site,"x"), num(site,"y"), num(site,"z"));
            lock = supplyOwner == null ? "" : str(m,"serviceLock"); granted = supplyOwner != null; pickupCenter = null;
        }
        supplyHandoff = false; resetWindow(startRow()); lastHost = System.nanoTime(); persist();
    }

    protected void hostReleaseWatchdog() {
        if (isHost() && assigned() && releasing && releaseExpired(System.nanoTime(), releaseStarted)) {
            warning("Supply cleanup timed out; ending the job and preserving unresolved supply locations for inspection."); endJob();
        }
    }
    protected void hostConnectionMaintenance() {
        if (!isHost() || !assigned()) return;
        if (ticks % 20 == 0) restoreWorkers();
        if (!stopped && begun) coordinateAvailability();
        if (!stopped && !independentSupplies()) for (UUID id : activeMembers()) {
            var c = participants.get(id);
            if (c != null && !c.connected()) { broadcast(jobMessage("lost")); break; }
        }
        if (independentSupplies() && !stopped && begun && !phase.equals("paused") && !releasing && ticks % 10 == 0) {
            for (UUID id : participants.keySet()) {
                JsonObject report = currentReport(id);
                if (report != null && str(assignment, "scope").equals(str(report, "scope"))
                    && TaskWire.flag(report, "connectionStopped") && TaskWire.flag(report, "reconnectReady")
                    && !TaskWire.flag(report, "pausedBeforeDisconnect") && workflowReady(id)) sendMember(id, jobMessage("resume"));
            }
        }
        if (stopped && !pausedBeforeDisconnect && ticks % 10 == 0 && reconnectReady()
            && participants.keySet().stream().allMatch(id -> {
                JsonObject r = currentReport(id);
                return r != null && TaskWire.flag(r,"reconnectReady") && !TaskWire.flag(r,"pausedBeforeDisconnect");
            })) {
            if (tryResume()) info("Crew connection restored; resuming the same execution with fresh verification.");
        }
        if (ticks % 10 == 0) retryWithdrawals();
        if (!stopped && !phase.equals("paused") && ticks % 20 == 0)
            for (UUID id : participants.keySet()) if (currentReport(id) != null) sendMember(id, jobMessage("nudge"));
    }

    static UUID recoveryTeleportTarget(UUID requester, List<UUID> active, Map<UUID, JsonObject> current) {
        return active.stream().filter(id -> !id.equals(requester)).filter(id -> {
            JsonObject report = current.get(id);
            if (report == null || !str(report, "phase").equals("building") || !str(report, "tpaRecovery").isEmpty()
                || !str(report, "name").matches("[A-Za-z0-9_]{1,16}")) return false;
            return !report.has("diagnostics") || !report.getAsJsonObject("diagnostics").has("idleTicks")
                || num(report.getAsJsonObject("diagnostics"), "idleTicks") < 400;
        }).min(Comparator.comparingInt(id -> {
            JsonObject diagnostics = current.get(id).has("diagnostics") ? current.get(id).getAsJsonObject("diagnostics") : null;
            return diagnostics != null && diagnostics.has("idleTicks") ? num(diagnostics, "idleTicks") : 0;
        }).thenComparingInt(id -> current.get(id).has("currentRow") ? num(current.get(id), "currentRow") : Integer.MAX_VALUE)).orElse(null);
    }

    private void coordinateRecoveryTeleports() {
        if (!isHost() || !assigned() || stopped || !begun || regrouping || releasing || phase.equals("paused")) { recoveryTeleports.clear(); return; }
        Map<UUID, JsonObject> current = new LinkedHashMap<>();
        for (UUID id : activeMembers()) { JsonObject report = currentReport(id); if (report != null && str(assignment, "scope").equals(str(report, "scope"))) current.put(id, report); }
        recoveryTeleports.entrySet().removeIf(e -> !current.containsKey(e.getKey()) || !Set.of("requested", "waiting").contains(str(current.get(e.getKey()), "tpaRecovery")));
        for (UUID requester : activeMembers()) {
            JsonObject report = current.get(requester); if (report == null) continue;
            String state = str(report, "tpaRecovery"); RecoveryTeleport pending = recoveryTeleports.get(requester);
            if (state.equals("requested")) {
                if (pending != null && pending.accepted()) recoveryTeleports.remove(requester);
                if (!recoveryTeleports.containsKey(requester)) {
                    UUID target = recoveryTeleportTarget(requester, activeMembers(), current); if (target == null) continue;
                    String token = UUID.randomUUID().toString(); JsonObject command = jobMessage("tpa-recovery");
                    command.addProperty("token", token); command.addProperty("target", target.toString()); command.addProperty("name", str(current.get(target), "name"));
                    recoveryTeleports.put(requester, new RecoveryTeleport(target, token, ticks, false)); sendMember(requester, command);
                    info("%s is using crew TPA recovery through %s.", memberName(requester), memberName(target));
                }
                continue;
            }
            if (!state.equals("waiting") || pending == null || pending.accepted() || ticks - pending.issued() < 10) continue;
            JsonObject target = current.get(pending.target());
            if (target == null || !str(target, "phase").equals("building") || !str(target, "tpaRecovery").isEmpty()) {
                sendMember(requester, jobMessage("tpa-recovery-cancel")); recoveryTeleports.remove(requester); continue;
            }
            JsonObject accept = jobMessage("tpa-recovery-accept");
            accept.addProperty("token", pending.token()); accept.addProperty("requester", str(report, "name")); accept.addProperty("requesterId", requester.toString());
            accept.addProperty("target", pending.target().toString()); accept.addProperty("requesterScope", str(report, "scope")); accept.addProperty("targetScope", str(target, "scope"));
            accept.addProperty("expires", System.currentTimeMillis() + 10_000);
            sendMember(pending.target(), accept); recoveryTeleports.put(requester, new RecoveryTeleport(pending.target(), pending.token(), pending.issued(), true));
        }
    }

    protected void hostCoordinate(boolean changed) {
        try {
            if (isHost() && assigned() && !stopped && regrouping && !releasing && !phase.equals("paused") && handoffExpired(ticks, regroupStarted)) {
                broadcast(jobMessage("regroupCancel")); joiningWorker = detachingWorker = null; rejoiningSupply = false; borrowingWorkers.clear();
                regroupRetryAfter = ticks + 400;
                warning("Lane handoff timed out; keeping existing duties and supply ownership. Work continues before another handoff attempt.");
            }
            coordinateRecoveryTeleports();
            coordinate(changed);
            if (isHost() && assigned() && !stopped && !regrouping && !releasing && !phase.equals("paused") && begun) resourceExchange.coordinate();
        } finally { recordTelemetry(); }
    }

    protected boolean endingsLoaded;
    protected String recoveryError="";
    protected abstract boolean nativeReady(UUID worker);
    protected abstract boolean localReconnectReady();
    public abstract boolean live();
    public abstract JsonObject recoveryRecord();
    protected abstract Path journal();
    protected abstract Path endings();
    protected abstract void checkpointJobs();
    protected abstract void clearLocal();
    public abstract void disconnected();
}
