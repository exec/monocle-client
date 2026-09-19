package dev.monocle.client.systems.modules.misc.swarm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.monocle.coordinator.RowVerification;
import dev.monocle.coordinator.HighwayJobs;
import dev.monocle.coordinator.ResourceLedger;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.commands.Commands;
import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.gui.WidgetScreen;
import dev.monocle.client.systems.bots.Bots;
import dev.monocle.client.systems.bots.BotWorkflows;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.player.AutoEat;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.player.InvUtils;
import dev.monocle.client.utils.player.Rotations;
import dev.monocle.client.utils.player.CustomPlayerInput;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.nio.file.*;
import java.util.*;

/** Main-thread coordinator. The builder remains the sole owner of local building/recovery logic. */
public final class SwarmCrew extends dev.monocle.coordinator.HighwayCoordinator<BlockPos> {

    public boolean breakOrder() { return localAssigned() && workSharing(assignment.getAsJsonObject("layout")) == WorkSharing.BreakOrder; }

    private static final Gson JSON = new Gson();
    private static final int WORK_WINDOW = RowVerification.WINDOW;
    private static final int SUPPLY_SPACING = 3;
    private final Bots swarm;
    private final String crewName;
    private final Minecraft mc = Minecraft.getInstance();
    private WorkOwners cachedOwners;
    private List<BlockPos> lastMining = List.of();
    private ItemStack borrowed = ItemStack.EMPTY;
    private UUID borrowedDrop, returnOwner;
    private boolean returnAuthorized, thrown;
    private boolean sendingReturn;
    private int facingTicks;
    private JsonObject pendingReturn;
    private final Set<SwarmConnection> reportedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
    private CustomPlayerInput positioningInput;
    private ClientInput previousInput;
    private LocalPlayer positioningPlayer;
    private String positioningDetail = "";
    private String lastScreenFreeDetail = "Not sampled yet";
    private JsonObject savedRecovery;
    private boolean recoveryLoaded, legacyRecovery;
    // Only unacknowledged cancellations persist; a reconnect must not resurrect an ended job.
    private final ResourcePool resourcePool = new ResourcePool();

    public CrewInventory.Policy inventoryPolicy() { return assigned() ? CrewInventory.Policy.read(assignment.getAsJsonObject("layout")) : CrewInventory.Policy.defaults(); }
    public float trashYaw(float forward) {
        return CrewInventory.trashYaw(forward);
    }
    public void requestCrewResource(int resource) {
        requestCrewResource(resource, -1);
    }
    private void requestCrewResource(int resource, int target) {
        CrewInventory.name(resource);
        if (!localAssigned() || !inventoryPolicy().enabled()) return;
        if (resourcePool.localOffer != null && !Set.of("complete", "cancelled", "failed").contains(resourcePool.localStage)) resourcePool.failLocal("Donor cannot retrieve the promised resource");
        resourcePool.need = resource; resourcePool.priorNeed = resource;
        resourcePool.needTarget = target;
        resourcePool.ownSupplySnapshot = resourcePool.carriedContainers();
        releaseSupply();
        serviceReturning = false;
    }
    public boolean tickInventoryExchange(HighwayBuilder builder) { return resourcePool.tickLocal(builder); }
    public boolean sharingSupply() { return resourcePool.shared() && resourcePool.donor(); }
    public boolean visitingSupply(BlockPos position) {
        return resourcePool.shared() && !resourcePool.donor() && resourcePool.localOffer.has("container")
            && position.equals(resourcePool.sharedPosition());
    }
    public boolean startupStockOwner() { return assigned() && me().toString().equals(str(assignment, "initialStockOwner")); }
    public boolean holdStartupChest() { return startupStockOwner() && (!assignment.has("initialStockScanned") || !assignment.get("initialStockScanned").getAsBoolean()); }
    public BlockPos startupChest() {
        if (!assigned() || startupStockOwner() || !assignment.has("initialStockOwner")) return null;
        String owner = str(assignment, "initialStockOwner");
        JsonArray sites = supplyContainers.has(owner) ? supplyContainers.getAsJsonArray(owner) : null;
        if (sites == null || sites.isEmpty()) return null;
        JsonObject site = sites.get(0).getAsJsonObject();
        return new BlockPos(num(site, "x"), num(site, "y"), num(site, "z"));
    }

    public SwarmCrew(Bots swarm) { this(swarm, ""); }
    public SwarmCrew(Bots swarm, String crewName) { this.swarm = swarm; this.crewName = crewName; localParticipant = crewName.isEmpty(); }
    private HighwayBuilder builder() { return Modules.get().get(HighwayBuilder.class); }
    protected UUID me() { return mc.player == null ? mc.getUser().getProfileId() : mc.player.getUUID(); }

    /** Coalesce local server confirmations into the next client-thread report/permission pass. */

    public boolean returningSupply() { return detachedSupply() && serviceReturning; }

    public BlockPos supplyPosition() { return independentSupplies() && detachedSupply() ? serviceSite(me()) : supplyCenter; }

    private BlockPos serviceSite(UUID worker) {
        JsonObject site = suppliers(assignment).getAsJsonObject(worker.toString());
        return new BlockPos(num(site, "x"), num(site, "y"), num(site, "z"));
    }

    WorkOwners workOwners() {
        if (cachedOwners == null || !cachedOwners.matches(assignment)) cachedOwners = new WorkOwners(assignment);
        return cachedOwners;
    }
    /** Ownership depends only on roster, duties and width, never on block state or row parity. */
    static final class WorkOwners {
        final List<UUID> members;
        private final com.google.gson.JsonArray roster;
        private final com.google.gson.JsonElement duties;
        private final String fallbackDuty;
        private final int width;
        private final int[][] owners;
        WorkOwners(JsonObject record) {
            roster = record.getAsJsonArray(record.has("activeMembers") ? "activeMembers" : "members").deepCopy();
            duties = record.has("duties") ? record.get("duties").deepCopy() : null;
            fallbackDuty = defaultDuty(record);
            width = num(record.getAsJsonObject("layout"), "width");
            if (width < 1 || width > 5) throw new IllegalArgumentException("Invalid ownership width");
            members = activeMembers(record);
            owners = new int[2][width];
            for (int kind = 0; kind < 2; kind++) {
                boolean excavation = kind == 0;
                boolean[] capable = new boolean[members.size()];
                for (int i = 0; i < capable.length; i++) capable[i] = dutyAllows(duty(record, members.get(i)), excavation);
                for (int column = 0; column < width; column++)
                    owners[kind][column] = capableOwner(width, members.size(), column, 0, i -> capable[i]);
            }
        }
        boolean matches(JsonObject record) {
            return width == num(record.getAsJsonObject("layout"), "width")
                && roster.equals(record.get(record.has("activeMembers") ? "activeMembers" : "members"))
                && Objects.equals(duties, record.get("duties")) && fallbackDuty.equals(defaultDuty(record));
        }
        int owner(boolean excavation, int column, int row) {
            return owners[excavation ? 0 : 1][Math.clamp(column, 0, width - 1)];
        }
    }

