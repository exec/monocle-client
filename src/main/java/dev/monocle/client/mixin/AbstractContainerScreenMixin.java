/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.monocle.client.gui.screens.InventoryManagerScreen.ToolbarLayout;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.misc.InventoryTweaks;
import dev.monocle.client.systems.modules.render.BetterTooltips;
import dev.monocle.client.systems.modules.render.ItemHighlight;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

import static dev.monocle.client.MonocleClient.mc;
import static com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin<T extends AbstractContainerMenu> extends Screen implements MenuAccess<T> {
    @Shadow
    protected Slot hoveredSlot;

    @Shadow
    protected int leftPos;
    @Shadow
    protected int topPos;
    @Shadow @Final
    protected int imageWidth;
    @Shadow @Final
    protected int imageHeight;

    @Unique private final List<Button> monocle$inventoryButtons = new ArrayList<>();
    @Unique private ToolbarLayout monocle$inventoryToolbar;
    @Unique private boolean monocle$toolbarPressed;

    @Shadow
    @Nullable
    protected abstract Slot getHoveredSlot(double x, double y);

    @Shadow
    public abstract @NonNull T getMenu();

    @Shadow
    private boolean doubleclick;

    @Shadow
    protected abstract void slotClicked(Slot slot, int slotId, int buttonNum, ContainerInput containerInput);

    @Shadow
    public abstract void onClose();

    public AbstractContainerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        InventoryTweaks invTweaks = Modules.get().get(InventoryTweaks.class);
        monocle$inventoryButtons.clear();
        monocle$inventoryToolbar = null;
        monocle$toolbarPressed = false;
        if (!invTweaks.isActive() || !invTweaks.showButtons()) return;
        boolean storage = invTweaks.canSteal(getMenu());
        if (!storage && !(getMenu() instanceof InventoryMenu)) return;

        if (storage) {
            monocle$inventoryButton("Refill", "Refill Loadout: take only missing saved quantities from this container.", () -> invTweaks.refill(getMenu()));
            monocle$inventoryButton("Deposit", "Deposit Excess: store surplus saved items, keeping the loadout and protected items.", () -> invTweaks.deposit(getMenu()));
        } else {
            monocle$inventoryButton("Arrange", "Arrange Hotbar: apply saved pins to the items you carry.", () -> invTweaks.arrange(getMenu()));
        }
        monocle$inventoryButton("Compact", "Compact Stacks: merge matching main-inventory stacks. Hotbar and container contents stay unchanged.", () -> invTweaks.compact(getMenu()));
        monocle$inventoryButton("Cancel", "Stop the current inventory operation. Clicking a slot or pressing a key also stops it.", invTweaks::cancelOperation);
        if (storage && invTweaks.showLegacyButtons()) {
            monocle$inventoryButton("Steal", "Legacy transfer: take all items allowed by the Steal filter, ignoring loadout quantities.", () -> invTweaks.steal(getMenu()));
            monocle$inventoryButton("Dump", "Legacy transfer: deposit items allowed by the Dump filter, ignoring loadout quantities.", () -> invTweaks.dump(getMenu()));
        }
    }

    @Unique
    private void monocle$inventoryButton(String label, String tooltip, Runnable action) {
        Button button = new Button.Builder(Component.literal(label), _ -> action.run())
            .pos(0, 0).size(52, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();
        button.visible = false;
        monocle$inventoryButtons.add(addRenderableWidget(button));
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void monocle$inventoryLayout(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (monocle$inventoryButtons.isEmpty()) return;
        InventoryTweaks manager = Modules.get().get(InventoryTweaks.class);
        // Recipe-book toggles can move the inventory without rebuilding the screen.
        monocle$inventoryToolbar = ToolbarLayout.calculate(width, height, leftPos, topPos, imageWidth, imageHeight, monocle$inventoryButtons.size());
        ToolbarLayout layout = monocle$inventoryToolbar;
        for (int i = 0; i < monocle$inventoryButtons.size(); i++) {
            Button button = monocle$inventoryButtons.get(i);
            button.visible = layout != null && manager.isActive() && manager.showButtons();
            if (layout != null) {
                button.setPosition(layout.x() + (layout.vertical() ? 0 : i * (layout.buttonWidth() + 2)), layout.y() + (layout.vertical() ? i * 22 : 0));
                button.setWidth(layout.buttonWidth());
            }
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void monocle$inventoryStatus(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (monocle$inventoryToolbar == null) return;
        InventoryTweaks manager = Modules.get().get(InventoryTweaks.class);
        if (!manager.isActive() || !manager.showButtons()) return;

        ToolbarLayout layout = monocle$inventoryToolbar;
        String status = manager.getStatus();
        String text = status;
        if (font.width(text) > layout.statusWidth() - 4) text = font.plainSubstrByWidth(text, layout.statusWidth() - font.width("…") - 4) + "…";
        graphics.fill(layout.x(), layout.statusY() - 1, layout.x() + layout.statusWidth(), layout.statusY() + font.lineHeight + 1, 0xDC15191F);
        graphics.text(font, text, layout.x() + 2, layout.statusY(), 0xFFE8D1A2);
        if (mouseX >= layout.x() && mouseX < layout.x() + layout.statusWidth() && mouseY >= layout.statusY() - 1 && mouseY <= layout.statusY() + font.lineHeight + 1)
            graphics.setTooltipForNextFrame(font, Component.literal(status), mouseX, mouseY);
    }

    @Inject(method = "slotClicked", at = @At("HEAD"))
    private void monocle$manualSlotInput(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
        Modules.get().get(InventoryTweaks.class).onManualInput();
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void monocle$containerClosed(CallbackInfo ci) {
        Modules.get().get(InventoryTweaks.class).cancelOperation();
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void monocle$toolbarReleased(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        // The native release path can treat a toolbar click as dropping the carried stack outside the menu.
        if (monocle$toolbarPressed) {
            monocle$toolbarPressed = false;
            super.mouseReleased(event);
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void monocle$toolbarDragged(MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        if (monocle$toolbarPressed) cir.setReturnValue(true);
    }

    // Inventory Tweaks
    @Inject(method = "mouseDragged", at = @At("TAIL"))
    private void onMouseDragged(MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != MOUSE_BUTTON_LEFT || doubleclick || !Modules.get().get(InventoryTweaks.class).mouseDragItemMove())
            return;

        Slot slot = getHoveredSlot(event.x(), event.y());
        if (slot != null && slot.hasItem() && mc.hasShiftDown())
            slotClicked(slot, slot.index, event.button(), ContainerInput.QUICK_MOVE);
    }

    // Middle click open
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void mouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        monocle$toolbarPressed = monocle$inventoryButtons.stream().anyMatch(button -> button.visible && button.isMouseOver(event.x(), event.y()));
        if (monocle$toolbarPressed) {
            if (event.button() == MOUSE_BUTTON_LEFT) super.mouseClicked(event, doubleClick);
            cir.setReturnValue(true);
            return;
        }
        if (getHoveredSlot(event.x(), event.y()) != null) Modules.get().get(InventoryTweaks.class).onManualInput();
        BetterTooltips tooltips = Modules.get().get(BetterTooltips.class);

        if (tooltips.shouldOpenContents(event) && hoveredSlot != null && !hoveredSlot.getItem().isEmpty() && getMenu().getCarried().isEmpty()) {
            if (tooltips.openContent(hoveredSlot.getItem())) {
                cir.setReturnValue(true);
            }
        }
    }

    // Keyboard input for middle click open
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void keyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        Modules.get().get(InventoryTweaks.class).onManualInput();
        BetterTooltips tooltips = Modules.get().get(BetterTooltips.class);

        if (tooltips.shouldOpenContents(event) && hoveredSlot != null && !hoveredSlot.getItem().isEmpty() && getMenu().getCarried().isEmpty()) {
            if (tooltips.openContent(hoveredSlot.getItem())) {
                cir.setReturnValue(true);
            }
        }
    }

    // Item Highlight
    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void onRenderSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        int color = Modules.get().get(ItemHighlight.class).getColor(slot.getItem());
        if (color != -1) graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color);
    }

    @ModifyReturnValue(method = "showTooltipWithItemInHand", at = @At("RETURN"))
    private boolean showTooltipWithItemInHand(boolean original, ItemStack item) {
        if (item.getTooltipImage().orElse(null) instanceof ClientTooltipComponent component) {
            return original || component.showTooltipWithItemInHand();
        }

        return original;
    }
}
