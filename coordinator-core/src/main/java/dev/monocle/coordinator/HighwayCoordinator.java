package dev.monocle.coordinator;

import com.google.gson.*;
import java.util.*;
import java.nio.file.*;
import dev.monocle.client.systems.bots.BotWorkflows;
import dev.monocle.client.systems.modules.misc.swarm.SwarmConnection;

/** Shared host state machine. P and the hooks isolate game observations/actions from coordination. */
public abstract class HighwayCoordinator<P> {
    protected JsonArray cachedRoster;
    protected List<UUID> cachedMembers=List.of();
    protected static final Gson JSON = new Gson();
    protected static final int WORK_WINDOW=RowVerification.WINDOW, SUPPLY_SPACING=3;
    public enum WorkSharing {
        Lanes, BreakOrder;
        @Override public String toString() { return this == Lanes ? "Lanes" : "Break Order"; }
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

    protected final Map<String, Set<UUID>> pendingEnds = new LinkedHashMap<>();

    public static WorkSharing workSharing(JsonObject layout) {
        return layout.has("workSharing") ? WorkSharing.valueOf(layout.get("workSharing").getAsString()) : WorkSharing.Lanes;
    }

    public boolean assigned() { return assignment != null; }

    public void workChanged() { if (assigned()) workDirty = true; }

    public boolean isParticipant(UUID worker) { return participants.containsKey(worker) || worker.equals(joiningWorker); }

    public boolean localAssigned() { return assigned() && localParticipant; }

    public boolean detachedSupply() { return localAssigned() && detachedMembers(assignment).contains(me()); }

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

    protected boolean sharedSupplyHold() { return supplyOwner != null && !detachedMembers(assignment).contains(supplyOwner); }

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

    public static int leadLimit(int length, int slowest, WorkSharing sharing) {
        return sharing == WorkSharing.BreakOrder ? Math.min(length, slowest + 1) : leadLimit(length, slowest);
    }

    public static boolean verifiedRow(int base, int mask, int row) {
        return RowVerification.verifiedRow(base, mask, row);
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
        return job.equals(str(message, "job")) && (message.has("generation") ? num(message, "generation") : 0) == generation;
    }

    public static boolean reconfigurationReady(JsonObject current, int generation, JsonObject next, boolean settled, boolean supplyReserved) {
        return settled && !supplyReserved && str(current, "job").equals(str(next, "job"))
            && str(current, "catalogId").equals(str(next, "catalogId")) && num(next, "generation") == generation + 1;
    }

    protected static String str(JsonObject m, String key) { return m.has(key) ? m.get(key).getAsString() : ""; }

    protected static int num(JsonObject m, String key) { return m.get(key).getAsInt(); }

    protected void broadcast(JsonObject m) {
        for (var c : participants.values()) if (c != null) c.send(JSON.toJson(m));
        apply(m);
    }

