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
        var eating = new ItemStack(Items.COOKED_BEEF, 32);
        assert AutoEat.sameFoodUse(eating, eating.copyWithCount(31), eating.copy()) : "Consuming an item preserves ownership of the same food stack";
        assert !AutoEat.sameFoodUse(eating, new ItemStack(Items.BREAD), eating);
        assert !AutoEat.sameFoodUse(eating, eating, new ItemStack(Items.BOW));
        assert !AutoEat.sameFoodUse(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
        assert !AutoEat.sameFoodUse(new ItemStack(Items.BOW), new ItemStack(Items.BOW), new ItemStack(Items.BOW));
        var renamed = eating.copy(); renamed.set(DataComponents.CUSTOM_NAME, Component.literal("New stack"));
        assert !AutoEat.sameFoodUse(eating, renamed, eating) : "A different food stack must release the use guard";
        assert !AutoEat.continueMeal(false, false, 18) : "Do not start a new meal above the threshold";
        for (int hunger : new int[] {14, 16, 18, 19, 17, 20}) {
            assert AutoEat.continueMeal(true, hunger <= 16, hunger) == (hunger < 20)
                : "An interrupted meal must continue above its trigger threshold, including after hunger falls again";
        }
        assert AutoEat.continueMeal(false, true, 18) : "Health can trigger a meal above the hunger threshold";
        assert AutoEat.continueMeal(false, true, 20) : "Preserve health-triggered always-edible food policy";
        assert !AutoEat.continueMeal(false, false, 20) : "A completed meal must not retrigger at full hunger";
        assert AutoEat.class.getDeclaredMethod("onTick", dev.monocle.client.events.world.TickEvent.Pre.class)
            .getAnnotation(meteordevelopment.orbit.EventHandler.class).priority() > meteordevelopment.orbit.EventPriority.MEDIUM
            : "Eating must claim its slot before Highway Builder's normal-priority tick";
        try (var bytes = AutoEat.class.getResourceAsStream("AutoEat.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            for (String name : List.of("onTick", "shouldEat")) {
                var method = compiled.methods().stream().filter(m -> m.methodName().equalsString(name)).findFirst().orElseThrow();
                var guards = method.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                    .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
                assert !guards.contains("screen") && !guards.contains("isWindowActive") && !guards.contains("isFocused")
                    : "Auto Eat and readiness must work with background, chat, pause and Bots windows";
                assert guards.contains("getCarried") : "Removing screen restrictions must retain cursor transaction safety";
                assert guards.contains("mealNeeded") : "Both ticking and readiness must honor the complete meal";
                if (name.equals("onTick")) assert guards.contains("setPressed") && !guards.contains("eat") && !guards.contains("useItem")
                    : "Claim eating early, but leave native use until after pending mining cleanup";
            }
            var eat = compiled.methods().stream().filter(m -> m.methodName().equalsString("eat")).findFirst().orElseThrow();
            var eatingCalls = eat.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
            assert eatingCalls.containsAll(List.of("setPressed", "isUsingItem", "isDestroying", "stopDestroyBlock", "useItem"))
                : "Food use must start directly without relying on GUI-suppressed vanilla key handling";
            var restart = compiled.methods().stream().filter(m -> m.methodName().equalsString("restartEating")).findFirst().orElseThrow().code().orElseThrow();
            var restartCalls = restart.elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
            assert restartCalls.containsAll(List.of("ownsItemUse", "releaseUsingItem", "setPressed")) && !restartCalls.contains("stopEating")
                : "Restart only owned food use, without handing the slot and work back between attempts";
            assert restart.elementList().stream().noneMatch(e -> e instanceof java.lang.classfile.instruction.FieldInstruction f
                && f.opcode() == java.lang.classfile.Opcode.PUTFIELD && List.of("eating", "mealPending", "slot", "prevSlot").contains(f.name().stringValue()))
                : "The stalled-use restart must retain the meal, selected slot and building pause";
            var ownership = compiled.methods().stream().filter(m -> m.methodName().equalsString("ownsItemUse")).findFirst().orElseThrow();
            var ownerCalls = ownership.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
            assert ownerCalls.containsAll(List.of("ownsFoodSlot", "isUsingItem", "getUsedItemHand", "sameFoodUse"));
            assert java.util.Collections.disjoint(ownerCalls, List.of("screen", "isWindowActive", "isFocused", "isDown"))
                : "Owned eating cannot depend on focus, screens or the physical use button";
            for (String methodName : List.of("ownsFoodSlot", "onPostTick")) {
                var method = compiled.methods().stream().filter(m -> m.methodName().equalsString(methodName)).findFirst().orElseThrow();
                var invoked = method.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                    .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
                assert java.util.Collections.disjoint(invoked, List.of("screen", "isWindowActive", "isFocused", "isDown"));
                assert invoked.containsAll(methodName.equals("ownsFoodSlot")
                    ? List.of("isActive", "isAlive", "getCarried", "getSelectedSlot", "sameFoodUse")
                    : List.of("ownsFoodSlot", "mealNeeded", "isEating", "eat"));
                if (methodName.equals("onPostTick")) assert method.code().orElseThrow().elementList().stream()
                    .anyMatch(e -> e instanceof java.lang.classfile.instruction.FieldInstruction f && f.name().equalsString("retryTicks"))
                    : "End-of-tick use must respect the short restart delay";
            }
            var change = compiled.methods().stream().filter(m -> m.methodName().equalsString("changeSlot")).findFirst().orElseThrow();
            var calls = change.code().orElseThrow().elementList().stream().filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
            assert calls.containsAll(List.of("getCarried", "foodHotbarSlot", "quickSwap", "fromId", "isSameItemSameComponents"));
            assert !calls.contains("move") && !calls.contains("drop") : "Food promotion must use cursor-free swaps, never pickup chains or drops";
            var stop = compiled.methods().stream().filter(m -> m.methodName().equalsString("stopEating")).findFirst().orElseThrow();
            assert stop.code().orElseThrow().elementList().stream().anyMatch(e -> e instanceof java.lang.classfile.instruction.InvokeInstruction c && c.name().equalsString("getSelectedSlot"))
                : "Cleanup must check current selection before restoring a slot";
        }
        try (var bytes = AutoEatTest.class.getResourceAsStream("/dev/monocle/client/mixin/MinecraftMixin.class")) {
            var compiled = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            for (String name : List.of("wrapStopUsing", "handleKeybindsInjectStopUsingItem", "HB$stopUsingItem")) {
                var method = compiled.methods().stream().filter(m -> m.methodName().equalsString(name)).findFirst().orElseThrow();
                String required = name.equals("HB$stopUsingItem") ? "ownsItemUse" : "HB$stopUsingItem";
                if (name.equals("HB$stopUsingItem")) assert method.code().orElseThrow().elementList().stream()
                    .filter(e -> e instanceof java.lang.classfile.instruction.InvokeInstruction c && c.name().equalsString("ownsItemUse")).count() == 2
                    : "Protect both Auto Eat and Auto Gap from physical key releases";
                assert method.code().orElseThrow().elementList().stream().anyMatch(e -> e instanceof java.lang.classfile.instruction.InvokeInstruction c && c.name().equalsString(required))
                    : "Both vanilla and Multitask key-release paths must consult Auto Eat ownership";
            }
        }
        System.out.println("Auto Eat checks passed: full-hunger meals, eating priority, owned stalled-use restarts, background/screen-independent eating, cursor safety, full-hotbar selection, food protection and native-swap guards.");
    }
}
