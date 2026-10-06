package dev.monocle.client.systems.bots;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.monocle.client.MonocleClient;
import dev.monocle.client.events.packets.InventoryEvent;
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

import java.util.*;

import static dev.monocle.client.MonocleClient.mc;

/** One client-thread native action. The host owns scheduling, profiles and teleport commands. */
public final class BotActions {
    private static final Set<String> TYPES = Set.of("Travel", "StashHunt", "StashScan", "StashResupply", "StashDeposit", "DropItems", "Wait", "Modules", "Tpa", "SetProfile", "Highway", "RecoverSupplies");
    private static final Set<String> EXTERNAL = Set.of("Highway", "SetProfile");
    private static final Set<String> STASH_ACTIONS = Set.of("StashScan", "StashResupply", "StashDeposit");
    private static final Set<String> RESERVED_MODULES = Set.of("highway-builder", "printer-helper");
    private static final List<String> CRYSTAL_GUARD_MODULES = List.of("auto-totem", "auto-gap", "auto-eat", "auto-armor", "crystal-aura");
    private final Bots bots;
    private final boolean highwayOwned;
    private JsonObject action, pending, originals = new JsonObject(), result;
    private String state = "Complete", detail = "Idle", dimension = "", pendingStashFailure;
    private int elapsed, remaining, dropWait, lastTick = -1, launchTick = -1, launchAttempts, bodyguardMissingTicks, bodyguardTpaAt;
    private boolean suspended, suspendRequested, checkpoint, armed, acknowledged, listening, modulesApplied, modulesArmed, restoredModuleLease, tpaSent, runOnly, enabledFly, stashHandoff;
    private long homeReadyAt;
    private ClientInput previousInput;
    private LocalPlayer inputPlayer;
    private final CustomPlayerInput input = new CustomPlayerInput();
    private final PrinterFlight.LocalRoute localFlight = new PrinterFlight.LocalRoute();
    private BotStashHunt survey;
    private BotStashScan stashScan;
    private BotStashResupply stashResupply;
    private BotStashDeposit stashDeposit;
    private BlockPos scanFlightTarget;
    private List<Vec3> scanFlightRoute = List.of();
    private final List<Vec3> stashFlightTrail = new ArrayList<>();
    private int scanFlightStep, scanFlightRetry, landingRouteStep = -1, landingFallbackRetry;
    private Vec3 landingFallbackTarget;
    private Iterator<BlockPos.MutableBlockPos> landingCandidates;
    private Vec3 landingSearchOrigin;
    public JsonObject stashTelemetry() { return stashScan == null ? null : stashScan.telemetry(); }
    public JsonObject stashPending() { return stashScan != null ? stashScan.pending() : stashDeposit!=null?stashDeposit.pending():null; }
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

