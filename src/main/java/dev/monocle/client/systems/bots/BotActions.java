package dev.monocle.client.systems.bots;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.packets.InventoryEvent;
import dev.monocle.client.events.packets.ContainerSlotUpdateEvent;
import dev.monocle.client.events.game.OpenScreenEvent;
import dev.monocle.client.events.packets.PacketEvent;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.combat.KillAura;
import dev.monocle.client.systems.modules.combat.CrystalAura;
import dev.monocle.client.systems.modules.combat.AutoTotem;
import dev.monocle.client.systems.modules.movement.elytrafly.ElytraFly;
import dev.monocle.client.systems.modules.movement.elytrafly.ElytraFlightModes;
import dev.monocle.client.systems.modules.movement.speed.Speed;
import dev.monocle.client.systems.modules.movement.speed.SpeedModes;
import dev.monocle.client.systems.modules.player.AutoEat;
import dev.monocle.client.systems.modules.player.AutoGap;
import dev.monocle.client.systems.modules.player.AutoMend;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import dev.monocle.client.systems.modules.world.HighwayPlan;
import dev.monocle.client.systems.modules.world.PrinterHelper;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.player.CustomPlayerInput;
import dev.monocle.client.utils.player.Rotations;
import dev.monocle.client.utils.player.SlotUtils;
import dev.monocle.client.utils.world.PrinterFlight;
import dev.monocle.client.utils.world.TickRate;
import dev.monocle.coordinator.PlayerObservation.Position;
import dev.monocle.coordinator.StashCatalog;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

import static dev.monocle.client.MonocleClient.mc;

/** One client-thread native action. The host owns scheduling, profiles and teleport commands. */
public final class BotActions {
    private static final Set<String> TYPES = Set.of("Travel", "StashHunt", "StashScan", "StashVisit", "StashResupply", "StashDeposit", "KitTransferPlan", "StashSortPlan", "DropItems", "Wait", "Modules", "Tpa", "SetProfile", "Highway", "RecoverSupplies");
    private static final Set<String> EXTERNAL = Set.of("Highway", "SetProfile", "KitTransferPlan", "StashSortPlan");
    private static final Set<String> STASH_ACTIONS = Set.of("StashScan", "StashVisit", "StashResupply", "StashDeposit");
    private static final int STASH_FLIGHT_MIN_DURABILITY = 64, STASH_FLIGHT_LANDING_RESERVE = 32;
    private static final Set<String> RESERVED_MODULES = Set.of("highway-builder", "printer-helper");
    private static final List<String> CRYSTAL_GUARD_MODULES = List.of("auto-totem", "auto-gap", "auto-eat", "auto-armor", "crystal-aura");
    private final Bots bots;
    private final boolean highwayOwned;
    private BotPersonalSupplies personalSupplies;
    private boolean stashMending;
    private boolean stashMendDeferred;
    private int stashMendDamage=-1,stashMendIdle;
    private dev.monocle.coordinator.ActionProgress progress = new dev.monocle.coordinator.ActionProgress();

