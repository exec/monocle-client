package dev.monocle.client.systems.modules.misc.swarm;

import com.google.gson.JsonObject;
import dev.monocle.client.systems.bots.BotActions;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;

import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.List;
import java.util.function.Predicate;

/** ./gradlew crewInventoryCheck — policy, conservation, direction and real compiled transfer boundaries. */
public final class CrewInventoryTest {
    public static void main(String[] args) throws Exception {
        boolean assertions = false; assert assertions = true;
        if (!assertions) throw new IllegalStateException("Assertions required");
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(DataComponentInitializers.PendingComponents::apply);
        var policy = CrewInventory.Policy.defaults();
        JsonObject layout = new JsonObject(); layout.add("inventory", policy.json());
        assert CrewInventory.Policy.read(layout).equals(policy);
        assert CrewInventory.Policy.read(new JsonObject()).equals(policy);
        for (int bad : new int[]{-1, 0, 63, 1537}) {
            JsonObject invalid = layout.deepCopy(); invalid.getAsJsonObject("inventory").addProperty("paving", bad);
            try { CrewInventory.Policy.read(invalid); throw new AssertionError("Invalid target accepted"); } catch (IllegalArgumentException expected) {}
        }
        JsonObject fractional = layout.deepCopy(); fractional.getAsJsonObject("inventory").addProperty("picks", 2.5);
        try { CrewInventory.Policy.read(fractional); throw new AssertionError("Fractional quantity accepted"); } catch (ArithmeticException expected) {}

        for (var item : List.of(Items.NETHERITE_PICKAXE, Items.GOLDEN_SWORD, Items.LEATHER_HELMET, Items.ELYTRA, Items.SHULKER_BOX,
            Items.ENDER_CHEST, Items.OBSIDIAN, Items.COOKED_BEEF, Items.TOTEM_OF_UNDYING, Items.FIREWORK_ROCKET, Items.ENDER_PEARL,
            Items.EXPERIENCE_BOTTLE, Items.ARROW, Items.POTION, Items.NETHERRACK, Items.COBBLESTONE, Items.END_STONE)) {
            assert CrewInventory.keep(new ItemStack(item), List.of(Blocks.OBSIDIAN), CrewInventory.FILLER, List.of()) : "Protected highway kit: " + item;
        }
        for (var item : List.of(Items.QUARTZ, Items.GOLD_NUGGET, Items.FLINT, Items.GRAVEL, Items.COAL, Items.STICK))
            assert !CrewInventory.keep(new ItemStack(item), List.of(Blocks.OBSIDIAN), CrewInventory.FILLER, List.of()) : "Default highway trash: " + item;
        ItemStack named = new ItemStack(Items.QUARTZ); named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep me"));
        assert CrewInventory.keep(named, List.of(), List.of(), List.of());
        assert CrewInventory.keep(new ItemStack(Items.QUARTZ), List.of(), List.of(), List.of(Items.QUARTZ));
        for (int yaw = -180; yaw <= 180; yaw += 45) {
            double angle = Math.toRadians(CrewInventory.trashYaw(yaw)), forward = Math.toRadians(yaw);
            assert Math.sin(angle) * Math.sin(forward) + Math.cos(angle) * Math.cos(forward) < -.999
                : "Trash travels directly opposite highway progress, including diagonal headings";
        }

        List<Predicate<ItemStack>> accepts = List.of(s -> s.is(Items.OBSIDIAN), s -> s.is(Items.DIAMOND_PICKAXE), s -> s.has(DataComponents.FOOD));
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.OBSIDIAN, 64), new ItemStack(Items.DIAMOND_PICKAXE), new ItemStack(Items.COOKED_BEEF, 32))));
        var inventory = List.of(new ItemStack(Items.OBSIDIAN, 16), box);
        assert java.util.Arrays.equals(CrewInventory.totals(inventory, accepts, false), new int[]{16, 0, 0});
        assert java.util.Arrays.equals(CrewInventory.totals(inventory, accepts, true), new int[]{80, 1, 32});
        assert CrewInventory.manifest(inventory).get("minecraft:obsidian").getAsInt() == 80;
        // Moving a box between tiers moves its contents; it does not create a second resource pool.
        int before = CrewInventory.totals(List.of(box), accepts, true)[0] + CrewInventory.totals(List.of(new ItemStack(Items.OBSIDIAN, 16)), accepts, true)[0];
        assert before == CrewInventory.totals(inventory, accepts, true)[0];
        var full = new SimpleContainer(36);
        for (int i = 0; i < 36; i++) full.setItem(i, new ItemStack(Items.NETHERRACK, 64));
        assert CrewInventory.capacity(full, new ItemStack(Items.OBSIDIAN), 1) == 0;
        full.setItem(0, new ItemStack(Items.OBSIDIAN, 32));
        assert CrewInventory.capacity(full, new ItemStack(Items.OBSIDIAN), 1) == 32;
        full.setItem(1, ItemStack.EMPTY);
        assert CrewInventory.capacity(full, new ItemStack(Items.OBSIDIAN), 1) == 32 : "Preserve the pickup slot";
        full.setItem(2, ItemStack.EMPTY);
        assert CrewInventory.capacity(full, new ItemStack(Items.OBSIDIAN), 1) == 96;

        JsonObject ledger = new JsonObject(); ledger.add("loose", CrewInventory.array(new int[]{192, 3, 64, 64, 4}));
        ledger.add("shulkers", CrewInventory.array(new int[]{64, 0, 0, 0, 0})); ledger.add("echest", CrewInventory.array(new int[]{128, 0, 0, 0, 0}));
        ledger.add("reserve", CrewInventory.array(new int[]{128, 2, 16, 16, 2}));
        ledger.add("target", CrewInventory.array(new int[]{512, 3, 64, 64, 4}));
        assert CrewInventory.available(ledger, 0) == 384 && CrewInventory.surplus(ledger, 0) == 256;
        assert CrewInventory.surplus(ledger, 1) == 1;
        assert CrewInventory.exchangeTarget(ledger, CrewInventory.MATERIALS) == 1536;
        assert CrewInventory.exchangeTarget(ledger, CrewInventory.PICKS) == 9;
        assert CrewInventory.exchangeTarget(ledger, CrewInventory.FOOD) == 256;
        assert CrewInventory.exchangeBatch(CrewInventory.MATERIALS) == 512;
        assert !CrewInventory.mayDrop(true, true, 192, 128, 64) : "Issued packets cannot be replayed";
        assert !CrewInventory.mayDrop(false, false, 192, 128, 64) : "Changed stacks invalidate prepared drops";
        assert !CrewInventory.mayDrop(false, true, 191, 128, 64) : "Do not give away a donor's reserve";
        assert CrewInventory.mayDrop(false, true, 192, 128, 64);
        assert !CrewInventory.mayDrop(false, true, 575, 512, 64) : "A donor keeps its normal working loadout";
        assert CrewInventory.mayDrop(false, true, 576, 512, 64);
        var meeting = new net.minecraft.core.BlockPos(0, 116, -81349);
        for (int[] heading : new int[][] {{0, -1}, {0, 1}, {-1, 0}, {1, 0}}) {
            int dx = heading[0], dz = heading[1];
            assert CrewInventory.pickupTarget(meeting, dx, dz, null, 10).equals(meeting);
            assert CrewInventory.pickupTarget(meeting, dx, dz, null, 80).equals(meeting.offset(dx, 0, dz));
            assert CrewInventory.pickupTarget(meeting, dx, dz, null, 120).equals(meeting);
            var drop = net.minecraft.world.phys.Vec3.atBottomCenterOf(meeting).add(dx * 2, .1, dz * 2);
            assert CrewInventory.pickupTarget(meeting, dx, dz, drop, 80).equals(meeting.offset(dx * 2, 0, dz * 2));
            assert CrewInventory.pickupTarget(meeting, dx, dz, drop.add(0, -3, 0), 10).equals(meeting) : "Never chase a stack below the highway";
            assert CrewInventory.pickupTarget(meeting, dx, dz, drop.add(20, 0, 20), 10).equals(meeting) : "Bound pickup recovery to this exchange";
        }
        exchangeRetries();
        for (String stage : List.of("issued", "uncertain", "ready", "sent", "complete", "cancelled")) {
            JsonObject receipt = new JsonObject(); receipt.addProperty("issued", true); receipt.addProperty("stage", stage);
            assert CrewInventory.unresolvedReceipt(receipt) == !List.of("complete", "cancelled").contains(stage) : "Sent alone is not proof of receipt";
        }
        assert !CrewInventory.unresolvedReceipt(null) && !CrewInventory.unresolvedReceipt(new JsonObject());
        var receiptDir = java.nio.file.Files.createTempDirectory("monocle-inspected-transfer-");
        var receiptPath = receiptDir.resolve("transfer.json");
        JsonObject stale = new JsonObject(); stale.addProperty("issued", true); stale.addProperty("stage", "uncertain");
        stale.addProperty("job", "old-execution");
        dev.monocle.coordinator.TaskFiles.write(receiptPath, stale);
        long cutoff = System.currentTimeMillis() - 1000;
        java.nio.file.Files.setLastModifiedTime(receiptPath, java.nio.file.attribute.FileTime.fromMillis(cutoff - 1));
        CrewInventory.inspectTransferReceipt(receiptPath, cutoff);
        JsonObject inspected = dev.monocle.coordinator.TaskFiles.read(receiptPath);
        assert !CrewInventory.unresolvedReceipt(inspected) && inspected.get("issued").getAsBoolean();
        assert inspected.get("previousStage").getAsString().equals("uncertain");
        var archive = receiptPath.resolveSibling("transfer.json.inspected-" + dev.monocle.coordinator.TaskFiles.hash(stale.toString()) + ".json");
        assert dev.monocle.coordinator.TaskFiles.read(archive).equals(stale) : "Original intent must remain recoverable";
        CrewInventory.inspectTransferReceipt(receiptPath, cutoff);
        assert dev.monocle.coordinator.TaskFiles.read(receiptPath).equals(inspected) : "Retry is idempotent";
        dev.monocle.coordinator.TaskFiles.write(receiptPath, stale);
        java.nio.file.Files.setLastModifiedTime(receiptPath, java.nio.file.attribute.FileTime.fromMillis(cutoff + 1));
        try { CrewInventory.inspectTransferReceipt(receiptPath, cutoff); throw new AssertionError("Replayed approval cleared a newer transfer"); }
        catch (IllegalStateException expected) { }
        assert dev.monocle.coordinator.TaskFiles.read(receiptPath).equals(stale);
        java.nio.file.Files.writeString(receiptPath, "broken json");
        try { CrewInventory.inspectTransferReceipt(receiptPath, cutoff); throw new AssertionError("Corrupt receipt cleared"); }
        catch (IllegalStateException expected) { }
        assert java.nio.file.Files.readString(receiptPath).equals("broken json");
        try (var bytes = SwarmCrew.class.getResourceAsStream("/dev/monocle/client/systems/modules/world/HighwayBuilder.class")) {
            var code = ClassFile.of().parse(bytes.readAllBytes()).methods().stream().filter(m -> m.methodName().equalsString("crewInventoryStatus")).findFirst().orElseThrow();
            assert code.code().orElseThrow().elementList().stream().anyMatch(e -> e instanceof java.lang.classfile.instruction.FieldInstruction f
                && f.opcode() == java.lang.classfile.Opcode.PUTFIELD && f.name().equalsString("waiting"))
                : "A transfer failure must replace the generic crew synchronization label";
        }
        for (String sender : List.of("ready", "issued", "sent", "failed")) for (String receiver : List.of("ready", "received", "failed"))
            assert CrewInventory.transferConfirmed(sender, receiver) == (sender.equals("sent") && receiver.equals("received"));
        for (int amount = 1; amount <= 64; amount++) {
            assert BotActions.confirmedDrop(192, amount, 192 - amount, 64, 64 - amount);
            assert !BotActions.confirmedDrop(192, amount, 192, 64, 64 - amount) : "A local slot prediction is not server confirmation";
            assert BotActions.nextDropCount(amount, 64) == (amount == 64 ? 64 : 1);
        }
        assert CrewInventory.mayDrop(false, true, 4000, 512, 1, 1728);
        assert !CrewInventory.mayDrop(false, true, 2000, 512, 1, 1728);

        try (var bytes = SwarmCrew.class.getResourceAsStream("SwarmCrew$ResourcePool.class");
             var shared = SwarmCrew.class.getResourceAsStream("/dev/monocle/coordinator/HighwayCoordinator$ResourceExchange.class")) {
            var compiled = ClassFile.of().parse(bytes.readAllBytes());
            var local = calls(compiled, "tickLocal");
            assert local.containsAll(List.of("crewRestockIdle", "crewInventorySettled", "releaseSupply", "nearPartner", "mayDrop", "saveLocal", "handleContainerInput"));
            assert local.indexOf("saveLocal") < local.indexOf("handleContainerInput") : "Durable intent precedes the drop packet";
            assert local.indexOf("requestSync") < local.indexOf("crewTravelRejoin") : "Positioning cannot starve server inventory confirmation";
            assert local.containsAll(List.of("pickupTarget", "getEntitiesOfClass", "newOwnSupplies"));
            var sharedCode=ClassFile.of().parse(shared.readAllBytes());
            assert calls(sharedCode, "coordinate").containsAll(List.of("available", "exchangeTarget", "transferConfirmed", "departSupply", "dispatch"));
            assert !calls(sharedCode, "coordinate").contains("pause") : "One transfer must not pause the whole crew";
            assert calls(compiled, "inventory").contains("confirmedDrop");
            assert calls(compiled, "close").containsAll(List.of("unresolvedReceipt", "saveLocal", "listen")) : "Ending preserves unconfirmed throws but never reopens an inspected cancellation";
            assert calls(compiled, "accept").containsAll(List.of("unresolvedReceipt", "accept")) : "Restore same-token receipts before allowing a new exchange";
            assert calls(compiled, "tickLocal").containsAll(List.of("crewMakeTransferRoom", "exchangeBatch"));
        }
        System.out.println("Crew inventory checks passed: kit protection, all-heading lane disposal, exact policy bounds, nested conservation, receiver capacity, donor reserves, two-sided receipts and durable no-replay drops.");
    }
    @SuppressWarnings("unchecked")
    private static void exchangeRetries() throws Exception {
        var commands = new java.util.ArrayList<JsonObject>();
        var crew = new dev.monocle.coordinator.HighwayCoordinator<net.minecraft.core.BlockPos>() {
            @Override protected boolean isHost() { return true; }
            @Override protected boolean worldAvailable() { return false; }
            @Override protected java.util.UUID me() { return new java.util.UUID(0, 1); }
            @Override protected java.util.UUID hostIdentity() { return me(); }
            @Override protected String scope() { return "test"; }
            @Override protected void persist() { }
            @Override protected void info(String format, Object... args) { }
            @Override protected void warning(String format, Object... args) { }
            @Override protected void apply(JsonObject command) { commands.add(command.deepCopy()); }
            @Override protected void installAssignment(JsonObject value) { throw new AssertionError("Unexpected reconfiguration"); }
            @Override protected String memberName(java.util.UUID worker) { return worker.toString(); }
            @Override protected net.minecraft.core.BlockPos point(int x, int y, int z) { return new net.minecraft.core.BlockPos(x, y, z); }
            @Override protected int x(net.minecraft.core.BlockPos p) { return p.getX(); }
            @Override protected int y(net.minecraft.core.BlockPos p) { return p.getY(); }
            @Override protected int z(net.minecraft.core.BlockPos p) { return p.getZ(); }
            @Override protected long packed(net.minecraft.core.BlockPos p) { return p.asLong(); }
            @Override protected net.minecraft.core.BlockPos unpacked(long p) { return net.minecraft.core.BlockPos.of(p); }
            @Override protected boolean hostRowResolved(net.minecraft.core.BlockPos p) { throw new AssertionError("Exchange must not gate the road"); }
            @Override protected net.minecraft.core.BlockPos rowCenter(int row) { return point(0, 116, row); }
            @Override public net.minecraft.core.BlockPos returnRendezvous() { return null; }
            @Override protected boolean nativeReady(java.util.UUID worker) { return true; }
            @Override protected boolean localReconnectReady() { return true; }
            @Override public boolean live() { return true; }
            @Override public JsonObject recoveryRecord() { return null; }
            @Override protected java.nio.file.Path journal() { throw new AssertionError("Test persistence is in-memory"); }
            @Override protected java.nio.file.Path endings() { return journal(); }
            @Override protected void checkpointJobs() { }
            @Override protected void clearLocal() { }
            @Override public void disconnected() { throw new AssertionError("Exchange timeout must not disconnect the crew"); }
        };
        Class<?> core = dev.monocle.coordinator.HighwayCoordinator.class;
        var assignment = new JsonObject(); assignment.add("layout", new JsonObject());
        field(core, "assignment").set(crew, assignment); field(core, "job").set(crew, "exchange-job");
        var reports = (java.util.Map<java.util.UUID, JsonObject>) field(core, "reports").get(crew);
        var offer = new JsonObject(); offer.addProperty("id", java.util.UUID.randomUUID().toString());
        offer.addProperty("phase", "drop"); offer.addProperty("sequence", 5); offer.addProperty("remaining", 128);
        offer.addProperty("x", 0); offer.addProperty("y", 116); offer.addProperty("z", -81349);
        var proposal = new JsonObject(); proposal.addProperty("count", 64); offer.add("proposal", proposal);
        for (String role : List.of("donor", "recipient")) {
            var id = java.util.UUID.randomUUID(); offer.addProperty(role, id.toString());
            var report = new JsonObject(); report.addProperty("job", "exchange-job"); report.addProperty("generation", 0);
            var inventory = new JsonObject(); inventory.addProperty("version", 1); inventory.addProperty("idle", true); inventory.addProperty("busy", false);
            for (String tier : List.of("loose", "shulkers", "echest", "reserve", "target")) inventory.add(tier, CrewInventory.array(new int[5]));
            report.add("inventory", inventory);
            var exchange = new JsonObject(); exchange.addProperty("need", -1); exchange.addProperty("sequence", 5);
            exchange.add("id", offer.get("id")); exchange.addProperty("stage", role.equals("donor") ? "sent" : "ready");
            report.add("exchange", exchange); reports.put(id, report);
        }
        Object exchange = field(core, "resourceExchange").get(crew);
        var hostOffer = field(exchange.getClass(), "hostOffer"); hostOffer.set(exchange, offer);
        var coordinate = exchange.getClass().getDeclaredMethod("coordinate"); coordinate.setAccessible(true);
        for (int tick : new int[] {1210, 2420, 3630}) {
            field(core, "ticks").setInt(crew, tick); coordinate.invoke(exchange);
            assert offer.get("phase").getAsString().equals("drop") && offer.get("sequence").getAsInt() == 5;
            assert offer.get("remaining").getAsInt() == 128 : "A timeout cannot count missing stock as delivered";
            assert !commands.isEmpty() && commands.getLast().getAsJsonObject("offer").equals(offer) : "Retry the same token/sequence, not another drop";
        }
        reports.get(java.util.UUID.fromString(offer.get("recipient").getAsString())).getAsJsonObject("exchange").addProperty("stage", "received");
        field(core, "ticks").setInt(crew, 3640); coordinate.invoke(exchange);
        assert offer.get("phase").getAsString().equals("gather") && offer.get("sequence").getAsInt() == 6;
        assert offer.get("remaining").getAsInt() == 64 : "Late pickup confirmation resumes the SAME exchange exactly once";
        field(core, "ticks").setInt(crew, 3650); coordinate.invoke(exchange);
        assert offer.get("remaining").getAsInt() == 64 && offer.get("phase").getAsString().equals("gather") : "Stale receipts cannot complete the next stack";
        field(core, "ticks").setInt(crew, 5000); coordinate.invoke(exchange);
        assert offer.get("phase").getAsString().equals("cancelled") : "Pre-drop timeouts can cancel and retry safely";
        for (String role : List.of("donor", "recipient")) {
            var e = reports.get(java.util.UUID.fromString(offer.get(role).getAsString())).getAsJsonObject("exchange");
            e.addProperty("sequence", 6);
            e.addProperty("stage", role.equals("donor") ? "uncertain" : "cancelled");
        }
        field(core, "ticks").setInt(crew, 5010); coordinate.invoke(exchange);
        assert offer.get("phase").getAsString().equals("uncertain") : "Cancellation must agree with an issued worker receipt instead of waiting forever for a safe ACK";
        for (var report : reports.values()) report.getAsJsonObject("exchange").addProperty("stage", "uncertain");
        field(core, "ticks").setInt(crew, 5020); coordinate.invoke(exchange);
        assert hostOffer.get(exchange) == null && !assignment.has("resourceExchange") : "Both uncertain receipts finalize terminal delivery without counting or repeating a drop";
    }
    private static java.lang.reflect.Field field(Class<?> owner, String name) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static List<String> calls(java.lang.classfile.ClassModel compiled, String name) {
        return compiled.methods().stream().filter(m -> m.methodName().equalsString(name)).findFirst().orElseThrow().code().orElseThrow().elementList().stream()
            .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).map(i -> i.name().stringValue()).toList();
    }
}
