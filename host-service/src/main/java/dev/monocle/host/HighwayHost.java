package dev.monocle.host;

import com.google.gson.*;
import dev.monocle.coordinator.*;
import dev.monocle.client.systems.modules.misc.swarm.SwarmConnection;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Files and remote worker observations around the same controller used in the game client. */
final class HighwayHost extends HighwayCoordinator<HighwayHost.Position> {
    record Position(int x, int y, int z) {}
    private final Path file;
    private final Predicate<UUID> ready;
    private final Consumer<JsonObject> checkpoint;
    private final UUID identity;
    private JsonObject saved;
    private String startingScope = "";
    private final Deque<JsonObject> events = new ArrayDeque<>();
    private final Deque<JsonObject> stashRequests=new ArrayDeque<>();
    HighwayHost(Path directory, String crew, Predicate<UUID> ready, Consumer<JsonObject> checkpoint) {
        file = directory.resolve("highway-" + UUID.nameUUIDFromBytes(crew.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json");
        identity = UUID.nameUUIDFromBytes(file.toAbsolutePath().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.ready = ready; this.checkpoint = checkpoint;
        saved = TaskFiles.read(file); if (saved.isEmpty()) saved = null;
        if (saved != null) {
            UUID.fromString(str(saved,"job")); UUID.fromString(str(saved,"catalogId"));
            Set<UUID> roster=new LinkedHashSet<>();saved.getAsJsonArray("members").forEach(v->roster.add(UUID.fromString(v.getAsString())));
            validateActiveMembers(saved,roster);
            if(!str(saved,"hostMember").isEmpty())throw new IllegalArgumentException("Standalone recovery cannot claim participating-host world authority");
        }
        loadEndings();
    }
    void start(JsonObject input, Set<UUID> workers) {
        if (assigned() || saved != null) throw new IllegalStateException("Finish or cancel this crew's previous highway first");
        JsonObject definition = HighwayJobs.checked(input);
        if (num(definition,"progress") >= num(definition,"length")) throw new IllegalArgumentException("Highway already complete");
        if (workers.isEmpty() || workers.size() > MAX_CREW_MEMBERS || workers.size() > num(definition.getAsJsonObject("layout"),"width")) throw new IllegalArgumentException("Crew must contain 1–3 workers and fit the highway width");
        Map<UUID,SwarmConnection> selected = new LinkedHashMap<>();
        for (UUID id : workers) {
            SwarmConnection c = connectionForWorker(id); JsonObject report = c == null ? null : peers.get(c);
            if (report == null || report.has("recoveryReady") && !TaskWire.flag(report,"recoveryReady") || !nativeReady(id) || !str(report,"scope").equals(str(definition,"scope"))
                || System.nanoTime()-reportTimes.getOrDefault(id,0L)>3_000_000_000L || !str(report,"job").isEmpty())
                throw new IllegalStateException("Every targeted worker must reach the Highway step on this job's server/dimension");
            selected.put(id,c);
        }
        startingScope = str(definition,"scope");
        startPrepared(definition, selected, false, point(num(definition,"x"),num(definition,"y"),num(definition,"z")));
    }
    boolean recoveryReady(UUID worker) {
        SwarmConnection c=connectionForWorker(worker); JsonObject r=c==null?null:peers.get(c);
        return r!=null && (!r.has("recoveryReady") || TaskWire.flag(r,"recoveryReady"));
    }
    void observe(SwarmConnection c, JsonObject m) {
        UUID id = UUID.fromString(str(m,"id"));
        PlayerObservation.fromHello(m,System.nanoTime()); hostMining(m);
        if (m.has("currentRow")) RowVerification.Progress.fromReport(m);
        if (assigned() && rebindParticipant(participants,id,c,job,generation,str(assignment,"scope"),m)) acknowledgments.remove(id);
        if (participants.get(id)==c && matchesGeneration(job,generation,m) && workReportChanged(reports.get(id),m)) workChanged();
        peers.entrySet().removeIf(e -> e.getKey()!=c && !e.getKey().connected() && str(e.getValue(),"id").equals(id.toString()));
        peers.put(c,m); reports.put(id,m); reportTimes.put(id,System.nanoTime());
        for (String ending : pendingEnds.keySet()) sendPendingEnd(c,id,ending);
        if (participants.get(id)==c && matchesGeneration(job,generation,m) && !lock.isEmpty() && lock.equals(str(m,"ack"))) acknowledgments.add(id);
    }
    boolean accepts(String execution) { return execution.isEmpty() || assigned() && job.equals(execution) || saved!=null && str(saved,"job").equals(execution) || pendingEnds.containsKey(execution); }
    void receive(SwarmConnection c, JsonObject m) {
        JsonObject peer=peers.get(c); if(peer==null) throw new IllegalArgumentException("Announce identity first");
        UUID id=UUID.fromString(str(peer,"id"));
        switch(str(m,"type")) {
            case "ended" -> { if(acknowledgeEnd(pendingEnds,str(m,"job"),id)) saveEndings(); }
            case "withdrawn" -> acknowledgeWithdrawal(id,m);
            case "request", "release", "clearance", "return" -> { if(participants.get(id)==c) receive(id,m); }
            default -> throw new IllegalArgumentException("Unsupported native highway report");
        }
    }
    void tick() {
        ticks++; hostReleaseWatchdog(); hostConnectionMaintenance();
        boolean changed=workDirty; workDirty=false; hostCoordinate(changed);
        if(assigned() && ticks%20==0) { persist(); checkpointJobs(); }
        if(ticks%20==0) for(var p:peers.entrySet()) if(p.getKey().connected()) for(String ending:pendingEnds.keySet()) sendPendingEnd(p.getKey(),UUID.fromString(str(p.getValue(),"id")),ending);
    }
    JsonObject pollStashRequest(){return stashRequests.pollFirst();}
    void clearStashRequests(Collection<String> workers){stashRequests.removeIf(r->workers.contains(str(r,"worker")));}
    JsonObject workerReport(UUID worker){return currentReport(worker);}
    void failStashRequest(JsonObject request){UUID worker=UUID.fromString(str(request,"worker"));JsonObject failure=jobMessage("resource-exhausted");failure.addProperty("resource",num(request,"resource"));sendMember(worker,failure);}
    @Override protected boolean onResourceExhausted(UUID worker,int resource){if(stashRequests.stream().anyMatch(r->str(r,"worker").equals(worker.toString())))return true;JsonObject r=new JsonObject();r.addProperty("worker",worker.toString());r.addProperty("resource",resource);r.addProperty("scope",scope());stashRequests.add(r);info("Worker requested stash resupply for resource %d; other builders continue.",resource);return true;}
    int pendingEndCount() { return pendingEnds.values().stream().mapToInt(Set::size).sum(); }
    boolean paused() { return phase.equals("paused"); }
    JsonObject snapshot() {
        JsonObject r=assigned()?assignment.deepCopy():saved==null?null:saved.deepCopy();
        if(r!=null && assigned()) {
            r.addProperty("progress",safeProgress()); r.addProperty("phase",phase);
            r.addProperty("supplyOwner",supplyOwner==null?"":supplyOwner.toString());
            r.add("supplyContainers", supplyContainers.deepCopy());
            if(supplyCenter!=null)r.addProperty("supplyPosition",x(supplyCenter)+", "+y(supplyCenter)+", "+z(supplyCenter));
            if(pickupCenter!=null)r.addProperty("pickupPosition",x(pickupCenter)+", "+y(pickupCenter)+", "+z(pickupCenter));
        }
        return r;
    }
    JsonObject status() {
        JsonObject r=new JsonObject(); r.addProperty("phase",assigned()?phase:saved!=null?"Inspection required":"idle");
        r.addProperty("telemetryPath",telemetryPath().toString()); r.addProperty("telemetryError",telemetryError()); r.addProperty("telemetryDropped",telemetryDropped());
        r.addProperty("pendingEnds",pendingEndCount()); r.addProperty("execution",assigned()?job:saved==null?"":str(saved,"job"));
        r.add("supplyContainers", supplyContainers.deepCopy());
        if(assigned()) {
            r.addProperty("progress",safeProgress()); r.addProperty("length",num(assignment,"length"));
            r.addProperty("generation",generation); r.addProperty("supplyOwner",supplyOwner==null?"":supplyOwner.toString());
            r.add("suppliers",suppliers(assignment).deepCopy()); r.add("activeMembers",JSON.toJsonTree(activeMembers()));
            JsonArray workers=new JsonArray(); int[] crewCounts=new int[3]; boolean chestKnown=true; int countedWorkers=0;
            for(UUID id:participants.keySet()) { JsonObject w=new JsonObject(); w.addProperty("id",id.toString()); JsonObject report=currentReport(id);
                if(detachedMembers(assignment).contains(id)) w.addProperty("returnGate",serviceReturnGate(id));
                w.addProperty("fresh",report!=null); if(report!=null) { for(String key:List.of("name","phase","status","currentRow","verifiedBase","verifiedMask","currentResolved","serviceReturning","serviceReady","regroupReady","ack","x","y","z","exchange","inventory","diagnostics")) if(report.has(key)) w.add(key,report.get(key).deepCopy());
                    if(report.has("roadPrediction"))try {w.add("roadPrediction",RoadForecast.checked(report.getAsJsonObject("roadPrediction")));}catch(RuntimeException ignored) {}
                    if(report.has("inventory")) try { JsonObject counts=ResourceLedger.resourceCounts(report.getAsJsonObject("inventory")); w.add("resourceCounts",counts);JsonObject total=counts.getAsJsonObject("total");
                        crewCounts[0]+=total.get("obsidian").getAsInt();crewCounts[1]+=total.get("pickaxes").getAsInt();crewCounts[2]+=total.get("food").getAsInt();chestKnown&=counts.get("enderChestKnown").getAsBoolean();countedWorkers++;
                    } catch(RuntimeException ignored) {} }
                workers.add(w); }
            r.add("workers",workers);
            JsonObject prediction=RoadForecast.crew(workers);if(prediction!=null)r.add("roadPrediction",prediction);
            JsonObject resources=new JsonObject(),total=new JsonObject();total.addProperty("obsidian",crewCounts[0]);total.addProperty("pickaxes",crewCounts[1]);total.addProperty("food",crewCounts[2]);resources.add("total",total);resources.addProperty("workers",countedWorkers);resources.addProperty("enderChestKnown",countedWorkers>0&&chestKnown);r.add("resourceCounts",resources);
            if(resourceExchange.hostOffer!=null) r.add("resourceExchange",resourceExchange.hostOffer.deepCopy());
        }
        r.add("events",JSON.toJsonTree(events)); return r;
    }
    @Override protected void info(String format,Object... args) {
        String detail=String.format(Locale.ROOT,format,args); if(detail.length()>2048)detail=detail.substring(0,2048);
        JsonObject event=new JsonObject();event.addProperty("time",System.currentTimeMillis());event.addProperty("detail",detail);
        if(events.size()==100)events.removeFirst();events.addLast(event);System.out.println("[Highway] "+detail);
    }
    @Override protected void warning(String format,Object... args) { info(format,args); }
    @Override protected boolean isHost() { return true; }
    @Override protected boolean worldAvailable() { return false; }
    @Override protected UUID me() { return identity; }
    @Override protected UUID hostIdentity() { return identity; }
    @Override protected String scope() { return assigned()?str(assignment,"scope"):startingScope; }
    @Override protected boolean nativeReady(UUID id) { return ready.test(id); }
    @Override protected boolean localReconnectReady() { return true; }
    @Override public boolean live() { return peers.keySet().stream().anyMatch(SwarmConnection::connected); }
    @Override public JsonObject recoveryRecord() { return saved==null?null:saved.deepCopy(); }
    @Override protected Path journal() { return file; }
    @Override protected Path endings() { return file.resolveSibling(file.getFileName().toString().replace(".json","-endings.json")); }
    @Override protected void checkpointJobs() { JsonObject r=snapshot(); if(r!=null) checkpoint.accept(r); }
    @Override protected void persist() {
        if(!assigned()) return;
        JsonObject r=snapshot();
        TaskFiles.write(file,r);saved=r;
    }
    @Override protected void clearLocal() {
        JsonObject r=snapshot();
        if(r!=null && (!str(r,"supplyOwner").isEmpty() || r.has("resourceExchange") || r.has("supplyContainers") && !r.getAsJsonObject("supplyContainers").isEmpty())) TaskFiles.write(file.resolveSibling("ended-"+UUID.fromString(str(r,"job"))+"-supplies.json"),r);
        try { Files.deleteIfExists(file); } catch(java.io.IOException e) { throw new IllegalStateException("Cannot clear highway journal",e); }
        assignment=saved=null;job="";phase="idle";stopped=begun=granted=backstepped=regrouping=regroupReady=regroupWasPaused=releasing=serviceReturning=serviceFinished=rejoiningSupply=false;
        joiningWorker=detachingWorker=rejoiningWorker=supplyOwner=null;serviceFront=supplyCenter=pickupCenter=null;generation=0;lock=acknowledged="";
        releaseStarted=0;retryingSupply=supplyHandoff=false;supplyRetryAfter=0;availabilityChange=null;
        cachedRoster=null;cachedMembers=List.of();offRangeArmed.clear();participants.clear();reports.clear();requested.clear();acknowledgments.clear();borrowingWorkers.clear();
        resourceExchange.hostOffer=null;supplyContainers=new JsonObject();resetWindow(0);
    }
    @Override public void disconnected() { if(!assigned())return;if(!stopped)pausedBeforeDisconnect=phase.equals("paused");stopped=true;lastPermit=0;phase="connection lost / inspect job"; }
    @Override protected void apply(JsonObject m) { applyController(m); }
    @Override protected void installAssignment(JsonObject m) { installRemoteAssignment(m); }
    @Override protected String memberName(UUID id) { JsonObject r=reports.get(id);return r==null?id.toString():str(r,"name"); }
    @Override protected Position point(int x,int y,int z) { return new Position(x,y,z); }
    @Override protected int x(Position p) { return p.x; }
    @Override protected int y(Position p) { return p.y; }
    @Override protected int z(Position p) { return p.z; }
    @Override protected long packed(Position p) { return ((long)p.x&0x3ffffffL)<<38 | ((long)p.z&0x3ffffffL)<<12 | (long)p.y&0xfffL; }
    @Override protected Position unpacked(long p) { return point((int)(p>>38),(int)(p<<52>>52),(int)(p<<26>>38)); }
    @Override protected boolean hostRowResolved(Position p) { throw new IllegalStateException("A standalone host cannot assert world verification"); }
    @Override protected Position rowCenter(int row) { var l=assignment.getAsJsonObject("layout");return point(num(assignment,"x")+num(l,"dx")*row,num(assignment,"y"),num(assignment,"z")+num(l,"dz")*row); }
    @Override public Position returnRendezvous() { return rowCenter(safeProgress()); }
}