    static dev.monocle.coordinator.ActionProgress.Observation progress(String phase, String expected, String milestone, BlockPos target, boolean waiting, boolean navigation) {
        return new dev.monocle.coordinator.ActionProgress.Observation(phase, expected, milestone, target == null ? "" : target.toShortString(),
            target == null ? Double.MAX_VALUE : mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(target)), 0, waiting, navigation);
    }
    JsonObject actionProgress() { return progress.report(); }
    boolean progressPaused() { return progress.paused(); }
    void resumeProgress() { progress.resume(); }
    void awaitingProgress(String expected) {
        progress.observe(progress(type(), expected, "", null, true, false), System.nanoTime() / 1_000_000);
    }
    void watchProgress() {
        if (!state.equals("Running") || suspended || suspendRequested || !Utils.canUpdate() || !dev.monocle.client.utils.network.WorldReadiness.INSTANCE.ready()) { progress.resetClock(); return; }
        if(!mc.player.isAlive()){progress.resetClock();return;}
        dev.monocle.coordinator.ActionProgress.Observation observation;
        boolean healthyWait = mc.isPaused() || TickRate.INSTANCE.getTimeSinceLastTick() >= 1.5f
            || Modules.get().get(AutoEat.class).eating || Modules.get().get(AutoGap.class).isEating() || mc.player.isUsingItem() || Modules.get().get(KillAura.class).attacking;
        String waitingFor = "Eating / combat / loaded server ticks";
        healthyWait |= stashMending || personalSupplies != null && personalSupplies.busy();
        if ((STASH_ACTIONS.contains(type())||type().equals("DropItems")&&action.has("handoff")&&action.get("handoff").getAsBoolean()) && !dev.monocle.client.utils.network.WorldReadiness.INSTANCE.inventoryReady()) {
            healthyWait = true; waitingFor = "Server inventory synchronization";
        }
        if (!healthyWait && STASH_ACTIONS.contains(type()) && !stashHomeArrived) {
            long now = System.currentTimeMillis();
            healthyWait = stashHomeWaiting(stashHomeAttempts,now,homeReadyAt,bots.stashHomeReadyAt(homeCooldownKey(),action.get("homeCooldownTicks").getAsLong()*50L));
            waitingFor = homeReadyAt > now ? "Home warmup" : homeReadyAt == 0 ? "Home cooldown / retry" : "Home arrival / retry";
        }
        if (healthyWait) observation = progress(type(), waitingFor, "", null, true, false);
        else if (type().equals("Highway") && bots.crew.localAssigned()) observation = Modules.get().get(HighwayBuilder.class).actionProgress();
        else if (STASH_ACTIONS.contains(type()) && !stashHomeArrived) observation = progress(type(), "Home arrival confirmation", "", null, false, false);
        else if (stashScan != null) observation = stashScan.progress();
        else if (stashResupply != null) observation = stashResupply.progress();
        else if (stashDeposit != null) observation = stashDeposit.progress();
        else if (survey != null) observation = survey.progress();
        else if (type().equals("Travel") && !(action.has("follow") && action.get("follow").getAsBoolean())) {
            Vec3 goal = new Vec3(action.get("x").getAsDouble(), action.get("y").getAsDouble(), action.get("z").getAsDouble());
            observation = new dev.monocle.coordinator.ActionProgress.Observation("Travel", "Safe destination arrival", "", goal.toString(), mc.player.position().distanceTo(goal), 0, false, !nativeBusy());
        } else observation = progress(type(), switch(type()) {
            case "DropItems" -> "Recipient alignment / server drop confirmation";
            case "Tpa" -> "Host handshake / teleport arrival";
            case "Highway" -> "Host highway assignment";
            case "KitTransferPlan" -> "Host transfer plan";
            case "StashSortPlan" -> "Host sorting ledger";
            case "RecoverSupplies" -> "Supply journal reconciliation / landing";
            case "Travel" -> "Subject visibility / formation updates";
            default -> "Configured interval / action completion";
        }, type().equals("DropItems") ? Integer.toString(remaining) : "", null,
            Set.of("Wait", "Modules").contains(type()) || type().equals("Tpa") && tpaSent && elapsed < action.get("warmupTicks").getAsInt() || type().equals("Travel"), false);
        if (!healthyWait && !EXTERNAL.contains(type()) && nativeBusy()) observation = new dev.monocle.coordinator.ActionProgress.Observation(
            observation.phase(), "Existing native controller release", observation.milestone(), observation.target(), observation.distance(), observation.effort(), observation.waiting(), false);
        var decision = progress.observe(observation, System.nanoTime() / 1_000_000);
        if(decision==dev.monocle.coordinator.ActionProgress.Decision.Pause&&observation.navigation()&&stashResupply!=null
            &&mc.player.containerMenu.getCarried().isEmpty()&&stashResupply.deferSourceApproach("Navigation stalled after two replans; source remains unresolved")){
            dev.monocle.client.MonocleClient.LOG.warn("[Workers] Deferring exhausted source approach instead of pausing the transfer: {} · {}",progress.report(),detail);
            stashResupply.close();stopStashNavigation();resetFlightRoutes(true);brakeFlight();progress.resume();
            detail="Deferred stalled source; continuing through other containers";return;
        }
        if (decision == dev.monocle.coordinator.ActionProgress.Decision.Replan) {
            if (type().equals("Highway")) Modules.get().get(HighwayBuilder.class).crewNudge();
            else { stopStashNavigation(); resetFlightRoutes(true); }
            dev.monocle.client.MonocleClient.LOG.warn("[Workers] Navigation stall recovery: {}", progress.report());
        }
    }
    private JsonObject action, pending, originals = new JsonObject(), result, deliveryAnchor;
    private Vec3 deliveryGoal;
    private String state = "Complete", detail = "Idle", dimension = "", pendingStashFailure;
    private int elapsed, remaining, dropWait, lastTick = -1, launchTick = -1, launchAttempts, bodyguardMissingTicks, bodyguardTpaAt;
    private boolean suspended, suspendRequested, checkpoint, armed, acknowledged, listening, modulesApplied, modulesArmed, restoredModuleLease, tpaSent, runOnly, enabledFly, stashHandoff;
    private long homeReadyAt;
    private int stashHomeAttempts;
    private boolean stashHomeArrived, stashHomeSent;
    public JsonObject stashTravel() {
        if (!type().equals("StashVisit")) return null;
        JsonObject travel=new JsonObject();travel.addProperty("name",action.get("name").getAsString());travel.addProperty("homeSent",stashHomeSent);travel.addProperty("arrived",stashHomeArrived);return travel;
    }
    private ClientInput previousInput;
    private LocalPlayer inputPlayer;
    private final CustomPlayerInput input = new CustomPlayerInput();
    private final PrinterFlight.LocalRoute localFlight = new PrinterFlight.LocalRoute();
    private final WalkingRoute walkingRoute=new WalkingRoute();
    private BotStashHunt survey;
    private BotStashScan stashScan;
    private BotStashResupply stashResupply;
    private BotStashDeposit stashDeposit;
    private BlockPos scanFlightTarget;
    private Vec3 scanCoverageFlightGoal;
    private List<Vec3> scanFlightRoute = List.of();
    private final List<Vec3> stashFlightTrail = new ArrayList<>();
    private int scanFlightStep, scanFlightRetry, landingRouteStep = -1, landingFallbackRetry;
    private BlockPos scanFlightWatchTarget;
    private Vec3 scanFlightProgress;
    private int scanFlightProgressTick,scanFlightRecoveries;
    private double stashCruiseSpeed;
    private boolean scanFlightEscaping;
    private JsonObject stashFlightDiagnostic;
    private Vec3 landingFallbackTarget;
    private Iterator<BlockPos.MutableBlockPos> landingCandidates;
    private Vec3 landingSearchOrigin;
    private Vec3 landingLiftTarget;
    private Vec3 stashClearanceTarget;
    private boolean landing;
    private int stashCollisionWalkUntil;
    private BlockPos stashFlightAttemptTarget;
    private int stashFlightFailures, stashFlightRetryAt;
    private Vec3 stashWalkProgress;
    private int stashWalkProgressTick;
    private final Map<String,Long> emptyStashSources=new LinkedHashMap<>();
    private UUID emptyStashTask;
    void bindTask(UUID task){emptyStashTask=bindEmptySources(emptyStashSources,emptyStashTask,task);}
    static UUID bindEmptySources(Map<String,Long> sources,UUID previous,UUID next){
        Objects.requireNonNull(next);
        if(!next.equals(previous))sources.clear();
        return next;
    }
    public JsonObject stashTelemetry() { return stashScan == null ? null : stashScan.telemetry(); }
    void scanCheckpoint(java.nio.file.Path path,String binding){if(stashScan!=null)stashScan.checkpoint(path,binding);}
    boolean independentScanCheckpoint(){return stashScan!=null&&stashScan.independentCheckpoint();}
    List<JsonObject> scanUploads(int sentThrough){return stashScan==null?List.of():stashScan.uploadWindow(sentThrough);}
    public boolean ownsScanRead(BlockPos pos){return stashScan!=null&&state.equals("Running")&&!suspended&&!suspendRequested&&stashScan.ownsRead(pos);}
    public boolean hasStashPending() { return stashScan != null ? stashScan.hasPending() : stashDeposit != null && stashDeposit.hasPending(); }
    public JsonObject stashPending() { return stashScan != null ? stashScan.pending() : stashDeposit!=null?stashDeposit.pending():null; }
    public void stashExport(JsonObject export){if(stashScan!=null)stashScan.export(export);}
    public int stashDelivery() { return stashScan != null ? stashScan.delivery() : stashDeposit!=null?stashDeposit.delivery():0; }
    public void acknowledgeStash(int delivery) { if (stashScan != null) stashScan.acknowledge(delivery); }
    public void acknowledgeStashDeposit(JsonObject ack){if(stashDeposit!=null)stashDeposit.acknowledge(ack);}
    public JsonObject stashWithdrawal(){return stashResupply==null||!stashResupply.hasWithdrawal()?null:stashResupply.result();}
    public JsonObject stashDeposit(){return stashDeposit==null||!stashDeposit.hasTouched()?null:stashDeposit.result();}
    public boolean stashFlightActive(){return action!=null&&Set.of("StashScan","StashResupply","StashDeposit").contains(type())&&Utils.canUpdate()&&mc.player.isFallFlying();}
    boolean stashHandoff(){return stashHandoff&&!suspendRequested&&!suspended;}
    static boolean compatibleStashFlight(JsonObject previous,JsonObject next,String world,Position position){
        return previous!=null&&next!=null&&STASH_ACTIONS.contains(previous.get("type").getAsString())&&STASH_ACTIONS.contains(next.get("type").getAsString())
            &&(!previous.has("dimension")||previous.get("dimension").getAsString().equals(world))
            &&(!next.has("dimension")||next.get("dimension").getAsString().equals(world))&&StashCatalog.nearby(next,position);
    }
    boolean prepareNext(JsonObject next){
        if(stashNavigation!=null&&stashNavigation.closing()&&!stopStashNavigation())return false;
        if(!stashHandoff)return true;
        if(stashHandoff()&&Utils.canUpdate()&&dimension.equals(mc.level.dimension().identifier().toString())&&compatibleStashFlight(action,next,dimension,new Position(mc.player.getX(),mc.player.getY(),mc.player.getZ())))return true;
        if(requestSuspend())return true;
        tick();return false;
    }
    private BotSupplyRecovery recovery;
    private boolean recoveryReady;
    public boolean recoveryReady() { return recoveryReady; }

    public BotActions(Bots bots) { this(bots,false); }
    public BotActions(Bots bots,boolean highwayOwned) { this.bots = Objects.requireNonNull(bots);this.highwayOwned=highwayOwned; }
    String detail(){return detail;}
    boolean landForProfile(){clientThread();listen(true);return landBeforeHandoff();}

    /** Validate at the Lua/network boundary before acquiring any control or changing a module. */
    public static JsonObject validate(JsonObject source) {
        if (source == null || source.size() > 32+(source.has("kitIdentities")?1:0)+(source.has("mixedLoad")?1:0)) throw new IllegalArgumentException("Invalid native action");
        JsonObject a = source.deepCopy();
        String type = string(a, "type", 32);
        if (!TYPES.contains(type)) throw new IllegalArgumentException("Unsupported native action: " + type);
        switch (type) {
            case "StashSortPlan" -> a=dev.monocle.coordinator.StashSortExecution.checkedRequest(a);
            case "KitTransferPlan" -> {if(a.has("step"))a=dev.monocle.coordinator.StashTransferTeam.checkedRequest(a);else{if(a.size()!=2)throw new IllegalArgumentException("Kit planning accepts only type and index");integer(a,"index",1,128);}}
            case "StashScan", "StashVisit" -> a = dev.monocle.coordinator.StashCatalog.plan(a);
            case "StashResupply" -> {
                if(a.has("mixedLoad")&&(!a.get("mixedLoad").isJsonPrimitive()||!a.getAsJsonPrimitive("mixedLoad").isBoolean()||!a.has("targetStash")||!a.has("knownKitTypeIds")||!a.has("kitTypeId")||!string(a,"depositMode",16).equals("Carry")))throw new IllegalArgumentException("Mixed collection requires a carried stash kit batch");
                a=dev.monocle.coordinator.StashCatalog.plan(a);JsonArray picks=a.getAsJsonArray("picks");if(picks==null||picks.isEmpty()||picks.size()>(a.has("sortBatch")?64:54))throw new IllegalArgumentException("Invalid selected stash containers");
                for(JsonElement value:picks){JsonObject p=value.getAsJsonObject();integer(p,"x",-29_900_000,29_900_000);integer(p,"y",-2048,2048);integer(p,"z",-29_900_000,29_900_000);integer(p,"slot",0,215);identifier(string(p,"resource",128));}
                if(a.has("kitTypeId")){UUID.fromString(string(a,"kitTypeId",36));integer(a,"count",1,36);if(!a.has("kitExemplar"))throw new IllegalArgumentException("Kit withdrawal needs its learned exemplar");optionalInteger(a,"pickIndex",0,0,picks.size());}
                if(a.has("transferAll")&&a.get("transferAll").getAsBoolean()&&(!a.has("kitTypeId")||!a.has("depositMode")||!a.get("depositMode").getAsString().equals("Carry")))throw new IllegalArgumentException("Transfer all needs a carried kit batch for stash delivery");
                if(a.has("depositMode")&&!Set.of("Carry","Ender Chest").contains(string(a,"depositMode",16)))throw new IllegalArgumentException("Invalid stash deposit mode");
                if(a.has("targetStash")&&a.getAsJsonObject("targetStash").has("targetColumn"))a.add("targetStash",dev.monocle.coordinator.StashCatalog.plan(a.getAsJsonObject("targetStash")));
                if(a.has("knownKitTypeIds")){
                    JsonArray ids=a.getAsJsonArray("knownKitTypeIds");
                    if(!a.has("targetStash")||ids.size()>512)throw new IllegalArgumentException("Incidental kits need a destination stash and at most 512 known types");
                    Set<String> unique=new HashSet<>();for(JsonElement id:ids)if(!unique.add(UUID.fromString(id.getAsString()).toString()))throw new IllegalArgumentException("Duplicate known kit type");
                }
            }
            case "StashDeposit" -> {
                a=dev.monocle.coordinator.StashCatalog.plan(a);UUID.fromString(string(a,"kitTypeId",36));integer(a,"count",1,36);
                if(a.has("protectedBoxes")){
                    JsonArray boxes=a.getAsJsonArray("protectedBoxes");if(boxes.size()>36)throw new IllegalArgumentException("Too many protected inventory boxes");
                    for(JsonElement e:boxes){JsonObject box=e.getAsJsonObject();identifier(string(box,"item",128));string(box,"name",128);integer(box,"quantity",1,64);if(box.getAsJsonObject("items").size()>54)throw new IllegalArgumentException("Protected kit manifest too large");}
                }
            }
            case "StashHunt" -> a = BotStashHunt.validate(a);
            case "RecoverSupplies" -> {
                if (a.has("x") || a.has("y") || a.has("z")) {
                    number(a, "x", -29_999_984, 29_999_984); number(a, "z", -29_999_984, 29_999_984); number(a, "y", -2048, 2048);
                }
                if (a.has("execution")) UUID.fromString(string(a, "execution", 36));
                if (a.has("inspectTransfersBefore")) {
                    double cutoff = number(a, "inspectTransfersBefore", 1, System.currentTimeMillis());
                    if (cutoff != Math.rint(cutoff)) throw new IllegalArgumentException("Expected integer inspection cutoff");
                }
                optionalInteger(a, "searchRadius", 16, 4, 32);
                optionalInteger(a, "retryTicks", 100, 20, 1200);
                optionalNumber(a, "flyBeyond", 8, 4, 32);
            }
            case "Travel" -> {
                if (a.has("follow") && a.get("follow").getAsBoolean()) {
                    UUID.fromString(string(a, "target", 36));
                    if (a.has("adaptiveFlight") && (!a.get("adaptiveFlight").isJsonPrimitive() || !a.getAsJsonPrimitive("adaptiveFlight").isBoolean()))
                        throw new IllegalArgumentException("Expected boolean adaptiveFlight");
                    optionalInteger(a, "ticks", 0, 0, 1_728_000);
                    if (a.has("bodyguard") && a.get("bodyguard").getAsBoolean()) {
                        optionalInteger(a,"workerCount",1,1,3);optionalInteger(a,"workerIndex",0,0,a.get("workerCount").getAsInt()-1);
                        if(!string(a,"targetName",16).matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("Invalid bodyguard subject name");
                    }
                    if (a.has("crystalGuard") && a.get("crystalGuard").getAsBoolean()) {
                        if (!a.has("bodyguard") || !a.get("bodyguard").getAsBoolean()) throw new IllegalArgumentException("Crystal Guard requires Bodyguard travel");
                        JsonArray names=a.has("combatTargets")?a.getAsJsonArray("combatTargets"):new JsonArray();
                        if(names.size()>16)throw new IllegalArgumentException("Choose at most 16 crystal targets");
                        for(JsonElement name:names)if(!name.isJsonPrimitive()||!name.getAsString().matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("Invalid crystal target name");
                        a.add("combatTargets",names);
                        JsonArray protectedPlayers=a.has("protectedPlayers")?a.getAsJsonArray("protectedPlayers"):new JsonArray();
                        for(JsonElement player:protectedPlayers)UUID.fromString(player.getAsString());
                        String subject=a.get("target").getAsString();
                        boolean subjectProtected=false;for(JsonElement player:protectedPlayers)if(player.getAsString().equals(subject))subjectProtected=true;
                        if(!subjectProtected)protectedPlayers.add(subject);
                        if(protectedPlayers.size()>16)throw new IllegalArgumentException("Too many protected crewmates");
                        a.add("protectedPlayers",protectedPlayers);
                    }
                } else {
                    number(a, "x", -29_999_984, 29_999_984); number(a, "z", -29_999_984, 29_999_984); number(a, "y", -2048, 2048);
                }
                optionalNumber(a, "radius", 2, .15, 8);
                optionalNumber(a, "flyBeyond", 6, 4, 32);
            }
            case "DropItems" -> {
                identifier(string(a, "item", 128)); integer(a, "count", 1, 1_048_576);
                if(a.has("kitTypeId"))UUID.fromString(string(a,"kitTypeId",36));
                if (a.has("recipient")) UUID.fromString(string(a, "recipient", 36));
                if(a.has("handoff")&&(!a.get("handoff").isJsonPrimitive()||!a.getAsJsonPrimitive("handoff").isBoolean()||!a.has("recipient")))throw new IllegalArgumentException("Handoff needs a recipient and boolean flag");
                if(a.has("deliveryAnchor")){if(!a.has("handoff")||!a.get("handoff").getAsBoolean())throw new IllegalArgumentException("Delivery anchor requires handoff");a.add("deliveryAnchor",BotDelivery.checked(a.getAsJsonObject("deliveryAnchor")));}
            }
            case "Wait" -> integer(a, "ticks", 0, 1_728_000);
            case "Modules" -> {
                if (!a.has("modules") || !a.get("modules").isJsonObject() || a.getAsJsonObject("modules").size() == 0 || a.getAsJsonObject("modules").size() > 64)
                    throw new IllegalArgumentException("Specify between 1 and 64 modules");
                for (var entry : a.getAsJsonObject("modules").entrySet()) {
                    if (!entry.getKey().matches("[a-z0-9-]{1,64}") || RESERVED_MODULES.contains(entry.getKey())
                        || !entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isBoolean())
                        throw new IllegalArgumentException("Invalid or separately managed module: " + entry.getKey());
                }
                optionalInteger(a, "ticks", 0, 0, 1_728_000);
            }
            case "Tpa" -> {
                String target = string(a, "target", 36);
                if (!target.matches("[A-Za-z0-9_]{1,16}")) UUID.fromString(target);
                if(a.has("external")&&(!a.get("external").isJsonPrimitive()||!a.getAsJsonPrimitive("external").isBoolean()))throw new IllegalArgumentException("Expected boolean external TPA flag");
                if(a.has("external")&&a.get("external").getAsBoolean()&&!string(a,"targetName",16).matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("External TPA needs the target's exact username");
                optionalInteger(a, "warmupTicks", 300, 0, 72_000);
                optionalInteger(a, "acceptDelayTicks", 10, 0, 200);
                optionalInteger(a, "timeoutTicks", 1200, 1, 1_728_000);
                optionalNumber(a, "radius", 8, 1, 16);
                if(a.has("arrivalStash"))a.add("arrivalStash",StashCatalog.plan(a.getAsJsonObject("arrivalStash")));
                if (a.get("warmupTicks").getAsInt() >= a.get("timeoutTicks").getAsInt()) throw new IllegalArgumentException("Teleport timeout must exceed warmup");
            }
            case "SetProfile" -> { String name = string(a, "name", 128); if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) throw new IllegalArgumentException("Invalid profile name"); }
            default -> {} // Highway is validated and executed by the native crew coordinator.
        }
        if (a.has("kitExemplar")) {
            if (!a.has("kitTypeId") || !a.get("kitExemplar").isJsonObject() || a.getAsJsonObject("kitExemplar").size() > 54
                || !a.has("incomplete") || !a.get("incomplete").isJsonPrimitive() || !a.getAsJsonPrimitive("incomplete").isBoolean())
                throw new IllegalArgumentException("Invalid kit exemplar");
            for (var entry : a.getAsJsonObject("kitExemplar").entrySet()) { identifier(entry.getKey()); integer(a.getAsJsonObject("kitExemplar"),entry.getKey(),0,1728); }
        }
        if(a.has("sortBatch")&&(!Set.of("StashResupply","StashDeposit").contains(type)||!a.has("kitIdentities")||!a.getAsJsonObject("kitIdentities").has(string(a,"kitTypeId",36))))throw new IllegalArgumentException("Sorting batches need an explicit kit identity");
        if (a.has("dimension")) identifier(string(a, "dimension", 128));
        if(a.has("kitIdentities")){
            if(!a.get("kitIdentities").isJsonObject())throw new IllegalArgumentException("Expected kit identities object");
            JsonObject identities=a.getAsJsonObject("kitIdentities");if(identities.size()>512)throw new IllegalArgumentException("Too many kit identities");
            for(var entry:identities.entrySet()){
                if(!UUID.fromString(entry.getKey()).toString().equals(entry.getKey())||!entry.getValue().isJsonObject())throw new IllegalArgumentException("Invalid kit identity");
                JsonObject identity=entry.getValue().getAsJsonObject();if(!identity.keySet().equals(Set.of("familyId","items"))||!identity.get("items").isJsonObject())throw new IllegalArgumentException("Invalid kit identity fields");
                String family=string(identity,"familyId",36);if(!UUID.fromString(family).toString().equals(family))throw new IllegalArgumentException("Invalid kit family");
                JsonObject items=identity.getAsJsonObject("items");if(items.size()>54)throw new IllegalArgumentException("Too many kit identity items");
                for(var item:items.entrySet()){identifier(item.getKey());integer(items,item.getKey(),1,1);}
            }
        }
        if(a.has("requireLocal")&&(!a.get("requireLocal").isJsonPrimitive()||!a.getAsJsonPrimitive("requireLocal").isBoolean()))throw new IllegalArgumentException("Expected boolean requireLocal");
        return a;
    }

    public void start(JsonObject next) {
        clientThread();
        if (state.equals("Running") || pending != null) throw new IllegalStateException("Finish or reconcile the current action first");
        JsonObject validated=validate(next);
        boolean keepFlight=stashHandoff()&&Utils.canUpdate()&&dimension.equals(mc.level.dimension().identifier().toString())&&compatibleStashFlight(action,validated,dimension,new Position(mc.player.getX(),mc.player.getY(),mc.player.getZ()));
        if(stashHandoff&&Utils.canUpdate()&&!mc.player.onGround()&&!keepFlight)throw new IllegalStateException("Land before starting an incompatible action");
        release(keepFlight);
        resetFlightRoutes(keepFlight);
        resetStashFlightProgress();
        stashHandoff=false;
        stashFlightAttemptTarget=null;stashFlightFailures=stashFlightRetryAt=0;
        action = validated; pending = null; pendingStashFailure = null; originals = new JsonObject(); result = null;
        progress = new dev.monocle.coordinator.ActionProgress();
        survey = type().equals("StashHunt") ? new BotStashHunt(action) : null;
        stashScan = type().equals("StashScan") ? new BotStashScan(action) : null;
        stashDeposit=type().equals("StashDeposit")?new BotStashDeposit(action):null;
        if (stashScan != null) {
            var selector=Modules.get().get(dev.monocle.client.systems.modules.world.SchematicSelector.class);
            if(selector.isActive())selector.disable();
        }
        recoveryReady = false;
        recovery = Set.of("RecoverSupplies", "Highway").contains(type()) ? new BotSupplyRecovery(bots, action.has("recovery") ? validateRecovery(action.getAsJsonObject("recovery")) : type().equals("RecoverSupplies") ? action : new JsonObject()) : null;
        elapsed = dropWait = launchAttempts = bodyguardMissingTicks = bodyguardTpaAt = 0; launchTick = lastTick = -1;
        deliveryAnchor=action.has("deliveryAnchor")?action.getAsJsonObject("deliveryAnchor").deepCopy():null;deliveryGoal=null;
        remaining = type().equals("DropItems") ? action.get("count").getAsInt() : action.has("ticks") ? action.get("ticks").getAsInt() : 0;
        resetStashHome();stashHomeAttempts=0;
        suspended = suspendRequested = checkpoint = armed = acknowledged = modulesApplied = modulesArmed = restoredModuleLease = tpaSent = runOnly = false;
        dimension = action.has("dimension") ? action.get("dimension").getAsString() : Utils.canUpdate() ? mc.level.dimension().identifier().toString() : "";
        stashResupply=type().equals("StashResupply")?new BotStashResupply(action,emptyStashSources,homeCooldownKey()+"\n"+dimension):null;
        state = "Running"; detail = "Starting " + type();
        listen(type().equals("DropItems") || survey != null || stashScan != null || stashResupply!=null || stashDeposit!=null);
    }

    public JsonObject tick() {return tick(false);}
    JsonObject tick(boolean workflowHandoff) {
        clientThread();
        if(stashNavigation!=null&&stashNavigation.closing()&&!stopStashNavigation()){
            detail="Waiting for safe Baritone movement release";return status();
        }
        if(stashHandoff()&&Utils.canUpdate()&&dev.monocle.client.utils.network.WorldReadiness.INSTANCE.ready()){if(!maintainStashClearance())holdStashPosition();return status();}
        boolean observeOnly = observesPendingDrop(state, suspended, issuedDrop());
        if (!shouldTick(state, suspended, observeOnly, suspendRequested)) return status();
        if (!Utils.canUpdate()) { disconnected(); detail = "Waiting for a world"; return status(); }
        if (!dev.monocle.client.utils.network.WorldReadiness.INSTANCE.ready()) {
            stopStashNavigation(); releaseMovement();
            detail=dev.monocle.client.utils.network.WorldReadiness.INSTANCE.snapshot().get("reason").getAsString();return status();
        }
        if (lastTick == mc.player.tickCount) return status();
        lastTick = mc.player.tickCount;
        try {
            if (observeOnly) { listen(true); settleDrop(); return status(); }
            if (!mc.player.isAlive()&&!suspendRequested) { stopStashNavigation(); releaseMovement(); detail = "Waiting for respawn before continuing worker action"; progress.resetClock(); return status(); }
            if (pendingStashFailure != null) {
                if (!landBeforeHandoff()) { detail = "Landing after stash failure: " + pendingStashFailure + " · " + detail; return status(); }
                String failure=pendingStashFailure;pendingStashFailure=null;fail(failure);return status();
            }
            if (STASH_ACTIONS.contains(type()) && !type().equals("StashVisit") && !suspendRequested && !nativeBusy() && tickStashMaintenance()) return status();
            if (STASH_ACTIONS.contains(type()) && mc.player.isFallFlying() && !stashFlightDurabilitySafe(gliderDurability(), true)) {
                if (!landBeforeHandoff()) { detail = "Elytra reserve low · " + detail; return status(); }
                fail("Elytra reserve is too low for elevated stash work; mend or replace it"); return status();
            }
            if (suspendRequested) {
                quietCrystalGuard();
                if (recovery != null && !recovery.suspend()) { detail = "Landing before suspending supply recovery"; return status(); }
                if (pending != null) settleDrop();
                if (type().equals("Tpa") && tpaSent) { teleport(); if (state.equals("Running")) return status(); }
                if (!landBeforeHandoff()) return status();
                if (!safeModuleRestore()) { landBeforeHandoff(); return status(); }
                if (pending == null) { suspended = true; release(); }
                return status();
            }
            if (STASH_ACTIONS.contains(type())&&!prepareStashHome()) return status();
            if (!type().equals("Tpa") && !EXTERNAL.contains(type()) && !dimension.isEmpty() && !dimension.equals(mc.level.dimension().identifier().toString())) {
                fail("Dimension changed; this action will not follow into another world"); return status();
            }
            // Native highways own their supply journal once assigned; an unrelated old journal
            // must not prevent a fresh crew from receiving and starting its highway action.
            if (type().equals("Highway")) recoveryReady = true;
            if (recovery != null && shouldRecover(type(), recoveryReady) && !nativeBusy()) {
                recoveryReady = recovery.tick(); detail = recovery.detail();
                if (!recoveryReady) return status();
            }
            if (type().equals("RecoverSupplies")) { if (recoveryReady) complete(recovery.detail()); else detail = "Waiting for native supply ownership to yield"; return status(); }
            if (EXTERNAL.contains(type())) { detail = "Waiting for host-managed " + type(); return status(); }
            if (type().equals("Wait")) { if (remaining > 0) remaining--; if (remaining == 0) complete("Wait complete"); return status(); }
            if (nativeBusy()) { stopStashNavigation(); releaseMovement(); detail = "Waiting for Highway Builder / Printer Helper to release control"; return status(); }
            switch (type()) {
                case "Travel" -> travel();
                case "StashHunt" -> survey();
                case "StashScan" -> scanStash(workflowHandoff);
                case "StashVisit" -> {holdStashPosition();completeStash("Stash arrival verified",workflowHandoff);}
                case "StashResupply" -> resupplyStash(workflowHandoff);
                case "StashDeposit" -> depositStash(workflowHandoff);
                case "DropItems" -> drop();
                case "Modules" -> runModules();
                case "Tpa" -> teleport();
                default -> throw new IllegalStateException("Unknown action");
            }
        } catch (RuntimeException error) { fail(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()); }
        return status();
    }

    private boolean prepareStashHome(){
        String home=action.get("homeName").getAsString();
        long now=System.currentTimeMillis(),cooldown=action.get("homeCooldownTicks").getAsLong()*50L;
        String server=homeCooldownKey();
        if(!mc.level.getChunkSource().hasChunk(mc.player.blockPosition().getX()>>4,mc.player.blockPosition().getZ()>>4)||TickRate.INSTANCE.getTimeSinceLastTick()>=1.5f){
            // Offline/loading time is not evidence that a teleport failed.
            if(homeReadyAt>0)homeReadyAt=Math.max(homeReadyAt,now);
            holdStashPosition();detail="Waiting for loaded world and server response before stash travel";return false;
        }
        if(homeReadyAt==0){
            if(stashHomeReached(dimension,mc.level.dimension().identifier().toString(),action,new Position(mc.player.getX(),mc.player.getY(),mc.player.getZ()))){
                if(stashHomeArrivalUsesCooldown(stashHomeSent,stashHomeAttempts))bots.recordStashHomeUse(server,now);
                homeReadyAt=now;stashHomeArrived=true;stashHomeAttempts=0;return true;
            }
            if(action.has("requireLocal")&&action.get("requireLocal").getAsBoolean())throw new IllegalStateException("Crew inventory work requires arrival at the assigned stash; kits retained");
            long ready=bots.stashHomeReadyAt(server,cooldown);
            if(now<ready){detail="Waiting "+Math.max(1,(ready-now+999)/1000)+"s for /home "+home+" cooldown";return false;}
            // Saturate the diagnostic count for old checkpoints, not the retry budget.
            holdStashPosition();mc.getConnection().sendCommand("home "+home);stashHomeSent=true;stashHomeAttempts=Math.min(3,stashHomeAttempts+1);homeReadyAt=now+action.get("homeWarmupTicks").getAsLong()*50L;bots.recordStashHomeUse(server,homeReadyAt);
        }
        if(now<homeReadyAt){detail="Waiting "+Math.max(1,(homeReadyAt-now+999)/1000)+"s for /home "+home+" warmup";return false;}
        if(!stashHomeArrived){
            stashHomeArrived=stashHomeReached(dimension,mc.level.dimension().identifier().toString(),action,new Position(mc.player.getX(),mc.player.getY(),mc.player.getZ()));
            if(!stashHomeArrived){
                holdStashPosition();
                if(stashHomeTimedOut(now,homeReadyAt)){
                    if(stashHomeRetry(stashHomeAttempts,now,homeReadyAt)){
                        MonocleClient.LOG.warn("[Workers] /home {} arrival timed out on attempt {}; retrying travel without replaying inventory moves",home,stashHomeAttempts>=3?"3+":Integer.toString(stashHomeAttempts));
                        resetStashHome();detail="Retrying /home "+home+" after interrupted or delayed arrival";return false;
                    }
                }
                detail="Waiting to arrive at stash after /home "+home+"; navigation and transfers are held";return false;
            }
            if(stashHomeArrivalUsesCooldown(stashHomeSent,stashHomeAttempts))bots.recordStashHomeUse(server,now);
            stashHomeAttempts=0; // A later server restart gets a fresh budget after verified arrival.
        }
        return true;
    }
    static boolean stashHomeReached(String expected,String actual,JsonObject action,Position position){return expected.equals(actual)&&(action.has("requireLocal")&&action.get("requireLocal").getAsBoolean()?StashCatalog.nearby(action,position,256):localStashTrip(action,position));}
    static boolean stashHomeTimedOut(long now,long readyAt){return now-readyAt>60_000;}
    static boolean stashHomeRetry(int attempts,long now,long readyAt){return attempts>0&&readyAt>0&&stashHomeTimedOut(now,readyAt);}
    static boolean stashHomeWaiting(int attempts,long now,long readyAt,long cooldownReadyAt){return attempts>0||readyAt>now||readyAt==0&&cooldownReadyAt>now;}
    static boolean stashHomeArrivalUsesCooldown(boolean sent,int attempts){return sent||attempts>0;}
    private void resetStashHome(){homeReadyAt=0;stashHomeArrived=stashHomeSent=false;}
    static boolean localStashTrip(JsonObject action,Position position){
        if(position==null)return false;
        if(action.has("picks")&&!action.getAsJsonArray("picks").isEmpty()){
            var picks=action.getAsJsonArray("picks");int index=action.has("pickIndex")?action.get("pickIndex").getAsInt():0;
            if(index<picks.size()){
                var target=picks.get(index).getAsJsonObject();double dx=target.get("x").getAsDouble()+.5-position.x(),dy=target.get("y").getAsDouble()+.5-position.y(),dz=target.get("z").getAsDouble()+.5-position.z();
                return dx*dx+dy*dy+dz*dz<=256*256;
            }
        }
        return StashCatalog.nearby(action,position,256);
    }
    public static String homeCooldownKey(){return mc.getCurrentServer()==null?"local":mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);}

    static boolean shouldRecover(String type, boolean recoveryReady) {
        return !recoveryReady && !type.equals("Highway");
    }

    /** Persist snapshot() before acknowledging; no destructive packet is emitted in the prepare tick. */
    public boolean checkpointRequired() { return checkpoint; }
    public void checkpointSaved() {
        clientThread();
        if (checkpoint) { checkpoint = false; if (pending != null) armed = true; else if (type().equals("Modules") || crystalGuard()) modulesArmed = true; }
    }

    public boolean requestSuspend() {
        clientThread(); suspendRequested = true;
        quietCrystalGuard();
        if (recovery != null && !recovery.suspend()) return false;
        // Prepared but unsent is known safe in this live process. A restored prepared record is not.
        if (pending != null && !pending.get("issued").getAsBoolean()) { pending = null; checkpoint = armed = false; }
        if (pending != null) { releaseMovement(); listen(true); detail = "Waiting for authoritative drop confirmation before suspending"; return false; }
        if (state.equals("Running") && type().equals("Tpa") && tpaSent) { detail = "Waiting for the outstanding teleport to resolve before yielding"; return false; }
        if (Utils.canUpdate() && !mc.player.onGround()) {
            if(!landing)detail = "Landing before handing off movement and profile settings";
            return false;
        }
        if (!safeModuleRestore()) { detail = "Land before restoring the temporary flight module"; return false; }
        if(!stopStashNavigation()){detail="Waiting for safe Baritone movement release before suspending";return false;}
        if (survey != null) Modules.get().get(dev.monocle.client.systems.modules.world.StashFinder.class).surveyFlush();
        suspended = true; release(); return true;
    }
    public void resume() { clientThread(); progress.resetClock(); suspendRequested = suspended = false; lastTick = -1; listen((type().equals("DropItems") || survey != null || stashScan != null || stashResupply != null || stashDeposit != null) && state.equals("Running")); }
    public void disconnected() {
        progress.resetClock();
        clientThread();
        // /home also suspends the runtime: retain scoped, expiring emptiness evidence for this task.
        resetStashHome();lastTick=-1; // Re-observe arrival and retry interrupted homes after host reconciliation.
        resetFlightRoutes();
        resetStashFlightProgress();
        quietCrystalGuard();
        stopStashNavigation();
        if (recovery != null) recovery.disconnected();
        if (stashScan != null) stashScan.close();
        if(stashResupply!=null)stashResupply.close();
        if(stashDeposit!=null)stashDeposit.close();
        releaseMovement();
    }
    public void stop() {
        clientThread();
        if (!state.equals("Running")) { release(); return; }
        if (pending != null && pending.get("issued").getAsBoolean()) fail("Stopped with an uncertain item drop. Review the recorded transfer; it will not be sent again.");
        else { pending = null; checkpoint = armed = false; fail("Cancelled"); }
    }
    public void markTpaSent() { clientThread(); if (!type().equals("Tpa")) throw new IllegalStateException("Not a teleport action"); if (!tpaSent) { tpaSent = true; elapsed = 0; } }
    boolean rejectTeleport(String message){
        clientThread();if(!teleportRejected(state,type(),tpaSent,elapsed,message))return false;
        tpaSent=false;fail("Teleport rejected by server: "+message);return true;
    }
    static boolean teleportRejected(String state,String type,boolean sent,int elapsed,String message){
        return state.equals("Running")&&type.equals("Tpa")&&sent&&elapsed<=200&&message.equals("Player not found!");
    }
    public void externalResult(boolean success, String message, JsonObject value) {
        clientThread();
        if (!state.equals("Running")) throw new IllegalStateException("Not an active external action");
        if (type().equals("Tpa") && !success && tpaSent) { detail = message + "; waiting for the outstanding teleport to settle"; return; }
        if (!EXTERNAL.contains(type()) && !(type().equals("Tpa") && !success)) throw new IllegalStateException("Not an active external action");
        if(success&&type().equals("KitTransferPlan")&&!Utils.canUpdate()){detail="Waiting for a world before inspecting carried kits";return;}
        result = value == null ? null : value.deepCopy();
        if(success&&type().equals("StashSortPlan")&&result!=null&&dev.monocle.coordinator.TaskWire.flag(result,"probe")){
            if(!Utils.canUpdate()){detail="Waiting for a world before checking sorting cargo";return;}
            int free=0,boxes=0;for(int i=0;i<36;i++){ItemStack stack=mc.player.getInventory().getItem(i);if(stack.isEmpty())free++;else if(stack.getItem() instanceof net.minecraft.world.item.BlockItem b&&b.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock)boxes+=stack.getCount();}
            result.addProperty("freeSlots",free);result.addProperty("shulkers",boxes);
        }
        if(success&&type().equals("KitTransferPlan")&&result!=null&&result.has("args")&&Utils.canUpdate()){
            JsonObject plan=result.getAsJsonObject("args");int carried=0;
            if(dev.monocle.coordinator.TaskWire.flag(plan,"depositCarried"))for(int i=0;i<36;i++){ItemStack stack=mc.player.getInventory().getItem(i);if(BotStashScan.matchesKit(stack,plan))carried+=stack.getCount();}
            result.addProperty("carried",carried);
        }
        if (success) complete(message); else fail(message);
    }

    public JsonObject snapshot() {
        JsonObject s = status(); s.addProperty("version", 1);
        s.add("actionProgress", progress.snapshot());
        if (action != null) s.add("action", action.deepCopy());
        s.addProperty("elapsed", elapsed); s.addProperty("remaining", remaining); s.addProperty("dimension", dimension);
        if(homeReadyAt>0)s.addProperty("homeReadyAt",homeReadyAt);
        s.addProperty("stashHomeSent",stashHomeSent);
        s.addProperty("stashHomeAttempts",stashHomeAttempts);
        if(pendingStashFailure!=null)s.addProperty("pendingStashFailure",pendingStashFailure);
        s.addProperty("suspended", suspended); s.addProperty("suspendRequested", suspendRequested); s.addProperty("tpaSent", tpaSent);
        s.addProperty("stashHandoff",stashHandoff);
        s.add("originalModules", originals.deepCopy());
        if (pending != null) s.add("pendingDrop", pending.deepCopy());
        if(deliveryAnchor!=null)s.add("deliveryAnchor",deliveryAnchor.deepCopy());
        if (survey != null) s.add("survey", survey.snapshot());
        if (stashScan != null) s.add("stashScan", stashScan.snapshot());
        if(stashResupply!=null){
            s.add("stashResupply",stashResupply.snapshot());
            JsonObject diagnostic=stashResupply.approachDiagnostic();diagnostic.addProperty("lastActionTick",lastTick);
            if(Utils.canUpdate())diagnostic.addProperty("playerTick",mc.player.tickCount);
            diagnostic.addProperty("walkingOnly",runOnly);s.add("stashApproach",diagnostic);
        }
        if(stashDeposit!=null)s.add("stashDeposit",stashDeposit.snapshot());
        if(personalSupplies!=null)s.add("personalSupplies",personalSupplies.diagnostic());
        s.addProperty("stashMending",stashMending);
        s.addProperty("stashMendDeferred",stashMendDeferred);
        if(stashFlightDiagnostic!=null)s.add("stashFlight",stashFlightDiagnostic.deepCopy());
        if(stashNavigation!=null)s.add("stashNavigation",stashNavigation.diagnostic());
        if(stashFlightAttemptTarget!=null){
            JsonObject flight=new JsonObject();flight.addProperty("target",stashFlightAttemptTarget.toShortString());
            flight.addProperty("failures",stashFlightFailures);flight.addProperty("walkingFallback",stashFlightFailures>=3);
            flight.addProperty("launchTick",launchTick);flight.addProperty("landing",landing);s.add("stashTravel",flight);
        }
        return s;
    }

    public void restore(JsonObject snapshot) {
        clientThread();
        if (state.equals("Running")) throw new IllegalStateException("Stop before restoring an action");
        try {
        start(snapshot.getAsJsonObject("action"));
        if (integer(snapshot, "version", 1, 1) != 1) throw new IllegalArgumentException("Unknown action snapshot version");
        String restoredState = string(snapshot, "state", 16);
        if (!Set.of("Running", "Complete", "Failed").contains(restoredState)) throw new IllegalArgumentException("Invalid action state");
        elapsed = integer(snapshot, "elapsed", 0, 1_728_000); remaining = integer(snapshot, "remaining", 0, 1_728_000);
        int maximumRemaining = type().equals("DropItems") ? action.get("count").getAsInt() : action.has("ticks") ? action.get("ticks").getAsInt() : 0;
        if (remaining > maximumRemaining) throw new IllegalArgumentException("Saved action cannot increase its requested work");
        detail = string(snapshot, "detail", 1024); state = restoredState;
        dimension = string(snapshot, "dimension", 128, true);
        if (!dimension.isEmpty()) identifier(dimension);
        if(snapshot.has("deliveryAnchor")){
            if(!action.has("handoff")||!action.get("handoff").getAsBoolean())throw new IllegalArgumentException("Unexpected delivery checkpoint");
            deliveryAnchor=BotDelivery.checked(snapshot.getAsJsonObject("deliveryAnchor"));
        }
        if(snapshot.has("homeReadyAt")){homeReadyAt=snapshot.get("homeReadyAt").getAsLong();if(homeReadyAt<0||homeReadyAt>System.currentTimeMillis()+3_600_000)throw new IllegalArgumentException("Invalid saved home warmup");}
        stashHomeSent=snapshot.has("stashHomeSent")&&snapshot.get("stashHomeSent").getAsBoolean();
        stashHomeAttempts=snapshot.has("stashHomeAttempts")?integer(snapshot,"stashHomeAttempts",0,3):stashHomeSent?1:0;
        // The saved world/teleport timer is stale after reconnect; inventory checkpoints are not.
        resetStashHome();
        if(snapshot.has("pendingStashFailure"))pendingStashFailure=string(snapshot,"pendingStashFailure",900);
        suspended = snapshot.get("suspended").getAsBoolean(); suspendRequested = snapshot.get("suspendRequested").getAsBoolean(); tpaSent = snapshot.get("tpaSent").getAsBoolean();
        stashHandoff=snapshot.has("stashHandoff")&&snapshot.get("stashHandoff").getAsBoolean()&&state.equals("Complete")&&STASH_ACTIONS.contains(type());
        if (snapshot.has("actionProgress")) progress.restore(snapshot.getAsJsonObject("actionProgress"));
        if (snapshot.has("originalModules")) {
            originals = snapshot.getAsJsonObject("originalModules").deepCopy();
            for (var entry : originals.entrySet()) if (!(type().equals("Modules") && action.getAsJsonObject("modules").has(entry.getKey())
                || crystalGuard() && CRYSTAL_GUARD_MODULES.contains(entry.getKey())) || !entry.getValue().getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Invalid saved module lease");
            modulesArmed = type().equals("Modules") && originals.size() == action.getAsJsonObject("modules").size()
                || crystalGuard() && originals.size() == CRYSTAL_GUARD_MODULES.size();
            restoredModuleLease = modulesArmed && state.equals("Running") && !suspended;
        }
        if (snapshot.has("result")) result = snapshot.getAsJsonObject("result").deepCopy();
        if (snapshot.has("survey")) {
            if (survey == null) throw new IllegalArgumentException("Unexpected survey checkpoint");
            survey.restore(snapshot.getAsJsonObject("survey"));
        }
        if (snapshot.has("stashScan")) {
            if (stashScan == null) throw new IllegalArgumentException("Unexpected stash checkpoint");
            stashScan.restore(snapshot.getAsJsonObject("stashScan"));
        }
        if(snapshot.has("stashResupply")){if(stashResupply==null)throw new IllegalArgumentException("Unexpected stash resupply checkpoint");stashResupply.restore(snapshot.getAsJsonObject("stashResupply"));}
        if(snapshot.has("stashDeposit")){if(stashDeposit==null)throw new IllegalArgumentException("Unexpected stash deposit checkpoint");stashDeposit.restore(snapshot.getAsJsonObject("stashDeposit"));}
        if (snapshot.has("pendingDrop")) {
            if (!type().equals("DropItems")) throw new IllegalArgumentException("Unexpected pending drop");
            pending = snapshot.getAsJsonObject("pendingDrop").deepCopy();
            integer(pending, "slot", 0, 35);
            int amount = integer(pending, "amount", 1, dropBurst()?396:99), total = integer(pending, "beforeTotal", 1, 1_048_576), count = integer(pending, "beforeCount", 1, 99);
            if (amount > remaining || !dropBurst()&&amount > count || count > total || !pending.has("stack") || !pending.get("stack").isJsonObject()) throw new IllegalArgumentException("Invalid saved drop quantities");
            if(pending.has("stage")){
                String stage=string(pending,"stage",8);
                if(stage.equals("swap")){
                    integer(pending,"source",9,35);integer(pending,"slot",0,8);
                    if(pending.has("displaced")&&!pending.get("displaced").isJsonObject())throw new IllegalArgumentException("Invalid displaced hotbar item");
                }else if(stage.equals("burst"))validateDropBurstRecord(pending);
                else throw new IllegalArgumentException("Invalid saved drop stage");
            }
            // The durable prepared record may already have produced a packet before the process died.
            pending.addProperty("issued", true); armed = checkpoint = false; acknowledged = false;
        }
        listen(issuedDrop() || (type().equals("DropItems") || survey != null || stashScan != null || stashResupply!=null || stashDeposit!=null) && state.equals("Running") && !suspended);
        } catch (RuntimeException error) { fail("Invalid saved action: " + error.getMessage()); throw error; }
    }

    private JsonObject status() {
        JsonObject s = new JsonObject(); s.addProperty("state", state); s.addProperty("detail", detail);
        s.addProperty("checkpointRequired", checkpoint); s.addProperty("remaining", remaining);
        if (result != null) s.add("result", result.deepCopy());
        return s;
    }

    private void drop() {
        if(action.has("handoff")&&action.get("handoff").getAsBoolean()&&remaining>0&&!issuedDrop()&&!prepareDelivery())return;
        releaseMovement();
        if (pending != null) {
            if (armed) {
                if (!inventoryReady() || !aimRecipient()) return;
                if(dropBurst()){
                    List<Drop> drops=checkedDropBurst(pending);
                    if(countInventory(action.get("item").getAsString())!=pending.get("beforeTotal").getAsInt()
                        ||drops.stream().anyMatch(d->!ItemStack.matches(d.stack(),mc.player.getInventory().getItem(d.slot())))){
                        fail("Inventory changed before the prepared drop burst; no items sent");return;
                    }
                    pending.addProperty("issued",true);armed=false;dropWait=0;acknowledged=false;
                    sendDropBurst(drops);refreshInventory();return;
                }
                ItemStack expected = decodeStack(pending.get("stack"));
                int slot = pending.get("slot").getAsInt(), amount = pending.get("amount").getAsInt();
                boolean swap=dropSwap();
                int source=swap?pending.get("source").getAsInt():slot;
                if (!ItemStack.matches(expected, mc.player.getInventory().getItem(source))
                    ||swap&&!ItemStack.matches(displacedDropStack(),mc.player.getInventory().getItem(slot))) { fail("Inventory changed before the prepared transfer; no items sent"); return; }
                pending.addProperty("issued", true); armed = false; dropWait = 0; acknowledged = false;
                if(!swap){
                    dev.monocle.client.utils.player.InvUtils.swap(slot,false);
                    if(action.has("handoff")&&action.get("handoff").getAsBoolean())mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(mc.player.getYRot(),mc.player.getXRot(),mc.player.onGround(),mc.player.horizontalCollision));
                }
                mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, SlotUtils.indexToId(source), swap?slot:amount == expected.getCount() ? 1 : 0, swap?ContainerInput.SWAP:ContainerInput.THROW, mc.player);
                refreshInventory();
            } else if (pending.get("issued").getAsBoolean()) settleDrop();
            return;
        }
        if (remaining == 0) { result = new JsonObject(); result.addProperty("dropped", action.get("count").getAsInt()); complete("Items dropped and confirmed by the server"); return; }
        if (!inventoryReady() || !aimRecipient()) return;
        String id = action.get("item").getAsString();
        int total = countInventory(id), slot = -1;
        if (total < remaining) { fail("Not enough carried " + id + " for the remaining transfer"); return; }
        List<Drop> drops=planDropBurst(mc.player.getInventory().getNonEquipmentItems(),remaining,stack->matchesTransfer(stack,id));
        slot=drops.getFirst().slot();
        ItemStack stack = mc.player.getInventory().getItem(slot);
        int amount = nextDropCount(remaining, stack.getCount());
        pending = new JsonObject(); pending.addProperty("slot", slot); pending.addProperty("amount", amount);
        pending.addProperty("beforeCount", stack.getCount()); pending.addProperty("beforeTotal", total); pending.addProperty("issued", false);
        pending.add("stack", ItemStack.CODEC.encodeStart(mc.player.registryAccess().createSerializationContext(JsonOps.INSTANCE), stack).getOrThrow());
        JsonArray batch=new JsonArray();int quantity=0;
        for(Drop drop:drops){
            JsonObject record=new JsonObject();record.addProperty("slot",drop.slot());record.addProperty("amount",drop.amount());
            record.add("stack",ItemStack.CODEC.encodeStart(mc.player.registryAccess().createSerializationContext(JsonOps.INSTANCE),drop.stack()).getOrThrow());batch.add(record);quantity+=drop.amount();
        }
        pending.addProperty("stage","burst");pending.add("drops",batch);pending.addProperty("amount",quantity);
        checkpoint = true; detail = "Checkpoint required before dropping " + quantity + " item(s) in " + drops.size() + " inventory click(s)";
    }

    record Drop(int slot,int amount,ItemStack stack){}
    static List<Drop> planDropBurst(List<ItemStack> inventory,int remaining,java.util.function.Predicate<ItemStack> matches){
        List<Drop> drops=new ArrayList<>();
        for(int slot=0;slot<Math.min(36,inventory.size())&&remaining>0&&drops.size()<BotStashBulk.MOVES_PER_TICK;slot++){
            ItemStack stack=inventory.get(slot);if(stack.isEmpty()||!matches.test(stack))continue;
            int amount=nextDropCount(remaining,stack.getCount());drops.add(new Drop(slot,amount,stack.copy()));remaining-=amount;
            if(amount<stack.getCount())break;
        }
        return drops;
    }
    private boolean dropBurst(){return pending!=null&&pending.has("stage")&&pending.get("stage").getAsString().equals("burst");}
    static void validateDropBurstRecord(JsonObject pending){
        if(!pending.has("drops")||!pending.get("drops").isJsonArray()||pending.getAsJsonArray("drops").isEmpty()||pending.getAsJsonArray("drops").size()>BotStashBulk.MOVES_PER_TICK)throw new IllegalArgumentException("Invalid saved inventory drop burst");
        Set<Integer> slots=new HashSet<>();int total=0;
        for(JsonElement value:pending.getAsJsonArray("drops")){
            JsonObject record=value.getAsJsonObject();int slot=integer(record,"slot",0,35),amount=integer(record,"amount",1,99);
            if(!slots.add(slot)||!record.has("stack")||!record.get("stack").isJsonObject())throw new IllegalArgumentException("Invalid saved burst slot or stack");total+=amount;
        }
        if(total!=integer(pending,"amount",1,396)||total>integer(pending,"beforeTotal",1,1_048_576))throw new IllegalArgumentException("Saved burst quantity changed");
    }
    private static List<Drop> checkedDropBurst(JsonObject pending){
        validateDropBurstRecord(pending);List<Drop> drops=new ArrayList<>();
        for(JsonElement value:pending.getAsJsonArray("drops")){
            JsonObject record=value.getAsJsonObject();ItemStack stack=decodeStack(record.get("stack"));int amount=record.get("amount").getAsInt();
            if(stack.isEmpty()||amount!=1&&amount!=stack.getCount()||amount>stack.getCount())throw new IllegalArgumentException("Invalid saved burst quantity");
            drops.add(new Drop(record.get("slot").getAsInt(),amount,stack));
        }
        return drops;
    }
    private void sendDropBurst(List<Drop> drops){
        if(action.has("handoff")&&action.get("handoff").getAsBoolean())mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(mc.player.getYRot(),mc.player.getXRot(),mc.player.onGround(),mc.player.horizontalCollision));
        for(Drop drop:drops){
            if(mc.player.containerMenu!=mc.player.inventoryMenu||!mc.player.inventoryMenu.getCarried().isEmpty()||!ItemStack.matches(drop.stack(),mc.player.getInventory().getItem(drop.slot())))throw new IllegalStateException("Inventory changed during drop burst; stop without replaying any click");
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId,SlotUtils.indexToId(drop.slot()),drop.amount()==drop.stack().getCount()?1:0,ContainerInput.THROW,mc.player);
        }
    }
    static boolean confirmedDropBurst(List<Drop> drops,List<ItemStack> inventory,ItemStack cursor,int beforeTotal,int afterTotal){
        if(drops.isEmpty()||drops.size()>BotStashBulk.MOVES_PER_TICK||!cursor.isEmpty()||beforeTotal-afterTotal!=drops.stream().mapToInt(Drop::amount).sum())return false;
        for(Drop drop:drops){
            if(drop.slot()>=inventory.size())return false;
            ItemStack expected=drop.stack().copy();expected.shrink(drop.amount());
            if(!ItemStack.matches(expected,inventory.get(drop.slot())))return false;
        }
        return true;
    }

    private boolean dropSwap(){return pending!=null&&pending.has("stage")&&pending.get("stage").getAsString().equals("swap");}
    private ItemStack displacedDropStack(){return pending.has("displaced")?decodeStack(pending.get("displaced")):ItemStack.EMPTY;}
    static boolean confirmedDropSwap(ItemStack expected,ItemStack displaced,ItemStack source,ItemStack hotbar,ItemStack cursor,int beforeTotal,int afterTotal){
        return !expected.isEmpty()&&cursor.isEmpty()&&beforeTotal==afterTotal&&ItemStack.matches(expected,hotbar)&&ItemStack.matches(displaced,source);
    }

    public static int nextDropCount(int remaining, int stackCount) { return stackCount <= remaining ? stackCount : 1; }
    public static boolean confirmedDrop(int beforeTotal, int amount, int afterTotal, int beforeCount, int afterCount) {
        return amount > 0 && beforeTotal - afterTotal == amount && beforeCount - afterCount == amount;
    }
    static boolean observesPendingDrop(String state, boolean suspended, boolean issued) { return issued && (!state.equals("Running") || suspended); }
    static boolean shouldTick(String state, boolean suspended, boolean observeOnly, boolean cleanup) {
        return observeOnly || cleanup || state.equals("Running") && !suspended;
    }
    private boolean issuedDrop() { return pending != null && pending.get("issued").getAsBoolean(); }

    private void settleDrop() {
        if (pending == null) return;
        if (acknowledged) {
            boolean swap=dropSwap();if(!swap)remaining -= pending.get("amount").getAsInt(); pending = null; acknowledged = checkpoint = armed = false; dropWait = 0;
            detail = swap?"Hotbar staging confirmed; no items dropped yet":"Drop confirmed"; if (!state.equals("Running") || suspended) listen(false); return;
        }
        dropWait = Math.min(121, dropWait + 1);
        if (dropWait > 120 && state.equals("Running")) { fail(dropSwap()?"Hotbar staging remains uncertain after server refresh; this swap will not be repeated":"Item drop remains uncertain after server refresh. Inspect inventory and the ground; this drop will not be repeated."); return; }
        if (Utils.canUpdate() && inventoryReady() && (dropWait == 1 || lastTick % 20 == 0)) refreshInventory();
        detail = state.equals("Running") ? dropSwap()?"Waiting for the server to confirm hotbar staging":"Waiting for the server to confirm the item drop" : "Transfer remains uncertain; observing inventory only, never repeating the click";
    }

    @EventHandler private void slot(ContainerSlotUpdateEvent event) {
        if(!mc.isSameThread()||!Utils.canUpdate()||!state.equals("Running")||suspended||suspendRequested)return;
        try{
            if(personalSupplies!=null&&personalSupplies.busy()){personalSupplies.slot(event);return;}
            if(stashDeposit!=null)stashDeposit.slot(event);
            if(stashResupply!=null)stashResupply.slot(event);
        }catch(RuntimeException e){fail("Stash slot update needs inspection: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
    }
    @EventHandler private void inventory(InventoryEvent event) {
        if (!mc.isSameThread() || !Utils.canUpdate()) return;
        if(personalSupplies!=null&&personalSupplies.busy()){
            try{personalSupplies.inventory(event);}catch(RuntimeException e){fail("Personal supply receipt needs inspection: "+e.getMessage());}
            return;
        }
        if (stashScan != null && state.equals("Running") && !suspended && !suspendRequested) {
            try { stashScan.inventory(event); }
            catch (RuntimeException e) { fail("Stash scan could not save container " + stashScan.target() + ": " + (e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())); }
        }
        if(state.equals("Running")&&!suspended&&!suspendRequested)try{
            if(stashDeposit!=null)stashDeposit.inventory(event);
            if(stashResupply!=null)stashResupply.inventory(event);
        }catch(RuntimeException e){fail("Stash transfer needs inspection: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
        if (!mc.isSameThread() || pending == null || !pending.get("issued").getAsBoolean() || !Utils.canUpdate()
            || event.packet.containerId() != mc.player.inventoryMenu.containerId) return;
        List<ItemStack> items = event.packet.items();
        int slot = pending.get("slot").getAsInt(), menuSlot = slot < 9 ? 36 + slot : slot;
        if (items.size() <= menuSlot) return;
        String id = action.get("item").getAsString(); int total = 0;
        for (int i = 0; i < 36; i++) { int index = i < 9 ? i + 36 : i; if (index < items.size() && matchesTransfer(items.get(index), id)) total += items.get(index).getCount(); }
        if(dropBurst()){
            List<ItemStack> inventory=new ArrayList<>();for(int i=0;i<36;i++){int index=i<9?i+36:i;if(index>=items.size())return;inventory.add(items.get(index));}
            acknowledged|=confirmedDropBurst(checkedDropBurst(pending),inventory,event.packet.carriedItem(),pending.get("beforeTotal").getAsInt(),total);return;
        }
        if(dropSwap()){
            int source=pending.get("source").getAsInt();
            if(items.size()>source)acknowledged|=confirmedDropSwap(decodeStack(pending.get("stack")),displacedDropStack(),items.get(source),items.get(menuSlot),event.packet.carriedItem(),pending.get("beforeTotal").getAsInt(),total);
            return;
        }
        ItemStack after = items.get(menuSlot), before = decodeStack(pending.get("stack"));
        if (!after.isEmpty() && !ItemStack.isSameItemSameComponents(before, after)) return;
        acknowledged |= confirmedDrop(pending.get("beforeTotal").getAsInt(), pending.get("amount").getAsInt(), total, pending.get("beforeCount").getAsInt(), after.getCount());
    }
    @EventHandler private void screen(OpenScreenEvent event) {
        if(stashScan!=null&&state.equals("Running")&&!suspended&&!suspendRequested&&stashScan.suppressScreen()&&event.screen instanceof AbstractContainerScreen<?>)event.cancel();
    }

    private void refreshInventory() {
        mc.getConnection().send(HighwayBuilder.cursorSyncRequest(mc.player.inventoryMenu.containerId,
            HashedStack.create(mc.player.inventoryMenu.getCarried(), mc.getConnection().decoratedHashOpsGenenerator())));
    }
    private boolean inventoryReady() {
        if(action.has("handoff")&&action.get("handoff").getAsBoolean()&&!dev.monocle.client.utils.network.WorldReadiness.INSTANCE.inventoryReady()){
            if(mc.player.tickCount%20==0&&mc.player.containerMenu==mc.player.inventoryMenu&&mc.player.inventoryMenu.getCarried().isEmpty())refreshInventory();
            detail="Waiting for server-confirmed courier inventory before dropping";return false;
        }
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty() || mc.gui.screen() instanceof AbstractContainerScreen
            && !(type().equals("DropItems")&&mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) {
            detail = "Close containers and put the inventory cursor away before transferring items"; return false;
        }
        if (bots.crew.localAssigned()) { detail = "Waiting for the native crew to finish reserved-container recovery"; return false; }
        return true;
    }
    private boolean aimRecipient() {
        if (!action.has("recipient")) return true;
        if(action.has("handoff")&&action.get("handoff").getAsBoolean()){
            if(deliveryAnchor==null||!deliveryAnchor.get("dimension").getAsString().equals(mc.level.dimension().identifier().toString())||!standable(mc.player.position()))return false;
            Vec3 target=BotDelivery.position(deliveryAnchor);
            if(mc.player.position().distanceToSqr(target)>16)return false;
            mc.player.setYRot((float)Rotations.getYaw(target));
            mc.player.setXRot(BotDelivery.pitch(mc.player.getEyeY(),target.y,mc.player.position().multiply(1,0,1).distanceTo(target.multiply(1,0,1))));return true;
        }
        Player recipient = mc.level.getPlayerByUUID(UUID.fromString(action.get("recipient").getAsString()));
        if (recipient == null || !recipient.isAlive() || recipient.distanceToSqr(mc.player) > 16 || !mc.player.hasLineOfSight(recipient)) { detail = "Waiting for the transfer recipient within four blocks and in sight"; return false; }
        mc.player.setYRot((float) Rotations.getYaw(recipient.position())); mc.player.setXRot(-10); return true;
    }
    private boolean prepareDelivery(){
        if(TickRate.INSTANCE.getTimeSinceLastTick()>=1.5f){releaseMovement();detail="Delivery approach waiting for server response";return false;}
        if(++elapsed>600){fail("Safe delivery handoff timed out; no uncertain drop will be repeated");return false;}
        if(mc.player.isPassenger()){releaseMovement();detail="Delivery requires dismounting before the supported walking handoff";return false;}
        if(deliveryAnchor==null){
            Player player=mc.level.getPlayerByUUID(UUID.fromString(action.get("recipient").getAsString()));
            if(player==null||!player.isAlive()||player.distanceToSqr(mc.player)>16||!mc.player.hasLineOfSight(player)){
                releaseMovement();detail="Waiting to snapshot the nearby delivery recipient";return false;
            }
            deliveryAnchor=BotDelivery.anchor(player.position(),player.getYRot(),mc.level.dimension().identifier().toString());checkpoint=true;
            detail="Checkpointing the recipient's fixed handoff position";return false;
        }
        if(!deliveryAnchor.get("dimension").getAsString().equals(mc.level.dimension().identifier().toString())){fail("Delivery location changed world; no kits dropped");return false;}
        if(!mc.player.onGround()||mc.player.isFallFlying()){
            if(!landBeforeHandoff())return false;
        }
        Vec3 from=mc.player.position(),target=BotDelivery.position(deliveryAnchor);
        if(deliveryGoal==null||!BotDelivery.corridor(from,deliveryGoal,this::standable))deliveryGoal=BotDelivery.goal(from,deliveryAnchor,this::standable);
        if(deliveryGoal==null){releaseMovement();detail="No supported local delivery approach; kits retained";return false;}
        if(from.distanceToSqr(deliveryGoal)>.09){
            if(!acquireMovement())return false;
            input.stop();brakeFlight();mc.player.setSprinting(false);
            Vec3 step=localGoal(from,deliveryGoal,.2);
            if(!BotDelivery.corridor(from,step,this::standable)){releaseMovement();detail="Delivery footing changed; stopping at the edge";return false;}
            mc.player.setYRot((float)Rotations.getYaw(step));input.sneak(true);input.forward(true);
            detail="Walking to fixed handoff spot · "+deliveryGoal;return false;
        }
        releaseMovement();
        if(!BotDelivery.corridor(from,target,this::standable)){detail="Delivery landing area is unsupported; kits retained";return false;}
        if(!aimRecipient())return false;
        if(mc.player.getDeltaMovement().horizontalDistanceSqr()>.0004){detail="Settling at handoff spot before aimed drop";return false;}
        return true;
    }

    @EventHandler(priority=meteordevelopment.orbit.EventPriority.LOWEST)
    private void deliveryMove(dev.monocle.client.events.entity.player.PlayerMoveEvent event){
        if(!mc.isSameThread()||!Utils.canUpdate()||!state.equals("Running")||suspended||suspendRequested||!type().equals("DropItems")
            ||!action.has("handoff")||!action.get("handoff").getAsBoolean()||inputPlayer!=mc.player||mc.player.input!=input)return;
        Vec3 movement=new Vec3(event.movement.x,0,event.movement.z);double length=movement.length();
        if(length>.12)movement=movement.scale(.12/length);
        if(!mc.player.onGround()||!BotDelivery.corridor(mc.player.position(),mc.player.position().add(movement),this::standable))movement=Vec3.ZERO;
        ((dev.monocle.client.mixininterface.IVec3)event.movement).monocle$set(movement.x,event.movement.y,movement.z);
    }
    private void runModules() {
        JsonObject requested = action.getAsJsonObject("modules");
        if (!modulesApplied) {
            for (var entry : requested.entrySet()) if (Modules.get().get(entry.getKey()) == null) throw new IllegalArgumentException("Unknown module: " + entry.getKey());
            for (var entry : requested.entrySet()) {
                Module module = Modules.get().get(entry.getKey());
                if (!originals.has(entry.getKey())) originals.addProperty(entry.getKey(), module.isActive());
            }
            if (!modulesArmed) { checkpoint = true; detail = "Checkpoint required before applying temporary module states"; return; }
            for (var entry : requested.entrySet()) {
                Module module = Modules.get().get(entry.getKey());
                setActive(module, entry.getValue().getAsBoolean());
            }
            modulesApplied = true;
            restoredModuleLease = false;
        }
        detail = "Running selected modules";
        if (action.get("ticks").getAsInt() > 0) {
            remaining = Math.max(0, remaining - 1);
            if (remaining == 0) {
                if (safeModuleRestore()) complete("Module interval complete");
                else detail = "Module interval finished; land before restoring the temporary flight module";
            }
        }
    }
    private boolean crystalGuard() {
        return action != null && type().equals("Travel") && action.has("crystalGuard") && action.get("crystalGuard").getAsBoolean();
    }

    private boolean armCrystalGuard() {
        if (modulesApplied) {
            Modules.get().get(AutoTotem.class).guardStrict(true);
            for(String name:CRYSTAL_GUARD_MODULES)if(!name.equals("crystal-aura"))setActive(Modules.get().get(name),true);
            return true;
        }
        if (AutoTotem.carriedTotems(mc.player.getInventory(), mc.player.getOffhandItem()) == 0) {
            fail("Crystal Guard needs at least one carried totem before combat"); return false;
        }
        for (String name : CRYSTAL_GUARD_MODULES) {
            Module module=Modules.get().get(name);
            if (module==null) throw new IllegalStateException("Missing combat module: " + name);
            if (!originals.has(name)) originals.addProperty(name,module.isActive());
        }
        if (!modulesArmed) { checkpoint=true; detail="Checkpointing Crystal Guard module states"; return false; }
        Set<String> names=new HashSet<>();for(JsonElement value:action.getAsJsonArray("combatTargets"))names.add(value.getAsString());
        Set<UUID> protectedPlayers=new HashSet<>();for(JsonElement value:action.getAsJsonArray("protectedPlayers"))protectedPlayers.add(UUID.fromString(value.getAsString()));
        Modules.get().get(CrystalAura.class).guardTargets(names,protectedPlayers);
        Modules.get().get(AutoTotem.class).guardStrict(true);
        modulesApplied=true;restoredModuleLease=false;
        for(String name:CRYSTAL_GUARD_MODULES)if(!name.equals("crystal-aura"))setActive(Modules.get().get(name),true);
        return true;
    }

    private void quietCrystalGuard() {
        if (crystalGuard() && modulesApplied) {
            Module aura=Modules.get().get(CrystalAura.class);
            if (aura.isActive()) aura.disable();
        }
    }
    private void teleport() {
        releaseMovement();
        String target = action.get("target").getAsString();
        Player player = mc.level.players().stream().filter(p -> p != mc.player && (p.getUUID().toString().equalsIgnoreCase(target) || p.getName().getString().equalsIgnoreCase(target))).findFirst().orElse(null);
        boolean nearby=player!=null&&player.isAlive()&&player.distanceToSqr(mc.player)<=Math.pow(action.get("radius").getAsDouble(),2)&&teleportStashReached(action,new Position(mc.player.getX(),mc.player.getY(),mc.player.getZ()));
        if(teleportMayArrive(action,dimension,mc.level.dimension().identifier().toString(),tpaSent,elapsed,nearby)){
            result=new JsonObject();result.add("deliveryAnchor",BotDelivery.anchor(player.position(),player.getYRot(),mc.level.dimension().identifier().toString()));
            complete("Recipient physically confirmed nearby");return;
        }
        if (!tpaSent) { detail = "Waiting for host teleport handshake"; return; }
        if (++elapsed >= action.get("timeoutTicks").getAsInt()) { fail("Teleport did not reach the observed target before timeout"); return; }
        if (elapsed < action.get("warmupTicks").getAsInt()) { detail = "Waiting for teleport warmup"; return; }
        if (!externalTeleport(action)&&(dimension.isEmpty() || !dimension.equals(mc.level.dimension().identifier().toString()))) { detail = "Waiting for the expected target dimension"; return; }
        if(!teleportStashReached(action,new Position(mc.player.getX(),mc.player.getY(),mc.player.getZ()))){detail="Waiting for teleport arrival at the assigned stash, not the leader's old position";return;}
        detail = "Waiting to observe the teleport target nearby";
    }
    static boolean externalTeleport(JsonObject action){return action.has("external")&&action.get("external").getAsBoolean();}
    static boolean teleportMayArrive(JsonObject action,String expected,String actual,boolean sent,int elapsed,boolean nearby){
        return nearby&&(externalTeleport(action)||!expected.isEmpty()&&expected.equals(actual))
            &&(sent?elapsed>=action.get("warmupTicks").getAsInt():externalTeleport(action));
    }
    static boolean teleportStashReached(JsonObject action,Position position){return !action.has("arrivalStash")||StashCatalog.nearby(action.getAsJsonObject("arrivalStash"),position,256);}

    private void travel() {
        if (action.has("follow") && action.get("follow").getAsBoolean()) {
            if (crystalGuard() && !armCrystalGuard()) return;
            if (!acquireMovement()) return;
            boolean bodyguard=action.has("bodyguard")&&action.get("bodyguard").getAsBoolean();
            boolean adaptiveFlight=bodyguard || action.has("adaptiveFlight")&&action.get("adaptiveFlight").getAsBoolean();
            input.stop(); brakeFlight(); runOnly = !adaptiveFlight;
            if (action.get("ticks").getAsInt() > 0 && ++elapsed >= action.get("ticks").getAsInt()) { complete("Follow duration finished"); return; }
            UUID target = UUID.fromString(action.get("target").getAsString());
            if (target.equals(mc.player.getUUID())) { fail("A follower cannot follow itself"); return; }
            Player leader = mc.level.players().stream().filter(p -> p.getUUID().equals(target) && p.isAlive()).findFirst().orElse(null);
            if (leader == null) {
                quietCrystalGuard();
                if(crystalGuard())CrystalFightRecorder.decision("subject_not_visible",null,null);
                if(!bodyguard&&mc.player.isFallFlying())landBeforeHandoff();
                if(bodyguard&&++bodyguardMissingTicks>=(crystalGuard()?1:40)&&mc.player.tickCount>=bodyguardTpaAt){bots.requestBodyguardTpa(target,action.get("targetName").getAsString());bodyguardTpaAt=mc.player.tickCount+400;}
                detail=bodyguard?"Subject not visible · TPA recovery "+(bodyguardTpaAt>mc.player.tickCount?"requested":"arming"):"Waiting for the leader to be visible in this world; no stale position is chased";return;
            }
            bodyguardMissingTicks=0;
            Player enemy=crystalGuard()?Modules.get().get(CrystalAura.class).guardEnemy(leader,64):null;
            if(crystalGuard() && AutoTotem.carriedTotems(mc.player.getInventory(),mc.player.getOffhandItem())==0){
                quietCrystalGuard();
                if(enemy!=null){
                    Vec3 away=retreatGoal(mc.player.position(),enemy.position(),16);
                    CrystalFightRecorder.decision("no_totems_retreat",enemy,away);
                    travel(away,2,6,false,null);
                } else { CrystalFightRecorder.decision("no_totems_hold",null,null); releaseMovement(); }
                detail="Crystal Guard · no totems; disengaging";return;
            }
            if(crystalGuard())setActive(Modules.get().get(CrystalAura.class),true);
            if(enemy!=null){
                Vec3 from=mc.player.position(), separation=new Vec3(from.x-enemy.getX(),0,from.z-enemy.getZ());
                if(separation.lengthSqr()<.01)separation=new Vec3(leader.getX()-enemy.getX(),0,leader.getZ()-enemy.getZ());
                if(separation.lengthSqr()<.01)separation=new Vec3(1,0,0);
                double distance=Math.sqrt(separation.lengthSqr());
                Vec3 goal=distance>=4.5&&distance<=6?from:enemy.position().add(separation.scale(5/distance));
                CrystalFightRecorder.decision("engage",enemy,goal);
                travel(goal,1,32,false,enemy.isFallFlying()?enemy:null);
                detail="Crystal Guard · engaging " + enemy.getName().getString() + " · " + detail;
                return;
            }
            Vec3 goal=bodyguard?formationGoal(leader.position(),leader.getYRot(),action.get("radius").getAsDouble(),action.get("workerIndex").getAsInt(),action.get("workerCount").getAsInt()):leader.position();
            if(crystalGuard())CrystalFightRecorder.decision("formation_no_target",null,goal);
            travel(goal,bodyguard ? .75 : action.get("radius").getAsDouble(),32,false,adaptiveFlight ? leader : null);
            if (goal.distanceToSqr(mc.player.position()) <= Math.pow(bodyguard ? .75 : action.get("radius").getAsDouble(),2)) detail = (bodyguard ? "Guarding · in formation with " : "Following · beside ") + leader.getName().getString();
            return;
        }
        travel(new Vec3(action.get("x").getAsDouble(), action.get("y").getAsDouble(), action.get("z").getAsDouble()), action.get("radius").getAsDouble(), action.get("flyBeyond").getAsDouble(), true,null);
    }
    private void travel(Vec3 goal, double radius, double flyBeyond, boolean finish,Player match) {
        if (!acquireMovement()) return;
        input.stop(); brakeFlight();
        if (mc.player.isPassenger() || mc.player.isInWater() || mc.player.isInLava()) { detail = "Travel needs an unmounted player outside fluid"; return; }
        if (Modules.get().get(AutoEat.class).eating || Modules.get().get(AutoGap.class).isEating() || Modules.get().get(KillAura.class).attacking || mc.player.isUsingItem() || TickRate.INSTANCE.getTimeSinceLastTick() >= 1.5f) { detail = "Travel waiting for combat, eating or server lag"; return; }
        Vec3 from = mc.player.position(); double distance = from.distanceTo(goal);
        if (distance <= radius && mc.player.onGround() && !mc.player.isFallFlying() && standable(BlockPos.containing(from))) { if (finish) complete("Destination reached on safe footing"); return; }
        ElytraFly fly = Modules.get().get(ElytraFly.class);
        ItemStack glider = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        boolean equipped = glider.has(DataComponents.GLIDER) && (!glider.isDamageableItem() || glider.getMaxDamage() - glider.getDamageValue() > 10);
        Vec3 waypoint = localGoal(from, goal, 8);
        boolean useFlight = !runOnly && equipped && fly.flightMode.get() == ElytraFlightModes.Vanilla && (match!=null&&match.isFallFlying() || distance > Math.max(radius + 2, flyBeyond));
        if (mc.player.isFallFlying()) {
            if (!fly.isActive()) { fail("ElytraFly was disabled in flight; control returned to the player"); return; }
            launchAttempts = 0; launchTick = -1; // Successful flight gives the next takeoff a fresh retry budget.
            if ((match==null||!match.isFallFlying())&&(distance <= radius + 3 || !useFlight)) {
                Vec3 landing = safeLandingBelow(from, 16);
                if (landing != null) {
                    if (from.y - landing.y < .2) { mc.player.stopFallFlying(); mc.player.setDeltaMovement(0, -.08, 0); runOnly = true; detail = "Landing safely"; return; }
                    fly.requestAutopilot(PrinterFlight.safeVelocity(from, landing, .3, mc.player.getBbWidth(), mc.player.getBbHeight(), this::clearBody));
                    detail = "Descending to safe footing"; return;
                }
            }
            if(match!=null&&match.isFallFlying()) {
                double maximum=Math.min(6,fly.horizontalSpeed.get());if(maximum<=0){detail="Bodyguard flight needs a positive ElytraFly speed";return;}
                Vec3 velocity=formationVelocity(from,goal,match.getDeltaMovement(),maximum);
                if(!PrinterFlight.segmentClear(from,from.add(velocity),mc.player.getBbWidth()+.12,Math.max(.7,mc.player.getBbHeight()),this::clearBody)){detail="Waiting for a clear formation flight path";return;}
                fly.requestFastAutopilot(velocity);detail="Flying in formation";return;
            }
            Vec3 step = localFlight.next(from, goal, mc.player.tickCount, mc.player.getBbWidth() + .12, Math.max(.7, mc.player.getBbHeight()), this::clearBody);
            if (step == null) { detail = "Waiting for a loaded, clear flight route"; return; }
            fly.requestAutopilot(PrinterFlight.safeVelocity(from, step, .65, mc.player.getBbWidth() + .12, Math.max(.7, mc.player.getBbHeight()), this::clearBody));
            detail = "Flying toward destination"; return;
        }
        if (useFlight && PrinterFlight.segmentClear(from, waypoint.add(0, 1.1, 0), mc.player.getBbWidth() + .12, 1.8, this::clearBody)) {
            if (!fly.isActive()) { fly.enable(); enabledFly = true; }
            brakeFlight();
            if (launchTick < 0 && mc.player.onGround()) {
                if (++launchAttempts > 2 || !PrinterFlight.segmentClear(from, from.add(0, 1.15, 0), mc.player.getBbWidth() + .12, 1.8, this::clearBody)) runOnly = true;
                else { mc.player.jumpFromGround(); launchTick = mc.player.tickCount; }
            }
            if (launchTick >= 0) {
                int age = mc.player.tickCount - launchTick;
                if (!mc.player.onGround() && age >= 3 && age % 4 == 3) mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                if (age > 30) { launchTick = -1; if (launchAttempts >= 2) runOnly = true; }
                detail = "Taking off"; return;
            }
        }
        if (!mc.player.onGround()) { detail = "Waiting to land before walking"; return; }
        if(match!=null) {
            Speed speed=Modules.get().get(Speed.class);
            if(speed.isActive()&&speed.speedMode.get()==SpeedModes.Vanilla) speed.vanillaSpeed.set(formationWalkSpeed(match.getDeltaMovement(),distance));
            if(distance<=radius){detail="Holding formation";return;}
        }
        // The existing builder's bounded walking search supplies stairs; no mining, paving or portals.
        BlockPos start = BlockPos.containing(from), local = BlockPos.containing(waypoint);
        List<HighwayPlan.Cell> route = walkingRoute.get(cell(start),cell(local),mc.player.tickCount,c->standable(block(c)),()->{
            Map<BlockPos,Boolean> checked=new HashMap<>();
            java.util.function.Predicate<BlockPos> safe=p->checked.computeIfAbsent(p,this::standable);
            List<BlockPos> candidates = new ArrayList<>();
            for (int y = -3; y <= 3; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                BlockPos p = new BlockPos(local.getX() + x, start.getY() + y, local.getZ() + z);
                if (safe.test(p)) candidates.add(p);
            }
            candidates.sort(Comparator.comparingDouble(p -> Vec3.atBottomCenterOf(p).distanceToSqr(goal)));
            for (BlockPos candidate : candidates) {
                List<HighwayPlan.Cell> found=HighwayPlan.route(cell(start),cell(candidate),c->safe.test(block(c)),
                    (a,b)->a.y()==b.y()||clearBody(PrinterFlight.body(Vec3.atBottomCenterOf(block(a.y()>b.y()?b:a).above()),mc.player.getBbWidth()+.12,1.8)));
                if(!found.isEmpty())return found;
            }
            return List.of();
        });
        if (route.isEmpty()) { detail = "Waiting for a supported walking route; no terrain will be altered"; return; }
        BlockPos step = block(route.get(Math.min(1, route.size() - 1)));
        Vec3 point = step.equals(BlockPos.containing(goal)) ? goal : Vec3.atBottomCenterOf(step);
        double lift = Math.max(0, point.y - from.y);
        if (!PrinterFlight.segmentClear(from.add(0, lift, 0), point.add(0, Math.max(0, from.y - point.y), 0), mc.player.getBbWidth() + .12, 1.8, this::clearBody)) { walkingRoute.reset();detail = "Waiting for the walking corridor to clear"; return; }
        mc.player.setYRot((float) Rotations.getYaw(point)); input.jump(lift > .1); input.forward(true);
        mc.player.setSprinting(distance > radius + 2 && mc.player.getFoodData().getFoodLevel() > 6);
        detail = "Walking toward destination";
    }

    static final class WalkingRoute {
        private HighwayPlan.Cell start,goal;
        private int plannedAt;
        private List<HighwayPlan.Cell> route=List.of();
        List<HighwayPlan.Cell> get(HighwayPlan.Cell from,HighwayPlan.Cell target,int tick,java.util.function.Predicate<HighwayPlan.Cell> safe,
                                  java.util.function.Supplier<List<HighwayPlan.Cell>> search) {
            boolean reuse=from.equals(start)&&target.equals(goal)&&tick>=plannedAt&&tick-plannedAt<5;
            if(reuse&&!route.isEmpty())reuse=safe.test(from)&&safe.test(route.get(Math.min(1,route.size()-1)));
            if(!reuse){start=from;goal=target;plannedAt=tick;route=List.copyOf(search.get());}
            return route;
        }
        void reset(){start=goal=null;route=List.of();}
    }

    static Vec3 formationGoal(Vec3 subject,float yaw,double spacing,int index,int count) {
        if(count<1||count>3||index<0||index>=count||!Double.isFinite(spacing)||spacing<=0)throw new IllegalArgumentException("Invalid formation");
        double angle=count==1?0:(index-(count-1)/2d)*Math.toRadians(60);
        Vec3 forward=Vec3.directionFromRotation(0,yaw),right=Vec3.directionFromRotation(0,yaw+90);
        return subject.add(forward.scale(-Math.cos(angle)*spacing)).add(right.scale(Math.sin(angle)*spacing));
    }
    static Vec3 retreatGoal(Vec3 from,Vec3 threat,double distance) {
        Vec3 away=new Vec3(from.x-threat.x,0,from.z-threat.z);
        if(away.lengthSqr()<.01)away=new Vec3(1,0,0);
        return from.add(away.normalize().scale(distance));
    }
    static Vec3 formationVelocity(Vec3 from,Vec3 goal,Vec3 subjectVelocity,double maximum) {
        Vec3 velocity=subjectVelocity.add(goal.subtract(from).scale(.22));double length=velocity.length();
        return length<=maximum?velocity:velocity.scale(maximum/length);
    }
    static double formationWalkSpeed(Vec3 subjectVelocity,double distance) {
        return Math.max(.1,Math.min(20,subjectVelocity.horizontalDistance()*20+Math.max(0,distance-.75)*2));
    }

    @EventHandler private void correction(PacketEvent.Receive event) {
        var packet=event.packet;
        if(!(packet instanceof ClientboundPlayerPositionPacket)&&!(packet instanceof net.minecraft.network.protocol.game.ClientboundDamageEventPacket))return;
        var world=mc.level;var player=mc.player;
        mc.execute(()->{if(world==mc.level&&player==mc.player)handleCorrection(packet);});
    }
    private void handleCorrection(net.minecraft.network.protocol.Packet<?> packet){
        if (packet instanceof ClientboundPlayerPositionPacket) resetFlightRoutes();
        if (survey != null && packet instanceof ClientboundPlayerPositionPacket) survey.correction();
        if(Utils.canUpdate()&&state.equals("Running")&&STASH_ACTIONS.contains(type())
            &&packet instanceof net.minecraft.network.protocol.game.ClientboundDamageEventPacket damage
            &&damage.entityId()==mc.player.getId()&&damage.getSource(mc.level).is(net.minecraft.world.damagesource.DamageTypes.FLY_INTO_WALL)){
            if(!landing)resetFlightRoutes(true);stopStashNavigation();brakeFlight();stashCollisionWalkUntil=mc.player.tickCount+200;
            detail="Server reported stash flight collision; landing before a walking retry";
            MonocleClient.LOG.warn("[Workers] Stash flight collision at {}; landing and walking for 200 ticks before retrying flight",mc.player.position());
        }
    }
    void acknowledgeSurvey(int delivery) { if (survey != null) survey.acknowledge(delivery); }

    private void survey() {
        if (!acquireMovement()) return;
        input.stop(); brakeFlight();
        detail = survey.detail();
        if (mc.player.isPassenger() || mc.player.isInWater() || mc.player.isInLava()) { detail = "Survey needs an unmounted player outside fluid"; return; }
        if (Modules.get().get(AutoEat.class).eating || Modules.get().get(AutoGap.class).isEating() || Modules.get().get(KillAura.class).attacking || mc.player.isUsingItem() || TickRate.INSTANCE.getTimeSinceLastTick() >= 1.5f) {
            survey.speed(1, true); detail = "Survey waiting for combat, food or server lag · " + survey.detail(); return;
        }
        Vec3 from = mc.player.position();
        if (action.get("y").getAsInt() < mc.level.getMinY() || action.get("y").getAsInt() + 2 > mc.level.getMaxY()) {
            fail("Survey altitude is outside this dimension's build limits"); return;
        }
        if (!survey.observe(from)) { survey.speed(1, true); detail = "Waiting for complete chunk coverage / host findings receipt · " + survey.detail(); return; }
        if (mc.player.tickCount % 20 == 0) Modules.get().get(dev.monocle.client.systems.modules.world.StashFinder.class).surveyFlush();
        if (survey.covered()) {
            if (!landBeforeHandoff()) return;
            Modules.get().get(dev.monocle.client.systems.modules.world.StashFinder.class).surveyFlush();
            complete("Survey complete · " + survey.detail()); return;
        }
        ElytraFly fly = Modules.get().get(ElytraFly.class);
        ItemStack glider = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!glider.has(DataComponents.GLIDER) || glider.isDamageableItem() && glider.getMaxDamage() - glider.getDamageValue() <= 10) {
            fail("Equip a usable elytra for stash hunting (more than 10 durability remaining)"); return;
        }
        if (fly.flightMode.get() != ElytraFlightModes.Vanilla || fly.horizontalSpeed.get() <= 0) { fail("Stash hunting requires Vanilla ElytraFly with a positive speed ceiling"); return; }
        if (!fly.isActive()) { fly.enable(); enabledFly = true; }
        Vec3 waypoint = localGoal(from, survey.target(), 8);
        if (mc.player.isFallFlying()) {
            double width = mc.player.getBbWidth() + .12, height = Math.max(.7, mc.player.getBbHeight());
            Vec3 step = localFlight.next(from, survey.target(), mc.player.tickCount, width, height, this::clearBody);
            if (step == null) {
                survey.speed(1, true);
                detail = "Survey needs a clear flight corridor; retrying local detour"; return;
            }
            Vec3 delta = step.subtract(from);
            double speed = survey.speed(Math.min(step.equals(waypoint) ? 6 : 1, fly.horizontalSpeed.get()), false);
            Vec3 velocity = delta.length() <= speed ? delta : delta.normalize().scale(speed);
            if (PrinterFlight.segmentClear(from, from.add(velocity), width, height, this::clearBody)) fly.requestFastAutopilot(velocity);
            detail = "Surveying · " + survey.detail(); return;
        }
        brakeFlight();
        if (launchTick < 0 && mc.player.onGround()) {
            if (!PrinterFlight.segmentClear(from, from.add(0, 1.15, 0), mc.player.getBbWidth() + .12, 1.8, this::clearBody)) { detail = "Clear headroom for survey takeoff"; return; }
            mc.player.jumpFromGround(); launchTick = mc.player.tickCount;
        }
        if (launchTick >= 0) {
            int age = mc.player.tickCount - launchTick;
            if (!mc.player.onGround() && age >= 3 && age % 4 == 3) mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            if (age > 30) launchTick = -1;
        }
        detail = "Taking off for survey · " + survey.detail();
    }

    static Vec3 localGoal(Vec3 from, Vec3 goal, double maximum) {
        if (from == null || goal == null || !Double.isFinite(maximum) || maximum <= 0 || maximum > 8
            || !Double.isFinite(from.lengthSqr()) || !Double.isFinite(goal.lengthSqr())) throw new IllegalArgumentException("Invalid local travel goal");
        Vec3 delta = goal.subtract(from); double distance = delta.length(); return distance <= maximum ? goal : from.add(delta.scale(maximum / distance));
    }
    private boolean clearBody(AABB box) {
        if (box.minY < mc.level.getMinY() || !PrinterFlight.loaded(box, mc.level.getChunkSource()::hasChunk)
            || !mc.level.noCollision(mc.player, box) || !mc.level.getEntities(mc.player, box, e -> e instanceof LivingEntity && !(e instanceof Player)).isEmpty()) return false;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(Math.nextDown(box.maxX), Math.nextDown(box.maxY), Math.nextDown(box.maxZ)))) {
            BlockState block = verified(pos);
            if (travelHazard(block) || !mc.level.getWorldBorder().isWithinBounds(pos)) return false;
        }
        return true;
    }
    static boolean travelHazard(BlockState block) {
        return block == null || !block.getFluidState().isEmpty() || block.is(Blocks.FIRE) || block.is(Blocks.SOUL_FIRE) || block.is(Blocks.POWDER_SNOW)
            || block.is(Blocks.END_PORTAL) || block.is(Blocks.END_GATEWAY) || block.is(Blocks.NETHER_PORTAL);
    }
    private boolean clearStashCruise(AABB body) {
        if(!clearBody(body))return false;
        AABB envelope=PrinterFlight.cruiseEnvelope(body);
        if(!PrinterFlight.loaded(envelope,mc.level.getChunkSource()::hasChunk))return false;
        for(BlockPos pos:BlockPos.betweenClosed(BlockPos.containing(envelope.minX,envelope.minY,envelope.minZ),
            BlockPos.containing(Math.nextDown(envelope.maxX),Math.nextDown(envelope.maxY),Math.nextDown(envelope.maxZ)))){
            BlockState block=verified(pos);
            if(block==null||!block.isAir()||!mc.level.getWorldBorder().isWithinBounds(pos))return false;
        }
        return true;
    }
    private BlockState verified(BlockPos position) {
        return !mc.level.hasChunkAt(position) || mc.level.getBlockStatePredictionHandler().serverVerifiedStates.containsKey(position.asLong()) ? null : mc.level.getBlockState(position);
    }
    private boolean standable(BlockPos feet) {
        return standable(Vec3.atBottomCenterOf(feet));
    }
    private boolean safeFloor(BlockPos position) {
        BlockState floor = verified(position);
        return safeSupport(floor)&&Block.isShapeFullBlock(floor.getCollisionShape(mc.level, position));
    }
    static boolean safeSupport(BlockState floor){
        return !travelHazard(floor)&&!floor.is(Blocks.MAGMA_BLOCK)&&!floor.is(Blocks.CACTUS)&&!floor.is(Blocks.CAMPFIRE)&&!floor.is(Blocks.SOUL_CAMPFIRE);
    }
    private VoxelShape safeLandingShape(BlockPos position){
        BlockState floor=verified(position);
        return safeSupport(floor)?floor.getCollisionShape(mc.level,position):Shapes.empty();
    }
    private boolean standable(Vec3 feet) {
        double width = mc.player.getBbWidth() + .12;
        return PrinterFlight.supported(feet, width, 1.8, this::safeFloor) && clearBody(PrinterFlight.body(feet, width, 1.8));
    }
    private Vec3 safeLandingBelow(Vec3 from, int limit) {
        return PrinterFlight.landingBelow(from,mc.player.getBbWidth(),mc.player.getBbHeight(),1.8,limit,this::safeLandingShape,this::clearBody);
    }
    static int retreatStep(Vec3 from,List<Vec3> route,int step){
        while(step>=0&&from.distanceToSqr(route.get(step))<.04)step--;
        return step;
    }
    static Iterator<BlockPos.MutableBlockPos> nearbyLandingCells(Vec3 from,int radius){
        var cells=BlockPos.spiralAround(BlockPos.containing(from),radius,net.minecraft.core.Direction.EAST,net.minecraft.core.Direction.SOUTH).iterator();
        if(cells.hasNext())cells.next(); // The current column was already checked.
        return cells;
    }
    static Vec3 nearbyLanding(Vec3 from,Iterator<? extends BlockPos> cells,int budget,java.util.function.Predicate<Vec3> safe){
        for(int n=0;n<budget&&cells.hasNext();n++){
            BlockPos cell=cells.next();
            Vec3 point=new Vec3(cell.getX()+.5,from.y,cell.getZ()+.5);
            if(safe.test(point))return point;
        }
        return null;
    }
    record LandingApproach(Vec3 lift,Vec3 target){}
    static LandingApproach nearbyLandingRoute(Vec3 from,Iterator<? extends BlockPos> cells,int budget,double width,double height,
                                              java.util.function.Predicate<AABB> clear,java.util.function.Predicate<Vec3> supported){
        for(int n=0;n<budget&&cells.hasNext();n++){
            BlockPos cell=cells.next();
            for(double rise=0;rise<=2;rise+=2){
                Vec3 lift=from.add(0,rise,0),point=new Vec3(cell.getX()+.5,lift.y,cell.getZ()+.5);
                if(PrinterFlight.segmentClear(from,lift,width,height,clear)&&PrinterFlight.segmentClear(lift,point,width,height,clear)&&supported.test(point))return new LandingApproach(lift,point);
            }
        }
        return null;
    }
    private boolean landBeforeHandoff() {
        if(!stopStashNavigation()){detail="Waiting for safe Baritone movement release before landing";return false;}
        if (mc.player.onGround()) { landing=false;landingFallbackTarget=landingLiftTarget=null;landingCandidates=null;landingSearchOrigin=null;landingFallbackRetry=0;landingRouteStep=-1;return true; }
        landing=true;
        if (inputPlayer == null && !acquireMovement()) return false;
        input.stop(); brakeFlight();
        if (mc.player.isFallFlying()) {
            Vec3 from = mc.player.position();Vec3 landing = safeLandingBelow(from, 32);
            if (landing != null) {
                if (from.y - landing.y < .2) { mc.player.stopFallFlying(); mc.player.setDeltaMovement(0, -.08, 0); }
                else Modules.get().get(ElytraFly.class).requestAutopilot(PrinterFlight.safeVelocity(from, landing, .3, mc.player.getBbWidth(), mc.player.getBbHeight(), this::clearBody));
                detail = "Landing on verified support at " + landing;
            } else {
                double width=mc.player.getBbWidth(),height=mc.player.getBbHeight();
                if(landingFallbackTarget==null&&mc.player.tickCount>=landingFallbackRetry){
                    if(landingCandidates==null||from.distanceToSqr(landingSearchOrigin)>4){
                        landingSearchOrigin=from;landingCandidates=nearbyLandingCells(from,16);
                    }
                    LandingApproach approach=nearbyLandingRoute(landingSearchOrigin,landingCandidates,16,width,height,this::clearBody,
                        p->safeLandingBelow(p,32)!=null);
                    if(approach!=null){landingLiftTarget=approach.lift();landingFallbackTarget=approach.target();}
                    boolean exhausted=!landingCandidates.hasNext();
                    landingFallbackRetry=mc.player.tickCount+(exhausted?20:1);
                    if(exhausted||landingFallbackTarget!=null)landingCandidates=null;
                }
                if(landingFallbackTarget!=null){
                    if(landingLiftTarget!=null){
                        if(from.distanceToSqr(landingLiftTarget)>.01){
                            Vec3 velocity=PrinterFlight.safeVelocity(from,landingLiftTarget,.3,width,height,this::clearBody);
                            if(velocity.lengthSqr()>.0001){Modules.get().get(ElytraFly.class).requestAutopilot(velocity);detail="Rising clear of chest edge before landing";return false;}
                            landingLiftTarget=landingFallbackTarget=null;
                        }else landingLiftTarget=null;
                    }
                }
                if(landingFallbackTarget!=null){
                    Vec3 velocity=PrinterFlight.safeVelocity(from,landingFallbackTarget,.3,width,height,this::clearBody);
                    if(velocity.lengthSqr()>.0001){Modules.get().get(ElytraFly.class).requestAutopilot(velocity);detail="Flying to nearby supported landing";return false;}
                    landingFallbackTarget=null;
                }
                if(landingCandidates!=null){detail="Searching for nearby supported landing";return false;}
                if((stashFlightTrail.size()>1||scanFlightRoute.size()>1)&&landingRouteStep!=-2){
                    List<Vec3> trail=stashFlightTrail.size()>1?stashFlightTrail:scanFlightRoute;
                    if(landingRouteStep<0)landingRouteStep=trail.size()-1;
                    landingRouteStep=retreatStep(from,trail,landingRouteStep);
                    if(landingRouteStep>=0){
                        Vec3 velocity=PrinterFlight.safeVelocity(from,trail.get(landingRouteStep),.3,width,height,this::clearBody);
                        if(velocity.lengthSqr()>.0001){Modules.get().get(ElytraFly.class).requestAutopilot(velocity);detail="No nearby landing; returning along confirmed flight route";return false;}
                    }
                    landingRouteStep=-2;
                }
                if(survey!=null&&PrinterFlight.segmentClear(from,from.add(0,-8,0),width,height,this::clearBody)){
                    Modules.get().get(ElytraFly.class).requestAutopilot(new Vec3(0,-.3,0));detail="Descending through clear air to find safe survey landing";
                }else detail="No clear supported landing nearby or on the saved route; retrying";
            }
        } else detail = "Waiting to reach the ground before yielding the task";
        return false;
    }
    private boolean acquireMovement() {
        if(!stopStashNavigation()||dev.monocle.client.pathing.BaritoneUtils.movementBusy()){
            input.stop();brakeFlight();detail="Waiting for path movement to release before worker steering";return false;
        }
        if (inputPlayer == null) { inputPlayer = mc.player; previousInput = mc.player.input; mc.player.input = input; }
        if (inputPlayer != mc.player || mc.player.input != input) {
            String reason="Another feature took movement control: expected="+input.getClass().getName()+", actual="+(mc.player.input==null?"none":mc.player.input.getClass().getName())
                +", previous="+(previousInput==null?"none":previousInput.getClass().getName())+", playerChanged="+(inputPlayer!=mc.player)+", action="+type();
            MonocleClient.LOG.warn("[Workers] {}",reason);fail(reason);return false;
        }
        return true;
    }
    private void brakeFlight() { stashCruiseSpeed=0; ElytraFly fly = Modules.get().get(ElytraFly.class); if (fly.isActive() && fly.flightMode.get() == ElytraFlightModes.Vanilla) fly.requestAutopilot(Vec3.ZERO); }
    private void releaseMovement() {
        input.stop();
        if (inputPlayer != null) {
            if (inputPlayer.input == input && previousInput != null) inputPlayer.input = previousInput;
            inputPlayer.setSprinting(false); inputPlayer = null; previousInput = null;
            Modules.get().get(ElytraFly.class).clearAutopilot();
        }
        ElytraFly fly = Modules.get().get(ElytraFly.class);
        if (enabledFly && Utils.canUpdate() && mc.player.onGround()) { if (fly.isActive()) fly.disable(); enabledFly = false; }
        launchTick = -1;
    }
    private void release() {release(false);}
    private void release(boolean keepFlight) {
        stopStashNavigation();
        Modules.get().get(AutoMend.class).releaseWorkerMaintenance(this);stashMending=stashMendDeferred=false;stashMendDamage=-1;stashMendIdle=0;
        if(personalSupplies!=null){personalSupplies.close();personalSupplies=null;}
        if (stashScan != null) stashScan.close();
        if (stashResupply != null) stashResupply.close();
        if (stashDeposit != null) stashDeposit.close();
        if (recovery != null) recovery.close();
        if(keepFlight){input.stop();brakeFlight();}else{releaseMovement();stashHandoff=false;}
        if (crystalGuard()) {
            quietCrystalGuard();
            Modules.get().get(CrystalAura.class).clearGuardTargets();
            Modules.get().get(AutoTotem.class).guardStrict(false);
        }
        if (action != null && (type().equals("Modules") || crystalGuard()) && (modulesApplied || restoredModuleLease)) for (var entry : originals.entrySet()) {
            Module module = Modules.get().get(entry.getKey());
            if (entry.getKey().equals("elytra-fly") && !safeModuleRestore()) continue;
            if (module != null && (crystalGuard() || module.isActive() == action.getAsJsonObject("modules").get(entry.getKey()).getAsBoolean())) setActive(module, entry.getValue().getAsBoolean());
        }
        modulesApplied = restoredModuleLease = false;
        listen(issuedDrop());
    }
    private boolean safeModuleRestore() {
        return !type().equals("Modules") || !modulesApplied && !restoredModuleLease || !originals.has("elytra-fly") || originals.get("elytra-fly").getAsBoolean()
            || !Utils.canUpdate() || mc.player.onGround() || !Modules.get().get(ElytraFly.class).isActive();
    }
    private dev.monocle.client.pathing.BaritoneUtils.StashNavigation stashNavigation;
    private BlockPos stashNavTarget;
    private int stashNavMode;
    private boolean stopStashNavigation() {
        if (stashNavigation != null) { if(!stashNavigation.release())return false;stashNavigation = null; }
        stashNavTarget = null;
        return true;
    }
    private boolean nativeBusy() {
        return bots.crew.localAssigned() || !highwayOwned&&Modules.get().get(HighwayBuilder.class).hasJob() || Modules.get().get(PrinterHelper.class).isActive()
            || Modules.get().get(dev.monocle.client.systems.modules.world.StashBuilder.class).isActive()
            || !type().equals("Modules") && stashNavigation == null && dev.monocle.client.pathing.BaritoneUtils.movementBusy();
    }
    private void scanStash(boolean workflowHandoff) {
        if (!dev.monocle.client.pathing.BaritoneUtils.IS_AVAILABLE) { fail("Stash scanning requires Baritone for Minecraft 26.2; install it in this profile's mods folder"); return; }
        if(stashInteractionPaused())return;
        stashScan.tick();
        detail = stashScan.detail();
        if (stashScan.done()) {result = stashScan.telemetry();completeStash("Stash scan complete; inspect unscanned counts and missing chunks before trusting coverage",workflowHandoff);return;}
        if (stashScan.approaching()) {
            ElytraFly fly=Modules.get().get(ElytraFly.class);
            boolean air=stashFlightDurabilitySafe(gliderDurability(),mc.player.isFallFlying())&&fly.flightMode.get()==ElytraFlightModes.Vanilla&&fly.horizontalSpeed.get()>0
                &&!runOnly&&(mc.player.isFallFlying()||stashFlightNeeded(stashScan.target().getY(),mc.player.getY(),false));
            stashScan.planCoverage(this::scanCoverageFloor,this::scanCoverageWalking,
                feet->clearBody(PrinterFlight.body(feet,stashFlightWidth(mc.player.getBbWidth()),.6)),air,
                mc.player.getEyeHeight(net.minecraft.world.entity.Pose.STANDING),mc.player.getEyeHeight(net.minecraft.world.entity.Pose.FALL_FLYING));
            stashScan.approachProgress(mc.player.getEyePosition());
            moveToStashContainer(stashScan.target(),stashScan.navigationTarget(),stashScan.hasHopperPerch()?0:stashScan.targetIsHopper()?2:-1,stashScan.targetIsHopper());
        } else {
            BotScanCoverage.Stop stop=stashScan.coverageStop();
            if(stop!=null&&stashScan.coverageReadSafe()&&mc.player.position().distanceToSqr(stop.feet())>.09){
                if(mc.player.isFallFlying()&&stashScan.target()!=null)flyToStashChest(stashScan.target(),stashScan.targetIsHopper());
                else if(!stop.flying()&&mc.player.onGround())walkToStashContainer(scanCoverageCell(stop.feet()),0);
                else holdStashPosition();
            }else if(stop!=null)holdStashPosition();
            else {
                BlockPos ahead=stashScan.walkingLookAhead(stashNavTarget);
                if(ahead!=null)walkToStashContainer(ahead,-1);
                else holdStashPosition();
            }
        }
    }
    private Vec3 scanCoverageFloor(BlockPos support){
        if(mc.player.isFallFlying()||safeLandingShape(support).isEmpty())return null;
        double width=mc.player.getBbWidth()+.12;
        return PrinterFlight.landingBelow(Vec3.atBottomCenterOf(support.above()),width,1.8,1.8,1,this::safeLandingShape,this::clearBody);
    }
    private BlockPos scanCoverageCell(Vec3 feet){
        // Match Baritone's playerFeet convention on chest tops and half slabs.
        BlockPos cell=BotScanCoverage.walkingCell(feet);
        return mc.level.getBlockState(cell).getBlock() instanceof net.minecraft.world.level.block.SlabBlock?cell.above():cell;
    }
    private boolean scanCoverageWalking(Vec3 feet){
        if(mc.player.isFallFlying()||Math.abs(feet.y-mc.player.getY())>2.5)return false;
        Vec3 supported=PrinterFlight.landingBelow(feet.add(0,.02,0),mc.player.getBbWidth()+.12,1.8,1.8,1,this::safeLandingShape,this::clearBody);
        return supported!=null&&supported.distanceToSqr(feet)<.001;
    }
    private boolean stashInteractionPaused(){
        if(maintainStashClearance())return true;
        if(!Modules.get().get(AutoEat.class).eating&&!mc.player.isUsingItem()&&TickRate.INSTANCE.getTimeSinceLastTick()<1.5f)return false;
        stopStashNavigation();brakeFlight();scanFlightProgressTick=mc.player.tickCount;detail="Stash work waiting for eating or server response";return true;
    }
    static boolean stashFlightNeeded(double targetY,double playerY,boolean gliding){return gliding||Math.abs(targetY-playerY)>2.5;}
    static boolean stashCollisionWalking(int until,int tick){int remaining=until-tick;return remaining>0&&remaining<=200;}
    static double stashFlightWidth(double width){return width+.12;}
    private boolean tickStashMaintenance(){
        // Never interrupt a sent kit click, cursor recovery, scan receipt or host reservation.
        boolean safe=(mc.player.containerMenu==mc.player.inventoryMenu||personalSupplies!=null&&personalSupplies.busy())&&mc.player.containerMenu.getCarried().isEmpty()
            &&(stashResupply==null||stashResupply.canYieldInventory())
            &&(stashDeposit==null||stashDeposit.canYieldInventory())
            &&(stashScan==null||stashScan.canYieldInventory());
        AutoMend mend=Modules.get().get(AutoMend.class);
        if(!safe){mend.workerYielding(this,false);return false;}
        if(!dev.monocle.client.utils.network.WorldReadiness.INSTANCE.inventoryReady()){
            mend.workerYielding(this,false);stopStashNavigation();brakeFlight();
            detail="Waiting for server-confirmed player inventory before personal maintenance";
            if(mc.player.containerMenu==mc.player.inventoryMenu&&mc.player.tickCount%20==0)refreshInventory();
            return true;
        }
        if(mc.player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)&&gliderDurability()<STASH_FLIGHT_MIN_DURABILITY
            &&(personalSupplies==null||!personalSupplies.busy())){
            int spare=stashSpareElytra(mc.player.getInventory().getNonEquipmentItems(),mc.player.getItemBySlot(EquipmentSlot.CHEST));
            if(spare>=0){
                mend.workerYielding(this,false);stopStashNavigation();
                if(!landBeforeHandoff())return true;
                if(mc.player.containerMenu!=mc.player.inventoryMenu||mc.player.isUsingItem())return true;
                dev.monocle.client.utils.player.InvUtils.move().from(spare).toArmor(EquipmentSlot.CHEST.getIndex());
                stashMending=stashMendDeferred=false;stashMendDamage=-1;stashMendIdle=0;resetFlightRoutes();progress.resetClock();
                detail="Equipping spare elytra before stash flight reserve is exhausted";return true;
            }
        }
        boolean repair=mend.requestWorkerMaintenance(this);
        var xp=net.minecraft.world.item.Items.EXPERIENCE_BOTTLE;
        if(repair&&BotPersonalSupplies.count(xp)==0&&(personalSupplies==null||!personalSupplies.busy())){
            if(personalSupplies==null)personalSupplies=new BotPersonalSupplies();
            if(stashMendMayDefer(xp,false,gliderDurability())&&!personalSupplies.canRestock(xp,this::personalSupplyIsCargo)){
                deferStashMending(mend);repair=false;
            }else stashMendDeferred=false;
        }else if(!repair||BotPersonalSupplies.count(xp)>0)stashMendDeferred=false;
        boolean food=Modules.get().get(AutoEat.class).isActive()&&personalFoodCount()<=8;
        if(food&&Modules.get().get(AutoEat.class).blacklist.get().contains(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE))
            throw new IllegalStateException("Personal food restock needs enchanted golden apples allowed in Auto Eat; change the job profile");
        net.minecraft.world.item.Item requested=food?net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE
            :repair&&BotPersonalSupplies.count(net.minecraft.world.item.Items.EXPERIENCE_BOTTLE)==0?net.minecraft.world.item.Items.EXPERIENCE_BOTTLE:null;
        if(personalSupplies!=null&&personalSupplies.canCancel(requested,mc.player.containerMenu.getCarried().isEmpty())){
            MonocleClient.LOG.info("[Workers] Cancelling obsolete personal supply request before any withdrawal: {}",personalSupplies.diagnostic());
            personalSupplies.close();
        }
        boolean supplies=personalSupplies!=null&&personalSupplies.busy();
        if(!repair&&!food&&!supplies){
            if(stashMending){stashMending=false;stashMendDamage=-1;stashMendIdle=0;resetFlightRoutes();progress.resetClock();}
            return false;
        }
        mend.workerYielding(this,false);
        if(supplies&&personalSupplies.approaching()){
            personalSupplies.approachTick();BlockPos target=personalSupplies.target();
            moveToStashContainer(target,BotStashScan.navigationTarget(target),-1,false);detail="Approaching personal supply cache at "+target.toShortString();return true;
        }
        stopStashNavigation();
        if(!supplies){
            if(stashResupply!=null)stashResupply.close();
            if(stashDeposit!=null)stashDeposit.close();
            if(stashScan!=null)stashScan.close();
        }
        if(!landBeforeHandoff())return true;
        holdStashPosition();
        if(Modules.get().get(AutoEat.class).eating||Modules.get().get(AutoGap.class).isEating()||mc.player.isUsingItem()){
            detail="Personal maintenance yielding to food";return true;
        }
        if(requested!=null||supplies){
            if(personalSupplies==null)personalSupplies=new BotPersonalSupplies();
            boolean waiting;
            try{waiting=personalSupplies.tick(requested,this::personalSupplyIsCargo);}
            catch(BotPersonalSupplies.Unavailable missing){
                if(!stashMendMayDefer(requested,personalSupplies.diagnostic().get("pendingWithdrawal").getAsBoolean(),gliderDurability()))throw missing;
                personalSupplies.close();deferStashMending(mend);resetFlightRoutes();progress.resetClock();return false;
            }
            if(waiting){
                detail=personalSupplies.detail();BlockPos target=personalSupplies.target();
                if(target!=null&&mc.player.containerMenu==mc.player.inventoryMenu&&personalSupplies.approaching())
                    moveToStashContainer(target,BotStashScan.navigationTarget(target),-1,false);
                return true;
            }
        }
        if(repair){
            if(mc.player.containerMenu!=mc.player.inventoryMenu||!mc.player.containerMenu.getCarried().isEmpty())return true;
            var exp=dev.monocle.client.utils.player.InvUtils.find(net.minecraft.world.item.Items.EXPERIENCE_BOTTLE);
            if(exp.found()&&!exp.isHotbar()){
                dev.monocle.client.utils.player.InvUtils.quickSwap().from(exp.slot()).toHotbar(mc.player.getInventory().getSelectedSlot());
                detail="Preparing experience bottles for elytra maintenance";return true;
            }
            int damage=mc.player.getItemBySlot(EquipmentSlot.CHEST).getDamageValue();
            if(stashMendDamage<0||damage<stashMendDamage)stashMendIdle=0;
            stashMendDamage=damage;
            if(++stashMendIdle>200)throw new IllegalStateException("Elytra mending made no progress for 200 ticks; check Mending and XP supply");
            stashMending=true;mend.workerYielding(this,true);detail="Mending stash elytra · "+mend.getInfoString();return true;
        }
        return false;
    }
    private void deferStashMending(AutoMend mend){
        if(!stashMendDeferred)MonocleClient.LOG.warn("[Workers] XP unavailable; deferring stash mending with {} elytra durability remaining",gliderDurability());
        stashMendDeferred=true;stashMending=false;stashMendDamage=-1;stashMendIdle=0;mend.workerYielding(this,false);
    }
    static boolean stashMendMayDefer(net.minecraft.world.item.Item requested,boolean pending,int durability){
        return requested==net.minecraft.world.item.Items.EXPERIENCE_BOTTLE&&!pending&&stashFlightDurabilitySafe(durability,false);
    }
    static int stashSpareElytra(List<ItemStack> inventory,ItemStack equipped){
        if(equipped.getOrDefault(DataComponents.ENCHANTMENTS,net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY).entrySet().stream()
            .anyMatch(e->e.getKey().is(net.minecraft.world.item.enchantment.Enchantments.BINDING_CURSE)&&e.getIntValue()>0))return -1;
        int best=-1,remaining=STASH_FLIGHT_MIN_DURABILITY-1;
        for(int i=0;i<Math.min(36,inventory.size());i++){
            ItemStack stack=inventory.get(i);int durability=stack.getMaxDamage()-stack.getDamageValue();
            if(stack.is(net.minecraft.world.item.Items.ELYTRA)&&durability>remaining){best=i;remaining=durability;}
        }
        return best;
    }
    private int personalFoodCount(){
        AutoEat eat=Modules.get().get(AutoEat.class);
        return AutoEat.foodCount(mc.player.getInventory(),mc.player.getOffhandItem(),eat.blacklist.get(),eat.protectNamed.get());
    }
    private boolean personalSupplyIsCargo(ItemStack stack){
        if(action.has("kitTypeId")&&BotStashScan.matchesKit(stack,action))return true;
        if(stashResupply!=null&&!stashResupply.personalBox(stack))return true;
        return false;
    }
    static boolean stashCruiseNeeded(double horizontal,boolean gliding,boolean launching,boolean available){
        return available&&(horizontal>4||gliding||launching);
    }
    static boolean stashFlightRetryAllowed(int failures){return failures<3;}
    private void stashFlightFailed(String reason){
        stashFlightFailures++;stashFlightRetryAt=mc.player.tickCount+20;launchTick=-1;
        scanFlightRoute=List.of();localFlight.reset();
        MonocleClient.LOG.warn("[Workers] Stash flight attempt {}/3 failed at {} toward {}: {}",stashFlightFailures,mc.player.position(),stashFlightAttemptTarget,reason);
        if(!stashFlightRetryAllowed(stashFlightFailures)){runOnly=true;landBeforeHandoff();}
        else brakeFlight();
        detail="Stash flight retry "+Math.min(stashFlightFailures,3)+"/3 · "+reason;
    }
    static boolean stashTakeoffClear(AABB body,java.util.function.Predicate<AABB> clear){
        // A chest-side standing position is legal even when an artificially widened body clips the chest.
        return clear.test(body.expandTowards(0,1.15,0).deflate(1e-7));
    }
    static boolean stashGlideRequest(int age,boolean grounded){return !grounded&&age>=3&&age%4==3;}
    private void moveToStashContainer(BlockPos target,BlockPos walkingTarget,int mode,boolean hopper){
        BotScanCoverage.Stop coverage=stashScan==null?null:stashScan.coverageStop();
        if(coverage!=null&&!coverage.covers(target))coverage=null;
        if(coverage!=null){walkingTarget=scanCoverageCell(coverage.feet());mode=0;}
        BlockPos travelTarget=coverage==null?target:walkingTarget;
        if(!target.equals(stashFlightAttemptTarget)){
            stashFlightAttemptTarget=target;stashFlightFailures=stashFlightRetryAt=0;launchTick=-1;runOnly=false;
            stashWalkProgress=null;
        }
        if(mc.player.tickCount<stashFlightRetryAt){stopStashNavigation();brakeFlight();detail+=" · waiting before flight retry";return;}
        double horizontal=Math.hypot(target.getX()+.5-mc.player.getX(),target.getZ()+.5-mc.player.getZ());
        ElytraFly fly=Modules.get().get(ElytraFly.class);
        boolean flightAvailable=stashFlightDurabilitySafe(gliderDurability(),mc.player.isFallFlying())&&fly.flightMode.get()==ElytraFlightModes.Vanilla&&fly.horizontalSpeed.get()>0;
        Vec3 position=mc.player.position();int tick=mc.player.tickCount;
        boolean paused=stashNavigation!=null&&stashNavigation.paused();
        if(stashWalkProgress==null||paused||position.distanceToSqr(stashWalkProgress)>=.25){stashWalkProgress=position;stashWalkProgressTick=tick;}
        if(paused){detail+=" · Baritone paused by eating/combat";return;}
        if(coverage!=null&&stashNavigation!=null&&stashFlightStalled(stashWalkProgress,position,stashWalkProgressTick,tick)){
            stashScan.rejectCoverage();stopStashNavigation();detail="Coverage stop unreachable; retrying normal chest approach";return;
        }
        boolean retryFlight=stashWalkingRetry(runOnly||stashNavigation!=null,flightAvailable,paused,stashCollisionWalking(stashCollisionWalkUntil,tick),stashFlightStalled(stashWalkProgress,position,stashWalkProgressTick,tick));
        if(retryFlight){
            runOnly=true;
            if(!stopStashNavigation()){detail="Waiting for safe walking release before retrying flight";return;}
            MonocleClient.LOG.warn("[Workers] Walking fallback made no movement for 60 ticks toward {}; retrying flight from {}",target,position);
            runOnly=false;stashFlightFailures=stashFlightRetryAt=0;launchTick=-1;stashWalkProgress=null;
            resetStashFlightProgress();
        }
        boolean cruise=stashCruiseNeeded(horizontal,mc.player.isFallFlying(),launchTick>=0,
            flightAvailable);
        boolean walkingStalled=stashResupply!=null&&stashResupply.approachNeedsFlight()&&flightAvailable
            &&(!mc.player.onGround()||stashTakeoffClear(mc.player.getBoundingBox(),this::clearBody));
        if(!runOnly&&flightAvailable&&!stashCollisionWalking(stashCollisionWalkUntil,mc.player.tickCount)&&(stashFlightNeeded(travelTarget.getY(),mc.player.getY(),mc.player.isFallFlying())||coverage!=null&&coverage.flying()
            ||retryFlight||walkingStalled||cruise)){
            if(walkingStalled&&stashNavigation!=null)MonocleClient.LOG.warn("[Workers] Walking approach made no useful progress for 60 ticks at {}; trying checked flight from {}",target,mc.player.position());
            stopStashNavigation();flyToStashChest(target,hopper);return;
        }
        if(mc.player.isFallFlying()){landBeforeHandoff();return;}
        BlockPos destination=horizontal>24&&stashFlightNeeded(travelTarget.getY(),mc.player.getY(),false)
            ?new BlockPos(target.getX(),mc.player.blockPosition().getY(),target.getZ()):walkingTarget;
        int destinationMode=destination.equals(walkingTarget)?mode:3;
        walkToStashContainer(destination,destinationMode);
    }
    private void walkToStashContainer(BlockPos destination,int destinationMode){
        releaseMovement();brakeFlight();
        if(stashNavigation!=null&&(!destination.equals(stashNavTarget)||destinationMode!=stashNavMode)&&!stopStashNavigation()){
            detail="Waiting for safe Baritone movement release before changing chest target";return;
        }
        if(stashNavigation==null){stashNavigation=new dev.monocle.client.pathing.BaritoneUtils.StashNavigation();stashNavTarget=destination;stashNavMode=destinationMode;}
        stashNavigation.moveTo(destination,destinationMode);
        detail+=" · Baritone "+stashNavigation.status();
    }
    private void holdStashPosition(){stopStashNavigation();if(mc.player.isFallFlying())brakeFlight();else releaseMovement();}
    static boolean stashWalkingRetry(boolean walking,boolean flightAvailable,boolean paused,boolean collisionCooldown,boolean stalled){
        return walking&&flightAvailable&&!paused&&!collisionCooldown&&stalled;
    }
    static boolean scanHover(Vec3 feet,BlockPos target){
        Vec3 center=Vec3.atCenterOf(target);
        return feet.y>=target.getY()+1+PrinterFlight.CRUISE_CLEARANCE&&feet.y<=target.getY()+1.2+PrinterFlight.CRUISE_CLEARANCE
            &&Math.hypot(feet.x-center.x,feet.z-center.z)<=1.8;
    }
    static boolean stashFlightGoal(Vec3 feet,BlockPos target,boolean hopper,boolean visible){
        return hopper?scanHover(feet,target):visible;
    }
    static boolean stashFinalApproach(Vec3 feet,BlockPos target){
        return target!=null&&feet.distanceToSqr(Vec3.atCenterOf(target))<=PrinterFlight.MAX_DISTANCE*PrinterFlight.MAX_DISTANCE;
    }
    static int stashFlightStep(Vec3 from,List<Vec3> route,int step,boolean openingReached){
        // Intermediate corners tolerate drift; the final approach must actually expose the container.
        while(step<route.size()&&from.distanceToSqr(route.get(step))<.04&&(step<route.size()-1||openingReached))step++;
        return step;
    }
    static boolean stashFlightStalled(Vec3 previous,Vec3 actual,int progressTick,int tick){
        return previous!=null&&actual.distanceToSqr(previous)<.25&&tick-progressTick>=60;
    }
    private void resetStashFlightProgress(){
        stashCruiseSpeed=0;
        scanFlightWatchTarget=null;scanFlightProgress=null;scanFlightProgressTick=scanFlightRecoveries=0;stashFlightDiagnostic=null;
        scanFlightTarget=null;scanFlightRoute=List.of();scanFlightEscaping=false;scanFlightStep=scanFlightRetry=0;localFlight.reset();
    }
    static double stashApproachSpeed(Vec3 from,Vec3 waypoint,BlockPos target,double configured){
        return Math.min(configured,from.distanceToSqr(Vec3.atCenterOf(target))>36&&from.distanceToSqr(waypoint)>4?1:.28);
    }
    static double stashCruiseSpeed(double current,double configured,boolean acceleration,double start,double step){
        double ceiling=Math.min(PrinterFlight.MAX_SAFE_SPEED,configured);
        return acceleration?Math.min(ceiling,Math.max(0,current)+start+step*.1):ceiling;
    }
    static boolean stashFlightDurabilitySafe(int remaining,boolean gliding){
        // Leave a landing reserve; don't reject an ongoing flight at its higher takeoff threshold.
        return gliding?remaining>STASH_FLIGHT_LANDING_RESERVE:remaining>=STASH_FLIGHT_MIN_DURABILITY;
    }
    private int gliderDurability(){
        ItemStack glider=mc.player.getItemBySlot(EquipmentSlot.CHEST);
        return !glider.has(DataComponents.GLIDER)?0:glider.isDamageableItem()?glider.getMaxDamage()-glider.getDamageValue():Integer.MAX_VALUE;
    }
    private void resetFlightRoutes() {resetFlightRoutes(false);}
    private void resetFlightRoutes(boolean keepTrail) {
        stashCruiseSpeed=0;
        stashWalkProgress=null;
        walkingRoute.reset();
        stashClearanceTarget=null;
        landing=false;
        localFlight.reset();
        scanFlightTarget = null; scanFlightRoute = List.of(); scanFlightStep = scanFlightRetry = 0;
        scanFlightEscaping=false;
        landingRouteStep = -1; landingFallbackRetry = 0; landingFallbackTarget = landingLiftTarget = null;
        landingCandidates = null; landingSearchOrigin = null;
        if(!keepTrail){stashFlightTrail.clear();stashCollisionWalkUntil=0;}
    }
    private boolean maintainStashClearance(){
        if(landing||runOnly||stashCollisionWalking(stashCollisionWalkUntil,mc.player.tickCount))return !landBeforeHandoff();
        if(!mc.player.isFallFlying())return false;
        ElytraFly fly=Modules.get().get(ElytraFly.class);
        if(fly.flightMode.get()!=ElytraFlightModes.Vanilla||fly.horizontalSpeed.get()<=0)return false;
        if(!fly.isActive()){fly.enable();enabledFly=true;}
        Vec3 from=mc.player.position();double width=stashFlightWidth(mc.player.getBbWidth()),height=mc.player.getBbHeight();
        // Final container approaches may enter the cruise buffer, never actual blocks or hazards.
        if(stashFinalApproach(from,scanFlightWatchTarget)&&clearBody(PrinterFlight.body(from,width,height))){stashClearanceTarget=null;return false;}
        if(clearStashCruise(PrinterFlight.body(from,width,height))){stashClearanceTarget=null;return false;}
        if(!acquireMovement())return true;
        stopStashNavigation();input.stop();
        if(stashClearanceTarget==null||!clearStashCruise(PrinterFlight.body(stashClearanceTarget,width,height))
            ||!PrinterFlight.segmentClear(from,stashClearanceTarget,width,height,this::clearBody))
            stashClearanceTarget=PrinterFlight.clearanceExit(from,width,height,this::clearBody,this::clearStashCruise);
        if(stashClearanceTarget==null){
            if(mc.player.tickCount>=stashFlightRetryAt)stashFlightFailed("No three-block flight clearance");
            else brakeFlight();
            return true;
        }
        Vec3 velocity=PrinterFlight.safeVelocity(from,stashClearanceTarget,Math.min(.3,fly.horizontalSpeed.get()),width,height,this::clearBody);
        fly.requestAutopilot(velocity);localFlight.reset();scanFlightRoute=List.of();scanFlightProgressTick=mc.player.tickCount;
        detail="Gaining three-block stash flight clearance";return true;
    }
    static Vec3 stashCruiseGoal(Vec3 from,BlockPos target){
        double offset=.5+.3+PrinterFlight.CRUISE_CLEARANCE+.1;
        double dx=from.x-target.getX()-.5,dz=from.z-target.getZ()-.5;
        return new Vec3(target.getX()+.5+(Math.abs(dx)>=Math.abs(dz)?Math.copySign(offset,dx):0),
            Math.max(from.y,target.getY()+1+PrinterFlight.CRUISE_CLEARANCE+.05),
            target.getZ()+.5+(Math.abs(dx)<Math.abs(dz)?Math.copySign(offset,dz):0));
    }
    static boolean recordStashFlight(List<Vec3> trail,Vec3 position){
        if(!trail.isEmpty()&&trail.getLast().distanceToSqr(position)<.25)return true;
        if(trail.size()>=PrinterFlight.MAX_NODES)return false;
        trail.add(new Vec3(position.x,position.y,position.z));return true;
    }
    private void flyToStashChest(BlockPos target,boolean hopper){
        BotScanCoverage.Stop coverage=stashScan==null?null:stashScan.coverageStop();
        Vec3 coverageGoal=coverage!=null&&coverage.flying()&&coverage.covers(target)?coverage.feet():null;
        if(!java.util.Objects.equals(coverageGoal,scanCoverageFlightGoal)){scanFlightRoute=List.of();scanFlightRetry=0;scanCoverageFlightGoal=coverageGoal;}
        if(!acquireMovement())return;
        input.stop();
        if(mc.player.onGround())stashFlightTrail.clear();
        // ponytail: cap the return trail at the existing route-node budget; land before renewing it.
        if(!recordStashFlight(stashFlightTrail,mc.player.position())){landBeforeHandoff();detail="Renewing bounded flight trail · "+detail;return;}
        ElytraFly fly=Modules.get().get(ElytraFly.class);
        if(!stashFlightDurabilitySafe(gliderDurability(),mc.player.isFallFlying())){
            if(!mc.player.onGround()){landBeforeHandoff();detail="Elytra reserve low · "+detail;return;}
            fail("Equip an elytra with at least "+STASH_FLIGHT_MIN_DURABILITY+" durability remaining for elevated stash work (equipped "+BuiltInRegistries.ITEM.getKey(mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem())+", remaining "+gliderDurability()+")");return;
        }
        if(fly.flightMode.get()!=ElytraFlightModes.Vanilla||fly.horizontalSpeed.get()<=0){fail("Elevated stash work requires Vanilla ElytraFly with positive speed");return;}
        if(!fly.isActive()){fly.enable();enabledFly=true;}
        Vec3 from=mc.player.position();
        int tick=mc.player.tickCount;
        // Establish the approach before takeoff so the clearance keeper uses the same destination.
        if(!target.equals(scanFlightWatchTarget)){
            resetStashFlightProgress();scanFlightWatchTarget=target;scanFlightProgress=from;scanFlightProgressTick=tick;
        }else if(scanFlightProgress==null||from.distanceToSqr(scanFlightProgress)>=.25){scanFlightProgress=from;scanFlightProgressTick=tick;if(mc.player.isFallFlying())stashFlightFailures=0;}
        if(!mc.player.isFallFlying()){
            brakeFlight();
            if(launchTick<0){
                if(mc.player.onGround()){
                    if(!stashTakeoffClear(mc.player.getBoundingBox(),this::clearBody)){stashFlightFailed("Takeoff obstructed toward "+target.toShortString());return;}
                    mc.player.jumpFromGround();
                }
                launchTick=tick;
            }
            if(launchTick>=0){int age=mc.player.tickCount-launchTick;
                if(stashGlideRequest(age,mc.player.onGround()))mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player,ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                if(age>30){stashFlightFailed("Server did not confirm takeoff within 30 ticks");return;}
            }
            detail="Taking off to scan chest "+target.toShortString();return;
        }
        launchTick=-1;
        double width=stashFlightWidth(mc.player.getBbWidth()),height=mc.player.getBbHeight();
        boolean approach=stashFinalApproach(from,target);
        // World state cannot change inside this client-thread search; discard cached corridors next tick.
        Map<AABB,Boolean> checkedCorridors=new HashMap<>();
        java.util.function.Predicate<AABB> clearance=approach?this::clearBody:this::clearStashCruise;
        java.util.function.Predicate<AABB> flightClear=box->checkedCorridors.computeIfAbsent(box,clearance::test);
        if(stashFlightStalled(scanFlightProgress,from,scanFlightProgressTick,tick)){
            scanFlightProgressTick=tick;localFlight.reset();scanFlightRoute=List.of();scanFlightStep=1;scanFlightRetry=tick;
            stashFlightDiagnostic=new JsonObject();stashFlightDiagnostic.addProperty("target",target.toShortString());stashFlightDiagnostic.addProperty("position",from.toString());
            stashFlightDiagnostic.addProperty("width",width);stashFlightDiagnostic.addProperty("height",height);
            AABB body=PrinterFlight.body(from,width,height);
            stashFlightDiagnostic.addProperty("startClear",clearBody(body));stashFlightDiagnostic.addProperty("loaded",PrinterFlight.loaded(body,mc.level.getChunkSource()::hasChunk));
            stashFlightDiagnostic.addProperty("collisionFree",mc.level.noCollision(mc.player,body));stashFlightDiagnostic.addProperty("attempt",++scanFlightRecoveries);
            stashFlightDiagnostic.addProperty("phase",approach?"Approach":"Cruise");stashFlightDiagnostic.addProperty("cruiseClear",clearStashCruise(body));
            stashFlightDiagnostic.addProperty("maxBuildY",mc.level.getMaxY());stashFlightDiagnostic.addProperty("gliderDurability",gliderDurability());
            if(scanFlightRecoveries<=3){
                scanFlightRoute=PrinterFlight.escapeRoute(from,new AABB(from.x-.25,from.y-.25,from.z-.25,from.x+.25,from.y+.25,from.z+.25),width,height,flightClear);
                scanFlightEscaping=scanFlightRoute.size()>1;
            }
            stashFlightDiagnostic.addProperty("escapeNodes",scanFlightRoute.size());
            MonocleClient.LOG.warn("[Workers] Stash flight made no movement for 60 ticks; recovery {}",stashFlightDiagnostic);
            if(scanFlightRecoveries>3){stashFlightFailures=2;stashFlightFailed("Chest approach stalled after three checked escape attempts");return;}
        }
        if(scanFlightEscaping){
            stashCruiseSpeed=0;
            scanFlightStep=stashFlightStep(from,scanFlightRoute,scanFlightStep,true);
            if(scanFlightStep<scanFlightRoute.size()){
                Vec3 velocity=PrinterFlight.safeVelocity(from,scanFlightRoute.get(scanFlightStep),Math.min(.28,fly.horizontalSpeed.get()),width,height,flightClear);
                if(velocity.lengthSqr()>.0001){fly.requestAutopilot(velocity);detail="Repositioning for chest approach · recovery "+scanFlightRecoveries+"/3";return;}
                brakeFlight();detail="Chest escape corridor changed; retrying checked recovery";return;
            }
            scanFlightEscaping=false;scanFlightRoute=List.of();scanFlightRetry=tick;scanFlightProgressTick=tick;
        }
        if(!approach){
            // Reuse bounded local detours instead of asking the chest approach planner for a hundreds-block route.
            scanFlightRoute=List.of();scanFlightTarget=null;
            Vec3 step=localFlight.next(from,stashCruiseGoal(from,target),mc.player.tickCount,width,height,flightClear);
            if(step==null){brakeFlight();detail="No loaded, clear stash travel corridor to "+target.toShortString()+"; retrying";return;}
            stashCruiseSpeed=stashCruiseSpeed(stashCruiseSpeed,fly.horizontalSpeed.get(),fly.acceleration.get(),fly.accelerationMin.get(),fly.accelerationStep.get());
            Vec3 velocity=PrinterFlight.safeVelocity(from,step,stashCruiseSpeed,width,height,flightClear);
            if(velocity.lengthSqr()<.0001)stashCruiseSpeed=0;
            fly.requestConfiguredAutopilot(velocity);
            detail="Flying between stash containers toward "+target.toShortString();return;
        }
        stashCruiseSpeed=0;
        boolean openingReached=coverageGoal!=null?from.distanceToSqr(coverageGoal)<=.09:mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target))<=20.25
            &&(hopper||BotStashScan.visibleFrom(mc.player.getEyePosition(),target));
        if(!scanFlightRoute.isEmpty()&&scanFlightStep>=scanFlightRoute.size()&&!openingReached){
            scanFlightRoute=List.of();scanFlightRetry=mc.player.tickCount;
        }
        if(!target.equals(scanFlightTarget)||scanFlightRoute.isEmpty()&&mc.player.tickCount>=scanFlightRetry){
            scanFlightTarget=target;scanFlightStep=1;scanFlightRetry=mc.player.tickCount+20;
            if(coverageGoal!=null){
                scanFlightRoute=PrinterFlight.route(from,coverageGoal,width,height,flightClear);
                if(scanFlightRoute.isEmpty()){stashScan.rejectCoverage();scanCoverageFlightGoal=null;coverageGoal=null;}
            }
            if(coverageGoal==null)scanFlightRoute=PrinterFlight.routeToInteraction(from,target,width,height,mc.player.getEyeHeight(),4.4,flightClear,
                p->stashFlightGoal(p,target,hopper,hopper||BotStashScan.visibleFrom(p.add(0,mc.player.getEyeHeight(),0),target)));
        }
        if(scanFlightRoute.isEmpty()){
            if(mc.player.tickCount>=stashFlightRetryAt)stashFlightFailed("No opening route to "+target.toShortString());
            else brakeFlight();
            return;
        }
        scanFlightStep=stashFlightStep(from,scanFlightRoute,scanFlightStep,openingReached);
        if(scanFlightStep>=scanFlightRoute.size()){
            brakeFlight();detail="Holding near chest "+target.toShortString();return;
        }
        Vec3 step=scanFlightRoute.get(scanFlightStep);
        Vec3 velocity=PrinterFlight.safeVelocity(from,step,stashApproachSpeed(from,step,target,fly.horizontalSpeed.get()),width,height,flightClear);
        if(velocity.lengthSqr()<.0001){scanFlightRoute=List.of();brakeFlight();detail="Flight route changed; replanning near "+target.toShortString();return;}
        fly.requestAutopilot(velocity);
        detail="Flying to chest "+target.toShortString();
    }
    private void resupplyStash(boolean workflowHandoff){
        if(!dev.monocle.client.pathing.BaritoneUtils.IS_AVAILABLE){fail("Stash resupply requires Baritone for Minecraft 26.2");return;}
        if(stashInteractionPaused())return;
        stashResupply.tick();detail=stashResupply.detail();
        if(stashResupply.done()){result=stashResupply.workflowResult();completeStash(action.has("depositMode")&&action.get("depositMode").getAsString().equals("Carry")?"Stash supplies collected into carried inventory":"Stash supplies loaded into the ender chest",workflowHandoff);return;}
        if(stashResupply.approaching()){
            BlockPos navigation=stashResupply.navigationTarget();boolean hopper=stashResupply.targetIsHopper();
            try{moveToStashContainer(stashResupply.target(),navigation,hopper?navigation.equals(stashResupply.target())?2:0:-1,hopper);}
            catch(RuntimeException error){
                String reason="Source approach failed at "+stashResupply.target().toShortString()+": "+(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());
                if(!stashResupply.recoverSourceIssue(reason))throw error;
                stashResupply.close();stopStashNavigation();detail="Delivering confirmed pickups before reporting: "+reason;
            }
        }else holdStashPosition();
    }
    private void depositStash(boolean workflowHandoff){
        if(!dev.monocle.client.pathing.BaritoneUtils.IS_AVAILABLE){fail("Stash deposit requires Baritone for Minecraft 26.2");return;}
        if(stashInteractionPaused())return;
        stashDeposit.tick();detail=stashDeposit.detail();
        if(stashDeposit.done()){result=stashDeposit.workflowResult();completeStash(stashDeposit.completionDetail(),workflowHandoff);return;}
        if(stashDeposit.approaching())moveToStashContainer(stashDeposit.target(),stashDeposit.target(),-1,false);else holdStashPosition();
    }
    private void completeStash(String message,boolean workflowHandoff){
        if(!workflowHandoff||!mc.player.isFallFlying()){if(landBeforeHandoff())complete(message);return;}
        recordStashFlight(stashFlightTrail,mc.player.position());holdStashPosition();
        state="Complete";detail=message;stashHandoff=true;
    }
    private void complete(String message) { state = "Complete"; detail = message; release(); }
    private void fail(String message) {
        if(stashFlightActive()&&!mc.player.onGround()){pendingStashFailure=message;detail="Landing after stash failure: "+message;stopStashNavigation();return;}
        if(stashResupply!=null&&stashResupply.hasWithdrawal())result=stashResupply.workflowResult();
        if(stashDeposit!=null&&stashDeposit.hasTouched())result=stashDeposit.workflowResult();
        state = "Failed"; detail = message; checkpoint = armed = false; release();
    }
    private void listen(boolean yes) { if (listening == yes) return; listening = yes; if (yes) MonocleClient.EVENT_BUS.subscribe(this); else MonocleClient.EVENT_BUS.unsubscribe(this); }
    private String type() { return action == null ? "" : action.get("type").getAsString(); }
    private static void clientThread() { if (!mc.isSameThread()) throw new IllegalStateException("Native actions must run on the Minecraft client thread"); }
    private static void setActive(Module module, boolean on) { if (module.isActive() != on) { if (on) module.enable(); else module.disable(); } }
    private int countInventory(String id) { int count = 0; for (int i = 0; i < 36; i++) if (matchesTransfer(mc.player.getInventory().getItem(i), id)) count += mc.player.getInventory().getItem(i).getCount(); return count; }
    private boolean matchesTransfer(ItemStack stack,String id){
        if(!matchesId(stack,id))return false;
        if(!action.has("kitTypeId"))return true;
        return BotStashScan.matchesKit(stack,action);
    }
    private static boolean matchesId(ItemStack stack, String id) { return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(id); }
    private static ItemStack decodeStack(JsonElement stack) { return ItemStack.CODEC.parse(mc.player.registryAccess().createSerializationContext(JsonOps.INSTANCE), stack).getOrThrow(); }
    private static HighwayPlan.Cell cell(BlockPos p) { return new HighwayPlan.Cell(p.getX(), p.getY(), p.getZ()); }
    private static BlockPos block(HighwayPlan.Cell c) { return new BlockPos(c.x(), c.y(), c.z()); }
    private static String string(JsonObject o, String key, int max) { return string(o, key, max, false); }
    private static String string(JsonObject o, String key, int max, boolean empty) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive() || !o.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("Expected " + key);
        String value = o.get(key).getAsString();
        if ((!empty && value.isBlank()) || value.length() > max || value.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("Invalid " + key);
        return value;
    }
    private static double number(JsonObject o, String key, double min, double max) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive() || !o.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException("Expected numeric " + key);
        double value = o.get(key).getAsDouble(); if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("Invalid " + key); return value;
    }
    private static int integer(JsonObject o, String key, int min, int max) { double value = number(o, key, min, max); if (value != Math.rint(value)) throw new IllegalArgumentException("Expected integer " + key); return (int) value; }
    private static void optionalInteger(JsonObject o, String key, int fallback, int min, int max) { if (!o.has(key)) o.addProperty(key, fallback); integer(o, key, min, max); }
    private static void optionalNumber(JsonObject o, String key, double fallback, double min, double max) { if (!o.has(key)) o.addProperty(key, fallback); number(o, key, min, max); }
    private static void identifier(String value) { if (Identifier.tryParse(value) == null || !value.contains(":")) throw new IllegalArgumentException("Expected a namespaced identifier"); }
    private static JsonObject validateRecovery(JsonObject policy) { JsonObject p=policy.deepCopy(); p.addProperty("type","RecoverSupplies"); return validate(p); }
}
