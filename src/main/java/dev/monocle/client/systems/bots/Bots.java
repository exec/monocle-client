/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.systems.bots;

import com.google.gson.JsonObject;
import dev.monocle.coordinator.HighwayJobs;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.systems.Systems;
import dev.monocle.client.systems.modules.misc.swarm.*;
import dev.monocle.client.utils.render.NotificationFeed;
import dev.monocle.client.utils.render.Notifications;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.client.utils.Utils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import java.io.File;
import java.util.*;
import static dev.monocle.client.MonocleClient.mc;

import dev.monocle.client.events.game.GameJoinedEvent;
import dev.monocle.client.events.game.GameLeftEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.settings.*;
import meteordevelopment.orbit.EventHandler;

public final class Bots extends dev.monocle.client.systems.System<Bots> {
    public static Bots get() { return Systems.get(Bots.class); }
    public final Settings settings = new Settings();
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    public final Setting<String> crewKey = sgGeneral.add(new StringSetting.Builder().name("crew-key")
        .description("Shared private Workers key, 24+ characters. Paste the same key on trusted workers. TCP LAN traffic is not encrypted; wss:// encrypts all traffic.")
        .defaultValue("").build());
    public final Setting<String> bindAddress = sgGeneral.add(new StringSetting.Builder().name("bind-address")
        .description("Host listener: loopback by default; use this computer's specific LAN IP for other machines.")
        .defaultValue("127.0.0.1").build());
    public final Setting<Boolean> acceptCrew = sgGeneral.add(new BoolSetting.Builder().name("accept-highway-assignments")
        .description("Allow the connected trusted host to temporarily configure and start Highway Builder when it is idle.")
        .defaultValue(true).build());
    public SwarmCrew crew = new SwarmCrew(this);
    public final Setting<Integer> sectionLength = sgGeneral.add(new IntSetting.Builder().name("crew-road-length")
        .description("Total shared road length. Players divide the width and advance together. Cardinal directions only.")
        .defaultValue(128).range(16, HighwayJobs.MAX_LENGTH).sliderRange(16, 512).build());
    public final Setting<Integer> teleportWarmupSeconds=sgGeneral.add(new IntSetting.Builder().name("crew-teleport-warmup").description("Server delay after /tpy before a returning stash or supply worker is expected to arrive.").defaultValue(15).range(0,60).sliderRange(0,30).build());
    public final Setting<Integer> teleportAcceptDelayMs=sgGeneral.add(new IntSetting.Builder().name("crew-teleport-accept-delay").description("Delay between a worker's /tpa and the active builder's /tpy. 500 ms preserves server command order.").defaultValue(500).range(0,5000).sliderRange(0,2000).build());
    public final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("What type of client to run.")
        .defaultValue(Mode.Host)
        .build()
    );
    public final Setting<Boolean> autoTpy=sgGeneral.add(new BoolSetting.Builder().name("auto-tpy")
        .description("Host only: automatically accept a same-crew worker's observed /tpa after 10 ticks.")
        .defaultValue(false).visible(() -> mode.get() == Mode.Host).build());

    public final Setting<String> ipAddress = sgGeneral.add(new StringSetting.Builder()
        .name("ip")
        .description("LAN host address, or wss://HOST[:PORT]/v1/workers. A web URL includes its own port; the TCP port setting is ignored.")
        .defaultValue("localhost")
        .visible(() -> mode.get() == Mode.Worker)
        .build()
    );

    public final Setting<Integer> serverPort = sgGeneral.add(new IntSetting.Builder()
        .name("port")
        .description("The port used for connections.")
        .defaultValue(6969)
        .range(1, 65535)
        .noSlider()
        .build()
    );

    public SwarmHost host;
    public SwarmWorker worker;
    private final dev.monocle.coordinator.BotChat chat = new dev.monocle.coordinator.BotChat();
    public com.google.gson.JsonArray chatFeed() { return chat.json(); }
    public final Setting<Integer> webPort = sgGeneral.add(new IntSetting.Builder().name("web-port")
        .description("Optional host WebSocket ingress on 127.0.0.1 only. 0 disables it. Use an HTTPS reverse proxy for remote workers.")
        .defaultValue(0).range(0, 65535).noSlider().visible(() -> mode.get() == Mode.Host).build());
    private record ConnectionConfig(Mode mode, String ip, int port, String key, String bind, int webPort) {}
    private ConnectionConfig connectionConfig;
    private long retryAt, noticeAt;
    private int attempts;
    private boolean workerWasConnected;
    private String connectionDetail = "", lastNotice = "";
    private boolean active;
    private Mode crewRole = Mode.Host;
    private final Map<String, CrewPreset> presets = new LinkedHashMap<>();
    private final Map<String, String> crewLabels = new LinkedHashMap<>();
    private final Map<String, String> crewKeys = new LinkedHashMap<>();
    private final Map<String, SwarmCrew> coordinators = new LinkedHashMap<>();
    private volatile Map<String, String> credentials = Map.of();
    private String selectedCrew = "Default";
    private String workerCrewName = "";
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final BotJobs catalog = new BotJobs(MonocleClient.FOLDER.toPath().resolve("bot-jobs.json"));
    private final BotWorkflows workflows = new BotWorkflows(MonocleClient.FOLDER.toPath().resolve("bot-workflows.json"));
    public BotWorkflows workflows() { return workflows; }
    private dev.monocle.coordinator.OperationsLibrary operations;
    public dev.monocle.coordinator.OperationsLibrary operations() {
        if (operations == null) operations = new dev.monocle.coordinator.OperationsLibrary(MonocleClient.FOLDER.toPath().resolve("bot-operations.json"));
        return operations;
    }
    private final BotScheduler tasks = new BotScheduler(this);
    public BotScheduler tasks() { return tasks; }
    private int catalogTicks;
    private long catalogSavedAt;
    private boolean catalogDirty;
    private boolean settingsLoaded;
    private boolean hostAutoTpy;
    private boolean workflowTpa;
    private final List<CrewDiscovery> discoveries = new ArrayList<>();
    private String pendingJoinCrew = "", pendingJoinJob = "";
    private long pendingJoinAt;

    public record CrewPreset(String name, int length, Set<UUID> workers) {
        public CrewPreset { workers = Set.copyOf(workers); }
    }
    public record Event(String time, String level, String message) {}
    public record JobView(UUID id, String name, String type, String crewId, String status, int progress, int length,
                          BlockPos origin, String scope, JsonObject layout, String detail, boolean includeHost, String workflowId, String workflowName,
                          boolean publicJoin) {
        public boolean unfinished() { return !Set.of("Complete", "Cancelled").contains(status) && progress < length; }
        public boolean unclaimed() { return crewId.isEmpty(); }
    }
    public record CrewDiscovery(String id, String name, String job, String jobName, String status, int workers, int capacity, boolean publicJoin) {}

    public Bots() { super("bots"); ensureDefaultCrew(); }
    public boolean isActive() { return active; }
    public void enable() {
        if (active) return;
        active = true; ensureDefaultCrew(); refreshCredentials(); connectionConfig = null; tickConnection();
    }
    public void disable() { close(); active = false; connectionDetail = "Offline"; }
    public void setEnabled(boolean enabled) { if (enabled) enable(); else disable(); save(); }
    public String connectionDetail() { return connectionDetail; }

    public void hostAutoTpy(boolean enabled) { hostAutoTpy = mode.get() == Mode.Worker && enabled; }

    public void sendWorkflowTpa(String name) {
        workflowTpa = true;
        try { mc.getConnection().sendCommand("tpa " + name); }
        finally { workflowTpa = false; }
    }

    public void onCommandSent(String command) {
        String target = tpaTarget(command);
        if (workflowTpa || target == null || !Utils.canUpdate()) return;
        if (mode.get() == Mode.Host) {
            if (autoTpy.get() && isHost()) tasks.requestLocalAutoTpy(target);
            return;
        }
        if (!hostAutoTpy || !isWorker()) return;
        JsonObject request = new JsonObject(); request.addProperty("type", "worker-tpa-request");
        request.addProperty("request", UUID.randomUUID().toString()); request.addProperty("target", target); request.addProperty("scope", worldScope());
        worker.send(request.toString());
    }

    static String tpaTarget(String command) {
        if (command == null) return null;
        var match = java.util.regex.Pattern.compile("(?i)^tpa ([a-z0-9_]{1,16})$").matcher(command.trim());
        return match.matches() ? match.group(1) : null;
    }

    public void generateKey() {
        if (isHost() || worker != null && worker.isAlive()) throw new IllegalStateException("Stop connections before changing the key.");
        if (settingsLoaded && hasPersistedCrewWork()) throw new IllegalStateException("Release assigned and recovered jobs before changing connection keys.");
        if (mode.get() == Mode.Host && coordinator(selectedCrew).pendingEndCount() > 0) throw new IllegalStateException("Wait for offline workers to acknowledge the ended job before rotating this crew's key.");
        String key = UUID.randomUUID().toString().replace("-", "");
        if (mode.get() == Mode.Host && !selectedCrew.equals("Default")) crewKeys.put(selectedCrew, key);
        else crewKey.set(key);
        refreshCredentials();
        mc.keyboardHandler.setClipboard(key);
        info("New private key copied. Paste it on your workers."); save();
    }
    public List<CrewPreset> presets() { return List.copyOf(presets.values()); }
    public String crewLabel(String id) { return crewLabels.getOrDefault(id, id); }
    private void requireHostRole() {
        if (mode.get() != Mode.Host) throw new IllegalStateException("Jobs and crews are managed by the host.");
    }
    public String createCrew(String label) {
        requireHostRole(); label = uniqueCrewLabel(label, "");
        String id = "crew-" + UUID.randomUUID();
        savePreset(id, sectionLength.get(), Set.of());
        crewLabels.put(id, label); selectCrew(id); save();
        return id;
    }
    public void renameCrew(String id, String label) {
        requireHostRole();
        if (!presets.containsKey(id)) throw new IllegalArgumentException("Unknown crew");
        crewLabels.put(id, uniqueCrewLabel(label, id)); save();
    }
    private String uniqueCrewLabel(String label, String except) {
        label = BotJobs.name(label);
        for (String id : presets.keySet()) if (!id.equals(except) && crewLabel(id).equalsIgnoreCase(label))
            throw new IllegalArgumentException("A crew already uses that name.");
        return label;
    }
    public void saveCrewWorkers(String id, Set<UUID> workers) {
        requireHostRole();
        CrewPreset preset = presets.get(id);
        if (preset == null) throw new IllegalArgumentException("Unknown crew");
        savePreset(id, preset.length(), workers);
    }
    private void ensureDefaultCrew() {
        if (presets.isEmpty()) presets.put("Default", new CrewPreset("Default", sectionLength.get(), Set.of()));
        if (!presets.containsKey(selectedCrew)) selectedCrew = presets.keySet().iterator().next();
    }
    public String selectedCrew() { return selectedCrew; }
    public void selectCrew(String name) {
        if (!presets.containsKey(name)) throw new IllegalArgumentException("Choose a saved crew first.");
        selectedCrew = name; save();
    }
    public String crewKey(String name) { return name.equals("Default") ? crewKey.get() : crewKeys.getOrDefault(name, ""); }
    public String keyForSelector(String selector) { return credentials.get(selector); }
    private void refreshCredentials() {
        Map<String, String> next = new HashMap<>();
        for (String name : presets.keySet()) {
            String key = crewKey(name);
            if (key.length() >= 24) next.put(SwarmConnection.credentialSelector(key), key);
        }
        credentials = Map.copyOf(next);
    }
    public List<SwarmConnection> allConnections() {
        if (host == null) return List.of();
        return Arrays.stream(host.getConnections()).filter(Objects::nonNull).toList();
    }
    public List<SwarmConnection> connectionsForCrew(String name) {
        String key = crewKey(name);
        if (key.length() < 24) return List.of();
        String selector = SwarmConnection.credentialSelector(key);
        return allConnections().stream().filter(c -> selector.equals(c.credentialId())).toList();
    }
    public SwarmCrew coordinator(String name) {
        if (!presets.containsKey(name)) throw new IllegalArgumentException("Unknown crew");
        return coordinators.computeIfAbsent(name, n -> new SwarmCrew(this, n));
    }
    public SwarmCrew controlCrew() { return mode.get() == Mode.Host ? coordinator(selectedCrew) : crew; }
    public void claimLocalCrew(SwarmCrew next) {
        if (crew != next && crew.localAssigned()) throw new IllegalStateException("This host already participates in another job. Use a workers-only crew or end that job first.");
        crew = next;
    }
    public void validateJobArea(SwarmCrew requesting, net.minecraft.core.BlockPos origin, com.google.gson.JsonObject layout, int length) {
        for (String name : List.copyOf(presets.keySet())) {
            SwarmCrew other = coordinator(name);
            if (other != requesting && other.conflictsWith(origin, layout, length))
                throw new IllegalStateException("Work/supply area overlaps crew " + name + ". Separate the jobs before preparing this crew.");
        }
    }
    public boolean hasJobs() { return crew.assigned() || coordinators.values().stream().anyMatch(SwarmCrew::assigned) || tasks.hasWork(); }
    public List<SwarmCrew.MemberView> allMembers() {
        Map<UUID, SwarmCrew.MemberView> result = new LinkedHashMap<>();
        if (mode.get() == Mode.Worker) return crew.members();
        for (String name : presets.keySet()) for (var member : coordinator(name).members()) {
            var previous = result.get(member.id());
            if (previous == null || member.connected() && (!previous.connected() || !member.phase().equals("idle"))) result.put(member.id(), member);
        }
        return List.copyOf(result.values());
    }
    public String workerCrew(UUID id) {
        for (String name : presets.keySet()) {
            SwarmConnection connection = coordinator(name).connectionForWorker(id);
            if (connection != null && connection.connected()) return name;
        }
        return "Offline";
    }
    public void reassignWorker(UUID id, String target) { reassignWorker(id, target, activeJob(target)); }
    private void reassignWorker(UUID id, String target, UUID joinJob) {
        if (!isHost() || !presets.containsKey(target)) throw new IllegalStateException("Start the host and choose a destination crew.");
        String source = workerCrew(id);
        if (source.equals(target)) return;
        if (tasks.reserves(id)) throw new IllegalStateException("Cancel this worker's queued tasks and let it return to its highway before changing crews.");
        if (!presets.containsKey(source)) throw new IllegalStateException("Worker is offline.");
        SwarmCrew old = coordinator(source);
        var member = old.members().stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
        if (!member.available() || old.isParticipant(id) || old.hasPendingEnds(Set.of(id)))
            throw new IllegalStateException("Release this worker's current job first; wait for worker release confirmation.");
        var message = new com.google.gson.JsonObject();
        SwarmConnection connection = old.connectionForWorker(id);
        if (connection == null || !connection.connected()) throw new IllegalStateException("Worker disconnected before reassignment.");
        message.addProperty("type", "assign-crew"); message.addProperty("crew", crewLabel(target)); message.addProperty("sealedKey", connection.sealSecret(crewKey(target)));
        message.addProperty("crewId", target);
        if (joinJob != null) message.addProperty("joinJob", joinJob.toString());
        if (connection == null || !connection.send(message.toString())) throw new IllegalStateException("Worker disconnected before reassignment.");
        info("Moving %s to %s; waiting for its authenticated reconnect.", member.name(), crewLabel(target));
    }
    /** Authenticated host-only management message; never forwarded to other workers. */
    public boolean handleManagement(SwarmConnection connection, com.google.gson.JsonObject message, boolean hostSide) {
        String type=dev.monocle.coordinator.TaskWire.text(message,"type");
        if (type.equals("crew-join-request")) {
            if (!hostSide) throw new IllegalArgumentException("Crew join requests must come from a worker");
            try {
                UUID workerId = workerFor(connection), jobId = UUID.fromString(dev.monocle.coordinator.TaskWire.text(message,"job"));
                String target = dev.monocle.coordinator.TaskWire.text(message,"crew"), source = workerCrew(workerId);
                JsonObject job = requireJob(jobId);
                if (!target.equals(BotJobs.text(job,"crew")) || !BotJobs.unfinished(job)) throw new IllegalArgumentException("That crew job is no longer joinable");
                if (!source.equals(target)) {
                    if (!job.get("publicJoin").getAsBoolean()) throw new IllegalStateException("That job is not open for joining");
                    reassignWorker(workerId,target,jobId); return true;
                } else addWorker(target,workerId);
                joinResult(connection,jobId,true,false,"Joining the live crew job");return true;
            } catch(RuntimeException e){String detail=e.getMessage()==null?"Join is not currently available":e.getMessage();joinResult(connection,null,false,detail.startsWith("Bring the worker")||detail.startsWith("Wait for"),detail);return true;}
        }
        if (type.equals("crew-join-result")) {
            if (hostSide || connection != worker) throw new IllegalArgumentException("Invalid crew join result");
            boolean accepted=message.has("accepted")&&message.get("accepted").getAsBoolean(),retry=message.has("retry")&&message.get("retry").getAsBoolean();
            connectionDetail=(accepted?"Join accepted":retry?"Join pending":"Join refused")+(message.has("detail")?" · "+message.get("detail").getAsString():"");
            if(accepted||!retry){pendingJoinCrew="";pendingJoinJob="";save();}
            return true;
        }
        if(type.equals("worker-tpa-request")) {
            if(!hostSide)throw new IllegalArgumentException("TPA observations must come from a worker");
            for(String crewId:presets.keySet()) for(var member:coordinator(crewId).members()) if(coordinator(crewId).connectionForWorker(member.id())==connection) {
                tasks.requestAutoTpy(member.id(),crewId,message);return true;
            }
            throw new IllegalArgumentException("Worker must announce its identity first");
        }
        if(type.equals("worker-chat")) {
            if(!hostSide)throw new IllegalArgumentException("Chat reports must come from a worker");
            for(String crewId:presets.keySet()) {var member=coordinator(crewId).members().stream().filter(m->coordinator(crewId).connectionForWorker(m.id())==connection).findFirst();
                if(member.isPresent()) {chat.append(member.get().id(),crewId,member.get().name(),"",dev.monocle.coordinator.TaskWire.text(message,"direction"),dev.monocle.coordinator.TaskWire.text(message,"text"),message.has("parts")?message.getAsJsonArray("parts"):null);return true;} }
            return true;
        }
        if(type.equals("manage-chat")) {
            if(hostSide||connection!=worker||!connection.connected())throw new IllegalArgumentException("Chat commands must come from the connected host");
            UUID id=UUID.fromString(dev.monocle.coordinator.TaskWire.text(message,"id"));String text=dev.monocle.coordinator.BotChat.command(dev.monocle.coordinator.TaskWire.text(message,"text"));
            if(!chat.first(id))return true;
            if(!Utils.canUpdate()||!worldScope().equals(dev.monocle.coordinator.TaskWire.text(message,"scope"))) { reportChat("error","Chat not sent: worker world changed");return true; }
            dev.monocle.client.utils.player.ChatUtils.sendPlayerMsg(text,false);reportChat("sent",text);return true;
        }
        if (tasks.handle(connection, message, hostSide)) return true;
        if (!message.has("type") || !message.get("type").getAsString().equals("assign-crew")) return false;
        if (hostSide) throw new IllegalArgumentException("Workers cannot reassign crews.");
        if (connection != worker || !connection.connected()) throw new IllegalStateException("Unauthenticated reassignment");
        if (tasks.hasWork() || crew.assigned() || crew.inspect().recovery() || dev.monocle.client.systems.modules.Modules.get().get(dev.monocle.client.systems.modules.world.HighwayBuilder.class).hasJob())
            throw new IllegalStateException("End the existing worker job before reassignment.");
        String key = connection.openSecret(message.get("sealedKey").getAsString()), name = message.get("crew").getAsString();
        if (key.length() < 24 || key.length() > 128 || name.isBlank() || name.length() > 48 || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid crew assignment credentials");
        crewKey.set(key); workerCrewName = name;
        pendingJoinCrew = message.has("crewId") ? message.get("crewId").getAsString() : "";
        pendingJoinJob = message.has("joinJob") ? UUID.fromString(message.get("joinJob").getAsString()).toString() : "";
        save();
        info("Host assigned this worker to %s. Reconnecting with its crew key.", name);
        return true;
    }
    private static void joinResult(SwarmConnection connection,UUID job,boolean accepted,boolean retry,String detail){JsonObject result=new JsonObject();result.addProperty("type","crew-join-result");result.addProperty("job",job==null?"":job.toString());result.addProperty("accepted",accepted);result.addProperty("retry",retry);result.addProperty("detail",detail);connection.send(result.toString());}
    private UUID workerFor(SwarmConnection connection) {
        for (String crewId : presets.keySet()) for (var member : coordinator(crewId).members())
            if (coordinator(crewId).connectionForWorker(member.id()) == connection) return member.id();
        throw new IllegalArgumentException("Worker must announce its identity first");
    }
    private UUID activeJob(String crewId) {
        syncJobs();
        return catalog.records.values().stream().filter(j -> crewId.equals(BotJobs.text(j,"crew")) && BotJobs.unfinished(j))
            .map(j -> UUID.fromString(BotJobs.text(j,"id"))).findFirst().orElse(null);
    }
    public void setJobPublic(UUID id, boolean enabled) {
        requireHostRole(); JsonObject job=requireJob(id).deepCopy();
        if (!BotJobs.unfinished(job) || BotJobs.text(job,"crew").isEmpty()) throw new IllegalStateException("Assign an unfinished job before opening it for joining.");
        job.addProperty("publicJoin",enabled);catalog.put(job);
    }
    public List<CrewDiscovery> discoveries() { return List.copyOf(discoveries); }
    public void updateCrewDiscovery(com.google.gson.JsonArray values) {
        if (mode.get()!=Mode.Worker) return;
        List<CrewDiscovery> next=new ArrayList<>();
        if(values!=null) for(var value:values) {
            if(next.size()==32)break;JsonObject row=value.getAsJsonObject();
            String id=BotJobs.text(row,"id"),name=BotJobs.text(row,"name"),job=BotJobs.text(row,"job"),jobName=BotJobs.text(row,"jobName"),status=BotJobs.text(row,"status");
            if(id.length()>64||name.length()>48||jobName.length()>64||status.length()>96)continue;
            if(!job.isEmpty())try{UUID.fromString(job);}catch(IllegalArgumentException ignored){continue;}
            int workers=Math.clamp(row.get("workers").getAsInt(),0,3),capacity=Math.clamp(row.get("capacity").getAsInt(),1,3);
            next.add(new CrewDiscovery(id,name,job,jobName,status,workers,capacity,row.has("publicJoin")&&row.get("publicJoin").getAsBoolean()));
        }
        discoveries.clear();discoveries.addAll(next);
    }
    public com.google.gson.JsonArray crewDiscovery() {
        syncJobs();com.google.gson.JsonArray result=new com.google.gson.JsonArray();
        for(String id:presets.keySet()) {
            JsonObject row=new JsonObject();row.addProperty("id",id);row.addProperty("name",crewLabel(id));
            JsonObject job=catalog.records.values().stream().filter(j->id.equals(BotJobs.text(j,"crew"))&&BotJobs.unfinished(j)).findFirst().orElse(null);
            var state=coordinator(id).inspect();int count=(int)coordinator(id).members().stream().filter(SwarmCrew.MemberView::connected).count();
            row.addProperty("workers",count);row.addProperty("capacity",dev.monocle.coordinator.HighwayCoordinator.MAX_CREW_MEMBERS);row.addProperty("status",state.phase());
            row.addProperty("job",job==null?"":BotJobs.text(job,"id"));row.addProperty("jobName",job==null?"":BotJobs.text(job,"name"));row.addProperty("publicJoin",job!=null&&job.get("publicJoin").getAsBoolean());result.add(row);
        }
        return result;
    }
    public void requestPublicJoin(String crewId,String job) {
        if(!isWorker())throw new IllegalStateException("Connect this worker to the host first.");
        UUID.fromString(job);pendingJoinCrew=crewId;pendingJoinJob=job;pendingJoinAt=0;save();sendPendingJoin();
    }
    private void sendPendingJoin() {
        if(!isWorker()||pendingJoinJob.isBlank()||System.nanoTime()<pendingJoinAt)return;
        JsonObject request=new JsonObject();request.addProperty("type","crew-join-request");request.addProperty("crew",pendingJoinCrew);request.addProperty("job",pendingJoinJob);
        worker.send(request.toString());pendingJoinAt=System.nanoTime()+1_000_000_000L;
    }
    public void sendMessage(String message) {
        if (!isHost()) throw new IllegalStateException("Start the host first.");
        for (var connection : connectionsForCrew(selectedCrew)) connection.send(message);
    }
    public void sendChat(String crewId, UUID target, String text) {
        requireHostRole();text=dev.monocle.coordinator.BotChat.command(text);
        for(var member:coordinator(crewId).members()) { if(target!=null&&!target.equals(member.id()))continue;
            SwarmConnection c=coordinator(crewId).connectionForWorker(member.id());if(c==null||!c.connected())continue;
            JsonObject m=new JsonObject();m.addProperty("type","manage-chat");m.addProperty("id",UUID.randomUUID().toString());m.addProperty("text",text);m.addProperty("scope",worldScope());c.send(m.toString()); }
    }
    private void reportChat(String direction,String text) {
        if(!active||mode.get()!=Mode.Worker||worker==null||!worker.connected())return;
        JsonObject m=new JsonObject();m.addProperty("type","worker-chat");m.addProperty("direction",direction);m.addProperty("text",text.substring(0,Math.min(text.length(),2048)));worker.send(m.toString());
    }
    @EventHandler
    private void onChat(dev.monocle.client.events.game.ReceiveMessageEvent event) {
        if(!active||mode.get()!=Mode.Worker||worker==null||!worker.connected())return;
        String plain=event.getMessage().getString();
        if(plain.length()>2048) {reportChat("received",plain);return;}
        var parts=new com.google.gson.JsonArray();
        event.getMessage().visit((style,text)-> {
            if(!text.isEmpty()) {
                JsonObject part=new JsonObject();part.addProperty("text",text);
                if(style.getColor()!=null)part.addProperty("color",String.format("#%06x",style.getColor().getValue()&0xffffff));
                parts.add(part);
            }
            return java.util.Optional.empty();
        },net.minecraft.network.chat.Style.EMPTY);
        JsonObject m=new JsonObject();m.addProperty("type","worker-chat");m.addProperty("direction","received");m.addProperty("text",plain);
        if(parts.size()<=256)m.add("parts",parts);
        worker.send(m.toString());
    }
    public void savePreset(String name, int length, Set<UUID> workers) {
        CrewPreset preset = checkedPreset(name, length, workers);
        if (coordinators.containsKey(preset.name()) && coordinators.get(preset.name()).assigned()) throw new IllegalStateException("End the crew's current job before changing its roster.");
        if (!presets.containsKey(preset.name()) && presets.size() >= 32) throw new IllegalArgumentException("Keep at most 32 saved crews.");
        presets.put(preset.name(), preset);
        if (!preset.name().equals("Default")) crewKeys.putIfAbsent(preset.name(), UUID.randomUUID().toString().replace("-", ""));
        refreshCredentials(); save();
    }
    public static CrewPreset checkedPreset(String name, int length, Set<UUID> workers) {
        name = name.trim();
        if (name.isEmpty() || name.length() > 48 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Choose a crew name of 1–48 printable characters.");
        if (length < 16 || length > HighwayJobs.MAX_LENGTH || workers.size() > dev.monocle.coordinator.HighwayCoordinator.MAX_CREW_MEMBERS)
            throw new IllegalArgumentException("Crew length must be 16–100,000, with at most 3 players total.");
        return new CrewPreset(name, length, workers);
    }
    public void removePreset(String name) {
        requireHostRole(); syncJobs();
        if (!presets.containsKey(name)) throw new IllegalArgumentException("Unknown crew");
        if (tasks.usesCrew(name)) throw new IllegalStateException("Cancel this crew's workflow queue and complete its return handoffs before deleting it.");
        if (catalog.records.values().stream().anyMatch(job -> BotJobs.text(job, "crew").equals(name))) throw new IllegalStateException("Release this crew's job first. The job will remain in Jobs.");
        if (coordinator(name).assigned() || coordinator(name).inspect().recovery() || coordinator(name).pendingEndCount() > 0 || connectionsForCrew(name).stream().anyMatch(SwarmConnection::connected)) throw new IllegalStateException("End its job, wait for worker acknowledgments and move connected workers before removing this crew.");
        presets.remove(name); crewLabels.remove(name); crewKeys.remove(name); coordinators.remove(name);
        if (selectedCrew.equals(name)) selectedCrew = presets.keySet().stream().findFirst().orElse("");
        refreshCredentials(); save();
    }

    private String worldScope() {
        if (!Utils.canUpdate()) throw new IllegalStateException("Join the job's Minecraft server first.");
        return (mc.getCurrentServer() == null ? "local" : mc.getCurrentServer().ip) + "\n" + mc.level.dimension().identifier();
    }
    public List<JobView> jobs() {
        if (mode.get() != Mode.Host) return List.of();
        syncJobs();
        return catalog.records.values().stream().map(this::jobView).toList();
    }
    public JobView job(UUID id) { syncJobs(); return jobView(requireJob(id)); }
    private JsonObject requireJob(UUID id) {
        catalog.load(); JsonObject value = catalog.records.get(id);
        if (value == null) throw new IllegalArgumentException("Unknown job");
        return value;
    }
    private JobView jobView(JsonObject value) {
        String owner = BotJobs.text(value, "crew");
        String detail = owner.isEmpty() ? switch (BotJobs.text(value, "status")) {
            case "Complete" -> "Finished work. Its saved record can be kept or explicitly deleted.";
            case "Cancelled" -> "Cancelled work. Its record is retained until explicitly deleted.";
            default -> "Unclaimed work. Select a crew to assign it.";
        } : "Inspect the assigned crew before changing this job.";
        if (presets.containsKey(owner)) detail = coordinator(owner).inspect().detail();
        return new JobView(UUID.fromString(BotJobs.text(value, "id")), BotJobs.text(value, "name"), "Highway", owner, BotJobs.text(value, "status"),
            value.get("progress").getAsInt(), value.get("length").getAsInt(), new BlockPos(value.get("x").getAsInt(), value.get("y").getAsInt(), value.get("z").getAsInt()),
            BotJobs.text(value, "scope"), value.getAsJsonObject("layout").deepCopy(), detail, value.get("includeHost").getAsBoolean(),
            BotJobs.text(value.getAsJsonObject("workflow"), "id"), BotJobs.text(value.getAsJsonObject("workflow"), "name"), value.get("publicJoin").getAsBoolean());
    }
    public UUID createHighwayJob(String name, int length) {
        return createHighwayJob(name, length, BotWorkflows.DEFAULT_ID);
    }
    public UUID createHighwayJob(String name, int length, String workflowId) {
        return createHighwayJob(name, length, workflowId, SwarmCrew.WorkSharing.Lanes);
    }
    public UUID createHighwayJob(String name, int length, String workflowId, SwarmCrew.WorkSharing sharing) {
        requireHostRole(); catalog.load();
        JsonObject value = new JsonObject(); UUID id = UUID.randomUUID();
        value.addProperty("id", id.toString()); value.addProperty("name", name); value.addProperty("length", length); value.addProperty("progress", 0);
        captureGeometry(value); setWorkflow(value, workflowId);
        value.getAsJsonObject("layout").addProperty("workSharing", java.util.Objects.requireNonNull(sharing).name());
        catalog.put(value); return id;
    }
    private void setWorkflow(JsonObject value, String workflowId) {
        JsonObject plan = workflows.compile(workflowId);
        value.getAsJsonObject("layout").addProperty("operation", BotWorkflows.operation(plan));
        value.add("workflow", plan); value.remove("memberWorkflows"); value.remove("duties");
    }
    private void captureGeometry(JsonObject value) {
        value.addProperty("scope", worldScope()); BlockPos origin = mc.player.blockPosition();
        value.addProperty("x", origin.getX()); value.addProperty("y", origin.getY()); value.addProperty("z", origin.getZ());
        HighwayBuilder builder = Modules.get().get(HighwayBuilder.class);
        JsonObject layout = builder.crewLayout(); builder.validateCrewLayout(layout);
        value.add("layout", layout);
    }
    private JsonObject editableJob(UUID id) {
        requireHostRole(); syncJobs(); JsonObject value = requireJob(id).deepCopy();
        if (!BotJobs.text(value, "crew").isEmpty() || !BotJobs.unfinished(value)) throw new IllegalStateException("Release an unfinished job before editing it.");
        return value;
    }
    public void updateHighwayJob(UUID id, String name, int length) {
        updateHighwayJob(id, name, length, job(id).workflowId());
    }
    public void updateHighwayJob(UUID id, String name, int length, String workflowId) {
        updateHighwayJob(id, name, length, workflowId, SwarmCrew.workSharing(job(id).layout()));
    }
    public void updateHighwayJob(UUID id, String name, int length, String workflowId, SwarmCrew.WorkSharing sharing) {
        JsonObject value = editableJob(id);
        if (value.get("progress").getAsInt() > 0 && length != value.get("length").getAsInt()) throw new IllegalStateException("A started job's geometry is fixed; create a new job to change its length.");
        if (!BotJobs.text(value.getAsJsonObject("workflow"), "id").equals(workflowId)) {
            if (value.get("progress").getAsInt() > 0) throw new IllegalStateException("A started job keeps its captured workflow. Create a new job to change it.");
            setWorkflow(value, workflowId);
        }
        value.getAsJsonObject("layout").addProperty("workSharing", java.util.Objects.requireNonNull(sharing).name());
        value.addProperty("name", name); value.addProperty("length", length); catalog.put(value);
    }
    public void recaptureHighwayJob(UUID id) {
        JsonObject value = editableJob(id);
        if (value.get("progress").getAsInt() > 0) throw new IllegalStateException("A started job keeps its original road position and layout.");
        SwarmCrew.WorkSharing sharing = SwarmCrew.workSharing(value.getAsJsonObject("layout"));
        captureGeometry(value);
        value.getAsJsonObject("layout").addProperty("workSharing", sharing.name());
        value.getAsJsonObject("layout").addProperty("operation", BotWorkflows.operation(value.getAsJsonObject("workflow")));
        catalog.put(value);
    }
    public void refreshJobWorkflow(UUID id) {
        JsonObject value = editableJob(id);
        if (value.get("progress").getAsInt() > 0) throw new IllegalStateException("A started job keeps its captured workflow.");
        setWorkflow(value, BotJobs.text(value.getAsJsonObject("workflow"), "id")); catalog.put(value);
    }
    public void assignJob(UUID id, String crewId, boolean includeHost) {
        assignJob(id, crewId, includeHost, Map.of());
    }
    public void assignJob(UUID id, String crewId, boolean includeHost, Map<UUID, String> overrides) {
        requireHostRole(); syncJobs();
        if (!isHost()) throw new IllegalStateException("Start the host and connect the crew first.");
        JsonObject value = editableJob(id); SwarmCrew controller = coordinator(crewId);
        if (presets.get(crewId).workers().isEmpty()) throw new IllegalStateException("Select at least one worker in Crews and Save roster before assigning this job.");
        if (catalog.records.values().stream().anyMatch(j -> BotJobs.text(j, "crew").equals(crewId)) || controller.assigned() || controller.inspect().recovery())
            throw new IllegalStateException("This crew still owns a job.");
        if (!worldScope().equals(BotJobs.text(value, "scope"))) throw new IllegalStateException("This job belongs to another server or dimension.");
        Set<UUID> roster = new LinkedHashSet<>(presets.get(crewId).workers());
        for (UUID workerId : roster) if (tasks.reserves(workerId)) throw new IllegalStateException("A selected worker already has queued workflow work; manage it from Jobs first.");
        if (includeHost) roster.add(mc.player.getUUID());
        if (roster.size() > value.getAsJsonObject("layout").get("width").getAsInt()) throw new IllegalArgumentException("The highway needs at least one floor column per participating account, including the host.");
        configureWorkerWorkflows(value, roster, overrides);
        value.addProperty("crew", crewId); value.addProperty("includeHost", includeHost); value.addProperty("status", "Assigning");
        catalog.put(value); // Durable ownership precedes commands to any worker.
        try { tasks.queueHighway(value.deepCopy(), crewId, presets.get(crewId).workers(), includeHost); }
        catch (RuntimeException e) {
            if (!controller.assigned() && !controller.inspect().recovery()) {
                value.addProperty("crew", ""); value.addProperty("status", "Unassigned"); catalog.put(value);
            }
            throw e;
        }
        syncJobs();
    }
    /** Scheduler bridge: profiles are already installed by every selected worker's runtime. */
    void startTaskHighway(JsonObject definition, String crewId, Set<UUID> workers, boolean includeHost) {
        requireHostRole();
        JsonObject value = BotJobs.checked(definition); catalog.put(value);
        try { coordinator(crewId).startJob(value.deepCopy(), workers, includeHost); }
        catch (RuntimeException e) {
            if (!coordinator(crewId).assigned()) { value.addProperty("crew", ""); value.addProperty("status", "Unassigned"); catalog.put(value); }
            throw e;
        }
    }
    private void configureWorkerWorkflows(JsonObject value, Set<UUID> roster, Map<UUID, String> overrides) {
        if (!roster.containsAll(overrides.keySet())) throw new IllegalArgumentException("Workflow overrides must belong to this job's roster.");
        JsonObject plans = new JsonObject();
        for (var entry : overrides.entrySet()) if (!entry.getValue().isEmpty()) plans.add(entry.getKey().toString(), workflows.compile(entry.getValue()));
        value.add("memberWorkflows", plans);
        applyWorkflowDuties(value, roster);
    }
    /** Validate capabilities before any assignment leaves the host; workers still validate their received snapshots. */
    public static void applyWorkflowDuties(JsonObject value, Set<UUID> roster) { dev.monocle.coordinator.HighwayCoordinator.applyWorkflowDuties(value, roster); }
    public void addWorker(String crewId, UUID workerId) {
        requireHostRole();
        if (tasks.joinHighway(crewId, workerId)) return;
        if (tasks.reserves(workerId)) throw new IllegalStateException("This worker is reserved by a workflow or teleport. Let its queue settle before manually adding it.");
        coordinator(crewId).addWorker(workerId);
        // Membership is added to the saved roster only after the coordinator confirms the new execution generation.
    }
    private SwarmCrew assignedController(UUID id) {
        requireHostRole(); syncJobs();
        String owner = BotJobs.text(requireJob(id), "crew");
        if (owner.isEmpty() || !presets.containsKey(owner)) throw new IllegalStateException("This job has no assigned crew.");
        return coordinator(owner);
    }
    public void pauseJob(UUID id) { if (!tasks.controlNative(id, "pause")) assignedController(id).pause(); syncJobs(); }
    public void resumeJob(UUID id) { if (!tasks.controlNative(id, "resume")) assignedController(id).resume(); syncJobs(); }
    public void releaseJob(UUID id) {
        SwarmCrew controller = assignedController(id); JsonObject value = requireJob(id).deepCopy();
        String status = BotJobs.text(value, "status");
        if (!Set.of("Complete", "Cancelled").contains(status)) value.addProperty("status", "Releasing");
        catalog.put(value); // Preserve the checkpoint before ending this execution.
        try { if (!tasks.controlNative(id, "cancel")) controller.releaseJob(); }
        catch (RuntimeException e) { value.addProperty("status", status); catalog.put(value); throw e; }
        syncJobs();
    }
    public void cancelJob(UUID id) {
        requireHostRole(); syncJobs(); JsonObject value = requireJob(id).deepCopy();
        String previous = BotJobs.text(value, "status"); value.addProperty("status", "Cancelled"); catalog.put(value);
        String owner = BotJobs.text(value, "crew");
        if (!owner.isEmpty()) {
            try { if (!tasks.controlNative(id, "cancel")) coordinator(owner).endJob(); }
            catch (RuntimeException e) { value.addProperty("status", previous); catalog.put(value); throw e; }
        }
        syncJobs();
    }
    void cancelTaskHighway(UUID id) {
        catalog.load(); JsonObject value = catalog.records.get(id);
        if (value == null || Set.of("Cancelled", "Releasing").contains(BotJobs.text(value, "status"))) return;
        value = value.deepCopy(); value.addProperty("status", "Cancelled"); catalog.put(value);
    }
    public void deleteJob(UUID id) {
        requireHostRole(); syncJobs();
        if (!BotJobs.text(requireJob(id), "crew").isEmpty()) throw new IllegalStateException("Release or cancel the assigned crew before deleting this job.");
        var linked = tasks.historyRecords().entrySet().stream().filter(e -> id.equals(BotHistory.nativeId(e.getValue()))).toList();
        for (var e : linked) if (!tasks.deletable(e.getKey(), e.getValue())) throw new IllegalStateException("Finish the linked workflow and its recovery before deleting this job.");
        for (var e : linked) tasks.removeHistory(e.getKey());
        catalog.remove(id);
    }

    public record HistoryView(UUID id, UUID nativeJob, List<UUID> tasks, String name, String status, String detail, long finishedAt) {}
    public List<HistoryView> jobHistory() {
        requireHostRole(); syncJobs();
        Map<UUID, HistoryView> history = new LinkedHashMap<>();
        for (var e : catalog.records.entrySet()) if (BotHistory.nativeFinished(e.getValue())) {
            var j = jobView(e.getValue());
            history.put(e.getKey(), new HistoryView(e.getKey(), e.getKey(), List.of(), j.name(), j.status(), "Highway · " + j.progress() + "/" + j.length() + " rows", BotHistory.finishedAt(e.getValue())));
        }
        for (var e : tasks.historyRecords().entrySet()) if (BotScheduler.terminal(BotJobs.text(e.getValue(), "status"))) {
            JsonObject t = e.getValue(); UUID nativeId = BotHistory.nativeId(t);
            HistoryView previous = nativeId == null ? null : history.get(nativeId);
            List<UUID> ids = new ArrayList<>(previous == null ? List.of() : previous.tasks()); ids.add(e.getKey());
            UUID key = previous == null ? e.getKey() : previous.id();
            history.put(key, new HistoryView(key, previous == null ? null : previous.nativeJob(), List.copyOf(ids),
                previous == null ? BotJobs.text(t, "name") : previous.name(), BotJobs.text(t, "status"),
                (previous == null ? "" : previous.detail() + "\n") + BotJobs.text(t, "detail"),
                tasks.deletable(e.getKey(), t) && (previous == null || previous.tasks().isEmpty() || previous.finishedAt() > 0)
                    ? Math.max(previous == null ? 0 : previous.finishedAt(), BotHistory.finishedAt(t)) : 0));
        }
        return history.values().stream().sorted(Comparator.comparingLong(HistoryView::finishedAt).reversed()).toList();
    }
    public void deleteTaskHistory(UUID id) {
        requireHostRole();
        JsonObject task = tasks.historyRecords().get(id);
        if (task == null) return;
        if (!tasks.deletable(id, task)) throw new IllegalStateException("Finish the task and its recovery before deleting history.");
        UUID nativeId = BotHistory.nativeId(task);
        syncJobs();
        if (nativeId != null && catalog.records.containsKey(nativeId) && BotHistory.nativeFinished(catalog.records.get(nativeId))) deleteJob(nativeId);
        else tasks.removeHistory(id);
    }
    public void deleteHistory(UUID id) {
        HistoryView entry = jobHistory().stream().filter(h -> h.id().equals(id)).findFirst().orElse(null);
        if (entry == null) return;
        if (entry.nativeJob() != null) deleteJob(entry.nativeJob());
        else for (UUID task : entry.tasks()) tasks.removeHistory(task);
    }
    public void clearJobHistory() {
        for (HistoryView entry : jobHistory()) if (entry.finishedAt() > 0) deleteHistory(entry.id());
    }
    private long nextHistoryCleanup;
    private void expireJobHistory() {
        long now = java.lang.System.currentTimeMillis();
        if (mode.get() != Mode.Host || now < nextHistoryCleanup) return;
        nextHistoryCleanup = now + 60_000;
        int days = dev.monocle.client.systems.config.Config.get().botJobHistoryDays.get();
        for (HistoryView entry : jobHistory()) if (BotHistory.expired(entry.finishedAt(), now, days)) deleteHistory(entry.id());
    }
    public void startSelectedHighway(int length) {
        requireHostRole();
        UUID id = createHighwayJob("Highway · " + crewLabel(selectedCrew).substring(0, Math.min(38, crewLabel(selectedCrew).length())), length);
        assignJob(id, selectedCrew, true);
    }
    public void endCrewExecution(String crewId) {
        requireHostRole(); syncJobs();
        for (JsonObject value : catalog.records.values()) if (BotJobs.text(value, "crew").equals(crewId)) {
            cancelJob(UUID.fromString(BotJobs.text(value, "id"))); return;
        }
        coordinator(crewId).endJob(); // Legacy inspection-only journal, before catalog migration.
    }
    /** Commit final verified progress before an execution journal is cleared or reassigned. */
    public void checkpointJobs() {
        requireHostRole(); syncJobs(); catalog.save(); catalogDirty = false; catalogSavedAt = System.nanoTime();
    }
    /** Import legacy assignments and checkpoint active jobs. No GUI needs to be open. */
    private void syncJobs() {
        if (mode.get() != Mode.Host) return;
        catalog.load(); boolean changed = false;
        for (String crewId : List.copyOf(presets.keySet())) {
            SwarmCrew controller = coordinator(crewId); JsonObject snapshot = controller.jobSnapshot();
            if (snapshot == null) continue;
            UUID id = UUID.fromString(BotJobs.text(snapshot, "id"));
            JsonObject old = catalog.records.get(id);
            if (old != null && !BotJobs.text(old, "crew").isEmpty() && !BotJobs.text(old, "crew").equals(crewId))
                throw new IllegalStateException("Conflicting saved crew claims for job " + id + "; inspect both crews.");
            JsonObject next = old == null ? BotJobs.checked(snapshot) : old.deepCopy();
            next.addProperty("crew", crewId);
            // The execution journal may contain a newer rewind than the separate catalog.
            // Replaying an older checkpoint is safe; merging by maximum could skip a restored hole.
            next.addProperty("progress", snapshot.get("progress").getAsInt());
            if (snapshot.has("execution")) next.addProperty("execution", BotJobs.text(snapshot, "execution"));
            if (controller.assigned() && snapshot.has("members")) {
                Set<UUID> workers = new LinkedHashSet<>();
                String hostId = BotJobs.text(snapshot, "hostMember");
                for (var member : snapshot.getAsJsonArray(snapshot.has("preferredMembers") ? "preferredMembers" : "members")) if (!member.getAsString().equals(hostId)) workers.add(UUID.fromString(member.getAsString()));
                CrewPreset preset = presets.get(crewId);
                if (!preset.workers().equals(workers)) { presets.put(crewId, new CrewPreset(crewId, preset.length(), workers)); save(); }
                next.addProperty("includeHost", !hostId.isEmpty());
            }
            String phase = controller.inspect().phase();
            if (!Set.of("Cancelled", "Releasing").contains(BotJobs.text(next, "status"))) {
                String state = !controller.assigned() ? "Inspection required" : controller.roadComplete() ? "Complete" : switch (phase) {
                    case "complete" -> "Complete";
                    case "building" -> "Running";
                    case "paused" -> "Paused";
                    case "positioning", "ready" -> "Positioning";
                    case "regrouping", "synchronized" -> "Rebalancing";
                    default -> phase.contains("rebalanc") || phase.contains("joining") ? "Rebalancing" : phase.contains("lost") || phase.contains("inspect") ? "Inspection required" : "Blocked";
                };
                next.addProperty("status", state);
            }
            if (!next.equals(old)) { catalog.records.put(id, next); changed = true; }
        }
        for (JsonObject value : catalog.records.values()) {
            String owner = BotJobs.text(value, "crew");
            if (owner.isEmpty()) continue;
            SwarmCrew controller = presets.containsKey(owner) ? coordinator(owner) : null;
            JsonObject current = controller == null ? null : controller.jobSnapshot();
            changed |= BotJobs.settleReleased(value, controller != null,
                current != null ? BotJobs.text(value, "id").equals(BotJobs.text(current, "id")) : controller != null && controller.inspect().recovery(),
                tasks.pendingNativeJob(UUID.fromString(BotJobs.text(value, "id"))),
                controller != null && controller.hasEndedSupplies(BotJobs.text(value, "execution")));
        }
        for (JsonObject value : catalog.records.values()) changed |= BotHistory.stamp(value, BotHistory.nativeFinished(value), java.lang.System.currentTimeMillis());
        catalogDirty |= changed;
        long now = System.nanoTime();
        if (catalogDirty && now - catalogSavedAt >= 1_000_000_000L) { catalog.save(); catalogDirty = false; catalogSavedAt = now; }
    }
    public List<Event> events() { return List.copyOf(events); }
    public void info(String message, Object... args) { notice(NotificationFeed.Severity.Info, message, args); }
    public void warning(String message, Object... args) { notice(NotificationFeed.Severity.Warning, message, args); }
    public void error(String message, Object... args) { notice(NotificationFeed.Severity.Error, message, args); }
    private void notice(NotificationFeed.Severity severity, String format, Object... args) {
        String message = String.format(Locale.ROOT, format, args);
        if (events.size() == 40) events.removeFirst();
        events.addLast(new Event(java.time.LocalTime.now().withNano(0).toString(), severity.name(), message));
        MonocleClient.LOG.info("[Workers] {}", message);
        Notifications.post("Workers", message, severity, message);
    }

    @Override public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.put("settings", settings.toTag()); tag.putBoolean("active", active);
        tag.putString("selectedCrew", selectedCrew);
        tag.putString("workerCrew", workerCrewName);
        tag.putString("pendingJoinCrew",pendingJoinCrew);tag.putString("pendingJoinJob",pendingJoinJob);
        ListTag crews = new ListTag();
        for (CrewPreset preset : presets.values()) {
            CompoundTag value = new CompoundTag();
            value.putString("name", preset.name()); value.putInt("length", preset.length());
            value.putString("label", crewLabel(preset.name()));
            if (!preset.name().equals("Default")) value.putString("key", crewKey(preset.name()));
            ListTag workers = new ListTag();
            for (UUID id : preset.workers()) workers.add(StringTag.valueOf(id.toString()));
            value.put("workers", workers); crews.add(value);
        }
        tag.put("crews", crews); return tag;
    }
    @Override public Bots fromTag(CompoundTag tag) {
        if (settingsLoaded && (hasJobs() || hasPersistedCrewWork())) {
            warning("Workers settings were not reloaded: release assigned/recovered jobs and wait for worker acknowledgments first so their keys and recovery controllers are retained.");
            return this;
        }
        disable();
        settings.fromTag(tag.getCompoundOrEmpty("settings"));
        crew = new SwarmCrew(this);
        crewRole = mode.get();
        workerCrewName = tag.getStringOr("workerCrew", "");
        if (workerCrewName.length() > 48) workerCrewName = "";
        pendingJoinCrew=tag.getStringOr("pendingJoinCrew","");pendingJoinJob=tag.getStringOr("pendingJoinJob","");
        try { if(!pendingJoinJob.isEmpty())UUID.fromString(pendingJoinJob); } catch(IllegalArgumentException e){pendingJoinCrew="";pendingJoinJob="";}
        presets.clear();
        crewKeys.clear(); crewLabels.clear(); coordinators.clear();
        Set<String> usedKeys = new HashSet<>();
        usedKeys.add(crewKey.get());
        for (Tag item : tag.getListOrEmpty("crews")) {
            if (!(item instanceof CompoundTag value) || presets.size() >= 32) continue;
            try {
                Set<UUID> workers = new LinkedHashSet<>();
                for (Tag id : value.getListOrEmpty("workers")) workers.add(UUID.fromString(id.asString().orElseThrow()));
                CrewPreset preset = checkedPreset(value.getStringOr("name", ""), value.getIntOr("length", 128), workers);
                presets.put(preset.name(), preset);
                crewLabels.put(preset.name(), BotJobs.name(value.getStringOr("label", preset.name())));
                if (!preset.name().equals("Default")) {
                    String key = value.getStringOr("key", "");
                    if (key.length() < 24 || key.length() > 128 || usedKeys.contains(key)) {
                        key = UUID.randomUUID().toString().replace("-", "");
                        MonocleClient.LOG.warn("Replaced invalid/duplicate saved crew key; copy the corrected key to that crew's workers.");
                    }
                    usedKeys.add(key);
                    crewKeys.put(preset.name(), key);
                }
            } catch (RuntimeException ignored) { MonocleClient.LOG.warn("Skipped malformed saved Workers crew"); }
        }
        ensureDefaultCrew(); refreshCredentials();
        selectedCrew = tag.getStringOr("selectedCrew", "Default");
        if (!presets.containsKey(selectedCrew)) selectedCrew = presets.keySet().iterator().next();
        settingsLoaded = true;
        if (tag.getBooleanOr("active", false)) enable();
        return this;
    }
    private boolean hasPersistedCrewWork() {
        if (crew.assigned() || crew.inspect().recovery() || crew.pendingEndCount() > 0) return true;
        if (mode.get() != Mode.Host) return false;
        catalog.load();
        return catalog.records.values().stream().anyMatch(value -> !BotJobs.text(value, "crew").isEmpty())
            || presets.keySet().stream().map(this::coordinator).anyMatch(c -> c.inspect().recovery() || c.pendingEndCount() > 0);
    }
    @Override public void load(File folder) {
        File target = folder == null ? getFile() : new File(folder, getFile().getName());
        if (target.exists()) { super.load(folder); return; }
        // One-time migration; old module/recovery files are left intact for recovery.
        File legacy = new File(folder == null ? MonocleClient.FOLDER : folder, "modules.nbt");
        try {
            if (!legacy.exists()) return;
            CompoundTag old = legacySettings(NbtIo.read(legacy.toPath()));
            if (old == null) return;
            fromTag(old); save(folder);
            info("Connection settings moved to Right Shift → Workers.");
        } catch (Exception e) { MonocleClient.LOG.warn("Could not migrate old bot connection settings", e); }
    }
    public static CompoundTag legacySettings(CompoundTag modules) {
        if (modules == null) return null;
        for (Tag item : modules.getListOrEmpty("modules")) {
            if (item instanceof CompoundTag module && module.getStringOr("name", "").equals("swarm")) {
                CompoundTag migrated = new CompoundTag();
                migrated.put("settings", module.getCompoundOrEmpty("settings").copy());
                migrated.putBoolean("active", module.getBooleanOr("active", false));
                return migrated;
            }
        }
        return null;
    }

    public void close() {
        hostAutoTpy = false;
        tasks.disconnected();
        if (mode.get() == Mode.Host) try { syncJobs(); if (catalogDirty) { catalog.save(); catalogDirty = false; } }
        catch (RuntimeException e) { MonocleClient.LOG.error("Could not checkpoint Workers jobs while closing", e); }
        crew.disconnected();
        for (SwarmCrew coordinator : coordinators.values()) if (coordinator != crew) coordinator.disconnected();
        try {
            if (host != null) {
                host.disconnect();
                host = null;
            }
            if (worker != null) {
                worker.disconnect();
                worker = null;
            }
        } catch (Exception _) {
        }
        workerWasConnected = false;
        retryAt = 0;
        attempts = 0;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        crew.disconnected();
        for (SwarmCrew coordinator : coordinators.values()) if (coordinator != crew) coordinator.disconnected();
        if (mode.get() == Mode.Worker) close();
    }

    @EventHandler
    private void onGameJoin(GameJoinedEvent event) {
        crew.disconnected();
        for (SwarmCrew coordinator : coordinators.values()) if (coordinator != crew) coordinator.disconnected();
        if (mode.get() == Mode.Worker) close();
    }

    public boolean isHost() {
        return mode.get() == Mode.Host && host != null && host.listening();
    }

    public boolean isWorker() {
        return mode.get() == Mode.Worker && worker != null && worker.connected();
    }

    @EventHandler
    private void onPreTick(TickEvent.Pre event) { if (active && crew.localAssigned()) crew.position(); }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        try { expireJobHistory(); } catch (RuntimeException e) { reportConnectionFailure("Job history cleanup deferred: " + e.getMessage()); }
        if (!active) { try { tasks.tick(); } catch (RuntimeException e) { reportConnectionFailure(e.getMessage()); } return; }
        tickConnection();
        if (mode.get() == Mode.Host) {
            for (var connection : allConnections()) if (!connection.failure().isEmpty()) reportConnectionFailure(connection.failure());
            for (String name : List.copyOf(presets.keySet())) tickCrew(coordinator(name));
            if (++catalogTicks % 20 == 0) try { syncJobs(); }
            catch (RuntimeException e) { reportConnectionFailure(e.getMessage()); }
        } else tickCrew(crew);
        try { tasks.tick(); }
        catch (RuntimeException e) { tasks.disconnected(); reportConnectionFailure("Task execution stopped: " + e.getMessage()); }
        if (isWorker() && dev.monocle.client.utils.Utils.canUpdate()) worker.tick();
    }
    private void tickCrew(SwarmCrew coordinator) {
        try { coordinator.tick(); }
        catch (RuntimeException e) {
            MonocleClient.LOG.error("Workers crew controller failed", e);
            coordinator.controllerFailed();
            reportConnectionFailure("Crew controller stopped: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    public void connectWorker(String ip, int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid Workers port");
        if (mode.get() != Mode.Worker && hasJobs()) throw new IllegalStateException("End host jobs before switching to Worker.");
        close();
        mode.set(Mode.Worker); ipAddress.set(ip); serverPort.set(port);
        connectionConfig = null;
        enable();
        tickConnection();
    }

    public void startHost() {
        if (mode.get() != Mode.Host) return;
        if (crewRole != Mode.Host && hasJobs()) throw new IllegalStateException("End the worker job before switching to Host.");
        enable();
        tickConnection(); // Apply a role change before opening the listener, not one tick afterward.
        close();
        connectionConfig = currentConfig();
        refreshCredentials();
        host = new SwarmHost(serverPort.get());
        connectionDetail = host.listening() ? "Listening at " + bindAddress.get() + ":" + serverPort.get() : "Host failed to start — check the key, bind address and port";
        if (host.listening()) info("Workers host listening at %s:%d", bindAddress.get(), serverPort.get());
    }

    private ConnectionConfig currentConfig() { return new ConnectionConfig(mode.get(), ipAddress.get().trim(), serverPort.get(), crewKey.get(), bindAddress.get().trim(), webPort.get()); }
    private String workerEndpoint() { return ipAddress.get().contains("://") ? ipAddress.get() : ipAddress.get() + ":" + serverPort.get(); }
    public static int retrySeconds(int failedAttempts) { return Math.min(10, 1 << Math.clamp(failedAttempts - 1, 0, 4)); }

    private void tickConnection() {
        if (!isActive()) return;
        if (crewRole != mode.get()) {
            if (hasJobs()) {
                mode.set(crewRole);
                warning("End all assigned jobs before changing this account's role.");
                return;
            }
            close();
            crew = new SwarmCrew(this); // Worker connections must not reuse a named host coordinator.
            crewRole = mode.get();
        }
        ConnectionConfig config = currentConfig();
        if (!config.equals(connectionConfig)) {
            close(); connectionConfig = config;
            connectionDetail = config.mode() == Mode.Host ? "Press Start host to listen" : "Connecting";
        }
        if (mode.get() != Mode.Worker) return;
        long now = System.nanoTime();
        if (crewKey.get().length() < 24) {
            connectionDetail = "Paste the host's crew key (at least 24 characters)";
            reportConnectionFailure(connectionDetail);
            return;
        }
        if (worker != null && worker.connected()) {
            if (!workerWasConnected) info("Connected to Workers host at %s", workerEndpoint());
            workerWasConnected = true; attempts = 0;
            connectionDetail = crew.assigned()
                ? crew.canResume() ? "Authenticated; crew assigned" : "Authenticated; interrupted crew job requires inspection"
                : "Authenticated" + (workerCrewName.isBlank() ? "" : " · " + workerCrewName) + "; " + (acceptCrew.get() ? "waiting for a crew assignment" : "highway assignments disabled");
            sendPendingJoin();
            return;
        }
        if (worker != null && worker.isAlive()) return; // One DNS/connect/handshake attempt at a time.
        if (worker != null) {
            crew.disconnected();
            connectionDetail = worker.failure().isEmpty() ? "Connection lost" : worker.failure();
            reportConnectionFailure(connectionDetail);
            worker.disconnect(); worker = null; workerWasConnected = false;
            retryAt = now + retrySeconds(Math.max(1, attempts)) * 1_000_000_000L;
        }
        if (now < retryAt) return;
        attempts = Math.min(100, attempts + 1);
        try { worker = new SwarmWorker(config.ip(), config.port(), config.key()); }
        catch (IllegalArgumentException e) {
            connectionDetail = e.getMessage(); reportConnectionFailure(connectionDetail);
            retryAt = now + retrySeconds(attempts) * 1_000_000_000L;
        }
    }

    public void reportConnectionFailure(String reason) {
        long now = System.nanoTime();
        if (!reason.equals(lastNotice) || now >= noticeAt) {
            warning("Workers: %s", reason);
            lastNotice = reason; noticeAt = now + 30_000_000_000L;
        }
    }

    public String connectionStatus() {
        if (!isActive()) return "Workers disabled";
        if (mode.get() == Mode.Host) return isHost() ? "Host listening · " + host.getConnectionCount() + " authenticated worker(s)" : "Host stopped";
        String endpoint = workerEndpoint();
        if (isWorker()) return "Connected · " + endpoint;
        if (crewKey.get().length() < 24) return "Worker needs host key · " + endpoint;
        if (worker != null && worker.isAlive()) return "Connecting / authenticating · " + endpoint;
        long seconds = Math.max(0, (retryAt - System.nanoTime() + 999_999_999L) / 1_000_000_000L);
        return "Retrying " + endpoint + " in " + seconds + "s";
    }

    public boolean sendToHost(JsonObject message) {
        return isWorker() && worker.send(message.toString());
    }

    @EventHandler
    private void onSend(dev.monocle.client.events.packets.PacketEvent.Send event) { if (active) crew.guard(event); }

    public enum Mode {
        Host,
        Worker
    }
}