    protected void coordinate(boolean workChanged) {
        if (isHost() && assigned() && !stopped && !phase.equals("paused")) {
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
            if (begun && !sharedSupplyHold() && workUpdateDue(ticks, true, workChanged)) coordinateWindow();
            if (detachedMember() != null) {
                coordinateServiceReturn();
                if (regrouping) return;
            }
            // Handoff is independent of the one physical container lock: a second hungry
            // builder can leave while the first runner is opening/recovering its shulker.
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

    public static boolean needsBeginRetry(JsonObject report) {
        return str(report, "phase").equals("ready") && (!report.has("begun") || !report.get("begun").getAsBoolean());
    }

    public static boolean reservationRetryDue(boolean granted, int elapsed) { return !granted && elapsed >= 200; }

    public static boolean rejoinArmed(boolean armed, boolean nearby) { return armed || !nearby; }

    protected boolean coordinateAvailability() {
        P front = returnRendezvous();
        boolean resuming = false;
        for (UUID id : participants.keySet()) {
            JsonObject r = currentReport(id);
            if (r == null || !r.has("x") || !str(assignment, "scope").equals(str(r, "scope"))) continue;
            boolean off = r.has("moduleOff") && r.get("moduleOff").getAsBoolean();
            if (!off) { offRangeArmed.remove(id); continue; }
            boolean nearby = hostNearby(front, point(num(r, "x"), num(r, "y"), num(r, "z")));
            boolean armed = rejoinArmed(offRangeArmed.getOrDefault(id, false), nearby);
            offRangeArmed.put(id, armed);
            if (nearby && armed && !awayMembers(assignment).has(id.toString())) { sendMember(id, jobMessage("resume-builder")); resuming = true; }
        }
        if (resuming) return false; // Wait for fresh telemetry instead of withdrawing a just-resumed worker.
        boolean liveRunner = detachedMembers(assignment).stream().anyMatch(id -> {
            JsonObject runner = currentReport(id);
            return runner == null || !runner.has("moduleOff") || !runner.get("moduleOff").getAsBoolean();
        });
        if (regrouping || supplyOwner != null || liveRunner
            || ticks < regroupRetryAfter || safeProgress() >= num(assignment, "length")) return false;
        JsonObject old = awayMembers(assignment), next = old.deepCopy();
        for (UUID id : participants.keySet()) {
            JsonObject report = currentReport(id);
            if (report == null || !report.has("x") || !str(assignment, "scope").equals(str(report, "scope"))) continue;
            boolean nearby = hostNearby(front, point(num(report, "x"), num(report, "y"), num(report, "z")));
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
        if (remaining.isEmpty()) return false; // Real work always needs an on-site worker.
        try { applyWorkflowDuties(assignment.deepCopy(), remaining); }
        catch (IllegalArgumentException e) { return false; }
        var request = jobMessage("regroup"); request.add("awayMembers", next);
        broadcast(request);
        info("Updating off-duty workers; %d builders will cover the highway. Return detection arms after leaving the crew's range.", remaining.size());
        return true;
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

    protected boolean supplyBarrierReady() {
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
        return ticks >= regroupRetryAfter && activeMembers().contains(member) && safeProgress() < num(assignment, "length");
    }

    protected void departSupply(UUID supplier) {
        // Assign the same rear service site as before; only the departing builder drains
        // its outstanding actions, in crewTravelSupply. Everyone else's native job stays live.
        JsonObject planned = supplyAssignment(assignment, supplier, true, safeProgress());
        JsonObject departure = serviceChange("service-detach", supplier, Math.incrementExact(serviceRevision(assignment, supplier)));
        departure.add("site", suppliers(planned).get(supplier.toString()).deepCopy());
        broadcast(departure);
        info("%s is leaving for supplies; remaining builders keep working and share the road.", memberName(supplier));
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
        for (UUID supplier : detachedMembers(assignment)) {
            coordinateServiceReturn(supplier);
            if (regrouping) return;
        }
    }

    protected void coordinateServiceReturn(UUID supplier) {
        if (!begun) return; // Finish an outbound/initial positioning handoff before merging into its running lanes.
        if (resourceTransfer(supplier)) return;
        JsonObject report = currentReport(supplier);
        if (report != null && report.has("exchange") && num(report.getAsJsonObject("exchange"), "need") >= 0) return;
        if (report == null || supplier.equals(supplyOwner) || !report.has("serviceReturning") || !report.get("serviceReturning").getAsBoolean()) return;
        if (serviceRevision(report, supplier) != serviceRevision(assignment, supplier)) return;
        P front = rowCenter(Math.max(startRow(), checkpointRow - 2));
        boolean rendered = validRenderedReturn(assignment, supplier, report);
        // Outside entity-tracking range (or with every worker resupplying), keep a
        // coarse approach point fresh. A visible crew is followed locally every tick.
        if (!rendered && ticks % 20 == 0) {
            JsonObject update = jobMessage("service-front"); update.addProperty("x", x(front)); update.addProperty("y", y(front)); update.addProperty("z", z(front));
            sendMember(supplier, update);
        }
        // Readiness refers to the worker's live entity destination, not an old host
        // checkpoint. The worker has landed and settled recovery before setting it.
        if (!hostReturnReady(assignment, supplier, report, front)) return;
        if (checkpointRow == num(assignment, "length")) {
            if (activeMembers().stream().allMatch(id -> currentReport(id) != null && "complete".equals(str(currentReport(id), "phase"))))
                sendMember(supplier, jobMessage("service-complete"));
            return;
        }
        if (ticks < regroupRetryAfter) return;
        JsonObject join = serviceChange("service-join", supplier, Math.incrementExact(serviceRevision(assignment, supplier)));
        broadcast(join);
        info("%s is merging into the moving crew; existing builders keep working.", memberName(supplier));
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
                JsonObject update = serviceChange(detached ? "service-detach" : "service-join", supplier, entry.getValue().getAsInt());
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
            long x = (long) num(site, "x") - num(current, "x"), z = (long) num(site, "z") - num(current, "z");
            long row = x * num(layout, "dx") + z * num(layout, "dz");
            if (num(site, "y") != num(current, "y") || x * num(layout, "dz") - z * num(layout, "dx") != 0
                || row < -90 || row > num(current, "length") - (compactSite(site) ? SUPPLY_SPACING : 10))
                throw new IllegalArgumentException("Invalid rear supply site");
            runners.add(supplier.toString(), site.deepCopy());
        } else if (str(update, "type").equals("service-join")) runners.remove(supplier.toString());
        else throw new IllegalArgumentException("Invalid supply update type");
        next.add("suppliers", runners); next.remove("detachedMember");
        next.add("activeMembers", JSON.toJsonTree(roster.stream().filter(id -> !runners.has(id.toString()) && !awayMembers(next).has(id.toString())).map(UUID::toString).toList()));
        JsonObject revisions = serviceRevisions(current).deepCopy(); revisions.addProperty(supplier.toString(), revision); next.add("serviceRevisions", revisions);
        validateActiveMembers(next, Set.copyOf(roster));
        return next;
    }

    protected void sendMember(UUID member, JsonObject message) {
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
        if (expected.isEmpty() && detached.isEmpty() || !expected.equals(new LinkedHashSet<>(activeMembers(record)))) throw new IllegalArgumentException("Missing or overlapping active worker duties");
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
        int length = num(assignment, "length");
        if (activeMembers().isEmpty()) return true; // Everyone is supplying; retain the checkpoint until the first return.
        Map<UUID, JsonObject> current = new LinkedHashMap<>();
        for (UUID id : activeMembers()) {
            JsonObject report = currentReport(id);
            // Missing telemetry expires outstanding permits; it is not treated as a worker at row zero.
            if (report == null || !report.has("currentRow") || str(assignment, "scope").isEmpty() || !str(assignment, "scope").equals(str(report, "scope"))) return false;
            int row = num(report, "currentRow");
            if (row < startRow() || row > length) throw new IllegalStateException("Worker reported progress outside the assigned job");
            current.put(id, report);
        }
        int minimum = slowestRow(length, current.values().stream().mapToInt(r -> num(r, "currentRow")).toArray());
        boolean shared = workSharing(assignment.getAsJsonObject("layout")) == WorkSharing.BreakOrder;
        int limit = leadLimit(length, minimum, workSharing(assignment.getAsJsonObject("layout")));
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
        if (hostAuthority && (!localParticipant || !worldAvailable() || !scope().equals(str(assignment, "scope")))) return false;
        // All world reads stay on the client thread. Shared results bound host verification to five
        // forward rows plus the slowest member's current row, irrespective of the crew size.
        Map<Integer, Boolean> hostResolved = new HashMap<>();
        if (hostAuthority) {
            if (minimum != startRow()) hostResolved.put(minimum, hostRowResolved(rowCenter(minimum)));
            for (int row = minimum + 1; row <= limit; row++) hostResolved.put(row, hostRowResolved(rowCenter(row)));
        }
        Map<Integer, Boolean> worldRows = Map.copyOf(hostResolved);
        Map<UUID, RowVerification.Progress> progress = new LinkedHashMap<>();
        current.forEach((id, report) -> progress.put(id, RowVerification.Progress.fromReport(report)));
        checkpointRow = RowVerification.checkpoint(hostAuthority, startRow(), checkpointRow, minimum, worldRows, progress.values());
        for (var member : current.entrySet()) {
            JsonObject report = member.getValue();
            // A reconnect may still be applying a lane update. Keep its existing permit
            // until the roster receipt arrives; never send unknown Break Order owners.
            if (report.has("suppliers") && !report.getAsJsonObject("suppliers").keySet().equals(suppliers(assignment).keySet())) continue;
            if (report.has("serviceRevisions") && !serviceRevisions(report).equals(serviceRevisions(assignment))) continue;
            int base = num(report, "currentRow") + 1;
            int mask = RowVerification.mask(hostAuthority, base, limit, worldRows, progress.get(member.getKey()));
            JsonObject permit = jobMessage("window");
            permit.addProperty("base", base); permit.addProperty("mask", mask); permit.addProperty("limit", limit);
            permit.addProperty("checkpoint", checkpointRow);
            if (shared) permit.add("mining", sharedMining);
            SwarmConnection connection = participants.get(member.getKey());
            if (connection == null) apply(permit); else connection.send(JSON.toJson(permit));
        }
        return true;
    }
    public static void applyWorkflowDuties(JsonObject value, Set<UUID> roster) {
        JsonObject main = BotWorkflows.checkedPlan(value.getAsJsonObject("workflow"));
        String required = main.get("duty").getAsString(); BotWorkflows.operation(main);
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
        return validRenderedReturn(a,supplier,r) || activeMembers(a).isEmpty() && r.has("x") && r.has("y") && r.has("z") && num(r,"y")==y(fallback) && distance(fallback,point(num(r,"x"),num(r,"y"),num(r,"z")))<=4;
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
        public boolean inTransfer(UUID member) {
            return hostOffer != null && (str(hostOffer, "donor").equals(member.toString()) || str(hostOffer, "recipient").equals(member.toString()));
        }
        public JsonObject worker(UUID id) {
            JsonObject r = currentReport(id);
            try {
                if (r == null || !r.has("exchange") || !r.has("inventory")) return null;
                JsonObject inventory = r.getAsJsonObject("inventory"), exchange = r.getAsJsonObject("exchange");
                if (ResourceLedger.integer(inventory, "version") != 1 || !inventory.getAsJsonPrimitive("busy").isBoolean()
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
                    if (r == null || awayMembers(assignment).has(recipient.toString()) || recipient.equals(supplyOwner)) continue;
                    int resource = ResourceLedger.integer(r.getAsJsonObject("exchange"), "need");
                    if (resource < 0 || resource >= ResourceLedger.RESOURCES || !detachedMembers(assignment).contains(recipient)) continue;
                    UUID donor = null; int most = 0;
                    for (UUID candidate : participants.keySet()) {
                        JsonObject d = worker(candidate);
                        if (candidate.equals(recipient) || d == null || candidate.equals(supplyOwner) || awayMembers(assignment).has(candidate.toString())
                            || !canDetach(candidate) && !detachedMembers(assignment).contains(candidate)
                            || !Set.of("building", "resupplying", "returning from supplies").contains(str(d, "phase"))
                            || !Set.of("idle", "complete", "cancelled").contains(str(d.getAsJsonObject("exchange"), "stage"))
                            || num(d.getAsJsonObject("exchange"), "need") == resource
                            || d.getAsJsonObject("inventory").get("busy").getAsBoolean()
                            || !d.getAsJsonObject("inventory").get("idle").getAsBoolean()) continue;
                        int surplus = ResourceLedger.surplus(d.getAsJsonObject("inventory"), resource);
                        if (surplus > most) { most = surplus; donor = candidate; }
                    }
                    if (donor == null) continue;
                    JsonObject ledger = r.getAsJsonObject("inventory");
                    int amount = Math.min(most, Math.max(1, ResourceLedger.value(ledger, "target", resource) - ResourceLedger.value(ledger, "loose", resource)));
                    hostOffer = new JsonObject(); hostOffer.addProperty("id", UUID.randomUUID().toString());
                    hostOffer.addProperty("resource", resource); hostOffer.addProperty("donor", donor.toString()); hostOffer.addProperty("recipient", recipient.toString());
                    hostOffer.addProperty("remaining", amount); hostOffer.addProperty("sequence", 0); hostOffer.addProperty("phase", "gather");
                    JsonObject meeting = suppliers(assignment).getAsJsonObject(recipient.toString());
                    // Two actors use the already-verified rear highway, never the active excavation face.
                    hostOffer.addProperty("x", num(meeting,"x")); hostOffer.addProperty("y", num(meeting,"y")); hostOffer.addProperty("z", num(meeting,"z"));
                    hostSince = ticks; saveHost(); dispatch();
                    if (activeMembers().contains(donor)) departSupply(donor);
                    info("Crew supplies: %s will give %s up to %d %s. Other builders continue.", memberName(donor), memberName(recipient), amount, ResourceLedger.name(resource));
                    break;
                }
                return;
            }
            UUID donor = UUID.fromString(str(hostOffer, "donor")), recipient = UUID.fromString(str(hostOffer, "recipient"));
            JsonObject d = exchangeReport(donor), r = exchangeReport(recipient);
            boolean terminal = Set.of("complete", "cancelled", "uncertain").contains(str(hostOffer, "phase"));
            if (!terminal && (d != null && Set.of("failed", "uncertain").contains(str(d, "stage")) || r != null && Set.of("failed", "uncertain").contains(str(r, "stage")))) {
                finish("uncertain".equals(d == null ? "" : str(d, "stage")) || "uncertain".equals(r == null ? "" : str(r, "stage")) ? "uncertain" : "cancelled"); return;
            }
            if (!terminal && ticks - hostSince > 1200) { finish(str(hostOffer, "phase").equals("drop") ? "uncertain" : "cancelled"); return; }
            switch (str(hostOffer, "phase")) {
                case "gather" -> {
                    if (d != null && d.has("proposal") && r != null && str(r, "stage").equals("meeting")) {
                        JsonObject proposal = d.getAsJsonObject("proposal");
                        int count = ResourceLedger.integer(proposal, "count");
                        if (count < 1 || count > Math.min(99, num(hostOffer, "remaining")) || proposal.toString().length() > 32768) { finish("cancelled"); return; }
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
                        int remaining = num(hostOffer, "remaining") - num(hostOffer.getAsJsonObject("proposal"), "count");
                        if (remaining <= 0) { finish("complete"); return; }
                        hostOffer.addProperty("remaining", remaining); hostOffer.addProperty("sequence", num(hostOffer, "sequence") + 1);
                        hostOffer.remove("proposal"); hostOffer.addProperty("phase", "gather"); hostSince = ticks; saveHost();
                    }
                }
                case "complete", "cancelled", "uncertain" -> {
                    boolean done = d != null && str(d, "stage").equals(str(hostOffer, "phase")) && r != null && str(r, "stage").equals(str(hostOffer, "phase"));
                    // Keep replaying the terminal instruction until both parties acknowledge it, including reconnects.
                    if (done) { hostOffer = null; assignment.remove("resourceExchange"); persist(); nextRequest = ticks + 100; return; }
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
            if (!phase.equals("complete")) warning("Crew transfer %s at %s, %s, %s. Any unconfirmed drop is not repeated; inspect the participants in Bots.", phase, str(hostOffer, "x"), str(hostOffer, "y"), str(hostOffer, "z"));
        }
    }
    public SwarmConnection connectionForWorker(UUID id) { return peers.entrySet().stream().filter(e -> e.getKey().connected() && id.toString().equals(str(e.getValue(), "id"))).map(Map.Entry::getKey).findFirst().orElse(null); }

    protected boolean allMembersConnected() {
        return !isHost() || !participants.isEmpty() && participants.values().stream().allMatch(c -> c == null || c.connected());
    }

    public void addWorker(UUID worker) {
        if (!isHost() || !assigned() || stopped || !live() || !allMembersConnected() || phase.equals("complete")) throw new IllegalStateException("Choose a connected, unfinished job first.");
        if (regrouping) throw new IllegalStateException("A worker is already joining; wait for supply recovery and lane positioning.");
        if (ticks < regroupRetryAfter) throw new IllegalStateException("Previous handoff was deferred; allowing the current lanes to progress before retrying.");
        if (detachedMember() != null) throw new IllegalStateException("Wait for the resupplying worker to return before adding another worker.");
        if (participants.size() >= num(assignment.getAsJsonObject("layout"), "width")) throw new IllegalStateException("There is no unused walking lane on this highway.");
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
        if (!isHost()) throw new IllegalStateException("Only the host can End a crew job. Use the host's Bots tab.");
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
            JsonObject report = currentReport(member.getKey());
            if (report == null || !str(assignment, "scope").equals(str(report, "scope")) || !TaskWire.flag(report, "reconnectReady")) return false;
        }
        broadcast(jobMessage("resume"));
        return true;
    }

    protected boolean reconnectReady() {
        return assigned() && live() && scope().equals(str(assignment, "scope"))
            && (!localParticipant || localReconnectReady()) && (!isHost() || allMembersConnected());
    }

    public static boolean handoffExpired(int now, int started) { return now - started >= 600; }

    public static boolean releaseExpired(long now, long started) { return now - started >= 30_000_000_000L; }

    public static void writeRecord(Path path, JsonObject record) throws java.io.IOException { TaskFiles.write(path, record); }
    protected void startPrepared(JsonObject definition, Map<UUID,SwarmConnection> selected, boolean includeHost, P origin) {
        if (hasPendingEnds(selected.keySet()))
            throw new IllegalStateException("A selected worker must acknowledge its previous job ending before joining this one");
        definition = definition.deepCopy();
        normalizeWorkflows(definition, selected.keySet());
        String id = UUID.randomUUID().toString();
        int progress = definition.has("progress") ? num(definition,"progress") : 0;
        int sectionLength = num(definition,"length"); String name = str(definition,"name"); JsonObject layout = definition.getAsJsonObject("layout");
        localParticipant = includeHost;
        participants.clear(); participants.putAll(selected); reports.clear();
        generation = 0;
        int index = 0;
        for (var member : participants.entrySet()) {
            var m = message("prepare");
            m.addProperty("job", id); m.addProperty("name", name); m.addProperty("scope", scope());
            m.addProperty("catalogId", str(definition, "id")); m.addProperty("generation", generation); m.addProperty("startRow", progress);
            m.addProperty("host", hostIdentity().toString()); m.addProperty("hostMember", includeHost ? me().toString() : "");
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
            case "service-detach", "service-join" -> {
                if (stopped || releasing || regrouping || phase.equals("paused")) return;
                UUID supplier = UUID.fromString(str(m, "supplier"));
                if (type.equals("service-join") && supplier.equals(supplyOwner)) return;
                JsonObject next = serviceUpdateAssignment(assignment, m);
                if (next == assignment) return;
                assignment = next; requested.remove(supplier); workChanged(); persist();
            }
            case "begin" -> {
                if (releasing || begun || stopped || ticks < nextStartAttempt) return;
                begun = true; phase = assignment.has("keepPaused") && assignment.get("keepPaused").getAsBoolean() ? "paused" : "building";
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
        if (!stopped) for (var c : participants.values()) if (c != null && !c.connected()) { broadcast(jobMessage("lost")); break; }
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
    protected void hostCoordinate(boolean changed) {
        if (isHost() && assigned() && !stopped && regrouping && !releasing && !phase.equals("paused") && handoffExpired(ticks, regroupStarted)) {
            broadcast(jobMessage("regroupCancel")); joiningWorker = detachingWorker = null; rejoiningSupply = false; borrowingWorkers.clear();
            regroupRetryAfter = ticks + 400;
            warning("Lane handoff timed out; keeping existing duties and supply ownership. Work continues before another handoff attempt.");
        }
        coordinate(changed);
        if (isHost() && assigned() && !stopped && !regrouping && !releasing && !phase.equals("paused") && begun) resourceExchange.coordinate();
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
