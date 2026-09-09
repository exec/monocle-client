package dev.monocle.client.gui.screens;

import dev.monocle.client.gui.GuiTheme;
import dev.monocle.client.gui.WindowScreen;
import dev.monocle.client.gui.widgets.WLabel;
import dev.monocle.client.gui.widgets.containers.WContainer;
import dev.monocle.client.gui.widgets.containers.WHorizontalList;
import dev.monocle.client.gui.widgets.containers.WTable;
import dev.monocle.client.gui.widgets.input.WDropdown;
import dev.monocle.client.gui.widgets.input.WIntEdit;
import dev.monocle.client.gui.widgets.pressable.WButton;
import dev.monocle.client.settings.Settings;
import dev.monocle.client.utils.player.InventoryLoadout;
import dev.monocle.client.systems.modules.misc.InventoryTweaks;
import dev.monocle.client.utils.render.prompts.YesNoPrompt;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

import static dev.monocle.client.MonocleClient.mc;

/** Saved loadout editing does not move or discard items. Transfers are explicit actions. */
public class InventoryManagerScreen extends WindowScreen {
    private static final String[] HOTBAR_SLOTS = { "None", "1", "2", "3", "4", "5", "6", "7", "8", "9" };

    private final InventoryTweaks manager;
    private final Settings managerSettings;
    private final List<WLabel> counts = new ArrayList<>();
    private final List<WDropdown<String>> pins = new ArrayList<>();
    private WLabel status;
    private WContainer settings;

    public InventoryManagerScreen(GuiTheme theme, InventoryTweaks manager) {
        super(theme, "Inventory Manager");
        this.manager = manager;
        this.managerSettings = manager.managerSettings();
    }

    @Override
    public void initWidgets() {
        counts.clear();
        pins.clear();
        status = add(theme.label(manager.getStatus(), true, 560)).expandX().widget();
        add(theme.label("Save what you carry, choose how much to keep, and pin your hotbar.\nOpen a storage container to Refill Loadout, Deposit Excess, or Compact Stacks.", 560)
            .color(theme.textSecondaryColor())).expandX();

        WHorizontalList actions = add(theme.horizontalList()).expandX().widget();
        WButton capture = actions.add(theme.button("Capture current inventory")).widget();
        capture.tooltip = "Save the items and total quantities in your 36 inventory slots. Existing hotbar items become pins. Armor and offhand are excluded. No items move.";
        capture.action = () -> {
            if (mc.player == null) {
                manager.captureLoadout();
                return;
            }
            if (manager.hasLoadout()) confirm("Replace saved loadout?", "This replaces your saved quantities and hotbar pins, not your items.", () -> {
                manager.captureLoadout();
                reload();
            });
            else {
                manager.captureLoadout();
                reload();
            }
        };

        WButton clear = actions.add(theme.button("Clear loadout")).widget();
        clear.tooltip = "Remove the saved rules only. Inventory items are never deleted.";
        clear.action = () -> {
            if (manager.hasLoadout()) confirm("Clear saved loadout?", "Remove all saved quantities and pins? Your inventory will not change.", () -> {
                manager.clearLoadout();
                reload();
            });
        };

        settings = add(theme.verticalList()).expandX().widget();
        settings.add(theme.settings(managerSettings)).expandX();
        add(theme.horizontalSeparator("Saved loadout")).expandX();
        if (!manager.hasLoadout()) {
            add(theme.label("Carry the items you want, then Capture current inventory.\nYou can change quantities or remove rules afterward.", 560)).expandX();
        } else {
            WTable table = add(theme.table()).expandX().widget();
            table.add(theme.label("Item"));
            table.add(theme.label("Have (total)"));
            table.add(theme.label("Keep (items)"));
            table.add(theme.label("Hotbar"));
            table.add(theme.label(""));
            table.row();

            List<InventoryLoadout.Rule> rules = manager.getLoadout();
            for (int i = 0; i < rules.size(); i++) {
                int index = i;
                InventoryLoadout.Rule rule = rules.get(i);
                WHorizontalList item = table.add(theme.horizontalList()).expandCellX().widget();
                item.add(theme.item(rule.template()));
                item.add(theme.label(rule.template().getHoverName().getString(), 180)).centerY();
                item.tooltip = "Matches this item, enchantments, names and contents. Tool wear is ignored. Multiple matching rules add their quantities together.";

                counts.add(table.add(theme.label("0")).centerY().widget());
                int max = Math.max(rule.amount(), Math.min(2304, 36 * rule.template().getMaxStackSize()));
                WIntEdit amount = table.add(theme.intEdit(rule.amount(), 1, max, true)).centerY().widget();
                amount.tooltip = "Desired total item count across your inventory, not a number of stacks.";
                WDropdown<String> pin = table.add(theme.dropdown(HOTBAR_SLOTS, HOTBAR_SLOTS[rule.hotbarSlot() + 1])).centerY().widget();
                pin.tooltip = "Keep a stack in this hotbar slot. Each slot accepts one rule; unpin its current rule before assigning another.";
                pins.add(pin);
                amount.action = () -> {
                    int value = Math.clamp(amount.get(), 1, max);
                    if (amount.get() != value) amount.set(value);
                    manager.updateRule(index, value, pin.get().equals("None") ? -1 : Integer.parseInt(pin.get()) - 1);
                };
                pin.action = () -> {
                    amount.action.run();
                    refreshLabels();
                };
                WButton remove = table.add(theme.button("Remove")).centerY().widget();
                remove.tooltip = "Stop refilling and reserving this item. Does not discard anything.";
                remove.action = () -> {
                    manager.removeRule(index);
                    reload();
                };
                table.row();
            }
        }

        add(theme.horizontalSeparator()).expandX();
        WHorizontalList bottom = add(theme.horizontalList()).expandX().widget();
        WButton arrange = bottom.add(theme.button("Arrange hotbar")).widget();
        arrange.tooltip = "Apply saved pins to the items you already carry. Does not take from containers or discard items.";
        arrange.action = () -> {
            if (mc.player != null) manager.arrange(mc.player.inventoryMenu);
        };
        WButton compact = bottom.add(theme.button("Compact stacks")).widget();
        compact.tooltip = "Merge matching stacks in your main inventory. Leaves the hotbar unchanged and never throws items away.";
        compact.action = () -> {
            if (mc.player != null) manager.compact(mc.player.inventoryMenu);
        };
        WButton cancel = bottom.add(theme.button("Cancel")).widget();
        cancel.action = manager::cancelOperation;
        WButton advanced = bottom.add(theme.button("Advanced & keybind")).expandCellX().right().widget();
        advanced.action = () -> {
            manager.cancelOperation();
            mc.gui.setScreen(new ModuleScreen(theme, manager));
        };
        refreshLabels();
    }

