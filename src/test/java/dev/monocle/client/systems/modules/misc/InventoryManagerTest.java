package dev.monocle.client.systems.modules.misc;

import dev.monocle.client.utils.player.InventoryLoadout;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.List;

/** Production module policy checks without constructing a GPU-dependent module instance. */
public final class InventoryManagerTest {
    public static void main(String[] args) throws Exception {
        boolean assertions = false;
        assert assertions = true;
        if (!assertions) throw new IllegalStateException("Run with assertions enabled (-ea).");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(DataComponentInitializers.PendingComponents::apply);
        contextIdentity();
        disposalReserves();
        valuableProtection();
        controllerGuards();
        System.out.println("Inventory Manager checks passed: context identity, explicit disposal permission, loadout reserves, protected valuables, space-pressure gating, and main-thread cancellation guards.");
    }

    private static void contextIdentity() {
        Object menu = new Object(), screen = new Object(), world = new Object();
        assert InventoryTweaks.sameContext(menu, menu, screen, screen, world, world);
        assert InventoryTweaks.sameContext(menu, menu, null, null, world, world) : "Background cleanup has no screen but still binds menu and world identity";
        assert !InventoryTweaks.sameContext(menu, new Object(), screen, screen, world, world) : "A reused container ID is not the original container object";
        assert !InventoryTweaks.sameContext(menu, menu, screen, new Object(), world, world);
        assert !InventoryTweaks.sameContext(menu, menu, null, screen, world, world);
        assert !InventoryTweaks.sameContext(menu, menu, screen, screen, world, new Object());
        assert !InventoryTweaks.sameContext(null, null, screen, screen, world, world);
        assert !InventoryTweaks.sameContext(menu, menu, screen, screen, null, null);
        Object equalButDistinct = new String("same"), otherEqual = new String("same");
        assert equalButDistinct.equals(otherEqual);
        assert !InventoryTweaks.sameContext(equalButDistinct, otherEqual, screen, screen, world, world) : "Context compares identity, not equals";
    }

    private static void disposalReserves() {
        ItemStack filler = new ItemStack(Items.NETHERRACK, 64);
        var allowed = List.of(Items.NETHERRACK);
        var rules = List.of(new InventoryLoadout.Rule(filler, 64, 1));
        assert !InventoryTweaks.disposableStack(filler, List.of(), List.of(), List.of(), 128) : "Cleanup is an explicit allowlist, never a generic junk guess";
        assert !InventoryTweaks.disposableStack(ItemStack.EMPTY, allowed, List.of(), rules, 128);
        assert !InventoryTweaks.disposableStack(filler, allowed, List.of(Items.NETHERRACK), rules, 128) : "Protection overrides disposal permission";
        assert !InventoryTweaks.disposableStack(filler, allowed, List.of(), rules, 64) : "The working filler reserve must remain";
        assert !InventoryTweaks.disposableStack(filler, allowed, List.of(), rules, 127) : "Only a whole excess stack may be discarded to create a slot";
        assert InventoryTweaks.disposableStack(filler, allowed, List.of(), rules, 128);
        assert InventoryTweaks.disposableStack(filler.copyWithCount(8), allowed, List.of(), rules, 72) : "A small expendable stack can make room while preserving the full working reserve";
        var repeated = List.of(new InventoryLoadout.Rule(filler, 64, 1), new InventoryLoadout.Rule(filler, 64, -1));
        assert !InventoryTweaks.disposableStack(filler, allowed, List.of(), repeated, 128) : "Matching pinned and spare rules reserve their combined quantity";
        assert InventoryTweaks.disposableStack(filler, allowed, List.of(), repeated, 192);
        assert !InventoryTweaks.disposableStack(new ItemStack(Items.DIAMOND, 64), allowed, List.of(), List.of(), 64) : "Unknown, unapproved items remain untouched";
        assert InventoryTweaks.disposableStack(filler, allowed, List.of(), List.of(), 64) : "An explicitly approved item with no saved reserve can be reclaimed";
        assert filler.getCount() == 64 && rules.get(0).amount() == 64 : "Policy queries cannot mutate inventory or saved quantities";
    }

