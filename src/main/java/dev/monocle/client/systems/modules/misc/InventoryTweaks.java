/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 * Monocle rework: loadouts, pinned hotbars, space-aware cleanup and cancellable native transfers.
 */

package dev.monocle.client.systems.modules.misc;

import dev.monocle.client.events.entity.DropItemsEvent;
import dev.monocle.client.events.entity.player.InteractBlockEvent;
import dev.monocle.client.events.entity.player.InteractEntityEvent;
import dev.monocle.client.events.game.OpenScreenEvent;
import dev.monocle.client.events.game.GameLeftEvent;
import dev.monocle.client.events.monocle.KeyInputEvent;
import dev.monocle.client.events.monocle.MouseClickEvent;
import dev.monocle.client.events.packets.InventoryEvent;
import dev.monocle.client.events.packets.PacketEvent;
import dev.monocle.client.events.world.TickEvent;
import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.screens.InventoryManagerScreen;
import dev.monocle.client.gui.widgets.WWidget;
import dev.monocle.client.settings.*;
import dev.monocle.client.systems.modules.Categories;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.misc.Keybind;
import dev.monocle.client.utils.misc.input.KeyAction;
import dev.monocle.client.utils.player.*;
import dev.monocle.client.systems.modules.player.AutoEat;
import dev.monocle.client.systems.modules.player.AutoGap;
import dev.monocle.client.systems.modules.player.AutoMend;
import dev.monocle.client.systems.modules.world.HighwayBuilder;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.item.BlockItem;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;