    private void confirm(String title, String message, Runnable action) {
        manager.cancelOperation();
        YesNoPrompt.create(theme, this).title(title).message(message)
            .dontShowAgainCheckboxVisible(false).onYes(action).show();
    }

    private void refreshLabels() {
        status.set(manager.getStatus());
        List<InventoryLoadout.Rule> rules = manager.getLoadout();
        for (int i = 0; i < Math.min(rules.size(), counts.size()); i++) {
            InventoryLoadout.Rule rule = rules.get(i);
            counts.get(i).set(mc.player == null ? "—" : Integer.toString(InventoryLoadout.count(mc.player.getInventory(), rule.template())));
            pins.get(i).set(HOTBAR_SLOTS[rule.hotbarSlot() + 1]);
        }
    }

    @Override
    public void tick() {
        super.tick();
        managerSettings.tick(settings, theme);
        refreshLabels();
    }

    @Override
    public boolean keyPressed(@NonNull KeyEvent event) {
        manager.onManualInput();
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        manager.cancelOperation();
        super.onClose();
    }

    @Override
    protected void onClosed() {
        manager.cancelOperation();
    }

    /** Pure layout calculation; never covers a container's slot rectangle, even at high GUI scales. */
    public record ToolbarLayout(int x, int y, int buttonWidth, boolean vertical, int statusY, int statusWidth) {
        public static ToolbarLayout calculate(int width, int height, int left, int top, int imageWidth, int imageHeight, int count) {
            if (count < 1 || width < 1 || height < 1) return null;
            int buttonWidth = 52;
            int rowWidth = count * (buttonWidth + 2) - 2;
            if (rowWidth <= width - 4) {
                int x = Math.clamp(left, 2, width - rowWidth - 2);
                if (top >= 36) return new ToolbarLayout(x, top - 22, buttonWidth, false, top - 34, rowWidth);
                int bottom = top + imageHeight;
                if (bottom >= 0 && bottom + 36 <= height) return new ToolbarLayout(x, bottom + 2, buttonWidth, false, bottom + 24, rowWidth);
            }

            int columnHeight = count * 22 - 2;
            int right = left + imageWidth;
            if (columnHeight + 16 > height) return null;
            int sideWidth = Math.max(left, width - right) - 6;
            if (sideWidth < 42) return null;
            buttonWidth = Math.min(buttonWidth, sideWidth);
            int x = width - right >= left ? right + 4 : left - buttonWidth - 4;
            int y = Math.clamp(top, 14, height - columnHeight - 2);
            return new ToolbarLayout(x, y, buttonWidth, true, y - 12, buttonWidth);
        }
    }
}