    public boolean doesExcavate() { return !localAssigned() || !away() && !detachedSupply() && dutyAllows(duty(me()), true); }
    public boolean doesPave() { return !localAssigned() || !away() && !detachedSupply() && dutyAllows(duty(me()), false); }
    public boolean activeCoworker(UUID player) {
        if (!localAssigned()) return false;
        List<UUID> active = activeMembers();
        return active.contains(me()) && active.contains(player);
    }
    public boolean ownsSealing(BlockPos pos) {
        if (!localAssigned()) return true;
        // One owner for each liquid plug, even when excavation and paving have different owners.
        return activeMembers().stream().anyMatch(id -> dutyAllows(duty(id), true)) ? owns(pos, true) : ownsPaving(pos);
    }
    public JsonObject workflow() {
        if (!localAssigned()) return null;
        JsonObject overrides = assignment.has("memberWorkflows") ? assignment.getAsJsonObject("memberWorkflows") : null;
        JsonObject selected = overrides != null && overrides.has(me().toString()) ? overrides.getAsJsonObject(me().toString())
            : assignment.has("workflow") ? assignment.getAsJsonObject("workflow") : null;
        return selected == null ? null : selected.deepCopy();
    }
    private String laneLabel() { return away() ? "Off duty · automatic return" : detachedSupply() ? "Detached supply task"
        : breakOrder() ? "Break Order · " + breakOrderName(activeMembers().indexOf(me()))
        : "Lane " + (activeMembers().indexOf(me()) + 1) + "/" + activeMembers().size(); }
    public boolean canResume() { return assigned() && !stopped && live(); }
    public boolean isPausedByHost() { return phase.equals("paused") || regrouping && regroupWasPaused; }
    public String jobId() { return job; }
    public String localStatus() { return phase + " · " + (builder().hasJob() ? builder().crewDiagnostics() + builder().crewSupplyForecast() : positioningDetail); }
    private String resourceFailureReason="";
    public String resourceFailure() { return resourceFailureReason; }
    public void failResources(String reason) {
        if(!localAssigned())return;
        resourceFailureReason=reason;
        builder().pauseJob(reason);builder().disable();clearLocal();phase="failed";swarm.error(reason);
    }
    public boolean live() { return swarm.isActive() && (swarm.isWorker() || swarm.isHost() && connections().stream().anyMatch(SwarmConnection::connected)); }
    protected String scope() { return !Utils.canUpdate() ? "" : (mc.getCurrentServer() == null ? "local" : mc.getCurrentServer().ip) + "\n" + mc.level.dimension().identifier(); }
    private Path namedJournal() { return MonocleClient.FOLDER.toPath().resolve(crewName.isEmpty() ? "swarm-crew-recovery.json" : "bot-crew-" + UUID.nameUUIDFromBytes(crewName.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json"); }
    protected Path journal() { return legacyRecovery ? MonocleClient.FOLDER.toPath().resolve("swarm-crew-recovery.json") : namedJournal(); }
    protected Path endings() { return namedJournal().resolveSibling(namedJournal().getFileName().toString().replace(".json", "-endings.json")); }
    private List<SwarmConnection> connections() { return swarm.connectionsForCrew(crewName); }

    public int pendingEndCount() { loadEndings(); return pendingEnds.values().stream().mapToInt(Set::size).sum(); }
    public boolean hasEndedSupplies(String execution) {
        return !execution.isEmpty() && Files.exists(namedJournal().resolveSibling("bot-ended-" + UUID.fromString(execution) + "-supplies.json"));
    }

    /** Independent job definition: executions and lane generations are deliberately not its identity. */
    public JsonObject jobSnapshot() {
        JsonObject record = assigned() ? assignment : recoveryRecord();
        if (record == null) return null;
        JsonObject result = new JsonObject();
        for (String key : List.of("name", "scope", "x", "y", "z", "length", "layout", "members", "preferredMembers", "preferredWorkflows", "borrowedMembers", "hostMember", "activeMembers", "duties", "workflow", "memberWorkflows", "detachedMember", "suppliers"))
            if (record.has(key)) result.add(key, record.get(key).deepCopy());
        result.addProperty("id", str(record, "catalogId").isEmpty() ? str(record, "job") : str(record, "catalogId"));
        result.addProperty("execution", str(record, "job"));
        result.addProperty("progress", assigned() ? safeProgress() : record.has("progress") ? num(record, "progress") : 0);
        result.addProperty("phase", assigned() ? phase : "inspection required");
        result.addProperty("roadComplete", roadComplete());
        if (!result.has("workflow") && record.getAsJsonObject("layout").has("operation")) result.add("workflow", BotWorkflows.legacyPlan(str(record.getAsJsonObject("layout"), "operation")));
        return result;
    }

    public BlockPos startPosition() {
        var layout = assignment.getAsJsonObject("layout");
        return origin().offset(num(layout, "dx") * startRow(), 0, num(layout, "dz") * startRow());
    }

    public record MemberView(UUID id, String name, String phase, String status, String lane, boolean connected, boolean available) {}
    public record JobView(String name, String phase, String detail, String origin, String supply, String recoveryPath, boolean assigned, boolean recovery, boolean resumable) {}

    public JsonObject recoveryRecord() {
        if (!recoveryLoaded) {
            recoveryLoaded = true;
            try {
                Path old = MonocleClient.FOLDER.toPath().resolve("swarm-crew-recovery.json");
                if (!Files.exists(journal()) && crewName.equals("Default") && Files.exists(old)) {
                    JsonObject record = readRecovery(old);
                    if (legacyHostRecord(record, mc.getUser().getProfileId())) { legacyRecovery = true; savedRecovery = record; }
                }
                if (savedRecovery == null && Files.exists(journal())) savedRecovery = readRecovery(journal());
            }
            catch (Exception e) { recoveryError = "Cannot read the recovery record: " + e.getMessage(); }
        }
        return savedRecovery;
    }

    static JsonObject readRecovery(Path path) throws java.io.IOException {
        JsonObject record = JSON.fromJson(Files.readString(path), JsonObject.class);
        if (record == null) throw new IllegalArgumentException("Empty recovery record");
        UUID.fromString(str(record, "job"));
        num(record, "x"); num(record, "y"); num(record, "z"); num(record, "length");
        JsonObject layout = record.getAsJsonObject("layout");
        num(layout, "dx"); num(layout, "dz"); num(layout, "height");
        anchorColumn(num(layout, "width"), num(record, "count"), num(record, "index"));
        if (record.getAsJsonArray("members").size() != num(record, "count")) throw new IllegalArgumentException("Invalid recovery membership");
        for (var member : record.getAsJsonArray("members")) UUID.fromString(member.getAsString());
        return record;
    }

    static boolean legacyHostRecord(JsonObject record, UUID local) {
        if (record == null) return false;
        if (record.has("host")) return str(record, "host").equals(local.toString());
        // Before named/remote-only crews, lane zero was always the host. Its server UUID can
        // differ from the launcher UUID on offline-mode servers, especially in the main menu.
        return record.has("index") && num(record, "index") == 0 && record.has("members") && !record.getAsJsonArray("members").isEmpty();
    }

    public JobView inspect() {
        JsonObject record = assigned() ? assignment : recoveryRecord();
        try { loadEndings(); } catch (IllegalStateException e) { recoveryError = e.getMessage(); }
        boolean recovery = Files.exists(journal());
        if (record == null) return new JobView(crewName.isEmpty() ? "No active crew" : swarm.crewLabel(crewName), recovery ? "recovery record unreadable" : "idle",
            recovery ? recoveryError + ". Check the saved file before ending this job." : pendingEnds.isEmpty() ? "Choose workers and prepare a highway job." : "Ended job; waiting for " + pendingEnds.values().stream().mapToInt(Set::size).sum() + " worker acknowledgment(s). Offline workers are cleared when they reconnect.",
            "", "", recovery ? journal().toString() : "", false, recovery, false);
        String detail = !assigned() ? "Recovered after restart. Check the saved supply location and recover any containers/drops, then release the assignment. Movement is not restarted automatically."
            : stopped ? "Waiting for same-execution reconnect on the original world. Restarted/mismatched workers require inspection; Resume is available when the roster is safe."
            : regrouping ? (releasing ? "Releasing assignment" : !borrowingWorkers.isEmpty() ? "Handing off workers to another task" : detachingWorker != null ? "Handing off a supply runner's duties" : rejoiningSupply ? "Restoring the returning worker's duties" : "Adding a worker") + ": finishing supply recovery and confirming outstanding work"
            : !localParticipant ? "Coordinating workers · " + checkpointRow + " rows completed · 5-row work window" : !begun ? positioningDetail : builder().getStatus();
        if (assigned() && swarm.isHost()) for (MemberView member : members()) {
            if (!regrouping && (!member.connected() || Set.of("paused", "blocked", "stopped").contains(member.phase()))) { detail = member.name() + ": " + member.status(); break; }
            if (regrouping && participants.containsKey(member.id()) && !member.phase().equals("synchronized")) {
                detail += " · " + member.name() + ": " + member.status() + " (Resume if recovery was paused)"; break;
            }
        }
        String supply = assigned() ? supplyOwner == null ? "No supply reservation" : "Owner " + memberName(supplyOwner) + " · " + supplyCenter.toShortString() + (granted ? " · recovery in progress" : " · waiting for clearance")
            : str(record, "supplyPosition").isBlank() ? "No saved supply reservation; still inspect the road for drops" : "Owner " + str(record, "supplyOwner") + " · " + str(record, "supplyPosition");
        if (independentSupplies()) {
            supply = "Independent restocking · " + detachedMembers(assignment).size() + " detached";
            for (var member : supplyContainers.entrySet()) for (JsonElement element : member.getValue().getAsJsonArray()) {
                JsonObject site = element.getAsJsonObject();
                supply += "\n" + memberName(UUID.fromString(member.getKey())) + " · " + num(site, "x") + ", " + num(site, "y") + ", " + num(site, "z");
            }
        }
        if (assigned() && inventoryPolicy().enabled()) supply += "\n" + resourcePool.summary();
        return new JobView(str(record, "name").isBlank() ? "Highway crew" : str(record, "name"), assigned() ? phase : "inspection required", detail,
            str(record, "x") + ", " + str(record, "y") + ", " + str(record, "z") + " · " + str(record, "length") + " road blocks · " + (assigned() ? safeProgress() : record.has("progress") ? num(record, "progress") : 0) + " completed",
            supply, journal().toString(), assigned(), recovery, assigned() && !phase.equals("complete") && (!stopped || reconnectReady()) && live() && (independentSupplies() || allMembersConnected()));
    }

    protected String memberName(UUID id) {
        for (JsonObject p : peers.values()) if (str(p, "id").equals(id.toString())) return str(p, "name");
        return id.equals(me()) ? mc.getUser().getName() : id.toString();
    }

    public List<MemberView> members() {
        Map<UUID, MemberView> result = new LinkedHashMap<>();
        if (localParticipant || !assigned() && crewName.equals("Default") && !swarm.crew.localAssigned()) result.put(me(), new MemberView(me(), mc.player == null ? mc.getUser().getName() : mc.player.getName().getString(), phase,
            !Utils.canUpdate() ? "Waiting to join Minecraft" : assigned() ? begun ? builder().getStatus() : positioningDetail : Files.exists(journal()) ? "Saved job needs inspection" : "Ready for assignment",
            assigned() ? laneLabel() : "—", swarm.isActive(),
            Utils.canUpdate() && !assigned() && !builder().hasJob() && !Files.exists(journal())));
        for (var entry : peers.entrySet()) {
            JsonObject p = entry.getValue(); UUID id = UUID.fromString(str(p, "id"));
            MemberView member = new MemberView(id, str(p, "name"), str(p, "phase"), str(p, "status"), str(p, "lane"), entry.getKey().connected(),
                entry.getKey().connected() && scope().equals(str(p, "scope")) && p.has("available") && p.get("available").getAsBoolean());
            if (!result.containsKey(id) || member.connected()) result.put(id, member);
        }
        JsonObject record = assigned() ? assignment : recoveryRecord();
        if (record != null && record.has("members")) for (var member : record.getAsJsonArray("members")) {
            UUID id = UUID.fromString(member.getAsString());
            result.putIfAbsent(id, new MemberView(id, id.toString(), "offline", "No connected report; job stays stopped", "—", false, false));
        }
        return List.copyOf(result.values());
    }

    public boolean conflictsWith(BlockPos start, JsonObject layout, int length) {
        JsonObject record = assigned() ? assignment : recoveryRecord();
        if (record == null || !scope().equals(str(record, "scope"))) return false;
        JsonObject candidate=new JsonObject();candidate.addProperty("scope",scope());candidate.addProperty("x",start.getX());candidate.addProperty("y",start.getY());candidate.addProperty("z",start.getZ());
        candidate.add("layout",layout);candidate.addProperty("length",length);
        return dev.monocle.coordinator.HighwayJobs.overlaps(record,candidate);
    }
    static net.minecraft.world.phys.AABB workArea(BlockPos start, JsonObject layout, int length) {
        int dx = num(layout, "dx"), dz = num(layout, "dz"), radius = num(layout, "width") / 2 + 8;
        BlockPos end = start.offset(dx * length, 0, dz * length);
        return new net.minecraft.world.phys.AABB(Math.min(start.getX(), end.getX()) - radius, start.getY() - 10,
            Math.min(start.getZ(), end.getZ()) - radius, Math.max(start.getX(), end.getX()) + radius + 1,
            start.getY() + num(layout, "height") + 10, Math.max(start.getZ(), end.getZ()) + radius + 1);
    }
    public static boolean within(int x, int z, int ox, int oz, int dx, int dz, int length) {
        long along = ((long) x - ox) * dx + ((long) z - oz) * dz;
        return along >= 1 && along <= length;
    }
    public static int anchorColumn(int width, int members, int index) {
        if (members < 1 || members > width || width > 5 || index < 0 || index >= members) throw new IllegalArgumentException("Crew must fit the highway width");
        return (2 * index + 1) * width / (2 * members);
    }
    public static int laneOwner(int width, int members, int column, int row) {
        column = Math.clamp(column, 0, width - 1); // Outside railings/supports belong to the edge worker.
        int owner = 0, distance = Integer.MAX_VALUE;
        for (int i = 0; i < members; i++) {
            int candidate = Math.abs(column - anchorColumn(width, members, i));
            if (candidate < distance) { owner = i; distance = candidate; }
        }
        return owner;
    }
    public BlockPos origin() { return new BlockPos(num(assignment, "x"), num(assignment, "y"), num(assignment, "z")); }
    public BlockPos lanePosition(BlockPos center) {
        var layout = assignment.getAsJsonObject("layout");
        List<UUID> active = activeMembers();
        int index = active.indexOf(me());
        if (index < 0) return center; // Detached supply travel uses its explicitly reserved service site.
        return workPosition(center, num(layout, "dx"), num(layout, "dz"), num(layout, "width"), active.size(), index, workSharing(layout));
    }
    static BlockPos workPosition(BlockPos center, int dx, int dz, int width, int members, int index, WorkSharing sharing) {
        int offset = sharing == WorkSharing.BreakOrder ? 0 : width / 2 - anchorColumn(width, members, index);
        return center.offset(dz * offset, 0, -dx * offset);
    }
    public boolean owns(BlockPos pos) {
        return ownsExcavation(pos) || ownsPaving(pos);
    }
    public boolean ownsExcavation(BlockPos pos) { return breakOrder() ? doesExcavate() && miningPosition(pos) : owns(pos, true); }
    public boolean miningAvailable(BlockPos pos) {
        UUID owner = miningOwners.get(pos);
        return !breakOrder() || owner == null || owner.equals(me()) || System.nanoTime() - lastPermit > 1_000_000_000L;
    }
    public List<BlockPos> excavationOrder(List<BlockPos> targets) {
        var layout = assignment.getAsJsonObject("layout");
        return breakOrderTargets(targets, num(layout, "dx"), num(layout, "dz"), activeMembers().indexOf(me()));
    }
    static String breakOrderName(int index) {
        return switch (Math.floorMod(index, 5)) {
            case 0 -> "Left → Right";
            case 1 -> "Right → Left";
            case 2 -> "Top → Bottom";
            case 3 -> "Bottom → Top";
            default -> "Center → Edges";
        };
    }
    static List<BlockPos> breakOrderTargets(List<BlockPos> targets, int dx, int dz, int index) {
        java.util.function.ToIntFunction<BlockPos> column = pos -> pos.getZ() * dx - pos.getX() * dz;
        Comparator<BlockPos> order = switch (Math.floorMod(index, 5)) {
            case 0 -> Comparator.comparingInt(column).thenComparingInt(BlockPos::getY);
            case 1 -> Comparator.comparingInt(column).reversed().thenComparing(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed());
            case 2 -> Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed().thenComparingInt(column);
            case 3 -> Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparing(Comparator.comparingInt(column).reversed());
            default -> {
                int center = (targets.stream().mapToInt(column).min().orElse(0) + targets.stream().mapToInt(column).max().orElse(0)) / 2;
                yield Comparator.<BlockPos>comparingInt(pos -> Math.abs(column.applyAsInt(pos) - center)).thenComparingInt(BlockPos::getY).thenComparingInt(column);
            }
        };
        return targets.stream().sorted(Comparator.<BlockPos>comparingInt(pos -> pos.getX() * dx + pos.getZ() * dz).thenComparing(order)).toList();
    }

    static List<BlockPos> reportedMining(JsonObject report) {
        if (!report.has("mining")) return List.of();
        var targets = report.getAsJsonArray("mining");
        if (targets.size() > 3) throw new IllegalArgumentException("Too many mining targets");
        return targets.asList().stream().map(value -> BlockPos.of(Long.parseLong(value.getAsString()))).toList();
    }

    static Map<BlockPos, UUID> shareMining(Map<UUID, List<BlockPos>> reports, Map<BlockPos, UUID> previous) {
        Map<BlockPos, UUID> result = new LinkedHashMap<>();
        // Preserve a working miner; simultaneous starts resolve in roster order, without a claim round trip.
        previous.forEach((pos, owner) -> { if (reports.getOrDefault(owner, List.of()).contains(pos)) result.put(pos, owner); });
        reports.forEach((owner, positions) -> positions.forEach(pos -> result.putIfAbsent(pos, owner)));
        return result;
    }

    private boolean miningPosition(BlockPos pos) {
        var layout = assignment.getAsJsonObject("layout");
        int dx = num(layout, "dx"), dz = num(layout, "dz"), width = num(layout, "width");
        int x = pos.getX() - num(assignment, "x"), z = pos.getZ() - num(assignment, "z");
        int column = width / 2 - (x * dz - z * dx), y = pos.getY() - num(assignment, "y");
        return within(pos.getX(), pos.getZ(), num(assignment, "x"), num(assignment, "z"), dx, dz, num(assignment, "length"))
            && column >= -1 && column <= width && y >= -1 && y <= num(layout, "height");
    }
    public boolean ownsPaving(BlockPos pos) { return owns(pos, false); }
    private boolean owns(BlockPos pos, boolean excavation) {
        if (!localAssigned()) return true;
        if (away() || detachedSupply()) return false;
        var layout = assignment.getAsJsonObject("layout");
        int dx = num(layout, "dx"), dz = num(layout, "dz"), width = num(layout, "width");
        int x = pos.getX() - num(assignment, "x"), z = pos.getZ() - num(assignment, "z");
        int row = x * dx + z * dz, column = width / 2 - (x * dz - z * dx);
        WorkOwners work = workOwners();
        int owner = work.owner(excavation, column, row + pos.getY() - num(assignment, "y"));
        return owner >= 0 && work.members.get(owner).equals(me());
    }
    static int capableOwner(int width, int count, int column, int row, java.util.function.IntPredicate capable) {
        column = Math.clamp(column, 0, width - 1);
        int owner = -1, distance = Integer.MAX_VALUE;
        for (int index = 0; index < count; index++) if (capable.test(index)) {
            int candidate = Math.abs(column - anchorColumn(width, count, index));
            if (candidate < distance) { owner = index; distance = candidate; }
        }
        return owner;
    }
    public boolean readyToAdvance(BlockPos nextCenter) {
        if (!localAssigned()) return true;
        var layout = assignment.getAsJsonObject("layout");
        int row = (nextCenter.getX() - num(assignment, "x")) * num(layout, "dx") + (nextCenter.getZ() - num(assignment, "z")) * num(layout, "dz");
        return canResume() && !regrouping && !detachedSupply() && !sharedSupplyHold() && System.nanoTime() - lastPermit <= 1_000_000_000L
            && canAdvance(row, leadLimit, verifiedBase, verifiedMask);
    }
    public boolean trailAuditor() { return localAssigned() && trailAuditor && !breakOrder() && !away() && !detachedSupply(); }
    public String windowStatus() {
        return "reported row " + actualRow + ", verification base " + verifiedBase + ", mask " + verifiedMask + ", limit " + leadLimit
            + (System.nanoTime() - lastPermit > 1_000_000_000L ? ", stale permit" : "");
    }

    public JsonObject localDiagnostics() {
        JsonObject d = new JsonObject(); long now = System.nanoTime();
        d.addProperty("execution", job); d.addProperty("generation", generation); d.addProperty("phase", phase);
        d.addProperty("actualRow", actualRow); d.addProperty("verifiedBase", verifiedBase); d.addProperty("verifiedMask", verifiedMask); d.addProperty("leadLimit", leadLimit);
        d.addProperty("permitAgeMs", lastPermit == 0 ? -1 : (now - lastPermit) / 1_000_000);
        d.addProperty("hostAgeMs", lastHost == 0 ? -1 : (now - lastHost) / 1_000_000);
        d.addProperty("stopped", stopped); d.addProperty("live", live()); d.addProperty("regrouping", regrouping);
        d.addProperty("away", away()); d.addProperty("detached", detachedSupply());
        d.addProperty("returning", serviceReturning); d.addProperty("finished", serviceFinished); d.addProperty("regroupReady", regroupReady);
        d.addProperty("independentSupplies", independentSupplies());
        d.addProperty("serviceFront", String.valueOf(serviceFront)); d.addProperty("serviceFrontAgeTicks", ticks - serviceFrontTick);
        if (assigned()) d.addProperty("serviceRevision", serviceRevision(assignment, me()));
        d.addProperty("sharedSupplyHold", assigned() && sharedSupplyHold()); d.addProperty("granted", granted);
        d.addProperty("supplyOwner", String.valueOf(supplyOwner)); d.addProperty("lock", lock); d.addProperty("ack", acknowledged);
        d.addProperty("pickupCenter", String.valueOf(pickupCenter)); d.addProperty("positioning", positioningDetail);
        return d;
    }

    protected BlockPos rowCenter(int row) {
        var layout = assignment.getAsJsonObject("layout");
        return origin().offset(num(layout, "dx") * row, 0, num(layout, "dz") * row);
    }

    public BlockPos returnRendezvous() {
        if (!assigned()) throw new IllegalStateException("The source highway no longer has an assignment.");
        return rowCenter(safeProgress());
    }
    public boolean canBorrow(Set<UUID> workers) { return borrowBlocker(workers).isEmpty(); }
    public String borrowingReason(Set<UUID> workers) { String reason = borrowBlocker(workers); return reason.isEmpty() ? null : reason; }
    public String borrowBlocker(Set<UUID> workers) {
        if (!swarm.isHost() || !assigned() || stopped || !live() || !allMembersConnected() || !begun || phase.equals("complete")) return "Choose a connected, running highway job.";
        if (regrouping || !borrowingWorkers.isEmpty()) return "Wait for the current membership handoff.";
        if (ticks < regroupRetryAfter) return "Allow the current lanes to make progress before retrying the handoff.";
        if (detachedMember() != null) return "Wait for the detached supply worker to return.";
        for (UUID member : activeMembers()) {
            JsonObject report = currentReport(member);
            if (report == null || !str(assignment, "scope").equals(str(report, "scope")) || !report.has("x")
                || !joinNearby(returnRendezvous(), new BlockPos(num(report, "x"), num(report, "y"), num(report, "z"))))
                return "Every active worker must report a fresh, on-site position before handing off the highway.";
        }
        return borrowingBlocker(assignment, workers, localParticipant ? me() : null);
    }
    public void requestBorrow(Set<UUID> workers) {
        String blocker = borrowBlocker(workers);
        if (!blocker.isEmpty()) throw new IllegalStateException(blocker);
        borrowingWorkers.addAll(workers);
        broadcast(jobMessage("regroup"));
        swarm.info("Safely handing off %d workers; confirming road work and recovering supply containers first.", workers.size());
    }
    public void requestReturn(UUID worker) {
        if (!borrowReady(worker)) throw new IllegalStateException("This worker has no confirmed return reservation.");
        addWorker(worker);
    }
    /** Scheduler may discard a return reservation after source cancellation/completion; never controls the borrowed task. */
    public void releaseReturn(UUID worker) {
        if (swarm.mode.get() != Bots.Mode.Host) throw new IllegalStateException("Only the host can release a return reservation.");
        if (!assigned() || !borrowedMembers().has(worker.toString())) return;
        if (!borrowReady(worker) || worker.equals(joiningWorker)) throw new IllegalStateException("Wait for the worker handoff to finish.");
        var previous = assignment.getAsJsonObject("borrowedMembers").remove(worker.toString());
        try { persist(); }
        catch (RuntimeException e) { assignment.getAsJsonObject("borrowedMembers").add(worker.toString(), previous); throw e; }
    }
    static int returnSlot(net.minecraft.world.Container inventory, ItemStack borrowed) {
        int slot = -1;
        if (borrowed.isEmpty()) return -1;
        for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) if (ItemStack.isSameItemSameComponents(borrowed, inventory.getItem(i))) {
            if (slot != -1 || inventory.getItem(i).getCount() != 1) return -1;
            slot = i;
        }
        return slot;
    }

    private void send(JsonObject m) {
        if (swarm.isHost()) receive(me(), m);
        else if (swarm.isWorker()) swarm.worker.send(JSON.toJson(m));
    }

    public void start(int sectionLength) { start(sectionLength, "Highway crew", Set.of()); }
    public void start(int sectionLength, String name, Set<UUID> selectedWorkers) { start(sectionLength, name, selectedWorkers, true); }
    public void start(int sectionLength, String name, Set<UUID> selectedWorkers, boolean includeHost) {
        JsonObject definition = new JsonObject();
        definition.addProperty("id", UUID.randomUUID().toString()); definition.addProperty("name", name);
        definition.addProperty("length", sectionLength); definition.addProperty("progress", 0);
        definition.addProperty("scope", scope()); definition.add("layout", builder().crewLayout());
        // The old command can still anchor workers-only jobs at the first selected worker.
        startJob(definition, selectedWorkers, includeHost);
    }

    public void startJob(JsonObject definition, Set<UUID> selectedWorkers, boolean includeHost) {
        if (!Utils.canUpdate() || !swarm.isHost() || swarm.host.getConnectionCount() == 0) throw new IllegalStateException("Start a host and connect at least one worker first.");
        recoveryRecord();
        if (assigned() || Files.exists(journal())) throw new IllegalStateException("Open Workers → Job inspection, recover any supplies, then End job before starting another.");
        UUID.fromString(str(definition, "id"));
        if (!scope().equals(str(definition, "scope"))) throw new IllegalStateException("This job belongs to another Minecraft server or dimension.");
        int sectionLength = num(definition, "length"), progress = definition.has("progress") ? num(definition, "progress") : 0;
        String name = str(definition, "name");
        if (sectionLength < 16 || sectionLength > HighwayJobs.MAX_LENGTH) throw new IllegalArgumentException("Shared road length must be 16–100,000.");
        if (progress < 0 || progress >= sectionLength) throw new IllegalArgumentException("Choose an unfinished job with valid saved progress.");
        name = name.strip();
        if (name.isEmpty() || name.length() > 48 || name.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Job name must be 1–48 printable characters.");
        if (includeHost && builder().hasJob()) throw new IllegalStateException("Stop the host's Highway Builder job first, or build with workers only.");
        JsonObject layout = definition.getAsJsonObject("layout");
        builder().validateCrewLayout(layout);
        int dx = num(layout, "dx"), dz = num(layout, "dz");
        if (Math.abs(dx) + Math.abs(dz) != 1) throw new IllegalStateException("The first crew build supports cardinal highways only.");
        Map<UUID, SwarmConnection> selected = new LinkedHashMap<>();
        if (includeHost) selected.put(me(), null);
        for (var entry : peers.entrySet().stream().sorted(Comparator.comparing((Map.Entry<SwarmConnection, JsonObject> e) -> str(e.getValue(), "name"), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(e -> str(e.getValue(), "id"))).toList()) {
            JsonObject p = entry.getValue();
            UUID id = UUID.fromString(str(p, "id"));
            if (!selectedWorkers.isEmpty() && !selectedWorkers.contains(id)) continue;
            if (!entry.getKey().connected() || !scope().equals(str(p, "scope"))) continue;
            if (p.has("recoveryReady") && !p.get("recoveryReady").getAsBoolean()) throw new IllegalStateException(str(p,"name") + " is reconciling workflow supplies before assignment.");
            if (!p.get("available").getAsBoolean() && !swarm.tasks().nativeReady(id)) throw new IllegalStateException(str(p, "name") + " must stop their builder and accept crew assignments first.");
            if (selected.putIfAbsent(id, entry.getKey()) != null || id.equals(me())) throw new IllegalStateException("Duplicate player identity in Workers.");
        }
        if (!selected.keySet().containsAll(selectedWorkers)) throw new IllegalStateException("A selected worker is offline or on another server/dimension. Reconnect them or change the selection.");
        if (selected.size() < (includeHost ? 2 : 1)) throw new IllegalStateException("Wait for a ready worker on the same server and dimension.");
        if (selected.size() > num(layout, "width")) throw new IllegalStateException("The crew cannot have more players than the highway has floor columns.");
        BlockPos origin = definition.has("x") ? new BlockPos(num(definition, "x"), num(definition, "y"), num(definition, "z")) : mc.player.blockPosition();
        if (!definition.has("x") && !includeHost) {
            JsonObject leader = peers.get(selected.values().iterator().next());
            origin = new BlockPos(num(leader, "x"), num(leader, "y"), num(leader, "z"));
        }
        if (Math.abs((long) origin.getX()) > 29_900_000 || Math.abs((long) origin.getZ()) > 29_900_000 || Math.abs((long) origin.getY()) > 2048) throw new IllegalArgumentException("Invalid job origin");
        swarm.validateJobArea(this, origin, layout, sectionLength);
        if (includeHost) swarm.claimLocalCrew(this);
        startPrepared(definition, selected, includeHost, origin);
    }

    /** Admission is explicit: an idle worker never silently changes a running job's ownership. */

    static boolean joinNearby(BlockPos front, BlockPos worker) {
        // 32-block-wide admission cube; the existing walking route deliberately does not climb floors.
        return worker.getY() == front.getY() && Math.abs((long) worker.getX() - front.getX()) <= 16 && Math.abs((long) worker.getZ() - front.getZ()) <= 16;
    }
    static boolean supplyRendezvousReady(BlockPos front, BlockPos worker) {
        return front.getY() == worker.getY() && front.distSqr(worker) <= 4;
    }

    static boolean serviceLandingReady(BlockPos target, Vec3 feet) {
        return target != null && feet.distanceToSqr(Vec3.atBottomCenterOf(target)) <= 1;
    }

    private record RenderedCrew(UUID member, BlockPos target) {}

    private int serviceFrontTick = -100;
    private boolean trailAuditor;
    private BlockPos returnDestination(RenderedCrew visible) {
        if (!localAssigned()) return null;
        return supplyReturnTarget(visible == null ? null : visible.target,
            serviceFront == null ? null : returningLane(assignment, me(), serviceFront), ticks - serviceFrontTick);
    }

    static BlockPos supplyReturnTarget(BlockPos visible, BlockPos hostFront, int age) {
        // Supply sites describe where to restock, never where to resume work.
        return visible != null ? visible : age >= 0 && age <= 60 ? hostFront : null;
    }

    public String supplyReturnStatus() {
        if (resourcePool.localBusy()) return "Supply return waiting for inventory task: " + resourcePool.detail;
        if (returnDestination(renderedCrew()) == null) return "Supply return waiting for a fresh job-front position from host";
        return "Supply recovery complete; waiting for the host to restore this worker's duties";
    }

    /** Entity tracking, not the tab list, camera direction, a configured radius or host telemetry. */
    private RenderedCrew renderedCrew() {
        if (!localAssigned() || !Utils.canUpdate() || !scope().equals(str(assignment, "scope"))) return null;
        var layout = assignment.getAsJsonObject("layout");
        int dx = num(layout, "dx"), dz = num(layout, "dz");
        BlockPos origin = origin();
        RenderedCrew best = null;
        for (UUID member : activeMembers()) {
            if (member.equals(me()) || awayMembers(assignment).has(member.toString())) continue;
            var player = mc.level.getPlayerByUUID(member);
            if (player == null || player.isRemoved() || !player.isAlive()) continue;
            BlockPos target = renderedReturnTarget(origin, dx, dz, num(layout, "width"), num(assignment, "length"), player.position());
            if (target != null && fartherBack(dx, dz, target, best == null ? null : best.target))
                best = new RenderedCrew(member, target);
        }
        if (best == null) return null;
        BlockPos lane = returningLane(assignment, me(), best.target);
        return new RenderedCrew(best.member, returnApproach(origin, dx, dz, num(assignment, "length"), lane, builder()::crewReturnCorridorClear));
    }

    /** A reconnect may trust the live player models only when they are genuinely nearby. */
    public BlockPos nearbyCrewCenter(int radius) {
        RenderedCrew visible = renderedCrew();
        if (visible == null || mc.player.position().distanceToSqr(Vec3.atBottomCenterOf(visible.target)) > (double) radius * radius) return null;
        var layout = assignment.getAsJsonObject("layout");
        BlockPos origin = origin();
        int row = (visible.target.getX() - origin.getX()) * num(layout, "dx") + (visible.target.getZ() - origin.getZ()) * num(layout, "dz");
        return origin.offset(num(layout, "dx") * row, 0, num(layout, "dz") * row);
    }

    /** Clear a disconnect stop only after same-scope, nearby crew evidence. */
    public boolean resumeNearbyReconnect() {
        if (!stopped) return true;
        if (!reconnectReady() || nearbyCrewCenter(32) == null) return false;
        stopped = false;
        pausedBeforeDisconnect = false;
        lastPermit = 0;
        phase = begun ? "building" : "positioning";
        persist();
        return true;
    }

    static BlockPos returnApproach(BlockPos origin, int dx, int dz, int length, BlockPos lane,
                                   java.util.function.BiPredicate<BlockPos, BlockPos> clear) {
        int row = (lane.getX() - origin.getX()) * dx + (lane.getZ() - origin.getZ()) * dz;
        // Only overshoot a live crew on verified road, never the job end or an uncleared excavation face.
        for (int extra = Math.min(2, length - row); extra > 0; extra--) {
            BlockPos ahead = lane.offset(dx * extra, 0, dz * extra);
            if (clear.test(lane, ahead)) return ahead;
        }
        return lane;
    }

    static BlockPos returningLane(JsonObject assignment, UUID supplier, BlockPos center) {
        List<UUID> active = activeMembers(assignment);
        List<UUID> returning = assignment.getAsJsonArray("members").asList().stream().map(id -> UUID.fromString(id.getAsString()))
            .filter(id -> active.contains(id) || id.equals(supplier)).toList();
        var layout = assignment.getAsJsonObject("layout");
        return workPosition(center, num(layout, "dx"), num(layout, "dz"), num(layout, "width"), returning.size(), returning.indexOf(supplier), workSharing(layout));
    }

    static BlockPos renderedReturnTarget(BlockPos origin, int dx, int dz, int width, int length, Vec3 model) {
        // Follow the on-road crew, not a specialist who has left for another task.
        double x = model.x - origin.getX() - .5, z = model.z - origin.getZ() - .5;
        double row = x * dx + z * dz, side = x * dz - z * dx;
        if (!Double.isFinite(row) || !Double.isFinite(side) || !Double.isFinite(model.y)
            || Math.abs(model.y - origin.getY()) > 2 || Math.abs(side) > width / 2.0 + 1
            || row < -.5 || row > length + .5) return null;
        // Join their current row, never the uncleared next row. Flight still checks actual clearance/footing.
        return origin.offset(dx * Math.max(0, (int) Math.floor(row)), 0, dz * Math.max(0, (int) Math.floor(row)));
    }

    static boolean fartherBack(int dx, int dz, BlockPos candidate, BlockPos current) {
        return current == null || (candidate.getX() - current.getX()) * dx + (candidate.getZ() - current.getZ()) * dz < 0;
    }

    static boolean serviceReturnReady(JsonObject assignment, UUID supplier, JsonObject report, BlockPos fallback) {
        if (!report.has("serviceReady") || !report.get("serviceReady").getAsBoolean()) return false;
        return validRenderedReturn(assignment, supplier, report) || activeMembers(assignment).isEmpty()
            && report.has("x") && report.has("y") && report.has("z")
            && supplyRendezvousReady(fallback, new BlockPos(num(report, "x"), num(report, "y"), num(report, "z")));
    }

    /** Releasing preserves the caller's catalog definition; this execution alone is canceled. */

    public void status() {
        JobView view = inspect();
        swarm.info("%s · %s · %s", view.name(), view.phase(), view.detail());
        if (!view.origin().isBlank()) swarm.info("%s · %s", view.origin(), view.supply());
        for (MemberView member : members()) swarm.info("%s · %s · %s", member.name(), member.lane(), member.status());
    }

    public void leave() {
        if (swarm.isHost() && assigned()) broadcast(jobMessage("pause"));
        clearLocal();
        swarm.warning("Left the crew locally after inspection. Use End job on its host to clear the remaining members.");
    }

    /** Ends work independently of cleanup; unresolved supplies are durably archived for inspection. */

    protected void clearLocal() {
        resourcePool.close();
        archiveInterruptedSupplies();
        releasePositioning();
        if (localParticipant) {
            if (builder().crewAssigned()) builder().stopJob();
            builder().endCrew();
        }
        // Do not report successful cleanup or acknowledge the host while the disk still retains this job.
        try { Files.deleteIfExists(journal()); } catch (Exception e) { throw new IllegalStateException("Could not clear crew recovery record: " + e.getMessage()); }
        assignment = null; cachedOwners = null; job = ""; phase = "idle"; stopped = begun = granted = backstepped = false;
        cachedRoster = null; cachedMembers = List.of();
        regrouping = regroupReady = regroupWasPaused = releasing = serviceReturning = serviceFinished = rejoiningSupply = false; joiningWorker = detachingWorker = null; serviceFront = null; generation = 0;
        supplyOwner = null; supplyCenter = null; lock = acknowledged = ""; supplyContainers = new JsonObject();
        pickupCenter = null;
        releaseStarted = 0;
        retryingSupply = false;
        supplyHandoff = false; rejoiningWorker = null;
        supplyRetryAfter = 0;
        availabilityChange = null;
        offRangeArmed.clear();
        participants.clear(); reports.clear(); requested.clear(); acknowledgments.clear();
        borrowingWorkers.clear();
        borrowed = ItemStack.EMPTY; borrowedDrop = returnOwner = null; returnAuthorized = thrown = false;
        pendingReturn = null; facingTicks = 0;
        resetWindow(0);
        savedRecovery = null; recoveryLoaded = true; recoveryError = ""; legacyRecovery = false;
        localParticipant = crewName.isEmpty();
    }

    private void archiveInterruptedSupplies() {
        JsonObject record = assigned() ? assignment.deepCopy() : recoveryRecord();
        if (record == null) return;
        if (assigned()) {
            record.addProperty("supplyOwner", supplyOwner == null ? "" : supplyOwner.toString());
            record.addProperty("supplyPosition", supplyCenter == null ? "" : supplyCenter.toShortString());
            record.add("supplyContainers", supplyContainers.deepCopy());
            record.addProperty("progress", safeProgress());
        }
        boolean localCleanup = localParticipant && builder().crewAssigned() && (!Utils.canUpdate() || builder().crewNeedsCleanup());
        if (str(record, "supplyOwner").isEmpty() && !localCleanup
            && (!record.has("supplyContainers") || record.getAsJsonObject("supplyContainers").isEmpty())) return;
        if (localCleanup) {
            record.addProperty("cleanupStatus", builder().crewDiagnostics());
            if (Utils.canUpdate()) record.addProperty("lastPlayerPosition", mc.player.blockPosition().toShortString());
        }
        if (pickupCenter != null) record.addProperty("pickupPosition", pickupCenter.toShortString());
        // A disconnected/explicitly ended execution cannot recover supplies now. Keep its location
        // independently of the active assignment instead of deleting the only recovery record.
        UUID id = UUID.fromString(str(record, "job"));
        Path archive = namedJournal().resolveSibling("bot-ended-" + id + "-supplies.json");
        try { writeRecord(archive, record); }
        catch (java.io.IOException e) { throw new IllegalStateException("Could not preserve ended job's supply recovery: " + e.getMessage(), e); }
        swarm.warning("Ended job has supplies requiring inspection at %s. Recovery record: %s", str(record, "supplyPosition"), archive);
    }

    protected void persist() {
        try {
            var record = assignment.deepCopy();
            record.addProperty("supplyOwner", supplyOwner == null ? "" : supplyOwner.toString());
            record.addProperty("supplyPosition", supplyCenter == null ? "" : supplyCenter.toShortString());
            record.add("supplyContainers", supplyContainers.deepCopy());
            record.addProperty("progress", safeProgress());
            writeRecord(journal(), record);
            savedRecovery = record; recoveryLoaded = true; recoveryError = "";
        } catch (Exception e) { disconnected(); throw new IllegalStateException("Cannot save crew recovery record: " + e.getMessage()); }
    }

    public void disconnected() {
        releasePositioning();
        if (!assigned() || stopped) return;
        pausedBeforeDisconnect = phase.equals("paused") || localParticipant && builder().wasPausedBeforeWorldChange();
        stopped = true;
        lastPermit = 0;
        verifiedMask = 0;
        phase = "waiting for world / crew connection";
        if (localParticipant && builder().crewAssigned()) builder().pauseForCrewConnection();
    }

    public void controllerFailed() {
        disconnected();
        // Connected sockets cannot repair a programming error. Explicit Resume/End
        // stays available, but automatic transport recovery must not replay the crash.
        pausedBeforeDisconnect = true;
        phase = "controller error / inspect job";
        if (localParticipant && assigned() && builder().crewAssigned()) builder().pauseJob("Crew controller error. See the log; use host Resume to retry or End job.");
    }

    public void tick() {
        ticks++;
        int previousRow = actualRow;
        String previousPhase = phase;
        if (swarm.host != null) for (var c : connections()) drain(c, true);
        if (crewName.isEmpty() && swarm.worker != null) drain(swarm.worker, false);
        if (!resourceFailure().isEmpty()) { phase="failed";return; }
        // Cancellation must survive pauses, missing players and cleanup deadlocks. The existing
        // END receipt is persisted/retried across reconnects; it never claims a container was recovered.
        hostReleaseWatchdog();
        if (!Utils.canUpdate() && localParticipant) {
            if (assigned()) disconnected();
            if (ticks % 10 == 0) announce();
            recordTelemetry();
            return;
        }
        if (assigned() && (!live() || localParticipant && !scope().equals(str(assignment, "scope")))) disconnected();
        if (swarm.isWorker() && assigned() && System.nanoTime() - lastHost > 5_000_000_000L) disconnected();
        hostConnectionMaintenance();
        if (assigned() && !stopped && localParticipant) {
            if (!away() && !builder().crewToggledOff()) actualRow = detachedSupply() ? Math.max(0, (mc.player.getBlockX() - num(assignment, "x")) * num(assignment.getAsJsonObject("layout"), "dx")
                + (mc.player.getBlockZ() - num(assignment, "z")) * num(assignment.getAsJsonObject("layout"), "dz"))
                : begun ? Math.clamp(builder().crewCurrentRow(), startRow(), num(assignment, "length")) : startRow();
            if (regrouping && !phase.equals("paused")) {
                // An unrelated runner keeps its supply FSM/lock while road lanes change.
                regroupReady = supplyHandoff && detachedSupply() && !me().equals(rejoiningWorker)
                    || (supplyHandoff || supplyOwner == null) && borrowed.isEmpty() && pendingReturn == null
                    && (releasing ? builder().crewReleaseReady() : builder().crewReconfigureReady());
                phase = regroupReady ? "synchronized" : "regrouping";
            } else if (away() && !phase.equals("paused")) {
                phase = "away";
                RenderedCrew visible = renderedCrew();
                if (!builder().crewToggledOff() && visible != null) builder().crewTravelDutyReturn(visible.target);
            } else if (detachedSupply() && !phase.equals("paused")) {
                if (serviceReturning && !builder().crewRestockIdle()) {
                    serviceReturning = false;
                    builder().crewCancelTravel(); // Native recovery owns movement until its task is finished.
                }
                phase = serviceFinished ? "complete" : serviceReturning ? "returning from supplies" : "resupplying";
                if (serviceReturning && !builder().hasJob() && ticks % 20 == 0)
                    begun = builder().beginCrewReturn(assignment.getAsJsonObject("layout"), startPosition(), num(assignment,"length")-startRow());
                if (!serviceFinished && serviceReturning && !resourcePool.localBusy() && ticks >= serviceReadyUntil) {
                    RenderedCrew visible = renderedCrew();
                    BlockPos destination = returnDestination(visible);
                    if (destination != null) builder().crewTravelRejoin(destination);
                    else builder().crewCancelTravel();
                }
            } else if (!begun && !phase.equals("paused")) {
                Vec3 target = Vec3.atBottomCenterOf(lanePosition(startPosition()));
                String blocker = positioningBlocker();
                boolean ready = blocker == null && laneReady(mc.player.position(), target, true);
                phase = ready ? "ready" : "positioning";
                if (blocker != null) positioningDetail = blocker;
                else if (ready) positioningDetail = "Centered; waiting for all crew members to report ready";
                if (mc.gui.screen() == null) lastScreenFreeDetail = positioningDetail;
                if (ticks % 100 == 0) MonocleClient.LOG.info("Crew positioning: phase={}, reason={}, lastScreenFree={}, player={}, target={}, grounded={}, input={}",
                    phase, positioningDetail, lastScreenFreeDetail, mc.player.position(), target, mc.player.onGround(), mc.player.input.getClass().getSimpleName());
            } else if (begun && !phase.equals("paused")) phase = workPhase(builder().hasJob(), builder().isJobPaused(), builder().getStatus());
            if (!phase.equals("paused")) tickReturn();
            if (hold() && (!builder().hasJob() || builder().isJobPaused())) holdStep(builder());
        }
        List<BlockPos> mining = breakOrder() && !detachedSupply() && !away() && Utils.canUpdate()
            ? builder().crewMiningTargets() : List.of();
        boolean changed = workDirty || previousRow != actualRow || !previousPhase.equals(phase) || !lastMining.equals(mining);
        lastMining = mining;
        workDirty = false;
        if (workUpdateDue(ticks, assigned(), changed)) announce();
        hostCoordinate(changed);
        if (assigned() && ticks % 20 == 0 && (savedRecovery == null || !savedRecovery.has("progress") || num(savedRecovery, "progress") != safeProgress())) persist();
    }

    protected static boolean workUpdateDue(int tick, boolean assigned, boolean changed) {
        return tick % (assigned ? 2 : 10) == 0 || assigned && changed;
    }

    static String workPhase(boolean hasJob, boolean paused, String status) {
        return hasJob ? paused ? "blocked" : "building" : status.startsWith("Completed") ? "complete" : "stopped";
    }

    static boolean laneReady(Vec3 player, Vec3 target, boolean grounded) {
        return grounded && player.distanceToSqr(target) < .09;
    }
    static boolean nearLane(Vec3 player, Vec3 target) {
        return Math.abs(player.y - target.y) <= .25 && player.distanceToSqr(target) <= 576;
    }
    static String positioningBlocker(String screen, boolean job, boolean riding, boolean gliding, boolean grounded, boolean foreignInput) {
        if (screen != null) return "Screen open: " + screen;
        if (job) return "A separate Highway Builder job is still present";
        if (riding) return "Player is riding an entity";
        if (gliding) return "Player is elytra-gliding";
        if (!grounded) return "Minecraft reports onGround=false";
        if (foreignInput) return "Another feature owns movement input";
        return null;
    }
    private String positioningBlocker() {
        var screen = mc.gui.screen();
        return positioningBlocker(positioningScreenAllowed(screen == null ? null : screen.getClass()) ? null : screen.getClass().getSimpleName(), builder().hasJob(),
            mc.player.isPassenger(), mc.player.isFallFlying(), mc.player.onGround(),
            mc.player.input instanceof CustomPlayerInput && mc.player.input != positioningInput);
    }
    static boolean positioningScreenAllowed(Class<?> screen) {
        return screen == null || ChatScreen.class.isAssignableFrom(screen) || PauseScreen.class.isAssignableFrom(screen) || WidgetScreen.class.isAssignableFrom(screen);
    }
    private void releasePositioning() {
        if (positioningInput != null) {
            positioningInput.stop();
            if (positioningPlayer.input == positioningInput) positioningPlayer.input = previousInput == null || previousInput instanceof CustomPlayerInput
                ? new net.minecraft.client.player.KeyboardInput(mc.options) : previousInput;
        }
        positioningInput = null; previousInput = null; positioningPlayer = null;
    }
    /** Runs before player movement, even though Highway Builder has not started yet. */
    public void position() {
        if (detachedSupply() || away()) { releasePositioning(); return; }
        if (localAssigned() && begun && !stopped && live() && Utils.canUpdate() && scope().equals(str(assignment, "scope"))
            && !phase.equals("paused") && supplyOwner != null && !supplyOwner.equals(me()) && !builder().hasJob()
            && builder().getStatus().startsWith("Completed")) {
            positionFinishedYield(); return;
        }
        if (!localAssigned() || begun || regrouping || stopped || !live() || !Utils.canUpdate() || !scope().equals(str(assignment, "scope")) || phase.equals("paused")) {
            releasePositioning(); return;
        }
        Vec3 target = Vec3.atBottomCenterOf(lanePosition(startPosition()));
        positioningDetail = "Lane start " + lanePosition(startPosition()).toShortString();
        String blocker = positioningBlocker();
        if (blocker != null) {
            releasePositioning(); positioningDetail += " · " + blocker; return;
        }
        if (!nearLane(mc.player.position(), target)) {
            releasePositioning(); positioningDetail += " · move within 24 blocks on the same level"; return;
        }
        if (positioningInput != null && (positioningPlayer != mc.player || mc.player.input != positioningInput)) {
            releasePositioning(); phase = "paused";
            swarm.warning("Crew positioning paused: another feature took movement control. Stop it, then resume the crew."); return;
        }
        if (laneReady(mc.player.position(), target, true)) {
            if (positioningInput != null) positioningInput.stop();
            positioningDetail += " · ready; waiting for teammates"; return;
        }
        Vec3 waypoint = builder().crewPositionWaypoint(lanePosition(startPosition()));
        if (waypoint == null) {
            if (positioningInput != null) positioningInput.stop();
            positioningDetail += " · waiting for a clear, supported route"; return;
        }
        walkPositioning(waypoint);
        positioningDetail += " · walking to lane";
    }

    private void positionFinishedYield() {
        BlockPos center = pickupCenter == null ? supplyCenter : pickupCenter;
        if (positioningBlocker() != null || mc.player.position().distanceToSqr(Vec3.atCenterOf(center)) >= 30.25) {
            releasePositioning(); return;
        }
        Vec3 waypoint = builder().crewSupplyYieldWaypoint(center);
        if (waypoint == null) { releasePositioning(); positioningDetail = "Finished lane; waiting for safe supply-clearance footing"; return; }
        walkPositioning(waypoint);
        positioningDetail = "Finished lane; making room for a teammate's supply recovery";
    }

    private void walkPositioning(Vec3 waypoint) {
        if (positioningInput == null) {
            dev.monocle.client.pathing.PathManagers.get().stop();
            if (swarm.worker != null) swarm.worker.target = null;
            positioningPlayer = mc.player; previousInput = mc.player.input;
            mc.player.input = positioningInput = new CustomPlayerInput();
        }
        positioningInput.stop();
        mc.player.setSprinting(false);
        mc.player.setYRot((float) Rotations.getYaw(waypoint));
        positioningInput.forward(true);
    }

    private void announce() {
        if (swarm.host != null) {
            Set<SwarmConnection> current = new HashSet<>(connections());
            peers.keySet().removeIf(c -> !current.contains(c));
            reportedFailures.retainAll(current);
            if (!assigned()) reports.clear();
        }
        JsonObject hello = message("hello");
        hello.addProperty("taskProtocol", 1); // Supports host-declared action capabilities before native execution.
        hello.addProperty("supplyProtocol", 2);
        hello.add("diagnostics", builder().diagnosticSnapshot());
        hello.addProperty("id", me().toString()); hello.addProperty("name", mc.player == null ? mc.getUser().getName() : mc.player.getName().getString());
        hello.addProperty("scope", scope()); hello.addProperty("available", Utils.canUpdate() && swarm.acceptCrew.get() && !swarm.tasks().workerBusy() && !builder().hasJob() && !assigned() && !Files.exists(journal()));
        JsonObject recovered = assigned() ? assignment : recoveryRecord();
        hello.addProperty("assignmentLoaded", assigned());
        hello.addProperty("recoveryReady", swarm.tasks().allowsNative());
        hello.addProperty("job", assigned() ? job : recovered == null ? "" : str(recovered, "job")); hello.addProperty("phase", phase); hello.addProperty("ack", acknowledged);
        hello.addProperty("lane", assigned() ? laneLabel() : "—");
        hello.addProperty("currentRow", actualRow);
        if (breakOrder() && !detachedSupply() && !away() && Utils.canUpdate())
            hello.add("mining", JSON.toJsonTree(builder().crewMiningTargets().stream().map(BlockPos::asLong).toList()));
        if (localAssigned() && !detachedSupply() && begun && Utils.canUpdate() && !hostAuthority()) {
            hello.addProperty("verifiedBase", actualRow + 1);
            hello.addProperty("verifiedMask", resolvedMask(actualRow + 1, num(assignment, "length"), row -> builder().crewRowResolved(rowCenter(row))));
            hello.addProperty("currentResolved", actualRow == startRow() || builder().crewRowResolved(rowCenter(actualRow)));
        }
        hello.addProperty("generation", !assigned() && recovered != null ? num(recovered,"generation") : generation); hello.addProperty("regroupReady", regroupReady);
        hello.addProperty("begun", begun); hello.addProperty("reconnectReady", reconnectReady() || !assigned() && recovered != null && scope().equals(str(recovered,"scope")) && swarm.tasks().workerBusy());
        hello.addProperty("moduleOff", temporaryOffDuty(localAssigned(), builder().crewUnavailable(), Modules.get().get(AutoEat.class).eating));
        hello.addProperty("supplyEta", localAssigned() && Utils.canUpdate() ? builder().crewSupplyEta() : -1);
        if (Utils.canUpdate()) hello.addProperty("renderDistance", Utils.getRenderDistance());
        hello.addProperty("pausedBeforeDisconnect", stopped && pausedBeforeDisconnect);
        hello.addProperty("connectionStopped", stopped);
        hello.addProperty("serviceReturning", serviceReturning);
        hello.addProperty("sharedSupplyProtocol", 1);
        hello.addProperty("initialStockProtocol", 1);
        hello.addProperty("initialStockReady", localAssigned() && begun && (startupStockOwner() ? builder().crewInitialStorageKnown() : builder().crewInitialStockReady()));
        hello.addProperty("resourceExhaustionProtocol", 1);
        hello.addProperty("chatProtocol", 1);
        if(localAssigned()&&Utils.canUpdate()) {JsonObject forecast=builder().roadPrediction();if(forecast!=null)hello.add("roadPrediction",forecast);}
        if (localAssigned() && Utils.canUpdate()) hello.add("supplyContainers", builder().crewSupplyContainers());
        if (localAssigned() && Utils.canUpdate() && inventoryPolicy().enabled()) {
            hello.add("inventory", builder().crewInventoryReport());
            hello.add("exchange", resourcePool.report());
        }
        if (assigned()) hello.add("suppliers", suppliers(assignment).deepCopy());
        if (assigned()) hello.add("serviceRevisions", serviceRevisions(assignment).deepCopy());
        RenderedCrew visible = away() || detachedSupply() && serviceReturning ? renderedCrew() : null;
        hello.addProperty("awayReady", away() && Utils.canUpdate()
            && (visible == null || mc.player.position().distanceToSqr(Vec3.atBottomCenterOf(visible.target)) <= 25)
            && builder().crewDutyRejoinReady());
        if (visible != null) hello.addProperty("renderedCrew", visible.member.toString());
        BlockPos rendezvous = returnDestination(visible);
        boolean serviceReady = Utils.canUpdate() && detachedSupply() && serviceReturning && rendezvous != null
            && (ticks < serviceReadyUntil || serviceLandingReady(rendezvous, mc.player.position()))
            && builder().crewPrepareRejoin();
        if (serviceReady && ticks >= serviceReadyUntil) serviceReadyUntil = ticks + 20;
        hello.addProperty("serviceReady", serviceReady);
        if (Utils.canUpdate()) {
            BlockPos feet = mc.player.blockPosition();
            hello.addProperty("x", feet.getX()); hello.addProperty("y", feet.getY()); hello.addProperty("z", feet.getZ());
        }
        hello.addProperty("positionCheck", lastScreenFreeDetail);
        hello.addProperty("status", !Utils.canUpdate() ? "Connected; waiting to join Minecraft" : assigned() ? localParticipant ? localStatus() : phase : recovered != null ? "Saved job needs inspection in Workers" : "Ready for assignment");
        if (swarm.isWorker()) swarm.worker.send(JSON.toJson(hello));
        if (swarm.isHost()) {
            if (localParticipant) reports.put(me(), hello);
            for (var c : connections()) c.send(JSON.toJson(message("heartbeat")));
        }
    }

    static boolean temporaryOffDuty(boolean assigned, boolean unavailable, boolean eating) {
        return assigned && (unavailable || eating);
    }

    static boolean outsideSupplyArea(JsonObject report, BlockPos center) {
        return report != null && center != null && report.has("x") && report.has("y") && report.has("z")
            && (Math.abs((long) num(report, "x") - center.getX()) > 8 || Math.abs((long) num(report, "z") - center.getZ()) > 8
                || Math.abs((long) num(report, "y") - center.getY()) > 11);
    }

    static boolean outsidePickupArea(JsonObject report, BlockPos center) {
        if (report == null || center == null || !report.has("x") || !report.has("y") || !report.has("z")) return false;
        // Reports contain block coordinates. The entire possible feet cell must be clear, not just its center.
        double distance = 0;
        for (String axis : List.of("x", "y", "z")) {
            double target = (axis.equals("x") ? center.getX() : axis.equals("y") ? center.getY() : center.getZ()) + .5;
            double delta = Math.max(0, Math.max(num(report, axis) - target, target - num(report, axis) - 1));
            distance += delta * delta;
        }
        return distance >= 12.25;
    }

    static boolean distantSupplier(JsonObject report, BlockPos center) {
        return center != null && report != null && report.has("x") && report.has("y") && report.has("z")
            && center.distSqr(new BlockPos(num(report, "x"), num(report, "y"), num(report, "z"))) > 144;
    }

    /** A lane-only return retains the generation, work window, supply locks and outstanding world ACKs. */

    private void applyServiceChange(JsonObject update) {
        if (stopped || releasing || regrouping || isPausedByHost()) return;
        UUID supplier = UUID.fromString(str(update, "supplier"));
        String type = str(update, "type");
        boolean departing = type.equals("service-detach"), offDuty = type.equals("service-away");
        boolean dutyReturn = type.equals("service-back") && away();
        if (!independentSupplies() && (!departing || offDuty) && supplier.equals(supplyOwner)) return;
        JsonObject next = serviceUpdateAssignment(assignment, update);
        if (next == assignment) return;
        // Only the returner settles/reanchors. Other builders retain their mining, paving,
        // input, row and permits; jobWorkPosition observes their updated lane naturally.
        if (localParticipant && supplier.equals(me()) && !departing && !offDuty && detachedSupply()) {
            if (!Utils.canUpdate()) return;
            if (ticks >= serviceReadyUntil) return; // Readiness expired; approach the live crew and report again.
            if (!serviceReturning || !builder().crewFinishRejoin(num(assignment, "length") - startRow())) return;
            serviceReturning = serviceFinished = false; serviceFront = null; serviceReadyUntil = 0;
            begun = true; phase = "building";
        }
        if (localParticipant && supplier.equals(me()) && dutyReturn && !builder().crewDutyRejoinReady()) return;
        assignment = next;
        if (localParticipant && supplier.equals(me()) && dutyReturn) {
            begun = true; phase = "building";
            builder().crewFinishDutyRejoin(num(assignment, "length") - startRow());
        }
        if (localParticipant && supplier.equals(me()) && offDuty) phase = "away";
        if (localParticipant && supplier.equals(me()) && departing) {
            serviceReturning = serviceFinished = false; serviceFront = null; serviceReadyUntil = 0;
            phase = "resupplying";
            builder().crewBeginSupply();
        }
        requested.remove(supplier);
        workChanged();
        persist();
    }

    private void drain(SwarmConnection c, boolean hostSide) {
        if (hostSide && !c.failure().isEmpty() && reportedFailures.add(c)) swarm.reportConnectionFailure(c.failure());
        for (int i = 0; i < 32; i++) {
            String text = c.poll(); if (text == null) break;
            try {
                if (!hostSide && text.startsWith("bot ")) {
                    if (!assigned() && !swarm.tasks().workerBusy() && Utils.canUpdate()) Commands.dispatch(text);
                    continue;
                }
                JsonObject m = JSON.fromJson(text, JsonObject.class);
                if (swarm.handleManagement(c, m, hostSide)) continue;
                if (hostSide) {
                    if (str(m, "type").equals("hello")) {
                        UUID id = UUID.fromString(str(m, "id"));
                        if (peers.containsKey(c) && !str(peers.get(c), "id").equals(id.toString())) throw new IllegalArgumentException("Player identity changed");
                        if (str(m, "name").length() > 64 || str(m, "scope").length() > 1024) throw new IllegalArgumentException("Invalid peer metadata");
                        if (m.has("x") && (Math.abs((long) num(m, "x")) > 29_900_000 || Math.abs((long) num(m, "z")) > 29_900_000 || Math.abs((long) num(m, "y")) > 2048)) throw new IllegalArgumentException("Invalid worker position");
                        if (!peers.containsKey(c) && c.connected()) swarm.info("Worker %s joined %s · %s", str(m, "name"), crewName.isEmpty() ? "Workers" : swarm.crewLabel(crewName), str(m, "status"));
                        if (assigned() && rebindParticipant(participants, id, c, job, generation, str(assignment, "scope"), m)) acknowledgments.remove(id);
                        reportedMining(m); // Validate bounded telemetry at the connection boundary, before controller ticks consume it.
                        if (m.has("currentRow")) RowVerification.Progress.fromReport(m);
                        if (participants.get(id) == c && matchesGeneration(job, generation, m) && workReportChanged(reports.get(id), m)) workChanged();
                        peers.put(c, m); reports.put(id, m); reportTimes.put(id, System.nanoTime());
                        swarm.tasks().observeWorker(c, m);
                        loadEndings();
                        for (String ending : pendingEnds.keySet()) sendPendingEnd(c, id, ending);
                        if (matchesGeneration(job, generation, m) && lock.equals(str(m, "ack")) && !lock.isEmpty() && participants.get(id) == c) acknowledgments.add(id);
                    } else if (peers.containsKey(c)) {
                        UUID id = UUID.fromString(str(peers.get(c), "id"));
                        if (str(m, "type").equals("withdrawn")) acknowledgeWithdrawal(id, m);
                        else if (str(m, "type").equals("ended")) {
                            if (acknowledgeEnd(pendingEnds, str(m, "job"), id)) saveEndings();
                        } else if (participants.get(id) == c) receive(id, m);
                    }
                } else { lastHost = System.nanoTime(); apply(m); }
            } catch (Exception e) { c.disconnect(); swarm.warning("Rejected Workers message: %s", e.getMessage()); }
        }
    }

    protected void apply(JsonObject m) {
        if (swarm != null && swarm.isHost() && !localParticipant) { applyController(m); return; }
        String type = str(m, "type");
        if (type.equals("heartbeat")) return;
        if (type.equals("withdraw")) {
            withdraw(m); return;
        }
        if (type.equals("end")) {
            String ending = str(m, "job"); UUID.fromString(ending);
            JsonObject record = assigned() ? assignment : recoveryRecord();
            if (record != null && ending.equals(str(record, "job"))) {
                clearLocal();
                swarm.info("Host ended the inspected crew job. Ready for a new assignment.");
            }
            // Idempotent receipt: a delayed cancellation never stops a different, newer job.
            var receipt = message("ended"); receipt.addProperty("job", ending);
            if (swarm.isWorker()) swarm.worker.send(JSON.toJson(receipt));
            return;
        }
        if (type.equals("prepare") || type.equals("reconfigure") || type.equals("restore")) {
            boolean restoring = type.equals("restore");
            if (restoring && assigned()) return; // Repeated restore must never reset a live worker.
            if (restoring) {
                JsonObject saved = recoveryRecord();
                if (!sameRecoveryExecution(saved, m, me())) throw new IllegalArgumentException("Recovery assignment does not match the saved execution");
                if (!swarm.tasks().allowsNative()) return; // Workflow must finish perception/recovery first.
            }
            boolean replacement = type.equals("reconfigure");
            if (replacement && releasing) return;
            if (replacement && (!assigned() || !job.equals(str(m, "job")) || num(m, "generation") <= generation)) return;
            boolean supplyUpdate = replacement && supplyHandoff && m.has("supplyHandoff") && m.get("supplyHandoff").getAsBoolean();
            if (replacement && !reconfigurationReady(assignment, generation, m, regroupReady, supplyOwner != null && !supplyUpdate))
                throw new IllegalStateException("Lane ownership changed without a completed synchronization barrier");
            if (!Utils.canUpdate() || swarm.isWorker() && !swarm.acceptCrew.get() || !replacement && (assigned() || builder().hasJob() || !restoring && Files.exists(journal()) || !swarm.tasks().allowsNative()) || !scope().equals(str(m, "scope"))) throw new IllegalStateException("Worker unavailable for a crew assignment");
            UUID.fromString(str(m, "job"));
            UUID.fromString(str(m, "catalogId"));
            if (str(m, "name").length() > 48) throw new IllegalArgumentException("Invalid crew name");
            if (Math.abs((long) num(m, "x")) > 29_900_000 || Math.abs((long) num(m, "z")) > 29_900_000 || Math.abs((long) num(m, "y")) > 2048 || num(m, "length") < 16 || num(m, "length") > HighwayJobs.MAX_LENGTH
                || num(m, "startRow") < 0 || num(m, "startRow") >= num(m, "length") || num(m, "generation") < 0) throw new IllegalArgumentException("Invalid section bounds");
            builder().validateCrewLayout(m.getAsJsonObject("layout"));
            anchorColumn(num(m.getAsJsonObject("layout"), "width"), num(m, "count"), num(m, "index"));
            if (m.getAsJsonArray("members").size() != num(m, "count") || !m.getAsJsonArray("members").get(num(m, "index")).getAsString().equals(me().toString())) throw new IllegalArgumentException("Invalid lane membership");
            Set<UUID> uniqueMembers = new HashSet<>();
            for (var id : m.getAsJsonArray("members")) if (!uniqueMembers.add(UUID.fromString(id.getAsString()))) throw new IllegalArgumentException("Duplicate lane membership");
            if (!m.has("initialStockOwner") || !uniqueMembers.contains(UUID.fromString(str(m, "initialStockOwner"))))
                throw new IllegalArgumentException("Invalid initial stock owner");
            validatePreferredMembers(m, uniqueMembers);
            normalizeWorkflows(m, uniqueMembers);
            List<UUID> active = activeMembers(m);
            if (new HashSet<>(active).size() != active.size() || !uniqueMembers.containsAll(active)) throw new IllegalArgumentException("Invalid active membership");
            validateActiveMembers(m, uniqueMembers);
            if (detachedMembers(m).isEmpty()) Bots.applyWorkflowDuties(m.deepCopy(), Set.copyOf(active));
            for (var entry : suppliers(m).entrySet()) {
                JsonObject layout = m.getAsJsonObject("layout");
                JsonObject site = entry.getValue().getAsJsonObject();
                if (!validSupplySite(m, site)) throw new IllegalArgumentException("Invalid supply site; the native route must verify its footing");
            }
            if (supplyUpdate) validateSupplyUpdate(assignment, m);
            if (replacement) { releasePositioning(); if (!detachedMembers(m).contains(me()) && !awayMembers(m).has(me().toString())) builder().endCrew(); }
            localParticipant = true;
            installAssignment(m);
            if (restoring && detachedSupply()) {
                // The workflow has recovered physical supplies; rejoin through the existing moving rendezvous.
                serviceReturning = true;
                serviceFront = rowCenter(startRow());
                serviceFrontTick = ticks;
            }
            if (away()) swarm.info("Off duty. Teammates cover the highway; leave and return within 16 blocks of the crew to rejoin automatically.");
            else if (detachedSupply()) swarm.info("Detached supply task at %s. Other builders take over the road until you return.", serviceSite(me()).toShortString());
            else swarm.info("Crew lane %d/%d: %s. Automatic nearby positioning; client menus are allowed. The host starts when everyone is ready.", active.indexOf(me()) + 1, active.size(), lanePosition(startPosition()).toShortString());
            return;
        }
        if (!assigned() || !matchesGeneration(job, generation, m)) return;
        switch (type) {
            case "resource-exhausted" -> {
                int resource=num(m,"resource");CrewInventory.name(resource);
                if(localParticipant&&resourcePool.need==resource&&builder().crewResourceExhausted(resource)&&!builder().crewNeedsCleanup())
                    failResources("Resource exhausted: "+CrewInventory.name(resource)+"; local supplies checked and no crew donor available.");
            }
            case "supply-containers" -> {
                JsonObject observed = new JsonObject();
                for (var member : assignment.getAsJsonArray("members")) {
                    String id = member.getAsString();
                    if (m.getAsJsonObject("containers").has(id))
                        observed.add(id, checkedSupplyContainers(assignment, m.getAsJsonObject("containers").get(id)));
                }
                if (!observed.equals(supplyContainers)) { supplyContainers = observed; persist(); }
            }
            case "resource-inspected" -> { if (localParticipant) resourcePool.resolveLocal(); }
            case "resource-exchange" -> { if (localParticipant && inventoryPolicy().enabled()) resourcePool.accept(m.getAsJsonObject("offer")); }
            case "resource-balance" -> {
                int resource = num(m, "resource"), target = num(m, "target"); CrewInventory.name(resource);
                if (localParticipant && target > builder().crewResourceTarget(resource) && target <= ResourceLedger.exchangeTarget(builder().crewInventoryReport(), resource))
                    requestCrewResource(resource, target);
            }
            case "stock-complete" -> { assignment.addProperty("initialStockScanned", true); assignment.addProperty("initialStocked", true); builder().crewInitialStockComplete(); persist(); }
            case "stock-scan-complete" -> { assignment.addProperty("initialStockScanned", true); persist(); }
            case "service-join", "service-detach", "service-away", "service-back" -> applyServiceChange(m);
            case "anticipate-supply" -> { if (localParticipant && !stopped && !regrouping && !isPausedByHost() && !releasing) builder().crewAnticipateSupply(); }
            case "nudge" -> { if (localParticipant && !isPausedByHost()) builder().crewNudge(); }
            case "resume-builder" -> {
                if (localParticipant && !isPausedByHost() && !releasing) {
                    if (away()) builder().enable(); else builder().resumeJob();
                }
            }
            case "window" -> {
                int base = num(m, "base"), mask = num(m, "mask"), limit = num(m, "limit"), checkpoint = num(m, "checkpoint");
                if (stopped || regrouping || base < startRow() + 1 || base > num(assignment, "length") + 1
                    || mask < 0 || mask >= 1 << WORK_WINDOW || limit < startRow() || limit > num(assignment, "length")
                    || checkpoint < startRow() || checkpoint > num(assignment, "length")) return;
                verifiedBase = base; verifiedMask = mask; leadLimit = limit; checkpointRow = checkpoint; lastPermit = System.nanoTime();
                trailAuditor = m.has("trailAuditor") && m.get("trailAuditor").getAsBoolean();
                miningOwners.clear();
                if (breakOrder() && m.has("mining")) {
                    JsonObject owners = m.getAsJsonObject("mining");
                    if (owners.size() > 15) throw new IllegalArgumentException("Too many shared mining targets");
                    List<UUID> active = activeMembers();
                    for (var entry : owners.entrySet()) {
                        BlockPos pos = BlockPos.of(Long.parseLong(entry.getKey()));
                        UUID owner = UUID.fromString(entry.getValue().getAsString());
                        if (!active.contains(owner) || !dutyAllows(duty(owner), true) || !miningPosition(pos)) throw new IllegalArgumentException("Invalid shared mining owner");
                        miningOwners.put(pos, owner);
                    }
                }
                if (localParticipant && Utils.canUpdate()) builder().crewRepairVerificationGap(checkpoint);
            }
            case "begin" -> {
                if (releasing || begun || stopped || localParticipant && !detachedSupply() && !away() && !phase.equals("ready")) return;
                if (ticks < nextStartAttempt) return;
                releasePositioning();
                begun = true; phase = detachedSupply() ? "resupplying" : "building";
                if (localParticipant && !detachedSupply() && !away()) {
                    try { builder().beginCrew(assignment.getAsJsonObject("layout"), startPosition(), num(assignment, "length") - startRow()); }
                    catch (IllegalStateException e) { positioningDetail = "Start deferred: " + e.getMessage(); }
                    if (!builder().hasJob() || !builder().crewAssigned()) {
                        builder().endCrew(); begun = false; phase = "positioning"; nextStartAttempt = ticks + 40;
                        return;
                    }
                }
                if (assignment.has("keepPaused") && assignment.get("keepPaused").getAsBoolean()) {
                    if (localParticipant) builder().pauseJob("Crew remains paused after lane redistribution");
                    phase = "paused";
                }
            }
            case "regroup" -> {
                if (releasing && (!m.has("releasing") || !m.get("releasing").getAsBoolean())) return;
                if (!regrouping) regroupStarted = ticks;
                releasePositioning(); regroupWasPaused = phase.equals("paused");
                regrouping = true; regroupReady = false;
                availabilityChange = m.has("awayMembers") ? m.getAsJsonObject("awayMembers").deepCopy() : null;
                supplyHandoff = m.has("supplyHandoff") && m.get("supplyHandoff").getAsBoolean();
                if (supplyHandoff) {
                    UUID supplier = UUID.fromString(str(m, "supplier"));
                    if (m.get("detach").getAsBoolean()) { detachingWorker = supplier; rejoiningWorker = null; rejoiningSupply = false; }
                    else { rejoiningWorker = supplier; detachingWorker = null; rejoiningSupply = true; }
                }
                releasing = releasing || m.has("releasing") && m.get("releasing").getAsBoolean();
                requested.clear(); phase = "regrouping";
                if (releasing && localParticipant && builder().isJobPaused() && builder().crewNeedsCleanup()) builder().resumeJob();
            }
            case "regroupCancel" -> {
                if (releasing) return; // Late membership controls cannot undo cancellation.
                regrouping = regroupReady = releasing = false;
                availabilityChange = null;
                supplyHandoff = false; detachingWorker = rejoiningWorker = null; rejoiningSupply = false;
                phase = regroupWasPaused ? "paused" : begun ? "building" : "positioning";
            }
            case "pause" -> { releasePositioning(); if (localParticipant) builder().pauseJob("Crew paused by host"); if (regrouping) regroupWasPaused = true; phase = "paused"; }
            case "resume" -> {
                if (stopped && reconnectReady()) { stopped = false; pausedBeforeDisconnect = false; lastPermit = 0; }
                if (!stopped) {
                    if (regrouping) regroupWasPaused = false;
                    if (assignment.has("keepPaused")) assignment.addProperty("keepPaused", false);
                    phase = regrouping ? "regrouping" : begun || detachedSupply() ? "building" : "positioning";
                    if (resumeLocalBuilder(localParticipant, begun, detachedSupply()))
                        builder().resumeCrewJob(assignment.getAsJsonObject("layout"), startPosition(), num(assignment, "length") - startRow());
                    phase = regrouping ? "regrouping" : !begun ? "positioning" : localParticipant ? workPhase(builder().hasJob(), builder().isJobPaused(), builder().getStatus()) : "building";
                }
            }
            case "lost" -> disconnected();
            case "reserve", "grant" -> {
                if (!lock.equals(str(m, "lock"))) { pickupCenter = null; reservationStarted = ticks; }
                supplyOwner = UUID.fromString(str(m, "owner")); supplyCenter = new BlockPos(num(m, "x"), num(m, "y"), num(m, "z"));
                if (pickupCenter == null && compactReservation()) {
                    JsonObject layout = assignment.getAsJsonObject("layout");
                    pickupCenter = supplyCenter.offset(-num(layout, "dx"), 0, -num(layout, "dz"));
                }
                lock = str(m, "lock"); granted = type.equals("grant"); facingTicks = 0; persist();
            }
            case "release" -> {
                if (!lock.equals(str(m, "lock"))) return;
                if (detachedSupply() && me().equals(supplyOwner)) serviceReturning = !resourcePool.localBusy() && (!m.has("retry") || !m.get("retry").getAsBoolean());
                retryingSupply = false;
                supplyOwner = null; supplyCenter = null; lock = acknowledged = ""; granted = backstepped = false;
                pickupCenter = null;
                borrowed = ItemStack.EMPTY; borrowedDrop = returnOwner = null; returnAuthorized = thrown = false; persist();
                pendingReturn = null;
                facingTicks = 0;
            }
            case "service-front" -> {
                if (detachedSupply() && serviceRequestCurrent(assignment, me(), m)) {
                    BlockPos target = new BlockPos(num(m, "x"), num(m, "y"), num(m, "z"));
                    var layout = assignment.getAsJsonObject("layout");
                    if (target.getY() != origin().getY() || !within(target.getX(), target.getZ(), origin().getX(), origin().getZ(),
                        num(layout, "dx"), num(layout, "dz"), num(assignment, "length"))) return;
                    serviceFront = target; serviceFrontTick = ticks;
                }
            }
            case "clearance" -> {
                BlockPos target = new BlockPos(num(m, "x"), num(m, "y"), num(m, "z"));
                if (lock.equals(str(m, "lock")) && validPickupCenter(supplyCenter, target)) pickupCenter = target;
            }
            case "service-complete" -> {
                if (detachedSupply()) {
                    builder().endCrew(); serviceFinished = true; phase = "complete";
                }
            }
            case "return" -> {
                if (me().toString().equals(str(m, "collector")) && borrowedDrop != null && borrowedDrop.toString().equals(str(m, "drop"))) {
                    returnOwner = UUID.fromString(str(m, "owner")); returnAuthorized = true;
                }
            }
            default -> { }
        }
    }

    private void withdraw(JsonObject request) {
        UUID.fromString(str(request, "job")); UUID.fromString(str(request, "token")); num(request, "generation");
        Path receiptFile = namedJournal().resolveSibling(namedJournal().getFileName().toString().replace(".json", "-withdrawal.json"));
        try {
            JsonObject receipt = Files.exists(receiptFile) ? JSON.fromJson(Files.readString(receiptFile), JsonObject.class) : null;
            boolean acknowledged = receipt != null && str(receipt, "job").equals(str(request, "job")) && withdrawalMatches(receipt, request);
            if (!acknowledged) {
                if (assigned()) {
                    if (!matchesGeneration(job, generation, request)) return; // Old handoffs must never clear a returning worker's newer generation.
                    if (!regroupReady || supplyOwner != null || !builder().crewReconfigureReady()) return;
                    clearLocal();
                } else if (Files.exists(journal()) || builder().hasJob()) return; // Recovery after restart is inspection-only, not implicit consent to abandon containers.
                receipt = request.deepCopy(); receipt.addProperty("type", "withdrawn");
                writeRecord(receiptFile, receipt);
            }
            if (swarm.isWorker()) swarm.worker.send(JSON.toJson(receipt));
        } catch (Exception e) { throw new IllegalStateException("Cannot confirm safe worker withdrawal: " + e.getMessage()); }
    }

    static void validatePreferredMembers(JsonObject record, Set<UUID> current) {
        List<UUID> preferred = preferredMembers(record);
        if (preferred.isEmpty() || preferred.size() > num(record.getAsJsonObject("layout"), "width") || new HashSet<>(preferred).size() != preferred.size() || !preferred.containsAll(current))
            throw new IllegalArgumentException("Invalid preferred crew membership");
        if (record.has("preferredWorkflows")) for (var entry : record.getAsJsonObject("preferredWorkflows").entrySet()) {
            if (!preferred.contains(UUID.fromString(entry.getKey()))) throw new IllegalArgumentException("Preferred workflow belongs to another crew");
            BotWorkflows.checkedPlan(entry.getValue().getAsJsonObject());
        }
        if (record.has("borrowedMembers")) for (var entry : record.getAsJsonObject("borrowedMembers").entrySet()) {
            UUID worker = UUID.fromString(entry.getKey()); JsonObject reservation = entry.getValue().getAsJsonObject();
            if (!preferred.contains(worker) || current.contains(worker)) throw new IllegalArgumentException("Borrowed worker still owns a highway lane");
            UUID.fromString(str(reservation, "token"));
            if (num(reservation, "generation") < 0 || num(reservation, "generation") >= num(record, "generation") || !reservation.get("ready").getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException("Invalid worker handoff generation");
        }
    }

    private void validateSupplyUpdate(JsonObject current, JsonObject next) {
        JsonObject expected = supplyAssignment(current, detachingWorker == null ? rejoiningWorker : detachingWorker, detachingWorker != null, num(next, "startRow"));
        for (String key : List.of("job", "catalogId", "scope", "x", "y", "z", "length", "layout", "members", "activeMembers", "suppliers", "hostMember",
            "workflow", "memberWorkflows", "awayMembers", "preferredMembers", "preferredWorkflows", "borrowedMembers"))
            if (!Objects.equals(expected.get(key), next.get(key))) throw new IllegalArgumentException("Supply handoff changed unrelated assignment data: " + key);
    }

    static boolean sameRecoveryExecution(JsonObject saved, JsonObject next, UUID worker) {
        if (saved == null || !str(saved,"job").equals(str(next,"job")) || !str(saved,"catalogId").equals(str(next,"catalogId"))
            || !str(saved,"scope").equals(str(next,"scope")) || num(next,"generation") < num(saved,"generation")) return false;
        for (String key : List.of("x","y","z","length","host")) if (!java.util.Objects.equals(saved.get(key),next.get(key))) return false;
        if (!java.util.Objects.equals(saved.get("layout"),next.get("layout"))) return false;
        return saved.getAsJsonArray("members").asList().stream().anyMatch(v -> v.getAsString().equals(worker.toString()))
            && next.getAsJsonArray("members").asList().stream().anyMatch(v -> v.getAsString().equals(worker.toString()));
    }

    protected void installAssignment(JsonObject m) {
        resourceFailureReason="";
        if (!localParticipant) { installRemoteAssignment(m); return; }
        serviceReadyUntil = 0;
        boolean keepSupply = supplyHandoff && m.has("supplyHandoff") && m.get("supplyHandoff").getAsBoolean();
        boolean keepRunner = keepSupply && localParticipant && detachedSupply() && detachedMembers(m).contains(me());
        assignment = m.deepCopy(); job = str(m, "job"); generation = num(m, "generation"); phase = "positioning";
        assignment.remove("supplyHandoff");
        lastScreenFreeDetail = "Not sampled yet";
        stopped = begun = backstepped = regrouping = regroupReady = releasing = false;
        if (!keepRunner) { serviceReturning = serviceFinished = false; serviceFront = null; }
        pausedBeforeDisconnect = false;
        releaseStarted = 0;
        if (!keepSupply) { retryingSupply = false; supplyRetryAfter = 0; }
        availabilityChange = null;
        nextStartAttempt = 0;
        if (!keepSupply) {
            // Legacy snapshots included an already-granted single runner. New runners
            // travel independently and request the container lock only at their site.
            supplyOwner = m.has("suppliers") ? null : detachedMember();
            supplyCenter = supplyOwner == null ? null : serviceSite(supplyOwner);
            lock = supplyOwner == null ? "" : str(m, "serviceLock"); granted = supplyOwner != null;
            pickupCenter = null;
        }
        supplyHandoff = false;
        resetWindow(startRow()); lastHost = System.nanoTime(); persist();
    }

    public void inspectPreviousTransfer(long before) {
        if (!mc.isSameThread() || assigned() || builder().hasJob() || resourcePool.localBusy())
            throw new IllegalStateException("Stop the native job/transfer before inspecting its old receipt");
        CrewInventory.inspectTransferReceipt(resourcePool.receiptFile(), before);
    }
    public void discardPreviousTransfer() {
        if (!mc.isSameThread() || assigned() || builder().hasJob() || resourcePool.localBusy())
            throw new IllegalStateException("Stop the native job/transfer before discarding its receipt");
        resourcePool.discardReceipt();
    }

    public String workflowRecoveryConcern() {
        if (assigned()) return "";
        JsonObject record=recoveryRecord();
        if (record!=null && !scope().equals(str(record,"scope"))) return "Supply recovery belongs to a different server/dimension";
        if (record!=null && me().toString().equals(str(record,"supplyOwner")))
            return "Legacy supply recovery at " + str(record,"supplyPosition") + " lacks a container identity; inspect this site before releasing it";
        try {
            Path path=resourcePool.receiptFile();
            JsonObject receipt=Files.exists(path)?JSON.fromJson(Files.readString(path),JsonObject.class):null;
            if(CrewInventory.unresolvedReceipt(receipt)) return "A previous crew transfer remains unconfirmed; its durable receipt has been preserved for reconciliation";
        } catch (Exception e) { return "Cannot read the previous crew transfer receipt: " + e.getMessage(); }
        return "";
    }
    public void workflowRecoveryComplete(String execution) {
        if (assigned()) return;
        JsonObject record=recoveryRecord();
        if(record==null || !scope().equals(str(record,"scope")) || !str(record,"job").equals(execution) || !me().toString().equals(str(record,"supplyOwner"))) return;
        record=record.deepCopy(); record.addProperty("supplyOwner",""); record.addProperty("supplyPosition","");
        try { writeRecord(journal(),record); savedRecovery=record; }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot checkpoint recovered workflow supplies",e); }
    }

    public void supplyBanter(UUID drop, UUID collector, int amount) {
        if (!localAssigned()) return;
        Set<UUID> crew = new HashSet<>(); assignment.getAsJsonArray("members").forEach(v -> crew.add(UUID.fromString(v.getAsString())));
        dev.monocle.client.systems.bots.BotBanter.pickup(drop, collector, amount, crew);
    }

    public JsonObject workerDiagnostics(UUID worker) {
        if (worker.equals(me())) return builder().diagnosticSnapshot();
        return peers.entrySet().stream().filter(e -> e.getKey().connected() && worker.toString().equals(str(e.getValue(), "id")))
            .map(e -> e.getValue().has("diagnostics") ? e.getValue().getAsJsonObject("diagnostics").deepCopy() : new JsonObject())
            .findFirst().orElseGet(JsonObject::new);
    }

    public boolean hold() { return localAssigned() && (stopped || !live()
        || away()
        || detachedSupply() && serviceReturning && builder().crewRestockIdle()
        || regrouping && !(supplyHandoff && detachedSupply() && !me().equals(rejoiningWorker)) && !me().equals(supplyOwner) && !builder().crewNeedsCleanup()
        || sharedSupplyHold() && !supplyOwner.equals(me())); }
    public void acknowledgeHold() {
        if (!lock.isEmpty()) { acknowledged = lock; if (swarm.isHost()) acknowledgments.add(me()); }
    }
    public void holdStep(HighwayBuilder b) {
        boolean settled = b.crewQuiesce();
        // Clear pickup space even while old road ACKs drain. Grant still requires both conditions.
        if (!stopped && live() && supplyCenter != null && (returnAuthorized || b.crewYield(pickupCenter == null ? supplyCenter : pickupCenter)) && settled) acknowledgeHold();
    }
    public boolean requestSupply(BlockPos origin) {
        return requestSupply(origin, false);
    }
    private boolean requestSupply(BlockPos origin, boolean detach) {
        if (!localAssigned()) return true;
        if (stopped || !live()) return false;
        if (independentSupplies() && detachedSupply()) return !serviceReturning;
        if (independentSupplies()) detach = true;
        if (regrouping && supplyOwner == null) return false;
        if (detach && activeMembers().contains(me()) || supplyOwner == null || !supplyOwner.equals(me())) {
            var m = jobMessage("request"); m.addProperty("x", origin.getX()); m.addProperty("y", origin.getY()); m.addProperty("z", origin.getZ());
            m.addProperty("detach", detach);
            m.addProperty("serviceRevision", serviceRevision(assignment, me()));
            send(m); return false;
        }
        return granted;
    }
    public boolean supplyReady(HighwayBuilder b) {
        if (!localAssigned()) return true;
        if (retryingSupply || releasing) return false;
        if (detachedSupply()) {
            BlockPos site = serviceSite(me());
            return !serviceReturning && b.crewTravelSupply(site) && (independentSupplies() || requestSupply(site, false));
        }
        requestSupply(BlockPos.containing(b.jobWorkPosition()), true);
        return false; // Native restocks always hand off their lane before using supplies.
    }
    public String supplyWaitReason() {
        if (releasing) return "Ending job; no new supply trips";
        if (retryingSupply) return "Waiting for the previous supply reservation to release";
        if (!detachedSupply()) return regrouping ? "Handing off lane before detached resupply" : "Requesting detached resupply from the host";
        if (builder().crewIsTraveling()) return ""; // Keep the native flight/route blocker visible.
        if (independentSupplies()) return serviceReturning ? "Returning to current work assignment" : "Preparing local supply space";
        if (supplyOwner == null) return "At supply staging site; waiting for container access";
        if (!supplyOwner.equals(me())) return "At supply staging site; waiting for " + memberName(supplyOwner) + " to recover their container";
        return granted ? "" : "Waiting for nearby players to clear the supply site";
    }
    /** Only called after the builder proves no container, transfer or block intent is outstanding. */
    public void retryUncommittedSupply() {
        if (independentSupplies()) { retryingSupply = false; return; }
        if (!localAssigned() || !me().equals(supplyOwner)) return;
        retryingSupply = true;
        var request = jobMessage("release"); request.addProperty("lock", lock); request.addProperty("retry", true);
        send(request); // A retry is not a completed supply trip. Queued teammates get their turn first.
    }
    public void releaseSupply() {
        if (localAssigned() && supplyOwner != null && supplyOwner.equals(me())) { var m = jobMessage("release"); m.addProperty("lock", lock); send(m); }
    }
    public void finishSupplyIfIdle() {
        releaseSupply();
        if (detachedSupply() && builder().crewRestockIdle() && !builder().crewNeedsCleanup() && !resourcePool.localBusy()) serviceReturning = true;
    }
    public boolean resumeDetachedReturn() {
        if (!localAssigned() || !detachedSupply() || !canResume() || isPausedByHost() || releasing
            || resourcePool.localBusy() || !builder().crewPrepareManualReturn()) return false;
        serviceReturning = true;
        serviceFinished = false;
        serviceReadyUntil = 0;
        workChanged(); // Request the current host front; rendered crewmates already take precedence.
        return true;
    }
    public boolean clearance(BlockPos container) {
        if (!localAssigned()) return true;
        // Crew mates yield only the actual pickup radius; the owner immediately collects its tracked drop.
        double distance = supplyYieldDistance();
        boolean clear = mc.level.players().stream().noneMatch(p -> p != mc.player && !crewMember(p.getUUID())
            && p.distanceToSqr(Vec3.atCenterOf(container)) < distance * distance);
        if (!clear && me().equals(supplyOwner) && ticks % 10 == 0 && validPickupCenter(supplyCenter, container)) {
            var request = jobMessage("clearance"); request.addProperty("lock", lock);
            request.addProperty("x", container.getX()); request.addProperty("y", container.getY()); request.addProperty("z", container.getZ()); send(request);
        }
        return clear;
    }
    static boolean crewPickupExclusion(double distanceSquared) { return distanceSquared < 2.25; }
    private boolean crewMember(UUID id) {
        return assignment != null && assignment.getAsJsonArray("members").asList().stream().anyMatch(member -> id.toString().equals(member.getAsString()));
    }
    static boolean validPickupCenter(BlockPos reservation, BlockPos pickup) {
        return reservation != null && Math.abs((long) pickup.getX() - reservation.getX()) <= 7
            && Math.abs((long) pickup.getZ() - reservation.getZ()) <= 7 && Math.abs((long) pickup.getY() - reservation.getY()) <= 10;
    }

    public boolean compactSupply() { return localAssigned() && assignment.has("suppliers") && compactSite(assignment.getAsJsonObject("suppliers").getAsJsonObject(me().toString())); }

    public boolean laneSupply() { return localAssigned() && assignment.has("suppliers") && laneSite(assignment.getAsJsonObject("suppliers").getAsJsonObject(me().toString())); }

    public int supplyLaneOffset() {
        if (!laneSupply()) return 0;
        BlockPos site = serviceSite(me()), origin = origin();
        JsonObject layout = assignment.getAsJsonObject("layout");
        return (site.getX() - origin.getX()) * num(layout, "dz") - (site.getZ() - origin.getZ()) * num(layout, "dx");
    }

    public boolean withinSupplyLane(BlockPos pos) {
        if (!laneSupply()) return true;
        JsonObject layout = assignment.getAsJsonObject("layout");
        return (pos.getX() - num(assignment, "x")) * num(layout, "dz") - (pos.getZ() - num(assignment, "z")) * num(layout, "dx") == supplyLaneOffset();
    }

    public double supplyYieldDistance() { return supplyOwner != null && assignment != null && assignment.has("suppliers") && laneSite(assignment.getAsJsonObject("suppliers").getAsJsonObject(supplyOwner.toString())) ? 1.75 : compactReservation() ? 3.5 : 5.5; }

    static net.minecraft.world.phys.AABB supplyWorkArea(BlockPos center, int dx, int dz, boolean compact) {
        var area = new net.minecraft.world.phys.AABB(center).inflate(7, 10, 7);
        if (!compact) return area;
        // Containers and relocation stay behind the runner; do not reserve the active excavation face.
        return new net.minecraft.world.phys.AABB(dx == -1 ? center.getX() - 1 : area.minX, area.minY, dz == -1 ? center.getZ() - 1 : area.minZ,
            dx == 1 ? center.getX() + 2 : area.maxX, area.maxY, dz == 1 ? center.getZ() + 2 : area.maxZ);
    }
    private net.minecraft.world.phys.AABB supplyWorkArea() {
        JsonObject layout = assignment.getAsJsonObject("layout");
        return supplyWorkArea(supplyCenter, num(layout, "dx"), num(layout, "dz"), compactReservation());
    }
    public boolean protectedPosition(BlockPos pos) {
        if (independentSupplies()) {
            if (!localAssigned() || !Utils.canUpdate()) return false;
            // Neighboring supply areas overlap. A bot must still recover its own container.
            if (containsSupplyPosition(builder().crewSupplyContainers(), pos, builder().crewPlacingSupply())) return false;
            for (var member : supplyContainers.entrySet()) {
                if (member.getKey().equals(me().toString())) continue;
                for (JsonElement element : member.getValue().getAsJsonArray()) {
                    JsonObject site = element.getAsJsonObject();
                    BlockPos container = new BlockPos(num(site, "x"), num(site, "y"), num(site, "z"));
                    if ((pos.equals(container) || pos.above().equals(container)) && observedSupplyAt(container)) return true;
                }
            }
            // Placement and its observation can arrive in different orders. Only a real
            // container/drop in another supplier's local area is protected during that gap.
            for (var member : suppliers(assignment).entrySet()) {
                if (member.getKey().equals(me().toString())) continue;
                JsonObject site = member.getValue().getAsJsonObject();
                BlockPos center = new BlockPos(num(site, "x"), num(site, "y"), num(site, "z"));
                if (pos.distSqr(center) <= 121 && (observedSupplyAt(pos) || observedSupplyAt(pos.above()))) return true;
            }
            return false;
        }
        if (!localAssigned() || supplyOwner == null || supplyOwner.equals(me()) || !Utils.canUpdate()
            || !supplyWorkArea().contains(Vec3.atCenterOf(pos))) return false;
        // Protect the actual container and its footing, not a large empty reservation
        // volume that can overlap the next road row after a supplier rejoins.
        for (BlockPos site : List.of(pos, pos.above())) {
            var block = mc.level.getBlockState(site).getBlock();
            if (block instanceof net.minecraft.world.level.block.ShulkerBoxBlock || block == net.minecraft.world.level.block.Blocks.ENDER_CHEST) return true;
        }
        return !mc.level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(pos.above()),
            item -> Utils.isShulker(item.getItem().getItem())).isEmpty();
    }
    static boolean containsSupplyPosition(com.google.gson.JsonArray containers, BlockPos pos) {
        return containsSupplyPosition(containers, pos, null);
    }
    static boolean containsSupplyPosition(com.google.gson.JsonArray containers, BlockPos pos, BlockPos placing) {
        // Vanilla predicts the block BEFORE PacketEvent.Send. The attempt was checked
        // against peer containers before entering useItemOn; its own prediction must
        // not become a peer's protected container while the packet is being sent.
        if (placing != null && (pos.equals(placing) || pos.above().equals(placing))) return true;
        for (JsonElement element : containers) {
            JsonObject site = element.getAsJsonObject();
            BlockPos container = new BlockPos(num(site, "x"), num(site, "y"), num(site, "z"));
            if (pos.equals(container) || pos.above().equals(container)) return true;
        }
        return false;
    }
    private boolean observedSupplyAt(BlockPos pos) {
        if (!mc.level.hasChunkAt(pos)) return false;
        var block = mc.level.getBlockState(pos).getBlock();
        return block instanceof net.minecraft.world.level.block.ShulkerBoxBlock || block == net.minecraft.world.level.block.Blocks.ENDER_CHEST
            || !mc.level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(pos),
                item -> Utils.isShulker(item.getItem().getItem())).isEmpty();
    }
    public boolean allowsWork(BlockPos pos) {
        if (!localAssigned()) return true;
        if (stopped || !live() || hold() || builder().crewIsTraveling()) return false;
        if (independentSupplies() && detachedSupply()) {
            var layout = assignment.getAsJsonObject("layout");
            return !serviceReturning && !protectedPosition(pos)
                && supplyWorkArea(supplyPosition(), num(layout, "dx"), num(layout, "dz"), compactSupply()).contains(Vec3.atCenterOf(pos));
        }
        if (supplyOwner != null && supplyOwner.equals(me()) && granted) return supplyWorkArea().contains(Vec3.atCenterOf(pos));
        if (away() || detachedSupply() || protectedPosition(pos)) return false;
        var layout = assignment.getAsJsonObject("layout");
        return builder().crewAuditTarget(pos) || owns(pos) && within(pos.getX(), pos.getZ(), num(assignment, "x"), num(assignment, "z"), num(layout, "dx"), num(layout, "dz"), num(assignment, "length"));
    }
    public boolean allowsPlacement(BlockPos pos) {
        if (allowsWork(pos)) return true;
        return localAssigned() && canResume() && !hold() && !away() && !detachedSupply() && !builder().crewIsTraveling()
            && !protectedPosition(pos) && ownsSealing(pos) && builder().crewSealingTarget(pos);
    }
    public void guard(PacketEvent.Send event) {
        if (!localAssigned() || !Utils.canUpdate() || !guardsManualActions(builder().isActive(), builder().crewToggledOff(), away())) return;
        if (builder().crewVerificationProbe() && event.packet instanceof ServerboundUseItemOnPacket) return;
        if (!borrowed.isEmpty() && !sendingReturn && (event.packet instanceof net.minecraft.network.protocol.game.ServerboundContainerClickPacket
            || event.packet instanceof ServerboundPlayerActionPacket drop && (drop.getAction() == ServerboundPlayerActionPacket.Action.DROP_ITEM || drop.getAction() == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS))) { event.cancel(); return; }
        if (event.packet instanceof ServerboundPlayerActionPacket p && (p.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK || p.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)) {
            if (!allowsWork(p.getPos())) event.cancel();
        } else if (event.packet instanceof ServerboundUseItemOnPacket p) {
            if (visitingSupply(p.getHitResult().getBlockPos()) && !stopped && live()) return;
            BlockPos hit = p.getHitResult().getBlockPos();
            String reason = hold() ? "crew hold" : builder().crewIsTraveling() ? "crew travel"
                : protectedPosition(hit) ? "protected supporting block"
                : protectedPosition(hit.relative(p.getHitResult().getDirection())) ? "protected destination" : null;
            if (reason != null) { builder().crewPlacementBlocked(reason, hit); event.cancel(); }
        }
    }

    static boolean guardsManualActions(boolean active, boolean toggledOff, boolean away) { return active && !toggledOff && !away; }
    public int verificationCheckpoint() { return checkpointRow; }
    public int firstWorkRow() { return startRow(); }

    public void pickup(int itemId, int collectorId, int amount) {
        resourcePool.pickup(itemId, collectorId, amount);
        // Accidental crew pickup stays in the resource pool. The owner's absence
        // observation retires recovery; do not lock the collector's inventory or
        // create another rendezvous solely to pass the same shulker back.
    }

    /** Host reservations plus a single local inventory owner. Road workers never wait for this exchange. */
    private final class ResourcePool {
        // ponytail: one exchange per crew; parallel pairs only if measured transfer queues warrant it.
        private JsonObject localOffer, receipt, ownSupplySnapshot;
        private int need = -1, priorNeed = -1, needTarget = -1, localSince, nextSync, facing, nextOwnSupplyCheck;
        private int received;
        private boolean listening;
        private String localStage = "idle", detail = "";

        boolean localBusy() { return need >= 0 || localOffer != null && !Set.of("complete", "cancelled", "failed").contains(localStage); }
        boolean shared() { return localOffer != null && str(localOffer, "phase").equals("shared") && ticks - localSince < 600 && !Set.of("complete", "failed").contains(localStage); }
        BlockPos sharedPosition() {
            JsonObject site = localOffer.getAsJsonObject("container");
            return new BlockPos(num(site,"x"), num(site,"y"), num(site,"z"));
        }
        Path receiptFile() { return namedJournal().resolveSibling(namedJournal().getFileName().toString().replace(".json", "-inventory-transfer.json")); }
        void discardReceipt() {
            try { Files.deleteIfExists(receiptFile()); }
            catch (java.io.IOException e) { throw new IllegalStateException("Cannot discard the previous transfer receipt", e); }
            resourceExchange.hostOffer = localOffer = receipt = null; localStage = "idle"; received = facing = nextSync = 0; need = priorNeed = -1;
        }
        JsonObject report() {
            JsonObject result = new JsonObject(); result.addProperty("need", need); result.addProperty("stage", localStage);
            result.addProperty("detail", detail);
            result.addProperty("stageTicks", ticks - localSince);
            result.addProperty("received", received);
            result.addProperty("issued", receipt != null && receipt.has("issued"));
            if (localOffer != null) {
                result.addProperty("id", str(localOffer, "id")); result.addProperty("sequence", num(localOffer, "sequence"));
                if (receipt != null && receipt.has("proposal")) result.add("proposal", receipt.get("proposal").deepCopy());
                if (shared() && donor()) {
                    BlockPos site = builder().crewSharedContainer();
                    if (site != null) { JsonObject p = new JsonObject(); p.addProperty("x",site.getX()); p.addProperty("y",site.getY()); p.addProperty("z",site.getZ()); result.add("container",p); }
                }
            }
            return result;
        }
        void listen(boolean enabled) {
            if (enabled == listening) return;
            if (enabled) MonocleClient.EVENT_BUS.subscribe(this); else MonocleClient.EVENT_BUS.unsubscribe(this);
            listening = enabled;
        }
        void saveLocal() {
            if (receipt == null || localOffer == null) return;
            receipt.addProperty("job", job); receipt.add("offer", localOffer.deepCopy()); receipt.addProperty("stage", localStage);
            try { writeRecord(receiptFile(), receipt); }
            catch (java.io.IOException e) { localStage = "uncertain"; detail = "Cannot save transfer receipt; no further drops: " + e.getMessage(); throw new IllegalStateException(detail, e); }
        }
        void accept(JsonObject offer) {
            if (offer == null || offer.toString().length() > 40000) throw new IllegalArgumentException("Invalid resource exchange");
            for (String key : List.of("resource", "remaining", "sequence", "x", "y", "z")) CrewInventory.integer(offer, key);
            if (offer.has("proposal")) {
                JsonObject proposal = offer.getAsJsonObject("proposal");
                int slot = CrewInventory.integer(proposal, "slot"), count = CrewInventory.integer(proposal, "count");
                int units = proposal.has("units") ? CrewInventory.integer(proposal, "units") : count;
                if (slot < 0 || slot >= 36 || count < 1 || count > 99 || units < count || units > num(offer, "remaining") || !proposal.has("stack"))
                    throw new IllegalArgumentException("Invalid inventory transfer proposal");
            }
            UUID.fromString(str(offer, "id"));
            UUID donor = UUID.fromString(str(offer, "donor")), recipient = UUID.fromString(str(offer, "recipient"));
            CrewInventory.name(num(offer, "resource"));
            JsonObject geometry = assignment.getAsJsonObject("layout");
            long x = (long) num(offer, "x") - num(assignment, "x"), z = (long) num(offer, "z") - num(assignment, "z");
            long row = x * num(geometry, "dx") + z * num(geometry, "dz");
            if (donor.equals(recipient) || !assignment.getAsJsonArray("members").asList().stream().anyMatch(e -> e.getAsString().equals(donor.toString()))
                || !assignment.getAsJsonArray("members").asList().stream().anyMatch(e -> e.getAsString().equals(recipient.toString()))
                || !me().equals(donor) && !me().equals(recipient) || num(offer, "remaining") < 1 || num(offer, "remaining") > 1536
                || num(offer, "sequence") < 0 || num(offer, "sequence") > 1536 || Math.abs((long) num(offer, "x")) > 29_900_000
                || Math.abs((long) num(offer, "z")) > 29_900_000 || num(offer, "y") != num(assignment, "y")
                || Math.abs(x * num(geometry, "dz") - z * num(geometry, "dx")) > (str(offer,"mode").equals("shared") ? num(geometry,"width") / 2 : 0)
                || row < -90 || row >= num(assignment, "length")
                || !Set.of("shared", "gather", "prepare", "drop", "complete", "cancelled", "uncertain").contains(str(offer, "phase")))
                throw new IllegalArgumentException("Resource exchange outside crew/job bounds");
            if (offer.has("container")) {
                JsonObject site = offer.getAsJsonObject("container");
                if (Math.abs((long) CrewInventory.integer(site,"x")) > 29_900_000 || Math.abs((long) CrewInventory.integer(site,"z")) > 29_900_000
                    || CrewInventory.integer(site,"y") != num(assignment,"y")) throw new IllegalArgumentException("Invalid shared supply position");
            }
            boolean same = localOffer != null && str(localOffer, "id").equals(str(offer, "id"));
            if (same && num(offer, "sequence") < num(localOffer, "sequence")) return;
            if (!same) {
                try {
                    JsonObject old = Files.exists(receiptFile()) ? JSON.fromJson(Files.readString(receiptFile()), JsonObject.class) : null;
                    if (old != null && old.has("offer") && str(old.getAsJsonObject("offer"), "id").equals(str(offer, "id"))) {
                        localOffer = old.getAsJsonObject("offer").deepCopy(); receipt = old;
                        localStage = str(old, "stage"); localSince = ticks;
                        received = old.has("received") ? CrewInventory.integer(old, "received") : 0;
                        // Restoring an issued intent retries confirmation, never the THROW packet.
                        if (localStage.equals("issued")) detail = "Rechecking the interrupted drop with the server; no duplicate drop";
                        accept(offer); return; // Reconcile the same durable sequence, never create a fresh drop intent.
                    }
                    if (CrewInventory.unresolvedReceipt(old)) {
                        localOffer = offer.deepCopy(); receipt = null; localStage = "uncertain"; detail = "An older item drop needs inspection; refusing a duplicate transfer"; return;
                    }
                } catch (Exception e) { throw new IllegalStateException("Cannot inspect the previous transfer receipt", e); }
            }
            boolean next = !same || num(offer, "sequence") > num(localOffer, "sequence");
            if (next) { if (!same) priorNeed = need; receipt = new JsonObject(); localStage = "gather"; localSince = ticks; received = facing = 0; nextSync = 0; detail = ""; }
            boolean inspected = offer.has("inspected") && offer.get("inspected").getAsBoolean();
            if (same && !next && Set.of("complete", "cancelled", "uncertain").contains(localStage) && !inspected) return;
            if (localOffer != null && !str(localOffer, "phase").equals(str(offer, "phase"))) localSince = ticks;
            localOffer = offer.deepCopy();
            if (inspected && receipt == null) receipt = new JsonObject();
            String phase = str(offer, "phase");
            if (Set.of("complete", "cancelled", "uncertain").contains(phase)) {
                if (same && str(offer,"mode").equals("shared")) builder().crewCloseSharedSupply();
                if (phase.equals("cancelled") && CrewInventory.unresolvedReceipt(receipt) && !inspected) {
                    localStage = "uncertain"; detail = "Cancelled exchange still has an issued drop to reconcile";
                    saveLocal(); listen(false); return;
                }
                localStage = phase; detail = "Crew transfer " + phase; saveLocal(); listen(false);
                if (!phase.equals("uncertain")) {
                    need = donor() || phase.equals("cancelled") ? priorNeed : -1;
                    if (builder().crewRestockIdle()) builder().crewFinishResourceWait();
                    serviceReturning = need < 0 && !builder().crewNeedsCleanup();
                }
                return;
            }
            need = -1; serviceReturning = false;
            if (phase.equals("prepare") && !receipt.has("baseline")) localStage = "preparing";
            listen(true); saveLocal();
        }
        boolean donor() { return localOffer != null && str(localOffer, "donor").equals(me().toString()); }
        JsonObject carriedContainers() {
            return CrewInventory.manifest(mc.player.getInventory().getNonEquipmentItems().stream()
                .filter(s -> s.is(net.minecraft.world.item.Items.ENDER_CHEST) || Utils.isShulker(s.getItem())).toList());
        }
        boolean newOwnSupplies(HighwayBuilder b, int resource) {
            return ownSupplySnapshot != null && !ownSupplySnapshot.equals(carriedContainers()) && b.crewHasStoredResource(resource);
        }
        int count(ItemStack expected) { return CrewInventory.count(mc.player.getInventory(), s -> ItemStack.isSameItemSameComponents(s, expected)); }
        ItemStack stack(JsonObject proposal) {
            return ItemStack.CODEC.parse(mc.player.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), proposal.get("stack")).getOrThrow();
        }
        void failLocal(String message) {
            localStage = receipt != null && receipt.has("issued") ? "uncertain" : "failed";
            if (localOffer != null && str(localOffer,"mode").equals("shared") && !donor()) need = priorNeed;
            detail = message; saveLocal();
        }
        boolean tickLocal(HighwayBuilder b) {
            if (!localBusy()) return false;
            if (stopped || releasing || isPausedByHost() || !live()) return false;
            if (localOffer != null && str(localOffer,"phase").equals("shared")) {
                if (ticks - localSince >= 600) { b.crewCloseSharedSupply(); failLocal("Shared supplies changed or timed out; choosing another source"); return false; }
                if (!shared()) return false;
                serviceReturning = false;
                if (!detachedSupply()) { requestSupply(BlockPos.containing(b.jobWorkPosition()),true); b.crewQuiesce(); return true; }
                int resource = num(localOffer,"resource");
                if (donor()) {
                    if (b.crewRestockIdle()) {
                        if (!b.crewHasShareableResource(resource)) { failLocal("Stored resource no longer available"); return false; }
                        b.crewFetchResource(resource, b.crewResourceTarget(resource));
                    }
                    localStage = "serving";
                    return false; // Normal native restock owns placement and all cleanup.
                }
                if (!b.crewRestockIdle()) return false;
                if (!localOffer.has("container")) { b.crewCloseSharedSupply(); b.crewQuiesce(); b.crewInventoryStatus("Waiting for a crew supply shulker to open"); return true; }
                String outcome = b.crewTakeSharedSupply(sharedPosition(), resource);
                if (outcome.equals("complete") || outcome.equals("failed")) {
                    localStage = outcome; b.crewCloseSharedSupply();
                    if (outcome.equals("complete")) { need = -1; serviceReturning = true; }
                    else need = priorNeed;
                } else localStage = "collecting";
                return true;
            }
            // A waiting exchange still yields to another bot's physical container recovery.
            if (hold()) { holdStep(b); return true; }
            if (need >= 0) {
                int target = Math.max(b.crewResourceTarget(need), needTarget);
                if (b.crewResourceCount(need) >= target) {
                    need = -1; b.crewFinishResourceWait(); releaseSupply(); serviceReturning = detachedSupply(); return false;
                }
                if (!detachedSupply()) { requestSupply(BlockPos.containing(b.jobWorkPosition()), true); b.crewQuiesce(); return true; }
                serviceReturning = false;
                // A newly acquired echest/shulker can make our own stock accessible while waiting for a donor.
                if (ticks >= nextOwnSupplyCheck && b.crewRestockIdle()) {
                    nextOwnSupplyCheck = ticks + 100;
                    if (newOwnSupplies(b, need)) {
                        int resource = need; need = -1;
                        b.crewFetchResource(resource, target);
                        return false;
                    }
                }
                b.crewQuiesce(); b.crewInventoryStatus("Crew supplies: waiting for a donor of " + CrewInventory.name(need));
                return true;
            }
            if (Set.of("uncertain", "failed").contains(localStage)) {
                b.crewQuiesce(); b.crewInventoryStatus(detail); return true;
            }
            if (!detachedSupply()) { b.crewQuiesce(); b.crewInventoryStatus("Crew supplies: detaching for exchange"); return true; }
            serviceReturning = false;
            int resource = num(localOffer, "resource");
            if (!b.crewRestockIdle()) return false; // Native owner finishes every container before the transfer can take over.
            if (!b.crewDetachedSupply() && !b.crewTravelSupply(serviceSite(me()))) return true;
            if (me().equals(supplyOwner)) {
                if (b.crewInventorySettled()) releaseSupply();
                return true; // Await the host's release before walking into the exchange.
            }
            if (!donor() && str(localOffer, "phase").equals("gather")
                && (b.crewResourceCount(resource) >= b.crewResourceTarget(resource) || newOwnSupplies(b, resource))) {
                failLocal("Own supplies are now available; retrying local restock before requesting a donation");
                return true; // No drop authorized in gather; cancel the pair before opening any container.
            }
            if (donor() && str(localOffer, "phase").equals("gather") && !receipt.has("proposal")
                && b.crewResourceCount(resource) <= b.crewResourceTarget(resource)) {
                if (!b.crewHasStoredResource(resource)) { failLocal("Donor no longer has transferable supplies"); return true; }
                b.crewFetchResource(resource, b.crewResourceTarget(resource) + Math.min(num(localOffer, "remaining"), ResourceLedger.exchangeBatch(resource)));
                return false;
            }
            BlockPos meeting = new BlockPos(num(localOffer, "x"), num(localOffer, "y"), num(localOffer, "z"));
            JsonObject layout = assignment.getAsJsonObject("layout");
            BlockPos target = donor() ? meeting.offset(num(layout, "dx") * 2, 0, num(layout, "dz") * 2) : meeting;
            if (!donor()) {
                var partner = mc.level.getPlayerByUUID(UUID.fromString(str(localOffer, "donor")));
                if (partner != null && partner.isAlive() && !partner.isRemoved())
                    target = partner.blockPosition().offset(-num(layout, "dx") * 2, 0, -num(layout, "dz") * 2);
            }
            if (str(localOffer, "phase").equals("drop") && localOffer.has("proposal")) {
                // Inventory confirmation must keep running even when positioning is still in progress.
                requestSync();
                if (!donor() && localStage.equals("ready")) {
                    ItemStack expected = stack(localOffer.getAsJsonObject("proposal"));
                    int amount = num(localOffer.getAsJsonObject("proposal"), "count");
                    if (CrewInventory.capacity(mc.player.getInventory(), expected, 1) < Math.max(0, amount - received)) {
                        b.crewMakeTransferRoom(expected, Math.max(0, amount - received));
                        b.crewInventoryStatus("Crew exchange: making room to recover the existing drop");
                        return true;
                    }
                    ItemEntity drop = mc.level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(meeting).inflate(6, 2, 6),
                            item -> item.isAlive() && ItemStack.isSameItemSameComponents(item.getItem(), expected)
                                && Math.abs(item.getY() - meeting.getY()) < 1)
                        .stream().min(java.util.Comparator.comparingDouble(mc.player::distanceToSqr)).orElse(null);
                    target = CrewInventory.pickupTarget(meeting, num(layout, "dx"), num(layout, "dz"), drop == null ? null : drop.position(), ticks - localSince);
                }
            }
            if (!b.crewTravelRejoin(target)) { b.crewInventoryStatus("Crew supplies: approaching exchange partner"); return true; }
            if (!b.crewInventorySettled() || mc.player.isUsingItem()) return true;
            if (!str(localOffer, "phase").equals("gather") && localOffer.has("proposal")) {
                ItemStack item = stack(localOffer.getAsJsonObject("proposal"));
                if (b.crewTransferUnits(resource, item) <= 0) { failLocal("Offered item does not match this bot's usable resource policy"); return true; }
                int amount = num(localOffer.getAsJsonObject("proposal"), "count");
                if (amount < 1 || amount > item.getCount()) { failLocal("Invalid transfer quantity"); return true; }
                if (localStage.equals("preparing")) {
                    if (!donor() && CrewInventory.capacity(mc.player.getInventory(), item, 1) < amount) {
                        if (!b.crewMakeTransferRoom(item, amount)) failLocal("Recipient has no safe inventory capacity");
                        return true;
                    }
                    requestSync();
                }
                if (donor() && str(localOffer, "phase").equals("drop") && localStage.equals("ready")) {
                    if (!nearPartner()) return true;
                    int slot = num(receipt.getAsJsonObject("proposal"), "slot");
                    ItemStack held = mc.player.getInventory().getItem(slot);
                    int units = localOffer.getAsJsonObject("proposal").has("units") ? num(localOffer.getAsJsonObject("proposal"), "units") : amount;
                    int available = ResourceLedger.available(b.crewInventoryReport(), resource);
                    if (held.getCount() < amount || !CrewInventory.mayDrop(receipt.has("issued"), ItemStack.isSameItemSameComponents(held, item),
                        available, b.crewResourceReserve(resource), amount, units)) { failLocal("Donor inventory changed; no items dropped"); return true; }
                    if (++facing < 2) return true;
                    receipt.addProperty("before", count(item)); receipt.addProperty("beforeSlot", held.getCount());
                    receipt.addProperty("issued", true); localStage = "issued"; saveLocal(); // durable BEFORE the destructive packet
                    mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, dev.monocle.client.utils.player.SlotUtils.indexToId(slot),
                        amount == held.getCount() ? 1 : 0, net.minecraft.world.inventory.ContainerInput.THROW, mc.player);
                    nextSync = 0;
                }
                if (localStage.equals("issued") || localStage.equals("ready") && str(localOffer, "phase").equals("drop")) requestSync();
                if (ticks - localSince > 1200 && !str(localOffer, "phase").equals("drop")) failLocal("Transfer preparation timed out; retrying the exchange");
            } else if (str(localOffer, "phase").equals("gather")) {
                localStage = "meeting";
                if (donor() && !receipt.has("proposal")) {
                    int surplus = b.crewResourceCount(resource) - b.crewResourceTarget(resource), chosen = -1;
                    for (int i = 0; i < 36; i++) {
                        ItemStack item = mc.player.getInventory().getItem(i);
                        if (b.crewTransferUnits(resource, item) <= 0) continue;
                        if (chosen < 0 || resource == CrewInventory.PICKS && item.getMaxDamage() - item.getDamageValue()
                            > mc.player.getInventory().getItem(chosen).getMaxDamage() - mc.player.getInventory().getItem(chosen).getDamageValue()) chosen = i;
                    }
                    if (chosen < 0 || surplus <= 0) { failLocal("Donor has no spare working stock"); return true; }
                    ItemStack item = mc.player.getInventory().getItem(chosen);
                    int limit = Math.min(surplus, num(localOffer, "remaining"));
                    int perItem = b.crewTransferUnits(resource, item);
                    int amount = dev.monocle.client.systems.bots.BotActions.nextDropCount(Math.max(1, limit / perItem), item.getCount());
                    JsonObject proposal = new JsonObject(); proposal.addProperty("slot", chosen); proposal.addProperty("count", amount);
                    proposal.addProperty("units", Math.multiplyExact(perItem, amount));
                    proposal.add("stack", ItemStack.CODEC.encodeStart(mc.player.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), item).getOrThrow());
                    receipt.add("proposal", proposal); saveLocal();
                }
            }
            b.crewInventoryStatus("Crew exchange: " + localStage + " · " + CrewInventory.name(resource));
            return true;
        }
        boolean nearPartner() {
            var partner = mc.level.getPlayerByUUID(UUID.fromString(str(localOffer, donor() ? "recipient" : "donor")));
            if (partner == null || !partner.onGround() || !mc.player.onGround() || mc.player.distanceToSqr(partner) > 9 || mc.player.distanceToSqr(partner) < 2.25
                || Math.abs(partner.getY() - mc.player.getY()) > .5) return false;
            Vec3 difference = partner.position().subtract(mc.player.position());
            if (!mc.level.noCollision(mc.player, mc.player.getBoundingBox().expandTowards(difference))) return false;
            mc.player.setYRot((float) Rotations.getYaw(partner.position())); mc.player.setXRot(10);
            return true;
        }
        void requestSync() {
            if (ticks < nextSync || mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()) return;
            nextSync = ticks + 20;
            mc.getConnection().send(HighwayBuilder.cursorSyncRequest(mc.player.inventoryMenu.containerId,
                net.minecraft.network.HashedStack.create(mc.player.inventoryMenu.getCarried(), mc.getConnection().decoratedHashOpsGenenerator())));
        }
        @meteordevelopment.orbit.EventHandler
        private void inventory(dev.monocle.client.events.packets.InventoryEvent event) {
            if (!mc.isSameThread() || !localBusy() || localOffer == null || !localOffer.has("proposal") || !Utils.canUpdate()
                || event.packet.containerId() != mc.player.inventoryMenu.containerId || event.packet.items().size() < 45) return;
            ItemStack expected = stack(localOffer.getAsJsonObject("proposal"));
            int total = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack s = event.packet.items().get(i < 9 ? i + 36 : i);
                if (ItemStack.isSameItemSameComponents(s, expected)) total += s.getCount();
            }
            if (localStage.equals("preparing")) {
                if (!donor() && CrewInventory.capacity(mc.player.getInventory(), expected, 1) < num(localOffer.getAsJsonObject("proposal"), "count")) return;
                receipt.addProperty("baseline", total); localStage = "ready"; localSince = ticks; saveLocal();
            }
            else if (localStage.equals("issued") && donor()) {
                int slot = num(receipt.getAsJsonObject("proposal"), "slot");
                ItemStack after = event.packet.items().get(slot < 9 ? slot + 36 : slot);
                int amount = num(localOffer.getAsJsonObject("proposal"), "count");
                if (dev.monocle.client.systems.bots.BotActions.confirmedDrop(num(receipt, "before"), amount, total, num(receipt, "beforeSlot"), after.getCount())
                    && (after.isEmpty() || ItemStack.isSameItemSameComponents(after, expected))) { localStage = "sent"; saveLocal(); }
            } else if (!donor() && localStage.equals("ready") && str(localOffer, "phase").equals("drop")
                && total >= num(receipt, "baseline") + num(localOffer.getAsJsonObject("proposal"), "count")) { localStage = "received"; saveLocal(); }
        }
        void pickup(int itemId, int collectorId, int amount) {
            if (!Utils.canUpdate() || localOffer == null || donor() || !localStage.equals("ready") || !str(localOffer, "phase").equals("drop")
                || collectorId != mc.player.getId() || amount <= 0 || !localOffer.has("proposal")) return;
            if (mc.level.getEntity(itemId) instanceof ItemEntity item && ItemStack.isSameItemSameComponents(item.getItem(), stack(localOffer.getAsJsonObject("proposal")))) {
                received += Math.min(amount, item.getItem().getCount());
                receipt.addProperty("received", received);
                if (received >= num(localOffer.getAsJsonObject("proposal"), "count")) localStage = "received";
                saveLocal();
            }
        }
        String summary() {
            int[] total = new int[CrewInventory.RESOURCES]; int known = 0;
            for (UUID member : participants.keySet()) {
                JsonObject r = resourceExchange.worker(member); if (r == null) continue;
                JsonObject ledger = r.getAsJsonObject("inventory"); known++;
                for (int resource = 0; resource < CrewInventory.RESOURCES; resource++) total[resource] += CrewInventory.available(ledger, resource);
            }
            return "Crew pool · " + known + " reporting · " + total[0] + " paving · " + total[1] + " usable picks · " + total[2] + " food · " + total[3] + " filler · " + total[4] + " echests (known storage only)"
                + (resourceExchange.hostOffer == null ? "" : "\nTransfer · " + memberName(UUID.fromString(str(resourceExchange.hostOffer, "donor"))) + " → " + memberName(UUID.fromString(str(resourceExchange.hostOffer, "recipient")))
                + " · " + str(resourceExchange.hostOffer, "phase") + " · " + str(resourceExchange.hostOffer, "remaining") + " " + CrewInventory.name(num(resourceExchange.hostOffer, "resource")));
        }
        void resolveLocal() {
            if (localOffer != null && localStage.equals("uncertain")) {
                if (receipt == null) receipt = new JsonObject();
                localStage = "cancelled"; need = -1; saveLocal(); listen(false);
                if (builder().crewRestockIdle()) builder().crewFinishResourceWait();
                serviceReturning = detachedSupply() && !builder().crewNeedsCleanup();
            }
        }
        void close() {
            if (Utils.canUpdate()) builder().crewCloseSharedSupply();
            if (localOffer != null && receipt != null) {
                localStage = CrewInventory.unresolvedReceipt(receipt) ? "uncertain" : "cancelled";
                saveLocal();
            }
            listen(false); resourceExchange.hostOffer = localOffer = receipt = null; need = priorNeed = -1; localStage = "idle";
        }
    }

    private void tickReturn() {
        if (pendingReturn != null && builder().crewCanReceive()) { send(pendingReturn); pendingReturn = null; }
        if (!returnAuthorized || thrown || borrowed.isEmpty() || stopped || !live()) return;
        var owner = mc.level.getPlayerByUUID(returnOwner);
        if (owner == null || mc.player.distanceToSqr(owner) > 9 || Math.abs(mc.player.getY() - owner.getY()) > .5 || !mc.player.onGround() || !owner.onGround()) return;
        if (mc.level.players().stream().anyMatch(p -> p != mc.player && p != owner && (p.distanceToSqr(owner) < 12.25 || p.distanceToSqr(mc.player) < 12.25))) return;
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.containerMenu.getCarried().isEmpty()) return;
        for (int step = 0; step <= 6; step++) {
            Vec3 point = mc.player.position().lerp(owner.position(), step / 6.0);
            BlockPos feet = BlockPos.containing(point);
            if (!mc.level.getBlockState(feet.below()).isFaceSturdy(mc.level, feet.below(), Direction.UP)
                || !mc.level.getBlockState(feet).getFluidState().isEmpty() || !mc.level.getBlockState(feet).getCollisionShape(mc.level, feet).isEmpty()
                || !mc.level.getBlockState(feet.above()).getFluidState().isEmpty() || !mc.level.getBlockState(feet.above()).getCollisionShape(mc.level, feet.above()).isEmpty()) return;
        }
        int slot = returnSlot(mc.player.getInventory(), borrowed);
        if (slot < 0) return;
        mc.player.setYRot((float) Rotations.getYaw(owner.position())); mc.player.setXRot(10);
        if (++facingTicks < 2) return;
        sendingReturn = true;
        try { InvUtils.drop().slot(slot); thrown = true; } finally { sendingReturn = false; }
        swarm.info("Returning the tracked supply shulker to %s. Waiting for their pickup confirmation.", owner.getName().getString());
    }

    @Override protected boolean isHost() { return swarm.isHost(); }
    @Override protected UUID hostIdentity() { return mc.getUser().getProfileId(); }
    @Override protected boolean worldAvailable() { return Utils.canUpdate(); }
    @Override protected void info(String format,Object... args) { swarm.info(format,args); }
    @Override protected void warning(String format,Object... args) { swarm.warning(format,args); }
    @Override protected BlockPos point(int x,int y,int z) { return new BlockPos(x,y,z); }
    @Override protected int x(BlockPos p) { return p.getX(); }
    @Override protected int y(BlockPos p) { return p.getY(); }
    @Override protected int z(BlockPos p) { return p.getZ(); }
    @Override protected long packed(BlockPos p) { return p.asLong(); }
    @Override protected BlockPos unpacked(long p) { return BlockPos.of(p); }
    @Override protected boolean hostRowResolved(BlockPos p) { return builder().crewRowResolved(p); }

    @Override protected boolean nativeReady(UUID worker) { return swarm.tasks().nativeReady(worker); }
    @Override protected boolean localReconnectReady() { return builder().crewCanReconnect(); }
    @Override protected void checkpointJobs() { swarm.checkpointJobs(); }
}