public class InventoryTweaks extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSorting = settings.createGroup("Sorting");
    private final SettingGroup sgAntiDrop = settings.createGroup("Anti Drop");
    private final SettingGroup sgAutoDrop = settings.createGroup("Auto Drop");
    private final SettingGroup sgStealDump = settings.createGroup("Steal and Dump");
    private final SettingGroup sgAutoSteal = settings.createGroup("Auto Steal");
    private final SettingGroup sgLoadout = settings.createGroup("Loadout");

    private final Setting<OnOpen> onOpen = sgLoadout.add(new EnumSetting.Builder<OnOpen>()
        .name("on-open").description("Optional action once when a supported storage container opens.")
        .defaultValue(OnOpen.Off).build());
    private final Setting<Boolean> spaceCleanup = sgLoadout.add(new BoolSetting.Builder()
        .name("space-cleanup").description("Opt in to discarding permitted excess items only when space is needed. Named items, tools, food, containers and loadout reserves are protected.")
        .defaultValue(false).build());
    private final Setting<Integer> minimumFreeSlots = sgLoadout.add(new IntSetting.Builder()
        .name("minimum-free-slots").description("Try to keep this many free player slots by compacting stacks, then discarding permitted excess.")
        .defaultValue(1).range(0, 9).build());
    private final Setting<Integer> clicksPerTick = sgLoadout.add(new IntSetting.Builder()
        .name("clicks-per-tick").description("Maximum native inventory clicks per tick. Lower this if your server rejects transfers.")
        .defaultValue(4).range(1, 8).build());
    private final Setting<Boolean> legacyButtons = sgStealDump.add(new BoolSetting.Builder()
        .name("legacy-transfer-buttons").description("Also show unrestricted Steal/Dump buttons. Existing item filters and protection lists still apply.")
        .defaultValue(false).build());

    // General

    private final Setting<Boolean> mouseDragItemMove = sgGeneral.add(new BoolSetting.Builder()
        .name("mouse-drag-item-move")
        .description("Moving mouse over items while holding shift will transfer it to the other container.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> xCarry = sgGeneral.add(new BoolSetting.Builder()
        .name("xcarry")
        .description("Allows you to store four extra item stacks in your crafting grid.")
        .defaultValue(true)
        .onChanged(v -> {
            if (v || !Utils.canUpdate()) return;
            mc.player.connection.send(new ServerboundContainerClosePacket(mc.player.inventoryMenu.containerId));
            invOpened = false;
        })
        .build()
    );

    private final Setting<Boolean> uncapBundleScrolling = sgGeneral.add(new BoolSetting.Builder()
        .name("uncap-bundle-scrolling")
        .description("Whether to uncap the bundle scrolling feature to let you select any item.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> frameInput = sgGeneral.add(new BoolSetting.Builder()
        .name("frame-input-handling")
        .description("Changes input handling to work every frame instead of every tick. A very minor effect but may\n" +
            "make inputs feel smoother, especially in laggy environments. Will flag anticheats that check packet order (Grim).")
        .defaultValue(false)
        .build()
    );

    // Sorting

    private final Setting<Boolean> sortingEnabled = sgSorting.add(new BoolSetting.Builder()
        .name("sorting-enabled")
        .description("Enable the keybind for compacting main-inventory stacks without changing your hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Keybind> sortingKey = sgSorting.add(new KeybindSetting.Builder()
        .name("sorting-key")
        .description("Key to compact main-inventory stacks while an inventory is open.")
        .visible(sortingEnabled::get)
        .defaultValue(Keybind.fromButton(InputConstants.MOUSE_BUTTON_MIDDLE))
        .build()
    );

    private final Setting<Integer> sortingDelay = sgSorting.add(new IntSetting.Builder()
        .name("sorting-delay")
        .description("Delay in ticks between moving items when sorting.")
        .visible(sortingEnabled::get)
        .defaultValue(1)
        .min(0)
        .build()
    );

    private final Setting<Boolean> disableInCreative = sgSorting.add(new BoolSetting.Builder()
        .name("disable-in-creative")
        .description("Disables the inventory sorter when in creative mode.")
        .defaultValue(true)
        .visible(sortingEnabled::get)
        .build()
    );

    // Anti drop

    private final Setting<List<Item>> antiDropItems = sgAntiDrop.add(new ItemListSetting.Builder()
        .name("anti-drop-items")
        .description("Items to prevent dropping. Doesn't work in creative inventory screen.")
        .build()
    );

    private final Setting<Boolean> antiItemFrame = sgAntiDrop.add(new BoolSetting.Builder()
        .name("item-frames")
        .description("Prevent anti-drop items from being placed in item frames or pots")
        .defaultValue(true)
        .build()
    );

    private final Setting<Keybind> antiDropOverrideBind = sgAntiDrop.add(new KeybindSetting.Builder()
        .name("override-bind")
        .description("Hold this bind to temporarily bypass anti-drop")
        .build()
    );

    // Auto Drop

    private final Setting<List<Item>> autoDropItems = sgAutoDrop.add(new ItemListSetting.Builder()
        .name("auto-drop-items")
        .description("Items permitted for space cleanup. Only excess above the saved loadout is disposable; protected items are never dropped.")
        .build()
    );

    private final Setting<Boolean> autoDropExcludeEquipped = sgAutoDrop.add(new BoolSetting.Builder()
        .name("exclude-equipped")
        .description("Legacy setting. Inventory Manager always protects equipment and offhand.")
        .defaultValue(true)
        .visible(() -> false)
        .build()
    );

    private final Setting<Boolean> autoDropExcludeHotbar = sgAutoDrop.add(new BoolSetting.Builder()
        .name("exclude-hotbar")
        .description("Protect the entire hotbar from space cleanup. Pinned slots are always protected.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoDropOnlyFullStacks = sgAutoDrop.add(new BoolSetting.Builder()
        .name("only-full-stacks")
        .description("Only drops the items if the stack is full.")
        .defaultValue(false)
        .build()
    );

    // Steal & Dump

    public final Setting<List<MenuType<?>>> stealScreens = sgStealDump.add(new ScreenHandlerListSetting.Builder()
        .name("steal-screens")
        .description("Select the screens to display buttons and auto steal.")
        .defaultValue(List.of(MenuType.GENERIC_9x3, MenuType.GENERIC_9x6, MenuType.SHULKER_BOX))
        .build()
    );

    private final Setting<Boolean> buttons = sgStealDump.add(new BoolSetting.Builder()
        .name("inventory-buttons")
        .description("Shows Refill, Deposit, Compact and Cancel controls in storage containers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> stealDrop = sgStealDump.add(new BoolSetting.Builder()
        .name("steal-drop")
        .description("Legacy ground-transfer mode is disabled. Use explicit space cleanup for permitted player items.")
        .defaultValue(false)
        .visible(() -> false)
        .build()
    );

    private final Setting<Boolean> dropBackwards = sgStealDump.add(new BoolSetting.Builder()
        .name("drop-backwards")
        .description("Drop items behind you.")
        .defaultValue(false)
        .visible(() -> false)
        .build()
    );

    private final Setting<ListMode> dumpFilter = sgStealDump.add(new EnumSetting.Builder<ListMode>()
        .name("dump-filter")
        .description("Dump mode.")
        .defaultValue(ListMode.None)
        .build()
    );

    private final Setting<List<Item>> dumpItems = sgStealDump.add(new ItemListSetting.Builder()
        .name("dump-items")
        .description("Items to dump.")
        .build()
    );

    private final Setting<ListMode> stealFilter = sgStealDump.add(new EnumSetting.Builder<ListMode>()
        .name("steal-filter")
        .description("Steal mode.")
        .defaultValue(ListMode.None)
        .build()
    );

    private final Setting<List<Item>> stealItems = sgStealDump.add(new ItemListSetting.Builder()
        .name("steal-items")
        .description("Items to steal.")
        .build()
    );

    // Auto Steal

    private final Setting<Boolean> autoSteal = sgAutoSteal.add(new BoolSetting.Builder()
        .name("auto-steal")
        .description("Automatically removes all possible items when you open a container.")
        .defaultValue(false)
        .onChanged(_ -> checkAutoStealSettings())
        .build()
    );

    private final Setting<Boolean> autoDump = sgAutoSteal.add(new BoolSetting.Builder()
        .name("auto-dump")
        .description("Automatically dumps all possible items when you open a container.")
        .defaultValue(false)
        .onChanged(_ -> checkAutoStealSettings())
        .build()
    );

    private final Setting<Integer> autoStealDelay = sgAutoSteal.add(new IntSetting.Builder()
        .name("delay")
        .description("The minimum delay between stealing the next stack in milliseconds.")
        .defaultValue(20)
        .sliderMax(1000)
        .build()
    );

    private final Setting<Integer> autoStealInitDelay = sgAutoSteal.add(new IntSetting.Builder()
        .name("initial-delay")
        .description("The initial delay before stealing in milliseconds. 0 to use normal delay instead.")
        .defaultValue(50)
        .sliderMax(1000)
        .build()
    );

    private final Setting<Integer> autoStealRandomDelay = sgAutoSteal.add(new IntSetting.Builder()
        .name("random")
        .description("Randomly adds a delay of up to the specified time in milliseconds.")
        .min(0)
        .sliderMax(1000)
        .defaultValue(50)
        .build()
    );

    private boolean invOpened, consumeSortRelease;
    private CompoundTag savedLoadout = new CompoundTag();
    private List<InventoryLoadout.Rule> loadout = List.of();
    private boolean loadoutLoaded;
    private ClientLevel loadoutWorld;
    private String loadoutError, status = "Capture a loadout to get started.";
    private Operation operation;
    private AbstractContainerMenu operationMenu, automaticMenu;
    private Screen operationScreen;
    private ClientLevel operationWorld;
    private InventoryTransfer transfer;
    private boolean sendingClick, awaitingSync, cleanupTurned;
    private float cleanupYaw, cleanupPitch;
    private int syncTick, movedItems, corrections, cleanupSlot = -1;
    private long nextActionAt;
    private InventoryLoadout.Move confirmingMove;
    private ItemStack expectedFrom = ItemStack.EMPTY, expectedTo = ItemStack.EMPTY, expectedCursor = ItemStack.EMPTY;
    private int confirmingAmount;

    private enum Operation { Refill, Deposit, Compact, Arrange, Steal, Dump, Cleanup }
    public enum OnOpen { Off, Refill, Deposit }

    public InventoryTweaks() {
        super(Categories.Misc, "inventory-manager", "Saved loadouts, pinned hotbars and controlled supply transfers. Monocle rework of Meteor's Inventory Tweaks.", "inventory-tweaks");
    }

    @Override
    public void onActivate() {
        invOpened = false;
        automaticMenu = mc.player == null ? null : mc.player.containerMenu;
    }

    @Override
    public void onDeactivate() {
        cancelOperation();
        if (invOpened && mc.player != null) {
            mc.player.connection.send(new ServerboundContainerClosePacket(mc.player.inventoryMenu.containerId));
        }
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        var button = theme.button("Open Inventory Manager");
        button.action = () -> mc.gui.setScreen(new InventoryManagerScreen(theme, this));
        return button;
    }

    public Settings managerSettings() {
        Settings controls = new Settings();
        var group = controls.getDefaultGroup();
        for (Setting<?> setting : List.of(onOpen, spaceCleanup, minimumFreeSlots, autoDropItems)) group.add(setting);
        return controls;
    }

    public String getStatus() { return loadoutError != null ? loadoutError : status; }
    @Override public String getInfoString() { return getStatus(); }
    public boolean isBusy() { return operation != null; }
    public boolean hasLoadout() { return !getLoadout().isEmpty(); }
    public boolean showLegacyButtons() { return legacyButtons.get(); }

    public List<InventoryLoadout.Rule> getLoadout() {
        if ((!loadoutLoaded || loadoutWorld != mc.level) && mc.player != null) {
            try {
                loadout = savedLoadout.isEmpty() ? List.of() : InventoryLoadout.fromTag(savedLoadout, mc.player.registryAccess());
                loadoutError = null;
            } catch (IllegalArgumentException e) {
                loadoutError = "Saved loadout cannot be read in this world. Original data kept; capture a replacement to reset it.";
                loadout = List.of();
            }
            loadoutLoaded = true;
            loadoutWorld = mc.level;
        }
        return loadout;
    }

    public void captureLoadout() {
        if (mc.player == null) { status = "Join a world to capture a loadout."; return; }
        saveLoadout(InventoryLoadout.capture(mc.player.getInventory()));
        status = "Captured " + loadout.size() + " loadout rules. Edit quantities and hotbar pins below.";
    }

    public void updateRule(int index, int amount, int hotbarSlot) {
        var rules = new ArrayList<>(getLoadout());
        if (index < 0 || index >= rules.size()) return;
        try {
            rules.set(index, new InventoryLoadout.Rule(rules.get(index).template(), amount, hotbarSlot));
            saveLoadout(rules);
        } catch (IllegalArgumentException e) { status = "Each hotbar slot can have only one rule; quantities must be 1–2304."; }
    }

    public void removeRule(int index) {
        var rules = new ArrayList<>(getLoadout());
        if (index < 0 || index >= rules.size()) return;
        rules.remove(index);
        saveLoadout(rules);
    }

    public void clearLoadout() {
        cancelOperation();
        savedLoadout = new CompoundTag();
        loadout = List.of();
        loadoutLoaded = true;
        loadoutWorld = mc.level;
        loadoutError = null;
        status = "Loadout cleared. No inventory items were changed.";
    }

    private void saveLoadout(List<InventoryLoadout.Rule> rules) {
        if (mc.player == null) { status = "Join a world to edit the loadout."; return; }
        List<InventoryLoadout.Rule> checked = InventoryLoadout.validateRules(rules);
        CompoundTag encoded = InventoryLoadout.toTag(checked, mc.player.registryAccess());
        cancelOperation();
        savedLoadout = encoded;
        loadout = checked;
        loadoutLoaded = true;
        loadoutWorld = mc.level;
        loadoutError = null;
        status = "Loadout saved.";
    }

    @Override public CompoundTag toTag() {
        CompoundTag tag = super.toTag();
        if (tag != null) tag.put("loadout", savedLoadout.copy());
        return tag;
    }

    @Override public Module fromTag(CompoundTag tag) {
        cancelOperation();
        savedLoadout = tag.getCompoundOrEmpty("loadout").copy();
        loadoutLoaded = false;
        loadoutError = null;
        loadout = List.of();
        return super.fromTag(tag);
    }

    public void refill(AbstractContainerMenu menu) { begin(Operation.Refill, menu); }
    public void deposit(AbstractContainerMenu menu) { begin(Operation.Deposit, menu); }
    public void compact(AbstractContainerMenu menu) { begin(Operation.Compact, menu); }
    public void arrange(AbstractContainerMenu menu) { begin(Operation.Arrange, menu); }
    public void steal(AbstractContainerMenu menu) { begin(Operation.Steal, menu); }
    public void dump(AbstractContainerMenu menu) { begin(Operation.Dump, menu); }

    private void begin(Operation requested, AbstractContainerMenu menu) {
        cancelOperation();
        if (mc.player == null || mc.level == null || mc.player.containerMenu != menu) { status = "Open an inventory first."; return; }
        if (mc.player.isCreative()) { status = "Inventory automation is disabled in Creative; items are unchanged."; return; }
        boolean storageAction = requested == Operation.Refill || requested == Operation.Deposit || requested == Operation.Steal || requested == Operation.Dump;
        if (storageAction ? !storageMenu(menu) : !(menu instanceof InventoryMenu) && !storageMenu(menu)) {
            status = "Use a chest, barrel, shulker, hopper or dispenser inventory.";
            return;
        }
        if ((requested == Operation.Refill || requested == Operation.Deposit || requested == Operation.Arrange) && !hasLoadout()) {
            status = "Capture a loadout first.";
            return;
        }
        if (otherInventoryWork()) { status = "Finish eating or pause the active builder/mending job first."; return; }
        if (!isActive()) enable();
        operation = requested;
        operationMenu = menu;
        operationScreen = mc.gui.screen();
        operationWorld = mc.level;
        automaticMenu = menu;
        movedItems = corrections = 0;
        cleanupSlot = -1;
        nextActionAt = System.currentTimeMillis() + Math.max(0, autoStealInitDelay.get());
        status = requested + ": checking inventory";
        requestSync();
    }

    public void cancelOperation() {
        if (operation != null) status = "Cancelled. Items remain in inventory or on your cursor.";
        if (transfer != null) transfer.cancel();
        if (cleanupTurned && mc.player != null && mc.level == operationWorld
            && mc.player.getYRot() == cleanupYaw + 180 && mc.player.getXRot() == -15) {
            mc.player.setYRot(cleanupYaw);
            mc.player.setXRot(cleanupPitch);
        }
        cleanupTurned = false;
        transfer = null;
        operation = null;
        operationMenu = null;
        operationWorld = null;
        operationScreen = null;
        confirmingMove = null;
        awaitingSync = false;
        cleanupSlot = -1;
    }

    public void onManualInput() {
        cancelOperation();
        automaticMenu = mc.player == null ? null : mc.player.containerMenu;
    }

    private void finish(String message) {
        cancelOperation();
        status = message;
    }

    @EventHandler private void onGameLeft(GameLeftEvent event) {
        cancelOperation();
        automaticMenu = null;
        loadoutLoaded = false;
        invOpened = false;
    }

    @EventHandler
    private void onKey(KeyInputEvent event) {
        if (event.action != KeyAction.Press) return;

        if (sortingKey.get().matches(event.input)) {
            if (sort()) event.cancel();
        }
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (event.action == KeyAction.Release && consumeSortRelease && sortingKey.get().matches(event.input)) {
            consumeSortRelease = false;
            event.cancel();
            return;
        }
        if (event.action != KeyAction.Press) return;

        if (sortingKey.get().matches(event.input)) {
            if (sort()) {
                consumeSortRelease = true;
                event.cancel();
            }
        }
    }

    private boolean sort() {
        if (!sortingEnabled.get() || !(mc.gui.screen() instanceof AbstractContainerScreen<?>) || mc.player.isCreative()) return false;
        compact(mc.player.containerMenu);
        return true;
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (operation != null && event.screen != operationScreen) cancelOperation();
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) { cancelOperation(); return; }
        if (operation == null) {
            if (mc.player.containerMenu != automaticMenu && mc.gui.screen() instanceof AbstractContainerScreen<?> && canSteal(mc.player.containerMenu)) {
                automaticMenu = mc.player.containerMenu;
                if (onOpen.get() == OnOpen.Refill) refill(automaticMenu);
                else if (onOpen.get() == OnOpen.Deposit) deposit(automaticMenu);
                else if (autoSteal.get()) steal(automaticMenu);
                else if (autoDump.get()) dump(automaticMenu);
            } else if (spaceCleanup.get() && !autoDropItems.get().isEmpty() && mc.gui.screen() == null
                && mc.player.containerMenu instanceof InventoryMenu && emptySlots() < minimumFreeSlots.get()
                && !otherInventoryWork() && System.currentTimeMillis() >= nextActionAt) begin(Operation.Cleanup, mc.player.containerMenu);
            return;
        }
        if (!sameContext(operationMenu, mc.player.containerMenu, operationScreen, mc.gui.screen(), operationWorld, mc.level)) {
            finish("Stopped: inventory or world changed.");
            return;
        }
        if (otherInventoryWork()) { finish("Stopped for another inventory action."); return; }
        if (cleanupTurned && (mc.player.getYRot() != cleanupYaw + 180 || mc.player.getXRot() != -15)) {
            finish("Cleanup cancelled: you changed your view.");
            return;
        }
        if (awaitingSync) {
            if (mc.player.tickCount - syncTick > 120) finish("Server did not confirm the inventory. Items preserved; retry when it responds.");
            return;
        }
        if (System.currentTimeMillis() < nextActionAt) return;
        if (transfer != null) {
            tickTransfer();
            return;
        }
        if (!operationMenu.getCarried().isEmpty()) {
            int destination = InventoryLoadout.cursorDestination(operationMenu.slots, mc.player.getInventory(), operationMenu.getCarried());
            if (destination < 0) { finish("No safe slot for the held item. Put it away, then retry; nothing was dropped."); return; }
            click(destination, 0);
            requestSync();
            return;
        }
        if (operation == Operation.Cleanup && emptySlots() >= minimumFreeSlots.get()) {
            finish("Inventory space ready.");
            return;
        }
        List<InventoryLoadout.Rule> rules = getLoadout();
        InventoryLoadout.Move move = switch (operation) {
            case Refill -> {
                var arrange = InventoryLoadout.arrange(operationMenu.slots, mc.player.getInventory(), rules);
                yield arrange != null ? arrange : InventoryLoadout.refill(operationMenu.slots, mc.player.getInventory(), rules);
            }
            case Deposit -> InventoryLoadout.deposit(operationMenu.slots, mc.player.getInventory(), rules, this::protectedForDeposit);
            case Compact, Cleanup -> InventoryLoadout.compact(operationMenu.slots, mc.player.getInventory(), rules);
            case Arrange -> InventoryLoadout.arrange(operationMenu.slots, mc.player.getInventory(), rules);
            case Steal, Dump -> legacyMove(operation == Operation.Steal);
        };
        if (move != null) {
            cleanupSlot = -1;
            confirmingMove = move;
            transfer = new InventoryTransfer(operationMenu, move, mc.player);
            status = operation + ": " + operationMenu.getSlot(move.from()).getItem().getHoverName().getString() + " (" + movedItems + " moved)";
            tickTransfer();
            return;
        }
        if (operation == Operation.Cleanup || operation == Operation.Refill && refillHasAvailableSupply()) {
            if (cleanupForSpace()) return;
        }
        String result = operation == Operation.Refill ? refillSummary() : operation + " complete: " + movedItems + " items moved.";
        if (operation == Operation.Cleanup) {
            result = emptySlots() >= minimumFreeSlots.get() ? "Inventory space ready." : "Inventory full: remaining items are protected or reserved.";
            nextActionAt = System.currentTimeMillis() + 2000;
        }
        finish(result);
    }

    public static boolean sameContext(Object expectedMenu, Object actualMenu, Object expectedScreen, Object actualScreen, Object expectedWorld, Object actualWorld) {
        return expectedMenu != null && expectedWorld != null && expectedMenu == actualMenu && expectedScreen == actualScreen && expectedWorld == actualWorld;
    }

    // Anti Drop

    @EventHandler
    private void onDropItems(DropItemsEvent event) {
        if (antiDropOverrideBind.get().isPressed()) return;
        if (antiDropItems.get().contains(event.itemStack.getItem())) event.cancel();
    }

    @EventHandler
    private void onInteractEntity(InteractEntityEvent event) {
        if (!antiItemFrame.get() || antiDropOverrideBind.get().isPressed()) return;
        if (!(event.entity instanceof ItemFrame)) return;

        Item item = mc.player.getItemInHand(event.hand).getItem();
        if (antiDropItems.get().contains(item)) event.cancel();
    }

    @EventHandler
    private void onInteractBlock(InteractBlockEvent event) {
        if (!antiItemFrame.get() || antiDropOverrideBind.get().isPressed()) return;
        if (event.hand != InteractionHand.MAIN_HAND) return;
        Block block = mc.level.getBlockState(event.result.getBlockPos()).getBlock();
        if (!(block instanceof DecoratedPotBlock)) return;

        Item item = mc.player.getItemInHand(event.hand).getItem();
        if (antiDropItems.get().contains(item)) event.cancel();
    }

    // XCarry

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (operation != null && !sendingClick && event.packet instanceof ServerboundContainerClickPacket) onManualInput();
        if (mc.player == null) return;
        if (!xCarry.get() || !(event.packet instanceof ServerboundContainerClosePacket packet)) return;

        if (packet.getContainerId() == mc.player.inventoryMenu.containerId) {
            invOpened = true;
            event.cancel();
        }
    }

    // Auto Steal

    private void checkAutoStealSettings() {
        if (autoSteal.get() && autoDump.get()) {
            error("You can't enable Auto Steal and Auto Dump at the same time!");
            autoDump.set(false);
        }
    }

    private void tickTransfer() {
        InventoryTransfer running = transfer;
        boolean done = running.tick(clicksPerTick.get(), this::click);
        if (running.failed()) { finish(running.error()); return; }
        if (!done) return;
        confirmingAmount = running.moved();
        expectedFrom = operationMenu.getSlot(confirmingMove.from()).getItem().copy();
        expectedTo = operationMenu.getSlot(confirmingMove.to()).getItem().copy();
        transfer = null;
        long delay = operation == Operation.Compact || operation == Operation.Cleanup
            ? Math.max(0, sortingDelay.get()) * 50L
            : Math.max(0, autoStealDelay.get()) + (autoStealRandomDelay.get() > 0 ? ThreadLocalRandom.current().nextInt(autoStealRandomDelay.get()) : 0);
        nextActionAt = System.currentTimeMillis() + delay;
        requestSync();
    }

    private void click(int slot, int button) {
        sendingClick = true;
        try { mc.gameMode.handleContainerInput(operationMenu.containerId, slot, button, ContainerInput.PICKUP, mc.player); }
        finally { sendingClick = false; }
    }

    private void requestSync() {
        awaitingSync = true;
        syncTick = mc.player.tickCount;
        expectedCursor = operationMenu.getCarried().copy();
        sendingClick = true;
        try {
            // A no-op slot with an impossible revision requests authoritative contents, not an outside/drop click.
            mc.getConnection().send(new ServerboundContainerClickPacket(operationMenu.containerId, -1, (short) -1, (byte) 0,
                ContainerInput.PICKUP, it.unimi.dsi.fastutil.ints.Int2ObjectMaps.emptyMap(),
                net.minecraft.network.HashedStack.create(operationMenu.getCarried(), mc.getConnection().decoratedHashOpsGenenerator())));
        } finally { sendingClick = false; }
    }

    private boolean otherInventoryWork() {
        HighwayBuilder builder = Modules.get().get(HighwayBuilder.class);
        return mc.player.isUsingItem() || Modules.get().get(AutoEat.class).eating || Modules.get().get(AutoGap.class).isEating()
            || Modules.get().isActive(AutoMend.class) || builder.hasJob() && !builder.isJobPaused()
            || Modules.get().get(dev.monocle.client.systems.modules.world.PrinterHelper.class).controlsInventory();
    }

    public static boolean storageMenu(AbstractContainerMenu menu) {
        return menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu || menu instanceof HopperMenu || menu instanceof DispenserMenu;
    }

    private int emptySlots() {
        int count = 0;
        for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).isEmpty()) count++;
        return count;
    }

    public static boolean protectedStack(ItemStack stack) {
        return stack.has(DataComponents.MAX_DAMAGE) || stack.has(DataComponents.UNBREAKABLE)
            || stack.has(DataComponents.CUSTOM_NAME) || stack.has(DataComponents.CUSTOM_DATA)
            || !stack.getEnchantments().isEmpty()
            || !stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY).isEmpty()
            || stack.has(DataComponents.CONTAINER) || stack.has(DataComponents.BUNDLE_CONTENTS)
            || stack.getItem() instanceof BlockItem block && block.getBlock() instanceof BaseEntityBlock;
    }

    private boolean protectedForDeposit(ItemStack stack) {
        return protectedStack(stack) || antiDropItems.get().contains(stack.getItem());
    }

    public static boolean disposableStack(ItemStack stack, List<Item> permitted, List<Item> protectedItems,
                                          List<InventoryLoadout.Rule> rules, int carriedCount) {
        return !stack.isEmpty() && permitted.contains(stack.getItem()) && !protectedItems.contains(stack.getItem())
            && !protectedStack(stack) && !stack.has(DataComponents.FOOD)
            && carriedCount - InventoryLoadout.target(rules, stack) >= stack.getCount();
    }

    private boolean cleanupForSpace() {
        // A blocked refill may have empty slots reserved for other pins; count only legal destinations via its planner.
        if (operation == Operation.Cleanup && emptySlots() >= minimumFreeSlots.get()) return false;
        // Compact even without disposal permission; a merge can free a slot without throwing anything away.
        var compact = InventoryLoadout.compact(operationMenu.slots, mc.player.getInventory(), getLoadout());
        if (compact != null) {
            confirmingMove = compact;
            transfer = new InventoryTransfer(operationMenu, compact, mc.player);
            status = "Compacting stacks to make room";
            tickTransfer();
            return true;
        }
        if (!spaceCleanup.get() || loadoutError != null) return false;
        int selected = -1;
        for (int i = autoDropExcludeHotbar.get() ? 9 : 0; i < 36; i++) {
            final int slot = i;
            if (getLoadout().stream().anyMatch(rule -> rule.hotbarSlot() == slot)) continue;
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (autoDropOnlyFullStacks.get() && stack.getCount() < stack.getMaxStackSize()) continue;
            if (disposableStack(stack, autoDropItems.get(), antiDropItems.get(), getLoadout(), InventoryLoadout.count(mc.player.getInventory(), stack))
                && (selected < 0 || stack.getCount() < mc.player.getInventory().getItem(selected).getCount())) selected = i;
        }
        if (selected < 0) return false;
        status = "Making room: discarding permitted excess " + mc.player.getInventory().getItem(selected).getHoverName().getString();
        if (cleanupSlot != selected) {
            cleanupSlot = selected;
            if (!cleanupTurned) {
                cleanupYaw = mc.player.getYRot();
                cleanupPitch = mc.player.getXRot();
                cleanupTurned = true;
            }
            mc.player.setYRot(cleanupYaw + 180);
            mc.player.setXRot(-15);
            nextActionAt = System.currentTimeMillis() + 150;
            return true;
        }
        int id = -1;
        for (int i = 0; i < operationMenu.slots.size(); i++) {
            Slot slot = operationMenu.getSlot(i);
            if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == selected
                && slot.isActive() && !slot.isFake() && slot.mayPickup(mc.player)) { id = i; break; }
        }
        if (id < 0) return false;
        sendingClick = true;
        try { mc.gameMode.handleContainerInput(operationMenu.containerId, id, 1, ContainerInput.THROW, mc.player); }
        finally { sendingClick = false; }
        cleanupSlot = -1;
        // Verify the dropped slot on the next full server snapshot too.
        confirmingMove = new InventoryLoadout.Move(id, id, 0);
        confirmingAmount = 0;
        expectedFrom = expectedTo = operationMenu.getSlot(id).getItem().copy();
        requestSync();
        return true;
    }

    private boolean refillHasAvailableSupply() {
        for (Slot slot : operationMenu.slots) {
            ItemStack stack = slot.getItem();
            if (slot.container != mc.player.getInventory() && slot.isActive() && !slot.isFake() && slot.mayPickup(mc.player)
                && !stack.isEmpty() && !stack.has(DataComponents.BUNDLE_CONTENTS)
                && InventoryLoadout.count(mc.player.getInventory(), stack) < InventoryLoadout.target(getLoadout(), stack)) return true;
        }
        return false;
    }

    private String refillSummary() {
        for (var rule : getLoadout()) {
            int count = InventoryLoadout.count(mc.player.getInventory(), rule.template());
            int target = InventoryLoadout.target(getLoadout(), rule.template());
            if (count < target) return "Refill stopped: " + rule.template().getHoverName().getString() + " " + count + "/" + target
                + (refillHasAvailableSupply() ? "; no safe inventory room." : "; missing from this container.");
        }
        return "Loadout refilled: " + movedItems + " items moved.";
    }

    private InventoryLoadout.Move legacyMove(boolean stealing) {
        var inventory = mc.player.getInventory();
        for (int from = 0; from < operationMenu.slots.size(); from++) {
            Slot source = operationMenu.getSlot(from);
            boolean playerSource = source.container == inventory;
            if (stealing == playerSource || !source.isActive() || source.isFake() || !source.mayPickup(mc.player)) continue;
            if (playerSource && (source.getContainerSlot() < 0 || source.getContainerSlot() >= 36)) continue;
            ItemStack stack = source.getItem();
            if (stack.isEmpty() || stack.has(DataComponents.BUNDLE_CONTENTS) || !stealing && antiDropItems.get().contains(stack.getItem())) continue;
            ListMode filter = stealing ? stealFilter.get() : dumpFilter.get();
            List<Item> items = stealing ? stealItems.get() : dumpItems.get();
            if (filter == ListMode.Whitelist && !items.contains(stack.getItem()) || filter == ListMode.Blacklist && items.contains(stack.getItem())) continue;
            for (boolean empty : new boolean[] {false, true}) {
                for (int to = 0; to < operationMenu.slots.size(); to++) {
                    Slot destination = operationMenu.getSlot(to);
                    boolean playerDestination = destination.container == inventory;
                    if (stealing != playerDestination || !destination.isActive() || destination.isFake() || !destination.mayPlace(stack)) continue;
                    if (playerDestination && (destination.getContainerSlot() < 0 || destination.getContainerSlot() >= 36)) continue;
                    ItemStack held = destination.getItem();
                    if (held.isEmpty() != empty || !empty && !ItemStack.isSameItemSameComponents(stack, held)) continue;
                    int amount = Math.min(stack.getCount(), Math.min(stack.getMaxStackSize(), destination.getMaxStackSize(stack)) - held.getCount());
                    if (amount > 0) return new InventoryLoadout.Move(from, to, amount);
                }
            }
        }
        return null;
    }

    public boolean showButtons() {
        return isActive() && buttons.get();
    }

    public boolean mouseDragItemMove() {
        return isActive() && mouseDragItemMove.get();
    }

    public boolean uncapBundleScrolling() {
        return isActive() && uncapBundleScrolling.get();
    }

    public boolean frameInput() {
        return isActive() && frameInput.get();
    }

    public boolean canSteal(AbstractContainerMenu handler) {
        if (!storageMenu(handler)) return false;
        try {
            return (stealScreens.get().contains(handler.getType()));
        } catch (UnsupportedOperationException _) {
            return false;
        }
    }

    @EventHandler
    private void onInventory(InventoryEvent event) {
        if (mc.player == null || operation == null || !awaitingSync || mc.player.containerMenu != operationMenu
            || event.packet.containerId() != operationMenu.containerId) return;
        awaitingSync = false;
        boolean corrected = !ItemStack.matches(expectedCursor, operationMenu.getCarried())
            || confirmingMove != null && (!ItemStack.matches(expectedFrom, operationMenu.getSlot(confirmingMove.from()).getItem())
                || !ItemStack.matches(expectedTo, operationMenu.getSlot(confirmingMove.to()).getItem()));
        if (corrected) {
            status = "Server corrected inventory; recalculating.";
            if (++corrections >= 3) { finish("Server repeatedly rejected inventory changes. Lower clicks per tick and retry; items are preserved."); return; }
        } else {
            corrections = 0;
            if (confirmingMove != null) movedItems += confirmingAmount;
        }
        confirmingMove = null;
    }

    public enum ListMode {
        Whitelist,
        Blacklist,
        None
    }
}
