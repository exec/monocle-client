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
        for (int yaw : new int[]{0, 90, 180, -90}) for (int size = 1; size <= 5; size++) for (int i = 0; i < size; i++) {
            float actual = CrewInventory.trashYaw(yaw, i, size);
            assert actual == yaw + (size == 1 || i > 0 && i < size - 1 ? 180 : i == 0 ? -90 : 90);
            if (i == 0 && size > 1) {
                double angle = Math.toRadians(actual), forward = Math.toRadians(yaw);
                // Minecraft yaw zero is south; left is east, not west.
                assert -Math.sin(angle) * Math.cos(forward) + Math.cos(angle) * Math.sin(forward) > .99;
            }
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
        assert CrewInventory.available(ledger, 0) == 384 && CrewInventory.surplus(ledger, 0) == 256;
        assert CrewInventory.surplus(ledger, 1) == 1;
        assert !CrewInventory.mayDrop(true, true, 192, 128, 64) : "Issued packets cannot be replayed";
        assert !CrewInventory.mayDrop(false, false, 192, 128, 64) : "Changed stacks invalidate prepared drops";
        assert !CrewInventory.mayDrop(false, true, 191, 128, 64) : "Do not give away a donor's reserve";
        assert CrewInventory.mayDrop(false, true, 192, 128, 64);
        for (String stage : List.of("issued", "uncertain", "ready", "sent", "complete", "cancelled")) {
            JsonObject receipt = new JsonObject(); receipt.addProperty("issued", true); receipt.addProperty("stage", stage);
            assert CrewInventory.unresolvedReceipt(receipt) == !List.of("sent", "complete", "cancelled").contains(stage);
        }
        assert !CrewInventory.unresolvedReceipt(null) && !CrewInventory.unresolvedReceipt(new JsonObject());
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

        try (var bytes = SwarmCrew.class.getResourceAsStream("SwarmCrew$ResourcePool.class");
             var shared = SwarmCrew.class.getResourceAsStream("/dev/monocle/coordinator/HighwayCoordinator$ResourceExchange.class")) {
            var compiled = ClassFile.of().parse(bytes.readAllBytes());
            var local = calls(compiled, "tickLocal");
            assert local.containsAll(List.of("crewRestockIdle", "crewInventorySettled", "releaseSupply", "nearPartner", "mayDrop", "saveLocal", "handleContainerInput"));
            assert local.indexOf("saveLocal") < local.indexOf("handleContainerInput") : "Durable intent precedes the drop packet";
            var sharedCode=ClassFile.of().parse(shared.readAllBytes());
            assert calls(sharedCode, "coordinate").containsAll(List.of("surplus", "transferConfirmed", "departSupply", "dispatch"));
            assert !calls(sharedCode, "coordinate").contains("pause") : "One transfer must not pause the whole crew";
            assert calls(compiled, "inventory").contains("confirmedDrop");
            assert calls(compiled, "close").containsAll(List.of("saveLocal", "listen"));
            assert calls(compiled, "accept").containsAll(List.of("unresolvedReceipt", "accept")) : "Restore same-token receipts before allowing a new exchange";
            assert calls(compiled, "tickLocal").contains("crewMakeTransferRoom");
        }
        System.out.println("Crew inventory checks passed: kit protection, all-heading lane disposal, exact policy bounds, nested conservation, receiver capacity, donor reserves, two-sided receipts and durable no-replay drops.");
    }
    private static List<String> calls(java.lang.classfile.ClassModel compiled, String name) {
        return compiled.methods().stream().filter(m -> m.methodName().equalsString(name)).findFirst().orElseThrow().code().orElseThrow().elementList().stream()
            .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).map(i -> i.name().stringValue()).toList();
    }
}