    private static void valuableProtection() {
        for (var item : List.of(Items.DIAMOND_PICKAXE, Items.SHEARS, Items.BOW, Items.ELYTRA,
            Items.SHULKER_BOX, Items.ENDER_CHEST, Items.CHEST, Items.BARREL, Items.FURNACE, Items.BUNDLE)) {
            ItemStack stack = new ItemStack(item);
            assert InventoryTweaks.protectedStack(stack) : "Tools, equipment and containers are protected: " + item;
            protectedFromDisposal(stack);
        }
        ItemStack unbreakable = new ItemStack(Items.DIAMOND_PICKAXE);
        unbreakable.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        assert !unbreakable.isDamageableItem() : "Native UNBREAKABLE items deliberately bypass isDamageableItem";
        assert InventoryTweaks.protectedStack(unbreakable);
        protectedFromDisposal(unbreakable);
        ItemStack named = new ItemStack(Items.DIRT, 64);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Do not discard"));
        assert InventoryTweaks.protectedStack(named);
        protectedFromDisposal(named);
        ItemStack custom = new ItemStack(Items.DIRT, 64);
        CompoundTag itemData = new CompoundTag();
        itemData.putString("server_item", "valuable");
        custom.set(DataComponents.CUSTOM_DATA, CustomData.of(itemData));
        protectedFromDisposal(custom);
        ItemStack holdingContents = new ItemStack(Items.DIRT, 64);
        holdingContents.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND))));
        protectedFromDisposal(holdingContents);

        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(VanillaRegistries.createLookup().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY), 5);
        ItemStack enchanted = new ItemStack(Items.DIRT, 64);
        enchanted.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());
        protectedFromDisposal(enchanted);
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        book.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
        assert book.getEnchantments().isEmpty() : "Stored book enchantments require their own protection check";
        assert InventoryTweaks.protectedStack(book);
        protectedFromDisposal(book);
        ItemStack food = new ItemStack(Items.COOKED_BEEF, 64);
        protectedFromDisposal(food);
        ItemStack customFood = new ItemStack(Items.DIRT, 64);
        customFood.set(DataComponents.FOOD, food.get(DataComponents.FOOD));
        protectedFromDisposal(customFood);
    }

    private static void protectedFromDisposal(ItemStack stack) {
        assert !InventoryTweaks.disposableStack(stack, List.of(stack.getItem()), List.of(), List.of(), stack.getCount())
            : "Safety protections override even an explicit allowlist entry: " + stack;
    }

    private static void controllerGuards() throws Exception {
        try (var bytes = InventoryTweaks.class.getResourceAsStream("InventoryTweaks.class")) {
            if (bytes == null) throw new AssertionError("Missing compiled Inventory Manager");
            var model = ClassFile.of().parse(bytes.readAllBytes());
            for (MethodModel method : model.methods()) {
                for (InvokeInstruction call : calls(method)) {
                    assert !call.owner().asInternalName().contains("MonocleExecutor") : "Container work must not escape into a background executor";
                    assert !(call.owner().asInternalName().equals("java/lang/Thread") && call.name().equalsString("sleep")) : "Inventory pacing must not sleep the client thread";
                }
            }
            MethodModel tick = model.methods().stream().filter(method -> method.methodName().equalsString("onTickPre")).findFirst().orElseThrow();
            var tickCalls = calls(tick);
            assert callIndex(tickCalls, "sameContext") < callIndex(tickCalls, "tickTransfer") : "Recheck exact menu, screen and world before any transfer step";
            assert callIndex(tickCalls, "otherInventoryWork") < callIndex(tickCalls, "tickTransfer") : "Yield to builders, eating and mending before continuing an inventory move";
            MethodModel cleanup = model.methods().stream().filter(method -> method.methodName().equalsString("cleanupForSpace")).findFirst().orElseThrow();
            var cleanupCalls = calls(cleanup);
            assert callIndex(cleanupCalls, "emptySlots") < callIndex(cleanupCalls, "compact") : "Check slot pressure before reclaiming inventory space";
            assert callIndex(cleanupCalls, "compact") < callIndex(cleanupCalls, "disposableStack") : "Try a lossless merge before selecting an expendable stack";
            assert callIndex(cleanupCalls, "disposableStack") < callIndex(cleanupCalls, "handleContainerInput") : "Validate permissions, valuable-item protection and quantity reserves before any discard";
            MethodModel cancel = model.methods().stream().filter(method -> method.methodName().equalsString("cancelOperation")).findFirst().orElseThrow();
            assert calls(cancel).stream().anyMatch(call -> call.owner().asInternalName().endsWith("/InventoryTransfer") && call.name().equalsString("cancel"))
                : "Cancelling the module operation must also cancel its bounded transfer";
        }
    }

    private static List<InvokeInstruction> calls(MethodModel method) {
        return method.code().stream().flatMap(code -> code.elementList().stream())
            .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).toList();
    }

    private static int callIndex(List<InvokeInstruction> calls, String name) {
        for (int i = 0; i < calls.size(); i++) if (calls.get(i).name().equalsString(name)) return i;
        throw new AssertionError("Missing safety call: " + name);
    }
}
