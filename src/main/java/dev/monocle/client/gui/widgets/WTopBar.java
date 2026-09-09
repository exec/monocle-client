/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.gui.widgets;

import dev.monocle.client.gui.renderer.GuiRenderer;
import dev.monocle.client.gui.tabs.Tab;
import dev.monocle.client.gui.tabs.TabScreen;
import dev.monocle.client.gui.tabs.Tabs;
import dev.monocle.client.gui.widgets.containers.WHorizontalList;
import dev.monocle.client.gui.widgets.pressable.WPressable;
import dev.monocle.client.utils.render.color.Color;
import net.minecraft.client.gui.screens.Screen;

import static dev.monocle.client.MonocleClient.mc;
import static com.mojang.blaze3d.platform.InputConstants.*;

public abstract class WTopBar extends WHorizontalList {
    protected abstract Color getButtonColor(boolean pressed, boolean hovered);

    protected abstract Color getNameColor();

    protected void renderButton(GuiRenderer renderer, WWidget button, boolean selected, boolean pressed, boolean hovered) {
        renderer.quad(button, getButtonColor(pressed || selected, hovered));
    }

    public WTopBar() {
        spacing = 0;
    }

    @Override
    public void init() {
        for (Tab tab : Tabs.get()) {
            add(new WTopBarButton(tab));
        }
    }

    protected class WTopBarButton extends WPressable {
        private final Tab tab;

        public WTopBarButton(Tab tab) {
            this.tab = tab;
        }

        @Override
        protected void onCalculateSize() {
            double pad = pad();

            width = pad + theme.textWidth(tab.name) + pad;
            height = pad + theme.textHeight() + pad;
        }

        @Override
        protected void onPressed(int button) {
            Screen screen = mc.gui.screen();

            if (!(screen instanceof TabScreen tabScreen) || tabScreen.tab != tab) {
                double mouseX = mc.mouseHandler.xpos();
                double mouseY = mc.mouseHandler.ypos();

                tab.openScreen(theme);
                grabOrReleaseMouse(mc.getWindow(), CURSOR_NORMAL, mouseX, mouseY);
            }
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            double pad = pad();
            boolean selected = mc.gui.screen() instanceof TabScreen tabScreen && tabScreen.tab == tab;
            renderButton(renderer, this, selected, pressed, mouseOver);
            renderer.text(tab.name, x + pad, y + pad, getNameColor(), false);
        }
    }
}
