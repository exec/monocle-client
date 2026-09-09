package dev.monocle.client.systems.modules.player;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.food.FoodProperties;
import java.util.List;

/** ./gradlew autoEatCheck; no live clicks or eating required. */
public final class AutoEatTest {
    public static void main(String[] args) throws Exception {
        boolean enabled = false;
        assert enabled = true;
        if (!enabled) throw new IllegalStateException("Assertions required");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(DataComponentInitializers.PendingComponents::apply);
        var inventory = new SimpleContainer(36);
        for (int i = 0; i < 9; i++) inventory.setItem(i, new ItemStack(Items.OBSIDIAN, 64));
        inventory.setItem(35, new ItemStack(Items.COOKED_BEEF, 32));
        assert AutoEat.foodHotbarSlot(inventory, 8) == 8 : "Full hotbars must not hide inventory food";
        inventory.setItem(4, ItemStack.EMPTY);
        assert AutoEat.foodHotbarSlot(inventory, 8) == 4 : "Prefer an empty slot before displacing anything";
        assert inventory.getItem(8).getCount() == 64 && inventory.getItem(35).getCount() == 32 : "Selecting a slot must not mutate stacks";
        for (int invalid : new int[] {-1, 9, 40}) {
            try { AutoEat.foodHotbarSlot(inventory, invalid); throw new AssertionError("Invalid slot accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        var snack = new FoodProperties(2, 1, false);
        var meal = new FoodProperties(8, 12, false);
        assert AutoEat.foodScore(snack, AutoEat.Priority.LeastWaste, 2, false) > AutoEat.foodScore(meal, AutoEat.Priority.LeastWaste, 2, false);
        assert AutoEat.foodScore(meal, AutoEat.Priority.LeastWaste, 8, false) > AutoEat.foodScore(snack, AutoEat.Priority.LeastWaste, 8, false);
        assert AutoEat.foodScore(meal, AutoEat.Priority.LeastWaste, 2, true) > AutoEat.foodScore(snack, AutoEat.Priority.LeastWaste, 2, true);
        for (var priority : List.of(AutoEat.Priority.Combined, AutoEat.Priority.Hunger, AutoEat.Priority.Saturation))
            assert AutoEat.foodScore(meal, priority, 2, false) == priority.value(meal) : "Preserve existing priority modes";
        var named = new ItemStack(Items.BREAD);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Save this"));
        assert !AutoEat.foodAllowed(named, List.of(), true) && AutoEat.foodAllowed(named, List.of(), false);
        assert !AutoEat.foodAllowed(named, List.of(Items.BREAD), false) : "Disabling named protection must not bypass blacklist";
        assert !AutoEat.foodAllowed(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE), List.of(Items.ENCHANTED_GOLDEN_APPLE), true);
        try (var bytes = AutoEat.class.getResourceAsStream("AutoEat.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var change = compiled.methods().stream().filter(m -> m.methodName().equalsString("changeSlot")).findFirst().orElseThrow();
            var calls = change.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
            assert calls.containsAll(List.of("getCarried", "foodHotbarSlot", "quickSwap", "fromId", "isSameItemSameComponents"));
            assert !calls.contains("move") && !calls.contains("drop") : "Food promotion must use cursor-free swaps, never pickup chains or drops";
            var stop = compiled.methods().stream().filter(m -> m.methodName().equalsString("stopEating")).findFirst().orElseThrow();
            assert stop.code().orElseThrow().elementList().stream().anyMatch(e -> e instanceof java.lang.classfile.instruction.InvokeInstruction c && c.name().equalsString("getSelectedSlot"))
                : "Cleanup must check current selection before restoring a slot";
        }
        System.out.println("Auto Eat checks passed: full-hotbar selection, food protection, hunger-aware scores, native-swap and selection-ownership guards.");
    }
}