    /** Validate at the Lua/network boundary before acquiring any control or changing a module. */
    public static JsonObject validate(JsonObject source) {
        if (source == null || source.size() > 32) throw new IllegalArgumentException("Invalid native action");
        JsonObject a = source.deepCopy();
        String type = string(a, "type", 32);
        if (!TYPES.contains(type)) throw new IllegalArgumentException("Unsupported native action: " + type);
        switch (type) {
            case "StashScan" -> a = dev.monocle.coordinator.StashCatalog.plan(a);
            case "StashResupply" -> {
                a=dev.monocle.coordinator.StashCatalog.plan(a);JsonArray picks=a.getAsJsonArray("picks");if(picks==null||picks.isEmpty()||picks.size()>54)throw new IllegalArgumentException("Stash resupply needs 1–54 selected shulkers");
                for(JsonElement value:picks){JsonObject p=value.getAsJsonObject();integer(p,"x",-29_900_000,29_900_000);integer(p,"y",-2048,2048);integer(p,"z",-29_900_000,29_900_000);integer(p,"slot",0,215);identifier(string(p,"resource",128));}
                if(a.has("kitTypeId")){UUID.fromString(string(a,"kitTypeId",36));integer(a,"count",1,36);if(!a.has("kitExemplar"))throw new IllegalArgumentException("Kit withdrawal needs its learned exemplar");optionalInteger(a,"pickIndex",0,0,picks.size());}
                if(a.has("transferAll")&&a.get("transferAll").getAsBoolean()&&(!a.has("kitTypeId")||!a.has("depositMode")||!a.get("depositMode").getAsString().equals("Carry")))throw new IllegalArgumentException("Transfer all needs a carried kit batch for stash delivery");
                if(a.has("depositMode")&&!Set.of("Carry","Ender Chest").contains(string(a,"depositMode",16)))throw new IllegalArgumentException("Invalid stash deposit mode");
            }
            case "StashDeposit" -> {a=dev.monocle.coordinator.StashCatalog.plan(a);UUID.fromString(string(a,"kitTypeId",36));integer(a,"count",1,36);}
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
                if(a.has("external")&&a.get("external").getAsBoolean()&&!string(a,"targetName",16).matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("External TPA needs the target's exact username");
                optionalInteger(a, "warmupTicks", 300, 0, 72_000);
                optionalInteger(a, "acceptDelayTicks", 10, 0, 200);
                optionalInteger(a, "timeoutTicks", 1200, 1, 1_728_000);
                optionalNumber(a, "radius", 8, 1, 16);
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
        if (a.has("dimension")) identifier(string(a, "dimension", 128));
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
        stashHandoff=false;
        action = validated; pending = null; pendingStashFailure = null; originals = new JsonObject(); result = null;
        survey = type().equals("StashHunt") ? new BotStashHunt(action) : null;
        stashScan = type().equals("StashScan") ? new BotStashScan(action) : null;
        stashResupply=type().equals("StashResupply")?new BotStashResupply(action):null;
        stashDeposit=type().equals("StashDeposit")?new BotStashDeposit(action):null;
        if (stashScan != null) {
            var selector=Modules.get().get(dev.monocle.client.systems.modules.world.SchematicSelector.class);
            if(selector.isActive())selector.disable();
        }
        recoveryReady = false;
        recovery = Set.of("RecoverSupplies", "Highway").contains(type()) ? new BotSupplyRecovery(bots, action.has("recovery") ? validateRecovery(action.getAsJsonObject("recovery")) : type().equals("RecoverSupplies") ? action : new JsonObject()) : null;
        elapsed = dropWait = launchAttempts = bodyguardMissingTicks = bodyguardTpaAt = 0; launchTick = lastTick = -1;
        remaining = type().equals("DropItems") ? action.get("count").getAsInt() : action.has("ticks") ? action.get("ticks").getAsInt() : 0;
        homeReadyAt=0;
        suspended = suspendRequested = checkpoint = armed = acknowledged = modulesApplied = modulesArmed = restoredModuleLease = tpaSent = runOnly = false;
        dimension = action.has("dimension") ? action.get("dimension").getAsString() : Utils.canUpdate() ? mc.level.dimension().identifier().toString() : "";
        state = "Running"; detail = "Starting " + type();
        listen(type().equals("DropItems") || survey != null || stashScan != null || stashResupply!=null || stashDeposit!=null);
    }

    public JsonObject tick() {return tick(false);}
    JsonObject tick(boolean workflowHandoff) {
        clientThread();
        if(stashHandoff()&&Utils.canUpdate()){holdStashPosition();return status();}
        boolean observeOnly = observesPendingDrop(state, suspended, issuedDrop());
        if (!shouldTick(state, suspended, observeOnly, suspendRequested)) return status();
        if (!Utils.canUpdate()) { disconnected(); detail = "Waiting for a world"; return status(); }
        if (lastTick == mc.player.tickCount) return status();
        lastTick = mc.player.tickCount;
        try {
            if (observeOnly) { listen(true); settleDrop(); return status(); }
            if (pendingStashFailure != null) {
                if (!landBeforeHandoff()) { detail = "Landing after stash failure: " + pendingStashFailure; return status(); }
                String failure=pendingStashFailure;pendingStashFailure=null;fail(failure);return status();
            }
            if (Set.of("StashScan","StashResupply","StashDeposit").contains(type()) && mc.player.isFallFlying() && gliderDurability() <= 120) {
                if (!landBeforeHandoff()) { detail = "Elytra reserve low; landing before any more stash work"; return status(); }
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
            if (Set.of("StashScan","StashResupply","StashDeposit").contains(type())&&!prepareStashHome()) return status();
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
        if(homeReadyAt==0){
            if(dimension.equals(mc.level.dimension().identifier().toString())&&localStashTrip(action,new Position(mc.player.getX(),mc.player.getY(),mc.player.getZ()))){
                homeReadyAt=now;return true;
            }
            long ready=bots.stashHomeReadyAt(server,cooldown);
            if(now<ready){detail="Waiting "+Math.max(1,(ready-now+999)/1000)+"s for /home "+home+" cooldown";return false;}
            mc.getConnection().sendCommand("home "+home);bots.recordStashHomeUse(server,now);homeReadyAt=now+action.get("homeWarmupTicks").getAsLong()*50L;
        }
        if(now<homeReadyAt){detail="Waiting "+Math.max(1,(homeReadyAt-now+999)/1000)+"s for /home "+home+" warmup";return false;}
        return true;
    }
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
            detail = "Landing before handing off movement and profile settings"; return false;
        }
        if (!safeModuleRestore()) { detail = "Land before restoring the temporary flight module"; return false; }
        if (survey != null) Modules.get().get(dev.monocle.client.systems.modules.world.StashFinder.class).surveyFlush();
        suspended = true; release(); return true;
    }
    public void resume() { clientThread(); suspendRequested = suspended = false; lastTick = -1; listen((type().equals("DropItems") || survey != null || stashScan != null || stashResupply != null || stashDeposit != null) && state.equals("Running")); }
    public void disconnected() {
        clientThread();
        resetFlightRoutes();
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
    public void externalResult(boolean success, String message, JsonObject value) {
        clientThread();
        if (!state.equals("Running")) throw new IllegalStateException("Not an active external action");
        if (type().equals("Tpa") && !success && tpaSent) { detail = message + "; waiting for the outstanding teleport to settle"; return; }
        if (!EXTERNAL.contains(type()) && !(type().equals("Tpa") && !success)) throw new IllegalStateException("Not an active external action");
        result = value == null ? null : value.deepCopy(); if (success) complete(message); else fail(message);
    }

    public JsonObject snapshot() {
        JsonObject s = status(); s.addProperty("version", 1);
        if (action != null) s.add("action", action.deepCopy());
        s.addProperty("elapsed", elapsed); s.addProperty("remaining", remaining); s.addProperty("dimension", dimension);
        if(homeReadyAt>0)s.addProperty("homeReadyAt",homeReadyAt);
        if(pendingStashFailure!=null)s.addProperty("pendingStashFailure",pendingStashFailure);
        s.addProperty("suspended", suspended); s.addProperty("suspendRequested", suspendRequested); s.addProperty("tpaSent", tpaSent);
        s.addProperty("stashHandoff",stashHandoff);
        s.add("originalModules", originals.deepCopy());
        if (pending != null) s.add("pendingDrop", pending.deepCopy());
        if (survey != null) s.add("survey", survey.snapshot());
        if (stashScan != null) s.add("stashScan", stashScan.snapshot());
        if(stashResupply!=null)s.add("stashResupply",stashResupply.snapshot());
        if(stashDeposit!=null)s.add("stashDeposit",stashDeposit.snapshot());
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
        if(snapshot.has("homeReadyAt")){homeReadyAt=snapshot.get("homeReadyAt").getAsLong();if(homeReadyAt<0||homeReadyAt>System.currentTimeMillis()+3_600_000)throw new IllegalArgumentException("Invalid saved home warmup");}
        if(snapshot.has("pendingStashFailure"))pendingStashFailure=string(snapshot,"pendingStashFailure",900);
        suspended = snapshot.get("suspended").getAsBoolean(); suspendRequested = snapshot.get("suspendRequested").getAsBoolean(); tpaSent = snapshot.get("tpaSent").getAsBoolean();
        stashHandoff=snapshot.has("stashHandoff")&&snapshot.get("stashHandoff").getAsBoolean()&&state.equals("Complete")&&STASH_ACTIONS.contains(type());
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
            int amount = integer(pending, "amount", 1, 99), total = integer(pending, "beforeTotal", 1, 1_048_576), count = integer(pending, "beforeCount", 1, 99);
            if (amount > remaining || amount > count || count > total || !pending.has("stack") || !pending.get("stack").isJsonObject()) throw new IllegalArgumentException("Invalid saved drop quantities");
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
        releaseMovement();
        if (pending != null) {
            if (armed) {
                if (!inventoryReady() || !aimRecipient()) return;
                ItemStack expected = decodeStack(pending.get("stack"));
                int slot = pending.get("slot").getAsInt(), amount = pending.get("amount").getAsInt();
                if (!ItemStack.matches(expected, mc.player.getInventory().getItem(slot))) { fail("Inventory changed before the prepared drop; no items sent"); return; }
                pending.addProperty("issued", true); armed = false; dropWait = 0; acknowledged = false;
                mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, SlotUtils.indexToId(slot), amount == expected.getCount() ? 1 : 0, ContainerInput.THROW, mc.player);
                refreshInventory();
            } else if (pending.get("issued").getAsBoolean()) settleDrop();
            return;
        }
        if (remaining == 0) { result = new JsonObject(); result.addProperty("dropped", action.get("count").getAsInt()); complete("Items dropped and confirmed by the server"); return; }
        if (!inventoryReady() || !aimRecipient()) return;
        String id = action.get("item").getAsString();
        int total = countInventory(id), slot = -1;
        if (total < remaining) { fail("Not enough carried " + id + " for the remaining transfer"); return; }
        for (int i = 0; i < 36; i++) if (matchesTransfer(mc.player.getInventory().getItem(i), id)) { slot = i; break; }
        ItemStack stack = mc.player.getInventory().getItem(slot);
        int amount = nextDropCount(remaining, stack.getCount());
        pending = new JsonObject(); pending.addProperty("slot", slot); pending.addProperty("amount", amount);
        pending.addProperty("beforeCount", stack.getCount()); pending.addProperty("beforeTotal", total); pending.addProperty("issued", false);
        pending.add("stack", ItemStack.CODEC.encodeStart(mc.player.registryAccess().createSerializationContext(JsonOps.INSTANCE), stack).getOrThrow());
        checkpoint = true; detail = "Checkpoint required before dropping " + amount + " item(s)";
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
            remaining -= pending.get("amount").getAsInt(); pending = null; acknowledged = checkpoint = armed = false; dropWait = 0;
            detail = "Drop confirmed"; if (!state.equals("Running") || suspended) listen(false); return;
        }
        dropWait = Math.min(121, dropWait + 1);
        if (dropWait > 120 && state.equals("Running")) { fail("Item drop remains uncertain after server refresh. Inspect inventory and the ground; this drop will not be repeated."); return; }
        if (Utils.canUpdate() && inventoryReady() && (dropWait == 1 || lastTick % 20 == 0)) refreshInventory();
        detail = state.equals("Running") ? "Waiting for the server to confirm the item drop" : "Drop remains uncertain; observing inventory only, never repeating the drop";
    }

    @EventHandler private void inventory(InventoryEvent event) {
        if (!mc.isSameThread() || !Utils.canUpdate()) return;
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
        ItemStack after = items.get(menuSlot), before = decodeStack(pending.get("stack"));
        if (!after.isEmpty() && !ItemStack.isSameItemSameComponents(before, after)) return;
        acknowledged = confirmedDrop(pending.get("beforeTotal").getAsInt(), pending.get("amount").getAsInt(), total, pending.get("beforeCount").getAsInt(), after.getCount());
    }
    @EventHandler private void screen(OpenScreenEvent event) {
        if(stashScan!=null&&state.equals("Running")&&!suspended&&!suspendRequested&&stashScan.suppressScreen()&&event.screen instanceof AbstractContainerScreen<?>)event.cancel();
    }

    private void refreshInventory() {
        mc.getConnection().send(HighwayBuilder.cursorSyncRequest(mc.player.inventoryMenu.containerId,
            HashedStack.create(mc.player.inventoryMenu.getCarried(), mc.getConnection().decoratedHashOpsGenenerator())));
    }
    private boolean inventoryReady() {
        if (mc.player.containerMenu != mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty() || mc.gui.screen() instanceof AbstractContainerScreen) {
            detail = "Close containers and put the inventory cursor away before transferring items"; return false;
        }
        if (bots.crew.localAssigned()) { detail = "Waiting for the native crew to finish reserved-container recovery"; return false; }
        return true;
    }
    private boolean aimRecipient() {
        if (!action.has("recipient")) return true;
        Player recipient = mc.level.getPlayerByUUID(UUID.fromString(action.get("recipient").getAsString()));
        if (recipient == null || !recipient.isAlive() || recipient.distanceToSqr(mc.player) > 16 || !mc.player.hasLineOfSight(recipient)) { detail = "Waiting for the transfer recipient within four blocks and in sight"; return false; }
        mc.player.setYRot((float) Rotations.getYaw(recipient.position())); mc.player.setXRot(-10); return true;
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
        if (!tpaSent) { detail = "Waiting for host teleport handshake"; return; }
        if (++elapsed >= action.get("timeoutTicks").getAsInt()) { fail("Teleport did not reach the observed target before timeout"); return; }
        if (elapsed < action.get("warmupTicks").getAsInt()) { detail = "Waiting for teleport warmup"; return; }
        if (dimension.isEmpty() || !dimension.equals(mc.level.dimension().identifier().toString())) { detail = "Waiting for the expected target dimension"; return; }
        String target = action.get("target").getAsString();
        Player player = mc.level.players().stream().filter(p -> p != mc.player && (p.getUUID().toString().equalsIgnoreCase(target) || p.getName().getString().equalsIgnoreCase(target))).findFirst().orElse(null);
        if (player != null && player.isAlive() && player.distanceToSqr(mc.player) <= Math.pow(action.get("radius").getAsDouble(), 2)) complete("Teleport physically confirmed beside the target");
        else detail = "Waiting to observe the teleport target nearby";
    }

    private void travel() {
        if (action.has("follow") && action.get("follow").getAsBoolean()) {
            if (crystalGuard() && !armCrystalGuard()) return;
            if (!acquireMovement()) return;
            boolean bodyguard=action.has("bodyguard")&&action.get("bodyguard").getAsBoolean();
            input.stop(); brakeFlight(); runOnly = !bodyguard;
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
            travel(goal,bodyguard ? .75 : action.get("radius").getAsDouble(),32,false,bodyguard ? leader : null);
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
            if ((match==null||!match.isFallFlying())&&(distance <= radius + 3 || !useFlight)) {
                BlockPos floor = safeLandingBelow(from, 16);
                if (floor != null) {
                    Vec3 landing = new Vec3(from.x, floor.getY() + 1, from.z);
                    if (from.y - landing.y < .2) { mc.player.stopFallFlying(); mc.player.setDeltaMovement(0, -.08, 0); runOnly = true; detail = "Landing safely"; return; }
                    fly.requestAutopilot(PrinterFlight.safeVelocity(from, landing, .3, mc.player.getBbWidth() + .12, Math.max(.7, mc.player.getBbHeight()), this::clearBody));
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
        List<BlockPos> candidates = new ArrayList<>();
        for (int y = -3; y <= 3; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            BlockPos p = new BlockPos(local.getX() + x, start.getY() + y, local.getZ() + z);
            if (standable(p)) candidates.add(p);
        }
        candidates.sort(Comparator.comparingDouble(p -> Vec3.atBottomCenterOf(p).distanceToSqr(goal)));
        List<HighwayPlan.Cell> route = List.of();
        for (BlockPos candidate : candidates) {
            route = HighwayPlan.route(cell(start), cell(candidate), c -> standable(block(c)),
                (a, b) -> a.y() == b.y() || clearBody(PrinterFlight.body(Vec3.atBottomCenterOf(block(a.y() > b.y() ? b : a).above()), mc.player.getBbWidth() + .12, 1.8)));
            if (!route.isEmpty()) break;
        }
        if (route.isEmpty()) { detail = "Waiting for a supported walking route; no terrain will be altered"; return; }
        BlockPos step = block(route.get(Math.min(1, route.size() - 1)));
        Vec3 point = step.equals(BlockPos.containing(goal)) ? goal : Vec3.atBottomCenterOf(step);
        double lift = Math.max(0, point.y - from.y);
        if (!PrinterFlight.segmentClear(from.add(0, lift, 0), point.add(0, Math.max(0, from.y - point.y), 0), mc.player.getBbWidth() + .12, 1.8, this::clearBody)) { detail = "Waiting for the walking corridor to clear"; return; }
        mc.player.setYRot((float) Rotations.getYaw(point)); input.jump(lift > .1); input.forward(true);
        mc.player.setSprinting(distance > radius + 2 && mc.player.getFoodData().getFoodLevel() > 6);
        detail = "Walking toward destination";
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
        if (event.packet instanceof ClientboundPlayerPositionPacket) resetFlightRoutes();
        if (survey != null && event.packet instanceof ClientboundPlayerPositionPacket) survey.correction();
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
        if (box.minY < mc.level.getMinY() || box.maxY > mc.level.getMaxY() + 1 || !PrinterFlight.loaded(box, mc.level.getChunkSource()::hasChunk)
            || !mc.level.noCollision(mc.player, box) || !mc.level.getEntities(mc.player, box, e -> e instanceof LivingEntity && !(e instanceof Player)).isEmpty()) return false;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(Math.nextDown(box.maxX), Math.nextDown(box.maxY), Math.nextDown(box.maxZ)))) {
            BlockState block = verified(pos);
            if (block == null || !mc.level.getWorldBorder().isWithinBounds(pos) || !block.getFluidState().isEmpty() || block.is(Blocks.FIRE) || block.is(Blocks.SOUL_FIRE) || block.is(Blocks.POWDER_SNOW)) return false;
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
        return floor != null && floor.getFluidState().isEmpty() && !floor.is(Blocks.MAGMA_BLOCK) && !floor.is(Blocks.CACTUS) && !floor.is(Blocks.CAMPFIRE) && !floor.is(Blocks.SOUL_CAMPFIRE)
            && Block.isShapeFullBlock(floor.getCollisionShape(mc.level, position));
    }
    private boolean standable(Vec3 feet) {
        double width = mc.player.getBbWidth() + .12;
        return PrinterFlight.supported(feet, width, 1.8, this::safeFloor) && clearBody(PrinterFlight.body(feet, width, 1.8));
    }
    private BlockPos safeLandingBelow(Vec3 from, int limit) {
        for (int n = 0; n <= limit; n++) {
            BlockPos feet = BlockPos.containing(from).below(n);
            Vec3 landing = new Vec3(from.x, feet.getY(), from.z);
            if (standable(landing) && PrinterFlight.segmentClear(from, landing, mc.player.getBbWidth() + .12, 1.8, this::clearBody)) return feet.below();
        }
        return null;
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
    private boolean landBeforeHandoff() {
        if (mc.player.onGround()) return true;
        if (inputPlayer == null && !acquireMovement()) return false;
        input.stop(); brakeFlight();
        if (mc.player.isFallFlying()) {
            Vec3 from = mc.player.position(); BlockPos floor = safeLandingBelow(from, 32);
            if (floor != null) {
                Vec3 landing = new Vec3(from.x, floor.getY() + 1, from.z);
                if (from.y - landing.y < .2) { mc.player.stopFallFlying(); mc.player.setDeltaMovement(0, -.08, 0); }
                else Modules.get().get(ElytraFly.class).requestAutopilot(PrinterFlight.safeVelocity(from, landing, .3, mc.player.getBbWidth() + .12, Math.max(.7, mc.player.getBbHeight()), this::clearBody));
                detail = "Landing before yielding the task";
            } else if ((stashFlightTrail.size()>1||scanFlightRoute.size()>1) && landingRouteStep!=-2) {
                List<Vec3> trail=stashFlightTrail.size()>1?stashFlightTrail:scanFlightRoute;
                if(landingRouteStep<0)landingRouteStep=trail.size()-1;
                landingRouteStep=retreatStep(from,trail,landingRouteStep);
                if(landingRouteStep>=0){
                    Vec3 velocity=PrinterFlight.safeVelocity(from,trail.get(landingRouteStep),.3,mc.player.getBbWidth()+.12,Math.max(.7,mc.player.getBbHeight()),this::clearBody);
                    if(velocity.lengthSqr()>.0001){Modules.get().get(ElytraFly.class).requestAutopilot(velocity);detail="Returning along the confirmed flight route to land";return false;}
                }
                landingRouteStep=-2;
                detail="Seeking a nearby safe landing";
            } else if (survey != null && PrinterFlight.segmentClear(from, from.add(0, -8, 0), mc.player.getBbWidth() + .12, 1.8, this::clearBody)) {
                Modules.get().get(ElytraFly.class).requestAutopilot(new Vec3(0, -.3, 0));
                detail = "Descending through clear air to find safe survey landing";
            } else {
                double width=mc.player.getBbWidth()+.12,height=Math.max(.7,mc.player.getBbHeight());
                if(landingFallbackTarget==null&&mc.player.tickCount>=landingFallbackRetry){
                    if(landingCandidates==null||from.distanceToSqr(landingSearchOrigin)>4){
                        landingSearchOrigin=from;landingCandidates=nearbyLandingCells(from,16);
                    }
                    landingFallbackTarget=nearbyLanding(landingSearchOrigin,landingCandidates,16,
                        p->PrinterFlight.segmentClear(from,p,width,height,this::clearBody)&&safeLandingBelow(p,32)!=null);
                    boolean exhausted=!landingCandidates.hasNext();
                    landingFallbackRetry=mc.player.tickCount+(exhausted?20:1);
                    if(exhausted||landingFallbackTarget!=null)landingCandidates=null;
                }
                if(landingFallbackTarget!=null){
                    Vec3 velocity=PrinterFlight.safeVelocity(from,landingFallbackTarget,.3,width,height,this::clearBody);
                    if(velocity.lengthSqr()>.0001){Modules.get().get(ElytraFly.class).requestAutopilot(velocity);detail="Flying to nearby supported landing";return false;}
                    landingFallbackTarget=null;
                }
                detail=landingCandidates==null?"No clear supported landing nearby; retrying":"Searching for nearby supported landing";
            }
        } else detail = "Waiting to reach the ground before yielding the task";
        return false;
    }
    private boolean acquireMovement() {
        if (inputPlayer == null) { inputPlayer = mc.player; previousInput = mc.player.input; mc.player.input = input; }
        if (inputPlayer != mc.player || mc.player.input != input) { fail("Another feature took movement control"); return false; }
        return true;
    }
    private void brakeFlight() { ElytraFly fly = Modules.get().get(ElytraFly.class); if (fly.isActive() && fly.flightMode.get() == ElytraFlightModes.Vanilla) fly.requestAutopilot(Vec3.ZERO); }
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
    private void stopStashNavigation() {
        if (stashNavigation != null) { stashNavigation.close(); stashNavigation = null; }
        stashNavTarget = null;
    }
    private boolean nativeBusy() {
        return bots.crew.localAssigned() || !highwayOwned&&Modules.get().get(HighwayBuilder.class).hasJob() || Modules.get().get(PrinterHelper.class).isActive()
            || !type().equals("Modules") && stashNavigation == null && dev.monocle.client.pathing.PathManagers.get().isPathing();
    }
    private void scanStash(boolean workflowHandoff) {
        if (!dev.monocle.client.pathing.BaritoneUtils.IS_AVAILABLE) { fail("Stash scanning requires Baritone for Minecraft 26.2; install it in this profile's mods folder"); return; }
        if(stashInteractionPaused())return;
        stashScan.tick();
        detail = stashScan.detail();
        if (stashScan.done()) {result = stashScan.telemetry();completeStash("Stash scan complete; inspect unscanned counts and missing chunks before trusting coverage",workflowHandoff);return;}
        if (stashScan.approaching()) {
            stashScan.approachProgress(mc.player.getEyePosition());
            moveToStashContainer(stashScan.target(),stashScan.navigationTarget(),stashScan.hasHopperPerch()?0:stashScan.targetIsHopper()?2:-1,stashScan.targetIsHopper());
        } else holdStashPosition();
    }
    private boolean stashInteractionPaused(){
        if(!Modules.get().get(AutoEat.class).eating&&!mc.player.isUsingItem()&&TickRate.INSTANCE.getTimeSinceLastTick()<1.5f)return false;
        stopStashNavigation();brakeFlight();detail="Stash work waiting for eating or server response";return true;
    }
    static boolean stashFlightNeeded(double targetY,double playerY,boolean gliding){return gliding||targetY-playerY>2.5;}
    static boolean stashTakeoffClear(AABB body,java.util.function.Predicate<AABB> clear){
        // A chest-side standing position is legal even when an artificially widened body clips the chest.
        return clear.test(body.expandTowards(0,1.15,0).deflate(1e-7));
    }
    private void moveToStashContainer(BlockPos target,BlockPos walkingTarget,int mode,boolean hopper){
        double horizontal=Math.hypot(target.getX()+.5-mc.player.getX(),target.getZ()+.5-mc.player.getZ());
        if(stashFlightNeeded(target.getY(),mc.player.getY(),mc.player.isFallFlying())&&horizontal<=24){
            stopStashNavigation();flyToStashChest(target,hopper);return;
        }
        if(mc.player.isFallFlying()){landBeforeHandoff();return;}
        releaseMovement();brakeFlight();
        BlockPos destination=horizontal>24&&stashFlightNeeded(target.getY(),mc.player.getY(),false)
            ?new BlockPos(target.getX(),mc.player.blockPosition().getY(),target.getZ()):walkingTarget;
        int destinationMode=destination.equals(walkingTarget)?mode:3;
        if(stashNavigation!=null&&(!destination.equals(stashNavTarget)||destinationMode!=stashNavMode))stopStashNavigation();
        if(stashNavigation==null){stashNavigation=new dev.monocle.client.pathing.BaritoneUtils.StashNavigation();stashNavTarget=destination;stashNavMode=destinationMode;}
        stashNavigation.moveTo(destination,destinationMode);
        detail+=" · Baritone "+stashNavigation.status();
    }
    private void holdStashPosition(){stopStashNavigation();if(mc.player.isFallFlying())brakeFlight();else releaseMovement();}
    static boolean scanHover(Vec3 feet,BlockPos target){
        Vec3 center=Vec3.atCenterOf(target);
        return feet.y>=target.getY()+1&&feet.y<=target.getY()+2
            &&Math.hypot(feet.x-center.x,feet.z-center.z)<=1.8;
    }
    static boolean stashFlightGoal(Vec3 feet,BlockPos target,boolean hopper,boolean visible){
        return hopper?scanHover(feet,target):visible;
    }
    private int gliderDurability(){
        ItemStack glider=mc.player.getItemBySlot(EquipmentSlot.CHEST);
        return !glider.has(DataComponents.GLIDER)?0:glider.isDamageableItem()?glider.getMaxDamage()-glider.getDamageValue():Integer.MAX_VALUE;
    }
    private void resetFlightRoutes() {resetFlightRoutes(false);}
    private void resetFlightRoutes(boolean keepTrail) {
        localFlight.reset();
        scanFlightTarget = null; scanFlightRoute = List.of(); scanFlightStep = scanFlightRetry = 0;
        landingRouteStep = -1; landingFallbackRetry = 0; landingFallbackTarget = null;
        landingCandidates = null; landingSearchOrigin = null;
        if(!keepTrail)stashFlightTrail.clear();
    }
    static boolean recordStashFlight(List<Vec3> trail,Vec3 position){
        if(!trail.isEmpty()&&trail.getLast().distanceToSqr(position)<.25)return true;
        if(trail.size()>=PrinterFlight.MAX_NODES)return false;
        trail.add(new Vec3(position.x,position.y,position.z));return true;
    }
    private void flyToStashChest(BlockPos target,boolean hopper){
        if(!acquireMovement())return;
        input.stop();
        if(mc.player.onGround())stashFlightTrail.clear();
        // ponytail: cap the return trail at the existing route-node budget; land before renewing it.
        if(!recordStashFlight(stashFlightTrail,mc.player.position())){landBeforeHandoff();detail="Landing to renew the bounded stash flight trail";return;}
        ElytraFly fly=Modules.get().get(ElytraFly.class);
        if(gliderDurability()<=160){
            if(!mc.player.onGround()){landBeforeHandoff();detail="Elytra reserve low; returning to a safe landing";return;}
            fail("Equip an elytra with at least 161 durability remaining for elevated stash work");return;
        }
        if(fly.flightMode.get()!=ElytraFlightModes.Vanilla||fly.horizontalSpeed.get()<=0){fail("Elevated stash work requires Vanilla ElytraFly with positive speed");return;}
        if(!fly.isActive()){fly.enable();enabledFly=true;}
        Vec3 from=mc.player.position();
        if(!mc.player.isFallFlying()){
            brakeFlight();
            if(launchTick<0&&mc.player.onGround()){
                if(!stashTakeoffClear(mc.player.getBoundingBox(),this::clearBody)){detail="Stash takeoff obstructed at "+mc.player.blockPosition().toShortString()+" toward chest "+target.toShortString();return;}
                mc.player.jumpFromGround();launchTick=mc.player.tickCount;
            }
            if(launchTick>=0){int age=mc.player.tickCount-launchTick;
                if(!mc.player.onGround()&&age>=3&&age%4==3)mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player,ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                if(age>30)launchTick=-1;
            }
            detail="Taking off to scan chest "+target.toShortString();return;
        }
        launchTick=-1;
        double width=mc.player.getBbWidth(),height=Math.max(.7,mc.player.getBbHeight());
        if(!scanFlightRoute.isEmpty()&&scanFlightStep>=scanFlightRoute.size()&&from.distanceToSqr(scanFlightRoute.getLast())>.25){
            scanFlightRoute=List.of();scanFlightRetry=mc.player.tickCount;
        }
        if(!target.equals(scanFlightTarget)||scanFlightRoute.isEmpty()&&mc.player.tickCount>=scanFlightRetry){
            scanFlightTarget=target;scanFlightStep=1;scanFlightRetry=mc.player.tickCount+20;
            scanFlightRoute=PrinterFlight.routeToPlacement(from,target,width,height,mc.player.getEyeHeight(),4.4,this::clearBody,
                p->stashFlightGoal(p,target,hopper,hopper||BotStashScan.visibleFrom(p.add(0,mc.player.getEyeHeight(),0),target)));
        }
        if(scanFlightRoute.isEmpty()){
            brakeFlight();detail="No loaded, clear flight route to chest "+target.toShortString()+"; retrying";return;
        }
        while(scanFlightStep<scanFlightRoute.size()&&from.distanceToSqr(scanFlightRoute.get(scanFlightStep))<.04)scanFlightStep++;
        if(scanFlightStep>=scanFlightRoute.size()){
            brakeFlight();detail="Holding near chest "+target.toShortString();return;
        }
        Vec3 step=scanFlightRoute.get(scanFlightStep);
        Vec3 velocity=PrinterFlight.safeVelocity(from,step,.28,width,height,this::clearBody);
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
        if(stashDeposit.done()){result=stashDeposit.result();completeStash("Kit deposit verified after reopening the destination chest",workflowHandoff);return;}
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
        if(stashDeposit!=null&&stashDeposit.hasTouched())result=stashDeposit.result();
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
