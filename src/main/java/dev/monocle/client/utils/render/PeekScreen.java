/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.utils.render;

import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.modules.render.BetterTooltips;
import dev.monocle.client.utils.Utils;
import dev.monocle.client.utils.render.color.Color;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.NonNull;
import com.mojang.blaze3d.platform.InputConstants;

import static dev.monocle.client.MonocleClient.mc;

public class PeekScreen extends AbstractContainerScreen<AbstractContainerMenu> {
    private final Identifier TEXTURE = Identifier.parse("textures/gui/container/shulker_box.png");
    private static final Identifier DOUBLE_TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private final ItemStack storageBlock;

    public PeekScreen(ItemStack storageBlock, ItemStack[] contents) {
        super(contents.length > 27
            ? ChestMenu.sixRows(0, mc.player.getInventory(), new SimpleContainer(contents))
            : new ShulkerBoxMenu(0, mc.player.getInventory(), new SimpleContainer(contents)), mc.player.getInventory(), storageBlock.getHoverName(), 176, contents.length > 27 ? 222 : 167);
        this.storageBlock = storageBlock;
        if (contents.length > 27) inventoryLabelY = imageHeight - 94;
    }

    @Override
    public boolean mouseClicked(@NonNull MouseButtonEvent click, boolean doubled) {
        BetterTooltips tooltips = Modules.get().get(BetterTooltips.class);

        if (tooltips.shouldOpenContents(click) && hoveredSlot != null && !hoveredSlot.getItem().isEmpty() && mc.player.containerMenu.getCarried().isEmpty()) {
            ItemStack itemStack = hoveredSlot.getItem();
            return tooltips.openContent(itemStack);
        }

        return false;
    }

    @Override
    public boolean mouseReleased(@NonNull MouseButtonEvent click) {
        return false;
    }

    @Override
    public boolean keyPressed(@NonNull KeyEvent input) {
        BetterTooltips tooltips = Modules.get().get(BetterTooltips.class);

        if (tooltips.shouldOpenContents(input) && hoveredSlot != null && !hoveredSlot.getItem().isEmpty() && mc.player.containerMenu.getCarried().isEmpty()) {
            ItemStack itemStack = hoveredSlot.getItem();
            if (tooltips.openContent(itemStack)) {
                return true;
            }
        }

        if (input.key() == InputConstants.KEY_ESCAPE || mc.options.keyInventory.matches(input)) {
            onClose();
            return true;
        }

        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        Color color = Utils.getShulkerColor(storageBlock);

        int i = (width - imageWidth) / 2;
        int j = (height - imageHeight) / 2;
        if (menu instanceof ChestMenu) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, DOUBLE_TEXTURE, i, j, 0, 0, imageWidth, 125, 256, 256, color.getPacked());
            graphics.blit(RenderPipelines.GUI_TEXTURED, DOUBLE_TEXTURE, i, j + 125, 0, 126, imageWidth, 96, 256, 256, color.getPacked());
            return;
        }
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, i, j, 0f, 0f, imageWidth, imageHeight, imageWidth, imageHeight, 256, 256, ARGB.colorFromFloat(color.a / 255f, color.r / 255f, color.g / 255f, color.b / 255f));
    }
}
