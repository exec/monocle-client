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
import dev.monocle.client.systems.modules.movement.elytrafly.ElytraFly;
import dev.monocle.client.systems.modules.movement.elytrafly.ElytraFlightModes;
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
    private static final Set<String> TYPES = Set.of("Travel", "StashHunt", "StashScan", "StashResupply", "DropItems", "Wait", "Modules", "Tpa", "SetProfile", "Highway", "RecoverSupplies");
    private static final Set<String> EXTERNAL = Set.of("Highway", "SetProfile");
    private static final Set<String> RESERVED_MODULES = Set.of("highway-builder", "printer-helper");
    private static final Map<String,Long> HOME_USE=new HashMap<>();
    private final Bots bots;
    private JsonObject action, pending, originals = new JsonObject(), result;
    private String state = "Complete", detail = "Idle", dimension = "";
    private int elapsed, remaining, dropWait, lastTick = -1, launchTick = -1, launchAttempts;
    private boolean suspended, suspendRequested, checkpoint, armed, acknowledged, listening, modulesApplied, modulesArmed, restoredModuleLease, tpaSent, runOnly, enabledFly;
    private long homeReadyAt;
    private ClientInput previousInput;
    private LocalPlayer inputPlayer;
    private final CustomPlayerInput input = new CustomPlayerInput();
    private BotStashHunt survey;
    private BotStashScan stashScan;
    private BotStashResupply stashResupply;
    private Vec3 scanMovement;
    public JsonObject stashTelemetry() { return stashScan == null ? null : stashScan.telemetry(); }
    public JsonObject stashPending() { return stashScan == null ? null : stashScan.pending(); }
    public int stashDelivery() { return stashScan == null ? 0 : stashScan.delivery(); }
    public void acknowledgeStash(int delivery) { if (stashScan != null) stashScan.acknowledge(delivery); }
    public JsonObject stashWithdrawal(){return stashResupply==null||result==null?null:result.deepCopy();}
    private BotSupplyRecovery recovery;
    private boolean recoveryReady;
    public boolean recoveryReady() { return recoveryReady; }

    public BotActions(Bots bots) { this.bots = Objects.requireNonNull(bots); }

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
                    optionalInteger(a, "ticks", 0, 0, 1_728_000);
                } else {
                    number(a, "x", -29_999_984, 29_999_984); number(a, "z", -29_999_984, 29_999_984); number(a, "y", -2048, 2048);
                }
                optionalNumber(a, "radius", 2, .15, 8);
                optionalNumber(a, "flyBeyond", 6, 4, 32);
            }
            case "DropItems" -> {
                identifier(string(a, "item", 128)); integer(a, "count", 1, 1_048_576);
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
                optionalInteger(a, "warmupTicks", 300, 0, 72_000);
                optionalInteger(a, "acceptDelayTicks", 10, 0, 200);
                optionalInteger(a, "timeoutTicks", 1200, 1, 1_728_000);
                optionalNumber(a, "radius", 8, 1, 16);
                if (a.get("warmupTicks").getAsInt() >= a.get("timeoutTicks").getAsInt()) throw new IllegalArgumentException("Teleport timeout must exceed warmup");
            }
            case "SetProfile" -> { String name = string(a, "name", 128); if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) throw new IllegalArgumentException("Invalid profile name"); }
            default -> {} // Highway is validated and executed by the native crew coordinator.
        }
        if (a.has("dimension")) identifier(string(a, "dimension", 128));
        return a;
    }

    public void start(JsonObject next) {
        clientThread();
        if (state.equals("Running") || pending != null) throw new IllegalStateException("Finish or reconcile the current action first");
        release();
        action = validate(next); pending = null; originals = new JsonObject(); result = null;
        survey = type().equals("StashHunt") ? new BotStashHunt(action) : null;
        stashScan = type().equals("StashScan") ? new BotStashScan(action) : null; scanMovement = null;
        stashResupply=type().equals("StashResupply")?new BotStashResupply(action):null;
        if (stashScan != null) {
            var selector=Modules.get().get(dev.monocle.client.systems.modules.world.SchematicSelector.class);
            if(selector.isActive())selector.disable();
        }
        recoveryReady = false;
        recovery = Set.of("RecoverSupplies", "Highway").contains(type()) ? new BotSupplyRecovery(bots, action.has("recovery") ? validateRecovery(action.getAsJsonObject("recovery")) : type().equals("RecoverSupplies") ? action : new JsonObject()) : null;
        elapsed = dropWait = launchAttempts = 0; launchTick = lastTick = -1;
        remaining = type().equals("DropItems") ? action.get("count").getAsInt() : action.has("ticks") ? action.get("ticks").getAsInt() : 0;
        homeReadyAt=0;
        suspended = suspendRequested = checkpoint = armed = acknowledged = modulesApplied = modulesArmed = restoredModuleLease = tpaSent = runOnly = false;
        dimension = action.has("dimension") ? action.get("dimension").getAsString() : Utils.canUpdate() ? mc.level.dimension().identifier().toString() : "";
        state = "Running"; detail = "Starting " + type();
        listen(type().equals("DropItems") || survey != null || stashScan != null || stashResupply!=null);
    }

    public JsonObject tick() {
        clientThread();
        boolean observeOnly = observesPendingDrop(state, suspended, issuedDrop());
        if (!shouldTick(state, suspended, observeOnly, suspendRequested)) return status();
        if (!Utils.canUpdate()) { disconnected(); detail = "Waiting for a world"; return status(); }
        if (lastTick == mc.player.tickCount) return status();
        lastTick = mc.player.tickCount;
        try {
            if (observeOnly) { listen(true); settleDrop(); return status(); }
            if (suspendRequested) {
                if (recovery != null && !recovery.suspend()) { detail = "Landing before suspending supply recovery"; return status(); }
                if (pending != null) settleDrop();
                if (type().equals("Tpa") && tpaSent) { teleport(); if (state.equals("Running")) return status(); }
                if (!landBeforeHandoff()) return status();
                if (!safeModuleRestore()) { landBeforeHandoff(); return status(); }
                if (pending == null) { suspended = true; release(); }
                return status();
            }
            if (Set.of("StashScan","StashResupply").contains(type())&&!prepareStashHome()) return status();
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
                case "StashScan" -> scanStash();
                case "StashResupply" -> resupplyStash();
                case "DropItems" -> drop();
                case "Modules" -> runModules();
                case "Tpa" -> teleport();
                default -> throw new IllegalStateException("Unknown action");
            }
        } catch (RuntimeException error) { fail(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()); }
        return status();
    }

    private boolean prepareStashHome(){
        String home=action.get("homeName").getAsString();if(home.isEmpty())return true;
        long now=System.currentTimeMillis(),cooldown=action.get("homeCooldownTicks").getAsLong()*50L;
        String server=(mc.getCurrentServer()==null?"local":mc.getCurrentServer().ip)+"\n"+home.toLowerCase(Locale.ROOT);
        if(homeReadyAt==0){
            long ready=HOME_USE.getOrDefault(server,0L)+cooldown;
            if(now<ready){detail="Waiting "+Math.max(1,(ready-now+999)/1000)+"s for /home "+home+" cooldown";return false;}
            mc.getConnection().sendCommand("home "+home);HOME_USE.put(server,now);homeReadyAt=now+action.get("homeWarmupTicks").getAsLong()*50L;
        }
        if(now<homeReadyAt){detail="Waiting "+Math.max(1,(homeReadyAt-now+999)/1000)+"s for /home "+home+" warmup";return false;}
        return true;
    }

    static boolean shouldRecover(String type, boolean recoveryReady) {
        return !recoveryReady && !type.equals("Highway");
    }

    /** Persist snapshot() before acknowledging; no destructive packet is emitted in the prepare tick. */
    public boolean checkpointRequired() { return checkpoint; }
    public void checkpointSaved() {
        clientThread();
        if (checkpoint) { checkpoint = false; if (pending != null) armed = true; else if (type().equals("Modules")) modulesArmed = true; }
    }

    public boolean requestSuspend() {
        clientThread(); suspendRequested = true;
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
    public void resume() { clientThread(); suspendRequested = suspended = false; lastTick = -1; listen((type().equals("DropItems") || survey != null || stashScan != null) && state.equals("Running")); }
    public void disconnected() {
        clientThread();
        stopStashNavigation();
        if (recovery != null) recovery.disconnected();
        if (stashScan != null) stashScan.close();
        if(stashResupply!=null)stashResupply.close();
        releaseMovement();
    }
    public void stop() {
        clientThread();
        if (!state.equals("Running")) { release(); return; }
        if (pending != null && pending.get("issued").getAsBoolean()) fail("Stopped with an uncertain item drop. Review the recorded transfer; it will not be sent again.");
        else { pending = null; checkpoint = armed = false; state = "Failed"; detail = "Cancelled"; release(); }
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
        s.addProperty("suspended", suspended); s.addProperty("suspendRequested", suspendRequested); s.addProperty("tpaSent", tpaSent);
        s.add("originalModules", originals.deepCopy());
        if (pending != null) s.add("pendingDrop", pending.deepCopy());
        if (survey != null) s.add("survey", survey.snapshot());
        if (stashScan != null) s.add("stashScan", stashScan.snapshot());
        if(stashResupply!=null)s.add("stashResupply",stashResupply.snapshot());
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
        suspended = snapshot.get("suspended").getAsBoolean(); suspendRequested = snapshot.get("suspendRequested").getAsBoolean(); tpaSent = snapshot.get("tpaSent").getAsBoolean();
        if (snapshot.has("originalModules")) {
            originals = snapshot.getAsJsonObject("originalModules").deepCopy();
            for (var entry : originals.entrySet()) if (!type().equals("Modules") || !action.getAsJsonObject("modules").has(entry.getKey()) || !entry.getValue().getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Invalid saved module lease");
            modulesArmed = type().equals("Modules") && originals.size() == action.getAsJsonObject("modules").size();
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
        if (snapshot.has("pendingDrop")) {
            if (!type().equals("DropItems")) throw new IllegalArgumentException("Unexpected pending drop");
            pending = snapshot.getAsJsonObject("pendingDrop").deepCopy();
            integer(pending, "slot", 0, 35);
            int amount = integer(pending, "amount", 1, 99), total = integer(pending, "beforeTotal", 1, 1_048_576), count = integer(pending, "beforeCount", 1, 99);
            if (amount > remaining || amount > count || count > total || !pending.has("stack") || !pending.get("stack").isJsonObject()) throw new IllegalArgumentException("Invalid saved drop quantities");
            // The durable prepared record may already have produced a packet before the process died.
            pending.addProperty("issued", true); armed = checkpoint = false; acknowledged = false;
        }
        listen(issuedDrop() || (type().equals("DropItems") || survey != null || stashScan != null || stashResupply!=null) && state.equals("Running") && !suspended);
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
        for (int i = 0; i < 36; i++) if (matchesId(mc.player.getInventory().getItem(i), id)) { slot = i; break; }
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
        if (!mc.isSameThread() || pending == null || !pending.get("issued").getAsBoolean() || !Utils.canUpdate()
            || event.packet.containerId() != mc.player.inventoryMenu.containerId) return;
        List<ItemStack> items = event.packet.items();
        int slot = pending.get("slot").getAsInt(), menuSlot = slot < 9 ? 36 + slot : slot;
        if (items.size() <= menuSlot) return;
        String id = action.get("item").getAsString(); int total = 0;
        for (int i = 0; i < 36; i++) { int index = i < 9 ? i + 36 : i; if (index < items.size() && matchesId(items.get(index), id)) total += items.get(index).getCount(); }
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
            if (!acquireMovement()) return;
            input.stop(); brakeFlight(); runOnly = true;
            if (action.get("ticks").getAsInt() > 0 && ++elapsed >= action.get("ticks").getAsInt()) { complete("Follow duration finished"); return; }
            UUID target = UUID.fromString(action.get("target").getAsString());
            if (target.equals(mc.player.getUUID())) { fail("A follower cannot follow itself"); return; }
            Player leader = mc.level.players().stream().filter(p -> p.getUUID().equals(target) && p.isAlive()).findFirst().orElse(null);
            if (leader == null) { if(mc.player.isFallFlying())landBeforeHandoff(); detail = "Waiting for the leader to be visible in this world; no stale position is chased"; return; }
            travel(leader.position(), action.get("radius").getAsDouble(), 32, false);
            if (leader.distanceToSqr(mc.player) <= Math.pow(action.get("radius").getAsDouble(), 2)) detail = "Following · beside " + leader.getName().getString();
            return;
        }
        travel(new Vec3(action.get("x").getAsDouble(), action.get("y").getAsDouble(), action.get("z").getAsDouble()), action.get("radius").getAsDouble(), action.get("flyBeyond").getAsDouble(), true);
    }
    private void travel(Vec3 goal, double radius, double flyBeyond, boolean finish) {
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
        boolean useFlight = !runOnly && equipped && fly.flightMode.get() == ElytraFlightModes.Vanilla && distance > Math.max(radius + 2, flyBeyond);
        if (mc.player.isFallFlying()) {
            if (!fly.isActive()) { fail("ElytraFly was disabled in flight; control returned to the player"); return; }
            if (distance <= radius + 3 || !useFlight) {
                BlockPos floor = safeLandingBelow(from, 16);
                if (floor != null) {
                    Vec3 landing = new Vec3(from.x, floor.getY() + 1, from.z);
                    if (from.y - landing.y < .2) { mc.player.stopFallFlying(); mc.player.setDeltaMovement(0, -.08, 0); runOnly = true; detail = "Landing safely"; return; }
                    fly.requestAutopilot(PrinterFlight.safeVelocity(from, landing, .3, mc.player.getBbWidth() + .12, Math.max(.7, mc.player.getBbHeight()), this::clearBody));
                    detail = "Descending to safe footing"; return;
                }
            }
            List<Vec3> route = PrinterFlight.route(from, waypoint, mc.player.getBbWidth() + .12, Math.max(.7, mc.player.getBbHeight()), this::clearBody);
            if (route.isEmpty()) { detail = "Waiting for a loaded, clear flight route"; return; }
            fly.requestAutopilot(PrinterFlight.safeVelocity(from, route.get(Math.min(1, route.size() - 1)), .65, mc.player.getBbWidth() + .12, Math.max(.7, mc.player.getBbHeight()), this::clearBody));
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

    @EventHandler private void correction(PacketEvent.Receive event) {
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
            Vec3 delta = waypoint.subtract(from);
            if (!PrinterFlight.segmentClear(from, waypoint, width, height, this::clearBody)) {
                survey.speed(1, true);
                // Retry the bounded local detour once per second, not a 4096-node search on every tick.
                if (mc.player.tickCount % 20 != 0) { detail = "Survey flight corridor blocked; retrying local detour"; return; }
                List<Vec3> route = PrinterFlight.route(from, waypoint, width, height, this::clearBody);
                if (route.isEmpty()) { detail = "Survey needs a clear flight corridor; adjust altitude or clear obstruction"; return; }
                delta = route.get(Math.min(1, route.size() - 1)).subtract(from);
            }
            double speed = survey.speed(Math.min(6, fly.horizontalSpeed.get()), false);
            Vec3 velocity = delta.length() <= speed ? delta : delta.normalize().scale(speed);
            if (PrinterFlight.segmentClear(from, from.add(velocity), width, height, this::clearBody)) fly.requestSurveyAutopilot(velocity);
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
        BlockState floor = verified(feet.below());
        return floor != null && floor.getFluidState().isEmpty() && !floor.is(Blocks.MAGMA_BLOCK) && !floor.is(Blocks.CACTUS) && !floor.is(Blocks.CAMPFIRE) && !floor.is(Blocks.SOUL_CAMPFIRE)
            && Block.isShapeFullBlock(floor.getCollisionShape(mc.level, feet.below())) && clearBody(PrinterFlight.body(Vec3.atBottomCenterOf(feet), mc.player.getBbWidth() + .12, 1.8));
    }
    private BlockPos safeLandingBelow(Vec3 from, int limit) {
        for (int n = 0; n <= limit; n++) {
            BlockPos feet = BlockPos.containing(from).below(n);
            if (standable(feet) && PrinterFlight.segmentClear(from, new Vec3(from.x, feet.getY(), from.z), mc.player.getBbWidth() + .12, 1.8, this::clearBody)) return feet.below();
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
            } else if (survey != null && PrinterFlight.segmentClear(from, from.add(0, -8, 0), mc.player.getBbWidth() + .12, 1.8, this::clearBody)) {
                Modules.get().get(ElytraFly.class).requestAutopilot(new Vec3(0, -.3, 0));
                detail = "Descending through clear air to find safe survey landing";
            } else detail = "No clear supported landing below; cannot safely change flight settings yet";
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
    private void release() {
        stopStashNavigation();
        if (stashScan != null) stashScan.close();
        if (recovery != null) recovery.close();
        releaseMovement();
        if (action != null && type().equals("Modules") && (modulesApplied || restoredModuleLease)) for (var entry : originals.entrySet()) {
            Module module = Modules.get().get(entry.getKey());
            if (entry.getKey().equals("elytra-fly") && !safeModuleRestore()) continue;
            if (module != null && module.isActive() == action.getAsJsonObject("modules").get(entry.getKey()).getAsBoolean()) setActive(module, entry.getValue().getAsBoolean());
        }
        modulesApplied = restoredModuleLease = false;
        listen(issuedDrop());
    }
    private boolean safeModuleRestore() {
        return !type().equals("Modules") || !modulesApplied && !restoredModuleLease || !originals.has("elytra-fly") || originals.get("elytra-fly").getAsBoolean()
            || !Utils.canUpdate() || mc.player.onGround() || !Modules.get().get(ElytraFly.class).isActive();
    }
    private dev.monocle.client.pathing.BaritoneUtils.StashNavigation stashNavigation;
    private void stopStashNavigation() {
        if (stashNavigation != null) { stashNavigation.close(); stashNavigation = null; }
    }
    private boolean nativeBusy() {
        return bots.crew.localAssigned() || Modules.get().get(HighwayBuilder.class).hasJob() || Modules.get().get(PrinterHelper.class).isActive()
            || !type().equals("Modules") && stashNavigation == null && dev.monocle.client.pathing.PathManagers.get().isPathing();
    }
    private void scanStash() {
        if (!dev.monocle.client.pathing.BaritoneUtils.IS_AVAILABLE) { fail("Stash scanning requires Baritone for Minecraft 26.2; install it in this profile's mods folder"); return; }
        releaseMovement(); brakeFlight();
        if (mc.player.isFallFlying()) { landBeforeHandoff(); detail = "Landing before opening stash containers"; return; }
        if (Modules.get().get(AutoEat.class).eating || mc.player.isUsingItem() || TickRate.INSTANCE.getTimeSinceLastTick() >= 1.5f) { stopStashNavigation(); detail = "Scan waiting for eating or server response"; return; }
        if (scanMovement == null || scanMovement.distanceToSqr(mc.player.position()) > .04) { scanMovement = mc.player.position(); stashScan.moved(); }
        stashScan.tick();
        detail = stashScan.detail();
        if (stashScan.done()) { result = stashScan.telemetry(); complete("Stash scan complete; inspect unscanned counts and missing chunks before trusting coverage"); return; }
        if (stashScan.approaching()) {
            if (stashNavigation == null) stashNavigation = new dev.monocle.client.pathing.BaritoneUtils.StashNavigation();
            stashNavigation.moveTo(stashScan.navigationTarget(),stashScan.hasHopperPerch()?0:stashScan.targetIsHopper()?2:-1);
            detail += " · Baritone navigating to container";
        } else stopStashNavigation();
    }
    private void resupplyStash(){
        if(!dev.monocle.client.pathing.BaritoneUtils.IS_AVAILABLE){fail("Stash resupply requires Baritone for Minecraft 26.2");return;}
        releaseMovement();brakeFlight();stashResupply.tick();detail=stashResupply.detail();
        if(stashResupply.done()){result=stashResupply.result();complete("Stash supplies loaded into the ender chest");return;}
        if(stashResupply.approaching()){if(stashNavigation==null)stashNavigation=new dev.monocle.client.pathing.BaritoneUtils.StashNavigation();stashNavigation.moveTo(stashResupply.target(),-1);}else stopStashNavigation();
    }
    private void complete(String message) { state = "Complete"; detail = message; release(); }
    private void fail(String message) { state = "Failed"; detail = message; checkpoint = armed = false; release(); }
    private void listen(boolean yes) { if (listening == yes) return; listening = yes; if (yes) MonocleClient.EVENT_BUS.subscribe(this); else MonocleClient.EVENT_BUS.unsubscribe(this); }
    private String type() { return action == null ? "" : action.get("type").getAsString(); }
    private static void clientThread() { if (!mc.isSameThread()) throw new IllegalStateException("Native actions must run on the Minecraft client thread"); }
    private static void setActive(Module module, boolean on) { if (module.isActive() != on) { if (on) module.enable(); else module.disable(); } }
    private int countInventory(String id) { int count = 0; for (int i = 0; i < 36; i++) if (matchesId(mc.player.getInventory().getItem(i), id)) count += mc.player.getInventory().getItem(i).getCount(); return count; }
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
